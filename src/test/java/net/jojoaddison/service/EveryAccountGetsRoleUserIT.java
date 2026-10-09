package net.jojoaddison.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.config.dbmigrations.InitialSetupMigration;
import net.jojoaddison.domain.Authority;
import net.jojoaddison.domain.User;
import net.jojoaddison.repository.UserRepository;
import net.jojoaddison.security.AuthoritiesConstants;
import net.jojoaddison.service.dto.AdminUserDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * ⛔ <b>Every account-creation path appends {@code ROLE_USER}</b> — {@code profile.md} § "User
 * (account)": <i>"Always append {@code ROLE_USER} by default in the gateway."</i> (F7, F-A)
 *
 * <h2>There are three paths and the first fix found two of them</h2>
 *
 * <p>{@link UserService#registerUser} — self-service registration — always has.
 * {@link UserService#createUser} — the administrator's invitation path behind
 * {@code POST /api/admin/users} — appended nothing until F7, so an account created by invitation held
 * only the authorities the administrator happened to type, and an invitation naming none produced an
 * account with an <b>empty</b> authority set. <b>{@code InitialSetupMigration.createProfessional}
 * was the third and was missed</b>, leaving all eight seeded demo clinicians holding exactly one
 * authority on every {@code dev}, {@code test} and {@code quality} stack (F-A).
 *
 * <h2>⚠ Why the F7 sweep missed the third, and what this class does about it</h2>
 *
 * <p>That sweep recorded: <i>"{@code AuthoritiesConstants.USER} had exactly two occurrences in
 * {@code gateway/src/main} and neither was in {@code createUser}"</i>. The measurement was accurate.
 * <b>One of those two occurrences is in {@code InitialSetupMigration} itself</b>, at
 * {@code createUserAuthority()} — which creates the {@code Authority} <em>row</em> and grants nothing
 * to anybody. The sweep opened the right file and read the one line in it that looks like a grant and
 * is not.
 *
 * <p>The previous version of this javadoc then wrote the limitation down and left it there: <i>"'every
 * creation path' is a claim no per-path test can make … it cannot be enforced by reflection … so the
 * guard is the name and the javadoc"</i>. <b>A guard that is a javadoc is not a guard</b>, and it was
 * already being read by somebody who had just added a third path in another file. So the coverage is
 * now <b>derived twice over</b>, and neither derivation is a list of logins or of methods:
 *
 * <ul>
 *   <li>{@link #everyUserConstructionSiteInMainIsAccountedFor} <b>enumerates the creation sites from
 *       the source tree</b> — every {@code new User(} in {@code src/main}, resolved to the method
 *       that holds it — and fails naming a site this class has not decided about. A fourth path
 *       cannot be added silently, in this file or any other.</li>
 *   <li>{@link #everySeededAccountHoldsRoleUser} <b>runs the seeder and asserts the invariant over
 *       whatever it produced</b>, rather than over logins written out here. A ninth discipline, or a
 *       tenth seeded account, is covered by the assertion on the day it is added.</li>
 * </ul>
 *
 * <p>⚠ Neither derivation can tell whether a {@code new User(} is <em>persisted</em> — that is the
 * judgement the enumeration forces a human to record, not one it makes.
 */
@IntegrationTest
class EveryAccountGetsRoleUserIT {

    /**
     * Every site in {@code src/main} that constructs a {@link User}, as {@code Class#method}, with
     * what was decided about each. ⛔ <b>Do not add a row to make the test pass</b> — add the row
     * because the site either appends {@code ROLE_USER} or demonstrably persists nothing.
     *
     * <table>
     *   <caption>The decision per site</caption>
     *   <tr><th>site</th><th>persists?</th><th>{@code ROLE_USER}</th></tr>
     *   <tr><td>{@code InitialSetupMigration#createUser}</td><td>yes</td>
     *       <td>granted; it is the only authority the {@code user} demo account holds</td></tr>
     *   <tr><td>{@code InitialSetupMigration#createAdmin}</td><td>yes</td>
     *       <td>granted beside {@code ROLE_ADMIN}</td></tr>
     *   <tr><td>{@code InitialSetupMigration#createProfessional}</td><td>yes</td>
     *       <td>granted beside the discipline — <b>F-A</b></td></tr>
     *   <tr><td>{@code UserService#registerUser}</td><td>yes</td>
     *       <td>granted, and it is the whole of the set</td></tr>
     *   <tr><td>{@code UserService#createUser}</td><td>yes</td>
     *       <td>granted beside whatever the invitation asked for — F7</td></tr>
     *   <tr><td>{@code UserMapper#userDTOToUser}</td><td><b>no</b></td>
     *       <td>n/a — a DTO-to-entity mapping whose result no caller saves; it exists for
     *           {@code userDTOsToUsers}, and an authority set invented here would overwrite the
     *           stored grants of whatever it was mapped onto</td></tr>
     *   <tr><td>{@code UserMapper#userFromId}</td><td><b>no</b></td>
     *       <td>n/a — an id-only stub for a relation reference; it sets no other field</td></tr>
     * </table>
     */
    private static final Set<String> DECIDED_USER_CONSTRUCTION_SITES = Set.of(
        "InitialSetupMigration#createUser",
        "InitialSetupMigration#createAdmin",
        "InitialSetupMigration#createProfessional",
        "UserService#registerUser",
        "UserService#createUser",
        "UserMapper#userDTOToUser",
        "UserMapper#userFromId"
    );

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private InitialSetupMigration initialSetupMigration;

    @BeforeEach
    @AfterEach
    void clear() {
        userRepository.deleteAll().block();
    }

    // -------------------------------------------------------------------------------------------------
    // The two derived guards. Neither names a login or a method it was written against.
    // -------------------------------------------------------------------------------------------------

    /**
     * ⛔ <b>Enumerates the creation sites from {@code src/main} and fails on one nobody has decided
     * about.</b>
     *
     * <p>This is what replaces the previous javadoc's <i>"the guard is the name and the javadoc"</i>.
     * It cannot tell whether a site persists — that judgement is recorded in
     * {@link #DECIDED_USER_CONSTRUCTION_SITES}'s table — but it can refuse to let a site exist
     * undecided, which is the half that failed.
     *
     * <p>Comments are stripped before the scan: a javadoc that <em>mentions</em>
     * {@code new User(} — this class's own does — is prose about a creation site and not one.
     */
    @Test
    void everyUserConstructionSiteInMainIsAccountedFor() throws Exception {
        Set<String> found = userConstructionSites();

        assertThat(found)
            .as(
                "Every `new User(` in gateway/src/main must be a site this class has decided about — " +
                "see DECIDED_USER_CONSTRUCTION_SITES. A new one either appends ROLE_USER " +
                "(profile.md: \"Always append ROLE_USER by default in the gateway\") or demonstrably " +
                "persists nothing. Grep for the GRANT (getAuthorities().add / setAuthorities), never " +
                "for AuthoritiesConstants.USER — that is how F-A survived F7."
            )
            .isEqualTo(DECIDED_USER_CONSTRUCTION_SITES);
    }

    /**
     * ⭐ <b>Runs the seeder and asserts the rule over every account it produced</b> — rather than over
     * {@code doctor}, {@code nurse} and the six beside them written out as cases.
     *
     * <p>This is the assertion F-A would have failed, and it fails for a ninth discipline added later
     * without anybody editing it. {@code InitialSetupMigration} is an {@link org.springframework.boot.ApplicationRunner},
     * which {@code @SpringBootTest} does not invoke — it is {@code SpringApplication.run} that calls
     * them — so it is injected and called directly, which is the shape the migration ITs in
     * {@code api/} use for the same reason.
     *
     * <p>The seeder is idempotent by {@code saveUserIfMissing}, so calling it on the emptied
     * collection of {@link #clear()} seeds the full set: {@code admin}, {@code user} and one
     * clinician per discipline. The demo accounts are seeded because the test profile is
     * {@code testdev} rather than {@code prod} — see {@code seedDemoAccounts}.
     */
    @Test
    void everySeededAccountHoldsRoleUser() {
        initialSetupMigration.run(null);

        List<User> seeded = userRepository.findAll().collectList().block();
        assertThat(seeded).as("the seeder created accounts to assert over").isNotEmpty();
        assertThat(seeded)
            .as("profile.md: \"Always append ROLE_USER by default in the gateway\" — every seeded account, derived not listed")
            .allSatisfy(
                user ->
                    assertThat(authoritiesOf(user.getLogin()))
                        .as("authorities of the seeded account %s", user.getLogin())
                        .contains(AuthoritiesConstants.USER)
            );
    }

    /**
     * Every {@code new User(} in {@code src/main}, as {@code SimpleClassName#enclosingMethod}.
     *
     * <p>The enclosing method is the nearest preceding member declaration at class-body indentation,
     * which holds because Prettier formats this repository and every member sits at four spaces. A
     * construction inside a lambda — {@code UserService.registerUser} has one — resolves to the
     * method that holds the lambda, which is the site a reader cares about.
     *
     * <p>⚠ <b>The leading modifier is required rather than optional, and that is not cosmetic.</b>
     * The character class admits spaces so that a generic return type survives it, which made an
     * optional modifier match {@code if (…) {} } four spaces deeper and resolve
     * {@code UserMapper#userDTOToUser} to {@code UserMapper#if}. Requiring one keyword is what
     * distinguishes a declaration from a statement here. A member declared with no modifier at all
     * resolves to {@code <unknown>} and fails this test by name, which is the right way round: the
     * site is reported rather than silently attributed to its neighbour.
     */
    private static Set<String> userConstructionSites() throws Exception {
        Pattern member = Pattern.compile(
            "(?m)^ {4}(?:public|protected|private|static|final|abstract|synchronized)\\s+[\\w$.<>\\[\\], ?]+\\s+(\\w+)\\s*\\("
        );
        Set<String> sites = new java.util.TreeSet<>();
        try (Stream<Path> tree = Files.walk(Path.of("src", "main", "java"))) {
            for (Path file : tree.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = stripComments(Files.readString(file));
                String className = file.getFileName().toString().replace(".java", "");
                int at = source.indexOf("new User(");
                while (at >= 0) {
                    Matcher matcher = member.matcher(source.substring(0, at));
                    String enclosing = null;
                    while (matcher.find()) {
                        enclosing = matcher.group(1);
                    }
                    sites.add(className + "#" + (enclosing == null ? "<unknown>" : enclosing));
                    at = source.indexOf("new User(", at + 1);
                }
            }
        }
        return sites;
    }

    /** Block and line comments blanked, so prose naming a construction is not counted as one. */
    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
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
