package com.webkreator.qlue.view.canoe.velocity;

import com.webkreator.qlue.view.Canoe;
import com.webkreator.qlue.view.canoe.CanoeTestSupport;
import com.webkreator.qlue.view.canoe.corpus.CanoeCorpus;
import com.webkreator.qlue.view.canoe.corpus.Payload;
import com.webkreator.qlue.view.canoe.corpus.Payloads;
import com.webkreator.qlue.view.canoe.corpus.SinkKind;
import com.webkreator.qlue.view.canoe.corpus.Verdict;
import com.webkreator.qlue.view.canoe.corpus.VerdictEvaluator;
import com.webkreator.qlue.view.canoe.corpus.XssCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code <script>} and {@code <style>} element bodies: total suppression, and the two places where
 * Canoe's model of where those bodies <em>end</em> disagrees with the HTML Standard's.
 *
 * <p>The suppression half is short. A reference inside either body encodes to the empty string, which
 * is the design working and is the reason F16's defective {@code js()} and {@code css()} are latent
 * rather than live. Almost every row in this group is therefore {@code SUPPRESSED_BY_DESIGN}, and a
 * file that only asserted that would be a file that could not fail.
 *
 * <h2>What this file asserts that {@code CanoeCorpusTest} does not</h2>
 *
 * <p>The interesting content is <strong>F10</strong>, and F10 is not a per-case verdict — it is a
 * disagreement between two parsers about the same bytes, which no single-parser ledger row can state.
 * The corpus records four rows ({@code desync.script-end-tag-with-a-suffix},
 * {@code desync.script-stuck-on-a-double-less-than} and their CSS twins) and each of them records
 * only Canoe's side: what Canoe emitted, and whether it was live. This file asserts the
 * <em>agreement</em>, by putting the same string through Canoe's state machine and through a real
 * HTML parser and requiring the two to reach the same conclusion. Until <strong>R17</strong> it
 * required them to disagree, and what it recorded was:
 *
 * <ul>
 *   <li><strong>Forward desync.</strong> {@code SCRIPT_END} matched the seven characters
 *       {@code /script} and set {@code state = TAG} with no check that what followed was whitespace,
 *       {@code /} or {@code >}. So {@code </scriptfoo>} returned Canoe to HTML while the browser's
 *       script-data-end-tag-name state kept it in script data, and Canoe encoded the rest of the page
 *       for contexts that did not exist there — including, if a URL attribute followed,
 *       {@code CTX_URI}.
 *   <li><strong>Converse desync.</strong> {@code <script>x = 1 <</script>} left Canoe stuck in
 *       {@code SCRIPT}: {@code SCRIPT_END} mismatched on the second {@code <} and returned to
 *       {@code SCRIPT} <em>without re-processing that character</em>, so the real {@code </script>}
 *       was never seen and every reference for the rest of the page silently became the empty string.
 *       Same shape as F14, different state.
 *   <li>{@code CSS_END} had both defects identically with {@code /style}, and the twins were asserted
 *       rather than assumed, because "identical code" is a claim about the source and this is a claim
 *       about behaviour.
 * </ul>
 *
 * <p>R17 closed both halves. {@code SCRIPT_END}/{@code CSS_END} now hand the name to
 * {@code SCRIPT_END_NAME}/{@code CSS_END_NAME}, which require the delimiter the HTML Standard
 * requires before the element is treated as closed, and both states re-process a mismatching
 * character instead of dropping it. The tests below keep their subjects and invert their claims;
 * each says what it used to be called and what it used to assert.
 *
 * <h2>The precondition, and why it is not asserted here</h2>
 *
 * <p>F10 was Low (latent) rather than exploitable for exactly one reason: <strong>attacker data can
 * never emit a raw {@code <}</strong>, so only template literal text could enter {@code SCRIPT_END}.
 * {@link #onlyTemplateTextCanCauseADesync} states that over this file's own templates and the whole
 * payload catalogue, which is the local form of the claim. R17 does not weaken it and it passes
 * unchanged: no encoder can emit a {@code <}, so no payload can steer these states, whichever way
 * they now transition.
 *
 * <p>The general form belongs to <strong>{@code ParserSteeringTest} (T23)</strong>: the property that
 * for every corpus template, the sequence of {@code currentContext()} values observed at each
 * reference position is identical whether the reference value is the inert marker or any payload in
 * the catalogue. That was the property F10's unexploitability rested on, and it is what any future
 * desync would have to break to matter. The review asks explicitly that any relaxation of the
 * encoders — wiring {@code HtmlEncoder.js()} into the {@code CTX_JS} arm, or a CSS encoder into the
 * suppressed {@code style} route — be checked against it before it lands.
 */
public class ScriptAndStyleElementTest {

    // ------------------------------------------------------------------
    // Suppression: the design working
    // ------------------------------------------------------------------

    /**
     * Every corpus case whose reference sits inside a {@code <script>} or {@code <style>} element
     * body, selected structurally so a case added later is picked up without anyone remembering to.
     */
    static List<XssCase.Invocation> elementBodyInvocations() {
        List<XssCase.Invocation> result = new ArrayList<>();
        for (XssCase testCase : CanoeCorpus.all()) {
            boolean isElementBody = (testCase.sink() == SinkKind.JAVASCRIPT
                    || testCase.sink() == SinkKind.CSS) && testCase.attribute() == null;
            if (isElementBody) {
                result.addAll(testCase.invocations());
            }
        }
        return result;
    }

    /**
     * A reference inside a {@code <script>} or {@code <style>} body contributes nothing, for every
     * payload in every case.
     *
     * <p>Asserted against the render with an empty value rather than against a literal string, so the
     * claim is "the payload contributed nothing" rather than "the output looked like this".
     *
     * <p>This is the row that flips the day {@code CTX_JS} is relaxed to real escaping, and it should:
     * every one of these becomes a claim about {@code HtmlEncoder.js()} instead. R13 corrected
     * {@code js()} (before it, per F16, an astral code point like {@code U+10027} was truncated to an
     * apostrophe inside the string literal these cases test), but it is not wired in, so the row stays
     * a suppression. The failure is the point.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("elementBodyInvocations")
    public void aReferenceInAScriptOrStyleBodyContributesNothing(XssCase.Invocation invocation) {
        XssCase testCase = invocation.testCase();
        String rendered = VerdictEvaluator.render(testCase, invocation.payload().value()).output();
        String benign = VerdictEvaluator.render(testCase, "").output();

        assertEquals(benign, rendered,
                () -> invocation + ": CTX_JS and the CSS states both encode to the empty string, so"
                        + " the render must be byte-identical to one with no value at all");
        assertTrue(invocation.verdict().isSuppression(),
                () -> invocation + " is ledgered " + invocation.verdict() + " but emits nothing;"
                        + " an element-body row can only be a suppression");
    }

    /**
     * The six states involved, named, so that a failure above says which one changed. It was four
     * until R17 split the "name matched" moment out of {@code SCRIPT_END}/{@code CSS_END} into
     * {@code SCRIPT_END_NAME}/{@code CSS_END_NAME}, which are still inside the element body and still
     * produce the body's context — being part-way into something that might be an end tag is not
     * being out of the element.
     *
     * <p>Note that {@code <style>} produces {@code CTX_SUPPRESS}. There is no {@code CTX_CSS}: R14
     * deleted the constant and its dead {@code encode()} arm (<strong>F21</strong>), so the
     * {@code CSS}/{@code CSS_END} states, like every route a {@code style} value takes, suppress. That
     * is a settled design decision, not a hole in the switch;
     * {@code AttributeNameMatrixTest.thereIsNoCtxCssAndStyleStillSuppresses} owns it.
     *
     * <p>Was {@code theFourStatesAScriptOrStyleBodyCanBeIn} until R17 added the two
     * {@code *_END_NAME} states; the subject is unchanged and every former assertion is still here.
     */
    @Test
    public void theSixStatesAScriptOrStyleBodyCanBeIn() {
        assertEquals(Canoe.CTX_JS, CanoeTestSupport.contextAfter("<script>"),
                "SCRIPT");
        assertEquals(Canoe.CTX_JS, CanoeTestSupport.contextAfter("<script>x</scr"),
                "SCRIPT_END, partway through the seven characters it matches");
        assertEquals(Canoe.CTX_JS, CanoeTestSupport.contextAfter("<script>x</script"),
                "SCRIPT_END_NAME (R17): the name is matched and the element is not closed yet");
        assertEquals(Canoe.CTX_SUPPRESS, CanoeTestSupport.contextAfter("<style>"),
                "CSS - CTX_SUPPRESS; there is no CTX_CSS since R14 (F21)");
        assertEquals(Canoe.CTX_SUPPRESS, CanoeTestSupport.contextAfter("<style>a{}</sty"),
                "CSS_END");
        assertEquals(Canoe.CTX_SUPPRESS, CanoeTestSupport.contextAfter("<style>a{}</style"),
                "CSS_END_NAME (R17)");

        assertEquals(Canoe.CTX_HTML, CanoeTestSupport.contextAfter("<script>x=1;</script><p>"),
                "...and a well-formed end tag returns the machine to HTML, which is the assertion"
                        + " the two desyncs below are measured against");
        assertEquals(Canoe.CTX_HTML, CanoeTestSupport.contextAfter("<script>x=1;</SCRIPT><p>"),
                "SCRIPT_END lowercases as it compares, so an uppercase end tag closes correctly."
                        + " Worth pinning: a case-sensitive comparison here would be the converse"
                        + " desync reached by ordinary markup rather than by a stray '<', and that"
                        + " WOULD be a new finding.");

        // ...and the fold is bounded to ASCII, which is the other half of that claim and was
        // missing until this file was reviewed. Character.toLowerCase(U+0130 LATIN CAPITAL
        // LETTER I WITH DOT ABOVE) is 'i', so an end tag spelled with it used to match
        // '/script' and close the element while every browser stayed in script data - F10's
        // forward desync, reached by a character the delimiter rule never sees. U+0130 is the
        // only code point in the BMP whose Character.toLowerCase() lands in '/script' or
        // '/style', so this one row is the whole of the extra surface the wider fold bought.
        // Written as an escape, per the ASCII rule in this package's README.
        assertEquals(Canoe.CTX_JS,
                CanoeTestSupport.contextAfter("<script>x=1;</scr\u0130pt><p>"),
                "the end-tag-name states fold ASCII and nothing else: U+0130 is not an ASCII"
                        + " letter, so the run is script data to Canoe exactly as it is to the"
                        + " browser");
        assertEquals("<script>x=1;</scr\u0130pt><p></p>",
                CanoeTestSupport.render("<script>x=1;</scr\u0130pt><p>$data</p>", "harmless")
                        .output(),
                "still inside the script element for both parsers, so the reference is suppressed"
                        + " rather than html-encoded into what the browser reads as JavaScript");
    }

    // ------------------------------------------------------------------
    // F10, forward: </scriptfoo> is no longer accepted as a terminator
    // ------------------------------------------------------------------

    /**
     * F10's forward desync, closed by R17 and now inverted. Was
     * {@code aScriptEndTagWithASuffixClosesTheScriptForCanoeAndNotForTheParser}, and it asserted the
     * disagreement: Canoe believed {@code </scriptfoo>} closed the script and encoded what followed
     * for {@code CTX_HTML}, while a real HTML parser did not — per the script-data-end-tag-name
     * state an end tag is only appropriate when the name is followed by whitespace, {@code /} or
     * {@code >}, so {@code </scriptfoo>} is character data and the tokenizer stays in script data.
     * The old test put the same string through both parsers and required them to differ, with jsoup
     * still holding the following text inside the {@code <script>} element and the {@code htmlWhite()}
     * output landing there as {@code &#39;} — inert only because script data is not entity-decoded.
     *
     * <p>R17 gives Canoe the standard's rule, so the two parsers now agree and the value that used to
     * land in script data does not land anywhere: the reference is inside the script body, and a
     * script body is suppressed.
     */
    @Test
    public void aScriptEndTagWithASuffixClosesTheScriptForNeitherCanoeNorTheParser() {
        String template = "<script>x=1;</scriptfoo>$data";

        assertEquals(Canoe.CTX_JS, CanoeTestSupport.contextAfter("<script>x=1;</scriptfoo>"),
                "R17: SCRIPT_END_NAME requires a delimiter after the name, so '</scriptfoo>' leaves"
                        + " the machine in the script body - which is where the browser is");

        CanoeTestSupport.RenderResult result =
                CanoeTestSupport.render(template, Payloads.QUOTE_SINGLE_BREAKOUT.value());
        assertEquals("<script>x=1;</scriptfoo>", result.output(),
                "the reference contributes nothing at all now: CTX_JS suppresses");
        assertTrue(result.dom().selectFirst("script").data().contains("scriptfoo"),
                () -> "the HTML parser still keeps everything after </scriptfoo> inside the script"
                        + " element - that half never changed. Script data: "
                        + result.dom().selectFirst("script").data());
        assertFalse(result.dom().selectFirst("script").data().contains("&#39;"),
                () -> "...but there is no longer any htmlWhite() output there to be inert about."
                        + " Script data: " + result.dom().selectFirst("script").data());
        assertNotEquals(Payloads.QUOTE_SINGLE_BREAKOUT.value(),
                result.dom().selectFirst("script").data(),
                "and the payload itself certainly does not reach the JavaScript parser");
        assertFalse(result.output().contains("');"),
                "the decoded breakout sequence must not appear anywhere in the bytes either");
    }

    /**
     * The delimiter rule R17 implements, spelled out one character at a time, in both elements.
     *
     * <p>The HTML Standard's script-data-end-tag-name and rawtext-end-tag-name states leave the
     * element only for tab, LF, FF, space, {@code /} or {@code >} (CR reaches the tokenizer as an LF),
     * and treat everything else as character data. This is the row-by-row form of that rule at the
     * level a template author sees: which spellings of a closing tag actually close the block. It is
     * new with R17 because before R17 there was nothing to enumerate — the name alone decided.
     */
    @Test
    public void onlyTheStandardsDelimitersEndTheElement() {
        // Closes.
        assertEquals(Canoe.CTX_HTML, CanoeTestSupport.contextAfter("<script>x=1;</script>"));
        assertEquals(Canoe.CTX_HTML, CanoeTestSupport.contextAfter("<script>x=1;</script >"));
        assertEquals(Canoe.CTX_HTML, CanoeTestSupport.contextAfter("<script>x=1;</script/>"));
        assertEquals(Canoe.CTX_HTML, CanoeTestSupport.contextAfter("<script>x=1;</script\t>"));
        assertEquals(Canoe.CTX_HTML, CanoeTestSupport.contextAfter("<script>x=1;</script\n>"));
        assertEquals(Canoe.CTX_HTML, CanoeTestSupport.contextAfter("<script>x=1;</script\r>"));
        assertEquals(Canoe.CTX_HTML, CanoeTestSupport.contextAfter("<script>x=1;</script\f>"));

        // Does not close.
        assertEquals(Canoe.CTX_JS, CanoeTestSupport.contextAfter("<script>x=1;</scriptfoo>"));
        assertEquals(Canoe.CTX_JS, CanoeTestSupport.contextAfter("<script>x=1;</scriptx"));

        // ...and the rendered proof for the two ends of it: a well-formed end tag lets the paragraph
        // after it have text, a suffixed one does not.
        assertEquals("<script>x=1;</script ><p>harmless</p>", CanoeTestSupport
                .render("<script>x=1;</script ><p>$data</p>", "harmless").output());
        assertEquals("<script>x=1;</scriptfoo><p></p>", CanoeTestSupport
                .render("<script>x=1;</scriptfoo><p>$data</p>", "harmless").output(),
                "still inside the script element, for Canoe and for the browser alike, so the"
                        + " reference is suppressed - fail-closed, and now correct rather than"
                        + " merely fail-closed");

        // The CSS twins, character for character.
        assertEquals(Canoe.CTX_HTML, CanoeTestSupport.contextAfter("<style>a{}</style>"));
        assertEquals(Canoe.CTX_HTML, CanoeTestSupport.contextAfter("<style>a{}</style >"));
        assertEquals(Canoe.CTX_HTML, CanoeTestSupport.contextAfter("<style>a{}</style/>"));
        assertEquals(Canoe.CTX_HTML, CanoeTestSupport.contextAfter("<style>a{}</style\t>"));
        assertEquals(Canoe.CTX_HTML, CanoeTestSupport.contextAfter("<style>a{}</style\n>"));
        assertEquals(Canoe.CTX_HTML, CanoeTestSupport.contextAfter("<style>a{}</style\r>"));
        assertEquals(Canoe.CTX_HTML, CanoeTestSupport.contextAfter("<style>a{}</style\f>"));
        assertEquals(Canoe.CTX_SUPPRESS, CanoeTestSupport.contextAfter("<style>a{}</stylefoo>"));
        assertEquals(Canoe.CTX_SUPPRESS, CanoeTestSupport.contextAfter("<style>a{}</stylex"));
    }

    /**
     * The forward desync's sharpest form, closed. Was
     * {@code afterAForwardDesyncAUrlAttributeIsEncodedWithUrlRatherThanHtmlWhite}: after the desync
     * Canoe was in HTML, so a <em>URL attribute</em> in the template put {@code url()} output — not
     * {@code htmlWhite()} output — into what the browser reads as script data, and {@code url()}'s
     * allowlist is much wider in the JavaScript-significant direction ({@code =}, {@code /},
     * {@code .} and {@code #} all pass naked). It was the shape that would have become live first if
     * the encoders were ever relaxed, and the review's refutation of F10 reasons only about
     * {@code htmlWhite()}, so it was recorded rather than left implicit.
     *
     * <p>The old test also recorded <em>why it was inert anyway</em>, and that reasoning is the part
     * worth keeping in prose: the template's own literal text after the desync — {@code <a href="} —
     * is a JavaScript syntax error, and a syntax error anywhere in a classic script block means the
     * whole block fails to parse. The attacker could not repair it, because repairing it needs a
     * {@code <} or a quote and no encoder emits either. That was a second, weaker guarantee stacked
     * on the first; R17 removes the need for both.
     *
     * <p>What is asserted now: every context after a suffixed end tag is the script body's, whatever
     * markup the template writes there, so no encoder other than the suppressing one can be reached.
     */
    @Test
    public void afterASuffixedEndTagNoAttributeEncoderIsReachableAtAll() {
        assertEquals(Canoe.CTX_JS,
                CanoeTestSupport.contextAfter("<script>x=1;</scriptfoo><a href=\""),
                "R17: Canoe knows it is still in script data, exactly as the browser does, so the"
                        + " template's <a href> is JavaScript source rather than an attribute");
        assertEquals(Canoe.CTX_JS,
                CanoeTestSupport.contextAfter("<script>x=1;</scriptfoo><a title=\""),
                "...and the same for any other attribute name: there is no attribute here");

        String rendered = CanoeTestSupport.render(
                "<script>x=1;</scriptfoo><a href=\"$data\">y</a>", "location=/x/").output();
        assertFalse(rendered.contains("location=/x/"),
                () -> "url()'s path safe set keeps '/' and '=' naked, which is what made this the"
                        + " sharpest form of the desync. url() is not reached now. Rendered: "
                        + rendered);
        assertEquals("<script>x=1;</scriptfoo><a href=\"\">y</a>", rendered,
                "the value is suppressed; the template's own markup is untouched, as always");

        // The browser's side of the agreement, unchanged: all of that is still script source.
        String scriptData = CanoeTestSupport.render(
                        "<script>x=1;</scriptfoo><a href=\"$data\">y</a>", "location=/x/")
                .dom().selectFirst("script").data();
        assertTrue(scriptData.contains("<a href="),
                () -> "the browser reads the template's own markup as JavaScript source - it always"
                        + " did, and that is the half Canoe now agrees with. Script data: "
                        + scriptData);
    }

    // ------------------------------------------------------------------
    // F10, converse: a stray '<' no longer leaves Canoe stuck in SCRIPT
    // ------------------------------------------------------------------

    /**
     * F10's converse desync, closed by R17 and now inverted. Was
     * {@code aStrayLessThanInsideAScriptSuppressesTheRestOfThePage}: {@code <script>x = 1 <</script>}
     * left Canoe in {@code SCRIPT} forever, because {@code SCRIPT_END} mismatched on the second
     * {@code <} and returned to {@code SCRIPT} without re-processing it, so the {@code <} that should
     * have started a fresh {@code SCRIPT_END} was dropped and the real {@code </script>} was never
     * recognised. Every reference for the rest of the page silently became the empty string — and
     * {@code harmless} is an ordinary word, so that was the page losing its content rather than a
     * security suppression. Same shape as F14, which is why the two were worth reading together: a
     * common typo in template literal text put the encoder into a state it could not leave, with no
     * error and no diagnostic, affecting the rest of the document rather than the construct that
     * caused it.
     *
     * <p>R17 sets {@code charNeedsProcessing = true} on the mismatch path — the idiom five other
     * states in {@code reallyProcessChar()} already use — so the character is handed back to
     * {@code SCRIPT} and a {@code <} there opens a fresh end tag.
     */
    @Test
    public void aStrayLessThanInsideAScriptNoLongerSuppressesTheRestOfThePage() {
        assertEquals(Canoe.CTX_HTML,
                CanoeTestSupport.contextAfter("<script>x = 1 <</script><p>"),
                "R17: the second '<' is re-processed, so the real </script> is recognised and a"
                        + " body-context reference after it is a body-context reference");

        CanoeTestSupport.RenderResult result = CanoeTestSupport
                .render("<script>x = 1 <</script><p>$data</p>", "harmless");
        assertEquals("<script>x = 1 <</script><p>harmless</p>", result.output(),
                "the paragraph has its text back");

        // The browser closes the script here, and now so does Canoe: that is the agreement.
        assertEquals("x = 1 <", result.dom().selectFirst("script").data(),
                "every browser ends script data at the first '</script', so the <p> really is a"
                        + " paragraph and really should have text in it");

        // The same shape without the doubled '<': an ordinary comparison in a script body.
        assertEquals("<script>a < b</script><p>harmless</p>",
                CanoeTestSupport.render("<script>a < b</script><p>$data</p>", "harmless").output(),
                "R17: the mismatching ' ' is re-processed too, so an ordinary '<' in the body no"
                        + " longer eats the character after it");
        assertEquals(Canoe.CTX_HTML,
                CanoeTestSupport.contextAfter("<script>a < b</script><p>"));

        // ...and the control that always worked.
        assertEquals("<script>x = 1;</script><p>harmless</p>",
                CanoeTestSupport.render("<script>x = 1;</script><p>$data</p>", "harmless").output(),
                "the identical page with no stray '<' at all");
    }

    // ------------------------------------------------------------------
    // The CSS twins
    // ------------------------------------------------------------------

    /**
     * {@code CSS_END} had both defects identically, with {@code /style} in place of {@code /script},
     * and R17 fixed both identically. The test keeps its name and inverts its claims.
     *
     * <p>Asserted rather than assumed, for the same reason it always was. "The code is identical" is
     * a claim about {@code Canoe.java}, and {@code CanoeStateMachineTest} is where source-shaped
     * claims live; this is a claim about behaviour, and the two states differ in one respect that
     * could plausibly have changed the answer — {@code SCRIPT} produces {@code CTX_JS} and
     * {@code CSS} produces {@code CTX_SUPPRESS} (there is no CTX_CSS; R14/F21), so the observable
     * effect of the fix in one is not the observable effect of the fix in the other: the script twin
     * moves from wrongly-HTML to suppressed, and the style twin from wrongly-HTML to suppressed by a
     * different route. What used to be asserted here: {@code </stylefoo>} returned Canoe to HTML,
     * and {@code <style>a{} <</style>} left it stuck in {@code CSS} with the rest of the page empty.
     */
    @Test
    public void bothDesyncsHaveExactCssTwins() {
        // Forward.
        assertEquals(Canoe.CTX_SUPPRESS, CanoeTestSupport.contextAfter("<style>a{}</stylefoo>"),
                "R17: CSS_END_NAME requires the same delimiter SCRIPT_END_NAME does, so"
                        + " '</stylefoo>' closes nothing and the style body still suppresses");
        CanoeTestSupport.RenderResult forward =
                CanoeTestSupport.render("<style>a{}</stylefoo>$data", Payloads.CSS_IMPORT.value());
        assertEquals("<style>a{}</stylefoo>", forward.output(),
                "the reference contributes nothing: it is inside the style element for Canoe as"
                        + " well as for the browser now");
        assertTrue(forward.dom().selectFirst("style").data().contains("stylefoo"),
                () -> "and the parser keeps the rest inside the style element, as it always did."
                        + " Style data: " + forward.dom().selectFirst("style").data());
        assertFalse(forward.dom().selectFirst("style").data().contains("@import url(//"),
                "the payload is not there in any form - it used to be there as inert entity-encoded"
                        + " raw text, safe for the RAWTEXT reason, and now it is simply absent");

        // Converse.
        assertEquals(Canoe.CTX_HTML, CanoeTestSupport.contextAfter("<style>a{} <</style><p>"),
                "R17: the mismatching '<' is re-processed, so </style> is recognised");
        assertEquals("<style>a{} <</style><p>harmless</p>",
                CanoeTestSupport.render("<style>a{} <</style><p>$data</p>", "harmless").output(),
                "the paragraph after a stray '<' in a style body has its text back");
    }

    // ------------------------------------------------------------------
    // The precondition F10's rating depends on
    // ------------------------------------------------------------------

    /**
     * A desync is a property of the template and never of the payload.
     *
     * <p>This is F10's whole reachability argument, stated locally: every payload in the catalogue,
     * through every desync template in this file plus their well-formed controls, must leave Canoe in
     * exactly the state the inert marker leaves it in. If a payload could move the state machine it
     * could create a desync itself, and F10 would have stopped being latent.
     *
     * <p><strong>R17 changes nothing here and the test passes unchanged</strong>, which is the point
     * of listing it in R17's brief: the argument is about what an encoder can emit, not about how
     * these states transition. No encoder can emit a raw {@code <}, so no payload reaches
     * {@code SCRIPT_END} or {@code CSS_END} whichever rule they apply once they are there. The
     * templates below still include the two desync shapes, now as shapes that no longer desync.
     *
     * <p>The general form is {@code ParserSteeringTest} (T23), quantified over the whole corpus rather
     * than over this file's nine templates, and it is the test that has to be re-run before any CSS
     * or JavaScript encoder is wired into the suppressed routes. This is the statement of the property
     * in the {@code <script>}/{@code <style>} states specifically, which is where it matters most:
     * those are the states an encoder relaxation changes.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("everyPayload")
    public void onlyTemplateTextCanCauseADesync(Payload payload) {
        for (String template : List.of(
                "<script>var x = '$data';</script><p>after</p>",
                "<script>$data</script><p>after</p>",
                "<script>x=1;</scriptfoo>$data",
                "<script>x = 1 <</script><p>$data</p>",
                "<style>p{color:$data}</style><p>after</p>",
                "<style>a{}</stylefoo>$data",
                "<style>a{} <</style><p>$data</p>",
                // R17's shapes: a delimited end tag and one with a suffix, in a template that
                // continues afterwards.
                "<script>x=1;</script ><p>$data</p>",
                "<script>x=1;</scriptfoo><a href=\"$data\">y</a>")) {
            CanoeTestSupport.RenderResult attacked =
                    CanoeTestSupport.render(template, payload.value());
            CanoeTestSupport.RenderResult benign =
                    CanoeTestSupport.render(template, Payloads.INERT_MARKER.value());

            assertEquals(benign.isError(), attacked.isError(),
                    () -> payload.id() + " changed whether Canoe rejected " + template);
            assertEquals(benign.context(), attacked.context(),
                    () -> "F10 is latent because attacker data cannot move the state machine, and "
                            + payload.id() + " moved it: " + template
                            + "\n  benign   : " + CanoeTestSupport.contextName(benign.context())
                            + "\n  attacked : " + CanoeTestSupport.contextName(attacked.context())
                            + "\nIf this is real, F10 is no longer Low (latent) and"
                            + " ParserSteeringTest (T23) needs writing before anything else.");
            String templateOnly = CanoeTestSupport.render(template, "").output();
            assertEquals(count(templateOnly, '<'), count(attacked.output(), '<'),
                    () -> payload.id() + " contributed a '<' to " + template + ", which is the"
                            + " single character F10's unexploitability rests on. Only the template"
                            + " may write one.\n  template : "
                            + CanoeTestSupport.quote(templateOnly)
                            + "\n  attacked : " + CanoeTestSupport.quote(attacked.output()));
        }
    }

    static List<Payload> everyPayload() {
        return Payloads.all();
    }

    private static long count(String haystack, char needle) {
        return haystack.chars().filter(c -> c == needle).count();
    }

    /**
     * The corpus's four F10 rows, and what each one records. Was
     * {@code theFourDesyncRowsRecordAvailabilityAndNotInjection}, and it recorded that two of them
     * were {@code SAFE} and two {@code SUPPRESSED_UNINTENDED} — the finding cost availability and
     * nothing else, and every one of the four would have flipped the moment {@code CTX_JS} or the
     * suppressed {@code style} route stopped being the empty string.
     *
     * <p>After R17 <strong>no F10 row records a defect at all</strong>, which is the stronger claim
     * this test now makes. The two {@code SUPPRESSED_UNINTENDED} rows — the converse desyncs, where
     * the page silently lost its content — are {@code SAFE}: the end tag is recognised, the reference
     * renders in the text context after it and {@code html()} escapes it. The two {@code SAFE} rows —
     * the suffixed end tags, safe because script and style data are not entity-decoded — are
     * {@code SUPPRESSED_BY_DESIGN}: Canoe now agrees with the browser that the reference is inside the
     * element body, and an element body suppresses. Two rows swapped verdicts in opposite directions
     * and the defect count went to zero, which is what closing a two-directional desync looks like.
     *
     * <p>The verdicts were never {@code KNOWN_VULNERABLE} and still are not. Recording them that way
     * would say attacker data reaches a sink live (&sect;2.1's definition), and it does not: the
     * desync was created by template literal text and the payload arrived inert either way. It would
     * also make {@code CanoeCorpusTest.ledgerMatchesObservedBehaviour} fail immediately, which is the
     * ledger doing its job. F10's citation stays on the rows because the row exists <em>because of</em>
     * the finding, not because the row is an exploit — the same convention F14's {@code SAFE} row
     * follows since R16.
     */
    @Test
    public void theFourDesyncRowsRecordNoDefectAtAll() {
        List<XssCase> f10 = CanoeCorpus.forFinding("F10");
        assertEquals(4, f10.size(),
                () -> "F10 should have four corpus rows - the script desync, the CSS desync, and"
                        + " both converses: " + f10);

        for (XssCase testCase : f10) {
            for (Payload payload : testCase.payloads()) {
                // Either live verdict is a claim that F10 is live; ACCEPTED_RESIDUAL would be one
                // too, and would slip past a test that only excluded KNOWN_VULNERABLE.
                assertFalse(testCase.verdictFor(payload).reachesSinkLive(),
                        () -> testCase.id() + " / " + payload.id() + " claims F10 is live. It is"
                                + " not, and the reason is the corollary the whole review rests on:"
                                + " attacker data can never emit a raw '<', so it can never enter"
                                + " SCRIPT_END or CSS_END. If a payload ever can, that is a finding"
                                + " about the encoders and ParserSteeringTest (T23) is where it"
                                + " shows up - not a verdict change here.");
                assertFalse(testCase.verdictFor(payload).isDefect(),
                        () -> testCase.id() + " / " + payload.id() + " is "
                                + testCase.verdictFor(payload) + ". R17 closed both directions of"
                                + " F10, so none of these four rows should record a defect any"
                                + " more: the suffixed-end-tag rows are suppressions inside the"
                                + " element body and the converse rows render their reference in the"
                                + " text context after a correctly recognised end tag. A defect here"
                                + " means one of the two halves regressed.");
            }
        }
    }
}
