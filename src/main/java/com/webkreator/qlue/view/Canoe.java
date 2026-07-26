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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Writer;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    // Value 5 was CTX_CSS, deleted by R14 (F21). currentContext() never produced it - ATTR_CSS
    // returns CTX_SUPPRESS - so the CTX_CSS arm of encode() was dead code. Canoe suppresses CSS
    // by design; the decision is recorded on the ATTR_CSS case in currentContext() and in encode().
    // The slot is left as a gap rather than reused, so no old caller silently rebinds to it.

    /**
     * A URL that loads a subresource or reroutes the page: {@code src} on {@code <script>},
     * {@code <iframe>} and {@code <embed>}, {@code data} on {@code <object>}, and {@code href} on
     * {@code <link>} and {@code <base>}. Routed to {@link HtmlEncoder#urlResource(String, java.util.List)},
     * which rejects an off-origin or protocol-relative authority (F6/R9). Distinct from {@link #CTX_URI}
     * because {@code <a href>} and {@code <img src>} are open-redirect and referrer surfaces, not
     * code-execution ones, and keep the ordinary {@code url()} encoder.
     */
    public static final int CTX_URI_RESOURCE = 6;

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

    /**
     * Inside {@code </script}, with the name matched and the character after it not yet seen.
     *
     * <p>The HTML Standard's script-data-end-tag-name state does not leave script data on the name
     * alone: the end tag is only "appropriate" when the character following the name is whitespace,
     * {@code /} or {@code >}. {@code SCRIPT_END} used to go straight to {@link #TAG} on the final
     * {@code t}, so {@code </scriptfoo>} closed the element for Canoe and not for the browser, and
     * everything after it was encoded for a context that did not exist there (F10, closed by R17).
     * This state is where that one character is judged. It is still script data - {@link
     * #currentContext()} answers {@link #CTX_JS} here, exactly as it does for {@code SCRIPT_END}.
     */
    public static final int SCRIPT_END_NAME = 20;

    /** The {@code </style} twin of {@link #SCRIPT_END_NAME}; suppresses, as every CSS route does. */
    public static final int CSS_END_NAME = 21;

    public static final int INVALID = 666;

    public static final int QUOTE_NONE = 0;

    public static final int QUOTE_DOUBLE = 1;

    public static final int QUOTE_SINGLE = 2;

    public static final int ATTR_HTML = 0;

    public static final int ATTR_CSS = 1;

    public static final int ATTR_JS = 2;

    public static final int ATTR_URI = 3;

    public static final int ATTR_DATA = 4;

    /**
     * An attribute name nothing recognises, which is suppressed.
     *
     * <p>This constant occupies the slot the retired ATTR_CONTENT held. ATTR_CONTENT existed for
     * one branch, that branch compared the characters of "data" rather than of "content" (F7), and
     * R7 resolved the pair: "data" is a URL and "content" is suppressed like every other name no
     * list holds. Nothing assigned ATTR_CONTENT after that, so it went with its branch.
     */
    public static final int ATTR_UNKNOWN = 5;

    public static final int ATTR_ACTIONSCRIPT = 6;

    /**
     * A URL-bearing attribute name on an element that loads a subresource with it (R9). Reached only
     * from {@link #setTagAttributeContext()}, which narrows {@link #ATTR_URI} to this when the tag
     * name says the value is a resource-loading sink; maps to {@link #CTX_URI_RESOURCE}.
     */
    public static final int ATTR_URI_RESOURCE = 7;

    private static final Logger log = LoggerFactory.getLogger(Canoe.class);

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
     * The URL-bearing attribute names, whose values go through {@link HtmlEncoder#url(String)}.
     *
     * <p>The set used to be five names - background, dynsrc, lowsrc, href and src - and every other
     * URL-bearing name in HTML took the ATTR_HTML default, which the HTML parser undoes before the
     * URL parser runs. That was the URL half of F3: <code>href</code> was percent-encoded and
     * <code>xlink:href</code>, <code>formaction</code> and <code>action</code> were handed back to
     * the attacker character for character, so the safe-by-analogy assumption a template author
     * would make was exactly wrong. R6 adds the twelve names the review enumerates.
     *
     * <p><code>data</code> is here because of R7: <code>&lt;object data&gt;</code> is a URL, and the
     * branch that used to claim the name yielded ATTR_CONTENT because it was a byte-identical copy
     * of the branch above it (F7).
     *
     * <p><code>xlink:href</code> needs no tokenizer change - {@link #isTagNameChar(char, int)}
     * accepts ':' - so it scans as one name and simply never matched <code>href</code>.
     *
     * <p><code>srcset</code> is a comma-and-whitespace separated list of URLs with descriptors, and
     * <code>url()</code> percent-encodes both separators, so an interpolated candidate list loses
     * its descriptors and a multi-candidate value is mangled into one long URL. That is an
     * availability cost and not a security hole: the alternative - parsing the list and encoding
     * each candidate by itself - is a feature to design rather than a default to guess at, and the
     * shape a template actually writes, <code>srcset="$url"</code>, still yields a usable URL. The
     * decision is recorded here rather than left for the next reader to rediscover.
     *
     * <p>Deliberately absent, and suppressed by the unknown-name rule instead:
     *
     * <ul>
     *   <li><code>srcdoc</code> - its value is parsed as a whole HTML document, so the correct
     *       encoding is a second full HTML encode and a single-encoded value is same-origin XSS.
     *       Suppression is the honest answer until somebody wants to build double encoding
     *       deliberately (R6, and &sect;6 of the remediation plan).
     *   <li><code>content</code> - a URL on exactly one element/attribute-value combination,
     *       <code>&lt;meta http-equiv="refresh" content="N; url=..."&gt;</code>. <strong>R10 decided
     *       deliberately to leave it suppressed</strong> rather than give that one combination a URL
     *       context. Recognising the refresh URL would need three things Canoe does not have and would
     *       be substantial machinery to add for one attribute: (1) the tag name (R8 supplies it), (2)
     *       the value of the <em>sibling</em> attribute <code>http-equiv="refresh"</code> - and Canoe
     *       scans attributes one at a time and never retains a prior attribute's value, and
     *       <code>content</code> may appear before <code>http-equiv</code>, so this is a whole
     *       sibling-attribute-value tracking facility - and (3) parsing the <code>N; url=</code>
     *       prefix out of the value so only the URL portion is encoded, which the per-reference
     *       encoding model cannot do at all: a reference is an opaque value encoded with one context,
     *       so Canoe never knows whether the literal <code>N; url=</code> prefix is part of the
     *       reference or of the surrounding template text. Routing every <code>content</code> to
     *       <code>url()</code> instead would percent-encode the prose in every meta description on the
     *       page. Suppression is fail-safe: a suppressed <code>content</code> renders empty, so no
     *       forced redirect occurs, and a meta refresh that legitimately needs a dynamic URL is a case
     *       for application code, not silent interpolation (F3's refresh row; R7 default, R10
     *       confirmed).
     *   <li><code>imagesrcset</code>, <code>xml:base</code>, <code>archive</code>,
     *       <code>classid</code>, <code>profile</code> - URL-bearing names that no ordinary template
     *       interpolates into. Suppression is strictly stronger than <code>url()</code>, which is a
     *       scheme filter and not an origin filter (F6), so leaving them off this list costs
     *       security nothing and costs availability only where a template needs them.
     * </ul>
     */
    private static final Set<String> URL_ATTRIBUTE_NAMES = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(
                    "action", "background", "cite", "codebase", "data", "dynsrc", "formaction",
                    "href", "longdesc", "lowsrc", "manifest", "ping", "poster", "src", "srcset",
                    "usemap", "xlink:href")));

    /**
     * The element/attribute combinations that load a subresource or reroute the page, and so take the
     * origin-checking {@link HtmlEncoder#urlResource(String, java.util.List)} rather than the ordinary
     * {@code url()} — the resource-loading half of the URL set, made distinguishable by R8's tag-name
     * tracking (R9). Each key is an element name; the value is the URL attribute on it that a browser
     * dereferences into an executable or page-controlling context.
     *
     * <ul>
     *   <li>{@code <script src>} — arbitrary JavaScript with the page's full privileges.
     *   <li>{@code <iframe src>} — an attacker document in the page's frame tree.
     *   <li>{@code <embed src>} — a plugin document.
     *   <li>{@code <object data>} — the object element's resource, script or document.
     *   <li>{@code <link href>} — a stylesheet or other subresource; an off-origin stylesheet can
     *       overlay, exfiltrate and restyle.
     *   <li>{@code <base href>} — reroutes <em>every</em> relative URL on the rest of the page, the
     *       widest blast radius of the group.
     * </ul>
     *
     * <p>Deliberately <em>not</em> here, and kept on the ordinary {@code url()} encoder: {@code <a
     * href>} and {@code <img src>} (and the other fetch-not-code names — {@code poster}, {@code cite},
     * {@code ping}, {@code srcset}, {@code formaction}, {@code action}, ...). An off-origin {@code <a
     * href>} is an open redirect and an off-origin {@code <img src>} is a referrer leak and a load;
     * neither is code execution, and rejecting an off-origin value from them would break the ordinary
     * "link to another site" and "hotlink an image" cases that are not a Canoe concern. &sect;6 of the
     * remediation plan records that boundary: these remain open-redirect/referrer surfaces by design,
     * F6 residue that R9 scopes out rather than closes.
     */
    private static final Map<String, String> RESOURCE_LOADING_SINKS;

    static {
        Map<String, String> sinks = new LinkedHashMap<>();
        sinks.put("script", "src");
        sinks.put("iframe", "src");
        sinks.put("embed", "src");
        sinks.put("object", "data");
        sinks.put("link", "href");
        sinks.put("base", "href");
        RESOURCE_LOADING_SINKS = Collections.unmodifiableMap(sinks);
    }

    /**
     * The attribute names whose value the browser treats as plain text, and which therefore reach
     * {@link HtmlEncoder#htmlAttr(String)}.
     *
     * <p>R5 inverted the default: an attribute name nothing here recognises is ATTR_UNKNOWN and is
     * suppressed, where it used to be ATTR_HTML. It has to be written as an allowlist of plain-text
     * names rather than as a denylist of dangerous ones, because a denylist puts every name nobody
     * thought of on the wrong side of it - which is what F3 and F20 were.
     *
     * <p>The membership test for every name below is the same: the browser consumes the decoded
     * value as <em>text</em> or as an enumerated keyword, hands it to no second parser, resolves no
     * URL from it, and acts on it as no security directive. <code>html()</code> escapes the space,
     * both quotes, '&gt;' and '=', so a value cannot leave the attribute it was written into even
     * unquoted, and that is the whole of what the encoder has to achieve for these names.
     *
     * <p>Deliberately absent, each for a stated reason:
     *
     * <ul>
     *   <li><code>sandbox</code>, <code>rel</code>, <code>integrity</code> - F20. The HTML parser
     *       consumes the decoded value as a <em>directive</em>, so no encoding of
     *       <code>allow-same-origin</code> means anything other than <code>allow-same-origin</code>.
     *       Encoding is not insufficient here, it is inapplicable, and suppression is not the
     *       preferred fix but the only one.
     *   <li><code>nonce</code> - inert as text, which is true and is the wrong test. An attacker who
     *       chooses the nonce can author a <code>&lt;script nonce&gt;</code> the content security
     *       policy then admits, which defeats the control rather than escaping the attribute. The
     *       review's own remediation sketch listed <code>nonce</code> among the plain-text names;
     *       implementing it as written would have left F20's worst row on <code>html()</code>.
     *   <li><code>http-equiv</code>, <code>charset</code> - parser and navigation directives. A
     *       value of <code>refresh</code> turns a sibling <code>content</code> into a redirect, and
     *       the document's declared encoding decides how every byte after it is tokenized.
     *   <li><code>crossorigin</code>, <code>referrerpolicy</code> - credential and referrer policy,
     *       the same class as <code>rel</code>: an attacker-chosen value weakens a control the
     *       template author set.
     *   <li><code>is</code> - selects which custom element definition upgrades the element, which is
     *       a choice of code rather than a piece of text.
     *   <li><code>style</code> and every name beginning <code>on</code> - classified above this
     *       list, as CSS and JavaScript, and suppressed there.
     * </ul>
     *
     * <p>Three names the review's F20 table lists and this allowlist deliberately <em>keeps</em>:
     *
     * <ul>
     *   <li><code>type</code> - the only attacker-reachable effect on
     *       <code>&lt;script type&gt;</code> is to stop the script running, which fails safe;
     *       nothing about it turns execution on where it was off, because the element is a
     *       <code>&lt;script&gt;</code> either way. It is plain text or an enumerated keyword
     *       everywhere else it appears - <code>&lt;input type&gt;</code>,
     *       <code>&lt;button type&gt;</code>, <code>&lt;ol type&gt;</code> - and a category widened
     *       from "security control" to "any behavioural directive" to admit it would admit half this
     *       list. Three other elements were checked rather than assumed, because
     *       <code>&lt;script&gt;</code> is not the interesting one:
     *       <ul>
     *         <li><code>&lt;object type&gt;</code> - the object element's algorithm takes the
     *             resource type from the response's <code>Content-Type</code> metadata when there is
     *             any, and consults the attribute only when there is none. An attacker-chosen
     *             <code>type</code> therefore cannot make a served resource be interpreted as
     *             something else, which is the vector worth worrying about (a user-uploaded image
     *             re-read as same-origin HTML). It could in the plugin era, which is why this entry
     *             says which rule retired it rather than that the attribute is inert.
     *         <li><code>&lt;embed type&gt;</code> - the same, and narrower still: shipping engines
     *             support only images and nested browsing contexts here, and the nested context
     *             loads the URL and obeys the server.
     *         <li><code>&lt;input type&gt;</code> - the reachable effects are changing which control
     *             is drawn and whether it submits (<code>image</code>, <code>submit</code>). Both
     *             need a URL to be interesting, and both of the attributes that supply one -
     *             <code>src</code> and <code>formaction</code> - are on the URL list. What is left
     *             is UI redressing, which this list does not claim to prevent.
     *       </ul>
     *   <li><code>target</code> - names a browsing context. Retargeting a navigation the template
     *       author chose is behaviour, not a security control being switched off: the residual is
     *       that <code>_blank</code> opens the author's own URL in a new context, and the attribute
     *       that would undo that context's implicit <code>noopener</code> is <code>rel</code>, which
     *       is suppressed. Recorded so that the decision is deliberate rather than by omission.
     *   <li><code>formtarget</code> - the submit-button analogue of <code>target</code>, kept for
     *       the same reason. Note that its sibling <code>formaction</code> is a URL and is on the
     *       URL list, which is the distinction worth seeing: one names a window, the other names the
     *       place the form's contents are sent.
     * </ul>
     *
     * <p>Five more names on this list are not <em>quite</em> "text a browser reads and hands to
     * nothing". Each was kept, and each is written out so that the next reader does not have to
     * re-derive the argument - or, better, so that they can disagree with it in one place:
     *
     * <ul>
     *   <li><code>pattern</code> - compiled by the browser as an ECMAScript regular expression, so a
     *       second parser genuinely does consume it. What that parser can be made to do is bounded:
     *       it matches, it cannot fetch and it cannot execute, and the worst attacker-reachable
     *       outcome is catastrophic backtracking, which hangs the tab it is in. That is a
     *       denial-of-service against the user themselves and not an escape.
     *   <li><code>media</code>, <code>sizes</code> - a media-query list and a source-size list, both
     *       consumed by the CSS media-query grammar. Also a second parser, and also a bounded one:
     *       the grammar carries no URL, no function that fetches and no path to script, and a value
     *       it cannot parse evaluates to <code>not all</code>, which is the safe direction. A value
     *       here decides whether a stylesheet or a candidate image applies, never what it is.
     *   <li><code>accept-charset</code> - decides the encoding a form's contents are submitted in,
     *       which is why it deserves a sentence next to <code>charset</code>, which is off the list.
     *       They are not the same class. <code>charset</code> decides how the browser tokenizes the
     *       rest of <em>this document</em> - the classic UTF-7 injection - so it is a live
     *       client-side parser directive. <code>accept-charset</code> changes the bytes the
     *       <em>server</em> receives, and reaching anything with it means finding a server that
     *       mis-decodes them. That is a real thing to be aware of and it is not something an HTML
     *       encoder is the right control for.
     *   <li><code>download</code>, <code>method</code>, <code>formmethod</code>,
     *       <code>enctype</code>, <code>formenctype</code> - navigation and submission behaviour.
     *       The strongest of them is worth stating plainly: an attacker-chosen <code>method</code>
     *       can turn a <code>POST</code> into a <code>GET</code>, which moves the form's fields -
     *       including any CSRF token - into a URL that reaches history, logs and the referrer. The
     *       destination is still the template author's, so this is disclosure through a downgrade
     *       rather than a redirect, and it sits in the same category as <code>target</code>:
     *       behaviour the author chose being changed, not a control being switched off. Suppressing
     *       them would cost every ordinary form; the residual is recorded instead.
     *   <li><code>for</code>, <code>form</code>, <code>headers</code>, <code>list</code>,
     *       <code>popovertarget</code>, <code>name</code> - identifiers naming other elements in the
     *       same document. An attacker who chooses one can re-associate a control with a different
     *       form, or point a label or a popover somewhere else. That is the DOM-clobbering family,
     *       which &sect;6 of the remediation plan puts out of scope by the review's own criterion -
     *       a name in the document's namespace is not a directive a browser algorithm consumes - and
     *       it is named here rather than left implicit, because <code>id</code> being out of scope
     *       and <code>form</code> being out of scope are the same decision and only one of them is
     *       obvious.
     * </ul>
     */
    private static final Set<String> PLAIN_TEXT_ATTRIBUTE_NAMES = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(
                    // Identity, labelling and the presentation of text.
                    "accesskey", "alt", "autocapitalize", "class", "contenteditable", "dir",
                    "draggable", "hidden", "id", "label", "lang", "name", "placeholder", "role",
                    "slot", "spellcheck", "tabindex", "title", "translate", "value",
                    // Form controls. Every one is text, a number, or an enumerated keyword.
                    "accept", "accept-charset", "autocomplete", "autofocus", "checked", "cols",
                    "dirname", "disabled", "enctype", "for", "form", "formenctype", "formmethod",
                    "formnovalidate", "inputmode", "list", "max", "maxlength", "method", "min",
                    "minlength", "multiple", "novalidate", "pattern", "readonly", "required",
                    "rows", "selected", "size", "step", "wrap",
                    // Tables.
                    "abbr", "colspan", "headers", "rowspan", "scope", "span",
                    // Embedded content, minus every name that resolves a URL.
                    "autoplay", "controls", "decoding", "default", "height", "kind", "loading",
                    "loop", "muted", "playsinline", "preload", "sizes", "srclang", "width",
                    // Links, metadata and the remaining enumerated attributes.
                    "coords", "datetime", "download", "high", "hreflang", "low", "media", "open",
                    "optimum", "popovertarget", "popovertargetaction", "reversed", "shape", "start",
                    "type", "target", "formtarget")));

    /**
     * The two families of attribute names that are plain text by construction.
     *
     * <p><code>aria-*</code> is an accessible name, description or state: text and enumerated
     * keywords, consumed by the accessibility tree and by nothing that parses. <code>data-*</code>
     * is reserved by the HTML Standard for the page's own use and has no browser semantics at all -
     * it is the one namespace where the standard guarantees a directive can never appear.
     *
     * <p>Note that the exact name <code>data</code> is a URL and is matched before this prefix runs;
     * <code>data-</code> requires the hyphen, exactly as the standard does.
     */
    private static final List<String> PLAIN_TEXT_ATTRIBUTE_PREFIXES =
            Collections.unmodifiableList(Arrays.asList("aria-", "data-"));

    /**
     * The names an application may not add to the plain-text allowlist, whatever it asks for.
     *
     * <p>The extension point exists so that a developer with
     * <code>&lt;div my-widget-config="$x"&gt;</code> has somewhere to go other than
     * <code>$_x.asis()</code>, which turns Canoe off for that value entirely. It is not a way to put
     * a policy directive or a markup-bearing attribute back on <code>html()</code>: every name here
     * is one whose suppression <em>is</em> the fix for a finding, so adding it would re-open F3 or
     * F20 through configuration, in a place no test in the suite would look.
     *
     * <p>Names Canoe classifies before it consults the allowlist at all - the URL set,
     * <code>style</code> and anything beginning <code>on</code> - are rejected too, so that a
     * configuration which would have had no effect fails at startup rather than looking as though it
     * worked.
     *
     * <p>The second group is the one that is easiest to leave out and is listed for a reason:
     * <code>imagesrcset</code>, <code>xml:base</code>, <code>archive</code>, <code>classid</code>
     * and <code>profile</code> are <em>URL-bearing</em> names that R6 deliberately did not route to
     * <code>url()</code>, on the argument that suppression is strictly stronger and no ordinary
     * template interpolates into them. That argument only holds while they stay suppressed. Adding
     * one to the plain-text allowlist would put a URL sink back on <code>html()</code>, whose output
     * the HTML parser decodes before the URL parser ever sees it - which is F3 exactly, and is
     * strictly worse than the <code>url()</code> routing R6 declined to give them. A name here is
     * therefore a name whose suppression is a recorded decision, whether that decision was recorded
     * as a finding (F3, F20) or as R6's own.
     */
    private static final Set<String> NAMES_THAT_MAY_NOT_BE_ADDED = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(
                    "srcdoc", "content", "sandbox", "rel", "integrity", "nonce", "http-equiv",
                    "charset", "crossorigin", "referrerpolicy", "is", "style",
                    "imagesrcset", "xml:base", "archive", "classid", "profile")));

    /**
     * The application's own additions to the plain-text allowlist, per Canoe instance.
     *
     * <p>Per instance and never static: the allowlist belongs to the engine that rendered the page,
     * so two applications in one JVM cannot widen each other's, and nothing can widen anybody's
     * after startup. {@code VelocityViewFactory} owns the configuration and hands the set to every
     * Canoe it constructs.
     */
    private final Set<String> extraPlainTextAttributes;

    /**
     * The origins a resource-loading sink ({@link #RESOURCE_LOADING_SINKS}) may load from, beyond the
     * page's own — the CDN allowlist for R9, per Canoe instance and never static for the same reason
     * {@link #extraPlainTextAttributes} is. Empty by default, which means "same-origin-relative URLs
     * only" on those six sinks. {@code VelocityViewFactory} owns the configuration and hands the parsed
     * list to every Canoe it constructs.
     */
    private final List<HtmlEncoder.TrustedOrigin> trustedResourceOrigins;

    /**
     * The name of the element whose tag is currently being parsed, in lower case, or null when the
     * parser is not inside a tag whose name has been read.
     *
     * <p>The shared buffer cannot carry this: {@code buf} is reused for the first attribute name the
     * moment one starts, so before R8 the element name was gone by the time
     * {@link #setTagAttributeContext()} ran, and {@code src} on {@code <script>} was
     * indistinguishable from {@code src} on {@code <img>} — F6's structural cause. This field is the
     * enabler for R9 (an origin policy for {@code src}/{@code href} on the resource-loading elements
     * {@code script}, {@code iframe}, {@code object}, {@code embed}, {@code link}, {@code base}),
     * which reads it in {@link #isResourceLoadingSink(String)}.
     *
     * <p>R10 ({@code <meta http-equiv="refresh" content>}) considered reading it and <strong>decided
     * not to</strong>: recognising a refresh URL needs the sibling <code>http-equiv="refresh"</code>'s
     * value as well as the tag name, plus parsing the {@code N; url=} prefix out of the content value,
     * and the deliberate R10 decision is to leave {@code content} suppressed (see
     * {@link #URL_ATTRIBUTE_NAMES}). The tag name alone is not enough for that decision, so this field
     * stays unread on the {@code content} path.
     *
     * <p>Lifecycle, which is the whole of what the field means:
     *
     * <ul>
     *   <li><strong>Set</strong> when the tag name completes in the TAG_NAME state, after the name
     *       has been validated and before any attribute is scanned. The name scan lower-cases as it
     *       buffers — the same convention the attribute-name scan follows — so the field is lower
     *       case and a comparison against a lower-case literal cannot be evaded by case. For an end
     *       tag the leading {@code '/'} is not part of the name; {@link #closingTag} records which
     *       kind of tag it was.
     *   <li><strong>Set</strong> equally when SCRIPT_END_NAME or CSS_END_NAME <em>confirms</em>
     *       {@code </script} or {@code </style} and enters the TAG state without ever passing through
     *       TAG_NAME, so the invariant "inside a tag whose name has been read" has no exception for
     *       the two elements whose end tag is matched by a different state. Confirmation is the
     *       delimiter after the name, not the name alone (R17, F10): at {@code </scriptfoo} there is
     *       no end tag, so the field stays as it was — null, because the {@code '<'} cleared it.
     *   <li><strong>Cleared</strong> when the tag ends — {@code '>'} in the TAG and TAG_EMPTY_ENDING
     *       states — and again when a new tag opens on {@code '<'}, so body text, script and style
     *       bodies, comments and DOCTYPEs never see the previous element's name, and an error path
     *       that abandons a half-read name cannot leak the name before it into the next tag.
     * </ul>
     */
    protected String tagName;

    /**
     * The name of the attribute whose value is being scanned, when nothing recognised it.
     *
     * <p>Kept only for the diagnostic in {@link #currentContext()}: a suppressed value is otherwise
     * indistinguishable from an empty one, which is the failure mode that sends a developer to
     * {@code $_x.asis()}. Null whenever the current attribute name <em>was</em> recognised, so the
     * field cannot report a stale name for a later attribute.
     *
     * <p>Protected rather than private so that {@code CanoeStateProbe} can assert the diagnostic
     * carries the right name: the log call itself is not observable from a test without installing
     * a logging backend, and a diagnostic nobody asserts is a diagnostic that names the wrong
     * attribute the first time somebody reorders this method.
     */
    protected String unknownAttributeName;

    /**
     * Create a Canoe instance with no application-level additions to the plain-text allowlist and no
     * trusted resource origins — resource-loading sinks accept same-origin-relative URLs only.
     */
    public Canoe(Writer writer) {
        this(writer, Collections.<String>emptySet());
    }

    /**
     * Create a Canoe instance that treats the given extra attribute names as plain text.
     *
     * <p>The names are put through {@link #normalisePlainTextAttributeNames(Collection)} here rather
     * than trusted, even though {@code VelocityViewFactory} has already validated everything it
     * passes. This constructor is public API on a public {@link Writer}, so "the caller validated
     * it" is a convention and not a guarantee, and a convention is not what should stand between an
     * application and {@code new Canoe(writer, Set.of("sandbox"))}. Validating twice costs one pass
     * over a handful of strings once per render; validating once costs the guard.
     *
     * @param writer                       the writer parsed output is passed to
     * @param extraPlainTextAttributeNames additional plain-text attribute names; a null or empty set
     *                                     means the built-in allowlist only
     * @throws IllegalArgumentException if a name is one
     *                                  {@link #normalisePlainTextAttributeNames(Collection)} refuses
     */
    public Canoe(Writer writer, Set<String> extraPlainTextAttributeNames) {
        this(writer, extraPlainTextAttributeNames, Collections.<String>emptyList());
    }

    /**
     * Create a Canoe instance that also permits resource-loading sinks to load from the given origins.
     *
     * <p>The origins are parsed and validated here through
     * {@link HtmlEncoder#parseTrustedOrigins(Collection)}, for the same reason the plain-text names
     * are put through {@link #normalisePlainTextAttributeNames(Collection)}: this is public API on a
     * public {@link Writer}, so validating at construction rather than trusting the caller is the
     * guard, and a bad origin fails here instead of silently matching nothing on every page.
     *
     * @param writer                       the writer parsed output is passed to
     * @param extraPlainTextAttributeNames additional plain-text attribute names
     * @param trustedResourceOrigins       hosts/origins a {@code <script src>}, {@code <iframe src>},
     *                                     {@code <object data>}, {@code <embed src>}, {@code <link
     *                                     href>} or {@code <base href>} may load from; a null or empty
     *                                     collection means same-origin-relative only
     * @throws IllegalArgumentException if a plain-text name is refused or an origin is malformed
     */
    public Canoe(Writer writer, Set<String> extraPlainTextAttributeNames,
                 Collection<String> trustedResourceOrigins) {
        this.writer = writer;
        this.state = HTML;
        this.extraPlainTextAttributes = (extraPlainTextAttributeNames == null)
                ? Collections.<String>emptySet()
                : normalisePlainTextAttributeNames(extraPlainTextAttributeNames);
        this.trustedResourceOrigins = HtmlEncoder.parseTrustedOrigins(trustedResourceOrigins);
    }

    /**
     * Validates and normalises application-supplied plain-text attribute names.
     *
     * <p>Called once at configuration time rather than per render, so that a name Canoe would refuse
     * fails at startup with a message naming the reason instead of silently doing nothing on every
     * page. Names are lower-cased because the attribute-name scan lower-cases as it buffers.
     *
     * @param names the names an application wants treated as plain text
     * @return an unmodifiable, lower-cased set
     * @throws IllegalArgumentException if a name is not a legal attribute name, begins {@code on},
     *                                  or is one of the names whose suppression is the fix for a
     *                                  finding
     */
    public static Set<String> normalisePlainTextAttributeNames(Collection<String> names) {
        Set<String> normalised = new LinkedHashSet<>();
        if (names == null) {
            return Collections.unmodifiableSet(normalised);
        }

        for (String raw : names) {
            if (raw == null) {
                throw new IllegalArgumentException("A plain-text attribute name cannot be null");
            }

            String name = raw.trim().toLowerCase();
            if (name.isEmpty()) {
                continue;
            }

            if (name.length() > MAX_TAGNAME_LEN - 1) {
                throw new IllegalArgumentException("Attribute name too long for Canoe's buffer: "
                        + name);
            }

            for (int i = 0; i < name.length(); i++) {
                if (!isNameChar(name.charAt(i), i)) {
                    throw new IllegalArgumentException("Not a legal attribute name: " + raw);
                }
            }

            if (name.startsWith("on")) {
                throw new IllegalArgumentException("Refusing to treat " + name + " as plain text:"
                        + " every attribute name beginning \"on\" is a JavaScript context, and the"
                        + " prefix rule has no exceptions (F1, F2, F19).");
            }

            if (URL_ATTRIBUTE_NAMES.contains(name) || NAMES_THAT_MAY_NOT_BE_ADDED.contains(name)) {
                throw new IllegalArgumentException("Refusing to treat " + name + " as plain text:"
                        + " Canoe classifies it before it consults the application allowlist, or its"
                        + " suppression is the fix for a finding. The per-name reasoning is on"
                        + " Canoe's URL_ATTRIBUTE_NAMES and PLAIN_TEXT_ATTRIBUTE_NAMES.");
            }

            normalised.add(name);
        }

        return Collections.unmodifiableSet(normalised);
    }

    /**
     * Whether a character is legal at the given position of a tag or attribute name.
     *
     * <p>The one implementation of the rule, so that
     * {@link #normalisePlainTextAttributeNames(Collection)} rejects exactly the names the tokenizer
     * could never produce rather than a second opinion about them - a configured name the parser
     * cannot buffer is a set entry nothing will ever match, which is a silently ineffective
     * security configuration.
     */
    private static boolean isNameChar(char c, int pos) {
        if (Character.isLetter(c) || (c == ':') || (c == '_')) {
            return true;
        }

        return (pos != 0) && (Character.isDigit(c) || (c == '-') || (c == '.'));
    }

    /**
     * Whether a character terminates the name of an end tag, which is what decides that the tag
     * really is one (R17, F10).
     *
     * <p>Used by {@link #SCRIPT_END_NAME} and {@link #CSS_END_NAME}, the only two places Canoe
     * matches an end tag name character by character rather than through {@code TAG_NAME}. The set
     * is the HTML Standard's: its script-data-end-tag-name and rawtext-end-tag-name states move on
     * only for tab, LF, FF, space, {@code /} or {@code >}, and anything else makes the whole thing
     * character data. CR is in the set because the standard's input preprocessing turns it into an
     * LF before the tokenizer sees it, so a browser treats {@code &lt;/script\r&gt;} as whitespace
     * too.
     *
     * <p>Written out rather than delegated to {@link Character#isWhitespace(char)}, which is wider -
     * it accepts a vertical tab and the Unicode space separators, none of which a browser treats as
     * whitespace here. Matching the standard exactly is the whole point of the check: a wider set
     * re-opens F10 for the characters it adds, because Canoe would leave script data where the
     * browser stays in it.
     */
    private static boolean isEndTagNameDelimiter(char c) {
        return (c == ' ') || (c == '\t') || (c == '\n') || (c == '\f') || (c == '\r')
                || (c == '/') || (c == '>');
    }

    /**
     * ASCII-only case folding, for the two states that match an end tag name against a literal
     * (R17, F10).
     *
     * <p>The HTML Standard's script-data-end-tag-name and rawtext-end-tag-name states accept only
     * <em>ASCII</em> upper alpha and ASCII lower alpha into the name; every other code point is
     * "anything else" and makes the whole run character data. {@link Character#toLowerCase(char)} is
     * a Unicode fold and is wider than that in one respect that matters here: it maps U+0130 LATIN
     * CAPITAL LETTER I WITH DOT ABOVE to {@code 'i'}, so an end tag that spells {@code script} with
     * U+0130 matched {@code /script} and closed the element for Canoe while every browser stayed in
     * script data. That is F10's forward desync exactly, reached by a different character than
     * {@code </scriptfoo>} and not closed by the delimiter rule, so the fold is bounded here rather
     * than left to {@code Character}. A sweep of the whole BMP finds U+0130 to be the only non-ASCII
     * code point whose {@code Character.toLowerCase()} lands in {@code /script} or {@code /style};
     * the point of writing the fold out is that no future JDK Unicode update can add a second one.
     *
     * <p>Deliberately not applied to {@code TAG_NAME}, which folds the same way for the opening
     * {@code <script>}: there the divergence runs the other way — Canoe enters {@code SCRIPT} and
     * suppresses where the browser sees an unknown element — which is fail-closed, and it is the
     * {@code isNameChar()}/{@code Character.isLetter()} observation the plan records separately.
     */
    private static char asciiToLowerCase(char c) {
        return ((c >= 'A') && (c <= 'Z')) ? (char) (c + ('a' - 'A')) : c;
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
            // Process characters one by one across the requested range
            // [offset, offset + len). The bound is offset + len, not len:
            // len is a count, not an end index, so at any non-zero offset the
            // old "i < len" stopped short by exactly offset characters and, at
            // offset >= len, never ran at all (F9).
            for (i = offset; i < offset + len; i++) {
                processChar(cbuff[i]);
            }
        } catch (IOException e) {
            // Error -- write only the "good" characters. i is the absolute
            // index of the character that failed, offset is where the range
            // began, so i - offset is the number parsed successfully.
            writer.write(cbuff, offset, i - offset);

            throw e;
        }

        // No error has occurred -- write the entire buffer
        writer.write(cbuff, offset, len);
    }

    /**
     * Determines if the character can be used in tag name.
     *
     * <p>Delegates to {@link #isNameChar(char, int)}, which is the same rule the application-level
     * allowlist is validated against; the two were written out twice for one commit and one copy is
     * one too many for a rule that decides where a name ends.
     *
     * @param c
     * @return
     */
    public boolean isTagNameChar(char c, int pos) {
        return isNameChar(c, pos);
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
     * <p>Everything below the prefix rule is a lookup of the buffered name in a
     * declared set rather than a chain of hand-unrolled comparisons. The name is
     * read out of buf as exactly the characters this attribute's own scan wrote -
     * bufLen counts them, plus the NUL terminator TAG_ATTR_NAME appends - so it is
     * bounded in the same sense R3 and R4 made their comparisons bounded, and a
     * name cannot inherit a byte from an earlier tag, attribute or value. What the
     * sets buy over the chains is that the classification is now data with its
     * reasoning attached, and that a reader can see the whole of what reaches each
     * encoder in one place: see URL_ATTRIBUTE_NAMES and PLAIN_TEXT_ATTRIBUTE_NAMES.
     *
     * <p><strong>R5 inverted the default.</strong> An unrecognised name used to be
     * ATTR_HTML and is ATTR_UNKNOWN now, which suppresses. The old default was the
     * policy and markup half of F3 and the whole of F20: html() is worthless for
     * any attribute whose decoded value a second parser or a browser algorithm
     * consumes, so every name nobody had thought of - every URL-bearing name
     * outside the five, srcdoc, content, sandbox, rel, integrity, nonce - was
     * handed to the attacker character for character. Fail-closed is the only
     * defensible default for a classifier whose misses are silent, and the
     * allowlist plus the application extension point are what keep the cost of it
     * from being paid in $_x.asis() calls.
     */
    protected void setTagAttributeContext() {
        // Fail closed. A name that reaches the end of this method unclassified is
        // suppressed, and says so at debug level when a reference lands in it.
        attributeContext = ATTR_UNKNOWN;
        unknownAttributeName = null;

        // Any event handler. The prefix rule replaces the on* table entirely; see
        // the method javadoc for why there is no exception list.
        if (bufferedNameStartsWith("on")) {
            attributeContext = ATTR_JS;
            return;
        }

        String name = bufferedName();

        if (URL_ATTRIBUTE_NAMES.contains(name)) {
            // R9: the same URL name is a code-execution sink on some elements and an open-redirect
            // surface on others, and R8's tag name is what tells them apart. src on <script> rejects
            // an off-origin authority; src on <img> does not.
            attributeContext = isResourceLoadingSink(name) ? ATTR_URI_RESOURCE : ATTR_URI;
            return;
        }

        if (name.equals("style")) {
            attributeContext = ATTR_CSS;
            return;
        }

        if (isPlainTextAttributeName(name)) {
            attributeContext = ATTR_HTML;
            return;
        }

        unknownAttributeName = name;
    }

    /**
     * Whether the current attribute is a resource-loading sink: a URL name on the element that
     * dereferences it into a code-execution or page-controlling context (R9). Reads {@link #tagName},
     * which R8 keeps available for the duration of the tag, so a {@code src} knows whether it is on a
     * {@code <script>} or an {@code <img>}. A null {@code tagName} needs no guard: this method runs
     * only while an attribute is being parsed, which is always inside a named tag, and even if it were
     * not, {@code RESOURCE_LOADING_SINKS.get(null)} is null and {@code attributeName.equals(null)} is
     * false, so the answer is the correct "not a resource sink" either way.
     */
    private boolean isResourceLoadingSink(String attributeName) {
        return attributeName.equals(RESOURCE_LOADING_SINKS.get(tagName));
    }

    /**
     * Whether the name is one the browser consumes as plain text: the built-in
     * allowlist, the two plain-text name families, or the application's own
     * additions.
     *
     * <p>The application's set is consulted last so that nothing it contains can
     * change how a name Canoe already classifies is treated;
     * {@link #normalisePlainTextAttributeNames(Collection)} refuses those names
     * outright as well, so the ordering here is a second line rather than the only
     * one.
     */
    private boolean isPlainTextAttributeName(String name) {
        if (PLAIN_TEXT_ATTRIBUTE_NAMES.contains(name)) {
            return true;
        }

        for (String prefix : PLAIN_TEXT_ATTRIBUTE_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }

        return extraPlainTextAttributes.contains(name);
    }

    /**
     * The attribute name the name scan has buffered, as a string.
     *
     * <p>bufLen counts the NUL terminator TAG_ATTR_NAME writes when the name ends,
     * so the name itself is the first bufLen - 1 characters and nothing this method
     * reads was written by anything other than the current name. The scan
     * lower-cases as it buffers, so the result is lower case and every set this
     * class compares it against is spelled in lower case.
     */
    private String bufferedName() {
        return new String(buf, 0, bufLen - 1);
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
                        // No stale element name while the new one is being read; an
                        // error that abandons this tag mid-name leaves null, never
                        // the previous tag's name.
                        tagName = null;
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
                    } else if (c == '-') {
                        // A third (or later) dash keeps us in comment-end, exactly as the HTML
                        // Standard's comment-end state does: another '-' appends and stays, so the
                        // '>' that follows any run of dashes still closes the comment (F14). Dropping
                        // back to COMMENT here meant <!--a---> never closed and every reference for
                        // the rest of the page rendered empty.
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

                        // Keep the element name past the point where buf is reused
                        // for attribute names (R8). Already lower case - the scan
                        // folds as it buffers - and without the leading '/' of an
                        // end tag, which closingTag records. bufLen counts the name
                        // plus the NUL terminator written above.
                        tagName = closingTag
                                ? new String(buf, 1, bufLen - 2)
                                : new String(buf, 0, bufLen - 1);

                        // By default, the next state
                        // (inside tag) is HTML
                        nextState = HTML;

                        // Detect <script> and <style> tags. A bounded comparison of
                        // the name the current scan wrote, not fixed buffer indices:
                        // this was the one fixed-index read R3 and R4 left behind
                        // (residue-safe only because resetBuffer() runs on every
                        // '<'), and R8 retired it along with the reason it needed
                        // that defence.
                        if (!closingTag) {
                            if (tagName.equals("script")) {
                                // Script
                                nextState = SCRIPT;
                            }

                            if (tagName.equals("style")) {
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
                        // The tag is over; what follows must not see its name.
                        tagName = null;
                    }
                    break;

                case TAG:
                    // Have we encountered the end of the tag?
                    if (c == '>') {
                        // Switch to the state we decided on earlier
                        state = nextState;
                        // The tag is over; what follows must not see its name.
                        tagName = null;
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
                        // bufLen and never reads or writes buf. Neither does
                        // SCRIPT_END_NAME, which reads one character and no buffer
                        // at all.
                        bufLen = 0;
                    }
                    break;

                case SCRIPT_END:
                    // asciiToLowerCase(), not Character.toLowerCase(): the standard's
                    // end-tag-name states fold ASCII and nothing else, and the wider
                    // fold made an end tag spelled with U+0130 close it (R17, F10).
                    if (asciiToLowerCase(c) == jsEnd.charAt(bufLen)) {
                        if (jsEnd.length() == bufLen + 1) {
                            // The name matched. That is not enough to leave script
                            // data: the HTML Standard checks the character after the
                            // name too, so the decision moves to SCRIPT_END_NAME and
                            // closingTag/tagName are assigned there, once the end tag
                            // is confirmed (R17, F10).
                            state = SCRIPT_END_NAME;
                        } else {
                            bufLen++;
                        }
                    } else {
                        // Not "</script" after all. Re-process the character rather
                        // than dropping it: it may itself be the '<' that opens the
                        // real end tag, which is what "<</script>" is and what F10's
                        // converse desync lost - the rest of the page stayed inside
                        // the script element and every reference in it was suppressed.
                        state = SCRIPT;
                        charNeedsProcessing = true;
                    }
                    break;

                case SCRIPT_END_NAME:
                    if (isEndTagNameDelimiter(c)) {
                        state = TAG;
                        nextState = HTML;
                        // The parser is inside "</script" now, having entered
                        // TAG without passing TAG_NAME, so set what TAG_NAME
                        // would have: this is the script element's end tag.
                        // Neither field is read again on this path today; they
                        // are kept truthful so that R9 cannot mistake the tail
                        // of an end tag for an opening <script>.
                        closingTag = true;
                        tagName = jsEnd.substring(1);

                        // TAG has not seen this character, and it carries meaning
                        // there: '>' ends the tag, '/' begins "</script/>".
                        charNeedsProcessing = true;
                    } else {
                        // "</scriptfoo": not an end tag at all. A browser emits those
                        // characters as script data and stays in the script element,
                        // so Canoe does too (R17, F10's forward desync). Re-process
                        // the character, because a '<' here opens a fresh end tag.
                        state = SCRIPT;
                        charNeedsProcessing = true;
                    }
                    break;

                case CSS:
                    if (c == '<') {
                        state = CSS_END;
                        // As in SCRIPT: bufLen indexes cssEnd, and buf is untouched
                        // by CSS_END and by CSS_END_NAME alike.
                        bufLen = 0;
                    }
                    break;

                case CSS_END:
                    // As in SCRIPT_END: an ASCII-only fold, for the same reason.
                    if (asciiToLowerCase(c) == cssEnd.charAt(bufLen)) {
                        if (cssEnd.length() == bufLen + 1) {
                            // As in SCRIPT_END: the name is matched, the character
                            // after it decides, and CSS_END_NAME is where it is read.
                            state = CSS_END_NAME;
                        } else {
                            bufLen++;
                        }
                    } else {
                        // As in SCRIPT_END: hand the mismatching character back.
                        state = CSS;
                        charNeedsProcessing = true;
                    }
                    break;

                case CSS_END_NAME:
                    if (isEndTagNameDelimiter(c)) {
                        state = TAG;
                        nextState = HTML;
                        // As in SCRIPT_END_NAME: the state entered TAG mid-way
                        // through "</style", so record the end tag it is in.
                        closingTag = true;
                        tagName = cssEnd.substring(1);
                        charNeedsProcessing = true;
                    } else {
                        // As in SCRIPT_END_NAME: "</stylefoo" closes nothing.
                        state = CSS;
                        charNeedsProcessing = true;
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
     * <p>This method is called once per reference the template inserts, which is
     * why the unknown-attribute diagnostic lives here rather than in
     * {@link #setTagAttributeContext()}: a page classifies every attribute it
     * contains and suppresses only the ones a reference actually lands in, and it
     * is the drop that a developer needs told about. Debug level, because on a page
     * with unrecognised attribute names it fires per reference; the message names
     * the attribute and the position, because "a value went missing somewhere on
     * the page" is the complaint this exists to answer.
     *
     * @return current output context
     */
    public int currentContext() {
        switch (state) {
            case HTML:
                return CTX_HTML;

            case SCRIPT:
            case SCRIPT_END:
            case SCRIPT_END_NAME:
                return CTX_JS;

            case URL:
                return CTX_URI;

            case CSS:
            case CSS_END:
            case CSS_END_NAME:
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

                    case ATTR_URI_RESOURCE:
                        return CTX_URI_RESOURCE;

                    case ATTR_UNKNOWN:
                        log.debug("Canoe suppressed a reference in the unrecognised attribute"
                                        + " \"{}\" (line: {}, pos: {}). Add the name to the"
                                        + " plain-text allowlist if its value is text; see"
                                        + " VelocityViewFactory.addPlainTextAttributes().",
                                unknownAttributeName, currentLine, currentPos);
                        return CTX_SUPPRESS;

                    // ATTR_CSS (the `style` attribute) is suppressed, not CSS-escaped, and that is
                    // the settled decision R14 records for F21. Canoe's design refuses to interpolate
                    // into CSS: F23 shows a `style` value is decoded in series - HTML character
                    // references first, then the CSS tokenizer - so an encoder correct against all of
                    // it is a project, not a line. R13 (which corrected HtmlEncoder.css()) is that
                    // project's precondition and is now met, but wiring it in has not been decided;
                    // until it is, `style` values render empty. There is deliberately no CTX_CSS.
                    case ATTR_CSS:
                    case ATTR_DATA:
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
                // Canoe does not interpolate into JavaScript: a JS context is suppressed by design.
                // Relaxing this to real escaping (HtmlEncoder.js(), corrected by R13) is an undecided
                // change that must re-run ParserSteeringTest first; there is deliberately no
                // pre-written line here to uncomment.
                return EMPTY_STRING;
            case CTX_URI:
                return HtmlEncoder.url(input);
            case CTX_URI_RESOURCE:
                // No instance in hand, so no configured allowlist: the safe default, which rejects
                // every off-origin authority. The instance path {@link #encode(String)} supplies the
                // application's trusted origins.
                return HtmlEncoder.urlResource(input, Collections.<HtmlEncoder.TrustedOrigin>emptyList());
            // There is no CTX_CSS. currentContext() routes ATTR_CSS to CTX_SUPPRESS, so a CSS context
            // was never produced and the old CTX_CSS arm here was dead code (F21). R14 deleted the
            // constant and this arm rather than wiring a CSS encoder in: see currentContext()'s
            // ATTR_CSS case for the reasoning (Canoe refuses to interpolate into CSS; F23's series of
            // decoders makes a correct CSS encoder a project, not a line; R13 is its precondition).
            case CTX_SUPPRESS:
            default:
                // Do nothing -- suppressed output
                return EMPTY_STRING;
        }
    }

    /**
     * Encodes a value for the context the parser is in <em>now</em>, using this instance's configured
     * trusted resource origins where the static {@link #encode(String, int)} cannot.
     *
     * <p>Every context but {@link #CTX_URI_RESOURCE} is context-only and the static dispatcher handles
     * it; the resource sink is the one place the answer depends on per-instance configuration (the CDN
     * allowlist), so it cannot be a static method of a value and a context. This is what
     * {@code CanoeReferenceInsertionHandler} calls, so a reference in a {@code <script src>} sees the
     * application's allowlist while a bare {@code Canoe.encode(value, CTX_URI_RESOURCE)} sees the empty
     * one.
     */
    public String encode(String input) {
        int ctx = currentContext();
        if (ctx == CTX_URI_RESOURCE) {
            return HtmlEncoder.urlResource(input, trustedResourceOrigins);
        }
        return encode(input, ctx);
    }

    /**
     * Writes a string to output, encoding it properly in the process.
     *
     * @param input
     * @throws Exception
     */
    public void writeEncoded(String input) throws Exception {
        write(encode(input));
    }
}
