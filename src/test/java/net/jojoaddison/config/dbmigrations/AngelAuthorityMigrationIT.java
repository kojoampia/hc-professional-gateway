package net.jojoaddison.config.dbmigrations;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.Authority;
import net.jojoaddison.domain.User;
import net.jojoaddison.security.AuthoritiesConstants;
import net.jojoaddison.security.jwt.TokenProvider;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;

/**
 * {@link AngelAuthorityMigration} — the item 63 retirement of {@code ROLE_ANGEL} from a database that
 * predates item 44.
 *
 * <p>The runner is constructed and called directly rather than being left to fire at context
 * startup, for {@code AngelDutyRoleMigrationIT}'s reason in {@code api/}: a startup invocation runs
 * once, before any fixture exists, so it can only ever be observed doing nothing. Calling it is also
 * the only way to exercise the second and third runs that idempotency is about.
 *
 * <p>The fixtures are written through the blocking {@link MongoTemplate}, as
 * {@code InitialSetupMigrationIT} writes its own and as the migration reads them. {@code ROLE_ANGEL}
 * has no constant left to name it after item 44, so it is spelled as a literal here exactly as a
 * stored document holds it.
 */
@IntegrationTest
class AngelAuthorityMigrationIT {

    private static final String RETIRED = "ROLE_ANGEL";

    private static final String ANGEL_ONLY = "item63-angel-only";
    private static final String ANGEL_AND_NURSE = "item63-angel-and-nurse";
    private static final String NURSE_ONLY = "item63-nurse-only";
    private static final String NURSE_AND_USER = "item63-nurse-and-user";

    @Autowired
    private MongoTemplate template;

    @Autowired
    private TokenProvider tokenProvider;

    @Autowired
    private ReactiveJwtAuthenticationConverter jwtAuthenticationConverter;

    @Autowired
    @Qualifier("userDetailsService")
    private ReactiveUserDetailsService userDetailsService;

    private AngelAuthorityMigration migration;

    @BeforeEach
    void setUp() {
        removeFixtures();
        migration = new AngelAuthorityMigration(template);
        // The surviving authorities the positive control is about. Saved rather than assumed present:
        // this context is shared, and DomainUserDetailsServiceIT and AuthorityResourceIT both empty
        // collections the seeder filled.
        template.save(authority(AuthoritiesConstants.NURSE));
        template.save(authority(AuthoritiesConstants.USER));
    }

    @AfterEach
    void tearDown() {
        removeFixtures();
    }

    private void removeFixtures() {
        template.remove(Query.query(Criteria.where("login").in(ANGEL_ONLY, ANGEL_AND_NURSE, NURSE_ONLY, NURSE_AND_USER)), User.class);
        template.remove(Query.query(Criteria.where("_id").is(RETIRED)), Authority.class);
    }

    private Authority authority(String name) {
        Authority authority = new Authority();
        authority.setName(name);
        return authority;
    }

    /** An account exactly as a pre-item-44 database holds one: activated, with a real password hash. */
    private User saveUser(String login, String... authorities) {
        User user = new User();
        user.setId(login);
        user.setLogin(login);
        // Exactly 60 characters: User.password carries @Size(min = 60, max = 60) for a bcrypt hash,
        // and MongoTemplate validates on save.
        user.setPassword(RandomStringUtils.randomAlphanumeric(60));
        user.setEmail(login + "@example.com");
        user.setActivated(true);
        user.setLangKey("en");
        for (String name : authorities) {
            user.getAuthorities().add(authority(name));
        }
        return template.save(user);
    }

    private Set<String> authorityNamesOf(String login) {
        User user = template.findOne(Query.query(Criteria.where("login").is(login)), User.class);
        assertThat(user).as("the migration must never delete an account").isNotNull();
        return user.getAuthorities().stream().map(Authority::getName).collect(java.util.stream.Collectors.toSet());
    }

