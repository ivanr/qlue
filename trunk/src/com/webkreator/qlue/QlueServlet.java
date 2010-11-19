package com.webkreator.qlue;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.UnavailableException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public abstract class QlueServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;

	private QlueApplication qlueApplication;

	private String characterEncoding = null;

	private Integer developmentMode = QlueConstants.DEVMODE_DISABLED;

	private String developmentModePassword = null;

	private String[] developmentModeRanges = null;

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

	protected void subclassInit() throws ServletException {
		// A subclass may want to do something useful here
	}

	protected void setApplication(QlueApplication app) {
		this.qlueApplication = app;
	}

	protected QlueApplication getApplication() {
		return qlueApplication;
	}

	protected void service(HttpServletRequest request,
			HttpServletResponse response) throws ServletException, IOException {
		// Forward request to the application.
		qlueApplication.service(this, request, response);
	}
}
