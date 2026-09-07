package net.jojoaddison.config;

import static net.jojoaddison.security.jwt.JwtAuthenticationTestUtils.createTokenWithAuthorities;
import static net.jojoaddison.security.jwt.JwtAuthenticationTestUtils.createTokenWithEmptyAuthorityClaim;

import java.util.Arrays;
import java.util.stream.Stream;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.security.AuthoritiesConstants;
import net.jojoaddison.security.jwt.AuthenticationIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Who may use the microservice routes under {@code /services/**}.
 *
 * <h3>Why a 404 is the "allowed" assertion</h3>
 * This is a {@code @WebFluxTest} slice holding the real {@link SecurityConfiguration} and exactly one
 * controller, which serves {@code /api/authenticate} and nothing else. So no handler exists for any
 * path below, and the three outcomes are unambiguous: <b>401</b> the request carried no usable token,
 * <b>403</b> the authorization rule refused it, <b>404</b> the rule let it through and routing found
 * nowhere to send it. In the deployed stack that last case is where Spring Cloud Gateway forwards to
 * the microservice; here it is where the slice runs out of application.
 *
 * <p>That is the same reading {@code deploy/deploy.sh}'s routing probe relies on against the running
 * gateway, and one of the cases below pins it deliberately.
 *
 * <h3>Why the admitted set is derived rather than listed</h3>
 * {@link #clinicalAndAdmin()} reads {@link AuthoritiesConstants#CLINICAL_AND_ADMIN}, so a tenth
 * clinical authority is covered on the day it is added to that array. A hand-written list of names
 * would keep passing while covering nine of ten — the failure that put eight of hc-admin's endpoints
 * behind a green test asserting a literal list of 23 paths, and the reason
 * {@code JhipsterEnumFieldValuesTest} in {@code api/} derives its expectations too.
 *
 * <p>The refused set is <b>not</b> derived, and cannot be: the point of these cases is that a caller
 * holding {@code ROLE_USER} — which is every applicant here, and every patient in the sibling stack,
 * since hc-patient grants it alongside {@code ROLE_PATIENT} — is refused. There is no array to read
 * that from; naming the authority is the assertion. {@code ROLE_ANGEL} joined that set on 2026-09-06
 * for the same reason, and its cases must stay named for a sharper one: derivation covers whatever
 * is <em>in</em> the array, so an authority removed from it loses its coverage in the same commit
 * and the suite stays green either way.
 *
 * @see AuthoritiesConstants#CLINICAL_AND_ADMIN
 */
@AutoConfigureWebTestClient(timeout = IntegrationTest.DEFAULT_TIMEOUT)
@AuthenticationIntegrationTest
class ServicesRouteAuthorizationIT {

    /**
     * The clinical surface of this stack's own service. Any path under {@code /services/**} that is
     * not one of the three islands would do; this one is the patient directory, which is what a
     * patient token was reaching before the rule named authorities.
     */
    private static final String OWN_SERVICE_CLINICAL = "/services/professionalservice/api/patients";

    /** The cross-stack read of hc-patient. */
    private static final String PATIENT_SERVICE = "/services/patientservice/api/profiles";

    /** The cross-stack read of hc-admin — a clinician's own shifts and accrued earnings. */
    private static final String ADMIN_SERVICE = "/services/adminservice/api/professionals/me/earnings";

    @Autowired
    private WebTestClient webTestClient;

    @Value("${jhipster.security.authentication.jwt.base64-secret}")
    private String jwtKey;

    static Stream<String> clinicalAndAdmin() {
        return Arrays.stream(AuthoritiesConstants.CLINICAL_AND_ADMIN);
    }

    static Stream<String> serviceRoutes() {
        return Stream.of(OWN_SERVICE_CLINICAL, PATIENT_SERVICE, ADMIN_SERVICE);
    }

    // --- the tightening itself ---------------------------------------------------------------

    /**
     * The case the rule exists for. {@code ROLE_USER} is what a self-service registration and a
     * careers applicant are granted, and it is all they hold until an administrator assigns a
     * clinical authority; under {@code .authenticated()} it opened the whole microservice surface.
     */
    @ParameterizedTest
    @MethodSource("serviceRoutes")
    void aRoleLessAccountIsRefusedEveryServiceRoute(String path) {
        expectForbidden(path, AuthoritiesConstants.USER);
    }

    /**
     * An angel is not a clinician, and this is the case that says so.
     *
     * <p>The estate decided on 2026-09-06 (docs/backlog.md item 30) that {@code ROLE_ANGEL} is a
     * grant over one named patient rather than a standing capability: hc-patient records it as an
     * {@code ACTIVE CareDelegation} and re-reads it per request, so a revocation takes effect on the
     * next call. {@code CLINICAL_AND_ADMIN} named it until then, and a role check cannot express any
     * of that — every angel in the estate held unrestricted cross-patient read.
     *
     * <p>Named rather than derived, and that is the point. {@link #clinicalAndAdmin()} reads the
     * array, so removing an authority from it silently removes its coverage too — the admitted-set
     * test would go from ten names to nine and stay green. Only an assertion that spells
     * {@code ROLE_ANGEL} fails when somebody puts it back.
     */
    @ParameterizedTest
    @MethodSource("serviceRoutes")
    void anAngelIsRefusedEveryServiceRoute(String path) {
        expectForbidden(path, AuthoritiesConstants.ANGEL);
    }

    /**
     * And an angel who also holds {@code ROLE_USER} — which every account created through this
     * gateway does, so it is the shape a real angel token actually takes.
     */
    @ParameterizedTest
    @MethodSource("serviceRoutes")
    void anAngelHoldingTheBaseUserAuthorityIsRefusedEveryServiceRoute(String path) {
        expectForbidden(path, AuthoritiesConstants.USER, AuthoritiesConstants.ANGEL);
    }

    /** Nor the recipient directory, which is the estate's valid-login list. */
    @Test
    void anAngelIsRefusedTheRecipientDirectory() {
        expectForbidden("/services/professionalservice/api/messaging/recipients", AuthoritiesConstants.ANGEL);
    }

    /**
     * What an angel keeps: the three islands, which sit above the authority rule and are what make
     * this a narrowing rather than a lock-out. An angel signs in, completes onboarding, reads the
     * inbox the shell loads for them, and sees their own roster — exactly as a role-less applicant
     * does. Losing these would turn a scope fix into an account that cannot use the portal at all.
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "/services/professionalservice/api/onboarding",
            "/services/professionalservice/api/onboarding/progress",
            "/services/professionalservice/api/messaging/unread-count",
            "/services/professionalservice/api/messaging/conversations",
            "/services/professionalservice/api/duty-roster",
        }
    )
    void anAngelStillReachesTheThreeIslands(String path) {
        expectPastAuthorization(path, AuthoritiesConstants.ANGEL);
    }

    /**
     * The estate case, and the reason this is not merely a tidy-up.
     *
     * <p>All three gateways sign with one key, this one stamps no {@code iss} claim and its decoder
     * validates none, so a token minted by hc-patient is accepted here exactly as if this gateway had
     * issued it. hc-patient grants {@code ROLE_PATIENT} <em>alongside</em> {@code ROLE_USER} rather
     * than instead of it, so a patient satisfied {@code .authenticated()} — and read the professional
     * stack's patient directory, verified against the quality stack on 2026-09-03.
     */
    @ParameterizedTest
    @MethodSource("serviceRoutes")
    void aPatientTokenFromTheSiblingStackIsRefusedEveryServiceRoute(String path) {
        expectForbidden(path, AuthoritiesConstants.USER, AuthoritiesConstants.PATIENT);
    }

    /**
     * A token with no {@code auth} claim at all — the shape a hand-minted probe token takes.
     *
     * <p>Genuinely absent, not empty: {@code createTokenWithAuthorities} omits the claim when given
     * no authorities, which is a different path through {@code JwtGrantedAuthoritiesConverter} from
     * the one below. This javadoc named the absent case while the helper wrote {@code ""}, so the
     * assertion was valid and the case it described was uncovered.
     */
    @ParameterizedTest
    @MethodSource("serviceRoutes")
    void aTokenCarryingNoAuthorityClaimIsRefusedEveryServiceRoute(String path) {
        expectForbidden(path);
    }

    /**
     * And a token whose {@code auth} claim is present and empty — which is what this gateway's own
     * {@code TokenProvider} mints for an account holding nothing, since it always writes the claim.
     */
    @ParameterizedTest
    @MethodSource("serviceRoutes")
    void aTokenCarryingAnEmptyAuthorityClaimIsRefusedEveryServiceRoute(String path) {
        webTestClient
            .get()
            .uri(path)
            .headers(headers -> headers.setBearerAuth(createTokenWithEmptyAuthorityClaim(jwtKey, "caller")))
            .exchange()
            .expectStatus()
            .isForbidden();
    }

    // --- what must keep working -------------------------------------------------------------

    /**
     * All nine, on all three routes. The three read-only authorities — carer, chemist, technician —
     * are the ones a rule copied from {@code CLINICAL_MUTATION} would have locked out of reads, so
     * they are asserted here rather than assumed.
     *
     * <p>Nine and not ten since 2026-09-06: {@code ROLE_ANGEL} left the array, and
     * {@link #anAngelIsRefusedEveryServiceRoute} is the case that holds it out by name.
     */
    @ParameterizedTest
    @MethodSource("clinicalAndAdminOnEveryRoute")
    void everyClinicalAuthorityAndTheAdministratorReachEveryServiceRoute(String authority, String path) {
        expectPastAuthorization(path, authority);
    }

    static Stream<Arguments> clinicalAndAdminOnEveryRoute() {
        return clinicalAndAdmin().flatMap(authority -> serviceRoutes().map(path -> Arguments.of(authority, path)));
    }

    /**
     * The three islands, reached by an account holding nothing but {@code ROLE_USER}.
     *
     * <p>Each is a caller that exists today: the applicant wizard and the shell's progress poll
     * (onboarding), the shell/sidebar/tab-bar inbox load on every signed-in page (messaging), and the
     * sidebar user card (the caller's own roster). A rule that refuses any of these passes every unit
     * test in this repository and puts a permanent error banner on an applicant's screen.
     *
     * <p>The messaging entries are the three GETs {@code MessagesApiService} fires without a user
     * asking — {@code conversations} and {@code unread-count} from the shell on sign-in, and
     * {@code messages/{id}} on every socket frame. Everything else that service can call is a
     * deliberate action on the messages page, and is refused above.
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "/services/professionalservice/api/onboarding",
            "/services/professionalservice/api/onboarding/progress",
            "/services/professionalservice/api/onboarding/applications/me",
            "/services/professionalservice/api/messaging/unread-count",
            "/services/professionalservice/api/messaging/conversations",
            "/services/professionalservice/api/messaging/messages/any-message",
            "/services/professionalservice/api/duty-roster",
        }
    )
    void aRoleLessApplicantReachesTheThreeIslands(String path) {
        expectPastAuthorization(path, AuthoritiesConstants.USER);
    }

    /**
     * The roster island is the bare GET and nothing else.
     *
     * <p>{@code /day/{date}} is the roster read that serves the stored document with its customer
     * snapshots, and the service holds it at {@code .authenticated()} — so the narrow matcher is what
     * keeps a token this gateway did not mint away from that handler. {@code /all} and
     * {@code /summary} are administrator views. Widening the matcher to {@code /duty-roster/**} would
     * fail here.
     *
     * <p>This is layered defence rather than the last line of it: {@code DutyRosterResource.day()}
     * resolves the caller through its own profile and answers an empty list to an account that has
     * none. See {@code SecurityConfiguration} for the residual that leaves, and backlog item 27.
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "/services/professionalservice/api/duty-roster/day/2026-09-03",
            "/services/professionalservice/api/duty-roster/all",
            "/services/professionalservice/api/duty-roster/summary",
        }
    )
    void theRosterIslandDoesNotExtendToTheReadsBesideIt(String path) {
        expectForbidden(path, AuthoritiesConstants.USER);
    }

    /**
     * The messaging island does not extend to the two endpoints beside it that are not own-scoped.
     *
     * <p>The island exists for three shell reads. It shipped as {@code /api/messaging/**} on all
     * methods, which is materially wider than the enumeration that justified it:
     *
     * <ul>
     *   <li>{@code GET /recipients} returns {@code (accountId, displayName, role)} for <b>every</b>
     *       ACTIVE professional on the estate, unpaginated — and {@code displayName} is the login,
     *       so it is the valid-login list for this gateway's own {@code /api/authenticate}.
     *   <li>{@code POST /conversations} injects a message into clinicians' inboxes, including a
     *       {@code recipientRole} broadcast to every nurse or doctor, with a push notification
     *       behind it.
     * </ul>
     *
     * <p>Both were reachable by a self-registered {@code ROLE_USER} account and, since this gateway
     * stamps no {@code iss} claim and validates none, by an hc-patient token — the hole item 19
     * exists to close, left open in a different place.
     *
     * <p>Same shape as {@link #theRosterIslandDoesNotExtendToTheReadsBesideIt}, which is why the
     * roster island was safe and this one was not.
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "/services/professionalservice/api/messaging/recipients",
            // The island's `conversations` matcher is an exact path, not a prefix: a thread's own
            // messages are own-scoped but are not one of the three the shell fires.
            "/services/professionalservice/api/messaging/conversations/any-thread/messages",
        }
    )
    void theMessagingIslandDoesNotExtendToTheReadsBesideIt(String path) {
        expectForbidden(path, AuthoritiesConstants.USER);
    }

    /**
     * Starting a conversation is not part of the island. A role-less caller may read the inbox the
     * shell loads for them; they may not put a message into anybody else's.
     */
    @Test
    void aRoleLessAccountCannotStartAConversation() {
        webTestClient
            .post()
            .uri("/services/professionalservice/api/messaging/conversations")
            .headers(headers -> headers.setBearerAuth(token(AuthoritiesConstants.USER)))
            .exchange()
            .expectStatus()
            .isForbidden();
    }

    /**
     * And neither may a patient token from the sibling stack, which is the same account shape with
     * one more authority on it.
     */
    @Test
    void aPatientTokenCannotStartAConversation() {
        webTestClient
            .post()
            .uri("/services/professionalservice/api/messaging/conversations")
            .headers(headers -> headers.setBearerAuth(token(AuthoritiesConstants.USER, AuthoritiesConstants.PATIENT)))
            .exchange()
            .expectStatus()
            .isForbidden();
    }

    /**
     * The island is GET-only on the paths it does name. {@code POST /messages/{id}/read} is a write
     * against the caller's own row, but it is not one of the three the shell fires, so it falls to
     * the authority rule like everything else.
     */
    @Test
    void theMessagingIslandIsReadOnly() {
        webTestClient
            .post()
            .uri("/services/professionalservice/api/messaging/messages/any-message/read")
            .headers(headers -> headers.setBearerAuth(token(AuthoritiesConstants.USER)))
            .exchange()
            .expectStatus()
            .isForbidden();
    }

    /**
     * A clinician still reaches all of it. The two endpoints above are refused for want of an
     * authority, not because the paths stopped being routed — and the four read-only authorities are
     * covered by {@link #everyClinicalAuthorityAndTheAdministratorReachEveryServiceRoute} on the
     * clinical routes, so this pins the messaging surface itself.
     */
    @ParameterizedTest
    @MethodSource("clinicalAndAdmin")
    void everyClinicalAuthorityAndTheAdministratorReachTheRecipientDirectory(String authority) {
        expectPastAuthorization("/services/professionalservice/api/messaging/recipients", authority);
    }

    /** The island is GET-only: a role-less caller may read their roster, never write one. */
    @Test
    void theRosterIslandIsReadOnly() {
        webTestClient
            .post()
            .uri("/services/professionalservice/api/duty-roster")
            .headers(headers -> headers.setBearerAuth(token(AuthoritiesConstants.USER)))
            .exchange()
            .expectStatus()
            .isForbidden();
    }

    // --- what this change must not have weakened --------------------------------------------

    /** Unauthenticated is still 401, on every one of them, island or not. */
    @ParameterizedTest
    @ValueSource(
        strings = {
            OWN_SERVICE_CLINICAL,
            PATIENT_SERVICE,
            ADMIN_SERVICE,
            "/services/professionalservice/api/onboarding/progress",
            "/services/professionalservice/api/messaging/unread-count",
            "/services/professionalservice/api/duty-roster",
        }
    )
    void nothingUnderServicesIsOpenToAnAnonymousCaller(String path) {
        webTestClient.get().uri(path).exchange().expectStatus().isUnauthorized();
    }

    /** The readiness probe stays open — it is how the deploy waits for the stack. */
    @Test
    void theReadinessProbeStaysOpen() {
        webTestClient.get().uri("/services/professionalservice/management/health/readiness").exchange().expectStatus().isNotFound();
    }

    /**
     * The service's OpenAPI document stays administrator-only. It sits above the new rule, so a
     * clinical authority must still be refused it — the one place under {@code /services/**} that was
     * already stricter than {@code .authenticated()}.
     */
    @Test
    void theServiceApiDocsStayAdministratorOnly() {
        expectForbidden("/services/professionalservice/v3/api-docs", AuthoritiesConstants.DOCTOR);
        expectPastAuthorization("/services/professionalservice/v3/api-docs", AuthoritiesConstants.ADMIN);
    }

    /**
     * An unknown service prefix must still reach routing rather than be refused.
     *
     * <p>{@code deploy/deploy.sh} probes {@code /services/definitely-not-a-service/api/profiles} with
     * an administrator token and requires <b>404</b>, using it as the control that tells "the static
     * route did not bind" apart from "the request never got as far as routing". A {@code denyAll()}
     * catch-all here would answer 403 and quietly cost that check its meaning — which is exactly the
     * class of breakage this rule change had to avoid.
     */
    @Test
    void anUnknownServicePrefixIsRefusedByRoutingRatherThanByAuthorization() {
        expectPastAuthorization("/services/definitely-not-a-service/api/profiles", AuthoritiesConstants.ADMIN);
    }

    // --- helpers ------------------------------------------------------------------------------

    private String token(String... authorities) {
        return createTokenWithAuthorities(jwtKey, "caller", authorities);
    }

    private void expectForbidden(String path, String... authorities) {
        webTestClient.get().uri(path).headers(headers -> headers.setBearerAuth(token(authorities))).exchange().expectStatus().isForbidden();
    }

    /**
     * Asserts the request got past authorization. See the class javadoc: with no handler for these
     * paths in the slice, 404 is what "allowed" looks like, and it is distinguishable from both
     * refusals.
     */
    private void expectPastAuthorization(String path, String... authorities) {
        webTestClient.get().uri(path).headers(headers -> headers.setBearerAuth(token(authorities))).exchange().expectStatus().isNotFound();
    }
}
