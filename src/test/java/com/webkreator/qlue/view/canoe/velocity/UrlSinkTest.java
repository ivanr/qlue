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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 *   <li><strong>Tag-name blindness.</strong> Canoe reuses {@code buf} for the attribute name at
 *       {@code Canoe.java:786}, so by the time {@code setTagAttributeContext()} runs the element name
 *       is gone. {@code src} on {@code <script>} and {@code src} on {@code <img>} therefore get the
 *       same encoder, and {@link #everyElementGetsTheSameEncoderForTheSameAttributeName} asserts
 *       byte-identical output across all nine elements rather than leaving the claim to nine notes
 *       that happen to agree. This is F6's structural cause and it is remediation item 5: any fix
 *       that lets {@code <script src>} reject an off-origin URL has to make this test fail.
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
                Arguments.of("a", "href", Canoe.CTX_URI, null),
                Arguments.of("img", "src", Canoe.CTX_URI, null),
                Arguments.of("script", "src", Canoe.CTX_URI, null),
                Arguments.of("iframe", "src", Canoe.CTX_URI, null),
                Arguments.of("embed", "src", Canoe.CTX_URI, null),
                Arguments.of("link", "href", Canoe.CTX_URI, null),
                Arguments.of("base", "href", Canoe.CTX_URI, null),
                Arguments.of("object", "data", Canoe.CTX_URI, "F7"),
                Arguments.of("form", "action", Canoe.CTX_URI, "F3"),
                // The rest of R6's names, each on an element that really carries it.
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

    /** The ones that reach {@code url()}, which since R6 and R7 is all but three. */
    static Stream<Arguments> elementsThatReachUrlEncoding() {
        return urlBearingElements().filter(a -> a.get()[2].equals(Canoe.CTX_URI));
    }

    @ParameterizedTest(name = "<{0} {1}>")
    @MethodSource("urlBearingElements")
    public void theContextOfAUrlAttributeDependsOnlyOnItsName(String element, String attribute,
                                                              int expected, String finding) {
        assertEquals(expected, CanoeTestSupport.contextAfter("<" + element + " " + attribute + "=\""),
                () -> (finding == null ? "" : finding + ": ")
                        + "<" + element + " " + attribute + "> must produce "
                        + CanoeTestSupport.contextName(expected) + " but produced "
                        + CanoeTestSupport.contextName(
                                CanoeTestSupport.contextAfter("<" + element + " " + attribute + "=\"")));
    }

    // ------------------------------------------------------------------
    // Tag-name blindness (F6's structural cause, remediation item 5)
    // ------------------------------------------------------------------

    /**
     * The same attribute name on nine different elements produces byte-identical output.
     *
     * <p>This is the assertion the plan asks for directly, and it is worth having as an equality
     * rather than as seven separate expectations: the claim is not "each of these is percent-encoded"
     * but "Canoe cannot tell them apart", and only a comparison says the second thing.
     *
     * <p>The impact of the seven is not equal and that is the finding. An off-origin {@code <img src>}
     * leaks a referrer and a load; an off-origin {@code <script src>} is arbitrary JavaScript running
     * with the page's full privileges; an off-origin {@code <base href>} retargets every relative URL
     * on the rest of the document. Canoe applies one encoder to all of them because the tag name is
     * already gone.
     */
    @Test
    public void everyElementGetsTheSameEncoderForTheSameAttributeName() {
        for (Payload payload : Payloads.families("JS_URL", "PROTOCOL_RELATIVE", "ABSOLUTE_OFFSITE")) {
            Set<String> encodings = new LinkedHashSet<>();
            Map<String, String> byElement = new LinkedHashMap<>();
            for (Arguments row : (Iterable<Arguments>) elementsThatReachUrlEncoding()::iterator) {
                String element = (String) row.get()[0];
                String attribute = (String) row.get()[1];
                String rendered = CanoeTestSupport
                        .render("<" + element + " " + attribute + "=\"$data\">", payload.value())
                        .output();
                String value = rendered.substring(rendered.indexOf("=\"") + 2, rendered.length() - 2);
                encodings.add(value);
                byElement.put(element + "/" + attribute, value);
            }
            assertEquals(1, encodings.size(),
                    () -> "F6: Canoe discards the tag name at Canoe.java:786, so every one of these"
                            + " must encode " + payload.id() + " identically. If this test ever"
                            + " fails, remediation item 5 has landed and the ledger entries on"
                            + " url.script-src-prefix, url.iframe-src and url.img-src need"
                            + " re-deciding one at a time. Observed: " + byElement);
        }
    }

    /**
     * F6's exploitation vector, as the review writes it, end to end.
     *
     * <pre>{@code <script src="$cdnBase/app.js"></script>}</pre>
     *
     * <p>with {@code cdnBase = //attacker.invalid} passes through <em>byte for byte</em> — every
     * character of a protocol-relative URL is on {@code url()}'s allowlist — and the result is
     * attacker-controlled JavaScript executing with the page's full privileges.
     *
     * <p>The assertion is on the jsoup-decoded attribute value rather than on Canoe's output, which is
     * the distinction the whole review turns on. It also asserts the negative: the same template with
     * a {@code javascript:} payload really is neutralised, because {@code url()} escapes the colon.
     * Without that half the test would read as "url() does nothing", and {@code url()} is a scheme
     * filter that works — it is an origin filter that does not exist.
     */
    @Test
    public void anOffOriginCdnBaseSurvivesIntoAScriptSrcByteForByte() {
        String template = "<script src=\"$data/app.js\"></script>";

        String decoded = CanoeTestSupport.render(template, "//attacker.invalid")
                .decodedAttr("script", "src");
        assertEquals("//attacker.invalid/app.js", decoded,
                "F6: every character of a protocol-relative URL is on url()'s allowlist"
                        + " (a-zA-Z0-9 / . - # ? =), so the value reaches the src attribute"
                        + " unmodified and the browser loads the attacker's script");
        assertTrue(VerdictEvaluator.analyseUrl(decoded).isDangerous(),
                "...and the URL oracle, which follows the WHATWG parser, agrees that it leaves the"
                        + " page's origin");

        String absolute = CanoeTestSupport.render(template, "https://attacker.invalid")
                .decodedAttr("script", "src");
        assertEquals("https://attacker.invalid/app.js", absolute,
                "F6: the regex ^(https?://)([^/]+)(/.*)?$ emits the scheme verbatim and the host"
                        + " survives because '.' and '-' are allowed");

        // The half that works, so the finding is not read as "url() does nothing".
        String script = CanoeTestSupport.render(template, "javascript:alert(1)")
                .decodedAttr("script", "src");
        assertFalse(script.contains("javascript:"),
                () -> "url() must percent-escape the colon, leaving a relative path. Got: " + script);
        assertTrue(script.startsWith("javascript%3A"),
                () -> "...and specifically as %3A. Got: " + script);
        assertFalse(VerdictEvaluator.analyseUrl(script).isDangerous());
    }

    /**
     * The blindness runs the other way too, and it is worth one assertion because it bounds what a
     * fix has to look at.
     *
     * <p>{@code href}, {@code src}, {@code background}, {@code dynsrc} and {@code lowsrc} produce
     * {@code CTX_URI} on <em>any</em> element, including ones where the attribute is not a URL and
     * not defined at all. {@code <p src>} and {@code <span lowsrc>} are nonsense markup and Canoe
     * percent-encodes their values anyway. Harmless — {@code url()} is stricter than {@code html()}
     * for a plain-text value, so this direction fails closed — but it is the same missing information
     * as the dangerous direction, and remediation item 5 has to supply it once for both.
     */
    @Test
    public void aRecognisedUriNameIsAUrlContextOnAnyElementAtAll() {
        assertEquals(Canoe.CTX_URI, CanoeTestSupport.contextAfter("<p src=\""));
        assertEquals(Canoe.CTX_URI, CanoeTestSupport.contextAfter("<span lowsrc=\""));
        assertEquals(Canoe.CTX_URI, CanoeTestSupport.contextAfter("<em dynsrc=\""));
        assertEquals(Canoe.CTX_URI, CanoeTestSupport.contextAfter("<div background=\""));

        // The one consequence a template author would notice: url() mangles a plain-text value that
        // html() would have round-tripped. F15 catalogues the five ways.
        assertEquals("<div background=\"a%20b%26c\">x</div>",
                CanoeTestSupport.render("<div background=\"$data\">x</div>", "a b&c").output(),
                "url()'s allowlist has no space and no '&' (F15b), so a value that happens to sit in"
                        + " an attribute Canoe thinks is a URL is percent-escaped whatever the"
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
     *   <tr><td>path prefix</td><td>{@code src="$data/app.js"}</td><td>yes — F6</td></tr>
     *   <tr><td>path suffix</td><td>{@code href="/p/$data"}</td><td>no</td></tr>
     *   <tr><td>query parameter</td><td>{@code href="/search?q=$data"}</td><td>no</td></tr>
     *   <tr><td>fragment</td><td>{@code href="/page#$data"}</td><td>no</td></tr>
     * </table>
     *
     * <p>This is the sharpest thing that can be said to somebody auditing templates against F6, and
     * it is the opposite of what the finding's headline suggests: it is not "a URL attribute holding
     * a reference is vulnerable", it is "a URL attribute whose reference can begin the authority is
     * vulnerable". The grep the review's triage section recommends returns all five shapes.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "full URL      | <a href=\"$data\">x</a>                  | true",
            "path prefix   | <script src=\"$data/app.js\"></script>   | true",
            "path suffix   | <a href=\"/p/$data\">x</a>               | false",
            "query         | <a href=\"/search?q=$data\">x</a>        | false",
            "fragment      | <a href=\"/page#$data\">x</a>            | false",
    })
    public void theFourSubstitutionPositions(String position, String template, boolean reachesOrigin) {
        String selector = template.startsWith("<script") ? "script" : "a";
        String attribute = template.startsWith("<script") ? "src" : "href";

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
