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
import com.webkreator.qlue.view.ViewFactory;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.app.event.EventCartridge;
import org.apache.velocity.runtime.RuntimeConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
     * <p>Canoe suppresses a reference in any attribute name it does not recognise (R5), which is
     * fail-closed and is right; without a way to widen the allowlist it is also how a security
     * control gets switched off in production, because the developer's remaining option is
     * {@code $_x.asis()} and that disables Canoe for the value entirely. Names are validated by
     * {@link Canoe#normalisePlainTextAttributeNames(java.util.Collection)}, which refuses anything
     * whose suppression is the fix for a finding, so the property can widen the plain-text set and
     * cannot re-open F3 or F20.
     */
    public static final String QLUE_CANOE_PLAIN_TEXT_ATTRIBUTES = "qlue.canoe.plainTextAttributes";

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

    protected Properties buildDefaultVelocityProperties(QlueApplication qlueApp) {
        Properties properties = new Properties();

        properties.setProperty(RuntimeConstants.INPUT_ENCODING, inputEncoding);

        // OUTPUT_ENCODING no longer exists in Velocity 2.3?
        // properties.setProperty(RuntimeConstants.OUTPUT_ENCODING, outputEncoding);
        properties.setProperty(OUTPUT_ENCODING, outputEncoding);

        properties.setProperty(RuntimeConstants.RESOURCE_LOADERS, "class,string");

        properties.setProperty("resource.loader.string.class", "org.apache.velocity.runtime.resource.loader.StringResourceLoader");
        properties.setProperty("resource.loader.string.repository.name", QLUE_STRING_RESOURCE_LOADER_KEY);

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
     * @param page
     * @param view
     * @throws Exception
     */
    public void render(Page page, VelocityView view) throws Exception {
        render(page, view, page.getContext().getResponse().getWriter());
    }

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

        try {
            Canoe qlueWriter = new Canoe(writer, plainTextAttributes);

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
            String message = e.getMessage();
            if ((message != null) && (message.startsWith(Canoe.ERROR_PREFIX))) {
                writer.append("[Encoding Error]");
            } else {
                throw e;
            }
        } finally {
            writer.flush();

            // We don't close the stream here in order
            // to enable Qlue to append to output as needed
            // (which is done in development mode)
        }
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
     * <p>The application-level half of R5. Canoe suppresses a reference in any attribute name it
     * does not recognise, which is the right default and cannot be the whole answer: a page with
     * {@code <div my-widget-config="$x">} would otherwise lose the value silently, and the
     * developer's next move is {@code $_x.asis()}, which turns the encoder off for that value
     * completely. This is the smaller hammer, and it is deliberately narrow — the names are treated
     * as <em>plain text</em>, so the value still goes through {@code html()} and still cannot leave
     * the attribute it was written into.
     *
     * <p>Call before the first render; the set is copied into every {@link Canoe} the factory
     * constructs. Names whose suppression is the fix for a finding are refused with an exception
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
     * Configure folder path where Velocity macros are stored.
     *
     * @param macroPath
     */
    public void setMacroPath(String macroPath) {
        this.macroPath = macroPath;
    }
}
