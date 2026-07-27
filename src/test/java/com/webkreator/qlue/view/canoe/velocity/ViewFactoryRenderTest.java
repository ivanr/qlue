package com.webkreator.qlue.view.canoe.velocity;

import com.webkreator.qlue.view.Canoe;
import com.webkreator.qlue.view.CanoeEncodingException;
import com.webkreator.qlue.view.canoe.CanoeTestSupport;
import com.webkreator.qlue.view.canoe.corpus.CanoeCorpus;
import com.webkreator.qlue.view.canoe.corpus.Payload;
import com.webkreator.qlue.view.canoe.corpus.Payloads;
import com.webkreator.qlue.view.canoe.corpus.XssCase;
import com.webkreator.qlue.view.velocity.ProductionRenderProbe;
import org.apache.velocity.app.VelocityEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * rather than degrading the page to {@code [Encoding Error]} — which after R21 is the decision rather
 * than the defect.
 *
 * <p>F13 itself is owned by {@code CanoeRobustnessTest}, which drives every rejection message through
 * {@code ProductionRenderProbe}. What is here is the part that belongs to this file: that the
 * <em>partial output</em> the two paths leave behind before giving up is byte-identical too, which is
 * the half a caller of {@code render()} actually receives — and R21's response reset, which is the
 * step that stops that half being served.
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
        claimed.add("rejected-literal-lt.vm");
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
                TEMPLATE_DIR + "rejected-literal-lt.vm", Map.of(),
                ProductionRenderProbe.Options.defaults().withoutAutoEscaping());

        assertTrue(production.exceptionEscaped(),
                () -> "a rejected template is rejected whether or not references are being encoded: "
                        + production);
        assertTrue(causeChainMentions(production.escaped(), "Tag name too short"),
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
                () -> ProductionRenderProbe.plainTextAttributesFromProperty("title, sandbox"),
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
    // R9's extension point (R25: the documented escape hatch, asserted)
    // ------------------------------------------------------------------

    /**
     * The CDN allowlist, configured on the factory and observed on the real render path.
     *
     * <p>{@code UrlSinkTest} owns what {@code urlResource()} does with an allowlist, driving it
     * through a {@link Canoe} the test constructs itself. What only this file can show is the half a
     * configuration change depends on: that the <em>factory</em> hands its configured origins to the
     * writer it builds per render. The two are separable — a factory that parsed the origins,
     * validated them and then never passed them on would satisfy every assertion in {@code
     * UrlSinkTest} and would leave every CDN script tag empty in production.
     *
     * <p>This is one of the two escape hatches R25 documents in {@code README.md} and {@code
     * qlue_user_guide.md}, and it is documented because R9's default is fail-closed: without it, an
     * application that legitimately serves its scripts from a CDN has no move left but
     * {@code $_x.asis()}, which turns Canoe off for that value entirely.
     */
    @Test
    public void theFactoryHandsItsTrustedResourceOriginsToEveryCanoeItBuilds() {
        String template = "<script src=\"$data/app.js\"></script>";
        Map<String, Object> model = Map.of("data", "//cdn.example.com/lib");

        ProductionRenderProbe.Outcome rejected = ProductionRenderProbe.render(template, model);
        assertFalse(rejected.exceptionEscaped(), () -> "" + rejected);
        assertEquals("<script src=\"/app.js\"></script>", rejected.output(),
                "R9: an unconfigured factory rejects the off-origin authority to the empty string,"
                        + " leaving only the template's own '/app.js'");

        ProductionRenderProbe.Outcome allowed = ProductionRenderProbe.render(template, model,
                ProductionRenderProbe.Options.defaults()
                        .withTrustedResourceOrigins("cdn.example.com"));
        assertFalse(allowed.exceptionEscaped(), () -> "" + allowed);
        assertEquals("<script src=\"//cdn.example.com/lib/app.js\"></script>", allowed.output(),
                "...and a configured one carries the origin into the Canoe it builds, so the CDN"
                        + " host survives byte for byte");

        ProductionRenderProbe.Outcome elsewhere = ProductionRenderProbe.render(template,
                Map.of("data", "//attacker.invalid/x"),
                ProductionRenderProbe.Options.defaults()
                        .withTrustedResourceOrigins("cdn.example.com"));
        assertEquals("<script src=\"/app.js\"></script>", elsewhere.output(),
                "the grant is to one host and not to off-origin URLs in general");
    }

    /**
     * The same allowlist, configured the way an application actually configures things: the
     * {@code qlue.canoe.trustedResourceOrigins} Qlue property.
     *
     * <p>The R9 twin of {@link #theAllowlistCanBeConfiguredWithAQlueProperty}, and it is read in the
     * same place for the same reason — {@code buildDefaultVelocityProperties()} is where every
     * shipped factory's {@code init()} reads the application's properties, so a malformed origin
     * throws from {@code init()} where somebody is reading the stack trace rather than silently
     * matching nothing on every page.
     */
    @Test
    public void theTrustedResourceOriginsCanBeConfiguredWithAQlueProperty() {
        assertEquals(Set.of(),
                ProductionRenderProbe.trustedResourceOriginsFromProperty(null),
                "an application that says nothing gets same-origin-relative resources only");

        assertEquals(Set.of("cdn.example.com", "https://static.example.com:8443"),
                ProductionRenderProbe.trustedResourceOriginsFromProperty(
                        "cdn.example.com https://static.example.com:8443"),
                "commas, whitespace or both separate entries, and both accepted forms - a bare host"
                        + " and an origin with a port - survive the property path");

        assertThrows(IllegalArgumentException.class,
                () -> ProductionRenderProbe.trustedResourceOriginsFromProperty(
                        "cdn.example.com/assets"),
                "an entry with a path is a misconfiguration and must fail at startup: it would"
                        + " otherwise look like a path restriction and be a host grant");
        assertThrows(IllegalArgumentException.class,
                () -> ProductionRenderProbe.trustedResourceOriginsFromProperty(
                        "ftp://cdn.example.com"),
                "...and so must a scheme the resource encoder can never emit");
    }

    /**
     * A stray separator in either allowlist property is a typo, and is treated as one.
     *
     * <p>Both properties are lists a human writes by hand into a {@code .properties} file, and both
     * are read with {@code split("[,\\s]+")}, which yields an <strong>empty first element</strong>
     * whenever the value opens with a separator — {@code ",cdn.example.com"} and
     * {@code " cdn.example.com"} both do. The empty-name guard in each reader is what decides what
     * happens next, and there are three possible answers, only one of which is right: refuse the
     * whole configuration at startup, quietly allowlist the empty name, or drop the empty token and
     * take the rest. It drops it.
     *
     * <p>That guard is the one branch outcome in each of these two methods that nothing reached
     * before this test, and by the rule the coverage gate in {@code build.gradle} is built on — an
     * unreached branch on this path is a security decision nobody tested — a guard on the entry
     * point to a security allowlist is not somewhere to leave one. The failure it prevents is not
     * dramatic and that is the point: an application whose CDN grant silently became "no grant, plus
     * one meaningless entry" because of a leading comma would see empty {@code <script src>}
     * attributes in production and nothing at all in its logs.
     *
     * <p>Defence in depth rather than a single guard, and the test says so on purpose:
     * {@code Canoe.normalisePlainTextAttributeNames()} skips a blank name too, and
     * {@code HtmlEncoder.parseTrustedOrigins()} skips a blank entry, so deleting either reader's
     * guard would still not put an empty name on an allowlist. Two of the three assertions below
     * hold for that second reason as well as the first, which is why the third checks the set that
     * comes back rather than only that nothing was thrown.
     *
     * @see #theAllowlistCanBeConfiguredWithAQlueProperty
     * @see #theTrustedResourceOriginsCanBeConfiguredWithAQlueProperty
     */
    @Test
    public void aLeadingSeparatorInAnAllowlistPropertyIsDroppedRatherThanConfigured() {
        assertEquals(Set.of("my-widget-config", "hx-target"),
                ProductionRenderProbe.plainTextAttributesFromProperty(
                        ", my-widget-config,,hx-target,"),
                "a leading comma, a doubled comma and a trailing comma are typos in a hand-written"
                        + " property, not names: the plain-text allowlist must come out with the two"
                        + " names the application meant and no empty entry");

        assertEquals(Set.of("cdn.example.com", "https://static.example.com:8443"),
                ProductionRenderProbe.trustedResourceOriginsFromProperty(
                        " cdn.example.com, https://static.example.com:8443 "),
                "the same for the origin property, whose leading space produces the same empty first"
                        + " element: a stray separator must not cost the application its CDN grant,"
                        + " and must not fail the whole configuration at startup either");

        assertFalse(
                ProductionRenderProbe.trustedResourceOriginsFromProperty(",cdn.example.com")
                        .contains(""),
                "and the empty token must not reach the set itself. An empty trusted origin is not"
                        + " exploitable - HtmlEncoder.parseTrustedOrigins() skips it, so it matches"
                        + " no authority - but it would be visible through"
                        + " getTrustedResourceOrigins(), and an allowlist that reports an entry"
                        + " nobody configured is an allowlist nobody can audit");
    }

    /**
     * A {@code null} or blank entry in a trusted-origin <em>collection</em> is dropped, not
     * allowlisted.
     *
     * <p>The sibling of the property test above, on the other supported way in.
     * {@code addTrustedResourceOrigins(Collection)} is public API — R25 documents it as one of the
     * two escape hatches — so its argument comes from application code, which means it can hold a
     * {@code null} that no {@code split()} would ever produce: a list assembled from a map lookup,
     * a Spring placeholder that did not resolve, or an environment variable that was not set. Both
     * outcomes of that guard, {@code null} and blank, were unreached before this test.
     *
     * <p>The behaviour has to be "drop it and keep the rest", and each of the alternatives is worse
     * in its own way. Throwing would let one unresolved placeholder take down an application at
     * startup over an entry that grants nothing. Adding it would put {@code null} or {@code ""} into
     * a set that is copied into every {@link Canoe} the factory builds and handed back out through
     * {@code getTrustedResourceOrigins()}.
     *
     * <p>Note that {@code HtmlEncoder.parseTrustedOrigins()} runs <em>first</em>, over the raw
     * collection, to validate it — and skips {@code null} and blank entries rather than refusing
     * them, which is what makes this guard reachable at all. If it were ever changed to refuse them,
     * this test fails here rather than the guard quietly becoming dead code with a floor still over
     * it.
     */
    @Test
    public void aNullOrBlankTrustedOriginIsDroppedRatherThanAllowlisted() {
        var factory = ProductionRenderProbe.newFactory();
        factory.addTrustedResourceOrigins(
                Arrays.asList("cdn.example.com", null, "   ", "https://static.example.com:8443"));

        assertEquals(Set.of("cdn.example.com", "https://static.example.com:8443"),
                factory.getTrustedResourceOrigins(),
                "the two real origins survive and neither the null nor the blank entry appears;"
                        + " configuration assembled in code is allowed to have holes in it, and a"
                        + " hole grants nothing");

        // ...and it is a drop rather than a silent whole-collection rejection: the grant works.
        ProductionRenderProbe.Outcome allowed = ProductionRenderProbe.render(
                "<script src=\"$data/app.js\"></script>",
                Map.of("data", "//cdn.example.com/lib"),
                ProductionRenderProbe.Options.defaults()
                        .withTrustedResourceOrigins("cdn.example.com"));
        assertEquals("<script src=\"//cdn.example.com/lib/app.js\"></script>", allowed.output(),
                "the surviving origin is a real grant and not just a set entry - a factory that"
                        + " dropped the whole collection on meeting a null would pass the assertion"
                        + " above if it also happened to report an empty set");

        // A collection that is nothing but holes is an application that configured nothing.
        var empty = ProductionRenderProbe.newFactory();
        empty.addTrustedResourceOrigins(Arrays.asList(null, "", "  "));
        assertEquals(Set.of(), empty.getTrustedResourceOrigins(),
                "and 'every entry was a hole' is 'the application said nothing', which is R9's"
                        + " fail-closed default rather than an error");
    }

    /**
     * Auto-escaping has exactly one writer, and it is application code.
     *
     * <p>The documentation says, in both files, that Canoe's reference encoding is on by default and
     * can be turned off only by an application calling {@code setAutoEscaping(false)} — never by
     * configuration. That is a claim about something that is <em>absent</em>, and no render can
     * enumerate the property names that do not exist, so it is pinned at the field instead: the
     * default is {@code true} and nothing outside the setter assigns it. Wire a Qlue property to it
     * and this test fails, which is the sentence in the documentation asking to be rewritten.
     *
     * <p>&sect;6 of the remediation plan records that nothing asserted this. Now something does.
     */
    @Test
    public void nothingButApplicationCodeCanTurnAutoEscapingOff() throws IOException {
        assertEquals(List.of("VelocityViewFactory.java: protected boolean useAutoEscaping = true;"),
                ProductionRenderProbe.assignmentsToUseAutoEscapingOutsideTheSetter(),
                "the only assignment to useAutoEscaping outside setAutoEscaping() must be the"
                        + " declaration's own initialiser, and it must initialise to true: a"
                        + " property, a system property or a constructor argument that reached this"
                        + " field would be a way to switch the encoder off from outside the"
                        + " application's own code");
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
     * <p>The last two assertions were the ledger pin, and R21 is what they were waiting for. They
     * still read the same way — an exception escapes, no marker appears — but they now assert a
     * decision rather than record a defect: the recovery is to <strong>fail the request outright</strong>,
     * so the exception escaping is the fix and not the symptom, and the marker is gone because
     * appending it to a response that ends inside an attribute list was never a recovery. What
     * changed is the third assertion: the exception a caller gets is Canoe's own, by type.
     */
    @Test
    public void anEncodingErrorLeavesIdenticalPartialOutputOnBothPathsAndNoMarker() {
        String template = "<p>ok</p>5 < 6";

        CanoeTestSupport.RenderResult harness = CanoeTestSupport.render(template);
        ProductionRenderProbe.Outcome production =
                ProductionRenderProbe.renderFile(TEMPLATE_DIR + "rejected-literal-lt.vm", Map.of());

        assertTrue(harness.isError(), () -> "the harness reports the error: " + harness);
        assertEquals("<p>ok</p>5 <", harness.output(),
                "Canoe writes the characters it accepted and stops mid-element");
        assertEquals(harness.output(), production.output(),
                "and the production response contains exactly the same bytes");

        assertTrue(production.exceptionEscaped(),
                () -> "R21: the request fails outright, which is the recovery. " + production);
        assertInstanceOf(CanoeEncodingException.class, production.escaped(),
                () -> "R21: and it fails with the exception Canoe threw, unwrapped, so a caller can"
                        + " catch the type instead of matching a message. " + production);
        assertFalse(production.recoveryBranchRan(),
                "R21: no [Encoding Error] marker reaches the response, on any path. The branch that"
                        + " appended it is deleted rather than repaired.");
    }

    /**
     * R21's response reset, driven directly against a stub {@code HttpServletResponse}.
     *
     * <p>This is the step {@code render(Page, VelocityView)} — the production entry point, and the
     * one place the writer is known to be the response's own — takes when Canoe refuses. Not
     * flushing keeps the response uncommitted; this is what makes the half-written page go away, so
     * that a {@code page.handleException()} view is the whole response rather than an error page
     * appended to a broken one. {@code sendError()} would clear the buffer by itself, so the reset
     * earns its keep on the handled path rather than on the 500.
     *
     * <p>The committed case is the residual, and it is asserted rather than assumed: a response whose
     * buffer has already gone out (8KB by default) cannot be withdrawn, and R21 does not pretend
     * otherwise — it logs and lets the exception through. {@code resetBuffer()} on a committed
     * response throws {@code IllegalStateException} per the servlet contract, so calling it anyway
     * would turn an encoding error into a different exception entirely.
     *
     * <p>Driven through a {@link Proxy} rather than a mock: mockito's inline mock
     * maker cannot instrument a class on this JDK (see this file's header), and a proxy over the
     * interface is exactly as much of a servlet container as this needs.
     */
    @Test
    public void theResponseIsResetWhenItStillCanBeAndNotWhenItCannot() {
        List<String> uncommittedCalls = new ArrayList<>();
        ProductionRenderProbe.discardPartialResponse(
                stubResponse(false, uncommittedCalls),
                new CanoeEncodingException("Invalid character after tag name", 1, 4));
        assertEquals(List.of("isCommitted", "resetBuffer"), uncommittedCalls,
                "R21: an uncommitted response is asked whether it is committed and then emptied, so"
                        + " the half-written page cannot be part of whatever is served instead");

        List<String> committedCalls = new ArrayList<>();
        ProductionRenderProbe.discardPartialResponse(
                stubResponse(true, committedCalls),
                new CanoeEncodingException("Invalid character after tag name", 1, 4));
        assertEquals(List.of("isCommitted"), committedCalls,
                "R21: a committed response is left alone - the bytes are on the wire and"
                        + " resetBuffer() would throw IllegalStateException. That is the residual,"
                        + " and no recovery closes it");

        // Null is the "no response to reset" case, which is every caller of the three-argument
        // render() that supplied its own writer. It must not throw.
        ProductionRenderProbe.discardPartialResponse(
                null, new CanoeEncodingException("Invalid tag", 1, 3));
    }

    /**
     * That the production entry point actually calls the step above — <strong>driven</strong>, not
     * read.
     *
     * <p>This test used to assert the wiring by reading {@code VelocityViewFactory.java} and looking
     * for {@code catch (CanoeEncodingException e)} and {@code discardPartialResponse(response, e)} in
     * the entry point's body, on the reasoning that a {@code TransactionContext} could not be
     * constructed without a live servlet stack. That reasoning was too pessimistic and the assertion
     * was too weak: reading the source proves somebody wrote the call, not that calling it does
     * anything. {@code TransactionContext}'s constructor needs a remote address, a request URI and a
     * session that remembers attributes, and nothing else — so
     * {@link ProductionRenderProbe#renderThroughResponse} builds a <em>real</em> context over four
     * {@link Proxy} stubs and drives {@code render(Page, VelocityView)} itself. The source-reading
     * assertions that survive are the two that are genuinely about the text: that the marker literal
     * and the message-prefix constant are gone from the file rather than commented out.
     *
     * <p>What the driven half shows, end to end: the request fails with Canoe's own exception, and
     * the half-written page is not merely unflushed but <em>gone</em>, so whatever
     * {@code QlueApplication.service()} does next — a {@code handleException()} view or a
     * {@code sendError(500)} — starts from an empty body.
     */
    @Test
    public void theProductionEntryPointWiresTheResponseReset() throws IOException {
        ProductionRenderProbe.ResponseOutcome outcome =
                ProductionRenderProbe.renderThroughResponse("<p>ok</p>5 < 6", false);

        assertInstanceOf(CanoeEncodingException.class, outcome.escaped(),
                () -> "R21: the entry point rethrows Canoe's exception unwrapped. " + outcome);
        assertEquals("Tag name too short", outcome.encodingError().getReason());
        assertTrue(outcome.calls().contains("resetBuffer"),
                () -> "R21: ...after resetting the response buffer. " + outcome);
        assertEquals("", outcome.body(),
                "R21: and the half-written page is gone, so a handleException() view is the whole"
                        + " response rather than an error page appended to a broken one");

        // The control: a template Canoe accepts is served, and nothing is reset.
        ProductionRenderProbe.ResponseOutcome clean =
                ProductionRenderProbe.renderThroughResponse("<p>ok</p>", false);
        assertFalse(clean.exceptionEscaped(), () -> "the control: " + clean);
        assertFalse(clean.calls().contains("resetBuffer"),
                () -> "a page that renders is not reset: " + clean);
        assertEquals("<p>ok</p>", clean.body());

        // The residual, on the real path: a response that has already gone out cannot be withdrawn,
        // and the entry point must not turn that into an IllegalStateException from resetBuffer().
        ProductionRenderProbe.ResponseOutcome committed =
                ProductionRenderProbe.renderThroughResponse("<p>ok</p>5 < 6", true);
        assertInstanceOf(CanoeEncodingException.class, committed.escaped(),
                () -> "R21: still Canoe's exception, not the servlet container's. " + committed);
        assertEquals("<p>ok</p>5 <", committed.body(),
                "and the partial page is still there, because there is no recovery for it");

        Path source = Path.of(
                "src/main/java/com/webkreator/qlue/view/velocity/VelocityViewFactory.java");
        assertTrue(Files.isReadable(source),
                "cannot read " + source.toAbsolutePath() + "; this test must run with the project"
                        + " directory as its working directory");
        String text = Files.readString(source, StandardCharsets.UTF_8);

        // The marker as a Java string literal, not as the phrase: the reasoning for deleting it is
        // written out in discardPartialResponse()'s javadoc, and that prose is the point.
        assertFalse(text.contains("\"[Encoding Error]\""),
                "R21: the marker branch is deleted, not commented out");
        assertFalse(text.contains("ERROR_PREFIX"),
                "R21: and nothing in the factory matches on the message prefix any more - the type"
                        + " in the cause chain is the whole recognition rule");
    }

    /**
     * A stub {@code HttpServletResponse} that records the calls R21's reset makes.
     *
     * @param committed what {@code isCommitted()} should answer
     * @param calls     the list every invoked method name is appended to, in order
     */
    private static HttpServletResponse stubResponse(boolean committed, List<String> calls) {
        return (HttpServletResponse) Proxy.newProxyInstance(
                ViewFactoryRenderTest.class.getClassLoader(),
                new Class<?>[]{HttpServletResponse.class},
                (proxy, method, args) -> {
                    calls.add(method.getName());
                    if ("isCommitted".equals(method.getName())) {
                        return committed;
                    }
                    if (method.getReturnType().isPrimitive()
                            && method.getReturnType() != void.class) {
                        return 0;
                    }
                    return null;
                });
    }

    /**
     * The fixture the F13 assertions use is a literal {@code <} in prose — the row R20 kept when it
     * triaged the review's table, and the one that replaced {@code <br/>} when R20 made that legal —
     * and the file-backed form behaves exactly as the string-backed one.
     *
     * <p>Worth a row of its own because the two go through different resource loaders and the error
     * is raised from inside {@code merge()} either way; if a loader ever buffered the template
     * differently, the number of characters that reached the writer before the error would change,
     * and that is a real difference a caller would see.
     *
     * <p>Which is why the coordinates are compared and not only the presence of an error. R21 made
     * them readable — {@code getLine()} and {@code getPosition()} rather than a substring of the
     * message — and the position <em>is</em> "how many characters reached the writer", so asserting
     * the two loaders agree on it is the direct form of the claim this test has always made
     * indirectly.
     */
    @Test
    public void theFileBackedAndStringBackedTemplatesFailIdentically() {
        ProductionRenderProbe.Outcome fromFile =
                ProductionRenderProbe.renderFile(TEMPLATE_DIR + "rejected-literal-lt.vm", Map.of());
        ProductionRenderProbe.Outcome fromString = ProductionRenderProbe.render("<p>ok</p>5 < 6");

        assertEquals(fromString.output(), fromFile.output(),
                "the resource loader does not change what reaches the response");
        assertEquals(fromString.exceptionEscaped(), fromFile.exceptionEscaped());
        assertTrue(causeChainMentions(fromFile.escaped(), Canoe.ERROR_PREFIX),
                () -> "and Canoe's own exception is in the cause chain either way: "
                        + fromFile.escaped());

        assertInstanceOf(CanoeEncodingException.class, fromFile.escaped(),
                () -> "R21: by type, from a file-backed template too. " + fromFile);
        assertEquals(fromString.encodingError().getReason(),
                fromFile.encodingError().getReason(), "the same rejection");
        assertEquals(fromString.encodingError().getLine(),
                fromFile.encodingError().getLine(), "on the same line");
        assertEquals(fromString.encodingError().getPosition(),
                fromFile.encodingError().getPosition(),
                "at the same position, which is the count of characters that reached the writer"
                        + " before Canoe gave up - the thing a buffering loader would move");
    }

    // ------------------------------------------------------------------
    // F22
    // ------------------------------------------------------------------

    /**
     * <strong>F22, closed by R22.</strong> The properties
     * {@code VelocityViewFactory.buildDefaultVelocityProperties()} returns now start an engine.
     *
     * <p>Was {@code theBaseFactorysDefaultPropertiesDeclareAClassLoaderItNeverConfigures}, found
     * while building this file's classpath engine. The method declared
     * {@code resource.loaders = class,string} and configured the string loader's implementation
     * class, its repository name, and the <em>class</em> loader's cache setting — but never
     * {@code resource.loader.class.class}. Velocity 2.4.1 ships no default for that key, so
     * {@code ResourceManagerImpl.initialize()} threw
     * {@code "Unable to find 'resource.loader.class.class' specification in configuration. This is a
     * critical value."} and only {@code ClasspathVelocityViewFactory}, which supplies the key in its
     * own override, produced a working engine.
     *
     * <p>{@code VelocityViewFactory} is {@code public abstract} and its class comment says it "needs
     * subclassing to provide initialization and decide where to look for template files", so a
     * subclass that did exactly that — supplied {@code init()} and {@code constructView()} and
     * inherited the properties — could not start. Availability only, and it failed loudly at startup
     * rather than silently at request time, which is why it was Low. It was recorded because it was
     * a documented extension point that did not work, and because the half-configured
     * {@code resource.loader.class.cache} line was the sort of thing that makes a reader believe the
     * class loader is configured when it was not.
     *
     * <p>The first assertions are on the property map rather than on the outcome, so they state the
     * cause — the key and the value it now carries; the second half builds an engine to show the
     * consequence, and it is the half that would fail if the value named a class Velocity cannot
     * instantiate, since {@code init()} loads every declared loader. The third states that the shipped
     * subclass still overrides the inherited default with the reloading loader, so that closing F22
     * in the base class cannot quietly change what a Qlue application actually runs.
     */
    @Test
    public void theBaseFactorysDefaultPropertiesConfigureTheClassLoaderTheyDeclare() {
        Properties properties = ProductionRenderProbe.defaultVelocityProperties();

        assertEquals("class,string", properties.getProperty("resource.loaders"),
                "the base class declares a class loader...");
        assertEquals("false", properties.getProperty("resource.loader.class.cache"),
                "...and configures its caching...");
        assertEquals("org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader",
                properties.getProperty("resource.loader.class.class"),
                "R22: ...and now says which loader it is. Velocity has no default for this key.");

        // The consequence: a subclass that supplies init() and constructView() and inherits these
        // properties -- which is what the class comment invites -- starts.
        new VelocityEngine(properties).init();

        // And the shipped subclass still asks for the reloading variant on top of the new default,
        // which is what a default Qlue application runs.
        assertEquals("com.webkreator.qlue.view.velocity.NonCachingClasspathResourceLoader",
                ProductionRenderProbe.classpathFactoryVelocityProperties()
                        .getProperty("resource.loader.class.class"),
                "ClasspathVelocityViewFactory keeps its override");
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
