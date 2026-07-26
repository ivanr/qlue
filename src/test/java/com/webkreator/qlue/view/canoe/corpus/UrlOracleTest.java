package com.webkreator.qlue.view.canoe.corpus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VerdictEvaluator#analyseUrl} tested on its own, against a real URL parser.
 *
 * <p>This method decides every {@link SinkKind#URL} verdict in the corpus. Its errors are not
 * symmetric: a false "dangerous" flags correct behaviour and someone investigates it, while a false
 * "safe" records a live vulnerability as {@link Verdict#SAFE}, where nobody looks at it again. Two
 * successive reviews of it found divergences from real browsers — nine the first time, seventy-seven
 * the second — and <em>every one of them pointed the same way</em>. That is the shape a blind oracle
 * has, and it is why the oracle needs a test of its own rather than the handful of incidental calls
 * it used to get from {@code HtmlEncoderUrlTest} and {@code CanoeCorpusTest}.
 *
 * <p>The corpus exercises only about five of {@code analyseUrl}'s branches, so the rules the method's
 * javadoc leads with — backslash-as-slash, the scheme asymmetry, tab/LF/CR removal, bracketed
 * literals, the safe-scheme allowlist, empty hosts, and now ports and scheme in the origin
 * comparison — were until this file effectively untested. Each is pinned below.
 *
 * <h2>Where the expectations come from</h2>
 *
 * <p><strong>Every expectation in {@link #pinnedCases()} is derived from Node's WHATWG {@code URL}
 * class, not from anyone's reading of the standard and not from what the oracle happens to do.</strong>
 * To regenerate or to check a new row, resolve the string against the same base URL this oracle uses:
 *
 * <pre>{@code
 * node -e '
 *   const BASE = "https://app.example/dir/page";
 *   const BASE_ORIGIN = "https://app.example";
 *   for (const s of ["//app.example:8443/x", "http://app.example/x"]) {
 *     try {
 *       const u = new URL(s, BASE);
 *       console.log(JSON.stringify(s), u.origin === BASE_ORIGIN ? "SAFE" : "DANGEROUS", u.origin, u.href);
 *     } catch (e) {
 *       console.log(JSON.stringify(s), "SAFE", "fails to parse");
 *     }
 *   }'
 * }</pre>
 *
 * <p>The mapping from Node to this oracle is: the URL fails to parse, or its {@code origin} equals
 * {@code https://app.example} → not dangerous; any other origin, including {@code null} for a
 * non-special scheme → dangerous. The four places the oracle deliberately disagrees with that mapping
 * are not hidden inside the table; they are in {@link #mailtoAndTelAreCalledSafeAlthoughTheyLeaveTheOrigin},
 * {@link #blobIsCalledDangerousAlthoughItsOriginIsThePage}, {@link #aBracketedHostIsNotValidatedAsIpv6}
 * and {@link #hostsAreNotIdnaMapped}, each with the reason it is deliberate.
 */
public class UrlOracleTest {

    // ------------------------------------------------------------------
    // The pinned table
    // ------------------------------------------------------------------

    /**
     * Node-derived ground truth. {@code true} means {@code analyseUrl} must report the URL as
     * dangerous. The note on each row is what Node produced, so a failure says what the browser
     * actually does rather than only that the boolean flipped.
     *
     * <p>Kept as a {@code MethodSource} rather than a {@code CsvSource} because several rows carry a
     * literal control character or a backslash, which CSV mangles, and because this file stays pure
     * ASCII: non-ASCII inputs are built with {@link #ch(int)} so no compiler default charset can
     * corrupt them.
     */
    static Stream<Arguments> pinnedCases() {
        List<Arguments> rows = new ArrayList<>();

        // --- Relative references stay on the page's own origin. -------------------------------
        row(rows, "", false, "empty; resolves to the base URL");
        row(rows, "   ", false, "all C0-and-space, stripped to empty");
        row(rows, "/x", false, "https://app.example/x");
        row(rows, "x", false, "https://app.example/dir/x");
        row(rows, "./x", false, "https://app.example/dir/x");
        row(rows, "../x", false, "https://app.example/x");
        row(rows, "#frag", false, "https://app.example/dir/page#frag");
        row(rows, "?q=1", false, "https://app.example/dir/page?q=1");
        row(rows, "/attacker.invalid/x", false, "one slash is a path: https://app.example/attacker.invalid/x");

        // --- Backslash is a path separator for a special scheme. ------------------------------
        row(rows, "\\attacker.invalid/x", false, "one separator, however spelled, is still a path");
        row(rows, "//attacker.invalid/x.js", true, "https://attacker.invalid/x.js");
        row(rows, "/\\attacker.invalid/x.js", true, "https://attacker.invalid/x.js");
        row(rows, "\\\\attacker.invalid/x.js", true, "https://attacker.invalid/x.js");
        row(rows, "\\/attacker.invalid/x", true, "https://attacker.invalid/x");
        row(rows, "///attacker.invalid/x", true, "the parser skips the whole run: https://attacker.invalid/x");
        row(rows, "////attacker.invalid/x", true, "https://attacker.invalid/x");
        row(rows, "/%5Cattacker.invalid/x.js", false, "%5C is not un-escaped into a separator");

        // --- Empty authority. -----------------------------------------------------------------
        row(rows, "//", false, "empty host for a special scheme: fails to parse");
        row(rows, "///", false, "fails to parse");

        // --- The scheme asymmetry. ------------------------------------------------------------
        row(rows, "https:/attacker.invalid/x", false,
                "the page's own scheme needs two separators: https://app.example/attacker.invalid/x");
        row(rows, "https:attacker.invalid/x", false, "https://app.example/dir/attacker.invalid/x");
        row(rows, "https:x", false, "https://app.example/dir/x");
        row(rows, "https:/x", false, "https://app.example/x");
        row(rows, "https:/\\attacker.invalid/x", true, "two separators, so an authority: https://attacker.invalid/x");
        row(rows, "https://attacker.invalid/x", true, "https://attacker.invalid/x");
        row(rows, "http:/attacker.invalid/x", true,
                "a different special scheme takes an authority after any run: http://attacker.invalid/x");
        row(rows, "http:attacker.invalid/x", true, "...including no run at all: http://attacker.invalid/x");
        row(rows, "http://attacker.invalid/x", true, "http://attacker.invalid/x");
        row(rows, "HTTPS://attacker.invalid/x.js", true, "the scheme is case-insensitive to the parser");

        // --- The origin is scheme, host AND port. ---------------------------------------------
        row(rows, "//app.example/x", false, "https://app.example/x");
        row(rows, "//APP.EXAMPLE/x", false, "ASCII case folding: https://app.example/x");
        row(rows, "//app.example", false, "https://app.example/");
        row(rows, "https://app.example/x", false, "https://app.example/x");
        row(rows, "//app.example:443/x", false, "the default port for https, so origin https://app.example");
        row(rows, "//app.example:/x", false, "an empty port is no port: origin https://app.example");
        row(rows, "https://app.example:/x", false, "origin https://app.example");
        row(rows, "//app.example:00443/x", false, "Node normalises 00443 to 443: origin https://app.example");
        row(rows, "//app.example:0443/x", false, "origin https://app.example");
        row(rows, "//app.example:8443/x", true, "origin https://app.example:8443 - a different port is a different origin");
        row(rows, "//app.example:80/x", true, "origin https://app.example:80 - 80 is not the https default");
        row(rows, "https://app.example:8443/x", true, "origin https://app.example:8443");
        row(rows, "https://app.example:443/x", false, "origin https://app.example");
        row(rows, "http://app.example/x", true, "origin http://app.example - a TLS downgrade is off-origin");
        row(rows, "http://app.example:80/x", true, "origin http://app.example");
        row(rows, "http://app.example:443/x", true, "origin http://app.example:443");
        row(rows, "http://app.example:00443/x", true, "Node normalises to origin http://app.example:443");
        row(rows, "//attacker.invalid:8443/x", true, "origin https://attacker.invalid:8443");
        row(rows, "https://attacker.invalid:8443/path", true, "origin https://attacker.invalid:8443");
        row(rows, "//app.example:65535/x", true, "origin https://app.example:65535");
        row(rows, "//app.example:99999/x", false, "a port above 65535 makes the URL fail to parse");

        // --- Userinfo splits at the LAST '@'. -------------------------------------------------
        row(rows, "https://trusted.example@attacker.invalid/x.js", true, "origin https://attacker.invalid");
        row(rows, "https://app.example@attacker.invalid/x", true, "origin https://attacker.invalid");
        row(rows, "https://a@b@app.example/x", false, "the last '@' wins: origin https://app.example");

        // --- Forbidden host code points, including the C0 range and U+007F. -------------------
        row(rows, "https://trusted.example%40attacker.invalid/x.js", false, "%40 decodes to '@': fails to parse");
        row(rows, "https://host%3A8443/path", false, "%3A decodes to ':': fails to parse, this is not a port");
        row(rows, "//at%25tacker.invalid/x", false, "'%' is itself forbidden in a host: fails to parse");
        row(rows, "//at%20tacker.invalid/x", false, "a space in a host: fails to parse");
        row(rows, "//attacker.invalid%2Fx", false, "'/' in a host: fails to parse");
        row(rows, "//at%00tacker.invalid/x", false, "NUL in a host: fails to parse");
        row(rows, "//at%01tacker.invalid/x", false, "U+0001 is a forbidden DOMAIN code point: fails to parse");
        row(rows, "//at%0Btacker.invalid/x", false, "U+000B: fails to parse");
        row(rows, "//at%0Ctacker.invalid/x", false, "U+000C: fails to parse");
        row(rows, "//at%7Ftacker.invalid/x", false, "U+007F is forbidden in a domain too: fails to parse");

        // --- Bracketed IPv6 literals. ---------------------------------------------------------
        row(rows, "https://[::1]/x", true, "origin https://[::1]");
        row(rows, "//[::1]/x", true, "origin https://[::1]");
        row(rows, "//[::1]:8443/x", true, "origin https://[::1]:8443");
        row(rows, "//[::1", false, "an unterminated bracket fails to parse");
        row(rows, "https://%5B%3A%3A1%5D/x", false, "an escaped bracket is not a bracket: fails to parse");

        // --- Schemes. -------------------------------------------------------------------------
        row(rows, "javascript:alert(1)", true, "javascript:alert(1)");
        row(rows, "  javascript:alert(1)", true, "leading space is stripped before the scheme is read");
        row(rows, "data:text/html,x", true, "data:text/html,x");
        row(rows, "data%3Atext/html%2Cx", false, "the colon is escaped, so this is a relative path");
        row(rows, "vbscript:msgbox(1)", true, "vbscript:msgbox(1)");
        row(rows, "view-source:https://app.example/x", true, "view-source: carries its own document");
        row(rows, "ftp://attacker.invalid/x", true, "origin ftp://attacker.invalid; off the allowlist");
        row(rows, "ws://attacker.invalid/x", true, "origin ws://attacker.invalid; off the allowlist");
        row(rows, "file:///etc/passwd", true, "origin null; off the allowlist");
        row(rows, "HTTPS%3A//attacker.invalid/x.js", false,
                "the colon is escaped, so the whole thing is one path segment");

        // --- Tab, LF and CR are removed from anywhere before parsing. -------------------------
        row(rows, "java" + ch(0x09) + "script:alert(1)", true, "the tab is removed: javascript:alert(1)");
        row(rows, "java" + ch(0x0a) + "script:alert(1)", true, "the LF is removed: javascript:alert(1)");
        row(rows, "java" + ch(0x0d) + "script:alert(1)", true, "the CR is removed: javascript:alert(1)");
        row(rows, "java" + ch(0x7f) + "script:alert(1)", false,
                "DEL is neither trimmed nor removed and is not a scheme character: a relative path");
        row(rows, "java\\x09script:alert(1)", false,
                "the four literal characters html() emits for a tab keep this relative");

        return rows.stream();
    }

    @ParameterizedTest(name = "[{index}] {0} -> dangerous={1}")
    @MethodSource("pinnedCases")
    public void analyseUrlAgreesWithNodesUrlParser(String url, boolean dangerous, String node) {
        VerdictEvaluator.UrlAnalysis analysis = VerdictEvaluator.analyseUrl(url);

        assertEquals(dangerous, analysis.isDangerous(),
                () -> "analyseUrl(" + quote(url) + ") said "
                        + (analysis.isDangerous() ? "DANGEROUS" : "SAFE")
                        + " but Node resolves it against " + VerdictEvaluator.BASE_ORIGIN
                        + "/dir/page to: " + node
                        + ". Oracle's reason: " + analysis.explanation()
                        + (dangerous
                        ? ". A false SAFE here records a real vulnerability as a SAFE corpus entry,"
                        + " which is the one failure mode the ledger cannot recover from."
                        : ". A false DANGEROUS here flags correct behaviour as a vulnerability."));
    }

    /** At least this many rows, so nobody trims the table back to the branches the corpus happens to hit. */
    @Test
    public void theTableIsNotAllowedToShrink() {
        long count = pinnedCases().count();
        assertTrue(count >= 40,
                () -> "the oracle's regression table has shrunk to " + count + " rows; it exists"
                        + " because the corpus exercises only about five of analyseUrl's branches");
    }

    // ------------------------------------------------------------------
    // Same origin means scheme, host and port
    // ------------------------------------------------------------------

    /**
     * The FIX 1 regression, stated as a property rather than as rows.
     *
     * <p>The oracle used to strip a numeric port, throw it away, and compare the remaining host to
     * {@code app.example} — with {@code BASE_SCHEME} used only to pick a separator rule and never
     * participating in the comparison at all. That made every one of these the page's own origin. It
     * was latent rather than wrong-in-the-ledger, because no corpus payload carries a port or an
     * {@code http://app.example} host; it would have stopped being latent the first time one did.
     */
    @Test
    public void aDifferentPortIsADifferentOrigin() {
        assertTrue(VerdictEvaluator.analyseUrl("//app.example:8443/x").isDangerous());
        assertTrue(VerdictEvaluator.analyseUrl("//app.example:80/x").isDangerous());
        assertTrue(VerdictEvaluator.analyseUrl("https://app.example:8443/x").isDangerous());
        assertTrue(VerdictEvaluator.analyseUrl("https://app.example:1/x").isDangerous());

        // The default port, however it is spelled, is the same origin as no port.
        assertFalse(VerdictEvaluator.analyseUrl("//app.example:443/x").isDangerous());
        assertFalse(VerdictEvaluator.analyseUrl("//app.example:0443/x").isDangerous());
        assertFalse(VerdictEvaluator.analyseUrl("//app.example:00443/x").isDangerous());
        assertFalse(VerdictEvaluator.analyseUrl("//app.example:/x").isDangerous());
        assertFalse(VerdictEvaluator.analyseUrl("//app.example/x").isDangerous());
    }

    /** {@code http:} on an {@code https:} page is an active downgrade, not the page's own origin. */
    @Test
    public void theSchemeParticipatesInTheOriginComparison() {
        assertTrue(VerdictEvaluator.analyseUrl("http://app.example/x").isDangerous());
        assertTrue(VerdictEvaluator.analyseUrl("http://app.example:80/x").isDangerous());
        assertTrue(VerdictEvaluator.analyseUrl("http:app.example/x").isDangerous());
        assertTrue(VerdictEvaluator.analyseUrl("http:/app.example/x").isDangerous());

        // The same host over the page's own scheme is fine, and so is a protocol-relative URL, which
        // inherits it.
        assertFalse(VerdictEvaluator.analyseUrl("https://app.example/x").isDangerous());
        assertFalse(VerdictEvaluator.analyseUrl("//app.example/x").isDangerous());
    }

    /**
     * {@code 443} is the https default and {@code 80} the http default, and neither is the other's.
     * Written out because a single shared default would pass every row of the table above except this
     * pairing.
     */
    @Test
    public void eachSchemeHasItsOwnDefaultPort() {
        assertFalse(VerdictEvaluator.analyseUrl("https://app.example:443/x").isDangerous());
        assertTrue(VerdictEvaluator.analyseUrl("https://app.example:80/x").isDangerous(),
                "80 is http's default, not https's");
        assertTrue(VerdictEvaluator.analyseUrl("http://app.example:443/x").isDangerous(),
                "443 is https's default, and this is an http: URL besides");
    }

    /** A port above 65535 is not a port; the URL fails to parse and reaches nothing. */
    @Test
    public void aPortAboveTheMaximumMakesTheUrlFailToParse() {
        assertFalse(VerdictEvaluator.analyseUrl("//attacker.invalid:65536/x").isDangerous());
        assertFalse(VerdictEvaluator.analyseUrl("//attacker.invalid:99999/x").isDangerous());
        assertFalse(VerdictEvaluator.analyseUrl("//attacker.invalid:123456789012345/x").isDangerous(),
                "a digit string too long for an int must not throw");

        assertTrue(VerdictEvaluator.analyseUrl("//attacker.invalid:65535/x").isDangerous(),
                "65535 is the largest port there is, and it parses");
    }

    // ------------------------------------------------------------------
    // Deliberate divergences from Node
    // ------------------------------------------------------------------

    /**
     * Divergence 1, and the only one that points the dangerous way. Node reports {@code origin: null}
     * for both, so the Node-derived rule would call them dangerous; the oracle calls them safe.
     *
     * <p>The justification is not the origin argument the allowlist's javadoc used to give — these
     * really do leave the origin. It is that neither loads content into the page nor navigates it:
     * the value goes to an external handler. That is a phishing and privacy problem, not an XSS, and
     * XSS is what this suite is about. See {@code VerdictEvaluator.SAFE_SCHEMES}.
     */
    @Test
    public void mailtoAndTelAreCalledSafeAlthoughTheyLeaveTheOrigin() {
        assertFalse(VerdictEvaluator.analyseUrl("mailto:a@attacker.invalid").isDangerous());
        assertFalse(VerdictEvaluator.analyseUrl("tel:+15551234").isDangerous());
    }

    /**
     * Divergence 2. Node gives {@code blob:https://app.example/uuid} the origin
     * {@code https://app.example}, so the Node-derived rule would call it safe. The oracle calls it
     * dangerous because a blob URL carries its own document, and a document at the page's origin
     * whose bytes the attacker chose is the definition of XSS. Erring loud.
     */
    @Test
    public void blobIsCalledDangerousAlthoughItsOriginIsThePage() {
        assertTrue(VerdictEvaluator.analyseUrl("blob:https://app.example/uuid").isDangerous());
    }

    /**
     * Divergence 3. The bracketed-host branch matches {@code [} and {@code ]}; it does not parse what
     * is between them. Node fails to parse all of these, so it never issues a request; the oracle
     * reports them as off-origin hosts.
     *
     * <p>Left as it is on purpose. Closing the gap means writing an IPv6 validator, and a validator
     * that wrongly rejected a real address would convert a live off-origin URL into a "fails to parse"
     * SAFE verdict — the one error direction this oracle must not have. Over-flagging costs a reviewer
     * a look.
     */
    @Test
    public void aBracketedHostIsNotValidatedAsIpv6() {
        assertTrue(VerdictEvaluator.analyseUrl("//[not-an-ip]/x").isDangerous(),
                "Node fails to parse this; the oracle over-flags, which is the safe direction");
        assertTrue(VerdictEvaluator.analyseUrl("//[zzz]/x").isDangerous());
        assertTrue(VerdictEvaluator.analyseUrl("//[]/x").isDangerous());

        // An unterminated bracket is the one bracketed case reported safe, and it agrees with Node.
        assertFalse(VerdictEvaluator.analyseUrl("https://[::1/x").isDangerous());
    }

    /**
     * Divergence 4. The standard runs a host through IDNA ToASCII before comparing it, which maps
     * U+FF0E FULLWIDTH FULL STOP to {@code .} and deletes U+00AD SOFT HYPHEN. This oracle does ASCII
     * case folding and nothing else, so both of these resolve to {@code app.example} in Node and are
     * reported off-origin here. Recorded, not implemented: it over-flags, and nothing in the corpus
     * generates a non-ASCII host — since R12 {@code url()} UTF-8 percent-encodes every non-ASCII code
     * point (it no longer collapses them to a literal {@code ?}), so a non-ASCII host reaches the
     * oracle as {@code %XX} bytes rather than as raw characters.
     */
    @Test
    public void hostsAreNotIdnaMapped() {
        assertTrue(VerdictEvaluator.analyseUrl("//app" + ch(0xff0e) + "example/x").isDangerous(),
                "U+FF0E maps to '.' under IDNA, so Node resolves this to https://app.example");
        assertTrue(VerdictEvaluator.analyseUrl("//app.example" + ch(0x00ad) + "/x").isDangerous(),
                "IDNA deletes U+00AD, so Node resolves this to https://app.example");

        // ASCII case is handled, which is the part that matters for anything the corpus produces.
        assertFalse(VerdictEvaluator.analyseUrl("//APP.EXAMPLE/x").isDangerous());
        assertFalse(VerdictEvaluator.analyseUrl("//App.Example/x").isDangerous());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static void row(List<Arguments> rows, String url, boolean dangerous, String node) {
        rows.add(Arguments.of(url, dangerous, node));
    }

    /**
     * A one-character string from a code unit, so that this file stays pure ASCII and cannot be
     * corrupted by a compiler running under a non-UTF-8 default charset — the same convention as
     * {@code Payloads} and {@code HtmlEncoderTest}.
     */
    private static String ch(int codeUnit) {
        return String.valueOf((char) codeUnit);
    }

    private static String quote(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c > 0x7e) {
                sb.append("\\u").append(String.format("%04X", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
