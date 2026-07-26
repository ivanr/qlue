package com.webkreator.qlue.view.canoe;

import com.webkreator.qlue.view.Canoe;
import com.webkreator.qlue.view.CanoeStateProbe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The state machine, tested directly. No Velocity, no rendering — just literal template text fed
 * through {@link Canoe}, with both the resulting state and the context it implies asserted.
 *
 * <p>This is the cheapest and most precise layer of the suite. Every context decision Canoe makes is
 * visible here, and each of the critical findings is ultimately a wrong answer to one of two
 * questions: what state am I in, and what context does that state imply?
 *
 * <p><strong>Both are asserted together, in one table.</strong> That is not tidiness. Five distinct
 * states plus every state {@code currentContext()} has no case for all collapse to
 * {@code CTX_SUPPRESS}, so a context-only assertion cannot tell "correctly suppressed inside a style
 * element" from "fell through a hole in the switch" — and the difference between those two is
 * exactly what F11 is. Worse, a context-only table records F11's holes as if they were intended:
 * fix F11 by giving {@code TAG_ATTR_VALUE_BEFORE} a real context and a context-only row for
 * {@code <p class=} fails, reporting a correct fix as a regression. With the state recorded
 * alongside, the row says what it means.
 */
public class CanoeStateMachineTest {

    // ------------------------------------------------------------------
    // The table
    // ------------------------------------------------------------------

