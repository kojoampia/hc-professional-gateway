package net.jojoaddison.management;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The names and label keys the two item-96 meters are published under.
 *
 * <p><strong>This class exists for a reader outside this repository.</strong> A dashboard queries a metric by its
 * exported name, not by the Micrometer name in the source, and the two differ: Micrometer's Prometheus naming
 * convention snake-cases the dots, folds in the base unit and appends {@code _total} to a counter. Nothing else here
 * would fail if one of those names changed, and the panel that broke would be in another repository — so the strings
 * are asserted literally, exactly as a query would spell them.</p>
 *
 * <p>Scraped from a real {@link PrometheusMeterRegistry} rather than transcribed from the convention's source, since
 * the transcription is the part that would be wrong.</p>
 */
class MeterScrapeNamesUnitTest {

    private PrometheusMeterRegistry meterRegistry;

    @BeforeEach
    void setup() {
        meterRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

        SecurityMetersService securityMetersService = new SecurityMetersService(meterRegistry);
        securityMetersService.trackLoginSuccess();
        securityMetersService.trackLoginRefused();
        securityMetersService.trackLoginUnavailable();

        new RegistrationMetersService(meterRegistry).recordAccountCounts(17, 4);
    }

    @Test
    void loginOutcomesAreScrapedUnderTheNameADashboardQueries() {
        String scrape = meterRegistry.scrape();

        assertThat(scrape)
            .contains("security_authentication_logins_total{outcome=\"success\"}")
            .contains("security_authentication_logins_total{outcome=\"refused\"}")
            .contains("security_authentication_logins_total{outcome=\"unavailable\"}");
    }

    @Test
    void theRegistrationSplitIsScrapedUnderTheNameADashboardQueries() {
        String scrape = meterRegistry.scrape();

        assertThat(scrape)
            .contains("security_registration_accounts{state=\"activated\"} 17.0")
            .contains("security_registration_accounts{state=\"not-activated\"} 4.0");
    }

    /**
     * The two families keep separate names, so no query can accidentally add sign-ins to token validation errors.
     */
    @Test
    void theLoginMeterAndTheInvalidTokenMeterDoNotShareAName() {
        String scrape = meterRegistry.scrape();

        assertThat(scrape).contains("security_authentication_invalid_tokens_errors_total");
        assertThat(scrape).doesNotContain("security_authentication_invalid_tokens_errors_total{outcome=");
    }
}
