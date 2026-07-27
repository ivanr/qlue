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

import com.webkreator.qlue.Page;
import com.webkreator.qlue.QlueApplication;
import com.webkreator.qlue.QlueSession;
import com.webkreator.qlue.TransactionContext;
import com.webkreator.qlue.view.Canoe;
import com.webkreator.qlue.view.CanoeEncodingException;
import com.webkreator.qlue.view.ViewFactory;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.app.event.EventCartridge;
import org.apache.velocity.runtime.RuntimeConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletResponse;
import java.io.Writer;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Base class for the view implementation that uses Velocity. Needs subclassing
 * to provide initialization and decide where to look for template files.
 */
public abstract class VelocityViewFactory implements ViewFactory {

    public static final String QLUE_STRING_RESOURCE_LOADER_KEY = "QLUE_STRING_RESOURCE_LOADER";

    public static final String QLUE_RAW_VELOCITY_CONFIG_PREFIX = "qlue.velocity.raw.";

    public static final String QLUE_VELOCITY_MAX_LOG_LEVEL = "qlue.velocity.maxLogLevel";

    /**
     * Qlue property naming additional attributes whose values Canoe should treat as plain text,
     * separated by commas or whitespace, e.g.
     * {@code qlue.canoe.plainTextAttributes = my-widget-config, hx-target}.
     *
     * <p>Canoe suppresses a reference in any attribute name it does not recognise, which is
     * fail-closed and is right; without a way to widen the allowlist it is also how a security
     * control gets switched off in production, because the developer's remaining option is
     * {@code $_x.asis()} and that disables Canoe for the value entirely. Names are validated by
     * {@link Canoe#normalisePlainTextAttributeNames(java.util.Collection)}, which refuses every name
     * whose suppression is what makes it safe, so the property can widen the plain-text set and
     * cannot turn a markup or policy attribute back into an encoded one.
     */
    public static final String QLUE_CANOE_PLAIN_TEXT_ATTRIBUTES = "qlue.canoe.plainTextAttributes";

    /**
     * Qlue property naming origins a resource-loading URL sink may load from, beyond the page's own,
     * separated by commas or whitespace, e.g.
     * {@code qlue.canoe.trustedResourceOrigins = cdn.example.com, https://static.example.com}.
     *
     * <p>Canoe rejects an off-origin or protocol-relative value on {@code <script src>},
     * {@code <iframe src>}, {@code <object data>}, {@code <embed src>}, {@code <link href>} and
     * {@code <base href>} by default; this is the CDN escape hatch. An entry is a host
     * ({@code cdn.example.com}) or an origin
     * ({@code https://cdn.example.com}, optionally with a {@code :port}), validated by
     * {@link com.webkreator.qlue.util.HtmlEncoder#parseTrustedOrigins(java.util.Collection)}, so a
     * malformed origin fails at startup rather than silently matching nothing.
     */
    public static final String QLUE_CANOE_TRUSTED_RESOURCE_ORIGINS = "qlue.canoe.trustedResourceOrigins";

    protected static Logger log = LoggerFactory.getLogger(VelocityViewFactory.class);

    protected String inputEncoding = "UTF-8";

    protected String outputEncoding = "UTF-8";

    protected String OUTPUT_ENCODING = "resource.default_encoding";

    protected String logChute = "com.webkreator.qlue.view.velocity.SLF4JLogChute";

    protected VelocityEngine velocityEngine;

    protected boolean useAutoEscaping = true;

    protected String macroPath = "";

    /**
     * The application's additions to Canoe's plain-text attribute allowlist.
     *
     * <p>Held per factory, and therefore per engine, rather than in a static on {@link Canoe}: two
     * applications in one JVM must not be able to widen each other's allowlist, and nothing should
     * be able to widen anybody's after the pages have started rendering. The set is handed to every
     * {@link Canoe} the factory constructs, which is one per render.
     */
    protected Set<String> plainTextAttributes = Collections.emptySet();

