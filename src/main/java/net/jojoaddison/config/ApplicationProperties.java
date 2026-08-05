package net.jojoaddison.config;

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

    // jhipster-needle-application-properties-property

    public Auth getAuth() {
        return auth;
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
    // jhipster-needle-application-properties-property-class
}
