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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.webkreator.qlue.TransactionContext;

public class Route {

	private Log log = LogFactory.getLog(Route.class);

	private String path;

	private Pattern pattern;

	private List<String> names = new ArrayList<String>();

	private Router router;

	private static final Pattern namePattern = Pattern
			.compile("^[a-zA-Z][a-zA-Z0-9_]{0,32}$");

	public Route(String path, Router router) {
		this.path = path;
		this.router = router;
		processPath();
	}

	public void processPath() {
		Pattern p = Pattern.compile("([^{]*)(\\{[^}]*\\})(.+)?");
		StringBuffer sb = new StringBuffer();
		sb.append('^');

		String haystack = path;
		boolean terminated = false;
		Matcher m = p.matcher(haystack);
		while ((m != null) && (m.find())) {
			sb.append(m.group(1));

			// Extract name by removing the curly braces
			String name = m.group(2);
			name = name.substring(1, name.length() - 1).trim();

			String pattern = "[^/]+";

			// Is this the terminating parameter?
			if (name.length() == 0) {
				pattern = ".*";
				terminated = true;
				name = "routeSuffix";
			} else {
				// Check for a custom pattern
				if (name.charAt(0) == '<') {
					pattern = name.substring(1, name.indexOf('>'));
					name = name.substring(name.indexOf('>') + 1);
				}

				if (name.length() == 0) {
					throw new RuntimeException(
							"Qlue: Empty URL parameter name in route");
				}

				Matcher nameMatcher = namePattern.matcher(name);
				if (nameMatcher.matches() == false) {
					throw new RuntimeException("Qlue: Invalid URL parameter: "
							+ name);
				}
			}

			names.add(name);
			sb.append('(');
			sb.append(pattern);
			sb.append(')');

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

		if (haystack != null) {
			sb.append(haystack);
		}

		// sb.append("(/.*)?$");
		sb.append("$");

		try {
			pattern = Pattern.compile(sb.toString());
		} catch (PatternSyntaxException pse) {
			throw new RuntimeException("Failed to compile route: " + path, pse);
		}
	}

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

		// Return the route
		return router.route(tx, routeSuffix);
	}

	public String getPath() {
		return path;
	}
}