    /**
     * The origins a resource-loading URL sink may load from, on top of the page's own — the CDN
     * allowlist.
     *
     * <p>Held per factory, and therefore per engine, for the same reason as {@link
     * #plainTextAttributes}: two applications in one JVM must not widen each other's, and nothing
     * should widen anybody's after rendering has started. The set is handed to every {@link Canoe} the
     * factory constructs, one per render, where it is parsed and validated.
     */
    protected Set<String> trustedResourceOrigins = Collections.emptySet();

    protected Properties buildDefaultVelocityProperties(QlueApplication qlueApp) {
        Properties properties = new Properties();

        properties.setProperty(RuntimeConstants.INPUT_ENCODING, inputEncoding);

        // OUTPUT_ENCODING no longer exists in Velocity 2.3?
        // properties.setProperty(RuntimeConstants.OUTPUT_ENCODING, outputEncoding);
        properties.setProperty(OUTPUT_ENCODING, outputEncoding);

        properties.setProperty(RuntimeConstants.RESOURCE_LOADERS, "class,string");

        properties.setProperty("resource.loader.string.class", "org.apache.velocity.runtime.resource.loader.StringResourceLoader");
        properties.setProperty("resource.loader.string.repository.name", QLUE_STRING_RESOURCE_LOADER_KEY);

        // The class loader is declared above and its caching is configured below, so it also has to
        // say which loader it is: Velocity 2.4.1 ships no default for this key and
        // ResourceManagerImpl.initialize() throws without it. The plain ClasspathResourceLoader is
        // the default because the class loader reads from the classpath; a subclass that wants the
        // reloading variant overrides it, as ClasspathVelocityViewFactory does.
        properties.setProperty("resource.loader.class.class",
                "org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader");

        if (qlueApp.getPriorityTemplatePath() != null) {
            properties.setProperty(RuntimeConstants.RESOURCE_LOADERS, "file,class,string");
            properties.setProperty("resource.loader.file.class", "org.apache.velocity.runtime.resource.loader.FileResourceLoader");
            properties.setProperty("resource.loader.file.cache", "false");
            properties.setProperty("resource.loader.file.path", qlueApp.getPriorityTemplatePath());
        }

        properties.setProperty(RuntimeConstants.VM_LIBRARY, macroPath);
        properties.setProperty(RuntimeConstants.VM_LIBRARY_AUTORELOAD, "true");
        properties.setProperty(RuntimeConstants.VM_PERM_ALLOW_INLINE, "true");
        properties.setProperty(RuntimeConstants.VM_PERM_ALLOW_INLINE_REPLACE_GLOBAL, "true");

        if (qlueApp.getProperty("qlue.velocity.cache") != null) {
            properties.setProperty("resource.loader.class.cache", qlueApp.getProperty("qlue.velocity.cache"));
            properties.setProperty("resource.loader.class.modification_check_interval", "0");
        } else {
            properties.setProperty("resource.loader.class.cache", "false");
        }

        if (qlueApp.getProperty("qlue.velocity.modificationCheckInterval") != null) {
            properties.setProperty(
                    "resource.loader.class.modification_check_interval",
                    qlueApp.getProperty("qlue.velocity.modificationCheckInterval"));
        }

        properties.setProperty("directive.set.null.allowed", "true");
        properties.setProperty("resource.manager.log_when_found", "false");
        properties.setProperty("velocimacro.inline.local_scope", "true");
        properties.setProperty("velocimacro.arguments.strict", "true");
        properties.setProperty("context.scope_control.macro", "true");
        properties.setProperty("runtime.strict_mode.enable", "true");
        properties.setProperty("runtime.strict_math", "true");

        // Pass-through the maxLogLevel setting into Velocity properties, for SLF4JLogChute to consume.
        String maxLogLevel = qlueApp.getProperty(VelocityViewFactory.QLUE_VELOCITY_MAX_LOG_LEVEL);
        if (maxLogLevel != null) {
            properties.setProperty(VelocityViewFactory.QLUE_VELOCITY_MAX_LOG_LEVEL, maxLogLevel);
        }

        // Widen Canoe's plain-text attribute allowlist, if the application asked for it. Read here
        // because this is the one method every shipped factory's init() calls with the application
        // in hand; a bad name throws from init() rather than dropping values at request time.
        addPlainTextAttributesFromProperty(qlueApp.getProperty(QLUE_CANOE_PLAIN_TEXT_ATTRIBUTES));
        addTrustedResourceOriginsFromProperty(qlueApp.getProperty(QLUE_CANOE_TRUSTED_RESOURCE_ORIGINS));

        // Pass raw Velocity configuration from Qlue properties.
        Properties qlueProperties = qlueApp.getProperties();
        Enumeration e = qlueProperties.propertyNames();
        while (e.hasMoreElements()) {
            String key = (String) e.nextElement();
            if (key.startsWith(QLUE_RAW_VELOCITY_CONFIG_PREFIX)) {
                properties.setProperty(
                        key.substring(QLUE_RAW_VELOCITY_CONFIG_PREFIX.length()),
                        qlueProperties.getProperty(key));
            }
        }

        return properties;
    }

