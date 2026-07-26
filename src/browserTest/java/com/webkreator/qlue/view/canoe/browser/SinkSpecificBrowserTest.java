package com.webkreator.qlue.view.canoe.browser;

import com.webkreator.qlue.view.canoe.CanoeTestSupport;
import com.webkreator.qlue.view.canoe.corpus.CanoeCorpus;
import com.webkreator.qlue.view.canoe.corpus.Payload;
import com.webkreator.qlue.view.canoe.corpus.Payloads;
import com.webkreator.qlue.view.canoe.corpus.Verdict;
import com.webkreator.qlue.view.canoe.corpus.VerdictEvaluator;
import com.webkreator.qlue.view.canoe.corpus.XssCase;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The vectors the review asks to see in a real browser, each with a bespoke assertion (T29).
 *
 * <p>{@link BrowserCorpusTest} asks one question of 128 rows: did any detector fire. That is the
 * right question at that scale and it is a weak one — "something happened" is not "the thing the
 * finding claims happened". Each test below asserts the specific effect instead: <em>which</em>
 * frame the script ran in, <em>which</em> URL the relative resource retargeted to, <em>which</em>
 * request the CSS made. A generic detector cannot tell a {@code srcdoc} breakout from the parent
 * page's own script running, and the difference is the whole of F3.
 *
 * <p>Every case is taken from the corpus rather than hand-written, so the HTML under test is the
 * HTML the Velocity tier ledgered, byte for byte. The two exceptions are marked and say why.
 */
public class SinkSpecificBrowserTest extends BrowserTestBase {

    static List<BrowserEngine> engines() {
        return engineArgumentsOrSkipMarker();
    }