    /**
     * The case the item was opened for: the seeded {@code angel} account holds {@code ROLE_ANGEL} and
     * <em>nothing else</em> — there is no {@code ROLE_USER} beside it, because
     * {@code InitialSetupMigration.createProfessional} only ever added the one discipline. So
     * revoking does not leave a normal user; it leaves an account with an empty authority set, which
     * is the end state {@code theStrippedAccountStillSignsInAndItsTokenGrantsNothing} below is about.
     */
    @Test
    void revokesTheGrantAndKeepsTheAccount() {
        template.save(authority(RETIRED));
        saveUser(ANGEL_ONLY, RETIRED);

        migration.run(null);

        assertThat(authorityNamesOf(ANGEL_ONLY)).as("the retired authority is revoked, not the account deleted").isEmpty();

        User after = template.findOne(Query.query(Criteria.where("login").is(ANGEL_ONLY)), User.class);
        assertThat(after.isActivated())
            .as("deactivating would say 'has not confirmed their email', which is false, and would lock the person out silently")
            .isTrue();
    }

    /**
     * The {@code jhi_authority} row, which is the half that is not merely residue.
     *
     * <p>{@code UserService.createUser} and {@code updateUser} resolve requested authority names
     * through {@code AuthorityRepository.findById}, so while this document exists an administrator can
     * grant the retired authority to a brand-new account through {@code POST /api/admin/users}.
     * Deleting it is what makes the name resolve to nothing and drop silently out of the set.
     */
    @Test
    void deletesTheAuthorityDocumentSoItCannotBeGrantedAgain() {
        template.save(authority(RETIRED));

        migration.run(null);

        assertThat(template.findById(RETIRED, Authority.class))
            .as("ROLE_ANGEL is hc-patient's authority and must not be assignable here")
            .isNull();
    }

    /**
     * <b>The pure positive control for the user array: it asserts nothing whatever about the angel.</b>
     *
     * <p>Every other case here says "the angel is gone" <em>as well as</em> some invariant, so all of
     * them go red both when the migration does nothing and when it takes too much. That is a
     * diagnosability gap rather than a detection gap, and it would not be worth a test except that
     * <b>this migration deletes from user documents and is unrecoverable</b>: when it does fire, the
     * difference between "did nothing" and "emptied an account" is the difference between a no-op and
     * an incident, and it should not be the same two tests going red for both. This one is green
     * whenever the migration is correct <em>or</em> inert, and red only when the pull takes more than
     * the retired grant.
     *
     * <p><b>Two accounts, and which mutation each one catches was measured rather than reasoned.</b>
     * The <em>holder</em> — selected by the query either way — is what catches an update that empties
     * the array instead of pulling one element from it; that mutation leaves a non-holder untouched,
     * because an over-broad update still only reaches the documents the query selected. The
     * <em>non-holder</em> catches the wholesale case, where the query widens to every account
     * <em>and</em> the update empties, and it is the assertion that fires first there, naming an
     * account that was never in scope at all — which is a clearer diagnosis of a wipe than a holder
     * losing a grant. Drop either row and one of the two goes unobserved by this control.
     *
     * <p><b>A widened query alone is not caught here, and correctly is not</b>, which is worth knowing
     * before someone "strengthens" this. Running the same {@code $pull} over every account removes
     * nothing from an account that has no such element, so it is behaviour-preserving: the safety
     * boundary is the pull condition, not the query, and the query is an optimisation and a statement
     * of intent. A test failing on that mutation would be asserting the implementation rather than
     * the effect.
     */
    @Test
    void neverRemovesAnAuthorityThatIsNotTheRetiredOne() {
        template.save(authority(RETIRED));
        // The non-holder: catches a widened scope.
        saveUser(NURSE_AND_USER, AuthoritiesConstants.NURSE, AuthoritiesConstants.USER);
        // The holder: catches a widened update. Nothing below asks whether its angel survived.
        saveUser(ANGEL_AND_NURSE, RETIRED, AuthoritiesConstants.NURSE, AuthoritiesConstants.USER);

        migration.run(null);

        assertThat(authorityNamesOf(NURSE_AND_USER))
            .as("an account that never held the retired authority must not be written at all")
            .contains(AuthoritiesConstants.NURSE, AuthoritiesConstants.USER);
        assertThat(authorityNamesOf(ANGEL_AND_NURSE))
            .as("a holder keeps every grant except the one being retired")
            .contains(AuthoritiesConstants.NURSE, AuthoritiesConstants.USER);
    }

