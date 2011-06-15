package com.webkreator.qlue.router;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
		Matcher m = p.matcher(haystack);
		while ((m != null) && (m.find())) {
			sb.append(m.group(1));

			// Extract name by removing the curly braces
			String name = m.group(2);
			name = name.substring(1, name.length() - 1).trim();

			if (name.length() == 0) {
				throw new RuntimeException(
						"Qlue: Empty URL parameter name in route");
			}

			String pattern = "[^/]+";

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

			names.add(name);
			sb.append('(');
			sb.append(pattern);
			sb.append(')');

			haystack = m.group(3);

			if (haystack != null) {
				m = p.matcher(haystack);
			} else {
				m = null;
			}
		}

		if (haystack != null) {
			sb.append(haystack);
		}

		sb.append("(/.*)?$");

		// TODO Replace compilation failure with a friendly message
		pattern = Pattern.compile(sb.toString());
	}

	public Object route(TransactionContext tx) {
		// Try to match
		Matcher m = pattern.matcher(tx.getRequestUri());
		if (m.matches() == false) {
			return null;
		}

		// Extract URL parameters
		int count = 1;
		for (String name : names) {
			String value = m.group(count++);

			if (log.isDebugEnabled()) {
				log.debug("Route: Adding URL parameter: " + name + "=" + value);
			}

			tx.addUrlParameter(name, value);
		}

		String extraPath = null;

		if (m.groupCount() >= count) {
			extraPath = m.group(count);
		}

		if (log.isDebugEnabled()) {
			log.debug("Route: Extra path: " + extraPath);
		}

		// Return the route
		return router.route(tx, extraPath);
	}

	public String getPath() {
		return path;
	}
}
