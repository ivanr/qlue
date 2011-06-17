/* 
 * Qlue Web Application Framework
 * Copyright 2009-2011 Ivan Ristic <ivanr@webkreator.com>
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.webkreator.qlue.QlueApplication;
import com.webkreator.qlue.TransactionContext;

public class QlueRouteManager implements RouteManager {
	
	private Log log = LogFactory.getLog(QlueRouteManager.class);
	
	private QlueApplication app;

	private List<Route> routes = new ArrayList<Route>();
	
	private Pattern propertyPattern = Pattern.compile("([^{]*)\\$\\{([^}]*)\\}(.+)?");
	
	private String suffix = ".html";
	
	private String index = "index";
	
	public QlueRouteManager(QlueApplication app) {
		this.app = app;
	}

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
			
			line = expandProperties(line);
			
			// Add route
			add(RouteFactory.create(this, line));
		}

		in.close();
	}

	public void add(Route route) {
		routes.add(route);
	}

	public Object route(TransactionContext context) {
		Object r = null;
		
		if (log.isDebugEnabled()) {
			log.debug("QlueRouter: Asked to route: " + context.getRequestUri());
		}
		
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
	
	String expandProperties(String input) {
		
		StringBuffer sb = new StringBuffer();
		String haystack = input;
		Matcher m = propertyPattern.matcher(haystack);
		while ((m != null) && (m.find())) {
			sb.append(m.group(1));
			
			String propertyName = m.group(2);
			
			if (app.getProperty(propertyName) != null) {
				sb.append(app.getProperty(propertyName));
			}
			
			haystack = m.group(3);
			
			if (haystack != null) {
				m = propertyPattern.matcher(haystack);
			} else {
				m = null;
			}
		}
		
		if (haystack != null) {
			sb.append(haystack);
		}
		
		return sb.toString();
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
