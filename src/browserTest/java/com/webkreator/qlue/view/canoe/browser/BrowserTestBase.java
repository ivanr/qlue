package com.webkreator.qlue.view.canoe.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Tracing;
import com.microsoft.playwright.options.WaitUntilState;
import com.webkreator.qlue.view.canoe.corpus.Payloads;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assumptions.abort;

/**
 * Playwright lifecycle and the five detectors of plan &sect;5.2 (T26).
 *
 * <h2>What may skip and what may not</h2>
 *
 * <p>Only the <em>launch</em> of a browser may skip. Once a browser is running, a failure is a
 * failure: a tier that turns every {@code PlaywrightException} into a skipped test can never fail,
 * and would report success for a browser tier that no longer works. That is the same rule
 * {@code BrowserSmokeTest} states, and it is the reason nothing below catches broadly.
 *
 * <p>The tier is written for Chromium, Firefox and WebKit. A missing engine is reported by name and
 * skipped; the engines that are present run. {@link #enginesThatRan()} names them, and every
 * parameterised test in this package carries the engine in its display name, so a green run says
 * which engines it is green in rather than implying all three.
 *
 * <h2>The five detectors</h2>
 *
 * <ol>
 *   <li><b>Script execution</b> — {@link Payloads#SENTINEL_FUNCTION} is exposed on the page. Every
 *       executable payload in the corpus calls it, so a call is direct evidence that attacker text
 *       reached a JavaScript parser <em>and ran</em>, which is the thing &sect;2.3 asks the browser
 *       tier to assert instead of a string comparison. It is exposed as a <em>binding</em> rather
 *       than a plain function so that the calling frame is recorded: T29's {@code srcdoc} case has
 *       to prove the script ran <em>inside the iframe</em> and same-origin, and a plain
 *       {@code exposeFunction} cannot tell one frame from another. An init script additionally
 *       records every call in a {@code window} array, so the detector is witnessed from both sides.
 *   <li><b>Dialogs</b> — recorded and dismissed. Dismissing matters: an undismissed dialog blocks
 *       the page and every later detector on it.
 *   <li><b>Sentinel origin</b> — {@code attacker.invalid} is an RFC 2606 reserved TLD that can
 *       never resolve, so a detector miss degrades to a connection failure rather than a real
 *       outbound request. Requests are recorded from {@code onRequest} and aborted by a
 *       {@code page.route} interceptor; recording from both means a mistake in the route glob shows
 *       up as an unaborted request rather than as a silently missed detection.
 *   <li><b>Navigation</b> — frame navigations, popups, and top-level document requests, judged
 *       against the sentinel server's origin.
 *   <li><b>Console</b> — errors and uncaught page exceptions. Recorded, but deliberately
 *       <em>not</em> part of {@link BrowserVerdict#exploited()}; see that class.
 * </ol>
 *
 * <h2>Why the page defines {@code v}, {@code h}, {@code f} and {@code go}</h2>
 *
 * <p>Corpus handler templates call into page script — {@code <form onsubmit="v('$data')">}. In a
 * real page {@code v} exists. Here it would not, and a {@code ReferenceError} on the first
 * statement aborts the whole handler <em>before</em> the injected second statement runs, so every
 * handler case would report a miss for a reason that has nothing to do with Canoe. The init script
 * defines them as no-ops. This makes the page more realistic, not more permissive: the sentinel is
 * still only reachable by attacker-controlled text.
 */
@Tag("browser")
public abstract class BrowserTestBase {

    /** Where a failing case leaves its trace and screenshot. */
    private static final Path ARTIFACTS = Paths.get("build", "reports", "canoe-browser");

    /**
     * How long to keep pumping the browser's event loop after the interaction phase.
     *
     * <p>The floor is unconditional, so a case that is expected to stay quiet is given the same
     * minimum as one expected to fire; the ceiling is only reached by cases that never fire. Making
     * the wait depend on what the ledger predicts would be an oracle that helps the answer it
     * expects.
     */
    private static final long SETTLE_FLOOR_MILLIS = 200;

    private static final long SETTLE_CEILING_MILLIS = 1200;

