/* 
 * Qlue Web Application Framework
 * Copyright 2009,2010 Ivan Ristic <ivanr@webkreator.com>
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

import it.sauronsoftware.cron4j.InvalidPatternException;
import it.sauronsoftware.cron4j.Scheduler;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;
import java.util.StringTokenizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.log4j.NDC;
import org.apache.tomcat.util.http.fileupload.FileItem;

import com.webkreator.canoe.HtmlEncoder;
import com.webkreator.qlue.util.BooleanEditor;
import com.webkreator.qlue.util.FormatTool;
import com.webkreator.qlue.util.IntegerEditor;
import com.webkreator.qlue.util.PropertyEditor;
import com.webkreator.qlue.util.StringEditor;
import com.webkreator.qlue.util.TextUtil;
import com.webkreator.qlue.view.DefaultView;
import com.webkreator.qlue.view.FileVelocityViewFactory;
import com.webkreator.qlue.view.FinalRedirectView;
import com.webkreator.qlue.view.NamedView;
import com.webkreator.qlue.view.RedirectView;
import com.webkreator.qlue.view.View;
import com.webkreator.qlue.view.ViewFactory;
import com.webkreator.qlue.view.ViewResolver;

/**
 * This class represents one Qlue application. Very simple applications might
 * use it directly, but most will need to subclass in order to support complex
 * configuration (page resolver, view resolver, etc).
 */
public class QlueApplication {

	public static final String PROPERTIES_FILENAME = "/WEB-INF/qlue.properties";

	public static final String REQUEST_ACTUAL_PAGE_KEY = "QLUE_ACTUAL_PAGE";

	private String messagesFilename = "com/webkreator/qlue/messages";

	private Properties properties = new Properties();

	private String appPrefix = "QlueApp";

	private Integer txIdsCounter = 10000;

	private HttpServlet servlet;

	private Log log = LogFactory.getLog(QlueApplication.class);

	private PageResolver pageResolver;

	private ViewResolver viewResolver = new ViewResolver();

	private ViewFactory viewFactory = new FileVelocityViewFactory();

	@SuppressWarnings("rawtypes")
	private HashMap<Class, PropertyEditor> editors = new HashMap<Class, PropertyEditor>();

	private String characterEncoding = "UTF-8";

	private int developmentMode = QlueConstants.DEVMODE_DISABLED;

	private String developmentModePassword = null;

	private String[] developmentModeRanges = null;

	private Scheduler scheduler;

	/**
	 * This is the default constructor. The idea is that a subclass will
	 * override it and supplement with its own configuration.
	 */
	protected QlueApplication() {
		initPropertyEditors();
	}

	/**
	 * This constructor is intended for use by very simple web applications that
	 * consist of only one package.
	 * 
	 * @param pagesHome
	 */
	public QlueApplication(String pagesHome) {
		initPropertyEditors();

		PrefixPageResolver pageResolver = new PrefixPageResolver();
		List<UriMapping> mappings = new ArrayList<UriMapping>();
		mappings.add(new UriMapping("/_qlue/", "com.webkreator.qlue.pages"));
		mappings.add(new UriMapping("/", pagesHome));
		pageResolver.setMappings(mappings);

		this.pageResolver = pageResolver;
	}

	// -- Main entry points --

	/**
	 * Initialize QlueApp instance. Qlue applications are designed to be used by
	 * servlets to delegate both initialization and request processing.
	 * 
	 * @param servlet
	 * @throws Exception
	 */
	public void init(HttpServlet servlet) throws Exception {
		this.servlet = servlet;

		// Load Qlue properties if the file exists
		File propsFile = new File(servlet.getServletContext().getRealPath(
				PROPERTIES_FILENAME));
		if (propsFile.exists()) {
			properties.load(new FileReader(propsFile));
		}

		// Must have a page resolver
		if (pageResolver == null) {
			throw new Exception("Page resolver not configured");
		}

		// Must have a view resolver
		if (viewResolver == null) {
			throw new Exception("View resolver not configured");
		}

		// Must have a view factory
		if (viewFactory == null) {
			throw new Exception("View factory not configured");
		}

		// Initialize Velocity
		viewFactory.init(this);

		// Schedule application jobs
		scheduleApplicationJobs();
	}

