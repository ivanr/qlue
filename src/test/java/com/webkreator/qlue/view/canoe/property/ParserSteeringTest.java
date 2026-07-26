package com.webkreator.qlue.view.canoe.property;

import com.webkreator.qlue.util.HtmlEncoder;
import com.webkreator.qlue.view.Canoe;
import com.webkreator.qlue.view.canoe.CanoeTestSupport;
import com.webkreator.qlue.view.canoe.corpus.CanoeCorpus;
import com.webkreator.qlue.view.canoe.corpus.Payload;
import com.webkreator.qlue.view.canoe.corpus.Payloads;
import com.webkreator.qlue.view.canoe.corpus.VerdictEvaluator;
import com.webkreator.qlue.view.canoe.corpus.XssCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <strong>The corollary the whole security review rests on, as a property: attacker data can never
 * move Canoe's state machine.</strong>
 *
 * <p>The review's argument is that encoded output can never contain a raw {@code <}, and that
 * {@code html()} and {@code url()} both neutralise quotes, so an attacker-supplied value can never
 * open a tag, never terminate an attribute value, and therefore never change what the parser thinks
 * it is looking at. The parser is steered by template literal text and by nothing else.
 *
 * <p>The property, stated so it can be run: for every corpus template, <em>the sequence of
 * {@code currentContext()} values observed at each reference position must be identical whether the
 * reference value is the inert marker or any payload in the catalogue.</em> That is a stronger claim
 * than "the output is safe" and a different one from "the output is identical" — two payloads
 * encoded for the same context produce different bytes, which is fine; a payload that reaches a
 * <em>different</em> context has moved the machine, which is not.
 *
 * <p>Measured over all {@code CanoeCorpus.all()} templates and all {@code Payloads.all()} values,
 * including every breakout family: <strong>no payload moves the machine anywhere.</strong> Not one
 * reference position, in any template, sees a different context.
 *
 * <h2>...and the corollary is false anyway. F24.</h2>
 *
 * <p><strong>The property above holds over the corpus and is not true in general.</strong>
 * {@code TemplateFuzzTest} (T31) generated the counterexample on its first run and
 * {@link #attackerDataCanSteerTheAttributeContextByEmittingARawColon} is it:
 * {@code HtmlEncoder.url()} copies a matched {@code http://} or {@code https://} prefix into the
 * output <em>unencoded</em>, colon and all; Canoe's attribute-value scan reads that colon as a
 * prefix delimiter and calls {@code detectAttributePrefix()}, which finds nothing and assigns
 * {@code ATTR_HTML}. Every later reference in that same attribute value then gets {@code html()}
 * where the author asked for a URL attribute — and {@code html()}'s character references decode
 * back to the attacker's raw {@code @}, which is precisely the byte {@code url()}'s {@code %40}
 * was accidentally neutralising.
 *
 * <p>Why the corpus sweep above could not see it, stated plainly because it is the lesson rather
 * than an excuse: the sweep varies <em>one</em> reference and holds every other model entry fixed,
 * on purpose, so that a divergence is unambiguous about which reference caused it. F24 needs two
 * references in one attribute value, with the attacker's in the first. No corpus template has that
 * shape, so the quantification — not the statement — is where the hole was. A generator that did
 * not know which shapes were interesting found it in a few hundred iterations.
 *
 * <p>The two claims are kept apart rather than merged. The corpus sweep is still worth running:
 * it is what would notice a <em>second</em> steering mechanism appearing in a shape somebody
 * actually writes. {@link #onlyTheUriContextCanEmitARawColon} is the bound on the first one.
 *
 * <h2>Why this is the test that gates the remediation</h2>
 *
 * <p><strong>Relaxing the {@code CTX_JS} or {@code CTX_CSS} suppression to real escaping — the
 * commented-out {@code HtmlEncoder.js()} and {@code HtmlEncoder.css()} calls at
 * {@code Canoe.java:1074-1081} — requires re-running this test first, and it must still pass.</strong>
 * The review asks for exactly that, in the corollary section, and this is the executable form of the
 * request. Today those two contexts emit the empty string, so they cannot contribute a character of
 * any kind and the property holds trivially for every reference that lands in them. The moment they
 * emit something, the property becomes a real constraint on two encoders that F16 has already shown
 * to be defective: {@code js()} truncates astral code points to their low sixteen bits, so
 * {@code U+10027} becomes an apostrophe, and {@code css()} emits unterminated two-digit hex escapes.
 * Either could produce a character that steers the parser, and no other test in this suite would
 * notice.
 *
 * <p>Three findings depend on this property being true and would need re-rating if it ever failed:
 * <strong>F10</strong> (both desyncs are reachable only from template text — {@code
 * ScriptAndStyleElementTest.onlyTemplateTextCanCauseADesync} is the local form of this test),
 * <strong>F14</strong> (the same argument for the comment states), and the review's whole
 * "what is not affected" section, which claims body-context templates are safe under all
 * twenty-one findings.
 *
 * <h2>The oracle is not blind</h2>
 *
 * <p>A property that has never failed is indistinguishable from a property that cannot fail, so
 * {@link #aDeliberatelyUnencodedRenderBreaksTheProperty} does what &sect;2.4 asks of the browser
 * detectors: it renders a payload through the {@code $_x.asis()} bypass, which is the one supported
 * way to put attacker-controlled bytes into the output unencoded, and requires the property to break.
 * If that test ever goes green, everything above it is vacuous.
 */
public class ParserSteeringTest {

    static List<XssCase> corpus() {
        return CanoeCorpus.all();
    }

    // ------------------------------------------------------------------
    // The property
    // ------------------------------------------------------------------

    /**
     * For one template, every payload in the catalogue produces the same context sequence as the
     * inert marker.
     *
     * <p>Quantified over the whole catalogue rather than over the payloads the case declares, on
     * purpose. A case's payload list records what is <em>worth attacking that sink with</em>; this
     * property is about what any value can do to the parser, and the CSS payloads have as much
     * business being tried against a {@code <title>} as against a {@code style} attribute. There are
     * no families excluded and no sampling.
     *
     * <p>All payloads are checked and the divergences reported together, rather than failing at the
     * first: if this property ever breaks, how many payloads and which families break it is the first
     * thing anyone would want to know.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("corpus")
    public void noPayloadMovesTheStateMachine(XssCase testCase) {
        Steering baseline = steer(testCase, Payloads.INERT_MARKER.value());
        List<String> divergences = new ArrayList<>();

        for (Payload payload : Payloads.all()) {
            Steering attacked = steer(testCase, payload.value());
            if (!baseline.sameContexts(attacked)) {
                divergences.add(payload.id()
                        + "\n      inert    : " + baseline
                        + "\n      attacked : " + attacked);
            }
        }

        assertTrue(divergences.isEmpty(),
                () -> testCase.id() + ": " + divergences.size() + " payload(s) moved Canoe's state"
                        + " machine. This is the property the review's corollary asserts and the one"
                        + " F10, F14 and the whole 'what is not affected' section depend on. If it"
                        + " is real, those three need re-rating before anything else."
                        + "\n  Template: " + CanoeTestSupport.quote(testCase.template())
                        + "\n  " + String.join("\n  ", divergences));
    }

    /**
     * Whether Canoe <em>rejects</em> a template must not depend on the payload either.
     *
     * <p>The companion half of the property, and it is a separate test because a divergence here
     * means something different: the context sequence says where the parser went, and this says
     * whether it survived. A payload that turned a rendering template into a rejected one — or the
     * other way round — would be steering the machine into the {@code INVALID} state, which is a
     * position the context sequence cannot represent.
     *
     * <p>The reported position inside the error message moves with the payload's length, which is
     * arithmetic rather than steering, so the comparison is on the message with its position
     * stripped. {@code CanoeCorpusTest.payloadsCannotAddMarkupDelimitersToOutput} pins the drift
     * itself as an identity.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("corpus")
    public void noPayloadChangesWhetherTheTemplateIsRejected(XssCase testCase) {
        Steering baseline = steer(testCase, Payloads.INERT_MARKER.value());
        List<String> divergences = new ArrayList<>();

        for (Payload payload : Payloads.all()) {
            Steering attacked = steer(testCase, payload.value());
            if (!baseline.sameRejection(attacked)) {
                divergences.add(payload.id()
                        + "\n      inert    : " + baseline.rejectionSummary()
                        + "\n      attacked : " + attacked.rejectionSummary());
            }
        }

        assertTrue(divergences.isEmpty(),
                () -> testCase.id() + ": " + divergences.size() + " payload(s) changed whether"
                        + " Canoe rejected the template.\n  " + String.join("\n  ", divergences));
    }

    // ------------------------------------------------------------------
    // F24: the counterexample
    // ------------------------------------------------------------------

    /**
     * <strong>F24 — attacker data can steer the attribute context, by making {@code url()} emit a
     * raw colon.</strong>
     *
     * <p>Found by {@code TemplateFuzzTest} on its first run. The template is the ordinary shape of
     * an application that keeps a base URL in configuration and appends a path to it:
     *
     * <pre>{@code <a href="$base$path">}</pre>
     *
     * <p>The three assertions are the three steps, and each is checked rather than argued:
     *
     * <ol>
     *   <li>{@code url()} emits {@code https://} with its colon intact, because {@code uriPattern}
     *       matches and {@code HtmlEncoder.java:190} appends group 1 without encoding it.
     *   <li>That colon moves the context observed at the <em>second</em> reference from
     *       {@code CTX_URI} to {@code CTX_HTML_ATTR}. This is the steering, and it is what the
     *       review's corollary says cannot happen.
     *   <li>The consequence: the second reference's {@code @} arrives raw rather than as
     *       {@code %40}, so the URL's authority becomes the attacker's host. The judgement is made
     *       by {@code VerdictEvaluator.analyseUrl} — the suite's own URL oracle, hardened against
     *       Node's WHATWG parser — and not by reading the string.
     * </ol>
     *
     * <p>The control is the same template with a base that has no colon in it. Everything else is
     * identical, {@code url()} applies to the second reference as the author intended, and the
     * {@code @} becomes {@code %40}. That pair is the whole finding: one character of difference in
     * a value the attacker does not even have to control.
     */
    @Test
    public void attackerDataCanSteerTheAttributeContextByEmittingARawColon() {
        String template = "<a href=\"$base$path\">x</a>";
        String attack = "@attacker.invalid/x";

        // Step 1: url() passes the scheme prefix through with its colon.
        assertEquals("https://app.example", HtmlEncoder.url("https://app.example"),
                "F24's mechanism: uriPattern matches, and group 1 is appended unencoded");
        assertTrue(HtmlEncoder.url("https://app.example").indexOf(':') >= 0,
                "F24 needs a raw colon in encoder output, and this is where it comes from");

        // Step 2: the colon moves the context at the second reference.
        List<Integer> withScheme = steer(template, model("https://app.example", attack)).contexts;
        List<Integer> withoutScheme = steer(template, model("/app", attack)).contexts;

        assertNotEquals(withoutScheme, withScheme,
                () -> "F24 is fixed if this passes: the base URL no longer decides which encoder the"
                        + " second reference gets. Update the ledger, delete"
                        + " TemplateFuzzTest.isTheKnownColonSteering, and restore the review's"
                        + " corollary. Contexts: " + names(withScheme) + " versus "
                        + names(withoutScheme));
        assertEquals(Canoe.CTX_HTML_ATTR, withScheme.get(1),
                () -> "F24: the second reference in a href is observed in CTX_HTML_ATTR, so it gets"
                        + " html() instead of url(). Contexts: " + names(withScheme));
        assertEquals(Canoe.CTX_URI, withoutScheme.get(1),
                () -> "the control: with no colon above it, the same reference is CTX_URI."
                        + " Contexts: " + names(withoutScheme));

        // Step 3: the consequence, judged by the suite's URL oracle rather than by eye.
        String steered = CanoeTestSupport
                .render(template, model("https://app.example", attack))
                .decodedAttr("a", "href");
        String control = CanoeTestSupport
                .render(template, model("/app", attack))
                .decodedAttr("a", "href");

        assertEquals("https://app.example@attacker.invalid/x", steered,
                "F24: html() encodes the '@' as &#64; and the HTML parser decodes it straight back,"
                        + " so the URL parser is handed a userinfo delimiter");
        assertEquals("/app%40attacker.invalid/x", control,
                "the control: url() percent-encodes the '@', which is one of the three accidents"
                        + " CanoeCorpusTest.urlEncodingAccidentsThatMakeOffsiteVectorsSafe pins");

        assertTrue(VerdictEvaluator.analyseUrl(steered).isDangerous(),
                () -> "F24: " + steered + " must reach an origin that is not the page's own - "
                        + VerdictEvaluator.analyseUrl(steered).explanation());
        assertFalse(VerdictEvaluator.analyseUrl(control).isDangerous(),
                () -> "the control must stay same-origin - "
                        + VerdictEvaluator.analyseUrl(control).explanation());
    }

    /**
     * The bound on F24: {@code CTX_URI} is the only context whose encoder can emit a raw colon.
     *
     * <p>A finding with no boundary gets re-litigated, and this one has a sharp boundary that is a
     * property of five functions rather than of the templates anybody tried. {@code html()} and
     * {@code htmlWhite()} render {@code :} as {@code &#58;}; {@code CTX_JS} and {@code CTX_CSS}
     * emit nothing; only {@code url()}'s {@code uriPattern} passthrough produces one. So F24 needs
     * a URL-bearing attribute holding two references, and cannot be reached from a plain-text
     * attribute, from body text, or from a handler.
     *
     * <p>It is also the test that fails if somebody "fixes" F15 by relaxing {@code url()}, or
     * enables the commented-out {@code css()} encoder — either of which could put a colon somewhere
     * new and widen the finding without anyone connecting the two changes.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("everyPayload")
    public void onlyTheUriContextCanEmitARawColon(Payload payload) {
        int[] contexts = {Canoe.CTX_HTML, Canoe.CTX_HTML_ATTR, Canoe.CTX_JS, Canoe.CTX_CSS,
                Canoe.CTX_SUPPRESS};

        for (int context : contexts) {
            String encoded = Canoe.encode(payload.value(), context);
            assertEquals(-1, encoded.indexOf(':'),
                    () -> "F24 would widen: encode(" + payload.id() + ", "
                            + CanoeTestSupport.contextName(context) + ") emitted a raw colon, which"
                            + " re-runs detectAttributePrefix() over whatever is in the buffer: "
                            + CanoeTestSupport.quote(encoded));
        }
    }

    /**
     * ...and in {@code CTX_URI} a colon survives only behind a lowercase {@code http://} or
     * {@code https://}, which is what makes the finding's precondition checkable.
     *
     * <p>The uppercase row is the one worth having: {@code uriPattern} is case-sensitive, so
     * {@code HTTPS://X} does <em>not</em> match, the colon is percent-encoded, and no steering
     * happens. That is F15's family of accidents doing useful work again, and it is the reason the
     * finding is rated where it is rather than higher.
     */
    @Test
    public void inTheUriContextOnlyALowercaseSchemePrefixKeepsItsColon() {
        assertEquals("https://app.example/a", HtmlEncoder.url("https://app.example/a"));
        assertEquals("http://app.example/a", HtmlEncoder.url("http://app.example/a"));

        assertEquals(-1, HtmlEncoder.url("HTTPS://app.example/a").indexOf(':'),
                "uriPattern is case-sensitive, so an uppercase scheme is percent-encoded and cannot"
                        + " steer anything");
        assertEquals(-1, HtmlEncoder.url("javascript:alert(1)").indexOf(':'),
                "and a scheme that is not http(s) never reaches the passthrough at all");
        assertEquals(-1, HtmlEncoder.url("ftp://app.example/a").indexOf(':'),
                "nor does any other scheme");
        assertEquals(-1, HtmlEncoder.url("//app.example/a").indexOf(':'),
                "a protocol-relative URL has no colon to begin with");
    }

    private static Map<String, Object> model(String base, String path) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("base", base);
        model.put("path", path);
        return model;
    }

    // ------------------------------------------------------------------
    // The mechanism the property rests on
    // ------------------------------------------------------------------

    /**
     * Why the property holds, stated as a fact about the encoders rather than as a survey of
     * outcomes.
     *
     * <p>Four characters can move Canoe's state machine from a text or attribute-value position:
     * {@code <} opens a tag, {@code >} closes one, and the two quote marks terminate a quoted
     * attribute value. Every encoder {@code Canoe.encode()} can dispatch to either escapes all four
     * or emits nothing at all. That is a claim about five functions, so it is asserted against those
     * five functions over every payload, rather than inferred from the corpus sweep above.
     *
     * <p>{@code CTX_HTML} and {@code CTX_HTML_ATTR} are the interesting rows; the other three are the
     * empty string today, which is why relaxing them is the change this file exists to gate.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("everyPayload")
    public void noEncoderCanEmitACharacterThatSteersTheParser(Payload payload) {
        int[] contexts = {Canoe.CTX_HTML, Canoe.CTX_HTML_ATTR, Canoe.CTX_JS, Canoe.CTX_URI,
                Canoe.CTX_CSS, Canoe.CTX_SUPPRESS};

        for (int context : contexts) {
            String encoded = Canoe.encode(payload.value(), context);
            for (char steering : new char[]{'<', '>', '"', '\''}) {
                assertEquals(-1, encoded.indexOf(steering),
                        () -> "encode(" + payload.id() + ", "
                                + CanoeTestSupport.contextName(context) + ") emitted a '" + steering
                                + "', which can move the state machine: "
                                + CanoeTestSupport.quote(encoded));
            }
        }
    }

    static List<Payload> everyPayload() {
        return Payloads.all();
    }

    /**
     * The two contexts that are suppressed today emit nothing at all, for every payload.
     *
     * <p>Recorded separately from the sweep above so that the difference is explicit: {@code CTX_JS}
     * and {@code CTX_CSS} pass the steering test <em>vacuously</em>, and the day
     * {@code Canoe.java:1074-1081} is uncommented they stop doing so and start depending on
     * {@code js()} and {@code css()} being correct. This test is the one that fails first when that
     * happens, and it is the one whose failure means "now go and run everything above".
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("everyPayload")
    public void theJsAndCssContextsPassVacuouslyBecauseTheyEmitNothing(Payload payload) {
        assertEquals("", Canoe.encode(payload.value(), Canoe.CTX_JS),
                "CTX_JS suppression; when this changes, re-run noPayloadMovesTheStateMachine before"
                        + " shipping it");
        assertEquals("", Canoe.encode(payload.value(), Canoe.CTX_CSS),
                "CTX_CSS suppression - which per F21 no context path can currently reach anyway");
    }

    // ------------------------------------------------------------------
    // The non-blind-oracle self-test
    // ------------------------------------------------------------------

    /**
     * The property must be able to fail, and unencoded output must be what breaks it.
     *
     * <p>{@code $_x.asis()} is the one supported way to put attacker-controlled bytes into the
     * response untouched, so it is the honest way to simulate an encoder that has stopped working.
     * The payload is a bare {@code <script>}: with it, the reference that follows is observed in
     * {@code CTX_JS} instead of {@code CTX_HTML}, and the machine ends up somewhere else entirely.
     *
     * <p>The control matters as much as the case. The identical template with the identical payload
     * through an ordinary encoded reference must <em>not</em> break the property, which is what
     * distinguishes "the oracle can see a change" from "the oracle sees a change whenever the payload
     * changes".
     */
    @Test
    public void aDeliberatelyUnencodedRenderBreaksTheProperty() {
        String bypassing = "<p>$_x.asis($data)$second</p>";
        String encoding = "<p>$data$second</p>";
        String payload = "<script>";

        List<Integer> bypassInert = steer(bypassing, Payloads.INERT_MARKER.value()).contexts;
        List<Integer> bypassAttacked = steer(bypassing, payload).contexts;
        assertNotEquals(bypassInert, bypassAttacked,
                "if this passes, the property above is vacuous: unencoded attacker bytes must move"
                        + " the state machine, because that is the only reason encoding them is"
                        + " what keeps it still");
        assertTrue(bypassAttacked.contains(Canoe.CTX_JS),
                () -> "and the direction is the alarming one: the reference after the bypass is"
                        + " observed inside a script element. Contexts: "
                        + names(bypassAttacked) + " versus " + names(bypassInert));

        assertEquals(steer(encoding, Payloads.INERT_MARKER.value()).contexts,
                steer(encoding, payload).contexts,
                "the control: the same payload through an encoded reference moves nothing, so the"
                        + " oracle is responding to the missing encoder and not to the payload");
    }

    /**
     * The bypass is not a defect in the property; it is outside the threat model.
     *
     * <p>&sect;2.5 is explicit — the attacker controls data, never the template — and
     * {@code $_x.asis()} is template text. Stating that here, next to the test that uses the bypass
     * to break the property, is the point: the self-test above shows what a broken encoder would look
     * like, and this shows that reaching it requires the template author's cooperation. Nothing in
     * the corpus can produce a bypass, because a payload cannot write a directive.
     */
    @Test
    public void noPayloadCanIntroduceABypassIntoATemplate() {
        for (Payload payload : Payloads.all()) {
            String rendered = CanoeTestSupport.render("<p>$data</p>", payload.value()).output();
            assertEquals(-1, rendered.indexOf("$_x."),
                    () -> payload.id() + " put a bypass prefix into the output, which would mean a"
                            + " payload can act as template text: " + rendered);
        }
    }

    // ------------------------------------------------------------------
    // Steering
    // ------------------------------------------------------------------

    private static Steering steer(XssCase testCase, String value) {
        Map<String, Object> model = new LinkedHashMap<>(testCase.extraModel());
        model.put(testCase.referenceName(), value);
        return steer(testCase.template(), model);
    }

    private static Steering steer(String template, String value) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("data", value);
        model.put("second", Payloads.INERT_MARKER.value());
        return steer(template, model);
    }

    /**
     * Renders through a {@link ContextRecordingCanoe} and reports what the handler saw.
     *
     * <p>Extra model entries keep whatever the case gave them, so the only thing that varies between
     * the baseline and the attacked run is the one reference under test. A second reference bound to
     * a payload as well would make a divergence ambiguous about which one caused it.
     */
    private static Steering steer(String template, Map<String, Object> model) {
        AtomicReference<ContextRecordingCanoe> recorder = new AtomicReference<>();
        CanoeTestSupport.RenderResult result = CanoeTestSupport.render(template, model,
                CanoeTestSupport.RenderOptions.defaults(),
                writer -> {
                    ContextRecordingCanoe canoe = new ContextRecordingCanoe(writer);
                    recorder.set(canoe);
                    return canoe;
                });
        return new Steering(recorder.get().contexts(), result.isError(), result.errorMessage());
    }

    private static String names(List<Integer> contexts) {
        return contexts.stream().map(CanoeTestSupport::contextName).collect(Collectors.toList())
                .toString();
    }

    /** What one render tells us about where the parser went. */
    private static final class Steering {

        private final List<Integer> contexts;
        private final boolean rejected;
        private final String errorMessage;

        Steering(List<Integer> contexts, boolean rejected, String errorMessage) {
            this.contexts = contexts;
            this.rejected = rejected;
            this.errorMessage = errorMessage;
        }

        boolean sameContexts(Steering other) {
            return contexts.equals(other.contexts);
        }

        boolean sameRejection(Steering other) {
            return rejected == other.rejected
                    && stripPosition(errorMessage).equals(stripPosition(other.errorMessage));
        }

        String rejectionSummary() {
            return rejected ? stripPosition(errorMessage) : "accepted";
        }

        /**
         * Removes the {@code (line: 1, pos: 27)} suffix. The position moves with the payload's
         * length, which is arithmetic and not steering.
         */
        private static String stripPosition(String message) {
            if (message == null) {
                return "";
            }
            int at = message.indexOf(" (line:");
            return at < 0 ? message : message.substring(0, at);
        }

        @Override
        public String toString() {
            return names(contexts) + (rejected ? " rejected: " + stripPosition(errorMessage) : "");
        }
    }
}
