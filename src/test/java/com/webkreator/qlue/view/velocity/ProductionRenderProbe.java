package com.webkreator.qlue.view.velocity;

import com.webkreator.qlue.CanoeProbePage;
import com.webkreator.qlue.Page;
import com.webkreator.qlue.TransactionContext;
import com.webkreator.qlue.view.CanoeEncodingException;
import org.apache.velocity.Template;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.resource.loader.StringResourceLoader;
import org.apache.velocity.runtime.resource.util.StringResourceRepository;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.Array;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Drives a template through the <em>real</em> {@link VelocityViewFactory#render(Page, VelocityView,
 * Writer)} and reports what a caller of that method actually observes.
 *
 * <p><strong>Why this exists.</strong> The rest of the Canoe suite renders through
 * {@code VelocityEngine.evaluate()}, which is fast, needs no {@code .vm} files and no mocks, and
 * exercises Canoe identically. It does not exercise <em>the factory</em>, and F13 was a defect in the
 * factory rather than in Canoe: {@code render()} meant to catch an encoding error and degrade the
 * page to {@code [Encoding Error]}, and the test it used could never be true. A test that
 * re-implements that test over an exception the harness produced is asserting against a copy of the
 * bug, and would still pass after the bug is fixed — which is exactly the failure mode the ledger
 * rule exists to prevent. So the F13 assertions go through the production
 * method and observe its actual effects: what escaped, and what reached the response.
 *
 * <p><strong>R21 changed what those effects are, not the reason for observing them here.</strong>
 * {@code render()} now recognises an encoding error by its type in the cause chain
 * ({@link CanoeEncodingException#findIn}), fails the request outright with that typed exception, and
 * leaves the partial output unflushed so the response can still be reset. {@link
 * Outcome#encodingError()} is what the tests assert on; {@link Outcome#recoveryBranchRan()} survives
 * as the standing check that no marker was reintroduced.
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
 * On the {@code render(page, view, writer)} entry points the one production input that is absent is
 * the {@code TransactionContext}, which is null on a page that was never routed, so {@code render()}
 * skips the block publishing {@code _ctx}, {@code _req}, {@code _res} and the session into the
 * model. No template under test refers to any of those. Everything on the path between the template
 * text and the response writer is real.
 *
 * <p>{@link #renderThroughResponse(String, boolean)} is the exception, and it is R21's: it drives the
 * two-argument {@code render(Page, VelocityView)} with a real {@code TransactionContext} built over
 * stub servlet interfaces, because that entry point exists only to reach the response and reset it.
 */
public final class ProductionRenderProbe {

    /**
     * What {@code render()} used to append when it believed it had caught an encoding error.
     *
     * <p>Kept after R21 deleted the branch that wrote it, because "no marker reaches the response"
     * is now a property worth holding rather than an observation about a branch that could not run.
     * See {@link Outcome#recoveryBranchRan()}.
     */
    public static final String ENCODING_ERROR_MARKER = "[Encoding Error]";

    private static final String REPOSITORY_NAME = "CANOE_PRODUCTION_PROBE_REPOSITORY";

    private static final AtomicLong TEMPLATE_COUNTER = new AtomicLong();

    private static final VelocityEngine ENGINE = createEngine();

    private static final VelocityEngine CLASSPATH_ENGINE = createClasspathEngine();

    private ProductionRenderProbe() {
    }

    /**
     * The production switches that change what {@code render()} does, so that T20 can cover every
     * setting of each rather than only the defaults. Two of them until R5, which added the
     * plain-text attribute allowlist.
     */
    public static final class Options {

        private boolean autoEscaping = true;
        private boolean directOutput = false;
        private String[] plainTextAttributes = new String[0];
        private String[] trustedResourceOrigins = new String[0];

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

        /**
         * {@code VelocityViewFactory.addPlainTextAttributes(...)}: R5's application-level extension
         * point, exercised on the real render path rather than by constructing a {@link
         * com.webkreator.qlue.view.Canoe} directly.
         *
         * <p>Worth having as a switch here for the same reason the other two are: what the unit
         * tests can show is that a configured {@link com.webkreator.qlue.view.Canoe} classifies the
         * name, and what only this path can show is that the factory actually hands its set to the
         * writer it builds per render.
         */
        public Options withPlainTextAttributes(String... names) {
            this.plainTextAttributes = names;
            return this;
        }

        /**
         * {@code VelocityViewFactory.addTrustedResourceOrigins(...)}: R9's CDN escape hatch,
         * exercised on the real render path.
         *
         * <p>Here for the same reason {@link #withPlainTextAttributes(String...)} is. {@code
         * UrlSinkTest} shows that a {@link com.webkreator.qlue.view.Canoe} constructed with an
         * allowlist admits the host, which is a claim about the encoder; what only this path can show
         * is that the <em>factory</em> carries its configured origins into the writer it builds per
         * render, which is the half an application's configuration actually depends on.
         */
        public Options withTrustedResourceOrigins(String... origins) {
            this.trustedResourceOrigins = origins;
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
        // template path, with resource.loader.class.class carrying the value
        // ClasspathVelocityViewFactory overrides it to. Since R22 the base class sets that key
        // itself, to the plain ClasspathResourceLoader -- before then it set no value at all and
        // Velocity 2.4.1, which ships no default, failed initialisation ("Unable to find
        // 'resource.loader.class.class' specification in configuration"). Which loader the subclass
        // names depends on resource.loader.file.cache, which is set only when the application
        // declares a priority template path -- so the non-caching loader is what a default Qlue
        // application runs, and it is what this engine uses.
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
     * The properties {@link ClasspathVelocityViewFactory} produces for a default application — the
     * base class's, plus its own override of {@code resource.loader.class.class}.
     *
     * <p>Exposed for the same reason {@link #defaultVelocityProperties()} is, and so that a test can
     * state what the shipped subclass runs now that R22 gives the base class a working default of
     * its own: the two answers differ, and the difference is the point.
     */
    public static java.util.Properties classpathFactoryVelocityProperties() {
        return new ClasspathVelocityViewFactory()
                .buildDefaultVelocityProperties(new CanoeProbePage().getApp());
    }

    /**
     * The plain-text attribute allowlist a factory ends up with when the application declares
     * {@code qlue.canoe.plainTextAttributes} in its Qlue properties.
     *
     * <p>Goes through {@code buildDefaultVelocityProperties()} because that is where every shipped
     * factory's {@code init()} reads the application's properties, so this exercises the real path
     * from a property file to a {@link com.webkreator.qlue.view.Canoe} rather than a copy of it.
     * Exposed here for the same reason {@link #defaultVelocityProperties()} is: the method is
     * reachable only from this package.
     *
     * @param propertyValue the property's value, or null for an application that does not set it
     */
    public static java.util.Set<String> plainTextAttributesFromProperty(String propertyValue) {
        com.webkreator.qlue.QlueApplication app = new CanoeProbePage().getApp();
        if (propertyValue != null) {
            app.getProperties().setProperty(
                    VelocityViewFactory.QLUE_CANOE_PLAIN_TEXT_ATTRIBUTES, propertyValue);
        }

        VelocityViewFactory factory = new ProbeViewFactory();
        factory.buildDefaultVelocityProperties(app);
        return factory.getPlainTextAttributes();
    }

    /**
     * The trusted resource origins a factory ends up with when the application declares
     * {@code qlue.canoe.trustedResourceOrigins} in its Qlue properties.
     *
     * <p>The R9 twin of {@link #plainTextAttributesFromProperty(String)}, and it goes through
     * {@code buildDefaultVelocityProperties()} for the same reason: that is where every shipped
     * factory's {@code init()} reads the application's properties, so a malformed origin throws from
     * {@code init()} rather than silently matching nothing on every page.
     *
     * @param propertyValue the property's value, or null for an application that does not set it
     */
    public static java.util.Set<String> trustedResourceOriginsFromProperty(String propertyValue) {
        com.webkreator.qlue.QlueApplication app = new CanoeProbePage().getApp();
        if (propertyValue != null) {
            app.getProperties().setProperty(
                    VelocityViewFactory.QLUE_CANOE_TRUSTED_RESOURCE_ORIGINS, propertyValue);
        }

        VelocityViewFactory factory = new ProbeViewFactory();
        factory.buildDefaultVelocityProperties(app);
        return factory.getTrustedResourceOrigins();
    }

    /**
     * Whether {@code useAutoEscaping} is assigned anywhere in {@code src/main} other than in
     * {@code setAutoEscaping()} — the executable form of the documentation's claim that auto-escaping
     * is on by default and can be turned off only by application code, never by configuration.
     *
     * <p>Stated as a source scan rather than as a behaviour because the claim is about the
     * <em>absence</em> of an input: no test can enumerate every property name that does not switch
     * the encoder off. What can be pinned is that the field has exactly one writer, which is what
     * makes the sentence in {@code README.md} and {@code qlue_user_guide.md} true; wire a property to
     * it and this fails, which is the point.
     *
     * @return every line outside {@code setAutoEscaping()} that assigns the field, which should be
     *         the declaration's own initialiser and nothing else
     */
    public static java.util.List<String> assignmentsToUseAutoEscapingOutsideTheSetter()
            throws java.io.IOException {
        java.nio.file.Path root = java.nio.file.Path.of("src/main/java");
        java.util.List<String> offenders = new ArrayList<>();
        try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(root)) {
            for (java.nio.file.Path file : (Iterable<java.nio.file.Path>)
                    files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                boolean inSetter = false;
                for (String line : java.nio.file.Files.readAllLines(file,
                        java.nio.charset.StandardCharsets.UTF_8)) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("public void setAutoEscaping(")) {
                        inSetter = true;
                        continue;
                    }
                    if (inSetter) {
                        // The setter is three lines long; its closing brace ends the window.
                        if (trimmed.equals("}")) {
                            inSetter = false;
                        }
                        continue;
                    }
                    if (trimmed.matches(".*\\buseAutoEscaping\\s*=[^=].*")) {
                        offenders.add(file.getFileName() + ": " + trimmed);
                    }
                }
            }
        }
        return offenders;
    }

    /**
     * A factory of the kind {@code render()} uses, configured with the given plain-text attribute
     * names, so that a test can hold <em>two of them at once</em> and show that neither can see the
     * other's allowlist.
     *
     * <p>{@link #render(String, Map, Options)} builds and discards a factory per call, which shows
     * that a configured factory and an unconfigured one behave differently but cannot show that two
     * live ones are isolated — and "per engine, never static" is the whole claim R5 makes about
     * where the allowlist lives. Two applications in one JVM widening each other's plain-text set
     * would be a security control changed by an unrelated deployment.
     *
     * @param plainTextAttributes names to widen this factory's allowlist with
     */
    public static VelocityViewFactory newFactory(String... plainTextAttributes) {
        VelocityViewFactory factory = new ProbeViewFactory();
        factory.addPlainTextAttributes(plainTextAttributes);
        return factory;
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

    /**
     * Publishes a fragment the probe's templates can {@code #parse} or {@code #include}.
     *
     * <p>Added by R21. A rejection inside a {@code #parse}d fragment is an ordinary production shape —
     * layouts are assembled that way — and it is the case that shows Velocity's wrapper message is not
     * one string but several: {@code "Exception rendering #parse(...)"} mentions neither
     * {@code "IO Error"} nor Canoe's prefix, so no test on the top-level message could have found it.
     */
    public static void publishFragment(String name, String content) {
        StringResourceLoader.getRepository(REPOSITORY_NAME).putStringResource(name, content);
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
        return render(templateText, model, options, new StringWriter());
    }

    /**
     * As {@link #render(String, Map, Options)}, rendering into a writer the caller supplies.
     *
     * <p>Exists for R21's second half. Whether {@code render()} <em>flushes</em> the partial output
     * is invisible through a plain {@link StringWriter} — Canoe has already written those characters
     * through to it before it rethrows, so the bytes are there either way — and it is the whole
     * difference between a response that can still be reset and one that is on the wire. A
     * {@link FlushCountingWriter} makes the flush observable; nothing else about the path changes.
     */
    public static Outcome render(String templateText, Map<String, Object> model, Options options,
                                 StringWriter response) {
        String name = "canoe-production-probe-" + TEMPLATE_COUNTER.incrementAndGet() + ".vm";
        StringResourceRepository repository = StringResourceLoader.getRepository(REPOSITORY_NAME);
        repository.putStringResource(name, templateText);

        Template template;
        try {
            template = ENGINE.getTemplate(name);
        } catch (Exception e) {
            throw new IllegalStateException("Could not build a Template from " + templateText, e);
        }
        return merge(template, model, options, response);
    }

    /**
     * A {@link StringWriter} that counts the calls {@code render()} makes to {@code flush()} and
     * {@code close()}, standing in for the servlet response writer whose flush commits the response.
     */
    public static final class FlushCountingWriter extends StringWriter {

        private int flushes;

        private int closes;

        @Override
        public void flush() {
            flushes++;
            super.flush();
        }

        @Override
        public void close() throws IOException {
            closes++;
            super.close();
        }

        /** How many times {@code render()} flushed. In production, "did the response commit". */
        public int flushes() {
            return flushes;
        }

        /** How many times {@code render()} closed. Production relies on it never doing so. */
        public int closes() {
            return closes;
        }
    }

    /**
     * Calls {@code VelocityViewFactory.discardPartialResponse(...)} — R21's response reset — on a
     * response the caller controls, in isolation from a render.
     *
     * <p>Exposed here because the method is {@code protected} on a class in this package. It is the
     * unit-sized view of the decision — reset when the response can still be reset, log and give up
     * when it cannot; {@link #renderThroughResponse(String, boolean)} is the whole-path view of it.
     */
    public static void discardPartialResponse(HttpServletResponse response,
                                             CanoeEncodingException error) {
        new ProbeViewFactory().discardPartialResponse(response, error);
    }

    /**
     * Drives {@code VelocityViewFactory.render(Page, VelocityView)} — the <strong>production entry
     * point</strong>, the two-argument one {@code VelocityView.render()} calls — rather than the
     * three-argument overload the rest of this class uses.
     *
     * <p><strong>Why it is worth the scaffolding.</strong> R21's recovery has two halves and only one
     * of them lives in the three-argument method. The other half — {@code response.resetBuffer()} —
     * is in this entry point, because this is the only place that knows the writer is the response's
     * own. Asserting it by reading the source would be asserting that somebody wrote the call, not
     * that calling it does anything; this drives the real method and observes the real effect.
     *
     * <p><strong>What is stubbed and what is not.</strong> The factory, the view, the template,
     * {@code merge()}, Canoe, the {@link Page}, the {@code QlueApplication} and the
     * {@link com.webkreator.qlue.TransactionContext} are all real — the context is built by its own
     * public constructor, so it generates a transaction id, opens a session and parses the proxy
     * headers exactly as a routed request does. Only the four servlet interfaces underneath it are
     * stubs, and they are {@link java.lang.reflect.Proxy} instances rather than mocks because
     * mockito's inline mock maker cannot instrument a class on this JDK (see
     * {@code ViewFactoryRenderTest}'s header). The response stub models the one behaviour the
     * assertion is about: {@code resetBuffer()} empties the body it has not sent, and
     * {@code isCommitted()} answers what the caller asked for.
     *
     * <p>Note that this also brings the {@code context != null} arm of the three-argument method
     * under test — {@code _ctx}, {@code _req}, {@code _res}, the nonce, the public session id and the
     * message source — which every other caller here skips, because a page that was never routed has
     * no context.
     *
     * @param templateText the template to render
     * @param committed    what the response should report from {@code isCommitted()}: false is the
     *                     ordinary case, true is the residual R21 cannot close
     */
    public static ResponseOutcome renderThroughResponse(String templateText, boolean committed) {
        String name = "canoe-production-entry-" + TEMPLATE_COUNTER.incrementAndGet() + ".vm";
        StringResourceLoader.getRepository(REPOSITORY_NAME).putStringResource(name, templateText);

        Template template;
        try {
            template = ENGINE.getTemplate(name);
        } catch (Exception e) {
            throw new IllegalStateException("Could not build a Template from " + templateText, e);
        }

        StubResponse response = new StubResponse(committed);
        CanoeProbePage page = new CanoeProbePage();

        try {
            page.setTransactionContext(new TransactionContext(
                    page.getApp(), null, null, stubRequest(), response.asServletResponse()));
        } catch (ServletException e) {
            throw new IllegalStateException("Could not build a TransactionContext", e);
        }

        VelocityViewFactory factory = new ProbeViewFactory();
        VelocityView view = new VelocityView(factory, template);

        try {
            factory.render(page, view);
            return new ResponseOutcome(response, null);
        } catch (Exception e) {
            return new ResponseOutcome(response, e);
        }
    }

    /**
     * A container's response, reduced to the two things R21 depends on: a writer that accumulates a
     * body, and a buffer that {@code resetBuffer()} throws away while the response is uncommitted.
     */
    public static final class StubResponse {

        private final boolean committed;

        private final StringWriter buffer = new StringWriter();

        private final PrintWriter writer = new PrintWriter(buffer);

        private final List<String> calls = new ArrayList<>();

        StubResponse(boolean committed) {
            this.committed = committed;
        }

        HttpServletResponse asServletResponse() {
            return (HttpServletResponse) Proxy.newProxyInstance(
                    StubResponse.class.getClassLoader(),
                    new Class<?>[]{HttpServletResponse.class},
                    (proxy, method, args) -> {
                        calls.add(method.getName());
                        switch (method.getName()) {
                            case "getWriter":
                                return writer;
                            case "isCommitted":
                                return committed;
                            case "resetBuffer":
                                if (committed) {
                                    throw new IllegalStateException(
                                            "the response has already been committed");
                                }
                                buffer.getBuffer().setLength(0);
                                return null;
                            default:
                                return defaultValue(method.getReturnType());
                        }
                    });
        }

        /** The body the client would receive. Empty once {@code resetBuffer()} has run. */
        public String body() {
            writer.flush();
            return buffer.toString();
        }

        /** Every method the factory called on the response, in order. */
        public List<String> calls() {
            return calls;
        }
    }

    /** What {@code render(Page, VelocityView)} did, and what it left in the response. */
    public static final class ResponseOutcome {

        private final StubResponse response;

        private final Exception escaped;

        ResponseOutcome(StubResponse response, Exception escaped) {
            this.response = response;
            this.escaped = escaped;
        }

        public String body() {
            return response.body();
        }

        public List<String> calls() {
            return response.calls();
        }

        public boolean exceptionEscaped() {
            return escaped != null;
        }

        public Exception escaped() {
            return escaped;
        }

        public CanoeEncodingException encodingError() {
            return CanoeEncodingException.findIn(escaped);
        }

        @Override
        public String toString() {
            return "ResponseOutcome[body=\"" + response.body() + "\", calls=" + response.calls()
                    + ", escaped=" + escaped + "]";
        }
    }

    /**
     * The request a routed transaction would carry, reduced to what
     * {@code TransactionContext}'s constructor reads: a remote address, a URI, no query string, no
     * content type, and a session that remembers what is put in it.
     */
    private static HttpServletRequest stubRequest() {
        Map<String, Object> sessionAttributes = new LinkedHashMap<>();

        HttpSession session = (HttpSession) Proxy.newProxyInstance(
                ProductionRenderProbe.class.getClassLoader(),
                new Class<?>[]{HttpSession.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getAttribute":
                            return sessionAttributes.get((String) args[0]);
                        case "setAttribute":
                            sessionAttributes.put((String) args[0], args[1]);
                            return null;
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });

        return (HttpServletRequest) Proxy.newProxyInstance(
                ProductionRenderProbe.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getSession":
                            return session;
                        case "getRemoteAddr":
                            return "127.0.0.1";
                        case "getRequestURI":
                            return "/canoe-production-entry";
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
    }

    /**
     * The boxed zero of a primitive return type, or null for a reference one. Built through a
     * one-element array so that every width is right — an {@code Integer} handed back for a
     * {@code long} method is a {@code ClassCastException} inside the proxy, which is a confusing way
     * for a test to fail.
     */
    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive() || returnType == void.class) {
            return null;
        }
        return Array.get(Array.newInstance(returnType, 1), 0);
    }

    /**
     * The part that is production code: build the view, build the page, and call
     * {@code VelocityViewFactory.render(page, view, writer)}.
     */
    private static Outcome merge(Template template, Map<String, Object> model, Options options) {
        return merge(template, model, options, new StringWriter());
    }

    private static Outcome merge(Template template, Map<String, Object> model, Options options,
                                 StringWriter response) {
        VelocityViewFactory factory = new ProbeViewFactory();
        factory.setAutoEscaping(options.autoEscaping);
        factory.addPlainTextAttributes(options.plainTextAttributes);
        factory.addTrustedResourceOrigins(options.trustedResourceOrigins);

        VelocityView view = new VelocityView(factory, template);

        Page page = new CanoeProbePage(options.directOutput);
        page.getModel().putAll(model);

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
         * The {@link CanoeEncodingException} in whatever escaped, or null if what escaped was not an
         * encoding error at all.
         *
         * <p>Deliberately a search of the cause chain rather than a cast, because that is the claim:
         * an application can find Canoe's exception no matter what wrapped it. After R21 the escaping
         * exception <em>is</em> the {@link CanoeEncodingException}, so the search terminates at depth
         * zero; before R21 it was reachable at depth one and nothing looked for it.
         */
        public CanoeEncodingException encodingError() {
            return CanoeEncodingException.findIn(escaped);
        }

        /**
         * True when the response carries the {@code [Encoding Error]} marker.
         *
         * <p>Before R21 this was the observation that would flip when F13 was fixed: the marker was
         * what the unreachable branch meant to append. R21 fixed it in the other direction — the
         * branch is deleted, because appending a marker to a response that ends inside an attribute
         * list is not a recovery — so this now reads as a standing assertion that no marker was
         * reintroduced, on any path, ever.
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
