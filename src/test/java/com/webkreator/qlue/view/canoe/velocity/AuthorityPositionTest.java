package com.webkreator.qlue.view.canoe.velocity;

import com.webkreator.qlue.view.Canoe;
import com.webkreator.qlue.view.CanoeStateProbe;
import com.webkreator.qlue.view.canoe.CanoeTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R29, R30 and R31 — the second review's four findings, and the properties that keep them closed.
 *
 * <p>All four are routing defects rather than encoding defects, and they share one shape: R9's origin
 * filter answers <em>the wrong question</em> or is <em>not asked at all</em>, while the encoder behind
 * it is correct. That is why nothing in this file asserts on encoder output in isolation. Every
 * assertion is about which encoder a value reached and what the sink was handed.
 *
 * <ul>
 *   <li><strong>F25 / F27 (R29).</strong> {@code RESOURCE_LOADING_SINKS} was one attribute per
 *       element, so SVG's {@code <script href>} and {@code <script xlink:href>} — the way SVG 2 and
 *       SVG 1.1 load an external script, both live in every shipping engine — took the ordinary
 *       {@code url()} while {@code src} on the same element was origin-filtered. {@code <frame src>}
 *       was missing outright. Both were measured live: the attacker's script executed in Chromium,
 *       Firefox and WebKit.
 *   <li><strong>F26 (R30).</strong> {@code urlResource()} rejects a value that <em>by itself</em>
 *       introduces an authority, and it never sees the literal text the template wrote in front of
 *       the reference. {@code <script src="/$path">} with {@code path = "/attacker.example/x.js"}
 *       rendered {@code //attacker.example/x.js}: every character of that host came from a value with
 *       no authority in it. The fix is positional and lives in Canoe, because the fact that decides it
 *       is not in the reference.
 *   <li><strong>F28 (R31).</strong> The value prefix scan counted characters the URL parser removes,
 *       so one space in front of an author's {@code javascript:} URL pushed {@code javascript} past
 *       the ten-character window and the handler stopped being suppressed.
 * </ul>
 *
 * <h2>Why the first review's corpus could not have caught any of them</h2>
 *
 * <p>No corpus template pairs {@code xlink:href} with anything but {@code <svg><a>}, none contains a
 * {@code <frame>}, and none puts literal URL text in front of a reference in a resource-loading sink.
 * The two URL templates that do carry a literal prefix — {@code <a href="https://app.example/$data">}
 * and {@code <a href="/search?q=$data">} — both close the authority before the reference, which is
 * precisely the half of the position axis that was already safe. The gap was in the quantification,
 * as it was for F24.
 */
public class AuthorityPositionTest {

    private static final String OFF_ORIGIN = "//attacker.example/x.js";

    // ---------------------------------------------------------------------------------------
    // R29 — the resource-sink table is element -> set of attribute names (F25, F27)
    // ---------------------------------------------------------------------------------------

    /**
     * F25. All three of SVG's and HTML's script-loading attribute names are origin-filtered on
     * {@code <script>}, and the failure this pins is that {@code href} and {@code xlink:href} were not.
     *
     * <p>Asserted as an <em>equality between the three</em> rather than as three separate suppressions,
     * because the finding is that they disagreed: {@code src} was suppressed and the other two were
     * emitted byte for byte, on the same element, in the same render.
     */
    @ParameterizedTest
    @ValueSource(strings = {"src", "href", "xlink:href"})
    public void everyScriptLoadingAttributeIsOriginFiltered(String attributeName) {
        CanoeTestSupport.RenderResult offOrigin = CanoeTestSupport.render(
                "<svg><script " + attributeName + "=\"$data\"></script></svg>", OFF_ORIGIN);
        assertEquals("<svg><script " + attributeName + "=\"\"></script></svg>", offOrigin.output(),
                attributeName + " on <script> must reject an off-origin authority (F25)");

        CanoeTestSupport.RenderResult relative = CanoeTestSupport.render(
                "<svg><script " + attributeName + "=\"$data\"></script></svg>", "/app.js");
        assertEquals("<svg><script " + attributeName + "=\"/app.js\"></script></svg>",
                relative.output(),
                attributeName + " on <script> must still carry a same-origin-relative URL");
    }

    /**
     * F25's mechanism, from the other side: the same attribute name is a link on one element and code
     * on another, which is R9's own argument applied to an element its table did not model.
     *
     * <p>{@code <svg><a xlink:href>} keeps {@code url()} and therefore keeps F6's accepted residual —
     * an off-origin link is an ordinary thing for a page to contain, and R26 ledgers it as
     * {@code OPEN_REDIRECT}. {@code <svg><script xlink:href>} is a script include and must not.
     */
    @Test
    public void theSameSvgNameIsALinkOnAnAnchorAndCodeOnAScript() {
        assertEquals("//attacker.example/x.js", CanoeTestSupport.render(
                        "<svg><a xlink:href=\"$data\"><text>go</text></a></svg>", OFF_ORIGIN)
                .decodedAttr("a", "xlink:href"),
                "an off-origin SVG link is F6's accepted residual and stays");

        assertEquals("", CanoeTestSupport.render(
                        "<svg><script xlink:href=\"$data\"></script></svg>", OFF_ORIGIN)
                .decodedAttr("script", "xlink:href"),
                "the identical name on <script> loads code and must be filtered (F25)");
    }

    /**
     * F27. {@code <frame src>} is {@code <iframe src>} under its obsolete spelling: an attacker
     * document in the page's own frame tree. Obsolete in the standard is not dead in the code — the
     * distinction R26's {@code INERT_SINK} note draws — and all three engines loaded it.
     */
    @Test
    public void frameSrcIsTheIframeSinkUnderItsObsoleteSpelling() {
        assertEquals("<frameset><frame src=\"\"></frameset>",
                CanoeTestSupport.render("<frameset><frame src=\"$data\"></frameset>", OFF_ORIGIN)
                        .output(),
                "<frame src> must be origin-filtered exactly as <iframe src> is (F27)");

        assertEquals("<iframe src=\"\"></iframe>",
                CanoeTestSupport.render("<iframe src=\"$data\"></iframe>", OFF_ORIGIN).output(),
                "the control: <iframe src> was already filtered");
    }

    /** Every element/attribute combination the table now holds, one row each. */
    @ParameterizedTest
    @CsvSource({
            "script, src",
            "script, href",
            "script, xlink:href",
            "iframe, src",
            "frame, src",
            "embed, src",
            "object, data",
            "link, href",
            "base, href",
    })
    public void everyResourceSinkCombinationRejectsAnOffOriginAuthority(String element,
                                                                       String attributeName)
            throws IOException {
        CanoeStateProbe probe = new CanoeStateProbe();
        probe.feed("<" + element + " " + attributeName + "=\"");

        assertEquals(Canoe.ATTR_URI_RESOURCE, probe.attributeContext(),
                element + "/" + attributeName + " must classify as a resource-loading sink");
        assertEquals(Canoe.CTX_URI_RESOURCE, probe.currentContext());
        assertEquals("", probe.encode(OFF_ORIGIN));
        assertEquals(attributeName, probe.urlAttributeName(),
                "the diagnostic must name the attribute the value went missing from");
    }

    /**
     * The boundary, written down so it is a decision rather than an omission. These fetch a resource
     * and are deliberately left on {@code url()} with {@code <img src>}, which is where F6's accepted
     * residual already lives: {@code <use>} is refused cross-origin by every current engine, and the
     * rest are media.
     */
    @ParameterizedTest
    @CsvSource({
            "'<svg><use href=\"$data\"></use></svg>',            use,   href",
            "'<svg><image href=\"$data\"></image></svg>',        image, href",
            "'<video src=\"$data\"></video>',                    video, src",
            "'<audio><source src=\"$data\"></audio>',            source, src",
            "'<video><track src=\"$data\"></video>',             track, src",
            "'<input type=\"image\" src=\"$data\">',             input, src",
            "'<img src=\"$data\">',                              img,   src",
            "'<a href=\"$data\">x</a>',                          a,     href",
    })
    public void theFetchNotCodeNamesKeepTheOrdinaryUrlEncoder(String template, String selector,
                                                              String attributeName) {
        assertEquals("//attacker.example/x.js",
                CanoeTestSupport.render(template, OFF_ORIGIN).decodedAttr(selector, attributeName),
                selector + "/" + attributeName + " is an F6 accepted residual, not a code sink");
    }

    /** A tag name nothing maps, and a mapped tag with an unmapped URL attribute: neither is a sink. */
    @Test
    public void anUnmappedElementOrAttributeIsNotAResourceSink() throws IOException {
        assertEquals(Canoe.ATTR_URI, new CanoeStateProbe().feed("<div src=\"").attributeContext(),
                "<div> is in no row of the table");
        assertEquals(Canoe.ATTR_URI, new CanoeStateProbe().feed("<script poster=\"")
                        .attributeContext(),
                "<script> is in the table, but poster is not one of its three names");
    }

    // ---------------------------------------------------------------------------------------
    // R30 — the URL context has a position (F26)
    // ---------------------------------------------------------------------------------------

    /**
     * The position machine itself, one row per transition. The claim is not that these strings are
     * URLs; it is that Canoe knows, character by character, whether the authority is still open.
     */
    @ParameterizedTest
    @CsvSource({
            // literal value text,     expected position
            "'',                       URLV_START",
            "'/',                      URLV_SLASH",
            "'//',                     URLV_AUTHORITY",
            "'//host',                 URLV_AUTHORITY",
            "'//host/',                URLV_PATH",
            "'//host?',                URLV_PATH",
            "'//host#',                URLV_PATH",
            "'/p',                     URLV_PATH",
            "'/p/',                    URLV_PATH",
            "'h',                      URLV_SCHEME",
            "'https',                  URLV_SCHEME",
            "'h+t-t.p2',               URLV_SCHEME",
            // Both ends of the scheme grammar's two ASCII ranges, because a fold that accepted one
            // code point past 'z' or 'Z' would read a value as a scheme that no URL parser does.
            "'h_t',                    URLV_PATH",
            "'h{t',                    URLV_PATH",
            "'HTTPS:',                 URLV_AFTER_SCHEME",
            "'9t',                     URLV_PATH",
            "'https:',                 URLV_AFTER_SCHEME",
            "'https:/',                URLV_AFTER_SCHEME",
            "'https://',               URLV_AFTER_SCHEME",
            "'https://cdn.ok',         URLV_AUTHORITY",
            "'https://cdn.ok/',        URLV_PATH",
            "'https://cdn.ok/a?b#c',   URLV_PATH",
            "'?q=1',                   URLV_PATH",
            "'#f',                     URLV_PATH",
            "'.',                      URLV_PATH",
    })
    public void theUrlPositionIsTrackedThroughTheValue(String valueText, String expected)
            throws IOException {
        CanoeStateProbe probe = new CanoeStateProbe();
        probe.feed("<script src=\"" + valueText);
        assertEquals(expected, CanoeStateProbe.urlValueStateName(probe.urlValueState()),
                "position after " + CanoeTestSupport.quote(valueText));
    }

    /**
     * F26's core. A reference that lands where the browser is still reading the host is suppressed,
     * because there is no encoding of a hostname that means anything but that hostname — the argument
     * F20 makes about policy tokens, in a different sink.
     */
    @ParameterizedTest
    @CsvSource({
            "'<script src=\"//$data\"></script>',                    attacker.example/x.js",
            "'<script src=\"//cdn.ok$data\"></script>',              .attacker.example/x.js",
            "'<script src=\"https://$data\"></script>',              attacker.example/x.js",
            "'<script src=\"https://cdn.ok$data\"></script>',        .attacker.example/x.js",
            "'<link href=\"//cdn.ok$data\">',                        .attacker.example/x.css",
            "'<base href=\"//$data\">',                              attacker.example/",
            "'<iframe src=\"https://$data\"></iframe>',              attacker.example/",
    })
    public void aReferenceInsideTheAuthorityIsSuppressed(String template, String payload) {
        String rendered = CanoeTestSupport.render(template, payload).output();
        assertFalse(rendered.contains("attacker"),
                "a value that extends the host must be suppressed, got: " + rendered);
    }

    /**
     * The one position where the value is allowed but must not begin with a slash: after the
     * template's own single leading {@code '/'}, the two together are {@code //host}.
     *
     * <p>Both directions in one test on purpose. Suppressing every value after a leading slash would
     * also pass the security half and would break {@code <script src="/$bundle">}, which is the shape
     * this finding is about.
     */
    @Test
    public void afterALeadingSlashOnlyASecondSlashIsRefused() {
        assertEquals("<script src=\"/\"></script>",
                CanoeTestSupport.render("<script src=\"/$data\"></script>",
                        "/attacker.example/x.js").output(),
                "the leading slash and the value's own make a protocol-relative URL (F26)");

        assertEquals("<script src=\"/app.js\"></script>",
                CanoeTestSupport.render("<script src=\"/$data\"></script>", "app.js").output(),
                "an ordinary path segment after the slash is the shape this sink exists for");
    }

    /**
     * Positions past the authority are untouched, which is the other half of the rule: once the host
     * is decided nothing a value emits can move it, so suppressing there would be pure cost.
     */
    @ParameterizedTest
    @CsvSource({
            "'<script src=\"/static/$data\"></script>',              /attacker.example/x.js",
            "'<script src=\"https://cdn.ok/$data\"></script>',       /attacker.example/x.js",
            "'<script src=\"/p/$data\"></script>',                   /attacker.example/x.js",
    })
    public void aReferencePastTheAuthorityIsEncodedAndKept(String template, String payload) {
        String rendered = CanoeTestSupport.render(template, payload).output();
        assertTrue(rendered.contains("attacker.example"),
                "the host is already decided here, so the value is a path: " + rendered);
        assertFalse(rendered.contains("=\"//") || rendered.contains("\"//attacker"),
                "and it must not be able to start an authority: " + rendered);
    }

    /**
     * The template need contain no off-origin literal at all: two ordinary references are enough, and
     * the position machine sees the first one's <em>encoder output</em> because Velocity writes it
     * back through this writer.
     */
    @Test
    public void twoReferencesCannotBuildAnAuthorityBetweenThem() {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("base", "/");
        model.put("path", "/attacker.example/x.js");
        assertEquals("<script src=\"/\"></script>",
                CanoeTestSupport.render("<script src=\"$base$path\"></script>", model).output(),
                "the first reference moved the position to URLV_SLASH (F26)");
    }

    /**
     * The position is reset at the {@code '='} and not at the first value character, so a reference
     * inserted in TAG_ATTR_VALUE_BEFORE — F11's unquoted shape, which R19 made renderable — is judged
     * from URLV_START rather than from whatever the previous attribute's value left behind.
     */
    @Test
    public void theValuePositionIsResetAtTheEqualsSoAnUnquotedValueIsJudged() throws IOException {
        CanoeStateProbe probe = new CanoeStateProbe();
        probe.feed("<script src=\"https://cdn.ok\" src=");

        assertEquals(Canoe.TAG_ATTR_VALUE_BEFORE, probe.state());
        assertEquals("URLV_START", CanoeStateProbe.urlValueStateName(probe.urlValueState()),
                "the previous value ended inside an authority and must not be inherited");
        assertEquals("", probe.encode(OFF_ORIGIN), "and the standalone origin check still applies");
        assertEquals("/app.js", probe.encode("/app.js"));
    }

    /**
     * {@code encode(null)} in slash position. Not reachable from
     * {@code CanoeReferenceInsertionHandler}, which returns early for a null reference, but
     * {@link Canoe#writeEncoded(String)} is public and {@code urlResource()} answers null for null —
     * so the slash guard has to be written to survive it rather than to assume it away.
     */
    @Test
    public void aNullValueInSlashPositionIsNotDereferenced() throws IOException {
        CanoeStateProbe probe = new CanoeStateProbe();
        probe.feed("<script src=\"/");
        assertEquals("URLV_SLASH", CanoeStateProbe.urlValueStateName(probe.urlValueState()));
        assertEquals(null, probe.encode(null));
    }

    /**
     * {@code CTX_URI} is deliberately <strong>not</strong> gated, and this test is where that decision
     * is recorded rather than left to be rediscovered as a fifth finding.
     *
     * <p>{@code <a href="/$slug">} with a payload of {@code /attacker.example} is an open redirect.
     * So is {@code <a href="$u">} with {@code //attacker.example}, which R9 scoped out and R26 ledgered
     * as one of 68 {@code ACCEPTED_RESIDUAL} rows. The outcome is the same in both, so gating the
     * concatenated spelling while the direct one is accepted would be an inconsistency and not a fix.
     *
     * <p>What the second review does change is that these positions are now <em>known</em> to be in
     * the residue: T16 said a path-suffix position could not reach the authority, and
     * that was measured only at {@code href="/p/$data"}, where it is true.
     */
    @Test
    public void theOpenRedirectSinksAreDeliberatelyNotPositionGated() {
        assertEquals("//attacker.example",
                CanoeTestSupport.render("<a href=\"/$data\">x</a>", "/attacker.example")
                        .decodedAttr("a", "href"),
                "an open redirect through concatenation is the same residual as one at offset 0");

        assertNotEquals("", CanoeTestSupport.render("<img src=\"//cdn.ok$data\">", ".attacker.example/p.png")
                        .decodedAttr("img", "src"),
                "and so is a referrer leak");
    }

    // ---------------------------------------------------------------------------------------
    // R31 — the prefix buffer sees what the URL parser sees (F28)
    // ---------------------------------------------------------------------------------------

    /**
     * F28. A URL parser removes leading C0 controls and spaces and removes all tab, LF and CR from
     * anywhere, so each of these <em>is</em> a {@code javascript:} URL to every engine — measured in
     * Chromium, Firefox and WebKit, where the payload executed on click before R31.
     *
     * <p>{@code url()} is not sufficient there and the reason is a third decoder: the HTML Standard
     * obtains a {@code javascript:} URL's script source by <strong>percent-decoding</strong> it, so
     * {@code url()}'s {@code %27} became an apostrophe again after the HTML parser had finished.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            " javascript:f('$data')",
            "  javascript:f('$data')",
            "\tjavascript:f('$data')",
            "\njavascript:f('$data')",
            "\rjavascript:f('$data')",
            "java\tscript:f('$data')",
            "jav\nascript:f('$data')",
            " java\tscript:f('$data')",
    })
    public void charactersTheUrlParserStripsDoNotShiftThePrefixWindow(String value) {
        String rendered = CanoeTestSupport.render("<a href=\"" + value + "\">x</a>",
                "');alert(1);//").output();
        assertTrue(rendered.contains("f('')"),
                "the scheme must be recognised however the value is padded (F28), got: " + rendered);
    }

    /** The control, and the two shapes the fix must not widen to. */
    @Test
    public void onlyTheCharactersAUrlParserRemovesAreIgnored() {
        assertTrue(CanoeTestSupport.render("<a href=\"javascript:f('$data')\">x</a>",
                        "');alert(1);//").output().contains("f('')"),
                "the plain spelling was always suppressed");

        // A space that is not leading is an ordinary value character and still fills the window, so
        // the scheme here is eleven characters in and is not recognised - which is F28's mechanism
        // with the trigger the strip does not remove. It is safe for the reason the finding gives:
        // a URL parser does not strip an interior space either, so this is not a javascript: URL.
        String interior = CanoeTestSupport.render("<a href=\"x /javascript:f($data)\">x</a>",
                "');alert(1);//").decodedAttr("a", "href");
        assertTrue(interior.contains("%27"),
                "the value took url() rather than suppression, as the browser's own reading of it"
                        + " requires: " + interior);
        assertFalse(interior.startsWith("javascript:"),
                "and what precedes it is not a scheme to any parser: " + interior);
    }

    /** The prefix scan folds case and the strip runs before the fold, so both apply together. */
    @Test
    public void theStripAndTheCaseFoldComposeIntoTheSameSuppression() {
        assertTrue(CanoeTestSupport.render("<a href=\" JaVa\tScRiPt:f('$data')\">x</a>",
                        "');alert(1);//").output().contains("f('')"));
    }

    /**
     * The other four prefixes reach the same narrowing through the same buffer, so the strip must not
     * be a {@code javascript}-shaped special case.
     */
    @ParameterizedTest
    @ValueSource(strings = {"javascript", "livescript", "mocha", "data", "asfunction"})
    public void everyRecognisedValuePrefixSurvivesLeadingWhitespace(String scheme)
            throws IOException {
        CanoeStateProbe probe = new CanoeStateProbe();
        probe.feed("<a title=\" \t" + scheme + ":");
        assertNotEquals(Canoe.ATTR_HTML, probe.attributeContext(),
                scheme + ": must still narrow the context when the value is padded");
        assertEquals("", probe.encode("');alert(1);//"),
                "and every context this method can assign - ATTR_JS, ATTR_DATA, ATTR_ACTIONSCRIPT -"
                        + " emits nothing, which is why narrowing is the only safe direction here");
    }
}
