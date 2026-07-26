package com.webkreator.qlue.view.canoe.velocity;

import com.webkreator.qlue.view.Canoe;
import com.webkreator.qlue.view.CanoeStateProbe;
import com.webkreator.qlue.view.canoe.CanoeTestSupport;
import com.webkreator.qlue.view.canoe.corpus.CanoeCorpus;
import com.webkreator.qlue.view.canoe.corpus.Payload;
import com.webkreator.qlue.view.canoe.corpus.Payloads;
import com.webkreator.qlue.view.canoe.corpus.SinkKind;
import com.webkreator.qlue.view.canoe.corpus.Verdict;
import com.webkreator.qlue.view.canoe.corpus.VerdictEvaluator;
import com.webkreator.qlue.view.canoe.corpus.XssCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * URL sinks: the seventeen names Canoe routes to {@code url()}, the elements that carry them, and
 * the four positions a reference can occupy inside a URL.
 *
 * <p>It was five names until R6, and the twelve it added are not a widening of this file's subject
 * so much as a transfer: they were F3 — {@code html()}-encoded and decoded straight back by the
 * parser — and they are F6 now, which is what the rest of this file is about. Nothing here asserts
 * that the new names are <em>safe</em>; it asserts that they behave identically to {@code href},
 * including in the way {@code href} is defective.
 *
 * <h2>What this file asserts that {@code CanoeCorpusTest} does not</h2>
 *
 * <p>The corpus holds a reviewed verdict for each (URL template, payload) pair and
 * {@code CanoeCorpusTest.ledgerMatchesObservedBehaviour} asserts each one against an independently
 * derived observation. What it cannot say is <em>why the verdicts are the same</em>. Every URL row in
 * the corpus reads as an isolated judgement about one template; the two properties below say that
 * those judgements were never independent in the first place:
 *
 * <ul>
 *   <li><strong>The tag name decides the encoder (R9).</strong> R8 keeps the element name available
 *       through attribute parsing, and R9 uses it: {@code src} on {@code <script>} rejects an
 *       off-origin authority, {@code src} on {@code <img>} does not.
 *       {@link #theTagNameNowDecidesTheEncoderForSrcAndHref} asserts the split — an off-origin value
 *       is empty on the six resource-loading sinks and survives on the open-redirect ones — which is
 *       the inversion of the old {@code everyElementGetsTheSameEncoderForTheSameAttributeName} that
 *       measured F6's structural cause before it was fixed.
 *   <li><strong>Position, not bytes, decides whether F6 fires.</strong> The same payload through the
 *       same encoder is a live off-origin script include in full-URL and path-prefix position and
 *       inert in query and fragment position — because what makes a URL off-origin is where its
 *       authority sits, and the template's own literal text is what decides that. A ledger of
 *       per-template verdicts records the four answers; {@link #theFourSubstitutionPositions} records
 *       the rule, which is what bounds F6 for anybody auditing templates.
 * </ul>
 *
 * <p>Where the arithmetic of the name set lives: {@code AttributeNameMatrixTest} owns the
 * {@code ATTR_*} partition and asserts that {@code ATTR_URI} is exactly the seventeen names R6 and
 * R7 settled. This file takes that set as given and asks what happens to a value inside one.
 */
public class UrlSinkTest {

    /** The Appendix A section the URL cases are filed under. */
    private static final String SECTION = "A.2 attribute names";

    /**
     * The URL-bearing (element, attribute) pairs the plan names, with what Canoe does with each.
     *
     * <p>Two of the nine used not to be {@code CTX_URI}, and each was a separate finding rather than
     * an exception: {@code <object data>} was F7 — the branch commented {@code content} compared
     * {@code data}, so the value was dropped — and {@code <form action>} was F3, which had no branch
     * at all, so {@code html()} applied and the parser handed the URL parser the attacker's
     * characters back. R6 and R7 closed both, and the table is now uniform: every URL-bearing name
     * in it reaches {@code url()}.
     *
     * <p>The rows R6 added are here too, so that the file's two properties — tag-name blindness and
     * position — are asserted over the whole URL set rather than over the five names that predate
     * it. That matters more than it did: {@code formaction} and {@code ping} now inherit F6's
     * off-origin passthrough, and inheriting it on eleven more names is the cost R6 accepted for
     * closing F3's URL half before R9 exists.
     */
    static Stream<Arguments> urlBearingElements() {
        return Stream.of(
                // The six resource-loading (element, attribute) combinations R9 routes to the
                // origin-checking encoder: their context is CTX_URI_RESOURCE, not CTX_URI, and that
                // is the whole of R9. See Canoe.RESOURCE_LOADING_SINKS.
                Arguments.of("script", "src", Canoe.CTX_URI_RESOURCE, null),
                Arguments.of("iframe", "src", Canoe.CTX_URI_RESOURCE, null),
                Arguments.of("embed", "src", Canoe.CTX_URI_RESOURCE, null),
                Arguments.of("object", "data", Canoe.CTX_URI_RESOURCE, "F7"),
                Arguments.of("link", "href", Canoe.CTX_URI_RESOURCE, null),
                Arguments.of("base", "href", Canoe.CTX_URI_RESOURCE, null),
                // The open-redirect and referrer surfaces, which keep the ordinary url() encoder by
                // design (R9 scopes them out): a href, img src, and the fetch-not-code names.
                Arguments.of("a", "href", Canoe.CTX_URI, null),
                Arguments.of("img", "src", Canoe.CTX_URI, null),
                Arguments.of("form", "action", Canoe.CTX_URI, "F3"),
                Arguments.of("button", "formaction", Canoe.CTX_URI, "F3"),
                Arguments.of("video", "poster", Canoe.CTX_URI, "F3"),
                Arguments.of("blockquote", "cite", Canoe.CTX_URI, "F3"),
                Arguments.of("img", "usemap", Canoe.CTX_URI, "F3"),
                Arguments.of("img", "longdesc", Canoe.CTX_URI, "F3"),
                Arguments.of("applet", "codebase", Canoe.CTX_URI, "F3"),
                Arguments.of("html", "manifest", Canoe.CTX_URI, "F3"),
                Arguments.of("a", "ping", Canoe.CTX_URI, "F3"),
                Arguments.of("img", "srcset", Canoe.CTX_URI, "F3"),
                Arguments.of("a", "xlink:href", Canoe.CTX_URI, "F3"),
                // ...and the two URL-bearing names R6 deliberately left off the list, which
                // suppress. Suppression is stronger than url(), so this is not a gap; it is the
                // fail-closed default doing the work for names no ordinary template writes.
                Arguments.of("iframe", "srcdoc", Canoe.CTX_SUPPRESS, "F3"),
                Arguments.of("link", "imagesrcset", Canoe.CTX_SUPPRESS, "F3"),
                Arguments.of("svg", "xml:base", Canoe.CTX_SUPPRESS, "F3"));
    }

    /** The resource-loading combinations R9 routes to the origin-checking encoder. */
    static Stream<Arguments> resourceLoadingSinks() {
        return urlBearingElements().filter(a -> a.get()[2].equals(Canoe.CTX_URI_RESOURCE));
    }

    /** The open-redirect/referrer combinations that keep the ordinary url() encoder. */
    static Stream<Arguments> openRedirectSinks() {
        return urlBearingElements().filter(a -> a.get()[2].equals(Canoe.CTX_URI));
    }

    @ParameterizedTest(name = "<{0} {1}>")
    @MethodSource("urlBearingElements")
    public void theContextOfAUrlAttributeDependsOnTheElementForResourceSinks(String element,
                                                              String attribute,
                                                              int expected, String finding) {
        assertEquals(expected, CanoeTestSupport.contextAfter("<" + element + " " + attribute + "=\""),
                () -> (finding == null ? "" : finding + ": ")
                        + "<" + element + " " + attribute + "> must produce "
                        + CanoeTestSupport.contextName(expected) + " but produced "
                        + CanoeTestSupport.contextName(
                                CanoeTestSupport.contextAfter("<" + element + " " + attribute + "=\"")));
    }

    // ------------------------------------------------------------------
    // The tag name decides the encoder (R9, formerly F6's structural cause)
    // ------------------------------------------------------------------

    /**
     * The same attribute name on a resource-loading element and on an open-redirect element no longer
     * produces the same output: the tag name now decides the encoder.
     *
     * <p>This inverts {@code everyElementGetsTheSameEncoderForTheSameAttributeName}, which asserted
     * byte-identical output across every element as the measurement of F6's structural cause — Canoe
     * could not tell {@code <script src>} from {@code <img src>}. R8 gave it the tag name and R9 used
     * it: an off-origin value on {@code <script>}, {@code <iframe>}, {@code <embed>}, {@code <object>},
     * {@code <link>} or {@code <base>} is rejected to the empty string, while the same value on
     * {@code <a>}, {@code <img>} or {@code <form>} passes through {@code url()} as before. So an
     * off-origin payload splits the elements into exactly two groups, which is the assertion here.
     */
    @Test
    public void theTagNameNowDecidesTheEncoderForSrcAndHref() {
        for (Payload payload : List.of(Payloads.PROTOCOL_RELATIVE, Payloads.ABSOLUTE_OFFSITE_HTTPS)) {
            Map<String, String> resourceOutputs = new LinkedHashMap<>();
            for (Arguments row : (Iterable<Arguments>) resourceLoadingSinks()::iterator) {
                resourceOutputs.put(row.get()[0] + "/" + row.get()[1],
                        renderedValue((String) row.get()[0], (String) row.get()[1], payload));
            }
            Map<String, String> openRedirectOutputs = new LinkedHashMap<>();
            for (Arguments row : (Iterable<Arguments>) openRedirectSinks()::iterator) {
                openRedirectOutputs.put(row.get()[0] + "/" + row.get()[1],
                        renderedValue((String) row.get()[0], (String) row.get()[1], payload));
            }

            for (Map.Entry<String, String> entry : resourceOutputs.entrySet()) {
                assertEquals("", entry.getValue(),
                        () -> "R9: " + entry.getKey() + " is a resource-loading sink, so an off-origin"
                                + " " + payload.id() + " must be rejected to the empty string. All: "
                                + resourceOutputs);
            }
            for (Map.Entry<String, String> entry : openRedirectOutputs.entrySet()) {
                assertTrue(entry.getValue().contains(Payloads.SENTINEL_HOST),
                        () -> "R9 scopes " + entry.getKey() + " out by design: an open-redirect or"
                                + " referrer surface keeps url(), so the off-origin host survives."
                                + " Got: " + entry.getValue());
            }
        }
    }

    private static String renderedValue(String element, String attribute, Payload payload) {
        return CanoeTestSupport
                .render("<" + element + " " + attribute + "=\"$data\">", payload.value())
                .decodedAttr(element, attribute);
    }

    /**
     * The inversion of {@code anOffOriginCdnBaseSurvivesIntoAScriptSrcByteForByte}: an off-origin
     * value no longer survives into a {@code <script src>} by default. It is rejected to the empty
     * string, so the src falls back to whatever literal the template wrote.
     *
     * <p>The assertion is on the jsoup-decoded attribute value rather than on Canoe's output, which is
     * the distinction the whole review turns on. All three off-origin shapes — protocol-relative,
     * absolute, uppercase-scheme absolute — are rejected, and the {@code http:host} form a browser
     * still reads as an authority (no {@code //}) is rejected too, which an origin filter that only
     * looked for {@code //} would have missed.
     */
    @Test
    public void anOffOriginValueIsRejectedFromAScriptSrcByDefault() {
        String template = "<script src=\"$data/app.js\"></script>";

        for (String offOrigin : List.of("//attacker.invalid", "https://attacker.invalid",
                "HTTPS://attacker.invalid", "http:attacker.invalid")) {
            String decoded = CanoeTestSupport.render(template, offOrigin).decodedAttr("script", "src");
            assertEquals("/app.js", decoded,
                    () -> "R9: <script src> is a resource-loading sink, so " + offOrigin + " is"
                            + " rejected to the empty string and only the template's '/app.js'"
                            + " remains. Got: " + decoded);
            assertFalse(VerdictEvaluator.analyseUrl(decoded).isDangerous(),
                    "...and what remains is same-origin");
        }

        // A same-origin-relative value is untouched: R9 rejects an authority, not a path.
        assertEquals("/local/app.js",
                CanoeTestSupport.render("<script src=\"$data\"></script>", "/local/app.js")
                        .decodedAttr("script", "src"),
                "a relative URL carries no authority and cannot leave the origin, so it survives");
    }

    // ------------------------------------------------------------------
    // The configurable CDN allowlist (R9)
    // ------------------------------------------------------------------

    /**
     * An allowlisted CDN host survives into a {@code <script src>}, byte for byte, while a
     * non-allowlisted host is still rejected. This is the escape hatch that keeps R9's fail-closed
     * default from forcing {@code $_x.asis()} on any application that serves scripts from a CDN.
     */
    @Test
    public void anAllowlistedCdnHostSurvivesIntoAScriptSrcWhileOthersAreRejected() {
        String template = "<script src=\"$data/app.js\"></script>";

        String cdn = renderWithTrustedOrigins(template, "//cdn.example.com/lib",
                List.of("cdn.example.com")).decodedAttr("script", "src");
        assertEquals("//cdn.example.com/lib/app.js", cdn,
                "an allowlisted host survives into a resource-loading sink");
        assertEquals("https://cdn.example.com/lib/app.js",
                renderWithTrustedOrigins(template, "https://cdn.example.com/lib",
                        List.of("cdn.example.com")).decodedAttr("script", "src"),
                "and so does its absolute form");

        String attacker = renderWithTrustedOrigins(template, "//attacker.invalid/x",
                List.of("cdn.example.com")).decodedAttr("script", "src");
        assertEquals("/app.js", attacker,
                "a host that is not on the allowlist is still rejected, allowlist or no allowlist");
    }

    /**
     * The origin form of an allowlist entry pins the scheme: {@code https://cdn.example.com} admits
     * an {@code https} load but rejects the {@code http} downgrade.
     */
    @Test
    public void anOriginAllowlistEntryPinsTheScheme() {
        String template = "<script src=\"$data\"></script>";

        assertEquals("https://cdn.example.com/a.js",
                renderWithTrustedOrigins(template, "https://cdn.example.com/a.js",
                        List.of("https://cdn.example.com")).decodedAttr("script", "src"),
                "the allowlisted https origin survives");
        assertEquals("",
                renderWithTrustedOrigins(template, "http://cdn.example.com/a.js",
                        List.of("https://cdn.example.com")).decodedAttr("script", "src"),
                "the http downgrade is rejected because the origin entry pinned https");
    }

    /**
     * The allowlist is per factory (per Canoe instance), never static: one application's trusted
     * origins cannot widen another's. Two writers with different allowlists, the same off-origin
     * value, opposite outcomes.
     */
    @Test
    public void theResourceOriginAllowlistIsPerInstance() {
        String template = "<script src=\"$data\"></script>";
        String value = "//cdn.example.com/a.js";

        assertEquals("//cdn.example.com/a.js",
                renderWithTrustedOrigins(template, value, List.of("cdn.example.com"))
                        .decodedAttr("script", "src"),
                "the writer that trusts the CDN lets it through");
        assertEquals("",
                renderWithTrustedOrigins(template, value, List.of())
                        .decodedAttr("script", "src"),
                "a writer with no allowlist rejects the very same value - the allowlist is not shared");
    }

    /**
     * The static {@link Canoe#encode(String, int)} dispatcher, with no instance to carry an
     * allowlist, applies the safe default for the resource context: every off-origin authority is
     * rejected. This is the path a caller reaches without a configured Canoe, and it must fail closed.
     */
    @Test
    public void theStaticEncoderForTheResourceContextRejectsOffOriginWithNoAllowlist() {
        assertEquals("", Canoe.encode("//attacker.invalid/x.js", Canoe.CTX_URI_RESOURCE),
                "the static resource encoder has no allowlist, so an off-origin value is suppressed");
        assertEquals("/local.js", Canoe.encode("/local.js", Canoe.CTX_URI_RESOURCE),
                "a same-origin-relative value still survives");
    }

    private static CanoeTestSupport.RenderResult renderWithTrustedOrigins(String template,
                                                                          String value,
                                                                          List<String> origins) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("data", value);
        return CanoeTestSupport.render(template, model, CanoeTestSupport.RenderOptions.defaults(),
                writer -> new Canoe(writer, java.util.Collections.emptySet(), origins));
    }

    /**
     * The blindness runs the other way too, and it is worth one assertion because it bounds what a
     * fix has to look at.
     *
     * <p>{@code href}, {@code src}, {@code background}, {@code dynsrc} and {@code lowsrc} produce
     * {@code CTX_URI} on <em>any</em> element that is not one of R9's six resource-loading elements,
     * including ones where the attribute is not a URL and not defined at all. {@code <p src>} and
     * {@code <span lowsrc>} are nonsense markup and Canoe percent-encodes their values anyway.
     * Harmless — {@code url()} is stricter than {@code html()} for a plain-text value, so this
     * direction fails closed. R9 supplied the tag name that the dangerous direction needed and used it
     * on exactly the six element/attribute combinations where a URL loads code; everywhere else the
     * name still decides the context on its own, which is what this test pins.
     */
    @Test
    public void aRecognisedUriNameIsAUrlContextOnAnyElementAtAll() {
        assertEquals(Canoe.CTX_URI, CanoeTestSupport.contextAfter("<p src=\""));
        assertEquals(Canoe.CTX_URI, CanoeTestSupport.contextAfter("<span lowsrc=\""));
        assertEquals(Canoe.CTX_URI, CanoeTestSupport.contextAfter("<em dynsrc=\""));
        assertEquals(Canoe.CTX_URI, CanoeTestSupport.contextAfter("<div background=\""));

        // The one consequence a template author would notice: url() percent-escapes a plain-text
        // value that html() would have round-tripped. Since R12 a space becomes %20 and an '&'
        // becomes &amp; (url() is the terminal encoder, so it HTML-encodes the ampersand rather than
        // leaving a raw one that could start an entity), which is inert but not what the author typed.
        assertEquals("<div background=\"a%20b&amp;c\">x</div>",
                CanoeTestSupport.render("<div background=\"$data\">x</div>", "a b&c").output(),
                "url() percent-escapes the space and emits '&' as &amp;, so a value that happens to"
                        + " sit in an attribute Canoe thinks is a URL is transformed whatever the"
                        + " element is");
    }

    // ------------------------------------------------------------------
    // The four substitution positions
    // ------------------------------------------------------------------

    /**
     * The four positions a reference can occupy in a URL, and the rule that separates them.
     *
     * <p>{@code url()} applies the identical transformation in all four — it has no idea where in the
     * value it is — and the outcomes differ completely, because whether a URL is off-origin is decided
     * by whether the attacker's bytes can reach the <strong>authority</strong>. The template's own
     * literal text is what decides that:
     *
     * <table>
     *   <caption>Position and reach</caption>
     *   <tr><th>Position</th><th>Template</th><th>Reaches the authority</th></tr>
     *   <tr><td>full URL</td><td>{@code href="$data"}</td><td>yes — F6</td></tr>
     *   <tr><td>path prefix</td><td>{@code href="$data/app.js"}</td><td>yes — F6</td></tr>
     *   <tr><td>path suffix</td><td>{@code href="/p/$data"}</td><td>no</td></tr>
     *   <tr><td>query parameter</td><td>{@code href="/search?q=$data"}</td><td>no</td></tr>
     *   <tr><td>fragment</td><td>{@code href="/page#$data"}</td><td>no</td></tr>
     * </table>
     *
     * <p>This is the sharpest thing that can be said to somebody auditing templates against F6, and
     * it is the opposite of what the finding's headline suggests: it is not "a URL attribute holding
     * a reference is vulnerable", it is "a URL attribute whose reference can begin the authority is
     * vulnerable". The grep the review's triage section recommends returns all five shapes.
     *
     * <p>This test measures the positional rule on {@code <a href>}, an open-redirect surface that
     * keeps {@code url()}: R9 does not change it, so it is where the position property is still visible
     * as a property of the encoder rather than of the sink. On the six resource-loading sinks the
     * full-URL and path-prefix positions are now rejected outright — that is R9, asserted in
     * {@link #anOffOriginValueIsRejectedFromAScriptSrcByDefault}.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "full URL      | <a href=\"$data\">x</a>                  | true",
            "path prefix   | <a href=\"$data/app.js\">x</a>           | true",
            "path suffix   | <a href=\"/p/$data\">x</a>               | false",
            "query         | <a href=\"/search?q=$data\">x</a>        | false",
            "fragment      | <a href=\"/page#$data\">x</a>            | false",
    })
    public void theFourSubstitutionPositions(String position, String template, boolean reachesOrigin) {
        String selector = "a";
        String attribute = "href";

        for (Payload payload : List.of(Payloads.PROTOCOL_RELATIVE, Payloads.ABSOLUTE_OFFSITE_HTTPS)) {
            String decoded = CanoeTestSupport.render(template, payload.value())
                    .decodedAttr(selector, attribute);

            assertTrue(decoded.contains(payload.value()),
                    () -> position + ": url() passes every character of " + payload.id()
                            + " through, in every position. That is the constant; the outcome is"
                            + " not. Got: " + decoded);
            assertEquals(reachesOrigin, VerdictEvaluator.analyseUrl(decoded).isDangerous(),
                    () -> position + " with " + payload.id() + ": expected "
                            + (reachesOrigin ? "an off-origin URL" : "the page's own origin")
                            + " but the URL oracle says "
                            + VerdictEvaluator.analyseUrl(decoded).explanation()
                            + ". Decoded value: " + decoded);
        }
    }

    /**
     * The positional rule, restated as the thing a reader is most likely to get wrong: the two safe
     * positions are safe because of the template, not because of the encoder.
     *
     * <p>Byte for byte, the value {@code url()} produced is the same in both. Only what the template
     * wrote in front of it differs, and that is enough to move the payload from the authority into
     * the query.
     */
    @Test
    public void theQueryPositionIsSafeBecauseOfTheTemplateAndNotBecauseOfTheEncoder() {
        String payload = Payloads.PROTOCOL_RELATIVE.value();

        String full = CanoeTestSupport.render("<a href=\"$data\">x</a>", payload)
                .decodedAttr("a", "href");
        String query = CanoeTestSupport.render("<a href=\"/search?q=$data\">x</a>", payload)
                .decodedAttr("a", "href");

        assertEquals(payload, full, "url() emitted the payload unchanged");
        assertEquals("/search?q=" + full, query,
                "...and emitted exactly the same bytes in query position; the only difference"
                        + " between the two attribute values is the ten characters the template"
                        + " itself wrote");
        assertNotEquals(VerdictEvaluator.analyseUrl(full).isDangerous(),
                VerdictEvaluator.analyseUrl(query).isDangerous(),
                "and the two resolve to different origins, from identical encoder output");
    }

    // ------------------------------------------------------------------
    // The corpus, consumed
    // ------------------------------------------------------------------

    /**
     * Every {@code SinkKind.URL} case in &sect;A.2 that Canoe classifies as a URL, checked for the
     * one property {@code url()} genuinely delivers: the value it emits can never carry a character
     * that closes an attribute or opens a tag.
     *
     * <p>That is the half of the encoder that works, and it is why F6 is High rather than Critical —
     * an off-origin URL is an off-origin URL and not a breakout. It is also the precondition
     * {@code ParserSteeringTest} (T23) generalises: {@code url()} percent-escapes both quote marks
     * ({@code %22}, {@code %27}) and the angle brackets, so a URL-context reference cannot move
     * Canoe's state machine any more than a body-context one can.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("urlInvocations")
    public void urlEncodingNeverEmitsACharacterThatCanLeaveTheAttribute(XssCase.Invocation invocation) {
        XssCase testCase = invocation.testCase();
        CanoeTestSupport.RenderResult rendered =
                VerdictEvaluator.render(testCase, invocation.payload().value());
        CanoeTestSupport.RenderResult benign = VerdictEvaluator.render(testCase, "");

        for (char delimiter : new char[]{'<', '>', '"', '\''}) {
            assertEquals(count(benign.output(), delimiter), count(rendered.output(), delimiter),
                    () -> "A URL-context payload contributed a '" + delimiter + "': " + invocation
                            + "\n  rendered : " + CanoeTestSupport.quote(rendered.output()));
        }
    }

    /** The &sect;A.2 URL cases whose attribute Canoe actually recognises, from the corpus. */
    static List<XssCase.Invocation> urlInvocations() {
        List<XssCase.Invocation> result = new ArrayList<>();
        for (XssCase testCase : CanoeCorpus.inSection(SECTION)) {
            if (testCase.sink() != SinkKind.URL || testCase.attribute() == null) {
                continue;
            }
            if (attributeContextOf(testCase.attribute()) != Canoe.ATTR_URI) {
                continue;
            }
            result.addAll(testCase.invocations());
        }
        return result;
    }

    /**
     * The corpus's URL group agrees with the positional rule: every {@code KNOWN_VULNERABLE} URL row
     * on a <em>recognised</em> attribute name is one where the reference can reach the authority.
     *
     * <p>Stated over the data rather than over behaviour, and it is the guard that stops the rule
     * above from quietly becoming false. A case added later that puts a reference in query or
     * fragment position and ledgers it vulnerable is either a mistake or a new finding, and either
     * way somebody has to look at it.
     */
    @Test
    public void noRecognisedUrlCaseIsVulnerableWithoutReachingTheAuthority() {
        List<String> offenders = new ArrayList<>();
        for (XssCase.Invocation invocation : urlInvocations()) {
            if (invocation.verdict() != Verdict.KNOWN_VULNERABLE) {
                continue;
            }
            String template = invocation.testCase().template();
            int at = template.indexOf("$" + invocation.testCase().referenceName());
            String before = at < 0 ? "" : template.substring(0, at);
            int quote = before.lastIndexOf('"');
            String literalPrefix = quote < 0 ? "" : before.substring(quote + 1);
            if (literalPrefix.indexOf('?') >= 0 || literalPrefix.indexOf('#') >= 0) {
                offenders.add(invocation + " (literal prefix " + literalPrefix + ")");
            }
        }
        assertTrue(offenders.isEmpty(),
                () -> "These rows record an F6-class vulnerability for a reference that sits after a"
                        + " '?' or a '#' in the template's own text, which puts it in the query or"
                        + " the fragment, where it cannot change the URL's origin: " + offenders
                        + "\nEither the verdict is wrong, or url() has started doing something this"
                        + " file does not model - in which case it is a new finding.");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static long count(String haystack, char needle) {
        return haystack.chars().filter(c -> c == needle).count();
    }

    private static int attributeContextOf(String attributeName) {
        try {
            return new CanoeStateProbe().feed("<x " + attributeName + "=\"").attributeContext();
        } catch (IOException e) {
            throw new AssertionError("Canoe rejected the attribute name " + attributeName, e);
        }
    }
}
