package net.jojoaddison.broker;

/**
 * The account event types this gateway publishes, named exactly as hc-patient names its own.
 *
 * <p>Two of them, and the pair is the answer to "which moment creates a professional?" — this stack
 * declines to decide it for the consumer. {@link #ACCOUNT_CREATED} says an account exists and cannot
 * yet be signed into; {@link #ACCOUNT_ACTIVATED} says it can. A reader wanting the first may open a
 * record on creation; a reader that considers an abandoned registration not to be a person waits for
 * the second. Publishing only one of the two would have made that choice on their behalf, in a
 * repository that does not own the directory.
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

    private ProfessionalEventType() {}
}
