package net.jojoaddison.web.rest;

import jakarta.validation.Valid;
import java.util.Objects;
import java.util.stream.Collectors;
import net.jojoaddison.config.Constants;
import net.jojoaddison.domain.Authority;
import net.jojoaddison.domain.User;
import net.jojoaddison.repository.UserRepository;
import net.jojoaddison.security.SecurityUtils;
import net.jojoaddison.service.LoginAvailabilityService;
import net.jojoaddison.service.MailService;
import net.jojoaddison.service.UserService;
import net.jojoaddison.service.dto.AdminUserDTO;
import net.jojoaddison.service.dto.LoginAvailabilityDTO;
import net.jojoaddison.service.dto.PasswordChangeDTO;
import net.jojoaddison.web.rest.errors.*;
import net.jojoaddison.web.rest.vm.KeyAndPasswordVM;
import net.jojoaddison.web.rest.vm.ManagedUserVM;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * REST controller for managing the current user's account.
 */
@RestController
@RequestMapping("/api")
public class AccountResource {

    private static class AccountResourceException extends RuntimeException {

        private AccountResourceException(String message) {
            super(message);
        }
    }

    private final Logger log = LoggerFactory.getLogger(AccountResource.class);

    private final UserRepository userRepository;

    private final UserService userService;

    private final MailService mailService;

    private final net.jojoaddison.broker.RegistrationEventPublisher registrationEventPublisher;

    private final LoginAvailabilityService loginAvailabilityService;

    public AccountResource(
        UserRepository userRepository,
        UserService userService,
        MailService mailService,
        net.jojoaddison.broker.RegistrationEventPublisher registrationEventPublisher,
        LoginAvailabilityService loginAvailabilityService
    ) {
        this.userRepository = userRepository;
        this.userService = userService;
        this.mailService = mailService;
        this.registrationEventPublisher = registrationEventPublisher;
        this.loginAvailabilityService = loginAvailabilityService;
    }

    /**
     * {@code POST  /register} : register the user.
     *
     * @param managedUserVM the managed user View Model.
     * @throws InvalidPasswordException {@code 400 (Bad Request)} if the password is incorrect.
     * @throws EmailAlreadyUsedException {@code 400 (Bad Request)} if the email is already used.
     * @throws LoginAlreadyUsedException {@code 400 (Bad Request)} if the login is already used.
     */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Void> registerAccount(@Valid @RequestBody ManagedUserVM managedUserVM) {
        if (isPasswordLengthInvalid(managedUserVM.getPassword())) {
            throw new InvalidPasswordException();
        }
        return userService
            .registerUser(managedUserVM, managedUserVM.getPassword())
            .doOnSuccess(mailService::sendActivationEmail)
            .flatMap(
                user -> publishNewAccount(user, net.jojoaddison.broker.RegistrationEventPublisher.ORIGIN_SELF_SERVICE, user.getLogin())
            )
            .then();
    }

    /**
     * The three frames a new account puts on {@code hc.professional.registration}, in order.
     *
     * <p>{@code AccountCreated} leads — it is the estate-shaped fact, the one hc-patient's gateway
     * publishes for the same moment — and {@code registration.created} and {@code onboarding.state}
     * continue the story it opens in this stack's own envelope. All three are kept because the
     * older two have a live consumer; see {@code RegistrationEventPublisher}'s class comment.
     *
     * <p><b>One runnable, one scheduler hop, and the whole build rather than only the send.</b>
     * StreamBridge does blocking I/O resolving a binding, and {@code UUID.randomUUID()} draws on
     * SecureRandom and can block on its own, while every caller here is a handler on a Netty event
     * loop. And the three share the accountId partition key, so the order they are <em>sent</em> in
     * is the order a consumer reads them in — scheduling them separately would leave that to the
     * pool.
     *
     * <p><b>The error arm is not redundant.</b> {@code RegistrationEventPublisher} catches its own
     * {@code RuntimeException}s, but this chain is subscribed <em>into the request</em> — unlike
     * activation's, because the three frames have to be ordered — so anything that escaped the
     * publisher would turn a successful registration into a 500 for somebody whose account already
     * exists and whose activation email has already gone. The account is the write path; the events
     * are not.
     */
    private Mono<Void> publishNewAccount(User user, String origin, String actor) {
        return Mono.fromRunnable(() -> {
            registrationEventPublisher.publishAccountCreated(
                user.getId(),
                user.getLogin(),
                user.getEmail(),
                user.getLangKey(),
                joinedAuthorities(user),
                user.isActivated()
            );
            registrationEventPublisher.publishRegistrationCreated(
                user.getId(),
                user.getLogin(),
                user.getEmail(),
                user.getLangKey(),
                origin,
                actor
            );
            registrationEventPublisher.publishOnboardingInProgress(user.getId(), user.getLogin(), actor);
        })
            .subscribeOn(Schedulers.boundedElastic())
            .doOnError(e -> log.warn("Could not announce the new account {} — the account is unaffected", user.getLogin(), e))
            .onErrorComplete()
            .then();
    }

