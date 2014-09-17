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
package com.webkreator.qlue.view;

import java.io.File;
import java.io.Writer;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.io.FileUtils;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.app.event.EventCartridge;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.resource.loader.StringResourceLoader;
import org.apache.velocity.runtime.resource.util.StringResourceRepository;

import com.webkreator.canoe.Canoe;
import com.webkreator.canoe.CanoeReferenceInsertionHandler;
import com.webkreator.canoe.HtmlEncoder;
import com.webkreator.qlue.Page;
import com.webkreator.qlue.QlueApplication;
import com.webkreator.qlue.TransactionContext;

/**
 * Base class for the view implementation that uses Velocity. Needs subclassing
 * to provide initialization and decide where to look for template files.
 */
public abstract class VelocityViewFactory implements ViewFactory {

	protected String suffix = ".vm";

	protected String inputEncoding = "UTF-8";

	protected String logChute = "com.webkreator.qlue.util.VelocityLog4jLogChute";

	protected VelocityEngine velocityEngine;

	protected String VELOCITY_STRING_RESOURCE_LOADER_KEY = "_QLUE_LOADER";

	protected boolean useAutoEscaping = true;

	protected String macroPath = "";

	protected Properties buildDefaultVelocityProperties(QlueApplication qlueApp) {
		Properties properties = new Properties();

		properties.setProperty("input.encoding", inputEncoding);

		properties.setProperty("resource.loader", "file,string");
		properties
				.setProperty("string.resource.loader.class",
						"org.apache.velocity.runtime.resource.loader.StringResourceLoader");
		properties.setProperty("string.resource.loader.repository.name",
				VELOCITY_STRING_RESOURCE_LOADER_KEY);

		properties.setProperty("velocimacro.library", macroPath);

		properties.setProperty(RuntimeConstants.RUNTIME_LOG_LOGSYSTEM_CLASS,
				logChute);

		if (qlueApp.getProperty("qlue.velocity.cache") != null) {
			properties.setProperty("file.resource.loader.cache",
					qlueApp.getProperty("qlue.velocity.cache"));
		}

		if (qlueApp.getProperty("qlue.velocity.modificationCheckInterval") != null) {
			properties
					.setProperty(
							"file.resource.loader.modificationCheckInterval",
							qlueApp.getProperty("qlue.velocity.modificationCheckInterval"));
		}

		return properties;
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

		model.put("_f", page.getFormatTool());

		// Normally, we don't want templates to be able to output
		// directly (without encoding) to responses, but some
		// pages will need to do that.
		if (page.allowDirectOutput()) {
			model.put(CanoeReferenceInsertionHandler.SAFE_REFERENCE_NAME,
					HtmlEncoder.instance());
		}

		model.put("_app", page.getQlueApp());
		model.put("_page", page);
		model.put("_i", page.getShadowInput());

		model.put("_ctx", context);
		model.put("_sess", page.getQlueApp().getQlueSession(context.request));
		model.put(
				"_m",
				page.getQlueApp().getMessageSource(
						page.getQlueApp().getQlueSession(context.request)
								.getLocale()));
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
				ec.addReferenceInsertionEventHandler(new CanoeReferenceInsertionHandler(
						qlueWriter));
				ec.attachToContext(velocityContext);
			}

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
	static interface FieldCallback {
		public void processField(String fieldName, Object fieldValue);
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

	public View constructView(Page page, File viewFile) throws Exception {
		// Find our repository
		StringResourceRepository repo = StringResourceLoader
				.getRepository(VELOCITY_STRING_RESOURCE_LOADER_KEY);
		// Add this file
		repo.putStringResource(viewFile.getAbsolutePath(),
				FileUtils.readFileToString(viewFile));
		// Construct view
		return new VelocityView(this, velocityEngine.getTemplate(viewFile
				.getAbsolutePath()));
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
