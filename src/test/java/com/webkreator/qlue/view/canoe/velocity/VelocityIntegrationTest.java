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
 * <p>One thing in here is a trap rather than a test, and it has a test named after the trap. Two
 * others were traps until R23 and R24 closed them, and each has an inverted test carrying the
 * mechanism:
 *
 * <ul>
 *   <li><strong>formal notation used to defeat the bypass</strong>, because the handler matched only
 *       the literal prefixes {@code $_x.} and {@code $!_x.} against the reference's source text and
 *       <code>${_x.</code> starts with neither, so a developer switching notation for readability
 *       silently changed the security behaviour of the line. All four spellings now bypass;
 *       {@code everySpellingOfTheBypassBypassesIncludingFormalNotation} is the inverted test.
 *   <li><strong>F12: an interpolated string literal double-encoded.</strong> {@code #set($msg =
 *       "Hello $data")} fires the handler while the <em>main</em> writer is wherever the
 *       {@code #set} happens to sit, so the value was encoded once for that position and again where
 *       {@code $msg} is printed. A plain {@code #set($u = $data)} never did, because a bare reference
 *       assignment does not fire {@code referenceInsert()} at all. R24 made the handler detect the
 *       nested render and return the value untouched, so it is encoded once, where it is printed;
 *       {@code anInterpolatedStringLiteralIsEncodedOnceAtThePositionItIsPrintedAt} is the inverted
 *       test and the section below it is the rest of the consequences. Deferring stops at the three
 *       directives that never print the string they asked the literal for — {@code #evaluate},
 *       which compiles it, and {@code #parse} and {@code #include}, which resolve it to a file —
 *       because there is no later encoding for those to defer to.
 * </ul>
 *
 * <ul>
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
     * The bypass works, in the two short spellings.
     *
     * <p>Formerly {@code theTwoDeclaredBypassPrefixesBypass}, renamed by R23: the handler declared
     * two prefixes when this was written and now declares four, so "the two declared prefixes" named
     * the wrong set. The other two are
     * {@link #everySpellingOfTheBypassBypassesIncludingFormalNotation}, kept separate because that is
     * where the inverted mechanism lives; between them the four spellings are covered once each.
     *
     * <p>Per &sect;2.5 a template author who calls this is outside the threat model; what is inside it
     * is that the bypass does what it says, because a bypass that silently stopped bypassing would
     * push developers to worse workarounds.
     */
    @Test
    public void theTwoShortBypassPrefixesBypass() {
        assertEquals("<p><b></p>",
                CanoeTestSupport.render("<p>$_x.asis($data)</p>", MARKUP).output(),
                "$_x. is SAFE_REFERENCE_PREFIX1");
        assertEquals("<p><b></p>",
                CanoeTestSupport.render("<p>$!_x.asis($data)</p>", MARKUP).output(),
                "$!_x. is SAFE_REFERENCE_PREFIX2");
    }

    /**
     * All four of Velocity's reference spellings bypass, including the two formal ones.
     *
     * <p>Formerly {@code formalNotationSilentlyDefeatsTheBypassBecauseThePrefixIsMatchedLiterally},
     * inverted by R23. The mechanism it pinned:
     * {@code CanoeReferenceInsertionHandler.referenceInsert()} is handed the reference's
     * <em>source text</em>, and the handler tested only {@code startsWith("$_x.")} and
     * {@code startsWith("$!_x.")}. The formal spellings begin <code>${_x.</code> and
     * <code>$!{_x.</code>; neither started with either prefix, so the handler did not return early
     * and Canoe encoded the tool's output a second time. The direction was safe — more encoding, not
     * less — but the failure was silent, and the two spellings are interchangeable everywhere else in
     * Velocity. What a developer saw was that wrapping a working reference in braces broke the page's
     * markup, with no diagnostic; the likely next step is to reach for something worse, which is the
     * same dynamic F12 creates. The old test's sharpest assertion was that the formal form's output
     * was byte-identical to never having called the tool at all: the bypass was not partially
     * applied, it was absent.
     *
     * <p>R23 added <code>${_x.</code> and <code>$!{_x.</code> to the prefix list, so this now asserts
     * the opposite. The literals Velocity 2.4.1 actually passes were measured rather than assumed —
     * <code>$_x.asis($data)</code>, <code>$!_x.asis($data)</code>, <code>${_x.asis($data)}</code> and
     * <code>$!{_x.asis($data)}</code>, each the reference's source text verbatim, braces included.
     *
     * <p>The last assertion inverts the old one's shape: the formal form's output is now
     * byte-identical to the short form's, and <em>differs</em> from not using the tool at all.
     */
    @Test
    public void everySpellingOfTheBypassBypassesIncludingFormalNotation() {
        assertEquals("<p><b></p>",
                CanoeTestSupport.render("<p>${_x.asis($data)}</p>", MARKUP).output(),
                "${_x. is SAFE_REFERENCE_PREFIX3");
        assertEquals("<p><b></p>",
                CanoeTestSupport.render("<p>$!{_x.asis($data)}</p>", MARKUP).output(),
                "$!{_x. is SAFE_REFERENCE_PREFIX4 - the quiet formal spelling, which the review does"
                        + " not mention and which failed the same way");

        assertEquals(CanoeTestSupport.render("<p>$_x.asis($data)</p>", MARKUP).output(),
                CanoeTestSupport.render("<p>${_x.asis($data)}</p>", MARKUP).output(),
                "the formal form is byte-identical to the short form: braces are notation, not"
                        + " semantics");
        assertNotEquals(CanoeTestSupport.render("<p>$data</p>", MARKUP).output(),
                CanoeTestSupport.render("<p>${_x.asis($data)}</p>", MARKUP).output(),
                "and no longer byte-identical to never having called the tool, which is what the"
                        + " former name recorded");
    }

    /**
     * Whitespace inside the braces is not a fifth spelling, because it is not a reference.
     *
     * <p>The natural next question after R23 is whether <code>${ _x.asis($v) }</code> is a spelling
     * the four-prefix list still misses. It is not: Velocity's lexer enters the reference state only
     * on the exact token <code>${</code> or <code>$!{</code>, so a space or newline after the brace
     * makes the whole construct literal template text. The handler never fires for the tool call at
     * all — only for the inner {@code $data}, which is an ordinary reference and is encoded — and
     * what reaches the page includes the braces the author typed.
     *
     * <p>Asserted rather than reasoned about, because "is there a spelling we missed" is exactly the
     * question a prefix list has to be able to answer, and because the answer is a property of
     * Velocity's grammar that a version bump could change.
     */
    @Test
    public void whitespaceInsideTheBracesIsNotAReferenceAtAll() {
        assertEquals("<p>${ _x.asis(&lt;b&gt;) }</p>",
                CanoeTestSupport.render("<p>${ _x.asis($data) }</p>", MARKUP).output(),
                "the braces are text; only the inner $data is a reference, and it is encoded");
        assertEquals("<p>$!{ _x.asis(&lt;b&gt;) }</p>",
                CanoeTestSupport.render("<p>$!{ _x.asis($data) }</p>", MARKUP).output(),
                "the quiet spelling behaves the same way");
    }

    /**
     * A longer name beginning with the same letters is not a bypass — in any of the four spellings.
     *
     * <p>{@code $_xy.} shares three characters with {@code $_x.} and differs at the fourth, which is
     * the dot. Worth an assertion because a prefix match on a name is the shape that most often
     * turns out to be a prefix match on a <em>prefix of a name</em> — which is F1's mechanism one
     * level up.
     *
     * <p>R23 doubled the size of the list this has to hold for, and the risk it guards is the
     * asymmetric one: a bypass that over-matches emits attacker data unencoded. The dot is what
     * makes the list safe, so every prefix is checked against a name that starts with
     * {@code CanoeReferenceInsertionHandler.SAFE_REFERENCE_NAME} and continues.
     */
    @Test
    public void aLongerToolNameIsNotABypass() {
        Map<String, Object> model = model("data", MARKUP, "_xy", new HtmlEncoder(),
                "_xtra", new HtmlEncoder());

        assertEquals("<p>&lt;b&gt;</p>",
                CanoeTestSupport.render("<p>$_xy.asis($data)</p>", model).output(),
                "$_xy. differs from $_x. at the dot, so the handler encodes normally");
        assertEquals("<p>&lt;b&gt;</p>",
                CanoeTestSupport.render("<p>$!_xy.asis($data)</p>", model).output(),
                "and the quiet spelling differs from $!_x. in the same place");
        assertEquals("<p>&lt;b&gt;</p>",
                CanoeTestSupport.render("<p>${_xy.asis($data)}</p>", model).output(),
                "${_xy. differs from ${_x. at the dot - R23's new prefix must not widen the match");
        assertEquals("<p>&lt;b&gt;</p>",
                CanoeTestSupport.render("<p>$!{_xtra.asis($data)}</p>", model).output(),
                "$!{_xtra. differs from $!{_x. at the dot too, with more characters after it");

        assertEquals("<p><b>|&lt;b&gt;</p>",
                CanoeTestSupport.render("<p>${_x.asis($data)}|${_xy}</p>",
                        model("data", MARKUP, "_xy", MARKUP)).output(),
                "a bare ${_xy} beside a real formal bypass is still encoded: the bypass is decided"
                        + " per reference, not per template");
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
                        "#set($u = $data)<p>$u</p>", "<p>" + MARKUP_IN_BODY + "</p>"),
                // Added by R24. Before it, this row would have read
                // "<p>&amp;lt&#59;b&amp;gt&#59;</p>" and would have been the odd one out in a table
                // whose whole point is that every directive produces what a bare reference does.
                org.junit.jupiter.params.provider.Arguments.of("set-interpolated",
                        "#set($u = \"$data\")<p>$u</p>", "<p>" + MARKUP_IN_BODY + "</p>"));
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
     * <strong>F12, inverted by R24.</strong> Formerly
     * {@code anInterpolatedStringLiteralIsEncodedTwice}.
     *
     * <p>The mechanism it pinned: {@code referenceInsert()} asked
     * {@code qlueWriter.currentContext()} — the position of the <em>main</em> output stream at the
     * instant the {@code #set} ran. Velocity renders an interpolated string literal into an internal
     * writer, but the event cartridge is attached to the context, so the handler still fired and
     * still consulted the main stream. The value was encoded for wherever the {@code #set} sat, and
     * encoded again where {@code $msg} was printed. The review's golden was
     * {@code <p>Hello &amp;amp;lt&amp;#59;b&amp;amp;gt&amp;#59;</p>}, and {@code &amp;amp;lt&amp;#59;}
     * was the visible symptom: {@code htmlWhite()} produced {@code &amp;lt;}, and {@code htmlWhite()}
     * then escaped that string's ampersand and semicolon.
     *
     * <p>R24 gave the handler a way to know that the writer is not Canoe — an
     * {@code ASTStringLiteral} frame below it on the call stack — and to hand the value back
     * untouched when it is. The value is then encoded exactly once, at the {@code <p>}, which is the
     * position Canoe genuinely knows.
     *
     * <p><strong>The template author's own literal text is encoded too</strong>, which is the part
     * worth asserting as bytes rather than as a substring. The whole string {@code $msg} is data by
     * the time it is printed, so {@code Hello } goes through the encoder with the value. In body
     * context that is invisible — {@code htmlWhite()} passes the space through — but in an attribute
     * {@code html()} emits {@code &amp;#32;} for it, which is why the second assertion exists. Both
     * render identically in a browser, and neither is a substring match that would still pass if the
     * encoding moved.
     */
    @Test
    public void anInterpolatedStringLiteralIsEncodedOnceAtThePositionItIsPrintedAt() {
        assertEquals("<p>Hello &lt;b&gt;</p>",
                CanoeTestSupport.render("#set($msg = \"Hello $data\")<p>$msg</p>", MARKUP).output(),
                "R24: encoded once, at the <p>, and not at all where the #set ran");

        assertEquals("<a title=\"Hello&#32;&lt;b&gt;\">x</a>",
                CanoeTestSupport.render("#set($msg = \"Hello $data\")<a title=\"$msg\">x</a>",
                        MARKUP).output(),
                "the author's own 'Hello ' is encoded with the value, because by the time the string"
                        + " is printed it is one value: html() emits &#32; for the space, which a"
                        + " browser renders as a space");

        assertEquals(CanoeTestSupport.render("<p>$data</p>", MARKUP).output(),
                CanoeTestSupport.render("#set($msg = \"$data\")<p>$msg</p>", MARKUP).output(),
                "and a literal that is nothing but the reference is now byte-identical to the bare"
                        + " reference, which is the shape of the whole fix");
    }

    /**
     * F12's true scope, and after R24 the two spellings finally agree.
     *
     * <p>A bare {@code #set($u = $data)} never fires {@code referenceInsert()} — there is no
     * insertion, only an assignment — so the value is untouched until {@code $u} is printed, and it
     * is then encoded once for the position it is printed at. This is the half the finding's original
     * text got wrong by omission, and it is what bounded the defect: it was not "references inside
     * {@code #set}", it was "references inside an interpolated string literal", wherever that literal
     * appeared. The two spellings are one character of template text apart, and before R24 the
     * interpolated one double-encoded to
     * {@code <a title="x&amp;amp;lt&amp;#59;b&amp;amp;gt&amp;#59;">} — which is the assertion this
     * test used to make, and the reason a developer could not tell the two apart by reading them.
     */
    @Test
    public void aPlainSetAssignmentSingleEncodesForThePositionTheValueIsPrintedAt() {
        assertEquals("<a title=\"&lt;b&gt;\">x</a>",
                CanoeTestSupport.render("#set($u = $data)<a title=\"$u\">x</a>", MARKUP).output(),
                "encoded once, for the attribute it is printed into and not for where the #set sat");
        assertEquals("<a title=\"x&lt;b&gt;\">x</a>",
                CanoeTestSupport.render("#set($u = \"x$data\")<a title=\"$u\">x</a>", MARKUP).output(),
                "...and R24 makes the interpolated form of the same assignment agree with it,"
                        + " character for character apart from the template's own 'x'");
    }

    /**
     * <strong>Inverted by R24.</strong> Formerly
     * {@code anInterpolatedSetInsideAScriptOrHandlerSilentlyProducesNothing}.
     *
     * <p>F12 encoding for the wrong position could also mean encoding for a <em>suppressed</em>
     * position, in which case the value disappeared entirely. The finding said so ("inside a tag it
     * means the value silently becomes empty") and it was worth an assertion because it was the
     * failure mode a developer met first: the {@code #set} was moved into a {@code <script>} block or
     * an event handler for tidiness, and the string it built was empty from then on with no error
     * anywhere. The old golden was {@code <script></script><p>x</p>} — the literal prefix and nothing
     * else, because the reference had been encoded for {@code CTX_JS}, which is the empty string.
     *
     * <p>It inverts because the {@code #set} no longer consults the position it sits at. Where the
     * literal is built is now irrelevant to what it contains; only where it is printed decides how
     * it is encoded. The {@code <script>} block stays empty because the {@code #set} directive writes
     * nothing to the page, which is the same before and after.
     */
    @Test
    public void anInterpolatedSetInsideAScriptNoLongerLosesItsValue() {
        assertEquals("<script></script><p>x&lt;b&gt;</p>",
                CanoeTestSupport.render("<script>#set($m = \"x$data\")</script><p>$m</p>",
                        MARKUP).output(),
                "R24: the value survives the suppressed position it was built in, and is encoded"
                        + " for the body context it is printed into");
        assertEquals("<script></script><p>&lt;b&gt;</p>",
                CanoeTestSupport.render("<script>#set($m = $data)</script><p>$m</p>",
                        MARKUP).output(),
                "...and the plain assignment in the same place is unchanged: the contrast that made"
                        + " the defect hard to spot is now no contrast at all");
    }

    /**
     * <strong>Retired by R24, and replaced by this.</strong> Formerly
     * {@code doubleEncodingNoLongerCoversAnyClassOfMissingClassification}, and before that
     * {@code …AnUnrecognisedUrlAttribute} and {@code …AnUnrecognisedHandler}.
     *
     * <p>That test's subject was the one direction F12 moved that was worth recording as a
     * mitigation rather than a defect: the double encoding neutralised a payload aimed at an
     * attribute Canoe classified as plain text when it should not have. It is retired because its
     * <em>precondition</em> is gone — there is no double encoding left to neutralise anything with —
     * and not because F12 was fixed under it. The distinction matters: a test whose mechanism has
     * been fixed gets inverted, a test whose subject no longer exists gets replaced by the assertion
     * that says so. This is that assertion, and it carries the retired test's reasoning:
     *
     * <ul>
     *   <li>The original sink was {@code onmouseenter} — F2's territory, the largest vulnerability
     *       class in the review. It was not in Canoe's {@code on*} table, so it was {@code ATTR_HTML}
     *       and its value was {@code html()}-encoded, which F2 showed was not enough: the parser
     *       decodes exactly once and the attacker's apostrophe became an apostrophe inside a
     *       JavaScript string literal. Routing the same value through an interpolated {@code #set}
     *       encoded it twice, so the one decode left the literal text {@code &amp;#39;} and the
     *       string literal was never closed. <strong>R4</strong> suppressed every {@code on*} value,
     *       so both paths became inert by design.
     *   <li>The sink then moved to {@code formaction}, which was F3's territory. <strong>R5 and
     *       R6</strong> made it a URL name and <strong>R12</strong> made {@code url()} reject an
     *       off-allowlist scheme outright, so both paths became inert there too.
     *   <li>What honestly remained was narrower: F12 masked <strong>F6</strong>. An off-origin URL
     *       survived {@code url()} byte for byte on the direct path and was mangled on the
     *       {@code #set} path, so the interpolated spelling was safe by accident where the direct
     *       spelling was a live vector. <strong>R24 removes the accident</strong>, and the third
     *       block below is where that is recorded: the two paths now agree, and they agree on the
     *       vulnerable answer. Nothing new is exposed — the class was already
     *       {@code KNOWN_VULNERABLE} in the ledger by its direct route, and no corpus template uses
     *       {@code #set} in any spelling — but the masking is gone and this says so out loud rather
     *       than leaving it to be rediscovered.
     * </ul>
     *
     * <p>The assertion that replaces all of it is one sentence: <strong>the {@code #set} path and the
     * direct path are byte-identical at every sink the accident touched.</strong> That is a stronger
     * statement than "F12 is fixed", it is the property R24 was for, and it fails in both directions
     * — if the interpolated path started encoding again, or if the direct path changed and the
     * interpolated one did not.
     */
    @Test
    public void theSetPathAndTheDirectPathAgreeAtEverySinkTheAccidentCovered() {
        String handlerPayload = Payloads.QUOTE_SINGLE_BREAKOUT.value();
        assertEquals(
                CanoeTestSupport.render("<div onmouseenter=\"v('$data')\">x</div>",
                        handlerPayload).output(),
                CanoeTestSupport.render(
                        "#set($v = \"$data\")<div onmouseenter=\"v('$v')\">x</div>",
                        handlerPayload).output(),
                "R4: an on* value is suppressed whichever path it arrives by");
        assertEquals("<div onmouseenter=\"v('')\">x</div>",
                CanoeTestSupport.render(
                        "#set($v = \"$data\")<div onmouseenter=\"v('$v')\">x</div>",
                        handlerPayload).output(),
                "...and the agreed answer is the empty value, not some jointly-wrong one");

        String schemePayload = Payloads.JS_URL.value();
        CanoeTestSupport.RenderResult schemeViaSet = CanoeTestSupport.render(
                "#set($v = \"$data\")<button formaction=\"$v\">go</button>", schemePayload);
        assertEquals(
                CanoeTestSupport.render("<button formaction=\"$data\">go</button>",
                        schemePayload).output(),
                schemeViaSet.output(),
                "R6 and R12: an off-allowlist scheme is rejected whichever path it arrives by");
        assertFalse(schemeViaSet.decodedAttr("button", "formaction").contains("javascript:"),
                () -> "...and the agreed answer is a rejection. Decoded: "
                        + schemeViaSet.decodedAttr("button", "formaction"));

        // The masking that was left, now removed. This is the row of the argument that changed:
        // before R24 the two paths differed here, and the interpolated one was safe by accident.
        String offOrigin = Payloads.PROTOCOL_RELATIVE.value();
        CanoeTestSupport.RenderResult offOriginViaSet = CanoeTestSupport.render(
                "#set($v = \"$data\")<button formaction=\"$v\">go</button>", offOrigin);
        assertEquals(
                CanoeTestSupport.render("<button formaction=\"$data\">go</button>",
                        offOrigin).output(),
                offOriginViaSet.output(),
                "F6, unmasked: url() passes every character of a protocol-relative URL, and the"
                        + " #set path no longer mangles it into something inert first");
        assertEquals(offOrigin, offOriginViaSet.decodedAttr("button", "formaction"),
                "and the agreed answer is F6's live vector, byte for byte. This is the honest"
                        + " consequence of R24 and is recorded rather than avoided: the accident"
                        + " that hid it was never a control");
    }

    /**
     * <strong>The consequence of R24 that gives an attacker raw bytes: {@code $_x.asis()} on an
     * interpolated {@code #set} value.</strong>
     *
     * <p>Before R24 the two halves of this template each did half a job and the result was, by
     * accident, once-encoded output: the {@code #set} encoded the value for wherever it sat, and
     * {@code asis()} then declined to encode it again, so {@code &lt;b&gt;} reached the page. After
     * R24 the {@code #set} does nothing to the value and {@code asis()} does nothing to it either,
     * so {@code <b>} reaches the page raw.
     *
     * <p>This is not a defect in R24 and it is not a new bypass. {@code asis()} is the documented,
     * unguarded escape hatch — {@code $_x.} is the one prefix in
     * {@code CanoeReferenceInsertionHandler} that means "emit this without encoding", and R23's note
     * on it says the same. What changed is that the combination used to be safer than it said it was,
     * and a developer who had written it and looked at the rendered page would have seen escaped
     * markup and concluded the framework was still protecting them. It was not protecting them; it
     * was double-encoding, and the second encoder was the one they had switched off.
     *
     * <p>It gets its own test because "this combination changed meaning" is exactly the kind of thing
     * that a suite records once and then nobody rediscovers. R25 owns the documentation; the sentence
     * in {@code qlue_user_guide.md} that this falsifies is corrected with R24.
     */
    @Test
    public void asisOnAnInterpolatedSetValueNowEmitsRawData() {
        assertEquals("<p>Hello <b></p>",
                CanoeTestSupport.render("#set($msg = \"Hello $data\")<p>$_x.asis($msg)</p>",
                        MARKUP).output(),
                "R24: the value is raw in $msg and asis() emits it raw. Before R24 this rendered"
                        + " '<p>Hello &lt;b&gt;</p>', because the #set had already encoded it once"
                        + " and asis() was declining to do it a second time");

        assertEquals(
                CanoeTestSupport.render("<p>$_x.asis($data)</p>", MARKUP).output(),
                CanoeTestSupport.render("#set($msg = \"$data\")<p>$_x.asis($msg)</p>",
                        MARKUP).output(),
                "...which is the same thing asis() does to a bare reference. That is the point:"
                        + " routing a value through an interpolated #set is no longer an encoding"
                        + " step, so it no longer half-protects a value the author asked to be"
                        + " emitted raw");
    }

    /**
     * <strong>The one place R24 must <em>not</em> defer: a literal that is compiled rather than
     * printed.</strong>
     *
     * <p>Deferring is a promise that the value will be encoded later, where it is written to the
     * page. {@code #evaluate} breaks that promise in the worst available way: it calls
     * {@code value()} on its argument — interpolating the literal, which is a nested render — and
     * then <em>parses the resulting string as VTL</em> and renders it. The data never passes through
     * a reference again, so a deferred value would never be encoded at all, and every {@code #} and
     * {@code $} in it would be template syntax. A payload of
     * <code>#set($injected = 1)$injected</code> would render as {@code 1}: server-side template
     * injection, which is a strictly worse outcome than the XSS this whole class exists to prevent.
     *
     * <p>So the detector reads one frame further than the literal. {@code ASTStringLiteral.value()}
     * has exactly one caller, and when that caller is {@code Evaluate} the string is source and not
     * output, so the value is encoded here as it always was. That is not a new control — it is the
     * pre-R24 behaviour, kept — and encoding is enough because {@code htmlWhite()} is an allowlist of
     * {@code [a-zA-Z0-9]} and a little whitespace: the payload's {@code #} and {@code $} come back as
     * {@code &amp;#35;} and {@code &amp;#36;} and nothing in the output can be reconstituted into a
     * directive.
     *
     * <p><strong>The underlying hole is real, pre-existing, and untouched.</strong> The plain
     * spelling {@code #set($t = $data)#evaluate($t)} hands {@code #evaluate} the raw value and always
     * has, because a bare assignment never fires this handler; the first assertion pins that so the
     * pair cannot be misread as "#evaluate is safe now". &sect;2.5 is the answer there — the attacker
     * controls data and never the template, and a directive whose argument is compiled is outside
     * what an output encoder can defend. What R24 must not do is widen it to a second spelling.
     *
     * <p>This test is also the alarm for a Velocity upgrade. The consumer is identified by the frame
     * directly below the literal, measured against velocity-engine-core 2.4.1; if a future release
     * puts plumbing in between, this assertion fails loudly rather than the deferral quietly
     * re-opening.
     */
    @Test
    public void evaluateOfAnInterpolatedLiteralIsStillEncodedRatherThanCompiled() {
        String vtl = "#set($injected = 1)$injected";

        assertEquals("1",
                CanoeTestSupport.render("#set($t = $data)#evaluate($t)", vtl).output(),
                "the plain assignment has always handed #evaluate raw data: no reference insertion"
                        + " happens for it, so there has never been anything to encode it. This is"
                        + " the pre-existing hole, and it is not R24's to close");

        assertEquals("&#35;set&#40;&#36;injected &#61; 1&#41;&#36;injected",
                CanoeTestSupport.render("#evaluate(\"$data\")", vtl).output(),
                "...and the interpolated spelling does not join it: Evaluate is the literal's"
                        + " consumer, so the value is encoded here, and what #evaluate then compiles"
                        + " is inert text with no '#' or '$' left in it");

        assertEquals("<p>" + MARKUP_IN_BODY + "</p>",
                CanoeTestSupport.render("#evaluate(\"<p>$data</p>\")", MARKUP).output(),
                "and an ordinary value through the same shape is encoded exactly once, which is what"
                        + " the directives() table asserts for the single-quoted spelling too");
    }

    /**
     * <strong>And the same for a literal that is resolved to a file rather than printed.</strong>
     *
     * <p>{@code #parse} and {@code #include} call {@code value()} on their argument for a template or
     * resource <em>name</em>. A deferred value there is an attacker-chosen path: {@code #parse} then
     * parses and renders whatever it finds, which is template injection by a second route, and
     * {@code #include} copies the bytes to the writer unparsed, which is file disclosure. Both are
     * outside what encoding-at-the-sink can reach once the path has been chosen, so the value is
     * encoded at the reference exactly as it was before R24 — and {@code html()}'s allowlist turns
     * {@code /} and {@code .} into character references, so no path survives it.
     *
     * <p>Asserted as a rejected lookup rather than as bytes, because that is the observable that
     * matters: the name Velocity is handed does not resolve to the attacker's file. As with
     * {@code #evaluate}, the plain spelling {@code #parse($data)} was and remains live, and &sect;2.5
     * is the answer there.
     */
    @Test
    public void aParsedOrIncludedTemplateNameBuiltFromAReferenceIsEncodedAndNotDeferred() {
        for (String directive : List.of("parse", "include")) {
            String template = "#" + directive + "(\"$data\")";

            // The fragment exists, so an unencoded name would resolve and render. The encoded name
            // is 'canoe&#45;fragment&#46;vm', which resolves to nothing.
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> CanoeTestSupport.render(template, "canoe-fragment.vm"),
                    () -> "#" + directive + " must not be handed the raw name: the value is encoded"
                            + " at the reference, so the lookup fails rather than loading the file"
                            + " the data named");
            assertTrue(failure.getCause() instanceof
                            org.apache.velocity.exception.ResourceNotFoundException,
                    () -> "...and it fails as a resource lookup, which is what an encoded name looks"
                            + " like. Actual cause: " + failure.getCause());
        }
    }

    /**
     * The deferral survives a directive that appears <em>below</em> the literal, which is the reason
     * the consumer is one frame and not the whole stack.
     *
     * <p>A {@code #set} inside a {@code #parse}d fragment is the commonest nested render there is —
     * a header that builds a title — and its stack carries a {@code Parse} frame four frames below
     * the literal, three below its real consumer. A {@code #set} inside an {@code #evaluate}d string carries an
     * {@code Evaluate} frame the same way. Both are ordinary F12 shapes and must still defer;
     * treating "the directive is somewhere on the stack" as disqualifying would be safe but would
     * leave F12 alive in every parsed fragment an application has, which is most of them.
     */
    @Test
    public void aSetInsideAParsedOrEvaluatedTemplateStillDefers() {
        CanoeTestSupport.publishFragment("canoe-setter-fragment.vm",
                "#set($inner = \"i$data\")<p>$inner</p>");

        assertEquals("<p>i&lt;b&gt;</p>",
                CanoeTestSupport.render("#parse('canoe-setter-fragment.vm')", MARKUP).output(),
                "Parse is on the stack, but it is not the literal's consumer - the #set is, and this"
                        + " is F12's own shape one level in");

        assertEquals("<p>i&lt;b&gt;</p>",
                CanoeTestSupport.render(
                        "#evaluate('#set($inner = \"i$data\")<p>$inner</p>')", MARKUP).output(),
                "...and the same inside an evaluated string, where an Evaluate frame sits below the"
                        + " #set rather than above it");
    }

    /**
     * The other directives that take a value through {@code ASTStringLiteral.value()}, and one that
     * does not take it to the page at all.
     *
     * <p>These are the shapes that told R24 where the boundary of the fix is. A macro argument, a
     * {@code #foreach} body and a {@code #parse}d fragment all render <em>inside</em> the literal's
     * private writer, and the detector sees the literal's frame under all of them — so the value is
     * carried raw to wherever the macro or the loop prints it, and encoded there. A comparison never
     * reaches a writer at all, which is why it is the one place the fix is visible as a bug fix in
     * the ordinary sense: {@code #if("$data" == "<b>")} used to compare the <em>encoded</em> value
     * against the author's literal and answer "no" for an input that plainly was {@code <b>}.
     *
     * <p>The last three rows are the deep stacks, and they are here as the shapes that would fail
     * first if the frame limit were ever tightened past the point a real template needs: measured
     * below {@code referenceInsert()}, the literal's node is 8 frames down through a {@code #parse}d
     * fragment, 9 through a {@code #foreach} and 10 through a macro invoked inside the literal, which
     * is the deepest the suite renders. Note what the {@code #parse} row says about a fragment inside
     * a literal: the fragment's {@code <em>} markup is template text
     * being turned into part of a string, so it is encoded along with the data when that string is
     * printed. That follows from the fix rather than being a separate decision, and it is the reason
     * building markup inside a {@code #set} is a thing to warn about rather than a thing to support.
     */
    @Test
    public void everyShapeThatRendersIntoALiteralCarriesTheValueRawToWhereItIsPrinted() {
        assertEquals("<table><tr><td>a&lt;b&gt;</td></tr></table>",
                CanoeTestSupport.render(
                        "#macro(cell $v)<td>$v</td>#end<table><tr>#cell(\"a$data\")</tr></table>",
                        MARKUP).output(),
                "a macro argument built from an interpolated literal is encoded at the <td> the"
                        + " macro prints it into, not at the call site");

        assertEquals("<p>a&lt;b&gt; </p>",
                CanoeTestSupport.render(
                        "#set($m = \"a#foreach($i in $items)$i#end \")<p>$m</p>",
                        model("data", MARKUP, "items", List.of(MARKUP))).output(),
                "a #foreach body inside a literal renders into the literal's writer too");

        assertEquals("<p>a&lt;em&gt;&lt;b&gt;&lt;&#47;em&gt;b</p>",
                CanoeTestSupport.render(
                        "#set($m = \"a#parse('canoe-fragment.vm')b\")<p>$m</p>", MARKUP).output(),
                "and so does a #parse'd fragment, eight frames down - with the fragment's own markup"
                        + " encoded along with the data, because a string built inside a #set is a"
                        + " value and not template text");

        assertEquals("<p>a&lt;b&gt;b</p>",
                CanoeTestSupport.render(
                        "#macro(inner)$data#end#set($m = \"a#inner()b\")<p>$m</p>", MARKUP).output(),
                "and a macro invoked inside the literal, ten frames down, which is the deepest"
                        + " nesting the suite renders and the row the frame limit must clear");

        assertEquals("yes",
                CanoeTestSupport.render("#if(\"$data\" == \"<b>\")yes#{else}no#end",
                        MARKUP).output(),
                "a literal used in a comparison never reaches a writer, so it compares the value"
                        + " the author bound. Before R24 this answered 'no': the comparison saw"
                        + " '&lt;b&gt;', encoded for a position the value was never printed at");
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
        CanoeTestSupport.RenderResult result = CanoeTestSupport.render("<p>ok</p>5 < 6", model(),
                CanoeTestSupport.RenderOptions.defaults().withoutAutoEscaping());
        assertTrue(result.isError(),
                () -> "Canoe wraps the writer whether or not the cartridge is attached; got "
                        + result);
        assertTrue(result.errorMessage().contains("Tag name too short"),
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
