package com.webkreator.qlue.view.canoe;

import com.webkreator.qlue.util.HtmlEncoder;
import com.webkreator.qlue.util.HtmlEncoder.TrustedOrigin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code HtmlEncoder.urlResource()} and its {@link TrustedOrigin} allowlist — the R9 encoder for the
 * six resource-loading URL sinks ({@code <script src>}, {@code <iframe src>}, {@code <object data>},
 * {@code <embed src>}, {@code <link href>}, {@code <base href>}).
 *
 * <p>It is {@code url()} plus an origin filter: it rejects a value that introduces an authority whose
 * host is not on the configured allowlist, and passes everything else through {@code url()}
 * unchanged. "Off-origin" cannot mean "different from ours" at encode time — Canoe does not know the
 * deploying application's own origin — so it means "specifies an authority at all", which is the
 * honest, encode-time-sound reading and is what these tests pin.
 */
public class HtmlEncoderResourceUrlTest {

    private static final List<TrustedOrigin> NONE = Collections.emptyList();

    private static String reject(String input) {
        return HtmlEncoder.urlResource(input, NONE);
    }

    // ------------------------------------------------------------------
    // The default: no allowlist, so any authority is rejected
    // ------------------------------------------------------------------

    /**
     * Every shape that introduces an authority a browser would act on is rejected to the empty
     * string, including the {@code scheme:host} and {@code scheme:/host} forms a browser reads as an
     * authority without any {@code //} — the ones an origin filter that only looked for {@code //}
     * would miss.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "//attacker.invalid/x.js",
            "https://attacker.invalid/x.js",
            "HTTPS://attacker.invalid/x.js",
            "http://attacker.invalid/x.js",
            "http:attacker.invalid/x.js",
            "http:/attacker.invalid/x.js",
            "https://attacker.invalid:8443/x.js",
            "https://[2001:db8::1]/x.js",
            "https://[2001:db8::1]:8443/x.js",
            "///attacker.invalid/x.js",
            "https://attacker.invalid?x",   // authority ends at the query
            "https://attacker.invalid#x",   // authority ends at the fragment
            "https://attacker.invalid:99999999999999/x.js", // out-of-range port; host still off-origin
    })
    public void anyOffOriginAuthorityIsRejectedWithNoAllowlist(String input) {
        assertEquals("", reject(input),
                () -> input + " introduces an off-origin authority, so it must be suppressed");
        assertEquals("", HtmlEncoder.urlResource(input, null),
                () -> input + " must also be rejected when the allowlist argument is null");
    }

    /**
     * A value that introduces no authority is passed through {@code url()} unchanged: a relative
     * reference cannot leave whatever origin the page is on, whether or not Canoe knows what that
     * origin is.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "/app.js          | /app.js",
            "app.js           | app.js",
            "/a/b/c           | /a/b/c",
            "?q=1             | ?q=1",
            "#frag            | #frag",
            "mailto:a@b.com   | mailto:a@b.com",
    })
    public void aValueWithNoAuthorityIsPassedThroughUnchanged(String input, String expected) {
        assertEquals(expected, reject(input));
    }

    /** An empty or already-rejected-scheme value stays empty, as {@code url()} left it. */
    @Test
    public void emptyAndRejectedSchemeStayEmpty() {
        assertEquals("", reject(""));
        assertEquals("", reject("javascript:alert(1)"));
        assertEquals("", reject("data:text/html,x"));
        assertNull(HtmlEncoder.urlResource(null, NONE));
        assertNull(HtmlEncoder.urlResource(null, null));
    }

    /**
     * The tricks {@code url()} already neutralises are not double-counted as an authority: a backslash
     * a browser reads as a slash is a {@code %5C} path here, and a userinfo {@code @} is a {@code %40}
     * that makes the host fail to parse. Neither reaches a live off-origin authority, so both pass
     * through rather than being suppressed — the reject-only-what-is-live property.
     */
    @Test
    public void valuesUrlAlreadyNeutralisedAreNotSuppressedAgain() {
        assertEquals("/%5Cattacker.invalid/x.js", reject("/\\attacker.invalid/x.js"));
        assertEquals("%5C%5Cattacker.invalid/x.js", reject("\\\\attacker.invalid/x.js"));
        assertEquals("https://trusted.example%40attacker.invalid/x.js",
                reject("https://trusted.example@attacker.invalid/x.js"));
    }

