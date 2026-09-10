package net.jojoaddison.service;

import net.jojoaddison.management.RegistrationMetersService;
import net.jojoaddison.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Counts the account population on a schedule and hands the answers to {@link RegistrationMetersService} — see
 * {@code docs/backlog.md} item 96.
 *
 * <p><strong>This exists so that the gauge callback does not.</strong> Micrometer samples a gauge synchronously on the
 * exporting or scraping thread, which on this gateway can be a Netty event-loop thread serving
 * {@code /management/prometheus}; a callback that queried Mongo there would block the loop, and Reactor would refuse
 * it outright rather than merely being slow. Reading the collection here, on a scheduling thread, and leaving the
 * gauge a field read is the same answer arrived at without that risk. The cost is bounded staleness — a sample can be
 * up to {@link #REFRESH_INTERVAL_MS} old, which for a population that moves at the speed of people signing up is not
 * a cost at all.</p>
 */
@Service
public class RegistrationMetersRefresher {

    /**
     * One minute, hard-coded rather than configurable, and deliberately so.
     *
     * <p>Two reasons. It matches {@code OTEL_METRIC_EXPORT_INTERVAL=60000}, so refreshing faster would produce
     * readings nothing exports. And {@code ApplicationProperties} is bound with {@code ignoreUnknownFields = false},
     * so an {@code application.*} key added here without a matching binding fails context startup — the failure mode
     * {@code docs/backlog.md} item 86 records, which the test-resources {@code application.yml} would have hidden
     * from every test in this repository.</p>
     */
    static final long REFRESH_INTERVAL_MS = 60_000;

    private final Logger log = LoggerFactory.getLogger(RegistrationMetersRefresher.class);

    private final UserRepository userRepository;
    private final RegistrationMetersService registrationMetersService;

    public RegistrationMetersRefresher(UserRepository userRepository, RegistrationMetersService registrationMetersService) {
        this.userRepository = userRepository;
        this.registrationMetersService = registrationMetersService;
    }

    /**
     * Fires immediately at startup and every minute after.
     *
     * <p>Subscribed rather than blocked — unlike {@code UserService.removeNotActivatedUsers} beside it, which blocks
     * because its caller wants to know the sweep finished. Nothing waits on a metric.</p>
     */
    @Scheduled(fixedRate = REFRESH_INTERVAL_MS)
    public void refresh() {
        refreshReactively().subscribe();
    }

    /**
     * The refresh itself, separated so it can be driven without the scheduler.
     *
     * <p>A failed count publishes <em>unavailable</em> and completes normally. Both halves matter: recording the
     * failure keeps a gap on the panel rather than a flat line asserting a population nobody counted, and swallowing
     * the error keeps a Mongo outage from killing the schedule, which would leave the gauge stuck at whatever it last
     * held even after the database came back.</p>
     */
    Mono<Void> refreshReactively() {
        return Mono.zip(userRepository.countByActivatedIsTrue(), userRepository.countByActivatedIsFalse())
            .doOnNext(counts -> registrationMetersService.recordAccountCounts(counts.getT1(), counts.getT2()))
            .doOnError(e -> {
                registrationMetersService.recordAccountCountsUnavailable();
                log.warn("Could not count the account population for the registration gauges: {}", e.getMessage());
            })
            .onErrorComplete()
            .then();
    }
}
