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

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Field;
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
import com.webkreator.qlue.util.WebUtil;
import com.webkreator.qlue.view.DefaultView;
import com.webkreator.qlue.view.FileVelocityViewFactory;
import com.webkreator.qlue.view.FinalRedirectView;
import com.webkreator.qlue.view.NamedView;
import com.webkreator.qlue.view.RedirectView;
import com.webkreator.qlue.view.View;
import com.webkreator.qlue.view.ViewFactory;
import com.webkreator.qlue.view.ViewResolver;

/**
 * 
 */
public class QlueApplication {

	public static final String PROPERTIES_FILENAME = "/WEB-INF/qlue.properties";

	public static final String SESSION_OBJECT_KEY = "QLUE_APPLICATION_SESSION_OBJECT";

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

	protected QlueApplication() {
		initPropertyEditors();
	}

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
	 * Initialise QlueApp instance. Qlue applications are designed to be used by
	 * servlets to delegate both initialisation and request processing.
	 * 
	 * @param servlet
	 * @throws Exception
	 */
	public void init(HttpServlet servlet) throws Exception {
		this.servlet = servlet;

		// Load Qlue properties
		File propsFile = new File(servlet.getServletContext().getRealPath(
				PROPERTIES_FILENAME));
		if (propsFile.exists()) {
			properties.load(new FileReader(propsFile));
			System.err.println(properties.getProperty("test"));
		}

		// Must have a page resolver.
		if (pageResolver == null) {
			throw new Exception("Page resolver not configured.");
		}

		// Must have a view resolver
		if (viewResolver == null) {
			throw new Exception("View resolver not configured.");
		}

		// Must have a view factory
		if (viewFactory == null) {
			throw new Exception("View factory not configured.");
		}

		// Initialise Velocity
		viewFactory.init(this);
	}

