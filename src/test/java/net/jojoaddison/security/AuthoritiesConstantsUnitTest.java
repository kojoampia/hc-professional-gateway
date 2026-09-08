package net.jojoaddison.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
 * tests is the only thing holding them together. <b>The pair no longer says the same thing about
 * {@code ROLE_ANGEL}, and should not.</b> hc-patient keeps the authority and asserts it is outside its
 * clinical sets; this stack removed it entirely on 2026-09-08 and asserts it is nowhere at all — see
 * {@link #noPrivilegeSetInThisClassNamesTheCareAngelAuthority}.
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
    void theBaseUserAuthorityIsNotAmongThem() {
        // ROLE_USER's absence is the reason the rule exists at all. Every account on all three
        // stacks holds it -- an applicant here, and a patient in hc-patient, which grants it
        // alongside ROLE_PATIENT -- so naming it would restore the `.authenticated()` this replaced.
        assertThat(AuthoritiesConstants.CLINICAL_AND_ADMIN)
            .doesNotContain(AuthoritiesConstants.USER)
            .doesNotContain(AuthoritiesConstants.PATIENT)
            .doesNotContain(AuthoritiesConstants.ANONYMOUS);
    }

    /**
     * The care angel does not exist in this stack — not as a constant, and not inside any privilege
     * set this class declares.
     *
     * <p><b>This replaces two named tests and is deliberately wider than either.</b> Until 2026-09-08
     * there was a {@code theCareAngelAuthorityIsNotAmongThem} naming {@code CLINICAL_AND_ADMIN} and a
     * {@code theCareAngelAuthorityStillExists} asserting the constant. Item 44 removed the authority
     * from this subsystem altogether — an angel supports a patient, and hc-patient owns the concept —
     * so the second is false and the first would have gone with the constant it referenced, taking the
     * guard with it. That was the risk worth avoiding: the tests existed to stop somebody putting
     * {@code ROLE_ANGEL} back into a privilege set, and deleting the constant is exactly the change
     * that makes putting it back feel like closing a gap.
     *
     * <p><b>It reads the class rather than a list of field names</b>, for the reason
     * {@code ServicesRouteAuthorizationIT} derives its admitted set and {@code JhipsterEnumFieldValuesTest}
     * in {@code api/} derives its expectations: a guard that names its own coverage stops covering
     * things. A tenth authority constant, or a third privilege array added next year, is checked on the
     * day it is written with nobody having edited this file. The literal is spelled out here because
     * there is no longer a constant to reference — which is the whole point.
     *
     * <p>What it cannot see is a bare {@code "ROLE_ANGEL"} written into a matcher in
     * {@code SecurityConfiguration} without passing through this class.
     * {@code ServicesRouteAuthorizationIT} covers that from the other side, by sending a token that
     * carries the authority and requiring 403.
     */
    @Test
    void noPrivilegeSetInThisClassNamesTheCareAngelAuthority() {
        List<String> offenders = new ArrayList<>();

        for (Field field : AuthoritiesConstants.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            Object value;
            try {
                value = field.get(null);
            } catch (IllegalAccessException e) {
                throw new AssertionError("could not read " + field.getName(), e);
            }
            if (value instanceof String authority && "ROLE_ANGEL".equals(authority)) {
                offenders.add(field.getName() + " declares ROLE_ANGEL");
            } else if (value instanceof String[] set && Arrays.asList(set).contains("ROLE_ANGEL")) {
                offenders.add(field.getName() + " contains ROLE_ANGEL");
            }
        }

        assertThat(offenders)
            .as(
                "ROLE_ANGEL is hc-patient's authority and has no meaning in this subsystem (docs/backlog.md item 44). " +
                "A token carrying it may still arrive here over the shared signing key, or be held by an account " +
                "created before the removal; it must go on granting nothing"
            )
            .isEmpty();
    }
}
