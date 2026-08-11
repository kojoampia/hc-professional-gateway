package net.jojoaddison.web.rest;

import net.jojoaddison.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The site's own origin has to be allowed, or nobody can sign in through it.
 *
 * <p>This exists because production shipped for six days with only the mobile schemes on
 * {@code jhipster.cors.allowed-origins}, and every check that was run said it was fine. Browsers
 * attach {@code Origin} to same-origin POSTs from fetch/XHR, so {@code CorsWebFilter} validates it
 * and answers 403 before the handler is reached; curl attaches nothing, so
 * {@code deploy.sh}'s authentication probe and MOB4's CORS gate both passed against a portal that
 * could not be logged into.
 *
 * <p>The gap was never the configuration alone — it was that nothing exercised the request a
 * browser actually sends. So these tests send the {@code Origin} header, and assert on 403
 * specifically rather than on success: credentials are irrelevant here, and a 401 means the request
 * reached authentication, which is the whole point.
 *
 * <p>The property is set explicitly because the test profile configures no CORS at all, which is
 * the other half of why this was invisible.
 */
@TestPropertySource(
    // Indexed form on purpose: a comma-separated value does not reliably bind to the List on
    // Spring's CorsConfiguration from @TestPropertySource, and the failure is silent — the list ends
    // up holding one entry that is the whole string, so every origin is refused and the test looks
    // like the fix does not work.
    properties = {
        "jhipster.cors.allowed-origins[0]=https://professional.abofonsa.com",
        "jhipster.cors.allowed-origins[1]=capacitor://localhost",
        "jhipster.cors.allowed-methods[0]=GET",
        "jhipster.cors.allowed-methods[1]=POST",
        "jhipster.cors.allowed-methods[2]=OPTIONS",
        "jhipster.cors.allowed-headers[0]=*",
    }
)
@SpringBootTest(
    classes = { net.jojoaddison.HcProfessionalGatewayApp.class, net.jojoaddison.config.JacksonConfiguration.class },
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@net.jojoaddison.config.EmbeddedMongo
@net.jojoaddison.config.EmbeddedKafka
class CorsBrowserOriginIT {

    @org.springframework.boot.test.web.server.LocalServerPort
    private int port;

    private static final String BODY = "{\"username\":\"someone\",\"password\":\"whatever\",\"rememberMe\":false}";

    private WebTestClient webTestClient;

    @org.junit.jupiter.api.BeforeEach
    void bindToTheRealPort() {
        webTestClient = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void aBrowserSignInFromTheSiteItselfIsNotBlocked() {
        webTestClient
            .post()
            .uri("/api/authenticate")
            .header("Origin", "https://professional.abofonsa.com")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(BODY)
            .exchange()
            .expectStatus()
            .value(
                status ->
                    org.assertj.core.api.Assertions.assertThat(status)
                        .as("the portal's own origin must reach authentication, not be refused by CORS")
                        .isNotEqualTo(403)
            );
    }

    @Test
    void aMobileClientIsStillAllowed() {
        // MOB4's reason for configuring CORS in the first place; fixing the browser must not cost it.
        webTestClient
            .post()
            .uri("/api/authenticate")
            .header("Origin", "capacitor://localhost")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(BODY)
            .exchange()
            .expectStatus()
            .value(status -> org.assertj.core.api.Assertions.assertThat(status).isNotEqualTo(403));
    }

    @Test
    void anUnrelatedOriginIsStillRefused() {
        // The allow-list has to keep meaning something, or this "fix" would just be disabling CORS.
        webTestClient
            .post()
            .uri("/api/authenticate")
            .header("Origin", "https://not-our-site.example.com")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(BODY)
            .exchange()
            .expectStatus()
            .isForbidden();
    }

    @Test
    void thePreflightABrowserSendsBeforeAPostSucceeds() {
        // Chrome sends this OPTIONS first for a JSON POST. If it fails the real request never runs,
        // and the failure surfaces in the console rather than in any server-side check.
        webTestClient
            .options()
            .uri("/api/authenticate")
            .header("Origin", "https://professional.abofonsa.com")
            .header("Access-Control-Request-Method", "POST")
            .header("Access-Control-Request-Headers", "content-type")
            .exchange()
            .expectStatus()
            .isOk()
            .expectHeader()
            .valueEquals("Access-Control-Allow-Origin", "https://professional.abofonsa.com");
    }
}
