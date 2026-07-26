package com.webkreator.qlue.view.canoe.velocity;

import com.webkreator.qlue.view.Canoe;
import com.webkreator.qlue.view.canoe.CanoeTestSupport;
import com.webkreator.qlue.view.canoe.corpus.CanoeCorpus;
import com.webkreator.qlue.view.canoe.corpus.Payload;
import com.webkreator.qlue.view.canoe.corpus.Payloads;
import com.webkreator.qlue.view.canoe.corpus.XssCase;
import com.webkreator.qlue.view.velocity.ProductionRenderProbe;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.exception.VelocityException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The production render path, and the reason the rest of this suite is allowed to avoid it.
 *
 * <p>Every other file renders through {@code VelocityEngine.evaluate()} with the template as a
 * {@code String}. That is fast, needs no fixtures and exercises Canoe identically — <em>if</em> the
 * two paths agree. They differ in five things that could each plausibly change a byte:
 *
 * <ul>
 *   <li>the template comes from a {@code .vm} <strong>file</strong> through the {@code class}
 *       resource loader, with its own encoding handling, rather than from a Java string literal;
 *   <li>{@code Template.merge()} runs instead of {@code evaluate()}, which is a different entry
 *       point into the same renderer and reports {@code IOException}s with a different message;
 *   <li>the model carries production's own entries — {@code _app}, {@code _page}, {@code _i},
 *       {@code _cmd}, {@code _errors}, the {@code _f} tool, and every public field of the command
 *       object, reflected in;
 *   <li>the {@link Canoe} is constructed by {@code render()} around the response writer rather than
 *       by the harness around a {@code StringWriter}; and
 *   <li>{@code render()}'s {@code finally} block flushes, and its {@code catch} block decides what a
 *       caller sees.
 * </ul>
 *
 * <p>So the assertion is <strong>byte-identical output</strong> over a dozen cases picked from across
 * the corpus — body text, a plain-text attribute, four kinds of URL sink, a recognised and an
 * unrecognised event handler, both CSS shapes, a script body, {@code srcdoc}, a meta refresh, the
 * unquoted-value case (F11, routed by R19), the {@code javascript:} prefix case, and a two-reference
 * template. If any row ever fails, the fast harness stops being evidence about production and every
 * conclusion downstream of it needs re-checking.
 *
 * <p><strong>Result: the two paths agree on all fourteen rows, for every payload tried.</strong>
 *
 * <h2>The three production switches</h2>
 *
 * <p>Beyond agreement, this file covers the three things only the factory can do:
 * {@code setAutoEscaping(false)} (the cartridge is never attached, so nothing is encoded — but Canoe
 * still parses), {@code allowDirectOutput()} (which decides whether {@code $_x} is in the model at
 * all), and F13's error path, where an encoding error escapes {@code render()} as an exception
 * instead of degrading the page to {@code [Encoding Error]}.
 *
 * <p>F13 itself is owned by {@code CanoeRobustnessTest}, which drives all thirteen rejection messages
 * through {@code ProductionRenderProbe}. What is here is the part that belongs to this file: that the
 * <em>partial output</em> the two paths leave behind before giving up is byte-identical too, which is
 * the half a caller of {@code render()} actually receives.
 *
 * <h2>Why there are no mocks</h2>
 *
 * <p>The plan sketch for this task said "with mocked servlet objects, in the style of the existing
 * {@code TestRouting}". That is not possible here: mockito-core 5.11.0's bundled ByteBuddy refuses to
 * instrument a class on this JDK ({@code Java 25 (69) is not supported}), so {@code mock(Page.class)}
 * fails at the first call. A real {@link com.webkreator.qlue.CanoeProbePage} with a real
 * {@code QlueApplication} turned out to need less scaffolding than the mock would have, and is
 * strictly better evidence: nothing on the path between the template and the response writer is a
 * stand-in.
 */
public class ViewFactoryRenderTest {

    private static final String TEMPLATE_DIR = "canoe/templates/";

    /**
     * One row: a {@code .vm} fixture, the corpus case it copies, and the payload to render it with.
     *
     * <p>The corpus id is not decoration. {@link #everyFixtureIsAVerbatimCopyOfItsCorpusCase} reads
     * the file and compares it against {@code CanoeCorpus.byId(...).template()}, so a fixture cannot
     * drift away from the case it claims to represent — which is the one way this file could quietly
     * stop testing what it says it tests.
     */
    private static final class Row {