    /**
     * Installed into every page before any document script runs.
     *
     * <p>It wraps the exposed binding rather than replacing it, and records whether the binding was
     * already present when it ran. {@code DetectorSelfTest} asserts that it was, which is what pins
     * the ordering assumption — Playwright installs an exposed binding ahead of init scripts
     * registered after the {@code exposeBinding} call — as a tested fact rather than a hope.
     */
    private static final String SENTINEL_INIT_SCRIPT =
            "(function(){\n"
                    + "  var w = window;\n"
                    + "  if (w.__canoeInitInstalled) { return; }\n"
                    + "  w.__canoeInitInstalled = true;\n"
                    + "  w.__canoeSentinelCalls = [];\n"
                    + "  var bound = w." + Payloads.SENTINEL_FUNCTION + ";\n"
                    + "  w.__canoeBindingPresentAtInit = (typeof bound === 'function');\n"
                    + "  w." + Payloads.SENTINEL_FUNCTION + " = function () {\n"
                    + "    var args = Array.prototype.slice.call(arguments).map(String).join(',');\n"
                    + "    try { w.__canoeSentinelCalls.push(args); } catch (e) {}\n"
                    + "    if (typeof bound === 'function') {\n"
                    + "      try { return bound.apply(null, arguments); } catch (e) {}\n"
                    + "    }\n"
                    + "  };\n"
                    + "  var stub = function () { return true; };\n"
                    + "  ['v', 'h', 'f', 'go'].forEach(function (n) {\n"
                    + "    if (typeof w[n] === 'undefined') { w[n] = stub; }\n"
                    + "  });\n"
                    + "  if (typeof w.$ === 'undefined') { w.$ = { ajax: stub }; }\n"
                    + "})();";

    /**
     * Dispatches one synthetic event per {@code on*} content attribute present in the document.
     *
     * <p>This is how an event handler case becomes observable at all: nothing in a headless run
     * focuses an input or toggles a {@code <details>}. It is deliberately driven off the attribute
     * <em>names in the markup</em> rather than off a list this file maintains, so a corpus case that
     * adds a handler name is triggered without anyone remembering to come here — and so that a name
     * no engine registers as a content attribute ({@code onreadystatechange} on an {@code <img>},
     * {@code onvisibilitychange} on a {@code <div>}) is dispatched and correctly finds no listener,
     * which is exactly the miss those rows predict.
     */
    private static final String TRIGGER_EVENTS_SCRIPT =
            "(function(){\n"
                    + "  var fired = [];\n"
                    + "  var els = Array.prototype.slice.call(document.querySelectorAll('*'));\n"
                    + "  for (var i = 0; i < els.length; i++) {\n"
                    + "    var el = els[i];\n"
                    + "    var attrs = el.attributes;\n"
                    + "    if (!attrs) { continue; }\n"
                    + "    for (var j = 0; j < attrs.length; j++) {\n"
                    + "      var n = String(attrs[j].name).toLowerCase();\n"
                    + "      if (n.length > 2 && n.substring(0, 2) === 'on') {\n"
                    + "        try {\n"
                    + "          el.dispatchEvent(new Event(n.substring(2),\n"
                    + "              { bubbles: true, cancelable: true }));\n"
                    + "          fired.push(n);\n"
                    + "        } catch (e) {}\n"
                    + "      }\n"
                    + "    }\n"
                    + "  }\n"
                    + "  return fired;\n"
                    + "})();";

