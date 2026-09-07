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

    /**
     * A patient's nominated care angel — a family member or proxy acting for one named person, not a
     * clinical discipline. The authority exists so that this gateway and the portal can tell that
     * somebody is an angel at all; what an angel may actually read is an {@code ACTIVE CareDelegation}
     * held in hc-patient and re-read per request. Deliberately outside {@link #CLINICAL_AND_ADMIN}
     * since 2026-09-06 — see the note there and docs/backlog.md item 30.
     */
    public static final String ANGEL = "ROLE_ANGEL";

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
     * <p><b>{@link #ANGEL} is deliberately absent, and it used to be here.</b> The estate decided on
     * 2026-09-06 (docs/backlog.md item 30) that an angel is <em>not</em> a clinical discipline. A
     * discipline is a standing capability; an angel's authority is a grant over one named patient,
     * which hc-patient records as an {@code ACTIVE CareDelegation} and re-reads per request so that a
     * revocation takes effect on the next call rather than when a {@code rememberMe} token expires.
     * Admitting {@code ROLE_ANGEL} here granted unrestricted cross-patient read across the estate on
     * a role check — exactly what that delegation model exists to prevent — and this line is where
     * that over-grant lived. The authority itself is not retired: it is still seeded, still assigned
     * and still carried in a token, and an angel still reaches the three islands below this rule
     * (onboarding, the shell's own-scoped inbox reads, their own roster). It simply no longer opens
     * the clinical surface. Do not add it back; {@code ServicesRouteAuthorizationIT} refuses it by
     * name, and {@code AuthoritiesConstantsUnitTest} refuses it in this array.
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
