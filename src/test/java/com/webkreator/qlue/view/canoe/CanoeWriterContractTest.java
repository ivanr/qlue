package com.webkreator.qlue.view.canoe;

import com.webkreator.qlue.view.Canoe;
import com.webkreator.qlue.view.CanoeStateProbe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Canoe} is a public {@link Writer} with no documented restriction on how it may be called,
 * so every inherited entry point is part of its contract whether or not Velocity happens to use it.
 *
 * <p>F9 lives here. {@code write(char[], int, int)} iterates {@code for (i = offset; i < len; i++)}
 * where the bound should be {@code offset + len}, and then writes the full requested range to the
 * underlying writer regardless. At {@code offset == 0} the two agree, which is why nothing has ever
 * noticed; at any other offset characters reach the response without passing through the state
 * machine, and the parser's idea of the current context goes stale.
 *
 * <p>These characterise F9 directly rather than through the corpus. There is deliberately no corpus
 * entry for F9: a case is a template rendered through Velocity, and no template can reach a
 * three-argument {@code write}, so the ledger has nothing to say about it.
 *
 * <p>F9 is <strong>latent</strong>: every
 * inherited {@code Writer} default funnels to {@code write(cbuf, 0, n)}, and Velocity's render path
 * uses only the one-argument forms, so nothing reaches the bug today. It is one buffering wrapper,
 * one {@code org.apache.velocity.io.Filter}, or one Velocity upgrade away from being live — and a
 * parser whose safety depends on nobody calling a standard method with a non-zero offset is not
 * safe, it is lucky.
 */
public class CanoeWriterContractTest {

    // ------------------------------------------------------------------
    // Entry points that are safe because they cannot express an offset
    // ------------------------------------------------------------------

    /**
     * Canoe is a faithful pass-through: whatever range the caller asked to be written reaches the
     * underlying writer byte for byte, through every entry point.
     *
     * <p>This says nothing about the parser, and it is worth being explicit about that, because it
     * looks as though it should. {@code Canoe.write} always ends with
     * {@code writer.write(cbuff, offset, len)} on the full requested range, whatever the state
     * machine did or did not see on the way — that is precisely the half of F9 that lets unparsed
     * characters reach the response. So a change to how the inherited methods delegate would not show
     * up here at all; {@link #everyOffsetFreeEntryPointLeavesTheSameState} is the test that would see
     * it, because it asks what the parser consumed rather than what the writer emitted.
     */
    @Test
    public void everyEntryPointPassesTheRequestedRangeThroughUnchanged() throws IOException {
        String document = "<p>text</p><a href=\"/x\">link</a>";

        assertEquals(document, viaWriteString(document));
        assertEquals(document, viaWriteCharArray(document));
        assertEquals(document, viaWriteStringRange(document));
        assertEquals(document, viaAppendCharSequence(document));
        assertEquals(document, viaAppendCharSequenceRange(document));
        assertEquals(document, viaWriteIntPerCharacter(document));
        assertEquals(document, viaAppendCharPerCharacter(document));
    }

    /**
     * And all of them leave the parser in the same state, not merely the same output. This is the
     * one that does real work: every inherited convenience method is documented to funnel into
     * {@code write(cbuf, 0, n)}, and if a future JDK or an override changed that delegation to a
     * non-zero offset, F9 would turn that method into an unparsed write and this test would say so.
     */
    @Test
    public void everyOffsetFreeEntryPointLeavesTheSameState() throws IOException {
        String openTag = "<a href=\"";

        for (Entry entry : Entry.values()) {
            assertEquals(Canoe.CTX_URI, contextVia(openTag, entry),
                    () -> "entry point " + entry + " left the parser somewhere other than inside a"
                            + " URI attribute value, so it did not deliver every character to the"
                            + " state machine");
        }
    }

    // ------------------------------------------------------------------
    // F9: the offset bug
    // ------------------------------------------------------------------

