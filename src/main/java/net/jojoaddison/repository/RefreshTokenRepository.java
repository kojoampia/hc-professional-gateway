package net.jojoaddison.repository;

import java.time.Instant;
import net.jojoaddison.domain.RefreshToken;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

/**
 * Spring Data MongoDB reactive repository for the {@link RefreshToken} document.
 */
@Repository
public interface RefreshTokenRepository extends ReactiveMongoRepository<RefreshToken, String> {
    Flux<RefreshToken> findAllByFamilyId(String familyId);

    Flux<RefreshToken> findAllByLogin(String login);

    /**
     * Live sessions for a login, oldest first — used to cap concurrent sessions and to render a
     * "signed-in devices" list.
     */
    Flux<RefreshToken> findAllByLoginAndRevokedAtIsNullAndRotatedAtIsNullAndExpiresAtAfterOrderByIssuedAtAsc(String login, Instant now);
}
