package com.webkreator.qlue.view.canoe.corpus;

import com.webkreator.qlue.view.canoe.CanoeTestSupport;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Derives the observed verdict for a (case, payload) pairing independently of what the ledger
 * records, so that a wrong entry fails instead of sitting in the corpus as unasserted data.
 *
 * <p>Without this, the corpus is a pile of opinions. The first review of the seeded corpus found
 * three wrong verdicts among fourteen cases, none of which any test would have caught.
 *
 * <p>The judgement is deliberately conservative: it asks whether the attacker's value arrives at the
 * consuming parser intact, not whether some clever payload might. Under-reporting is caught by the
 * browser tier, which observes effects rather than strings; over-reporting would be worse, because
 * it would flag correct behaviour as a vulnerability and train readers to ignore the suite.
 */
public final class VerdictEvaluator {

    /** The host the rendered page is treated as being served from. */
    public static final String BASE_HOST = "app.example";

    private static final Pattern SCHEME =
            Pattern.compile("^([a-zA-Z][a-zA-Z0-9+.\\-]*):");

    /** The scheme the rendered page is treated as being served over. */
    public static final String BASE_SCHEME = "https";

    /**
     * The page's own origin, as the browser computes it: scheme, host <em>and port</em>.
     *
     * <p>A same-origin test that compares only the host is not a same-origin test. It calls
     * {@code https://app.example:8443/x} the page's own origin, when a different port is a different
     * origin under every same-origin rule a browser applies, and it calls {@code http://app.example/x}
     * the page's own origin, when that is an active TLS downgrade on an {@code https:} page. Both are
     * exactly the "false safe" this class exists to prevent, so the comparison below is against this
     * whole string.
     */
    public static final String BASE_ORIGIN = BASE_SCHEME + "://" + BASE_HOST;

    /** Schemes that execute script or carry their own document. */
    private static final Set<String> SCRIPT_BEARING_SCHEMES = new HashSet<>(Arrays.asList(
            "javascript", "vbscript", "livescript", "mocha", "data", "blob", "filesystem",
            "view-source"));

    /**
     * The only schemes this oracle is willing to call harmless. Anything not listed here is reported
     * as dangerous, including schemes nobody has thought of yet.
     *
     * <p>This is an allowlist on purpose. The obvious alternative — call anything outside
     * {@link #SCRIPT_BEARING_SCHEMES} safe — is a denylist of eight names wearing an allowlist's
     * clothes, and it quietly laundered {@code ftp://attacker.invalid/x} and
     * {@code file:///etc/passwd} into {@link Verdict#SAFE} corpus entries. A scheme registered after
     * this file was written must fail loud rather than quiet.
     *
     * <p>The rule the list encodes is narrower than "leaving the origin is dangerous", because
     * {@code mailto:} and {@code tel:} do leave the origin and are still on it. The rule is: a URL
     * sink is dangerous when the attacker can make the browser <em>load content into the page, or
     * navigate it, at an origin the page does not control</em>. {@code http:} and {@code https:} are
     * judged by origin below rather than by scheme. {@code mailto:} and {@code tel:} hand the value
     * to an external handler and load nothing — they are a privacy and phishing problem, not an XSS,
     * and this suite's subject is XSS. They stay on the list with that stated reason rather than with
     * the origin argument, which does not cover them. Everything else is dangerous by default.
     */
    private static final Set<String> SAFE_SCHEMES = new HashSet<>(Arrays.asList(
            "http", "https", "mailto", "tel"));

    /**
     * The WHATWG <em>forbidden host code point</em> set, written out. A host containing any of these
     * after percent-decoding makes the URL fail to parse, so the browser never issues a request.
     *
     * <p>The set the parser actually applies to a domain is the <em>forbidden domain code point</em>
     * set, which is this list <strong>plus every C0 control plus U+007F</strong>. Those are not
     * listed here because they are a contiguous range; {@link #isForbiddenInHost(char)} adds them.
     * Leaving them out is how {@code //at%01tacker.invalid/x} and {@code //at%7Ftacker.invalid/x}
     * came to be reported as reaching an off-origin host when Node refuses to parse them at all.
     *
     * <p>{@code '\0'} is written as an escape rather than as a literal NUL byte on purpose. A raw NUL
     * in the source makes {@code file} report this file as {@code data}, makes {@code git diff} render
     * it as {@code Bin 0 -> N bytes} and makes {@code git grep} skip it entirely — in the one file
     * whose whole premise is that a wrong verdict must be visible to a reviewer.
     *
     * <p>{@code %} belongs on this list and the decode-then-check order is deliberate: the standard
     * percent-decodes the host <em>before</em> applying the test, and {@code %} is itself forbidden.
     * That is why {@code https://trusted.example%40attacker.invalid/x.js} and
     * {@code https://host%3A8443/path} genuinely fail to parse rather than resolving to
     * {@code trusted.example@attacker.invalid} and {@code host:8443}. Confirmed against Node's
     * WHATWG URL parser.
     */
    private static final Set<Character> FORBIDDEN_HOST_CHARS = new HashSet<>(Arrays.asList(
            '\0', '\t', '\n', '\r', ' ', '#', '/', ':', '<', '>', '?', '@', '[', '\\', ']',
            '^', '|', '%'));

