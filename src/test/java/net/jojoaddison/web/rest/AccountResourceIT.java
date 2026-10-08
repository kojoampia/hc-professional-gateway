package net.jojoaddison.web.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.config.Constants;
import net.jojoaddison.domain.Authority;
import net.jojoaddison.domain.User;
import net.jojoaddison.repository.AuthorityRepository;
import net.jojoaddison.repository.UserRepository;
import net.jojoaddison.security.AuthoritiesConstants;
import net.jojoaddison.service.UserService;
import net.jojoaddison.service.dto.AdminUserDTO;
import net.jojoaddison.service.dto.PasswordChangeDTO;
import net.jojoaddison.web.rest.vm.KeyAndPasswordVM;
import net.jojoaddison.web.rest.vm.ManagedUserVM;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Integration tests for the {@link AccountResource} REST controller.
 */
@AutoConfigureWebTestClient(timeout = IntegrationTest.DEFAULT_TIMEOUT)
@IntegrationTest
class AccountResourceIT {

    static final String TEST_USER_LOGIN = "test";

    @Autowired
    private ObjectMapper om;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuthorityRepository authorityRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private WebTestClient accountWebTestClient;

    @BeforeEach
    public void setup() {
        userRepository.deleteAll().block();
    }

    @Test
    @WithUnauthenticatedMockUser
    void testNonAuthenticatedUser() {
        accountWebTestClient
            .get()
            .uri("/api/authenticate")
            .accept(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .isEmpty();
    }

    @Test
    @WithMockUser(TEST_USER_LOGIN)
    void testAuthenticatedUser() {
        accountWebTestClient
            .get()
            .uri("/api/authenticate")
            .accept(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(String.class)
            .isEqualTo(TEST_USER_LOGIN);
    }

    @Test
    @WithMockUser(TEST_USER_LOGIN)
    void testGetExistingAccount() {
        Set<String> authorities = new HashSet<>();
        authorities.add(AuthoritiesConstants.ADMIN);

        AdminUserDTO user = new AdminUserDTO();
        user.setLogin(TEST_USER_LOGIN);
        user.setFirstName("john");
        user.setLastName("doe");
        user.setEmail("john.doe@jhipster.com");
        user.setImageUrl("http://placehold.it/50x50");
        user.setLangKey("en");
        user.setAuthorities(authorities);
        userService.createUser(user).block();

        accountWebTestClient
            .get()
            .uri("/api/account")
            .accept(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus()
            .isOk()
            .expectHeader()
            .contentType(MediaType.APPLICATION_JSON_VALUE)
            .expectBody()
            .jsonPath("$.login")
            .isEqualTo(TEST_USER_LOGIN)
            .jsonPath("$.firstName")
            .isEqualTo("john")
            .jsonPath("$.lastName")
            .isEqualTo("doe")
            .jsonPath("$.email")
            .isEqualTo("john.doe@jhipster.com")
            .jsonPath("$.imageUrl")
            .isEqualTo("http://placehold.it/50x50")
            .jsonPath("$.langKey")
            .isEqualTo("en")
            // ⚠ TWO authorities since F7, and the second one is the point of that finding:
            // profile.md says "always append ROLE_USER by default in the gateway", and
            // UserService.createUser — the invitation path this fixture uses — appended nothing.
            // The requested ROLE_ADMIN is still here, because "append" is not "replace";
            // EveryAccountGetsRoleUserIT holds both halves.
            .jsonPath("$.authorities")
            .value(org.hamcrest.Matchers.containsInAnyOrder(AuthoritiesConstants.USER, AuthoritiesConstants.ADMIN));
    }

    @Test
    void testGetUnknownAccount() {
        accountWebTestClient
            .get()
            .uri("/api/account")
            .accept(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus()
            .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void testRegisterValid() throws Exception {
        ManagedUserVM validUser = new ManagedUserVM();
        validUser.setLogin("test-register-valid");
        validUser.setPassword("password");
        validUser.setFirstName("Alice");
        validUser.setLastName("Test");
        validUser.setEmail("test-register-valid@example.com");
        validUser.setImageUrl("http://placehold.it/50x50");
        validUser.setLangKey(Constants.DEFAULT_LANGUAGE);
        validUser.setAuthorities(Set.of(AuthoritiesConstants.USER));
        assertThat(userRepository.findOneByLogin("test-register-valid").blockOptional()).isEmpty();

        accountWebTestClient
            .post()
            .uri("/api/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(validUser))
            .exchange()
            .expectStatus()
            .isCreated();

        assertThat(userRepository.findOneByLogin("test-register-valid").blockOptional()).isPresent();
    }

    @Test
    void testRegisterInvalidLogin() throws Exception {
        ManagedUserVM invalidUser = new ManagedUserVM();
        invalidUser.setLogin("funky-log(n"); // <-- invalid
        invalidUser.setPassword("password");
        invalidUser.setFirstName("Funky");
        invalidUser.setLastName("One");
        invalidUser.setEmail("funky@example.com");
        invalidUser.setActivated(true);
        invalidUser.setImageUrl("http://placehold.it/50x50");
        invalidUser.setLangKey(Constants.DEFAULT_LANGUAGE);
        invalidUser.setAuthorities(Set.of(AuthoritiesConstants.USER));

        accountWebTestClient
            .post()
            .uri("/api/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(invalidUser))
            .exchange()
            .expectStatus()
            .isBadRequest();

        Optional<User> user = userRepository.findOneByEmailIgnoreCase("funky@example.com").blockOptional();
        assertThat(user).isEmpty();
    }

    static Stream<ManagedUserVM> invalidUsers() {
        return Stream.of(
            createInvalidUser("bob", "password", "Bob", "Green", "invalid", true), // <-- invalid
            createInvalidUser("bob", "123", "Bob", "Green", "bob@example.com", true), // password with only 3 digits
            createInvalidUser("bob", null, "Bob", "Green", "bob@example.com", true) // invalid null password
        );
    }

    @ParameterizedTest
    @MethodSource("invalidUsers")
    void testRegisterInvalidUsers(ManagedUserVM invalidUser) throws Exception {
        accountWebTestClient
            .post()
            .uri("/api/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(invalidUser))
            .exchange()
            .expectStatus()
            .isBadRequest();

        Optional<User> user = userRepository.findOneByLogin("bob").blockOptional();
        assertThat(user).isEmpty();
    }

    private static ManagedUserVM createInvalidUser(
        String login,
        String password,
        String firstName,
        String lastName,
        String email,
        boolean activated
    ) {
        ManagedUserVM invalidUser = new ManagedUserVM();
        invalidUser.setLogin(login);
        invalidUser.setPassword(password);
        invalidUser.setFirstName(firstName);
        invalidUser.setLastName(lastName);
        invalidUser.setEmail(email);
        invalidUser.setActivated(activated);
        invalidUser.setImageUrl("http://placehold.it/50x50");
        invalidUser.setLangKey(Constants.DEFAULT_LANGUAGE);
        invalidUser.setAuthorities(Set.of(AuthoritiesConstants.USER));
        return invalidUser;
    }

    @Test
    void testRegisterDuplicateLogin() throws Exception {
        // First registration
        ManagedUserVM firstUser = new ManagedUserVM();
        firstUser.setLogin("alice");
        firstUser.setPassword("password");
        firstUser.setFirstName("Alice");
        firstUser.setLastName("Something");
        firstUser.setEmail("alice@example.com");
        firstUser.setImageUrl("http://placehold.it/50x50");
        firstUser.setLangKey(Constants.DEFAULT_LANGUAGE);
        firstUser.setAuthorities(Set.of(AuthoritiesConstants.USER));

        // Duplicate login, different email
        ManagedUserVM secondUser = new ManagedUserVM();
        secondUser.setLogin(firstUser.getLogin());
        secondUser.setPassword(firstUser.getPassword());
        secondUser.setFirstName(firstUser.getFirstName());
        secondUser.setLastName(firstUser.getLastName());
        secondUser.setEmail("alice2@example.com");
        secondUser.setImageUrl(firstUser.getImageUrl());
        secondUser.setLangKey(firstUser.getLangKey());
        secondUser.setCreatedBy(firstUser.getCreatedBy());
        secondUser.setCreatedDate(firstUser.getCreatedDate());
        secondUser.setLastModifiedBy(firstUser.getLastModifiedBy());
        secondUser.setLastModifiedDate(firstUser.getLastModifiedDate());
        secondUser.setAuthorities(new HashSet<>(firstUser.getAuthorities()));

        // First user
        accountWebTestClient
            .post()
            .uri("/api/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(firstUser))
            .exchange()
            .expectStatus()
            .isCreated();

        // Second (non activated) user
        accountWebTestClient
            .post()
            .uri("/api/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(secondUser))
            .exchange()
            .expectStatus()
            .isCreated();

        Optional<User> testUser = userRepository.findOneByEmailIgnoreCase("alice2@example.com").blockOptional();
        assertThat(testUser).isPresent();
        testUser.orElseThrow().setActivated(true);
        userRepository.save(testUser.orElseThrow()).block();

        // Second (already activated) user
        accountWebTestClient
            .post()
            .uri("/api/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(secondUser))
            .exchange()
            .expectStatus()
            .isBadRequest();
    }

    @Test
    void testRegisterDuplicateEmail() throws Exception {
        // First user
        ManagedUserVM firstUser = new ManagedUserVM();
        firstUser.setLogin("test-register-duplicate-email");
        firstUser.setPassword("password");
        firstUser.setFirstName("Alice");
        firstUser.setLastName("Test");
        firstUser.setEmail("test-register-duplicate-email@example.com");
        firstUser.setImageUrl("http://placehold.it/50x50");
        firstUser.setLangKey(Constants.DEFAULT_LANGUAGE);
        firstUser.setAuthorities(Set.of(AuthoritiesConstants.USER));

        // Register first user
        accountWebTestClient
            .post()
            .uri("/api/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(firstUser))
            .exchange()
            .expectStatus()
            .isCreated();

        Optional<User> testUser1 = userRepository.findOneByLogin("test-register-duplicate-email").blockOptional();
        assertThat(testUser1).isPresent();

        // Duplicate email, different login
        ManagedUserVM secondUser = new ManagedUserVM();
        secondUser.setLogin("test-register-duplicate-email-2");
        secondUser.setPassword(firstUser.getPassword());
        secondUser.setFirstName(firstUser.getFirstName());
        secondUser.setLastName(firstUser.getLastName());
        secondUser.setEmail(firstUser.getEmail());
        secondUser.setImageUrl(firstUser.getImageUrl());
        secondUser.setLangKey(firstUser.getLangKey());
        secondUser.setAuthorities(new HashSet<>(firstUser.getAuthorities()));

        // Register second (non activated) user
        accountWebTestClient
            .post()
            .uri("/api/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(secondUser))
            .exchange()
            .expectStatus()
            .isCreated();

        Optional<User> testUser2 = userRepository.findOneByLogin("test-register-duplicate-email").blockOptional();
        assertThat(testUser2).isEmpty();

        Optional<User> testUser3 = userRepository.findOneByLogin("test-register-duplicate-email-2").blockOptional();
        assertThat(testUser3).isPresent();

        // Duplicate email - with uppercase email address
        ManagedUserVM userWithUpperCaseEmail = new ManagedUserVM();
        userWithUpperCaseEmail.setId(firstUser.getId());
        userWithUpperCaseEmail.setLogin("test-register-duplicate-email-3");
        userWithUpperCaseEmail.setPassword(firstUser.getPassword());
        userWithUpperCaseEmail.setFirstName(firstUser.getFirstName());
        userWithUpperCaseEmail.setLastName(firstUser.getLastName());
        userWithUpperCaseEmail.setEmail("TEST-register-duplicate-email@example.com");
        userWithUpperCaseEmail.setImageUrl(firstUser.getImageUrl());
        userWithUpperCaseEmail.setLangKey(firstUser.getLangKey());
        userWithUpperCaseEmail.setAuthorities(new HashSet<>(firstUser.getAuthorities()));

        // Register third (not activated) user
        accountWebTestClient
            .post()
            .uri("/api/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(userWithUpperCaseEmail))
            .exchange()
            .expectStatus()
            .isCreated();

        Optional<User> testUser4 = userRepository.findOneByLogin("test-register-duplicate-email-3").blockOptional();
        assertThat(testUser4).isPresent();
        assertThat(testUser4.orElseThrow().getEmail()).isEqualTo("test-register-duplicate-email@example.com");

        testUser4.orElseThrow().setActivated(true);
        userService.updateUser((new AdminUserDTO(testUser4.orElseThrow()))).block();

        // Register 4th (already activated) user
        accountWebTestClient
            .post()
            .uri("/api/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(secondUser))
            .exchange()
            .expectStatus()
            .is4xxClientError();
    }

    @Test
    void testRegisterAdminIsIgnored() throws Exception {
        ManagedUserVM validUser = new ManagedUserVM();
        validUser.setLogin("badguy");
        validUser.setPassword("password");
        validUser.setFirstName("Bad");
        validUser.setLastName("Guy");
        validUser.setEmail("badguy@example.com");
        validUser.setActivated(true);
        validUser.setImageUrl("http://placehold.it/50x50");
        validUser.setLangKey(Constants.DEFAULT_LANGUAGE);
        validUser.setAuthorities(Set.of(AuthoritiesConstants.ADMIN));

        accountWebTestClient
            .post()
            .uri("/api/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(validUser))
            .exchange()
            .expectStatus()
            .isCreated();

        Optional<User> userDup = userRepository.findOneByLogin("badguy").blockOptional();
        assertThat(userDup).isPresent();
        assertThat(userDup.orElseThrow().getAuthorities())
            .hasSize(1)
            .containsExactly(authorityRepository.findById(AuthoritiesConstants.USER).block());
    }

    @Test
    void testActivateAccount() {
        final String activationKey = "some activation key";
        User user = new User();
        user.setLogin("activate-account");
        user.setEmail("activate-account@example.com");
        user.setPassword(RandomStringUtils.randomAlphanumeric(60));
        user.setActivated(false);
        user.setActivationKey(activationKey);

        userRepository.save(user).block();

        accountWebTestClient.get().uri("/api/activate?key={activationKey}", activationKey).exchange().expectStatus().isOk();

        user = userRepository.findOneByLogin(user.getLogin()).block();
        assertThat(user.isActivated()).isTrue();
    }

    @Test
    void testActivateAccountWithWrongKey() {
        accountWebTestClient
            .get()
            .uri("/api/activate?key=wrongActivationKey")
            .exchange()
            .expectStatus()
            .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    @WithMockUser("save-account")
    void testSaveAccount() throws Exception {
        User user = new User();
        user.setLogin("save-account");
        user.setEmail("save-account@example.com");
        user.setPassword(RandomStringUtils.randomAlphanumeric(60));
        user.setActivated(true);
        userRepository.save(user).block();

        AdminUserDTO userDTO = new AdminUserDTO();
        userDTO.setLogin("not-used");
        userDTO.setFirstName("firstname");
        userDTO.setLastName("lastname");
        userDTO.setEmail("save-account@example.com");
        userDTO.setActivated(false);
        userDTO.setImageUrl("http://placehold.it/50x50");
        userDTO.setLangKey(Constants.DEFAULT_LANGUAGE);
        userDTO.setAuthorities(Set.of(AuthoritiesConstants.ADMIN));

        accountWebTestClient
            .post()
            .uri("/api/account")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(userDTO))
            .exchange()
            .expectStatus()
            .isOk();

        User updatedUser = userRepository.findOneByLogin(user.getLogin()).block();
        assertThat(updatedUser.getFirstName()).isEqualTo(userDTO.getFirstName());
        assertThat(updatedUser.getLastName()).isEqualTo(userDTO.getLastName());
        assertThat(updatedUser.getEmail()).isEqualTo(userDTO.getEmail());
        assertThat(updatedUser.getLangKey()).isEqualTo(userDTO.getLangKey());
        assertThat(updatedUser.getPassword()).isEqualTo(user.getPassword());
        assertThat(updatedUser.getImageUrl()).isEqualTo(userDTO.getImageUrl());
        assertThat(updatedUser.isActivated()).isTrue();
        assertThat(updatedUser.getAuthorities()).isEmpty();
    }

    @Test
    @WithMockUser("save-invalid-email")
    void testSaveInvalidEmail() throws Exception {
        User user = new User();
        user.setLogin("save-invalid-email");
        user.setEmail("save-invalid-email@example.com");
        user.setPassword(RandomStringUtils.randomAlphanumeric(60));
        user.setActivated(true);

        userRepository.save(user).block();

        AdminUserDTO userDTO = new AdminUserDTO();
        userDTO.setLogin("not-used");
        userDTO.setFirstName("firstname");
        userDTO.setLastName("lastname");
        userDTO.setEmail("invalid email");
        userDTO.setActivated(false);
        userDTO.setImageUrl("http://placehold.it/50x50");
        userDTO.setLangKey(Constants.DEFAULT_LANGUAGE);
        userDTO.setAuthorities(Set.of(AuthoritiesConstants.ADMIN));

        accountWebTestClient
            .post()
            .uri("/api/account")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(userDTO))
            .exchange()
            .expectStatus()
            .isBadRequest();

        assertThat(userRepository.findOneByEmailIgnoreCase("invalid email").blockOptional()).isNotPresent();
    }

    @Test
    @WithMockUser("save-existing-email")
    void testSaveExistingEmail() throws Exception {
        User user = new User();
        user.setLogin("save-existing-email");
        user.setEmail("save-existing-email@example.com");
        user.setPassword(RandomStringUtils.randomAlphanumeric(60));
        user.setActivated(true);
        userRepository.save(user).block();

        User anotherUser = new User();
        anotherUser.setLogin("save-existing-email2");
        anotherUser.setEmail("save-existing-email2@example.com");
        anotherUser.setPassword(RandomStringUtils.randomAlphanumeric(60));
        anotherUser.setActivated(true);

        userRepository.save(anotherUser).block();

        AdminUserDTO userDTO = new AdminUserDTO();
        userDTO.setLogin("not-used");
        userDTO.setFirstName("firstname");
        userDTO.setLastName("lastname");
        userDTO.setEmail("save-existing-email2@example.com");
        userDTO.setActivated(false);
        userDTO.setImageUrl("http://placehold.it/50x50");
        userDTO.setLangKey(Constants.DEFAULT_LANGUAGE);
        userDTO.setAuthorities(Set.of(AuthoritiesConstants.ADMIN));

        accountWebTestClient
            .post()
            .uri("/api/account")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(userDTO))
            .exchange()
            .expectStatus()
            .isBadRequest();

        User updatedUser = userRepository.findOneByLogin("save-existing-email").block();
        assertThat(updatedUser.getEmail()).isEqualTo("save-existing-email@example.com");
    }

    @Test
    @WithMockUser("save-existing-email-and-login")
    void testSaveExistingEmailAndLogin() throws Exception {
        User user = new User();
        user.setLogin("save-existing-email-and-login");
        user.setEmail("save-existing-email-and-login@example.com");
        user.setPassword(RandomStringUtils.randomAlphanumeric(60));
        user.setActivated(true);
        userRepository.save(user).block();

        AdminUserDTO userDTO = new AdminUserDTO();
        userDTO.setLogin("not-used");
        userDTO.setFirstName("firstname");
        userDTO.setLastName("lastname");
        userDTO.setEmail("save-existing-email-and-login@example.com");
        userDTO.setActivated(false);
        userDTO.setImageUrl("http://placehold.it/50x50");
        userDTO.setLangKey(Constants.DEFAULT_LANGUAGE);
        userDTO.setAuthorities(Set.of(AuthoritiesConstants.ADMIN));

        accountWebTestClient
            .post()
            .uri("/api/account")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(userDTO))
            .exchange()
            .expectStatus()
            .isOk();

        User updatedUser = userRepository.findOneByLogin("save-existing-email-and-login").block();
        assertThat(updatedUser.getEmail()).isEqualTo("save-existing-email-and-login@example.com");
    }

    /**
     * T4's happy path: {@code PUT /api/account} writes the five fields and nothing else.
     *
     * <p>The twin of {@code testSaveAccount}, which stays green beside it because the deprecated
     * {@code POST} is now an adapter over the same write. Both are kept until T6 retires the verb;
     * see {@code AccountResource#saveAccount}.
     */
    @Test
    @WithMockUser("update-account")
    void testUpdateAccount() throws Exception {
        User user = new User();
        user.setLogin("update-account");
        user.setEmail("update-account@example.com");
        user.setPassword(RandomStringUtils.randomAlphanumeric(60));
        user.setActivated(true);
        userRepository.save(user).block();

        AdminUserDTO accountDTO = ownAccountBody(
            "firstname",
            "lastname",
            "update-account@example.com",
            Constants.DEFAULT_LANGUAGE,
            "http://placehold.it/50x50"
        );

        accountWebTestClient
            .put()
            .uri("/api/account")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(accountDTO))
            .exchange()
            .expectStatus()
            .isOk();

        User updatedUser = userRepository.findOneByLogin(user.getLogin()).block();
        assertThat(updatedUser.getFirstName()).isEqualTo(accountDTO.getFirstName());
        assertThat(updatedUser.getLastName()).isEqualTo(accountDTO.getLastName());
        assertThat(updatedUser.getEmail()).isEqualTo(accountDTO.getEmail());
        assertThat(updatedUser.getLangKey()).isEqualTo(accountDTO.getLangKey());
        assertThat(updatedUser.getImageUrl()).isEqualTo(accountDTO.getImageUrl());
        assertThat(updatedUser.getPassword()).isEqualTo(user.getPassword());
    }

    /**
     * ⛔ <b>{@code profile.md}: "Do not update the fields {@code id}, {@code activated} and
     * {@code login} from this endpoint."</b> (F4)
     *
     * <p>Asserted against <b>raw JSON</b> rather than a DTO, and since F4 that is no longer because
     * the type forbids writing it: {@link AdminUserDTO} is what this endpoint binds, exactly as the
     * specification names, so all four fields below are now <em>sendable</em>. What refuses them is
     * {@code AccountResource#ownAccountUpdate}, which reads five values by name, and
     * {@code UserService}'s five-argument {@code updateUser}, which is the only write reached from
     * here. ⚠ Raw JSON is kept anyway, so the case cannot be weakened by a change to a constructor.
     *
     * <p>⚠ <b>The request succeeds.</b> "Do not update" is not "refuse the request" —
     * {@code GET /api/account} answers an {@code AdminUserDTO} carrying all three and the obvious
     * client sends the document straight back, so a 400 would make the endpoint unusable by the only
     * caller the specification describes. The five allowed fields land and the three forbidden
     * values stand.
     */
    @Test
    @WithMockUser("update-account-identity")
    void testUpdateAccountDoesNotUpdateIdActivatedOrLogin() {
        User user = new User();
        user.setLogin("update-account-identity");
        user.setEmail("update-account-identity@example.com");
        user.setPassword(RandomStringUtils.randomAlphanumeric(60));
        user.setActivated(true);
        userRepository.save(user).block();

        putRawBody(
            """
            {
              "id": "some-other-id",
              "login": "not-used",
              "firstName": "firstname",
              "lastName": "lastname",
              "email": "update-account-identity@example.com",
              "langKey": "en",
              "imageUrl": "http://placehold.it/50x50",
              "activated": false
            }
            """
        );

        User updatedUser = userRepository.findOneByLogin("update-account-identity").block();
        // The control: the five allowed fields did land, so the three below were not merely unread
        // because the whole request was ignored.
        assertThat(updatedUser.getFirstName()).isEqualTo("firstname");
        assertThat(updatedUser.getId()).as("id is not updated from this endpoint").isEqualTo(user.getId());
        assertThat(updatedUser.getLogin()).as("login is not updated from this endpoint").isEqualTo("update-account-identity");
        assertThat(updatedUser.isActivated()).as("activated is not updated from this endpoint").isTrue();
    }

    /**
     * ⛔ <b>And {@code authorities} is unwritable, which is not a deny-list entry but the point of the
     * whole endpoint.</b>
     *
     * <p>The career role is a <em>request</em> granted after review, written on
     * {@code ProfessionalApplication}, never on the account by its holder. The one edit that would
     * break this is routing the handler through {@code UserService.updateUser(AdminUserDTO)}, which
     * clears the authority set and refills it from the body — so this case is the guard on that
     * specific mistake rather than on the field in general.
     *
     * <p>Two bodies, because they fail differently: granting {@code ROLE_ADMIN} to an account with
     * none, and <em>removing</em> an authority from an account that has one. An implementation that
     * cleared the set and refilled it would pass the first and fail the second.
     */
    @Test
    @WithMockUser("update-account-authorities")
    void testUpdateAccountDoesNotWriteAuthorities() {
        User user = new User();
        user.setLogin("update-account-authorities");
        user.setEmail("update-account-authorities@example.com");
        user.setPassword(RandomStringUtils.randomAlphanumeric(60));
        user.setActivated(true);
        Authority granted = new Authority();
        granted.setName(AuthoritiesConstants.USER);
        user.setAuthorities(new java.util.HashSet<>(java.util.Set.of(granted)));
        userRepository.save(user).block();

        putRawBody(
            """
            {
              "login": "not-used",
              "firstName": "firstname",
              "lastName": "lastname",
              "email": "update-account-authorities@example.com",
              "langKey": "en",
              "authorities": ["ROLE_ADMIN"]
            }
            """
        );

        assertThat(userRepository.findOneByLogin("update-account-authorities").block().getAuthorities())
            .as("a body cannot grant an authority")
            .extracting(Authority::getName)
            .containsExactly(AuthoritiesConstants.USER);

        putRawBody(
            """
            {
              "login": "not-used",
              "firstName": "firstname",
              "lastName": "lastname",
              "email": "update-account-authorities@example.com",
              "langKey": "en",
              "authorities": []
            }
            """
        );

        assertThat(userRepository.findOneByLogin("update-account-authorities").block().getAuthorities())
            .as("nor take one away — an implementation that cleared and refilled would pass the first body and fail this one")
            .extracting(Authority::getName)
            .containsExactly(AuthoritiesConstants.USER);
    }

    /**
     * ⚠ <b>A body with no {@code login} is refused with a 400, and that is a consequence of binding
     * the type the specification names.</b>
     *
     * <p>{@code AdminUserDTO.login} is {@code @NotBlank}, so {@code @Valid} rejects the request
     * before the handler runs — even though {@link AccountResource#updateUserAccount} would then
     * ignore the value. Recorded as a case rather than left to be discovered, because it is a
     * <em>new</em> refusal: the five-component record this endpoint used to bind had no login at all
     * and a body without one was fine.
     *
     * <p>It costs the caller the specification describes nothing — {@code GET /api/account} answers
     * the login and step 1's dialog is populated from it — and it makes the two verbs refuse the
     * same bodies, which is the property {@code AccountResource#saveAccount}'s javadoc relies on
     * while both exist.
     */
    @Test
    @WithMockUser("update-account-no-login")
    void testUpdateAccountWithoutALoginIsRefused() {
        User user = new User();
        user.setLogin("update-account-no-login");
        user.setEmail("update-account-no-login@example.com");
        user.setPassword(RandomStringUtils.randomAlphanumeric(60));
        user.setActivated(true);
        userRepository.save(user).block();

        accountWebTestClient
            .put()
            .uri("/api/account")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """
                {
                  "firstName": "firstname",
                  "lastName": "lastname",
                  "email": "update-account-no-login@example.com",
                  "langKey": "en"
                }
                """
            )
            .exchange()
            .expectStatus()
            .isBadRequest();

        assertThat(userRepository.findOneByLogin("update-account-no-login").block().getFirstName())
            .as("a refused request writes nothing")
            .isNull();
    }

    /**
     * The list in the handler names the three fields the specification names, and nothing else.
     *
     * <p>A constant nothing compares against is prose; this is what ties
     * {@code AccountResource.FIELDS_THIS_ENDPOINT_DOES_NOT_UPDATE} to the two cases above, so that
     * adding a fourth name to it without a case, or removing one that has a case, goes red.
     */
    @Test
    void theForbiddenFieldListIsTheOneTheSpecificationNames() {
        assertThat(AccountResource.FIELDS_THIS_ENDPOINT_DOES_NOT_UPDATE).containsExactly("id", "activated", "login");
    }

    @Test
    @WithMockUser("update-invalid-email")
    void testUpdateAccountInvalidEmail() throws Exception {
        User user = new User();
        user.setLogin("update-invalid-email");
        user.setEmail("update-invalid-email@example.com");
        user.setPassword(RandomStringUtils.randomAlphanumeric(60));
        user.setActivated(true);
        userRepository.save(user).block();

        AdminUserDTO accountDTO = ownAccountBody(
            "firstname",
            "lastname",
            "invalid email",
            Constants.DEFAULT_LANGUAGE,
            "http://placehold.it/50x50"
        );

        accountWebTestClient
            .put()
            .uri("/api/account")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(accountDTO))
            .exchange()
            .expectStatus()
            .isBadRequest();

        assertThat(userRepository.findOneByEmailIgnoreCase("invalid email").blockOptional()).isNotPresent();
    }

    @Test
    @WithMockUser("update-existing-email")
    void testUpdateAccountExistingEmail() throws Exception {
        User user = new User();
        user.setLogin("update-existing-email");
        user.setEmail("update-existing-email@example.com");
        user.setPassword(RandomStringUtils.randomAlphanumeric(60));
        user.setActivated(true);
        userRepository.save(user).block();

        User anotherUser = new User();
        anotherUser.setLogin("update-existing-email2");
        anotherUser.setEmail("update-existing-email2@example.com");
        anotherUser.setPassword(RandomStringUtils.randomAlphanumeric(60));
        anotherUser.setActivated(true);
        userRepository.save(anotherUser).block();

        AdminUserDTO accountDTO = ownAccountBody(
            "firstname",
            "lastname",
            "update-existing-email2@example.com",
            Constants.DEFAULT_LANGUAGE,
            "http://placehold.it/50x50"
        );

        accountWebTestClient
            .put()
            .uri("/api/account")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(accountDTO))
            .exchange()
            .expectStatus()
            .isBadRequest();

        User updatedUser = userRepository.findOneByLogin("update-existing-email").block();
        assertThat(updatedUser.getEmail()).isEqualTo("update-existing-email@example.com");
    }

    /**
     * Re-sending one's own address is the ordinary case and must not be refused.
     *
     * <p>The email rule is "belongs to a different login", not "is already in use". Written as the
     * latter it would refuse every save in which the clinician did not change their address — which
     * is most of them.
     */
    @Test
    @WithMockUser("update-existing-email-and-login")
    void testUpdateAccountExistingEmailAndLogin() throws Exception {
        User user = new User();
        user.setLogin("update-existing-email-and-login");
        user.setEmail("update-existing-email-and-login@example.com");
        user.setPassword(RandomStringUtils.randomAlphanumeric(60));
        user.setActivated(true);
        userRepository.save(user).block();

        AdminUserDTO accountDTO = ownAccountBody(
            "firstname",
            "lastname",
            "update-existing-email-and-login@example.com",
            Constants.DEFAULT_LANGUAGE,
            "http://placehold.it/50x50"
        );

        accountWebTestClient
            .put()
            .uri("/api/account")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(accountDTO))
            .exchange()
            .expectStatus()
            .isOk();

        User updatedUser = userRepository.findOneByLogin("update-existing-email-and-login").block();
        assertThat(updatedUser.getEmail()).isEqualTo("update-existing-email-and-login@example.com");
    }

    /**
     * That the {@code .authenticated()} gate reaches the new verb.
     *
     * <p>{@code SecurityConfiguration} holds {@code /api/**} with no method scoping, so {@code PUT}
     * needed no rule of its own — this asserts that rather than trusting the read of the config, and
     * it is the case that would fail if somebody later scoped that matcher to a verb list.
     */
    @Test
    @WithUnauthenticatedMockUser
    void testUpdateAccountRequiresAuthentication() {
        accountWebTestClient
            .put()
            .uri("/api/account")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"firstName\":\"firstname\",\"lastName\":\"lastname\",\"langKey\":\"en\"}")
            .exchange()
            .expectStatus()
            .isUnauthorized();
    }

    @Test
    @WithMockUser("change-password-wrong-existing-password")
    void testChangePasswordWrongExistingPassword() throws Exception {
        User user = new User();
        String currentPassword = RandomStringUtils.randomAlphanumeric(60);
        user.setPassword(passwordEncoder.encode(currentPassword));
        user.setLogin("change-password-wrong-existing-password");
        user.setEmail("change-password-wrong-existing-password@example.com");
        userRepository.save(user).block();

        accountWebTestClient
            .post()
            .uri("/api/account/change-password")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(new PasswordChangeDTO("1" + currentPassword, "new password")))
            .exchange()
            .expectStatus()
            .isBadRequest();

        User updatedUser = userRepository.findOneByLogin("change-password-wrong-existing-password").block();
        assertThat(passwordEncoder.matches("new password", updatedUser.getPassword())).isFalse();
        assertThat(passwordEncoder.matches(currentPassword, updatedUser.getPassword())).isTrue();
    }

    @Test
    @WithMockUser("change-password")
    void testChangePassword() throws Exception {
        User user = new User();
        String currentPassword = RandomStringUtils.randomAlphanumeric(60);
        user.setPassword(passwordEncoder.encode(currentPassword));
        user.setLogin("change-password");
        user.setEmail("change-password@example.com");
        userRepository.save(user).block();

        accountWebTestClient
            .post()
            .uri("/api/account/change-password")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(new PasswordChangeDTO(currentPassword, "new password")))
            .exchange()
            .expectStatus()
            .isOk();

        User updatedUser = userRepository.findOneByLogin("change-password").block();
        assertThat(passwordEncoder.matches("new password", updatedUser.getPassword())).isTrue();
    }

    @Test
    @WithMockUser("change-password-too-small")
    void testChangePasswordTooSmall() throws Exception {
        User user = new User();
        String currentPassword = RandomStringUtils.randomAlphanumeric(60);
        user.setPassword(passwordEncoder.encode(currentPassword));
        user.setLogin("change-password-too-small");
        user.setEmail("change-password-too-small@example.com");
        userRepository.save(user).block();

        String newPassword = RandomStringUtils.random(ManagedUserVM.PASSWORD_MIN_LENGTH - 1);

        accountWebTestClient
            .post()
            .uri("/api/account/change-password")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(new PasswordChangeDTO(currentPassword, newPassword)))
            .exchange()
            .expectStatus()
            .isBadRequest();

        User updatedUser = userRepository.findOneByLogin("change-password-too-small").block();
        assertThat(updatedUser.getPassword()).isEqualTo(user.getPassword());
    }

    @Test
    @WithMockUser("change-password-too-long")
    void testChangePasswordTooLong() throws Exception {
        User user = new User();
        String currentPassword = RandomStringUtils.randomAlphanumeric(60);
        user.setPassword(passwordEncoder.encode(currentPassword));
        user.setLogin("change-password-too-long");
        user.setEmail("change-password-too-long@example.com");
        userRepository.save(user).block();

        String newPassword = RandomStringUtils.random(ManagedUserVM.PASSWORD_MAX_LENGTH + 1);

        accountWebTestClient
            .post()
            .uri("/api/account/change-password")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(new PasswordChangeDTO(currentPassword, newPassword)))
            .exchange()
            .expectStatus()
            .isBadRequest();

        User updatedUser = userRepository.findOneByLogin("change-password-too-long").block();
        assertThat(updatedUser.getPassword()).isEqualTo(user.getPassword());
    }

    @Test
    @WithMockUser("change-password-empty")
    void testChangePasswordEmpty() throws Exception {
        User user = new User();
        String currentPassword = RandomStringUtils.randomAlphanumeric(60);
        user.setPassword(passwordEncoder.encode(currentPassword));
        user.setLogin("change-password-empty");
        user.setEmail("change-password-empty@example.com");
        userRepository.save(user).block();

        accountWebTestClient
            .post()
            .uri("/api/account/change-password")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(new PasswordChangeDTO(currentPassword, "")))
            .exchange()
            .expectStatus()
            .isBadRequest();

        User updatedUser = userRepository.findOneByLogin("change-password-empty").block();
        assertThat(updatedUser.getPassword()).isEqualTo(user.getPassword());
    }

    @Test
    void testRequestPasswordReset() {
        User user = new User();
        user.setPassword(RandomStringUtils.randomAlphanumeric(60));
        user.setActivated(true);
        user.setLogin("password-reset");
        user.setEmail("password-reset@example.com");
        user.setLangKey("en");
        userRepository.save(user).block();

        accountWebTestClient
            .post()
            .uri("/api/account/reset-password/init")
            .bodyValue("password-reset@example.com")
            .exchange()
            .expectStatus()
            .isOk();
    }

    @Test
    void testRequestPasswordResetUpperCaseEmail() {
        User user = new User();
        user.setPassword(RandomStringUtils.randomAlphanumeric(60));
        user.setActivated(true);
        user.setLogin("password-reset-upper-case");
        user.setEmail("password-reset-upper-case@example.com");
        user.setLangKey("en");
        userRepository.save(user).block();

        accountWebTestClient
            .post()
            .uri("/api/account/reset-password/init")
            .bodyValue("password-reset-upper-case@EXAMPLE.COM")
            .exchange()
            .expectStatus()
            .isOk();
    }

    @Test
    void testRequestPasswordResetWrongEmail() {
        accountWebTestClient
            .post()
            .uri("/api/account/reset-password/init")
            .bodyValue("password-reset-wrong-email@example.com")
            .exchange()
            .expectStatus()
            .isOk();
    }

    @Test
    void testFinishPasswordReset() throws Exception {
        User user = new User();
        user.setPassword(RandomStringUtils.randomAlphanumeric(60));
        user.setLogin("finish-password-reset");
        user.setEmail("finish-password-reset@example.com");
        user.setResetDate(Instant.now().plusSeconds(60));
        user.setResetKey("reset key");
        userRepository.save(user).block();

        KeyAndPasswordVM keyAndPassword = new KeyAndPasswordVM();
        keyAndPassword.setKey(user.getResetKey());
        keyAndPassword.setNewPassword("new password");

        accountWebTestClient
            .post()
            .uri("/api/account/reset-password/finish")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(keyAndPassword))
            .exchange()
            .expectStatus()
            .isOk();

        User updatedUser = userRepository.findOneByLogin(user.getLogin()).block();
        assertThat(passwordEncoder.matches(keyAndPassword.getNewPassword(), updatedUser.getPassword())).isTrue();
    }

    @Test
    void testFinishPasswordResetTooSmall() throws Exception {
        User user = new User();
        user.setPassword(RandomStringUtils.randomAlphanumeric(60));
        user.setLogin("finish-password-reset-too-small");
        user.setEmail("finish-password-reset-too-small@example.com");
        user.setResetDate(Instant.now().plusSeconds(60));
        user.setResetKey("reset key too small");
        userRepository.save(user).block();

        KeyAndPasswordVM keyAndPassword = new KeyAndPasswordVM();
        keyAndPassword.setKey(user.getResetKey());
        keyAndPassword.setNewPassword("foo");

        accountWebTestClient
            .post()
            .uri("/api/account/reset-password/finish")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(keyAndPassword))
            .exchange()
            .expectStatus()
            .isBadRequest();

        User updatedUser = userRepository.findOneByLogin(user.getLogin()).block();
        assertThat(passwordEncoder.matches(keyAndPassword.getNewPassword(), updatedUser.getPassword())).isFalse();
    }

