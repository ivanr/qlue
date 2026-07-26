package com.webkreator.qlue.view.canoe;

import com.webkreator.qlue.view.Canoe;
import com.webkreator.qlue.view.CanoeStateProbe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code detectAttributePrefix()} and {@code setTagAttributeContext()}, and the interaction between
 * them. Two functions, one shared 36-character buffer, and three findings that live in the seam:
 * F4 (the value scan discards what the name established), F5 (the value scan reads buffer indices
 * nothing in the value ever wrote), and F7 (a branch that can never be taken).
 *
 * <p>These assert on {@code attributeContext} — the {@code ATTR_*} value — rather than only on the
 * {@code CTX_*} it produces. That is the level the two functions actually work at, and it separates
 * facts that {@link Canoe#currentContext()} merges: {@code ATTR_CSS}, {@code ATTR_DATA},
 * {@code ATTR_CONTENT} and {@code ATTR_ACTIONSCRIPT} all collapse to {@code CTX_SUPPRESS}, so a
 * context-only assertion cannot tell "the style attribute is being suppressed" from "the value began
 * {@code asfunction:}". {@code CanoeStateMachineTest} owns the context-level statements; this file
 * owns the mechanism.
 *
 * <p><strong>The one rule that explains almost everything here.</strong> Both functions confirm a
 * name or prefix ended by testing a fixed index for {@code '\0'}. Only two things ever write that
 * terminator, and neither is the value scan:
 *
 * <ul>
 *   <li>a <em>tag name</em> of length L writes {@code buf[0..L-1]} and its terminator at
 *       {@code buf[L]};</li>
 *   <li>an <em>attribute name</em> of length L does the same;</li>
 *   <li>an attribute <em>value</em> writes {@code buf[0..9]} at most and never a terminator
 *       ({@code Canoe.java:933} has no counterpart to {@code Canoe.java:809}).</li>
 * </ul>
 *
 * <p>So when {@code detectAttributePrefix()} tests {@code buf[10] == '\0'} to confirm the value
 * began exactly {@code javascript}, the character it reads was left there by the most recent name
 * long enough to reach index 10 — possibly in a different element. If that name's length is exactly
 * 10 the index holds its terminator and the check passes; if it is 11 or more the index holds a
 * letter and the check fails; if it is 9 or less the index still holds whatever was there before,
 * which on a freshly constructed {@link Canoe} is the zero-fill. That is F5 in one paragraph, and
 * every table below is a corollary of it.
 */
public class AttributePrefixTest {

    // ------------------------------------------------------------------
    // F4 - the context-widening reset
    // ------------------------------------------------------------------

    /**
     * F4. {@code detectAttributePrefix()} opens with an unconditional
     * {@code attributeContext = ATTR_HTML} ({@code Canoe.java:224}) and only ever assigns from there,
     * so it can widen the context but never narrow it. The first colon in a value therefore throws
     * away the classification the attribute <em>name</em> produced.
     *
     * <p>Asserted at the {@code ATTR_*} level, where the reset is directly visible: the attribute is
     * still {@code style}, but Canoe has stopped believing the value is CSS. The colon is not a
     * hostile character — it is the basic syntax of a CSS declaration and of every absolute URL — so
     * this fires on ordinary templates rather than on attacks.
     */
    @Test
    public void theFirstColonInAValueDiscardsTheNameDerivedContext() throws IOException {
        assertEquals(Canoe.ATTR_CSS, attributeContextOf("<div style=\""),
                "the name established CSS");
        assertEquals(Canoe.ATTR_CSS, attributeContextOf("<div style=\"color"),
                "and it survives every character up to the colon");
        assertEquals(Canoe.ATTR_HTML, attributeContextOf("<div style=\"color:"),
                "F4: the colon in color: reset it to ATTR_HTML");

        assertEquals(Canoe.ATTR_URI, attributeContextOf("<a href=\"/p/"),
                "a relative path has no colon, so the name-derived ATTR_URI survives");
        assertEquals(Canoe.ATTR_URI, attributeContextOf("<a href=\"http"));
        assertEquals(Canoe.ATTR_HTML, attributeContextOf("<a href=\"http://x/"),
                "F4: the colon in http: downgraded percent-encoding to entity-encoding");
    }

    /**
     * The reset is what makes F4 a security defect rather than a curiosity: it converts suppression
     * into encoding. {@code style="$c"} emits nothing, which is the design; {@code style="color:$c"}
     * emits {@code html()} output, which the HTML parser decodes back into the attacker's original
     * characters before the CSS parser ever sees them.
     */
    @Test
    public void theResetTurnsSuppressionIntoHtmlEncoding() {
        assertEquals(Canoe.CTX_SUPPRESS, CanoeTestSupport.contextAfter("<div style=\""));
        assertEquals(Canoe.CTX_HTML_ATTR, CanoeTestSupport.contextAfter("<div style=\"color:"));
        assertEquals(Canoe.CTX_URI, CanoeTestSupport.contextAfter("<a href=\"/p/"));
        assertEquals(Canoe.CTX_HTML_ATTR, CanoeTestSupport.contextAfter("<a href=\"http://x/"));
    }

    /**
     * F17. The reset does not only widen {@code ATTR_CSS} and {@code ATTR_URI} — it widens
     * {@code ATTR_JS} too, and that is a hole in the one guarantee Canoe genuinely delivers.
     *
     * <p>F1 and F2 are about event handlers Canoe fails to <em>recognise</em>. This is about one it
     * recognises perfectly: {@code onclick} resolves to {@code ATTR_JS}, and then the first colon in
     * the handler body throws that away and leaves {@code html()} encoding a value that the HTML
     * parser will decode before the JavaScript parser compiles it. A colon in the first eleven
     * characters of a handler is not exotic — an object literal ({@code f({a:1})}), a ternary
     * ({@code a?b:c}) or a label all produce one.
     *
     * <p>Neither the finding nor the review's remediation list covers this: replacing the {@code on*}
     * table with a prefix rule, which is remediation item 1, would not help at all, because the name
     * is already classified correctly. Only deleting the reset (item 3) fixes it.
     */
    @Test
    public void theResetAlsoDefeatsTheJavascriptSuppression() throws IOException {
        assertEquals(Canoe.ATTR_JS, attributeContextOf("<a onclick=\"var a="),
                "a handler with no colon nearby is suppressed, as designed");
        assertEquals(Canoe.CTX_JS, CanoeTestSupport.contextAfter("<a onclick=\"var a="));

        for (String body : List.of("f({a:", "a?b:", "x=1;a:", "return {x:", "if(a){b:")) {
            assertEquals(Canoe.ATTR_HTML, attributeContextOf("<a onclick=\"" + body),
                    "F17: " + CanoeTestSupport.quote(body) + " reset a recognised handler to HTML");
            assertEquals(Canoe.CTX_HTML_ATTR, CanoeTestSupport.contextAfter("<a onclick=\"" + body),
                    "F17: and CTX_HTML_ATTR means html(), which the parser undoes");
        }
    }

    /**
     * F17, as the thing a reader actually cares about: the attacker's characters, after the HTML
     * parser has decoded them, in a handler Canoe classified correctly.
     *
     * <p>The two templates differ by four characters of template text that no reviewer would look
     * at twice. One suppresses the payload completely; the other hands it to the JavaScript parser
     * with the quote, the parenthesis and the comment marker all intact.
     */
    @Test
    public void aColonInARecognisedHandlerLetsThePayloadReachTheJavascriptParser() {
        String payload = "');alert(1);//";

        CanoeTestSupport.RenderResult suppressed =
                CanoeTestSupport.render("<a onclick=\"f('$data')\">x</a>", payload);
        assertEquals("f('')", suppressed.decodedAttr("a", "onclick"),
                "no colon: CTX_JS, and the value is dropped entirely");

        CanoeTestSupport.RenderResult live =
                CanoeTestSupport.render("<a onclick=\"f({a:1,b:'$data'})\">x</a>", payload);
        assertTrue(live.decodedAttr("a", "onclick").contains(payload),
                "F17: after entity decoding the handler body contains " + payload
                        + " verbatim, which is arbitrary script execution");
    }

    /**
     * F17 does not depend on how the handler is quoted, or on the reference sitting inside a
     * JavaScript string literal.
     *
     * <p>Worth pinning because all three are plausible mitigations to reach for, and none of them is
     * one. The reset happens in the value scan, which runs identically for a double-quoted,
     * single-quoted and unquoted attribute value; and {@code html()} recovers every character
     * regardless of what surrounds the reference, so a value spliced straight into an expression is
     * as live as one inside a string. The unquoted row is not defeated by F11 either, because F11
     * only drops a reference that sits <em>immediately</em> after the {@code =}.
     */
    @Test
    public void theResetFiresWhateverTheQuotingAndWhereverTheReferenceSits() {
        String payload = "');alert(1);//";

        for (String template : List.of(
                "<a onclick=\"f({a:1,b:'$data'})\">x</a>",   // double-quoted, inside a JS string
                "<a onclick='f({a:1,b:\"$data\"})'>x</a>",   // single-quoted attribute value
                "<a onclick=f({a:1,b:$data})>x</a>",         // unquoted attribute value
                "<a onclick=\"f({a:1,b:$data})\">x</a>",     // outside any JS string literal
                "<a onclick=\"x?a:b;g($data)\">x</a>")) {    // colon from a ternary, not a literal

            CanoeTestSupport.RenderResult result = CanoeTestSupport.render(template, payload);
            assertTrue(result.decodedAttr("a", "onclick").contains(payload),
                    "F17: " + CanoeTestSupport.quote(template) + " handed the payload to the"
                            + " JavaScript parser verbatim; rendered "
                            + CanoeTestSupport.quote(result.output()));
        }
    }

    /**
     * And it happens even when nothing matches. Every branch in {@code detectAttributePrefix()} is a
     * positive match that returns; there is no "no prefix recognised, restore what the name said"
     * path, because the name-derived value was overwritten on entry and nothing kept a copy.
     */
    @Test
    public void theResetHappensEvenWhenNoPrefixMatches() throws IOException {
        // The last entry, a bare ":", is an F5 row rather than a prefix-table row: the value wrote
        // nothing, so the prefix checks read the residue of the attribute name "style" - buf[0] is
        // 's', which matches none of asfunction/data/javascript/livescript/mocha. It still shows the
        // reset standing, which is what this test is about, but for a reason that has nothing to do
        // with the value.
        for (String value : List.of("color:", "http:", "https:", "ftp:", "vbscript:", "x:", ":")) {
            assertEquals(Canoe.ATTR_HTML, attributeContextOf("<div style=\"" + value),
                    "no prefix branch matches " + CanoeTestSupport.quote(value)
                            + ", so the reset stands");
        }
    }

    // ------------------------------------------------------------------
    // The colon-position boundary
    // ------------------------------------------------------------------

    /**
     * The value index at and below which a colon reaches {@code detectAttributePrefix()}. Measured,
     * not assumed: the security review placed it one character earlier on its adversarial pass, then
     * corrected itself, which is exactly the kind of fact that should be settled by a test rather
     * than by re-reading the source a third time.
     */
    private static final int LAST_TRIGGERING_COLON_INDEX = 10;

    static Stream<Arguments> colonPositions() {
        List<Arguments> rows = new ArrayList<>();
        for (int index = 0; index <= 12; index++) {
            rows.add(Arguments.of(index, index <= LAST_TRIGGERING_COLON_INDEX));
        }
        return rows.stream();
    }

    /**
     * A colon at value index 0 through 10 calls {@code detectAttributePrefix()}; at index 11 and
     * beyond it does not, because the scan has already set {@code bufLen = -1} and switched itself
     * off.
     *
     * <p>{@code style} is the probe because it is the only attribute whose name-derived context is
     * both non-default and visible: {@code ATTR_CSS} before the reset, {@code ATTR_HTML} after. A
     * plain {@code title} would show nothing, since {@code ATTR_HTML} is also what the reset assigns.
     */
    @ParameterizedTest(name = "colon at value index {0} triggers the prefix scan: {1}")
    @MethodSource("colonPositions")
    public void aColonTriggersThePrefixScanUpToIndexTen(int index, boolean expectedToTrigger)
            throws IOException {
        String prefix = "<div style=\"" + repeat('a', index) + ":";
        int expected = expectedToTrigger ? Canoe.ATTR_HTML : Canoe.ATTR_CSS;

        assertEquals(expected, attributeContextOf(prefix),
                "colon at index " + index + " in " + CanoeTestSupport.quote(prefix));
    }

    /**
     * Why the boundary is 10 and not 9, stated where a future reader will find it.
     *
     * <p>{@code Canoe.java:912-937} tests {@code c == ':'} <em>before</em> it tests
     * {@code bufLen == 10}. So when the eleventh character of a value is a colon, {@code bufLen} is
     * 10 — the scan is one character from giving up — and the colon is still examined. It is only
     * the eleventh <em>non-colon</em> character that switches the scan off, and by then a colon at
     * index 11 arrives to find {@code bufLen == -1}.
     *
     * <p>The consequence in real templates is that {@code background:}, whose colon sits at index 10,
     * is affected by F4 and {@code text-decoration:} is not.
     */
    @Test
    public void theBoundaryIsSetByTheColonTestPrecedingTheLengthCutoff() throws IOException {
        assertEquals(Canoe.ATTR_HTML, attributeContextOf("<div style=\"background:"),
                "background: puts the colon at index 10, the last index that still triggers");
        assertEquals(Canoe.ATTR_CSS, attributeContextOf("<div style=\"text-decoration:"),
                "text-decoration: puts it at index 15, by which point the scan has given up");

        // The scan's own bookkeeping, which is what the two rows above are really about.
        assertEquals(10, new CanoeStateProbe().feed("<div style=\"background").bufLen(),
                "ten characters buffered and bufLen still valid, so the next colon is examined");
        assertEquals(-1, new CanoeStateProbe().feed("<div style=\"background-").bufLen(),
                "one more non-colon character and the scan switches itself off");
    }

    /**
     * Only the first colon is ever examined, in either direction. Once {@code detectAttributePrefix()}
     * has run, {@code bufLen} is set to -1 and later colons are ignored; once the scan has given up
     * on length, a later colon cannot revive it.
     *
     * <p>The second row is the interesting one, because it is F4 failing to fire: a CSS declaration
     * whose property name is long enough keeps its suppression even though the value goes on to
     * contain a colon that would otherwise have reset it.
     */
    @Test
    public void onlyTheFirstColonIsExamined() throws IOException {
        assertEquals(Canoe.ATTR_HTML, attributeContextOf("<div style=\"aaaaaaaaaa:javascript:"),
                "the first colon (index 10) reset the context; the second is never looked at");
        assertEquals(Canoe.ATTR_CSS, attributeContextOf("<div style=\"aaaaaaaaaaa:javascript:"),
                "one character longer and no colon is examined at all, so CSS suppression holds");
        assertEquals(Canoe.ATTR_JS, attributeContextOf("<a href=\"javascript:alert(1):x"),
                "and a matched prefix is not un-matched by a later colon");
    }

    // ------------------------------------------------------------------
    // Every prefix the function knows
    // ------------------------------------------------------------------

    static Stream<Arguments> valuePrefixes() {
        return Stream.of(
                // The five the function is documented to detect. Each is probed after a one-character
                // attribute name on a fresh Canoe, so that every index the check reads is either
                // written by the value itself or still zero from construction - see the class javadoc.
                prefix("asfunction", Canoe.ATTR_ACTIONSCRIPT),
                prefix("data", Canoe.ATTR_DATA),
                prefix("javascript", Canoe.ATTR_JS),
                prefix("livescript", Canoe.ATTR_JS),
                prefix("mocha", Canoe.ATTR_JS),

                // Case is folded by the value scan itself (Canoe.java:933), so no case variant evades
                // the table. This is one of the few things in this area that is simply correct.
                prefix("JAVASCRIPT", Canoe.ATTR_JS),
                prefix("JaVaScRiPt", Canoe.ATTR_JS),
                prefix("Mocha", Canoe.ATTR_JS),
                prefix("MOCHA", Canoe.ATTR_JS),
                prefix("DATA", Canoe.ATTR_DATA),
                prefix("AsFunction", Canoe.ATTR_ACTIONSCRIPT),
                prefix("LiveScript", Canoe.ATTR_JS),

                // Near-misses. Three different mechanisms reject these; see
                // nearMissesAreRejectedByThreeDifferentMechanisms for which is which.
                prefix("javascripx", Canoe.ATTR_HTML),
                prefix("javascrip", Canoe.ATTR_HTML),
                prefix("javascriptx", Canoe.ATTR_HTML),
                prefix("livescripx", Canoe.ATTR_HTML),
                prefix("livescriptx", Canoe.ATTR_HTML),
                prefix("asfunctioo", Canoe.ATTR_HTML),
                prefix("asfunctionx", Canoe.ATTR_HTML),
                prefix("datax", Canoe.ATTR_HTML),
                prefix("dat", Canoe.ATTR_HTML),
                prefix("mochax", Canoe.ATTR_HTML),
                prefix("moch", Canoe.ATTR_HTML),

                // Schemes the function has never heard of, including the ones that still execute in
                // some engines. Nothing here is a prefix match; url() is the only thing standing
                // between these and the sink, and only when the name resolved to ATTR_URI.
                prefix("vbscript", Canoe.ATTR_HTML),
                prefix("view-source", Canoe.ATTR_HTML),
                prefix("blob", Canoe.ATTR_HTML),
                prefix("file", Canoe.ATTR_HTML),
                prefix("http", Canoe.ATTR_HTML),
                prefix("https", Canoe.ATTR_HTML),

                // An empty value - "<a p=\":". This row documents F5, not the prefix table: the
                // value contributed no characters at all, so every index the checks read still
                // holds the attribute name's residue ('p' at buf[0], its terminator at buf[1]).
                // ATTR_HTML here means "buf[0] is not a, d, j, l or m", which is a fact about the
                // name "p" rather than about the value.
                prefix("", Canoe.ATTR_HTML));
    }

    private static Arguments prefix(String value, int expected) {
        return Arguments.of(value, expected);
    }

    /**
     * The complete prefix table, probed in isolation.
     *
     * <p>The probe uses a one-character attribute name ({@code <a p="}) on a freshly constructed
     * {@link Canoe}, which is the only configuration in which the function's <em>intended</em>
     * behaviour is observable: the name writes only {@code buf[0..1]}, so every terminator index the
     * checks read is still zero from the array's construction. Change the attribute name and the
     * answers change; that is F5, and
     * {@link #whichPrefixesCanMatchDependsOnTheCurrentAttributeNamesLength} is the table of it.
     */
    @ParameterizedTest(name = "value prefix \"{0}:\" -> {1}")
    @MethodSource("valuePrefixes")
    public void detectsExactlyThePrefixesItKnows(String value, int expected) throws IOException {
        assertEquals(expected, attributeContextOf("<a p=\"" + value + ":"),
                () -> "value prefix " + CanoeTestSupport.quote(value + ":") + " expected "
                        + CanoeStateProbe.attributeContextName(expected));
    }

    /**
     * The three ways a near-miss is rejected, which are worth separating because only one of them is
     * a real comparison.
     *
     * <ol>
     *   <li><strong>A read index disagrees.</strong> {@code javascripx} differs at {@code buf[9]},
     *       which the check reads. This is the function working as intended.</li>
     *   <li><strong>The terminator index disagrees.</strong> {@code datax} is rejected because
     *       {@code buf[4]} holds {@code 'x'} rather than a NUL — the only reason a longer value is
     *       caught at all, since the check never looks past the prefix. Note what this depends on:
     *       {@code buf[4]} is NUL for {@code data} only by accident of what wrote it last.</li>
     *   <li><strong>The scan gave up first.</strong> {@code javascriptx} is not rejected by any
     *       comparison — the eleventh character sets {@code bufLen = -1}, so the colon that follows
     *       never calls {@code detectAttributePrefix()} at all and the name-derived context
     *       survives untouched.</li>
     * </ol>
     *
     * <p>The third is the one with a consequence, but not the one it looks like. Both
     * {@code <div style="javascriptx:...">} and {@code <div style="javascript:...">} suppress: the
     * first because the scan gave up and {@code ATTR_CSS} survived, the second because the prefix
     * matched and produced {@code ATTR_JS}. Two different routes, one observable outcome — and the
     * routes only diverge if the commented-out {@code CTX_JS} encoder at
     * {@code Canoe.java:1074-1081} is ever enabled, at which point one of them starts emitting.
     *
     * <p>The real safety difference is the pair asserted at the end of this test:
     * {@code javascriptx:} (eleven characters, scan gives up, {@code ATTR_CSS}, suppressed) against
     * {@code text-align:} (ten characters, scan runs, no prefix matches, {@code ATTR_HTML},
     * html-encoded). Same attribute, same shape, and the longer value is the safe one.
     */
    @Test
    public void nearMissesAreRejectedByThreeDifferentMechanisms() throws IOException {
        // 1. A read index disagrees: the scan ran, and the comparison failed.
        CanoeStateProbe compared = new CanoeStateProbe().feed("<a p=\"javascripx:");
        assertEquals(Canoe.ATTR_HTML, compared.attributeContext());
        assertEquals(-1, compared.bufLen(), "the scan ran and then switched itself off");
        assertEquals('x', compared.bufferAt(9),
                "buf[9] is the index the javascript check disagreed on");

        // 2. The terminator index disagrees.
        CanoeStateProbe terminated = new CanoeStateProbe().feed("<a p=\"datax:");
        assertEquals(Canoe.ATTR_HTML, terminated.attributeContext());
        assertEquals('x', terminated.bufferAt(4), "buf[4] is not a NUL, so 'data' did not end there");

        // 3. The scan gave up before the colon arrived, so no comparison happened - and because
        // nothing ran, the name-derived context is still intact.
        assertEquals(Canoe.ATTR_CSS, attributeContextOf("<div style=\"javascriptx:"),
                "eleven characters switch the scan off, so the CSS context is never reset");
        assertEquals(Canoe.ATTR_HTML, attributeContextOf("<div style=\"text-align:"),
                "ten characters, and the same template loses its CSS suppression");
    }

    /**
     * {@code detectAttributePrefix()} is not told which attribute it is scanning, so a value prefix
     * that only makes sense in a URL is honoured anywhere. In the {@code style} case the answer is
     * accidentally conservative — {@code ATTR_JS} suppresses just as {@code ATTR_CSS} would — but it
     * is arrived at for a reason that has nothing to do with the attribute, and it is the same
     * blindness that produces F17 in the opposite direction.
     */
    @Test
    public void thePrefixScanDoesNotKnowWhichAttributeItIsScanning() throws IOException {
        assertEquals(Canoe.ATTR_JS, attributeContextOf("<div style=\"javascript:"),
                "a CSS value beginning javascript: is classified as JavaScript");
        assertEquals(Canoe.ATTR_ACTIONSCRIPT, attributeContextOf("<p title=\"asfunction:"),
                "and a plain-text title beginning asfunction: is classified as ActionScript");
    }

    // ------------------------------------------------------------------
    // F5 - the buffer residue
    // ------------------------------------------------------------------

    /**
     * The mechanical fact the whole of F5 rests on: an attribute value can never repair the index
     * its own prefix check reads.
     *
     * <p>{@code TAG_ATTR_VALUE} writes at most {@code buf[0..9]} — at {@code bufLen == 10} it stops
     * writing and sets {@code bufLen = -1} — and unlike {@code TAG_ATTR_NAME} ({@code Canoe.java:809})
     * it never appends a terminator. So {@code buf[10]}, the index that decides whether
     * {@code javascript}, {@code livescript} and {@code asfunction} matched, is untouched by the
     * value no matter how long the value is.
     */
    @Test
    public void theValueScanNeverWritesTheIndexItsOwnCheckReads() throws IOException {
        String armed = "<i placeholder=\"s\">";
        assertEquals('r', new CanoeStateProbe().feed(armed).bufferAt(10),
                "an 11-character attribute name put a letter at buf[10]");

        CanoeStateProbe probe = new CanoeStateProbe().feed(armed + "<a href=\"abcdefghijklmnop");
        assertEquals(-1, probe.bufLen(), "the value scan has long since given up");
        assertEquals('r', probe.bufferAt(10),
                "and buf[10] still holds the residue: a 16-character value could not clear it");
    }

    /**
     * The complementary fact: a name of length L writes its terminator at {@code buf[L]}. This is the
     * rule that makes every table below predictable rather than folklore.
     */
    @Test
    public void aNameOfLengthNWritesItsTerminatorAtIndexN() throws IOException {
        for (int length = 1; length <= 12; length++) {
            String name = "z" + repeat('q', length - 1);
            CanoeStateProbe probe = new CanoeStateProbe().feed("<i " + name + "=\"1\">");
            assertEquals('\0', probe.bufferAt(length),
                    "an attribute name of length " + length + " terminates at buf[" + length + "]");
            if (length > 1) {
                assertNotEquals('\0', probe.bufferAt(length - 1),
                        "and buf[" + (length - 1) + "] holds its last character");
            }
        }

        // Tag names go through the same buffer and the same terminator, so an element name is just
        // as capable of arming or repairing the value check as an attribute name is.
        assertEquals('\0', new CanoeStateProbe().feed("<blockquote>").bufferAt(10),
                "a 10-character tag name terminates exactly on the index javascript: reads");
        assertEquals('x', new CanoeStateProbe().feed("<blockquotex>").bufferAt(10));
    }

    /**
     * F5 as a table: the length of the attribute name on the <em>preceding</em> element against the
     * context a fixed {@code <a href="javascript:...">} resolves to.
     *
     * <p>The expectations are literals rather than a formula, deliberately. The point of the table is
     * that the same template is safe or unsafe depending on markup that has nothing to do with it,
     * and a formula would restate the bug's cause where a table shows its effect. When F5 is fixed
     * the whole column collapses to {@code ATTR_JS} and every row from 11 upwards fails, which is the
     * signal to update the ledger.
     */
    static Stream<Arguments> precedingNameLengths() {
        // Index 0 is unused; entry i is the outcome for a preceding attribute name of i characters.
        int[] expected = {
                -1,
                Canoe.ATTR_JS, Canoe.ATTR_JS, Canoe.ATTR_JS, Canoe.ATTR_JS, Canoe.ATTR_JS,
                Canoe.ATTR_JS, Canoe.ATTR_JS, Canoe.ATTR_JS, Canoe.ATTR_JS, Canoe.ATTR_JS,
                Canoe.ATTR_HTML, Canoe.ATTR_HTML, Canoe.ATTR_HTML, Canoe.ATTR_HTML,
                Canoe.ATTR_HTML, Canoe.ATTR_HTML, Canoe.ATTR_HTML, Canoe.ATTR_HTML,
                Canoe.ATTR_HTML, Canoe.ATTR_HTML};

        List<Arguments> rows = new ArrayList<>();
        for (int length = 1; length <= 20; length++) {
            rows.add(Arguments.of(length, expected[length]));
        }
        return rows.stream();
    }

    @ParameterizedTest(name = "preceding attribute name of {0} characters -> {1}")
    @MethodSource("precedingNameLengths")
    public void aPrecedingAttributeNameDecidesWhetherJavascriptIsRecognised(int length, int expected)
            throws IOException {
        String preceding = "<i " + "z" + repeat('q', length - 1) + "=\"1\">";
        String target = "<a href=\"javascript:";

        assertEquals(expected, attributeContextOf(preceding + target),
                () -> "a preceding attribute name of " + length + " characters gives "
                        + CanoeStateProbe.attributeContextName(expected) + " for " + target);
    }

    /**
     * The table above cannot, on its own, tell "10 characters repaired {@code buf[10]}" from
     * "10 characters left it alone and it happened to be zero" — a freshly constructed {@link Canoe}
     * has a zero-filled buffer, so both look identical. This dirties the buffer first, which
     * separates them.
     *
     * <p>The result is the part of F5 that is genuinely hard to review: a ten-character attribute
     * name anywhere on the page <em>heals</em> a template that a longer name broke, and a
     * nine-character one does not. Reordering two unrelated elements changes whether a
     * {@code javascript:} URL is suppressed.
     */
    @Test
    public void aTenCharacterNameRepairsTheBufferAndAShorterOneDoesNot() throws IOException {
        String arm = "<i placeholder=\"s\">";        // 11 characters: writes a letter to buf[10]
        String tenCharacterName = "<i xlink:href=\"s\">";  // 10: writes its terminator to buf[10]
        String nineCharacterName = "<i xlinkhref=\"s\">";  // 9: does not reach buf[10] at all
        String target = "<a href=\"javascript:";

        assertEquals(Canoe.ATTR_JS, attributeContextOf(target),
                "a fresh Canoe has a zero-filled buffer, so the check passes");
        assertEquals(Canoe.ATTR_HTML, attributeContextOf(arm + target),
                "F5: an 11-character name armed it");
        assertEquals(Canoe.ATTR_JS, attributeContextOf(arm + tenCharacterName + target),
                "F5: a 10-character name repaired it - its terminator lands on buf[10]");
        assertEquals(Canoe.ATTR_HTML, attributeContextOf(arm + nineCharacterName + target),
                "F5: a 9-character name cannot reach buf[10], so the residue survives");

        // Same three, seen at the index itself.
        assertEquals('r', new CanoeStateProbe().feed(arm).bufferAt(10));
        assertEquals('\0', new CanoeStateProbe().feed(arm + tenCharacterName).bufferAt(10));
        assertEquals('r', new CanoeStateProbe().feed(arm + nineCharacterName).bufferAt(10));
    }

    /**
     * {@code data} and {@code mocha} read {@code buf[4]} and {@code buf[5]}, which are close enough
     * to the start of the buffer that the <em>current</em> attribute's own name settles them. A name
     * of length L terminates at {@code buf[L]}, so it clears index L and fills every index below it:
     * the check at index N survives a name of length N (its terminator) or shorter (older residue,
     * zero on a fresh Canoe), and fails for any name longer than N.
     *
     * <p>So {@code javascript:} is defeated by names of 11 characters and up — the F5 exploitation
     * vector — but {@code data:} is defeated by names of five characters and up, and {@code mocha:}
     * by six. Those are not exotic lengths. This is the same defect with a much lower threshold, and
     * the finding records the indices without drawing the conclusion.
     */
    static Stream<Arguments> currentNameLengths() {
        List<Arguments> rows = new ArrayList<>();
        for (int length = 1; length <= 12; length++) {
            rows.add(Arguments.of(length,
                    length <= 4 ? Canoe.ATTR_DATA : Canoe.ATTR_HTML,     // data:  reads buf[4]
                    length <= 5 ? Canoe.ATTR_JS : Canoe.ATTR_HTML,       // mocha: reads buf[5]
                    length <= 10 ? Canoe.ATTR_JS : Canoe.ATTR_HTML));    // javascript: reads buf[10]
        }
        return rows.stream();
    }

    @ParameterizedTest(name = "attribute name of {0} characters: data:={1} mocha:={2} javascript:={3}")
    @MethodSource("currentNameLengths")
    public void whichPrefixesCanMatchDependsOnTheCurrentAttributeNamesLength(
            int length, int expectedForData, int expectedForMocha, int expectedForJavascript)
            throws IOException {
        String name = "z" + repeat('q', length - 1);

        assertEquals(expectedForData, attributeContextOf("<a " + name + "=\"data:"),
                "data: after a " + length + "-character attribute name");
        assertEquals(expectedForMocha, attributeContextOf("<a " + name + "=\"mocha:"),
                "mocha: after a " + length + "-character attribute name");
        assertEquals(expectedForJavascript, attributeContextOf("<a " + name + "=\"javascript:"),
                "javascript: after a " + length + "-character attribute name");
    }

    /**
     * The same fact in the names a template actually contains, because the parameterised table above
     * reads as a synthetic edge case and this does not.
     *
     * <p>{@code href} is four characters, so {@code <a href="data:...">} detects the prefix.
     * {@code title} is five, so {@code <a title="data:...">} never can — {@code buf[4]} holds the
     * {@code 'e'} of {@code title}. Neither template mentions a buffer.
     */
    @Test
    public void ordinaryAttributeNamesDecideWhetherTheDataPrefixIsSeen() throws IOException {
        assertEquals(Canoe.ATTR_DATA, attributeContextOf("<a href=\"data:"),
                "href is 4 characters, so its terminator lands exactly on the index data: reads");
        assertEquals(Canoe.ATTR_HTML, attributeContextOf("<a title=\"data:"),
                "F5: title is 5 characters, so buf[4] holds a letter and data: is missed");
        assertEquals(Canoe.ATTR_JS, attributeContextOf("<a title=\"mocha:"),
                "title is 5, which is exactly what mocha: needs");
        assertEquals(Canoe.ATTR_HTML, attributeContextOf("<div background=\"mocha:"),
                "F5: background is 10 characters, so mocha: is missed");
    }

    // ------------------------------------------------------------------
    // F7 - the branch that can never be taken
    // ------------------------------------------------------------------

    /**
     * F7's second half, which the finding states but does not assert: the {@code ATTR_URI} branch at
     * {@code Canoe.java:304-308} is unreachable.
     *
     * <p>Its guard is character-for-character identical to the branch above it — both test
     * {@code buf[0..3] == "data"} and {@code buf[4] == '\0'} — and the branch above returns. So any
     * input that could satisfy the second has already left the function via the first, and
     * {@code setTagAttributeContext()} can never produce {@code ATTR_URI} from a name beginning with
     * {@code d} unless that name is {@code dynsrc}.
     *
     * <p>Asserted by exhaustion over the inputs that can reach the guard at all. The guard reads five
     * fixed indices, so exactly one attribute name satisfies it — {@code data}, in any case, since
     * names are lower-cased on the way into the buffer — and every case variant of it resolves to
     * {@code ATTR_CONTENT}.
     *
     * <p>{@code CanoeStateMachineTest.derivesAttributeContextFromTheName} already records the
     * resulting contexts; this records why the second branch cannot change them.
     */
    @Test
    public void theSecondDataBranchIsUnreachable() throws IOException {
        for (String name : List.of("data", "DATA", "Data", "dAtA", "dATa")) {
            assertEquals(Canoe.ATTR_CONTENT, attributeContextOf("<object " + name + "=\"x"),
                    "the branch commented \"content\" claims every spelling of data");
        }

        // The only name beginning with 'd' that does reach ATTR_URI, so the dead branch is not
        // masking a case that some other input covers.
        assertEquals(Canoe.ATTR_URI, attributeContextOf("<img dynsrc=\"x"));
        for (String name : List.of("dat", "datum", "database", "data-id", "dataset")) {
            assertEquals(Canoe.ATTR_HTML, attributeContextOf("<p " + name + "=\"x"),
                    name + " does not reach either branch");
        }
    }

    /**
     * The two {@code data} spellings do not collide, which is worth pinning because the names
     * suggest they should. {@code setTagAttributeContext()} answers {@code ATTR_CONTENT} for the
     * attribute <em>named</em> {@code data}; {@code detectAttributePrefix()} answers
     * {@code ATTR_DATA} for a value <em>beginning</em> {@code data:}. They are different constants
     * set by different functions, and {@code currentContext()} maps both to {@code CTX_SUPPRESS} —
     * so the distinction is invisible downstream today, and would stop being invisible the moment
     * either constant is given a real encoder.
     */
    @Test
    public void theDataAttributeAndTheDataUrlPrefixAreDifferentConstants() throws IOException {
        assertEquals(Canoe.ATTR_CONTENT, attributeContextOf("<object data=\"x"));
        assertEquals(Canoe.ATTR_DATA, attributeContextOf("<a href=\"data:"));
        assertEquals(Canoe.CTX_SUPPRESS, CanoeTestSupport.contextAfter("<object data=\"x"));
        assertEquals(Canoe.CTX_SUPPRESS, CanoeTestSupport.contextAfter("<a href=\"data:"));

        // And the F4 reset applies here too: an attribute named data whose value carries any other
        // colon loses its ATTR_CONTENT and becomes html-encoded.
        assertEquals(Canoe.CTX_HTML_ATTR, CanoeTestSupport.contextAfter("<object data=\"http://x/"),
                "F4 again: the suppression of data= survives only while the value has no colon");
    }

    /**
     * {@code ATTR_ACTIONSCRIPT} is only ever produced by the {@code asfunction:} value prefix, and it
     * is the one prefix whose match makes the value <em>safer</em> than not matching: it suppresses,
     * where a miss would fall back to the {@code ATTR_HTML} reset and html-encode.
     */
    @Test
    public void asfunctionIsTheOnlyProducerOfTheActionscriptContext() throws IOException {
        assertEquals(Canoe.ATTR_ACTIONSCRIPT, attributeContextOf("<a href=\"asfunction:"));
        assertEquals(Canoe.CTX_SUPPRESS, CanoeTestSupport.contextAfter("<a href=\"asfunction:"));
        assertEquals(Canoe.ATTR_HTML, attributeContextOf("<a asfunction=\"x"),
                "there is no attribute name that produces it");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static int attributeContextOf(String prefix) throws IOException {
        return new CanoeStateProbe().feed(prefix).attributeContext();
    }

    private static String repeat(char c, int count) {
        char[] chars = new char[count];
        java.util.Arrays.fill(chars, c);
        return new String(chars);
    }

    /**
     * Guards the assumption the isolation probe rests on: {@code new Canoe(...)} starts with a
     * zero-filled buffer. Java guarantees it, but the whole of {@link #detectsExactlyThePrefixesItKnows}
     * would silently become a different test if Canoe ever pre-filled or pooled the array.
     */
    @Test
    public void aFreshCanoeHasAZeroFilledBuffer() {
        for (char c : new CanoeStateProbe().buffer()) {
            assertTrue(c == '\0', "a freshly constructed Canoe must have a zero-filled buffer");
        }
    }
}