	/**
	 * Destroys application resources.
	 */
	public void destroy() {
		// Stop scheduler
		scheduler.stop();
	}

	/**
	 * Schedules application jobs for execution.
	 */
	private void scheduleApplicationJobs() {
		// Create scheduler
		scheduler = new Scheduler();
		scheduler.start();

		// Enumerate all application methods and look
		// for the QlueSchedule annotation
		Method[] methods = this.getClass().getMethods();
		for (Method m : methods) {
			if (m.isAnnotationPresent(QlueSchedule.class)) {
				QlueSchedule qs = m.getAnnotation(QlueSchedule.class);
				try {
					scheduler.schedule(qs.value(),
							new QlueScheduleMethodTaskWrapper(this, m));
				} catch (InvalidPatternException ipe) {
					log.error("QlueSchedule: Invalid schedule pattern: "
							+ qs.value());
				}
			}
		}
	}

	/**
	 * Schedules the supplied task.
	 * 
	 * @param task
	 * @param schedule
	 * @return Task ID, which can later be used to cancel, or reschedule the
	 *         task.
	 */
	public String scheduleTask(Runnable task, String schedule) {
		return scheduler.schedule(schedule, task);
	}

	/**
	 * Deschedules task with the given ID.
	 * 
	 * @param taskId
	 */
	public void descheduleTask(String taskId) {
		scheduler.deschedule(taskId);
	}

	/**
	 * Reschedules an existing task.
	 * 
	 * @param taskId
	 * @param newSchedule
	 */
	public void reschedule(String taskId, String newSchedule) {
		scheduler.reschedule(taskId, newSchedule);
	}

	/**
	 * This method is the main entry point for request processing.
	 * 
	 * @param servlet
	 * @param request
	 * @param response
	 * @throws ServletException
	 * @throws java.io.IOException
	 */
	protected void service(HttpServlet servlet, HttpServletRequest request,
			HttpServletResponse response) throws ServletException,
			java.io.IOException {
		// Remember when processing began
		long startTime = System.currentTimeMillis();

		// Set character encoding
		request.setCharacterEncoding(characterEncoding);

		// Create a new application session
		// object if the session is new
		HttpSession session = request.getSession();
		if (session.isNew()) {
			Object o = createNewSessionObject();
			if (o != null) {
				session.setAttribute(QlueConstants.QLUE_SESSION_OBJECT, o);
			}
		}

		// Create new context
		TransactionContext context = new TransactionContext(this,
				servlet.getServletConfig(), servlet.getServletContext(),
				request, response);

		// Create a logging context using the unique transaction ID
		NDC.push(appPrefix + "/" + context.getTxId());

		// Proceed to the second stage of request processing
		try {
			if (log.isDebugEnabled()) {
				log.debug("Processing request: " + request.getRequestURI());
			}

			serviceInternal(context);

			if (log.isDebugEnabled()) {
				log.debug("Processed request in "
						+ (System.currentTimeMillis() - startTime));
			}
		} finally {
			// Remove logging context
			NDC.remove();
		}
	}

