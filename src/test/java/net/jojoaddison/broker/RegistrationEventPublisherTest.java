package net.jojoaddison.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
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
 *
 * <p>Extended by backlog.md item 47 with the two account events, which are a different envelope on
 * the same topic — hc-patient's {@code PatientEvent} shape, field for field. The cases below assert
 * the field <em>names</em> deliberately: they are the whole of what the two products share, and a
 * rename here is a silent decorrelation on the far side rather than a compile error on this one.
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

    // --- backlog.md item 47: the two account events, in hc-patient's shape ------------------------

    /**
     * The field names are the assertion. They are what makes this frame readable by a consumer
     * written against hc-patient's {@code PatientEvent}, and nothing else about it is shared.
     */
    @Test
    void publishesAccountCreatedInTheEstateShape() {
        publisher.publishAccountCreated("user-42", "ama.serwaa", "  Ama@LOCALHOST ", "en", "ROLE_USER", false);

        ProfessionalEvent event = captureAccountEvent();
        assertThat(event.eventId()).isNotNull();
        assertThat(event.type()).isEqualTo("AccountCreated");
        assertThat(event.version()).isEqualTo(1);
        assertThat(event.occurredAt()).isNotNull();
        assertThat(event.source()).isEqualTo("hc-professional-gateway");
        // Lowercased and trimmed here rather than trusted from the caller, so this side and
        // hc-patient's correlate on the same string.
        assertThat(event.subject()).isEqualTo(new ProfessionalEvent.Subject("ama@localhost", "user-42"));
        assertThat(event.data()).containsOnlyKeys("username", "authorities", "langKey", "activated");
        assertThat(event.data())
            .containsEntry("username", "ama.serwaa")
            .containsEntry("authorities", "ROLE_USER")
            .containsEntry("langKey", "en")
            .containsEntry("activated", false);
    }

    /**
     * hc-admin's directory is specified to display the professional's username, and this is the only
     * frame that carries it — the profile half publishes identifiers only. It rode in the subject
     * until the join was narrowed to {@code accountId} alone, and moved here rather than being
     * dropped: without it hc-admin has a column it must show and nothing to put in it.
     *
     * <p>It is <b>data, not a key</b>. Nothing correlates on it, and a login can be edited in user
     * management without orphaning anything.
     */
    @Test
    void theAccountFramesCarryTheUsernameForDisplayAndNotAsAJoinKey() {
        publisher.publishAccountCreated("user-42", "ama.serwaa", "ama@localhost", "en", "ROLE_USER", false);
        assertThat(captureAccountEvent().data()).containsEntry("username", "ama.serwaa");

        reset(streamBridge);

        // Repeated on activation rather than assumed from the earlier frame: at-least-once delivery
        // is not at-least-once ordering, so a consumer can legitimately see this one first.
        publisher.publishAccountActivated("user-42", "ama.serwaa", "ama@localhost");
        ProfessionalEvent activated = captureAccountEvent();
        assertThat(activated.data()).containsEntry("username", "ama.serwaa");
        assertThat(activated.subject().accountId()).isEqualTo("user-42");
    }

    /**
     * The account facts and no more. A clinical role and a licence number are the two fields
     * hc-admin needs to open a {@code Professional}, and neither exists in this stack at
     * registration — see {@link ProfessionalEvent}. This asserts the absence so that adding
     * either becomes a deliberate act with a failing test in front of it.
     */
    @Test
    void accountCreatedCarriesNoClinicalIdentity() {
        publisher.publishAccountCreated("user-42", "ama.serwaa", "ama@localhost", "en", "ROLE_USER", false);

        assertThat(captureAccountEvent().data()).doesNotContainKeys("role", "requestedRole", "licenceNumber", "licenseNumber");
    }

    /** An administrator-created account can arrive usable; the flag says so on the first frame. */
    @Test
    void accountCreatedReportsAnAlreadyActivatedAccount() {
        publisher.publishAccountCreated("user-42", "kofi.admin", "kofi@localhost", "fr", "ROLE_ADMIN,ROLE_USER", true);

        assertThat(captureAccountEvent().data()).containsEntry("activated", true).containsEntry("langKey", "fr");
    }

    /** The moment that published nothing at all before item 47. */
    @Test
    void publishesAccountActivated() {
        publisher.publishAccountActivated("user-42", "ama.serwaa", "ama@localhost");

        ProfessionalEvent event = captureAccountEvent();
        assertThat(event.type()).isEqualTo("AccountActivated");
        assertThat(event.version()).isEqualTo(1);
        assertThat(event.subject().accountId()).isEqualTo("user-42");
        assertThat(event.data()).containsOnlyKeys("activatedAt", "username");
    }

    /**
     * The key is unchanged, and that is the point of asserting it on the new events too. hc-admin
     * builds a {@code DirectoryLink} per {@code accountId}; keying these on the email to match
     * hc-patient would give one clinician two links.
     */
    @Test
    void accountEventsAreKeyedByAccountIdLikeEverythingElseOnThisTopic() {
        publisher.publishAccountActivated("user-42", "ama.serwaa", "ama@localhost");

        ArgumentCaptor<Message<ProfessionalEvent>> captor = accountCaptor();
        verify(streamBridge).send(eq(RegistrationEventPublisher.REGISTRATION_TOPIC_BINDING), captor.capture());
        assertThat(new String((byte[]) captor.getValue().getHeaders().get(KafkaHeaders.KEY), StandardCharsets.UTF_8)).isEqualTo("user-42");
    }

    @Test
    void neverPropagatesBrokerFailuresFromTheAccountEvents() {
        when(
            streamBridge.send(eq(RegistrationEventPublisher.REGISTRATION_TOPIC_BINDING), org.mockito.ArgumentMatchers.any(Message.class))
        ).thenThrow(new IllegalStateException("broker down"));

        publisher.publishAccountCreated("user-42", "ama.serwaa", "ama@localhost", "en", "ROLE_USER", false);
        publisher.publishAccountActivated("user-42", "ama.serwaa", "ama@localhost");
        // no exception — neither registration nor activation may fail because Kafka is unavailable
    }

    private ProfessionalEvent captureAccountEvent() {
        ArgumentCaptor<Message<ProfessionalEvent>> captor = accountCaptor();
        verify(streamBridge).send(eq(RegistrationEventPublisher.REGISTRATION_TOPIC_BINDING), captor.capture());
        return captor.getValue().getPayload();
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Message<ProfessionalEvent>> accountCaptor() {
        return ArgumentCaptor.forClass(Message.class);
    }
}
