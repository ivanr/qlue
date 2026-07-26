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
 * <em>divergence</em>, by putting the same string through Canoe's state machine and through a real
 * HTML parser and requiring the two to disagree:
 *
 * <ul>
 *   <li><strong>Forward desync.</strong> {@code SCRIPT_END} matches the seven characters
 *       {@code /script} and sets {@code state = TAG} with no check that what follows is whitespace,
 *       {@code /} or {@code >} ({@code Canoe.java:947-958}). So {@code </scriptfoo>} returns Canoe to
 *       HTML while the browser's script-data-end-tag-name state keeps it in script data. Canoe then
 *       encodes the rest of the page for contexts that do not exist there — including, if a URL
 *       attribute follows, {@code CTX_URI}.
 *   <li><strong>Converse desync.</strong> {@code <script>x = 1 <</script>} leaves Canoe stuck in
 *       {@code SCRIPT}: {@code SCRIPT_END} mismatches on the second {@code <} and returns to
 *       {@code SCRIPT} <em>without re-processing that character</em>, so the real {@code </script>} is
 *       never seen and every reference for the rest of the page silently becomes the empty string.
 *       Same shape as F14, different state.
 *   <li>{@code CSS_END} ({@code Canoe.java:967-978}) has both defects identically with {@code /style},
 *       and the twins are asserted rather than assumed, because "identical code" is a claim about the
 *       source and this is a claim about behaviour.
 * </ul>
 *
 * <h2>The precondition, and why it is not asserted here</h2>
 *
 * <p>F10 is Low (latent) rather than exploitable for exactly one reason: <strong>attacker data can
 * never emit a raw {@code <}</strong>, so only template literal text can enter {@code SCRIPT_END}.
 * {@link #onlyTemplateTextCanCauseADesync} states that over this file's own templates and the whole
 * payload catalogue, which is the local form of the claim.
 *
 * <p>The general form belongs to <strong>{@code ParserSteeringTest} (T23)</strong>, which is not
 * written yet: the property that for every corpus template, the sequence of {@code currentContext()}
 * values observed at each reference position is identical whether the reference value is the inert
 * marker or any payload in the catalogue. That is the property F10's unexploitability rests on, and
 * the review asks explicitly that any relaxation of the encoders — the commented-out
 * {@code HtmlEncoder.js()} and {@code HtmlEncoder.css()} at {@code Canoe.java:1074-1081} — be checked
 * against it before it lands. If T23 ever fails, every row in this file needs re-rating and F10 stops
 * being latent.
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
     * <p>This is the row that flips when {@code Canoe.java:1074-1081} is uncommented, and it should:
     * the day {@code CTX_JS} stops meaning the empty string, every one of these becomes a claim about
     * {@code HtmlEncoder.js()} instead, and per F16 that encoder truncates astral code points to their
     * low sixteen bits — {@code U+10027} silently becomes an apostrophe, inside the string literal
     * these cases are testing. The failure is the point.
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
     * The four states involved, named, so that a failure above says which one changed.
     *
     * <p>Note that {@code <style>} produces {@code CTX_SUPPRESS} and not {@code CTX_CSS}. That is
     * <strong>F21</strong> and it is not a hole in the switch — the {@code CSS}/{@code CSS_END} states
     * simply have no {@code CTX_CSS} arm, and nothing anywhere in {@code currentContext()} does. Both
     * constants encode to the empty string today, which is why it is latent;
     * {@code AttributeNameMatrixTest.currentContextCanNeverReturnCtxCss} owns the finding.
     */
    @Test
    public void theFourStatesAScriptOrStyleBodyCanBeIn() {
        assertEquals(Canoe.CTX_JS, CanoeTestSupport.contextAfter("<script>"),
                "SCRIPT");
        assertEquals(Canoe.CTX_JS, CanoeTestSupport.contextAfter("<script>x</scr"),
                "SCRIPT_END, partway through the seven characters it matches");
        assertEquals(Canoe.CTX_SUPPRESS, CanoeTestSupport.contextAfter("<style>"),
                "CSS - and CTX_SUPPRESS rather than CTX_CSS, which is F21");
        assertEquals(Canoe.CTX_SUPPRESS, CanoeTestSupport.contextAfter("<style>a{}</sty"),
                "CSS_END");

        assertEquals(Canoe.CTX_HTML, CanoeTestSupport.contextAfter("<script>x=1;</script><p>"),
                "...and a well-formed end tag returns the machine to HTML, which is the assertion"
                        + " the two desyncs below are measured against");
        assertEquals(Canoe.CTX_HTML, CanoeTestSupport.contextAfter("<script>x=1;</SCRIPT><p>"),
                "SCRIPT_END lowercases as it compares (Canoe.java:948), so an uppercase end tag"
                        + " closes correctly. Worth pinning: a case-sensitive comparison here would"
                        + " be the converse desync reached by ordinary markup rather than by a stray"
                        + " '<', and that WOULD be a new finding.");
    }

    // ------------------------------------------------------------------
    // F10, forward: </scriptfoo> is accepted as a terminator
    // ------------------------------------------------------------------

    /**
     * F10's forward desync, asserted as a disagreement between two parsers rather than as a fact
     * about one.
     *
     * <p>Canoe believes {@code </scriptfoo>} closed the script and encodes what follows for
     * {@code CTX_HTML}. A real HTML parser does not: per the script-data-end-tag-name state, an end
     * tag is only appropriate when the tag name is followed by whitespace, {@code /} or {@code >}, so
     * {@code </scriptfoo>} is emitted as character data and the tokenizer stays in script data. The
     * assertion is that jsoup — which implements that state — still has the following text inside the
     * {@code <script>} element.
     */
    @Test
    public void aScriptEndTagWithASuffixClosesTheScriptForCanoeAndNotForTheParser() {
        String template = "<script>x=1;</scriptfoo>$data";

        assertEquals(Canoe.CTX_HTML, CanoeTestSupport.contextAfter("<script>x=1;</scriptfoo>"),
                "F10: SCRIPT_END matches the seven characters '/script' and immediately sets"
                        + " state = TAG with no check on what follows (Canoe.java:947-958)");

        CanoeTestSupport.RenderResult result =
                CanoeTestSupport.render(template, Payloads.QUOTE_SINGLE_BREAKOUT.value());
        assertTrue(result.dom().selectFirst("script").data().contains("scriptfoo"),
                () -> "F10: the HTML parser keeps everything after </scriptfoo> inside the script"
                        + " element, which is precisely what Canoe stopped believing. Script data: "
                        + result.dom().selectFirst("script").data());
        assertTrue(result.dom().selectFirst("script").data().contains("&#39;"),
                () -> "...and the reference Canoe encoded for CTX_HTML lands there as htmlWhite()"
                        + " output. Script data: " + result.dom().selectFirst("script").data());

        // Why this is still not an injection, stated as an assertion rather than as prose. Character
        // references are NOT decoded inside script data, so &#39; is the five literal characters
        // '&', '#', '3', '9', ';' to the JavaScript parser - a syntax error, not a closed string
        // literal. And </scriptfoo> is itself already a syntax error, so the block never runs at all.
        assertNotEquals(Payloads.QUOTE_SINGLE_BREAKOUT.value(),
                result.dom().selectFirst("script").data(),
                "F10 is latent, not exploitable: script data is raw text, so the parser does not"
                        + " decode htmlWhite()'s character references and the attacker's apostrophe"
                        + " never becomes an apostrophe");
        assertFalse(result.output().contains("');"),
                "the decoded breakout sequence must not appear anywhere in the bytes either");
    }

    /**
     * The forward desync's sharpest form, and the one the review's refutation does not literally
     * cover: after the desync Canoe is in HTML, so a <em>URL attribute</em> in the template puts
     * {@code url()} output — not {@code htmlWhite()} output — into what the browser reads as script
     * data, and {@code url()}'s allowlist is much wider in the JavaScript-significant direction
     * ({@code =}, {@code /}, {@code .} and {@code #} all pass naked).
     *
     * <p>It is still not exploitable, and the reason is worth having as an assertion because it is a
     * different reason from the one F10 gives: the template's own literal text after the desync —
     * {@code <a href="} — is itself a JavaScript syntax error, and a syntax error anywhere in a
     * classic script block means the <strong>whole block</strong> fails to parse and nothing in it
     * runs. The attacker cannot repair it, because repairing it needs a {@code <} or a quote and no
     * encoder emits either.
     *
     * <p>Recorded rather than left implicit because it is the shape that would become live first if
     * the encoders were ever relaxed, and because F10's stated refutation reasons only about
     * {@code htmlWhite()}.
     */
    @Test
    public void afterAForwardDesyncAUrlAttributeIsEncodedWithUrlRatherThanHtmlWhite() {
        assertEquals(Canoe.CTX_URI,
                CanoeTestSupport.contextAfter("<script>x=1;</scriptfoo><a href=\""),
                "F10: Canoe thinks it is in an <a href> attribute; the browser thinks it is in"
                        + " script data");
        assertEquals(Canoe.CTX_HTML_ATTR,
                CanoeTestSupport.contextAfter("<script>x=1;</scriptfoo><a title=\""),
                "...and in an ordinary attribute for any other name, which is html() rather than"
                        + " htmlWhite() - a third encoder reaching the same desynced position");

        String rendered = CanoeTestSupport.render(
                "<script>x=1;</scriptfoo><a href=\"$data\">y</a>", "location=/x/").output();
        assertTrue(rendered.contains("location=/x/"),
                () -> "url()'s allowlist is a-zA-Z0-9 / . - # ? = , so these twelve characters pass"
                        + " through naked where htmlWhite() would have escaped the '=' and the '/'."
                        + " Rendered: " + rendered);

        // ...and the reason it is inert anyway.
        String scriptData = CanoeTestSupport.render(
                        "<script>x=1;</scriptfoo><a href=\"$data\">y</a>", "location=/x/")
                .dom().selectFirst("script").data();
        assertTrue(scriptData.contains("<a href="),
                () -> "the browser reads the template's own markup as JavaScript source, and '<a' is"
                        + " a syntax error that kills the whole block before the payload is reached."
                        + " No payload can repair it: doing so needs a '<' or a quote, and neither"
                        + " html(), htmlWhite() nor url() can emit one. Script data: " + scriptData);
    }

    // ------------------------------------------------------------------
    // F10, converse: a stray '<' leaves Canoe stuck in SCRIPT
    // ------------------------------------------------------------------

    /**
     * F10's converse desync: {@code <script>x = 1 <</script>} leaves Canoe in {@code SCRIPT} forever.
     *
     * <p>{@code SCRIPT_END} mismatches on the second {@code <} and returns to {@code SCRIPT} without
     * re-processing it, so the {@code <} that should have started a fresh {@code SCRIPT_END} is
     * dropped and the real {@code </script>} is never recognised. Every reference for the rest of the
     * page silently becomes the empty string.
     *
     * <p>Fail-closed, so an availability defect rather than a vulnerability — and the same shape as
     * F14, which is why the two are worth reading together: a common typo in template literal text
     * puts the encoder into a state it cannot leave, with no error and no diagnostic, affecting the
     * rest of the document rather than the construct that caused it.
     */
    @Test
    public void aStrayLessThanInsideAScriptSuppressesTheRestOfThePage() {
        assertEquals(Canoe.CTX_JS,
                CanoeTestSupport.contextAfter("<script>x = 1 <</script><p>"),
                "F10 converse: Canoe never left SCRIPT, so a body-context reference three elements"
                        + " later is still encoded as if it were inside the script");

        CanoeTestSupport.RenderResult result = CanoeTestSupport
                .render("<script>x = 1 <</script><p>$data</p>", "harmless");
        assertEquals("<script>x = 1 <</script><p></p>", result.output(),
                "the paragraph is empty - and 'harmless' is an ordinary word, so this is not a"
                        + " security suppression, it is the page losing its content");

        // The browser does close the script, which is the divergence.
        assertEquals("x = 1 <", result.dom().selectFirst("script").data(),
                "every browser ends script data at the first '</script', so the <p> really is a"
                        + " paragraph and really should have had text in it");

        // ...and the contrast, one character away.
        assertEquals("<script>x = 1;</script><p>harmless</p>",
                CanoeTestSupport.render("<script>x = 1;</script><p>$data</p>", "harmless").output(),
                "remove the stray '<' and the identical page renders correctly");
    }

    // ------------------------------------------------------------------
    // The CSS twins
    // ------------------------------------------------------------------

    /**
     * {@code CSS_END} has both defects identically, with {@code /style} in place of {@code /script}.
     *
     * <p>Asserted rather than assumed. "The code is identical" is a claim about {@code Canoe.java},
     * and {@code CanoeStateMachineTest} is where source-shaped claims live; this is a claim about
     * behaviour, and the two states differ in one respect that could plausibly have changed the
     * answer — {@code SCRIPT} produces {@code CTX_JS} and {@code CSS} produces {@code CTX_SUPPRESS}
     * (F21), so the observable effect of being stuck in one is not the observable effect of being
     * stuck in the other.
     */
    @Test
    public void bothDesyncsHaveExactCssTwins() {
        // Forward.
        assertEquals(Canoe.CTX_HTML, CanoeTestSupport.contextAfter("<style>a{}</stylefoo>"),
                "F10: CSS_END accepts '/style' followed by anything");
        CanoeTestSupport.RenderResult forward =
                CanoeTestSupport.render("<style>a{}</stylefoo>$data", Payloads.CSS_IMPORT.value());
        assertTrue(forward.dom().selectFirst("style").data().contains("stylefoo"),
                () -> "and the parser keeps the rest inside the style element. Style data: "
                        + forward.dom().selectFirst("style").data());
        assertFalse(forward.dom().selectFirst("style").data().contains("@import url(//"),
                "safe for the RAWTEXT reason: a style element's content is raw text, so the browser"
                        + " never decodes htmlWhite()'s character references and the payload is inert"
                        + " text rather than an @import rule");

        // Converse.
        assertEquals(Canoe.CTX_SUPPRESS, CanoeTestSupport.contextAfter("<style>a{} <</style><p>"),
                "F10 converse: stuck in CSS, so the rest of the page is suppressed");
        assertEquals("<style>a{} <</style><p></p>",
                CanoeTestSupport.render("<style>a{} <</style><p>$data</p>", "harmless").output());
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
     * could create the desync itself, and F10 would stop being latent.
     *
     * <p>The general form is {@code ParserSteeringTest} (T23), quantified over the whole corpus rather
     * than over this file's six templates, and it is the test that has to be re-run before the
     * commented-out encoders at {@code Canoe.java:1074-1081} are enabled. Until T23 exists this is the
     * only executable statement of the property in the {@code <script>}/{@code <style>} states
     * specifically, which is where it matters most: those are the states an encoder relaxation
     * changes.
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
                "<style>a{} <</style><p>$data</p>")) {
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
     * The corpus's four F10 rows, and what each one records.
     *
     * <p>Read as a group they are the reason F10 is worth keeping in the review at Low rather than
     * closing: two of them are {@code SAFE} and two are {@code SUPPRESSED_UNINTENDED}, so today the
     * finding costs availability and nothing else — and every one of the four would flip the moment
     * {@code CTX_JS} or {@code CTX_CSS} stopped being the empty string, which is what the remediation
     * path contemplates.
     *
     * <p>The verdicts are deliberately <em>not</em> {@code KNOWN_VULNERABLE}. Recording them that way
     * would say attacker data reaches a sink live (&sect;2.1's definition), and it does not: the
     * desync is created by template literal text and the payload arrives inert either way. It would
     * also make {@code CanoeCorpusTest.ledgerMatchesObservedBehaviour} fail immediately, which is the
     * ledger doing its job. F10's citation is on the rows because the row exists <em>because of</em>
     * the finding, not because the row is an exploit.
     */
    @Test
    public void theFourDesyncRowsRecordAvailabilityAndNotInjection() {
        List<XssCase> f10 = CanoeCorpus.forFinding("F10");
        assertEquals(4, f10.size(),
                () -> "F10 should have four corpus rows - the script desync, the CSS desync, and"
                        + " both converses: " + f10);

        for (XssCase testCase : f10) {
            for (Payload payload : testCase.payloads()) {
                assertNotEquals(Verdict.KNOWN_VULNERABLE, testCase.verdictFor(payload),
                        () -> testCase.id() + " / " + payload.id() + " claims F10 is live. It is"
                                + " not, and the reason is the corollary the whole review rests on:"
                                + " attacker data can never emit a raw '<', so it can never enter"
                                + " SCRIPT_END or CSS_END. If a payload ever can, that is a finding"
                                + " about the encoders and ParserSteeringTest (T23) is where it"
                                + " shows up - not a verdict change here.");
            }
        }
    }
}