    /**
     * F9, stated plainly. Three characters of a fifteen-character array reach the writer without
     * ever entering the state machine, and the parser is left mid-tag when the document is closed.
     *
     * <p>F9. When the loop bound is fixed to {@code i < offset + len}, this test fails — update it
     * then, and say so.
     */
    @Test
    public void writeWithANonZeroOffsetSkipsTheTailOfTheRange() throws IOException {
        char[] buffer = "XXX<b>hello</b>".toCharArray();
        CanoeStateProbe probe = new CanoeStateProbe();
        probe.feed(buffer, 3, 12);

        assertEquals("<b>hello</b>", probe.output(),
                "the full requested range reaches the underlying writer");
        assertEquals(Canoe.TAG_NAME, probe.state(),
                "F9: the loop ran to index 11, so only the first 9 of 12 characters were parsed"
                        + " and the parser stopped on the '<' of the closing tag");
        assertEquals(Canoe.CTX_SUPPRESS, probe.currentContext(),
                "F9: three characters reached the response unparsed and the context is now wrong");

        // The same characters written at offset 0 end where they should.
        CanoeStateProbe reference = new CanoeStateProbe().feed("<b>hello</b>");
        assertEquals(Canoe.HTML, reference.state());
        assertEquals(Canoe.CTX_HTML, reference.currentContext());
    }

    /**
     * The degenerate case, and the dangerous one: when {@code offset >= len} the loop body never
     * runs. Every character is written to the response, none is parsed, and the state machine
     * freezes — so every subsequent reference on the page is encoded for a stale context.
     */
    @Test
    public void writeWithOffsetAtOrPastTheLengthParsesNothing() throws IOException {
        char[] buffer = "XXXXXXXX<script>".toCharArray();
        CanoeStateProbe probe = new CanoeStateProbe();
        probe.feed(buffer, 8, 8);

        assertEquals("<script>", probe.output(), "the requested range still reaches the writer");
        assertEquals(Canoe.HTML, probe.state(),
                "F9: offset >= len, so the loop never ran and the parser never saw the script tag");
        assertEquals(Canoe.CTX_HTML, probe.currentContext(),
                "F9: Canoe believes it is in body text while the browser is inside a script element."
                        + " Every reference from here on is encoded with htmlWhite() and written"
                        + " straight into script source.");
    }

    /**
     * And {@code offset > len} behaves no differently, which is worth pinning separately: an
     * implementation that had merely mixed up its two arguments would throw
     * {@link IndexOutOfBoundsException} somewhere in here, and the fact that nothing does is what
     * makes the failure silent. The whole range is emitted, none of it is parsed, and the caller gets
     * no signal at all.
     */
    @Test
    public void writeWithOffsetGreaterThanTheLengthAlsoParsesNothing() {
        char[] buffer = "XXXXXXXXXX<script>".toCharArray();
        CanoeStateProbe probe = new CanoeStateProbe();

        assertDoesNotThrow(() -> probe.feed(buffer, 10, 8),
                "F9: offset > len is not rejected, so the desynchronisation is completely silent");

        assertEquals("<script>", probe.output(), "the requested range still reaches the writer");
        assertEquals(Canoe.HTML, probe.state(),
                "F9: the loop bound was already behind the start index, so nothing was parsed");
    }

    /**
     * F9 can suppress an encoding error outright. With the range {@code (offset 2, len 14)} the loop
     * runs over buffer indices 2 to 13, which is the twelve characters {@code <p>ok</p><br} — so
     * {@code <br} <em>is</em> parsed and only the trailing {@code />} falls past the bound. That is
     * enough: the {@code /} is the character Canoe rejects, it is never seen, no error is raised, and
     * the whole malformed range is then written to the response.
     */
    @Test
    public void aNonZeroOffsetCanHideMarkupThatWouldOtherwiseBeRejected() {
        String document = "<p>ok</p><br/>";

        // At offset 0 the '/' is parsed and rejected.
        assertTrue(CanoeTestSupport.write(document).isError());

        // At offset 2 it is never parsed, so no error is raised and everything is written.
        CanoeStateProbe probe = new CanoeStateProbe();
        assertDoesNotThrow(() -> probe.feed(("XX" + document).toCharArray(), 2, document.length()),
                "F9: the encoding error that offset 0 raises has been suppressed entirely");

        assertEquals(document, probe.output(),
                "F9: the rejected markup reached the response intact");
        assertEquals(Canoe.TAG_NAME, probe.state(),
                "the parser stopped inside the <br tag name, having never reached the '/'");
    }