    protected void tweakVelocityContext(VelocityContext velocityContext) {
        // Do nothing; intended for subclasses to override.
    }

    /**
     * Generate output, given page and view.
     *
     * <p>This is the production entry point — the only caller is {@code VelocityView.render()} — and
     * it is where the writer is known to be the response's own. That is what lets it do the one part
     * of the recovery the {@link #render(Page, VelocityView, Writer)} overload cannot: throw away
     * the half-written page instead of leaving it in the response buffer. See
     * {@link #discardPartialResponse(HttpServletResponse, CanoeEncodingException)}.
     *
     * @param page
     * @param view
     * @throws Exception
     */
    public void render(Page page, VelocityView view) throws Exception {
        HttpServletResponse response = page.getContext().getResponse();

        try {
            render(page, view, response.getWriter());
        } catch (CanoeEncodingException e) {
            discardPartialResponse(response, e);
            throw e;
        }
    }

    /**
     * Renders into an arbitrary writer.
     *
     * <p><strong>What happens when Canoe refuses.</strong> The request fails outright: the
     * {@link CanoeEncodingException} is pulled out of Velocity's wrapper and rethrown as itself, so a
     * caller can catch the type rather than pattern-match a message, and the partial output <em>is
     * not flushed</em>. Both halves are deliberate and both are argued in
     * {@link #discardPartialResponse(HttpServletResponse, CanoeEncodingException)}.
     *
     * <p>An exception that is not an encoding error is rethrown unchanged, and the output is
     * flushed. That is a scope boundary rather than a judgement: what a failed {@code #parse} or a
     * throwing model object should leave in the response is a different question with different
     * answers.
     */
    public void render(Page page, VelocityView view, Writer writer) throws Exception {
        final Map<String, Object> model = page.getModel();

        // Add common objects to the model

        for (QlueVelocityTool tool : page.getVelocityTools()) {
            tool.setPage(page);
            model.put(tool.getName(), tool);
        }

        // Normally, we don't want templates to be able to output
        // directly (without encoding) to responses, but some
        // pages will need to do that.
        if (page.allowDirectOutput()) {
            QlueVelocityTool tool = page.getApp().getEncodingTool();
            tool.setPage(page);
            model.put(tool.getName(), tool);
        }

        model.put("_app", page.getApp());
        model.put("_page", page);
        model.put("_i", page.getShadowInput());
        model.put("_cmd", page.getCommandObject());
        model.put("_errors", page.getErrors());

        TransactionContext context = page.getContext();
        if (context != null) {
            model.put("_ctx", context);
            model.put("_req", context.request);
            model.put("_res", context.response);
            model.put("_qlue_nonce", context.getNonce());
            model.put("_qlue_publicSessionId", context.getProperties().getProperty("_qlue_publicSessionId"));
            model.put("_qlue_userId", context.getUserId());

            QlueSession qlueSession = page.getQlueSession();
            if (qlueSession != null) {
                model.put("_sess", qlueSession);
                model.put("_m", page.getApp().getMessageSource(qlueSession.getLocale()));
                model.put("_secret", qlueSession.getSessionSecret());
            }
        }

        // Expose the public variables of the command object
        processPageFields(page.getCommandObject(), new FieldCallback() {
            public void processField(String fieldName, Object fieldValue) {
                if (fieldValue != null) {
                    model.put(fieldName, fieldValue);
                } else {
                    model.put(fieldName, null);
                }
            }
        });

        // An encoding error must not reach the client. Everything Canoe accepted before it gave up
        // has already been written through to this writer -- Canoe.write(char[],int,int) emits the
        // good characters and then rethrows -- so the only thing still under our control is whether
        // those bytes are committed, and flushing them would make the failure unrecoverable.
        boolean flushOutput = true;

        try {
            Canoe qlueWriter = new Canoe(writer, plainTextAttributes, trustedResourceOrigins);

            Template template = view.getTemplate();
            VelocityContext velocityContext = new VelocityContext(model);

            if (useAutoEscaping) {
                EventCartridge ec = new EventCartridge();
                ec.addReferenceInsertionEventHandler(new CanoeReferenceInsertionHandler(qlueWriter));
                ec.attachToContext(velocityContext);
            }

            tweakVelocityContext(velocityContext);

            template.merge(velocityContext, qlueWriter);
        } catch (Exception e) {
            CanoeEncodingException encodingError = CanoeEncodingException.findIn(e);
            if (encodingError == null) {
                throw e;
            }

            flushOutput = false;

            // Logged here rather than thrown on, because this is where the context is: the wrapper
            // names the template the error was raised in, and the wrapper is what the caller stops
            // seeing. What the caller gets instead is the exception it can do something with.
            // view.getTemplate() cannot be null here: an encoding error means merge() ran, and it
            // ran on that template. No guard, because a guard nothing can trigger is a branch
            // nothing can test.
            log.error("Canoe refused to render template {}: {}",
                    view.getTemplate().getName(), encodingError.getMessage(), e);

            throw encodingError;
        } finally {
            if (flushOutput) {
                writer.flush();
            }

            // We don't close the stream here in order
            // to enable Qlue to append to output as needed
            // (which is done in development mode)
        }
    }