    static Stream<Arguments> transitions() {
        return Stream.of(
                // --- body and text-ish contexts ---
                row("before any markup", "", Canoe.HTML, Canoe.CTX_HTML),
                row("body text", "<p>", Canoe.HTML, Canoe.CTX_HTML),
                row("between elements", "<p></p>", Canoe.HTML, Canoe.CTX_HTML),
                row("after a self-closing tag", "<br />", Canoe.HTML, Canoe.CTX_HTML),

                // RCDATA and RAWTEXT elements Canoe does not model. Safe in both cases, but for
                // different reasons: in RCDATA a decoded &lt; is character data and never becomes a
                // tag opener; in RAWTEXT entities are not decoded at all.
                row("inside textarea (RCDATA)", "<textarea>", Canoe.HTML, Canoe.CTX_HTML),
                row("inside title (RCDATA)", "<title>", Canoe.HTML, Canoe.CTX_HTML),
                row("inside xmp (RAWTEXT)", "<xmp>", Canoe.HTML, Canoe.CTX_HTML),
                row("inside noembed (RAWTEXT)", "<noembed>", Canoe.HTML, Canoe.CTX_HTML),
                row("inside noscript (RAWTEXT)", "<noscript>", Canoe.HTML, Canoe.CTX_HTML),
                row("inside iframe", "<iframe>", Canoe.HTML, Canoe.CTX_HTML),

                // --- tag and attribute name positions ---
                row("immediately after '<'", "<", Canoe.TAG_NAME, Canoe.CTX_SUPPRESS),
                row("part-way through a tag name", "<p", Canoe.TAG_NAME, Canoe.CTX_SUPPRESS),
                row("after a tag name", "<p ", Canoe.TAG, Canoe.CTX_SUPPRESS),
                row("part-way through an attribute name", "<p cla",
                        Canoe.TAG_ATTR_NAME, Canoe.CTX_SUPPRESS),
                row("after an attribute name", "<p class ",
                        Canoe.TAG_ATTR_NAME_AFTER, Canoe.CTX_SUPPRESS),

                // F11: currentContext() has no case for TAG_ATTR_VALUE_BEFORE. The state column is
                // what stops these two rows from reading as deliberate suppression.
                row("after the equals sign (F11)", "<p class=",
                        Canoe.TAG_ATTR_VALUE_BEFORE, Canoe.CTX_SUPPRESS),
                row("after equals and a space (F11)", "<p class= ",
                        Canoe.TAG_ATTR_VALUE_BEFORE, Canoe.CTX_SUPPRESS),

                // --- script and style element bodies ---
                row("script body", "<script>", Canoe.SCRIPT, Canoe.CTX_JS),
                row("script body, uppercase tag", "<SCRIPT>", Canoe.SCRIPT, Canoe.CTX_JS),
                row("script body with attributes", "<script type=\"text/javascript\">",
                        Canoe.SCRIPT, Canoe.CTX_JS),
                row("part-way through a script close", "<script></scr",
                        Canoe.SCRIPT_END, Canoe.CTX_JS),
                row("closed script", "<script>x</script>", Canoe.HTML, Canoe.CTX_HTML),
                row("style body", "<style>", Canoe.CSS, Canoe.CTX_SUPPRESS),
                row("style body, uppercase tag", "<STYLE>", Canoe.CSS, Canoe.CTX_SUPPRESS),
                row("part-way through a style close", "<style></sty",
                        Canoe.CSS_END, Canoe.CTX_SUPPRESS),
                row("closed style", "<style>a{}</style>", Canoe.HTML, Canoe.CTX_HTML),

                // --- attribute values, by attribute name ---
                row("unrecognised attribute", "<p class=\"",
                        Canoe.TAG_ATTR_VALUE, Canoe.CTX_HTML_ATTR),
                row("title attribute", "<a title=\"", Canoe.TAG_ATTR_VALUE, Canoe.CTX_HTML_ATTR),
                row("href", "<a href=\"", Canoe.TAG_ATTR_VALUE, Canoe.CTX_URI),
                row("src", "<img src=\"", Canoe.TAG_ATTR_VALUE, Canoe.CTX_URI),
                row("background", "<body background=\"", Canoe.TAG_ATTR_VALUE, Canoe.CTX_URI),
                row("dynsrc", "<img dynsrc=\"", Canoe.TAG_ATTR_VALUE, Canoe.CTX_URI),
                row("lowsrc", "<img lowsrc=\"", Canoe.TAG_ATTR_VALUE, Canoe.CTX_URI),
                row("style", "<div style=\"", Canoe.TAG_ATTR_VALUE, Canoe.CTX_SUPPRESS),
                row("onclick", "<a onclick=\"", Canoe.TAG_ATTR_VALUE, Canoe.CTX_JS),
                row("data", "<object data=\"", Canoe.TAG_ATTR_VALUE, Canoe.CTX_SUPPRESS),
                row("uppercase attribute name", "<a HREF=\"", Canoe.TAG_ATTR_VALUE, Canoe.CTX_URI),

                // --- value termination, by quoting style ---
                row("double-quoted value", "<a href=\"x", Canoe.TAG_ATTR_VALUE, Canoe.CTX_URI),
                row("closed double-quoted value", "<a href=\"x\"", Canoe.TAG, Canoe.CTX_SUPPRESS),
                row("single-quoted value", "<a href='x", Canoe.TAG_ATTR_VALUE, Canoe.CTX_URI),
                row("closed single-quoted value", "<a href='x'", Canoe.TAG, Canoe.CTX_SUPPRESS),
                row("double quote inside a single-quoted value", "<a href='x\">y",
                        Canoe.TAG_ATTR_VALUE, Canoe.CTX_URI),
                row("unquoted value, one character in", "<a href=/",
                        Canoe.TAG_ATTR_VALUE, Canoe.CTX_URI),
                row("unquoted value ended by whitespace", "<a href=/p/ t",
                        Canoe.TAG_ATTR_NAME, Canoe.CTX_SUPPRESS),
                row("unquoted value ended by '>'", "<a href=/p/>x", Canoe.HTML, Canoe.CTX_HTML),
                row("value after leading whitespace", "<a href=  \"",
                        Canoe.TAG_ATTR_VALUE, Canoe.CTX_URI),

                // --- attribute-name-after transitions ---
                row("valueless attribute then another", "<p disabled class=\"",
                        Canoe.TAG_ATTR_VALUE, Canoe.CTX_HTML_ATTR),
                row("valueless attribute then a slash", "<p class />", Canoe.HTML, Canoe.CTX_HTML),
                row("slash after a closed value", "<img src=\"a\" /",
                        Canoe.TAG_EMPTY_ENDING, Canoe.CTX_SUPPRESS),

                // --- comments and doctype ---
                row("after a bang", "<!", Canoe.COMMENT_OPEN_OR_DOCTYPE, Canoe.CTX_SUPPRESS),
                row("after one comment dash", "<!-", Canoe.COMMENT_OPEN_2, Canoe.CTX_SUPPRESS),
                row("inside a comment", "<!--", Canoe.COMMENT, Canoe.CTX_SUPPRESS),
                row("comment containing markup", "<!-- <p> ", Canoe.COMMENT, Canoe.CTX_SUPPRESS),
                row("after one closing dash", "<!-- a -",
                        Canoe.COMMENT_CLOSE_1, Canoe.CTX_SUPPRESS),
                row("after two closing dashes", "<!-- a --",
                        Canoe.COMMENT_CLOSE_2, Canoe.CTX_SUPPRESS),
                row("dash that does not close", "<!--a-b", Canoe.COMMENT, Canoe.CTX_SUPPRESS),
                row("after a closed comment", "<!-- c -->", Canoe.HTML, Canoe.CTX_HTML),
                row("inside a conditional comment", "<!--[if IE]>",
                        Canoe.COMMENT, Canoe.CTX_SUPPRESS),
                row("part-way through DOCTYPE", "<!DOC", Canoe.DOCTYPE_TEST, Canoe.CTX_SUPPRESS),
                row("inside a doctype", "<!DOCTYPE ", Canoe.DOCTYPE, Canoe.CTX_SUPPRESS),
                row("after a doctype", "<!DOCTYPE html>", Canoe.HTML, Canoe.CTX_HTML));
    }

