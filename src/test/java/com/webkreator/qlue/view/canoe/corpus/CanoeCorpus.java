package com.webkreator.qlue.view.canoe.corpus;

import com.webkreator.qlue.view.Canoe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The case catalogue: every template worth attacking, the payloads worth attacking it with, and the
 * reviewed verdict for each pairing.
 *
 * <p>One corpus feeds both tiers. The Velocity tier renders every case and asserts on the bytes; the
 * browser tier takes the same case objects, serves the rendered bytes, and asserts on effects.
 * Declaring the permutation space twice would guarantee the two drift apart.
 *
 * <p>Cases are grouped by the section of the plan's Appendix A they come from, and construction is
 * deferred rather than done in a static initialiser: a validation failure in case 1400 should be a
 * test failure naming that case, not an {@code ExceptionInInitializerError} that turns into a
 * {@code NoClassDefFoundError} in every unrelated test class.
 *
 * <h2>How a verdict got here</h2>
 *
 * <p>Every verdict below was set by rendering the case, reading the output and the jsoup-decoded sink
 * value, and then judging by hand what the consuming parser would do with it — never by copying
 * whatever {@code VerdictEvaluator} happened to say. That order matters: reversed, the ledger stops
 * being a ledger and becomes a transcript of the bugs. {@code
 * CanoeCorpusTest.ledgerMatchesObservedBehaviour} then asserts the two agree, so a hand judgement
 * that was wrong fails loudly rather than sitting here as unasserted prose.
 *
 * <p>Appendix A &sect;A.3 (the event-handler names) was deliberately thin here until T15, which
 * filled it in: all 21 recognised names, the three declared-but-dead ones, and the 91 unrecognised
 * ones, generated from two name lists rather than hand-written. {@code EventHandlerMatrixTest}'s
 * completeness guard reads the HTML Standard's event handler content attributes from
 * {@code src/test/resources/canoe/html-event-handler-attributes.txt} and fails if any of them has no
 * case here. &sect;A.8 (writer permutations) is covered by {@code CanoeWriterContractTest} and by
 * {@code ChunkInvarianceTest}, which applies the chunk-splitting property to every template below.
 *
 * <p>&sect;A.6 (Velocity reference forms and directives) is delivered by
 * {@code VelocityIntegrationTest}, which declares its own templates rather than adding cases here.
 * That is deliberate: a case in this catalogue is a <em>sink</em> carrying a reviewed verdict, and a
 * reference form is not a sink. {@code $data}, {@code $!data} and <code>${data}</code> inside
 * {@code <p>...</p>} are one sink and one verdict written three ways; what varies is whether the
 * encoder runs at all, which is a property of {@code CanoeReferenceInsertionHandler} and of
 * Velocity's rendering model. Putting them here would have produced thirty-five rows with the same
 * verdict and no way to tell which of them was asserting anything.
 */
public final class CanoeCorpus {

    private CanoeCorpus() {
    }

    private static final class Holder {
        static final List<XssCase> CASES = build();
        static final Map<String, XssCase> BY_ID = index(CASES);
    }

    /** Every case, in declaration order. */
    public static List<XssCase> all() {
        return Collections.unmodifiableList(Holder.CASES);
    }

    /** Every (case, payload) pair, which is the unit a parameterised test runs. */
    public static List<XssCase.Invocation> allInvocations() {
        List<XssCase.Invocation> result = new ArrayList<>();
        for (XssCase testCase : Holder.CASES) {
            result.addAll(testCase.invocations());
        }
        return result;
    }

    /**
     * The pairings that earn a browser run: every {@link Verdict#KNOWN_VULNERABLE} entry in a
     * browser-relevant case, plus one safe control per case so a green run means the detectors
     * stayed quiet rather than that nothing was loaded.
     */
    public static List<XssCase.Invocation> browserInvocations() {
        return allInvocations().stream()
                .filter(XssCase.Invocation::isBrowserRelevant)
                .collect(Collectors.toList());
    }

    /** Cases documenting a specific finding, e.g. {@code forFinding("F1")}. */
    public static List<XssCase> forFinding(String finding) {
        return Holder.CASES.stream()
                .filter(c -> finding.equals(c.finding()))
                .collect(Collectors.toList());
    }

    /** Cases from one Appendix A section, for splitting the suite by category. */
    public static List<XssCase> inSection(String section) {
        return Holder.CASES.stream()
                .filter(c -> section.equals(c.section()))
                .collect(Collectors.toList());
    }

    public static XssCase byId(String id) {
        XssCase result = Holder.BY_ID.get(id);
        if (result == null) {
            throw new IllegalArgumentException("No such case: " + id);
        }
        return result;
    }

    private static Map<String, XssCase> index(List<XssCase> cases) {
        Map<String, XssCase> result = new LinkedHashMap<>();
        for (XssCase testCase : cases) {
            if (result.put(testCase.id(), testCase) != null) {
                throw new IllegalStateException("Duplicate case id: " + testCase.id());
            }
        }
        return result;
    }

    private static List<XssCase> build() {
        List<XssCase> cases = new ArrayList<>();

        // A.1
        bodyContexts(cases);
        structuralPositions(cases);

        // A.3
        eventHandlers(cases);
        eventHandlerMatrix(cases);

        // A.2
        recognisedUriAttributes(cases);
        plainTextAttributes(cases);
        unrecognisedUrlAttributes(cases);
        markupAndRefreshSinks(cases);
        policyAttributes(cases);
        attributeNameSyntax(cases);

        // A.4
        cssContexts(cases);
        attributeValuePrefixes(cases);
        bufferResidue(cases);

        // A.7
        malformedTemplates(cases);

        return cases;
    }

    // ------------------------------------------------------------------
    // Section names, so a typo groups a case under a section nobody reads
    // ------------------------------------------------------------------

    private static final String A1 = "A.1 insertion contexts";
    private static final String A2 = "A.2 attribute names";
    private static final String A3 = "A.3 event handlers";
    private static final String A4 = "A.4 attribute value prefixes";
    private static final String A7 = "A.7 malformed template shapes";

    /**
     * The longest tag or attribute name Canoe will parse: the shared buffer's length less the slot
     * the name scan reserves for its NUL terminator. Read from {@link Canoe#MAX_TAGNAME_LEN} rather
     * than written out, because R20 moved it once (36 to 128) and the rows either side of the
     * boundary are about the <em>relationship</em> - "the limit is the buffer minus one", from both
     * sides - and not about either number.
     */
    private static final int NAME_LIMIT = Canoe.MAX_TAGNAME_LEN - 1;

    // ------------------------------------------------------------------
    // Shared payload selections and notes
    // ------------------------------------------------------------------

    /** Everything that tries to reach a URL sink: eight schemes, two relatives, three absolutes. */
    private static List<Payload> allUrlPayloads() {
        return Payloads.families("JS_URL", "PROTOCOL_RELATIVE", "ABSOLUTE_OFFSITE");
    }

    /**
     * A three-payload probe for the long tail of URL-bearing attribute names.
     *
     * <p>The full thirteen would be thirteen renders per name across fifteen names for no extra
     * information: every one of those names reaches the identical classification — the
     * {@code ATTR_HTML} fall-through before R5 and R6, and either {@code url()} or R5's fail-closed
     * default after them — so the per-payload distinctions are a property of the encoder and of the
     * URL parser, not of the name. They are pinned once, exhaustively, on the four headline sinks
     * below, and the tail carries one payload per mechanism — a script scheme, a protocol-relative
     * host, an absolute host.
     */
    private static List<Payload> urlProbe() {
        return Arrays.asList(Payloads.JS_URL, Payloads.PROTOCOL_RELATIVE,
                Payloads.ABSOLUTE_OFFSITE_HTTPS);
    }

    /** Two payloads that between them carry every markup delimiter, for the plain-text tail. */
    private static List<Payload> plainTextProbe() {
        return Arrays.asList(Payloads.TAG_IMG_ONERROR, Payloads.ATTR_DOUBLE_QUOTE_BREAKOUT);
    }

    /**
     * Why six {@code JS_URL} payloads were safe in an attribute Canoe did <em>not</em> protect. Every
     * one was an accident of {@code html()}, not a defence, and the reasons were written out in full
     * wherever they applied so that nobody read a {@code SAFE} row as evidence the sink was handled.
     *
     * <p>Retained as history after R5 and R6, and the group's own helper is gone: no sink in the
     * corpus reaches {@code html()} with a URL payload any more, so there is nothing left to mark
     * safe by accident. It is kept for the same reason {@link #CSS_BACKSLASH_IS_AN_ESCAPE} is - all
     * three mechanisms are properties of {@code html()} and of the URL parser rather than of the
     * routing, they are unchanged, and they are what would decide these payloads again if any name
     * were moved back onto the plain-text allowlist.
     */
    private static final String C0_CONTROL_ACCIDENT =
            "The six safe entries are neutralised by accident, not design, for three separate"
                    + " reasons. The tab, newline and leading-control splits and the NUL split are"
                    + " safe because html() renders C0 controls as the four literal characters \\xNN"
                    + " rather than as a character reference, and the resulting backslash is not a"
                    + " valid scheme character; if html() is ever changed to emit &#9; these flip to"
                    + " vulnerable. The entity-encoded prefix is safe because html() escapes the"
                    + " ampersand, so the parser decodes exactly once and the URL parser is handed"
                    + " the literal text &#106;avascript rather than a 'j' - which is the single"
                    + " clearest disproof of a second decode anywhere in the corpus. The"
                    + " percent-encoded prefix is safe because nothing percent-decodes a URL before"
                    + " scheme detection.";

    /**
     * Why the {@code PROTOCOL_RELATIVE/backslash} row inside a CSS {@code url()} was correctly
     * {@code KNOWN_VULNERABLE} and was still not an off-origin fetch. Written out because the
     * verdict invited exactly the wrong reading.
     *
     * <p>Retained as history after R2 re-verdicted that row to {@code SUPPRESSED_BY_DESIGN}: what it
     * describes is a CSS-tokenizer behaviour rather than a Canoe one, it is unchanged, and it is the
     * thing that would decide the row's impact again if anything ever re-enabled output there.
     */
    private static final String CSS_BACKSLASH_IS_AN_ESCAPE =
            "One row needed its own reading, and the reading survives R2 as history. Before R2,"
                    + " PROTOCOL_RELATIVE/backslash - /\\attacker.invalid/x.js - was"
                    + " KNOWN_VULNERABLE for the right reason, the attacker's bytes reaching the CSS"
                    + " parser untouched, which is F4 - but it did NOT fetch from the sentinel host."
                    + " CSS reads a backslash as the start of an escape, so \\a is U+000A and the"
                    + " url() token resolved to a path that is not the attacker's host. The URL"
                    + " oracle's backslash-is-a-path-separator rule is an HTML/URL rule, not a CSS"
                    + " one, and this is the sink where the two disagree. The row is a suppression"
                    + " now and issues no request at all, so nothing here bears on its verdict; it is"
                    + " recorded because it is a CSS-tokenizer fact rather than a Canoe one, and it"
                    + " is what would bound the impact again if output here were ever re-enabled.";

    /**
     * The three CSS cases carry {@code CSS_EXPRESSION}, which no engine has run since Internet
     * Explorer 11 was retired. It stayed in the ledger — Canoe emitted it live, and &sect;8 is
     * explicit that a dead vector is still a Canoe defect — and it was flagged so the browser tier
     * expected the detector to stay quiet rather than reporting a ledger divergence.
     *
     * <p>Retained as history after R2. Those rows are suppressions now, so the browser tier already
     * expects silence and the flag would record nothing; the reasoning is kept because it is why the
     * payload is in the catalogue at all.
     */
    private static final String EXPRESSION_IS_DEAD =
            "CSS_EXPRESSION used to be flagged not-browser-observable here: expression() was an"
                    + " Internet Explorer extension and no engine the browser tier drives will"
                    + " evaluate it, so while the ledger entry was KNOWN_VULNERABLE - the ledger's"
                    + " subject being what Canoe emitted - the flag was how the browser tier was told"
                    + " to expect a miss instead of failing on the divergence. R2 made the row a"
                    + " suppression, so the browser tier already expects silence and the flag has"
                    + " been removed; the reasoning is kept because it is why the payload is in the"
                    + " catalogue at all.";

    /**
     * The blanket version of {@link #EXPRESSION_IS_DEAD} for the URL sinks, where the same problem
     * arrives payload by payload rather than family by family.
     */
    private static final String DEAD_URL_VECTORS =
            "Some payloads here are flagged not-browser-observable, which is a statement about 2026"
                    + " browsers and not about the verdict: vbscript: has no engine left to run it"
                    + " and a data:text/html document is blocked from top-level navigation and from"
                    + " subresource execution in every shipping browser. They stay KNOWN_VULNERABLE"
                    + " because Canoe emitted the attacker's URL live, which is the ledger's subject;"
                    + " the flag exists so the browser tier expects a detector miss rather than"
                    + " reporting a divergence against a ledger that never claimed otherwise.";

    /**
     * Why every {@code QUOTE_BREAKOUT/double-quote} row against a single-quoted template literal is
     * flagged, added by T28 after a real browser measured all of them at once.
     *
     * <p>This is the one place the two tiers' oracles were known to disagree and nobody had listed
     * the rows. Plan item 6 records that {@code VerdictEvaluator} is deliberately not quote-aware
     * and "over-reports rather than under-reports"; T28 loaded the consequences and found seven
     * {@code KNOWN_VULNERABLE} rows where the browser does exactly nothing. The verdict is not
     * wrong under &sect;2.1 — a raw {@code "} does reach the JavaScript parser, which is more than
     * the {@code ENTITY_BREAKOUT} control manages — but no engine will ever act on it, so the flag
     * rather than a re-litigated verdict is the right record.
     *
     * <p>Retained as history after R4 suppressed every row it qualified. The flag is gone with the
     * verdicts — the corpus only permits it on {@link Verdict#KNOWN_VULNERABLE} rows, and a
     * suppressed row already expects browser silence — but what it records is a fact about the
     * JavaScript parser rather than about Canoe, so it is still true and it is what would bound the
     * impact again if output into a handler were ever re-enabled.
     */
    private static final String A_DOUBLE_QUOTE_CANNOT_CLOSE_A_SINGLE_QUOTED_LITERAL =
            "QUOTE_BREAKOUT/double-quote used to be flagged not-browser-observable here. Every"
                    + " template in this group puts the reference inside a SINGLE-quoted JavaScript"
                    + " string literal, and a double quote cannot close one: the payload arrived"
                    + " live, stayed one string argument, and no engine parsed a single new token of"
                    + " it as code. The single-quote sibling of the same row was the one that ran."
                    + " The verdict stayed KNOWN_VULNERABLE because the raw quote did reach the"
                    + " JavaScript parser and because VerdictEvaluator is deliberately not"
                    + " quote-aware (plan item 6); BrowserCorpusTest is what turned that documented"
                    + " over-report into a list of named rows instead of a sentence. Neither quote"
                    + " payload is emitted at all now, so the distinction records nothing and the"
                    + " flag has gone with the verdict it qualified.";

    /**
     * The bound T28 put on F4, in the same shape T16 put one on F6.
     *
     * <p>T17 measured what decides whether Canoe <em>encodes</em> a CSS reference: the index of the
     * first colon. A browser then measured what decides whether anything <em>happens</em>, and it
     * is a different question with a different answer — the CSS container the reference sits in.
     * Three {@code style} attributes, all past the colon test, all html-encoded, all
     * {@code KNOWN_VULNERABLE}: {@code background:$x} fetches from the attacker's origin,
     * {@code content:'$x'} produces one inert string, and {@code background:url($x)} produces a
     * bad-url token and drops the declaration. F4 was real and narrower than it read, and neither
     * half of that sentence can be reached from Canoe's output alone.
     *
     * <p>Retained as history after R2 closed F4 and all three rows became suppressions. It is worth
     * keeping for the same reason as {@link #CSS_BACKSLASH_IS_AN_ESCAPE}: it is a statement about
     * what a browser does with a CSS value, not about what Canoe emits, so it is still true and it
     * is what would bound the impact again if the suppression were ever relaxed.
     */
    private static final String THE_CSS_CONTAINER_DECIDES =
            "F4's blast radius was bounded by the CSS container the reference sits in, not only by"
                    + " the colon index T17 measures: whether the value becomes a DECLARATION"
                    + " decides whether a browser acts on it. css.style-background was the shape"
                    + " that did; this one did not, and both were correctly KNOWN_VULNERABLE anyway,"
                    + " because the ledger's subject is what Canoe emitted. Both are suppressions"
                    + " after R2, so neither reaches a CSS container at all; the bound is kept as the"
                    + " statement of what the impact would be if it did.";

    /**
     * Why {@code view-source:} is flagged wherever it is navigated to rather than fetched.
     */
    private static final String VIEW_SOURCE_IS_BLOCKED_FROM_CONTENT =
            "JS_URL/view-source is flagged not-browser-observable. Every current engine refuses to"
                    + " navigate web content to view-source: - Chromium answers \"Not allowed to"
                    + " load local resource\" and never issues the request - so the nested"
                    + " https://attacker.invalid/x is never reached. It stays KNOWN_VULNERABLE"
                    + " because Canoe emitted it live, which is exactly why the payload exists: it"
                    + " is the scheme a denylist of eight names forgets. Measured in Chromium by"
                    + " BrowserCorpusTest.";

    /**
     * The {@code JS_URL} payloads whose scheme {@code url()} rejects outright since R12: a clean
     * leading {@code javascript:}, {@code data:}, {@code vbscript:} or {@code view-source:} that is
     * not on {@code HtmlEncoder}'s {http, https, mailto} allowlist.
     *
     * <p>They used to render {@link Verdict#SAFE} — the old {@code url()} escaped the colon to
     * {@code %3A}, leaving a relative path — and they are {@link Verdict#SUPPRESSED_BY_DESIGN} now,
     * because R12 emits nothing for a rejected scheme. That is strictly stronger: a suppressed value
     * cannot be a relative path with the attacker's fragment on it either. The rest of the
     * {@code JS_URL} family is not here — {@code tab-split}, {@code entity-decimal},
     * {@code percent-encoded} and the leading-junk shapes carry no clean scheme, so {@code url()}
     * reads them as relative references and they stay SAFE.
     */
    private static final List<Payload> URL_SCHEME_REJECTED_PAYLOADS = Arrays.asList(
            Payloads.JS_URL, Payloads.JS_URL_MIXED_CASE, Payloads.DATA_URL_HTML,
            Payloads.VBSCRIPT_URL, Payloads.VIEW_SOURCE_URL);

    /**
     * Applies R11+R12's two verdict deltas to a URL case, for whichever of the affected payloads it
     * actually uses. A rejected scheme suppresses; an uppercase absolute off-origin URL, which the old
     * case-sensitive regex used to neutralise by accident, is now normalised and passes through, so it
     * is {@link Verdict#KNOWN_VULNERABLE} under F6 exactly like its lowercase sibling.
     *
     * <p>{@code reachesAuthority} is false for the query and fragment positions, where the template's
     * own literal text keeps every payload on the page's origin: there the uppercase URL stays SAFE
     * and only the rejected schemes move.
     */
    private static void applyUrlSchemeReverdict(XssCase.Builder builder, List<Payload> payloads,
                                                boolean reachesAuthority) {
        for (Payload payload : URL_SCHEME_REJECTED_PAYLOADS) {
            if (payloads.contains(payload)) {
                builder.override(payload, Verdict.SUPPRESSED_BY_DESIGN);
            }
        }
        if (reachesAuthority && payloads.contains(Payloads.ABSOLUTE_OFFSITE_UPPERCASE)) {
            builder.override(Payloads.ABSOLUTE_OFFSITE_UPPERCASE, Verdict.KNOWN_VULNERABLE);
        }
    }

    /**
     * R9's re-verdict for a resource-loading sink. On {@code <script src>}, {@code <iframe src>},
     * {@code <object data>}, {@code <embed src>}, {@code <link href>} and {@code <base href>}, Canoe
     * now routes the value to {@link com.webkreator.qlue.util.HtmlEncoder#urlResource} rather than to
     * {@code url()}, and {@code urlResource} rejects an off-origin or protocol-relative authority to
     * the empty string. So the payloads that {@code url()} passed through byte for byte — a
     * protocol-relative {@code //host}, an absolute {@code https://host}, its uppercase-scheme sibling
     * and (on {@code <base>}) the {@code BASE_HIJACK} host — move from {@link Verdict#KNOWN_VULNERABLE}
     * citing F6 to {@link Verdict#SUPPRESSED_BY_DESIGN}: rendered against the sink, the src or href
     * falls back to whatever literal the template wrote and the attacker's authority never reaches the
     * browser.
     *
     * <p>Given the exact off-origin payloads the case uses, because the corpus refuses an override for
     * a payload the case does not carry. The backslash and userinfo shapes are deliberately absent:
     * {@code url()} already neutralised those to a same-origin path or an unparseable host, so
     * {@code urlResource} passes them through unchanged and their verdict does not move.
     */
    private static XssCase.Builder resourceSinkRejectsOffOrigin(XssCase.Builder builder,
                                                                Payload... offOrigin) {
        for (Payload payload : offOrigin) {
            builder.override(payload, Verdict.SUPPRESSED_BY_DESIGN);
        }
        return builder;
    }

    /**
     * The shape shared by every URL-bearing name R6 added to {@code ATTR_URI}.
     *
     * <p>It used to be {@link Verdict#KNOWN_VULNERABLE} against F3 for every payload: the name fell
     * through to {@code ATTR_HTML}, and the HTML parser decodes {@code html()}'s references before
     * the URL parser runs, so the attacker recovered every character of the URL. R6 routes these
     * names to {@code url()} and the shape becomes {@link #recognisedUriAttribute}'s, byte for byte
     * and verdict for verdict — which is the honest statement of what R6 bought and what it did not.
     *
     * <p><strong>The default is SAFE and the off-origin payloads are KNOWN_VULNERABLE citing
     * F6</strong>, not F3. Every script-bearing scheme is genuinely neutralised: since R12
     * {@code url()} rejects a scheme off its {http, https, mailto} allowlist to the empty string, so
     * those rows are {@link Verdict#SUPPRESSED_BY_DESIGN} — see {@link #applyUrlSchemeReverdict}. A
     * protocol-relative or absolute {@code http(s)} URL is not neutralised at all: it is a valid URL
     * and {@code url()} emits it byte for byte, uppercase scheme included. A URL attribute routed to
     * {@code url()} inherits {@code url()}'s defects, and the ledger records that rather than reading
     * the routing fix as a fix for the sink.
     *
     * <p>Whether R9 closes such a row depends on the element. On a resource-loading combination —
     * {@code <object data>} is the one built by this helper — R9 routes to {@code urlResource()} and
     * the off-origin rows become {@link Verdict#SUPPRESSED_BY_DESIGN}; the caller applies
     * {@link #resourceSinkRejectsOffOrigin} to say so. On an open-redirect or referrer surface —
     * {@code <form action>}, {@code <button formaction>}, {@code <video poster>}, {@code <a ping>} and
     * the rest — R9 deliberately leaves the row {@code KNOWN_VULNERABLE} under F6, because an
     * off-origin navigation or fetch is not code execution; that is the residual F6 R26 tracks.
     */
    private static XssCase.Builder urlAttributeAddedByR6(String id, String template,
                                                         String selector, String attribute,
                                                         List<Payload> payloads) {
        XssCase.Builder builder = XssCase.id(id)
                .section(A2)
                .template(template)
                .sink(SinkKind.URL, selector, attribute)
                .payloads(payloads)
                .verdict(Verdict.SAFE)
                .finding("F6")
                .override(Payloads.PROTOCOL_RELATIVE, Verdict.KNOWN_VULNERABLE)
                .override(Payloads.ABSOLUTE_OFFSITE_HTTPS, Verdict.KNOWN_VULNERABLE);
        applyUrlSchemeReverdict(builder, payloads, true);
        return builder;
    }

