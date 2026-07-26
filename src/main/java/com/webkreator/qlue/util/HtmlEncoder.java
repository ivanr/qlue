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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
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

    // ------------------------------------------------------------------
    // Resource-loading URL encoding (R9)
    // ------------------------------------------------------------------
    //
    // url() is a scheme filter, not an origin filter (F6): it neutralises javascript: and data: but
    // emits //attacker/x.js and https://attacker/x.js byte for byte, because every character in an
    // off-origin URL is legal in an authority. That is harmless in <a href> and <img src> — an
    // off-origin link or image is an open-redirect or a referrer leak — but on <script src>,
    // <iframe src>, <object data>, <embed src>, <link href> and <base href> it is arbitrary code
    // execution or a whole-page hijack. Canoe knows the tag name now (R8), so it routes those six
    // element/attribute combinations here instead of to url().
    //
    // The policy this method enforces is the only one an encoder can enforce soundly at encode time:
    // Canoe does not know the deploying application's own origin, so "off-origin" cannot mean
    // "different from ours". It means "specifies an authority at all" — a protocol-relative //host or
    // an absolute scheme://host (or, for the special http/https schemes, the sloppy scheme:host and
    // scheme:/host forms a browser still reads as an authority). A relative reference (/path, path,
    // ?query, #frag) carries no authority and therefore cannot leave whatever origin the page is on,
    // so it is always allowed. An authority is allowed only when its host is on a configured
    // allowlist, for the CDN case; everything else is suppressed to the empty string, exactly as
    // Canoe suppresses a reference it will not encode.
    //
    // The authority is detected on url()'s OUTPUT rather than on the raw input, which is what makes
    // the check both sound and free of the tricks the reviewer flagged. url() has already escaped the
    // backslash a browser would read as a slash (/\host -> /%5Chost) and the '@' a userinfo trick
    // would hide a real host behind (a@b -> a%40b, a forbidden host code point), so those do not reach
    // a live authority and are not suppressed unnecessarily; and it has already rejected every
    // non-{http,https,mailto} scheme to the empty string. What survives with a live authority is
    // exactly //host, scheme://host and the special-scheme scheme:host / scheme:/host forms — and all
    // of those are detected here, including scheme:host, which an origin filter that only looked for
    // "//" would miss.

    /**
     * Encodes a value for a resource-loading URL attribute — {@code src} on {@code <script>},
     * {@code <iframe>} and {@code <embed>}, {@code data} on {@code <object>}, and {@code href} on
     * {@code <link>} and {@code <base>} — rejecting any value that introduces an authority whose host
     * is not on {@code allowedOrigins}.
     *
     * @param input          the value the template interpolated
     * @param allowedOrigins the hosts/origins a resource may be loaded from, or an empty list for
     *                       "same-origin-relative only"
     * @return the {@link #url(String)} encoding of the value when it is same-origin-relative or its
     *         authority is allowlisted; the empty string when it introduces a disallowed authority;
     *         {@code null} for a {@code null} input
     */
    public static String urlResource(String input, List<TrustedOrigin> allowedOrigins) {
        if (input == null) {
            return null;
        }
        String encoded = url(input);
        if (encoded.isEmpty()) {
            // Empty input, or a scheme url() rejected (javascript:, data:, ...): already suppressed.
            return encoded;
        }

        Authority authority = authorityOf(encoded);
        if (authority == null) {
            // Same-origin-relative, or an opaque scheme (mailto:) that loads nothing into the page.
            return encoded;
        }

        List<TrustedOrigin> origins = (allowedOrigins == null) ? Collections.emptyList() : allowedOrigins;
        for (TrustedOrigin origin : origins) {
            if (origin.matches(authority.scheme, authority.host, authority.port)) {
                return encoded;
            }
        }
        return "";
    }

    /**
     * The authority of an already-{@link #url(String)}-encoded value as a browser would resolve it, or
     * {@code null} when the value carries no authority a browser would act on (a relative reference, an
     * opaque {@code mailto:}, or an authority whose host fails to parse).
     *
     * <p>This follows the WHATWG URL parser closely enough to reproduce its answer on the shapes that
     * matter: the special {@code http}/{@code https} schemes introduce an authority after any number
     * of slashes — including none, so {@code http:host} is off-origin — and a scheme-less value
     * introduces one only after two or more. A host containing a forbidden code point after
     * percent-decoding makes the URL fail to parse, which is reported as "no live authority" rather
     * than as an off-origin host, so a value {@code url()} already neutralised is not double-counted as
     * a breach.
     *
     * <p>The input is {@code url()}'s own output, which never contains a raw backslash, a raw
     * userinfo {@code @} or a raw control character — {@code url()} percent-escapes every one — so this
     * parser does not carry the WHATWG parser's handling of those. A backslash a browser would read as
     * a slash is a {@code %5C} here (a same-origin path, not an authority separator), and a userinfo
     * {@code @} is a {@code %40} (a forbidden host code point, so the URL fails to parse). Both are the
     * same answer this parser gives, reached because {@code url()} already made the transformation.
     */
    private static Authority authorityOf(String encoded) {
        Matcher m = SCHEME.matcher(encoded);
        if (m.find()) {
            String scheme = m.group(1).toLowerCase();
            String rest = encoded.substring(m.end());
            if (scheme.equals("http") || scheme.equals("https")) {
                int i = 0;
                while (i < rest.length() && rest.charAt(i) == '/') {
                    i++;
                }
                return parseAuthority(scheme, rest.substring(i));
            }
            // mailto: and any other opaque allowed scheme carry no authority.
            return null;
        }

        int run = 0;
        while (run < encoded.length() && encoded.charAt(run) == '/') {
            run++;
        }
        if (run >= 2) {
            return parseAuthority(null, encoded.substring(run));
        }
        return null;
    }

    /**
     * Parses the host and port out of the authority-and-onwards text {@code s}, stopping at the first
     * {@code / ? #}, and returns {@code null} when the host would make the URL fail to parse.
     */
    private static Authority parseAuthority(String scheme, String s) {
        int end = s.length();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                end = i;
                break;
            }
        }
        String hostAndPort = s.substring(0, end);

        // A bracketed IPv6 literal is recognised before the forbidden-code-point check, because '[',
        // ']' and ':' are forbidden in a host outside brackets and are the address inside them.
        if (hostAndPort.startsWith("[")) {
            int close = hostAndPort.indexOf(']');
            if (close < 0) {
                return null;
            }
            String host = hostAndPort.substring(0, close + 1);
            String after = hostAndPort.substring(close + 1);
            int port = -1;
            if (after.startsWith(":") && allDigits(after.substring(1))) {
                port = parsePort(after.substring(1));
            } else if (!after.isEmpty()) {
                return null;
            }
            return new Authority(scheme, host, port);
        }

        String host = hostAndPort;
        int port = -1;
        int colon = hostAndPort.lastIndexOf(':');
        if (colon >= 0 && allDigits(hostAndPort.substring(colon + 1))) {
            port = parsePort(hostAndPort.substring(colon + 1));
            host = hostAndPort.substring(0, colon);
        }

        String decoded = percentDecode(host);
        if (decoded.isEmpty() || hasForbiddenHostChar(decoded)) {
            return null;
        }
        return new Authority(scheme, decoded, port);
    }

    private static boolean allDigits(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    private static int parsePort(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean hasForbiddenHostChar(String host) {
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c < 0x20 || c == 0x7F || "\t\n\r #/:<>?@[\\]^|%".indexOf(c) >= 0) {
                return true;
            }
        }
        return false;
    }

    private static String percentDecode(String input) {
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '%' && i + 2 < input.length()
                    && isHex(input.charAt(i + 1)) && isHex(input.charAt(i + 2))) {
                sb.append((char) Integer.parseInt(input.substring(i + 1, i + 3), 16));
                i += 2;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** A parsed authority: the scheme (null for a protocol-relative URL), host and port (-1 if none). */
    private static final class Authority {
        final String scheme;
        final String host;
        final int port;

        Authority(String scheme, String host, int port) {
            this.scheme = scheme;
            this.host = host;
            this.port = port;
        }
    }

    /**
     * A host, or scheme-and-host(-and-port), a resource may be loaded from — the parsed form of one
     * entry on {@link #urlResource(String, List)}'s allowlist. A bare {@code cdn.example.com} matches
     * that host under any allowed scheme and port; {@code https://cdn.example.com} pins the scheme;
     * {@code https://cdn.example.com:8443} pins the port too.
     */
    public static final class TrustedOrigin {

        private final String scheme;
        private final String host;
        private final int port;

        private TrustedOrigin(String scheme, String host, int port) {
            this.scheme = scheme;
            this.host = host;
            this.port = port;
        }

        /**
         * Whether a value's authority — {@code valueScheme} may be null for a protocol-relative URL,
         * which inherits the page's scheme — is one this origin permits.
         */
        boolean matches(String valueScheme, String valueHost, int valuePort) {
            if (!host.equalsIgnoreCase(valueHost)) {
                return false;
            }
            if (scheme != null && valueScheme != null && !scheme.equals(valueScheme)) {
                return false;
            }
            return port < 0 || port == valuePort;
        }

        /**
         * Parses one allowlist entry: a bare host, or {@code scheme://host}, either optionally with a
         * {@code :port}. A path, a userinfo {@code @}, an unsupported scheme or an empty host is
         * rejected, so a misconfiguration fails at startup rather than silently matching nothing.
         */
        public static TrustedOrigin parse(String raw) {
            if (raw == null) {
                throw new IllegalArgumentException("A trusted resource origin cannot be null");
            }
            String s = raw.trim();
            if (s.isEmpty()) {
                throw new IllegalArgumentException("A trusted resource origin cannot be empty");
            }

            String scheme = null;
            String authority = s;
            int schemeIdx = s.indexOf("://");
            if (schemeIdx >= 0) {
                scheme = s.substring(0, schemeIdx).toLowerCase();
                if (!scheme.equals("http") && !scheme.equals("https")) {
                    throw new IllegalArgumentException("A trusted resource origin's scheme must be"
                            + " http or https: " + raw);
                }
                authority = s.substring(schemeIdx + 3);
            }

            int slash = authority.indexOf('/');
            if (slash >= 0) {
                throw new IllegalArgumentException("A trusted resource origin must be a host or an"
                        + " origin, with no path: " + raw);
            }
            if (authority.indexOf('@') >= 0) {
                throw new IllegalArgumentException("A trusted resource origin must not carry"
                        + " userinfo: " + raw);
            }

            if (authority.startsWith("[")) {
                int close = authority.indexOf(']');
                if (close < 0) {
                    throw new IllegalArgumentException("Unterminated IPv6 literal in trusted resource"
                            + " origin: " + raw);
                }
                String host = authority.substring(0, close + 1).toLowerCase();
                String after = authority.substring(close + 1);
                int port = -1;
                if (after.startsWith(":")) {
                    port = requirePort(after.substring(1), raw);
                } else if (!after.isEmpty()) {
                    throw new IllegalArgumentException("Unexpected text after IPv6 literal in trusted"
                            + " resource origin: " + raw);
                }
                // The brackets, colons and hex of an IPv6 literal are the address, not forbidden host
                // code points, so it is not put through hasForbiddenHostChar.
                return new TrustedOrigin(scheme, host, port);
            }

            String host;
            int port = -1;
            int colon = authority.lastIndexOf(':');
            if (colon >= 0) {
                port = requirePort(authority.substring(colon + 1), raw);
                host = authority.substring(0, colon);
            } else {
                host = authority;
            }

            host = host.toLowerCase();
            if (host.isEmpty()) {
                throw new IllegalArgumentException("A trusted resource origin must name a host: " + raw);
            }
            if (hasForbiddenHostChar(host)) {
                throw new IllegalArgumentException("A trusted resource origin host contains an illegal"
                        + " character: " + raw);
            }
            return new TrustedOrigin(scheme, host, port);
        }

        private static int requirePort(String digits, String raw) {
            if (!allDigits(digits)) {
                throw new IllegalArgumentException("A trusted resource origin's port must be numeric: "
                        + raw);
            }
            int port = parsePort(digits);
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException("A trusted resource origin's port is out of range: "
                        + raw);
            }
            return port;
        }

        @Override
        public String toString() {
            return (scheme == null ? "" : scheme + "://") + host + (port < 0 ? "" : ":" + port);
        }
    }

    /**
     * Parses and validates a collection of trusted-resource-origin strings into an immutable list,
     * once at configuration time so a bad entry fails at startup. A {@code null} collection is treated
     * as empty.
     */
    public static List<TrustedOrigin> parseTrustedOrigins(Collection<String> raw) {
        List<TrustedOrigin> result = new ArrayList<>();
        if (raw == null) {
            return Collections.unmodifiableList(result);
        }
        for (String entry : raw) {
            if (entry == null || entry.trim().isEmpty()) {
                continue;
            }
            result.add(TrustedOrigin.parse(entry));
        }
        return Collections.unmodifiableList(result);
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