    private static Arguments row(String description, String prefix, int state, int context) {
        return Arguments.of(description, prefix, state, context);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("transitions")
    public void parsesTo(String description, String prefix, int expectedState, int expectedContext)
            throws IOException {
        CanoeStateProbe probe = new CanoeStateProbe().feed(prefix);

        assertEquals(expectedState, probe.state(),
                () -> description + ": after " + CanoeTestSupport.quote(prefix)
                        + " expected state " + CanoeStateProbe.stateName(expectedState)
                        + " but was " + CanoeStateProbe.stateName(probe.state()));

        assertEquals(expectedContext, probe.currentContext(),
                () -> description + ": after " + CanoeTestSupport.quote(prefix)
                        + " expected " + CanoeTestSupport.contextName(expectedContext)
                        + " but was " + CanoeTestSupport.contextName(probe.currentContext()));
    }

    // ------------------------------------------------------------------
    // Coverage of the state set
    // ------------------------------------------------------------------

    /**
     * Every state Canoe declares must appear in the table above, or be accounted for by name.
     *
     * <p>The state list is read from {@link Canoe} by reflection rather than hand-maintained, so
     * adding a state to the tokenizer fails this test instead of silently going untested. An
     * unreached state is by definition an untested context decision.
     */
    @Test
    public void theTableCoversEveryDeclaredState() {
        Set<Integer> declared = declaredStates();
        Set<Integer> covered = new LinkedHashSet<>();
        for (Arguments arguments : (Iterable<Arguments>) transitions()::iterator) {
            covered.add((Integer) arguments.get()[2]);
        }

        List<String> uncovered = new ArrayList<>();
        for (int state : declared) {
            if (!covered.contains(state)) {
                uncovered.add(CanoeStateProbe.stateName(state));
            }
        }

        // INVALID is only reachable by raising an error, which a table of clean prefixes cannot
        // express; it is covered by statesWithNoCaseFallThroughToSuppress below. URL is declared and
        // has a currentContext() case, but nothing ever assigns it.
        assertEquals(List.of("URL", "INVALID"), uncovered,
                "The set of states the table does not cover changed. If a new state was added to"
                        + " Canoe, add a row for it; if URL is now reachable, theUrlStateIsDeadCode"
                        + " should be failing too.");
    }

    /**
     * {@code URL} is declared and given a {@code currentContext()} case, but no code path assigns
     * it: {@code CTX_URI} is only ever produced through {@code TAG_ATTR_VALUE} with
     * {@code ATTR_URI}. Asserted by exhaustion over the table rather than over a handful of guesses,
     * so that anything which starts reaching it fails here.
     */
    @Test
    public void theUrlStateIsDeadCode() throws IOException {
        for (Arguments arguments : (Iterable<Arguments>) transitions()::iterator) {
            String prefix = (String) arguments.get()[1];
            assertTrue(new CanoeStateProbe().feed(prefix).state() != Canoe.URL,
                    "something now reaches the URL state: " + CanoeTestSupport.quote(prefix));
        }
    }

    /** Reads the state constants out of {@link Canoe}, excluding the other constant families. */
    private static Set<Integer> declaredStates() {
        Set<Integer> states = new LinkedHashSet<>();
        for (Field field : Canoe.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != int.class) {
                continue;
            }
            String name = field.getName();
            if (name.startsWith("CTX_") || name.startsWith("ATTR_") || name.startsWith("QUOTE_")
                    || name.startsWith("MAX_")) {
                continue;
            }
            try {
                int value = field.getInt(null);
                assertTrue(!CanoeStateProbe.stateName(value).startsWith("UNKNOWN"),
                        "CanoeStateProbe.stateName does not know state " + name);
                states.add(value);
            } catch (IllegalAccessException e) {
                throw new AssertionError("Cannot read " + name, e);
            }
        }
        return states;
    }

    // ------------------------------------------------------------------
    // Holes in currentContext()
    // ------------------------------------------------------------------

    /**
     * F11. A reference placed immediately after {@code =} is encoded for {@code CTX_SUPPRESS} and
     * renders as nothing, because the quote that would advance the parser into
     * {@code TAG_ATTR_VALUE} never arrives.
     *
     * <p>Narrower than it looks: a single character of literal value text is enough to advance the
     * parser, so only a reference immediately after the equals sign is dropped.
     */
    @Test
    public void unquotedValuesAreSuppressedOnlyImmediatelyAfterTheEquals() {
        assertEquals(Canoe.CTX_SUPPRESS, CanoeTestSupport.contextAfter("<a href="));
        assertEquals(Canoe.CTX_SUPPRESS, CanoeTestSupport.contextAfter("<a href= "));
        assertEquals(Canoe.CTX_URI, CanoeTestSupport.contextAfter("<a href=/"),
                "one literal character is enough to reach TAG_ATTR_VALUE");
    }

