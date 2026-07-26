package com.webkreator.qlue.view.canoe.corpus;

/**
 * What consumes the value after the HTML parser has finished with it.
 *
 * <p>This is the distinction the security review turns on. In body text the HTML parser renders a
 * character reference as a character and stops, so entity encoding ends the matter. In an attribute
 * value the parser <em>decodes</em> the reference and hands the decoded result to a second parser,
 * which sees the attacker's original bytes. Same encoder, opposite outcome, entirely because of what
 * consumes the value afterward.
 */
public enum SinkKind {

    /** Body text, RCDATA, or RAWTEXT. Consumed by the HTML parser and rendered; nothing follows. */
    HTML_TEXT,

    /** An attribute whose value the browser treats as plain text: {@code id}, {@code alt}, {@code title}. */
    PLAIN_TEXT_ATTR,

    /** An event handler attribute, or the body of a {@code javascript:} URL. Compiled as script. */
    JAVASCRIPT,

    /** A {@code style} attribute or {@code <style>} body. Parsed as CSS. */
    CSS,

    /** An attribute the browser resolves as a URL and may fetch or navigate to. */
    URL,

    /** An attribute parsed as HTML in its own right — {@code srcdoc}. Needs double encoding. */
    MARKUP,

    /** {@code content} on {@code <meta http-equiv=refresh>}: a delay and a URL in one value. */
    REFRESH,

    /**
     * An attribute whose decoded value the HTML parser itself acts on as a <em>switch that turns a
     * security control on or off</em> — {@code sandbox}, {@code rel}, {@code integrity}, {@code
     * nonce}.
     *
     * <p>A fifth category the review's "JavaScript, CSS, a URL, or markup" framing does not cover, and
     * the one Canoe's {@code html()} default was least able to help with: policy tokens are letters,
     * digits, hyphens and spaces, every one of which either passes {@code html()} naked or round-trips
     * through the parser's character-reference decoding. There is no encoding that makes
     * {@code allow-same-origin} mean something else. Recorded as F20.
     *
     * <p><strong>R5 suppresses all four names</strong>, which is the only fix this category ever
     * admitted: none of them is on the plain-text allowlist, and
     * {@code Canoe.NAMES_THAT_MAY_NOT_BE_ADDED} refuses them from application configuration too. The
     * category is kept — with its criteria, which are what decided which names went on the allowlist
     * and which did not — because it is the question a new attribute has to be asked, and because a
     * suppressed sink still needs a declared kind for the oracle to judge it by.
     *
     * <h2>The exclusion criterion, stated so the boundary is checkable</h2>
     *
     * <p>This javadoc used to read "a security <em>or behavioural</em> directive", which is a wider
     * definition than F20's own, and the extra words were there only to accommodate {@code target},
     * {@code formtarget} and {@code type}. Two definitions that disagree are worse than either, so
     * the strict one wins and those three are {@link #PLAIN_TEXT_ATTR} cases with their reasoning
     * recorded. An attribute belongs here when <strong>all three</strong> hold:
     *
     * <ol>
     *   <li>the HTML parser or a browser algorithm consumes the decoded value as a directive rather
     *       than handing it to a second parser or fetching it;
     *   <li>the directive it controls is a <em>security</em> control, not a behavioural one; and
     *   <li>no encoding of the value can change what it means, because the meaning is the letters.
     * </ol>
     *
     * <p>Criterion 2 is what excludes {@code target}/{@code formtarget} (retargeting a navigation is
     * behaviour) and {@code type} on {@code <script>} (a content-type directive whose only
     * attacker-reachable effect is to <em>disable</em> the script, which fails safe — and which is
     * plain text on {@code <input>}, {@code <button>} and {@code <ol>} anyway). {@code rel} qualifies
     * on the strength of {@code rel=opener} alone.
     *
     * <p>Criterion 3 is what separates this category from {@code clobber.id}. That case makes F20's
     * argument verbatim — the legal values are exactly the dangerous ones, so only refusing to
     * interpolate helps — and is still {@link #PLAIN_TEXT_ATTR}/{@code SAFE}, because criterion 1
     * fails: an {@code id} is a name in the document's own namespace, and nothing in the browser
     * treats it as a directive. What it endangers is <em>other scripts on the page</em> that trust
     * the named-element namespace, not a control the browser is enforcing.
     */
    POLICY,

    /** A position with no downstream consumer, or one where Canoe emits nothing at all. */
    NONE
}
