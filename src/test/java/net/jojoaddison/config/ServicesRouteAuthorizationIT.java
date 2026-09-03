package net.jojoaddison.config;

import static net.jojoaddison.security.jwt.JwtAuthenticationTestUtils.createTokenWithAuthorities;

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
 * that from; naming the authority is the assertion.
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

    /** A token with no {@code auth} claim at all — the shape a hand-minted probe token takes. */
    @ParameterizedTest
    @MethodSource("serviceRoutes")
    void aTokenCarryingNoAuthorityIsRefusedEveryServiceRoute(String path) {
        expectForbidden(path);
    }

    // --- what must keep working -------------------------------------------------------------

    /**
     * All ten, on all three routes. The four read-only authorities — carer, angel, chemist,
     * technician — are the ones a rule copied from {@code CLINICAL_MUTATION} would have locked out of
     * reads, so they are asserted here rather than assumed.
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
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "/services/professionalservice/api/onboarding",
            "/services/professionalservice/api/onboarding/progress",
            "/services/professionalservice/api/onboarding/applications/me",
            "/services/professionalservice/api/messaging/unread-count",
            "/services/professionalservice/api/messaging/conversations",
            "/services/professionalservice/api/duty-roster",
        }
    )
    void aRoleLessApplicantReachesTheThreeIslands(String path) {
        expectPastAuthorization(path, AuthoritiesConstants.USER);
    }

    /**
     * The roster island is the bare GET and nothing else.
     *
     * <p>{@code /day/{date}} is the one roster read that carries customer names, addresses and phone
     * numbers, and the service holds it at {@code .authenticated()} — so a wildcard island would hand
     * a patient token the estate's customer list. {@code /all} and {@code /summary} are administrator
     * views. Widening the matcher to {@code /duty-roster/**} would fail here.
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
