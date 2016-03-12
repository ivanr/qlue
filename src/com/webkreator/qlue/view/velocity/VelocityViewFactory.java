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
import com.webkreator.qlue.TransactionContext;
import com.webkreator.qlue.util.HtmlEncoder;
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
import java.util.Map;
import java.util.Properties;

/**
 * Base class for the view implementation that uses Velocity. Needs subclassing
 * to provide initialization and decide where to look for template files.
 */
public abstract class VelocityViewFactory implements ViewFactory {

	public static final String QLUE_STRING_RESOURCE_LOADER_KEY = "QLUE_STRING_RESOURCE_LOADER";
	
	protected static Logger log = LoggerFactory.getLogger(VelocityViewFactory.class);

	protected String suffix = ".vm";

	protected String inputEncoding = "UTF-8";

	protected String logChute = "com.webkreator.qlue.view.velocity.SLF4JLogChute";

	protected VelocityEngine velocityEngine;

	protected boolean useAutoEscaping = true;

	protected String macroPath = "";

	protected Properties buildDefaultVelocityProperties(QlueApplication qlueApp) {
		Properties properties = new Properties();

		properties.setProperty(RuntimeConstants.INPUT_ENCODING, inputEncoding);

		properties.setProperty(RuntimeConstants.RESOURCE_LOADER, "file,string");
		properties.setProperty("string.resource.loader.class", "org.apache.velocity.runtime.resource.loader.StringResourceLoader");
		properties.setProperty("string.resource.loader.repository.name", QLUE_STRING_RESOURCE_LOADER_KEY);

		properties.setProperty(RuntimeConstants.VM_LIBRARY, macroPath);
		properties.setProperty(RuntimeConstants.VM_LIBRARY_AUTORELOAD, "true");
		properties.setProperty(RuntimeConstants.VM_PERM_ALLOW_INLINE, "true");
		properties.setProperty(RuntimeConstants.VM_PERM_ALLOW_INLINE_REPLACE_GLOBAL, "true");

		properties.setProperty(RuntimeConstants.RUNTIME_LOG_LOGSYSTEM_CLASS, logChute);

		if (qlueApp.getProperty("qlue.velocity.cache") != null) {
			properties.setProperty(RuntimeConstants.FILE_RESOURCE_LOADER_CACHE, qlueApp.getProperty("qlue.velocity.cache"));
		} else {
            properties.setProperty(RuntimeConstants.FILE_RESOURCE_LOADER_CACHE, "false");
        }

		if (qlueApp.getProperty("qlue.velocity.modificationCheckInterval") != null) {
			properties.setProperty(
					"file.resource.loader.modificationCheckInterval",
					qlueApp.getProperty("qlue.velocity.modificationCheckInterval"));
		}

        properties.setProperty("directive.set.null.allowed", "true");

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
	protected void render(Page page, VelocityView view) throws Exception {
		TransactionContext context = page.getContext();

		// Obtain the model from the page
		final Map<String, Object> model = page.getModel();

		// Add common objects to the model

		Object f = page.getVelocityTool();
		if (f instanceof QlueVelocityTool) {
			((QlueVelocityTool)f).setPage(page);
		}
		model.put("_f", f);

		// Normally, we don't want templates to be able to output
		// directly (without encoding) to responses, but some
		// pages will need to do that.
		if (page.allowDirectOutput()) {
			model.put(CanoeReferenceInsertionHandler.SAFE_REFERENCE_NAME, HtmlEncoder.instance());
		}

		model.put("_app", page.getApp());
		model.put("_page", page);
		model.put("_i", page.getShadowInput());

		model.put("_ctx", context);
		model.put("_sess", page.getApp().getQlueSession(context.request));
		model.put("_m", page.getApp().getMessageSource(page.getApp().getQlueSession(context.request).getLocale()));
		model.put("_req", context.request);
		model.put("_res", context.response);
		model.put("_cmd", page.getCommandObject());
		model.put("_errors", page.getErrors());

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

		// Configure Content-Type (which will set both the MIME
		// type and the character encoding).
		context.response.setContentType(page.getContentType());

		Writer writer = context.response.getWriter();

		try {
			Canoe qlueWriter = new Canoe(writer);

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

	/**
	 * Get the current Velocity template suffix.
	 * 
	 * @return
	 */
	public String getSuffix() {
		return suffix;
	}

	/**
	 * Set Velocity template suffix.
	 * 
	 * @param suffix
	 */
	public void setSuffix(String suffix) {
		this.suffix = suffix;
	}

	public void setAutoEscaping(boolean b) {
		useAutoEscaping = b;
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
