package net.jojoaddison.security;

/**
 * Constants for Spring Security authorities.
 */
public final class AuthoritiesConstants {

    public static final String ADMIN = "ROLE_ADMIN";

    public static final String USER = "ROLE_USER";

    public static final String ANONYMOUS = "ROLE_ANONYMOUS";

    public static final String SYSTEM = "ROLE_SYSTEM";

    public static final String ACTUATOR = "ROLE_ACTUATOR";

    public static final String KAFKA = "ROLE_KAFKA";

    public static final String KAFKA_CONSUMER = "ROLE_KAFKA_CONSUMER";

    public static final String KAFKA_PRODUCER = "ROLE_KAFKA_PRODUCER";

    public static final String KAFKA_ADMIN = "ROLE_KAFKA_ADMIN";

    public static final String KAFKA_STREAMS = "ROLE_KAFKA_STREAMS";

    public static final String KAFKA_CONNECT = "ROLE_KAFKA_CONNECT";

    public static final String PATIENT = "ROLE_PATIENT";

    public static final String DOCTOR = "ROLE_DOCTOR";

    public static final String NURSE = "ROLE_NURSE";

    public static final String CARER = "ROLE_CARER";

    public static final String PARAMEDIC = "ROLE_PARAMEDIC";

    public static final String PHARMACIST = "ROLE_PHARMACIST";

    public static final String THERAPIST = "ROLE_THERAPIST";

    public static final String CHEMIST = "ROLE_CHEMIST";

    public static final String TECHNICIAN = "ROLE_TECHNICIAN";

    /**
     * Who may use the microservice routes under {@code /services/**}: the administrator and the
     * eight clinical disciplines.
     *
     * <p><b>All eight, not the six of {@code CLINICAL_MUTATION}.</b> Carer, chemist and technician
     * are read-only in v1, which is a rule about <em>writes</em> and is enforced by the services
     * themselves ({@code api/config/SecurityConfiguration}, proved by
     * {@code ClinicalAuthorityMatrixIT}). Naming only the six here would take those three out of the
     * patient directory, the roster and their own earnings — a routing rule silently reimplementing a
     * mutation rule, and getting it wrong.
     *
     * <p><b>There is no {@code ANGEL} constant to leave out of this array any more, and that is the
     * point.</b> {@code ROLE_ANGEL} left this array on 2026-09-06 (docs/backlog.md item 30, an angel
     * is not a clinical discipline) and left this stack altogether on 2026-09-08 (item 44): <b>an
     * angel only supports a patient, and has no role whatsoever in the professional subsystem.</b> An
     * angel's authority is a grant over one named patient, which hc-patient records as an
     * {@code ACTIVE CareDelegation} and re-reads per request so that a revocation takes effect on the
     * next call rather than when a {@code rememberMe} token expires — and hc-patient owns the whole
     * surface for it. This stack no longer seeds the authority, no longer assigns it and no longer
     * names it anywhere.
     *
     * <p><b>A token bearing {@code ROLE_ANGEL} can still arrive here, and must keep granting nothing.</b>
     * The three gateways share one signing key and this one stamps no {@code iss} claim, so an
     * hc-patient token reaches this rule exactly as if this gateway had minted it — as does a token for
     * an account on a long-lived database that held the authority before it was removed. Both are
     * refused by this array for the same reason {@code ROLE_PATIENT} is: the list is positive, so an
     * authority nothing here names is an authority nothing here admits. {@code ServicesRouteAuthorizationIT}
     * asserts that by the literal string, and {@code AuthoritiesConstantsUnitTest} fails if
     * {@code "ROLE_ANGEL"} reappears in any privilege set in this class.
     *
     * <p><b>{@code ROLE_USER} is deliberately absent, and that is the whole point of the list.</b> The
     * three stacks share one signing key and no token this gateway issues carries an {@code iss}
     * claim, so {@code .authenticated()} on {@code /services/**} meant "authenticated by any of the
     * three" — and hc-patient grants {@code ROLE_USER} alongside {@code ROLE_PATIENT}
     * ({@code hc-patient/gateway/service/UserService}), so a patient token satisfied it. Naming
     * {@code ROLE_USER} here would restore exactly that.
     *
     * <p>Positive, never a {@code ROLE_PATIENT} denylist: which authorities the other two stacks mint
     * is theirs to change, and a denylist written here goes stale the day they add one.
     */
    public static final String[] CLINICAL_AND_ADMIN = {
        ADMIN,
        DOCTOR,
        NURSE,
        PARAMEDIC,
        PHARMACIST,
        THERAPIST,
        CARER,
        CHEMIST,
        TECHNICIAN,
    };

    private AuthoritiesConstants() {}
}
