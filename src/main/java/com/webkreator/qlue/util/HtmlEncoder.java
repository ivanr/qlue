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
package com.webkreator.qlue.util;

import com.webkreator.qlue.Page;
import com.webkreator.qlue.view.velocity.CanoeReferenceInsertionHandler;
import com.webkreator.qlue.view.velocity.QlueVelocityTool;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Contains a number of utility methods to properly encode data when preparing HTML responses.
 */
public class HtmlEncoder implements QlueVelocityTool {

    public static final int HTAB = 0x09;

    public static final int LF = 0x0a;

    public static final int CR = 0x0d;

    private Page page;

    private static Pattern uriPattern = Pattern.compile("^(https?://)([^/]+)(/.*)?$");

    private static final char[] hexDigits = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'};

    public String getName() {
        return CanoeReferenceInsertionHandler.SAFE_REFERENCE_NAME;
    }

    /**
     * Encodes input string for output into HTML.
     *
     * @param input
     * @return
     */
    public static String html(String input) {
        if (input == null) {
            return null;
        }

        StringBuilder sb = new StringBuilder(input.length() * 2);
        HtmlEncoder.html(input, sb);

        return sb.toString();
    }

    /**
     * Encodes input string for output into HTML.
     *
     * @param input
     * @param sb
     */
    public static void html(String input, StringBuilder sb) {
        if (input == null) {
            return;
        }

        for (int c : input.codePoints().toArray()) {
            HtmlEncoder.html(c, sb);
        }
    }