    /**
     * The account's authorities as {@code AccountCreated} carries them: comma-joined and sorted.
     *
     * <p>Sorted so two frames for one account are byte-identical rather than differing by
     * {@code HashSet} iteration order, and joined rather than nested because that is the shape
     * hc-patient publishes and hc-admin's parser already splits on the comma. At registration the
     * value is {@code ROLE_USER} alone — the nine clinical authorities are granted later, by an
     * administrator, and no account event can say what a clinician is.
     *
     * <p>Package-private because {@code UserResource}'s invitation path sends the same field on the
     * same event and must not derive it a second way — two derivations of one rule disagreeing is a
     * defect this estate has already paid for more than once.
     */
    static String joinedAuthorities(User user) {
        return user.getAuthorities().stream().map(Authority::getName).sorted().collect(Collectors.joining(","));
    }

    /**
     * {@code GET  /register/login-available} : whether a login can still be registered.
     *
     * <p>Anonymous, because it serves the registration form, and the caller has no account yet by
     * definition. See {@link net.jojoaddison.service.LoginAvailabilityService} for why that is
     * acceptable and what must not be added to the response.
     *
     * <p>The answer is advisory: nothing is reserved, and {@code POST /register} remains the
     * authority. A login reported available can be taken between the check and the submit, which is
     * why registration still returns {@code 400 LOGIN_ALREADY_USED} and the form still handles it.
     *
     * @param login the candidate login; validated against the same pattern and length registration
     *              enforces, so a malformed value is a {@code 400} rather than a wasted query.
     * @return the normalised login, whether it is free, and alternatives when it is not.
     */
    @GetMapping("/register/login-available")
    public Mono<LoginAvailabilityDTO> isLoginAvailable(@RequestParam("login") String login) {
        // Validated here rather than with @Pattern/@Size on the parameter. Those need @Validated on
        // the class, which raises ConstraintViolationException — and this repo's reactive
        // ExceptionTranslator does not map it, so a malformed login came back as 500 instead of
        // 400. Adding a global handler for it would change the error shape of every other endpoint
        // on this controller, so the check is explicit and local.
        String candidate = LoginAvailabilityService.normalise(login);
        if (candidate.isEmpty() || candidate.length() > 50 || !candidate.matches(Constants.LOGIN_REGEX)) {
            throw new BadRequestAlertException("Invalid login", "userManagement", "invalidlogin");
        }
        return loginAvailabilityService.check(candidate);
    }

    /**
     * {@code GET  /activate} : activate the registered user.
     *
     * <p><b>This published nothing at all until backlog.md item 47</b> — it activated the user and
     * returned — so the one moment at which a clinician's account stops being a pending registration
     * and becomes usable was invisible to the whole estate. It now emits {@code AccountActivated},
     * the second of the two account events hc-patient's gateway publishes for the same two moments.
     *
     * @param key the activation key.
     * @throws RuntimeException {@code 500 (Internal Server Error)} if the user couldn't be activated.
     */
    @GetMapping("/activate")
    public Mono<Void> activateAccount(@RequestParam(value = "key") String key) {
        return userService
            .activateRegistration(key)
            .switchIfEmpty(Mono.error(new AccountResourceException("No user was found for this activation key")))
            .doOnSuccess(this::publishActivation)
            .then();
    }

    /**
     * Announces the activation without the caller waiting on it.
     *
     * <p><b>Fire-and-forget, unlike registration's, and the difference is deliberate.</b> The
     * account is already activated by the time this runs — the key is consumed and the row is
     * saved — so the only thing a broker round trip could still change is how long the person
     * stares at the activation card, and how the request fails if Kafka is unreachable. Neither is
     * a price worth paying for an event. Registration's three frames are subscribed into its chain
     * because they must reach the partition in a fixed order; this one has nothing to order against.
     *
     * <p>Off the event loop for the reason {@code RegistrationEventPublisher}'s class comment gives:
     * the whole build and send, not only the send. Errors are logged and swallowed here <em>and</em>
     * inside the publisher — belt and braces on the one path whose failure must never reach an
     * account holder.
     */
    private void publishActivation(User user) {
        Mono.fromRunnable(() -> registrationEventPublisher.publishAccountActivated(user.getId(), user.getLogin(), user.getEmail()))
            .subscribeOn(Schedulers.boundedElastic())
            .doOnError(e -> log.warn("Could not publish AccountActivated for {} — the account is activated regardless", user.getLogin(), e))
            .onErrorComplete()
            .subscribe();
    }

