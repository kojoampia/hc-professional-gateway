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

    public static final String ANGEL = "ROLE_ANGEL";

    public static final String CARER = "ROLE_CARER";

    public static final String PARAMEDIC = "ROLE_PARAMEDIC";

    public static final String PHARMACIST = "ROLE_PHARMACIST";

    public static final String THERAPIST = "ROLE_THERAPIST";

    public static final String CHEMIST = "ROLE_CHEMIST";

    public static final String TECHNICIAN = "ROLE_TECHNICIAN";

    /**
     * Who may use the microservice routes under {@code /services/**}: the administrator and the nine
     * clinical authorities.
     *
     * <p><b>All nine, not the six of {@code CLINICAL_MUTATION}.</b> Carer, angel, chemist and
     * technician are read-only in v1, which is a rule about <em>writes</em> and is enforced by the
     * services themselves ({@code api/config/SecurityConfiguration}, proved by
     * {@code ClinicalAuthorityMatrixIT}). Naming only the six here would take those four out of the
     * patient directory, the roster and their own earnings — a routing rule silently reimplementing a
     * mutation rule, and getting it wrong.
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
        ANGEL,
        CHEMIST,
        TECHNICIAN,
    };

    private AuthoritiesConstants() {}
}
