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
 * <p>A pairing is expected to trip a detector exactly when its verdict says the data reaches the
 * sink live — {@link Verdict#reachesSinkLive()}, which is {@link Verdict#KNOWN_VULNERABLE} or
 * {@link Verdict#ACCEPTED_RESIDUAL} — <em>and</em> {@link XssCase.Invocation#isBrowserObservable()}.
 * Everything else — {@code SAFE}, the two suppression verdicts, and the live-but-unobservable rows —
 * is expected to be silent.
 *
 * <p><strong>Reading the wider predicate is not a relaxation; it is the whole of R26 as far as this
 * tier is concerned.</strong> Every row this tier expects to fire is an F6 residual now: an
 * off-origin {@code <a href>}, {@code <img src>}, {@code srcset}, {@code xlink:href} or form action
 * that {@code url()} passes through and R9 deliberately does not filter. What fires for them is the
 * sentinel-origin detector or the off-origin-navigation one, exactly as before the verdict was
 * renamed — the browser cannot tell the difference either, which is the point. Had this test kept
 * asking {@code == KNOWN_VULNERABLE}, all nineteen would have flipped to "expected silent" while
 * still firing, and the tier would have gone red for a paperwork reason.
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
 * <p><strong>No row carries the flag after Phase A.</strong> Every one of the eighteen that did has
 * been re-verdicted to a suppression or to SAFE by R2 through R7, and the flag is only permitted on
 * a {@code KNOWN_VULNERABLE} row. The condition above is therefore equivalent to "is it
 * {@code KNOWN_VULNERABLE}" today, and it is kept in that form rather than simplified: the reason
 * the flag existed has not gone anywhere, and the next row that needs it will need it for exactly
 * the same reason.
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

        boolean expectHit = invocation.verdict().reachesSinkLive()
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
     * took it from 128/45/59/69 to 114/30/53/61, by moving 40 invocations from
     * {@code KNOWN_VULNERABLE} to {@code SUPPRESSED_BY_DESIGN} and 2 to {@code SAFE} across the
     * eleven {@code css.*} cases, the two F17 {@code prefix.*} handlers and
     * {@code prefix.vbscript-not-in-the-table} — and by removing the not-browser-observable flags
     * those rows carried, since the flag only means anything on a row claiming a live vector.
     *
     * <p>R3 (compare value prefixes by length rather than by fixed buffer indices) took it to
     * 110/25/52/58. Two cases moved to {@code SUPPRESSED_BY_DESIGN} — {@code
     * residue.js-url-armed-buffer}, whose six payload invocations across it and {@code
     * residue.data-url-armed-buffer} become two controls — and the five not-browser-observable flags
     * they carried went with them. Only one of the six was ever observable in a browser: the
     * {@code data:} row's four payloads are markup in a background-image attribute, which no engine
     * renders, and the {@code javascript:} row's double-quote payload cannot close a single-quoted
     * literal. So the tier lost four pages and one must-fire row.
     *
     * <p>R4 (replace the {@code on*} table with a prefix rule) took it to 103/18/45/58, and it was
     * the largest move any single task made until Phase A finished: F1, F2 and F19 together were 98
     * of the ledger's remaining 233 {@code KNOWN_VULNERABLE} invocations. Eleven browser-relevant
     * handler cases are involved — {@code handler.onsubmit}, {@code .onselect}, {@code .onfocus},
     * {@code .onreadystatechange}, {@code .ontoggle}, {@code .onmouseenter},
     * {@code .onanimationstart}, {@code .onwebkitanimationstart}, {@code .onvisibilitychange},
     * {@code .onshow} and, unchanged, {@code .onmouseover}. Each drops from one page per payload to
     * one control, which is the seven pages the total loses, and the seven not-browser-observable
     * flags they carried go with them because the corpus only permits the flag on a row claiming a
     * live vector. The must-fire count falls by exactly the handler rows that were live in a
     * browser; the quiet count is unchanged at 58, because every page those cases lose is replaced
     * by the control the case still contributes.
     *
     * <p>R5, R6 and R7 (fail closed on unknown names, extend the URL-bearing set, resolve the
     * {@code content}/{@code data} pair) take it to the numbers below, and two of the three figures
     * do something no previous task made them do.
     *
     * <ul>
     *   <li>The <strong>unobservable count reaches zero</strong>. It was 18 and every one of those
     *       rows has been re-verdicted to a suppression: {@code url.action}, {@code url.formaction}
     *       and {@code url.xlink-href}'s dead-scheme rows are suppressions under {@code url()} now
     *       (R12 rejects a scheme off the {http, https, mailto} allowlist to the empty string),
     *       {@code url.srcset}'s six are the same, and {@code policy.nonce}'s three went with F20.
     *       The corpus only permits the flag on a {@code KNOWN_VULNERABLE} row, so the axis is empty
     *       — see {@code CanoeCorpusTest.browserObservabilityIsOnlyClaimedWhereItChangesAnExpecta}
     *       {@code tion}, which now guards the machinery instead of counting users. Every row this
     *       tier still expects to fire is one a browser really acts on.
     *   <li>The <strong>must-fire count is F6 and nothing else</strong>. After R11 and R12 it is 28
     *       rows rather than 19, all of them an off-origin or protocol-relative URL reaching a sink
     *       {@code url()} passes through byte for byte — the nine extra are the uppercase-scheme
     *       off-origin rows R12 stopped neutralising by accident (an uppercase scheme is normalised
     *       now, so {@code HTTPS://attacker} is a real off-origin URL). That is the whole of what is
     *       left in the ledger, and R9 still owns it.
     *   <li>The <strong>total is 72</strong>: 63 after R7 plus those nine uppercase KNOWN_VULNERABLE
     *       rows, which each earn a page load now that they are a live vector rather than a control.
     * </ul>
     *
     * <p>R9 (reject off-origin and protocol-relative URLs in resource-loading sinks) takes it to
     * <strong>62/18/44/0</strong>. The three browser-relevant resource sinks re-verdict every
     * off-origin row to {@code SUPPRESSED_BY_DESIGN}: {@code url.script-src-prefix} and
     * {@code url.iframe-src} lose three must-fire rows each, and {@code url.base-href} loses four
     * (the {@code BASE_HIJACK} host plus the three off-origin URLs), so the must-fire count falls
     * 28 &rarr; 18 and the total falls 72 &rarr; 62 by exactly those ten pages. Each case still
     * contributes its one safe control, so the <strong>quiet count is unchanged at 44</strong>. The
     * eighteen rows that remain must-fire are all F6 on {@code <a href>}, {@code <img src>}, a form
     * action or another open-redirect/referrer surface that R9 scopes out by design — the residual
     * F6 that R26 tracks and that this component does not treat as code execution.
     * </p>
     *
     * <p>R19 (route {@code TAG_ATTR_VALUE_BEFORE}, F11) takes it to <strong>65/19/46/0</strong>, and
     * it is the only Phase D task that touches this tier. Two cases join it, for three pages. The
     * first, {@code unquoted.immediately-after-equals}, is {@code <a href=$data>} — the shape F11
     * rendered empty and R19 routes — and it earns two pages, its {@code PROTOCOL_RELATIVE/slashes}
     * row (must-fire, F6, exactly as its quoted twin {@code url.href-full} is) plus one safe control.
     * The second, {@code unquoted.plain-text-after-equals}, is {@code <span title=$data>} with the
     * {@code ATTR_BREAKOUT} family and earns the one control every safe case earns. That is where the
     * safety argument for R19 stops being an argument: the claim is that {@code html()} and
     * {@code url()} cannot emit a character that ends an unquoted attribute value, and here a real
     * engine parses the result rather than jsoup.
     *
     * <p>R26 (the sixth verdict, {@link Verdict#ACCEPTED_RESIDUAL}) leaves all four figures
     * <strong>unchanged at 65/19/46/0</strong>, and that is a result rather than a coincidence.
     * Every one of the 68 rows it re-verdicted was {@code KNOWN_VULNERABLE} and is a residual now;
     * nineteen of them sit on browser-relevant cases, and both the relevance rule
     * ({@code XssCase.Invocation.isBrowserRelevant}) and the expectation above read
     * {@link Verdict#reachesSinkLive()}, so the same pages load and the same detectors are expected
     * to fire. If any of these four numbers moves with R26, something read the narrower predicate.
     *
     * <p><strong>Unverified.</strong> R26 could not run this tier: {@code browserTest} hangs in this
     * environment on {@code FIREFOX url.action / JS_URL/plain}, the known interaction R28 owns. The
     * four figures above were recomputed from the corpus rather than from a run, and the changes to
     * this file are compile-checked only. R28 is where they are confirmed.
     */
    @Test
    public void theBrowserRelevantSubsetIsTheSizeTheCorpusClaims() {
        List<XssCase.Invocation> invocations = CanoeCorpus.browserInvocations();
        long mustFire = invocations.stream()
                .filter(i -> i.verdict().reachesSinkLive() && i.isBrowserObservable())
                .count();
        long unobservable = invocations.stream()
                .filter(i -> !i.isBrowserObservable())
                .count();
        long quiet = invocations.size() - mustFire;

        assertEquals(65, invocations.size(), "browser-relevant invocation count");
        assertEquals(0, unobservable, "invocations flagged as not browser-observable");
        assertEquals(19, mustFire, "invocations that must trip a detector");
        assertEquals(46, quiet, "invocations that must trip none");
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
