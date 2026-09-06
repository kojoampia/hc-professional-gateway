package net.jojoaddison.config;

import java.util.List;
import net.jojoaddison.security.jwt.TokenProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Properties specific to Hc Professional Gateway.
 * <p>
 * Properties are configured in the {@code application.yml} file.
 * See {@link tech.jhipster.config.JHipsterProperties} for a good example.
 */
@ConfigurationProperties(prefix = "application", ignoreUnknownFields = false)
public class ApplicationProperties {

    private final Auth auth = new Auth();
    private final Security security = new Security();

    // jhipster-needle-application-properties-property

    public Auth getAuth() {
        return auth;
    }

    public Security getSecurity() {
        return security;
    }

    // jhipster-needle-application-properties-property-getter

    /**
     * Mobile session settings.
     *
     * <p>Deliberately a <strong>separate namespace</strong> from
     * {@code jhipster.security.authentication.jwt.*}, which keeps its 24 h / 30 d values untouched.
     * The browser app and the two sibling stacks that share the signing key therefore cannot
     * regress when these are tuned — that separation is the entire point of not reusing the
     * JHipster properties.
     */
    public static class Auth {

        private final Mobile mobile = new Mobile();

        public Mobile getMobile() {
            return mobile;
        }

        public static class Mobile {

            /**
             * Access-token lifetime for mobile clients. Short by design: it is the exposure window
             * after a refresh token is revoked, because the downstream services are stateless and
             * hold no revocation list.
             */
            private long accessTokenValidityInSeconds = 900;

            /** Refresh-token lifetime. Rotated on every use, so this bounds an idle session. */
            private long refreshTokenValidityInDays = 60;

            /**
             * Concurrent live sessions per login; the oldest are revoked past this. Zero disables
             * the cap.
             */
            private int maxSessionsPerLogin = 5;

            public long getAccessTokenValidityInSeconds() {
                return accessTokenValidityInSeconds;
            }

            public void setAccessTokenValidityInSeconds(long accessTokenValidityInSeconds) {
                this.accessTokenValidityInSeconds = accessTokenValidityInSeconds;
            }

            public long getRefreshTokenValidityInDays() {
                return refreshTokenValidityInDays;
            }

            public void setRefreshTokenValidityInDays(long refreshTokenValidityInDays) {
                this.refreshTokenValidityInDays = refreshTokenValidityInDays;
            }

            public int getMaxSessionsPerLogin() {
                return maxSessionsPerLogin;
            }

            public void setMaxSessionsPerLogin(int maxSessionsPerLogin) {
                this.maxSessionsPerLogin = maxSessionsPerLogin;
            }
        }
    }

    /**
     * Security settings this application owns, as distinct from the ones JHipsterProperties owns.
     *
     * <p>They live here rather than under {@code jhipster.security.*} because JHipsterProperties binds with
     * {@code ignoreUnknownFields = false}: an extra key under its prefix is not ignored, it fails context startup
     * with an unbound-property error. This class has the same strictness, which is why the nested types below exist
     * rather than the properties being read with a bare {@code @Value}.
     */
    public static class Security {

        private final Jwt jwt = new Jwt();

        public Jwt getJwt() {
            return jwt;
        }

        public static class Jwt {

            /**
             * Whether to reject tokens minted for a different Health Connect product.
             *
             * <p>Off by default, and that default is load-bearing — see {@link TokenOriginValidator}. Turning it on
             * rejects every token that lacks {@code iss}/{@code aud}, which is every token in flight at the moment
             * it is switched on, and every token a sibling product issues until it emits its own.
             */
            private boolean validateOrigin = false;

            /** Issuers whose tokens this gateway accepts, once {@link #validateOrigin} is on. */
            private List<String> trustedIssuers = List.of(TokenProvider.ISSUER);

            /** The audience a token must name to be accepted here. */
            private String audience = TokenProvider.AUDIENCE;

            public boolean isValidateOrigin() {
                return validateOrigin;
            }

            public void setValidateOrigin(boolean validateOrigin) {
                this.validateOrigin = validateOrigin;
            }

            public List<String> getTrustedIssuers() {
                return trustedIssuers;
            }

            public void setTrustedIssuers(List<String> trustedIssuers) {
                this.trustedIssuers = trustedIssuers;
            }

            public String getAudience() {
                return audience;
            }

            public void setAudience(String audience) {
                this.audience = audience;
            }
        }
    }
    // jhipster-needle-application-properties-property-class
}
