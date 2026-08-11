package net.jojoaddison.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Masking is what lets the mail outcomes be logged at INFO without putting addresses into a stream
 * that leaves the host. It is pure, so it is tested here rather than through the mail path.
 *
 * <p>The property that matters is that the local part never survives — everything else is
 * presentation.
 */
class MailServiceMaskEmailUnitTest {

    @Test
    void keepsTheFirstCharacterAndTheDomain() {
        // The domain stays because that is what a relay problem clusters on.
        assertThat(MailService.maskEmail("jane.doe@example.com")).isEqualTo("j***@example.com");
    }

    @Test
    void hidesTheLocalPartEvenWhenItIsASingleCharacter() {
        assertThat(MailService.maskEmail("a@example.com")).isEqualTo("a***@example.com");
    }

    @Test
    void keepsNothingWhenThereIsNoLocalPartToKeep() {
        // A bare domain or a malformed value: better to log nothing than half an address.
        assertThat(MailService.maskEmail("@example.com")).isEqualTo("***");
        assertThat(MailService.maskEmail("not-an-address")).isEqualTo("***");
    }

    @Test
    void survivesAMissingAddress() {
        // sendEmailSync is reachable with a null `to` from the generic sendEmail path.
        assertThat(MailService.maskEmail(null)).isEqualTo("(none)");
        assertThat(MailService.maskEmail("   ")).isEqualTo("(none)");
    }

    @Test
    void neverLeavesTheLocalPartInTheOutput() {
        String masked = MailService.maskEmail("verydistinctivename@example.com");

        assertThat(masked).doesNotContain("verydistinctivename");
        assertThat(masked).contains("@example.com");
    }
}
