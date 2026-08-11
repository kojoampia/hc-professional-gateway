package net.jojoaddison.web.rest;

import static org.assertj.core.api.Assertions.assertThat;

import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.User;
import net.jojoaddison.repository.UserRepository;
import net.jojoaddison.web.rest.vm.LoginVM;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;
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
