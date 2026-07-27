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
import static org.junit.jupiter.api.Assumptions.abort;

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
 *
 * <p><strong>R28 ran all three engines and found no divergence — and neither of the two vectors
 * &sect;5.2 named could be exercised.</strong> R6 suppresses both, so {@code markup.srcdoc},
 * {@code markup.srcdoc-whole-value} and {@code url.xlink-href} &times; {@code JS_URL/plain} render
 * an empty attribute and are silent in every engine for a reason that has nothing to do with the
 * engines. Read the headline accordingly: it is agreement among the rows that still emit something.
 * What is genuinely measured on those sinks is narrower — {@code url.xlink-href}'s three off-origin
 * rows fire identically everywhere, and {@code SinkSpecificBrowserTest} confirms per engine that a
 * suppressed {@code srcdoc} still leaves a same-origin iframe with nothing in it. If either
 * attribute is ever re-opened, &sect;5.2's question is open with it.
 */
public class BrowserCorpusTest extends BrowserTestBase {

    private static final Path REPORT =
            Paths.get("build", "reports", "canoe-browser", "corpus-results.md");

    private static final List<Result> RESULTS = Collections.synchronizedList(new ArrayList<>());

    private static final String FIREFOX_INSECURE_FORM_SUBMISSION =
            "Playwright's Firefox build wedges the page when a form whose action is an OFF-LOOPBACK"
                    + " http: URL is submitted, and emits no request, requestfailed or"
                    + " framenavigated event for the submission - so there is no observation to"
                    + " compare with the ledger in either direction. Measured in R28 against a real"
                    + " page.click on the submit button as well as scripted activation, and against"
                    + " https: to the same host (fine), a same-origin http: action (fine), a"
                    + " cross-origin LOOPBACK http: action on another port (fine - which is why the"
                    + " trigger is recorded as off-loopback rather than merely cross-origin, and it"
                    + " holds whether that port answers, refuses or accepts and never replies) and"
                    + " location.href to the identical off-loopback http: URL (fine). Not DNS:"
                    + " http://example.com, which does resolve here, wedges too. Not Firefox's"
                    + " HTTPS-First upgrade: dom.security.https_first, https_first_schemeless,"
                    + " https_first_pbm and https_only_mode all off changes nothing. Not this"
                    + " harness: reproduced against bare Playwright with no route interception, no"
                    + " init script and no detectors wired, while Chromium and WebKit submit the"
                    + " same markup without incident. The same sink is confirmed on Firefox by this"
                    + " case's ABSOLUTE_OFFSITE/https and /uppercase-scheme rows, which fire.";

