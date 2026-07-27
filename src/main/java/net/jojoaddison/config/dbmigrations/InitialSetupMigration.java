package net.jojoaddison.config.dbmigrations;

import java.time.Instant;
import java.util.UUID;
import net.jojoaddison.config.Constants;
import net.jojoaddison.domain.Authority;
import net.jojoaddison.domain.User;
import net.jojoaddison.security.AuthoritiesConstants;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Seeds the initial database state when it is missing.
 */
@Component
public class InitialSetupMigration implements ApplicationRunner {

    private final MongoTemplate template;
    private final PasswordEncoder passwordEncoder;
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(InitialSetupMigration.class);

    public InitialSetupMigration(MongoTemplate template, PasswordEncoder passwordEncoder) {
        this.template = template;
        this.passwordEncoder = passwordEncoder;
        cleanup();
    }

    public final void cleanup() {
        template.dropCollection(Authority.class);
        template.dropCollection(User.class);
        logger.info("Dropped Authority and User collections");
    }

    @Override
    public void run(ApplicationArguments args) {
        Authority userAuthority = saveAuthorityIfMissing(createUserAuthority());
        Authority adminAuthority = saveAuthorityIfMissing(createAdminAuthority());
        saveUserIfMissing(createUser(userAuthority), "user");
        saveUserIfMissing(createAdmin(adminAuthority, userAuthority), "admin");
        saveUserIfMissing(createProfessional(saveAuthorityIfMissing(createDoctorAuthority()), "doctor"), "doctor");
        saveUserIfMissing(createProfessional(saveAuthorityIfMissing(createNurseAuthority()), "nurse"), "nurse");
        saveUserIfMissing(createProfessional(saveAuthorityIfMissing(createAngelAuthority()), "angel"), "angel");
        saveUserIfMissing(createProfessional(saveAuthorityIfMissing(createCarerAuthority()), "carer"), "carer");
        saveUserIfMissing(createProfessional(saveAuthorityIfMissing(createParamedicAuthority()), "paramedic"), "paramedic");
        saveUserIfMissing(
            createProfessional(saveAuthorityIfMissing(createAuthority(AuthoritiesConstants.PHARMACIST)), "pharmacist"),
            "pharmacist"
        );
        saveUserIfMissing(
            createProfessional(saveAuthorityIfMissing(createAuthority(AuthoritiesConstants.THERAPIST)), "therapist"),
            "therapist"
        );
        saveUserIfMissing(createProfessional(saveAuthorityIfMissing(createAuthority(AuthoritiesConstants.CHEMIST)), "chemist"), "chemist");
        saveUserIfMissing(
            createProfessional(saveAuthorityIfMissing(createAuthority(AuthoritiesConstants.TECHNICIAN)), "technician"),
            "technician"
        );
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

    private void saveUserIfMissing(User user, String login) {
        Query query = Query.query(Criteria.where("login").is(login));
        if (!template.exists(query, User.class)) {
            template.save(user);
        }
    }

    private User createUser(Authority userAuthority) {
        User userUser = new User();
        String login = "user";
        String password = (Character.toUpperCase(login.charAt(0)) + login.substring(1) + "@");
        for (int i = 1; i < login.length(); i++) {
            password += i;
        }
        logger.info("Creating user with login: {} and password: {}", login, password);
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
        String password = (Character.toUpperCase(login.charAt(0)) + login.substring(1) + "@");
        for (int i = 1; i < login.length(); i++) {
            password += i;
        }
        logger.info("Creating admin with login: {} and password: {}", login, password);
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
        String password = (Character.toUpperCase(login.charAt(0)) + login.substring(1) + "@");
        for (int i = 1; i < login.length(); i++) {
            password += i;
        }
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
