package net.jojoaddison.web.rest;

import static net.jojoaddison.management.SecurityMetersService.LOGINS_METER_NAME;
import static net.jojoaddison.management.SecurityMetersService.LOGINS_METER_OUTCOME_DIMENSION;
import static net.jojoaddison.management.SecurityMetersService.LOGIN_OUTCOME_REFUSED;
import static net.jojoaddison.management.SecurityMetersService.LOGIN_OUTCOME_SUCCESS;
import static net.jojoaddison.management.SecurityMetersService.LOGIN_OUTCOME_UNAVAILABLE;
import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.User;
import net.jojoaddison.repository.UserRepository;
import net.jojoaddison.web.rest.vm.LoginVM;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * The sign-in counters, driven through the real endpoint rather than through the controller object.
 *
 * <p>{@code LoginOutcomeMetersUnitTest} settles which exception means what; what this class settles is that the
 * counting is actually wired into the running application — a meter nobody increments is the failure mode this whole
 * item exists to remove, and it looks exactly like a working one from inside the source. See {@code docs/backlog.md}
 * item 96.</p>
 */
@AutoConfigureWebTestClient(timeout = IntegrationTest.DEFAULT_TIMEOUT)
@IntegrationTest
class LoginOutcomeMetersIT {

    private static final String PASSWORD = "the-right-password";

    @Autowired
    private ObjectMapper om;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void aGoodPasswordCountsOneSuccessAndNoFailure() {
        User user = account("login-meters-good", true);
        Map<String, Double> before = loginCounts();

        signIn(user.getLogin(), PASSWORD).expectStatus().isOk();

        assertOnlyOutcomeMoved(before, LOGIN_OUTCOME_SUCCESS);

        userRepository.delete(user).block();
    }

    @Test
    void aRefusedPasswordCountsOneRefusalAndNoSuccess() {
        User user = account("login-meters-wrong-password", true);
        Map<String, Double> before = loginCounts();

        signIn(user.getLogin(), "not-the-right-password").expectStatus().isUnauthorized();

        assertOnlyOutcomeMoved(before, LOGIN_OUTCOME_REFUSED);

        userRepository.delete(user).block();
    }

    @Test
    void anUnactivatedAccountCountsARefusalRatherThanAnOutage() {
        User user = account("login-meters-unactivated", false);
        Map<String, Double> before = loginCounts();

        signIn(user.getLogin(), PASSWORD).expectStatus().isUnauthorized();

        assertOnlyOutcomeMoved(before, LOGIN_OUTCOME_REFUSED);

        userRepository.delete(user).block();
    }

    private User account(String login, boolean activated) {
        User user = new User();
        user.setLogin(login);
        user.setEmail(login + "@example.com");
        user.setActivated(activated);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        return userRepository.save(user).block();
    }

    private WebTestClient.ResponseSpec signIn(String username, String password) {
        LoginVM login = new LoginVM();
        login.setUsername(username);
        login.setPassword(password);
        return webTestClient
            .post()
            .uri("/api/authenticate")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(login))
            .exchange();
    }

    private Map<String, Double> loginCounts() {
        Map<String, Double> counts = new LinkedHashMap<>();
        for (String outcome : new String[] { LOGIN_OUTCOME_SUCCESS, LOGIN_OUTCOME_REFUSED, LOGIN_OUTCOME_UNAVAILABLE }) {
            counts.put(outcome, meterRegistry.get(LOGINS_METER_NAME).tag(LOGINS_METER_OUTCOME_DIMENSION, outcome).counter().count());
        }
        return counts;
    }

    /**
     * Asserts the one counter that had to move moved by exactly one, and that neither of the others moved at all.
     *
     * <p>The second half is the half worth having. A hook that incremented every counter on every attempt would
     * satisfy any assertion shaped "the number went up" and would leave the dashboard saying nothing.</p>
     */
    private void assertOnlyOutcomeMoved(Map<String, Double> before, String expectedOutcome) {
        Map<String, Double> after = loginCounts();
        before.forEach(
            (outcome, count) ->
                assertThat(after.get(outcome) - count).as("outcome=%s", outcome).isEqualTo(outcome.equals(expectedOutcome) ? 1 : 0)
        );
    }
}
