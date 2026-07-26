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
 * <h2>...and the corollary was false, until R2. F24.</h2>
 *
 * <p><strong>The property above held over the corpus and was not true in general.</strong>
 * {@code TemplateFuzzTest} (T31) generated the counterexample on its first run and
 * {@link #attackerDataCanNoLongerSteerTheAttributeContextWithARawColon} is it, now inverted:
 * {@code HtmlEncoder.url()} copies a matched {@code http://} or {@code https://} prefix into the
 * output <em>unencoded</em>, colon and all; Canoe's attribute-value scan reads that colon as a
 * prefix delimiter and calls {@code detectAttributePrefix()}, which found nothing and assigned
 * {@code ATTR_HTML}. Every later reference in that same attribute value then got {@code html()}
 * where the author asked for a URL attribute — and {@code html()}'s character references decode
 * back to the attacker's raw {@code @}, which is precisely the byte {@code url()}'s {@code %40}
 * was accidentally neutralising.
 *
 * <p>R2 removed the assignment, not the colon. {@code detectAttributePrefix()} may now only narrow
 * the context, and no encoder can put text in the buffer that spells one of its five prefixes, so
 * attacker data can no longer change which encoder any reference gets. The finding's root cause —
 * an encoder emitting a raw colon at all — is untouched and belongs to R11; what remains of it is
 * that the colon still consumes the scan's single look at the value, which can only ever cost a
 * <em>narrowing</em> and therefore fails safe.
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
 * actually writes. {@link #theOnlyRawColonAnyEncoderEmitsIsAnAllowlistedSchemeSeparator} is the
 * bound on the first one, tightened by R11 and R12.
 *
 * <h2>Why this is the test that gates the remediation</h2>
 *
 * <p><strong>Relaxing Canoe's JavaScript or CSS suppression to real escaping — wiring
 * {@code HtmlEncoder.js()} into the {@code CTX_JS} arm, or a CSS encoder into the {@code ATTR_CSS}
 * route — requires re-running this test first, and it must still pass.</strong>
 * The review asks for exactly that, in the corollary section, and this is the executable form of the
 * request. Today the JS context and the suppressed {@code style} route emit the empty string, so they
 * cannot contribute a character of any kind and the property holds trivially for every reference that
 * lands in them. The moment they emit something, the property becomes a real constraint on two
 * encoders F16 showed to be defective and R13 corrected: before R13 {@code js()} truncated astral
 * code points to their low sixteen bits ({@code U+10027} became an apostrophe) and {@code css()}
 * emitted unterminated two-digit hex escapes. R13 fixed both, but neither is wired in — R14 deleted
 * {@code CTX_CSS} rather than route to a CSS encoder — so relaxing either suppression is still an
 * undecided change, and this is the test that gates it. No other test in this suite would notice a
 * steering character one of them emitted.
 *
 * <p>Three findings depend on this property being true and would need re-rating if it ever failed:
 * <strong>F10</strong> (closed by R17; both desyncs were reachable only from template text, which is
 * why they were latent rather than exploitable, and {@code
 * ScriptAndStyleElementTest.onlyTemplateTextCanCauseADesync} is the local form of this test — it
 * passed unchanged through R17, because the argument is about what an encoder can emit),
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
     * <strong>F24's exploitable path, closed by R2.</strong> Was
     * {@code attackerDataCanSteerTheAttributeContextByEmittingARawColon}; inverted rather than
     * deleted, because it is the only test that drives the exact shape the fuzzer found.
     *
     * <p>Found by {@code TemplateFuzzTest} on its first run. The template is the ordinary shape of
     * an application that keeps a base URL in configuration and appends a path to it:
     *
     * <pre>{@code <a href="$base$path">}</pre>
     *
     * <p>The steering had three steps and R2 removes the second, which is what turns the finding
     * into a mitigated one rather than a fixed one:
     *
     * <ol>
     *   <li>{@code url()} <strong>still</strong> emits {@code https://} with its colon intact,
     *       because {@code uriPattern} matches and {@code HtmlEncoder.java:190} appends group 1
     *       without encoding it. That is the root cause and R11 removes it; this test asserts it is
     *       still there, so that R11's landing is what changes this row and not something else.
     *   <li>That colon used to move the context observed at the <em>second</em> reference from
     *       {@code CTX_URI} to {@code CTX_HTML_ATTR}. It no longer does: the colon still reaches
     *       {@code detectAttributePrefix()}, which now matches nothing and assigns nothing, so the
     *       name-derived {@code ATTR_URI} survives. The review's corollary — that attacker data
     *       cannot move Canoe's state machine — holds again on this shape.
     *   <li>The consequence is therefore gone: the second reference's {@code @} is percent-encoded
     *       in both renders, so the URL's authority stays the page's own. The judgement is still
     *       made by {@code VerdictEvaluator.analyseUrl} — the suite's own URL oracle, hardened
     *       against Node's WHATWG parser — and not by reading the string.
     * </ol>
     *
     * <p>The control is the same template with a base that has no colon in it. It is kept, and the
     * assertion that used to distinguish it from the attacked render is now the assertion that it
     * does not: one character of difference in a value the attacker does not even have to control
     * has stopped deciding which encoder the reference downstream of it gets.
     *
     * <p>What R2 does <em>not</em> do is stop {@code url()} emitting the colon. A raw colon in
     * encoder output still burns the one look {@code detectAttributePrefix()} gets at the value, so
     * it can still stop a later prefix from being <em>recognised</em> — the direction that fails
     * safe under every prefix the method can assign, since all three suppress, but a direction that
     * exists. R11 and R12 are what remove the colon itself.
     */
    @Test
    public void attackerDataCanNoLongerSteerTheAttributeContextWithARawColon() {
        String template = "<a href=\"$base$path\">x</a>";
        String attack = "@attacker.invalid/x";

        // Step 1: url() still emits a colon for an absolute allowlisted-scheme URL - but since R11
        // and R12 it emits it from the parse (an http/https/mailto scheme) rather than by copying a
        // matched prefix out of the input, and at a fixed position behind a scheme name that
        // detectAttributePrefix() matches none of. The colon is no longer the F24 hazard it was.
        assertEquals("https://app.example", HtmlEncoder.url("https://app.example"),
                "R12: an allowlisted scheme is emitted from the parse, colon included");
        assertTrue(HtmlEncoder.url("https://app.example").indexOf(':') >= 0,
                "the colon is still here, now behind an allowlisted scheme rather than copied");

        // Step 2: the colon no longer moves the context at the second reference.
        List<Integer> withScheme = steer(template, model("https://app.example", attack)).contexts;
        List<Integer> withoutScheme = steer(template, model("/app", attack)).contexts;

        assertEquals(withoutScheme, withScheme,
                () -> "R2: the base URL must no longer decide which encoder the second reference"
                        + " gets. Contexts: " + names(withScheme) + " versus "
                        + names(withoutScheme));
        assertEquals(Canoe.CTX_URI, withScheme.get(1),
                () -> "R2: the second reference in a href is observed in CTX_URI even with a raw"
                        + " colon above it, so it gets url(). Contexts: " + names(withScheme));
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

        assertEquals("https://app.example%40attacker.invalid/x", steered,
                "R2: url() applies to the second reference and percent-encodes the '@', so the URL"
                        + " parser is not handed a userinfo delimiter. It used to read"
                        + " https://app.example@attacker.invalid/x, because html() wrote &#64; and"
                        + " the HTML parser decoded it straight back");
        assertEquals("/app%40attacker.invalid/x", control,
                "the control: url() percent-encodes the '@', which is one of the three accidents"
                        + " CanoeCorpusTest.urlEncodingAccidentsThatMakeOffsiteVectorsSafe pins");

        assertFalse(VerdictEvaluator.analyseUrl(steered).isDangerous(),
                () -> "R2: " + steered + " must stay on the page's own origin - "
                        + VerdictEvaluator.analyseUrl(steered).explanation());
        assertFalse(VerdictEvaluator.analyseUrl(control).isDangerous(),
                () -> "the control must stay same-origin - "
                        + VerdictEvaluator.analyseUrl(control).explanation());
    }

    /**
     * The bound on F24, restated for R11 and R12: the only raw colon any encoder can emit sits
     * immediately behind an allowlisted scheme name.
     *
     * <p>{@code html()} and {@code htmlWhite()} render {@code :} as {@code &#58;}; {@code CTX_JS} and
     * {@code CTX_SUPPRESS} (where a suppressed {@code style} value lands since R14 deleted
     * {@code CTX_CSS}) emit nothing; so no context but {@code CTX_URI} produces a colon at all, which
     * is the first half of the property and is asserted below over the other contexts.
     *
     * <p>The literal "no context can emit a raw colon" that R11's plan text asks for is not reachable
     * while an absolute {@code http(s)} URL is allowed to survive — and it must, or
     * {@code <a href="$absoluteUrl">} breaks, and F6's off-origin rows would read SAFE, which R9 (not
     * R12) owns. So the achievable and equivalent property is stronger where it counts: {@code url()}
     * emits a colon <em>only</em> as the separator of an {@code http}, {@code https} or {@code mailto}
     * scheme, from its own parse rather than copied out of the input. A rejected scheme
     * ({@code javascript:}, {@code data:}, {@code vbscript:}) emits nothing, and a colon anywhere but
     * a scheme separator — in a relative path, a homoglyph — is percent-escaped. That is what closes
     * F24 by design: after R2 a colon cannot steer at all, and after R12 the only colon that reaches
     * the value scan is one behind a scheme name that {@code detectAttributePrefix()} matches none of.
     *
     * <p>It is also the test that fails if somebody widens the scheme allowlist or wires a CSS encoder
     * into the suppressed {@code style} route — either of which could put a colon somewhere new.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("everyPayload")
    public void theOnlyRawColonAnyEncoderEmitsIsAnAllowlistedSchemeSeparator(Payload payload) {
        int[] colonlessContexts = {Canoe.CTX_HTML, Canoe.CTX_HTML_ATTR, Canoe.CTX_JS,
                Canoe.CTX_SUPPRESS};
        for (int context : colonlessContexts) {
            String encoded = Canoe.encode(payload.value(), context);
            assertEquals(-1, encoded.indexOf(':'),
                    () -> "no context but CTX_URI may emit a colon: encode(" + payload.id() + ", "
                            + CanoeTestSupport.contextName(context) + ") = "
                            + CanoeTestSupport.quote(encoded));
        }

        // A colon in scheme position - before the first '/', '?' or '#' - must be an allowlisted
        // scheme separator. A colon after one of those is in a path, query or fragment, where it is
        // not scheme-like and cannot be read as a prefix delimiter.
        String url = Canoe.encode(payload.value(), Canoe.CTX_URI);
        int firstDelimiter = url.length();
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == ':' || c == '/' || c == '?' || c == '#') {
                firstDelimiter = i;
                break;
            }
        }
        if (firstDelimiter < url.length() && url.charAt(firstDelimiter) == ':') {
            String scheme = url.substring(0, firstDelimiter);
            assertTrue(scheme.equals("http") || scheme.equals("https") || scheme.equals("mailto"),
                    () -> "the only colon url() may emit in scheme position is an allowlisted scheme"
                            + " separator, but encode(" + payload.id() + ", CTX_URI) = "
                            + CanoeTestSupport.quote(url) + " put one after " + scheme
                            + ". detectAttributePrefix() matches none of http/https/mailto, so such a"
                            + " colon cannot steer; any other scheme-position colon is a new hazard.");
        }
    }

    /**
     * ...and in {@code CTX_URI} a colon survives only behind an allowlisted scheme, case-insensitively.
     *
     * <p>R12 normalises an uppercase scheme rather than escaping its colon by accident, so
     * {@code HTTPS://X} keeps its colon now — correctly, because it is a real off-origin URL (F6). A
     * scheme off the allowlist keeps no colon at all: it is rejected to the empty string.
     */
    @Test
    public void inTheUriContextOnlyAnAllowlistedSchemeKeepsItsColon() {
        assertEquals("https://app.example/a", HtmlEncoder.url("https://app.example/a"));
        assertEquals("http://app.example/a", HtmlEncoder.url("http://app.example/a"));
        assertEquals("mailto:a@app.example", HtmlEncoder.url("mailto:a@app.example"));

        assertTrue(HtmlEncoder.url("HTTPS://app.example/a").startsWith("https:"),
                "R12 normalises an uppercase scheme; the colon is kept because the URL is real");
        assertEquals(-1, HtmlEncoder.url("javascript:alert(1)").indexOf(':'),
                "a rejected scheme is suppressed to the empty string, so no colon");
        assertEquals(-1, HtmlEncoder.url("ftp://app.example/a").indexOf(':'),
                "nor does any other off-allowlist scheme keep one");
        assertEquals(-1, HtmlEncoder.url("//app.example/a").indexOf(':'),
                "a protocol-relative URL has no colon to begin with");
        assertEquals(-1, HtmlEncoder.url("a:b").indexOf(':'),
                "and a colon in a bare relative reference is escaped, not kept");
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
     * <p>{@code CTX_HTML}, {@code CTX_HTML_ATTR} and {@code CTX_URI} are the rows that emit; the two
     * suppressed rows ({@code CTX_JS} and {@code CTX_SUPPRESS}, where a {@code style} value lands since
     * R14 deleted {@code CTX_CSS}) are the empty string today, which is why relaxing them is the change
     * this file exists to gate.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("everyPayload")
    public void noEncoderCanEmitACharacterThatSteersTheParser(Payload payload) {
        int[] contexts = {Canoe.CTX_HTML, Canoe.CTX_HTML_ATTR, Canoe.CTX_JS, Canoe.CTX_URI,
                Canoe.CTX_SUPPRESS};

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
     * The suppressed contexts emit nothing at all, for every payload.
     *
     * <p>Recorded separately from the sweep above so that the difference is explicit: {@code CTX_JS}
     * passes the steering test <em>vacuously</em>, and the day it is relaxed to real escaping it stops
     * doing so and starts depending on {@code js()} being correct. This test is the one that fails
     * first when that happens, and it is the one whose failure means "now go and run everything
     * above".
     *
     * <p>CSS is checked through the route that actually reaches it: {@code ATTR_CSS} (the {@code style}
     * attribute) maps to {@code CTX_SUPPRESS}. R14 deleted {@code CTX_CSS}, so there is no separate CSS
     * context to encode against; the suppression is the {@code CTX_SUPPRESS} arm, and relaxing it is
     * the same undecided project (F23, R13) the {@code style} case in {@code Canoe.currentContext()}
     * records.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("everyPayload")
    public void theSuppressedContextsPassVacuouslyBecauseTheyEmitNothing(Payload payload) {
        assertEquals("", Canoe.encode(payload.value(), Canoe.CTX_JS),
                "CTX_JS suppression; when this changes, re-run the sweeps above before shipping it");
        assertEquals("", Canoe.encode(payload.value(), Canoe.CTX_SUPPRESS),
                "CTX_SUPPRESS is where a style (ATTR_CSS) value lands since R14 deleted CTX_CSS");
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
