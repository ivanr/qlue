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

import java.nio.charset.StandardCharsets;
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

    /**
     * A leading scheme, if the value carries one: an ASCII letter followed by letters, digits and
     * {@code + - .}, up to the first colon. This is exactly the URL Standard's scheme grammar, and it
     * is the whole of what {@link #url(String)} inspects to decide whether the value is an absolute
     * URL or a relative reference. The match is case-insensitive because schemes are.
     */
    private static final Pattern SCHEME = Pattern.compile("^([a-zA-Z][a-zA-Z0-9+.\\-]*):");

    /**
     * The only schemes {@link #url(String)} will emit. Everything else — {@code javascript:},
     * {@code data:}, {@code vbscript:}, {@code view-source:} and every scheme nobody has registered
     * yet — is rejected to the empty string rather than pattern-matched for danger, so a scheme this
     * list has never heard of fails closed. {@code http} and {@code https} carry a hierarchical URL;
     * {@code mailto} is opaque and loads nothing into the page.
     */
    private static final java.util.Set<String> ALLOWED_SCHEMES =
            java.util.Set.of("http", "https", "mailto");

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

    // ------------------------------------------------------------------
    // URL encoding (R11 + R12)
    // ------------------------------------------------------------------
    //
    // The value handed here is whatever the template interpolated into a URL-bearing attribute, which
    // is not always a whole URL: it may be an absolute URL (<a href="$u">), a path prefix
    // (<a href="$base/x">), a query fragment (<a href="/search?q=$q">) or a bare fragment
    // (<a href="/p#$f">). The encoder cannot see the literal text around it, so it treats the value as
    // a URL reference in its own right and encodes each component the value contains by that
    // component's rules. A query fragment carries no scheme and no authority, so it is encoded as a
    // path/query/fragment and the template's own '/search?q=' is what keeps it on the page's origin.
    //
    // Three rules do all the work the old encoder got wrong (F15):
    //
    //   1. Percent-escaping is per UTF-8 BYTE, not per Java char. A non-Latin-1 code point becomes its
    //      UTF-8 bytes, each escaped, rather than a literal '?' (which used to turn a path into a
    //      query string) or a single Latin-1 byte.
    //   2. The value is split into scheme / authority / path / query / fragment and each keeps the
    //      delimiters that are structural in it: '&' and '=' survive in a query, ':' and '[' ']'
    //      survive in an authority so a port and an IPv6 literal are not destroyed.
    //   3. An existing '%XX' escape is passed through rather than re-escaped, so correctly
    //      pre-encoded input is not double-encoded.
    //
    // Rule 2 has a cost that is worth stating rather than discovering. Because the encoder cannot see
    // the literal text around the reference, it cannot tell "$u is a whole URL" from "$q is one query
    // parameter's value". Keeping a component's own delimiters is right for the first and permissive
    // for the second: in <a href="/search?q=$q"> a payload of "1&b=2" now emits "1&amp;b=2", which the
    // HTML parser decodes to "1&b=2", so the value adds a parameter to the template author's query
    // rather than staying inside one. The same was already true of '?', '#', '/' and '=' before this
    // rewrite; '&' joins them because collapsing a multi-parameter query into one parameter (F15b) is
    // the larger and far more common harm. Parameter injection is bounded - the origin and the path are
    // the template's, url() adds no authority, and R9's origin filter is unaffected - but a template
    // that interpolates into a query string and cares which parameters the URL carries must validate
    // the value, exactly as it would with '?' or '#'.
    //
    // The scheme separator is emitted from the parse (from ALLOWED_SCHEMES), never copied out of the
    // input, which is what closes F24 by design: the only raw colon this encoder can produce sits
    // immediately behind an allowlisted scheme name or inside such a URL's authority. A scheme that is
    // not on the allowlist is rejected to the empty string, so javascript:, data:, vbscript: and the
    // rest are neutralised by suppression rather than by escaping a single delimiter.

    /**
     * Encodes input string for output into a URL-bearing attribute value.
     *
     * @param input
     * @return
     */
    public static String url(String input) {
        if (input == null) {
            return null;
        }
        if (input.isEmpty()) {
            return "";
        }

        Matcher m = SCHEME.matcher(input);
        if (m.find()) {
            String scheme = m.group(1).toLowerCase();
            if (!ALLOWED_SCHEMES.contains(scheme)) {
                // Rejected: javascript:, data:, vbscript:, view-source: and everything unregistered.
                // Emitting nothing is the safe empty value Canoe writes for a suppressed reference.
                return "";
            }
            String rest = input.substring(m.end());
            StringBuilder sb = new StringBuilder(input.length() * 2);
            sb.append(scheme).append(':');
            if (scheme.equals("mailto")) {
                // Opaque: no authority, no path structure. Encode the whole tail as a query-like body
                // so an addressee, a '?subject=' and its '&' separators survive.
                appendEncoded(rest, sb, QUERY_SAFE);
            } else {
                // http/https: hierarchical. Only "scheme://" introduces an authority; "scheme:/x" and
                // "scheme:x" have none and are encoded as a path.
                if (rest.startsWith("//")) {
                    sb.append("//");
                    appendHierPart(rest.substring(2), sb, true);
                } else {
                    appendHierPart(rest, sb, false);
                }
            }
            return sb.toString();
        }

        // No scheme: a relative reference. A leading "//" still introduces an authority
        // (protocol-relative URL); anything else is a path, optionally with a query and fragment.
        StringBuilder sb = new StringBuilder(input.length() * 2);
        if (input.startsWith("//")) {
            sb.append("//");
            appendHierPart(input.substring(2), sb, true);
        } else {
            appendHierPart(input, sb, false);
        }
        return sb.toString();
    }

    /**
     * Encodes an authority (when {@code hasAuthority}) followed by a path, then a query and fragment.
     * The authority runs to the first {@code / ? #}; the path to the first {@code ? #}; the query to
     * the first {@code #}.
     *
     * <p>Colons are structural in an authority (a port, an IPv6 literal) but scheme-like at the head
     * of a relative path, so a path that has no authority in front of it escapes them — that is what
     * stops a bare {@code a:b} value looking like a scheme once it reaches the browser.
     */
    private static void appendHierPart(String s, StringBuilder sb, boolean hasAuthority) {
        int i = 0;
        if (hasAuthority) {
            int authEnd = s.length();
            for (int j = 0; j < s.length(); j++) {
                char c = s.charAt(j);
                if (c == '/' || c == '?' || c == '#') {
                    authEnd = j;
                    break;
                }
            }
            appendEncoded(s.substring(0, authEnd), sb, AUTHORITY_SAFE);
            i = authEnd;
        }

        int pathEnd = s.length();
        for (int j = i; j < s.length(); j++) {
            char c = s.charAt(j);
            if (c == '?' || c == '#') {
                pathEnd = j;
                break;
            }
        }
        // A path in front of an authority may hold colons (they follow the host); a bare relative
        // path must not, or its first segment reads as a scheme.
        appendEncoded(s.substring(i, pathEnd), sb, hasAuthority ? PATH_SAFE : RELATIVE_PATH_SAFE);
        i = pathEnd;

        if (i < s.length() && s.charAt(i) == '?') {
            int queryEnd = s.indexOf('#', i);
            if (queryEnd < 0) {
                queryEnd = s.length();
            }
            sb.append('?');
            appendEncoded(s.substring(i + 1, queryEnd), sb, QUERY_SAFE);
            i = queryEnd;
        }

        if (i < s.length() && s.charAt(i) == '#') {
            sb.append('#');
            appendEncoded(s.substring(i + 1), sb, QUERY_SAFE);
        }
    }

    /** Unreserved characters, safe in every component and never escaped. */
    private static boolean isUnreserved(int c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '-' || c == '.' || c == '_' || c == '~';
    }

    // The delimiter each component keeps. None includes a quote or a markup delimiter, so no url()
    // output can carry '<', '>', '"', '`' or '\'' — the property Canoe's state machine rests on. '@'
    // is escaped in every path so that a value concatenated after a scheme-and-host base (the
    // <a href="$base$path"> shape) cannot introduce userinfo and move the authority off-origin; it is
    // kept only in a query, where it is past the '?' and can start no authority.
    private static final String AUTHORITY_SAFE = "!$&()*+,;=:[]";
    private static final String PATH_SAFE = "!$&()*+,;=:/";
    private static final String RELATIVE_PATH_SAFE = "!$&()*+,;=/";
    private static final String QUERY_SAFE = "!$&()*+,;=:@/?";

    /**
     * Appends {@code s} percent-escaped per UTF-8 byte, passing through unreserved characters and the
     * component's own delimiters, and passing an existing {@code %XX} escape through untouched so
     * correctly encoded input is not encoded a second time.
     */
    private static void appendEncoded(String s, StringBuilder sb, String extraSafe) {
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < n && isHex(s.charAt(i + 1)) && isHex(s.charAt(i + 2))) {
                // Already encoded; keep it verbatim.
                sb.append('%').append(s.charAt(i + 1)).append(s.charAt(i + 2));
                i += 3;
                continue;
            }
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '&') {
                // '&' is a structural URL delimiter (a query separator) — it is on every component's
                // safe set — but it also starts an HTML character reference, and url()'s output is
                // written straight into the attribute with no second pass. Emitting it as &amp; keeps
                // it a separator for the URL parser once the HTML parser has decoded it, and stops an
                // input like &#106;avascript: being reconstituted into a scheme. This is the one place
                // url() must know it lands in HTML.
                sb.append("&amp;");
            } else if (isUnreserved(cp) || (cp < 0x80 && extraSafe.indexOf(cp) >= 0)) {
                sb.append((char) cp);
            } else {
                for (byte b : new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8)) {
                    sb.append('%');
                    HtmlEncoder.hex(b & 0xff, sb);
                }
            }
        }
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
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
