package com.webkreator.qlue.view.canoe;

import com.webkreator.qlue.view.Canoe;
import com.webkreator.qlue.view.CanoeStateProbe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Canoe} is a public {@link Writer} with no documented restriction on how it may be called,
 * so every inherited entry point is part of its contract whether or not Velocity happens to use it.
 *
 * <p>F9 lived here, and R15 fixed it. {@code write(char[], int, int)} used to iterate
 * {@code for (i = offset; i < len; i++)} where the bound must be {@code offset + len}, and then wrote
 * the full requested range to the underlying writer regardless. At {@code offset == 0} the two agree,
 * which is why nothing ever noticed; at any other offset characters reached the response without
 * passing through the state machine — exactly {@code offset} of them — and the parser's idea of the
 * current context went stale. The error path had the mirror defect: {@code len - (len - i)} is
 * {@code i}, an absolute index handed back as a length. R15 corrected the bound to
 * {@code i < offset + len} and the error-path length to {@code i - offset}.
 *
 * <p>These tests are the regression net for that fix, inverted from the assertions that used to pin
 * the bug: every one now asserts the offset entry point parses <em>exactly</em> the requested range
 * {@code [offset, offset + len)}, reaching the same state and context as feeding the same characters
 * through {@code write(String)}. Each carries its former, bug-pinning assertion in this javadoc so the
 * mechanism it guards is not lost.
 *
 * <p>These characterise F9 directly rather than through the corpus. There is deliberately no corpus
 * entry for F9: a case is a template rendered through Velocity, and no template can reach a
 * three-argument {@code write} at a non-zero offset, so the ledger has nothing to say about it.
 *
 * <p>F9 was <strong>latent</strong>: every inherited {@code Writer} default funnels to
 * {@code write(cbuf, 0, n)}, and Velocity's render path uses only the one-argument forms, so nothing
 * reached the bug — but {@code Canoe} is a public {@code Writer} with no documented restriction, one
 * buffering wrapper, one {@code org.apache.velocity.io.Filter}, or one Velocity upgrade away from a
 * non-zero offset. A parser whose safety depended on nobody calling a standard method that way was
 * not safe, it was lucky; R15 removed the luck.
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
    // R15: the offset entry point, now correct
    // ------------------------------------------------------------------

    /**
     * A non-zero offset now parses exactly the requested range and nothing else.
     *
     * <p>Inverted from {@code writeWithANonZeroOffsetSkipsTheTailOfTheRange}, which asserted the
     * range {@code (buffer, 3, 12)} over {@code "XXX<b>hello</b>"} left the parser in {@code TAG_NAME}
     * with a {@code CTX_SUPPRESS} context — three characters skipped by the truncated bound. With the
     * bound corrected to {@code i < offset + len} the parser sees all twelve characters of
     * {@code "<b>hello</b>"}, so it ends exactly where {@code write("<b>hello</b>")} does: back in
     * {@code HTML} after the closing tag, with a {@code CTX_HTML} context.
     */
    @Test
    public void writeWithANonZeroOffsetParsesExactlyTheRequestedRange() throws IOException {
        char[] buffer = "XXX<b>hello</b>".toCharArray();
        CanoeStateProbe probe = new CanoeStateProbe();
        probe.feed(buffer, 3, 12);

        assertEquals("<b>hello</b>", probe.output(),
                "the full requested range reaches the underlying writer");

        // The same characters written at offset 0 are the reference the range must match.
        CanoeStateProbe reference = new CanoeStateProbe().feed("<b>hello</b>");
        assertEquals(reference.state(), probe.state(),
                "R15: every character of the range is now parsed, so the offset write ends in the"
                        + " same state as the offset-0 write of the same characters");
        assertEquals(reference.currentContext(), probe.currentContext(),
                "R15: ...and in the same context; the machine no longer stalls three characters short");
        assertEquals(Canoe.HTML, probe.state());
        assertEquals(Canoe.CTX_HTML, probe.currentContext());
    }

    /**
     * {@code offset == len} is an ordinary call once the bound is right: it writes {@code len}
     * characters starting at {@code offset}, and parses every one of them.
     *
     * <p>Inverted from {@code writeWithOffsetAtOrPastTheLengthParsesNothing}, which read this as the
     * degenerate case where the old {@code i < len} bound (already behind {@code offset}) skipped the
     * loop body entirely: the script tag reached the response unparsed and Canoe believed it was still
     * in body text. {@code len} is a count, not an end index, so {@code (buffer, 8, 8)} over
     * {@code "XXXXXXXX<script>"} is the range {@code [8, 16)} — the eight characters {@code "<script>"}
     * — and the fixed bound parses all of them, entering the {@code SCRIPT} state exactly as
     * {@code write("<script>")} does.
     */
    @Test
    public void writeWithOffsetEqualToLenParsesTheWholeRange() throws IOException {
        char[] buffer = "XXXXXXXX<script>".toCharArray();
        CanoeStateProbe probe = new CanoeStateProbe();
        probe.feed(buffer, 8, 8);

        assertEquals("<script>", probe.output(), "the requested range reaches the writer");

        CanoeStateProbe reference = new CanoeStateProbe().feed("<script>");
        assertEquals(reference.state(), probe.state(),
                "R15: the script tag is parsed, so the parser is inside the script element, not"
                        + " still in body text");
        assertEquals(reference.currentContext(), probe.currentContext());
        assertEquals(Canoe.SCRIPT, probe.state());
    }

    /**
     * {@code offset} larger than {@code len} is likewise ordinary now, because the two are
     * independent: it writes {@code len} characters starting at {@code offset}.
     *
     * <p>Inverted from {@code writeWithOffsetGreaterThanTheLengthAlsoParsesNothing}, which pinned that
     * {@code offset > len} was silently a no-op under the old bound — nothing thrown, nothing parsed.
     * {@code (buffer, 10, 8)} over {@code "XXXXXXXXXX<script>"} is the range {@code [10, 18)}, the eight
     * characters {@code "<script>"}, and the corrected bound parses them into the {@code SCRIPT} state.
     */
    @Test
    public void writeWithOffsetGreaterThanLenParsesTheWholeRange() throws IOException {
        char[] buffer = "XXXXXXXXXX<script>".toCharArray();
        CanoeStateProbe probe = new CanoeStateProbe();
        probe.feed(buffer, 10, 8);

        assertEquals("<script>", probe.output(), "the requested range reaches the writer");

        CanoeStateProbe reference = new CanoeStateProbe().feed("<script>");
        assertEquals(reference.state(), probe.state(),
                "R15: offset and len are independent now, so the range [10,18) parses like the"
                        + " eight-character string it is");
        assertEquals(Canoe.SCRIPT, probe.state());
    }

    /**
     * Markup that offset 0 rejects is now rejected at every offset too, because every offset parses
     * the whole range.
     *
     * <p>Inverted from {@code aNonZeroOffsetCanHideMarkupThatWouldOtherwiseBeRejected}, which showed
     * the {@code (offset 2, len 14)} range over {@code "XX<p>ok</p>5 < 6"} stopped short of the
     * character that Canoe rejects, so the encoding error offset 0 raises was suppressed and the
     * malformed markup reached the response. With the corrected bound the range is {@code [2, 16)} —
     * the whole of {@code "<p>ok</p>5 < 6"} — every character is parsed, and the same error fires.
     */
    @Test
    public void aNonZeroOffsetNoLongerHidesRejectedMarkup() {
        String document = "<p>ok</p>5 < 6";

        // At offset 0 the whole range is parsed and rejected.
        assertTrue(CanoeTestSupport.write(document).isError());

        // At offset 2 it is now parsed as well, so the same error is raised.
        CanoeStateProbe probe = new CanoeStateProbe();
        assertThrows(IOException.class,
                () -> probe.feed(("XX" + document).toCharArray(), 2, document.length()),
                "R15: the encoding error offset 0 raises is no longer suppressed by the offset");
    }

    /**
     * No character escapes the parser now, at any offset: the whole requested range is parsed, so an
     * offset write ends exactly where the offset-0 write of the same range does.
     *
     * <p>Inverted from {@code theNumberOfUnparsedCharactersEqualsTheOffset}, which measured that the
     * count of skipped characters was exactly the offset. The document is still chosen so that every
     * prefix of it leaves a distinct parser state — {@code "<a b='1' c='2' d>"} moves through tag
     * name, attribute name, quoted value and back on almost every character — because a document with
     * few distinct states would let a still-broken bound pass by coincidence for some offsets. The
     * full state tuple is asserted rather than {@code state} alone, and it is compared against the
     * <em>whole</em> document rather than a prefix, since no offset truncates the parse any more.
     */
    @ParameterizedTest(name = "offset {0}")
    @ValueSource(ints = {0, 1, 2, 3, 5, 8})
    public void everyOffsetParsesTheWholeRange(int offset) throws IOException {
        String document = "<a b='1' c='2' d>";
        char[] buffer = (repeat('X', offset) + document).toCharArray();

        CanoeStateProbe probe = new CanoeStateProbe();
        probe.feed(buffer, offset, document.length());

        assertEquals(document, probe.output(), "the whole requested range reaches the writer");

        CanoeStateProbe reference = new CanoeStateProbe();
        reference.feed(document);

        assertEquals(signature(reference), signature(probe),
                "R15: the parser sees all " + document.length() + " characters whatever the offset,"
                        + " so it ends in the state the full range produces, not a shorter prefix's");
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
     * The error path now flushes exactly the parsed prefix, at every offset.
     *
     * <p>Inverted from {@code theErrorPathWritesTheWrongAmountOfPartialOutput}. The old error path
     * wrote {@code writer.write(cbuff, offset, len - (len - i))}, and {@code len - (len - i)}
     * simplifies to {@code i} — an absolute array index handed back where a length is expected — so
     * for the range {@code (offset 1, len 14)} over {@code "X<p>ok</p>5 < 6"} it emitted
     * {@code "<p>ok</p>5 < "}, the good prefix <em>plus</em> the offending character. R15 writes
     * {@code writer.write(cbuff, offset, i - offset)}: {@code i} is where the rejected character sits,
     * {@code offset} is where the range began, so {@code i - offset} is precisely the number of
     * characters parsed successfully, and the flushed prefix is {@code "<p>ok</p>5 <"} — the same as
     * at offset 0, where the arithmetic was accidentally right all along.
     */
    @Test
    public void theErrorPathWritesExactlyTheParsedPrefix() {
        // "5 < 6" is rejected: a literal '<' in body text is not a tag, and R20 keeps that one.
        String document = "<p>ok</p>5 < 6";
        char[] buffer = ("X" + document).toCharArray();
        CanoeStateProbe probe = new CanoeStateProbe();

        IOException error = assertThrows(IOException.class,
                () -> probe.feed(buffer, 1, document.length()));
        assertTrue(error.getMessage().startsWith(Canoe.ERROR_PREFIX), error.getMessage());

        assertEquals("<p>ok</p>5 <", probe.output(),
                "R15: the error path flushes only the parsed prefix, not the rejected character");

        // At offset 0 the same arithmetic was always right; the offset case now matches it.
        CanoeStateProbe atZero = new CanoeStateProbe();
        assertThrows(IOException.class, () -> atZero.feed(document.toCharArray(), 0,
                document.length()));
        assertEquals("<p>ok</p>5 <", atZero.output(),
                "at offset 0 the good prefix is exactly right, and the offset case now agrees");
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
