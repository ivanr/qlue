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
 * to {@code ATTR_CSS}, which produces {@code CTX_SUPPRESS}, which is the empty string. F4 is that
 * this guarantee is defeated by writing a CSS property in front of the reference — the thing every
 * real {@code style} attribute does. {@code detectAttributePrefix()} fires on the first colon at
 * value index 0 through 10 and unconditionally resets {@code attributeContext} to {@code ATTR_HTML}
 * ({@code Canoe.java:224}); no prefix matches; {@code html()} takes over; and the HTML parser decodes
 * every character reference before the CSS parser sees the value.
 *
 * <h2>What this file asserts that {@code CanoeCorpusTest} does not</h2>
 *
 * <ul>
 *   <li><strong>The boundary as a function, at the Velocity level.</strong> {@code AttributePrefixTest}
 *       (T10) pins the colon index 0-12 against a bare {@code Canoe}; the corpus holds eleven
 *       {@code css.*} rows with individually reviewed verdicts. Neither says that the outcome is a
 *       function of <em>the index of the first colon and nothing else</em>.
 *       {@link #thePropertyNameDecidesWhetherStyleIsSuppressed} parameterises the whole set of
 *       property names F4 lists, asserts that each one's colon index is what the finding claims, and
 *       asserts that the verdict follows the index — so {@code padding:} and {@code display:} must
 *       agree because they are both 7, and {@code background:} and {@code font-family:} must differ
 *       because they are 10 and 11. A per-template ledger records eleven answers; this records the
 *       rule that generates them.
 *   <li><strong>The positions the reset does <em>not</em> reach.</strong> A colon inside a
 *       {@code <style>} element body, inside an {@code @media} block, or anywhere past index 10 is
 *       just a character, because the CSS states have no value-prefix scan at all. That asymmetry —
 *       {@code color:$x} suppressed in a stylesheet and injectable in an attribute, from templates a
 *       developer would call equivalent — is the shape of F4 that a list of vulnerable rows hides.
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
     * Every property name F4's precondition paragraph names, with the index its colon lands on.
     *
     * <p>The index is asserted rather than trusted: it is the whole precondition, and the review
     * corrected itself on it once already (the adversarial pass placed the cutoff one character
     * earlier and concluded {@code background:} was safe, which it is not — {@code c == ':'} is
     * tested before the {@code bufLen == 10} cutoff at {@code Canoe.java:924}).
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
     * The finding, as a function of one integer.
     *
     * <p>Three things are asserted together per row, and the point is that they are together: the
     * colon's index, the context Canoe ends up in, and what the CSS parser is actually handed. A test
     * of the context alone would pass if the reference-insertion handler stopped consulting it; a test
     * of the decoded value alone would not say why.
     */
    @ParameterizedTest(name = "{0}: (colon at {1})")
    @MethodSource("cssProperties")
    public void thePropertyNameDecidesWhetherStyleIsSuppressed(String property, int colonIndex,
                                                               boolean injectable) {
        assertEquals(colonIndex, property.length(),
                property + ": F4's precondition is the index of the colon in the attribute VALUE,"
                        + " which for 'name:' is the length of the name. If this row is wrong the"
                        + " whole table is measuring something else.");

        String template = "<div style=\"" + property + ":$data\">x</div>";
        String payload = Payloads.CSS_URL_BEACON.value();

        int context = CanoeTestSupport.contextAfter("<div style=\"" + property + ":");
        assertEquals(injectable ? Canoe.CTX_HTML_ATTR : Canoe.CTX_SUPPRESS, context,
                () -> "F4: a colon at index " + colonIndex + " must "
                        + (injectable
                                ? "fire detectAttributePrefix(), which resets attributeContext to"
                                        + " ATTR_HTML and hands the value to html()"
                                : "leave the name-derived ATTR_CSS alone, because bufLen was set to"
                                        + " -1 at index 10 and the scan never runs")
                        + ". Observed " + CanoeTestSupport.contextName(context));

        String decoded = CanoeTestSupport.render(template, payload).decodedAttr("div", "style");
        if (injectable) {
            assertEquals(property + ":" + payload, decoded,
                    "F4: the HTML parser decodes html()'s character references while building the"
                            + " attribute value, so the CSS parser receives the attacker's"
                            + " declarations verbatim - a full-viewport overlay, a beacon to an"
                            + " attacker origin, or CSS-selector exfiltration of DOM content");
        } else {
            assertEquals(property + ":", decoded,
                    "the reference contributed nothing at all, which is the design working");
        }
    }

    /**
     * The two templates the finding is really about, side by side.
     *
     * <p>{@code <div style="$c">} is suppressed and {@code <div style="color:$c">} is injectable, and
     * no template author would call those different. The pair is the shortest statement of F4 and it
     * is kept out of the parameterised table above so that it reads as a comparison rather than as
     * two rows.
     */
    @Test
    public void aBareStyleAttributeIsSuppressedAndOneWithAPropertyIsNot() {
        String payload = Payloads.CSS_OVERLAY.value();

        assertEquals("<div style=\"\">x</div>",
                CanoeTestSupport.render("<div style=\"$data\">x</div>", payload).output(),
                "the design working: ATTR_CSS survives, CTX_SUPPRESS applies, nothing is emitted");

        String withProperty = CanoeTestSupport.render("<div style=\"color:$data\">x</div>", payload)
                .decodedAttr("div", "style");
        assertEquals("color:" + payload, withProperty,
                "F4: six characters of literal template text convert 'refuse to output into CSS'"
                        + " into 'HTML-encode and let the parser undo it'");
        assertTrue(withProperty.contains("position:fixed") && withProperty.contains("url(//"),
                () -> "and the declarations arrive intact, which is the concrete impact: a"
                        + " full-viewport clickjacking overlay that also beacons out. Got: "
                        + withProperty);
    }

    /**
     * Only the <em>first</em> colon matters, so a complete declaration in front of the reference is
     * still injectable and a second reference later in the same value changes nothing.
     *
     * <p>{@code detectAttributePrefix()} runs once and sets {@code bufLen} to -1, so nothing later in
     * the value is examined. The reference's own position is irrelevant; only the first colon's is.
     */
    @Test
    public void onlyTheFirstColonIsEverExamined() {
        assertEquals(Canoe.CTX_HTML_ATTR,
                CanoeTestSupport.contextAfter("<div style=\"color:red;background:"),
                "F4: the scan fired on the colon of 'color:' at index 5 and gave up; the second"
                        + " declaration is never looked at");
        assertEquals(Canoe.CTX_HTML_ATTR,
                CanoeTestSupport.contextAfter(
                        "<div style=\"color:red;text-decoration:underline;font-family:"),
                "...however far into the value the reference eventually sits");

        // ...and the converse: a value whose first colon is past the window stays suppressed no
        // matter how many colons follow it.
        assertEquals(Canoe.CTX_SUPPRESS,
                CanoeTestSupport.contextAfter("<div style=\"text-decoration:underline;color:"),
                "the first colon is at index 15, so the scan never ran; the colon of 'color:' later"
                        + " in the same value cannot revive it");
    }

    /**
     * A CSS string literal around the reference is not a mitigation, and a template author is likely
     * to think it is.
     *
     * <p>{@code html()} turns the apostrophe into {@code &#39;} and the HTML parser gives it back as a
     * real quote before the CSS parser runs — the identical mechanism to F1's JavaScript string
     * literal. The comparison that makes it land is {@code content:'$x'} against
     * {@code font-family:'$x'}: both are quoted CSS strings, one is injectable and one is not, and the
     * only difference is that the second property name is four characters longer.
     */
    @Test
    public void aQuotedCssStringIsNotAContainer() {
        String payload = Payloads.CSS_URL_BEACON.value();

        String content = CanoeTestSupport
                .render("<div style=\"content:'$data'\">x</div>", payload).decodedAttr("div", "style");
        assertEquals("content:'" + payload + "'", content,
                "F4: colon at index 7, so html() applies and the quote the template wrote does not"
                        + " contain anything - the payload's own ';' closes the declaration");

        String fontFamily = CanoeTestSupport
                .render("<div style=\"font-family:'$data'\">x</div>", payload)
                .decodedAttr("div", "style");
        assertEquals("font-family:''", fontFamily,
                "the same shape with a longer property name: colon at index 11, the scan has already"
                        + " given up, ATTR_CSS survives and nothing is emitted");

        assertNotEquals(content, fontFamily,
                "two quoted CSS strings, opposite outcomes, decided entirely by the length of the"
                        + " property name in front of them");
    }

    /**
     * A reference inside a CSS {@code url()}, which is F4's concrete impact in one template: an
     * attacker-chosen URL fetched on every render, which is how CSS exfiltration of DOM content is
     * bootstrapped.
     *
     * <p>Note what is <em>not</em> happening here: no URL encoder is involved. The attribute is
     * {@code style}, so once the reset has fired the value goes through {@code html()}, not through
     * {@code url()} — the encoder that at least escapes a colon. A CSS {@code url()} inside a
     * {@code style} attribute is the one URL sink in the whole component with no URL handling at all.
     */
    @Test
    public void aReferenceInsideACssUrlFunctionReachesAnAttackerOrigin() {
        String decoded = CanoeTestSupport
                .render("<div style=\"background:url($data)\">x</div>",
                        "//" + Payloads.SENTINEL_HOST + "/beacon")
                .decodedAttr("div", "style");

        assertEquals("background:url(//" + Payloads.SENTINEL_HOST + "/beacon)", decoded,
                "F4: the CSS parser receives the attacker's url() token intact");
        assertTrue(VerdictEvaluator.analyseUrl("//" + Payloads.SENTINEL_HOST + "/beacon").isDangerous(),
                "...and it is off-origin, which is a request to the attacker on every page load");

        // The same value in an attribute Canoe DOES treat as a URL, for contrast.
        String throughUrlEncoder = CanoeTestSupport
                .render("<a href=\"$data\">x</a>", "//" + Payloads.SENTINEL_HOST + "/beacon")
                .decodedAttr("a", "href");
        assertEquals("//" + Payloads.SENTINEL_HOST + "/beacon", throughUrlEncoder,
                "url() lets a protocol-relative URL through too (F6), so in this particular case the"
                        + " two encoders agree - which is worth knowing before concluding that"
                        + " routing style through url() would have helped");
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
     * The asymmetry itself, as one comparison: identical CSS, identical payload, opposite outcomes,
     * decided by whether the declaration was written in a stylesheet or in an attribute.
     */
    @Test
    public void theSameDeclarationIsSuppressedInAStylesheetAndInjectableInAnAttribute() {
        String payload = Payloads.CSS_URL_BEACON.value();

        String inStylesheet = CanoeTestSupport
                .render("<style>p{color:$data}</style>", payload).output();
        assertEquals("<style>p{color:}</style>", inStylesheet,
                "CTX_SUPPRESS: the centrepiece of the design, working");

        String inAttribute = CanoeTestSupport
                .render("<p style=\"color:$data\">x</p>", payload).decodedAttr("p", "style");
        assertEquals("color:" + payload, inAttribute,
                "F4: the same declaration, the same payload, and the CSS parser gets all of it");
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
     * The style attribute is the only attribute name that produces {@code ATTR_CSS}, so it is the only
     * one the reset can downgrade from a CSS context — which bounds F4's CSS half exactly.
     *
     * <p>The URI and JS halves are the same reset reaching two other classifications, and they are
     * F4's second consequence and F17 respectively. Recorded here so the three are visible as one
     * mechanism rather than three findings that happen to share a line number.
     */
    @Test
    public void theResetDowngradesEveryClassificationAndNotOnlyTheCssOne() {
        assertEquals(Canoe.ATTR_CSS, attributeContextOf("style"),
                "style is the one name that produces ATTR_CSS");

        assertEquals(Canoe.CTX_SUPPRESS, CanoeTestSupport.contextAfter("<div style=\""));
        assertEquals(Canoe.CTX_HTML_ATTR, CanoeTestSupport.contextAfter("<div style=\"color:"),
                "F4: ATTR_CSS downgraded");
        assertEquals(Canoe.CTX_URI, CanoeTestSupport.contextAfter("<a href=\""));
        assertEquals(Canoe.CTX_HTML_ATTR, CanoeTestSupport.contextAfter("<a href=\"https:"),
                "F4's second consequence: ATTR_URI downgraded, so a link is entity-encoded rather"
                        + " than percent-encoded");
        assertEquals(Canoe.CTX_JS, CanoeTestSupport.contextAfter("<a onclick=\""));
        assertEquals(Canoe.CTX_HTML_ATTR, CanoeTestSupport.contextAfter("<a onclick=\"f({a:"),
                "F17: ATTR_JS downgraded, which is the same line of code reaching the one"
                        + " classification Canoe gets right");

        // ...and the prefix the scan is actually looking for, so the reset is not read as unconditional
        // damage: when a prefix DOES match, the context narrows correctly.
        assertEquals(Canoe.CTX_JS, CanoeTestSupport.contextAfter("<a href=\"javascript:"),
                "detectAttributePrefix() exists to narrow ATTR_URI to ATTR_JS for a script scheme,"
                        + " and that part works. The defect is the unconditional reset it does first,"
                        + " which is why remediation item 1 deletes the reset rather than the scan.");
    }

    /** Sanity: the corpus's CSS group covers both sides of the boundary, or the file proves nothing. */
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
        assertFalse(suppressed.isEmpty(), "no suppressed CSS case in " + SECTION);
        assertFalse(live.isEmpty(), "no injectable CSS case in " + SECTION);
        assertTrue(live.size() >= 8,
                () -> "F4's precondition paragraph names eight property names that trigger it and"
                        + " the corpus should carry a case for each shape; it has " + live);
    }

    private static int attributeContextOf(String attributeName) {
        try {
            return new CanoeStateProbe().feed("<x " + attributeName + "=\"").attributeContext();
        } catch (IOException e) {
            throw new AssertionError("Canoe rejected the attribute name " + attributeName, e);
        }
    }
}
