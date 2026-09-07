package net.jojoaddison.security;

import java.io.Serial;
import java.util.Collection;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The authenticated principal, carrying the gateway's {@code User.id} beside the login.
 *
 * <p>Exists so that {@code TokenProvider} can stamp the {@code uid} claim without a second query.
 * {@link DomainUserDetailsService} has the {@code User} document in hand when it builds the
 * principal, and {@code UserDetailsRepositoryReactiveAuthenticationManager} keeps whatever it
 * returns as {@code Authentication.getPrincipal()} — so the id travels from the one place that
 * already loaded it to the one place that needs it, and no mint path can be reached without it.
 *
 * <p>It extends Spring's own {@code User} rather than replacing it because everything downstream —
 * {@link SecurityUtils#getCurrentUserLogin()} included — matches on {@link UserDetails} and reads
 * {@code getUsername()}. A principal that is not a {@code UserDetails} would resolve to null there
 * and take the whole audit trail with it.
 *
 * <p>{@link #getUsername()} is the login in every case, including when the person signed in with an
 * email address — {@link DomainUserDetailsService} accepts either as a sign-in identifier but always
 * builds the principal from {@code User.getLogin()}. So {@code sub} is a login and nothing else. It
 * is still the identifier that moves when a login is edited in user management, which is what
 * {@link #getUid()} exists to sit beside. See {@code backlog.md} item 48.
 */
public class AccountUserDetails extends org.springframework.security.core.userdetails.User {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String uid;

    public AccountUserDetails(String username, String password, String uid, Collection<? extends GrantedAuthority> authorities) {
        super(username, password, authorities);
        this.uid = uid;
    }

    /**
     * The gateway's {@code User.id} for this account — a Mongo {@code _id}, stable across a login
     * being edited in user management.
     *
     * <p>Nullable in principle: nothing in the {@code User} document forbids it, and a token minted
     * for an account without one carries no {@code uid} claim rather than a null one. Callers must
     * treat absence as "not known", never as a value.
     */
    public String getUid() {
        return uid;
    }
}