    /**
     * Throws away whatever of the page has already been written, after Canoe has refused to render
     * the rest of it.
     *
     * <p><strong>What the response holds at this point.</strong> Canoe streams: it writes every
     * character it accepted straight through to the writer it wraps, and on the character it refuses
     * it writes the good ones and rethrows ({@code Canoe.write(char[], int, int)}). In production
     * that writer is {@code response.getWriter()}, so the prefix of the page is already in the
     * response buffer. For an error inside a tag that prefix ends mid-element — an unterminated
     * {@code <img}, with the browser still waiting for the {@code >}.
     *
     * <p><strong>Why the request fails outright.</strong> Three candidates were considered:
     *
     * <ul>
     *   <li><em>Append {@code [Encoding Error]} and serve the page.</em> The worst of the three: the
     *       marker lands inside an attribute list rather than in the document, the status code stays
     *       200, and the client gets a page that looks served and is not.</li>
     *   <li><em>Truncate to the last known-good tag boundary.</em> More honest than the marker and
     *       still not honest enough. A truncated document is one a browser renders happily — the
     *       reader sees a page missing its content and its footer, with a 200 and nothing in it
     *       saying so, which is silent corruption. It would also cost the streaming property: Canoe
     *       would have to buffer
     *       from the last boundary onwards to be able to rewind to it, and the bytes it must
     *       un-write are already past the writer in any case.</li>
     *   <li><em>Fail the request.</em> The rejections are template-authoring errors, not attacker
     *       input, so the person who needs to know is the developer and the honest report is an
     *       error. Qlue already knows how to turn one into a response: {@code QlueApplication.service()}
     *       catches it, offers it to {@code page.handleException()} first, and falls back to
     *       {@code sendError(500)}. Both of those want the response <strong>uncommitted</strong>, in
     *       two different ways — {@code sendError()} is skipped outright by an explicit
     *       {@code !response.isCommitted()} guard, and a {@code handleException()} view has no guard
     *       at all, so on a committed response it is simply appended to the broken page. That is what
     *       this method and the suppressed flush protect.</li>
     * </ul>
     *
     * <p><strong>Why not flushing is not enough on its own.</strong> Skipping the flush is what keeps
     * the response uncommitted, and that alone restores {@code sendError(500)}. But an application
     * whose page handles the exception renders its error view into the <em>same</em> response, and
     * without this reset that view would be appended to the fragment of the broken page.
     * {@code resetBuffer()} discards the unsent body and keeps the status and headers, so the error
     * view is the whole response.
     *
     * <p><strong>The residual, which is real.</strong> A servlet response commits when its buffer
     * fills, and the buffer is a few kilobytes — the servlet specification sets no size, Tomcat
     * defaults to 8KB and other containers pick their own — so a template that raises after that much
     * output has already put a fragment on the wire and nothing here can take it back. That case is
     * logged at error level and the exception still propagates; there is no recovery for it, and
     * there is no recovery for it under truncation either. It is a bound on the fix rather than a
     * defect in it, and {@code response.setBufferSize()} is the only lever an application has over
     * where the bound sits.
     *
     * @param response the response being rendered into. Never null from
     *                 {@link #render(Page, VelocityView)}, which has already dereferenced it to reach
     *                 the writer; the guard is for a subclass or a direct caller
     * @param error    the rejection, for the diagnostic
     */
    protected void discardPartialResponse(HttpServletResponse response,
                                          CanoeEncodingException error) {
        if (response == null) {
            return;
        }

        if (response.isCommitted()) {
            log.error("Canoe raised an encoding error after the response was committed, so the"
                            + " client has already received a partial page that cannot be"
                            + " withdrawn: {}", error.getMessage());
            return;
        }

        response.resetBuffer();
    }