    /**
     * The tail form of {@link #urlAttributeAddedByR6}: three payloads, one per mechanism. The
     * per-payload distinctions are pinned exhaustively on the four headline sinks, which carry all
     * thirteen.
     */
    private static XssCase.Builder urlAttributeAddedByR6(String id, String template,
                                                         String selector, String attribute) {
        return urlAttributeAddedByR6(id, template, selector, attribute, urlProbe());
    }

    /**
     * The shape shared by the URL-bearing names R6 deliberately left off the URL list, which R5's
     * fail-closed default therefore suppresses.
     *
     * <p>These were F3 rows too, and the reasoning for treating them differently from the names
     * above is worth having in one place. {@code url()} is a scheme filter and not an origin filter,
     * so routing a name to it closes the script-scheme half of F3 and opens F6 on that name.
     * Suppression closes both and costs availability instead: nothing renders. R6's list is the set
     * of names an ordinary template interpolates into, where losing the value is not acceptable;
     * everything else — {@code imagesrcset}, {@code xml:base}, {@code archive}, {@code classid},
     * {@code profile} — takes the stronger answer, and an application that genuinely needs one has
     * {@code $_x.asis()} and the knowledge that it is choosing to.
     */
    private static XssCase.Builder urlAttributeSuppressedByR5(String id, String template,
                                                              String selector, String attribute) {
        return XssCase.id(id)
                .section(A2)
                .template(template)
                .sink(SinkKind.URL, selector, attribute)
                .payloads(urlProbe())
                .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                .finding("F3");
    }

    /**
     * The shape shared by the five names {@code setTagAttributeContext()} has always mapped to
     * {@code ATTR_URI}. R6's twelve additions take {@link #urlAttributeAddedByR6}, which is the same
     * shape with a different history attached; the two are kept apart so that a reader can still see
     * which rows were F3 and which never were.
     */
    private static XssCase.Builder recognisedUriAttribute(String id, String template,
                                                          String selector, String attribute) {
        XssCase.Builder builder = XssCase.id(id)
                .section(A2)
                .template(template)
                .sink(SinkKind.URL, selector, attribute)
                .payloads(allUrlPayloads())
                .verdict(Verdict.SAFE)
                .finding("F6")
                .override(Payloads.PROTOCOL_RELATIVE, Verdict.KNOWN_VULNERABLE)
                .override(Payloads.ABSOLUTE_OFFSITE_HTTPS, Verdict.KNOWN_VULNERABLE);
        applyUrlSchemeReverdict(builder, allUrlPayloads(), true);
        return builder;
    }

    /** The shape shared by the names where {@code ATTR_HTML} is genuinely the right answer. */
    private static XssCase.Builder plainTextAttribute(String id, String template,
                                                      String selector, String attribute,
                                                      List<Payload> payloads) {
        return XssCase.id(id)
                .section(A2)
                .template(template)
                .sink(SinkKind.PLAIN_TEXT_ATTR, selector, attribute)
                .payloads(payloads)
                .verdict(Verdict.SAFE);
    }

    /**
     * The shape shared by every policy-bearing attribute (F20).
     *
     * <p>The default is {@link Verdict#SUPPRESSED_BY_DESIGN} since R5, and every row in the group
     * takes it: the names are off the plain-text allowlist, so nothing is emitted.
     *
     * <p>It was {@link Verdict#SAFE} with each case naming the payloads that were actually
     * <em>live</em> in its attribute, and that per-payload precision is worth keeping in the notes
     * even though the verdicts no longer differ. It was the opposite of how the group was first
     * written: the cross-product handed all three policy tokens to all six attributes and recorded
     * {@code KNOWN_VULNERABLE} for every one, which is a claim &sect;2.1 does not support.
     * {@code sandbox="opener"} is an unknown sandbox token, so the sandbox stays maximally
     * restrictive; {@code rel="_blank"} is not a link type. The bytes arrived, but nothing acted on
     * them, and "attacker data reaches the sink <em>live</em>" is the definition. The oracle could not
     * see the difference — it asks only whether the bytes survived — so that was a wrong verdict no
     * test could have caught, which is exactly the class the ledger exists to make visible.
     */
    private static XssCase.Builder policyAttribute(String id, String template,
                                                   String selector, String attribute) {
        return XssCase.id(id)
                .section(A2)
                .template(template)
                .sink(SinkKind.POLICY, selector, attribute)
                .payloads(Payloads.family("POLICY_OVERRIDE"))
                .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                .finding("F20");
    }

    /**
     * Why every row in the policy group is a suppression since R5, and why that is the only fix that
     * was ever available for it.
     *
     * <p>Written once and attached to each row, because the argument is the same one four times and
     * it is the argument a reader has to accept before the group makes sense.
     */
    private static final String A_DIRECTIVE_CANNOT_BE_ENCODED =
            "Re-verdicted by R5. A policy token is letters, digits, hyphens, underscores and spaces;"
                    + " html() passes the letters and digits naked and turns the rest into character"
                    + " references the HTML parser puts straight back, so the value arrived byte for"
                    + " byte and no change to the encoder could ever have altered that. Encoding was"
                    + " not insufficient here, it was inapplicable - the browser consumes the decoded"
                    + " value as a DIRECTIVE rather than handing it to a parser - so recognising the"
                    + " name and refusing to interpolate was not the preferred fix but the only one."
                    + " R5's plain-text allowlist is what implements it: the name is not on the list,"
                    + " so the fail-closed default drops the value. Reviewed against the sink: the"
                    + " attribute renders empty for every payload, byte-identical to a render with an"
                    + " empty value. The finding stays cited so the row remains traceable to F20, and"
                    + " Canoe.NAMES_THAT_MAY_NOT_BE_ADDED refuses the same name from application"
                    + " configuration, so the allowlist cannot be widened back onto it.";

    /**
     * The shape shared by every template Canoe refuses outright. Two payloads, not one: the inert
     * marker shows the rejection is a property of the template, and a hostile payload shows the
     * attacker neither caused it nor can avoid it. {@code
     * CanoeCorpusTest.payloadsCannotAddMarkupDelimitersToOutput} asserts that pairing directly.
     */
    private static XssCase.Builder rejected(String id, String template) {
        return XssCase.id(id)
                .section(A7)
                .template(template)
                .noSink()
                .payloads(Payloads.INERT_MARKER, Payloads.TAG_IMG_ONERROR)
                .verdict(Verdict.REJECTED);
    }

    // ------------------------------------------------------------------
    // A.1 Insertion contexts
    // ------------------------------------------------------------------

    private static void bodyContexts(List<XssCase> cases) {

        // The review's "what is not affected" claim: htmlWhite() is an allowlist, and the decisive
        // property is that '<' can never appear in the output, so the attacker is confined to text.
        cases.add(XssCase.id("body.paragraph")
                .section(A1)
                .template("<p>$data</p>")
                .textSink("p")
                .payloads(Payloads.families("TAG_BREAKOUT", "QUOTE_BREAKOUT", "ENTITY_BREAKOUT",
                        "ATTR_BREAKOUT", "UNICODE_EDGE", "CONTROL_CHARS", "LENGTH_STRESS"))
                .verdict(Verdict.SAFE)
                .note("Body insertion is the common pattern and is unaffected by every finding."
                        + " CONTROL_CHARS is here rather than in a rejection case because Canoe"
                        + " rejects control characters in template literal text, not in encoded"
                        + " payload output: htmlWhite() turns them into the four literal characters"
                        + " \\xNN before they reach the state machine, so they can never trip the"
                        + " 'Invalid character detected in output' check.")
                .browserRelevant()
                .build());

        // Depth changes nothing: the context is a property of the parser state, not of the tree.
        cases.add(XssCase.id("body.nested-elements")
                .section(A1)
                .template("<div><section><p>$data</p></section></div>")
                .textSink("p")
                .payloads(Payloads.family("TAG_BREAKOUT"))
                .verdict(Verdict.SAFE)
                .note("Included because a reader can reasonably wonder whether the state machine"
                        + " tracks nesting at all. It does not: there is no element stack.")
                .build());

        // Between two elements rather than inside one - still HTML state.
        cases.add(XssCase.id("body.between-elements")
                .section(A1)
                .template("<p>a</p>$data<p>b</p>")
                .textSink("body")
                .payloads(Payloads.family("TAG_BREAKOUT"))
                .verdict(Verdict.SAFE)
                .build());

        // Two references with nothing between them, so an encoder that leaked state between calls
        // would show up here.
        cases.add(XssCase.id("body.adjacent-references")
                .section(A1)
                .template("<p>$data$second</p>")
                .model("second", "SECOND")
                .textSink("p")
                .payloads(Payloads.family("TAG_BREAKOUT"))
                .verdict(Verdict.SAFE)
                .build());

        // One reference on each side of a tag boundary. body.adjacent-references puts two references
        // next to each other with no markup between; this one puts markup between them, so the
        // machine leaves HTML for TAG_NAME/TAG and comes back twice between the two insertions.
        cases.add(XssCase.id("body.either-side-of-a-tag-boundary")
                .section(A1)
                .template("<p>$data</p><div>$second</div>")
                .model("second", "SECOND")
                .textSink("p")
                .payloads(Payloads.family("TAG_BREAKOUT"))
                .verdict(Verdict.SAFE)
                .note("BodyContextTest binds the payload to BOTH references and asserts both are"
                        + " encoded for body context, which is the assertion this case shape exists"
                        + " for; the corpus binds only one so that the sink under test stays"
                        + " unambiguous.")
                .build());

        // A reference immediately before and immediately after a script element: the state machine
        // enters and leaves SCRIPT around them, and neither is affected.
        cases.add(XssCase.id("body.before-script-block")
                .section(A1)
                .template("<p>$data</p><script>var a=1;</script>")
                .textSink("p")
                .payloads(Payloads.family("TAG_BREAKOUT"))
                .verdict(Verdict.SAFE)
                .build());

        cases.add(XssCase.id("body.after-script-block")
                .section(A1)
                .template("<script>var a=1;</script><p>$data</p>")
                .textSink("p")
                .payloads(Payloads.family("TAG_BREAKOUT"))
                .verdict(Verdict.SAFE)
                .note("Proves SCRIPT_END returned the machine to HTML. If it had not, this would be"
                        + " suppressed - which is what the F10 converse case did until R17 made the"
                        + " mismatching character be re-processed rather than dropped.")
                .build());

        // Inside a table cell, and in the foster-parenting position before the first row. Neither is
        // special to Canoe; both are here because they are special to the HTML parser, and the
        // browser tier compares the two.
        cases.add(XssCase.id("body.table-cell")
                .section(A1)
                .template("<table><tr><td>$data</td></tr></table>")
                .textSink("td")
                .payloads(Payloads.family("TAG_BREAKOUT"))
                .verdict(Verdict.SAFE)
                .build());

        cases.add(XssCase.id("body.table-foster-parented")
                .section(A1)
                .template("<table>$data<tr><td>x</td></tr></table>")
                .textSink("body")
                .payloads(Payloads.family("TAG_BREAKOUT"))
                .verdict(Verdict.SAFE)
                .note("The browser moves this text out of the table and in front of it. Canoe never"
                        + " sees the difference; the encoding is the same either way.")
                .browserRelevant()
                .build());

        // Foreign content. The HTML parser's tokenizer rules differ inside SVG, which is why this is
        // worth a browser run even though Canoe treats it as ordinary HTML.
        cases.add(XssCase.id("body.svg-subtree")
                .section(A1)
                .template("<svg><text>$data</text></svg>")
                .textSink("text")
                .payloads(Payloads.family("TAG_BREAKOUT"))
                .verdict(Verdict.SAFE)
                .browserRelevant()
                .build());

        cases.add(XssCase.id("body.select-option")
                .section(A1)
                .template("<select><option>$data</option></select>")
                .textSink("option")
                .payloads(Payloads.family("TAG_BREAKOUT"))
                .verdict(Verdict.SAFE)
                .build());

        // RCDATA. Canoe does not model textarea or title at all - they resolve to CTX_HTML - and the
        // review argues that is still safe: in RCDATA a decoded &lt; is character data and never
        // becomes a tag opener. These two cases are that argument, executable.
        cases.add(XssCase.id("rcdata.textarea")
                .section(A1)
                .template("<textarea>$data</textarea>")
                .textSink("textarea")
                .payloads(Payloads.families("TAG_BREAKOUT", "ENTITY_BREAKOUT"))
                .verdict(Verdict.SAFE)
                .note("Canoe has no RCDATA state; this is CTX_HTML. Safe anyway, because the only"
                        + " escape from RCDATA is a literal </textarea and htmlWhite() cannot emit"
                        + " the '<'.")
                .browserRelevant()
                .build());

        cases.add(XssCase.id("rcdata.title")
                .section(A1)
                .template("<title>$data</title>")
                .textSink("title")
                .payloads(Payloads.family("TAG_BREAKOUT"))
                .verdict(Verdict.SAFE)
                .build());

        // RAWTEXT. Stronger than RCDATA: the browser does not decode character references here at
        // all, so the entity-encoded payload is displayed as literal text.
        cases.add(XssCase.id("rawtext.xmp")
                .section(A1)
                .template("<xmp>$data</xmp>")
                .textSink("xmp")
                .payloads(Payloads.family("TAG_BREAKOUT"))
                .verdict(Verdict.SAFE)
                .note("In RAWTEXT the entities are not decoded, so the user sees &lt;img ...&gt;"
                        + " spelled out. Ugly, inert.")
                .browserRelevant()
                .build());

        cases.add(XssCase.id("rawtext.noembed")
                .section(A1)
                .template("<noembed>$data</noembed>")
                .textSink("noembed")
                .payloads(Payloads.family("TAG_BREAKOUT"))
                .verdict(Verdict.SAFE)
                .build());

        cases.add(XssCase.id("rawtext.noscript")
                .section(A1)
                .template("<noscript>$data</noscript>")
                .textSink("noscript")
                .payloads(Payloads.family("TAG_BREAKOUT"))
                .verdict(Verdict.SAFE)
                .note("The one element whose parsing depends on a browser setting: with scripting"
                        + " disabled the content is parsed as ordinary markup rather than RAWTEXT."
                        + " Safe either way, because the payload carries no raw '<'.")
                .browserRelevant()
                .build());

        cases.add(XssCase.id("rawtext.iframe-legacy-content")
                .section(A1)
                .template("<iframe>$data</iframe>")
                .textSink("iframe")
                .payloads(Payloads.family("TAG_BREAKOUT"))
                .verdict(Verdict.SAFE)
                .build());

        // Comments. currentContext() has no case for any COMMENT_* state, so everything inside one
        // is suppressed. Fail-closed, but the value vanishes with no diagnostic, which is the
        // silent-suppression class F11 belongs to.
        cases.add(XssCase.id("comment.body")
                .section(A1)
                .template("<!-- $data -->")
                .noSink()
                .payloads(Payloads.family("TAG_BREAKOUT"))
                .verdict(Verdict.SUPPRESSED_UNINTENDED)
                .note("F11 lists the COMMENT_* states among those currentContext() has no case for."
                        + " A generator stamp or a debug marker built from a reference renders empty."
                        + " R19 closed F11's attribute-value half and deliberately stopped there:"
                        + " TAG_ATTR_VALUE_BEFORE has a name-derived answer waiting for it, and a"
                        + " comment has no encoder at all until somebody models '-->' and the"
                        + " nested-comment rules, so these states keep their hole.")
                .build());

        cases.add(XssCase.id("comment.conditional")
                .section(A1)
                .template("<!--[if IE]>$data<![endif]-->")
                .noSink()
                .payloads(Payloads.family("TAG_BREAKOUT"))
                .verdict(Verdict.SUPPRESSED_UNINTENDED)
                .note("A downlevel-hidden conditional comment is an ordinary comment to both Canoe"
                        + " and every browser still shipping.")
                .build());

        // F14, closed by R16. COMMENT_CLOSE_2 used to drop back to COMMENT on a third '-', so the
        // comment never closed and every reference for the rest of the page was suppressed. R16 keeps
        // COMMENT_CLOSE_2 on a third (or later) dash, matching the HTML Standard's comment-end state,
        // so the '>' closes the comment and the <p> that follows is real markup again.
        cases.add(XssCase.id("comment.three-dashes-swallows-the-page")
                .section(A1)
                .template("<!--a---><p>$data</p>")
                .textSink("p")
                .payloads(Payloads.family("TAG_BREAKOUT"))
                .verdict(Verdict.SAFE)
                .finding("F14")
                .note("Every browser closes this comment at the '>', and after R16 so does Canoe: the"
                        + " third dash stays in comment-end and the following '>' returns the parser to"
                        + " HTML. The reference in the <p> is no longer swallowed - it renders in the"
                        + " text context, where TAG_BREAKOUT is HTML-escaped and cannot change the"
                        + " document's shape. Was SUPPRESSED_UNINTENDED (the value, and every value"
                        + " after it, rendered empty); re-verdicted SAFE against the fixed output.")
                .build());

        cases.add(XssCase.id("doctype.internal-subset")
                .section(A1)
                .template("<!DOCTYPE $data>")
                .noSink()
                .payloads(Payloads.family("TAG_BREAKOUT"))
                .verdict(Verdict.SUPPRESSED_UNINTENDED)
                .note("DOCTYPE is another state currentContext() has no case for.")
                .build());
    }

    /**
     * The positions inside markup a reference can occupy that are not "text" or "a quoted attribute
     * value" — and the two that are, under each quoting style.
     */
    private static void structuralPositions(List<XssCase> cases) {

        // Tag-name position. The reference is evaluated in TAG_NAME, which currentContext() maps to
        // CTX_SUPPRESS, so it renders empty - and then '<' immediately followed by '>' is a tag with
        // no name, which Canoe rejects. Suppression and rejection compound.
        cases.add(rejected("position.tag-name", "<$data>x")
                .section(A1)
                .note("Suppression makes the tag name empty, and an empty tag name is fatal:"
                        + " 'Tag name too short'. A dynamic element name is not expressible.")
                .build());

        cases.add(rejected("position.closing-tag-name", "<p>x</$data>")
                .section(A1)
                .note("Same mechanism as position.tag-name, one character further along.")
                .build());

        // Attribute-name position. TAG state is CTX_SUPPRESS, so the name renders empty and the '='
        // that follows has no name in front of it.
        cases.add(rejected("position.attribute-name", "<p $data=\"x\">y</p>")
                .section(A1)
                .note("'Invalid character in attribute name' - the '=' arrives where an attribute"
                        + " name was expected, because the name was suppressed to nothing.")
                .build());

        // The three quoting styles, all resolving to ATTR_HTML on a plain-text name. html() escapes
        // space and '>' as well as the quotes, so even the unquoted form cannot be terminated.
        cases.add(XssCase.id("quoting.double-quoted")
                .section(A1)
                .template("<span title=\"$data\">x</span>")
                .sink(SinkKind.PLAIN_TEXT_ATTR, "span", "title")
                .payloads(Payloads.family("ATTR_BREAKOUT"))
                .verdict(Verdict.SAFE)
                .build());

        cases.add(XssCase.id("quoting.single-quoted")
                .section(A1)
                .template("<span title='$data'>x</span>")
                .sink(SinkKind.PLAIN_TEXT_ATTR, "span", "title")
                .payloads(Payloads.family("ATTR_BREAKOUT"))
                .verdict(Verdict.SAFE)
                .build());

        cases.add(XssCase.id("quoting.unquoted-after-literal-text")
                .section(A1)
                .template("<span title=x$data>y</span>")
                .sink(SinkKind.PLAIN_TEXT_ATTR, "span", "title")
                .payloads(Payloads.family("ATTR_BREAKOUT"))
                .verdict(Verdict.SAFE)
                .note("The literal 'x' is what makes this row a different shape from"
                        + " unquoted.plain-text-after-equals: it advances the machine to"
                        + " TAG_ATTR_VALUE with QUOTE_NONE before the reference is inserted, which is"
                        + " why it rendered correctly all through F11's lifetime. html() escapes"
                        + " space and '>', so an unquoted value still cannot be terminated.")
                .browserRelevant()
                .build());

        // R19 gave TAG_ATTR_VALUE_BEFORE the attribute's name-derived context, so a reference sitting
        // directly after the '=' is encoded rather than dropped (F11). The three rows below hold that
        // position: a plain-text name, a URL name, and the same URL name with whitespace between the
        // '=' and the reference. The classifications that suppress are unchanged by R19 and are
        // covered where they already were, by AttributeNameMatrixTest and UnquotedAttributeValueTest.
        cases.add(XssCase.id("unquoted.plain-text-after-equals")
                .section(A1)
                .template("<span title=$data>x</span>")
                .sink(SinkKind.PLAIN_TEXT_ATTR, "span", "title")
                .payloads(Payloads.family("ATTR_BREAKOUT"))
                .verdict(Verdict.SAFE)
                .finding("F11")
                .note("The row R19 exists for, and the one that carries its safety argument. Was not"
                        + " in the corpus before R19, because under F11 it rendered empty and said"
                        + " nothing that quoting.unquoted-after-literal-text did not. Now the value"
                        + " arrives html()-encoded into an attribute with no quotes around it, and"
                        + " the payload that tries to exploit exactly that - ATTR_BREAKOUT/"
                        + "unquoted-attr, 'x onmouseover=...' - cannot: html() escapes the space to"
                        + " &#32; and the '=' to &#61;, and the character-reference state appends to"
                        + " the value it is in rather than re-tokenizing, so nothing terminates the"
                        + " attribute. Verified against the rendered output: 'x onmouseover=...'"
                        + " renders <span title=x&#32;onmouseover&#61;...>, which is one attribute"
                        + " named title, and the DOM shape is identical to the benign render.")
                .browserRelevant()
                .build());

        cases.add(XssCase.id("unquoted.immediately-after-equals")
                .section(A1)
                .template("<a href=$data>link</a>")
                .sink(SinkKind.URL, "a", "href")
                .payloads(Payloads.family("PROTOCOL_RELATIVE"))
                .verdict(Verdict.SAFE)
                .finding("F6")
                .override(Payloads.PROTOCOL_RELATIVE, Verdict.KNOWN_VULNERABLE)
                .note("Re-verdicted by R19, and the citation moved with the verdict. Was"
                        + " SUPPRESSED_UNINTENDED x3 under F11: currentContext() had no case for"
                        + " TAG_ATTR_VALUE_BEFORE, so the value vanished with no error and no"
                        + " diagnostic, and the documented remedy was $_x.asis(), which disables"
                        + " Canoe entirely. R19 routes the state to ATTR_URI, so this template now"
                        + " renders byte-for-byte what <a href=\"$data\"> renders, minus the quotes -"
                        + " checked payload by payload against url()'s output. That makes it exactly"
                        + " as safe, and exactly as unsafe: PROTOCOL_RELATIVE/slashes arrives as"
                        + " //attacker.invalid/x.js because url() is a scheme filter and not an"
                        + " origin filter, which is F6 and is the residual R26 tracks; the two"
                        + " backslash spellings are percent-escaped to same-origin paths and are"
                        + " SAFE. The row cites the finding its current verdict is about, so it"
                        + " cites F6; F11's own evidence moved to unquoted.plain-text-after-equals.")
                // Browser-relevant since R19, and it is the tier that settles the safety argument:
                // whether a real engine reads an unquoted url()-encoded value as the href the DOM
                // oracle says it is. Its quoted twin url.href-full has been loaded since the tier
                // existed, so the two are directly comparable.
                .browserRelevant()
                .build());

        cases.add(XssCase.id("unquoted.whitespace-then-reference")
                .section(A1)
                .template("<a href= $data>link</a>")
                .sink(SinkKind.URL, "a", "href")
                .payloads(Payloads.family("PROTOCOL_RELATIVE"))
                .verdict(Verdict.SAFE)
                .finding("F6")
                .override(Payloads.PROTOCOL_RELATIVE, Verdict.KNOWN_VULNERABLE)
                .note("TAG_ATTR_VALUE_BEFORE skips whitespace, so the extra space changes nothing -"
                        + " which was true when this row recorded F11's suppression and is still true"
                        + " now that it records R19's routing. Re-verdicted with its twin above, and"
                        + " for the same reasons; the rendered output differs from that row's by the"
                        + " one space the template itself contains.")
                .build());

        // Script and style element bodies. Both suppressed, and both deliberately: refusing to output
        // into JavaScript and CSS is the centrepiece of the design.
        cases.add(XssCase.id("script.body-string-literal")
                .section(A1)
                .template("<script>var x = '$data';</script>")
                .sink(SinkKind.JAVASCRIPT, "script", null)
                .payloads(Payloads.families("QUOTE_BREAKOUT", "UNICODE_EDGE"))
                .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                .note("CTX_JS maps to the empty string. R13 fixed js() (F16), so the encoder is now"
                        + " correct, but wiring it into the CTX_JS arm is a deliberate design decision"
                        + " that has not been taken - and R14 deleted its CSS twin (CTX_CSS) rather"
                        + " than route to a CSS encoder, for the same reason. Refusing to interpolate"
                        + " into JavaScript is the centrepiece of the design, and this row stays"
                        + " suppressed.")
                .build());

        cases.add(XssCase.id("script.body-bare")
                .section(A1)
                .template("<script>$data</script>")
                .sink(SinkKind.JAVASCRIPT, "script", null)
                .payloads(Payloads.family("TAG_BREAKOUT"))
                .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                .build());

        cases.add(XssCase.id("style.body-declaration")
                .section(A1)
                .template("<style>p { color: $data; }</style>")
                .sink(SinkKind.CSS, "style", null)
                .payloads(Payloads.families("CSS_INJECTION", "CSS_IMPORT"))
                .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                .note("The CSS element body is the one CSS position Canoe gets right. Contrast"
                        + " css.style-with-property, where the identical payload survives because a"
                        + " colon in the attribute value threw the classification away (F4).")
                .build());

        cases.add(XssCase.id("style.body-bare")
                .section(A1)
                .template("<style>$data</style>")
                .sink(SinkKind.CSS, "style", null)
                .payloads(Payloads.family("CSS_IMPORT"))
                .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                .build());

        // A reference either side of a state transition, which is the shape T23's steering property
        // generalises: $data is encoded for CTX_URI and $second for CTX_HTML, in one render.
        XssCase.Builder transition = XssCase.id("transition.attribute-then-text")
                .section(A1)
                .template("<a href=\"$data\">$second</a>")
                .model("second", "click here")
                .sink(SinkKind.URL, "a", "href")
                .payloads(allUrlPayloads())
                .verdict(Verdict.SAFE)
                .finding("F6")
                .override(Payloads.PROTOCOL_RELATIVE, Verdict.KNOWN_VULNERABLE)
                .override(Payloads.ABSOLUTE_OFFSITE_HTTPS, Verdict.KNOWN_VULNERABLE)
                .note("Two references, two contexts, one render. The second is a fixed model value"
                        + " rather than the payload so that the sink under test stays unambiguous."
                        + " Full-URL position, so R12's scheme reverdict applies: rejected schemes"
                        + " suppress, the uppercase off-origin URL is KNOWN_VULNERABLE.");
        applyUrlSchemeReverdict(transition, allUrlPayloads(), true);
        cases.add(transition.build());
    }

