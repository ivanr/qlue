package com.webkreator.qlue.view.canoe.corpus;

/**
 * <em>Which</em> non-executing sink an {@link Verdict#ACCEPTED_RESIDUAL} row's data reaches.
 *
 * <p>{@code ACCEPTED_RESIDUAL} says "the sink is not code execution", which on its own is a
 * negative and a negative is easy to write down without having looked. This enum is the positive
 * half: a case that claims the verdict has to name what the browser <em>does</em> do with the
 * value, and the corpus refuses the verdict without it. The four constants below were not chosen in
 * advance — they are what the 68 rows R26 re-verdicted actually turned out to be, read one at a
 * time against the template, the payload and the attribute the value lands in.
 *
 * <p>They are ordered by what the attacker gets, worst first. That ordering is the useful thing to
 * carry: if the residue is ever revisited — and {@link #FORM_RETARGET} is the one most likely to be
 * revisited — this is the list to work down.
 *
 * <p><strong>Not the same axis as {@code notBrowserObservable}.</strong> That flag is the browser
 * tier's expectation of a detector, set on rows the tier actually loads. This is a property of the
 * sink, set on every residual row whether a browser sees it or not. They have to agree where they
 * overlap, and that is asserted rather than hoped for: no {@link #INERT_SINK} case may be
 * browser-relevant, because the browser tier expects a detector to fire for every live row it loads
 * and {@code INERT_SINK} is the claim that no engine will —
 * {@code CanoeCorpusTest.everyResidualCitesAFindingAndNamesItsSink} catches the contradiction at
 * its source.
 */
public enum ResidualSink {

    /**
     * The value is a form's submission target. The browser leaves the origin <em>and</em> delivers
     * whatever the user typed into the form — including any CSRF token the template put in a hidden
     * field — to the attacker's origin.
     *
     * <p>Strictly worse than {@link #OPEN_REDIRECT} and still not code execution: nothing the
     * attacker's origin returns runs with the page's privileges. Two cases carry it,
     * {@code url.action} (<code>&lt;form action&gt;</code>) and {@code url.formaction}
     * (<code>&lt;button formaction&gt;</code>), and the second is the sharper one — {@code
     * formaction} overrides an {@code action} the template set from a constant, so care taken at the
     * form element does not help.
     *
     * <p>This is the residual class with the best case for being closed rather than accepted. R9's
     * argument for leaving {@code <a href>} alone — an off-origin link is an ordinary thing for a
     * page to contain — is much weaker here: an off-origin form action is not ordinary, and an
     * origin filter on {@code action}/{@code formaction} would cost far less availability than one
     * on {@code href}. R26 does not take that decision, because R9 took the scope decision and R26's
     * job is to record the residue rather than to reopen it; it is recorded here so that whoever
     * reopens it starts from the right end of the list.
     */
    FORM_RETARGET,

    /**
     * The value is a navigation target the user reaches by acting on the element — clicking a link.
     * The browser leaves the page's origin and lands wherever the attacker chose.
     *
     * <p>The classic open redirect: it lends the deploying site's reputation and its {@code
     * Referer} to a page the attacker controls, and it is the vector behind most credential-phishing
     * chains that begin on a trusted domain. It is not code execution — the destination document is
     * a document of the attacker's own origin, with none of this page's privileges, no access to its
     * DOM and no access to its cookies.
     *
     * <p>This is the class R9 explicitly declined to filter, and the reason is stated in R9 and not
     * softened here: an {@code <a href>} to another site is what hypertext is, so a component that
     * emptied every off-origin link would be switched off for the whole attribute, which is a worse
     * outcome than the redirect. Thirty-two of the 34 rows carrying it are {@code <a href>} or its
     * SVG twin {@code <a xlink:href>}, most of them syntax permutations of the same sink.
     *
     * <p>The other two are {@code img longdesc}, which R26 first put under {@link #INERT_SINK} and
     * review moved here. It is never <em>fetched</em>, but Gecko still dereferences it on user
     * action: {@code ImageAccessible} exposes a {@code showlongdesc} default action through the
     * platform accessibility API — the one NVDA and JAWS invoke to open the description — and the
     * image context menu still reads the attribute. "The user acts on the element and the browser
     * leaves the origin" is this class's definition, and it is met; how narrow the affordance is
     * belongs in the case note, not in the class. The correction is the reason
     * {@code INERT_SINK}'s wording is a claim about engines rather than about specifications: a
     * feature the standard calls obsolete can still have live code behind it.
     */
    OPEN_REDIRECT,

