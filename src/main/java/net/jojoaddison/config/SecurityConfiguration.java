package net.jojoaddison.config;

import static org.springframework.security.config.Customizer.withDefaults;
import static org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers.pathMatchers;

import net.jojoaddison.security.AuthoritiesConstants;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UserDetailsRepositoryReactiveAuthenticationManager;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.header.ReferrerPolicyServerHttpHeadersWriter;
import org.springframework.security.web.server.header.XFrameOptionsServerHttpHeadersWriter.Mode;
import org.springframework.security.web.server.util.matcher.NegatedServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.OrServerWebExchangeMatcher;
import tech.jhipster.config.JHipsterProperties;

@Configuration
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity
public class SecurityConfiguration {

    private final JHipsterProperties jHipsterProperties;

    public SecurityConfiguration(JHipsterProperties jHipsterProperties) {
        this.jHipsterProperties = jHipsterProperties;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public ReactiveAuthenticationManager reactiveAuthenticationManager(ReactiveUserDetailsService userDetailsService) {
        UserDetailsRepositoryReactiveAuthenticationManager authenticationManager = new UserDetailsRepositoryReactiveAuthenticationManager(
            userDetailsService
        );
        authenticationManager.setPasswordEncoder(passwordEncoder());
        return authenticationManager;
    }

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        http
            .securityMatcher(
                new NegatedServerWebExchangeMatcher(
                    new OrServerWebExchangeMatcher(pathMatchers("/app/**", "/i18n/**", "/content/**", "/swagger-ui/**"))
                )
            )
            .cors(withDefaults())
            .csrf(csrf -> csrf.disable())
            .headers(
                headers ->
                    headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(jHipsterProperties.getSecurity().getContentSecurityPolicy()))
                        .frameOptions(frameOptions -> frameOptions.mode(Mode.DENY))
                        .referrerPolicy(
                            referrer ->
                                referrer.policy(ReferrerPolicyServerHttpHeadersWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)
                        )
                        .permissionsPolicy(
                            permissions ->
                                permissions.policy(
                                    "camera=(), fullscreen=(self), geolocation=(), gyroscope=(), magnetometer=(), microphone=(), midi=(), payment=(), sync-xhr=()"
                                )
                        )
            )
            .authorizeExchange(
                authz ->
                    // prettier-ignore
                authz
                    .pathMatchers("/api/authenticate").permitAll()
                    .pathMatchers("/api/register").permitAll()
                    // The registration form's login look-ahead. A separate rule because the matcher
                    // above is an exact path match, so it does NOT cover /api/register/**; without
                    // this line the look-ahead falls through to /api/** and 401s for the anonymous
                    // caller it exists to serve. GET-only, so nothing under this prefix can be
                    // written to anonymously if a POST is ever added here.
                    .pathMatchers(HttpMethod.GET, "/api/register/login-available").permitAll()
                    .pathMatchers("/api/activate").permitAll()
                    .pathMatchers("/api/account/reset-password/init").permitAll()
                    .pathMatchers("/api/account/reset-password/finish").permitAll()
                    // Mobile refresh-token endpoints. These MUST be permitAll and MUST sit above the
                    // "/api/**" rule below, which would otherwise swallow them: by the time a client
                    // calls either one its access token has usually already expired, so requiring a
                    // valid one would make refresh impossible exactly when it is needed. They
                    // authorize on the refresh token itself — see AuthSessionResource.
                    .pathMatchers(HttpMethod.POST, "/api/auth/refresh").permitAll()
                    .pathMatchers(HttpMethod.POST, "/api/auth/logout").permitAll()
                    .pathMatchers("/api/auth/sessions/**").authenticated()
                    .pathMatchers("/api/admin/**").hasAuthority(AuthoritiesConstants.ADMIN)
                    .pathMatchers("/api/**").authenticated()
                    // The STOMP handshake for message notifications, routed straight through to
                    // professionalservice. It has to be open HERE: a browser cannot set an
                    // Authorization header on a WebSocket upgrade, so the token is presented on the
                    // CONNECT frame and validated by the service, which drops the session when it is
                    // missing or invalid. Without this rule the exchange matches nothing below and
                    // Spring Security denies it — the handshake 401s before it is ever routed, and
                    // the symptom is a dashboard that renders but never updates rather than an error.
                    .pathMatchers("/websocket/**").permitAll()
                    .pathMatchers("/services/*/management/health/readiness").permitAll()
                    .pathMatchers("/services/*/v3/api-docs").hasAuthority(AuthoritiesConstants.ADMIN)
                    .pathMatchers("/services/**").authenticated()
                    .pathMatchers("/v3/api-docs/**").hasAuthority(AuthoritiesConstants.ADMIN)
                    .pathMatchers("/management/health").permitAll()
                    .pathMatchers("/management/health/**").permitAll()
                    .pathMatchers("/management/info").permitAll()
                    .pathMatchers("/management/prometheus").permitAll()
                    .pathMatchers("/management/**").hasAuthority(AuthoritiesConstants.ADMIN)
            )
            .httpBasic(basic -> basic.disable())
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(withDefaults()));
        return http.build();
    }
}
