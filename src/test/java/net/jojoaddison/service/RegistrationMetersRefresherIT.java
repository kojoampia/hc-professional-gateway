package net.jojoaddison.service;

import static net.jojoaddison.management.RegistrationMetersService.ACCOUNT_STATE_ACTIVATED;
import static net.jojoaddison.management.RegistrationMetersService.ACCOUNT_STATE_NOT_ACTIVATED;
import static net.jojoaddison.management.RegistrationMetersService.REGISTERED_ACCOUNTS_METER_NAME;
import static net.jojoaddison.management.RegistrationMetersService.REGISTERED_ACCOUNTS_METER_STATE_DIMENSION;
import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.User;
import net.jojoaddison.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The registration split against a real user collection.
 *
 * <p>The unit test beside this one proves the arithmetic; what this proves is that the derived count queries exist,
 * are derivable by Spring Data, and answer about the collection this gateway actually writes to. See
 * {@code docs/backlog.md} item 96.</p>
 */
@IntegrationTest
class RegistrationMetersRefresherIT {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private RegistrationMetersRefresher refresher;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void theTwoSeriesAgreeWithTheCollectionTheyClaimToCount() {
        User activated = account("registration-meters-activated", true);
        User notActivated = account("registration-meters-not-activated", false);

        refresher.refreshReactively().block();

        assertThat(gauge(ACCOUNT_STATE_ACTIVATED)).isEqualTo(userRepository.countByActivatedIsTrue().block().doubleValue());
        assertThat(gauge(ACCOUNT_STATE_NOT_ACTIVATED)).isEqualTo(userRepository.countByActivatedIsFalse().block().doubleValue());

        userRepository.delete(activated).block();
        userRepository.delete(notActivated).block();
    }

    @Test
    void aRegistrationThatHasNotBeenActivatedMovesOneSeriesAndNotTheOther() {
        // The split is the whole point: an account arriving unactivated must not be counted as activated, and an
        // activation must move it across rather than adding to both. Asserting only that the numbers are plausible
        // would pass just as well against a gauge that counted every account twice.
        refresher.refreshReactively().block();
        double activatedBefore = gauge(ACCOUNT_STATE_ACTIVATED);
        double notActivatedBefore = gauge(ACCOUNT_STATE_NOT_ACTIVATED);

        User applicant = account("registration-meters-applicant", false);
        refresher.refreshReactively().block();

        assertThat(gauge(ACCOUNT_STATE_NOT_ACTIVATED)).isEqualTo(notActivatedBefore + 1);
        assertThat(gauge(ACCOUNT_STATE_ACTIVATED)).isEqualTo(activatedBefore);

        applicant.setActivated(true);
        userRepository.save(applicant).block();
        refresher.refreshReactively().block();

        assertThat(gauge(ACCOUNT_STATE_NOT_ACTIVATED)).isEqualTo(notActivatedBefore);
        assertThat(gauge(ACCOUNT_STATE_ACTIVATED)).isEqualTo(activatedBefore + 1);

        userRepository.delete(applicant).block();
    }

    private User account(String login, boolean activated) {
        User user = new User();
        user.setLogin(login);
        user.setEmail(login + "@example.com");
        user.setActivated(activated);
        user.setPassword(passwordEncoder.encode("does-not-sign-in-here"));
        return userRepository.save(user).block();
    }

    private double gauge(String state) {
        return meterRegistry.get(REGISTERED_ACCOUNTS_METER_NAME).tag(REGISTERED_ACCOUNTS_METER_STATE_DIMENSION, state).gauge().value();
    }
}