    /**
     * {@code GET  /account} : get the current user.
     *
     * @return the current user.
     * @throws RuntimeException {@code 500 (Internal Server Error)} if the user couldn't be returned.
     */
    @GetMapping("/account")
    public Mono<AdminUserDTO> getAccount() {
        return userService
            .getUserWithAuthorities()
            .map(AdminUserDTO::new)
            .switchIfEmpty(Mono.error(new AccountResourceException("User could not be found")));
    }

    /**
     * {@code POST  /account} : update the current user information.
     *
     * @param userDTO the current user information.
     * @throws EmailAlreadyUsedException {@code 400 (Bad Request)} if the email is already used.
     * @throws RuntimeException {@code 500 (Internal Server Error)} if the user login wasn't found.
     */
    @PostMapping("/account")
    public Mono<Void> saveAccount(@Valid @RequestBody AdminUserDTO userDTO) {
        return SecurityUtils.getCurrentUserLogin()
            .switchIfEmpty(Mono.error(new AccountResourceException("Current user login not found")))
            .flatMap(userLogin ->
                userRepository
                    .findOneByEmailIgnoreCase(userDTO.getEmail())
                    .filter(existingUser -> !existingUser.getLogin().equalsIgnoreCase(userLogin))
                    .hasElement()
                    .flatMap(emailExists -> {
                        if (emailExists) {
                            throw new EmailAlreadyUsedException();
                        }
                        return userRepository.findOneByLogin(userLogin);
                    }))
            .switchIfEmpty(Mono.error(new AccountResourceException("User could not be found")))
            .flatMap(
                user ->
                    userService.updateUser(
                        userDTO.getFirstName(),
                        userDTO.getLastName(),
                        userDTO.getEmail(),
                        userDTO.getLangKey(),
                        userDTO.getImageUrl()
                    )
            );
    }

    /**
     * {@code POST  /account/change-password} : changes the current user's password.
     *
     * @param passwordChangeDto current and new password.
     * @throws InvalidPasswordException {@code 400 (Bad Request)} if the new password is incorrect.
     */
    @PostMapping(path = "/account/change-password")
    public Mono<Void> changePassword(@RequestBody PasswordChangeDTO passwordChangeDto) {
        if (isPasswordLengthInvalid(passwordChangeDto.getNewPassword())) {
            throw new InvalidPasswordException();
        }
        return userService.changePassword(passwordChangeDto.getCurrentPassword(), passwordChangeDto.getNewPassword());
    }

    /**
     * {@code POST   /account/reset-password/init} : Send an email to reset the password of the user.
     *
     * @param mail the mail of the user.
     */
    @PostMapping(path = "/account/reset-password/init")
    public Mono<Void> requestPasswordReset(@RequestBody String mail) {
        return userService
            .requestPasswordReset(mail)
            .doOnSuccess(user -> {
                if (Objects.nonNull(user)) {
                    mailService.sendPasswordResetMail(user);
                } else {
                    // Pretend the request has been successful to prevent checking which emails really exist
                    // but log that an invalid attempt has been made
                    log.warn("Password reset requested for non existing mail");
                }
            })
            .then();
    }

    /**
     * {@code POST   /account/reset-password/finish} : Finish to reset the password of the user.
     *
     * @param keyAndPassword the generated key and the new password.
     * @throws InvalidPasswordException {@code 400 (Bad Request)} if the password is incorrect.
     * @throws RuntimeException {@code 500 (Internal Server Error)} if the password could not be reset.
     */
    @PostMapping(path = "/account/reset-password/finish")
    public Mono<Void> finishPasswordReset(@RequestBody KeyAndPasswordVM keyAndPassword) {
        if (isPasswordLengthInvalid(keyAndPassword.getNewPassword())) {
            throw new InvalidPasswordException();
        }
        return userService
            .completePasswordReset(keyAndPassword.getNewPassword(), keyAndPassword.getKey())
            .switchIfEmpty(Mono.error(new AccountResourceException("No user was found for this reset key")))
            .then();
    }

    private static boolean isPasswordLengthInvalid(String password) {
        return (
            StringUtils.isEmpty(password) ||
            password.length() < ManagedUserVM.PASSWORD_MIN_LENGTH ||
            password.length() > ManagedUserVM.PASSWORD_MAX_LENGTH
        );
    }
}