    /**
     * Whether a code point in a decoded host makes the URL fail to parse. The C0 range and U+007F are
     * tested here rather than enumerated in {@link #FORBIDDEN_HOST_CHARS}; see that field's note.
     */
    private static boolean isForbiddenInHost(char c) {
        return c < 0x20 || c == 0x7F || FORBIDDEN_HOST_CHARS.contains(c);
    }

    /** Default ports for the special schemes this oracle understands, per the URL Standard. */
    private static final int HTTPS_DEFAULT_PORT = 443;

    private static final int HTTP_DEFAULT_PORT = 80;

    /** The highest port the URL parser accepts; above this the URL fails to parse. */
    private static final int MAX_PORT = 65535;

    /** Sentinel for a port that is all digits but outside the range the parser accepts. */
    private static final int PORT_OUT_OF_RANGE = -1;

    private VerdictEvaluator() {
    }

    /**
     * Renders a case with the given value bound to its payload reference.
     */
    public static CanoeTestSupport.RenderResult render(XssCase testCase, String value) {
        Map<String, Object> model = new LinkedHashMap<>(testCase.extraModel());
        model.put(testCase.referenceName(), value);
        return CanoeTestSupport.render(testCase.template(), model);
    }

    /**
     * Observes what actually happens, ignoring what the ledger claims.
     */
    public static Observation observe(XssCase testCase, Payload payload) {
        CanoeTestSupport.RenderResult attacked = render(testCase, payload.value());

        if (attacked.isError()) {
            return new Observation(Verdict.REJECTED, attacked, null,
                    "Canoe raised: " + attacked.errorMessage());
        }

        CanoeTestSupport.RenderResult empty = render(testCase, "");
        if (attacked.output().equals(empty.output())) {
            // Observation cannot tell design from accident; the ledger records the intent and
            // matches() accepts either suppression verdict.
            return new Observation(Verdict.SUPPRESSED_BY_DESIGN, attacked, "",
                    "output is identical to rendering with an empty value");
        }

        String sinkValue = extractSink(testCase, attacked);

        switch (testCase.sink()) {
            case HTML_TEXT:
            case PLAIN_TEXT_ATTR:
                return judgeStructurally(testCase, payload, attacked);

            case JAVASCRIPT:
                return judgeAsJavaScript(payload, attacked, sinkValue);

            case CSS:
            case MARKUP:
                return judgeByVerbatimArrival(payload, attacked, sinkValue);

            case POLICY:
                return judgeAsPolicy(testCase, payload, attacked, sinkValue);

            case REFRESH:
                return judgeAsRefresh(attacked, sinkValue);

            case URL:
                return judgeAsUrl(attacked, sinkValue);

            case NONE:
            default:
                return new Observation(Verdict.SAFE, attacked, sinkValue,
                        "no sink to reach");
        }
    }

    /**
     * For text and plain-text attributes the payload arriving intact is expected and harmless — it
     * is text. What matters is whether it changed the shape of the document, which is the generic
     * injection oracle: it needs no opinion about which characters are dangerous.
     */
    private static Observation judgeStructurally(XssCase testCase, Payload payload,
                                                 CanoeTestSupport.RenderResult attacked) {
        CanoeTestSupport.RenderResult benign = render(testCase, Payloads.INERT_MARKER.value());
        String attackedShape = domSkeleton(attacked.dom());
        String benignShape = domSkeleton(benign.dom());

        if (attackedShape.equals(benignShape)) {
            return new Observation(Verdict.SAFE, attacked, attackedShape,
                    "document structure is identical to the benign render");
        }
        return new Observation(Verdict.KNOWN_VULNERABLE, attacked, attackedShape,
                "document structure diverged from the benign render: expected " + benignShape
                        + " but got " + attackedShape);
    }

    /**
     * The tokens each policy attribute's algorithm actually acts on.
     *
     * <p>This table is what makes a {@link SinkKind#POLICY} judgement a judgement rather than a
     * substring test. &sect;2.1 defines {@link Verdict#KNOWN_VULNERABLE} as attacker data reaching the
     * sink <em>live</em>, and for a directive "live" means the browser recognises the token, not
     * merely that the bytes arrived. Before this existed the policy cross-product handed all three
     * {@code POLICY_OVERRIDE} payloads to every policy attribute and recorded every pairing as a
     * vulnerability, including {@code sandbox="_blank"} — an unknown sandbox token, which leaves the
     * sandbox maximally restrictive and is the opposite of an escape.
     *
     * <p>{@code integrity} is deliberately absent and handled separately; see
     * {@link #integrityIsLive}.
     */
    private static final Map<String, Set<String>> POLICY_TOKENS = policyTokens();

    private static Map<String, Set<String>> policyTokens() {
        Map<String, Set<String>> tokens = new LinkedHashMap<>();
        tokens.put("sandbox", new HashSet<>(Arrays.asList(
                "allow-downloads", "allow-forms", "allow-modals", "allow-orientation-lock",
                "allow-pointer-lock", "allow-popups", "allow-popups-to-escape-sandbox",
                "allow-presentation", "allow-same-origin", "allow-scripts",
                "allow-top-navigation", "allow-top-navigation-by-user-activation")));
        tokens.put("rel", new HashSet<>(Arrays.asList(
                "alternate", "author", "bookmark", "canonical", "dns-prefetch", "external", "help",
                "icon", "license", "manifest", "modulepreload", "next", "nofollow", "noopener",
                "noreferrer", "opener", "pingback", "preconnect", "prefetch", "preload",
                "prerender", "prev", "search", "stylesheet", "tag")));
        return Collections.unmodifiableMap(tokens);
    }

