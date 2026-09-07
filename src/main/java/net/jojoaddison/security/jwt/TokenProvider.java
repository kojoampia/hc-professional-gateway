package net.jojoaddison.security.jwt;

import static net.jojoaddison.security.SecurityUtils.AUTHORITIES_KEY;
import static net.jojoaddison.security.SecurityUtils.JWT_ALGORITHM;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import net.jojoaddison.security.AccountUserDetails;
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
 * <p><strong>The claim set gained {@code iss} and {@code aud} on 2026-09-06</strong> ({@code backlog.md}
 * item 27). Before that it was {@code sub}, {@code iat}, {@code exp} and the space-delimited
 * {@code auth} string, on the reasoning that "no claim changes" is what let MOB3 ship without
 * coordinating three deployments. That reasoning held only while nothing needed to tell the three
 * stacks apart. Three stacks (hc-admin, hc-professional, hc-patient) share the HS512 signing key, so
 * a token any one of them mints verifies at all three; {@code professionalservice} resolves the
 * caller by matching {@code sub} — a login — against {@code Profile.accountId}, and hc-patient hands
 * out {@code ROLE_USER} alongside {@code ROLE_PATIENT}, which is what an applicant here holds. A
 * colliding login on the sibling stack therefore reaches the {@code .authenticated()} onboarding
 * island as that professional. {@code iss} is the only thing that can tell the two apart.
 *
 * <p><strong>Nothing validates these claims by default.</strong> That is sequencing, not oversight:
 * every token in flight when this shipped lacks them, so a decoder that demanded them would sign out
 * every live session at once. {@link net.jojoaddison.config.TokenOriginValidator} does the checking
 * and is off until {@code application.security.jwt.validate-origin=true}.
 *
 * <p><strong>It gained {@code uid} on 2026-09-07</strong> ({@code backlog.md} item 48), for the same
 * sequencing reason and on the same 30-day clock. {@code professionalservice} has no user store, so
 * until this claim existed the only identifier crossing the boundary was {@code sub} — a login —
 * while this gateway keys its own account events on {@code User.id}. The two producers on
 * {@code hc.professional.registration} therefore named one clinician differently, which is what item
 * 48 was opened for. <b>Nothing may depend on the claim being present</b> until it has been live
 * longer than the remember-me token lifetime (30 days); {@code api/} reads it as an optional extra
 * beside the login and never in place of it.
 */
@Component
public class TokenProvider {

    /**
     * Identifies this gateway as the minter of a token. Must match an issuer that
     * {@code professionalservice} trusts once validation is switched on, and must differ from what
     * hc-admin and hc-patient use — telling the three apart is the entire point. hc-patient's
     * gateway stamps {@code hc-patient-gateway}; the estate's convention is {@code hc-<product>-gateway}.
     */
    public static final String ISSUER = "hc-professional-gateway";

    /** The subsystem this stack's own services check for. hc-admin and hc-patient have their own. */
    public static final String AUDIENCE = "hc-professional";

    /**
     * Every subsystem a token minted here is legitimately good for.
     *
     * <p>Wider than hc-patient's single-valued {@code aud}, and deliberately so: <strong>this is the
     * only one of the three gateways that proxies to the other two.</strong>
     * {@code /services/patientservice/**} and {@code /services/adminservice/**} carry this very token
     * across to hc-patient and hc-admin, which is the whole reason the signing key is shared. Their
     * validators check {@code audience.contains(requiredAudience)} against a single configured
     * string, so a token naming only {@code hc-professional} could never be accepted there whatever
     * they set — one of the two sides has to widen, and the minting side is where {@code aud} is
     * defined to be a list.
     *
     * <p>This grants nothing on its own. A sibling still has to add {@link #ISSUER} to its
     * {@code trusted-issuers} before one of our tokens is accepted, which is a deliberate,
     * reviewable decision on their side rather than something this list can make for them. The names
     * are the ones hc-patient's own tests already use for the three products.
     */
    public static final List<String> AUDIENCES = List.of(AUDIENCE, "hc-patient", "hc-admin");

    /**
     * The claim carrying {@code User.id} — the account identifier that survives a login being
     * edited, and the value this gateway keys its account events on.
     *
     * <p>Named {@code uid} rather than reusing {@code sub} because {@code sub} is a published
     * contract: {@code professionalservice} resolves the caller by matching it against
     * {@code Profile.accountId}, every audit row in that database holds it, and changing what it
     * means would orphan all of them at once. This sits beside it instead.
     */
    public static final String UID_KEY = "uid";

    private final JwtEncoder jwtEncoder;

    public TokenProvider(JwtEncoder jwtEncoder) {
        this.jwtEncoder = jwtEncoder;
    }

    /** Mints from an authenticated principal — the login path. */
    public String createAccessToken(Authentication authentication, Duration validity) {
        return createAccessToken(
            authentication.getName(),
            uidOf(authentication),
            authorityString(authentication.getAuthorities()),
            validity
        );
    }

    /** Mints from a login plus a pre-joined authority string — the refresh path. */
    public String createAccessToken(String login, String uid, String authorities, Duration validity) {
        Instant now = Instant.now();

        // @formatter:off
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
            .issuedAt(now)
            .expiresAt(now.plus(validity))
            .subject(login)
            // Origin, so that "authenticated" can be made to mean "authenticated by THIS stack".
            // Both login and refresh mint through here, so both carry them or neither does.
            .issuer(ISSUER)
            .audience(AUDIENCES)
            .claim(AUTHORITIES_KEY, authorities);
        // @formatter:on

        // Omitted rather than written null when the account has no id. A claim that is present and
        // empty is a value a reader can compare against stored data and match something; an absent
        // one is the only shape that says "not known" without ambiguity. api/ treats both blank and
        // absent as absent for the same reason, but only one of them can be got wrong here.
        if (uid != null && !uid.isBlank()) {
            claims.claim(UID_KEY, uid);
        }

        JwsHeader jwsHeader = JwsHeader.with(JWT_ALGORITHM).build();
        return this.jwtEncoder.encode(JwtEncoderParameters.from(jwsHeader, claims.build())).getTokenValue();
    }

    /**
     * The gateway {@code User.id} behind an authentication, or null when the principal does not
     * carry one.
     *
     * <p>Null is reachable and is not a defect: a test principal, or any {@link Authentication}
     * built from something other than {@link AccountUserDetails}, has no id to give. The token then
     * carries no {@code uid} claim, which is exactly the shape every token minted before 2026-09-07
     * has — so the absent case is the one already on the wire and must stay harmless.
     */
    public static String uidOf(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof AccountUserDetails account ? account.getUid() : null;
    }

    public String authorityString(Collection<? extends GrantedAuthority> authorities) {
        return authorities.stream().map(GrantedAuthority::getAuthority).collect(Collectors.joining(" "));
    }
}
