package net.jojoaddison.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.Authority;
import net.jojoaddison.domain.User;
import net.jojoaddison.repository.UserRepository;
import net.jojoaddison.security.AuthoritiesConstants;
import net.jojoaddison.service.dto.AdminUserDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * ⛔ <b>Every account-creation path appends {@code ROLE_USER}</b> — {@code profile.md} § "User
 * (account)": <i>"Always append {@code ROLE_USER} by default in the gateway."</i> (F7)
 *
 * <h2>There are two paths and only one of them did it</h2>
 *
 * <p>{@link UserService#registerUser} — self-service registration — always has.
 * {@link UserService#createUser} — the administrator's invitation path behind
 * {@code POST /api/admin/users} — appended nothing, so an account created by invitation held only
 * the authorities the administrator happened to type, and an invitation naming none produced an
 * account with an <b>empty</b> authority set. Measured before the fix:
 * {@code AuthoritiesConstants.USER} had exactly two occurrences in {@code gateway/src/main} and
 * neither was in {@code createUser}.
 *
 * <p><b>Both paths in one class on purpose.</b> The requirement is about <em>every</em> creation
 * path, and that is a claim no per-path test can make: a third path added later is caught by a
 * reader of this class, which is named for the rule rather than for a method. ⚠ It cannot be
 * enforced by reflection — "is this method a creation path" is not a property anything can read —
 * so the guard is the name and the javadoc, and a new creator wants a case here.
 */
@IntegrationTest
class EveryAccountGetsRoleUserIT {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserService userService;

    @BeforeEach
    void clear() {
        userRepository.deleteAll().block();
    }

    private static AdminUserDTO invitation(String login, Set<String> authorities) {
        AdminUserDTO userDTO = new AdminUserDTO();
        userDTO.setLogin(login);
        userDTO.setEmail(login + "@example.com");
        userDTO.setFirstName("Ama");
        userDTO.setLastName("Boateng");
        userDTO.setAuthorities(authorities);
        return userDTO;
    }

    private Set<String> authoritiesOf(String login) {
        User stored = userRepository.findOneByLogin(login).block();
        assertThat(stored).as("the account was created").isNotNull();
        return stored.getAuthorities().stream().map(Authority::getName).collect(java.util.stream.Collectors.toSet());
    }

    // -------------------------------------------------------------------------------------------------
    // The invitation path — the one that was wrong
    // -------------------------------------------------------------------------------------------------

    /**
     * ⛔ <b>The case that was red before F7.</b> An invitation naming no authority produced an
     * account with an empty set, which is not an applicant: every {@code .authenticated()} rule
     * serves a role-less caller, but {@code ROLE_USER} is what the surfaces positively name.
     */
    @Test
    void anInvitationNamingNoAuthorityStillGetsRoleUser() {
        userService.createUser(invitation("invite-none", Set.of())).block();

        assertThat(authoritiesOf("invite-none")).containsExactly(AuthoritiesConstants.USER);
    }

    /** A null authority set is the same request spelled differently, and takes the same answer. */
    @Test
    void anInvitationWithANullAuthoritySetStillGetsRoleUser() {
        userService.createUser(invitation("invite-null", null)).block();

        assertThat(authoritiesOf("invite-null")).containsExactly(AuthoritiesConstants.USER);
    }

    /**
     * ⭐ <b>"Append", not "replace"</b> — an invitation that grants a clinical authority produces
     * both. This is the half a careless fix breaks: setting the authority set to {@code ROLE_USER}
     * would satisfy the two cases above and silently strip every invited clinician of their role.
     */
    @Test
    void anInvitationGrantingAClinicalAuthorityGetsBoth() {
        userService.createUser(invitation("invite-nurse", Set.of(AuthoritiesConstants.NURSE))).block();

        assertThat(authoritiesOf("invite-nurse")).containsExactlyInAnyOrder(AuthoritiesConstants.NURSE, AuthoritiesConstants.USER);
    }

    /** Several at once, with the default added beside them rather than instead of any. */
    @Test
    void anInvitationGrantingSeveralAuthoritiesKeepsThemAll() {
        userService.createUser(invitation("invite-many", Set.of(AuthoritiesConstants.ADMIN, AuthoritiesConstants.DOCTOR))).block();

        assertThat(authoritiesOf("invite-many")).containsExactlyInAnyOrder(
            AuthoritiesConstants.ADMIN,
            AuthoritiesConstants.DOCTOR,
            AuthoritiesConstants.USER
        );
    }

    /** Naming {@code ROLE_USER} explicitly cannot produce it twice; the set is keyed on the name. */
    @Test
    void anInvitationThatNamesRoleUserItselfGetsItOnce() {
        userService.createUser(invitation("invite-explicit", Set.of(AuthoritiesConstants.USER))).block();

        assertThat(authoritiesOf("invite-explicit")).containsExactly(AuthoritiesConstants.USER);
    }

    // -------------------------------------------------------------------------------------------------
    // The self-service path — which always did it, and is asserted so the rule has both halves here
    // -------------------------------------------------------------------------------------------------

    /**
     * {@link UserService#registerUser} appends it too, and has since the generator wrote it.
     *
     * <p>Asserted beside the invitation cases rather than trusted: the rule is about every path, and
     * a class holding only the path that was broken would let the other one regress unremarked.
     */
    @Test
    void aSelfServiceRegistrationGetsRoleUser() {
        AdminUserDTO registration = invitation("register-self", Set.of(AuthoritiesConstants.ADMIN));
        registration.setLangKey("en");

        userService.registerUser(registration, "the-password").block();

        assertThat(authoritiesOf("register-self"))
            .as("registration ignores a requested authority entirely — ROLE_USER is the whole of it")
            .containsExactly(AuthoritiesConstants.USER);
    }
}