	/**
	 * Request processing entry point.
	 * 
	 * @param context
	 * @throws ServletException
	 * @throws java.io.IOException
	 */
	protected void serviceInternal(TransactionContext context)
			throws ServletException, java.io.IOException {
		Page page = null;

		try {
			// Check if we need to handle multipart/form-data
			context.processMultipart();

			// -- Page resolution --

			// Check if this is a request for a persistent page. We can
			// honor such requests only if we are not handling errors
			if (context.isErrorHandler() == false) {
				// Is this request for a persistent page?
				String pid = context.getParameter("_pid");
				if (pid != null) {
					// Find page record
					PersistentPageRecord pageRecord = context
							.findPersistentPageRecord(pid);
					if (pageRecord == null) {
						throw new PersistentPageNotFoundException(
								"Persistent page not found: " + pid);
					}

					// OK, got the page
					page = pageRecord.page;

					// If the requested persistent page no longer exists,
					// redirect the user to where he is supposed to go
					if ((page == null) && (pageRecord.replacementUri != null)) {
						// But not if we're already there
						if (context.getRequestUri().compareTo(
								pageRecord.replacementUri) != 0) {
							context.getResponse().sendRedirect(
									pageRecord.replacementUri);
							return;
						}
					}
				}
			}

			// If we still don't have a page see if we can create a new one
			if (page == null) {
				String uri = context.getRequestUri();
				int i = uri.indexOf('?');
				if (i != -1) {
					uri = uri.substring(0, i);
				}

				// Ask the resolver to create a page for us
				page = pageResolver.resolvePage(context.request, uri);
				if (page == null) {
					// Page not found
					throw new PageNotFoundException();
				}
			}

			// Page access in Qlue is synchronized, which means that
			// it can process only one request at a time. This is not
			// a problem for non-persistent pages, which are created
			// on per-request basis. Synchronization may be a problem,
			// but only if you abuse persistent pages, which were designed
			// to be used by one user at a time (on per-session basis).
			synchronized (page) {
				page.setQlueApp(this);
				page.setUri(context.getRequestUri());
				page.determineDefaultViewName(viewResolver);
				page.setContext(context);
				page.determineCommandObject();

				// Persist persistent pages when we see a POST
				if ((page.isPersistent()) && (context.isPost())) {
					context.persistPage(page);
				}

				// Give page a chance to prepare for the execution
				View view = page.preService();

				// If we don't have a view here, that means that
				// the pre-service method didn't interrupt request
				// processing -- we can continue.
				if (view == null) {
					// Instruct page to transition to its next state
					page.updateState();

					// Binds parameters of a persistent page initially when
					// the page is initialized, but later only on POST requests
					if ((page.getState().compareTo(Page.STATE_NEW) == 0)
							|| (context.isPost())) {
						page.getErrors().clear();
						bindParameters(page, context);
					}

					if (page.getState().compareTo(Page.STATE_NEW) == 0) {
						// Give page the opportunity to initialize
						page.init();

						// Update shadow input
						updateShadowInput(page, context);
					}

					// -- Process request --

					if (page.hasErrors()) {
						view = page.onValidationError();
					}

					// If we've made it so far that means that all is
					// dandy, and that we can finally let the page
					// process the current request
					if (view == null) {
						// Process request
						view = page.service();
					}
				}

				// Render view
				if (view != null) {
					// If we get a DefaultView or NamedView instance
					// we have to replace them with a real view, using
					// the name of the page in the view resolution process.
					if (view instanceof DefaultView) {
						// The page wants to use the default view.
						view = constructView(page, page.getViewName());
					} else if (view instanceof NamedView) {
						// We don't have a view, we just have its name.
						// Construct view using view factory.
						view = constructView(page,
								((NamedView) view).getViewName());
					} else if (view instanceof FinalRedirectView) {
						page.setState(Page.STATE_FINISHED);

						if (((RedirectView) view).getPage() != null) {
							context.replacePage(page, (FinalRedirectView) view);
						}
					}

					if (view == null) {
						throw new RuntimeException(
								"Qlue: Unable to resolve view");
					}

					// Render page
					view.render(page);
				}

				// In development mode, append debugging information
				// to the end of the page
				masterWriteRequestDevelopmentInformation(context, page);
			}

			// Execute page commit. This is what it sounds like,
			// an opportunity to use a simple approach to transaction
			// management for simple applications.
			if (page != null) {
				page.commit();
			}
		} catch (RequestMethodException rme) {
			// Execute rollback to undo any changes
			if (page != null) {
				page.rollback();
			}

			// Convert RequestMethodException into a 405 response
			context.getResponse().sendError(
					HttpServletResponse.SC_METHOD_NOT_ALLOWED);
		} catch (PageNotFoundException pnfe) {
			// Execute rollback to undo any changes
			if (page != null) {
				page.rollback();
			}

			// Convert PageNotFoundException into a 404 response
			context.getResponse().sendError(HttpServletResponse.SC_NOT_FOUND);
		} catch (Throwable t) {
			// Execute rollback to undo any changes
			if (page != null) {
				page.rollback();
			}

			// Log exception, then convert it into a ServletException. We
			// don't want to set response code here because the server might
			// not invoke the Throwable handler (and we want it to)
			log.error("Page exception", t);

			// Because we are about to throw an exception, which may cause
			// another page to handle this request, we need to remember
			// the current page (which is useful for debugging information, etc)
			setActualPage(page);

			throw new ServletException(t);
		}
	}

