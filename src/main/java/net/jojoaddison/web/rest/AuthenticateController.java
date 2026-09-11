package net.jojoaddison.web.rest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import java.security.Principal;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import net.jojoaddison.management.SecurityMetersService;
import net.jojoaddison.security.jwt.TokenProvider;
import net.jojoaddison.service.RefreshTokenService;
import net.jojoaddison.web.rest.vm.LoginVM;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Controller to authenticate users.
 */
@RestController
@RequestMapping("/api")
public class AuthenticateController {

    private final Logger log = LoggerFactory.getLogger(AuthenticateController.class);

    @Value("${jhipster.security.authentication.jwt.token-validity-in-seconds:0}")
    private long tokenValidityInSeconds;

    @Value("${jhipster.security.authentication.jwt.token-validity-in-seconds-for-remember-me:0}")
    private long tokenValidityInSecondsForRememberMe;

    private final ReactiveAuthenticationManager authenticationManager;
    private final TokenProvider tokenProvider;
    private final RefreshTokenService refreshTokenService;
    private final SecurityMetersService metersService;

    public AuthenticateController(
        ReactiveAuthenticationManager authenticationManager,
        TokenProvider tokenProvider,
        RefreshTokenService refreshTokenService,
        SecurityMetersService metersService
    ) {
        this.authenticationManager = authenticationManager;
        this.tokenProvider = tokenProvider;
        this.refreshTokenService = refreshTokenService;
        this.metersService = metersService;
    }

    /**
     * {@code POST /authenticate} : authenticate and issue a token.
     *
     * <p>Two shapes come out of here, decided by {@link LoginVM#isMobileClient()}:
     *
     * <ul>
     *   <li><strong>Browser</strong> (no {@code client} in the body) — exactly what this endpoint
     *       has always returned: {@code {"id_token": ...}} valid 24 h, or 30 days with remember-me,
     *       plus the {@code Authorization} response header. Byte-identical, deliberately: the web
     *       app is unchanged by this work and must stay that way.
     *   <li><strong>Mobile</strong> — a short-lived access token plus a rotating refresh token and
     *       {@code expires_in}. Lifetimes come from {@code application.auth.mobile.*}, a separate
     *       namespace from the JHipster JWT properties so the browser path cannot regress.
     * </ul>
     *
     * <p><strong>Both outcomes are counted, and the counting sits inside the {@code flatMap} on purpose.</strong>
     * A body that fails {@code @Valid} errors on {@code loginVM} itself, before this point — that is a malformed
     * request, not a sign-in that went wrong, and counting it would put every bad client request on the outage line.
     * See {@code docs/backlog.md} item 96 and {@link #trackLoginFailure}.</p>
     */
    @PostMapping("/authenticate")
    public Mono<ResponseEntity<JWTToken>> authorize(@Valid @RequestBody Mono<LoginVM> loginVM) {
        return loginVM.flatMap(login ->
            authenticationManager
                .authenticate(new UsernamePasswordAuthenticationToken(login.getUsername(), login.getPassword()))
                .flatMap(auth -> login.isMobileClient() ? mobileResponse(auth, login) : browserResponse(auth, login))
                // Success is "the caller got a token", not "the password matched": the mobile path still has a
                // refresh token to persist after the credential is accepted, and a sign-in that dies there is not
                // one the clinician got the benefit of.
                .doOnNext(response -> metersService.trackLoginSuccess())
                .doOnError(this::trackLoginFailure));
    }

