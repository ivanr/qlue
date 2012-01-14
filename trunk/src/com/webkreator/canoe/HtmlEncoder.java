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
package com.webkreator.canoe;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Contains a number of utility methods to properly encode data when preparing
 * HTML responses.
 */
public class HtmlEncoder {

	protected static HtmlEncoder _instance;

	private static Pattern uriPattern = Pattern
			.compile("^(https?://)([^/]+)(/.*)?$");

	/**
	 * Creates an instance of this encoder. We'd normally not need it, as all
	 * methods are static, but we need to pass an object into the model used by
	 * Velocity templates.
	 */
	public static synchronized HtmlEncoder instance() {
		if (_instance == null) {
			_instance = new HtmlEncoder();
		}

		return _instance;
	}

	/**
	 * Encodes input string for output into HTML.
	 * 
	 * @param input
	 * @return
	 */
	public static String encodeForHTML(String input) {
		if (input == null) {
			return null;
		}

		StringBuffer sb = new StringBuffer(input.length() * 2);
		encodeForHTML(input, sb);

		return sb.toString();
	}

	/**
	 * Encodes input string for output into HTML.
	 * 
	 * @param input
	 * @param sb
	 */
	public static void encodeForHTML(String input, StringBuffer sb) {
		if (input == null) {
			return;
		}

		for (int i = 0, n = input.length(); i < n; i++) {
			char c = input.charAt(i);

			switch (c) {
			// A few explicit conversions first
			case '<':
				sb.append("&lt;");
				break;
			case '>':
				sb.append("&gt;");
				break;
			case '&':
				sb.append("&amp;");
				break;
			case '"':
				sb.append("&quot;");
				break;
			case '\'':
				sb.append("&#39;");
				break;
			case '/':
				sb.append("&#47;");
				break;
			case '=':
				sb.append("&#61;");
				break;
			default:
				// Ranges a-z, A-Z, and 0-9 are allowed naked
				if (((c >= 'a') && (c <= 'z')) || ((c >= 'A') && (c <= 'Z'))
						|| ((c >= '0') && (c <= '9'))) {
					sb.append(c);
				} else {
					// Make control characters visible
					if (c < 32) {
						sb.append("[");
						sb.append(Integer.toHexString((int) c));
						sb.append("]");
					} else {
						// Encode everything else
						sb.append("&#");
						sb.append((int) c);
						sb.append(';');
					}
				}
				break;
			}
		}
	}

	/**
	 * Encodes input string for output into JavaScript.
	 * 
	 * @param input
	 * @return
	 */
	public static String encodeForJavaScript(String input) {
		if (input == null) {
			return null;
		}

		StringBuffer sb = new StringBuffer(input.length() * 2);
		encodeForJavaScript(input, sb);

		return sb.toString();
	}

	/**
	 * Encodes input string for output into JavaScript.
	 * 
	 * @param input
	 * @param sb
	 */
	public static void encodeForJavaScript(String input, StringBuffer sb) {
		if (input == null) {
			return;
		}

		sb.append('\'');

		for (int i = 0, n = input.length(); i < n; i++) {
			char c = input.charAt(i);

			if (((c >= 'a') && (c <= 'z')) || ((c >= 'A') && (c <= 'Z'))
					|| ((c >= '0') && (c <= '9'))) {
				sb.append(c);
			} else if (c <= 127) {
				sb.append("\\x");
				String hex = Integer.toString(c, 16);
				if (hex.length() < 2) {
					sb.append('0');
				}
				sb.append(hex);
			} else {
				sb.append("\\u");
				String hex = Integer.toString(c, 16);
				for (int k = hex.length(); k < 4; k++) {
					sb.append('0');
				}
				sb.append(hex);
			}
		}

		sb.append('\'');
	}

