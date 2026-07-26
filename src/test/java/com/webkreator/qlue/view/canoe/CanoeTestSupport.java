package com.webkreator.qlue.view.canoe;

import com.webkreator.qlue.util.HtmlEncoder;
import com.webkreator.qlue.view.Canoe;
import com.webkreator.qlue.view.CanoeEncodingException;
import com.webkreator.qlue.view.velocity.CanoeReferenceInsertionHandler;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.app.event.EventCartridge;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.resource.loader.StringResourceLoader;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.StringWriter;
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;

/**
 * Harness for the Canoe test suite.
 *
 * <p>Canoe does not need the servlet stack. {@link com.webkreator.qlue.view.velocity.VelocityViewFactory#render}
 * requires a {@code Page}, a {@code QlueApplication} and a {@code TransactionContext}, none of which
 * the encoder itself touches: all it needs is a {@link VelocityEngine}, a {@link VelocityContext}
 * carrying a {@link CanoeReferenceInsertionHandler}, and a {@link Canoe} to merge into. Templates are
 * passed to {@link VelocityEngine#evaluate} as strings, so the bulk of the suite needs no {@code .vm}
 * files and no mocks.
 *
 * <p>{@code ViewFactoryRenderTest} (T20) exercises the real production path and asserts that it
 * agrees byte-for-byte with this harness, which is what justifies the shortcut everywhere else.
 *
 * <p>The engine mirrors {@code VelocityViewFactory.buildDefaultVelocityProperties()}, including
 * {@code runtime.strict_mode.enable}, which changes how undefined references behave and would
 * otherwise be a silent source of divergence. Two production settings are omitted deliberately:
 * the {@code class} resource loader, which needs a classpath layout this harness has no use for,
 * and the {@code file} loader, which only exists when the application sets a priority template
 * path. {@code ViewFactoryRenderTest} (T20) covers both by going through the real factory.
 */
public final class CanoeTestSupport {

    /**
     * The name Velocity binds the encoding tool under. References prefixed with {@code $_x.} bypass
     * Canoe entirely; see {@link CanoeReferenceInsertionHandler}.
     */
    public static final String ENCODING_TOOL_NAME = CanoeReferenceInsertionHandler.SAFE_REFERENCE_NAME;

    /** The repository {@code #parse} and {@code #include} resolve against; see publishFragment. */
    public static final String STRING_REPOSITORY_NAME = "CANOE_TEST_STRING_REPOSITORY";

    /** The reference name every single-payload template in the corpus binds its payload to. */
    public static final String PAYLOAD_REFERENCE = "data";

    private static final VelocityEngine ENGINE = createEngine();

    private CanoeTestSupport() {
    }

    private static VelocityEngine createEngine() {
        Properties properties = new Properties();

        // Mirrors VelocityViewFactory.buildDefaultVelocityProperties(). Resource-loader and
        // macro-library settings are omitted because evaluate() takes templates as strings.
        properties.setProperty("resource.default_encoding", "UTF-8");
        properties.setProperty("directive.set.null.allowed", "true");
        properties.setProperty("resource.manager.log_when_found", "false");
        properties.setProperty("velocimacro.inline.local_scope", "true");
        properties.setProperty("velocimacro.arguments.strict", "true");
        properties.setProperty("context.scope_control.macro", "true");
        properties.setProperty("runtime.strict_mode.enable", "true");
        properties.setProperty("runtime.strict_math", "true");
        properties.setProperty(RuntimeConstants.VM_PERM_ALLOW_INLINE, "true");
        properties.setProperty(RuntimeConstants.VM_PERM_ALLOW_INLINE_REPLACE_GLOBAL, "true");
        properties.setProperty(RuntimeConstants.VM_LIBRARY, "");

        // A string resource loader, so that #parse and #include have somewhere to resolve against.
        // Production also registers a class loader, which needs a classpath layout this harness has
        // no use for; ViewFactoryRenderTest covers that path.
        properties.setProperty(RuntimeConstants.RESOURCE_LOADERS, "string");
        properties.setProperty("resource.loader.string.class",
                "org.apache.velocity.runtime.resource.loader.StringResourceLoader");
        properties.setProperty("resource.loader.string.repository.name",
                STRING_REPOSITORY_NAME);

        VelocityEngine engine = new VelocityEngine(properties);
        engine.init();
        return engine;
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    /**
     * Renders a template with no references bound.
     */
    public static RenderResult render(String template) {
        return render(template, new LinkedHashMap<>());
    }

    /**
     * Renders a template with a single payload bound to {@code $data}.
     */
    public static RenderResult render(String template, String payload) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put(PAYLOAD_REFERENCE, payload);
        return render(template, model);
    }

