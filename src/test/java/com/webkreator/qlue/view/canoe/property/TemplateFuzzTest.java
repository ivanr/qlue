package com.webkreator.qlue.view.canoe.property;

import com.webkreator.qlue.view.canoe.CanoeTestSupport;
import com.webkreator.qlue.view.canoe.corpus.Payload;
import com.webkreator.qlue.view.canoe.corpus.Payloads;
import com.webkreator.qlue.view.canoe.corpus.VerdictEvaluator;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A bounded random-template generator, run through the oracles of T21–T24 (T31).
 *
 * <h2>What it is for</h2>
 *
 * <p>The corpus is 275 templates somebody chose. {@code DomEquivalenceTest} runs the structural
 * oracle over exactly those, so it can only find an injection in a shape somebody already thought
 * to write down — which is the same limitation that produced F1, F2, F3 and F19 in
 * {@code setTagAttributeContext()} itself. This file removes the choosing: it builds templates from
 * a grammar and puts the reference wherever the grammar allows, so the shapes are generated rather
 * than curated.
 *
 * <h2>The oracle</h2>
 *
 * <p>Four properties per (template, payload) pair, in increasing strength and decreasing cost:
 *
 * <ol>
 *   <li><strong>Rejection is a property of the template.</strong> Whether Canoe raises an encoding
 *       error, and which error, must not depend on the payload. This is
 *       {@code CanoeCorpusTest.payloadsCannotAddMarkupDelimitersToOutput}'s rejection half.
 *   <li><strong>No new markup delimiter.</strong> The counts of {@code <}, {@code >}, {@code "} and
 *       {@code '} in the output must be identical to a render with an empty value. This is the
 *       airtight form of the review's decisive property and it holds in partial output too.
 *   <li><strong>No structural divergence.</strong> The jsoup skeleton — element order, tag names,
 *       attribute names — must be identical to a render with the inert marker. T24's oracle.
 *   <li><strong>No steering.</strong> The sequence of {@code currentContext()} values observed at
 *       each reference position must be identical to the inert render's. T23's property, which is
 *       the one that would notice a payload moving the parser without changing the document.
 * </ol>
 *
 * <p>Property 2 is strictly stronger than property 3 for the shapes this generator produces, and
 * both are kept because they fail differently: a delimiter-count failure says <em>which character</em>
 * escaped, and a skeleton failure says <em>what it built</em>.
 *
 * <h2>Determinism, and how to hunt</h2>
 *
 * <p>The seed and the iteration count come from system properties, so the hermetic {@code test} run
 * is reproducible and a local hunt is not bounded by it. {@code build.gradle} pins
 * {@code canoe.fuzz.seed=20260726} and {@code canoe.fuzz.iterations=2000}; both can be overridden:
 *
 * <pre>{@code
 * # reproduce exactly what CI ran
 * ./gradlew test --tests '*TemplateFuzzTest*'
 *
 * # a long, differently seeded hunt
 * ./gradlew test --tests '*TemplateFuzzTest*' \
 *     -Dcanoe.fuzz.seed=$RANDOM -Dcanoe.fuzz.iterations=1000000
 * }</pre>
 *
 * <p>A failure prints the seed, the iteration number, the <em>minimised</em> template and the
 * payload. Minimisation is delta debugging over the fragment list: fragments are dropped one at a
 * time and the drop is kept whenever the failure survives, which usually reduces a twelve-element
 * document to the two or three fragments that matter.
 *
 * <h2>Result: one counterexample, on the first run</h2>
 *
 * <p>The first run of this file failed property 4, on the template {@code <a href="${data}} against
 * {@code ABSOLUTE_OFFSITE/userinfo}: the contexts were
 * {@code [CTX_URI, CTX_URI]} for the marker and {@code [CTX_URI, CTX_HTML_ATTR]} for the payload.
 * That is <strong>F24</strong>, and it was the first counterexample anyone had produced to the
 * review's corollary that attacker data can never steer the parser. The old {@code HtmlEncoder.url()}
 * copied a matched {@code http://} or {@code https://} prefix into the output with its colon intact,
 * Canoe's value scan treated that colon as a prefix delimiter, and every later reference in the same
 * attribute value dropped from {@code url()} to {@code html()}.
 *
 * <p><strong>R2, R11 and R12 closed it, and the exemption is gone.</strong> R2 made
 * {@code detectAttributePrefix()} narrow-only, so no colon could steer; R11 deleted the prefix
 * passthrough and R12 rewrote {@code url()} to emit a colon only as an allowlisted scheme separator,
 * from its parse, never copied from the input. Property 4 now holds outright, so the
 * {@code isTheKnownColonSteering} exemption {@code check()} used to carry is removed, and the run
 * asserts that the number of pairs steering the parser via a colon is <strong>zero</strong> rather
 * than non-zero. That is the "Done when" for R11 stated as a measurement: a raw colon still appears
 * in the output of an absolute {@code https} URL, but it no longer moves any context, so counting
 * colons is no longer counting steering.
 *
 * <p><strong>Nothing else.</strong> With that one mechanism gone, no other violation of any
 * of the four properties appears — measured at 2,000 iterations x 5 payloads on the pinned seed, and
 * at 200,000 iterations x 5 payloads (one million pairs) on the pinned seed in a soak run on
 * 2026-07-26.
 *
 * <p>The plan's rule for a counterexample is that it is minimised and promoted into
 * {@code CanoeCorpus} as a permanent case. This one is not, and the reason is the precedent
 * &sect;0.15 set for F17: the corpus runs one shared payload catalogue against every template, which
 * is what makes it a fair comparison, and F24 needs <em>two</em> references in one attribute value
 * with a specific value in the first. It gets the dedicated test written for it instead, in the file
 * whose property it breaks.
 */
public class TemplateFuzzTest {

    /** Overridden from {@code build.gradle}; the default keeps an IDE run reproducible too. */
    private static final long SEED = Long.getLong("canoe.fuzz.seed", 20260726L);

    /** Iterations per run. Each iteration renders one template with the marker and five payloads. */
    private static final int ITERATIONS = Integer.getInteger("canoe.fuzz.iterations", 2000);

    /** Payloads attacked per generated template. */
    private static final int PAYLOADS_PER_TEMPLATE = 5;

    /**
     * The reference the generator plants; bound by {@link CanoeTestSupport#render(String, String)}.
     *
     * <p>Formal notation, and not for tidiness. Velocity's reference names are greedy, so
     * {@code $data} followed by a fragment beginning with a letter parses as {@code $dataplain} —
     * and under {@code runtime.strict_mode.enable}, which production sets and this harness mirrors,
     * an undefined reference is a {@code MethodInvocationException} rather than literal text. The
     * generator would then spend its budget on Velocity errors instead of on Canoe.
     * {@code VelocityIntegrationTest} (T19) asserts that the formal form's output is byte-identical
     * to the short form, so this costs nothing in fidelity.
     */
    private static final String REFERENCE = "${data}";

    // ------------------------------------------------------------------
    // The run
    // ------------------------------------------------------------------

    /**
     * The main loop. Fails on the first counterexample, after minimising it.
     *
     * <p>Fails fast rather than collecting, because a counterexample here is a finding rather than a
     * row: the useful output is one minimal template, not a histogram.
     */
    @Test
    public void noGeneratedTemplateLetsAPayloadEscapeItsPosition() {
        Random random = new Random(SEED);
        List<Payload> catalogue = Payloads.all();
        int pairs = 0;
        int colonSteerings = 0;

        for (int iteration = 0; iteration < ITERATIONS; iteration++) {
            List<String> fragments = generate(random);

            for (int p = 0; p < PAYLOADS_PER_TEMPLATE; p++) {
                Payload payload = catalogue.get(random.nextInt(catalogue.size()));
                pairs++;
                if (steersTheParserViaAColon(join(fragments), payload.value())) {
                    colonSteerings++;
                }

                String violation = check(join(fragments), payload.value());
                if (violation != null) {
                    List<String> minimal = minimise(fragments, payload.value());
                    throw new AssertionError(
                            "Fuzz counterexample at iteration " + iteration + " of " + ITERATIONS
                                    + " (seed " + SEED + ")."
                                    + "\n  payload   : " + payload.id() + " = "
                                    + CanoeTestSupport.quote(payload.value())
                                    + "\n  generated : " + CanoeTestSupport.quote(join(fragments))
                                    + "\n  minimised : " + CanoeTestSupport.quote(join(minimal))
                                    + "\n  violation : " + check(join(minimal), payload.value())
                                    + "\n\nMinimise further by hand if needed, then promote the"
                                    + " template into CanoeCorpus as a permanent case with a"
                                    + " reviewed verdict and a finding reference."
                                    + "\n  Reproduce with: -Dcanoe.fuzz.seed=" + SEED
                                    + " -Dcanoe.fuzz.iterations=" + ITERATIONS);
                }
            }
        }

        assertEquals(ITERATIONS * PAYLOADS_PER_TEMPLATE, pairs,
                "every iteration must have been attacked with " + PAYLOADS_PER_TEMPLATE
                        + " payloads; a smaller number means the loop was short-circuited");

        // R11's "Done when", as a measurement: F24 is closed, so no generated pair steers the parser
        // via a colon. This used to assert the count was NON-zero, evidencing an exemption; the
        // exemption is gone with the finding and the assertion is inverted.
        assertEquals(0, colonSteerings,
                "seed " + SEED + " generated a pair that steered the parser via a colon, which"
                        + " R2, R11 and R12 were supposed to have made impossible. A raw colon that"
                        + " moves a context is F24 back from the dead.");
    }

    /**
     * Whether a colon in the attacked output actually <em>moved</em> the parser relative to the inert
     * render — the thing R2, R11 and R12 abolished. A colon still appears in the output of an absolute
     * {@code https} URL, so counting colons is not counting steering; this requires both a colon
     * increase and a context divergence, which is exactly the F24 signature and is now never true.
     */
    private static boolean steersTheParserViaAColon(String template, String value) {
        ContextRecordingCanoe benignContexts = new ContextRecordingCanoe(new java.io.StringWriter());
        ContextRecordingCanoe attackedContexts = new ContextRecordingCanoe(new java.io.StringWriter());
        recordContexts(template, Payloads.INERT_MARKER.value(), benignContexts);
        recordContexts(template, value, attackedContexts);
        if (benignContexts.contexts().equals(attackedContexts.contexts())) {
            return false;
        }
        CanoeTestSupport.RenderResult benign =
                CanoeTestSupport.render(template, Payloads.INERT_MARKER.value());
        CanoeTestSupport.RenderResult attacked = CanoeTestSupport.render(template, value);
        return count(attacked.output(), ':') > count(benign.output(), ':');
    }

    // ------------------------------------------------------------------
    // The oracle
    // ------------------------------------------------------------------

    /**
     * Returns a description of the first property the pair violates, or null when all four hold.
     *
     * <p>A string rather than an assertion so that the minimiser can ask the same question
     * repeatedly without catching {@link AssertionError}, and so that the failure message can quote
     * the violation of the <em>minimised</em> template rather than of the original.
     */
    private static String check(String template, String value) {
        ContextRecordingCanoe attackedContexts = null;
        ContextRecordingCanoe benignContexts = null;

        CanoeTestSupport.RenderResult empty = CanoeTestSupport.render(template, "");
        CanoeTestSupport.RenderResult benign =
                CanoeTestSupport.render(template, Payloads.INERT_MARKER.value());
        CanoeTestSupport.RenderResult attacked = CanoeTestSupport.render(template, value);

        // 1. Rejection is a property of the template.
        if (benign.isError() != attacked.isError()) {
            return "the payload changed whether Canoe rejects the template: benign "
                    + (benign.isError() ? "rejected" : "accepted") + ", attacked "
                    + (attacked.isError() ? "rejected" : "accepted");
        }
        if (benign.isError() && !withoutPosition(benign.errorMessage())
                .equals(withoutPosition(attacked.errorMessage()))) {
            return "the payload changed which error Canoe raised: " + benign.errorMessage()
                    + " vs " + attacked.errorMessage();
        }

        // 2. No new markup delimiter, in full or partial output.
        for (char delimiter : new char[]{'<', '>', '"', '\''}) {
            long before = count(empty.output(), delimiter);
            long after = count(attacked.output(), delimiter);
            if (before != after) {
                return "the payload changed the number of '" + delimiter + "' characters from "
                        + before + " to " + after + " (output "
                        + CanoeTestSupport.quote(attacked.output()) + ")";
            }
        }

        // 3. No structural divergence - unless the value was suppressed, in which case there is no
        //    attacker byte in the output to have diverged anything.
        //
        //    The exemption is VerdictEvaluator.observe()'s first step, imported here because R19 is
        //    where the two oracles first disagreed. The corpus oracle compares the attacked render
        //    against a render with an empty value before it judges any sink, and calls an exact match
        //    a suppression rather than looking for an injection in it; this oracle only ever compared
        //    against the INERT_MARKER render, which cannot tell "the payload built markup" from "the
        //    payload was suppressed and the marker was not".
        //
        //    R19 made that distinction reachable. `<img src=${data} alt="a">` is a generator host, and
        //    until R19 it rendered `<img src= alt="a">` for every value including the marker, so both
        //    sides were equally broken and the property held vacuously. Now the marker renders and a
        //    rejected scheme does not, and an unquoted attribute whose value is EMPTY swallows the
        //    next attribute - the browser reads `alt="a"` as src's unquoted value, so the skeleton
        //    loses `alt`. That is data loss and it is worth knowing about (see
        //    UnquotedAttributeValueTest.anEmptyUnquotedValueSwallowsTheNextAttribute), but it is not
        //    an injection: the output contains no character the payload contributed, which is exactly
        //    what `attacked.output().equals(empty.output())` establishes. It is also strictly better
        //    than what F11 did, which was to render every unquoted value empty unconditionally.
        //
        //    Property 2 above is unaffected and still runs against the empty render, so a payload that
        //    adds a delimiter is caught whether or not it was suppressed, and property 4 still runs.
        if (!attacked.output().equals(empty.output())) {
            String benignShape = VerdictEvaluator.domSkeleton(Jsoup.parse(benign.output()));
            String attackedShape = VerdictEvaluator.domSkeleton(Jsoup.parse(attacked.output()));
            if (!benignShape.equals(attackedShape)) {
                return "the document skeleton diverged: " + benignShape + " vs " + attackedShape;
            }
        }

        // 4. No steering: the contexts seen at each reference position must be identical.
        benignContexts = new ContextRecordingCanoe(new java.io.StringWriter());
        attackedContexts = new ContextRecordingCanoe(new java.io.StringWriter());
        recordContexts(template, Payloads.INERT_MARKER.value(), benignContexts);
        recordContexts(template, value, attackedContexts);
        if (!benignContexts.contexts().equals(attackedContexts.contexts())) {
            // No exemption any more. R2 made detectAttributePrefix() narrow-only and R11/R12 stopped
            // url() emitting a colon that is not an allowlisted scheme separator, so a divergence
            // here is a real steering mechanism and belongs on the failure path - which is where F24
            // used to be exempted from.
            return "the payload moved the parser: contexts " + benignContexts.contexts()
                    + " vs " + attackedContexts.contexts();
        }

        return null;
    }

    /**
     * Renders through a supplied {@link ContextRecordingCanoe} so the context at each reference is
     * observable. Errors are swallowed: property 1 has already compared them, and a rejected render
     * still produces the contexts it saw before it stopped.
     */
    private static void recordContexts(String template, String value, ContextRecordingCanoe canoe) {
        try {
            CanoeTestSupport.render(template, java.util.Map.of("data", value),
                    CanoeTestSupport.RenderOptions.defaults(), writer -> canoe);
        } catch (RuntimeException e) {
            // Property 1 owns rejection; this call is only here for the context sequence.
        }
    }

    // ------------------------------------------------------------------
    // The generator
    // ------------------------------------------------------------------

    /**
     * Builds one template as a list of fragments, exactly one of which is the reference.
     *
     * <p>A list rather than a string because the minimiser works by dropping fragments, and a
     * fragment boundary is the only place a drop can leave something Canoe will still parse.
     *
     * <p>The shapes are constrained to what Canoe accepts, which is narrower than HTML: it rejects a
     * DOCTYPE that follows an element, and a literal {@code <} in text. Generating those would turn
     * the run into a rejection benchmark instead of an injection hunt. Some rejection is still
     * generated deliberately — the unterminated shapes below — because property 1 is about rejected
     * renders and property 2 has to hold in partial output.
     *
     * <p>Three shapes <em>were</em> in that excluded list and are now generated, on the reasoning
     * the {@link #NOISE} javadoc gives for keeping F5's fragments: the shapes a fixed finding makes
     * reachable are exactly the ones the fuzzer had never been able to explore. A comment above the
     * DOCTYPE, which F18 rejected and R18 made legal; a second DOCTYPE and {@code <br/>} on a void
     * element, both of which R20 made legal. The second DOCTYPE is emitted at the same point as the
     * first, so a run explores documents with two of them, which is what a layout plus an included
     * fragment produces.
     */
    private static List<String> generate(Random random) {
        List<String> fragments = new ArrayList<>();

        if (random.nextInt(4) == 0) {
            if (random.nextInt(2) == 0) {
                fragments.add("<!-- licence -->");
            }
            fragments.add("<!DOCTYPE html>");
            // R20: a second declaration is ignored with a warning rather than refused, so the
            // fuzzer can explore the layout-plus-fragment shape that produces one.
            if (random.nextInt(4) == 0) {
                fragments.add("<!DOCTYPE html>");
            }
        }

        int leading = random.nextInt(4);
        for (int i = 0; i < leading; i++) {
            fragments.add(NOISE[random.nextInt(NOISE.length)]);
        }

        String[] host = HOSTS[random.nextInt(HOSTS.length)];
        fragments.add(host[0]);
        fragments.add(REFERENCE);
        fragments.add(host[1]);

        int trailing = random.nextInt(3);
        for (int i = 0; i < trailing; i++) {
            fragments.add(NOISE[random.nextInt(NOISE.length)]);
        }

        return fragments;
    }

    /**
     * Fragments that surround the host without containing the reference.
     *
     * <p>{@code <input placeholder="x">} and its eleven-character sibling are here on purpose:
     * under F5 the <em>preceding</em> attribute name decided whether a {@code javascript:} prefix in
     * the host was detected, so a generator with no preceding elements could never reach half of the
     * prefix-detection behaviour. R3 closed that, and the fragments stay — a fuzzer whose corpus
     * shrinks every time a finding is fixed stops being able to see the finding come back.
     */
    private static final String[] NOISE = {
            "<p>text</p>",
            "<div class=\"c\">t</div>",
            "<!-- a comment -->",
            "<img src=\"/a.png\" alt=\"a\">",
            // R20 made the no-space self-closing form legal; before that it was a rejection the
            // generator had to avoid, so no fuzz run had ever put one in front of a reference.
            "<br/>",
            "<img src=\"/a.png\" alt=\"a\"/>",
            "<a href=\"/x\">y</a>",
            "<input placeholder=\"x\">",
            "<input aria-describedby=\"x\">",
            "<span title=\"t\">s</span>",
            "<table><tr><td>cell</td></tr></table>",
            "<ul><li>item</li></ul>",
            "<svg><circle r=\"1\"></circle></svg>",
            "<script>var q = 1;</script>",
            "<style>p{color:red}</style>",
            "<form action=\"/post\"><button type=\"submit\">go</button></form>",
            "\n  ",
            "plain text ",
    };

    /**
     * The positions a reference can occupy, as an opening and a closing fragment.
     *
     * <p>Every insertion context from Appendix A.1 that a generator can reach, plus the four
     * attribute-value shapes and the prefixed forms F4, F5 and F17 turn on. The last three are
     * deliberately unterminated, so that rejection and partial output are exercised rather than
     * assumed.
     */
    private static final String[][] HOSTS = {
            {"<p>", "</p>"},
            {"<div>", "</div>"},
            {"<div><span>", "</span></div>"},
            {"<table><tr><td>", "</td></tr></table>"},
            {"<textarea>", "</textarea>"},
            {"<title>", "</title>"},
            {"<noscript>", "</noscript>"},
            {"<a href=\"", "\">x</a>"},
            {"<a href='", "'>x</a>"},
            {"<a href=\"/path/", "\">x</a>"},
            {"<a href=\"/path?q=", "\">x</a>"},
            {"<a href=\"#", "\">x</a>"},
            {"<img src=", " alt=\"a\">"},
            {"<img src=\"", "\" alt=\"a\">"},
            {"<div background=\"", "\">x</div>"},
            {"<a href=\"javascript:f('", "')\">x</a>"},
            {"<a href=\"asfunction:f('", "')\">x</a>"},
            {"<a href=\"mocha:f('", "')\">x</a>"},
            {"<div style=\"background:", "\">x</div>"},
            {"<div style=\"content:'", "'\">x</div>"},
            {"<div style=\"background:url(", ")\">x</div>"},
            {"<div onclick=\"f('", "')\">x</div>"},
            {"<div onmouseenter=\"f('", "')\">x</div>"},
            {"<div onbeforeinput=\"f('", "')\">x</div>"},
            {"<div onsubmit=\"f('", "')\">x</div>"},
            {"<div data=\"", "\">x</div>"},
            {"<iframe srcdoc=\"", "\"></iframe>"},
            {"<iframe sandbox=\"", "\"></iframe>"},
            {"<meta http-equiv=\"refresh\" content=\"", "\">"},
            {"<link rel=\"", "\" href=\"/a.css\">"},
            {"<base href=\"", "\">"},
            {"<script>var a = '", "';</script>"},
            {"<style>a{color:", "}</style>"},
            {"<!-- ", " -->"},
            {"<p id=\"", "\">x</p>"},
            {"<p title=\"", "\" class=\"c\">x</p>"},
            {"<x-custom data-v=\"", "\">t</x-custom>"},
            {"<svg><a xlink:href=\"", "\">t</a></svg>"},
            {"<p>a</p><p>", "</p><p>b</p>"},
            {"<a href=\"", ""},
            {"<div title=\"", "\">"},
            {"<p>", ""},
    };

    private static String join(List<String> fragments) {
        return String.join("", fragments);
    }

    // ------------------------------------------------------------------
    // Minimisation
    // ------------------------------------------------------------------

    /**
     * Delta debugging over the fragment list: drop one fragment at a time, keep the drop whenever
     * the violation survives, and repeat until a full pass changes nothing.
     *
     * <p>The reference fragment is never dropped, because a template with no reference cannot
     * violate anything and the minimiser would happily reduce every counterexample to the empty
     * string.
     */
    private static List<String> minimise(List<String> fragments, String value) {
        List<String> current = new ArrayList<>(fragments);
        boolean changed = true;

        while (changed) {
            changed = false;
            for (int i = 0; i < current.size(); i++) {
                if (REFERENCE.equals(current.get(i))) {
                    continue;
                }
                List<String> candidate = new ArrayList<>(current);
                candidate.remove(i);
                if (check(join(candidate), value) != null) {
                    current = candidate;
                    changed = true;
                    break;
                }
            }
        }

        return current;
    }

    // ------------------------------------------------------------------
    // The oracle must be able to fail
    // ------------------------------------------------------------------

    /**
     * &sect;2.4, applied to a generated corpus: a fuzzer whose oracle cannot fail is a very
     * expensive way of doing nothing.
     *
     * <p>Each row replaces the generated {@code $data} with {@code $_x.asis($data)}, which is the
     * supported way to put unencoded bytes into the output, and requires the oracle to notice. The
     * rows are chosen one per property, so a single broken property cannot hide behind the other
     * three.
     */
    @Test
    public void theOracleCatchesAnUnencodedRenderInEveryPositionItGenerates() {
        assertViolation("<p>$_x.asis($data)</p>", Payloads.TAG_IMG_ONERROR.value(),
                "a new element in body text");
        assertViolation("<a title=\"$_x.asis($data)\">x</a>",
                Payloads.ATTR_DOUBLE_QUOTE_BREAKOUT.value(),
                "a new attribute, by terminating the value early");
        assertViolation("<a href=\"$_x.asis($data)\">x</a>", Payloads.QUOTE_DOUBLE_BREAKOUT.value(),
                "a quote loose in a URL attribute");
        assertViolation("<p>$_x.asis($data)</p>", Payloads.TAG_SCRIPT.value(),
                "a script element, which the parser hoists into <head> and which also moves the"
                        + " context sequence for anything after it");

        // The unquoted host, which R19 made live and which property 3 now exempts when the value is
        // suppressed. The exemption must be exactly that narrow: an unencoded value in the same
        // position still builds an attribute and must still be caught, or the exemption has blinded
        // the oracle to the one shape it was written for.
        assertViolation("<img src=$_x.asis($data) alt=\"a\">",
                Payloads.ATTR_UNQUOTED_BREAKOUT.value(),
                "a new attribute out of an unquoted value, which is the position the suppression"
                        + " exemption in check() covers");

        // ...and the same shape again with a payload that carries no markup delimiter at all, which
        // is what makes it evidence about property 3 rather than about property 2. ATTR_UNQUOTED_
        // BREAKOUT above contains two apostrophes, so property 2 reports it and returns before the
        // skeleton is ever compared - the row is a true statement about the oracle as a whole and no
        // statement at all about the exemption. This one changes no delimiter count (the quotes in
        // the output are the template's own alt="a"), so property 2 is silent, the value is not
        // suppressed so the exemption does not apply, and the violation has to be the skeleton one.
        // Asserted by name: if the exemption ever widens to cover this, the assertion says which
        // property went quiet instead of merely that something still fires.
        assertViolationIs("the document skeleton diverged",
                "<img src=$_x.asis($data) alt=\"a\">",
                "x onmouseover=" + Payloads.SENTINEL_FUNCTION + "(1)",
                "a new attribute out of an unquoted value, built from characters property 2 does"
                        + " not count");
    }

    /**
     * ...and the same payloads through an ordinary reference must <em>not</em> violate anything.
     *
     * <p>Without this half, the test above is consistent with an oracle that reports a violation
     * whenever the value changes at all, which is a different oracle and a useless one. It is the
     * same pairing {@code DomEquivalenceTest} and {@code ParserSteeringTest} carry.
     */
    @Test
    public void theSamePayloadsThroughAnEncodedReferenceViolateNothing() {
        for (String template : List.of("<p>$data</p>", "<a title=\"$data\">x</a>",
                "<a href=\"$data\">x</a>")) {
            for (Payload payload : List.of(Payloads.TAG_IMG_ONERROR, Payloads.TAG_SCRIPT,
                    Payloads.ATTR_DOUBLE_QUOTE_BREAKOUT, Payloads.QUOTE_DOUBLE_BREAKOUT)) {
                assertEquals(null, check(template, payload.value()),
                        () -> "the encoder held for " + payload.id() + " in " + template
                                + ", so the oracle must be silent");
            }
        }
    }

    private static void assertViolation(String template, String value, String what) {
        assertTrue(check(template, value) != null,
                () -> "the oracle failed to notice " + what + " in " + template);
    }

    /**
     * As {@link #assertViolation}, but pins <em>which</em> property fired. Needed wherever the point
     * of a row is that one named property still works: {@link #check} returns on the first violation
     * it finds, so a row whose payload trips an earlier property says nothing about a later one.
     */
    private static void assertViolationIs(String expected, String template, String value,
                                          String what) {
        String violation = check(template, value);
        assertTrue(violation != null && violation.startsWith(expected),
                () -> "the oracle was expected to report \"" + expected + "\" for " + what + " in "
                        + template + ", and reported: " + violation);
    }

    /**
     * The minimiser reduces a counterexample rather than merely returning it, and it never drops the
     * reference.
     *
     * <p>Driven with a deliberately unencoded template, since there is no real counterexample to
     * minimise. Without this the minimiser would only ever run on the day it is needed, which is the
     * worst day to discover it is broken.
     */
    @Test
    public void theMinimiserShrinksACounterexampleAndKeepsTheReference() {
        List<String> fragments = new ArrayList<>(List.of(
                "<!DOCTYPE html>", "<p>text</p>", "<div class=\"c\">t</div>",
                "<p>", "$_x.asis($data)", "</p>", "<ul><li>item</li></ul>"));

        List<String> minimal = minimise(fragments, Payloads.TAG_IMG_ONERROR.value());

        assertTrue(minimal.size() < fragments.size(),
                () -> "the minimiser returned " + minimal + " unchanged");
        assertTrue(minimal.contains("$_x.asis($data)"),
                () -> "the minimiser dropped the reference, so " + minimal + " cannot violate"
                        + " anything");
        assertTrue(check(join(minimal), Payloads.TAG_IMG_ONERROR.value()) != null,
                () -> "the minimised template " + join(minimal) + " no longer reproduces");
    }

    /**
     * The generator produces what it claims to: exactly one reference, and every declared host shape
     * reached within the pinned run.
     *
     * <p>A generator that silently stopped emitting half its shapes would leave the run green and
     * the coverage claim false, which is the failure mode &sect;8 names. Asserted over the same
     * seed the run uses, so the two cannot disagree.
     */
    @Test
    public void theGeneratorReachesEveryDeclaredShape() {
        Random random = new Random(SEED);
        Set<String> openersSeen = new LinkedHashSet<>();

        for (int i = 0; i < ITERATIONS; i++) {
            List<String> fragments = generate(random);
            assertEquals(1, fragments.stream().filter(REFERENCE::equals).count(),
                    () -> "exactly one reference per template, got " + fragments);

            int index = fragments.indexOf(REFERENCE);
            openersSeen.add(fragments.get(index - 1));
        }

        List<String> unreached = new ArrayList<>();
        for (String[] host : HOSTS) {
            if (!openersSeen.contains(host[0])) {
                unreached.add(host[0]);
            }
        }
        assertTrue(unreached.isEmpty(),
                () -> "seed " + SEED + " with " + ITERATIONS + " iterations never generated: "
                        + unreached + ". Either raise canoe.fuzz.iterations or change the seed;"
                        + " a shape that is declared and never generated is a coverage claim that"
                        + " is not true.");
    }

    /** The generated templates are pure ASCII, which is the suite's source rule applied to its data. */
    @Test
    public void everyFragmentIsPureAscii() {
        for (String[] host : HOSTS) {
            assertAscii(host[0]);
            assertAscii(host[1]);
        }
        for (String noise : NOISE) {
            assertAscii(noise);
        }
    }

    private static void assertAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            assertFalse(s.charAt(i) > 0x7f,
                    () -> "non-ASCII in a generator fragment: " + CanoeTestSupport.quote(s));
        }
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private static long count(String haystack, char needle) {
        return haystack.chars().filter(c -> c == needle).count();
    }

    /** Canoe's messages end in {@code " (line: N, pos: M)"}, which moves with the payload length. */
    private static String withoutPosition(String message) {
        if (message == null) {
            return null;
        }
        int at = message.lastIndexOf(" (line: ");
        return at < 0 ? message : message.substring(0, at);
    }
}
