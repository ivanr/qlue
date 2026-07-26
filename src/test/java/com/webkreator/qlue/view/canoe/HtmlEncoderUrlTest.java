package com.webkreator.qlue.view.canoe;

import com.webkreator.qlue.util.HtmlEncoder;
import com.webkreator.qlue.view.canoe.corpus.VerdictEvaluator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code HtmlEncoder.url()}, which gets its own file because it is the only encoder with structure.
 *
 * <p>It splits its input on {@code ^(https?://)([^/]+)(/.*)?$}, emits the matched scheme
 * <strong>verbatim</strong>, and percent-encodes everything outside the allowlist
 * {@code a-zA-Z0-9 / . - # ? =}.
 *
 * <p>That design makes it a <em>scheme</em> filter and not an <em>origin</em> filter, which is F6.
 * It genuinely does neutralise {@code javascript:}, {@code data:} and {@code vbscript:} — the colon
 * is escaped, leaving a relative path — but a protocol-relative or absolute off-origin URL passes
 * through byte for byte, because every character in a hostname is on the allowlist. Canoe has already
 * overwritten the tag name by the time it reaches the attribute, so {@code <script src>} and
 * {@code <img src>} get the same treatment.
 *
 * <p>The allowlist also mangles legitimate URLs in five distinct ways, recorded here as F15. None is
 * a vulnerability — they all fail closed — but each one silently produces a URL that does not mean
 * what the template author wrote, and every one of them pushes a developer towards
 * {@code $_x.asis()}, which turns encoding off altogether.
 */
public class HtmlEncoderUrlTest {

    // ------------------------------------------------------------------
    // What the scheme filter genuinely stops
    // ------------------------------------------------------------------

    /**
     * The colon is not on the allowlist, so any scheme the regex does not match loses it. This is
     * real protection and it is worth stating plainly, because F6 is easy to over-read as "url()
     * does nothing".
     */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "javascript:alert(1)      | javascript%3Aalert%281%29",
            "vbscript:msgbox(1)       | vbscript%3Amsgbox%281%29",
            "livescript:alert(1)      | livescript%3Aalert%281%29",
            "mocha:alert(1)           | mocha%3Aalert%281%29",
            "view-source:http://x/    | view-source%3Ahttp%3A//x/",
            "blob:http://x/y          | blob%3Ahttp%3A//x/y",
    })
    public void escapesTheColonOfEveryUnrecognisedScheme(String input, String expected) {
        assertEquals(expected, HtmlEncoder.url(input.trim()));
        assertFalse(VerdictEvaluator.analyseUrl(HtmlEncoder.url(input.trim())).isDangerous(),
                "with the colon escaped this is a relative path, not a scheme");
    }

    /**
     * The regex is case-sensitive, so an uppercase scheme does not match and its colon is escaped
     * too. Accidental protection — the regex was presumably meant to be case-insensitive — but
     * protection nonetheless, and worth pinning so that "fixing" the regex is recognised as removing
     * a defence.
     */
    @Test
    public void anUppercaseSchemeIsEscapedBecauseTheRegexIsCaseSensitive() {
        assertEquals("HTTP%3A//host/path", HtmlEncoder.url("HTTP://host/path"));
        assertEquals("HTTPS%3A//host/path", HtmlEncoder.url("HTTPS://host/path"));
        assertFalse(VerdictEvaluator.analyseUrl("HTTPS%3A//attacker.invalid/x").isDangerous());

        // Making the regex case-insensitive would let the host through, as the lowercase form shows.
        assertTrue(VerdictEvaluator.analyseUrl(
                HtmlEncoder.url("https://attacker.invalid/x")).isDangerous());
    }

    /** An empty or malformed authority does not match either, so the whole input is escaped. */
    @Test
    public void aMalformedAuthorityDoesNotMatchTheRegex() {
        assertEquals("http%3A//", HtmlEncoder.url("http://"));
        assertEquals("https%3A///x", HtmlEncoder.url("https:///x"));
    }

    // ------------------------------------------------------------------
    // F6: what it does not stop
    // ------------------------------------------------------------------

    /**
     * F6. Every character of a hostname is on the allowlist, so an off-origin URL survives intact.
     * For {@code <script src="$x">} that is attacker-controlled JavaScript running with full page
     * privileges.
     */
    @Test
    public void offOriginUrlsPassThroughUnchanged() {
        assertEquals("//attacker.invalid/x.js", HtmlEncoder.url("//attacker.invalid/x.js"));
        assertEquals("https://attacker.invalid/x.js",
                HtmlEncoder.url("https://attacker.invalid/x.js"));
        assertEquals("http://attacker.invalid/x.js",
                HtmlEncoder.url("http://attacker.invalid/x.js"));

        assertTrue(VerdictEvaluator.analyseUrl("//attacker.invalid/x.js").isDangerous());
        assertTrue(VerdictEvaluator.analyseUrl("https://attacker.invalid/x.js").isDangerous());
    }

    /**
     * The scheme is emitted from {@code m.group(1)} without inspection, which is safe only because
     * the group can match nothing but the two literals {@code http://} and {@code https://}.
     */
    @Test
    public void theSchemeGroupCanOnlyEverBeHttpOrHttps() {
        assertEquals("http://host/", HtmlEncoder.url("http://host/"));
        assertEquals("https://host/", HtmlEncoder.url("https://host/"));
        assertEquals("ftp%3A//host/", HtmlEncoder.url("ftp://host/"));
        assertEquals("httpx%3A//host/", HtmlEncoder.url("httpx://host/"));
    }

    /**
     * The path group is optional, and a null third group must not throw. Two different shapes reach
     * that null: a bare host, and a host the allowlist mangles on the way past — the second matters
     * because the null-guard sits after the host has already been rewritten.
     */
    @Test
    public void aUrlWithNoPathIsHandled() {
        assertEquals("https://host", HtmlEncoder.url("https://host"));
        assertEquals("https://host%3A8443", HtmlEncoder.url("https://host:8443"));
    }

    /**
     * {@code ([^/]+)} is greedy and stops only at a {@code /}, so a query string or fragment with no
     * path is swallowed into the <em>host</em> group. The characters {@code ?}, {@code =} and
     * {@code #} are all on the allowlist, so the value survives unchanged and nobody notices.
     *
     * <p>Harmless as it stands, and recorded because it means the host group is not a host: any
     * future attempt to validate {@code m.group(2)} as a hostname — the natural fix for F6 — has to
     * split off the query and fragment first, or every no-path URL with a query will fail it.
     */
    @Test
    public void theHostGroupSwallowsAQueryStringWhenThereIsNoPath() {
        assertEquals("https://host?q=1", HtmlEncoder.url("https://host?q=1"));
        assertEquals("https://host#f", HtmlEncoder.url("https://host#f"));

        // With a path present the groups split where they should.
        assertEquals("https://host/p?q=1", HtmlEncoder.url("https://host/p?q=1"));
    }

    // ------------------------------------------------------------------
    // Escapes that neutralise a vector by accident
    // ------------------------------------------------------------------

    /**
     * A backslash becomes {@code %5C}, and no browser un-escapes that back into a path separator, so
     * the Windows-style protocol-relative form stays a same-origin path. Accidental, and pinned so it
     * is noticed if the allowlist ever gains a backslash.
     */
    @Test
    public void aBackslashCannotBecomeAPathSeparator() {
        assertEquals("/%5Cattacker.invalid/x.js", HtmlEncoder.url("/\\attacker.invalid/x.js"));
        assertEquals("%5C%5Cattacker.invalid/x.js", HtmlEncoder.url("\\\\attacker.invalid/x.js"));
        assertFalse(VerdictEvaluator.analyseUrl("/%5Cattacker.invalid/x.js").isDangerous());
    }

    /**
     * A {@code data:} URL loses its colon like every other unrecognised scheme, and its comma as
     * well, so {@code data:text/html,x} becomes the relative path {@code data%3Atext/html%2Cx}. The
     * markup-carrying variant is neutralised the same way.
     *
     * <p>Worth a named test rather than a row in the sweep above: {@code data:} is the one scheme in
     * that group that carries a whole document rather than a script fragment, so it is the one a
     * reader is most likely to want to check individually.
     */
    @Test
    public void aDataUrlLosesItsColonAndItsComma() {
        assertEquals("data%3Atext/html%2Cx", HtmlEncoder.url("data:text/html,x"));
        assertEquals("data%3Atext/html%3Bbase64%2CAAA",
                HtmlEncoder.url("data:text/html;base64,AAA"));
        assertFalse(VerdictEvaluator.analyseUrl("data%3Atext/html%2Cx").isDangerous(),
                "with the colon escaped this is a relative path, not a data: URL");

        // Unescaped it really would carry an attacker-controlled document.
        assertTrue(VerdictEvaluator.analyseUrl("data:text/html,x").isDangerous());
    }

    /**
     * {@code https:/\} does not match the regex — the regex demands two forward slashes — so the
     * whole input is escaped and the backslash becomes {@code %5C}. That matters because
     * {@code https:/\attacker.invalid/x} <em>is</em> a URL reaching {@code attacker.invalid} in every
     * browser: WHATWG treats {@code \} as a path separator for special schemes, so the two-separator
     * run introduces an authority. Escaping the backslash is what stops it, and it stops it by
     * accident.
     */
    @Test
    public void theBackslashAuthorityFormIsEscapedRatherThanRecognised() {
        assertEquals("https%3A/%5C", HtmlEncoder.url("https:/\\"));
        assertEquals("https%3A/%5Cattacker.invalid/x",
                HtmlEncoder.url("https:/\\attacker.invalid/x"));

        assertFalse(VerdictEvaluator.analyseUrl("https%3A/%5Cattacker.invalid/x").isDangerous(),
                "no colon and no separator run, so this is a relative path");
        assertTrue(VerdictEvaluator.analyseUrl("https:/\\attacker.invalid/x").isDangerous(),
                "unescaped, a browser resolves this to host attacker.invalid");
    }

    /**
     * Userinfo is escaped to {@code %40}, which puts a forbidden code point <em>inside the host</em>.
     * The URL then fails to parse rather than reaching the attacker's host — so the classic
     * "trusted-looking prefix" trick does not work here.
     */
    @Test
    public void userinfoBecomesAForbiddenHostCharacter() {
        assertEquals("https://trusted.example%40attacker.invalid/x.js",
                HtmlEncoder.url("https://trusted.example@attacker.invalid/x.js"));
        assertFalse(VerdictEvaluator.analyseUrl(
                        "https://trusted.example%40attacker.invalid/x.js").isDangerous(),
                "a percent-encoded '@' in the host makes the URL unparseable");

        // Unescaped, the same input really would reach the attacker host.
        assertTrue(VerdictEvaluator.analyseUrl(
                "https://trusted.example@attacker.invalid/x.js").isDangerous());
    }

    // ------------------------------------------------------------------
    // F15: legitimate URLs that url() corrupts
    // ------------------------------------------------------------------

    /**
     * F15a. The host group is escaped with the same allowlist as everything else, so a colon before
     * a port number becomes {@code %3A} — a forbidden host code point. Any URL with an explicit port
     * is destroyed.
     */
    @Test
    public void anExplicitPortIsDestroyed() {
        assertEquals("https://host%3A8443/path", HtmlEncoder.url("https://host:8443/path"));
        assertFalse(VerdictEvaluator.analyseUrl("https://host%3A8443/path").isDangerous(),
                "F15a: fails closed, but the link is broken rather than safe");
    }

    /**
     * F15b. {@code &} is not on the allowlist, so it becomes {@code %26} — a literal ampersand
     * inside the first parameter's value rather than a separator. Every query string with more than
     * one parameter is silently corrupted.
     */
    @Test
    public void aQueryStringWithTwoParametersIsCorrupted() {
        assertEquals("/search?q=hello%26lang=en", HtmlEncoder.url("/search?q=hello&lang=en"));
        // '?' and '=' survive, so a single parameter is fine; only the separator is lost.
        assertEquals("/search?q=hello", HtmlEncoder.url("/search?q=hello"));
    }

    /**
     * F15c. Percent is not on the allowlist, so input that is already percent-encoded is encoded
     * again. A caller who correctly encoded a URL before handing it to a template gets it corrupted.
     */
    @Test
    public void alreadyEncodedInputIsDoubleEncoded() {
        assertEquals("a%2520b", HtmlEncoder.url("a%20b"));
        assertEquals("/a%252Fb", HtmlEncoder.url("/a%2Fb"));
    }

    /**
     * F15d. The worst of the five, because it changes the URL's <em>structure</em> rather than
     * merely its text: any code point above 255 is replaced with a literal {@code ?}, and {@code ?}
     * is on the allowlist. A path containing a non-Latin-1 character therefore becomes a query
     * string.
     *
     * <p>{@code /search/CJK/results} does not become a mangled path — it becomes a request for
     * {@code /search/} with a query. Correct handling would be to UTF-8 encode the code point and
     * percent-escape each byte.
     */
    @Test
    public void aNonLatin1CharacterInAPathBecomesAQuerySeparator() {
        String cjk = new String(Character.toChars(0x4E2D));

        assertEquals("?", HtmlEncoder.url(cjk));
        assertEquals("/search/?/results", HtmlEncoder.url("/search/" + cjk + "/results"),
                "F15d: the path has become a query string");

        // Latin-1 code points are percent-escaped correctly, single byte at a time.
        assertEquals("%E9", HtmlEncoder.url(new String(Character.toChars(0xE9))));

        // Astral code points collapse the same way.
        assertEquals("?", HtmlEncoder.url(new String(Character.toChars(0x1F600))));
    }

    /**
     * F15e. The host group is escaped with the path's allowlist, so the brackets and colons of an
     * IPv6 literal all become percent-escapes — and an escaped bracket is not a bracket. WHATWG only
     * reads a host as an IPv6 address when the first code point is a literal {@code [}, so
     * {@code https://%5B%3A%3A1%5D/x} is not "IPv6 written oddly", it is a host containing forbidden
     * code points, and the URL fails to parse.
     *
     * <p>Every IPv6 URL is destroyed, not merely the awkward ones. Fails closed like the rest of
     * F15, and like the rest of F15 the developer's remedy is {@code $_x.asis()}.
     */
    @Test
    public void everyIpv6LiteralIsDestroyed() {
        assertEquals("https://%5B%3A%3A1%5D/x", HtmlEncoder.url("https://[::1]/x"));
        assertEquals("https://%5B%3A%3A1%5D%3A8443/x", HtmlEncoder.url("https://[::1]:8443/x"));

        assertFalse(VerdictEvaluator.analyseUrl("https://%5B%3A%3A1%5D/x").isDangerous(),
                "F15e: the escaped brackets are forbidden host code points, so nothing is fetched");

        // Unescaped the same input parses, which is what makes this corruption rather than defence.
        assertTrue(VerdictEvaluator.analyseUrl("https://[::1]/x").isDangerous(),
                "a real IPv6 literal parses fine and is off-origin");
    }

    /**
     * F6, in its homograph form. A punycode host is nothing but ASCII letters, digits, hyphens and
     * dots, so it is entirely inside the allowlist and passes through untouched.
     *
     * <p>{@code xn--80ak6aa92e.com} is the Cyrillic spelling of {@code apple.com}. Since {@code url()}
     * looks at schemes rather than origins it has no opinion about where the host points, so a
     * template that interpolates a hostname will emit a homograph of the site's own domain exactly as
     * readily as it emits the domain. This is the same gap as the protocol-relative case above, seen
     * from the phishing side rather than the script-inclusion side.
     */
    @Test
    public void aPunycodeHomographHostPassesThroughUnchanged() {
        assertEquals("https://xn--80ak6aa92e.com/x", HtmlEncoder.url("https://xn--80ak6aa92e.com/x"));
        assertEquals("//xn--80ak6aa92e.com/x.js", HtmlEncoder.url("//xn--80ak6aa92e.com/x.js"));

        assertTrue(VerdictEvaluator.analyseUrl("https://xn--80ak6aa92e.com/x").isDangerous(),
                "F6: url() emits an off-origin host verbatim, homograph or not");
    }

    // ------------------------------------------------------------------
    // The allowlist itself
    // ------------------------------------------------------------------

    /**
     * The complete allowlist, asserted by exhaustion over ASCII. Anything that starts passing
     * through unescaped is a security decision.
     */
    @Test
    public void theAllowlistIsExactlyAlphanumericsAndSixPunctuationMarks() {
        String allowedPunctuation = "/.-#?=";

        for (int i = 0; i < 128; i++) {
            final int c = i;
            String input = String.valueOf((char) c);
            final String encoded = HtmlEncoder.url(input);
            boolean passedThrough = encoded.equals(input);

            boolean shouldPass = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || allowedPunctuation.indexOf(c) >= 0;

            assertEquals(shouldPass, passedThrough,
                    () -> "url(U+" + Integer.toHexString(c) + ") = "
                            + CanoeTestSupport.quote(encoded));
        }
    }

    /**
     * RFC 3986 calls {@code _} and {@code ~} unreserved, and they are not on this allowlist. Harmless
     * — {@code %5F} decodes back to {@code _} — but noted so nobody mistakes their absence for a
     * deliberate restriction.
     */
    @Test
    public void unreservedCharactersAreEscapedUnnecessarilyButHarmlessly() {
        assertEquals("a%5Fb", HtmlEncoder.url("a_b"));
        assertEquals("a%7Eb", HtmlEncoder.url("a~b"));
    }

    @Test
    public void nullAndEmptyAreHandled() {
        assertEquals(null, HtmlEncoder.url(null));
        assertEquals("", HtmlEncoder.url(""));
    }

    /**
     * The escape is always two uppercase hex digits, so there is no truncated-escape parsing to
     * exploit.
     */
    @Test
    public void everyEscapeIsExactlyTwoUppercaseHexDigits() {
        for (int i = 0; i <= 255; i++) {
            final int c = i;
            final String encoded = HtmlEncoder.url(String.valueOf((char) c));
            if (encoded.startsWith("%")) {
                assertEquals(3, encoded.length(),
                        () -> "malformed escape for U+" + Integer.toHexString(c) + ": " + encoded);
                assertTrue(encoded.substring(1).matches("[0-9A-F]{2}"),
                        () -> "escape is not uppercase hex for U+" + Integer.toHexString(c) + ": "
                                + encoded);
            }
        }
    }
}
