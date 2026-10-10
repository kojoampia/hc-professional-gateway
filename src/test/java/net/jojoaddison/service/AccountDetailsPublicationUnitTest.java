package net.jojoaddison.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Set;
import net.jojoaddison.broker.RegistrationEventPublisher;
import net.jojoaddison.domain.User;
import net.jojoaddison.repository.AuthorityRepository;
import net.jojoaddison.repository.UserRepository;
import net.jojoaddison.service.dto.AdminUserDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import reactor.core.publisher.Mono;

/**
 * {@code AccountDetailsUpdated} is published from {@code UserService.saveUser} — the single funnel
 * every {@code User} write in this gateway passes through (backlog.md row 230, unit A).
 *
 * <p><b>Why this class asserts the <em>site</em> and not the frame.</b>
 * {@code RegistrationEventPublisherTest} proves the frame's shape; nothing there can tell whether
 * anything ever calls it. That split is backlog.md item 47's own lesson — activation had a correct
 * publisher method and no caller, and published nothing at all for months — and it is the shape of
 * failure a meter fed by events inherits: a producer writing where nobody reads looks exactly like a
 * producer working.
 *
 * <p>A plain unit test rather than an addition to {@code UserServiceIT}, for
 * {@code AccountEventPublicationUnitTest}'s reason: that suite runs against a real
 * {@code StreamBridge} with no broker, where a publish that never happened and a publish that failed
 * silently are indistinguishable.
 *
 * <p>The verifications carry a {@code timeout} because the publish is fire-and-forget on the
 * bounded-elastic scheduler and deliberately does not join the write's chain. That is the property
 * being relied on, not an accident — see {@code UserService.saveUser}.
 */
class AccountDetailsPublicationUnitTest {

    private UserRepository userRepository;
    private RegistrationEventPublisher events;
    private UserService userService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        events = mock(RegistrationEventPublisher.class);
        userService = new UserService(userRepository, mock(PasswordEncoder.class), mock(AuthorityRepository.class), events);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
    }

    /**
     * The administrator's edit in user management writes the same four fields step 1 does, so it must
     * announce them too. It reaches {@code saveUser} like every other write, which is the whole point
     * of publishing from there.
     */
    @Test
    void anAdministratorEditingAnAccountAnnouncesTheVerdict() {
        when(userRepository.findById("user-42")).thenReturn(Mono.just(storedAccount()));

        userService.updateUser(completeAccountUpdate()).block();

        verify(events, timeout(2000)).publishAccountDetailsUpdated("user-42", "ama.serwaa", "ama@localhost", true);
    }

    /**
     * ⭐ <b>The verdict is computed on the row as saved, not on what it was before.</b> An edit that
     * <em>clears</em> a step-1 field has to move the meter back down, and a consumer that only ever
     * received {@code true} could never do it. This is the case that would pass over a publisher
     * hard-coded to announce completeness.
     */
    @Test
    void anEditThatEmptiesAStepOneFieldAnnouncesFalse() {
        when(userRepository.findById("user-42")).thenReturn(Mono.just(storedAccount()));

        AdminUserDTO update = completeAccountUpdate();
        update.setImageUrl(null);

        userService.updateUser(update).block();

        verify(events, timeout(2000)).publishAccountDetailsUpdated("user-42", "ama.serwaa", "ama@localhost", false);
    }

    /**
     * ⛔ The write path is the account, not the event. A broker that refuses must cost a frame and
     * never a saved row — the rule both existing publishers here state, and the one a consumer-side
     * fix cannot restore.
     */
    @Test
    void aRefusedPublishDoesNotFailTheWrite() {
        when(userRepository.findById("user-42")).thenReturn(Mono.just(storedAccount()));
        doThrow(new IllegalStateException("broker down"))
            .when(events)
            .publishAccountDetailsUpdated(anyString(), anyString(), anyString(), anyBoolean());

        AdminUserDTO saved = userService.updateUser(completeAccountUpdate()).block();

        assertThat(saved).isNotNull();
        assertThat(saved.getLogin()).isEqualTo("ama.serwaa");
    }

    /**
     * ⚠ <b>Every write announces, including one that touches none of the four fields</b> — here,
     * following the activation link. That is the cost {@code UserService.saveUser} accepts in
     * exchange for a site no future write path can miss, and it is asserted rather than left as a
     * comment so that narrowing it to "only when it changed" becomes a deliberate act with a failing
     * test in front of it.
     *
     * <p>It is also the one case that shows the funnel working: nothing in
     * {@code activateRegistration} mentions this event, step 1 or the four fields.
     */
    @Test
    void aWriteThatTouchesNoneOfTheFourFieldsStillAnnounces() {
        when(userRepository.findOneByActivationKey("an-activation-key")).thenReturn(Mono.just(storedAccount()));

        userService.activateRegistration("an-activation-key").block();

        ArgumentCaptor<Boolean> verdict = ArgumentCaptor.forClass(Boolean.class);
        verify(events, timeout(2000)).publishAccountDetailsUpdated(eq("user-42"), eq("ama.serwaa"), eq("ama@localhost"), verdict.capture());
        assertThat(verdict.getValue()).isTrue();
    }

    /** The stored row, complete by {@link AccountCompleteness}. */
    private User storedAccount() {
        User user = new User();
        user.setId("user-42");
        user.setLogin("ama.serwaa");
        user.setEmail("ama@localhost");
        user.setFirstName("Ama");
        user.setLastName("Serwaa");
        user.setLangKey("en");
        user.setImageUrl("https://example.invalid/ama.png");
        user.setResetKey("a-reset-key");
        user.setResetDate(java.time.Instant.now());
        return user;
    }

    /**
     * The administrator's body. {@code authorities} is an empty set rather than null because
     * {@code updateUser} streams it; which authorities an account holds is not this class's subject.
     */
    private AdminUserDTO completeAccountUpdate() {
        AdminUserDTO dto = new AdminUserDTO();
        dto.setId("user-42");
        dto.setLogin("ama.serwaa");
        dto.setEmail("ama@localhost");
        dto.setFirstName("Ama");
        dto.setLastName("Serwaa");
        dto.setLangKey("en");
        dto.setImageUrl("https://example.invalid/ama.png");
        dto.setActivated(true);
        dto.setAuthorities(Set.of());
        return dto;
    }
}