    /**
     * Every state the table reaches that {@code currentContext()} has no case for must fall through
     * to {@code CTX_SUPPRESS}. Fail-closed, which is right — but if a future change gives any of
     * them a real context, that is a security decision and should not happen silently.
     */
    @Test
    public void statesWithNoCaseFallThroughToSuppress() throws IOException {
        Set<Integer> withACase = Set.of(Canoe.HTML, Canoe.SCRIPT, Canoe.SCRIPT_END, Canoe.URL,
                Canoe.CSS, Canoe.CSS_END, Canoe.TAG, Canoe.TAG_NAME, Canoe.TAG_ATTR_NAME_AFTER,
                Canoe.TAG_ATTR_VALUE);

        for (Arguments arguments : (Iterable<Arguments>) transitions()::iterator) {
            String prefix = (String) arguments.get()[1];
            CanoeStateProbe probe = new CanoeStateProbe().feed(prefix);
            if (!withACase.contains(probe.state())) {
                assertEquals(Canoe.CTX_SUPPRESS, probe.currentContext(),
                        CanoeStateProbe.stateName(probe.state())
                                + " has no case in currentContext() and must suppress: "
                                + CanoeTestSupport.quote(prefix));
            }
        }

        // And INVALID, once the parser has given up entirely.
        CanoeStateProbe probe = new CanoeStateProbe();
        try {
            probe.feed("<br/>");
        } catch (IOException expected) {
            // Canoe rejects a '/' immediately after a tag name; see CanoeRobustnessTest.
        }
        assertEquals(Canoe.INVALID, probe.state());
        assertEquals(Canoe.CTX_SUPPRESS, probe.currentContext());
    }

    // ------------------------------------------------------------------
    // Termination desyncs (F10 and its comment sibling)
    // ------------------------------------------------------------------

    /**
     * F10. {@code SCRIPT_END} matches the seven characters {@code /script} and returns to HTML with
     * no check that what follows is whitespace, {@code /} or {@code >}. Per the HTML Standard's
     * script-data-end-tag-name state, {@code </scriptfoo>} does not close a script element, so the
     * browser stays in script data while Canoe believes it is back in HTML.
     */
    @Test
    public void scriptEndAcceptsATagNameItShouldNot() {
        assertEquals(Canoe.CTX_HTML, CanoeTestSupport.contextAfter("<script>x</scriptfoo>"),
                "F10: Canoe thinks the script element closed, the browser does not");
        assertEquals(Canoe.CTX_HTML, CanoeTestSupport.contextAfter("<style>x</stylefoo>"),
                "F10: the same defect in CSS_END");
    }

    /**
     * The converse desync, also F10: {@code SCRIPT_END} returns to {@code SCRIPT} on a mismatch
     * <em>without</em> re-processing the character, so a stray {@code <} swallows the one that would
     * have started the real closing tag. Everything after it is suppressed.
     */
    @Test
    public void scriptAndStyleEndSwallowTheCharacterThatMismatched() {
        assertEquals(Canoe.CTX_JS, CanoeTestSupport.contextAfter("<script>x = 1 <</script>"),
                "F10: still inside SCRIPT, so the rest of the page is suppressed");
        assertEquals(Canoe.CTX_HTML, CanoeTestSupport.contextAfter("<script>x = 1 </script>"),
                "the same template without the stray '<' closes correctly");

        assertEquals(Canoe.CTX_SUPPRESS, CanoeTestSupport.contextAfter("<style>a{}<</style>"),
                "F10: the CSS twin, which the original finding does not mention");
        assertEquals(Canoe.CTX_HTML, CanoeTestSupport.contextAfter("<style>a{}</style>"));
    }

    /**
     * F14. A comment ending in three or more dashes closes in every browser — the HTML Standard's
     * comment-end state stays in comment-end on a {@code -} and closes on the following {@code >} —
     * but {@code COMMENT_CLOSE_2} drops back to {@code COMMENT} instead, so Canoe never sees the
     * comment close and suppresses the entire rest of the page.
     *
     * <p>Fail-closed, so an availability defect rather than a vulnerability, and the same shape as
     * F10's converse: a state machine that is not a faithful model of the HTML tokenizer.
     */
    @Test
    public void aCommentEndingInThreeDashesNeverCloses() {
        assertEquals(Canoe.CTX_HTML, CanoeTestSupport.contextAfter("<!--a-b-->"),
                "two dashes close correctly");
        assertEquals(Canoe.CTX_SUPPRESS, CanoeTestSupport.contextAfter("<!--a--->"),
                "F14: three dashes leave Canoe stuck inside the comment");
        assertEquals(Canoe.CTX_SUPPRESS, CanoeTestSupport.contextAfter("<!--a---->"),
                "F14: and four");
    }

