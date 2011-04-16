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
package com.webkreator.qlue.util;

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

import org.apache.log4j.LogManager;
import org.apache.log4j.PropertyConfigurator;
import org.apache.log4j.xml.DOMConfigurator;

import com.webkreator.qlue.QlueConstants;

/**
 * This class serves as a bridge between the servlet container and
 * log4j. It subscribes to context events, initialising log4j when
 * the context is created, and shutting log4j when the context is
 * destroyed. 
 */
public class Log4jConfigListener implements ServletContextListener {

	protected String location = "/WEB-INF/log4j.properties";

	protected Integer interval = null;

	/**
	 * Initialise log4j.
	 */
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

	/**
	 * Shut down log4j.
	 */
	public void contextDestroyed(ServletContextEvent event) {
		ServletContext servletContext = event.getServletContext();

		servletContext.log("Shutting down log4j");

		LogManager.shutdown();
	}
}
