/* 
 * Qlue Web Application Framework
 * Copyright 2009,2010 Ivan Ristic <ivanr@webkreator.com>
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
package com.webkreator.canoe;

import java.io.IOException;
import java.io.Writer;

// IDEA Have a list of white-listed HTML tags

// TODO Support HTML comments

// TODO Support DOCTYPE declarations

// TODO Prevent output into CTX_JS

// TODO Prevent output into script:src

public class Canoe extends Writer {
	public static final int CTX_SUPPRESS = 0;

	public static final int CTX_HTML = 1;

	public static final int CTX_JS = 2;

	public static final int CTX_URI = 3;

	public static final int CTX_CSS = 4;

	public static final String ERROR_PREFIX = "Encoding Error: ";

	public static final int MAX_TAGNAME_LEN = 16;

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

	protected int currentLine = 1;

	protected int currentPos = 1;

	protected String errorMessage;

	public Canoe(Writer writer) {
		this.writer = writer;
		this.state = HTML;
	}

	@Override
	public void close() throws IOException {
		writer.close();
	}

	@Override
	public void flush() throws IOException {
		writer.flush();
	}

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
	 */
	protected void detectAttributePrefix() {
		// Use HTML by default
		attributeContext = ATTR_HTML;

		if (buf[0] == 'a') {
			if ((buf[1] == 's') && (buf[2] == 'f') && (buf[3] == 'u')
					&& (buf[4] == 'n') && (buf[5] == 'c') && (buf[6] == 't')
					&& (buf[7] == 'i') && (buf[8] == 'o') && (buf[9] == 'n')
					&& (buf[10] == '\0')) {
				// asfunction
				attributeContext = ATTR_ACTIONSCRIPT;
				return;
			}
		}

		if (buf[0] == 'd') {
			if ((buf[1] == 'a') && (buf[2] == 't') && (buf[3] == 'a')
					&& (buf[4] == '\0')) {
				// data
				attributeContext = ATTR_DATA;
				return;
			}
		}

		if (buf[0] == 'j') {
			if ((buf[1] == 'a') && (buf[2] == 'v') && (buf[3] == 'a')
					&& (buf[4] == 's') && (buf[5] == 'c') && (buf[6] == 'r')
					&& (buf[7] == 'i') && (buf[8] == 'p') && (buf[9] == 't')
					&& (buf[10] == '\0')) {
				// javascript
				attributeContext = ATTR_JS;
				return;
			}
		}

		if (buf[0] == 'l') {
			if ((buf[1] == 'i') && (buf[2] == 'v') && (buf[3] == 'e')
					&& (buf[4] == 's') && (buf[5] == 'c') && (buf[6] == 'r')
					&& (buf[7] == 'i') && (buf[8] == 'p') && (buf[9] == 't')
					&& (buf[10] == '\0')) {
				// livescript
				attributeContext = ATTR_JS;
				return;
			}
		}

		if (buf[0] == 'm') {
			if ((buf[1] == 'o') && (buf[2] == 'c') && (buf[3] == 'h')
					&& (buf[4] == 'a') && (buf[5] == '\0')) {
				// mocha
				attributeContext = ATTR_JS;
				return;
			}
		}
	}

	/**
	 * Determines context for tag attributes based on the attribute name.
	 */
	protected void setTagAttributeContext() {
		// Use HTML by default
		attributeContext = ATTR_HTML;

		// background
		if ((buf[0] == 'b') && (buf[1] == 'a') && (buf[2] == 'c')
				&& (buf[3] == 'k') && (buf[4] == 'g') && (buf[5] == 'r')
				&& (buf[6] == 'o') && (buf[7] == 'u') && (buf[8] == 'n')
				&& (buf[9] == 'd') && (buf[10] == '\0')) {
			attributeContext = ATTR_URI;
			return;
		}

		// content
		if ((buf[0] == 'd') && (buf[1] == 'a') && (buf[2] == 't')
				&& (buf[3] == 'a') && (buf[4] == '\0')) {
			attributeContext = ATTR_CONTENT;
			return;
		}

		// data
		if ((buf[0] == 'd') && (buf[1] == 'a') && (buf[2] == 't')
				&& (buf[3] == 'a') && (buf[4] == '\0')) {
			attributeContext = ATTR_URI;
			return;
		}

		// dynsrc
		if ((buf[0] == 'd') && (buf[1] == 'y') && (buf[2] == 'n')
				&& (buf[3] == 's') && (buf[4] == 'r') && (buf[5] == 'c')
				&& (buf[6] == '\0')) {
			attributeContext = ATTR_URI;
			return;
		}

		// lowsrc
		if ((buf[0] == 'l') && (buf[1] == 'o') && (buf[2] == 'w')
				&& (buf[3] == 's') && (buf[4] == 'r') && (buf[5] == 'c')
				&& (buf[6] == '\0')) {
			attributeContext = ATTR_URI;
			return;
		}

		// href
		if ((buf[0] == 'h') && (buf[1] == 'r') && (buf[2] == 'e')
				&& (buf[3] == 'f') && (buf[4] == '\0')) {
			attributeContext = ATTR_URI;
			return;
		}

		// on
		if ((buf[0] == 'o') && (buf[1] == 'n')) {
			// onAbort
			if ((buf[2] == 'a') && (buf[3] == 'b') && (buf[4] == 'o')
					&& (buf[5] == 'r') && (buf[6] == 't') && (buf[7] == '\0')) {
				attributeContext = ATTR_JS;
				return;
			}

			// onBlur
			if ((buf[2] == 'b') && (buf[3] == 'l') && (buf[4] == 'u')
					&& (buf[5] == 'r') && (buf[6] == '\0')) {
				attributeContext = ATTR_JS;
				return;
			}

			// onC
			if (buf[2] == 'c') {
				// onChange
				if ((buf[3] == 'h') && (buf[4] == 'a') && (buf[5] == 'n')
						&& (buf[6] == 'g') && (buf[7] == 'e')
						&& (buf[8] == '\0')) {
					attributeContext = ATTR_JS;
					return;
				}

				// onClick
				if ((buf[3] == 'l') && (buf[4] == 'i') && (buf[5] == 'c')
						&& (buf[6] == 'k') && (buf[7] == '\0')) {
					attributeContext = ATTR_JS;
					return;
				}
			}

			// onD
			if (buf[2] == 'd') {
				// onDblClick
				if ((buf[3] == 'b') && (buf[4] == 'l') && (buf[5] == 'c')
						&& (buf[6] == 'l') && (buf[7] == 'i')
						&& (buf[8] == 'c') && (buf[9] == 'k')
						&& (buf[10] == '\0')) {
					attributeContext = ATTR_JS;
					return;
				}

				// onDragDrop
				if ((buf[3] == 'r') && (buf[4] == 'a') && (buf[5] == 'g')
						&& (buf[6] == 'd') && (buf[7] == 'r')
						&& (buf[8] == 'o') && (buf[9] == 'p')
						&& (buf[10] == '\0')) {

					attributeContext = ATTR_JS;
					return;
				}
			}

			// onE
			if (buf[2] == 'e') {
				// onEnd
				if ((buf[3] == 'n') && (buf[4] == 'd') && (buf[5] == '\0')) {
					attributeContext = ATTR_JS;
					return;
				}

				// onError
				if ((buf[3] == 'r') && (buf[4] == 'r') && (buf[5] == 'o')
						&& (buf[6] == 'r') && (buf[7] == '\0')) {
					attributeContext = ATTR_JS;
					return;
				}
			}

			// onKey
			if ((buf[2] == 'k') && (buf[3] == 'e') && (buf[4] == 'y')) {
				// onKeyDown
				if ((buf[5] == 'd') && (buf[6] == 'o') && (buf[7] == 'w')
						&& (buf[8] == 'n') && (buf[9] == '\0')) {
					attributeContext = ATTR_JS;
					return;
				}

				// onKeyPress
				if ((buf[5] == 'p') && (buf[6] == 'r') && (buf[7] == 'e')
						&& (buf[8] == 's') && (buf[9] == 's')
						&& (buf[10] == '\0')) {
					attributeContext = ATTR_JS;
					return;
				}

				// onKeyUp
				if ((buf[5] == 'u') && (buf[6] == 'p') && (buf[7] == '\0')) {
					attributeContext = ATTR_JS;
					return;
				}
			}

			// onLoad
			if ((buf[2] == 'l') && (buf[3] == 'o') && (buf[4] == 'a')
					&& (buf[5] == 'd') && (buf[6] == '\0')) {
				attributeContext = ATTR_JS;
				return;
			}

			// onMo
			if ((buf[2] == 'm') && (buf[3] == 'o')) {
				// onMouse
				if ((buf[4] == 'u') && (buf[5] == 's') && (buf[6] == 'e')) {
					// onMouseDown
					if ((buf[7] == 'd') && (buf[8] == 'o') && (buf[9] == 'w')
							&& (buf[10] == 'n') && (buf[11] == '\0')) {
						attributeContext = ATTR_JS;
						return;
					}

					// onMouseMove
					if ((buf[7] == 'm') && (buf[8] == 'o') && (buf[9] == 'v')
							&& (buf[10] == 'e') && (buf[11] == '\0')) {
						attributeContext = ATTR_JS;
						return;
					}

					// onMouseOut
					if ((buf[7] == 'o') && (buf[8] == 'u') && (buf[9] == 't')
							&& (buf[10] == '\0')) {
						attributeContext = ATTR_JS;
						return;
					}

					// onMouseOver
					if ((buf[7] == 'o') && (buf[8] == 'v') && (buf[9] == 'e')
							&& (buf[10] == 'r') && (buf[11] == '\0')) {
						attributeContext = ATTR_JS;
						return;
					}

					// onMouseUp
					if ((buf[7] == 'u') && (buf[8] == 'p') && (buf[9] == '\0')) {
						attributeContext = ATTR_JS;
						return;
					}
				}

				// onMove
				if ((buf[4] == 'v') && (buf[5] == 'e') && (buf[6] == '\0')) {
					attributeContext = ATTR_JS;
					return;
				}
			}

			// onRe
			if ((buf[2] == 'r') && (buf[3] == 'e')) {
				// onReadyStateChange
				if ((buf[4] == 'd') && (buf[5] == 'y') && (buf[6] == 's')
						&& (buf[7] == 't') && (buf[8] == 'a')
						&& (buf[9] == 't') && (buf[10] == 'e')
						&& (buf[11] == 'c') && (buf[12] == 'h')
						&& (buf[13] == 'a') && (buf[14] == 'n')
						&& (buf[15] == 'g') && (buf[16] == 'e')
						&& (buf[17] == '\0')) {
					attributeContext = ATTR_JS;
					return;
				}

				// onRes
				if (buf[4] == 's') {
					// onReset
					if ((buf[5] == 'e') && (buf[6] == 't') && (buf[7] == '\0')) {
						attributeContext = ATTR_JS;
						return;
					}

					// onResize
					if ((buf[5] == 'i') && (buf[6] == 'z') && (buf[7] == 'e')
							&& (buf[8] == '\0')) {
						attributeContext = ATTR_JS;
						return;
					}
				}
			}

			// onS
			if (buf[0] == 's') {
				// onSelect
				if ((buf[1] == 'e') && (buf[2] == 'l') && (buf[3] == 'e')
						&& (buf[4] == 'c') && (buf[5] == 't')
						&& (buf[6] == '\0')) {
					attributeContext = ATTR_JS;
					return;
				}

				// onSubmit
				if ((buf[1] == 'u') && (buf[2] == 'b') && (buf[3] == 'm')
						&& (buf[4] == 'i') && (buf[5] == 't')
						&& (buf[6] == '\0')) {
					attributeContext = ATTR_JS;
					return;
				}
			}

			// onUnLoad
			if ((buf[2] == 'u') && (buf[3] == 'n') && (buf[4] == 'l')
					&& (buf[5] == 'o') && (buf[6] == 'a') && (buf[7] == 'd')
					&& (buf[8] == '\0')) {
				attributeContext = ATTR_JS;
				return;
			}
		}

		// s
		if (buf[0] == 's') {
			// src
			if ((buf[1] == 'r') && (buf[2] == 'c') && (buf[3] == '\0')) {
				attributeContext = ATTR_URI;
				return;
			}

			// style
			if ((buf[1] == 't') && (buf[2] == 'y') && (buf[3] == 'l')
					&& (buf[4] == 'e') && (buf[5] == '\0')) {
				attributeContext = ATTR_CSS;
				return;
			}
		}

		return;
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
					bufLen = 0;
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

			case TAG_NAME:
				// On the first character, check if this is a closing tag
				if ((bufLen == 0) && (c == '/')) {
					// Closing tag
					buf[bufLen++] = '/';
					closingTag = true;
				} else {
					// Not a closing tag

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
						bufLen = 0;

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
					bufLen = 0;
					charNeedsProcessing = true;
				}
				break;

			case TAG_ATTR_VALUE_BEFORE:
				// First non-whitespace character starts attribute value
				if (!Character.isWhitespace(c)) {
					state = TAG_ATTR_VALUE;
					bufLen = 0;

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

	private void raiseError(String errorMessage) throws IOException {
		state = INVALID;
		this.errorMessage = ERROR_PREFIX + errorMessage + " (line: " + currentLine
				+ ", pos: " + currentPos + ")";
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
	 * Determines the current output context based on the
	 * parser's internal state.
	 * 
	 * @return current output context
	 */
	public int currentContext() {
		// System.err.println("STATE = " + state);

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
				return CTX_HTML;

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
			return HtmlEncoder.encodeForHTML(input);
		case CTX_JS:
			return HtmlEncoder.encodeForJavaScript(input);
		case CTX_URI:
			return HtmlEncoder.encodeForURL(input);
		case CTX_SUPPRESS:
		default:
			// Do nothing -- suppressed output
			return new String("");
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
