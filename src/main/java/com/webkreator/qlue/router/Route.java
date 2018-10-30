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

import com.webkreator.qlue.TransactionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Represents one route in the routing table.
 */
public class Route {

	private Logger log = LoggerFactory.getLogger(Route.class);

	private EnumSet<RouteMethod> acceptedMethods;

	private String path;

	private Pattern pattern;

	private List<String> names = new ArrayList<>();

	private Router router;

	private RouteManager manager;

	private static final Pattern namePattern = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]{0,32}$");

	private boolean redirects = false;

	/**
	 * Creates new route, given path and router instance.
	 */
	public Route(EnumSet<RouteMethod> acceptedMethods, String path, Router router, RouteManager manager) {
		this.acceptedMethods = acceptedMethods;
		this.path = path;
		this.router = router;
		this.manager = manager;

        if (path != null) {
            processPath();
        }
	}

	/**
	 * Converts route path into a regular expression that is design to extract
	 * named path parameters.
	 */
	public void processPath() {
		// Start building actual path with an anchor at the beginning
		StringBuilder sb = new StringBuilder();
		sb.append('^');

		// Loop through the path to find path parameters and
		// replace them with regular expression captures
		String haystack = path;
		boolean terminated = false;

		int i;
		while ((i = haystack.indexOf("{")) != -1) {
			// The prefix is the bit that came before the starting brace
			String prefix = haystack.substring(0, i);

			// Extract the parameter by finding the matching ending brace
			int j = findEndingBrace(haystack, i);
			String name = haystack.substring(i, j + 1);

			// The remaining part, which is the bit that came after
			// the parameter, is now the new haystack
			haystack = haystack.substring(j + 1);

			boolean optional = false;
			if (name.charAt(name.length() - 1) == '?') {
				optional = true;
				name = name.substring(1, name.length() - 2).trim();
			} else {
				name = name.substring(1, name.length() - 1).trim();
			}

			String pattern = "[^/]+";

			// Is this the terminating parameter?
			if (name.length() == 0) {
				// This path parameter does not have a name, which
				// means it is the route suffix (the final part in a path)
				pattern = ".*";
				terminated = true;
				name = "pathSuffix";
			} else {
				// This path parameter has a name

				// Check for a custom pattern
				if (name.charAt(0) == '<') {
					// Separate path parameter name and custom pattern
					pattern = name.substring(1, name.indexOf('>'));
					name = name.substring(name.indexOf('>') + 1);
				}

				// Empty parameters with custom patterns are not allowed
				if (name.length() == 0) {
					throw new RuntimeException("Qlue: Empty URL parameter name in route");
				}

				// Check that the parameter name is valid
				Matcher nameMatcher = namePattern.matcher(name);
				if (nameMatcher.matches() == false) {
					throw new RuntimeException("Qlue: Invalid URL parameter: " + name);
				}
			}

			// Remember path parameter name
			names.add(name);

			// Special case when path ends with "/{}", because
			// we want to issue a redirection from directory
			// paths that are not slash-terminated (e.g., if
			// we get /test we redirect to /test/
			if ((terminated) && ( prefix.endsWith("/") || prefix.endsWith("/?") )) {
				if (prefix.endsWith("/")) {
					// Remove / from the end of the prefix
					prefix = prefix.substring(0, prefix.length() - 1);
					sb.append(Pattern.quote(prefix));

					// Add / to the beginning of the last capture,
					// but make the capture optional
					sb.append("(?:/(");
					sb.append(pattern);
					sb.append("))?");

					// Indicate that, at runtime, we will need to
					// detect an empty final capture and issue
					// a redirection if there's not a terminating
					// forward slash character at the end of request URI
					redirects = true;
				} else {
					// Remove /? from the end of the prefix
					prefix = prefix.substring(0, prefix.length() - 2);
					sb.append(Pattern.quote(prefix));

					// Add / to the beginning of the last capture,
					// but make the capture optional
					sb.append("(?:/?(");
					sb.append(pattern);
					sb.append("))?");

					redirects = false;
				}
			} else {
				sb.append(Pattern.quote(prefix));
				sb.append('(');
				sb.append(pattern);
				sb.append(')');
			}

			if ((haystack != null) && (haystack.length() > 0)) {
				if (terminated) {
					throw new RuntimeException("Qlue: Terminating URI parameter must be at the end");
				}
			}
		}

		// When we're here that means that there are no more
		// path parameters left in the haystack; we can append
		// it to the pattern to finish the process
		if (haystack != null) {
			if (haystack.endsWith("/?")) {
				// Special handling for routes that want to respond
				// to both "/folder" and "/folder/" without a redirection.
				redirects = false;
				haystack = haystack.substring(0, haystack.length() - 2);
				sb.append(Pattern.quote(haystack));
				sb.append("/?");
			} else if (haystack.endsWith("/")) {
				// Special handling for routes that end with "/" we
				// want to respond with a redirection when the final /
				// is not supplied (e.g., /test -> /test/). To achieve
				// that, the last / in the path becomes optional and
				// we add further checks at runtime
				redirects = true;
				haystack = haystack.substring(0, haystack.length() - 1);
				sb.append(Pattern.quote(haystack));
				sb.append("/?");
			} else {
				sb.append(Pattern.quote(haystack));
			}
		}

		sb.append("$");

		try {
			pattern = Pattern.compile(sb.toString());
		} catch (PatternSyntaxException pse) {
			throw new RuntimeException("Failed to compile route: " + path, pse);
		}
	}

	private int findEndingBrace(String haystack, int startingPost) {
		int depth = 0;
		for (int i  = startingPost; i < haystack.length(); i++) {
			if (haystack.charAt(i) == '{') {
				depth++;
			}
			if (haystack.charAt(i) == '}') {
				depth--;
				if (depth == 0) {
					return i;
				}
			}
		}

		throw new RuntimeException("Ending brace not found in haystack: " + haystack);
	}

	/**
	 * Attempts to match the transaction to this route and, if successful,
	 * returns the route associated with the route.
	 */
	public Object route(TransactionContext tx) {
        // If the path is null, that means this is a meta route,
		// and we always accept transactions.
        if (path == null) {
            return router.route(tx, this, null);
        }

		// Check if the request method matches.
		RouteMethod method = RouteMethod.fromTransaction(tx);
		if (!acceptedMethods.contains(method)) {
			return null;
		}

		// Otherwise, attempt to match the request URI to the path we have.

		Matcher m = pattern.matcher(tx.getRequestUri());
		if (m.matches() == false) {
			return null;
		}

		// Extract URL parameters
		int count = 1;
		String pathSuffix = null;
		for (String name : names) {
			String value = m.group(count++);

			if (log.isDebugEnabled()) {
				log.debug("Route: Adding URL parameter: " + name + "=" + value);
			}

			tx.addUrlParameter(name, value);

			if (name.compareTo("pathSuffix") == 0) {
				pathSuffix = value;
			}
		}

		if (manager.isRedirectFolderWithoutTrailingSlash()) {
			if (redirects && (tx.getRequestUri().endsWith("/") == false)
					&& ((pathSuffix == null) || (pathSuffix.length() == 0))) {
				return RedirectionRouter.newAddTrailingSlash(tx, 307).route(tx, this, pathSuffix);
			}
		}

		// Return the route
		return router.route(tx, this, pathSuffix);
	}

	/**
	 * Returns the patch attached to this route.
	 */
	public String getPath() {
		return path;
	}

	public boolean acceptsMethod(RouteMethod method) {
		return acceptedMethods.contains(method);
	}

	public boolean isRedirectsWithoutTrailingSlash() {
		return redirects;
	}
}
