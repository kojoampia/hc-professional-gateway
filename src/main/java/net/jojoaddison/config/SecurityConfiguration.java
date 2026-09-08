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
                    // PublicUserResource returns {id, login} for every activated account — the
                    // complete login-to-User.id table. "Public" is JHipster's name for it, not a
                    // description of who may read it: it fell through to the catch-all below, so any
                    // authenticated caller could enumerate the mapping, including an applicant
                    // holding bare ROLE_USER and a token minted by a sibling stack (the three
                    // gateways share a signing key and TokenOriginValidator ships disabled).
                    //
                    // That mapping is what makes a client-supplied accountId targetable rather than
                    // guessable, which is why backlog item 53 could not adopt it — see item 54 for
                    // the takeover it enabled in api/. Nothing in the estate calls this endpoint:
                    // the only three references to it are comments in web/, mobile/ and api/
                    // explaining why the messaging recipient picker was built separately instead.
                    // It is kept rather than deleted because item 50's migration needs exactly this
                    // table, and admin is who would run that.
                    //
                    // Both patterns: "/api/users/**" does not match "/api/users" itself.
                    .pathMatchers("/api/users", "/api/users/**").hasAuthority(AuthoritiesConstants.ADMIN)
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
                    // 2. Messaging — EXACTLY THE THREE GETS THE SHELL FIRES BY ITSELF, and no more.
                    //    `MessagesApiService` is injected by the shell, the sidebar and the tab bar
                    //    and loads `conversations` + `unread-count` on every signed-in page for
                    //    every account, including an applicant's, with no role check and no opt-out
                    //    from the global error banner; `messages/{id}` is fetched on every socket
                    //    frame, because the notification carries identifiers only. A 403 on any of
                    //    the three would put a red banner over the applicant's wizard on every
                    //    navigation, permanently.
                    //
                    //    THIS WAS `/api/messaging/**` ON ALL METHODS, which is materially wider than
                    //    the enumeration above, and two endpoints under it are not own-scoped:
                    //    `GET /recipients` returns account id, login and role for EVERY ACTIVE
                    //    PROFESSIONAL ON THE ESTATE, unpaginated — a valid-login list for this
                    //    gateway's own /api/authenticate — and `POST /conversations` injects a
                    //    message into clinicians' inboxes, including a recipientRole broadcast to
                    //    every nurse or doctor with a push notification behind it. Both were
                    //    reachable by a self-registered ROLE_USER account and by an hc-patient
                    //    token, which is the hole this whole rule exists to close.
                    //
                    //    Everything else `MessagesApiService` can call — opening a thread, replying,
                    //    marking read, composing — is a deliberate action on the /messages page and
                    //    falls to the authority rule below. An applicant is not a correspondent:
                    //    nothing in this estate addresses one (`MessagingService.recipients` and
                    //    `resolveRole` both read ACTIVE applications only), and onboarding
                    //    correspondence travels as `correctionNotes` on the application, rendered on
                    //    the applicant's own profile tab. See docs/backlog.md item 19.
                    .pathMatchers(HttpMethod.GET, "/services/professionalservice/api/messaging/conversations").authenticated()
                    .pathMatchers(HttpMethod.GET, "/services/professionalservice/api/messaging/unread-count").authenticated()
                    .pathMatchers(HttpMethod.GET, "/services/professionalservice/api/messaging/messages/*").authenticated()
                    // 3. The caller's OWN duty roster, GET and the bare path only. The sidebar user
                    //    card loads it on sign-in for every account; for an applicant it answers an
                    //    empty list, because the resource resolves the caller from the token and
                    //    discloses nobody else's assignments.
                    //
                    //    NOT `/duty-roster/**`. `/day/{date}` is the one roster read that serves the
                    //    stored document with its customer names, addresses and phone numbers, and
                    //    the service holds it at .authenticated() — so this matcher is what keeps a
                    //    token this gateway did not mint away from that handler at all. `/all`,
                    //    `/summary` and the customer trail stay behind the authority rule below for
                    //    the same reason.
                    //
                    //    THIS IS LAYERED DEFENCE, NOT THE LAST LINE OF IT, and the difference is
                    //    worth stating because a reader who checks the claim will otherwise conclude
                    //    the comment is simply wrong. `DutyRosterResource.day()` resolves the caller
                    //    through `ownProfileId()` and returns an empty list for an account with no
                    //    professional profile, so a patient token reaching it gets nothing rather
                    //    than the estate's customers.
                    //
                    //    THE RESIDUAL IS REAL AND IS NOT THIS RULE'S TO FIX. `ownProfileId()`
                    //    matches `Profile.accountId` against the token `sub`; the three gateways
                    //    share one key and this one validates no issuer, so a caller from hc-patient
                    //    WHOSE LOGIN STRING EQUALS A PROFESSIONAL'S resolves to that professional's
                    //    profile. This matcher makes that unreachable on `/day`; it stays reachable
                    //    through the onboarding island. Not introduced here — see docs/backlog.md
                    //    item 27, and the `iss` work item 19 already names as the structural fix.
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
                    //
                    // ROLE_ANGEL WAS IN THIS LIST UNTIL 2026-09-06 AND IS NOT ANY MORE. An angel is
                    // a proxy for one named patient, recorded in hc-patient as an ACTIVE
                    // CareDelegation and re-read per request; it is not a discipline, and a role
                    // check here granted every angel in the estate unrestricted cross-patient read.
                    // The authority survives — an angel still signs in and still reaches the three
                    // islands above — it just no longer opens the clinical surface. Backlog item 30.
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