	/**
	 * Invoked to store the original text values for parameters. The text is
	 * needed in the cases where it cannot be converted to the intended type
	 * (e.g., integer).
	 * 
	 * @param page
	 * @param context
	 * @throws Exception
	 */
	private void updateShadowInput(Page page, TransactionContext context)
			throws Exception {
		// Ask the page to provide a command object, which can be
		// a custom object or the page itself.
		Object commandObject = page.getCommandObject();
		if (commandObject == null) {
			throw new RuntimeException("Qlue: Command object cannot be null.");
		}

		ShadowInput shadowInput = page.getShadowInput();

		// Loop through the command object fields in order to determine
		// if any are annotated as parameters. Remember the original
		// text values of parameters.
		Field[] fields = commandObject.getClass().getFields();
		for (Field f : fields) {
			if (f.isAnnotationPresent(QlueParameter.class)) {
				// Update missing shadow input fields
				if (shadowInput.get(f.getName()) == null) {
					// Find the property editor
					PropertyEditor pe = editors.get(f.getType());
					if (pe == null) {
						throw new RuntimeException(
								"Qlue: Binding does not know how to handle type: "
										+ f.getType());
					}

					// If the object exists, convert it to
					// text using the property editor
					Object o = f.get(commandObject);
					if (o != null) {
						shadowInput.set(f.getName(), pe.toText(o));
					}
				}
			}
		}
	}

	/**
	 * Appends debugging information to the view, but only if the development
	 * mode is active.
	 * 
	 * @param context
	 * @param page
	 * @throws IOException
	 */
	protected void masterWriteRequestDevelopmentInformation(
			TransactionContext context, Page page) throws IOException {
		// Check development mode
		if (page.isDeveloperAccess() == false) {
			return;
		}

		// We might be in an error handler, in which case we want to display
		// the state of the actual (original) page and not this one.
		Page actualPage = getActualPage(page);
		if (actualPage != null) {
			// Use the actual page and context
			page = actualPage;
			context = page.getContext();
		}

		// Ignore redirections
		int status = context.response.getStatus();
		if ((status >= 300) && (status <= 399)) {
			return;
		}

		// Ignore responses other than text/html
		String contentType = context.response.getContentType();
		if (contentType == null) {
			return;
		}

		int i = contentType.indexOf(';');
		if (i != -1) {
			contentType = contentType.substring(0, i);
		}

		if (contentType.compareToIgnoreCase("text/html") != 0) {
			return;
		}

		// Append output
		PrintWriter out = context.response.getWriter();
		out.println("<hr><div align=left><pre>");
		out.println("<b>Request</b>\n");
		context.writeRequestDevelopmentInformation(out);
		out.println("");
		out.println("<b>Page</b>\n");
		page.writeDevelopmentInformation(out);
		out.println("");
		out.println("<b>Session</b>\n");
		page.getQlueSession().writeDevelopmentInformation(out);
		out.println("");
		out.println("<b>Application</b>\n");
		this.writeDevelopmentInformation(out);
		out.println("</pre></div>");
	}

	/**
	 * Write application-specific debugging output.
	 * 
	 * @param out
	 */
	protected void writeDevelopmentInformation(PrintWriter out) {
		out.println(" Prefix: " + HtmlEncoder.encodeForHTML(appPrefix));
		out.println(" Page ID counter: " + txIdsCounter);
		out.println(" Development mode: " + developmentMode);
	}

	/**
	 * Bind request parameters to the command object provided by the page.
	 * 
	 * @param page
	 * @param context
	 * @throws IllegalAccessException
	 * @throws IllegalArgumentException
	 */
	private void bindParameters(Page page, TransactionContext context)
			throws Exception {
		// Ask the page to provide a command object, which can be
		// a custom object or the page itself.
		Object commandObject = page.getCommandObject();
		if (commandObject == null) {
			throw new RuntimeException("Qlue: Command object cannot be null");
		}

		// Loop through the command object fields in order to determine
		// if any are annotated as parameters. Validate those that are,
		// then bind them.
		Field[] fields = commandObject.getClass().getFields();
		for (Field f : fields) {
			if (f.isAnnotationPresent(QlueParameter.class)) {
				QlueParameter qp = f.getAnnotation(QlueParameter.class);

				// Process only the parameters that are
				// in the same state as the page, or if the parameter
				// uses the special state POST, which triggers on all
				// POST requests (irrespective of the state).
				if (((qp.state().compareTo(Page.STATE_POST) == 0) && (page.context
						.isPost()))
						|| (qp.state().compareTo(Page.STATE_NEW_OR_POST) == 0)
						|| (qp.state().compareTo(page.getState()) == 0)) {
					// We have a parameter; dispatch to the appropriate handler.
					if (f.getType().isArray()) {
						bindArrayParameter(commandObject, f, page, context);
					} else {
						bindNonArrayParameter(commandObject, f, page, context);
					}
				}
			}
		}
	}

