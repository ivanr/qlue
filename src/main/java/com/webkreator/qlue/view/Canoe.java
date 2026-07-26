/* 
 * Qlue Web Application Framework
 * Copyright 2009-2012 Ivan Ristic <ivanr@webkreator.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.webkreator.qlue.view;

import com.webkreator.qlue.util.HtmlEncoder;

import java.io.IOException;
import java.io.Writer;
import java.util.Arrays;

/**
 * Canoe is a context-aware output encoder for HTML responses. It parses output
 * in real time and thus knows exactly what output encoding to use to encode a
 * piece of data.
 */
public class Canoe extends Writer {

    public static final String EMPTY_STRING = "";

    public static final int CTX_SUPPRESS = 0;

    public static final int CTX_HTML = 1;

    public static final int CTX_HTML_ATTR = 2;

    public static final int CTX_JS = 3;

    public static final int CTX_URI = 4;

    public static final int CTX_CSS = 5;

    public static final String ERROR_PREFIX = "Encoding Error: ";

    public static final int MAX_TAGNAME_LEN = 36;

    public static final int HTML = 0;

    public static final int TAG_NAME = 1;

    public static final int TAG = 2;

    public static final int TAG_ATTR_NAME = 3;

    public static final int TAG_ATTR_NAME_AFTER = 4;

    public static final int TAG_ATTR_VALUE_BEFORE = 5;

    public static final int TAG_ATTR_VALUE = 6;

    public static final int SCRIPT = 7;

    public static final int SCRIPT_END = 8;

    public static final int CSS = 9;

    public static final int CSS_END = 10;

    public static final int URL = 11;

    public static final int TAG_EMPTY_ENDING = 12;

    public static final int COMMENT_OPEN_OR_DOCTYPE = 13;

    public static final int COMMENT_OPEN_2 = 14;

    public static final int COMMENT = 15;

    public static final int COMMENT_CLOSE_1 = 16;

    public static final int COMMENT_CLOSE_2 = 17;

    public static final int DOCTYPE = 18;

    public static final int DOCTYPE_TEST = 19;

    public static final int INVALID = 666;

    public static final int QUOTE_NONE = 0;

    public static final int QUOTE_DOUBLE = 1;

    public static final int QUOTE_SINGLE = 2;

    public static final int ATTR_HTML = 0;

    public static final int ATTR_CSS = 1;

    public static final int ATTR_JS = 2;

    public static final int ATTR_URI = 3;

    public static final int ATTR_DATA = 4;

    public static final int ATTR_CONTENT = 5;

    public static final int ATTR_ACTIONSCRIPT = 6;

    protected boolean closingTag;

    protected int state;

    protected int nextState;

    protected int attributeContext;

    protected Writer writer;

    char buf[] = new char[MAX_TAGNAME_LEN];

    int bufLen;

    int attrQuotes;

    protected String cssEnd = "/style";

    protected String jsEnd = "/script";

    protected String doctypeText = "doctype";

    protected int currentLine = 1;

    protected int currentPos = 1;

    protected String errorMessage;

    protected int tagCount;

    /**
     * Create a Canoe instance.
     */
    public Canoe(Writer writer) {
        this.writer = writer;
        this.state = HTML;
    }

    /**
     * Close stream.
     */
    @Override
    public void close() throws IOException {
        writer.close();
    }

    /**
     * Flush stream.
     */
    @Override
    public void flush() throws IOException {
        writer.flush();
    }

    /**
     * Write one or more characters to output.
     */
    @Override
    public void write(char[] cbuff, int offset, int len) throws IOException {
        int i = offset;

        try {
            // Process characters one by one
            for (i = offset; i < len; i++) {
                processChar(cbuff[i]);
            }
        } catch (IOException e) {
            // Error -- write only "good" characters. In case of
            // an error i will contain the last known good character.
            writer.write(cbuff, offset, len - (len - i));

            throw e;
        }

        // No error has occurred -- write the entire buffer
        writer.write(cbuff, offset, len);
    }

    /**
     * Determines if the character can be used in tag name.
     *
     * @param c
     * @return
     */
    public boolean isTagNameChar(char c, int pos) {
        if (Character.isLetter(c)) {
            return true;
        }

        if ((c == ':') || (c == '_')) {
            return true;
        }

        if (pos != 0) {
            if (Character.isDigit(c)) {
                return true;
            }

            if ((c == '-') || (c == '.')) {
                return true;
            }
        }

        return false;
    }