    // ------------------------------------------------------------------
    // A.3 Event handlers
    // ------------------------------------------------------------------

    private static void eventHandlers(List<XssCase> cases) {

        // F1, closed by R4. The onS branch tested buf[0]=='s' inside a block that had already
        // established buf[0]=='o', so onselect and onsubmit fell through to ATTR_HTML.
        cases.add(XssCase.id("handler.onsubmit")
                .section(A3)
                .template("<form onsubmit=\"v('$data')\"></form>")
                .sink(SinkKind.JAVASCRIPT, "form", "onsubmit")
                .payloads(Payloads.families("QUOTE_BREAKOUT", "ENTITY_BREAKOUT"))
                .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                .finding("F1")
                .note("Re-verdicted by R4, from KNOWN_VULNERABLE. The dead branch at"
                        + " Canoe.java:536-552 is gone with the whole table, and the prefix rule that"
                        + " replaced it classifies onsubmit like any other name beginning 'on'."
                        + " Reviewed against the sink: all three payloads render"
                        + " <form onsubmit=\"v('')\"></form>, byte-identical to a render with an"
                        + " empty value, so the JavaScript parser is handed one empty string literal"
                        + " and the attacker contributes no character to it. SUPPRESSED_BY_DESIGN"
                        + " rather than SAFE: nothing is emitted, which is what CTX_JS means. "
                        + ENTITY_BREAKOUT_IS_THE_CONTROL
                        + " " + A_DOUBLE_QUOTE_CANNOT_CLOSE_A_SINGLE_QUOTED_LITERAL
                        + " The ENTITY_PRE_ENCODED override to SAFE is gone with it - that payload"
                        + " was safe because html() escaped its ampersands, and it is suppressed now"
                        + " like every other. The finding stays cited so the row remains traceable"
                        + " to F1; the not-browser-observable flag on the double-quote payload is"
                        + " gone with the KNOWN_VULNERABLE verdict it qualified, since a suppressed"
                        + " row expects browser silence anyway.")
                .browserRelevant()
                .build());

        // The contrast that used to make F1 dangerous: a reviewer who spot-checked onclick concluded
        // the mechanism worked. After R4 the two rows are the same statement, which is the fix.
        cases.add(XssCase.id("handler.onclick")
                .section(A3)
                .template("<a onclick=\"v('$data')\">x</a>")
                .sink(SinkKind.JAVASCRIPT, "a", "onclick")
                .payloads(Payloads.family("QUOTE_BREAKOUT"))
                .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                .note("One of the eighteen spec handlers the old table did recognise, and"
                        + " spot-checking it is how the F1 and F2 misses survived fifteen years."
                        + " Unchanged by R4 in outcome and entirely changed in reason: it is"
                        + " suppressed by the same two-character comparison as every other handler"
                        + " now, so there is nothing left for a spot check to be unrepresentative"
                        + " of.")
                .build());

        // F19, closed by R4. The chain's guard was buf[2]=='r' && buf[3]=='e' and its body then
        // tested buf[4]=='d', so the comparands spelled on+re+dystatechange - the 'a' of "ready" was
        // missing, and the branch could only ever match a name no document contains.
        cases.add(XssCase.id("handler.onreadystatechange")
                .section(A3)
                .template("<img src=\"x\" onreadystatechange=\"f('$data')\">")
                .sink(SinkKind.JAVASCRIPT, "img", "onreadystatechange")
                .payloads(Payloads.family("QUOTE_BREAKOUT"))
                .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                .finding("F19")
                .note("Re-verdicted by R4, from KNOWN_VULNERABLE. The misspelt chain at"
                        + " Canoe.java:505-534 is gone and the prefix rule reads two characters, so"
                        + " neither the fifth character nor the spelling of the rest can decide"
                        + " anything. Reviewed against the sink: both payloads render"
                        + " <img src=\"x\" onreadystatechange=\"f('')\">, so no attacker character"
                        + " reaches the attribute. The finding stays cited for traceability; the"
                        + " not-browser-observable flag on the QUOTE_BREAKOUT family is gone with"
                        + " the KNOWN_VULNERABLE verdict it qualified - it said that no element"
                        + " hosts this attribute, which is still true and is now beside the point.")
                .browserRelevant()
                .build());

        // The name the dead branch did match, which no document contains. Kept because it is F19's
        // evidence: before R4 it was the one spelling of the name that suppressed, and after R4 it
        // is indistinguishable from the real one - which is the fix stated in one pair of rows.
        cases.add(XssCase.id("handler.onredystatechange")
                .section(A3)
                .template("<img src=\"x\" onredystatechange=\"f('$data')\">")
                .sink(SinkKind.JAVASCRIPT, "img", "onredystatechange")
                .payloads(Payloads.family("QUOTE_BREAKOUT"))
                .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                .note("F19's evidence. The branch worked, for an attribute name that does not exist,"
                        + " and its real twin one letter away was injectable. Both are suppressed"
                        + " now, by the prefix rule, and the pair is kept so that any change which"
                        + " separates them again fails here.")
                .build());

        // F2, closed by R4: there was no "any attribute starting with on is a JS context" rule, and
        // the hand-written table predated most of the modern DOM event set.
        cases.add(XssCase.id("handler.onfocus")
                .section(A3)
                .template("<input value=\"search\" onfocus=\"h('$data')\">")
                .sink(SinkKind.JAVASCRIPT, "input", "onfocus")
                .payloads(Payloads.families("QUOTE_BREAKOUT", "ENTITY_BREAKOUT"))
                .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                .finding("F2")
                .note("Re-verdicted by R4, from KNOWN_VULNERABLE. onfocus is one of the 76 spec"
                        + " handlers the table had never heard of; the prefix rule that replaced the"
                        + " table has heard of all of them, including the ones the standard has not"
                        + " defined yet. Reviewed against the sink: all three payloads render"
                        + " <input value=\"search\" onfocus=\"h('')\">, byte-identical to a render"
                        + " with an empty value. " + ENTITY_BREAKOUT_IS_THE_CONTROL
                        + " " + A_DOUBLE_QUOTE_CANNOT_CLOSE_A_SINGLE_QUOTED_LITERAL
                        + " The finding stays cited for traceability, and the double-quote payload's"
                        + " not-browser-observable flag is gone with the verdict it qualified.")
                .browserRelevant()
                .build());
    }

    // ------------------------------------------------------------------
    // A.3 Event handlers, exhaustively (T15)
    // ------------------------------------------------------------------

    /**
     * The 21 {@code on*} names the deleted {@code on*} table could actually reach.
     *
     * <p>Every {@code on*} name classifies as {@code ATTR_JS} since R4, so this list no longer
     * partitions anything — it is kept because the two halves of the old partition are the two
     * halves of F1, F2 and F19, and a group where every row now says the same thing needs the record
     * of which rows used to say something else. {@code EventHandlerMatrixTest} asserts the halves
     * agree in outcome rather than that they differ.
     *
     * <p>The list is not maintained here:
     * {@code CanoeStateMachineTest.namesTheOldOnStarTableDeclared} owns the 24 names the table
     * declared, and {@code EventHandlerMatrixTest.theOldRecognisedListMatchesTheStateMachineTable}
     * asserts that this list is exactly that table minus the three dead branches.
     *
     * <p>Three of the 21 — {@code ondragdrop}, {@code onend} and {@code onmove} — are not event
     * handler content attributes in any version of the HTML Standard. See {@link #ONDRAGDROP_IS_DEAD}.
     */
    private static final String[] RECOGNISED_HANDLERS = {
            "onabort", "onblur", "onchange", "onclick", "ondblclick", "ondragdrop", "onend",
            "onerror", "onkeydown", "onkeypress", "onkeyup", "onload", "onmousedown", "onmousemove",
            "onmouseout", "onmouseover", "onmouseup", "onmove", "onreset", "onresize", "onunload"};

    /**
     * Every {@code on*} name the deleted {@code on*} table had never heard of, from two sources: the
     * HTML Standard's event handler content attributes (the checked-in list at
     * {@code src/test/resources/canoe/html-event-handler-attributes.txt}, which
     * {@code EventHandlerMatrixTest}'s completeness guard reads) and the handlers F2 enumerates that
     * the HTML Standard defines elsewhere or not at all — UI Events' {@code onfocusin}, CSS
     * Animations' {@code onanimationstart}, Pointer Events, Touch Events, and the two Selection
     * handlers.
     *
     * <p>Every one of them used to take the {@code ATTR_HTML} fall-through, which the HTML parser
     * undoes before the value is compiled as JavaScript. That was F2, and the count is the part of
     * F2 worth reading twice: the finding's title says "roughly 40", and there are 91 here. R4's
     * prefix rule closed all 91 at once, and the list is kept because 91 rows that were injectable
     * and now suppress are the regression net for the whole finding — a name reappearing on the
     * wrong side of the old partition fails here rather than being noticed by nobody.
     *
     * <p>{@code onselect}, {@code onsubmit} and {@code onreadystatechange} are deliberately absent —
     * Canoe declared branches for all three and could not take any of them, which is a different
     * finding (F1 and F19) and a different class of defect, so they are declared by hand above with
     * the failing comparison named.
     */
    private static final String[] UNRECOGNISED_HANDLERS = {
            // HTML Standard section 8.1.8.2, table 1: supported by all HTML elements.
            "onauxclick", "onbeforeinput", "onbeforematch", "onbeforetoggle", "oncancel",
            "oncanplay", "oncanplaythrough", "onclose", "oncommand", "oncontextlost",
            "oncontextmenu", "oncontextrestored", "oncopy", "oncuechange", "oncut", "ondrag",
            "ondragend", "ondragenter", "ondragleave", "ondragover", "ondragstart", "ondrop",
            "ondurationchange", "onemptied", "onended", "onformdata", "oninput",
            "oninvalid", "onloadeddata", "onloadedmetadata", "onloadstart", "onmouseenter",
            "onmouseleave", "onpaste", "onpause", "onplay", "onplaying", "onprogress",
            "onratechange", "onscrollend", "onsecuritypolicyviolation", "onseeked",
            "onseeking", "onslotchange", "onstalled", "onsuspend", "ontimeupdate", "ontoggle",
            "onvolumechange", "onwaiting", "onwheel",
            // ...and the four -webkit- prefixed names table 1 also defines. These are HTML
            // Standard content attributes in their own right, not CSS Animations / CSS Transitions
            // names: the unprefixed onanimationstart and ontransitionend are defined elsewhere and
            // appear further down this list, while these four are HTML's. Every one fires from a
            // CSS animation or transition with no user interaction at all in Blink, WebKit and
            // Gecko, which makes them the cheapest handlers in the whole group to trigger.
            "onwebkitanimationend", "onwebkitanimationiteration", "onwebkitanimationstart",
            "onwebkittransitionend",
            // HTML Standard table 2: all HTML elements other than <body> and <frameset>.
            "onfocus", "onscroll",
            // HTML Standard table 4: Document IDL attributes. NOT content attributes -- see
            // WINDOW_REFLECTING_HANDLERS for why the element matters, and NO_ELEMENT_HOSTS_IT for
            // why this one is generated on <div> anyway.
            "onvisibilitychange",
            // HTML Standard table 3: Window handlers, exposed as content attributes on <body> and
            // <frameset>. Generated on <body>; see WINDOW_REFLECTING_HANDLERS.
            "onafterprint", "onbeforeprint", "onbeforeunload", "onhashchange", "onlanguagechange",
            "onmessage", "onmessageerror", "onoffline", "ononline", "onpagehide", "onpagereveal",
            "onpageshow", "onpageswap", "onpopstate", "onrejectionhandled", "onstorage",
            "onunhandledrejection",
            // Defined outside the HTML Standard, and listed in F2.
            "onfocusin", "onfocusout", "onpointerdown", "onpointerup", "onpointerover",
            "onanimationstart", "onanimationend", "ontransitionrun", "ontransitionend", "onsearch",
            "onshow", "ontouchstart", "ontouchend", "ontouchmove", "onselectstart",
            "onselectionchange"};

    /**
     * The handlers the HTML Standard exposes on {@code Window} and reflects onto {@code <body>} and
     * {@code <frameset>} — and nowhere else.
     *
     * <p>These are generated on {@code <body>} rather than on the default {@code <div>}, and the
     * reason is a ledger correctness one rather than a cosmetic one. A case declares
     * {@link SinkKind#JAVASCRIPT}, which is a claim that the value is compiled as script; on a
     * {@code <div>} an {@code onstorage} attribute is inert text that no engine will ever register,
     * so the claim is false and the row would be a guaranteed browser-tier failure the moment T28
     * lands. The classification Canoe applies is identical either way — an {@code on*} handler is
     * JavaScript by its name prefix, and R9's tag-name check fires only on the six resource-loading
     * URL sinks, none of which is a handler — so moving the element costs nothing and makes the sink
     * real.
     *
     * <p>{@code onunload} is a Window handler too and is deliberately <em>not</em> here. It was one
     * of the 21 names the old table reached, so its row was already {@code SUPPRESSED_BY_DESIGN}
     * and generated on the default element. The list was drawn up as "the handlers whose row claims
     * a <em>live</em> sink", because that was the claim an element could falsify; since R4 every
     * handler row records suppression, but the {@link SinkKind#JAVASCRIPT} declaration is still a
     * claim about what the sink <em>would</em> compile, so these names stay on {@code <body>} where
     * that claim is true.
     */
    private static final List<String> WINDOW_REFLECTING_HANDLERS = Arrays.asList(
            "onafterprint", "onbeforeprint", "onbeforeunload", "onhashchange", "onlanguagechange",
            "onmessage", "onmessageerror", "onoffline", "ononline", "onpagehide", "onpagereveal",
            "onpageshow", "onpageswap", "onpopstate", "onrejectionhandled", "onstorage",
            "onunhandledrejection");

    /**
     * The two names that are IDL attributes on {@code Document} and content attributes on nothing.
     *
     * <p>No element hosts them. Writing {@code <div onvisibilitychange="...">} produces an attribute
     * the parser stores and no engine ever registers a listener from, which is exactly the shape
     * {@code WINDOW_REFLECTING_HANDLERS} exists to avoid — except that here there is no element to
     * move to, so the flag was the only honest record while the rows were live.
     *
     * <p>Retained as history after R4 suppressed both. The flag is gone with the verdicts it
     * qualified, and the fact it records — that markup cannot register a listener for either name —
     * is a property of the HTML Standard rather than of Canoe, so it is unchanged and it is what
     * would bound these two rows again if output into a handler were ever re-enabled.
     */
    private static final String NO_ELEMENT_HOSTS_IT =
            "No element hosts this attribute. It is an IDL attribute on Document (HTML Standard"
                    + " section 8.1.8.2, table 4), so it has no content-attribute form and no"
                    + " shipping engine registers a listener from markup -- <div "
                    + "onvisibilitychange=...> and <body onreadystatechange=...> are both inert."
                    + " While the row was KNOWN_VULNERABLE that mattered: Canoe classified the name"
                    + " as plain text and the attacker's characters arrived live, which is the"
                    + " ledger's subject, so the row was flagged not-browser-observable to tell the"
                    + " browser tier to expect a detector miss rather than report a divergence."
                    + " R4 suppresses it, so the browser tier expects silence anyway and the flag"
                    + " has been removed. Contrast handler.ontoggle, which is one click away, and"
                    + " the four onwebkit* handlers, which need no interaction at all -- both of"
                    + " those were reachable sinks and are suppressed by the same prefix rule.";

    /** Elements that take no closing tag, so the generated template does not emit one. */
    private static final List<String> VOID_ELEMENTS =
            Arrays.asList("input", "img", "br", "meta", "link");

    private static final String RECOGNISED_HANDLERS_ARE_THE_DESIGN_WORKING =
            "One of the 21 names the deleted on* table could actually reach: ATTR_JS -> CTX_JS ->"
                    + " the empty string. These cases are what stopped the group from being a list"
                    + " of 90 failures with nothing to compare them against - the encoder was not"
                    + " broken, the table was incomplete, and only having both halves in the ledger"
                    + " showed which. Reviewed against the sink after R4 and unchanged: the rendered"
                    + " handler body is the template's own text with an empty string literal in it."
                    + " Two qualifications this note used to carry are gone. They are no longer one"
                    + " of 21 names out of 115 - every on* name reaches the same two-character"
                    + " comparison now - and they are no longer suppressed only while the value has"
                    + " no colon in its first eleven characters, which was F17 and which R2 closed;"
                    + " prefix.colon-in-a-recognised-handler is that row.";

    private static final String UNRECOGNISED_HANDLERS_ARE_F2 =
            "Re-verdicted by R4, from KNOWN_VULNERABLE. The name was not in the hand-unrolled table,"
                    + " so it took the ATTR_HTML default: html() turned the payload into character"
                    + " references, the HTML parser decoded them while building the attribute value,"
                    + " and the JavaScript parser was handed the attacker's original characters -"
                    + " the identical mechanism to F1, reached by an omission rather than by a wrong"
                    + " buffer index. R4 replaced the table with a prefix rule, so there is no"
                    + " allowlist for a name to be missing from. Reviewed against the sink: the"
                    + " rendered handler body is f('') for the payload, byte-identical to a render"
                    + " with an empty value, so no attacker character reaches the JavaScript parser."
                    + " SUPPRESSED_BY_DESIGN rather than SAFE: nothing is emitted, which is what"
                    + " CTX_JS means. The finding stays cited so the row remains traceable to F2.";

    /**
     * Why {@code ondragdrop} is a curiosity rather than a flagged row.
     *
     * <p>It was the clearest single marker of the deleted table's age: {@code ondragdrop} was a
     * Netscape 4 event, removed from Gecko in Firefox 3, and no engine has fired it this century —
     * while HTML5's {@code ondrop} and {@code ondragstart}, which every engine fires, were missing.
     * Canoe spent a branch suppressing a handler that cannot run and let the two that can through.
     * R4's prefix rule covers all three, which is the general form of the observation.
     *
     * <p>It is deliberately <em>not</em> marked {@code notBrowserObservable}. That axis exists to
     * stop a {@link Verdict#KNOWN_VULNERABLE} row from becoming a guaranteed browser-tier failure,
     * and {@code CanoeCorpusTest.browserObservabilityIsOnlyClaimedWhereItChangesAnExpectation}
     * enforces that it may only be set where it changes an expectation. This row is
     * {@code SUPPRESSED_BY_DESIGN}: the browser tier already expects silence, so the flag would
     * record nothing and hide the reasoning. The dead-event observation belongs in this note, which
     * is where it is.
     */
    private static final String ONDRAGDROP_IS_DEAD =
            "A Netscape 4 event, removed from Gecko in Firefox 3 and fired by no engine since."
                    + " Canoe used to spend one of its 21 branches suppressing a handler that cannot"
                    + " run, while ondrop and ondragstart - which every engine fires - fell through"
                    + " to html(). R4's prefix rule suppresses all three, so the observation is now"
                    + " about why a hand-maintained table was the wrong structure rather than about"
                    + " which names it happened to hold. Suppressed here as it always was, so"
                    + " browser-observability says nothing and the flag is deliberately not set; see"
                    + " the field javadoc.";

    /**
     * The shape shared by every generated event-handler case.
     *
     * <p>One payload, not the family. The bulk of this group is 107 names that reach the identical
     * comparison chain and take one of two identical outcomes, so multiplying each by the payload
     * catalogue would buy nothing but run time — the per-payload distinctions are properties of
     * {@code html()} and of the JavaScript parser, and they are pinned exhaustively on the four
     * headline handlers ({@code onsubmit}, {@code onselect}, {@code onfocus} and {@code onclick}),
     * which carry {@code QUOTE_BREAKOUT} and {@code ENTITY_BREAKOUT} together. That pairing is the
     * corpus's evidence that the parser decodes exactly once; see
     * {@link #ENTITY_BREAKOUT_IS_THE_CONTROL}.
     *
     * <p>The element is {@code <div>} unless a case says otherwise. An {@code on*} handler is
     * classified by its name prefix and nothing else — R8 tracks the tag name now, but R9 consults it
     * only on the six resource-loading URL sinks, so no handler's classification depends on the
     * element — and choosing a "realistic" one per handler would suggest a dependency that does not
     * exist. It does affect whether the declared
     * {@link SinkKind#JAVASCRIPT} sink <em>exists</em>, which is a different question and one the
     * ledger has to get right: see {@link #WINDOW_REFLECTING_HANDLERS}, which are generated on
     * {@code <body>} because {@code <div onstorage>} is an attribute no engine will ever register.
     *
     * <p>{@code content} is element content, and it is a parameter for the same reason. An empty
     * {@code <div>} has zero height and cannot be hovered, so a hover handler on one is a sink that
     * exists in the markup and can never fire; the two hover cases carry visible text.
     */
    private static XssCase.Builder handler(String name, String element, String precedingAttributes,
                                           String content) {
        String open = "<" + element + precedingAttributes + " " + name + "=\"f('$data')\">";
        String template = VOID_ELEMENTS.contains(element)
                ? open
                : open + content + "</" + element + ">";
        return XssCase.id("handler." + name)
                .section(A3)
                .template(template)
                .sink(SinkKind.JAVASCRIPT, element, name)
                .payloads(Payloads.QUOTE_SINGLE_BREAKOUT);
    }

