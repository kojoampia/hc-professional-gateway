package net.jojoaddison.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import net.jojoaddison.config.Constants;
import net.jojoaddison.domain.Authority;
import net.jojoaddison.domain.User;
import net.jojoaddison.repository.AuthorityRepository;
import net.jojoaddison.repository.UserRepository;
import net.jojoaddison.security.AuthoritiesConstants;
import net.jojoaddison.security.SecurityUtils;
import net.jojoaddison.service.dto.AdminUserDTO;
import net.jojoaddison.service.dto.UserDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tech.jhipster.security.RandomUtil;

/**
 * Service class for managing users.
 */
@Service
public class UserService {

    private final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;

    private final PasswordEncoder passwordEncoder;

    private final AuthorityRepository authorityRepository;

    private final net.jojoaddison.broker.RegistrationEventPublisher registrationEventPublisher;

    public UserService(
        UserRepository userRepository,
        PasswordEncoder passwordEncoder,
        AuthorityRepository authorityRepository,
        net.jojoaddison.broker.RegistrationEventPublisher registrationEventPublisher
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authorityRepository = authorityRepository;
        this.registrationEventPublisher = registrationEventPublisher;
    }

    public Mono<User> activateRegistration(String key) {
        log.debug("Activating user for activation key {}", key);
        return userRepository
            .findOneByActivationKey(key)
            .flatMap(user -> {
                // activate given user for the registration key.
                user.setActivated(true);
                user.setActivationKey(null);
                return saveUser(user);
            })
            .doOnNext(user -> log.debug("Activated user: {}", user));
    }

    public Mono<User> completePasswordReset(String newPassword, String key) {
        log.debug("Reset user password for reset key {}", key);
        return userRepository
            .findOneByResetKey(key)
            .filter(user -> user.getResetDate().isAfter(Instant.now().minus(1, ChronoUnit.DAYS)))
            .publishOn(Schedulers.boundedElastic())
            .map(user -> {
                user.setPassword(passwordEncoder.encode(newPassword));
                user.setResetKey(null);
                user.setResetDate(null);
                return user;
            })
            .flatMap(this::saveUser);
    }

    public Mono<User> requestPasswordReset(String mail) {
        return userRepository
            .findOneByEmailIgnoreCase(mail)
            .filter(User::isActivated)
            .publishOn(Schedulers.boundedElastic())
            .map(user -> {
                user.setResetKey(RandomUtil.generateResetKey());
                user.setResetDate(Instant.now());
                return user;
            })
            .flatMap(this::saveUser);
    }

    public Mono<User> registerUser(AdminUserDTO userDTO, String password) {
        return userRepository
            .findOneByLogin(userDTO.getLogin().toLowerCase())
            .flatMap(existingUser -> {
                if (!existingUser.isActivated()) {
                    return userRepository.delete(existingUser);
                } else {
                    return Mono.error(new UsernameAlreadyUsedException());
                }
            })
            .then(userRepository.findOneByEmailIgnoreCase(userDTO.getEmail()))
            .flatMap(existingUser -> {
                if (!existingUser.isActivated()) {
                    return userRepository.delete(existingUser);
                } else {
                    return Mono.error(new EmailAlreadyUsedException());
                }
            })
            .publishOn(Schedulers.boundedElastic())
            .then(
                Mono.fromCallable(() -> {
                    User newUser = new User();
                    String encryptedPassword = passwordEncoder.encode(password);
                    newUser.setLogin(userDTO.getLogin().toLowerCase());
                    // new user gets initially a generated password
                    newUser.setPassword(encryptedPassword);
                    newUser.setFirstName(userDTO.getFirstName());
                    newUser.setLastName(userDTO.getLastName());
                    if (userDTO.getEmail() != null) {
                        newUser.setEmail(userDTO.getEmail().toLowerCase());
                    }
                    newUser.setImageUrl(userDTO.getImageUrl());
                    newUser.setLangKey(userDTO.getLangKey());
                    // new user is not active
                    newUser.setActivated(false);
                    // new user gets registration key
                    newUser.setActivationKey(RandomUtil.generateActivationKey());
                    return newUser;
                })
            )
            .flatMap(newUser -> {
                Set<Authority> authorities = new HashSet<>();
                return authorityRepository
                    .findById(AuthoritiesConstants.USER)
                    .map(authorities::add)
                    .thenReturn(newUser)
                    .doOnNext(user -> user.setAuthorities(authorities))
                    .flatMap(this::saveUser)
                    .doOnNext(user -> log.debug("Created Information for User: {}", user));
            });
    }

