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

import java.io.IOException;
import java.net.SocketException;

import javax.servlet.ServletException;
import javax.servlet.UnavailableException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * This class serves as a glue between Qlue applications and the Servlet
 * specification. It's an abstract class; a subclass should be used to create
 * the desired instance of the Qlue application.
 */
public class QlueServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private static final String QLUE_APP_CLASS = "QLUE_APP_CLASS";

    private static final String QLUE_PAGES_PACKAGE = "QLUE_PAGES_PACKAGE";

    private QlueApplication qlueApp;

    /**
     * Retrieve servlet parameters from web.xml and initialize application.
     */
    @Override
    public final void init() throws ServletException {
        try {
            createApplicationObject();

            if (qlueApp == null) {
                throw new UnavailableException("QlueServlet: Application not available");
            }

            qlueApp.init(this);
        } catch (Throwable t) {
            throw new ServletException(t);
        }
    }

    /**
     * By default, we look for a servlet init parameter to determine the name
     * of the application class. Subclasses can override this method to
     * obtain the application object in some other way.
     */
    protected void createApplicationObject() throws ClassNotFoundException {
        String pagesPackage = getServletConfig().getInitParameter(QLUE_PAGES_PACKAGE);
        String appClass = getServletConfig().getInitParameter(QLUE_APP_CLASS);

        if (pagesPackage != null) {
            if (appClass != null) {
                throw new RuntimeException("Only one parameter allowed");
            }

            setApp(new QlueApplication(pagesPackage));
            return;
        }

        if (appClass != null) {
            Object app =  Class.forName(appClass);
            if (app instanceof QlueApplication) {
                setApp((QlueApplication) app);
            } else {
                throw new RuntimeException("Application object not instance of QlueApplication");
            }
        }
    }

    /**
     * Associate Qlue application with this servlet.
     *
     * @param app
     */
    protected void setApp(QlueApplication app) {
        this.qlueApp = app;
    }

    /**
     * Retrieve the application associated with this servlet.
     *
     * @return
     */
    protected QlueApplication getApp() {
        return qlueApp;
    }

    /**
     * This method is invoked by the servlet container, and all we do is pass on
     * the parameters to the associated Qlue application.
     */
    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
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

        qlueApp.destroy();
    }
}
