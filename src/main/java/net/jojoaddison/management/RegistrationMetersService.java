package net.jojoaddison.management;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * How many accounts this gateway holds, split by whether they have been activated — see {@code docs/backlog.md}
 * item 96.
 *
 * <p><strong>These are gauges, not counters, and that is the whole decision.</strong> The question asked was "how
 * many registrations are activated and how many are not", which is a question about a <em>population</em>: a counter
 * incremented at registration and at activation answers "how many were created since this JVM last started", loses
 * everything on a restart, and can never be subtracted back into a population. A gauge is a query, so it is true at
 * the moment it is read and survives a restart because it was never accumulated.</p>
 *
 * <p><strong>The population is every document in {@code jhi_user}</strong> — admins, the seeded demo accounts and
 * self-registered applicants alike. This gateway creates no synthetic user rows ({@code Constants.SYSTEM} is an audit
 * string, not an account), so there is nothing to exclude and no hidden denominator.</p>
 *
 * <p><strong>{@code not-activated} is not a failure count.</strong> An invitation that has been sent and not yet
 * clicked sits there legitimately, and so does a registration from ten minutes ago. What the series is good for is its
 * <em>shape</em>: a number that tracks registrations and then falls is activation working, and one that only climbs is
 * activation mail that is not arriving. Read it as a backlog, never as an error count.</p>
 *
 * <p><strong>This class holds meters and nothing else</strong>, exactly as {@link SecurityMetersService} does. It runs
 * no query itself: {@code net.jojoaddison.service.RegistrationMetersRefresher} does that on a schedule and pushes the
 * answers in. That split is not tidiness — it is what keeps the gauge callback free of I/O. Micrometer samples a gauge
 * synchronously on whichever thread is exporting or scraping, and on this gateway that can be a Netty event-loop
 * thread serving {@code /management/prometheus}; a callback that queried Mongo there would either be refused outright
 * by Reactor's non-blocking check or would hold the loop for the duration of a slow query. Sampling one field cannot
 * do either.</p>
 */
@Service
public class RegistrationMetersService {

    public static final String REGISTERED_ACCOUNTS_METER_NAME = "security.registration.accounts";
    public static final String REGISTERED_ACCOUNTS_METER_DESCRIPTION =
        "Accounts held by this gateway, split by whether they have been activated.";
    public static final String REGISTERED_ACCOUNTS_METER_BASE_UNIT = "accounts";
    public static final String REGISTERED_ACCOUNTS_METER_STATE_DIMENSION = "state";

    /** The account has clicked its activation link (or was seeded activated) and can sign in. */
    public static final String ACCOUNT_STATE_ACTIVATED = "activated";
    /** The account exists and has not been activated yet. A backlog, not an error — see the class javadoc. */
    public static final String ACCOUNT_STATE_NOT_ACTIVATED = "not-activated";

    /**
     * {@code NaN} until a count has actually been read, and back to {@code NaN} whenever one fails.
     *
     * <p><strong>A failed read is not a zero and is not the last good answer either.</strong> Prometheus and Grafana
     * render {@code NaN} as a gap, which is the truthful rendering of "nobody could count them just then"; carrying
     * the previous value forward would draw a flat line, and a flat line on this panel is indistinguishable from "no
     * registrations happened" — the reading items 62, 78 and 83 exist to refuse.</p>
     */
    private volatile double activatedAccounts = Double.NaN;

    private volatile double notActivatedAccounts = Double.NaN;

    public RegistrationMetersService(MeterRegistry registry) {
        registeredAccountsGaugeForStateBuilder(ACCOUNT_STATE_ACTIVATED, () -> activatedAccounts).register(registry);
        registeredAccountsGaugeForStateBuilder(ACCOUNT_STATE_NOT_ACTIVATED, () -> notActivatedAccounts).register(registry);
    }

    private Gauge.Builder<?> registeredAccountsGaugeForStateBuilder(String state, Supplier<Number> value) {
        return Gauge.builder(REGISTERED_ACCOUNTS_METER_NAME, value)
            .baseUnit(REGISTERED_ACCOUNTS_METER_BASE_UNIT)
            .description(REGISTERED_ACCOUNTS_METER_DESCRIPTION)
            .tag(REGISTERED_ACCOUNTS_METER_STATE_DIMENSION, state);
    }

    /**
     * Publishes a pair of counts that were actually read.
     *
     * <p>The two counts come from two queries and so are read microseconds apart; a registration landing between them
     * can make their sum disagree with a third count of the collection by one. No total is published, precisely so
     * that nothing here depends on the two being atomic.</p>
     */
    public void recordAccountCounts(long activated, long notActivated) {
        this.activatedAccounts = activated;
        this.notActivatedAccounts = notActivated;
    }

    /**
     * Publishes that the population could not be counted, which is a different statement from counting zero.
     */
    public void recordAccountCountsUnavailable() {
        this.activatedAccounts = Double.NaN;
        this.notActivatedAccounts = Double.NaN;
    }
}
