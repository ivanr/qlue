package com.webkreator.qlue.view;

import java.io.IOException;
import java.io.StringWriter;

/**
 * A {@link Canoe} that lets tests see its internal state.
 *
 * <p>Declared in {@code com.webkreator.qlue.view} rather than in the test suite's own package
 * because {@code buf}, {@code bufLen} and {@code attrQuotes} are package-private. {@code state},
 * {@code nextState} and {@code attributeContext} are protected and would be reachable from a
 * subclass anywhere; the buffer is not, and {@code BufferResidueTest} (T22) needs to read it to
 * characterise F5 — which byte of residue an earlier attribute name left, not merely that the
 * outcome changed.
 *
 * <p>Asserting on the state rather than only on {@link Canoe#currentContext()} matters because
 * several distinct states collapse to the same context. {@code CTX_SUPPRESS} is returned by
 * {@code CSS}, {@code CSS_END}, {@code TAG}, {@code TAG_NAME}, {@code TAG_ATTR_NAME_AFTER}, every
 * state {@code currentContext()} has no case for, and the default branch — so a test that checked
 * only the context could not tell "correctly suppressed inside a style element" from "fell through
 * a hole in the switch".
 */
public class CanoeStateProbe extends Canoe {

    private final StringWriter sink;

    public CanoeStateProbe() {
        this(new StringWriter());
    }

    private CanoeStateProbe(StringWriter sink) {
        super(sink);
        this.sink = sink;
    }

    /**
     * Feeds literal template text through the state machine.
     *
     * <p>Always an offset-0 write, because {@code Writer.write(String)} delegates to
     * {@code write(cbuf, 0, len)}. F9 — the loop bound of {@code i < len} where it should be
     * {@code i < offset + len} — is invisible at offset 0, so this method cannot exercise it. Use
     * {@link #feed(char[], int, int)} for that.
     */
    public CanoeStateProbe feed(String text) throws IOException {
        write(text);
        return this;
    }

    /**
     * Feeds a slice of a character array, which is the entry point F9 lives in.
     */
    public CanoeStateProbe feed(char[] buffer, int offset, int length) throws IOException {
        write(buffer, offset, length);
        return this;
    }

    /** Everything that reached the underlying writer. */
    public String output() {
        return sink.toString();
    }

    /** The parser's current state, one of {@code Canoe.HTML}, {@code Canoe.TAG}, and so on. */
    public int state() {
        return state;
    }

    /** The state the parser will move to when the current tag closes. */
    public int nextState() {
        return nextState;
    }

    /** The context derived from the current attribute's name, or its value prefix. */
    public int attributeContext() {
        return attributeContext;
    }

    /** Which quote style, if any, delimits the attribute value being parsed. */
    public int attrQuotes() {
        return attrQuotes;
    }

    /** How much of the shared buffer is currently in use; -1 once value scanning has given up. */
    public int bufLen() {
        return bufLen;
    }

    /**
     * A copy of the shared name/value buffer, including the residue an earlier tag left behind.
     * The buffer is a field of the whole render and is never cleared — only {@code bufLen} is reset.
     */
    public char[] buffer() {
        return buf.clone();
    }

    /** The character at one buffer index, as the hand-unrolled comparisons in Canoe read it. */
    public char bufferAt(int index) {
        return buf[index];
    }

    /** A readable name for a state constant, so failures say TAG_ATTR_VALUE rather than 6. */
    public static String stateName(int state) {
        switch (state) {
            case HTML: return "HTML";
            case TAG_NAME: return "TAG_NAME";
            case TAG: return "TAG";
            case TAG_ATTR_NAME: return "TAG_ATTR_NAME";
            case TAG_ATTR_NAME_AFTER: return "TAG_ATTR_NAME_AFTER";
            case TAG_ATTR_VALUE_BEFORE: return "TAG_ATTR_VALUE_BEFORE";
            case TAG_ATTR_VALUE: return "TAG_ATTR_VALUE";
            case SCRIPT: return "SCRIPT";
            case SCRIPT_END: return "SCRIPT_END";
            case CSS: return "CSS";
            case CSS_END: return "CSS_END";
            case URL: return "URL";
            case TAG_EMPTY_ENDING: return "TAG_EMPTY_ENDING";
            case COMMENT_OPEN_OR_DOCTYPE: return "COMMENT_OPEN_OR_DOCTYPE";
            case COMMENT_OPEN_2: return "COMMENT_OPEN_2";
            case COMMENT: return "COMMENT";
            case COMMENT_CLOSE_1: return "COMMENT_CLOSE_1";
            case COMMENT_CLOSE_2: return "COMMENT_CLOSE_2";
            case DOCTYPE: return "DOCTYPE";
            case DOCTYPE_TEST: return "DOCTYPE_TEST";
            case INVALID: return "INVALID";
            default: return "UNKNOWN(" + state + ")";
        }
    }

    /** A readable name for an {@code ATTR_*} constant. */
    public static String attributeContextName(int context) {
        switch (context) {
            case ATTR_HTML: return "ATTR_HTML";
            case ATTR_CSS: return "ATTR_CSS";
            case ATTR_JS: return "ATTR_JS";
            case ATTR_URI: return "ATTR_URI";
            case ATTR_DATA: return "ATTR_DATA";
            case ATTR_CONTENT: return "ATTR_CONTENT";
            case ATTR_ACTIONSCRIPT: return "ATTR_ACTIONSCRIPT";
            default: return "UNKNOWN(" + context + ")";
        }
    }
}