    /**
     * Detects one of "asfunction:", "data:", "javascript:", "livescript:", and
     * "mocha:" attribute value prefixes, and sets the attribute value context
     * accordingly.
     *
     * <p>This method may only ever <em>narrow</em> the context. It starts from
     * whatever {@link #setTagAttributeContext()} derived from the attribute
     * name and assigns ATTR_ACTIONSCRIPT, ATTR_DATA or ATTR_JS only when one of
     * the five prefixes actually matches; when none does, the name-derived
     * context is left exactly as it was. It used to open with an unconditional
     * "attributeContext = ATTR_HTML", which meant the first colon in any value
     * threw the name's classification away: a style attribute stopped being
     * suppressed the moment a CSS property name was written in front of the
     * reference, and a correctly recognised on* handler stopped being
     * suppressed the moment its body contained an object literal or a ternary.
     * All three of the prefixes this method can assign map to a suppressing
     * context, so narrowing is the only direction that is safe here.
     *
     * <p>The comparison is length-checked against bufLen rather than made of
     * fixed buffer indices. It used to confirm that a prefix ended by testing
     * buf[4], buf[5] or buf[10] for a NUL, but the value scan never writes a
     * terminator - only the name scan does - so the byte it read was left there
     * by whichever earlier tag or attribute name was long enough to reach that
     * index. Whether "javascript:" was recognised therefore depended on markup
     * elsewhere on the page: an eleven-character name upstream disarmed it, a
     * ten-character one repaired it, and reordering two unrelated elements
     * changed the security of the page. Comparing bufLen characters against a
     * literal cannot read anything the value did not write.
     */
    protected void detectAttributePrefix() {
        if (bufferedValueIs("asfunction")) {
            attributeContext = ATTR_ACTIONSCRIPT;
            return;
        }

        if (bufferedValueIs("data")) {
            attributeContext = ATTR_DATA;
            return;
        }

        if (bufferedValueIs("javascript") || bufferedValueIs("livescript")
                || bufferedValueIs("mocha")) {
            attributeContext = ATTR_JS;
        }
    }

