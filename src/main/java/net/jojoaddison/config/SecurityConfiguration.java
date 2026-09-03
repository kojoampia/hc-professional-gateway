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
                    // THE THREE ISLANDS BELOW ARE REACHABLE BY ANY AUTHENTICATED ACCOUNT, AND EACH
                    // IS HERE BECAUSE A NAMED CALLER HOLDS NO CLINICAL AUTHORITY.
                    //
                    // An applicant holds ROLE_USER and nothing else until an administrator assigns
                    // one, so the surfaces they touch cannot be gated on a clinical role. These are
                    // the only three, established by reading every caller rather than by reasoning
                    // about which ones ought to matter — see docs/backlog.md item 19.
                    //
                    // 1. Onboarding. The applicant's own application, profile, documents, progress
                    //    and first-login acknowledgement. This mirrors api/'s own
                    //    `/api/onboarding/**` .authenticated() rule exactly, and must keep mirroring
                    //    it: a gateway stricter than the service it fronts refuses a request the
                    //    service was written to serve, and the refusal is attributed to the service.
                    .pathMatchers("/services/professionalservice/api/onboarding/**").authenticated()
                    // 2. Messaging. The shell, the sidebar and the tab bar all load
                    //    `conversations` and `unread-count` on every signed-in page — for every
                    //    account, including an applicant's, with no role check and no opt-out from
                    //    the global error banner. A 403 here would put a red banner over the
                    //    applicant's wizard on every navigation, permanently. api/ likewise holds
                    //    `/api/messaging/**` at .authenticated() on the stated grounds that
                    //    messaging is correspondence rather than clinical data.
                    .pathMatchers("/services/professionalservice/api/messaging/**").authenticated()
                    // 3. The caller's OWN duty roster, GET and the bare path only. The sidebar user
                    //    card loads it on sign-in for every account; for an applicant it answers an
                    //    empty list, because the resource resolves the caller from the token and
                    //    discloses nobody else's assignments.
                    //
                    //    NOT `/duty-roster/**`. `/day/{date}` carries customer names, addresses and
                    //    phone numbers and is only .authenticated() at the service, so a wildcard
                    //    here would hand that to a patient token. `/all`, `/summary` and the
                    //    customer trail stay behind the authority rule below for the same reason.
                    .pathMatchers(HttpMethod.GET, "/services/professionalservice/api/duty-roster").authenticated()
                    // Everything else behind the three microservice routes — professionalservice's
                    // clinical surface, and the cross-stack patientservice and adminservice routes.
                    //
                    // This was `.authenticated()`, which meant "authenticated by ANY of the three
                    // stacks": they share one signing key, this gateway stamps no `iss` claim and its
                    // decoder validates none, so nothing in a token says which gateway minted it. A
                    // patient token therefore read the professional patient directory (verified on
                    // quality, 2026-09-03: `ROLE_USER` alone got 200 from
                    // /services/professionalservice/api/patients and
                    // /services/patientservice/api/profiles). hc-admin's mirror-image route is
                    // specified as ADMIN, or ADMIN/OPERATOR on GET, and this side is tightened first
                    // so the estate does not carry two rules for one shape with the weaker one
                    // reachable — hc-admin/docs/duty-roster-resolution.md § 9.1, decision 9.
                    //
                    // Deliberately NOT narrowed per prefix. adminservice is documented as usable
                    // only for `/api/professionals/me/**`, and deploy/prod-server/compose.yml
                    // declines to restate that list on the same grounds this rule declines to:
                    // hc-admin owns which of its endpoints are self-service, and a copy here is a
                    // second answer nobody would think to update. What this rule owns is WHO, not
                    // WHICH — and the answer is the same for all three prefixes.
                    //
                    // The catch-all shape also keeps the deploy-time routing probe honest:
                    // deploy.sh asks for /services/definitely-not-a-service/... with an admin token
                    // and requires a 404, which distinguishes "the static route did not bind" from
                    // "the request never reached routing". A denyAll() here would answer 403 and
                    // that check would stop being able to tell the two apart.
                    .pathMatchers("/services/**").hasAnyAuthority(AuthoritiesConstants.CLINICAL_AND_ADMIN)
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
