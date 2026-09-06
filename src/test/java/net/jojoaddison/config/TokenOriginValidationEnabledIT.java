package net.jojoaddison.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.util.Base64;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.management.SecurityMetersService;
import net.jojoaddison.security.AuthoritiesConstants;
import net.jojoaddison.security.SecurityUtils;
import net.jojoaddison.security.jwt.TokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * How {@link TokenOriginValidator} is <em>attached</em> to this gateway's decoder — which is a different question
 * from what the validator decides, and the one that can go wrong silently.
 *
 * <p>{@code TokenOriginValidatorUnitTest} proves the decision table. It cannot see the wiring, and the wiring is
 * where the hazard is: {@code NimbusReactiveJwtDecoder.setJwtValidator} <strong>replaces</strong> the validator
 * rather than adding to it, so attaching the origin check without delegating to
 * {@code JwtValidators.createDefault()} switches expiry checking off altogether. That was measured rather than
 * imagined — with the delegating wrapper removed and {@code validate-origin=true}, an expired but correctly issued
 * token got {@code 200 OK} from {@code GET /api/auth/sessions}. This gateway decodes on <em>every</em> proxied
 * request, so the result would be an expiry-free door in front of the whole estate, and both repos'
 * {@code clean verify} would stay green. {@link #expiryIsStillCheckedWithTheValidatorAttached} is the test that
 * stops it; {@code professionalservice} has had the equivalent since the change shipped and this repo had nothing.
 *
 * <p>{@code GET /api/auth/sessions} is the probe: it needs no seeded user — an account with no refresh tokens has
 * no sessions, so the answer is an empty array — which keeps every assertion here about the token and nothing
 * else. The accepted case is asserted on the body rather than merely on the status, so the file cannot pass by the
 * endpoint being broken in a way that answers 200 for everyone.
 *
 * <p>Tokens are signed with the key the application is configured with, so verification always succeeds and the
 * only thing under test is what the validators do afterwards. The issuer and audience of an accepted token come
 * from {@link TokenProvider} rather than from literals, so a rename of either constant fails here rather than in
 * production on the day {@code validate-origin} is turned on.
 */
@AutoConfigureWebTestClient(timeout = IntegrationTest.DEFAULT_TIMEOUT)
@IntegrationTest
@TestPropertySource(properties = { "application.security.jwt.validate-origin=true" })
class TokenOriginValidationEnabledIT {

    /**
     * A login. On a sibling stack it is whatever whoever registered there chose to type, which is the whole of
     * {@code docs/backlog.md} item 27 — nothing has to be guessed, because logins are names.
     */
    private static final String LOGIN = "nurse-jane";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private MeterRegistry meterRegistry;

    @Value("${jhipster.security.authentication.jwt.base64-secret}")
    private String jwtKey;

    @Test
    void aTokenThisGatewayMintedIsAccepted() {
        // The control. Without it every assertion below would pass just as well against a door that is shut.
        webTestClient
            .get()
            .uri("/api/auth/sessions")
            .headers(headers -> headers.setBearerAuth(token(TokenProvider.ISSUER, TokenProvider.AUDIENCE, 3600)))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .json("[]");
    }

    @Test
    void aSiblingStacksTokenIsRefusedAtTheDoor() {
        // Same signing key, valid signature, authorities a hc-patient account really holds. Before iss there was
        // nothing to tell it apart — and this gateway sees it first, because it decodes every proxied request.
        webTestClient
            .get()
            .uri("/api/auth/sessions")
            .headers(headers -> headers.setBearerAuth(token("hc-patient-gateway", "hc-patient", 3600)))
            .exchange()
            .expectStatus()
            .isUnauthorized();
    }

    @Test
    void aTokenWithoutTheClaimsIsRejected() {
        // Every token this gateway minted before 2026-09-06. Exactly why the flag ships off.
        webTestClient
            .get()
            .uri("/api/auth/sessions")
            .headers(headers -> headers.setBearerAuth(token(null, null, 3600)))
            .exchange()
            .expectStatus()
            .isUnauthorized();
    }

    @Test
    void expiryIsStillCheckedWithTheValidatorAttached() {
        // THE REGRESSION THIS CLASS EXISTS FOR. setJwtValidator REPLACES, so a bare
        // `jwtDecoder.setJwtValidator(new TokenOriginValidator(...))` in SecurityJwtConfiguration answers 200 here
        // — correct issuer, correct audience, expired an hour ago, and nothing left to notice.
        webTestClient
            .get()
            .uri("/api/auth/sessions")
            .headers(headers -> headers.setBearerAuth(token(TokenProvider.ISSUER, TokenProvider.AUDIENCE, -3600)))
            .exchange()
            .expectStatus()
            .isUnauthorized();
    }

    @Test
    void anOriginRejectionIsCountedAsUntrustedOriginRatherThanLoggedAsUnknown() {
        // The cutover has to be watchable. Until this meter existed a rejection matched none of
        // SecurityJwtConfiguration's message branches and fell to the else, so every live pre-claims session
        // produced one ERROR labelled "Unknown JWT" and no metric at all — and "old tokens draining as expected"
        // was indistinguishable from "the issuer string is wrong and nobody can sign in".
        double before = untrustedOriginCount();

        webTestClient
            .get()
            .uri("/api/auth/sessions")
            .headers(headers -> headers.setBearerAuth(token("hc-patient-gateway", "hc-patient", 3600)))
            .exchange()
            .expectStatus()
            .isUnauthorized();

        assertThat(untrustedOriginCount()).isEqualTo(before + 1);
    }

    private double untrustedOriginCount() {
        return meterRegistry
            .get(SecurityMetersService.INVALID_TOKENS_METER_NAME)
            .tag(SecurityMetersService.INVALID_TOKENS_METER_CAUSE_DIMENSION, "untrusted-origin")
            .counter()
            .count();
    }

    private String token(String issuer, String audience, long secondsUntilExpiry) {
        byte[] keyBytes = Base64.from(jwtKey).decode();
        SecretKey key = new SecretKeySpec(keyBytes, 0, keyBytes.length, MacAlgorithm.HS512.getName());
        Instant now = Instant.now();
        Instant expiresAt = now.plus(secondsUntilExpiry, ChronoUnit.SECONDS);
        // A token that has already expired has to be dated from before it expired — NimbusJwtEncoder refuses a
        // claim set whose iat is after its exp — and far enough back that JwtTimestampValidator's 60-second default
        // clock skew cannot make the expired case look live.
        Instant issuedAt = expiresAt.minus(300, ChronoUnit.SECONDS);
        if (issuedAt.isAfter(now.minus(120, ChronoUnit.SECONDS))) {
            issuedAt = now.minus(120, ChronoUnit.SECONDS);
        }

        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .subject(LOGIN)
            // ROLE_USER and nothing else: what an applicant holds here, and what hc-patient hands every patient
            // alongside ROLE_PATIENT. Rejecting ROLE_PATIENT is deliberately NOT the fix — which authorities the
            // sibling stacks mint is theirs to change.
            .claim(SecurityUtils.AUTHORITIES_KEY, AuthoritiesConstants.USER);
        if (issuer != null) {
            claims.issuer(issuer);
        }
        if (audience != null) {
            claims.audience(List.of(audience));
        }

        return new NimbusJwtEncoder(new ImmutableSecret<>(key))
            .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS512).build(), claims.build()))
            .getTokenValue();
    }
}
