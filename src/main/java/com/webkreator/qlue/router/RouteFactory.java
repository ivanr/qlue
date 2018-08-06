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

import java.util.EnumSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Creates route instances given route text representations.
 */
public class RouteFactory {

    private static final Pattern metaRoutePattern = Pattern.compile("^@([a-zA-Z0-9]+)\\s+(.*)$");

	/**
	 * Creates route from its text representation.
	 */
	public static Route create(RouteManager manager, String route) {
		Router router;

        if ((route.length() > 0)&&(route.charAt(0) == '@')) {
            return createMetaRoute(manager, route);
        }

		// Split the route into tokens.
		int nextTokenPos = 0;
		String[] tokens = route.split("\\s+");
		if (tokens.length < 2) {
			throw new RuntimeException("Qlue: Invalid route: " + route);
		}

		// Parse optional request methods.

		EnumSet<RouteMethod> acceptedMethods = EnumSet.allOf(RouteMethod.class);

		if (tokens[nextTokenPos].charAt(0) != '/') {
			acceptedMethods = parseAcceptedMethods(tokens[nextTokenPos]);
			nextTokenPos++;
		}

		// Parse path.

		String path = tokens[nextTokenPos++];
		
		// Remove multiple consecutive forward slashes, which could
		// have been introduced through variable expansion.
		path = path.replaceAll("/{2,}", "/");		

		// The action to take is in the second token.
		String action = tokens[nextTokenPos++];

		// Convert the path into a pattern.

		// Handle the action string.
		if (action.startsWith("package:")) {
			// Package name.
			String packageName = action.substring(8).trim();
			router = new PackageRouter(manager, packageName);
		} else if (action.startsWith("router:")) {
			// Custom router; instantiate the named Router instance.
			String routerClassName = action.substring(7).trim();
			router = findRouter(routerClassName);
		} else if (action.startsWith("redirect:")) {
			// Redirection.

			String uri = action.substring(9).trim();

			// Check for explicit redirection status code.
			if (nextTokenPos < tokens.length) {
				int status;

				String statusToken = tokens[nextTokenPos++];

				try {
					status = Integer.parseInt(statusToken);
				} catch (Exception e) {
					throw new RuntimeException("Qlue: Invalid redirection status in route: " + statusToken);
				}
				
				router = new RedirectionRouter(uri, status);
			} else {
				router = new RedirectionRouter(uri);
			}
		} else if (action.startsWith("status:")) {
		    // Respond with status code.

			String statusCodeString = action.substring(7).trim();
			int statusCode;
			
			try {
				// Parse and validate response status code.
				statusCode = Integer.parseInt(statusCodeString);
				if ((statusCode >= 100) && (statusCode <= 999)) {
					if (nextTokenPos < tokens.length) {
						// Recombine the remaining tokens back
						// into a single string.
						StringBuilder sb = new StringBuilder();
						for(int i = nextTokenPos; i <= tokens.length - 1; i++) {
							if (i > nextTokenPos) {
								sb.append(' ');
							}
							
							sb.append(tokens[i]);
						}
						nextTokenPos++;
						
						// Status code and message.
						router = new StatusCodeRouter(statusCode, sb.toString());
					} else {
						// Status code only.
						router = new StatusCodeRouter(statusCode);
					}
				} else {
					throw new RuntimeException("Qlue: Invalid status code in route: " + statusCodeString);
				}
			} catch (Exception e) {
				throw new RuntimeException("Qlue: Invalid status code in route: " + statusCodeString);
			}
		} else if (action.startsWith("static:")) {
			// Static route.

			String staticPath = action.substring(7).trim();
			
			if (nextTokenPos < tokens.length) {
				// Recombine the remaining tokens back into a single string.
				StringBuilder sb = new StringBuilder();
				sb.append(staticPath);
				sb.append(' ');
				
				for(int i = nextTokenPos; i <= tokens.length - 1; i++) {
					if (i > nextTokenPos) {
						sb.append(' ');
					}
					
					sb.append(tokens[i]);
				}
				nextTokenPos++;
				
				// Status code and message.
				router = new StaticFileRouter(manager, sb.toString());
			} else {
				// Status code only.
				router = new StaticFileRouter(manager, staticPath);
			}					
		} else {
			// Route directly to a class.
			router = new ClassRouter(action);
		}

		return new Route(acceptedMethods, path, router);
	}

	private static EnumSet<RouteMethod> parseAcceptedMethods(String token) {
		EnumSet<RouteMethod> acceptedMethods = EnumSet.noneOf(RouteMethod.class);

		String[] methods = token.split(",");
		for (String m : methods) {
			acceptedMethods.add(RouteMethod.valueOf(m));
		}

		return acceptedMethods;
	}

	private static Route createMetaRoute(RouteManager manager, String route) {
        Matcher m = metaRoutePattern.matcher(route);
        if (!m.matches()) {
            throw new RuntimeException("Qlue: Invalid config route: " + route);
        }

        String configDirective = m.group(1);
        String configText = m.group(2);

        switch(configDirective) {
			case "header":
				return new Route(null, null, HeaderConfigRouter.fromString(manager, configText));
			case "define":
				DefineConfigRouter.updateProperties(manager, configText);
				return null;
			default:
				throw new RuntimeException("Qlue: Unknown configuration directive: " + configDirective);
		}
    }

    /**
	 * Finds router instance based on class name.
	 */
	private static Router findRouter(String className) {
		Class candidate = null;

		try {
			ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
			candidate = Class.forName(className, true, classLoader);
		} catch (Exception e) {
			throw new RuntimeException("ClassRouter: Unknown class: " + className);
		}

		if (!Router.class.isAssignableFrom(candidate)) {
			throw new RuntimeException("ClassRouter: Class " + className + " is not a subclass of Page.");
		}

		try {
			return (Router) candidate.newInstance();
		} catch (Exception e) {
			throw new RuntimeException("ClassRouter: Failed to create class instance: " + e.getMessage(), e);
		}
	}
}
