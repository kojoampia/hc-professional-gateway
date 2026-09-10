package net.jojoaddison.service;

import static net.jojoaddison.management.RegistrationMetersService.ACCOUNT_STATE_ACTIVATED;
import static net.jojoaddison.management.RegistrationMetersService.ACCOUNT_STATE_NOT_ACTIVATED;
import static net.jojoaddison.management.RegistrationMetersService.REGISTERED_ACCOUNTS_METER_NAME;
import static net.jojoaddison.management.RegistrationMetersService.REGISTERED_ACCOUNTS_METER_STATE_DIMENSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.jojoaddison.management.RegistrationMetersService;
import net.jojoaddison.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import reactor.core.publisher.Mono;

/**
 * The scheduled half of the registration split: what it publishes when the counts arrive, and what it publishes when
 * they do not. See {@code docs/backlog.md} item 96.
 */
class RegistrationMetersRefresherUnitTest {

    private MeterRegistry meterRegistry;
    private UserRepository userRepository;
    private RegistrationMetersRefresher refresher;

    @BeforeEach
    void setup() {
        meterRegistry = new SimpleMeterRegistry();
        userRepository = mock(UserRepository.class);
        refresher = new RegistrationMetersRefresher(userRepository, new RegistrationMetersService(meterRegistry));
    }

    @Test
    void aMixedPopulationIsPublishedOnBothSeries() {
        when(userRepository.countByActivatedIsTrue()).thenReturn(Mono.just(17L));
        when(userRepository.countByActivatedIsFalse()).thenReturn(Mono.just(4L));

        refresher.refreshReactively().block();

        assertThat(gauge(ACCOUNT_STATE_ACTIVATED)).isEqualTo(17);
        assertThat(gauge(ACCOUNT_STATE_NOT_ACTIVATED)).isEqualTo(4);
    }

    @Test
    void anEmptyCollectionIsPublishedAsZeroRatherThanSkipped() {
        when(userRepository.countByActivatedIsTrue()).thenReturn(Mono.just(0L));
        when(userRepository.countByActivatedIsFalse()).thenReturn(Mono.just(0L));

        refresher.refreshReactively().block();

        assertThat(gauge(ACCOUNT_STATE_ACTIVATED)).isZero();
        assertThat(gauge(ACCOUNT_STATE_NOT_ACTIVATED)).isZero();
    }

    @Test
    void aFailedCountIsPublishedAsUnavailableAndNotAsZero() {
        // The successful refresh first is load-bearing: both gauges start at NaN, so a failure asserted against a
        // freshly built service passes whether the failure was published or nothing happened at all. Only a value
        // that was there and is gone proves the failure reached the meter.
        when(userRepository.countByActivatedIsTrue()).thenReturn(Mono.just(17L));
        when(userRepository.countByActivatedIsFalse()).thenReturn(Mono.just(4L));
        refresher.refreshReactively().block();
        assertThat(gauge(ACCOUNT_STATE_ACTIVATED)).isEqualTo(17);

        when(userRepository.countByActivatedIsTrue()).thenReturn(Mono.error(new DataAccessResourceFailureException("no route to mongo")));

        refresher.refreshReactively().block();

        assertThat(gauge(ACCOUNT_STATE_ACTIVATED)).isNaN();
        assertThat(gauge(ACCOUNT_STATE_NOT_ACTIVATED)).isNaN();
    }

    @Test
    void aFailedCountDoesNotPropagate() {
        // The scheduler is fire-and-forget: an error escaping here would be an unhandled signal on every tick for as
        // long as Mongo is away, and nothing is watching for it. The failure is already reported — as a gap on the
        // panel — so there is nothing left for it to do but end the tick.
        when(userRepository.countByActivatedIsTrue()).thenReturn(Mono.error(new DataAccessResourceFailureException("no route to mongo")));
        when(userRepository.countByActivatedIsFalse()).thenReturn(Mono.just(4L));

        assertThatCode(() -> refresher.refreshReactively().block()).doesNotThrowAnyException();
    }

    private double gauge(String state) {
        return meterRegistry.get(REGISTERED_ACCOUNTS_METER_NAME).tag(REGISTERED_ACCOUNTS_METER_STATE_DIMENSION, state).gauge().value();
    }
}
