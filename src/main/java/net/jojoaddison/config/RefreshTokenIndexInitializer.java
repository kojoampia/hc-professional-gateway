package net.jojoaddison.config;

import java.time.Duration;
import net.jojoaddison.domain.RefreshToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;
import tech.jhipster.config.JHipsterConstants;

/**
 * Creates the indexes the {@link RefreshToken} collection needs.
 *
 * <p><strong>Why this exists rather than {@code @Indexed} annotations:</strong> this project does
 * not set {@code spring.data.mongodb.auto-index-creation}, and Spring Data MongoDB defaults it to
 * {@code false}. Annotating the domain class would compile, read convincingly, and create nothing —
 * expired tokens would accumulate forever with no visible failure. Creating them explicitly here
 * makes the intent executable.
 *
 * <p>Runs as an idempotent {@link ApplicationRunner}, matching {@code InitialSetupMigration}.
 * {@code ensureIndex} is a no-op when the index already exists with the same definition.
 */
@Component
@Profile("!" + JHipsterConstants.SPRING_PROFILE_TEST)
public class RefreshTokenIndexInitializer implements ApplicationRunner {

    private final Logger log = LoggerFactory.getLogger(RefreshTokenIndexInitializer.class);

    private final ReactiveMongoTemplate mongoTemplate;

    public RefreshTokenIndexInitializer(ReactiveMongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        var indexOps = mongoTemplate.indexOps(RefreshToken.class);

        indexOps
            // TTL index: Mongo reaps rows once expires_at passes, so nothing schedules a cleanup.
            // Revoked rows have their expires_at pulled forward to a short retention window instead
            // of being deleted, so a replay is still detectable for a while after revocation.
            .ensureIndex(new Index().on("expires_at", org.springframework.data.domain.Sort.Direction.ASC).expire(Duration.ZERO))
            .then(indexOps.ensureIndex(new Index().on("family_id", org.springframework.data.domain.Sort.Direction.ASC)))
            .then(indexOps.ensureIndex(new Index().on("login", org.springframework.data.domain.Sort.Direction.ASC)))
            .doOnSuccess(name -> log.debug("refresh_token indexes ensured"))
            .doOnError(error -> log.error("Could not create refresh_token indexes — expired tokens will not be reaped", error))
            // Never let index creation stop the application from starting.
            .onErrorComplete()
            .subscribe();
    }
}
