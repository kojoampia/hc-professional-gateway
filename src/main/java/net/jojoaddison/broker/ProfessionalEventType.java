package net.jojoaddison.broker;

/**
 * The account event types this gateway publishes, named exactly as hc-patient names its own.
 *
 * <p>{@link #ACCOUNT_CREATED} and {@link #ACCOUNT_ACTIVATED} are the answer to "which moment creates
 * a professional?" — this stack declines to decide it for the consumer. The first says an account
 * exists and cannot yet be signed into; the second says it can. A reader wanting the first may open a
 * record on creation; a reader that considers an abandoned registration not to be a person waits for
 * the second. Publishing only one of the two would have made that choice on their behalf, in a
 * repository that does not own the directory.
 *
 * <p>{@link #ACCOUNT_DETAILS_UPDATED} answers a different question and has a different consumer:
 * it tells {@code api/} whether onboarding <b>step 1</b> is satisfied, because the four fields step 1
 * requires live on {@code User} here and {@code api/} owns the meter. See that constant, and
 * backlog.md row 230.
 *
 * <p>{@code AccountCreated} also carries {@code data.activated}, mirroring hc-patient, so an
 * administrator-created (invitation) account that arrives already usable is distinguishable from a
 * self-service registration awaiting its email link without waiting for a second frame.
 *
 * <p>A consumer meeting a type it does not recognise on this topic must ignore it — the same rule
 * both existing publishers state, and the reason a new type here is additive rather than breaking.
 */
public final class ProfessionalEventType {

    /** Published from self-service registration and from the administrator-created invitation path. */
    public static final String ACCOUNT_CREATED = "AccountCreated";

    /** Published when the activation link is followed and the account becomes usable. */
    public static final String ACCOUNT_ACTIVATED = "AccountActivated";

    /**
     * The account's own details were written, and whether onboarding <b>step 1</b> is now satisfied
     * — backlog.md row 230, unit A.
     *
     * <h2>What it is for, and why {@code api/} cannot answer the question itself</h2>
     *
     * <p>{@code api/} owns the completion meter and the four fields step 1 requires are
     * <em>here</em>. {@code OnboardingService.java:412-417} records the consequence and refuses to
     * work around it: <i>"there is deliberately <b>no cross-service call invented here</b> to read
     * them, so what this gate enforces is steps 2, 3 and 4"</i>. Row 230's decision is that step 1
     * arrives as an event, which is this type.
     *
     * <p>⛔ <b>{@code data} is one boolean and carries none of the four fields.</b>
     * {@code firstName}, {@code lastName} and {@code imageUrl} are exactly the personal data the
     * estate's identifiers-only rule exists for. {@link net.jojoaddison.service.AccountCompleteness}
     * decides the answer on this side and only the answer travels — a fact <em>about</em> the
     * account rather than its contents, the same distinction {@code ProfileStatus} draws when it
     * sends {@code licenceVerified} rather than a licence number.
     *
     * <p><b>A snapshot, not a delta, and therefore idempotent.</b> A redelivery or a full replay
     * applies the same boolean twice. The envelope's {@code occurredAt} is what lets a consumer
     * refuse an <em>older</em> frame: at-least-once delivery is not at-least-once ordering, and the
     * two frames either side of a correction can arrive in either order.
     *
     * <p>⚠ <b>Published on every write to a {@code User} row, not only on writes that touch the four
     * fields.</b> That is deliberate and the reasoning is {@code UserService.saveUser}'s rather than
     * this constant's — see it there. The cost is frames repeating a boolean nobody changed, which
     * this type's idempotence makes harmless; the benefit is that no write path can be forgotten.
     */
    public static final String ACCOUNT_DETAILS_UPDATED = "AccountDetailsUpdated";

    private ProfessionalEventType() {}
}
