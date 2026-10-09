package net.jojoaddison.web.rest;

import jakarta.validation.Valid;
import java.util.List;
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
import org.springframework.security.access.prepost.PreAuthorize;
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
     * <p>⛔ <b>The response shape is {@link AdminUserDTO} and must stay that way.</b> It is how
     * onboarding step 1 learns which of {@code firstName}, {@code lastName}, {@code langKey} and
     * {@code imageUrl} is still empty, and it has three live consumers that cache it:
     * {@code web/app/core/auth/account.service.ts}, {@code mobile/app/core/auth/account.service.ts},
     * and — less obviously — <b>two {@code web/} interceptors special-case this exact path</b>,
     * exempting it from the 401 sign-out ({@code auth-expired.interceptor.ts}) and from the global
     * error banner ({@code error-handler.interceptor.ts}). Narrowing it to the five fields
     * the five fields the write reads would break sign-in: {@code authorities} is what routes a
     * clinician into the shell and an applicant into onboarding.
     *
     * <p>⭐ <b>Since F4 the read and the write bind the SAME type</b>, {@link AdminUserDTO}, as
     * {@code profile.md} specifies — so the asymmetry is no longer in the types but in what the
     * write <em>reads</em> from one. {@code AccountResource#ownAccountUpdate} is where that
     * asymmetry lives now, and it is five named getters rather than a narrower class.
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
     * The three fields {@code profile.md} forbids this endpoint from writing (F4).
     *
     * <p>{@code profile.md} § "User (account)", under {@code api/account}:
     * <i>"updateUserAccount(AdminUserDTO)"</i> and <i>"Do not update the fields {@code id},
     * {@code activated} and {@code login} from this endpoint."</i> Declared as a list because the
     * requirement is a list — {@link #updateUserAccount} drops them structurally and
     * {@code AccountResourceIT} asserts each one by name, and this is what the two have in common
     * to point at.
     *
     * <p>⚠ <b>"Do not update" and not "refuse the request".</b> {@code GET /api/account} answers an
     * {@link AdminUserDTO} carrying all three, step 1's dialog is populated from exactly that, and
     * the obvious client sends the document back — so a 400 on naming them would make the endpoint
     * unusable by the only caller the specification describes. They are ignored, the write succeeds,
     * and the stored values stand.
     */
    static final List<String> FIELDS_THIS_ENDPOINT_DOES_NOT_UPDATE = List.of("id", "activated", "login");

    /**
     * {@code PUT  /account} : update the current user's own account — onboarding step 1.
     *
     * <p><b>Named for the operation {@code profile.md} names</b>: {@code updateUserAccount(AdminUserDTO)}.
     *
     * <p><b>Subject-less, so the gate is {@code .authenticated()} and not {@code ROLE_ADMIN}.</b>
     * This path names nobody: the account is resolved from the token, and there is no identifier a
     * caller could substitute. Where identity <em>is</em> the boundary an authority check constrains
     * nothing further — which is why the rule here differs from {@code UserResource}'s, whose paths
     * take a login and are therefore admin-gated. ⛔ Never add a self-carve-out comparing the caller
     * against a path variable: there is no subject in this path and there must not be one, because
     * that comparison is the shape that gets it wrong (see {@code workspace/CLAUDE.md} § "Who may
     * read a profile").
     *
     * <p><b>{@code @PreAuthorize} as well as the filter chain, deliberately.</b>
     * {@code SecurityConfiguration} holds {@code /api/**} at {@code .authenticated()} with no verb
     * scoping, so this handler is covered there already and <b>needed no new rule for {@code PUT}</b>
     * — the two permitted exceptions below it ({@code /api/account/reset-password/init} and
     * {@code /finish}) are path-specific and do not match {@code /api/account}. The annotation is the
     * second layer, intercepting the invocation whatever the verb, and this repo keeps both: a rule
     * written against one HTTP method is exactly how a {@code HEAD} slipped past
     * {@code ProfileResource}'s filter-chain rule while only the annotation refused it.
     *
     * <h2>⛔ The body is {@link AdminUserDTO}, because the specification says so (F4)</h2>
     *
     * <p>This bound a five-component {@code OwnAccountDTO} record, and <b>its javadoc argued that an
     * allow-list beats a deny-list because "a deny-list must track a DTO that grows". That reasoning
     * was overruled by the owner and the type is retired</b> — {@code profile.md} names both the
     * operation and the bound type, and "the narrower type is safer" is not an argument against
     * building what was specified.
     *
     * <p><b>What the deny-list costs, stated rather than hidden, since the overruled argument was
     * not wrong about the mechanism:</b> a field added to {@code AdminUserDTO} for the
     * administrator's user-management screens does arrive on this endpoint's wire for free. Two
     * things stop that mattering, and neither is this handler remembering to drop it.
     * {@link #ownAccountUpdate} reads <b>five values by name</b> and passes them as five arguments,
     * so an eleventh field cannot be written by code that does not mention it; and
     * {@code UserService.updateUser(String, String, String, String, String)} is the only write
     * reached from here. ⛔ <b>Do not route this through
     * {@code UserService.updateUser(AdminUserDTO)}</b> — that overload clears the authority set and
     * refills it from the body, which is the one edit that would turn this endpoint into a
     * privilege-escalation path.
     *
     * <p><b>{@code authorities} is therefore unwritable here, and that is not a deny-list entry.</b>
     * The career role is a <em>request</em> granted after review, written on
     * {@code ProfessionalApplication} by T3, never on the account by its holder.
     * {@code AccountResourceIT.testSaveAccount} has always asserted that {@code activated} and
     * {@code authorities} do not come from the request body; the {@code PUT} cases beside it assert
     * the same, plus {@code id} and {@code login} by name.
     *
     * @param userDTO the account document; only the five fields step 1 edits are read.
     * @throws EmailAlreadyUsedException {@code 400 (Bad Request)} if the email belongs to another login.
     * @throws RuntimeException {@code 500 (Internal Server Error)} if the user login wasn't found.
     */
    @PutMapping("/account")
    @PreAuthorize("isAuthenticated()")
    public Mono<Void> updateUserAccount(@Valid @RequestBody AdminUserDTO userDTO) {
        return updateOwnAccount(userDTO);
    }

    /**
     * {@code POST  /account} : update the current user information.
     *
     * @deprecated since T4 ({@code profile.md} § "Step 1 — Complete the account"), replaced by
     *             {@link #updateUserAccount(AdminUserDTO)}. <b>Retires in T6</b>, which drops
     *             {@code SettingsComponent} — {@code web/}'s {@code AccountService.save()} is the
     *             only caller of this verb in the estate ({@code mobile/} posts the
     *             {@code /account/**} sub-paths and never this one). <b>It stays until then on the
     *             house rule, not on taste:</b> add before removing, and remove the consumer before
     *             the producer. T6 is scheduled last, so deleting this now would leave a live client
     *             calling a dead verb for the whole of T2, T3, T5, T8 and T9.
     *
     *             <p><b>It is an adapter over the same private write</b> rather than a second copy of
     *             it, so the two verbs cannot drift apart while both exist — and the four
     *             {@code POST} cases in {@code AccountResourceIT} go on proving the behaviour the
     *             {@code PUT} cases assert beside them. ⭐ Since F4 both verbs bind
     *             {@link AdminUserDTO}, so the two are now the same request with two names and the
     *             adapter is a one-line delegation; before it, the {@code PUT} narrowed the body
     *             first and the shapes differed.
     *
     * @param userDTO the current user information; only the five narrow fields are read.
     * @throws EmailAlreadyUsedException {@code 400 (Bad Request)} if the email is already used.
     * @throws RuntimeException {@code 500 (Internal Server Error)} if the user login wasn't found.
     */
    @Deprecated(forRemoval = true)
    @PostMapping("/account")
    @PreAuthorize("isAuthenticated()")
    public Mono<Void> saveAccount(@Valid @RequestBody AdminUserDTO userDTO) {
        return updateOwnAccount(userDTO);
    }

    /**
     * The one write behind both {@code PUT} and the deprecated {@code POST}.
     *
     * <p><b>The email check is not a uniqueness constraint and must not be simplified into one.</b>
     * It refuses the address only when it belongs to a <em>different</em> login — a caller re-sending
     * their own address is the ordinary case, and {@code testSaveExistingEmailAndLogin} (with its
     * {@code PUT} twin) is what holds that open. {@code findOneByEmailIgnoreCase} plus the login
     * filter, rather than a plain existence probe, is the whole of that distinction.
     *
     * <p>⛔ <b>It calls {@code UserService}'s five-argument {@code updateUser} and not the
     * {@code AdminUserDTO} overload, which clears the authority set and refills it from its body.</b>
     * That has always been true of this endpoint and it is the single most important line here:
     * since F4 the bound type <em>can</em> carry {@code authorities}, {@code activated}, {@code id}
     * and {@code login}, so the narrow overload is now what makes those four unwritable rather than
     * a belt beside a type that had no braces to hold.
     */
    private Mono<Void> updateOwnAccount(AdminUserDTO userDTO) {
        OwnAccountUpdate update = ownAccountUpdate(userDTO);
        return SecurityUtils.getCurrentUserLogin()
            .switchIfEmpty(Mono.error(new AccountResourceException("Current user login not found")))
            .flatMap(userLogin ->
                userRepository
                    .findOneByEmailIgnoreCase(update.email())
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
                user -> userService.updateUser(update.firstName(), update.lastName(), update.email(), update.langKey(), update.imageUrl())
            );
    }

    /**
     * The five values this endpoint writes, read off the bound document <b>by name</b>.
     *
     * <p>This is where {@code profile.md}'s <i>"Do not update the fields {@code id},
     * {@code activated} and {@code login} from this endpoint"</i> is implemented, and it is
     * implemented by <b>not reading them</b> rather than by clearing them afterwards — the
     * difference matters, because "write it then put it back" has a window and a bug in it where
     * "never read it" has neither. {@code authorities} is absent for the same reason and
     * {@link #FIELDS_THIS_ENDPOINT_DOES_NOT_UPDATE} names the three the specification lists.
     *
     * <p>⚠ <b>A record rather than five locals, so the next field is a compile error and not an
     * oversight.</b> The component list is the canonical constructor and is in
     * {@code UserService.updateUser}'s parameter order, so the type and the write agree by reading
     * rather than by checking — which is the one part of the retired {@code OwnAccountDTO}'s
     * reasoning that survives the owner's ruling, because it is a statement about this method and
     * not about what the endpoint binds.
     */
    private static OwnAccountUpdate ownAccountUpdate(AdminUserDTO userDTO) {
        return new OwnAccountUpdate(
            userDTO.getFirstName(),
            userDTO.getLastName(),
            userDTO.getEmail(),
            userDTO.getLangKey(),
            userDTO.getImageUrl()
        );
    }

    /** @see #ownAccountUpdate(AdminUserDTO) */
    private record OwnAccountUpdate(String firstName, String lastName, String email, String langKey, String imageUrl) {}

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