    /**
     * Whether the characters the value scan has buffered are exactly the given
     * prefix. The scan lower-cases as it buffers, so the comparison is against
     * a lower-case literal and no case variant evades it.
     *
     * @param prefix the prefix to compare against, in lower case
     * @return true when bufLen characters of buf equal prefix
     */
    private boolean bufferedValueIs(String prefix) {
        if (bufLen != prefix.length()) {
            return false;
        }

        for (int i = 0; i < bufLen; i++) {
            if (buf[i] != prefix.charAt(i)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Begins a fresh use of the shared name/value buffer.
     *
     * <p>buf is a field of the whole render, so without this every use of it
     * starts on top of whatever the previous tag name, attribute name or
     * attribute value left behind. That residue was the root cause of the
     * prefix-detection defect this class's detectAttributePrefix() used to
     * carry, and clearing on reuse is what keeps the buffer from meaning
     * anything other than "what the current name or value has written".
     */
    private void resetBuffer() {
        Arrays.fill(buf, '\0');
        bufLen = 0;
    }

    /**
     * Determines context for tag attributes based on the attribute name.
     *
     * <p>The event-handler rule is a <em>prefix</em> rule: any attribute whose
     * name begins "on" is JavaScript. It replaces a table of twenty-four
     * hand-unrolled comparison chains that recognised eighteen of the ninety-four
     * event handler content attributes the HTML Standard defines, and three of
     * whose branches could never be taken at all - onselect and onsubmit tested
     * buf[0] == 's' inside a block that had already established buf[0] == 'o',
     * and the onreadystatechange chain spelled "onredystatechange", missing the
     * "a" of "ready". Every name the table missed took the ATTR_HTML default, and
     * html()'s character references are decoded by the HTML parser before the
     * value is compiled as JavaScript, so each miss handed the attacker's
     * original characters to the script engine.
     *
     * <p>There is no benign exception worth carving out of the rule. An attribute
     * whose name begins "on" and which no engine will ever fire is inert either
     * way, so suppressing it costs a template author nothing; a name that is
     * missed is arbitrary script execution. The rule also cannot go stale: every
     * handler the standard adds in future is already covered, which is what makes
     * EventHandlerMatrixTest's completeness guard permanently satisfiable rather
     * than a list to catch up with.
     *
     * <p>Every comparison here is made against the buffered name as a bounded
     * string rather than against fixed buf indices, for the reason R3 gave for
     * the value side: buf is a field of the whole render, and a comparison that
     * reads an index the current name did not write is reading residue. The name
     * scan does write a NUL terminator, so the old chains were not wrong in the
     * way detectAttributePrefix()'s were - but "correct because a terminator
     * happens to be there" is the property that failed on the value side, and it
     * is not worth keeping on this one.
     */
    protected void setTagAttributeContext() {
        // Use HTML by default
        attributeContext = ATTR_HTML;

        // Any event handler. The prefix rule replaces the on* table entirely; see
        // the method javadoc for why there is no exception list.
        if (bufferedNameStartsWith("on")) {
            attributeContext = ATTR_JS;
            return;
        }

        // background
        if (bufferedNameIs("background")) {
            attributeContext = ATTR_URI;
            return;
        }

        // XXX The following two cases are the same, which one is correct?

        // content
        if (bufferedNameIs("data")) {
            attributeContext = ATTR_CONTENT;
            return;
        }

        // data
        if (bufferedNameIs("data")) {
            attributeContext = ATTR_URI;
            return;
        }

        // dynsrc
        if (bufferedNameIs("dynsrc")) {
            attributeContext = ATTR_URI;
            return;
        }

        // lowsrc
        if (bufferedNameIs("lowsrc")) {
            attributeContext = ATTR_URI;
            return;
        }

        // href
        if (bufferedNameIs("href")) {
            attributeContext = ATTR_URI;
            return;
        }

        // src
        if (bufferedNameIs("src")) {
            attributeContext = ATTR_URI;
            return;
        }

        // style
        if (bufferedNameIs("style")) {
            attributeContext = ATTR_CSS;
            return;
        }
    }

    /**
     * Whether the attribute name the name scan has buffered is exactly the given
     * name. The scan lower-cases as it buffers, so the comparison is against a
     * lower-case literal and no case variant evades it.
     *
     * @param name the name to compare against, in lower case
     * @return true when the buffered name equals name
     */
    private boolean bufferedNameIs(String name) {
        // bufLen counts the NUL terminator TAG_ATTR_NAME writes when the name
        // ends, so a buffered name of n characters leaves bufLen at n + 1.
        if (bufLen != name.length() + 1) {
            return false;
        }

        return bufferedNameStartsWith(name);
    }

    /**
     * Whether the attribute name the name scan has buffered begins with the given
     * prefix. Only the prefix's own characters are read, so nothing this method
     * looks at can have been written by an earlier name or value.
     *
     * @param prefix the prefix to compare against, in lower case
     * @return true when the buffered name starts with prefix
     */
    private boolean bufferedNameStartsWith(String prefix) {
        if (bufLen - 1 < prefix.length()) {
            return false;
        }

        for (int i = 0; i < prefix.length(); i++) {
            if (buf[i] != prefix.charAt(i)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Process one character and keep track of character coordinates within
     * output.
     *
     * @param c
     */
    protected void processChar(char c) throws IOException {
        // First process the character
        reallyProcessChar(c);

        // Keep track of the character position, which
        // is useful for error reporting
        if (c == 0x0a) {
            currentLine++;
            currentPos = 1;
        } else {
            currentPos++;
        }
    }

    /**
     * Processes one output character.
     *
     * @param c
     */
    protected void reallyProcessChar(char c) throws IOException {
        boolean charNeedsProcessing = true;

        while (charNeedsProcessing) {
            // By default we assume character will be processed,
            // and leave it to individual states to override
            charNeedsProcessing = false;

            // System.err.println("CHAR = " + c + " STATE = " + state);

            switch (state) {

                case HTML:
                    // Detect tags
                    if (c == '<') {
                        // New tag
                        state = TAG_NAME;
                        closingTag = false;
                        resetBuffer();
                        tagCount++;
                    } else {
                        // Non-markup character

                        // Do not allow characters below 0x20, except \t, \n and \r
                        if ((c < 0x20)
                                && ((c != '\t') && (c != '\r') && (c != '\n'))) {
                            raiseError("Invalid character detected in output");
                            return;
                        }
                    }
                    break;

                case COMMENT_OPEN_OR_DOCTYPE:
                    if (c == '-') {
                        state = COMMENT_OPEN_2;
                    } else if ((c == 'D') || (c == 'd')) {
                        if (tagCount != 1) {
                            raiseError("DOCTYPE declaration must be at the beginning");
                        } else {
                            bufLen = 1;
                            state = DOCTYPE_TEST;
                        }
                    } else {
                        raiseError("Invalid tag");
                    }
                    break;

                case DOCTYPE_TEST:
                    if (Character.toLowerCase(c) != doctypeText.charAt(bufLen)) {
                        raiseError("Invalid DOCTYPE declaration");
                    } else {
                        if (bufLen == doctypeText.length() - 1) {
                            state = DOCTYPE;
                        } else {
                            bufLen++;
                        }
                    }
                    break;

                case COMMENT_OPEN_2:
                    if (c == '-') {
                        state = COMMENT;
                    } else {
                        raiseError("Invalid tag");
                    }
                    break;

                case COMMENT:
                    if (c == '-') {
                        state = COMMENT_CLOSE_1;
                    }
                    break;

                case COMMENT_CLOSE_1:
                    if (c == '-') {
                        state = COMMENT_CLOSE_2;
                    } else {
                        state = COMMENT;
                    }
                    break;

                case COMMENT_CLOSE_2:
                    if (c == '>') {
                        state = HTML;
                    } else {
                        state = COMMENT;
                    }
                    break;

                case DOCTYPE:
                    if (c == '>') {
                        state = HTML;
                    }
                    break;

                case TAG_NAME:
                    // On the first character, check if this is a closing tag,
                    // a comment, or a DOCTYPE declaration
                    if (bufLen == 0) {
                        if (c == '/') {
                            // Closing tag
                            buf[bufLen++] = '/';
                            closingTag = true;
                            continue;
                        } else if (c == '!') {
                            state = COMMENT_OPEN_OR_DOCTYPE;
                            continue;
                        }
                    }

                    // Check if character is part of tag name
                    if (isTagNameChar(c, bufLen)) {
                        // Character is part of tag name

                        // Check tag name length
                        if (bufLen == buf.length - 1) {
                            raiseError("Tag name too long");
                            return;
                        }

                        // Copy tag name character into buffer
                        buf[bufLen++] = Character.toLowerCase(c);
                    } else {
                        // Found tag name (the current
                        // character not part of name)

                        buf[bufLen++] = '\0';
                        // System.err.println("TAG NAME: " + inBuf());

                        // Do we have at least one character in tag name?
                        if (((closingTag == false) && (bufLen == 1))
                                || (closingTag == true) && (bufLen == 2)) {
                            raiseError("Tag name too short");
                            return;
                        }

                        // Char after tag name must be '>' or whitespace
                        if ((Character.isWhitespace(c) == false) && (c != '>')) {
                            raiseError("Invalid character after tag name");
                            return;
                        }

                        // By default, the next state
                        // (inside tag) is HTML
                        nextState = HTML;

                        // Detect <script> and <style> tags
                        if (!closingTag) {
                            if ((buf[0] == 's') && (buf[1] == 'c')
                                    && (buf[2] == 'r') && (buf[3] == 'i')
                                    && (buf[4] == 'p') && (buf[5] == 't')
                                    && (buf[6] == '\0')) {
                                // Script
                                nextState = SCRIPT;
                            }

                            if ((buf[0] == 's') && (buf[1] == 't')
                                    && (buf[2] == 'y') && (buf[3] == 'l')
                                    && (buf[4] == 'e') && (buf[5] == '\0')) {
                                // Style
                                nextState = CSS;
                            }
                        }

                        // We're in a tag now
                        state = TAG;

                        // Still need to consume the character
                        charNeedsProcessing = true;
                    }
                    break;

                case TAG_EMPTY_ENDING:
                    if (c != '>') {
                        raiseError("Expected '>' after '/' in tag.");
                        return;
                    } else {
                        state = nextState;
                    }
                    break;

                case TAG:
                    // Have we encountered the end of the tag?
                    if (c == '>') {
                        // Switch to the state we decided on earlier
                        state = nextState;
                    } else if (c == '/') {
                        // Seems like the end of an empty element
                        state = TAG_EMPTY_ENDING;
                    } else {
                        // We're still inside of a tag

                        // A non-whitespace character will begin attribute name
                        if (Character.isWhitespace(c) == false) {
                            // Check that the character is allowed in attribute name
                            if (isTagNameChar(c, bufLen) == false) {
                                raiseError("Invalid character in attribute name");
                                return;
                            }

                            // Start processing attribute name
                            state = TAG_ATTR_NAME;
                            resetBuffer();

                            // Still need to consume the character
                            charNeedsProcessing = true;
                        }
                    }
                    break;

                case TAG_ATTR_NAME:
                    // Is character part of attribute name
                    if (isTagNameChar(c, bufLen)) {
                        // Character is part of attribute name

                        if (bufLen == buf.length - 1) {
                            raiseError("Attribute name too long");
                            return;
                        }

                        buf[bufLen++] = Character.toLowerCase(c);
                    } else {
                        // Found attribute name (this character not part of it)

                        buf[bufLen++] = '\0';

                        // System.err.println("ATTR NAME: " + inBuf());

                        // Do we have at least one character in tag name?
                        if (bufLen == 1) {
                            raiseError("Attribute name too short");
                            return;
                        }

                        // Determine attribute context based on its name
                        setTagAttributeContext();

                        // Tag name can be followed by =, whitespace, /, and >
                        if ((Character.isWhitespace(c) == false) && (c != '>')
                                && (c != '=') && (c != '/')) {
                            raiseError("Invalid character after tag name");
                            state = INVALID;
                            return;
                        }

                        state = TAG_ATTR_NAME_AFTER;

                        // Still need to consume character
                        charNeedsProcessing = true;
                    }

                    break;

                case TAG_ATTR_NAME_AFTER:
                    if (Character.isWhitespace(c)) {
                        // Do nothing
                    } else if (c == '=') {
                        state = TAG_ATTR_VALUE_BEFORE;
                    } else if (c == '/') {
                        state = TAG_EMPTY_ENDING;
                    } else if (c == '>') {
                        // Tag attribute without value, then end of tag
                        state = TAG;
                        charNeedsProcessing = true;
                    } else {
                        // Seems like attribute without value, and
                        // a new tag

                        if (isTagNameChar(c, bufLen) == false) {
                            raiseError("Invalid character in tag name");
                            return;
                        }

                        state = TAG_ATTR_NAME;
                        resetBuffer();
                        charNeedsProcessing = true;
                    }
                    break;

                case TAG_ATTR_VALUE_BEFORE:
                    // First non-whitespace character starts attribute value
                    if (!Character.isWhitespace(c)) {
                        state = TAG_ATTR_VALUE;
                        resetBuffer();

                        // Check the starting character
                        if (c == '"') {
                            // Double quote
                            attrQuotes = QUOTE_DOUBLE;
                        } else if (c == '\'') {
                            // Single quote
                            attrQuotes = QUOTE_SINGLE;
                        } else {
                            // No quotes
                            attrQuotes = QUOTE_NONE;
                            // Still need to consume character
                            charNeedsProcessing = true;
                        }
                    }
                    break;

                case TAG_ATTR_VALUE:
                    // Determine if we're at the end of attribute value
                    switch (attrQuotes) {

                        case QUOTE_NONE:
                            if ((Character.isWhitespace(c)) || (c == '>')) {
                                state = TAG;
                                // Still need to consume character
                                charNeedsProcessing = true;
                            }
                            break;

                        case QUOTE_SINGLE:
                            if (c == '\'') {
                                state = TAG;
                            }
                            break;

                        case QUOTE_DOUBLE:
                            if (c == '"') {
                                state = TAG;
                            }
                            break;
                    }

                    // Attribute value prefix detection
                    if (state == TAG_ATTR_VALUE) {
                        if (bufLen != -1) {
                            if (c == ':') {
                                // Look in the buffer to see if the
                                // prefix matches any of the ones we're
                                // looking for
                                detectAttributePrefix();

                                // Do not look into attribute value any more
                                bufLen = -1;
                            } else {
                                // The longest prefix has 10 characters
                                if (bufLen == 10) {
                                    // Do not look into attribute value any more
                                    bufLen = -1;
                                } else {
                                    if (bufLen == buf.length) {
                                        raiseError("Internal error #1001");
                                        return;
                                    }

                                    buf[bufLen++] = Character.toLowerCase(c);
                                }
                            }
                        }
                    }
                    break;

                case SCRIPT:
                    if (c == '<') {
                        state = SCRIPT_END;
                        // Not resetBuffer(): SCRIPT_END counts through jsEnd with
                        // bufLen and never reads or writes buf.
                        bufLen = 0;
                    }
                    break;

                case SCRIPT_END:
                    if (Character.toLowerCase(c) == jsEnd.charAt(bufLen)) {
                        if (jsEnd.length() == bufLen + 1) {
                            state = TAG;
                            nextState = HTML;
                        } else {
                            bufLen++;
                        }
                    } else {
                        state = SCRIPT;
                    }
                    break;

                case CSS:
                    if (c == '<') {
                        state = CSS_END;
                        // As in SCRIPT: bufLen indexes cssEnd, and buf is untouched.
                        bufLen = 0;
                    }
                    break;

                case CSS_END:
                    if (Character.toLowerCase(c) == cssEnd.charAt(bufLen)) {
                        if (cssEnd.length() == bufLen + 1) {
                            state = TAG;
                            nextState = HTML;
                        } else {
                            bufLen++;
                        }
                    } else {
                        state = CSS;
                    }
                    break;
            }
        }
    }

    /**
     * Raise an error.
     *
     * @param errorMessage
     * @throws IOException
     */
    private void raiseError(String errorMessage) throws IOException {
        state = INVALID;
        this.errorMessage = ERROR_PREFIX + errorMessage + " (line: "
                + currentLine + ", pos: " + currentPos + ")";
        throw new IOException(this.errorMessage);
    }

    /**
     * Converts the contents of the buffer into a string.
     *
     * @return String that represents the contents of the buffer
     */
    protected String inBuf() {
        if ((bufLen > 0) && (buf[bufLen - 1] == '\0')) {
            return new String(buf, 0, bufLen - 1);
        } else {
            return new String(buf, 0, bufLen);
        }
    }

    /**
     * Determines the current output context based on the parser's internal
     * state.
     *
     * @return current output context
     */
    public int currentContext() {
        switch (state) {
            case HTML:
                return CTX_HTML;

            case SCRIPT:
            case SCRIPT_END:
                return CTX_JS;

            case URL:
                return CTX_URI;

            case CSS:
            case CSS_END:
            case TAG:
            case TAG_NAME:
            case TAG_ATTR_NAME_AFTER:
                return CTX_SUPPRESS;

            case TAG_ATTR_VALUE:
                switch (attributeContext) {
                    case ATTR_HTML:
                        return CTX_HTML_ATTR;

                    case ATTR_JS:
                        return CTX_JS;

                    case ATTR_URI:
                        return CTX_URI;

                    case ATTR_CSS:
                    case ATTR_DATA:
                    case ATTR_CONTENT:
                    case ATTR_ACTIONSCRIPT:
                        return CTX_SUPPRESS;

                    default:
                        return CTX_SUPPRESS;
                }
        }

        return CTX_SUPPRESS;
    }

    /**
     * Encodes string, choosing the appropriate encoding method depending on the
     * current output context.
     *
     * @param input
     * @param ctx
     * @return
     */
    public static String encode(String input, int ctx) {
        switch (ctx) {
            case CTX_HTML:
                return HtmlEncoder.htmlWhite(input);
            case CTX_HTML_ATTR:
                return HtmlEncoder.htmlAttr(input);
            case CTX_JS:
                // Do not output anything into JS contexts
                // return HtmlEncoder.encodeForJavaScript(input);
                return EMPTY_STRING;
            case CTX_URI:
                return HtmlEncoder.url(input);
            case CTX_CSS:
                // Do not output anything into CSS contexts
                // return HtmlEncoder.encodeForCSS(input);
                return EMPTY_STRING;
            case CTX_SUPPRESS:
            default:
                // Do nothing -- suppressed output
                return EMPTY_STRING;
        }
    }

    /**
     * Writes a string to output, encoding it properly in the process.
     *
     * @param input
     * @throws Exception
     */
    public void writeEncoded(String input) throws Exception {
        write(encode(input, currentContext()));
    }
}
