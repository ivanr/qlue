package com.webkreator.qlue.view.canoe.corpus;

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
     * information: every one of those names takes the identical {@code ATTR_HTML} fall-through, so the
     * per-payload distinctions are a property of {@code html()} and of the URL parser, not of the
     * name. They are pinned once, exhaustively, on the four headline sinks below, and the tail carries
     * one payload per mechanism — a script scheme, a protocol-relative host, an absolute host.
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
     * Why six {@code JS_URL} payloads are safe in an attribute Canoe does <em>not</em> protect. Every
     * one is an accident of {@code html()}, not a defence, and the reasons are written out in full
     * wherever they apply so that nobody reads a {@code SAFE} row as evidence the sink is handled.
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
     * The {@code JS_URL} payloads {@code html()} neutralises by accident. Applied as a group because
     * they are all the same kind of luck; see {@link #C0_CONTROL_ACCIDENT} for the three mechanisms.
     */
    private static final List<Payload> HTML_ENCODER_ACCIDENTS = Arrays.asList(
            Payloads.JS_URL_TAB_SPLIT, Payloads.JS_URL_NEWLINE_SPLIT,
            Payloads.JS_URL_LEADING_CONTROL, Payloads.JS_URL_NUL_SPLIT,
            Payloads.JS_URL_ENTITY_DECIMAL, Payloads.JS_URL_PERCENT_ENCODED);

    /**
     * Why the {@code PROTOCOL_RELATIVE/backslash} row inside a CSS {@code url()} is correctly
     * {@code KNOWN_VULNERABLE} and is still not an off-origin fetch. Written out because the verdict
     * invites exactly the wrong reading.
     */
    private static final String CSS_BACKSLASH_IS_AN_ESCAPE =
            "One row needs its own reading. PROTOCOL_RELATIVE/backslash is /\\attacker.invalid/x.js,"
                    + " and it is KNOWN_VULNERABLE for the right reason - the attacker's bytes reach"
                    + " the CSS parser untouched, which is F4 - but it does NOT fetch from the"
                    + " sentinel host. CSS reads a backslash as the start of an escape, so \\a is"
                    + " U+000A and the url() token resolves to a path that is not the attacker's"
                    + " host. The URL oracle's backslash-is-a-path-separator rule is an HTML/URL"
                    + " rule, not a CSS one, and this is the sink where the two disagree. Do not read"
                    + " the row as evidence of an off-origin request; the neighbouring CSS_INJECTION"
                    + " payloads are the ones that make that request.";

    /**
     * The three CSS cases carry {@code CSS_EXPRESSION}, which no engine has run since Internet
     * Explorer 11 was retired. It stays in the ledger — Canoe emitted it live, and &sect;8 is
     * explicit that a dead vector is still a Canoe defect — and it is flagged so the browser tier
     * expects the detector to stay quiet rather than reporting a ledger divergence.
     */
    private static final String EXPRESSION_IS_DEAD =
            "CSS_EXPRESSION is flagged not-browser-observable: expression() was an Internet Explorer"
                    + " extension and no engine the browser tier drives will evaluate it. The ledger"
                    + " entry is about what Canoe emitted and stays KNOWN_VULNERABLE; the flag is how"
                    + " the browser tier is told to expect a miss instead of failing on the"
                    + " divergence.";

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
     */
    private static final String A_DOUBLE_QUOTE_CANNOT_CLOSE_A_SINGLE_QUOTED_LITERAL =
            "QUOTE_BREAKOUT/double-quote is flagged not-browser-observable. Every template in this"
                    + " group puts the reference inside a SINGLE-quoted JavaScript string literal,"
                    + " and a double quote cannot close one: the payload arrives live, stays one"
                    + " string argument, and no engine parses a single new token of it as code. The"
                    + " single-quote sibling of the same row is the one that runs. The verdict stays"
                    + " KNOWN_VULNERABLE because the raw quote does reach the JavaScript parser and"
                    + " because VerdictEvaluator is deliberately not quote-aware (plan item 6);"
                    + " BrowserCorpusTest is what turned that documented over-report into a list of"
                    + " named rows instead of a sentence.";

    /**
     * The bound T28 put on F4, in the same shape T16 put one on F6.
     *
     * <p>T17 measured what decides whether Canoe <em>encodes</em> a CSS reference: the index of the
     * first colon. A browser then measured what decides whether anything <em>happens</em>, and it
     * is a different question with a different answer — the CSS container the reference sits in.
     * Three {@code style} attributes, all past the colon test, all html-encoded, all
     * {@code KNOWN_VULNERABLE}: {@code background:$x} fetches from the attacker's origin,
     * {@code content:'$x'} produces one inert string, and {@code background:url($x)} produces a
     * bad-url token and drops the declaration. F4 is real and narrower than it reads, and neither
     * half of that sentence can be reached from Canoe's output alone.
     */
    private static final String THE_CSS_CONTAINER_DECIDES =
            "F4's blast radius is bounded by the CSS container the reference sits in, not only by"
                    + " the colon index T17 measures: whether the value becomes a DECLARATION"
                    + " decides whether a browser acts on it. css.style-background is the shape that"
                    + " does; this one does not, and both are correctly KNOWN_VULNERABLE, because"
                    + " the ledger's subject is what Canoe emitted.";

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

    /** The shape shared by every URL-bearing name that falls through to {@code ATTR_HTML} (F3). */
    private static XssCase.Builder unrecognisedUrlAttribute(String id, String template,
                                                            String selector, String attribute,
                                                            List<Payload> payloads) {
        return XssCase.id(id)
                .section(A2)
                .template(template)
                .sink(SinkKind.URL, selector, attribute)
                .payloads(payloads)
                .verdict(Verdict.KNOWN_VULNERABLE)
                .finding("F3");
    }

    /**
     * The tail form of {@link #unrecognisedUrlAttribute}: three payloads, one per mechanism. The
     * per-payload distinctions are pinned exhaustively on the four headline sinks, which carry all
     * thirteen.
     */
    private static XssCase.Builder unrecognisedUrlAttribute(String id, String template,
                                                            String selector, String attribute) {
        return unrecognisedUrlAttribute(id, template, selector, attribute, urlProbe());
    }

    /**
     * The {@code JS_URL} payloads {@code html()} neutralises by accident, marked safe; see
     * {@link #C0_CONTROL_ACCIDENT}.
     */
    private static XssCase.Builder withC0ControlAccidents(XssCase.Builder builder) {
        for (Payload payload : HTML_ENCODER_ACCIDENTS) {
            builder.override(payload, Verdict.SAFE);
        }
        return builder;
    }

    /** The shape shared by the five names {@code setTagAttributeContext()} maps to {@code ATTR_URI}. */
    private static XssCase.Builder recognisedUriAttribute(String id, String template,
                                                          String selector, String attribute) {
        return XssCase.id(id)
                .section(A2)
                .template(template)
                .sink(SinkKind.URL, selector, attribute)
                .payloads(allUrlPayloads())
                .verdict(Verdict.SAFE)
                .finding("F6")
                .override(Payloads.PROTOCOL_RELATIVE, Verdict.KNOWN_VULNERABLE)
                .override(Payloads.ABSOLUTE_OFFSITE_HTTPS, Verdict.KNOWN_VULNERABLE);
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
     * <p>The default is {@link Verdict#SAFE} and each case names the payloads that are actually
     * <em>live</em> in its attribute. That is the opposite of how this group was first written, and
     * the change matters: the cross-product handed all three policy tokens to all six attributes and
     * recorded {@code KNOWN_VULNERABLE} for every one, which is a claim &sect;2.1 does not support.
     * {@code sandbox="opener"} is an unknown sandbox token, so the sandbox stays maximally
     * restrictive; {@code rel="_blank"} is not a link type. The bytes arrive, but nothing acts on
     * them, and "attacker data reaches the sink <em>live</em>" is the definition. The oracle could not
     * see the difference — it asks only whether the bytes survived — so this was a wrong verdict that
     * no test could have caught, which is exactly the class the ledger exists to make visible.
     */
    private static XssCase.Builder policyAttribute(String id, String template,
                                                   String selector, String attribute) {
        return XssCase.id(id)
                .section(A2)
                .template(template)
                .sink(SinkKind.POLICY, selector, attribute)
                .payloads(Payloads.family("POLICY_OVERRIDE"))
                .verdict(Verdict.SAFE)
                .finding("F20");
    }

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
                        + " suppressed - which is exactly what the F10 converse case does.")
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
                        + " A generator stamp or a debug marker built from a reference renders empty.")
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

        // F14: COMMENT_CLOSE_2 drops back to COMMENT on a third '-', so the comment never closes and
        // every reference for the rest of the page is suppressed.
        cases.add(XssCase.id("comment.three-dashes-swallows-the-page")
                .section(A1)
                .template("<!--a---><p>$data</p>")
                .textSink("p")
                .payloads(Payloads.family("TAG_BREAKOUT"))
                .verdict(Verdict.SUPPRESSED_UNINTENDED)
                .finding("F14")
                .note("Every browser closes this comment at the '>'. Canoe does not, so the <p> that"
                        + " follows is comment text to it and the reference inside renders empty -"
                        + " as does every reference after it, anywhere on the page.")
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
                .note("The literal 'x' is what makes this reachable at all: it advances the machine"
                        + " to TAG_ATTR_VALUE with QUOTE_NONE. A reference sitting directly after the"
                        + " '=' does not get that far - see unquoted.immediately-after-equals."
                        + " html() escapes space and '>', so an unquoted value still cannot be"
                        + " terminated.")
                .browserRelevant()
                .build());

        // F11: currentContext() has no case for TAG_ATTR_VALUE_BEFORE, so a reference immediately
        // after '=' is inserted while the parser is still waiting for a quote.
        cases.add(XssCase.id("unquoted.immediately-after-equals")
                .section(A1)
                .template("<a href=$data>link</a>")
                .sink(SinkKind.URL, "a", "href")
                .payloads(Payloads.family("PROTOCOL_RELATIVE"))
                .verdict(Verdict.SUPPRESSED_UNINTENDED)
                .finding("F11")
                .note("Fail-closed, but silent: the value vanishes with no error and no diagnostic,"
                        + " and the documented remedy is $_x.asis(), which disables Canoe entirely.")
                .build());

        cases.add(XssCase.id("unquoted.whitespace-then-reference")
                .section(A1)
                .template("<a href= $data>link</a>")
                .sink(SinkKind.URL, "a", "href")
                .payloads(Payloads.family("PROTOCOL_RELATIVE"))
                .verdict(Verdict.SUPPRESSED_UNINTENDED)
                .finding("F11")
                .note("TAG_ATTR_VALUE_BEFORE skips whitespace, so the extra space changes nothing.")
                .build());

        // Script and style element bodies. Both suppressed, and both deliberately: refusing to output
        // into JavaScript and CSS is the centrepiece of the design.
        cases.add(XssCase.id("script.body-string-literal")
                .section(A1)
                .template("<script>var x = '$data';</script>")
                .sink(SinkKind.JAVASCRIPT, "script", null)
                .payloads(Payloads.families("QUOTE_BREAKOUT", "UNICODE_EDGE"))
                .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                .note("CTX_JS maps to the empty string. The commented-out code at Canoe.java:1074-1081"
                        + " contemplates replacing that with HtmlEncoder.js(), which F16 shows is not"
                        + " fit for it.")
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
        cases.add(XssCase.id("transition.attribute-then-text")
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
                        + " rather than the payload so that the sink under test stays unambiguous.")
                .build());
    }

    // ------------------------------------------------------------------
    // A.3 Event handlers
    // ------------------------------------------------------------------

    private static void eventHandlers(List<XssCase> cases) {

        // F1: the onS branch tests buf[0]=='s', but buf[0] is provably 'o' inside the on* block, so
        // onselect and onsubmit are unreachable and fall through to ATTR_HTML.
        cases.add(XssCase.id("handler.onsubmit")
                .section(A3)
                .template("<form onsubmit=\"v('$data')\"></form>")
                .sink(SinkKind.JAVASCRIPT, "form", "onsubmit")
                .payloads(Payloads.families("QUOTE_BREAKOUT", "ENTITY_BREAKOUT"))
                .verdict(Verdict.KNOWN_VULNERABLE)
                .finding("F1")
                .override(Payloads.ENTITY_PRE_ENCODED, Verdict.SAFE)
                .notBrowserObservable(Payloads.QUOTE_DOUBLE_BREAKOUT)
                .note("Dead branch at Canoe.java:514. html() entity-encodes the payload and the HTML"
                        + " parser decodes it back before the value is compiled as JavaScript."
                        + " " + ENTITY_BREAKOUT_IS_THE_CONTROL
                        + " " + A_DOUBLE_QUOTE_CANNOT_CLOSE_A_SINGLE_QUOTED_LITERAL)
                .browserRelevant()
                .build());

        // The contrast that makes F1 dangerous: a reviewer who spot-checks onclick concludes the
        // mechanism works.
        cases.add(XssCase.id("handler.onclick")
                .section(A3)
                .template("<a onclick=\"v('$data')\">x</a>")
                .sink(SinkKind.JAVASCRIPT, "a", "onclick")
                .payloads(Payloads.family("QUOTE_BREAKOUT"))
                .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                .note("Recognised, so suppressed. Spot-checking this one is how the F1 miss survives.")
                .build());

        // F19: the third dead on* branch. Its guard is buf[2]=='r' && buf[3]=='e' and its body then
        // tests buf[4]=='d', so the comparands spell on+re+dystatechange - the 'a' of "ready" is
        // missing. The branch matches onredystatechange and can never match the real attribute.
        cases.add(XssCase.id("handler.onreadystatechange")
                .section(A3)
                .template("<img src=\"x\" onreadystatechange=\"f('$data')\">")
                .sink(SinkKind.JAVASCRIPT, "img", "onreadystatechange")
                .payloads(Payloads.family("QUOTE_BREAKOUT"))
                .verdict(Verdict.KNOWN_VULNERABLE)
                .finding("F19")
                .notBrowserObservableFamily("QUOTE_BREAKOUT")
                .note("Dead branch at Canoe.java:483-491. Same mechanism as F1: html() entity-encodes"
                        + " the payload and the HTML parser decodes it back before the value is"
                        + " compiled as JavaScript. " + NO_ELEMENT_HOSTS_IT)
                .browserRelevant()
                .build());

        // The name the branch does match, which no document contains. Ledgered because it is the
        // evidence for F19 rather than a vulnerability: it is the misspelling working correctly, and
        // if this case ever stops being suppressed the F19 diagnosis was wrong.
        cases.add(XssCase.id("handler.onredystatechange")
                .section(A3)
                .template("<img src=\"x\" onredystatechange=\"f('$data')\">")
                .sink(SinkKind.JAVASCRIPT, "img", "onredystatechange")
                .payloads(Payloads.family("QUOTE_BREAKOUT"))
                .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                .note("F19's evidence: the branch works, for an attribute name that does not exist."
                        + " Suppressed here, injectable one letter away.")
                .build());

        // F2: there is no "any attribute starting with on is a JS context" rule, and the hand-written
        // table predates most of the modern DOM event set.
        cases.add(XssCase.id("handler.onfocus")
                .section(A3)
                .template("<input value=\"search\" onfocus=\"h('$data')\">")
                .sink(SinkKind.JAVASCRIPT, "input", "onfocus")
                .payloads(Payloads.families("QUOTE_BREAKOUT", "ENTITY_BREAKOUT"))
                .verdict(Verdict.KNOWN_VULNERABLE)
                .finding("F2")
                .override(Payloads.ENTITY_PRE_ENCODED, Verdict.SAFE)
                .notBrowserObservable(Payloads.QUOTE_DOUBLE_BREAKOUT)
                .note(ENTITY_BREAKOUT_IS_THE_CONTROL
                        + " " + A_DOUBLE_QUOTE_CANNOT_CLOSE_A_SINGLE_QUOTED_LITERAL)
                .browserRelevant()
                .build());
    }

    // ------------------------------------------------------------------
    // A.3 Event handlers, exhaustively (T15)
    // ------------------------------------------------------------------

    /**
     * The 21 {@code on*} names {@code setTagAttributeContext()} genuinely recognises.
     *
     * <p>The list is not maintained here: {@code CanoeStateMachineTest.declaredOnStarBranches} owns
     * the 24 declared branches and reads them back out of {@code Canoe.java} so the table cannot
     * drift, and {@code EventHandlerMatrixTest.theRecognisedListMatchesTheStateMachineTable} asserts
     * that this list is exactly that table minus the three dead branches. Duplicating the source
     * scan here would give two places to update and no extra assurance.
     *
     * <p>Three of the 21 — {@code ondragdrop}, {@code onend} and {@code onmove} — are not event
     * handler content attributes in any version of the HTML Standard. See {@link #ONDRAGDROP_IS_DEAD}.
     */
    private static final String[] RECOGNISED_HANDLERS = {
            "onabort", "onblur", "onchange", "onclick", "ondblclick", "ondragdrop", "onend",
            "onerror", "onkeydown", "onkeypress", "onkeyup", "onload", "onmousedown", "onmousemove",
            "onmouseout", "onmouseover", "onmouseup", "onmove", "onreset", "onresize", "onunload"};

    /**
     * Every {@code on*} name Canoe does <em>not</em> recognise, from two sources: the HTML Standard's
     * event handler content attributes (the checked-in list at
     * {@code src/test/resources/canoe/html-event-handler-attributes.txt}, which
     * {@code EventHandlerMatrixTest}'s completeness guard reads) and the handlers F2 enumerates that
     * the HTML Standard defines elsewhere or not at all — UI Events' {@code onfocusin}, CSS
     * Animations' {@code onanimationstart}, Pointer Events, Touch Events, and the two Selection
     * handlers.
     *
     * <p>Every one of them takes the {@code ATTR_HTML} fall-through, which the HTML parser undoes
     * before the value is compiled as JavaScript. That is F2, and the count is the part of F2 worth
     * reading twice: the finding's title says "roughly 40", and there are 91 here.
     *
     * <p>{@code onselect}, {@code onsubmit} and {@code onreadystatechange} are deliberately absent —
     * Canoe declares branches for all three and cannot take any of them, which is a different
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
     * lands. The classification Canoe applies is identical either way — it discards the tag name
     * once attribute parsing begins — so moving the element costs nothing and makes the sink real.
     *
     * <p>{@code onunload} is a Window handler too and is deliberately <em>not</em> here. It is one of
     * the 21 names Canoe recognises, so its row is {@code SUPPRESSED_BY_DESIGN} and its claim is
     * "nothing was emitted" — which is true on any element and asks nothing of the browser tier. The
     * list is the handlers whose row claims a <em>live</em> sink, because that is the claim an
     * element can falsify.
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
     * move to, so the flag is the only honest record. The Canoe defect is real and unchanged: the
     * name takes the {@code ATTR_HTML} fall-through and the attacker's characters arrive live at
     * whatever reads the attribute. What will not happen is a browser firing the handler.
     */
    private static final String NO_ELEMENT_HOSTS_IT =
            "No element hosts this attribute. It is an IDL attribute on Document (HTML Standard"
                    + " section 8.1.8.2, table 4), so it has no content-attribute form and no"
                    + " shipping engine registers a listener from markup -- <div "
                    + "onvisibilitychange=...> and <body onreadystatechange=...> are both inert."
                    + " The row stays KNOWN_VULNERABLE because Canoe classified it as plain text and"
                    + " the attacker's characters arrive live, which is the ledger's subject; it is"
                    + " flagged not-browser-observable because the browser tier must expect a"
                    + " detector miss rather than report a divergence. Contrast handler.ontoggle,"
                    + " which is one click away, and the four onwebkit* handlers, which need no"
                    + " interaction at all.";

    /** Elements that take no closing tag, so the generated template does not emit one. */
    private static final List<String> VOID_ELEMENTS =
            Arrays.asList("input", "img", "br", "meta", "link");

    private static final String RECOGNISED_HANDLERS_ARE_THE_DESIGN_WORKING =
            "One of the 21 names setTagAttributeContext() genuinely recognises: ATTR_JS ->"
                    + " CTX_JS -> the empty string. These cases are what stops the group from being"
                    + " a list of 90 failures with nothing to compare them against - the encoder is"
                    + " not broken, the table is incomplete, and only having both halves in the"
                    + " ledger shows which. Note also that they are suppressed only while the value"
                    + " has no colon in its first eleven characters; prefix.colon-in-a-recognised-"
                    + "handler is this same classification thrown away by F17.";

    private static final String UNRECOGNISED_HANDLERS_ARE_F2 =
            "The name is not in the hand-unrolled table, so it takes the ATTR_HTML default. html()"
                    + " turns the payload into character references, the HTML parser decodes them"
                    + " while building the attribute value, and the JavaScript parser is handed the"
                    + " attacker's original characters. Identical mechanism to F1, reached by an"
                    + " omission rather than by a wrong buffer index.";

    /**
     * Why {@code ondragdrop} is a curiosity rather than a flagged row.
     *
     * <p>It is the clearest single marker of the table's age: {@code ondragdrop} was a Netscape 4
     * event, removed from Gecko in Firefox 3, and no engine has fired it this century — while HTML5's
     * {@code ondrop} and {@code ondragstart}, which every engine fires, are missing. Canoe spends a
     * branch suppressing a handler that cannot run and lets the two that can through.
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
                    + " Canoe spends one of its 21 branches suppressing a handler that cannot run,"
                    + " while ondrop and ondragstart - which every engine fires - fall through to"
                    + " html(). Suppressed, so browser-observability says nothing here and the flag"
                    + " is deliberately not set; see the field javadoc.";

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
     * <p>The element is {@code <div>} unless a case says otherwise. Canoe discards the tag name once
     * attribute parsing begins ({@code buf} is reused at {@code Canoe.java:786}), so the element
     * cannot affect the <em>classification</em> and choosing a "realistic" one per handler would
     * suggest a dependency that does not exist. It does affect whether the declared
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
                .verdict(Verdict.KNOWN_VULNERABLE)
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
     * The event-handler matrix: all 21 recognised names, the three declared-but-dead ones, and the
     * 87 the table has never heard of.
     *
     * <p>{@code EventHandlerMatrixTest} (T15) is the test side of this, and its completeness guard is
     * the reason the group is exhaustive rather than representative: the guard reads the HTML
     * Standard's event handler content attributes from a checked-in resource file and fails if any
     * name has no case here. That converts "we listed the ones we thought of" — which is exactly what
     * {@code setTagAttributeContext()} itself is — into "we cover the spec", and it will fail
     * usefully the next time the list is refreshed against a newer revision of the standard.
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
                .verdict(Verdict.KNOWN_VULNERABLE)
                .finding("F1")
                .override(Payloads.ENTITY_PRE_ENCODED, Verdict.SAFE)
                .notBrowserObservable(Payloads.QUOTE_DOUBLE_BREAKOUT)
                .note("The onS block at Canoe.java:513-530 tests buf[0]=='s', and buf[0] is provably"
                        + " 'o' inside the block guarded by (buf[0]=='o' && buf[1]=='n') at line 334."
                        + " So it asks whether the attribute is named 'select', which it cannot be."
                        + " onselect fires on any text input the user selects text in, which needs"
                        + " no script and no unusual interaction. " + ENTITY_BREAKOUT_IS_THE_CONTROL
                        + " " + A_DOUBLE_QUOTE_CANNOT_CLOSE_A_SINGLE_QUOTED_LITERAL)
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
                    .verdict(Verdict.KNOWN_VULNERABLE)
                    .finding("F2")
                    .note(UNRECOGNISED_HANDLERS_ARE_F2);
            switch (name) {
                case "ontoggle":
                    // <details> toggles on a plain click, so this is one of the cheapest of the 91
                    // to demonstrate in a browser and one worth loading.
                    builder = handler(name, "details", "", "<summary>x</summary>y")
                            .verdict(Verdict.KNOWN_VULNERABLE)
                            .finding("F2")
                            .note(UNRECOGNISED_HANDLERS_ARE_F2
                                    + " ontoggle fires when a <details> element is opened, which is"
                                    + " one click and no script.")
                            .browserRelevant();
                    break;
                case "onmouseenter":
                    builder = handler(name, "div", "", "hover me")
                            .verdict(Verdict.KNOWN_VULNERABLE)
                            .finding("F2")
                            .note(UNRECOGNISED_HANDLERS_ARE_F2
                                    + " onmouseenter enters the onmouse branch and matches none of"
                                    + " d/m/o/u at buf[7], which is the near-miss shape that makes"
                                    + " the hand-unrolled table hard to audit: onmouseout and"
                                    + " onmouseover are one letter away and both suppressed. "
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
                            .browserRelevant()
                            .notBrowserObservable(Payloads.QUOTE_SINGLE_BREAKOUT);
                    break;
                case "onshow":
                    builder.note(UNRECOGNISED_HANDLERS_ARE_F2
                                    + " Flagged not-browser-observable. It is the only"
                                    + " browser-RELEVANT handler in this group that carries the flag"
                                    + " for a dead event rather than for a missing element: the"
                                    + " 'show' event was removed from the HTML Standard in 2022 and"
                                    + " Gecko's <menuitem>, the other thing that fired it, went with"
                                    + " Firefox 85, so no shipping engine will dispatch it. (The two"
                                    + " Document IDL names, onreadystatechange and"
                                    + " onvisibilitychange, are also flagged, for the different"
                                    + " reason that no element hosts them at all.) The ledger entry"
                                    + " is about what Canoe emitted and stays KNOWN_VULNERABLE; the"
                                    + " flag is how the browser tier is told to expect a detector"
                                    + " miss rather than a divergence. Compare handler.ondragdrop,"
                                    + " which is the same observation about a handler Canoe DOES"
                                    + " recognise and which therefore cannot carry the flag.")
                            .browserRelevant()
                            .notBrowserObservable(Payloads.QUOTE_SINGLE_BREAKOUT);
                    break;
                default:
                    break;
            }
            cases.add(builder.build());
        }
    }

    /**
     * Why the {@code ENTITY_BREAKOUT} family belongs in a live JavaScript sink and nowhere else.
     *
     * <p>It was declared for exactly this and then used only at {@code body.paragraph} and {@code
     * rcdata.textarea} — two plain-text sinks where the mechanism it probes cannot fire either way,
     * so the family was carried by the corpus without ever being exercised.
     */
    private static final String ENTITY_BREAKOUT_IS_THE_CONTROL =
            "The two payloads here are a matched pair, and together they are the corpus's only direct"
                    + " evidence for the claim the whole review turns on. QUOTE_BREAKOUT carries a"
                    + " raw apostrophe: html() writes &#39;, the HTML parser decodes it while"
                    + " building the attribute value, and the JavaScript parser is handed a real"
                    + " quote - one decode, and the string literal is escaped. ENTITY_PRE_ENCODED"
                    + " carries the SAME payload already spelled as character references: html()"
                    + " escapes its ampersands, so the parser's one decode returns the literal text"
                    + " &#39;&#41; and the JavaScript parser sees eight harmless characters inside"
                    + " the string. Same sink, same encoder, opposite outcomes - which shows the"
                    + " parser decodes exactly once. If a second decode ever appeared anywhere in the"
                    + " chain, this row flips to vulnerable and says so.";

    // ------------------------------------------------------------------
    // A.2 Attribute names
    // ------------------------------------------------------------------

    /**
     * The five names {@code setTagAttributeContext()} maps to {@code ATTR_URI}, so the value goes
     * through {@code HtmlEncoder.url()}.
     *
     * <p>All five behave identically, and identically wrongly, because {@code url()} is a scheme
     * filter rather than an origin filter (F6). Every {@code javascript:}-style scheme is genuinely
     * neutralised — the colon becomes {@code %3A} and what is left is a relative path — and the origin
     * is what survives.
     */
    private static void recognisedUriAttributes(List<XssCase> cases) {

        String urlAccidents =
                "PROTOCOL_RELATIVE_BACKSLASH is safe because url() escapes the backslash to %5C and no"
                        + " browser un-escapes it back into a path separator."
                        + " ABSOLUTE_OFFSITE_UPPERCASE is safe because the scheme regex is"
                        + " case-sensitive, so the colon gets escaped and the result is a relative"
                        + " path. ABSOLUTE_OFFSITE_USERINFO is safe because url() escapes the '@' to"
                        + " %40, putting a forbidden code point inside the host, so the URL fails to"
                        + " parse. All three are accidents of the encoder, not design.";

        cases.add(recognisedUriAttribute("url.href-full",
                "<a href=\"$data\">link</a>", "a", "href")
                .note(urlAccidents)
                .browserRelevant()
                .build());

        cases.add(recognisedUriAttribute("url.img-src",
                "<img src=\"$data\">", "img", "src")
                .note("Same encoder as <script src>, because Canoe discards the tag name once"
                        + " attribute parsing begins (buf is reused at Canoe.java:786). The impact"
                        + " differs enormously; the encoding does not.")
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

        // Canoe discards the tag name once attribute parsing begins, so src on <script> and src on
        // <img> get the same encoder.
        cases.add(XssCase.id("url.script-src-prefix")
                .section(A2)
                .template("<script src=\"$data/app.js\"></script>")
                .sink(SinkKind.URL, "script", "src")
                .payloads(allUrlPayloads())
                .verdict(Verdict.SAFE)
                .finding("F6")
                .override(Payloads.PROTOCOL_RELATIVE, Verdict.KNOWN_VULNERABLE)
                .override(Payloads.ABSOLUTE_OFFSITE_HTTPS, Verdict.KNOWN_VULNERABLE)
                .note("Attacker-controlled JavaScript executing with full page privileges. The"
                        + " safe entries are safe for the same accidental reasons as url.href-full.")
                .browserRelevant()
                .build());

        // The rest of the elements F6's exploitation vector applies to. The verdicts are identical
        // to url.img-src's and that identity IS the finding: Canoe reuses buf for the attribute name
        // at Canoe.java:786, so by the time setTagAttributeContext() runs the tag name is gone and
        // <script src>, <iframe src>, <embed src> and <img src> are indistinguishable. The
        // consequences are not: an off-origin <img src> leaks a referrer, an off-origin <script src>
        // and an off-origin <iframe src> are arbitrary code in the page. UrlSinkTest (T16) asserts
        // the byte-identity across all nine elements rather than leaving it to these five notes.
        cases.add(recognisedUriAttribute("url.iframe-src",
                "<iframe src=\"$data\"></iframe>", "iframe", "src")
                .note("An off-origin iframe is not same-origin script execution, but it is an"
                        + " attacker-controlled document inside the page's frame tree, with"
                        + " postMessage, top-level navigation and full-viewport overlay available"
                        + " to it. Same encoder as <img src>.")
                .browserRelevant()
                .build());

        cases.add(recognisedUriAttribute("url.embed-src",
                "<embed src=\"$data\">", "embed", "src")
                .note("<embed> loads a plugin document. Same encoder again, and the tag name Canoe"
                        + " threw away is the only thing that distinguishes it from <img>.")
                .build());

        cases.add(recognisedUriAttribute("url.link-href",
                "<link rel=\"stylesheet\" href=\"$data\">", "link", "href")
                .note("An off-origin stylesheet is not inert: it can lay a full-viewport overlay,"
                        + " exfiltrate DOM content through attribute-selector url() rules, and"
                        + " restyle a form's submit target's surroundings. Same href, same url(),"
                        + " as <a href>.")
                .build());

        // The four substitution positions. url() escapes the same characters wherever the reference
        // sits, and the four positions still behave differently, because what makes an off-origin
        // URL off-origin is its position in the value rather than its bytes.
        cases.add(XssCase.id("url.href-query-parameter")
                .section(A2)
                .template("<a href=\"/search?q=$data\">x</a>")
                .sink(SinkKind.URL, "a", "href")
                .payloads(allUrlPayloads())
                .verdict(Verdict.SAFE)
                .note("Query-parameter position, and every payload is SAFE - including the two that"
                        + " make url.href-full and url.script-src-prefix vulnerable. The reason is"
                        + " not the encoder: //attacker.invalid/x.js survives url() byte for byte"
                        + " here exactly as it does there. It is that the template's own literal"
                        + " '/search?q=' has already committed the URL to the page's origin, so the"
                        + " attacker's authority-looking bytes are query data. This case exists to"
                        + " stop F6 being read as 'a URL-bearing attribute is vulnerable': F6 is"
                        + " reachable only where the payload can reach the AUTHORITY, which is the"
                        + " full-URL and path-prefix positions and not these two.")
                .build());

        cases.add(XssCase.id("url.href-fragment")
                .section(A2)
                .template("<a href=\"/page#$data\">x</a>")
                .sink(SinkKind.URL, "a", "href")
                .payloads(allUrlPayloads())
                .verdict(Verdict.SAFE)
                .note("Fragment position, safe for the same reason as the query one and one step"
                        + " further: everything after the '#' is not even sent to the server. Note"
                        + " that url()'s allowlist passes '#' and '?' naked, so a payload in PATH"
                        + " position can still add a query or a fragment of its own - it just cannot"
                        + " add an authority, which is the only thing that changes origin.")
                .build());

        // href on <base> is recognised, so url() applies - and url() lets a protocol-relative URL
        // through byte for byte, which retargets every relative URL on the rest of the page.
        cases.add(XssCase.id("url.base-href")
                .section(A2)
                .template("<base href=\"$data\"><img src=\"/logo.png\">")
                .sink(SinkKind.URL, "base", "href")
                .payloads(Payloads.families("BASE_HIJACK", "PROTOCOL_RELATIVE", "ABSOLUTE_OFFSITE"))
                .verdict(Verdict.SAFE)
                .finding("F6")
                .overrideFamily("BASE_HIJACK", Verdict.KNOWN_VULNERABLE)
                .override(Payloads.PROTOCOL_RELATIVE, Verdict.KNOWN_VULNERABLE)
                .override(Payloads.ABSOLUTE_OFFSITE_HTTPS, Verdict.KNOWN_VULNERABLE)
                .note("The widest blast radius of any F6 case: <base href> retargets every subsequent"
                        + " relative URL on the page, so one attacker-controlled value moves every"
                        + " script, stylesheet, image and form action to the attacker's origin. The"
                        + " review does not cover <base> specifically; it is F6's mechanism exactly.")
                .browserRelevant()
                .build());

        // F7: the branch that was meant to test for 'content' tests for 'data' instead, so 'data'
        // resolves to ATTR_CONTENT and the value is dropped. Fail-safe, and a functional bug
        // developers will route around with $_x.asis().
        cases.add(XssCase.id("attr.data-on-object")
                .section(A2)
                .template("<object data=\"$data\"></object>")
                .sink(SinkKind.URL, "object", "data")
                .payloads(urlProbe())
                .verdict(Verdict.SUPPRESSED_UNINTENDED)
                .finding("F7")
                .note("Two consequences of one copy-paste: <object data> silently drops its value,"
                        + " and there is no check for 'content' at all - see refresh.meta-content.")
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
                .note("Eleven characters, so this attribute name is also the one that arms F5's"
                        + " buffer residue for whatever follows it - see"
                        + " residue.js-url-armed-buffer.")
                .build());

        // The three names F20's table lists and SinkKind.POLICY's criteria exclude. They live here
        // rather than being deleted, because "we thought about this one and it does not qualify" is
        // information, and because the next reader will otherwise re-derive the same argument.
        cases.add(plainTextAttribute("plain.type", "<script type=\"$data\" src=\"/app.js\"></script>",
                "script", "type", Payloads.family("POLICY_OVERRIDE"))
                .note("Considered for SinkKind.POLICY and rejected. type on <script> is a"
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
                .note("Considered for SinkKind.POLICY and rejected. Retargeting a navigation into a"
                        + " named or new browsing context is behaviour, not a security control being"
                        + " switched off - the closest it comes is that target=_blank implies"
                        + " noopener, and the attribute that undoes THAT is rel, which is why rel is"
                        + " in the policy group and this is not. Recorded as SAFE with the value"
                        + " arriving verbatim, which is the honest description.")
                .build());

        cases.add(plainTextAttribute("plain.formtarget",
                "<form action=\"/save\"><button formtarget=\"$data\">go</button></form>",
                "button", "formtarget", Payloads.family("POLICY_OVERRIDE"))
                .note("The submit-button analogue of plain.target, and rejected from the policy group"
                        + " for the same reason.")
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
     * The URL-bearing names {@code setTagAttributeContext()} has never heard of (F3). Every one falls
     * through to {@code ATTR_HTML}, and the HTML parser undoes that encoding before handing the value
     * to the URL parser — so the attacker recovers every character.
     *
     * <p>The contrast with {@link #recognisedUriAttributes} is the whole finding: {@code href} is
     * protected by {@code url()} and {@code xlink:href} is not, so the safe-by-analogy assumption a
     * developer would make is exactly wrong.
     */
    private static void unrecognisedUrlAttributes(List<XssCase> cases) {

        // The four headline sinks carry the full thirteen payloads, so the per-payload distinctions
        // are pinned exhaustively somewhere.
        cases.add(withC0ControlAccidents(unrecognisedUrlAttribute("url.action",
                "<form action=\"$data\"></form>", "form", "action", allUrlPayloads()))
                .notBrowserObservable(Payloads.VBSCRIPT_URL, Payloads.DATA_URL_HTML,
                        Payloads.VIEW_SOURCE_URL)
                .note("A javascript: URL here runs on submit, and an absolute URL sends the form's"
                        + " contents - including any CSRF token - to the attacker. "
                        + C0_CONTROL_ACCIDENT + " " + DEAD_URL_VECTORS
                        + " " + VIEW_SOURCE_IS_BLOCKED_FROM_CONTENT)
                .browserRelevant()
                .build());

        cases.add(withC0ControlAccidents(unrecognisedUrlAttribute("url.formaction",
                "<form action=\"/save\"><button formaction=\"$data\">go</button></form>",
                "button", "formaction", allUrlPayloads()))
                .notBrowserObservable(Payloads.VBSCRIPT_URL, Payloads.DATA_URL_HTML,
                        Payloads.VIEW_SOURCE_URL)
                .note("formaction overrides the form's own action, so a template that carefully sets"
                        + " action from a constant is still fully controllable. "
                        + C0_CONTROL_ACCIDENT + " " + DEAD_URL_VECTORS
                        + " " + VIEW_SOURCE_IS_BLOCKED_FROM_CONTENT)
                .browserRelevant()
                .build());

        cases.add(withC0ControlAccidents(unrecognisedUrlAttribute("url.srcset",
                "<img srcset=\"$data\" src=\"/i.png\">", "img", "srcset", allUrlPayloads()))
                .notBrowserObservable(Payloads.JS_URL, Payloads.JS_URL_MIXED_CASE,
                        Payloads.JS_URL_LEADING_SPACE, Payloads.VBSCRIPT_URL,
                        Payloads.DATA_URL_HTML, Payloads.VIEW_SOURCE_URL)
                .note("srcset takes precedence over src where the browser supports it, and it is a"
                        + " comma-separated list, so one value can name several attacker origins."
                        + " " + C0_CONTROL_ACCIDENT
                        + " Every live JS_URL row is flagged not-browser-observable here, which is"
                        + " a stronger statement than the flags on url.action and url.formaction and"
                        + " deserves its own sentence: srcset is an image-source list, and an image"
                        + " source is fetched, never navigated to or executed. No srcset candidate"
                        + " has ever run a javascript: URL in any engine, so every one of these rows"
                        + " would be a guaranteed browser-tier failure. The ledger keeps them"
                        + " KNOWN_VULNERABLE because Canoe emitted the attacker's URL live into a"
                        + " URL-bearing attribute it does not recognise, which is F3 exactly; the"
                        + " off-origin rows in this case are the ones a browser will confirm.")
                .browserRelevant()
                .build());

        cases.add(withC0ControlAccidents(unrecognisedUrlAttribute("url.poster",
                "<video poster=\"$data\"></video>", "video", "poster", allUrlPayloads()))
                .note(C0_CONTROL_ACCIDENT)
                .build());

        // F3: isTagNameChar accepts ':', so xlink:href scans as a single attribute name and simply
        // does not match href. It gets ATTR_HTML, which the parser undoes.
        cases.add(withC0ControlAccidents(XssCase.id("url.xlink-href")
                .section(A2)
                .template("<svg><a xlink:href=\"$data\"><text>go</text></a></svg>")
                .sink(SinkKind.URL, "a", "xlink:href")
                .payloads(Payloads.family("JS_URL"))
                .verdict(Verdict.KNOWN_VULNERABLE)
                .finding("F3"))
                .notBrowserObservable(Payloads.VBSCRIPT_URL, Payloads.DATA_URL_HTML,
                        Payloads.VIEW_SOURCE_URL)
                .note(C0_CONTROL_ACCIDENT + " " + DEAD_URL_VECTORS
                        + " " + VIEW_SOURCE_IS_BLOCKED_FROM_CONTENT + " Plain href IS protected by url(), so"
                        + " the safe-by-analogy assumption a developer would make is wrong. And"
                        + " xlink:href is exactly ten characters, so it repairs buf[10] for whatever"
                        + " follows it - see residue.js-url-repaired-by-a-ten-character-name.")
                .browserRelevant()
                .build());

        // The tail: one payload per mechanism. Several of these attributes are legacy and no longer
        // honoured by any shipping browser, which does not change the ledger - the ledger is about
        // what Canoe emitted, not about whether a 2026 engine acts on it.
        // This note used to say the ledger "records what Canoe emitted", which is a second definition
        // of KNOWN_VULNERABLE sitting alongside the plan's, and it was doing real work here: it was
        // the only thing reconciling a vulnerable verdict with a sink no browser dereferences. The
        // reconciliation belongs on its own axis - see XssCase.isBrowserObservable - so the note now
        // states the plan's definition and points at the flag rather than bending the verdict.
        String legacy = "A legacy sink no current browser is known to fetch. Still KNOWN_VULNERABLE"
                + " under the plan's definition, which is that attacker data reaches the sink live:"
                + " Canoe classified a URL-bearing attribute as plain text and the attacker's URL"
                + " arrived at it intact, which is F3 whether or not a 2026 engine acts on it. None"
                + " of these cases is browser-relevant, so nothing is asked of the browser tier here;"
                + " where a dead vector DOES sit in a browser-relevant case it is flagged"
                + " not-browser-observable rather than having its verdict rewritten.";

        cases.add(unrecognisedUrlAttribute("url.cite",
                "<blockquote cite=\"$data\">x</blockquote>", "blockquote", "cite").build());

        cases.add(unrecognisedUrlAttribute("url.ping",
                "<a ping=\"$data\" href=\"/x\">y</a>", "a", "ping")
                .note("ping fires a POST to the named URL on click, with no user-visible effect -"
                        + " the quietest exfiltration channel in this group.")
                .build());

        cases.add(unrecognisedUrlAttribute("url.imagesrcset",
                "<link rel=\"preload\" as=\"image\" imagesrcset=\"$data\">", "link", "imagesrcset")
                .build());

        cases.add(unrecognisedUrlAttribute("url.xml-base",
                "<svg xml:base=\"$data\"><text>x</text></svg>", "svg", "xml:base")
                .note("The SVG analogue of <base href>: it rebases every relative URL in the subtree."
                        + " Scans as one attribute name for the same reason xlink:href does.")
                .build());

        cases.add(unrecognisedUrlAttribute("url.usemap",
                "<img usemap=\"$data\" src=\"/i.png\">", "img", "usemap")
                .note(legacy).build());

        cases.add(unrecognisedUrlAttribute("url.longdesc",
                "<img longdesc=\"$data\" src=\"/i.png\">", "img", "longdesc")
                .note(legacy + " Also worth noting that longdesc fails 'lowsrc' at buf[2]=='n',"
                        + " which is the near-miss that makes the hand-unrolled table hard to audit.")
                .build());

        cases.add(unrecognisedUrlAttribute("url.codebase",
                "<applet codebase=\"$data\"></applet>", "applet", "codebase")
                .note(legacy).build());

        cases.add(unrecognisedUrlAttribute("url.archive",
                "<object archive=\"$data\"></object>", "object", "archive")
                .note(legacy).build());

        cases.add(unrecognisedUrlAttribute("url.classid",
                "<object classid=\"$data\"></object>", "object", "classid")
                .note(legacy).build());

        cases.add(unrecognisedUrlAttribute("url.manifest",
                "<html manifest=\"$data\"><body>x</body></html>", "html", "manifest")
                .note(legacy).build());

        cases.add(unrecognisedUrlAttribute("url.profile",
                "<html><head profile=\"$data\"></head><body>x</body></html>", "head", "profile")
                .note(legacy).build());
    }

    /**
     * The two sinks that are neither plain text nor a URL: an attribute parsed as HTML in its own
     * right, and one that carries a delay and a URL in a single value.
     */
    private static void markupAndRefreshSinks(List<XssCase> cases) {

        // F3: srcdoc is parsed as markup in its own right, so it needs double encoding. Canoe applies
        // single, and the HTML parser undoes it before the iframe document is parsed.
        cases.add(XssCase.id("markup.srcdoc")
                .section(A2)
                .template("<iframe srcdoc=\"<p>$data</p>\"></iframe>")
                .sink(SinkKind.MARKUP, "iframe", "srcdoc")
                .payloads(Payloads.family("SRCDOC_MARKUP"))
                .verdict(Verdict.KNOWN_VULNERABLE)
                .finding("F3")
                .note("Same-origin XSS: the iframe document parses and executes the decoded markup.")
                .browserRelevant()
                .build());

        cases.add(XssCase.id("markup.srcdoc-whole-value")
                .section(A2)
                .template("<iframe srcdoc=\"$data\"></iframe>")
                .sink(SinkKind.MARKUP, "iframe", "srcdoc")
                .payloads(Payloads.families("SRCDOC_MARKUP", "TAG_BREAKOUT"))
                .verdict(Verdict.KNOWN_VULNERABLE)
                .finding("F3")
                .note("The whole iframe document, rather than a fragment inside one. Same mechanism;"
                        + " included because the template shape a developer reaches for first is"
                        + " srcdoc=\"$html\", not srcdoc=\"<p>$name</p>\".")
                .browserRelevant()
                .build());

        // F3's content row, and the other half of F7: there is no check for 'content' at all, because
        // the branch that should hold it tests for 'data'.
        cases.add(XssCase.id("refresh.meta-content")
                .section(A2)
                .template("<meta http-equiv=\"refresh\" content=\"$data\">")
                .sink(SinkKind.REFRESH, "meta", "content")
                .payloads(Payloads.family("META_REFRESH"))
                .verdict(Verdict.KNOWN_VULNERABLE)
                .finding("F3")
                .note("A forced top-level navigation, which needs no click and no script. The"
                        + " missing 'content' branch is F7's second consequence; the impact is F3's.")
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
     * are {@code plain.*} cases now, each with the reasoning kept rather than deleted.
     * {@code nonce} was moved <em>in</em>, from the plain-text group — see {@link #policyNonce}.
     */
    private static void policyAttributes(List<XssCase> cases) {

        cases.add(policyAttribute("policy.sandbox",
                "<iframe sandbox=\"$data\" src=\"/user-content\"></iframe>", "iframe", "sandbox")
                .override(Payloads.POLICY_SANDBOX_ESCAPE, Verdict.KNOWN_VULNERABLE)
                .note("The one row in this group with a Critical-class outcome: allow-scripts plus"
                        + " allow-same-origin removes the sandbox entirely, and the framed document is"
                        + " then same-origin script execution. A template that derives the sandbox"
                        + " level from data - a permissions setting, a plan tier, a preview mode - is"
                        + " handing the attacker the sandbox. The other two payloads arrive just as"
                        + " verbatim and are SAFE, because 'opener' and '_blank' are not sandbox"
                        + " tokens: an unrecognised token leaves the sandbox maximally restrictive,"
                        + " which is the opposite of an escape. Recording those as vulnerabilities"
                        + " because the bytes survived is the mistake a byte-counting oracle makes.")
                .browserRelevant()
                .build());

        cases.add(policyAttribute("policy.rel",
                "<a rel=\"$data\" target=\"_blank\" href=\"/x\">y</a>", "a", "rel")
                .override(Payloads.POLICY_REL_OPENER, Verdict.KNOWN_VULNERABLE)
                .note("rel=opener undoes the implicit noopener that target=_blank carries, which"
                        + " restores window.opener and with it reverse tabnabbing - and it is the"
                        + " only reason rel is in this group at all, since most link types are"
                        + " behavioural. The other two payloads are not link types, so they are"
                        + " ignored: the link relation list is an allowlist, and an unknown relation"
                        + " is dropped rather than honoured.")
                .build());

        cases.add(policyAttribute("policy.integrity",
                "<script src=\"/app.js\" integrity=\"$data\"></script>", "script", "integrity")
                .note("Subresource integrity is a security control the template author added"
                        + " deliberately, and an attacker who controls it can set a wrong digest and"
                        + " block the script. None of these three payloads does that, which is the"
                        + " row worth reading twice: SRI parses the attribute into a set of"
                        + " <algorithm>-<base64> expressions and discards what it cannot parse, and"
                        + " an EMPTY metadata set makes the check pass unconditionally. An"
                        + " unparseable integrity attribute is not a failing digest, it is no digest,"
                        + " so the resource loads exactly as it would have. All three arrive"
                        + " verbatim and none of them is live. A payload of the form sha256-<junk>"
                        + " would flip this case, and there is deliberately no such payload yet -"
                        + " when one is added, this note is the reviewed reason its verdict differs.")
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
                .verdict(Verdict.KNOWN_VULNERABLE)
                .note("Unlike every other attribute in this group, nonce has no token vocabulary:"
                        + " the whole value is the directive, so every payload that arrives is live"
                        + " by construction and there is no inert combination to record. The"
                        + " POLICY_OVERRIDE payloads are used rather than nonce-shaped strings"
                        + " because the point is verbatim arrival, and their character set - letters,"
                        + " digits, hyphens, underscores, a space - is the same set a base64 nonce"
                        + " draws from. Canoe's own part still holds: the value cannot break out of"
                        + " the attribute. It does not have to."
                        + " All three payloads are flagged not-browser-observable, and the reason is"
                        + " structural rather than a dead engine: a nonce does nothing at all unless"
                        + " the response carries a Content-Security-Policy naming one, and this"
                        + " template has no author nonce for a policy to name. The browser tier"
                        + " serves what Canoe rendered; adding a CSP header would be the tier"
                        + " editing the document under test, and a header naming the ATTACKER's"
                        + " nonce would be assuming the conclusion. Demonstrating F20's nonce row in"
                        + " a browser needs a different template - an author nonce in the policy and"
                        + " a second, attacker-controlled script element - which the corpus does not"
                        + " have. Recorded here rather than left as a browser-tier failure nobody"
                        + " could act on. Measured in Chromium by BrowserCorpusTest.")
                .notBrowserObservableFamily("POLICY_OVERRIDE")
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
        cases.add(XssCase.id("separator.duplicate-attribute")
                .section(A2)
                .template("<a href=\"/safe\" href=\"$data\">x</a>")
                .sink(SinkKind.URL, "a", "href")
                .payloads(allUrlPayloads())
                .verdict(Verdict.SAFE)
                .note("Canoe classifies each occurrence independently and has no notion of a"
                        + " duplicate, so the second href gets ATTR_URI just like the first and the"
                        + " emitted bytes are byte-identical to url.href-full's. What makes this SAFE"
                        + " is the parser, not the encoder: the duplicate is dropped before any URL"
                        + " is resolved. Swap the two attributes and every verdict here flips to"
                        + " url.href-full's - which is why the encoding is worth recording even"
                        + " though today's outcome is safe.")
                .build());

        // ...and the ordering the note above describes only in prose, as a case. This is the
        // dangerous half of the pair: the parser keeps the FIRST occurrence, so here the attacker's
        // value is the one that survives and the template author's /safe is the one discarded.
        // Canoe's output is the same shape either way; only the order decides.
        cases.add(XssCase.id("separator.duplicate-attribute-reversed")
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
                        + " ordering being SAFE is the kind of result that gets generalised.")
                .build());

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
                        + " down. This is the same defect as the <br/> row in F13's table, reached"
                        + " from the attribute side.")
                .build());
    }

    // ------------------------------------------------------------------
    // A.4 Attribute value prefixes
    // ------------------------------------------------------------------

    /**
     * The CSS half of F4. {@code detectAttributePrefix()} resets {@code attributeContext} to {@code
     * ATTR_HTML} unconditionally on the first colon at value index 0–10, and colons are the basic
     * syntax of a CSS declaration — so writing a property name in front of the reference silently
     * converts {@code style} from "suppress" to "HTML-encode".
     *
     * <p>The colon position decides the outcome, and the boundary is index 10 inclusive, because
     * {@code c == ':'} is tested before the {@code bufLen == 10} cutoff. {@code AttributePrefixTest}
     * pins every index from 0 to 12; the cases here are the end-to-end ones on either side of it.
     */
    private static void cssContexts(List<XssCase> cases) {

        cases.add(XssCase.id("css.style-with-property")
                .section(A4)
                .template("<div style=\"color:$data\">x</div>")
                .sink(SinkKind.CSS, "div", "style")
                .payloads(Payloads.family("CSS_INJECTION"))
                .verdict(Verdict.KNOWN_VULNERABLE)
                .finding("F4")
                .notBrowserObservable(Payloads.CSS_EXPRESSION)
                .note("color: puts the colon at index 5. This is a complete defeat of the"
                        + " refuse-to-output-into-CSS guarantee the original design documents call"
                        + " Canoe's centrepiece. " + EXPRESSION_IS_DEAD)
                .browserRelevant()
                .build());

        cases.add(XssCase.id("css.style-bare")
                .section(A4)
                .template("<div style=\"$data\">x</div>")
                .sink(SinkKind.CSS, "div", "style")
                .payloads(Payloads.family("CSS_INJECTION"))
                .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                .note("No preceding colon, so the name-derived ATTR_CSS survives and output is empty.")
                .build());

        cases.add(XssCase.id("css.style-width")
                .section(A4)
                .template("<div style=\"width:$data\">x</div>")
                .sink(SinkKind.CSS, "div", "style")
                .payloads(Payloads.family("CSS_INJECTION"))
                .verdict(Verdict.KNOWN_VULNERABLE)
                .finding("F4")
                .note("Colon at index 5.")
                .build());

        cases.add(XssCase.id("css.style-margin")
                .section(A4)
                .template("<div style=\"margin:$data\">x</div>")
                .sink(SinkKind.CSS, "div", "style")
                .payloads(Payloads.family("CSS_INJECTION"))
                .verdict(Verdict.KNOWN_VULNERABLE)
                .finding("F4")
                .note("Colon at index 6.")
                .build());

        cases.add(XssCase.id("css.style-display")
                .section(A4)
                .template("<div style=\"display:$data\">x</div>")
                .sink(SinkKind.CSS, "div", "style")
                .payloads(Payloads.family("CSS_INJECTION"))
                .verdict(Verdict.KNOWN_VULNERABLE)
                .finding("F4")
                .note("Colon at index 7, the same index as padding: - which is the point of having"
                        + " both. F4's precondition is a character count, not a property, so two"
                        + " properties that share an index have to agree or the model is wrong.")
                .build());

        cases.add(XssCase.id("css.style-position")
                .section(A4)
                .template("<div style=\"position:$data\">x</div>")
                .sink(SinkKind.CSS, "div", "style")
                .payloads(Payloads.family("CSS_INJECTION"))
                .verdict(Verdict.KNOWN_VULNERABLE)
                .finding("F4")
                .note("Colon at index 8.")
                .build());

        cases.add(XssCase.id("css.style-padding")
                .section(A4)
                .template("<div style=\"padding:$data\">x</div>")
                .sink(SinkKind.CSS, "div", "style")
                .payloads(Payloads.family("CSS_INJECTION"))
                .verdict(Verdict.KNOWN_VULNERABLE)
                .finding("F4")
                .note("Colon at index 7.")
                .build());

        cases.add(XssCase.id("css.style-font-size")
                .section(A4)
                .template("<div style=\"font-size:$data\">x</div>")
                .sink(SinkKind.CSS, "div", "style")
                .payloads(Payloads.family("CSS_INJECTION"))
                .verdict(Verdict.KNOWN_VULNERABLE)
                .finding("F4")
                .note("Colon at index 9.")
                .build());

        // The boundary itself, from the vulnerable side.
        cases.add(XssCase.id("css.style-background")
                .section(A4)
                .template("<div style=\"background:$data\">x</div>")
                .sink(SinkKind.CSS, "div", "style")
                .payloads(Payloads.family("CSS_INJECTION"))
                .verdict(Verdict.KNOWN_VULNERABLE)
                .finding("F4")
                .notBrowserObservable(Payloads.CSS_EXPRESSION)
                .note("Colon at index 10 - the last position that still triggers, because c == ':'"
                        + " is evaluated before the bufLen == 10 cutoff. The review corrected itself"
                        + " on this exact case; it is affected. " + EXPRESSION_IS_DEAD)
                .browserRelevant()
                .build());

        // ...and from the safe side, one character further along.
        cases.add(XssCase.id("css.style-font-family-quoted")
                .section(A4)
                .template("<div style=\"font-family:'$data'\">x</div>")
                .sink(SinkKind.CSS, "div", "style")
                .payloads(Payloads.family("CSS_INJECTION"))
                .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                .note("font-family is eleven characters, so the colon sits at index 11 and the scan"
                        + " has already given up (bufLen was set to -1 at index 10). ATTR_CSS"
                        + " survives and the value is suppressed. One character decides it.")
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

        // Only the FIRST colon matters, so a complete declaration in front of the reference is still
        // vulnerable - the scan has already fired and set bufLen to -1.
        cases.add(XssCase.id("css.style-after-a-complete-declaration")
                .section(A4)
                .template("<div style=\"color:red;background:$data\">x</div>")
                .sink(SinkKind.CSS, "div", "style")
                .payloads(Payloads.family("CSS_INJECTION"))
                .verdict(Verdict.KNOWN_VULNERABLE)
                .finding("F4")
                .note("detectAttributePrefix() runs once, on the first colon, and sets bufLen to -1"
                        + " so nothing later in the value is examined. The reference's own position"
                        + " in the value is irrelevant; only the first colon's is.")
                .build());

        // Inside a quoted CSS string, which is the shape a template author reaches for when they
        // think quoting will contain the value. It does not: html() turns the apostrophe into &#39;
        // and the HTML parser gives it back to the CSS parser as a real quote.
        cases.add(XssCase.id("css.style-inside-a-quoted-css-string")
                .section(A4)
                .template("<div style=\"content:'$data'\">x</div>")
                .sink(SinkKind.CSS, "div", "style")
                .payloads(Payloads.family("CSS_INJECTION"))
                .verdict(Verdict.KNOWN_VULNERABLE)
                .finding("F4")
                .notBrowserObservableFamily("CSS_INJECTION")
                .note("Colon at index 7, so the reset fires and html() applies. The CSS string"
                        + " literal around the reference is not a mitigation - it is the same"
                        + " situation as a JavaScript string literal in an event handler, and F1's"
                        + " whole mechanism. Contrast css.style-font-family-quoted, which is also a"
                        + " quoted CSS string and IS suppressed, because font-family: puts the colon"
                        + " at index 11. Two templates that differ only in a property name, one"
                        + " injectable and one not: the quoting decides nothing and the character"
                        + " count decides everything. " + EXPRESSION_IS_DEAD
                        + " " + THE_CSS_CONTAINER_DECIDES
                        + " Here the container is a CSS string, and none of the CSS_INJECTION"
                        + " payloads carries an apostrophe, so all three stay inside it: the browser"
                        + " sees one enormous string value for content: on a div, which is not even"
                        + " a pseudo-element, and issues no request. The whole family is therefore"
                        + " flagged. The verdict is unchanged and the sentence above is unchanged -"
                        + " a payload that DID carry an apostrophe would escape, because html()"
                        + " writes &#39; and the parser hands back a real quote - but the corpus's"
                        + " CSS payloads are written to demonstrate the declaration-level attack,"
                        + " and this container defeats that one specific shape.")
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
                .verdict(Verdict.KNOWN_VULNERABLE)
                .finding("F4")
                .notBrowserObservableFamily("CSS_INJECTION")
                .notBrowserObservable(Payloads.PROTOCOL_RELATIVE_BACKSLASH,
                        Payloads.PROTOCOL_RELATIVE_DOUBLE_BACKSLASH)
                .note("F4's concrete impact in one template: an attacker-chosen URL inside a CSS"
                        + " url() is a request to their origin on every render, which is how CSS"
                        + " exfiltration of DOM content is bootstrapped. PROTOCOL_RELATIVE/slashes"
                        + " is the row that demonstrates it, and it is the only one of the six here"
                        + " that a browser acts on."
                        + " " + CSS_BACKSLASH_IS_AN_ESCAPE + " " + EXPRESSION_IS_DEAD
                        + " " + THE_CSS_CONTAINER_DECIDES
                        + " Here the container is an unquoted url() token, and it voids four of the"
                        + " six rows in two different ways, both measured in Chromium by"
                        + " BrowserCorpusTest. The three CSS_INJECTION payloads each contain a"
                        + " literal '(' from their own nested url(...), and a '(' inside an unquoted"
                        + " url token makes it a bad-url-token, so the whole declaration is dropped"
                        + " and nothing is fetched at all. The two backslash spellings are eaten by"
                        + " CSS's escape syntax, which is what CSS_BACKSLASH_IS_AN_ESCAPE says: the"
                        + " browser requested /ttacker.invalid/x.js for the /\\ form - the escape"
                        + " consumed the 'a' of the host as a hex digit, producing U+000A, which"
                        + " the URL parser then removes from anywhere in a URL - and"
                        + " /attacker.invalid/x.js for the"
                        + " \\\\ form, where \\\\ unescapes to one backslash and the URL parser then"
                        + " reads it as a path separator. Both stay on the page's own origin.")
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

        // F17. The colon-triggered reset does not only widen ATTR_CSS and ATTR_URI; it widens
        // ATTR_JS, which is the one classification Canoe gets right. onclick is a recognised handler,
        // it resolves to ATTR_JS -> CTX_JS -> the empty string, and then a colon anywhere in the
        // first eleven characters of the value throws that answer away and html() takes over. The
        // HTML parser undoes html() before the JavaScript parser runs.
        //
        // The whole group is deliberately three cases: two shapes that fire and one that does not,
        // differing only in how many characters precede the colon. That is the finding's real
        // character - the boundary is positional, not semantic, so it cannot be reasoned about from
        // what the handler DOES, only measured.
        cases.add(XssCase.id("prefix.colon-in-a-recognised-handler")
                .section(A4)
                .template("<a onclick=\"f({a:1,b:'$data'})\">x</a>")
                .sink(SinkKind.JAVASCRIPT, "a", "onclick")
                .payloads(Payloads.family("QUOTE_BREAKOUT"))
                .verdict(Verdict.KNOWN_VULNERABLE)
                .finding("F17")
                .note("An object literal in a handler body puts the colon at value index 4, inside"
                        + " the 0-10 window, so detectAttributePrefix() resets attributeContext to"
                        + " ATTR_HTML, matches none of its five prefixes, and leaves the value to"
                        + " html(). Decoded, the handler reads f({a:1,b:'');__canoePwned('q');//'})."
                        + " Compare handler.onclick, which is the SAME attribute with a colon-free"
                        + " body and is suppressed - which is why spot-checking onclick concludes the"
                        + " mechanism works. Note also that replacing the on* table with a prefix"
                        + " rule (remediation item 2) does nothing here: the name is already being"
                        + " classified correctly and the value scan discards the answer afterwards."
                        + " Only deleting the reset closes it, which is why that item was moved to"
                        + " first."
                        + " Both payloads are flagged not-browser-observable, and the single-quote"
                        + " one needs its own reading, because it is the only row in the corpus whose"
                        + " flag is about the SHAPE of the injection rather than about a dead engine."
                        + " f({a:1,b:'');__canoePwned('q');//'}) is a SyntaxError: the payload closes"
                        + " the string literal and then the call's parenthesis, leaving the object"
                        + " literal unclosed, so the handler never compiles and nothing at all runs."
                        + " The attacker's characters are live - a payload written for this position,"
                        + " '});__canoePwned('f17');//, does execute, and"
                        + " SinkSpecificBrowserTest.f17IsExploitableWithAPayloadShapedForItsPosition"
                        + " runs it in a real browser rather than leaving the claim as prose. The"
                        + " corpus keeps the family payload because the corpus is about what Canoe"
                        + " does with a shared catalogue of hostile strings; the bespoke test is"
                        + " about what the browser does with one written for this template."
                        + " " + A_DOUBLE_QUOTE_CANNOT_CLOSE_A_SINGLE_QUOTED_LITERAL)
                .notBrowserObservableFamily("QUOTE_BREAKOUT")
                .browserRelevant()
                .build());

        cases.add(XssCase.id("prefix.url-literal-in-a-recognised-handler")
                .section(A4)
                .template("<a onclick=\"go('http://x'+'$data')\">x</a>")
                .sink(SinkKind.JAVASCRIPT, "a", "onclick")
                .payloads(Payloads.family("QUOTE_BREAKOUT"))
                .verdict(Verdict.KNOWN_VULNERABLE)
                .finding("F17")
                .note("The second F17 shape, and the one that shows what kind of boundary this is: a"
                        + " URL literal in the handler puts the colon of http: at index 8. Rename the"
                        + " function from go() to open() - two characters longer - and the colon"
                        + " moves to index 11, the scan has already given up, and the identical"
                        + " handler is suppressed. Nothing about what the code does changed. Decoded,"
                        + " this one reads go('http://x'+'');__canoePwned('q');//')."
                        + " " + A_DOUBLE_QUOTE_CANNOT_CLOSE_A_SINGLE_QUOTED_LITERAL)
                .notBrowserObservable(Payloads.QUOTE_DOUBLE_BREAKOUT)
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
                        + " the behaviour F5 takes away by changing nothing but the page around it."
                        + " The bare form; residue.js-url-clean-buffer is the same href preceded by"
                        + " an element, which is what makes it a statement about buf rather than"
                        + " about the prefix table.")
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
                .note("mocha is checked at buf[5] rather than buf[10], so it has its own, shorter"
                        + " residue window - see residue.data-url-armed-buffer for the buf[4] one.")
                .build());

        cases.add(XssCase.id("prefix.asfunction")
                .section(A4)
                .template("<a href=\"asfunction:f('$data')\">x</a>")
                .sink(SinkKind.JAVASCRIPT, "a", "href")
                .payloads(Payloads.family("QUOTE_BREAKOUT"))
                .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                .note("An ActionScript scheme from the Flash era. Still in the table; srcset is not.")
                .build());

        // The scheme the table does NOT know, in a position where the name-derived ATTR_URI would
        // have handled it. This is F4's second consequence, on its own.
        cases.add(XssCase.id("prefix.vbscript-not-in-the-table")
                .section(A4)
                .template("<a href=\"vbscript:f('$data')\">x</a>")
                .sink(SinkKind.JAVASCRIPT, "a", "href")
                .payloads(Payloads.family("QUOTE_BREAKOUT"))
                .verdict(Verdict.KNOWN_VULNERABLE)
                .finding("F4")
                .note("The colon fires detectAttributePrefix(), which resets to ATTR_HTML and then"
                        + " matches none of its five prefixes - so a scheme the table has never heard"
                        + " of ends up LESS suppressed than one it has. Without the reset the"
                        + " name-derived ATTR_URI would have applied url() and percent-escaped the"
                        + " quotes; with it, html() applies and the parser hands the VBScript engine"
                        + " the attacker's original characters."
                        + " Both payloads are flagged not-browser-observable for the reason the"
                        + " sentence above states outright and the flag had not caught up with:"
                        + " there is no VBScript engine left in any shipping browser, so nothing"
                        + " parses the href at all and a click navigates nowhere. Same reasoning as"
                        + " the JS_URL/vbscript flags on url.action and url.formaction; measured in"
                        + " Chromium by BrowserCorpusTest.")
                .notBrowserObservableFamily("QUOTE_BREAKOUT")
                .browserRelevant()
                .build());

        // The URI downgrade F4 describes, with the reason it is not exploitable in this shape spelled
        // out rather than left implied.
        cases.add(XssCase.id("prefix.https-downgrades-url-to-html")
                .section(A4)
                .template("<a href=\"https://app.example/$data\">x</a>")
                .sink(SinkKind.URL, "a", "href")
                .payloads(Payloads.families("PROTOCOL_RELATIVE", "ABSOLUTE_OFFSITE"))
                .verdict(Verdict.SAFE)
                .finding("F4")
                .note("The ':' in 'https:' sits at index 5 and fires the reset, so this href is"
                        + " html-encoded rather than percent-encoded - a silent change of encoder"
                        + " that no template author asked for. SAFE only because the template's own"
                        + " trailing '/' pins the payload into the path: url() and html() differ here"
                        + " in what they do to '&', '%' and non-ASCII, which is query-parameter and"
                        + " path manipulation rather than origin control. The verdict would change"
                        + " if the template ended at the host.")
                .build());

        // The prefix window is ten characters wide, and a payload can sit right on its edge.
        cases.add(XssCase.id("prefix.payload-at-the-window-boundary")
                .section(A4)
                .template("<a href=\"$data\">x</a>")
                .sink(SinkKind.URL, "a", "href")
                .payloads(Payloads.families("LENGTH_STRESS", "UNICODE_EDGE"))
                .verdict(Verdict.SAFE)
                .note("The payload is the whole value, so it drives the prefix scan itself. Ten"
                        + " characters then a colon is the last position that reaches"
                        + " detectAttributePrefix(); it matches no prefix, and url() has already"
                        + " escaped the colon to %3A, so the result is a relative path. The homoglyph"
                        + " colons are safe twice over: url() replaces every code point above 255"
                        + " with a literal '?' (F15d), so they are not colons by the time any parser"
                        + " sees them.")
                .build());
    }

    // ------------------------------------------------------------------
    // A.4 buffer residue
    // ------------------------------------------------------------------

    /**
     * F5, as three templates that differ only in the elements around the one under test.
     *
     * <p>{@code buf} is a 36-character field shared across the whole render and never cleared; only
     * {@code bufLen} is reset. The {@code TAG_ATTR_VALUE} path never writes a NUL terminator, and the
     * value scan can only ever write indices 0–9, so a value can never repair {@code buf[10]} itself.
     * Whether {@code javascript:} is recognised therefore depends on what an earlier, unrelated
     * attribute name left there.
     */
    private static void bufferResidue(List<XssCase> cases) {

        // The three cases below differ only in the attribute name of the element in front of the one
        // under test - two characters, eleven, then eleven repaired by ten - which is the whole of
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
                .note("A preceding element is not enough to arm F5; the preceding attribute NAME has"
                        + " to be long enough. 'id' writes buf[0..1] and its terminator at buf[2],"
                        + " and the value scan can only ever write indices 0-9, so buf[10] still"
                        + " holds the zero it was initialised with. The javascript: check reads"
                        + " buf[10], matches, and the value is suppressed. This is what makes F5"
                        + " survive casual testing: the page looks exactly like the vulnerable one.")
                .build());

        cases.add(XssCase.id("residue.js-url-armed-buffer")
                .section(A4)
                .template("<input placeholder=\"Search\">"
                        + "<a href=\"javascript:f('$data')\">details</a>")
                .sink(SinkKind.JAVASCRIPT, "a", "href")
                .payloads(Payloads.family("QUOTE_BREAKOUT"))
                .verdict(Verdict.KNOWN_VULNERABLE)
                .finding("F5")
                .note("placeholder is 11 characters, so it writes buf[0..10] leaving buf[10]='r' and"
                        + " its terminator at buf[11]; the javascript: check reads buf[10] and fails."
                        + " Identical template to residue.js-url-clean-buffer - only the order of two"
                        + " elements differs, and that changes whether the page is safe."
                        + " " + A_DOUBLE_QUOTE_CANNOT_CLOSE_A_SINGLE_QUOTED_LITERAL)
                .notBrowserObservable(Payloads.QUOTE_DOUBLE_BREAKOUT)
                .browserRelevant()
                .build());

        // The half of F5 that is easiest to disbelieve: an unrelated element can put the page BACK
        // into a safe state, because its attribute name is exactly the right length.
        cases.add(XssCase.id("residue.js-url-repaired-by-a-ten-character-name")
                .section(A4)
                .template("<input placeholder=\"Search\">"
                        + "<a xlink:href=\"/x\">y</a>"
                        + "<a href=\"javascript:f('$data')\">details</a>")
                .sink(SinkKind.JAVASCRIPT, "a", "href")
                .payloads(Payloads.family("QUOTE_BREAKOUT"))
                .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                .note("The same page as residue.js-url-armed-buffer with one extra link in the"
                        + " middle. xlink:href is exactly ten characters, so its NUL terminator lands"
                        + " on buf[10] and repairs what placeholder broke. Deleting an unrelated"
                        + " element from this page makes it vulnerable; that is the action at a"
                        + " distance F5 describes, and it is why the fix is to clear the buffer"
                        + " rather than to lengthen any particular check.")
                .build());

        // The shorter residue windows. 'data' is checked at buf[4], so it is armed by any preceding
        // attribute name of five characters or more - and repaired by 'href', whose own terminator
        // lands there.
        cases.add(XssCase.id("residue.data-url-clean-buffer")
                .section(A4)
                .template("<a href=\"data:$data\">x</a>")
                .sink(SinkKind.URL, "a", "href")
                .payloads(Payloads.families("SRCDOC_MARKUP", "TAG_BREAKOUT"))
                .verdict(Verdict.SUPPRESSED_BY_DESIGN)
                .note("href is four characters, so its NUL terminator sits at buf[4] - which is"
                        + " exactly the index the 'data' prefix check reads. The prefix matches,"
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
                .verdict(Verdict.KNOWN_VULNERABLE)
                .finding("F5")
                .notBrowserObservableFamily("SRCDOC_MARKUP", "TAG_BREAKOUT")
                .note("Every payload here is flagged not-browser-observable, and this is the case"
                        + " where that flag earns its keep: the sink is a data: URL in a background"
                        + " IMAGE attribute, the payloads are markup, and no browser renders markup"
                        + " as a background image - it decodes the data: URL, fails to recognise it"
                        + " as an image, and stops. Nothing executes, nothing is fetched, and no"
                        + " detector can fire. The ledger entry is still correct and still the point:"
                        + " Canoe let the attacker complete an arbitrary data: URL. Compare"
                        + " markup.srcdoc, where the same payload family reaches a sink that DOES"
                        + " parse markup and is fully browser-observable. "
                        + "'background' is ten characters, so buf[4] holds its 'g' rather than a"
                        + " terminator and the 'data' check fails - while the reset that ran first"
                        + " has already discarded the name-derived ATTR_URI. html() applies, the"
                        + " parser decodes it, and the attacker completes an arbitrary data: URL."
                        + " Any URI attribute name of five characters or more does this; href, at"
                        + " four, does not. The impact here is a resource load rather than script"
                        + " execution, because a background image is not a document - the point of"
                        + " the case is the buf[4] window, which the review records alongside"
                        + " buf[10] but which had no executable case until now.")
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
     * half-written response looks like. Per F13 a rejection is not a degraded page — the
     * {@code [Encoding Error]} recovery branch is unreachable, so it is an unhandled 500.
     */
    private static void malformedTemplates(List<XssCase> cases) {

        // The XHTML-style void elements. '/' immediately after a tag name is rejected; '<br />' and
        // '<img src="a.png"/>' are both fine.
        cases.add(rejected("reject.void-br", "<p>$data</p><br/>")
                .note("'Invalid character after tag name'. <br /> with a space is accepted, so this"
                        + " is a whitespace-sensitive rejection of the most common void-element"
                        + " spelling in the wild.")
                .build());

        cases.add(rejected("reject.void-hr", "<p>$data</p><hr/>").build());

        cases.add(rejected("reject.void-img", "<p>$data</p><img/>").build());

        cases.add(rejected("reject.bare-less-than-in-body", "<p>5 < 6 $data</p>")
                .note("'Tag name too short'. A literal '<' in body text kills the render - which is"
                        + " also, read the other way, the check that makes body context safe.")
                .build());

        cases.add(rejected("reject.closing-tag-with-space", "<p>$data</p></ p>").build());

        cases.add(rejected("reject.empty-closing-tag", "<p>$data</p></>").build());

        // The MAX_TAGNAME_LEN boundary, from both sides. The constant is 36 and buf is 36 long, but
        // the check fires at bufLen == buf.length - 1, so the real limit is 35 characters.
        cases.add(XssCase.id("shape.tag-name-at-the-limit")
                .section(A7)
                .template("<" + repeat('a', 35) + ">$data")
                .textSink(repeat('a', 35))
                .payloads(Payloads.family("LENGTH_STRESS"))
                .verdict(Verdict.SAFE)
                .note("35 characters is the longest tag name that parses. MAX_TAGNAME_LEN reads 36,"
                        + " but the check is bufLen == buf.length - 1 and the name needs a NUL"
                        + " terminator, so 36 is one too many. Left unclosed on purpose: see"
                        + " reject.closing-tag-name-at-the-limit for why it cannot be closed.")
                .build());

        cases.add(rejected("reject.tag-name-over-the-limit", "<" + repeat('a', 36) + ">$data")
                .note("'Tag name too long' at the 36th character. F13's table gives"
                        + " <data-widget-configuration-attribute-name> as the example; the boundary"
                        + " is pinned here.")
                .build());

        cases.add(rejected("reject.closing-tag-name-at-the-limit",
                "<" + repeat('a', 35) + ">$data</" + repeat('a', 35) + ">")
                .note("The 35-character opening tag above parses; the matching CLOSING tag does not,"
                        + " because buf[0] holds the '/' and only 34 characters of name fit after it."
                        + " So an element name of exactly 35 characters can be opened and can never"
                        + " be closed. An availability nuance of the 'Tag name too long' defect"
                        + " already in F13's table rather than a finding of its own, but a surprising"
                        + " one, and it is pinned here so a future fix to the limit has to decide"
                        + " about both ends.")
                .build());

        cases.add(XssCase.id("shape.attribute-name-at-the-limit")
                .section(A7)
                .template("<p " + repeat('a', 35) + "=\"1\">$data</p>")
                .textSink("p")
                .payloads(Payloads.family("LENGTH_STRESS"))
                .verdict(Verdict.SAFE)
                .note("Attribute names share buf and therefore share the limit: 35 parses, 36 does"
                        + " not.")
                .build());

        cases.add(rejected("reject.attribute-name-over-the-limit",
                "<p " + repeat('a', 36) + "=\"1\">$data</p>")
                .note("'Attribute name too long'.")
                .build());

        // DOCTYPE placement. tagCount counts every '<' seen in HTML state, comments included.
        cases.add(rejected("reject.doctype-after-an-element", "<html><!DOCTYPE html><p>$data</p>")
                .note("Correct to reject, and the message says so plainly.")
                .build());

        cases.add(rejected("reject.doctype-after-a-comment",
                "<!-- c --><!DOCTYPE html><p>$data</p>")
                .finding("F18")
                .note("F18: a licence header, an editor marker or a generator stamp above the DOCTYPE"
                        + " is legal HTML and common in template files, and it takes the whole page"
                        + " down. The check wants 'no element has been emitted yet', not 'no < has"
                        + " been seen yet'.")
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

        // F10, both directions. Neither is attacker-reachable today, precisely because attacker data
        // can never emit a raw '<' - the property T23 guards.
        cases.add(XssCase.id("desync.script-end-tag-with-a-suffix")
                .section(A7)
                .template("<script>x=1;</scriptfoo>$data")
                .textSink("script")
                .payloads(Payloads.family("TAG_BREAKOUT"))
                .verdict(Verdict.SAFE)
                .finding("F10")
                .note("SCRIPT_END matches the seven characters '/script' and returns to HTML without"
                        + " checking what follows, so Canoe believes the script ended and encodes for"
                        + " CTX_HTML. Every browser stays in script data. SAFE anyway, and for a"
                        + " reason worth stating: htmlWhite() output inside script TEXT is not"
                        + " entity-decoded, so &#39; stays literal - syntax errors, not a string"
                        + " breakout. That reasoning holds only while no encoder can emit a raw '<',"
                        + " which is the property ParserSteeringTest (T23) exists to guard.")
                .browserRelevant()
                .build());

        cases.add(XssCase.id("desync.script-stuck-on-a-double-less-than")
                .section(A7)
                .template("<script>x = 1 <</script><p>$data</p>")
                .textSink("p")
                .payloads(Payloads.family("TAG_BREAKOUT"))
                .verdict(Verdict.SUPPRESSED_UNINTENDED)
                .finding("F10")
                .note("The converse desync. SCRIPT_END mismatches on the second '<' and returns to"
                        + " SCRIPT without re-processing that character, so the real </script> is"
                        + " never seen and every reference for the rest of the page is suppressed."
                        + " Same shape as F14, different state.")
                .build());

        cases.add(XssCase.id("desync.style-end-tag-with-a-suffix")
                .section(A7)
                .template("<style>a{}</stylefoo>$data")
                .textSink("style")
                .payloads(Payloads.families("CSS_INJECTION", "CSS_IMPORT"))
                .verdict(Verdict.SAFE)
                .finding("F10")
                .note("CSS_END has the identical defect with '/style'. Safe for the RAWTEXT reason:"
                        + " the browser never decodes character references inside a style element, so"
                        + " the entity-encoded payload is inert text rather than CSS.")
                .build());

        cases.add(XssCase.id("desync.style-stuck-on-a-double-less-than")
                .section(A7)
                .template("<style>a{} <</style><p>$data</p>")
                .textSink("p")
                .payloads(Payloads.family("CSS_INJECTION"))
                .verdict(Verdict.SUPPRESSED_UNINTENDED)
                .finding("F10")
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
                        + " 36-character buffer, so depth costs it nothing.")
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