    public static void html(int c, StringBuilder sb) {
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
                if (((c >= 'a') && (c <= 'z'))
                        || ((c >= 'A') && (c <= 'Z'))
                        || ((c >= '0') && (c <= '9'))) {
                    sb.append((char) c);
                } else {
                    // Make control characters visible
                    if (c < 32) {
                        sb.append("\\x");
                        HtmlEncoder.hex(c, sb);
                    } else {
                        // Encode everything else
                        sb.append("&#");
                        sb.append(Integer.toString(c));
                        sb.append(';');
                    }
                }
                break;
        }
    }

    /**
     * Encodes input string for output into JavaScript.
     *
     * @param input
     * @return
     */
    public static String js(String input) {
        if (input == null) {
            return null;
        }

        StringBuilder sb = new StringBuilder(input.length() * 2);
        HtmlEncoder.js(input, sb);

        return sb.toString();
    }

    /**
     * Encodes input string for output into JavaScript.
     *
     * @param input
     * @param sb
     */
    public static void js(String input, StringBuilder sb) {
        if (input == null) {
            return;
        }

        sb.append('\'');

        for (int c : input.codePoints().toArray()) {
            if (((c >= 'a') && (c <= 'z'))
                    || ((c >= 'A') && (c <= 'Z'))
                    || ((c >= '0') && (c <= '9'))) {
                sb.append((char) c);
            } else if (c <= 127) {
                sb.append("\\x");
                HtmlEncoder.hex(c, sb);
            } else {
                sb.append("\\u");
                HtmlEncoder.hex(c >> 8, sb);
                HtmlEncoder.hex(c, sb);
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
    public static String url(String input) {
        if (input == null) {
            return null;
        }

        StringBuilder sb = new StringBuilder(input.length() * 2);

        Matcher m = uriPattern.matcher(input);
        if (m.matches()) {
            // The first group is only literals "http://" and "https://"
            sb.append(m.group(1));
            HtmlEncoder.url(m.group(2), sb);
            HtmlEncoder.url(m.group(3), sb);
        } else {
            HtmlEncoder.url(input, sb);
        }

        return sb.toString();
    }

    /**
     * Encodes input string for output into URL.
     *
     * @param input
     * @param sb
     */
    private static void url(String input, StringBuilder sb) {
        if (input == null) {
            return;
        }

        for (int c : input.codePoints().toArray()) {
            if ((c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || (c == '/')
                    || (c == '.')
                    || (c == '-')
                    || (c == '#')
                    || (c == '?')
                    || (c == '=')) {
                sb.append((char) c);
            } else {
                if (c <= 255) {
                    sb.append('%');
                    HtmlEncoder.hex(c, sb);
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
    public static String css(String input) {
        if (input == null) {
            return null;
        }

        StringBuilder sb = new StringBuilder(input.length() * 2);
        HtmlEncoder.css(input, sb);

        return sb.toString();
    }

    /**
     * Encodes input string for output into CSS.
     *
     * @param input
     * @param sb
     */
    private static void css(String input, StringBuilder sb) {
        if (input == null) {
            return;
        }

        sb.append('\'');

        for (int c : input.codePoints().toArray()) {
            if ((c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')) {
                sb.append((char) c);
            } else {
                if (c <= 255) {
                    sb.append('\\');
                    HtmlEncoder.hex(c, sb);
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
    public static String htmlWhite(String input) {
        if (input == null) {
            return null;
        }

        StringBuilder sb = new StringBuilder(input.length() * 2);
        HtmlEncoder.htmlWhite(input, sb);

        return sb.toString();
    }

    public static String htmlWhiteLineBreaks(String input) {
        if (input == null) {
            return null;
        }

        StringBuilder sb = new StringBuilder(input.length() * 2);
        HtmlEncoder.htmlWhiteLineBreaks(input, sb);

        return sb.toString();
    }

    /**
     * Encodes input for HTML, preserving whitespace.
     *
     * @param input
     * @param sb
     */
    public static void htmlWhite(String input, StringBuilder sb) {
        if (input == null) {
            return;
        }

        for (int c : input.codePoints().toArray()) {
            HtmlEncoder.htmlWhite(c, sb);
        }
    }

    public static void htmlWhiteLineBreaks(String input, StringBuilder sb) {
        if (input == null) {
            return;
        }

        for (int c : input.codePoints().toArray()) {
            HtmlEncoder.htmlWhiteLineBreaks(c, sb);
        }
    }

    public static void htmlWhite(int c, StringBuilder sb) {
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
                // Ranges a-z, A-Z, and 0-9 are allowed as-is
                if (((c >= 'a') && (c <= 'z'))
                        || ((c >= 'A') && (c <= 'Z'))
                        || ((c >= '0') && (c <= '9'))
                        || (c == CR)
                        || (c == LF)
                        || (c == ' ')
                        || (c == HTAB)) {
                    sb.append((char) c);
                } else {
                    // Make control characters visible
                    if (c < 32) {
                        sb.append("\\x");
                        HtmlEncoder.hex(c, sb);
                    } else {
                        // Encode everything else
                        sb.append("&#");
                        sb.append(Integer.toString(c));
                        sb.append(';');
                    }
                }
                break;
        }
    }

    public static void htmlWhiteLineBreaks(int c, StringBuilder sb) {
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
            case '\r':
                // Ignoring.
                break;
            case '\n':
                sb.append("<br>");
                break;
            default:
                // Ranges a-z, A-Z, and 0-9 are allowed as-is
                if (((c >= 'a') && (c <= 'z'))
                        || ((c >= 'A') && (c <= 'Z'))
                        || ((c >= '0') && (c <= '9'))
                        || (c == ' ')
                        || (c == HTAB)) {
                    sb.append((char) c);
                } else {
                    // Make control characters visible
                    if (c < 32) {
                        sb.append("\\x");
                        HtmlEncoder.hex(c, sb);
                    } else {
                        // Encode everything else
                        sb.append("&#");
                        sb.append(Integer.toString(c));
                        sb.append(';');
                    }
                }
                break;
        }
    }

    public static void hex(int c, StringBuilder sb) {
        sb.append(hexDigits[(c >> 4) & 0x0f]);
        sb.append(hexDigits[c & 0x0f]);
    }

    public static String asis(String input) {
        return input;
    }

    @Override
    public void setPage(Page page) {
        this.page = page;
    }

    public static String htmlAttr(String input) {
        return HtmlEncoder.html(input);
    }
}
