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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The review's "What is not affected" claim, as executable evidence rather than as argument.
 *
 * <p>The claim bounds the exploitable surface of every finding in the review, and it is therefore the
 * single most consequential sentence in that document for anyone triaging an application: a reference
 * in ordinary HTML body position — {@code <p>$data</p>}, overwhelmingly the most common pattern —
 * cannot be used to reach any of the twenty-one findings. Templates that interpolate only into body
 * text are safe today and remain safe under all of them.
 *
 * <p>The argument is short. Body references get {@code CTX_HTML} &rarr;
 * {@code HtmlEncoder.htmlWhite()}, which is an allowlist rather than a denylist: exactly four
 * categories of character survive it, and <strong>{@code <} is not one of them</strong>. No {@code <}
 * means no tag injection, no comment injection, and no route into any of the attribute contexts where
 * the exploitable findings live. The attacker is confined to text.
 *
 * <h2>What this file does that the corpus does not</h2>
 *
 * <p>The two overlap by design and they answer different questions, so they should not be merged.
 *
 * <ul>
 *   <li><strong>The corpus is the per-case ledger.</strong> Its unit is a (template, payload) pair
 *       with a reviewed verdict attached, and {@code CanoeCorpusTest.ledgerMatchesObservedBehaviour}
 *       asserts each pair individually against an independently derived observation. It answers "what
 *       does this template do with this payload today, and did a human agree with that".
 *   <li><strong>This file asserts the property.</strong> Its unit is the claim itself — {@code <}
 *       cannot appear in the encoded value, end to end through a real render — quantified over every
 *       body-context case the corpus holds and every payload in the catalogue. It answers "and is
 *       that true of all of them, including ones nobody has written a case for yet".
 * </ul>
 *
 * <p>Merging them would lose whichever half was folded into the other: a property test cannot record
 * that {@code comment.body} is fail-closed-but-silent rather than safe, and a ledger of 80 individual
 * SAFE rows does not say that the property holds by construction. So this file <em>consumes</em> the
 * corpus rather than re-declaring templates ({@link #bodyContextInvocations()}), and adds the
 * quantified property, the end-to-end allowlist sweep, and the shapes a per-case ledger has no reason
 * to carry.
 *
 * <p>Note also the level. {@code HtmlEncoderTest.htmlWhiteCanNeverEmitALessThan} asserts the same
 * property of the encoder in isolation; this file asserts it of the <em>rendered document</em>, which
 * is a different claim. An encoder that could not emit {@code <} would still leave the page injectable
 * if the reference were routed to a different encoder, and routing is exactly what Canoe gets wrong
 * everywhere else.
 */
public class BodyContextTest {

    /** The Appendix A section the insertion-context cases are filed under. */
    private static final String SECTION = "A.1 insertion contexts";

    // ------------------------------------------------------------------
    // The corpus, consumed
    // ------------------------------------------------------------------

    /**
     * Every &sect;A.1 case whose reference sits in a text position rather than inside a tag.
     *
     * <p>Selected by sink kind rather than by an id list, so a body-context case added to the corpus
     * later is picked up here without anyone remembering to. {@code REJECTED} cases are excluded
     * because they have no encoded value to state a property about — {@code position.tag-name} and
     * its siblings are &sect;A.1 entries about where a reference may <em>not</em> go, and
     * {@code CanoeRobustnessTest} owns them.
     */
    static List<XssCase.Invocation> bodyContextInvocations() {
        List<XssCase.Invocation> result = new ArrayList<>();
        for (XssCase testCase : CanoeCorpus.inSection(SECTION)) {
            if (testCase.sink() != SinkKind.HTML_TEXT && testCase.sink() != SinkKind.NONE) {
                continue;
            }
            if (testCase.defaultVerdict() == Verdict.REJECTED) {
                continue;
            }
            result.addAll(testCase.invocations());
        }
        return result;
    }

    /**
     * The corpus records no body-context vulnerability, which is the review's claim stated over the
     * ledger rather than over the encoder.
     *
     * <p>This is deliberately an assertion about the <em>data</em>, not about behaviour: the
     * behaviour is asserted case by case by {@code CanoeCorpusTest}. What it catches is a corpus edit
     * that quietly ledgers a body-context row as vulnerable without anyone noticing that the review's
     * headline bound has just been broken.
     */
    @Test
    public void theCorpusRecordsNoVulnerabilityInBodyContext() {
        List<String> vulnerable = new ArrayList<>();
        for (XssCase.Invocation invocation : bodyContextInvocations()) {
            if (invocation.verdict() == Verdict.KNOWN_VULNERABLE) {
                vulnerable.add(invocation.toString() + " (" + invocation.testCase().finding() + ")");
            }
        }
        assertTrue(vulnerable.isEmpty(),
                () -> "The review's 'What is not affected' section says plain HTML body insertion"
                        + " cannot reach any finding, and the whole triage guidance rests on it."
                        + " These body-context rows now claim otherwise: " + vulnerable
                        + "\nEither the row is wrong, or that section is - and if it is that"
                        + " section, the fix is a finding, not an edit to this test.");
    }

    // ------------------------------------------------------------------
    // The property
    // ------------------------------------------------------------------

    /**
     * The decisive property, in its readable form: the characters a body reference actually
     * contributed to the rendered document include no {@code <}, {@code >}, {@code "} or {@code '}.
     *
     * <p><strong>The airtight half of this property lives elsewhere, deliberately.</strong>
     * {@code CanoeCorpusTest.payloadsCannotAddMarkupDelimitersToOutput} compares delimiter
     * <em>counts</em> against a render with an empty value, which is the form that cannot be wrong —
     * that render is the template's own literal text, so any delimiter beyond it came from the
     * payload. It already runs over a strict superset of these rows: every invocation in the corpus,
     * 886 of them, against these 82. This method used to re-run exactly that comparison first and
     * then do the extraction, which bought nothing but 82 duplicate assertions and an invitation to
     * "fix" one copy.
     *
     * <p>What is kept is the half the count comparison cannot give you: the extracted region names
     * the actual characters, so a failure reads as "the encoded value carries a raw quote" rather
     * than as "there is one more quote in the document than there should be". Its boundaries come
     * from a common-prefix/suffix diff and can be pulled inwards by a payload whose encoding happens
     * to start or end like the surrounding template, so it can under-report — which is precisely why
     * it is the readable half and not the load-bearing one.
     *
     * <p>Note that the rows whose reference is suppressed outright — {@code comment.body},
     * {@code doctype.internal-subset} and the rest of the {@code SinkKind.NONE} group, twelve
     * invocations — contribute the empty region and pass tautologically. They are left in rather than
     * filtered out because the selection is by sink kind on purpose (see
     * {@link #bodyContextInvocations()}), and a row that starts passing for a real reason if
     * suppression is ever fixed is better than a row that has to be remembered.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("bodyContextInvocations")
    public void aBodyReferenceCanNeverContributeAMarkupDelimiter(XssCase.Invocation invocation) {
        XssCase testCase = invocation.testCase();
        String rendered = VerdictEvaluator.render(testCase, invocation.payload().value()).output();
        String templateOnly = VerdictEvaluator.render(testCase, "").output();

        String contributed = contributedRegion(templateOnly, rendered);
        for (char delimiter : new char[]{'<', '>', '"', '\''}) {
            assertFalse(contributed.indexOf(delimiter) >= 0,
                    () -> "The encoded value itself carries a raw '" + delimiter + "'. In body"
                            + " context htmlWhite() must convert every one of them to a character"
                            + " reference, and '<' in particular is what the whole 'what is not"
                            + " affected' bound rests on.\n  case        : " + invocation
                            + "\n  contributed : " + CanoeTestSupport.quote(contributed)
                            + "\n  rendered    : " + CanoeTestSupport.quote(rendered));
        }
    }

    /**
     * Every payload in the catalogue, into the shape the claim is actually about.
     *
     * <p>The corpus gives {@code body.paragraph} seven families, which are the ones that mean
     * something there; this sweeps all of them, including the URL, CSS, policy and markup payloads
     * that have no business in body text — precisely because the claim is that <em>nothing</em>
     * reaches a sink from here, not that the families somebody thought to attack with do not.
     *
     * <p>The structural comparison is the generic injection oracle and needs no opinion about which
     * characters are dangerous: if the document's element and attribute shape is identical to the
     * benign render, the payload stayed inside the text node it was given.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("everyPayload")
    public void everyPayloadFamilyIsInertInAParagraph(Payload payload) {
        String template = "<p>$data</p>";
        CanoeTestSupport.RenderResult attacked = CanoeTestSupport.render(template, payload.value());
        CanoeTestSupport.RenderResult benign =
                CanoeTestSupport.render(template, Payloads.INERT_MARKER.value());

        assertFalse(attacked.isError(),
                () -> "body insertion must never raise an encoding error; " + payload.id()
                        + " did: " + attacked.errorMessage());
        assertEquals(count(CanoeTestSupport.render(template, "").output(), '<'),
                count(attacked.output(), '<'),
                () -> "the only '<' characters in the output must be the two the template itself"
                        + " wrote. Got: " + CanoeTestSupport.quote(attacked.output()));
        assertEquals(VerdictEvaluator.domSkeleton(benign.dom()),
                VerdictEvaluator.domSkeleton(attacked.dom()),
                () -> payload.id() + " changed the shape of the document, which means it escaped the"
                        + " text node it was placed in. Rendered: "
                        + CanoeTestSupport.quote(attacked.output()));
        assertEquals(Canoe.CTX_HTML, attacked.context(),
                () -> payload.id() + " left the parser somewhere other than HTML state");
    }

    static List<Payload> everyPayload() {
        return Payloads.all();
    }

    /**
     * The other half of "confined to text": the payload does not merely fail to escape, it arrives
     * <em>whole</em>.
     *
     * <p>Worth asserting because the alternative reading of a green delimiter sweep is that
     * {@code htmlWhite()} is dropping characters, which would be safe and useless. Restricted to
     * printable-ASCII payloads: {@code htmlWhite()} deliberately does not round-trip a C0 control (it
     * emits the four literal characters {@code \xNN}) or a lone surrogate (it emits an invalid
     * numeric reference the parser replaces with U+FFFD), and both of those are recorded in
     * {@code HtmlEncoderTest} rather than here.
     */
    @Test
    public void aPrintablePayloadReachesTheTextNodeWholeAndInert() {
        for (Payload payload : Payloads.all()) {
            if (!isPrintableAscii(payload.value())) {
                continue;
            }
            String decoded = CanoeTestSupport.render("<p>$data</p>", payload.value())
                    .dom().selectFirst("p").wholeText();
            assertEquals(payload.value(), decoded,
                    () -> payload.id() + " must arrive at the text node exactly as the attacker"
                            + " wrote it - encoded on the wire, decoded by the parser, and inert"
                            + " because a text node is not a parser");
        }
    }

    /**
     * The allowlist itself, end to end rather than at the encoder.
     *
     * <p>The review names exactly four categories of character that survive {@code htmlWhite()}, and
     * this is that list rendered through a template: letters and digits raw, the four whitespace
     * characters raw, everything else a numeric or named reference, and the remaining C0 controls as
     * the literal four-character text {@code \xNN}.
     */
    @Test
    public void theFourSurvivingCategoriesAreExactlyWhatTheReviewLists() {
        assertEquals("<p>abcXYZ0189</p>",
                CanoeTestSupport.render("<p>$data</p>", "abcXYZ0189").output(),
                "ASCII letters and digits pass through naked");

        assertEquals("<p>a b" + ch(0x09) + "c" + ch(0x0d) + ch(0x0a) + "d</p>",
                CanoeTestSupport.render("<p>$data</p>", "a b" + ch(0x09) + "c" + ch(0x0d)
                        + ch(0x0a) + "d").output(),
                "space, tab, CR and LF pass through raw - which is the only difference between"
                        + " htmlWhite() and html()");

        assertEquals("<p>&lt;&gt;&amp;&quot;&#39;&#47;&#61;</p>",
                CanoeTestSupport.render("<p>$data</p>", "<>&\"'/=").output(),
                "the seven delimiters get explicit named or numeric forms");

        assertEquals("<p>&#33;&#64;&#123;&#125;&#59;&#40;&#41;</p>",
                CanoeTestSupport.render("<p>$data</p>", "!@{};()").output(),
                "every other punctuation mark becomes a decimal reference");

        assertEquals("<p>a\\x01b\\x1Fc&#127;d</p>",
                CanoeTestSupport.render("<p>$data</p>",
                        "a" + ch(0x01) + "b" + ch(0x1f) + "c" + ch(0x7f) + "d").output(),
                "the remaining C0 controls become the literal four-character text \\xNN, which is"
                        + " why a control character in a PAYLOAD is safe while the same character in"
                        + " template literal text is fatal (see"
                        + " reject.control-character-in-template-text). Note DEL, which is not below"
                        + " 0x20 and so takes the ordinary reference branch rather than the \\xNN"
                        + " one - the review's fourth category is the C0 controls specifically, and"
                        + " U+007F is not one of them.");

        assertEquals("<p>&#128512;</p>",
                CanoeTestSupport.render("<p>$data</p>", ch(0xd83d) + ch(0xde00)).output(),
                "htmlWhite() iterates code points, so an astral character emits one correct"
                        + " reference rather than two surrogate ones");
    }

    /**
     * Proves the property above can fail.
     *
     * <p>Most payloads carry no markup delimiter at all, so a sweep that never sees one is
     * indistinguishable from a sweep that is not looking. The same non-blind-oracle argument the plan
     * makes for the browser tier's detectors.
     */
    @Test
    public void theLessThanFreedomPropertyWouldCatchAnUnencodedRender() {
        String encoded = CanoeTestSupport.render("<p>$data</p>", Payloads.TAG_IMG_ONERROR.value())
                .output();
        String unencoded = "<p>" + Payloads.TAG_IMG_ONERROR.value() + "</p>";

        long templateOnly = count(CanoeTestSupport.render("<p>$data</p>", "").output(), '<');
        assertEquals(templateOnly, count(encoded, '<'));
        assertTrue(count(unencoded, '<') > templateOnly,
                "an unencoded render of the same payload must carry more '<' than the template's"
                        + " own, or the delimiter comparison is not looking at anything");
        assertNotEquals(VerdictEvaluator.domSkeleton(
                        CanoeTestSupport.render("<p>$data</p>", Payloads.INERT_MARKER.value()).dom()),
                VerdictEvaluator.domSkeleton(org.jsoup.Jsoup.parse(unencoded)),
                "and the structural oracle must see the extra element, or it is not an oracle");
    }

    // ------------------------------------------------------------------
    // The shapes a per-case ledger has no reason to carry
    // ------------------------------------------------------------------

    /** Depth changes nothing: there is no element stack, only one state variable. */
    @Test
    public void nestingDoesNotChangeTheContext() {
        assertEquals(Canoe.CTX_HTML, CanoeTestSupport.contextAfter("<div><section><p>"));
        assertEquals(Canoe.CTX_HTML, CanoeTestSupport.contextAfter("<table><tr><td>"));
        assertEquals(Canoe.CTX_HTML, CanoeTestSupport.contextAfter("<svg><text>"));

        String deep = CanoeTestSupport
                .render("<div><section><p>$data</p></section></div>", Payloads.TAG_IMG_ONERROR.value())
                .output();
        assertEquals(3, count(deep, '<') - count(deep, "</"),
                "three opening tags and three closing ones, and nothing the payload added");
    }

    /**
     * Two references with nothing between them, and two with a tag boundary between them.
     *
     * <p>Both are bound to the payload here, which is what the corpus cases cannot do — an
     * {@link XssCase} binds one payload and fixes the rest of its model so that the sink under test
     * stays unambiguous. The point is that the encoder holds no state across insertions: an encoder
     * that carried anything from one call to the next would show up as a difference between the two
     * halves of the same render.
     */
    @Test
    public void adjacentReferencesAndReferencesEitherSideOfATagBoundary() {
        Map<String, Object> both = new LinkedHashMap<>();
        both.put("data", Payloads.TAG_IMG_ONERROR.value());
        both.put("second", Payloads.TAG_IMG_ONERROR.value());

        String adjacent = CanoeTestSupport.render("<p>$data$second</p>", both).output();
        String encodedOnce = CanoeTestSupport
                .render("<p>$data</p>", Payloads.TAG_IMG_ONERROR.value()).output();
        String oneValue = encodedOnce.substring("<p>".length(),
                encodedOnce.length() - "</p>".length());
        assertEquals("<p>" + oneValue + oneValue + "</p>", adjacent,
                "two adjacent references encode identically and independently");

        String acrossBoundary = CanoeTestSupport
                .render("<p>$data</p><div>$second</div>", both).output();
        assertEquals("<p>" + oneValue + "</p><div>" + oneValue + "</div>", acrossBoundary,
                "and so do two references separated by a tag boundary, which takes the machine out"
                        + " to TAG_NAME and back twice between the insertions");

        assertEquals(1 + 1 + 1 + 1, count(acrossBoundary, '<'),
                "the only '<' characters are the four the template wrote");
    }

    /**
     * RCDATA and RAWTEXT are both safe, and the review is careful to say they are safe for
     * <em>different</em> reasons. This asserts the difference rather than the conclusion, because the
     * conclusion is the same either way and would hide a regression in one of the two.
     *
     * <ul>
     *   <li>In <strong>RCDATA</strong> ({@code <textarea>}, {@code <title>}) the parser decodes
     *       character references while building the character data, so the attacker's raw
     *       {@code <img …>} does arrive — as text. A decoded {@code &lt;} is character data and never
     *       becomes a tag opener.
     *   <li>In <strong>RAWTEXT</strong> ({@code <xmp>}, {@code <noembed>}, legacy {@code <iframe>}
     *       content) references are not decoded at all, so the user is shown the escaped text
     *       spelled out. Ugly, and inert twice over.
     * </ul>
     *
     * <p>Canoe models neither: both resolve to {@code CTX_HTML}, which
     * {@code CanoeStateMachineTest} records. The safety is the HTML parser's, not Canoe's — the one
     * thing Canoe contributes is that {@code htmlWhite()} cannot emit the literal {@code </xmp} or
     * {@code </textarea} that would be needed to escape either.
     */
    @Test
    public void rcdataDecodesToTextAndRawtextDoesNotDecodeAtAll() {
        String payload = Payloads.TAG_IMG_ONERROR.value();

        for (String element : List.of("textarea", "title")) {
            String template = "<" + element + ">$data</" + element + ">";
            assertEquals(Canoe.CTX_HTML, CanoeTestSupport.contextAfter("<" + element + ">"),
                    element + " is RCDATA to the browser and plain HTML state to Canoe");
            assertEquals(payload,
                    CanoeTestSupport.render(template, payload).dom().selectFirst(element).wholeText(),
                    element + ": the parser decodes the references into character data, which is"
                            + " text and cannot open a tag");
        }

        for (String element : List.of("xmp", "noembed", "iframe")) {
            String template = "<" + element + ">$data</" + element + ">";
            CanoeTestSupport.RenderResult result = CanoeTestSupport.render(template, payload);
            String raw = result.dom().selectFirst(element).data();

            assertFalse(raw.isEmpty(),
                    element + ": expected the parser to expose this element's content as raw data,"
                            + " which is what makes it RAWTEXT");
            assertNotEquals(payload, raw,
                    element + ": in RAWTEXT the references are NOT decoded, so the attacker's"
                            + " original characters must not appear");
            assertTrue(raw.contains("&lt;img"),
                    element + ": the user is shown the escaped text spelled out. Got: " + raw);
            assertFalse(raw.indexOf('<') >= 0,
                    element + ": and there is no '<' to close the element with. Got: " + raw);
        }

        // <noscript> is the one element whose parsing depends on a browser setting. jsoup parses
        // with scripting disabled, which is the case where the content is ordinary markup rather
        // than RAWTEXT - the more dangerous of the two, and still safe, because the payload carries
        // no raw '<' in either mode.
        assertEquals(payload,
                CanoeTestSupport.render("<noscript>$data</noscript>", payload)
                        .dom().selectFirst("noscript").wholeText(),
                "with scripting disabled <noscript> content is parsed as markup; safe anyway");
    }

    /**
     * Comments, conditional comments and the DOCTYPE internal subset: everything is suppressed, and
     * that is fail-closed rather than correct.
     *
     * <p>{@code currentContext()} has no case for any {@code COMMENT_*} or {@code DOCTYPE*} state, so
     * they fall to {@code CTX_SUPPRESS} at the end of the switch. Safe, and part of F11's class: the
     * value vanishes with no error and no diagnostic, and the documented remedy is
     * {@code $_x.asis()}, which disables Canoe for that value entirely. A generator stamp or a build
     * marker built from a reference renders empty.
     *
     * <p>R19 closed F11's attribute-value half — {@code TAG_ATTR_VALUE_BEFORE} has a name-derived
     * answer waiting for it — and stopped there. These states have no encoder at all: interpolating
     * into a comment would need {@code -->} and the nested-comment rules modelled first. So the holes
     * here are the ones that remain, and they remain deliberately.
     */
    @Test
    public void everythingInsideACommentIsSuppressed() {
        assertEquals("<!--  -->",
                CanoeTestSupport.render("<!-- $data -->", Payloads.TAG_IMG_ONERROR.value()).output(),
                "an ordinary comment");
        assertEquals("<!--[if IE]><![endif]-->",
                CanoeTestSupport.render("<!--[if IE]>$data<![endif]-->",
                        Payloads.TAG_IMG_ONERROR.value()).output(),
                "a downlevel-hidden conditional comment is an ordinary comment to Canoe and to every"
                        + " browser still shipping");
        assertEquals("<!DOCTYPE >",
                CanoeTestSupport.render("<!DOCTYPE $data>", Payloads.TAG_IMG_ONERROR.value()).output(),
                "the DOCTYPE internal subset position");

        assertEquals(Canoe.CTX_SUPPRESS, CanoeTestSupport.contextAfter("<!-- "));
        assertEquals(Canoe.CTX_SUPPRESS, CanoeTestSupport.contextAfter("<!--[if IE]>"));
        assertEquals(Canoe.CTX_SUPPRESS, CanoeTestSupport.contextAfter("<!DOCTYPE "));

        // ...and, by contrast, the comment that used to never close now does (F14, closed by R16).
        // <!--a---> closes at the '>', so the following <p> is real markup again and its reference
        // renders in the text context - HTML-escaped, not suppressed, and not injected raw.
        CanoeTestSupport.RenderResult afterThreeDashClose =
                CanoeTestSupport.render("<!--a---><p>$data</p>", Payloads.TAG_IMG_ONERROR.value());
        assertTrue(afterThreeDashClose.output().startsWith("<!--a---><p>&lt;img"),
                "R16: <!--a---> closes, so the reference after it renders escaped in the <p>. Got: "
                        + afterThreeDashClose.output());
        assertFalse(afterThreeDashClose.output().contains("<img"),
                "R16: and there is no raw '<img' to inject with");
    }

    /**
     * A reference immediately before and immediately after a {@code <script>} block.
     *
     * <p>The machine leaves HTML for {@code SCRIPT} and comes back through {@code SCRIPT_END} and
     * {@code SCRIPT_END_NAME} between the two, and neither reference is affected. The second one is
     * the load-bearing assertion: if the end tag had not returned the machine to HTML the reference
     * after the block would be suppressed, which is what
     * {@code desync.script-stuck-on-a-double-less-than} showed happening when the block contained a
     * stray {@code <} — until R17 made the mismatching character be re-processed, so that row renders
     * its paragraph too now.
     */
    @Test
    public void aReferenceOnEitherSideOfAScriptBlockIsStillBodyContext() {
        Map<String, Object> both = new LinkedHashMap<>();
        both.put("data", Payloads.TAG_IMG_ONERROR.value());
        both.put("second", Payloads.TAG_IMG_ONERROR.value());

        String rendered = CanoeTestSupport
                .render("<p>$data</p><script>var a=1;</script><p>$second</p>", both).output();

        String oneValue = encodedValueOf(Payloads.TAG_IMG_ONERROR.value());
        assertEquals("<p>" + oneValue + "</p><script>var a=1;</script><p>" + oneValue + "</p>",
                rendered,
                "both references are encoded for body context, and the script body between them"
                        + " changes nothing about either");

        assertEquals(Canoe.CTX_HTML, CanoeTestSupport.contextAfter("<script>var a=1;</script><p>"),
                "SCRIPT_END returned the machine to HTML");
        assertEquals(Canoe.CTX_JS, CanoeTestSupport.contextAfter("<script>"),
                "...and inside the block it is CTX_JS, which encodes to the empty string by design");

        // The contrast, so the assertion above is not just a fact about one template.
        assertEquals("<script>var x = '';</script>",
                CanoeTestSupport.render("<script>var x = '$data';</script>",
                        Payloads.QUOTE_SINGLE_BREAKOUT.value()).output(),
                "refusing to output into a script body is the centrepiece of the design, and it is"
                        + " the reason the two body references either side of it are the only places"
                        + " on this page a value can appear");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** What {@code <p>$data</p>} renders the payload as, with the template's own text removed. */
    private static String encodedValueOf(String payload) {
        String rendered = CanoeTestSupport.render("<p>$data</p>", payload).output();
        return rendered.substring("<p>".length(), rendered.length() - "</p>".length());
    }

    /**
     * The characters the reference contributed, found by removing the longest common prefix and
     * suffix the template-only render shares with the attacked one.
     *
     * <p>Conservative by construction: if the encoded value happens to begin or end like the template
     * text around it, the region comes out shorter than the truth rather than longer, so it can
     * under-report and never over-report. That is why the caller also asserts the airtight count
     * comparison.
     */
    private static String contributedRegion(String templateOnly, String rendered) {
        int prefix = 0;
        int limit = Math.min(templateOnly.length(), rendered.length());
        while (prefix < limit && templateOnly.charAt(prefix) == rendered.charAt(prefix)) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < limit - prefix
                && templateOnly.charAt(templateOnly.length() - 1 - suffix)
                        == rendered.charAt(rendered.length() - 1 - suffix)) {
            suffix++;
        }
        return rendered.substring(prefix, rendered.length() - suffix);
    }

    private static boolean isPrintableAscii(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c > 0x7e) {
                return false;
            }
        }
        return true;
    }

    private static long count(String haystack, char needle) {
        return haystack.chars().filter(c -> c == needle).count();
    }

    private static long count(String haystack, String needle) {
        long total = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            total++;
            at = haystack.indexOf(needle, at + 1);
        }
        return total;
    }

    /**
     * A one-character string from a code unit, so that this file stays pure ASCII and cannot be
     * corrupted by a compiler running under a non-UTF-8 default charset.
     */
    private static String ch(int codeUnit) {
        return String.valueOf((char) codeUnit);
    }
}