    /**
     * The administrator-created account — {@code POST /api/admin/users}, the invitation path.
     *
     * <h2>⛔ {@code ROLE_USER} is appended whatever the body asks for (F7)</h2>
     *
     * <p>{@code profile.md} § "User (account)": <i>"Always append {@code ROLE_USER} by default in the
     * gateway."</i> <b>{@link #registerUser} did and this did not.</b> Measured:
     * {@code AuthoritiesConstants.USER} had exactly two occurrences in {@code src/main} and neither
     * was here, so an account created by invitation held only the authorities the administrator
     * happened to type — and an invitation naming none produced an account with an <em>empty</em>
     * authority set, which is not the same thing as an applicant.
     *
     * <p><b>Why that matters rather than being a tidiness point:</b> every {@code .authenticated()}
     * rule in this estate serves a role-less caller, but {@code ROLE_USER} is what the surfaces
     * <em>positively</em> name — {@code web/}'s shell routes on the authority list, and the
     * onboarding island exists for a holder of exactly this one. "Append" is also the operative
     * word: it is added <em>to</em> whatever the administrator asked for, never instead of it, so an
     * invitation that grants {@code ROLE_NURSE} produces both.
     *
     * <p>It is a {@link java.util.Set} of {@link Authority} keyed on name, and
     * {@code authorityRepository.findById} returns the same row for a duplicate request, so naming
     * {@code ROLE_USER} explicitly in the body cannot produce it twice.
     */
    public Mono<User> createUser(AdminUserDTO userDTO) {
        User user = new User();
        user.setLogin(userDTO.getLogin().toLowerCase());
        user.setFirstName(userDTO.getFirstName());
        user.setLastName(userDTO.getLastName());
        if (userDTO.getEmail() != null) {
            user.setEmail(userDTO.getEmail().toLowerCase());
        }
        user.setImageUrl(userDTO.getImageUrl());
        if (userDTO.getLangKey() == null) {
            user.setLangKey(Constants.DEFAULT_LANGUAGE); // default language
        } else {
            user.setLangKey(userDTO.getLangKey());
        }
        // ROLE_USER is appended to whatever the invitation asked for, never instead of it (F7).
        // Built as a LinkedHashSet so a body that names ROLE_USER itself does not request the same
        // row twice — and so the default is in the SAME stream as the requested ones, rather than
        // added afterwards where a later edit to the stream could drop it.
        Set<String> requested = new LinkedHashSet<>(userDTO.getAuthorities() != null ? userDTO.getAuthorities() : new HashSet<>());
        requested.add(AuthoritiesConstants.USER);
        return Flux.fromIterable(requested)
            .flatMap(authorityRepository::findById)
            .doOnNext(authority -> user.getAuthorities().add(authority))
            .then(Mono.just(user))
            .publishOn(Schedulers.boundedElastic())
            .map(newUser -> {
                String encryptedPassword = passwordEncoder.encode(RandomUtil.generatePassword());
                newUser.setPassword(encryptedPassword);
                newUser.setResetKey(RandomUtil.generateResetKey());
                newUser.setResetDate(Instant.now());
                newUser.setActivated(true);
                return newUser;
            })
            .flatMap(this::saveUser)
            .doOnNext(user1 -> log.debug("Created Information for User: {}", user1));
    }

