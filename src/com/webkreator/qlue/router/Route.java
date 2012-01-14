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

import java.text.CharacterIterator;
import java.text.StringCharacterIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.webkreator.qlue.TransactionContext;

/**
 * Represents one route in the routing table.
 */
public class Route {

	private Log log = LogFactory.getLog(Route.class);

	private String path;

	private Pattern pattern;

	private List<String> names = new ArrayList<String>();

	private Router router;

	private static final Pattern namePattern = Pattern
			.compile("^[a-zA-Z][a-zA-Z0-9_]{0,32}$");

	private boolean redirects = false;

	/**
	 * Creates new route, given path and router instance.
	 * 
	 * @param path
	 * @param router
	 */
	public Route(String path, Router router) {
		this.path = path;
		this.router = router;

		processPath();
	}

	/**
	 * Converts route path into a regular expression that is design to extract
	 * named path parameters.
	 */
	public void processPath() {
		// Start building actual path with an anchor at the beginning
		StringBuffer sb = new StringBuffer();
		sb.append('^');

		// Loop through the path to find path parameters and
		// replace them with regular expression captures
		String haystack = path;
		boolean terminated = false;
		Pattern p = Pattern.compile("([^{]*)(\\{[^}]*\\})(.+)?");
		Matcher m = p.matcher(haystack);
		while ((m != null) && (m.find())) {
			// Append the first group, which is static content
			String prefix = m.group(1);

			// Extract parameter name by removing the curly braces
			String name = m.group(2);
			name = name.substring(1, name.length() - 1).trim();

			String pattern = "[^/]+";

			// Is this the terminating parameter?
			if (name.length() == 0) {
				// This path parameter does not have a name, which
				// means it is the route suffix (the final part in a path)
				pattern = ".*";
				terminated = true;
				name = "routeSuffix";
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
					throw new RuntimeException(
							"Qlue: Empty URL parameter name in route");
				}

				// Check that the parameter name is valid
				Matcher nameMatcher = namePattern.matcher(name);
				if (nameMatcher.matches() == false) {
					throw new RuntimeException("Qlue: Invalid URL parameter: "
							+ name);
				}
			}

			// Remember path parameter name
			names.add(name);

			// Special case when path ends with "/{}", because
			// we want to issue a redirection from directory
			// paths that are not slash-terminated (e.g., if
			// we get /test we redirect to /test/
			if ((terminated) && (prefix.endsWith("/"))) {
				// Remove / from the end of the prefix
				prefix = prefix.substring(0, prefix.length() - 1);
				sb.append(escapePatternMetachars(prefix));

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
				sb.append(escapePatternMetachars(prefix));
				sb.append('(');
				sb.append(pattern);
				sb.append(')');
			}

			// The last group, which is the bit that came after
			// the parameter, is now the haystack
			haystack = m.group(3);

			if ((haystack != null) && (haystack.length() > 0)) {
				if (terminated) {
					throw new RuntimeException(
							"Qlue: Terminating URI parameter must be at the end");
				}

				m = p.matcher(haystack);
			} else {
				m = null;
			}
		}

		// When we're here that means that there are no more
		// path parameters left in the haystack; we can append
		// it to the pattern to finish the process
		if (haystack != null) {
			if (haystack.endsWith("/")) {
				// Special handling for routes that end with "/" we
				// want to respond with a redirection when the final /
				// is not supplied (e.g., /test -> /test/). To achieve
				// that, the last / in the path becomes optional and
				// we add further checks at runtime
				redirects = true;
				haystack = haystack.substring(0, haystack.length() - 1);
				sb.append(haystack);
				sb.append("/?");
			} else {
				sb.append(haystack);
			}
		}

		sb.append("$");

		try {
			pattern = Pattern.compile(sb.toString());
		} catch (PatternSyntaxException pse) {
			throw new RuntimeException("Failed to compile route: " + path, pse);
		}
	}

	/**
	 * Escapes input to neutralize all pattern metacharacters except for the
	 * question mark.
	 * 
	 * @param input
	 * @return
	 */
	private String escapePatternMetachars(String input) {
		StringBuffer sb = new StringBuffer();

		CharacterIterator it = new StringCharacterIterator(input);
		for (char c = it.first(); c != CharacterIterator.DONE; c = it.next()) {
			switch (c) {
			case '{':
			case '}':
			case '+':
			case '.':
			case '[':
			case ']':
			case '*':
			case '^':
			case '$':
			case '\\':
			case '|':
			case '?':
				sb.append('\\');
			default:
				sb.append(c);
			}
		}

		return sb.toString();
	}

	/**
	 * Attempts to match the transaction to this route and, if successful,
	 * returns the route assocaited with the route.
	 * 
	 * @param tx
	 * @return
	 */
	public Object route(TransactionContext tx) {
		// Try to match
		Matcher m = pattern.matcher(tx.getRequestUri());
		if (m.matches() == false) {
			return null;
		}

		// Extract URL parameters
		int count = 1;
		String routeSuffix = null;
		for (String name : names) {
			String value = m.group(count++);

			if (log.isDebugEnabled()) {
				log.debug("Route: Adding URL parameter: " + name + "=" + value);
			}

			tx.addUrlParameter(name, value);

			if (name.compareTo("routeSuffix") == 0) {
				routeSuffix = value;
			}
		}

		if (redirects && (tx.getRequestUri().endsWith("/") == false)
				&& ((routeSuffix == null) || (routeSuffix.length() == 0))) {
			return new RedirectionRouter(tx.getRequestUri() + "/", 302).route(
					tx, routeSuffix);
		}

		// Return the route
		return router.route(tx, routeSuffix);
	}

	/**
	 * Returns the patch attached to this route.
	 * 
	 * @return
	 */
	public String getPath() {
		return path;
	}
}
