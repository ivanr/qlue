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
 * <p>{@code setTagAttributeContext()} and {@code detectAttributePrefix()} are hand-unrolled
 * comparison chains — {@code buf[0]=='b' && buf[1]=='a' && buf[2]=='c' && …} — and every one of
 * those {@code &&} operands is a branch with two outcomes. The rest of the suite drives the
 * <em>true</em> path of each chain exhaustively: {@code CanoeStateMachineTest} names all 24
 * {@code on*} branches, {@code AttributeNameMatrixTest} partitions ninety names, and
 * {@code EventHandlerMatrixTest} probes every handler the HTML Standard defines. None of them
 * produces an input that reaches character <em>k</em> of a chain and then diverges, so before this
 * file the branch coverage of {@code setTagAttributeContext()} was <strong>65.6%</strong> — 135 of
 * 392 branch outcomes never taken. An unreached branch in either method is by definition an
 * untested security decision, which is the argument the coverage gate in {@code build.gradle}
 * rests on.
 *
 * <p>The inputs are therefore generated rather than chosen: for each recognised name and each
 * index into it, the name truncated at that index with one character replaced. That is the
 * cheapest input that makes exactly one comparison in the chain evaluate false, and it is also a
 * real security question — {@code hreq}, {@code onclicq} and {@code javascripq:} are what an
 * attacker's near-miss looks like, and a chain that accepted one of them would be a sink Canoe
 * classified by accident.
 *
 * <p>The <em>other</em> direction — an attribute name that Canoe fails to recognise when it should
 * — is F2 and F3, and lives in {@code EventHandlerMatrixTest} and {@code AttributeNameMatrixTest}.
 * This file cannot see those, because it only ever asserts that a non-name is not a name.
 *
 * <h2>What it deliberately does not reach</h2>
 *
 * <p>Three groups of branch outcome survive this sweep and are excluded from the gate rather than
 * chased, each with the test that proves it dead:
 *
 * <ul>
 *   <li>the whole {@code onselect}/{@code onsubmit} block, which sits inside {@code buf[0]=='o' &&
 *       buf[1]=='n'} and then tests {@code buf[0]=='s'} — <strong>F1</strong>, asserted by
 *       {@code CanoeStateMachineTest.everyDeclaredOnStarBranchNameIsClassified};
 *   <li>the {@code buf[10]=='\0'} terminator of the three ten-character value prefixes, which needs
 *       buffer residue at index 10 and is owned by {@code BufferResidueTest} (F5); and
 *   <li>{@code "Internal error #1001"} and the {@code attrQuotes} default arm, neither of which has
 *       a reachable input — see {@code CanoeRobustnessTest}.
 * </ul>
 */
public class NearMissNameSweepTest {

