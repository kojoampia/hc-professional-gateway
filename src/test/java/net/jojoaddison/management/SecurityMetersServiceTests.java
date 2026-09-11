package net.jojoaddison.management;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Collection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SecurityMetersServiceTests {

    private static final String INVALID_TOKENS_METER_EXPECTED_NAME = "security.authentication.invalid-tokens";

    private static final String LOGINS_METER_EXPECTED_NAME = "security.authentication.logins";

    private MeterRegistry meterRegistry;

    private SecurityMetersService securityMetersService;

    @BeforeEach
    public void setup() {
        meterRegistry = new SimpleMeterRegistry();

        securityMetersService = new SecurityMetersService(meterRegistry);
    }

    @Test
    void testInvalidTokensCountersByCauseAreCreated() {
        meterRegistry.get(INVALID_TOKENS_METER_EXPECTED_NAME).counter();

        meterRegistry.get(INVALID_TOKENS_METER_EXPECTED_NAME).tag("cause", "expired").counter();

        meterRegistry.get(INVALID_TOKENS_METER_EXPECTED_NAME).tag("cause", "unsupported").counter();

        meterRegistry.get(INVALID_TOKENS_METER_EXPECTED_NAME).tag("cause", "invalid-signature").counter();

        meterRegistry.get(INVALID_TOKENS_METER_EXPECTED_NAME).tag("cause", "malformed").counter();

        // backlog.md item 27: the meter a validate-origin cutover is watched on.
        meterRegistry.get(INVALID_TOKENS_METER_EXPECTED_NAME).tag("cause", "untrusted-origin").counter();

        Collection<Counter> counters = meterRegistry.find(INVALID_TOKENS_METER_EXPECTED_NAME).counters();

        assertThat(counters).hasSize(5);
    }

    @Test
    void testCountMethodsShouldBeBoundToCorrectCounters() {
        assertThat(meterRegistry.get(INVALID_TOKENS_METER_EXPECTED_NAME).tag("cause", "expired").counter().count()).isZero();

        securityMetersService.trackTokenExpired();

        assertThat(meterRegistry.get(INVALID_TOKENS_METER_EXPECTED_NAME).tag("cause", "expired").counter().count()).isEqualTo(1);

        assertThat(meterRegistry.get(INVALID_TOKENS_METER_EXPECTED_NAME).tag("cause", "unsupported").counter().count()).isZero();

        securityMetersService.trackTokenUnsupported();

        assertThat(meterRegistry.get(INVALID_TOKENS_METER_EXPECTED_NAME).tag("cause", "unsupported").counter().count()).isEqualTo(1);

        assertThat(meterRegistry.get(INVALID_TOKENS_METER_EXPECTED_NAME).tag("cause", "invalid-signature").counter().count()).isZero();

        securityMetersService.trackTokenInvalidSignature();

        assertThat(meterRegistry.get(INVALID_TOKENS_METER_EXPECTED_NAME).tag("cause", "invalid-signature").counter().count()).isEqualTo(1);

        assertThat(meterRegistry.get(INVALID_TOKENS_METER_EXPECTED_NAME).tag("cause", "malformed").counter().count()).isZero();

        securityMetersService.trackTokenMalformed();

        assertThat(meterRegistry.get(INVALID_TOKENS_METER_EXPECTED_NAME).tag("cause", "malformed").counter().count()).isEqualTo(1);

        assertThat(meterRegistry.get(INVALID_TOKENS_METER_EXPECTED_NAME).tag("cause", "untrusted-origin").counter().count()).isZero();

        securityMetersService.trackTokenUntrustedOrigin();

        assertThat(meterRegistry.get(INVALID_TOKENS_METER_EXPECTED_NAME).tag("cause", "untrusted-origin").counter().count()).isEqualTo(1);
    }

    // backlog.md item 96: sign-in outcomes, a separate meter from the invalid tokens above.

    @Test
    void testLoginCountersByOutcomeAreCreated() {
        meterRegistry.get(LOGINS_METER_EXPECTED_NAME).tag("outcome", "success").counter();

        meterRegistry.get(LOGINS_METER_EXPECTED_NAME).tag("outcome", "refused").counter();

        meterRegistry.get(LOGINS_METER_EXPECTED_NAME).tag("outcome", "unavailable").counter();

        Collection<Counter> counters = meterRegistry.find(LOGINS_METER_EXPECTED_NAME).counters();

        assertThat(counters).hasSize(3);
    }

    @Test
    void testLoginCountMethodsShouldBeBoundToCorrectCounters() {
        securityMetersService.trackLoginSuccess();

        assertThat(loginCount("success")).isEqualTo(1);
        assertThat(loginCount("refused")).isZero();
        assertThat(loginCount("unavailable")).isZero();

        securityMetersService.trackLoginRefused();

        assertThat(loginCount("success")).isEqualTo(1);
        assertThat(loginCount("refused")).isEqualTo(1);
        assertThat(loginCount("unavailable")).isZero();

        securityMetersService.trackLoginUnavailable();

        assertThat(loginCount("success")).isEqualTo(1);
        assertThat(loginCount("refused")).isEqualTo(1);
        assertThat(loginCount("unavailable")).isEqualTo(1);
    }

    /**
     * The trap item 96 names, held here rather than argued in a comment.
     *
     * <p>An expired token belongs to a login that <em>worked</em> — the user signed in yesterday and came back this
     * morning. If the two meters ever shared a name or a counter, a healthy return visit would land on the failed-login
     * panel, and a dashboard would report an authentication problem where there is a token lifetime.</p>
     */
    @Test
    void aTokenThatMerelyExpiredIsNotAFailedLogin() {
        securityMetersService.trackTokenExpired();
        securityMetersService.trackTokenUntrustedOrigin();
        securityMetersService.trackTokenInvalidSignature();
        securityMetersService.trackTokenMalformed();

        assertThat(loginCount("success")).isZero();
        assertThat(loginCount("refused")).isZero();
        assertThat(loginCount("unavailable")).isZero();
    }

    /** And the same statement the other way round: a sign-in is not a token validation error. */
    @Test
    void aRefusedLoginIsNotAnInvalidToken() {
        securityMetersService.trackLoginRefused();
        securityMetersService.trackLoginUnavailable();
        securityMetersService.trackLoginSuccess();

        Collection<Counter> invalidTokens = meterRegistry.find(INVALID_TOKENS_METER_EXPECTED_NAME).counters();

        assertThat(invalidTokens).isNotEmpty().allSatisfy(counter -> assertThat(counter.count()).isZero());
    }

    private double loginCount(String outcome) {
        return meterRegistry.get(LOGINS_METER_EXPECTED_NAME).tag("outcome", outcome).counter().count();
    }
}