	/**
	 * Bind an array parameter.
	 * 
	 * @param commandObject
	 * @param f
	 * @param page
	 * @param context
	 */
	private void bindArrayParameter(Object commandObject, Field f, Page page,
			TransactionContext context) throws Exception {
		// Find shadow input
		ShadowInput shadowInput = page.getShadowInput();

		// Get the annotation
		QlueParameter qp = f.getAnnotation(QlueParameter.class);

		// Look for a property editor, which will know how
		// to convert text into a proper native type
		PropertyEditor pe = editors.get(f.getType());
		if (pe == null) {
			throw new RuntimeException(
					"Qlue: Binding does not know how to handle type: "
							+ f.getType());
		}

		String[] values = context.getParameterValues(f.getName());
		if (values.length != 0) {
			shadowInput.set(f.getName(), values);
		} else {
			Object[] originalValues = (Object[]) f.get(commandObject);
			String[] textValues = new String[originalValues.length];
			for (int i = 0; i < originalValues.length; i++) {
				textValues[i] = originalValues.toString();
			}

			shadowInput.set(f.getName(), textValues);
		}

		boolean hasErrors = false;
		Object[] convertedValues = new Object[values.length];
		for (int i = 0; i < values.length; i++) {
			String newValue = validateParameter(page, f, qp, values[i]);
			if (newValue == null) {
				values[i] = newValue;
				convertedValues[i] = pe.fromText(f, values[i], f.get(commandObject));
			} else {
				hasErrors = true;
			}
		}

		if (hasErrors == false) {
			f.set(commandObject, convertedValues);
		}
	}

	/**
	 * Validate one parameter.
	 * 
	 * @param page
	 * @param f
	 * @param qp
	 * @param value
	 * @return
	 */
	protected String validateParameter(Page page, Field f, QlueParameter qp,
			String value) {
		// Transform value according to the list
		// of transformation functions supplied
		String tfn = qp.tfn();

		if (tfn.length() != 0) {
			StringTokenizer st = new StringTokenizer(tfn, " ,");
			while (st.hasMoreTokens()) {
				String t = st.nextToken();
				if (t.compareTo("trim") == 0) {
					value = value.trim();
				} else if (t.compareTo("lowercase") == 0) {
					value = value.toLowerCase();
				} else {
					throw new RuntimeException(
							"Qlue: Invalid parameter transformation function: "
									+ t);
				}
			}
		}

		// If the parameter is mandatory, check that is
		// not empty or that it does not consist only
		// of whitespace characters.
		if (qp.mandatory()) {
			if (TextUtil.isEmptyOrWhitespace(value)) {
				page.addError(f.getName(), getFieldMissingMessage(qp));
				return null;
			}
		}

		// Check size
		if (qp.maxSize() != -1) {
			if ((value.length() > qp.maxSize())) {
				if (qp.ignoreInvalid() == false) {
					page.addError(f.getName(), "qlue.validation.maxSize");
					return null;
				} else {
					return null;
				}
			}
		}

		// Check that it conforms to the supplied regular expression
		if (qp.pattern().length() != 0) {
			Pattern p = null;

			// Compile the pattern first
			try {
				p = Pattern.compile(qp.pattern(), Pattern.DOTALL);
			} catch (PatternSyntaxException e) {
				throw new RuntimeException("Qlue: Invalid pattern: "
						+ qp.pattern());
			}

			// Try to match
			Matcher m = p.matcher(value);
			if ((m.matches() == false)) {
				if (qp.ignoreInvalid() == false) {
					page.addError(f.getName(), "qlue.validation.pattern");
					return null;
				} else {
					return null;
				}
			}
		}

		return value;
	}