    /** Script and style termination is matched case-insensitively, which is correct. */
    @Test
    public void scriptAndStyleTerminationIsCaseInsensitive() {
        assertEquals(Canoe.CTX_HTML, CanoeTestSupport.contextAfter("<script>x</SCRIPT>"));
        assertEquals(Canoe.CTX_HTML, CanoeTestSupport.contextAfter("<style>x</StYlE>"));
    }

    // ------------------------------------------------------------------
    // Attribute name classification
    // ------------------------------------------------------------------

    /**
     * The attribute name drives everything downstream, so the derived {@code ATTR_*} value is worth
     * asserting directly rather than only through the context it produces.
     */
    @Test
    public void derivesAttributeContextFromTheName() throws IOException {
        assertEquals(Canoe.ATTR_URI, attributeContextOf("<a href=\"x"));
        assertEquals(Canoe.ATTR_CSS, attributeContextOf("<div style=\"x"));
        assertEquals(Canoe.ATTR_JS, attributeContextOf("<a onclick=\"x"));
        assertEquals(Canoe.ATTR_HTML, attributeContextOf("<a title=\"x"));

        // F7: the branch commented "content" tests for "data", so data= resolves to ATTR_CONTENT and
        // the ATTR_URI branch below it is unreachable. There is no check for "content" at all.
        assertEquals(Canoe.ATTR_CONTENT, attributeContextOf("<object data=\"x"));
        assertEquals(Canoe.ATTR_HTML, attributeContextOf("<meta content=\"x"),
                "F7: content is not recognised, so it gets the ATTR_HTML default");
    }

    // ------------------------------------------------------------------
    // The on* prefix rule (F1, F2, F19 — closed by R4)
    // ------------------------------------------------------------------

    /**
     * The names the deleted {@code on*} table used to declare a leaf branch for, all of which must
     * now classify as JavaScript through the prefix rule that replaced it.
     *
     * <p>Was {@code declaredOnStarBranches}, and it was the F1/F19 evidence table: twenty-four
     * branches were written and twenty-one of them worked. {@code onselect} and {@code onsubmit}
     * read {@code buf[0]}/{@code buf[1]} where every sibling read {@code buf[2]}/{@code buf[3]},
     * inside a block that had already established {@code buf[0] == 'o'} (F1); and the
     * {@code onreadystatechange} chain's comparands spelled {@code onredystatechange}, missing the
     * {@code a} of "ready" (F19). Both are recorded here rather than deleted with the branches,
     * because the reasoning is what says why a table was the wrong structure: F1 was findable by
     * reading the source, F19 was not — its indices are consecutive, its terminator index matches
     * the number of characters it compares, its comment says the right thing, and it is only wrong
     * if you read thirteen comparands back as a word.
     *
     * <p>R4's prefix rule cannot have either defect: there is one comparison, of two characters, and
     * every name below reaches it identically. The table is kept as the list of names that used to
     * be special so that a change which reintroduces per-name handling fails on all twenty-four at
     * once, and so that {@code onredystatechange} — the misspelling F19's branch did match — is
     * still probed alongside the name it should have matched.
     */
    public static Stream<Arguments> namesTheOldOnStarTableDeclared() {
        return Stream.of(
                onStar("onabort", null),
                onStar("onblur", null),
                onStar("onchange", null),
                onStar("onclick", null),
                onStar("ondblclick", null),
                onStar("ondragdrop", null),
                onStar("onend", null),
                onStar("onerror", null),
                onStar("onkeydown", null),
                onStar("onkeypress", null),
                onStar("onkeyup", null),
                onStar("onload", null),
                onStar("onmousedown", null),
                onStar("onmousemove", null),
                onStar("onmouseout", null),
                onStar("onmouseover", null),
                onStar("onmouseup", null),
                onStar("onmove", null),
                onStar("onreadystatechange", "F19"),
                onStar("onreset", null),
                onStar("onresize", null),
                onStar("onselect", "F1"),
                onStar("onsubmit", "F1"),
                onStar("onunload", null));
    }

    /** {@code finding} names the finding whose dead branch used to own the name, or null. */
    private static Arguments onStar(String name, String finding) {
        return Arguments.of(name, finding);
    }

    /**
     * F1, F2 and F19, inverted by R4. Was {@code everyDeclaredOnStarBranchNameIsClassified}, whose
     * expectation column carried {@code ATTR_HTML} for the three names no branch could reach.
     *
     * <p>All twenty-four resolve to {@code ATTR_JS} now, and by the same two-character comparison,
     * so there is no longer a per-name expectation to get wrong. Each name is probed on a fresh
     * {@link Canoe}, which since R3's buffer clearing is a convention rather than a precaution.
     */
    @ParameterizedTest(name = "{0}{1}")
    @MethodSource("namesTheOldOnStarTableDeclared")
    public void everyNameTheOldOnStarTableDeclaredIsClassifiedAsJavascript(String name,
                                                                          String finding)
            throws IOException {
        assertEquals(Canoe.ATTR_JS, attributeContextOf("<img " + name + "=\""),
                () -> (finding == null
                        ? name + " must classify as JavaScript"
                        : finding + ": " + name + " used to be a branch that could not be taken and"
                                + " must classify as JavaScript through the prefix rule now"));

        assertEquals(Canoe.CTX_JS, CanoeTestSupport.contextAfter("<img " + name + "=\""),
                () -> name + " must be suppressed; html() here is what the HTML parser decodes"
                        + " before the value is compiled as JavaScript");
    }