    /**
     * Splits a failed sign-in into <em>refused</em> and <em>could not ask</em>.
     *
     * <p>The distinction is the point of the meter, and it is the one {@code docs/backlog.md} item 83 was raised
     * about at a different site: a wall of "wrong password" lines that turns out to be an unreachable user store
     * costs an operator the whole of an outage before anyone questions the diagnosis. Here the two states are two
     * series, so nothing has to be questioned.</p>
     *
     * <p>The classification is Spring Security's own rather than a guess about exception types.
     * {@code AuthenticationServiceException} is documented as the failure <em>"due to a system problem"</em> — the
     * user store could not be consulted — so it is separated from its siblings even though it is an
     * {@code AuthenticationException}, and anything that is not an {@code AuthenticationException} at all is the same
     * statement arriving untyped.</p>
     *
     * <p><strong>Measured, in {@code LoginOutcomeMetersUnitTest}, which drives the real
     * {@code UserDetailsRepositoryReactiveAuthenticationManager}:</strong> that manager does <em>not</em> wrap, so a
     * dead Mongo arrives here as a bare {@code DataAccessResourceFailureException} and it is the untyped arm that
     * fires. The {@code AuthenticationServiceException} clause is therefore defensive — it costs nothing and it is
     * what a future Spring, or a different manager, would use to say the same thing.</p>
     */
    private void trackLoginFailure(Throwable error) {
        if (error instanceof AuthenticationException && !(error instanceof AuthenticationServiceException)) {
            metersService.trackLoginRefused();
        } else {
            metersService.trackLoginUnavailable();
            log.error("Could not answer a sign-in attempt", error);
        }
    }

    private Mono<ResponseEntity<JWTToken>> browserResponse(Authentication authentication, LoginVM login) {
        return Mono.fromCallable(() -> this.createToken(authentication, login.isRememberMe())).map(jwt -> {
            HttpHeaders httpHeaders = new HttpHeaders();
            httpHeaders.setBearerAuth(jwt);
            return new ResponseEntity<>(new JWTToken(jwt), httpHeaders, HttpStatus.OK);
        });
    }

    private Mono<ResponseEntity<JWTToken>> mobileResponse(Authentication authentication, LoginVM login) {
        String authorities = tokenProvider.authorityString(authentication.getAuthorities());
        return refreshTokenService
            .issue(
                authentication.getName(),
                TokenProvider.uidOf(authentication),
                authorities,
                login.getClient(),
                login.getDeviceId(),
                login.getDeviceName()
            )
            .map(pair -> {
                HttpHeaders httpHeaders = new HttpHeaders();
                httpHeaders.setBearerAuth(pair.accessToken());
                JWTToken body = new JWTToken(pair.accessToken(), pair.refreshToken(), pair.expiresInSeconds());
                return new ResponseEntity<>(body, httpHeaders, HttpStatus.OK);
            });
    }

    /**
     * {@code GET /authenticate} : check if the user is authenticated, and return its login.
     *
     * @param request the HTTP request.
     * @return the login if the user is authenticated.
     */
    @GetMapping("/authenticate")
    public Mono<String> isAuthenticated(ServerWebExchange request) {
        log.debug("REST request to check if the current user is authenticated");
        return request.getPrincipal().map(Principal::getName);
    }

    /**
     * Mints a browser access token with the classic JHipster lifetimes.
     *
     * <p>Kept as a public method on this controller because {@code AuthenticateControllerIT} and
     * the Kafka resource tests call it directly. The minting itself now lives in
     * {@link TokenProvider} so that the refresh path — which has no {@link Authentication} — can
     * produce an identical token.
     */
    public String createToken(Authentication authentication, boolean rememberMe) {
        long seconds = rememberMe ? this.tokenValidityInSecondsForRememberMe : this.tokenValidityInSeconds;
        return tokenProvider.createAccessToken(authentication, Duration.of(seconds, ChronoUnit.SECONDS));
    }

    /**
     * Object to return as body in JWT Authentication.
     *
     * <p>{@code refresh_token} and {@code expires_in} are omitted entirely when null, so the
     * browser response keeps exactly the fields it always had.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    static class JWTToken {

        private String idToken;
        private final String refreshToken;
        private final Long expiresIn;

        JWTToken(String idToken) {
            this(idToken, null, null);
        }

        JWTToken(String idToken, String refreshToken, Long expiresIn) {
            this.idToken = idToken;
            this.refreshToken = refreshToken;
            this.expiresIn = expiresIn;
        }

        @JsonProperty("id_token")
        String getIdToken() {
            return idToken;
        }

        void setIdToken(String idToken) {
            this.idToken = idToken;
        }

        @JsonProperty("refresh_token")
        String getRefreshToken() {
            return refreshToken;
        }

        @JsonProperty("expires_in")
        Long getExpiresIn() {
            return expiresIn;
        }
    }
}
