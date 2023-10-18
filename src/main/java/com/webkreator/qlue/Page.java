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

import com.webkreator.qlue.annotations.QlueCommandObject;
import com.webkreator.qlue.annotations.QlueParameter;
import com.webkreator.qlue.annotations.QluePersistentPage;
import com.webkreator.qlue.exceptions.BadRequestException;
import com.webkreator.qlue.exceptions.MethodNotAllowedException;
import com.webkreator.qlue.exceptions.UnauthorizedException;
import com.webkreator.qlue.util.BearerToken;
import com.webkreator.qlue.util.HtmlEncoder;
import com.webkreator.qlue.view.View;
import com.webkreator.qlue.view.ViewResolver;
import com.webkreator.qlue.view.velocity.QlueVelocityTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.Reader;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.*;

/**
 * Represents a single unit of work application will perform. This class handles both non-persistent
 * and persistent pages. I used only one class for both because the intention is that applications
 * never really subclass this class directly. Rather, they should create their own base page class. And,
 * because Java doesn't support multiple inheritance, having two Qlue base classes would complicate things.
 */
public abstract class Page implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * This is a meta-state that's used for parameter binding: matches
     * on all requests. Page state can't be set to this value.
     */
    public static final String STATE_ANY = "ANY";

    /**
     * This is a meta-state that's used for parameter binding: non-persistent
     * pages bind all STATE_DEFAULT parameters on every request; persistent
     * pages bind STATE_DEFAULT parameters only on POST requests.
     */
    public static final String STATE_DEFAULT = "DEFAULT";

    /**
     * This is a meta-state that's used for parameter binding: matches any
     * HTTP GET request. Page state can't be set to this value.
     */
    public static final String STATE_GET = "GET";

    /**
     * This is a meta-state that's used for parameter binding: matches any
     * HTTP POST request. Page state can't be set to this value.
     */
    public static final String STATE_POST = "POST";

    /**
     * This is the default state of every page, which is used until
     * it completes its first request.
     */
    public static final String STATE_INIT = "INIT";

    /**
     * This is the default working state, i.e., the state to
     * which pages transition after STATE_INIT. It's used for
     * convenience because most persistent pages have only
     * two states.
     */
    public static final String STATE_IN_PROGRESS = "IN_PROGRESS";

    /**
     * This is the final state for persistent pages; it indicates
     * that there is no more work to be done.
     */
    public static final String STATE_FINISHED = "FINISHED";

    protected static final Logger qlueLog = LoggerFactory.getLogger(Page.class);


    // -- Set by framework at the beginning of a transaction.

    protected transient QlueApplication app;

    protected transient TransactionContext context;

    protected transient Map<String, Object> model = new HashMap<String, Object>();


    // -- Instance fields.

    private Integer id;

    private String state = STATE_INIT;

    private boolean cleanupInvoked;

    protected String viewName;

    protected String contentType = "text/html; charset=UTF-8";

    private Object commandObject;

    private boolean commandObjectDetermined;

    protected Errors errors = new Errors();

    protected ShadowInput shadowInput = new ShadowInput();

    protected Page() {
    }

    public Page(QlueApplication app) {
        setApp(app);
        determineDefaultViewName(app.getViewResolver());
        determineCommandObject();
    }

    /**
     * Has this page finished its work?
     */
    public boolean isFinished() {
        return getState().equals(STATE_FINISHED);
    }

    /**
     * Retrieve unique page ID.
     */
    public Integer getId() {
        return id;
    }

    /**
     * Set page ID. Pages are allocated IDs only prior to being persisted.
     * Transient pages do not need IDs.
     */
    void setId(int id) {
        this.id = id;
    }

    public void clearShadowInput() {
        shadowInput = new ShadowInput();
    }

    /**
     * Retrieve shadow input associated with page.
     */
    public ShadowInput getShadowInput() {
        return shadowInput;
    }

    /**
     * Retrieve page state.
     */
    public String getState() {
        return state;
    }

    /**
     * Change page state to given value.
     */
    protected void setState(String state) {
        if ((state == Page.STATE_INIT) || (state == Page.STATE_ANY) || (state == Page.STATE_GET) || (state == Page.STATE_POST)) {
            throw new IllegalArgumentException("Invalid state transition: " + state);
        }

        this.state = state;
    }

    /**
     * Retrieve the application to which this page belongs.
     */
    public QlueApplication getApp() {
        return app;
    }

    /**
     * Associate Qlue application with this page.
     */
    void setApp(QlueApplication app) {
        this.app = app;
    }

    /**
     * Return a command object. By default, the page is the command object, but
     * a subclass has the option to use a different object. The page can use the
     * supplied context to choose which command object (out of several it might
     * be using) to return.
     */
    public final synchronized Object getCommandObject() {
        if (!commandObjectDetermined && (commandObject == null)) {
            determineCommandObject();
        }

        if (commandObject != null) {
            return commandObject;
        } else {
            // The page itself is the command object.
            return this;
        }
    }

    /**
     * This method will determine what the command object is supposed to be. The
     * page itself is the default command object, but subclass can override this
     * behavior.
     */
    protected void determineCommandObject() {

        // Look for the command object among the page fields via the @QlueCommandObject annotation.
        try {
            Field[] fields = this.getClass().getFields();
            for (Field f : fields) {
                if (f.isAnnotationPresent(QlueCommandObject.class)) {
                    commandObject = f.get(this);

                    // If the command object doesn't exist, we try to create a new instance.
                    if (commandObject == null) {
                        try {
                            // Our first attempt is to use the default constructor.
                            commandObject = f.getType().newInstance();
                            f.set(this, commandObject);
                        } catch (InstantiationException ie) {
                            // Inner classes have an implicit constructor that takes a reference to the parent object.
                            // http://docs.oracle.com/javase/specs/jls/se8/html/jls-8.html#jls-8.8.1
                            try {
                                commandObject = f.getType().getConstructor(this.getClass()).newInstance(this);
                                f.set(this, commandObject);
                            } catch (NoSuchMethodException | InstantiationException | InvocationTargetException ex) {
                                throw new RuntimeException("Unable to create command object: " + f.getType());
                            }
                        }
                    }

                    commandObjectDetermined = true;
                    return;
                }
            }

            // Leave null as a signal to use itself as the command object.
            commandObject = null;
            commandObjectDetermined = true;
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Process one HTTP request. By default, pages accept only GET (and HEAD,
     * treated as GET) and POST.
     */
    public View service() throws Exception {
        switch (context.request.getMethod()) {
            case "GET":
            case "HEAD":
                return onGet();
            case "DELETE":
                return onDelete();
            case "PATCH":
                return onPatch();
            case "POST":
                return onPost();
            case "PUT":
                return onPut();
            default:
                throw new MethodNotAllowedException();
        }
    }

    /**
     * Process a GET request. The default implementation does not actually do
     * anything -- it just throws an exception.
     */
    public View onGet() throws Exception {
        throw new MethodNotAllowedException();
    }

    /**
     * Process a DELETE request. The default implementation does not actually do
     * anything -- it just throws an exception.
     */
    public View onDelete() throws Exception {
        throw new MethodNotAllowedException();
    }

    public View onPatch() throws Exception {
        throw new MethodNotAllowedException();
    }

    /**
     * Process a POST request. The default implementation does not actually do
     * anything -- it just throws an exception.
     */
    public View onPost() throws Exception {
        throw new MethodNotAllowedException();
    }

    /**
     * Process a PUT request. The default implementation does not actually do
     * anything -- it just throws an exception.
     */
    public View onPut() throws Exception {
        throw new MethodNotAllowedException();
    }

    /**
     * Retrieve the model associated with a page.
     */
    public Map<String, Object> getModel() {
        return model;
    }

    /**
     * Add a key-value pair to the model.
     */
    void addToModel(String key, Object value) {
        model.put(key, value);
    }

    /**
     * Retrieve value from the model, using the given key.
     */
    public Object getFromModel(String key) {
        return model.get(key);
    }

    /**
     * Retrieve the default view name associated with page.
     */
    public String getViewName() {
        return viewName;
    }

    /**
     * Retrieve the response content type associated with this page.
     */
    public String getContentType() {
        return contentType;
    }

    /**
     * Set response content type.
     */
    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    /**
     * Retrieve page transaction context.
     */
    public TransactionContext getContext() {
        return context;
    }

    /**
     * Set page transaction context.
     */
    void setContext(TransactionContext context) {
        this.context = context;
    }

    /**
     * Return page's format tool. By default, we respond with application's
     * format tool, but pages (subclasses) can create their own.
     */
    public List<QlueVelocityTool> getVelocityTools() {
        return getApp().getVelocityTools();
    }

    /**
     * Determine the default view name for this page.
     */
    void determineDefaultViewName(ViewResolver resolver) {
        viewName = this.getClass().getSimpleName();
    }

    public View initBackend() throws Exception {
        return null;
    }

    /**
     * This method is invoked right before the main service method. It allows
     * the page to prepare for request processing. The default implementation
     * will, on POST request, check that there is a nonce value supplied in the
     * request, and that the value matches the value stored in the session. It
     * will also expose the nonce to the model.
     */
    public View checkAccess() throws Exception {
        if (context.isPost() && getClass().isAnnotationPresent(QluePersistentPage.class)) {
            verifySessionSecret();
        }

        return null;
    }

    protected void verifySessionSecret() throws Exception {
        BearerToken sessionSecret = getQlueSession().getSessionSecret();

        String suppliedSecret = context.getParameter("_secret");
        if (suppliedSecret == null) {
            throw new UnauthorizedException("Secret session token hasn't been provided");
        }

        try {
            if (sessionSecret.checkMaskedToken(suppliedSecret) == false) {
                throw new UnauthorizedException("Secret session token mismatch");
            }
        } catch (IllegalArgumentException e) {
            throw new UnauthorizedException("Invalid secret session token");
        }
    }

    public View validateParameters() {
        return null;
    }

    public View prepareForService() {
        return null;
    }

    public void startHttpSession() {
        getQlueSession();
    }

    /**
     * Does this page has any parameter validation errors?
     */
    protected boolean hasErrors() {
        return errors.hasErrors();
    }

    /**
     * Retrieve validation errors.
     */
    public Errors getErrors() {
        return errors;
    }

    /**
     * Adds a page-specific error message.
     */
    protected void addError(String message) {
        errors.addError(message);
    }

    /**
     * Adds a field-specific error message.
     */
    public void addError(String param, String message) {
        errors.addError(param, message);
    }

    /**
     * Retrieve session associated with this page.
     */
    public QlueSession getQlueSession() {
        /*
        // This code used to determine who started the HTTP session.
        try {
            throw new RuntimeException();
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
        */

        return app.getQlueSession(context.getRequest());
    }

    public boolean allowDirectOutput() {
        return app.allowDirectOutput();
    }

    public boolean isQlueDevMode() {
        return app.isQlueDevMode(context);
    }

    public String getNoParamUri() {
        return context.getRequestUri();
    }

    /**
     * Outputs page-specific debugging information.
     */
    void writeDevelopmentInformation(PrintWriter out) {
        // Page fields
        out.println(" Id: " + getId());
        out.println(" Class: " + this.getClass());
        out.println(" State: " + HtmlEncoder.html(getState()));
        out.println(" Errors {");

        // Errors
        int i = 1;
        for (Error e : errors.getAllErrors()) {
            out.print("   " + i++ + ". ");
            out.print(HtmlEncoder.html(e.getMessage()));

            if (e.getParam() != null) {
                out.print(" [field " + HtmlEncoder.html(e.getParam())
                        + "]");
            }

            out.println();
        }

        out.println(" }");
        out.println("");

        // Model
        out.println("<b>Model</b>\n");

        Map<String, Object> model = getModel();
        if (model != null) {
            TreeMap<String, Object> treeMap = new TreeMap<>();

            for (Iterator<String> it = model.keySet().iterator(); it.hasNext(); ) {
                String name = it.next();
                treeMap.put(name, model.get(name));
            }

            Iterator<String> it = treeMap.keySet().iterator();
            while (it.hasNext()) {
                String name = it.next();
                Object o = treeMap.get(name);
                out.println(" "
                        + HtmlEncoder.html(name)
                        + ": "
                        + ((o != null) ? HtmlEncoder.html(o.toString())
                        : "null"));
            }
        }
    }

    /**
     * Executes page rollback. The default implementation cleans up resources.
     */
    public void rollback() {
    }

    /**
     * Executes page commit. The default implementation cleans up resources.
     */
    public void commit() {
    }

    /**
     * In the default implementation, we delete any files that were created
     * during the processing of a multipart/form-data request.
     */
    public void cleanup() {
        cleanupInvoked = true;
        deleteFiles();
    }

    /**
     * Invoked after data validation and binding, but before request processing,
     * giving the page a chance to initialize itself. This method is invoked
     * only when the state is STATE_NEW (which means only once for a page).
     */
    public View init() throws Exception {
        return null;
    }

    /**
     * Delete files created by processing multipart/form-data.
     */
    void deleteFiles() {
        Object commandObject = getCommandObject();
        if (commandObject == null) {
            return;
        }

        // Look for QlueFile instances
        Field[] fields = commandObject.getClass().getFields();
        for (Field f : fields) {
            if (f.isAnnotationPresent(QlueParameter.class)) {
                if (QlueFile.class.isAssignableFrom(f.getType())) {
                    // Delete temporary file
                    QlueFile qf = null;
                    try {
                        qf = (QlueFile) f.get(commandObject);
                        if (qf != null) {
                            qf.delete();
                        }
                    } catch (Exception e) {
                        qlueLog.error("Qlue: Failed deleting file " + qf, e);
                    }
                }
            }
        }
    }

    /**
     * Is page persistent?
     */
    public boolean isPersistent() {
        return getClass().isAnnotationPresent(QluePersistentPage.class);
    }

    /**
     * This method is invoked after built-in parameter validation fails. The
     * default implementation will throw an exception for non-persistent pages,
     * and ignore the problem for persistent pages.
     */
    public View handleParameterValidationFailure() throws Exception {
        if (isPersistent() == true) {
            // Persistent pages, by default, have to
            // explicitly handle their errors.
            return null;
        }

        // We're going to throw an exception here to interrupt normal
        // page processing. The errors should already be available in
        // the page, so there's no need for us to add anything else here.
        throw new BadRequestException();
    }

    public View handleException(Exception e) {
        // Do nothing by default, let subclasses handle it.
        return null;
    }

    public boolean isCleanupInvoked() {
        return cleanupInvoked;
    }

    void setRoutedResponseHeaders() {
        for (Map.Entry<String, String> me : context.getResponseHeaders().entrySet()) {
            context.response.setHeader(me.getKey(), me.getValue());
        }
    }

    protected boolean validateBean(Object object) {
        return validateBean(object, null);
    }

    protected boolean validateBean(Object object, String parentPath) {
        return app.doBeanValidation(object, parentPath, this);
    }

    protected Object convertJsonToObject(Reader reader, Class<?> type) {
        return app.convertJsonToObject(reader, type);
    }

    /**
     * Return the default state after INIT. Most persistent pages support
     * only two states, INIT and IN_PROGRESS, and Qlue will automatically
     * switch from one to the other unless the page explicitly makes
     * the transition. Pages can override this method if they wish to
     * use more meaningful state names.
     *
     * @return
     */
    public String getDefaultStateAfterInit() {
        return Page.STATE_IN_PROGRESS;
    }
}