	/**
	 * Encodes input string for output into URL.
	 * 
	 * @param input
	 * @return
	 */
	public static String encodeForURL(String input) {
		if (input == null) {
			return null;
		}
		
		StringBuffer sb = new StringBuffer(input.length() * 2);

		Matcher m = uriPattern.matcher(input);
		if (m.matches()) {
			sb.append(m.group(1));
			encodeForURL(m.group(2), sb);
			encodeForURL(m.group(3), sb);
		} else {
			encodeForURL(input, sb);
		}
	
		return sb.toString();
	}

	/**
	 * Encodes input string for output into URL.
	 * 
	 * @param input
	 * @param sb
	 */
	private static void encodeForURL(String input, StringBuffer sb) {
		if (input == null) {
			return;
		}

		for (int i = 0, n = input.length(); i < n; i++) {
			char c = input.charAt(i);

			if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
					|| (c >= '0' && c <= '9') || (c == '/') || (c == '.')
					|| (c == '#') || (c == '?') || (c == '=')) {
				sb.append(c);
			} else {
				if (c <= 255) {
					sb.append('%');
					String hex = Integer.toString(c, 16);
					if (hex.length() < 2) {
						sb.append('0');
					}
					sb.append(hex);
				} else {
					sb.append('?');
				}
			}
		}
	}

	/**
	 * Encodes input string for output into CSS.
	 * 
	 * @param input
	 * @return
	 */
	public static String encodeForCSS(String input) {
		if (input == null) {
			return null;
		}

		StringBuffer sb = new StringBuffer(input.length() * 2);
		encodeForCSS(input, sb);

		return sb.toString();
	}

	/**
	 * Encodes input string for output into CSS.
	 * 
	 * @param input
	 * @param sb
	 */
	private static void encodeForCSS(String input, StringBuffer sb) {
		if (input == null) {
			return;
		}

		sb.append('\'');

		for (int i = 0, n = input.length(); i < n; i++) {
			char c = input.charAt(i);

			if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
					|| (c >= '0' && c <= '9')) {
				sb.append(c);
			} else {
				if (c <= 255) {
					sb.append('\\');
					String hex = Integer.toString(c, 16);
					if (hex.length() < 2) {
						sb.append('0');
					}
					sb.append(hex);
				} else {
					sb.append('?');
				}
			}
		}

		sb.append('\'');
	}

	/**
	 * Encodes input for HTML, preserving whitespace.
	 * 
	 * @param input
	 * @return
	 */
	public static String encodeForHTMLPreserveWhitespace(String input) {
		if (input == null) {
			return null;
		}

		StringBuffer sb = new StringBuffer(input.length() * 2);
		encodeForHTMLPreserveWhitespace(input, sb);

		return sb.toString();
	}

	/**
	 * Encodes input for HTML, preserving whitespace.
	 * 
	 * @param input
	 * @param sb
	 */
	public static void encodeForHTMLPreserveWhitespace(String input,
			StringBuffer sb) {
		if (input == null) {
			return;
		}

		for (int i = 0, n = input.length(); i < n; i++) {
			char c = input.charAt(i);

			switch (c) {
			// A few explicit conversions first
			case '<':
				sb.append("&lt;");
				break;
			case '>':
				sb.append("&gt;");
				break;
			case '&':
				sb.append("&amp;");
				break;
			case '"':
				sb.append("&quot;");
				break;
			case '\'':
				sb.append("&#39;");
				break;
			case '/':
				sb.append("&#47;");
				break;
			case '=':
				sb.append("&#61;");
				break;
			default:
				// Ranges a-z, A-Z, and 0-9 are allowed naked
				if (((c >= 'a') && (c <= 'z')) || ((c >= 'A') && (c <= 'Z'))
						|| ((c >= '0') && (c <= '9')) || (c == 0x0d)
						|| (c == 0x0a) || (c == 0x09)) {
					sb.append(c);
				} else {
					// Make control characters visible
					if (c < 32) {
						sb.append("[");
						sb.append(Integer.toHexString((int) c));
						sb.append("]");
					} else {
						// Encode everything else
						sb.append("&#");
						sb.append((int) c);
						sb.append(';');
					}
				}
				break;
			}
		}
	}

	public static String asis(String input) {
		return input;
	}
}
