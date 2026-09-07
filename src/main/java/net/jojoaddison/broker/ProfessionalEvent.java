package net.jojoaddison.broker;

import java.time.Instant;
import java.util.Map;

/**
 * One thing that happened to one clinician's <em>account</em>, on {@code hc.professional.registration}.
 *
 * <h2>This is the estate's shape, not this stack's</h2>
 *
 * <p>Field for field it is {@code hc-patient/gateway}'s {@code PatientEvent}: {@code eventId},
 * {@code type}, {@code version}, {@code occurredAt}, {@code source}, {@code subject}, {@code data}.
 * Deliberately a copy rather than a shared library, for the reason that record gives — the two
 * products are separately deployed and separately versioned, and a shared type would couple their
 * release cycles to make a seven-field record marginally less repetitive. What has to agree is the
 * wire shape, which is what the field names here are.</p>
 *
 * <p><b>It is a second envelope on a topic that already carries one</b>, and that was the trade.
 * {@link RegistrationEventPublisher}'s own {@code registration.created} and {@code onboarding.state}
 * use this stack's older {@code eventType}/{@code actor}/{@code payload} envelope
 * (professional-onboarding-workflow.md § Domain events) and keep using it — they have a consumer
 * today. A reader of this topic therefore dispatches on which of {@code type} and {@code eventType}
 * is present. The alternative was to leave this stack as the one producer in the estate whose
 * account events look like nothing else's, which is what made hc-admin's directory need a
 * hc-professional-shaped branch in the first place.</p>
 *
 * <h2>What this cannot carry, which is the point worth reading</h2>
 *
 * <p><b>Neither a clinical role nor a licence number, at either moment, and not for want of a
 * field.</b> An account here is created holding {@code ROLE_USER} alone — the nine clinical
 * authorities are granted later, by an administrator, at the {@code AUTHORITY_ASSIGNED} step of the
 * onboarding state machine in {@code api/}. The discipline the applicant asked for is
 * {@code ProfessionalApplication.requestedRole}, written by the wizard in {@code api/} and never
 * seen by this gateway. And a licence <em>number</em> exists nowhere in this subsystem at all:
 * {@code PersonalDocument} of type {@code LICENSE} carries a name, a checksum, an expiry date and a
 * verification status, and no number field.</p>
 *
 * <p>So a consumer that needs those two before it will open a record — hc-admin's
 * {@code Professional} requires both, {@code @NotNull} — cannot be satisfied by an account event of
 * any version. That is a fact about where the data lives, not a gap in this envelope, and it is
 * recorded in backlog.md item 47 rather than papered over with a plausible default.</p>
 */
public record ProfessionalEvent(
    String eventId,
    String type,
    int version,
    Instant occurredAt,
    String source,
    Subject subject,
    Map<String, Object> data
) {
    public static final int VERSION = 1;

    /**
     * Who the event is about.
     *
     * <p>hc-patient's third field is the {@code patientId} its onboarding mints later; this stack's
     * is the {@code accountId}, which exists from the first moment and <b>is the correlation key on
     * this topic</b> — {@code registration.created} and {@code onboarding.state} have keyed on it
     * since WP3, and hc-admin's {@code DirectoryLink.external_key} holds it for every clinician it
     * already knows. Renaming it {@code patientId} for symmetry, or moving the correlation onto the
     * email to match hc-patient, would give one clinician two links.
     *
     * @param email lowercased before it is put here, so both sides correlate without either having
     *              to remember to. Already published on this topic by {@code registration.created},
     *              so it is not new exposure — but nothing beyond these three identifiers belongs
     *              on this stream.
     */
    public record Subject(String email, String login, String accountId) {}
}