    /**
     * How many characters escape the parser, as a function of the offset. The count is exactly the
     * offset, for any offset up to the length — which is the clearest statement of the bug.
     *
     * <p>The document is chosen so that <em>every</em> prefix of it leaves a distinct parser state,
     * which is the whole point and is easy to get wrong. An earlier version of this test used
     * {@code "<p>0123456789</p>"}, which has only two distinct states across its eighteen prefixes:
     * for offsets 0, 5 and 8 the assertion passed even under a <em>corrected</em> {@code write()} that
     * parsed the whole range, so three of the six rows were blind to the bug they are named after.
     * {@code "<a b='1' c='2' d>"} moves through tag name, attribute name, quoted value and back on
     * almost every character, and the full state tuple is asserted rather than {@code state} alone.
     *
     * <p>Verified by patching {@code Canoe.write} to the corrected bound {@code i < offset + len} and
     * confirming this test then fails for every non-zero offset, which is the property the javadoc
     * above promises.
     */
    @ParameterizedTest(name = "offset {0}")
    @ValueSource(ints = {0, 1, 2, 3, 5, 8})
    public void theNumberOfUnparsedCharactersEqualsTheOffset(int offset) throws IOException {
        String document = "<a b='1' c='2' d>";
        char[] buffer = (repeat('X', offset) + document).toCharArray();

        CanoeStateProbe probe = new CanoeStateProbe();
        probe.feed(buffer, offset, document.length());

        assertEquals(document, probe.output(), "output is correct regardless; only parsing is not");

        int parsed = Math.max(0, document.length() - offset);
        CanoeStateProbe reference = new CanoeStateProbe();
        reference.feed(document.substring(0, parsed));

        assertEquals(signature(reference), signature(probe),
                "F9: the parser sees only the first " + parsed + " of " + document.length()
                        + " characters when the offset is " + offset + ", so it ends up in the state"
                        + " that prefix produces rather than the one the full range would");
    }

    /**
     * Everything the parser carries forward, as one comparable value. {@code state} alone is not
     * enough: many characters leave it unchanged, so a test that asserted only on it would silently
     * accept a parser that had consumed a different number of characters.
     */
    private static String signature(CanoeStateProbe probe) {
        return CanoeStateProbe.stateName(probe.state())
                + "/next=" + CanoeStateProbe.stateName(probe.nextState())
                + "/attr=" + CanoeStateProbe.attributeContextName(probe.attributeContext())
                + "/quotes=" + probe.attrQuotes()
                + "/bufLen=" + probe.bufLen();
    }

    /**
     * The error path has the same defect in mirror image. {@code len - (len - i)} simplifies to
     * {@code i}, an absolute index passed where a length is expected, so the partial output written
     * on failure is the wrong size for any non-zero offset.
     */
    @Test
    public void theErrorPathWritesTheWrongAmountOfPartialOutput() {
        // "<br/>" is rejected: a '/' immediately after a tag name is not allowed. At offset 1 the
        // '/' does fall inside the truncated loop bound, so the error path runs.
        String document = "<p>ok</p><br/>";
        char[] buffer = ("X" + document).toCharArray();
        CanoeStateProbe probe = new CanoeStateProbe();

        IOException error = assertThrows(IOException.class,
                () -> probe.feed(buffer, 1, document.length()));
        assertTrue(error.getMessage().startsWith(Canoe.ERROR_PREFIX), error.getMessage());

        // len - (len - i) simplifies to i, an absolute array index passed where a length is
        // expected. The partial output therefore includes the very character that was rejected.
        assertEquals("<p>ok</p><br/", probe.output(),
                "F9: the error path emitted the offending '/' as well as the good prefix."
                        + " A correct implementation would write \"<p>ok</p><br\".");

        // At offset 0 the same arithmetic is accidentally right, which is why nothing has noticed.
        CanoeStateProbe atZero = new CanoeStateProbe();
        assertThrows(IOException.class, () -> atZero.feed(document.toCharArray(), 0,
                document.length()));
        assertEquals("<p>ok</p><br", atZero.output(),
                "at offset 0 the good prefix is exactly right");
    }

    // ------------------------------------------------------------------
    // General Writer contract
    // ------------------------------------------------------------------

    @Test
    public void zeroLengthWritesAreNoOps() throws IOException {
        CanoeStateProbe probe = new CanoeStateProbe().feed("<a href=\"");
        int stateBefore = probe.state();

        probe.feed(new char[0], 0, 0);
        probe.feed("");

        assertEquals(stateBefore, probe.state());
        assertEquals("<a href=\"", probe.output());
    }

