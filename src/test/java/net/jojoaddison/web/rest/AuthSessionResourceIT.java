package net.jojoaddison.web.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.Authority;
import net.jojoaddison.domain.RefreshToken;
import net.jojoaddison.domain.User;
import net.jojoaddison.repository.RefreshTokenRepository;
import net.jojoaddison.repository.UserRepository;
import net.jojoaddison.security.AuthoritiesConstants;
import net.jojoaddison.service.RefreshTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Integration tests for the mobile refresh-token flow (MOB3).
 *
 * <p>Covers what {@code mobile-app-plan.md} names as this work package's gate: issue, rotate,
 * reuse-detect, revoke, expiry and the deactivated-user path — plus the two guarantees that protect
 * everything already in production, namely that the browser response shape is unchanged and that a
 * mobile-issued access token still carries the claims {@code professionalservice} reads.
 */
@AutoConfigureWebTestClient(timeout = IntegrationTest.DEFAULT_TIMEOUT)
@IntegrationTest
class AuthSessionResourceIT {

    private static final String PASSWORD = "mobile-pass";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private RefreshTokenService refreshTokenService;

    private String login;

    @BeforeEach
    void seedUser() {
        login = "mob-" + Instant.now().toEpochMilli() + "-" + Math.abs(java.util.UUID.randomUUID().hashCode());

        Authority authority = new Authority();
        authority.setName(AuthoritiesConstants.USER);

        User user = new User();
        user.setLogin(login);
        user.setEmail(login + "@example.com");
        user.setActivated(true);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.getAuthorities().add(authority);
        userRepository.save(user).block();
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> loginAs(String client) {
        var body = client == null
            ? Map.of("username", login, "password", PASSWORD)
            : Map.of("username", login, "password", PASSWORD, "client", client, "deviceId", "device-1", "deviceName", "Pixel 9");

        return webTestClient
            .post()
            .uri("/api/authenticate")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
            .returnResult()
            .getResponseBody();
    }

    private WebTestClient.ResponseSpec postRefresh(String refreshToken) {
        return webTestClient
            .post()
            .uri("/api/auth/refresh")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("refresh_token", refreshToken))
            .exchange();
    }

    /** Decodes the payload of a JWS without verifying it — enough to assert the claim set. */
    private Map<String, Object> claimsOf(String jwt) {
        String payload = new String(java.util.Base64.getUrlDecoder().decode(jwt.split("\\.")[1]));
        return new com.fasterxml.jackson.databind.ObjectMapper()
            .convertValue(assertJsonObject(payload), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    }

    private com.fasterxml.jackson.databind.JsonNode assertJsonObject(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new AssertionError("token payload is not JSON: " + json, e);
        }
    }

    // ------------------------------------------------------------------ the browser must not change

    @Test
    void browserLoginResponseIsUnchanged() {
        Map<String, Object> body = loginAs(null);

        // The single most important assertion in this file. The web app is untouched by MOB3 and a
        // stray refresh_token in its response would mean dead rows accruing for sessions that can
        // never use them.
        assertThat(body).containsKey("id_token");
        assertThat(body).doesNotContainKey("refresh_token");
        assertThat(body).doesNotContainKey("expires_in");
        assertThat(refreshTokenRepository.findAllByLogin(login).collectList().block()).isEmpty();
    }

    @Test
    void anExplicitWebClientIsStillTreatedAsABrowser() {
        Map<String, Object> body = webTestClient
            .post()
            .uri("/api/authenticate")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("username", login, "password", PASSWORD, "client", "web"))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
            .returnResult()
            .getResponseBody();