    /**
     * Renders a template against an explicit model, with default options.
     */
    public static RenderResult render(String template, Map<String, Object> model) {
        return render(template, model, RenderOptions.defaults());
    }

    /**
     * Renders a template against an explicit model.
     *
     * <p>Never throws. An encoding error is reported through {@link RenderResult#isError()} together
     * with whatever output reached the writer before the error.
     *
     * <p>Note what a real caller observes, which is <em>not</em> quite the same thing.
     * {@code VelocityViewFactory.render()} used to intend to swallow encoding errors and append
     * {@code [Encoding Error]}, and tested {@code startsWith(Canoe.ERROR_PREFIX)} on the top-level
     * exception to decide — a test Velocity's wrapper made permanently false (F13). R21 replaced it
     * with the type in the cause chain and chose the other recovery: the request fails outright, with
     * the {@code CanoeEncodingException} unwrapped and the partial output left unflushed. This class
     * deliberately does <em>not</em> model the factory's decision either way: a predicate here would
     * be a copy of the check under test and would keep answering the same after the check changed.
     * {@code ProductionRenderProbe} drives the real {@code render()} instead, and
     * {@code CanoeRobustnessTest.everyErrorCanoeRaisesEscapesRenderAsACatchableCanoeEncodingException}
     * asserts on what it observes.
     */
    public static RenderResult render(String template, Map<String, Object> model,
                                      RenderOptions options) {
        return render(template, model, options, Canoe::new);
    }

    /**
     * As {@link #render(String, Map, RenderOptions)}, but with the {@link Canoe} supplied by the
     * caller.
     *
     * <p>Present for {@code ParserSteeringTest} (T23), whose property is about the sequence of
     * {@link Canoe#currentContext()} values observed <em>at each reference position</em> rather than
     * about the output bytes. Only {@code CanoeReferenceInsertionHandler} knows where those positions
     * are, and the only way to see what it saw is to record the calls it makes — which needs a
     * {@code Canoe} subclass in the writer's place.
     *
     * <p>The alternative was for the property test to build its own {@link VelocityEngine}, and the
     * whole reason this class exists is that a second engine configuration drifts from the first.
     */
    public static RenderResult render(String template, Map<String, Object> model,
                                      RenderOptions options,
                                      Function<Writer, ? extends Canoe> canoeFactory) {
        StringWriter sink = new StringWriter();
        Canoe canoe = canoeFactory.apply(sink);

        VelocityContext context = new VelocityContext();
        for (Map.Entry<String, Object> entry : model.entrySet()) {
            context.put(entry.getKey(), entry.getValue());
        }
        if (options.bindEncodingTool && !model.containsKey(ENCODING_TOOL_NAME)) {
            context.put(ENCODING_TOOL_NAME, new HtmlEncoder());
        }

        if (options.autoEscaping) {
            EventCartridge cartridge = new EventCartridge();
            cartridge.addReferenceInsertionEventHandler(new CanoeReferenceInsertionHandler(canoe));
            cartridge.attachToContext(context);
        }

        try {
            ENGINE.evaluate(context, canoe, "canoe-test", template);
            canoe.flush();
            return new RenderResult(sink.toString(), null, null, canoe.currentContext());
        } catch (Exception e) {
            String encodingError = findEncodingError(e);
            if (encodingError == null) {
                throw new IllegalStateException(
                        "Template failed for a reason unrelated to Canoe encoding: " + template, e);
            }
            return new RenderResult(sink.toString(), encodingError, e, canoe.currentContext());
        }
    }

    /**
     * Which parts of the production wiring to switch on. Production binds {@code $_x} only when the
     * page calls {@code allowDirectOutput()}, and attaches the reference-insertion handler only when
     * {@code useAutoEscaping} is set, so both need to be controllable to test either path.
     */
    public static final class RenderOptions {

        private boolean autoEscaping = true;
        private boolean bindEncodingTool = true;

        public static RenderOptions defaults() {
            return new RenderOptions();
        }

        /** Detaches the event cartridge, as {@code setAutoEscaping(false)} does. */
        public RenderOptions withoutAutoEscaping() {
            this.autoEscaping = false;
            return this;
        }

