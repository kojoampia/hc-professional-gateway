package net.jojoaddison.config;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;

/**
 * Rejects tokens minted for a different Health Connect product.
 *
 * <p>The HMAC signing key is shared with hc-admin and hc-patient — one key, three products, by design, so that a
 * session works across them. The consequence nobody chose is that a token minted by any one of the three verifies
 * perfectly at the other two, carrying whatever authorities it was given. hc-patient grants {@code ROLE_USER}
 * alongside {@code ROLE_PATIENT}, and {@code ROLE_USER} is what an applicant here holds — so a patient's token is
 * indistinguishable from an applicant's, and if its {@code sub} collides with a professional's login it resolves to
 * that professional. Logins are human-chosen and registration on the sibling stack is self-service, so nothing has to
 * be guessed. See {@code docs/backlog.md} item 27.</p>
 *
 * <p>{@code TokenProvider} has stamped {@code iss} and {@code aud} since 2026-09-06. This is the other half: the
 * check that makes them mean something. It matters here as well as in {@code professionalservice} — this gateway
 * decodes the token on every proxied request, so a sibling stack's token reaches the routing layer first.</p>
 *
 * <p>This is a port of hc-patient's validator of the same name, deliberately kept identical in shape. Three products
 * telling each other apart is not a place for three different mechanisms.</p>
 *
 * <h2>Why it is off by default</h2>
 *
 * <p>Turning this on rejects every token that lacks the claims — which is every token in flight at the moment it is
 * switched on, and every token the sibling products issue until they emit their own. Enabled on the day it ships it
 * would sign out every user of this stack at once.</p>
 *
 * <p>The properties live under {@code application.*} rather than {@code jhipster.*} deliberately: JHipsterProperties
 * binds with {@code ignoreUnknownFields = false}, so an extra key under its prefix does not get ignored — it fails
 * the whole context startup with an unbound-property error.</p>
 *
 * <p>So it ships disabled and is enabled per environment with
 * {@code application.security.jwt.validate-origin=true}, once:</p>
 *
 * <ol>
 *   <li>the claims have been live longer than the longest token lifetime — 30 days here, the JHipster remember-me
 *       window — so no valid token predates them; and</li>
 *   <li>hc-admin and hc-patient emit their own {@code iss}/{@code aud}, or are accepted here by adding their issuers
 *       to {@code trusted-issuers}.</li>
 * </ol>
 *
 * <p>A flag makes that a one-line config change per environment and an instant rollback, rather than a code release
 * on the day something goes wrong.</p>
 */
public class TokenOriginValidator implements OAuth2TokenValidator<Jwt> {

    private static final Logger LOG = LoggerFactory.getLogger(TokenOriginValidator.class);

    private final List<String> trustedIssuers;
    private final String requiredAudience;

    public TokenOriginValidator(List<String> trustedIssuers, String requiredAudience) {
        this.trustedIssuers = List.copyOf(trustedIssuers);
        this.requiredAudience = requiredAudience;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        // getClaimAsString, NOT getIssuer(). Spring's convenience getter coerces `iss` to a java.net.URL and
        // throws IllegalArgumentException on anything else — and ours is "hc-professional-gateway", a plain string,
        // which RFC 7519 explicitly permits (StringOrURI). Using the typed getter here meant hc-patient's copy of this
        // validator threw on every request the moment it was enabled, rather than validating anything.
        String issuer = token.getClaimAsString(JwtClaimNames.ISS);
        if (issuer == null || !trustedIssuers.contains(issuer)) {
            LOG.warn("Rejected a token whose issuer is not trusted by this gateway");
            return OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_issuer", "The token was not issued by a trusted issuer", null)
            );
        }
        List<String> audience = token.getAudience();
        if (audience == null || !audience.contains(requiredAudience)) {
            LOG.warn("Rejected a token that is not addressed to this subsystem");
            return OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_audience", "The token was not issued for this subsystem", null)
            );
        }
        return OAuth2TokenValidatorResult.success();
    }
}