    /**
     * Update all information for a specific user, and return the modified user.
     *
     * @param userDTO user to update.
     * @return updated user.
     */
    public Mono<AdminUserDTO> updateUser(AdminUserDTO userDTO) {
        return userRepository
            .findById(userDTO.getId())
            .flatMap(user -> {
                user.setLogin(userDTO.getLogin().toLowerCase());
                user.setFirstName(userDTO.getFirstName());
                user.setLastName(userDTO.getLastName());
                if (userDTO.getEmail() != null) {
                    user.setEmail(userDTO.getEmail().toLowerCase());
                }
                user.setImageUrl(userDTO.getImageUrl());
                user.setActivated(userDTO.isActivated());
                user.setLangKey(userDTO.getLangKey());
                Set<Authority> managedAuthorities = user.getAuthorities();
                managedAuthorities.clear();
                return Flux.fromIterable(userDTO.getAuthorities())
                    .flatMap(authorityRepository::findById)
                    .map(managedAuthorities::add)
                    .then(Mono.just(user));
            })
            .flatMap(this::saveUser)
            .doOnNext(user -> log.debug("Changed Information for User: {}", user))
            .map(AdminUserDTO::new);
    }

    public Mono<Void> deleteUser(String login) {
        return userRepository
            .findOneByLogin(login)
            .flatMap(user -> userRepository.delete(user).thenReturn(user))
            .doOnNext(user -> log.debug("Deleted User: {}", user))
            .then();
    }

    /**
     * Update basic information (first name, last name, email, language) for the current user.
     *
     * @param firstName first name of user.
     * @param lastName  last name of user.
     * @param email     email id of user.
     * @param langKey   language key.
     * @param imageUrl  image URL of user.
     * @return a completed {@link Mono}.
     */
    public Mono<Void> updateUser(String firstName, String lastName, String email, String langKey, String imageUrl) {
        return SecurityUtils.getCurrentUserLogin()
            .flatMap(userRepository::findOneByLogin)
            .flatMap(user -> {
                user.setFirstName(firstName);
                user.setLastName(lastName);
                if (email != null) {
                    user.setEmail(email.toLowerCase());
                }
                user.setLangKey(langKey);
                user.setImageUrl(imageUrl);
                return saveUser(user);
            })
            .doOnNext(user -> log.debug("Changed Information for User: {}", user))
            .then();
    }

