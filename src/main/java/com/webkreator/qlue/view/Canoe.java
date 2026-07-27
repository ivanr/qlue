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

    // Value 5 was CTX_CSS. Canoe suppresses CSS by design - ATTR_CSS returns CTX_SUPPRESS - so the
    // slot is left as a gap rather than reused, and no old caller can silently rebind to it.

    /**
     * A URL that loads a subresource or reroutes the page: {@code src} on {@code <script>},
     * {@code <iframe>}, {@code <frame>} and {@code <embed>}, {@code data} on {@code <object>}, and
     * {@code href} on {@code <link>} and {@code <base>}. Routed to
     * {@link HtmlEncoder#urlResource(String, java.util.List)}, which rejects an off-origin or
     * protocol-relative authority. Distinct from {@link #CTX_URI} because {@code <a href>} and
     * {@code <img src>} are open-redirect and referrer surfaces, not code-execution ones, and keep
     * the ordinary {@code url()} encoder.
     */
    public static final int CTX_URI_RESOURCE = 6;

    /**
     * The prefix every encoding error's message carries. {@link CanoeEncodingException} builds its
     * message with it, and it is kept as a compatibility surface for existing log lines and greps.
     *
     * <p>Nothing matches on this string: to recognise an encoding error use
     * {@link CanoeEncodingException#findIn(Throwable)}, which finds the type in a cause chain even
     * after a template engine has wrapped it, and to read the error without the decoration use
     * {@link CanoeEncodingException#getReason()} rather than stripping this prefix.
     */
    public static final String ERROR_PREFIX = "Encoding Error: ";

    /**
     * The length of the shared name buffer, and therefore one more than the longest tag or attribute
     * name Canoe will parse: the scan raises once it would fill the last slot, which is reserved for
     * the NUL terminator the name scan appends. The same constant decides both length checks —
     * {@code Tag name too long} in TAG_NAME and {@code Attribute name too long} in TAG_ATTR_NAME.
     *
     * <p>Neither limit is a security control: a name is template text, never attacker data, and
     * nothing downstream reads past {@code bufLen}. The cap exists because the buffer is fixed-size
     * by design — the scan is bounded, allocation-free and cannot be made to grow by output — so it
     * is what keeps a pathological name from being a pathological allocation. It also bounds the
     * application-configured plain-text allowlist in
     * {@link #normalisePlainTextAttributeNames(Collection)}, which refuses a name the tokenizer could
     * never buffer.
     */
    public static final int MAX_TAGNAME_LEN = 128;

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
     * {@code /} or {@code >}. This state is where that one character is judged, so that
     * {@code </scriptfoo>} stays inside the script element for Canoe exactly as it does for a
     * browser. It is still script data — {@link #currentContext()} answers {@link #CTX_JS} here,
     * exactly as it does for {@link #SCRIPT_END}.
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

    /** An attribute name nothing recognises, whose value is suppressed. */
    public static final int ATTR_UNKNOWN = 5;

    public static final int ATTR_ACTIONSCRIPT = 6;

    /**
     * A URL-bearing attribute name on an element that loads a subresource with it. Reached only
     * from {@link #setTagAttributeContext()}, which narrows {@link #ATTR_URI} to this when the tag
     * name says the value is a resource-loading sink; maps to {@link #CTX_URI_RESOURCE}.
     */
    public static final int ATTR_URI_RESOURCE = 7;

    // -----------------------------------------------------------------------------------------
    // Where in a URL the attribute-value scan is.
    // -----------------------------------------------------------------------------------------
    //
    // HtmlEncoder.urlResource() asks whether the VALUE a reference produces introduces an authority.
    // That is the wrong question whenever the template wrote literal URL text in front of the
    // reference, because the authority is then introduced by the two of them together and by neither
    // alone: <script src="/$path"> with path = "/attacker.example/x.js" renders
    // //attacker.example/x.js, and every character of the authority came from a value that carries
    // no authority at all. The encoder cannot see that, and cannot be made to: the fact that decides
    // it is not in the reference. So Canoe answers the positional half itself, with a small state
    // machine run over the value characters alongside the prefix scan.
    //
    // The states are the URL parser's front end, and only its front end: what is being tracked is
    // "is the authority still open", not the URL. Everything unrecognised resolves towards "open",
    // which refuses.

    /** Nothing of the value has been seen yet. The reference carries the whole authority, or none. */
    public static final int URLV_START = 0;

    /** The value so far could still be a scheme name: ASCII alpha, then alnum and {@code + - .}. */
    public static final int URLV_SCHEME = 1;

    /** Exactly one leading {@code '/'}. A second one opens an authority, so a value must not begin one. */
    public static final int URLV_SLASH = 2;

    /** {@code scheme:} has been seen and slashes are being skipped; the host begins at the next character. */
    public static final int URLV_AFTER_SCHEME = 3;

    /** Inside the authority: every character the reference emits is part of the host. */
    public static final int URLV_AUTHORITY = 4;

    /** Past the authority, or there was never one. Nothing after this point can move the host. */
    public static final int URLV_PATH = 5;

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

    /**
     * Whether an element has been emitted in this document, which is the precondition a DOCTYPE
     * declaration is tested against: a declaration below an element is refused.
     *
     * <p>Set at the point TAG_NAME commits to a tag — a start tag's first name character, or the
     * {@code '/'} of an end tag — and deliberately <em>not</em> set for the {@code '!'} that opens a
     * comment or a DOCTYPE, so a licence header or a generator stamp above the declaration is legal.
     * That is exactly the HTML Standard's boundary: its "initial" insertion mode ignores a comment
     * and stays there, and any tag moves the parser past it, after which a DOCTYPE token is a parse
     * error a browser ignores.
     *
     * @see #doctypeSeen
     */
    protected boolean elementSeen;

    /**
     * Whether a DOCTYPE declaration has already been accepted in this document.
     *
     * <p>A second DOCTYPE is <strong>ignored, with a warning</strong>, because a browser ignores it
     * too: it is a parse error in "before html" and after it, and the token is discarded. Refusing
     * the page would be strictness no consuming parser has, applied to the most ordinary composition
     * mistake in a templating system — a layout and an included fragment each declaring one. The
     * warning is at warn rather than debug because it fires at most once per document and names a
     * real authoring defect.
     *
     * <p>Tracked separately from {@link #elementSeen}, which carries the one DOCTYPE rejection that
     * remains: a declaration <em>after an element</em> is a template whose document order is wrong,
     * and Canoe has already emitted the element it would have applied to.
     *
     * <p>Set where the declaration is <em>admitted</em> — the {@code d} of {@code <!d}, before
     * DOCTYPE_TEST has spelt the rest of the word out. A misspelling raises from there and ends the
     * render, so there is no path on which this field can be true for a declaration that never
     * parsed.
     */
    protected boolean doctypeSeen;

    /**
     * Whether non-whitespace text has been written in the {@link #HTML} state, which is what decides
     * that a DOCTYPE declaration below it will be ignored by the browser.
     *
     * <p>Canoe accepts {@code hello<!DOCTYPE html>}; the HTML Standard does not. Its "initial"
     * insertion mode ignores whitespace, and any other character is a parse error that switches to
     * "before html" — so by the time the declaration arrives the document is already committed to
     * <strong>quirks mode</strong> and the DOCTYPE the author wrote does nothing. The input renders,
     * and the consequence is reported as a warning rather than left silent.
     *
     * <p>Whitespace does not set it, and that is not a detail: a template whose first line is a
     * Velocity directive or a comment emits a newline before the DOCTYPE, which is both extremely
     * common and exactly the case the standard ignores. Warning about it would make the diagnostic
     * noise and train its reader to ignore the real one.
     *
     * <p>Read only where a DOCTYPE is admitted, so it costs one assignment per text character and
     * nothing else. It keeps being maintained after the DOCTYPE for the same reason {@link
     * #doctypeSeen} does — a later declaration, if one arrives, gets the same judgement.
     */
    protected boolean textSeen;

    /**
     * The URL-bearing attribute names, whose values go through {@link HtmlEncoder#url(String)}.
     *
     * <p><code>xlink:href</code> needs no tokenizer change - {@link #isTagNameChar(char, int)}
     * accepts ':' - so it scans as one name.
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
     *       deliberately.
     *   <li><code>content</code> - a URL on exactly one element/attribute-value combination,
     *       <code>&lt;meta http-equiv="refresh" content="N; url=..."&gt;</code>, and
     *       <strong>deliberately left suppressed</strong> rather than given a URL context.
     *       Recognising the refresh URL would need two things Canoe does not have: the value of the
     *       <em>sibling</em> attribute <code>http-equiv="refresh"</code> - and Canoe scans attributes
     *       one at a time, never retains a prior attribute's value, and <code>content</code> may
     *       appear before <code>http-equiv</code> - and a parse of the <code>N; url=</code> prefix so
     *       that only the URL portion is encoded, which the per-reference encoding model cannot do at
     *       all: a reference is an opaque value encoded with one context, so Canoe never knows
     *       whether the literal <code>N; url=</code> prefix is part of the reference or of the
     *       surrounding template text. Routing every <code>content</code> to <code>url()</code>
     *       instead would percent-encode the prose in every meta description on the page. Suppression
     *       is fail-safe: a suppressed <code>content</code> renders empty, so no forced redirect
     *       occurs, and a meta refresh that legitimately needs a dynamic URL is a case for
     *       application code, not silent interpolation.
     *   <li><code>imagesrcset</code>, <code>xml:base</code>, <code>archive</code>,
     *       <code>classid</code>, <code>profile</code> - URL-bearing names that no ordinary template
     *       interpolates into. Suppression is strictly stronger than <code>url()</code>, which is a
     *       scheme filter and not an origin filter, so leaving them off this list costs security
     *       nothing and costs availability only where a template needs them.
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
     * {@code url()}. Each key is an element name; the values are the URL attributes on it that a
     * browser dereferences into an executable or page-controlling context.
     *
     * <ul>
     *   <li>{@code <script src>}, and SVG's {@code <script href>} and {@code <script xlink:href>} —
     *       arbitrary JavaScript with the page's full privileges. SVG 1.1 loads an external script
     *       with {@code xlink:href} and SVG 2 with {@code href}, and every shipping engine runs both.
     *   <li>{@code <iframe src>}, and {@code <frame src>} under its obsolete spelling — an attacker
     *       document in the page's frame tree. Framesets are obsolete in the standard and removed
     *       from no shipping engine, and it is the engines that decide whether a sink is live.
     *   <li>{@code <embed src>} — a plugin document.
     *   <li>{@code <object data>} — the object element's resource, script or document.
     *   <li>{@code <link href>} — a stylesheet or other subresource; an off-origin stylesheet can
     *       overlay, exfiltrate and restyle.
     *   <li>{@code <base href>} — reroutes <em>every</em> relative URL on the rest of the page, the
     *       widest blast radius of the group.
     * </ul>
     *
     * <p>The value is a set because an element may have several attributes that load code, and the
     * map is keyed by element because the same attribute name is a link on one element and code
     * execution on another.
     *
     * <p>Deliberately <em>not</em> here, and kept on the ordinary {@code url()} encoder: {@code <a
     * href>} and {@code <img src>} (and the other fetch-not-code names — {@code poster}, {@code cite},
     * {@code ping}, {@code srcset}, {@code formaction}, {@code action}, ...). An off-origin {@code <a
     * href>} is an open redirect and an off-origin {@code <img src>} is a referrer leak and a load;
     * neither is code execution, and rejecting an off-origin value from them would break the ordinary
     * "link to another site" and "hotlink an image" cases that are not a Canoe concern. Those remain
     * open-redirect and referrer surfaces by design.
     *
     * <p>Checked and deliberately left on {@code url()} with {@code <img src>}, so that the boundary is
     * a decision rather than an omission: {@code <svg><use href>} and {@code <svg><image href>} — the
     * first is refused cross-origin by every current engine and the second is an image — and
     * {@code <video src>}, {@code <audio src>}, {@code <source src>}, {@code <track src>} and
     * {@code <input type=image src>}, which fetch media under exactly the argument that keeps
     * {@code <img src>} here.
     */
    private static final Map<String, Set<String>> RESOURCE_LOADING_SINKS;

    static {
        Map<String, Set<String>> sinks = new LinkedHashMap<>();
        sinks.put("script", resourceAttributes("src", "href", "xlink:href"));
        sinks.put("iframe", resourceAttributes("src"));
        sinks.put("frame", resourceAttributes("src"));
        sinks.put("embed", resourceAttributes("src"));
        sinks.put("object", resourceAttributes("data"));
        sinks.put("link", resourceAttributes("href"));
        sinks.put("base", resourceAttributes("href"));
        RESOURCE_LOADING_SINKS = Collections.unmodifiableMap(sinks);
    }

    private static Set<String> resourceAttributes(String... names) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(names)));
    }

    /**
     * The attribute names whose value the browser treats as plain text, and which therefore reach
     * {@link HtmlEncoder#htmlAttr(String)}.
     *
     * <p>An attribute name nothing here recognises is ATTR_UNKNOWN and is suppressed. The
     * classification has to be written as an allowlist of plain-text names rather than as a denylist
     * of dangerous ones, because a denylist puts every name nobody thought of on the wrong side of
     * it.
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
     *   <li><code>sandbox</code>, <code>rel</code>, <code>integrity</code> - the HTML parser
     *       consumes the decoded value as a <em>directive</em>, so no encoding of
     *       <code>allow-same-origin</code> means anything other than <code>allow-same-origin</code>.
     *       Encoding is not insufficient here, it is inapplicable, and suppression is not the
     *       preferred fix but the only one.
     *   <li><code>nonce</code> - inert as text, which is true and is the wrong test. An attacker who
     *       chooses the nonce can author a <code>&lt;script nonce&gt;</code> the content security
     *       policy then admits, which defeats the control rather than escaping the attribute.
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
     * <p>Three names that look like directives and that this allowlist deliberately <em>keeps</em>:
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
     *       which is out of scope by this list's own criterion - a name in the document's namespace
     *       is not a directive a browser algorithm consumes - and it is named here rather than left
     *       implicit, because <code>id</code> being out of scope and <code>form</code> being out of
     *       scope are the same decision and only one of them is obvious.
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
     * is one whose suppression <em>is</em> the security property, so adding it would undo that
     * through configuration, in a place no test in the suite would look.
     *
     * <p>Names Canoe classifies before it consults the allowlist at all - the URL set,
     * <code>style</code> and anything beginning <code>on</code> - are rejected too, so that a
     * configuration which would have had no effect fails at startup rather than looking as though it
     * worked.
     *
     * <p>The second group is the one that is easiest to leave out and is listed for a reason:
     * <code>imagesrcset</code>, <code>xml:base</code>, <code>archive</code>, <code>classid</code>
     * and <code>profile</code> are <em>URL-bearing</em> names deliberately not routed to
     * <code>url()</code>, on the argument that suppression is strictly stronger and no ordinary
     * template interpolates into them. That argument only holds while they stay suppressed. Adding
     * one to the plain-text allowlist would put a URL sink on <code>html()</code>, whose output the
     * HTML parser decodes before the URL parser ever sees it, which is strictly worse than the
     * <code>url()</code> routing they were declined.
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
     * page's own — the CDN allowlist, per Canoe instance and never static for the same reason
     * {@link #extraPlainTextAttributes} is. Empty by default, which means "same-origin-relative URLs
     * only" on those sinks. {@code VelocityViewFactory} owns the configuration and hands the parsed
     * list to every Canoe it constructs.
     */
    private final List<HtmlEncoder.TrustedOrigin> trustedResourceOrigins;

    /**
     * The name of the element whose tag is currently being parsed, in lower case, or null when the
     * parser is not inside a tag whose name has been read.
     *
     * <p>The shared buffer cannot carry this: {@code buf} is reused for the first attribute name the
     * moment one starts, so by the time {@link #setTagAttributeContext()} runs the element name would
     * be gone and {@code src} on {@code <script>} would be indistinguishable from {@code src} on
     * {@code <img>}. This field is what makes the origin policy on the resource-loading elements
     * possible; {@link #isResourceLoadingSink(String)} reads it.
     *
     * <p>Deliberately unread on the {@code content} path: recognising a
     * {@code <meta http-equiv="refresh" content>} URL needs the sibling attribute's value as well as
     * the tag name, and {@code content} is left suppressed instead (see {@link #URL_ATTRIBUTE_NAMES}).
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
     *       delimiter after the name, not the name alone: at {@code </scriptfoo} there is no end tag,
     *       so the field stays as it was — null, because the {@code '<'} cleared it.
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
     * The name of the current attribute when it is a URL-bearing one, for the diagnostic in
     * {@link #encodeResourceUrl(String)}; null otherwise. Assigned on the path that classifies a URL
     * name, so it costs an assignment and no branch, and cleared at the top of
     * {@link #setTagAttributeContext()} with its sibling so it can never report a name from an
     * earlier attribute.
     */
    protected String urlAttributeName;

    /**
     * Where in a URL the current attribute value has got to — one of the {@code URLV_*} constants —
     * which is what decides whether a reference in a resource-loading sink can complete or extend the
     * authority.
     *
     * <p>Reset where the value <em>begins</em>, which is the {@code '='} in TAG_ATTR_NAME_AFTER and
     * not the first value character: a reference sitting directly after the equals sign
     * ({@code <a href=$x>}) is inserted while the parser is still in TAG_ATTR_VALUE_BEFORE, and it
     * has to be judged too.
     *
     * <p>Advanced on every character the value scan sees, which includes the characters the encoders
     * themselves emit — Velocity writes an encoded reference back through this writer — so a value
     * that ends in a {@code '/'} moves the position exactly as a template literal {@code '/'} does.
     * That is what makes {@code <script src="$base$path">} with {@code base = "/"} judge {@code $path}
     * from URLV_SLASH rather than from URLV_START.
     */
    protected int urlValueState = URLV_START;

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
     *                                  or is one Canoe refuses to treat as text
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
                        + " prefix rule has no exceptions.");
            }

            if (URL_ATTRIBUTE_NAMES.contains(name) || NAMES_THAT_MAY_NOT_BE_ADDED.contains(name)) {
                throw new IllegalArgumentException("Refusing to treat " + name + " as plain text:"
                        + " Canoe classifies it before it consults the application allowlist, or its"
                        + " suppression is what makes it safe. The per-name reasoning is on Canoe's"
                        + " URL_ATTRIBUTE_NAMES and PLAIN_TEXT_ATTRIBUTE_NAMES.");
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
     * really is one.
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
     * whitespace here. Matching the standard exactly is the whole point of the check: for every
     * character a wider set would add, Canoe would leave script data where the browser stays in it,
     * and encode the rest of the page for a context it is not in.
     */
    private static boolean isEndTagNameDelimiter(char c) {
        return (c == ' ') || (c == '\t') || (c == '\n') || (c == '\f') || (c == '\r')
                || (c == '/') || (c == '>');
    }

    /**
     * Whether a character in body text is whitespace the HTML Standard's "initial" insertion mode
     * ignores, and therefore text a DOCTYPE declaration may still follow.
     *
     * <p>The standard's set is tab, LF, FF, CR and space; this one omits FF, and the omission is
     * deliberate rather than an oversight. A form feed cannot reach the HTML state at all — the C0
     * guard in {@link #HTML} rejects every character below 0x20 except tab, CR and LF — so a
     * {@code c == '\f'} test here would be a branch no input can take. The four that remain are the
     * four that can occur.
     *
     * <p>{@link Character#isWhitespace(char)} is deliberately not used either: it is a Unicode fold
     * that also accepts U+2028, U+3000 and the other space separators, none of which the standard's
     * "initial" mode ignores, so it would silently withhold the warning for exactly the exotic input a
     * reader would most want it for.
     */
    private static boolean isInitialModeWhitespace(char c) {
        return (c == ' ') || (c == '\t') || (c == '\n') || (c == '\r');
    }

    /**
     * ASCII-only case folding, for the two states that match an end tag name against a literal.
     *
     * <p>The HTML Standard's script-data-end-tag-name and rawtext-end-tag-name states accept only
     * <em>ASCII</em> upper alpha and ASCII lower alpha into the name; every other code point is
     * "anything else" and makes the whole run character data. {@link Character#toLowerCase(char)} is
     * a Unicode fold and is wider than that in one respect that matters here: it maps U+0130 LATIN
     * CAPITAL LETTER I WITH DOT ABOVE to {@code 'i'}, so an end tag spelling {@code script} with
     * U+0130 would match {@code /script} and close the element for Canoe while every browser stayed
     * in script data. A sweep of the whole BMP finds U+0130 to be the only non-ASCII code point whose
     * {@code Character.toLowerCase()} lands in {@code /script} or {@code /style}; the point of
     * writing the fold out is that no future JDK Unicode update can add a second one.
     *
     * <p>Deliberately not applied to {@code TAG_NAME}, which folds the same way for the opening
     * {@code <script>}: there the divergence runs the other way — Canoe enters {@code SCRIPT} and
     * suppresses where the browser sees an unknown element — which is fail-closed.
     */
    private static char asciiToLowerCase(char c) {
        return ((c >= 'A') && (c <= 'Z')) ? (char) (c + ('a' - 'A')) : c;
    }

    /**
     * Whether a URL parser would remove this character from the value before reading it — which is
     * what would otherwise make Canoe's view of where a scheme begins differ from the browser's.
     *
     * <p>The URL Standard's basic parser removes leading and trailing C0 controls and spaces from the
     * input, and removes <em>all</em> ASCII tab, LF and CR from anywhere in it. So
     * <code>&lt;a href=" javascript:f('$id')"&gt;</code> and
     * <code>&lt;a href="java&lt;TAB&gt;script:f('$id')"&gt;</code> are both {@code javascript:} URLs to
     * every engine, while a value scan that counted characters would see eleven and twelve of them
     * and give up at ten. Skipping the stripped characters is what keeps one character of whitespace
     * from deciding whether a prefix is recognised.
     *
     * <p>Trailing strip is deliberately not modelled: the scan runs left to right and cannot know a
     * character is trailing, and treating it as significant is the conservative direction for both
     * callers — one more character buffered can only fail to match a prefix, and one more character
     * advanced can only move the URL position towards "authority open", which refuses.
     *
     * @param c       the value character
     * @param atStart whether nothing significant has been consumed yet, which is the only place the
     *                space and C0 strip applies
     */
    private static boolean isUrlStripped(char c, boolean atStart) {
        if ((c == '\t') || (c == '\n') || (c == '\r')) {
            return true;
        }

        return atStart && (c <= ' ');
    }

    /** ASCII alpha, which is the only thing a URL scheme may begin with. */
    private static boolean isSchemeStart(char c) {
        return ((c >= 'a') && (c <= 'z')) || ((c >= 'A') && (c <= 'Z'));
    }

    /** The characters a URL scheme may continue with: ASCII alphanumeric and {@code + - .}. */
    private static boolean isSchemeChar(char c) {
        return isSchemeStart(c) || ((c >= '0') && (c <= '9'))
                || (c == '+') || (c == '-') || (c == '.');
    }

    /**
     * Moves {@link #urlValueState} on by one attribute-value character.
     *
     * <p>Run for every attribute value, not only the URL-bearing ones, because
     * {@link #detectAttributePrefix()} can narrow a name-derived context mid-value and the position
     * has to be right either way. It is a switch over six states and costs nothing measurable.
     *
     * <p>The transitions are the URL parser's, reduced to the one question this answers:
     *
     * <ul>
     *   <li>a leading {@code '/'} is URLV_SLASH, and a second one opens the authority — which is
     *       precisely the protocol-relative form, and precisely what {@code <script src="/$path">}
     *       lets a value complete;
     *   <li>{@code scheme:} is URLV_AFTER_SCHEME, where any number of slashes are skipped and the
     *       host begins at the first character that is not one. This treats an opaque scheme
     *       ({@code mailto:}) as opening an authority, which it does not — deliberately, because the
     *       error is towards refusal and no template writes {@code <script src="mailto:$x">};
     *   <li>the authority ends at the first {@code / ? #}, after which nothing can move the host and
     *       URLV_PATH absorbs the rest;
     *   <li>anything that is neither a slash nor a scheme start at URLV_START is a relative path, a
     *       query or a fragment, none of which can grow an authority.
     * </ul>
     */
    private void advanceUrlValueState(char c) {
        if (isUrlStripped(c, urlValueState == URLV_START)) {
            return;
        }

        switch (urlValueState) {
            case URLV_START:
                if (c == '/') {
                    urlValueState = URLV_SLASH;
                } else if (isSchemeStart(c)) {
                    urlValueState = URLV_SCHEME;
                } else {
                    urlValueState = URLV_PATH;
                }
                break;

            case URLV_SCHEME:
                if (c == ':') {
                    urlValueState = URLV_AFTER_SCHEME;
                } else if (!isSchemeChar(c)) {
                    urlValueState = URLV_PATH;
                }
                break;

            case URLV_SLASH:
                urlValueState = (c == '/') ? URLV_AUTHORITY : URLV_PATH;
                break;

            case URLV_AFTER_SCHEME:
                if (c != '/') {
                    urlValueState = URLV_AUTHORITY;
                }
                break;

            case URLV_AUTHORITY:
                if ((c == '/') || (c == '?') || (c == '#')) {
                    urlValueState = URLV_PATH;
                }
                break;

            default:
                // URLV_PATH is absorbing: the authority is closed and cannot be reopened.
                break;
        }
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
            // [offset, offset + len). The bound is offset + len, not len: len
            // is a count, not an end index.
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
     * allowlist is validated against, so a configured name the tokenizer could never produce is
     * refused rather than left as a set entry nothing can match.
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
     * context is left exactly as it was. All three of the prefixes this method
     * can assign map to a suppressing context, so narrowing is the only
     * direction that is safe: widening on the first colon in a value would
     * unsuppress a style attribute the moment a CSS property name was written
     * in front of the reference, and an on* handler the moment its body held an
     * object literal or a ternary.
     *
     * <p>The comparison is length-checked against bufLen rather than made of
     * fixed buffer indices. The value scan writes no NUL terminator - only the
     * name scan does - so a fixed-index test would read whatever an earlier tag
     * or attribute name left in the buffer, and whether "javascript:" was
     * recognised would depend on markup elsewhere on the page. Comparing bufLen
     * characters against a literal cannot read anything the value did not
     * write.
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
     * attribute value left behind. Clearing on reuse is what keeps the buffer
     * from meaning anything other than "what the current name or value has
     * written".
     */
    private void resetBuffer() {
        Arrays.fill(buf, '\0');
        bufLen = 0;
    }

    /**
     * Determines context for tag attributes based on the attribute name.
     *
     * <p>The event-handler rule is a <em>prefix</em> rule: any attribute whose
     * name begins "on" is JavaScript. There is no benign exception worth carving
     * out of it. An attribute whose name begins "on" and which no engine will
     * ever fire is inert either way, so suppressing it costs a template author
     * nothing; a name that is missed is arbitrary script execution, because
     * html()'s character references are decoded by the HTML parser before the
     * value is compiled as JavaScript. The rule also cannot go stale: every one
     * of the ninety-four event handler content attributes the HTML Standard
     * defines is covered, and so is every one it adds in future.
     *
     * <p>Everything below the prefix rule is a lookup of the buffered name in a
     * declared set. The name is read out of buf as exactly the characters this
     * attribute's own scan wrote - bufLen counts them, plus the NUL terminator
     * TAG_ATTR_NAME appends - so a name cannot inherit a byte from an earlier
     * tag, attribute or value. The whole of what reaches each encoder is
     * therefore visible in one place: see URL_ATTRIBUTE_NAMES and
     * PLAIN_TEXT_ATTRIBUTE_NAMES.
     *
     * <p><strong>The default is fail-closed.</strong> An unrecognised name is
     * ATTR_UNKNOWN, which suppresses, rather than ATTR_HTML. html() is worthless
     * for any attribute whose decoded value a second parser or a browser
     * algorithm consumes, so a classifier whose misses are silent cannot
     * default to it; the allowlist plus the application extension point are what
     * keep the cost of that from being paid in $_x.asis() calls.
     */
    protected void setTagAttributeContext() {
        // Fail closed. A name that reaches the end of this method unclassified is
        // suppressed, and says so at debug level when a reference lands in it.
        attributeContext = ATTR_UNKNOWN;
        unknownAttributeName = null;
        urlAttributeName = null;

        // Any event handler. The prefix rule replaces the on* table entirely; see
        // the method javadoc for why there is no exception list.
        if (bufferedNameStartsWith("on")) {
            attributeContext = ATTR_JS;
            return;
        }

        String name = bufferedName();

        if (URL_ATTRIBUTE_NAMES.contains(name)) {
            // The same URL name is a code-execution sink on some elements and an open-redirect
            // surface on others, and the tag name is what tells them apart. src on <script> rejects
            // an off-origin authority; src on <img> does not.
            attributeContext = isResourceLoadingSink(name) ? ATTR_URI_RESOURCE : ATTR_URI;
            urlAttributeName = name;
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
     * dereferences it into a code-execution or page-controlling context. Reads {@link #tagName},
     * which stays available for the duration of the tag, so a {@code src} knows whether it is on a
     * {@code <script>} or an {@code <img>}. A null {@code tagName} needs no guard: this method runs
     * only while an attribute is being parsed, which is always inside a named tag, and even if it were
     * not, {@code RESOURCE_LOADING_SINKS.get(null)} is null and the answer is the correct "not a
     * resource sink" either way.
     *
     * <p>A set membership test, because an element may have more than one attribute that loads code —
     * SVG's {@code <script>} has three. See {@link #RESOURCE_LOADING_SINKS}.
     */
    private boolean isResourceLoadingSink(String attributeName) {
        Set<String> attributes = RESOURCE_LOADING_SINKS.get(tagName);
        return (attributes != null) && attributes.contains(attributeName);
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
                    } else {
                        // Non-markup character

                        // Do not allow characters below 0x20, except \t, \n and \r
                        if ((c < 0x20)
                                && ((c != '\t') && (c != '\r') && (c != '\n'))) {
                            raiseError("Invalid character detected in output");
                            return;
                        }

                        // Remember that the document has text in it, which is what makes a
                        // DOCTYPE below this point one the browser will ignore. Whitespace does
                        // not count, because the HTML Standard's "initial" insertion mode
                        // ignores it and a template's first line routinely emits some.
                        if (!isInitialModeWhitespace(c)) {
                            textSeen = true;
                        }
                    }
                    break;

                case COMMENT_OPEN_OR_DOCTYPE:
                    if (c == '-') {
                        state = COMMENT_OPEN_2;
                    } else if ((c == 'D') || (c == 'd')) {
                        // A DOCTYPE declaration has to come before the first element. That is
                        // the one DOCTYPE shape Canoe refuses, and it is refused because a
                        // browser cannot honour it and ordinary composition does not produce
                        // it: by the time an element has been emitted the document's mode is
                        // already decided, and a template that declares its DOCTYPE after its
                        // markup has its document order wrong.
                        //
                        // The other two shapes are accepted with a warning. A browser IGNORES a
                        // second declaration and IGNORES one that follows text (going quirks for
                        // the latter), so refusing either would be strictness no consuming
                        // parser has, applied to the two mistakes template composition produces
                        // most often - a layout and an included fragment each declaring one, and
                        // a fragment that emits a line of text above the layout's declaration.
                        // See doctypeSeen and textSeen for the reasoning in full.
                        if (elementSeen) {
                            raiseError("DOCTYPE declaration must precede the first element");
                        } else {
                            if (doctypeSeen) {
                                log.warn("Canoe ignored a duplicate DOCTYPE declaration (line: {},"
                                                + " pos: {}). A browser keeps the first declaration"
                                                + " and discards this one; the usual cause is a"
                                                + " layout and an included fragment each declaring"
                                                + " one, and the fix is to remove the declaration"
                                                + " from the fragment.",
                                        currentLine, currentPos);
                            }

                            if (textSeen) {
                                log.warn("Canoe accepted a DOCTYPE declaration that follows text"
                                                + " (line: {}, pos: {}). A browser ignores a"
                                                + " declaration that is not the first thing in the"
                                                + " document and renders the page in QUIRKS MODE,"
                                                + " so this declaration has no effect; move it above"
                                                + " every character of output.",
                                        currentLine, currentPos);
                            }

                            doctypeSeen = true;
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
                        // '>' that follows any run of dashes still closes the comment. Dropping
                        // back to COMMENT here would mean <!--a---> never closes and every
                        // reference for the rest of the page renders empty.
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
                        if (c == '!') {
                            // A bang declaration - a comment or a DOCTYPE. Neither is an
                            // element, so elementSeen is deliberately left alone, which is
                            // what makes a comment above the DOCTYPE legal.
                            state = COMMENT_OPEN_OR_DOCTYPE;
                            continue;
                        }

                        // Anything else here begins a tag: a start tag's name, or the '/'
                        // of an end tag. Either one moves a browser past the HTML
                        // Standard's "initial" insertion mode, after which a DOCTYPE is
                        // ignored - so this is where "an element has been emitted"
                        // becomes true, whatever the rest of the name turns out to be.
                        elementSeen = true;

                        if (c == '/') {
                            // Closing tag
                            buf[bufLen++] = '/';
                            closingTag = true;
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

                        // Char after tag name must be whitespace, '>' or '/'.
                        //
                        // '/' is accepted because a solidus straight after a tag name is the
                        // self-closing start tag of XHTML and of every serializer that emits
                        // one, so <br/> and <br /> have to agree. Nothing else is needed to
                        // accept it: the branch below re-processes this character in the TAG
                        // state, which is where the '/' belongs.
                        if ((Character.isWhitespace(c) == false) && (c != '>') && (c != '/')) {
                            raiseError("Invalid character after tag name");
                            return;
                        }

                        // Keep the element name past the point where buf is reused
                        // for attribute names. Already lower case - the scan folds
                        // as it buffers - and without the leading '/' of an end
                        // tag, which closingTag records. bufLen counts the name
                        // plus the NUL terminator written above.
                        tagName = closingTag
                                ? new String(buf, 1, bufLen - 2)
                                : new String(buf, 0, bufLen - 1);

                        // By default, the next state
                        // (inside tag) is HTML
                        nextState = HTML;

                        // Detect <script> and <style> tags. A comparison of the name
                        // the current scan wrote, not of fixed buffer indices, so
                        // no earlier name's residue can decide it.
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
                        // The value begins here, and it is here rather than at the first value
                        // character because a reference can be inserted before there is one:
                        // <a href=$x> is judged in TAG_ATTR_VALUE_BEFORE.
                        urlValueState = URLV_START;
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
                        // Keep track of where in a URL this value has got to, so that a
                        // resource-loading reference can be judged by whether the authority is
                        // still open. Run for every attribute; it decides nothing for the
                        // others and costs one switch.
                        advanceUrlValueState(c);

                        if (bufLen != -1) {
                            if (isUrlStripped(c, bufLen == 0)) {
                                // A character the URL parser removes must not shift the
                                // ten-character prefix window, or " javascript:" and
                                // "java<TAB>script:" stop being recognised while staying
                                // javascript: URLs to every engine. Skipped rather than
                                // buffered, so the buffer holds what the browser will read.
                            } else if (c == ':') {
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
                    // fold would let an end tag spelled with U+0130 close the element.
                    if (asciiToLowerCase(c) == jsEnd.charAt(bufLen)) {
                        if (jsEnd.length() == bufLen + 1) {
                            // The name matched. That is not enough to leave script
                            // data: the HTML Standard checks the character after the
                            // name too, so the decision moves to SCRIPT_END_NAME and
                            // closingTag/tagName are assigned there, once the end tag
                            // is confirmed.
                            state = SCRIPT_END_NAME;
                        } else {
                            bufLen++;
                        }
                    } else {
                        // Not "</script" after all. Re-process the character rather
                        // than dropping it: it may itself be the '<' that opens the
                        // real end tag, which is what "<</script>" is. Dropping it
                        // would leave the rest of the page inside the script element
                        // with every reference in it suppressed.
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
                        // are kept truthful so that the resource-sink lookup
                        // cannot mistake the tail of an end tag for an opening
                        // <script>.
                        closingTag = true;
                        tagName = jsEnd.substring(1);

                        // TAG has not seen this character, and it carries meaning
                        // there: '>' ends the tag, '/' begins "</script/>".
                        charNeedsProcessing = true;
                    } else {
                        // "</scriptfoo": not an end tag at all. A browser emits those
                        // characters as script data and stays in the script element,
                        // so Canoe does too. Re-process the character, because a '<'
                        // here opens a fresh end tag.
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
     * Raise an error: put the parser in {@link #INVALID} and throw.
     *
     * <p>The exception is a {@link CanoeEncodingException} rather than a bare {@link IOException} so
     * that it can still be recognised after a template engine has wrapped it, and so that it carries
     * the line and position as fields rather than only inside the message.
     *
     * @param errorMessage the error, without the prefix and without the coordinates
     * @throws CanoeEncodingException always
     */
    private void raiseError(String errorMessage) throws CanoeEncodingException {
        state = INVALID;
        CanoeEncodingException error =
                new CanoeEncodingException(errorMessage, currentLine, currentPos);
        this.errorMessage = error.getMessage();
        throw error;
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
     * <p>{@link #TAG_ATTR_VALUE_BEFORE} gets the same answer as {@link #TAG_ATTR_VALUE}, so that an
     * unquoted value is encoded by its attribute's name rather than suppressed. See the case label
     * below for why that is safe. {@code <a href=$x>} inserts the reference while the parser is
     * still waiting for the quote that decides the value's quoting style, and that quote never
     * arrives; {@code <a href=/p/$y>} reaches {@code TAG_ATTR_VALUE} because one literal character
     * is enough, so without the shared arm the two spellings would disagree.
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

            // An unquoted value, judged by the attribute's name. The state is entered
            // from TAG_ATTR_NAME_AFTER on '=', which is only reachable through TAG_ATTR_NAME, which
            // calls setTagAttributeContext() before it leaves - so attributeContext is this
            // attribute's own classification and never a leftover from an earlier one. What it has
            // not been through is detectAttributePrefix(), which runs on value characters and can
            // only ever narrow; a reference sitting directly after the '=' has no value characters
            // in front of it, so there is nothing to narrow from and the name-derived answer is the
            // whole answer.
            //
            // WHY THIS IS SAFE, and it is the whole argument for the case label. An unquoted value
            // ends at whitespace or '>' - for this tokenizer (TAG_ATTR_VALUE, QUOTE_NONE) and for
            // the HTML Standard's attribute-value-unquoted state, which additionally treats '"',
            // '\'', '<', '=' and '`' as a parse error that stays *inside* the value. And the first
            // character decides the quoting: a leading '"' or '\'' would be read as an opening quote
            // here and in the standard's before-attribute-value state. So routing this state is safe
            // exactly when no encoder reachable from it can emit whitespace or '>' anywhere, or a
            // quote at the front. Checked against each of them rather than assumed:
            //
            //   ATTR_HTML -> HtmlEncoder.htmlAttr(), which is html(): everything outside [A-Za-z0-9]
            //     becomes a character reference, so space is "&#32;", '>' is "&gt;", '"' is "&quot;"
            //     and '\'' is "&#39;". A C0 control becomes the four printable characters \xNN. The
            //     output alphabet is alphanumerics plus '&', '#', ';' and '\', and holds no
            //     terminator. (The character reference is decoded into the value by the browser, not
            //     re-tokenized: the character-reference state appends to the current attribute value
            //     and returns, so "&#32;" is a space *in* the value and not the end of it.)
            //   ATTR_URI -> HtmlEncoder.url(), whose alphabet is the unreserved set [A-Za-z0-9-._~],
            //     the per-component safe delimiters (AUTHORITY_SAFE, PATH_SAFE, RELATIVE_PATH_SAFE,
            //     QUERY_SAFE - none of which contains a quote, a space, '<' or '>'), the structural
            //     "//", ':', '?', '#' it emits itself, "&amp;" for an ampersand, and %XX escapes.
            //     Anything else, including every whitespace character and every quote, is
            //     percent-escaped. It cannot start with a quote: '"' is %22 and '\'' is %27.
            //   ATTR_URI_RESOURCE -> HtmlEncoder.urlResource(), which returns either url()'s output
            //     or the empty string, so it inherits the property above.
            //   ATTR_JS, ATTR_CSS, ATTR_DATA, ATTR_ACTIONSCRIPT, ATTR_UNKNOWN -> the empty string.
            //
            // The empty string is the remaining case. Nothing is written, the state stays
            // TAG_ATTR_VALUE_BEFORE, and the template's own next character is handled exactly as it
            // was before the reference existed. A non-empty value moves the machine to
            // TAG_ATTR_VALUE with QUOTE_NONE on its first character, which is the same place
            // `<a href=/p/$y>` was already reaching.
            //
            // The one thing an empty value does cost is recorded rather than hidden: an unquoted
            // attribute with no value is not an attribute with an empty value. `<img src= alt="a">`
            // is ONE attribute to every tokenizer, this one included - the browser reads `alt="a"`
            // as src's unquoted value - so the following attribute is swallowed. That is true of a
            // legitimately empty model value too, so it is a property of unquoted values rather than
            // of this routing. Emitting `""` here instead would repair `<img src=$x alt="a">` and
            // break `<a href=$base/p>`, and would make encode() depend on the parser's position; the
            // template-level answer - quote the value - has no such trade. See
            // UnquotedAttributeValueTest.anEmptyUnquotedValueSwallowsTheNextAttribute. What keeps
            // that a data-loss bug rather than a routing one is that the swallowed region - which
            // may hold another reference, not only literal text - is one attribute value to both
            // tokenizers, so a reference inside it is encoded for the SWALLOWING attribute's
            // classification, which is the classification the browser applies to those bytes too:
            // .aSecondReferenceInsideTheSwallowedRegionKeepsTheSwallowingAttributesContext.
            //
            // UnquotedAttributeValueTest.noEncoderReachableFromAnAttributeValueCanTerminateAn
            // UnquotedOne is the executable form of this argument: it sweeps every corpus payload
            // through every context this arm can return and fails if any output carries a terminator
            // or opens with a quote. If a future encoder can emit one - a CSS encoder wired into
            // ATTR_CSS, a real JavaScript encoder behind CTX_JS - that test fails, and this case
            // label has to be reconsidered rather than the test relaxed.
            case TAG_ATTR_VALUE_BEFORE:
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

                    // ATTR_CSS (the `style` attribute) is suppressed, not CSS-escaped: Canoe refuses
                    // to interpolate into CSS by design. A `style` value is decoded in series - HTML
                    // character references first, then the CSS tokenizer - so an encoder correct
                    // against all of it is a project rather than a line, and until one is built
                    // `style` values render empty. There is deliberately no CTX_CSS.
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
                // A template that has to write a value into a script element does it explicitly,
                // with $_x.js().
                return EMPTY_STRING;
            case CTX_URI:
                return HtmlEncoder.url(input);
            case CTX_URI_RESOURCE:
                // No instance in hand, so no configured allowlist: the safe default, which rejects
                // every off-origin authority. The instance path {@link #encode(String)} supplies the
                // application's trusted origins.
                return HtmlEncoder.urlResource(input, Collections.<HtmlEncoder.TrustedOrigin>emptyList());
            // There is no CTX_CSS: currentContext() routes ATTR_CSS to CTX_SUPPRESS, so no CSS
            // context is ever produced. See currentContext()'s ATTR_CSS case for the reasoning.
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
            return encodeResourceUrl(input);
        }
        return encode(input, ctx);
    }

    /**
     * Encodes a value for a resource-loading URL sink, judged by <em>where in the URL it sits</em>.
     *
     * <p>{@link HtmlEncoder#urlResource(String, List)} rejects a value whose own encoded output
     * introduces an authority. That is the whole answer only when the value <em>is</em> the URL. When
     * the template wrote literal URL text in front of the reference, the authority belongs to the two
     * of them together: {@code <script src="/$path">} with {@code path = "/attacker.example/x.js"}
     * renders {@code //attacker.example/x.js}, and every character of that host came from a value
     * that carries no authority at all. The encoder cannot see the literal and so cannot be the place
     * this is decided; {@link #urlValueState} is.
     *
     * <table>
     *   <caption>What each position permits</caption>
     *   <tr><th>Position</th><th>Answer</th></tr>
     *   <tr><td>{@link #URLV_START}, {@link #URLV_SCHEME}, {@link #URLV_PATH}</td>
     *       <td>{@code urlResource()}, unchanged. Either the value carries the whole authority and is
     *           judged on it, or the authority is closed and nothing can move the host.</td></tr>
     *   <tr><td>{@link #URLV_SLASH}</td>
     *       <td>{@code urlResource()}, and then refuse an output that begins with {@code '/'}: the
     *           literal slash and that one make {@code //host}. A value that begins anything else is
     *           an ordinary path segment.</td></tr>
     *   <tr><td>{@link #URLV_AFTER_SCHEME}, {@link #URLV_AUTHORITY}</td>
     *       <td>Refuse. The reference lands where the browser is still reading the host, and no
     *           encoding of a hostname means anything other than that hostname.</td></tr>
     * </table>
     *
     * <p>A refusal is the empty string, which is what Canoe writes for every suppressed reference, and
     * it is logged at debug level beside the unrecognised-attribute diagnostic for the same reason:
     * a value that vanishes with no diagnostic is what sends a developer to {@code $_x.asis()}.
     *
     * <p><strong>{@link #CTX_URI} is deliberately not gated the same way.</strong> {@code <a
     * href="/$slug">} with a payload of {@code /attacker.example} is still an open redirect and
     * {@code <img src="//cdn$p">} is still a referrer leak, because that is the same outcome
     * {@code <a href="$u">} already has at offset 0 and those sinks are open-redirect and referrer
     * surfaces by design. Gating the concatenated spelling while the direct spelling is accepted
     * would be an inconsistency rather than a fix.
     */
    private String encodeResourceUrl(String input) {
        if ((urlValueState == URLV_AFTER_SCHEME) || (urlValueState == URLV_AUTHORITY)) {
            log.debug("Canoe suppressed a reference that lands inside the authority of the"
                            + " resource-loading URL attribute \"{}\" on <{}> (line: {}, pos: {})."
                            + " A value there chooses the host the resource is loaded from; put the"
                            + " whole URL in one reference so it can be checked against the trusted"
                            + " origins, or move the reference past the '/' that ends the host.",
                    urlAttributeName, tagName, currentLine, currentPos);
            return EMPTY_STRING;
        }

        String encoded = HtmlEncoder.urlResource(input, trustedResourceOrigins);

        if ((urlValueState == URLV_SLASH) && (encoded != null) && encoded.startsWith("/")) {
            log.debug("Canoe suppressed a reference beginning \"/\" after the leading \"/\" of the"
                            + " resource-loading URL attribute \"{}\" on <{}> (line: {}, pos: {})."
                            + " The two together are a protocol-relative \"//host\", which loads the"
                            + " resource from an origin the value chooses.",
                    urlAttributeName, tagName, currentLine, currentPos);
            return EMPTY_STRING;
        }

        return encoded;
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