    @Test
    void testFinishPasswordResetWrongKey() throws Exception {
        KeyAndPasswordVM keyAndPassword = new KeyAndPasswordVM();
        keyAndPassword.setKey("wrong reset key");
        keyAndPassword.setNewPassword("new password");

        accountWebTestClient
            .post()
            .uri("/api/account/reset-password/finish")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(keyAndPassword))
            .exchange()
            .expectStatus()
            .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // -------------------------------------------------------------------------------------------------
    // PUT /api/account helpers (F4)
    // -------------------------------------------------------------------------------------------------

    /**
     * An {@link AdminUserDTO} carrying only the five fields step 1 edits.
     *
     * <p>⚠ <b>It deliberately leaves {@code id}, {@code login}, {@code activated} and
     * {@code authorities} unset rather than being a narrower type that cannot hold them</b> — since
     * F4 the endpoint binds {@code AdminUserDTO}, as {@code profile.md} names, so the happy-path
     * cases send what a well-behaved client sends and
     * {@link #testUpdateAccountDoesNotUpdateIdActivatedOrLogin} sends what a careless one does.
     */
    private static AdminUserDTO ownAccountBody(String firstName, String lastName, String email, String langKey, String imageUrl) {
        AdminUserDTO accountDTO = new AdminUserDTO();
        // ⚠ A login is REQUIRED on the wire and IGNORED by the write, which is a real consequence of
        // binding the type profile.md names: AdminUserDTO.login is @NotBlank, so @Valid refuses a
        // body without one with a 400 before the handler runs. It is not a hardship for the caller
        // the specification describes — GET /api/account answers the login and step 1's dialog is
        // populated from exactly that — and the deprecated POST has always behaved this way, which
        // is the property this file's javadoc calls "both verbs refuse the same bodies". The value
        // is deliberately not the caller's, so a case that passed because the login was honoured
        // would be visible.
        accountDTO.setLogin("not-used");
        accountDTO.setFirstName(firstName);
        accountDTO.setLastName(lastName);
        accountDTO.setEmail(email);
        accountDTO.setLangKey(langKey);
        accountDTO.setImageUrl(imageUrl);
        return accountDTO;
    }

    /** {@code PUT /api/account} with a hand-built body, expecting 200. */
    private void putRawBody(String body) {
        accountWebTestClient
            .put()
            .uri("/api/account")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()
            .expectStatus()
            .isOk();
    }
}