    /**
     * The (engine, row) pairs a browser engine cannot be asked about here, each with its cause.
     *
     * <p><strong>This is not a way to make a red row green.</strong> A row goes in only when the
     * engine produces <em>no observation at all</em> — not a different one — so that there is
     * nothing to compare against the ledger in either direction. Every entry is measured, not
     * assumed, and {@link #everyEngineLimitationIsNarrowAndAccountedFor} pins the shape of the
     * table so it cannot become a place to put inconvenient results.
     *
     * <p>R28 found exactly one such limitation, in Playwright's Firefox build. Submitting a form
     * whose {@code action} is an <em>off-loopback {@code http:}</em> URL wedges the page: the
     * submitting {@code page.evaluate} never returns, and Firefox emits no {@code request},
     * {@code requestfailed} or {@code framenavigated} event for the submission — measured with a
     * real {@code page.click} on the submit button as well as with scripted activation, so it is
     * the submission and not the harness's way of triggering it, and reproduced against bare
     * Playwright with none of this class's detectors wired, so it is not this harness at all.
     *
     * <p><strong>Off-loopback, not merely cross-origin</strong>, and the distinction is measured: a
     * form action pointing at a second loopback server on another port is cross-origin and submits
     * without incident, whether that port answers, refuses the connection or accepts it and never
     * replies. {@code https:} to the same off-loopback host is fine, {@code location.href} to the
     * identical URL is fine, and Chromium and WebKit take the same markup without incident. The two
     * rows below are the only browser-relevant rows whose form action resolves to an off-loopback
     * {@code http:} URL, and they resolve to one only because the sentinel server is
     * {@code http://127.0.0.1}: the payload is {@code //attacker.invalid/x.js}, so it inherits the
     * page's scheme. Each case's other two must-fire rows are {@code https:} and are unaffected.
     *
     * <p>Nothing about F6 goes unmeasured on Firefox as a result. Both cases carry
     * {@code ABSOLUTE_OFFSITE/https} and {@code ABSOLUTE_OFFSITE/uppercase-scheme} rows against the
     * same sink, both are {@code ACCEPTED_RESIDUAL}/{@code FORM_RETARGET}, and both fire on Firefox
     * — an {@code https:} form action to the same unreachable host is submitted, observed and
     * aborted at the route exactly as it is in Chromium and WebKit. What the two rows below would
     * have added on Firefox is that the scheme-relative spelling reaches the same sink, and that is
     * what is lost.
     */
    static final Map<BrowserEngine, Map<String, String>> ENGINE_LIMITATIONS = Map.of(
            BrowserEngine.FIREFOX, Map.of(
                    "url.action / PROTOCOL_RELATIVE/slashes", FIREFOX_INSECURE_FORM_SUBMISSION,
                    "url.formaction / PROTOCOL_RELATIVE/slashes",
                    FIREFOX_INSECURE_FORM_SUBMISSION));

    private static final List<String> LIMITATIONS_APPLIED =
            Collections.synchronizedList(new ArrayList<>());

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
        String limitation = limitationFor(engine, invocation);
        if (limitation != null) {
            LIMITATIONS_APPLIED.add(engine + " / " + invocation);
            abort(engine + " cannot be asked about '" + invocation + "': " + limitation);
        }

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
     * <p><strong>R26's figures were verified by R28's first run and they were right</strong>:
     * 65/19/46/0, measured rather than recomputed, on Chromium, Firefox and WebKit. That is what
     * R26 left unverified — {@code browserTest} could not complete in this environment until R28
     * bounded it — and nothing had read the narrower predicate.
     *
     * <p>R28 then moves it to <strong>67/19/48/0</strong>, and only the two counts that describe
     * <em>silence</em> move. The task closed the coverage gap Appendix A &sect;A.3 had recorded
     * since T15 by adding the SVG animation handlers {@code onbegin} and {@code onrepeat}; both are
     * {@code SUPPRESSED_BY_DESIGN} like every other handler since R4, so each contributes one safe
     * control and nothing to the must-fire count. They are loaded rather than left to the Velocity
     * tier because SMIL starts on load: an SVG animation handler fires with no user interaction in
     * all three engines, which makes these two of the cheapest suppressed handlers in the corpus to
     * demonstrate and two of the few where "no detector fired" means the sink was reached and
     * refused rather than never reached.
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