    /**
     * The attribute names {@code setTagAttributeContext()} has a comparison chain for, mapped to
     * the {@code ATTR_*} that chain assigns.
     *
     * <p>Two entries are not the names their comments claim, and both are findings rather than
     * transcription errors here. {@code onredystatechange} is the seventeen characters the
     * {@code onreadystatechange} chain actually compares (<strong>F19</strong>), so it is the name
     * whose near misses exercise that chain. {@code onselect} and {@code onsubmit} are absent,
     * because their block cannot be entered at all (<strong>F1</strong>) and a near miss of an
     * unreachable chain proves nothing.
     *
     * <p>The exact names and their expected contexts are asserted elsewhere — this map is used for
     * its <em>keys</em>, and the values are carried so that a near miss can be checked against the
     * classification it must not receive rather than against a bare {@code ATTR_HTML}.
     */
    private static Map<String, Integer> recognisedAttributeNames() {
        Map<String, Integer> names = new LinkedHashMap<>();

        // Non-handler chains. `data` yields ATTR_CONTENT because the branch commented `// content`
        // compares the characters of `data`; that is F7 and it is asserted in
        // AttributeNameMatrixTest.theSourceDeclaresExactlyTheNonHandlerBranchesTheMatrixExpects.
        names.put("background", Canoe.ATTR_URI);
        names.put("data", Canoe.ATTR_CONTENT);
        names.put("dynsrc", Canoe.ATTR_URI);
        names.put("lowsrc", Canoe.ATTR_URI);
        names.put("href", Canoe.ATTR_URI);
        names.put("src", Canoe.ATTR_URI);
        names.put("style", Canoe.ATTR_CSS);

        // The event handlers, taken from the table CanoeStateMachineTest asserts against the source
        // so that a branch added to Canoe.java cannot be missed here.
        for (Arguments row : (Iterable<Arguments>) CanoeStateMachineTest.declaredOnStarBranches()
                ::iterator) {
            String name = (String) row.get()[0];
            int expected = (Integer) row.get()[1];
            if (expected == Canoe.ATTR_JS) {
                names.put(name, Canoe.ATTR_JS);
            }
        }
        names.put("onredystatechange", Canoe.ATTR_JS);

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
     * an unknown name is F3, and remediation item 3 proposes inverting it. If that lands, this
     * sweep flips wholesale to {@code CTX_SUPPRESS}, which is a loud and correct failure.
     */
    @ParameterizedTest(name = "{1} is not {0}")
    @MethodSource("attributeNameNearMisses")
    public void aNearMissOfAnAttributeNameIsNotClassifiedAsThatName(String name, String nearMiss,
                                                                    int nameContext)
            throws IOException {
        int observed = new CanoeStateProbe().feed("<img " + nearMiss + "=\"").attributeContext();

        assertNotEquals(nameContext, observed,
                () -> nearMiss + " was classified as " + CanoeStateProbe.attributeContextName(nameContext)
                        + ", the context " + name + " gets. The comparison chain for " + name
                        + " matches a name it was not written for, which is the F1/F19 defect shape"
                        + " in the opposite direction.");
        assertEquals(Canoe.ATTR_HTML, observed,
                () -> nearMiss + " must fall through to the ATTR_HTML default, but got "
                        + CanoeStateProbe.attributeContextName(observed));
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
     * F5's buffer residue disarms all three ten-character value prefixes, not only
     * {@code javascript:}.
     *
     * <p>{@code BufferResidueTest}'s javadoc says {@code javascript}, {@code livescript} and
     * {@code asfunction} all read {@code buf[10]}, and it measures the length table on
     * {@code javascript} alone — which is the right choice there, because that is the one with an
     * exploit attached. It leaves the other two chains' terminator comparison with only a true
     * outcome, and "the same index therefore the same behaviour" is an inference rather than a
     * measurement. It is two lines to measure.
     *
     * <p>{@code placeholder} is eleven characters, so it leaves an {@code r} at {@code buf[10]} that
     * neither the {@code href} name nor the ten characters of the prefix overwrite.
     */
    @ParameterizedTest(name = "{0}: is disarmed by an 11-character name")
    @MethodSource("tenCharacterValuePrefixes")
    public void bufferResidueDisarmsEveryTenCharacterValuePrefix(String prefix, int cleanContext)
            throws IOException {
        assertEquals(cleanContext,
                new CanoeStateProbe().feed("<a href=\"" + prefix + ":").attributeContext(),
                () -> prefix + ": is recognised when the buffer is clean");

        CanoeStateProbe armed =
                new CanoeStateProbe().feed("<a placeholder=\"x\" href=\"" + prefix + ":");
        assertEquals('r', armed.bufferAt(10),
                "the eleventh character of 'placeholder' is the residue that decides this");
        assertEquals(Canoe.ATTR_URI, armed.attributeContext(),
                () -> "F5: " + prefix + ": is no longer recognised once buf[10] holds residue, so"
                        + " the same template is suppressed or url()-encoded depending on what"
                        + " element precedes it. Before R2 the miss fell back to the reset's"
                        + " ATTR_HTML; it now falls back to href's own ATTR_URI, which changes the"
                        + " encoder and not the finding - R3 is what makes the comparison"
                        + " length-checked so the prefix is recognised either way.");
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
