package net.jojoaddison.config;

import static net.jojoaddison.security.SecurityUtils.AUTHORITIES_KEY;
import static net.jojoaddison.security.SecurityUtils.JWT_ALGORITHM;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import net.jojoaddison.management.SecurityMetersService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtGrantedAuthoritiesConverterAdapter;

@Configuration
public class SecurityJwtConfiguration {

    private final Logger log = LoggerFactory.getLogger(SecurityJwtConfiguration.class);

    @Value("${jhipster.security.authentication.jwt.base64-secret}")
    private String jwtKey;

    /**
     * Origin-validation settings. Injected as typed properties rather than read with {@code @Value} because the
     * {@code application.*} prefix is bound strictly — an unknown key there fails context startup rather than being
     * ignored, so the binding has to be declared.
     */
    private final ApplicationProperties.Security.Jwt jwtProperties;

    public SecurityJwtConfiguration(ApplicationProperties applicationProperties) {
        this.jwtProperties = applicationProperties.getSecurity().getJwt();
    }

    @Bean
    public ReactiveJwtDecoder jwtDecoder(SecurityMetersService metersService) {
        NimbusReactiveJwtDecoder jwtDecoder = NimbusReactiveJwtDecoder.withSecretKey(getSecretKey()).macAlgorithm(JWT_ALGORITHM).build();
        if (jwtProperties.isValidateOrigin()) {
            // Layered on top of the defaults rather than replacing them: setJwtValidator REPLACES, so a bare
            // validator here would silently drop the expiry check — a worse hole than the one being closed, and one
            // this gateway would open on every proxied request. Held by
            // TokenOriginValidationEnabledIT.expiryIsStillCheckedWithTheValidatorAttached, not by this comment:
            // flattened to a bare validator, that test answers 200 to a token that expired an hour ago.
            jwtDecoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefault(),
                    new TokenOriginValidator(jwtProperties.getTrustedIssuers(), jwtProperties.getAudience())
                )
            );
            log.info(
                "JWT origin validation is ON: issuers {} audience '{}'",
                jwtProperties.getTrustedIssuers(),
                jwtProperties.getAudience()
            );
        } else {
            log.info(
                "JWT origin validation is OFF. A token minted by any product sharing this signing key is accepted. " +
                "Enable with application.security.jwt.validate-origin=true once every issuer emits iss/aud."
            );
        }
        return token -> {
            try {
                return jwtDecoder
                    .decode(token)
                    .doOnError(e -> {
                        if (isUntrustedOrigin(e.getMessage())) {
                            // Not "Unknown": a token that verified, had not expired, and was minted for another
                            // product. This is the branch a validate-origin cutover is watched on — every live
                            // pre-claims session lands here, and a count that does not fall means the issuer string
                            // is wrong rather than that old tokens are draining.
                            metersService.trackTokenUntrustedOrigin();
                            log.warn("Rejected a token minted for another Health Connect product: {}", e.getMessage());
                        } else if (e.getMessage().contains("Jwt expired at")) {
                            metersService.trackTokenExpired();
                        } else if (e.getMessage().contains("Failed to validate the token")) {
                            metersService.trackTokenInvalidSignature();
                        } else if (
                            e.getMessage().contains("Invalid JWT serialization:") ||
                            e.getMessage().contains("Invalid unsecured/JWS/JWE header:")
                        ) {
                            metersService.trackTokenMalformed();
                        } else {
                            log.error("Unknown JWT reactive error {}", e.getMessage());
                        }
                    });
            } catch (Exception e) {
                // Checked FIRST, and that order is load-bearing: Nimbus wraps a validator failure as "An error
                // occurred while attempting to decode the Jwt: <description>", so the malformed branch below would
                // otherwise swallow every origin rejection and count it as a broken token.
                if (isUntrustedOrigin(e.getMessage())) {
                    metersService.trackTokenUntrustedOrigin();
                    log.warn("Rejected a token minted for another Health Connect product: {}", e.getMessage());
                } else if (e.getMessage().contains("An error occurred while attempting to decode the Jwt")) {
                    metersService.trackTokenMalformed();
                } else if (e.getMessage().contains("Failed to validate the token")) {
                    metersService.trackTokenInvalidSignature();
                } else {
                    log.error("Unknown JWT error {}", e.getMessage());
                }
                throw e;
            }
        };
    }

    @Bean
    public JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(getSecretKey()));
    }

    @Bean
    public ReactiveJwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter grantedAuthoritiesConverter = new JwtGrantedAuthoritiesConverter();
        grantedAuthoritiesConverter.setAuthorityPrefix("");
        grantedAuthoritiesConverter.setAuthoritiesClaimName(AUTHORITIES_KEY);

        ReactiveJwtAuthenticationConverter jwtAuthenticationConverter = new ReactiveJwtAuthenticationConverter();

        jwtAuthenticationConverter.setJwtGrantedAuthoritiesConverter(
            new ReactiveJwtGrantedAuthoritiesConverterAdapter(grantedAuthoritiesConverter)
        );
        return jwtAuthenticationConverter;
    }

    /**
     * Whether a decode failure came from {@link TokenOriginValidator} rather than from signature, expiry or a
     * malformed token.
     *
     * <p>Matching on the message is what every other branch here does — Spring surfaces a validator failure as a
     * {@code JwtValidationException} whose message is the wrapped {@code OAuth2Error} description and nothing else,
     * so there is no type to switch on. The two descriptions are constants on the validator so this is not a copy.</p>
     */
    private boolean isUntrustedOrigin(String message) {
        return (
            message != null &&
            (message.contains(TokenOriginValidator.UNTRUSTED_ISSUER_DESCRIPTION) ||
                message.contains(TokenOriginValidator.UNTRUSTED_AUDIENCE_DESCRIPTION))
        );
    }

    private SecretKey getSecretKey() {
        byte[] keyBytes = Base64.from(jwtKey).decode();
        return new SecretKeySpec(keyBytes, 0, keyBytes.length, JWT_ALGORITHM.getName());
    }
}