    /**
     * A policy attribute is live when the payload arrives verbatim <em>and</em> the value carries a
     * token the browser's algorithm recognises.
     *
     * <p>{@code nonce} is the one attribute with no token vocabulary: the whole value <em>is</em> the
     * directive, so any non-empty value that survives is live by construction. That is what makes it
     * strictly stronger than {@code target}, which this suite does not classify as a policy sink at
     * all — an attacker who chooses the CSP nonce can author a {@code <script nonce>} that the policy
     * then admits, which defeats the control rather than redirecting a navigation.
     */
    private static Observation judgeAsPolicy(XssCase testCase, Payload payload,
                                             CanoeTestSupport.RenderResult attacked,
                                             String sinkValue) {
        if (!sinkValue.contains(payload.value())) {
            return new Observation(Verdict.SAFE, attacked, sinkValue,
                    "the payload does not survive into the policy directive intact");
        }

        String attribute = testCase.attribute();
        if ("nonce".equals(attribute)) {
            return new Observation(Verdict.KNOWN_VULNERABLE, attacked, sinkValue,
                    "the whole value is the directive, so any value that arrives is the nonce the"
                            + " content security policy will admit");
        }
        if ("integrity".equals(attribute)) {
            return integrityIsLive(sinkValue)
                    ? new Observation(Verdict.KNOWN_VULNERABLE, attacked, sinkValue,
                            "the value carries a parseable hash expression, so subresource integrity"
                                    + " acts on the attacker's digest")
                    : new Observation(Verdict.SAFE, attacked, sinkValue,
                            "no token parses as a hash expression, so the metadata set is empty and"
                                    + " the integrity check passes vacuously - the bytes arrived and"
                                    + " nothing acted on them");
        }

        Set<String> known = POLICY_TOKENS.get(attribute);
        if (known == null) {
            throw new AssertionError("Case " + testCase.id() + " declares a POLICY sink on attribute "
                    + attribute + ", which VerdictEvaluator has no token vocabulary for. Add one"
                    + " rather than letting the judgement fall back to a substring test.");
        }
        for (String token : sinkValue.trim().split("\\s+")) {
            if (known.contains(token.toLowerCase())) {
                return new Observation(Verdict.KNOWN_VULNERABLE, attacked, sinkValue,
                        "the directive carries the token " + token + ", which the " + attribute
                                + " algorithm acts on");
            }
        }
        return new Observation(Verdict.SAFE, attacked, sinkValue,
                "the payload arrives verbatim, but no token in it is one the " + attribute
                        + " algorithm recognises, so nothing acts on it");
    }

    /**
     * Whether an {@code integrity} value carries anything subresource integrity will act on.
     *
     * <p>SRI parses the attribute into a metadata set of {@code <algorithm>-<base64>} expressions and
     * discards tokens it cannot parse. If the resulting set is <em>empty</em> the check returns true
     * unconditionally — an unparseable {@code integrity} attribute is not a failing digest, it is no
     * digest at all, and the resource loads normally. So none of the three policy tokens is live
     * here; the value reaches the algorithm and the algorithm ignores it.
     */
    private static boolean integrityIsLive(String value) {
        for (String token : value.trim().split("\\s+")) {
            int dash = token.indexOf('-');
            if (dash < 0) {
                continue;
            }
            String algorithm = token.substring(0, dash).toLowerCase();
            if ((algorithm.equals("sha256") || algorithm.equals("sha384") || algorithm.equals("sha512"))
                    && dash + 1 < token.length()) {
                return true;
            }
        }
        return false;
    }

    /**
     * For CSS and markup sinks, the question is whether the attacker's characters reach the consumer
     * after the HTML parser has decoded character references. In both cases the consumer is a second
     * parser and verbatim arrival is the whole story.
     */
    private static Observation judgeByVerbatimArrival(Payload payload,
                                                      CanoeTestSupport.RenderResult attacked,
                                                      String sinkValue) {
        if (sinkValue.contains(payload.value())) {
            return new Observation(Verdict.KNOWN_VULNERABLE, attacked, sinkValue,
                    "the payload arrives at the sink verbatim once character references are decoded");
        }
        return new Observation(Verdict.SAFE, attacked, sinkValue,
                "the payload does not survive into the sink intact");
    }

    /**
     * The characters a value must carry to leave the JavaScript string literal a template spliced it
     * into: the three quote marks and the escape character. Everything else — {@code (}, {@code )},
     * {@code ;}, an identifier, a keyword — is inert until the string has been closed, so the quote
     * is the whole gate.
     *
     * <p>The plan's &sect;5.1 names a wider set, {@code ' " ( ) ; \}, which was written as "the
     * attacker's syntactically significant characters" and is right about significance and wrong
     * about sufficiency: {@code ENTITY_PRE_ENCODED} carries {@code ;} in every one of its character
     * references and cannot escape anything.
     */
    private static final String JS_STRING_ESCAPES = "'\"`\\";