    private static XssCase.Builder handler(String name, String element, String precedingAttributes) {
        return handler(name, element, precedingAttributes, "");
    }

    private static XssCase.Builder handler(String name) {
        return handler(name, defaultElementFor(name), "", "");
    }

    /**
     * A stylesheet that actually starts an animation, so that an animation handler has something to
     * fire from.
     *
     * <p>Two colours that differ is enough: the animation runs and {@code animationstart} dispatches
     * whether or not anything visibly moves. It is spelled as a {@code <style>} element rather than
     * as an inline {@code animation-name} because {@code @keyframes} has no inline form.
     */
    private static final String CSS_ANIMATION_KEYFRAMES =
            "<style>@keyframes canoefade{from{opacity:0.9}to{opacity:1}}</style>";

    private static final String CSS_ANIMATION_IS_THE_CHEAPEST_TRIGGER =
            "A CSS animation fires this with no user interaction at all, which is the group's answer"
                    + " to 'the handler still needs a click' -- and the case therefore carries a real"
                    + " @keyframes rule and a real animation property, because a handler attribute"
                    + " on an element with no animation is a sink that cannot fire and a browser"
                    + " expectation nobody can meet.";

    private static final String HOVER_NEEDS_A_TARGET =
            "The element carries text. An empty <div> has zero height, so a hover handler on one can"
                    + " never be exercised: the markup declares a sink the browser tier could never"
                    + " reach, which is the same class of mistake as declaring SinkKind.JAVASCRIPT"
                    + " for a Window handler on a <div>.";

    /** A handler on an element that is genuinely animating; see {@link #CSS_ANIMATION_KEYFRAMES}. */
    private static XssCase.Builder animatedHandler(String name) {
        return XssCase.id("handler." + name)
                .section(A3)
                .template(CSS_ANIMATION_KEYFRAMES
                        + "<div style=\"animation:canoefade 1s\" " + name + "=\"f('$data')\">x</div>")
                .sink(SinkKind.JAVASCRIPT, "div", name)
                .payloads(Payloads.QUOTE_SINGLE_BREAKOUT)
                .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                .finding("F2")
                .browserRelevant();
    }

    /**
     * {@code <body>} for the handlers the HTML Standard reflects onto it, {@code <div>} for
     * everything else. See {@link #WINDOW_REFLECTING_HANDLERS}.
     */
    private static String defaultElementFor(String name) {
        return WINDOW_REFLECTING_HANDLERS.contains(name) ? "body" : "div";
    }

    /**
     * The event-handler matrix: the 21 names the deleted {@code on*} table could reach, the three it
     * declared and could not, and the 91 it had never heard of.
     *
     * <p>Every one of the 115 is {@code SUPPRESSED_BY_DESIGN} since R4, and the group's value is in
     * the split rather than in the verdicts: 97 of these rows were {@code KNOWN_VULNERABLE} against
     * F1, F2 and F19, and keeping them named and grouped is what makes a re-introduced allowlist
     * fail loudly instead of quietly re-opening the finding on whichever names it forgets.
     *
     * <p>{@code EventHandlerMatrixTest} (T15) is the test side of this, and its completeness guard is
     * the reason the group is exhaustive rather than representative: the guard reads the HTML
     * Standard's event handler content attributes from a checked-in resource file and fails if any
     * name has no case here. That converted "we listed the ones we thought of" — which is exactly
     * what {@code setTagAttributeContext()} used to be — into "we cover the spec", and it will fail
     * usefully the next time the list is refreshed against a newer revision of the standard. R4's
     * prefix rule is what makes it permanently satisfiable: a handler name the standard adds is
     * already classified before anybody writes its case.
     */
    private static void eventHandlerMatrix(List<XssCase> cases) {

        // The names already declared by hand above, with the failing comparison named. Collected
        // rather than hardcoded so that promoting a generated case to a hand-written one is a local
        // edit and not a silent duplicate-id failure.
        List<String> alreadyDeclared = new ArrayList<>();
        for (XssCase existing : cases) {
            if (A3.equals(existing.section())) {
                alreadyDeclared.add(existing.attribute());
            }
        }

        // F1's other half. onsubmit is declared by hand above; this is the same dead branch reached
        // through the other name it was written for.
        cases.add(XssCase.id("handler.onselect")
                .section(A3)
                .template("<input value=\"text\" onselect=\"v('$data')\">")
                .sink(SinkKind.JAVASCRIPT, "input", "onselect")
                .payloads(Payloads.families("QUOTE_BREAKOUT", "ENTITY_BREAKOUT"))
                .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                .finding("F1")
                .note("Re-verdicted by R4, from KNOWN_VULNERABLE. The onS block at"
                        + " Canoe.java:536-552 tested buf[0]=='s', and buf[0] was provably 'o'"
                        + " inside the block guarded by (buf[0]=='o' && buf[1]=='n'), so it asked"
                        + " whether the attribute was named 'select' - which it could not be. The"
                        + " reachability of the sink is why the row mattered: onselect fires on any"
                        + " text input the user selects text in, needing no script and no unusual"
                        + " interaction. Reviewed against the sink: all three payloads now render"
                        + " <input value=\"text\" onselect=\"v('')\">, byte-identical to a render"
                        + " with an empty value, so the handler body contains one empty string"
                        + " literal and no attacker character. " + ENTITY_BREAKOUT_IS_THE_CONTROL
                        + " " + A_DOUBLE_QUOTE_CANNOT_CLOSE_A_SINGLE_QUOTED_LITERAL
                        + " The finding stays cited for traceability; the ENTITY_PRE_ENCODED"
                        + " override and the double-quote payload's not-browser-observable flag are"
                        + " both gone with the KNOWN_VULNERABLE verdict they qualified.")
                .browserRelevant()
                .build());
        alreadyDeclared.add("onselect");

        for (String name : RECOGNISED_HANDLERS) {
            if (alreadyDeclared.contains(name)) {
                continue;
            }
            XssCase.Builder builder = handler(name)
                    .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                    .note("ondragdrop".equals(name)
                            ? ONDRAGDROP_IS_DEAD
                            : RECOGNISED_HANDLERS_ARE_THE_DESIGN_WORKING);
            if ("onmouseover".equals(name)) {
                // One suppressed control in the browser tier, so a green run means the detectors
                // stayed quiet when they should rather than that only vulnerable rows were loaded.
                // The div carries text: an empty div has zero height, so a hover handler on one is
                // a control that can never be exercised, and a control that cannot fire proves
                // nothing about the detectors staying quiet.
                builder = handler(name, "div", "", "hover me")
                        .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                        .note(RECOGNISED_HANDLERS_ARE_THE_DESIGN_WORKING + " " + HOVER_NEEDS_A_TARGET)
                        .browserRelevant();
            }
            cases.add(builder.build());
        }

        for (String name : UNRECOGNISED_HANDLERS) {
            if (alreadyDeclared.contains(name)) {
                continue;
            }
            XssCase.Builder builder = handler(name)
                    .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                    .finding("F2")
                    .note(UNRECOGNISED_HANDLERS_ARE_F2);
            switch (name) {
                case "ontoggle":
                    // <details> toggles on a plain click, so this is one of the cheapest of the 91
                    // to demonstrate in a browser and one worth loading.
                    builder = handler(name, "details", "", "<summary>x</summary>y")
                            .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                            .finding("F2")
                            .note(UNRECOGNISED_HANDLERS_ARE_F2
                                    + " ontoggle fires when a <details> element is opened, which is"
                                    + " one click and no script.")
                            .browserRelevant();
                    break;
                case "onmouseenter":
                    builder = handler(name, "div", "", "hover me")
                            .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                            .finding("F2")
                            .note(UNRECOGNISED_HANDLERS_ARE_F2
                                    + " onmouseenter used to enter the onmouse branch and match none"
                                    + " of d/m/o/u at buf[7], which is the near-miss shape that made"
                                    + " the hand-unrolled table impossible to audit: onmouseout and"
                                    + " onmouseover were one letter away and both suppressed. The"
                                    + " three are one statement now. "
                                    + HOVER_NEEDS_A_TARGET)
                            .browserRelevant();
                    break;
                case "onwebkitanimationstart":
                    // Two of the handlers in this group need neither a click nor a hover nor a
                    // resource load, and this is one of them, so it carries a real animation.
                    builder = animatedHandler(name)
                            .note(UNRECOGNISED_HANDLERS_ARE_F2
                                    + " " + CSS_ANIMATION_IS_THE_CHEAPEST_TRIGGER
                                    + " This is the spelling the HTML Standard itself defines --"
                                    + " section 8.1.8.2 table 1 lists the four -webkit- prefixed"
                                    + " names, which is why the checked-in spec list carries them"
                                    + " and why the count is 94 rather than 90.");
                    break;
                case "onanimationstart":
                    // Same shape, and it carries the keyframes for the same reason: the note used to
                    // claim "a CSS animation fires this with no user interaction" over a template
                    // with no @keyframes rule and no animation property, so nothing started and the
                    // claim was decoration rather than a browser expectation.
                    builder = animatedHandler(name)
                            .note(UNRECOGNISED_HANDLERS_ARE_F2
                                    + " " + CSS_ANIMATION_IS_THE_CHEAPEST_TRIGGER
                                    + " This is the CSS Animations spelling; the HTML Standard's own"
                                    + " onwebkitanimationstart is the prefixed twin, and every"
                                    + " engine fires both.");
                    break;
                case "onvisibilitychange":
                    builder.note(UNRECOGNISED_HANDLERS_ARE_F2 + " " + NO_ELEMENT_HOSTS_IT)
                            .browserRelevant();
                    break;
                case "onshow":
                    builder.note(UNRECOGNISED_HANDLERS_ARE_F2
                                    + " This row used to be flagged not-browser-observable, and it"
                                    + " was the only browser-RELEVANT handler in the group that"
                                    + " carried the flag for a dead event rather than for a missing"
                                    + " element: the 'show' event was removed from the HTML Standard"
                                    + " in 2022 and Gecko's <menuitem>, the other thing that fired"
                                    + " it, went with Firefox 85, so no shipping engine will"
                                    + " dispatch it. (The two Document IDL names,"
                                    + " onreadystatechange and onvisibilitychange, were also"
                                    + " flagged, for the different reason that no element hosts them"
                                    + " at all.) The flag is gone with the KNOWN_VULNERABLE verdict"
                                    + " it qualified - a suppressed row expects browser silence"
                                    + " anyway, and the corpus only permits the flag where it"
                                    + " changes an expectation. The dead-event observation is kept"
                                    + " here because it is what would bound this row again if"
                                    + " output into a handler were ever re-enabled. Compare"
                                    + " handler.ondragdrop, which is the same observation about a"
                                    + " handler the old table DID recognise and which therefore"
                                    + " never carried the flag either.")
                            .browserRelevant();
                    break;
                default:
                    break;
            }
            cases.add(builder.build());
        }
    }

    /**
     * Why the {@code ENTITY_BREAKOUT} family was carried into the three headline handler cases, and
     * what it measured while those cases were live.
     *
     * <p>It was declared for exactly this and then used only at {@code body.paragraph} and {@code
     * rcdata.textarea} — two plain-text sinks where the mechanism it probes cannot fire either way,
     * so the family was carried by the corpus without ever being exercised.
     *
     * <p>Retained as history after R4 made all three of those cases suppressions, for the same
     * reason {@link #CSS_BACKSLASH_IS_AN_ESCAPE} and {@link #THE_CSS_CONTAINER_DECIDES} are kept:
     * what it describes is a property of the HTML parser rather than of Canoe, it is unchanged, and
     * it is the reasoning that would decide the pair's verdicts again if anything ever re-enabled
     * output into a handler. The payloads stay in the cases so that a regression is measured against
     * the same inputs that measured the finding.
     */
    private static final String ENTITY_BREAKOUT_IS_THE_CONTROL =
            "The two payloads here were a matched pair while this sink was live, and together they"
                    + " were the corpus's only direct evidence for the claim the whole review turns"
                    + " on. QUOTE_BREAKOUT carries a raw apostrophe: html() wrote &#39;, the HTML"
                    + " parser decoded it while building the attribute value, and the JavaScript"
                    + " parser was handed a real quote - one decode, and the string literal was"
                    + " escaped. ENTITY_PRE_ENCODED carries the SAME payload already spelled as"
                    + " character references: html() escaped its ampersands, so the parser's one"
                    + " decode returned the literal text &#39;&#41; and the JavaScript parser saw"
                    + " eight harmless characters inside the string. Same sink, same encoder,"
                    + " opposite outcomes - which is what showed the parser decodes exactly once."
                    + " Neither payload is emitted at all now, so the pair no longer distinguishes"
                    + " anything here; the reasoning is kept because it is a statement about the"
                    + " HTML parser rather than about Canoe, and it is what would decide these rows"
                    + " again if output into a handler were ever re-enabled.";

    // ------------------------------------------------------------------
    // A.2 Attribute names
    // ------------------------------------------------------------------

    /**
     * The five names {@code setTagAttributeContext()} has always mapped to {@code ATTR_URI}, so the
     * value goes through {@code HtmlEncoder.url()}.
     *
     * <p>On the elements that do not load a subresource with the name — {@code <a href>},
     * {@code <img src>}, {@code <table background>} and the rest — all of these behave identically,
     * and identically wrongly, because {@code url()} is a scheme filter rather than an origin filter
     * (F6). Every {@code javascript:}-style scheme is genuinely neutralised — since R12 it is rejected
     * to the empty string rather than colon-escaped — and the off-origin authority is what survives.
     * That is the F6 residue R9 scopes out by design: an open redirect or a referrer leak, not code
     * execution. Where the same name <em>does</em> load a subresource — {@code src} on {@code
     * <script>}, and {@code data} on {@code <object>} in {@link #unrecognisedUrlAttributes} — R9
     * routes it to {@code urlResource()} instead and the off-origin authority is rejected; see
     * {@code url.script-src-prefix} and {@code attr.data-on-object}.
     *
     * <p>Since R6 there are seventeen names in the group rather than five, and the twelve additions
     * are in {@link #unrecognisedUrlAttributes} with the F3 history that brought them here. Their
     * verdicts are these verdicts: that is what "routed to url()" means, and it is why the ledger's
     * F6 count went up when F3's went to zero.
     */
    private static void recognisedUriAttributes(List<XssCase> cases) {

        String urlAccidents =
                "Since R12 these outcomes are by design rather than by accident. PROTOCOL_RELATIVE_"
                        + "BACKSLASH is safe because url() percent-encodes the backslash to %5C - it"
                        + " is neither unreserved nor a delimiter - and no browser un-escapes it into"
                        + " a separator. ABSOLUTE_OFFSITE_USERINFO is safe because the authority safe"
                        + " set excludes '@', so it becomes %40, a forbidden host code point, and the"
                        + " URL fails to parse. ABSOLUTE_OFFSITE_UPPERCASE, by contrast, is NO longer"
                        + " safe: the old case-sensitive regex neutralised it by accident, and R12"
                        + " normalises the scheme, so it is a real off-origin URL and is"
                        + " KNOWN_VULNERABLE under F6 like its lowercase sibling.";

        cases.add(recognisedUriAttribute("url.href-full",
                "<a href=\"$data\">link</a>", "a", "href")
                .note(urlAccidents)
                .browserRelevant()
                .build());

        cases.add(recognisedUriAttribute("url.img-src",
                "<img src=\"$data\">", "img", "src")
                .note("R9 keeps <img src> on url(): an off-origin image is a referrer leak and a load,"
                        + " not code execution, so it stays an open-redirect/referrer surface by"
                        + " design. This is where the off-origin passthrough is still KNOWN_VULNERABLE"
                        + " under F6 - the residual R9 scopes out and R26 tracks - and it is exactly"
                        + " the row url.script-src-prefix used to be byte-identical to before R8 gave"
                        + " Canoe the tag name to tell them apart.")
                .browserRelevant()
                .build());

        cases.add(recognisedUriAttribute("url.background",
                "<table background=\"$data\"><tr><td>x</td></tr></table>", "table", "background")
                .note("background is ten characters, which is exactly the length that leaves buf[10]"
                        + " holding its own NUL terminator - see residue.data-url-armed-buffer for"
                        + " what that does to the shorter prefix checks.")
                .build());

        cases.add(recognisedUriAttribute("url.dynsrc",
                "<img dynsrc=\"$data\">", "img", "dynsrc")
                .note("A dead Internet Explorer attribute. Recognised, while HTML5's srcset is not -"
                        + " a good marker of the table's age.")
                .build());

        cases.add(recognisedUriAttribute("url.lowsrc",
                "<img lowsrc=\"$data\">", "img", "lowsrc")
                .build());

        // <script src> is a resource-loading sink (R9): with R8's tag name available, Canoe routes it
        // to urlResource(), which rejects an off-origin or protocol-relative authority. It is no
        // longer the same encoder as <img src>, which is the whole point of R9 and is why
        // UrlSinkTest.everyElementGetsTheSameEncoderForTheSameAttributeName is inverted.
        XssCase.Builder scriptSrcPrefix = XssCase.id("url.script-src-prefix")
                .section(A2)
                .template("<script src=\"$data/app.js\"></script>")
                .sink(SinkKind.URL, "script", "src")
                .payloads(allUrlPayloads())
                .verdict(Verdict.SAFE)
                .finding("F6")
                .note("Re-verdicted by R9, from KNOWN_VULNERABLE/F6 to SUPPRESSED_BY_DESIGN. The"
                        + " sink loads and executes JavaScript, so an off-origin value here is"
                        + " arbitrary code with full page privileges - the code-execution end of F6,"
                        + " and what R9 closes. Path-prefix position, so the payload reaches the"
                        + " authority; urlResource() rejects a protocol-relative //host, an absolute"
                        + " https://host and its uppercase sibling to the empty string, so the src"
                        + " falls back to the template's own '/app.js'. Reviewed against the sink: the"
                        + " rendered script tag carries no attacker host. The rejected-scheme payloads"
                        + " were already SUPPRESSED_BY_DESIGN under R12.")
                .browserRelevant();
        applyUrlSchemeReverdict(scriptSrcPrefix, allUrlPayloads(), true);
        resourceSinkRejectsOffOrigin(scriptSrcPrefix, Payloads.PROTOCOL_RELATIVE,
                Payloads.ABSOLUTE_OFFSITE_HTTPS, Payloads.ABSOLUTE_OFFSITE_UPPERCASE);
        cases.add(scriptSrcPrefix.build());

        // The rest of the resource-loading elements. Before R8 gave Canoe the tag name these were
        // byte-identical to <img src> - the same encoder for a referrer leak and for arbitrary code -
        // and that identity WAS F6's structural cause. R9 ends it: <iframe src>, <embed src> and
        // <link href> reject an off-origin authority where <img src> passes it, because an off-origin
        // iframe or embed is an attacker document in the page and an off-origin stylesheet can
        // overlay and exfiltrate. UrlSinkTest.theTagNameNowDecidesTheEncoderForSrcAndHref asserts the
        // split rather than the old byte-identity.
        cases.add(resourceSinkRejectsOffOrigin(recognisedUriAttribute("url.iframe-src",
                "<iframe src=\"$data\"></iframe>", "iframe", "src"),
                Payloads.PROTOCOL_RELATIVE, Payloads.ABSOLUTE_OFFSITE_HTTPS,
                Payloads.ABSOLUTE_OFFSITE_UPPERCASE)
                .note("Re-verdicted by R9, from KNOWN_VULNERABLE/F6. An off-origin iframe is an"
                        + " attacker-controlled document inside the page's frame tree, with"
                        + " postMessage, top-level navigation and full-viewport overlay available to"
                        + " it - the code-execution end of F6. R9 routes <iframe src> to"
                        + " urlResource(), which rejects the authority, so the src renders empty. No"
                        + " longer the same encoder as <img src>: that is the finding, now fixed.")
                .browserRelevant()
                .build());

        cases.add(resourceSinkRejectsOffOrigin(recognisedUriAttribute("url.embed-src",
                "<embed src=\"$data\">", "embed", "src"),
                Payloads.PROTOCOL_RELATIVE, Payloads.ABSOLUTE_OFFSITE_HTTPS,
                Payloads.ABSOLUTE_OFFSITE_UPPERCASE)
                .note("Re-verdicted by R9, from KNOWN_VULNERABLE/F6. <embed> loads a plugin document"
                        + " from the URL, so R9 treats <embed src> as a resource-loading sink and"
                        + " urlResource() rejects the off-origin authority. The tag name R8 keeps is"
                        + " what now distinguishes it from <img>.")
                .build());

        cases.add(resourceSinkRejectsOffOrigin(recognisedUriAttribute("url.link-href",
                "<link rel=\"stylesheet\" href=\"$data\">", "link", "href"),
                Payloads.PROTOCOL_RELATIVE, Payloads.ABSOLUTE_OFFSITE_HTTPS,
                Payloads.ABSOLUTE_OFFSITE_UPPERCASE)
                .note("Re-verdicted by R9, from KNOWN_VULNERABLE/F6. An off-origin stylesheet can lay"
                        + " a full-viewport overlay, exfiltrate DOM content through attribute-selector"
                        + " url() rules, and restyle a form's submit surroundings, so <link href> is a"
                        + " resource-loading sink. urlResource() rejects the authority; the href"
                        + " renders empty. <a href> keeps url() and stays an open-redirect surface -"
                        + " the deliberate boundary R9 draws.")
                .build());

        // The four substitution positions. url() escapes the same characters wherever the reference
        // sits, and the four positions still behave differently, because what makes an off-origin
        // URL off-origin is its position in the value rather than its bytes.
        XssCase.Builder hrefQuery = XssCase.id("url.href-query-parameter")
                .section(A2)
                .template("<a href=\"/search?q=$data\">x</a>")
                .sink(SinkKind.URL, "a", "href")
                .payloads(allUrlPayloads())
                .verdict(Verdict.SAFE)
                .note("Query-parameter position, and the off-origin payloads are SAFE - including the"
                        + " two that make url.href-full and url.script-src-prefix vulnerable. The"
                        + " reason is not the encoder: //attacker.invalid/x.js survives url() byte for"
                        + " byte here exactly as it does there, and the uppercase absolute URL stays"
                        + " SAFE too, because the template's own literal '/search?q=' has already"
                        + " committed the URL to the page's origin, so the attacker's"
                        + " authority-looking bytes are query data. This case exists to stop F6 being"
                        + " read as 'a URL-bearing attribute is vulnerable': F6 is reachable only"
                        + " where the payload can reach the AUTHORITY, which is the full-URL and"
                        + " path-prefix positions and not these two. The rejected-scheme payloads are"
                        + " the one thing that does move: a value beginning javascript:, data: or"
                        + " vbscript: is a whole URL to url() wherever the template puts it, so R12"
                        + " suppresses it and the query value is dropped - fail-safe, and an availability"
                        + " cost only a query literally starting with a rejected scheme would ever pay.");
        applyUrlSchemeReverdict(hrefQuery, allUrlPayloads(), false);
        cases.add(hrefQuery.build());

        XssCase.Builder hrefFragment = XssCase.id("url.href-fragment")
                .section(A2)
                .template("<a href=\"/page#$data\">x</a>")
                .sink(SinkKind.URL, "a", "href")
                .payloads(allUrlPayloads())
                .verdict(Verdict.SAFE)
                .note("Fragment position, safe for the same reason as the query one and one step"
                        + " further: everything after the '#' is not even sent to the server. Note"
                        + " that url()'s allowlist passes '#' and '?' naked, so a payload in PATH"
                        + " position can still add a query or a fragment of its own - it just cannot"
                        + " add an authority, which is the only thing that changes origin. As in the"
                        + " query case, a value that is itself a rejected-scheme URL suppresses under"
                        + " R12 rather than percent-escaping.");
        applyUrlSchemeReverdict(hrefFragment, allUrlPayloads(), false);
        cases.add(hrefFragment.build());

        // <base href> is the widest resource sink of all: it retargets every relative URL on the
        // rest of the page. R9 routes it to urlResource(), which rejects the off-origin authority.
        XssCase.Builder baseHref = XssCase.id("url.base-href")
                .section(A2)
                .template("<base href=\"$data\"><img src=\"/logo.png\">")
                .sink(SinkKind.URL, "base", "href")
                .payloads(Payloads.families("BASE_HIJACK", "PROTOCOL_RELATIVE", "ABSOLUTE_OFFSITE"))
                .verdict(Verdict.SAFE)
                .finding("F6")
                .note("Re-verdicted by R9, from KNOWN_VULNERABLE/F6. The widest blast radius of any"
                        + " F6 case: <base href> retargets every subsequent relative URL on the page,"
                        + " so one attacker-controlled value would move every script, stylesheet,"
                        + " image and form action to the attacker's origin - which is why it belongs"
                        + " in the resource-loading set even though the review does not name <base>"
                        + " specifically. urlResource() rejects the BASE_HIJACK host, the"
                        + " protocol-relative and both absolute off-origin URLs to the empty string,"
                        + " so the base href renders empty and the page keeps its own origin.")
                .browserRelevant();
        resourceSinkRejectsOffOrigin(baseHref, Payloads.BASE_HIJACK, Payloads.PROTOCOL_RELATIVE,
                Payloads.ABSOLUTE_OFFSITE_HTTPS, Payloads.ABSOLUTE_OFFSITE_UPPERCASE);
        cases.add(baseHref.build());

        // F7, closed by R7. The branch that was meant to test for 'content' tested for 'data'
        // instead, so 'data' resolved to ATTR_CONTENT and the value was dropped - fail-safe, and a
        // functional bug developers route around with $_x.asis() - while 'content' had no test at
        // all. R7 resolved the pair: <object data> is a URL.
        cases.add(resourceSinkRejectsOffOrigin(urlAttributeAddedByR6("attr.data-on-object",
                "<object data=\"$data\"></object>", "object", "data"),
                Payloads.PROTOCOL_RELATIVE, Payloads.ABSOLUTE_OFFSITE_HTTPS)
                .note("Re-verdicted twice: by R7 from SUPPRESSED_UNINTENDED/F7 (the copy-paste that"
                        + " compared 'data' where it meant 'content' dropped this value; R7 made"
                        + " <object data> a URL), and by R9 from KNOWN_VULNERABLE/F6 to"
                        + " SUPPRESSED_BY_DESIGN. <object data> loads the object element's resource -"
                        + " a document, an image or, historically, a plugin - so R9 treats it as a"
                        + " resource-loading sink and urlResource() rejects the off-origin authority."
                        + " Reviewed against the sink: the data attribute renders empty for a"
                        + " protocol-relative or absolute off-origin URL. AttributePrefixTest"
                        + ".theDataBranchPairIsResolved is the mechanism side of the F7 half.")
                .build());
    }

