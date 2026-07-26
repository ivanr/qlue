package com.webkreator.qlue.view.velocity;

import com.webkreator.qlue.CanoeProbePage;
import com.webkreator.qlue.Page;
import org.apache.velocity.Template;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.resource.loader.StringResourceLoader;
import org.apache.velocity.runtime.resource.util.StringResourceRepository;

import java.io.StringWriter;
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Drives a template through the <em>real</em> {@link VelocityViewFactory#render(Page, VelocityView,
 * Writer)} and reports what a caller of that method actually observes.
 *
 * <p><strong>Why this exists.</strong> The rest of the Canoe suite renders through
 * {@code VelocityEngine.evaluate()}, which is fast, needs no {@code .vm} files and no mocks, and
 * exercises Canoe identically. It does not exercise <em>the factory</em>, and F13 is a defect in the
 * factory rather than in Canoe: {@code render()} means to catch an encoding error and degrade the
 * page to {@code [Encoding Error]}, and the test it uses can never be true. A test that re-implements
 * that test over an exception the harness produced is asserting against a copy of the bug, and will
 * still pass after the bug is fixed — which is exactly the failure mode the ledger rule in
 * {@code PLAN.md} §2.1 exists to prevent. So the F13 assertions go through the production method and
 * observe its actual effects: either an exception escapes, or the marker appears in the response.
 *
 * <p>It also closes the second half of that gap. {@code evaluate()} and {@code Template.merge()}
 * wrap Canoe's {@code IOException} in <em>different</em> messages — {@code "IO Error in writer: ..."}
 * and {@code "IO Error rendering template '...'"} respectively — and only the second is production.
 * Everything else in the suite sees the first one only.
 *
 * <p><strong>Declared in {@code com.webkreator.qlue.view.velocity}</strong> because
 * {@link VelocityView}'s constructor and {@code getTemplate()} are package-private, exactly as
 * {@code CanoeStateProbe} is declared in {@code com.webkreator.qlue.view} to reach Canoe's buffer.
 *
 * <p><strong>What is and is not real here.</strong> The factory, the {@link VelocityView}, the
 * {@link Template}, {@code Template.merge()}, the {@link com.webkreator.qlue.view.Canoe} wrapping,
 * the event cartridge, and the whole {@code try/catch/finally} are production code running
 * unmodified, and so are the {@link Page} and the {@code QlueApplication} behind it — see
 * {@link CanoeProbePage}. Nothing here is mocked: Mockito's inline mock maker cannot instrument a
 * class on this JDK, and a real page turned out to need less scaffolding than a mocked one would.
 * The one production input that is absent is the {@code TransactionContext}, which is null on a page
 * that was never routed, so {@code render()} skips the block publishing {@code _ctx}, {@code _req},
 * {@code _res} and the session into the model. No template under test refers to any of those.
 * Everything on the path between the template text and the response writer is real.
 */
public final class ProductionRenderProbe {

    /** What {@code render()} appends when it believes it has caught an encoding error. */
    public static final String ENCODING_ERROR_MARKER = "[Encoding Error]";

    private static final String REPOSITORY_NAME = "CANOE_PRODUCTION_PROBE_REPOSITORY";

    private static final AtomicLong TEMPLATE_COUNTER = new AtomicLong();

    private static final VelocityEngine ENGINE = createEngine();

    private static final VelocityEngine CLASSPATH_ENGINE = createClasspathEngine();

    private ProductionRenderProbe() {
    }

    /**
     * The two production switches that change what {@code render()} does, so that T20 can cover
     * both settings of each rather than only the defaults.
     */
    public static final class Options {

        private boolean autoEscaping = true;
        private boolean directOutput = false;

        public static Options defaults() {
            return new Options();
        }

        /** {@code VelocityViewFactory.setAutoEscaping(false)}: no event cartridge, no encoding. */
        public Options withoutAutoEscaping() {
            this.autoEscaping = false;
            return this;
        }

        /** A page whose application returns true from {@code allowDirectOutput()}, so {@code $_x} is bound. */
        public Options withDirectOutput() {
            this.directOutput = true;
            return this;
        }
    }

