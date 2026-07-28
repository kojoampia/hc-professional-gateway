package net.jojoaddison.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;

/**
 * WP3 gate (professional-onboarding-workflow.md § Domain events), gateway
 * side: registration.created carries the documented envelope, is keyed by
 * accountId, distinguishes self-service from invitation origins, and never
 * throws into the registration path. (The broker round-trip itself is proven
 * by the api's Testcontainers-Kafka IT on the shared contract.)
 */
class RegistrationEventPublisherTest {

    private StreamBridge streamBridge;
    private RegistrationEventPublisher publisher;

    @BeforeEach
    void setUp() {
        streamBridge = mock(StreamBridge.class);
        publisher = new RegistrationEventPublisher(streamBridge);
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishesDocumentedEnvelopeKeyedByAccountId() {
        publisher.publishRegistrationCreated(
            "user-42",
            "ama.serwaa",
            "ama@localhost",
            "en",
            RegistrationEventPublisher.ORIGIN_SELF_SERVICE,
            "ama.serwaa"
        );

        ArgumentCaptor<Message<Map<String, Object>>> captor = ArgumentCaptor.forClass(Message.class);
        verify(streamBridge).send(eq(RegistrationEventPublisher.REGISTRATION_TOPIC_BINDING), captor.capture());

        Message<Map<String, Object>> message = captor.getValue();
        assertThat(new String((byte[]) message.getHeaders().get(KafkaHeaders.KEY))).isEqualTo("user-42");

        Map<String, Object> envelope = message.getPayload();
        assertThat(envelope.get("eventType")).isEqualTo("registration.created");
        assertThat(envelope.get("source")).isEqualTo("hc-professional-gateway");
        assertThat(envelope.get("actor")).isEqualTo("ama.serwaa");
        assertThat(envelope.get("eventId")).isNotNull();
        assertThat(envelope.get("occurredAt")).isNotNull();

        Map<String, Object> payload = (Map<String, Object>) envelope.get("payload");
        assertThat(payload).containsOnlyKeys("accountId", "login", "email", "langKey", "origin");
        assertThat(payload)
            .containsEntry("accountId", "user-42")
            .containsEntry("login", "ama.serwaa")
            .containsEntry("email", "ama@localhost")
            .containsEntry("langKey", "en")
            .containsEntry("origin", "self-service");
    }

    @Test
    @SuppressWarnings("unchecked")
    void marksInvitationOrigin() {
        publisher.publishRegistrationCreated(
            "user-42",
            "ama.serwaa",
            "ama@localhost",
            "en",
            RegistrationEventPublisher.ORIGIN_INVITATION,
            "admin"
        );

        ArgumentCaptor<Message<Map<String, Object>>> captor = ArgumentCaptor.forClass(Message.class);
        verify(streamBridge).send(eq(RegistrationEventPublisher.REGISTRATION_TOPIC_BINDING), captor.capture());
        Map<String, Object> payload = (Map<String, Object>) captor.getValue().getPayload().get("payload");
        assertThat(payload).containsEntry("origin", "invitation");
        assertThat(captor.getValue().getPayload()).containsEntry("actor", "admin");
    }

    @Test
    void neverPropagatesBrokerFailures() {
        when(
            streamBridge.send(eq(RegistrationEventPublisher.REGISTRATION_TOPIC_BINDING), org.mockito.ArgumentMatchers.any(Message.class))
        ).thenThrow(new IllegalStateException("broker down"));

        publisher.publishRegistrationCreated(
            "user-42",
            "ama.serwaa",
            "ama@localhost",
            "en",
            RegistrationEventPublisher.ORIGIN_SELF_SERVICE,
            "ama.serwaa"
        );
        // no exception — registration must not fail because Kafka is unavailable
    }
}
