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
package com.webkreator.qlue.view.velocity;

import com.webkreator.qlue.view.Canoe;
import com.webkreator.qlue.view.canoe.CanoeTestSupport;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R24's detector, on its own: what it answers, and how much of the stack it is allowed to read.
 *
 * <p>{@code CanoeReferenceInsertionHandler.encodingMustBeDeferred()} is the whole of
 * the F12 fix. It answers one question — is this reference being rendered into the private
 * {@code StringBuilderWriter} that {@code ASTStringLiteral.value()} allocates, rather than into
 * {@link com.webkreator.qlue.view.Canoe}, <em>and</em> is that string going to be printed through a
 * reference later, where the encoding it is deferring can actually happen — and it answers it from
 * the call stack, because (measured in velocity-engine-core 2.4.1) that is the only place the answer
 * exists: the internal context adapters carry template and macro name stacks and nothing about which
 * node is evaluating, and {@code ASTReference.render()} never shows the handler the writer it is
 * about to write to.
 *
 * <p>The second half of that question is a separate section below, and it is not a refinement: a
 * value deferred into {@code #evaluate}'s argument is compiled as VTL rather than printed, which is
 * template injection rather than the XSS this class is about.
 *
 * <p><strong>The two error directions are not comparable, and every assertion here is about that.</strong>
 * Answering "not nested" when the render <em>is</em> nested encodes the value twice, which is F12
 * itself: visible, harmless, and the behaviour this replaces. Answering "nested" when it is not
 * returns attacker-controlled data to Velocity unencoded, which is XSS. So the detector is allowed
 * to be conservative and is not allowed to be generous, and the tests below drive each bound from
 * the generous side: a literal below the Velocity run, a literal past the frame limit, a stack with
 * no Velocity in it at all, a literal whose consumer compiles it instead of printing it, a literal
 * with no consumer frame at all. Each must answer {@code false}.
 *
 * <p>{@link com.webkreator.qlue.view.canoe.velocity.VelocityIntegrationTest} owns the other half —
 * what real templates render to. This file feeds the decision synthetic stacks, which is the only
 * way to assert the <em>bound</em> exactly rather than by timing it, and then measures the real walk
 * once so that the cost is a number in the record rather than an assumption.
 */
public class NestedRenderDetectionTest {

    private static final String LITERAL = CanoeReferenceInsertionHandler.STRING_LITERAL_CLASS;

    /** Where the cost measurement is written; mirrors {@code MatrixReportTest}'s fallback. */
    private static final Path OUTPUT_DIR = Paths.get(
            System.getProperty("canoe.report.dir", "build/reports/canoe"));

    // ------------------------------------------------------------------
    // What the decision is
    // ------------------------------------------------------------------

    /**
     * The shape the fix exists for: the literal's node inside the run of Velocity frames.
     *
     * <p>This is the stack of {@code #set($m = "x$data")} as it actually is, read off a render
     * rather than invented — see the class javadoc of the handler for the frame-by-frame list.
     */
    @Test
    public void aLiteralInsideTheVelocityRunIsANestedRender() {
        assertTrue(CanoeReferenceInsertionHandler.encodingMustBeDeferred(Stream.of(
                        "com.webkreator.qlue.view.velocity.CanoeReferenceInsertionHandler",
                        "org.apache.velocity.app.event.EventCartridge",
                        "org.apache.velocity.app.event.EventHandlerUtil",
                        "org.apache.velocity.runtime.parser.node.ASTReference",
                        "org.apache.velocity.runtime.parser.node.SimpleNode",
                        LITERAL,
                        "org.apache.velocity.runtime.parser.node.ASTSetDirective",
                        "com.webkreator.qlue.view.velocity.VelocityViewFactory")),
                "the literal node is five frames below the handler for a bare interpolated #set");
    }

    /** And the shape that must not be one: the same run with no literal in it. */
    @Test
    public void aReferenceRenderedStraightToCanoeIsNotANestedRender() {
        assertFalse(CanoeReferenceInsertionHandler.encodingMustBeDeferred(Stream.of(
                        "com.webkreator.qlue.view.velocity.CanoeReferenceInsertionHandler",
                        "org.apache.velocity.app.event.EventCartridge",
                        "org.apache.velocity.app.event.EventHandlerUtil",
                        "org.apache.velocity.runtime.parser.node.ASTReference",
                        "org.apache.velocity.runtime.parser.node.SimpleNode",
                        "org.apache.velocity.runtime.RuntimeInstance",
                        "com.webkreator.qlue.view.velocity.VelocityViewFactory")),
                "no literal on the stack means the writer is Canoe, which is the encoding case");
    }

    /**
     * A literal <em>below</em> the Velocity run belongs to an outer render, and does not count.
     *
     * <p>This is the case the package boundary exists for and the one that decides its direction. If
     * application code entered Velocity from inside a literal's render — a tool method that merges a
     * second template, say — then the inner reference is being written to that second render's
     * writer, not to the outer literal's {@code StringBuilderWriter}. Treating the outer frame as
     * ours would return raw data for a reference that really is going to Canoe, which is the error
     * direction that is XSS. Stopping at the boundary answers "not nested" and encodes.
     */
    @Test
    public void aLiteralBelowTheVelocityRunIsSomebodyElsesRender() {
        assertFalse(CanoeReferenceInsertionHandler.encodingMustBeDeferred(Stream.of(
                        "com.webkreator.qlue.view.velocity.CanoeReferenceInsertionHandler",
                        "org.apache.velocity.app.event.EventCartridge",
                        "org.apache.velocity.runtime.parser.node.ASTReference",
                        "org.apache.velocity.runtime.RuntimeInstance",
                        "com.example.app.SomeTool",                 // <- the run ends here
                        LITERAL,
                        "org.apache.velocity.runtime.parser.node.ASTSetDirective")),
                "the literal is under a frame that left Velocity, so it is not this reference's"
                        + " writer and the answer must fall to 'encode'");
    }

    /** No Velocity on the stack at all - a direct call, or a handler reached some other way. */
    @Test
    public void aStackWithNoVelocityInItIsNotANestedRender() {
        assertFalse(CanoeReferenceInsertionHandler.encodingMustBeDeferred(Stream.of(
                        "com.webkreator.qlue.view.velocity.CanoeReferenceInsertionHandler",
                        "com.example.app.Caller")),
                "dropWhile consumes the whole stack and the walk sees no literal at all");
        assertFalse(CanoeReferenceInsertionHandler.encodingMustBeDeferred(Stream.of()),
                "an empty stack is not a nested render either");
    }

    /**
     * The class name is compared exactly, so a near miss is not a match.
     *
     * <p>{@code ASTStringLiteral} has no subclass in Velocity and the comparison is deliberately
     * {@code equals} rather than {@code startsWith} or {@code contains}: the asymmetry means a
     * lenient match buys nothing and risks the expensive direction. An inner class or a differently
     * named node answering "nested" would be a false positive.
     */
    @Test
    public void theClassNameIsMatchedExactly() {
        for (String nearMiss : List.of(
                "org.apache.velocity.runtime.parser.node.ASTStringLiteralX",
                "org.apache.velocity.runtime.parser.node.ASTStringLiteral$Inner",
                "org.apache.velocity.runtime.parser.node.ASTTextblock",
                "org.apache.velocity.runtime.parser.node.ASTString")) {
            assertFalse(CanoeReferenceInsertionHandler.encodingMustBeDeferred(Stream.of(
                            "org.apache.velocity.app.event.EventCartridge", nearMiss)),
                    () -> nearMiss + " is not ASTStringLiteral and must not answer 'nested'");
        }
    }

    // ------------------------------------------------------------------
    // What the literal is for
    // ------------------------------------------------------------------

    /**
     * <strong>A literal is only deferrable if something is going to print it.</strong>
     *
     * <p>Deferring says "encode this later, where it is written to the page". That is a claim about
     * what becomes of the string, not about the literal, and three directives call
     * {@code ASTStringLiteral.value()} for something that never reaches a reference:
     * {@code #evaluate} parses the string as VTL, {@code #parse} treats it as a template name and
     * parses the file it names, and {@code #include} treats it as a resource name and copies the
     * file's bytes to the writer. A deferred value in the first is server-side template injection —
     * {@code #evaluate("…$data…")} with a payload of <code>#set($injected = 1)$injected</code> would
     * render {@code 1} — and in the other two it is an attacker-chosen path.
     *
     * <p>{@code ASTStringLiteral.value()} has exactly one caller per invocation, so the frame
     * directly below the literal <em>is</em> that consumer. These are the four stacks as rendered and
     * read off, not invented; {@code VelocityIntegrationTest} asserts the bytes the same templates
     * produce.
     */
    @Test
    public void aLiteralConsumedAsTemplateSourceOrAsAPathIsNotDeferred() {
        for (String consumer : List.of(
                "org.apache.velocity.runtime.directive.Evaluate",
                "org.apache.velocity.runtime.directive.Parse",
                "org.apache.velocity.runtime.directive.Include")) {
            assertFalse(CanoeReferenceInsertionHandler.encodingMustBeDeferred(Stream.of(
                            "com.webkreator.qlue.view.velocity.CanoeReferenceInsertionHandler",
                            "org.apache.velocity.app.event.EventCartridge",
                            "org.apache.velocity.app.event.EventHandlerUtil",
                            "org.apache.velocity.runtime.parser.node.ASTReference",
                            "org.apache.velocity.runtime.parser.node.SimpleNode",
                            LITERAL,
                            consumer,
                            "org.apache.velocity.runtime.parser.node.ASTDirective")),
                    () -> consumer + " never prints the string it asked the literal for, so there is"
                            + " no later encoding to defer to and the value must be encoded here");
        }
    }

    /**
     * ...and the same directive <em>above</em> the literal is an ordinary nested render.
     *
     * <p>This is why the consumer is one frame and not "anywhere below the literal". A {@code #set}
     * inside a {@code #parse}d template — a header fragment that builds a title, say — has
     * {@code Parse} on its stack four frames below the literal, three below its real consumer, and it
     * is exactly the shape R24 exists for. So is a {@code #set} inside an {@code #evaluate}d string. Widening the
     * check to the whole run would answer "encode" for both, which is safe but would leave F12 alive
     * in every parsed fragment in an application — most of them, in practice.
     */
    @Test
    public void aDirectiveFurtherDownTheRunIsNotTheLiteralsConsumer() {
        assertTrue(CanoeReferenceInsertionHandler.encodingMustBeDeferred(Stream.of(
                        "com.webkreator.qlue.view.velocity.CanoeReferenceInsertionHandler",
                        "org.apache.velocity.app.event.EventCartridge",
                        "org.apache.velocity.app.event.EventHandlerUtil",
                        "org.apache.velocity.runtime.parser.node.ASTReference",
                        "org.apache.velocity.runtime.parser.node.SimpleNode",
                        LITERAL,
                        "org.apache.velocity.runtime.parser.node.ASTExpression",
                        "org.apache.velocity.runtime.parser.node.ASTSetDirective",
                        "org.apache.velocity.runtime.parser.node.SimpleNode",
                        "org.apache.velocity.runtime.directive.Parse",           // <- not the consumer
                        "org.apache.velocity.runtime.parser.node.ASTDirective")),
                "a #set inside a #parse'd template is a nested render like any other");
    }

    /**
     * A literal with no frame under it at all cannot be shown to be deferrable, so it is not.
     *
     * <p>This cannot arise from Velocity — {@code value()} is always called from somewhere — but it
     * is the boundary of the rule and it must fall the same way every other unknown does. It is also
     * what the run ending immediately after the literal looks like: application code that entered
     * {@code value()} directly, which is neither a {@code #set} nor a directive this class knows.
     */
    @Test
    public void aLiteralWhoseConsumerIsOffTheEndOfTheRunIsNotDeferred() {
        assertFalse(CanoeReferenceInsertionHandler.encodingMustBeDeferred(Stream.of(
                        "com.webkreator.qlue.view.velocity.CanoeReferenceInsertionHandler",
                        "org.apache.velocity.app.event.EventCartridge",
                        LITERAL)),
                "no consumer frame means no evidence that anything will print the string");
        assertFalse(CanoeReferenceInsertionHandler.encodingMustBeDeferred(Stream.of(
                        "com.webkreator.qlue.view.velocity.CanoeReferenceInsertionHandler",
                        "org.apache.velocity.app.event.EventCartridge",
                        LITERAL,
                        "com.example.app.SomeTool")),
                "and a consumer outside Velocity is off the end of the run, which is the same thing");
    }

    // ------------------------------------------------------------------
    // The bound
    // ------------------------------------------------------------------

    /**
     * The walk stops at the end of the Velocity run, and the count is asserted rather than timed.
     *
     * <p>The reason the bound exists is that the walk short-circuits on the first literal but the
     * common case is that there is none: without a stopping rule, every reference on an ordinary page
     * would walk to the bottom of a servlet stack — about a hundred frames in this suite's JUnit
     * harness and more in a container. Counting what the decision consumes is exact, cheap and does
     * not flake, which a timing assertion on the same property would.
     */
    @Test
    public void theWalkReadsOnlyTheVelocityRunAndStops() {
        List<String> stack = new ArrayList<>(List.of(
                "com.webkreator.qlue.view.velocity.CanoeReferenceInsertionHandler",
                "org.apache.velocity.app.event.EventCartridge",
                "org.apache.velocity.app.event.EventHandlerUtil",
                "org.apache.velocity.runtime.parser.node.ASTReference",
                "org.apache.velocity.runtime.RuntimeInstance",
                "com.webkreator.qlue.view.velocity.VelocityViewFactory"));
        for (int i = 0; i < 500; i++) {
            stack.add("jakarta.servlet.FilterChain$" + i);
        }

        AtomicInteger read = new AtomicInteger();
        assertFalse(CanoeReferenceInsertionHandler.encodingMustBeDeferred(
                stack.stream().peek(name -> read.incrementAndGet())));
        assertEquals(6, read.get(),
                "one frame for this class, four for the Velocity run, and one more to see that the"
                        + " run has ended - and then nothing, however deep the stack below is");
    }

    /**
     * The frame limit is the backstop, and it fails towards encoding.
     *
     * <p>The package boundary is the bound that does the work; {@link
     * CanoeReferenceInsertionHandler#MAX_FRAMES} is there for the stack shape nobody predicted, where
     * the Velocity run never ends within a sane distance. A literal past the limit is not found, so
     * the value is encoded twice. That is F12's behaviour for that one pathological template, which
     * is the correct thing for a backstop to degrade to.
     */
    @Test
    public void theFrameLimitTruncatesTowardsEncoding() {
        List<String> stack = new ArrayList<>();
        stack.add("com.webkreator.qlue.view.velocity.CanoeReferenceInsertionHandler");
        for (int i = 0; i < CanoeReferenceInsertionHandler.MAX_FRAMES + 10; i++) {
            stack.add("org.apache.velocity.runtime.parser.node.SimpleNode");
        }
        stack.add(LITERAL);

        AtomicInteger read = new AtomicInteger();
        assertFalse(CanoeReferenceInsertionHandler.encodingMustBeDeferred(
                        stack.stream().peek(name -> read.incrementAndGet())),
                "a literal below the limit is not seen, and not seeing it means encoding");
        assertEquals(CanoeReferenceInsertionHandler.MAX_FRAMES, read.get(),
                "and the limit is what stopped the walk, rather than the run ending");

        // ...and the limit is generous enough for the shapes that exist: the deepest the suite
        // renders is a macro invoked inside a literal, which puts the node 10 frames below
        // referenceInsert(), with a #parse'd fragment inside one at 8 and a #foreach at 9.
        List<String> deepButReal = new ArrayList<>();
        deepButReal.add("com.webkreator.qlue.view.velocity.CanoeReferenceInsertionHandler");
        for (int i = 0; i < 20; i++) {
            deepButReal.add("org.apache.velocity.runtime.parser.node.SimpleNode");
        }
        deepButReal.add(LITERAL);
        deepButReal.add("org.apache.velocity.runtime.parser.node.ASTExpression");
        assertTrue(CanoeReferenceInsertionHandler.encodingMustBeDeferred(deepButReal.stream()),
                "twenty frames of nesting is still inside the limit");
    }

    // ------------------------------------------------------------------
    // What it costs
    // ------------------------------------------------------------------

    /**
     * The measurement R24 owes: what the stack walk costs, per reference, as a number.
     *
     * <p>A detector that reads the call stack on every reference of every page is exactly the kind
     * of change that gets shipped on an assumption, so this measures it instead. Both figures are
     * written to {@code build/reports/canoe/reference-insertion-cost.txt} so that they survive the
     * run rather than living in a commit message.
     *
     * <p><strong>The walk is timed in situ, which is the only honest way to time it.</strong> Its
     * cost is dominated by the number of frames read, and that number is a property of the stack it
     * is called from: from this test's own JUnit stack there are no Velocity frames at all, the walk
     * runs to {@link CanoeReferenceInsertionHandler#MAX_FRAMES} and costs about 8.7 &micro;s — a
     * figure that describes nothing that can happen in production, where the handler is always
     * called from inside Velocity and the run ends within a dozen frames. So the timing loop runs
     * inside {@link Canoe#encode(String)} of a {@link Canoe} subclass, one frame below the handler,
     * which is the production stack shape exactly.
     *
     * <p>Measured on the machine R24 landed on, and recorded here because the plan asked for a
     * number rather than an assurance:
     * <ul>
     *   <li>the walk itself: <strong>~2.1 &micro;s per reference</strong>, reading about eleven
     *       frames — one to leave this class, the eight-frame Velocity run, and one to see that the
     *       run has ended;
     *   <li>a whole render, A/B against the same build with the detector call disabled:
     *       <strong>3.1 &rarr; 5.2 &micro;s per reference</strong> for the reference-dense template
     *       below (35 characters of markup per reference, +67%), and
     *       <strong>14.0 &rarr; 16.2 &micro;s per reference</strong> for a template with 235
     *       characters of markup per reference (+15%).
     * </ul>
     *
     * <p>Read the second pair, not the first percentage: the dense template is a worst case built to
     * isolate the reference path, and a page's cost per reference rises with the markup around it
     * while the walk's does not. A page with 300 references pays about 0.6 ms. That is the price of
     * not double-encoding, and it is recorded rather than hidden.
     *
     * <p>The assertion is a loose ceiling on the in-situ figure. It exists to catch a walk that
     * stopped being bounded — the boundary rule deleted, say, so that every reference reads to the
     * bottom of a servlet stack — and deliberately not to police microseconds on a machine whose
     * load this test does not control.
     */
    @Test
    public void theStackWalkCostsAboutTwoMicrosecondsPerReference() throws IOException {
        int references = 200;
        StringBuilder template = new StringBuilder();
        for (int i = 0; i < references; i++) {
            template.append("<div class=\"row\"><p>$data</p></div>\n");
        }
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("data", "<b>value</b>");

        // Warm up: C2 needs to see both the render and the walk before either is worth timing.
        for (int i = 0; i < 50; i++) {
            CanoeTestSupport.render(template.toString(), model);
        }
        CanoeTestSupport.render(template.toString(), model, CanoeTestSupport.RenderOptions.defaults(),
                TimingCanoe::new);

        int renders = 50;
        long renderStart = System.nanoTime();
        for (int i = 0; i < renders; i++) {
            CanoeTestSupport.render(template.toString(), model);
        }
        long renderNanos = System.nanoTime() - renderStart;

        TimingCanoe.nanos = 0;
        TimingCanoe.calls = 0;
        for (int i = 0; i < 20; i++) {
            CanoeTestSupport.render(template.toString(), model,
                    CanoeTestSupport.RenderOptions.defaults(), TimingCanoe::new);
        }
        assertTrue(TimingCanoe.calls > 0, "the timing writer must have seen every reference");

        double nanosPerWalk = TimingCanoe.nanos / (double) TimingCanoe.calls;
        double nanosPerReference = renderNanos / (double) (renders * references);

        Files.createDirectories(OUTPUT_DIR);
        String report = String.format(
                "R24 reference-insertion cost, measured by %s%n"
                        + "  stack walk, in situ         %8.0f ns per call   (%d calls, from the"
                        + " handler's own stack)%n"
                        + "  full render per reference   %8.0f ns per ref    (%d renders x %d refs,"
                        + " reference-dense template)%n"
                        + "  walk as a share of a render %8.1f %%%n",
                getClass().getSimpleName(), nanosPerWalk, TimingCanoe.calls,
                nanosPerReference, renders, references,
                100.0 * nanosPerWalk / nanosPerReference);
        Files.write(OUTPUT_DIR.resolve("reference-insertion-cost.txt"),
                report.getBytes(StandardCharsets.UTF_8));

        assertTrue(nanosPerWalk < 25_000,
                () -> "the stack walk costs " + Math.round(nanosPerWalk) + " ns per call, which is"
                        + " far more than a walk bounded at the end of the Velocity run can account"
                        + " for. Report:\n" + report);
    }

    /**
     * A {@link Canoe} that times the detector from inside the handler's own call stack.
     *
     * <p>{@code CanoeReferenceInsertionHandler} calls {@code qlueWriter.encode()}, so this
     * subclass's {@code encode()} sits exactly one frame below the handler — which puts the walk in
     * the stack shape it has in production, the only shape whose cost means anything. The counters
     * are static because {@code CanoeTestSupport.render} builds the writer through a factory.
     */
    private static final class TimingCanoe extends Canoe {

        static long nanos;
        static long calls;

        /** Enough repetitions per reference that {@code nanoTime()}'s own cost does not dominate. */
        private static final int REPEATS = 32;

        TimingCanoe(Writer writer) {
            super(writer);
        }

        @Override
        public String encode(String input) {
            boolean nested = false;
            long start = System.nanoTime();
            for (int i = 0; i < REPEATS; i++) {
                nested |= CanoeReferenceInsertionHandler.encodingMustBeDeferred();
            }
            nanos += System.nanoTime() - start;
            calls += REPEATS;
            if (nested) {
                throw new IllegalStateException(
                        "this template has no interpolated literal in it; the detector answering"
                                + " 'nested' here would be the false positive the design forbids");
            }
            return super.encode(input);
        }
    }
}