        /** Leaves {@code $_x} unbound, as a page that has not called allowDirectOutput() would. */
        public RenderOptions withoutEncodingTool() {
            this.bindEncodingTool = false;
            return this;
        }
    }

    /**
     * Walks the cause chain looking for the exception Canoe raises. Velocity wraps it in its own
     * exception types and the message varies with whichever directive was rendering — {@code "IO
     * Error in writer: ..."}, {@code "Exception rendering #parse(...)"}, {@code "VelocimacroProxy
     * .render() : ..."} — so matching on the top-level message alone misses most of them.
     *
     * <p><strong>The match is on the type</strong> ({@link CanoeEncodingException}, R21), which is
     * strictly stronger than the {@code IOException}-plus-message-prefix test it replaces and no
     * looser: every one of those exceptions carries the prefix, and nothing else can carry the type.
     * Strictness matters more here than in most places. The corpus is a catalogue of hostile strings
     * that Velocity quotes back in its own parse and method-invocation errors; a loose match would
     * eventually classify one of those as an encoding error and record a bogus REJECTED verdict,
     * which is a ledger entry nobody can reproduce.
     *
     * <p>The depth bound guards against a cause cycle. {@code Throwable.getCause()} returns null for
     * a self-cycle, but an overridden {@code getCause()} can produce a two-cycle that would spin
     * forever. It is the same walk {@link CanoeEncodingException#findIn(Throwable)} performs, and
     * deliberately a separate copy of it: this one has to answer with the <em>message</em> so that
     * the rest of the suite can assert coordinates, and a harness that called the production helper
     * would stop being able to tell the reader what it saw.
     */
    private static String findEncodingError(Throwable t) {
        Throwable current = t;
        for (int depth = 0; current != null && depth < 32; depth++) {
            if (current instanceof CanoeEncodingException) {
                return current.getMessage();
            }
            current = current.getCause();
        }
        return null;
    }

    /**
     * Publishes a template fragment that {@code #parse} and {@code #include} can resolve.
     *
     * <p>Goes through this class rather than through {@link StringResourceLoader} directly because
     * the repository does not exist until the engine has been initialised, and the engine is
     * initialised lazily by the first render. A caller reaching for the repository in a
     * {@code @BeforeAll} gets a null back, which is a confusing failure a long way from its cause.
     */
    public static void publishFragment(String name, String content) {
        // Calling any method on this class initialises it, and ENGINE's initialiser is what creates
        // the repository. That ordering is the whole reason this method exists.
        StringResourceLoader.getRepository(STRING_REPOSITORY_NAME).putStringResource(name, content);
    }

    // ------------------------------------------------------------------
    // Direct state machine probes
    // ------------------------------------------------------------------

    /**
     * Writes literal template text into a bare {@link Canoe} and returns the context a reference at
     * that position would be encoded for. No Velocity involved; this is the cheapest way to test the
     * state machine.
     *
     * @throws AssertionError if the text raises an encoding error
     */
    public static int contextAfter(String templatePrefix) {
        WriteResult result = write(templatePrefix);
        if (result.isError()) {
            throw new AssertionError("Expected " + quote(templatePrefix)
                    + " to parse cleanly, but Canoe raised: " + result.errorMessage());
        }
        return result.context();
    }

    /**
     * Writes literal template text into a bare {@link Canoe}, tolerating encoding errors.
     */
    public static WriteResult write(String templateText) {
        StringWriter sink = new StringWriter();
        Canoe canoe = new Canoe(sink);
        try {
            canoe.write(templateText);
            canoe.flush();
            return new WriteResult(sink.toString(), null, null, canoe.currentContext());
        } catch (Exception e) {
            String encodingError = findEncodingError(e);
            if (encodingError == null) {
                throw new IllegalStateException("Unexpected failure writing " + quote(templateText), e);
            }
            return new WriteResult(sink.toString(), encodingError, e, canoe.currentContext());
        }
    }

    /**
     * Renders a value as Canoe would at the given context. Thin wrapper over the static dispatcher,
     * present so tests read in terms of context names rather than integers.
     */
    public static String encodeFor(String input, int context) {
        return Canoe.encode(input, context);
    }

    // ------------------------------------------------------------------
    // Context naming
    // ------------------------------------------------------------------

