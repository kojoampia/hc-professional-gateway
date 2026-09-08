package net.jojoaddison.web.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.stream.StreamSupport;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.User;
import net.jojoaddison.repository.UserRepository;
import net.jojoaddison.security.jwt.TokenProvider;
import net.jojoaddison.web.rest.vm.LoginVM;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Integration tests for the {@link AuthenticateController} REST controller.
 */
@AutoConfigureWebTestClient(timeout = IntegrationTest.DEFAULT_TIMEOUT)
@IntegrationTest
class AuthenticateControllerIT {

    @Autowired
    private ObjectMapper om;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void testAuthorize() throws Exception {
        User user = new User();
        user.setLogin("user-jwt-controller");
        user.setEmail("user-jwt-controller@example.com");
        user.setActivated(true);
        user.setPassword(passwordEncoder.encode("test"));

        userRepository.save(user).block();

        LoginVM login = new LoginVM();
        login.setUsername("user-jwt-controller");
        login.setPassword("test");
        webTestClient
            .post()
            .uri("/api/authenticate")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(login))
            .exchange()
            .expectStatus()
            .isOk()
            .expectHeader()
            .valueMatches("Authorization", "Bearer .+")
            .expectBody()
            .jsonPath("$.id_token")
            .isNotEmpty();
    }

    @Test
    void theBrowserTokenCarriesTheOriginClaims() throws Exception {
        User user = new User();
        user.setLogin("user-jwt-origin");
        user.setEmail("user-jwt-origin@example.com");
        user.setActivated(true);
        user.setPassword(passwordEncoder.encode("test"));
        userRepository.save(user).block();

        LoginVM login = new LoginVM();
        login.setUsername("user-jwt-origin");
        login.setPassword("test");

        byte[] body = webTestClient
            .post()
            .uri("/api/authenticate")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(login))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .returnResult()
            .getResponseBody();

        String idToken = om.readTree(body).get("id_token").asString();
        JsonNode claims = om.readTree(new String(Base64.getUrlDecoder().decode(idToken.split("\\.")[1]), StandardCharsets.UTF_8));

        // Issuer and audience are not validated by default — see TokenOriginValidator — but they have to be present
        // and correct before validation can be switched on, and this is where a typo would otherwise sit unnoticed
        // until it locked every user out on the day someone enabled the validators.
        assertThat(claims.get("iss").asString()).isEqualTo(TokenProvider.ISSUER);

        // A single-valued `aud` serializes as a bare string rather than a one-element array — RFC 7519 allows both,
        // and Nimbus takes the shorter form. Accept either, so this survives the audience list changing length.
        JsonNode audience = claims.get("aud");
        assertThat(audience.isArray() ? audienceValues(audience) : List.of(audience.asString())).containsAll(TokenProvider.AUDIENCES);
    }

    /**
     * The browser token carries {@code uid} = {@code User.id}, and {@code sub} is still the login.
     *
     * <p>Both halves matter. {@code professionalservice} resolves the caller by matching {@code sub}
     * against {@code Profile.accountId} and every audit row in that database holds the same string,
     * so a change that moved {@code User.id} into {@code sub} would orphan all of them at once —
     * this asserts the claim was <em>added beside</em> the subject rather than substituted for it
     * (backlog.md item 48).
     */
    @Test
    void theBrowserTokenCarriesTheAccountUidBesideTheLogin() throws Exception {
        User user = new User();
        user.setLogin("user-jwt-uid");
        user.setEmail("user-jwt-uid@example.com");
        user.setActivated(true);
        user.setPassword(passwordEncoder.encode("test"));
        String uid = userRepository.save(user).block().getId();

        LoginVM login = new LoginVM();
        login.setUsername("user-jwt-uid");
        login.setPassword("test");

        byte[] body = webTestClient
            .post()
            .uri("/api/authenticate")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(login))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .returnResult()
            .getResponseBody();

        String idToken = om.readTree(body).get("id_token").asString();
        JsonNode claims = om.readTree(new String(Base64.getUrlDecoder().decode(idToken.split("\\.")[1]), StandardCharsets.UTF_8));

        assertThat(uid).isNotBlank().isNotEqualTo("user-jwt-uid");
        assertThat(claims.get(TokenProvider.UID_KEY).asString()).isEqualTo(uid);
        assertThat(claims.get("sub").asString()).isEqualTo("user-jwt-uid");
    }

    private static List<String> audienceValues(JsonNode audience) {
        return StreamSupport.stream(audience.spliterator(), false).map(JsonNode::asString).toList();
    }

    @Test
    void anUnactivatedAccountIsRejectedWithUnauthorizedRatherThan500() throws Exception {
        // Every new registration lands in this state until the activation link is clicked, so this
        // is the first thing a real user hits if they try to sign in too early. It used to answer
        // 500: UserNotActivatedException extends AuthenticationException, and only the
        // BadCredentials and UsernameNotFound subtypes were mapped, so it fell through to Spring's
        // default and read as the site being broken.
        User user = new User();
        user.setLogin("user-not-activated");
        user.setEmail("user-not-activated@example.com");
        user.setActivated(false);
        user.setPassword(passwordEncoder.encode("test"));
        userRepository.save(user).block();

        LoginVM login = new LoginVM();
        login.setUsername("user-not-activated");
        login.setPassword("test");
        webTestClient
            .post()
            .uri("/api/authenticate")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(login))
            .exchange()
            .expectStatus()
            .isUnauthorized();

        userRepository.delete(user).block();
    }

    @Test
    void anUnactivatedAccountLooksExactlyLikeAMissingOne() throws Exception {
        // The status has to match what a nonexistent account returns, or the difference becomes an
        // oracle: 500 for "exists but unactivated" against 401 for "no such user" told an anonymous
        // caller which logins are real. Both are 401 with the same generic detail.
        User user = new User();
        user.setLogin("user-not-activated-two");
        user.setEmail("user-not-activated-two@example.com");
        user.setActivated(false);
        user.setPassword(passwordEncoder.encode("test"));
        userRepository.save(user).block();

        LoginVM unactivated = new LoginVM();
        unactivated.setUsername("user-not-activated-two");
        unactivated.setPassword("wrong-password-entirely");
        byte[] unactivatedBody = webTestClient
            .post()
            .uri("/api/authenticate")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(unactivated))
            .exchange()
            .expectStatus()
            .isUnauthorized()
            .expectBody()
            .returnResult()
            .getResponseBodyContent();

        LoginVM missing = new LoginVM();
        missing.setUsername("no-such-account-at-all");
        missing.setPassword("wrong-password-entirely");
        byte[] missingBody = webTestClient
            .post()
            .uri("/api/authenticate")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(missing))
            .exchange()
            .expectStatus()
            .isUnauthorized()
            .expectBody()
            .returnResult()
            .getResponseBodyContent();

        assertThat(new String(unactivatedBody)).isEqualTo(new String(missingBody));

        userRepository.delete(user).block();
    }

    @Test
    void testAuthorizeWithRememberMe() throws Exception {
        User user = new User();
        user.setLogin("user-jwt-controller-remember-me");
        user.setEmail("user-jwt-controller-remember-me@example.com");
        user.setActivated(true);
        user.setPassword(passwordEncoder.encode("test"));

        userRepository.save(user).block();

        LoginVM login = new LoginVM();
        login.setUsername("user-jwt-controller-remember-me");
        login.setPassword("test");
        login.setRememberMe(true);
        webTestClient
            .post()
            .uri("/api/authenticate")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(login))
            .exchange()
            .expectStatus()
            .isOk()
            .expectHeader()
            .valueMatches("Authorization", "Bearer .+")
            .expectBody()
            .jsonPath("$.id_token")
            .isNotEmpty();
    }

    @Test
    void testAuthorizeFails() throws Exception {
        LoginVM login = new LoginVM();
        login.setUsername("wrong-user");
        login.setPassword("wrong password");
        webTestClient
            .post()
            .uri("/api/authenticate")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(login))
            .exchange()
            .expectStatus()
            .isUnauthorized()
            .expectHeader()
            .doesNotExist("Authorization")
            .expectBody()
            .jsonPath("$.id_token")
            .doesNotExist();
    }
}
