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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code detectAttributePrefix()} and {@code setTagAttributeContext()}, and the interaction between
 * them. Two functions, one shared 36-character buffer, and three findings that live in the seam:
 * F4 (the value scan discards what the name established), F5 (the value scan reads buffer indices
 * nothing in the value ever wrote), and F7 (a branch that can never be taken).
 *
 * <p><strong>R5, R6 and R7 have landed, and F7 is closed.</strong> The identical {@code data}
 * branch pair is gone: {@code <object data>} is a URL and {@code content} suppresses. The
 * expectations in this file that read {@code ATTR_HTML} now read {@code ATTR_UNKNOWN} wherever the
 * probe's attribute name is one nobody classified, because R5 inverted that default; where the
 * assertion was about the prefix scan rather than about the name, the observation is unchanged and
 * only the constant moved.
 *
 * <p><strong>R2 has landed, and F4/F17 are closed.</strong> {@code detectAttributePrefix()} no
 * longer opens with {@code attributeContext = ATTR_HTML}; it starts from the name-derived context
 * and only ever narrows it. Everything in this file that used to assert the reset now asserts its
 * absence — the tests were inverted rather than deleted, because they are the regression net for the
 * exact defect just fixed, and each carries its former name in its javadoc so the plan's
 * "Done when" list can still be traced to them.
 *
 * <p><strong>R3 has landed, and F5 is closed.</strong> The five value prefixes are compared as
 * bounded strings against {@code bufLen} rather than through fixed buffer indices, and {@code buf} is
 * cleared on every reuse. The F5 tables below are inverted the same way: same rows, same lengths,
 * one outcome.
 *
 * <p>These assert on {@code attributeContext} — the {@code ATTR_*} value — rather than only on the
 * {@code CTX_*} it produces. That is the level the two functions actually work at, and it separates
 * facts that {@link Canoe#currentContext()} merges: {@code ATTR_CSS}, {@code ATTR_DATA},
 * {@code ATTR_UNKNOWN} and {@code ATTR_ACTIONSCRIPT} all collapse to {@code CTX_SUPPRESS}, so a
 * context-only assertion cannot tell "the style attribute is being suppressed" from "the value began
 * {@code asfunction:}". {@code CanoeStateMachineTest} owns the context-level statements; this file
 * owns the mechanism.
 *
 * <p><strong>The one rule that used to explain almost everything here.</strong> Both functions
 * confirmed a name or prefix had ended by testing a fixed index for {@code '\0'}. Only two things
 * ever write that terminator, and neither was the value scan:
 *
 * <ul>
 *   <li>a <em>tag name</em> of length L writes {@code buf[0..L-1]} and its terminator at
 *       {@code buf[L]};</li>
 *   <li>an <em>attribute name</em> of length L does the same;</li>
 *   <li>an attribute <em>value</em> writes {@code buf[0..9]} at most and never a terminator.</li>
 * </ul>
 *
 * <p>So when {@code detectAttributePrefix()} tested {@code buf[10] == '\0'} to confirm the value
 * began exactly {@code javascript}, the character it read had been left there by the most recent
 * name long enough to reach index 10 — possibly in a different element. If that name's length was
 * exactly 10 the index held its terminator and the check passed; if it was 11 or more the index held
 * a letter and the check failed; if it was 9 or less the index still held whatever was there before,
 * which on a freshly constructed {@link Canoe} is the zero-fill. That was F5 in one paragraph, and
 * every F5 table below is a corollary of it — inverted, and kept because the corollaries are exactly
 * where a regression would show first.
 *
 * <p>The first two bullets are still true and no longer load-bearing: R4 replaced the name-side
 * comparisons with a prefix rule and bounded string compares, and R5 replaced those with lookups of
 * the buffered name in declared sets, so no classification in the class reads a fixed buffer index
 * any more. What is left of the paragraph above is history, and the buffer it describes is cleared
 * on every reuse, which is
 * {@code BufferResidueTest.theBufferHoldsNothingTheCurrentNameOrValueWrote}.
 */
public class AttributePrefixTest {

    // ------------------------------------------------------------------
    // F4 and F17 - the context-widening reset, and its absence
    // ------------------------------------------------------------------

    /**
     * F4, inverted by R2. Was {@code theFirstColonInAValueDiscardsTheNameDerivedContext}.
     *
     * <p>{@code detectAttributePrefix()} used to open with an unconditional
     * {@code attributeContext = ATTR_HTML} ({@code Canoe.java:224}) and only ever assign from there,
     * so it could widen the context but never narrow it, and the first colon in a value threw away
     * the classification the attribute <em>name</em> produced. The line is gone: the method now
     * starts from the name-derived context and leaves it alone unless one of its five prefixes
     * matches.
     *
     * <p>Asserted at the {@code ATTR_*} level, where the reset was directly visible: the attribute
     * is {@code style} and Canoe goes on believing the value is CSS. The colon was never a hostile
     * character — it is the basic syntax of a CSS declaration and of every absolute URL — which is
     * why the defect fired on ordinary templates rather than on attacks, and why its absence has to
     * be asserted on ordinary templates too.
     */
    @Test
    public void theFirstColonInAValueKeepsTheNameDerivedContext() throws IOException {
        assertEquals(Canoe.ATTR_CSS, attributeContextOf("<div style=\""),
                "the name established CSS");
        assertEquals(Canoe.ATTR_CSS, attributeContextOf("<div style=\"color"),
                "and it survives every character up to the colon");
        assertEquals(Canoe.ATTR_CSS, attributeContextOf("<div style=\"color:"),
                "R2: and past it - the colon in color: matches no prefix, so nothing is assigned");

        assertEquals(Canoe.ATTR_URI, attributeContextOf("<a href=\"/p/"),
                "a relative path has no colon, so the name-derived ATTR_URI survives");
        assertEquals(Canoe.ATTR_URI, attributeContextOf("<a href=\"http"));
        assertEquals(Canoe.ATTR_URI, attributeContextOf("<a href=\"http://x/"),
                "R2: an absolute URL keeps percent-encoding instead of dropping to entity-encoding");
    }

    /**
     * Inverted by R2. Was {@code theResetTurnsSuppressionIntoHtmlEncoding}.
     *
     * <p>The reset was what made F4 a security defect rather than a curiosity: it converted
     * suppression into encoding. {@code style="$c"} emitted nothing, which is the design;
     * {@code style="color:$c"} emitted {@code html()} output, which the HTML parser decodes back
     * into the attacker's original characters before the CSS parser ever sees them. Both templates
     * now suppress, and the two {@code href} rows now both reach {@code url()}.
     *
     * <p>Stated at the {@code CTX_*} level on purpose: {@code CTX_HTML_ATTR} is the context whose
     * encoder the HTML parser undoes, and the whole of F4 was reaching it from a sink that is not
     * plain text. Neither of these four prefixes may reach it again.
     */
    @Test
    public void noColonTurnsSuppressionIntoHtmlEncoding() {
        assertEquals(Canoe.CTX_SUPPRESS, CanoeTestSupport.contextAfter("<div style=\""));
        assertEquals(Canoe.CTX_SUPPRESS, CanoeTestSupport.contextAfter("<div style=\"color:"));
        assertEquals(Canoe.CTX_URI, CanoeTestSupport.contextAfter("<a href=\"/p/"));
        assertEquals(Canoe.CTX_URI, CanoeTestSupport.contextAfter("<a href=\"http://x/"));
    }

    /**
     * F17, inverted by R2. Was {@code theResetAlsoDefeatsTheJavascriptSuppression}.
     *
     * <p>The reset did not only widen {@code ATTR_CSS} and {@code ATTR_URI} — it widened
     * {@code ATTR_JS} too, which was a hole in the one guarantee Canoe genuinely delivers.
     *
     * <p>F1 and F2 were about event handlers Canoe failed to <em>recognise</em>, and R4 closed them.
     * This is about one it recognised perfectly all along: {@code onclick} resolves to
     * {@code ATTR_JS}, and the first colon in the
     * handler body used to throw that away and leave {@code html()} encoding a value that the HTML
     * parser decodes before the JavaScript parser compiles it. A colon in the first eleven
     * characters of a handler is not exotic — an object literal ({@code f({a:1})}), a ternary
     * ({@code a?b:c}) or a label all produce one, which is why the five bodies below are ordinary
     * JavaScript rather than attacks.
     *
     * <p>Worth keeping the note that R4 did not reach this: replacing the {@code on*} table with a
     * prefix rule did not help here at all, because the name was already classified correctly and
     * the value scan discarded the answer afterwards. Only deleting the reset closed it, which is
     * why R2 led the phase and R4 followed.
     */
    @Test
    public void theJavascriptSuppressionSurvivesAColonInTheHandlerBody() throws IOException {
        assertEquals(Canoe.ATTR_JS, attributeContextOf("<a onclick=\"var a="),
                "a handler with no colon nearby is suppressed, as designed");
        assertEquals(Canoe.CTX_JS, CanoeTestSupport.contextAfter("<a onclick=\"var a="));

        for (String body : List.of("f({a:", "a?b:", "x=1;a:", "return {x:", "if(a){b:")) {
            assertEquals(Canoe.ATTR_JS, attributeContextOf("<a onclick=\"" + body),
                    "R2: " + CanoeTestSupport.quote(body)
                            + " must leave a recognised handler classified as JavaScript");
            assertEquals(Canoe.CTX_JS, CanoeTestSupport.contextAfter("<a onclick=\"" + body),
                    "R2: and CTX_JS means the value is dropped rather than html()-encoded");
        }
    }

    /**
     * F17 at the sink, inverted by R2. Was
     * {@code aColonInARecognisedHandlerLetsThePayloadReachTheJavascriptParser}.
     *
     * <p>The two templates differ by four characters of template text that no reviewer would look
     * at twice. One used to suppress the payload completely and the other handed it to the
     * JavaScript parser with the quote, the parenthesis and the comment marker all intact; they
     * are now the same template as far as the reference is concerned.
     */
    @Test
    public void aColonInARecognisedHandlerNoLongerLetsThePayloadThrough() {
        String payload = "');alert(1);//";

        CanoeTestSupport.RenderResult withoutColon =
                CanoeTestSupport.render("<a onclick=\"f('$data')\">x</a>", payload);
        assertEquals("f('')", withoutColon.decodedAttr("a", "onclick"),
                "no colon: CTX_JS, and the value is dropped entirely");

        CanoeTestSupport.RenderResult withColon =
                CanoeTestSupport.render("<a onclick=\"f({a:1,b:'$data'})\">x</a>", payload);
        assertEquals("f({a:1,b:''})", withColon.decodedAttr("a", "onclick"),
                "R2: the colon changes nothing, so the handler body is the template's own text with"
                        + " an empty string literal where the reference was");
        assertFalse(withColon.decodedAttr("a", "onclick").contains(payload),
                "R2: no character of the payload reaches the JavaScript parser");
    }

    /**
     * Inverted by R2. Was {@code theResetFiresWhateverTheQuotingAndWhereverTheReferenceSits}.
     *
     * <p>The reset happened in the value scan, which runs identically for a double-quoted,
     * single-quoted and unquoted attribute value, and {@code html()} recovered every character
     * regardless of what surrounded the reference — so none of the three obvious mitigations was
     * one. The same five shapes now all suppress, which is the statement worth keeping: the fix has
     * to be independent of quoting and of where in the value the reference sits, or it has only
     * moved the boundary.
     *
     * <p>The unquoted row is a special case for a reason unrelated to R2: F11 drops a reference that
     * sits <em>immediately</em> after the {@code =}, and this one does not, so it genuinely
     * exercises {@code TAG_ATTR_VALUE} with {@code QUOTE_NONE}.
     */
    @Test
    public void nothingFiresWhateverTheQuotingAndWhereverTheReferenceSits() {
        String payload = "');alert(1);//";

        for (String template : List.of(
                "<a onclick=\"f({a:1,b:'$data'})\">x</a>",   // double-quoted, inside a JS string
                "<a onclick='f({a:1,b:\"$data\"})'>x</a>",   // single-quoted attribute value
                "<a onclick=f({a:1,b:$data})>x</a>",         // unquoted attribute value
                "<a onclick=\"f({a:1,b:$data})\">x</a>",     // outside any JS string literal
                "<a onclick=\"x?a:b;g($data)\">x</a>")) {    // colon from a ternary, not a literal

            CanoeTestSupport.RenderResult result = CanoeTestSupport.render(template, payload);
            assertEquals(CanoeTestSupport.render(template, "").output(), result.output(),
                    "R2: " + CanoeTestSupport.quote(template) + " must render byte-identically to"
                            + " one with an empty value; got "
                            + CanoeTestSupport.quote(result.output()));
        }
    }

    /**
     * Inverted by R2. Was {@code theResetHappensEvenWhenNoPrefixMatches}, and it is the row that
     * states the fix rather than one of its consequences: every branch in
     * {@code detectAttributePrefix()} is a positive match that assigns, and there is no longer any
     * code path at all that runs when none of them does. R3 reshaped the branches — five unrolled
     * chains became three length-checked comparisons — without changing that.
     */
    @Test
    public void nothingHappensWhenNoPrefixMatches() throws IOException {
        // The last entry, a bare ":", used to be an F5 row rather than a prefix-table row: the value
        // wrote nothing, so the prefix checks read the residue of the attribute name "style" -
        // buf[0] held its 's', which matched none of asfunction/data/javascript/livescript/mocha.
        // Since R3 the buffer is cleared when the value starts and the comparison is length-checked,
        // so the row is ordinary: bufLen is 0, which is not the length of any of the five prefixes,
        // and no index the value did not write is ever read.
        for (String value : List.of("color:", "http:", "https:", "ftp:", "vbscript:", "x:", ":")) {
            assertEquals(Canoe.ATTR_CSS, attributeContextOf("<div style=\"" + value),
                    "no prefix branch matches " + CanoeTestSupport.quote(value)
                            + ", so the name-derived ATTR_CSS is left alone");
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
     * off. R2 keeps that window exactly as it was and removes its consequence, so the test now
     * asserts both halves separately: the window is measured against the scan's own bookkeeping, and
     * the context is required to be the name-derived one at <em>every</em> index.
     *
     * <p>{@code style} is still the probe because it is the only attribute whose name-derived
     * context is both non-default and visible. Before R2 the two columns were the same fact — the
     * context was {@code ATTR_HTML} exactly where the scan ran. That they are now independent is the
     * fix.
     */
    @ParameterizedTest(name = "colon at value index {0} triggers the prefix scan: {1}")
    @MethodSource("colonPositions")
    public void aColonAtAnyIndexLeavesTheNameDerivedContextAlone(int index,
                                                                 boolean expectedToTrigger)
            throws IOException {
        String value = repeat('a', index);
        String prefix = "<div style=\"" + value + ":";

        assertEquals(Canoe.ATTR_CSS, attributeContextOf(prefix),
                "R2: colon at index " + index + " in " + CanoeTestSupport.quote(prefix)
                        + " must leave the name-derived ATTR_CSS alone");

        // The window itself, measured where it is still observable: bufLen is non-negative for as
        // long as the scan is willing to look at the next character.
        boolean scanStillArmed =
                new CanoeStateProbe().feed("<div style=\"" + value).bufLen() >= 0;
        assertEquals(expectedToTrigger, scanStillArmed,
                "the 0-10 window is unchanged by R2; only its consequence is gone. Index " + index);
    }

    /**
     * Why the boundary is 10 and not 9, stated where a future reader will find it.
     *
     * <p>{@code Canoe.java} tests {@code c == ':'} <em>before</em> it tests {@code bufLen == 10}. So
     * when the eleventh character of a value is a colon, {@code bufLen} is 10 — the scan is one
     * character from giving up — and the colon is still examined. It is only the eleventh
     * <em>non-colon</em> character that switches the scan off, and by then a colon at index 11
     * arrives to find {@code bufLen == -1}.
     *
     * <p>The consequence in real templates used to be that {@code background:}, whose colon sits at
     * index 10, was affected by F4 and {@code text-decoration:} was not. After R2 the two agree, and
     * the boundary survives only as the bound on how much of a value the scan reads — which is worth
     * keeping measured, because R3 rewrote the comparison the scan feeds and kept the window as it
     * was.
     */
    @Test
    public void theBoundaryIsSetByTheColonTestPrecedingTheLengthCutoff() throws IOException {
        assertEquals(Canoe.ATTR_CSS, attributeContextOf("<div style=\"background:"),
                "background: puts the colon at index 10, the last index that still reaches the scan"
                        + " - and reaching it now changes nothing");
        assertEquals(Canoe.ATTR_CSS, attributeContextOf("<div style=\"text-decoration:"),
                "text-decoration: puts it at index 15, by which point the scan has given up");

        // The scan's own bookkeeping, which is the only place the boundary is still visible.
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
     * <p>After R2 this is a statement about <em>narrowing</em> rather than about widening, and it is
     * the direction that still has a consequence: a value whose first colon matches nothing burns
     * the one look the scan gets, so a {@code javascript:} further along is never seen. That is not
     * a new defect — the same value reaches {@code url()} either way, and the prefixes the scan can
     * assign all suppress — but it is the shape of the remaining fail-open. R3 rewrote the
     * comparison and deliberately kept this behaviour, which is why it stays pinned.
     */
    @Test
    public void onlyTheFirstColonIsExamined() throws IOException {
        assertEquals(Canoe.ATTR_URI, attributeContextOf("<a href=\"/a:javascript:"),
                "the first colon (index 2) matched nothing and switched the scan off, so the"
                        + " javascript: after it is never looked at and ATTR_URI stands");
        assertEquals(Canoe.ATTR_JS, attributeContextOf("<a href=\"javascript:alert(1):x"),
                "and a matched prefix is not un-matched by a later colon");

        assertEquals(Canoe.ATTR_CSS, attributeContextOf("<div style=\"aaaaaaaaaa:javascript:"),
                "R2: the first colon (index 10) is examined, matches nothing, and leaves ATTR_CSS");
        assertEquals(Canoe.ATTR_CSS, attributeContextOf("<div style=\"aaaaaaaaaaa:javascript:"),
                "one character longer and no colon is examined at all - same answer, which is the"
                        + " point: the two sides of the boundary have stopped differing");
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
                prefix("javascripx", Canoe.ATTR_UNKNOWN),
                prefix("javascrip", Canoe.ATTR_UNKNOWN),
                prefix("javascriptx", Canoe.ATTR_UNKNOWN),
                prefix("livescripx", Canoe.ATTR_UNKNOWN),
                prefix("livescriptx", Canoe.ATTR_UNKNOWN),
                prefix("asfunctioo", Canoe.ATTR_UNKNOWN),
                prefix("asfunctionx", Canoe.ATTR_UNKNOWN),
                prefix("datax", Canoe.ATTR_UNKNOWN),
                prefix("dat", Canoe.ATTR_UNKNOWN),
                prefix("mochax", Canoe.ATTR_UNKNOWN),
                prefix("moch", Canoe.ATTR_UNKNOWN),

                // Schemes the function has never heard of, including the ones that still execute in
                // some engines. Nothing here is a prefix match; url() is the only thing standing
                // between these and the sink, and only when the name resolved to ATTR_URI. The
                // expectation reads ATTR_UNKNOWN rather than ATTR_HTML since R5, and the constant is
                // the NAME's answer rather than the scan's: the probe's attribute is called "p",
                // which is on none of Canoe's lists, so "no prefix matched" leaves the fail-closed
                // default where it used to leave the plain-text one. The rows still say what they
                // always said - that none of these schemes is detected - and what changed is that a
                // missed scheme in an unclassified attribute is now dropped rather than encoded.
                prefix("vbscript", Canoe.ATTR_UNKNOWN),
                prefix("view-source", Canoe.ATTR_UNKNOWN),
                prefix("blob", Canoe.ATTR_UNKNOWN),
                prefix("file", Canoe.ATTR_UNKNOWN),
                prefix("http", Canoe.ATTR_UNKNOWN),
                prefix("https", Canoe.ATTR_UNKNOWN),

                // An empty value - "<a p=\":". This row used to document F5 rather than the prefix
                // table: the value contributed no characters at all, so every index the checks read
                // still held the attribute name's residue ('p' at buf[0], its terminator at
                // buf[1]), and the fall-through meant "buf[0] is not a, d, j, l or m" - a fact about the
                // name "p" rather than about the value. Since R3 it is an ordinary row: bufLen is 0,
                // which is not the length of any of the five prefixes.
                prefix("", Canoe.ATTR_UNKNOWN));
    }

    private static Arguments prefix(String value, int expected) {
        return Arguments.of(value, expected);
    }

    /**
     * The complete prefix table, probed in isolation.
     *
     * <p>The probe uses a one-character attribute name ({@code <a p="}) on a freshly constructed
     * {@link Canoe}, which used to be the only configuration in which the function's
     * <em>intended</em> behaviour was observable: the name writes only {@code buf[0..1]}, so every
     * terminator index the checks read was still zero from the array's construction. Changing the
     * attribute name changed the answers; that was F5, and
     * {@link #whichPrefixesCanMatchNoLongerDependsOnTheCurrentAttributeNamesLength} is the table of
     * it. Since R3 the probe's shape is a convention rather than a precondition — the same rows pass
     * behind any name — and it is kept so that this table and the one below differ in one variable.
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
     *   <li><strong>A character disagrees.</strong> {@code javascripx} differs at index 9. This is
     *       the comparison working as intended.</li>
     *   <li><strong>The length disagrees.</strong> {@code datax} is rejected because five buffered
     *       characters are not four — the only reason a longer value is caught at all, since the
     *       comparison never looks past the prefix. Before R3 this was a NUL test at {@code buf[4]}
     *       rather than a length test, and {@code buf[4]} held a NUL for {@code data} only by
     *       accident of what had written it last; that accident was F5.</li>
     *   <li><strong>The scan gave up first.</strong> {@code javascriptx} is not rejected by any
     *       comparison — the eleventh character sets {@code bufLen = -1}, so the colon that follows
     *       never calls {@code detectAttributePrefix()} at all.</li>
     * </ol>
     *
     * <p>The three used to produce two different outcomes, and the pair at the end of this test was
     * the proof: {@code javascriptx:} (eleven characters, scan gives up, {@code ATTR_CSS},
     * suppressed) against {@code text-align:} (ten characters, scan runs, no prefix matches,
     * {@code ATTR_HTML}, html-encoded) — same attribute, same shape, and the longer value was the
     * safe one. After R2 all three mechanisms end in the same place, because "the comparison failed"
     * and "no comparison happened" have become the same instruction, and the pair is kept as the
     * assertion that they still are.
     *
     * <p>R3 merged the first two into one length-checked comparison and left the third exactly as it
     * was, which is the thing this test now guards: the scan's ten-character window is still what
     * bounds how much of a value is read, and a fix that widened it would change which values reach
     * the comparison at all.
     */
    @Test
    public void nearMissesAreRejectedByThreeDifferentMechanisms() throws IOException {
        // 1. A character disagrees: the scan ran, and the comparison failed.
        CanoeStateProbe compared = new CanoeStateProbe().feed("<a p=\"javascripx:");
        assertEquals(Canoe.ATTR_UNKNOWN, compared.attributeContext(),
                "the name 'p' is on none of the lists, so ATTR_UNKNOWN here is the name's answer and"
                        + " not the prefix scan's - it was ATTR_HTML until R5 inverted the default,"
                        + " and the observation this line makes is the same either way");
        assertEquals(-1, compared.bufLen(), "the scan ran and then switched itself off");
        assertEquals('x', compared.bufferAt(9),
                "buf[9] is the character the javascript comparison disagreed on");

        // 2. The length disagrees.
        CanoeStateProbe terminated = new CanoeStateProbe().feed("<a p=\"datax:");
        assertEquals(Canoe.ATTR_UNKNOWN, terminated.attributeContext());
        assertEquals('x', terminated.bufferAt(4),
                "R3: five characters were buffered where 'data' needs exactly four. Before R3 this"
                        + " was read as \"buf[4] is not a NUL\", which is the same answer for a"
                        + " different and much less reliable reason");

        // 3. The scan gave up before the colon arrived, so no comparison happened.
        assertEquals(Canoe.ATTR_CSS, attributeContextOf("<div style=\"javascriptx:"),
                "eleven characters switch the scan off, so nothing runs");
        assertEquals(Canoe.ATTR_CSS, attributeContextOf("<div style=\"text-align:"),
                "R2: ten characters, the scan runs and matches nothing, and the same template keeps"
                        + " its CSS suppression - the two mechanisms now agree");
    }

    /**
     * {@code detectAttributePrefix()} is not told which attribute it is scanning, so a value prefix
     * that only makes sense in a URL is honoured anywhere. In the {@code style} case the answer is
     * accidentally conservative — {@code ATTR_JS} suppresses just as {@code ATTR_CSS} would — but it
     * is arrived at for a reason that has nothing to do with the attribute, and it was the same
     * blindness that produced F17 in the opposite direction.
     *
     * <p>After R2 the blindness only ever costs a suppression the attribute did not ask for, never a
     * suppression the attribute did ask for, because every context this method can assign emits
     * nothing. That is the whole reason the narrowing half of the method was safe to keep.
     */
    @Test
    public void thePrefixScanDoesNotKnowWhichAttributeItIsScanning() throws IOException {
        assertEquals(Canoe.ATTR_JS, attributeContextOf("<div style=\"javascript:"),
                "a CSS value beginning javascript: is classified as JavaScript");
        assertEquals(Canoe.ATTR_ACTIONSCRIPT, attributeContextOf("<p title=\"asfunction:"),
                "and a plain-text title beginning asfunction: is classified as ActionScript");
    }

    // ------------------------------------------------------------------
    // F5 - the buffer residue, and its absence
    // ------------------------------------------------------------------

    /**
     * Inverted by R3. Was {@code theValueScanNeverWritesTheIndexItsOwnCheckReads}, and it stated the
     * mechanical fact the whole of F5 rested on: an attribute value could never repair the index its
     * own prefix check read.
     *
     * <p>{@code TAG_ATTR_VALUE} writes at most {@code buf[0..9]} — at {@code bufLen == 10} it stops
     * writing and sets {@code bufLen = -1} — and unlike {@code TAG_ATTR_NAME} it never appends a
     * terminator. So {@code buf[10]}, the index that used to decide whether {@code javascript},
     * {@code livescript} and {@code asfunction} matched, was untouched by the value no matter how
     * long the value was, and whatever an earlier name had left there was the answer.
     *
     * <p>The value scan still writes no terminator — that half is unchanged, and it is why a fix
     * confined to the value scan would have been the wrong one. What changed is both sides of the
     * problem: nothing reads index 10 any more, and the buffer is cleared when the value starts, so
     * there is nothing at index 10 to read. Both are asserted here, because either alone would leave
     * the finding one edit away from returning.
     */
    @Test
    public void theValueScanStillWritesNoTerminatorAndNothingNeedsItTo() throws IOException {
        String armed = "<i placeholder=\"s\">";
        assertEquals('\0', new CanoeStateProbe().feed(armed).bufferAt(10),
                "R3: an 11-character attribute name used to leave its 'r' at buf[10]; the buffer is"
                        + " cleared when that element's value starts");

        CanoeStateProbe probe = new CanoeStateProbe().feed(armed + "<a href=\"abcdefghijklmnop");
        assertEquals(-1, probe.bufLen(), "the value scan has long since given up");
        assertEquals('j', probe.bufferAt(9),
                "the value wrote the ten characters it is allowed to write");
        assertEquals('\0', probe.bufferAt(10),
                "R3: and index 10 holds nothing, because the value never writes a terminator and no"
                        + " longer inherits one either");

        // The classification the residue used to decide, on the same page.
        assertEquals(Canoe.ATTR_JS, attributeContextOf(armed + "<a href=\"javascript:"),
                "R3: which is why the prefix is recognised behind an 11-character name");
    }

    /**
     * The complementary fact: a name of length L writes its terminator at {@code buf[L]}. This is the
     * rule that made every F5 table predictable rather than folklore. Nothing classifies by fixed
     * index any more — R4 replaced the name-side comparisons and R5 replaced those with set lookups
     * of the buffered name — but the terminator is still what {@code bufLen} counts and therefore
     * still what decides where the name the lookup reads ends, so it stays measured.
     *
     * <p>Probed at the {@code =} rather than after the whole element since R3: the buffer is cleared
     * when the attribute's value starts, so a probe that fed the closing quote would be reading a
     * cleared buffer and would assert nothing about the name at all.
     */
    @Test
    public void aNameOfLengthNWritesItsTerminatorAtIndexN() throws IOException {
        for (int length = 1; length <= 12; length++) {
            String name = "z" + repeat('q', length - 1);
            CanoeStateProbe probe = new CanoeStateProbe().feed("<i " + name + "=");
            assertEquals('\0', probe.bufferAt(length),
                    "an attribute name of length " + length + " terminates at buf[" + length + "]");
            if (length > 1) {
                assertNotEquals('\0', probe.bufferAt(length - 1),
                        "and buf[" + (length - 1) + "] holds its last character");
            }
            assertEquals('\0', probe.bufferAt(length + 1),
                    "R3: and nothing beyond the terminator, because the buffer was cleared before"
                            + " the name was written into it");
        }

        // Tag names go through the same buffer and the same terminator. That used to make an element
        // name just as capable of arming or repairing the value check as an attribute name; it is now
        // only a statement about how a tag name is stored.
        assertEquals('\0', new CanoeStateProbe().feed("<blockquote>").bufferAt(10),
                "a 10-character tag name terminates at buf[10]");
        assertEquals('x', new CanoeStateProbe().feed("<blockquotex>").bufferAt(10));
    }

    /**
     * F5 as a table, inverted by R3. Was
     * {@code aPrecedingAttributeNameDecidesWhetherJavascriptIsRecognised}: the length of the
     * attribute name on the <em>preceding</em> element against the context a fixed
     * {@code <a href="javascript:...">} resolves to.
     *
     * <p>The expectations are literals rather than a formula, deliberately, and the twenty rows are
     * kept. The point of the table used to be that the same template was safe or unsafe depending on
     * markup that had nothing to do with it, and a formula would have restated the bug's cause where
     * a table showed its effect. The column has now collapsed to {@code ATTR_JS}, which is what the
     * plan said the fix would look like from here; keeping the rows means a regression fails on the
     * same lengths that used to record the finding.
     *
     * <p>Both earlier stages are worth remembering when reading a failure. Before R2 a missed prefix
     * left the reset's {@code ATTR_HTML} and the payload was html-encoded; between R2 and R3 it left
     * the name-derived {@code ATTR_URI} and the payload was url-encoded into a {@code javascript:}
     * URL, which the HTML Standard percent-decodes before compiling — a different encoder and not a
     * fix. A row that fails with {@code ATTR_URI} is F5 returning, not a cosmetic change.
     */
    static Stream<Arguments> precedingNameLengths() {
        List<Arguments> rows = new ArrayList<>();
        for (int length = 1; length <= 20; length++) {
            rows.add(Arguments.of(length, Canoe.ATTR_JS));
        }
        return rows.stream();
    }

    @ParameterizedTest(name = "preceding attribute name of {0} characters -> {1}")
    @MethodSource("precedingNameLengths")
    public void noPrecedingAttributeNameDecidesWhetherJavascriptIsRecognised(int length,
                                                                            int expected)
            throws IOException {
        String preceding = "<i " + "z" + repeat('q', length - 1) + "=\"1\">";
        String target = "<a href=\"javascript:";

        assertEquals(expected, attributeContextOf(preceding + target),
                () -> "a preceding attribute name of " + length + " characters must still give "
                        + CanoeStateProbe.attributeContextName(expected) + " for " + target);
    }

    /**
     * Inverted by R3. Was {@code aTenCharacterNameRepairsTheBufferAndAShorterOneDoesNot}.
     *
     * <p>The table above cannot, on its own, tell "10 characters repaired {@code buf[10]}" from
     * "10 characters left it alone and it happened to be zero" — a freshly constructed {@link Canoe}
     * has a zero-filled buffer, so both looked identical. Dirtying the buffer first separated them,
     * and the result was the part of F5 that was genuinely hard to review: a ten-character attribute
     * name anywhere on the page <em>healed</em> a template that a longer name broke, and a
     * nine-character one did not, so reordering two unrelated elements changed whether a
     * {@code javascript:} URL was suppressed.
     *
     * <p>The three real names are kept — {@code placeholder}, {@code xlink:href}, {@code xlinkhref},
     * the ones the finding is written around — because "these three specific names now agree" is a
     * sharper regression net than three synthetic lengths.
     */
    @Test
    public void noNameLengthArmsOrRepairsTheBuffer() throws IOException {
        String arm = "<i placeholder=\"s\">";        // 11 characters: used to write a letter to buf[10]
        String tenCharacterName = "<i xlink:href=\"s\">";  // 10: used to write its terminator there
        String nineCharacterName = "<i xlinkhref=\"s\">";  // 9: never reached buf[10] at all
        String target = "<a href=\"javascript:";

        assertEquals(Canoe.ATTR_JS, attributeContextOf(target),
                "the bare target, which was safe before R3 as well");
        assertEquals(Canoe.ATTR_JS, attributeContextOf(arm + target),
                "R3: an 11-character name no longer arms anything");
        assertEquals(Canoe.ATTR_JS, attributeContextOf(arm + tenCharacterName + target),
                "R3: and a 10-character name has nothing left to repair");
        assertEquals(Canoe.ATTR_JS, attributeContextOf(arm + nineCharacterName + target),
                "R3: nor does a 9-character name leave residue behind for the value to trip over");

        // Same three, seen at the index itself.
        assertEquals('\0', new CanoeStateProbe().feed(arm).bufferAt(10));
        assertEquals('\0', new CanoeStateProbe().feed(arm + tenCharacterName).bufferAt(10));
        assertEquals('\0', new CanoeStateProbe().feed(arm + nineCharacterName).bufferAt(10));
    }

    /**
     * Inverted by R3. Was {@code whichPrefixesCanMatchDependsOnTheCurrentAttributeNamesLength}.
     *
     * <p>{@code data} and {@code mocha} read {@code buf[4]} and {@code buf[5]}, which are close
     * enough to the start of the buffer that the <em>current</em> attribute's own name settled them.
     * A name of length L terminates at {@code buf[L]}, so it clears index L and fills every index
     * below it: the check at index N survived a name of length N (its terminator) or shorter (older
     * residue, zero on a fresh Canoe), and failed for any name longer than N.
     *
     * <p>So {@code javascript:} was defeated by names of 11 characters and up — the F5 exploitation
     * vector — but {@code data:} was defeated by names of five characters and up, and {@code mocha:}
     * by six. Those are not exotic lengths. It was the same defect with a much lower threshold, and
     * the finding records the indices without drawing the conclusion; this table is where the
     * conclusion was drawn, so it is where the fix has to be shown holding at every length rather
     * than only at the one the exploit used.
     */
    static Stream<Arguments> currentNameLengths() {
        List<Arguments> rows = new ArrayList<>();
        for (int length = 1; length <= 12; length++) {
            rows.add(Arguments.of(length,
                    Canoe.ATTR_DATA,     // data:       compared against bufLen == 4
                    Canoe.ATTR_JS,       // mocha:      compared against bufLen == 5
                    Canoe.ATTR_JS));     // javascript: compared against bufLen == 10
        }
        return rows.stream();
    }

    @ParameterizedTest(name = "attribute name of {0} characters: data:={1} mocha:={2} javascript:={3}")
    @MethodSource("currentNameLengths")
    public void whichPrefixesCanMatchNoLongerDependsOnTheCurrentAttributeNamesLength(
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
     * Inverted by R3. Was {@code ordinaryAttributeNamesDecideWhetherTheDataPrefixIsSeen}, and it is
     * the same fact in the names a template actually contains, because the parameterised table above
     * reads as a synthetic edge case and this does not.
     *
     * <p>{@code href} is four characters, so {@code <a href="data:...">} detected the prefix.
     * {@code title} is five, so {@code <a title="data:...">} never could — {@code buf[4]} held the
     * {@code 'e'} of {@code title}. Neither template mentions a buffer, and one character of
     * attribute name was the whole difference; the four now agree.
     */
    @Test
    public void ordinaryAttributeNamesNoLongerDecideWhetherTheDataPrefixIsSeen() throws IOException {
        assertEquals(Canoe.ATTR_DATA, attributeContextOf("<a href=\"data:"),
                "href is 4 characters, which used to be the only reason this one worked");
        assertEquals(Canoe.ATTR_DATA, attributeContextOf("<a title=\"data:"),
                "R3: title is 5 characters, so buf[4] used to hold a letter and data: was missed");
        assertEquals(Canoe.ATTR_JS, attributeContextOf("<a title=\"mocha:"),
                "title is 5, which is exactly what mocha: used to need");
        assertEquals(Canoe.ATTR_JS, attributeContextOf("<div background=\"mocha:"),
                "R3: background is 10 characters, so mocha: used to be missed here - and between R2"
                        + " and R3 the miss left background's own ATTR_URI, which emitted the value"
                        + " into a mocha: URL rather than suppressing it");
    }

    // ------------------------------------------------------------------
    // F7 - the branch pair, resolved by R7
    // ------------------------------------------------------------------

    /**
     * <strong>Retired and inverted by R7.</strong> Was {@code theSecondDataBranchIsUnreachable}, and
     * what it asserted was F7's second half, which the finding states but never proved: the
     * {@code ATTR_URI} branch at {@code Canoe.java:304-308} could not be taken.
     *
     * <p>The reasoning, kept because it is what the fix had to answer. The two branches' guards were
     * character-for-character identical — both tested {@code buf[0..3] == "data"} and
     * {@code buf[4] == '\0'} — and the first one returned, so any input that could satisfy the
     * second had already left the method through it. The author's own {@code XXX} marker sat above
     * the pair asking which was correct, and the comments answered differently from the code: the
     * first was commented {@code // content} and compared {@code data}. Two consequences, and
     * neither was visible from the branch that ran: {@code <object data>} silently dropped its
     * value, which is a functional bug a developer routes around with {@code $_x.asis()}, and there
     * was no test for {@code content} anywhere in the class, which is the {@code <meta
     * http-equiv=refresh>} row of F3.
     *
     * <p>R7's answer, asserted below. {@code data} is a URL — it is {@code <object data>} — so it
     * joins the URL name set and reaches {@code url()}. {@code content} is a URL on exactly one
     * element and attribute-value combination and Canoe cannot see either yet (R8, R10), so it
     * suppresses, which is also where R5's fail-closed default would have put it. Both branches are
     * gone; the classification is a set lookup, and a set cannot hold the same name twice.
     */
    @Test
    public void theDataBranchPairIsResolved() throws IOException {
        for (String name : List.of("data", "DATA", "Data", "dAtA", "dATa")) {
            assertEquals(Canoe.ATTR_URI_RESOURCE, attributeContextOf("<object " + name + "=\"x"),
                    "R7: <object data> is a URL, in every spelling the name scan lower-cases; R9"
                            + " narrows it to the resource-loading variant because <object data> loads"
                            + " a subresource");
        }

        assertEquals(Canoe.ATTR_UNKNOWN, attributeContextOf("<meta content=\"x"),
                "R7: and 'content' suppresses rather than having no classification at all");

        // The name that always reached ATTR_URI, so the pair was never masking it.
        assertEquals(Canoe.ATTR_URI, attributeContextOf("<img dynsrc=\"x"));

        // The neighbours, which reach neither and are now dropped rather than html-encoded (R5) -
        // except data-id, which is in the data- plain-text family and is text by construction.
        for (String name : List.of("dat", "datum", "database", "dataset")) {
            assertEquals(Canoe.ATTR_UNKNOWN, attributeContextOf("<p " + name + "=\"x"),
                    name + " is on none of the lists");
        }
        assertEquals(Canoe.ATTR_HTML, attributeContextOf("<p data-id=\"x"),
                "data-id is in the data-* family, which the HTML Standard reserves for the page's"
                        + " own use and gives no browser semantics at all");
    }

    /**
     * The two {@code data} spellings do not collide, which is worth pinning because the names
     * suggest they should — and since R7 the pinning has teeth it did not have before.
     *
     * <p>{@code setTagAttributeContext()} answers {@code ATTR_URI} for the attribute <em>named</em>
     * {@code data}; {@code detectAttributePrefix()} answers {@code ATTR_DATA} for a value
     * <em>beginning</em> {@code data:}. Until R7 the name's answer was {@code ATTR_CONTENT} and both
     * constants mapped to {@code CTX_SUPPRESS}, so the distinction was invisible downstream and this
     * test could not have caught a collision. Now they produce different encoders — {@code url()}
     * and the empty string — and the two halves of the assertion are genuinely different
     * observations.
     *
     * <p>The pair also states R7's answer in its most compact form: {@code <object data="data:...">}
     * is a URL attribute holding a {@code data:} URL, and it suppresses because the <em>value</em>
     * prefix narrows the name's {@code ATTR_URI} the way any of the five prefixes would.
     */
    @Test
    public void theDataAttributeAndTheDataUrlPrefixAreDifferentConstants() throws IOException {
        // The name 'data' on <object> is a resource-loading URL sink (R9's narrowing of R7's URL);
        // the value prefix 'data:' is ATTR_DATA. Different constants, different encoders.
        assertEquals(Canoe.ATTR_URI_RESOURCE, attributeContextOf("<object data=\"x"));
        assertEquals(Canoe.ATTR_DATA, attributeContextOf("<a href=\"data:"));
        assertEquals(Canoe.CTX_URI_RESOURCE, CanoeTestSupport.contextAfter("<object data=\"x"));
        assertEquals(Canoe.CTX_SUPPRESS, CanoeTestSupport.contextAfter("<a href=\"data:"));

        // Name and prefix together: the value prefix narrows, exactly as it does on href.
        assertEquals(Canoe.ATTR_DATA, attributeContextOf("<object data=\"data:"));
        assertEquals(Canoe.CTX_SUPPRESS, CanoeTestSupport.contextAfter("<object data=\"data:"));

        // The F4 reset used to apply here too: an attribute named data whose value carried any other
        // colon lost its name-derived context and became html-encoded. R2 removed that, so what the
        // value contains decides nothing unless it is one of the five prefixes.
        assertEquals(Canoe.CTX_URI_RESOURCE, CanoeTestSupport.contextAfter("<object data=\"http://x/"),
                "R2: the classification of data= no longer changes at the first colon in the value"
                        + " (R9: and it is the resource-loading URL context, since <object> loads it)");
    }

    /**
     * {@code ATTR_ACTIONSCRIPT} is only ever produced by the {@code asfunction:} value prefix.
     *
     * <p>It used to be the one prefix whose match made the value <em>safer</em> than not matching:
     * it suppresses, where a miss fell back to the reset's {@code ATTR_HTML} and html-encoded. After
     * R2 a miss falls back to the attribute's own context instead — {@code ATTR_URI} in the
     * {@code href} probed below — so the match is a narrowing like the other four rather than a
     * repair of the method's own damage.
     */
    @Test
    public void asfunctionIsTheOnlyProducerOfTheActionscriptContext() throws IOException {
        assertEquals(Canoe.ATTR_ACTIONSCRIPT, attributeContextOf("<a href=\"asfunction:"));
        assertEquals(Canoe.CTX_SUPPRESS, CanoeTestSupport.contextAfter("<a href=\"asfunction:"));
        assertEquals(Canoe.ATTR_UNKNOWN, attributeContextOf("<a asfunction=\"x"),
                "there is no attribute name that produces it - and since R5 a name nobody"
                        + " classified is ATTR_UNKNOWN rather than ATTR_HTML");
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
