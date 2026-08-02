package net.jojoaddison.config.dbmigrations;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import net.jojoaddison.config.Constants;
import net.jojoaddison.domain.Authority;
import net.jojoaddison.domain.User;
import net.jojoaddison.security.AuthoritiesConstants;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Seeds the initial database state when it is missing.
 * <p>
 * <strong>Idempotent, and it has to stay that way.</strong> This runs on every start, so anything
 * it does destructively it does to live data. It previously opened by dropping the Authority and
 * User collections from its own constructor, which meant every restart deleted every account: the
 * administrator's rotated password reverted to the seeded default, and any clinician who had
 * registered lost their credentials and authority grants while their onboarding application
 * survived in professionalService, orphaned from a user that no longer existed. That drop also made
 * the {@code saveUserIfMissing} / {@code saveAuthorityIfMissing} guards below unreachable — nothing
 * is ever missing from a collection that was just emptied.
 * <p>
 * Those guards are now the whole mechanism: seed what is absent, touch nothing that exists.
 */
@Component
public class InitialSetupMigration implements ApplicationRunner {

    private final MongoTemplate template;
    private final PasswordEncoder passwordEncoder;

    /**
     * Administrator password for a database that has none yet, from {@code GATEWAY_ADMIN_PASSWORD}.
     * <p>
     * Blank — the default — falls back to a value derived from the login, which is fine for a
     * developer and is published in this repository, so it must not be what a deployment relies on.
     * Set it in production and the first administrator is created with a real secret instead of one
     * anybody can compute from the source. It applies only when the account does not already exist;
     * changing it later does not reset a password that has since been rotated through the UI.
     */
    private final String configuredAdminPassword;

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(InitialSetupMigration.class);