    /**
     * The names where {@code ATTR_HTML} is the right answer: the browser treats the value as plain
     * text, and {@code html()}'s allowlist cannot be escaped from a quoted or even an unquoted value
     * because space and {@code >} are encoded too.
     *
     * <p>These are the cases that stop a green run from being vacuous. A suite that only ever asserts
     * "this is broken" proves nothing when a fix lands.
     */
    private static void plainTextAttributes(List<XssCase> cases) {

        List<Payload> full = Payloads.families("TAG_BREAKOUT", "ATTR_BREAKOUT", "QUOTE_BREAKOUT");

        cases.add(plainTextAttribute("plain.id", "<div id=\"$data\">x</div>", "div", "id", full)
                .note("ATTR_HTML is correct here: the browser treats the value as plain text.")
                .browserRelevant()
                .build());

        cases.add(plainTextAttribute("plain.class", "<div class=\"$data\">x</div>", "div", "class",
                full).build());

        cases.add(plainTextAttribute("plain.title", "<a title=\"$data\">link</a>", "a", "title",
                Payloads.families("TAG_BREAKOUT", "ATTR_BREAKOUT", "QUOTE_BREAKOUT",
                        "UNICODE_EDGE", "CONTROL_CHARS", "LENGTH_STRESS"))
                .note("Carries the encoder edge cases as well as the breakout families, because a"
                        + " plain-text attribute is where a mangled-but-inert value is most likely to"
                        + " be noticed by a user rather than by a parser.")
                .browserRelevant()
                .build());

        cases.add(plainTextAttribute("plain.value", "<input value=\"$data\">", "input", "value",
                full).build());

        cases.add(plainTextAttribute("plain.alt", "<img alt=\"$data\" src=\"/i.png\">", "img", "alt",
                plainTextProbe()).build());

        cases.add(plainTextAttribute("plain.name", "<input name=\"$data\">", "input", "name",
                plainTextProbe()).build());

        cases.add(plainTextAttribute("plain.placeholder", "<input placeholder=\"$data\">",
                "input", "placeholder", plainTextProbe())
                .note("Eleven characters, so until R3 this attribute name was also the one that armed"
                        + " F5's buffer residue for whatever followed it - see"
                        + " residue.js-url-armed-buffer, which is the same name used as an attack"
                        + " and is now suppressed.")
                .build());

        // The three names F20's table lists and SinkKind.POLICY's criteria exclude. They live here
        // rather than being deleted, because "we thought about this one and it does not qualify" is
        // information, and because the next reader will otherwise re-derive the same argument.
        //
        // R5 had to decide each of them again, from the other end: the question stopped being "is
        // this a policy sink" and became "is this a plain-text sink we are willing to put on an
        // allowlist". All three answers came out the same way and all three are ON the allowlist, so
        // these rows are unchanged - which is the useful thing about them, since a fail-closed
        // default that suppressed them would have been the availability failure trap 4 warns about,
        // and a denylist that admitted sandbox would have been the security one. The verdicts below
        // are therefore load-bearing in both directions.
        cases.add(plainTextAttribute("plain.type", "<script type=\"$data\" src=\"/app.js\"></script>",
                "script", "type", Payloads.family("POLICY_OVERRIDE"))
                .note("R5's decision: ON the plain-text allowlist, so html() still applies and the"
                        + " value still arrives verbatim. Considered for SinkKind.POLICY and"
                        + " rejected, for the reasons that follow, and considered again for"
                        + " suppression under R5 and kept. type on <script> is a"
                        + " content-type directive, and the only thing an attacker can do with it is"
                        + " make the browser refuse to run the script - which fails safe. There is no"
                        + " value of type that turns script execution ON where it was off, because"
                        + " the element is a <script> either way. It is also plain text nearly"
                        + " everywhere else it appears: <input type>, <button type>, <ol type>. A"
                        + " category that has to be widened from 'security control' to 'security or"
                        + " behavioural directive' to admit it is a category that has stopped"
                        + " meaning anything.")
                .build());

        cases.add(plainTextAttribute("plain.target", "<a target=\"$data\" href=\"/x\">y</a>",
                "a", "target", Payloads.family("POLICY_OVERRIDE"))
                .note("R5's decision: ON the plain-text allowlist. Considered for SinkKind.POLICY"
                        + " and rejected, and considered again for suppression under R5 and kept."
                        + " Retargeting a navigation into a"
                        + " named or new browsing context is behaviour, not a security control being"
                        + " switched off - the closest it comes is that target=_blank implies"
                        + " noopener, and the attribute that undoes THAT is rel, which is why rel is"
                        + " in the policy group and suppressed and this is not. The residual is"
                        + " stated rather than waved away: an attacker-chosen target opens the"
                        + " TEMPLATE AUTHOR's URL in a context of the attacker's naming, which is a"
                        + " UI-redressing nuisance and not a control being turned off, because the"
                        + " destination is not theirs to choose. Recorded as SAFE with the value"
                        + " arriving verbatim, which is the honest description.")
                .build());

        cases.add(plainTextAttribute("plain.formtarget",
                "<form action=\"/save\"><button formtarget=\"$data\">go</button></form>",
                "button", "formtarget", Payloads.family("POLICY_OVERRIDE"))
                .note("R5's decision: ON the plain-text allowlist. The submit-button analogue of"
                        + " plain.target, rejected from the policy group and kept off the"
                        + " suppression list for the same reason. Worth reading beside url.formaction,"
                        + " which R6 routed to url(): the two names differ by four characters and"
                        + " one of them names a window while the other names the place the form's"
                        + " contents are sent. A single classification for both would have been"
                        + " wrong whichever one it picked.")
                .build());

        cases.add(plainTextAttribute("plain.lang", "<p lang=\"$data\">x</p>", "p", "lang",
                plainTextProbe()).build());

        cases.add(plainTextAttribute("plain.dir", "<p dir=\"$data\">x</p>", "p", "dir",
                plainTextProbe()).build());

        cases.add(plainTextAttribute("plain.role", "<div role=\"$data\">x</div>", "div", "role",
                plainTextProbe()).build());

        cases.add(plainTextAttribute("plain.aria-label", "<button aria-label=\"$data\">x</button>",
                "button", "aria-label", plainTextProbe()).build());

        cases.add(plainTextAttribute("plain.data-star", "<div data-widget=\"$data\">x</div>",
                "div", "data-widget", plainTextProbe())
                .note("data-* names do not match the 'data' branch, which requires buf[4]=='\\0'."
                        + " They land in ATTR_HTML, which is correct for them.")
                .build());

        // DOM clobbering. Not XSS, and the ledger says so rather than quietly rounding it to SAFE:
        // the value does reach the sink, and the sink is the document's named-element namespace.
        cases.add(XssCase.id("clobber.id")
                .section(A2)
                .template("<div id=\"$data\">x</div>")
                .sink(SinkKind.PLAIN_TEXT_ATTR, "div", "id")
                .payloads(Payloads.family("DOM_CLOBBER"))
                .verdict(Verdict.SAFE)
                .note("SAFE for this suite's subject, which is XSS: the value cannot execute and"
                        + " cannot change document structure. It is not harmless - an attacker-chosen"
                        + " id or name shadows a global, so script that reads document.body or"
                        + " location through the named-element namespace gets an element instead."
                        + " No encoder can prevent that, because the legal values are exactly the"
                        + " dangerous ones; only refusing to interpolate can. Recorded here so the"
                        + " limitation is visible rather than absent.")
                .build());

        cases.add(XssCase.id("clobber.name")
                .section(A2)
                .template("<input name=\"$data\">")
                .sink(SinkKind.PLAIN_TEXT_ATTR, "input", "name")
                .payloads(Payloads.family("DOM_CLOBBER"))
                .verdict(Verdict.SAFE)
                .note("The form-control variant of clobber.id, and the more reachable one: named form"
                        + " controls are exposed on the form object as well as on document.")
                .build());
    }

    /**
     * The URL-bearing names {@code setTagAttributeContext()} had never heard of (F3), and what R5
     * and R6 did with them.
     *
     * <p>Every one of them used to fall through to {@code ATTR_HTML}, and the HTML parser undoes
     * that encoding before handing the value to the URL parser — so the attacker recovered every
     * character. The contrast with {@link #recognisedUriAttributes} was the whole finding:
     * {@code href} was protected by {@code url()} and {@code xlink:href} was not, so the
     * safe-by-analogy assumption a developer would make was exactly wrong.
     *
     * <p>The group splits in two now, and the split is a judgement rather than a mechanism:
     *
     * <ul>
     *   <li><strong>Routed to {@code url()}</strong> — the names R6 lists, which an ordinary
     *       template interpolates into. Their verdicts become {@link #recognisedUriAttribute}'s,
     *       because they now <em>are</em> recognised URI attributes: script schemes neutralised,
     *       off-origin URLs passing byte for byte, and the rows that stay
     *       {@link Verdict#KNOWN_VULNERABLE} citing <strong>F6</strong> rather than F3. That is the
     *       honest record of the change: the classification defect is closed and the encoder defect
     *       underneath it is not.
     *   <li><strong>Suppressed</strong> — {@code imagesrcset}, {@code xml:base}, {@code archive},
     *       {@code classid} and {@code profile}, which R5's fail-closed default catches because R6
     *       deliberately did not list them. Suppression is strictly stronger than {@code url()};
     *       what it costs is the value.
     * </ul>
     */
    private static void unrecognisedUrlAttributes(List<XssCase> cases) {

        // Why the C0-control and entity accidents that used to be recorded here are gone with the
        // classification. They were properties of html(), and html() no longer runs on any of these
        // names; under url() the same six payloads are safe for one reason instead of three, which
        // is the colon. C0_CONTROL_ACCIDENT is still carried by the sinks that still reach html().
        String underUrlEncodingNow =
                "Re-verdicted by R5+R6, then again by R11+R12. The name is on the URL list, so url()"
                        + " applies where html() used to. Since R12 url() parses the value and rejects"
                        + " a scheme off its {http, https, mailto} allowlist to the empty string, so"
                        + " reviewed against the sink: a clean javascript:, data:, vbscript: or"
                        + " view-source: URL is SUPPRESSED_BY_DESIGN (nothing renders), an off-origin"
                        + " http(s) or protocol-relative URL arrives byte for byte and is"
                        + " KNOWN_VULNERABLE under F6 - and the uppercase-scheme off-origin URL joins"
                        + " it, because R12 normalises the scheme rather than the old regex leaving it"
                        + " relative. The JS_URL variants with no clean scheme (a tab-split, an"
                        + " entity- or percent-encoded prefix, a leading control) carry no colon at"
                        + " the head, so url() reads them as relative references and they stay SAFE."
                        + " The finding citation moves with the defect: F3 was 'this name is not"
                        + " classified', which is fixed, and F6 is 'url() is a scheme filter rather"
                        + " than an origin filter', which is what is left. R9 closes the remainder.";

        String suppressedInstead =
                "Re-verdicted by R5, from KNOWN_VULNERABLE/F3 to SUPPRESSED_BY_DESIGN. R6 did not"
                        + " put this name on the URL list, so R5's fail-closed default catches it"
                        + " and the value is dropped. Reviewed against the sink: the attribute"
                        + " renders empty for all three payloads, byte-identical to a render with an"
                        + " empty value. The finding stays cited so the row remains traceable to F3."
                        + " Suppression rather than url() is deliberate and is the stronger of the"
                        + " two answers - url() would leave F6's off-origin passthrough open on this"
                        + " name - and it is affordable precisely because no ordinary template"
                        + " interpolates into it. If one needs to, that is a later task to route it"
                        + " to a URL encoder, and NOT a name for the application allowlist:"
                        + " Canoe.NAMES_THAT_MAY_NOT_BE_ADDED refuses it from configuration, because"
                        + " the plain-text allowlist grants html(), which is F3 on a URL sink and is"
                        + " weaker than the url() this name was deliberately denied. The strongest"
                        + " answer and the weakest one are one property line apart, so the property"
                        + " line throws.";

        // The four headline sinks carry the full thirteen payloads, so the per-payload distinctions
        // are pinned exhaustively somewhere.
        cases.add(urlAttributeAddedByR6("url.action",
                "<form action=\"$data\"></form>", "form", "action", allUrlPayloads())
                .note("A javascript: URL here used to run on submit; an absolute URL still sends the"
                        + " form's contents - including any CSRF token - to the attacker, which is"
                        + " the half R6 does not close. " + underUrlEncodingNow)
                .browserRelevant()
                .build());

        cases.add(urlAttributeAddedByR6("url.formaction",
                "<form action=\"/save\"><button formaction=\"$data\">go</button></form>",
                "button", "formaction", allUrlPayloads())
                .note("formaction overrides the form's own action, so a template that carefully sets"
                        + " action from a constant is still fully controllable by whoever controls"
                        + " this value. " + underUrlEncodingNow)
                .browserRelevant()
                .build());

        cases.add(urlAttributeAddedByR6("url.srcset",
                "<img srcset=\"$data\" src=\"/i.png\">", "img", "srcset", allUrlPayloads())
                .note("srcset takes precedence over src where the browser supports it, and it is a"
                        + " comma-and-whitespace separated list of candidates with descriptors."
                        + " " + underUrlEncodingNow
                        + " The list syntax is the one place R6 accepted an availability cost with"
                        + " its eyes open: url() percent-encodes the comma and the space, so an"
                        + " interpolated multi-candidate value loses its descriptors and becomes one"
                        + " long URL. Parsing the list and encoding each candidate separately is a"
                        + " feature to design rather than a default to guess at, and the shape a"
                        + " template actually writes - srcset=\"$url\" - still yields a usable URL."
                        + " Recorded here as well as on Canoe.URL_ATTRIBUTE_NAMES because this is"
                        + " where somebody investigating a broken image will look."
                        + " The JS_URL rows used to be flagged not-browser-observable here, and the"
                        + " flag went with the KNOWN_VULNERABLE verdict it qualified: an image source"
                        + " is fetched, never navigated to or executed, so no srcset candidate has"
                        + " ever run a javascript: URL in any engine. The off-origin rows are the"
                        + " ones a browser confirms, and they are the ones still vulnerable.")
                .browserRelevant()
                .build());

        cases.add(urlAttributeAddedByR6("url.poster",
                "<video poster=\"$data\"></video>", "video", "poster", allUrlPayloads())
                .note(underUrlEncodingNow)
                .build());

        // F3's clearest single row, closed by R6. isTagNameChar accepts ':', so xlink:href always
        // scanned as a single attribute name; it simply did not match href, and one attribute name
        // away from the best-protected sink in the component was the worst-protected one.
        cases.add(urlAttributeAddedByR6("url.xlink-href",
                "<svg><a xlink:href=\"$data\"><text>go</text></a></svg>", "a", "xlink:href",
                Payloads.families("JS_URL", "PROTOCOL_RELATIVE", "ABSOLUTE_OFFSITE"))
                .note(underUrlEncodingNow
                        + " This is the row the finding was easiest to see in: plain href was"
                        + " protected by url() and this was not, so the safe-by-analogy assumption a"
                        + " developer would make was wrong. The two names are one classification"
                        + " now, which AttributeNameMatrixTest.hrefAndXlinkHrefReachTheSameEncoder"
                        + " asserts as an equality rather than as two expectations."
                        + " " + DEAD_URL_VECTORS + " " + VIEW_SOURCE_IS_BLOCKED_FROM_CONTENT)
                .browserRelevant()
                .build());

        // The tail: one payload per mechanism. Several of these attributes are legacy and no longer
        // honoured by any shipping browser, which does not change the ledger - the ledger is about
        // whether attacker data reaches the sink live, not about whether a 2026 engine acts on it.
        String legacy = "A legacy sink no current browser is known to fetch. That does not change"
                + " the verdict under the plan's definition, which is that attacker data reaches"
                + " the sink live; where a dead vector sits in a browser-relevant case it is"
                + " flagged not-browser-observable rather than having its verdict rewritten. None"
                + " of these cases is browser-relevant, so nothing is asked of the browser tier"
                + " here.";

        cases.add(urlAttributeAddedByR6("url.cite",
                "<blockquote cite=\"$data\">x</blockquote>", "blockquote", "cite")
                .note(underUrlEncodingNow).build());

        cases.add(urlAttributeAddedByR6("url.ping",
                "<a ping=\"$data\" href=\"/x\">y</a>", "a", "ping")
                .note("ping fires a POST to the named URL on click, with no user-visible effect -"
                        + " the quietest exfiltration channel in this group, and one R6's routing"
                        + " does nothing about: an off-origin ping is exactly the payload url()"
                        + " passes through. " + underUrlEncodingNow)
                .build());

        cases.add(urlAttributeSuppressedByR5("url.imagesrcset",
                "<link rel=\"preload\" as=\"image\" imagesrcset=\"$data\">", "link", "imagesrcset")
                .note(suppressedInstead
                        + " The one name in this half that a modern template might plausibly write,"
                        + " and the reason it is here rather than on the URL list is srcset's own:"
                        + " it is a candidate list, url() cannot encode one, and preload hints are"
                        + " not usually built from data.")
                .build());

        cases.add(urlAttributeSuppressedByR5("url.xml-base",
                "<svg xml:base=\"$data\"><text>x</text></svg>", "svg", "xml:base")
                .note("The SVG analogue of <base href>: it rebases every relative URL in the"
                        + " subtree, which is the widest blast radius of anything in this group."
                        + " That is also why it is on the suppressed side - routing it to url()"
                        + " would leave F6 open on an attribute that retargets a whole subtree."
                        + " " + suppressedInstead)
                .build());

        cases.add(urlAttributeAddedByR6("url.usemap",
                "<img usemap=\"$data\" src=\"/i.png\">", "img", "usemap")
                .note(legacy + " " + underUrlEncodingNow).build());

        cases.add(urlAttributeAddedByR6("url.longdesc",
                "<img longdesc=\"$data\" src=\"/i.png\">", "img", "longdesc")
                .note(legacy + " Also worth noting that longdesc used to fail 'lowsrc' at"
                        + " buf[2]=='n', which was the near-miss shape that made the hand-unrolled"
                        + " table hard to audit; the classification is a set lookup now."
                        + " " + underUrlEncodingNow)
                .build());

        cases.add(urlAttributeAddedByR6("url.codebase",
                "<applet codebase=\"$data\"></applet>", "applet", "codebase")
                .note(legacy + " " + underUrlEncodingNow).build());

        cases.add(urlAttributeSuppressedByR5("url.archive",
                "<object archive=\"$data\"></object>", "object", "archive")
                .note(legacy + " " + suppressedInstead).build());

        cases.add(urlAttributeSuppressedByR5("url.classid",
                "<object classid=\"$data\"></object>", "object", "classid")
                .note(legacy + " " + suppressedInstead).build());

        cases.add(urlAttributeAddedByR6("url.manifest",
                "<html manifest=\"$data\"><body>x</body></html>", "html", "manifest")
                .note(legacy + " " + underUrlEncodingNow).build());

        cases.add(urlAttributeSuppressedByR5("url.profile",
                "<html><head profile=\"$data\"></head><body>x</body></html>", "head", "profile")
                .note(legacy + " " + suppressedInstead).build());
    }