	/**
	 * Bind a parameter that is not an array.
	 * 
	 * @param commandObject
	 * @param f
	 * @param page
	 * @param context
	 * @throws Exception
	 */
	private void bindNonArrayParameter(Object commandObject, Field f,
			Page page, TransactionContext context) throws Exception {
		// Find shadow input
		ShadowInput shadowInput = page.getShadowInput();

		// Get the annotation
		QlueParameter qp = f.getAnnotation(QlueParameter.class);

		// First check if the parameter is a file
		if (QlueFile.class.isAssignableFrom(f.getType())) {
			bindFileParameter(commandObject, f, page, context);
			return;
		}

		// Look for a property editor, which will know how
		// to convert text into a native type
		PropertyEditor pe = editors.get(f.getType());
		if (pe == null) {
			throw new RuntimeException(
					"Qlue: Binding does not know how to handle type: "
							+ f.getType());
		}

		// Keep track of the original text parameter value
		String value = context.getParameter(f.getName());
		if (value != null) {
			// Load from the parameter
			shadowInput.set(f.getName(), value);
		} else {
			// Load from the command object
			Object o = f.get(commandObject);
			if (o != null) {
				shadowInput.set(f.getName(), pe.toText(o));
			}
		}

		// If the parameter is present in request, validate it
		// and set on the command object
		if (value != null) {
			String newValue = validateParameter(page, f, qp, value);
			if (newValue != null) {
				value = newValue;
				f.set(commandObject,
						pe.fromText(f, value, f.get(commandObject)));
			}
		} else {
			f.set(commandObject, pe.fromText(f, value, f.get(commandObject)));
			// We are here if the parameter is not in request, in which
			// case we need to check of the parameter is mandatory
			if (qp.mandatory()) {
				page.addError(f.getName(), getFieldMissingMessage(qp));
			}
		}
	}

	/**
	 * Retrieve field message that we need to emit when a mandatory parameter is
	 * missing.
	 * 
	 * @param qp
	 * @return
	 */
	private String getFieldMissingMessage(QlueParameter qp) {
		return (qp.fieldMissingMessage().length() > 0) ? qp
				.fieldMissingMessage() : "qlue.validation.mandatory";
	}

	/**
	 * Bind file parameter.
	 * 
	 * @param commandObject
	 * @param f
	 * @param page
	 * @param context
	 * @throws Exception
	 */
	private void bindFileParameter(Object commandObject, Field f, Page page,
			TransactionContext context) throws Exception {
		QlueParameter qp = f.getAnnotation(QlueParameter.class);

		FileItem fi = context.getFile(f.getName());
		if ((fi == null) || (fi.getSize() == 0)) {
			if (qp.mandatory()) {
				page.addError(f.getName(), getFieldMissingMessage(qp));
			}

			return;
		}

		File file = File.createTempFile("qlue-", ".tmp");
		fi.write(file);
		fi.delete();

		QlueFile qf = new QlueFile(file.getAbsolutePath());
		qf.setContentType(fi.getContentType());

		f.set(commandObject, qf);
	}

	/**
	 * Register a new property editor.
	 * 
	 * @param editor
	 */
	private void registerPropertyEditor(PropertyEditor editor) {
		editors.put(editor.getEditorClass(), editor);
	}

	/**
	 * Register the built-in property editors.
	 */
	protected void initPropertyEditors() {
		registerPropertyEditor(new IntegerEditor());
		registerPropertyEditor(new StringEditor());
		registerPropertyEditor(new BooleanEditor());
	}

	/**
	 * Invoke the view factory to construct the view indicated by the name.
	 * 
	 * @param page
	 * @param name
	 * @return
	 * @throws Exception
	 */
	View constructView(Page page, String name) throws Exception {
		return viewFactory.constructView(page, name);
	}

	/**
	 * Retrieve page resolver.
	 * 
	 * @return
	 */
	public PageResolver getPageResolver() {
		return pageResolver;
	}

	/**
	 * Set page resolver.
	 * 
	 * @param pageResolver
	 */
	protected void setPageResolver(PageResolver pageResolver) {
		this.pageResolver = pageResolver;
	}

	/**
	 * Retrieve view resolver.
	 * 
	 * @return
	 */
	public ViewResolver getViewResolver() {
		return viewResolver;
	}

	/**
	 * Set view resolver.
	 * 
	 * @param viewResolver
	 */
	protected void setViewResolver(ViewResolver viewResolver) {
		this.viewResolver = viewResolver;
	}

