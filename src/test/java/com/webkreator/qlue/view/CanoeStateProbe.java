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
 * outcome changed. R3 closed F5 by clearing the buffer on every reuse, and the same access is what
 * asserts the clearing: "the outcome is the same now" would hold for a dozen reasons, and
 * {@code buf[10] == '\0'} is the one.
 *
 * <p>Asserting on the state rather than only on {@link Canoe#currentContext()} matters because
 * several distinct states collapse to the same context. {@code CTX_SUPPRESS} is returned by
 * {@code CSS}, {@code CSS_END}, {@code CSS_END_NAME}, {@code TAG}, {@code TAG_NAME},
 * {@code TAG_ATTR_NAME_AFTER}, every
 * state {@code currentContext()} has no case for, and the default branch — so a test that checked
 * only the context could not tell "correctly suppressed inside a style element" from "fell through
 * a hole in the switch".
 */
public class CanoeStateProbe extends Canoe {

    private final StringWriter sink;

    public CanoeStateProbe() {
        this(new StringWriter());
    }

    /**
     * A probe whose plain-text allowlist carries the application's own additions, for the tests of
     * R5's extension point. Pass names through
     * {@link Canoe#normalisePlainTextAttributeNames(java.util.Collection)} first, exactly as
     * {@code VelocityViewFactory} does, so that the probe cannot be given a set production could
     * never hold.
     */
    public CanoeStateProbe(java.util.Set<String> extraPlainTextAttributeNames) {
        this(new StringWriter(), extraPlainTextAttributeNames);
    }

    private CanoeStateProbe(StringWriter sink) {
        super(sink);
        this.sink = sink;
    }

    private CanoeStateProbe(StringWriter sink, java.util.Set<String> extraPlainTextAttributeNames) {
        super(sink, extraPlainTextAttributeNames);
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

    /**
     * The attribute name R5's unknown-name rule captured for its diagnostic, or null when the
     * current attribute name was recognised.
     *
     * <p>The diagnostic itself is an slf4j debug call, which no test can observe without installing
     * a backend; what a test can observe is that the field the message interpolates holds the name
     * of the attribute the reference is actually in. A diagnostic naming the wrong attribute is
     * worse than none, because the developer it exists for would go and look at the wrong element.
     */
    public String unknownAttributeName() {
        return unknownAttributeName;
    }

    /**
     * The element name R8 tracks for the duration of the tag, in lower case, or null when the
     * parser is not inside a tag whose name has been read.
     *
     * <p>This is the field R9 consults - "is the current element one of
     * script/iframe/object/embed/link/base". R10 weighed consulting it for "is this a meta whose
     * http-equiv is refresh" and deliberately did not, because that decision needs the sibling
     * http-equiv value as well and R10 left content suppressed. So what the tests assert through this
     * accessor is exactly the value R9's decision is made on: available for every attribute of the
     * tag, lower-cased however the template spelled it, stripped of an end tag's leading '/', and null
     * again the moment the tag closes.
     */
    public String tagName() {
        return tagName;
    }

    /** Whether the tag being parsed is an end tag; pairs with {@link #tagName()}. */
    public boolean closingTag() {
        return closingTag;
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
     * A copy of the shared name/value buffer. It is a field of the whole render, and since R3 it is
     * cleared at every reuse, so anything above what the current name or value wrote is a NUL -
     * which is itself what {@code BufferResidueTest.theBufferHoldsNothingTheCurrentNameOrValueWrote}
     * asserts through this accessor.
     */
    public char[] buffer() {
        return buf.clone();
    }

    /**
     * The character at one buffer index. Canoe no longer classifies attribute names or values by
     * fixed buffer index - R3 and R4 replaced both sets of hand-unrolled comparisons with bounded
     * string comparisons, and R8 retired the last fixed-index read, TAG_NAME's script/style
     * detection, when it began keeping the completed tag name in a field of its own - so this
     * accessor exists for the tests that record what those comparisons used to read, and that
     * nothing reads it now.
     */
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
            case SCRIPT_END_NAME: return "SCRIPT_END_NAME";
            case CSS: return "CSS";
            case CSS_END: return "CSS_END";
            case CSS_END_NAME: return "CSS_END_NAME";
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
            case ATTR_URI_RESOURCE: return "ATTR_URI_RESOURCE";
            case ATTR_DATA: return "ATTR_DATA";
            case ATTR_UNKNOWN: return "ATTR_UNKNOWN";
            case ATTR_ACTIONSCRIPT: return "ATTR_ACTIONSCRIPT";
            default: return "UNKNOWN(" + context + ")";
        }
    }
}
