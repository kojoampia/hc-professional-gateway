package net.jojoaddison.security.jwt;

import static net.jojoaddison.security.SecurityUtils.JWT_ALGORITHM;
import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.util.Base64;
import java.time.Duration;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import net.jojoaddison.security.AccountUserDetails;
import net.jojoaddison.security.AuthoritiesConstants;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * The {@code uid} claim, and above all its absence (backlog.md item 48).
 *
 * <p>The integration tests beside this one assert the happy path on both mint routes. What they
 * cannot reach is the shape a token takes when there is no {@code User.id} to put on it — which is
 * the shape <b>every token minted before 2026-09-07 already has</b>, and the one that has to stay
 * harmless for the thirty days a remember-me token lives. So it is asserted here directly rather
 * than inferred from the claim being optional.
 */
class TokenProviderUidClaimUnitTest {

    private static final String KEY = "fd54a45s65fds737b9aafcb3412e07ed99b267f33413274720ddbb7f6c5e64e9f14075f2d7ed041592f0b7657baf8";

    private final TokenProvider tokenProvider = new TokenProvider(new NimbusJwtEncoder(new ImmutableSecret<>(secretKey())));

    @Test
    void aPrincipalCarryingAnAccountIdPutsItOnTheToken() {
        String token = tokenProvider.createAccessToken(
            authentication(new AccountUserDetails("doctor", "x", "68b0c2f1", authorities())),
            Duration.ofMinutes(5)
        );

        assertThat(claim(token, TokenProvider.UID_KEY)).isEqualTo("\"68b0c2f1\"");
        assertThat(claim(token, "sub")).isEqualTo("\"doctor\"");
    }

    /**
     * A principal that is not an {@link AccountUserDetails} mints the pre-2026-09-07 claim set.
     *
     * <p>Reachable in production rather than only in tests: anything that authenticates without
     * going through {@code DomainUserDetailsService} has no account row behind it.
     */
    @Test
    void aPrincipalWithoutOneMintsTheClaimSetThatWasThereBefore() {
        Authentication authentication = new UsernamePasswordAuthenticationToken("doctor", "x", authorities());

        String token = tokenProvider.createAccessToken(authentication, Duration.ofMinutes(5));

        assertThat(TokenProvider.uidOf(authentication)).isNull();
        assertThat(claim(token, TokenProvider.UID_KEY)).isNull();
        assertThat(claim(token, "sub")).isEqualTo("\"doctor\"");
        assertThat(claim(token, "iss")).isEqualTo("\"" + TokenProvider.ISSUER + "\"");
    }

    /**
     * A blank id is treated as no id, and the claim is <b>omitted</b> rather than written empty.
     *
     * <p>An empty string is a value a consumer can compare against stored data and match something;
     * an absent claim is the only shape that says "not known" without ambiguity. This is the same
     * reasoning {@code api/} applies when reading it, and only one of the two ends can be got wrong
     * here.
     */
    @Test
    void aBlankAccountIdIsOmittedRatherThanWrittenAsAnEmptyClaim() {
        String token = tokenProvider.createAccessToken("doctor", "  ", "ROLE_USER", Duration.ofMinutes(5));

        assertThat(claim(token, TokenProvider.UID_KEY)).isNull();
    }

    // ------------------------------------------------------------------ helpers

    private static Authentication authentication(AccountUserDetails principal) {
        return new UsernamePasswordAuthenticationToken(principal, principal.getPassword(), principal.getAuthorities());
    }

    private static List<SimpleGrantedAuthority> authorities() {
        return List.of(new SimpleGrantedAuthority(AuthoritiesConstants.USER));
    }

    /** The raw JSON value of one claim, or null when the claim is not in the payload at all. */
    private static String claim(String token, String name) {
        String payload = new String(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        var node = new tools.jackson.databind.ObjectMapper().readTree(payload).get(name);
        return node == null ? null : node.toString();
    }

    private static SecretKey secretKey() {
        byte[] keyBytes = Base64.from(KEY).decode();
        return new SecretKeySpec(keyBytes, 0, keyBytes.length, JWT_ALGORITHM.getName());
    }
}
