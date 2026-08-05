package net.jojoaddison.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import net.jojoaddison.config.ApplicationProperties;
import net.jojoaddison.domain.RefreshToken;
import net.jojoaddison.repository.RefreshTokenRepository;
import net.jojoaddison.repository.UserRepository;
import net.jojoaddison.security.jwt.TokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Issues, rotates and revokes refresh tokens.
 *
 * <p>Follows the OAuth 2.0 Security Best Current Practice for public clients: opaque tokens,
 * one-time use, rotation on every exchange, and <strong>reuse detection</strong> — presenting a
 * token that has already been exchanged revokes the entire family.
 *
 * <p>That last rule logs the legitimate device out too. This is intended: once a token has been
 * seen twice, either it leaked or the client is buggy, and the only safe reading is that the chain
 * is compromised. A forced re-login is a far cheaper failure than a silently shared session.
 *
 * <h3>What this deliberately does not do</h3>
 * Revoking a refresh token does <em>not</em> invalidate access tokens already minted from it.
 * {@code professionalservice} is stateless and holds no revocation list, and giving it one would
 * couple three separately-deployed stacks that share the signing key. The exposure window is
 * therefore one access-token lifetime (15 minutes by default) — short by construction, which is
 * the whole reason the mobile access token is short.
 */
@Service
public class RefreshTokenService {

    /** Revocation reasons, recorded on the row for audit. */
    public static final String REASON_LOGOUT = "LOGOUT";
    public static final String REASON_REUSE_DETECTED = "REUSE_DETECTED";
    public static final String REASON_USER_INACTIVE = "USER_INACTIVE";
    public static final String REASON_SESSION_LIMIT = "SESSION_LIMIT";

    /**
     * How long a revoked-but-unexpired row is kept before the TTL index reaps it. Without this
     * grace period a revoked token would be deleted immediately and a subsequent replay would look
     * like an unknown token rather than a detected reuse, losing the signal.
     */
    private static final Duration REVOKED_RETENTION = Duration.ofDays(7);

    private static final int TOKEN_BYTES = 16; // 128 bits per half

    private final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final TokenProvider tokenProvider;
    private final ApplicationProperties.Auth.Mobile mobileProperties;
    private final SecureRandom secureRandom = new SecureRandom();

