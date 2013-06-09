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

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletException;
import javax.servlet.UnavailableException;
import javax.servlet.annotation.WebListener;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * This class serves as a glue between Qlue applications and the Servlet
 * specification. It's an abstract class; a subclass should be used to create
 * the desired instance of the Qlue application.
 */
@WebListener
public abstract class QlueServlet extends HttpServlet implements ServletContextListener {

	private static final long serialVersionUID = 1L;

	private QlueApplication qlueApplication;

	/**
	 * Retrieve servlet parameters from web.xml and initialize application.
	 */
	@Override
	public final void init() throws ServletException {
		// Let subclasses do their own initialization
		subclassInit();

		if (qlueApplication == null) {
			throw new UnavailableException(
					"QlueServlet: Application object not set in subclass");
		}

		// Initialize application
		try {
			qlueApplication.init(this);
		} catch (Exception e) {
			throw new ServletException(e);
		}
	}

	/**
	 * This empty method exists to allow subclasses to perform their own
	 * initialization, following the main initialization carried out in this
	 * class.
	 * 
	 * @throws ServletException
	 */
	protected void subclassInit() throws ServletException {
		// A subclass may want to do something useful here
	}

	/**
	 * Associate Qlue application with this servlet.
	 * 
	 * @param app
	 */
	protected void setApplication(QlueApplication app) {
		this.qlueApplication = app;
	}

	/**
	 * Retrieve the application associated with this servlet.
	 * 
	 * @return
	 */
	protected QlueApplication getApplication() {
		return qlueApplication;
	}

	/**
	 * This method is invoked by the servlet container, and all we do is pass on
	 * the parameters to the associated Qlue application.
	 */
	protected void service(HttpServletRequest request,
			HttpServletResponse response) throws ServletException, IOException {
		try {
			// Forward request to the application.
			qlueApplication.service(this, request, response);
		} catch (SocketException e) {
			// Ignore "Broken pipe" exceptions, which occur when clients go away.
			if ((e.getMessage() == null)||(!e.getMessage().contains("Broken pipe"))) {
				throw e;
			}
		}
	}

	/**
	 * Invokes the destroy() method on the application object.
	 */
	@Override
	public void destroy() {
		if (qlueApplication == null) {
			return;
		}

		qlueApplication.destroy();
	}
	
    @Override
    public void contextInitialized(ServletContextEvent arg0) {
        // Do nothing.        
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
    	// Do nothing.
    }
}