    /**
     * The write every {@code User} change <b>that goes through this service</b> passes through — and,
     * since backlog.md row 230, the one place {@code AccountDetailsUpdated} is published from.
     *
     * <h2>⛔ It is NOT the only writer of a {@code User} row, and this javadoc claimed it was</h2>
     *
     * <p>It read "the one write every {@code User} change in this gateway passes through" and called
     * itself "the single funnel". <b>It is the only writer that goes via {@code userRepository}</b> —
     * eight callers, all in this class — and
     * {@code config/dbmigrations/InitialSetupMigration.saveUserIfMissing} writes {@code User} rows
     * with {@code template.save(...)}, bypassing this method, and therefore the event, entirely.
     *
     * <p>⚠ <b>So every seeded demo clinician reads {@code steps.account: false} permanently, for two
     * independent reasons</b>: no frame is ever published for them, and {@code createProfessional}
     * never calls {@code setImageUrl}, so the verdict would be {@code false} even if one were. That is
     * cosmetic — nothing gates on step 1, by {@code api/}'s {@code OnboardingProgressDTO.Steps} — but
     * it is what the quality stack and any training-manual screenshot will show, so it is written down
     * here rather than found there.
     *
     * <p>⭐ <b>The seeder deliberately does not publish, and should not start.</b> Three reasons, in
     * order of weight. It is an {@code ApplicationRunner}, so it would publish ten frames on every
     * boot of every environment — including each test context — onto a topic hc-admin consumes, for
     * accounts that are fixtures. It runs at startup, before the broker is necessarily reachable, and
     * {@code StreamBridge}'s first {@code send} for a destination blocks for up to
     * {@code default.api.timeout.ms} against an absent one, which is the hazard {@code api/}'s
     * {@code DomainEventPublisher.entityChangeExecutor} exists to keep off exactly this kind of path.
     * And the verdict would be {@code false} regardless, so it would buy nothing visible. What the
     * demo accounts actually need is an avatar, which is the owner's row 232 and not a publish here.
     *
     * <h2>⭐ Why the event is published HERE rather than from the endpoints that edit step 1</h2>
     *
     * <p>The obvious shape is a call from {@code AccountResource.updateOwnAccount} (step 1's own
     * write) and another from {@link #updateUser(AdminUserDTO)} (an administrator editing the same
     * four fields in user management). <b>That shape is a list of call sites, and this estate has
     * paid for it three times.</b> {@code api/}'s {@code EntityChangeAnnouncer} records the bill in
     * as many words: {@code entity.created} is published by hand from ten resources and is therefore
     * absent from every write path nobody remembered, and backlog.md item 49 is the same defect one
     * level up — {@code ProfileStatus} hung off a table of four call sites, the licence-renewal path
     * was not on the table, and the far side went on rendering a stale answer. The conclusion there
     * was <i>"a list of call sites cannot fail when an eleventh one is written"</i>, and it transfers
     * to the eight callers below: a ninth way to write a {@code User} <em>through this service</em>
     * publishes without its author knowing this event exists. ⚠ It does <b>not</b> transfer to a
     * writer that bypasses the repository, as the paragraph above says — so this is the right site
     * and not a complete one.
     *
     * <p>⚠ <b>What it costs, stated rather than hidden.</b> This method is reached by activation, a
     * password reset request, a password change, registration, the administrator's create and update,
     * and step 1's own write — so frames go out that repeat a boolean nobody changed. Three things
     * make that the cheaper side of the trade. The event is a <b>snapshot and idempotent</b>, so a
     * repeat applies the same value twice; {@code User} writes are human-paced rather than
     * per-request, so the volume is tiny; and the consumer refuses an older {@code occurredAt}, so a
     * reordered repeat cannot move the answer backwards. The alternative — comparing against the
     * stored row to publish only on a change — would add a read to every write of a {@code User} to
     * save frames on a topic that already carries three per registration.
     *
     * <p><b>Fire and forget, off the event loop, and it cannot fail the write.</b> Exactly
     * {@code AccountResource.publishActivation}'s shape and for its reason: the row is saved by the
     * time this runs, so the only things a broker round trip could still change are how long the
     * caller waits and whether an unreachable Kafka turns a successful account edit into a 500.
     * The whole build and send is wrapped, not only the send — {@code StreamBridge.send} resolves a
     * binding with blocking I/O and {@code UUID.randomUUID()} draws on SecureRandom — and errors are
     * swallowed here as well as inside the publisher. ⛔ <b>Do not subscribe this into the chain.</b>
     * Registration's three frames are, because they must reach the partition in a fixed order; this
     * one has nothing to order against and every caller of this method is a write path.
     */
    private Mono<User> saveUser(User user) {
        return SecurityUtils.getCurrentUserLogin()
            .switchIfEmpty(Mono.just(Constants.SYSTEM))
            .flatMap(login -> {
                if (user.getCreatedBy() == null) {
                    user.setCreatedBy(login);
                }
                user.setLastModifiedBy(login);
                return userRepository.save(user);
            })
            .doOnNext(this::publishAccountDetails);
    }