    private static VelocityEngine createEngine() {
        java.util.Properties properties = new java.util.Properties();

        // Mirrors VelocityViewFactory.buildDefaultVelocityProperties(), minus the class and file
        // resource loaders: this probe supplies its templates as strings, and the loader a Template
        // came from has no bearing on how merge() reports an IOException from the writer.
        properties.setProperty(RuntimeConstants.INPUT_ENCODING, "UTF-8");
        properties.setProperty("resource.default_encoding", "UTF-8");
        properties.setProperty(RuntimeConstants.RESOURCE_LOADERS, "string");
        properties.setProperty("resource.loader.string.class",
                "org.apache.velocity.runtime.resource.loader.StringResourceLoader");
        properties.setProperty("resource.loader.string.repository.name", REPOSITORY_NAME);
        properties.setProperty("resource.loader.string.cache", "false");
        properties.setProperty(RuntimeConstants.VM_LIBRARY, "");
        properties.setProperty(RuntimeConstants.VM_PERM_ALLOW_INLINE, "true");
        properties.setProperty(RuntimeConstants.VM_PERM_ALLOW_INLINE_REPLACE_GLOBAL, "true");
        properties.setProperty("directive.set.null.allowed", "true");
        properties.setProperty("resource.manager.log_when_found", "false");
        properties.setProperty("velocimacro.inline.local_scope", "true");
        properties.setProperty("velocimacro.arguments.strict", "true");
        properties.setProperty("context.scope_control.macro", "true");
        properties.setProperty("runtime.strict_mode.enable", "true");
        properties.setProperty("runtime.strict_math", "true");

        VelocityEngine engine = new VelocityEngine(properties);
        engine.init();
        return engine;
    }

    /**
     * A second engine, configured with the {@code class,string} loader pair production actually
     * declares, so that {@code ViewFactoryRenderTest} (T20) can drive real {@code .vm} files from
     * {@code src/test/resources/canoe/templates/}.
     *
     * <p>The string-backed engine above is deliberately kept: F13's assertions are about how
     * {@code Template.merge()} reports an {@code IOException} and do not care where the template came
     * from, and generating a fresh string resource per call is what keeps those tests independent.
     * A file-backed template is a different claim — that the loader, the encoding, and the parse of a
     * real file all agree with the harness — and it is T20's claim, so it gets its own engine rather
     * than a mode flag on the first one.
     */
    private static VelocityEngine createClasspathEngine() {
        java.util.Properties properties = new java.util.Properties();

        properties.setProperty(RuntimeConstants.INPUT_ENCODING, "UTF-8");
        properties.setProperty("resource.default_encoding", "UTF-8");

        // Exactly what buildDefaultVelocityProperties() sets for an application with no priority
        // template path, plus the one property ClasspathVelocityViewFactory adds on top of it.
        // That property is not optional: Velocity 2.4.1 ships no default for
        // resource.loader.class.class and fails initialisation without it ("Unable to find
        // 'resource.loader.class.class' specification in configuration"), so the base class's
        // properties alone do not produce a working engine. Which loader it names depends on
        // resource.loader.file.cache, which is set only when the application declares a priority
        // template path -- so the non-caching loader is what a default Qlue application runs.
        properties.setProperty(RuntimeConstants.RESOURCE_LOADERS, "class,string");
        properties.setProperty("resource.loader.string.class",
                "org.apache.velocity.runtime.resource.loader.StringResourceLoader");
        properties.setProperty("resource.loader.string.repository.name", REPOSITORY_NAME);
        properties.setProperty("resource.loader.class.class",
                "com.webkreator.qlue.view.velocity.NonCachingClasspathResourceLoader");
        properties.setProperty("resource.loader.class.cache", "false");

        properties.setProperty(RuntimeConstants.VM_LIBRARY, "");
        properties.setProperty(RuntimeConstants.VM_LIBRARY_AUTORELOAD, "true");
        properties.setProperty(RuntimeConstants.VM_PERM_ALLOW_INLINE, "true");
        properties.setProperty(RuntimeConstants.VM_PERM_ALLOW_INLINE_REPLACE_GLOBAL, "true");
        properties.setProperty("directive.set.null.allowed", "true");
        properties.setProperty("resource.manager.log_when_found", "false");
        properties.setProperty("velocimacro.inline.local_scope", "true");
        properties.setProperty("velocimacro.arguments.strict", "true");
        properties.setProperty("context.scope_control.macro", "true");
        properties.setProperty("runtime.strict_mode.enable", "true");
        properties.setProperty("runtime.strict_math", "true");

        VelocityEngine engine = new VelocityEngine(properties);
        engine.init();
        return engine;
    }

    /**
     * The properties {@link VelocityViewFactory#buildDefaultVelocityProperties} produces for a
     * default application, so that a test can build an engine from them and see whether one starts.
     *
     * <p>Reachable only from this package, which is why it is exposed here rather than assembled by
     * hand in the test: a hand-assembled copy would be a copy of what somebody believed the method
     * returns, and F13 is this suite's standing lesson about copies of the thing under test.
     */
    public static java.util.Properties defaultVelocityProperties() {
        return new ProbeViewFactory().buildDefaultVelocityProperties(new CanoeProbePage().getApp());
    }

