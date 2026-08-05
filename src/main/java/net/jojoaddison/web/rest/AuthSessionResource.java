package net.jojoaddison.web.rest;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import java.security.Principal;
import java.time.Instant;
import net.jojoaddison.service.RefreshTokenService;
import net.jojoaddison.service.RefreshTokenService.InvalidRefreshTokenException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Refresh-token endpoints for mobile clients.
 *
 * <p>{@code /refresh} and {@code /logout} are {@code permitAll} in
 * {@link net.jojoaddison.config.SecurityConfiguration} — necessarily, because the access token has
 * usually expired by the time they are called. They authorise on the refresh token itself.
 *
 * @see RefreshTokenService
 */
@RestController
@RequestMapping("/api/auth")
public class AuthSessionResource {

    private final Logger log = LoggerFactory.getLogger(AuthSessionResource.class);

    private final RefreshTokenService refreshTokenService;

    public AuthSessionResource(RefreshTokenService refreshTokenService) {
        this.refreshTokenService = refreshTokenService;
    }

    /** Exchanges a refresh token for a new access + refresh pair. 401 on anything suspicious. */
    @PostMapping("/refresh")
    public Mono<ResponseEntity<RefreshResponse>> refresh(@RequestBody RefreshRequest request, ServerWebExchange exchange) {
        return refreshTokenService
            .rotate(request.getRefreshToken(), remoteIp(exchange))
            .map(pair -> ResponseEntity.ok(new RefreshResponse(pair.accessToken(), pair.refreshToken(), pair.expiresInSeconds())))
            .onErrorResume(InvalidRefreshTokenException.class, e -> {
                log.debug("Refresh rejected: {}", e.getMessage());
                return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
            });
    }

    /**
     * Revokes the family the presented token belongs to.
     *
     * <p>Always 204, even for an unknown or malformed token — the response must not reveal whether
     * a given token existed.
     */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> logout(@RequestBody RefreshRequest request) {
        return refreshTokenService.revokeByToken(request.getRefreshToken(), RefreshTokenService.REASON_LOGOUT);
    }

    /** The caller's own live sessions. */
    @GetMapping("/sessions")
    public Flux<SessionDTO> sessions(Principal principal) {
        return refreshTokenService
            .activeSessions(principal.getName())
            .map(
                token ->
                    new SessionDTO(
                        token.getFamilyId(),
                        token.getClient(),
                        token.getDeviceId(),
                        token.getDeviceName(),
                        token.getIssuedAt(),
                        token.getLastUsedAt(),
                        token.getExpiresAt()
                    )
            );
    }

    /** Signs one of the caller's own devices out. 404 when it is not theirs or does not exist. */
    @DeleteMapping("/sessions/{familyId}")
    public Mono<ResponseEntity<Void>> revokeSession(@PathVariable String familyId, Principal principal) {
        return refreshTokenService
            .revokeOwnSession(principal.getName(), familyId)
            .map(revoked -> revoked ? ResponseEntity.noContent().<Void>build() : ResponseEntity.status(HttpStatus.NOT_FOUND).<Void>build());
    }

    private String remoteIp(ServerWebExchange exchange) {
        var address = exchange.getRequest().getRemoteAddress();
        return address == null ? "unknown" : address.getAddress().getHostAddress();
    }

    /** Request body for {@code /refresh} and {@code /logout}. */
    public static class RefreshRequest {

        @NotBlank
        private String refreshToken;

        @JsonProperty("refresh_token")
        public String getRefreshToken() {
            return refreshToken;
        }

        @JsonProperty("refresh_token")
        public void setRefreshToken(String refreshToken) {
            this.refreshToken = refreshToken;
        }
    }

    /** Mirrors the login response shape, so the client has one parser for both. */
    public static class RefreshResponse {

        private final String idToken;
        private final String refreshToken;
        private final long expiresIn;

        public RefreshResponse(String idToken, String refreshToken, long expiresIn) {
            this.idToken = idToken;
            this.refreshToken = refreshToken;
            this.expiresIn = expiresIn;
        }

        @JsonProperty("id_token")
        public String getIdToken() {
            return idToken;
        }

        @JsonProperty("refresh_token")
        public String getRefreshToken() {
            return refreshToken;
        }

        @JsonProperty("expires_in")
        public long getExpiresIn() {
            return expiresIn;
        }
    }

    /** A live session. Carries no token material. */
    public record SessionDTO(
        String id,
        String client,
        String deviceId,
        String deviceName,
        Instant issuedAt,
        Instant lastUsedAt,
        Instant expiresAt
    ) {}
}
