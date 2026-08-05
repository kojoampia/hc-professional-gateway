package net.jojoaddison.web.rest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import java.security.Principal;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import net.jojoaddison.security.jwt.TokenProvider;
import net.jojoaddison.service.RefreshTokenService;
import net.jojoaddison.web.rest.vm.LoginVM;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
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

    public AuthenticateController(
        ReactiveAuthenticationManager authenticationManager,
        TokenProvider tokenProvider,
        RefreshTokenService refreshTokenService
    ) {
        this.authenticationManager = authenticationManager;
        this.tokenProvider = tokenProvider;
        this.refreshTokenService = refreshTokenService;
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
     */
    @PostMapping("/authenticate")
    public Mono<ResponseEntity<JWTToken>> authorize(@Valid @RequestBody Mono<LoginVM> loginVM) {
        return loginVM.flatMap(
            login ->
                authenticationManager
                    .authenticate(new UsernamePasswordAuthenticationToken(login.getUsername(), login.getPassword()))
                    .flatMap(auth -> login.isMobileClient() ? mobileResponse(auth, login) : browserResponse(auth, login))
        );
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
            .issue(authentication.getName(), authorities, login.getClient(), login.getDeviceId(), login.getDeviceName())
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
