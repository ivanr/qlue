package com.webkreator.qlue.view.canoe.corpus;

import com.webkreator.qlue.view.canoe.CanoeTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

        // onsubmit is vulnerable; claiming it is safe must also be caught.
        XssCase wronglySafe = XssCase.id("oracle-selftest-safe")
                .section("self-test")
                .template("<form onsubmit=\"v('$data')\"></form>")
                .sink(SinkKind.JAVASCRIPT, "form", "onsubmit")
                .payloads(Payloads.QUOTE_SINGLE_BREAKOUT)
                .verdict(Verdict.SAFE)
                .build();
        VerdictEvaluator.Observation vulnerableReality =
                VerdictEvaluator.observe(wronglySafe, Payloads.QUOTE_SINGLE_BREAKOUT);
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
     * The three verdicts the first review of this corpus found wrong. Each is safe only by accident
     * of how {@code url()} escapes one character, so each is pinned with the reason: if the encoder
     * changes, these flip, and the ledger must be revisited rather than quietly following along.
     */
    @Test
    public void urlEncodingAccidentsThatMakeOffsiteVectorsSafe() {
        // '@' escaped to %40 puts a forbidden code point inside the host: the URL fails to parse.
        assertFalse(VerdictEvaluator.analyseUrl(
                "https://trusted.example%40attacker.invalid/x.js").isDangerous());
        // ...whereas an unescaped '@' really would reach the attacker host.
        assertTrue(VerdictEvaluator.analyseUrl(
                "https://trusted.example@attacker.invalid/x.js").isDangerous());

        // '\' escaped to %5C stays a same-origin path; no browser un-escapes it to a separator.
        assertFalse(VerdictEvaluator.analyseUrl("/%5Cattacker.invalid/x.js").isDangerous());
        assertTrue(VerdictEvaluator.analyseUrl("//attacker.invalid/x.js").isDangerous());

        // The scheme regex is case-sensitive, so HTTPS: is escaped and the result is relative.
        assertFalse(VerdictEvaluator.analyseUrl("HTTPS%3A//attacker.invalid/x.js").isDangerous());
        assertTrue(VerdictEvaluator.analyseUrl("HTTPS://attacker.invalid/x.js").isDangerous());

        // html() renders C0 controls as the four literal characters \xNN, and a backslash is not a
        // valid scheme character, so a tab-split javascript: URL becomes a relative path.
        assertFalse(VerdictEvaluator.analyseUrl("java\\x09script:alert(1)").isDangerous());
        assertTrue(VerdictEvaluator.analyseUrl("javascript:alert(1)").isDangerous());
        // Leading whitespace, by contrast, really is trimmed by browsers before scheme detection.
        assertTrue(VerdictEvaluator.analyseUrl("  javascript:alert(1)").isDangerous());
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

    @Test
    public void vulnerableCasesCiteAFinding() {
        for (XssCase testCase : CanoeCorpus.all()) {
            boolean anyVulnerable = testCase.payloads().stream()
                    .anyMatch(p -> testCase.verdictFor(p) == Verdict.KNOWN_VULNERABLE);
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
     */
    @Test
    public void browserObservabilityIsOnlyClaimedWhereItChangesAnExpectation() {
        int flagged = 0;
        for (XssCase.Invocation invocation : CanoeCorpus.allInvocations()) {
            if (invocation.isBrowserObservable()) {
                continue;
            }
            flagged++;
            assertEquals(Verdict.KNOWN_VULNERABLE, invocation.verdict(),
                    () -> invocation + " is flagged not-browser-observable but is not"
                            + " KNOWN_VULNERABLE. The flag only means something for a row that claims"
                            + " a live vector: for anything else the browser tier already expects"
                            + " silence, so the flag hides the reasoning instead of recording it.");
            assertTrue(invocation.testCase().isBrowserRelevant(),
                    () -> invocation + " is flagged not-browser-observable, but its case is not"
                            + " browser-relevant, so no browser will ever load it and the flag is"
                            + " decoration. Say it in the note instead.");
        }
        assertTrue(flagged > 0, "no invocation is flagged not-browser-observable, which would mean"
                + " the axis exists and nothing uses it - check it was not lost in a merge");
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
