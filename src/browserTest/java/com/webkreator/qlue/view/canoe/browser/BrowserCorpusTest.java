package com.webkreator.qlue.view.canoe.browser;

import com.webkreator.qlue.view.canoe.CanoeTestSupport;
import com.webkreator.qlue.view.canoe.corpus.CanoeCorpus;
import com.webkreator.qlue.view.canoe.corpus.Verdict;
import com.webkreator.qlue.view.canoe.corpus.VerdictEvaluator;
import com.webkreator.qlue.view.canoe.corpus.XssCase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The corpus, in a real browser (T28).
 *
 * <p>Every browser-relevant invocation is rendered through the same {@code VerdictEvaluator.render}
 * the Velocity tier uses, served from the sentinel origin, loaded, interacted with, and judged
 * against its ledger verdict. One corpus, two tiers: the bytes asserted in {@code src/test} and the
 * effects asserted here come from the same {@link XssCase} object, so the two cannot drift.
 *
 * <h2>What the assertion is</h2>
 *
 * <p>A pairing is expected to trip a detector exactly when it is {@link Verdict#KNOWN_VULNERABLE}
 * <em>and</em> {@link XssCase.Invocation#isBrowserObservable()}. Everything else — {@code SAFE},
 * the two suppression verdicts, and the vulnerable-but-unobservable rows — is expected to be
 * silent.
 *
 * <p>The second half of that condition is not a loophole and it is not derived here. The corpus
 * carries the flag, set by review, for rows that target vectors no shipping engine acts on:
 * {@code srcset} never dereferences a {@code javascript:} URL, {@code vbscript:} has no engine
 * left, {@code expression()} died with IE11, a {@code data:} URL in a background-<em>image</em>
 * attribute loads no document, and a handler name that is an IDL attribute on {@code Document}
 * registers nothing from markup. Re-deriving that list here would be the browser tier marking its
 * own homework; plan &sect;A.9 and &sect;5.2 have the reasoning, and
 * {@code CanoeCorpusTest.browserObservabilityIsOnlyClaimedWhereItChangesAnExpectation} stops the
 * flag from being used to excuse anything else.
 *
 * <p>Console errors are not exploitation. Several rows put the attacker's characters into a
 * position where they arrive live and produce a {@code SyntaxError} — an object literal that never
 * closes, a scheme no engine implements. Those are real Canoe defects and real browser misses at
 * the same time, and counting the console would paper over the difference; see
 * {@link BrowserVerdict}.
 *
 * <h2>Per-engine results</h2>
 *
 * <p>Results are recorded per engine and written to
 * {@code build/reports/canoe-browser/corpus-results.md} rather than collapsed into one verdict.
 * &sect;5.2 names {@code xlink:href} and {@code srcdoc} as vectors that behave differently across
 * engines; a tier that took the union or the intersection would report agreement it never
 * measured. Each (engine, invocation) pair is its own test, so a divergence is a named failing test
 * rather than a line in a log.
 */
public class BrowserCorpusTest extends BrowserTestBase {

    private static final Path REPORT =
            Paths.get("build", "reports", "canoe-browser", "corpus-results.md");

    private static final List<Result> RESULTS = Collections.synchronizedList(new ArrayList<>());

    static List<BrowserEngine> engines() {
        return engineArgumentsOrSkipMarker();
    }

    static Stream<Arguments> browserInvocations() {
        List<BrowserEngine> engines = engineArgumentsOrSkipMarker();
        List<XssCase.Invocation> invocations = CanoeCorpus.browserInvocations();
        List<Arguments> arguments = new ArrayList<>(engines.size() * invocations.size());
        for (BrowserEngine engine : engines) {
            for (XssCase.Invocation invocation : invocations) {
                arguments.add(Arguments.of(engine, invocation));
            }
        }
        return arguments.stream();
    }

    /**
     * The subset assertion the plan states: a {@code KNOWN_VULNERABLE} pairing trips a detector, a
     * {@code SAFE} one trips none.
     */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("browserInvocations")
    public void theBrowserAgreesWithTheLedger(BrowserEngine engine, XssCase.Invocation invocation) {
        CanoeTestSupport.RenderResult rendered =
                VerdictEvaluator.render(invocation.testCase(), invocation.payload().value());
        assertFalse(rendered.isError(),
                "a browser-relevant case must render: " + rendered.errorMessage());

        boolean expectHit = invocation.verdict() == Verdict.KNOWN_VULNERABLE
                && invocation.isBrowserObservable();

        BrowserVerdict verdict = runCase(engine, invocation.toString(), rendered.output(),
                fullInteraction());
        RESULTS.add(new Result(engine, invocation, expectHit, verdict));

        if (expectHit) {
            assertTrue(verdict.exploited(),
                    "the ledger says " + invocation.verdict() + " and the corpus says a browser"
                            + " will act on it, but no detector fired.\n"
                            + "  template : " + invocation.testCase().template() + "\n"
                            + "  payload  : " + CanoeTestSupport.quote(invocation.payload().value())
                            + "\n  rendered : " + CanoeTestSupport.quote(rendered.output()) + "\n"
                            + verdict.describe());
        } else {
            assertFalse(verdict.exploited(),
                    "the ledger says " + invocation.verdict()
                            + (invocation.isBrowserObservable() ? "" : " (not browser-observable)")
                            + ", but " + verdict.firedDetectors() + " fired.\n"
                            + "  template : " + invocation.testCase().template() + "\n"
                            + "  payload  : " + CanoeTestSupport.quote(invocation.payload().value())
                            + "\n  rendered : " + CanoeTestSupport.quote(rendered.output()) + "\n"
                            + verdict.describe());
        }
    }

    /**
     * The tier's own coverage figure, pinned so it cannot quietly shrink.
     *
     * <p>A browser tier that loads nothing passes. This states how much it loads and how the load
     * splits between rows that must fire and rows that must not — the second number being the one
     * that makes a green run mean "the detectors stayed quiet when they should" rather than
     * "nothing interesting was served".
     *
     * <p>The figures move whenever a remediation task re-verdicts the ledger, and they are meant to:
     * the load shrinks because a case that stops being {@code KNOWN_VULNERABLE} contributes one
     * control instead of one page per payload. R2 (delete {@code detectAttributePrefix()}'s reset)
     * took it from 128/45/59/69 to the numbers below, by moving 40 invocations from
     * {@code KNOWN_VULNERABLE} to {@code SUPPRESSED_BY_DESIGN} and 2 to {@code SAFE} across the
     * eleven {@code css.*} cases, the two F17 {@code prefix.*} handlers and
     * {@code prefix.vbscript-not-in-the-table} — and by removing the not-browser-observable flags
     * those rows carried, since the flag only means anything on a row claiming a live vector.
     */
    @Test
    public void theBrowserRelevantSubsetIsTheSizeTheCorpusClaims() {
        List<XssCase.Invocation> invocations = CanoeCorpus.browserInvocations();
        long mustFire = invocations.stream()
                .filter(i -> i.verdict() == Verdict.KNOWN_VULNERABLE && i.isBrowserObservable())
                .count();
        long unobservable = invocations.stream()
                .filter(i -> !i.isBrowserObservable())
                .count();
        long quiet = invocations.size() - mustFire;

        assertEquals(114, invocations.size(), "browser-relevant invocation count");
        assertEquals(30, unobservable, "invocations flagged as not browser-observable");
        assertEquals(53, mustFire, "invocations that must trip a detector");
        assertEquals(61, quiet, "invocations that must trip none");
    }

    @AfterAll
    static void writeResults() {
        if (RESULTS.isEmpty()) {
            return;
        }
        Map<BrowserEngine, List<Result>> byEngine = RESULTS.stream().collect(Collectors.groupingBy(
                r -> r.engine, LinkedHashMap::new, Collectors.toList()));

        StringBuilder sb = new StringBuilder();
        sb.append("# Canoe browser tier — corpus results\n\n");
        sb.append("Engines that ran: ").append(enginesThatRan()).append("\n\n");
        for (BrowserEngine engine : BrowserEngine.values()) {
            if (!enginesThatRan().contains(engine)) {
                sb.append("- **").append(engine).append("** did not run: ")
                        .append(unavailabilityOf(engine)).append('\n');
            }
        }
        sb.append('\n');

        for (Map.Entry<BrowserEngine, List<Result>> entry : byEngine.entrySet()) {
            sb.append("## ").append(entry.getKey()).append("\n\n");
            sb.append("| case / payload | ledger | observable | expected | detectors |\n");
            sb.append("|---|---|---|---|---|\n");
            List<Result> results = new ArrayList<>(entry.getValue());
            results.sort(Comparator.comparing(r -> r.invocation.toString()));
            for (Result result : results) {
                sb.append("| `").append(result.invocation).append("` | ")
                        .append(result.invocation.verdict()).append(" | ")
                        .append(result.invocation.isBrowserObservable() ? "yes" : "no").append(" | ")
                        .append(result.expectHit ? "fire" : "silent").append(" | ")
                        .append(result.verdict.firedDetectors().isEmpty()
                                ? "-" : result.verdict.firedDetectors().toString().replace('|', '/'))
                        .append(" |\n");
            }
            sb.append('\n');
        }

        // The machine-readable twin, for MatrixReportTest's browser column (T33). The Markdown
        // above is for people; this is so the Velocity tier's generated matrix can say what a
        // browser actually did without anybody parsing a table.
        StringBuilder csv = new StringBuilder();
        csv.append("engine,invocation,detectors,expected\n");
        List<Result> sorted = new ArrayList<>(RESULTS);
        sorted.sort(Comparator.comparing((Result r) -> r.engine.name())
                .thenComparing(r -> r.invocation.toString()));
        for (Result result : sorted) {
            csv.append(quoteCsv(result.engine.name())).append(',')
                    .append(quoteCsv(result.invocation.toString())).append(',')
                    .append(quoteCsv(result.verdict.firedDetectors().isEmpty()
                            ? "-" : String.join("+", result.verdict.firedDetectors().stream()
                            .map(Object::toString).collect(Collectors.toList()))))
                    .append(',')
                    .append(quoteCsv(result.expectHit ? "fire" : "silent"))
                    .append('\n');
        }

        try {
            Files.createDirectories(REPORT.getParent());
            Files.write(REPORT, sb.toString().getBytes(StandardCharsets.UTF_8));
            Files.write(REPORT.resolveSibling("corpus-results.csv"),
                    csv.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String quoteCsv(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static final class Result {

        final BrowserEngine engine;
        final XssCase.Invocation invocation;
        final boolean expectHit;
        final BrowserVerdict verdict;

        Result(BrowserEngine engine, XssCase.Invocation invocation, boolean expectHit,
               BrowserVerdict verdict) {
            this.engine = engine;
            this.invocation = invocation;
            this.expectHit = expectHit;
            this.verdict = verdict;
        }
    }
}
