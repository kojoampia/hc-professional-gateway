package net.jojoaddison.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;
import java.util.TimeZone;
import net.jojoaddison.config.LocaleConfiguration.AngularCookieLocaleContextResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.SimpleLocaleContext;
import org.springframework.context.i18n.SimpleTimeZoneAwareLocaleContext;
import org.springframework.context.i18n.TimeZoneAwareLocaleContext;
import org.springframework.http.ResponseCookie;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

/**
 * How the gateway decides which language a request is in.
 *
 * <p>The largest untested surface in this service: 30 of its 34 branches were never taken. That
 * matters more here than the number suggests, because <b>four languages are a standing condition of
 * this platform</b> — en, es, fr and de ship together or the change is not done — and this is the
 * code that turns a browser's cookie into the locale the rest of the request runs under.
 *
 * <p>The cookie is Angular's {@code NG_TRANSLATE_LANG_KEY}, whose value the client writes wrapped in
 * literal {@code %22} quote marks and which may carry a time zone after a space. Both of those are
 * shapes this resolver strips by hand, and neither is exercised anywhere else.
 */
class AngularCookieLocaleContextResolverTest {

    private static final String COOKIE = "NG_TRANSLATE_LANG_KEY";

    private AngularCookieLocaleContextResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new AngularCookieLocaleContextResolver();
    }

    private static MockServerWebExchange exchangeWithCookie(String value) {
        return MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/account").cookie(new org.springframework.http.HttpCookie(COOKIE, value))
        );
    }

    private Locale localeFrom(String cookieValue) {
        return resolver.resolveLocaleContext(exchangeWithCookie(cookieValue)).getLocale();
    }

    /**
     * The four the platform ships. Parameterised by hand rather than with {@code @ValueSource} so
     * the list reads as the invariant it is: if a fifth language is added, this is one of the places
     * that has to change.
     */
    @Test
    void resolvesEachOfTheFourShippedLanguages() {
        assertThat(localeFrom("%22en%22")).isEqualTo(Locale.ENGLISH);
        assertThat(localeFrom("%22es%22")).isEqualTo(Locale.of("es"));
        assertThat(localeFrom("%22fr%22")).isEqualTo(Locale.FRENCH);
        assertThat(localeFrom("%22de%22")).isEqualTo(Locale.GERMAN);
    }

    /**
     * The quoting is the whole reason this class exists rather than Spring's own cookie resolver.
     * Angular writes the value with literal {@code %22} either side, and a resolver that did not
     * strip them would parse a language tag of {@code %22en%22} and fail.
     */
    @Test
    void stripsTheAngularQuoteMarkersFromEitherSide() {
        assertThat(localeFrom("%22fr%22")).isEqualTo(Locale.FRENCH);
        // Unquoted works too — the strip is unconditional, so a client that stops quoting keeps working.
        assertThat(localeFrom("fr")).isEqualTo(Locale.FRENCH);
    }

    /** A region tag, written with the hyphen the web uses and parsed as the underscore Java wants. */
    @Test
    void convertsAHyphenatedLanguageTagToAJavaLocale() {
        assertThat(localeFrom("%22en-GB%22")).isEqualTo(Locale.of("en", "GB"));
        assertThat(localeFrom("%22de-CH%22")).isEqualTo(Locale.of("de", "CH"));
    }

    /** A time zone may follow the locale after a space; both halves have to survive the split. */
    @Test
    void parsesATimeZoneThatFollowsTheLocale() {
        LocaleContext context = resolver.resolveLocaleContext(exchangeWithCookie("%22fr Africa/Accra%22"));

        assertThat(context.getLocale()).isEqualTo(Locale.FRENCH);
        assertThat(((TimeZoneAwareLocaleContext) context).getTimeZone()).isEqualTo(TimeZone.getTimeZone("Africa/Accra"));
    }

    /**
     * The sentinel for "no locale, but a time zone": Angular writes a bare hyphen where the language
     * would be. The locale must come back null rather than being parsed as a language called "-".
     */
    @Test
    void treatsAHyphenAsNoLocaleAtAll() {
        LocaleContext context = resolver.resolveLocaleContext(exchangeWithCookie("%22- Africa/Accra%22"));

        assertThat(context.getLocale()).isNull();
        assertThat(((TimeZoneAwareLocaleContext) context).getTimeZone()).isEqualTo(TimeZone.getTimeZone("Africa/Accra"));
    }

    /** No cookie: nothing is resolved here, and the request falls through to Spring's own default. */
    @Test
    void resolvesNoLocaleWhenTheCookieIsAbsent() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/account"));

        LocaleContext context = resolver.resolveLocaleContext(exchange);

        assertThat(context.getLocale()).isNull();
        assertThat(((TimeZoneAwareLocaleContext) context).getTimeZone()).isNull();
    }

    /**
     * An already-resolved request is not parsed twice — the attribute short-circuits the whole
     * method. This is what stops a later filter's {@code setLocaleContext} being undone by a
     * subsequent read of the original cookie.
     */
    @Test
    void doesNotReparseTheCookieOnceTheLocaleIsResolved() {
        MockServerWebExchange exchange = exchangeWithCookie("%22fr%22");

        resolver.setLocaleContext(exchange, new SimpleLocaleContext(Locale.GERMAN));

        assertThat(resolver.resolveLocaleContext(exchange).getLocale()).isEqualTo(Locale.GERMAN);
    }

    /** Writing a locale sets the cookie in the Angular shape the client expects to read back. */
    @Test
    void writesTheCookieBackInTheShapeAngularReads() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/account"));

        resolver.setLocaleContext(exchange, new SimpleLocaleContext(Locale.of("es")));

        ResponseCookie written = exchange.getResponse().getCookies().getFirst(COOKIE);
        assertThat(written).isNotNull();
        assertThat(written.getValue()).isEqualTo("%22es%22");
        assertThat(written.getPath()).isEqualTo("/");
    }

    /**
     * <b>Writing a time zone throws. The round trip this class is built for does not work.</b>
     *
     * <p>{@code setLocaleContext} composes the value as {@code %22<locale> <zone>%22} — with a
     * literal space — and {@code ResponseCookie} refuses it: {@code IllegalArgumentException:
     * RFC2616 cookie value cannot have ' '}. So the write half cannot produce the very shape the
     * read half goes to the trouble of parsing, and {@code parseLocaleCookieIfNecessary}'s
     * space-splitting can only ever fire on a cookie written by something other than this resolver.
     *
     * <p><b>Latent, not live.</b> The only caller is {@code localeChangeFilter}, which passes a
     * {@code SimpleLocaleContext} — not time-zone-aware — so {@code timeZone} stays null, no space
     * is composed, and nothing throws today. It becomes a 500 the moment any caller passes a
     * {@code TimeZoneAwareLocaleContext}, which is a perfectly ordinary thing to hand a
     * {@code LocaleContextResolver} and which {@code resolveLocaleContext} itself returns.
     *
     * <p>Pinned rather than fixed: the repair is either percent-encoding the separator or changing
     * it, and both change a cookie format the Angular client also reads. That is a decision to take
     * deliberately, not a side effect of adding tests. If it is fixed, this test fails and should be
     * replaced by the two assertions it displaced — that the value is {@code "%22fr Africa/Accra%22"}
     * and {@code "%22- UTC%22"} respectively.
     */
    @Test
    void throwsWhenAskedToWriteATimeZone() {
        MockServerWebExchange withLocale = MockServerWebExchange.from(MockServerHttpRequest.get("/api/account"));
        assertThatThrownBy(
            () ->
                resolver.setLocaleContext(
                    withLocale,
                    new SimpleTimeZoneAwareLocaleContext(Locale.FRENCH, TimeZone.getTimeZone("Africa/Accra"))
                )
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot have ' '");

        // Same failure for the time-zone-only form, where the locale is the '-' sentinel.
        MockServerWebExchange withoutLocale = MockServerWebExchange.from(MockServerHttpRequest.get("/api/account"));
        assertThatThrownBy(
            () -> resolver.setLocaleContext(withoutLocale, new SimpleTimeZoneAwareLocaleContext(null, TimeZone.getTimeZone("UTC")))
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot have ' '");
    }

    /**
     * The path that is actually taken in production: a time-zone-aware context whose zone is null
     * behaves exactly like a plain one, which is why the defect above has never been hit.
     */
    @Test
    void writesNormallyWhenATimeZoneAwareContextCarriesNoZone() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/account"));

        resolver.setLocaleContext(exchange, new SimpleTimeZoneAwareLocaleContext(Locale.GERMAN, null));

        assertThat(exchange.getResponse().getCookies().getFirst(COOKIE).getValue()).isEqualTo("%22de%22");
    }

    /**
     * A null context clears the cookie rather than writing an empty one — the max-age of zero is
     * what makes the browser drop it, and an expiry left off would leave the old language in place.
     */
    @Test
    void clearsTheCookieWhenTheContextIsNull() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/account"));

        resolver.setLocaleContext(exchange, null);

        ResponseCookie written = exchange.getResponse().getCookies().getFirst(COOKIE);
        assertThat(written.getValue()).isEmpty();
        assertThat(written.getMaxAge()).isZero();
    }

    /**
     * Setting a locale without a time zone must remove any previously resolved one, rather than
     * leaving the old zone attached to the new language.
     */
    @Test
    void dropsAPreviouslyResolvedTimeZoneWhenTheNewContextHasNone() {
        MockServerWebExchange exchange = exchangeWithCookie("%22fr Africa/Accra%22");
        assertThat(((TimeZoneAwareLocaleContext) resolver.resolveLocaleContext(exchange)).getTimeZone()).isNotNull();

        resolver.setLocaleContext(exchange, new SimpleLocaleContext(Locale.GERMAN));

        LocaleContext after = resolver.resolveLocaleContext(exchange);
        assertThat(after.getLocale()).isEqualTo(Locale.GERMAN);
        assertThat(((TimeZoneAwareLocaleContext) after).getTimeZone()).isNull();
    }
}
