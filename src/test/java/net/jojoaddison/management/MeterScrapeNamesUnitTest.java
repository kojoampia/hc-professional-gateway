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
 *
 * <p><strong>Both meters carry a base unit, and both spellings hide it by coincidence.</strong> Neither is unitless:
 * {@code logins} and {@code accounts} are declared, and the convention appends a base unit only when the name does not
 * already end in it — which each of these happens to do. The pre-existing meter is the same rule with the other
 * outcome, {@code security.authentication.invalid-tokens} + {@code errors} exporting as
 * {@code ..._invalid_tokens_errors_total}. So <em>renaming either meter to anything not ending in its own unit word
 * silently changes the exported name</em>: {@code security.registration.population} would export as
 * {@code security_registration_population_accounts}. Nothing else in the estate would notice — this class is the only
 * thing standing between such a rename and a screen of blank panels in another repository.</p>
 *
 * <p><strong>The residual, stated because it is easy to mistake this class for more than it is.</strong> What is
 * pinned here is {@code PrometheusMeterRegistry}'s convention. The dashboard reads <em>Mimir</em>, fed by the
 * OpenTelemetry agent's Micrometer bridge, and item 96 deliberately chose that path over scraping
 * {@code /management/prometheus}. The two conventions agree today and hc-patient's live series in Mimir corroborate
 * it, but <strong>nothing here measures the name that actually arrives</strong>. One read-back after package B rolls
 * closes that, and until then this is a strong proxy rather than the thing itself.</p>
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
