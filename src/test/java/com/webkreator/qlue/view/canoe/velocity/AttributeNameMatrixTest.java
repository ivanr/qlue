package com.webkreator.qlue.view.canoe.velocity;

import com.webkreator.qlue.view.Canoe;
import com.webkreator.qlue.view.CanoeStateProbe;
import com.webkreator.qlue.view.canoe.CanoeTestSupport;
import com.webkreator.qlue.view.canoe.corpus.CanoeCorpus;
import com.webkreator.qlue.view.canoe.corpus.Payload;
import com.webkreator.qlue.view.canoe.corpus.Payloads;
import com.webkreator.qlue.view.canoe.corpus.SinkKind;
import com.webkreator.qlue.view.canoe.corpus.VerdictEvaluator;
import com.webkreator.qlue.view.canoe.corpus.XssCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The attribute-name matrix from Appendix A &sect;A.2, and the partition it adds up to.
 *
 * <p>Canoe's entire safety rests on {@code setTagAttributeContext()} recognising every dangerous
 * attribute name, because the default for an unrecognised name is {@code ATTR_HTML} &rarr;
 * {@code html()}, and {@code html()} is worthless for any attribute whose decoded value a second
 * parser consumes. Every miss is an XSS. So the useful question is not "does {@code href} work" — it
 * does — but "what is the complete recognised set, and is it the set the review says it is".
 *
 * <h2>What this file does that the corpus does not</h2>
 *
 * <p>The corpus holds the per-name ledger for &sect;A.2 and this file consumes it: {@link
 * #everyAttributeNameTheCorpusExercisesIsInTheMatrix} fails if a corpus case names an attribute the
 * matrix has never classified, so the two cannot drift. What the matrix adds is the <strong>partition
 * assertion</strong>: for every name below, which of the five reachable {@code ATTR_*}
 * classifications Canoe gives it, and then that the partition is exactly what the review's "The
 * systemic flaw" section documents — five {@code ATTR_URI} names, one {@code ATTR_CSS}, one
 * {@code ATTR_CONTENT}, 21 reachable {@code ATTR_JS}, everything else {@code ATTR_HTML}.
 *
 * <p>A ledger of individually-correct rows cannot state that. It says "{@code srcdoc} is vulnerable";
 * it does not say "and there is nothing else in the recognised set that anybody has overlooked". The
 * partition is also the claim a fix has to change, so it is worth having in one place where a
 * remediation can be measured against it.
 *
 * <h2>Where the arithmetic for the handlers lives</h2>
 *
 * <p>The 21 {@code ATTR_JS} names are {@code EventHandlerMatrixTest}'s and
 * {@code CanoeStateMachineTest}'s: the first probes every handler name that exists in the world, the
 * second reads the 24 declared branches back out of {@code Canoe.java}. This file carries one
 * handler as a representative so that the partition has a fifth cell, and delegates the count.
 */
public class AttributeNameMatrixTest {

    /** The Appendix A section the attribute-name cases are filed under. */
    private static final String SECTION = "A.2 attribute names";

    /**
     * Which part of Appendix A &sect;A.2 a name comes from. Recorded on every row because the group
     * is the reviewed judgement and the classification is only the measurement: {@code srcset} and
     * {@code placeholder} are both {@code ATTR_HTML}, and one of those is a Critical finding.
     */
    enum Group {
        /** The five names {@code setTagAttributeContext()} maps to {@code ATTR_URI}. */
        RECOGNISED_URI,
        /** {@code style}. */
        RECOGNISED_CSS,
        /** {@code data} (F7). */
        RECOGNISED_CONTENT,
        /** One representative event handler; the other 20 belong to {@code EventHandlerMatrixTest}. */
        RECOGNISED_JS,
        /** Names where {@code ATTR_HTML} is genuinely the right answer. */
        PLAIN_TEXT,
        /** URL-bearing names Canoe has never heard of (F3). */
        URL_MISSED,
        /** {@code srcdoc}, parsed as markup in its own right (F3). */
        MARKUP,
        /** {@code content} on {@code <meta http-equiv=refresh>} (F3, F7). */
        REFRESH,
        /** Attributes the browser acts on as a security directive (F20). */
        POLICY,
        /** The same names spelled differently, to pin the case-insensitive name scan. */
        CASE_PERMUTATION
    }

    // ------------------------------------------------------------------
    // The matrix
    // ------------------------------------------------------------------

    static Stream<Arguments> matrix() {
        return Stream.of(
                // --- the complete recognised set: five URI names, one CSS, one CONTENT ---
                row("background", Group.RECOGNISED_URI, Canoe.ATTR_URI),
                row("dynsrc", Group.RECOGNISED_URI, Canoe.ATTR_URI),
                row("lowsrc", Group.RECOGNISED_URI, Canoe.ATTR_URI),
                row("href", Group.RECOGNISED_URI, Canoe.ATTR_URI),
                row("src", Group.RECOGNISED_URI, Canoe.ATTR_URI),
                row("style", Group.RECOGNISED_CSS, Canoe.ATTR_CSS),
                row("data", Group.RECOGNISED_CONTENT, Canoe.ATTR_CONTENT),
                row("onclick", Group.RECOGNISED_JS, Canoe.ATTR_JS),

                // --- plain text, where ATTR_HTML is correct ---
                // html() escapes space, '>', '=' and both quotes, so a value cannot be terminated
                // even unquoted. These rows are what stop a green run from being vacuous.
                row("id", Group.PLAIN_TEXT, Canoe.ATTR_HTML),
                row("class", Group.PLAIN_TEXT, Canoe.ATTR_HTML),
                row("title", Group.PLAIN_TEXT, Canoe.ATTR_HTML),
                row("alt", Group.PLAIN_TEXT, Canoe.ATTR_HTML),
                row("value", Group.PLAIN_TEXT, Canoe.ATTR_HTML),
                row("name", Group.PLAIN_TEXT, Canoe.ATTR_HTML),
                row("placeholder", Group.PLAIN_TEXT, Canoe.ATTR_HTML),
                row("lang", Group.PLAIN_TEXT, Canoe.ATTR_HTML),
                row("dir", Group.PLAIN_TEXT, Canoe.ATTR_HTML),
                row("role", Group.PLAIN_TEXT, Canoe.ATTR_HTML),
                row("aria-label", Group.PLAIN_TEXT, Canoe.ATTR_HTML),
                row("aria-describedby", Group.PLAIN_TEXT, Canoe.ATTR_HTML),
                row("data-widget", Group.PLAIN_TEXT, Canoe.ATTR_HTML),
                row("type", Group.PLAIN_TEXT, Canoe.ATTR_HTML),
                row("target", Group.PLAIN_TEXT, Canoe.ATTR_HTML),
                row("formtarget", Group.PLAIN_TEXT, Canoe.ATTR_HTML),
                row("accesskey", Group.PLAIN_TEXT, Canoe.ATTR_HTML),
                row("autocomplete", Group.PLAIN_TEXT, Canoe.ATTR_HTML),
                row("colspan", Group.PLAIN_TEXT, Canoe.ATTR_HTML),
                row("contenteditable", Group.PLAIN_TEXT, Canoe.ATTR_HTML),
                row("coords", Group.PLAIN_TEXT, Canoe.ATTR_HTML),
                row("datetime", Group.PLAIN_TEXT, Canoe.ATTR_HTML),
                row("download", Group.PLAIN_TEXT, Canoe.ATTR_HTML),
                row("enctype", Group.PLAIN_TEXT, Canoe.ATTR_HTML),
                row("for", Group.PLAIN_TEXT, Canoe.ATTR_HTML),
                row("headers", Group.PLAIN_TEXT, Canoe.ATTR_HTML),
                row("height", Group.PLAIN_TEXT, Canoe.ATTR_HTML),
                row("hreflang", Group.PLAIN_TEXT, Canoe.ATTR_HTML),
                row("label", Group.PLAIN_TEXT, Canoe.ATTR_HTML),
                row("list", Group.PLAIN_TEXT, Canoe.ATTR_HTML),
                row("maxlength", Group.PLAIN_TEXT, Canoe.ATTR_HTML),
                row("media", Group.PLAIN_TEXT, Canoe.ATTR_HTML),
                row("method", Group.PLAIN_TEXT, Canoe.ATTR_HTML),
                row("pattern", Group.PLAIN_TEXT, Canoe.ATTR_HTML),
                row("popovertarget", Group.PLAIN_TEXT, Canoe.ATTR_HTML),
                row("size", Group.PLAIN_TEXT, Canoe.ATTR_HTML),
                row("sizes", Group.PLAIN_TEXT, Canoe.ATTR_HTML),
                row("slot", Group.PLAIN_TEXT, Canoe.ATTR_HTML),
                row("spellcheck", Group.PLAIN_TEXT, Canoe.ATTR_HTML),
                row("srclang", Group.PLAIN_TEXT, Canoe.ATTR_HTML),
                row("step", Group.PLAIN_TEXT, Canoe.ATTR_HTML),
                row("tabindex", Group.PLAIN_TEXT, Canoe.ATTR_HTML),
                row("width", Group.PLAIN_TEXT, Canoe.ATTR_HTML),
                row("wrap", Group.PLAIN_TEXT, Canoe.ATTR_HTML),

                // --- URL-bearing and unrecognised: F3's table, plus the three legacy object ones ---
                row("action", Group.URL_MISSED, Canoe.ATTR_HTML),
                row("formaction", Group.URL_MISSED, Canoe.ATTR_HTML),
                row("poster", Group.URL_MISSED, Canoe.ATTR_HTML),
                row("cite", Group.URL_MISSED, Canoe.ATTR_HTML),
                row("usemap", Group.URL_MISSED, Canoe.ATTR_HTML),
                row("longdesc", Group.URL_MISSED, Canoe.ATTR_HTML),
                row("codebase", Group.URL_MISSED, Canoe.ATTR_HTML),
                row("manifest", Group.URL_MISSED, Canoe.ATTR_HTML),
                row("ping", Group.URL_MISSED, Canoe.ATTR_HTML),
                row("srcset", Group.URL_MISSED, Canoe.ATTR_HTML),
                row("imagesrcset", Group.URL_MISSED, Canoe.ATTR_HTML),
                row("xlink:href", Group.URL_MISSED, Canoe.ATTR_HTML),
                row("xml:base", Group.URL_MISSED, Canoe.ATTR_HTML),
                row("archive", Group.URL_MISSED, Canoe.ATTR_HTML),
                row("profile", Group.URL_MISSED, Canoe.ATTR_HTML),
                row("classid", Group.URL_MISSED, Canoe.ATTR_HTML),

                // --- markup and refresh ---
                row("srcdoc", Group.MARKUP, Canoe.ATTR_HTML),
                row("content", Group.REFRESH, Canoe.ATTR_HTML),

                // --- policy directives (F20) ---
                row("sandbox", Group.POLICY, Canoe.ATTR_HTML),
                row("nonce", Group.POLICY, Canoe.ATTR_HTML),
                row("rel", Group.POLICY, Canoe.ATTR_HTML),
                row("integrity", Group.POLICY, Canoe.ATTR_HTML),

                // --- case permutations: the name scan lowercases as it buffers ---
                row("HREF", Group.CASE_PERMUTATION, Canoe.ATTR_URI),
                row("HrEf", Group.CASE_PERMUTATION, Canoe.ATTR_URI),
                row("SRC", Group.CASE_PERMUTATION, Canoe.ATTR_URI),
                row("BACKGROUND", Group.CASE_PERMUTATION, Canoe.ATTR_URI),
                row("STYLE", Group.CASE_PERMUTATION, Canoe.ATTR_CSS),
                row("Data", Group.CASE_PERMUTATION, Canoe.ATTR_CONTENT),
                row("ONCLICK", Group.CASE_PERMUTATION, Canoe.ATTR_JS),
                // ...and the ones case cannot save, because the bug is in the indices.
                row("ONSUBMIT", Group.CASE_PERMUTATION, Canoe.ATTR_HTML),
                row("SRCDOC", Group.CASE_PERMUTATION, Canoe.ATTR_HTML),
                row("SANDBOX", Group.CASE_PERMUTATION, Canoe.ATTR_HTML),
                row("XLink:Href", Group.CASE_PERMUTATION, Canoe.ATTR_HTML));
    }

    private static Arguments row(String name, Group group, int expected) {
        return Arguments.of(name, group, expected);
    }

    /**
     * Every name in the matrix, classified.
     *
     * <p>Both the {@code ATTR_*} value and the {@code CTX_*} it produces are asserted, because the
     * two are not in bijection and the interesting failures live in the gap: {@code ATTR_CSS},
     * {@code ATTR_CONTENT}, {@code ATTR_DATA} and {@code ATTR_ACTIONSCRIPT} all collapse to
     * {@code CTX_SUPPRESS}, so a context-only assertion cannot tell a {@code style} attribute Canoe
     * classified correctly from one it fell through on. See {@link #currentContextCanNeverReturnCtxCss}.
     */
    @ParameterizedTest(name = "{0} ({1})")
    @MethodSource("matrix")
    public void classifiesTo(String name, Group group, int expected) {
        assertEquals(expected, attributeContextOf(name),
                () -> name + " (" + group + ") must classify as "
                        + CanoeStateProbe.attributeContextName(expected) + " but was "
                        + CanoeStateProbe.attributeContextName(attributeContextOf(name)));

        assertEquals(contextFor(expected), CanoeTestSupport.contextAfter("<x " + name + "=\""),
                () -> name + " (" + group + ") classifies as "
                        + CanoeStateProbe.attributeContextName(expected) + ", which must produce "
                        + CanoeTestSupport.contextName(contextFor(expected)));
    }

    /** The context {@code currentContext()} produces for an {@code ATTR_*} value inside a value. */
    private static int contextFor(int attributeContext) {
        switch (attributeContext) {
            case Canoe.ATTR_HTML:
                return Canoe.CTX_HTML_ATTR;
            case Canoe.ATTR_JS:
                return Canoe.CTX_JS;
            case Canoe.ATTR_URI:
                return Canoe.CTX_URI;
            default:
                // ATTR_CSS, ATTR_DATA, ATTR_CONTENT and ATTR_ACTIONSCRIPT share one arm of the
                // switch. Note in particular that ATTR_CSS does NOT produce CTX_CSS.
                return Canoe.CTX_SUPPRESS;
        }
    }

    // ------------------------------------------------------------------
    // The partition
    // ------------------------------------------------------------------

    /**
     * The partition the review's "The systemic flaw" section documents, asserted as a whole rather
     * than one name at a time.
     *
     * <p>Measured against the working tree it agrees exactly, which is worth recording because the
     * count in that section was wrong once already — it read 24 {@code ATTR_JS} names off the source
     * and only 21 can be taken.
     */
    @Test
    public void thePartitionIsExactlyWhatTheReviewDocuments() {
        Map<Integer, Set<String>> byClassification = new LinkedHashMap<>();
        for (Arguments arguments : (Iterable<Arguments>) matrix()::iterator) {
            String name = (String) arguments.get()[0];
            int measured = attributeContextOf(name);
            byClassification.computeIfAbsent(measured, key -> new LinkedHashSet<>())
                    .add(name.toLowerCase());
        }

        assertEquals(Set.of("background", "dynsrc", "lowsrc", "href", "src"),
                byClassification.get(Canoe.ATTR_URI),
                "ATTR_URI must be exactly the five names the review lists. A name added here is a"
                        + " fix; a name removed is a regression; either way the review's table and"
                        + " the remediation allowlist have to change with it.");

        assertEquals(Set.of("style"), byClassification.get(Canoe.ATTR_CSS),
                "ATTR_CSS must be exactly one name");

        assertEquals(Set.of("data"), byClassification.get(Canoe.ATTR_CONTENT),
                "ATTR_CONTENT must be exactly one name, and it must be 'data' rather than 'content'"
                        + " - which is F7: the branch commented 'content' compares 'data'");

        // The handler half is EventHandlerMatrixTest's, so this only asserts the shape.
        for (String jsName : byClassification.get(Canoe.ATTR_JS)) {
            assertTrue(jsName.startsWith("on"),
                    "every ATTR_JS name must be an event handler; got " + jsName
                            + ". The count of 21 is asserted by"
                            + " EventHandlerMatrixTest.theMatrixPartitionsIntoTwentyOneRecognisedNames"
                            + "AndEverythingElse and by"
                            + " CanoeStateMachineTest.onlyTwentyOneOfTheTwentyFourDeclaredOnStar"
                            + "BranchesCanBeTaken.");
        }

        // Nothing produces ATTR_DATA or ATTR_ACTIONSCRIPT from a name: those two come only from a
        // value prefix, which is AttributePrefixTest's territory.
        assertFalse(byClassification.containsKey(Canoe.ATTR_DATA),
                "ATTR_DATA is reachable only from a 'data:' value prefix, never from an attribute"
                        + " name");
        assertFalse(byClassification.containsKey(Canoe.ATTR_ACTIONSCRIPT),
                "ATTR_ACTIONSCRIPT is reachable only from an 'asfunction:' value prefix");

        assertEquals(5, byClassification.size(),
                () -> "a name in this matrix produced a classification outside the five the review"
                        + " documents: " + byClassification.keySet());
    }

    /**
     * The recognised set is an exact-match table, not a prefix rule.
     *
     * <p>Every branch ends by testing the NUL terminator the name scan wrote, so a longer name that
     * starts with a recognised one is not recognised. That is the correct behaviour and it is the
     * reason the table is so brittle: {@code srcdoc} and {@code srcset} are both one or three
     * characters away from {@code src}, and both are findings.
     */
    @Test
    public void aRecognisedNameIsMatchedWholeRatherThanAsAPrefix() {
        for (String nearMiss : List.of("hrefx", "srcx", "styles", "datax", "backgrounds",
                "lowsrcs", "dynsrcs", "srcdoc", "srcset", "hreflang")) {
            assertEquals(Canoe.ATTR_HTML, attributeContextOf(nearMiss),
                    nearMiss + " extends a recognised name and must not inherit its classification");
        }
        // ...and shorter is not enough either.
        for (String tooShort : List.of("hre", "sr", "styl", "dat", "background", "lowsr")) {
            int expected = "background".equals(tooShort) ? Canoe.ATTR_URI : Canoe.ATTR_HTML;
            assertEquals(expected, attributeContextOf(tooShort),
                    tooShort + ": a truncated name matches only if it is itself a recognised name");
        }
    }

    /**
     * The non-handler branches {@code setTagAttributeContext()} declares, read out of the source
     * rather than trusted.
     *
     * <p>The same technique {@code CanoeStateMachineTest.theSourceDeclaresExactlyTheOnStarBranches}
     * {@code TheTableLists} uses for the {@code on*} half, applied to the other half: a leaf branch
     * is a single-lowercase-word {@code // name} comment whose block assigns an {@code ATTR_*} value
     * before the next such comment. The group comments ({@code // on}, {@code // s}) assign nothing
     * and drop out by the same rule.
     *
     * <p>What the assertion is really pinning is <strong>F7</strong>, and it pins it in a form that
     * cannot be argued with: the branch the author commented {@code // content} compares the
     * characters of {@code data} and yields {@code ATTR_CONTENT}, and the branch commented
     * {@code // data} is byte-identical to it and yields {@code ATTR_URI}, so it can never be
     * reached. The author's own {@code XXX} comment sits directly above the pair.
     */
    @Test
    public void theSourceDeclaresExactlyTheNonHandlerBranchesTheMatrixExpects() throws IOException {
        Path source = Path.of("src/main/java/com/webkreator/qlue/view/Canoe.java");
        assertTrue(Files.isReadable(source),
                "cannot read " + source.toAbsolutePath() + "; this test must run with the project"
                        + " directory as its working directory");
        String text = Files.readString(source, StandardCharsets.UTF_8);

        int start = text.indexOf("protected void setTagAttributeContext()");
        assertTrue(start > 0, "setTagAttributeContext() has been renamed");
        int end = text.indexOf("\n    /**", start);
        assertTrue(end > start, "cannot find the end of setTagAttributeContext()");
        String body = text.substring(start, end);

        Matcher matcher = Pattern.compile("(?m)^\\s*//\\s*([a-z]+)\\s*$").matcher(body);
        List<String> declared = new ArrayList<>();
        String pendingName = null;
        int pendingStart = -1;
        while (matcher.find()) {
            if (pendingName != null) {
                addIfItAssigns(declared, pendingName, body, pendingStart, matcher.start());
            }
            pendingName = matcher.group(1);
            pendingStart = matcher.end();
        }
        if (pendingName != null) {
            addIfItAssigns(declared, pendingName, body, pendingStart, body.length());
        }

        assertEquals(List.of(
                        "background=ATTR_URI",
                        "content=ATTR_CONTENT",
                        "data=ATTR_URI",
                        "dynsrc=ATTR_URI",
                        "lowsrc=ATTR_URI",
                        "href=ATTR_URI",
                        "src=ATTR_URI",
                        "style=ATTR_CSS"),
                declared,
                "The non-handler branches setTagAttributeContext() declares no longer match this"
                        + " matrix. Two of the eight rows are F7 and are expected to look wrong: the"
                        + " branch commented 'content' compares the characters of 'data', and the"
                        + " branch commented 'data' is byte-identical to it and therefore"
                        + " unreachable. If that pair has been repaired, F7 is fixed - update the"
                        + " ledger entry on attr.data-on-object and the refresh.meta-content case,"
                        + " and say so in the commit message.");
    }

    private static void addIfItAssigns(List<String> into, String name, String body, int from, int to) {
        Matcher assignment =
                Pattern.compile("attributeContext = (ATTR_[A-Z]+);").matcher(body.substring(from, to));
        if (assignment.find() && !"ATTR_JS".equals(assignment.group(1))) {
            into.add(name + "=" + assignment.group(1));
        }
    }

    /**
     * {@code currentContext()} can never return {@code CTX_CSS}, so the {@code CTX_CSS} arm of
     * {@code Canoe.encode()} is dead code. Recorded as <strong>F21</strong>.
     *
     * <p>The review's mapping table presents six contexts, of which {@code CTX_CSS} maps to the empty
     * string; that is true of {@code encode()} and irrelevant, because {@code currentContext()}'s
     * {@code TAG_ATTR_VALUE} switch groups {@code ATTR_CSS} with {@code ATTR_DATA},
     * {@code ATTR_CONTENT} and {@code ATTR_ACTIONSCRIPT} and returns {@code CTX_SUPPRESS} for all
     * four. Only five of the six contexts are reachable.
     *
     * <p>No security impact today — both constants encode to the empty string — and it matters
     * anyway, for the reason F16 matters: the commented-out code at {@code Canoe.java:1074-1081}
     * contemplates replacing the CSS suppression with {@code HtmlEncoder.css()}, and uncommenting the
     * {@code CTX_CSS} arm would change <strong>nothing at all</strong>. A reviewer would then believe
     * {@code style} values were being CSS-escaped when they were still being dropped, and — worse —
     * the same edit to the {@code CTX_JS} arm above it <em>would</em> take effect, so half of an
     * apparently symmetrical change would land and half would not.
     *
     * <p>It is also the second reason the {@code ATTR_*} value is asserted alongside the context
     * throughout this file: at the context level a correctly-classified {@code style} attribute is
     * indistinguishable from one that fell through a hole in the switch, which is exactly the
     * ambiguity F11 is made of.
     */
    @Test
    public void currentContextCanNeverReturnCtxCss() throws IOException {
        // Measured: the one attribute name that produces ATTR_CSS does not produce CTX_CSS.
        assertEquals(Canoe.ATTR_CSS, attributeContextOf("style"));
        assertEquals(Canoe.CTX_SUPPRESS, CanoeTestSupport.contextAfter("<div style=\""),
                "F21: ATTR_CSS produces CTX_SUPPRESS, not CTX_CSS");
        assertEquals(Canoe.CTX_SUPPRESS, CanoeTestSupport.contextAfter("<style>"),
                "F21: the CSS element body produces CTX_SUPPRESS too, from the CSS state");
        assertEquals(Canoe.CTX_SUPPRESS, CanoeTestSupport.contextAfter("<style>a{}</sty"),
                "F21: and so does CSS_END");

        // ...and by exhaustion over the whole matrix, so that a new classification cannot introduce
        // it unnoticed.
        for (Arguments arguments : (Iterable<Arguments>) matrix()::iterator) {
            String name = (String) arguments.get()[0];
            assertFalse(CanoeTestSupport.contextAfter("<x " + name + "=\"") == Canoe.CTX_CSS,
                    "F21: " + name + " now reaches CTX_CSS, which nothing did when the finding was"
                            + " recorded. If that is a fix, the encode() arm is live now and"
                            + " HtmlEncoder.css() has to be fit for it - see F16.");
        }

        // The source fact behind the two measurements, which is the general form of the claim: the
        // string CTX_CSS does not appear in currentContext() at all, while encode() has an arm for it.
        Path source = Path.of("src/main/java/com/webkreator/qlue/view/Canoe.java");
        String text = Files.readString(source, StandardCharsets.UTF_8);
        String currentContext = methodBody(text, "public int currentContext()");
        String encode = methodBody(text, "public static String encode(String input, int ctx)");

        assertFalse(currentContext.contains("CTX_CSS"),
                "F21: currentContext() mentions CTX_CSS now. If it can return it, this finding is"
                        + " fixed and the CTX_CSS arm of encode() is reachable for the first time.");
        assertTrue(encode.contains("case CTX_CSS:"),
                "encode() must still carry the arm that cannot be reached, or F21 has been closed"
                        + " from the other end by deleting it");
    }

    private static String methodBody(String text, String signature) {
        int start = text.indexOf(signature);
        assertTrue(start > 0, "cannot find " + signature);
        int end = text.indexOf("\n    /**", start);
        assertTrue(end > start, "cannot find the end of " + signature);
        return text.substring(start, end);
    }

    // ------------------------------------------------------------------
    // The corpus, consumed
    // ------------------------------------------------------------------

    /**
     * Every attribute name a &sect;A.2 corpus case exercises appears in the matrix.
     *
     * <p>This is what keeps the two from drifting. The corpus is where a name gets a reviewed verdict;
     * the matrix is where it gets a classification. A case added to one and not the other means
     * either a verdict nobody derived from a classification, or a classification nobody attacked.
     */
    @Test
    public void everyAttributeNameTheCorpusExercisesIsInTheMatrix() {
        Set<String> inMatrix = new LinkedHashSet<>();
        for (Arguments arguments : (Iterable<Arguments>) matrix()::iterator) {
            inMatrix.add(((String) arguments.get()[0]).toLowerCase());
        }

        List<String> missing = new ArrayList<>();
        for (XssCase testCase : CanoeCorpus.inSection(SECTION)) {
            String attribute = testCase.attribute();
            if (attribute == null) {
                continue;
            }
            if (!inMatrix.contains(attribute.toLowerCase())) {
                missing.add(testCase.id() + " -> " + attribute);
            }
        }

        assertTrue(missing.isEmpty(),
                () -> "Corpus cases in " + SECTION + " name attributes the matrix never classifies: "
                        + missing + ". Add a row, so the verdict and the classification are recorded"
                        + " in the same place.");
    }

    /**
     * F3's punchline, as a single comparison: the same payload into {@code href} and into
     * {@code xlink:href}.
     *
     * <p>{@code href} is percent-encoded by {@code url()}; {@code xlink:href} is entity-encoded by
     * {@code html()} and the parser puts it straight back. {@code isTagNameChar()} accepts {@code ':'}
     * (Canoe.java:200) so {@code xlink:href} scans as one attribute name and simply does not match
     * {@code href}. The safe-by-analogy assumption a developer would make is exactly wrong.
     */
    @Test
    public void hrefIsProtectedAndXlinkHrefIsNot() {
        Payload jsUrl = Payloads.JS_URL;

        String throughUrl = CanoeTestSupport.render("<a href=\"$data\">x</a>", jsUrl.value())
                .decodedAttr("a", "href");
        String throughHtml = CanoeTestSupport
                .render("<svg><a xlink:href=\"$data\"><text>x</text></a></svg>", jsUrl.value())
                .decodedAttr("a", "xlink:href");

        assertFalse(throughUrl.contains("javascript:"),
                () -> "F6/F3: url() must escape the colon, leaving a relative path. Got: "
                        + throughUrl);
        assertTrue(throughUrl.contains("%3A"),
                () -> "and the colon must be there as %3A. Got: " + throughUrl);

        assertEquals(jsUrl.value(), throughHtml,
                "F3: xlink:href takes the ATTR_HTML fall-through, so the parser hands the URL"
                        + " parser the attacker's original characters. Same template shape, same"
                        + " payload, opposite outcome, entirely because of the ten characters in the"
                        + " attribute name.");
    }

    /**
     * F3's {@code srcdoc} row: an attribute parsed as HTML in its own right needs <em>double</em>
     * encoding, and Canoe applies single.
     */
    @Test
    public void srcdocNeedsDoubleEncodingAndGetsSingle() {
        String decoded = CanoeTestSupport
                .render("<iframe srcdoc=\"<p>$data</p>\"></iframe>", Payloads.SRCDOC_MARKUP.value())
                .decodedAttr("iframe", "srcdoc");

        assertEquals("<p>" + Payloads.SRCDOC_MARKUP.value() + "</p>", decoded,
                "F3: the value the iframe document is parsed from must contain the attacker's raw"
                        + " markup. That is same-origin script execution.");
    }

    /**
     * F3's {@code content} row and F7's second consequence, in one place: there is no check for
     * {@code content} at all, because the branch that was meant to hold it compares {@code data}.
     */
    @Test
    public void metaRefreshContentIsUnrecognisedBecauseTheBranchComparesData() {
        assertEquals(Canoe.ATTR_CONTENT, attributeContextOf("data"),
                "F7: 'data' takes the branch commented 'content'");
        assertEquals(Canoe.ATTR_HTML, attributeContextOf("content"),
                "F7: ...and 'content' therefore has no branch of its own at all");

        String decoded = CanoeTestSupport
                .render("<meta http-equiv=\"refresh\" content=\"$data\">", Payloads.META_REFRESH.value())
                .decodedAttr("meta", "content");
        assertEquals(Payloads.META_REFRESH.value(), decoded,
                "F3: a forced top-level navigation to an attacker origin, needing no click and no"
                        + " script");
    }

    /**
     * F20: for a policy directive the value arrives byte for byte and no change to the encoder can
     * alter that, because a policy token is letters, digits, hyphens, underscores and spaces — every
     * one of which either passes {@code html()} naked or round-trips through the parser's decoding.
     *
     * <p>Encoding is not merely insufficient here; it is inapplicable. Only recognising the name and
     * suppressing can help, which is why F20 turns on remediation item 3 rather than on the encoder.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("policyCases")
    public void aPolicyDirectiveArrivesByteForByte(XssCase testCase) {
        for (Payload payload : testCase.payloads()) {
            String decoded = VerdictEvaluator.render(testCase, payload.value())
                    .decodedAttr(testCase.selector(), testCase.attribute());
            assertEquals(payload.value(), decoded,
                    () -> "F20: " + testCase.attribute() + " must receive " + payload.value()
                            + " unchanged. Whether the browser ACTS on it is a separate question the"
                            + " ledger answers per token; this test is only about arrival.");
        }
    }

    static Stream<XssCase> policyCases() {
        return CanoeCorpus.inSection(SECTION).stream()
                .filter(c -> c.sink() == SinkKind.POLICY);
    }

    /**
     * The plain-text group, which is where {@code ATTR_HTML} is the right answer: the payload arrives
     * whole, and it arrives as text.
     *
     * <p>Both halves are asserted. Arrival shows the encoding is reversible and the attribute is not
     * silently mangled — the developer gets the value they asked for. Structural identity against the
     * benign render shows the payload stayed inside the value it was given, which is the generic
     * injection oracle and needs no opinion about which characters are dangerous.
     *
     * <p>Parameterised over (name, payload) rather than looping inside one {@code @Test}, like the
     * rest of this file. As a single test the whole plain-text group — every name in it, times three
     * payloads — reported as one row, and the first failure stopped the sweep: the reader learned
     * that {@code id} was broken and nothing at all about the other forty-odd names, which is the
     * opposite of what a matrix is for.
     */
    @ParameterizedTest(name = "{0} / {1}")
    @MethodSource("plainTextNameAndPayload")
    public void aPlainTextAttributeReceivesThePayloadWholeAndAsText(String name, Payload payload) {
        String template = "<x " + name + "=\"$data\">y</x>";

        CanoeTestSupport.RenderResult attacked = CanoeTestSupport.render(template, payload.value());
        CanoeTestSupport.RenderResult benign =
                CanoeTestSupport.render(template, Payloads.INERT_MARKER.value());

        assertEquals(payload.value(), attacked.decodedAttr("x", name),
                () -> name + " must receive the payload whole: html() is reversible, so the"
                        + " developer gets the value they asked for");
        assertEquals(VerdictEvaluator.domSkeleton(benign.dom()),
                VerdictEvaluator.domSkeleton(attacked.dom()),
                () -> name + " with " + payload.id() + " changed the shape of the document,"
                        + " which means the value escaped the attribute it was placed in");
    }

    /**
     * Every {@link Group#PLAIN_TEXT} name crossed with the three payloads that between them carry
     * every markup delimiter: a tag opener, a double quote, an apostrophe.
     */
    static Stream<Arguments> plainTextNameAndPayload() {
        List<Arguments> rows = new ArrayList<>();
        for (Arguments arguments : (Iterable<Arguments>) matrix()::iterator) {
            if (arguments.get()[1] != Group.PLAIN_TEXT) {
                continue;
            }
            for (Payload payload : List.of(Payloads.TAG_IMG_ONERROR,
                    Payloads.ATTR_DOUBLE_QUOTE_BREAKOUT, Payloads.QUOTE_SINGLE_BREAKOUT)) {
                rows.add(Arguments.of(arguments.get()[0], payload));
            }
        }
        return rows.stream();
    }

    // ------------------------------------------------------------------
    // Case and separator permutations
    // ------------------------------------------------------------------

    /**
     * The separators {@code TAG_ATTR_NAME_AFTER} skips before the {@code =}, and the two duplicate
     * orderings.
     *
     * <p>All four whitespace spellings resolve to the same classification, which is Canoe working.
     * The duplicate pair is the interesting one and it is the parser's behaviour rather than Canoe's:
     * Canoe classifies each occurrence independently and emits byte-identical output either way, and
     * the HTML parser keeps the <em>first</em> occurrence — so the ordering decides whether the page
     * is safe and Canoe cannot see the difference.
     */
    @Test
    public void separatorPermutationsDoNotChangeTheClassification() {
        assertEquals(Canoe.CTX_URI, CanoeTestSupport.contextAfter("<a href=\""));
        assertEquals(Canoe.CTX_URI, CanoeTestSupport.contextAfter("<a href =\""));
        assertEquals(Canoe.CTX_URI, CanoeTestSupport.contextAfter("<a href" + ch(0x09) + "=\""));
        assertEquals(Canoe.CTX_URI, CanoeTestSupport.contextAfter("<a href" + ch(0x0a) + "=\""));
        assertEquals(Canoe.CTX_URI,
                CanoeTestSupport.contextAfter("<a href" + ch(0x0d) + ch(0x0a) + "=\""));
        assertEquals(Canoe.CTX_URI, CanoeTestSupport.contextAfter("<a href  =  \""),
                "whitespace on both sides of the equals");

        // A duplicate attribute is classified twice, identically, and Canoe has no notion of the
        // duplication at all.
        assertEquals(Canoe.CTX_URI, CanoeTestSupport.contextAfter("<a href=\"/safe\" href=\""));
        assertEquals(Canoe.CTX_URI, CanoeTestSupport.contextAfter("<a href=\"x\" href=\""));

        String first = CanoeTestSupport
                .render("<a href=\"/safe\" href=\"$data\">x</a>", Payloads.PROTOCOL_RELATIVE.value())
                .decodedAttr("a", "href");
        String second = CanoeTestSupport
                .render("<a href=\"$data\" href=\"/safe\">x</a>", Payloads.PROTOCOL_RELATIVE.value())
                .decodedAttr("a", "href");
        assertEquals("/safe", first,
                "the parser keeps the first occurrence, so the template author's value wins and the"
                        + " attacker's is discarded - see separator.duplicate-attribute");
        assertEquals(Payloads.PROTOCOL_RELATIVE.value(), second,
                "reverse the two and the attacker's value is the one that survives, from"
                        + " byte-identical Canoe output - see separator.duplicate-attribute-reversed");

        // An attribute after '/' is not a classification question at all: Canoe rejects the template.
        assertTrue(CanoeTestSupport.render("<img src=\"a.png\"/ alt=\"$data\">", "x").isError(),
                "TAG_EMPTY_ENDING demands '>' immediately after '/', so an XHTML-style tag with a"
                        + " trailing attribute takes the page down - the same defect as <br/> in"
                        + " F13's table, reached from the attribute side");
    }

    /**
     * A boolean attribute does not desynchronise the classification of the next one, because
     * {@code TAG_ATTR_NAME_AFTER} starts a new name on any name character.
     */
    @Test
    public void aValuelessAttributeDoesNotDisturbTheNextName() {
        assertEquals(Canoe.CTX_HTML_ATTR, CanoeTestSupport.contextAfter("<input disabled value=\""));
        assertEquals(Canoe.CTX_URI, CanoeTestSupport.contextAfter("<a download href=\""));
        assertEquals(Canoe.CTX_JS, CanoeTestSupport.contextAfter("<button autofocus onclick=\""));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * The {@code ATTR_*} value derived from an attribute name on a fresh {@link Canoe}, so that the
     * only thing in the buffer is the name itself and its terminator. F5's residue is a separate axis
     * owned by {@code AttributePrefixTest}; mixing it in here would make a failure ambiguous between
     * "the name is unrecognised" and "an earlier name armed the buffer".
     */
    private static int attributeContextOf(String attributeName) {
        try {
            return new CanoeStateProbe().feed("<x " + attributeName + "=\"").attributeContext();
        } catch (IOException e) {
            throw new AssertionError("Canoe rejected the attribute name " + attributeName, e);
        }
    }

    /**
     * A one-character string from a code unit, so that this file stays pure ASCII and cannot be
     * corrupted by a compiler running under a non-UTF-8 default charset.
     */
    private static String ch(int codeUnit) {
        return String.valueOf((char) codeUnit);
    }
}