    /**
     * A JavaScript sink is live when the payload arrives verbatim <em>and</em> carries a character
     * that can close the string literal it landed in.
     *
     * <p>Verbatim arrival alone is not enough here, and the distinction is not academic: {@code
     * ENTITY_PRE_ENCODED} is already spelled as character references, so {@code html()} escapes its
     * ampersands, the parser decodes exactly once, and what reaches the JavaScript parser is the
     * literal text {@code &#39;&#41;...} sitting harmlessly inside the string literal the template
     * opened. The bytes survived; nothing escaped. Reporting that as a vulnerability would be the
     * over-report this class's javadoc warns about, and it would also destroy the case's value —
     * the point of pairing that payload with {@code QUOTE_BREAKOUT} in the same handler is that one
     * escapes and the other does not, which is the evidence that the parser decodes once rather than
     * twice.
     *
     * <p>Two limitations, stated rather than implied, and both in the conservative direction:
     *
     * <ul>
     *   <li><strong>Not quote-aware.</strong> A double-quote payload inside a single-quoted string
     *       literal cannot actually escape it, and this rule still calls it live. That is deliberate:
     *       on a JavaScript sink, over-reporting costs a reviewer one look and under-reporting hides
     *       script execution, and every {@link Verdict#KNOWN_VULNERABLE} row on a JavaScript sink in
     *       this corpus cites a finding whose exploit uses the matching quote.
     *   <li><strong>Assumes a string literal.</strong> A reference spliced <em>outside</em> one —
     *       {@code onclick="f($data)"} — needs no quote to be live, because a bare identifier is
     *       already an expression. No corpus case has that shape; if one is added, this rule has to
     *       be told about it or that case has to carry its verdict by hand.
     * </ul>
     */
    private static Observation judgeAsJavaScript(Payload payload,
                                                 CanoeTestSupport.RenderResult attacked,
                                                 String sinkValue) {
        String scriptSource = scriptSourceOf(sinkValue);
        boolean throughAJavascriptUrl = !scriptSource.equals(sinkValue);

        if (!scriptSource.contains(payload.value())) {
            return new Observation(Verdict.SAFE, attacked, sinkValue,
                    "the payload does not survive into the JavaScript sink intact");
        }
        for (int i = 0; i < JS_STRING_ESCAPES.length(); i++) {
            if (payload.value().indexOf(JS_STRING_ESCAPES.charAt(i)) >= 0) {
                return new Observation(Verdict.KNOWN_VULNERABLE, attacked, sinkValue,
                        "the payload arrives at the JavaScript parser verbatim once "
                                + (throughAJavascriptUrl
                                        ? "the javascript: URL's percent-escapes are"
                                        : "character references are")
                                + " decoded, carrying " + JS_STRING_ESCAPES.charAt(i));
            }
        }
        return new Observation(Verdict.SAFE, attacked, sinkValue,
                "the payload arrives verbatim, but it carries no quote and no backslash, so it"
                        + " cannot close the JavaScript string literal it was placed in");
    }

    /**
     * The text a {@code javascript:} URL actually compiles, which is <em>not</em> the attribute
     * value: the HTML Standard's javascript-URL steps percent-decode the part after the scheme
     * before handing it to the JavaScript parser ("let scriptSource be the UTF-8 decoding of the
     * percent-decoding of encodedScriptSource"). For anything that is not a {@code javascript:} URL
     * the value is returned unchanged, so the ordinary handler-body case is untouched.
     *
     * <p>Without this, a percent-escaped payload inside a {@code javascript:} href reads as SAFE —
     * the exact "false safe" this class's javadoc says it must not have. It became reachable when
     * R2 deleted {@code detectAttributePrefix()}'s reset: a template whose {@code javascript:}
     * prefix was missed for some other reason (F5's buffer residue) kept the name-derived
     * {@code ATTR_URI} and was encoded with {@code url()} rather than with {@code html()}, so the
     * attacker's quote arrived as {@code %27} instead of as {@code &#39;} — and both decoders ran.
     * The URL oracle already judges {@code data:} the same way, by scheme rather than by whether the
     * bytes happen to be escaped; this makes the JavaScript sink agree with it.
     *
     * <p>R3 closed F5, so the two invocations that reached this decode — {@code
     * residue.js-url-armed-buffer}'s pair — are suppressed now and no corpus row exercises it. It
     * stays, for the same reason it was added: the rule is right whether or not a case currently
     * needs it, and the way it would be needed again is a {@code javascript:} URL that reaches
     * {@code url()} rather than {@code CTX_JS}, which is one routing mistake away in either
     * direction. {@code BufferResidueTest} applies the identical decode by hand at the sink, so the
     * claim is asserted somewhere a reader will meet it.
     *
     * <p>Deliberately restricted to {@code javascript:}. {@code vbscript:}, {@code livescript:} and
     * {@code mocha:} have no specification and no shipping implementation, so there is no algorithm
     * to point at and nothing that would run the decoded text.
     */
    private static String scriptSourceOf(String sinkValue) {
        String url = removeTabsAndNewlines(stripLeadingAndTrailingC0AndSpace(sinkValue));
        if (!url.regionMatches(true, 0, "javascript:", 0, "javascript:".length())) {
            return sinkValue;
        }
        return percentDecode(url);
    }

