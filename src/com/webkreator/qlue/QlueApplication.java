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
import org.apache.commons.mail.Email;
import org.apache.commons.mail.EmailException;
import org.apache.commons.mail.SimpleEmail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import javax.servlet.ServletException;
import javax.servlet.http.*;
import java.io.*;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
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

    private Logger log = LoggerFactory.getLogger(QlueApplication.class);

    private QlueRouteManager routeManager = new QlueRouteManager(this);

    private ViewResolver viewResolver = new ViewResolver();

    private ViewFactory viewFactory = new ClasspathVelocityViewFactory();

    private HashMap<Class, PropertyEditor> editors = new HashMap<>();

    private String characterEncoding = "UTF-8";

    private int developmentMode = QlueConstants.DEVMODE_DISABLED;

    private String developmentModePassword = null;

    private List<CIDRUtils> developmentSubnets = null;

    private List<CIDRUtils> trustedProxies = null;

    private String adminEmail;

    private String urgentEmail;

    private int urgentCounter = -1;

    private SmtpEmailSender smtpEmailSender;

    private SmtpEmailSender asyncSmtpEmailSender;

    private HashMap<Locale, MessageSource> messageSources = new HashMap<>();

    private String confPath;

    private int frontendEncryptionCheck = FRONTEND_ENCRYPTION_CONTAINER;

    private Timer timer;

    private String priorityTemplatePath;

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

    /**
     * Initialize QlueApp instance. Qlue applications are designed to be used by
     * servlets to delegate both initialization and request processing.
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
        File propsFile;

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

        properties.setProperty("confPath", confPath);
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

        priorityTemplatePath = getProperty("qlue.velocity.priorityTemplatePath");
        if (priorityTemplatePath != null) {
            Path p = FileSystems.getDefault().getPath(priorityTemplatePath);
            if (!p.isAbsolute()) {
                priorityTemplatePath = getApplicationRoot() + "/" + priorityTemplatePath;
            }

            File f = new File(priorityTemplatePath);
            if (!f.exists()) {
                throw new QlueException("Priority template path doesn't exist: " + priorityTemplatePath);
            }

            if (!f.isDirectory()) {
                throw new QlueException("Priority template path is not a directory: " + priorityTemplatePath);
            }
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
            throw new RuntimeException("Invalid value for the " + PROPERTY_FRONTEND_ENCRYPTION + " parameter:" + value);
        }
    }

    /**
     * Destroys the application. Invoked when the backing servlet is destroyed.
     */
    public void destroy() {
    }

    /**
     * This method is the main entry point for request processing.
     */
    protected void service(HttpServlet servlet, HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException
    {
        // Remember when processing began.
        long startTime = System.currentTimeMillis();

        // Set the default character encoding.
        request.setCharacterEncoding(characterEncoding);

        // Create a new application session object if one does not exist.
        HttpSession session = request.getSession();
        synchronized (session) {
            if (session.getAttribute(QlueConstants.QLUE_SESSION_OBJECT) == null) {
                session.setAttribute(QlueConstants.QLUE_SESSION_OBJECT,
                        createNewSessionObject());
            }
        }

        // Create a new context.
        TransactionContext context = new TransactionContext(
                this,
                servlet.getServletConfig(),
                servlet.getServletContext(),
                request,
                response);

        // Create a logging context using the unique transaction ID.
        MDC.put("txId", Integer.toString(context.getTxId()));

        // Proceed to the second stage of request processing
        try {
            log.debug("Processing request: " + request.getRequestURI());
            serviceInternal(context);
            log.debug("Processed request in " + (System.currentTimeMillis() - startTime));
        } finally {
            MDC.clear();
        }
    }

    protected Object route(TransactionContext context) {
        return routeManager.route(context);
    }

    protected View processPage(Page page) throws Exception {
        // Check access. The idea with this hook is to run it as early as possible,
        // before any parameters are accessed, thus minimising the executed code.
        View view = page.checkAccess();
        if (view != null) {
            return view;
        }

        // Parameter binding always runs for non-persistent pages. For
        // persistent pages, it runs only on POST requests.
        if (!page.isPersistent() || (page.context.isPost())) {
            page.getErrors().clear();
            updateShadowInput(page);
            bindParameters(page);
        }

        // Run custom parameter validation.
        view = page.validateParameters();
        if (view != null) {
            return view;
        }

        // Handle validation errors, if any.
        if (page.hasErrors()) {
            view = page.handleValidationError();
            if (view != null) {
                return view;
            }
        }

        // Initialize the page. This really only makes sense for persistent pages, where you
        // want to run some code only once. With non-persistent pages, it's better to have
        // all the code in the same method.
        if (page.getState().equals(Page.STATE_NEW)) {
            view = page.init();
            if (view != null) {
                return view;
            }
        }

        // Early call to prepare the page for the main thing.
        view = page.prepareForService();
        if (view != null) {
            return view;
        }

        // Finally, run the main processing entry point.
        return page.service();
    }

    /**
     * Request processing entry point.
     */
    protected void serviceInternal(TransactionContext context) throws IOException {
        Page page = null;

        try {
            // First check if this is a request for a persistent page. We can
            // honour such requests only when we're not handling errors.

            if (context.isErrorHandler() == false) {
                // Perisistent pages are identified via the "_pid" parameter. If we have
                // one such parameter, we look for the corresponding page in session storage.
                String pids[] = context.getParameterValues("_pid");
                if ((pids != null)&&(pids.length != 0)) {
                    // Only one _pid parameter is allowed.
                    if (pids.length != 1) {
                        throw new RuntimeException("Request contains multiple _pid parameters");
                    }

                    // Find the page using the requested page ID.
                    PersistentPageRecord pageRecord = context.findPersistentPageRecord(pids[0]);
                    if (pageRecord == null) {
                        throw new PersistentPageNotFoundException("Persistent page not found: " + pids[0]);
                    }

                    // If the replacementUri is set that means that the page no longer
                    // exist and that we need to forward all further request to it.
                    if (pageRecord.replacementUri != null) {
                        context.getResponse().sendRedirect(pageRecord.replacementUri);
                        return;
                    }

                    // Otherwise, let's use this page.
                    page = pageRecord.page;
                    if (page == null) {
                        throw new RuntimeException("Page record doesn't contain page");
                    }
                }
            }

            // If we don't have a persistent page we'll create a new one by routing this request.

            if (page == null) {
                Object routeObject = route(context);
                if (routeObject == null) {
                    throw new PageNotFoundException();
                } else if (routeObject instanceof View) {
                    page = new DirectViewPage((View) routeObject);
                } else if (routeObject instanceof Page) {
                    page = (Page) routeObject;
                } else {
                    throw new RuntimeException("Qlue: Unexpected router response: " + routeObject);
                }
            }

            // Run the page. Access to the page is synchronised, which means that only one
            // HTTP request can handle it at any given time.

            synchronized (page) {
                page.setApp(this);
                page.determineDefaultViewName(viewResolver);
                page.setContext(context);
                page.determineCommandObject();

                if (page.isPersistent()) {
                    context.persistPage(page);
                }

                View view = processPage(page);
                if (view != null) {
                    renderView(view, context, page);
                }

                // Execute page commit. This is what it sounds like,
                // an opportunity to use a simple approach to transaction
                // management for simple applications.
                page.commit();

                // Automatic page state transition.
                if (!page.isPersistent()) {
                    // Non-persistent pages automatically transition to FINISHED so that cleanup can be invoked.
                    page.setState(Page.STATE_FINISHED);
                } else {
                    // For persistent pages, we change their state only if they're left as NEW
                    // after execution. We change to POSTED in order to prevent multiple calls to init().
                    if (page.getState().equals(Page.STATE_NEW)) {
                        page.setState(Page.STATE_ACTIVATED);
                    }
                }
            }
        } catch (PersistentPageNotFoundException ppnfe) {
            // When we encounter an unknown process reference, we
            // redirect back to the site home page. Showing errors
            // is probably not going to be helpful, and may actually compel the
            // user to go back and try again (and that's not going to work).
            context.getResponse().sendRedirect("/");
        } catch (RequestMethodException rme) {
            if (page != null) {
                if (page.isDevelopmentMode()) {
                    log.error(rme.getMessage(), rme);
                }

                page.rollback();
            }

            // Convert RequestMethodException into a 405 response.
            context.getResponse().sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
        } catch (PageNotFoundException pnfe) {
            if (page != null) {
                if (page.isDevelopmentMode()) {
                    log.error(pnfe.getMessage(), pnfe);
                }

                page.rollback();
            }

            // Convert PageNotFoundException into a 404 response.
            context.getResponse().sendError(HttpServletResponse.SC_NOT_FOUND);
        } catch (ValidationException ve) {
            if (!page.isDevelopmentMode()) {
                if (page != null) {
                    page.rollback();
                }

                // Respond to validation errors with a 400 response.
                context.getResponse().sendError(HttpServletResponse.SC_BAD_REQUEST);
            } else {
                // In development mode, we let the exception propagate to that it
                // can be handled by our generic exception handler, which will show
                // the error information on the screen.
                throw ve;
            }
        } catch (QlueSecurityException se) {
            if (page != null) {
                page.rollback();
            }

            log.error("Security exception: " + context.getRequestUriWithQueryString(), se);

            // Respond to security exceptions with a 400 response.
            context.getResponse().sendError(HttpServletResponse.SC_BAD_REQUEST);
        } catch (Throwable t) {
            if (page != null) {
                page.rollback();

                // Because we are about to throw an exception, which may cause
                // another page to handle this request, we need to remember
                // the current page (which is useful for debugging information, etc).
                setActualPage(page);
            }

            // Don't process the exception further if the problem is caused
            // by the client going away (e.g., interrupted file download).
            if (!t.getClass().getName().contains("ClientAbortException")) {
                // Handle application exception, which will record full context
                // data and, optionally, notify the administrator via email.
                handleApplicationException(context, page, t);

                // We do not wish to propagate the exception further, so simply send a 500 response
                // here (but only if response headers have not been sent).
                if (context.getResponse().isCommitted() == false) {
                    if (t instanceof ServiceUnavailableException) {
                        context.getResponse().sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                    } else {
                        context.getResponse().sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                    }
                }
            }
        } finally {
            // Invoke cleanup on finished pages.
            if ((page != null)&&(page.isFinished())&&(!page.isCleanupInvoked())) {
                page.cleanup();
            }

            // In development mode, append debugging information to the end of the page.
            masterWriteRequestDevelopmentInformation(context, page);
        }
    }

    /**
     * Handle application exception. We dump debugging information into the
     * application activity log and, if the admin email address is configured,
     * we send the same via email.
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
            if ((fatalError) && (urgentEmail != null)) {
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
        if ((fatalError) && (urgentEmail != null)) {
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
        // NullView only indicates that no further output is needed.
        if (view instanceof NullView) {
            return;
        }

        // If we get a DefaultView or NamedView instance
        // we have to replace them with a real view, using
        // the name of the page in the view resolution process.
        if (view instanceof DefaultView) {
            view = viewFactory.constructView(page, page.getViewName());
        } else if (view instanceof NamedView) {
            view = viewFactory.constructView(page, ((NamedView) view).getViewName());
        } else if (view instanceof ClasspathView) {
            view = viewFactory.constructView(((ClasspathView) view).getViewName());
        } else if (view instanceof FinalRedirectView) {
            page.setState(Page.STATE_FINISHED);

            if (((RedirectView) view).getPage() == null) {
                page.context.replacePage(page, (FinalRedirectView) view);
            }
        }

        if (view == null) {
            throw new RuntimeException("Qlue: Unable to resolve view");
        }

        try {
            view.render(tx, page);
        } catch (Throwable t) {
            t.printStackTrace(System.err);
        }
    }

    /**
     * Invoked to store the original text values for parameters. The text is
     * needed in the cases where it cannot be converted to the intended type.
     */
    private void updateShadowInput(Page page) throws Exception {
        // Ask the page to provide a command object, which can be
        // a custom object or the page itself.
        Object commandObject = page.getCommandObject();
        if (commandObject == null) {
            throw new RuntimeException("Qlue: Command object cannot be null.");
        }

        // Loop through the command object fields in order to determine
        // if any are annotated as parameters. Remember the original
        // text values of parameters.
        //Field[] fields = commandObject.getClass().getFields();
        Set<Field> fields = getClassPublicFields(commandObject.getClass());
        for (Field f : fields) {
            if (f.isAnnotationPresent(QlueParameter.class)) {
                if (QlueFile.class.isAssignableFrom(f.getType())) {
                    continue;
                }

                if (!Modifier.isPublic(f.getModifiers())) {
                    throw new QlueException("QlueParameter used on a non-public field");
                }

                // Update missing shadow input fields
                if (page.getShadowInput().get(f.getName()) == null) {
                    if (f.getType().isArray()) {
                        updateShadowInputArrayParam(page, f);
                    } else {
                        updateShadowInputNonArrayParam(page, f);
                    }
                }
            }
        }
    }

    private void updateShadowInputArrayParam(Page page, Field f) throws Exception {
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

    private void updateShadowInputNonArrayParam(Page page, Field f) throws Exception {
        // Find the property editor
        PropertyEditor pe = editors.get(f.getType());
        if (pe == null) {
            throw new RuntimeException("Qlue: Binding does not know how to handle type: " + f.getType());
        }

        // If the object exists, convert it to
        // text using the property editor
        Object o = f.get(page.getCommandObject());
        if (o != null) {
            page.getShadowInput().set(f.getName(), pe.toText(o));
        }
    }

    /**
     * Appends debugging information to the view, but only if the development mode is active.
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
        if (contentType != null) {
            int i = contentType.indexOf(';');
            if (i != -1) {
                contentType = contentType.substring(0, i);
            }

            if (contentType.compareToIgnoreCase("text/html") != 0) {
                return;
            }
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
     */
    protected void writeDevelopmentInformation(PrintWriter out) {
        out.println(" Prefix: " + HtmlEncoder.encodeForHTML(appPrefix));
        out.println(" Page ID counter: " + txIdsCounter);
        out.println(" Development mode: " + developmentMode);
    }

    protected Set<Field> getClassPublicFields(Class klass) {
        Set<Field> fields = new HashSet<>();

        for(;;) {
            Field[] fs = klass.getDeclaredFields();
            for (Field f : fs) {
                fields.add(f);
            }

            klass = klass.getSuperclass();
            if (klass == null) {
                break;
            }

            if (klass.getCanonicalName().equals(Page.class.getCanonicalName())) {
                break;
            }
        }

        return fields;
    }

    /**
     * Bind request parameters to the command object provided by the page.
     */
    private void bindParameters(Page page) throws Exception {
        // Ask the page to provide a command object we can bind to. Simpler pages
        // might see themselves as the command objects; more complex might use more than one.
        Object commandObject = page.getCommandObject();
        if (commandObject == null) {
            throw new RuntimeException("Qlue: Command object cannot be null");
        }

        // Loop through the command object fields in order to determine if any are annotated as
        // parameters. Validate those that are, then bind them.
        //Field[] fields = commandObject.getClass().getDeclaredFields();
        Set<Field> fields = getClassPublicFields(commandObject.getClass());
        for (Field f : fields) {
            // We bind command object fields that have the QlueParameter annotation.
            if (f.isAnnotationPresent(QlueParameter.class) == false) {
                continue;
            }

            // We bind only to public fields, but it commonly happens that the QlueParameter
            // annotation is used on other field types, leading to frustration because it's
            // not obvious why binding is not working. For this reason, we detect that problem
            // here and force an error to inform the developer.
            if (!Modifier.isPublic(f.getModifiers())) {
                throw new QlueException("QlueParameter used on a non-public field");
            }

            try {
                QlueParameter qp = f.getAnnotation(QlueParameter.class);

                // Bind this parameter only if it matches any state, or if it matches the page's current state.
                if (qp.state().equals(Page.STATE_ANY)||(qp.state().equals(page.getState()))) {

                    if (qp.source().equals(ParamSource.URL)) {
                        // Bind parameters transported in URL. For this to work there needs
                        // to exist a route that parses out the parameter out of the URL.
                        bindParameterFromString(commandObject, f, page, page.context.getUrlParameter(f.getName()));
                    } else {
                        if (qp.source().equals(ParamSource.GET_POST)
                            || (qp.source().equals(ParamSource.GET) && page.context.isGet())
                            || (qp.source().equals(ParamSource.POST) && page.context.isPost()))
                        {
                            if (f.getType().isArray()) {
                                bindArrayParameter(commandObject, f, page);
                            } else {
                                bindNonArrayParameter(commandObject, f, page);
                            }
                        }
                    }
                }
            } catch (IllegalArgumentException e) {
                // Transform editor exception into a validation error.
                page.addError(f.getName(), e.getMessage());
            }
        }
    }

    /**
     * Bind an array parameter.
     */
    private void bindArrayParameter(Object commandObject, Field f, Page page) throws Exception {
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

        String[] values = page.context.getParameterValues(f.getName());
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
     */
    private void bindNonArrayParameter(Object commandObject, Field f, Page page) throws Exception {
        // Find shadow input
        ShadowInput shadowInput = page.getShadowInput();

        // Get the annotation
        QlueParameter qp = f.getAnnotation(QlueParameter.class);

        // First check if the parameter is a file
        if (QlueFile.class.isAssignableFrom(f.getType())) {
            bindFileParameter(commandObject, f, page);
            return;
        }

        // Look for a property editor, which will know how
        // to convert text into a native type
        PropertyEditor pe = editors.get(f.getType());
        if (pe == null) {
            throw new RuntimeException("Qlue: Binding does not know how to handle type: " + f.getType());
        }

        // Keep track of the original text parameter value
        String value = page.context.getParameter(f.getName());
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

    private void bindParameterFromString(Object commandObject, Field f, Page page, String value) throws Exception {
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
     */
    private String getFieldMissingMessage(QlueParameter qp) {
        return (qp.fieldMissingMessage().length() > 0) ? qp.fieldMissingMessage() : "qlue.validation.mandatory";
    }

    /**
     * Bind file parameter.
     */
    private void bindFileParameter(Object commandObject, Field f, Page page) throws Exception {
        QlueParameter qp = f.getAnnotation(QlueParameter.class);

        Part p = null;

        try {
            p = page.context.getPart(f.getName());
        } catch(ServletException e) {}

        if ((p == null) || (p.getSize() == 0)) {
            if (qp.mandatory()) {
                page.addError(f.getName(), getFieldMissingMessage(qp));
            }

            return;
        }

        File file = File.createTempFile("qlue-", ".tmp");
        p.write(file.getAbsolutePath());
        p.delete();

        QlueFile qf = new QlueFile(file.getAbsolutePath());
        qf.setContentType(p.getContentType());
        qf.setSubmittedFilename(p.getSubmittedFileName());

        f.set(commandObject, qf);
    }

    /**
     * Register a new property editor.
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
     */
    public ViewResolver getViewResolver() {
        return viewResolver;
    }

    /**
     * Set view resolver.
     */
    protected void setViewResolver(ViewResolver viewResolver) {
        this.viewResolver = viewResolver;
    }

    /**
     * Retrieve view factory.
     */
    public ViewFactory getViewFactory() {
        return viewFactory;
    }

    /**
     * Set view factory.
     */
    protected void setViewFactory(ViewFactory viewFactory) {
        this.viewFactory = viewFactory;
    }

    /**
     * Get application root directory.
     */
    public String getApplicationRoot() {
        return servlet.getServletContext().getRealPath("/");
    }

    /**
     * Get application prefix.
     */
    public String getAppPrefix() {
        return appPrefix;
    }

    /**
     * Set application prefix.
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
     * Retrieve an encoding tool the application can use to write directly to HTML.
     */
    public Object getEncodingTool() {
        return new HtmlEncoder();
    }

    /**
     * This method is invoked to create a new session object. A QlueSession
     * instance is returned by default, but most applications will want to
     * override this method and provide their own session objects.
     */
    protected QlueSession createNewSessionObject() {
        return new QlueSession();
    }

    /**
     * Returns the session object associated with the current HTTP session.
     */
    public QlueSession getQlueSession(HttpServletRequest request) {
        return (QlueSession) request.getSession().getAttribute(QlueConstants.QLUE_SESSION_OBJECT);
    }

    /**
     * Invalidates the existing session and creates a new one, preserving the
     * QlueSession object in the process. This method should be invoked
     * immediately after a user is authenticated to prevent session fixation
     * attacks.
     */
    public void regenerateSession(HttpServletRequest request) {
        QlueSession qlueSession = getQlueSession(request);
        QluePageManager pageManager = (QluePageManager) request.getSession().getAttribute(QlueConstants.QLUE_SESSION_PAGE_MANAGER);
        request.getSession().invalidate();
        request.getSession(true).setAttribute(QlueConstants.QLUE_SESSION_OBJECT, qlueSession);
        request.getSession().setAttribute(QlueConstants.QLUE_SESSION_PAGE_MANAGER, pageManager);
    }

    /**
     * Set application prefix, which is used in logging as part of the unique transaction identifier.
     */
    protected void setPrefix(String prefix) {
        this.appPrefix = prefix;
    }

    /**
     * Whether direct output (in which the programmer is expected to manually
     * encode data) is allowed. We do not allow direct output by default.
     * Override this method to change the behaviour.
     */
    public boolean allowDirectOutput() {
        return false;
    }

    /**
     * Configure character encoding.
     */
    protected void setCharacterEncoding(String characterEncoding) {
        this.characterEncoding = characterEncoding;
    }

    /**
     * Retrieves application's character encoding.
     */
    public String getCharacterEncoding() {
        return characterEncoding;
    }

    /**
     * Configure development mode.
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

        throw new IllegalArgumentException("Invalid value for development mode: " + input);
    }

    /**
     * Get the development mode setting.
     */
    public int getApplicationDevelopmentMode() {
        return developmentMode;
    }

    /**
     * Set development mode password.
     */
    public void setDevelopmentModePassword(String developmentModePassword) {
        this.developmentModePassword = developmentModePassword;
    }

    private void setTrustedProxies(String combinedSubnets) throws Exception {
        if (TextUtil.isEmpty(combinedSubnets)) {
            return;
        }

        String[] subnets = combinedSubnets.split("[;,\\x20]");

        trustedProxies = new ArrayList<>();
        for (String s : subnets) {
            if (TextUtil.isEmpty(s)) {
                continue;
            }

            if ((!s.contains("/"))&&(!s.contains(":"))) {
                s = s + "/32";
            }

            try {
                trustedProxies.add(new CIDRUtils(s));
            } catch (IllegalArgumentException iae) {
                throw new RuntimeException("Qlue: Invalid proxy subnet: " + s);
            }
        }
    }

    public boolean isTrustedProxyRequest(TransactionContext context) {
        if (trustedProxies == null) {
            return false;
        }

        try {
            InetAddress remoteAddr = InetAddress.getByName(context.request.getRemoteAddr());
            for (CIDRUtils su : trustedProxies) {
                if (su.isInRange(remoteAddr)) {
                    return true;
                }
            }
        } catch(UnknownHostException e) {
            // Shouldn't happen.
            e.printStackTrace(System.err);
            return false;
        }

        return false;
    }

    /**
     * Configure the set of IP addresses that are allowed to use development mode.
     */
    protected void setDevelopmentSubnets(String combinedSubnets) throws Exception {
        if (TextUtil.isEmpty(combinedSubnets)) {
            return;
        }

        String[] subnets = combinedSubnets.split("[;,\\x20]");

        developmentSubnets = new ArrayList<>();
        for (String s : subnets) {
            if (TextUtil.isEmpty(s)) {
                continue;
            }

            if ((!s.contains("/"))&&(!s.contains(":"))) {
                s = s + "/32";
            }

            try {
                developmentSubnets.add(new CIDRUtils(s));
            } catch (IllegalArgumentException iae) {
                throw new RuntimeException("Qlue: Invalid development subnet: " + s);
            }
        }
    }

    /**
     * Check if the current transaction comes from an IP address that is allowed
     * to use development mode.
     */
    public boolean isDeveloperRequest(TransactionContext context) {
        if (developmentSubnets == null) {
            return false;
        }

        try {
            InetAddress remoteAddr = InetAddress.getByName(context.getEffectiveRemoteAddr());
            for (CIDRUtils su : developmentSubnets) {
                if (su.isInRange(remoteAddr)) {
                    return true;
                }

            }
        } catch(UnknownHostException e) {
            // Shouldn't happen.
            e.printStackTrace(System.err);
            return false;
        }

        return false;
    }

    /**
     * Check if the current transaction comes from a developer.
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
     */
    public String getDeveloperPassword() {
        return developmentModePassword;
    }

    /**
     * Retrieve this application's properties.
     */
    public Properties getProperties() {
        return properties;
    }

    /**
     * Retrieve a single named property as text.
     */
    public String getProperty(String key) {
        return VariableExpander.expand(properties.getProperty(key), properties);
    }

    /**
     * Retrieve a single named property as text, using the supplied default
     * value if the property is not set.
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
     */
    protected void setMessagesFilename(String messagesFilename) {
        this.messagesFilename = messagesFilename;
    }

    /**
     * Retrieve this application's message source.
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
     */
    void setActualPage(Page page) {
        page.context.request.setAttribute(REQUEST_ACTUAL_PAGE_KEY, page);
    }

    /**
     * Retrieve the actual page that tried to handle the current transaction and
     * failed.
     */
    Page getActualPage(Page currentPage) {
        return (Page) currentPage.context.request.getAttribute(REQUEST_ACTUAL_PAGE_KEY);
    }

    /**
     * Allocates a new page ID.
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
                log.error("SendUrgentRemindersTask exception", t);
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

    public String getPriorityTemplatePath() {
        return priorityTemplatePath;
    }

    /**
     * Returns class given its name.
     *
     * @param name
     * @return
     */
    public static Class classForName(String name) {
        try {
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            return Class.forName(name, true /* initialize */, classLoader);
        } catch (ClassNotFoundException e) {
            return null;
        } catch (NoClassDefFoundError e) {
            // NoClassDefFoundError is thrown when there is a class
            // that matches the name when ignoring case differences.
            // We do not care about that.
            return null;
        }
    }
}