    /**
     * The positive control, and without it "the angel is gone" is indistinguishable from "the
     * migration emptied the collections". It is deliberately two statements: a mixed account keeps
     * every other grant, and an account that never held the retired authority is not written at all.
     *
     * <p>Unlike {@link #neverRemovesAnAuthorityThatIsNotTheRetiredOne} above, this one <em>does</em>
     * assert the angel is gone, so it goes red under a neutered migration too. Both are wanted: this
     * says the change happened, that one says nothing else did.
     */
    @Test
    void leavesEveryOtherAuthorityAndItsHoldersAlone() {
        template.save(authority(RETIRED));
        saveUser(ANGEL_AND_NURSE, RETIRED, AuthoritiesConstants.NURSE, AuthoritiesConstants.USER);
        saveUser(NURSE_ONLY, AuthoritiesConstants.NURSE);

        migration.run(null);

        assertThat(authorityNamesOf(ANGEL_AND_NURSE))
            .as("this narrows an account, it does not empty one")
            .containsExactlyInAnyOrder(AuthoritiesConstants.NURSE, AuthoritiesConstants.USER);
        assertThat(authorityNamesOf(NURSE_ONLY)).containsExactly(AuthoritiesConstants.NURSE);

        assertThat(template.findById(AuthoritiesConstants.NURSE, Authority.class))
            .as("a surviving discipline is a cross-repo invariant and this must not touch it")
            .isNotNull();
        assertThat(template.findById(AuthoritiesConstants.USER, Authority.class)).isNotNull();
    }

    /**
     * The property the design rests on: both operations match the retired name only, so there is no
     * marker document and no "has this run" flag to keep in step. A deployment restarts as often as
     * it likes.
     */
    @Test
    void isIdempotent() {
        template.save(authority(RETIRED));
        saveUser(ANGEL_AND_NURSE, RETIRED, AuthoritiesConstants.NURSE);

        migration.run(null);
        migration.run(null);
        migration.run(null);

        assertThat(authorityNamesOf(ANGEL_AND_NURSE)).containsExactly(AuthoritiesConstants.NURSE);
        assertThat(template.findById(RETIRED, Authority.class)).isNull();
    }

    /**
     * Nothing to do is not an error, and this is the case production is in — <b>0</b> accounts held
     * the authority on the deployed Mongo when item 44 shipped.
     */
    @Test
    void isASilentNoOpOnADatabaseThatHasNone() {
        saveUser(NURSE_ONLY, AuthoritiesConstants.NURSE);
        List<Authority> before = template.findAll(Authority.class);

        migration.run(null);

        assertThat(authorityNamesOf(NURSE_ONLY)).containsExactly(AuthoritiesConstants.NURSE);
        assertThat(template.findAll(Authority.class))
            .as("a database with no angel must come out of this byte for byte as it went in")
            .containsExactlyInAnyOrderElementsOf(before);
    }

    /**
     * <b>What revoking actually leaves behind</b>, established rather than assumed — an account with
     * zero authorities could plausibly be worse than one holding a role nothing honours.
     *
     * <p>It is not. The account still authenticates ({@code DomainUserDetailsService} refuses on
     * {@code activated}, never on an empty authority set), the minted token carries an empty
     * {@code auth} claim, and the resource-server converter maps that to <em>no</em> authorities
     * rather than to one blank one — so the caller reaches the same {@code .authenticated()} islands
     * it reached holding {@code ROLE_ANGEL}, and nothing else. Strictly narrowing, and observably
     * nothing.
     */
    @Test
    void theStrippedAccountStillSignsInAndItsTokenGrantsNothing() {
        template.save(authority(RETIRED));
        saveUser(ANGEL_ONLY, RETIRED);

        migration.run(null);

        UserDetails principal = userDetailsService.findByUsername(ANGEL_ONLY).block();
        assertThat(principal).as("an authority set is not a credential; an emptied account still signs in").isNotNull();
        assertThat(principal.getAuthorities()).isEmpty();

        String claim = tokenProvider.authorityString(principal.getAuthorities());
        assertThat(claim).isEmpty();

        Jwt token = Jwt.withTokenValue("irrelevant").header("alg", "HS512").subject(ANGEL_ONLY).claim("auth", claim).build();
        assertThat(jwtAuthenticationConverter.convert(token).block().getAuthorities())
            .as("an empty auth claim must convert to no authorities, not to one blank one")
            .isEmpty();
    }
}
