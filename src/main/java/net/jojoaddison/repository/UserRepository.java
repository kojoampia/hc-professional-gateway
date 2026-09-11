package net.jojoaddison.repository;

import java.time.Instant;
import net.jojoaddison.domain.User;
import org.springframework.data.domain.*;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Spring Data MongoDB reactive repository for the {@link User} entity.
 */
@Repository
public interface UserRepository extends ReactiveMongoRepository<User, String> {
    Mono<User> findOneByActivationKey(String activationKey);
    Flux<User> findAllByActivatedIsFalseAndActivationKeyIsNotNullAndCreatedDateBefore(Instant dateTime);
    Mono<User> findOneByResetKey(String resetKey);
    Mono<User> findOneByEmailIgnoreCase(String email);
    Mono<User> findOneByLogin(String login);

    /**
     * Every login beginning with {@code prefix}, used to suggest an available alternative.
     * <p>
     * Spring Data derives this to a prefix-anchored regex, which the index on {@code login} can
     * serve. It exists so suggesting takes <em>one</em> query rather than one per candidate: the
     * caller reads the neighbourhood of taken logins once and picks from it in memory.
     */
    Flux<User> findAllByLoginStartingWith(String prefix);

    Flux<User> findAllByIdNotNull(Pageable pageable);

    Flux<User> findAllByIdNotNullAndActivatedIsTrue(Pageable pageable);

    Mono<Long> count();

    /**
     * The activated and not-yet-activated halves of the account population, for the registration gauges — see
     * {@code net.jojoaddison.management.RegistrationMetersService} and {@code docs/backlog.md} item 96.
     * <p>
     * Two independent counts rather than a total minus one of them: derived counts cannot disagree into a negative
     * number if an account is created or deleted between the two queries, whereas a subtraction can.
     */
    Mono<Long> countByActivatedIsTrue();

    Mono<Long> countByActivatedIsFalse();
}
