/* 
 * Qlue Web Application Framework
 * Copyright 2009 Ivan Ristic <ivanr@webkreator.com>
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
package com.webkreator.qlue;

import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.webkreator.canoe.HtmlEncoder;
import com.webkreator.qlue.util.UriBuilder;
import com.webkreator.qlue.view.View;
import com.webkreator.qlue.view.ViewResolver;

public abstract class Page {

	public static final String STATE_NEW = "NEW";

	public static final String STATE_POST = "POST";

	public static final String STATE_SUBMIT = "SUBMIT";

	public static final String STATE_FINISHED = "FINISHED";

	public static final String STATE_NEW_OR_POST = "NEW_OR_POST";

	private Integer id;

	private String state = STATE_NEW;

	private Log log = LogFactory.getLog(Page.class);

	protected QlueApplication qlueApp;

	private String uri;

	protected TransactionContext context;

	protected Map<String, Object> model = new HashMap<String, Object>();

	protected String view;

	protected String contentType = "text/html; charset=UTF-8";

	protected Object commandObject;

	protected Errors errors = new Errors();

	protected ShadowInput shadowInput = new ShadowInput();

	public boolean isFinished() {
		return (getState().compareTo(STATE_FINISHED) == 0);
	}

	/**
	 * 
	 * @return
	 */
	public Integer getId() {
		return id;
	}

	/**
	 * 
	 * @param id
	 */
	public void setId(Integer id) {
		this.id = id;
	}

	/**
	 * Update this page's state. In this simple implementation, we change from
	 * STATE_NEW to STATE_SUBMIT on first POST and we never move away from
	 * STATE_SUBMIT. An advanced implementation could have several submit states
	 * and offer means to cycle among them.
	 * 
	 * @return
	 */
	public void updateState() {
		if (context.isPost()) {
			setState(STATE_SUBMIT);
		}
	}

	public ShadowInput getShadowInput() {
		return shadowInput;
	}

	/**
	 * 
	 * @return
	 */
	public String getState() {
		return state;
	}

	/**
	 * 
	 * @param state
	 */
	public void setState(String state) {
		this.state = state;
	}

	protected Log getLog() {
		return log;
	}

	public QlueApplication getQlueApp() {
		return qlueApp;
	}

	public void setQlueApp(QlueApplication qlueApp) {
		this.qlueApp = qlueApp;
	}

	/**
	 * Return a command object. By default, the page is the command object, but
	 * a subclass has the option to use a different object. The page can use the
	 * supplied context to choose which command object (out of several it might
	 * be using) to return.
	 */
	public final synchronized Object getCommandObject() {
		if (commandObject == null) {
			determineCommandObject();
		}

		return commandObject;
	}

	public void determineCommandObject() {
		commandObject = this;
	}

	/**
	 * Process one HTTP request.
	 * 
	 * @param context
	 * @return
	 * @throws Exception
	 */
	public View service() throws Exception {
		if ((context.request.getMethod().compareTo("GET") == 0)
				|| (context.request.getMethod().compareTo("HEAD") == 0)) {
			return onGet();
		} else if (context.request.getMethod().compareTo("POST") == 0) {
			return onPost();
		} else {
			throw new RequestMethodException();
		}
	}

	/**
	 * Show something (process a GET request).
	 * 
	 * @param context
	 * @return
	 * @throws Exception
	 */
	public View onGet() throws Exception {
		throw new RequestMethodException();
	}

	/**
	 * Do something (process a POST request). The default implement
	 * implementation will simply redirect to the same URI (effectively
	 * converting a POST to a GET). Any POST data will be lost in conversion,
	 * though.
	 * 
	 * @param context
	 * @return
	 * @throws Exception
	 */
	public View onPost() throws Exception {
		throw new RequestMethodException();
	}

	// -- Getters and setters --

	public Map<String, Object> getModel() {
		return model;
	}

	public String getView() {
		return view;
	}

	public void setView(String view) {
		this.view = view;
	}

	public String getContentType() {
		return contentType;
	}

	public void setContentType(String contentType) {
		this.contentType = contentType;
	}

	public TransactionContext getContext() {
		return context;
	}

	public void setContext(TransactionContext context) {
		this.context = context;
	}

	public String getUri() {
		if (id == null) {
			return uri;
		} else {
			UriBuilder r = new UriBuilder(uri);
			r.clearParams();
			r.addParam("_pid", id);

			return r.getUri();
		}
	}

	public void setUri(String uri) {
		this.uri = uri;
	}

	public Object getFormatTool() {
		return getQlueApp().getFormatTool();
	}

	public void constructDefaultView(ViewResolver resolver) {
		view = resolver.resolveView(getNoParamUri());
	}

	/**
	 * This method is invoked right before the main service method. It allows
	 * the page to prepare for request processing.
	 */
	public View preService() throws Exception {
		// Retrieve session nonce
		String nonce = getQlueSession().getNonce();

		// Verify nonce on every POST
		if (context.isPost()
				&& getClass().isAnnotationPresent(QluePersistentPage.class)) {
			String suppliedNonce = context.getParameter("_nonce");
			if (suppliedNonce == null) {
				throw new RuntimeException("Nonce missing.");
			}

			if (suppliedNonce.compareTo(nonce) != 0) {
				throw new RuntimeException("Nonce mismatch. Expected " + nonce
						+ " but got " + suppliedNonce);
			}
		}

		// Add nonce to the model so that it
		// can be used from the templates
		model.put("_nonce", nonce);

		return null;
	}

	protected boolean hasErrors() {
		return errors.hasErrors();
	}

	/**
	 * 
	 * @return
	 */
	public Errors getErrors() {
		return errors;
	}

	/**
	 * Adds a page-specific error message.
	 * 
	 * @param message
	 */
	public void addError(String message) {
		errors.addError(message);
	}

	/**
	 * Adds a field-specific error message.
	 * 
	 * @param fieldName
	 * @param message
	 */
	public void addError(String fieldName, String message) {
		errors.addError(fieldName, message);
	}

	protected QlueSession getQlueSession() {
		return qlueApp.getQlueSession(context.getRequest());
	}

	public boolean allowDirectOutput() {
		return qlueApp.allowDirectOutput();
	}

	public boolean isDeveloperAccess() {
		return qlueApp.isDeveloperAccess(context);
	}

	public String getNoParamUri() {
		int i = uri.indexOf('?');
		if (i == -1) {
			return uri;
		} else {
			return uri.substring(0, i);
		}
	}

	protected void writeDevelopmentInformation(PrintWriter out) {
		out.println(" Id: " + getId());
		out.println(" Class: " + this.getClass());
		out.println(" State: " + HtmlEncoder.encodeForHTML(getState()));
		out.println(" Errors {");

		int i = 1;
		for (Error e : errors.getAllErrors()) {
			out.print("   " + i++ + ". ");
			out.print(HtmlEncoder.encodeForHTML(e.getMessage()));

			if (e.getField() != null) {
				out.print(" [field " + HtmlEncoder.encodeForHTML(e.getField())
						+ "]");
			}

			out.println();
		}

		out.println(" }");
		out.println("");
		out.println("<b>Model</b>\n");

		Map<String, Object> model = getModel();

		for (Iterator<String> it = model.keySet().iterator(); it.hasNext();) {
			String name = it.next();
			Object o = model.get(name);
			out.println(" "
					+ HtmlEncoder.encodeForHTML(name)
					+ ": "
					+ ((o != null) ? HtmlEncoder.encodeForHTML(o.toString())
							: "null"));
		}
	}

	public void rollback() {
		deleteFiles();
	}

	public void commit() {
		deleteFiles();
	}

	public void loadData() throws Exception {
	}

	public void deleteFiles() {
		Object commandObject = getCommandObject();
		if (commandObject == null) {
			return;
		}

		// Look for QlueFile instances
		Field[] fields = commandObject.getClass().getFields();
		for (Field f : fields) {
			if (f.isAnnotationPresent(QlueParameter.class)) {
				if (QlueFile.class.isAssignableFrom(f.getType())) {
					try {
						QlueFile qf = (QlueFile) f.get(commandObject);
						qf.delete();
					} catch (Exception e) {
						e.printStackTrace(System.err);
					}
				}
			}
		}
	}

	public boolean isPersistent() {
		return getClass().isAnnotationPresent(QluePersistentPage.class);
	}

	/**
	 * This method is invoked after built-in parameter validation fails. The
	 * default implementation will throw an exception for non-persistent pages,
	 * and ignore the problem for persistent pages.
	 * 
	 * @return
	 * @throws Exception
	 */
	View onValidationError() throws Exception {
		if (isPersistent() == true) {
			// Let the page handle validation errors
			return null;
		}

		// Report fatal error
		throw new ValidationException("Parameter validation failed");
	}
}