    @Test
    public void flushAndCloseReachTheUnderlyingWriter() throws IOException {
        RecordingWriter underlying = new RecordingWriter();
        Canoe canoe = new Canoe(underlying);

        canoe.write("<p>x</p>");
        canoe.flush();
        assertTrue(underlying.flushed, "flush must reach the underlying writer");

        canoe.close();
        assertTrue(underlying.closed, "close must reach the underlying writer");
    }

    /**
     * A document split across many writes must parse identically to the same document written in
     * one go. {@code ChunkInvarianceTest} (T21) states this as a property over the whole corpus;
     * here it is pinned for the one case the contract makes most likely to break — a split in the
     * middle of a tag name.
     */
    @Test
    public void splittingAWriteInsideATagDoesNotChangeTheOutcome() throws IOException {
        String document = "<a href=\"/x\">link</a>";

        CanoeStateProbe whole = new CanoeStateProbe().feed(document);
        CanoeStateProbe split = new CanoeStateProbe();
        for (int i = 0; i < document.length(); i++) {
            split.feed(document.substring(i, i + 1));
        }

        assertEquals(whole.output(), split.output());
        assertEquals(whole.state(), split.state());
        assertEquals(whole.currentContext(), split.currentContext());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Every inherited {@link Writer} entry point that cannot express a non-zero offset. */
    private enum Entry {
        WRITE_STRING, WRITE_CHARS, WRITE_STRING_RANGE, APPEND, APPEND_RANGE, APPEND_CHAR, WRITE_INT
    }

    private static int contextVia(String text, Entry entry) throws IOException {
        StringWriter sink = new StringWriter();
        Canoe canoe = new Canoe(sink);
        switch (entry) {
            case WRITE_STRING:
                canoe.write(text);
                break;
            case WRITE_CHARS:
                canoe.write(text.toCharArray());
                break;
            case WRITE_STRING_RANGE:
                canoe.write(text, 0, text.length());
                break;
            case APPEND:
                canoe.append(text);
                break;
            case APPEND_RANGE:
                canoe.append(text, 0, text.length());
                break;
            case APPEND_CHAR:
                for (int i = 0; i < text.length(); i++) {
                    canoe.append(text.charAt(i));
                }
                break;
            case WRITE_INT:
                for (int i = 0; i < text.length(); i++) {
                    canoe.write(text.charAt(i));
                }
                break;
        }
        return canoe.currentContext();
    }

    private static String viaWriteString(String text) throws IOException {
        StringWriter sink = new StringWriter();
        Canoe canoe = new Canoe(sink);
        canoe.write(text);
        return sink.toString();
    }

    private static String viaWriteCharArray(String text) throws IOException {
        StringWriter sink = new StringWriter();
        Canoe canoe = new Canoe(sink);
        canoe.write(text.toCharArray());
        return sink.toString();
    }

    private static String viaWriteStringRange(String text) throws IOException {
        StringWriter sink = new StringWriter();
        Canoe canoe = new Canoe(sink);
        canoe.write(text, 0, text.length());
        return sink.toString();
    }

    private static String viaAppendCharSequence(String text) throws IOException {
        StringWriter sink = new StringWriter();
        Canoe canoe = new Canoe(sink);
        canoe.append(text);
        return sink.toString();
    }

    private static String viaAppendCharSequenceRange(String text) throws IOException {
        StringWriter sink = new StringWriter();
        Canoe canoe = new Canoe(sink);
        canoe.append(text, 0, text.length());
        return sink.toString();
    }

    private static String viaWriteIntPerCharacter(String text) throws IOException {
        StringWriter sink = new StringWriter();
        Canoe canoe = new Canoe(sink);
        for (int i = 0; i < text.length(); i++) {
            canoe.write(text.charAt(i));
        }
        return sink.toString();
    }

    private static String viaAppendCharPerCharacter(String text) throws IOException {
        StringWriter sink = new StringWriter();
        Canoe canoe = new Canoe(sink);
        for (int i = 0; i < text.length(); i++) {
            canoe.append(text.charAt(i));
        }
        return sink.toString();
    }

    private static String repeat(char c, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    /** Records whether flush and close were propagated. */
    private static final class RecordingWriter extends Writer {

        private final StringBuilder written = new StringBuilder();
        boolean flushed;
        boolean closed;

        @Override
        public void write(char[] cbuf, int off, int len) {
            written.append(cbuf, off, len);
        }

        @Override
        public void flush() {
            flushed = true;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
