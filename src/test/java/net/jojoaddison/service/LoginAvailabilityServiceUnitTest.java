package net.jojoaddison.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The suggestion generator is pure, so it is tested without a database. What matters is not that it
 * produces pretty names but that every name it produces could actually be registered — a suggestion
 * the form accepts and the server then rejects is worse than offering nothing.
 */
class LoginAvailabilityServiceUnitTest {

    @Test
    void stemDropsATrailingNumberSoTheLadderContinuesRatherThanNests() {
        // jdoe2 must lead to jdoe3, not jdoe21.
        assertThat(LoginAvailabilityService.stem("jdoe2")).isEqualTo("jdoe");
        assertThat(LoginAvailabilityService.stem("jdoe123")).isEqualTo("jdoe");
        assertThat(LoginAvailabilityService.stem("jdoe")).isEqualTo("jdoe");
    }

    @Test
    void stemKeepsAnAllDigitLoginIntact() {
        // Stripping every character would leave an empty stem, and the suggestions would then have
        // nothing to do with what was typed.
        assertThat(LoginAvailabilityService.stem("12345")).isEqualTo("12345");
    }

    @Test
    void suggestionsSkipTheOnesAlreadyTaken() {
        Set<String> taken = Set.of("jdoe1", "jdoe2", "jdoe4");

        assertThat(LoginAvailabilityService.pickAvailable("jdoe", taken)).containsExactly("jdoe3", "jdoe5", "jdoe6");
    }

    @Test
    void suggestionsAreOfferedWhenNothingNearbyIsTaken() {
        assertThat(LoginAvailabilityService.pickAvailable("jdoe", Set.of())).containsExactly("jdoe1", "jdoe2", "jdoe3");
    }

    @Test
    void aStemAtTheLengthLimitIsShortenedSoTheSuggestionStillFits() {
        // User.login is @Size(max = 50). The suffix is what makes the name distinct, so the stem is
        // what gets cut — suggesting a 51-character login would fail bean validation on submit.
        String stem = "a".repeat(50);

        var suggestions = LoginAvailabilityService.pickAvailable(stem, Set.of());

        assertThat(suggestions).isNotEmpty();
        assertThat(suggestions).allSatisfy(s -> assertThat(s.length()).isLessThanOrEqualTo(50));
        assertThat(suggestions.get(0)).isEqualTo("a".repeat(49) + "1");
    }

    @Test
    void normaliseMatchesWhatTheDatabaseStores() {
        // User.setLogin lower-cases. Checking the raw input would report "JDoe" free while "jdoe"
        // is registered, and registration would then reject the very name the form approved.
        assertThat(LoginAvailabilityService.normalise("  JDoe  ")).isEqualTo("jdoe");
        assertThat(LoginAvailabilityService.normalise(null)).isEmpty();
    }
}
