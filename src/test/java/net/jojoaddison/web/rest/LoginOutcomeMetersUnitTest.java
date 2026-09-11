package net.jojoaddison.web.rest;

import static net.jojoaddison.management.SecurityMetersService.LOGINS_METER_NAME;
import static net.jojoaddison.management.SecurityMetersService.LOGINS_METER_OUTCOME_DIMENSION;
import static net.jojoaddison.management.SecurityMetersService.LOGIN_OUTCOME_REFUSED;
import static net.jojoaddison.management.SecurityMetersService.LOGIN_OUTCOME_SUCCESS;
import static net.jojoaddison.management.SecurityMetersService.LOGIN_OUTCOME_UNAVAILABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import net.jojoaddison.domain.Authority;
import net.jojoaddison.domain.User;
import net.jojoaddison.management.SecurityMetersService;
import net.jojoaddison.repository.UserRepository;
import net.jojoaddison.security.AuthoritiesConstants;
import net.jojoaddison.security.DomainUserDetailsService;
import net.jojoaddison.security.jwt.TokenProvider;
import net.jojoaddison.service.RefreshTokenService;
import net.jojoaddison.web.rest.vm.LoginVM;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.UserDetailsRepositoryReactiveAuthenticationManager;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import reactor.core.publisher.Mono;

/**
 * What {@code POST /api/authenticate} counts, and — more to the point — what it does not.
 *
 * <p>The subject of this class is the classification, so it drives the <strong>real</strong>
 * {@link UserDetailsRepositoryReactiveAuthenticationManager} over the <strong>real</strong>
 * {@link DomainUserDetailsService} with only the Mongo repository stubbed. Which exception Spring Security actually
 * raises for a wrong password, for a login nobody holds, for an unactivated account and for a user store that cannot
 * be reached is the whole question here (see {@code docs/backlog.md} item 96), and it is worth answering by asking it
 * rather than by reading the class hierarchy.</p>
 *
 * <p>Every case asserts the two counters that must <em>not</em> move as well as the one that must. A meter that
 * increments on every outcome passes any test shaped "it counts" and tells an operator nothing.</p>
 */
class LoginOutcomeMetersUnitTest {

    private static final String LOGIN = "outcome-meters-user";
    private static final String PASSWORD = "the-right-password";

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private MeterRegistry meterRegistry;
    private UserRepository userRepository;
    private RefreshTokenService refreshTokenService;
    private AuthenticateController controller;

    @BeforeEach
    void setup() {
        meterRegistry = new SimpleMeterRegistry();
        SecurityMetersService metersService = new SecurityMetersService(meterRegistry);

        userRepository = mock(UserRepository.class);
        UserDetailsRepositoryReactiveAuthenticationManager authenticationManager = new UserDetailsRepositoryReactiveAuthenticationManager(
            new DomainUserDetailsService(userRepository)
        );
        authenticationManager.setPasswordEncoder(passwordEncoder);

        TokenProvider tokenProvider = mock(TokenProvider.class);
        when(tokenProvider.createAccessToken(any(), any())).thenReturn("a-signed-token");

        refreshTokenService = mock(RefreshTokenService.class);
        when(tokenProvider.authorityString(any())).thenReturn(AuthoritiesConstants.USER);

        controller = new AuthenticateController(authenticationManager, tokenProvider, refreshTokenService, metersService);
    }

    @Test
    void aGoodPasswordCountsSuccessAndNothingElse() {
        userExists(true);

        attemptLogin(LOGIN, PASSWORD);

        assertOnlyOutcomeCounted(LOGIN_OUTCOME_SUCCESS);
    }

    @Test
    void aRefusedPasswordCountsRefusedAndNotSuccess() {
        userExists(true);

        attemptLogin(LOGIN, "not-the-right-password");

        assertOnlyOutcomeCounted(LOGIN_OUTCOME_REFUSED);
    }

    @Test
    void aLoginNobodyHoldsIsRefusedRatherThanUnavailable() {
        // DomainUserDetailsService turns an empty result into UsernameNotFoundException. Nothing failed: the
        // gateway asked and the answer was no.
        when(userRepository.findOneByLogin(LOGIN)).thenReturn(Mono.empty());

        attemptLogin(LOGIN, PASSWORD);

        assertOnlyOutcomeCounted(LOGIN_OUTCOME_REFUSED);
    }

    @Test
    void anUnactivatedAccountIsRefusedRatherThanUnavailable() {
        // Every self-registration sits in this state until the activation link is clicked, so this is the most
        // common refusal on the system after a typo. It is refused with the rest deliberately — see
        // SecurityMetersService.trackLoginRefused for why splitting it out would hand back the enumeration oracle
        // AuthenticateControllerIT.anUnactivatedAccountLooksExactlyLikeAMissingOne closes.
        userExists(false);

        attemptLogin(LOGIN, PASSWORD);

        assertOnlyOutcomeCounted(LOGIN_OUTCOME_REFUSED);
    }

