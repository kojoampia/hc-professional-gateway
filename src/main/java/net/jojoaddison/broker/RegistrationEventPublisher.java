package net.jojoaddison.broker;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * Publishes everything this gateway puts on {@code hc.professional.registration} for the admin
 * portal: {@code registration.created}, the opening {@code onboarding.state}
 * (professional-onboarding-workflow.md § Domain events, and § "Onboarding state events and the
 * completion contract"), and the two estate-shaped account events
 * {@link ProfessionalEventType}. Fired for both self-service registration and
 * administrator-created (invitation) accounts. Every record is keyed by accountId; publishing never
 * breaks the write path — failures are logged, not propagated.
 *
 * <h2>Two envelopes on one topic, on purpose</h2>
 *
 * <p>{@code registration.created} and {@code onboarding.state} keep this stack's original
 * {@code eventType}/{@code actor}/{@code payload} envelope. {@code AccountCreated} and
 * {@code AccountActivated} use {@link ProfessionalEvent}, which is hc-patient's shape field
 * for field. <b>The older two are not replaced and not deprecated</b>: they are a published contract
 * with a live consumer — hc-admin writes a {@code DirectoryLink} from them today — and withdrawing
 * them to reduce the frame count would break the one thing on this topic that currently works. The
 * cost is three frames per registration instead of two, all keyed identically, so they share a
 * partition and arrive in the order they are sent.
 *
 * <p><b>A consumer still reading only {@code eventType} will log the new frames as unreadable.</b>
 * hc-admin's {@code SiblingEventParser} checks for {@code eventType} before it checks the type, so
 * until it gains a branch for {@code type} it warns once per new frame and applies nothing. That is
 * noise on somebody else's log rather than a stall — nothing throws, no partition blocks — and it is
 * the honest price of putting a second shape on a shared topic. Named here so it is found by reading
 * rather than by wondering.
 *
 * <h2>Nothing on this topic is scheduled here</h2>
 *
 * <p>{@code StreamBridge.send} resolves a binding, serialises and hands off to the producer, and
 * none of that is guaranteed non-blocking; {@code UUID.randomUUID()} in the envelope build draws on
 * SecureRandom and can block on its own. This is a reactive gateway, so <b>every caller wraps the
 * whole call — build and send — in {@code Mono.fromRunnable(...).subscribeOn(boundedElastic())}</b>
 * rather than only the send. hc-patient's {@code PatientEventPublisher} schedules inside itself
 * instead; the difference is deliberate here, because this class's callers publish two or three
 * events that must reach the partition in a defined order, and self-scheduling each one would leave
 * that order to the pool.
 */
@Component
public class RegistrationEventPublisher {

    public static final String REGISTRATION_TOPIC_BINDING = "registrationEvents-out-0";
    public static final String ORIGIN_SELF_SERVICE = "self-service";
    public static final String ORIGIN_INVITATION = "invitation";

    private static final String SOURCE = "hc-professional-gateway";

    private static final Logger log = LoggerFactory.getLogger(RegistrationEventPublisher.class);

    private final StreamBridge streamBridge;

    public RegistrationEventPublisher(StreamBridge streamBridge) {
        this.streamBridge = streamBridge;
    }

    /**
     * The opening state of a clinician's onboarding, emitted beside {@code registration.created}.
     *
     * <p>Same topic and key, so one consumer following one {@code accountId} sees the whole
     * sequence in order: {@code IN_PROGRESS} here, then {@code COMPLETED} and {@code ACTIVE} from
     * {@code api/} as the application advances. The state is in the payload rather than the event
     * type so a consumer switches on one field.
     */
    public void publishOnboardingInProgress(String accountId, String login, String actor) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("accountId", accountId);
        payload.put("state", "IN_PROGRESS");
        send("onboarding.state", accountId, payload, login, actor);
    }

    public void publishRegistrationCreated(String accountId, String login, String email, String langKey, String origin, String actor) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("accountId", accountId);
        payload.put("login", login);
        payload.put("email", email);
        payload.put("langKey", langKey);
        payload.put("origin", origin);
        send("registration.created", accountId, payload, login, actor);
    }

    /**
     * The account exists and can be correlated across the estate.
     *
     * <p>Published from both origins, and beside {@code registration.created} rather than instead of
     * it — see the class comment. {@code data} is hc-patient's three keys and no more: an account
     * event says who signed up and in what language, and <b>says nothing about what they are
     * clinically</b>, because at this moment nothing does. See {@link ProfessionalEvent} for
     * why no version of this event can carry a role or a licence number.
     *
     * @param authorities comma-joined and sorted by the caller, matching hc-patient exactly. Sorted
     *                    so two frames for the same account are byte-identical rather than
     *                    differing by {@code HashSet} iteration order; joined rather than nested so
     *                    a consumer reads one field. At registration this is {@code ROLE_USER}
     *                    alone.
     * @param activated false for a self-service registration awaiting its email link, and possibly
     *                  true for an administrator-created account. Carried so a consumer that acts
     *                  only on usable accounts need not wait for {@code AccountActivated} on a
     *                  frame that already answers the question.
     */
    public void publishAccountCreated(String accountId, String login, String email, String langKey, String authorities, boolean activated) {
        Map<String, Object> data = new LinkedHashMap<>();
        // The username hc-admin's directory displays. It rode in the subject until the join was
        // narrowed to accountId alone; it belongs here instead, where it reads as what it is —
        // display data, not an identifier anything correlates on. Dropping it entirely would leave
        // hc-admin with a column it is specified to show and no value to put in it, and this is the
        // only frame that carries it: the profile half publishes identifiers only.
        data.put("username", login);
        data.put("authorities", authorities);
        data.put("langKey", String.valueOf(langKey));
        data.put("activated", activated);
        sendAccountEvent(ProfessionalEventType.ACCOUNT_CREATED, accountId, login, email, data);
    }

    /**
     * The account is now usable — the activation link was followed and the key consumed.
     *
     * <p><b>This moment published nothing at all until item 47</b>, so the one point at which a
     * clinician's account stops being a pending registration was invisible to the estate.
     *
     * <p>{@code activatedAt} duplicates the envelope's {@code occurredAt} and is kept anyway,
     * because it is what hc-patient's {@code AccountActivated} carries and the value of one shape is
     * that a reader does not have to check which producer sent a frame before reading it.
     */
    public void publishAccountActivated(String accountId, String login, String email) {
        sendAccountEvent(
            ProfessionalEventType.ACCOUNT_ACTIVATED,
            accountId,
            login,
            email,
            // username repeated rather than assumed: at-least-once delivery is not
            // at-least-once *ordering*, so a consumer can see this frame first.
            Map.of("activatedAt", Instant.now().toString(), "username", login)
        );
    }

    private void send(String eventType, String accountId, Map<String, Object> payload, String login, String actor) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("eventType", eventType);
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("source", SOURCE);
        envelope.put("actor", actor);
        envelope.put("payload", payload);
        dispatch(eventType, accountId, envelope, login);
    }

    private void sendAccountEvent(String type, String accountId, String login, String email, Map<String, Object> data) {
        ProfessionalEvent event = new ProfessionalEvent(
            UUID.randomUUID().toString(),
            type,
            ProfessionalEvent.VERSION,
            Instant.now(),
            SOURCE,
            // accountId ONLY — the gateway's User.id, and the estate's sole correlation key for a
            // professional. hc-admin's SiblingDomainEvent has said so all along: "the correlation
            // key: lowercased email for a patient, accountId for a professional." The login used to
            // ride here beside it; two join keys is two answers to "is this the same clinician", and
            // they diverge the moment a login is edited in user management.
            new ProfessionalEvent.Subject(normaliseEmail(email), accountId),
            data
        );
        dispatch(type, accountId, event, login);
    }

    /**
     * One send, one catch, for both envelopes.
     *
     * <p>The key is the accountId's bytes on {@link KafkaHeaders#KEY}, which is how this topic has
     * been keyed since WP3 and is what hc-admin's links are built on. Explicitly UTF-8 where it used
     * to take the platform default — the value is a Mongo id, so the bytes are the same either way,
     * and a partition key that depends on a locale is not worth keeping.
     */
    private void dispatch(String eventType, String accountId, Object envelope, String login) {
        try {
            streamBridge.send(
                REGISTRATION_TOPIC_BINDING,
                MessageBuilder.withPayload(envelope).setHeader(KafkaHeaders.KEY, accountId.getBytes(StandardCharsets.UTF_8)).build()
            );
        } catch (RuntimeException e) {
            log.error("Failed to publish {} for {}", eventType, login, e);
        }
    }

    /** Lowercased and trimmed here so this side and hc-patient's correlate without either remembering to. */
    private String normaliseEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
