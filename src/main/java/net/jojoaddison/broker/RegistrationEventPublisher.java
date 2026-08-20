package net.jojoaddison.broker;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * Publishes {@code registration.created} and the opening {@code onboarding.state} event to
 * {@code hc.professional.registration} for the admin portal
 * (professional-onboarding-workflow.md § Domain events, and § "Onboarding state events and the
 * completion contract"). Fired for both self-service registration and administrator-created
 * (invitation) accounts. Records are keyed by accountId; publishing never breaks the registration
 * path — failures are logged, not propagated.
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

    private void send(String eventType, String accountId, Map<String, Object> payload, String login, String actor) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("eventType", eventType);
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("source", SOURCE);
        envelope.put("actor", actor);
        envelope.put("payload", payload);
        try {
            streamBridge.send(
                REGISTRATION_TOPIC_BINDING,
                MessageBuilder.withPayload(envelope).setHeader(KafkaHeaders.KEY, accountId.getBytes()).build()
            );
        } catch (RuntimeException e) {
            log.error("Failed to publish {} for {}", eventType, login, e);
        }
    }
}
