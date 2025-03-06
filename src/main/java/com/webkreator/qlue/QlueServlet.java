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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.UnavailableException;
import javax.servlet.annotation.MultipartConfig;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.SocketException;

/**
 * This class serves as a glue between Qlue applications and the Servlet
 * specification. It's an abstract class; a subclass should be used to create
 * the desired instance of the Qlue application.
 */
@MultipartConfig
public class QlueServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private static final String QLUE_APP_CLASS = "QLUE_APP_CLASS";

    private static final String QLUE_PAGES_ROOT_PACKAGE = "QLUE_PAGES_ROOT_PACKAGE";

    private static final String QLUE_SERVLET_INIT_FAILED = "QLUE_SERVLET_INIT_FAILED";

    private QlueApplication qlueApp;

    private static final Logger log = LoggerFactory.getLogger(QlueServlet.class);

    /**
     * Retrieve servlet parameters from web.xml and initialize application.
     */
    @Override
    public final void init() throws ServletException {
        try {
            createApplicationObject();

            if (qlueApp == null) {
                throw new UnavailableException("QlueServlet: Application not configured");
            }

            qlueApp.init(this);
        } catch (Exception e) {
            getServletContext().setAttribute(QLUE_SERVLET_INIT_FAILED, "true");

            if (e instanceof ServletException) {
                // We assume the subclass is throwing exactly what
                // it wants to communicate back to the servlet container.
                throw (ServletException) e;
            } else {
                // Otherwise, we tell the container that the app is unavailable.
                log.error("Application failed to start", e);
                throw new ServletException(e);
            }
        }
    }

    /**
     * By default, we look for a servlet init parameter to determine the name
     * of the application class. Subclasses can override this method to
     * obtain the application object in some other way.
     */
    protected void createApplicationObject() throws ClassNotFoundException {
        String pagesPackage = getServletConfig().getInitParameter(QLUE_PAGES_ROOT_PACKAGE);
        String appClassName = getServletConfig().getInitParameter(QLUE_APP_CLASS);

        if (pagesPackage != null) {
            if (appClassName != null) {
                throw new RuntimeException("Only one parameter allowed");
            }

            setApp(new QlueApplication(pagesPackage));
            return;
        }

        if (appClassName != null) {
            Class appClass = QlueApplication.classForName(appClassName);
            if (appClass == null) {
                throw new RuntimeException("Unable to find application class: " + appClassName);
            }

            if (!QlueApplication.class.isAssignableFrom(appClass)) {
                throw new RuntimeException("Application object not instance of QlueApplication: " + appClassName);
            }

            try {
                setApp((QlueApplication) appClass.newInstance());
            } catch (InstantiationException | IllegalAccessException e) {
                throw new RuntimeException("Unable to create application instance: " + appClassName);
            }
        }
    }

    /**
     * Retrieve the application associated with this servlet.
     */
    protected QlueApplication getApp() {
        return qlueApp;
    }

    /**
     * Associate Qlue application with this servlet.
     */
    protected void setApp(QlueApplication app) {
        this.qlueApp = app;
    }

    /**
     * This method is invoked by the servlet container, and all we do is pass on
     * the parameters to the associated Qlue application.
     */
    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        if (!qlueApp.isPropertiesAvailable()) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setHeader("Content-Type", "text/plain");
            response.getOutputStream().println("Application not configured");
            return;
        }

        try {
            qlueApp.service(this, request, response);
        } catch (SocketException e) {
            // Ignore "Broken pipe" exceptions, which occur when clients go away.
            if ((e.getMessage() == null) || (!e.getMessage().contains("Broken pipe"))) {
                throw e;
            }
        }
    }

    /**
     * Invokes the destroy() method on the application object.
     */
    @Override
    public void destroy() {
        if (qlueApp == null) {
            return;
        }

        qlueApp.qlueBeforeDestroy();
        qlueApp.destroy();
    }
}
