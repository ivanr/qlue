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
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 *   <li>{@code matrix.md} — the scoreboard, one table per Appendix A
 *       section, and two rosters: every {@code KNOWN_VULNERABLE} pairing, which since R26 is empty
 *       and asserted to be, and every {@code ACCEPTED_RESIDUAL} one with the non-executing sink it
 *       reaches. The first is the working list - the set of things a fix has to flip; the second is
 *       the set somebody decided to live with, which is the list that needs re-reading rather than
 *       fixing.
 *   <li>{@code matrix.csv} — the same data at (case, payload) granularity, one row per invocation,
 *       so the browser tier and anything written later can consume it without parsing Markdown.
 * </ul>
 *
 * <p>A case's finding citation is reported and not checked. It used to be both: the report measured
 * corpus coverage against a finding list parsed out of the Canoe security reviews' glance tables, so
 * a finding written there and not tested here showed up as a gap on the next run. Those reviews are
 * now held outside this repository, and a coverage denominator that lives on somebody's disk is one
 * that passes or fails depending on whose machine ran the build. So the denominator is gone and the
 * citations remain: {@code XssCase.validate()} still refuses a live verdict that cites nothing, and
 * the columns below still say which finding each row is about, but nothing here resolves an
 * {@code F<n>} against a document any more.
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

    /** Written by {@code BrowserCorpusTest} when the browser tier has run. */
    private static final Path BROWSER_RESULTS =
            Paths.get("build", "reports", "canoe-browser", "corpus-results.csv");

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
        Map<String, BrowserResult> browser = readBrowserResults();

        Files.createDirectories(OUTPUT_DIR);
        Path markdown = OUTPUT_DIR.resolve("matrix.md");
        Path csv = OUTPUT_DIR.resolve("matrix.csv");
        Files.write(markdown, renderMarkdown(rows, browser).getBytes(StandardCharsets.UTF_8));
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

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private static String renderMarkdown(List<Row> rows, Map<String, BrowserResult> browser) {
        StringBuilder sb = new StringBuilder(64 * 1024);

        sb.append("# Canoe test matrix\n\n");
        sb.append("Generated by `MatrixReportTest` on every `./gradlew test` run, from"
                + " `CanoeCorpus`. Do not edit: it is regenerated.\n\n");
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
                        + " the design, and it is the signal to update the"
                        + " ledger rather than a regression.\n\n");

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

    private static final class BrowserResult {
        String engine;
        String detectors;
    }
}