        assertEquals(67, invocations.size(), "browser-relevant invocation count");
        assertEquals(0, unobservable, "invocations flagged as not browser-observable");
        assertEquals(19, mustFire, "invocations that must trip a detector");
        assertEquals(48, quiet, "invocations that must trip none");
    }

    private static String limitationFor(BrowserEngine engine, XssCase.Invocation invocation) {
        return ENGINE_LIMITATIONS.getOrDefault(engine, Map.of()).get(invocation.toString());
    }

    /**
     * The guard on {@link #ENGINE_LIMITATIONS}: it stays small, it stays explained, and every entry
     * still names a row this tier actually loads.
     *
     * <p>An escape hatch with no guard is a place to put failures. Four things are asserted. The
     * count is pinned, so widening the table is a deliberate edit to this number rather than a
     * quiet addition. Every entry names a real browser-relevant invocation, so a corpus rename
     * turns into a failure here instead of into a silently ineffective exemption — which would
     * otherwise read as "the row runs everywhere" while the row it was meant to cover had gone. And
     * every reason is long enough to be a reason: the cause, and what was measured to establish it.
     *
     * <p>The fourth is the one that keeps the table honest about <em>consequences</em> rather than
     * about its own shape. <strong>An excused row may not be the only thing measuring its case's
     * sink on that engine.</strong> The whole argument for excusing these two is that the sink is
     * reached anyway — {@code url.action} and {@code url.formaction} each carry two {@code https:}
     * must-fire rows against the same {@code FORM_RETARGET} sink, and those do fire on Firefox — so
     * that argument is asserted here instead of being left in prose that nothing checks. If the
     * sibling rows were ever dropped, re-verdicted or excused as well, the exemption would quietly
     * become "Firefox does not measure this sink at all", which is the thing it claims not to be.
     */
    @Test
    public void everyEngineLimitationIsNarrowAndAccountedFor() {
        List<XssCase.Invocation> invocations = CanoeCorpus.browserInvocations();
        List<String> known = invocations.stream()
                .map(Object::toString)
                .collect(Collectors.toList());

        long entries = ENGINE_LIMITATIONS.values().stream().mapToLong(Map::size).sum();
        assertEquals(2, entries,
                "the number of (engine, row) pairs excused from the browser tier. Raising it means"
                        + " another engine cannot be measured somewhere; say why in the entry.");

        ENGINE_LIMITATIONS.forEach((engine, rows) -> rows.forEach((row, reason) -> {
            assertTrue(known.contains(row),
                    "the limitation for " + engine + " names '" + row + "', which is not a"
                            + " browser-relevant invocation. Either the corpus renamed it - in which"
                            + " case the exemption has been silently inactive - or it was never"
                            + " loaded here.");
            assertTrue(reason.length() > 200,
                    engine + " / " + row + " is excused with a reason too short to be one: "
                            + reason);

            XssCase.Invocation excused = invocations.stream()
                    .filter(i -> i.toString().equals(row))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("unreachable: " + row));
            List<String> stillMeasured = invocations.stream()
                    .filter(i -> i.testCase().id().equals(excused.testCase().id()))
                    .filter(i -> i.verdict().reachesSinkLive() && i.isBrowserObservable())
                    .filter(i -> limitationFor(engine, i) == null)
                    .map(Object::toString)
                    .collect(Collectors.toList());
            assertTrue(!stillMeasured.isEmpty(),
                    "excusing '" + row + "' on " + engine + " leaves that case with no row this"
                            + " engine is asked to fire, so " + engine + " no longer measures the"
                            + " sink at all. An exemption is only narrow while a sibling row still"
                            + " reaches the same sink on the same engine; either restore one or"
                            + " stop calling this a narrow limitation.");
        }));
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
        sb.append("Engines that ran:\n\n");
        for (BrowserEngine engine : enginesThatRan()) {
            sb.append("- **").append(engine).append("** ").append(versionOf(engine)).append('\n');
        }
        sb.append('\n');
        for (BrowserEngine engine : BrowserEngine.values()) {
            if (!enginesThatRan().contains(engine)) {
                sb.append("- **").append(engine).append("** did not run: ")
                        .append(unavailabilityOf(engine)).append('\n');
            }
        }
        sb.append('\n');

        if (!LIMITATIONS_APPLIED.isEmpty()) {
            sb.append("## Rows an engine could not be asked about\n\n");
            List<String> applied = new ArrayList<>(LIMITATIONS_APPLIED);
            Collections.sort(applied);
            for (String entry : applied) {
                sb.append("- `").append(entry).append("`\n");
            }
            sb.append('\n').append(FIREFOX_INSECURE_FORM_SUBMISSION).append("\n\n");
        }

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
