package com.webkreator.qlue.router;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.webkreator.qlue.TransactionContext;

public class QlueRouter {
	
	private Log log = LogFactory.getLog(QlueRouter.class);

	private List<Route> routes = new ArrayList<Route>();

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
}
