package net.jojoaddison.security.jwt;

import static net.jojoaddison.security.SecurityUtils.AUTHORITIES_KEY;
import static net.jojoaddison.security.SecurityUtils.JWT_ALGORITHM;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.util.Base64;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import net.jojoaddison.repository.UserRepository;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.crypto.codec.Hex;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@TestConfiguration(proxyBeanMethods = false)
public class JwtAuthenticationTestUtils {

    @Bean
    private MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }

    @Bean
    ReactiveUserDetailsService userDetailsService() {
        return Mockito.mock(ReactiveUserDetailsService.class);
    }

    @Bean
    UserRepository userRepository() {
        return Mockito.mock(UserRepository.class);
    }

    public static String createValidToken(String jwtKey) {
        return createValidTokenForUser(jwtKey, "anonymous");
    }

    public static String createValidTokenForUser(String jwtKey, String user) {
        JwtEncoder encoder = jwtEncoder(jwtKey);

        var now = Instant.now();

        JwtClaimsSet claims = JwtClaimsSet.builder()
            .issuedAt(now)
            .expiresAt(now.plusSeconds(60))
            .subject(user)
            .claims(customClaim -> customClaim.put(AUTHORITIES_KEY, List.of("ROLE_ADMIN")))
            .build();

        JwsHeader jwsHeader = JwsHeader.with(JWT_ALGORITHM).build();
        return encoder.encode(JwtEncoderParameters.from(jwsHeader, claims)).getTokenValue();
    }

    /**
     * A token carrying exactly the authorities given — and, given none, <b>no {@code auth} claim at
     * all</b>.
     *
     * <p>The claim is written as ONE SPACE-DELIMITED STRING, because that is what
     * {@link net.jojoaddison.security.jwt.TokenProvider} really mints and what every token in the
     * estate looks like on the wire. {@link #createValidTokenForUser} above writes a {@code List}
     * instead; {@code JwtGrantedAuthoritiesConverter} accepts both, so that difference is invisible
     * until something reads the claim itself. A rule test asserting who may pass should not be the
     * place that first discovers the two shapes are not interchangeable.
     *
     * <p><b>The empty case omits the claim rather than writing {@code ""}</b>, which is a different
     * path through {@code JwtGrantedAuthoritiesConverter} — absent claim versus present-and-empty —
     * and it is the one a hand-minted probe token takes. It used to write {@code String.join(" ")},
     * so a test whose name said "no {@code auth} claim at all" was exercising the empty-string path
     * and leaving the absent one uncovered. Both assertions are valid; only one of them was the one
     * being described. {@link #createTokenWithEmptyAuthorityClaim} keeps the other reachable.
     */
    public static String createTokenWithAuthorities(String jwtKey, String user, String... authorities) {
        return encodeToken(jwtKey, user, authorities.length == 0 ? null : String.join(" ", authorities));
    }

    /**
     * A token whose {@code auth} claim is present and empty — the shape {@code TokenProvider} mints
     * for an account holding no authority, since it always writes the claim.
     */
    public static String createTokenWithEmptyAuthorityClaim(String jwtKey, String user) {
        return encodeToken(jwtKey, user, "");
    }

    /** {@code authorities == null} omits the claim; anything else writes it verbatim. */
    private static String encodeToken(String jwtKey, String user, String authorities) {
        JwtEncoder encoder = jwtEncoder(jwtKey);

        var now = Instant.now();

        JwtClaimsSet.Builder claims = JwtClaimsSet.builder().issuedAt(now).expiresAt(now.plusSeconds(60)).subject(user);
        if (authorities != null) {
            claims.claims(customClaim -> customClaim.put(AUTHORITIES_KEY, authorities));
        }

        JwsHeader jwsHeader = JwsHeader.with(JWT_ALGORITHM).build();
        return encoder.encode(JwtEncoderParameters.from(jwsHeader, claims.build())).getTokenValue();
    }

    public static String createTokenWithDifferentSignature() {
        JwtEncoder encoder = jwtEncoder("Xfd54a45s65fds737b9aafcb3412e07ed99b267f33413274720ddbb7f6c5e64e9f14075f2d7ed041592f0b7657baf8");

        var now = Instant.now();
        var past = now.plusSeconds(60);

        JwtClaimsSet claims = JwtClaimsSet.builder().issuedAt(now).expiresAt(past).subject("anonymous").build();

        JwsHeader jwsHeader = JwsHeader.with(JWT_ALGORITHM).build();
        return encoder.encode(JwtEncoderParameters.from(jwsHeader, claims)).getTokenValue();
    }

    public static String createExpiredToken(String jwtKey) {
        JwtEncoder encoder = jwtEncoder(jwtKey);

        var now = Instant.now();
        var past = now.minusSeconds(600);

        JwtClaimsSet claims = JwtClaimsSet.builder().issuedAt(past).expiresAt(past.plusSeconds(1)).subject("anonymous").build();

        JwsHeader jwsHeader = JwsHeader.with(JWT_ALGORITHM).build();
        return encoder.encode(JwtEncoderParameters.from(jwsHeader, claims)).getTokenValue();
    }

    public static String createInvalidToken(String jwtKey) {
        return createValidToken(jwtKey).substring(1);
    }

    public static String createSignedInvalidJwt(String jwtKey) throws Exception {
        return calculateHMAC("foo", jwtKey);
    }

    private static JwtEncoder jwtEncoder(String jwtKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(getSecretKey(jwtKey)));
    }

    private static SecretKey getSecretKey(String jwtKey) {
        byte[] keyBytes = Base64.from(jwtKey).decode();
        return new SecretKeySpec(keyBytes, 0, keyBytes.length, JWT_ALGORITHM.getName());
    }

    private static String calculateHMAC(String data, String key) throws Exception {
        SecretKeySpec secretKeySpec = new SecretKeySpec(Base64.from(key).decode(), "HmacSHA512");
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(secretKeySpec);
        return String.copyValueOf(Hex.encode(mac.doFinal(data.getBytes())));
    }
}
