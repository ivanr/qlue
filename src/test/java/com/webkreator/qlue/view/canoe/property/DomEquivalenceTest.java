package com.webkreator.qlue.view.canoe.property;

import com.webkreator.qlue.view.canoe.CanoeTestSupport;
import com.webkreator.qlue.view.canoe.corpus.CanoeCorpus;
import com.webkreator.qlue.view.canoe.corpus.Payload;
import com.webkreator.qlue.view.canoe.corpus.Payloads;
import com.webkreator.qlue.view.canoe.corpus.VerdictEvaluator;
import com.webkreator.qlue.view.canoe.corpus.XssCase;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The differential oracle: <strong>render twice, parse both, and require the document's shape to be
 * the same.</strong>
 *
 * <p>Every other assertion in this suite encodes somebody's opinion about which characters are
 * dangerous. That opinion is exactly what failed in {@code setTagAttributeContext()} — F1, F2, F3 and
 * F19 are all "the author listed the things they thought of" — so the highest-yield test for
 * vulnerabilities <em>nobody has written down</em> is one that holds no such opinion. This is it. It
 * renders each corpus case once with the inert marker and once with each payload, parses both with
 * jsoup, and compares element count, tag names in order, and the attribute names on each element. If
 * the shapes match, the payload stayed inside the value it was meant to occupy. If they diverge, the
 * payload created structure, and that is an injection whether or not anyone predicted it.
 *
 * <p>Measured over all {@code CanoeCorpus.all()} templates and all {@code Payloads.all()} values —
 * every case against every payload, no sampling and no exclusions:
 * <strong>no divergence anywhere.</strong> There is nothing to triage into the ledger.
 *
 * <h2>What this cannot see, stated honestly</h2>
 *
 * <p>A structural oracle catches structural injection: a new element, a new attribute, an element
 * that ended early. It is blind to <em>value-level</em> injection into a live sink, because those
 * leave the document shape untouched:
 *
 * <ul>
 *   <li><strong>{@code srcdoc}</strong> (F3) — the payload is markup, but it is markup inside one
 *       attribute of one {@code <iframe>}; the outer document has the same skeleton either way. The
 *       injected document is a different document, and this oracle only parses the outer one.
 *   <li><strong>{@code javascript:} URLs</strong> (F5, F17) — {@code <a href="javascript:...">} is
 *       one element with one attribute regardless of what the URL says.
 *   <li><strong>CSS</strong> (F4) — a {@code style} attribute carrying a full-viewport overlay and a
 *       beacon is still one attribute on one element.
 *   <li><strong>Event handlers</strong> (F1, F2, F19) — {@code onsubmit="v('');alert(1)//')"} has the
 *       same attribute name as the benign render.
 *   <li><strong>Policy attributes</strong> (F20) — a {@code sandbox} value that removes every
 *       restriction is structurally identical to one that adds them.
 * </ul>
 *
 * <p>So this file proves one thing completely and says nothing about the rest. The rest is covered by
 * the sink-liveness assertions ({@code UrlSinkTest}, {@code EventHandlerMatrixTest},
 * {@code CssContextTest}, and {@code VerdictEvaluator}'s per-sink judgements) and, when it lands, by
 * the browser tier, which asserts on effects rather than on shape. Recording the limitation here
 * rather than leaving it implied matters, because "the DOM oracle is green" reads like "nothing got
 * through" and it does not mean that.
 *
 * <h2>Relationship to the ledger</h2>
 *
 * <p>{@code VerdictEvaluator.judgeStructurally} already uses {@link VerdictEvaluator#domSkeleton}
 * for the {@code HTML_TEXT} and {@code PLAIN_TEXT_ATTR} sinks — about half the corpus — and
 * {@code CanoeCorpusTest.ledgerMatchesObservedBehaviour} asserts the result against the reviewed
 * verdict. This file runs the same comparison over <em>every</em> sink kind and every payload,
 * including the pairings no case declares, which is where an unknown vulnerability would be. The two
 * are not duplicates: there, a divergence changes a verdict; here, a divergence is a finding.
 */
public class DomEquivalenceTest {

    static List<XssCase> corpus() {
        return CanoeCorpus.all();
    }

    // ------------------------------------------------------------------
    // The oracle
    // ------------------------------------------------------------------

    /**
     * No payload changes the shape of the document, in any corpus template.
     *
     * <p>Quantified over the whole payload catalogue rather than over the payloads each case
     * declares. A case's list says what is worth attacking that sink with; this asks what any value
     * can do to any document, which is the question an unknown vulnerability would answer
     * unexpectedly. All 275 templates against all 52 payloads is 14,300 pairs and runs in well under
     * a second, so there is no reason to sample.
     *
     * <p>Divergences are collected rather than thrown at the first, because the first thing anyone
     * would want from a failure is the shape of the group that failed.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("corpus")
    public void noPayloadChangesTheShapeOfTheDocument(XssCase testCase) {
        String benign = skeleton(testCase, Payloads.INERT_MARKER.value());
        List<String> divergences = new ArrayList<>();

        for (Payload payload : Payloads.all()) {
            String attacked = skeleton(testCase, payload.value());
            if (!benign.equals(attacked)) {
                divergences.add(payload.id()
                        + "\n      inert    : " + benign
                        + "\n      attacked : " + attacked);
            }
        }

        assertTrue(divergences.isEmpty(),
                () -> testCase.id() + ": " + divergences.size() + " payload(s) changed the document"
                        + " structure. Each one is an injection - the payload left the value it was"
                        + " meant to occupy - and each needs triaging into the ledger with a finding"
                        + " reference, per PLAN.md section 8."
                        + "\n  Template: " + CanoeTestSupport.quote(testCase.template())
                        + "\n  " + String.join("\n  ", divergences));
    }

    /**
     * The oracle must be able to fail, and unencoded markup must be what makes it.
     *
     * <p>&sect;2.4 requires this of the browser detectors and the same argument applies here with more
     * force, because a structural comparison that silently compared two empty skeletons would be
     * green over the entire corpus and would look exactly like today's result. That is not
     * hypothetical: {@code domSkeleton} used to select over {@code document.body()}, which made every
     * {@code <head>}-hoisted case compare {@code body[]} against {@code body[]} — fifteen invocations
     * asserting nothing (&sect;0.10).
     *
     * <p>Each row below is a real injection into a real template, through the {@code $_x.asis()}
     * bypass because that is the supported way to put unencoded bytes into the output. Every one must
     * be caught.
     */
    @Test
    public void aDeliberatelyUnencodedRenderBreaksTheOracle() {
        assertOracleCatches("<p>$_x.asis($data)</p>", Payloads.TAG_IMG_ONERROR.value(),
                "a new element in body text");
        assertOracleCatches("<a title=\"$_x.asis($data)\">x</a>",
                Payloads.ATTR_DOUBLE_QUOTE_BREAKOUT.value(),
                "a new attribute, by terminating the value early");
        assertOracleCatches("<p>$_x.asis($data)</p>", Payloads.TAG_SCRIPT.value(),
                "a script element, which the parser hoists nowhere and which body-only selection"
                        + " would still have caught");
        assertOracleCatches("$_x.asis($data)<p>x</p>", Payloads.TAG_SCRIPT.value(),
                "...and an injected <script> at the top of the document, which the HTML parser"
                        + " hoists into <head> - exactly the shape a skeleton selected over"
                        + " document.body() was blind to before section 0.10 fixed it");
    }

    private static void assertOracleCatches(String template, String payload, String what) {
        Map<String, Object> benignModel = new LinkedHashMap<>();
        benignModel.put("data", Payloads.INERT_MARKER.value());
        Map<String, Object> attackedModel = new LinkedHashMap<>();
        attackedModel.put("data", payload);

        String benign = skeletonOf(CanoeTestSupport.render(template, benignModel).output());
        String attacked = skeletonOf(CanoeTestSupport.render(template, attackedModel).output());

        assertNotEquals(benign, attacked,
                () -> "the oracle failed to notice " + what + " in " + template
                        + "\n  inert    : " + benign + "\n  attacked : " + attacked);
    }

    /**
     * The encoded control for each self-test row: the identical payload through an ordinary
     * reference must <em>not</em> move the skeleton.
     *
     * <p>Without this pair, {@link #aDeliberatelyUnencodedRenderBreaksTheOracle} would be consistent
     * with an oracle that reports a divergence whenever the value changes at all — which is a
     * different oracle, and a useless one.
     */
    @Test
    public void theSamePayloadsThroughAnEncodedReferenceDoNotMoveTheSkeleton() {
        for (String template : List.of("<p>$data</p>", "<a title=\"$data\">x</a>",
                "$data<p>x</p>")) {
            for (Payload payload : List.of(Payloads.TAG_IMG_ONERROR, Payloads.TAG_SCRIPT,
                    Payloads.ATTR_DOUBLE_QUOTE_BREAKOUT)) {
                String benign = skeletonOf(
                        CanoeTestSupport.render(template, Payloads.INERT_MARKER.value()).output());
                String attacked = skeletonOf(
                        CanoeTestSupport.render(template, payload.value()).output());
                assertEquals(benign, attacked,
                        () -> "the encoder held for " + payload.id() + " in " + template);
            }
        }
    }

    // ------------------------------------------------------------------
    // The limitation, as tests rather than as prose
    // ------------------------------------------------------------------

    /**
     * The blind spot, demonstrated rather than described: four templates the ledger records as
     * {@code KNOWN_VULNERABLE} whose skeletons are identical under attack.
     *
     * <p>This is the most important test in the file, because it is the one that stops a green run
     * from being read as "nothing got through". Each row is a real, cited vulnerability that this
     * oracle cannot see, and each names the test that does see it. If a future change ever made one
     * of these divergent, that would be a strictly better world and this test would fail — which is
     * the right way round.
     */
    @Test
    public void structuralEquivalenceDoesNotMeanSafeAndHereAreFourProofs() {
        assertBlindTo("markup.srcdoc", Payloads.SRCDOC_MARKUP,
                "F3: the injected markup lives inside one attribute of one <iframe>; the injected"
                        + " document is a different document and this oracle parses the outer one");
        assertBlindTo("residue.js-url-armed-buffer", Payloads.QUOTE_SINGLE_BREAKOUT,
                "F5: a javascript: URL whose prefix detection was disarmed by an 11-character"
                        + " attribute name above it is still one attribute on one element");
        assertBlindTo("css.style-with-property", Payloads.CSS_OVERLAY,
                "F4: a full-viewport overlay with a beacon in it is one style attribute");
        assertBlindTo("handler.onsubmit", Payloads.QUOTE_SINGLE_BREAKOUT,
                "F1: the attribute name is unchanged; only the JavaScript inside it is not");
    }

    private static void assertBlindTo(String caseId, Payload payload, String why) {
        XssCase testCase = CanoeCorpus.byId(caseId);
        assertEquals(skeleton(testCase, Payloads.INERT_MARKER.value()),
                skeleton(testCase, payload.value()),
                () -> caseId + " / " + payload.id() + ": " + why);
        assertNotEquals(VerdictEvaluator.render(testCase, Payloads.INERT_MARKER.value()).output(),
                VerdictEvaluator.render(testCase, payload.value()).output(),
                () -> caseId + ": the bytes must differ even though the shape does not, or this row"
                        + " is not demonstrating a blind spot at all");
    }

    /**
     * The skeleton records attribute <em>names</em> and not values, which is the mechanical reason
     * for the blind spot above.
     *
     * <p>Stated directly against {@link VerdictEvaluator#domSkeleton} so the limitation is a property
     * of a named function rather than a claim about a set of examples. Widening it to include values
     * would not be an improvement: it would turn every encoded render into a divergence, since
     * encoding changes the value by design, and the oracle would report the whole corpus.
     */
    @Test
    public void theSkeletonRecordsAttributeNamesAndDeliberatelyNotValues() {
        String a = skeletonOf("<a href=\"/safe\">x</a>");
        String b = skeletonOf("<a href=\"javascript:alert(1)\">x</a>");
        assertEquals(a, b, "two different href values, one skeleton");

        String c = skeletonOf("<a href=\"/safe\" onclick=\"x\">x</a>");
        assertNotEquals(a, c, "a new attribute name is a divergence");

        assertTrue(a.contains("a[href]"),
                () -> "the skeleton names the element and its attributes: " + a);
    }

    /**
     * Attribute order does not affect the skeleton, and element order does.
     *
     * <p>Both halves are deliberate and neither is obvious. Sorting attribute names means a template
     * that reorders attributes is not reported, which is right — the HTML parser does not care about
     * attribute order either. Keeping elements in document order means an injection that moves an
     * element rather than adding one is still caught, which is the {@code <table>} foster-parenting
     * case among others.
     */
    @Test
    public void attributeOrderIsIgnoredAndElementOrderIsNot() {
        assertEquals(skeletonOf("<a href=\"/x\" title=\"t\">y</a>"),
                skeletonOf("<a title=\"t\" href=\"/x\">y</a>"),
                "attribute names are sorted, because the parser does not care about their order");
        assertNotEquals(skeletonOf("<p>a</p><div>b</div>"),
                skeletonOf("<div>b</div><p>a</p>"),
                "element order is document order, so an injection that relocates an element is"
                        + " still a divergence");
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    /**
     * The document skeleton for one case and one value.
     *
     * <p>A rejected template still has a skeleton: {@code Canoe} writes everything it accepted before
     * giving up, and that partial output is what a real response would contain, so it is parsed like
     * any other. Rejection cases are therefore compared on the shape of their partial output rather
     * than skipped — which is the same argument that removed the early return from
     * {@code CanoeCorpusTest.payloadsCannotAddMarkupDelimitersToOutput} (&sect;0.10).
     */
    private static String skeleton(XssCase testCase, String value) {
        return skeletonOf(VerdictEvaluator.render(testCase, value).output());
    }

    private static String skeletonOf(String html) {
        return VerdictEvaluator.domSkeleton(Jsoup.parse(html));
    }
}
