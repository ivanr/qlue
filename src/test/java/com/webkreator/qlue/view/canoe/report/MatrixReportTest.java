package com.webkreator.qlue.view.canoe.report;

import com.webkreator.qlue.view.canoe.CanoeTestSupport;
import com.webkreator.qlue.view.canoe.corpus.CanoeCorpus;
import com.webkreator.qlue.view.canoe.corpus.Verdict;
import com.webkreator.qlue.view.canoe.corpus.XssCase;
import com.webkreator.qlue.view.canoe.property.ContextRecordingCanoe;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The suite as documentation: {@code build/reports/canoe/matrix.md} (T33).
 *
 * <h2>Why this is a test and not a script</h2>
 *
 * <p>F8 is "no tests, no documentation, no published threat model". The tests answer the first
 * clause; this file answers the third, and it answers it by <strong>generating</strong> the threat
 * model from the corpus rather than writing one alongside it. A written threat model is out of date
 * the first time somebody adds a case. This one cannot be, because it is produced by the same
 * {@code ./gradlew test} run that asserts the corpus, from the same objects.
 *
 * <p>It is a test rather than a Gradle task for the same reason: a report nobody runs is a report
 * nobody reads. {@code build.gradle} passes {@code canoe.report.dir}, so it lands in
 * {@code build/reports/canoe/} on every run.
 *
 * <h2>What it emits</h2>
 *
 * <ul>
 *   <li>{@code matrix.md} — the scoreboard, the finding-coverage table, one table per Appendix A
 *       section, and two rosters: every {@code KNOWN_VULNERABLE} pairing, which since R26 is empty
 *       and asserted to be, and every {@code ACCEPTED_RESIDUAL} one with the non-executing sink it
 *       reaches. The first is the working list - the set of things a fix has to flip; the second is
 *       the set somebody decided to live with, which is the list that needs re-reading rather than
 *       fixing.
 *   <li>{@code matrix.csv} — the same data at (case, payload) granularity, one row per invocation,
 *       so the browser tier and anything written later can consume it without parsing Markdown.
 * </ul>
 *
 * <p>The finding list is read out of the two Canoe security reviews' own glance tables
 * rather than restated here. That is what makes "a finding with zero corpus cases" a measurement:
 * the denominator comes from the reviews, so a finding added there and not tested here shows up in
 * the report as a gap on the next run, without anyone remembering to look.
 *
 * <h2>The browser column</h2>
 *
 * <p>The browser tier lives in a separate source set and does not run under {@code test}, so this
 * report shows the corpus's browser <em>expectation</em> — fire, silent, or not loaded — and
 * overlays what actually happened if {@code build/reports/canoe-browser/corpus-results.csv} exists
 * from a previous {@code ./gradlew browserTest}. Reporting a stale-but-labelled result beats
 * reporting nothing; the file carries the engines that produced it.
 */
public class MatrixReportTest {

    /** Where {@code build.gradle} tells us to write. Falls back so an IDE run still works. */
    private static final Path OUTPUT_DIR = Paths.get(
            System.getProperty("canoe.report.dir", "build/reports/canoe"));

    /**
     * The reviews, which own the finding list this report measures coverage against.
     *
     * <p>A list rather than a single file since the second review (2026-07-27), which adds F25-F28.
     * The glance tables are parsed in order and the first definition of an id wins, so a later
     * document may restate a finding without silently redefining its severity. The list is what makes
     * "a finding with zero corpus cases" a measurement: the denominator is the reviews' own, so a
     * finding written in either and not tested here shows up as a gap on the next run.
     */
    private static final List<Path> REVIEWS = List.of(
            Paths.get("CANOE-SECURITY-REVIEW-2026-07-25.md"),
            Paths.get("CANOE-SECURITY-REVIEW-2026-07-27-1.md"));

    /** Written by {@code BrowserCorpusTest} when the browser tier has run. */
    private static final Path BROWSER_RESULTS =
            Paths.get("build", "reports", "canoe-browser", "corpus-results.csv");

    /**
     * Findings with no corpus case, and why each one is not a gap.
     *
     * <p>Every entry here is a finding whose subject is not a (template, payload) pairing, so the
     * corpus is structurally the wrong instrument and a case would have to be invented to satisfy a
     * counter. Each names the file that does test it. Anything that appears in the report's gap
     * list and not here fails {@link #everyFindingIsEitherCoveredOrExplained}, which is the point:
     * the exemption list is small, reviewed, and cannot grow silently.
     */
    private static final Map<String, String> FINDINGS_WITHOUT_CASES = explainedGaps();