    /**
     * The arithmetic, inverted. Was
     * {@code onlyTwentyOneOfTheTwentyFourDeclaredOnStarBranchesCanBeTaken}: 24 branches declared,
     * 21 reachable, three dead. There are no branches to count now — one prefix rule replaces all
     * twenty-four — so the number worth stating is that the dead set is empty.
     */
    @Test
    public void noNameTheOldOnStarTableDeclaredIsUnreachableAnyMore() throws IOException {
        List<String> declared = new ArrayList<>();
        List<String> dead = new ArrayList<>();
        for (Arguments row : (Iterable<Arguments>) namesTheOldOnStarTableDeclared()::iterator) {
            String name = (String) row.get()[0];
            declared.add(name);
            if (attributeContextOf("<img " + name + "=\"") != Canoe.ATTR_JS) {
                dead.add(name);
            }
        }

        assertEquals(24, declared.size(), "the number of names the old on* table declared");
        assertEquals(List.of(), dead,
                "F1 and F19 were the three of these the table declared and could never reach:"
                        + " onselect, onsubmit and onreadystatechange. R4's prefix rule reaches"
                        + " every name that begins 'on', so this list must stay empty. A name"
                        + " appearing here means something has started special-casing names again.");
    }

    /**
     * F19's misspelling, inverted by R4. Was {@code onreadystatechangeIsSpeltWithoutItsA}.
     *
     * <p>The dead branch's guard was {@code buf[2]=='r' && buf[3]=='e'} and its body then demanded
     * {@code buf[4]=='d'}, so the comparands spelled {@code on} + {@code re} + {@code dystatechange}
     * and the real attribute could never match. It was the inverse of the usual bug shape and worth
     * keeping the record of: the branch was not unreachable, it was reachable by the wrong input, so
     * a coverage tool would have reported it covered had anything ever exercised it.
     *
     * <p>Both names classify as JavaScript now, because the prefix rule reads two characters and
     * neither the fifth character nor the spelling of the rest can matter. The buffer probes are
     * kept and inverted too: {@code buf[4]} still differs between the two names, and the point is
     * that nothing reads it.
     */
    @Test
    public void theMisspeltNameAndTheRealOneAreNowTheSameStatement() throws IOException {
        assertEquals(Canoe.ATTR_JS, attributeContextOf("<img onreadystatechange=\""),
                "R4: the real attribute name is classified by the prefix rule");
        assertEquals(Canoe.ATTR_JS, attributeContextOf("<img onredystatechange=\""),
                "R4: and so is the misspelling F19's branch used to match, for the same reason");

        // The comparison that used to fail. buf[4] is the fifth character of the attribute name and
        // it still differs between the two names; what changed is that nothing looks at it. Probed
        // at the '=' rather than after the opening quote since R3: the quote starts the attribute
        // value, and the value scan clears the buffer before writing into it.
        assertEquals('a', new CanoeStateProbe().feed("<img onreadystatechange=").bufferAt(4),
                "buf[4] still holds the 'a' of 'ready'; the branch that tested it against 'd' is"
                        + " gone");
        assertEquals('d', new CanoeStateProbe().feed("<img onredystatechange=").bufferAt(4),
                "and the misspelling still puts a 'd' there, which no longer decides anything");

        assertEquals(Canoe.CTX_JS, CanoeTestSupport.contextAfter("<img onreadystatechange=\""),
                "R4: suppressed, so no entity-encoded payload reaches the JavaScript parser");
    }

