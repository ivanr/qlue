package com.webkreator.qlue.view.canoe;

import com.webkreator.qlue.util.HtmlEncoder;
import com.webkreator.qlue.view.canoe.corpus.VerdictEvaluator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code HtmlEncoder.url()}, which gets its own file because it is the only encoder with structure.
 *
 * <p><strong>R11 and R12 rewrote it.</strong> It used to match {@code ^(https?://)([^/]+)(/.*)?$},
 * emit the matched scheme <strong>verbatim</strong> — the one path in the component that produced a
 * raw colon, which was F24 — and percent-encode everything else a single Java {@code char} at a time
 * against the allowlist {@code a-zA-Z0-9 / . - # ? =}. That design corrupted five ordinary inputs
 * (F15) and steered Canoe's parser (F24).
 *
 * <p>The rewrite parses the value into scheme / authority / path / query / fragment and encodes each
 * component by its own rules, percent-escaping per UTF-8 <em>byte</em> and passing an existing
 * {@code %XX} through untouched. The scheme is emitted from a {http, https, mailto} allowlist rather
 * than copied out of the input, so:
 *
 * <ul>
 *   <li>the five F15 corruptions are gone — a port, an IPv6 literal, a multi-parameter query, a
 *       pre-encoded value and a non-Latin-1 path character all survive;
 *   <li>a scheme that is not on the allowlist — {@code javascript:}, {@code data:},
 *       {@code vbscript:}, {@code view-source:}, and anything unregistered — is rejected to the empty
 *       string, so it is neutralised by suppression rather than by escaping one delimiter;
 *   <li>the only raw colon {@code url()} can now emit sits immediately behind an allowlisted scheme
 *       name or inside such a URL's authority, which is what keeps F24 closed by design.
 * </ul>
 *
 * <p>It remains a <em>scheme</em> filter and not an <em>origin</em> filter (F6): a protocol-relative
 * or absolute off-origin {@code http(s)} URL still passes through, because that is a valid URL and
 * origin filtering is R9's job. What R12 changed there is only correctness — an uppercase scheme is
 * normalised and passes now, where the old case-sensitive regex neutralised it by accident.
 *
 * <p>One thing the rewrite does that a pure URL encoder would not: it emits {@code &} as
 * {@code &amp;}. {@code url()} is the terminal encoder, written straight into an HTML attribute with
 * no second pass, so a raw {@code &} would let an input like {@code &#106;avascript:} be reconstituted
 * into a scheme by the HTML parser. {@code &amp;} decodes back to a single {@code &} for the URL
 * parser — keeping it a query separator — while carrying no entity of its own.
 */
public class HtmlEncoderUrlTest {

    // ------------------------------------------------------------------
    // Scheme allowlist: what url() rejects, and what it keeps
    // ------------------------------------------------------------------

    /**
     * Every scheme off the {http, https, mailto} allowlist is rejected to the empty string. This is
     * the strict form of what the old encoder did by accident: it escaped the colon and left a
     * relative path, which was safe but noisy; the rewrite emits nothing at all.
     */
    @ParameterizedTest(name = "{0} is rejected")
    @ValueSource(strings = {
            "javascript:alert(1)",
            "vbscript:msgbox(1)",
            "livescript:alert(1)",
            "mocha:alert(1)",
            "view-source:http://x/",
            "blob:http://x/y",
            "data:text/html,x",
            "ftp://host/",
            "httpx://host/",
    })
    public void rejectsEveryUnrecognisedScheme(String input) {
        assertEquals("", HtmlEncoder.url(input),
                "a scheme not on {http, https, mailto} is suppressed, not escaped");
        assertFalse(VerdictEvaluator.analyseUrl(HtmlEncoder.url(input)).isDangerous(),
                "the empty string reaches no origin and runs no script");
    }

    /**
     * A {@code data:} URL is rejected like every other off-allowlist scheme, markup payload and all.
     * Kept as its own test rather than a row above because {@code data:} is the one rejected scheme
     * that carries a whole document, so it is the one a reader is most likely to check individually.
     */
    @Test
    public void aDataUrlIsRejected() {
        assertEquals("", HtmlEncoder.url("data:text/html,x"));
        assertEquals("", HtmlEncoder.url("data:text/html;base64,AAA"));

        // Unescaped it really would carry an attacker-controlled document.
        assertTrue(VerdictEvaluator.analyseUrl("data:text/html,x").isDangerous());
    }

    /**
     * An uppercase scheme is now <em>normalised</em> and passes through, where the old case-sensitive
     * regex escaped its colon and neutralised it by accident. Schemes are case-insensitive in the URL
     * Standard, so {@code HTTPS://attacker.invalid/x} genuinely reaches the attacker and is correctly
     * reported dangerous — F6, not a defence.
     */
    @Test
    public void anUppercaseSchemeIsNormalisedAndPassesThrough() {
        assertEquals("http://host/path", HtmlEncoder.url("HTTP://host/path"));
        assertEquals("https://host/path", HtmlEncoder.url("HTTPS://host/path"));
        assertTrue(VerdictEvaluator.analyseUrl(HtmlEncoder.url("HTTPS://attacker.invalid/x")).isDangerous(),
                "R12 normalises the scheme, so the off-origin host is reached - this is F6");
    }

    /** Only http, https and mailto survive as schemes; everything else is rejected. */
    @Test
    public void onlyTheAllowlistedSchemesSurvive() {
        assertEquals("http://host/", HtmlEncoder.url("http://host/"));
        assertEquals("https://host/", HtmlEncoder.url("https://host/"));
        assertEquals("mailto:a@b.com", HtmlEncoder.url("mailto:a@b.com"));
        assertEquals("", HtmlEncoder.url("ftp://host/"));
        assertEquals("", HtmlEncoder.url("httpx://host/"));
    }

    /** An http(s) URL with no path is handled, and the port is no longer destroyed. */
    @Test
    public void aUrlWithNoPathIsHandled() {
        assertEquals("https://host", HtmlEncoder.url("https://host"));
        assertEquals("https://host:8443", HtmlEncoder.url("https://host:8443"));
    }

    /**
     * A scheme with a malformed or empty authority still parses to a hierarchical URL rather than
     * throwing; the components that are there are encoded and the ones that are not are omitted.
     */
    @Test
    public void aMalformedAuthorityIsStillHandled() {
        assertEquals("http://", HtmlEncoder.url("http://"));
        assertEquals("https:///x", HtmlEncoder.url("https:///x"));
    }

    // ------------------------------------------------------------------
    // F6: what it still does not stop
    // ------------------------------------------------------------------

    /**
     * F6. Every character of a hostname is legal in an authority, so an off-origin URL survives
     * intact. For {@code <script src="$x">} that is attacker-controlled JavaScript running with full
     * page privileges. R12 makes the encoding correct; it does not add origin filtering, which is R9.
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
     * A query string or fragment with no path is split off from the host rather than swallowed into
     * it: the authority runs only to the first {@code / ? #}.
     */
    @Test
    public void theAuthorityStopsAtTheFirstQueryOrFragment() {
        assertEquals("https://host?q=1", HtmlEncoder.url("https://host?q=1"));
        assertEquals("https://host#f", HtmlEncoder.url("https://host#f"));
        assertEquals("https://host/p?q=1", HtmlEncoder.url("https://host/p?q=1"));
    }

    /**
     * F6, in its homograph form. A punycode host is nothing but ASCII letters, digits, hyphens and
     * dots, so it passes through untouched. {@code xn--80ak6aa92e.com} is the Cyrillic spelling of
     * {@code apple.com}; {@code url()} looks at schemes and structure rather than at where a host
     * points, so a template that interpolates a hostname emits a homograph as readily as the domain.
     */
    @Test
    public void aPunycodeHomographHostPassesThroughUnchanged() {
        assertEquals("https://xn--80ak6aa92e.com/x", HtmlEncoder.url("https://xn--80ak6aa92e.com/x"));
        assertEquals("//xn--80ak6aa92e.com/x.js", HtmlEncoder.url("//xn--80ak6aa92e.com/x.js"));

        assertTrue(VerdictEvaluator.analyseUrl("https://xn--80ak6aa92e.com/x").isDangerous(),
                "F6: url() emits an off-origin host verbatim, homograph or not");
    }

    // ------------------------------------------------------------------
    // Neutralisations that now happen by design rather than by accident
    // ------------------------------------------------------------------

    /**
     * A backslash is percent-encoded to {@code %5C} — deliberately, because it is neither unreserved
     * nor a component delimiter — so the Windows-style protocol-relative form stays a same-origin
     * path. The old encoder produced the same bytes by the same reasoning; the difference is that this
     * is now a rule about the safe set rather than a happy accident of an allowlist.
     */
    @Test
    public void aBackslashIsPercentEncodedByDesign() {
        assertEquals("/%5Cattacker.invalid/x.js", HtmlEncoder.url("/\\attacker.invalid/x.js"));
        assertEquals("%5C%5Cattacker.invalid/x.js", HtmlEncoder.url("\\\\attacker.invalid/x.js"));
        assertFalse(VerdictEvaluator.analyseUrl("/%5Cattacker.invalid/x.js").isDangerous());
    }

    /**
     * {@code https:/\attacker.invalid/x} <em>is</em> a URL reaching {@code attacker.invalid} in a real
     * browser, because WHATWG treats {@code \} as a path separator for special schemes and the two
     * separators introduce an authority. url() keeps it same-origin by percent-encoding the backslash:
     * the scheme is kept (https is allowlisted), but the {@code \} becomes {@code %5C}, so no
     * separator run forms and the value is a relative path.
     */
    @Test
    public void theBackslashAuthorityFormIsNeutralisedByEncodingTheBackslash() {
        assertEquals("https:/%5C", HtmlEncoder.url("https:/\\"));
        assertEquals("https:/%5Cattacker.invalid/x",
                HtmlEncoder.url("https:/\\attacker.invalid/x"));

        assertFalse(VerdictEvaluator.analyseUrl("https:/%5Cattacker.invalid/x").isDangerous(),
                "no separator run, so this is a relative path on the page's own origin");
        assertTrue(VerdictEvaluator.analyseUrl("https:/\\attacker.invalid/x").isDangerous(),
                "unescaped, a browser resolves this to host attacker.invalid");
    }

    /**
     * Userinfo is escaped to {@code %40}, which puts a forbidden code point inside the host, so the
     * URL fails to parse rather than reaching the attacker's host. This is deliberate: the authority
     * safe set does not include {@code @}, so the classic trusted-looking-prefix trick is neutralised
     * by design.
     */
    @Test
    public void userinfoBecomesAForbiddenHostCharacter() {
        assertEquals("https://trusted.example%40attacker.invalid/x.js",
                HtmlEncoder.url("https://trusted.example@attacker.invalid/x.js"));
        assertFalse(VerdictEvaluator.analyseUrl(
                        "https://trusted.example%40attacker.invalid/x.js").isDangerous(),
                "a percent-encoded '@' in the host makes the URL unparseable");

        assertTrue(VerdictEvaluator.analyseUrl(
                "https://trusted.example@attacker.invalid/x.js").isDangerous());
    }

    // ------------------------------------------------------------------
    // F15, inverted: the five legitimate URLs url() used to corrupt
    // ------------------------------------------------------------------

    /**
     * F15a, fixed. The authority keeps its colon, so a port survives. Any URL with an explicit port
     * used to be destroyed ({@code https://host%3A8443/path}); it round-trips now.
     */
    @Test
    public void anExplicitPortSurvives() {
        assertEquals("https://host:8443/path", HtmlEncoder.url("https://host:8443/path"));
        assertTrue(VerdictEvaluator.analyseUrl("https://attacker.invalid:8443/path").isDangerous(),
                "and the port is real, so a different-port off-origin URL is still off-origin");
    }

    /**
     * F15b, fixed. {@code &} is the query separator and survives — emitted as {@code &amp;}, which the
     * HTML parser decodes back to a single {@code &} for the URL parser, so a multi-parameter query is
     * no longer collapsed into one parameter's value.
     */
    @Test
    public void aQueryStringWithTwoParametersSurvives() {
        assertEquals("/search?q=hello&amp;lang=en", HtmlEncoder.url("/search?q=hello&lang=en"));
        // A single parameter is unchanged.
        assertEquals("/search?q=hello", HtmlEncoder.url("/search?q=hello"));
    }

    /**
     * F15c, fixed. An existing {@code %XX} escape is passed through rather than re-escaped, so input
     * that was correctly percent-encoded before it reached the template is not double-encoded.
     */
    @Test
    public void alreadyEncodedInputIsNotDoubleEncoded() {
        assertEquals("a%20b", HtmlEncoder.url("a%20b"));
        assertEquals("/a%2Fb", HtmlEncoder.url("/a%2Fb"));
    }

    /**
     * F15d, fixed, and it was the worst of the five because it changed the URL's <em>structure</em>:
     * a code point above U+00FF used to become a literal {@code ?}, turning a path into a query
     * string. Now every non-ASCII code point is UTF-8 encoded and each byte percent-escaped, so the
     * path stays a path.
     */
    @Test
    public void aNonLatin1CharacterInAPathIsUtf8PercentEncoded() {
        String cjk = new String(Character.toChars(0x4E2D));

        assertEquals("%E4%B8%AD", HtmlEncoder.url(cjk));
        assertEquals("/search/%E4%B8%AD/results", HtmlEncoder.url("/search/" + cjk + "/results"),
                "F15d: the path stays a path");

        // Latin-1 code points are UTF-8 encoded too, so U+00E9 is its two UTF-8 bytes, not one.
        assertEquals("%C3%A9", HtmlEncoder.url(new String(Character.toChars(0xE9))));

        // Astral code points emit their four UTF-8 bytes.
        assertEquals("%F0%9F%98%80", HtmlEncoder.url(new String(Character.toChars(0x1F600))));
    }

    /**
     * F15e, fixed. The authority keeps the brackets and colons of an IPv6 literal, so every IPv6 URL
     * survives rather than being turned into a host full of forbidden code points.
     */
    @Test
    public void everyIpv6LiteralSurvives() {
        assertEquals("https://[::1]/x", HtmlEncoder.url("https://[::1]/x"));
        assertEquals("https://[::1]:8443/x", HtmlEncoder.url("https://[::1]:8443/x"));

        assertTrue(VerdictEvaluator.analyseUrl("https://[::1]/x").isDangerous(),
                "a real IPv6 literal parses fine and is off-origin");
    }

    // ------------------------------------------------------------------
    // The safe sets, swept
    // ------------------------------------------------------------------

    /**
     * The passthrough set for a relative reference, asserted by exhaustion over ASCII. A value with no
     * scheme is a path (up to the first {@code ?} or {@code #}), so the set is the unreserved
     * characters plus the sub-delimiters and path punctuation that are structural there — and,
     * pointedly, <em>not</em> {@code :} (which would look like a scheme), {@code @} (which could
     * introduce userinfo when concatenated after a base), {@code &} (emitted as {@code &amp;}), the
     * quote {@code '} or any markup delimiter.
     */
    @Test
    public void theRelativePathPassthroughSetIsExactlyUnreservedPlusPathPunctuation() {
        // unreserved: A-Za-z0-9 - . _ ~   ;  path safe extras: ! $ ( ) * + , ; = / ? #
        String extraSafe = "-._~!$()*+,;=/?#";

        for (int c = 0; c < 128; c++) {
            final int cp = c;
            String input = String.valueOf((char) c);
            final String encoded = HtmlEncoder.url(input);
            boolean passedThrough = encoded.equals(input);

            boolean shouldPass = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || extraSafe.indexOf(c) >= 0;

            assertEquals(shouldPass, passedThrough,
                    () -> "url(U+" + Integer.toHexString(cp) + ") = "
                            + CanoeTestSupport.quote(encoded));
        }
    }

    /**
     * A colon in a bare relative reference is escaped, so the value cannot be read as a scheme once it
     * reaches the browser. Inside an authority a colon is kept (it is a port or an IPv6 literal), which
     * is what {@link #anExplicitPortSurvives} pins.
     */
    @Test
    public void aColonInARelativePathIsEscaped() {
        // A colon after the first path segment is escaped so the value cannot look scheme-like.
        assertEquals("foo/a%3Ab", HtmlEncoder.url("foo/a:b"));
        assertEquals("%3A", HtmlEncoder.url(":"));
        // A letter-then-colon at the head is a scheme, and an unrecognised one is rejected.
        assertEquals("", HtmlEncoder.url("a:b"));
    }

    /**
     * {@code _} and {@code ~} are RFC 3986 unreserved characters and now pass through, where the old
     * allowlist escaped them unnecessarily.
     */
    @Test
    public void unreservedCharactersPassThrough() {
        assertEquals("a_b", HtmlEncoder.url("a_b"));
        assertEquals("a~b", HtmlEncoder.url("a~b"));
    }

    /**
     * The {@code %XX} passthrough triggers only on a complete escape. A lone {@code %}, a {@code %}
     * with a non-hex digit after it, or a {@code %} at the very end is itself escaped to {@code %25},
     * so a stray percent cannot masquerade as the start of an escape. Lowercase hex is recognised as
     * an existing escape just as uppercase is.
     */
    @Test
    public void anIncompletePercentEscapeIsItselfEncoded() {
        assertEquals("a%25", HtmlEncoder.url("a%"));
        assertEquals("a%25zz", HtmlEncoder.url("a%zz"));
        assertEquals("a%25b", HtmlEncoder.url("a%b"));
        // First hex digit valid, second not: still not a complete escape.
        assertEquals("a%253z", HtmlEncoder.url("a%3z"));
        // A character below '0' right after the '%' is not hex either, so the '%' is encoded and the
        // path delimiter that follows keeps its meaning.
        assertEquals("x%25/y", HtmlEncoder.url("x%/y"));
        // A complete escape passes through in either hex case, digit or letter.
        assertEquals("a%3fb", HtmlEncoder.url("a%3fb"));
        assertEquals("a%3Fb", HtmlEncoder.url("a%3Fb"));
        assertEquals("a%9cb", HtmlEncoder.url("a%9cb"));
    }

    /**
     * The whole hierarchy in one value — authority, path, query and fragment — so the component
     * splitter is exercised end to end: each delimiter keeps its structural characters and the query
     * and fragment are separated from each other.
     */
    @Test
    public void aFullHierarchyIsSplitIntoItsComponents() {
        assertEquals("https://host:8443/a/b?x=1&amp;y=2#frag",
                HtmlEncoder.url("https://host:8443/a/b?x=1&y=2#frag"));
        // A protocol-relative URL with a query but no path or fragment.
        assertEquals("//host?x=1&amp;y=2", HtmlEncoder.url("//host?x=1&y=2"));
        // A bare fragment with a query character in it stays in the fragment.
        assertEquals("/p#a?b", HtmlEncoder.url("/p#a?b"));
    }

    @Test
    public void nullAndEmptyAreHandled() {
        assertEquals(null, HtmlEncoder.url(null));
        assertEquals("", HtmlEncoder.url(""));
    }

    /**
     * Every percent-escape is two uppercase hex digits per UTF-8 byte, so there is no truncated-escape
     * parsing to exploit. A single code point may emit several escapes now (its UTF-8 bytes), but each
     * one is a well-formed {@code %XX}.
     */
    @Test
    public void everyEscapeIsUppercaseHexBytes() {
        for (int c = 0; c <= 0x2FFF; c++) {
            if (Character.isSurrogate((char) c)) {
                continue;
            }
            final int cp = c;
            final String encoded = HtmlEncoder.url(new String(Character.toChars(c)));
            for (int i = 0; i < encoded.length(); i++) {
                if (encoded.charAt(i) == '%') {
                    assertTrue(i + 2 < encoded.length()
                                    && isUpperHex(encoded.charAt(i + 1))
                                    && isUpperHex(encoded.charAt(i + 2)),
                            () -> "malformed escape for U+" + Integer.toHexString(cp) + ": " + encoded);
                }
            }
        }
    }

    private static boolean isUpperHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F');
    }

    // ------------------------------------------------------------------
    // Scheme confusion: the spellings that are not a scheme to url() and must not become one again
    // ------------------------------------------------------------------

    /**
     * The scheme-confusion catalogue, asserted byte for byte. None of these carries a scheme
     * {@code url()}'s grammar recognises, so each is encoded as a relative reference — and the point
     * of pinning the exact bytes is that none of them can turn back into a scheme in the browser
     * either. A split control character is {@code %09}/{@code %0A}, not a literal tab or newline that
     * WHATWG would strip out of the URL before parsing it; a leading space or control is
     * {@code %20}/{@code %01}, not something the C0-and-space strip removes; a homoglyph colon is its
     * UTF-8 bytes; and the colon that follows every one of them is {@code %3A}. An uppercase or mixed
     * spelling <em>is</em> recognised, case-insensitively, and rejected.
     */
    @Test
    public void aSchemeSplitByAControlCharacterIsNotAScheme() {
        // A control character inside the scheme name: the value is a relative path, colon included.
        assertEquals("java%09script%3Aalert(1)", HtmlEncoder.url("java\tscript:alert(1)"));
        assertEquals("java%0Ascript%3Aalert(1)", HtmlEncoder.url("java\nscript:alert(1)"));
        assertEquals("javascript%0D%3Aalert(1)", HtmlEncoder.url("javascript\r:alert(1)"));

        // A leading space or control. WHATWG strips these before parsing, so they must not be emitted
        // raw in front of a scheme that would then be live.
        assertEquals("%20javascript%3Aalert(1)", HtmlEncoder.url(" javascript:alert(1)"));
        assertEquals("%01javascript%3Aalert(1)", HtmlEncoder.url("\u0001javascript:alert(1)"));
        assertEquals("%00javascript%3Aalert(1)", HtmlEncoder.url("\u0000javascript:alert(1)"));
        assertEquals("%0Ajavascript%3Aalert(1)", HtmlEncoder.url("\njavascript:alert(1)"));

        // A homoglyph colon is not a colon: it is UTF-8 percent-encoded rather than dropped or
        // replaced by an ASCII one.
        assertEquals("javascript%EF%BC%9Aalert(1)",
                HtmlEncoder.url("javascript\uFF1Aalert(1)"));
        assertEquals("javascript%EA%9E%89alert(1)",
                HtmlEncoder.url("javascript\uA789alert(1)"));

        // A percent-encoded colon stays percent-encoded rather than being decoded into one.
        assertEquals("javascript%3Aalert(1)", HtmlEncoder.url("javascript%3Aalert(1)"));

        // ...and the spellings that ARE a scheme, case-insensitively, are rejected outright.
        assertEquals("", HtmlEncoder.url("JAVASCRIPT:alert(1)"));
        assertEquals("", HtmlEncoder.url("JaVaScRiPt:alert(1)"));
        assertEquals("", HtmlEncoder.url("javascript:"));
    }

    /**
     * The reconstitution shapes, which are the reason {@code url()} emits {@code &} as {@code &amp;}.
     * The HTML parser decodes an attribute value once, before the URL parser sees it, so an input
     * carrying {@code &#106;} would come back as a {@code j} — and {@code &#106;avascript:} as a live
     * {@code javascript:} URL — if the ampersand were emitted raw. Escaping it means the entity
     * arrives as the six literal characters {@code &#106;}, which no scheme grammar accepts.
     *
     * <p>The {@code #} in these inputs also starts a fragment as far as the component splitter is
     * concerned, which is why the tail is not percent-escaped: it is fragment text. That is harmless
     * for exactly the same reason — what precedes the colon is no longer a scheme.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "&#106;avascript:alert(1)",
            "&#x6A;avascript:alert(1)",
            "jav&#x09;ascript:alert(1)",
            "&#0000106;avascript:alert(1)",
    })
    public void anEntityEncodedSchemeCannotBeReconstituted(String input) {
        String encoded = HtmlEncoder.url(input);
        assertFalse(encoded.contains("&#"),
                () -> "every '&' must be escaped, or the HTML parser rebuilds the scheme: " + encoded);
        assertTrue(encoded.contains("&amp;"),
                () -> "the ampersand is the character that matters here: " + encoded);
        // Decoding the attribute value the way the HTML parser does gives back a value whose first
        // colon is not preceded by anything a URL parser will accept as a scheme.
        String decoded = encoded.replace("&amp;", "&");
        int colon = decoded.indexOf(':');
        assertFalse(colon > 0 && decoded.substring(0, colon).matches("[a-zA-Z][a-zA-Z0-9+.\\-]*"),
                () -> "after HTML decoding this must not carry a scheme: " + decoded);
    }

    /**
     * No {@code url()} output carries a character that could end the attribute it is written into, or
     * a bare {@code &} that could start a character reference — asserted over every code point below
     * U+3000 in every component position rather than over the handful anybody thought to try.
     *
     * <p>This is the property Canoe's state machine rests on and the one a widened safe set would
     * break silently: {@code AUTHORITY_SAFE}, {@code PATH_SAFE}, {@code RELATIVE_PATH_SAFE} and
     * {@code QUERY_SAFE} are four separate lists, and a character added to any of them is a character
     * that reaches the attribute unescaped.
     */
    @Test
    public void noComponentEmitsAQuoteAMarkupDelimiterOrABareAmpersand() {
        String forbidden = "\"'<>` \t\n\r";

        for (int cp = 0; cp <= 0x3000; cp++) {
            if (Character.isSurrogate((char) cp)) {
                continue;
            }
            String ch = new String(Character.toChars(cp));
            for (String position : new String[]{
                    ch,                        // the whole value
                    "/p/" + ch,                // a relative path
                    "//h" + ch + "/p",         // an authority
                    "?q=" + ch,                // a query
                    "#f" + ch,                 // a fragment
                    "https://h" + ch + "/p",   // an absolute URL's authority
                    "https://h/" + ch,         // ...its path
                    "https://h?q=" + ch,       // ...its query
                    "https://h#" + ch,         // ...its fragment
                    "mailto:a@b" + ch,         // an opaque mailto body
            }) {
                final String encoded = HtmlEncoder.url(position);
                for (int i = 0; i < encoded.length(); i++) {
                    final char c = encoded.charAt(i);
                    assertFalse(forbidden.indexOf(c) >= 0,
                            () -> "url(" + CanoeTestSupport.quote(position) + ") emitted "
                                    + CanoeTestSupport.quote(String.valueOf(c)) + ": " + encoded);
                }
                for (int i = encoded.indexOf('&'); i >= 0; i = encoded.indexOf('&', i + 1)) {
                    assertTrue(encoded.startsWith("&amp;", i),
                            () -> "url(" + CanoeTestSupport.quote(position) + ") emitted a bare '&',"
                                    + " which the HTML parser may read as a character reference: "
                                    + encoded);
                }
            }
        }
    }

    /**
     * The cost of keeping {@code &} structural, recorded rather than discovered. {@code url()} cannot
     * see the template text around the reference, so it cannot tell a whole URL from one query
     * parameter's value: in {@code <a href="/search?q=$q">} an ampersand in {@code $q} decodes back to
     * a parameter separator and the value adds a parameter to the author's query.
     *
     * <p>This is the deliberate other side of F15b — the old encoder escaped {@code &} to {@code %26}
     * and collapsed every multi-parameter query into one parameter — and it is bounded: the origin and
     * the path are still the template's, {@code url()} adds no authority, and nothing here weakens the
     * scheme allowlist. It is pinned so that changing it is a decision rather than a side effect.
     */
    @Test
    public void anAmpersandStaysAParameterSeparatorWhichIsAlsoItsCost() {
        // The benefit: an interpolated whole URL keeps its parameters.
        assertEquals("/search?q=hello&amp;lang=en", HtmlEncoder.url("/search?q=hello&lang=en"));

        // The cost: interpolated into a query, a value can add a parameter of its own. '?', '#', '/'
        // and '=' behaved this way before R12 as well; '&' joined them.
        assertEquals("1&amp;admin=true", HtmlEncoder.url("1&admin=true"));
        assertEquals("1#x", HtmlEncoder.url("1#x"));
        assertEquals("1?x=2", HtmlEncoder.url("1?x=2"));

        // What the added parameter still cannot do is carry a scheme or an authority of its own: a
        // rejected scheme suppresses the whole value, and there is no way to end the attribute.
        assertEquals("1&amp;x=javascript%3Aalert(1)", HtmlEncoder.url("1&x=javascript:alert(1)"),
                "a colon past the head of the value is escaped, so no injected parameter is a scheme");
        assertEquals("", HtmlEncoder.url("javascript:alert(1)&x=1"),
                "and a value that does lead with a scheme is rejected whole, ampersand and all");
    }
}
