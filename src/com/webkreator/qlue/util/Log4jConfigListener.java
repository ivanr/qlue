package com.webkreator.qlue.util;

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

import org.apache.log4j.LogManager;
import org.apache.log4j.PropertyConfigurator;
import org.apache.log4j.xml.DOMConfigurator;

import com.webkreator.qlue.QlueConstants;

public class Log4jConfigListener implements ServletContextListener {

	protected String location = "/WEB-INF/log4j.properties";

	protected Integer interval = null;

	public void contextInitialized(ServletContextEvent event) {
		ServletContext servletContext = event.getServletContext();

		String s = null;

		// Configuration path parameter
		s = servletContext.getInitParameter(QlueConstants.QLUE_LOG4J_CONFIG);
		if (s != null) {
			location = s;
		}

		// Refresh interval parameter
		s = servletContext
				.getInitParameter(QlueConstants.QLUE_LOG4J_REFRESH_INTERVAL);
		if (s != null) {
			interval = Integer.parseInt(s);
			if (interval == 0) {
				interval = null;
			}
		}

		if (interval != null) {
			servletContext.log("Initializing log4j with " + location
					+ " (refresh interval " + interval + ")");
		} else {
			servletContext.log("Initializing log4j with " + location);
		}

		// Set the "webapp.root" property, which is used by log4j
		System.setProperty("webapp.root", event.getServletContext()
				.getRealPath("/"));

		// Configure log4j
		if (location.endsWith(".xml")) {
			if (interval != null) {
				DOMConfigurator.configureAndWatch(location, interval);
			} else {
				DOMConfigurator.configure(servletContext.getRealPath(location));
			}
		} else {
			if (interval != null) {
				PropertyConfigurator.configureAndWatch(location, interval);
			} else {
				PropertyConfigurator.configure(servletContext
						.getRealPath(location));
			}
		}
	}

	public void contextDestroyed(ServletContextEvent event) {
		ServletContext servletContext = event.getServletContext();

		servletContext.log("Shutting down log4j");

		LogManager.shutdown();
	}
}