    /**
     * {@code <meta http-equiv=refresh content>} carries a delay and a URL in one value, so the URL
     * has to be cut out of it before the URL oracle can see it.
     *
     * <p>This used to be {@code sinkValue.contains(SENTINEL_HOST)} — the only sink in the corpus
     * judged by a host substring match, and therefore the only one that would call
     * {@code 0;url=javascript:__canoePwned('u')} safe. Every other sink is judged by a rule; this one
     * was judged by the one payload that happened to be in the corpus.
     *
     * <p>The grammar followed is the HTML Standard's: optional whitespace, a time, then optionally
     * {@code ;} or {@code ,}, optional whitespace, an optional {@code url} keyword and {@code =}, and
     * the URL, which may be wrapped in matching quotes. A value that does not fit that shape is
     * handed to {@link #analyseUrl} whole rather than declared safe, because this oracle's errors are
     * not symmetric: over-reporting costs a reviewer one look, under-reporting hides a redirect.
     */
    private static Observation judgeAsRefresh(CanoeTestSupport.RenderResult attacked,
                                              String sinkValue) {
        String target = refreshTarget(sinkValue);
        UrlAnalysis analysis = analyseUrl(target);
        Verdict verdict = analysis.dangerous ? Verdict.KNOWN_VULNERABLE : Verdict.SAFE;
        return new Observation(verdict, attacked, sinkValue,
                "the refresh target " + CanoeTestSupport.quote(target) + " is a "
                        + analysis.explanation());
    }

    /** The URL portion of a {@code refresh} content value, or the whole value if it has no delay. */
    static String refreshTarget(String content) {
        int i = 0;
        while (i < content.length() && content.charAt(i) <= ' ') {
            i++;
        }
        int digitsStart = i;
        while (i < content.length()
                && (Character.isDigit(content.charAt(i)) || content.charAt(i) == '.')) {
            i++;
        }
        if (i == digitsStart) {
            // No time at all, so this does not match the grammar. Judge the whole value.
            return content;
        }
        while (i < content.length() && content.charAt(i) <= ' ') {
            i++;
        }
        if (i >= content.length() || (content.charAt(i) != ';' && content.charAt(i) != ',')) {
            // A bare time reloads the current page; there is no URL to reach anywhere.
            return "";
        }
        i++;
        while (i < content.length() && content.charAt(i) <= ' ') {
            i++;
        }
        if (content.regionMatches(true, i, "url", 0, 3)) {
            int afterKeyword = i + 3;
            while (afterKeyword < content.length() && content.charAt(afterKeyword) <= ' ') {
                afterKeyword++;
            }
            if (afterKeyword < content.length() && content.charAt(afterKeyword) == '=') {
                i = afterKeyword + 1;
                while (i < content.length() && content.charAt(i) <= ' ') {
                    i++;
                }
            }
        }
        String url = content.substring(i);
        if (url.length() >= 2 && (url.charAt(0) == '"' || url.charAt(0) == '\'')
                && url.charAt(url.length() - 1) == url.charAt(0)) {
            url = url.substring(1, url.length() - 1);
        }
        return url;
    }

    private static Observation judgeAsUrl(CanoeTestSupport.RenderResult attacked, String sinkValue) {
        UrlAnalysis analysis = analyseUrl(sinkValue);
        Verdict verdict = analysis.dangerous ? Verdict.KNOWN_VULNERABLE : Verdict.SAFE;
        return new Observation(verdict, attacked, sinkValue, analysis.explanation);
    }

    // ------------------------------------------------------------------
    // URL analysis
    // ------------------------------------------------------------------

