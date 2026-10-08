package net.jojoaddison.service.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import java.io.Serializable;

/**
 * The body of {@code PUT /api/account}: the five fields a clinician may write on their own account.
 *
 * <p><b>An allow-list, and the point is that it is one by construction rather than by discipline.</b>
 * {@code POST /api/account} binds {@link AdminUserDTO}, which also carries {@code id}, {@code login},
 * {@code activated}, {@code authorities} and four audit fields, and discards all of them — correctly,
 * because {@code AccountResource} has always called the narrow
 * {@link net.jojoaddison.service.UserService#updateUser(String, String, String, String, String)}
 * rather than the {@code AdminUserDTO} overload that rewrites the authority set. But that correctness
 * lives in the <em>handler</em>, which means it is a deny-list maintained by hand: <b>a deny-list must
 * track a DTO that grows; an allow-list cannot acquire a field through someone else's edit.</b> A
 * field added to {@code AdminUserDTO} for the administrator's user-management screens arrives on the
 * self-service account endpoint for free, and nothing fails when the handler is not updated to drop
 * it.
 *
 * <p><b>A record rather than a class, for the same reason.</b> The component list <em>is</em> the
 * canonical constructor, so widening this type is not an edit somebody can make in passing — and the
 * components are in {@code UserService.updateUser}'s parameter order, so the type and the write agree
 * by reading rather than by checking.
 *
 * <p><b>There is deliberately no {@code langKey}-is-a-known-locale check and no {@code imageUrl}
 * format check here.</b> The constraints below are {@link AdminUserDTO}'s, field for field, so the
 * two verbs refuse the same bodies while both exist — {@code POST} is deprecated and retires in T6
 * (see {@code profile.md} § "Step 1 — Complete the account"), and a narrow type that validated
 * <em>differently</em> would make the deprecated verb the more permissive one, which is the wrong way
 * round for something a live client still calls.
 *
 * <p><b>What is not here is the authority list, and that is not an omission.</b> Step 1 edits an
 * account's name, language and avatar; the career role is a <em>request</em> granted after review and
 * is written on {@code ProfessionalApplication} by T3, never on the account by its holder. So this
 * endpoint needs no authority allow-list: there is no field through which an authority could arrive,
 * and the service method it calls cannot write one. See {@code AccountResource#updateAccount}.
 *
 * @param firstName the account holder's first name; required by step 1's client-side rule, not here
 * @param lastName  the account holder's last name
 * @param email     the account's email address; refused with {@code 400} when another login holds it
 * @param langKey   the UI language, one of the keys in {@code web/app/config/language.constants.ts}
 * @param imageUrl  the avatar URL; see {@code profile-addendum.md} Q12 — nothing in this estate
 *                  uploads one, and step 1 collects no input for it
 */
public record OwnAccountDTO(
    @Size(max = 50) String firstName,
    @Size(max = 50) String lastName,
    @Email @Size(min = 5, max = 254) String email,
    @Size(min = 2, max = 10) String langKey,
    @Size(max = 256) String imageUrl
)
    implements Serializable {}
