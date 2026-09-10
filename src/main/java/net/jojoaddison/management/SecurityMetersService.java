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

    /**
     * Sign-in attempts at {@code POST /api/authenticate}, by what came of them — see {@code docs/backlog.md} item 96.
     *
     * <p><strong>This is a different meter from {@link #INVALID_TOKENS_METER_NAME} and the two must never be added
     * together.</strong> That one counts <em>tokens</em>: an {@code expired} token is a login that <em>succeeded</em>
     * and whose token has since aged out, so a healthy user coming back the next morning increments it. Rolling the
     * two into one number would report that return visit as an authentication failure, which is the reading item 96
     * exists to prevent.</p>
     */
    public static final String LOGINS_METER_NAME = "security.authentication.logins";
    public static final String LOGINS_METER_DESCRIPTION = "Counts sign-in attempts by outcome: success, refused or unavailable.";
    public static final String LOGINS_METER_BASE_UNIT = "logins";
    public static final String LOGINS_METER_OUTCOME_DIMENSION = "outcome";

    /** A token was issued: the credential was accepted and the caller got what they asked for. */
    public static final String LOGIN_OUTCOME_SUCCESS = "success";
    /** The credential was <em>refused</em> — asked and answered no. */
    public static final String LOGIN_OUTCOME_REFUSED = "refused";
    /** The question could not be <em>asked</em>: the user store or the token path failed. */
    public static final String LOGIN_OUTCOME_UNAVAILABLE = "unavailable";

    private final Counter tokenInvalidSignatureCounter;
    private final Counter tokenExpiredCounter;
    private final Counter tokenUnsupportedCounter;
    private final Counter tokenMalformedCounter;
    private final Counter tokenUntrustedOriginCounter;

    private final Counter loginSuccessCounter;
    private final Counter loginRefusedCounter;
    private final Counter loginUnavailableCounter;

    public SecurityMetersService(MeterRegistry registry) {
        this.tokenInvalidSignatureCounter = invalidTokensCounterForCauseBuilder("invalid-signature").register(registry);
        this.tokenExpiredCounter = invalidTokensCounterForCauseBuilder("expired").register(registry);
        this.tokenUnsupportedCounter = invalidTokensCounterForCauseBuilder("unsupported").register(registry);
        this.tokenMalformedCounter = invalidTokensCounterForCauseBuilder("malformed").register(registry);
        this.tokenUntrustedOriginCounter = invalidTokensCounterForCauseBuilder("untrusted-origin").register(registry);

        this.loginSuccessCounter = loginsCounterForOutcomeBuilder(LOGIN_OUTCOME_SUCCESS).register(registry);
        this.loginRefusedCounter = loginsCounterForOutcomeBuilder(LOGIN_OUTCOME_REFUSED).register(registry);
        this.loginUnavailableCounter = loginsCounterForOutcomeBuilder(LOGIN_OUTCOME_UNAVAILABLE).register(registry);
    }

    private Counter.Builder invalidTokensCounterForCauseBuilder(String cause) {
        return Counter.builder(INVALID_TOKENS_METER_NAME)
            .baseUnit(INVALID_TOKENS_METER_BASE_UNIT)
            .description(INVALID_TOKENS_METER_DESCRIPTION)
            .tag(INVALID_TOKENS_METER_CAUSE_DIMENSION, cause);
    }

    private Counter.Builder loginsCounterForOutcomeBuilder(String outcome) {
        return Counter.builder(LOGINS_METER_NAME)
            .baseUnit(LOGINS_METER_BASE_UNIT)
            .description(LOGINS_METER_DESCRIPTION)
            .tag(LOGINS_METER_OUTCOME_DIMENSION, outcome);
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

    /**
     * A sign-in that ended with a token in the caller's hands.
     */
    public void trackLoginSuccess() {
        this.loginSuccessCounter.increment();
    }

    /**
     * A sign-in the authentication backend <em>answered no</em> to: a wrong password, a login nobody holds, or an
     * account that has not been activated yet.
     *
     * <p><strong>Those three are deliberately one number, and the reason is a decision this repository has already
     * taken once.</strong> {@code AuthenticateControllerIT.anUnactivatedAccountLooksExactlyLikeAMissingOne} holds the
     * 401 bodies byte-identical so that an anonymous caller cannot tell "exists but unactivated" from "no such user";
     * splitting them here would hand that same distinction back through {@code /management/prometheus}, which is
     * {@code permitAll()}. What the operator loses is nothing they need from this meter — "are activation emails
     * arriving?" is answered by {@link RegistrationMetersService}'s {@code not-activated} population, which climbs
     * when they are not, and does so whether or not anyone tries to sign in.</p>
     */
    public void trackLoginRefused() {
        this.loginRefusedCounter.increment();
    }

    /**
     * A sign-in this gateway could not answer — as opposed to one it answered with a no.
     *
     * <p><strong>Read it as "the gateway failed", not as "Mongo is down".</strong> The user store being unreachable
     * is the case it was built for and the commonest one, and a refresh token that could not be persisted is the
     * next; but the classification is by exclusion, so a <em>programming</em> error on the token path — an NPE out of
     * {@code browserResponse}, {@code mobileResponse} or {@code TokenProvider} — lands here too. That is deliberate:
     * from the clinician's side it is the same event, and a bug that stops sign-ins should not be invisible because
     * nobody wrote a counter for it. It does mean this line climbing is not by itself evidence about the database.
     * The `log.error` beside the increment carries the stack trace, and an error-level count is a panel of its own.</p>
     *
     * <p>This is the meter that keeps an outage from being reported as a wall of wrong passwords, and it is here for
     * the reason {@code docs/backlog.md} item 83 gives at a different site — <em>"refused" is not "could not ask"</em>.
     * With one failure counter, a Mongo outage and a credential-stuffing run are the same rising line and the operator
     * has to guess which they are looking at. The two lines separate them without a guess: {@code refused} climbing
     * alone is people getting their passwords wrong, and {@code unavailable} climbing at all is this gateway being
     * unable to answer.</p>
     */
    public void trackLoginUnavailable() {
        this.loginUnavailableCounter.increment();
    }
}