    /**
     * The two sinks that are neither plain text nor a URL: an attribute parsed as HTML in its own
     * right, and one that carries a delay and a URL in a single value.
     */
    private static void markupAndRefreshSinks(List<XssCase> cases) {

        String srcdocIsSuppressed =
                "Re-verdicted by R6, from KNOWN_VULNERABLE. srcdoc's value is parsed as a whole HTML"
                        + " document, so the correct encoding is a second full HTML encode and the"
                        + " single encode Canoe applied was same-origin script execution: the HTML"
                        + " parser decoded the references while building the attribute value, and"
                        + " the iframe's parser was handed the attacker's raw markup. R6 suppresses"
                        + " rather than encoding, and the choice is the honest one until somebody"
                        + " wants to build double encoding deliberately - which is a feature to"
                        + " design, and which section 6 of the remediation plan records as out of"
                        + " scope. Reviewed against the sink: the attribute renders as the"
                        + " template's own text with nothing between the tags, byte-identical to a"
                        + " render with an empty value, so no attacker character reaches the iframe"
                        + " document. SUPPRESSED_BY_DESIGN rather than SUPPRESSED_UNINTENDED: the"
                        + " value is dropped because dropping it is the decision, not because"
                        + " nobody classified the name. The finding stays cited so the row remains"
                        + " traceable to F3.";

        cases.add(XssCase.id("markup.srcdoc")
                .section(A2)
                .template("<iframe srcdoc=\"<p>$data</p>\"></iframe>")
                .sink(SinkKind.MARKUP, "iframe", "srcdoc")
                .payloads(Payloads.family("SRCDOC_MARKUP"))
                .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                .finding("F3")
                .note(srcdocIsSuppressed)
                .browserRelevant()
                .build());

        cases.add(XssCase.id("markup.srcdoc-whole-value")
                .section(A2)
                .template("<iframe srcdoc=\"$data\"></iframe>")
                .sink(SinkKind.MARKUP, "iframe", "srcdoc")
                .payloads(Payloads.families("SRCDOC_MARKUP", "TAG_BREAKOUT"))
                .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                .finding("F3")
                .note("The whole iframe document, rather than a fragment inside one. Included"
                        + " because the template shape a developer reaches for first is"
                        + " srcdoc=\"$html\", not srcdoc=\"<p>$name</p>\" - and it is also the shape"
                        + " where the suppression costs the most, since the whole document is the"
                        + " value. " + srcdocIsSuppressed)
                .browserRelevant()
                .build());

        // F3's content row, and the other half of F7: there was no check for 'content' at all,
        // because the branch that should have held it tested for 'data'. R7 resolved the pair.
        cases.add(XssCase.id("refresh.meta-content")
                .section(A2)
                .template("<meta http-equiv=\"refresh\" content=\"$data\">")
                .sink(SinkKind.REFRESH, "meta", "content")
                .payloads(Payloads.family("META_REFRESH"))
                .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                .finding("F3")
                .note("Re-verdicted by R5+R7, from KNOWN_VULNERABLE; R10 confirmed the suppression"
                        + " deliberately. A forced top-level navigation to an attacker origin, needing"
                        + " no click and no script, reached through an attribute Canoe had no branch"
                        + " for at all - the second consequence of F7's copy-paste, with F3's impact."
                        + " 'content' is off the URL list deliberately: it carries a URL on exactly one"
                        + " element and attribute-value combination, <meta http-equiv=refresh"
                        + " content='N; url=...'>. R10 weighed giving that one combination a URL"
                        + " context and chose suppression instead, because recognising it needs the"
                        + " sibling http-equiv='refresh' value (which Canoe never retains, and which"
                        + " may be scanned after content) and parsing the 'N; url=' prefix out of the"
                        + " value (which the per-reference encoding model cannot do - the reference is"
                        + " opaque). Routing every content to url() would percent-encode ordinary prose"
                        + " in every meta description on the page. Reviewed against the sink: the"
                        + " attribute renders empty, so there is no refresh target for the browser to"
                        + " navigate to - the template's own meta element remains and does nothing."
                        + " SUPPRESSED_BY_DESIGN because suppression is the deliberate final decision,"
                        + " not a placeholder; the finding stays cited so the row is traceable to F3.")
                .browserRelevant()
                .build());
    }

    /**
     * The policy-bearing names: attributes whose decoded value the browser acts on as a switch that
     * turns a <em>security control</em> on or off, rather than handing it to a second parser.
     * Recorded as F20.
     *
     * <p>This is the group {@code html()} can do least about. Policy tokens are letters, digits,
     * hyphens, underscores and spaces; {@code html()} passes the letters and digits naked and turns
     * the rest into character references the parser puts straight back. The value arrives byte for
     * byte, every time, and no change to the encoder can alter that — only recognising the name and
     * suppressing can, which is remediation item 3 in the review.
     *
     * <p>Four names, not the six F20's table lists. {@code target}, {@code formtarget} and
     * {@code type} were considered and rejected against the criteria in {@link SinkKind#POLICY}; they
     * are {@code plain.*} cases now, each with the reasoning kept rather than deleted, and R5 made
     * the same three decisions the same way — they are on the plain-text allowlist and the other
     * four are not.
     * {@code nonce} was moved <em>in</em>, from the plain-text group — see {@link #policyNonce}.
     */
    private static void policyAttributes(List<XssCase> cases) {

        cases.add(policyAttribute("policy.sandbox",
                "<iframe sandbox=\"$data\" src=\"/user-content\"></iframe>", "iframe", "sandbox")
                .note("The one row in this group that had a Critical-class outcome: allow-scripts"
                        + " plus allow-same-origin removes the sandbox entirely, and the framed"
                        + " document is then same-origin script execution. A template that derives"
                        + " the sandbox level from data - a permissions setting, a plan tier, a"
                        + " preview mode - was handing the attacker the sandbox. The other two"
                        + " payloads arrived just as verbatim and were SAFE, because 'opener' and"
                        + " '_blank' are not sandbox tokens: an unrecognised token leaves the"
                        + " sandbox maximally restrictive, which is the opposite of an escape."
                        + " Recording those as vulnerabilities because the bytes survived is the"
                        + " mistake a byte-counting oracle makes, and it is worth keeping now that"
                        + " all three rows read alike for a different reason. "
                        + A_DIRECTIVE_CANNOT_BE_ENCODED)
                .browserRelevant()
                .build());

        cases.add(policyAttribute("policy.rel",
                "<a rel=\"$data\" target=\"_blank\" href=\"/x\">y</a>", "a", "rel")
                .note("rel=opener undoes the implicit noopener that target=_blank carries, which"
                        + " restores window.opener and with it reverse tabnabbing - and it was the"
                        + " only reason rel was in this group at all, since most link types are"
                        + " behavioural. The other two payloads are not link types, so they were"
                        + " ignored: the link relation list is an allowlist, and an unknown relation"
                        + " is dropped rather than honoured. Note the asymmetry the group settles:"
                        + " rel is suppressed and its sibling target is on the plain-text allowlist,"
                        + " because target names a browsing context and rel is what would undo that"
                        + " context's implicit noopener. " + A_DIRECTIVE_CANNOT_BE_ENCODED)
                .build());

        cases.add(policyAttribute("policy.integrity",
                "<script src=\"/app.js\" integrity=\"$data\"></script>", "script", "integrity")
                .note("Subresource integrity is a security control the template author added"
                        + " deliberately, and an attacker who controls it can set a wrong digest and"
                        + " block the script. None of these three payloads did that, which was the"
                        + " row worth reading twice: SRI parses the attribute into a set of"
                        + " <algorithm>-<base64> expressions and discards what it cannot parse, and"
                        + " an EMPTY metadata set makes the check pass unconditionally. An"
                        + " unparseable integrity attribute is not a failing digest, it is no digest,"
                        + " so the resource loaded exactly as it would have. All three arrived"
                        + " verbatim and none of them was live - which is precisely why the fix had"
                        + " to be the name rather than the payload set: a sha256-<junk> payload"
                        + " would have flipped the case, and no encoder would have stopped it."
                        + " " + A_DIRECTIVE_CANNOT_BE_ENCODED)
                .build());

        policyNonce(cases);
    }

    /**
     * The CSP nonce, promoted out of the plain-text group.
     *
     * <p>It was {@code plain.nonce}/{@code SAFE}, on the argument that the value cannot break out of
     * the attribute — which is true and is not the question. A nonce is a directive the HTML parser
     * hands straight to the content security policy, made of letters, digits and {@code +/=}, every
     * one of which arrives byte for byte. An attacker who chooses it can then author a
     * {@code <script nonce="...">} the policy admits, which defeats a real security control. That is
     * strictly stronger than {@code target}, which used to be ledgered here as
     * {@code KNOWN_VULNERABLE}/{@code POLICY} while {@code nonce} sat two groups away as SAFE.
     *
     * <p>The boundary this settles is worth stating, because two neighbouring cases looked like
     * counterexamples. {@code clobber.id} makes F20's argument word for word — "the legal values are
     * exactly the dangerous ones; only refusing to interpolate can help" — and is still SAFE, because
     * an {@code id} is a name in the document's own namespace and no browser algorithm treats it as a
     * directive; what it endangers is other scripts on the page. {@code plain.type} arrives just as
     * verbatim and is SAFE because its only attacker-reachable effect is to disable a script. The
     * criteria are written out on {@link SinkKind#POLICY} so the three verdicts can be checked
     * against each other rather than taken on trust.
     *
     * <p>And the practical consequence: remediation item 3's allowlist listed {@code nonce} among the
     * plain-text names. Implementing the review exactly as written would have left {@code nonce} on
     * {@code html()} — the outcome F20 exists to prevent. That entry has been removed from the review.
     */
    private static void policyNonce(List<XssCase> cases) {
        cases.add(policyAttribute("policy.nonce",
                "<script nonce=\"$data\" src=\"/app.js\"></script>", "script", "nonce")
                .note(A_DIRECTIVE_CANNOT_BE_ENCODED
                        + " This row is the one the review's own remediation sketch would have got"
                        + " wrong: it listed nonce among the plain-text names, on the argument that"
                        + " the value cannot break out of the attribute - which is true, and is the"
                        + " wrong test. Implementing R5 as written would have left F20's worst row"
                        + " on html(). Canoe's PLAIN_TEXT_ATTRIBUTE_NAMES javadoc records the"
                        + " correction, and NAMES_THAT_MAY_NOT_BE_ADDED refuses the name from"
                        + " configuration as well."
                        + " The original reasoning, kept because it is what decided the verdict"
                        + " while the row was live: unlike every other attribute in this group,"
                        + " nonce has no token vocabulary at all -"
                        + " the whole value is the directive, so every payload that arrives is live"
                        + " by construction and there is no inert combination to record. The"
                        + " POLICY_OVERRIDE payloads are used rather than nonce-shaped strings"
                        + " because the point is verbatim arrival, and their character set - letters,"
                        + " digits, hyphens, underscores, a space - is the same set a base64 nonce"
                        + " draws from. Canoe's own part still holds: the value cannot break out of"
                        + " the attribute. It does not have to."
                        + " All three payloads used to be flagged not-browser-observable, and the"
                        + " reason was structural rather than a dead engine: a nonce does nothing"
                        + " at all unless"
                        + " the response carries a Content-Security-Policy naming one, and this"
                        + " template has no author nonce for a policy to name. The browser tier"
                        + " serves what Canoe rendered; adding a CSP header would be the tier"
                        + " editing the document under test, and a header naming the ATTACKER's"
                        + " nonce would be assuming the conclusion. Demonstrating F20's nonce row in"
                        + " a browser needs a different template - an author nonce in the policy and"
                        + " a second, attacker-controlled script element - which the corpus does not"
                        + " have. Recorded here rather than left as a browser-tier failure nobody"
                        + " could act on. Measured in Chromium by BrowserCorpusTest. The flag is"
                        + " gone with the KNOWN_VULNERABLE verdict it qualified - a suppressed row"
                        + " expects browser silence anyway, and the corpus only permits the flag"
                        + " where it changes an expectation - and R28 still owns building the"
                        + " template that would demonstrate the finding in a browser, which is worth"
                        + " doing even now: it is the one row in the review with no browser evidence"
                        + " either before or after the fix.")
                .browserRelevant()
                .build());
    }

    /**
     * Case and separator permutations of the attribute name itself. These are the cases where Canoe
     * mostly works, and they earn their place by pinning that: the name scan lowercases as it
     * buffers, so the recognised set is genuinely case-insensitive, and {@code TAG_ATTR_NAME_AFTER}
     * skips any run of whitespace before the {@code =}.
     */
    private static void attributeNameSyntax(List<XssCase> cases) {

        cases.add(recognisedUriAttribute("name.href-uppercase",
                "<a HREF=\"$data\">x</a>", "a", "href")
                .note("TAG_ATTR_NAME does buf[bufLen++] = Character.toLowerCase(c), so the whole"
                        + " recognised set is case-insensitive. Verdicts identical to url.href-full.")
                .build());

        cases.add(recognisedUriAttribute("name.href-mixed-case",
                "<a HrEf=\"$data\">x</a>", "a", "href").build());

        cases.add(XssCase.id("name.onclick-uppercase")
                .section(A2)
                .template("<a ONCLICK=\"f('$data')\">x</a>")
                .sink(SinkKind.JAVASCRIPT, "a", "onclick")
                .payloads(Payloads.family("QUOTE_BREAKOUT"))
                .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                .note("The on* table is case-insensitive for the same reason. Note this says nothing"
                        + " about F1 and F19: ONSUBMIT and ONREADYSTATECHANGE are injectable in every"
                        + " casing, because the bug is in the indices, not in the letters.")
                .build());

        cases.add(recognisedUriAttribute("separator.space-before-equals",
                "<a href =\"$data\">x</a>", "a", "href")
                .note("TAG_ATTR_NAME_AFTER skips whitespace before the '='. All four separator"
                        + " permutations resolve to the same ATTR_URI classification.")
                .build());

        cases.add(recognisedUriAttribute("separator.tab-before-equals",
                "<a href\t=\"$data\">x</a>", "a", "href").build());

        cases.add(recognisedUriAttribute("separator.newline-before-equals",
                "<a href\n=\"$data\">x</a>", "a", "href").build());

        cases.add(recognisedUriAttribute("separator.crlf-before-equals",
                "<a href\r\n=\"$data\">x</a>", "a", "href").build());

        // The one case in this group where the first hand verdict was wrong. It was written as a
        // copy of url.href-full - same attribute, same classification, therefore same verdict - and
        // the evaluator disagreed, because the sink is not the second href at all: the HTML parser
        // keeps the FIRST occurrence of a duplicate attribute and discards every later one, so the
        // attacker's value never reaches a URL parser. The evaluator was right and the copy was
        // wrong, which is the exact failure mode ledgerMatchesObservedBehaviour exists to catch.
        XssCase.Builder duplicateAttribute = XssCase.id("separator.duplicate-attribute")
                .section(A2)
                .template("<a href=\"/safe\" href=\"$data\">x</a>")
                .sink(SinkKind.URL, "a", "href")
                .payloads(allUrlPayloads())
                .verdict(Verdict.SAFE)
                .note("Canoe classifies each occurrence independently and has no notion of a"
                        + " duplicate, so the second href gets ATTR_URI just like the first and the"
                        + " emitted bytes are byte-identical to url.href-full's. What makes this SAFE"
                        + " is the parser, not the encoder: the duplicate is dropped before any URL"
                        + " is resolved, so even the uppercase off-origin URL stays SAFE here while"
                        + " it is KNOWN_VULNERABLE in url.href-full. Swap the two attributes and every"
                        + " verdict flips to url.href-full's - which is why the encoding is worth"
                        + " recording even though today's outcome is safe. The rejected-scheme"
                        + " payloads suppress under R12 for the ordinary reason and would suppress"
                        + " whichever href they landed in.");
        applyUrlSchemeReverdict(duplicateAttribute, allUrlPayloads(), false);
        cases.add(duplicateAttribute.build());

        // ...and the ordering the note above describes only in prose, as a case. This is the
        // dangerous half of the pair: the parser keeps the FIRST occurrence, so here the attacker's
        // value is the one that survives and the template author's /safe is the one discarded.
        // Canoe's output is the same shape either way; only the order decides.
        XssCase.Builder duplicateAttributeReversed = XssCase.id("separator.duplicate-attribute-reversed")
                .section(A2)
                .template("<a href=\"$data\" href=\"/safe\">x</a>")
                .sink(SinkKind.URL, "a", "href")
                .payloads(allUrlPayloads())
                .verdict(Verdict.SAFE)
                .finding("F6")
                .override(Payloads.PROTOCOL_RELATIVE, Verdict.KNOWN_VULNERABLE)
                .override(Payloads.ABSOLUTE_OFFSITE_HTTPS, Verdict.KNOWN_VULNERABLE)
                .note("Every verdict here is url.href-full's, and separator.duplicate-attribute's are"
                        + " all SAFE, from the same Canoe output. The difference is entirely the"
                        + " parser's duplicate-attribute rule, and a template author who writes a"
                        + " fallback after a dynamic value rather than before it has written the"
                        + " vulnerable one. Worth a case rather than a sentence, because the safe"
                        + " ordering being SAFE is the kind of result that gets generalised.");
        applyUrlSchemeReverdict(duplicateAttributeReversed, allUrlPayloads(), true);
        cases.add(duplicateAttributeReversed.build());

        cases.add(XssCase.id("separator.valueless-attribute-then-value")
                .section(A2)
                .template("<input disabled value=\"$data\">")
                .sink(SinkKind.PLAIN_TEXT_ATTR, "input", "value")
                .payloads(plainTextProbe())
                .verdict(Verdict.SAFE)
                .note("TAG_ATTR_NAME_AFTER starts a new attribute name on any name character, so a"
                        + " boolean attribute does not desynchronise the classification of the next"
                        + " one.")
                .build());

        cases.add(rejected("separator.attribute-after-slash",
                "<img src=\"a.png\"/ alt=\"$data\">")
                .section(A2)
                .note("TAG_EMPTY_ENDING demands '>' immediately: 'Expected '>' after '/' in tag.'."
                        + " An XHTML-style self-closing tag with a trailing attribute takes the page"
                        + " down. This was the same defect as the <br/> row in F13's table,"
                        + " reached from the attribute side; R20 fixed that row and left this one,"
                        + " because the two are not the same shape after all. A '/' that ends a tag"
                        + " name is a self-closing start tag and is what R20 accepts; a '/' followed"
                        + " by another ATTRIBUTE is the HTML Standard's unexpected-solidus-in-tag"
                        + " parse error, which a browser recovers from by ignoring the solidus."
                        + " Accepting it is a separate decision, on a shape no serializer emits, and"
                        + " R20's package did not include it.")
                .build());
    }

    // ------------------------------------------------------------------
    // A.4 Attribute value prefixes
    // ------------------------------------------------------------------

    /**
     * The CSS half of F4, <strong>closed by R2</strong>.
     *
     * <p>{@code detectAttributePrefix()} used to reset {@code attributeContext} to {@code ATTR_HTML}
     * unconditionally on the first colon at value index 0–10, and colons are the basic syntax of a
     * CSS declaration — so writing a property name in front of the reference silently converted
     * {@code style} from "suppress" to "HTML-encode". R2 deleted that reset, so the method can now
     * only narrow the context and the name-derived {@code ATTR_CSS} survives whatever the value
     * contains.
     *
     * <p>The colon position used to decide the outcome, and the boundary was index 10 inclusive,
     * because {@code c == ':'} is tested before the {@code bufLen == 10} cutoff. That boundary still
     * exists in the parser — the colon still fires the scan and still sets {@code bufLen = -1} — but
     * it no longer has a consequence, and every case below now records the same outcome as the ones
     * that always sat on the safe side of it. The group is kept, verdicts flipped rather than rows
     * deleted, because it is the regression net for F4: if any one of these ever stops being
     * suppressed, the reset (or something with its shape) is back.
     *
     * <p>That every {@code style} value stays suppressed is also the settled R14 decision (F21), not a
     * pending state: R14 kept suppressing and deleted the dead {@code CTX_CSS} arm rather than route
     * {@code ATTR_CSS} to a CSS encoder. F23 shows a {@code style} value is decoded in series, so a
     * correct CSS encoder is a project, not a line; R13 corrected {@code css()} as its precondition.
     * These rows would need re-verdicting only if that project is ever undertaken.
     */
    private static void cssContexts(List<XssCase> cases) {

        cases.add(XssCase.id("css.style-with-property")
                .section(A4)
                .template("<div style=\"color:$data\">x</div>")
                .sink(SinkKind.CSS, "div", "style")
                .payloads(Payloads.family("CSS_INJECTION"))
                .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                .finding("F4")
                .note("color: puts the colon at index 5, which used to be a complete defeat of the"
                        + " refuse-to-output-into-CSS guarantee the original design documents call"
                        + " Canoe's centrepiece. R2: the colon still calls detectAttributePrefix(),"
                        + " none of the five value prefixes matches 'color', and the method no"
                        + " longer assigns anything when nothing matches - so the name-derived"
                        + " ATTR_CSS stands and CTX_SUPPRESS emits the empty string. Reviewed"
                        + " against the sink: the rendered style attribute is exactly 'color:' for"
                        + " all three payloads, byte-identical to a render with an empty value, so"
                        + " the CSS parser receives no attacker character at all.")
                .browserRelevant()
                .build());

        cases.add(XssCase.id("css.style-bare")
                .section(A4)
                .template("<div style=\"$data\">x</div>")
                .sink(SinkKind.CSS, "div", "style")
                .payloads(Payloads.family("CSS_INJECTION"))
                .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                .note("No preceding colon, so the name-derived ATTR_CSS survives and output is empty."
                        + " Before R2 this was the only row in the group that could say that; it is"
                        + " now what every row says, which is the whole of the fix in one sentence.")
                .build());

        cases.add(XssCase.id("css.style-width")
                .section(A4)
                .template("<div style=\"width:$data\">x</div>")
                .sink(SinkKind.CSS, "div", "style")
                .payloads(Payloads.family("CSS_INJECTION"))
                .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                .finding("F4")
                .note("Colon at index 5. R2: no prefix matches 'width', the context is left alone,"
                        + " and the sink is 'width:' with nothing after it.")
                .build());

        cases.add(XssCase.id("css.style-margin")
                .section(A4)
                .template("<div style=\"margin:$data\">x</div>")
                .sink(SinkKind.CSS, "div", "style")
                .payloads(Payloads.family("CSS_INJECTION"))
                .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                .finding("F4")
                .note("Colon at index 6. R2: sink is 'margin:' with nothing after it.")
                .build());

        cases.add(XssCase.id("css.style-display")
                .section(A4)
                .template("<div style=\"display:$data\">x</div>")
                .sink(SinkKind.CSS, "div", "style")
                .payloads(Payloads.family("CSS_INJECTION"))
                .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                .finding("F4")
                .note("Colon at index 7, the same index as padding: - which is the point of having"
                        + " both. F4's precondition was a character count, not a property, so two"
                        + " properties that shared an index had to agree or the model was wrong."
                        + " R2 makes every index agree; the pair is kept so that a future change"
                        + " that reintroduces a positional dependence fails on both rows at once.")
                .build());

        cases.add(XssCase.id("css.style-position")
                .section(A4)
                .template("<div style=\"position:$data\">x</div>")
                .sink(SinkKind.CSS, "div", "style")
                .payloads(Payloads.family("CSS_INJECTION"))
                .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                .finding("F4")
                .note("Colon at index 8. R2: sink is 'position:' with nothing after it.")
                .build());

        cases.add(XssCase.id("css.style-padding")
                .section(A4)
                .template("<div style=\"padding:$data\">x</div>")
                .sink(SinkKind.CSS, "div", "style")
                .payloads(Payloads.family("CSS_INJECTION"))
                .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                .finding("F4")
                .note("Colon at index 7. R2: sink is 'padding:' with nothing after it.")
                .build());

        cases.add(XssCase.id("css.style-font-size")
                .section(A4)
                .template("<div style=\"font-size:$data\">x</div>")
                .sink(SinkKind.CSS, "div", "style")
                .payloads(Payloads.family("CSS_INJECTION"))
                .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                .finding("F4")
                .note("Colon at index 9. R2: sink is 'font-size:' with nothing after it.")
                .build());

        // The boundary itself. Before R2 this was the last index on the vulnerable side; it is now
        // the row that shows the boundary has stopped meaning anything.
        cases.add(XssCase.id("css.style-background")
                .section(A4)
                .template("<div style=\"background:$data\">x</div>")
                .sink(SinkKind.CSS, "div", "style")
                .payloads(Payloads.family("CSS_INJECTION"))
                .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                .finding("F4")
                .note("Colon at index 10 - the last position that still reaches"
                        + " detectAttributePrefix(), because c == ':' is evaluated before the"
                        + " bufLen == 10 cutoff. The review corrected itself on this exact case and"
                        + " concluded it was affected, which it was. R2: the scan still runs here"
                        + " and still matches nothing, and matching nothing is now a no-op, so this"
                        + " row and css.style-font-family-quoted - the two sides of the old boundary"
                        + " - render identically. Reviewed against the sink: 'background:' with"
                        + " nothing after it, for all three payloads, so no request is issued.")
                .browserRelevant()
                .build());

        // ...and the row that was always on the safe side, which the boundary used to separate from
        // the one above.
        cases.add(XssCase.id("css.style-font-family-quoted")
                .section(A4)
                .template("<div style=\"font-family:'$data'\">x</div>")
                .sink(SinkKind.CSS, "div", "style")
                .payloads(Payloads.family("CSS_INJECTION"))
                .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                .note("font-family is eleven characters, so the colon sits at index 11 and the scan"
                        + " has already given up (bufLen was set to -1 at index 10). ATTR_CSS"
                        + " survives and the value is suppressed. One character used to decide it;"
                        + " after R2 the eleventh character decides nothing, because the scan that"
                        + " does run on the other side of the boundary can no longer widen anything.")
                .build());

        cases.add(XssCase.id("css.style-text-decoration")
                .section(A4)
                .template("<div style=\"text-decoration:$data\">x</div>")
                .sink(SinkKind.CSS, "div", "style")
                .payloads(Payloads.family("CSS_INJECTION"))
                .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                .note("Colon at index 15. The safe side of the boundary, and the reason the trigger"
                        + " is described as common but not universal.")
                .build());

        // Only the FIRST colon is ever examined. That is still true of the parser; what changed is
        // that examining it can no longer widen the context.
        cases.add(XssCase.id("css.style-after-a-complete-declaration")
                .section(A4)
                .template("<div style=\"color:red;background:$data\">x</div>")
                .sink(SinkKind.CSS, "div", "style")
                .payloads(Payloads.family("CSS_INJECTION"))
                .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                .finding("F4")
                .note("detectAttributePrefix() runs once, on the first colon, and sets bufLen to -1"
                        + " so nothing later in the value is examined. The reference's own position"
                        + " in the value is irrelevant; only the first colon's is. R2: that one"
                        + " examination matches no prefix and therefore changes nothing, so a"
                        + " reference after a complete declaration is suppressed exactly like one"
                        + " after none. Reviewed against the sink: 'color:red;background:'.")
                .build());

        // Inside a quoted CSS string, which is the shape a template author reaches for when they
        // think quoting will contain the value. It does not: html() turns the apostrophe into &#39;
        // and the HTML parser gives it back to the CSS parser as a real quote.
        cases.add(XssCase.id("css.style-inside-a-quoted-css-string")
                .section(A4)
                .template("<div style=\"content:'$data'\">x</div>")
                .sink(SinkKind.CSS, "div", "style")
                .payloads(Payloads.family("CSS_INJECTION"))
                .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                .finding("F4")
                .note("Colon at index 7, so the reset used to fire and html() used to apply. The CSS"
                        + " string literal around the reference was never a mitigation - it was the"
                        + " same situation as a JavaScript string literal in an event handler, and"
                        + " F1's whole mechanism - and it is not what makes this row safe now"
                        + " either. R2 is: no prefix matches 'content', so ATTR_CSS survives and the"
                        + " sink is \"content:''\", an empty CSS string. Contrast"
                        + " css.style-font-family-quoted, which used to be the only one of the pair"
                        + " that suppressed; both do now, and the character count that used to"
                        + " decide between them decides nothing.")
                .browserRelevant()
                .build());

        cases.add(XssCase.id("css.media-query-in-a-style-body")
                .section(A4)
                .template("<style>@media screen{p{color:$data}}</style>")
                .sink(SinkKind.CSS, "style", null)
                .payloads(Payloads.families("CSS_INJECTION", "CSS_IMPORT"))
                .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                .note("A colon inside a <style> element body is just a character: the CSS state has"
                        + " no attribute-value prefix scan, so detectAttributePrefix() never runs and"
                        + " CTX_SUPPRESS holds however deeply nested the reference is. That is the"
                        + " asymmetry T17 exists to state - 'color:' suppresses in a <style> body"
                        + " and is injectable in a style attribute, and the two look identical to a"
                        + " template author.")
                .build());

        cases.add(XssCase.id("css.style-inside-url-function")
                .section(A4)
                .template("<div style=\"background:url($data)\">x</div>")
                .sink(SinkKind.CSS, "div", "style")
                .payloads(Payloads.families("CSS_INJECTION", "PROTOCOL_RELATIVE"))
                .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                .finding("F4")
                .note("This was F4's concrete impact in one template: an attacker-chosen URL inside"
                        + " a CSS url() is a request to their origin on every render, which is how"
                        + " CSS exfiltration of DOM content is bootstrapped, and"
                        + " PROTOCOL_RELATIVE/slashes was the row that demonstrated it in a real"
                        + " browser. R2: 'background' matches none of the five value prefixes, the"
                        + " name-derived ATTR_CSS stands, and the sink is 'background:url()' for all"
                        + " six payloads - an empty url() token, so nothing is fetched from any"
                        + " origin. Reviewed against the sink rather than inferred: the rendered"
                        + " attribute is byte-identical to a render with an empty value."
                        + " The two browser observations this row used to carry are worth keeping as"
                        + " history, because both are about the CSS tokenizer rather than about"
                        + " Canoe and both would return the moment anything re-enabled output here."
                        + " " + CSS_BACKSLASH_IS_AN_ESCAPE + " " + EXPRESSION_IS_DEAD
                        + " " + THE_CSS_CONTAINER_DECIDES)
                .browserRelevant()
                .build());
    }