	/**
	 * Retrieve view factory.
	 * 
	 * @return
	 */
	public ViewFactory getViewFactory() {
		return viewFactory;
	}

	/**
	 * Set view factory.
	 * 
	 * @param viewFactory
	 */
	protected void setViewFactory(ViewFactory viewFactory) {
		this.viewFactory = viewFactory;
	}

	/**
	 * Get application root directory.
	 * 
	 * @return
	 */
	public String getApplicationRoot() {
		return servlet.getServletContext().getRealPath("/");
	}

	/**
	 * Get application prefix.
	 * 
	 * @return
	 */
	public String getAppPrefix() {
		return appPrefix;
	}

	/**
	 * Set application prefix.
	 * 
	 * @param appPrefix
	 */
	protected void setAppPrefix(String appPrefix) {
		this.appPrefix = appPrefix;
	}

	/**
	 * Retrieve this application's format tool, which is used in templates to
	 * format output (but _not_ for output encoding). By default, that's an
	 * instance of FormatTool, but subclasses can use something else.
	 */
	public Object getFormatTool() {
		return new FormatTool();
	}

	/**
	 * This method is invoked to create a new session object. A QlueSession
	 * instance is returned by default, but most applications will want to
	 * override this method and provide their own session objects.
	 * 
	 * @return new session object
	 */
	protected QlueSession createNewSessionObject() {
		return new QlueSession(this);
	}

	/**
	 * Returns the session object associated with the current HTTP session.
	 * 
	 * @param request
	 * @return
	 */
	public QlueSession getQlueSession(HttpServletRequest request) {
		return (QlueSession) request.getSession().getAttribute(
				QlueConstants.QLUE_SESSION_OBJECT);
	}

	/**
	 * Invalidates the existing session and creates a new one, preserving the
	 * QlueSession object in the process. This method should be invoked
	 * immediately after a user is authenticated to prevent session fixation
	 * attacks.
	 * 
	 * @param request
	 */
	public void regenerateSession(HttpServletRequest request) {
		QlueSession qlueSession = getQlueSession(request);
		QluePageManager pageManager = (QluePageManager) request.getSession()
				.getAttribute(QlueConstants.QLUE_SESSION_PAGE_MANAGER);
		request.getSession().invalidate();
		request.getSession(true).setAttribute(
				QlueConstants.QLUE_SESSION_OBJECT, qlueSession);
		request.getSession().setAttribute(
				QlueConstants.QLUE_SESSION_PAGE_MANAGER, pageManager);
	}

	/**
	 * Set application prefix, which is used in logging as part of the unique
	 * transaction identifier.
	 * 
	 * @param prefix
	 */
	protected void setPrefix(String prefix) {
		this.appPrefix = prefix;
	}

	/**
	 * Whether direct output (in which the programmer is expected to manually
	 * encode data) is allowed. We do not allow direct output by default.
	 * Override this method to change the behaviour.
	 * 
	 * @return always false
	 */
	public boolean allowDirectOutput() {
		return false;
	}

	/**
	 * Configure character encoding.
	 * 
	 * @param characterEncoding
	 */
	protected void setCharacterEncoding(String characterEncoding) {
		this.characterEncoding = characterEncoding;
	}

	/**
	 * Retrieves application's character encoding.
	 * 
	 * @return
	 */
	public String getCharacterEncoding() {
		return characterEncoding;
	}

	/**
	 * Configure development mode.
	 * 
	 * @param developmentMode
	 */
	protected void setApplicationDevelopmentMode(Integer developmentMode) {
		this.developmentMode = developmentMode;
	}

	/**
	 * Get the development mode setting.
	 * 
	 * @return
	 */
	public int getApplicationDevelopmentMode() {
		return developmentMode;
	}

	/**
	 * Set development mode password.
	 * 
	 * @param developmentModePassword
	 */
	public void setDevelopmentModePassword(String developmentModePassword) {
		this.developmentModePassword = developmentModePassword;
	}

	/**
	 * Configure the set of IP addresses that are allowed to use development
	 * mode.
	 * 
	 * @param developmentModeRanges
	 */
	protected void setDevelopmentModeRanges(String[] developmentModeRanges) {
		this.developmentModeRanges = developmentModeRanges;
	}

