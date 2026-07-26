package com.webkreator.qlue.view.canoe.velocity;

import com.webkreator.qlue.view.Canoe;
import com.webkreator.qlue.view.CanoeStateProbe;
import com.webkreator.qlue.view.canoe.CanoeStateMachineTest;
import com.webkreator.qlue.view.canoe.CanoeTestSupport;
import com.webkreator.qlue.view.canoe.corpus.CanoeCorpus;
import com.webkreator.qlue.view.canoe.corpus.Payload;
import com.webkreator.qlue.view.canoe.corpus.Verdict;
import com.webkreator.qlue.view.canoe.corpus.VerdictEvaluator;
import com.webkreator.qlue.view.canoe.corpus.XssCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The event-handler matrix: every {@code on*} attribute name, and which side of Canoe's
 * hand-unrolled allowlist it falls on.
 *
 * <p>This is the highest-severity group in the suite. F1, F2 and F19 all live here, and all three
 * yield arbitrary script execution against an attacker who controls only data. It is also the group
 * whose absence let three dead branches survive a hand review that found two of them.
 *
 * <h2>What this file does that the corpus does not</h2>
 *
 * <p>The corpus is the per-name ledger: 115 names, one reviewed verdict each, asserted by {@code
 * CanoeCorpusTest.ledgerMatchesObservedBehaviour}. This file consumes those cases and adds the two
 * things a ledger cannot state about itself:
 *
 * <ul>
 *   <li><strong>The partition.</strong> Exactly 21 names classify as {@code ATTR_JS} and every other
 *       {@code on*} name in the matrix classifies as {@code ATTR_HTML}, with nothing in between and
 *       nothing else anywhere. A ledger of 115 individually-correct rows does not say that.
 *   <li><strong>The completeness guard.</strong>
 *       {@link #everySpecEventHandlerAttributeHasACorpusCase} enumerates the HTML Standard's event
 *       handler content attributes from a checked-in resource file and fails if any of them has no
 *       corpus case. Without it the group is "the handlers we thought of", which is exactly what
 *       {@code setTagAttributeContext()} is, and repeating a component's own mistake in the test
 *       that is supposed to catch it is how F2 stayed open for fifteen years.
 * </ul>
 *
 * <h2>Where the arithmetic lives</h2>
 *
 * <p>The "24 declared, 21 reachable" count belongs to {@code CanoeStateMachineTest}, which asserts
 * all 24 branches by name and reads the leaf branches back out of {@code Canoe.java} so its table
 * cannot drift from the source. This file does not repeat that scan; it asserts the same 21 names
 * from the other direction — by probing every name in the matrix and partitioning the results — so
 * that the two would have to be wrong in the same way at the same time to agree.
 */
public class EventHandlerMatrixTest {

    /** The Appendix A section the event-handler cases are filed under. */
    private static final String SECTION = "A.3 event handlers";

    /**
     * The checked-in list of HTML Standard event handler content attributes. Its header records
     * where it came from and when, and what is deliberately absent from it.
     */
    private static final String SPEC_LIST_RESOURCE = "/canoe/html-event-handler-attributes.txt";

    /**
     * The line in that file separating the content attributes from table 4's IDL-only names. It is a
     * marker rather than a convention about ordering, because "the last two lines are special" is
     * exactly the kind of rule that survives one refresh of the list and not two.
     */
    private static final String IDL_ONLY_MARKER = "#!idl-only";

    /**
     * The three names {@code setTagAttributeContext()} declares a branch for and can never reach.
     * They are separated from the other 87 misses throughout this file because the defect is a
     * different one: F2 is an omission, F1 and F19 are branches that were written, reviewed, and are
     * wrong at the character level.
     */
    private static final List<String> DECLARED_BUT_DEAD =
            List.of("onreadystatechange", "onselect", "onsubmit");

    // ------------------------------------------------------------------
    // The corpus, consumed
    // ------------------------------------------------------------------

    static List<XssCase> handlerCases() {
        return CanoeCorpus.inSection(SECTION);
    }

    static Stream<XssCase> recognisedHandlerCases() {
        return handlerCases().stream()
                .filter(c -> c.defaultVerdict().isSuppression())
                .filter(c -> !"onredystatechange".equals(c.attribute()));
    }

    static Stream<XssCase> unrecognisedHandlerCases() {
        return handlerCases().stream()
                .filter(c -> c.defaultVerdict() == Verdict.KNOWN_VULNERABLE)
                .filter(c -> !DECLARED_BUT_DEAD.contains(c.attribute()));
    }

    static Stream<XssCase> declaredButDeadHandlerCases() {
        return handlerCases().stream()
                .filter(c -> DECLARED_BUT_DEAD.contains(c.attribute()));
    }

    // ------------------------------------------------------------------
    // The 21 that work
    // ------------------------------------------------------------------

    /**
     * Every recognised handler suppresses, end to end through a real render.
     *
     * <p>Asserted against the render with an <em>empty</em> value rather than against a literal
     * expected string, so the test says "the payload contributed nothing" rather than "the output
     * looked like this", and so it keeps working if the surrounding template shape ever changes.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("recognisedHandlerCases")
    public void everyRecognisedHandlerSuppressesTheValue(XssCase testCase) {
        Payload payload = testCase.payloads().get(0);
        String rendered = VerdictEvaluator.render(testCase, payload.value()).output();
        String withNothing = VerdictEvaluator.render(testCase, "").output();

        assertEquals(withNothing, rendered,
                () -> testCase.attribute() + " is one of the 21 names Canoe recognises and must"
                        + " therefore emit nothing at all into the handler body. Rendered: "
                        + CanoeTestSupport.quote(rendered));

        String decoded = VerdictEvaluator.render(testCase, payload.value())
                .decodedAttr(testCase.selector(), testCase.attribute());
        assertFalse(decoded.contains(payload.value()),
                () -> testCase.attribute() + " let the payload through: " + decoded);
    }

    /**
     * {@code ondragdrop}, as a curiosity rather than as a defect.
     *
     * <p>It is the single clearest marker of the table's age. {@code ondragdrop} was a Netscape 4
     * event, removed from Gecko in Firefox 3; Canoe spends one of its twenty-one branches suppressing
     * a handler no engine has fired this century, while HTML5's {@code ondrop} and
     * {@code ondragstart} — which every engine fires — take the {@code ATTR_HTML} fall-through.
     *
     * <p>Note what is <em>not</em> done here: the corpus case is not flagged
     * {@code notBrowserObservable}, because that axis may only be set where it changes an
     * expectation, and a {@code SUPPRESSED_BY_DESIGN} row already expects browser silence. See
     * {@code CanoeCorpus.ONDRAGDROP_IS_DEAD}.
     */
    @Test
    public void canoeSuppressesADeadNetscapeEventAndMissesTheThreeThatReplacedIt() {
        assertEquals(Canoe.ATTR_JS, attributeContextOf("ondragdrop"),
                "ondragdrop is recognised");
        for (String replacement : List.of("ondrop", "ondragstart", "ondragend")) {
            assertEquals(Canoe.ATTR_HTML, attributeContextOf(replacement),
                    replacement + " is the HTML5 event that replaced it, and is not recognised."
                            + " ondrop fails the ondblclick chain at buf[3]=='r' and the ondragdrop"
                            + " chain at buf[4]=='o'.");
        }
    }

    // ------------------------------------------------------------------
    // The three that are written and cannot be taken (F1, F19)
    // ------------------------------------------------------------------

    /**
     * F1. The {@code onS} block at {@code Canoe.java:513-530} sits inside the guard
     * {@code if ((buf[0] == 'o') && (buf[1] == 'n'))} at line 334, and then opens with
     * {@code if (buf[0] == 's')}. Every sibling branch tests {@code buf[2]}, {@code buf[3]}, …; this
     * one restarts at {@code buf[0]}, so it asks whether the attribute is named {@code select} or
     * {@code submit} — impossible, because {@code buf[0] == 'o'} is already established.
     *
     * <p>The comparison that fails, spelled out so the next reader does not have to re-derive it:
     * for {@code onsubmit}, {@code buf[0]} holds {@code 'o'} and the branch tests it against
     * {@code 's'}.
     */
    @Test
    public void onselectAndOnsubmitTestTheWrongBufferIndex() {
        for (String name : List.of("onselect", "onsubmit")) {
            assertEquals(Canoe.ATTR_HTML, attributeContextOf(name),
                    "F1: " + name + " falls through to the ATTR_HTML default");
            assertEquals('o', bufferAt(name, 0),
                    "F1: the onS branch tests buf[0] == 's', and buf[0] is provably 'o' here because"
                            + " the enclosing block already required it. The branch is dead code.");
        }

        // ...and the names the dead branch would match, which are not attribute names at all.
        assertEquals(Canoe.ATTR_HTML, attributeContextOf("select"),
                "not that it helps: a bare 'select' attribute never enters the on* block either,"
                        + " because the guard at line 334 requires buf[0]=='o' && buf[1]=='n'. The"
                        + " branch matches nothing whatsoever.");
    }

    /**
     * F19. The third dead branch, and the one no amount of reading found: its guard is
     * {@code buf[2]=='r' && buf[3]=='e'} and its body then demands {@code buf[4]=='d'}, so the
     * comparands spell {@code on} + {@code re} + {@code dystatechange}. The {@code a} of "ready" is
     * missing.
     *
     * <p>Not unreachable — reachable by the wrong input, which is why a coverage tool would have
     * reported the line as covered had anything ever exercised it.
     */
    @Test
    public void onreadystatechangeSpellsItsNameWithoutTheA() {
        assertEquals(Canoe.ATTR_HTML, attributeContextOf("onreadystatechange"),
                "F19: the real attribute name is not recognised");
        assertEquals(Canoe.ATTR_JS, attributeContextOf("onredystatechange"),
                "F19: the misspelling the branch does match, which no document contains");
        assertEquals('a', bufferAt("onreadystatechange", 4),
                "F19: buf[4] holds the 'a' of 'ready' and the branch at Canoe.java:483-491 tests it"
                        + " against 'd'");
    }

    /**
     * All three dead branches, end to end: the attacker's quote reaches the JavaScript parser.
     *
     * <p>The assertion is on the <em>jsoup-decoded</em> attribute value, which is the whole point of
     * the review: a string assertion on Canoe's output would see
     * {@code &#39;&#41;&#59;__canoePwned…} and call it safe, and the HTML parser decodes every one of
     * those references back to the attacker's original characters before the value is compiled as
     * JavaScript.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("declaredButDeadHandlerCases")
    public void aDeclaredButDeadHandlerLetsTheQuoteThrough(XssCase testCase) {
        Payload quoteBreakout = testCase.payloads().stream()
                .filter(p -> "QUOTE_BREAKOUT".equals(p.family()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(testCase.id()
                        + " must carry a QUOTE_BREAKOUT payload; without one it cannot show that the"
                        + " string literal closes"));

        String decoded = VerdictEvaluator.render(testCase, quoteBreakout.value())
                .decodedAttr(testCase.selector(), testCase.attribute());

        assertTrue(decoded.contains(quoteBreakout.value()),
                () -> testCase.finding() + ": " + testCase.attribute() + " must hand the payload to"
                        + " the JavaScript parser verbatim. Decoded value: " + decoded);
        assertTrue(decoded.indexOf('\'') >= 0,
                () -> testCase.finding() + ": the apostrophe that closes the string literal must"
                        + " survive the parser's one decode. Decoded value: " + decoded);
    }

    // ------------------------------------------------------------------
    // The 87 the table has never heard of (F2)
    // ------------------------------------------------------------------

    /**
     * Every unrecognised handler is injectable, end to end, for the same reason and by the same
     * mechanism.
     *
     * <p>One payload per name, deliberately. The 91 names reach the identical fall-through and the
     * per-payload distinctions are properties of {@code html()} and of the JavaScript parser rather
     * than of the name, so they are pinned exhaustively on the four headline handlers — which carry
     * {@code QUOTE_BREAKOUT} and {@code ENTITY_BREAKOUT} together, the pairing that shows the parser
     * decodes exactly once. Multiplying 87 names by the payload catalogue would add run time and no
     * information.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("unrecognisedHandlerCases")
    public void everyUnrecognisedHandlerReachesTheJavaScriptParser(XssCase testCase) {
        Payload payload = testCase.payloads().get(0);
        String decoded = VerdictEvaluator.render(testCase, payload.value())
                .decodedAttr(testCase.selector(), testCase.attribute());

        assertTrue(decoded.contains(payload.value()),
                () -> "F2: " + testCase.attribute() + " takes the ATTR_HTML fall-through, so the"
                        + " payload must arrive at the JavaScript parser verbatim once the HTML"
                        + " parser has decoded the character references. Decoded value: " + decoded);
        assertTrue(decoded.indexOf('\'') >= 0,
                () -> "F2: the apostrophe must survive, or the string literal does not close and the"
                        + " row is not the vulnerability it claims to be. Decoded value: " + decoded);
    }

    /**
     * The near misses, which are what make the table impossible to audit by reading.
     *
     * <p>Each pair below differs by one or two characters and lands on opposite sides of the
     * allowlist. A reviewer who checks the left-hand name concludes the mechanism works.
     */
    @Test
    public void theSuppressedAndInjectableSetsAreSeparatedByOneOrTwoCharacters() {
        assertNearMiss("onmouseover", "onmouseenter", "onmouse enters the group branch and then"
                + " matches none of d/m/o/u at buf[7]");
        assertNearMiss("onmousemove", "onmouseleave", "same branch, same index");
        assertNearMiss("ondragdrop", "ondrag", "ondrag is a prefix of ondragdrop, and the branch"
                + " demands buf[6]=='d' where ondrag has its NUL terminator");
        assertNearMiss("onchange", "onratechange", "the group branch keys on buf[2], so a name that"
                + " merely ends in 'change' never reaches the onChange comparison");
        assertNearMiss("onload", "onloadstart", "onLoad demands buf[6]=='\\0'");
        assertNearMiss("onreset", "onscroll", "nothing under 'on' + 's' is reachable at all,"
                + " because the onS block tests buf[0]; see F1");
    }

    private static void assertNearMiss(String recognised, String missed, String why) {
        assertEquals(Canoe.ATTR_JS, attributeContextOf(recognised),
                recognised + " is recognised");
        assertEquals(Canoe.ATTR_HTML, attributeContextOf(missed),
                missed + " is not, and is one or two characters away from " + recognised + ": " + why);
    }

    // ------------------------------------------------------------------
    // The partition
    // ------------------------------------------------------------------

    /**
     * The matrix partitions into exactly two classifications, and the recognised half is exactly 21
     * names.
     *
     * <p>Asserted by probing every name the corpus carries, which is the opposite direction from
     * {@code CanoeStateMachineTest.everyDeclaredOnStarBranchNameIsClassified} — that one starts from
     * the branches the source declares and asks what each resolves to, this one starts from the names
     * that exist in the world and asks which of them the source catches. Both would have to be wrong
     * in the same way to agree.
     */
    @Test
    public void theMatrixPartitionsIntoTwentyOneRecognisedNamesAndEverythingElse() {
        Set<String> recognised = new LinkedHashSet<>();
        Set<String> unrecognised = new LinkedHashSet<>();

        for (XssCase testCase : handlerCases()) {
            String name = testCase.attribute();
            int classification = attributeContextOf(name);
            if (classification == Canoe.ATTR_JS) {
                recognised.add(name);
            } else if (classification == Canoe.ATTR_HTML) {
                unrecognised.add(name);
            } else {
                throw new AssertionError("on* name " + name + " classifies as "
                        + CanoeStateProbe.attributeContextName(classification)
                        + ", which is neither half of the partition this test asserts. A third"
                        + " classification for an event handler is a security decision and needs a"
                        + " finding, not a widened test.");
            }
        }

        // onredystatechange is F19's evidence rather than a real attribute name, so it is counted
        // out of the recognised set: it is a name no document contains.
        assertTrue(recognised.remove("onredystatechange"),
                "handler.onredystatechange is F19's evidence and must classify as ATTR_JS; if it"
                        + " stopped doing so, the F19 diagnosis was wrong");

        assertEquals(21, recognised.size(),
                () -> "Canoe must recognise exactly 21 event handler names. Recognised: "
                        + recognised);
        assertEquals(91 + DECLARED_BUT_DEAD.size(), unrecognised.size(),
                () -> "the matrix must carry 91 unrecognised names plus the three declared-but-dead"
                        + " ones. Unrecognised: " + unrecognised);
        assertTrue(unrecognised.containsAll(DECLARED_BUT_DEAD),
                "F1 and F19: onselect, onsubmit and onreadystatechange are declared and must still"
                        + " land in the unrecognised half");
    }

    /**
     * The measured arithmetic behind F2, stated as numbers rather than as "roughly forty".
     *
     * <p>F2's title said "roughly 40 modern event handlers" and its body listed 64 by hand. Measured
     * against the HTML Standard's own list, Canoe recognises <strong>18 of the 94</strong> event
     * handler content attributes the standard defines, and the three extra names it does recognise —
     * {@code ondragdrop}, {@code onend}, {@code onmove} — are not in that list.
     *
     * <p>The 94 is itself a correction. The first transcription of section 8.1.8.2 said 92: it
     * dropped the four {@code -webkit-} prefixed names table 1 defines (on the mistaken belief that
     * every animation and transition handler belonged to CSS Animations and CSS Transitions rather
     * than to HTML), and it counted table 4's {@code onreadystatechange} and
     * {@code onvisibilitychange} as content attributes when they are IDL attributes only. Two errors
     * pointing opposite ways, which is why the wrong total was close enough not to look wrong.
     *
     * <p>{@code onend} is not among the three "pre-standard" names in the sense the resource file's
     * header used to claim, and the difference is recorded there: it is a standardised SVG animation
     * event attribute (SVG 1.1 section 19) that Gecko fires. It is outside <em>this</em> list because
     * this list is derived from the HTML Standard, not because it is dead.
     */
    @Test
    public void canoeRecognisesEighteenOfTheNinetyFourSpecEventHandlers() throws IOException {
        List<String> spec = specEventHandlerAttributes();
        assertEquals(94, spec.size(),
                "the checked-in HTML Standard list has changed size; if that is a deliberate"
                        + " refresh, update this count and the numbers quoted in F2");

        List<String> recognised = new ArrayList<>();
        for (String name : spec) {
            if (attributeContextOf(name) == Canoe.ATTR_JS) {
                recognised.add(name);
            }
        }

        assertEquals(18, recognised.size(),
                () -> "Canoe recognises " + recognised.size() + " of the 94 event handler content"
                        + " attributes the HTML Standard defines: " + recognised);
        assertEquals(94 - 18, spec.size() - recognised.size(),
                "...and misses 76, which is the number F2's table carries");

        for (String extra : List.of("ondragdrop", "onend", "onmove")) {
            assertEquals(Canoe.ATTR_JS, attributeContextOf(extra),
                    extra + " is recognised by Canoe");
            assertFalse(spec.contains(extra),
                    extra + " is recognised by Canoe and is not in the HTML Standard's event handler"
                            + " content attribute tables, which is how 18 spec names plus 3 others"
                            + " make the 21");
        }

        // The two IDL-only names are excluded from the count and are not excluded from the corpus.
        // Both are unrecognised, so counting them would have made the miss 78 of 96 rather than
        // 76 of 94 - the same mechanism, a number that does not match the standard's own tables.
        List<String> idlOnly = specIdlOnlyAttributes();
        assertEquals(List.of("onreadystatechange", "onvisibilitychange"), idlOnly,
                "table 4 of section 8.1.8.2 is these two names and nothing else");
        for (String name : idlOnly) {
            assertEquals(Canoe.ATTR_HTML, attributeContextOf(name),
                    name + " is not recognised either, so excluding it from the 94 changes the"
                            + " denominator and not the ratio");
        }
    }

    /**
     * The 21 names the corpus records as recognised are exactly the names
     * {@code CanoeStateMachineTest}'s source-derived table says can be taken.
     *
     * <p>{@code CanoeCorpus.RECOGNISED_HANDLERS}'s javadoc has claimed this since T15 and cited this
     * test by name; the test did not exist. The claim was true and nothing asserted it, which is the
     * shape &sect;8 warns about — a cross-reference between two hand-maintained lists, where the
     * whole value is that they cannot drift apart.
     *
     * <p>Asserted as <strong>membership</strong> rather than as a count. Two lists of 21 names can
     * agree on their size and disagree on a name, and a name is what a security decision is made of:
     * the failure this guards against is somebody adding {@code ondrop} to one list and deleting
     * {@code ondragdrop} from the other.
     */
    @Test
    public void theRecognisedListMatchesTheStateMachineTable() {
        Set<String> fromTheStateMachineTable = new LinkedHashSet<>();
        for (Object[] row : CanoeStateMachineTest.declaredOnStarBranches()
                .map(a -> a.get()).toList()) {
            if ((Integer) row[1] == Canoe.ATTR_JS) {
                fromTheStateMachineTable.add((String) row[0]);
            }
        }

        Set<String> fromTheCorpus = new LinkedHashSet<>();
        for (XssCase testCase : handlerCases()) {
            if (testCase.defaultVerdict().isSuppression()
                    && !"onredystatechange".equals(testCase.attribute())) {
                fromTheCorpus.add(testCase.attribute());
            }
        }

        assertEquals(fromTheStateMachineTable, fromTheCorpus,
                "The corpus's recognised-handler list and CanoeStateMachineTest's declared-branch"
                        + " table disagree. The second is read back out of Canoe.java by"
                        + " theSourceDeclaresExactlyTheOnStarBranchesTheTableLists, so it is the one"
                        + " that tracks the source; a difference here means either a branch changed"
                        + " and CanoeCorpus.RECOGNISED_HANDLERS was not updated, or a corpus verdict"
                        + " was changed without the classification changing.");
        assertEquals(21, fromTheCorpus.size(),
                () -> "and there must be 21 of them: " + fromTheCorpus);
    }

    /** Every case in this group records either suppression or a cited vulnerability, never SAFE. */
    @Test
    public void noHandlerCaseIsMerelySafe() {
        for (XssCase testCase : handlerCases()) {
            Verdict verdict = testCase.defaultVerdict();
            assertTrue(verdict.isSuppression() || verdict == Verdict.KNOWN_VULNERABLE,
                    () -> testCase.id() + " records " + verdict + ". An event handler value is"
                            + " either suppressed or compiled as JavaScript; there is no third"
                            + " outcome, and a SAFE row here would mean the sink was mis-declared.");
            if (verdict == Verdict.KNOWN_VULNERABLE) {
                assertNotNull(testCase.finding(), testCase.id() + " cites no finding");
            }
        }
    }

    // ------------------------------------------------------------------
    // The completeness guard
    // ------------------------------------------------------------------

    /**
     * Every event handler content attribute the HTML Standard defines has a corpus case.
     *
     * <p>This is the point of the task. Without it the group is "the handlers we happened to think
     * of" — which is exactly what {@code setTagAttributeContext()} is, and repeating the mistake
     * inside the test that exists to catch it would be worse than having no test, because it would
     * look like coverage.
     *
     * <p>The list is checked in at {@code src/test/resources/canoe/html-event-handler-attributes.txt}
     * with its provenance and transcription date in the header. When it is next refreshed against a
     * newer revision of the standard, this test fails until every new name has been classified and
     * ledgered — which is the failure the guard exists to produce.
     */
    @Test
    public void everySpecEventHandlerAttributeHasACorpusCase() throws IOException {
        Set<String> covered = coveredAttributeNames();

        List<String> missing = namesWithNoCase(specEventHandlerAttributes(), covered);
        assertTrue(missing.isEmpty(),
                () -> "The HTML Standard defines these event handler content attributes and the"
                        + " corpus has no case for them: " + missing
                        + "\nAdd each to CanoeCorpus.RECOGNISED_HANDLERS or"
                        + " CanoeCorpus.UNRECOGNISED_HANDLERS after checking which side of"
                        + " setTagAttributeContext()'s allowlist it falls on. A name with no case is"
                        + " an unreviewed security decision.");

        // The two IDL-only names are out of the count and in the corpus, which is the point of
        // carrying them: F19 is about onreadystatechange, which an author can still write as an
        // attribute even though nothing will fire it.
        List<String> missingIdl = namesWithNoCase(specIdlOnlyAttributes(), covered);
        assertTrue(missingIdl.isEmpty(),
                () -> "the IDL-only names are excluded from the 94 and not from the corpus, and"
                        + " these have no case: " + missingIdl);
    }

    /**
     * Which of {@code spec} has no corpus case. Extracted so that the guard above and its self-test
     * below run the <em>same</em> code rather than the same idea.
     */
    static List<String> namesWithNoCase(List<String> spec, Set<String> covered) {
        List<String> missing = new ArrayList<>();
        for (String name : spec) {
            if (!covered.contains(name)) {
                missing.add(name);
            }
        }
        return missing;
    }

    private static Set<String> coveredAttributeNames() {
        Set<String> covered = new LinkedHashSet<>();
        for (XssCase testCase : handlerCases()) {
            covered.add(testCase.attribute());
        }
        return covered;
    }

    /**
     * Proves the guard above can fail, and that it is reading a real list.
     *
     * <p>This test used to say in its javadoc that it "proves the guard above can fail" and then only
     * sanity-check the resource file — the guard's {@code missing.isEmpty()} logic was never run
     * against a name that ought to be missing, so the one thing it claimed to establish was the one
     * thing it did not. {@link #namesWithNoCase} is now the guard's own body, and the assertion below
     * drives it with a name no corpus case can have. Compare {@code
     * CanoeCorpusTest.theLedgerOracleDetectsAWrongVerdict}, which builds a deliberately wrong case
     * and watches the real oracle disagree with it.
     *
     * <p>The file checks stay, because they answer a different question: a completeness guard that
     * silently reads an empty or truncated resource passes for the wrong reason and is
     * indistinguishable from one that works.
     */
    @Test
    public void theGuardWouldNoticeAMissingName() throws IOException {
        Set<String> covered = coveredAttributeNames();

        // The guard's own logic, run against a name that must be missing.
        assertEquals(List.of("onbogus"), namesWithNoCase(List.of("onbogus"), covered),
                "everySpecEventHandlerAttributeHasACorpusCase decides what is missing with this"
                        + " method, so if it cannot report a name the corpus has never heard of, the"
                        + " guard passes vacuously no matter what the resource file says");
        assertTrue(namesWithNoCase(List.of("onclick", "onfocus"), covered).isEmpty(),
                "...and it must not report names that ARE covered, or every run is a false alarm");

        List<String> spec = specEventHandlerAttributes();
        assertTrue(spec.size() > 80,
                "the spec list is suspiciously short (" + spec.size() + "); a truncated or"
                        + " unreadable resource would make the completeness guard pass vacuously");
        for (String name : spec) {
            assertTrue(name.startsWith("on"),
                    "every name in the list must be an on* attribute; got " + name);
        }
        assertTrue(spec.contains("onsubmit") && spec.contains("onbeforetoggle")
                        && spec.contains("onwebkitanimationstart"),
                "the list must contain the names the findings turn on, or it is not the list it"
                        + " claims to be");
        assertFalse(spec.contains("onreadystatechange"),
                "F19's attribute is an IDL attribute on Document, not a content attribute, so it"
                        + " belongs below the #!idl-only marker and out of the 94");

        // A name the standard does not define must not be in the file, or the guard would be
        // demanding cases for attributes that do not exist.
        assertFalse(spec.contains("ondragdrop"),
                "ondragdrop is a Netscape 4 name and must not appear in a list derived from the"
                        + " HTML Standard; the header records it among the deliberate exclusions");

        // ...and the corpus set the guard compares against must be non-trivial too.
        assertTrue(handlerCases().size() > 100,
                "section " + SECTION + " has only " + handlerCases().size() + " cases, which is"
                        + " fewer than the matrix declares");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * The event handler <em>content attributes</em> the checked-in list carries: everything above the
     * {@code #!idl-only} marker, comments and blank lines stripped.
     */
    private static List<String> specEventHandlerAttributes() throws IOException {
        return readSpecList().get(false);
    }

    /**
     * The names below the {@code #!idl-only} marker: table 4 of section 8.1.8.2, which the standard
     * defines as IDL attributes on {@code Document} and <em>not</em> as content attributes. They are
     * excluded from every count in this file and included in the corpus; see
     * {@link #canoeRecognisesEighteenOfTheNinetyFourSpecEventHandlers}.
     */
    private static List<String> specIdlOnlyAttributes() throws IOException {
        return readSpecList().get(true);
    }

    /** The checked-in list, split at the {@code #!idl-only} marker. */
    private static Map<Boolean, List<String>> readSpecList() throws IOException {
        List<String> contentAttributes = new ArrayList<>();
        List<String> idlOnly = new ArrayList<>();
        List<String> current = contentAttributes;
        try (InputStream in = EventHandlerMatrixTest.class.getResourceAsStream(SPEC_LIST_RESOURCE)) {
            assertNotNull(in, "cannot read " + SPEC_LIST_RESOURCE + " from the test classpath");
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (IDL_ONLY_MARKER.equals(trimmed)) {
                    current = idlOnly;
                    continue;
                }
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                current.add(trimmed);
            }
        }
        assertFalse(idlOnly.isEmpty(),
                "the " + IDL_ONLY_MARKER + " marker is missing from " + SPEC_LIST_RESOURCE
                        + ", so table 4's two IDL attributes would be counted as content attributes"
                        + " and every number in F2 would be two too high");

        List<String> everything = new ArrayList<>(contentAttributes);
        everything.addAll(idlOnly);
        assertEquals(everything.size(), new LinkedHashSet<>(everything).size(),
                "the spec list contains a duplicate name");

        Map<Boolean, List<String>> split = new LinkedHashMap<>();
        split.put(false, contentAttributes);
        split.put(true, idlOnly);
        return split;
    }

    /**
     * The {@code ATTR_*} value {@code setTagAttributeContext()} derives from an attribute name, on a
     * fresh {@link Canoe} so that nothing but the name itself is in the buffer. F5's residue was a
     * separate axis and mixing it in here would have made a failure ambiguous between "the branch is
     * dead" and "an earlier name armed the buffer"; R3 clears the buffer on every reuse, so the
     * fresh Canoe is now a convention rather than a precaution.
     */
    private static int attributeContextOf(String attributeName) {
        try {
            return new CanoeStateProbe().feed("<div " + attributeName + "=\"").attributeContext();
        } catch (IOException e) {
            throw new AssertionError("Canoe rejected the attribute name " + attributeName, e);
        }
    }

    /**
     * The buffer as {@code setTagAttributeContext()} saw it, which means stopping at the {@code =}
     * rather than after the opening quote.
     *
     * <p>The quote starts the attribute value, and since R3 the value scan clears the buffer before
     * writing into it, so a probe fed one character further would read a cleared buffer and the two
     * F1/F19 assertions above would be measuring nothing. The classification itself is unaffected —
     * it happens when the name ends — which is why {@link #attributeContextOf(String)} still feeds
     * the quote.
     */
    private static char bufferAt(String attributeName, int index) {
        try {
            return new CanoeStateProbe().feed("<div " + attributeName + "=").bufferAt(index);
        } catch (IOException e) {
            throw new AssertionError("Canoe rejected the attribute name " + attributeName, e);
        }
    }
}
