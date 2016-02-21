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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Creates route instaces given route text representations. 
 */
public class RouteFactory {

    private static final Pattern configRoutePattern = Pattern.compile("^@([a-zA-Z0-9]+)\\s+(.*)$");

	/**
	 * Creates route from its text representation.
	 *  
	 * @param manager
	 * @param route
	 * @return
	 */
	public static Route create(RouteManager manager, String route) {
		Router router = null;

        if ((route.length() > 0)&&(route.charAt(0) == '@')) {
            return createConfig(manager, route);
        }

		// Split route into tokens
		String[] tokens = route.split("\\s+");
		if (tokens.length < 2) {
			throw new RuntimeException("Qlue: Invalid route: " + route);
		}

		// Path
		String path = tokens[0];			
		
		// Remove multiple consecutive forward slashes, which could
		// have been introduced through variable expansion
		path = path.replaceAll("/{2,}", "/");		

		// Action
		String action = tokens[1];

		// Convert path into a pattern

		// Handle action string
		if (action.startsWith("package:")) {
			// Package name
			String packageName = action.substring(8).trim();
			router = new PackageRouter(manager, packageName);
		} else if (action.startsWith("router:")) {
			// Custom router; instantiate
			// the named Router instance
			String routerClassName = action.substring(7).trim();
			router = findRouter(routerClassName);
		} else if (action.startsWith("redirect:")) {
			// Redirection
			String uri = action.substring(9).trim();

			// Check for explicit redirection status code
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
		} else if (action.startsWith("static:")) {
			// Static route
			String staticPath = action.substring(7).trim();
			
			if (tokens.length > 2) {
				// Recombine the remaining tokens back
				// into a single string
				StringBuffer sb = new StringBuffer();
				sb.append(staticPath);
				sb.append(' ');
				
				for(int i = 2; i <= tokens.length -1; i++) {
					if (i > 2) {
						sb.append(' ');
					}
					
					sb.append(tokens[i]);
				}
				
				// Status code and message
				router = new StaticFileRouter(manager, sb.toString());
			} else {
				// Status code only
				router = new StaticFileRouter(manager, staticPath);
			}					
		} else {
			// Class name
			router = new ClassRouter(action);
		}

		return new Route(path, router);
	}

    private static Route createConfig(RouteManager manager, String route) {
        Matcher m = configRoutePattern.matcher(route);
        if (m.matches() == false) {
            throw new RuntimeException("Qlue: Invalid config route: " + route);
        }

        String configDirective = m.group(1);
        String configText = m.group(2);

        if (configDirective.equals("header")) {
            return new Route(null, HeaderConfigRouter.fromString(manager, configText));
        } else {
            throw new RuntimeException("Qlue: Unknown configuration directive");
        }
    }

    /**
	 * Finds router instance based on class name.
	 */
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
