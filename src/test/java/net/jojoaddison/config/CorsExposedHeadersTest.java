package net.jojoaddison.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;

/**
 * Every header a cross-origin client is expected to read is named in {@code jhipster.cors.exposed-headers}.
 *
 * <p><b>A header a browser cannot read is the same as one never sent.</b> CORS hides every response header
 * from JavaScript except a short safelist and whatever {@code Access-Control-Expose-Headers} names, and it
 * does so <em>silently</em> — the request succeeds, the header is on the wire, and {@code headers.get(...)}
 * returns {@code null}. A client parsing it sees an absent header and falls back to whatever it does when
 * nothing was restricted, which is exactly the silence backlog items 107, 112, 114, 126 and 128 exist to
 * remove.
 *
 * <p>That is not hypothetical. Items 114 and 126 shipped both clients reading {@code X-Restricted-Parts}
 * while this list did not carry it. {@code web/} was unaffected — it is served same-origin with the
 * gateway — but {@code mobile/} runs at {@code capacitor://localhost}, is listed as an allowed origin, and
 * uses Angular's {@code HttpClient} rather than {@code CapacitorHttp}, so CORS applies to it. Its half of
 * both items parsed a header that never arrived.
 *
 * <p><b>This reads the shipping file by path, and that is the point.</b> {@code CorsBrowserOriginIT}
 * beside it supplies its own CORS properties through {@code @TestPropertySource}, so it proves the filter
 * works and can say nothing about what production is configured to expose — its own javadoc warns that
 * the failure there is silent. A test that asserts against configuration it supplied itself cannot catch
 * a configuration mistake, which is the lesson backlog items 50 and 86 paid for in {@code api/}.
 *
 * <p><b>These names are a cross-repo contract</b> and are spelled as literals deliberately: they are
 * minted in {@code api/}'s {@code PatientResource}, consumed in {@code web/} and {@code mobile/}, and
 * travel through this gateway, which compiles against none of them. A constant imported from anywhere
 * would only move the place the three copies can drift apart. If a header is renamed there, this test is
 * meant to fail here.
 */
class CorsExposedHeadersTest {

    /**
     * The shipping configuration, by path rather than from the classpath: {@code src/test/resources} would
     * shadow it, and a test reading its own copy is the trap this class exists to avoid.
     */
    private static final Path PRODUCTION_CONFIG = Path.of("src/main/resources/config/application-prod.yml");

    private static final Path DEVELOPMENT_CONFIG = Path.of("src/main/resources/config/application-dev.yml");

    @ParameterizedTest(name = "{0} is exposed to cross-origin clients")
    @ValueSource(strings = { "X-Restricted-Parts", "X-Restricted-Follow-Ups" })
    @DisplayName("a header the clients parse is one a browser is allowed to read")
    void everyHeaderTheClientsParseIsExposed(String header) throws IOException {
        assertThat(exposedHeadersOf(PRODUCTION_CONFIG))
            .as(
                "%s is read by web/ and mobile/ but is not in %s's jhipster.cors.exposed-headers, " +
                "so a cross-origin caller such as mobile/ (capacitor://localhost) cannot see it and " +
                "silently behaves as though nothing was restricted",
                header,
                PRODUCTION_CONFIG
            )
            .contains(header);

        assertThat(exposedHeadersOf(DEVELOPMENT_CONFIG))
            .as("%s must also be exposed in %s, or the defect is invisible until production", header, DEVELOPMENT_CONFIG)
            .contains(header);
    }

    /**
     * Reads {@code jhipster.cors.exposed-headers} and splits it the way Spring Boot does.
     *
     * <p>Returned as a list of trimmed names rather than the raw string so that a substring match cannot
     * pass by accident — {@code X-Restricted-Parts} is a prefix of nothing here today, but asserting on
     * membership rather than on {@code String.contains} keeps that true if a longer name is ever added.
     */
    @SuppressWarnings("unchecked")
    private static List<String> exposedHeadersOf(Path config) throws IOException {
        try (var in = Files.newInputStream(config)) {
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> jhipster = (Map<String, Object>) root.get("jhipster");
            Map<String, Object> cors = (Map<String, Object>) jhipster.get("cors");
            Object exposed = cors.get("exposed-headers");
            assertThat(exposed).as("jhipster.cors.exposed-headers is absent from %s", config).isNotNull();
            return Arrays.stream(exposed.toString().split(",")).map(String::trim).toList();
        }
    }
}
