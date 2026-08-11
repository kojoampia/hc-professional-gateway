package net.jojoaddison.web.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.User;
import net.jojoaddison.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Integration tests for {@code GET /api/register/login-available}.
 *
 * <p>The endpoint serves the registration form, so the property that matters most is that it works
 * <em>unauthenticated</em>. It sits under {@code /api/register/}, and the {@code permitAll} matcher
 * for {@code /api/register} is an exact path match that does not cover it — so without its own rule
 * it falls through to {@code /api/**} and 401s. That is a silent failure in the sense that the
 * feature simply never works for the only callers it has, which is why it is asserted here.
 */
@AutoConfigureWebTestClient(timeout = IntegrationTest.DEFAULT_TIMEOUT)
@IntegrationTest
class LoginAvailabilityResourceIT {

    private static final String PATH = "/api/register/login-available";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private WebTestClient webTestClient;

    private User persistLogin(String login) {
        User user = new User();
        user.setLogin(login);
        user.setPassword(passwordEncoder.encode("irrelevant-but-valid-length-password"));
        user.setEmail(login + "@example.com");
        user.setActivated(true);
        user.setLangKey("en");
        return userRepository.save(user).block();
    }

    @Test
    void anAnonymousCallerCanCheckAFreeLogin() {
        webTestClient
            .get()
            .uri(uriBuilder -> uriBuilder.path(PATH).queryParam("login", "definitely-unclaimed-name").build())
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .jsonPath("$.login")
            .isEqualTo("definitely-unclaimed-name")
            .jsonPath("$.available")
            .isEqualTo(true)
            .jsonPath("$.suggestions")
            .isEmpty();
    }

    @Test
    void aTakenLoginComesBackUnavailableWithUsableAlternatives() {
        User taken = persistLogin("availability-taken");
        try {
            LoginAvailabilityResponse body = webTestClient
                .get()
                .uri(uriBuilder -> uriBuilder.path(PATH).queryParam("login", "availability-taken").build())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(LoginAvailabilityResponse.class)
                .returnResult()
                .getResponseBody();

            assertThat(body.available()).isFalse();
            assertThat(body.suggestions()).isNotEmpty();
            // Every suggestion must itself be free — an offer the server would reject is worse than none.
            for (String suggestion : body.suggestions()) {
                assertThat(userRepository.findOneByLogin(suggestion).blockOptional())
                    .as("suggested login %s must not already exist", suggestion)
                    .isEmpty();
            }
        } finally {
            userRepository.delete(taken).block();
        }
    }

    @Test
    void theSuggestionLadderSkipsNamesThatAreAlreadyTaken() {
        User base = persistLogin("ladder");
        User first = persistLogin("ladder1");
        try {
            LoginAvailabilityResponse body = webTestClient
                .get()
                .uri(uriBuilder -> uriBuilder.path(PATH).queryParam("login", "ladder").build())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(LoginAvailabilityResponse.class)
                .returnResult()
                .getResponseBody();

            assertThat(body.available()).isFalse();
            assertThat(body.suggestions()).doesNotContain("ladder1");
            assertThat(body.suggestions()).startsWith("ladder2");
        } finally {
            userRepository.delete(base).block();
            userRepository.delete(first).block();
        }
    }

    @Test
    void theCheckIsCaseInsensitiveBecauseLoginsAreStoredLowerCased() {
        // User.setLogin lower-cases on save. Reporting "MixedCase" free while "mixedcase" exists
        // would have the form approve a name registration then rejects.
        User taken = persistLogin("MixedCase");
        try {
            webTestClient
                .get()
                .uri(uriBuilder -> uriBuilder.path(PATH).queryParam("login", "MixedCase").build())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.available")
                .isEqualTo(false)
                .jsonPath("$.login")
                .isEqualTo("mixedcase");
        } finally {
            userRepository.delete(taken).block();
        }
    }

    @Test
    void aMalformedLoginIsRejectedRatherThanQueried() {
        webTestClient
            .get()
            .uri(uriBuilder -> uriBuilder.path(PATH).queryParam("login", "not a valid login!").build())
            .exchange()
            .expectStatus()
            .isBadRequest();
    }

    @Test
    void theResponseNeverCarriesAnythingAboutTheAccountHoldingTheLogin() {
        // The endpoint is an anonymous enumeration oracle by necessity; it must leak exactly one
        // bit and nothing else. A future change that returned, say, the holder's email to render a
        // nicer message would turn a name check into a directory.
        User taken = persistLogin("privacy-probe");
        try {
            String body = new String(
                webTestClient
                    .get()
                    .uri(uriBuilder -> uriBuilder.path(PATH).queryParam("login", "privacy-probe").build())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .returnResult()
                    .getResponseBodyContent()
            );

            assertThat(body).doesNotContain("privacy-probe@example.com");
            assertThat(body).doesNotContain("password");
            assertThat(body).doesNotContain(taken.getId());
        } finally {
            userRepository.delete(taken).block();
        }
    }

    /** Minimal mirror of the response body, so the test does not depend on the DTO's setters. */
    record LoginAvailabilityResponse(String login, boolean available, List<String> suggestions) {}
}
