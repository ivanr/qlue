package com.webkreator.qlue.view.canoe.velocity;

import com.webkreator.qlue.view.Canoe;
import com.webkreator.qlue.view.CanoeStateProbe;
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
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The CSS contexts, and the one character that decides between them.
 *
 * <p>Canoe's stated centrepiece is that it refuses to output into CSS at all: {@code style} resolves
 * to {@code ATTR_CSS}, which produces {@code CTX_SUPPRESS}, which is the empty string. F4 was that
 * this guarantee could be defeated by writing a CSS property in front of the reference — the thing
 * every real {@code style} attribute does. {@code detectAttributePrefix()} fired on the first colon
 * at value index 0 through 10 and unconditionally reset {@code attributeContext} to
 * {@code ATTR_HTML} ({@code Canoe.java:224}); no prefix matched; {@code html()} took over; and the
 * HTML parser decoded every character reference before the CSS parser saw the value.
 *
 * <p><strong>R2 deleted that line, and this file is inverted rather than deleted.</strong> The
 * method may now only narrow the context, so the colon index has stopped deciding anything and every
 * assertion below is the corresponding claim about its absence. The tests are kept because they are
 * the regression net for F4: they are the only place that says the outcome must be a function of the
 * attribute name alone, and the only place that would notice a positional dependence coming back in
 * a different shape.
 *
 * <h2>What this file asserts that {@code CanoeCorpusTest} does not</h2>
 *
 * <ul>
 *   <li><strong>The absence of the boundary as a function, at the Velocity level.</strong>
 *       {@code AttributePrefixTest} (T10) pins the colon index 0-12 against a bare {@code Canoe};
 *       the corpus holds eleven {@code css.*} rows with individually reviewed verdicts. Neither says
 *       that the outcome is <em>independent of the index of the first colon</em>.
 *       {@link #thePropertyNameDecidesWhetherStyleIsSuppressed} parameterises the whole set of
 *       property names F4 listed, asserts that each one's colon index is still what the finding
 *       claimed, and then requires every index to reach the same answer — so {@code background:} and
 *       {@code font-family:}, which the finding put on opposite sides at 10 and 11, must now agree.
 *       A per-template ledger records eleven answers; this records the rule that generates them.
 *   <li><strong>The positions the reset never reached.</strong> A colon inside a {@code <style>}
 *       element body, inside an {@code @media} block, or anywhere past index 10 is just a character,
 *       because the CSS states have no value-prefix scan at all. That asymmetry —
 *       {@code color:$x} suppressed in a stylesheet and injectable in an attribute, from templates a
 *       developer would call equivalent — was the shape of F4 that a list of vulnerable rows hid,
 *       and it is now the pair of templates that must render the same thing.
 * </ul>
 *
 * <p>Note the level. This file renders through Velocity and asserts on the jsoup-decoded attribute
 * value, which is what the CSS parser receives; {@code AttributePrefixTest} asserts the same boundary
 * against {@code currentContext()} with no Velocity involved. Both are needed, because a correct
 * context that the reference-insertion handler never consults would leave the unit test green and the
 * page injectable.
 */
public class CssContextTest {

    /** The Appendix A section the CSS and value-prefix cases are filed under. */
    private static final String SECTION = "A.4 attribute value prefixes";

    /**
     * Every property name F4's precondition paragraph names, with the index its colon lands on, and
     * whether that index reaches {@code detectAttributePrefix()} at all.
     *
     * <p>The index is asserted rather than trusted: it was the whole precondition, and the review
     * corrected itself on it once already (the adversarial pass placed the cutoff one character
     * earlier and concluded {@code background:} was safe, which it was not — {@code c == ':'} is
     * tested before the {@code bufLen == 10} cutoff at {@code Canoe.java:924}). The third column is
     * kept, and is now the column that must <strong>not</strong> change the answer.
     */
    static Stream<Arguments> cssProperties() {
        return Stream.of(
                Arguments.of("color", 5, true),
                Arguments.of("width", 5, true),
                Arguments.of("margin", 6, true),
                Arguments.of("padding", 7, true),
                Arguments.of("display", 7, true),
                Arguments.of("position", 8, true),
                Arguments.of("font-size", 9, true),
                Arguments.of("background", 10, true),
                Arguments.of("font-family", 11, false),
                Arguments.of("text-decoration", 15, false));
    }

    /**
     * The finding's absence, as a function of one integer. Inverted by R2; the method kept its name
     * because the claim it makes is still "what the property name does or does not decide", and the
     * answer is now "nothing".
     *
     * <p>Three things are asserted together per row, and the point is that they are together: the
     * colon's index, the context Canoe ends up in, and what the CSS parser is actually handed. A test
     * of the context alone would pass if the reference-insertion handler stopped consulting it; a test
     * of the decoded value alone would not say why. The {@code reachesTheScan} column is no longer
     * allowed to influence either of the last two — that independence is the fix, and asserting it
     * per row is what would catch a positional dependence reappearing in some other form.
     */
    @ParameterizedTest(name = "{0}: (colon at {1})")
    @MethodSource("cssProperties")
    public void thePropertyNameDecidesWhetherStyleIsSuppressed(String property, int colonIndex,
                                                               boolean reachesTheScan) {
        assertEquals(colonIndex, property.length(),
                property + ": F4's precondition was the index of the colon in the attribute VALUE,"
                        + " which for 'name:' is the length of the name. If this row is wrong the"
                        + " whole table is measuring something else.");

        String template = "<div style=\"" + property + ":$data\">x</div>";
        String payload = Payloads.CSS_URL_BEACON.value();

        int context = CanoeTestSupport.contextAfter("<div style=\"" + property + ":");
        assertEquals(Canoe.CTX_SUPPRESS, context,
                () -> "R2: a colon at index " + colonIndex + " "
                        + (reachesTheScan
                                ? "fires detectAttributePrefix(), which matches none of its five"
                                        + " prefixes and must therefore leave attributeContext alone"
                                : "does not reach detectAttributePrefix() at all, because bufLen was"
                                        + " set to -1 at index 10")
                        + " - either way the name-derived ATTR_CSS stands. Observed "
                        + CanoeTestSupport.contextName(context));

        String decoded = CanoeTestSupport.render(template, payload).decodedAttr("div", "style");
        assertEquals(property + ":", decoded,
                "the reference contributed nothing at all, which is the design working. Before R2"
                        + " the eight rows whose colon reaches the scan produced " + property + ":"
                        + payload + " instead, because the HTML parser decodes html()'s character"
                        + " references while building the attribute value and the CSS parser then"
                        + " received the attacker's declarations verbatim.");
    }

    /**
     * The two templates the finding was really about, side by side. Inverted by R2; was
     * {@code aBareStyleAttributeIsSuppressedAndOneWithAPropertyIsNot}.
     *
     * <p>{@code <div style="$c">} was suppressed and {@code <div style="color:$c">} was injectable,
     * and no template author would call those different. The pair was the shortest statement of F4,
     * and it is the shortest statement of the fix for the same reason: the six characters of literal
     * template text have stopped mattering.
     */
    @Test
    public void aBareStyleAttributeAndOneWithAPropertyAreBothSuppressed() {
        String payload = Payloads.CSS_OVERLAY.value();

        assertEquals("<div style=\"\">x</div>",
                CanoeTestSupport.render("<div style=\"$data\">x</div>", payload).output(),
                "the design working: ATTR_CSS survives, CTX_SUPPRESS applies, nothing is emitted");

        assertEquals("<div style=\"color:\">x</div>",
                CanoeTestSupport.render("<div style=\"color:$data\">x</div>", payload).output(),
                "R2: six characters of literal template text used to convert 'refuse to output into"
                        + " CSS' into 'HTML-encode and let the parser undo it'; they now convert"
                        + " nothing");

        String withProperty = CanoeTestSupport.render("<div style=\"color:$data\">x</div>", payload)
                .decodedAttr("div", "style");
        assertEquals("color:", withProperty,
                "and the CSS parser is handed a property name with no value - not the full-viewport"
                        + " clickjacking overlay with a beacon in it that this used to produce");
        assertFalse(withProperty.contains("position:fixed") || withProperty.contains("url(//"),
                () -> "no declaration of the attacker's may survive. Got: " + withProperty);
    }

    /**
     * Only the <em>first</em> colon is examined, and after R2 that is a fact about the scan rather
     * than about the outcome: a complete declaration in front of the reference, a reference deep in
     * a long value, and a value whose first colon is past the window all reach the same place.
     *
     * <p>{@code detectAttributePrefix()} runs once and sets {@code bufLen} to -1, so nothing later in
     * the value is examined. The reference's own position was irrelevant before and is irrelevant
     * now; what has changed is that the first colon's position is too.
     */
    @Test
    public void onlyTheFirstColonIsEverExamined() {
        assertEquals(Canoe.CTX_SUPPRESS,
                CanoeTestSupport.contextAfter("<div style=\"color:red;background:"),
                "R2: the scan fires on the colon of 'color:' at index 5, matches nothing, and gives"
                        + " up; the second declaration is never looked at and ATTR_CSS stands");
        assertEquals(Canoe.CTX_SUPPRESS,
                CanoeTestSupport.contextAfter(
                        "<div style=\"color:red;text-decoration:underline;font-family:"),
                "...however far into the value the reference eventually sits");

        // ...and the case that was already suppressed, which is now indistinguishable from the two
        // above rather than being the only safe one of the three.
        assertEquals(Canoe.CTX_SUPPRESS,
                CanoeTestSupport.contextAfter("<div style=\"text-decoration:underline;color:"),
                "the first colon is at index 15, so the scan never ran at all");
    }

    /**
     * A CSS string literal around the reference was never a mitigation, and a template author is
     * likely to think it is. Inverted by R2; was {@code aQuotedCssStringIsNotAContainer}.
     *
     * <p>{@code html()} turned the apostrophe into {@code &#39;} and the HTML parser gave it back as
     * a real quote before the CSS parser ran — the identical mechanism to F1's JavaScript string
     * literal. The comparison that made it land was {@code content:'$x'} against
     * {@code font-family:'$x'}: both quoted CSS strings, one injectable and one not, with the only
     * difference being that the second property name is four characters longer. The pair is kept,
     * because the assertion worth having now is that they are the same — and it is still true, and
     * still worth saying, that the quoting is not what makes either of them safe.
     */
    @Test
    public void aQuotedCssStringIsStillNotAContainerAndNoLongerNeedsToBe() {
        String payload = Payloads.CSS_URL_BEACON.value();

        String content = CanoeTestSupport
                .render("<div style=\"content:'$data'\">x</div>", payload).decodedAttr("div", "style");
        assertEquals("content:''", content,
                "R2: colon at index 7, the scan runs and matches nothing, ATTR_CSS survives and the"
                        + " string literal the template wrote is empty. It used to read content:'"
                        + payload + "' - and the quote was no container, because the payload's own"
                        + " ';' closed the declaration");

        String fontFamily = CanoeTestSupport
                .render("<div style=\"font-family:'$data'\">x</div>", payload)
                .decodedAttr("div", "style");
        assertEquals("font-family:''", fontFamily,
                "the same shape with a longer property name: colon at index 11, the scan has already"
                        + " given up, ATTR_CSS survives and nothing is emitted");

        assertEquals(content.substring(content.indexOf(':')),
                fontFamily.substring(fontFamily.indexOf(':')),
                "two quoted CSS strings, the same outcome - the length of the property name in front"
                        + " of them decides nothing any more");
    }

    /**
     * A reference inside a CSS {@code url()}, which was F4's concrete impact in one template: an
     * attacker-chosen URL fetched on every render, which is how CSS exfiltration of DOM content is
     * bootstrapped. Inverted by R2; was
     * {@code aReferenceInsideACssUrlFunctionReachesAnAttackerOrigin}.
     *
     * <p>Note what was never happening here: no URL encoder was involved. The attribute is
     * {@code style}, so once the reset had fired the value went through {@code html()}, not through
     * {@code url()} — the encoder that at least escapes a colon. That is why the second half of this
     * test is kept unchanged: it is the measurement that says routing {@code style} through
     * {@code url()} would <em>not</em> have helped, since {@code url()} passes a protocol-relative
     * URL through byte for byte (F6). Suppression, not a different encoder, is what closes this.
     */
    @Test
    public void aReferenceInsideACssUrlFunctionNoLongerReachesAnyOrigin() {
        String decoded = CanoeTestSupport
                .render("<div style=\"background:url($data)\">x</div>",
                        "//" + Payloads.SENTINEL_HOST + "/beacon")
                .decodedAttr("div", "style");

        assertEquals("background:url()", decoded,
                "R2: the CSS parser receives an empty url() token. It used to receive the attacker's"
                        + " intact");
        assertTrue(VerdictEvaluator.analyseUrl("//" + Payloads.SENTINEL_HOST + "/beacon").isDangerous(),
                "...and the value that no longer arrives is off-origin, so this was a request to the"
                        + " attacker on every page load");

        // The same value in an attribute Canoe DOES treat as a URL, for contrast.
        String throughUrlEncoder = CanoeTestSupport
                .render("<a href=\"$data\">x</a>", "//" + Payloads.SENTINEL_HOST + "/beacon")
                .decodedAttr("a", "href");
        assertEquals("//" + Payloads.SENTINEL_HOST + "/beacon", throughUrlEncoder,
                "url() lets a protocol-relative URL through too (F6), so routing style through url()"
                        + " would not have closed this - suppression is what did");
    }

    // ------------------------------------------------------------------
    // The positions the reset cannot reach
    // ------------------------------------------------------------------

    /**
     * A colon in a {@code <style>} element body is just a character.
     *
     * <p>The CSS states have no value-prefix scan — {@code detectAttributePrefix()} is called only
     * from the {@code TAG_ATTR_VALUE} path at {@code Canoe.java:918} — so {@code CTX_SUPPRESS} holds
     * however deeply nested the reference is. That produces the asymmetry F4 is easiest to
     * misunderstand through: {@code color:$x} suppresses in a stylesheet and is injectable in an
     * attribute, and the two templates look equivalent to whoever wrote them.
     *
     * <p>{@code <style>} producing {@code CTX_SUPPRESS} rather than {@code CTX_CSS} is
     * <strong>F21</strong>, which {@code AttributeNameMatrixTest.currentContextCanNeverReturnCtxCss}
     * owns. It has no consequence here — both encode to the empty string — and it is worth knowing
     * while reading these assertions, because {@code CTX_SUPPRESS} in a stylesheet looks like a hole
     * in the switch and is in fact the intended answer arriving by the wrong route.
     */
    @Test
    public void aColonInsideAStyleElementBodyDoesNothingAtAll() {
        for (String prefix : List.of(
                "<style>",
                "<style>p{color:",
                "<style>@media screen{p{color:",
                "<style>@media screen and (min-width:600px){p{background:url(",
                "<style>p{content:'",
                "<style>p{color:red;background:")) {
            assertEquals(Canoe.CTX_SUPPRESS, CanoeTestSupport.contextAfter(prefix),
                    prefix + ": the CSS states never call detectAttributePrefix(), so no colon"
                            + " anywhere in a stylesheet can widen the context");
        }

        assertEquals("<style>@media screen{p{color:}}</style>",
                CanoeTestSupport.render("<style>@media screen{p{color:$data}}</style>",
                        Payloads.CSS_IMPORT.value()).output(),
                "and the reference contributes nothing, at any nesting depth");
    }

    /**
     * The asymmetry itself, as one comparison: identical CSS, identical payload, and — before R2 —
     * opposite outcomes, decided by whether the declaration was written in a stylesheet or in an
     * attribute. Inverted by R2; was
     * {@code theSameDeclarationIsSuppressedInAStylesheetAndInjectableInAnAttribute}.
     */
    @Test
    public void theSameDeclarationIsSuppressedInAStylesheetAndInAnAttribute() {
        String payload = Payloads.CSS_URL_BEACON.value();

        String inStylesheet = CanoeTestSupport
                .render("<style>p{color:$data}</style>", payload).output();
        assertEquals("<style>p{color:}</style>", inStylesheet,
                "CTX_SUPPRESS: the centrepiece of the design, working");

        String inAttribute = CanoeTestSupport
                .render("<p style=\"color:$data\">x</p>", payload).decodedAttr("p", "style");
        assertEquals("color:", inAttribute,
                "R2: the same declaration, the same payload, and the CSS parser gets none of it -"
                        + " where it used to get color:" + payload + " in the attribute and nothing"
                        + " in the stylesheet, from two templates a developer would call equivalent");
    }

    // ------------------------------------------------------------------
    // The corpus, consumed
    // ------------------------------------------------------------------

    /** Every {@code SinkKind.CSS} case, from both &sect;A.1 (stylesheet bodies) and &sect;A.4. */
    static List<XssCase.Invocation> cssInvocations() {
        List<XssCase.Invocation> result = new ArrayList<>();
        for (XssCase testCase : CanoeCorpus.all()) {
            if (testCase.sink() == SinkKind.CSS) {
                result.addAll(testCase.invocations());
            }
        }
        return result;
    }

    /**
     * Every CSS row is either fully suppressed or fully live: there is no partial escaping anywhere in
     * this group.
     *
     * <p>Worth stating because it is what makes F4 a routing bug rather than an encoder bug, and
     * because it is the property that has to be re-checked before the commented-out
     * {@code HtmlEncoder.css()} at {@code Canoe.java:1074-1081} is ever enabled — per F16 that encoder
     * emits unterminated two-digit hex escapes, so the day it is switched on this test's two-valued
     * answer becomes three-valued and every row here needs re-deciding.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("cssInvocations")
    public void everyCssRowIsEitherSuppressedOutrightOrLiveVerbatim(XssCase.Invocation invocation) {
        XssCase testCase = invocation.testCase();
        Payload payload = invocation.payload();
        String rendered = VerdictEvaluator.render(testCase, payload.value()).output();
        String benign = VerdictEvaluator.render(testCase, "").output();

        if (invocation.verdict().isSuppression()) {
            assertEquals(benign, rendered,
                    () -> invocation + " is ledgered as suppressed, so the render must be identical"
                            + " to one with an empty value");
            return;
        }

        assertEquals(Verdict.KNOWN_VULNERABLE, invocation.verdict(),
                () -> invocation + ": a CSS row is either suppressed or live. A SAFE CSS row would"
                        + " mean the value reached the CSS parser and was harmless there, which"
                        + " nothing in this component can currently produce - if one appears, it is a"
                        + " new behaviour and needs a finding rather than a widened test.");

        String sinkValue = testCase.attribute() == null
                ? VerdictEvaluator.render(testCase, payload.value()).decodedText(testCase.selector())
                : VerdictEvaluator.render(testCase, payload.value())
                        .decodedAttr(testCase.selector(), testCase.attribute());
        assertTrue(sinkValue.contains(payload.value()),
                () -> "F4: " + invocation + " is ledgered live, so the CSS parser must receive the"
                        + " payload verbatim once the HTML parser has decoded the character"
                        + " references. Got: " + sinkValue);
    }

    /**
     * The reset downgraded every classification and not only the CSS one, so its removal has to be
     * asserted on all three. Inverted by R2; was
     * {@code theResetDowngradesEveryClassificationAndNotOnlyTheCssOne}.
     *
     * <p>{@code style} is the only attribute name that produces {@code ATTR_CSS}, which bounded F4's
     * CSS half exactly. The URI and JS halves were the same reset reaching two other
     * classifications, and they are F4's second consequence and F17 respectively. Keeping the three
     * in one test is what says they were one mechanism rather than three findings sharing a line
     * number — and it is what would catch a partial fix that closed one and left the others.
     */
    @Test
    public void nothingDowngradesAnyClassificationAndTheNarrowingStillWorks() {
        assertEquals(Canoe.ATTR_CSS, attributeContextOf("style"),
                "style is the one name that produces ATTR_CSS");

        assertEquals(Canoe.CTX_SUPPRESS, CanoeTestSupport.contextAfter("<div style=\""));
        assertEquals(Canoe.CTX_SUPPRESS, CanoeTestSupport.contextAfter("<div style=\"color:"),
                "R2: ATTR_CSS is no longer downgraded (F4)");
        assertEquals(Canoe.CTX_URI, CanoeTestSupport.contextAfter("<a href=\""));
        assertEquals(Canoe.CTX_URI, CanoeTestSupport.contextAfter("<a href=\"https:"),
                "R2: ATTR_URI is no longer downgraded, so a link stays percent-encoded rather than"
                        + " silently becoming entity-encoded (F4's second consequence)");
        assertEquals(Canoe.CTX_JS, CanoeTestSupport.contextAfter("<a onclick=\""));
        assertEquals(Canoe.CTX_JS, CanoeTestSupport.contextAfter("<a onclick=\"f({a:"),
                "R2: ATTR_JS is no longer downgraded, which was the same line of code reaching the"
                        + " one classification Canoe gets right (F17)");

        // ...and the half of the method that was always correct, which the fix had to preserve:
        // when a prefix DOES match, the context still narrows.
        assertEquals(Canoe.CTX_JS, CanoeTestSupport.contextAfter("<a href=\"javascript:"),
                "detectAttributePrefix() exists to narrow ATTR_URI to ATTR_JS for a script scheme,"
                        + " and that part always worked. R2 deleted the unconditional reset it did"
                        + " first rather than the scan itself, so this must still hold.");
    }

    /**
     * Sanity: the corpus's CSS group covers both sides of the boundary, or the file proves nothing.
     *
     * <p>Inverted by R2. Before the fix the requirement was that at least eight {@code css.*} cases
     * were <em>live</em>, one per property name in F4's precondition paragraph. Those cases still
     * exist and still carry those property names — that is what makes them the regression net — but
     * every one of them is now a suppression, so the requirement is the count on the other side.
     */
    @Test
    public void theCorpusCoversBothSidesOfTheColonBoundary() {
        List<String> suppressed = new ArrayList<>();
        List<String> live = new ArrayList<>();
        for (XssCase testCase : CanoeCorpus.inSection(SECTION)) {
            if (testCase.sink() != SinkKind.CSS) {
                continue;
            }
            (testCase.defaultVerdict().isSuppression() ? suppressed : live).add(testCase.id());
        }
        assertTrue(live.isEmpty(),
                () -> "R2: no CSS case in " + SECTION + " may be live any more; a colon in a style"
                        + " value cannot reach the CSS parser. Live: " + live);
        assertTrue(suppressed.size() >= 10,
                () -> "F4's precondition paragraph names eight property names that used to trigger"
                        + " it, and the corpus carries a case for each shape plus the two that never"
                        + " did; all of them must now be suppressed. It has " + suppressed);
    }

    private static int attributeContextOf(String attributeName) {
        try {
            return new CanoeStateProbe().feed("<x " + attributeName + "=\"").attributeContext();
        } catch (IOException e) {
            throw new AssertionError("Canoe rejected the attribute name " + attributeName, e);
        }
    }
}