    /**
     * Decides whether a URL, as the browser would see it, leaves the page's origin or executes
     * script, resolved against {@code https://app.example/dir/page}.
     *
     * <p>This method is the oracle the whole verdict ledger depends on for URL sinks, so its errors
     * are not symmetric. A false "dangerous" flags correct behaviour and gets noticed; a false "safe"
     * launders a real vulnerability into a {@link Verdict#SAFE} corpus entry that nobody will look at
     * again. Every divergence found so far pointed the second way, so the model below follows the
     * WHATWG URL Standard's basic URL parser closely enough to reproduce its answers on the cases
     * that matter, and each rule below was checked against Node's implementation.
     *
     * <p>Three of those rules are counter-intuitive and are the reason a hand-rolled "does it start
     * with {@code //}" test is not good enough:
     *
     * <ul>
     *   <li><strong>Backslash is a slash.</strong> For a special scheme the parser treats {@code \}
     *       exactly as {@code /}, so {@code /\attacker.invalid/x} and {@code \\attacker.invalid/x}
     *       are protocol-relative URLs reaching {@code attacker.invalid}, not same-origin paths.
     *   <li><strong>Tab, LF and CR are removed from everywhere</strong> in the input before parsing,
     *       not merely trimmed from the ends. That is what makes {@code java<LF>script:alert(1)} a
     *       live {@code javascript:} URL.
     *   <li><strong>The number of slashes is not fixed.</strong> After a scheme that differs from the
     *       page's own, any number of separators — including none — introduces an authority, so
     *       {@code http:/attacker.invalid/x} and even {@code http:attacker.invalid/x} reach the
     *       attacker's host.
     * </ul>
     *
     * <p>A fourth rule is not counter-intuitive so much as easy to skip: <strong>an origin is a
     * scheme, a host <em>and</em> a port</strong>, and all three are compared. See
     * {@link #BASE_ORIGIN}.
     *
     * <p>Percent-escapes are deliberately <em>not</em> decoded before structural analysis:
     * {@code /%5Cattacker.invalid} is a same-origin path, not a protocol-relative URL, because no
     * browser un-escapes {@code %5C} back into a path separator. Decoding happens only inside the
     * host, where the standard does it too.
     *
     * <h2>Known conservative gaps</h2>
     *
     * <p>Both of these make the oracle answer "dangerous" where a browser answers "same origin", so
     * both fail in the direction a reviewer notices. They are recorded rather than fixed.
     *
     * <ul>
     *   <li><strong>No IDNA mapping.</strong> The standard runs the host through IDNA ToASCII, which
     *       applies Unicode case folding, NFC normalisation and the mapping table before comparing.
     *       This method does none of that, so {@code //app<U+FF0E>example/x} (fullwidth full stop) and
     *       {@code //app.example<U+00AD>/x} (a soft hyphen, which IDNA deletes) are reported as
     *       off-origin hosts where Node resolves both to {@code https://app.example}. ASCII case is
     *       handled, which covers everything the corpus generates.
     *   <li><strong>No IPv6 validation.</strong> The bracketed-literal branch in
     *       {@link #judgeAuthority} recognises brackets; it does not parse what is between them. See
     *       that method.
     * </ul>
     */
    public static UrlAnalysis analyseUrl(String rawValue) {
        String value = removeTabsAndNewlines(stripLeadingAndTrailingC0AndSpace(rawValue));

        if (value.isEmpty()) {
            return new UrlAnalysis(false, "empty URL");
        }

        Matcher scheme = SCHEME.matcher(value);
        if (scheme.find()) {
            String name = scheme.group(1).toLowerCase();
            String afterScheme = value.substring(scheme.end());

            if (SCRIPT_BEARING_SCHEMES.contains(name)) {
                return new UrlAnalysis(true, "resolves to a " + name + ": URL");
            }
            if (name.equals("http") || name.equals("https")) {
                int authority = authorityStartAfterScheme(name, afterScheme);
                if (authority < 0) {
                    return new UrlAnalysis(false, name + ": URL with no authority, so it resolves"
                            + " against the page's own origin");
                }
                return judgeAuthority(afterScheme.substring(authority), name, name + ": URL");
            }
            if (SAFE_SCHEMES.contains(name)) {
                return new UrlAnalysis(false, name + ": URL, which hands the value to an external"
                        + " handler rather than loading content into the page");
            }
            return new UrlAnalysis(true, "leaves the page's origin through the " + name + ": scheme,"
                    + " which is not on the safe-scheme allowlist");
        }

        int authority = authorityStartOfRelativeUrl(value);
        if (authority >= 0) {
            // A protocol-relative URL inherits the base URL's scheme, so that is the scheme its
            // origin is built from.
            return judgeAuthority(value.substring(authority), BASE_SCHEME, "protocol-relative URL");
        }

        return new UrlAnalysis(false, "relative URL, so it stays on the page's own origin");
    }

    /**
     * Where the authority begins in the text following {@code scheme:}, or {@code -1} if this URL has
     * no authority and therefore resolves against the base URL.
     *
     * <p>The standard splits here on whether the scheme matches the base URL's. A URL whose scheme is
     * the page's own enters "special relative or authority" state, which demands two separators
     * before it will read a host — so {@code https:/x} is the path {@code /x} on the page's own
     * origin, while {@code https:/\attacker.invalid/x} reaches the attacker. A URL with a
     * <em>different</em> special scheme enters "special authority slashes" state, which accepts any
     * number of separators including none, so {@code http:attacker.invalid/x} is off-origin.
     */
    private static int authorityStartAfterScheme(String scheme, String afterScheme) {
        int run = separatorRunLength(afterScheme);
        if (scheme.equals(BASE_SCHEME)) {
            return run >= 2 ? run : -1;
        }
        return run;
    }

    /**
     * Where the authority begins in a URL with no scheme, or {@code -1} for an ordinary relative
     * reference. Two or more separators — in any mix of {@code /} and {@code \}, and however many —
     * make this protocol-relative, which is why {@code ///attacker.invalid/x} is off-origin and
     * {@code \attacker.invalid/x} is not.
     */
    private static int authorityStartOfRelativeUrl(String value) {
        int run = separatorRunLength(value);
        return run >= 2 ? run : -1;
    }

    /** How many leading characters are path separators, counting {@code \} as one of them. */
    private static int separatorRunLength(String value) {
        int i = 0;
        while (i < value.length() && (value.charAt(i) == '/' || value.charAt(i) == '\\')) {
            i++;
        }
        return i;
    }

