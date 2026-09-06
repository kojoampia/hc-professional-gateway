package net.jojoaddison.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import net.jojoaddison.security.jwt.TokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Covers the check that tells the three Health Connect products apart.
 *
 * <p>They share one HMAC signing key, so signature verification cannot distinguish them: a token minted by hc-patient
 * verifies here perfectly, carrying hc-patient's authorities. Issuer and audience are the only thing that can, and
 * this is what enforces them. Mirrors hc-patient's test of the same name; the decision table is deliberately
 * identical.
 */
class TokenOriginValidatorUnitTest {

    private final TokenOriginValidator validator = new TokenOriginValidator(List.of(TokenProvider.ISSUER), TokenProvider.AUDIENCE);

    @Test
    void acceptsATokenFromThisSubsystem() {
        assertThat(validate(TokenProvider.ISSUER, List.of(TokenProvider.AUDIENCE)).hasErrors()).isFalse();
    }

    @Test
    void acceptsWhatThisGatewayActuallyMints() {
        // Derived rather than restated: if TokenProvider ever stops naming its own audience, this fails here rather
        // than in production on the day validation is switched on.
        assertThat(validate(TokenProvider.ISSUER, TokenProvider.AUDIENCES).hasErrors()).isFalse();
    }

    @Test
    void rejectsATokenFromASiblingProduct() {
        // The whole point. Same key, same signature, different product.
        OAuth2TokenValidatorResult result = validate("hc-patient-gateway", List.of("hc-patient"));

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).anyMatch(e -> "invalid_issuer".equals(e.getErrorCode()));
    }

    @Test
    void rejectsATokenAddressedToAnotherSubsystem() {
        // A trusted issuer can still mint a token meant for somewhere else.
        OAuth2TokenValidatorResult result = validate(TokenProvider.ISSUER, List.of("hc-admin"));

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).anyMatch(e -> "invalid_audience".equals(e.getErrorCode()));
    }

    @Test
    void rejectsATokenWithNoIssuerAtAll() {
        // Every token this gateway minted before 2026-09-06 looks like this, which is precisely why the validator
        // ships disabled.
        assertThat(validate(null, List.of(TokenProvider.AUDIENCE)).hasErrors()).isTrue();
    }

    @Test
    void rejectsATokenWithNoAudienceAtAll() {
        assertThat(validate(TokenProvider.ISSUER, null).hasErrors()).isTrue();
    }

    @Test
    void acceptsAnAdditionallyTrustedIssuerWhenConfigured() {
        // The migration path: rather than waiting for a sibling to change, its issuer can be added to the trust list.
        TokenOriginValidator lenient = new TokenOriginValidator(List.of(TokenProvider.ISSUER, "hc-admin-gateway"), TokenProvider.AUDIENCE);
        Jwt token = jwt("hc-admin-gateway", List.of(TokenProvider.AUDIENCE));

        assertThat(lenient.validate(token).hasErrors()).isFalse();
    }

    @Test
    void toleratesAClaimSetWithNothingButASubject() {
        // Defensive: the validator reads two claims and must not throw on a token that has neither.
        Jwt bare = new Jwt("token", null, null, Map.of("alg", "HS512"), Map.of("sub", "alice"));

        assertThat(validator.validate(bare).hasErrors()).isTrue();
    }

    private OAuth2TokenValidatorResult validate(String issuer, List<String> audience) {
        return validator.validate(jwt(issuer, audience));
    }

    private static Jwt jwt(String issuer, List<String> audience) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "HS512").claim("sub", "alice");
        if (issuer != null) {
            builder.claim("iss", issuer);
        }
        if (audience != null) {
            builder.audience(audience);
        }
        return builder.build();
    }
}