	/**
	 * Check if the current transaction comes from an IP address that is allowed
	 * to use development mode.
	 * 
	 * @param context
	 * @return
	 */
	public boolean isDeveloperIP(TransactionContext context) {
		if (developmentModeRanges == null) {
			return false;
		}

		for (String ip : developmentModeRanges) {
			if (context.request.getRemoteAddr().compareTo(ip) == 0) {
				return true;
			}
		}

		return false;
	}

	/**
	 * Check if the current transaction comes from a developer.
	 * 
	 * @param context
	 * @return
	 */
	public boolean isDeveloperAccess(TransactionContext context) {
		// Check IP address first
		if (isDeveloperIP(context) == false) {
			return false;
		}

		// Check session development mode (explicitly enabled)
		if (getQlueSession(context.getRequest()).getDevelopmentMode() == QlueConstants.DEVMODE_ENABLED) {
			return true;
		}

		// Check session development mode (explicitly disabled)
		if (getQlueSession(context.getRequest()).getDevelopmentMode() == QlueConstants.DEVMODE_DISABLED) {
			return false;
		}

		// Check application development mode
		if (getApplicationDevelopmentMode() == QlueConstants.DEVMODE_ENABLED) {
			return true;
		}

		return false;
	}

	/**
	 * Check given password against the current development password.
	 * 
	 * @param password
	 * @return
	 */
	public boolean checkDeveloperPassword(String password) {
		if ((password == null) || (developmentModePassword == null)) {
			return false;
		}

		if (password.compareTo(developmentModePassword) == 0) {
			return true;
		}

		return false;
	}

	/**
	 * Get the current development password.
	 * 
	 * @return
	 */
	public String getDeveloperPassword() {
		return developmentModePassword;
	}

	/**
	 * Retrieve this application's properties.
	 * 
	 * @return
	 */
	public Properties getProperties() {
		return properties;
	}

	/**
	 * Retrieve a single named property as text.
	 * 
	 * @param key
	 * @return
	 */
	public String getProperty(String key) {
		return properties.getProperty(key);
	}

	/**
	 * Retrieve a single named property as text, using the supplied default
	 * value if the property is not set.
	 * 
	 * @param key
	 * @param defaultValue
	 * @return
	 */
	public String getProperty(String key, String defaultValue) {
		String value = properties.getProperty(key);
		if (value != null) {
			return value;
		} else {
			return defaultValue;
		}
	}

	/**
	 * Retrieve a single integer property.
	 * 
	 * @param key
	 * @return
	 */
	public Integer getIntProperty(String key) {
		String value = properties.getProperty(key);
		if (value == null) {
			return null;
		}

		return Integer.parseInt(value);
	}

	/**
	 * Retrieve a single integer property, using the supplied default value if
	 * the property is not set.
	 * 
	 * @param key
	 * @param defaultValue
	 * @return
	 */
	public Integer getIntProperty(String key, int defaultValue) {
		String value = properties.getProperty(key);
		if (value == null) {
			return defaultValue;
		}

		return Integer.parseInt(value);
	}

	/**
	 * Configure the path to the file that contains localized messages.
	 * 
	 * @param messagesFilename
	 */
	protected void setMessagesFilename(String messagesFilename) {
		this.messagesFilename = messagesFilename;
	}

	/**
	 * Retrieve this application's message source.
	 * 
	 * @param locale
	 * @return
	 */
	public MessageSource getMessageSource(Locale locale) {
		return new MessageSource(
				(PropertyResourceBundle) ResourceBundle.getBundle(
						messagesFilename, locale), locale);
	}

	/**
	 * Remember the current page for later use (e.g., in an error handler).
	 * 
	 * @param page
	 */
	void setActualPage(Page page) {
		page.context.request.setAttribute(REQUEST_ACTUAL_PAGE_KEY, page);
	}

	/**
	 * Retrieve the actual page that tried to handle the current transaction and
	 * failed.
	 * 
	 * @param currentPage
	 * @return
	 */
	Page getActualPage(Page currentPage) {
		return (Page) currentPage.context.request
				.getAttribute(REQUEST_ACTUAL_PAGE_KEY);
	}

	/**
	 * Allocates a new page ID.
	 * 
	 * @return
	 */
	synchronized int allocatePageId() {
		txIdsCounter++;
		return txIdsCounter;
	}
}