    private static Map<String, String> explainedGaps() {
        Map<String, String> gaps = new LinkedHashMap<>();
        gaps.put("F8", "the finding is 'no tests, no documentation, no threat model'; this suite and"
                + " this report are the answer to it, not a case in it");
        gaps.put("F9", "a Writer-API defect, invisible through Velocity because"
                + " CanoeReferenceInsertionHandler never calls write(char[],int,int) at a non-zero"
                + " offset - CanoeWriterContractTest and ChunkInvarianceTest");
        gaps.put("F12", "closed by R24: an interpolated string literal renders into Velocity's own"
                + " writer, the handler detects that and defers, and the value is encoded once where"
                + " it is printed - unless the literal's consumer is #evaluate, #parse or #include,"
                + " which compile or resolve the string rather than printing it and so keep the"
                + " pre-R24 encoding. The exemption stays, and is reworded rather than deleted,"
                + " because"
                + " the reason it gave is still the true one: the finding is about a Velocity"
                + " reference FORM - #set of an interpolated literal - and a corpus case is a"
                + " template plus a payload at a sink, so the same defect would have to be restated"
                + " once per sink to be counted. It is not stale in the sense this map's second"
                + " assertion catches: no corpus template uses #set in any spelling, verified"
                + " against matrix.csv rather than assumed, so F12 has acquired no cases -"
                + " VelocityIntegrationTest's F12 section and, for the detector itself,"
                + " NestedRenderDetectionTest");
        gaps.put("F7", "closed by R7, and the citation left the corpus with the branch pair: the two"
                + " rows that carried it are re-verdicted under other findings - attr.data-on-object"
                + " is a URL sink citing F6 and refresh.meta-content is a suppression citing F3 -"
                + " because a case cites the finding its CURRENT verdict is about. The finding's own"
                + " evidence is a source-level fact about two identical comparison chains, which is"
                + " where it always belonged - AttributePrefixTest.theDataBranchPairIsResolved and"
                + " AttributeNameMatrixTest.theSourceDeclaresTheTwoNameListsTheMatrixExpects, which"
                + " asserts the ATTR_CONTENT constant and the author's XXX marker are both gone");
        // F13's exemption is deleted rather than reworded, because the finding acquired corpus
        // cases in R20 and a stale exemption is what this map's second assertion exists to catch.
        // The exemption said the finding was about what escapes VelocityViewFactory.render(), which
        // the corpus harness deliberately does not model - and that is still true of F13's *delivery*
        // half, which R21 closed and which CanoeRobustnessTest (via ProductionRenderProbe) and
        // CanoeEncodingExceptionTest own. What changed is that F13's other half is a table of
        // templates, R20 triaged it, and the rows that moved are rows: void.br-no-space, which is
        // the table's first entry rendering instead of failing, and
        // shape.framework-length-attribute-name, which is the length cap that a real page hit. Both
        // cite F13, so the finding now has live regression cases in the ledger as well as in the
        // two tests.
        gaps.put("F15", "url() corrupting legitimate URLs is an author-data defect with no attacker"
                + " payload - HtmlEncoderUrlTest");
        gaps.put("F16", "js() and css() are unreachable from Canoe.encode() today, so no case can"
                + " route a payload through them - HtmlEncoderTest");
        gaps.put("F21", "closed by R14: the CTX_CSS constant and its dead encode() arm are deleted,"
                + " so there is no CSS context for a case to produce - style still classifies as"
                + " ATTR_CSS and suppresses. The source-level fact is asserted by"
                + " AttributeNameMatrixTest.thereIsNoCtxCssAndStyleStillSuppresses");
        gaps.put("F22", "closed by R22: the base factory now sets resource.loader.class.class, so an"
                + " engine built from its own properties starts. The exemption stays because the"
                + " finding is a Velocity engine configuration defect and the corpus is still the"
                + " wrong instrument for it - it was decided at init(), before any template exists,"
                + " and a case is a template plus a payload -"
                + " ViewFactoryRenderTest.theBaseFactorysDefaultPropertiesConfigureTheClassLoader"
                + "TheyDeclare");
        gaps.put("F23", "the CSS double decode is a browser behaviour; the three templates it bounds"
                + " are corpus cases cited against F4 - SinkSpecificBrowserTest");
        gaps.put("F24", "needs two references in one attribute value, which the shared-payload"
                + " corpus cannot express - ParserSteeringTest and TemplateFuzzTest");
        return gaps;
    }

    // ------------------------------------------------------------------
    // The report
    // ------------------------------------------------------------------