    /**
     * The rule's deliberate cost, pinned so nobody "fixes" it: <em>every</em> name beginning
     * {@code on} is suppressed, including names that are not and never will be event handlers.
     *
     * <p>R4's words in the plan are "there is no benign exception worth the risk", and this test is
     * that decision made concrete. A bare {@code on}, a hyphenated {@code on-click} (the shape a
     * framework's custom attribute takes), a nonsense {@code onx}, and ordinary English words like
     * {@code only} and {@code once} all classify as {@code ATTR_JS} and emit nothing. The cost is
     * real — a template author with {@code <div only="$x">} silently loses the value — and it is
     * accepted, because the alternative is an exception list, and an exception list is the
     * structure whose 76 misses were F2. If one of these rows ever stops classifying as
     * {@code ATTR_JS}, an exception has been carved and this file is where its risk gets argued.
     *
     * <p>The contrast row keeps the rule honest in the other direction: a name that does not begin
     * {@code on} — here the single letter {@code o} — must not be caught, or the rule has become a
     * one-character prefix and every attribute starting with {@code o} is vanishing.
     */
    @Test
    public void everyNameBeginningOnIsSuppressedIncludingBenignOnes() throws IOException {
        for (String name : List.of("on", "onx", "on-click", "only", "once")) {
            assertEquals(Canoe.ATTR_JS, attributeContextOf("<div " + name + "=\""),
                    () -> name + " begins 'on' and must classify as ATTR_JS. This is the accepted"
                            + " cost of the prefix rule, not a bug: an exception for it would be"
                            + " the start of the allowlist R4 deleted.");
            assertEquals(Canoe.CTX_JS, CanoeTestSupport.contextAfter("<div " + name + "=\""),
                    () -> name + " must be suppressed, benign or not");
        }

        assertEquals(Canoe.ATTR_HTML, attributeContextOf("<div o=\""),
                "a name that does not begin 'on' must fall through to the ATTR_HTML default (R5's"
                        + " to invert); the rule reads two characters, not one");
    }

    /**
     * The prefix rule is a <em>prefix</em> rule, and the source says so.
     *
     * <p>Was {@code theSourceDeclaresExactlyTheOnStarBranchesTheTableLists}, which read the 24
     * {@code // onXxx} leaf branches back out of {@code Canoe.java} so that the table above could
     * not drift from the source. There is nothing to enumerate now, and the property that replaces
     * it is the stronger one: {@code setTagAttributeContext()} must contain no per-handler-name
     * comparison at all, so no name can be added to or dropped from an allowlist that does not
     * exist.
     *
     * <p>Asserted against the source text as well as against behaviour, because the behavioural
     * sweeps ({@code EventHandlerMatrixTest}, {@code NearMissNameSweepTest}) can only probe names
     * somebody thought of, and a re-introduced special case for a name nobody listed would pass
     * every one of them.
     */
    @Test
    public void theSourceClassifiesHandlersByPrefixAndNotByName() throws IOException {
        Path source = Path.of("src/main/java/com/webkreator/qlue/view/Canoe.java");
        assertTrue(Files.isReadable(source),
                "cannot read " + source.toAbsolutePath() + "; this test must run with the project"
                        + " directory as its working directory");
        String text = Files.readString(source, StandardCharsets.UTF_8);

        int start = text.indexOf("protected void setTagAttributeContext()");
        assertTrue(start > 0, "setTagAttributeContext() has been renamed");
        int end = text.indexOf("\n    /**", start);
        assertTrue(end > start, "cannot find the end of setTagAttributeContext()");
        String body = text.substring(start, end);

        // Exactly one comparison assigns ATTR_JS, and it is the two-character prefix test.
        assertEquals(1, countOccurrences(body, "attributeContext = ATTR_JS;"),
                "setTagAttributeContext() must reach ATTR_JS through exactly one comparison. More"
                        + " than one means a handler name is being special-cased again, which is the"
                        + " structure R4 deleted: a table of 24 comparison chains of which 3 were"
                        + " silently dead.");
        assertTrue(body.contains("bufferedNameStartsWith(\"on\")"),
                "the ATTR_JS assignment must be guarded by the on-prefix test");

        // ...and no handler name appears in the method at all. onselect and onreadystatechange are
        // the two the old table got wrong; onclick and onmouseover are two it got right.
        for (String name : List.of("onclick", "onmouseover", "onselect", "onsubmit",
                "onreadystatechange", "onredystatechange")) {
            assertTrue(!body.contains(name),
                    "setTagAttributeContext() mentions " + name + ". The prefix rule needs no"
                            + " handler name, and a name in the source is an allowlist entry"
                            + " whatever it is called.");
        }
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = text.indexOf(needle, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + needle.length();
        }
    }

    /** Attribute names are lower-cased before comparison, so case cannot be used to evade the table. */
    @Test
    public void attributeNamesAreMatchedCaseInsensitively() throws IOException {
        assertEquals(Canoe.ATTR_URI, attributeContextOf("<a HREF=\"x"));
        assertEquals(Canoe.ATTR_URI, attributeContextOf("<a HrEf=\"x"));
        assertEquals(Canoe.ATTR_JS, attributeContextOf("<a ONCLICK=\"x"));
        assertEquals(Canoe.ATTR_CSS, attributeContextOf("<div StYlE=\"x"));
    }

