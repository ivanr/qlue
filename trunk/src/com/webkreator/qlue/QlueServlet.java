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

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.UnavailableException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * This class serves as a glue between Qlue applications and the Servlet
 * specification. It's an abstract class; a subclass should be used to
 * create the desired instance of the Qlue application. 
 */
public abstract class QlueServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;

	private QlueApplication qlueApplication;

	private String characterEncoding = null;

	private Integer developmentMode = QlueConstants.DEVMODE_DISABLED;

	private String developmentModePassword = null;

	private String[] developmentModeRanges = null;

	/**
	 * Retrieve servlet parameters from web.xml and initialise application.
	 */
	@Override
	public final void init() throws ServletException {
		String s = null;

		// Character encoding
		characterEncoding = getInitParameter(QlueConstants.QLUE_CHARACTER_ENCODING);

		// Development mode enabled
		s = getInitParameter(QlueConstants.QLUE_DEVMODE_ENABLED);
		if (s != null) {
			if (setDevelopmentModeFromString(s) == false) {
				throw new UnavailableException(
						"QlueServlet: Invalid value for the "
								+ QlueConstants.QLUE_DEVMODE_ENABLED
								+ " parameter");
			}
		}

		// Development mode password
		developmentModePassword = getInitParameter(QlueConstants.QLUE_DEVMODE_PASSWORD);

		// Development mode IP address ranges
		s = getInitParameter(QlueConstants.QLUE_DEVMODE_RANGES);
		if (s != null) {
			developmentModeRanges = s.split("[;,\\x20]");
			// TODO Verify each IP address
		}

		// Let subclasses do their own initialization
		subclassInit();

		if (qlueApplication == null) {
			throw new UnavailableException(
					"QlueServlet: Application object not set in subclass");
		}

		// Initialise application
		try {
			qlueApplication.init(this);
		} catch (Exception e) {
			throw new ServletException(e);
		}

		if (characterEncoding != null) {
			qlueApplication.setCharacterEncoding(characterEncoding);
		}

		if (developmentMode != null) {
			qlueApplication.setApplicationDevelopmentMode(developmentMode);
		}

		qlueApplication.setDevelopmentModePassword(developmentModePassword);

		qlueApplication.setDevelopmentModeRanges(developmentModeRanges);
	}

	/**
	 * Configures development mode based on the parameter setting.
	 * 
	 * @param s
	 * @return
	 */
	private boolean setDevelopmentModeFromString(String s) {
		if (s.compareToIgnoreCase("on") == 0) {
			developmentMode = QlueConstants.DEVMODE_ENABLED;
			return true;
		} else if (s.compareToIgnoreCase("off") == 0) {
			developmentMode = QlueConstants.DEVMODE_DISABLED;
			return true;
		} else if (s.compareToIgnoreCase("ondemand") == 0) {
			developmentMode = QlueConstants.DEVMODE_ONDEMAND;
			return true;
		}

		return false;
	}

	/**
	 * This empty method exists to allow subclasses to perform
	 * their own initialisation, following the main initialisation
	 * carried out in this class.
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
	 * This method is invoked by the servlet container, and all we do
	 * is pass on the parameters to the associated Qlue application.
	 */
	protected void service(HttpServletRequest request,
			HttpServletResponse response) throws ServletException, IOException {
		// Forward request to the application.
		qlueApplication.service(this, request, response);
	}
}
