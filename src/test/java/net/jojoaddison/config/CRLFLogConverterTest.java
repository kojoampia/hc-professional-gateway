package net.jojoaddison.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

/**
 * The log-forging defence, which had no test of any kind.
 *
 * <p>Its job is to stop a caller putting CR or LF into a value that gets logged and thereby writing
 * what looks like a second, fabricated log line — see the OWASP Log Injection reference on the class
 * itself. That makes it a security control, and an untested security control is the kind that keeps
 * working right up until someone reformats it.
 *
 * <p>What is actually asserted here is the transformation, not that the method runs: the difference
 * between this converter working and silently passing input through is one {@code replaceAll}, and
 * both compile.
 */
class CRLFLogConverterTest {

    private CRLFLogConverter converter;

    @BeforeEach
    void setUp() {
        converter = new CRLFLogConverter();
        converter.start();
    }

    private static ILoggingEvent eventFrom(String loggerName, Marker... markers) {
        ILoggingEvent event = mock(ILoggingEvent.class);
        when(event.getLoggerName()).thenReturn(loggerName);
        when(event.getMarkerList()).thenReturn(markers.length == 0 ? List.of() : List.of(markers));
        return event;
    }

    /**
     * The attack this exists to stop: a newline in attacker-controlled input, which without
     * replacement ends the current line and begins one the attacker composed.
     */
    @Test
    void replacesTheCharactersThatWouldForgeANewLogLine() {
        ILoggingEvent event = eventFrom("net.jojoaddison.web.rest.UserResource");

        String forged = "login=ama\n2026-08-19 ERROR admin deleted everything";

        assertThat(converter.transform(event, forged))
            .isEqualTo("login=ama_2026-08-19 ERROR admin deleted everything")
            .doesNotContain("\n");
    }

    /** Carriage return and tab go the same way — all three are in the character class. */
    @Test
    void replacesCarriageReturnAndTabAsWell() {
        ILoggingEvent event = eventFrom("net.jojoaddison.service.UserService");

        assertThat(converter.transform(event, "a\rb\tc\nd")).isEqualTo("a_b_c_d");
    }

    /** Every occurrence, not merely the first — one survivor is enough to forge a line. */
    @Test
    void replacesEveryOccurrenceRatherThanTheFirst() {
        ILoggingEvent event = eventFrom("net.jojoaddison.web.rest.AccountResource");

        assertThat(converter.transform(event, "\n\n\n")).isEqualTo("___");
    }

    /** Ordinary text is returned unchanged; a converter that mangles clean input is its own bug. */
    @Test
    void leavesAMessageWithoutControlCharactersAlone() {
        ILoggingEvent event = eventFrom("net.jojoaddison.service.MailService");

        assertThat(converter.transform(event, "mail sent to j***@example.com")).isEqualTo("mail sent to j***@example.com");
    }

    /**
     * The allowlist. These frameworks format multi-line output deliberately — Spring Boot's failure
     * analyzers and Hibernate's SQL among them — and replacing their newlines would turn a readable
     * diagnostic into one long line. They are trusted because the text is theirs, not a caller's.
     */
    @Test
    void leavesTheAllowlistedFrameworkLoggersUntouched() {
        for (String safeLogger : new String[] {
            "org.hibernate.SQL",
            "org.springframework.boot.autoconfigure.condition.ConditionEvaluationReportLogger",
            "org.springframework.boot.diagnostics.LoggingFailureAnalysisReporter",
        }) {
            assertThat(converter.transform(eventFrom(safeLogger), "line one\nline two"))
                .as("logger %s", safeLogger)
                .isEqualTo("line one\nline two");
        }
    }

    /**
     * Matched by prefix, so a child logger inherits the exemption — and, less comfortably, so would
     * any logger whose name merely starts with one of those strings. Pinned as documentation of the
     * rule rather than as endorsement: {@code org.hibernate} covers every Hibernate logger, which is
     * the intent, but the check is {@code startsWith} and not a package-boundary comparison.
     */
    @Test
    void matchesTheAllowlistByPrefixIncludingChildLoggers() {
        assertThat(converter.transform(eventFrom("org.hibernate.type.descriptor.sql.BasicBinder"), "a\nb")).isEqualTo("a\nb");
    }