    /**
     * Judges an authority against {@link #BASE_ORIGIN}.
     *
     * <p>{@code scheme} is the effective scheme of the URL the authority came from — the scheme it
     * spelled out, or {@link #BASE_SCHEME} for a protocol-relative URL, which inherits the base URL's.
     * It is a parameter rather than something recovered from {@code what}, which is prose for the
     * explanation and nothing else.
     *
     * <p>All three components of the origin are compared. Dropping the port makes
     * {@code //app.example:8443/x} the page's own origin, which it is not; dropping the scheme makes
     * {@code http://app.example/x} the page's own origin, which is a TLS downgrade on an
     * {@code https:} page.
     */
    private static UrlAnalysis judgeAuthority(String authority, String scheme, String what) {
        int end = authority.length();
        for (int i = 0; i < authority.length(); i++) {
            char c = authority.charAt(i);
            // '\' ends the authority too, because for a special scheme it is a path separator.
            if (c == '/' || c == '\\' || c == '?' || c == '#') {
                end = i;
                break;
            }
        }
        String hostAndPort = authority.substring(0, end);

        // WHATWG splits userinfo at the LAST '@'.
        int at = hostAndPort.lastIndexOf('@');
        String host = at >= 0 ? hostAndPort.substring(at + 1) : hostAndPort;

        // A bracketed literal has to be recognised before anything else, because '[', ':' and ']' are
        // all forbidden host code points outside brackets and inside them they are the address.
        // Checking the forbidden set first would report every IPv6 URL as unparseable.
        //
        // This branch matches brackets; it does NOT parse an IPv6 address, so it says nothing about
        // whether the contents are one. //[not-an-ip]/x, //[zzz]/x and //[]/x are all reported here as
        // off-origin hosts, where Node refuses to parse them at all. That is a deliberate choice, not
        // an oversight: a validator is the only way to close the gap, and a validator that wrongly
        // *rejects* a real address would turn a live off-origin URL into a "fails to parse" SAFE
        // verdict, which is the one error direction this class must not have. Reporting an
        // unparseable host as off-origin costs a reviewer one look; the other mistake costs a
        // vulnerability.
        if (host.startsWith("[")) {
            int close = host.indexOf(']');
            if (close < 0) {
                return new UrlAnalysis(false, what + " whose bracketed host literal is unterminated,"
                        + " so the URL fails to parse");
            }
            return new UrlAnalysis(true, what + " reaching off-origin host "
                    + host.substring(0, close + 1));
        }

        // Split off a numeric port before checking host code points. An empty port ("//host:/x") is
        // all-digits vacuously, and the standard treats it as no port at all, which is what happens
        // here: portText is "" and effectivePort() returns the scheme's default.
        String portText = "";
        int colon = host.lastIndexOf(':');
        if (colon >= 0 && host.substring(colon + 1).chars().allMatch(Character::isDigit)) {
            portText = host.substring(colon + 1);
            host = host.substring(0, colon);
        }

        String decoded = percentDecode(host);
        for (int i = 0; i < decoded.length(); i++) {
            if (isForbiddenInHost(decoded.charAt(i))) {
                return new UrlAnalysis(false, what + " whose host " + decoded
                        + " contains a forbidden code point, so the URL fails to parse");
            }
        }

        if (decoded.isEmpty()) {
            return new UrlAnalysis(false, what + " with an empty host");
        }

        int port = effectivePort(scheme, portText);
        if (port == PORT_OUT_OF_RANGE) {
            return new UrlAnalysis(false, what + " whose port " + portText + " is above " + MAX_PORT
                    + ", so the URL fails to parse");
        }

        String origin = originOf(scheme, decoded, port);
        if (origin.equals(BASE_ORIGIN)) {
            return new UrlAnalysis(false, what + " pointing at the page's own origin " + origin);
        }
        return new UrlAnalysis(true, what + " reaching off-origin " + origin);
    }

    /**
     * The port that actually applies: the digits if there are any, otherwise the scheme's default.
     *
     * <p>The digits are parsed as a number rather than compared as text, because the parser
     * normalises them — Node reads {@code 00443} as {@code 443} and {@code 0080} as {@code 80}, so
     * {@code http://app.example:00443/x} has origin {@code http://app.example:443} and is not the
     * default port dressed up. Digit strings above {@link #MAX_PORT} make the URL fail to parse.
     */
    private static int effectivePort(String scheme, String portText) {
        if (portText.isEmpty()) {
            return defaultPort(scheme);
        }
        String digits = portText.replaceFirst("^0+", "");
        if (digits.isEmpty()) {
            // ":0" and ":000" are port zero, which parses; it is simply not any default.
            return 0;
        }
        if (digits.length() > 5) {
            return PORT_OUT_OF_RANGE;
        }
        int value = Integer.parseInt(digits);
        return value > MAX_PORT ? PORT_OUT_OF_RANGE : value;
    }

    /** The default port of a special scheme, or {@code -1} for a scheme that has none. */
    private static int defaultPort(String scheme) {
        if (scheme.equalsIgnoreCase("https")) {
            return HTTPS_DEFAULT_PORT;
        }
        if (scheme.equalsIgnoreCase("http")) {
            return HTTP_DEFAULT_PORT;
        }
        return -1;
    }

