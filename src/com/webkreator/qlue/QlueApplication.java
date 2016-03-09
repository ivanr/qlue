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
package com.webkreator.qlue;

import com.webkreator.qlue.editors.*;
import com.webkreator.qlue.exceptions.*;
import com.webkreator.qlue.router.QlueRouteManager;
import com.webkreator.qlue.router.RouteFactory;
import com.webkreator.qlue.util.*;
import com.webkreator.qlue.view.*;
import com.webkreator.qlue.view.velocity.ClasspathVelocityViewFactory;
import com.webkreator.qlue.view.velocity.DefaultVelocityTool;
import org.apache.commons.fileupload.FileItem;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.mail.Email;
import org.apache.commons.mail.EmailException;
import org.apache.commons.mail.SimpleEmail;
import org.apache.log4j.NDC;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.*;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.security.InvalidParameterException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * This class represents one Qlue application. Very simple applications might
 * use it directly, but most will need to subclass in order to support complex
 * configuration (page resolver, view resolver, etc).
 */
public class QlueApplication {

    public static final String PROPERTIES_FILENAME = "qlue.properties";

    public static final String ROUTES_FILENAME = "routes.conf";

    public static final String REQUEST_ACTUAL_PAGE_KEY = "QLUE_ACTUAL_PAGE";

    public static final String PROPERTY_CONF_PATH = "qlue.confPath";

    public static final int FRONTEND_ENCRYPTION_NO = 0;

    public static final int FRONTEND_ENCRYPTION_CONTAINER = 1;

    public static final int FRONTEND_ENCRYPTION_FORCE_YES = 2;

    public static final int FRONTEND_ENCRYPTION_TRUSTED_HEADER = 3;

    private static final String PROPERTY_CHARACTER_ENCODING = "qlue.characterEncoding";

    private static final String PROPERTY_DEVMODE_ENABLED = "qlue.devmode.active";

    private static final String PROPERTY_DEVMODE_RANGES = "qlue.devmode.subnets";

    private static final String PROPERTY_DEVMODE_PASSWORD = "qlue.devmode.password";

    private static final String PROPERTY_TRUSTED_PROXIES = "qlue.trustedProxies";

    private static final String PROPERTY_FRONTEND_ENCRYPTION = "qlue.frontendEncryption";

    private static final String PROPERTY_ADMIN_EMAIL = "qlue.adminEmail";

    private static final String PROPERTY_URGENT_EMAIL = "qlue.urgentEmail";

    private String messagesFilename = "com/webkreator/qlue/messages";

    private Properties properties = new Properties();

    private String appPrefix = "QlueApp";

    private Integer txIdsCounter = 10000;

    private HttpServlet servlet;

    private Log log = LogFactory.getLog(QlueApplication.class);

    private QlueRouteManager routeManager = new QlueRouteManager(this);

    private ViewResolver viewResolver = new ViewResolver();

    private ViewFactory viewFactory = new ClasspathVelocityViewFactory();

    @SuppressWarnings("rawtypes")
    private HashMap<Class, PropertyEditor> editors = new HashMap<Class, PropertyEditor>();

    private String characterEncoding = "UTF-8";

    private int developmentMode = QlueConstants.DEVMODE_DISABLED;

    private String developmentModePassword = null;

    private IpRangeFilter[] developmentSubnets = null;

    private IpRangeFilter[] trustedProxies = null;

    private String adminEmail;

    private String urgentEmail;

    private int urgentCounter = -1;

    private SmtpEmailSender smtpEmailSender;

    private SmtpEmailSender asyncSmtpEmailSender;

    private HashMap<Locale, MessageSource> messageSources = new HashMap<Locale, MessageSource>();

    private String confPath;

    private int frontendEncryptionCheck = FRONTEND_ENCRYPTION_CONTAINER;

    private Timer timer;

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