    public InitialSetupMigration(
        MongoTemplate template,
        PasswordEncoder passwordEncoder,
        @Value("${gateway.admin-password:}") String configuredAdminPassword
    ) {
        this.template = template;
        this.passwordEncoder = passwordEncoder;
        this.configuredAdminPassword = configuredAdminPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Authorities first, and unconditionally. They are a cross-repo invariant (the nine clinical
        // roles), so each must exist even when the demo account that would once have introduced it
        // already does. They used to be created inside the arguments to saveUserIfMissing below;
        // now that user seeding is lazy, leaving them nested there would mean a role silently
        // stopped being ensured as soon as its demo user existed.
        Authority userAuthority = saveAuthorityIfMissing(createUserAuthority());
        Authority adminAuthority = saveAuthorityIfMissing(createAdminAuthority());
        Authority doctorAuthority = saveAuthorityIfMissing(createDoctorAuthority());
        Authority nurseAuthority = saveAuthorityIfMissing(createNurseAuthority());
        Authority angelAuthority = saveAuthorityIfMissing(createAngelAuthority());
        Authority carerAuthority = saveAuthorityIfMissing(createCarerAuthority());
        Authority paramedicAuthority = saveAuthorityIfMissing(createParamedicAuthority());
        Authority pharmacistAuthority = saveAuthorityIfMissing(createAuthority(AuthoritiesConstants.PHARMACIST));
        Authority therapistAuthority = saveAuthorityIfMissing(createAuthority(AuthoritiesConstants.THERAPIST));
        Authority chemistAuthority = saveAuthorityIfMissing(createAuthority(AuthoritiesConstants.CHEMIST));
        Authority technicianAuthority = saveAuthorityIfMissing(createAuthority(AuthoritiesConstants.TECHNICIAN));

        // Suppliers, not values: the account is only built when it is actually missing, so the log
        // lines inside these factories describe something that happened. Passing the built User
        // eagerly meant every boot announced "Creating admin ..." for an account it then left alone.
        saveUserIfMissing("user", () -> createUser(userAuthority));
        saveUserIfMissing("admin", () -> createAdmin(adminAuthority, userAuthority));
        saveUserIfMissing("doctor", () -> createProfessional(doctorAuthority, "doctor"));
        saveUserIfMissing("nurse", () -> createProfessional(nurseAuthority, "nurse"));
        saveUserIfMissing("angel", () -> createProfessional(angelAuthority, "angel"));
        saveUserIfMissing("carer", () -> createProfessional(carerAuthority, "carer"));
        saveUserIfMissing("paramedic", () -> createProfessional(paramedicAuthority, "paramedic"));
        saveUserIfMissing("pharmacist", () -> createProfessional(pharmacistAuthority, "pharmacist"));
        saveUserIfMissing("therapist", () -> createProfessional(therapistAuthority, "therapist"));
        saveUserIfMissing("chemist", () -> createProfessional(chemistAuthority, "chemist"));
        saveUserIfMissing("technician", () -> createProfessional(technicianAuthority, "technician"));
        logger.info("Initial setup migration completed successfully");
    }

    private Authority createAuthority(String authority) {
        Authority adminAuthority = new Authority();
        adminAuthority.setName(authority);
        return adminAuthority;
    }

    private Authority createAdminAuthority() {
        Authority adminAuthority = createAuthority(AuthoritiesConstants.ADMIN);
        return adminAuthority;
    }

    private Authority createUserAuthority() {
        Authority userAuthority = createAuthority(AuthoritiesConstants.USER);
        return userAuthority;
    }

    private Authority createDoctorAuthority() {
        Authority userAuthority = createAuthority(AuthoritiesConstants.DOCTOR);
        return userAuthority;
    }

    private Authority createNurseAuthority() {
        Authority userAuthority = createAuthority(AuthoritiesConstants.NURSE);
        return userAuthority;
    }

    private Authority createAngelAuthority() {
        Authority userAuthority = createAuthority(AuthoritiesConstants.ANGEL);
        return userAuthority;
    }

    private Authority createCarerAuthority() {
        Authority userAuthority = createAuthority(AuthoritiesConstants.CARER);
        return userAuthority;
    }

    private Authority createParamedicAuthority() {
        Authority userAuthority = createAuthority(AuthoritiesConstants.PARAMEDIC);
        return userAuthority;
    }

    private Authority saveAuthorityIfMissing(Authority authority) {
        Authority existingAuthority = template.findById(authority.getName(), Authority.class);
        if (existingAuthority != null) {
            return existingAuthority;
        }
        return template.save(authority);
    }

    /**
     * Seeds {@code login} only when no such account exists. The factory is not invoked otherwise, so
     * an existing account is never rebuilt, re-encoded, or announced in the log.
     */
    private void saveUserIfMissing(String login, Supplier<User> factory) {
        Query query = Query.query(Criteria.where("login").is(login));
        if (template.exists(query, User.class)) {
            logger.debug("Account {} already exists — left unchanged", login);
            return;
        }
        template.save(factory.get());
    }

    /** {@code doctor} -> {@code Doctor@12345}. Public by construction — see createAdmin. */
    private String derivedPassword(String login) {
        StringBuilder password = new StringBuilder(Character.toUpperCase(login.charAt(0)) + login.substring(1) + "@");
        for (int i = 1; i < login.length(); i++) {
            password.append(i);
        }
        return password.toString();
    }

    private User createUser(Authority userAuthority) {
        User userUser = new User();
        String login = "user";
        String password = derivedPassword(login);
        userUser.setId("user-2");
        userUser.setLogin("user");
        userUser.setPassword(passwordEncoder.encode(password));
        userUser.setFirstName("User");
        userUser.setLastName("User");
        userUser.setEmail("user@localhost");
        userUser.setActivated(true);
        userUser.setLangKey("en");
        userUser.setCreatedBy(Constants.SYSTEM);
        userUser.setCreatedDate(Instant.now());
        userUser.getAuthorities().add(userAuthority);
        return userUser;
    }

    private User createAdmin(Authority adminAuthority, Authority userAuthority) {
        User adminUser = new User();
        String login = "admin";
        String password;
        if (StringUtils.hasText(configuredAdminPassword)) {
            password = configuredAdminPassword;
            logger.info("Creating admin with login: {} and the configured GATEWAY_ADMIN_PASSWORD", login);
        } else {
            password = derivedPassword(login);
            // The value itself is deliberately not logged. These logs are shipped off the host, and
            // a password in them outlives the terminal it was printed to. The derived form is
            // documented in AGENTS.md for local use.
            logger.warn(
                "Creating admin with login: {} and a password derived from it — this value is public. " +
                "Set GATEWAY_ADMIN_PASSWORD for any deployment that is reachable by anyone else.",
                login
            );
        }
        adminUser.setId("user-1");
        adminUser.setLogin("admin");
        adminUser.setPassword(passwordEncoder.encode(password));
        adminUser.setFirstName("admin");
        adminUser.setLastName("Administrator");
        adminUser.setEmail("admin@localhost");
        adminUser.setActivated(true);
        adminUser.setLangKey("en");
        adminUser.setCreatedBy(Constants.SYSTEM);
        adminUser.setCreatedDate(Instant.now());
        adminUser.getAuthorities().add(adminAuthority);
        adminUser.getAuthorities().add(userAuthority);
        return adminUser;
    }

    private User createProfessional(Authority professionalAuthority, String login) {
        User professionalUser = new User();
        String password = derivedPassword(login);
        professionalUser.setId(UUID.randomUUID().toString());
        professionalUser.setLogin(login);
        professionalUser.setPassword(passwordEncoder.encode(password));
        professionalUser.setFirstName("Professional");
        professionalUser.setLastName(Character.toUpperCase(login.charAt(0)) + login.substring(1));
        professionalUser.setEmail(login + "@localhost");
        professionalUser.setActivated(true);
        professionalUser.setLangKey("en");
        professionalUser.setCreatedBy(Constants.SYSTEM);
        professionalUser.setCreatedDate(Instant.now());
        professionalUser.getAuthorities().add(professionalAuthority);
        return professionalUser;
    }
}