    /**
     * Generates the report. Assertions are about the report being real and complete, not about the
     * corpus — the corpus has its own tests and duplicating them here would mean two places to
     * update.
     */
    @Test
    public void theMatrixReportIsGenerated() throws IOException {
        List<Row> rows = rows();
        Map<String, Finding> findings = readFindingsFromTheReview();
        Map<String, BrowserResult> browser = readBrowserResults();

        Files.createDirectories(OUTPUT_DIR);
        Path markdown = OUTPUT_DIR.resolve("matrix.md");
        Path csv = OUTPUT_DIR.resolve("matrix.csv");
        Files.write(markdown, renderMarkdown(rows, findings, browser).getBytes(StandardCharsets.UTF_8));
        Files.write(csv, renderCsv(rows, browser).getBytes(StandardCharsets.UTF_8));

        assertTrue(Files.size(markdown) > 0, () -> markdown + " is empty");
        assertTrue(Files.size(csv) > 0, () -> csv + " is empty");

        String text = Files.readString(markdown, StandardCharsets.UTF_8);
        for (XssCase testCase : CanoeCorpus.all()) {
            assertTrue(text.contains("`" + testCase.id() + "`"),
                    () -> testCase.id() + " is in the corpus and not in the report, so the report is"
                            + " not the corpus and cannot be read as one");
        }

        assertEquals(CanoeCorpus.all().stream().mapToInt(c -> c.payloads().size()).sum(), rows.size(),
                "one row per (case, payload) invocation");

        // The CSV must have exactly one header line plus one line per invocation, or something
        // downstream will read a truncated matrix and believe it.
        List<String> csvLines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        assertEquals(rows.size() + 1, csvLines.size(), "CSV header plus one line per invocation");
    }

