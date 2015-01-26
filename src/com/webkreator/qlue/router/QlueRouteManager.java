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
package com.webkreator.qlue.router;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.webkreator.qlue.QlueApplication;
import com.webkreator.qlue.TransactionContext;
import com.webkreator.qlue.util.VariableExpander;

/**
 * Implements the default routing functionality, which accepts
 * a single routing file (routes.conf) that contains one route
 * per line.
 */
public class QlueRouteManager implements RouteManager {
	
	private Log log = LogFactory.getLog(QlueRouteManager.class);
	
	private QlueApplication app;

	private List<Route> routes = new ArrayList<Route>();	
	
	private String suffix = ".html";
	
	private String index = "index";
	
	public QlueRouteManager(QlueApplication app) {
		this.app = app;
	}

	/**
	 * Loads routes from a file.
	 * 
	 * @param routesFile
	 * @throws Exception
	 */
	public void load(File routesFile) throws Exception {
		// Remove any existing routers
		routes.clear();

		// Loop through the lines in the file, creating
		// one router per non-comment line
		BufferedReader in = new BufferedReader(new FileReader(routesFile));

		String line = null;
		while ((line = in.readLine()) != null) {
			// Ignore comment lines
			line = line.trim();
			if ((line.length() == 0) || (line.charAt(0) == '#')) {
				continue;
			}					
			
			// Expand variables specified in the line, if any
			line = expandProperties(line);					
			
			// Add route
			add(RouteFactory.create(this, line));
		}

		// Close stream
		in.close();
	}

	/**
	 * Adds a new route.
	 * 
	 * @param route
	 */
	public void add(Route route) {
		routes.add(route);
	}

	/**
	 * Routes transaction using previously configured routes.
	 * 
	 * @param context
	 * @return
	 */
	public Object route(TransactionContext context) {
		Object r = null;
		
		if (log.isDebugEnabled()) {
			log.debug("QlueRouter: Asked to route: " + context.getRequestUri());
		}
		
		// Loop through the configured routes
		for (Route route : routes) {
			if (log.isDebugEnabled()) {
				log.debug("QlueRouter: Trying " + route.getPath());
			}
		
			r = route.route(context);
			if (r != null) {
				return r;
			}
		}
		
		return null;
	}
	
	/**
	 * Replace variables (in the format "${variableName}") with
	 * their values from the Qlue properties file.
	 * 
	 * @param input
	 * @return
	 */
	String expandProperties(String input) {
		return VariableExpander.expand(input, app.getProperties());		
	}	
	
	@Override
	public String getIndex() {
		return index;
	}
	
	public void setIndex(String index) {
		this.index = index;
	}
	
	@Override
	public String getSuffix() {
		return suffix;
	}
	
	public void setSuffix(String suffix) {
		this.suffix = suffix;
	}
}
