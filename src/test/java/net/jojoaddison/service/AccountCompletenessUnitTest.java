package net.jojoaddison.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Consumer;
import net.jojoaddison.domain.User;
import org.junit.jupiter.api.Test;

/**
 * Onboarding step 1 is the four account fields, and <b>each of them is asserted on its own</b>
 * (backlog.md row 230, unit A).
 *
 * <p>One "complete → true, empty → false" case would pass over a predicate that read any single
 * field and ignored the other three, which is the shape of mistake a four-term conjunction actually
 * makes. Every case below leaves three fields filled and clears one, so a dropped term fails with
 * the name of the field it dropped.
 *
 * <p>⚠ <b>{@code imageUrl} has a case of its own for a second reason</b>, recorded on
 * {@link AccountCompleteness}: {@code profile.md}'s step-1 trigger names four fields and its
 * "All fields are required" list names three. Four is implemented, so if that is ever settled the
 * other way this is the test that says so out loud rather than a term quietly disappearing.
 */
class AccountCompletenessUnitTest {

    @Test
    void anAccountWithAllFourFieldsIsComplete() {
        assertThat(AccountCompleteness.isComplete(completeAccount())).isTrue();
    }

    @Test
    void aNullAccountIsNotComplete() {
        assertThat(AccountCompleteness.isComplete(null)).isFalse();
    }

    @Test
    void aMissingFirstNameLeavesStepOneOutstanding() {
        assertEachAbsenceIsRefused(user -> user.setFirstName(null), user -> user.setFirstName("  "));
    }

    @Test
    void aMissingLastNameLeavesStepOneOutstanding() {
        assertEachAbsenceIsRefused(user -> user.setLastName(null), user -> user.setLastName(""));
    }

    @Test
    void aMissingLangKeyLeavesStepOneOutstanding() {
        assertEachAbsenceIsRefused(user -> user.setLangKey(null), user -> user.setLangKey(" "));
    }

    /** @see AccountCompleteness the specification is not unanimous about this one; four is implemented */
    @Test
    void aMissingImageUrlLeavesStepOneOutstanding() {
        assertEachAbsenceIsRefused(user -> user.setImageUrl(null), user -> user.setImageUrl("\t"));
    }

    /**
     * ⚠ The email is deliberately not one of the four — an account cannot exist without one, so
     * requiring it would make the predicate unfalsifiable rather than stricter.
     */
    @Test
    void theEmailIsNotOneOfStepOnesFields() {
        User user = completeAccount();
        user.setEmail(null);

        assertThat(AccountCompleteness.isComplete(user)).isTrue();
    }

    /**
     * Blank is absent, for every field, by one definition. A predicate that accepted {@code ""} for
     * any one of them would be satisfiable by submitting an empty form.
     */
    private void assertEachAbsenceIsRefused(Consumer<User> clear, Consumer<User> blank) {
        User nulled = completeAccount();
        clear.accept(nulled);
        assertThat(AccountCompleteness.isComplete(nulled)).as("a null value must leave step 1 outstanding").isFalse();

        User blanked = completeAccount();
        blank.accept(blanked);
        assertThat(AccountCompleteness.isComplete(blanked)).as("a blank value must leave step 1 outstanding").isFalse();
    }

    private User completeAccount() {
        User user = new User();
        user.setId("user-42");
        user.setLogin("ama.serwaa");
        user.setEmail("ama@localhost");
        user.setFirstName("Ama");
        user.setLastName("Serwaa");
        user.setLangKey("en");
        user.setImageUrl("https://example.invalid/ama.png");
        return user;
    }
}
