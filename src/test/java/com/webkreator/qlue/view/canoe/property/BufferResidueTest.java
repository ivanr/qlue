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
 * <strong>F5 as a property.</strong> The property is that a template's security does not depend on
 * markup that appears before it. It does not hold.
 *
 * <p>The finding is stated as an anecdote — a {@code placeholder} attribute somewhere above a
 * {@code javascript:} URL disarms Canoe's prefix detection — and an anecdote is the wrong shape for
 * it, because the thing that decides the outcome is not an attribute name but an <em>integer</em>.
 * So this file fixes the target and varies only the preceding markup:
 *
 * <pre>{@code <PREFIX><a href="javascript:f('$data')">x</a>}</pre>
 *
 * <p>where {@code PREFIX} is a benign element carrying one attribute whose name is 1 to 20 characters
 * long. Twenty renders of the same reference, in the same template, into the same sink; the property
 * says all twenty produce identical bytes. They fall into two groups, and the boundary is between 10
 * and 11.
 *
 * <h2>The mechanism, and the byte that carries it</h2>
 *
 * <p>{@code detectAttributePrefix()} confirms the value prefix was exactly {@code javascript} by
 * testing {@code buf[10] == '\0'}. {@code buf} is a 36-character field of the whole render, never
 * cleared — only {@code bufLen} is reset. The value scan writes indices 0 through 9 and bails at
 * {@code bufLen == 10} without writing, so a value can never repair index 10 itself
 * ({@code Canoe.java:933} omits the {@code buf[bufLen++] = '\0'} that {@code TAG_ATTR_NAME} does at
 * line 809). Whatever an earlier attribute name left at index 10 therefore decides the answer:
 *
 * <ul>
 *   <li>a name of <strong>fewer than 10</strong> characters never reaches index 10, so a fresh
 *       Canoe's zero-fill survives and the prefix matches;
 *   <li>a name of <strong>exactly 10</strong> puts its own terminator there, which also matches — so
 *       a ten-character name anywhere on the page <em>repairs</em> a buffer a longer one dirtied;
 *   <li>a name of <strong>11 or more</strong> leaves a letter there, the prefix does not match, and
 *       because of F4 the context has already been reset to {@code ATTR_HTML}.
 * </ul>
 *
 * <p>These tests read {@link CanoeStateProbe#bufferAt(int)} so the evidence is the byte itself, not
 * merely that the outcome changed. "The output differs" is a symptom shared by a dozen possible
 * causes; {@code buf[10] == 'r'} is the cause.
 *
 * <h2>Relationship to the other two files that touch F5</h2>
 *
 * <p>{@code AttributePrefixTest} (T10) owns the unit-level table — it drives {@code CanoeStateProbe}
 * directly and asserts {@code ATTR_*} constants. This file asserts the <em>rendered page</em>, with a
 * real payload in it, because the finding's claim is about pages and because the step from
 * {@code ATTR_HTML} to "the attacker's apostrophe reaches the JavaScript parser" is the step that
 * makes it High rather than a curiosity. The corpus holds three cases (clean, armed, repaired) whose
 * verdicts this file's table generalises.
 */
public class BufferResidueTest {

    /** The fixed target: a {@code javascript:} URL with the reference inside a string literal. */
    private static final String TARGET = "<a href=\"javascript:f('$data')\">x</a>";

    /** The payload, chosen because its first character is what closes the string literal. */
    private static final String PAYLOAD = Payloads.QUOTE_SINGLE_BREAKOUT.value();

    /** The longest preceding attribute name the table covers. */
    private static final int MAX_NAME_LENGTH = 20;

    /** The index {@code javascript}, {@code livescript} and {@code asfunction} all test. */
    private static final int JAVASCRIPT_TERMINATOR_INDEX = 10;

    // ------------------------------------------------------------------
    // The property, and its counterexample
    // ------------------------------------------------------------------

    /**
     * The property: preceding markup must not change the render. <strong>It does.</strong>
     *
     * <p>Twenty renders of one template, differing only in a benign element above it, produce
     * <em>two</em> distinct outputs where the property demands one. The assertion is written as the
     * counterexample rather than as the property, because the property is false today and a test
     * asserting it would be red from the first commit — &sect;2.1's ledger rule. When F5 is fixed
     * this test fails, and that failure is the signal to invert it.
     *
     * <p>The two groups are named rather than counted: one is byte-identical to a render with no
     * value at all (suppression working), the other is byte-identical to a render where the payload
     * was {@code html()}-encoded into the URL (suppression defeated).
     */
    @Test
    public void thePrecedingElementDecidesWhetherTheSameTemplateIsSuppressedOrEncoded() {
        Map<String, List<Integer>> outputsToLengths = new LinkedHashMap<>();
        for (int length = 1; length <= MAX_NAME_LENGTH; length++) {
            String rendered = renderWithPrefix(precedingElement(length), PAYLOAD);
            outputsToLengths.computeIfAbsent(rendered, key -> new ArrayList<>()).add(length);
        }

        assertNotEquals(1, outputsToLengths.size(),
                "F5 is fixed if this fails: all twenty preceding elements now produce the same"
                        + " render, which is the property this file exists to state. Invert the"
                        + " assertion and update the ledger.");
        assertEquals(2, outputsToLengths.size(),
                () -> "F5 produces exactly two outcomes, split at the 10/11 boundary; got "
                        + outputsToLengths.size() + " groups: " + outputsToLengths.values());

        List<List<Integer>> groups = new ArrayList<>(outputsToLengths.values());
        assertEquals(rangeList(1, 10), groups.get(0),
                "names of 1 to 10 characters leave the prefix detection working");
        assertEquals(rangeList(11, MAX_NAME_LENGTH), groups.get(1),
                "names of 11 and up defeat it");

        List<String> outputs = new ArrayList<>(outputsToLengths.keySet());
        assertEquals(TARGET.replace("$data", ""), outputs.get(0),
                "the first group is CTX_JS: byte-identical to rendering with no value at all");
        assertEquals(TARGET.replace("$data", Canoe.encode(PAYLOAD, Canoe.CTX_HTML_ATTR)),
                outputs.get(1),
                "the second is CTX_HTML_ATTR: the payload html()-encoded into a javascript: URL");
    }

    /**
     * The consequence, stated at the sink rather than at the encoder.
     *
     * <p>An 11-character preceding name is not merely "encoded differently" — the HTML parser decodes
     * {@code html()}'s character references exactly once before the {@code javascript:} URL is
     * compiled, so the attacker's apostrophe arrives as an apostrophe and closes the string literal.
     * A 10-character one leaves nothing at all in the URL. This is the step that makes F5 High, and
     * it is asserted against the jsoup-decoded attribute value for the reason &sect;5.1 gives: a
     * string assertion on the raw bytes would call both of them safe.
     */
    @Test
    public void anElevenCharacterNameLetsThePayloadReachTheJavaScriptParser() {
        CanoeTestSupport.RenderResult armed = CanoeTestSupport.render(
                precedingElement(11) + TARGET, PAYLOAD);
        assertTrue(armed.decodedAttr("a", "href").contains("');"),
                () -> "F5: the string literal in the javascript: URL is closed. Decoded href: "
                        + armed.decodedAttr("a", "href"));

        CanoeTestSupport.RenderResult clean = CanoeTestSupport.render(
                precedingElement(10) + TARGET, PAYLOAD);
        assertEquals("javascript:f('')", clean.decodedAttr("a", "href"),
                "with a ten-character name the identical template suppresses the value entirely");
    }

    // ------------------------------------------------------------------
    // The table
    // ------------------------------------------------------------------

    /**
     * The length&rarr;context table F5 reduces to.
     *
     * <p>Expectations are literals rather than a formula. A formula would restate the bug's cause;
     * the table shows its effect, which is that eleven is a cliff edge with nothing on either side of
     * it to suggest one. When F5 is fixed the whole column collapses to {@code CTX_JS} and rows 11
     * upwards fail.
     *
     * <p>The third column is the byte at {@code buf[10]} at the moment the check runs, so each row
     * carries its own explanation.
     */
    static Stream<Arguments> precedingNameLengths() {
        List<Arguments> rows = new ArrayList<>();
        for (int length = 1; length <= MAX_NAME_LENGTH; length++) {
            boolean detected = length <= JAVASCRIPT_TERMINATOR_INDEX;
            rows.add(Arguments.of(length,
                    detected ? Canoe.CTX_JS : Canoe.CTX_HTML_ATTR,
                    detected ? '\0' : 'q'));
        }
        return rows.stream();
    }

    @ParameterizedTest(name = "preceding attribute name of {0} characters")
    @MethodSource("precedingNameLengths")
    public void aPrecedingNameOfNCharactersDecidesTheContextAndTheByteThatDecidedIt(
            int length, int expectedContext, char expectedResidue) throws IOException {
        String prefix = precedingElement(length);

        CanoeStateProbe probe = new CanoeStateProbe();
        probe.feed(prefix + "<a href=\"javascript:");

        assertEquals(expectedResidue, probe.bufferAt(JAVASCRIPT_TERMINATOR_INDEX),
                () -> "F5: buf[10] after a preceding attribute name of " + length + " characters."
                        + " Buffer: " + describe(probe.buffer()));
        assertEquals(expectedContext, probe.currentContext(),
                () -> "a preceding attribute name of " + length + " characters gives "
                        + CanoeTestSupport.contextName(expectedContext));

        // ...and the same conclusion end to end, so the table is about pages and not about a field.
        String rendered = renderWithPrefix(prefix, PAYLOAD);
        boolean suppressed = rendered.equals(TARGET.replace("$data", ""));
        assertEquals(expectedContext == Canoe.CTX_JS, suppressed,
                () -> "the rendered page must agree with the context: " + rendered);
    }

    /**
     * A ten-character name <em>repairs</em> a buffer an eleven-character one dirtied.
     *
     * <p>The table above cannot show this on its own: a fresh {@link Canoe} has a zero-filled buffer,
     * so "10 characters wrote a terminator at index 10" and "10 characters left index 10 alone and it
     * was already zero" look identical. Dirtying the buffer first separates them, and the result is
     * the part of F5 that is genuinely hard to reason about — an unrelated {@code xlink:href}
     * somewhere on the page makes a vulnerable template safe again, and moving it below the target
     * makes it vulnerable again.
     */
    @Test
    public void aTenCharacterNameRepairsTheBufferAndAShorterOneDoesNot() throws IOException {
        String arm = precedingElement(11);
        String repair = precedingElement(10);
        String tooShort = precedingElement(9);

        assertEquals('q', bufferByteAfter(arm), "11 characters leave a letter at buf[10]");
        assertEquals('\0', bufferByteAfter(arm + repair),
                "10 characters put their own terminator there");
        assertEquals('q', bufferByteAfter(arm + tooShort),
                "9 characters cannot reach index 10, so the residue survives");

        assertEquals(TARGET.replace("$data", ""), renderWithPrefix(arm + repair, PAYLOAD),
                "F5: the page with two benign elements above the target is SAFE");
        assertNotEquals(TARGET.replace("$data", ""), renderWithPrefix(arm + tooShort, PAYLOAD),
                "F5: the same page with a nine-character name instead is not");
        assertEquals(renderWithPrefix(arm, PAYLOAD), renderWithPrefix(arm + tooShort, PAYLOAD),
                "...and is byte-identical to having no second element at all");

        // Order matters, which is the sentence in the finding that is hardest to believe.
        assertNotEquals(renderWithPrefix(arm + repair, PAYLOAD),
                renderWithPrefix(repair + arm, PAYLOAD),
                "F5: swapping two benign elements changes whether the page is injectable");
    }

    // ------------------------------------------------------------------
    // The shorter indices: data: and mocha:
    // ------------------------------------------------------------------

    /**
     * {@code data:} reads {@code buf[4]} and {@code mocha:} reads {@code buf[5]}, and at those
     * indices the deciding name is the <em>current</em> attribute's, not a preceding element's.
     *
     * <p>T10 established the rule the finding does not state: a name of length L writes its
     * terminator at {@code buf[L]}, so a prefix check reading index N passes exactly when
     * {@code L <= N}. For {@code javascript} that means N=10 and a preceding element is needed to
     * arm it, because {@code href}, {@code src} and every other realistic name is shorter. For
     * {@code data} it means N=4 and for {@code mocha} N=5 — lengths that ordinary attribute names
     * reach on their own, so no preceding markup is needed at all.
     *
     * <p>Rendered rather than probed, because the point is that these are ordinary templates:
     * {@code <a href="data:...">} detects the prefix and {@code <a title="data:...">} does not, and
     * nothing about either one mentions a buffer.
     */
    static Stream<Arguments> currentNameLengths() {
        List<Arguments> rows = new ArrayList<>();
        for (int length = 1; length <= 12; length++) {
            rows.add(Arguments.of(length, length <= 4, length <= 5));
        }
        return rows.stream();
    }

    @ParameterizedTest(name = "current attribute name of {0} characters")
    @MethodSource("currentNameLengths")
    public void theCurrentAttributeNameDecidesTheShortPrefixes(int length, boolean dataDetected,
                                                               boolean mochaDetected) {
        String name = attributeName(length);

        String data = CanoeTestSupport.render(
                "<a " + name + "=\"data:$data\">x</a>", PAYLOAD).output();
        assertEquals(dataDetected, data.equals("<a " + name + "=\"data:\">x</a>"),
                () -> "data: reads buf[4], so a name of " + length + " characters "
                        + (dataDetected ? "leaves it clear" : "overwrites it") + ". Rendered: "
                        + data);

        String mocha = CanoeTestSupport.render(
                "<a " + name + "=\"mocha:$data\">x</a>", PAYLOAD).output();
        assertEquals(mochaDetected, mocha.equals("<a " + name + "=\"mocha:\">x</a>"),
                () -> "mocha: reads buf[5], so a name of " + length + " characters "
                        + (mochaDetected ? "leaves it clear" : "overwrites it") + ". Rendered: "
                        + mocha);
    }

    /**
     * The same fact in names a real template contains, so the parameterised table above is not the
     * only statement of it.
     */
    @Test
    public void ordinaryAttributeNamesDecideTheShortPrefixesWithNoPrecedingMarkupAtAll() {
        assertEquals("<a href=\"data:\">x</a>",
                CanoeTestSupport.render("<a href=\"data:$data\">x</a>", PAYLOAD).output(),
                "href is 4 characters, so its terminator lands exactly on the index data: reads");
        assertNotEquals("<a title=\"data:\">x</a>",
                CanoeTestSupport.render("<a title=\"data:$data\">x</a>", PAYLOAD).output(),
                "F5: title is 5, so buf[4] holds the 'e' and the prefix is missed - one character"
                        + " of attribute name apart, with no preceding element involved");
        assertEquals("<a title=\"mocha:\">x</a>",
                CanoeTestSupport.render("<a title=\"mocha:$data\">x</a>", PAYLOAD).output(),
                "title is exactly the 5 characters mocha: needs");
        assertNotEquals("<div background=\"mocha:\">x</div>",
                CanoeTestSupport.render("<div background=\"mocha:$data\">x</div>", PAYLOAD).output(),
                "F5: background is 10, so mocha: is missed");
    }

    // ------------------------------------------------------------------
    // Residue across write() calls
    // ------------------------------------------------------------------

    /**
     * The residue is a property of the {@code Canoe}, not of a {@code write()} call: it crosses call
     * boundaries within one render, because {@code buf} is a field.
     *
     * <p>This matters because Velocity does not write a template in one call. Every literal text node
     * and every reference is its own {@code write()}, so the arming element and the target routinely
     * arrive separately — and if the residue were somehow per-call, F5 would be far narrower than it
     * is. The three feeds below are the same characters divided three ways and reach the same
     * {@code buf[10]}.
     */
    @Test
    public void theResidueCrossesWriteCallsWithinOneRender() throws IOException {
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

        assertEquals('q', oneCall.bufferAt(JAVASCRIPT_TERMINATOR_INDEX));
        assertEquals('q', twoCalls.bufferAt(JAVASCRIPT_TERMINATOR_INDEX),
                "the residue survives a write() boundary between the two elements");
        assertEquals('q', charByChar.bufferAt(JAVASCRIPT_TERMINATOR_INDEX),
                "and survives 39 of them");
        assertEquals(Canoe.CTX_HTML_ATTR, twoCalls.currentContext());
        assertEquals(Canoe.CTX_HTML_ATTR, charByChar.currentContext());
    }

    /**
     * The same thing through Velocity, where the write boundaries are real rather than arranged.
     *
     * <p>A reference between the arming element and the target forces Velocity to split the template
     * into three writes, and the outcome is identical to writing it as one string. The second half is
     * the sharper claim: the <em>arming attribute's own value</em> can be a reference — attacker
     * data — and that changes nothing, because the arming is done by the attribute <em>name</em>,
     * which is template text. F5 is a template-ordering defect and not an injection.
     */
    @Test
    public void velocityWriteBoundariesDoNotChangeTheOutcome() {
        String arm = precedingElement(11);

        String asOneTemplate = renderWithPrefix(arm, PAYLOAD);
        String withAReferenceBetween = CanoeTestSupport.render(
                arm + "<p>$mid</p>" + TARGET,
                new LinkedHashMap<>(Map.of("data", PAYLOAD, "mid", "text"))).output();
        assertTrue(withAReferenceBetween.endsWith(asOneTemplate.substring(arm.length())),
                () -> "the target renders identically whether or not a reference split the writes."
                        + "\n  one template : " + asOneTemplate
                        + "\n  with a split : " + withAReferenceBetween);

        String armedByAReferenceValue = CanoeTestSupport.render(
                "<i zqqqqqqqqqq=\"$mid\">" + TARGET,
                new LinkedHashMap<>(Map.of("data", PAYLOAD, "mid", "x"))).output();
        assertEquals(asOneTemplate.substring(asOneTemplate.indexOf("<a ")),
                armedByAReferenceValue.substring(armedByAReferenceValue.indexOf("<a ")),
                "the arming is done by the attribute name, which is template text; what its value"
                        + " holds is irrelevant");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * A benign element carrying one attribute whose name is {@code length} characters long.
     *
     * <p>The name is {@code z} followed by {@code q}s so that the residue byte is always the same
     * letter and a failure message can name it. The element is {@code <i>} — one character, so its
     * tag name cannot reach index 10 and confuse the reading.
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

    private static List<Integer> rangeList(int from, int to) {
        List<Integer> result = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            result.add(i);
        }
        return result;
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
    }
}
