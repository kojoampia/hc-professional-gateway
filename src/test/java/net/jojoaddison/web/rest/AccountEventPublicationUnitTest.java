package net.jojoaddison.web.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Set;
import net.jojoaddison.broker.RegistrationEventPublisher;
import net.jojoaddison.domain.Authority;
import net.jojoaddison.domain.User;
import net.jojoaddison.repository.UserRepository;
import net.jojoaddison.service.LoginAvailabilityService;
import net.jojoaddison.service.MailService;
import net.jojoaddison.service.UserService;
import net.jojoaddison.web.rest.vm.ManagedUserVM;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * backlog.md item 47, defect 2: <b>activation published nothing at all.</b>
 *
 * <p>{@code RegistrationEventPublisherTest} proves the frames are the right shape; this proves the
 * two call sites actually send them, which is the half that was missing. Deliberately a plain unit
 * test rather than an addition to {@code AccountResourceIT}: that suite runs against a real
 * {@code StreamBridge} with no broker, where a publish that never happens and a publish that fails
 * silently look identical — which is precisely the condition this defect lived in.
 *
 * <p>The verifications carry a {@code timeout} because the activation publish is fire-and-forget on
 * the bounded-elastic scheduler and does not join the request's chain. That is the property being
 * relied on rather than an accident: activation must not wait on a broker.
 */
class AccountEventPublicationUnitTest {

    private UserService userService;
    private RegistrationEventPublisher events;
    private AccountResource resource;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        events = mock(RegistrationEventPublisher.class);
        resource = new AccountResource(
            mock(UserRepository.class),
            userService,
            mock(MailService.class),
            events,
            mock(LoginAvailabilityService.class)
        );
    }

    @Test
    void activationPublishesAccountActivated() {
        when(userService.activateRegistration("a-key")).thenReturn(Mono.just(registeredUser()));

        resource.activateAccount("a-key").block();

        verify(events, timeout(2000)).publishAccountActivated("user-42", "ama.serwaa", "ama@localhost");
    }

    /**
     * The write path is the account, not the event. Activation has already consumed the key and
     * saved the row by the time anything is published, so a broker that refuses must cost an event
     * and never an account.
     */
    @Test
    void aRefusedBrokerDoesNotFailActivation() {
        when(userService.activateRegistration("a-key")).thenReturn(Mono.just(registeredUser()));
        doThrow(new IllegalStateException("broker down")).when(events).publishAccountActivated(anyString(), anyString(), anyString());

        assertThat(resource.activateAccount("a-key").block()).isNull();
    }

    /**
     * Registration sends {@code AccountCreated} beside the two events it already sent, with the
     * authorities comma-joined and sorted — {@code ROLE_USER} alone at this point, which is the
     * fact that makes a clinical role impossible to carry here.
     */
    @Test
    void registrationPublishesAccountCreatedBesideTheExistingTwo() {
        when(userService.registerUser(any(), anyString())).thenReturn(Mono.just(registeredUser()));

        resource.registerAccount(managedUserVM()).block();

        verify(events, timeout(2000)).publishAccountCreated("user-42", "ama.serwaa", "ama@localhost", "en", "ROLE_USER", false);
        verify(events, timeout(2000)).publishRegistrationCreated(
            "user-42",
            "ama.serwaa",
            "ama@localhost",
            "en",
            RegistrationEventPublisher.ORIGIN_SELF_SERVICE,
            "ama.serwaa"
        );
        verify(events, timeout(2000)).publishOnboardingInProgress("user-42", "ama.serwaa", "ama.serwaa");
    }

    @Test
    void aRefusedBrokerDoesNotFailRegistration() {
        when(userService.registerUser(any(), anyString())).thenReturn(Mono.just(registeredUser()));
        doThrow(new IllegalStateException("broker down"))
            .when(events)
            .publishAccountCreated(anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean());

        // The publisher swallows its own failures; this asserts the caller does not reintroduce
        // them by subscribing the chain into the request.
        assertThat(resource.registerAccount(managedUserVM()).block()).isNull();
    }

    private User registeredUser() {
        User user = new User();
        user.setId("user-42");
        user.setLogin("ama.serwaa");
        user.setEmail("ama@localhost");
        user.setLangKey("en");
        user.setActivated(false);
        Authority role = new Authority();
        role.setName("ROLE_USER");
        user.setAuthorities(Set.of(role));
        return user;
    }

    private ManagedUserVM managedUserVM() {
        ManagedUserVM vm = new ManagedUserVM();
        vm.setLogin("ama.serwaa");
        vm.setEmail("ama@localhost");
        vm.setLangKey("en");
        vm.setPassword("a-long-enough-password");
        return vm;
    }
}
