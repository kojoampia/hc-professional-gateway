package net.jojoaddison.service;

import net.jojoaddison.domain.User;

/**
 * Whether an account provides every field onboarding <b>step 1</b> requires — the predicate behind
 * {@code AccountDetailsUpdated.data.detailsComplete}, and the only thing about those fields that
 * leaves this gateway (backlog.md row 230, unit A).
 *
 * <h2>Why a boolean exists at all, when the fields are right here</h2>
 *
 * <p>{@code api/} owns the completion meter and cannot see these fields.
 * {@code OnboardingService.java:412-417} records that deliberately: <i>"{@code firstName},
 * {@code lastName}, {@code langKey} and {@code imageUrl} live on {@code User} in
 * {@code hcProfessionalGateway}; there is deliberately <b>no cross-service call invented here</b> to
 * read them"</i>. Row 230 settles how step 1 reaches the meter instead — <b>as an event</b> — and
 * this class is the half of that which has to run on this side, because the four values themselves
 * must not travel.
 *
 * <h2>⛔ The event carries this answer and never the inputs</h2>
 *
 * <p>The estate's rule on both topics is <em>identifiers and metadata only — never the changed
 * fields</em>, and {@code firstName}, {@code lastName} and {@code imageUrl} are precisely the
 * personal data that rule exists for. A boolean <em>about</em> completeness is a fact about the
 * account rather than its contents — the same distinction {@code ProfileStatus} already draws when
 * it sends {@code licenceVerified} instead of a licence number, for a subsystem that holds no
 * licence number at all.
 *
 * <p>So the division is: this gateway decides <em>whether</em> step 1 is done, {@code api/} records
 * the answer against an account id, and nothing in between ever holds a name. ⚠ It also means the
 * rule cannot be changed on one side: a reader of the far side's projection learns only true or
 * false, so if the field list below moves, this is the only place it moves.
 *
 * <h2>⚠ Four fields, including {@code imageUrl} — and the specification is not unanimous</h2>
 *
 * <p>{@code profile.md} § "Step 1 — Complete the account" opens with the trigger: <i>"If any of the
 * account's {@code firstName}, {@code lastName}, {@code langKey} or {@code imageUrl} is empty, open
 * the <b>User update</b> dialog"</i> — four fields, and step 1 is therefore incomplete while any of
 * them is empty. <b>The "All fields are required" list immediately below it names only first name,
 * last name and language.</b>
 *
 * <p>⭐ <b>Four is implemented, and the owner has since settled it: keep four, and build the avatar
 * upload as its own row</b> (backlog.md row 232). The reading was the trigger condition's — it is the
 * sentence that defines when the step is outstanding — and {@code OnboardingService}'s own note, the
 * in-tree record of what the owner was asked, says <i>"step 1's <b>four</b> account fields"</i>.
 * ⛔ Do not narrow this predicate to three on the strength of the "All fields are required" list; the
 * question was raised and answered.
 *
 * <p>⚠ <b>The cost while no avatar upload exists is visible and harmless</b>: an applicant without
 * one never sees step 1 tick. Nothing is gated on this value anywhere — see
 * {@code api/ OnboardingProgressDTO.Steps} — so it delays a tick and blocks no submission.
 *
 * <h2>⚠ Where the field list actually lives, since this javadoc understated it</h2>
 *
 * <p>It said the change was <i>"one clause in {@link #isComplete} and one case in
 * {@code AccountCompletenessUnitTest} — nothing else in either repository encodes the field list"</i>.
 * Strictly true on <em>encodes</em>, and misleading in the way this estate keeps paying for: the four
 * names are <b>enumerated in prose</b> in roughly eight javadoc blocks row 230 added or touched, four
 * of them in the other repository — {@code api/}'s {@code AccountDetailsEvent},
 * {@code ProfessionalEventType}, {@code OnboardingProgressDTO.Steps} and
 * {@code OnboardingService.submitForReview} — besides more in files that change never opened
 * ({@code User}, {@code AdminUserDTO}, {@code AccountResource} and their tests).
 *
 * <p><b>So: {@link #isComplete} is the only place the rule is EXECUTED, and the prose is commentary
 * that goes stale silently.</b> Changing the rule means the clause, the unit test, and then reading
 * the prose — and the way to find it is a command rather than a count, because a count maintained by
 * hand is exactly what was wrong here:
 *
 * <pre>
 * grep -rln langKey src | xargs grep -ln imageUrl      # run in each of gateway/ and api/
 * </pre>
 *
 * <p>That is this estate's own correction applied to itself — <i>list them, or read the directory</i>:
 * an instruction that cannot go stale beats a number that can.
 *
 * <p>{@code email} is <b>not</b> among them, and that is not an omission:
 * {@code profile.md}'s trigger does not name it and an account cannot exist without one, so
 * requiring it would make the predicate unfalsifiable.
 *
 * <p>In {@code ..service..} rather than {@code ..domain..} or {@code ..web..} for the reason
 * {@code api/}'s {@code ProfileCompleteness} gives for the same choice: {@code TechnicalStructureTest}
 * lets a service reference {@code ..domain..}, and this predicate is about the document rather than
 * about HTTP. Static and stateless — the answer is a function of the user and of nothing else.
 */
public final class AccountCompleteness {

    private AccountCompleteness() {}

    /**
     * Whether this account provides all four of step 1's fields.
     *
     * @param user the stored account <b>after</b> the write has been applied, or {@code null}.
     * @return {@code true} when every field step 1 requires carries text.
     */
    public static boolean isComplete(User user) {
        return (
            user != null &&
            hasText(user.getFirstName()) &&
            hasText(user.getLastName()) &&
            hasText(user.getLangKey()) &&
            hasText(user.getImageUrl())
        );
    }

    /**
     * Provided means more than non-null: a blank string is what an untouched input posts, and
     * counting it would make step 1 satisfiable by submitting an empty form. The same definition
     * {@code api/}'s {@code ProfileCompleteness} uses, so the two steps agree about what absence is.
     */
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