    /**
     * Activates anything a user could click or submit.
     *
     * <p>Order matters and is not arbitrary. Anchors first, because a {@code javascript:} href is
     * the cheapest signal and clicking it navigates nowhere. Submit controls next, so that a
     * {@code formaction} wins over the form's own {@code action}. Forms last, and only those with
     * no submit control of their own, so the {@code formaction} navigation just queued is not
     * immediately replaced by a same-origin one.
     *
     * <p>SVG anchors take the {@code MouseEvent} path: {@code click()} is an {@code HTMLElement}
     * method and {@code SVGAElement} does not always carry it, while a dispatched click still runs
     * the element's activation behaviour.
     */
    private static final String TRIGGER_ACTIVATION_SCRIPT =
            "(function(){\n"
                    + "  var acted = [];\n"
                    + "  function activate(el) {\n"
                    + "    try {\n"
                    + "      if (typeof el.click === 'function') { el.click(); }\n"
                    + "      else {\n"
                    + "        el.dispatchEvent(new MouseEvent('click',\n"
                    + "            { bubbles: true, cancelable: true, view: window }));\n"
                    + "      }\n"
                    + "      acted.push(el.tagName);\n"
                    + "    } catch (e) {}\n"
                    + "  }\n"
                    + "  var anchors = document.querySelectorAll('a, area');\n"
                    + "  for (var i = 0; i < anchors.length; i++) { activate(anchors[i]); }\n"
                    + "  var submits = document.querySelectorAll(\n"
                    + "      'button, input[type=submit], input[type=image]');\n"
                    + "  for (var j = 0; j < submits.length; j++) { activate(submits[j]); }\n"
                    + "  var forms = document.querySelectorAll('form');\n"
                    + "  for (var k = 0; k < forms.length; k++) {\n"
                    + "    var form = forms[k];\n"
                    + "    if (form.querySelector('button, input[type=submit], input[type=image]')) {\n"
                    + "      continue;\n"
                    + "    }\n"
                    + "    try {\n"
                    + "      if (typeof form.requestSubmit === 'function') { form.requestSubmit(); }\n"
                    + "      else { form.submit(); }\n"
                    + "      acted.push('FORM');\n"
                    + "    } catch (e) {\n"
                    + "      try { form.submit(); acted.push('FORM'); } catch (e2) {}\n"
                    + "    }\n"
                    + "  }\n"
                    + "  return acted;\n"
                    + "})();";

    /** The sentinel origin, per test class, as T25 specifies. */
    protected static SentinelServer server;

    @BeforeAll
    static void startSentinelServer() {
        server = SentinelServer.start();
    }

    @AfterAll
    static void stopSentinelServer() {
        if (server != null) {
            server.close();
            server = null;
        }
    }

    // ------------------------------------------------------------------
    // Engine availability
    // ------------------------------------------------------------------

    /**
     * The engines that launched in this environment, in declaration order.
     *
     * <p>Empty when Playwright itself is unavailable. Callers that parameterise over engines must
     * cope with that; see {@link #engineArgumentsOrSkipMarker()}.
     */
    public static List<BrowserEngine> enginesThatRan() {
        return PlaywrightFixture.get().available();
    }

    /** Why an engine is not available, for a skip message that names a cause. */
    public static String unavailabilityOf(BrowserEngine engine) {
        return PlaywrightFixture.get().failure(engine);
    }

    /**
     * The engine list a {@code @MethodSource} should use.
     *
     * <p>JUnit fails a {@code @ParameterizedTest} whose source is empty, which would turn "no
     * browser is installed" — the one thing the plan says must skip cleanly — into a hard failure.
     * So when nothing launched this returns a single null, and {@link #browserFor} turns that into
     * an abort carrying the reason each engine gave.
     */
    protected static List<BrowserEngine> engineArgumentsOrSkipMarker() {
        List<BrowserEngine> engines = enginesThatRan();
        return engines.isEmpty() ? Collections.singletonList(null) : engines;
    }

    /** The launched browser for an engine, or an abort naming why there is not one. */
    protected static Browser browserFor(BrowserEngine engine) {
        PlaywrightFixture fixture = PlaywrightFixture.get();
        if (engine == null) {
            return abort("No browser engine is available. " + fixture.summary()
                    + " Run ./gradlew playwrightInstall, or point PLAYWRIGHT_BROWSERS_PATH at a"
                    + " cache that has them.");
        }
        Browser browser = fixture.browser(engine);
        if (browser == null) {
            return abort(engine + " is not installed: " + fixture.failure(engine));
        }
        return browser;
    }

    // ------------------------------------------------------------------
    // Running a case
    // ------------------------------------------------------------------

    /** No interaction at all: whatever the document does on load is the whole story. */
    public static Consumer<Page> passiveLoad() {
        return page -> {
        };
    }

    /**
     * Dispatch every {@code on*} attribute's event, then click and submit everything clickable.
     */
    public static Consumer<Page> fullInteraction() {
        return page -> {
            evaluateQuietly(page, TRIGGER_EVENTS_SCRIPT);
            evaluateQuietly(page, TRIGGER_ACTIVATION_SCRIPT);
        };
    }

