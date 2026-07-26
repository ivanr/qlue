package com.webkreator.qlue.view.canoe;

import com.webkreator.qlue.view.Canoe;
import com.webkreator.qlue.view.CanoeStateProbe;
import com.webkreator.qlue.view.canoe.corpus.Payload;
import com.webkreator.qlue.view.canoe.corpus.Payloads;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unquoted attribute values — the position F11 dropped and R19 routes.
 *
 * <p><strong>What R19 changed.</strong> {@code currentContext()} had no case for
 * {@code TAG_ATTR_VALUE_BEFORE}, the state the parser sits in between the {@code =} and the first
 * character of the value. A reference placed there — {@code <a href=$x>} — was encoded for
 * {@code CTX_SUPPRESS} and rendered as the empty string, because the quote that would have advanced
 * the machine into {@code TAG_ATTR_VALUE} never arrives. R19 gives the state the same answer
 * {@code TAG_ATTR_VALUE} gets: the attribute's name-derived context.
 *
 * <p>The defect was narrow in a way that made it worse rather than better. One literal character was
 * enough to advance the parser, so {@code <a href=/p/$y>} rendered correctly and only
 * {@code <a href=$y>} lost its value; a developer meeting that has no error, no diagnostic and a
 * template that looks identical to one that works. The documented remedy was
 * {@code allowDirectOutput()} plus {@code $_x.asis()}, which turns Canoe off for the value entirely,
 * so a fail-safe suppression was converting into a manually encoded, unreviewed output site.
 *
 * <h2>Why routing this state is safe</h2>
 *
 * <p>An unquoted value ends at whitespace or {@code >} — for Canoe's own tokenizer
 * ({@code TAG_ATTR_VALUE} with {@code QUOTE_NONE}) and for the HTML Standard's
 * attribute-value-unquoted state, which additionally treats {@code "}, {@code '}, {@code <},
 * {@code =} and {@code `} as a parse error that stays <em>inside</em> the value. The first character
 * decides the quoting, so a leading {@code "} or {@code '} would be read as an opening quote here
 * and in the standard's before-attribute-value state.
 *
 * <p>So the routing is safe exactly when no encoder reachable from an attribute value can emit
 * whitespace or {@code >} anywhere in its output, or a quote at the front of it. That is the whole
 * argument, and {@link #noEncoderReachableFromAnAttributeValueCanTerminateAnUnquotedOne} is its
 * executable form: it sweeps every payload in the catalogue through every context an attribute name
 * can produce and fails if any output carries a terminator. If a future encoder can emit one — a CSS
 * encoder wired into {@code ATTR_CSS}, a real JavaScript encoder behind {@code CTX_JS} — that test
 * fails, and {@code Canoe.currentContext()}'s {@code TAG_ATTR_VALUE_BEFORE} case has to be
 * reconsidered rather than the test relaxed.
 *
 * <p>The empty-output case needs no argument and gets one anyway, in
 * {@link #aSuppressedValueLeavesTheParserExactlyWhereItWas}: nothing is written, the machine stays in
 * {@code TAG_ATTR_VALUE_BEFORE}, and the template's own next character is handled as it was before
 * the reference existed.
 */
public class UnquotedAttributeValueTest {

    /**
     * The characters that end an unquoted attribute value, in Canoe and in the HTML Standard's
     * attribute-value-unquoted state. Everything else — including the quotes, which are a parse
     * error and nothing more once the value has started — stays in the value.
     */
    private static final String VALUE_TERMINATORS = " \t\n\f\r>";

    /**
     * The characters that would be read as an opening quote if an encoder emitted one first. Only the
     * first character of the output matters: after it, the quoting style is decided.
     */
    private static final String QUOTE_OPENERS = "\"'";

    // ------------------------------------------------------------------
    // The shapes
    // ------------------------------------------------------------------

    static Stream<Arguments> shapes() {
        return Stream.of(
                // The headline: a URL name with the reference directly after the '='. Rendered
                // rather than described, because "the value is no longer empty" is the whole claim.
                shape("<a href=$data>link</a>", "/p/1?a=b&c=d#f",
                        "<a href=/p/1?a=b&amp;c=d#f>link</a>"),
                // ...and the quoted form, unchanged by R19. Same bytes, plus the template's quotes.
                shape("<a href=\"$data\">link</a>", "/p/1?a=b&c=d#f",
                        "<a href=\"/p/1?a=b&amp;c=d#f\">link</a>"),
                // Whitespace between the '=' and the reference. TAG_ATTR_VALUE_BEFORE skips it, so
                // the only difference in the output is the space the template itself wrote.
                shape("<a href= $data>link</a>", "/p/1?a=b&c=d#f",
                        "<a href= /p/1?a=b&amp;c=d#f>link</a>"),
                // A plain-text name. html() escapes the space to &#32; and the '=' to &#61;, so the
                // payload that exists to terminate an unquoted value cannot.
                shape("<div id=$data>x</div>", "x onmouseover=__canoePwned('a')",
                        "<div id=x&#32;onmouseover&#61;&#95;&#95;canoePwned&#40;&#39;a&#39;&#41;"
                                + ">x</div>"),
                shape("<span title=$data>x</span>", "a b",
                        "<span title=a&#32;b>x</span>"),
                // A resource-loading sink: R9's origin filter still applies, so a relative path is
                // emitted and an off-origin authority is not.
                shape("<script src=$data></script>", "/app.js",
                        "<script src=/app.js></script>"),
                shape("<script src=$data></script>", "//attacker.invalid/x.js",
                        "<script src=></script>"),
                // The three classifications that suppress do so in this position too. R19 routed the
                // state; it did not change what any name is classified as.
                shape("<div style=$data>x</div>", "color:red",
                        "<div style=>x</div>"),
                shape("<a onclick=$data>x</a>", "f()",
                        "<a onclick=>x</a>"),
                shape("<div my-widget-config=$data>x</div>", "a b",
                        "<div my-widget-config=>x</div>"),
                // An empty value is still an empty value, and it renders the same as a suppressed
                // one — which is exactly why F11 was invisible.
                shape("<a href=$data>link</a>", "",
                        "<a href=>link</a>"),
                // The value ends where the template says it ends, not where the encoder stops.
                shape("<a href=$data class=b>link</a>", "/p",
                        "<a href=/p class=b>link</a>"));
    }

    private static Arguments shape(String template, String value, String expected) {
        return Arguments.of(template, value, expected);
    }

    @ParameterizedTest(name = "{0} <- {1}")
    @MethodSource("shapes")
    public void unquotedValuesRenderTheirNameDerivedEncoding(String template, String value,
                                                             String expected) {
        CanoeTestSupport.RenderResult result = CanoeTestSupport.render(template, value);
        assertFalse(result.isError(),
                () -> template + " raised " + result.errorMessage());
        assertEquals(expected, result.output(),
                () -> "R19: " + CanoeTestSupport.quote(template) + " with "
                        + CanoeTestSupport.quote(value));
    }

    // ------------------------------------------------------------------
    // The quoted and unquoted forms are the same value
    // ------------------------------------------------------------------

    /**
     * The claim R19 makes, stated as an equivalence rather than as a list of outputs: for every
     * payload in the catalogue, the value a browser extracts from {@code <a href=$data>} is the value
     * it extracts from {@code <a href="$data">}.
     *
     * <p>The comparison is at the DOM rather than at the byte level on purpose. The two templates
     * cannot produce identical bytes — one has quotes in it — and the question that matters is what
     * the URL parser is handed, which is the attribute value after character references are decoded.
     * If any payload made the two disagree, either the unquoted form terminated early (a breakout) or
     * it was encoded for a different context (a routing bug), and both show up here.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("everyPayload")
    public void theUnquotedFormDeliversTheSameValueAsTheQuotedForm(Payload payload) {
        assertSameValueQuotedAndUnquoted("<a href=%s>link</a>", "<a href=\"%s\">link</a>",
                "a", "href", payload);
        assertSameValueQuotedAndUnquoted("<span title=%s>x</span>", "<span title=\"%s\">x</span>",
                "span", "title", payload);
        assertSameValueQuotedAndUnquoted("<script src=%s></script>",
                "<script src=\"%s\"></script>", "script", "src", payload);
    }

    private static void assertSameValueQuotedAndUnquoted(String unquotedTemplate,
                                                         String quotedTemplate,
                                                         String selector, String attribute,
                                                         Payload payload) {
        CanoeTestSupport.RenderResult unquoted =
                CanoeTestSupport.render(String.format(unquotedTemplate, "$data"), payload.value());
        CanoeTestSupport.RenderResult quoted =
                CanoeTestSupport.render(String.format(quotedTemplate, "$data"), payload.value());

        assertFalse(unquoted.isError(), () -> unquotedTemplate + " raised " + unquoted.errorMessage());
        assertFalse(quoted.isError(), () -> quotedTemplate + " raised " + quoted.errorMessage());

        assertEquals(quoted.decodedAttr(selector, attribute),
                unquoted.decodedAttr(selector, attribute),
                () -> "R19: the unquoted form of " + selector + "@" + attribute + " must deliver the"
                        + " value the quoted form delivers, for " + payload
                        + "\n  unquoted : " + CanoeTestSupport.quote(unquoted.output())
                        + "\n  quoted   : " + CanoeTestSupport.quote(quoted.output()));

        // ...and the payload must not have created markup, which is the failure mode a value-level
        // comparison alone could miss if both forms broke out identically.
        assertEquals(1, unquoted.dom().select(selector).size(),
                () -> "R19: " + payload + " produced more than one " + selector + " in "
                        + CanoeTestSupport.quote(unquoted.output()));
    }

    static List<Payload> everyPayload() {
        return Payloads.all();
    }

    // ------------------------------------------------------------------
    // The safety argument, executable
    // ------------------------------------------------------------------

    /**
     * <strong>The load-bearing test for R19.</strong> No encoder reachable from an attribute value
     * can emit a character that ends an unquoted one, and none can open with a quote.
     *
     * <p>The contexts are not written down here: they are collected by driving one attribute name per
     * classification through the state machine to the {@code =} and reading
     * {@code currentContext()} — so a routing change that produces a context this sweep does not
     * cover fails {@link #theSweepCoversEveryContextAnAttributeNameCanProduce} rather than passing
     * quietly with a hole in it.
     *
     * <p>The payloads are the whole catalogue, which is the right corpus for this question: it
     * contains the quote, space and {@code >} shapes deliberately, plus the astral and lone-surrogate
     * cases where an encoder is most likely to emit something unintended.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("everyPayload")
    public void noEncoderReachableFromAnAttributeValueCanTerminateAnUnquotedOne(Payload payload) {
        for (int context : contextsReachableFromAnAttributeName()) {
            assertNoTerminator(CanoeTestSupport.encodeFor(payload.value(), context),
                    context, payload.value(), "the static dispatcher");
        }

        // ...and again through the instance path, which is the one CanoeReferenceInsertionHandler
        // actually calls. It differs for CTX_URI_RESOURCE only, where the application's CDN
        // allowlist decides whether urlResource() emits the URL or the empty string - and the
        // interesting direction is the one that emits, so the allowlist here admits the payloads'
        // sentinel host. Without it every resource-sink assertion below would be vacuous against the
        // empty string.
        for (String template : List.of("<a href=", "<span title=", "<script src=",
                "<a onclick=", "<div style=", "<div my-widget-config=")) {
            Canoe canoe = new Canoe(new StringWriter(), Set.of(),
                    List.of(Payloads.SENTINEL_HOST, "https://" + Payloads.SENTINEL_HOST));
            try {
                canoe.write(template);
            } catch (IOException e) {
                throw new AssertionError("Expected " + template + " to parse cleanly", e);
            }
            assertNoTerminator(canoe.encode(payload.value()), canoe.currentContext(),
                    payload.value(), "the instance path after " + CanoeTestSupport.quote(template));
        }
    }

    private static void assertNoTerminator(String encoded, int context, String value, String how) {
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            final int position = i;
            assertTrue(VALUE_TERMINATORS.indexOf(c) < 0,
                    () -> "R19: " + CanoeTestSupport.contextName(context) + " via " + how
                            + " emitted U+" + String.format("%04X", (int) c) + " at offset "
                            + position + ", which ends an unquoted attribute value. Routing"
                            + " TAG_ATTR_VALUE_BEFORE is only safe while this cannot happen; see"
                            + " Canoe.currentContext(). Input: "
                            + CanoeTestSupport.quote(value) + ", output: "
                            + CanoeTestSupport.quote(encoded));
        }
        if (!encoded.isEmpty()) {
            assertTrue(QUOTE_OPENERS.indexOf(encoded.charAt(0)) < 0,
                    () -> "R19: " + CanoeTestSupport.contextName(context) + " via " + how
                            + " opened with " + CanoeTestSupport.quote(encoded.substring(0, 1))
                            + ", which the before-attribute-value state reads as the value's opening"
                            + " quote. Input: " + CanoeTestSupport.quote(value));
        }
        // '<' cannot end a value, but an encoder that emitted one would be a far larger problem than
        // this test's subject, and this is the cheapest place the sweep already exists.
        assertFalse(encoded.indexOf('<') >= 0,
                () -> "R19: " + CanoeTestSupport.contextName(context) + " via " + how
                        + " emitted a raw '<' for " + CanoeTestSupport.quote(value));
    }

    /**
     * The sweep above is only worth anything if it reaches every context an attribute value can be
     * encoded for. Two halves, because either on its own is a list that can go stale:
     *
     * <ol>
     *   <li>the contexts the probe templates actually produce, read out of the running machine, are
     *       the five the sweep expects; and</li>
     *   <li>the {@code ATTR_*} classifications {@code Canoe} declares are the eight the probe
     *       templates were written against — so adding a ninth fails here and forces a probe for it,
     *       rather than escaping the sweep silently because no template reaches it.</li>
     * </ol>
     *
     * <p>{@code ATTR_DATA} and {@code ATTR_ACTIONSCRIPT} have no probe template because no attribute
     * <em>name</em> produces them: they are assigned by {@code detectAttributePrefix()} from value
     * characters, which by definition cannot be in front of a reference that sits directly after the
     * {@code =}. They are covered anyway, because both map to {@code CTX_SUPPRESS}, which the sweep
     * does reach.
     */
    @Test
    public void theSweepCoversEveryContextAnAttributeNameCanProduce() {
        assertEquals(Set.of(Canoe.CTX_HTML_ATTR, Canoe.CTX_URI, Canoe.CTX_URI_RESOURCE,
                        Canoe.CTX_JS, Canoe.CTX_SUPPRESS),
                contextsReachableFromAnAttributeName(),
                "the set of contexts an attribute value can reach has changed. Add the new one to"
                        + " the probe templates below, or the terminator sweep above stops covering"
                        + " it - and R19's case label in Canoe.currentContext() rests on that sweep.");

        Set<String> declared = new java.util.TreeSet<>();
        for (java.lang.reflect.Field field : Canoe.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
                    && field.getName().startsWith("ATTR_")) {
                declared.add(field.getName());
            }
        }
        assertEquals(new java.util.TreeSet<>(List.of("ATTR_ACTIONSCRIPT", "ATTR_CSS", "ATTR_DATA",
                        "ATTR_HTML", "ATTR_JS", "ATTR_UNKNOWN", "ATTR_URI", "ATTR_URI_RESOURCE")),
                declared,
                "Canoe declares an attribute classification this test has not been told about. The"
                        + " probe templates below are one per classification, so a new one reaches no"
                        + " probe and its context escapes the terminator sweep above without failing"
                        + " anything. Add a template for it (or record here why no attribute name can"
                        + " produce it), then re-read R19's case label in Canoe.currentContext().");
    }

    /**
     * The contexts a reference sitting directly after an {@code =} can be encoded for, collected by
     * running the machine rather than by writing them down. One template per {@code ATTR_*}
     * classification {@code setTagAttributeContext()} can assign.
     */
    private static Set<Integer> contextsReachableFromAnAttributeName() {
        Set<Integer> contexts = new LinkedHashSet<>();
        for (String prefix : List.of(
                "<span title=",              // ATTR_HTML
                "<a href=",                  // ATTR_URI
                "<script src=",              // ATTR_URI_RESOURCE
                "<a onclick=",               // ATTR_JS
                "<div style=",               // ATTR_CSS
                "<div my-widget-config=")) { // ATTR_UNKNOWN
            contexts.add(CanoeTestSupport.contextAfter(prefix));
        }
        return contexts;
    }

    // ------------------------------------------------------------------
    // What the parser does around the reference
    // ------------------------------------------------------------------

    /**
     * A suppressed value writes nothing, so the machine is still in {@code TAG_ATTR_VALUE_BEFORE}
     * when the template's next character arrives — and that character is handled exactly as it would
     * have been with no reference in the template at all.
     *
     * <p>This is the case R19's routing quietly depends on: the {@code >} of {@code <div style=$x>}
     * has to close the tag, and the space of {@code <div style=$x class=y>} has to start the next
     * attribute name, with no value ever having been written.
     */
    @Test
    public void aSuppressedValueLeavesTheParserExactlyWhereItWas() throws IOException {
        assertEquals(Canoe.TAG_ATTR_VALUE_BEFORE, new CanoeStateProbe().feed("<div style=").state());

        assertEquals("<div style=>x</div>",
                CanoeTestSupport.render("<div style=$data>x</div>", "color:red").output());
        assertEquals("<div style= class=y>x</div>",
                CanoeTestSupport.render("<div style=$data class=y>x</div>", "color:red").output());
        assertEquals(Canoe.CTX_HTML,
                CanoeTestSupport.contextAfter("<div style=>"),
                "the '>' still closes the tag with no value written");
    }

    /**
     * A non-empty value moves the machine into {@code TAG_ATTR_VALUE} with {@code QUOTE_NONE} on its
     * first character — the same place {@code <a href=/p/$y>} was already reaching under F11 — so
     * everything downstream of the reference, including a second reference in the same value, is
     * parsed as it always was.
     */
    @Test
    public void anEmittedValueLeavesTheParserWhereALiteralValueWouldHave() throws IOException {
        assertEquals(Canoe.TAG_ATTR_VALUE,
                new CanoeStateProbe().feed("<a href=/p").state());
        assertEquals(Canoe.CTX_URI, CanoeTestSupport.contextAfter("<a href=/p"));

        // A second reference in the same unquoted value is still a URL. The colon url() emits behind
        // an allowlisted scheme re-runs detectAttributePrefix(), which since R2 may only narrow -
        // and "https" narrows to nothing (F24's corollary).
        assertEquals("<a href=https://h.example/a/b>x</a>",
                CanoeTestSupport.render("<a href=$data$more>x</a>",
                        java.util.Map.of("data", "https://h.example/a", "more", "/b")).output());
    }

    /**
     * A {@code /} directly before the {@code >} of an unquoted value is part of the value, in Canoe
     * and in every browser: the standard's attribute-value-unquoted state has no self-closing rule,
     * and the self-closing-start-tag state is only entered from before-attribute-name. So
     * {@code <a href=$x/>} yields a value with a trailing slash rather than a self-closed element.
     *
     * <p>Recorded here because R19 makes the shape reachable — under F11 the value was empty and the
     * question did not arise — and because it is a template-authoring trap rather than a Canoe
     * defect: the two tokenizers agree, which is the property that matters and the one asserted.
     */
    @Test
    public void aSlashBeforeTheAngleBracketIsPartOfTheValueInBothTokenizers() {
        CanoeTestSupport.RenderResult result =
                CanoeTestSupport.render("<a href=$data/>x", "/p");
        assertEquals("<a href=/p/>x", result.output());
        assertEquals("/p/", result.decodedAttr("a", "href"),
                "the browser reads the slash as part of the value too, so the element is not"
                        + " self-closed and the href gained a trailing slash - a template that means"
                        + " <a href=\"$data\"/> has to write the quotes");
    }

    /**
     * <strong>The residual R19 leaves, stated rather than discovered.</strong> An unquoted attribute
     * whose value renders <em>empty</em> is not an attribute with an empty value: the tokenizer skips
     * the whitespace after the {@code =} and reads whatever comes next as the value, so the template's
     * own next attribute is swallowed. {@code <img src= alt="a">} is one attribute, not two.
     *
     * <p>This is a property of HTML and not of Canoe — the two tokenizers agree, which is what the
     * assertions below establish — and it applies to a legitimately empty model value exactly as it
     * applies to a suppressed one. It is recorded here because R19 is what makes it visible: under
     * F11 <em>every</em> unquoted value rendered empty, so every such template was in this state
     * unconditionally and nobody could tell. R19 reduces it to the suppressing classifications and to
     * genuinely empty values, and does not remove it.
     *
     * <p>It was left rather than fixed, and the alternative is worth recording. Emitting {@code ""}
     * for a suppressed value in this position would repair {@code <img src=$x alt="a">} and would
     * break {@code <a href=$base/p>}, which renders {@code <a href=/p>} today and would become
     * {@code <a href=""/p>} — a stray {@code p} attribute where there is currently a working relative
     * URL. It would also make {@code Canoe.encode()} depend on the parser's position and on whether
     * the result is empty, which is a surprising contract for a method application code calls
     * directly. The template-level answer — quote the value — costs the author two characters and has
     * no such trade.
     *
     * <p>Not a bypass in either direction: what gets swallowed is the template's own literal text,
     * which Canoe and the browser read the same way, so nothing is decoded twice and no attacker byte
     * changes position. The effect is data loss — including the loss of a security-bearing attribute
     * such as {@code rel="noopener"} — and it is fail-closed in the sense that the attacker can only
     * remove markup here, never add it.
     *
     * <p>The swallowed region may contain another <em>reference</em> rather than only literal text,
     * and that case carries the whole of the argument above:
     * {@link #aSecondReferenceInsideTheSwallowedRegionKeepsTheSwallowingAttributesContext} is where
     * it is asserted.
     */
    @Test
    public void anEmptyUnquotedValueSwallowsTheNextAttribute() {
        // A suppressed value and a genuinely empty one are the same thing at this level, which is
        // the point: this is not something a payload did.
        for (String value : List.of("javascript:x", "")) {
            CanoeTestSupport.RenderResult result =
                    CanoeTestSupport.render("<img src=$data alt=\"a\">", value);
            assertEquals("<img src= alt=\"a\">", result.output(),
                    () -> "Canoe emits nothing for " + CanoeTestSupport.quote(value));
            assertEquals("alt=\"a\"", result.decodedAttr("img", "src"),
                    "and the browser reads the template's own alt attribute as src's unquoted value");
            assertFalse(result.dom().selectFirst("img").hasAttr("alt"),
                    "so alt is gone - data loss, and the reason a template should quote the value");
        }

        // Quoting the value in the template removes it entirely, which is the documented answer.
        CanoeTestSupport.RenderResult quoted =
                CanoeTestSupport.render("<img src=\"$data\" alt=\"a\">", "javascript:x");
        assertEquals("<img src=\"\" alt=\"a\">", quoted.output());
        assertEquals("a", quoted.decodedAttr("img", "alt"));

        // ...and so does a literal character between the '=' and the reference, which is the same
        // thing F11 was narrow about.
        CanoeTestSupport.RenderResult prefixed =
                CanoeTestSupport.render("<a href=/p$data rel=noopener>x</a>", "javascript:x");
        assertEquals("<a href=/p rel=noopener>x</a>", prefixed.output());
        assertEquals("noopener", prefixed.decodedAttr("a", "rel"),
                "rel survives with its own value because href's is not empty - one literal character"
                        + " in front of the reference is the whole difference");
    }

    /**
     * The half of the swallowing residual that has to be true for it to stay a data-loss bug rather
     * than a routing one: when a <em>second</em> reference sits inside the region an empty unquoted
     * value swallows, Canoe encodes it for the swallowing attribute's classification — which is the
     * classification the browser applies to those bytes too, because both tokenizers agree that the
     * region is one attribute value.
     *
     * <p>Worth asserting rather than arguing, because the shape looks alarming from either end.
     * {@code <a href=$a onclick=$b>} appears to interpolate into an event handler and does not: there
     * is no {@code onclick} attribute in the resulting DOM at all, and {@code $b} is URL-encoded
     * because {@code href} is what the bytes belong to. {@code <a onclick=$a href=$b>} appears to
     * escape a suppressed handler into a live URL and does not: {@code attributeContext} is still
     * {@code ATTR_JS} when {@code $b} is inserted, so {@code $b} is suppressed as well.
     *
     * <p>If Canoe ever re-classified inside a swallowed region — reading {@code onclick=} as a new
     * attribute name where the browser reads it as value bytes — this test fails, and that divergence
     * would be the F10-class defect: two tokenizers disagreeing about where a value ends.
     */
    @Test
    public void aSecondReferenceInsideTheSwallowedRegionKeepsTheSwallowingAttributesContext() {
        // A URL name swallows a handler. $b is encoded by url(), and lands in href's value.
        CanoeTestSupport.RenderResult urlFirst = CanoeTestSupport.render(
                "<a href=$a onclick=$b>x</a>",
                java.util.Map.of("a", "javascript:x", "b", "alert(1)"));
        assertEquals("<a href= onclick=alert(1)>x</a>", urlFirst.output());
        assertFalse(urlFirst.dom().selectFirst("a").hasAttr("onclick"),
                "the browser reads onclick=... as href's unquoted value, so no handler exists");
        assertEquals("onclick=alert(1)", urlFirst.decodedAttr("a", "href"),
                "and the bytes are href's, encoded as href's - the same attribute Canoe classified");

        // A handler name swallows a URL. $b is suppressed, because ATTR_JS is still the context.
        CanoeTestSupport.RenderResult handlerFirst = CanoeTestSupport.render(
                "<a onclick=$a href=$b>x</a>",
                java.util.Map.of("a", "f()", "b", "https://attacker.invalid/x"));
        assertEquals("<a onclick= href=>x</a>", handlerFirst.output(),
                "the second reference is inside onclick's value, so it is suppressed too - a"
                        + " suppressed handler cannot leak its neighbour into a live URL");
        assertFalse(handlerFirst.dom().selectFirst("a").hasAttr("href"),
                "and there is no href attribute either");
    }

    // ------------------------------------------------------------------
    // The diagnostic
    // ------------------------------------------------------------------

    /**
     * The names that still suppress in this position do so for their own recorded reasons, and the
     * unknown-name diagnostic R5 added fires here too — which matters, because "the value went
     * missing" is the complaint R19 exists to stop answering with silence.
     */
    @Test
    public void anUnknownNameStillSuppressesAndStillSaysSo() throws IOException {
        CanoeStateProbe probe = new CanoeStateProbe().feed("<div my-widget-config=");
        assertEquals(Canoe.TAG_ATTR_VALUE_BEFORE, probe.state());
        assertEquals(Canoe.CTX_SUPPRESS, probe.currentContext());
        assertEquals("my-widget-config", probe.unknownAttributeName(),
                "the diagnostic names the attribute in the unquoted position too, not only once a"
                        + " quote has been seen");
    }

    /**
     * Every {@code ATTR_*} classification survives the move to the value-before position unchanged.
     * The point is that R19 routed a state and changed no classification: if a name is encoded
     * differently depending on whether the author wrote quotes, the fix has moved a boundary rather
     * than closed a hole.
     */
    @Test
    public void quotingChangesNothingAboutHowANameIsClassified() throws IOException {
        List<String> names = new ArrayList<>(List.of("title", "id", "href", "src", "onclick",
                "style", "my-widget-config", "aria-label", "data-widget", "action", "srcdoc"));
        for (String name : names) {
            int unquoted = CanoeTestSupport.contextAfter("<a " + name + "=");
            int doubleQuoted = CanoeTestSupport.contextAfter("<a " + name + "=\"");
            int singleQuoted = CanoeTestSupport.contextAfter("<a " + name + "='");
            assertEquals(doubleQuoted, unquoted,
                    () -> name + ": the unquoted position must agree with the double-quoted one");
            assertEquals(doubleQuoted, singleQuoted,
                    () -> name + ": the two quoting styles must agree with each other");
        }
    }
}
