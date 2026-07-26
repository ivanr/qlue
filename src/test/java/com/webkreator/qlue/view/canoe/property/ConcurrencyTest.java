package com.webkreator.qlue.view.canoe.property;

import com.webkreator.qlue.util.HtmlEncoder;
import com.webkreator.qlue.view.Canoe;
import com.webkreator.qlue.view.canoe.CanoeTestSupport;
import com.webkreator.qlue.view.canoe.corpus.CanoeCorpus;
import com.webkreator.qlue.view.canoe.corpus.Payload;
import com.webkreator.qlue.view.canoe.corpus.Payloads;
import com.webkreator.qlue.view.canoe.corpus.VerdictEvaluator;
import com.webkreator.qlue.view.canoe.corpus.XssCase;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One {@link Canoe} per render, and nothing shared behind it (T32).
 *
 * <h2>The assumption, and why it is worth a test</h2>
 *
 * <p>{@code VelocityViewFactory.render()} constructs a {@code new Canoe(writer)} on every call
 * (line 204), so two concurrent requests get two tokenizers. That is the whole of Canoe's
 * thread-safety story, and the rest of this suite depends on it without ever saying so: every
 * measurement in every other file is single-threaded, so a shared mutable field would be invisible
 * to all of them and would show up in production as one user's page carrying another user's
 * encoding context. {@code buf} is the field that would do it — it is per instance, and until R3 it
 * was also the field F5 showed was never cleared, so "per instance" was doing visible work. R3
 * clears it on every reuse, which makes the remaining shared state the parser's own position in the
 * document; that is no less per-request and no less capable of crossing two renders.
 *
 * <p>Two things are actually asserted, because "it passed under load" is not a proof:
 *
 * <ol>
 *   <li><strong>Output equality.</strong> Every corpus case is rendered single-threaded first, then
 *       all of them are rendered again on a thread pool several times wider than the case count,
 *       interleaved by a barrier so the renders genuinely overlap. Every concurrent output must be
 *       byte-identical to its single-threaded twin, including the rejection cases and their error
 *       messages.
 *   <li><strong>No mutable static state.</strong> Reflection over {@code Canoe} and
 *       {@code HtmlEncoder} requires every static field to be {@code final} and of an immutable
 *       type, with the exceptions named and justified. A load test finds a race when it happens to
 *       lose; a structural assertion finds the field.
 * </ol>
 *
 * <h2>What it found</h2>
 *
 * <p>Nothing races, and one field is one keyword away from being able to:
 * {@code HtmlEncoder.uriPattern} is {@code private static Pattern} and <em>not</em> {@code final}.
 * A {@code Pattern} is immutable and thread-safe, and a {@code Matcher} is created per call, so
 * there is no defect today. It is recorded in {@link #everyStaticFieldIsFinalAndImmutable} rather
 * than in the review, because "nobody has assigned to it" is not a property a test can hold on to
 * and the fix is one keyword.
 */
public class ConcurrencyTest {

    /** Threads. Deliberately far above the case count, so renders overlap rather than queue. */
    private static final int THREADS = 32;

    /** How many times the whole corpus is rendered concurrently. */
    private static final int ROUNDS = 2;

    // ------------------------------------------------------------------
    // Output equality under concurrency
    // ------------------------------------------------------------------

    /**
     * Every corpus case renders identically whether it is alone or one of hundreds in flight.
     *
     * <p>The baseline is computed first, single-threaded, and stored; the concurrent renders are
     * then compared against it rather than against each other. Comparing the concurrent runs against
     * each other would pass if <em>every</em> thread saw the same corrupted state, which is exactly
     * what a shared buffer would produce.
     *
     * <p>Every task is submitted before any of them is released, by a {@link CountDownLatch} start
     * gate. Without it the pool drains the queue almost serially on a fast machine, and a
     * concurrency test that never overlaps is green for the wrong reason - which is why the number
     * of distinct threads is asserted rather than assumed.
     */
    @Test
    public void everyCaseRendersIdenticallyUnderConcurrency() throws Exception {
        List<XssCase> corpus = CanoeCorpus.all();
        List<Payload> payloads = List.of(
                Payloads.INERT_MARKER,
                Payloads.QUOTE_SINGLE_BREAKOUT,
                Payloads.ABSOLUTE_OFFSITE_HTTPS,
                Payloads.TAG_IMG_ONERROR);

        // Baseline, single-threaded.
        Map<String, String> expected = new LinkedHashMap<>();
        for (XssCase testCase : corpus) {
            for (Payload payload : payloads) {
                expected.put(keyOf(testCase, payload), describe(testCase, payload.value()));
            }
        }

        // A single start gate rather than a barrier: every task is submitted first and blocks, then
        // all of them are released at once. A CyclicBarrier would make the last partial batch wait
        // for parties that never arrive, which costs a minute and interleaves nothing.
        Set<String> threadsUsed = Collections.synchronizedSet(new LinkedHashSet<>());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<String[]>> futures = new ArrayList<>();
        List<String[]> observed = new ArrayList<>();

        try {
            for (int round = 0; round < ROUNDS; round++) {
                for (XssCase testCase : corpus) {
                    for (Payload payload : payloads) {
                        futures.add(pool.submit(() -> {
                            start.await(1, TimeUnit.MINUTES);
                            threadsUsed.add(Thread.currentThread().getName());
                            return new String[]{keyOf(testCase, payload),
                                    describe(testCase, payload.value())};
                        }));
                    }
                }
            }
            start.countDown();
            for (Future<String[]> future : futures) {
                observed.add(future.get(5, TimeUnit.MINUTES));
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(corpus.size() * payloads.size() * ROUNDS, observed.size(),
                "every scheduled render must have completed");

        // Without this the test would pass on a single-threaded executor, which is the same
        // failure mode as a browser detector that never fires: green, and measuring nothing.
        assertTrue(threadsUsed.size() > 1,
                () -> "every render ran on one thread (" + threadsUsed + "), so nothing was"
                        + " concurrent and this test asserted nothing about shared state");

        List<String> divergences = new ArrayList<>();
        for (String[] entry : observed) {
            if (!expected.get(entry[0]).equals(entry[1])) {
                divergences.add(entry[0]
                        + "\n      single-threaded : " + CanoeTestSupport.quote(expected.get(entry[0]))
                        + "\n      concurrent      : " + CanoeTestSupport.quote(entry[1]));
            }
        }

        assertTrue(divergences.isEmpty(),
                () -> divergences.size() + " render(s) differed under concurrency, which means"
                        + " something behind Canoe is shared. One Canoe per render is the whole of"
                        + " the design's thread-safety argument (VelocityViewFactory.render:204) and"
                        + " every other measurement in this suite is single-threaded, so nothing"
                        + " else would notice.\n  " + String.join("\n  ", divergences));
    }

    private static String keyOf(XssCase testCase, Payload payload) {
        return testCase.id() + " / " + payload.id();
    }

    /**
     * The comparison above must be able to fail, and a {@link Canoe} carrying state from an earlier
     * render must be what makes it.
     *
     * <p>&sect;2.4 again, and it matters more here than almost anywhere: a concurrency test that
     * happens not to interleave is indistinguishable from a concurrency test over code with no
     * shared state, and both are green. What the byte comparison actually has to be sensitive to is
     * <em>one render seeing another render's leftovers</em>, so that is what is demonstrated —
     * deterministically, with no threads, because a race that has to be provoked is a race that
     * might not be.
     *
     * <p>Two {@code Canoe}s, identical text written to both, and different contexts out, because
     * one of them has an earlier element in it. If the production wiring ever hoisted the
     * {@code Canoe} out of {@code render()}, that is the shape of what would happen — one user's
     * markup deciding another user's encoding — and the assertion above is what would catch it.
     *
     * <p><strong>The instrument changed with R3, and the first half of this test records why.</strong>
     * It used to be F5 used as an instrument: {@code mocha:} tested {@code buf[5]},
     * {@code placeholder} is eleven characters, and the {@code h} it left at index 5 was enough to
     * change which encoder the reference got. R3 made the prefix comparison length-checked and
     * clears {@code buf} on every reuse, so that pair of writes now agrees — which is asserted here
     * rather than deleted, because "the two Canoes agree about {@code mocha:}" is the regression net
     * for the fix, and because an instrument that has silently stopped measuring is how a
     * concurrency test becomes green for the wrong reason.
     *
     * <p>What replaces it is the parser state itself, which is not a defect and never was: a
     * {@code Canoe} that has already seen {@code <script>} is in {@code SCRIPT} state, so identical
     * following text lands in a different context. {@code buf} was only ever one of the fields a
     * {@code Canoe} carries across writes.
     */
    @Test
    public void aCanoeCarriesStateAcrossWritesWhichIsWhyItMustNotBeShared() throws Exception {
        // R3: the F5 instrument, kept as the assertion that it no longer works.
        Canoe fresh = new Canoe(new StringWriter());
        fresh.write("<a href=\"mocha:");
        assertEquals(Canoe.CTX_JS, fresh.currentContext(),
                "mocha: is recognised and the reference will be suppressed");

        Canoe afterAnElevenCharacterName = new Canoe(new StringWriter());
        afterAnElevenCharacterName.write("<input placeholder=\"x\">");
        afterAnElevenCharacterName.write("<a href=\"mocha:");
        assertEquals(Canoe.CTX_JS, afterAnElevenCharacterName.currentContext(),
                "R3: an eleven-character attribute name in an earlier element used to leave an 'h'"
                        + " at buf[5], so mocha: was no longer recognised and the identical text"
                        + " landed in a different context. The buffer is cleared on reuse now, and"
                        + " the comparison is length-checked, so this pair agrees.");

        // ...and the state a Canoe legitimately carries, which is what makes the equality assertion
        // above non-vacuous now.
        Canoe inHtml = new Canoe(new StringWriter());
        inHtml.write("<p>");
        assertEquals(Canoe.CTX_HTML, inHtml.currentContext(),
                "a fresh Canoe is parsing ordinary markup");

        Canoe inScript = new Canoe(new StringWriter());
        inScript.write("<script>var q = 1;");
        inScript.write("<p>");
        assertEquals(Canoe.CTX_JS, inScript.currentContext(),
                "the same three characters written to a Canoe that has already entered a script"
                        + " element are script data, not markup, so the reference after them is"
                        + " CTX_JS and dropped rather than html()-encoded. That is what a shared"
                        + " Canoe would do to two concurrent renders, and it is why the byte"
                        + " comparison above is not vacuous.");
    }

    // ------------------------------------------------------------------
    // Static state, structurally
    // ------------------------------------------------------------------

    /**
     * No static field of {@code Canoe} or {@code HtmlEncoder} is mutable shared state.
     *
     * <p>The brief for this task named two: {@code HtmlEncoder.uriPattern} and
     * {@code HtmlEncoder.hexDigits}. Both are safe, for different reasons, and both are worth
     * pinning:
     *
     * <ul>
     *   <li>{@code uriPattern} is a {@link java.util.regex.Pattern}, which is immutable and
     *       explicitly documented as safe for concurrent use; the {@code Matcher} that carries the
     *       per-call state is created inside {@code url()}. It is <strong>not declared final</strong>,
     *       which is the one thing here worth changing — nothing assigns to it today, and "nothing
     *       assigns to it today" is not a property.
     *   <li>{@code hexDigits} is {@code static final char[]}, and an array reference being final
     *       says nothing about its contents. It is safe because nothing writes to it, which this
     *       test asserts by checking its contents rather than its modifiers.
     * </ul>
     *
     * <p>Everything else must be a primitive or {@link String} constant. A new static field that is
     * neither fails here, which is the point: the failure arrives with the field rather than with
     * the first production race.
     */
    @Test
    public void everyStaticFieldIsFinalAndImmutable() {
        List<String> problems = new ArrayList<>();

        for (Class<?> type : List.of(Canoe.class, HtmlEncoder.class)) {
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                if (field.isSynthetic()) {
                    continue;
                }

                String name = type.getSimpleName() + "." + field.getName();
                boolean immutableType = field.getType().isPrimitive()
                        || field.getType() == String.class
                        || field.getType() == java.util.regex.Pattern.class
                        || field.getType() == char[].class
                        || org.slf4j.Logger.class.isAssignableFrom(field.getType());

                if (!immutableType) {
                    problems.add(name + " is static and of mutable type " + field.getType().getName()
                            + ". One Canoe per render only helps if there is nothing behind it.");
                }
                if (!Modifier.isFinal(field.getModifiers()) && !"uriPattern".equals(field.getName())) {
                    problems.add(name + " is static and not final.");
                }
            }
        }

        assertTrue(problems.isEmpty(), () -> String.join("\n  ", problems));

        // The two the brief names, asserted by value rather than by modifier.
        assertEquals("0123456789ABCDEF", new String(hexDigits()),
                "HtmlEncoder.hexDigits is a static array, so final says nothing about its contents;"
                        + " nothing may write to it");
        assertEquals("https://app.example/x", HtmlEncoder.url("https://app.example/x"),
                "uriPattern still matches what F24 and F15 are about, so the exemption above is"
                        + " still describing the field that exists");
    }

    private static char[] hexDigits() {
        try {
            Field field = HtmlEncoder.class.getDeclaredField("hexDigits");
            field.setAccessible(true);
            return ((char[]) field.get(null)).clone();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("HtmlEncoder.hexDigits has been renamed or removed", e);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Everything one render produces that a caller can observe: the bytes, and the encoding error if
     * there was one.
     *
     * <p>The error message is part of the comparison because a rejection case's whole output is the
     * exception, and a race that changed only the reported position would otherwise pass.
     */
    private static String describe(XssCase testCase, String value) {
        CanoeTestSupport.RenderResult result = VerdictEvaluator.render(testCase, value);
        return result.isError()
                ? "ERROR " + result.errorMessage() + " | partial " + result.output()
                : result.output();
    }

}
