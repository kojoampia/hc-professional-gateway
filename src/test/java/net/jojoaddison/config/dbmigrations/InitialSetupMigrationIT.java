package net.jojoaddison.config.dbmigrations;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.User;
import net.jojoaddison.security.AuthoritiesConstants;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;
import tech.jhipster.config.JHipsterConstants;

/**
 * The seeder runs on every start, so what matters is not that it seeds — the rest of the suite
 * depends on that already — but that a second run leaves existing accounts alone.
 * <p>
 * It used to drop the Authority and User collections from its own constructor. Every restart
 * therefore deleted every account: a rotated administrator password reverted to the seeded default,
 * and a clinician who had registered lost their credentials while their onboarding application
 * survived in professionalService, orphaned. These tests exist so that cannot come back quietly.
 */
@IntegrationTest
class InitialSetupMigrationIT {

    @Autowired
    private InitialSetupMigration migration;

    @Autowired
    private MongoTemplate template;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void rerunningTheMigrationKeepsAChangedAdminPassword() {
        // Stand in for an administrator who rotated their password after the first boot.
        User admin = template.findOne(Query.query(Criteria.where("login").is("admin")), User.class);
        assertThat(admin).as("the seeder should have created an admin on context startup").isNotNull();
        String rotated = passwordEncoder.encode("a-password-nobody-can-derive");
        admin.setPassword(rotated);
        template.save(admin);

        migration.run(null);

        User after = template.findOne(Query.query(Criteria.where("login").is("admin")), User.class);
        assertThat(after).isNotNull();
        assertThat(after.getPassword())
            .as("a restart must not reset a password that was rotated after the account was created")
            .isEqualTo(rotated);
    }

    @Test
    void rerunningTheMigrationKeepsAccountsItDidNotCreate() {
        // Stand in for a clinician who registered through the portal.
        User registered = new User();
        registered.setId("registered-clinician");
        registered.setLogin("registered.clinician");
        registered.setPassword(passwordEncoder.encode("irrelevant"));
        registered.setEmail("registered.clinician@example.com");
        registered.setActivated(true);
        registered.setLangKey("en");
        template.save(registered);

        migration.run(null);

        assertThat(template.exists(Query.query(Criteria.where("login").is("registered.clinician")), User.class))
            .as("a restart must not delete accounts created through registration")
            .isTrue();

        template.remove(registered);
    }

    @Test
    void rerunningTheMigrationDoesNotDuplicateSeededAccounts() {
        migration.run(null);
        migration.run(null);

        long admins = template.count(Query.query(Criteria.where("login").is("admin")), User.class);
        assertThat(admins).as("seeding must be idempotent, not additive").isEqualTo(1);
    }

    @Test
    void everyClinicalAuthorityIsSeeded() {
        // The nine clinical roles are a cross-repo invariant; the seeder is where the gateway half
        // of it is established.
        assertThat(template.findAll(net.jojoaddison.domain.Authority.class).stream().map(a -> a.getName())).contains(
            AuthoritiesConstants.ADMIN,
            AuthoritiesConstants.USER,
            AuthoritiesConstants.DOCTOR,
            AuthoritiesConstants.NURSE,
            AuthoritiesConstants.PARAMEDIC,
            AuthoritiesConstants.PHARMACIST,
            AuthoritiesConstants.THERAPIST,
            AuthoritiesConstants.CARER,
            AuthoritiesConstants.ANGEL,
            AuthoritiesConstants.CHEMIST,
            AuthoritiesConstants.TECHNICIAN
        );
    }

    @Test
    void anAuthorityIsRestoredEvenWhenItsDemoAccountAlreadyExists() {
        // Authority seeding used to be nested inside the arguments to saveUserIfMissing. Now that
        // user seeding is lazy, that nesting would have meant a role stopped being ensured the
        // moment its demo user existed — which is exactly the state every deployment is in after
        // its first boot. Deleting the authority and leaving the user in place reproduces it.
        template.remove(Query.query(Criteria.where("_id").is(AuthoritiesConstants.DOCTOR)), net.jojoaddison.domain.Authority.class);
        assertThat(template.findById(AuthoritiesConstants.DOCTOR, net.jojoaddison.domain.Authority.class)).isNull();
        assertThat(template.exists(Query.query(Criteria.where("login").is("doctor")), User.class))
            .as("the demo account must still be present for this to test what it claims")
            .isTrue();

        migration.run(null);

        assertThat(template.findById(AuthoritiesConstants.DOCTOR, net.jojoaddison.domain.Authority.class))
            .as("a clinical authority must be re-created even when its demo account already exists")
            .isNotNull();
    }

    @Test
    void underProdTheDemoAccountsAreNotSeededButTheAdministratorIs() {
        // Built by hand against a prod Environment rather than by changing the context's profile,
        // so this asserts the gate itself without disturbing the rest of the suite — which runs
        // under `testdev` and legitimately expects the demo accounts to exist.
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles(JHipsterConstants.SPRING_PROFILE_PRODUCTION);
        InitialSetupMigration prodMigration = new InitialSetupMigration(template, passwordEncoder, "", prod);

        for (String login : DEMO_LOGINS) {
            template.remove(Query.query(Criteria.where("login").is(login)), User.class);
        }
        template.remove(Query.query(Criteria.where("login").is("admin")), User.class);

        prodMigration.run(null);

        for (String login : DEMO_LOGINS) {
            assertThat(template.exists(Query.query(Criteria.where("login").is(login)), User.class))
                .as("%s has a password derived from its login and must not be seeded in production", login)
                .isFalse();
        }
        assertThat(template.exists(Query.query(Criteria.where("login").is("admin")), User.class))
            .as("the administrator is the only way into an empty production database, so it must still be seeded")
            .isTrue();

        // Put the suite's expected state back: this class shares a context with the others.
        migration.run(null);
        for (String login : DEMO_LOGINS) {
            assertThat(template.exists(Query.query(Criteria.where("login").is(login)), User.class)).isTrue();
        }
    }

    private static final List<String> DEMO_LOGINS = List.of(
        "user",
        "doctor",
        "nurse",
        "angel",
        "carer",
        "paramedic",
        "pharmacist",
        "therapist",
        "chemist",
        "technician"
    );
}