    /**
     * Announces {@link AccountCompleteness#isComplete} for a saved account, without the caller
     * waiting on it — see {@link #saveUser}, which is the only call site and holds the reasoning.
     *
     * <p>The verdict is computed <b>here, on the saved row</b>, so the four fields never leave this
     * class. An account with no id has not been persisted and cannot be correlated with anything on
     * the far side, so it is skipped rather than published with a null key — the same refusal
     * {@code api/}'s {@code publishProfileStatus} makes for a profile with no account.
     */
    private void publishAccountDetails(User user) {
        if (user.getId() == null) {
            log.warn("Not announcing account details for {} — the saved row carries no id", user.getLogin());
            return;
        }
        boolean detailsComplete = AccountCompleteness.isComplete(user);
        Mono.fromRunnable(
            () -> registrationEventPublisher.publishAccountDetailsUpdated(user.getId(), user.getLogin(), user.getEmail(), detailsComplete)
        )
            .subscribeOn(Schedulers.boundedElastic())
            .doOnError(e -> log.warn("Could not announce account details for {} — the account is saved regardless", user.getLogin(), e))
            .onErrorComplete()
            .subscribe();
    }

    public Mono<Void> changePassword(String currentClearTextPassword, String newPassword) {
        return SecurityUtils.getCurrentUserLogin()
            .flatMap(userRepository::findOneByLogin)
            .publishOn(Schedulers.boundedElastic())
            .map(user -> {
                String currentEncryptedPassword = user.getPassword();
                if (!passwordEncoder.matches(currentClearTextPassword, currentEncryptedPassword)) {
                    throw new InvalidPasswordException();
                }
                String encryptedPassword = passwordEncoder.encode(newPassword);
                user.setPassword(encryptedPassword);
                return user;
            })
            .flatMap(this::saveUser)
            .doOnNext(user -> log.debug("Changed password for User: {}", user))
            .then();
    }

    public Flux<AdminUserDTO> getAllManagedUsers(Pageable pageable) {
        return userRepository.findAllByIdNotNull(pageable).map(AdminUserDTO::new);
    }

    public Flux<UserDTO> getAllPublicUsers(Pageable pageable) {
        return userRepository.findAllByIdNotNullAndActivatedIsTrue(pageable).map(UserDTO::new);
    }

    public Mono<Long> countManagedUsers() {
        return userRepository.count();
    }

    public Mono<User> getUserWithAuthoritiesByLogin(String login) {
        return userRepository.findOneByLogin(login);
    }

    /**
     * The same read as {@link #getUserWithAuthoritiesByLogin(String)}, addressed by {@code User.id}.
     * <p>
     * It exists for hc-admin, which is migrating {@code Profile.accountId} to hold this id rather
     * than the login (their item 123). The id is the stable key: {@code PUT /api/admin/users/{login}}
     * can change a login, so a sibling holding a copy of one goes stale with nothing failing.
     *
     * @param id the {@code User.id} of the user to find.
     * @return the user, or empty if no account carries that id.
     */
    public Mono<User> getUserWithAuthoritiesById(String id) {
        return userRepository.findById(id);
    }

    public Mono<User> getUserWithAuthorities() {
        return SecurityUtils.getCurrentUserLogin().flatMap(userRepository::findOneByLogin);
    }

    /**
     * Not activated users should be automatically deleted after 3 days.
     * <p>
     * This is scheduled to get fired everyday, at 01:00 (am).
     */
    @Scheduled(cron = "0 0 1 * * ?")
    public void removeNotActivatedUsers() {
        removeNotActivatedUsersReactively().blockLast();
    }

    public Flux<User> removeNotActivatedUsersReactively() {
        return userRepository
            .findAllByActivatedIsFalseAndActivationKeyIsNotNullAndCreatedDateBefore(Instant.now().minus(3, ChronoUnit.DAYS))
            .flatMap(user -> userRepository.delete(user).thenReturn(user))
            .doOnNext(user -> log.debug("Deleted User: {}", user));
    }

    /**
     * Gets a list of all the authorities.
     * @return a list of all the authorities.
     */
    public Flux<String> getAuthorities() {
        return authorityRepository.findAll().map(Authority::getName);
    }
}
