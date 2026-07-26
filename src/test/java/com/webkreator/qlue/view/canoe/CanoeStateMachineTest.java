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
 * exactly what F11 was. The prediction this javadoc used to make came true, which is the argument
 * for the shape of the table: "fix F11 by giving {@code TAG_ATTR_VALUE_BEFORE} a real context and a
 * context-only row for {@code <p class=} fails, reporting a correct fix as a regression". R19 gave it
 * one, the two rows moved from {@code CTX_SUPPRESS} to the context their attribute name derives, and
 * because the state is recorded alongside, the change reads as the routing fix it is rather than as a
 * hole being papered over. The remaining holes — {@code TAG_ATTR_NAME}, {@code TAG_EMPTY_ENDING},
 * the {@code COMMENT_*}/{@code DOCTYPE_*} states, {@code INVALID} — are still recorded as holes, by
 * {@link #statesWithNoCaseFallThroughToSuppress}.
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

                // R19: TAG_ATTR_VALUE_BEFORE answers with the attribute's name-derived context, so
                // an unquoted value written directly after the '=' is encoded rather than dropped.
                // The state column is what says these rows are about the value position and not
                // about `class` - the parser has not seen a quote and never will.
                row("after the equals sign (R19)", "<p class=",
                        Canoe.TAG_ATTR_VALUE_BEFORE, Canoe.CTX_HTML_ATTR),
                row("after equals and a space (R19)", "<p class= ",
                        Canoe.TAG_ATTR_VALUE_BEFORE, Canoe.CTX_HTML_ATTR),
                row("after equals on a URL name (R19)", "<a href=",
                        Canoe.TAG_ATTR_VALUE_BEFORE, Canoe.CTX_URI),
                row("after equals on a resource sink (R19)", "<script src=",
                        Canoe.TAG_ATTR_VALUE_BEFORE, Canoe.CTX_URI_RESOURCE),
                row("after equals on a handler (R19)", "<a onclick=",
                        Canoe.TAG_ATTR_VALUE_BEFORE, Canoe.CTX_JS),
                row("after equals on style (R19)", "<div style=",
                        Canoe.TAG_ATTR_VALUE_BEFORE, Canoe.CTX_SUPPRESS),
                row("after equals on an unlisted name (R19)", "<div my-widget-config=",
                        Canoe.TAG_ATTR_VALUE_BEFORE, Canoe.CTX_SUPPRESS),

                // --- script and style element bodies ---
                row("script body", "<script>", Canoe.SCRIPT, Canoe.CTX_JS),
                row("script body, uppercase tag", "<SCRIPT>", Canoe.SCRIPT, Canoe.CTX_JS),
                row("script body with attributes", "<script type=\"text/javascript\">",
                        Canoe.SCRIPT, Canoe.CTX_JS),
                row("part-way through a script close", "<script></scr",
                        Canoe.SCRIPT_END, Canoe.CTX_JS),
                // R17: the name alone does not end script data - the character after it decides -
                // so the whole name matched is its own state, and it is still CTX_JS there.
                row("script close, name matched but unconfirmed", "<script>x</script",
                        Canoe.SCRIPT_END_NAME, Canoe.CTX_JS),
                row("closed script", "<script>x</script>", Canoe.HTML, Canoe.CTX_HTML),
                // R17: a name with a suffix is not an end tag, so the machine is back in the script
                // body - where it stays until a real end tag arrives.
                row("script close with a suffix (R17)", "<script>x</scriptfoo>",
                        Canoe.SCRIPT, Canoe.CTX_JS),
                row("style body", "<style>", Canoe.CSS, Canoe.CTX_SUPPRESS),
                row("style body, uppercase tag", "<STYLE>", Canoe.CSS, Canoe.CTX_SUPPRESS),
                row("part-way through a style close", "<style></sty",
                        Canoe.CSS_END, Canoe.CTX_SUPPRESS),
                row("style close, name matched but unconfirmed", "<style>a{}</style",
                        Canoe.CSS_END_NAME, Canoe.CTX_SUPPRESS),
                row("closed style", "<style>a{}</style>", Canoe.HTML, Canoe.CTX_HTML),
                row("style close with a suffix (R17)", "<style>a{}</stylefoo>",
                        Canoe.CSS, Canoe.CTX_SUPPRESS),

                // --- attribute values, by attribute name ---
                // Was "unrecognised attribute" until R5, when an unrecognised name stopped being
                // html-encoded: `class` reaches CTX_HTML_ATTR because it is on the plain-text
                // allowlist now, not because nothing recognised it.
                row("plain-text attribute", "<p class=\"",
                        Canoe.TAG_ATTR_VALUE, Canoe.CTX_HTML_ATTR),
                row("title attribute", "<a title=\"", Canoe.TAG_ATTR_VALUE, Canoe.CTX_HTML_ATTR),
                row("href", "<a href=\"", Canoe.TAG_ATTR_VALUE, Canoe.CTX_URI),
                row("src", "<img src=\"", Canoe.TAG_ATTR_VALUE, Canoe.CTX_URI),
                row("background", "<body background=\"", Canoe.TAG_ATTR_VALUE, Canoe.CTX_URI),
                row("dynsrc", "<img dynsrc=\"", Canoe.TAG_ATTR_VALUE, Canoe.CTX_URI),
                row("lowsrc", "<img lowsrc=\"", Canoe.TAG_ATTR_VALUE, Canoe.CTX_URI),
                row("style", "<div style=\"", Canoe.TAG_ATTR_VALUE, Canoe.CTX_SUPPRESS),
                row("onclick", "<a onclick=\"", Canoe.TAG_ATTR_VALUE, Canoe.CTX_JS),
                // R7: <object data> is a URL. It was CTX_SUPPRESS here while the two identical
                // `data` branches stood, one of them commented "content" (F7), and the suppression
                // was a functional bug rather than a defence - the value simply vanished. R9 narrows
                // it further: <object data> loads a subresource, so it is the resource-loading URL
                // context that rejects an off-origin authority.
                row("data", "<object data=\"", Canoe.TAG_ATTR_VALUE, Canoe.CTX_URI_RESOURCE),
                // R5: a name on none of the lists suppresses. This row is the fail-closed default in
                // the same table as the names that have a classification, which is where the two are
                // easiest to compare.
                row("unlisted attribute (R5)", "<div my-widget-config=\"",
                        Canoe.TAG_ATTR_VALUE, Canoe.CTX_SUPPRESS),
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

                // R20: a '/' straight after the tag name ends the name and is re-processed in the
                // TAG state, which is where '/' has always meant TAG_EMPTY_ENDING. The three rows
                // are the whole of the path: the name ends, the tag ends, and the element's own
                // next state is honoured on the way out - <script/> lands in SCRIPT exactly as
                // <script /> does, because TAG_EMPTY_ENDING leaves for nextState and not for HTML.
                row("slash immediately after a tag name", "<br/",
                        Canoe.TAG_EMPTY_ENDING, Canoe.CTX_SUPPRESS),
                row("after a self-closed void element", "<br/>", Canoe.HTML, Canoe.CTX_HTML),
                row("after a self-closed script element", "<script/>", Canoe.SCRIPT, Canoe.CTX_JS),

                // --- comments and doctype ---
                row("after a bang", "<!", Canoe.COMMENT_OPEN_OR_DOCTYPE, Canoe.CTX_SUPPRESS),
                row("after one comment dash", "<!-", Canoe.COMMENT_OPEN_2, Canoe.CTX_SUPPRESS),
                row("inside a comment", "<!--", Canoe.COMMENT, Canoe.CTX_SUPPRESS),
                row("comment containing markup", "<!-- <p> ", Canoe.COMMENT, Canoe.CTX_SUPPRESS),
                row("after one closing dash", "<!-- a -",
                        Canoe.COMMENT_CLOSE_1, Canoe.CTX_SUPPRESS),
                row("after two closing dashes", "<!-- a --",
                        Canoe.COMMENT_CLOSE_2, Canoe.CTX_SUPPRESS),
                row("a run of closing dashes stays in comment-end", "<!-- a ---",
                        Canoe.COMMENT_CLOSE_2, Canoe.CTX_SUPPRESS),
                row("a non-dash after two closing dashes drops back to comment", "<!-- a -- b",
                        Canoe.COMMENT, Canoe.CTX_SUPPRESS),
                row("dash that does not close", "<!--a-b", Canoe.COMMENT, Canoe.CTX_SUPPRESS),
                row("after a closed comment", "<!-- c -->", Canoe.HTML, Canoe.CTX_HTML),
                row("inside a conditional comment", "<!--[if IE]>",
                        Canoe.COMMENT, Canoe.CTX_SUPPRESS),
                row("part-way through DOCTYPE", "<!DOC", Canoe.DOCTYPE_TEST, Canoe.CTX_SUPPRESS),
                row("inside a doctype", "<!DOCTYPE ", Canoe.DOCTYPE, Canoe.CTX_SUPPRESS),
                row("after a doctype", "<!DOCTYPE html>", Canoe.HTML, Canoe.CTX_HTML),

                // R18: a comment does not consume the document's one DOCTYPE slot, so the
                // declaration after it parses like any other. The rejections that must survive
                // are in CanoeRobustnessTest, which is where the messages and positions live.
                row("doctype after a comment", "<!-- c --><!DOC",
                        Canoe.DOCTYPE_TEST, Canoe.CTX_SUPPRESS),
                row("after a doctype that followed a comment", "<!-- c --><!DOCTYPE html>",
                        Canoe.HTML, Canoe.CTX_HTML),
                row("doctype after leading text", "hello<!DOCTYPE html>",
                        Canoe.HTML, Canoe.CTX_HTML),

                // R20: the two DOCTYPE arms that used to raise. A second declaration is admitted
                // into DOCTYPE_TEST exactly as the first is - the parser spells the word out and
                // leaves at the '>' - so the state path after it is indistinguishable from a
                // document with one, which is the point: the difference is a log line, not a
                // parse. The rejection that survives is elementSeen's, in CanoeRobustnessTest.
                row("second doctype, part-way through", "<!DOCTYPE html><!DOC",
                        Canoe.DOCTYPE_TEST, Canoe.CTX_SUPPRESS),
                row("after a second doctype", "<!DOCTYPE html><!DOCTYPE html>",
                        Canoe.HTML, Canoe.CTX_HTML),
                row("after a second doctype separated by a comment",
                        "<!DOCTYPE html><!-- c --><!DOCTYPE html>", Canoe.HTML, Canoe.CTX_HTML));
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
     * F11, closed by R19 and now inverted. Was
     * {@code unquotedValuesAreSuppressedOnlyImmediatelyAfterTheEquals}: it pinned the defect that
     * {@code currentContext()} had no case for {@code TAG_ATTR_VALUE_BEFORE}, so a reference placed
     * immediately after {@code =} was encoded for {@code CTX_SUPPRESS} and rendered as nothing —
     * the quote that would have advanced the parser into {@code TAG_ATTR_VALUE} never arrives.
     *
     * <p>The defect was narrower than it looks, and that is what made it dangerous rather than
     * merely annoying: a single character of literal value text was enough to advance the parser, so
     * {@code <a href=/p/$y>} worked and only {@code <a href=$y>} silently lost its value. A developer
     * meeting that reaches for {@code allowDirectOutput()} and {@code $_x.asis()}, which turns Canoe
     * off for the value entirely.
     *
     * <p>R19 routes the state to the same place {@code TAG_ATTR_VALUE} goes: the attribute's
     * name-derived context. The three shapes below are the same attribute in three positions, and
     * they now agree — which is the whole claim.
     */
    @Test
    public void unquotedValuesTakeTheirNameDerivedContextImmediatelyAfterTheEquals() {
        assertEquals(Canoe.CTX_URI, CanoeTestSupport.contextAfter("<a href="),
                "R19: the name is complete, so the context is known even with no quote and no value");
        assertEquals(Canoe.CTX_URI, CanoeTestSupport.contextAfter("<a href= "),
                "TAG_ATTR_VALUE_BEFORE skips whitespace, and the answer does not depend on it");
        assertEquals(Canoe.CTX_URI, CanoeTestSupport.contextAfter("<a href=/"),
                "one literal character reaches TAG_ATTR_VALUE, which always answered correctly");

        // The routing is the attribute's, not the position's: every classification survives the
        // move, including the two that suppress.
        assertEquals(Canoe.CTX_HTML_ATTR, CanoeTestSupport.contextAfter("<span title="));
        assertEquals(Canoe.CTX_URI_RESOURCE, CanoeTestSupport.contextAfter("<script src="));
        assertEquals(Canoe.CTX_JS, CanoeTestSupport.contextAfter("<a onclick="));
        assertEquals(Canoe.CTX_SUPPRESS, CanoeTestSupport.contextAfter("<div style="),
                "style is ATTR_CSS and suppresses in either position (R14)");
        assertEquals(Canoe.CTX_SUPPRESS, CanoeTestSupport.contextAfter("<div my-widget-config="),
                "an unlisted name is ATTR_UNKNOWN and suppresses in either position (R5)");
    }

    /**
     * The state is entered only from {@code TAG_ATTR_NAME_AFTER} on {@code =}, which is only
     * reachable from {@code TAG_ATTR_NAME}, which classifies the name before it leaves — so
     * {@code attributeContext} in {@code TAG_ATTR_VALUE_BEFORE} is always this attribute's own and
     * never a leftover from an earlier one. That is the precondition R19's case label rests on, and
     * a buffer-residue defect in this component has already been real once (F5), so it is asserted
     * rather than read off the control flow.
     */
    @Test
    public void theContextInValueBeforeBelongsToTheAttributeInFront() {
        assertEquals(Canoe.CTX_URI, CanoeTestSupport.contextAfter("<a title=\"t\" href="),
                "a plain-text attribute before it does not leak ATTR_HTML in");
        assertEquals(Canoe.CTX_HTML_ATTR, CanoeTestSupport.contextAfter("<a href=\"/p\" title="),
                "and a URL attribute before it does not leak ATTR_URI in");
        assertEquals(Canoe.CTX_HTML_ATTR, CanoeTestSupport.contextAfter("<a href=\"javascript:x\" title="),
                "not even when the earlier value narrowed the context through detectAttributePrefix()");
        assertEquals(Canoe.CTX_URI, CanoeTestSupport.contextAfter("<a disabled href="),
                "a valueless attribute in between reclassifies too");
    }

    /**
     * Every state the table reaches that {@code currentContext()} has no case for must fall through
     * to {@code CTX_SUPPRESS}. Fail-closed, which is right — but if a future change gives any of
     * them a real context, that is a security decision and should not happen silently.
     */
    @Test
    public void statesWithNoCaseFallThroughToSuppress() throws IOException {
        Set<Integer> withACase = Set.of(Canoe.HTML, Canoe.SCRIPT, Canoe.SCRIPT_END,
                Canoe.SCRIPT_END_NAME, Canoe.URL, Canoe.CSS, Canoe.CSS_END, Canoe.CSS_END_NAME,
                Canoe.TAG, Canoe.TAG_NAME, Canoe.TAG_ATTR_NAME_AFTER, Canoe.TAG_ATTR_VALUE,
                // R19 added this one. It is in the set rather than in the sweep below, which is the
                // security decision the sweep exists to make visible.
                Canoe.TAG_ATTR_VALUE_BEFORE);

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
            probe.feed("5 < 6");
        } catch (IOException expected) {
            // Canoe rejects a literal '<' in body text; see CanoeRobustnessTest.
        }
        assertEquals(Canoe.INVALID, probe.state());
        assertEquals(Canoe.CTX_SUPPRESS, probe.currentContext());
    }

    // ------------------------------------------------------------------
    // Termination desyncs (F10 and its comment sibling)
    // ------------------------------------------------------------------

    /**
     * F10's forward desync, closed by R17 and now inverted. Was
     * {@code scriptEndAcceptsATagNameItShouldNot}: it pinned the defect that {@code SCRIPT_END}
     * matched the seven characters {@code /script} and set {@code state = TAG} with no check on what
     * followed, so {@code </scriptfoo>} returned Canoe to HTML while the browser's
     * script-data-end-tag-name state kept it in script data — and every reference after it was
     * encoded for a context that did not exist there.
     *
     * <p>R17 moves the decision to {@code SCRIPT_END_NAME}/{@code CSS_END_NAME}, which require the
     * standard's delimiter — tab, LF, FF, CR, space, {@code /} or {@code >} — before the element is
     * treated as closed. A name with any other character after it is not an end tag, and the machine
     * returns to the element body, which is where the browser has been all along.
     */
    @Test
    public void scriptEndRequiresADelimiterAfterTheName() {
        // Closes: each of the delimiters the standard names, in both elements.
        assertEquals(Canoe.CTX_HTML, CanoeTestSupport.contextAfter("<script>x</script>"),
                "'>' closes, as it always did");
        assertEquals(Canoe.CTX_SUPPRESS, CanoeTestSupport.contextAfter("<script>x</script "),
                "R17: a space closes the name and leaves the parser inside the tag");
        assertEquals(Canoe.CTX_HTML, CanoeTestSupport.contextAfter("<script>x</script >"),
                "R17: '</script >' is an end tag with trailing whitespace");
        assertEquals(Canoe.CTX_HTML, CanoeTestSupport.contextAfter("<script>x</script\t>"),
                "R17: tab");
        assertEquals(Canoe.CTX_HTML, CanoeTestSupport.contextAfter("<script>x</script\n>"),
                "R17: line feed");
        assertEquals(Canoe.CTX_HTML, CanoeTestSupport.contextAfter("<script>x</script\r>"),
                "R17: carriage return, which the standard's preprocessing turns into a line feed");
        assertEquals(Canoe.CTX_HTML, CanoeTestSupport.contextAfter("<script>x</script\f>"),
                "R17: form feed");
        assertEquals(Canoe.CTX_HTML, CanoeTestSupport.contextAfter("<script>x</script/>"),
                "R17: '</script/>' - a '/' delimits the name, then TAG_EMPTY_ENDING takes the '>'");

        // Does not close: anything else after the name is character data to the browser, so the
        // element body continues.
        assertEquals(Canoe.CTX_JS, CanoeTestSupport.contextAfter("<script>x</scriptfoo>"),
                "R17: '</scriptfoo>' closes nothing - Canoe and the browser now agree");
        assertEquals(Canoe.CTX_JS, CanoeTestSupport.contextAfter("<script>x</scriptx"),
                "R17: and one extra character is enough to make it not an end tag");
        assertEquals(Canoe.CTX_JS, CanoeTestSupport.contextAfter("<script>x</scrip"),
                "a partial name never closed anything");

        // The CSS twins, character for character.
        assertEquals(Canoe.CTX_HTML, CanoeTestSupport.contextAfter("<style>x</style>"));
        assertEquals(Canoe.CTX_HTML, CanoeTestSupport.contextAfter("<style>x</style >"));
        assertEquals(Canoe.CTX_HTML, CanoeTestSupport.contextAfter("<style>x</style/>"));
        assertEquals(Canoe.CTX_SUPPRESS, CanoeTestSupport.contextAfter("<style>x</stylefoo>"),
                "R17: the same rule in CSS_END_NAME; still CTX_SUPPRESS because the style body is"
                        + " suppressed rather than JavaScript-escaped (R14/F21)");
        assertEquals(Canoe.CTX_SUPPRESS, CanoeTestSupport.contextAfter("<style>x</stylex"));
    }

    /**
     * The other half of F10's forward desync, and the half R17 as first written left open: the name
     * has to be matched with an <em>ASCII</em> fold.
     *
     * <p>The delimiter rule decides what happens after the name; this decides what counts as the
     * name. The standard's script-data-end-tag-name and rawtext-end-tag-name states accept ASCII
     * upper alpha and ASCII lower alpha and nothing else, so a non-ASCII code point in the run is
     * "anything else" and the tokenizer stays in script data. {@code Character.toLowerCase()} is a
     * Unicode fold, and it maps U+0130 LATIN CAPITAL LETTER I WITH DOT ABOVE onto {@code 'i'} - the
     * one code point in the BMP whose fold lands anywhere in {@code /script} or {@code /style}. With
     * it, an end tag spelled with U+0130 matched {@code /script}, closed the element for Canoe and
     * not for the browser, and put {@code html()} or {@code url()} output into what the browser
     * reads as JavaScript: F10's forward desync, unaffected by the delimiter check because the name
     * genuinely does end at a {@code >}.
     *
     * <p>Same shape as {@code aNearMissOfScriptOrStyleIsAnOrdinaryElement} in
     * {@code NearMissNameSweepTest}, at the closing tag and in the dangerous direction. The opening
     * tag folds the same way and is deliberately left alone: there the divergence runs the other way
     * - Canoe enters {@code SCRIPT} where the browser has an unknown element - which suppresses, and
     * suppression is fail-closed.
     */
    @Test
    public void theEndTagNameIsMatchedWithAnAsciiFoldAndNotAUnicodeOne() {
        assertEquals(Canoe.CTX_HTML, CanoeTestSupport.contextAfter("<script>x</SCRIPT>"),
                "ASCII upper alpha still folds, so an uppercase end tag closes");
        assertEquals(Canoe.CTX_HTML, CanoeTestSupport.contextAfter("<script>x</ScRiPt>"),
                "...in any mixture");

        assertEquals(Canoe.CTX_JS, CanoeTestSupport.contextAfter("<script>x</scr\u0130pt>"),
                "U+0130 lowercases to 'i' under Character.toLowerCase() and to itself under the"
                        + " standard's ASCII fold: the run is character data and the element stays"
                        + " open, for Canoe as for the browser");
        assertEquals(Canoe.CTX_JS, CanoeTestSupport.contextAfter("<script>x</scr\u0130pt><a href=\""),
                "and so no attribute encoder is reachable after it either - the sharp form of the"
                        + " desync, which is url() output landing in script data");

        // The style twin cannot be reached by U+0130 - there is no 'i' in '/style' - and a sweep of
        // the BMP finds no second code point that folds into either name, so there is no positive
        // case to write for it. This row is the control that says so: U+017F LATIN SMALL LETTER
        // LONG S is the nearest miss, it never matched 's' under either fold, and it still does not.
        assertEquals(Canoe.CTX_SUPPRESS, CanoeTestSupport.contextAfter("<style>x</\u017Ftyle>"),
                "the CSS twin closes nothing on a non-ASCII near miss, before R17 and after it");
    }

    /**
     * F10's converse desync, closed by R17 and now inverted. Was
     * {@code scriptAndStyleEndSwallowTheCharacterThatMismatched}: {@code SCRIPT_END} returned to
     * {@code SCRIPT} on a mismatch <em>without</em> re-processing the character, so a stray
     * {@code <} swallowed the one that would have started the real closing tag and everything after
     * it was suppressed for the rest of the page.
     *
     * <p>R17 sets {@code charNeedsProcessing = true} on that path, the same idiom five other states
     * in {@code reallyProcessChar()} already use, so the mismatching character is handed back to
     * {@code SCRIPT}/{@code CSS} and a {@code <} there opens a fresh end tag.
     */
    @Test
    public void scriptAndStyleEndReprocessTheCharacterThatMismatched() {
        assertEquals(Canoe.CTX_HTML, CanoeTestSupport.contextAfter("<script>x = 1 <</script>"),
                "R17: the second '<' is re-processed, so the real </script> is recognised");
        assertEquals(Canoe.CTX_HTML, CanoeTestSupport.contextAfter("<script>x = 1 </script>"),
                "the same template without the stray '<' closes, as it always did");
        assertEquals(Canoe.CTX_HTML, CanoeTestSupport.contextAfter("<script>a < b</script>"),
                "R17: an ordinary comparison in the body no longer eats the character after it");
        assertEquals(Canoe.CTX_JS, CanoeTestSupport.contextAfter("<script>a < b"),
                "...and a mismatch on its own leaves the machine in the script body, where it"
                        + " belongs");

        assertEquals(Canoe.CTX_HTML, CanoeTestSupport.contextAfter("<style>a{}<</style>"),
                "R17: the CSS twin, which the original finding does not mention");
        assertEquals(Canoe.CTX_HTML, CanoeTestSupport.contextAfter("<style>a{}</style>"));
    }

    /**
     * F14, closed by R16 and now inverted. Was {@code aCommentEndingInThreeDashesNeverCloses}: it
     * pinned the defect that {@code COMMENT_CLOSE_2} dropped back to {@code COMMENT} on a third dash,
     * so {@code <!--a--->} never closed and every reference for the rest of the page rendered empty.
     *
     * <p>R16 makes {@code COMMENT_CLOSE_2} stay in {@code COMMENT_CLOSE_2} on a {@code -}, exactly as
     * the HTML Standard's comment-end state does — another {@code -} keeps us in comment-end — so the
     * {@code >} that follows any run of two or more dashes closes the comment and the parser returns
     * to HTML. This test asserts that a dash run of any length now closes.
     *
     * <p>One residual divergence, out of R16's scope and recorded rather than hidden: the
     * <em>shortest</em> abrupt-close form {@code <!--->} does not close in Canoe, because Canoe models
     * {@code <!--} as landing directly in {@code COMMENT} (comment state) and has no
     * comment-start/comment-start-dash state, so its single interior dash reaches only
     * {@code COMMENT_CLOSE_1}, and the {@code >} there returns to {@code COMMENT}. The HTML Standard
     * treats {@code <!--->} as an abrupt-closing empty comment. This is the same "not a faithful model
     * of the tokenizer" class F10 was in — R17 has since closed that one — it is fail-closed (the rest
     * of the page is suppressed, never mis-parsed), and R16 deliberately touches only
     * {@code COMMENT_CLOSE_2}.
     */
    @Test
    public void aCommentEndingInThreeDashesNowCloses() {
        assertEquals(Canoe.CTX_HTML, CanoeTestSupport.contextAfter("<!--a-b-->"),
                "two dashes close, as they always did");
        assertEquals(Canoe.CTX_HTML, CanoeTestSupport.contextAfter("<!--a--->"),
                "R16: three dashes close now — the third dash stays in comment-end");
        assertEquals(Canoe.CTX_HTML, CanoeTestSupport.contextAfter("<!--a---->"),
                "R16: and four");
        assertEquals(Canoe.CTX_HTML, CanoeTestSupport.contextAfter("<!------>"),
                "R16: a run of dashes with no comment body still closes");

        // The empty comment <!----> is <!-- followed by -->, so its two closing dashes reach
        // COMMENT_CLOSE_2 and the '>' closes it.
        assertEquals(Canoe.CTX_HTML, CanoeTestSupport.contextAfter("<!---->"),
                "R16: the empty comment <!----> closes");

        // The shortest abrupt-close form <!---> has a single interior dash. Canoe has no
        // comment-start-dash state, so it reaches only COMMENT_CLOSE_1 and the '>' returns to
        // COMMENT: it does not close. Out of R16's scope, fail-closed, and asserted here so the
        // residual is a recorded decision rather than an omission.
        assertEquals(Canoe.CTX_SUPPRESS, CanoeTestSupport.contextAfter("<!--->"),
                "<!---> does not close: Canoe models no comment-start-dash state (fail-closed,"
                        + " out of R16's scope)");
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

        // R7 resolved F7's branch pair. Both branches compared the characters of "data" under
        // comments reading "content" and "data", so "data" reached a suppressing context and
        // "content" had no branch at all; the author's XXX marker sat above the pair. <object data>
        // is a URL, and "content" is a URL only on <meta http-equiv=refresh>, which R10 deliberately
        // left suppressed - so it stays where R5's fail-closed default puts it.
        assertEquals(Canoe.ATTR_URI_RESOURCE, attributeContextOf("<object data=\"x"),
                "R7 made <object data> a URL; R9 narrows it to the resource-loading variant");
        assertEquals(Canoe.ATTR_UNKNOWN, attributeContextOf("<meta content=\"x"),
                "R7 default, R10 confirmed: content is suppressed - recognising a refresh from a"
                        + " description needs sibling-attribute-value tracking Canoe does not have");

        // R5's inversion, in the one line that used to read the other way: a name nothing
        // recognises is ATTR_UNKNOWN, not ATTR_HTML.
        assertEquals(Canoe.ATTR_UNKNOWN, attributeContextOf("<div my-widget-config=\"x"));
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

        assertEquals(Canoe.ATTR_UNKNOWN, attributeContextOf("<div o=\""),
                "a name that does not begin 'on' must fall through to R5's fail-closed default"
                        + " rather than being classified as script; the rule reads two characters,"
                        + " not one");
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
        assertEquals(Canoe.CTX_SUPPRESS, CanoeTestSupport.contextAfter("<a href=\"h\" href2=\""),
                "R5: a near miss on a listed name gets the fail-closed default. It was"
                        + " CTX_HTML_ATTR until R5 inverted it, and the observation the row makes -"
                        + " that the second name and not the first decides - is the same either"
                        + " way, because href2 is not href.");
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
