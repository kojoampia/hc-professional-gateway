package net.jojoaddison.security.jwt;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import net.jojoaddison.config.SecurityConfiguration;
import net.jojoaddison.config.SecurityJwtConfiguration;
import net.jojoaddison.config.WebConfigurer;
import net.jojoaddison.management.SecurityMetersService;
import net.jojoaddison.security.jwt.TokenProvider;
import net.jojoaddison.service.RefreshTokenService;
import net.jojoaddison.web.rest.AuthenticateController;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tech.jhipster.config.JHipsterProperties;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import(
    {
        JHipsterProperties.class,
        WebConfigurer.class,
        SecurityConfiguration.class,
        SecurityJwtConfiguration.class,
        SecurityMetersService.class,
        JwtAuthenticationTestUtils.class,
        // AuthenticateController mints through TokenProvider since MOB3, so the slice needs it.
        // It only depends on JwtEncoder, which SecurityJwtConfiguration above already supplies.
        TokenProvider.class,
    }
)
// RefreshTokenService is the controller's other new dependency, but it reaches MongoDB and this
// slice exercises nothing but JWT validation on GET /api/authenticate. Mocking it keeps the slice
// a slice — pulling in a real one would drag Mongo into a test that has no business needing it.
@MockitoBean(types = { RefreshTokenService.class })
@WebFluxTest(
    controllers = { AuthenticateController.class },
    properties = {
        "jhipster.security.authentication.jwt.base64-secret=fd54a45s65fds737b9aafcb3412e07ed99b267f33413274720ddbb7f6c5e64e9f14075f2d7ed041592f0b7657baf8",
        "jhipster.security.authentication.jwt.token-validity-in-seconds=60000",
    }
)
@ComponentScan({})
public @interface AuthenticationIntegrationTest {
}
