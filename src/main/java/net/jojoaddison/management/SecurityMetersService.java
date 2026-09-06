package net.jojoaddison.management;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

@Service
public class SecurityMetersService {

    public static final String INVALID_TOKENS_METER_NAME = "security.authentication.invalid-tokens";
    public static final String INVALID_TOKENS_METER_DESCRIPTION =
        "Indicates validation error count of the tokens presented by the clients.";
    public static final String INVALID_TOKENS_METER_BASE_UNIT = "errors";
    public static final String INVALID_TOKENS_METER_CAUSE_DIMENSION = "cause";

    private final Counter tokenInvalidSignatureCounter;
    private final Counter tokenExpiredCounter;
    private final Counter tokenUnsupportedCounter;
    private final Counter tokenMalformedCounter;
    private final Counter tokenUntrustedOriginCounter;

    public SecurityMetersService(MeterRegistry registry) {
        this.tokenInvalidSignatureCounter = invalidTokensCounterForCauseBuilder("invalid-signature").register(registry);
        this.tokenExpiredCounter = invalidTokensCounterForCauseBuilder("expired").register(registry);
        this.tokenUnsupportedCounter = invalidTokensCounterForCauseBuilder("unsupported").register(registry);
        this.tokenMalformedCounter = invalidTokensCounterForCauseBuilder("malformed").register(registry);
        this.tokenUntrustedOriginCounter = invalidTokensCounterForCauseBuilder("untrusted-origin").register(registry);
    }

    private Counter.Builder invalidTokensCounterForCauseBuilder(String cause) {
        return Counter.builder(INVALID_TOKENS_METER_NAME)
            .baseUnit(INVALID_TOKENS_METER_BASE_UNIT)
            .description(INVALID_TOKENS_METER_DESCRIPTION)
            .tag(INVALID_TOKENS_METER_CAUSE_DIMENSION, cause);
    }

    public void trackTokenInvalidSignature() {
        this.tokenInvalidSignatureCounter.increment();
    }

    public void trackTokenExpired() {
        this.tokenExpiredCounter.increment();
    }

    public void trackTokenUnsupported() {
        this.tokenUnsupportedCounter.increment();
    }

    public void trackTokenMalformed() {
        this.tokenMalformedCounter.increment();
    }

    /**
     * A token that verified and had not expired, but was minted for another Health Connect product — see
     * {@code net.jojoaddison.config.TokenOriginValidator} and {@code docs/backlog.md} item 27.
     *
     * <p>This is the meter to watch when {@code application.security.jwt.validate-origin} is turned on. Without it
     * the cutover is unobservable: "old tokens draining away as expected" and "the issuer string is wrong and nobody
     * can sign in" look identical from outside, and both used to land in the same {@code Unknown JWT error} log line.
     * A count that falls towards zero is the first; one that does not is the second, and it is the signal to turn the
     * flag back off.</p>
     */
    public void trackTokenUntrustedOrigin() {
        this.tokenUntrustedOriginCounter.increment();
    }
}