    public RefreshTokenService(
        RefreshTokenRepository refreshTokenRepository,
        UserRepository userRepository,
        TokenProvider tokenProvider,
        ApplicationProperties applicationProperties
    ) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
        this.tokenProvider = tokenProvider;
        this.mobileProperties = applicationProperties.getAuth().getMobile();
    }

    /** The pair handed back to a mobile client on login and on every refresh. */
    public record TokenPair(String accessToken, String refreshToken, long expiresInSeconds) {}

    /** A presented refresh token, split into its lookup id and its secret. */
    private record PresentedToken(String id, String secret) {}

    public Duration accessTokenValidity() {
        return Duration.ofSeconds(mobileProperties.getAccessTokenValidityInSeconds());
    }

    /**
     * Issues the first token of a new family, after a successful password login.
     *
     * @param authorities pre-joined authority string, as it will appear in the {@code auth} claim
     */
    public Mono<TokenPair> issue(String login, String authorities, String client, String deviceId, String deviceName) {
        Instant now = Instant.now();
        String familyId = randomToken();

        return capSessions(login, now)
            .then(Mono.defer(() -> persistNewToken(login, familyId, client, deviceId, deviceName, now)))
            .map(secret -> toPair(login, authorities, secret));
    }

    /**
     * Exchanges a refresh token for a new pair.
     *
     * <p>Rejects with {@link InvalidRefreshTokenException} on anything suspicious, having first
     * revoked the family when the presented token looks replayed.
     */
    public Mono<TokenPair> rotate(String presented, String remoteIp) {
        PresentedToken parsed;
        try {
            parsed = parse(presented);
        } catch (IllegalArgumentException e) {
            return Mono.error(new InvalidRefreshTokenException("Malformed refresh token"));
        }

        Instant now = Instant.now();

        return refreshTokenRepository
            .findById(parsed.id())
            .switchIfEmpty(Mono.error(new InvalidRefreshTokenException("Unknown refresh token")))
            .flatMap(stored -> {
                if (!matches(stored.getTokenHash(), parsed.secret())) {
                    // Right id, wrong secret. Do not touch the family: a guessed id must not become
                    // a way to log somebody else out.
                    return Mono.error(new InvalidRefreshTokenException("Refresh token does not match"));
                }
                if (stored.getRotatedAt() != null) {
                    log.warn(
                        "Refresh token reuse detected for login '{}' family '{}' from {} — revoking the family",
                        stored.getLogin(),
                        stored.getFamilyId(),
                        remoteIp
                    );
                    return revokeFamily(stored.getFamilyId(), REASON_REUSE_DETECTED).then(
                        Mono.error(new InvalidRefreshTokenException("Refresh token already used"))
                    );
                }
                if (stored.getRevokedAt() != null) {
                    return Mono.error(new InvalidRefreshTokenException("Refresh token revoked"));
                }
                if (stored.getExpiresAt() == null || !stored.getExpiresAt().isAfter(now)) {
                    return Mono.error(new InvalidRefreshTokenException("Refresh token expired"));
                }
                return rotateVerified(stored, now, remoteIp);
            });
    }

    /** Revokes the family the presented token belongs to. Silent about unknown tokens. */
    public Mono<Void> revokeByToken(String presented, String reason) {
        PresentedToken parsed;
        try {
            parsed = parse(presented);
        } catch (IllegalArgumentException e) {
            return Mono.empty();
        }
        return refreshTokenRepository
            .findById(parsed.id())
            .filter(stored -> matches(stored.getTokenHash(), parsed.secret()))
            .flatMap(stored -> revokeFamily(stored.getFamilyId(), reason))
            .then();
    }

    public Mono<Void> revokeFamily(String familyId, String reason) {
        Instant now = Instant.now();
        return refreshTokenRepository
            .findAllByFamilyId(familyId)
            .filter(token -> token.getRevokedAt() == null)
            .flatMap(token -> refreshTokenRepository.save(markRevoked(token, now, reason)))
            .then();
    }

    public Mono<Void> revokeAllForLogin(String login, String reason) {
        Instant now = Instant.now();
        return refreshTokenRepository
            .findAllByLogin(login)
            .filter(token -> token.getRevokedAt() == null)
            .flatMap(token -> refreshTokenRepository.save(markRevoked(token, now, reason)))
            .then();
    }

    /** Live sessions for a login, oldest first. */
    public Flux<RefreshToken> activeSessions(String login) {
        return refreshTokenRepository.findAllByLoginAndRevokedAtIsNullAndRotatedAtIsNullAndExpiresAtAfterOrderByIssuedAtAsc(
            login,
            Instant.now()
        );
    }

    /** Revokes one of the caller's own sessions by family id. */
    public Mono<Boolean> revokeOwnSession(String login, String familyId) {
        return refreshTokenRepository
            .findAllByFamilyId(familyId)
            .collectList()
            .flatMap(tokens -> {
                if (tokens.isEmpty() || tokens.stream().anyMatch(token -> !login.equals(token.getLogin()))) {
                    // Either it does not exist or it is not theirs. Same answer for both, so the
                    // endpoint cannot be used to probe for other people's session ids.
                    return Mono.just(false);
                }
                return revokeFamily(familyId, REASON_LOGOUT).thenReturn(true);
            });
    }

    // ------------------------------------------------------------------ internals

    private Mono<TokenPair> rotateVerified(RefreshToken stored, Instant now, String remoteIp) {
        return userRepository
            .findOneByLogin(stored.getLogin())
            .switchIfEmpty(
                revokeAllForLogin(stored.getLogin(), REASON_USER_INACTIVE).then(
                    Mono.error(new InvalidRefreshTokenException("User no longer exists"))
                )
            )
            .flatMap(user -> {
                if (!user.isActivated()) {
                    // Deactivation propagates here rather than at the access token, which is why the
                    // access token is short-lived: worst case the user keeps access for its lifetime.
                    return revokeAllForLogin(stored.getLogin(), REASON_USER_INACTIVE).then(
                        Mono.error(new InvalidRefreshTokenException("User is not activated"))
                    );
                }

                // Authorities are re-read on every rotation, so a role change reaches the device
                // within one access-token lifetime without any push from the server.
                String authorities = user
                    .getAuthorities()
                    .stream()
                    .map(authority -> authority.getName())
                    .sorted()
                    .reduce((a, b) -> a + " " + b)
                    .orElse("");

                return persistNewToken(
                    stored.getLogin(),
                    stored.getFamilyId(),
                    stored.getClient(),
                    stored.getDeviceId(),
                    stored.getDeviceName(),
                    now
                ).flatMap(successor -> {
                    stored.setRotatedAt(now);
                    stored.setReplacedById(successor.tokenId());
                    stored.setLastUsedAt(now);
                    stored.setLastUsedIp(remoteIp);
                    // Keep the spent row around briefly so a replay is still detectable.
                    stored.setExpiresAt(now.plus(REVOKED_RETENTION));
                    return refreshTokenRepository.save(stored).thenReturn(toPair(stored.getLogin(), authorities, successor));
                });
            });
    }

    /** A freshly minted token: what the client is given, plus the id we stored it under. */
    private record NewToken(String tokenId, String presentable) {}

    private Mono<NewToken> persistNewToken(String login, String familyId, String client, String deviceId, String deviceName, Instant now) {
        String tokenId = randomToken();
        String secret = randomToken();

        RefreshToken token = new RefreshToken();
        token.setId(tokenId);
        token.setTokenHash(sha256(secret));
        token.setFamilyId(familyId);
        token.setLogin(login);
        token.setClient(client);
        token.setDeviceId(deviceId);
        token.setDeviceName(deviceName);
        token.setIssuedAt(now);
        token.setExpiresAt(now.plus(Duration.ofDays(mobileProperties.getRefreshTokenValidityInDays())));

        return refreshTokenRepository.save(token).thenReturn(new NewToken(tokenId, tokenId + "." + secret));
    }

    private TokenPair toPair(String login, String authorities, NewToken newToken) {
        Duration validity = accessTokenValidity();
        String accessToken = tokenProvider.createAccessToken(login, authorities, validity);
        return new TokenPair(accessToken, newToken.presentable(), validity.toSeconds());
    }

    /**
     * Caps concurrent sessions per login by revoking the oldest families. Without this a user who
     * never signs out accumulates a live credential per install, forever.
     */
    private Mono<Void> capSessions(String login, Instant now) {
        int max = mobileProperties.getMaxSessionsPerLogin();
        if (max <= 0) {
            return Mono.empty();
        }
        return refreshTokenRepository
            .findAllByLoginAndRevokedAtIsNullAndRotatedAtIsNullAndExpiresAtAfterOrderByIssuedAtAsc(login, now)
            .collectList()
            .flatMap(active -> {
                // The new session is about to be added, so keep at most max-1 of the existing ones.
                int excess = active.size() - (max - 1);
                if (excess <= 0) {
                    return Mono.empty();
                }
                List<RefreshToken> oldest = active.subList(0, excess);
                log.info("Session limit reached for login '{}' — revoking {} oldest session(s)", login, oldest.size());
                return Flux.fromIterable(oldest).flatMap(token -> revokeFamily(token.getFamilyId(), REASON_SESSION_LIMIT)).then();
            });
    }

    private RefreshToken markRevoked(RefreshToken token, Instant now, String reason) {
        token.setRevokedAt(now);
        token.setRevokedReason(reason);
        if (token.getExpiresAt() == null || token.getExpiresAt().isAfter(now.plus(REVOKED_RETENTION))) {
            token.setExpiresAt(now.plus(REVOKED_RETENTION));
        }
        return token;
    }

    private PresentedToken parse(String presented) {
        if (presented == null || presented.isBlank()) {
            throw new IllegalArgumentException("empty");
        }
        int separator = presented.indexOf('.');
        if (separator <= 0 || separator == presented.length() - 1) {
            throw new IllegalArgumentException("malformed");
        }
        return new PresentedToken(presented.substring(0, separator), presented.substring(separator + 1));
    }

    private boolean matches(String storedHash, String presentedSecret) {
        // Constant-time: a timing oracle here would let an attacker recover the secret byte by byte.
        return MessageDigest.isEqual(storedHash.getBytes(StandardCharsets.UTF_8), sha256(presentedSecret).getBytes(StandardCharsets.UTF_8));
    }

    private String randomToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS; this cannot happen on a conformant JVM.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Signals that a presented refresh token cannot be exchanged. Always answered with a 401. */
    public static class InvalidRefreshTokenException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public InvalidRefreshTokenException(String message) {
            super(message);
        }
    }
}