    /**
     * The browser fetches the URL as a subresource and gives the response no authority in the
     * document: it is decoded as an image, or the request's response is discarded entirely. What the
     * attacker gains is the <em>request</em> — the {@code Referer} header naming the page the user
     * is on, the client's IP address, the user agent, the timing, and for {@code <a ping>} a POST
     * that says which link was clicked.
     *
     * <p>Not code execution and not a navigation: the user stays where they are, and nothing the
     * attacker's origin returns is parsed as script, as HTML or as CSS. The corresponding
     * <em>executing</em> sinks — {@code <script src>}, {@code <iframe src>}, {@code <object data>},
     * {@code <embed src>}, {@code <link href>}, {@code <base href>} — are exactly the six
     * {@code Canoe.RESOURCE_LOADING_SINKS} R9 closed, which is what makes this boundary a line R9
     * drew rather than a line R26 invented.
     */
    REFERRER_LEAK,

    /**
     * The value reaches the attribute and <strong>no shipping engine dereferences it at all</strong>
     * — the attribute is legacy, obsolete, or specified to accept only a same-document reference
     * that an absolute URL cannot be.
     *
     * <p>Kept as a distinct class rather than folded into {@link #REFERRER_LEAK} because the two say
     * different things about what happens next. A referrer leak is a live effect that R9 chose not
     * to prevent; this is no effect, in any engine anyone can test against today. Recording it as a
     * residual at all — rather than as {@link Verdict#SAFE} — follows the rule the corpus has used
     * since §2.1: the ledger's subject is Canoe's output, and "a dead vector is still a Canoe defect
     * if Canoe emitted the payload live". These rows say the encoder let the authority through; they
     * do not say a browser acted on it.
     *
     * <p>The six cases and why each is inert: {@code img dynsrc} is an Internet Explorer attribute
     * no other engine ever implemented; {@code img lowsrc} is a Netscape extension that survives in
     * WebCore and Gecko as URL-serialisation bookkeeping only ({@code isURLAttribute},
     * {@code GetURIAttr}), with no fetch path; {@code applet codebase} needs an {@code <applet>}
     * element, which every engine removed with plugin support; {@code html manifest} needs
     * Application Cache, removed from Chrome, Firefox and Safari, and which required a same-origin
     * manifest even when it existed; {@code blockquote cite} is metadata the standard says a UA
     * <em>may</em> expose and none does, in UI or in the accessibility tree; and {@code img usemap}
     * is resolved by "parse a hash-name reference", never fetched, so a value that does not begin
     * {@code #} names no map and is ignored.
     *
     * <p>{@code img longdesc} was the seventh until review moved it to {@link #OPEN_REDIRECT}, and
     * the reason is worth keeping next to the others: it is not fetched either, but Gecko exposes a
     * {@code showlongdesc} accessibility action that opens the URL, so an engine <em>does</em>
     * dereference it. "Obsolete in the standard" and "dead in the engines" are different claims, and
     * only the second one belongs in this class.
     *
     * <p>Two of those are worth reading twice. {@code applet codebase} and {@code html manifest}
     * were code execution in their day — a codebase pointing at the attacker loaded the attacker's
     * classes, and a poisoned application cache was persistent same-origin XSS. They are inert
     * because a feature was removed, not because the value is harmless, so this class is the one to
     * re-examine if a corpus row is ever ported to a different rendering target.
     */
    INERT_SINK
}