	/**
	 * 
	 * @param context
	 * @throws ServletException
	 */
	public void initRequestUri(TransactionContext context)
			throws ServletException {
		// Retrieve URI and normalise it.
		String uri = WebUtil.normaliseUri(context.request.getRequestURI());

		if (context.request.getQueryString() != null) {
			uri = uri + "?" + context.request.getQueryString();
		}

		// We are not expecting back-references in the URI, so
		// respond with an error if we do see one.
		if (uri.indexOf("..") != -1) {
			throw new ServletException(
					"Security violation: directory backreference "
							+ "detected in request URI: " + uri);
		}

		// Remember for later.
		context.setRequestUri(uri);
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
				session.setAttribute(SESSION_OBJECT_KEY, o);
			}
		}

		// Initialise context
		TransactionContext context = new TransactionContext(this,
				servlet.getServletConfig(), servlet.getServletContext(),
				request, response);

		// Assign a unique number to the transaction
		synchronized (txIdsCounter) {
			txIdsCounter++;
			context.setTxId(txIdsCounter);
		}

		// Construct a normalised request URI.
		initRequestUri(context);

		// Create a logging context using the unique transaction ID.
		NDC.push(appPrefix + "/" + context.getTxId());

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
			// First check if we need to handle multipart/form-data
			context.processMultipart();

			// -- Page resolution --

			// First check if this is a request for a persistent page
			// (which we can only honour if we are not handling errors)
			if (context.isErrorHandler() == false) {
				// Is this request for a persistent page?
				String pid = context.getParameter("_pid");
				if (pid != null) {
					PersistentPageRecord pageRecord = context
							.findPersistentPageRecord(pid);
					if (pageRecord == null) {
						throw new PersistentPageNotFoundException(
								"Persistent page not found: " + pid);
					}

					page = pageRecord.page;

					// If the requested persistent page no longer exists,
					// redirect the user to where he is supposed to go
					if ((page == null) && (pageRecord.replacementUri != null)) {
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

			// Initialise page
			synchronized (page) {
				page.setQlueApp(this);
				page.setUri(context.getRequestUri());
				page.constructDefaultView(viewResolver);
				page.setContext(context);
				page.determineCommandObject();

				// Persist persistent pages when we see a POST
				if ((page.isPersistent()) && (context.isPost())) {
					context.persistPage(page);
				}

				// Prepare first
				View view = page.preService();

				// If we don't have a view here, that means that
				// the pre-service method didn't interrupt request
				// processing -- we can continue.
				if (view == null) {
					// Update page state
					page.updateState();

					// Binds parameters of a persistent page initially when
					// the page is initialised, but later only on POST requests.
					if ((page.getState().compareTo(Page.STATE_NEW) == 0)
							|| (context.isPost())) {
						page.getErrors().clear();
						bindParameters(page, context);
					}

					if (page.getState().compareTo(Page.STATE_NEW) == 0) {
						// Give page the opportunity to initialize
						page.loadData();

						// Update shadow input
						updateShadowInput(page, context);
					}

					// -- Process request --

					if (page.hasErrors()) {
						view = page.onValidationError();
					}

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
						view = constructView(page, page.getView());
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

					view.render(page);
				}

				masterWriteRequestDevelopmentInformation(context, page);
			}

			if (page != null) {
				page.commit();
			}
		} catch (RequestMethodException rme) {
			if (page != null) {
				page.rollback();
			}

			// Convert RequestMethodException into a 405 response
			context.getResponse().sendError(
					HttpServletResponse.SC_METHOD_NOT_ALLOWED);
		} catch (PageNotFoundException pnfe) {
			if (page != null) {
				page.rollback();
			}

			// Convert PageNotFoundException into a 404 response
			context.getResponse().sendError(HttpServletResponse.SC_NOT_FOUND);
		} catch (Throwable t) {
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
		// if any are annotated as parameters. Validate those that are,
		// then bind them.
		Field[] fields = commandObject.getClass().getFields();
		for (Field f : fields) {
			if (f.isAnnotationPresent(QlueParameter.class)) {
				// Update missing shadow input fields
				if (shadowInput.get(f.getName()) == null) {
					Object o = f.get(commandObject);
					if (o != null) {
						shadowInput.set(f.getName(), o.toString());
					}
				}
			}
		}
	}

	void masterWriteRequestDevelopmentInformation(TransactionContext context,
			Page page) throws IOException {
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

		// Check response status code
		int status = context.response.getStatus();
		if ((status >= 300) && (status <= 399)) {
			return;
		}

		// Check content type
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

	protected void writeDevelopmentInformation(PrintWriter out) {
		out.println(" Prefix: " + HtmlEncoder.encodeForHTML(appPrefix));
		out.println(" Page ID counter: " + txIdsCounter);
		out.println(" Development mode: " + developmentMode);
	}

	/**
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
			throw new RuntimeException("Qlue: Command object cannot be null.");
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
				// POST requests
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
	 * 
	 * @param commandObject
	 * @param f
	 * @param page
	 * @param context
	 * @throws Exception
	 */
	private void bindNonArrayParameter(Object commandObject, Field f,
			Page page, TransactionContext context) throws Exception {
		ShadowInput shadowInput = page.getShadowInput();

		// Get the annotation.
		QlueParameter qp = f.getAnnotation(QlueParameter.class);

		// First check if the parameter is a file
		if (QlueFile.class.isAssignableFrom(f.getType())) {
			bindFileParameter(commandObject, f, page, context);
			return;
		}

		// Look for a property editor, which will know how to convert
		// text into a proper native type.
		PropertyEditor pe = editors.get(f.getType());
		if (pe == null) {
			throw new RuntimeException(
					"Qlue: Binding does not know how to handle type: "
							+ f.getType());
		}

		// Look for the parameter in the request object
		String value = context.getParameter(f.getName());
		if (value != null) {
			// Load from the parameter
			shadowInput.set(f.getName(), value);
		} else {
			// Load from the command object
			shadowInput.set(f.getName(), f.toString());
		}

		// If present, validate and set on the command object.
		if (value != null) {
			boolean hasErrors = false;

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
					page.addError(f.getName(), "qlue.validation.mandatory");
					hasErrors = true;
				}
			}

			// Check size.
			if (qp.maxSize() != -1) {
				if ((value.length() > qp.maxSize())) {
					hasErrors = true;

					if (qp.ignoreInvalid() == false) {
						page.addError(f.getName(), "qlue.validation.maxSize");
					}
				}
			}

			// Check that it conforms to the supplied regular expression.
			if (qp.pattern().length() != 0) {
				Pattern p = null;

				// Try to compile the pattern
				try {
					p = Pattern.compile(qp.pattern(), Pattern.DOTALL);
				} catch (PatternSyntaxException e) {
					throw new RuntimeException("Qlue: Invalid pattern: "
							+ qp.pattern());
				}

				Matcher m = p.matcher(value);
				if ((m.matches() == false)) {
					hasErrors = true;

					if (qp.ignoreInvalid() == false) {
						page.addError(f.getName(), "qlue.validation.pattern");
					}
				}
			}

			// Can we find a Validator instance to handle the type?
			// TODO

			// Bind the value only if there were no validation errors.
			if (hasErrors == false) {
				f.set(commandObject, pe.fromText(f, value));
			}
		} else {
			// Is the parameter mandatory?
			if (qp.mandatory()) {
				page.addError(f.getName(), "qlue.validation.mandatory");
			}
		}
	}

	private void bindFileParameter(Object commandObject, Field f, Page page,
			TransactionContext context) throws Exception {
		QlueParameter qp = f.getAnnotation(QlueParameter.class);

		FileItem fi = context.getFile(f.getName());
		if ((fi == null) || (fi.getSize() == 0)) {
			if (qp.mandatory()) {
				page.addError(f.getName(), "qlue.validation.mandatory");
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
	 * Not implemented.
	 * 
	 * @param commandObject
	 * @param f
	 * @param page
	 * @param context
	 */
	private void bindArrayParameter(Object commandObject, Field f, Page page,
			TransactionContext context) {
		// TODO
		throw new RuntimeException("Qlue: Not implemented.");
	}

	private void registerPropertyEditor(PropertyEditor editor) {
		editors.put(editor.getEditorClass(), editor);
	}

	protected void initPropertyEditors() {
		registerPropertyEditor(new IntegerEditor());
		registerPropertyEditor(new StringEditor());
		registerPropertyEditor(new BooleanEditor());
	}

	/**
	 * 
	 * @param page
	 * @param name
	 * @return
	 * @throws Exception
	 */
	View constructView(Page page, String name) throws Exception {
		return viewFactory.constructView(page, name);
	}

	// -- Getters and setters --

	public PageResolver getPageResolver() {
		return pageResolver;
	}

	public void setPageResolver(PageResolver pageResolver) {
		this.pageResolver = pageResolver;
	}

	public ViewResolver getViewResolver() {
		return viewResolver;
	}

	public void setViewResolver(ViewResolver viewResolver) {
		this.viewResolver = viewResolver;
	}

	public ViewFactory getViewFactory() {
		return viewFactory;
	}

	public void setViewFactory(ViewFactory viewFactory) {
		this.viewFactory = viewFactory;
	}

	public String getApplicationRoot() {
		return servlet.getServletContext().getRealPath("/");
	}

	public String getAppPrefix() {
		return appPrefix;
	}

	public void setAppPrefix(String appPrefix) {
		this.appPrefix = appPrefix;
	}

	public Object getFormatTool() {
		return new FormatTool();
	}

	public boolean isFolderUri(String uri) {
		return pageResolver.isFolderUri(WebUtil.normaliseUri(uri));
	}

	/**
	 * This method is invoked to create a new session object. A QlueSession
	 * instance is returned by default, but most applications will want to
	 * override this method and provide their own session objects.
	 * 
	 * @return new session object
	 */
	public QlueSession createNewSessionObject() {
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
				SESSION_OBJECT_KEY);
	}

	public void setPrefix(String prefix) {
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

	public void setCharacterEncoding(String characterEncoding) {
		this.characterEncoding = characterEncoding;
	}

	public void setApplicationDevelopmentMode(Integer developmentMode) {
		this.developmentMode = developmentMode;
	}

	public int getApplicationDevelopmentMode() {
		return developmentMode;
	}

	public void setDevelopmentModePassword(String developmentModePassword) {
		this.developmentModePassword = developmentModePassword;
	}

	public void setDevelopmentModeRanges(String[] developmentModeRanges) {
		this.developmentModeRanges = developmentModeRanges;
	}

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

	public boolean isDeveloperAccess(TransactionContext context) {
		// Check IP address first
		if (isDeveloperIP(context) == false) {
			return false;
		}

		// Check session development mode
		if (getQlueSession(context.getRequest()).getDevelopmentMode() == QlueConstants.DEVMODE_ENABLED) {
			return true;
		}

		if (getQlueSession(context.getRequest()).getDevelopmentMode() == QlueConstants.DEVMODE_DISABLED) {
			return false;
		}

		// Check application development mode
		if (getApplicationDevelopmentMode() == QlueConstants.DEVMODE_ENABLED) {
			return true;
		}

		return false;
	}

	public boolean checkDeveloperPassword(String password) {
		if ((password == null) || (developmentModePassword == null)) {
			return false;
		}

		if (password.compareTo(developmentModePassword) == 0) {
			return true;
		}

		return false;
	}

	public String getDeveloperPassword() {
		return developmentModePassword;
	}

	public Properties getProperties() {
		return properties;
	}

	public void setMessagesFilename(String messagesFilename) {
		this.messagesFilename = messagesFilename;
	}

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
}