    /**
     * A host that fails to parse — a forbidden code point, an unterminated IPv6 bracket, a control
     * character, DEL, or an empty host — is reported as no live authority, so the (already inert)
     * {@code url()} output stands rather than being wrongly flagged as a breach.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "https://ho:st/x",              // a stray colon is a forbidden host char
            "https://[oops/x",              // unterminated IPv6 bracket
            "https://[::1]junk/x",          // junk after the IPv6 literal
            "https://ho%01st/x",            // a C0 control in the host
            "https://ho%7Fst/x",            // DEL in the host
            "https://ho%st/x",              // a lone percent, itself forbidden
            "https://:8443/x",              // empty host
            "https://attacker.invalid:/x",  // a trailing colon with no port digits
            "https://[2001:db8::1]:x/y",    // an IPv6 literal with a non-numeric port
            "http://",                      // scheme then only slashes: empty authority
            "//",                           // protocol-relative with an empty authority
    })
    public void aHostThatFailsToParseIsNotAnOffOriginAuthority(String input) {
        // Whatever url() produced is returned verbatim; the point is that it is NOT suppressed to "".
        assertEquals(HtmlEncoder.url(input), reject(input),
                () -> input + " has no parseable off-origin authority, so urlResource must pass"
                        + " url()'s output through");
    }

    // ------------------------------------------------------------------
    // The allowlist
    // ------------------------------------------------------------------

    @Test
    public void aBareHostEntryAdmitsAnyAllowedSchemeAndPort() {
        List<TrustedOrigin> cdn = HtmlEncoder.parseTrustedOrigins(Arrays.asList("cdn.example.com"));
        assertEquals("//cdn.example.com/a.js", HtmlEncoder.urlResource("//cdn.example.com/a.js", cdn));
        assertEquals("https://cdn.example.com/a.js",
                HtmlEncoder.urlResource("https://cdn.example.com/a.js", cdn));
        assertEquals("http://cdn.example.com/a.js",
                HtmlEncoder.urlResource("http://cdn.example.com/a.js", cdn));
        assertEquals("https://cdn.example.com:8443/a.js",
                HtmlEncoder.urlResource("https://cdn.example.com:8443/a.js", cdn));
        // A different host is still rejected.
        assertEquals("", HtmlEncoder.urlResource("//attacker.invalid/a.js", cdn));
    }

    @Test
    public void anOriginEntryPinsTheScheme() {
        List<TrustedOrigin> https = HtmlEncoder.parseTrustedOrigins(Arrays.asList("https://cdn.example.com"));
        assertEquals("https://cdn.example.com/a.js",
                HtmlEncoder.urlResource("https://cdn.example.com/a.js", https));
        assertEquals("", HtmlEncoder.urlResource("http://cdn.example.com/a.js", https),
                "the http downgrade is off-scheme");
        // A protocol-relative URL inherits the page scheme, so a scheme-qualified entry still admits it.
        assertEquals("//cdn.example.com/a.js",
                HtmlEncoder.urlResource("//cdn.example.com/a.js", https));
    }

    @Test
    public void anOriginEntryWithAPortPinsThePort() {
        List<TrustedOrigin> pinned =
                HtmlEncoder.parseTrustedOrigins(Arrays.asList("https://cdn.example.com:8443"));
        assertEquals("https://cdn.example.com:8443/a.js",
                HtmlEncoder.urlResource("https://cdn.example.com:8443/a.js", pinned));
        assertEquals("", HtmlEncoder.urlResource("https://cdn.example.com:9000/a.js", pinned),
                "a different port is a different origin");
        assertEquals("", HtmlEncoder.urlResource("https://cdn.example.com/a.js", pinned),
                "the default port does not match an explicit :8443 entry");
    }

    @Test
    public void anIpv6OriginIsMatched() {
        List<TrustedOrigin> v6 = HtmlEncoder.parseTrustedOrigins(Arrays.asList("[2001:db8::1]"));
        assertEquals("https://[2001:db8::1]/a.js",
                HtmlEncoder.urlResource("https://[2001:db8::1]/a.js", v6));
        assertEquals("", HtmlEncoder.urlResource("https://[2001:db8::2]/a.js", v6));
    }

    @Test
    public void aNullOrBlankListEntryIsSkipped() {
        List<TrustedOrigin> origins =
                HtmlEncoder.parseTrustedOrigins(Arrays.asList("cdn.example.com", null, "   ", ""));
        assertEquals(1, origins.size());
        assertTrue(HtmlEncoder.parseTrustedOrigins(null).isEmpty());
    }

    // ------------------------------------------------------------------
    // TrustedOrigin.parse validation
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "cdn.example.com",
            "https://cdn.example.com",
            "http://cdn.example.com",
            "cdn.example.com:8443",
            "https://cdn.example.com:8443",
            "[2001:db8::1]",
            "[2001:db8::1]:8443",
            "  cdn.example.com  ",
    })
    public void aLegalOriginParses(String raw) {
        TrustedOrigin origin = TrustedOrigin.parse(raw);
        assertTrue(origin.toString().length() > 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "ftp://cdn.example.com",         // scheme off {http, https}
            "cdn.example.com/path",          // a path
            "user@cdn.example.com",          // userinfo
            "[2001:db8::1",                  // unterminated IPv6
            "[2001:db8::1]junk",             // junk after IPv6 literal
            "cdn.example.com:abc",           // non-numeric port
            "cdn.example.com:8.4",           // a dot is below '0', so not all digits
            "cdn.example.com:99999",         // port out of range
            "cdn.example.com:99999999999999",// digits that overflow an int
            ":8443",                         // empty host
            "cd n.example.com",              // illegal host character
    })
    public void anIllegalOriginIsRefused(String raw) {
        assertThrows(IllegalArgumentException.class, () -> TrustedOrigin.parse(raw));
    }

    @Test
    public void aNullOriginStringIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> TrustedOrigin.parse(null));
    }

    @Test
    public void theToStringRoundTripsTheThreeShapes() {
        assertEquals("cdn.example.com", TrustedOrigin.parse("cdn.example.com").toString());
        assertEquals("https://cdn.example.com",
                TrustedOrigin.parse("https://cdn.example.com").toString());
        assertEquals("https://cdn.example.com:8443",
                TrustedOrigin.parse("HTTPS://cdn.example.com:8443").toString());
    }
}
