package net.jojoaddison.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import net.jojoaddison.config.Constants;
import net.jojoaddison.domain.User;
import net.jojoaddison.repository.UserRepository;
import net.jojoaddison.service.dto.LoginAvailabilityDTO;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Answers whether a login can be registered, and proposes alternatives when it cannot.
 *
 * <p><strong>This is an advisory read, not a reservation.</strong> Nothing is locked, so a login
 * reported available can be taken by another registration a moment later. The uniqueness check
 * inside {@code UserService.registerUser} stays the authority; this service exists only so the form
 * can say so before the user has filled in a password.
 *
 * <p><strong>It is also a user-enumeration oracle, and deliberately a cheap one.</strong> Anyone may
 * call it anonymously — registration would be unusable otherwise — so it confirms which logins
 * exist. Registration already leaks exactly that through its {@code 400 LOGIN_ALREADY_USED}, so the
 * class of leak is not new; what changes is the cost, since a GET has no side effects and can be run
 * in a loop. The mitigation is a rate limit at the edge (see {@code deploy/prod-server}), not
 * anything here: throttling in the application would still let a caller learn the answer, just more
 * slowly. Two rules follow from that and should survive any refactor — <em>never</em> return the
 * email address, display name or any other field of the account that holds a taken login, and keep
 * the response identical in shape whether the login is taken or free, so nothing extra is inferable
 * from timing or payload size beyond the single bit that was asked for.
 */
@Service
public class LoginAvailabilityService {

    /**
     * How many taken logins to read around the requested one. A prefix with more neighbours than
     * this simply produces suggestions drawn from the first {@code N}; since candidates are checked
     * against that set and any collision only costs one discarded candidate, a partial view can at
     * worst suggest something already taken — which registration then rejects, exactly as it would
     * have without a suggestion.
     */
    private static final int NEIGHBOURHOOD_LIMIT = 200;

    /** How many alternatives to offer. Three fits the form without becoming a wall of chips. */
    private static final int MAX_SUGGESTIONS = 3;

    /** Mirrors {@code User.login}'s {@code @Size}; a suggestion that cannot be registered is worse than none. */
    private static final int MAX_LOGIN_LENGTH = 50;

    private final UserRepository userRepository;

    public LoginAvailabilityService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * @param rawLogin the login as typed; trimmed and lower-cased before anything is looked up.
     * @return availability plus, when taken, up to {@value #MAX_SUGGESTIONS} free alternatives.
     */
    public Mono<LoginAvailabilityDTO> check(String rawLogin) {
        String login = normalise(rawLogin);
        return userRepository
            .findOneByLogin(login)
            .map(existing -> Boolean.TRUE)
            .defaultIfEmpty(Boolean.FALSE)
            .flatMap(taken -> {
                if (Boolean.FALSE.equals(taken)) {
                    return Mono.just(new LoginAvailabilityDTO(login, true, List.of()));
                }
                return suggestionsFor(login).map(suggestions -> new LoginAvailabilityDTO(login, false, suggestions));
            });
    }

    /** Trim and lower-case, matching what {@code User.setLogin} does on the way into the database. */
    public static String normalise(String rawLogin) {
        return rawLogin == null ? "" : rawLogin.trim().toLowerCase(Locale.ENGLISH);
    }

    private Mono<List<String>> suggestionsFor(String login) {
        String stem = stem(login);
        return userRepository
            .findAllByLoginStartingWith(stem)
            .take(NEIGHBOURHOOD_LIMIT)
            .map(User::getLogin)
            .collect(Collectors.toSet())
            .map(taken -> pickAvailable(stem, taken));
    }

    /**
     * Strips a trailing run of digits so {@code jdoe2} suggests {@code jdoe3} rather than
     * {@code jdoe21}. A login that is nothing but digits keeps its stem, since an empty stem would
     * generate suggestions unrelated to what was asked for.
     */
    static String stem(String login) {
        int end = login.length();
        while (end > 0 && Character.isDigit(login.charAt(end - 1))) {
            end--;
        }
        return end == 0 ? login : login.substring(0, end);
    }

    /**
     * Numeric ladder over the stem: {@code jdoe1}, {@code jdoe2}, … Deterministic on purpose — the
     * same input yields the same offer, which keeps the tests meaningful and avoids handing two
     * people racing for a name two different-looking-but-equally-contended options.
     */
    static List<String> pickAvailable(String stem, Set<String> taken) {
        List<String> suggestions = new ArrayList<>();
        for (int n = 1; suggestions.size() < MAX_SUGGESTIONS && n <= 999; n++) {
            String candidate = truncateToFit(stem, n) + n;
            if (!taken.contains(candidate) && isRegisterable(candidate)) {
                suggestions.add(candidate);
            }
        }
        return List.copyOf(suggestions);
    }

    /** Keeps {@code stem + suffix} inside the 50-character limit by shortening the stem, never the suffix. */
    private static String truncateToFit(String stem, int suffix) {
        int room = MAX_LOGIN_LENGTH - String.valueOf(suffix).length();
        return stem.length() <= room ? stem : stem.substring(0, room);
    }

    /**
     * A suggestion has to satisfy the same constraints registration enforces. The stem is derived
     * from a login the caller typed, which the endpoint has already validated, so this is a guard
     * against a future change to the generator rather than against hostile input.
     */
    private static boolean isRegisterable(String candidate) {
        return !candidate.isEmpty() && candidate.length() <= MAX_LOGIN_LENGTH && candidate.matches(Constants.LOGIN_REGEX);
    }
}
