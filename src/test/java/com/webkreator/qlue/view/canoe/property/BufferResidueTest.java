package com.webkreator.qlue.view.canoe.property;

import com.webkreator.qlue.view.Canoe;
import com.webkreator.qlue.view.CanoeStateProbe;
import com.webkreator.qlue.view.canoe.CanoeTestSupport;
import com.webkreator.qlue.view.canoe.corpus.Payloads;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
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
 * <strong>F5 as a property, and the property now holds.</strong> A template's security must not
 * depend on markup that appears before it.
 *
 * <p>The finding was stated as an anecdote — a {@code placeholder} attribute somewhere above a
 * {@code javascript:} URL disarmed Canoe's prefix detection — and an anecdote was the wrong shape
 * for it, because the thing that decided the outcome was not an attribute name but an
 * <em>integer</em>. So this file fixes the target and varies only the preceding markup:
 *
 * <pre>{@code <PREFIX><a href="javascript:f('$data')">x</a>}</pre>
 *
 * <p>where {@code PREFIX} is a benign element carrying one attribute whose name is 1 to 20
 * characters long. Twenty renders of the same reference, in the same template, into the same sink;
 * the property says all twenty produce identical bytes. Before R3 they fell into two groups with the
 * boundary between 10 and 11. They are now one group, and every test below is that same table with
 * its expectation collapsed.
 *
 * <h2>The mechanism that used to carry it, and what replaced it</h2>
 *
 * <p>{@code detectAttributePrefix()} confirmed the value prefix was exactly {@code javascript} by
 * testing {@code buf[10] == '\0'}. {@code buf} is a 36-character field of the whole render, and it
 * was never cleared — only {@code bufLen} was reset. The value scan writes indices 0 through 9 and
 * bails at {@code bufLen == 10} without writing, so a value could never repair index 10 itself
 * ({@code TAG_ATTR_VALUE} omits the {@code buf[bufLen++] = '\0'} that {@code TAG_ATTR_NAME} does).
 * Whatever an earlier attribute name left at index 10 therefore decided the answer: a name of fewer
 * than 10 characters never reached the index and a fresh Canoe's zero-fill survived; a name of
 * exactly 10 put its own terminator there, so a ten-character name anywhere on the page
 * <em>repaired</em> a buffer a longer one had dirtied; a name of 11 or more left a letter there and
 * the prefix was missed.
 *
 * <p>R3 changed two things, and it takes both to make the property hold:
 *
 * <ul>
 *   <li>the five prefixes are compared as bounded strings against {@code bufLen} characters, so the
 *       comparison cannot read an index the value did not write; and
 *   <li>{@code buf} is cleared on every reuse — new tag name, new attribute name, new attribute
 *       value — so there is no residue for anything else to read either.
 * </ul>
 *
 * <p>Either alone would have fixed {@code javascript:}. Both together are what make "the buffer
 * holds nothing the current name or value put there" an invariant rather than a property of one
 * comparison, which is {@link #theBufferHoldsNothingTheCurrentNameOrValueWrote}.
 *
 * <h2>What R2 had already changed here</h2>
 *
 * <p>Under F5 alone the fallback for a missed prefix was the name-derived context, which for this
 * file's fixed target is {@code href} and therefore {@code url()} — <em>a different encoder and not
 * a fix</em>, because a {@code javascript:} URL is percent-decoded by the HTML Standard's
 * javascript-URL steps before its script source is compiled, so {@code url()}'s {@code %27} arrived
 * at the JavaScript parser as an apostrophe exactly as {@code html()}'s {@code &#39;} had before R2.
 * That is why the ledger row {@code residue.js-url-armed-buffer} stayed {@code KNOWN_VULNERABLE}
 * through R2 and only R3 closes it. {@code VerdictEvaluator} still knows about that decode, and
 * {@link #anElevenCharacterNameNoLongerLetsThePayloadThrough} still applies it by hand, because the
 * assertion worth keeping is about the script source and not about the attribute value.
 *
 * <p>These tests read {@link CanoeStateProbe#bufferAt(int)} so the evidence is the byte itself, not
 * merely that the outcome changed. "The output is the same" is a symptom shared by a dozen possible
 * causes; {@code buf[10] == '\0'} whatever precedes it is the cause.
 *
 * <h2>Relationship to the other two files that touch F5</h2>
 *
 * <p>{@code AttributePrefixTest} (T10) owns the unit-level table — it drives {@code CanoeStateProbe}
 * directly and asserts {@code ATTR_*} constants. This file asserts the <em>rendered page</em>, with a
 * real payload in it, because the finding's claim was about pages. The corpus holds three cases
 * (clean, armed, repaired) whose verdicts this file's table generalises; all three are
 * {@code SUPPRESSED_BY_DESIGN} since R3, and they are kept as the ledger's own record that the three
 * pages have stopped differing.
 */
public class BufferResidueTest {

    /** The fixed target: a {@code javascript:} URL with the reference inside a string literal. */
    private static final String TARGET = "<a href=\"javascript:f('$data')\">x</a>";

    /** The payload, chosen because its first character is what closes the string literal. */
    private static final String PAYLOAD = Payloads.QUOTE_SINGLE_BREAKOUT.value();

    /** The longest preceding attribute name the table covers. */
    private static final int MAX_NAME_LENGTH = 20;

    /** The index {@code javascript}, {@code livescript} and {@code asfunction} used to test. */
    private static final int JAVASCRIPT_TERMINATOR_INDEX = 10;

    // ------------------------------------------------------------------
    // The property
    // ------------------------------------------------------------------

    /**
     * The property: preceding markup must not change the render. <strong>It no longer does.</strong>
     *
     * <p>Inverted by R3. Was {@code thePrecedingElementDecidesWhetherTheSameTemplateIsSuppressedOr}
     * {@code Encoded}, which asserted the counterexample — two distinct outputs where the property
     * demands one, split at the 10/11 boundary — because the property was false and a test asserting
     * it would have been red from the first commit. It is true now, so the assertion is the property
     * itself, and the group structure is kept in the failure message so a regression says which
     * lengths diverged rather than only that some did.
     */
    @Test
    public void thePrecedingElementDoesNotDecideAnything() {
        Map<String, List<Integer>> outputsToLengths = new LinkedHashMap<>();
        for (int length = 1; length <= MAX_NAME_LENGTH; length++) {
            String rendered = renderWithPrefix(precedingElement(length), PAYLOAD);
            outputsToLengths.computeIfAbsent(rendered, key -> new ArrayList<>()).add(length);
        }

        assertEquals(1, outputsToLengths.size(),
                () -> "R3: all twenty preceding elements must produce the same render. They fell"
                        + " into " + outputsToLengths.size() + " groups: " + outputsToLengths.values()
                        + ". A split at 10/11 means the buffer residue is back.");
        assertEquals(TARGET.replace("$data", ""),
                outputsToLengths.keySet().iterator().next(),
                "and the single outcome is CTX_JS: byte-identical to rendering with no value at all,"
                        + " which is what the prefix table exists to do");
    }

    /**
     * The consequence, stated at the sink rather than at the encoder.
     *
     * <p>Inverted by R3. Was {@code anElevenCharacterNameLetsThePayloadReachTheJavaScriptParser}.
     *
     * <p>An 11-character preceding name used to be the whole exploit. Before R2 the HTML parser
     * decoded {@code html()}'s character references once before the {@code javascript:} URL was
     * compiled; after R2 the javascript-URL steps percent-decoded {@code url()}'s escapes instead.
     * Either way the attacker's apostrophe arrived as an apostrophe and closed the string literal.
     * Now no value reaches the URL at all, whatever precedes it.
     *
     * <p>Still asserted in two stages, and the second stage is the one worth keeping: the
     * percent-decode is applied explicitly, so a future change that lets a {@code url()}-escaped
     * payload back into a {@code javascript:} href fails here rather than reading as safe. An
     * assertion on the attribute value alone would call {@code %27%29%3B} inert, which is exactly the
     * mistake this file spent R2 documenting.
     */
    @Test
    public void anElevenCharacterNameNoLongerLetsThePayloadThrough() {
        CanoeTestSupport.RenderResult armed = CanoeTestSupport.render(
                precedingElement(11) + TARGET, PAYLOAD);
        String href = armed.decodedAttr("a", "href");
        assertEquals("javascript:f('')", href,
                "R3: the prefix is recognised whatever precedes it, so ATTR_JS applies and the value"
                        + " is dropped. Decoded href: " + href);
        assertFalse(percentDecoded(href).contains("');"),
                () -> "R3: and the javascript: URL's script source, percent-decoded as the HTML"
                        + " Standard decodes it before compiling, is the template's own text with an"
                        + " empty literal in it. Script source: " + percentDecoded(href));

        CanoeTestSupport.RenderResult clean = CanoeTestSupport.render(
                precedingElement(10) + TARGET, PAYLOAD);
        assertEquals(clean.decodedAttr("a", "href"), href,
                "and the ten-character name that used to be the repair now changes nothing");
    }

    /**
     * The HTML Standard's javascript-URL step, applied by hand: "let scriptSource be the UTF-8
     * decoding of the percent-decoding of encodedScriptSource". ASCII-only, which is all the payload
     * needs.
     */
    private static String percentDecoded(String url) {
        StringBuilder out = new StringBuilder(url.length());
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '%' && i + 2 < url.length()) {
                out.append((char) Integer.parseInt(url.substring(i + 1, i + 3), 16));
                i += 2;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    // ------------------------------------------------------------------
    // The table
    // ------------------------------------------------------------------

    /**
     * The length&rarr;context table F5 reduced to, with its column collapsed.
     *
     * <p>Expectations are still literals rather than a formula, and the twenty rows are still here.
     * Before R3 the point of the table was that eleven was a cliff edge with nothing on either side
     * of it to suggest one; the point now is that there is no edge, and a table of twenty identical
     * rows is the only way to say that in a form a regression can break. If the residue ever returns,
     * rows 11 upwards fail exactly as rows 11 upwards used to be the ones that recorded it.
     *
     * <p>The third column is the byte at {@code buf[10]} when the check runs. It is {@code '\0'} for
     * every row for a reason worth separating from the comparison: {@code buf} is cleared when the
     * attribute value starts, so index 10 holds nothing at all rather than holding a terminator that
     * happens to be right.
     */
    static Stream<Arguments> precedingNameLengths() {
        List<Arguments> rows = new ArrayList<>();
        for (int length = 1; length <= MAX_NAME_LENGTH; length++) {
            rows.add(Arguments.of(length, Canoe.CTX_JS, '\0'));
        }
        return rows.stream();
    }

    @ParameterizedTest(name = "preceding attribute name of {0} characters")
    @MethodSource("precedingNameLengths")
    public void aPrecedingNameOfNCharactersChangesNeitherTheContextNorTheBuffer(
            int length, int expectedContext, char expectedResidue) throws IOException {
        String prefix = precedingElement(length);

        CanoeStateProbe probe = new CanoeStateProbe();
        probe.feed(prefix + "<a href=\"javascript:");

        assertEquals(expectedResidue, probe.bufferAt(JAVASCRIPT_TERMINATOR_INDEX),
                () -> "R3: buf[10] after a preceding attribute name of " + length + " characters."
                        + " Buffer: " + describe(probe.buffer()));
        assertEquals(expectedContext, probe.currentContext(),
                () -> "a preceding attribute name of " + length + " characters must still give "
                        + CanoeTestSupport.contextName(expectedContext));

        // ...and the same conclusion end to end, so the table is about pages and not about a field.
        String rendered = renderWithPrefix(prefix, PAYLOAD);
        boolean suppressed = rendered.equals(TARGET.replace("$data", ""));
        assertEquals(expectedContext == Canoe.CTX_JS, suppressed,
                () -> "the rendered page must agree with the context: " + rendered);
    }

    /**
     * Inverted by R3. Was {@code aTenCharacterNameRepairsTheBufferAndAShorterOneDoesNot}.
     *
     * <p>The table above could not, on its own, tell "10 characters wrote a terminator at index 10"
     * from "10 characters left index 10 alone and it was already zero", because a fresh {@link Canoe}
     * has a zero-filled buffer. Dirtying the buffer first separated them, and the result was the part
     * of F5 that was genuinely hard to reason about — an unrelated {@code xlink:href} somewhere on
     * the page made a vulnerable template safe again, and moving it below the target made it
     * vulnerable again.
     *
     * <p>The same three feeds are kept, because the distinction the dirtying draws is exactly what
     * has to stay dead: nothing arms, nothing repairs, and the two orderings agree. The last
     * assertion is the finding's own sentence with its verb reversed.
     */
    @Test
    public void nothingCanArmOrRepairTheBufferAndOrderNoLongerMatters() throws IOException {
        String arm = precedingElement(11);
        String repair = precedingElement(10);
        String tooShort = precedingElement(9);

        assertEquals('\0', bufferByteAfter(arm),
                "R3: 11 characters no longer leave a letter at buf[10] - the buffer is cleared when"
                        + " the attribute value starts");
        assertEquals('\0', bufferByteAfter(arm + repair), "nor do 11 followed by 10");
        assertEquals('\0', bufferByteAfter(arm + tooShort), "nor 11 followed by 9");

        String suppressed = TARGET.replace("$data", "");
        assertEquals(suppressed, renderWithPrefix(arm + repair, PAYLOAD));
        assertEquals(suppressed, renderWithPrefix(arm + tooShort, PAYLOAD),
                "R3: the nine-character name that used to leave the page injectable no longer does");
        assertEquals(suppressed, renderWithPrefix(arm, PAYLOAD));

        // Order mattered, which was the sentence in the finding that was hardest to believe.
        assertEquals(renderWithPrefix(arm + repair, PAYLOAD),
                renderWithPrefix(repair + arm, PAYLOAD),
                "R3: swapping two benign elements can no longer change whether the page is"
                        + " injectable");
    }

    /**
     * The invariant the buffer clearing buys, stated on its own rather than through one prefix.
     *
     * <p>The comparison in {@code detectAttributePrefix()} is length-checked now, so it would be
     * correct even over a dirty buffer. This asserts the other half of R3: the buffer is not dirty.
     * Every index above what the current name or value has written holds a NUL, at each of the three
     * points where the buffer is reused. That is the property the rest of Canoe's fixed-index
     * comparisons — the {@code on*} table and the name chains, which R4 still owns — are relying on
     * without saying so, and it is worth one test that says so.
     */
    @Test
    public void theBufferHoldsNothingTheCurrentNameOrValueWrote() throws IOException {
        String dirty = "<i placeholder=\"Search\">";

        // A tag name: "a" and its terminator, then nothing.
        assertClearAbove(new CanoeStateProbe().feed(dirty + "<a"), 1, "tag name");

        // An attribute name: "href" and its terminator at index 4.
        assertClearAbove(new CanoeStateProbe().feed(dirty + "<a href"), 4, "attribute name");

        // An attribute value: ten characters and no terminator at all, which is the case that used
        // to expose the residue.
        assertClearAbove(new CanoeStateProbe().feed(dirty + "<a href=\"javascript"), 10,
                "attribute value");
    }

    private static void assertClearAbove(CanoeStateProbe probe, int firstClearIndex, String what) {
        char[] buffer = probe.buffer();
        for (int i = firstClearIndex; i < buffer.length; i++) {
            int index = i;
            assertEquals('\0', buffer[i],
                    () -> "R3: buf[" + index + "] must be clear while parsing a " + what
                            + "; found residue. Buffer: " + describe(buffer));
        }
    }

    // ------------------------------------------------------------------
    // The shorter indices: data: and mocha:
    // ------------------------------------------------------------------

    /**
     * Inverted by R3. Was {@code theCurrentAttributeNameDecidesTheShortPrefixes}.
     *
     * <p>{@code data:} read {@code buf[4]} and {@code mocha:} read {@code buf[5]}, and at those
     * indices the deciding name was the <em>current</em> attribute's rather than a preceding
     * element's. T10 established the rule the finding does not state: a name of length L wrote its
     * terminator at {@code buf[L]}, so a prefix check reading index N passed exactly when
     * {@code L <= N}. For {@code javascript} that meant N=10 and a preceding element was needed to
     * arm it, because {@code href}, {@code src} and every other realistic name is shorter. For
     * {@code data} it meant N=4 and for {@code mocha} N=5 — lengths ordinary attribute names reach on
     * their own, so no preceding markup was needed at all, and this was F5 with a threshold low
     * enough to hit by accident.
     *
     * <p>The table is kept at the same twelve lengths with both columns collapsed to "detected",
     * because these two are the rows a regression would reach first.
     *
     * <p>Rendered rather than probed, because the point is that these are ordinary templates:
     * {@code <a href="data:...">} and {@code <a title="data:...">} must now agree, and nothing about
     * either one mentions a buffer.
     */
    static Stream<Arguments> currentNameLengths() {
        List<Arguments> rows = new ArrayList<>();
        for (int length = 1; length <= 12; length++) {
            rows.add(Arguments.of(length, true, true));
        }
        return rows.stream();
    }

    @ParameterizedTest(name = "current attribute name of {0} characters")
    @MethodSource("currentNameLengths")
    public void theCurrentAttributeNameNoLongerDecidesTheShortPrefixes(int length,
                                                                      boolean dataDetected,
                                                                      boolean mochaDetected) {
        String name = attributeName(length);

        String data = CanoeTestSupport.render(
                "<a " + name + "=\"data:$data\">x</a>", PAYLOAD).output();
        assertEquals(dataDetected, data.equals("<a " + name + "=\"data:\">x</a>"),
                () -> "R3: data: used to read buf[4], so a name of " + length + " characters could"
                        + " overwrite it. It is compared against bufLen now. Rendered: " + data);

        String mocha = CanoeTestSupport.render(
                "<a " + name + "=\"mocha:$data\">x</a>", PAYLOAD).output();
        assertEquals(mochaDetected, mocha.equals("<a " + name + "=\"mocha:\">x</a>"),
                () -> "R3: mocha: used to read buf[5], with the same consequence. Rendered: "
                        + mocha);
    }

    /**
     * Inverted by R3. Was {@code ordinaryAttributeNamesDecideTheShortPrefixesWithNoPrecedingMarkup}
     * {@code AtAll}, and it is the row that shows the finding was reachable without contriving
     * anything: one character of attribute name apart, {@code href} and {@code title} used to
     * disagree about whether a {@code data:} URL was a {@code data:} URL.
     */
    @Test
    public void ordinaryAttributeNamesAllAgreeAboutTheShortPrefixes() {
        assertEquals("<a href=\"data:\">x</a>",
                CanoeTestSupport.render("<a href=\"data:$data\">x</a>", PAYLOAD).output(),
                "href is 4 characters, which used to be the only reason this one worked");
        assertEquals("<a title=\"data:\">x</a>",
                CanoeTestSupport.render("<a title=\"data:$data\">x</a>", PAYLOAD).output(),
                "R3: title is 5, so buf[4] used to hold its 'e' and the prefix was missed. The two"
                        + " templates now agree.");
        assertEquals("<a title=\"mocha:\">x</a>",
                CanoeTestSupport.render("<a title=\"mocha:$data\">x</a>", PAYLOAD).output(),
                "title was exactly the 5 characters mocha: needed");
        assertEquals("<div background=\"mocha:\">x</div>",
                CanoeTestSupport.render("<div background=\"mocha:$data\">x</div>", PAYLOAD).output(),
                "R3: background is 10, so mocha: used to be missed here");
    }

    // ------------------------------------------------------------------
    // Residue across write() calls
    // ------------------------------------------------------------------

    /**
     * Inverted by R3. Was {@code theResidueCrossesWriteCallsWithinOneRender}.
     *
     * <p>The residue was a property of the {@code Canoe} rather than of a {@code write()} call: it
     * crossed call boundaries within one render, because {@code buf} is a field. That mattered
     * because Velocity does not write a template in one call — every literal text node and every
     * reference is its own {@code write()}, so the arming element and the target routinely arrived
     * separately, and if the residue had been per-call F5 would have been far narrower than it was.
     *
     * <p>The three feeds are kept exactly as they were, and the claim they carry is now the opposite
     * one: the same characters divided three ways reach the same clean {@code buf[10]} and the same
     * context. The chunking half of the statement is worth keeping regardless of F5 — {@code buf} is
     * still a field, and a fix that only worked when a template arrived in one call would pass every
     * other test in this suite.
     */
    @Test
    public void neitherTheResidueNorTheOutcomeDependsOnWriteBoundaries() throws IOException {
        String arm = precedingElement(11);
        String target = "<a href=\"javascript:";

        CanoeStateProbe oneCall = new CanoeStateProbe();
        oneCall.feed(arm + target);

        CanoeStateProbe twoCalls = new CanoeStateProbe();
        twoCalls.feed(arm);
        twoCalls.feed(target);

        CanoeStateProbe charByChar = new CanoeStateProbe();
        for (char c : (arm + target).toCharArray()) {
            charByChar.feed(String.valueOf(c));
        }

        assertEquals('\0', oneCall.bufferAt(JAVASCRIPT_TERMINATOR_INDEX));
        assertEquals('\0', twoCalls.bufferAt(JAVASCRIPT_TERMINATOR_INDEX),
                "R3: no residue survives a write() boundary between the two elements, because there"
                        + " is none to survive");
        assertEquals('\0', charByChar.bufferAt(JAVASCRIPT_TERMINATOR_INDEX),
                "and none survives 39 of them");
        assertEquals(Canoe.CTX_JS, oneCall.currentContext());
        assertEquals(Canoe.CTX_JS, twoCalls.currentContext());
        assertEquals(Canoe.CTX_JS, charByChar.currentContext());
    }

    /**
     * The same thing through Velocity, where the write boundaries are real rather than arranged.
     *
     * <p>A reference between the arming element and the target forces Velocity to split the template
     * into three writes, and the outcome is identical to writing it as one string. The second half
     * used to be the sharper claim — that the arming was done by the attribute <em>name</em>, which
     * is template text, so F5 was a template-ordering defect and not an injection. Since R3 nothing
     * arms anything, and what the pair now measures is that the target renders identically however
     * the writes fall and whatever the neighbouring element's value contains. That is the part of the
     * old statement that outlives the finding: it is the only test in this file whose write
     * boundaries are Velocity's own rather than this file's.
     */
    @Test
    public void velocityWriteBoundariesDoNotChangeTheOutcome() {
        String arm = precedingElement(11);

        String asOneTemplate = renderWithPrefix(arm, PAYLOAD);
        assertEquals(TARGET.replace("$data", ""), asOneTemplate,
                "R3: the target suppresses, whatever precedes it");

        String withAReferenceBetween = CanoeTestSupport.render(
                arm + "<p>$mid</p>" + TARGET,
                new LinkedHashMap<>(Map.of("data", PAYLOAD, "mid", "text"))).output();
        assertTrue(withAReferenceBetween.endsWith(asOneTemplate),
                () -> "the target renders identically whether or not a reference split the writes."
                        + "\n  one template : " + asOneTemplate
                        + "\n  with a split : " + withAReferenceBetween);

        String precededByAReferenceValue = CanoeTestSupport.render(
                "<i zqqqqqqqqqq=\"$mid\">" + TARGET,
                new LinkedHashMap<>(Map.of("data", PAYLOAD, "mid", "x"))).output();
        assertEquals(asOneTemplate,
                precededByAReferenceValue.substring(precededByAReferenceValue.indexOf("<a ")),
                "and what the preceding element's value holds is irrelevant, as it always was - it"
                        + " was the name that armed the buffer, and now neither does");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * A benign element carrying one attribute whose name is {@code length} characters long.
     *
     * <p>The name is {@code z} followed by {@code q}s so that any residue byte would always be the
     * same letter and a failure message could name it. The element is {@code <i>} — one character, so
     * its tag name cannot reach index 10 and confuse the reading.
     */
    private static String precedingElement(int length) {
        return "<i " + attributeName(length) + "=\"1\">";
    }

    private static String attributeName(int length) {
        StringBuilder name = new StringBuilder(length);
        name.append('z');
        while (name.length() < length) {
            name.append('q');
        }
        return name.toString();
    }

    private static String renderWithPrefix(String prefix, String payload) {
        String rendered = CanoeTestSupport.render(prefix + TARGET, payload).output();
        assertTrue(rendered.startsWith(prefix), () -> "unexpected render: " + rendered);
        return rendered.substring(prefix.length());
    }

    private static char bufferByteAfter(String templateText) throws IOException {
        CanoeStateProbe probe = new CanoeStateProbe();
        probe.feed(templateText + "<a href=\"javascript:");
        return probe.bufferAt(JAVASCRIPT_TERMINATOR_INDEX);
    }

    /** The first twelve buffer bytes, with NUL shown as a dot, for failure messages. */
    private static String describe(char[] buffer) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 12; i++) {
            sb.append(buffer[i] == '\0' ? '.' : buffer[i]);
        }
        return sb.append(']').toString();
    }

    /**
     * A guard on the generator, so the table above cannot be quietly measuring something else.
     *
     * <p>Everything in this file depends on {@link #precedingElement(int)} producing an attribute
     * name of exactly the requested length. If it ever produced 19 distinct names and one duplicate,
     * or an off-by-one length, the table would still pass and would be measuring the wrong integer.
     * That guard matters more now than it did: a table whose expectations are all the same value
     * cannot fail on a generator bug the way a table with a boundary in it would have.
     */
    @Test
    public void theGeneratedPrefixSetIsWhatTheTableClaims() {
        Set<String> names = new LinkedHashSet<>();
        for (int length = 1; length <= MAX_NAME_LENGTH; length++) {
            String name = attributeName(length);
            assertEquals(length, name.length(), () -> "generated name: " + name);
            assertTrue(names.add(name), () -> "duplicate generated name: " + name);
            assertFalse(precedingElement(length).contains("$"),
                    "the preceding element must carry no reference; it is template text");
        }
        assertEquals(MAX_NAME_LENGTH, names.size());
        assertEquals(Arrays.asList("z", "zq", "zqq"), new ArrayList<>(names).subList(0, 3));

        // The lengths that used to sit on either side of the boundary must still be in the table, or
        // the collapse above would be a collapse of the wrong twenty rows.
        assertTrue(names.contains(attributeName(JAVASCRIPT_TERMINATOR_INDEX))
                        && names.contains(attributeName(JAVASCRIPT_TERMINATOR_INDEX + 1)),
                "the 10 and 11 character names are the two the finding turned on");
        assertNotEquals(attributeName(JAVASCRIPT_TERMINATOR_INDEX),
                attributeName(JAVASCRIPT_TERMINATOR_INDEX + 1));
    }
}
