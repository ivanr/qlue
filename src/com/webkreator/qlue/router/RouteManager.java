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

public class RouteManager {
	
	private Log log = LogFactory.getLog(RouteManager.class);
	
	private QlueApplication app;

	private List<Route> routes = new ArrayList<Route>();
	
	private Pattern propertyPattern = Pattern.compile("([^{]*)\\$\\{([^}]*)\\}(.+)?");
	
	public RouteManager(QlueApplication app) {
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
			add(RouteFactory.create(line));
		}

		in.close();
	}

	public void add(Route route) {
		routes.add(route);
	}

	public Object route(TransactionContext tx) {
		Object r = null;
		
		if (log.isDebugEnabled()) {
			log.debug("QlueRouter: Asked to route: " + tx.getRequestUri());
		}
		
		for (Route route : routes) {
			if (log.isDebugEnabled()) {
				log.debug("QlueRouter: Trying " + route.getPath());
			}
		
			r = route.route(tx);
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
}
