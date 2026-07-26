package com.webkreator.qlue.view.canoe;

import com.webkreator.qlue.view.Canoe;
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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The templates Canoe refuses to render, and what a real response looks like when it does.
 *
 * <p>Canoe is not only an encoder; it is a validating HTML tokenizer that throws on anything it does
 * not recognise. Fifteen call sites raise an encoding error, and none of the shapes that reach them
 * is attacker-controlled — they are all template-authoring hazards. That makes them availability
 * defects rather than vulnerabilities, and it is why they are gathered here rather than ledgered in
 * the corpus. Several are shapes a competent author would write without hesitating: {@code <br/>},
 * a literal {@code <} in prose, a custom element with a long name.
 *
 * <p>Each error is asserted with its exact message <em>and</em> its reported line and position. The
 * coordinates are the part most likely to rot silently, because nothing else in the system reads
 * them and they are the only diagnostic a developer gets.
 *
 * <p><strong>F13 is the point of this file.</strong> {@code VelocityViewFactory.render()} contains a
 * recovery branch meant to turn an encoding error into an {@code [Encoding Error]} marker in an
 * otherwise-served page. It tests {@code startsWith(Canoe.ERROR_PREFIX)} against the message of the
 * <em>top-level</em> exception, and Velocity always wraps Canoe's {@code IOException} — the
 * production {@code Template.merge()} path yields {@code "IO Error rendering template '...'"}, and
 * this harness's {@code evaluate()} path yields {@code "IO Error in writer: ..."}. Neither starts
 * with the prefix, so the branch has never run: every error below is an unhandled exception, which
 * in production means a 500 on top of a response that is already half written.
 * {@link #noErrorCanoeRaisesIsSwallowedInProduction} pins that for every input in the table,
 * and {@link #aRejectedTemplateLeavesAHalfWrittenResponse} pins what the client has already received.
 *
 * <p>That F13 test is the one file in this suite that does <em>not</em> use the fast harness. It
 * calls the real {@code VelocityViewFactory.render(page, view, writer)} through
 * {@link ProductionRenderProbe} and asserts on what escapes it, because a test that re-applied the
 * factory's own broken predicate to an exception the harness produced would keep passing after the
 * factory was fixed — and a {@code KNOWN_VULNERABLE} pin that survives the fix is worse than no pin
 * at all.
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
                        "DOCTYPE declaration must be at the beginning", 1, 9),
                rejected("bang that is neither comment nor doctype", "<!x>", "Invalid tag", 1, 3),
                rejected("CDATA section", "<p><![CDATA[x]]></p>", "Invalid tag", 1, 6),

                // DOCTYPE_TEST.
                rejected("misspelt DOCTYPE", "<!DXCTYPE html>", "Invalid DOCTYPE declaration", 1, 4),

                // COMMENT_OPEN_2.
                rejected("single dash after a bang", "<!-x-->", "Invalid tag", 1, 4),

                // TAG_NAME.
                rejected("tag name of 36 characters", "<" + repeat('a', 36) + ">",
                        "Tag name too long", 1, 37),
                rejected("tag name of 37 characters", "<" + repeat('a', 37) + ">",
                        "Tag name too long", 1, 37),
                rejected("empty tag", "<>", "Tag name too short", 1, 2),
                rejected("empty closing tag", "</>", "Tag name too short", 1, 3),
                rejected("space after a closing slash", "</ p>", "Tag name too short", 1, 3),
                rejected("literal < in body text", "<p>5 < 6</p>", "Tag name too short", 1, 7),
                rejected("XML prolog", "<?xml version=\"1.0\"?>", "Tag name too short", 1, 2),
                rejected("XHTML-style void element", "<br/>",
                        "Invalid character after tag name", 1, 4),

                // TAG_EMPTY_ENDING.
                rejected("something other than > after /", "<img src=\"a\" /x>",
                        "Expected '>' after '/' in tag.", 1, 15),

                // TAG: a character that cannot start an attribute name.
                rejected("quote where an attribute name should be", "<p \"x\">",
                        "Invalid character in attribute name", 1, 4),

                // TAG_ATTR_NAME.
                rejected("attribute name of 36 characters", "<p " + repeat('a', 36) + "=\"1\">",
                        "Attribute name too long", 1, 39),
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
                Arguments.of("void element with a space", "<br />"),
                Arguments.of("slash after a quoted value", "<img src=\"a.png\"/>"),
                Arguments.of("slash after an unquoted value", "<img src=a.png />"),
                Arguments.of("tag name of 35 characters", "<" + repeat('a', 35) + ">"),
                Arguments.of("attribute name of 35 characters", "<p " + repeat('a', 35) + "=\"1\">"),
                Arguments.of("DOCTYPE first", "<!DOCTYPE html><html></html>"),
                Arguments.of("DOCTYPE in lower case", "<!doctype html><html></html>"),
                Arguments.of("legacy DOCTYPE with a public identifier",
                        "<!DOCTYPE html PUBLIC \"-//W3C//DTD HTML 4.01//EN\">"),
                Arguments.of("DOCTYPE after leading text", "hello<!DOCTYPE html>"),
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
     */
    @Test
    public void theTwoInvalidCharacterAfterTagNameSitesAreBothReached() throws IOException {
        assertEquals(Canoe.TAG_NAME, stateBefore("<br"));
        assertEquals(expectedMessage("Invalid character after tag name", 1, 4),
                CanoeTestSupport.write("<br/>").errorMessage());

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
     * {@code MAX_TAGNAME_LEN} is 36 and it is the length of the buffer, not the limit on a name: a
     * name is rejected once it would fill the last slot, so 35 characters is the longest that
     * renders. Both names and attribute names share the constant and the buffer.
     *
     * <p>35 is short for a custom element. {@code <data-widget-configuration-attribute>} is 35 and
     * renders; one more character and the page is a 500.
     */
    @Test
    public void theNameLengthLimitIsOneLessThanTheBufferLength() {
        assertEquals(36, Canoe.MAX_TAGNAME_LEN);

        for (int length = 1; length <= 35; length++) {
            assertFalse(CanoeTestSupport.write("<" + repeat('a', length) + ">").isError(),
                    "a tag name of " + length + " characters must render");
            assertFalse(CanoeTestSupport.write("<p " + repeat('a', length) + "=\"1\">").isError(),
                    "an attribute name of " + length + " characters must render");
        }

        assertEquals(expectedMessage("Tag name too long", 1, 37),
                CanoeTestSupport.write("<" + repeat('a', 36) + ">").errorMessage());
        assertEquals(expectedMessage("Attribute name too long", 1, 39),
                CanoeTestSupport.write("<p " + repeat('a', 36) + "=\"1\">").errorMessage());

        assertFalse(CanoeTestSupport.write("<data-widget-configuration-attribute>").isError(),
                "35 characters, the longest custom element name Canoe will render");
        assertTrue(CanoeTestSupport.write("<data-widget-configuration-attributes>").isError(),
                "36, and the page is gone");
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
        assertEquals(expectedMessage("Invalid character after tag name", 1, 4),
                CanoeTestSupport.write("<br/>").errorMessage());
        assertEquals(expectedMessage("Invalid character after tag name", 2, 4),
                CanoeTestSupport.write("<p>\n<br/>").errorMessage());
        assertEquals(expectedMessage("Invalid character after tag name", 4, 4),
                CanoeTestSupport.write("<p>\n</p>\n<p>\n<br/>").errorMessage());
        assertEquals(expectedMessage("Tag name too short", 2, 16),
                CanoeTestSupport.write("line one\nline two <p>5 < 6").errorMessage());

        // A CR does not start a new line; only LF does, so a CRLF file reports the same coordinates
        // as an LF one.
        assertEquals(expectedMessage("Invalid character after tag name", 2, 4),
                CanoeTestSupport.write("<p>\r\n<br/>").errorMessage());
    }

    // ------------------------------------------------------------------
    // F13 - the recovery branch that never runs
    // ------------------------------------------------------------------

    /**
     * F13. For every error Canoe can raise, {@code VelocityViewFactory.render()}'s recovery branch
     * does not fire — asserted by calling that method and watching the exception come back out.
     *
     * <p>The branch reads {@code e.getMessage().startsWith(Canoe.ERROR_PREFIX)} on the exception it
     * caught, and the exception it catches is never Canoe's. Velocity wraps the {@code IOException}
     * in a {@code VelocityException} whose message begins {@code "IO Error"} — {@code "IO Error
     * rendering template '...'"} on the production {@code Template.merge()} path. The test fails, the
     * {@code else} branch rethrows, and the request becomes a 500 on top of a half-written response.
     *
     * <p><strong>Why this goes through {@link ProductionRenderProbe} rather than the fast harness.</strong>
     * The obvious way to write this test is to re-apply {@code startsWith(Canoe.ERROR_PREFIX)} to the
     * exception the harness produced and assert it is false. That is a copy of the check under test:
     * fixing F13 — by matching on the cause chain, or by catching a typed
     * {@code CanoeEncodingException} — changes {@code render()} and leaves the copy answering exactly
     * as before, so the test stays green and the ledger rule in {@code PLAN.md} §2.1 (a
     * {@code KNOWN_VULNERABLE} pin must fail loudly when the vulnerability disappears) is violated
     * silently. This version calls the real {@code render()} and asserts on the two things a caller
     * can actually see: an exception escaped, and no {@code [Encoding Error]} reached the response.
     * Both flip the moment the recovery branch starts working.
     *
     * <p>It also covers the path the rest of the suite cannot. Everything else here renders through
     * {@code VelocityEngine.evaluate()}, whose wrapper message is {@code "IO Error in writer: ..."};
     * production uses {@code Template.merge()}, whose message is {@code "IO Error rendering template
     * '...'"}. Only the second is the string the unreachable branch is actually tested against, and
     * only this test sees it.
     *
     * <p>Asserted over the whole rejection table rather than for one example, because "the branch is
     * unreachable" is a claim about all errors and a single case would only show that one of them
     * escapes.
     */
    @Test
    public void noErrorCanoeRaisesIsSwallowedInProduction() {
        for (Arguments row : (Iterable<Arguments>) rejections()::iterator) {
            String description = (String) row.get()[0];
            String input = (String) row.get()[1];

            ProductionRenderProbe.Outcome outcome = ProductionRenderProbe.render(input);

            assertTrue(outcome.exceptionEscaped(),
                    "F13: " + description + " must escape VelocityViewFactory.render() as an"
                            + " unhandled exception; if it no longer does, F13 has been fixed and"
                            + " this test is the notice. Was: " + outcome);
            assertFalse(outcome.recoveryBranchRan(),
                    "F13: " + description + " must not produce an [Encoding Error] marker, because"
                            + " the branch that appends it cannot run. Was: " + outcome);

            assertTrue(outcome.escaped().getMessage().startsWith("IO Error rendering template"),
                    "F13: the production wrapper message is what the branch actually tests, and it"
                            + " begins \"IO Error rendering template\", not "
                            + CanoeTestSupport.quote(Canoe.ERROR_PREFIX) + " - was: "
                            + CanoeTestSupport.quote(outcome.escaped().getMessage()));
        }
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
     * The other half of F13, and the half that matters to whoever is looking at the response: the
     * bytes already written.
     *
     * <p>{@code Canoe.write} flushes everything up to the offending character before rethrowing, so
     * the client has already received a prefix of the page. For an error inside a tag that prefix
     * ends mid-element — an unterminated {@code <img}, with the browser still waiting for the
     * {@code >}. Appending {@code [Encoding Error]} to that, which is what the unreachable branch
     * intends, would put the marker inside an attribute list rather than in the document; the review
     * notes that the recovery is a poor one even if it were reachable, and this is why.
     */
    @Test
    public void aRejectedTemplateLeavesAHalfWrittenResponse() {
        CanoeTestSupport.RenderResult inBody = CanoeTestSupport.render("<p>ok</p><br/>");
        assertEquals("<p>ok</p><br", inBody.output(),
                "the offending '/' is withheld, everything before it is already sent");

        CanoeTestSupport.RenderResult inTag =
                CanoeTestSupport.render("<div class=\"a\"><img src=\"$data\" /y>", "value");
        assertEquals("<div class=\"a\"><img src=\"value\" /", inTag.output(),
                "F13: the response ends inside the img element, with an unmatched '<'");
        assertTrue(inTag.output().lastIndexOf('<') > inTag.output().lastIndexOf('>'),
                "the last '<' is unclosed, so appending anything appends it to an attribute list");

        // A reference that was rendered before the error still reaches the client, correctly
        // encoded. The failure is not atomic: there is no rollback.
        CanoeTestSupport.RenderResult afterAReference =
                CanoeTestSupport.render("<p>$data</p><br/>", "<b>");
        assertEquals("<p>&lt;b&gt;</p><br", afterAReference.output());
    }

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
    // F18 - the DOCTYPE that a comment makes illegal
    // ------------------------------------------------------------------

    /**
     * F18. {@code tagCount} is incremented for every {@code <} seen in {@code HTML} state, comments
     * included, and the DOCTYPE check demands {@code tagCount == 1}. So a comment before the DOCTYPE
     * — a licence header, an editor marker, a conditional comment — makes the DOCTYPE illegal and
     * takes the page down.
     *
     * <p>Leading text or whitespace is fine, because neither contains a {@code <}. It is specifically
     * markup before the DOCTYPE that fails, and a comment is the only markup that is legal there.
     */
    @Test
    public void aCommentBeforeTheDoctypeMakesTheDoctypeIllegal() {
        assertFalse(CanoeTestSupport.write("<!DOCTYPE html><html></html>").isError());
        assertFalse(CanoeTestSupport.write("\n  <!DOCTYPE html><html></html>").isError(),
                "leading whitespace contains no '<', so tagCount is still 1");

        assertEquals(expectedMessage("DOCTYPE declaration must be at the beginning", 1, 13),
                CanoeTestSupport.write("<!-- c --><!DOCTYPE html><html></html>").errorMessage(),
                "F18: a comment above the DOCTYPE is legal HTML and Canoe rejects it");
        assertEquals(expectedMessage("DOCTYPE declaration must be at the beginning", 1, 9),
                CanoeTestSupport.write("<html><!DOCTYPE html>").errorMessage(),
                "the case the check was written for, which is a genuine error");
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