        // These are the default routes for a simple application; we use them
        // to avoid having to provide routing configuration.
        routeManager.add(RouteFactory.create(routeManager, "/_qlue/{} package:com.webkreator.qlue.pages"));
        routeManager.add(RouteFactory.create(routeManager, "/{} package:" + pagesHome));
    }

    protected void determineConfigPath() {
        // First, try a system property.
        confPath = System.getProperty(PROPERTY_CONF_PATH);
        if (confPath != null) {
            return;
        }

        // Assume the configuration is in the WEB-INF folder.
        confPath = servlet.getServletContext().getRealPath("/WEB-INF/");
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

        determineConfigPath();

        loadProperties();

        initRouteManagers();

        if (viewResolver == null) {
            throw new Exception("View resolver not configured");
        }

        if (viewFactory == null) {
            throw new Exception("View factory not configured");
        }

        viewFactory.init(this);

        Calendar nextHour = Calendar.getInstance();
        nextHour.set(Calendar.HOUR_OF_DAY, nextHour.get(Calendar.HOUR_OF_DAY) + 1);
        nextHour.set(Calendar.MINUTE, 0);
        nextHour.set(Calendar.SECOND, 0);

        scheduleTask(new SendUrgentRemindersTask(), nextHour.getTime(), 60 * 60 * 1000);
    }

    protected void initRouteManagers() throws Exception {
        File routesFile = new File(confPath, ROUTES_FILENAME);
        if (routesFile.exists()) {
            routeManager.load(routesFile);
        }
    }

    void loadProperties() throws Exception {
        File propsFile = null;

        String filename = System.getProperty("qlue.properties");
        if (filename == null) {
            filename = PROPERTIES_FILENAME;
        }

        if (filename.charAt(0) == '/') {
            propsFile = new File(filename);
        } else {
            propsFile = new File(confPath, filename);
        }

        if (propsFile.exists() == false) {
            throw new QlueException("Unable to find file: " + propsFile.getAbsolutePath());
        }

        properties.load(new FileReader(propsFile));

        // Expose confPath in properties
        properties.setProperty("confPath", confPath);

        // Expose WEB-INF path in properties
        properties.setProperty("webRoot", servlet.getServletContext().getRealPath("/"));

        if (getProperty(PROPERTY_CHARACTER_ENCODING) != null) {
            setCharacterEncoding(getProperty(PROPERTY_CHARACTER_ENCODING));
        }

        if (getProperty(PROPERTY_DEVMODE_ENABLED) != null) {
            setApplicationDevelopmentMode(getProperty(PROPERTY_DEVMODE_ENABLED));
        }

        if (getProperty(PROPERTY_DEVMODE_RANGES) != null) {
            setDevelopmentSubnets(getProperty(PROPERTY_DEVMODE_RANGES));
        }

        if (getProperty(PROPERTY_TRUSTED_PROXIES) != null) {
            setTrustedProxies(getProperty(PROPERTY_TRUSTED_PROXIES));
        }

        if (getProperty(PROPERTY_FRONTEND_ENCRYPTION) != null) {
            configureFrontendEncryption(getProperty(PROPERTY_FRONTEND_ENCRYPTION));
        }

        developmentModePassword = getProperty(PROPERTY_DEVMODE_PASSWORD);

        adminEmail = getProperty(PROPERTY_ADMIN_EMAIL);

        urgentEmail = getProperty(PROPERTY_URGENT_EMAIL);

        // Configure the SMTP email senders

        smtpEmailSender = new SmtpEmailSender();

        if (getBooleanProperty("qlue.smtp.async", "false")) {
            AsyncSmtpEmailSender myAsyncSmtpEmailSender = new AsyncSmtpEmailSender(smtpEmailSender);

            // Start a new daemon thread to send email in the background.
            Thread thread = new Thread(myAsyncSmtpEmailSender);
            thread.setDaemon(true);
            thread.start();

            asyncSmtpEmailSender = myAsyncSmtpEmailSender;
        } else {
            // All email sending is synchronous.
            asyncSmtpEmailSender = smtpEmailSender;
        }

        smtpEmailSender.setSmtpServer(getProperty("qlue.smtp.server"));
        if (getProperty("qlue.smtp.port") != null) {
            smtpEmailSender.setSmtpPort(Integer.valueOf(getProperty("qlue.smtp.port")));
        }

        if (getProperty("qlue.smtp.protocol") != null) {
            smtpEmailSender.setSmtpProtocol(getProperty("qlue.smtp.protocol"));
        }

        if (getProperty("qlue.smtp.username") != null) {
            smtpEmailSender.setSmtpUsername(getProperty("qlue.smtp.username"));
            smtpEmailSender.setSmtpPassword(getProperty("qlue.smtp.password"));
        }
    }

    private void configureFrontendEncryption(String value) {
        if ("no".equals(value)) {
            frontendEncryptionCheck = FRONTEND_ENCRYPTION_NO;
        } else if ("forceYes".equals(value)) {
            frontendEncryptionCheck = FRONTEND_ENCRYPTION_FORCE_YES;
        } else if ("container".equals(value)) {
            frontendEncryptionCheck = FRONTEND_ENCRYPTION_CONTAINER;
        } else if ("trustedHeader".equals(value)) {
            frontendEncryptionCheck = FRONTEND_ENCRYPTION_TRUSTED_HEADER;
        } else {
            throw new RuntimeException("Invalud value for the " + PROPERTY_FRONTEND_ENCRYPTION + " parameter:" + value);
        }
    }

    /**
     * Destroys application resources.
     */
    public void destroy() {
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
    protected void service(HttpServlet servlet, HttpServletRequest request, HttpServletResponse response)
            throws ServletException, java.io.IOException
    {
        // Remember when processing began
        long startTime = System.currentTimeMillis();

        // Set character encoding
        request.setCharacterEncoding(characterEncoding);

        // Create a new application session
        // object if one does not exist
        HttpSession session = request.getSession();
        synchronized (session) {
            if (session.getAttribute(QlueConstants.QLUE_SESSION_OBJECT) == null) {
                session.setAttribute(QlueConstants.QLUE_SESSION_OBJECT,
                        createNewSessionObject());
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
                log.debug("Processed request in " + (System.currentTimeMillis() - startTime));
            }
        } finally {
            // Remove logging context
            NDC.remove();
        }
    }

    protected Object route(TransactionContext context) {
        return routeManager.route(context);
    }

    /**
     * Request processing entry point.
     *
     * @param context
     * @throws ServletException
     * @throws java.io.IOException
     */
    protected void serviceInternal(TransactionContext context) throws ServletException, java.io.IOException {
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
                    PersistentPageRecord pageRecord = context.findPersistentPageRecord(pid);
                    if (pageRecord == null) {
                        throw new PersistentPageNotFoundException("Persistent page not found: " + pid);
                    }

                    // OK, got the page
                    page = pageRecord.page;

                    // If the requested persistent page no longer exists,
                    // redirect the user to where he is supposed to go
                    if (page == null) {
                        context.getResponse().sendRedirect(pageRecord.replacementUri);
                        return;
                    }
                }
            }

            // If we still don't have a page see if we can create a new one
            if (page == null) {
                Object routeObject = route(context);
                if (routeObject == null) {
                    throw new PageNotFoundException();
                } else if (routeObject instanceof View) {
                    page = new DirectViewPage((View)routeObject);
                } else if (routeObject instanceof Page) {
                    page = (Page) routeObject;
                } else {
                    throw new RuntimeException("Qlue: Unexpected router response: " + routeObject);
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
                page.determineDefaultViewName(viewResolver);
                page.setContext(context);
                page.determineCommandObject();

                // Persist persistent pages when we see a POST
                // if ((page.isPersistent()) && (context.isPost())) {
                if (page.isPersistent()) {
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
                    if ((page.getState().compareTo(Page.STATE_NEW) == 0) || (context.isPost())) {
                        page.getErrors().clear();
                        bindParameters(page, context);
                    }

                    // preServiceWithParams after parameter binding, but before init.
                    view = page.preServiceWithParams();
                    if (view == null) {
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
                }

                // Render view
                if (view != null) {
                    renderView(view, context, page);
                }

                // In development mode, append debugging
                // information to the end of the page
                masterWriteRequestDevelopmentInformation(context, page);

                // Execute page commit. This is what it sounds like,
                // an opportunity to use a simple approach to transaction
                // management for simple applications.
                if (page != null) {
                    page.commit();
                }
            }
        } catch (PersistentPageNotFoundException ppnfe) {
            // When we encounter an unknown process reference, we
            // redirect back to the site home page. Showing errors
            // is not really helpful, and may actually compel the
            // user to go back and try again (and that's not going to work).

            // No need to roll-back page here, as page has not been located yet.
            context.getResponse().sendRedirect("/");
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
        } catch (ValidationException ve) {
            if (page != null) {
                page.rollback();
            }

            // Respond to validation errors with a 400 response
            context.getResponse().sendError(HttpServletResponse.SC_BAD_REQUEST);
        } catch (QlueSecurityException se) {
            if (page != null) {
                page.rollback();
            }

            log.error("Security exception: " + context.getRequestUriWithQueryString(), se);

            // Respond to security exceptions with a 400 response
            context.getResponse().sendError(HttpServletResponse.SC_BAD_REQUEST);
        } catch (Throwable t) {
            if (page != null) {
                page.rollback();

                // Because we are about to throw an exception, which may cause
                // another page to handle this request, we need to remember
                // the current page (which is useful for debugging information,
                // etc)
                setActualPage(page);
            }

            // Don't process the exception further if the problem is caused
            // by the client going away (e.g., interrupted file download).
            if (!t.getClass().getName().contains("ClientAbortException")) {
                // Handle application exception, which will record full context
                // data and, optionally, notify the administrator via email
                handleApplicationException(context, page, t);

                // We do not wish to propagate the exception
                // further, so simply send a 500 response here (but
                // only if response headers have not been sent).
                if (context.getResponse().isCommitted() == false) {
                    if (t instanceof ServiceUnavailableException) {
                        context.getResponse().sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                    } else {
                        context.getResponse().sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                    }
                }
            }
        }
    }

    /**
     * Handle application exception. We dump debugging information into the
     * application activity log and, if the admin email address is configured,
     * we send the same via email.
     *
     * @param tx
     * @param page
     * @param t
     */
    protected void handleApplicationException(TransactionContext tx, Page page, Throwable t) {
        String debugInfo = null;

        if (tx != null) {
            // Dump debugging information into a String
            StringWriter sw = new StringWriter();
            sw.append("Debugging information follows:");

            try {
                _masterWriteRequestDevelopmentInformation(tx, page, new PrintWriter(sw));
            } catch (IOException e) {
                // Ignore (but log, in case we do get something)
                log.error("Exception while preparing debugging information", e);
            }

            // Qlue formats debugging information using HTML markup, and here
            // we want to log it to text files, which means we need to strip
            // out the markup and convert entity references.
            HtmlToText htt = new HtmlToText();
            try {
                htt.parse(new StringReader(sw.getBuffer().toString()));
                debugInfo = htt.toString();
            } catch (IOException e) {
                log.error("Error while converting HTML", e);
            }
        }

        // Record message to the activity log
        log.error("Application exception", t);
        if (debugInfo != null) {
            log.error(debugInfo);
        }

        // Send email notification
        try {
            Email email = new SimpleEmail();
            email.setCharset("UTF-8");

            if (t.getMessage() != null) {
                email.setSubject("Application Exception: " + t.getMessage());
            } else {
                email.setSubject("Application Exception");
            }

            StringWriter msgBody = new StringWriter();
            PrintWriter pw = new PrintWriter(msgBody);

            t.printStackTrace(pw);
            pw.println();

            if (debugInfo != null) {
                pw.print(debugInfo);
            }

            email.setMsg(msgBody.toString());

            sendAdminEmail(email, true /* fatalError */);
        } catch (Exception e) {
            log.error("Failed sending admin email: ", e);
        }
    }

    public synchronized void sendAdminEmail(Email email) {
        sendAdminEmail(email, false);
    }

    public synchronized void sendAdminEmail(Email email, boolean fatalError) {
        if (adminEmail == null) {
            return;
        }

        // Configure the correct email address.
        try {
            email.setFrom(adminEmail);

            // If this is a fatal error and we have an
            // email address for emergencies, treat it
            // as an emergency.
            if ((fatalError)&&(urgentEmail != null)) {
                email.addTo(urgentEmail);
            } else {
                email.addTo(adminEmail);
            }
        } catch (EmailException e) {
            log.error("Invalid admin email address", e);
        }

        // Update the email subject to include the application prefix.
        email.setSubject("[" + getAppPrefix() + "] " + email.getSubject());

        // If the email is about a fatal problem, determine
        // if we want to urgently notify the administrators; we
        // want to send only one urgent email per time period.
        if ((fatalError)&&(urgentEmail != null)) {
            // When the counter is at -1 that means we didn't
            // send any emails in the previous time period. In
            // other words, we can send one now.
            if (urgentCounter == -1) {
                urgentCounter = 0;
            } else {
                // Alternatively, just increment the counter
                // and send nothing.
                urgentCounter++;

                log.info("Suppressing fatal error email (" + urgentCounter + "): " + email.getSubject());

                return;
            }
        }

        // Send the email now.
        try {
            getEmailSender().send(email);
        } catch (Exception e) {
            log.error("Failed to send email", e);
        }
    }

    public void renderView(View view, TransactionContext tx, Page page) throws Exception {
        // NullView only indicates that no further output should be made.
        if (view instanceof NullView) {
            return;
        }

        // If we get a DefaultView or NamedView instance
        // we have to replace them with a real view, using
        // the name of the page in the view resolution process.
        if (view instanceof DefaultView) {
            view = viewFactory.constructView(page, page.getViewName());
        } else if (view instanceof NamedView) {
            view = viewFactory.constructView(page, ((NamedView)view).getViewName());
        } else if (view instanceof ClasspathView) {
            view = viewFactory.constructView(((ClasspathView)view).getViewName());
        } else if (view instanceof FinalRedirectView) {
            page.setState(Page.STATE_FINISHED);

            if (((RedirectView) view).getPage() == null) {
                page.context.replacePage(page, (FinalRedirectView) view);
            }
        }

        if (view == null) {
            throw new RuntimeException("Qlue: Unable to resolve view");
        }

        // Render the view now.
        try {
            view.render(tx, page);
        } catch (Throwable t) {
            // Ignore exceptions during view rendering.
            t.printStackTrace(System.err);
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

        // Loop through the command object fields in order to determine
        // if any are annotated as parameters. Remember the original
        // text values of parameters.
        Field[] fields = commandObject.getClass().getFields();
        for (Field f : fields) {
            if (f.isAnnotationPresent(QlueParameter.class)) {
                if (QlueFile.class.isAssignableFrom(f.getType())) {
                    continue;
                }

                // Update missing shadow input fields
                if (page.getShadowInput().get(f.getName()) == null) {
                    if (f.getType().isArray()) {
                        updateShadowInputArrayParam(page, context, f);
                    } else {
                        updateShadowInputNonArrayParam(page, context, f);
                    }
                }
            }
        }
    }

    private void updateShadowInputArrayParam(Page page, TransactionContext context, Field f) throws Exception {
        // Find the property editor
        PropertyEditor pe = editors.get(f.getType().getComponentType());
        if (pe == null) {
            throw new RuntimeException("Qlue: Binding does not know how to handle type: " + f.getType().getComponentType());
        }

        // If there is any data in the command object
        // use it to populate shadow input
        if (f.get(page.getCommandObject()) != null) {
            Object[] originalValues = (Object[]) f.get(page.getCommandObject());
            String[] textValues = new String[originalValues.length];

            for (int i = 0; i < originalValues.length; i++) {
                textValues[i] = pe.toText(originalValues[i]);
            }

            page.getShadowInput().set(f.getName(), textValues);
        }
    }

    private void updateShadowInputNonArrayParam(Page page, TransactionContext context, Field f) throws Exception {
        // Find the property editor
        PropertyEditor pe = editors.get(f.getType());
        if (pe == null) {
            throw new RuntimeException(
                    "Qlue: Binding does not know how to handle type: "
                            + f.getType());
        }

        // If the object exists, convert it to
        // text using the property editor
        Object o = f.get(page.getCommandObject());
        if (o != null) {
            page.getShadowInput().set(f.getName(), pe.toText(o));
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
    protected void masterWriteRequestDevelopmentInformation(TransactionContext context, Page page) throws IOException {
        if (page == null) {
            return;
        }

        // Check development mode
        if (page.isDevelopmentMode() == false) {
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

        // Ignore redirections; RedirectView knows to display development
        // information before redirects, which is why we don't need
        // to worry here.
        int status = context.response.getStatus();
        if ((status >= 300) && (status <= 399)) {
            return;
        }

        // Ignore responses other than text/html; we don't want to
        // corrupt images and other resources that are not pages.
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
        _masterWriteRequestDevelopmentInformation(context, page, context.response.getWriter());
    }

    protected void _masterWriteRequestDevelopmentInformation(TransactionContext context, Page page, PrintWriter out) throws IOException {
        if (page == null) {
            return;
        }

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
    private void bindParameters(Page page, TransactionContext context) throws Exception {
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
            if (f.isAnnotationPresent(QlueParameter.class) == false) {
                continue;
            }

            try {
                QlueParameter qp = f.getAnnotation(QlueParameter.class);

                if (qp.state().compareTo(Page.STATE_URL) == 0) {
                    // Bind parameters transported in URL
                    bindParameterFromString(commandObject, f, page, context, context.getUrlParameter(f.getName()));
                } else {
                    // Process only the parameters that are
                    // in the same state as the page, or if the parameter
                    // uses the special state POST, which triggers on all
                    // POST requests (irrespective of the state).

                    if ( ((qp.state().compareTo(Page.STATE_POST) == 0) && (page.context.isPost()))
                         || (qp.state().compareTo(Page.STATE_NEW_OR_POST) == 0)
                         || (qp.state().compareTo(page.getState()) == 0))
                    {
                        // We have a parameter; dispatch
                        // to the appropriate handler.
                        if (f.getType().isArray()) {
                            bindArrayParameter(commandObject, f, page, context);
                        } else {
                            bindNonArrayParameter(commandObject, f, page, context);
                        }
                    }
                }
            } catch (InvalidParameterException e) {
                // Transform editor exception into a validation error
                page.addError(f.getName(), e.getMessage());
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
    private void bindArrayParameter(Object commandObject, Field f, Page page, TransactionContext context) throws Exception {
        // Find shadow input
        ShadowInput shadowInput = page.getShadowInput();

        // Get the annotation
        QlueParameter qp = f.getAnnotation(QlueParameter.class);

        // Look for a property editor, which will know how
        // to convert text into a proper native type
        PropertyEditor pe = editors.get(f.getType().getComponentType());
        if (pe == null) {
            throw new RuntimeException("Qlue: Binding does not know how to handle type: " + f.getType().getComponentType());
        }

        String[] values = context.getParameterValues(f.getName());
        if ((values == null) || (values.length == 0)) {
            // Parameter not in input; create an empty array
            // and set it on the command object.
            f.set(commandObject, Array.newInstance(f.getType().getComponentType(), 0));
            return;
        }

        // Parameter in input

        shadowInput.set(f.getName(), values);

        boolean hasErrors = false;
        Object[] convertedValues = (Object[]) Array.newInstance(f.getType().getComponentType(), values.length);
        for (int i = 0; i < values.length; i++) {
            String newValue = validateParameter(page, f, qp, values[i]);
            if (newValue != null) {
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
    protected String validateParameter(Page page, Field f, QlueParameter qp, String value) {
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
                    throw new RuntimeException("Qlue: Invalid parameter transformation function: " + t);
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
                throw new RuntimeException("Qlue: Invalid parameter validation pattern: " + qp.pattern());
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
    private void bindNonArrayParameter(Object commandObject, Field f, Page page, TransactionContext context) throws Exception {
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
            throw new RuntimeException("Qlue: Binding does not know how to handle type: " + f.getType());
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
                f.set(commandObject, pe.fromText(f, value, f.get(commandObject)));
            }
        } else {
            f.set(commandObject, pe.fromText(f, value, f.get(commandObject)));

            // We are here if the parameter is not in the request, in which
            // case we need to check of the parameter is mandatory
            if (qp.mandatory()) {
                page.addError(f.getName(), getFieldMissingMessage(qp));
            }
        }
    }

    private void bindParameterFromString(Object commandObject, Field f, Page page, TransactionContext context, String value) throws Exception {
        // Find shadow input
        ShadowInput shadowInput = page.getShadowInput();

        // Get the annotation
        QlueParameter qp = f.getAnnotation(QlueParameter.class);

        // First check if the parameter is a file
        if (QlueFile.class.isAssignableFrom(f.getType())) {
            throw new RuntimeException("Qlue: Unable to bind a string to file parameter");
        }

        // Look for a property editor, which will know how
        // to convert text into a native type
        PropertyEditor pe = editors.get(f.getType());
        if (pe == null) {
            throw new RuntimeException("Qlue: Binding does not know how to handle type: " + f.getType());
        }

        // Keep track of the original text parameter value
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
                f.set(commandObject, pe.fromText(f, value, f.get(commandObject)));
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
        return (qp.fieldMissingMessage().length() > 0) ? qp.fieldMissingMessage() : "qlue.validation.mandatory";
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
    private void bindFileParameter(Object commandObject, Field f, Page page, TransactionContext context) throws Exception {
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
        registerPropertyEditor(new LongEditor());
        registerPropertyEditor(new StringEditor());
        registerPropertyEditor(new BooleanEditor());
        registerPropertyEditor(new DateEditor());
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
     * instance of DefaultVelocityTool, but subclasses can use something else.
     */
    public Object getVelocityTool() {
        return new DefaultVelocityTool();
    }

    /**
     * This method is invoked to create a new session object. A QlueSession
     * instance is returned by default, but most applications will want to
     * override this method and provide their own session objects.
     *
     * @return new session object
     */
    protected QlueSession createNewSessionObject() {
        return new QlueSession();
    }

    /**
     * Returns the session object associated with the current HTTP session.
     *
     * @param request
     * @return
     */
    public QlueSession getQlueSession(HttpServletRequest request) {
        return (QlueSession) request.getSession().getAttribute(QlueConstants.QLUE_SESSION_OBJECT);
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
        QluePageManager pageManager = (QluePageManager) request.getSession().getAttribute(QlueConstants.QLUE_SESSION_PAGE_MANAGER);
        request.getSession().invalidate();
        request.getSession(true).setAttribute(QlueConstants.QLUE_SESSION_OBJECT, qlueSession);
        request.getSession().setAttribute(QlueConstants.QLUE_SESSION_PAGE_MANAGER, pageManager);
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
     * @param input
     */
    protected void setApplicationDevelopmentMode(String input) {
        if (input.compareToIgnoreCase("on") == 0) {
            developmentMode = QlueConstants.DEVMODE_ENABLED;
            return;
        } else if (input.compareToIgnoreCase("off") == 0) {
            developmentMode = QlueConstants.DEVMODE_DISABLED;
            return;
        } else if (input.compareToIgnoreCase("ondemand") == 0) {
            developmentMode = QlueConstants.DEVMODE_ONDEMAND;
            return;
        }

        throw new InvalidParameterException("Invalid value for development mode: " + input);
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

    private void setTrustedProxies(String combinedSubnets) throws Exception {
        if (TextUtil.isEmpty(combinedSubnets)) {
            return;
        }

        String[] subnets = combinedSubnets.split("[;,\\x20]");

        trustedProxies = new IpRangeFilter[subnets.length];
        int count = 0;
        for (String s : subnets) {
            if (TextUtil.isEmpty(s)) {
                continue;
            }

            String subnet = s;
            if (s.contains("/") == false) {
                subnet = s + "/32";
            }

            try {
                trustedProxies[count++] = new IpRangeFilter(subnet);
            } catch (IllegalArgumentException iae) {
                throw new RuntimeException("Qlue: Invalid proxy subnet: " + s);
            }
        }
    }

    public boolean isTrustedProxyRequest(TransactionContext context) {
        if (trustedProxies == null) {
            return false;
        }

        InetAddress remoteAddr;
        try {
            remoteAddr = InetAddress.getByName(context.request.getRemoteAddr());
        } catch (Exception e) {
            return false;
        }

        for (IpRangeFilter su : trustedProxies) {
            if (su == null) {
                continue;
            }

            if (su.isInRange(remoteAddr)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Configure the set of IP addresses that are allowed to use development
     * mode.
     *
     * @param combinedSubnets
     */
    protected void setDevelopmentSubnets(String combinedSubnets) throws Exception {
        if (TextUtil.isEmpty(combinedSubnets)) {
            return;
        }

        String[] subnets = combinedSubnets.split("[;,\\x20]");

        developmentSubnets = new IpRangeFilter[subnets.length];
        int count = 0;
        for (String s : subnets) {
            if (TextUtil.isEmpty(s)) {
                continue;
            }

            String subnet = s;
            if (s.contains("/") == false) {
                subnet = s + "/32";
            }

            try {
                developmentSubnets[count++] = new IpRangeFilter(subnet);
            } catch (IllegalArgumentException iae) {
                throw new RuntimeException("Qlue: Invalid development subnet: " + s);
            }
        }
    }

    /**
     * Check if the current transaction comes from an IP address that is allowed
     * to use development mode.
     *
     * @param context
     * @return
     */
    public boolean isDeveloperRequest(TransactionContext context) {
        if (developmentSubnets == null) {
            return false;
        }

        InetAddress remoteAddr;
        try {
            remoteAddr = InetAddress.getByName(context.getEffectiveRemoteAddr());
        } catch (Exception e) {
            return false;
        }

        for (IpRangeFilter su : developmentSubnets) {
            if (su == null) {
                continue;
            }

            if (su.isInRange(remoteAddr)) {
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
    public boolean isDevelopmentMode(TransactionContext context) {
        // Check IP address first
        if (isDeveloperRequest(context) == false) {
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
        return VariableExpander.expand(properties.getProperty(key), properties);
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
        String value = getProperty(key);
        if (value != null) {
            return value;
        } else {
            return defaultValue;
        }
    }

    public Boolean getBooleanProperty(String key) {
        String value = getProperty(key);
        if (value == null) {
            return null;
        }

        return Boolean.parseBoolean(value);
    }

    public Boolean getBooleanProperty(String key, String defaultValue) {
        String value = getProperty(key);
        if (value == null) {
            return Boolean.parseBoolean(defaultValue);
        }

        return Boolean.parseBoolean(value);
    }

    /**
     * Retrieve a single integer property.
     *
     * @param key
     * @return
     */
    public Integer getIntProperty(String key) {
        String value = getProperty(key);
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
        String value = getProperty(key);
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
        MessageSource source = messageSources.get(locale);
        if (source == null) {
            source = new MessageSource((PropertyResourceBundle) ResourceBundle.getBundle(messagesFilename, locale), locale);
            messageSources.put(locale, source);
        }

        return source;
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
        return (Page) currentPage.context.request.getAttribute(REQUEST_ACTUAL_PAGE_KEY);
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

    public EmailSender getEmailSender() {
        return asyncSmtpEmailSender;
    }

    public EmailSender getAsyncEmailSender() {
        return asyncSmtpEmailSender;
    }

    public EmailSender getSyncEmailSender() {
        return smtpEmailSender;
    }

    public String getConfPath() {
        return confPath;
    }

    public int getFrontendEncryptionCheck() {
        return frontendEncryptionCheck;
    }

    public String getAdminEmail() {
        return adminEmail;
    }

    protected void scheduleTask(Runnable maintenanceTask, Date firstTime, long period) {
        if (timer == null) {
            timer = new Timer();
        }

        timer.scheduleAtFixedRate(new RunnableTaskWrapper(maintenanceTask), firstTime, period);
    }

    private class SendUrgentRemindersTask implements Runnable {

        @Override
        public void run() {
            try {
                if ((adminEmail == null) || (urgentEmail == null) || (urgentCounter < 0)) {
                    return;
                }

                log.info("Sending urgent reminder: urgentCounter=" + urgentCounter);

                if (urgentCounter == 0) {
                    // Nothing has happened in the last period; setting
                    // the counter to -1 means that the next exception
                    // will send an urgent email immediately.
                    urgentCounter = -1;
                } else {
                    // There were a number of exceptions in the last period,
                    // which means that we should send a reminder email.
                    Email email = new SimpleEmail();
                    email.setCharset("UTF-8");
                    email.setFrom(adminEmail);
                    email.addTo(urgentEmail);
                    email.setSubject("[" + getAppPrefix() + "] " + "Suppressed " + urgentCounter + " exception(s) in the last period");

                    try {
                        getEmailSender().send(email);
                        urgentCounter = 0;
                    } catch (Exception e) {
                        log.error("Failed to send email", e);
                    }
                }
            } catch (Throwable t) {
                log.error(t);
            }
        }
    }

    private class RunnableTaskWrapper extends TimerTask {

        private Runnable task;

        RunnableTaskWrapper(Runnable task) {
            this.task = task;
        }

        @Override
        public void run() {
            task.run();
        }
    }
}