    /** A logger whose name does not begin with an allowlisted prefix gets no exemption. */
    @Test
    void doesNotExemptALoggerThatIsNotOnTheAllowlist() {
        assertThat(converter.transform(eventFrom("com.example.hibernate"), "a\nb")).isEqualTo("a_b");
        assertThat(converter.transform(eventFrom("net.jojoaddison.web.rest.UserResource"), "a\nb")).isEqualTo("a_b");
    }

    /**
     * <b>The allowlist is a raw string prefix, so a logger named {@code org.hibernateX} is exempt.</b>
     *
     * <p>{@code isLoggerSafe} asks {@code getLoggerName().startsWith("org.hibernate")}, which is true
     * of {@code org.hibernateX.Sneaky} as surely as of {@code org.hibernate.SQL} — there is no check
     * that the prefix ends on a package boundary. Anything logged through such a logger keeps its CR
     * and LF, which is the one thing this class exists to prevent.
     *
     * <p>Recorded rather than fixed. It is a weakness and not a hole: it takes a logger deliberately
     * named to straddle the prefix, which means code in this repository, and at that point the
     * attacker is already inside. A boundary-aware check ({@code equals} or {@code startsWith(prefix
     * + ".")}) would close it, and this test is here so that change is a visible decision rather
     * than a silent one. This assertion documents today's behaviour — if it is fixed, this fails.
     */
    @Test
    void exemptsAnyLoggerWhoseNameMerelyStartsWithAnAllowlistedPrefix() {
        assertThat(converter.transform(eventFrom("org.hibernateX.Sneaky"), "a\nb")).isEqualTo("a\nb");
        assertThat(converter.transform(eventFrom("org.springframework.boot.autoconfigureXYZ"), "a\nb")).isEqualTo("a\nb");
    }

    /**
     * The explicit opt-out: code that knows its own message is safe and wants it readable marks the
     * event. This is the escape hatch, so it is worth a test that it actually opens.
     */
    @Test
    void honoursTheCrlfSafeMarker() {
        ILoggingEvent event = eventFrom("net.jojoaddison.config.WebConfigurer", CRLFLogConverter.CRLF_SAFE_MARKER);

        assertThat(converter.transform(event, "a\nb")).isEqualTo("a\nb");
    }

    /**
     * Only the first marker is consulted — {@code markers.get(0).contains(...)} — so a CRLF_SAFE
     * marker sitting behind another one does not exempt the event. Recorded because it is a real
     * limitation of the check rather than an accident of this test's construction.
     */
    @Test
    void consultsOnlyTheFirstMarkerInTheList() {
        Marker unrelated = MarkerFactory.getMarker("SOMETHING_ELSE");
        ILoggingEvent event = eventFrom("net.jojoaddison.config.WebConfigurer", unrelated, CRLFLogConverter.CRLF_SAFE_MARKER);

        assertThat(converter.transform(event, "a\nb")).isEqualTo("a_b");
    }

    /** An event carrying no markers at all must not trip the marker check. */
    @Test
    void handlesAnEventWithNoMarkers() {
        assertThat(converter.transform(eventFrom("net.jojoaddison.web.rest.UserResource"), "a\nb")).isEqualTo("a_b");
    }

    /**
     * With no colour option configured the replacement is a bare underscore. The coloured variant
     * wraps the same underscore in ANSI escapes, so the guarantee that matters — no CR, LF or tab
     * survives — holds either way.
     */
    @Test
    void usesABareUnderscoreWhenNoColourOptionIsConfigured() {
        assertThat(converter.transform(eventFrom("net.jojoaddison.Anything"), "x\ny")).isEqualTo("x_y").doesNotContain("\n", "\r", "\t");
    }
}