    /**
     * Serves {@code html}, loads it, runs {@code interaction}, waits for the detectors to settle,
     * and hands the page and the accumulated verdict to {@code assertions}.
     *
     * <p>The assertions run while the page is still open, which is what makes a trace and a
     * screenshot on failure possible: by the time a JUnit failure is visible to the runner the
     * context would otherwise be gone. On failure the trace and screenshot are written under
     * {@code build/reports/canoe-browser/} and named in the rethrown error.
     */
    protected void runCase(BrowserEngine engine, String label, String html,
                           Consumer<Page> interaction,
                           BiConsumer<Page, BrowserVerdict> assertions) {
        Browser browser = browserFor(engine);
        String url = server.publish(label, html);
        int logMark = server.log().size();

        BrowserContext context = browser.newContext();
        BrowserVerdict verdict = new BrowserVerdict(label, engine, url);
        boolean tracing = startTracing(context);
        try {
            Page page = context.newPage();
            wireDetectors(page, verdict);

            try {
                page.navigate(url, new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.LOAD)
                        .setTimeout(15_000));
            } catch (RuntimeException e) {
                // A navigation can be superseded by the document itself — a meta refresh that fires
                // before load, for instance. That is a result, not an error, and the detectors have
                // already recorded it.
                verdict.recordConsoleError("navigation: " + e.getMessage());
            }

            interaction.accept(page);
            readWindowSentinel(page, verdict);
            settle(page, verdict);
            readWindowSentinel(page, verdict);
            verdict.recordServerRequests(serverRequestsSince(logMark));

            try {
                assertions.accept(page, verdict);
            } catch (Throwable failure) {
                captureArtifacts(page, context, engine, label, tracing);
                throw failure;
            }
        } finally {
            context.close();
        }
    }

    /** The common shape: load, interact, and judge the verdict alone. */
    protected BrowserVerdict runCase(BrowserEngine engine, String label, String html,
                                     Consumer<Page> interaction) {
        BrowserVerdict[] holder = new BrowserVerdict[1];
        runCase(engine, label, html, interaction, (page, verdict) -> holder[0] = verdict);
        return holder[0];
    }

    // ------------------------------------------------------------------
    // Detector wiring
    // ------------------------------------------------------------------

    private void wireDetectors(Page page, BrowserVerdict verdict) {
        // 1. Script execution. exposeBinding rather than exposeFunction: identical from the page's
        // point of view, but it reports the calling frame, which T29 needs to say "inside the
        // iframe, same-origin" rather than merely "somewhere on the page".
        page.exposeBinding(Payloads.SENTINEL_FUNCTION, (source, args) -> {
            String argument = args.length == 0 ? "" : String.valueOf(args[0]);
            String frameUrl;
            try {
                frameUrl = source.frame().url();
            } catch (RuntimeException e) {
                frameUrl = "<unknown frame>";
            }
            verdict.recordScriptExecution(argument, frameUrl);
            return null;
        });
        page.addInitScript(SENTINEL_INIT_SCRIPT);

        // 2. Dialogs. Dismissed, because an open dialog blocks every later detector.
        page.onDialog(dialog -> {
            verdict.recordDialog(dialog.type(), dialog.message());
            dialog.dismiss();
        });

        // 3. The sentinel origin. Recorded from onRequest and aborted at the route, so that a
        // mistake in the glob shows up as an unaborted request rather than as a missed detection.
        page.onRequest(request -> {
            String requestUrl = request.url();
            if (isSentinelOrigin(requestUrl)) {
                verdict.recordSentinelRequest(requestUrl);
            }
        });
        page.route("**://" + Payloads.SENTINEL_HOST + "/**", route -> {
            verdict.recordAbortedSentinelRequest(route.request().url());
            route.abort();
        });

        // 4. Navigation.
        page.onFrameNavigated(frame -> {
            String frameUrl = frame.url();
            verdict.recordNavigation(frameUrl);
            if (isOffOrigin(frameUrl)) {
                verdict.recordOffOriginNavigation(frameUrl);
            }
        });
        page.onPopup(popup -> {
            verdict.recordPopup(popup.url());
            if (isOffOrigin(popup.url())) {
                verdict.recordOffOriginNavigation(popup.url());
            }
        });

        // 5. Console.
        page.onConsoleMessage(message -> {
            if ("error".equals(message.type())) {
                verdict.recordConsoleError(message.text());
            }
        });
        page.onPageError(verdict::recordPageError);
    }

    /**
     * Whether a request is a real HTTP request to the attacker origin.
     *
     * <p>The scheme test is load-bearing and was put here by a corpus row rather than by design.
     * {@code url.srcset} carries {@code view-source:https://attacker.invalid/x}; Chromium selects
     * that srcset candidate, emits a request event for it and then refuses to load it ("Not allowed
     * to load local resource"). A substring match on the host reported that as a hit, so a row the
     * corpus correctly predicts no engine will act on came back exploited. Nothing left the
     * browser, and a detector that counts a blocked internal-scheme request as an outbound one
     * over-reports in the one direction a security suite must not.
     */
    private static boolean isSentinelOrigin(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        int schemeEnd;
        if (lower.startsWith("http://")) {
            schemeEnd = "http:".length();
        } else if (lower.startsWith("https://")) {
            schemeEnd = "https:".length();
        } else {
            return false;
        }
        String rest = url.substring(schemeEnd + 2);
        int slash = rest.indexOf('/');
        String authority = slash < 0 ? rest : rest.substring(0, slash);
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            authority = authority.substring(at + 1);
        }
        int colon = authority.indexOf(':');
        if (colon >= 0) {
            authority = authority.substring(0, colon);
        }
        return authority.equalsIgnoreCase(Payloads.SENTINEL_HOST);
    }

    /**
     * Whether a committed document URL belongs to somewhere other than the sentinel server.
     *
     * <p>{@code about:blank} and {@code about:srcdoc} are excluded because every page has them and
     * they are not navigations anywhere. Chromium's {@code chrome-error://} and the equivalents in
     * the other engines are excluded because an <em>aborted</em> off-origin navigation lands there,
     * and that abort is the sentinel-origin detector's own doing — counting it here would report
     * the same event twice under two names.
     */
    private static boolean isOffOrigin(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        if (url.startsWith("about:") || url.startsWith("chrome-error:")
                || url.startsWith("javascript:")) {
            return false;
        }
        return !url.startsWith(server.origin());
    }

    // ------------------------------------------------------------------
    // Waiting
    // ------------------------------------------------------------------

    /**
     * Pumps the browser's event loop until a detector fires or the ceiling is reached.
     *
     * <p>{@code page.waitForTimeout} rather than {@code Thread.sleep}: Playwright Java delivers
     * events on the thread that calls into it, so a sleeping test receives nothing and every
     * detector reads empty.
     */
    private void settle(Page page, BrowserVerdict verdict) {
        long start = System.nanoTime();
        while (true) {
            long elapsed = (System.nanoTime() - start) / 1_000_000L;
            if (elapsed >= SETTLE_CEILING_MILLIS) {
                return;
            }
            if (elapsed >= SETTLE_FLOOR_MILLIS && verdict.exploited()) {
                return;
            }
            try {
                page.waitForTimeout(25);
            } catch (RuntimeException e) {
                return;
            }
        }
    }

    private static void readWindowSentinel(Page page, BrowserVerdict verdict) {
        Object calls = evaluateQuietly(page,
                "(function(){ return (window.__canoeSentinelCalls || []).splice(0); })();");
        if (calls instanceof List) {
            List<String> values = new ArrayList<>();
            for (Object call : (List<?>) calls) {
                values.add(String.valueOf(call));
            }
            verdict.recordWindowSentinel(values);
        }
    }

    /**
     * Evaluates in the page, tolerating the two failures that are part of normal operation here: a
     * navigation destroying the execution context, and a page closed by its own script. Anything
     * the script itself throws is caught inside the script, so this cannot hide a broken detector.
     */
    private static Object evaluateQuietly(Page page, String script) {
        try {
            return page.evaluate(script);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private List<String> serverRequestsSince(int mark) {
        List<SentinelServer.LoggedRequest> log = server.log();
        return log.subList(Math.min(mark, log.size()), log.size()).stream()
                .map(SentinelServer.LoggedRequest::toString)
                .collect(Collectors.toList());
    }

    // ------------------------------------------------------------------
    // Artifacts
    // ------------------------------------------------------------------

    private static boolean startTracing(BrowserContext context) {
        try {
            context.tracing().start(new Tracing.StartOptions()
                    .setScreenshots(true)
                    .setSnapshots(true)
                    .setSources(false));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void captureArtifacts(Page page, BrowserContext context, BrowserEngine engine,
                                         String label, boolean tracing) {
        String slug = engine + "-" + label.replaceAll("[^A-Za-z0-9.-]", "_");
        try {
            Files.createDirectories(ARTIFACTS);
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(ARTIFACTS.resolve(slug + ".png"))
                    .setFullPage(true));
        } catch (Exception ignored) {
            // A screenshot of a page that has already navigated away is not worth failing over; the
            // assertion failure below is the real report.
        }
        if (tracing) {
            try {
                context.tracing().stop(new Tracing.StopOptions()
                        .setPath(ARTIFACTS.resolve(slug + "-trace.zip")));
            } catch (Exception ignored) {
                // Same.
            }
        }
    }

    // ------------------------------------------------------------------
    // The fixture
    // ------------------------------------------------------------------

    /**
     * One Playwright instance and one {@link Browser} per engine, for the whole JVM.
     *
     * <p>Launching a browser costs a few hundred milliseconds and the tier does upwards of four
     * hundred page loads, so the expensive objects are shared and only the {@link BrowserContext}
     * is per case — which is what gives each case a clean cookie jar, cache and script world. The
     * sentinel server is the other way round, per test class, as T25 asks.
     */
    private static final class PlaywrightFixture {

        private static PlaywrightFixture instance;

        private final Playwright playwright;
        private final String playwrightFailure;
        private final Map<BrowserEngine, Browser> browsers = new EnumMap<>(BrowserEngine.class);
        private final Map<BrowserEngine, String> failures = new LinkedHashMap<>();

        private PlaywrightFixture() {
            Playwright created = null;
            String failure = null;
            try {
                created = Playwright.create();
            } catch (Exception e) {
                failure = e.getMessage();
            }
            this.playwright = created;
            this.playwrightFailure = failure;

            for (BrowserEngine engine : BrowserEngine.values()) {
                if (playwright == null) {
                    failures.put(engine, "Playwright is unavailable: " + playwrightFailure);
                    continue;
                }
                try {
                    browsers.put(engine, engine.type(playwright).launch());
                } catch (Exception e) {
                    failures.put(engine, summarise(e.getMessage()));
                }
            }
        }

        static synchronized PlaywrightFixture get() {
            if (instance == null) {
                instance = new PlaywrightFixture();
                PlaywrightFixture fixture = instance;
                Runtime.getRuntime().addShutdownHook(new Thread(fixture::close));
            }
            return instance;
        }

        List<BrowserEngine> available() {
            List<BrowserEngine> result = new ArrayList<>();
            for (BrowserEngine engine : BrowserEngine.values()) {
                if (browsers.containsKey(engine)) {
                    result.add(engine);
                }
            }
            return result;
        }

        Browser browser(BrowserEngine engine) {
            return browsers.get(engine);
        }

        String failure(BrowserEngine engine) {
            return failures.getOrDefault(engine, "");
        }

        String summary() {
            if (playwright == null) {
                return "Playwright could not start: " + playwrightFailure;
            }
            return failures.entrySet().stream()
                    .map(e -> e.getKey() + " (" + e.getValue() + ")")
                    .collect(Collectors.joining("; ", "None of the engines launched: ", "."));
        }

        void close() {
            for (Browser browser : browsers.values()) {
                try {
                    browser.close();
                } catch (RuntimeException ignored) {
                    // Shutdown-time only.
                }
            }
            if (playwright != null) {
                try {
                    playwright.close();
                } catch (RuntimeException ignored) {
                    // Shutdown-time only.
                }
            }
        }

        /**
         * One readable line out of Playwright's multi-line launch failure.
         *
         * <p>Taking the first line is not enough: that line is {@code "Error {"}, and a skip reason
         * of "Error {" is worse than none — it says an engine did not run and hides why, which is
         * the exact failure {@link EngineRosterTest} exists to prevent.
         */
        private static String summarise(String message) {
            if (message == null || message.isBlank()) {
                return "no message";
            }
            String flattened = message.replaceAll("\\s+", " ").trim();
            return flattened.length() <= 300 ? flattened : flattened.substring(0, 300) + "...";
        }
    }
}
