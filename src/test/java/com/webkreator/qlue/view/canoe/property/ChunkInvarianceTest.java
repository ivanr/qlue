package com.webkreator.qlue.view.canoe.property;

import com.webkreator.qlue.view.Canoe;
import com.webkreator.qlue.view.CanoeStateProbe;
import com.webkreator.qlue.view.canoe.CanoeTestSupport;
import com.webkreator.qlue.view.canoe.corpus.CanoeCorpus;
import com.webkreator.qlue.view.canoe.corpus.XssCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chunking property: <strong>where the template text is cut into {@code write()} calls must not
 * change what Canoe does with it.</strong>
 *
 * <p>Canoe is a character-by-character parser behind a {@link java.io.Writer}, so nothing about its
 * result should depend on how the characters arrive. That is worth stating as a property rather than
 * assuming, because a dependence on buffer boundaries is invisible in ordinary testing — every test
 * in this suite writes whole templates — and becomes live the first time anything buffers. Velocity's
 * {@code ASTText} writes one string per literal text node today; a different renderer, an
 * {@code org.apache.velocity.io.Filter}, a {@code BufferedWriter} around the response, or a servlet
 * container that chunks the output stream all change the split points and none of them is a code
 * change to Qlue.
 *
 * <h2>What holds</h2>
 *
 * <p>Split at <em>every</em> index, and at a seeded sample of multi-way splits, over all
 * {@value #CORPUS_SIZE_NOTE} corpus templates: the output bytes, whether an encoding error was
 * raised, the message when one was, the final {@code currentContext()}, and the final parser state
 * are all identical to the unsplit run. <strong>No counterexample exists.</strong> Every split point
 * of every corpus template was tried — 9,996 two-way splits — so this half of the property is
 * exhaustive rather than sampled.
 *
 * <h2>The offset entry point holds too, since R15</h2>
 *
 * <p>Every assertion above uses {@code write(String)}, which the JDK's {@code Writer} implements as
 * {@code write(cbuf, 0, len)}. Feeding the <em>same</em> chunks as slices of one array — the
 * {@code write(char[], offset, length)} entry point, with a non-zero offset — used to collapse the
 * property immediately. That was <strong>F9</strong>: the loop bound was {@code i < len} where it must
 * be {@code i < offset + len}, so the parser saw only the first {@code length - offset} characters of
 * the range while all {@code length} were written out, and the number of characters that escaped the
 * state machine was exactly the offset. R15 corrected the bound, so the property now holds over both
 * entry points: a mid-point slice desynchronises nothing.
 *
 * <p>The second half of this file is the regression net for that fix. It used to measure how far from
 * invariant the offset entry point was — a count, {@code 243} of {@code 275} templates, of how many a
 * single mid-point slice desynchronised — and it now asserts that same count is <em>zero</em>.
 * {@code CanoeWriterContractTest} (T7) owns F9's per-entry-point contract; what is here is its scale.
 */
public class ChunkInvarianceTest {

    /** Documentation only; the real number comes from {@link CanoeCorpus#all()} at run time. */
    static final String CORPUS_SIZE_NOTE = "275";

    /**
     * How many random multi-way splittings each template gets, on top of the exhaustive two-way
     * sweep. Seeded, so a failure is reproducible.
     *
     * <p>This is the one sampled part of the file and it is sampled because it has to be: the number
     * of ways to cut a 232-character template into up to five pieces is in the billions. What is
     * <em>not</em> sampled is the two-way sweep, which is exhaustive, and a multi-way split can only
     * break the property in a way a two-way split cannot if the parser carries state across more than
     * one boundary — which is what the multi-way sample is looking for.
     */
    private static final int RANDOM_SPLITTINGS_PER_TEMPLATE = 20;

    /** Up to this many pieces per random splitting. */
    private static final int MAX_CHUNKS = 5;

    private static final long SEED = 0x5EEDL;

    static List<XssCase> corpus() {
        return CanoeCorpus.all();
    }

    // ------------------------------------------------------------------
    // The property
    // ------------------------------------------------------------------

    /**
     * Split at every index: the result is identical to the unsplit run.
     *
     * <p>All split points are checked in one test rather than one test per point, because the unit
     * the property is quantified over is the <em>template</em> — "this template parses the same
     * however it is cut" — and 9,996 JUnit rows would say the same thing more slowly. Every
     * divergence is collected and reported together rather than stopping at the first, so a failure
     * shows the shape of the problem instead of one instance of it.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("corpus")
    public void splittingAtEveryIndexChangesNothing(XssCase testCase) {
        String text = testCase.template();
        Trace whole = feedChunks(text, new int[0]);
        List<String> divergences = new ArrayList<>();

        for (int index = 0; index <= text.length(); index++) {
            Trace split = feedChunks(text, new int[]{index});
            if (!whole.equals(split)) {
                divergences.add("split at " + index + "\n      whole: " + whole
                        + "\n      split: " + split);
            }
        }

        assertTrue(divergences.isEmpty(),
                () -> testCase.id() + ": " + divergences.size() + " of " + (text.length() + 1)
                        + " split points changed the result. A parser whose answer depends on where"
                        + " the writer happened to cut the text fails open the first time anything"
                        + " buffers.\n  Template: " + CanoeTestSupport.quote(text)
                        + "\n  " + String.join("\n  ", divergences));
    }

    /**
     * The multi-way sample, for state that survives more than one boundary.
     *
     * <p>{@value #RANDOM_SPLITTINGS_PER_TEMPLATE} seeded splittings of up to {@value #MAX_CHUNKS}
     * pieces each. A two-way split can only expose "the parser lost something at one boundary"; this
     * looks for "the parser lost something at a boundary and the loss only shows after another one",
     * which is the shape the shared {@code buf} field could plausibly produce.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("corpus")
    public void randomMultiWaySplitsChangeNothingEither(XssCase testCase) {
        String text = testCase.template();
        Trace whole = feedChunks(text, new int[0]);
        Random random = new Random(SEED + testCase.id().hashCode());
        List<String> divergences = new ArrayList<>();

        for (int attempt = 0; attempt < RANDOM_SPLITTINGS_PER_TEMPLATE; attempt++) {
            int[] points = randomSplitPoints(random, text.length());
            Trace split = feedChunks(text, points);
            if (!whole.equals(split)) {
                divergences.add("splits at " + java.util.Arrays.toString(points)
                        + "\n      whole: " + whole + "\n      split: " + split);
            }
        }

        assertTrue(divergences.isEmpty(),
                () -> testCase.id() + ": " + divergences.size() + " of "
                        + RANDOM_SPLITTINGS_PER_TEMPLATE + " random splittings changed the result."
                        + "\n  Template: " + CanoeTestSupport.quote(text)
                        + "\n  " + String.join("\n  ", divergences));
    }

    /**
     * The property has to be able to fail, and this proves it can.
     *
     * <p>A deliberately broken feed — one that drops a character at the split point — must be caught
     * by the same comparison the two tests above use. Without this, "no divergence anywhere in the
     * corpus" is equally consistent with "the comparison compares nothing", which is the failure mode
     * &sect;8 warns about and which this suite has already found twice in its own tests.
     */
    @Test
    public void theComparisonWouldNoticeADivergence() {
        String text = "<a href=\"/x\">y</a>";
        Trace whole = feedChunks(text, new int[0]);

        Trace lossy = trace(probe -> {
            probe.feed(text.substring(0, 5));
            probe.feed(text.substring(6));       // one character dropped
        });

        assertNotEquals(whole, lossy,
                "if this passes, the Trace comparison is not comparing anything and both properties"
                        + " above are vacuous");
    }

    // ------------------------------------------------------------------
    // R15: the offset entry point, now invariant like the offset-0 one
    // ------------------------------------------------------------------

    /**
     * A non-zero offset now parses exactly the requested range, so it ends in the same context as the
     * offset-0 write of the same characters.
     *
     * <p>Inverted from {@code aNonZeroOffsetSkipsExactlyOffsetCharactersFromTheParser}, which asserted
     * that {@code (text, 3, length - 3)} left the parser in a context <em>other</em> than
     * {@code CTX_HTML} — three characters skipped by the old {@code i < len} bound. With the bound
     * corrected to {@code i < offset + len} every character in the range is parsed, so the offset write
     * reaches the same context as {@code write} of the identical substring, and both end in
     * {@code CTX_HTML}.
     */
    @Test
    public void aNonZeroOffsetParsesExactlyTheRequestedRange() throws IOException {
        char[] text = "<a href=\"/x\">y</a>".toCharArray();

        CanoeStateProbe atZero = new CanoeStateProbe();
        atZero.feed(text, 0, text.length);
        assertEquals(Canoe.CTX_HTML, atZero.currentContext(),
                "the whole string is parsed at offset 0");

        CanoeStateProbe atThree = new CanoeStateProbe();
        atThree.feed(text, 3, text.length - 3);
        assertEquals(new String(text, 3, text.length - 3), atThree.output(),
                "every character in the range is written to the response");
        assertEquals(Canoe.CTX_HTML, atThree.currentContext(),
                "R15: ...and every one is now parsed, so the machine ends where the same substring"
                        + " written at offset 0 would leave it. Actual: "
                        + CanoeTestSupport.contextName(atThree.currentContext()));
    }

    /**
     * F9's scale, over the corpus, driven to zero: how many templates a single mid-point slice
     * desynchronises.
     *
     * <p>Measured before R15: <strong>243 of 275</strong>. This test asserted that count was more than
     * half the corpus, as F9's signature; it now asserts it is <em>zero</em>, which is R15's. The slice
     * feeds each template as two slices of one array through {@code write(char[], offset, length)} —
     * the second slice at a non-zero offset — and compares the resulting {@link Trace} to the unsplit
     * run. Under the corrected bound every character of every slice is parsed, so the slice trace and
     * the whole trace are identical for every template, and the count is zero.
     */
    @Test
    public void noMidPointSliceDesynchronisesTheCorpus() {
        int divergent = 0;
        List<String> examples = new ArrayList<>();

        for (XssCase testCase : CanoeCorpus.all()) {
            String text = testCase.template();
            if (text.length() < 4) {
                continue;
            }
            Trace whole = feedChunks(text, new int[0]);
            Trace sliced = feedSlices(text, text.length() / 2);
            if (!whole.equals(sliced)) {
                divergent++;
                if (examples.size() < 3) {
                    examples.add(testCase.id() + ": whole=" + whole + " sliced=" + sliced);
                }
            }
        }

        int corpusSize = CanoeCorpus.all().size();
        int finalDivergent = divergent;
        assertEquals(0, divergent,
                () -> "R15: a mid-point slice through write(char[], offset, length) must now parse"
                        + " the same as the unsplit run, but " + finalDivergent + " of " + corpusSize
                        + " templates still diverged. Before R15 this was 243 of 275 (F9); a non-zero"
                        + " count here means the loop bound is wrong again. Examples: " + examples);
    }

    /**
     * The invariance property and its former F9 exception are the same statement seen from two sides,
     * so they are compared directly on one template.
     *
     * <p>Same template, same two pieces, two entry points: {@code write(String)} twice is invariant,
     * and — since R15 — {@code write(char[], offset, length)} twice is too. Inverted from
     * {@code theSameTwoPiecesAreInvariantAsStringsAndNotAsSlices}, whose second assertion was an
     * {@code assertNotEquals} pinning that the slice path diverged; it is now an {@code assertEquals}.
     */
    @Test
    public void theSameTwoPiecesAreInvariantAsStringsAndAsSlices() {
        String text = "<a href=\"javascript:x\">y</a>";
        int at = 10;

        assertEquals(feedChunks(text, new int[0]), feedChunks(text, new int[]{at}),
                "two write(String) calls: invariant");
        assertEquals(feedChunks(text, new int[0]), feedSlices(text, at),
                "R15: the identical two pieces through write(char[],int,int) are invariant too");
    }

    // ------------------------------------------------------------------
    // Feeding
    // ------------------------------------------------------------------

    /**
     * Feeds the text as substrings split at the given points, through {@code write(String)}.
     *
     * <p>Stops at the first encoding error, because that is what a real caller does: Velocity
     * abandons the render when the writer throws, so continuing to feed would compare a sequence of
     * writes that could never happen.
     */
    private static Trace feedChunks(String text, int[] splitPoints) {
        List<String> pieces = pieces(text, splitPoints);
        return trace(probe -> {
            for (String piece : pieces) {
                probe.feed(piece);
            }
        });
    }

    /**
     * Feeds the same pieces as slices of one shared array, through
     * {@code write(char[], offset, length)} — the F9 entry point. Every call after the first has a
     * non-zero offset.
     */
    private static Trace feedSlices(String text, int splitPoint) {
        char[] chars = text.toCharArray();
        return trace(probe -> {
            probe.feed(chars, 0, splitPoint);
            probe.feed(chars, splitPoint, chars.length - splitPoint);
        });
    }

    private static List<String> pieces(String text, int[] splitPoints) {
        List<String> pieces = new ArrayList<>();
        int previous = 0;
        for (int point : splitPoints) {
            pieces.add(text.substring(previous, point));
            previous = point;
        }
        pieces.add(text.substring(previous));
        return pieces;
    }

    private static int[] randomSplitPoints(Random random, int length) {
        int chunks = 2 + random.nextInt(MAX_CHUNKS - 1);
        TreeSet<Integer> points = new TreeSet<>();
        for (int i = 0; i < chunks - 1; i++) {
            points.add(random.nextInt(length + 1));
        }
        int[] result = new int[points.size()];
        int index = 0;
        for (int point : points) {
            result[index++] = point;
        }
        return result;
    }

    private interface Feed {
        void accept(CanoeStateProbe probe) throws IOException;
    }

    private static Trace trace(Feed feed) {
        CanoeStateProbe probe = new CanoeStateProbe();
        String error = null;
        try {
            feed.accept(probe);
        } catch (IOException e) {
            error = e.getMessage();
        }
        return new Trace(probe.output(), error, probe.currentContext(), probe.state());
    }

    /**
     * Everything an observer of a render can see: the bytes, whether it failed and how, and where
     * the machine ended up.
     *
     * <p>The final state is compared as well as the final context because several states collapse to
     * {@code CTX_SUPPRESS}; a context-only comparison would call two genuinely different parser
     * positions equal, which is exactly the blindness this property exists to rule out.
     */
    private static final class Trace {

        private final String output;
        private final String error;
        private final int context;
        private final int state;

        Trace(String output, String error, int context, int state) {
            this.output = output;
            this.error = error;
            this.context = context;
            this.state = state;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Trace)) {
                return false;
            }
            Trace that = (Trace) other;
            return context == that.context && state == that.state
                    && output.equals(that.output) && Objects.equals(error, that.error);
        }

        @Override
        public int hashCode() {
            return Objects.hash(output, error, context, state);
        }

        @Override
        public String toString() {
            return "out=" + CanoeTestSupport.quote(output)
                    + " ctx=" + CanoeTestSupport.contextName(context)
                    + " state=" + CanoeStateProbe.stateName(state)
                    + (error == null ? "" : " error=" + error);
        }
    }
}
