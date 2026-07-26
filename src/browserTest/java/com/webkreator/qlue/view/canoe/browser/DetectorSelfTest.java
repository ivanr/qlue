package com.webkreator.qlue.view.canoe.browser;

import com.webkreator.qlue.view.canoe.corpus.Payloads;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gate (T27): every detector is shown to fire, on a page written to trip it.
 *
 * <p><b>If this class is not green, nothing else in this package means anything.</b> Plan
 * &sect;2.4: "A browser-based security test that never fails is indistinguishable from a
 * browser-based security test that is broken." {@code BrowserCorpusTest} asserts that 100-odd
 * {@code KNOWN_VULNERABLE} rows trip a detector and that two dozen more do not; a detector that
 * cannot fire turns the first half into noise and the second half into a false clean bill of
 * health. Read a red {@code BrowserCorpusTest} as evidence about Canoe only after reading a green
 * one here.
 *
 * <p>Every page below is deliberately <em>unencoded</em> — hand-written HTML that never went
 * through Canoe. That is the whole design: the detectors are calibrated against markup known to be
 * dangerous, so their silence elsewhere is a measurement rather than an assumption.
 *
 * <p>The class also asserts the converse, in {@link #noDetectorFiresOnABenignPage}, and that half
 * matters as much. "Every detector fired" is only interesting if the detectors are capable of not
 * firing; an oracle that reports an injection on every page is exactly as blind as one that
 * reports none, and it would make every {@code SAFE} control in the corpus fail rather than every
 * vulnerable row pass.
 */
public class DetectorSelfTest extends BrowserTestBase {

    static List<BrowserEngine> engines() {
        return engineArgumentsOrSkipMarker();
    }

    /**
     * Detector 1: script execution, through the exposed binding <em>and</em> through the window
     * sentinel the init script installs.
     *
     * <p>The two must agree. They can only agree if the init script ran after the binding was
     * installed and wrapped it rather than replacing it, which is an ordering assumption Playwright
     * documents nowhere; asserting {@code __canoeBindingPresentAtInit} turns it into a tested fact.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    public void theScriptExecutionDetectorFires(BrowserEngine engine) {
        runCase(engine, "self.script",
                "<button id=\"b\" onclick=\"" + Payloads.SENTINEL_FUNCTION
                        + "('self-test-onclick')\">go</button>",
                fullInteraction(),
                (page, verdict) -> {
                    assertTrue(verdict.scriptExecutions().contains("self-test-onclick"),
                            "the exposed binding did not record the call:\n" + verdict.describe());
                    assertTrue(verdict.windowSentinelCalls().contains("self-test-onclick"),
                            "the window sentinel did not record the call:\n" + verdict.describe());
                    assertEquals(Boolean.TRUE, page.evaluate("window.__canoeBindingPresentAtInit"),
                            "the init script ran before the binding was installed, so it replaced"
                                    + " the binding instead of wrapping it. Every window-sentinel"
                                    + " reading in this package is unreliable until that is fixed.");
                    assertTrue(verdict.exploited(), verdict.describe());
                });
    }

    /** Detector 2: dialogs. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    public void theDialogDetectorFires(BrowserEngine engine) {
        BrowserVerdict verdict = runCase(engine, "self.dialog",
                "<script>alert('self-test-dialog')</script>",
                passiveLoad());

        assertTrue(verdict.dialogs().contains("alert: self-test-dialog"),
                "no dialog was recorded:\n" + verdict.describe());
        assertTrue(verdict.exploited(), verdict.describe());
    }

    /**
     * Detector 3: the sentinel origin.
     *
     * <p>Asserted twice over. The request must be <em>seen</em>, which is what makes a beacon
     * observable, and it must have been <em>aborted</em> at the route, which is what proves the
     * {@code page.route} glob matches. Without the second assertion a mistyped glob would leave the
     * detector working and the interception silently absent, and the tier would be making real
     * outbound connection attempts to a host chosen for never resolving.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    public void theSentinelOriginDetectorFires(BrowserEngine engine) {
        BrowserVerdict verdict = runCase(engine, "self.sentinel",
                "<img src=\"https://" + Payloads.SENTINEL_HOST + "/b\" alt=\"\">",
                passiveLoad());

        assertTrue(verdict.sentinelRequests().stream().anyMatch(u -> u.contains("/b")),
                "no request to the sentinel origin was recorded:\n" + verdict.describe());
        assertFalse(verdict.abortedSentinelRequests().isEmpty(),
                "the request reached the network instead of being aborted at the route:\n"
                        + verdict.describe());
        assertTrue(verdict.exploited(), verdict.describe());
    }

    /** Detector 4: navigation, from a meta refresh to a same-origin destination. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    public void theNavigationDetectorFires(BrowserEngine engine) {
        BrowserVerdict verdict = runCase(engine, "self.navigation",
                "<meta http-equiv=\"refresh\" content=\"0;url=" + SentinelServer.TARGET_PATH + "\">",
                passiveLoad());

        assertTrue(verdict.navigations().stream()
                        .anyMatch(u -> u.equals(server.url(SentinelServer.TARGET_PATH))),
                "the navigation to " + SentinelServer.TARGET_PATH + " was not recorded:\n"
                        + verdict.describe());
        assertTrue(verdict.serverRequests().contains("GET " + SentinelServer.TARGET_PATH),
                "the server never saw the navigation: " + verdict.serverRequests());
    }

    /**
     * Detector 4, second half: an <em>off-origin</em> navigation is classified as one.
     *
     * <p>The test above proves navigations are seen; it cannot prove the origin comparison works,
     * because its destination is same-origin by construction. Every off-origin payload in the
     * corpus targets {@code attacker.invalid}, which the route interceptor aborts before it
     * commits — so there is nothing in the corpus that exercises this branch either. A second
     * sentinel server on a second ephemeral port is a real, reachable, different origin, and is the
     * only way to calibrate it.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    public void theNavigationDetectorDistinguishesAnotherOrigin(BrowserEngine engine) {
        try (SentinelServer elsewhere = SentinelServer.start()) {
            BrowserVerdict verdict = runCase(engine, "self.navigation-off-origin",
                    "<meta http-equiv=\"refresh\" content=\"0;url="
                            + elsewhere.url(SentinelServer.TARGET_PATH) + "\">",
                    passiveLoad());

            assertTrue(verdict.offOriginNavigations().stream()
                            .anyMatch(u -> u.startsWith(elsewhere.origin())),
                    "an off-origin navigation was not classified as one:\n" + verdict.describe());
            assertTrue(verdict.exploited(), verdict.describe());
            assertEquals(1, elsewhere.requestsFor(SentinelServer.TARGET_PATH).size(),
                    "the other origin should have served exactly one navigation");
        }
    }

    /** Detector 5: the console, from a script that cannot be parsed. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    public void theConsoleDetectorFires(BrowserEngine engine) {
        BrowserVerdict verdict = runCase(engine, "self.console",
                "<script>this is not javascript {{{</script>",
                passiveLoad());

        assertFalse(verdict.consoleErrors().isEmpty() && verdict.pageErrors().isEmpty(),
                "a syntax error produced no console output:\n" + verdict.describe());
        // The console is evidence, not exploitation. If this ever becomes true, every corpus row
        // whose payload lands in a syntactically broken position starts passing for the wrong
        // reason; see BrowserVerdict.
        assertFalse(verdict.exploited(),
                "a console error must not count as exploitation:\n" + verdict.describe());
    }

    /**
     * The converse, and the half that is easy to leave out: on a page with nothing hostile in it,
     * every detector stays quiet — including after the full interaction sweep clicks and submits
     * everything on it.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    public void noDetectorFiresOnABenignPage(BrowserEngine engine) {
        BrowserVerdict verdict = runCase(engine, "self.benign",
                "<p id=\"probe\">canoe</p>"
                        + "<a href=\"" + SentinelServer.TARGET_PATH + "\">link</a>"
                        + "<img src=\"/logo.png\" alt=\"\">"
                        + "<script src=\"" + SentinelServer.SCRIPT_PATH + "\"></script>"
                        + "<div onmouseover=\"f('safe')\">hover</div>",
                fullInteraction());

        assertFalse(verdict.exploited(),
                "a benign page tripped " + verdict.firedDetectors() + ":\n" + verdict.describe());
        assertTrue(verdict.scriptExecutions().isEmpty(), verdict.describe());
        assertTrue(verdict.sentinelRequests().isEmpty(), verdict.describe());
        assertTrue(verdict.dialogs().isEmpty(), verdict.describe());
        assertTrue(verdict.consoleErrors().isEmpty() && verdict.pageErrors().isEmpty(),
                "a benign page produced console output, which would mask a real one:\n"
                        + verdict.describe());
    }
}
