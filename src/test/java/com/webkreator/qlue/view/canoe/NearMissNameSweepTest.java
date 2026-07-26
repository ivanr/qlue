package com.webkreator.qlue.view.canoe;

import com.webkreator.qlue.view.Canoe;
import com.webkreator.qlue.view.CanoeStateProbe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The negative half of the classification claim: <strong>a name that agrees with a recognised name
 * up to character <em>k</em> and then differs must not be recognised.</strong>
 *
 * <h2>Why this file exists (T30)</h2>
 *
 * <p>{@code setTagAttributeContext()} and {@code detectAttributePrefix()} used to be hand-unrolled
 * comparison chains — {@code buf[0]=='b' && buf[1]=='a' && buf[2]=='c' && …} — and every one of
 * those {@code &&} operands was a branch with two outcomes. The rest of the suite drove the
 * <em>true</em> path of each chain exhaustively: {@code CanoeStateMachineTest} named all 24
 * {@code on*} branches, {@code AttributeNameMatrixTest} partitions ninety names, and
 * {@code EventHandlerMatrixTest} probes every handler the HTML Standard defines. None of them
 * produced an input that reached character <em>k</em> of a chain and then diverged, so before this
 * file the branch coverage of {@code setTagAttributeContext()} was <strong>65.6%</strong> — 135 of
 * 392 branch outcomes never taken. An unreached branch in either method is by definition an
 * untested security decision, which is the argument the coverage gate in {@code build.gradle}
 * rests on.
 *
 * <p>R3 and R4 replaced both sets of chains with bounded string comparisons, so the branch counts
 * are far smaller — but the sweep is what drives the <em>loop</em> of each comparison to its
 * mismatching iteration, which is the same coverage argument against a different implementation,
 * and the security question it asks is unchanged.
 *
 * <p>The inputs are therefore generated rather than chosen: for each recognised name and each
 * index into it, the name truncated at that index with one character replaced. That is the
 * cheapest input that makes exactly one comparison evaluate false, and it is also a real security
 * question — {@code hreq}, {@code onclicq} and {@code javascripq:} are what an attacker's near-miss
 * looks like, and a comparison that accepted one of them would be a sink Canoe classified by
 * accident.
 *
 * <p>The <em>other</em> direction — an attribute name that Canoe fails to recognise when it should
 * — was F2 and F3, and lives in {@code EventHandlerMatrixTest} and {@code AttributeNameMatrixTest}.
 * This file cannot see those, because it only ever asserts that a name is classified as what it is
 * rather than as something else.
 *
 * <h2>The handler half is inverted (R4)</h2>
 *
 * <p>The {@code on*} rows used to assert that {@code onclicq} was <em>not</em> classified as
 * JavaScript, which was true and was the wrong thing to want: a near miss of a handler name is
 * overwhelmingly likely to be another handler name, and every one the table missed was F2. R4
 * replaced the table with a prefix rule, so {@link #aNearMissOfAHandlerNameIsAHandlerToo} asserts
 * the opposite and asserts something stronger — the classification depends on the first two
 * characters and on nothing else, so no near miss of any length can fall out of it. The non-handler
 * names keep the original sweep, because {@code href} and {@code hreq} genuinely are different
 * sinks.
 *
 * <h2>What it deliberately does not reach</h2>
 *
 * <p>Two groups of branch outcome survive this sweep and are excluded from the gate rather than
 * chased, each with the test that proves it dead:
 *
 * <ul>
 *   <li>nothing in the {@code on*} table any more, and nothing in {@code detectAttributePrefix()}.
 *       The 25 outcomes of the {@code onselect}/{@code onsubmit} block were unreachable because the
 *       block sat inside {@code buf[0]=='o' && buf[1]=='n'} and then tested {@code buf[0]=='s'}
 *       (<strong>F1</strong>); R4 deleted the whole table. The {@code buf[10]=='\0'} terminator of
 *       the three ten-character value prefixes was here too, because making it evaluate false
 *       required buffer residue at index 10 and no near miss could produce it (F5, owned by
 *       {@code BufferResidueTest}); R3 replaced it with a length check against {@code bufLen}, which
 *       this sweep's truncation rows reach in both directions; and
 *   <li>{@code "Internal error #1001"} and the {@code attrQuotes} default arm, neither of which has
 *       a reachable input — see {@code CanoeRobustnessTest}.
 * </ul>
 */
public class NearMissNameSweepTest {

    /**
     * The attribute names {@code setTagAttributeContext()} compares in full, mapped to the
     * {@code ATTR_*} each one assigns.
     *
     * <p>Handler names used to be in here too, taken from
     * {@code CanoeStateMachineTest.declaredOnStarBranches} so that a branch added to
     * {@code Canoe.java} could not be missed. R4 deleted the table those names came from, and a
     * prefix rule has no per-name comparison for a near miss to fall off — so the handler half moved
     * to {@link #handlerNameNearMisses}, which asserts the opposite outcome. What is left here is
     * the seven names that are still matched whole.
     *
     * <p>{@code data} yields {@code ATTR_CONTENT} because the branch commented {@code // content}
     * compares the characters of {@code data}; that is F7 and it is asserted in
     * {@code AttributeNameMatrixTest.theSourceDeclaresExactlyTheNonHandlerBranchesTheMatrixExpects}.
     *
     * <p>The exact names and their expected contexts are asserted elsewhere — this map is used for
     * its <em>keys</em>, and the values are carried so that a near miss can be checked against the
     * classification it must not receive rather than against a bare {@code ATTR_HTML}.
     */
    private static Map<String, Integer> recognisedAttributeNames() {
        Map<String, Integer> names = new LinkedHashMap<>();

        names.put("background", Canoe.ATTR_URI);
        names.put("data", Canoe.ATTR_CONTENT);
        names.put("dynsrc", Canoe.ATTR_URI);
        names.put("lowsrc", Canoe.ATTR_URI);
        names.put("href", Canoe.ATTR_URI);
        names.put("src", Canoe.ATTR_URI);
        names.put("style", Canoe.ATTR_CSS);

        return names;
    }

    /**
     * The handler names the deleted {@code on*} table declared, taken from
     * {@code CanoeStateMachineTest} so that the two files cannot disagree about what it held.
     *
     * <p>{@code onredystatechange} is added because it is the seventeen characters the
     * {@code onreadystatechange} chain actually compared (<strong>F19</strong>), and its near misses
     * used to be the only inputs that exercised that chain. Both spellings are the same case now,
     * and the pair is kept because a rule that ever distinguishes them again is a table.
     */
    private static Set<String> handlerNames() {
        Set<String> names = new LinkedHashSet<>();
        for (Arguments row : (Iterable<Arguments>)
                CanoeStateMachineTest.namesTheOldOnStarTableDeclared()::iterator) {
            names.add((String) row.get()[0]);
        }
        names.add("onredystatechange");
        return names;
    }

    /** The value prefixes {@code detectAttributePrefix()} has a comparison chain for. */
    private static Map<String, Integer> recognisedValuePrefixes() {
        Map<String, Integer> prefixes = new LinkedHashMap<>();
        prefixes.put("asfunction", Canoe.ATTR_ACTIONSCRIPT);
        prefixes.put("data", Canoe.ATTR_DATA);
        prefixes.put("javascript", Canoe.ATTR_JS);
        prefixes.put("livescript", Canoe.ATTR_JS);
        prefixes.put("mocha", Canoe.ATTR_JS);
        return prefixes;
    }

    // ------------------------------------------------------------------
    // Generation
    // ------------------------------------------------------------------

    /**
     * Every one-character divergence from a recognised name, plus the one-character extension.
     *
     * <p>For a name of <em>n</em> characters this yields <em>n</em> truncations — the name up to
     * index <em>i</em> with a character that is not {@code name[i]} in its place, which makes
     * comparison <em>i</em> of the chain evaluate false and every earlier one true — and one longer
     * name, which is the only way to make the trailing {@code buf[n]=='\0'} test evaluate false.
     *
     * <p>A divergence that happens to spell another recognised name is dropped rather than
     * asserted, since it is not a near miss of anything.
     */
    private static List<String> nearMissesOf(String name, Set<String> recognised) {
        List<String> result = new ArrayList<>(name.length() + 1);
        for (int i = 0; i < name.length(); i++) {
            String candidate = name.substring(0, i) + differentLetter(name.charAt(i));
            if (!recognised.contains(candidate)) {
                result.add(candidate);
            }
        }
        String longer = name + differentLetter('\0');
        if (!recognised.contains(longer)) {
            result.add(longer);
        }
        return result;
    }

    /** A letter that is not {@code c}. Letters are legal at every index of a name or a prefix. */
    private static char differentLetter(char c) {
        return c == 'q' ? 'z' : 'q';
    }

    public static Stream<Arguments> attributeNameNearMisses() {
        Map<String, Integer> recognised = recognisedAttributeNames();
        List<Arguments> rows = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : recognised.entrySet()) {
            for (String nearMiss : nearMissesOf(entry.getKey(), recognised.keySet())) {
                rows.add(Arguments.of(entry.getKey(), nearMiss, entry.getValue()));
            }
        }
        return rows.stream();
    }

    /**
     * Every one-character divergence from each of the 24 names the deleted {@code on*} table
     * declared, with the classification the prefix rule gives it.
     *
     * <p>The expectation is computed from the near miss itself rather than tabled, and that is the
     * assertion: {@code ATTR_JS} when the first two characters survive the divergence,
     * {@code ATTR_HTML} when they do not. Truncating {@code onclick} at index 0 or 1 gives {@code q}
     * and {@code oq}, which are not handler names and must not be treated as any; every other row —
     * {@code onq}, {@code onclicq}, {@code onclickq} — is a handler as far as Canoe is concerned and
     * must suppress. Those are the rows that used to fail, one per name per index, and they are F2
     * in miniature.
     */
    public static Stream<Arguments> handlerNameNearMisses() {
        Set<String> handlers = handlerNames();
        List<Arguments> rows = new ArrayList<>();
        for (String name : handlers) {
            for (String nearMiss : nearMissesOf(name, handlers)) {
                int expected = nearMiss.startsWith("on") ? Canoe.ATTR_JS : Canoe.ATTR_HTML;
                rows.add(Arguments.of(name, nearMiss, expected));
            }
        }
        return rows.stream();
    }

    /**
     * The value-prefix near misses that {@code detectAttributePrefix()} can actually see.
     *
     * <p>The extension row is generated only for prefixes shorter than ten characters. Canoe stops
     * buffering the attribute value at {@code bufLen == 10} (Canoe.java:918), so a colon at value
     * index 11 never calls {@code detectAttributePrefix()} at all — the boundary
     * {@code AttributePrefixTest} pins index by index. {@code javascriptq:} is therefore not a near
     * miss of {@code javascript:}; it is a value the prefix detector never looks at.
     */
    public static Stream<Arguments> valuePrefixNearMisses() {
        Map<String, Integer> recognised = recognisedValuePrefixes();
        List<Arguments> rows = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : recognised.entrySet()) {
            for (String nearMiss : nearMissesOf(entry.getKey(), recognised.keySet())) {
                if (nearMiss.length() > 10) {
                    continue;
                }
                rows.add(Arguments.of(entry.getKey(), nearMiss, entry.getValue()));
            }
        }
        return rows.stream();
    }

    // ------------------------------------------------------------------
    // The sweeps
    // ------------------------------------------------------------------

    /**
     * A near miss of a recognised attribute name gets the {@code ATTR_HTML} default.
     *
     * <p>{@code ATTR_HTML} is the right expectation today and it is also the finding: fail-open on
     * an unknown name is F3, and R5 inverts it. If that lands, this sweep flips wholesale to
     * {@code CTX_SUPPRESS}, which is a loud and correct failure.
     */
    @ParameterizedTest(name = "{1} is not {0}")
    @MethodSource("attributeNameNearMisses")
    public void aNearMissOfAnAttributeNameIsNotClassifiedAsThatName(String name, String nearMiss,
                                                                    int nameContext)
            throws IOException {
        int observed = new CanoeStateProbe().feed("<img " + nearMiss + "=\"").attributeContext();

        assertNotEquals(nameContext, observed,
                () -> nearMiss + " was classified as " + CanoeStateProbe.attributeContextName(nameContext)
                        + ", the context " + name + " gets. The comparison for " + name
                        + " matches a name it was not written for, which is the F1/F19 defect shape"
                        + " in the opposite direction.");
        assertEquals(Canoe.ATTR_HTML, observed,
                () -> nearMiss + " must fall through to the ATTR_HTML default, but got "
                        + CanoeStateProbe.attributeContextName(observed));
    }

    /**
     * Inverted by R4. Was the {@code on*} half of
     * {@link #aNearMissOfAnAttributeNameIsNotClassifiedAsThatName}, which required {@code onclicq},
     * {@code onmouseoveq} and 170-odd siblings to fall through to {@code ATTR_HTML}.
     *
     * <p>Every one of those rows was F2: a name one character away from a handler is a handler, or
     * is the handler the standard will define next year, and {@code html()}'s character references
     * are decoded by the HTML parser before an event handler value is compiled as JavaScript. The
     * rows are kept and their expectation flipped, because they are the densest available
     * demonstration that the classification survives every possible divergence — no truncation, no
     * substitution and no extension of a handler name can produce anything but {@code ATTR_JS},
     * unless it destroys the two characters the rule reads.
     */
    @ParameterizedTest(name = "{1} is a handler like {0}")
    @MethodSource("handlerNameNearMisses")
    public void aNearMissOfAHandlerNameIsAHandlerToo(String name, String nearMiss, int expected)
            throws IOException {
        int observed = new CanoeStateProbe().feed("<img " + nearMiss + "=\"").attributeContext();

        assertEquals(expected, observed,
                () -> nearMiss + " is a near miss of " + name + " and must classify as "
                        + CanoeStateProbe.attributeContextName(expected) + " but got "
                        + CanoeStateProbe.attributeContextName(observed)
                        + ". The prefix rule reads the first two characters and nothing else, so a"
                        + " divergence that leaves 'on' intact cannot change the answer and one that"
                        + " destroys it must.");
    }

    /**
     * A near miss of a recognised value prefix does not arm that prefix's context.
     *
     * <p>Probed through {@code href}, whose name-derived context is {@code ATTR_URI}. That used to
     * be invisible: {@code detectAttributePrefix()} reset the context to {@code ATTR_HTML} before it
     * compared anything and never restored it, so every row here answered {@code ATTR_HTML} whatever
     * the attribute was called. R2 deleted the reset, so the expectation is now {@code ATTR_URI} —
     * the name's own answer, surviving a comparison that failed — and this sweep has become a
     * statement about two things at once: the chain rejects the near miss, and rejecting it costs
     * the attribute nothing.
     */
    @ParameterizedTest(name = "{1}: is not {0}:")
    @MethodSource("valuePrefixNearMisses")
    public void aNearMissOfAValuePrefixIsNotClassifiedAsThatPrefix(String prefix, String nearMiss,
                                                                   int prefixContext)
            throws IOException {
        int observed = new CanoeStateProbe().feed("<a href=\"" + nearMiss + ":").attributeContext();

        assertNotEquals(prefixContext, observed,
                () -> nearMiss + ": was classified as "
                        + CanoeStateProbe.attributeContextName(prefixContext)
                        + ", the context " + prefix + ": gets");
        assertEquals(Canoe.ATTR_URI, observed,
                () -> nearMiss + ": must leave href's own ATTR_URI untouched, but got "
                        + CanoeStateProbe.attributeContextName(observed));
    }

    /**
     * The sweep must be able to fail, and the exact names must be what makes it.
     *
     * <p>&sect;2.4's rule applied to a generated corpus: a sweep whose inputs were all rejected for
     * some unrelated reason — an illegal attribute-name character, say — would be green over every
     * row and would look exactly like today's result. Each exact name is therefore run through the
     * identical probe and must produce the context its near misses must not.
     */
    @Test
    public void theExactNamesAreClassifiedAndTheSweepWouldNoticeIfTheyWereNot() throws IOException {
        for (Map.Entry<String, Integer> entry : recognisedAttributeNames().entrySet()) {
            assertEquals(entry.getValue(),
                    new CanoeStateProbe().feed("<img " + entry.getKey() + "=\"").attributeContext(),
                    () -> entry.getKey() + " must still be recognised, or its near misses are"
                            + " passing for a reason that has nothing to do with the chain");
        }
        for (String name : handlerNames()) {
            assertEquals(Canoe.ATTR_JS,
                    new CanoeStateProbe().feed("<img " + name + "=\"").attributeContext(),
                    () -> name + " must classify as JavaScript, or its near misses are agreeing with"
                            + " it for a reason that has nothing to do with the prefix rule");
        }
        for (Map.Entry<String, Integer> entry : recognisedValuePrefixes().entrySet()) {
            assertEquals(entry.getValue(),
                    new CanoeStateProbe().feed("<a href=\"" + entry.getKey() + ":")
                            .attributeContext(),
                    () -> entry.getKey() + ": must still be recognised");
        }
    }

    /**
     * The generator produces the shape it claims to: one row per index, plus one longer name.
     *
     * <p>Pinned as a count because the whole coverage argument is "every comparison in the chain
     * gets a false outcome", and that is only true if there is one input per comparison. A
     * generator that silently produced fewer rows would lower coverage without failing anything.
     */
    @Test
    public void theGeneratorProducesOneNearMissPerComparisonInTheChain() {
        Set<String> recognised = new LinkedHashSet<>(recognisedAttributeNames().keySet());
        assertEquals(List.of("q", "hq", "hrq", "hreq", "hrefq"),
                nearMissesOf("href", recognised),
                "four characters and a terminator is five comparisons, so five near misses");

        assertEquals(11, nearMissesOf("background", recognised).size(),
                "ten characters and a terminator");

        assertTrue(nearMissesOf("onclick", recognised).stream().noneMatch(recognised::contains),
                "a near miss that spells another recognised name is dropped");
    }

    // ------------------------------------------------------------------
    // Tag names, and three state-machine arms the corpus never lands on
    // ------------------------------------------------------------------

    /**
     * {@code <script>} and {@code <style>} are matched by the same shape of chain, in
     * {@code reallyProcessChar()} rather than in a helper, and get the same treatment.
     *
     * <p>The stakes are higher here than for an attribute name: a tag name that matched
     * {@code script} by accident would put the following markup into the {@code SCRIPT} state,
     * where every reference is suppressed — a silent availability failure of the F14 class — and one
     * that failed to match would put a reference into a live script block. F10 is the second half
     * of this claim, at the closing tag.
     */
    @ParameterizedTest(name = "<{0}> is ordinary HTML")
    @MethodSource("tagNameNearMisses")
    public void aNearMissOfScriptOrStyleIsAnOrdinaryElement(String tagName) throws IOException {
        CanoeStateProbe probe = new CanoeStateProbe().feed("<" + tagName + ">");
        assertEquals(Canoe.HTML, probe.state(),
                () -> "<" + tagName + "> left the parser in "
                        + CanoeStateProbe.stateName(probe.state())
                        + "; only <script> and <style> may change the state after a tag closes");
        assertEquals(Canoe.CTX_HTML, probe.currentContext(),
                () -> "a reference after <" + tagName + "> must be html-encoded, not suppressed");
    }

    public static Stream<Arguments> tagNameNearMisses() {
        Set<String> recognised = Set.of("script", "style");
        List<Arguments> rows = new ArrayList<>();
        for (String name : recognised) {
            for (String nearMiss : nearMissesOf(name, recognised)) {
                rows.add(Arguments.of(nearMiss));
            }
        }
        return rows.stream();
    }

    /** ...and the exact names do change it, so the sweep above is not vacuous. */
    @Test
    public void scriptAndStyleThemselvesDoChangeTheState() throws IOException {
        assertEquals(Canoe.SCRIPT, new CanoeStateProbe().feed("<script>").state());
        assertEquals(Canoe.CSS, new CanoeStateProbe().feed("<style>").state());
    }

    /**
     * Inverted by R3. Was {@code bufferResidueDisarmsEveryTenCharacterValuePrefix}: F5's buffer
     * residue disarmed all three ten-character value prefixes, not only {@code javascript:}.
     *
     * <p>{@code BufferResidueTest} measures the length table on {@code javascript} alone — which is
     * the right choice there, because that is the one with an exploit attached. It left the other two
     * chains' terminator comparison with only a true outcome, and "the same index therefore the same
     * behaviour" is an inference rather than a measurement. It was two lines to measure, and it is
     * two lines to keep measuring now that the answer has flipped: R3 replaced three separate
     * terminator tests with three separate length checks, and a fix that reached only the prefix with
     * the exploit attached would pass every other test in the suite.
     *
     * <p>{@code placeholder} is eleven characters, so it used to leave an {@code r} at
     * {@code buf[10]} that neither the {@code href} name nor the ten characters of the prefix
     * overwrote. It now writes into a buffer that is cleared again when the next value starts.
     */
    @ParameterizedTest(name = "{0}: survives an 11-character name")
    @MethodSource("tenCharacterValuePrefixes")
    public void noBufferResidueDisarmsAnyTenCharacterValuePrefix(String prefix, int cleanContext)
            throws IOException {
        assertEquals(cleanContext,
                new CanoeStateProbe().feed("<a href=\"" + prefix + ":").attributeContext(),
                () -> prefix + ": is recognised when nothing precedes it");

        CanoeStateProbe armed =
                new CanoeStateProbe().feed("<a placeholder=\"x\" href=\"" + prefix + ":");
        assertEquals('\0', armed.bufferAt(10),
                "R3: the eleventh character of 'placeholder' used to be the residue that decided"
                        + " this; the buffer is cleared when an attribute value starts");
        assertEquals(cleanContext, armed.attributeContext(),
                () -> "R3: " + prefix + ": must be recognised whatever precedes it. Before R2 a miss"
                        + " fell back to the reset's ATTR_HTML and after R2 to href's own ATTR_URI,"
                        + " which changed the encoder and not the finding; the length-checked"
                        + " comparison is what closes it.");
    }

    public static Stream<Arguments> tenCharacterValuePrefixes() {
        return Stream.of(
                Arguments.of("asfunction", Canoe.ATTR_ACTIONSCRIPT),
                Arguments.of("javascript", Canoe.ATTR_JS),
                Arguments.of("livescript", Canoe.ATTR_JS));
    }

    /**
     * The two name characters {@code isTagNameChar()} allows that no other test uses.
     *
     * <p>{@code isTagNameChar} allows {@code :} and {@code _} at any index and {@code -} and
     * {@code .} at any index but the first. The matrix elsewhere covers {@code :} (through
     * {@code xlink:href}) and {@code -} (through {@code data-*} and {@code aria-label}); the other
     * two operands of those two conditions had no input at all, which is a gap in the same place a
     * gap matters — {@code isTagNameChar} is what decides where an attribute name ends, and an
     * attribute name that ends in the wrong place is classified against the wrong buffer contents.
     */
    @ParameterizedTest(name = "<x {0}=\"\">")
    @MethodSource("unusualButLegalAttributeNames")
    public void theRemainingLegalNameCharactersAreAcceptedAsPlainText(String name)
            throws IOException {
        CanoeStateProbe probe = new CanoeStateProbe().feed("<x " + name + "=\"");
        assertEquals(Canoe.ATTR_HTML, probe.attributeContext(),
                () -> name + " is not a recognised name and must get the plain-text default");
        assertEquals(Canoe.CTX_HTML_ATTR, probe.currentContext(),
                () -> name + " must be a single attribute name, not two");
    }

    public static Stream<Arguments> unusualButLegalAttributeNames() {
        return Stream.of(
                Arguments.of("a_b"),
                Arguments.of("_leading"),
                Arguments.of("a.b"),
                Arguments.of("a-b"),
                Arguments.of("a:b"),
                Arguments.of("a1"));
    }

    /**
     * A valueless attribute may be followed by {@code /}, which closes an empty element.
     *
     * <p>The character after an attribute name is checked against whitespace, {@code >}, {@code =}
     * and {@code /} in one condition; the {@code /} operand is the last of the four and is the only
     * one no corpus template reaches, because the corpus writes {@code <img src="x">} rather than
     * {@code <img hidden/>}.
     */
    @Test
    public void aValuelessAttributeMayBeFollowedBySlash() {
        assertTrue(CanoeTestSupport.write("<img hidden/>").isError() == false,
                "an XHTML-style empty element after a valueless attribute must be accepted");
        assertEquals(Canoe.CTX_HTML, CanoeTestSupport.contextAfter("<img hidden/>"),
                "and it must leave the parser in body context");
    }
}
