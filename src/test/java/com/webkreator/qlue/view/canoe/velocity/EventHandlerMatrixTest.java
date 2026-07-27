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
 * The event-handler matrix: every {@code on*} attribute name, and the one rule that now classifies
 * all of them.
 *
 * <p>This was the highest-severity group in the suite. F1, F2 and F19 all lived here, and all three
 * yielded arbitrary script execution against an attacker who controls only data. It is also the
 * group whose absence let three dead branches survive a hand review that found two of them.
 *
 * <p><strong>R4 closed all three</strong> by deleting the hand-unrolled allowlist and putting a
 * prefix rule in its place: any attribute whose name begins {@code on} is {@code ATTR_JS}. Every
 * test below that measured which side of the allowlist a name fell on has been inverted rather than
 * deleted, because the names are the regression net — an allowlist re-introduced under any name
 * fails on the 91 rows it forgets.
 *
 * <h2>What this file does that the corpus does not</h2>
 *
 * <p>The corpus is the per-name ledger: 117 names, one reviewed verdict each, asserted by {@code
 * CanoeCorpusTest.ledgerMatchesObservedBehaviour}. This file consumes those cases and adds the two
 * things a ledger cannot state about itself:
 *
 * <ul>
 *   <li><strong>The partition.</strong> Every {@code on*} name in the matrix classifies as
 *       {@code ATTR_JS}, with nothing on the other side and nothing else anywhere. It used to be a
 *       21/94 split; it is a partition of 117 real names (118 corpus rows, counting F19's
 *       {@code onredystatechange} evidence) into "all handlers" and "nothing" now, and a ledger of
 *       117 individually-correct rows does not say either.
 *   <li><strong>The completeness guard.</strong>
 *       {@link #everySpecEventHandlerAttributeHasACorpusCase} enumerates the HTML Standard's event
 *       handler content attributes from a checked-in resource file and fails if any of them has no
 *       corpus case. Without it the group is "the handlers we thought of", which is exactly what
 *       {@code setTagAttributeContext()} used to be, and repeating a component's own mistake in the
 *       test that is supposed to catch it is how F2 stayed open for fifteen years. The prefix rule
 *       is what makes the guard permanently satisfiable rather than a list to catch up with.
 * </ul>
 *
 * <h2>Where the arithmetic lives</h2>
 *
 * <p>The "24 declared, 21 reachable" count belonged to {@code CanoeStateMachineTest}, which asserted
 * all 24 branches by name and read the leaf branches back out of {@code Canoe.java} so its table
 * could not drift from the source. There are no branches to count now, and that file's inverted
 * version asserts the source contains no per-name comparison at all. This file asserts the same
 * thing from the other direction — by probing every name in the matrix and partitioning the results
 * — so that the two would have to be wrong in the same way at the same time to agree.
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
     * The three names the deleted {@code on*} table declared a branch for and could never reach.
     * They are kept separate from the other 91 misses throughout this file because the defect was a
     * different one: F2 was an omission, F1 and F19 were branches that had been written, reviewed,
     * and were wrong at the character level.
     */
    private static final List<String> DECLARED_BUT_DEAD =
            List.of("onreadystatechange", "onselect", "onsubmit");

    /**
     * The 21 names the deleted table could reach, taken from
     * {@code CanoeStateMachineTest.namesTheOldOnStarTableDeclared} minus {@link #DECLARED_BUT_DEAD}.
     *
     * <p>The split is derived rather than duplicated, and it is derived from the file that used to
     * read the branches back out of {@code Canoe.java}, so the two halves of the old partition
     * cannot drift apart now that the source no longer says what they are.
     */
    private static Set<String> namesTheOldTableCouldReach() {
        Set<String> reachable = new LinkedHashSet<>();
        for (Object[] row : CanoeStateMachineTest.namesTheOldOnStarTableDeclared()
                .map(a -> a.get()).toList()) {
            String name = (String) row[0];
            if (!DECLARED_BUT_DEAD.contains(name)) {
                reachable.add(name);
            }
        }
        return reachable;
    }

    // ------------------------------------------------------------------
    // The corpus, consumed
    // ------------------------------------------------------------------

    static List<XssCase> handlerCases() {
        return CanoeCorpus.inSection(SECTION);
    }

    /**
     * The 21 the old table reached, selected by name rather than by verdict.
     *
     * <p>It used to select on {@code defaultVerdict().isSuppression()}, which was the same set while
     * the two halves had different verdicts. Every handler case is a suppression now, so a
     * verdict-based filter would silently widen this stream to all 117 and the inverted sibling
     * below would run on nothing — the failure mode where a parameterised test passes because its
     * source is empty.
     */
    static Stream<XssCase> casesTheOldTableReached() {
        Set<String> reachable = namesTheOldTableCouldReach();
        return handlerCases().stream()
                .filter(c -> reachable.contains(c.attribute()));
    }

    /**
     * The 93 the old table had never heard of: everything but the 21 and the three dead ones.
     *
     * <p>91 until R28, which added the two SVG animation names {@code onbegin} and {@code onrepeat}
     * — the gap Appendix A &sect;A.3 had recorded rather than closed. They belong on this side by
     * the same rule as everything else here: the deleted table had no branch for either.
     */
    static Stream<XssCase> casesTheOldTableMissed() {
        Set<String> reachable = namesTheOldTableCouldReach();
        return handlerCases().stream()
                .filter(c -> !reachable.contains(c.attribute()))
                .filter(c -> !DECLARED_BUT_DEAD.contains(c.attribute()))
                .filter(c -> !"onredystatechange".equals(c.attribute()));
    }

    static Stream<XssCase> declaredButDeadHandlerCases() {
        return handlerCases().stream()
                .filter(c -> DECLARED_BUT_DEAD.contains(c.attribute()));
    }

    // ------------------------------------------------------------------
    // The 21 the old table reached
    // ------------------------------------------------------------------

    /**
     * Every handler the old table reached suppresses, end to end through a real render.
     *
     * <p>Asserted against the render with an <em>empty</em> value rather than against a literal
     * expected string, so the test says "the payload contributed nothing" rather than "the output
     * looked like this", and so it keeps working if the surrounding template shape ever changes.
     *
     * <p>Unchanged by R4 in outcome. It is kept as a separate stream from the 91 below so that a
     * change which suppresses one half and not the other fails on the half it broke rather than
     * somewhere in a 117-row sweep.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("casesTheOldTableReached")
    public void everyHandlerTheOldTableReachedSuppressesTheValue(XssCase testCase) {
        assertSuppresses(testCase, "one of the 21 names the old on* table could reach");
    }

    /**
     * {@code ondragdrop}, inverted by R4. Was
     * {@code canoeSuppressesADeadNetscapeEventAndMissesTheThreeThatReplacedIt}.
     *
     * <p>It was the single clearest marker of the table's age. {@code ondragdrop} is a Netscape 4
     * event, removed from Gecko in Firefox 3; Canoe spent one of its twenty-one branches suppressing
     * a handler no engine has fired this century, while HTML5's {@code ondrop} and
     * {@code ondragstart} — which every engine fires — took the {@code ATTR_HTML} fall-through. The
     * observation was never about those four names: it was that a hand-maintained table ages, and
     * that the direction it ages in is towards suppressing what cannot run and passing what can.
     *
     * <p>All four are one statement now, which is the answer to the observation rather than a repair
     * of it. The four names are kept because they are the cheapest demonstration in the suite that
     * the rule is not a table with better contents.
     */
    @Test
    public void theDeadNetscapeEventAndTheThreeThatReplacedItAreOneStatementNow() {
        assertEquals(Canoe.ATTR_JS, attributeContextOf("ondragdrop"),
                "ondragdrop was one of the 21 the old table reached");
        for (String replacement : List.of("ondrop", "ondragstart", "ondragend")) {
            assertEquals(Canoe.ATTR_JS, attributeContextOf(replacement),
                    replacement + " is an HTML5 event the old table missed - ondrop failed the"
                            + " ondblclick chain at buf[3]=='r' and the ondragdrop chain at"
                            + " buf[4]=='o' - and the prefix rule classifies it like every other"
                            + " name beginning 'on'.");
        }
    }

    /**
     * The render-and-compare the suppression assertions share: the payload must contribute nothing
     * to the output, and must not appear in the decoded attribute value.
     */
    private static void assertSuppresses(XssCase testCase, String why) {
        Payload payload = testCase.payloads().get(0);
        String rendered = VerdictEvaluator.render(testCase, payload.value()).output();
        String withNothing = VerdictEvaluator.render(testCase, "").output();

        assertEquals(withNothing, rendered,
                () -> testCase.attribute() + " is " + why + " and must emit nothing at all into the"
                        + " handler body. Rendered: " + CanoeTestSupport.quote(rendered));

        String decoded = VerdictEvaluator.render(testCase, payload.value())
                .decodedAttr(testCase.selector(), testCase.attribute());
        assertFalse(decoded.contains(payload.value()),
                () -> testCase.attribute() + " let the payload through: " + decoded);
    }

    // ------------------------------------------------------------------
    // The three that were written and could not be taken (F1, F19)
    // ------------------------------------------------------------------

    /**
     * F1, inverted by R4. Was {@code onselectAndOnsubmitTestTheWrongBufferIndex}.
     *
     * <p>The {@code onS} block sat inside the guard
     * {@code if ((buf[0] == 'o') && (buf[1] == 'n'))} and then opened with
     * {@code if (buf[0] == 's')}. Every sibling branch tested {@code buf[2]}, {@code buf[3]}, …;
     * that one restarted at {@code buf[0]}, so it asked whether the attribute was named
     * {@code select} or {@code submit} — impossible, because {@code buf[0] == 'o'} was already
     * established. For {@code onsubmit}, {@code buf[0]} held {@code 'o'} and the branch tested it
     * against {@code 's'}.
     *
     * <p>Both names classify as JavaScript now. The buffer probe is kept and inverted: {@code buf[0]}
     * still holds the {@code 'o'} that made the branch dead, and the point is that nothing compares
     * it against anything.
     */
    @Test
    public void onselectAndOnsubmitAreClassifiedByThePrefixRuleNow() {
        for (String name : List.of("onselect", "onsubmit")) {
            assertEquals(Canoe.ATTR_JS, attributeContextOf(name),
                    "R4: " + name + " is classified by the on-prefix rule");
            assertEquals('o', bufferAt(name, 0),
                    "buf[0] still holds the 'o' that made the onS branch dead code; the branch that"
                            + " tested it against 's' is gone with the rest of the table");
        }

        // ...and the names the dead branch would have matched, which are not event handlers at all.
        // R5 has landed and inverted the default this line used to assert: a bare 'select' is on
        // none of Canoe's lists, so it is dropped rather than html-encoded. What the row is for is
        // unchanged - the prefix rule is a rule about names beginning "on", not about the words the
        // dead branch happened to spell - and it must not be classified as script.
        assertEquals(Canoe.ATTR_UNKNOWN, attributeContextOf("select"),
                "a bare 'select' attribute is not a handler and must reach R5's fail-closed default"
                        + " rather than ATTR_JS");
    }

    /**
     * F19, inverted by R4. Was {@code onreadystatechangeSpellsItsNameWithoutTheA}.
     *
     * <p>The third dead branch, and the one no amount of reading found: its guard was
     * {@code buf[2]=='r' && buf[3]=='e'} and its body then demanded {@code buf[4]=='d'}, so the
     * comparands spelled {@code on} + {@code re} + {@code dystatechange}. The {@code a} of "ready"
     * was missing. Not unreachable — reachable by the wrong input, which is why a coverage tool
     * would have reported the line as covered had anything ever exercised it.
     *
     * <p>The real name and the misspelling are indistinguishable now: the prefix rule reads two
     * characters, and {@code buf[4]} is not one of them.
     */
    @Test
    public void onreadystatechangeAndItsMisspellingAreClassifiedAlike() {
        assertEquals(Canoe.ATTR_JS, attributeContextOf("onreadystatechange"),
                "R4: the real attribute name is classified by the on-prefix rule");
        assertEquals(Canoe.ATTR_JS, attributeContextOf("onredystatechange"),
                "R4: and so is the misspelling the dead branch matched, which no document contains");
        assertEquals('a', bufferAt("onreadystatechange", 4),
                "buf[4] still holds the 'a' of 'ready' that the dead branch tested against 'd'; the"
                        + " comparison is gone, so the character decides nothing");
    }

    /**
     * All three dead branches, inverted end to end. Was
     * {@code aDeclaredButDeadHandlerLetsTheQuoteThrough}, which required the attacker's quote to
     * reach the JavaScript parser.
     *
     * <p>The assertion still runs on the <em>jsoup-decoded</em> attribute value, because that is
     * what makes it mean anything: a string assertion on Canoe's output would have seen
     * {@code &#39;&#41;&#59;__canoePwned…} and called it safe when the HTML parser was about to
     * decode every one of those references back before the value was compiled as JavaScript. The
     * decoded value must now be free of the payload entirely.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("declaredButDeadHandlerCases")
    public void aDeadBranchNameIsSuppressedLikeEveryOtherHandler(XssCase testCase) {
        Payload quoteBreakout = testCase.payloads().stream()
                .filter(p -> "QUOTE_BREAKOUT".equals(p.family()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(testCase.id()
                        + " must carry a QUOTE_BREAKOUT payload; without one it cannot show that the"
                        + " string literal stays closed"));

        String decoded = VerdictEvaluator.render(testCase, quoteBreakout.value())
                .decodedAttr(testCase.selector(), testCase.attribute());
        String benign = VerdictEvaluator.render(testCase, "")
                .decodedAttr(testCase.selector(), testCase.attribute());

        assertFalse(decoded.contains(quoteBreakout.value()),
                () -> testCase.finding() + ", closed by R4: " + testCase.attribute() + " must hand"
                        + " the JavaScript parser nothing of the payload. Decoded value: " + decoded);
        assertEquals(benign, decoded,
                () -> testCase.finding() + ", closed by R4: the decoded handler body must be the"
                        + " template's own text and nothing else. The apostrophes it contains are"
                        + " the template's; the payload's must not add any. Decoded value: "
                        + decoded);
    }

    // ------------------------------------------------------------------
    // The 93 the table had never heard of: F2's 91, plus R28's two SVG animation names
    // ------------------------------------------------------------------

    /**
     * F2, inverted by R4 across all 91 of its rows. Was
     * {@code everyUnrecognisedHandlerReachesTheJavaScriptParser}, which required the payload to
     * arrive at the JavaScript parser verbatim once the HTML parser had decoded the character
     * references {@code html()} wrote.
     *
     * <p>The 91 names reached the identical {@code ATTR_HTML} fall-through and now reach the
     * identical prefix rule, so the suppression is asserted the same way for all of them: the render
     * with the payload must be byte-identical to the render with an empty value, and the decoded
     * attribute must not contain the payload.
     *
     * <p>One payload per name, deliberately, as before. The per-payload distinctions are properties
     * of {@code html()} and of the JavaScript parser rather than of the name, and they are pinned
     * exhaustively on the four headline handlers — which carry {@code QUOTE_BREAKOUT} and
     * {@code ENTITY_BREAKOUT} together. Multiplying 91 names by the payload catalogue would add run
     * time and no information.
     *
     * <p>The stream is 93 since R28, not 91. {@code onbegin} and {@code onrepeat} are not among the
     * names F2 enumerated — F2 is an HTML-Standard-shaped finding and these are SVG 1.1 attributes —
     * but they took the same {@code ATTR_HTML} fall-through for the same reason and they are
     * asserted here for the same reason. The finding's own count stays 91 everywhere it is quoted;
     * see {@code CanoeCorpus.SVG_ANIMATION_HANDLERS}.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("casesTheOldTableMissed")
    public void everyHandlerTheOldTableMissedIsSuppressedToo(XssCase testCase) {
        assertSuppresses(testCase, "closed by R4: one of the 93 names the old on* table had never"
                + " heard of - F2's 91, plus onbegin and onrepeat, which R28 added - and classified"
                + " by the prefix rule that replaced it");
    }

    /**
     * The near misses, inverted by R4. Was
     * {@code theSuppressedAndInjectableSetsAreSeparatedByOneOrTwoCharacters}.
     *
     * <p>Each pair below differs by one or two characters and used to land on opposite sides of the
     * allowlist, which is what made the table impossible to audit by reading: a reviewer who checked
     * the left-hand name concluded the mechanism worked. The pairs are kept, with the comparison
     * that separated them still named, because a rule that ever starts distinguishing them again is
     * a table by another name — and these six are where that would show first.
     */
    @Test
    public void theNearMissPairsAreNoLongerSeparatedAtAll() {
        assertNoLongerANearMiss("onmouseover", "onmouseenter", "onmouseenter entered the onmouse"
                + " group branch and matched none of d/m/o/u at buf[7]");
        assertNoLongerANearMiss("onmousemove", "onmouseleave", "same branch, same index");
        assertNoLongerANearMiss("ondragdrop", "ondrag", "ondrag is a prefix of ondragdrop, and the"
                + " branch demanded buf[6]=='d' where ondrag has its NUL terminator");
        assertNoLongerANearMiss("onchange", "onratechange", "the group branch keyed on buf[2], so a"
                + " name that merely ends in 'change' never reached the onChange comparison");
        assertNoLongerANearMiss("onload", "onloadstart", "onLoad demanded buf[6]=='\\0'");
        assertNoLongerANearMiss("onreset", "onscroll", "nothing under 'on' + 's' was reachable at"
                + " all, because the onS block tested buf[0]; see F1");
    }

    private static void assertNoLongerANearMiss(String wasRecognised, String wasMissed, String why) {
        assertEquals(Canoe.ATTR_JS, attributeContextOf(wasRecognised),
                wasRecognised + " was on the recognised side of the old table and must stay"
                        + " suppressed");
        assertEquals(Canoe.ATTR_JS, attributeContextOf(wasMissed),
                wasMissed + " was one or two characters away from " + wasRecognised + " and on the"
                        + " other side of the allowlist: " + why + ". Under the prefix rule the two"
                        + " are the same case, and if they ever differ again the rule has grown a"
                        + " table.");
    }

    // ------------------------------------------------------------------
    // The partition
    // ------------------------------------------------------------------

    /**
     * The partition, inverted by R4. Was
     * {@code theMatrixPartitionsIntoTwentyOneRecognisedNamesAndEverythingElse}: exactly 21 names
     * classified as {@code ATTR_JS} and the other 94 as {@code ATTR_HTML}.
     *
     * <p>It is a partition of 117 real names into "all handlers" and "nothing" now. The assertion
     * below counts 118, and the difference is one deliberate row: {@code onredystatechange}, F19's
     * evidence, is a name no document contains but the corpus carries, and it must land in the
     * {@code ATTR_JS} half like everything else — so the arithmetic is the 21 the old table
     * reached, the 3 it declared and could not, the 93 it had never heard of, plus the misspelling.
     *
     * <p>The total was 116 until R28, which closed &sect;A.3's recorded gap by adding
     * {@code onbegin} and {@code onrepeat}. Neither is an HTML Standard event handler content
     * attribute, so neither moves the 94 the completeness guard reads from the checked-in list;
     * they are two more names "that exist in the world", which is the question this test asks.
     * That is the whole of F1, F2 and F19 in one assertion: the empty half is the one that used to
     * hold 94 names, three of them with a branch written for them that could not be taken.
     *
     * <p>Asserted by probing every name the corpus carries, which is the opposite direction from
     * {@code CanoeStateMachineTest.everyNameTheOldOnStarTableDeclaredIsClassifiedAsJavascript} —
     * that one starts from the names the deleted table declared and asks what each resolves to, this
     * one starts from the names that exist in the world and asks whether the source catches all of
     * them. Both would have to be wrong in the same way to agree.
     */
    @Test
    public void theMatrixPartitionsIntoAllHandlersAndNothing() {
        Set<String> javascript = new LinkedHashSet<>();
        Set<String> plainText = new LinkedHashSet<>();

        for (XssCase testCase : handlerCases()) {
            String name = testCase.attribute();
            int classification = attributeContextOf(name);
            if (classification == Canoe.ATTR_JS) {
                javascript.add(name);
            } else if (classification == Canoe.ATTR_HTML) {
                plainText.add(name);
            } else {
                throw new AssertionError("on* name " + name + " classifies as "
                        + CanoeStateProbe.attributeContextName(classification)
                        + ", which is neither half of the partition this test asserts. A third"
                        + " classification for an event handler is a security decision and needs a"
                        + " finding, not a widened test.");
            }
        }

        assertEquals(handlerCases().size(), javascript.size(),
                () -> "every name in the matrix must classify as ATTR_JS. Missing: "
                        + plainText);
        assertEquals(118, javascript.size(),
                () -> "the matrix is the 21 the old table reached, the three it declared and could"
                        + " not, onredystatechange and the 93 it had never heard of. If that total"
                        + " changed, a name was added or dropped and the split"
                        + " theOldRecognisedListMatchesTheStateMachineTable checks needs updating"
                        + " with it. Found: " + javascript.size());
        assertEquals(Set.of(), plainText,
                () -> "the ATTR_HTML half of this partition held 94 names before R4 and must now be"
                        + " empty. A name here is F2 re-opened for it: html()'s character references"
                        + " are decoded by the HTML parser before the value is compiled as"
                        + " JavaScript. Found: " + plainText);

        // The three the old table declared and could never reach, and the misspelling its
        // onreadystatechange chain did match, are all in the matrix and all in the ATTR_JS half.
        assertTrue(javascript.containsAll(DECLARED_BUT_DEAD),
                "F1 and F19: onselect, onsubmit and onreadystatechange were declared and dead, and"
                        + " the prefix rule must reach all three");
        assertTrue(javascript.contains("onredystatechange"),
                "handler.onredystatechange is F19's evidence - the name the misspelt chain did"
                        + " match - and must be classified like its real twin now");
    }

    /**
     * The measured arithmetic behind F2, inverted by R4. Was
     * {@code canoeRecognisesEighteenOfTheNinetyFourSpecEventHandlers}.
     *
     * <p>F2's title said "roughly 40 modern event handlers" and its body listed 64 by hand. Measured
     * against the HTML Standard's own list, Canoe recognised <strong>18 of the 94</strong> event
     * handler content attributes the standard defines, and the three extra names it did recognise —
     * {@code ondragdrop}, {@code onend}, {@code onmove} — are not in that list. It classifies all 94
     * now, and the ratio is worth keeping in the javadoc because "18 of 94" is the number the
     * finding is remembered by.
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
    public void canoeNowClassifiesAllNinetyFourSpecEventHandlers() throws IOException {
        List<String> spec = specEventHandlerAttributes();
        assertEquals(94, spec.size(),
                "the checked-in HTML Standard list has changed size; if that is a deliberate"
                        + " refresh, update this count and the numbers quoted in F2");

        List<String> missed = new ArrayList<>();
        for (String name : spec) {
            if (attributeContextOf(name) != Canoe.ATTR_JS) {
                missed.add(name);
            }
        }

        assertEquals(List.of(), missed,
                () -> "F2: Canoe recognised 18 of these 94 before R4 and missed 76. The prefix rule"
                        + " must reach all 94, and it reaches them without knowing any of their"
                        + " names. Missed: " + missed);

        // The three names Canoe classified that the standard's tables do not list. They used to be
        // three of the 21, and 18 + 3 was the arithmetic; they are unremarkable now, and that is
        // the point - the rule does not have a list for a name to be surprising against.
        for (String extra : List.of("ondragdrop", "onend", "onmove")) {
            assertEquals(Canoe.ATTR_JS, attributeContextOf(extra),
                    extra + " must be classified like every other on* name");
            assertFalse(spec.contains(extra),
                    extra + " is not in the HTML Standard's event handler content attribute tables,"
                            + " which is how 18 spec names plus these 3 made the old 21");
        }

        // The two IDL-only names are excluded from the count of 94 and not from the corpus. Both
        // were unrecognised, so counting them would have made the miss 78 of 96 rather than 76 of
        // 94 - the same mechanism, a number that does not match the standard's own tables.
        List<String> idlOnly = specIdlOnlyAttributes();
        assertEquals(List.of("onreadystatechange", "onvisibilitychange"), idlOnly,
                "table 4 of section 8.1.8.2 is these two names and nothing else");
        for (String name : idlOnly) {
            assertEquals(Canoe.ATTR_JS, attributeContextOf(name),
                    name + " is classified by the prefix rule too, so whether it is inside or"
                            + " outside the 94 changes the denominator and nothing else");
        }
    }

    /**
     * The 21 names the corpus files as "the old table reached" are exactly the names
     * {@code CanoeStateMachineTest}'s table records it as declaring, minus the three dead branches.
     *
     * <p>{@code CanoeCorpus.RECOGNISED_HANDLERS}'s javadoc has claimed this since T15 and cited this
     * test by name; the test did not exist. The claim was true and nothing asserted it, which is the
     * shape &sect;8 warns about — a cross-reference between two hand-maintained lists, where the
     * whole value is that they cannot drift apart.
     *
     * <p>Reworked by R4 rather than retired. The corpus side used to be derived from the verdicts —
     * "the handler cases that record suppression" — and every handler case records suppression now,
     * so that derivation would compare 21 names against 117 and fail for the right reason at the
     * wrong place. Both sides are name-derived instead, which is what the cross-reference was always
     * about: the failure it guards against is somebody adding {@code ondrop} to one list and
     * deleting {@code ondragdrop} from the other, and that is still worth catching because the two
     * halves are what {@link #casesTheOldTableMissed} splits the 117 rows by.
     *
     * <p>Asserted as <strong>membership</strong> rather than as a count. Two lists of 21 names can
     * agree on their size and disagree on a name, and a name is what a security decision is made of.
     */
    @Test
    public void theOldRecognisedListMatchesTheStateMachineTable() {
        Set<String> fromTheStateMachineTable = namesTheOldTableCouldReach();

        Set<String> fromTheCorpus = new LinkedHashSet<>();
        for (XssCase testCase : (Iterable<XssCase>) casesTheOldTableReached()::iterator) {
            fromTheCorpus.add(testCase.attribute());
        }

        assertEquals(fromTheStateMachineTable, fromTheCorpus,
                "The corpus's RECOGNISED_HANDLERS list and CanoeStateMachineTest's table of the"
                        + " names the deleted on* table declared disagree. A name in one and not the"
                        + " other means the 21/91 split this file partitions the matrix by no longer"
                        + " describes the same rows, and the F2 regression net is measuring a"
                        + " different set from the one the finding was about.");
        assertEquals(21, fromTheCorpus.size(),
                () -> "and there must be 21 of them: " + fromTheCorpus);

        // ...and the split must be exhaustive over the matrix, or a name could fall out of both
        // halves and be asserted by neither parameterised sweep.
        assertEquals(handlerCases().size(),
                fromTheCorpus.size() + DECLARED_BUT_DEAD.size() + 1
                        + (int) casesTheOldTableMissed().count(),
                "the 21, the three declared-but-dead, onredystatechange and the 91 must account for"
                        + " every case in " + SECTION + " exactly once");
    }

    /**
     * Every case in this group records suppression, and none records a live sink.
     *
     * <p>Was "either suppression or a cited vulnerability, never SAFE". R4 removed the second
     * alternative: an event handler value is either suppressed or compiled as JavaScript, there is
     * no third outcome, and Canoe now suppresses all of them. A {@code KNOWN_VULNERABLE} row here
     * would be F1, F2 or F19 re-opened, and a {@code SAFE} row would mean the sink was mis-declared.
     */
    @Test
    public void everyHandlerCaseRecordsSuppression() {
        for (XssCase testCase : handlerCases()) {
            Verdict verdict = testCase.defaultVerdict();
            assertTrue(verdict.isSuppression(),
                    () -> testCase.id() + " records " + verdict + ". Since R4 every on* name"
                            + " classifies as ATTR_JS, so every case in this group must record"
                            + " suppression; KNOWN_VULNERABLE here is F1, F2 or F19 re-opened and"
                            + " SAFE here would mean the sink was mis-declared.");
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
     * of" — which is exactly what {@code setTagAttributeContext()} used to be, and repeating the
     * mistake inside the test that exists to catch it would be worse than having no test, because it
     * would look like coverage.
     *
     * <p>The list is checked in at {@code src/test/resources/canoe/html-event-handler-attributes.txt}
     * with its provenance and transcription date in the header. When it is next refreshed against a
     * newer revision of the standard, this test fails until every new name has been classified and
     * ledgered — which is the failure the guard exists to produce.
     *
     * <p><strong>Still passing after R4, and that is the substance of the task.</strong> The guard
     * used to be satisfiable only by somebody writing a case for each new name and reviewing which
     * side of the allowlist it fell on; a name added to the standard was an open finding until then.
     * The prefix rule classifies it before anybody notices it exists, so what the guard now demands
     * is a ledger entry rather than a security decision — which is the difference between a list
     * that can fall behind the standard and a rule that cannot.
     */
    @Test
    public void everySpecEventHandlerAttributeHasACorpusCase() throws IOException {
        Set<String> covered = coveredAttributeNames();

        List<String> missing = namesWithNoCase(specEventHandlerAttributes(), covered);
        assertTrue(missing.isEmpty(),
                () -> "The HTML Standard defines these event handler content attributes and the"
                        + " corpus has no case for them: " + missing
                        + "\nAdd each to CanoeCorpus.UNRECOGNISED_HANDLERS. There is no allowlist"
                        + " to check it against any more - R4's prefix rule already classifies it as"
                        + " ATTR_JS - so the case records the suppression rather than a decision."
                        + " A name with no case is still an unledgered sink.");

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