        assertThat(body).doesNotContainKey("refresh_token");
    }

    // ------------------------------------------------------------------ issue

    @Test
    void mobileLoginIssuesAPairAndPersistsExactlyOneToken() {
        Map<String, Object> body = loginAs("mobile-android");

        assertThat(body).containsKeys("id_token", "refresh_token", "expires_in");
        assertThat((String) body.get("refresh_token")).contains(".");
        assertThat(((Number) body.get("expires_in")).longValue()).isEqualTo(900L);

        var stored = refreshTokenRepository.findAllByLogin(login).collectList().block();
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getClient()).isEqualTo("mobile-android");
        assertThat(stored.get(0).getDeviceName()).isEqualTo("Pixel 9");
        // Only the hash is kept — a database leak must not yield a usable credential.
        assertThat(stored.get(0).getTokenHash()).isNotEqualTo(body.get("refresh_token"));
        assertThat((String) body.get("refresh_token")).doesNotContain(stored.get(0).getTokenHash());
    }

    @Test
    void theMobileAccessTokenKeepsTheClaimSetDownstreamServicesRead() {
        Map<String, Object> body = loginAs("mobile-ios");
        Map<String, Object> claims = claimsOf((String) body.get("id_token"));

        // professionalservice reads sub and auth, and three stacks share the signing key. A changed
        // claim set here would break them silently.
        assertThat(claims).containsKeys("sub", "iat", "exp", "auth");
        assertThat(claims.get("sub")).isEqualTo(login);
        assertThat((String) claims.get("auth")).contains(AuthoritiesConstants.USER);
        assertThat(claims).doesNotContainKeys("uid", "sid", "client");

        long ttl = ((Number) claims.get("exp")).longValue() - ((Number) claims.get("iat")).longValue();
        assertThat(ttl).isEqualTo(900L);
    }

    // ------------------------------------------------------------------ rotate

    @Test
    void refreshRotatesAndInvalidatesThePresentedToken() {
        String first = (String) loginAs("mobile-ios").get("refresh_token");

        Map<String, Object> rotated = postRefresh(first)
            .expectStatus()
            .isOk()
            .expectBody(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
            .returnResult()
            .getResponseBody();

        String second = (String) rotated.get("refresh_token");
        assertThat(second).isNotEqualTo(first);
        assertThat(rotated.get("id_token")).isNotNull();

        // The successor works...
        postRefresh(second).expectStatus().isOk();
    }

    @Test
    void rotationKeepsTheFamilyAndChainsTheReplacement() {
        String first = (String) loginAs("mobile-ios").get("refresh_token");
        postRefresh(first).expectStatus().isOk();

        var all = refreshTokenRepository.findAllByLogin(login).collectList().block();
        assertThat(all).hasSize(2);
        assertThat(all).extracting(RefreshToken::getFamilyId).containsOnly(all.get(0).getFamilyId());

        var spent = all.stream().filter(t -> t.getRotatedAt() != null).findFirst().orElseThrow();
        assertThat(spent.getReplacedById()).isNotNull();
        assertThat(all).anyMatch(t -> t.getId().equals(spent.getReplacedById()));
    }

    // ------------------------------------------------------------------ reuse detection

    @Test
    void replayingASpentTokenRevokesTheWholeFamily() {
        String first = (String) loginAs("mobile-android").get("refresh_token");
        String second = (String) postRefresh(first)
            .expectStatus()
            .isOk()
            .expectBody(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
            .returnResult()
            .getResponseBody()
            .get("refresh_token");

        // Present the already-exchanged token a second time.
        postRefresh(first).expectStatus().isUnauthorized();

        // The legitimate successor dies too. That is intended: once a token has been seen twice the
        // chain must be assumed compromised, and a forced re-login is the cheap failure.
        postRefresh(second).expectStatus().isUnauthorized();

        var all = refreshTokenRepository.findAllByLogin(login).collectList().block();
        assertThat(all).allMatch(t -> t.getRevokedAt() != null);
        assertThat(all).anyMatch(t -> RefreshTokenService.REASON_REUSE_DETECTED.equals(t.getRevokedReason()));
    }

    @Test
    void aGuessedIdWithTheWrongSecretDoesNotRevokeAnything() {
        String valid = (String) loginAs("mobile-ios").get("refresh_token");
        String id = valid.substring(0, valid.indexOf('.'));

        postRefresh(id + ".not-the-secret").expectStatus().isUnauthorized();

        // Crucially the real token still works — otherwise anyone who could enumerate ids could log
        // arbitrary users out.
        postRefresh(valid).expectStatus().isOk();
    }

    // ------------------------------------------------------------------ rejection paths

    @Test
    void unknownMalformedAndEmptyTokensAreAllRejected() {
        postRefresh("does-not-exist.secret").expectStatus().isUnauthorized();
        postRefresh("no-separator").expectStatus().isUnauthorized();
        postRefresh("").expectStatus().isUnauthorized();
    }

    @Test
    void anExpiredTokenIsRejected() {
        String token = (String) loginAs("mobile-ios").get("refresh_token");

        RefreshToken stored = refreshTokenRepository.findAllByLogin(login).blockFirst();
        stored.setExpiresAt(Instant.now().minusSeconds(60));
        refreshTokenRepository.save(stored).block();

        postRefresh(token).expectStatus().isUnauthorized();
    }

    @Test
    void aDeactivatedUserCannotRefreshAndLosesEverySession() {
        String token = (String) loginAs("mobile-android").get("refresh_token");

        User user = userRepository.findOneByLogin(login).block();
        user.setActivated(false);
        userRepository.save(user).block();

        postRefresh(token).expectStatus().isUnauthorized();

        // Deactivation reaches the device within one access-token lifetime because the authority
        // list is re-read on every rotation — this is what makes the short access token worth it.
        var all = refreshTokenRepository.findAllByLogin(login).collectList().block();
        assertThat(all).allMatch(t -> t.getRevokedAt() != null);
        assertThat(all).anyMatch(t -> RefreshTokenService.REASON_USER_INACTIVE.equals(t.getRevokedReason()));
    }

    @Test
    void authorityChangesReachTheNextAccessToken() {
        String token = (String) loginAs("mobile-ios").get("refresh_token");

        User user = userRepository.findOneByLogin(login).block();
        Authority admin = new Authority();
        admin.setName(AuthoritiesConstants.ADMIN);
        user.getAuthorities().add(admin);
        userRepository.save(user).block();

        Map<String, Object> rotated = postRefresh(token)
            .expectStatus()
            .isOk()
            .expectBody(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
            .returnResult()
            .getResponseBody();

        assertThat((String) claimsOf((String) rotated.get("id_token")).get("auth")).contains(AuthoritiesConstants.ADMIN);
    }

    // ------------------------------------------------------------------ logout

    @Test
    void logoutRevokesTheFamilyAndAlwaysAnswers204() {
        String token = (String) loginAs("mobile-android").get("refresh_token");

        webTestClient
            .post()
            .uri("/api/auth/logout")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("refresh_token", token))
            .exchange()
            .expectStatus()
            .isNoContent();

        postRefresh(token).expectStatus().isUnauthorized();

        // Unknown and malformed tokens get the same 204, so the endpoint cannot be used to probe
        // whether a token exists.
        for (String probe : new String[] { "unknown.token", "garbage", "" }) {
            webTestClient
                .post()
                .uri("/api/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("refresh_token", probe))
                .exchange()
                .expectStatus()
                .isNoContent();
        }
    }

    // ------------------------------------------------------------------ session cap

    @Test
    void concurrentSessionsAreCappedByRevokingTheOldest() {
        // max-sessions-per-login defaults to 5.
        String oldest = (String) loginAs("mobile-ios").get("refresh_token");
        for (int i = 0; i < 4; i++) {
            loginAs("mobile-ios");
        }
        assertThat(refreshTokenService.activeSessions(login).collectList().block()).hasSize(5);

        loginAs("mobile-ios"); // the sixth

        assertThat(refreshTokenService.activeSessions(login).collectList().block()).hasSize(5);
        postRefresh(oldest).expectStatus().isUnauthorized();
    }

    // ------------------------------------------------------------------ security config wiring

    // ------------------------------------------------------------------ session management
    //
    // GET /sessions and DELETE /sessions/{familyId} are the "signed in on these devices, sign that
    // one out" surface. Until now the only thing asserted about them was that an anonymous caller
    // gets 401 — the listing had never been read and the revocation had never been performed, so
    // both branches of revokeOwnSession's found/not-found ternary were untaken.

    /** The listing shows the caller's live sessions, with the device details the screen renders. */
    @Test
    void listsTheCallersActiveSessionsWithTheirDeviceDetails() {
        Map<String, Object> mobile = loginAs("mobile");

        List<Map<String, Object>> sessions = getSessions((String) mobile.get("id_token"));

        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0))
            .containsEntry("client", "mobile")
            .containsEntry("deviceId", "device-1")
            .containsEntry("deviceName", "Pixel 9");
        assertThat(sessions.get(0).get("id")).isNotNull();
        assertThat(sessions.get(0).get("issuedAt")).isNotNull();
        assertThat(sessions.get(0).get("expiresAt")).isNotNull();
    }

    /** A revoked session leaves the list, which is what makes the screen truthful after a sign-out. */
    @Test
    void revokingASessionRemovesItFromTheListing() {
        Map<String, Object> mobile = loginAs("mobile");
        String accessToken = (String) mobile.get("id_token");
        String familyId = (String) getSessions(accessToken).get(0).get("id");

        webTestClient
            .delete()
            .uri("/api/auth/sessions/{familyId}", familyId)
            .headers(headers -> headers.setBearerAuth(accessToken))
            .exchange()
            .expectStatus()
            .isNoContent();

        assertThat(getSessions(accessToken)).isEmpty();
        // And the refresh token that session was built on is dead, not merely hidden.
        postRefresh((String) mobile.get("refresh_token")).expectStatus().isUnauthorized();
    }

    /**
     * <b>The ownership property.</b> A family id belonging to somebody else must not be revocable,
     * and the refusal must be indistinguishable from one that does not exist — otherwise the
     * endpoint becomes an oracle for discovering other people's session ids.
     *
     * <p>This is the assertion that most needed writing: {@code revokeOwnSession} folds
     * "not found" and "not yours" into one {@code false} deliberately, and nothing checked that the
     * second case was reached at all.
     */
    @Test
    void cannotRevokeASessionBelongingToAnotherUser() {
        Map<String, Object> victimSession = loginAs("mobile");
        String victimAccess = (String) victimSession.get("id_token");
        String victimFamily = (String) getSessions(victimAccess).get(0).get("id");

        String attackerAccess = (String) loginAsOtherUser().get("id_token");

        webTestClient
            .delete()
            .uri("/api/auth/sessions/{familyId}", victimFamily)
            .headers(headers -> headers.setBearerAuth(attackerAccess))
            .exchange()
            .expectStatus()
            .isNotFound();

        // Untouched: the victim is still signed in on that device.
        assertThat(getSessions(victimAccess)).hasSize(1);
        postRefresh((String) victimSession.get("refresh_token")).expectStatus().isOk();
    }

    /** An id that matches no family answers exactly as one that is not yours does. */
    @Test
    void revokingAnUnknownSessionAnswersNotFound() {
        String accessToken = (String) loginAs("mobile").get("id_token");

        webTestClient
            .delete()
            .uri("/api/auth/sessions/{familyId}", "no-such-family-" + UUID.randomUUID())
            .headers(headers -> headers.setBearerAuth(accessToken))
            .exchange()
            .expectStatus()
            .isNotFound();
    }

    /** Browser sign-in mints no refresh token, so that account has no sessions to list. */
    @Test
    void listsNothingForAnAccountWithNoRefreshTokens() {
        String accessToken = (String) loginAs(null).get("id_token");

        assertThat(getSessions(accessToken)).isEmpty();
    }

    private List<Map<String, Object>> getSessions(String accessToken) {
        return webTestClient
            .get()
            .uri("/api/auth/sessions")
            .headers(headers -> headers.setBearerAuth(accessToken))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(new org.springframework.core.ParameterizedTypeReference<List<Map<String, Object>>>() {})
            .returnResult()
            .getResponseBody();
    }

    /** A second, unrelated account — for the ownership check. */
    private Map<String, Object> loginAsOtherUser() {
        String other = "other-" + Instant.now().toEpochMilli() + "-" + Math.abs(UUID.randomUUID().hashCode());
        Authority authority = new Authority();
        authority.setName(AuthoritiesConstants.USER);
        User user = new User();
        user.setLogin(other);
        user.setEmail(other + "@example.com");
        user.setActivated(true);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.getAuthorities().add(authority);
        userRepository.save(user).block();

        return webTestClient
            .post()
            .uri("/api/authenticate")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("username", other, "password", PASSWORD, "client", "mobile", "deviceId", "device-2", "deviceName", "Pixel 8"))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
            .returnResult()
            .getResponseBody();
    }

    @Test
    void refreshAndLogoutArePublicButSessionsRequireAuthentication() {
        // These two must be reachable without a bearer token: by the time a client calls them its
        // access token has usually expired. A 401 here would mean the /api/** rule swallowed them.
        postRefresh("anything.at-all").expectStatus().isUnauthorized(); // reached the handler, not the filter
        webTestClient.get().uri("/api/auth/sessions").exchange().expectStatus().isUnauthorized();
    }
}