    /**
     * An origin in the serialised form a browser reports, with the default port omitted — which is
     * what makes {@code https://app.example:443/x} and {@code https://app.example/x} compare equal
     * while {@code https://app.example:8443/x} does not.
     */
    private static String originOf(String scheme, String host, int port) {
        String lowerScheme = scheme.toLowerCase();
        String origin = lowerScheme + "://" + host.toLowerCase();
        return port == defaultPort(lowerScheme) ? origin : origin + ":" + port;
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

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /**
     * The standard's first step: remove any leading and trailing C0 control or space. The test
     * {@code c <= ' '} is exactly that set, and it deliberately does not strip DEL (U+007F), which is
     * above space and is left in place — as the standard leaves it.
     */
    private static String stripLeadingAndTrailingC0AndSpace(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) <= ' ') {
            start++;
        }
        while (end > start && value.charAt(end - 1) <= ' ') {
            end--;
        }
        return value.substring(start, end);
    }

    /**
     * The standard's second step, and the one that is easy to miss: tab, LF and CR are removed from
     * <em>anywhere</em> in the URL, not merely trimmed from the ends. It is that removal, not any
     * scheme-matching leniency, that turns {@code java<LF>script:alert(1)} into a live
     * {@code javascript:} URL — the newline never reaches the scheme parser at all.
     */
    private static String removeTabsAndNewlines(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\t' && c != '\n' && c != '\r') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Sink extraction and DOM shape
    // ------------------------------------------------------------------

    private static String extractSink(XssCase testCase, CanoeTestSupport.RenderResult result) {
        if (testCase.sink() == SinkKind.NONE) {
            return "";
        }
        Element element = result.dom().selectFirst(testCase.selector());
        if (element == null) {
            throw new AssertionError("Case " + testCase.id() + " declares selector "
                    + testCase.selector() + " but nothing matched in: " + result.output());
        }
        return testCase.attribute() == null
                ? element.wholeText()
                : element.attr(testCase.attribute());
    }

    /**
     * The shape of a document: element names in order, each with its attribute names. Deliberately
     * excludes text and attribute <em>values</em>, because those are where attacker data is supposed
     * to land. A difference here means the payload created or altered markup.
     *
     * <p>The selection is over the <em>whole</em> document rather than over {@code body()}, and that
     * is not a detail. The HTML parser hoists {@code <script>}, {@code <title>}, {@code <noscript>}
     * and anything else it finds before the first flow content into {@code <head>}, so a
     * body-only skeleton reduces those documents to the empty string {@code body[]} on both sides of
     * the comparison and the structural oracle silently stops asserting anything. Fifteen invocations
     * were in that state — {@code policy.nonce}, {@code rcdata.title}, {@code rawtext.noscript} and
     * the two {@code desync.*-end-tag-with-a-suffix} cases — and the CSP nonce one is where a real
     * breakout would have been invisible. Measured across the whole corpus both ways: no invocation
     * changes verdict, so widening the selection costs nothing and closes the hole.
     */
    public static String domSkeleton(Document document) {
        List<String> parts = new ArrayList<>();
        Elements all = document.select("*");
        for (Element element : all) {
            List<String> attributeNames = new ArrayList<>();
            element.attributes().forEach(a -> attributeNames.add(a.getKey()));
            Collections.sort(attributeNames);
            parts.add(element.tagName() + attributeNames);
        }
        return String.join(",", parts);
    }

    // ------------------------------------------------------------------
    // Results
    // ------------------------------------------------------------------

    /** What the URL analyser concluded, and why. */
    public static final class UrlAnalysis {

        private final boolean dangerous;
        private final String explanation;

        UrlAnalysis(boolean dangerous, String explanation) {
            this.dangerous = dangerous;
            this.explanation = explanation;
        }

        public boolean isDangerous() {
            return dangerous;
        }

        public String explanation() {
            return explanation;
        }
    }

    /** The observed verdict together with the evidence for it. */
    public static final class Observation {

        private final Verdict verdict;
        private final CanoeTestSupport.RenderResult result;
        private final String sinkValue;
        private final String explanation;

        Observation(Verdict verdict, CanoeTestSupport.RenderResult result, String sinkValue,
                    String explanation) {
            this.verdict = verdict;
            this.result = result;
            this.sinkValue = sinkValue;
            this.explanation = explanation;
        }

        public Verdict verdict() {
            return verdict;
        }

        public CanoeTestSupport.RenderResult result() {
            return result;
        }

        /** The decoded value that reached the sink, or the DOM shape for structural judgements. */
        public String sinkValue() {
            return sinkValue;
        }

        public String explanation() {
            return explanation;
        }

        /**
         * Whether a recorded verdict is consistent with what was observed. The two suppression
         * verdicts are interchangeable here: observation sees an empty value and cannot tell whether
         * emitting nothing was the design or an accident. That distinction is the reviewer's, and it
         * is recorded in the ledger rather than derived.
         */
        public boolean matches(Verdict recorded) {
            if (recorded.isSuppression() && verdict.isSuppression()) {
                return true;
            }
            return recorded == verdict;
        }

        @Override
        public String toString() {
            return verdict + " (" + explanation + ")";
        }
    }
}
