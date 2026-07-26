package com.webkreator.qlue.view.canoe;

import com.webkreator.qlue.view.Canoe;
import com.webkreator.qlue.view.CanoeEncodingException;
import com.webkreator.qlue.view.CanoeStateProbe;
import com.webkreator.qlue.view.velocity.ProductionRenderProbe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The templates Canoe refuses to render, and what a real response looks like when it does.
 *
 * <p>Canoe is not only an encoder; it is a validating HTML tokenizer that throws on anything it does
 * not recognise. Fifteen call sites raise an encoding error, and none of the shapes that reach them
 * is attacker-controlled — they are all template-authoring hazards. That makes them availability
 * defects rather than vulnerabilities, and it is why they are gathered here rather than ledgered in
 * the corpus.
 *
 * <p><strong>R20 triaged that table, and the three rows it moved are the reason this file reads the
 * way it does.</strong> {@code <br/>} and a name longer than 35 characters were shapes a competent
 * author would write without hesitating, and a second DOCTYPE is what a layout and an included
 * fragment produce between them; all three now render, and the accepted table below is where they
 * went. What is left is deliberate: {@code <p>5 &lt; 6</p>}, {@code </ p>}, {@code </>} and a C0
 * control in body text are template-authoring <em>errors</em> rather than styles, each is a shape no
 * author writes on purpose, and since R21 each arrives as a catchable {@link CanoeEncodingException}
 * on an unflushed, resettable response rather than as a half-written 500. The reasoning per surviving
 * row is on {@link #rejections()}.
 *
 * <p>Each error is asserted with its exact message <em>and</em> its reported line and position. The
 * coordinates are the part most likely to rot silently, because nothing else in the system reads
 * them and they are the only diagnostic a developer gets.
 *
 * <p><strong>F13 is the point of this file.</strong> {@code VelocityViewFactory.render()} used to
 * carry a recovery branch meant to turn an encoding error into an {@code [Encoding Error]} marker in
 * an otherwise-served page. It tested {@code startsWith(Canoe.ERROR_PREFIX)} against the message of
 * the <em>top-level</em> exception, and Velocity always wraps Canoe's {@code IOException} — the
 * production {@code Template.merge()} path yields {@code "IO Error rendering template '...'"}, and
 * this harness's {@code evaluate()} path yields {@code "IO Error in writer: ..."}. Neither starts
 * with the prefix, so the branch never ran: every error below was an unhandled exception, which in
 * production meant a 500 on top of a response that had already been flushed — and, because it had
 * been flushed, a 500 the container could no longer send.
 *
 * <p>R21 closes it. {@code Canoe.raiseError()} throws a {@code CanoeEncodingException}, the factory
 * finds it in the cause chain and rethrows it unwrapped, and the recovery is to <em>fail the request
 * outright</em>: the marker branch is deleted and the partial output is left unflushed so the
 * response can still be reset. {@link #everyErrorCanoeRaisesEscapesRenderAsACatchableCanoeEncodingException}
 * asserts that for every input in the table,
 * {@link #aRejectedTemplateIsNotFlushedSoTheResponseCanStillBeReset} asserts the flush half, and
 * {@link #aRejectedTemplateLeavesAHalfWrittenResponse} pins what is in the writer regardless — which
 * is why leaving it there was never a recovery.
 *
 * <p>Those tests are the one place in this suite that does <em>not</em> use the fast harness. They
 * call the real {@code VelocityViewFactory.render(page, view, writer)} through
 * {@link ProductionRenderProbe} and assert on what escapes it, because a test that re-applied the
 * factory's own broken predicate to an exception the harness produced would have kept passing after
 * the factory was fixed — and a pin that survives the fix is worse than no pin at all.
 */
public class CanoeRobustnessTest {

    // ------------------------------------------------------------------
    // The table
    // ------------------------------------------------------------------

    /**
     * One row per {@code raiseError()} call site in {@code Canoe.java}, in source order, plus the
     * near-miss inputs that must <em>not</em> be rejected.
     *
     * <p>Two messages appear at more than one call site — {@code Invalid tag} twice and
     * {@code Invalid character after tag name} twice — so the rows carry a description naming the
     * state the error was raised from. {@link #everyRaiseErrorMessageIsReached} checks the set of
     * messages against the source file, {@link #theNumberOfRaiseErrorCallSitesIsPinned} checks that
     * the set is still the right size to be checking, and
     * {@link #theTwoInvalidTagSitesAreBothReached} and
     * {@link #theTwoInvalidCharacterAfterTagNameSitesAreBothReached} separate the duplicated pairs.
     *
     * <h2>Why each of these is still a rejection (R20)</h2>
     *
     * <p>R20 triaged F13's table rather than emptying it. What follows is the reasoning for the rows
     * that survived, written here because a rejection with no recorded reason is one the next reader
     * will either delete or defend at random. The common part first: <strong>since R21 a rejection is
     * a {@link CanoeEncodingException} on a response that has not been flushed and can still be
     * reset</strong>, so the cost of one is a clean failed request with coordinates in it, not a
     * half-written page under a 200 and not the unsendable 500 F13 described. That is what makes
     * "reject" an affordable answer for a template defect at all.
     *
     * <ul>
     *   <li><strong>{@code <p>5 < 6</p>} — {@code Tag name too short}.</strong> A literal {@code <}
     *       in prose. Kept, and it is the row with the strongest case: this check is what makes the
     *       body context safe to reason about. Canoe's model of the document is the model the browser
     *       has, and a raw {@code <} is exactly where the two would part company — the browser opens a
     *       tag, and if Canoe did not, every reference after it would be encoded for a context that
     *       does not exist there. The template author's fix is {@code &lt;}, which is what the
     *       character means in text and what every reference Canoe encodes already emits.
     *   <li><strong>{@code </ p>} and {@code </>} — {@code Tag name too short}.</strong> Neither is
     *       an end tag to a browser either: {@code </>} is discarded outright and {@code </ p>}
     *       becomes a bogus comment. There is no author intent to preserve, no serializer emits
     *       them, and accepting them would mean modelling two more browser recovery rules for
     *       nothing.
     *   <li><strong>A C0 control in body text — {@code Invalid character detected in output}.</strong>
     *       Kept, and it is the row a reader is most likely to want relaxed, because a stray byte
     *       from a database column takes the page down. It stays because the character is in the
     *       <em>template's own literal text</em> and not in a value: {@code htmlWhite()} turns a
     *       control inside an encoded reference into the four printable characters {@code \xNN}
     *       before the tokenizer ever sees it, which is why {@code body.paragraph} carries the
     *       CONTROL_CHARS payload family and is SAFE. A control that reaches this check is therefore
     *       a corrupt template file or output written around Canoe, and both are worth being told
     *       about.
     * </ul>
     *
     * <p>The rows R20 <em>removed</em> are in {@link #accepted()} instead: {@code <br/>}, names up to
     * 127 characters, and a second DOCTYPE. {@link #aCommentBeforeTheDoctypeIsNowLegal} carries the
     * DOCTYPE reasoning and {@link #theNameLengthLimitIsOneLessThanTheBufferLength} the length one.
     */
    static Stream<Arguments> rejections() {
        return Stream.of(
                // HTML: a control character in body text. Correct, and undocumented: one stray byte
                // from a database column takes the page down.
                rejected("C0 control character in body text", "<p>a" + ch(0x01) + "b</p>",
                        "Invalid character detected in output", 1, 5),
                rejected("NUL in body text", "<p>" + ch(0x00) + "</p>",
                        "Invalid character detected in output", 1, 4),

                // COMMENT_OPEN_OR_DOCTYPE.
                rejected("DOCTYPE after another element", "<html><!DOCTYPE html>",
                        "DOCTYPE declaration must precede the first element", 1, 9),
                rejected("DOCTYPE after an end tag", "</p><!DOCTYPE html>",
                        "DOCTYPE declaration must precede the first element", 1, 7),
                rejected("bang that is neither comment nor doctype", "<!x>", "Invalid tag", 1, 3),
                rejected("CDATA section", "<p><![CDATA[x]]></p>", "Invalid tag", 1, 6),

                // DOCTYPE_TEST.
                rejected("misspelt DOCTYPE", "<!DXCTYPE html>", "Invalid DOCTYPE declaration", 1, 4),

                // COMMENT_OPEN_2.
                rejected("single dash after a bang", "<!-x-->", "Invalid tag", 1, 4),

                // TAG_NAME.
                rejected("tag name of 128 characters",
                        "<" + repeat('a', Canoe.MAX_TAGNAME_LEN) + ">",
                        "Tag name too long", 1, Canoe.MAX_TAGNAME_LEN + 1),
                rejected("tag name of 129 characters",
                        "<" + repeat('a', Canoe.MAX_TAGNAME_LEN + 1) + ">",
                        "Tag name too long", 1, Canoe.MAX_TAGNAME_LEN + 1),
                rejected("empty tag", "<>", "Tag name too short", 1, 2),
                rejected("empty closing tag", "</>", "Tag name too short", 1, 3),
                rejected("space after a closing slash", "</ p>", "Tag name too short", 1, 3),
                rejected("literal < in body text", "<p>5 < 6</p>", "Tag name too short", 1, 7),
                rejected("XML prolog", "<?xml version=\"1.0\"?>", "Tag name too short", 1, 2),
                rejected("quote directly after a tag name", "<p\"x\">",
                        "Invalid character after tag name", 1, 3),

                // TAG_EMPTY_ENDING.
                rejected("something other than > after /", "<img src=\"a\" /x>",
                        "Expected '>' after '/' in tag.", 1, 15),

                // TAG: a character that cannot start an attribute name.
                rejected("quote where an attribute name should be", "<p \"x\">",
                        "Invalid character in attribute name", 1, 4),

                // TAG_ATTR_NAME.
                rejected("attribute name of 128 characters",
                        "<p " + repeat('a', Canoe.MAX_TAGNAME_LEN) + "=\"1\">",
                        "Attribute name too long", 1, Canoe.MAX_TAGNAME_LEN + 3),
                rejected("attribute name starting with a digit", "<p 1>",
                        "Attribute name too short", 1, 4),
                rejected("attribute name starting with a hyphen", "<p -x>",
                        "Attribute name too short", 1, 4),
                rejected("quote directly after an attribute name", "<p class\"x\">",
                        "Invalid character after tag name", 1, 9),

                // TAG_ATTR_NAME_AFTER.
                rejected("quote after an attribute name and a space", "<p class \"x\">",
                        "Invalid character in tag name", 1, 10));
    }

    /**
     * The shapes that sit just inside each boundary. Without these the table above would pass just as
     * happily against a Canoe that rejected everything.
     */
    static Stream<Arguments> accepted() {
        return Stream.of(
                // R20: the no-space form is legal now, and agrees with the spaced one it always
                // disagreed with. Every element it can be written on is here, because the '/' is
                // routed through TAG_EMPTY_ENDING to whatever state the element implies - which for
                // <script/> and <style/> is not HTML.
                Arguments.of("void element with no space", "<br/>"),
                Arguments.of("void element with a space", "<br />"),
                Arguments.of("self-closed element with attributes", "<img src=\"a.png\" alt=\"a\"/>"),
                Arguments.of("self-closed script element", "<script/>"),
                Arguments.of("self-closed style element", "<style/>"),
                Arguments.of("self-closed element in a document", "<p>a</p><hr/><p>b</p>"),
                Arguments.of("slash after a quoted value", "<img src=\"a.png\"/>"),
                Arguments.of("slash after an unquoted value", "<img src=a.png />"),
                Arguments.of("tag name of 127 characters",
                        "<" + repeat('a', Canoe.MAX_TAGNAME_LEN - 1) + ">"),
                Arguments.of("attribute name of 127 characters",
                        "<p " + repeat('a', Canoe.MAX_TAGNAME_LEN - 1) + "=\"1\">"),
                Arguments.of("framework-length data attribute",
                        "<div data-controller-target-value-for-the-widget=\"1\">"),
                Arguments.of("DOCTYPE first", "<!DOCTYPE html><html></html>"),
                // R20: a browser ignores the second declaration, so Canoe does too - with a warning.
                Arguments.of("second DOCTYPE", "<!DOCTYPE html><!DOCTYPE html>"),
                Arguments.of("second DOCTYPE after a comment",
                        "<!DOCTYPE html><!-- c --><!DOCTYPE html>"),
                Arguments.of("DOCTYPE in lower case", "<!doctype html><html></html>"),
                Arguments.of("legacy DOCTYPE with a public identifier",
                        "<!DOCTYPE html PUBLIC \"-//W3C//DTD HTML 4.01//EN\">"),
                Arguments.of("DOCTYPE after leading text", "hello<!DOCTYPE html>"),
                Arguments.of("DOCTYPE after a comment", "<!-- c --><!DOCTYPE html><html></html>"),
                Arguments.of("DOCTYPE after several comments and text",
                        "<!-- a -->\n<!-- b -->  x  <!DOCTYPE html><html></html>"),
                Arguments.of("comment", "<!-- c -->"),
                Arguments.of("tab, CR and LF in body text", "<p>a\tb\r\nc</p>"),
                Arguments.of("DEL, which is not below 0x20", "<p>" + ch(0x7f) + "</p>"),
                Arguments.of("attribute value of 40 characters",
                        "<p class=\"" + repeat('a', 40) + "\">"),

                // Nothing raises an error at end of input, so every unterminated construct is
                // accepted. Canoe validates transitions, not well-formedness.
                Arguments.of("unclosed tag at end of output", "<p class=\"x\""),
                Arguments.of("unclosed comment", "<!-- x"),
                Arguments.of("unclosed script element", "<script>x=1"),
                Arguments.of("unclosed attribute value", "<a href=\"/x"));
    }

    private static Arguments rejected(String description, String input, String message,
                                      int line, int position) {
        return Arguments.of(description, input, message, line, position);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejections")
    public void isRejected(String description, String input, String message, int line, int position)
            throws IOException {
        CanoeTestSupport.WriteResult result = CanoeTestSupport.write(input);

        assertTrue(result.isError(), () -> description + ": expected "
                + CanoeTestSupport.quote(input) + " to be rejected, but it rendered as "
                + CanoeTestSupport.quote(result.output()));
        assertEquals(expectedMessage(message, line, position), result.errorMessage(), description);

        // Every raiseError() leaves the parser in INVALID, which currentContext() has no case for,
        // so anything written afterwards is suppressed rather than encoded for a stale context.
        CanoeStateProbe probe = new CanoeStateProbe();
        try {
            probe.feed(input);
        } catch (IOException expected) {
            // The error under test.
        }
        assertEquals(Canoe.INVALID, probe.state(), description + ": must end in INVALID");
        assertEquals(Canoe.CTX_SUPPRESS, probe.currentContext(), description);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("accepted")
    public void isAccepted(String description, String input) {
        CanoeTestSupport.WriteResult result = CanoeTestSupport.write(input);

        assertFalse(result.isError(), () -> description + ": expected "
                + CanoeTestSupport.quote(input) + " to render cleanly, but Canoe raised "
                + result.errorMessage());
        assertEquals(input, result.output(), description + ": output must be verbatim");
    }

    private static String expectedMessage(String message, int line, int position) {
        return Canoe.ERROR_PREFIX + message + " (line: " + line + ", pos: " + position + ")";
    }

    // ------------------------------------------------------------------
    // Coverage of the raiseError() call sites
    // ------------------------------------------------------------------

    /**
     * Every distinct {@code raiseError()} <em>message</em> in {@code Canoe.java} must be reached by
     * the table above.
     *
     * <p>The list of messages is read out of the source file rather than hand-maintained, for the
     * same reason {@code CanoeStateMachineTest} reads the state constants by reflection: a new
     * rejection added to the tokenizer should fail this test rather than quietly go untested. There
     * is no reflective handle on a string literal inside a method, so the source is scanned.
     *
     * <p><strong>This is message coverage, not call-site coverage, and the name now says so.</strong>
     * There are 15 {@code raiseError(...)} call sites and only 13 distinct message literals, so a set
     * comparison is satisfied by 13 of the 15 — and a <em>new</em> call site added with an existing
     * message leaves the set unchanged and this test green, which is precisely the drift it is
     * supposed to catch. {@link #theNumberOfRaiseErrorCallSitesIsPinned} closes that gap by counting
     * occurrences; {@link #theTwoInvalidTagSitesAreBothReached} and
     * {@link #theTwoInvalidCharacterAfterTagNameSitesAreBothReached} pin the two duplicated messages
     * to the states that raise them, which is the part a count cannot express.
     *
     * <p>{@code Internal error #1001} is expected to be reported as unreachable; see
     * {@link #theInternalErrorBranchIsDeadCode} for the argument and the exhaustive check.
     */
    @Test
    public void everyRaiseErrorMessageIsReached() throws IOException {
        Set<String> declared = new LinkedHashSet<>(raiseErrorMessagesInSource());
        Set<String> covered = new LinkedHashSet<>();
        for (Arguments row : (Iterable<Arguments>) rejections()::iterator) {
            covered.add((String) row.get()[2]);
        }

        List<String> unreached = new ArrayList<>(declared);
        unreached.removeAll(covered);

        assertEquals(List.of("Internal error #1001"), unreached,
                "The set of raiseError() messages no test reaches changed. Add a row to"
                        + " rejections() for any new one; if Internal error #1001 is now reachable,"
                        + " theInternalErrorBranchIsDeadCode should be failing too.");

        List<String> unknown = new ArrayList<>(covered);
        unknown.removeAll(declared);
        assertEquals(List.of(), unknown,
                "the table expects a message Canoe no longer raises");
    }

    /**
     * {@code Internal error #1001} ({@code Canoe.java:928-931}) cannot be reached.
     *
     * <p>Its guard is {@code bufLen == buf.length}, that is 36, inside the attribute-value scan. But
     * {@code bufLen} is set to 0 on entry to {@code TAG_ATTR_VALUE} ({@code Canoe.java:868}), is
     * incremented by exactly one per buffered character, and the branch immediately above sets it to
     * -1 as soon as it reaches 10. So while the scan is live {@code bufLen} is in 0..10, and once it
     * is -1 the whole block is skipped. There is no path to 36.
     *
     * <p>Asserted rather than argued: an attribute value far longer than the buffer is fed one
     * character at a time and {@code bufLen} is checked after each. If a future change lets the scan
     * run past ten characters this fails here, and {@link #everyRaiseErrorMessageIsReached} will
     * then be the test that says the branch needs a row.
     */
    @Test
    public void theInternalErrorBranchIsDeadCode() throws IOException {
        CanoeStateProbe probe = new CanoeStateProbe().feed("<p class=\"");

        for (int i = 0; i < 200; i++) {
            probe.feed(String.valueOf((char) ('a' + (i % 26))));
            int bufLen = probe.bufLen();
            assertTrue(bufLen == -1 || (bufLen >= 0 && bufLen <= 10),
                    "the value scan's bufLen reached " + bufLen + " after " + (i + 1)
                            + " characters; Internal error #1001 needs 36");
        }

        // The same, through the ordinary write path, to show that no length of value is rejected.
        CanoeTestSupport.WriteResult result =
                CanoeTestSupport.write("<p class=\"" + repeat('z', 1000) + "\">");
        assertFalse(result.isError(), "no attribute value length is rejected");
    }

    /**
     * Both {@code Invalid tag} sites, which the message alone cannot distinguish: one is a bang
     * followed by something that is neither {@code -} nor {@code d}, the other is a single dash that
     * is not followed by a second.
     */
    @Test
    public void theTwoInvalidTagSitesAreBothReached() throws IOException {
        assertEquals(Canoe.COMMENT_OPEN_OR_DOCTYPE, stateBefore("<!"));
        assertEquals(expectedMessage("Invalid tag", 1, 3),
                CanoeTestSupport.write("<!x>").errorMessage());

        assertEquals(Canoe.COMMENT_OPEN_2, stateBefore("<!-"));
        assertEquals(expectedMessage("Invalid tag", 1, 4),
                CanoeTestSupport.write("<!-x-->").errorMessage());
    }

    /**
     * Both {@code Invalid character after tag name} sites. The message is the same and the wording
     * fits only the first: the second is raised from {@code TAG_ATTR_NAME}, where the offending
     * character follows an <em>attribute</em> name, not a tag name. The second is also the only site
     * that assigns {@code state = INVALID} itself rather than relying on {@code raiseError()} to do
     * it.
     *
     * <p>The first site used to be reached by {@code <br/>}, which R20 made legal. It is still
     * reachable and still worth a row — the character after a tag name may now be whitespace,
     * {@code >} or {@code /} and nothing else — so the fixture moved to a quote, which is the exact
     * twin of the {@code <p class"x">} below it and makes the pair read as one table.
     */
    @Test
    public void theTwoInvalidCharacterAfterTagNameSitesAreBothReached() throws IOException {
        assertEquals(Canoe.TAG_NAME, stateBefore("<p"));
        assertEquals(expectedMessage("Invalid character after tag name", 1, 3),
                CanoeTestSupport.write("<p\"x\">").errorMessage());

        assertEquals(Canoe.TAG_ATTR_NAME, stateBefore("<p class"));
        assertEquals(expectedMessage("Invalid character after tag name", 1, 9),
                CanoeTestSupport.write("<p class\"x\">").errorMessage());
    }

    /**
     * How many {@code raiseError(...)} call sites {@code Canoe.java} contains, and how many distinct
     * messages they use. The gap between them is the whole reason both numbers are pinned.
     */
    private static final int EXPECTED_RAISE_ERROR_CALL_SITES = 15;

    private static final int EXPECTED_DISTINCT_RAISE_ERROR_MESSAGES = 13;

    /**
     * The count that {@link #everyRaiseErrorMessageIsReached} cannot check, because deduplicating is
     * the first thing a set does.
     *
     * <p>Both numbers are asserted, and they fail for different reasons:
     *
     * <ul>
     *   <li>a call site <em>added</em> with a message that already exists moves the site count and
     *       not the message count — the drift a set comparison is blind to;</li>
     *   <li>a call site <em>deleted</em> where its message survives elsewhere does the same in
     *       reverse;</li>
     *   <li>a message added or removed moves both.</li>
     * </ul>
     *
     * <p>Neither number is load-bearing on its own. Their purpose is to make any edit to the
     * tokenizer's error handling stop here first, where the message explains what to do about it,
     * rather than surfacing as a puzzling gap somewhere in the rejection table.
     */
    @Test
    public void theNumberOfRaiseErrorCallSitesIsPinned() throws IOException {
        List<String> callSites = raiseErrorMessagesInSource();

        assertEquals(EXPECTED_RAISE_ERROR_CALL_SITES, callSites.size(),
                "the number of raiseError() call sites in Canoe.java changed. If a site was added,"
                        + " give it a row in rejections() and update this count; if one was removed,"
                        + " remove its row. Found: " + callSites);

        assertEquals(EXPECTED_DISTINCT_RAISE_ERROR_MESSAGES,
                new LinkedHashSet<>(callSites).size(),
                "the number of distinct raiseError() messages changed. Two messages are used twice"
                        + " each, which is why this is not the same number as the count above.");
    }

    /**
     * Reads every {@code raiseError("...")} argument out of the tokenizer's source, in source order
     * and <em>with duplicates</em>, so that callers can count sites as well as compare messages.
     * Fails loudly when the source tree is not on disk — the assertions this feeds are about the
     * source file, and pretending to check it from a jar would be worse than not checking it.
     */
    private static List<String> raiseErrorMessagesInSource() throws IOException {
        Path source = Path.of("src/main/java/com/webkreator/qlue/view/Canoe.java");
        assertTrue(Files.isReadable(source),
                "cannot read " + source.toAbsolutePath() + "; this test must run with the project"
                        + " directory as its working directory");

        String text = Files.readString(source, StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("raiseError\\(\"([^\"]*)\"\\)").matcher(text);
        List<String> messages = new ArrayList<>();
        while (matcher.find()) {
            messages.add(matcher.group(1));
        }
        return messages;
    }

    private static int stateBefore(String prefix) throws IOException {
        return new CanoeStateProbe().feed(prefix).state();
    }

    // ------------------------------------------------------------------
    // The MAX_TAGNAME_LEN boundary
    // ------------------------------------------------------------------

    /**
     * {@code MAX_TAGNAME_LEN} is the length of the buffer, not the limit on a name: a name is
     * rejected once it would fill the last slot, which the name scan reserves for its NUL terminator,
     * so the longest name that renders is one character shorter. Both tag names and attribute names
     * share the constant and the buffer.
     *
     * <p><strong>R20 raised the constant from 36 to 128</strong>, which is the third row of F13's
     * table and the one &sect;5 observation 1 of the remediation plan is about: the finding names
     * {@code Tag name too long}, but the attribute sibling is the one a real page hits. Every
     * assertion below used to read 35 and 36 and is the same assertion at the new boundary; what has
     * been added is the third block, which is the shape the old limit actually broke. A
     * {@code data-*} attribute name from any modern framework — {@code
     * data-controller-target-value-for-the-widget} is 43 characters and unremarkable — was a failed
     * request, and nothing about it is attacker-reachable or even unusual.
     *
     * <p>Formerly {@code theNameLengthLimitIsOneLessThanTheBufferLength} carried this as a defect
     * rather than as a boundary: "35 is short for a custom element. {@code
     * <data-widget-configuration-attribute>} is 35 and renders; one more character and the page is a
     * 500." The name is kept because the relationship it states — the limit is the buffer minus one —
     * is still exactly true and is still the thing a future change to the constant must preserve.
     */
    @Test
    public void theNameLengthLimitIsOneLessThanTheBufferLength() {
        assertEquals(128, Canoe.MAX_TAGNAME_LEN, "R20 raised the buffer from 36");
        int longest = Canoe.MAX_TAGNAME_LEN - 1;

        for (int length = 1; length <= longest; length++) {
            assertFalse(CanoeTestSupport.write("<" + repeat('a', length) + ">").isError(),
                    "a tag name of " + length + " characters must render");
            assertFalse(CanoeTestSupport.write("<p " + repeat('a', length) + "=\"1\">").isError(),
                    "an attribute name of " + length + " characters must render");
        }

        assertEquals(expectedMessage("Tag name too long", 1, Canoe.MAX_TAGNAME_LEN + 1),
                CanoeTestSupport.write("<" + repeat('a', Canoe.MAX_TAGNAME_LEN) + ">")
                        .errorMessage());
        assertEquals(expectedMessage("Attribute name too long", 1, Canoe.MAX_TAGNAME_LEN + 3),
                CanoeTestSupport.write("<p " + repeat('a', Canoe.MAX_TAGNAME_LEN) + "=\"1\">")
                        .errorMessage());

        // The shapes the old limit rejected, at their real lengths rather than as a count.
        assertFalse(CanoeTestSupport.write("<data-widget-configuration-attribute>").isError(),
                "35 characters: this rendered before R20 as well, and it was the ceiling");
        assertFalse(CanoeTestSupport.write("<data-widget-configuration-attributes>").isError(),
                "R20: 36, which used to be the first length that took the page down");
        assertFalse(CanoeTestSupport
                        .write("<div data-controller-target-value-for-the-widget=\"1\">").isError(),
                "R20: and the attribute half, which is the one an ordinary page hits - 43"
                        + " characters, and every framework generates names like it");
        assertFalse(CanoeTestSupport.write("<data-widget-configuration-attribute>"
                        + "</data-widget-configuration-attribute>").isError(),
                "R20: and the asymmetry that came with the old limit - buf[0] holds the '/' of an"
                        + " end tag, so a closing name has one character less room than the start"
                        + " tag it matches, and a 35-character element could be opened and never"
                        + " closed. The asymmetry survives the raise and now bites at 127, where no"
                        + " real element name reaches: reject.closing-tag-name-at-the-limit");
        assertTrue(CanoeTestSupport.write("<" + repeat('a', longest) + "></" + repeat('a', longest)
                        + ">").isError(),
                "...which is to say the closing half of the boundary is still one character shorter");
    }

    // ------------------------------------------------------------------
    // Error coordinates
    // ------------------------------------------------------------------

    /**
     * The reported position is the one-based index of the offending character, and the line counter
     * advances on LF. Nothing in the system consumes these numbers, so they are asserted here or
     * nowhere.
     */
    @Test
    public void errorCoordinatesTrackLinesAndColumns() {
        // The fixture is "5 < 6" rather than "<br/>", which R20 made legal. It fails at the same
        // offset - the fourth character - so every coordinate in this test is the one it always was.
        assertEquals(expectedMessage("Tag name too short", 1, 4),
                CanoeTestSupport.write("5 < 6").errorMessage());
        assertEquals(expectedMessage("Tag name too short", 2, 4),
                CanoeTestSupport.write("<p>\n5 < 6").errorMessage());
        assertEquals(expectedMessage("Tag name too short", 4, 4),
                CanoeTestSupport.write("<p>\n</p>\n<p>\n5 < 6").errorMessage());
        assertEquals(expectedMessage("Tag name too short", 2, 16),
                CanoeTestSupport.write("line one\nline two <p>5 < 6").errorMessage());

        // A CR does not start a new line; only LF does, so a CRLF file reports the same coordinates
        // as an LF one.
        assertEquals(expectedMessage("Tag name too short", 2, 4),
                CanoeTestSupport.write("<p>\r\n5 < 6").errorMessage());
    }

    // ------------------------------------------------------------------
    // F13 - the rejection a caller can catch (R21)
    // ------------------------------------------------------------------

    /**
     * F13, inverted by R21. Formerly {@code noErrorCanoeRaisesIsSwallowedInProduction}, which
     * recorded the defect: {@code VelocityViewFactory.render()}'s recovery branch read
     * {@code e.getMessage().startsWith(Canoe.ERROR_PREFIX)} on the exception it <em>caught</em>, and
     * the exception it caught was never Canoe's. Velocity wraps an {@code IOException} from the
     * writer in a {@code VelocityException} of its own — {@code "IO Error rendering template '...'"}
     * on the production {@code Template.merge()} path, {@code "IO Error in writer: ..."} on
     * {@code evaluate()} — so the test was applied to the wrapper's message, could never be true, and
     * every encoding error left {@code render()} as an unhandled exception on top of a response that
     * had already been flushed.
     *
     * <p>R21 replaces the message test with a type in the cause chain
     * ({@code CanoeEncodingException.findIn}) and unwraps it, so what a caller of {@code render()}
     * now sees is the {@link CanoeEncodingException} itself: catchable by type, carrying the reason
     * and the coordinates as fields. This test asserts that for every input in the rejection table —
     * the whole table rather than one example, because "no error is swallowed" was a claim about all
     * of them and so is "every error arrives catchable".
     *
     * <p><strong>The old assertion, exactly inverted.</strong> The last check below is the one the
     * broken branch performed: {@code getMessage().startsWith(ERROR_PREFIX)} on the top-level
     * exception. It used to be false for every input, and the old test pinned it as false against the
     * production wrapper message. It is now true for every input — and the point is that
     * {@code render()} no longer needs to ask, because the type already answered.
     *
     * <p><strong>Why this goes through {@link ProductionRenderProbe} rather than the fast harness.</strong>
     * Unchanged from the original, and it is the reason the original was written that way: a test that
     * re-applies the factory's own predicate to an exception the harness produced is a copy of the
     * check under test and would answer identically before and after the fix. This version calls the
     * real {@code render()} and asserts on what a caller observes. It also covers the path the rest of
     * the suite cannot — everything else renders through {@code VelocityEngine.evaluate()}, and only
     * {@code Template.merge()} is production.
     */
    @Test
    public void everyErrorCanoeRaisesEscapesRenderAsACatchableCanoeEncodingException() {
        for (Arguments row : (Iterable<Arguments>) rejections()::iterator) {
            String description = (String) row.get()[0];
            String input = (String) row.get()[1];
            String message = (String) row.get()[2];
            int line = (Integer) row.get()[3];
            int position = (Integer) row.get()[4];

            ProductionRenderProbe.Outcome outcome = ProductionRenderProbe.render(input);

            assertTrue(outcome.exceptionEscaped(),
                    "R21: " + description + " must still fail the request - the recovery is to fail"
                            + " outright, not to serve a degraded page. Was: " + outcome);

            assertInstanceOf(CanoeEncodingException.class, outcome.escaped(),
                    "R21: " + description + " must reach the caller as the exception Canoe threw,"
                            + " not as Velocity's wrapper: that is what makes it catchable by type."
                            + " Was: " + outcome);

            CanoeEncodingException error = outcome.encodingError();
            assertEquals(message, error.getReason(), description + ": the reason, as a field");
            assertEquals(line, error.getLine(), description + ": the line, as a field");
            assertEquals(position, error.getPosition(), description + ": the position, as a field");
            assertEquals(expectedMessage(message, line, position), error.getMessage(),
                    description + ": and the message is unchanged");

            assertFalse(outcome.recoveryBranchRan(),
                    "R21: " + description + " must not produce an [Encoding Error] marker. The"
                            + " branch that appended it is deleted rather than repaired: the response"
                            + " ends inside an element, so the marker would land in an attribute"
                            + " list. Was: " + outcome);

            // The check the broken branch performed, on the exception it actually caught. It was
            // false for every input in this table, which is F13; it is true for every one of them
            // now - and render() no longer asks.
            assertTrue(outcome.escaped().getMessage().startsWith(Canoe.ERROR_PREFIX),
                    "R21: the top-level message now does start with "
                            + CanoeTestSupport.quote(Canoe.ERROR_PREFIX) + " - was: "
                            + CanoeTestSupport.quote(outcome.escaped().getMessage()));
        }
    }

    /**
     * The half of R21's recovery that a {@code StringWriter} cannot show: {@code render()} does not
     * <strong>flush</strong> the partial page when Canoe refuses.
     *
     * <p>This is the load-bearing half. Canoe streams, so everything it accepted is already in the
     * writer by the time it throws, and no catch block can take those characters back. What the catch
     * block decides is whether they are <em>committed</em>: production's writer is
     * {@code response.getWriter()}, a servlet response commits when its buffer is flushed, and
     * {@code QlueApplication.service()} will only call {@code sendError(500)} — or let the page's own
     * {@code handleException()} view replace the body — while the response is uncommitted. The old
     * {@code finally} block flushed unconditionally, which committed a half-written page and left the
     * container nothing to work with; a 500 that could not be sent is why F13 is worse than the
     * unreachable branch alone suggests.
     *
     * <p>The successful render is the control: the flush is suppressed on the error path only, and a
     * page that renders is still flushed exactly once.
     */
    @Test
    public void aRejectedTemplateIsNotFlushedSoTheResponseCanStillBeReset() {
        ProductionRenderProbe.FlushCountingWriter rejected =
                new ProductionRenderProbe.FlushCountingWriter();
        ProductionRenderProbe.Outcome outcome = ProductionRenderProbe.render(
                REJECTED_TEMPLATE, Map.of(),
                ProductionRenderProbe.Options.defaults(), rejected);

        assertTrue(outcome.exceptionEscaped(), () -> "the premise: " + outcome);
        assertEquals(0, rejected.flushes(),
                "R21: the partial page must not be flushed, or the response commits and Qlue's"
                        + " sendError(500) is skipped by its own isCommitted() guard");
        assertEquals(REJECTED_TEMPLATE_PREFIX, rejected.toString(),
                "the characters Canoe accepted are in the writer either way - it wrote them"
                        + " through before it threw - which is why the flush is the whole decision");

        ProductionRenderProbe.FlushCountingWriter accepted =
                new ProductionRenderProbe.FlushCountingWriter();
        ProductionRenderProbe.Outcome clean = ProductionRenderProbe.render(
                "<p>$data</p>", Map.of("data", "x"),
                ProductionRenderProbe.Options.defaults(), accepted);

        assertFalse(clean.exceptionEscaped(), () -> "the control: " + clean);
        assertEquals(1, accepted.flushes(), "a page that renders is still flushed");
        assertEquals(0, accepted.closes(),
                "and still not closed - Qlue appends development information after render()");
    }

    /**
     * The premise of the test above, isolated: {@code render()} does have a working non-error path,
     * so "an exception escaped" is a statement about encoding errors and not about the probe being
     * unable to render anything at all.
     */
    @Test
    public void theProductionPathRendersCleanlyWhenNothingIsRejected() {
        ProductionRenderProbe.Outcome outcome =
                ProductionRenderProbe.render("<p>$data</p>", "<b>");

        assertFalse(outcome.exceptionEscaped(), "a valid template must render: " + outcome);
        assertEquals("<p>&lt;b&gt;</p>", outcome.output(),
                "and Canoe must be wired in, so the payload is encoded rather than passed through");
    }

    /**
     * The other half of F13, and the half that decided R21's recovery: the bytes already written.
     *
     * <p>{@code Canoe.write} writes everything up to the offending character before rethrowing, so a
     * prefix of the page is in the writer no matter what the catch block does. For an error inside a
     * tag that prefix ends mid-element — an unterminated {@code <img}, with the browser still waiting
     * for the {@code >}. Appending {@code [Encoding Error]} to that, which is what the unreachable
     * branch intended, would have put the marker inside an attribute list rather than in the
     * document. Truncating to the last {@code >} would have produced a document a browser renders
     * happily, missing its content and its footer, under a 200. Neither is a recovery, which is why
     * R21 fails the request and — the part this test cannot see, because a {@code StringWriter} has
     * no buffer to commit — declines to flush what is here, so the response can still be replaced
     * wholesale. See {@link #aRejectedTemplateIsNotFlushedSoTheResponseCanStillBeReset}.
     *
     * <p>The assertions are unchanged from before R21. They are about what Canoe leaves in the
     * writer, and R21 changed nothing about that: no catch block can un-write a streamed character.
     */
    @Test
    public void aRejectedTemplateLeavesAHalfWrittenResponse() {
        CanoeTestSupport.RenderResult inBody = CanoeTestSupport.render(REJECTED_TEMPLATE);
        assertEquals(REJECTED_TEMPLATE_PREFIX, inBody.output(),
                "the offending character is withheld, everything before it is already sent");

        CanoeTestSupport.RenderResult inTag =
                CanoeTestSupport.render("<div class=\"a\"><img src=\"$data\" /y>", "value");
        assertEquals("<div class=\"a\"><img src=\"value\" /", inTag.output(),
                "F13: the response ends inside the img element, with an unmatched '<'");
        assertTrue(inTag.output().lastIndexOf('<') > inTag.output().lastIndexOf('>'),
                "the last '<' is unclosed, so appending anything appends it to an attribute list");

        // A reference that was rendered before the error still reaches the client, correctly
        // encoded. The failure is not atomic: there is no rollback.
        CanoeTestSupport.RenderResult afterAReference =
                CanoeTestSupport.render("<p>$data</p>5 < 6", "<b>");
        assertEquals("<p>&lt;b&gt;</p>5 <", afterAReference.output());
    }

    /**
     * The template every F13/R21 test in this file uses to reach a rejection, and the exact prefix
     * Canoe leaves in the writer when it does.
     *
     * <p>It used to be {@code <p>ok</p><br/>}, the review's own first rejection row, which R20 made
     * legal. The replacement is chosen so that nothing else in these tests had to move: {@code 5 < 6}
     * is the same length as {@code <br/>} and fails at the same offset — the fourth character — so
     * every coordinate, every {@code write(char[], int, int)} range in {@code CanoeWriterContractTest}
     * and every "how many characters reached the writer" assertion reads exactly as before. It is also
     * the row R20 deliberately keeps, so the fixture is a rejection that is meant to stay one rather
     * than the next one waiting to be triaged away.
     *
     * <p>The prefix ends on an unmatched {@code <}, which is the property
     * {@link #aRejectedTemplateLeavesAHalfWrittenResponse} is about: appending anything to it appends
     * it to a half-open tag.
     */
    static final String REJECTED_TEMPLATE = "<p>ok</p>5 < 6";

    static final String REJECTED_TEMPLATE_PREFIX = "<p>ok</p>5 <";

    // ------------------------------------------------------------------
    // F14 - the comment that now closes (R16)
    // ------------------------------------------------------------------

    /**
     * F14, as a rendering outcome, inverted by R16. Was
     * {@code aCommentEndingInThreeDashesEmptiesTheRestOfThePage}: it established that
     * {@code COMMENT_CLOSE_2} dropped back to {@code COMMENT} on a third dash and so never saw the
     * {@code >}, which cost every reference from the comment onwards its value — silently, with no
     * error, including references many kilobytes later in a different included template.
     *
     * <p>R16 keeps {@code COMMENT_CLOSE_2} on a third (or later) dash, so the {@code >} that follows a
     * dash run closes the comment and the references after it render in their real context. This test
     * asserts the value that used to vanish now arrives.
     */
    @Test
    public void aCommentEndingInThreeDashesNowClosesAndTheRestOfThePageRenders() {
        assertEquals("<!--a--><p>PAYLOAD</p>",
                CanoeTestSupport.render("<!--a--><p>$data</p>", "PAYLOAD").output(),
                "two dashes: the comment closes and the reference renders");

        assertEquals("<!--a---><p>PAYLOAD</p>",
                CanoeTestSupport.render("<!--a---><p>$data</p>", "PAYLOAD").output(),
                "R16: three dashes close, and the reference after it renders");
        assertEquals("<!--a----><p>PAYLOAD</p>",
                CanoeTestSupport.render("<!--a----><p>$data</p>", "PAYLOAD").output(),
                "R16: four dashes, likewise");

        // The reference is now in its real context, so it is encoded for that context rather than
        // dropped: markup in a text sink is HTML-escaped, not emptied.
        assertEquals("<!--a---><p>&lt;b&gt;</p>",
                CanoeTestSupport.render("<!--a---><p>$data</p>", "<b>").output(),
                "R16: the reference renders in the text context and is HTML-escaped there");

        // It reaches later elements too: the parser leaves COMMENT at the '>', so an attribute value
        // several elements on renders normally.
        assertEquals("<!--a---><a title=\"PAYLOAD\" href=\"PAYLOAD\">x</a>",
                CanoeTestSupport.render("<!--a---><a title=\"$data\" href=\"$data\">x</a>",
                        "PAYLOAD").output(),
                "R16: later elements render, each in its own context");

        assertFalse(CanoeTestSupport.render("<!--a---><p>$data</p>", "PAYLOAD").isError(),
                "still no error - the fix is a state transition, not a new failure path");
    }

    // ------------------------------------------------------------------
    // F18 - the DOCTYPE a comment used to make illegal
    // ------------------------------------------------------------------

    /**
     * F18, inverted by R18. Formerly
     * {@code aCommentBeforeTheDoctypeMakesTheDoctypeIllegal}, which recorded the defect: {@code
     * tagCount} was incremented for every {@code <} seen in {@code HTML} state, comments included, and
     * the DOCTYPE check demanded {@code tagCount == 1}, so a comment before the DOCTYPE — a licence
     * header, an editor marker, a conditional comment — made the DOCTYPE illegal and took the page
     * down with {@code DOCTYPE declaration must be at the beginning}. The check wanted "no
     * <em>element</em> has been emitted yet" and asked "no {@code <} has been seen yet" instead.
     *
     * <p>R18 replaces the counter with {@code elementSeen}, set where TAG_NAME commits to a tag and
     * not where the {@code '!'} of a bang declaration does, so comments — any number of them, with
     * whitespace and text between — no longer stand between a template and its DOCTYPE.
     *
     * <p>The rejections that must survive are the point of the second half: this fix must not widen
     * into "a DOCTYPE anywhere". They live in {@link #rejections()} as well, which is what makes them
     * part of the message-and-position table; they are repeated here because they are the regression
     * net for this specific change and belong beside the thing it made legal.
     */
    @Test
    public void aCommentBeforeTheDoctypeIsNowLegal() {
        assertFalse(CanoeTestSupport.write("<!DOCTYPE html><html></html>").isError());
        assertFalse(CanoeTestSupport.write("\n  <!DOCTYPE html><html></html>").isError(),
                "leading whitespace was always fine and stays fine");

        // The shape F18 was about.
        assertFalse(CanoeTestSupport.write("<!-- licence --><!DOCTYPE html><html></html>").isError(),
                "R18: a licence header above the DOCTYPE is legal HTML and now renders");

        // Several comments, with whitespace and text between them.
        assertFalse(CanoeTestSupport.write(
                        "<!-- a -->\n<!-- b --> generated <!-- c --><!DOCTYPE html><html></html>")
                .isError(), "R18: comments do not accumulate towards anything any more");

        // A comment is markup that is legal before the DOCTYPE; an element is not.
        assertEquals(expectedMessage("DOCTYPE declaration must precede the first element", 1, 9),
                CanoeTestSupport.write("<html><!DOCTYPE html>").errorMessage(),
                "the case the check was written for, which is a genuine error");
        assertEquals(expectedMessage("DOCTYPE declaration must precede the first element", 1, 7),
                CanoeTestSupport.write("</p><!DOCTYPE html>").errorMessage(),
                "an end tag is a tag too: it moves a browser past the initial insertion mode"
                        + " exactly as a start tag does");
        assertEquals(expectedMessage("DOCTYPE declaration must precede the first element", 1, 16),
                CanoeTestSupport.write("<!-- c --><p><!DOCTYPE html>").errorMessage(),
                "a comment before the element does not buy the DOCTYPE a place after it");

        // Two DOCTYPEs, with and without a comment between them. R18 left these rejected and R20
        // relaxed them; see theSecondDoctypeIsIgnoredWithAWarning for the decision and the
        // diagnostic. What belongs to F18 is the bound: the comment above the first declaration is
        // what makes these documents legal, and it does not become a licence for a declaration
        // anywhere - the elementSeen rejections above still fire.
        assertFalse(CanoeTestSupport.write("<!DOCTYPE html><!DOCTYPE html>").isError(),
                "R20: the second DOCTYPE is ignored rather than refused, as a browser ignores it");
        assertFalse(CanoeTestSupport.write("<!DOCTYPE html><!-- c --><!DOCTYPE html>").isError(),
                "R20: and a comment between them changes nothing either way");

        // Body text before the DOCTYPE stays accepted, as it always was. The HTML Standard's
        // "initial" insertion mode ignores whitespace and calls other text a parse error, so a
        // browser would ignore this DOCTYPE and render in quirks mode. R18 accepted it silently
        // and R20 keeps the acceptance and adds the warning: see
        // theQuirksModeConsequenceOfTextBeforeTheDoctypeIsWarnedAbout.
        assertFalse(CanoeTestSupport.write("hello<!DOCTYPE html>").isError(),
                "leading text is accepted, deliberately and unchanged");
    }

    // ------------------------------------------------------------------
    // R20 - the two DOCTYPE rejections that became warnings
    // ------------------------------------------------------------------

    /**
     * R20's first DOCTYPE decision, inverting the half of {@link #aCommentBeforeTheDoctypeIsNowLegal}
     * that used to assert {@code Duplicate DOCTYPE declaration} at line 1, position 18.
     *
     * <p>R18 gave that rejection its own message rather than deciding it, and said so: whether being
     * stricter than a browser is right here is a question about the rejection table, not about F18.
     * The answer is that it is not. The HTML Standard's tree construction discards a DOCTYPE token
     * that arrives in any mode after "initial" — it is a parse error and the token is ignored — so
     * every consuming parser renders the page with the first declaration and no consequence at all.
     * Refusing it made Canoe the only participant with an opinion, and the shape that produces it is
     * the most ordinary composition mistake a templating system has: a layout declares a DOCTYPE and
     * an included fragment declares one too.
     *
     * <p>The diagnostic survives the refusal, which is the whole of what the check was worth. It goes
     * to the logger at warn level, it names the coordinates, and it says what to do about it. That is
     * asserted here rather than assumed, because a warning nobody asserts is a warning that stops
     * being emitted the first time somebody reorders this branch — the same reasoning
     * {@code unknownAttributeName} carries for R5's suppression diagnostic.
     *
     * <p>{@code doctypeSeen} is kept, and it has to be: it is what makes the second declaration
     * detectable at all.
     */
    @Test
    public void theSecondDoctypeIsIgnoredWithAWarning() {
        assertFalse(CanoeTestSupport.write("<!DOCTYPE html><!DOCTYPE html>").isError());

        // The whole document renders, verbatim, and a reference after the second declaration lands
        // in its real context rather than being lost with the page.
        assertEquals("<!DOCTYPE html><!DOCTYPE html><p>&lt;b&gt;</p>",
                CanoeTestSupport.render("<!DOCTYPE html><!DOCTYPE html><p>$data</p>", "<b>")
                        .output(),
                "R20: the second declaration is passed through as the browser will see it - ignored"
                        + " by the tree builder, not removed from the byte stream - and the text sink"
                        + " after it escapes normally");

        List<String> warnings = warningsWhileWriting("<!DOCTYPE html><!DOCTYPE html>");
        assertEquals(1, warnings.size(),
                () -> "R20: exactly one warning, for the second declaration only: " + warnings);
        assertTrue(warnings.get(0).contains("duplicate DOCTYPE"),
                () -> "the warning must name what it saw: " + warnings.get(0));
        assertTrue(warnings.get(0).contains("line: 1") && warnings.get(0).contains("pos: 18"),
                () -> "...and where, which is the coordinate the rejection used to report: "
                        + warnings.get(0));

        // A single declaration is silent, which is what keeps the warning worth reading.
        assertEquals(List.of(), warningsWhileWriting("<!DOCTYPE html><html></html>"));
        assertEquals(List.of(), warningsWhileWriting("<!-- c --><!DOCTYPE html><html></html>"),
                "and a comment above it is not text: F18's shape stays silent as well as legal");
    }

    /**
     * R20's second DOCTYPE decision, in the other direction: text before the DOCTYPE was already
     * accepted and stays accepted, but the consequence a browser imposes is now discoverable.
     *
     * <p>The HTML Standard's "initial" insertion mode ignores whitespace and treats any other
     * character as a parse error that switches to "before html" — so by the time the declaration
     * arrives the document is already in <strong>quirks mode</strong> and the DOCTYPE the author
     * wrote does nothing. R18 accepted this deliberately (a rejection would have been a new way for
     * an ordinary page to fail) and R20 keeps that and closes the gap the other way: Canoe now says
     * what the browser is going to do.
     *
     * <p>Whitespace does not warn, and that assertion is the load-bearing one. A template whose first
     * line is a directive or a comment emits a newline above the DOCTYPE, which is both very common
     * and exactly what the standard ignores; a diagnostic that fired on it would be noise, and noise
     * is how the duplicate-DOCTYPE warning above would come to be ignored too.
     */
    @Test
    public void theQuirksModeConsequenceOfTextBeforeTheDoctypeIsWarnedAbout() {
        assertFalse(CanoeTestSupport.write("hello<!DOCTYPE html>").isError(),
                "accepted, exactly as before R20");

        List<String> warnings = warningsWhileWriting("hello<!DOCTYPE html>");
        assertEquals(1, warnings.size(), () -> "R20: one warning: " + warnings);
        assertTrue(warnings.get(0).contains("QUIRKS MODE"),
                () -> "the warning must name the consequence, which is the whole reason it exists"
                        + " - the declaration is accepted and does nothing: " + warnings.get(0));
        assertTrue(warnings.get(0).contains("line: 1") && warnings.get(0).contains("pos: 8"),
                () -> "...and where the declaration is: " + warnings.get(0));

        // Whitespace is not text. Neither is a comment, which F18 established.
        assertEquals(List.of(), warningsWhileWriting("\n  <!DOCTYPE html>"),
                "R20: leading whitespace is what the standard's initial mode ignores, and what a"
                        + " template whose first line is a directive emits");
        assertEquals(List.of(), warningsWhileWriting("<!-- licence -->\n<!DOCTYPE html>"),
                "a comment above the DOCTYPE is legal HTML and silent (F18/R18)");

        // Both diagnostics on one document, because the two conditions are independent.
        List<String> both =
                warningsWhileWriting("hello<!DOCTYPE html><!DOCTYPE html>");
        assertEquals(3, both.size(),
                () -> "the first declaration warns about the text; the second warns about both,"
                        + " because it is a duplicate AND still below text: " + both);
    }

    /**
     * Every warning Canoe logs while writing the given output, as the strings a developer would read.
     *
     * <p>Captured from {@code System.err}, which is where slf4j-simple — the binding this suite runs
     * with — sends everything. That is a coarse instrument and it is the honest one here: the
     * alternative is asserting that a field was set, and the field is not what a developer sees. The
     * capture is scoped to the write and restored in a finally, so a failure cannot swallow the rest
     * of the run's output.
     */
    private static List<String> warningsWhileWriting(String output) {
        java.io.PrintStream original = System.err;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        try {
            System.setErr(new java.io.PrintStream(captured, true, StandardCharsets.UTF_8));
            CanoeTestSupport.write(output);
        } finally {
            System.err.flush();
            System.setErr(original);
        }

        List<String> warnings = new ArrayList<>();
        for (String line : captured.toString(StandardCharsets.UTF_8).split("\n")) {
            if (line.contains("WARN") && line.contains("Canoe")) {
                warnings.add(line);
            }
        }
        return warnings;
    }

    /**
     * The DOCTYPE rule reaches the encoder, not only the error path: a reference after a comment and
     * a DOCTYPE renders in its real context now, where before the whole page was refused.
     */
    @Test
    public void aReferenceAfterACommentAndADoctypeRendersInItsContext() {
        assertEquals("<!-- c --><!DOCTYPE html><p>&lt;b&gt;</p>",
                CanoeTestSupport.render("<!-- c --><!DOCTYPE html><p>$data</p>", "<b>").output(),
                "R18: the text sink escapes the payload, which needs the page to render at all");

        assertEquals("<!-- c --><!DOCTYPE html><a href=\"/p/PAYLOAD\">x</a>",
                CanoeTestSupport.render("<!-- c --><!DOCTYPE html><a href=\"/p/$data\">x</a>",
                        "PAYLOAD").output(),
                "and the attribute contexts after it are unaffected by the DOCTYPE");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * A one-character string from a code unit. Keeps this source file pure ASCII: a raw NUL in a
     * Java source file makes git treat the file as binary, which has already happened once in this
     * suite.
     */
    private static String ch(int codeUnit) {
        return String.valueOf((char) codeUnit);
    }

    private static String repeat(char c, int count) {
        char[] chars = new char[count];
        java.util.Arrays.fill(chars, c);
        return new String(chars);
    }
}
