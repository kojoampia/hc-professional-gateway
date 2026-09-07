package net.jojoaddison.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * What {@link AuthoritiesConstants#CLINICAL_AND_ADMIN} names, asserted rather than read.
 *
 * <p>That array is the whole of the authorization rule on {@code /services/**} — the one thing
 * standing between a token this gateway did not mint and the clinical surface of three stacks. Every
 * test that exercises the rule derives its cases from the array ({@code ServicesRouteAuthorizationIT}
 * deliberately, so a tenth discipline is covered the day it is added), which means the array's
 * <em>contents</em> are the one thing those tests cannot check: adding a name adds a passing case,
 * and removing one removes a case rather than failing anything. This class is where the contents are
 * spelled out.
 *
 * <p>Its sibling is {@code hc-patient}'s {@code AuthoritiesConstantsUnitTest}. The two repositories
 * share no artefact and cannot, so each writes the same eight discipline names down and the pair of
 * tests is the only thing holding them together.
 */
class AuthoritiesConstantsUnitTest {

    @Test
    void theServicesRuleAdmitsTheAdministratorAndTheEightClinicalDisciplines() {
        // Literal strings, not the constants beside them: a typo in a constant would be copied into
        // the expectation and assert nothing. These are the exact values that arrive in an `auth`
        // claim, and hc-patient's own test writes the same eight down for the same reason.
        assertThat(AuthoritiesConstants.CLINICAL_AND_ADMIN).containsExactlyInAnyOrder(
            "ROLE_ADMIN",
            "ROLE_DOCTOR",
            "ROLE_NURSE",
            "ROLE_PARAMEDIC",
            "ROLE_PHARMACIST",
            "ROLE_THERAPIST",
            "ROLE_CARER",
            "ROLE_CHEMIST",
            "ROLE_TECHNICIAN"
        );
    }

    @Test
    void theCareAngelAuthorityIsNotAmongThem() {
        // THE ESTATE DECIDED ON 2026-09-06 THAT AN ANGEL IS NOT A CLINICAL DISCIPLINE (backlog
        // item 30), and this array is where the over-grant actually lived: naming ROLE_ANGEL here
        // opened /services/** — professionalservice's clinical surface AND the cross-stack
        // patientservice and adminservice routes — to every angel in the estate.
        //
        // A discipline is a standing capability. An angel's authority is an ACTIVE CareDelegation
        // over ONE patient, held in hc-patient and re-read per request so a revocation takes effect
        // on the next call rather than when a rememberMe token expires. A role check in a gateway
        // can express none of that — not the patient, not the dates, not the revocability.
        //
        // Putting it back would look like closing a gap, because ROLE_ANGEL is still a seeded
        // authority (InitialSetupMigration) and still one of the nine values web/ and mobile/ know
        // about. It is not a gap. See the note on the constant.
        assertThat(AuthoritiesConstants.CLINICAL_AND_ADMIN).doesNotContain(AuthoritiesConstants.ANGEL);
    }

    @Test
    void theBaseUserAuthorityIsNotAmongThem() {
        // ROLE_USER's absence is the reason the rule exists at all. Every account on all three
        // stacks holds it -- an applicant here, and a patient in hc-patient, which grants it
        // alongside ROLE_PATIENT -- so naming it would restore the `.authenticated()` this replaced.
        assertThat(AuthoritiesConstants.CLINICAL_AND_ADMIN)
            .doesNotContain(AuthoritiesConstants.USER)
            .doesNotContain(AuthoritiesConstants.PATIENT)
            .doesNotContain(AuthoritiesConstants.ANONYMOUS);
    }

    @Test
    void theCareAngelAuthorityStillExists() {
        // The narrowing is a scope change, not a retirement. ROLE_ANGEL is still seeded by
        // InitialSetupMigration, still assignable, still carried in a token and still one of the
        // nine values web/ and mobile/ enumerate -- an angel signs in and reaches the three islands
        // that sit above the /services/** rule. Deleting the constant would be a different and much
        // larger change, and this asserts that it was not made by accident.
        assertThat(AuthoritiesConstants.ANGEL).isEqualTo("ROLE_ANGEL");
    }
}
