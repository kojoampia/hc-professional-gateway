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
import java.time.Duration;
import net.jojoaddison.management.RegistrationMetersService;
import net.jojoaddison.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.scheduling.annotation.Scheduled;
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

    /**
     * A Mongo that accepts the query and never answers.
     *
     * <p>This is the failure the {@code NaN} decision is most easily defeated by, because unlike a refusal it emits
     * <em>nothing</em>: without {@link RegistrationMetersRefresher#COUNT_TIMEOUT} no {@code onError} ever arrives, no
     * failure is recorded, and the gauge holds its last good value for the length of the hang — the flat line
     * {@code RegistrationMetersService} exists to refuse, arriving through the one path with no signal to catch.</p>
     *
     * <p>Driven through the deadline-taking overload with fifty milliseconds rather than the production ten seconds —
     * {@code reactor-test} is not on this classpath, so there is no virtual time, and the operator being exercised is
     * the real one either way. The successful refresh first is the same load-bearing setup as the test above: only a
     * value that was there and is gone proves the timeout reached the meter.</p>
     */
    @Test
    void aCountThatNeverAnswersIsPublishedAsUnavailable() {
        when(userRepository.countByActivatedIsTrue()).thenReturn(Mono.just(17L));
        when(userRepository.countByActivatedIsFalse()).thenReturn(Mono.just(4L));
        refresher.refreshReactively().block();
        assertThat(gauge(ACCOUNT_STATE_ACTIVATED)).isEqualTo(17);

        when(userRepository.countByActivatedIsTrue()).thenReturn(Mono.never());

        // A bounded block, and the bound matters: with the timeout removed this Mono never completes, and a bare
        // block() would hang the build instead of failing it — a mutation nobody could read the result of, and a
        // regression that would look like a stuck CI runner rather than a broken meter.
        refresher.refreshReactively(Duration.ofMillis(50)).block(Duration.ofSeconds(5));

        assertThat(gauge(ACCOUNT_STATE_ACTIVATED)).isNaN();
        assertThat(gauge(ACCOUNT_STATE_NOT_ACTIVATED)).isNaN();
    }

    /**
     * The timeout has to stay well under the refresh interval, and the reason is not tidiness.
     *
     * <p>Two ticks in flight can complete out of order, so an older pair of counts can overwrite a newer one and the
     * gauge goes backwards with nothing in the logs to explain it. A timeout shorter than the interval means a hung
     * tick has given up before the next one starts.</p>
     */
    @Test
    void aHungRefreshGivesUpBeforeTheNextOneStarts() {
        assertThat(RegistrationMetersRefresher.COUNT_TIMEOUT.toMillis()).isLessThan(RegistrationMetersRefresher.REFRESH_INTERVAL_MS);
    }

    /**
     * The trigger itself, which no test in this repository can reach at runtime.
     *
     * <p>{@code @EnableScheduling} lives on {@code AsyncConfiguration}, which is
     * {@code @Profile("!testdev & !testprod")}, and integration tests run under {@code testdev} with
     * {@code AsyncSyncConfiguration} substituted — so the scheduler never starts in a test and both
     * {@code RegistrationMetersRefresherIT} methods drive {@code refreshReactively()} by hand. Delete the annotation
     * and every gate in this repository stays green while both gauges sit at {@code NaN} for the life of the JVM and
     * both registration panels are permanently empty. That is the same "a meter nobody increments looks exactly like
     * a working one" failure {@code LoginOutcomeMetersIT} closes for the counters, and the only way to close it here
     * is to read the annotation, as {@code AuthoritiesConstantsUnitTest} reads the authority fields.</p>
     */
    @Test
    void theRefreshIsScheduledAtAll() throws NoSuchMethodException {
        Scheduled scheduled = RegistrationMetersRefresher.class.getMethod("refresh").getAnnotation(Scheduled.class);

        assertThat(scheduled).as("nothing else in this repository can notice if the trigger is removed").isNotNull();
    }

    /** And that it fires at the interval the class documents, rather than at some other one. */
    @Test
    void theRefreshIsScheduledAtTheDocumentedInterval() throws NoSuchMethodException {
        Scheduled scheduled = RegistrationMetersRefresher.class.getMethod("refresh").getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull().extracting(Scheduled::fixedRate).isEqualTo(RegistrationMetersRefresher.REFRESH_INTERVAL_MS);
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