    /**
     * A readable name for one of Canoe's {@code CTX_*} constants, so a failure message says
     * "expected CTX_JS but was CTX_HTML_ATTR" rather than "expected 3 but was 2".
     */
    public static String contextName(int context) {
        switch (context) {
            case Canoe.CTX_SUPPRESS:
                return "CTX_SUPPRESS";
            case Canoe.CTX_HTML:
                return "CTX_HTML";
            case Canoe.CTX_HTML_ATTR:
                return "CTX_HTML_ATTR";
            case Canoe.CTX_JS:
                return "CTX_JS";
            case Canoe.CTX_URI:
                return "CTX_URI";
            case Canoe.CTX_URI_RESOURCE:
                return "CTX_URI_RESOURCE";
            // There is no CTX_CSS since R14 (F21); value 5 is an unused gap.
            default:
                return "CTX_UNKNOWN(" + context + ")";
        }
    }

    // ------------------------------------------------------------------
    // Assertion helpers
    // ------------------------------------------------------------------

    /**
     * The property the whole of Canoe's body-context safety rests on: no encoder can emit a raw
     * {@code <}, so attacker data can never open a tag and can never steer the state machine.
     *
     * <p>ParserSteeringTest (T23) states the stronger form of this property.
     */
    public static void assertCannotOpenTag(String encoded) {
        if (encoded.indexOf('<') >= 0) {
            throw new AssertionError("Encoded output contains a raw '<', which can open a tag: "
                    + quote(encoded));
        }
    }

    public static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '"':
                    sb.append("\\\"");
                    break;
                default:
                    if (c < 0x20 || c == 0x7f) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Results
    // ------------------------------------------------------------------

    /**
     * The outcome of writing literal text into a bare {@link Canoe}.
     */
    public static class WriteResult {

        private final String output;
        private final String errorMessage;
        private final Throwable thrown;
        private final int context;

        WriteResult(String output, String errorMessage, Throwable thrown, int context) {
            this.output = output;
            this.errorMessage = errorMessage;
            this.thrown = thrown;
            this.context = context;
        }

        /** Everything that reached the underlying writer, including any partial output before an error. */
        public String output() {
            return output;
        }

        /** True when Canoe rejected the input. */
        public boolean isError() {
            return errorMessage != null;
        }

        /** The encoding error message, from {@code "Encoding Error: "} onwards; null when clean. */
        public String errorMessage() {
            return errorMessage;
        }

        /** The exception as Velocity threw it, before the cause chain was walked. */
        public Throwable thrown() {
            return thrown;
        }

        /** The context a reference appended at this position would be encoded for. */
        public int context() {
            return context;
        }

        public String contextName() {
            return CanoeTestSupport.contextName(context);
        }
    }

    /**
     * The outcome of merging a template. Adds DOM-level accessors, which is where most assertions
     * should live: a string assertion tests what Canoe emitted, whereas parsing the output first
     * tests what a browser will hand on to the JavaScript, CSS or URL parser.
     */
    public static class RenderResult extends WriteResult {

        private Document parsed;

        RenderResult(String output, String errorMessage, Throwable thrown, int context) {
            super(output, errorMessage, thrown, context);
        }

        /** The rendered output parsed by an HTML parser, with character references decoded. */
        public Document dom() {
            if (parsed == null) {
                parsed = Jsoup.parse(output());
            }
            return parsed;
        }

        /**
         * The value of an attribute <em>after</em> character-reference decoding — that is, the string
         * the browser hands to the JavaScript, CSS or URL parser.
         *
         * <p>This is the distinction the security review turns on. {@code html()} converts
         * {@code ');alert(1)} to {@code &#39;&#41;;alert&#40;1&#41;}, which a naive string assertion
         * would call safe; the HTML parser decodes it back to the attacker's original characters
         * before the value is compiled as JavaScript.
         */
        public String decodedAttr(String cssSelector, String attributeName) {
            Element element = dom().selectFirst(cssSelector);
            if (element == null) {
                throw new AssertionError("No element matched " + quote(cssSelector)
                        + " in rendered output: " + quote(output()));
            }
            return element.attr(attributeName);
        }

        /** The decoded text content of the first element matching the selector. */
        public String decodedText(String cssSelector) {
            Element element = dom().selectFirst(cssSelector);
            if (element == null) {
                throw new AssertionError("No element matched " + quote(cssSelector)
                        + " in rendered output: " + quote(output()));
            }
            return element.text();
        }

        @Override
        public String toString() {
            return isError()
                    ? "RenderResult[error=" + errorMessage() + ", partial=" + quote(output()) + "]"
                    : "RenderResult[" + quote(output()) + ", context=" + contextName() + "]";
        }
    }
}
