package net.jojoaddison.security.jwt;

import static net.jojoaddison.security.SecurityUtils.AUTHORITIES_KEY;
import static net.jojoaddison.security.SecurityUtils.JWT_ALGORITHM;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * Mints access tokens.
 *
 * <p>Extracted from {@code AuthenticateController} so that login and refresh mint identically.
 * Refresh has no {@link Authentication} to hand — it rebuilds the authority list from the user
 * record — so the minting logic could not stay trapped in a controller method that required one.
 *
 * <p><strong>The claim set is deliberately unchanged from what the gateway has always issued:</strong>
 * {@code sub}, {@code iat}, {@code exp} and the space-delimited {@code auth} string. Three stacks
 * (hc-admin, hc-professional, hc-patient) share the HS512 signing key, and {@code professionalservice}
 * reads only {@code sub} and {@code auth}. Adding a claim here would be safe for validators that
 * ignore unknown claims, but there is no need for one yet, and "no claim changes" is what lets this
 * ship without coordinating three deployments.
 */
@Component
public class TokenProvider {

    private final JwtEncoder jwtEncoder;

    public TokenProvider(JwtEncoder jwtEncoder) {
        this.jwtEncoder = jwtEncoder;
    }

    /** Mints from an authenticated principal — the login path. */
    public String createAccessToken(Authentication authentication, Duration validity) {
        return createAccessToken(authentication.getName(), authorityString(authentication.getAuthorities()), validity);
    }

    /** Mints from a login plus a pre-joined authority string — the refresh path. */
    public String createAccessToken(String login, String authorities, Duration validity) {
        Instant now = Instant.now();

        // @formatter:off
        JwtClaimsSet claims = JwtClaimsSet.builder()
            .issuedAt(now)
            .expiresAt(now.plus(validity))
            .subject(login)
            .claim(AUTHORITIES_KEY, authorities)
            .build();
        // @formatter:on

        JwsHeader jwsHeader = JwsHeader.with(JWT_ALGORITHM).build();
        return this.jwtEncoder.encode(JwtEncoderParameters.from(jwsHeader, claims)).getTokenValue();
    }

    public String authorityString(Collection<? extends GrantedAuthority> authorities) {
        return authorities.stream().map(GrantedAuthority::getAuthority).collect(Collectors.joining(" "));
    }
}