    @Test
    void anUnreachableUserStoreIsUnavailableRatherThanRefused() {
        // The case this meter exists for, and the one item 83 is about at a different site: with a single failure
        // counter, a Mongo outage arrives on the dashboard as a wall of wrong passwords and the operator spends the
        // outage reading about credentials.
        when(userRepository.findOneByLogin(LOGIN)).thenReturn(Mono.error(new DataAccessResourceFailureException("no route to mongo")));

        Throwable raised = attemptLogin(LOGIN, PASSWORD);

        assertOnlyOutcomeCounted(LOGIN_OUTCOME_UNAVAILABLE);

        // What Spring Security hands the controller for a dead user store is the other half of the classification,
        // so it is asserted rather than assumed. Either shape is fine — the manager may pass the data-access failure
        // through untouched, or wrap it as AuthenticationServiceException, which is documented as the failure "due to
        // a system problem". What must never happen is for it to arrive as a plain AuthenticationException, because
        // that is the shape of a refusal and the meter would then call an outage a wrong password.
        assertThat(raised)
            .as("a dead user store arrived as %s", raised.getClass().getName())
            .satisfiesAnyOf(
                error -> assertThat(error).isNotInstanceOf(AuthenticationException.class),
                error -> assertThat(error).isInstanceOf(AuthenticationServiceException.class)
            );
    }

    /**
     * The mobile branch, which the browser cases never enter.
     *
     * <p>{@code authorize} puts its {@code doOnNext} <em>after</em> the response {@code flatMap} so that success
     * means "the caller got a token" rather than "the password matched" — and the only branch where those two differ
     * is this one, because it persists a refresh token after the credential is accepted. Without a mobile case that
     * reason is asserted in a comment and exercised by nothing.</p>
     */
    @Test
    void aMobileSignInCountsSuccessOnceTheRefreshTokenIsIssued() {
        userExists(true);
        when(refreshTokenService.issue(any(), any(), any(), any(), any(), any())).thenReturn(
            Mono.just(new RefreshTokenService.TokenPair("access", "refresh", 900))
        );

        attemptMobileLogin(LOGIN, PASSWORD);

        assertOnlyOutcomeCounted(LOGIN_OUTCOME_SUCCESS);
    }

    @Test
    void aMobileSignInWhoseRefreshTokenCannotBePersistedIsUnavailableAndNotASuccess() {
        // The credential was accepted and the clinician still cannot sign in. Counting this as a success — which is
        // what tracking the authentication step alone would do — would leave the failure on no panel at all, and
        // counting it as `refused` would blame the password. It is the gateway failing, so it is `unavailable`.
        userExists(true);
        when(refreshTokenService.issue(any(), any(), any(), any(), any(), any())).thenReturn(
            Mono.error(new DataAccessResourceFailureException("no route to mongo"))
        );

        attemptMobileLogin(LOGIN, PASSWORD);

        assertOnlyOutcomeCounted(LOGIN_OUTCOME_UNAVAILABLE);
    }

    private void userExists(boolean activated) {
        User user = new User();
        user.setId("uid-1");
        user.setLogin(LOGIN);
        user.setEmail(LOGIN + "@example.com");
        user.setActivated(activated);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        Authority authority = new Authority();
        authority.setName(AuthoritiesConstants.USER);
        user.setAuthorities(Set.of(authority));
        when(userRepository.findOneByLogin(LOGIN)).thenReturn(Mono.just(user));
    }

    /**
     * Drives one sign-in and returns whatever the controller propagated, or {@code null} if it succeeded.
     *
     * <p>The failure is absorbed here because in the running application it goes to {@code ExceptionTranslator} and
     * becomes a 401 or a 500; what this class is about is what was counted on the way past.</p>
     */
    /** The same drive, with {@code client} set so {@link LoginVM#isMobileClient()} takes the refresh-token path. */
    private void attemptMobileLogin(String username, String password) {
        LoginVM login = new LoginVM();
        login.setUsername(username);
        login.setPassword(password);
        login.setClient("ios");
        login.setDeviceId("device-1");
        login.setDeviceName("a phone");
        controller.authorize(Mono.just(login)).onErrorResume(error -> Mono.empty()).block();
    }

    private Throwable attemptLogin(String username, String password) {
        LoginVM login = new LoginVM();
        login.setUsername(username);
        login.setPassword(password);
        AtomicReference<Throwable> raised = new AtomicReference<>();
        controller
            .authorize(Mono.just(login))
            .onErrorResume(error -> {
                raised.set(error);
                return Mono.empty();
            })
            .block();
        return raised.get();
    }

    private void assertOnlyOutcomeCounted(String expectedOutcome) {
        for (String outcome : Set.of(LOGIN_OUTCOME_SUCCESS, LOGIN_OUTCOME_REFUSED, LOGIN_OUTCOME_UNAVAILABLE)) {
            double count = meterRegistry.get(LOGINS_METER_NAME).tag(LOGINS_METER_OUTCOME_DIMENSION, outcome).counter().count();
            assertThat(count).as("outcome=%s", outcome).isEqualTo(outcome.equals(expectedOutcome) ? 1 : 0);
        }
    }
}
