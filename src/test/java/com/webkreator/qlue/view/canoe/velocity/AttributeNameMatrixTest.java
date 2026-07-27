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
import java.util.Arrays;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The attribute-name matrix from Appendix A &sect;A.2, and the partition it adds up to.
 *
 * <p>Canoe's entire safety used to rest on {@code setTagAttributeContext()} recognising every
 * dangerous attribute name, because the default for an unrecognised name was {@code ATTR_HTML}
 * &rarr; {@code html()}, and {@code html()} is worthless for any attribute whose decoded value a
 * second parser or a browser algorithm consumes. Every miss was an XSS, and the misses were F3 and
 * F20. <strong>R5 inverted the default</strong>: an unrecognised name is {@code ATTR_UNKNOWN} and
 * suppresses, and {@code ATTR_HTML} is reached only through a documented allowlist of plain-text
 * names. So the useful question has changed shape — it is no longer "what is the complete recognised
 * set of dangerous names", which is unanswerable, but "what is the complete set of names we have
 * argued are text", which is a list somebody can review.
 *
 * <h2>What this file does that the corpus does not</h2>
 *
 * <p>The corpus holds the per-name ledger for &sect;A.2 and this file consumes it: {@link
 * #everyAttributeNameTheCorpusExercisesIsInTheMatrix} fails if a corpus case names an attribute the
 * matrix has never classified, so the two cannot drift. What the matrix adds is the <strong>partition
 * assertion</strong>: for every name below, which of the {@code ATTR_*} classifications Canoe gives
 * it, and then that the partition is exactly the one R5, R6 and R7 settled — seventeen
 * {@code ATTR_URI} names, one {@code ATTR_CSS}, every {@code on*} name {@code ATTR_JS}, the
 * plain-text allowlist {@code ATTR_HTML}, and <em>everything else</em> {@code ATTR_UNKNOWN}.
 * {@link #everyNameOutsideTheAllowlistsIsSuppressed} states the last clause as a property rather
 * than as a list, which is what makes it a fail-closed claim rather than a table of names somebody
 * happened to write down.
 *
 * <p>A ledger of individually-correct rows cannot state that. It said "{@code srcdoc} is
 * vulnerable"; it did not say "and there is nothing else in the recognised set that anybody has
 * overlooked". The partition is also the claim a fix has to change, so it is worth having in one
 * place where a remediation can be measured against it.
 *
 * <h2>Where the arithmetic for the handlers lives</h2>
 *
 * <p>The {@code ATTR_JS} names are {@code EventHandlerMatrixTest}'s and
 * {@code CanoeStateMachineTest}'s: the first probes every handler name that exists in the world, the
 * second reads the prefix rule back out of {@code Canoe.java}. This file carries one handler as a
 * representative so that the partition has a cell for it, and delegates the count.
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
        /** The seventeen names {@code setTagAttributeContext()} maps to {@code ATTR_URI}. */
        RECOGNISED_URI,
        /** {@code style}. */
        RECOGNISED_CSS,
        /** One representative event handler; the rest belong to {@code EventHandlerMatrixTest}. */
        RECOGNISED_JS,
        /** Names on the plain-text allowlist, where {@code ATTR_HTML} is the right answer. */
        PLAIN_TEXT,
        /**
         * URL-bearing names that are on neither list, so R5's fail-closed default suppresses them.
         *
         * <p>These were F3 before R6 and are the names R6 deliberately did not put on the URL list:
         * no ordinary template interpolates into them, and suppression is strictly stronger than
         * {@code url()}, which is a scheme filter rather than an origin filter (F6).
         */
        URL_SUPPRESSED,
        /** {@code srcdoc}, parsed as markup in its own right (F3, suppressed by R6). */
        MARKUP,
        /** {@code content} on {@code <meta http-equiv=refresh>} (F3, F7; suppressed by R7, R10 confirmed). */
        REFRESH,
        /** Attributes the browser acts on as a security directive (F20, suppressed by R5). */
        POLICY,
        /** Names nobody has classified at all, which is the fail-closed default's own group. */
        UNKNOWN,
        /** The same names spelled differently, to pin the case-insensitive name scan. */
        CASE_PERMUTATION
    }

    // ------------------------------------------------------------------
    // The matrix
    // ------------------------------------------------------------------

    static Stream<Arguments> matrix() {
        return Stream.of(
                // --- the complete URL set: the five names Canoe always knew... ---
                row("background", Group.RECOGNISED_URI, Canoe.ATTR_URI),
                row("dynsrc", Group.RECOGNISED_URI, Canoe.ATTR_URI),
                row("lowsrc", Group.RECOGNISED_URI, Canoe.ATTR_URI),
                row("href", Group.RECOGNISED_URI, Canoe.ATTR_URI),
                row("src", Group.RECOGNISED_URI, Canoe.ATTR_URI),
                // ...and the twelve R6 added, of which `data` is R7's half of the F7 pair.
                row("action", Group.RECOGNISED_URI, Canoe.ATTR_URI),
                row("formaction", Group.RECOGNISED_URI, Canoe.ATTR_URI),
                row("poster", Group.RECOGNISED_URI, Canoe.ATTR_URI),
                row("cite", Group.RECOGNISED_URI, Canoe.ATTR_URI),
                row("usemap", Group.RECOGNISED_URI, Canoe.ATTR_URI),
                row("longdesc", Group.RECOGNISED_URI, Canoe.ATTR_URI),
                row("codebase", Group.RECOGNISED_URI, Canoe.ATTR_URI),
                row("manifest", Group.RECOGNISED_URI, Canoe.ATTR_URI),
                row("ping", Group.RECOGNISED_URI, Canoe.ATTR_URI),
                row("srcset", Group.RECOGNISED_URI, Canoe.ATTR_URI),
                row("xlink:href", Group.RECOGNISED_URI, Canoe.ATTR_URI),
                row("data", Group.RECOGNISED_URI, Canoe.ATTR_URI),

                row("style", Group.RECOGNISED_CSS, Canoe.ATTR_CSS),
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
                // On the allowlist by decision, not because it passes the group's own test: an
                // attacker-chosen CSP nonce is the nonce the policy admits. It was a POLICY row
                // below until the allowlist was widened to take it. See Canoe's
                // PLAIN_TEXT_ATTRIBUTE_NAMES javadoc and the plain.nonce corpus row.
                row("nonce", Group.PLAIN_TEXT, Canoe.ATTR_HTML),
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

                // --- URL-bearing names R6 deliberately left off the URL list, so they suppress ---
                row("imagesrcset", Group.URL_SUPPRESSED, Canoe.ATTR_UNKNOWN),
                row("xml:base", Group.URL_SUPPRESSED, Canoe.ATTR_UNKNOWN),
                row("archive", Group.URL_SUPPRESSED, Canoe.ATTR_UNKNOWN),
                row("profile", Group.URL_SUPPRESSED, Canoe.ATTR_UNKNOWN),
                row("classid", Group.URL_SUPPRESSED, Canoe.ATTR_UNKNOWN),

                // --- markup and refresh ---
                row("srcdoc", Group.MARKUP, Canoe.ATTR_UNKNOWN),
                row("content", Group.REFRESH, Canoe.ATTR_UNKNOWN),

                // --- policy directives (F20) ---
                row("sandbox", Group.POLICY, Canoe.ATTR_UNKNOWN),
                row("rel", Group.POLICY, Canoe.ATTR_UNKNOWN),
                row("integrity", Group.POLICY, Canoe.ATTR_UNKNOWN),

                // --- the other names R5 keeps off the plain-text allowlist, each argued in
                // Canoe's PLAIN_TEXT_ATTRIBUTE_NAMES javadoc ---
                row("http-equiv", Group.POLICY, Canoe.ATTR_UNKNOWN),
                row("charset", Group.POLICY, Canoe.ATTR_UNKNOWN),
                row("crossorigin", Group.POLICY, Canoe.ATTR_UNKNOWN),
                row("referrerpolicy", Group.POLICY, Canoe.ATTR_UNKNOWN),
                row("is", Group.POLICY, Canoe.ATTR_UNKNOWN),

                // --- and names nobody classified at all, which is the default's own group ---
                row("my-widget-config", Group.UNKNOWN, Canoe.ATTR_UNKNOWN),
                row("hx-target", Group.UNKNOWN, Canoe.ATTR_UNKNOWN),
                row("ng-model", Group.UNKNOWN, Canoe.ATTR_UNKNOWN),

                // --- case permutations: the name scan lowercases as it buffers ---
                row("HREF", Group.CASE_PERMUTATION, Canoe.ATTR_URI),
                row("HrEf", Group.CASE_PERMUTATION, Canoe.ATTR_URI),
                row("SRC", Group.CASE_PERMUTATION, Canoe.ATTR_URI),
                row("BACKGROUND", Group.CASE_PERMUTATION, Canoe.ATTR_URI),
                row("STYLE", Group.CASE_PERMUTATION, Canoe.ATTR_CSS),
                row("Data", Group.CASE_PERMUTATION, Canoe.ATTR_URI),
                row("ONCLICK", Group.CASE_PERMUTATION, Canoe.ATTR_JS),
                // ONSUBMIT was ATTR_HTML here until R4: the on* table's onselect/onsubmit block
                // tested buf[0]=='s' inside a block that had already established buf[0]=='o', so
                // no casing could save it because the bug was in the indices rather than in the
                // letters. The prefix rule lower-cases the name as it buffers like everything else,
                // so ONSUBMIT and onsubmit are one case now.
                row("ONSUBMIT", Group.CASE_PERMUTATION, Canoe.ATTR_JS),
                row("SRCDOC", Group.CASE_PERMUTATION, Canoe.ATTR_UNKNOWN),
                row("SANDBOX", Group.CASE_PERMUTATION, Canoe.ATTR_UNKNOWN),
                // XLink:Href was ATTR_HTML here until R6, and the casing was never what decided it:
                // the name scan lower-cased it then as it does now, and there was simply no branch
                // for it. It is on the URL list, so the mixed-case spelling reaches url() like the
                // lower-case one.
                row("XLink:Href", Group.CASE_PERMUTATION, Canoe.ATTR_URI),
                row("TITLE", Group.CASE_PERMUTATION, Canoe.ATTR_HTML),
                row("Aria-Label", Group.CASE_PERMUTATION, Canoe.ATTR_HTML),
                row("DATA-Widget", Group.CASE_PERMUTATION, Canoe.ATTR_HTML));
    }

    private static Arguments row(String name, Group group, int expected) {
        return Arguments.of(name, group, expected);
    }

    /**
     * Every name in the matrix, classified.
     *
     * <p>Both the {@code ATTR_*} value and the {@code CTX_*} it produces are asserted, because the
     * two are not in bijection and the interesting failures live in the gap: {@code ATTR_CSS},
     * {@code ATTR_UNKNOWN}, {@code ATTR_DATA} and {@code ATTR_ACTIONSCRIPT} all collapse to
     * {@code CTX_SUPPRESS}, so a context-only assertion cannot tell a {@code style} attribute Canoe
     * classified correctly from one it fell through on — which since R5 is the difference between a
     * decision and the fail-closed default. See {@link #thereIsNoCtxCssAndStyleStillSuppresses}.
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
                // ATTR_CSS, ATTR_DATA, ATTR_UNKNOWN and ATTR_ACTIONSCRIPT share one outcome of the
                // switch. Note in particular that ATTR_CSS suppresses: there is no CTX_CSS (R14/F21).
                return Canoe.CTX_SUPPRESS;
        }
    }

    // ------------------------------------------------------------------
    // The partition
    // ------------------------------------------------------------------

    /**
     * The partition R5, R6 and R7 settled, asserted as a whole rather than one name at a time.
     *
     * <p>Was {@code thePartitionIsExactlyWhatTheReviewDocuments}, which recorded the review's "The
     * systemic flaw" table: five {@code ATTR_URI} names, one {@code ATTR_CSS}, one
     * {@code ATTR_CONTENT} that was really {@code data} misfiled (F7), and everything else
     * {@code ATTR_HTML}. Three of those four cells have changed and the fourth — the one that
     * mattered — has been inverted: the residue of the partition is {@code ATTR_UNKNOWN} now, so a
     * name nobody has classified is dropped rather than handed to {@code html()}.
     */
    @Test
    public void thePartitionIsExactlyWhatPhaseASettled() {
        Map<Integer, Set<String>> byClassification = new LinkedHashMap<>();
        for (Arguments arguments : (Iterable<Arguments>) matrix()::iterator) {
            String name = (String) arguments.get()[0];
            int measured = attributeContextOf(name);
            byClassification.computeIfAbsent(measured, key -> new LinkedHashSet<>())
                    .add(name.toLowerCase());
        }

        assertEquals(Set.of("background", "dynsrc", "lowsrc", "href", "src",
                        "action", "formaction", "poster", "cite", "usemap", "longdesc", "codebase",
                        "manifest", "ping", "srcset", "xlink:href", "data"),
                byClassification.get(Canoe.ATTR_URI),
                "ATTR_URI must be exactly the seventeen names R6 and R7 settled: the five Canoe"
                        + " always knew, the eleven the review enumerates, and 'data'. A name added"
                        + " here needs a reviewed reason on Canoe.URL_ATTRIBUTE_NAMES; a name"
                        + " removed either regresses F3 or is a deliberate move to suppression, and"
                        + " either way the corpus row for it has to be re-verdicted.");

        assertEquals(Set.of("style"), byClassification.get(Canoe.ATTR_CSS),
                "ATTR_CSS must be exactly one name");

        // The handler half is EventHandlerMatrixTest's, so this only asserts the shape.
        for (String jsName : byClassification.get(Canoe.ATTR_JS)) {
            assertTrue(jsName.startsWith("on"),
                    "every ATTR_JS name must be an event handler; got " + jsName
                            + ". The count is asserted by"
                            + " EventHandlerMatrixTest.everyHandlerNameReachesTheSameClassification"
                            + " and by CanoeStateMachineTest"
                            + ".theSourceClassifiesHandlersByPrefixAndNotByName.");
        }

        // Nothing produces ATTR_DATA or ATTR_ACTIONSCRIPT from a name: those two come only from a
        // value prefix, which is AttributePrefixTest's territory.
        assertFalse(byClassification.containsKey(Canoe.ATTR_DATA),
                "ATTR_DATA is reachable only from a 'data:' value prefix, never from an attribute"
                        + " name");
        assertFalse(byClassification.containsKey(Canoe.ATTR_ACTIONSCRIPT),
                "ATTR_ACTIONSCRIPT is reachable only from an 'asfunction:' value prefix");

        // Every name that is neither a URL, nor style, nor a handler, nor on the plain-text
        // allowlist is ATTR_UNKNOWN. That cell is the whole of R5.
        assertTrue(byClassification.get(Canoe.ATTR_UNKNOWN).size() >= 15,
                () -> "the fail-closed cell must not be empty or nearly so; it holds F20's four"
                        + " policy names, srcdoc, content, the URL names R6 left off the list and"
                        + " the framework attributes nobody classified. Got: "
                        + byClassification.get(Canoe.ATTR_UNKNOWN));

        assertEquals(5, byClassification.size(),
                () -> "a name in this matrix produced a classification outside the five that are"
                        + " reachable from a name - ATTR_URI, ATTR_CSS, ATTR_JS, ATTR_HTML and"
                        + " ATTR_UNKNOWN: " + byClassification.keySet());
    }

    /**
     * <strong>R5's central property</strong>, and the one the fail-closed default is worth having
     * for: a name on none of the lists is suppressed, whatever it is.
     *
     * <p>Every other test in this file names the attributes somebody thought of. This one takes the
     * shapes a real page carries that nobody will ever add to a list — framework attributes, custom
     * element attributes, typos, truncations of recognised names, and names invented for this test —
     * and requires all of them to reach {@code CTX_SUPPRESS}. Before R5 every one of them was
     * {@code html()}, and that was not a bug in a list: it was the default, so no list could ever
     * have been long enough.
     *
     * <p>The cost is stated rather than hidden. {@code lowsr}, {@code hre} and {@code my-attr} are
     * values a template author would expect to see rendered, and they render empty now. That is what
     * the plain-text allowlist and {@code VelocityViewFactory.addPlainTextAttributes()} exist to
     * bound; the trade is deliberate, and trap 4 in the remediation plan is the record of it.
     */
    @Test
    public void everyNameOutsideTheAllowlistsIsSuppressed() {
        for (String unlisted : List.of(
                // near misses of names Canoe does classify
                "hrefx", "srcx", "styles", "datax", "backgrounds", "lowsrcs", "dynsrcs",
                "hre", "sr", "styl", "dat", "lowsr", "titl", "classs",
                // framework and custom-element attributes
                "my-widget-config", "hx-target", "ng-model", "v-bind", "x-data", "wire:model",
                // markup, refresh and policy sinks
                "srcdoc", "content", "sandbox", "rel", "integrity", "http-equiv",
                // URL names R6 left off the URL list
                "imagesrcset", "xml:base", "archive", "classid", "profile")) {
            assertEquals(Canoe.ATTR_UNKNOWN, attributeContextOf(unlisted),
                    unlisted + " is on none of Canoe's lists, so R5's fail-closed default must"
                            + " classify it ATTR_UNKNOWN");
            assertEquals(Canoe.CTX_SUPPRESS, CanoeTestSupport.contextAfter("<x " + unlisted + "=\""),
                    unlisted + " must reach CTX_SUPPRESS. If it reaches CTX_HTML_ATTR, the default"
                            + " has been un-inverted and F3's markup/policy half and F20 are open"
                            + " again.");
        }
    }

    /**
     * A listed name is matched whole, not as a prefix — which since R5 cuts both ways.
     *
     * <p>Before R5 this was {@code aRecognisedNameIsMatchedWholeRatherThanAsAPrefix} and its point
     * was that the exact-match table was brittle: {@code srcdoc} and {@code srcset} are one and
     * three characters from {@code src}, and both were findings. The brittleness is gone with the
     * default — a near miss now lands in suppression rather than in {@code html()} — and exact
     * matching is still worth pinning, because the <em>allowlist</em> is the thing that must not
     * grow by accident: if {@code titlex} inherited {@code title}'s classification, every attacker
     * would need is an attribute name with a listed prefix.
     *
     * <p>The two deliberate prefix families are the exception and are asserted as such: only
     * {@code aria-} and {@code data-} match by prefix, and only with their hyphen.
     */
    @Test
    public void aListedNameIsMatchedWholeExceptForTheTwoPrefixFamilies() {
        for (String nearMiss : List.of("hrefx", "srcx", "styles", "datax", "titlex", "classy",
                "backgrounds", "lowsrcs", "dynsrcs", "srcdoc", "arialabel", "datawidget")) {
            assertEquals(Canoe.ATTR_UNKNOWN, attributeContextOf(nearMiss),
                    nearMiss + " extends a listed name and must not inherit its classification");
        }

        // ...and shorter is not enough either, unless the shorter form is itself listed.
        for (String tooShort : List.of("hre", "sr", "styl", "dat", "lowsr")) {
            assertEquals(Canoe.ATTR_UNKNOWN, attributeContextOf(tooShort),
                    tooShort + ": a truncated name matches only if it is itself a listed name");
        }
        assertEquals(Canoe.ATTR_URI, attributeContextOf("background"),
                "...and 'background' is itself listed");

        // The two families, which do match by prefix, and only with the hyphen the standard uses.
        for (String prefixed : List.of("aria-label", "aria-anything-at-all", "data-widget",
                "data-x", "data-really-long-framework-name")) {
            assertEquals(Canoe.ATTR_HTML, attributeContextOf(prefixed),
                    prefixed + " is in a plain-text name family and must be html()-encoded");
        }
        assertEquals(Canoe.ATTR_URI, attributeContextOf("data"),
                "the exact name 'data' is <object data>, a URL, and is matched before the"
                        + " data- prefix ever runs");
        assertEquals(Canoe.ATTR_UNKNOWN, attributeContextOf("aria"),
                "'aria' alone is not an attribute and does not get the family's treatment");
    }

    /**
     * The two name lists {@code Canoe.java} declares, read out of the source rather than trusted,
     * and compared against this matrix.
     *
     * <p>Was {@code theSourceDeclaresExactlyTheNonHandlerBranchesTheMatrixExpects}, which parsed the
     * eight {@code // name} leaf branches out of {@code setTagAttributeContext()} and pinned
     * <strong>F7</strong> in a form that could not be argued with: the branch the author commented
     * {@code // content} compared the characters of {@code data} and yielded {@code ATTR_CONTENT},
     * the branch commented {@code // data} was byte-identical to it and could therefore never be
     * reached, and the author's own {@code XXX} marker sat directly above the pair. R7 resolved it —
     * {@code data} is a URL and {@code content} suppresses — so there is no pair left to pin and the
     * shape of the source has changed underneath the parser: the branches are two declared sets now.
     *
     * <p>What survives is the reason the old test existed, which was never really F7: a matrix that
     * agrees with behaviour cannot tell you whether the behaviour came from the list you are reading
     * or from somewhere else, so the list itself is worth reading. The two assertions below are that
     * {@code URL_ATTRIBUTE_NAMES} holds exactly the names this file classifies {@code ATTR_URI}, and
     * that no name whose suppression is the fix for a finding has appeared in the plain-text
     * allowlist — which is the edit somebody makes when a page loses a value and the diagnostic says
     * which attribute it was.
     */
    @Test
    public void theSourceDeclaresTheTwoNameListsTheMatrixExpects() throws IOException {
        String text = canoeSource();

        // Matched as code rather than as text: the constant's retirement is recorded in prose on
        // ATTR_UNKNOWN, and a test that forbade the words would forbid the explanation too.
        assertFalse(Pattern.compile("(int|case|=)\\s+ATTR_CONTENT").matcher(text).find(),
                "F7/R7: the ATTR_CONTENT constant went with the branch pair that was its only"
                        + " producer. If it is declared, assigned or switched on again, something is"
                        + " classifying a name as 'content' and the refresh.meta-content ledger row"
                        + " needs re-deciding.");
        assertFalse(text.contains("XXX"),
                "R7: the author's own XXX marker asked which of the two identical `data` branches"
                        + " was correct. Both are gone and the question is answered in"
                        + " URL_ATTRIBUTE_NAMES' javadoc.");

        Set<String> declaredUrlNames = declaredNames(text, "URL_ATTRIBUTE_NAMES");
        Set<String> expectedUrlNames = new LinkedHashSet<>();
        for (Arguments arguments : (Iterable<Arguments>) matrix()::iterator) {
            if (attributeContextOf((String) arguments.get()[0]) == Canoe.ATTR_URI) {
                expectedUrlNames.add(((String) arguments.get()[0]).toLowerCase());
            }
        }
        assertEquals(expectedUrlNames, declaredUrlNames,
                "Canoe.URL_ATTRIBUTE_NAMES and this matrix's ATTR_URI rows must be the same set."
                        + " A name in the source and not here is a routing decision with no reviewed"
                        + " corpus row behind it; a name here and not in the source cannot be"
                        + " reached at all.");

        Set<String> declaredPlainText = declaredNames(text, "PLAIN_TEXT_ATTRIBUTE_NAMES");
        for (String reserved : List.of("srcdoc", "content", "sandbox", "rel", "integrity",
                "http-equiv", "charset", "crossorigin", "referrerpolicy", "is", "style")) {
            assertFalse(declaredPlainText.contains(reserved),
                    reserved + " has been added to the plain-text allowlist. Its suppression is the"
                            + " fix for a finding - F3's markup half, F7's refresh row, or F20 - so"
                            + " this is a re-opened vulnerability rather than a widened allowlist."
                            + " Canoe.NAMES_THAT_MAY_NOT_BE_ADDED refuses the same names from"
                            + " application configuration; this is the source-side half of it.");
        }
        assertTrue(declaredPlainText.contains("title") && declaredPlainText.contains("id"),
                "...and the allowlist must still hold the ordinary text attributes, or the list"
                        + " being read is not the one that decides anything");
    }

    /**
     * The string literals of one declared name set in {@code Canoe.java}.
     *
     * <p>Reads the source rather than reflecting on the field, deliberately: the field is private
     * and making it visible for a test would be the first step towards something else reaching it,
     * and the claim being made is about what a reviewer sees when they open the file.
     */
    private static Set<String> declaredNames(String text, String field) {
        int start = text.indexOf("Set<String> " + field + " =");
        assertTrue(start > 0, field + " is no longer declared in Canoe.java");
        int end = text.indexOf(";", start);
        assertTrue(end > start, "cannot find the end of " + field);

        Set<String> names = new LinkedHashSet<>();
        Matcher literal = Pattern.compile("\"([^\"]+)\"").matcher(text.substring(start, end));
        while (literal.find()) {
            names.add(literal.group(1));
        }
        return names;
    }

    private static String canoeSource() throws IOException {
        Path source = Path.of("src/main/java/com/webkreator/qlue/view/Canoe.java");
        assertTrue(Files.isReadable(source),
                "cannot read " + source.toAbsolutePath() + "; this test must run with the project"
                        + " directory as its working directory");
        return Files.readString(source, StandardCharsets.UTF_8);
    }

    /**
     * There is no {@code CTX_CSS}: <strong>R14 closed F21 by deletion</strong>.
     *
     * <p>Was {@code currentContextCanNeverReturnCtxCss}, which asserted three ways over that
     * {@code currentContext()} never produced {@code CTX_CSS} while {@code encode()} still carried a
     * dead {@code CTX_CSS} arm — the review's mapping table listing six contexts of which only five
     * could be produced. R14 removed the ambiguity at the root: the {@code CTX_CSS} constant and its
     * {@code encode()} arm are gone, so "can never return {@code CTX_CSS}" becomes "there is no
     * {@code CTX_CSS}". The regression intent is unchanged and is what this test guards: {@code style}
     * still classifies as {@code ATTR_CSS} and still suppresses, and no {@code CTX_CSS} may reappear.
     *
     * <p>The decision R14 recorded is to keep suppressing. Canoe refuses to interpolate into CSS:
     * F23 shows a {@code style} value is decoded in series — HTML character references first, then the
     * CSS tokenizer — so a CSS encoder correct against all of it is a project, not a line. R13 (which
     * corrected {@code HtmlEncoder.css()}) is that project's precondition and is now met, but wiring a
     * CSS encoder in has not been decided; if it ever is, that is where {@code CTX_CSS} would come
     * back, and this test would fail and point at the reasoning on {@code Canoe.currentContext()}'s
     * {@code ATTR_CSS} case.
     *
     * <p>It is also the second reason the {@code ATTR_*} value is asserted alongside the context
     * throughout this file: at the context level a correctly-classified {@code style} attribute is
     * indistinguishable from one that fell through a hole in the switch, which is exactly the
     * ambiguity F11 was made of. R19 closed F11's attribute-value hole and left the comment and
     * DOCTYPE ones, so the ambiguity is smaller and the reason for asserting the {@code ATTR_*} value
     * is unchanged.
     */
    @Test
    public void thereIsNoCtxCssAndStyleStillSuppresses() throws IOException {
        // Measured: style is classified as ATTR_CSS and suppresses; so do the CSS element states.
        assertEquals(Canoe.ATTR_CSS, attributeContextOf("style"));
        assertEquals(Canoe.CTX_SUPPRESS, CanoeTestSupport.contextAfter("<div style=\""),
                "R14/F21: ATTR_CSS produces CTX_SUPPRESS");
        assertEquals(Canoe.CTX_SUPPRESS, CanoeTestSupport.contextAfter("<style>"),
                "R14/F21: the CSS element body produces CTX_SUPPRESS too, from the CSS state");
        assertEquals(Canoe.CTX_SUPPRESS, CanoeTestSupport.contextAfter("<style>a{}</sty"),
                "R14/F21: and so does CSS_END");

        // ...and by exhaustion over the whole matrix: every name reaches one of the five contexts a
        // name can produce, and never the deleted CTX_CSS slot (value 5).
        Set<Integer> reachable = Set.of(Canoe.CTX_SUPPRESS, Canoe.CTX_HTML_ATTR, Canoe.CTX_JS,
                Canoe.CTX_URI, Canoe.CTX_URI_RESOURCE);
        for (Arguments arguments : (Iterable<Arguments>) matrix()::iterator) {
            String name = (String) arguments.get()[0];
            int context = CanoeTestSupport.contextAfter("<x " + name + "=\"");
            assertNotEquals(5, context,
                    "R14/F21: " + name + " reached context 5, the deleted CTX_CSS slot");
            assertTrue(reachable.contains(context),
                    "R14/F21: " + name + " reached " + CanoeTestSupport.contextName(context)
                            + ", outside the five contexts a name can produce");
        }

        // The source fact behind R14, asserted on code tokens rather than prose (the deletion is
        // explained in comments that necessarily name CTX_CSS): the CTX_CSS constant is gone, nothing
        // returns it, and encode() carries no case arm for it. So a reviewer cannot make the
        // half-live/half-dead edit F21 warned about - uncommenting a CTX_CSS arm changing nothing
        // while its CTX_JS twin took effect. If any of these tokens comes back, so does the trap.
        String text = canoeSource();
        assertFalse(text.contains("CTX_CSS ="),
                "R14/F21: the CTX_CSS constant is declared again. It was deleted; re-adding a CSS"
                        + " context means a CSS encoder must be fit for F23's series of decoders"
                        + " (F16/R13) - do not reintroduce it without that decision.");
        assertFalse(text.contains("case CTX_CSS"),
                "R14/F21: encode() carries a CTX_CSS arm again. The dead arm was deleted; re-adding it"
                        + " recreates the half-live/half-dead trap R14 removed.");
        assertFalse(text.contains("return CTX_CSS"),
                "R14/F21: currentContext() returns CTX_CSS. It never did, and R14 deleted the"
                        + " constant, so this cannot compile without reintroducing it deliberately.");
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
     * F3's punchline, inverted by R6: the same payload into {@code href} and into
     * {@code xlink:href} now behaves the same way.
     *
     * <p>Was {@code hrefIsProtectedAndXlinkHrefIsNot}, and the asymmetry it recorded was the whole
     * of F3 in two attribute names: {@code href} was percent-encoded by {@code url()} while
     * {@code xlink:href} was entity-encoded by {@code html()} and the parser put it straight back,
     * so the safe-by-analogy assumption a developer would make was exactly wrong. R6 put
     * {@code xlink:href} on the URL list. The tokenizer never needed changing —
     * {@code isTagNameChar()} accepts {@code ':'}, so the name always scanned as one name; it simply
     * did not match {@code href}.
     *
     * <p>What the two names still share is {@code url()}'s own defects, and the test says so rather
     * than reading as "xlink:href is safe now": a rejected scheme is suppressed (F6's scheme half
     * works, by suppression since R12) and an off-origin host is not (F6's origin half does not exist,
     * and R9 owns it).
     */
    @Test
    public void hrefAndXlinkHrefReachTheSameEncoder() {
        Payload jsUrl = Payloads.JS_URL;

        String throughHref = CanoeTestSupport.render("<a href=\"$data\">x</a>", jsUrl.value())
                .decodedAttr("a", "href");
        String throughXlink = CanoeTestSupport
                .render("<svg><a xlink:href=\"$data\"><text>x</text></a></svg>", jsUrl.value())
                .decodedAttr("a", "xlink:href");

        assertEquals(throughHref, throughXlink,
                "R6: the two names are one classification now. Before it, the same payload came out"
                        + " percent-encoded from one and verbatim from the other.");
        assertFalse(throughXlink.contains("javascript:"),
                () -> "url() must neutralise the scheme. Got: " + throughXlink);
        assertEquals("", throughXlink,
                () -> "R12: javascript: is off the {http,https,mailto} allowlist, so url() rejects it"
                        + " to the empty string rather than escaping its colon. Got: " + throughXlink);

        // ...and the half of url() that does not exist, which the ledger records as F6 on both
        // names rather than as F3 on one of them.
        String offOrigin = CanoeTestSupport
                .render("<svg><a xlink:href=\"$data\"><text>x</text></a></svg>",
                        Payloads.PROTOCOL_RELATIVE.value())
                .decodedAttr("a", "xlink:href");
        assertEquals(Payloads.PROTOCOL_RELATIVE.value(), offOrigin,
                "R6 routes this name to url(), and url() is a scheme filter rather than an origin"
                        + " filter (F6): a protocol-relative URL is on its allowlist character for"
                        + " character. The ledger rows for this sink cite F6 now, not F3, and R9"
                        + " with R12 is what closes them.");
    }

    /**
     * F3's {@code srcdoc} row, inverted by R6: the one attribute whose correct encoding is a
     * <em>second</em> full HTML encode is suppressed instead.
     *
     * <p>Was {@code srcdocNeedsDoubleEncodingAndGetsSingle}. The mechanism it recorded is unchanged
     * and is why the decision went this way: the value of {@code srcdoc} is parsed as a whole HTML
     * document, so a single-encoded value hands the iframe's parser the attacker's raw markup, and
     * that is same-origin script execution. Building double encoding deliberately is a feature; R6
     * suppresses, and &sect;6 of the remediation plan records that as the settled scope.
     */
    @Test
    public void srcdocIsSuppressedRatherThanSingleEncoded() {
        String decoded = CanoeTestSupport
                .render("<iframe srcdoc=\"<p>$data</p>\"></iframe>", Payloads.SRCDOC_MARKUP.value())
                .decodedAttr("iframe", "srcdoc");

        assertEquals("<p></p>", decoded,
                "R6: the iframe document is parsed from the template's own markup with nothing"
                        + " between the tags. If the payload is back in this value, srcdoc has been"
                        + " given an encoder and single encoding is same-origin XSS.");
        assertEquals(Canoe.CTX_SUPPRESS, CanoeTestSupport.contextAfter("<iframe srcdoc=\""));
    }

    /**
     * F7 and F3's {@code content} row, both resolved by R7.
     *
     * <p>Was {@code metaRefreshContentIsUnrecognisedBecauseTheBranchComparesData}. The two branches
     * were byte-identical comparisons of {@code data} under comments reading {@code // content} and
     * {@code // data}, so {@code data} resolved to a suppressing context, {@code content} had no
     * branch at all, and the author's {@code XXX} marker sat above the pair asking which was
     * correct. The answer R7 recorded, and R10 confirmed: {@code data} is {@code <object data>} and
     * is a URL; {@code content} is a URL on exactly one element/attribute-value combination, and R10
     * deliberately left it suppressed because recognising it needs sibling-attribute-value tracking
     * (the {@code http-equiv="refresh"} value) and {@code N; url=} prefix parsing that the
     * per-reference encoding model cannot do. Suppression is fail-safe.
     */
    @Test
    public void theDataAndContentPairIsResolved() {
        assertEquals(Canoe.ATTR_URI, attributeContextOf("data"),
                "R7: <object data> is a URL");
        assertEquals(Canoe.ATTR_UNKNOWN, attributeContextOf("content"),
                "R7 default, R10 confirmed: 'content' suppresses - telling a meta refresh from a meta"
                        + " description needs sibling-attribute tracking Canoe does not have");

        String decoded = CanoeTestSupport
                .render("<meta http-equiv=\"refresh\" content=\"$data\">", Payloads.META_REFRESH.value())
                .decodedAttr("meta", "content");
        assertEquals("", decoded,
                "R7 default, R10 confirmed: the forced top-level navigation F3 recorded here needed no"
                        + " click and no script. The value is dropped, which is the deliberate final"
                        + " decision - a suppressed content renders empty, so no forced redirect"
                        + " occurs.");
    }

    /**
     * F20, inverted by R5: a policy directive no longer arrives at all.
     *
     * <p>Was {@code aPolicyDirectiveArrivesByteForByte}, and the reasoning it carried is why these
     * names are off the plain-text allowlist rather than being encoded harder. A policy token is
     * letters, digits, hyphens, underscores and spaces; {@code html()} passes the letters and digits
     * naked and turns the rest into character references the parser puts straight back, so the value
     * arrived byte for byte and <em>no</em> change to the encoder could have altered that. Encoding
     * was not insufficient here, it was inapplicable, and recognising the name and suppressing was
     * not the preferred fix but the only one.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("policyCases")
    public void aPolicyDirectiveIsSuppressedBecauseNoEncodingCouldHelp(XssCase testCase) {
        for (Payload payload : testCase.payloads()) {
            String decoded = VerdictEvaluator.render(testCase, payload.value())
                    .decodedAttr(testCase.selector(), testCase.attribute());
            assertEquals("", decoded,
                    () -> "F20/R5: " + testCase.attribute() + " must receive nothing at all. If"
                            + payload.value() + " is back in this value, the name is on the"
                            + " plain-text allowlist again, and html() cannot make a directive"
                            + " inert - the browser reads the decoded value as a directive whatever"
                            + " the encoder did on the way in.");
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
    // R5's two mitigations: the extension point and the diagnostic
    // ------------------------------------------------------------------

    /**
     * The application-level escape hatch, at the Canoe level.
     *
     * <p>Fail-closed is right and fail-closed with no way out is how a security control gets
     * switched off in production: a developer whose {@code <div my-widget-config="$x">} renders
     * empty reaches for {@code $_x.asis()}, which turns Canoe off for that value entirely. The
     * allowlist can be widened per engine, and what it grants is <em>plain text</em> — the value
     * still goes through {@code html()} and still cannot leave the attribute.
     *
     * <p>The set is per instance and is asserted to be so. A static would let one application in a
     * shared JVM widen another's allowlist, which is a security control being changed by an
     * unrelated deployment.
     */
    @Test
    public void anApplicationCanWidenThePlainTextAllowlistPerEngine() throws IOException {
        Set<String> extra = Canoe.normalisePlainTextAttributeNames(
                List.of("my-widget-config", "HX-Target"));

        assertEquals(Canoe.ATTR_HTML,
                new CanoeStateProbe(extra).feed("<div my-widget-config=\"").attributeContext(),
                "a configured name must reach the plain-text encoder");
        assertEquals(Canoe.ATTR_HTML,
                new CanoeStateProbe(extra).feed("<div hx-target=\"").attributeContext(),
                "...and the names are lower-cased on the way in, because the name scan lower-cases"
                        + " as it buffers");

        // Still text, not markup: the grant is html(), not a bypass.
        CanoeTestSupport.RenderResult rendered = CanoeTestSupport.render(
                "<div my-widget-config=\"$data\">x</div>",
                Map.of("data", Payloads.ATTR_DOUBLE_QUOTE_BREAKOUT.value()),
                CanoeTestSupport.RenderOptions.defaults(),
                writer -> new Canoe(writer, extra));
        assertEquals(Payloads.ATTR_DOUBLE_QUOTE_BREAKOUT.value(),
                rendered.decodedAttr("div", "my-widget-config"),
                "the value arrives whole and inside the attribute; html() escaped the quote and the"
                        + " parser handed it back as text");
        assertEquals(1, rendered.dom().select("div").size(),
                "...and it created no element of its own, which is what makes the grant a"
                        + " plain-text one rather than a bypass");

        // ...and the widening belongs to the instance that was configured.
        assertEquals(Canoe.ATTR_UNKNOWN,
                new CanoeStateProbe().feed("<div my-widget-config=\"").attributeContext(),
                "a Canoe that was not configured must be unaffected. The allowlist is per engine;"
                        + " a static would let one application widen another's.");

        // A null set is "the application said nothing" rather than a NullPointerException at the
        // first attribute of the first page. The one-argument constructor takes this path too.
        assertEquals(Canoe.ATTR_UNKNOWN,
                new CanoeStateProbe(null).feed("<div my-widget-config=\"").attributeContext(),
                "a null extra-allowlist must behave as an empty one");
        assertEquals(Canoe.ATTR_HTML,
                new CanoeStateProbe(null).feed("<div title=\"").attributeContext(),
                "...and must not disturb the built-in allowlist");
    }

    /**
     * The names the extension point refuses, which is what stops it being a way to re-open a
     * finding through configuration.
     *
     * <p>Every name below is one whose <em>suppression is a recorded decision</em>: adding it back
     * to the plain-text set would restore exactly the behaviour F3 or F20 records, or would undo
     * R6's own choice not to route a URL-bearing name. The refusal is loud — an exception at
     * configuration time — rather than a silent ignore, because a configuration that appears to work
     * and does nothing is the same class of defect as a suppression with no diagnostic.
     *
     * <p>The last group in the list is the one that is easiest to miss and the reason it is asserted
     * separately below: {@code imagesrcset}, {@code xml:base}, {@code archive}, {@code classid} and
     * {@code profile} are URL-bearing names R6 deliberately left off the URL set, on the argument
     * that suppression is <em>stronger</em> than {@code url()}. Putting one on the plain-text
     * allowlist does not give it {@code url()} — it gives it {@code html()}, which the HTML parser
     * decodes before the URL parser sees it, and that is F3 exactly. The strongest answer and the
     * weakest one are one configuration line apart, so the line has to be refused.
     */
    @Test
    public void theExtensionPointRefusesTheNamesWhoseSuppressionIsTheFix() {
        for (String refused : List.of("sandbox", "rel", "integrity", "srcdoc", "content",
                "http-equiv", "charset", "crossorigin", "referrerpolicy", "is", "style",
                "href", "src", "formaction", "xlink:href", "data")) {
            assertThrows(IllegalArgumentException.class,
                    () -> Canoe.normalisePlainTextAttributeNames(List.of(refused)),
                    refused + " must be refused: it is either classified before the application"
                            + " allowlist is consulted, or its suppression is the fix for a finding");
        }

        // The URL-bearing names R6 chose to suppress rather than to route. html() on any of these is
        // F3 on a URL sink, which is worse than the url() they were denied.
        for (String urlBearing : List.of("imagesrcset", "xml:base", "archive", "classid",
                "profile")) {
            assertThrows(IllegalArgumentException.class,
                    () -> Canoe.normalisePlainTextAttributeNames(List.of(urlBearing)),
                    urlBearing + " resolves a URL, so 'treat it as plain text' is never the thing"
                            + " the application means. Suppression is R6's recorded decision for it;"
                            + " configuration must not be able to replace that with html().");
        }

        // The on* rule has no exceptions, and configuration is not one either - in any casing, since
        // the parser lower-cases every name it buffers and this check has to agree with it.
        assertThrows(IllegalArgumentException.class,
                () -> Canoe.normalisePlainTextAttributeNames(List.of("onclick")));
        assertThrows(IllegalArgumentException.class,
                () -> Canoe.normalisePlainTextAttributeNames(List.of("ONCLICK")),
                "an upper-case handler name is the same name: normalisation lower-cases before it"
                        + " tests the prefix, or the rule could be evaded by holding shift");
        assertThrows(IllegalArgumentException.class,
                () -> Canoe.normalisePlainTextAttributeNames(List.of("SandBox")),
                "...and the same is true of every reserved name");
        assertThrows(IllegalArgumentException.class,
                () -> Canoe.normalisePlainTextAttributeNames(List.of("only")),
                "including the benign names the prefix rule catches: an exception here is the start"
                        + " of the allowlist R4 deleted");

        // A name the tokenizer could never produce is refused too, because a set entry nothing can
        // match is a security configuration that silently does nothing.
        assertThrows(IllegalArgumentException.class,
                () -> Canoe.normalisePlainTextAttributeNames(List.of("my widget")));
        assertThrows(IllegalArgumentException.class,
                () -> Canoe.normalisePlainTextAttributeNames(List.of("title=x")),
                "a name carrying the character that ends a name in the tokenizer is not one name,"
                        + " and accepting it would let a property line read as though it configured"
                        + " something it cannot");
        assertThrows(IllegalArgumentException.class,
                () -> Canoe.normalisePlainTextAttributeNames(List.of("a\"b")));
        assertThrows(IllegalArgumentException.class,
                () -> Canoe.normalisePlainTextAttributeNames(List.of("-leading-hyphen")));
        assertThrows(IllegalArgumentException.class,
                () -> Canoe.normalisePlainTextAttributeNames(
                        List.of("a".repeat(Canoe.MAX_TAGNAME_LEN))),
                "a name longer than the name scan can buffer could never match either - Canoe raises"
                        + " 'Attribute name too long' before it classifies anything. The length is"
                        + " read from MAX_TAGNAME_LEN rather than written out: R20 raised it from 36"
                        + " to 128, and this assertion is about the relationship between the"
                        + " validator and the buffer, not about either number");
        assertEquals(Set.of("a".repeat(Canoe.MAX_TAGNAME_LEN - 1)),
                Canoe.normalisePlainTextAttributeNames(
                        List.of("a".repeat(Canoe.MAX_TAGNAME_LEN - 1))),
                "...and one character shorter is accepted, which is the boundary the tokenizer has");

        // ...and the two shapes that are not errors: nothing at all, and blank entries in a list.
        // A property written as "a, , b" is a typo rather than a misconfiguration.
        assertEquals(Set.of(), Canoe.normalisePlainTextAttributeNames(null),
                "a null collection is 'the application said nothing'");
        assertEquals(Set.of("a-b"),
                Canoe.normalisePlainTextAttributeNames(List.of(" ", "a-b", "")),
                "blank entries are skipped rather than rejected");
        assertThrows(IllegalArgumentException.class,
                () -> Canoe.normalisePlainTextAttributeNames(Arrays.asList("a-b", null)),
                "...but a null element is a programming error and says so");
    }

    /**
     * The guard is on the constructor and not only on the validator, so a caller cannot reach the
     * plain-text allowlist by going around {@code VelocityViewFactory}.
     *
     * <p>{@link Canoe} is a public {@link java.io.Writer} with a public constructor taking the
     * extra-name set. If the constructor stored what it was handed, "the factory validated it" would
     * be the whole of the control, and {@code new Canoe(writer, Set.of("sandbox"))} would put F20's
     * worst name back on {@code html()} from application code with no configuration anybody could
     * audit. The validation is cheap — once per render over a handful of strings — and the
     * alternative is a guard that holds only for callers who happen to use the front door.
     */
    @Test
    public void theCanoeConstructorValidatesTheNamesItIsHandedRatherThanTrustingTheCaller()
            throws IOException {
        for (String refused : List.of("sandbox", "srcdoc", "onclick", "xml:base")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new Canoe(new java.io.StringWriter(), Set.of(refused)),
                    refused + " must be refused by the constructor too, or the extension point's"
                            + " guard is only a convention about which method callers use");
        }

        // ...and a legitimate set still works, in any casing, because the constructor normalises
        // rather than merely checking.
        assertEquals(Canoe.ATTR_HTML,
                new CanoeStateProbe(Set.of("My-Widget-Config")).feed("<div my-widget-config=\"")
                        .attributeContext(),
                "the constructor lower-cases what it accepts, so a set that was not put through"
                        + " normalisePlainTextAttributeNames first still matches the parser's"
                        + " lower-cased name rather than silently matching nothing");
    }

    /**
     * The diagnostic R5 owes a developer: which attribute the value went missing in.
     *
     * <p>A suppressed value is otherwise indistinguishable from an empty one — that was F11's whole
     * complaint, and it applies to every name the fail-closed default catches whether or not R19 has
     * routed the position the reference sits in. Canoe logs the name at
     * debug level from {@code currentContext()}, which is called once per inserted reference, so the
     * message appears for the drop rather than for every attribute on the page.
     *
     * <p>What is asserted here is the field the message interpolates, because the log call itself is
     * not observable without installing a backend. The property that matters is that it names the
     * attribute the reference is <em>in</em>: a diagnostic pointing at the previous element is worse
     * than none, because the developer would go and look at the wrong markup.
     */
    @Test
    public void theSuppressionDiagnosticNamesTheAttributeTheReferenceIsIn() throws IOException {
        CanoeStateProbe probe = new CanoeStateProbe().feed("<div my-widget-config=\"");
        assertEquals(Canoe.CTX_SUPPRESS, probe.currentContext());
        assertEquals("my-widget-config", probe.unknownAttributeName());

        // A recognised name clears it, so the field cannot name a stale attribute.
        assertNull(new CanoeStateProbe().feed("<div my-widget-config=\"x\" title=\"")
                        .unknownAttributeName(),
                "the name is cleared when the next attribute is recognised, or the message would"
                        + " blame an attribute that rendered perfectly well");
        assertEquals("ng-model",
                new CanoeStateProbe().feed("<div data-x=\"y\" ng-model=\"").unknownAttributeName(),
                "and an unrecognised name after a recognised one names itself rather than"
                        + " inheriting anything");

        // The shapes that would let a stale name survive if the capture were not cleared per
        // attribute name: a second unrecognised attribute on the same element, a self-closing tag
        // between the two, a valueless attribute in between, and a nested element.
        assertEquals("my-b",
                new CanoeStateProbe().feed("<div my-a=\"x\" my-b=\"").unknownAttributeName(),
                "two unrecognised attributes on one element must each name themselves, or the"
                        + " message points at the first thing that went wrong on the element rather"
                        + " than at the value the developer is missing");
        assertEquals("hx-x",
                new CanoeStateProbe().feed("<img my-a=\"x\"/><div hx-x=\"").unknownAttributeName(),
                "a self-closing tag in between must not leave the previous element's name behind");
        assertNull(new CanoeStateProbe().feed("<img my-a=\"x\"/><div title=\"")
                        .unknownAttributeName(),
                "...and the clear survives the self-closing tag too");
        assertEquals("ng-model",
                new CanoeStateProbe().feed("<input my-a disabled ng-model=\"")
                        .unknownAttributeName(),
                "an attribute with no value is classified like any other, so the valueless"
                        + " unrecognised name is cleared by the recognised one that follows it");
        assertNull(new CanoeStateProbe().feed("<div my-a=\"x\"><span data-y=\"")
                        .unknownAttributeName(),
                "and a nested element starts from its own attributes");

        // ...and the source carries the call, at debug level, with the name in it.
        String text = canoeSource();
        String currentContext = methodBody(text, "public int currentContext()");
        assertTrue(currentContext.contains("log.debug("),
                "R5: currentContext() must log the suppressed attribute name at debug level. A"
                        + " suppressed value with no diagnostic is indistinguishable from an empty"
                        + " one, which is what sends a developer to $_x.asis().");
        assertTrue(currentContext.contains("unknownAttributeName"),
                "...and the message must carry the attribute name, which is the one piece of"
                        + " information the developer does not already have");
    }

    /**
     * Where a developer has to point a logging configuration to see that diagnostic, which is the
     * only actionable half of "suppression is silent".
     *
     * <p>{@code README.md} and {@code qlue_user_guide.md} both tell a developer to raise
     * {@code com.webkreator.qlue.view.Canoe} to DEBUG when a value has gone missing, and that the
     * message names the attribute and points at
     * {@code VelocityViewFactory.addPlainTextAttributes()}. That is three concrete claims — a logger
     * name, a level, and what the text says — and a logger name is exactly the kind of thing that
     * goes stale silently when a class moves package.
     *
     * <p>Asserted on the logger's <em>name</em> rather than on captured output, deliberately. The
     * suite runs with slf4j-simple, whose per-logger level is fixed when the logger is constructed —
     * at {@link Canoe}'s class initialisation, long before any one test could raise it — so capturing
     * a DEBUG line would mean turning debug on for the whole run and reading a few thousand of them
     * off {@code System.err}. The level is pinned at the call site by
     * {@link #theSuppressionDiagnosticNamesTheAttributeTheReferenceIsIn} instead, which reads the
     * source. Between the two, every word of the documented instruction has something behind it.
     */
    @Test
    public void theSuppressionDiagnosticGoesToTheLoggerTheDocumentationNames() throws Exception {
        java.lang.reflect.Field logField = Canoe.class.getDeclaredField("log");
        logField.setAccessible(true);
        org.slf4j.Logger logger = (org.slf4j.Logger) logField.get(null);

        assertEquals("com.webkreator.qlue.view.Canoe", logger.getName(),
                "the documentation tells a developer to raise this logger to DEBUG to find out"
                        + " which attribute swallowed a value; if the class moves, the instruction"
                        + " in README.md and qlue_user_guide.md moves with it");

        String currentContext = methodBody(canoeSource(), "public int currentContext()");
        assertTrue(currentContext.contains("addPlainTextAttributes()"),
                "...and the message must name the way out, not only the problem: a developer who"
                        + " has just found the drop needs to be sent to the extension point rather"
                        + " than to $_x.asis()");
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
                        + " trailing attribute takes the page down. This reads like <br/> from F13's"
                        + " table reached from the attribute side, and R20 decided it is not the same"
                        + " shape: a '/' that ENDS A TAG NAME is a self-closing start tag and is what"
                        + " R20 accepted, while a '/' followed by another ATTRIBUTE is the HTML"
                        + " Standard's unexpected-solidus-in-tag parse error, which no serializer"
                        + " emits - see the separator.attribute-after-slash corpus row");
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
     * only thing in the buffer is the name itself and its terminator. That used to be a precondition
     * rather than a convention: F5's residue was a separate axis owned by
     * {@code AttributePrefixTest}, and mixing it in here would have made a failure ambiguous between
     * "the name is unrecognised" and "an earlier name armed the buffer". R3 clears the buffer on
     * every reuse, so a fresh Canoe and a used one now agree; the probe is unchanged because a
     * failure here should still be about one name.
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