    /** Whitespace around the equals sign does not change the derived context. */
    @Test
    public void whitespaceAroundTheEqualsDoesNotChangeTheContext() {
        assertEquals(Canoe.CTX_URI, CanoeTestSupport.contextAfter("<a href=\""));
        assertEquals(Canoe.CTX_URI, CanoeTestSupport.contextAfter("<a href =\""));
        assertEquals(Canoe.CTX_URI, CanoeTestSupport.contextAfter("<a href\t=\t\""));
        assertEquals(Canoe.CTX_URI, CanoeTestSupport.contextAfter("<a href\n=\n\""));
    }

    /** The last attribute wins, because each name overwrites the shared buffer as it is parsed. */
    @Test
    public void theLastAttributeNameDeterminesTheContext() {
        assertEquals(Canoe.CTX_URI, CanoeTestSupport.contextAfter("<a title=\"t\" href=\""));
        assertEquals(Canoe.CTX_HTML_ATTR, CanoeTestSupport.contextAfter("<a href=\"h\" title=\""));
        assertEquals(Canoe.CTX_HTML_ATTR, CanoeTestSupport.contextAfter("<a href=\"h\" href2=\""),
                "a near-miss on a recognised name gets the ATTR_HTML default");
    }

    // ------------------------------------------------------------------
    // The two most security-relevant single facts the state machine produces
    // ------------------------------------------------------------------

    /**
     * F4, inverted by R2. Was {@code aColonInTheValueDowngradesTheNameDerivedContext}.
     *
     * <p>{@code detectAttributePrefix()} used to open with an unconditional
     * {@code attributeContext = ATTR_HTML}, so the first colon at value index 0-10 discarded the
     * context the attribute <em>name</em> established: a URL that began with a scheme stopped being
     * percent-encoded and started being entity-encoded, which the parser undoes. The line is gone
     * and the method can now only narrow.
     */
    @Test
    public void aColonInTheValueLeavesTheNameDerivedContextAlone() {
        assertEquals(Canoe.CTX_URI, CanoeTestSupport.contextAfter("<a href=\"/path/"),
                "no colon, so the name-derived ATTR_URI survives");
        assertEquals(Canoe.CTX_URI, CanoeTestSupport.contextAfter("<a href=\"http://x/"),
                "R2: the colon in http: no longer resets the context, so url() still applies");
        assertEquals(Canoe.CTX_SUPPRESS, CanoeTestSupport.contextAfter("<div style=\""),
                "no colon, so CSS is suppressed as designed");
        assertEquals(Canoe.CTX_SUPPRESS, CanoeTestSupport.contextAfter("<div style=\"color:"),
                "R2: the colon in color: no longer defeats the CSS suppression");
    }

    /**
     * F5, inverted by R3. Was {@code whetherAJavascriptUrlIsRecognisedDependsOnEarlierMarkup}.
     *
     * <p>{@code detectAttributePrefix()} used to confirm the prefix was exactly {@code javascript} by
     * testing {@code buf[10] == '\0'}, but the value scan never writes a terminator and {@code buf}
     * was a field of the whole render that was never cleared. Whether {@code javascript:} was
     * recognised therefore depended on what an earlier, unrelated attribute name had left at index
     * 10 — the whole finding in three lines: the same template, safe or not depending on what
     * precedes it.
     *
     * <p>R3 compares the buffered prefix against {@code bufLen} characters and clears the buffer on
     * every reuse, so all four rows are the same statement now. {@code BufferResidueTest} (T22)
     * characterises the full length dependence and its collapse.
     *
     * <p>The two armed rows are the ones to read a failure against. Before R2 they were
     * {@code CTX_HTML_ATTR}, from the reset; between R2 and R3 they were {@code CTX_URI}, from the
     * {@code href} in the template — a different encoder and not a fix, because the HTML Standard
     * percent-decodes a {@code javascript:} URL before compiling it and {@code url()}'s escapes come
     * straight back off.
     */
    @Test
    public void whetherAJavascriptUrlIsRecognisedDoesNotDependOnEarlierMarkup() {
        assertEquals(Canoe.CTX_JS, CanoeTestSupport.contextAfter("<a href=\"javascript:"),
                "the prefix matches and output is suppressed");
        assertEquals(Canoe.CTX_JS,
                CanoeTestSupport.contextAfter("<a xlinkhref=\"1\" href=\"javascript:"),
                "a 9-character name never reached buf[10], so this one was safe before R3 too");
        assertEquals(Canoe.CTX_JS,
                CanoeTestSupport.contextAfter("<a onmouseoverx=\"1\" href=\"javascript:"),
                "R3: an 11-character name used to leave 'r' at buf[10] and the check failed, so the"
                        + " value was url()-encoded into a javascript: URL rather than suppressed");
        assertEquals(Canoe.CTX_JS,
                CanoeTestSupport.contextAfter("<a data-something-long=\"1\" href=\"javascript:"),
                "R3: and any name of 11 or more characters used to arm it");
    }

    private static int attributeContextOf(String prefix) throws IOException {
        return new CanoeStateProbe().feed(prefix).attributeContext();
    }
}
