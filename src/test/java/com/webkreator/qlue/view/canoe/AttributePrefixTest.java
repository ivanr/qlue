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
 * <p><strong>R2 has landed, and F4/F17 are closed.</strong> {@code detectAttributePrefix()} no
 * longer opens with {@code attributeContext = ATTR_HTML}; it starts from the name-derived context
 * and only ever narrows it. Everything in this file that used to assert the reset now asserts its
 * absence — the tests were inverted rather than deleted, because they are the regression net for the
 * exact defect just fixed, and each carries its former name in its javadoc so the plan's
 * "Done when" list can still be traced to them. F5 is untouched and R3 owns it, but note that its
 * <em>consequence</em> moved: a missed prefix now falls back to the name-derived context rather than
 * to {@code ATTR_HTML}, so the same residue that used to produce {@code html()} now produces
 * {@code url()} in a {@code href} and suppression in a {@code style}.
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
     * <p>F1 and F2 are about event handlers Canoe fails to <em>recognise</em>. This is about one it
     * recognises perfectly: {@code onclick} resolves to {@code ATTR_JS}, and the first colon in the
     * handler body used to throw that away and leave {@code html()} encoding a value that the HTML
     * parser decodes before the JavaScript parser compiles it. A colon in the first eleven
     * characters of a handler is not exotic — an object literal ({@code f({a:1})}), a ternary
     * ({@code a?b:c}) or a label all produce one, which is why the five bodies below are ordinary
     * JavaScript rather than attacks.
     *
     * <p>Worth keeping the note that R4 does not reach this: replacing the {@code on*} table with a
     * prefix rule would not have helped at all, because the name was already classified correctly
     * and the value scan discarded the answer afterwards. Only deleting the reset closed it.
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
     * {@code detectAttributePrefix()} is a positive match that returns, and there is no longer any
     * code path at all that runs when none of them does.
     */
    @Test
    public void nothingHappensWhenNoPrefixMatches() throws IOException {
        // The last entry, a bare ":", is an F5 row rather than a prefix-table row: the value wrote
        // nothing, so the prefix checks read the residue of the attribute name "style" - buf[0] is
        // 's', which matches none of asfunction/data/javascript/livescript/mocha. It reaches the
        // same outcome as the rest for a reason that has nothing to do with the value, which after
        // R2 is fine: a spurious *miss* is now a no-op rather than a downgrade.
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
     * keeping measured, because R3 rewrites that scan.
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
     * assign all suppress — but it is the shape of the remaining fail-open and is worth pinning
     * before R3 rewrites the comparison.
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
     * <p>The mechanisms remain worth separating for R3, which replaces the first two with a bounded
     * string comparison and must not accidentally change the third.
     */
    @Test
    public void nearMissesAreRejectedByThreeDifferentMechanisms() throws IOException {
        // 1. A read index disagrees: the scan ran, and the comparison failed.
        CanoeStateProbe compared = new CanoeStateProbe().feed("<a p=\"javascripx:");
        assertEquals(Canoe.ATTR_HTML, compared.attributeContext(),
                "the name 'p' is unrecognised, so ATTR_HTML here is the name's answer and not the"
                        + " prefix scan's");
        assertEquals(-1, compared.bufLen(), "the scan ran and then switched itself off");
        assertEquals('x', compared.bufferAt(9),
                "buf[9] is the index the javascript check disagreed on");

        // 2. The terminator index disagrees.
        CanoeStateProbe terminated = new CanoeStateProbe().feed("<a p=\"datax:");
        assertEquals(Canoe.ATTR_HTML, terminated.attributeContext());
        assertEquals('x', terminated.bufferAt(4), "buf[4] is not a NUL, so 'data' did not end there");

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
     *
     * <p>R2 changed what the second group holds and not the split. A missed prefix used to leave the
     * reset's {@code ATTR_HTML}; it now leaves the name-derived {@code ATTR_URI}, because the
     * attribute is {@code href}. F5 is unchanged and R3 still owns it — the prefix is still missed —
     * but the fallback is {@code url()} rather than {@code html()}. That is not a fix: the HTML
     * Standard percent-decodes a {@code javascript:} URL before compiling it, so the payload still
     * arrives. See {@code BufferResidueTest} and the {@code residue.js-url-armed-buffer} ledger row.
     */
    static Stream<Arguments> precedingNameLengths() {
        // Index 0 is unused; entry i is the outcome for a preceding attribute name of i characters.
        int[] expected = {
                -1,
                Canoe.ATTR_JS, Canoe.ATTR_JS, Canoe.ATTR_JS, Canoe.ATTR_JS, Canoe.ATTR_JS,
                Canoe.ATTR_JS, Canoe.ATTR_JS, Canoe.ATTR_JS, Canoe.ATTR_JS, Canoe.ATTR_JS,
                Canoe.ATTR_URI, Canoe.ATTR_URI, Canoe.ATTR_URI, Canoe.ATTR_URI,
                Canoe.ATTR_URI, Canoe.ATTR_URI, Canoe.ATTR_URI, Canoe.ATTR_URI,
                Canoe.ATTR_URI, Canoe.ATTR_URI};

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
        assertEquals(Canoe.ATTR_URI, attributeContextOf(arm + target),
                "F5: an 11-character name armed it, and after R2 the miss falls back to the"
                        + " name-derived ATTR_URI rather than to ATTR_HTML");
        assertEquals(Canoe.ATTR_JS, attributeContextOf(arm + tenCharacterName + target),
                "F5: a 10-character name repaired it - its terminator lands on buf[10]");
        assertEquals(Canoe.ATTR_URI, attributeContextOf(arm + nineCharacterName + target),
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
        assertEquals(Canoe.ATTR_URI, attributeContextOf("<div background=\"mocha:"),
                "F5: background is 10 characters, so mocha: is missed - and after R2 the miss leaves"
                        + " background's own ATTR_URI rather than downgrading it to ATTR_HTML");
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

        // The F4 reset used to apply here too: an attribute named data whose value carried any other
        // colon lost its ATTR_CONTENT and became html-encoded. R2 removed that, so the suppression
        // of data= no longer depends on what the value contains.
        assertEquals(Canoe.CTX_SUPPRESS, CanoeTestSupport.contextAfter("<object data=\"http://x/"),
                "R2: the suppression of data= no longer ends at the first colon in the value");
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