        final String file;
        final String caseId;
        final Payload payload;

        Row(String file, String caseId, Payload payload) {
            this.file = file;
            this.caseId = caseId;
            this.payload = payload;
        }

        @Override
        public String toString() {
            return file + " (" + caseId + " / " + payload.id() + ")";
        }
    }

    /**
     * The dozen-and-a-bit representative cases, chosen to reach every encoder Canoe has and every
     * verdict class the ledger has: {@code htmlWhite()}, {@code html()}, {@code url()}, the empty
     * string from {@code CTX_JS}, and the empty string from {@code CTX_SUPPRESS}.
     */
    static List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("body-paragraph.vm", "body.paragraph", Payloads.TAG_IMG_ONERROR));
        rows.add(new Row("plain-text-attribute.vm", "quoting.double-quoted",
                Payloads.ATTR_DOUBLE_QUOTE_BREAKOUT));
        rows.add(new Row("url-href.vm", "url.href-full", Payloads.JS_URL));
        rows.add(new Row("url-script-src-prefix.vm", "url.script-src-prefix",
                Payloads.PROTOCOL_RELATIVE));
        rows.add(new Row("handler-onsubmit.vm", "handler.onsubmit", Payloads.QUOTE_SINGLE_BREAKOUT));
        rows.add(new Row("handler-onclick.vm", "handler.onclick", Payloads.QUOTE_SINGLE_BREAKOUT));
        rows.add(new Row("css-style-with-property.vm", "css.style-with-property", Payloads.CSS_OVERLAY));
        rows.add(new Row("css-style-bare.vm", "css.style-bare", Payloads.CSS_OVERLAY));
        rows.add(new Row("script-body-string-literal.vm", "script.body-string-literal",
                Payloads.QUOTE_SINGLE_BREAKOUT));
        rows.add(new Row("markup-srcdoc.vm", "markup.srcdoc", Payloads.SRCDOC_MARKUP));
        rows.add(new Row("prefix-javascript-exact.vm", "prefix.javascript-exact",
                Payloads.QUOTE_SINGLE_BREAKOUT));
        rows.add(new Row("unquoted-after-equals.vm", "unquoted.immediately-after-equals",
                Payloads.JS_URL));
        rows.add(new Row("refresh-meta-content.vm", "refresh.meta-content", Payloads.META_REFRESH));
        rows.add(new Row("transition-attribute-then-text.vm", "transition.attribute-then-text",
                Payloads.JS_URL));
        return rows;
    }

    // ------------------------------------------------------------------
    // The fixtures are the corpus
    // ------------------------------------------------------------------

    /**
     * Each {@code .vm} file holds exactly the template of the corpus case it names.
     *
     * <p>Without this the comparison below would still pass — it renders the same file through both
     * paths — while quietly testing a template nobody had reviewed. The corpus is where verdicts
     * live; a fixture that has drifted from its case is a fixture with no verdict.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    public void everyFixtureIsAVerbatimCopyOfItsCorpusCase(Row row) throws IOException {
        XssCase testCase = CanoeCorpus.byId(row.caseId);
        assertEquals(testCase.template(), readFixture(row.file),
                () -> row.file + " has drifted from corpus case " + row.caseId
                        + ". The fixture must be a byte-for-byte copy, or the comparison below is"
                        + " comparing two renders of a template with no reviewed verdict.");
    }

    /**
     * No fixture is orphaned, and none is used twice.
     *
     * <p>A {@code .vm} file left behind after a row is deleted looks like coverage and is not; the
     * directory listing is the only place that can notice.
     */
    @Test
    public void everyFixtureInTheDirectoryIsClaimedByExactlyOneRow() {
        Set<String> claimed = new LinkedHashSet<>();
        for (Row row : rows()) {
            assertTrue(claimed.add(row.file), () -> row.file + " is claimed by two rows");
        }
        // Two fixtures exist for the production switches rather than for the comparison table.
        claimed.add("rejected-void-element.vm");
        claimed.add("direct-output.vm");

        assertEquals(claimed, fixturesOnDisk(),
                "every .vm file under src/test/resources/canoe/templates must be used by a test");
    }

    // ------------------------------------------------------------------
    // The claim this whole task exists to make
    // ------------------------------------------------------------------

    /**
     * The production path and the fast harness produce byte-identical output.
     *
     * <p>Rendered twice with the row's payload and once with the inert marker, because the second
     * comparison is what catches an agreement that only holds for values that happen to be
     * suppressed: nine of the fourteen rows encode to something, five to nothing, and a defect in
     * the production path that swallowed output would agree with the harness on the five.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    public void theProductionPathAgreesWithTheHarnessByteForByte(Row row) {
        for (String value : List.of(row.payload.value(), Payloads.INERT_MARKER.value(), "")) {
            Map<String, Object> model = modelFor(row.caseId, value);

            CanoeTestSupport.RenderResult harness =
                    CanoeTestSupport.render(CanoeCorpus.byId(row.caseId).template(), model);
            ProductionRenderProbe.Outcome production =
                    ProductionRenderProbe.renderFile(TEMPLATE_DIR + row.file, model);

            assertFalse(production.exceptionEscaped(),
                    () -> row + ": production render failed with " + production.escaped());
            assertFalse(harness.isError(), () -> row + ": harness render failed with " + harness);
            assertEquals(harness.output(), production.output(),
                    () -> row + " with value " + CanoeTestSupport.quote(value)
                            + ": the two render paths disagree. If this fails, every conclusion in"
                            + " this suite drawn from engine.evaluate() needs re-checking.");
        }
    }

    /**
     * The same comparison over <em>every</em> payload the row's corpus case carries, rather than the
     * one representative payload above.
     *
     * <p>Cheap, and it turns "the paths agree on fourteen strings" into "the paths agree on several
     * hundred", which is the difference between a smoke test and evidence. It is a separate test
     * because a failure here and a failure above mean different things: above, the paths disagree
     * about a case; here, they disagree about a value.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    public void theTwoPathsAgreeForEveryPayloadTheCaseCarries(Row row) {
        XssCase testCase = CanoeCorpus.byId(row.caseId);
        List<String> disagreements = new ArrayList<>();

        for (Payload payload : testCase.payloads()) {
            Map<String, Object> model = modelFor(row.caseId, payload.value());
            CanoeTestSupport.RenderResult harness =
                    CanoeTestSupport.render(testCase.template(), model);
            ProductionRenderProbe.Outcome production =
                    ProductionRenderProbe.renderFile(TEMPLATE_DIR + row.file, model);

            if (harness.isError() != production.exceptionEscaped()) {
                disagreements.add(payload.id() + ": harness error=" + harness.isError()
                        + ", production exception=" + production.exceptionEscaped());
            } else if (!harness.output().equals(production.output())) {
                disagreements.add(payload.id()
                        + "\n    harness    : " + CanoeTestSupport.quote(harness.output())
                        + "\n    production : " + CanoeTestSupport.quote(production.output()));
            }
        }

        assertTrue(disagreements.isEmpty(),
                () -> row.file + ": the two render paths disagree on " + disagreements.size()
                        + " payload(s):\n  " + String.join("\n  ", disagreements));
    }

    // ------------------------------------------------------------------
    // setAutoEscaping(false)
    // ------------------------------------------------------------------

    /**
     * {@code setAutoEscaping(false)} means no encoding at all, on the real path.
     *
     * <p>The event cartridge is the only thing the flag controls, so the raw value reaches the
     * response — and the harness's {@code withoutAutoEscaping()} must agree with it byte for byte,
     * because the rest of the suite uses the harness's version to test that path.
     */
    @Test
    public void autoEscapingOffProducesTheRawValueOnBothPaths() {
        String value = Payloads.TAG_IMG_ONERROR.value();
        Map<String, Object> model = Map.of("data", value);

        ProductionRenderProbe.Outcome production = ProductionRenderProbe.renderFile(
                TEMPLATE_DIR + "body-paragraph.vm", model,
                ProductionRenderProbe.Options.defaults().withoutAutoEscaping());
        assertFalse(production.exceptionEscaped(),
                () -> "no encoding does not mean no render: " + production.escaped());
        assertEquals("<p>" + value + "</p>", production.output(),
                "with the cartridge detached, the payload reaches the response verbatim");

        CanoeTestSupport.RenderResult harness = CanoeTestSupport.render("<p>$data</p>", model,
                CanoeTestSupport.RenderOptions.defaults().withoutAutoEscaping());
        assertEquals(harness.output(), production.output(),
                "and the harness's withoutAutoEscaping() models it exactly");
    }

    /**
     * With auto-escaping off, Canoe is still in the writer chain — so a template it rejects is still
     * rejected, and the page still fails.
     *
     * <p>{@code render()} wraps the writer unconditionally at {@code VelocityViewFactory.java:203}
     * and only the cartridge sits behind the flag. This is the assertion that says "turning off auto
     * escaping" is not "turning off Canoe", which is what the name suggests and what a developer
     * reaching for the switch to escape one of &sect;3's availability defects would be relying on.
     */
    @Test
    public void autoEscapingOffStillLeavesCanoeParsingTheTemplate() {
        ProductionRenderProbe.Outcome production = ProductionRenderProbe.renderFile(
                TEMPLATE_DIR + "rejected-void-element.vm", Map.of(),
                ProductionRenderProbe.Options.defaults().withoutAutoEscaping());

        assertTrue(production.exceptionEscaped(),
                () -> "<br/> is rejected whether or not references are being encoded: " + production);
        assertTrue(causeChainMentions(production.escaped(), "Invalid character after tag name"),
                () -> "and for the same reason: " + production.escaped());
    }

    // ------------------------------------------------------------------
    // allowDirectOutput()
    // ------------------------------------------------------------------

    /**
     * {@code allowDirectOutput()} is the switch that puts {@code $_x} in the model, and the bypass
     * works only when it is on.
     *
     * <p>Both halves matter. With direct output on, {@code $_x.asis($data)} writes the raw value —
     * that is the documented escape hatch and &sect;2.5 puts the template author who uses it outside
     * the threat model. With it off, the tool is simply absent from the model, and because Qlue runs
     * Velocity in strict mode the render <em>fails</em> rather than silently encoding or printing the
     * reference's own text. Fail-closed and loud, which is the right answer and is not the default
     * Velocity would have given.
     */
    @Test
    public void theEncodingToolIsBoundOnlyWhenTheApplicationAllowsDirectOutput() {
        Map<String, Object> model = Map.of("data", "<b>");

        ProductionRenderProbe.Outcome allowed = ProductionRenderProbe.renderFile(
                TEMPLATE_DIR + "direct-output.vm", model,
                ProductionRenderProbe.Options.defaults().withDirectOutput());
        assertFalse(allowed.exceptionEscaped(), () -> "with $_x bound: " + allowed);
        assertEquals("<p><b></p>", allowed.output(),
                "$_x.asis() bypasses Canoe on the production path exactly as it does on the harness");

        ProductionRenderProbe.Outcome denied = ProductionRenderProbe.renderFile(
                TEMPLATE_DIR + "direct-output.vm", model);
        assertTrue(denied.exceptionEscaped(),
                () -> "a page that has not called allowDirectOutput() has no $_x, and strict mode"
                        + " turns that into a rendering failure rather than a silent encode: "
                        + denied);
    }

    // ------------------------------------------------------------------
    // R5's extension point
    // ------------------------------------------------------------------

    /**
     * The plain-text attribute allowlist, widened on the factory and observed on the real render
     * path.
     *
     * <p>R5 makes an unrecognised attribute name suppress, which is the right default and cannot be
     * the whole answer: without a way to widen it, a page with {@code <div my-widget-config="$x">}
     * loses the value silently and the developer's next move is {@code $_x.asis()}, which turns
     * Canoe off for that value completely. {@code AttributeNameMatrixTest} owns the classification;
     * what only this file can show is that the factory hands its set to the {@link Canoe} it builds
     * per render, which is the half a configuration change actually depends on.
     *
     * <p>Both directions are asserted, because "the allowlist works" and "the allowlist is the only
     * reason it works" are different claims and only the pair distinguishes them.
     */
    @Test
    public void theFactoryHandsItsPlainTextAllowlistToEveryCanoeItBuilds() {
        Map<String, Object> model = Map.of("data", "widget-42");

        ProductionRenderProbe.Outcome suppressed = ProductionRenderProbe.render(
                "<div my-widget-config=\"$data\">x</div>", model);
        assertFalse(suppressed.exceptionEscaped(), () -> "" + suppressed);
        assertEquals("<div my-widget-config=\"\">x</div>", suppressed.output(),
                "R5: an unconfigured factory drops the value, and the debug log names the attribute"
                        + " - which is the whole reason the extension point exists");

        ProductionRenderProbe.Outcome allowed = ProductionRenderProbe.render(
                "<div my-widget-config=\"$data\">x</div>", model,
                ProductionRenderProbe.Options.defaults()
                        .withPlainTextAttributes("my-widget-config"));
        assertFalse(allowed.exceptionEscaped(), () -> "" + allowed);
        assertEquals("<div my-widget-config=\"widget&#45;42\">x</div>", allowed.output(),
                "...and a configured one encodes it as plain text. Note what the grant is: html(),"
                        + " not a bypass - the hyphen comes back as a character reference and the"
                        + " parser decodes it into the value the developer asked for.");
    }

    /**
     * The same allowlist, configured the way an application actually configures things: a Qlue
     * property rather than a call.
     *
     * <p>{@code buildDefaultVelocityProperties()} is where every shipped factory's {@code init()}
     * reads the application's properties, so the property is read there and a bad name throws from
     * {@code init()} rather than dropping values at request time. The separator is deliberately
     * lenient — commas, whitespace or both — because a list in a property file is written by hand.
     */
    @Test
    public void theAllowlistCanBeConfiguredWithAQlueProperty() {
        assertEquals(Set.of(),
                ProductionRenderProbe.plainTextAttributesFromProperty(null),
                "an application that says nothing gets the built-in allowlist only");

        assertEquals(Set.of("my-widget-config", "hx-target", "x-data"),
                ProductionRenderProbe.plainTextAttributesFromProperty(
                        "my-widget-config, HX-Target x-data"),
                "commas, whitespace or both separate names, and they are lower-cased because the"
                        + " attribute-name scan lower-cases as it buffers");

        assertThrows(IllegalArgumentException.class,
                () -> ProductionRenderProbe.plainTextAttributesFromProperty("title, nonce"),
                "F20: a name whose suppression is the fix must fail at startup, where somebody is"
                        + " reading the stack trace, rather than silently doing nothing on every"
                        + " page");
    }

    /**
     * Two factories, alive at the same time, with different allowlists and no way to see each
     * other's.
     *
     * <p>This is the claim R5 makes about <em>where</em> the extension point lives, and it is the
     * one a passing single-factory test says nothing about. The set is a field of the factory rather
     * than a static on {@link Canoe} precisely so that two applications in one JVM cannot widen each
     * other's plain-text names — a security control changed by an unrelated deployment, with no
     * configuration anybody could audit. {@code ConcurrencyTest.everyStaticFieldIsFinalAndImmutable}
     * guards the other half by requiring every static collection in {@link Canoe} to reject
     * mutation; this guards the half that is about ownership rather than about mutability.
     */
    @Test
    public void twoFactoriesDoNotShareAnAllowlist() {
        var first = ProductionRenderProbe.newFactory("my-widget-config");
        var second = ProductionRenderProbe.newFactory("hx-target");

        assertEquals(Set.of("my-widget-config"), first.getPlainTextAttributes());
        assertEquals(Set.of("hx-target"), second.getPlainTextAttributes(),
                "the second factory must not have inherited the first's name; if it did, the"
                        + " allowlist is shared state and one application is configuring another");

        // ...and widening one after the other exists still does not reach it.
        second.addPlainTextAttributes("x-data");
        assertEquals(Set.of("my-widget-config"), first.getPlainTextAttributes(),
                "a later widening of one factory must not appear in another");
        assertEquals(Set.of("hx-target", "x-data"), second.getPlainTextAttributes());

        // A factory nobody configured sees neither.
        assertEquals(Set.of(), ProductionRenderProbe.newFactory().getPlainTextAttributes());

        // The set a caller gets back is a copy in the sense that matters: it cannot be added to.
        assertThrows(UnsupportedOperationException.class,
                () -> first.getPlainTextAttributes().add("sandbox"),
                "an accessor that hands out a mutable allowlist is the same defect as a static one,"
                        + " reached one method call later");
    }

    /**
     * The names the extension point refuses, on the factory rather than on {@link Canoe}.
     *
     * <p>The refusal is what stops the allowlist being a way to re-open a finding through
     * configuration, and it has to happen at configuration time: a factory that accepted
     * {@code sandbox} and quietly ignored it would look exactly like one that worked.
     */
    @Test
    public void theFactoryRefusesNamesWhoseSuppressionIsTheFix() {
        assertThrows(IllegalArgumentException.class,
                () -> ProductionRenderProbe.render("<p>$data</p>", Map.of("data", "x"),
                        ProductionRenderProbe.Options.defaults()
                                .withPlainTextAttributes("sandbox")),
                "F20: sandbox's suppression is the fix, so the factory must refuse to put it back on"
                        + " html() - loudly, at configuration time");
        assertThrows(IllegalArgumentException.class,
                () -> ProductionRenderProbe.render("<p>$data</p>", Map.of("data", "x"),
                        ProductionRenderProbe.Options.defaults()
                                .withPlainTextAttributes("onclick")),
                "...and the on* prefix rule has no configuration exception either");
    }

    // ------------------------------------------------------------------
    // F13
    // ------------------------------------------------------------------

    /**
     * F13, from this file's angle: what reaches the response when Canoe gives up.
     *
     * <p>{@code CanoeRobustnessTest} owns the finding and drives every rejection message through the
     * production path. The claim here is narrower and belongs with the byte-comparison above — the
     * <em>partial output</em> is identical on both paths, so the harness's
     * {@code RenderResult.output()} is a faithful model of what a real response contains after an
     * encoding error, and the response ends mid-element with no marker of any kind.
     *
     * <p>The last two assertions are the ledger pin. They fail when F13 is fixed, and that failure is
     * the signal to update this test rather than a regression.
     */
    @Test
    public void anEncodingErrorLeavesIdenticalPartialOutputOnBothPathsAndNoMarker() {
        String template = "<p>ok</p><br/>";

        CanoeTestSupport.RenderResult harness = CanoeTestSupport.render(template);
        ProductionRenderProbe.Outcome production =
                ProductionRenderProbe.renderFile(TEMPLATE_DIR + "rejected-void-element.vm", Map.of());

        assertTrue(harness.isError(), () -> "the harness reports the error: " + harness);
        assertEquals("<p>ok</p><br", harness.output(),
                "Canoe writes the characters it accepted and stops mid-element");
        assertEquals(harness.output(), production.output(),
                "and the production response contains exactly the same bytes");

        assertTrue(production.exceptionEscaped(),
                () -> "F13: the recovery branch tests startsWith(ERROR_PREFIX) on the top-level"
                        + " exception, and Template.merge() wraps it as \"IO Error rendering"
                        + " template '...'\", so the branch never runs and the caller gets an"
                        + " exception. " + production);
        assertFalse(production.recoveryBranchRan(),
                "F13: no [Encoding Error] marker reaches the response. When this fails, F13 has"
                        + " been fixed and the ledger needs updating.");
    }

    /**
     * The fixture the F13 assertions use is the {@code <br/>} row from the review's own table, and
     * the file-backed form behaves exactly as the string-backed one.
     *
     * <p>Worth a row of its own because the two go through different resource loaders and the error
     * is raised from inside {@code merge()} either way; if a loader ever buffered the template
     * differently, the number of characters that reached the writer before the error would change,
     * and that is a real difference a caller would see.
     */
    @Test
    public void theFileBackedAndStringBackedTemplatesFailIdentically() {
        ProductionRenderProbe.Outcome fromFile =
                ProductionRenderProbe.renderFile(TEMPLATE_DIR + "rejected-void-element.vm", Map.of());
        ProductionRenderProbe.Outcome fromString = ProductionRenderProbe.render("<p>ok</p><br/>");

        assertEquals(fromString.output(), fromFile.output(),
                "the resource loader does not change what reaches the response");
        assertEquals(fromString.exceptionEscaped(), fromFile.exceptionEscaped());
        assertTrue(causeChainMentions(fromFile.escaped(), Canoe.ERROR_PREFIX),
                () -> "and Canoe's own IOException is in the cause chain either way: "
                        + fromFile.escaped());
    }

    // ------------------------------------------------------------------
    // F22
    // ------------------------------------------------------------------

    /**
     * <strong>F22</strong>, found while building this file's classpath engine: the properties
     * {@code VelocityViewFactory.buildDefaultVelocityProperties()} returns do not start an engine.
     *
     * <p>The method declares {@code resource.loaders = class,string} and configures the string
     * loader's implementation class, its repository name, and the <em>class</em> loader's cache
     * setting — but never {@code resource.loader.class.class}. Velocity 2.4.1 ships no default for
     * that key, so {@code ResourceManagerImpl.initialize()} throws
     * {@code "Unable to find 'resource.loader.class.class' specification in configuration. This is a
     * critical value."} Only {@code ClasspathVelocityViewFactory} supplies it, in its own override.
     *
     * <p>{@code VelocityViewFactory} is {@code public abstract} and its class comment says it "needs
     * subclassing to provide initialization and decide where to look for template files", so a
     * subclass that does exactly that — supplies {@code init()} and {@code constructView()} and
     * inherits the properties — cannot start. Availability only, and it fails loudly at startup
     * rather than silently at request time, which is why it is Low. It is recorded because it is a
     * documented extension point that does not work, and because the half-configured
     * {@code resource.loader.class.cache} line is the sort of thing that makes a reader believe the
     * class loader is configured when it is not.
     *
     * <p>The assertion is on the property map rather than on the exception, so it states the cause;
     * the second half builds an engine to show the consequence. Both flip when the missing line is
     * added to the base class.
     */
    @Test
    public void theBaseFactorysDefaultPropertiesDeclareAClassLoaderItNeverConfigures() {
        Properties properties = ProductionRenderProbe.defaultVelocityProperties();

        assertEquals("class,string", properties.getProperty("resource.loaders"),
                "the base class declares a class loader...");
        assertEquals("false", properties.getProperty("resource.loader.class.cache"),
                "...and configures its caching...");
        assertNull(properties.getProperty("resource.loader.class.class"),
                "F22: ...but never says which loader it is. Velocity has no default for this key.");

        VelocityEngine engine = new VelocityEngine(properties);
        VelocityException thrown = assertThrows(VelocityException.class, engine::init,
                "F22: an engine built from the base class's own properties does not start");
        assertTrue(thrown.getMessage().contains("resource.loader.class.class"),
                () -> "and says so: " + thrown.getMessage());

        // The one shipped subclass that works, and the line that makes it work.
        properties.setProperty("resource.loader.class.class",
                "com.webkreator.qlue.view.velocity.NonCachingClasspathResourceLoader");
        new VelocityEngine(properties).init();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * The model a case needs. Only {@code transition.attribute-then-text} carries a second
     * reference; it gets the inert marker so that the row is testing one variable.
     */
    private static Map<String, Object> modelFor(String caseId, String value) {
        Map<String, Object> model = new LinkedHashMap<>();
        XssCase testCase = CanoeCorpus.byId(caseId);
        model.putAll(testCase.extraModel());
        model.put(testCase.referenceName(), value);
        return model;
    }

    private static String readFixture(String name) throws IOException {
        try (InputStream in = ViewFactoryRenderTest.class.getClassLoader()
                .getResourceAsStream(TEMPLATE_DIR + name)) {
            assertNotNull(in, () -> "no such fixture on the test classpath: " + TEMPLATE_DIR + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Set<String> fixturesOnDisk() {
        URL url = ViewFactoryRenderTest.class.getClassLoader().getResource(TEMPLATE_DIR);
        assertNotNull(url, "the template fixture directory is missing from the test classpath");
        File directory = new File(url.getPath());
        assertTrue(directory.isDirectory(), () -> "not a directory: " + directory);

        Set<String> names = new LinkedHashSet<>();
        String[] listed = directory.list();
        assertNotNull(listed);
        for (String name : listed) {
            if (name.endsWith(".vm")) {
                names.add(name);
            }
        }
        return names;
    }

    private static boolean causeChainMentions(Throwable thrown, String fragment) {
        for (Throwable current = thrown; current != null; current = current.getCause()) {
            if (current.getMessage() != null && current.getMessage().contains(fragment)) {
                return true;
            }
        }
        return false;
    }
}