    /**
     * Renders a {@code .vm} file from the test classpath through the production render path.
     *
     * @param resourceName the template's classpath name, e.g. {@code canoe/templates/body-text.vm}
     */
    public static Outcome renderFile(String resourceName, Map<String, Object> model) {
        return renderFile(resourceName, model, Options.defaults());
    }

    /** As {@link #renderFile(String, Map)}, with the production switches under the caller's control. */
    public static Outcome renderFile(String resourceName, Map<String, Object> model,
                                     Options options) {
        Template template;
        try {
            template = CLASSPATH_ENGINE.getTemplate(resourceName, "UTF-8");
        } catch (Exception e) {
            throw new IllegalStateException("Could not load template " + resourceName
                    + " from the test classpath", e);
        }
        return merge(template, model, options);
    }

    /** Renders a template with no references bound. */
    public static Outcome render(String templateText) {
        return render(templateText, new LinkedHashMap<>());
    }

    /** Renders a template with a single payload bound to {@code $data}. */
    public static Outcome render(String templateText, String payload) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("data", payload);
        return render(templateText, model);
    }

    /**
     * Renders a template through {@code VelocityViewFactory.render(page, view, writer)} and reports
     * what the caller sees. Never throws: an escaping exception is captured, because "did an
     * exception escape" is the assertion.
     */
    public static Outcome render(String templateText, Map<String, Object> model) {
        return render(templateText, model, Options.defaults());
    }

    /** As {@link #render(String, Map)}, with the production switches under the caller's control. */
    public static Outcome render(String templateText, Map<String, Object> model, Options options) {
        String name = "canoe-production-probe-" + TEMPLATE_COUNTER.incrementAndGet() + ".vm";
        StringResourceRepository repository = StringResourceLoader.getRepository(REPOSITORY_NAME);
        repository.putStringResource(name, templateText);

        Template template;
        try {
            template = ENGINE.getTemplate(name);
        } catch (Exception e) {
            throw new IllegalStateException("Could not build a Template from " + templateText, e);
        }
        return merge(template, model, options);
    }

    /**
     * The part that is production code: build the view, build the page, and call
     * {@code VelocityViewFactory.render(page, view, writer)}.
     */
    private static Outcome merge(Template template, Map<String, Object> model, Options options) {
        VelocityViewFactory factory = new ProbeViewFactory();
        factory.setAutoEscaping(options.autoEscaping);

        VelocityView view = new VelocityView(factory, template);

        Page page = new CanoeProbePage(options.directOutput);
        page.getModel().putAll(model);

        StringWriter response = new StringWriter();
        try {
            factory.render(page, view, response);
            return new Outcome(response.toString(), null);
        } catch (Exception e) {
            return new Outcome(response.toString(), e);
        }
    }

    /**
     * The concrete factory. {@code render()} is inherited unmodified from
     * {@link VelocityViewFactory}; {@code init()} and {@code constructView()} are not on the path
     * under test, because the probe hands the view in ready-made.
     */
    private static final class ProbeViewFactory extends VelocityViewFactory {

        @Override
        public void init(com.webkreator.qlue.QlueApplication qlueApp) {
            throw new UnsupportedOperationException("not on the path under test");
        }

        @Override
        public com.webkreator.qlue.view.View constructView(String viewName) {
            throw new UnsupportedOperationException("not on the path under test");
        }
    }

    /**
     * What a caller of {@code VelocityViewFactory.render()} observes: the bytes that reached the
     * response, and the exception that escaped, if one did.
     */
    public static final class Outcome {

        private final String output;
        private final Exception escaped;

        Outcome(String output, Exception escaped) {
            this.output = output;
            this.escaped = escaped;
        }

        /** Everything written to the response writer, including partial output before an error. */
        public String output() {
            return output;
        }

        /** True when {@code render()} let the exception through, which in production is a 500. */
        public boolean exceptionEscaped() {
            return escaped != null;
        }

        /** The exception {@code render()} let through, or null. */
        public Exception escaped() {
            return escaped;
        }

        /**
         * True when {@code render()}'s recovery branch ran, that is when the response carries the
         * {@code [Encoding Error]} marker instead of an exception. This is the observation that
         * flips when F13 is fixed.
         */
        public boolean recoveryBranchRan() {
            return output.contains(ENCODING_ERROR_MARKER);
        }

        @Override
        public String toString() {
            return "Outcome[output=\"" + output + "\", escaped="
                    + (escaped == null ? "none" : escaped.getClass().getSimpleName() + ": "
                    + escaped.getMessage()) + "]";
        }
    }
}