    /**
     * The value prefixes {@code detectAttributePrefix()} knows, and the ones it does not.
     *
     * <p>{@code AttributePrefixTest} (T10) covers the mechanics at unit level — every prefix, every
     * near miss, the colon at each index from 0 to 12. What is here is the end-to-end subset: the
     * cases where a whole rendered template, with a payload in it, is the clearest statement of the
     * behaviour.
     */
    private static void attributeValuePrefixes(List<XssCase> cases) {

        // F17, closed by R2. The colon-triggered reset did not only widen ATTR_CSS and ATTR_URI; it
        // widened ATTR_JS, which is the one classification Canoe gets right. onclick is a recognised
        // handler, it resolves to ATTR_JS -> CTX_JS -> the empty string, and a colon anywhere in the
        // first eleven characters of the value used to throw that answer away and hand the value to
        // html(), which the HTML parser undoes before the JavaScript parser runs.
        //
        // The group is still deliberately three cases: two shapes that used to fire and one that
        // never did, differing only in how many characters precede the colon. The boundary was
        // positional rather than semantic, so it could not be reasoned about from what the handler
        // DOES, only measured - and keeping all three is what measures that the positional
        // dependence is now gone rather than moved.
        cases.add(XssCase.id("prefix.colon-in-a-recognised-handler")
                .section(A4)
                .template("<a onclick=\"f({a:1,b:'$data'})\">x</a>")
                .sink(SinkKind.JAVASCRIPT, "a", "onclick")
                .payloads(Payloads.family("QUOTE_BREAKOUT"))
                .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                .finding("F17")
                .note("An object literal in a handler body puts the colon at value index 4, inside"
                        + " the 0-10 window, so detectAttributePrefix() ran - and used to reset"
                        + " attributeContext to ATTR_HTML, match none of its five prefixes, and"
                        + " leave the value to html(). Decoded, the handler used to read"
                        + " f({a:1,b:'');__canoePwned('q');//'}). R2: the scan still runs on that"
                        + " colon and still matches nothing, and matching nothing no longer assigns"
                        + " anything, so the name-derived ATTR_JS stands and CTX_JS emits the empty"
                        + " string. Reviewed against the sink: the rendered handler is"
                        + " f({a:1,b:''}) for both payloads, byte-identical to a render with an"
                        + " empty value, so the JavaScript parser is handed one empty string"
                        + " literal. This row is now identical in outcome to handler.onclick, which"
                        + " is the SAME attribute with a colon-free body - and that is the point:"
                        + " spot-checking onclick used to conclude the mechanism worked, and now it"
                        + " genuinely does. Note also that R4's on* prefix rule would not have"
                        + " closed this: the name was already classified correctly and the value"
                        + " scan discarded the answer afterwards, which is why R2 leads the phase."
                        + " SinkSpecificBrowserTest.f17IsExploitableWithAPayloadShapedForItsPosition"
                        + " used to run the position-shaped payload '});__canoePwned('f17');// in a"
                        + " real browser; it is now the inverted test that requires that payload to"
                        + " be suppressed too, because the shared QUOTE_BREAKOUT payloads alone"
                        + " could never have shown F17 executing.")
                .browserRelevant()
                .build());

        cases.add(XssCase.id("prefix.url-literal-in-a-recognised-handler")
                .section(A4)
                .template("<a onclick=\"go('http://x'+'$data')\">x</a>")
                .sink(SinkKind.JAVASCRIPT, "a", "onclick")
                .payloads(Payloads.family("QUOTE_BREAKOUT"))
                .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                .finding("F17")
                .note("The second F17 shape, and the one that showed what kind of boundary this was:"
                        + " a URL literal in the handler puts the colon of http: at index 8. Renaming"
                        + " the function from go() to open() - two characters longer - moved the"
                        + " colon to index 11, the scan had already given up, and the identical"
                        + " handler was suppressed, with nothing about what the code does having"
                        + " changed. Decoded, this one used to read"
                        + " go('http://x'+'');__canoePwned('q');//'). R2: reviewed against the sink,"
                        + " it now reads go('http://x'+'') for both payloads - the value is"
                        + " suppressed whatever the function is called.")
                .browserRelevant()
                .build());

        cases.add(XssCase.id("prefix.colon-past-the-handler-window")
                .section(A4)
                .template("<a onclick=\"$.ajax({url:'/a',t:'$data'})\">x</a>")
                .sink(SinkKind.JAVASCRIPT, "a", "onclick")
                .payloads(Payloads.family("QUOTE_BREAKOUT"))
                .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                .note("The negative control for the two above, and the reason they are worth three"
                        + " cases rather than one. The first colon here sits at index 11, one past"
                        + " the window, because the jQuery idiom spells six characters before the"
                        + " brace where the object-literal shape spells three. bufLen was set to -1"
                        + " at index 10, detectAttributePrefix() never runs, the name-derived ATTR_JS"
                        + " survives, and Canoe does exactly what it was designed to do. A reader who"
                        + " sees only this case concludes the handler suppression works.")
                .build());

        cases.add(XssCase.id("prefix.javascript-exact")
                .section(A4)
                .template("<a href=\"javascript:f('$data')\">x</a>")
                .sink(SinkKind.JAVASCRIPT, "a", "href")
                .payloads(Payloads.family("QUOTE_BREAKOUT"))
                .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                .note("The prefix is recognised, so ATTR_JS wins and the value is suppressed. This is"
                        + " the behaviour F5 used to take away by changing nothing but the page"
                        + " around it, until R3 made the comparison length-checked. The bare form;"
                        + " residue.js-url-clean-buffer is the same href preceded by an element,"
                        + " which is what makes it a statement about buf rather than about the"
                        + " prefix table.")
                .build());

        cases.add(XssCase.id("prefix.javascript-mixed-case")
                .section(A4)
                .template("<a href=\"JavaScript:f('$data')\">x</a>")
                .sink(SinkKind.JAVASCRIPT, "a", "href")
                .payloads(Payloads.family("QUOTE_BREAKOUT"))
                .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                .note("The value scan lowercases as it buffers, exactly as the name scan does.")
                .build());

        cases.add(XssCase.id("prefix.livescript")
                .section(A4)
                .template("<a href=\"livescript:f('$data')\">x</a>")
                .sink(SinkKind.JAVASCRIPT, "a", "href")
                .payloads(Payloads.family("QUOTE_BREAKOUT"))
                .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                .build());

        cases.add(XssCase.id("prefix.mocha")
                .section(A4)
                .template("<a href=\"mocha:f('$data')\">x</a>")
                .sink(SinkKind.JAVASCRIPT, "a", "href")
                .payloads(Payloads.family("QUOTE_BREAKOUT"))
                .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                .note("mocha was checked at buf[5] rather than buf[10], so it had its own, shorter"
                        + " residue window - see residue.data-url-armed-buffer for the buf[4] one."
                        + " R3 replaced all five index tests with one length-checked comparison, so"
                        + " the windows are gone and this row's suppression no longer depends on how"
                        + " long the attribute happens to be called.")
                .build());

        cases.add(XssCase.id("prefix.asfunction")
                .section(A4)
                .template("<a href=\"asfunction:f('$data')\">x</a>")
                .sink(SinkKind.JAVASCRIPT, "a", "href")
                .payloads(Payloads.family("QUOTE_BREAKOUT"))
                .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                .note("An ActionScript scheme from the Flash era. Still in the table; srcset is not.")
                .build());

        // The scheme the table does NOT know, in a position where the name-derived ATTR_URI should
        // handle it. This was F4's second consequence, on its own; R2 is exactly the sentence the
        // old note ended on.
        cases.add(XssCase.id("prefix.vbscript-not-in-the-table")
                .section(A4)
                .template("<a href=\"vbscript:f('$data')\">x</a>")
                .sink(SinkKind.JAVASCRIPT, "a", "href")
                .payloads(Payloads.family("QUOTE_BREAKOUT"))
                .verdict(Verdict.SAFE)
                .finding("F4")
                .note("The colon fires detectAttributePrefix(), which used to reset to ATTR_HTML and"
                        + " then match none of its five prefixes - so a scheme the table has never"
                        + " heard of ended up LESS suppressed than one it has, and html() handed the"
                        + " VBScript engine the attacker's original characters. R2 removes the reset,"
                        + " which is what the old note said would fix it: the name-derived ATTR_URI"
                        + " survives and url() applies. Reviewed against the sink, the href is"
                        + " vbscript:f('%27);__canoePwned(%27q%27);//') - the vbscript: scheme is"
                        + " template text, and url() sees only the payload, whose quotes it"
                        + " percent-escapes to %27 so nothing can close the literal the template"
                        + " opened (R12 keeps the inert ')', ';' and '_' rather than escaping them,"
                        + " which changes the bytes but not the verdict). SAFE rather than"
                        + " SUPPRESSED: the value is"
                        + " emitted, it is simply emitted inert. Two things bound the verdict and"
                        + " both are worth stating. First, there is no VBScript engine left in any"
                        + " shipping browser, so nothing parses this href at all and a click"
                        + " navigates nowhere - which is why the row carried a not-browser-observable"
                        + " flag before, and why the flag is gone now that the row no longer claims a"
                        + " live vector. Second, this is deliberately NOT judged the way"
                        + " residue.js-url-armed-buffer is: a javascript: URL is percent-decoded by"
                        + " the HTML Standard before it is compiled, so escaping is not neutralising"
                        + " there, but vbscript: has no such algorithm and no implementation to run"
                        + " it. If one ever reappears, this row is wrong and should move back to"
                        + " KNOWN_VULNERABLE.")
                .browserRelevant()
                .build());

        // The URI downgrade F4 described, now closed. Kept because it is the regression net for the
        // half of F4 that never produced a KNOWN_VULNERABLE row and so would otherwise be untested.
        cases.add(XssCase.id("prefix.https-downgrades-url-to-html")
                .section(A4)
                .template("<a href=\"https://app.example/$data\">x</a>")
                .sink(SinkKind.URL, "a", "href")
                .payloads(Payloads.families("PROTOCOL_RELATIVE", "ABSOLUTE_OFFSITE"))
                .verdict(Verdict.SAFE)
                .finding("F4")
                .note("The ':' in 'https:' sits at index 5 and used to fire the reset, so this href"
                        + " was html-encoded rather than percent-encoded - a silent change of encoder"
                        + " that no template author asked for. It was SAFE anyway, but only because"
                        + " the template's own trailing '/' pinned the payload into the path: url()"
                        + " and html() differ in what they do to '&', '%' and non-ASCII, which is"
                        + " query-parameter and path manipulation rather than origin control, and"
                        + " the verdict would have changed if the template ended at the host. R2"
                        + " makes the accident unnecessary: 'https' matches none of the five value"
                        + " prefixes, the name-derived ATTR_URI stands, and the reference gets the"
                        + " url() the author asked for. The verdict is unchanged and the reason for"
                        + " it is not, which is why the note is kept rather than shortened.")
                .build());

        // The prefix window is ten characters wide, and a payload can sit right on its edge.
        cases.add(XssCase.id("prefix.payload-at-the-window-boundary")
                .section(A4)
                .template("<a href=\"$data\">x</a>")
                .sink(SinkKind.URL, "a", "href")
                .payloads(Payloads.families("LENGTH_STRESS", "UNICODE_EDGE"))
                .verdict(Verdict.SAFE)
                .override(Payloads.LENGTH_AT_PREFIX_WINDOW, Verdict.SUPPRESSED_BY_DESIGN)
                .note("The payload is the whole value, so it drives the prefix scan itself. Ten"
                        + " characters then a colon is the last position that reaches"
                        + " detectAttributePrefix(); it matches no prefix. Re-verdicted by R12 for"
                        + " the ten-a's-then-colon payload: 'aaaaaaaaaa:x' is a scheme to url()'s"
                        + " parser, and 'aaaaaaaaaa' is not on the {http,https,mailto} allowlist, so"
                        + " it is rejected and suppressed rather than left as the relative path the"
                        + " old %3A escape produced. The homoglyph colons are safe by design under"
                        + " R12: url() UTF-8 encodes every code point above 0x7F, so U+A789 and"
                        + " U+FF1A become their percent-escaped bytes and are not colons - and having"
                        + " no ASCII colon they carry no scheme, so they encode as an ordinary"
                        + " relative path.")
                .build());
    }

    // ------------------------------------------------------------------
    // A.4 buffer residue
    // ------------------------------------------------------------------

    /**
     * F5, as three templates that differ only in the elements around the one under test.
     *
     * <p>{@code buf} is a fixed-size field shared across the whole render, and it used to be
     * cleared by nothing at all — only {@code bufLen} was reset. The {@code TAG_ATTR_VALUE} path
     * never writes a NUL terminator, and the value scan can only ever write indices 0–9, so a value
     * could never repair {@code buf[10]} itself. Whether {@code javascript:} was recognised therefore
     * depended on what an earlier, unrelated attribute name had left there.
     *
     * <p><strong>R3 closed the finding</strong> by comparing the buffered prefix against
     * {@code bufLen} characters instead of testing fixed indices, and by clearing {@code buf} on
     * every reuse. All five cases below are {@code SUPPRESSED_BY_DESIGN} now, which is the point of
     * keeping them: the group exists to say that these pages have stopped differing, and it would
     * fail as loudly as it used to pass if any one of them started differing again.
     */
    private static void bufferResidue(List<XssCase> cases) {

        // The three cases below differ only in the attribute name of the element in front of the one
        // under test - two characters, eleven, then eleven followed by ten - which is the whole of
        // F5 stated as a table. This first one used to be the bare <a href="javascript:...">, which
        // made it a byte-for-byte duplicate of prefix.javascript-exact: same template modulo the
        // link text, same sink, same payloads, same verdict. It carries a short preceding attribute
        // name now, so the trio is a real progression and the case says something the prefix group
        // does not.
        cases.add(XssCase.id("residue.js-url-clean-buffer")
                .section(A4)
                .template("<input id=\"q\">"
                        + "<a href=\"javascript:f('$data')\">details</a>")
                .sink(SinkKind.JAVASCRIPT, "a", "href")
                .payloads(Payloads.family("QUOTE_BREAKOUT"))
                .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                .note("The control for the two below, and the reason F5 survived casual testing: this"
                        + " page looked exactly like the vulnerable one. A preceding element was"
                        + " never enough to arm F5 - the preceding attribute NAME had to be long"
                        + " enough. 'id' writes buf[0..1] and its terminator at buf[2], and the value"
                        + " scan can only ever write indices 0-9, so buf[10] still held the zero it"
                        + " was initialised with, the javascript: check read buf[10] and matched."
                        + " Since R3 the prefix is compared against bufLen and the buffer is cleared"
                        + " on reuse, so this case reaches the same verdict for a reason that no"
                        + " longer has anything to do with the buffer.")
                .build());

        cases.add(XssCase.id("residue.js-url-armed-buffer")
                .section(A4)
                .template("<input placeholder=\"Search\">"
                        + "<a href=\"javascript:f('$data')\">details</a>")
                .sink(SinkKind.JAVASCRIPT, "a", "href")
                .payloads(Payloads.family("QUOTE_BREAKOUT"))
                .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                .finding("F5")
                .note("Re-verdicted by R3, from KNOWN_VULNERABLE. placeholder is 11 characters, so it"
                        + " wrote buf[0..10] leaving buf[10]='r' and its terminator at buf[11]; the"
                        + " javascript: check read buf[10] and failed. Identical template to"
                        + " residue.js-url-clean-buffer - only the order of two elements differed,"
                        + " and that decided whether the page was safe. R2 changed the ENCODER this"
                        + " row went through and not the verdict: before R2 the missed prefix left"
                        + " the reset's ATTR_HTML and the payload was html()-encoded, after R2 it"
                        + " left the name-derived ATTR_URI and the href read"
                        + " javascript:f('%27%29%3B%5F%5FcanoePwned%28%27q%27%29%3B//'), which was"
                        + " still live because the HTML Standard's javascript-URL steps"
                        + " percent-decode the script source before compiling it. R3 makes the prefix"
                        + " comparison length-checked, so ATTR_JS applies whatever precedes the"
                        + " element. Reviewed against the sink: both QUOTE_BREAKOUT payloads render"
                        + " <a href=\"javascript:f('')\">details</a>, which is the template's own"
                        + " text with an empty string literal in it - no attacker character reaches"
                        + " the attribute, so none reaches the script source after percent-decoding"
                        + " either, and the row is judged on the decoded source rather than on the"
                        + " escaped bytes exactly as it was when it was vulnerable. SUPPRESSED_BY"
                        + "_DESIGN rather than SAFE: nothing is emitted, which is what CTX_JS means."
                        + " The finding stays cited so the row remains traceable to F5; the"
                        + " not-browser-observable flag on the double-quote payload is gone with the"
                        + " KNOWN_VULNERABLE verdict it qualified, since a suppressed row expects"
                        + " browser silence anyway.")
                .browserRelevant()
                .build());

        // The half of F5 that was easiest to disbelieve: an unrelated element could put the page
        // BACK into a safe state, because its attribute name was exactly the right length.
        cases.add(XssCase.id("residue.js-url-repaired-by-a-ten-character-name")
                .section(A4)
                .template("<input placeholder=\"Search\">"
                        + "<a xlink:href=\"/x\">y</a>"
                        + "<a href=\"javascript:f('$data')\">details</a>")
                .sink(SinkKind.JAVASCRIPT, "a", "href")
                .payloads(Payloads.family("QUOTE_BREAKOUT"))
                .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                .note("The same page as residue.js-url-armed-buffer with one extra link in the"
                        + " middle. xlink:href is exactly ten characters, so its NUL terminator"
                        + " landed on buf[10] and repaired what placeholder broke: deleting an"
                        + " unrelated element from this page used to make it vulnerable, which is the"
                        + " action at a distance F5 describes and the reason the fix was to clear the"
                        + " buffer rather than to lengthen any particular check. R3 did both that and"
                        + " the length-checked comparison, so this page and the one above it are now"
                        + " the same statement - which is why the pair is kept rather than merged.")
                .build());

        // The shorter residue windows. 'data' was checked at buf[4], so it was armed by any preceding
        // attribute name of five characters or more - and repaired by 'href', whose own terminator
        // landed there.
        cases.add(XssCase.id("residue.data-url-clean-buffer")
                .section(A4)
                .template("<a href=\"data:$data\">x</a>")
                .sink(SinkKind.URL, "a", "href")
                .payloads(Payloads.families("SRCDOC_MARKUP", "TAG_BREAKOUT"))
                .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                .note("href is four characters, so its NUL terminator sat at buf[4] - which was"
                        + " exactly the index the 'data' prefix check read. The prefix matches,"
                        + " ATTR_DATA applies, and the value is suppressed. Declared with a URL sink"
                        + " like its residue.data-url-armed-buffer twin: it plainly has one, and"
                        + " declaring noSink() here meant the case would have kept passing if the"
                        + " suppression ever stopped happening, because a NONE sink is judged SAFE"
                        + " unconditionally.")
                .build());

        cases.add(XssCase.id("residue.data-url-armed-buffer")
                .section(A4)
                .template("<body background=\"data:$data\">x</body>")
                .sink(SinkKind.URL, "body", "background")
                .payloads(Payloads.families("SRCDOC_MARKUP", "TAG_BREAKOUT"))
                .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                .finding("F5")
                .note("Re-verdicted by R3, from KNOWN_VULNERABLE, and it is the lower-threshold half"
                        + " of the finding: 'data' was checked at buf[4], so any URI attribute name"
                        + " of five characters or more disarmed it and href, at four, did not."
                        + " 'background' is ten characters, so buf[4] held its 'g' rather than a"
                        + " terminator and the check failed. Before R2 the reset had already"
                        + " discarded the name-derived ATTR_URI by that point and html() applied;"
                        + " after R2 the ATTR_URI survived and url() applied instead, and the verdict"
                        + " was unchanged either way because the URL oracle judges a data: URL by its"
                        + " scheme rather than by whether the payload's bytes happen to be escaped -"
                        + " the browser percent-decodes the data: URL before parsing the document"
                        + " inside it, so the attacker completed an arbitrary data: URL under both"
                        + " encoders. Reviewed against the sink: all four payloads now render"
                        + " <body background=\"data:\">x</body>, so the attribute carries the"
                        + " template's own scheme and nothing else. There is no URL for the oracle to"
                        + " judge, dangerous or otherwise, which is the difference between this and a"
                        + " SAFE verdict. The impact was always a resource load rather than script"
                        + " execution, because a background image is not a document; the point of the"
                        + " case is the buf[4] window, which the review records alongside buf[10] but"
                        + " which had no executable case until this corpus. The"
                        + " not-browser-observable flags all four payloads carried are gone with the"
                        + " KNOWN_VULNERABLE verdict they qualified - they said that no browser"
                        + " renders markup as a background image, which is still true and is now"
                        + " beside the point. Compare markup.srcdoc, where the same payload family"
                        + " reaches a sink that DOES parse markup.")
                .browserRelevant()
                .build());
    }

