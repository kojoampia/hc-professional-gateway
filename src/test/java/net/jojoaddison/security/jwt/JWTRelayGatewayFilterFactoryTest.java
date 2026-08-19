package net.jojoaddison.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * The filter that puts the caller's token on the request the gateway forwards.
 *
 * <p><b>This runs on every proxied request.</b> {@code application.yml} registers {@code JWTRelay}
 * under {@code default-filters}, which applies to all routes rather than to any one of them — the
 * static {@code professionalservice}, {@code patientservice} and {@code adminservice} routes in
 * {@code deploy/prod-server/compose.yml} set {@code StripPrefix} and {@code PreserveHostHeader} and
 * inherit this. So it sits in front of the cross-stack reads as well as this stack's own, and until
 * now nothing exercised its body at all.
 *
 * <p>Tested directly rather than through a route: the class is a filter factory, and standing up a
 * gateway with a downstream to observe would prove the framework wires filters — which it does —
 * rather than what this one decides. The decisions are the point, and there are three: no header,
 * a usable header, and a header that is present but malformed.
 *
 * <p>Subscribed with {@code block()} rather than StepVerifier: {@code reactor-test} is not on this
 * project's test classpath, and pulling in a dependency to assert that a {@code Mono<Void>}
 * completed would be a poor trade. {@code block()} propagates the error signal unchanged, which
 * is the only reactive property these tests need.
 */
class JWTRelayGatewayFilterFactoryTest {

    private static final String VALID_TOKEN = "header.payload.signature";

    private ReactiveJwtDecoder jwtDecoder;
    private GatewayFilter filter;
    private GatewayFilterChain chain;
    private ServerWebExchange forwarded;

    @BeforeEach
    void setUp() {
        jwtDecoder = mock(ReactiveJwtDecoder.class);
        filter = new JWTRelayGatewayFilterFactory(jwtDecoder).apply(new Object());
        chain = mock(GatewayFilterChain.class);
        forwarded = null;
        // Capture whatever the filter hands onward: the exchange is mutated rather than returned,
        // so the assertion has to be made on what the chain actually receives.
        when(chain.filter(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            forwarded = invocation.getArgument(0);
            return Mono.empty();
        });
    }

    private static Jwt decoded() {
        return Jwt.withTokenValue(VALID_TOKEN)
            .header("alg", "HS512")
            .subject("ama.serwaa")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(300))
            .claims(claims -> claims.put("auth", "ROLE_DOCTOR"))
            .build();
    }

    /**
     * No Authorization header: the request passes through untouched.
     *
     * <p>This is the branch that decides whether unauthenticated traffic reaches downstream services
     * at all. It has to stay a pass-through rather than a rejection — the gateway proxies public
     * endpoints too, and turning this into a 401 would close them without anything in a route
     * definition saying so. The decoder must not be consulted; asking it to decode nothing is how
     * this path would start throwing.
     */
    @Test
    void letsAnAnonymousRequestThroughWithoutConsultingTheDecoder() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/services/adminservice/api/x"));

        filter.filter(exchange, chain).block();

        verify(chain).filter(org.mockito.ArgumentMatchers.any());
        verify(jwtDecoder, never()).decode(anyString());
        assertThat(forwarded.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isNull();
    }

    /**
     * The ordinary path: a valid token is decoded and re-attached to the forwarded request.
     *
     * <p>The re-attachment is not a no-op even though the value is unchanged. The filter rebuilds
     * the header through {@code setBearerAuth} on a mutated exchange, and that mutation is what the
     * downstream service receives — a change that dropped it would leave this stack's own routes
     * working (they share a security context) while every cross-stack call arrived unauthenticated.
     */
    @Test
    void decodesAndRelaysAValidBearerTokenDownstream() {
        when(jwtDecoder.decode(VALID_TOKEN)).thenReturn(Mono.just(decoded()));
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/services/adminservice/api/professionals/me/earnings").header(
                HttpHeaders.AUTHORIZATION,
                "Bearer " + VALID_TOKEN
            )
        );

        filter.filter(exchange, chain).block();

        verify(jwtDecoder).decode(VALID_TOKEN);
        assertThat(forwarded.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer " + VALID_TOKEN);
    }

    /**
     * A token the decoder rejects must not reach the downstream service.
     *
     * <p>An expired or wrongly-signed token is the case the shared platform key makes real: three
     * stacks accept each other's tokens, so a token that is merely stale looks structurally perfect.
     * The error has to propagate rather than be swallowed into a pass-through.
     */
    @Test
    void doesNotForwardWhenTheDecoderRejectsTheToken() {
        when(jwtDecoder.decode(VALID_TOKEN)).thenReturn(Mono.error(new JwtException("expired")));
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/services/patientservice/api/profiles").header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN)
        );

        assertThatThrownBy(() -> filter.filter(exchange, chain).block()).isInstanceOf(JwtException.class);

        verify(chain, never()).filter(org.mockito.ArgumentMatchers.any());
    }

    /**
     * <b>A malformed Authorization header throws, and the throw is not reactive.</b>
     *
     * <p>{@code extractToken} raises {@code IllegalArgumentException} from inside {@code apply}'s
     * lambda, before any {@code Mono} is constructed — so it propagates synchronously out of
     * {@code filter()} rather than as an error signal. That distinction is the finding, and it is
     * why this is pinned rather than merely covered: a synchronous throw from a gateway filter is
     * not translated into a 401 by anything downstream of it, and surfaces to the caller as a
     * <b>500</b>. A client sending "Bearer" with no token, or an unsupported scheme, gets "internal
     * server error" for what is a malformed request.
     *
     * <p>These assertions describe what the code does today, deliberately — if the behaviour is
     * changed to a 400 or a 401, this test should fail and be updated, which is the point of
     * writing it down.
     */
    @Test
    void throwsSynchronouslyOnAMalformedAuthorizationHeader() {
        for (String malformed : new String[] { "Bearer", "Bearer ", "Basic dXNlcjpwYXNz", "token-with-no-scheme", "" }) {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/services/professionalservice/api/x").header(HttpHeaders.AUTHORIZATION, malformed)
            );

            assertThatThrownBy(() -> filter.filter(exchange, chain))
                .as("Authorization: '%s'", malformed)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid token in Authorization header");
        }
        verify(chain, never()).filter(org.mockito.ArgumentMatchers.any());
        verify(jwtDecoder, never()).decode(anyString());
    }

    /**
     * The boundary in {@code extractToken}: the header must be longer than the scheme itself.
     *
     * <p>{@code "Bearer "} is exactly seven characters, so {@code length() > 7} is what separates a
     * header carrying a token from one carrying none. A single-character token is the shortest input
     * that must survive it — an off-by-one here would reject real tokens only at lengths nobody
     * tests with.
     */
    @Test
    void acceptsTheShortestTokenThatIsLongerThanTheSchemeItself() {
        when(jwtDecoder.decode("x")).thenReturn(Mono.just(Jwt.withTokenValue("x").header("alg", "none").claim("sub", "s").build()));
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/services/professionalservice/api/x").header(HttpHeaders.AUTHORIZATION, "Bearer x")
        );

        filter.filter(exchange, chain).block();

        verify(jwtDecoder).decode("x");
        assertThat(forwarded.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer x");
    }

    /** The factory is a Spring component with a no-arg config; {@code apply} must tolerate it. */
    @Test
    void appliesWithAnEmptyConfiguration() {
        assertThat(new JWTRelayGatewayFilterFactory(jwtDecoder).apply((Object) null)).isNotNull();
    }
}
