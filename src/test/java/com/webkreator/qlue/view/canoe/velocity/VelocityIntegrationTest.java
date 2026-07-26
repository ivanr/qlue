package com.webkreator.qlue.view.canoe.velocity;

import com.webkreator.qlue.util.HtmlEncoder;
import com.webkreator.qlue.view.canoe.CanoeTestSupport;
import com.webkreator.qlue.view.canoe.corpus.Payloads;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Velocity layer itself: reference forms, directives, value types, and the {@code $_x} bypass.
 *
 * <p>Everything else in this suite is about <em>where</em> a reference sits — which context Canoe's
 * state machine has reached, and therefore which encoder runs. This file is about the other half of
 * the wiring, the half that decides <em>whether the encoder runs at all</em>. That question is
 * settled entirely inside {@code CanoeReferenceInsertionHandler} and by Velocity's own rendering
 * model, and it is independent of context: a bypass bypasses in body text exactly as it does in an
 * event handler.
 *
 * <p>Three things in here are traps rather than tests, and each has a test named after the trap:
 *
 * <ul>
 *   <li><strong>Formal notation defeats the bypass.</strong> {@code $_x.asis($data)} bypasses
 *       encoding and {@code ${_x.asis($data)}} does not, because the handler matches the literal
 *       prefixes {@code $_x.} and {@code $!_x.} against the reference's source text and
 *       {@code ${_x.} starts with neither. A developer switching notation for readability silently
 *       changes the security behaviour of the line, in the safe direction here and in the unsafe
 *       direction for anything that was relying on the raw output.
 *   <li><strong>F12: an interpolated string literal double-encodes.</strong> {@code #set($msg =
 *       "Hello $data")} fires the handler while the <em>main</em> writer is wherever the
 *       {@code #set} happens to sit, so the value is encoded once for that position and again where
 *       {@code $msg} is printed. A plain {@code #set($u = $data)} does not, because a bare reference
 *       assignment never fires {@code referenceInsert()} at all.
 *   <li><strong>{@code #include} is not {@code #parse}.</strong> {@code #include} copies the file's
 *       bytes to the writer without a Velocity parse — but the writer <em>is</em> the {@link
 *       com.webkreator.qlue.view.Canoe}, so the included bytes still steer the state machine. An
 *       included fragment is template text, not data, and the threat model (&sect;2.5) says so.
 * </ul>
 *
 * <h2>Strict mode, and what does not reproduce</h2>
 *
 * <p>The plan's &sect;A.6 says an undefined reference "renders literally as {@code $missing}". That is
 * Velocity's default and <strong>not</strong> Qlue's: {@code buildDefaultVelocityProperties()} sets
 * {@code runtime.strict_mode.enable=true}, so an undefined reference — and a reference whose value is
 * null — is a rendering failure. It is intended Velocity behaviour rather than a Canoe defect, so it
 * is pinned here rather than written up as a finding, and the plan's parenthetical has been
 * corrected. It matters for the suite because it is the reason
 * {@link #anUnboundEncodingToolIsARenderFailureRatherThanASilentEncode} can assert what it does.
 */
public class VelocityIntegrationTest {

    /** The value every reference-form row carries; short, and every character needs escaping. */
    private static final String MARKUP = "<b>";

    /** What {@code htmlWhite()} makes of {@link #MARKUP} in body context. */
    private static final String MARKUP_IN_BODY = "&lt;b&gt;";

    /**
     * The two fragments {@code #parse} and {@code #include} resolve against. Byte-identical on
     * purpose: {@link #includeCopiesTemplateTextAndIsThereforeNotAReferenceAtAll} compares the two
     * directives over the same content, so the only variable is the directive.
     */
    @BeforeAll
    public static void publishFragments() {
        CanoeTestSupport.publishFragment("canoe-fragment.vm", "<em>$data</em>");
        CanoeTestSupport.publishFragment("canoe-raw-fragment.vm", "<em>$data</em>");
    }

    private static Map<String, Object> model(Object... keysAndValues) {
        Map<String, Object> model = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            model.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return model;
    }

    // ------------------------------------------------------------------
    // Reference forms
    // ------------------------------------------------------------------

    /**
     * The six ways of writing a reference all reach {@code referenceInsert()} and all encode.
     *
     * <p>Stated as a set rather than one row per form, because the claim that matters is that none of
     * them is a way out. Velocity's grammar treats {@code $x}, {@code $!x}, {@code ${x}} and
     * {@code $!{x}} as four distinct AST nodes and the quiet forms differ in what they do with a null;
     * a suite that covered only the plain form would be asserting about one of four code paths.
     */
    static Stream<String> referenceForms() {
        return Stream.of("$data", "$!data", "${data}", "$!{data}");
    }

    @ParameterizedTest(name = "{0} is encoded")
    @MethodSource("referenceForms")
    public void everyReferenceFormIsEncoded(String form) {
        assertEquals("<p>" + MARKUP_IN_BODY + "</p>",
                CanoeTestSupport.render("<p>" + form + "</p>", MARKUP).output(),
                () -> form + " must reach CanoeReferenceInsertionHandler like any other reference");
    }

    /**
     * Method calls and property access encode the <em>returned</em> value, not the object.
     *
     * <p>Worth its own test because the handler receives the rendered {@code Object} and calls
     * {@code toString()} on it: a getter that returns markup is indistinguishable, at the point of
     * encoding, from a {@code String} field that holds markup. The one thing that could have gone
     * wrong here — a reference with a method call taking a different path through the event cartridge
     * — does not.
     */
    @Test
    public void methodCallsAndPropertyAccessAreEncodedToo() {
        assertEquals("<p>&lt;i&gt;method&lt;&#47;i&gt;</p>",
                CanoeTestSupport.render("<p>$data.method()</p>", model("data", new Hostile())).output());
        assertEquals("<p>&lt;b&gt;property&lt;&#47;b&gt;</p>",
                CanoeTestSupport.render("<p>$data.property</p>", model("data", new Hostile())).output());
    }

    // ------------------------------------------------------------------
    // Value types
    // ------------------------------------------------------------------

    /**
     * A non-{@code String} object is encoded through its {@code toString()}.
     *
     * <p>This is the shape a developer is most likely to believe is safe — a domain object rather
     * than a string — and it is exactly as encoded as a string, because
     * {@code referenceInsert()} calls {@code arg1.toString()} before handing the value to
     * {@code Canoe.encode()}.
     */
    @Test
    public void aHostileToStringIsEncodedLikeAnyOtherValue() {
        assertEquals("<p>&lt;span&gt;toString&lt;&#47;span&gt;</p>",
                CanoeTestSupport.render("<p>$data</p>", model("data", new Hostile())).output());
    }

    /**
     * Collections, arrays and numbers, each of which reaches {@code toString()} differently.
     *
     * <p>The array row is the interesting one and it is a rendering defect rather than a security
     * one: {@code $data} on a {@code String[]} prints the JVM's default {@code toString()} — the
     * {@code [Ljava.lang.String;@1c32886a} form — where a {@code List} prints its elements. Canoe
     * encodes whichever it is handed, so both are inert; the array's output contains a {@code @} and a
     * {@code .}, both of which {@code htmlWhite()} turns into character references, which is why the
     * assertion below cannot just look for the raw form.
     */
    @Test
    public void collectionsArraysAndNumbersAreAllEncodedThroughToString() {
        assertEquals("<p>&#91;&lt;b&gt;&#44; &amp;&#93;</p>",
                CanoeTestSupport.render("<p>$data</p>", model("data", List.of("<b>", "&"))).output(),
                "a List renders its elements, and every delimiter in the rendering is escaped");

        String array = CanoeTestSupport.render("<p>$data</p>",
                model("data", new String[]{"<b>", "&"})).output();
        assertTrue(array.startsWith("<p>&#91;Ljava&#46;lang&#46;String&#59;&#64;"),
                () -> "an array reaches Object.toString(), and every non-alphanumeric character in"
                        + " that is escaped. Rendered: " + array);
        assertFalse(array.contains("<b>"), "the elements are not rendered at all");

        assertEquals("<p>42</p>", CanoeTestSupport.render("<p>$data</p>", model("data", 42)).output(),
                "digits survive htmlWhite() naked");

        assertEquals("<p>&lt;b&gt;&amp;</p>",
                CanoeTestSupport.render("<p>#foreach($i in $data)$i#end</p>",
                        model("data", new String[]{"<b>", "&"})).output(),
                "iterating the array reaches the elements, and each one is encoded separately");
    }

    /** An empty value produces an empty rendering and no error. */
    @Test
    public void anEmptyValueRendersAsNothing() {
        assertEquals("<p></p>", CanoeTestSupport.render("<p>$data</p>", "").output());
    }

    /**
     * Null and undefined references, under Qlue's {@code runtime.strict_mode.enable}.
     *
     * <p><strong>This is the plan's one claim about &sect;A.6 that does not reproduce.</strong> The
     * plan says {@code $missing} "renders literally as {@code $missing}", which is Velocity's
     * behaviour with strict mode off. Qlue turns strict mode on in
     * {@code buildDefaultVelocityProperties()}, so an undefined reference is a
     * {@code MethodInvocationException} and a null-valued one is a {@code VelocityException}, and
     * <em>the quiet forms do not help</em> for an undefined reference — {@code $!missing} throws
     * exactly as {@code $missing} does, because strict mode's check happens before the quiet form's
     * null handling. The quiet form does help for a reference that exists and is null.
     *
     * <p>Not a finding: this is documented, intended Velocity behaviour that Qlue opts into
     * deliberately, and it fails closed. It is pinned because the rest of the suite depends on it —
     * an unbound {@code $_x} is a rendering failure rather than a silent bypass for this reason and
     * no other.
     */
    @Test
    public void strictModeMakesUndefinedAndNullReferencesRenderingFailures() {
        for (String form : List.of("$missing", "$!missing", "${missing}", "$!{missing}")) {
            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> CanoeTestSupport.render("<p>" + form + "</p>", model()),
                    () -> form + " must fail under strict mode; the plan's '$missing renders"
                            + " literally' is Velocity's non-strict behaviour and not Qlue's");
            assertTrue(rootMessage(thrown).contains("has not been set"),
                    () -> "strict mode's own diagnostic, for " + form + ": " + rootMessage(thrown));
        }

        IllegalStateException nullValue = assertThrows(IllegalStateException.class,
                () -> CanoeTestSupport.render("<p>$data</p>", model("data", null)));
        assertTrue(rootMessage(nullValue).contains("evaluated to null"),
                () -> "a bound-but-null reference has its own diagnostic: " + rootMessage(nullValue));

        assertEquals("<p></p>", CanoeTestSupport.render("<p>$!data</p>", model("data", null)).output(),
                "the quiet form does cover a bound null - which is the distinction between the two"
                        + " failures above, and the reason both are worth pinning");
    }

    // ------------------------------------------------------------------
    // The $_x bypass
    // ------------------------------------------------------------------

    /**
     * The bypass works, in both spellings the handler declares.
     *
     * <p>Per &sect;2.5 a template author who calls this is outside the threat model; what is inside it
     * is that the bypass does what it says, because a bypass that silently stopped bypassing would
     * push developers to worse workarounds.
     */
    @Test
    public void theTwoDeclaredBypassPrefixesBypass() {
        assertEquals("<p><b></p>",
                CanoeTestSupport.render("<p>$_x.asis($data)</p>", MARKUP).output(),
                "$_x. is SAFE_REFERENCE_PREFIX1");
        assertEquals("<p><b></p>",
                CanoeTestSupport.render("<p>$!_x.asis($data)</p>", MARKUP).output(),
                "$!_x. is SAFE_REFERENCE_PREFIX2");
    }

    /**
     * <strong>The trap.</strong> Formal notation is not a bypass, and nothing says so at the point of
     * use.
     *
     * <p>{@code CanoeReferenceInsertionHandler.referenceInsert()} is handed the reference's
     * <em>source text</em> and tests {@code startsWith("$_x.")} and {@code startsWith("$!_x.")}. The
     * formal spellings begin <code>${_x.</code> and <code>$!{_x.</code>; neither starts with either
     * prefix, so the handler does not return early and Canoe encodes the tool's output a second time.
     *
     * <p>The direction of the change is safe — more encoding, not less — but the failure is silent
     * and the two spellings are interchangeable everywhere else in Velocity. What a developer sees is
     * that wrapping a working reference in braces breaks the page's markup, with no diagnostic; the
     * likely next step is to reach for something worse, which is the same dynamic F12 creates.
     *
     * <p>The second assertion is the one that makes this a test rather than an anecdote: the formal
     * form's output is byte-identical to the output of not using the tool at all. The bypass is not
     * partially applied, it is absent.
     */
    @Test
    public void formalNotationSilentlyDefeatsTheBypassBecauseThePrefixIsMatchedLiterally() {
        assertEquals("<p>&lt;b&gt;</p>",
                CanoeTestSupport.render("<p>${_x.asis($data)}</p>", MARKUP).output(),
                "${_x. does not start with $_x.");
        assertEquals("<p>&lt;b&gt;</p>",
                CanoeTestSupport.render("<p>$!{_x.asis($data)}</p>", MARKUP).output(),
                "$!{_x. does not start with $!_x. either - the same trap in the quiet spelling,"
                        + " which the review does not mention");

        assertEquals(CanoeTestSupport.render("<p>$data</p>", MARKUP).output(),
                CanoeTestSupport.render("<p>${_x.asis($data)}</p>", MARKUP).output(),
                "and the result is byte-identical to never having called the tool: asis() returned"
                        + " the raw value and Canoe encoded it exactly as it encodes anything else");
    }

    /**
     * A longer name beginning with the same letters is not a bypass.
     *
     * <p>{@code $_xy.} shares three characters with {@code $_x.} and differs at the fourth, which is
     * the dot. Worth an assertion because a prefix match on a name is the shape that most often
     * turns out to be a prefix match on a <em>prefix of a name</em> — which is F1's mechanism one
     * level up.
     */
    @Test
    public void aLongerToolNameIsNotABypass() {
        assertEquals("<p>&lt;b&gt;</p>",
                CanoeTestSupport.render("<p>$_xy.asis($data)</p>",
                        model("data", MARKUP, "_xy", new HtmlEncoder())).output(),
                "$_xy. differs from $_x. at the dot, so the handler encodes normally");
    }

    /**
     * The bypass is a property of the <em>reference name</em>, not of the method called on it.
     *
     * <p>{@code $_x.html($data)} bypasses Canoe too — the handler returns early before it looks at
     * anything but the prefix — so what reaches the page is {@code HtmlEncoder.html()}'s output and
     * not {@code html()} applied twice. That is the intent, and it is the reason the tool is useful:
     * a developer who wants a specific encoder gets that encoder and only that one.
     *
     * <p>The assertion is a comparison rather than a literal, because "was it encoded once or twice"
     * is the whole question and only a comparison asks it.
     */
    @Test
    public void theBypassIsDecidedByTheReferenceNameAndNotByTheMethod() {
        String once = HtmlEncoder.html(MARKUP);
        assertEquals("<p>" + once + "</p>",
                CanoeTestSupport.render("<p>$_x.html($data)</p>", MARKUP).output(),
                "the tool's own html() output reaches the page unmodified");
        assertNotEquals(HtmlEncoder.html(once),
                CanoeTestSupport.render("<p>$_x.html($data)</p>", MARKUP).output()
                        .replace("<p>", "").replace("</p>", ""),
                "and is not encoded a second time");
    }

    /**
     * A page that has not called {@code allowDirectOutput()} leaves {@code $_x} unbound, and under
     * strict mode that is a rendering failure rather than a silent encode.
     *
     * <p>Fail-closed and loud, which is the right behaviour and is not obvious: the handler's
     * early return is keyed on the reference's <em>text</em>, so it fires whether or not the tool
     * exists. Had strict mode been off, {@code $_x.asis($data)} on a page without direct output would
     * have rendered the literal text {@code $_x.asis($data)} into the response.
     */
    @Test
    public void anUnboundEncodingToolIsARenderFailureRatherThanASilentEncode() {
        assertThrows(IllegalStateException.class,
                () -> CanoeTestSupport.render("<p>$_x.asis($data)</p>", model("data", MARKUP),
                        CanoeTestSupport.RenderOptions.defaults().withoutEncodingTool()),
                "with $_x unbound, strict mode fails the render");
    }

    // ------------------------------------------------------------------
    // Directives
    // ------------------------------------------------------------------

    /**
     * Every directive that can carry a reference carries it through the handler unchanged.
     *
     * <p>Each row renders {@link #MARKUP} through a different directive and must produce the same
     * {@code htmlWhite()} output as a bare reference would. The point is not that any single one is
     * surprising; it is that the event cartridge is attached to the <em>context</em>, so anything
     * that renders through that context — a macro body, a parsed fragment, an evaluated string —
     * fires the handler, and a directive that did not would be a hole with no diagnostic.
     */
    static Stream<org.junit.jupiter.params.provider.Arguments> directives() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("if",
                        "#if($data)<p>$data</p>#end", "<p>" + MARKUP_IN_BODY + "</p>"),
                org.junit.jupiter.params.provider.Arguments.of("foreach",
                        "<ul>#foreach($i in $items)<li>$i</li>#end</ul>",
                        "<ul><li>" + MARKUP_IN_BODY + "</li><li>&amp;</li></ul>"),
                org.junit.jupiter.params.provider.Arguments.of("macro",
                        "#macro(cell $v)<td>$v</td>#end<table><tr>#cell($data)</tr></table>",
                        "<table><tr><td>" + MARKUP_IN_BODY + "</td></tr></table>"),
                org.junit.jupiter.params.provider.Arguments.of("parse",
                        "<p>#parse('canoe-fragment.vm')</p>",
                        "<p><em>" + MARKUP_IN_BODY + "</em></p>"),
                org.junit.jupiter.params.provider.Arguments.of("evaluate-reference",
                        "#evaluate($fragment)", "<p>" + MARKUP_IN_BODY + "</p>"),
                org.junit.jupiter.params.provider.Arguments.of("evaluate-literal",
                        "#evaluate('<p>$data</p>')", "<p>" + MARKUP_IN_BODY + "</p>"),
                org.junit.jupiter.params.provider.Arguments.of("set-plain",
                        "#set($u = $data)<p>$u</p>", "<p>" + MARKUP_IN_BODY + "</p>"));
    }

    @ParameterizedTest(name = "#{0}")
    @MethodSource("directives")
    public void everyDirectiveThatCanCarryAReferenceStillEncodesIt(String name, String template,
                                                                   String expected) {
        Map<String, Object> model = model(
                "data", MARKUP,
                "items", List.of("<b>", "&"),
                "fragment", "<p>$data</p>");
        assertEquals(expected, CanoeTestSupport.render(template, model).output(),
                () -> "#" + name + " must not be a way past the reference insertion handler");
    }

    /**
     * {@code #include} is the one directive that does not fire the handler, and it is not a hole.
     *
     * <p>{@code #include} copies the resource's bytes to the writer with no Velocity parse, so the
     * fragment's own {@code $data} arrives as the four literal characters and no reference is
     * inserted. The bytes still pass through {@link com.webkreator.qlue.view.Canoe}, so an included
     * fragment can steer the state machine exactly as inline template text can — which is correct
     * under &sect;2.5, where the attacker controls data and never the template, and worth stating
     * because "included content" is the kind of thing that gets treated as data by mistake.
     */
    @Test
    public void includeCopiesTemplateTextAndIsThereforeNotAReferenceAtAll() {
        assertEquals("<p><em>$data</em></p>",
                CanoeTestSupport.render("<p>#include('canoe-raw-fragment.vm')</p>", MARKUP).output(),
                "#include does not parse, so $data is literal text and the handler never fires");
        assertEquals("<p><em>&lt;b&gt;</em></p>",
                CanoeTestSupport.render("<p>#parse('canoe-fragment.vm')</p>", MARKUP).output(),
                "...whereas #parse does, on byte-identical fragment content. The two directives"
                        + " differ in exactly one thing and it is not visible at the call site");
    }

    // ------------------------------------------------------------------
    // F12
    // ------------------------------------------------------------------

    /**
     * <strong>F12</strong>, as the golden the review records.
     *
     * <p>{@code referenceInsert()} asks {@code qlueWriter.currentContext()} — the position of the
     * <em>main</em> output stream at the instant the {@code #set} runs. Velocity renders an
     * interpolated string literal into an internal writer, but the event cartridge is attached to the
     * context, so the handler still fires and still consults the main stream. The value is therefore
     * encoded for wherever the {@code #set} sat, and encoded again where {@code $msg} is printed.
     *
     * <p>{@code &amp;lt&#59;} is the visible symptom: {@code htmlWhite()} produced {@code &lt;},
     * {@code htmlWhite()} then escaped that string's ampersand and semicolon.
     */
    @Test
    public void anInterpolatedStringLiteralIsEncodedTwice() {
        assertEquals("<p>Hello &amp;lt&#59;b&amp;gt&#59;</p>",
                CanoeTestSupport.render("#set($msg = \"Hello $data\")<p>$msg</p>", MARKUP).output(),
                "F12, exactly as the review records it");
    }

    /**
     * F12's true scope: only interpolated string literals, and the plain assignment is correct.
     *
     * <p>A bare {@code #set($u = $data)} never fires {@code referenceInsert()} — there is no
     * insertion, only an assignment — so the value is untouched until {@code $u} is printed, and it
     * is then encoded once for the position it is printed at. This is the half the finding's original
     * text got wrong by omission, and it is what bounds the defect: it is not "references inside
     * {@code #set}", it is "references inside an interpolated string literal", wherever that literal
     * appears.
     */
    @Test
    public void aPlainSetAssignmentSingleEncodesForThePositionTheValueIsPrintedAt() {
        assertEquals("<a title=\"&lt;b&gt;\">x</a>",
                CanoeTestSupport.render("#set($u = $data)<a title=\"$u\">x</a>", MARKUP).output(),
                "encoded once, for the attribute it is printed into and not for where the #set sat");
        assertEquals("<a title=\"x&amp;lt&#59;b&amp;gt&#59;\">x</a>",
                CanoeTestSupport.render("#set($u = \"x$data\")<a title=\"$u\">x</a>", MARKUP).output(),
                "...and the interpolated form of the same assignment double-encodes, one character"
                        + " of template text apart");
    }

    /**
     * F12 encoding for the wrong position can also mean encoding for a <em>suppressed</em> position,
     * in which case the value disappears entirely.
     *
     * <p>The finding says this ("inside a tag it means the value silently becomes empty") and it is
     * worth an assertion because it is the failure mode a developer meets first: the {@code #set} is
     * moved into a {@code <script>} block or an event handler for tidiness, and the string it builds
     * is empty from then on with no error anywhere.
     */
    @Test
    public void anInterpolatedSetInsideAScriptOrHandlerSilentlyProducesNothing() {
        assertEquals("<script></script><p>x</p>",
                CanoeTestSupport.render("<script>#set($m = \"x$data\")</script><p>$m</p>",
                        MARKUP).output(),
                "F12: the reference was encoded for CTX_JS, which is the empty string, so $m is"
                        + " the literal prefix and nothing else");
        assertEquals("<script></script><p>&lt;b&gt;</p>",
                CanoeTestSupport.render("<script>#set($m = $data)</script><p>$m</p>",
                        MARKUP).output(),
                "...and the plain assignment in the same place is correct, which is the contrast"
                        + " that makes the defect hard to spot");
    }

    /**
     * The one direction F12 moves that is worth recording as a mitigation rather than a defect: the
     * double encoding neutralises a payload aimed at an attribute Canoe classifies as plain text
     * when it should not.
     *
     * <p><strong>Half-inverted by R4.</strong> This was
     * {@code doubleEncodingAccidentallyNeutralisesAnUnrecognisedHandler}, and the sink was
     * {@code onmouseenter} — F2's territory, the largest vulnerability class in the review.
     * {@code onmouseenter} was not in Canoe's {@code on*} table, so it was {@code ATTR_HTML} and its
     * value was {@code html()}-encoded, which F2 showed was not enough: the parser decodes exactly
     * once and the attacker's apostrophe became an apostrophe inside a JavaScript string literal.
     * Routing the same value through an interpolated {@code #set} first encoded it twice, so the one
     * decode left the literal text {@code &#39;} and the string literal was never closed.
     *
     * <p>R4 suppresses every {@code on*} value, so the handler half of the template is inert by
     * design on both paths and the accident has nothing left to neutralise there. The first
     * assertion below is inverted to say exactly that. <strong>Trap 2 in the plan's &sect;1 is not
     * closed by it</strong>: the same accident still covers F3's unrecognised URL-bearing names,
     * which is R5 and R6's territory, so the sink moves to {@code formaction} and the warning stands
     * unchanged — a fix to F12 before those land turns this template from safe to injectable with no
     * other change.
     *
     * <p>This is not a reason to keep F12. It is here because a suite that only recorded F12 as
     * double encoding would let that land unremarked.
     */
    @Test
    public void doubleEncodingAccidentallyNeutralisesAnUnrecognisedUrlAttribute() {
        String payload = Payloads.QUOTE_SINGLE_BREAKOUT.value();

        // R4: the handler this test used to be about is suppressed on both paths now, so the
        // double encoding neither helps nor is needed.
        CanoeTestSupport.RenderResult handlerDirect = CanoeTestSupport.render(
                "<div onmouseenter=\"v('$data')\">x</div>", payload);
        assertEquals("v('')", handlerDirect.decodedAttr("div", "onmouseenter"),
                "R4: onmouseenter is classified by the on-prefix rule, so nothing is emitted and"
                        + " there is no payload for F12's double encoding to neutralise");

        // ...and the class the accident still covers, which is why trap 2 stands until R5 and R6.
        String urlPayload = Payloads.JS_URL.value();
        CanoeTestSupport.RenderResult direct = CanoeTestSupport.render(
                "<button formaction=\"$data\">go</button>", urlPayload);
        assertEquals(urlPayload, direct.decodedAttr("button", "formaction"),
                () -> "F3: formaction is not a name Canoe recognises, so html() applies and the"
                        + " parser decodes the attacker's URL straight back. Decoded: "
                        + direct.decodedAttr("button", "formaction"));

        CanoeTestSupport.RenderResult viaSet = CanoeTestSupport.render(
                "#set($v = \"$data\")<button formaction=\"$v\">go</button>", urlPayload);
        assertFalse(viaSet.decodedAttr("button", "formaction").contains("javascript:"),
                () -> "F12's double encoding survives the parser's single decode, so the same"
                        + " payload arrives with no scheme colon at all. Decoded: "
                        + viaSet.decodedAttr("button", "formaction"));
        assertTrue(viaSet.decodedAttr("button", "formaction").contains("&#58;"),
                "the decoded value still carries a character reference where the colon was, which"
                        + " is what inert looks like here");
    }

    // ------------------------------------------------------------------
    // Several references in one template
    // ------------------------------------------------------------------

    /**
     * Two references inside one tag are encoded independently, for their own attributes.
     *
     * <p>The handler is stateless and consults the writer each time, so this is expected — but it is
     * the assertion that would fail if the context were ever cached per tag or per render, which is a
     * plausible optimisation of a char-by-char parser and would be catastrophic here.
     */
    @Test
    public void twoReferencesInOneTagGetTheirOwnContexts() {
        assertEquals("<a href=\"/p?q=1\" title=\"&lt;b&gt;\">x</a>",
                CanoeTestSupport.render("<a href=\"$a\" title=\"$b\">x</a>",
                        model("a", "/p?q=1", "b", MARKUP)).output(),
                "href gets url(), which passes '/', '?' and '=' naked, and title gets html(),"
                        + " which would have escaped all three - in the same tag");

        assertEquals("<a href=\"/p\" onclick=\"\">x</a>",
                CanoeTestSupport.render("<a href=\"$a\" onclick=\"$b\">x</a>",
                        model("a", "/p", "b", MARKUP)).output(),
                "and a recognised handler in the same tag is suppressed while the URL beside it is"
                        + " not");
    }

    /**
     * A reference either side of a state transition: an attribute value, then element text.
     *
     * <p>{@code url()} and {@code htmlWhite()} produce visibly different output for the same input,
     * which is what makes this readable as evidence that the context moved rather than as two
     * unrelated encodings.
     */
    @Test
    public void aReferenceEitherSideOfAStateTransitionSeesTheTransition() {
        assertEquals("<a href=\"/a/b\">&#47;a&#47;b</a>",
                CanoeTestSupport.render("<a href=\"$data\">$second</a>",
                        model("data", "/a/b", "second", "/a/b")).output(),
                "url() passes the slash naked and htmlWhite() emits a character reference for it;"
                        + " the same value looks different on the two sides of the '>', which is"
                        + " what makes this readable as evidence that the context moved");
    }

    // ------------------------------------------------------------------
    // Auto-escaping off
    // ------------------------------------------------------------------

    /**
     * With {@code setAutoEscaping(false)} the cartridge is never attached, so no reference is
     * encoded anywhere — including inside directives and interpolated string literals.
     *
     * <p>The parameterised sweep is over the same forms and directives as above, because "auto
     * escaping is off" has to mean off for all of them; a form that still encoded would be a
     * different defect from a form that still bypassed.
     */
    @ParameterizedTest(name = "auto-escaping off: {0}")
    @ValueSource(strings = {
            "<p>$data</p>",
            "<p>$!data</p>",
            "<p>${data}</p>",
            "<a title=\"$data\">x</a>",
            "<a href=\"$data\">x</a>",
            "<a onclick=\"$data\">x</a>",
            "<div style=\"$data\">x</div>",
            "<script>var x = $data;</script>",
            "#if($data)<p>$data</p>#end",
            "#set($m = \"$data\")<p>$m</p>",
            "#set($m = $data)<p>$m</p>"})
    public void autoEscapingOffMeansNoEncodingAnywhere(String template) {
        String rendered = CanoeTestSupport.render(template, model("data", MARKUP),
                CanoeTestSupport.RenderOptions.defaults().withoutAutoEscaping()).output();
        assertTrue(rendered.contains(MARKUP),
                () -> "with the cartridge detached the raw value must reach the output for "
                        + template + ", but got " + CanoeTestSupport.quote(rendered));
    }

    /**
     * Auto-escaping off does <em>not</em> switch Canoe off: the state machine still parses every
     * byte, and still rejects what it rejects.
     *
     * <p>{@code render()} wraps the response writer in a {@link com.webkreator.qlue.view.Canoe}
     * unconditionally and only the event cartridge is conditional, so a template that raises an
     * encoding error raises it with auto-escaping off too. Worth pinning because "turn off auto
     * escaping" reads like "turn off Canoe", and the availability defects in &sect;3's table survive
     * the switch.
     */
    @Test
    public void autoEscapingOffStillRunsTheStateMachine() {
        CanoeTestSupport.RenderResult result = CanoeTestSupport.render("<p>ok</p><br/>", model(),
                CanoeTestSupport.RenderOptions.defaults().withoutAutoEscaping());
        assertTrue(result.isError(),
                () -> "Canoe wraps the writer whether or not the cartridge is attached; got "
                        + result);
        assertTrue(result.errorMessage().contains("Invalid character after tag name"),
                () -> "the same rejection as with auto-escaping on: " + result.errorMessage());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static String rootMessage(Throwable thrown) {
        Throwable current = thrown;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return String.valueOf(current.getMessage());
    }

    /**
     * A value object whose every accessor returns markup, so that "the object was encoded" and "the
     * object's string was encoded" cannot be confused.
     */
    public static final class Hostile {

        public String getProperty() {
            return "<b>property</b>";
        }

        public String method() {
            return "<i>method</i>";
        }

        @Override
        public String toString() {
            return "<span>toString</span>";
        }
    }
}