    // ------------------------------------------------------------------
    // A.7 Malformed and hostile template shapes
    // ------------------------------------------------------------------

    /**
     * The templates Canoe refuses, and the ones it accepts but models wrongly.
     *
     * <p>{@code CanoeRobustnessTest} (T11) owns the exact error messages and the line/position
     * reporting. These entries exist so the shapes are in the corpus: the generated report counts
     * them, T21's chunk-invariance property runs over them, and the browser tier can see what a
     * half-written response looks like. A rejection is not a degraded page: after R21 it is a
     * {@code CanoeEncodingException} the application can catch, on a response that has not been
     * flushed and can still be replaced wholesale. Before R21 it was an unhandled 500 on a response
     * that had been flushed and therefore could not be — F13, and the reason none of these rows is
     * merely cosmetic.
     *
     * <p>R21 changed how a rejection is delivered and not which templates are rejected, so every
     * verdict below was unchanged by it. <strong>R20 then decided which of them should be rejections
     * at all</strong>, and five rows moved: the three XHTML-style void elements and the second
     * DOCTYPE render now, and the two length boundaries moved from 35/36 to 127/128. Each surviving
     * rejection has its reason on its own row, and {@code CanoeRobustnessTest.rejections()} carries
     * the reasoning for the group.
     */
    private static void malformedTemplates(List<XssCase> cases) {

        // The XHTML-style void elements, ACCEPTED since R20. TAG_NAME lets a '/' end the name and
        // hands it to the TAG state, which already routed it to TAG_EMPTY_ENDING.
        cases.add(XssCase.id("void.br-no-space")
                .section(A7)
                .template("<p>$data</p><br/>")
                .textSink("p")
                .payloads(Payloads.INERT_MARKER, Payloads.TAG_IMG_ONERROR)
                .verdict(Verdict.SAFE)
                .finding("F13")
                .note("R20: the page renders. Was reject.void-br (REJECTED, 'Invalid character after"
                        + " tag name'), the first row of F13's table: <br /> with a space parsed and"
                        + " <br/> without one did not, so the two spellings of the most common void"
                        + " element in the wild disagreed and the commoner one took the page down."
                        + " Re-verdicted SAFE by reading the render: the reference is in the <p> text"
                        + " sink, htmlWhite() escapes TAG_IMG_ONERROR there, and the document's shape"
                        + " is the template's own. The F13 citation is kept so the finding keeps a"
                        + " live regression case rather than losing one when it was closed.")
                .build());

        cases.add(XssCase.id("void.hr-no-space")
                .section(A7)
                .template("<p>$data</p><hr/>")
                .textSink("p")
                .payloads(Payloads.INERT_MARKER, Payloads.TAG_IMG_ONERROR)
                .verdict(Verdict.SAFE)
                .note("R20, as void.br-no-space. Was reject.void-hr.")
                .build());

        cases.add(XssCase.id("void.img-no-space")
                .section(A7)
                .template("<p>$data</p><img/>")
                .textSink("p")
                .payloads(Payloads.INERT_MARKER, Payloads.TAG_IMG_ONERROR)
                .verdict(Verdict.SAFE)
                .note("R20, as void.br-no-space. Was reject.void-img.")
                .build());

        cases.add(rejected("reject.bare-less-than-in-body", "<p>5 < 6 $data</p>")
                .note("'Tag name too short'. A literal '<' in body text kills the render - which is"
                        + " also, read the other way, the check that makes body context safe.")
                .build());

        cases.add(rejected("reject.closing-tag-with-space", "<p>$data</p></ p>").build());

        cases.add(rejected("reject.empty-closing-tag", "<p>$data</p></>").build());

        // The MAX_TAGNAME_LEN boundary, from both sides. R20 raised the constant from 36 to 128;
        // buf is MAX_TAGNAME_LEN long and the check fires at bufLen == buf.length - 1, so the real
        // limit is one less than the constant and the relationship is what these rows pin.
        cases.add(XssCase.id("shape.tag-name-at-the-limit")
                .section(A7)
                .template("<" + repeat('a', NAME_LIMIT) + ">$data")
                .textSink(repeat('a', NAME_LIMIT))
                .payloads(Payloads.family("LENGTH_STRESS"))
                .verdict(Verdict.SAFE)
                .note("127 characters is the longest tag name that parses. MAX_TAGNAME_LEN reads"
                        + " 128, but the check is bufLen == buf.length - 1 and the name needs a NUL"
                        + " terminator, so 128 is one too many. R20 raised the constant from 36,"
                        + " which put this boundary at 35 - short enough that ordinary custom"
                        + " element and data-* attribute names crossed it. Left unclosed on purpose:"
                        + " see reject.closing-tag-name-at-the-limit for why it cannot be closed.")
                .build());

        cases.add(rejected("reject.tag-name-over-the-limit",
                "<" + repeat('a', NAME_LIMIT + 1) + ">$data")
                .note("'Tag name too long' at the 128th character. Still a rejection after R20,"
                        + " which raised the cap rather than removing it: the buffer is fixed-size"
                        + " by design, so there is a limit at some length and this row is where it"
                        + " is. F13's table gives <data-widget-configuration-attribute-name> as the"
                        + " example, and that name renders now.")
                .build());

        cases.add(rejected("reject.closing-tag-name-at-the-limit",
                "<" + repeat('a', NAME_LIMIT) + ">$data</" + repeat('a', NAME_LIMIT) + ">")
                .note("The 127-character opening tag above parses; the matching CLOSING tag does"
                        + " not, because buf[0] holds the '/' and only 126 characters of name fit"
                        + " after it. So an element name of exactly the limit can be opened and can"
                        + " never be closed. R20 moved the length at which this bites (35 -> 127)"
                        + " and deliberately did not close the asymmetry: it is one character at a"
                        + " length no real element name reaches, where before it was one character"
                        + " at a length several do. Pinned so a future change to the limit has to"
                        + " decide about both ends.")
                .build());

        cases.add(XssCase.id("shape.attribute-name-at-the-limit")
                .section(A7)
                .template("<p " + repeat('a', NAME_LIMIT) + "=\"1\">$data</p>")
                .textSink("p")
                .payloads(Payloads.family("LENGTH_STRESS"))
                .verdict(Verdict.SAFE)
                .note("Attribute names share buf and therefore share the limit: 127 parses, 128 does"
                        + " not. This is the half of the cap that a real page hits - see"
                        + " shape.framework-length-attribute-name, which used to be a rejection at"
                        + " 43 characters.")
                .build());

        cases.add(rejected("reject.attribute-name-over-the-limit",
                "<p " + repeat('a', NAME_LIMIT + 1) + "=\"1\">$data</p>")
                .note("'Attribute name too long'.")
                .build());

        // R20's own row: the shape the old cap actually broke on ordinary pages.
        cases.add(XssCase.id("shape.framework-length-attribute-name")
                .section(A7)
                .template("<div data-controller-target-value-for-the-widget=\"1\">$data</div>")
                .textSink("div")
                .payloads(Payloads.INERT_MARKER, Payloads.TAG_IMG_ONERROR)
                .verdict(Verdict.SAFE)
                .finding("F13")
                .note("A data-* attribute name of 43 characters, which is unremarkable in any modern"
                        + " framework and was a failed request before R20 ('Attribute name too"
                        + " long', at 36). Section 5 observation 1 of the remediation plan is this"
                        + " row: F13's table lists the tag-name cap and not its attribute sibling,"
                        + " and the sibling is the one a real template hits. The reference renders in"
                        + " the <div> text sink, where htmlWhite() escapes it - the name is"
                        + " plain-text-classified by the data- prefix rule, so nothing about the"
                        + " length changes the classification either.")
                .build());

        // DOCTYPE placement. The precondition used to be a tagCount that counted every '<' seen in
        // HTML state, comments included (F18); R18 makes it "no element has been emitted yet", which
        // is the question the check was always asking, plus "no DOCTYPE has been accepted yet".
        cases.add(rejected("reject.doctype-after-an-element", "<html><!DOCTYPE html><p>$data</p>")
                .note("Correct to reject, and the message says so plainly. R18 rewords it from"
                        + " 'DOCTYPE declaration must be at the beginning' - which stopped being true"
                        + " of the rule the moment a comment was allowed above the DOCTYPE - to"
                        + " 'DOCTYPE declaration must precede the first element'.")
                .build());

        cases.add(XssCase.id("doctype.second-is-ignored")
                .section(A7)
                .template("<!DOCTYPE html><!-- c --><!DOCTYPE html><p>$data</p>")
                .textSink("p")
                .payloads(Payloads.INERT_MARKER, Payloads.TAG_IMG_ONERROR)
                .verdict(Verdict.SAFE)
                .note("R20: the page renders, and the second declaration is passed through to the"
                        + " browser, which discards the token. Was reject.second-doctype (REJECTED,"
                        + " 'Duplicate DOCTYPE declaration'), added by R18 to bound its own fix and"
                        + " left for R20 to decide. Decided: the HTML Standard ignores a DOCTYPE"
                        + " token in every insertion mode after 'initial', so no consuming parser"
                        + " has an opinion about this document, and the shape it comes from - a"
                        + " layout and an included fragment each declaring one - is the most ordinary"
                        + " composition mistake a templating system has. Canoe warns instead"
                        + " (CanoeRobustnessTest.theSecondDoctypeIsIgnoredWithAWarning asserts the"
                        + " message and the coordinates), which keeps the whole value the check ever"
                        + " had. Re-verdicted SAFE by reading the render: the reference reaches the"
                        + " <p> text sink and htmlWhite() escapes it there, and the DOM skeleton is"
                        + " the benign one. The comment between the two declarations is kept from the"
                        + " R18 row, because 'a comment does not re-open the door' is still the bound"
                        + " on F18 - what changed is that there is no door.")
                .build());

        cases.add(XssCase.id("doctype.after-leading-text")
                .section(A7)
                .template("hello<!DOCTYPE html><p>$data</p>")
                .textSink("p")
                .payloads(Payloads.INERT_MARKER, Payloads.TAG_IMG_ONERROR)
                .verdict(Verdict.SAFE)
                .note("Accepted before R20 and accepted after it, with a warning added. The HTML"
                        + " Standard's 'initial' insertion mode ignores whitespace and treats any"
                        + " other character as a parse error that moves the parser on, so a browser"
                        + " renders this document in QUIRKS MODE and the declaration does nothing."
                        + " R18 accepted it silently and said the silence was R20's to decide; R20"
                        + " keeps the acceptance - a rejection would be a new way for a page that"
                        + " renders today to fail - and closes the gap with a diagnostic instead. The"
                        + " row is here because the ledger had no case for this shape at all: it was"
                        + " an accepted input nothing rendered. What it bounds is that the warning"
                        + " changed nothing about the OUTPUT, which is the risk in adding one.")
                .build());

        // F18, closed by R18. This row used to be reject.doctype-after-a-comment: a licence header,
        // an editor marker or a generator stamp above the DOCTYPE is legal HTML and common in
        // template files, and it took the whole page down.
        cases.add(XssCase.id("doctype.after-a-comment")
                .section(A7)
                .template("<!-- c --><!DOCTYPE html><p>$data</p>")
                .textSink("p")
                .payloads(Payloads.family("TAG_BREAKOUT"))
                .verdict(Verdict.SAFE)
                .finding("F18")
                .note("R18: the page renders. The precondition asks whether an element has been"
                        + " emitted rather than whether a '<' has been seen, and a comment is not an"
                        + " element - it does not move a browser out of the 'initial' insertion mode"
                        + " either. So the reference reaches the <p> text context, where htmlWhite()"
                        + " escapes TAG_BREAKOUT and the document's shape is the template's own. Was"
                        + " REJECTED, citing F18 for the rejection; re-verdicted SAFE against the"
                        + " rendered output, keeping the citation so F18 keeps a live regression"
                        + " case.")
                .build());

        cases.add(rejected("reject.doctype-misspelt", "<!DOCTYPX html><p>$data</p>")
                .note("'Invalid DOCTYPE declaration' - a different message from the placement one,"
                        + " and reached by a different branch.")
                .build());

        cases.add(rejected("reject.control-character-in-template-text",
                "<p>" + ch(0x01) + "$data</p>")
                .note("A C0 control in template LITERAL text is fatal. The same character inside a"
                        + " payload is not, because htmlWhite() turns it into the four literal"
                        + " characters \\x01 before it reaches the state machine - which is why"
                        + " body.paragraph carries the CONTROL_CHARS family and is SAFE.")
                .build());

        cases.add(rejected("reject.cdata-section", "<![CDATA[$data]]>")
                .note("'Invalid tag'. COMMENT_OPEN_OR_DOCTYPE accepts only '-' and 'd'/'D' after"
                        + " '<!'. Legal in foreign content, and rejected everywhere.")
                .build());

        cases.add(rejected("reject.unknown-bang-declaration", "<p>$data</p><!x>").build());

        cases.add(rejected("reject.equals-with-no-attribute-name", "<p =x>$data</p>")
                .note("'Invalid character in attribute name', raised from TAG state.")
                .build());

        cases.add(rejected("reject.quote-immediately-after-attribute-name", "<p id\"x\">$data</p>")
                .note("'Invalid character after tag name', raised from TAG_ATTR_NAME - the same"
                        + " message as reject.void-br, from a different call site. That is why T11"
                        + " pins the call-site count as well as the message set.")
                .build());

        cases.add(rejected("reject.quote-after-attribute-name-and-space", "<p id \"x\">$data</p>")
                .note("'Invalid character in tag name', raised from TAG_ATTR_NAME_AFTER. One space"
                        + " away from the case above and a different message.")
                .build());

        // Accepted, but the state machine's model of the document is now wrong or truncated.
        cases.add(XssCase.id("shape.unclosed-tag-at-end-of-output")
                .section(A7)
                .template("<p>$data</p><div")
                .textSink("p")
                .payloads(Payloads.family("TAG_BREAKOUT"))
                .verdict(Verdict.SAFE)
                .note("No error: the machine simply ends in TAG_NAME. The reference before it was"
                        + " encoded correctly, which is all this case claims.")
                .build());

        cases.add(XssCase.id("shape.unclosed-attribute-value-at-end-of-output")
                .section(A7)
                .template("<p>text</p><a title=\"$data")
                .textSink("body")
                .payloads(Payloads.family("ATTR_BREAKOUT"))
                .verdict(Verdict.SAFE)
                .note("ATTR_HTML applies and html() encodes the payload, so the truncated attribute"
                        + " cannot be escaped from. The declaration used to be noSink() + SAFE, which"
                        + " is a row that could never fail: observe() returns SAFE unconditionally"
                        + " for a NONE sink, so the case asserted nothing at all. It is judged"
                        + " structurally against the whole document now - jsoup does discard the"
                        + " unterminated tag, which is a fair description of what a browser does with"
                        + " it, and the assertion that therefore has teeth is that the payload does"
                        + " not put anything BACK: a value that escaped the truncated attribute would"
                        + " show up as an element or an attribute the benign render does not have."
                        + " The leading <p> is there so the skeleton is non-empty on both sides.")
                .build());

        cases.add(XssCase.id("shape.unclosed-comment")
                .section(A7)
                .template("<!-- $data")
                .noSink()
                .payloads(Payloads.family("TAG_BREAKOUT"))
                .verdict(Verdict.SUPPRESSED_UNINTENDED)
                .build());

        cases.add(XssCase.id("shape.unclosed-script")
                .section(A7)
                .template("<script>$data")
                .sink(SinkKind.JAVASCRIPT, "script", null)
                .payloads(Payloads.family("QUOTE_BREAKOUT"))
                .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                .build());

        // F10, both directions, closed by R17. Neither was ever attacker-reachable, precisely
        // because attacker data can never emit a raw '<' - the property T23 guards - so what these
        // four rows record is a divergence between Canoe's model of the tokenizer and the browser's,
        // and what they record now is that the divergence is gone.
        cases.add(XssCase.id("desync.script-end-tag-with-a-suffix")
                .section(A7)
                .template("<script>x=1;</scriptfoo>$data")
                .sink(SinkKind.JAVASCRIPT, "script", null)
                .payloads(Payloads.family("TAG_BREAKOUT"))
                .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                .finding("F10")
                .note("Was SAFE, and safe for an accidental reason. SCRIPT_END used to match the"
                        + " seven characters '/script' and return to HTML without checking what"
                        + " followed, so Canoe believed the script had ended and encoded the"
                        + " reference for CTX_HTML while every browser stayed in script data; the"
                        + " htmlWhite() output landed in script TEXT, where character references are"
                        + " not decoded, so it was inert by luck rather than by routing. R17 requires"
                        + " the delimiter the HTML Standard requires - whitespace, '/' or '>' - so"
                        + " '</scriptfoo>' now closes nothing for Canoe either. The reference is"
                        + " inside the script body for both parsers, and a script body is suppressed"
                        + " by design (CTX_JS -> empty), so the payload reaches no sink at all. The"
                        + " sink moved with the verdict: it was declared HTML_TEXT because Canoe put"
                        + " htmlWhite() output there, and the position is now unambiguously"
                        + " JAVASCRIPT - which is what makes this row fail rather than pass the day"
                        + " CTX_JS is relaxed, because a JavaScript breakout does not change the"
                        + " document skeleton the HTML_TEXT oracle measures. Browser-relevant still:"
                        + " the tier confirms the rendered page fires nothing.")
                .browserRelevant()
                .build());

        cases.add(XssCase.id("desync.script-stuck-on-a-double-less-than")
                .section(A7)
                .template("<script>x = 1 <</script><p>$data</p>")
                .textSink("p")
                .payloads(Payloads.family("TAG_BREAKOUT"))
                .verdict(Verdict.SAFE)
                .finding("F10")
                .note("Was SUPPRESSED_UNINTENDED - the converse desync, an availability defect of"
                        + " the same shape as F14. SCRIPT_END mismatched on the second '<' and"
                        + " returned to SCRIPT without re-processing that character, so the '<' that"
                        + " opens the real </script> was dropped, the machine never left the script"
                        + " body and every reference for the rest of the page rendered empty. R17"
                        + " re-processes the mismatching character, so the end tag is recognised and"
                        + " the reference lands in the <p> text context html() escapes: the"
                        + " structural oracle sees the same document skeleton as the benign render,"
                        + " which is SAFE. The page keeps its content, which is the point.")
                .build());

        cases.add(XssCase.id("desync.style-end-tag-with-a-suffix")
                .section(A7)
                .template("<style>a{}</stylefoo>$data")
                .sink(SinkKind.CSS, "style", null)
                .payloads(Payloads.families("CSS_INJECTION", "CSS_IMPORT"))
                .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                .finding("F10")
                .note("Was SAFE, for the RAWTEXT reason: the browser never decodes character"
                        + " references inside a style element, so the entity-encoded payload was"
                        + " inert text rather than CSS. CSS_END had the identical defect with"
                        + " '/style' and R17 fixed it identically, so '</stylefoo>' closes nothing"
                        + " and the reference is inside the style body, which suppresses (R14/F21"
                        + " keeps ATTR_CSS and the CSS states on CTX_SUPPRESS). Nothing reaches the"
                        + " sink. Sink kind moved from HTML_TEXT to CSS with the verdict, for the"
                        + " same reason as the script twin: the position is a style element body"
                        + " now that Canoe agrees the element is still open.")
                .build());

        cases.add(XssCase.id("desync.style-stuck-on-a-double-less-than")
                .section(A7)
                .template("<style>a{} <</style><p>$data</p>")
                .textSink("p")
                .payloads(Payloads.family("CSS_INJECTION"))
                .verdict(Verdict.SAFE)
                .finding("F10")
                .note("Was SUPPRESSED_UNINTENDED, the CSS twin of the converse desync. R17 hands the"
                        + " mismatching character back to CSS, so </style> is recognised and the"
                        + " reference renders html()-escaped in the paragraph after it - inert text"
                        + " in a text sink, with the document skeleton unchanged.")
                .build());

        cases.add(XssCase.id("shape.script-containing-a-script-literal")
                .section(A7)
                .template("<script>var a = \"<script>\";</script><p>$data</p>")
                .textSink("p")
                .payloads(Payloads.family("TAG_BREAKOUT"))
                .verdict(Verdict.SAFE)
                .note("Canoe and the HTML Standard agree here, for once: script data state ends at"
                        + " the first '</script', and a nested opening tag inside the body is just"
                        + " text to both. Included because the shape looks like a desync and is not.")
                .build());

        cases.add(XssCase.id("shape.deeply-nested-elements")
                .section(A7)
                .template(nest("div", 20, "<p>$data</p>"))
                .textSink("p")
                .payloads(Payloads.family("TAG_BREAKOUT"))
                .verdict(Verdict.SAFE)
                .note("There is no element stack to overflow: Canoe keeps one state variable and one"
                        + " fixed-size name buffer, so depth costs it nothing.")
                .build());
    }

    // ------------------------------------------------------------------
    // Template helpers
    // ------------------------------------------------------------------

    /**
     * A one-character string from a code unit, so that this file stays pure ASCII and cannot be
     * corrupted by a compiler running under a non-UTF-8 default charset.
     */
    private static String ch(int codeUnit) {
        return String.valueOf((char) codeUnit);
    }

    private static String repeat(char c, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    /** {@code depth} nested elements of the given name wrapped around {@code inner}. */
    private static String nest(String element, int depth, String inner) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            sb.append('<').append(element).append('>');
        }
        sb.append(inner);
        for (int i = 0; i < depth; i++) {
            sb.append("</").append(element).append('>');
        }
        return sb.toString();
    }
}
