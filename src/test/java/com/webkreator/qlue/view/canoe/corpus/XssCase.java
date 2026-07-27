package com.webkreator.qlue.view.canoe.corpus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One template, the sink it places a reference in, the payloads worth attacking it with, and the
 * reviewed verdict for each.
 *
 * <p>The verdict is per case <em>and payload</em>, not per case: {@code <a href="$data">} is safe
 * against {@code javascript:alert(1)} — since R12 {@code url()} rejects the scheme to the empty
 * string — and vulnerable against
 * {@code //attacker.invalid/x.js}, which passes through byte-for-byte. A single verdict per template
 * would have to round one of those off, and rounding off is how the F6 gap survived for fifteen
 * years.
 *
 * <p>So a case carries a default verdict, optional per-family verdicts, and optional per-payload
 * overrides, resolved in that order of increasing specificity. The common "everything is safe here"
 * case stays terse; the interesting cases stay precise.
 */
public final class XssCase {

    private final String id;
    private final String section;
    private final String template;
    private final String referenceName;
    private final Map<String, Object> extraModel;
    private final SinkKind sink;
    private final String selector;
    private final String attribute;
    private final List<Payload> payloads;
    private final Verdict defaultVerdict;
    private final Map<String, Verdict> familyVerdicts;
    private final Map<Payload, Verdict> overrides;
    private final String finding;
    private final String note;
    private final ResidualSink residualSink;
    private final boolean browserRelevant;
    private final Set<Payload> notBrowserObservable;
    private final Set<String> notBrowserObservableFamilies;

    private XssCase(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "id");
        this.section = builder.section;
        this.template = Objects.requireNonNull(builder.template, "template for case " + builder.id);
        this.referenceName = builder.referenceName;
        this.extraModel = Collections.unmodifiableMap(new LinkedHashMap<>(builder.extraModel));
        this.sink = Objects.requireNonNull(builder.sink,
                "case " + builder.id + " did not declare a sink");
        this.selector = builder.selector;
        this.attribute = builder.attribute;
        this.payloads = Collections.unmodifiableList(new ArrayList<>(builder.payloads));
        this.defaultVerdict = Objects.requireNonNull(builder.defaultVerdict,
                "case " + builder.id + " did not declare a verdict");
        this.familyVerdicts = Collections.unmodifiableMap(new LinkedHashMap<>(builder.familyVerdicts));
        this.overrides = Collections.unmodifiableMap(new LinkedHashMap<>(builder.overrides));
        this.finding = builder.finding;
        this.note = builder.note;
        this.residualSink = builder.residualSink;
        this.browserRelevant = builder.browserRelevant;
        this.notBrowserObservable =
                Collections.unmodifiableSet(new LinkedHashSet<>(builder.notBrowserObservable));
        this.notBrowserObservableFamilies = Collections.unmodifiableSet(
                new LinkedHashSet<>(builder.notBrowserObservableFamilies));

        validate();
    }

    private void validate() {
        if (payloads.isEmpty()) {
            throw new IllegalArgumentException("Case " + id + " has no payloads");
        }
        for (Payload payload : overrides.keySet()) {
            if (!payloads.contains(payload)) {
                throw new IllegalArgumentException(
                        "Case " + id + " overrides a payload it does not use: " + payload);
            }
        }
        for (String family : familyVerdicts.keySet()) {
            boolean used = payloads.stream().anyMatch(p -> p.family().equals(family));
            if (!used) {
                throw new IllegalArgumentException(
                        "Case " + id + " sets a verdict for family " + family + ", which it does not use");
            }
        }
        for (Payload payload : notBrowserObservable) {
            if (!payloads.contains(payload)) {
                throw new IllegalArgumentException("Case " + id
                        + " marks a payload it does not use as not browser-observable: " + payload);
            }
        }
        for (String family : notBrowserObservableFamilies) {
            boolean used = payloads.stream().anyMatch(p -> p.family().equals(family));
            if (!used) {
                throw new IllegalArgumentException("Case " + id
                        + " marks family " + family + " as not browser-observable, but does not"
                        + " use it");
            }
        }
        if (sink != SinkKind.NONE && selector == null) {
            throw new IllegalArgumentException(
                    "Case " + id + " declares sink " + sink + " but no selector to find it with");
        }

        // A verdict with no citation is a review failure, so the corpus refuses to hold one. This is
        // the guard that keeps the ledger from becoming a record of "whatever the code did". Both
        // live verdicts are covered: an ACCEPTED_RESIDUAL row is a row where the data still reaches
        // the sink, so it owes the same citation as the KNOWN_VULNERABLE row it was promoted from -
        // and R26 kept every one of the 68 citations rather than letting the verdict change wash
        // them away.
        boolean anyLive = payloads.stream().anyMatch(p -> verdictFor(p).reachesSinkLive());
        if (anyLive && (finding == null || finding.isEmpty())) {
            throw new IllegalArgumentException("Case " + id
                    + " records attacker data reaching the sink live but cites no finding. Cite one"
                    + " from the Canoe security reviews, which are held outside this repository, or"
                    + " open a new one.");
        }

        // The residual sink class is required on an ACCEPTED_RESIDUAL case and forbidden anywhere
        // else, the same way notBrowserObservable is constrained to the rows it means something on.
        // Required, because the verdict's whole content is a claim about what the sink does and an
        // unnamed sink is an unmade claim. Forbidden elsewhere, because a sink class on a suppressed
        // or safe row is a residue that is not there - and the pin list in CanoeCorpusTest reads
        // this field, so a stray one would put a case on a list it does not belong on.
        boolean anyResidual = payloads.stream()
                .anyMatch(p -> verdictFor(p) == Verdict.ACCEPTED_RESIDUAL);
        if (anyResidual && residualSink == null) {
            throw new IllegalArgumentException("Case " + id
                    + " is marked ACCEPTED_RESIDUAL but declares no residual sink class. Say which"
                    + " non-executing sink the data reaches - ResidualSink.OPEN_REDIRECT,"
                    + " FORM_RETARGET, REFERRER_LEAK or INERT_SINK - by calling .residualSink(...)."
                    + " The verdict means 'the sink is not code execution', and naming the sink is"
                    + " what turns that from an assertion into a reviewable one.");
        }
        if (!anyResidual && residualSink != null) {
            throw new IllegalArgumentException("Case " + id
                    + " declares the residual sink class " + residualSink + " but has no"
                    + " ACCEPTED_RESIDUAL payload. Either the verdict was lowered and the sink class"
                    + " should go with it, or the sink class is on the wrong case.");
        }
    }

    public String id() {
        return id;
    }

    /** The Appendix A section this case comes from, used to group the generated report. */
    public String section() {
        return section;
    }

    public String template() {
        return template;
    }

    /** The reference the payload binds to; {@code data} unless the case says otherwise. */
    public String referenceName() {
        return referenceName;
    }

    /** Additional model entries, for cases needing more than a single payload reference. */
    public Map<String, Object> extraModel() {
        return extraModel;
    }

    public SinkKind sink() {
        return sink;
    }

    /** jsoup selector for the element holding the sink; null only when {@link SinkKind#NONE}. */
    public String selector() {
        return selector;
    }

    /** Attribute name holding the sink; null when the sink is text. */
    public String attribute() {
        return attribute;
    }

    public List<Payload> payloads() {
        return payloads;
    }

    /**
     * The reviewed verdict for this payload against this template, resolved most-specific first:
     * per-payload override, then per-family verdict, then the case default.
     */
    public Verdict verdictFor(Payload payload) {
        Verdict override = overrides.get(payload);
        if (override != null) {
            return override;
        }
        Verdict family = familyVerdicts.get(payload.family());
        if (family != null) {
            return family;
        }
        return defaultVerdict;
    }

    public Verdict defaultVerdict() {
        return defaultVerdict;
    }

    /** The finding this case documents, e.g. {@code "F1"}; null when the case records safe behaviour. */
    public String finding() {
        return finding;
    }

    public String note() {
        return note;
    }

    /**
     * Which non-executing sink this case's {@link Verdict#ACCEPTED_RESIDUAL} rows reach; null on
     * every case that has none. See {@link ResidualSink}.
     */
    public ResidualSink residualSink() {
        return residualSink;
    }

    /**
     * Whether this case participates in the browser tier at all. Which of its <em>invocations</em>
     * actually get loaded is a narrower question — see {@link Invocation#isBrowserRelevant()} — so
     * that a case with thirteen payloads, eight of them safe, does not cost thirteen page loads
     * across three engines.
     */
    public boolean isBrowserRelevant() {
        return browserRelevant;
    }

    /**
     * Whether a real browser can be expected to <em>act</em> on this pairing at all.
     *
     * <p>This is a third axis, independent of the verdict and of browser relevance, and it exists
     * because the two were being conflated. The ledger's subject is Canoe: per &sect;2.1 a value that
     * reaches the sink live is {@link Verdict#KNOWN_VULNERABLE} whether or not a 2026 engine
     * dereferences it, and &sect;8 says so explicitly — "a dead vector is still a Canoe defect if
     * Canoe emitted the payload live". The browser tier's subject is the browser, and &sect;5.2 says
     * "divergence in either direction fails the test". Put together, those two rules make about
     * twenty invocations guaranteed false failures the moment T25–T29 land: {@code srcset} never
     * runs a {@code javascript:} URL, {@code vbscript:} and {@code expression()} are gone from every
     * shipping engine, and a {@code data:} URL in a {@code background} attribute loads no document.
     *
     * <p>Rather than redefine the verdict for those rows — which is what the {@code legacy} note in
     * {@code CanoeCorpus} had started doing — they are flagged here, so the browser tier can expect a
     * detector <em>miss</em> and still assert the Velocity-tier verdict unchanged. A row that claims
     * a live vector and is not browser-observable is a claim about Canoe's output with an explicit
     * note that no engine will confirm it.
     *
     * <p>The flag is still empty after R26, and the residue it would have described is now said in
     * a verdict instead: {@link ResidualSink#INERT_SINK} records "no engine dereferences this" as a
     * property of the sink, on every row, rather than as an expectation of the browser tier on the
     * rows the tier happens to load. None of the six inert cases is browser-relevant, so nothing is
     * asked of the tier for them and no flag is needed — and neither is {@code url.longdesc}, which
     * review moved out of that class to {@link ResidualSink#OPEN_REDIRECT}, so the correction did
     * not change what the tier loads either.
     */
    public boolean isBrowserObservable(Payload payload) {
        return !notBrowserObservable.contains(payload)
                && !notBrowserObservableFamilies.contains(payload.family());
    }

    /** Every (case, payload) pair, which is the unit a parameterised test runs. */
    public List<Invocation> invocations() {
        List<Invocation> result = new ArrayList<>(payloads.size());
        for (Payload payload : payloads) {
            result.add(new Invocation(this, payload));
        }
        return result;
    }

    @Override
    public String toString() {
        return id;
    }

    public static Builder id(String id) {
        return new Builder(id);
    }

    /**
     * A single (case, payload) pair. JUnit displays {@link #toString()}, which must therefore be
     * unique across the corpus: a report showing eight identically named tests, three of which fail,
     * tells you nothing about which three.
     */
    public static final class Invocation {

        private final XssCase testCase;
        private final Payload payload;

        Invocation(XssCase testCase, Payload payload) {
            this.testCase = testCase;
            this.payload = payload;
        }

        public XssCase testCase() {
            return testCase;
        }

        public Payload payload() {
            return payload;
        }

        public Verdict verdict() {
            return testCase.verdictFor(payload);
        }

        /**
         * Whether this pairing earns a browser run. Everything whose verdict says the data reaches
         * the sink live does, because those are the entries whose verdict depends on parser
         * behaviour rather than on Canoe's output alone. Safe pairings are loaded only as controls,
         * one per case, so that a green browser run means the detectors stayed quiet when they
         * should rather than that nothing was loaded.
         *
         * <p>The test is {@link Verdict#reachesSinkLive()} and not {@code == KNOWN_VULNERABLE},
         * which matters since R26: an {@link Verdict#ACCEPTED_RESIDUAL} row is one where the
         * attacker's authority arrives at the sink, and a browser confirming that is exactly what
         * keeps the acceptance honest. Reading the narrower test would have quietly dropped every
         * residual row out of the browser tier at the moment the verdict was introduced, taking the
         * evidence with it.
         */
        public boolean isBrowserRelevant() {
            if (!testCase.isBrowserRelevant()) {
                return false;
            }
            if (verdict().reachesSinkLive()) {
                return true;
            }
            return isFirstSafeControlOfItsCase();
        }

        /** See {@link XssCase#isBrowserObservable(Payload)}. */
        public boolean isBrowserObservable() {
            return testCase.isBrowserObservable(payload);
        }

        /** The case's declared {@link ResidualSink}; null unless this row is a residual. */
        public ResidualSink residualSink() {
            return verdict() == Verdict.ACCEPTED_RESIDUAL ? testCase.residualSink() : null;
        }

        private boolean isFirstSafeControlOfItsCase() {
            for (Payload candidate : testCase.payloads()) {
                if (!testCase.verdictFor(candidate).reachesSinkLive()) {
                    return candidate.equals(payload);
                }
            }
            return false;
        }

        @Override
        public String toString() {
            return testCase.id() + " / " + payload.id();
        }
    }

    public static final class Builder {

        private final String id;
        private String section;
        private String template;
        private String referenceName = "data";
        private final Map<String, Object> extraModel = new LinkedHashMap<>();
        private SinkKind sink;
        private String selector;
        private String attribute;
        private final List<Payload> payloads = new ArrayList<>();
        private Verdict defaultVerdict;
        private final Map<String, Verdict> familyVerdicts = new LinkedHashMap<>();
        private final Map<Payload, Verdict> overrides = new LinkedHashMap<>();
        private String finding;
        private String note;
        private ResidualSink residualSink;
        private boolean browserRelevant;
        private final Set<Payload> notBrowserObservable = new LinkedHashSet<>();
        private final Set<String> notBrowserObservableFamilies = new LinkedHashSet<>();

        private Builder(String id) {
            this.id = id;
        }

        public Builder section(String section) {
            this.section = section;
            return this;
        }

        public Builder template(String template) {
            this.template = template;
            return this;
        }

        /** Binds the payload to a reference other than {@code $data}. */
        public Builder referenceName(String referenceName) {
            this.referenceName = referenceName;
            return this;
        }

        /** Adds a model entry, for cases needing more than the single payload reference. */
        public Builder model(String name, Object value) {
            this.extraModel.put(name, value);
            return this;
        }

        /** Declares the sink and where to find it in the parsed output. */
        public Builder sink(SinkKind kind, String selector, String attribute) {
            this.sink = kind;
            this.selector = selector;
            this.attribute = attribute;
            return this;
        }

        /** Declares a sink that is element text rather than an attribute. */
        public Builder textSink(String selector) {
            return sink(SinkKind.HTML_TEXT, selector, null);
        }

        /** Declares that the reference produces no observable sink at all. */
        public Builder noSink() {
            this.sink = SinkKind.NONE;
            return this;
        }

        public Builder payloads(Payload... values) {
            Collections.addAll(this.payloads, values);
            return this;
        }

        public Builder payloads(List<Payload> values) {
            this.payloads.addAll(values);
            return this;
        }

        public Builder verdict(Verdict verdict) {
            this.defaultVerdict = verdict;
            return this;
        }

        /**
         * Records a verdict for a whole payload family. Without this, a case that pulls in a family
         * has to name every member individually to say "these are all safe here", and any payload
         * added to that family later inherits the case default unreviewed.
         */
        public Builder overrideFamily(String family, Verdict verdict) {
            this.familyVerdicts.put(family, verdict);
            return this;
        }

        /** Records a payload whose outcome differs from its family's or the case's. */
        public Builder override(Payload payload, Verdict verdict) {
            this.overrides.put(payload, verdict);
            return this;
        }

        public Builder finding(String finding) {
            this.finding = finding;
            return this;
        }

        public Builder note(String note) {
            this.note = note;
            return this;
        }

        /**
         * Names the non-executing sink this case's {@link Verdict#ACCEPTED_RESIDUAL} rows reach.
         * Required on such a case and rejected on any other; see {@link ResidualSink} and
         * {@link XssCase#validate()}.
         */
        public Builder residualSink(ResidualSink residualSink) {
            this.residualSink = residualSink;
            return this;
        }

        public Builder browserRelevant() {
            this.browserRelevant = true;
            return this;
        }

        /**
         * Records that no shipping browser will act on these payloads in this template, so the
         * browser tier must expect a detector miss rather than report a ledger divergence. See
         * {@link XssCase#isBrowserObservable(Payload)}.
         */
        public Builder notBrowserObservable(Payload... values) {
            Collections.addAll(this.notBrowserObservable, values);
            return this;
        }

        /** The whole-family form of {@link #notBrowserObservable(Payload...)}. */
        public Builder notBrowserObservableFamily(String... families) {
            Collections.addAll(this.notBrowserObservableFamilies, families);
            return this;
        }

        public XssCase build() {
            return new XssCase(this);
        }
    }
}