    /**
     * Invokes callback for each of the object's fields.
     *
     * @param object
     * @param callback
     */
    void processPageFields(Object object, FieldCallback callback) {
        Field[] fields = object.getClass().getFields();
        if (fields == null) {
            return;
        }

        for (int i = 0; i < fields.length; i++) {
            Field field = fields[i];

            try {
                if (field.getName().startsWith("STATE_") == false) {
                    Object fieldValue = field.get(object);
                    callback.processField(field.getName(), fieldValue);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Callback interface.
     */
    interface FieldCallback {
        void processField(String fieldName, Object fieldValue);
    }

    public void setAutoEscaping(boolean b) {
        useAutoEscaping = b;
    }

    /**
     * Adds attribute names Canoe should treat as plain text, on top of its built-in allowlist.
     *
     * <p>Canoe suppresses a reference in any attribute name it
     * does not recognise, which is the right default and cannot be the whole answer: a page with
     * {@code <div my-widget-config="$x">} would otherwise lose the value silently, and the
     * developer's next move is {@code $_x.asis()}, which turns the encoder off for that value
     * completely. This is the smaller hammer, and it is deliberately narrow — the names are treated
     * as <em>plain text</em>, so the value still goes through {@code html()} and still cannot leave
     * the attribute it was written into.
     *
     * <p>Call before the first render; the set is copied into every {@link Canoe} the factory
     * constructs. Names whose suppression is what makes them safe are refused with an exception
     * rather than accepted and ignored — see
     * {@link Canoe#normalisePlainTextAttributeNames(Collection)}.
     *
     * @param names attribute names, in any case
     * @throws IllegalArgumentException if a name is not a legal attribute name, begins {@code on},
     *                                  or is one Canoe refuses to treat as text
     */
    public void addPlainTextAttributes(String... names) {
        addPlainTextAttributes(Arrays.asList(names));
    }

    /** The collection form of {@link #addPlainTextAttributes(String...)}. */
    public void addPlainTextAttributes(Collection<String> names) {
        Set<String> merged = new LinkedHashSet<>(plainTextAttributes);
        merged.addAll(Canoe.normalisePlainTextAttributeNames(names));
        plainTextAttributes = Collections.unmodifiableSet(merged);
    }

    /**
     * The property form of {@link #addPlainTextAttributes(String...)}: a comma- or
     * whitespace-separated list, or null for "the application said nothing".
     *
     * <p>Named apart from the varargs overload on purpose. {@code addPlainTextAttributes("x")}
     * would bind to a {@code (String)} overload rather than to the varargs one, so a caller adding
     * a single name would silently get the list parser; the two doing the same thing for that one
     * input is exactly the kind of coincidence that stops holding later.
     */
    protected void addPlainTextAttributesFromProperty(String namesFromProperty) {
        if (namesFromProperty == null) {
            return;
        }

        List<String> names = new ArrayList<>();
        for (String name : namesFromProperty.split("[,\\s]+")) {
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        addPlainTextAttributes(names);
    }

    /**
     * The application's additions to Canoe's plain-text attribute allowlist, normalised to lower
     * case. Never null; empty unless the application asked for something.
     */
    public Set<String> getPlainTextAttributes() {
        return plainTextAttributes;
    }

    /**
     * Adds origins a resource-loading URL sink may load from, on top of the page's own — the CDN
     * escape hatch.
     *
     * <p>By default Canoe rejects an off-origin or protocol-relative value on {@code <script src>},
     * {@code <iframe src>}, {@code <object data>}, {@code <embed src>}, {@code <link href>} and
     * {@code <base href>}, which is the right default;
     * without a way to widen it, an application that legitimately serves its scripts from a CDN has no
     * option but {@code $_x.asis()}, which turns Canoe off for that value. An entry is a host
     * ({@code cdn.example.com}) or an origin ({@code https://cdn.example.com}, optionally with a
     * {@code :port}).
     *
     * <p>Call before the first render; the set is validated and copied into every {@link Canoe} the
     * factory constructs. A malformed origin is refused with an exception rather than accepted and
     * ignored — see
     * {@link com.webkreator.qlue.util.HtmlEncoder#parseTrustedOrigins(Collection)}.
     *
     * @param origins hosts or origins, in any case
     * @throws IllegalArgumentException if an entry is not a legal host or origin
     */
    public void addTrustedResourceOrigins(String... origins) {
        addTrustedResourceOrigins(Arrays.asList(origins));
    }

    /** The collection form of {@link #addTrustedResourceOrigins(String...)}. */
    public void addTrustedResourceOrigins(Collection<String> origins) {
        // Parse to validate up front, exactly as addPlainTextAttributes normalises; keep the raw
        // strings, because Canoe parses them again at construction (its constructor is public API and
        // does not trust the caller).
        com.webkreator.qlue.util.HtmlEncoder.parseTrustedOrigins(origins);
        Set<String> merged = new LinkedHashSet<>(trustedResourceOrigins);
        for (String origin : origins) {
            if (origin != null && !origin.trim().isEmpty()) {
                merged.add(origin.trim());
            }
        }
        trustedResourceOrigins = Collections.unmodifiableSet(merged);
    }

    /**
     * The property form of {@link #addTrustedResourceOrigins(String...)}: a comma- or
     * whitespace-separated list, or null for "the application said nothing".
     */
    protected void addTrustedResourceOriginsFromProperty(String originsFromProperty) {
        if (originsFromProperty == null) {
            return;
        }

        List<String> origins = new ArrayList<>();
        for (String origin : originsFromProperty.split("[,\\s]+")) {
            if (!origin.isEmpty()) {
                origins.add(origin);
            }
        }
        addTrustedResourceOrigins(origins);
    }

    /**
     * The application's trusted resource origins, as configured. Never null; empty unless the
     * application asked for something.
     */
    public Set<String> getTrustedResourceOrigins() {
        return trustedResourceOrigins;
    }

    /**
     * Configure folder path where Velocity macros are stored.
     *
     * @param macroPath
     */
    public void setMacroPath(String macroPath) {
        this.macroPath = macroPath;
    }
}
