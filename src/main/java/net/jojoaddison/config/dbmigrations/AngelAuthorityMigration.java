package net.jojoaddison.config.dbmigrations;

import com.mongodb.client.result.UpdateResult;
import net.jojoaddison.domain.Authority;
import net.jojoaddison.domain.User;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Retires the {@code ROLE_ANGEL} authority from a database that predates item 44 — it strips the
 * grant from every user document that still carries it and deletes the {@code jhi_authority} row
 * (docs/backlog.md item 63).
 *
 * <p>Item 44 removed the care angel from this subsystem on 2026-09-08: an angel supports one named
 * patient, hc-patient owns the authority and the whole surface for it, and nothing here names the
 * concept any more. It shipped {@code api/}'s {@code AngelDutyRoleMigration} for the roster rows the
 * retired {@code DutyRole} left behind, and <b>no gateway counterpart</b>. {@link InitialSetupMigration}
 * only ever creates — "already exists, left unchanged" — so on a volume that predates the roll the
 * authority document and an activated {@code angel} account holding it both survived, and the account
 * went on signing in with {@code "auth": "ROLE_ANGEL"} in its token.
 *
 * <h2>This is a tidy-up, not a security fix, and saying so plainly matters</h2>
 *
 * <p>The surviving grant reaches nothing, on either side of the shared signing key, and both halves
 * of that were established before this class was written rather than hoped for. Here, every
 * {@code /services/**} rule is a <em>positive</em> list and {@code ROLE_ANGEL} has been in none of
 * them since item 30, so such a caller is exactly a role-less applicant: the three
 * {@code .authenticated()} islands and nothing else — see {@code ServicesRouteAuthorizationIT}. At
 * hc-patient, which keeps the authority, {@code PatientScope} says it in its own words: <i>"{@code
 * ROLE_ANGEL} grants nothing. An {@code ACTIVE CareDelegation} grants everything"</i>, re-read per
 * request, so a token bearing the role without a delegation behind it opens no record there either.
 *
 * <h2>Why a boot-time runner does this at all, when item 44 decided it should not</h2>
 *
 * <p>{@code InitialSetupMigrationIT} recorded the opposite policy — <i>"revoking grants is an
 * operator's decision, not a boot-time runner's"</i> — and that rule is right about a grant an
 * operator <em>made</em>. It does not fit this one, for three reasons.
 *
 * <p><b>There is no intent left to preserve.</b> The rule protects an administrator's decision from
 * a runner that thinks it knows better; nobody can decide to keep {@code ROLE_ANGEL} on an account
 * here, because there is no meaning left for them to be choosing between. The authority names a
 * concept this product deleted.
 *
 * <p><b>The authority document is the part that is not inert.</b> The grant is residue and shrinks;
 * the {@code jhi_authority} row is a live capability. {@code UserService.createUser} and
 * {@code updateUser} resolve every requested authority name through
 * {@code AuthorityRepository.findById}, so while that row exists an administrator can hand
 * {@code ROLE_ANGEL} to a brand-new account through {@code POST /api/admin/users}, and
 * {@code GET /api/authorities} still offers it as an assignable role. Item 63 reasoned that item 59
 * had removed the fixture that creates the grant "so this cannot recur"; that is true of the fixture
 * and false of an administrator. Deleting the row is what actually makes it unrepeatable — the name
 * then resolves to nothing and drops silently out of the requested set.
 *
 * <p><b>An {@link ApplicationRunner} is the only mechanism this repo has.</b> There is no Liquibase
 * and no Mongock here. "An operator will do it" is not a competing plan but the absence of one: it
 * has to be remembered against every long-lived database and every dump restored from one, and the
 * single box known to hold the row is the quality stack, which {@code startup.sh --clean} rebuilds
 * anyway. The database this class is really for is the one nobody has looked at — the same reader
 * {@code AngelDutyRoleMigration} was written for, which deletes whole roster documents from a runner
 * on the same day, for the same retired concept. The gateway declining to strip one embedded string
 * while its sibling deletes 104 rows is an asymmetry rather than a principle.
 *
 * <h2>What it deliberately does not do</h2>
 *
 * <p><b>The account is left activated and able to sign in.</b> Stripping the grant leaves a user
 * document with an empty authority set, and that end state was checked rather than assumed: the
 * minted token carries an empty {@code auth} claim, {@code JwtGrantedAuthoritiesConverter} maps an
 * empty claim to no authorities at all rather than to one blank one, and the caller reaches the same
 * {@code .authenticated()} islands it reaches today holding {@code ROLE_ANGEL} — which is to say the
 * change is strictly narrowing and observably nothing. A zero-authority account is already a shape
 * this codebase supports; {@code AccountResourceIT} saves and signs in as one.
 *
 * <p><b>Deactivating it was rejected.</b> {@code activated: false} is not a spare flag — it means
 * "registered, has not confirmed their email", it is what {@code AccountActivated} is published
 * about, and it is a toggle in user management that an administrator could flip back with no idea
 * why it was off. Turning a confirmed account into one would state something false and lock a person
 * out of the applicant surface with no message explaining it.
 *
 * <p><b>Deleting the account was rejected outright</b>, on {@link InitialSetupMigration}'s own
 * recorded history: that class used to drop the {@code User} collection from its constructor, so
 * every restart destroyed every account and orphaned the onboarding applications in
 * {@code professionalService} that key on the login. A runner that deletes user documents by
 * authority is a smaller instance of exactly that, it is unrecoverable, and this stack cannot know
 * what such an account is — on some other database it may be a person whose real destination is
 * hc-patient, and their gateway account is the only record that they were ever here.
 *
 * <p><b>And no authority is granted in place of the one removed.</b> Adding {@code ROLE_USER} to
 * tidy the end state would invent a grant the account never had, from a boot-time runner, which is
 * the thing the paragraphs above spend their length refusing to do.
 *
 * <h2>Properties</h2>
 *
 * <p><b>Idempotent by construction.</b> Both operations match the retired name only, so a second run
 * matches nothing; there is no marker document and no "has this run" flag that could be wrong.
 *
 * <p><b>Silent on a database that has none</b>, which is production — <b>0</b> accounts held
 * {@code ROLE_ANGEL} on the deployed Mongo when item 44 shipped. If that measurement is ever found
 * to have been wrong, this class is what makes the answer the same everywhere instead of a manual
 * write somebody has to be told about.
 *
 * <p><b>No {@code @Profile} exclusion</b>, unlike {@code AngelDutyRoleMigration}. That exclusion
 * exists for {@code ShiftTypeMigration}'s reason — a rewriting migration that would fight test
 * fixtures — and there is nothing here to fight: no test and no seeder creates this authority, so in
 * a test context the class is the same no-op it is on production, and running it there is what keeps
 * the two identical. {@link InitialSetupMigration} beside it registers on no profile either.
 *
 * <p>The blocking {@link MongoTemplate} rather than the reactive one, again as
 * {@link InitialSetupMigration} does. This gateway is WebFlux, but an {@link ApplicationRunner} is
 * called once on the main thread after refresh and before the server accepts traffic, so blocking
 * there blocks nothing — whereas {@code .block()} on a reactive pipeline is the call that throws
 * once anything runs on a Reactor thread.
 */
@Component
public class AngelAuthorityMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AngelAuthorityMigration.class);

    /**
     * The retired authority, spelled as a literal.
     *
     * <p>It has to be: {@code AuthoritiesConstants.ANGEL} was deleted by item 44, and
     * {@code AuthoritiesConstantsUnitTest} reflects over that class and fails if the string comes
     * back to it. A migration naming a name the product no longer has is the normal shape of this —
     * {@code AngelDutyRoleMigration} spells {@code "ANGEL"} for the identical reason.
     */
    private static final String RETIRED = "ROLE_ANGEL";

    /** The field on {@code jhi_user} holding the embedded authority documents. */
    private static final String AUTHORITIES = "authorities";

    private final MongoTemplate template;

    public AngelAuthorityMigration(MongoTemplate template) {
        this.template = template;
    }

    @Override
    public void run(ApplicationArguments args) {
        revokeTheGrant();
        deleteTheAuthority();
    }

    /**
     * Pulls the embedded {@code ROLE_ANGEL} document out of every account that carries it.
     *
     * <p>A separate operation from the deletion below, and it has to be: the authority is
     * <em>embedded</em> in the user document rather than referenced, so removing the
     * {@code jhi_authority} row leaves each grant untouched and the token still says
     * {@code ROLE_ANGEL}. Two writes, or half a job.
     *
     * <p>An account holding other authorities keeps every one of them — this narrows accounts, it
     * does not empty them. <b>The pull condition is what guarantees that, not the query.</b> The
     * query selects holders so the write touches as few documents as it can and says what it means,
     * but running the same {@code $pull} over every account would remove nothing from an account
     * without such an element. Widening the condition is the change that would do damage; widening
     * the query is not. {@code AngelAuthorityMigrationIT} pins the distinction, mutation by mutation.
     */
    private void revokeTheGrant() {
        Query holders = Query.query(Criteria.where(AUTHORITIES + "._id").is(RETIRED));
        Update revoke = new Update().pull(AUTHORITIES, new Document("_id", RETIRED));
        UpdateResult result = template.updateMulti(holders, revoke, User.class);
        if (result.getModifiedCount() > 0) {
            log.info(
                "Revoked the retired authority {} from {} account(s) — see docs/backlog.md item 63",
                RETIRED,
                result.getModifiedCount()
            );
        } else {
            log.debug("No account holds {} — nothing to revoke", RETIRED);
        }
    }

    /**
     * Deletes the {@code jhi_authority} row, which is what stops the authority being grantable again
     * through {@code POST /api/admin/users}.
     */
    private void deleteTheAuthority() {
        long deleted = template.remove(Query.query(Criteria.where("_id").is(RETIRED)), Authority.class).getDeletedCount();
        if (deleted > 0) {
            log.info("Deleted the retired authority document {} — it is hc-patient's and is not assignable here", RETIRED);
        } else {
            log.debug("No {} authority document present — nothing to delete", RETIRED);
        }
    }
}