    /**
     * F3, {@code srcdoc}: the script runs <em>inside the iframe</em>, and the iframe is same-origin
     * with the page that framed it.
     *
     * <p>Both halves matter and neither is visible to a generic detector. If the sentinel had been
     * called from the main frame this would be an ordinary body-context injection and not a
     * {@code srcdoc} one; if the {@code srcdoc} document had an opaque origin the injected script
     * could not read the framing page and the impact would be a fraction of what F3 claims. A
     * {@code srcdoc} iframe inherits its parent's origin, so the injected code sits inside the
     * application's own security context — which is why the finding is Critical and not Medium.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    public void srcdocRunsScriptInsideTheIframeAndSameOrigin(BrowserEngine engine) {
        Rendered rendered = render("markup.srcdoc-whole-value", Payloads.SRCDOC_MARKUP);

        runCase(engine, "sink.srcdoc", rendered.html, passiveLoad(), (page, verdict) -> {
            assertTrue(verdict.scriptExecutions().contains("srcdoc"),
                    "the srcdoc payload did not run:\n" + verdict.describe());

            int index = verdict.scriptExecutions().indexOf("srcdoc");
            String frameUrl = verdict.scriptExecutionFrames().get(index);
            assertFalse(frameUrl.equals(verdict.url()),
                    "the script ran in the main frame, so this is not a srcdoc breakout at all: "
                            + frameUrl);

            assertEquals(2, page.frames().size(),
                    "expected the page and one srcdoc frame, got "
                            + page.frames().stream().map(f -> f.url()).toList());

            // window.origin, not location.origin: a srcdoc document's *URL* is about:srcdoc, whose
            // origin serialises to "null", while the document's own origin is inherited from the
            // parent. Asserting the first would have failed for a reason that has nothing to do
            // with the finding.
            assertEquals(server.origin(), page.frames().get(1).evaluate("window.origin"),
                    "a srcdoc document inherits the framing page's origin");

            // And the assertion that actually matters, because it is the capability rather than a
            // string: from inside the frame, the injected script reads the framing page's URL. A
            // cross-origin frame would throw a SecurityError here. This is what makes F3 Critical —
            // the attacker's code is inside the application's security context, not beside it.
            Object parentUrl = page.frames().get(1).evaluate(
                    "(function(){ try { return String(parent.location.href); }"
                            + " catch (e) { return 'DENIED: ' + e; } })()");
            assertEquals(verdict.url(), parentUrl,
                    "the injected script could not read the framing page, so it is not same-origin"
                            + " with it and F3 is over-rated.");
        });
    }

    /**
     * F3, {@code xlink:href}: a synthetic click on an SVG link navigates to a {@code javascript:}
     * URL and runs it.
     *
     * <p>Written as a click rather than as a load because that is the vector's whole shape: nothing
     * happens when the page loads, so a passive browser tier would report this Critical finding as
     * silent. The click is synthetic and untrusted, which is the interesting part — the HTML
     * Standard runs an element's activation behaviour for a dispatched {@code click} regardless of
     * {@code isTrusted}, so a page that never sees a user can still be attacked by any script that
     * calls {@code click()} on a link the attacker supplied the href for.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    public void anSvgXlinkHrefClickRunsAJavascriptUrl(BrowserEngine engine) {
        Rendered rendered = render("url.xlink-href", Payloads.JS_URL);

        runCase(engine, "sink.xlink-href", rendered.html,
                page -> page.evaluate(
                        "document.querySelector('svg a').dispatchEvent("
                                + "new MouseEvent('click', {bubbles:true, cancelable:true,"
                                + " view:window}))"),
                (page, verdict) -> assertTrue(verdict.scriptExecutions().contains("u"),
                        "clicking the SVG link did not run the javascript: URL:\n"
                                + verdict.describe()));
    }

    /**
     * F3, {@code <meta http-equiv=refresh>}: the <em>top-level</em> document navigates to the
     * attacker's origin.
     *
     * <p>Asserted as a main-frame navigation request rather than as "a request to attacker.invalid
     * happened", because the two are very different outcomes. A subresource fetch to an attacker
     * origin leaks a referrer and a cookie-less hit; a top-level navigation takes the user's
     * session away from the application and hands the attacker the whole browsing context.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    public void metaRefreshNavigatesTheTopLevelDocumentOffOrigin(BrowserEngine engine) {
        Rendered rendered = render("refresh.meta-content", Payloads.META_REFRESH);

        runCase(engine, "sink.meta-refresh", rendered.html, passiveLoad(), (page, verdict) -> {
            assertTrue(verdict.sentinelRequests().stream()
                            .anyMatch(u -> u.endsWith("//" + Payloads.SENTINEL_HOST + "/target")),
                    "the meta refresh did not reach the sentinel origin:\n" + verdict.describe());
            assertFalse(verdict.abortedSentinelRequests().isEmpty(),
                    "the navigation should have been aborted at the route:\n" + verdict.describe());
            // The page never left the sentinel origin only because the interceptor aborted it. That
            // is the tier protecting the test environment, not the browser protecting the user.
            assertTrue(page.url().startsWith(server.origin())
                            || page.url().startsWith("chrome-error:"),
                    "unexpected final URL " + page.url());
        });
    }

    /**
     * {@code <base href>}: a <em>relative</em> resource elsewhere on the page retargets to the
     * attacker's origin.
     *
     * <p>The review does not cover this one. It is worth its own test because the damage is done to
     * markup the attacker never touched: the corpus template's {@code <img src="/logo.png">} is a
     * root-relative reference to the application's own asset, and it is the thing that ends up
     * fetched from {@code attacker.invalid}. Asserting on the <em>path</em> is what says so — a
     * generic "the sentinel origin was contacted" assertion would pass just as well if the
     * {@code <base>} element itself had made the request, which it cannot.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    public void aBaseHrefHijackRetargetsLaterRelativeUrls(BrowserEngine engine) {
        Rendered rendered = render("url.base-href", Payloads.BASE_HIJACK);

        BrowserVerdict verdict = runCase(engine, "sink.base-href", rendered.html, passiveLoad());

        assertTrue(verdict.sentinelRequests().stream().anyMatch(u -> u.endsWith("/logo.png")),
                "the page's own /logo.png was not retargeted to the attacker origin:\n"
                        + verdict.describe());
        assertFalse(verdict.serverRequests().contains("GET /logo.png"),
                "the sentinel origin served /logo.png, so the base href did not take effect:\n"
                        + verdict.describe());
    }

    /**
     * F4's concrete impact: an attacker-controlled {@code style} value issues a
     * {@code background:url()} request to their origin.
     *
     * <p>This is the row the finding rests on, and it is one of six CSS rows the browser tier
     * loads. The other five do not fire, for reasons that have nothing to do with Canoe — a CSS
     * string containing the payload, a bad-url token, a CSS escape eating the host's first letter —
     * so asserting the specific request here rather than "a CSS case fired somewhere" is what keeps
     * F4's evidence attached to a shape that actually works.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    public void anInjectedStyleValueBeaconsToTheAttackerOrigin(BrowserEngine engine) {
        Rendered rendered = render("css.style-background", Payloads.CSS_URL_BEACON);

        BrowserVerdict verdict = runCase(engine, "sink.css-exfiltration", rendered.html,
                passiveLoad());

        assertTrue(verdict.sentinelRequests().stream()
                        .anyMatch(u -> u.equals("http://" + Payloads.SENTINEL_HOST + "/beacon")),
                "the injected background:url() did not beacon out:\n" + verdict.describe());
    }

    /**
     * The CSS twin of the test above, and the reason F4 has a bound: the same {@code style}
     * attribute, with the reference one container deeper, fetches from the page's own origin.
     *
     * <p>{@code background:url($x)} with {@code /\attacker.invalid/x.js} renders as
     * {@code url(&#47;&#92;attacker&#46;invalid&#47;x&#46;js)}. Three decoders then run in series
     * over a value Canoe encoded once. The HTML parser turns the character references back into
     * characters. The CSS tokenizer reads the backslash as an escape introducer and consumes the
     * {@code a} after it as a hex digit, yielding U+000A. The URL parser then removes U+000A from
     * anywhere in the string before parsing — the same rule that makes {@code java<LF>script:} live
     * and that the suite's URL oracle was hardened for — so what is actually requested is
     * {@code /ttacker.invalid/x.js} on the page's own origin, with the host's first letter simply
     * gone.
     *
     * <p>Asserted on the server's request log rather than on a detector, because the point is the
     * exact path that was requested and no detector reports that. This is F23.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    public void theCssTokenizerReReadsCanoesOutputAsAnEscape(BrowserEngine engine) {
        Rendered rendered = render("css.style-inside-url-function",
                Payloads.PROTOCOL_RELATIVE_BACKSLASH);

        BrowserVerdict verdict = runCase(engine, "sink.css-backslash-escape", rendered.html,
                passiveLoad());

        assertTrue(verdict.sentinelRequests().isEmpty(),
                "the attacker origin should be unreachable through a CSS backslash escape:\n"
                        + verdict.describe());
        assertTrue(verdict.serverRequests().contains("GET /ttacker.invalid/x.js"),
                "expected the page's own origin to be asked for a path whose first letter the CSS"
                        + " escape ate; the server saw " + verdict.serverRequests());
    }

    /**
     * F17, with a payload shaped for the position it lands in.
     *
     * <p>The corpus row {@code prefix.colon-in-a-recognised-handler} is flagged
     * not-browser-observable, and the flag would be easy to misread as "F17 is theoretical". It is
     * not. The shared {@code QUOTE_BREAKOUT} payload closes the string literal and the call's
     * parenthesis and leaves the surrounding object literal open, so the handler is a
     * {@code SyntaxError} and nothing runs — an accident of that one payload, not a property of the
     * sink. Written for the position, the same template executes.
     *
     * <p>This is the only test in the class whose payload is not from the corpus, and it is
     * deliberate: the corpus is a fixed catalogue of hostile strings applied across many templates,
     * which is what makes it a fair comparison between them; a finding that needs one payload
     * shaped for one template needs a test, not a corpus entry that would then be applied to
     * fourteen unrelated cases.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    public void f17IsExploitableWithAPayloadShapedForItsPosition(BrowserEngine engine) {
        XssCase testCase = CanoeCorpus.byId("prefix.colon-in-a-recognised-handler");
        String payload = "'});" + Payloads.SENTINEL_FUNCTION + "('f17');//";
        CanoeTestSupport.RenderResult rendered = VerdictEvaluator.render(testCase, payload);
        assertFalse(rendered.isError(), rendered.errorMessage());

        // The precondition F17 is about: the value was html-encoded rather than suppressed, so the
        // attacker's characters come back out of the HTML parser intact.
        assertTrue(rendered.decodedAttr("a", "onclick").contains("'});"),
                "F17's precondition does not hold any more; the value was: "
                        + rendered.decodedAttr("a", "onclick"));

        BrowserVerdict verdict = runCase(engine, "sink.f17", rendered.output(), fullInteraction());

        assertTrue(verdict.scriptExecutions().contains("f17"),
                "F17 did not execute, which contradicts the finding:\n" + verdict.describe());
    }

    /**
     * F20's sandbox row, as an effect rather than as a string comparison.
     *
     * <p>Both payloads of {@code policy.sandbox} frame the same document, whose only script calls
     * the sentinel. With {@code sandbox="allow-scripts allow-same-origin"} it runs; with
     * {@code sandbox="opener"} — an unrecognised token, which leaves the sandbox maximally
     * restrictive — it cannot. The pair is the evidence, not either half: a single test showing the
     * script running proves nothing about the sandbox, because it would also run with no sandbox at
     * all.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    public void aSandboxEscapeLetsFramedContentRunScript(BrowserEngine engine) {
        Rendered escaped = render("policy.sandbox", Payloads.POLICY_SANDBOX_ESCAPE);
        Rendered restrictive = render("policy.sandbox", Payloads.POLICY_REL_OPENER);
        assertEquals(Verdict.KNOWN_VULNERABLE, escaped.verdict);
        assertEquals(Verdict.SAFE, restrictive.verdict);

        BrowserVerdict withEscape = runCase(engine, "sink.sandbox-escape", escaped.html,
                passiveLoad());
        assertTrue(withEscape.scriptExecutions().contains("sandbox-scripts-enabled"),
                "the framed document's script did not run, so the sandbox was not defeated:\n"
                        + withEscape.describe());

        BrowserVerdict withoutEscape = runCase(engine, "sink.sandbox-intact", restrictive.html,
                passiveLoad());
        assertFalse(withoutEscape.scriptExecutions().contains("sandbox-scripts-enabled"),
                "an unrecognised sandbox token must leave the sandbox maximally restrictive, so the"
                        + " framed script must not run:\n" + withoutEscape.describe());
    }

    // ------------------------------------------------------------------

    private static Rendered render(String caseId, Payload payload) {
        XssCase testCase = CanoeCorpus.byId(caseId);
        assertTrue(testCase.payloads().contains(payload),
                caseId + " does not carry " + payload);
        CanoeTestSupport.RenderResult result = VerdictEvaluator.render(testCase, payload.value());
        assertFalse(result.isError(), result.errorMessage());
        assertNotNull(result.output());
        return new Rendered(result.output(), testCase.verdictFor(payload));
    }

    private static final class Rendered {

        final String html;
        final Verdict verdict;

        Rendered(String html, Verdict verdict) {
            this.html = html;
            this.verdict = verdict;
        }
    }
}
