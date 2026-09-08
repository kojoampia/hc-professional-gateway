package net.jojoaddison.web.rest;

import static org.assertj.core.api.Assertions.assertThat;

import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.User;
import net.jojoaddison.repository.UserRepository;
import net.jojoaddison.security.AuthoritiesConstants;
import net.jojoaddison.service.dto.UserDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Integration tests for the {@link PublicUserResource} REST controller.
 *
 * <p>The class-level {@code @WithMockUser} is <b>ADMIN</b>, and since backlog item 55 that is load
 * bearing rather than incidental: this endpoint returns {@code {id, login}} for every activated
 * account — the complete login-to-{@code User.id} table — and is now admin-only. The class ran as
 * ADMIN before the gate existed too, which is exactly why it could not have caught its absence, so
 * {@link #aNonAdminCannotEnumerateTheLoginToIdTable()} carries its own authorities.
 */
@AutoConfigureWebTestClient(timeout = IntegrationTest.DEFAULT_TIMEOUT)
@WithMockUser(authorities = AuthoritiesConstants.ADMIN)
@IntegrationTest
class PublicUserResourceIT {

    private static final String DEFAULT_LOGIN = "johndoe";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WebTestClient webTestClient;

    private User user;

    @BeforeEach
    public void initTest() {
        user = UserResourceIT.initTestUser(userRepository);
    }

    /**
     * The gate, asserted from the outside. Until 2026-09-08 there was no rule for {@code /api/users},
     * so it fell through to {@code .pathMatchers("/api/**").authenticated()} and any authenticated
     * caller could read the mapping — an applicant holding bare {@code ROLE_USER} among them.
     *
     * <p>Why that mattered: the mapping is what turns a client-supplied {@code accountId} from
     * guessable into targetable. See backlog items 53 and 54.
     *
     * <p>{@code ROLE_USER} rather than a clinical role on purpose — it is the weakest authority the
     * estate issues and the one an applicant mid-onboarding actually holds.
     */
    @Test
    @WithMockUser(authorities = AuthoritiesConstants.USER)
    void aNonAdminCannotEnumerateTheLoginToIdTable() {
        userRepository.save(user).block();

        webTestClient.get().uri("/api/users?sort=id,desc").accept(MediaType.APPLICATION_JSON).exchange().expectStatus().isForbidden();
    }

    /**
     * The pattern pair, not a duplicate of the above: {@code "/api/users/**"} does not match
     * {@code "/api/users"} itself in Spring, so a rule written with only one of them leaves the other
     * on the catch-all. This asserts the sub-path half.
     */
    @Test
    @WithMockUser(authorities = AuthoritiesConstants.USER)
    void theGateCoversSubPathsAndNotJustTheCollection() {
        webTestClient.get().uri("/api/users/" + DEFAULT_LOGIN).accept(MediaType.APPLICATION_JSON).exchange().expectStatus().isForbidden();
    }

    @Test
    void getAllPublicUsers() {
        // Initialize the database
        userRepository.save(user).block();

        // Get all the users
        UserDTO foundUser = webTestClient
            .get()
            .uri("/api/users?sort=id,desc")
            .accept(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus()
            .isOk()
            .expectHeader()
            .contentType(MediaType.APPLICATION_JSON)
            .returnResult(UserDTO.class)
            .getResponseBody()
            .blockFirst();

        assertThat(foundUser.getLogin()).isEqualTo(DEFAULT_LOGIN);
    }
}
