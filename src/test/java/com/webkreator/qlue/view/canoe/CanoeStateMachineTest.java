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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    // The on* table, exhaustively (F1, F19)
    // ------------------------------------------------------------------

    /**
     * Every event-handler name {@code setTagAttributeContext()} declares a leaf branch for, against
     * the context that name <em>actually</em> resolves to.
     *
     * <p>Twenty-four branches are written. Twenty-one of them work. The three that do not are
     * {@code onselect} and {@code onsubmit}, whose branch reads {@code buf[0]}/{@code buf[1]} where
     * every sibling reads {@code buf[2]}/{@code buf[3]} (F1), and {@code onreadystatechange}, whose
     * comparison chain spells {@code onredystatechange} (F19).
     *
     * <p>The expectations are literals, one row per name, and that is the whole point of the test.
     * F1 was found by reading the source; F19 was not, and could not reasonably have been — its
     * indices are consecutive, its terminator index matches the number of characters it compares,
     * and its comment says the right thing. It is only wrong if you read the thirteen comparands
     * back as a word. A table of literal expectations makes each branch state its own answer in a
     * form nobody can skim, and it is the reason the count of working branches cannot drift again.
     */
    public static Stream<Arguments> declaredOnStarBranches() {
        return Stream.of(
                onStar("onabort", Canoe.ATTR_JS, null),
                onStar("onblur", Canoe.ATTR_JS, null),
                onStar("onchange", Canoe.ATTR_JS, null),
                onStar("onclick", Canoe.ATTR_JS, null),
                onStar("ondblclick", Canoe.ATTR_JS, null),
                onStar("ondragdrop", Canoe.ATTR_JS, null),
                onStar("onend", Canoe.ATTR_JS, null),
                onStar("onerror", Canoe.ATTR_JS, null),
                onStar("onkeydown", Canoe.ATTR_JS, null),
                onStar("onkeypress", Canoe.ATTR_JS, null),
                onStar("onkeyup", Canoe.ATTR_JS, null),
                onStar("onload", Canoe.ATTR_JS, null),
                onStar("onmousedown", Canoe.ATTR_JS, null),
                onStar("onmousemove", Canoe.ATTR_JS, null),
                onStar("onmouseout", Canoe.ATTR_JS, null),
                onStar("onmouseover", Canoe.ATTR_JS, null),
                onStar("onmouseup", Canoe.ATTR_JS, null),
                onStar("onmove", Canoe.ATTR_JS, null),
                onStar("onreadystatechange", Canoe.ATTR_HTML, "F19"),
                onStar("onreset", Canoe.ATTR_JS, null),
                onStar("onresize", Canoe.ATTR_JS, null),
                onStar("onselect", Canoe.ATTR_HTML, "F1"),
                onStar("onsubmit", Canoe.ATTR_HTML, "F1"),
                onStar("onunload", Canoe.ATTR_JS, null));
    }

    private static Arguments onStar(String name, int expected, String finding) {
        return Arguments.of(name, expected, finding);
    }

    /**
     * Each name is probed on a fresh {@link Canoe}, so the only thing in the buffer is the attribute
     * name itself and its terminator. F5's residue is a separate axis, owned by
     * {@code AttributePrefixTest}; mixing it in here would make a failure ambiguous between "the
     * branch is dead" and "an earlier name armed the buffer".
     */
    @ParameterizedTest(name = "{0} -> {1}{2}")
    @MethodSource("declaredOnStarBranches")
    public void everyDeclaredOnStarBranchNameIsClassified(String name, int expected, String finding)
            throws IOException {
        assertEquals(expected, attributeContextOf("<img " + name + "=\""),
                () -> (finding == null
                        ? name + " is declared and must classify as JavaScript"
                        : finding + ": " + name + " is declared but its branch cannot be taken"));

        int expectedContext = expected == Canoe.ATTR_JS ? Canoe.CTX_JS : Canoe.CTX_HTML_ATTR;
        assertEquals(expectedContext, CanoeTestSupport.contextAfter("<img " + name + "=\""),
                () -> (finding == null
                        ? name + " must be suppressed"
                        : finding + ": " + name + " gets html(), which the parser decodes before"
                                + " the value is compiled as JavaScript"));
    }

    /**
     * The arithmetic the table above is really about, stated as a number so that it appears in the
     * review and in the failure message rather than having to be counted by hand: 24 branches are
     * declared, 21 can be taken.
     */
    @Test
    public void onlyTwentyOneOfTheTwentyFourDeclaredOnStarBranchesCanBeTaken() {
        List<String> declared = new ArrayList<>();
        List<String> dead = new ArrayList<>();
        for (Arguments row : (Iterable<Arguments>) declaredOnStarBranches()::iterator) {
            declared.add((String) row.get()[0]);
            if ((Integer) row.get()[1] != Canoe.ATTR_JS) {
                dead.add((String) row.get()[0]);
            }
        }

        assertEquals(24, declared.size(), "the number of declared on* branches");
        assertEquals(List.of("onreadystatechange", "onselect", "onsubmit"), dead,
                "the on* branches that are written but cannot be taken: onselect and onsubmit are"
                        + " F1, onreadystatechange is F19. If this list shrank, a finding has been"
                        + " fixed and the ledger needs updating.");
        assertEquals(21, declared.size() - dead.size(),
                "the number of on* names Canoe genuinely recognises");
    }

    /**
     * F19, at the character that causes it.
     *
     * <p>The branch at {@code Canoe.java:483-491} sits inside {@code buf[2]=='r' && buf[3]=='e'} and
     * then demands {@code buf[4]=='d'}. Concatenate the indices and the name it matches is
     * {@code on} + {@code re} + {@code dystatechange} — seventeen characters, with no {@code a}
     * after the {@code re}. So the branch recognises {@code onredystatechange}, an attribute that
     * does not exist, and cannot recognise {@code onreadystatechange}, which does.
     *
     * <p>This is the inverse of the usual bug shape and worth stating plainly: the branch is not
     * unreachable, it is reachable by the wrong input. A coverage tool that could reach it would
     * report the line as covered.
     */
    @Test
    public void onreadystatechangeIsSpeltWithoutItsA() throws IOException {
        assertEquals(Canoe.ATTR_HTML, attributeContextOf("<img onreadystatechange=\""),
                "F19: the real attribute name falls through to the ATTR_HTML default");
        assertEquals(Canoe.ATTR_JS, attributeContextOf("<img onredystatechange=\""),
                "F19: and the misspelling the branch actually matches is suppressed");

        // The comparison that fails. buf[4] is the fifth character of the attribute name; the
        // branch demands 'd' there, and onreadystatechange has 'a'.
        assertEquals('a', new CanoeStateProbe().feed("<img onreadystatechange=\"").bufferAt(4),
                "F19: buf[4] holds the 'a' of 'ready', and the branch tests it against 'd'");
        assertEquals('d', new CanoeStateProbe().feed("<img onredystatechange=\"").bufferAt(4),
                "the misspelling puts a 'd' at buf[4], which is why that one matches");

        // Nothing downstream picks it up either: onRes fails on the same index, onUnLoad fails on
        // buf[2], and the onS block tests buf[0], which is 'o'.
        assertEquals(Canoe.CTX_HTML_ATTR, CanoeTestSupport.contextAfter("<img onreadystatechange=\""),
                "F19: html(), so an entity-encoded payload reaches the JavaScript parser intact");
    }

    /**
     * The table in {@link #declaredOnStarBranches} must list exactly the branches the source
     * declares, read out of {@code Canoe.java} rather than trusted.
     *
     * <p>A leaf branch is a {@code // onXxx} comment whose block assigns {@code ATTR_JS} before the
     * next {@code // on} comment; the grouping comments ({@code // onC}, {@code // onMouse},
     * {@code // onRe}, …) assign nothing and are skipped by the same rule. A branch added or removed
     * in the tokenizer therefore fails here, which is the only way the "24 declared, 21 working"
     * arithmetic stays true without someone re-counting it.
     */
    @Test
    public void theSourceDeclaresExactlyTheOnStarBranchesTheTableLists() throws IOException {
        Path source = Path.of("src/main/java/com/webkreator/qlue/view/Canoe.java");
        assertTrue(Files.isReadable(source),
                "cannot read " + source.toAbsolutePath() + "; this test must run with the project"
                        + " directory as its working directory");
        String text = Files.readString(source, StandardCharsets.UTF_8);

        // Every "// onXxx" comment, and the source between it and the next one.
        Matcher matcher = Pattern.compile("//\\s*(on[A-Za-z]*)\\b").matcher(text);
        List<String> declared = new ArrayList<>();
        String pendingName = null;
        int pendingStart = -1;
        while (matcher.find()) {
            if (pendingName != null && assignsJavascriptContext(text, pendingStart, matcher.start())) {
                declared.add(pendingName.toLowerCase());
            }
            pendingName = matcher.group(1);
            pendingStart = matcher.end();
        }
        if (pendingName != null && assignsJavascriptContext(text, pendingStart, text.length())) {
            declared.add(pendingName.toLowerCase());
        }

        List<String> tabled = new ArrayList<>();
        for (Arguments row : (Iterable<Arguments>) declaredOnStarBranches()::iterator) {
            tabled.add((String) row.get()[0]);
        }

        assertEquals(tabled, declared,
                "the on* branches Canoe.java declares no longer match the table in"
                        + " declaredOnStarBranches(). Add or remove a row, and update the counts in"
                        + " onlyTwentyOneOfTheTwentyFourDeclaredOnStarBranchesCanBeTaken.");
    }

    private static boolean assignsJavascriptContext(String text, int from, int to) {
        return text.substring(from, to).contains("attributeContext = ATTR_JS;");
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
     * F4. {@code detectAttributePrefix()} opens with an unconditional
     * {@code attributeContext = ATTR_HTML}, so the first colon at value index 0-10 discards the
     * context the attribute <em>name</em> established. A URL that begins with a scheme therefore
     * stops being percent-encoded and starts being entity-encoded, which the parser undoes.
     */
    @Test
    public void aColonInTheValueDowngradesTheNameDerivedContext() {
        assertEquals(Canoe.CTX_URI, CanoeTestSupport.contextAfter("<a href=\"/path/"),
                "no colon, so the name-derived ATTR_URI survives");
        assertEquals(Canoe.CTX_HTML_ATTR, CanoeTestSupport.contextAfter("<a href=\"http://x/"),
                "F4: the colon in http: reset the context to ATTR_HTML");
        assertEquals(Canoe.CTX_SUPPRESS, CanoeTestSupport.contextAfter("<div style=\""),
                "no colon, so CSS is suppressed as designed");
        assertEquals(Canoe.CTX_HTML_ATTR, CanoeTestSupport.contextAfter("<div style=\"color:"),
                "F4: the colon in color: defeated the CSS suppression entirely");
    }

    /**
     * F5. {@code detectAttributePrefix()} confirms the prefix was exactly {@code javascript} by
     * testing {@code buf[10] == '\0'}, but the value scan never writes a terminator and {@code buf}
     * is a field of the whole render that is never cleared. Whether {@code javascript:} is
     * recognised therefore depends on what an earlier, unrelated attribute name left at index 10.
     *
     * <p>This is the whole finding in three lines: the same template, safe or not depending on what
     * precedes it. {@code BufferResidueTest} (T22) characterises the full length dependence.
     */
    @Test
    public void whetherAJavascriptUrlIsRecognisedDependsOnEarlierMarkup() {
        assertEquals(Canoe.CTX_JS, CanoeTestSupport.contextAfter("<a href=\"javascript:"),
                "with a clean buffer buf[10] is 0, the prefix matches, and output is suppressed");
        assertEquals(Canoe.CTX_JS,
                CanoeTestSupport.contextAfter("<a xlinkhref=\"1\" href=\"javascript:"),
                "a 9-character name leaves buf[10] untouched, so the page is still safe");
        assertEquals(Canoe.CTX_HTML_ATTR,
                CanoeTestSupport.contextAfter("<a onmouseoverx=\"1\" href=\"javascript:"),
                "F5: an 11-character name leaves 'r' at buf[10] and the check fails");
        assertEquals(Canoe.CTX_HTML_ATTR,
                CanoeTestSupport.contextAfter("<a data-something-long=\"1\" href=\"javascript:"),
                "F5: any name of 11 or more characters arms it");
    }

    private static int attributeContextOf(String prefix) throws IOException {
        return new CanoeStateProbe().feed(prefix).attributeContext();
    }
}
