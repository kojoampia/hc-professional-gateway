package net.jojoaddison.management;

import static net.jojoaddison.management.RegistrationMetersService.ACCOUNT_STATE_ACTIVATED;
import static net.jojoaddison.management.RegistrationMetersService.ACCOUNT_STATE_NOT_ACTIVATED;
import static net.jojoaddison.management.RegistrationMetersService.REGISTERED_ACCOUNTS_METER_NAME;
import static net.jojoaddison.management.RegistrationMetersService.REGISTERED_ACCOUNTS_METER_STATE_DIMENSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Collection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

class RegistrationMetersServiceTests {

    private MeterRegistry meterRegistry;

    private RegistrationMetersService registrationMetersService;

    @BeforeEach
    public void setup() {
        meterRegistry = new SimpleMeterRegistry();

        registrationMetersService = new RegistrationMetersService(meterRegistry);
    }

    @Test
    void testRegisteredAccountGaugesByStateAreCreated() {
        meterRegistry.get(REGISTERED_ACCOUNTS_METER_NAME).tag(REGISTERED_ACCOUNTS_METER_STATE_DIMENSION, "activated").gauge();

        meterRegistry.get(REGISTERED_ACCOUNTS_METER_NAME).tag(REGISTERED_ACCOUNTS_METER_STATE_DIMENSION, "not-activated").gauge();

        Collection<Gauge> gauges = meterRegistry.find(REGISTERED_ACCOUNTS_METER_NAME).gauges();

        assertThat(gauges).hasSize(2);
    }

    @Test
    void aMixedPopulationIsReportedOnBothSeries() {
        registrationMetersService.recordAccountCounts(17, 4);

        assertThat(gauge(ACCOUNT_STATE_ACTIVATED)).isEqualTo(17);
        assertThat(gauge(ACCOUNT_STATE_NOT_ACTIVATED)).isEqualTo(4);
    }

    @Test
    void anEmptyPopulationIsReportedAsZeroOnBothSeries() {
        // A gateway whose user collection has been emptied — the state quality's `startup.sh --clean` leaves it in —
        // must read as zero rather than as no data. Zero accounts is an answer; NaN is the absence of one.
        registrationMetersService.recordAccountCounts(0, 0);

        assertThat(gauge(ACCOUNT_STATE_ACTIVATED)).isZero();
        assertThat(gauge(ACCOUNT_STATE_NOT_ACTIVATED)).isZero();
    }

    @Test
    void aPopulationNobodyHasCountedYetIsNotZero() {
        // Before the first refresh has run, nothing has been measured. Publishing zero here would put a real number
        // on a panel for the first minute of every restart, and the number would be wrong.
        assertThat(gauge(ACCOUNT_STATE_ACTIVATED)).isNaN();
        assertThat(gauge(ACCOUNT_STATE_NOT_ACTIVATED)).isNaN();
    }

    @Test
    void aFailedCountErasesTheOldOneRatherThanLeavingItStanding() {
        // The item 78 shape, in a gauge: a read that failed is not a population of zero and is not the population
        // from a minute ago either. A carried-forward value draws a flat line, and a flat line on this panel says
        // "no registrations happened" — a claim nobody established.
        registrationMetersService.recordAccountCounts(17, 4);

        registrationMetersService.recordAccountCountsUnavailable();

        assertThat(gauge(ACCOUNT_STATE_ACTIVATED)).isNaN();
        assertThat(gauge(ACCOUNT_STATE_NOT_ACTIVATED)).isNaN();
    }

    /**
     * The gauges are sampled wherever the exporter happens to be, and on this gateway that includes Netty's event
     * loop — {@code /management/prometheus} is served by WebFlux like everything else.
     *
     * <p>This drives a sample on a Reactor scheduler thread, which BlockHound treats as non-blocking, so anything
     * the callback did that blocked would throw here. The first half of the test proves the trap is armed: without
     * it, a green result would be equally consistent with BlockHound not being installed at all.</p>
     */
    @Test
    void samplingAGaugeDoesNotBlockANonBlockingThread() {
        registrationMetersService.recordAccountCounts(17, 4);

        assertThatThrownBy(() ->
            Mono.fromCallable(() -> {
                Thread.sleep(1);
                return 0;
            })
                .subscribeOn(Schedulers.parallel())
                .block(Duration.ofSeconds(5)))
            .as("BlockHound must be installed, or the assertion below proves nothing")
            .hasMessageContaining("Blocking call!");

        Double sampled = Mono.fromCallable(() -> gauge(ACCOUNT_STATE_ACTIVATED))
            .subscribeOn(Schedulers.parallel())
            .block(Duration.ofSeconds(5));

        assertThat(sampled).isEqualTo(17);
    }

    private double gauge(String state) {
        return meterRegistry.get(REGISTERED_ACCOUNTS_METER_NAME).tag(REGISTERED_ACCOUNTS_METER_STATE_DIMENSION, state).gauge().value();
    }
}
