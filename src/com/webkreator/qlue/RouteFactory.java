package com.webkreator.qlue;

public class RouteFactory {

	public static Route create(String route) {
		Router router = null;

		// Split route into tokens
		String[] tokens = route.split("\\s+");
		if (tokens.length < 2) {
			throw new RuntimeException("Qlue: Invalid route: " + route);
		}

		// Path
		String path = tokens[0];

		// Remove trailing slash from path
		if ((path.length() > 0) && (path.charAt(path.length() - 1) == '/')) {
			path = path.substring(0, path.length() - 1);
		}

		// Action
		String action = tokens[1];

		// Convert path into a pattern

		// Handle action string
		if (action.startsWith("package:")) {
			// Package name
			String packageName = action.substring(8).trim();
			router = new PackageRouter(packageName);
		} else if (action.startsWith("router:")) {
			// Custom router; instantiate
			// the named Router instance
			String routerClassName = action.substring(7).trim();
			router = findRouter(routerClassName);
		} else if (action.startsWith("redirect:")) {
			// Redirection
			String uri = action.substring(9).trim();

			if (tokens.length > 2) {
				int status = 301;
				try {
					status = Integer.parseInt(tokens[2]);
				} catch (Exception e) {
					throw new RuntimeException(
							"Qlue: Invalid redirection status in route: "
									+ tokens[2]);
				}
				router = new RedirectionRouter(uri, status);
			} else {
				router = new RedirectionRouter(uri);
			}
		} else if (action.startsWith("status:")) {
			String statusCodeString = action.substring(7).trim();
			int statusCode = 0;
			
			try {
				// Parse and validate response status code
				statusCode = Integer.parseInt(statusCodeString);
				if ((statusCode >= 100) && (statusCode <= 999)) {
					if (tokens.length > 2) {
						// Recombine the remaining tokens back
						// into a single string
						StringBuffer sb = new StringBuffer();
						for(int i = 2; i <= tokens.length -1; i++) {
							if (i > 2) {
								sb.append(' ');
							}
							
							sb.append(tokens[i]);
						}
						
						// Status code and message
						router = new StatusCodeRouter(statusCode, sb.toString());
					} else {
						// Status code only
						router = new StatusCodeRouter(statusCode);
					}
				} else {
					throw new RuntimeException(
							"Qlue: Invalid status code in route: "
									+ statusCodeString);
				}
			} catch (Exception e) {
				throw new RuntimeException(
						"Qlue: Invalid status code in route: "
								+ statusCodeString);
			}
		} else {
			// Class name
			router = new ClassRouter(action);
		}

		return new Route(path, router);
	}

	@SuppressWarnings("rawtypes")
	private static Router findRouter(String className) {
		Class candidate = null;

		// Look for class
		try {
			ClassLoader classLoader = Thread.currentThread()
					.getContextClassLoader();
			candidate = Class.forName(className, true, classLoader);
		} catch (Exception e) {
			throw new RuntimeException("ClassRouter: Unknown class: "
					+ className);
		}

		// Check class is instance of Page
		if (!Router.class.isAssignableFrom(candidate)) {
			throw new RuntimeException("ClassRouter: Class " + className
					+ " is not a subclass of Page.");
		}

		// Return one instance
		try {
			return (Router) candidate.newInstance();
		} catch (Exception e) {
			throw new RuntimeException(
					"ClassRouter: Failed to create class instance: "
							+ e.getMessage(), e);
		}
	}
}
