package com.webkreator.qlue.view.canoe.corpus;

import com.webkreator.qlue.util.HtmlEncoder;
import com.webkreator.qlue.view.canoe.CanoeTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ledger, plus the integrity of the corpus that holds it.
 *
 * <p>{@link #ledgerMatchesObservedBehaviour} is the important test in this file and arguably in the
 * suite: it is what turns the recorded verdicts from opinions into assertions. A {@link
 * Verdict#KNOWN_VULNERABLE} entry fails here when the vulnerability disappears, which is the signal
 * to update the ledger rather than a bug.
 */
public class CanoeCorpusTest {

    static List<XssCase.Invocation> invocations() {
        return CanoeCorpus.allInvocations();
    }

    // ------------------------------------------------------------------
    // The ledger
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("invocations")
    public void ledgerMatchesObservedBehaviour(XssCase.Invocation invocation) {
        VerdictEvaluator.Observation observed =
                VerdictEvaluator.observe(invocation.testCase(), invocation.payload());

        assertTrue(observed.matches(invocation.verdict()),
                () -> "Ledger says " + invocation.verdict() + " but observed " + observed.verdict()
                        + " for " + invocation
                        + "\n  template : " + invocation.testCase().template()
                        + "\n  payload  : " + CanoeTestSupport.quote(invocation.payload().value())
                        + "\n  rendered : " + CanoeTestSupport.quote(observed.result().output())
                        + "\n  at sink  : " + CanoeTestSupport.quote(
                                String.valueOf(observed.sinkValue()))
                        + "\n  because  : " + observed.explanation()
                        + "\n\nIf the vulnerability was just fixed, update the ledger entry in"
                        + " CanoeCorpus and say so in the commit message. A KNOWN_VULNERABLE row"
                        + " failing is good news and is what this suite exists for; see"
                        + " src/test/java/com/webkreator/qlue/view/canoe/README.md for what to do"
                        + " next, and build/reports/canoe/matrix.md for the other rows on the same"
                        + " finding.");
    }

    // ------------------------------------------------------------------
    // R26's guard: the count is zero, and the residue cannot grow
    // ------------------------------------------------------------------

    /**
     * <strong>The number this suite was built to move.</strong>
     *
     * <p>It opened at 281 invocations across the corpus and it is zero. Everything that was
     * exploitable — a value arriving live in a JavaScript, CSS, markup or resource-loading sink — is
     * closed at the component, by R2 through R12; what could not be closed is 68 invocations of F6
     * on sinks that are not code execution, and those carry {@link Verdict#ACCEPTED_RESIDUAL} with a
     * declared {@link ResidualSink} rather than sitting on a list nobody can empty.
     *
     * <p>This assertion is the one that has to hold from here on. A new {@code KNOWN_VULNERABLE}
     * row is a regression <em>or</em> a newly discovered vulnerability, and either way it is
     * something a person has to decide about rather than something that lands in a generated table.
     * It is deliberately not "the count did not go up": the count is zero, and the failure message
     * says what the two honest ways out are.
     */
    @Test
    public void noInvocationIsKnownVulnerable() {
        List<XssCase.Invocation> vulnerable = CanoeCorpus.allInvocations().stream()
                .filter(i -> i.verdict() == Verdict.KNOWN_VULNERABLE)
                .collect(java.util.stream.Collectors.toList());

        assertTrue(vulnerable.isEmpty(),
                () -> "The KNOWN_VULNERABLE count is " + vulnerable.size() + " and it must be zero."
                        + " These rows claim attacker data reaches a sink live at a sink that IS"
                        + " code execution:\n  "
                        + vulnerable.stream()
                                .map(i -> i + "  (" + i.testCase().finding() + ")")
                                .collect(java.util.stream.Collectors.joining("\n  "))
                        + "\n\nThere are exactly two honest ways out of this failure, and neither is"
                        + " editing this test.\n"
                        + "  1. Fix Canoe, and re-verdict the row to whatever the fixed component"
                        + " does.\n"
                        + "  2. If the row is a residual - the data reaches the sink and the sink is"
                        + " not code execution - give it Verdict.ACCEPTED_RESIDUAL, a ResidualSink"
                        + " naming what the browser does with the value instead, a finding citation,"
                        + " and an entry in this file's PINNED_RESIDUALS. That is a decision to"
                        + " record in review, not a relabelling: read"
                        + " Verdict.ACCEPTED_RESIDUAL's javadoc first.\n"
                        + "A row that is genuinely exploitable and genuinely unfixable today is a"
                        + " reason to stop and talk to somebody, not a reason to widen a table.");
    }

    /**
     * The residue is pinned: exactly these cases, with exactly these sinks, and the list may only
     * shrink.
     *
     * <p>{@link Verdict#ACCEPTED_RESIDUAL} is the one verdict in the ledger that records a live data
     * flow somebody decided to live with. The failure mode it invites is obvious and is the reason
     * for this list: a row that becomes inconvenient gets quietly promoted into the accepted set,
     * and because the set is described rather than enumerated, nothing notices. So it is
     * enumerated. A case that starts carrying the verdict fails here until somebody adds it
     * deliberately; a case that stops carrying it fails here too, because the list is meant to
     * shrink and a stale entry is how a closed residual keeps a reputation for being open.
     *
     * <p>The invocation count is pinned per case as well as the sink class, which is what stops a
     * <em>payload</em> from joining the residue silently: adding a fourth off-origin family to
     * {@code url.href-full} is exactly as much a decision as adding a twenty-seventh case.
     */
    private static final Map<String, Residual> PINNED_RESIDUALS = pinnedResiduals();

    private static Map<String, Residual> pinnedResiduals() {
        Map<String, Residual> pinned = new java.util.LinkedHashMap<>();

        // <a href> and its SVG twin: a click leaves the origin. Twelve cases, and nine of them are
        // the same sink written a different way - the case, separator and quoting permutations that
        // pin "the spelling does not change the classification".
        pinned.put("url.href-full", new Residual(ResidualSink.OPEN_REDIRECT, 3));
        pinned.put("transition.attribute-then-text", new Residual(ResidualSink.OPEN_REDIRECT, 3));
        pinned.put("name.href-uppercase", new Residual(ResidualSink.OPEN_REDIRECT, 3));
        pinned.put("name.href-mixed-case", new Residual(ResidualSink.OPEN_REDIRECT, 3));
        pinned.put("separator.space-before-equals", new Residual(ResidualSink.OPEN_REDIRECT, 3));
        pinned.put("separator.tab-before-equals", new Residual(ResidualSink.OPEN_REDIRECT, 3));
        pinned.put("separator.newline-before-equals", new Residual(ResidualSink.OPEN_REDIRECT, 3));
        pinned.put("separator.crlf-before-equals", new Residual(ResidualSink.OPEN_REDIRECT, 3));
        pinned.put("separator.duplicate-attribute-reversed",
                new Residual(ResidualSink.OPEN_REDIRECT, 3));
        pinned.put("url.xlink-href", new Residual(ResidualSink.OPEN_REDIRECT, 3));
        pinned.put("unquoted.immediately-after-equals", new Residual(ResidualSink.OPEN_REDIRECT, 1));
        pinned.put("unquoted.whitespace-then-reference", new Residual(ResidualSink.OPEN_REDIRECT, 1));

        // Not an <a href>, and here because review moved it off INERT_SINK: longdesc is never
        // fetched, but Gecko exposes a 'showlongdesc' accessibility action that opens it, so a user
        // acting on the element still leaves the origin.
        pinned.put("url.longdesc", new Residual(ResidualSink.OPEN_REDIRECT, 2));

        // A form's submission target: the navigation plus everything the user typed.
        pinned.put("url.action", new Residual(ResidualSink.FORM_RETARGET, 3));
        pinned.put("url.formaction", new Residual(ResidualSink.FORM_RETARGET, 3));

        // Fetched as a subresource; the response gets no authority in the document.
        pinned.put("url.img-src", new Residual(ResidualSink.REFERRER_LEAK, 3));
        pinned.put("url.background", new Residual(ResidualSink.REFERRER_LEAK, 3));
        pinned.put("url.srcset", new Residual(ResidualSink.REFERRER_LEAK, 3));
        pinned.put("url.poster", new Residual(ResidualSink.REFERRER_LEAK, 3));
        pinned.put("url.ping", new Residual(ResidualSink.REFERRER_LEAK, 2));

        // Reaches the attribute; no shipping engine dereferences it.
        pinned.put("url.dynsrc", new Residual(ResidualSink.INERT_SINK, 3));
        pinned.put("url.lowsrc", new Residual(ResidualSink.INERT_SINK, 3));
        pinned.put("url.cite", new Residual(ResidualSink.INERT_SINK, 2));
        pinned.put("url.usemap", new Residual(ResidualSink.INERT_SINK, 2));
        pinned.put("url.codebase", new Residual(ResidualSink.INERT_SINK, 2));
        pinned.put("url.manifest", new Residual(ResidualSink.INERT_SINK, 2));

        return java.util.Collections.unmodifiableMap(pinned);
    }

    private static final class Residual {

        final ResidualSink sink;
        final int invocations;

        Residual(ResidualSink sink, int invocations) {
            this.sink = sink;
            this.invocations = invocations;
        }

        @Override
        public String toString() {
            return sink + " x" + invocations;
        }
    }

    /** See {@link #PINNED_RESIDUALS}. */
    @Test
    public void theAcceptedResidueIsExactlyTheListItWasPinnedTo() {
        Map<String, Residual> observed = new java.util.LinkedHashMap<>();
        for (XssCase.Invocation invocation : CanoeCorpus.allInvocations()) {
            if (invocation.verdict() != Verdict.ACCEPTED_RESIDUAL) {
                continue;
            }
            Residual seen = observed.get(invocation.testCase().id());
            observed.put(invocation.testCase().id(), new Residual(invocation.residualSink(),
                    seen == null ? 1 : seen.invocations + 1));
        }

        String help = "\n\nWhat this list is. Every entry is a (case, payload) pairing where"
                + " attacker data reaches the sink and somebody decided the sink is not code"
                + " execution - an off-origin link, an off-origin image, an off-origin form"
                + " action. R9 drew that line and R26 wrote it down. The list is pinned so that"
                + " the set can be READ rather than described, because a set described as 'the"
                + " ones we accepted' is a set that grows.\n"
                + "It is allowed to shrink and not to grow. If you are here because you closed a"
                + " residual - an origin filter on <form action>, say - delete its line and say so"
                + " in the commit; that is the direction this list is for.\n"
                + "If you are here because a new row wants in, it needs the whole argument, not an"
                + " entry: which sink, why the sink does not execute what it fetches, and why the"
                + " availability cost of closing it is not worth paying. Verdict.ACCEPTED_RESIDUAL"
                + " and ResidualSink have the reasoning for the twenty-six that are already here.";

        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Residual> entry : observed.entrySet()) {
            Residual pin = PINNED_RESIDUALS.get(entry.getKey());
            if (pin == null) {
                problems.add("NEW residual, not on the pinned list: " + entry.getKey() + " ("
                        + entry.getValue() + ")");
            } else if (pin.sink != entry.getValue().sink) {
                problems.add(entry.getKey() + " is pinned as " + pin.sink + " and now declares "
                        + entry.getValue().sink);
            } else if (pin.invocations != entry.getValue().invocations) {
                problems.add(entry.getKey() + " is pinned at " + pin.invocations
                        + " residual invocations and now has " + entry.getValue().invocations);
            }
        }
        for (String id : PINNED_RESIDUALS.keySet()) {
            if (!observed.containsKey(id)) {
                problems.add("GONE from the residue, and still pinned: " + id + ". If the residual"
                        + " was closed, that is good news - delete the line.");
            }
        }

        assertTrue(problems.isEmpty(),
                () -> "The accepted residue does not match its pinned list:\n  "
                        + String.join("\n  ", problems) + help);
    }

    /**
     * The invariants every residual row carries, checked over the ledger rather than trusted to the
     * builder that produced them: a finding citation, a declared sink class, and no sink class
     * anywhere else.
     */
    @Test
    public void everyResidualCitesAFindingAndNamesItsSink() {
        for (XssCase testCase : CanoeCorpus.all()) {
            boolean residual = testCase.payloads().stream()
                    .anyMatch(p -> testCase.verdictFor(p) == Verdict.ACCEPTED_RESIDUAL);
            if (residual) {
                assertNotNull(testCase.finding(),
                        testCase.id() + " accepts a residual and cites no finding. The data still"
                                + " reaches the sink, so the row owes the same citation the"
                                + " KNOWN_VULNERABLE row it came from owed.");
                assertNotNull(testCase.residualSink(),
                        testCase.id() + " accepts a residual and does not say which sink");
            } else {
                assertNull(testCase.residualSink(),
                        testCase.id() + " declares the residual sink " + testCase.residualSink()
                                + " but has no ACCEPTED_RESIDUAL row, so the sink class describes a"
                                + " residue that is not there");
            }

            // INERT_SINK and browser relevance cannot both be true. The browser tier expects a
            // detector to fire for every live row it loads, and INERT_SINK is precisely the claim
            // that no engine dereferences the value - so a case asserting both would be a
            // guaranteed browser-tier failure written into the corpus. It is the same conflict the
            // notBrowserObservable flag was invented for, caught at its source instead.
            if (testCase.residualSink() == ResidualSink.INERT_SINK) {
                assertFalse(testCase.isBrowserRelevant(),
                        testCase.id() + " declares INERT_SINK and is browser-relevant. Those"
                                + " contradict: the tier expects a detector to fire for a live row,"
                                + " and INERT_SINK says no engine touches the value. Either the sink"
                                + " class is wrong - if a browser really does fetch or navigate to"
                                + " it, this is REFERRER_LEAK or OPEN_REDIRECT - or the case should"
                                + " not be loaded in a browser.");
            }
        }
    }

    /** The two halves of that constraint, exercised on the builder so they cannot be loosened. */
    @Test
    public void aResidualNeedsASinkClassAndNothingElseMayCarryOne() {
        assertThrows(IllegalArgumentException.class, () -> XssCase.id("residual-without-a-sink")
                .template("<a href=\"$data\">x</a>")
                .sink(SinkKind.URL, "a", "href")
                .payloads(Payloads.PROTOCOL_RELATIVE)
                .verdict(Verdict.ACCEPTED_RESIDUAL)
                .finding("none - self test")
                .build());

        assertThrows(IllegalArgumentException.class, () -> XssCase.id("residual-without-a-finding")
                .template("<a href=\"$data\">x</a>")
                .sink(SinkKind.URL, "a", "href")
                .payloads(Payloads.PROTOCOL_RELATIVE)
                .verdict(Verdict.ACCEPTED_RESIDUAL)
                .residualSink(ResidualSink.OPEN_REDIRECT)
                .build());

        assertThrows(IllegalArgumentException.class, () -> XssCase.id("sink-class-on-a-safe-row")
                .template("<a href=\"$data\">x</a>")
                .sink(SinkKind.URL, "a", "href")
                .payloads(Payloads.PROTOCOL_RELATIVE)
                .verdict(Verdict.SAFE)
                .residualSink(ResidualSink.OPEN_REDIRECT)
                .build());
    }

    /**
     * A residual row is still asserted, and this is the assertion. The oracle observes {@link
     * Verdict#KNOWN_VULNERABLE} for it — it reads output and cannot tell a redirect from an
     * execution — and nothing else is accepted, so the day the value stops reaching the sink the row
     * fails and has to be re-verdicted by hand.
     *
     * <p>Without this, {@code ACCEPTED_RESIDUAL} would be a verdict that matches whatever happens,
     * which is precisely the rubber stamp the corpus's design note is about. It is checked on a
     * synthetic case rather than on a corpus row, so that it keeps meaning something after the last
     * residual is closed.
     */
    @Test
    public void aResidualStopsMatchingWhenTheDataStopsReachingTheSink() {
        XssCase live = XssCase.id("residual-selftest-live")
                .section("self-test")
                .template("<a href=\"$data\">go</a>")
                .sink(SinkKind.URL, "a", "href")
                .payloads(Payloads.PROTOCOL_RELATIVE)
                .verdict(Verdict.ACCEPTED_RESIDUAL)
                .residualSink(ResidualSink.OPEN_REDIRECT)
                .finding("none - self test")
                .build();
        VerdictEvaluator.Observation reaching =
                VerdictEvaluator.observe(live, Payloads.PROTOCOL_RELATIVE);
        assertEquals(Verdict.KNOWN_VULNERABLE, reaching.verdict(),
                "the oracle judges reach, not consequence, so it still says KNOWN_VULNERABLE");
        assertTrue(reaching.matches(Verdict.ACCEPTED_RESIDUAL));

        // ...and the same template on a payload url() rejects. The data no longer reaches the sink,
        // so the residual claim is false and must not match.
        VerdictEvaluator.Observation suppressed = VerdictEvaluator.observe(
                XssCase.id("residual-selftest-suppressed")
                        .section("self-test")
                        .template("<a href=\"$data\">go</a>")
                        .sink(SinkKind.URL, "a", "href")
                        .payloads(Payloads.JS_URL)
                        .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                        .build(),
                Payloads.JS_URL);
        assertTrue(suppressed.verdict().isSuppression());
        assertFalse(suppressed.matches(Verdict.ACCEPTED_RESIDUAL),
                "a residual row whose value stopped reaching the sink must fail, or the verdict is"
                        + " a label rather than an assertion");

        // A safe arrival must not match either: the residue is a claim that the data got there.
        VerdictEvaluator.Observation safe = VerdictEvaluator.observe(
                XssCase.id("residual-selftest-safe")
                        .section("self-test")
                        .template("<a href=\"/p/$data\">go</a>")
                        .sink(SinkKind.URL, "a", "href")
                        .payloads(Payloads.PROTOCOL_RELATIVE)
                        .verdict(Verdict.SAFE)
                        .build(),
                Payloads.PROTOCOL_RELATIVE);
        assertEquals(Verdict.SAFE, safe.verdict());
        assertFalse(safe.matches(Verdict.ACCEPTED_RESIDUAL));

        // And the asymmetry is one-way: a KNOWN_VULNERABLE ledger entry is never satisfied by
        // anything but an observed KNOWN_VULNERABLE, so ACCEPTED_RESIDUAL cannot be used to hold a
        // row whose verdict somebody meant to lower.
        assertFalse(safe.matches(Verdict.KNOWN_VULNERABLE));
    }

    /**
     * Proves the ledger check above can actually fail. A verdict oracle that never disagrees is
     * indistinguishable from one that is broken, and the first review of this corpus found three
     * wrong verdicts that no test caught — so the oracle's ability to catch them is itself asserted.
     */
    @Test
    public void theLedgerOracleDetectsAWrongVerdict() {
        // Body text is safe; claiming otherwise must be caught.
        XssCase wronglyVulnerable = XssCase.id("oracle-selftest-vulnerable")
                .section("self-test")
                .template("<p>$data</p>")
                .textSink("p")
                .payloads(Payloads.TAG_IMG_ONERROR)
                .verdict(Verdict.KNOWN_VULNERABLE)
                .finding("none - self test")
                .build();
        VerdictEvaluator.Observation safeReality =
                VerdictEvaluator.observe(wronglyVulnerable, Payloads.TAG_IMG_ONERROR);
        assertEquals(Verdict.SAFE, safeReality.verdict());
        assertFalse(safeReality.matches(Verdict.KNOWN_VULNERABLE));

        // An off-origin href is vulnerable; claiming it is safe must also be caught. This half has
        // now been rewritten twice for the same reason: it used <form onsubmit="v('$data')"> and F1
        // until R4 closed it, then xlink:href and F3 until R6 routed that name to url(). Each time,
        // the template stopped emitting anything the oracle would call live, so the oracle would
        // have agreed with the wrong verdict and the self-test would have passed vacuously. F6 is
        // the replacement and is the last one available: url() is a scheme filter and not an origin
        // filter, so a protocol-relative URL reaches the sink byte for byte on the best-protected
        // attribute in the component. If R9 or R12 ever makes this half pass vacuously too, there is
        // nothing left in the corpus for a self-test to use - which will be the right problem to
        // have, and the answer will be a synthetic sink rather than a real one.
        XssCase wronglySafe = XssCase.id("oracle-selftest-safe")
                .section("self-test")
                .template("<a href=\"$data\">go</a>")
                .sink(SinkKind.URL, "a", "href")
                .payloads(Payloads.PROTOCOL_RELATIVE)
                .verdict(Verdict.SAFE)
                .build();
        VerdictEvaluator.Observation vulnerableReality =
                VerdictEvaluator.observe(wronglySafe, Payloads.PROTOCOL_RELATIVE);
        assertEquals(Verdict.KNOWN_VULNERABLE, vulnerableReality.verdict());
        assertFalse(vulnerableReality.matches(Verdict.SAFE));

        // ...and the same again for an element the HTML parser hoists into <head>, because the two
        // rows above only prove the oracle works in body context. The structural oracle used to
        // select over document.body() alone, so for a template like this one both the benign and the
        // attacked render reduced to the empty skeleton "body[]" and compared equal no matter what
        // the payload did. Fifteen invocations were in that state - policy.nonce among them, where a
        // breakout would have been exactly the thing nobody saw. A row that cannot fail is worse
        // than no row, so the oracle's ability to fail in head context is asserted too.
        XssCase hoistedIntoHead = XssCase.id("oracle-selftest-head")
                .section("self-test")
                .template("<title>$data</title>")
                .textSink("title")
                .payloads(Payloads.TAG_IMG_ONERROR)
                .verdict(Verdict.KNOWN_VULNERABLE)
                .finding("none - self test")
                .build();
        VerdictEvaluator.Observation headReality =
                VerdictEvaluator.observe(hoistedIntoHead, Payloads.TAG_IMG_ONERROR);
        assertEquals(Verdict.SAFE, headReality.verdict());
        assertFalse(headReality.matches(Verdict.KNOWN_VULNERABLE));
        assertFalse(headReality.sinkValue().isEmpty(),
                "the skeleton of a <head>-hoisted document must not be empty, or this row proves"
                        + " nothing: that is precisely the state document.body().select(\"*\") left"
                        + " it in. Observed skeleton: " + headReality.sinkValue());
        assertTrue(headReality.sinkValue().contains("title"),
                "the hoisted element itself has to appear in the skeleton. Observed: "
                        + headReality.sinkValue());
    }

    /**
     * The off-site vectors {@code url()} neutralises — <strong>by design since R12</strong>, where
     * they used to be safe by accident of the old allowlist. Each is pinned by rendering it through
     * {@code url()} and confirming the result with the URL oracle, so the neutralisation is asserted
     * as a property of the encoder rather than of a string somebody wrote down.
     *
     * <p>Before R12 the plan flagged these as "safe by luck": {@code url()} happened to escape the
     * one character that mattered. R12 makes the escaping deliberate — the authority safe set excludes
     * {@code @}, the path safe sets exclude {@code \}, and a scheme off the {http, https, mailto}
     * allowlist is rejected outright — so the reason is now the design and not the accident. The one
     * accident R12 did <em>not</em> preserve is the case-sensitive scheme regex: an uppercase scheme
     * is normalised now, so it is a real off-origin URL, and this test records that flip rather than
     * pretending the neutralisation survived.
     */
    @Test
    public void urlNeutralisesOffsiteVectorsByDesign() {
        // A rejected scheme is suppressed to the empty string - the allowlist, by design.
        assertEquals("", HtmlEncoder.url("javascript:alert(1)"));
        assertFalse(VerdictEvaluator.analyseUrl(HtmlEncoder.url("javascript:alert(1)")).isDangerous());
        assertTrue(VerdictEvaluator.analyseUrl("javascript:alert(1)").isDangerous());

        // Userinfo '@' is escaped in the authority, so a trusted-looking prefix cannot smuggle the
        // authority off-origin: the '%40' is a forbidden host code point and the URL fails to parse.
        assertEquals("https://trusted.example%40attacker.invalid/x.js",
                HtmlEncoder.url("https://trusted.example@attacker.invalid/x.js"));
        assertFalse(VerdictEvaluator.analyseUrl(
                HtmlEncoder.url("https://trusted.example@attacker.invalid/x.js")).isDangerous());
        assertTrue(VerdictEvaluator.analyseUrl(
                "https://trusted.example@attacker.invalid/x.js").isDangerous());

        // A backslash is percent-encoded, so the Windows-style protocol-relative form stays a
        // same-origin path; no browser un-escapes '%5C' back into a separator.
        assertEquals("/%5Cattacker.invalid/x.js", HtmlEncoder.url("/\\attacker.invalid/x.js"));
        assertFalse(VerdictEvaluator.analyseUrl(
                HtmlEncoder.url("/\\attacker.invalid/x.js")).isDangerous());
        assertTrue(VerdictEvaluator.analyseUrl("//attacker.invalid/x.js").isDangerous());

        // The accident R12 removed: an uppercase scheme is normalised and passes through now, so it
        // is a genuine off-origin URL. url() does not neutralise it - R9's origin filter will.
        assertEquals("https://attacker.invalid/x.js", HtmlEncoder.url("HTTPS://attacker.invalid/x.js"));
        assertTrue(VerdictEvaluator.analyseUrl(
                HtmlEncoder.url("HTTPS://attacker.invalid/x.js")).isDangerous());
    }

    /**
     * The URL oracle itself, checked against the WHATWG URL Standard rather than against intuition.
     *
     * <p>{@code analyseUrl} decides every {@code SinkKind.URL} verdict in the corpus, so an error in
     * it is not a test bug — it is a vulnerability recorded as {@link Verdict#SAFE}. Its errors are
     * also not symmetric: a false "dangerous" gets investigated, a false "safe" is invisible. The
     * first review of the oracle found nine divergences and <em>every one of them</em> pointed the
     * same way, which is exactly the shape a blind oracle has.
     *
     * <p>Each row below was resolved against base {@code https://app.example/dir/page} using Node's
     * WHATWG URL parser, and each one used to be reported safe.
     */
    @ParameterizedTest(name = "{0} reaches {1}")
    @CsvSource(delimiter = '|', value = {
            // Backslash is a path separator for special schemes, so these are not relative paths.
            "/\\attacker.invalid/x.js       | host attacker.invalid",
            "\\\\attacker.invalid/x.js      | host attacker.invalid",
            "https:/\\attacker.invalid/x    | host attacker.invalid",
            // A special scheme other than the page's own takes an authority after any run of
            // separators, including a single one.
            "http:/attacker.invalid/x       | host attacker.invalid",
            // Three slashes is not an empty host; the parser skips the whole run.
            "///attacker.invalid/x          | host attacker.invalid",
            // A bracketed IPv6 literal parses; '[' ':' ']' are forbidden only outside brackets.
            "https://[::1]/x                | host [::1]",
            // Schemes off the safe allowlist leave the origin whether or not they run script.
            "ftp://attacker.invalid/x       | the ftp: scheme",
            "file:///etc/passwd             | the file: scheme",
    })
    public void theUrlOracleAgreesWithTheWhatwgUrlStandard(String url, String why) {
        assertTrue(VerdictEvaluator.analyseUrl(url).isDangerous(),
                () -> "analyseUrl called " + url + " safe, but a browser resolves it to " + why
                        + ". A false 'safe' here records a real vulnerability as a SAFE corpus"
                        + " entry, which is the one failure mode the ledger cannot recover from."
                        + " Observed: " + VerdictEvaluator.analyseUrl(url).explanation());
    }

    /**
     * The removal that makes {@code java<LF>script:} live. The standard strips tab, LF and CR from
     * anywhere in a URL before parsing it, so the newline never reaches the scheme parser — which is
     * why a payload split by one is not neutralised by the split.
     *
     * <p>Kept apart from the table above because it cannot be written as a CSV row: the payload has
     * to carry a real control character, and this file stays pure ASCII.
     */
    @Test
    public void tabsAndNewlinesAreRemovedFromAnywhereInAUrl() {
        assertTrue(VerdictEvaluator.analyseUrl("java" + ch(0x0a) + "script:alert(1)").isDangerous(),
                "a newline inside the scheme is removed before parsing, so this is javascript:");
        assertTrue(VerdictEvaluator.analyseUrl("java" + ch(0x09) + "script:alert(1)").isDangerous(),
                "and so is a tab");
        assertTrue(VerdictEvaluator.analyseUrl("java" + ch(0x0d) + "script:alert(1)").isDangerous(),
                "and a carriage return");

        // The literal four-character text html() produces for a tab is not a control character, so
        // it survives and keeps the URL relative. That is the accident CanoeCorpus relies on.
        assertFalse(VerdictEvaluator.analyseUrl("java\\x09script:alert(1)").isDangerous());

        // DEL is above space, so it is neither trimmed nor removed, and it is not a scheme character.
        assertFalse(VerdictEvaluator.analyseUrl("java" + ch(0x7f) + "script:alert(1)").isDangerous());
    }

    /**
     * The two host escapes that genuinely do neutralise an off-origin vector, kept beside the table
     * above so nobody "fixes" them along with it. The standard percent-decodes a host before testing
     * it for forbidden code points, and {@code %} is itself forbidden, so both of these fail to parse
     * in a real browser. Confirmed with Node.
     */
    @Test
    public void aPercentEncodedHostGenuinelyFailsToParse() {
        assertFalse(VerdictEvaluator.analyseUrl(
                        "https://trusted.example%40attacker.invalid/x.js").isDangerous(),
                "%40 decodes to '@', which is a forbidden host code point");
        assertFalse(VerdictEvaluator.analyseUrl("https://host%3A8443/path").isDangerous(),
                "%3A decodes to ':', so this is not a port - the URL does not parse");
        assertFalse(VerdictEvaluator.analyseUrl("https://%5B%3A%3A1%5D/x").isDangerous(),
                "an escaped IPv6 literal is not an IPv6 literal, it is an unparseable host");

        // An unescaped port, by contrast, parses and reaches the host.
        assertTrue(VerdictEvaluator.analyseUrl("https://attacker.invalid:8443/path").isDangerous());
    }

    /**
     * The refresh oracle judges the URL it extracts, not the string it was handed.
     *
     * <p>It used to be {@code sinkValue.contains(SENTINEL_HOST)} — the only sink in the corpus judged
     * by a hostname substring, which means it was judged by the one payload that happened to be in
     * the corpus rather than by a rule. Any redirect to somewhere other than the sentinel host, and
     * any {@code javascript:} refresh target, came back {@link Verdict#SAFE}. That is the false-safe
     * this suite has now corrected three times in three different oracles, so it gets a test of its
     * own rather than a fix and a hope.
     */
    @Test
    public void theRefreshOracleJudgesTheTargetUrlRatherThanMatchingAHostname() {
        // What the corpus payload does, unchanged.
        assertTrue(VerdictEvaluator.analyseUrl(
                VerdictEvaluator.refreshTarget("0;url=//attacker.invalid/target")).isDangerous());

        // ...and the three things the substring match called safe.
        assertTrue(VerdictEvaluator.analyseUrl(
                        VerdictEvaluator.refreshTarget("0;url=javascript:alert(1)")).isDangerous(),
                "a javascript: refresh target must not be safe merely because it names no host");
        assertTrue(VerdictEvaluator.analyseUrl(
                        VerdictEvaluator.refreshTarget("0;url=https://elsewhere.example/x"))
                        .isDangerous(),
                "any off-origin redirect is a redirect, sentinel host or not");
        assertTrue(VerdictEvaluator.analyseUrl(
                        VerdictEvaluator.refreshTarget("0; URL = '//attacker.invalid/x'"))
                        .isDangerous(),
                "the keyword is case-insensitive, spacing is free and the URL may be quoted");

        // A bare delay reloads the current page: there is no target to reach anywhere.
        assertEquals("", VerdictEvaluator.refreshTarget("5"));
        assertFalse(VerdictEvaluator.analyseUrl(
                VerdictEvaluator.refreshTarget("5")).isDangerous());

        // A same-origin redirect stays safe, so the hardening did not simply make everything red.
        assertFalse(VerdictEvaluator.analyseUrl(
                VerdictEvaluator.refreshTarget("0;url=/dashboard")).isDangerous());

        // A value that does not fit the grammar is judged whole rather than waved through.
        assertEquals("javascript:alert(1)", VerdictEvaluator.refreshTarget("javascript:alert(1)"));
    }

    /** A single separator is a path, however it is spelled, and stays on the page's own origin. */
    @Test
    public void oneSeparatorIsAPathNotAnAuthority() {
        assertFalse(VerdictEvaluator.analyseUrl("/attacker.invalid/x").isDangerous());
        assertFalse(VerdictEvaluator.analyseUrl("\\attacker.invalid/x").isDangerous());
        assertFalse(VerdictEvaluator.analyseUrl("https:/attacker.invalid/x").isDangerous(),
                "same scheme as the page, one separator: the parser falls back to a relative path");
        assertFalse(VerdictEvaluator.analyseUrl("mailto:a@attacker.invalid").isDangerous(),
                "mailto: is on the safe allowlist; it loads nothing into the page");
        assertFalse(VerdictEvaluator.analyseUrl("https://app.example/x").isDangerous());
    }

    /**
     * A one-character string from a code unit, so that this file stays pure ASCII and cannot be
     * corrupted by a compiler running under a non-UTF-8 default charset.
     */
    private static String ch(int codeUnit) {
        return String.valueOf((char) codeUnit);
    }

    // ------------------------------------------------------------------
    // Structural safety, asserted rather than assumed
    // ------------------------------------------------------------------

    /**
     * The property the review's corollary rests on: attacker data can never move Canoe's state
     * machine, because no encoder can emit a raw {@code <} and quotes are always neutralised.
     *
     * <p>Both halves matter. A raw {@code "} terminates an attribute value in Canoe's state machine
     * just as effectively as a {@code <} opens a tag, so checking only for {@code <} would stay green
     * if {@code html()} ever stopped escaping quotes.
     *
     * <p>For the Appendix A &sect;A.7 cases Canoe rejects there is no output to count delimiters in,
     * so the property is asserted in the form that still means something there: the rejection must be
     * a property of the <em>template</em> and not of the payload. A payload that turned an accepted
     * template into a rejected one, or the reverse, would be steering the parser just as surely as one
     * that opened a tag — and F9 shows the reverse is not hypothetical, since a shifted parse window
     * can suppress an encoding error outright.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("invocations")
    public void payloadsCannotAddMarkupDelimitersToOutput(XssCase.Invocation invocation) {
        CanoeTestSupport.RenderResult attacked = VerdictEvaluator.render(
                invocation.testCase(), invocation.payload().value());
        CanoeTestSupport.RenderResult benign = VerdictEvaluator.render(
                invocation.testCase(), Payloads.INERT_MARKER.value());

        if (attacked.isError() || benign.isError()) {
            assertEquals(benign.isError(), attacked.isError(),
                    () -> "Whether Canoe rejects this template depends on the payload, so the"
                            + " payload is steering the parser: " + invocation
                            + "\n  benign   : " + describe(benign)
                            + "\n  attacked : " + describe(attacked));

            assertEquals(withoutPosition(benign.errorMessage()),
                    withoutPosition(attacked.errorMessage()),
                    () -> "The payload changed which error Canoe raised: " + invocation
                            + "\n  benign   : " + benign.errorMessage()
                            + "\n  attacked : " + attacked.errorMessage());

            // The reported position does legitimately move, and stripping it was leaving the only
            // part of the message the payload can influence unasserted. It moves by an exactly
            // predictable amount: the error is raised at a fixed offset into the template, so the
            // reported position differs by however much longer the encoded payload made the output
            // before that point. Asserting the identity rather than deleting the number is what
            // turns "the position is allowed to drift" into "the position drifts by exactly the
            // length of what was written". Verified against all the rows where it actually drifts.
            assertEquals(reportedPosition(attacked.errorMessage()) - reportedPosition(benign.errorMessage()),
                    attacked.output().length() - benign.output().length(),
                    () -> "The reported error position moved by an amount the payload's own encoded"
                            + " length does not account for, which means the payload changed WHERE"
                            + " Canoe stopped and not merely how much it had written: " + invocation
                            + "\n  benign   : " + benign.errorMessage()
                            + " after " + benign.output().length() + " characters"
                            + "\n  attacked : " + attacked.errorMessage()
                            + " after " + attacked.output().length() + " characters");
            // ...and then fall through, because a rejected render still has partial output, and the
            // delimiter counts have to hold in it too. Skipping the loop here left 44 partial
            // outputs unchecked for the property the test is named after.
        }

        for (char delimiter : new char[]{'<', '>', '"', '\''}) {
            assertEquals(count(benign.output(), delimiter), count(attacked.output(), delimiter),
                    () -> "Payload changed the number of '" + delimiter + "' characters in the"
                            + " output, so it can steer the parser: " + invocation
                            + "\n  benign   : " + CanoeTestSupport.quote(benign.output())
                            + "\n  attacked : " + CanoeTestSupport.quote(attacked.output()));
        }
    }

    /**
     * Proves the check above is not vacuous. Most payloads contain no markup delimiter at all, so an
     * unencoded render must be shown to trip it — the same non-blind-oracle argument the plan makes
     * for the browser tier's detectors.
     */
    @Test
    public void theDelimiterCheckWouldCatchAnUnencodedRender() {
        String benign = "<p>" + Payloads.INERT_MARKER.value() + "</p>";
        String attacked = "<p>" + Payloads.TAG_IMG_ONERROR.value() + "</p>";

        assertTrue(count(attacked, '<') > count(benign, '<'),
                "an unencoded tag-breakout payload must change the '<' count");
        assertTrue(count(attacked, '"') == count(benign, '"'),
                "and this particular payload carries no quotes, which is why both are checked");
        assertTrue(count("<a title=\"" + Payloads.ATTR_DOUBLE_QUOTE_BREAKOUT.value() + "\">", '"')
                        > count("<a title=\"x\">", '"'),
                "an unencoded attribute-breakout payload must change the quote count");
    }

    private static long count(String haystack, char needle) {
        return haystack.chars().filter(c -> c == needle).count();
    }

    /** An encoding error message with its {@code (line: N, pos: N)} suffix removed. */
    private static String withoutPosition(String errorMessage) {
        int at = errorMessage.lastIndexOf(" (line: ");
        return at < 0 ? errorMessage : errorMessage.substring(0, at);
    }

    /** The {@code pos:} field of an encoding error message, or {@code -1} when it carries none. */
    private static int reportedPosition(String errorMessage) {
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("pos: (\\d+)\\)").matcher(errorMessage);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private static String describe(CanoeTestSupport.RenderResult result) {
        return result.isError()
                ? "rejected: " + result.errorMessage()
                : "rendered: " + CanoeTestSupport.quote(result.output());
    }

    // ------------------------------------------------------------------
    // Corpus integrity
    // ------------------------------------------------------------------

    @Test
    public void corpusIsNotEmpty() {
        assertFalse(CanoeCorpus.all().isEmpty());
        assertFalse(CanoeCorpus.browserInvocations().isEmpty());
    }

    @Test
    public void caseIdsAreUnique() {
        Set<String> seen = new HashSet<>();
        for (XssCase testCase : CanoeCorpus.all()) {
            assertTrue(seen.add(testCase.id()), "duplicate case id: " + testCase.id());
        }
    }

    /**
     * JUnit shows {@link XssCase.Invocation#toString()} as the test name, and the report is useless
     * if eight tests share a name and three of them fail.
     */
    @Test
    public void invocationDisplayNamesAreUnique() {
        Set<String> seen = new LinkedHashSet<>();
        for (XssCase.Invocation invocation : CanoeCorpus.allInvocations()) {
            assertTrue(seen.add(invocation.toString()),
                    "duplicate invocation display name: " + invocation);
        }
    }

    @Test
    public void everyCaseIsRetrievableById() {
        for (XssCase testCase : CanoeCorpus.all()) {
            assertEquals(testCase, CanoeCorpus.byId(testCase.id()));
        }
        assertThrows(IllegalArgumentException.class, () -> CanoeCorpus.byId("no.such.case"));
    }

    @Test
    public void everyCaseDeclaresASection() {
        for (XssCase testCase : CanoeCorpus.all()) {
            assertNotNull(testCase.section(), testCase.id() + " has no Appendix A section");
        }
    }

    /**
     * Both live verdicts, not just {@link Verdict#KNOWN_VULNERABLE}. Asking the narrower question
     * would make this loop body unreachable now that the count is zero — the ledger-level twin of
     * the builder guard in {@code XssCase.validate()}, which R26 widened to
     * {@link Verdict#reachesSinkLive()} for exactly this reason.
     */
    @Test
    public void vulnerableCasesCiteAFinding() {
        for (XssCase testCase : CanoeCorpus.all()) {
            boolean anyVulnerable = testCase.payloads().stream()
                    .anyMatch(p -> testCase.verdictFor(p).reachesSinkLive());
            if (anyVulnerable) {
                assertNotNull(testCase.finding(),
                        testCase.id() + " records a vulnerability but cites no finding");
                assertFalse(testCase.finding().isEmpty());
            }
        }
    }

    @Test
    public void aCaseCannotClaimAVulnerabilityWithoutCitingOne() {
        assertThrows(IllegalArgumentException.class, () -> XssCase.id("uncited")
                .template("<p>$data</p>")
                .textSink("p")
                .payloads(Payloads.TAG_IMG_ONERROR)
                .verdict(Verdict.KNOWN_VULNERABLE)
                .build());
    }

    @Test
    public void aCaseCannotOverrideAPayloadItDoesNotUse() {
        assertThrows(IllegalArgumentException.class, () -> XssCase.id("stray-override")
                .template("<p>$data</p>")
                .textSink("p")
                .payloads(Payloads.TAG_IMG_ONERROR)
                .verdict(Verdict.SAFE)
                .override(Payloads.CSS_OVERLAY, Verdict.SUPPRESSED_BY_DESIGN)
                .build());
    }

    @Test
    public void aCaseCannotOmitItsSinkOrVerdictOrPayloads() {
        assertThrows(NullPointerException.class, () -> XssCase.id("no-sink")
                .template("<p>$data</p>")
                .payloads(Payloads.TAG_IMG_ONERROR)
                .verdict(Verdict.SAFE)
                .build());

        assertThrows(NullPointerException.class, () -> XssCase.id("no-verdict")
                .template("<p>$data</p>")
                .textSink("p")
                .payloads(Payloads.TAG_IMG_ONERROR)
                .build());

        assertThrows(IllegalArgumentException.class, () -> XssCase.id("no-payloads")
                .template("<p>$data</p>")
                .textSink("p")
                .verdict(Verdict.SAFE)
                .build());
    }

    @Test
    public void familyVerdictsResolveBetweenDefaultAndOverride() {
        XssCase testCase = XssCase.id("resolution")
                .template("<a href=\"$data\">x</a>")
                .sink(SinkKind.URL, "a", "href")
                .payloads(Payloads.families("JS_URL", "PROTOCOL_RELATIVE"))
                .verdict(Verdict.SAFE)
                .overrideFamily("JS_URL", Verdict.KNOWN_VULNERABLE)
                .override(Payloads.JS_URL_TAB_SPLIT, Verdict.SAFE)
                .finding("F6")
                .build();

        assertEquals(Verdict.KNOWN_VULNERABLE, testCase.verdictFor(Payloads.JS_URL));
        assertEquals(Verdict.SAFE, testCase.verdictFor(Payloads.JS_URL_TAB_SPLIT));
        assertEquals(Verdict.SAFE, testCase.verdictFor(Payloads.PROTOCOL_RELATIVE));
    }

    /**
     * Every payload family is reached by at least one case.
     *
     * <p>This used to be one-directional, with a {@code KNOWN_GAPS} list of families no case had
     * claimed yet and a companion test that failed once the list emptied. T12 emptied it — {@code
     * CSS_IMPORT}, {@code META_REFRESH}, {@code BASE_HIJACK}, {@code DOM_CLOBBER}, {@code
     * CONTROL_CHARS} and {@code LENGTH_STRESS} all have cases now — so, as that companion test's
     * javadoc instructed, both it and the list are gone and this asserts full coverage instead.
     *
     * <p>Adding a payload family without a case that uses it now fails here, which is the right
     * direction: a family nobody attacks with is a family nobody has reviewed.
     */
    /**
     * The browser-observability axis, kept honest.
     *
     * <p>The flag exists so that a ledger entry about <em>Canoe's output</em> and a browser tier that
     * asserts on <em>a 2026 engine's behaviour</em> can both be right about the same row: {@code
     * srcset} never runs a {@code javascript:} URL, {@code expression()} died with Internet Explorer,
     * and a {@code data:} URL in a background-image attribute loads no document. Without the axis,
     * &sect;5.2's "divergence in either direction fails the test" turns about twenty rows into
     * guaranteed browser-tier failures the moment T25–T29 land.
     *
     * <p>Two properties keep it from becoming a way to quietly excuse a row. It may only be set on a
     * {@link Verdict#KNOWN_VULNERABLE} pairing — a safe control is expected to trip nothing anyway,
     * so flagging one says nothing and hides the intent — and it may only be set on a case the
     * browser tier will actually load, since a flag on a case no browser sees is decoration.
     *
     * <p><strong>No row uses it any more, and that is the state Phase A left the corpus in.</strong>
     * The axis carried about twenty invocations at its peak: {@code srcset} never running a
     * {@code javascript:} URL, {@code vbscript:} and {@code expression()} having no engine left, a
     * {@code data:} URL in a background attribute loading no document, {@code onvisibilitychange}
     * having no element that hosts it, and the CSP nonce having no policy to be admitted by. Every
     * one of those rows has since been re-verdicted to a suppression by R2, R3, R4, R5, R6 or R7, and
     * the corpus only permits the flag on a {@code KNOWN_VULNERABLE} row — so the flags went with the
     * verdicts they qualified, and each one's reasoning was moved into the note it belonged to rather
     * than deleted. What is left {@code KNOWN_VULNERABLE} is F6's off-origin rows, and a browser
     * confirms every one of them.
     *
     * <p>The count assertion was therefore inverted rather than dropped. It used to be
     * {@code flagged > 0} — "the axis exists and nothing uses it" was the merge accident it guarded
     * against — and the guard is now on the <em>machinery</em> instead, exercised against a synthetic
     * case, so that losing the axis still fails here while an empty axis does not.
     */
    @Test
    public void browserObservabilityIsOnlyClaimedWhereItChangesAnExpectation() {
        for (XssCase.Invocation invocation : CanoeCorpus.allInvocations()) {
            if (invocation.isBrowserObservable()) {
                continue;
            }
            assertTrue(invocation.verdict().reachesSinkLive(),
                    () -> invocation + " is flagged not-browser-observable and is "
                            + invocation.verdict() + ", which does not claim the data reached the"
                            + " sink. The flag only means something for a row that claims a live"
                            + " vector - either live verdict, since R26 - because for anything else"
                            + " the browser tier already expects silence and the flag hides the"
                            + " reasoning instead of recording it.");
            assertTrue(invocation.testCase().isBrowserRelevant(),
                    () -> invocation + " is flagged not-browser-observable, but its case is not"
                            + " browser-relevant, so no browser will ever load it and the flag is"
                            + " decoration. Say it in the note instead.");
        }

        // The machinery, exercised on a case built here rather than taken from the corpus, so that
        // the axis cannot be quietly removed while no real row is using it.
        XssCase synthetic = XssCase.id("observability-selftest")
                .section("self-test")
                .template("<a href=\"$data\">go</a>")
                .sink(SinkKind.URL, "a", "href")
                .payloads(Payloads.PROTOCOL_RELATIVE, Payloads.ABSOLUTE_OFFSITE_HTTPS)
                .verdict(Verdict.KNOWN_VULNERABLE)
                .finding("none - self test")
                .notBrowserObservable(Payloads.PROTOCOL_RELATIVE)
                .browserRelevant()
                .build();
        assertFalse(synthetic.isBrowserObservable(Payloads.PROTOCOL_RELATIVE),
                "the flag must still take effect, or the browser tier has lost the only way it has"
                        + " of expecting a detector miss without a ledger divergence");
        assertTrue(synthetic.isBrowserObservable(Payloads.ABSOLUTE_OFFSITE_HTTPS),
                "...and must not spill onto the payloads it was not set for");
    }

    @Test
    public void everyPayloadFamilyIsReachedByACase() {
        Set<String> used = new HashSet<>();
        for (XssCase testCase : CanoeCorpus.all()) {
            for (Payload payload : testCase.payloads()) {
                used.add(payload.family());
            }
        }

        Set<String> notCovered = new LinkedHashSet<>(Payloads.familyNames());
        notCovered.removeAll(used);
        // INERT_MARKER is the oracle's baseline rather than an attack. It happens to be used by the
        // A.7 rejection cases, where the point is that even an inert value cannot save the template,
        // but it is removed here so that stops being load-bearing.
        notCovered.remove("INERT_MARKER");

        assertTrue(notCovered.isEmpty(),
                "Payload families no case attacks with: " + notCovered
                        + ". Either claim them in a case or delete them from Payloads.");
    }
}