    /**
     * Every finding in the review's glance table either has a corpus case or an explained reason for
     * not having one.
     *
     * <p>This is the assertion that turns the report's coverage table into something with teeth. A
     * new finding added to the review with no case and no entry in {@link #FINDINGS_WITHOUT_CASES}
     * fails here rather than sitting in a generated table nobody reads.
     *
     * <p>It fails in the other direction too: an exemption for a finding that has since acquired
     * cases is a stale exemption, and a stale exemption is how a real gap gets to hide behind an
     * old excuse.
     */
    @Test
    public void everyFindingIsEitherCoveredOrExplained() throws IOException {
        Map<String, Finding> findings = readFindingsFromTheReview();
        Set<String> covered = CanoeCorpus.all().stream()
                .map(XssCase::finding)
                .filter(f -> f != null && !f.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<String> unexplained = new ArrayList<>();
        for (String id : findings.keySet()) {
            if (!covered.contains(id) && !FINDINGS_WITHOUT_CASES.containsKey(id)) {
                unexplained.add(id + " - " + findings.get(id).summary);
            }
        }
        assertTrue(unexplained.isEmpty(),
                () -> "These findings have no corpus case and no recorded reason:\n  "
                        + String.join("\n  ", unexplained)
                        + "\nEither add a case, or add an entry to FINDINGS_WITHOUT_CASES saying"
                        + " which test covers it and why the corpus is the wrong instrument.");

        List<String> stale = new ArrayList<>();
        for (String id : FINDINGS_WITHOUT_CASES.keySet()) {
            if (covered.contains(id)) {
                stale.add(id);
            }
        }
        assertTrue(stale.isEmpty(),
                () -> "These findings are listed as having no corpus case, and they now have one: "
                        + stale + ". Delete the exemption.");

        // ...and no case may cite a finding the review does not have.
        for (String id : covered) {
            assertTrue(findings.containsKey(id),
                    () -> "a corpus case cites " + id + ", which is not in the review's glance"
                            + " table. Either the finding was renumbered or the citation is wrong;"
                            + " a KNOWN_VULNERABLE row with a dangling citation is the review"
                            + " failure PLAN.md section 8 describes.");
        }
    }

    /**
     * <strong>R26's second guard: a live row's citation has to resolve.</strong>
     *
     * <p>The corpus already refuses a live verdict with no citation at all ({@code
     * XssCase.validate()}), and {@link #everyFindingIsEitherCoveredOrExplained} already refuses a
     * citation the review does not have — but it applies that to every case, including the safe ones
     * whose citation is historical. This states the property where it matters most and in the form
     * R26 asked for: <em>a row that says attacker data reaches the sink live must point at a real
     * finding in one of the reviews</em>. That is the sentence the ledger's
     * design note rests on, and "the count went back above zero with nothing attached" is what its
     * failure looks like from outside.
     *
     * <p>It lives here rather than in {@code CanoeCorpusTest} because the review is the authority for
     * the finding list and {@link #readFindingsFromTheReview()} is the one parser for it. A second
     * parser next to the ledger would be a second list to drift.
     *
     * <p>Both live verdicts are checked, which is also what keeps this from being vacuous while
     * {@code KNOWN_VULNERABLE} is empty: the 68 {@link Verdict#ACCEPTED_RESIDUAL} rows all cite F6
     * and all resolve, so the assertion is exercised on every run and would notice F6 being
     * renumbered out from under them.
     */
    @Test
    public void everyRowThatReachesItsSinkLiveCitesAFindingTheReviewHas() throws IOException {
        Map<String, Finding> findings = readFindingsFromTheReview();

        List<String> offenders = new ArrayList<>();
        int checked = 0;
        for (XssCase.Invocation invocation : CanoeCorpus.allInvocations()) {
            if (!invocation.verdict().reachesSinkLive()) {
                continue;
            }
            checked++;
            String finding = invocation.testCase().finding();
            if (finding == null || finding.isEmpty()) {
                offenders.add(invocation + " (" + invocation.verdict() + ") cites nothing");
            } else if (!findings.containsKey(finding)) {
                offenders.add(invocation + " (" + invocation.verdict() + ") cites " + finding
                        + ", which is not in the review's glance table");
            }
        }

        assertTrue(offenders.isEmpty(),
                () -> "These rows record attacker data reaching a sink live without a finding the"
                        + " review actually has:\n  " + String.join("\n  ", offenders)
                        + "\n\nA live row is a claim about this component's security, and the"
                        + " citation is what makes it reviewable: it says where the reasoning is and"
                        + " who agreed to it. If the vector is new, open a finding in "
                        + REVIEWS + " and cite it. If the finding was renumbered, fix the citation."
                        + " Do not leave the row uncited - an unattributed vulnerability in a ledger"
                        + " is indistinguishable from a note somebody left themselves.");

        assertTrue(checked > 0,
                "no row in the ledger records data reaching a sink live at all, so this assertion"
                        + " checked nothing. If F6's residue has genuinely been closed, that is the"
                        + " best possible reason for this failure and the test should be reduced to"
                        + " its KNOWN_VULNERABLE half; if it has not, the ledger has stopped being"
                        + " readable from here.");
    }

    /**
     * The review's glance table parses, and it has the number of rows the review's summary claims.
     *
     * <p>The report's whole coverage denominator comes from a regular expression over a Markdown
     * table. If that regex silently matched nothing, the coverage table would be empty, the gap list
     * would be empty, and everything above would pass. So the parse is asserted before it is used.
     */
    @Test
    public void theReviewsGlanceTableParses() throws IOException {
        Map<String, Finding> findings = readFindingsFromTheReview();

        assertFalse(findings.isEmpty(), "no findings parsed out of " + REVIEWS);
        assertTrue(findings.containsKey("F1"), "F1 must parse");
        assertEquals("Critical", findings.get("F1").severity);
        assertTrue(findings.size() >= 28,
                () -> "the reviews declare at least 28 findings; parsed " + findings.size() + ": "
                        + findings.keySet());

        // The numbering must be dense: F1..Fn with nothing missing, or a gap in the report's
        // coverage table would be indistinguishable from a finding that was never written.
        for (int i = 1; i <= findings.size(); i++) {
            String id = "F" + i;
            assertTrue(findings.containsKey(id),
                    () -> id + " is missing from the glance table, which is numbered densely");
        }
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private static String renderMarkdown(List<Row> rows, Map<String, Finding> findings,
                                         Map<String, BrowserResult> browser) {
        StringBuilder sb = new StringBuilder(64 * 1024);

        sb.append("# Canoe test matrix\n\n");
        sb.append("Generated by `MatrixReportTest` on every `./gradlew test` run, from"
                + " `CanoeCorpus` and the glance tables of the two Canoe security reviews."
                + " Do not edit: it is regenerated.\n\n");
        sb.append("A machine-readable form of the same data, one row per (case, payload)"
                + " invocation, is in `matrix.csv` alongside this file.\n\n");

        // Scoreboard.
        Map<Verdict, Integer> byVerdict = new LinkedHashMap<>();
        Map<Verdict, Set<String>> casesByVerdict = new LinkedHashMap<>();
        for (Verdict verdict : Verdict.values()) {
            byVerdict.put(verdict, 0);
            casesByVerdict.put(verdict, new LinkedHashSet<>());
        }
        for (Row row : rows) {
            byVerdict.merge(row.verdict, 1, Integer::sum);
            casesByVerdict.get(row.verdict).add(row.caseId);
        }

        sb.append("## Scoreboard\n\n");
        sb.append("| Verdict | Invocations | Cases | What it means |\n");
        sb.append("|---|---:|---:|---|\n");
        for (Verdict verdict : Verdict.values()) {
            sb.append("| `").append(verdict).append("` | ").append(byVerdict.get(verdict))
                    .append(" | ").append(casesByVerdict.get(verdict).size()).append(" | ")
                    .append(meaningOf(verdict)).append(" |\n");
        }
        sb.append("| **total** | **").append(rows.size()).append("** | **")
                .append(CanoeCorpus.all().size()).append("** | |\n\n");

        int vulnerable = byVerdict.get(Verdict.KNOWN_VULNERABLE);
        int residual = byVerdict.get(Verdict.ACCEPTED_RESIDUAL);
        if (vulnerable == 0) {
            sb.append("> **`KNOWN_VULNERABLE`: 0.** It opened at 281 invocations. Everything that"
                    + " was exploitable - a value arriving live in a JavaScript, CSS, markup or"
                    + " resource-loading sink - is closed at the component;"
                    + " `CanoeCorpusTest.noInvocationIsKnownVulnerable` asserts the count and"
                    + " fails if it ever goes back up.\n>\n");
        } else {
            sb.append("> **`KNOWN_VULNERABLE`: ").append(vulnerable).append(" invocations across ")
                    .append(casesByVerdict.get(Verdict.KNOWN_VULNERABLE).size())
                    .append(" cases, and the count is asserted to be zero - so the build is red.**"
                            + " Each of them is a template where attacker data reaches a sink that"
                            + " executes what it is given. See the roster below.\n>\n");
        }
        sb.append("> **`ACCEPTED_RESIDUAL`: ").append(residual).append(" invocations across ")
                .append(casesByVerdict.get(Verdict.ACCEPTED_RESIDUAL).size())
                .append(" cases.** These are the ones that could not be driven to zero by fixing"
                        + " anything: attacker data reaches the sink and the sink is not code"
                        + " execution - an off-origin link, an off-origin image, an off-origin form"
                        + " action. Every one is F6, every one carries a `ResidualSink` saying what"
                        + " the browser does with the value instead, and the set is pinned to an"
                        + " explicit list that may only shrink. Their roster is at the end of this"
                        + " file too.\n>\n")
                .append("> A row under either verdict **fails when it stops being true** - that is"
                        + " the design (PLAN.md section 2.1), and it is the signal to update the"
                        + " ledger rather than a regression.\n\n");

        // Finding coverage.
        sb.append("## Finding coverage\n\n");
        sb.append("One row per finding in the review's glance table. A finding with no corpus case"
                + " is a coverage gap unless the corpus is structurally the wrong instrument for"
                + " it, in which case the reason and the file that does test it are given.\n\n");
        sb.append("| Finding | Severity | Cases | Invocations | `KNOWN_VULNERABLE` |"
                + " `ACCEPTED_RESIDUAL` | Summary |\n");
        sb.append("|---|---|---:|---:|---:|---:|---|\n");
        for (Map.Entry<String, Finding> entry : findings.entrySet()) {
            String id = entry.getKey();
            List<Row> forFinding = rows.stream()
                    .filter(r -> id.equals(r.finding)).collect(Collectors.toList());
            long cases = forFinding.stream().map(r -> r.caseId).distinct().count();
            long vulnerableRows = forFinding.stream()
                    .filter(r -> r.verdict == Verdict.KNOWN_VULNERABLE).count();
            long residualRows = forFinding.stream()
                    .filter(r -> r.verdict == Verdict.ACCEPTED_RESIDUAL).count();
            sb.append("| **").append(id).append("** | ").append(entry.getValue().severity)
                    .append(" | ").append(cases == 0 ? "**0**" : String.valueOf(cases))
                    .append(" | ").append(forFinding.size())
                    .append(" | ").append(vulnerableRows == 0 ? "0" : "**" + vulnerableRows + "**")
                    .append(" | ").append(residualRows)
                    .append(" | ").append(entry.getValue().summary).append(" |\n");
        }
        sb.append('\n');

        List<String> gaps = findings.keySet().stream()
                .filter(id -> rows.stream().noneMatch(r -> id.equals(r.finding)))
                .collect(Collectors.toList());
        sb.append("### Findings with no corpus case\n\n");
        if (gaps.isEmpty()) {
            sb.append("None.\n\n");
        } else {
            for (String id : gaps) {
                String reason = FINDINGS_WITHOUT_CASES.get(id);
                sb.append("- **").append(id).append("** - ")
                        .append(reason == null
                                ? "**COVERAGE GAP: no case and no recorded reason.**"
                                : reason)
                        .append('\n');
            }
            sb.append("\n`MatrixReportTest.everyFindingIsEitherCoveredOrExplained` fails if a"
                    + " finding appears here without a reason.\n\n");
        }

        // Per-section case tables.
        sb.append("## Cases, by Appendix A section\n\n");
        Map<String, List<Row>> bySection = new TreeMap<>();
        for (Row row : rows) {
            bySection.computeIfAbsent(row.section, key -> new ArrayList<>()).add(row);
        }
        for (Map.Entry<String, List<Row>> entry : bySection.entrySet()) {
            sb.append("### ").append(entry.getKey()).append("\n\n");
            sb.append("| Case | Sink | Context | Encoder | Verdicts | Finding | Browser |\n");
            sb.append("|---|---|---|---|---|---|---|\n");

            Map<String, List<Row>> byCase = new LinkedHashMap<>();
            for (Row row : entry.getValue()) {
                byCase.computeIfAbsent(row.caseId, key -> new ArrayList<>()).add(row);
            }
            List<String> ids = new ArrayList<>(byCase.keySet());
            ids.sort(Comparator.naturalOrder());
            for (String id : ids) {
                List<Row> caseRows = byCase.get(id);
                Row first = caseRows.get(0);
                sb.append("| `").append(id).append("` | ").append(first.sink)
                        .append(" | ").append(first.context)
                        .append(" | `").append(first.encoder).append('`')
                        .append(" | ").append(verdictSummary(caseRows))
                        .append(" | ").append(first.finding == null ? "" : first.finding)
                        .append(" | ").append(browserCell(caseRows, browser))
                        .append(" |\n");
            }
            sb.append('\n');
        }

        // The rosters.
        sb.append("## The `KNOWN_VULNERABLE` roster\n\n");
        List<Row> vulnerableRows = rows.stream()
                .filter(r -> r.verdict == Verdict.KNOWN_VULNERABLE)
                .sorted(Comparator.comparing((Row r) -> r.finding == null ? "zz" : padded(r.finding))
                        .thenComparing(r -> r.caseId).thenComparing(r -> r.payloadId))
                .collect(Collectors.toList());
        if (vulnerableRows.isEmpty()) {
            sb.append("**Empty**, and asserted to be, by"
                    + " `CanoeCorpusTest.noInvocationIsKnownVulnerable`. A pairing appears here when"
                    + " attacker data reaches a sink that executes what it is given; the count"
                    + " opened at 281 and the tasks that closed it are R2 through R12.\n\n");
        } else {
            sb.append("Every pairing where attacker data reaches a sink live at a sink that"
                    + " executes. Drive this list to zero.\n\n");
            sb.append("| Case | Payload | Finding | Sink | Context | Browser |\n");
            sb.append("|---|---|---|---|---|---|\n");
            for (Row row : vulnerableRows) {
                sb.append("| `").append(row.caseId).append("` | `").append(row.payloadId)
                        .append("` | ").append(row.finding == null ? "" : row.finding)
                        .append(" | ").append(row.sink)
                        .append(" | ").append(row.context)
                        .append(" | ").append(browserCell(List.of(row), browser))
                        .append(" |\n");
            }
            sb.append('\n');
        }

        sb.append("## The `ACCEPTED_RESIDUAL` roster\n\n");
        sb.append("Every pairing where attacker data reaches the sink and the sink is not code"
                + " execution. The `Residual sink` column is the claim: `OPEN_REDIRECT` a"
                + " navigation the user starts, `FORM_RETARGET` a submission and its contents,"
                + " `REFERRER_LEAK` a subresource fetch whose response gets no authority in the"
                + " document, `INERT_SINK` an attribute no shipping engine dereferences. The set"
                + " is pinned in `CanoeCorpusTest.PINNED_RESIDUALS` and may only shrink.\n\n");
        sb.append("| Case | Payload | Finding | Residual sink | Sink | Context | Browser |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        List<Row> residualRows = rows.stream()
                .filter(r -> r.verdict == Verdict.ACCEPTED_RESIDUAL)
                .sorted(Comparator.comparing((Row r) -> r.residualSink == null ? "zz" : r.residualSink)
                        .thenComparing(r -> r.caseId).thenComparing(r -> r.payloadId))
                .collect(Collectors.toList());
        for (Row row : residualRows) {
            sb.append("| `").append(row.caseId).append("` | `").append(row.payloadId)
                    .append("` | ").append(row.finding == null ? "" : row.finding)
                    .append(" | `").append(row.residualSink).append('`')
                    .append(" | ").append(row.sink)
                    .append(" | ").append(row.context)
                    .append(" | ").append(browserCell(List.of(row), browser))
                    .append(" |\n");
        }
        sb.append('\n');

        sb.append("## The browser column\n\n");
        if (browser.isEmpty()) {
            sb.append("`build/reports/canoe-browser/corpus-results.csv` does not exist, so no"
                    + " browser results are overlaid. The column shows the corpus's *expectation*"
                    + " only: **fire** for a browser-relevant pairing whose verdict says the data"
                    + " reached the sink live (`KNOWN_VULNERABLE` or `ACCEPTED_RESIDUAL` - since"
                    + " R26 every one of them is the latter) and a browser"
                    + " acts on, **silent** for one it must not act on, **inert** for a pairing"
                    + " flagged `notBrowserObservable`, and blank for a case the browser tier does"
                    + " not load. Run `./gradlew browserTest` and regenerate.\n\n");
        } else {
            sb.append("Overlaid from `build/reports/canoe-browser/corpus-results.csv`, ")
                    .append(browser.size()).append(" results. A cell reading `fire/HIT` or"
                            + " `silent/-` agrees with the ledger; anything else does not, and"
                            + " `BrowserCorpusTest` would have failed.\n\n");
        }

        return sb.toString();
    }

    private static String padded(String finding) {
        // F9 must sort before F10, and a plain string comparison puts it after.
        return finding.matches("F\\d+") ? String.format("F%03d", Integer.parseInt(finding.substring(1)))
                : finding;
    }

    private static String verdictSummary(List<Row> caseRows) {
        Map<Verdict, Integer> counts = new LinkedHashMap<>();
        for (Row row : caseRows) {
            counts.merge(row.verdict, 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .map(e -> "`" + e.getKey() + "`&nbsp;" + e.getValue())
                .collect(Collectors.joining("<br>"));
    }

    private static String browserCell(List<Row> caseRows, Map<String, BrowserResult> browser) {
        Set<String> cells = new LinkedHashSet<>();
        for (Row row : caseRows) {
            if (!row.browserRelevant) {
                continue;
            }
            String expectation = !row.browserObservable ? "inert"
                    : row.verdict.reachesSinkLive() ? "fire" : "silent";
            BrowserResult result = browser.get(row.caseId + " / " + row.payloadId);
            cells.add(result == null ? expectation : expectation + "/" + result.detectors);
        }
        return cells.isEmpty() ? "" : String.join(", ", cells);
    }

    private static String meaningOf(Verdict verdict) {
        switch (verdict) {
            case SAFE:
                return "attacker data reaches the sink inert";
            case KNOWN_VULNERABLE:
                return "attacker data reaches the sink **live**; cites a finding."
                        + " **This column must read 0** (R26)";
            case ACCEPTED_RESIDUAL:
                return "attacker data reaches the sink live and the sink is **not code"
                        + " execution**; cites a finding and names the sink it reaches";
            case SUPPRESSED_BY_DESIGN:
                return "Canoe emits nothing, and that is the intent";
            case SUPPRESSED_UNINTENDED:
                return "Canoe emits nothing where it should have encoded; fail-safe, still a defect";
            case REJECTED:
                return "Canoe raises an encoding error; since R21 it escapes render() as a"
                        + " catchable CanoeEncodingException and the request fails";
            default:
                return "";
        }
    }

    private static String renderCsv(List<Row> rows, Map<String, BrowserResult> browser) {
        StringBuilder sb = new StringBuilder(64 * 1024);
        sb.append("section,case,payload,family,sink,attribute,context,encoder,verdict,"
                + "residual_sink,finding,"
                + "browser_relevant,browser_observable,browser_result,template\n");
        for (Row row : rows) {
            BrowserResult result = browser.get(row.caseId + " / " + row.payloadId);
            sb.append(csv(row.section)).append(',')
                    .append(csv(row.caseId)).append(',')
                    .append(csv(row.payloadId)).append(',')
                    .append(csv(row.family)).append(',')
                    .append(csv(row.sink)).append(',')
                    .append(csv(row.attribute)).append(',')
                    .append(csv(row.context)).append(',')
                    .append(csv(row.encoder)).append(',')
                    .append(csv(row.verdict.name())).append(',')
                    .append(csv(row.residualSink == null ? "" : row.residualSink)).append(',')
                    .append(csv(row.finding == null ? "" : row.finding)).append(',')
                    .append(row.browserRelevant).append(',')
                    .append(row.browserObservable).append(',')
                    .append(csv(result == null ? "" : result.detectors)).append(',')
                    .append(csv(row.template)).append('\n');
        }
        return sb.toString();
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        return '"' + safe.replace("\"", "\"\"").replace("\r", "\\r").replace("\n", "\\n") + '"';
    }

    // ------------------------------------------------------------------
    // Gathering
    // ------------------------------------------------------------------

    private static List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        for (XssCase testCase : CanoeCorpus.all()) {
            String contexts = contextsOf(testCase);
            String encoders = encodersFor(contexts);
            for (XssCase.Invocation invocation : testCase.invocations()) {
                Row row = new Row();
                row.section = testCase.section() == null ? "(unsectioned)" : testCase.section();
                row.caseId = testCase.id();
                row.template = testCase.template();
                row.sink = testCase.sink().name();
                row.attribute = testCase.attribute() == null ? "" : testCase.attribute();
                row.context = contexts;
                row.encoder = encoders;
                row.payloadId = invocation.payload().id();
                row.family = invocation.payload().family();
                row.verdict = invocation.verdict();
                row.residualSink = invocation.residualSink() == null
                        ? null : invocation.residualSink().name();
                row.finding = testCase.finding();
                row.browserRelevant = invocation.isBrowserRelevant();
                row.browserObservable = invocation.isBrowserObservable();
                rows.add(row);
            }
        }
        return rows;
    }

    /**
     * The context Canoe assigns at each reference position in this template.
     *
     * <p>Observed rather than derived. {@code ContextRecordingCanoe} records every
     * {@code currentContext()} call {@code CanoeReferenceInsertionHandler} makes, which is the only
     * place "the context at this reference" exists as a value; the last entry is the state the
     * render finished in and is dropped. A case whose reference is suppressed and a case whose
     * reference is html-encoded look identical in the output for an empty payload, so reading this
     * off the bytes would be guesswork.
     */
    private static String contextsOf(XssCase testCase) {
        AtomicReference<ContextRecordingCanoe> recorder = new AtomicReference<>();
        Map<String, Object> model = new LinkedHashMap<>(testCase.extraModel());
        model.put(testCase.referenceName(), "");
        try {
            CanoeTestSupport.render(testCase.template(), model,
                    CanoeTestSupport.RenderOptions.defaults(),
                    writer -> {
                        ContextRecordingCanoe canoe = new ContextRecordingCanoe(writer);
                        recorder.set(canoe);
                        return canoe;
                    });
        } catch (RuntimeException e) {
            // A rejected template still recorded whatever it saw before it stopped.
        }

        ContextRecordingCanoe canoe = recorder.get();
        if (canoe == null || canoe.contexts().isEmpty()) {
            return "(none)";
        }
        List<Integer> contexts = canoe.contexts();
        List<Integer> atReferences = contexts.size() > 1
                ? contexts.subList(0, contexts.size() - 1)
                : contexts;
        return atReferences.stream().map(CanoeTestSupport::contextName).distinct()
                .collect(Collectors.joining(", "));
    }

    /** The encoder {@code Canoe.encode()} dispatches to for each context named. */
    private static String encodersFor(String contexts) {
        return java.util.Arrays.stream(contexts.split(", "))
                .map(MatrixReportTest::encoderFor)
                .distinct()
                .collect(Collectors.joining(", "));
    }

    private static String encoderFor(String contextName) {
        switch (contextName) {
            case "CTX_HTML":
                return "htmlWhite()";
            case "CTX_HTML_ATTR":
                return "htmlAttr()";
            case "CTX_URI":
                return "url()";
            case "CTX_JS":
                return "(suppressed)";
            case "CTX_SUPPRESS":
                return "(suppressed)";
            default:
                return contextName;
        }
    }

    /**
     * The findings, read out of the review's glance table.
     *
     * <p>Parsed rather than restated, so that the coverage denominator is the review's list and not
     * a second list that drifts from it.
     */
    private static Map<String, Finding> readFindingsFromTheReview() throws IOException {
        Map<String, Finding> findings = new LinkedHashMap<>();
        for (Path review : REVIEWS) {
            assertTrue(Files.isReadable(review),
                    () -> "cannot read " + review.toAbsolutePath() + "; this test must run with the"
                            + " project directory as its working directory");
            Matcher matcher = Pattern.compile("(?m)^\\| (F\\d+) \\| ([^|]+?) \\| (.+?) \\|\\s*$")
                    .matcher(Files.readString(review, StandardCharsets.UTF_8));
            while (matcher.find()) {
                Finding finding = new Finding();
                finding.severity = matcher.group(2).trim();
                finding.summary = matcher.group(3).trim();
                findings.putIfAbsent(matcher.group(1), finding);
            }
        }
        return findings;
    }

    /** Browser results from a previous {@code ./gradlew browserTest}, if any. */
    private static Map<String, BrowserResult> readBrowserResults() throws IOException {
        Map<String, BrowserResult> results = new LinkedHashMap<>();
        if (!Files.isReadable(BROWSER_RESULTS)) {
            return results;
        }
        List<String> lines = Files.readAllLines(BROWSER_RESULTS, StandardCharsets.UTF_8);
        for (int i = 1; i < lines.size(); i++) {
            String[] parts = lines.get(i).split(",", -1);
            if (parts.length < 3) {
                continue;
            }
            BrowserResult result = new BrowserResult();
            result.engine = unquote(parts[0]);
            result.detectors = unquote(parts[2]);
            results.putIfAbsent(unquote(parts[1]), result);
        }
        return results;
    }

    private static String unquote(String value) {
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1).replace("\"\"", "\"");
        }
        return trimmed;
    }

    // ------------------------------------------------------------------
    // Value types
    // ------------------------------------------------------------------

    private static final class Row {
        String section;
        String caseId;
        String template;
        String sink;
        String attribute;
        String context;
        String encoder;
        String payloadId;
        String family;
        Verdict verdict;
        String residualSink;
        String finding;
        boolean browserRelevant;
        boolean browserObservable;
    }

    private static final class Finding {
        String severity;
        String summary;
    }

    private static final class BrowserResult {
        String engine;
        String detectors;
    }
}
