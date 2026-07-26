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
 * <p>{@link BrowserCorpusTest} asks one question of every browser-relevant row: did any detector
 * fire. That is the right question at that scale and it is a weak one — "something happened" is not
 * "the thing the finding claims happened". Each test below asserts the specific effect instead:
 * <em>which</em> frame the script ran in, <em>which</em> URL the relative resource retargeted to,
 * <em>which</em> request the CSS made. A generic detector cannot tell a {@code srcdoc} breakout from
 * the parent page's own script running, and the difference was the whole of F3.
 *
 * <p><strong>Most of this file is inverted now</strong>, by R2 (F4 and F17), R5 (F20) and R6 and R7
 * (F3's three sinks). The inversions are the reason the file is worth more after a fix than before
 * it: a generic "no detector fired" result over a suppressed row is green for a dozen reasons, and
 * only an assertion about the specific effect can tell "suppressed" from "arrived and happened not
 * to do anything this time". Each test keeps the mechanism that made its finding exploitable in its
 * javadoc, because that mechanism is a browser behaviour and is unchanged — what changed is that
 * Canoe no longer feeds it.
 *
 * <p>Every case is taken from the corpus rather than hand-written, so the HTML under test is the
 * HTML the Velocity tier ledgered, byte for byte. The two exceptions are marked and say why.
 */
public class SinkSpecificBrowserTest extends BrowserTestBase {

    static List<BrowserEngine> engines() {
        return engineArgumentsOrSkipMarker();
    }

    /**
     * F3's {@code srcdoc} row, <strong>inverted by R6</strong>. Was
     * {@code srcdocRunsScriptInsideTheIframeAndSameOrigin}.
     *
     * <p>The reasoning it recorded is a browser fact and is unchanged, so it is kept: a
     * {@code srcdoc} iframe <em>inherits its parent's origin</em>, so the injected code used to sit
     * inside the application's own security context rather than beside it — which is why the finding
     * was Critical and not Medium, and it was asserted as a capability rather than as a string, by
     * reading {@code parent.location.href} from inside the frame. A cross-origin frame would have
     * thrown a SecurityError there. Neither half was visible to a generic detector: script running
     * in the main frame would have been an ordinary body-context injection and not a
     * {@code srcdoc} one.
     *
     * <p>R6 leaves the attribute empty, so the iframe still exists and its document is still
     * same-origin — that has not changed and cannot be encoded away — and there is no attacker
     * markup in it. The assertions invert to exactly that: the frame is there, the origin is still
     * inherited, and nothing ran.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    public void srcdocIsSuppressedSoNothingRunsInsideTheIframe(BrowserEngine engine) {
        Rendered rendered = render("markup.srcdoc-whole-value", Payloads.SRCDOC_MARKUP);
        assertEquals(Verdict.SUPPRESSED_BY_DESIGN, rendered.verdict);
        assertFalse(rendered.html.contains("onerror"),
                "R6: the payload must not appear in the rendered page at all: " + rendered.html);

        runCase(engine, "sink.srcdoc", rendered.html, passiveLoad(), (page, verdict) -> {
            assertFalse(verdict.scriptExecutions().contains("srcdoc"),
                    "the srcdoc payload ran, so the value is being encoded rather than suppressed -"
                            + " and single encoding into srcdoc is same-origin XSS:\n"
                            + verdict.describe());

            assertEquals(2, page.frames().size(),
                    "the iframe itself must still be there - suppression is about the value, not"
                            + " about the template's own markup - got "
                            + page.frames().stream().map(f -> f.url()).toList());

            // window.origin, not location.origin: a srcdoc document's *URL* is about:srcdoc, whose
            // origin serialises to "null", while the document's own origin is inherited from the
            // parent. The inheritance is what made the finding Critical, it is unchanged, and it is
            // asserted here so that the test still says why suppressing this attribute matters.
            assertEquals(server.origin(), page.frames().get(1).evaluate("window.origin"),
                    "a srcdoc document still inherits the framing page's origin, which is why"
                            + " anything interpolated into it would still be same-origin script");
        });
    }

    /**
     * F3's {@code xlink:href} row, <strong>inverted by R6</strong>. Was
     * {@code anSvgXlinkHrefClickRunsAJavascriptUrl}.
     *
     * <p>Still written as a click rather than as a load, because that is the vector's whole shape:
     * nothing happens when the page loads, so a passive browser tier would have reported this
     * Critical finding as silent, and would report a regression of it as silent too. The click is
     * synthetic and untrusted, which is the interesting part — the HTML Standard runs an element's
     * activation behaviour for a dispatched {@code click} regardless of {@code isTrusted}, so a page
     * that never sees a user can still be attacked by any script that calls {@code click()} on a
     * link the attacker supplied the href for.
     *
     * <p>R6 puts {@code xlink:href} on the URL list, and since R12 {@code url()} rejects a scheme off
     * its {http, https, mailto} allowlist to the empty string — so the {@code javascript:} URL is
     * suppressed outright and the click navigates nowhere instead of compiling a script.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    public void anSvgXlinkHrefClickNoLongerRunsAJavascriptUrl(BrowserEngine engine) {
        Rendered rendered = render("url.xlink-href", Payloads.JS_URL);
        assertEquals(Verdict.SUPPRESSED_BY_DESIGN, rendered.verdict);
        assertFalse(rendered.html.contains("javascript:"),
                "R12: url() must have rejected the scheme, emitting nothing: " + rendered.html);

        runCase(engine, "sink.xlink-href", rendered.html,
                page -> page.evaluate(
                        "document.querySelector('svg a').dispatchEvent("
                                + "new MouseEvent('click', {bubbles:true, cancelable:true,"
                                + " view:window}))"),
                (page, verdict) -> assertFalse(verdict.scriptExecutions().contains("u"),
                        "clicking the SVG link ran the javascript: URL, so xlink:href is being"
                                + " html-encoded again:\n" + verdict.describe()));
    }

    /**
     * F3's {@code <meta http-equiv=refresh>} row, <strong>inverted by R5 and R7</strong>. Was
     * {@code metaRefreshNavigatesTheTopLevelDocumentOffOrigin}.
     *
     * <p>It was asserted as a main-frame navigation request rather than as "a request to
     * attacker.invalid happened", because the two are very different outcomes: a subresource fetch
     * to an attacker origin leaks a referrer and a cookie-less hit, while a top-level navigation
     * takes the user's session away from the application and hands the attacker the whole browsing
     * context. That distinction is why the row was worth a bespoke test, and it is why the inverted
     * form asserts on the request log rather than on a detector — a detector that stayed quiet would
     * also stay quiet if the navigation had happened and simply not been intercepted.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    public void metaRefreshNoLongerNavigatesAnywhere(BrowserEngine engine) {
        Rendered rendered = render("refresh.meta-content", Payloads.META_REFRESH);
        assertEquals(Verdict.SUPPRESSED_BY_DESIGN, rendered.verdict);
        assertFalse(rendered.html.contains(Payloads.SENTINEL_HOST),
                "R7: the refresh target must not appear in the rendered page at all: "
                        + rendered.html);

        runCase(engine, "sink.meta-refresh", rendered.html, passiveLoad(), (page, verdict) -> {
            assertTrue(verdict.sentinelRequests().isEmpty(),
                    "the meta refresh reached the sentinel origin, so 'content' is being encoded"
                            + " rather than suppressed:\n" + verdict.describe());
            assertTrue(page.url().startsWith(server.origin()),
                    "and the document must still be the one that was served; it was at "
                            + page.url());
        });
    }

    /**
     * What R6 bought at a URL sink, as an effect: a {@code javascript:} URL in {@code formaction}
     * no longer runs when the form is submitted.
     *
     * <p>New with R5+R6, and it is the browser-side half of the largest routing change in the phase.
     * {@code formaction} was {@code html()}-encoded, so the HTML parser handed the URL parser the
     * attacker's characters back and submitting the form ran their script with the page's
     * privileges; it is {@code url()}-encoded now, and since R12 a scheme off the allowlist is
     * rejected outright, so {@code formaction} renders empty and submitting the form posts to the
     * page's own URL.
     *
     * <p>Worth a bespoke test rather than a corpus row because the corpus row is a suppression and a
     * suppressed row is loaded at most once, as a control, with no assertion about <em>why</em> it
     * stayed quiet. This asserts the mechanism: the form is actually submitted, and nothing runs.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    public void aFormactionJavascriptUrlNoLongerRunsOnSubmit(BrowserEngine engine) {
        Rendered rendered = render("url.formaction", Payloads.JS_URL);
        assertEquals(Verdict.SUPPRESSED_BY_DESIGN, rendered.verdict);
        assertFalse(rendered.html.contains("javascript:"),
                "R12: url() must have rejected the scheme, emitting nothing: " + rendered.html);

        BrowserVerdict verdict = runCase(engine, "sink.formaction", rendered.html,
                fullInteraction());

        assertFalse(verdict.scriptExecutions().contains("u"),
                "submitting the form ran the javascript: URL, so formaction is being html-encoded"
                        + " again:\n" + verdict.describe());
    }

    /**
     * {@code <base href>}: R9 closes the hijack, so a <em>relative</em> resource elsewhere on the page
     * stays on the application's own origin.
     *
     * <p>Inverted from {@code aBaseHrefHijackRetargetsLaterRelativeUrls}, which measured F6's widest
     * blast radius before R9 fixed it: the corpus template's {@code <img src="/logo.png">} is a
     * root-relative reference to the application's own asset, and with an off-origin {@code <base href>}
     * it was the thing that ended up fetched from {@code attacker.invalid}. R9 treats {@code <base href>}
     * as a resource-loading sink and rejects the off-origin authority to the empty string, so the base
     * renders empty and {@code /logo.png} resolves against the page's own origin. Asserting on the
     * <em>path</em> is still what makes the test precise: the application's server is the one that must
     * serve {@code /logo.png}, and the sentinel origin must never be asked for it.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    public void aBaseHrefHijackIsClosedSoRelativeUrlsStayOnOrigin(BrowserEngine engine) {
        Rendered rendered = render("url.base-href", Payloads.BASE_HIJACK);

        BrowserVerdict verdict = runCase(engine, "sink.base-href", rendered.html, passiveLoad());

        assertFalse(verdict.sentinelRequests().stream().anyMatch(u -> u.endsWith("/logo.png")),
                "R9: the base href was suppressed, so /logo.png must not have been retargeted to the"
                        + " attacker origin:\n" + verdict.describe());
        assertTrue(verdict.serverRequests().contains("GET /logo.png"),
                "R9: /logo.png must be served from the application's own origin now that the base"
                        + " href is empty:\n" + verdict.describe());
    }

    /**
     * F4's concrete impact, inverted by R2. Was
     * {@code anInjectedStyleValueBeaconsToTheAttackerOrigin}.
     *
     * <p>An attacker-controlled {@code style} value used to issue a {@code background:url()} request
     * to their origin, and this was the row the finding rested on — one of six CSS rows the browser
     * tier loads, and the only one that fired. The other five did not, for reasons that have nothing
     * to do with Canoe: a CSS string containing the payload, a bad-url token, a CSS escape eating
     * the host's first letter. That is exactly why this test is kept and inverted rather than left
     * to {@code BrowserCorpusTest}: a generic "no detector fired" result over the CSS group would be
     * green for five of those six rows even if F4 came back, and only the specific request assertion
     * distinguishes "suppressed" from "arrived and did nothing".
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    public void anInjectedStyleValueNoLongerBeaconsToTheAttackerOrigin(BrowserEngine engine) {
        Rendered rendered = render("css.style-background", Payloads.CSS_URL_BEACON);
        assertEquals(Verdict.SUPPRESSED_BY_DESIGN, rendered.verdict);
        assertFalse(rendered.html.contains(Payloads.SENTINEL_HOST),
                "R2: the payload must not appear in the rendered page at all: " + rendered.html);

        BrowserVerdict verdict = runCase(engine, "sink.css-exfiltration", rendered.html,
                passiveLoad());

        assertTrue(verdict.sentinelRequests().isEmpty(),
                "R2: the style value is suppressed, so no request may reach the attacker origin:\n"
                        + verdict.describe());
    }

    /**
     * The CSS twin of the test above, inverted by R2. Was
     * {@code theCssTokenizerReReadsCanoesOutputAsAnEscape}.
     *
     * <p>This was the reason F4 had a bound: the same {@code style} attribute, with the reference
     * one container deeper, fetched from the page's own origin.
     * {@code background:url($x)} with {@code /\attacker.invalid/x.js} rendered as
     * {@code url(&#47;&#92;attacker&#46;invalid&#47;x&#46;js)}, and three decoders then ran in
     * series over a value Canoe encoded once. The HTML parser turned the character references back
     * into characters. The CSS tokenizer read the backslash as an escape introducer and consumed the
     * {@code a} after it as a hex digit, yielding U+000A. The URL parser then removed U+000A from
     * anywhere in the string before parsing — the same rule that makes {@code java<LF>script:} live
     * and that the suite's URL oracle was hardened for — so what was actually requested was
     * {@code /ttacker.invalid/x.js} on the page's own origin, with the host's first letter simply
     * gone. That is F23, and it is a browser behaviour rather than a Canoe one: it is unchanged and
     * unobservable now, because nothing reaches the CSS tokenizer for it to act on.
     *
     * <p>Still asserted on the server's request log rather than on a detector, because the claim is
     * about which paths were requested and no detector reports that. The template's own
     * {@code background:url()} is the only thing left in the value, and an empty url() token issues
     * no request at all — so the assertion is that the server saw nothing from this page beyond the
     * document itself.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    public void theCssTokenizerHasNothingLeftToReRead(BrowserEngine engine) {
        Rendered rendered = render("css.style-inside-url-function",
                Payloads.PROTOCOL_RELATIVE_BACKSLASH);
        assertEquals(Verdict.SUPPRESSED_BY_DESIGN, rendered.verdict);

        BrowserVerdict verdict = runCase(engine, "sink.css-backslash-escape", rendered.html,
                passiveLoad());

        assertTrue(verdict.sentinelRequests().isEmpty(),
                "the attacker origin must be unreachable:\n" + verdict.describe());
        assertFalse(verdict.serverRequests().contains("GET /ttacker.invalid/x.js"),
                "R2: F23's three-decoder chain has nothing to act on, so the page's own origin must"
                        + " not be asked for the escape-mangled path either; the server saw "
                        + verdict.serverRequests());
        assertFalse(verdict.serverRequests().contains("GET /attacker.invalid/x.js"),
                "...nor for the un-mangled one; the server saw " + verdict.serverRequests());
    }

    /**
     * F17, with a payload shaped for the position it lands in — inverted by R2. Was
     * {@code f17IsExploitableWithAPayloadShapedForItsPosition}.
     *
     * <p>The corpus row {@code prefix.colon-in-a-recognised-handler} used to be flagged
     * not-browser-observable, and the flag would have been easy to misread as "F17 is theoretical".
     * It was not. The shared {@code QUOTE_BREAKOUT} payload closes the string literal and the call's
     * parenthesis and leaves the surrounding object literal open, so the handler was a
     * {@code SyntaxError} and nothing ran — an accident of that one payload, not a property of the
     * sink. Written for the position, the same template executed.
     *
     * <p>That is precisely why this test is kept after the fix rather than retired with the flag.
     * The corpus payloads could never have demonstrated F17 in a browser, so they cannot demonstrate
     * its absence either: a green {@code BrowserCorpusTest} over that row proves only that a
     * SyntaxError is still a SyntaxError. The position-shaped payload is the one input that told the
     * two apart, and it is the one that has to be suppressed for the fix to mean anything.
     *
     * <p>This is still the only test in the class whose payload is not from the corpus, and still
     * deliberately so: the corpus is a fixed catalogue of hostile strings applied across many
     * templates, which is what makes it a fair comparison between them; a claim that needs one
     * payload shaped for one template needs a test, not a corpus entry that would then be applied to
     * fourteen unrelated cases.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    public void f17IsNoLongerExploitableEvenWithAPayloadShapedForItsPosition(BrowserEngine engine) {
        XssCase testCase = CanoeCorpus.byId("prefix.colon-in-a-recognised-handler");
        String payload = "'});" + Payloads.SENTINEL_FUNCTION + "('f17');//";
        CanoeTestSupport.RenderResult rendered = VerdictEvaluator.render(testCase, payload);
        assertFalse(rendered.isError(), rendered.errorMessage());

        // F17's precondition: the value was html-encoded rather than suppressed, so the attacker's
        // characters came back out of the HTML parser intact. R2 removes the precondition.
        assertEquals("f({a:1,b:''})", rendered.decodedAttr("a", "onclick"),
                "R2: the handler must be the template's own text with an empty string literal where"
                        + " the reference was; it was: " + rendered.decodedAttr("a", "onclick"));

        BrowserVerdict verdict = runCase(engine, "sink.f17", rendered.output(), fullInteraction());

        assertFalse(verdict.scriptExecutions().contains("f17"),
                "F17 executed, so the reset - or something with its shape - is back:\n"
                        + verdict.describe());
    }

    /**
     * F20's sandbox row, <strong>inverted by R5</strong>. Was
     * {@code aSandboxEscapeLetsFramedContentRunScript}.
     *
     * <p>Both payloads of {@code policy.sandbox} frame the same document, whose only script calls
     * the sentinel. With {@code sandbox="allow-scripts allow-same-origin"} it ran; with
     * {@code sandbox="opener"} — an unrecognised token, which leaves the sandbox maximally
     * restrictive — it could not. The pair was the evidence and neither half was: a single test
     * showing the script running proved nothing about the sandbox, because it would also run with no
     * sandbox at all.
     *
     * <p>R5 suppresses the value, so both renders carry {@code sandbox=""} — the empty token list,
     * which is the <em>most</em> restrictive sandbox there is — and the framed script cannot run in
     * either. The pair is kept and both halves inverted, because the property worth asserting is
     * still a comparison: what has to be true is that the attacker's chosen tokens no longer make
     * any difference, and one render cannot say that.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    public void aSandboxBuiltFromDataIsEmptyAndThereforeMaximallyRestrictive(BrowserEngine engine) {
        Rendered escaped = render("policy.sandbox", Payloads.POLICY_SANDBOX_ESCAPE);
        Rendered restrictive = render("policy.sandbox", Payloads.POLICY_REL_OPENER);
        assertEquals(Verdict.SUPPRESSED_BY_DESIGN, escaped.verdict);
        assertEquals(Verdict.SUPPRESSED_BY_DESIGN, restrictive.verdict);
        assertEquals(escaped.html, restrictive.html,
                "R5: the two renders must be byte-identical now - the attacker's tokens decide"
                        + " nothing, which is the only fix available for a directive");

        BrowserVerdict withEscape = runCase(engine, "sink.sandbox-escape", escaped.html,
                passiveLoad());
        assertFalse(withEscape.scriptExecutions().contains("sandbox-scripts-enabled"),
                "the framed document's script ran, so allow-scripts allow-same-origin reached the"
                        + " sandbox attribute and F20 is open again:\n" + withEscape.describe());

        BrowserVerdict withoutEscape = runCase(engine, "sink.sandbox-intact", restrictive.html,
                passiveLoad());
        assertFalse(withoutEscape.scriptExecutions().contains("sandbox-scripts-enabled"),
                "an empty sandbox token list is the most restrictive sandbox there is, so the"
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
