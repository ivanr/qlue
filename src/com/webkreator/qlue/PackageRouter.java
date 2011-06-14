package com.webkreator.qlue;

import java.util.StringTokenizer;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class PackageRouter implements Router {

	private Log log = LogFactory.getLog(ClassRouter.class);

	private String packageName;

	private String uriSuffix = ".html";

	public PackageRouter(String packageName) {
		this.packageName = packageName;
	}

	@Override
	public Object route(TransactionContext tx, String extraPath) {
		return resolveUri(extraPath, packageName);
	}

	public Object resolveUri(String uri, String rootPackage) {
		@SuppressWarnings("rawtypes")
		Class pageClass = null;

		if (uri.indexOf("..") != -1) {
			throw new SecurityException(
					"Directory backreferences not allowed in path");
		}

		// Handle URI suffix
		if ((uriSuffix != null) && (uri.endsWith(uriSuffix))) {
			// Remove suffix from URI
			uri = uri.substring(0, uri.length() - 5);
		}

		// Start building class name.
		StringBuilder sb = new StringBuilder();

		// Start with the root package.
		sb.append(rootPackage);

		// Each folder in the URI corresponds to a package name.
		String lastToken = null;
		StringTokenizer st = new StringTokenizer(uri, "/");
		while (st.hasMoreTokens()) {
			if (lastToken != null) {
				sb.append(".");
				sb.append(lastToken);
			}

			lastToken = st.nextToken();
		}

		if (lastToken != null) {
			sb.append(".");
			sb.append(lastToken);
		}

		String className = sb.toString();
		
		// Look for a class with this name
		pageClass = classForName(className);
		if (pageClass == null) {
			// Try as a folder
			pageClass = classForName(className + ".index");
			if (pageClass == null) {
				return null;
			}
		}

		// Check class is instance of Page
		if (!Page.class.isAssignableFrom(pageClass)) {
			throw new RuntimeException("ClassPageResolver: Class " + className
					+ " is not a subclass of Page.");
		}

		try {
			return pageClass.newInstance();
		} catch (Exception e) {
			log.error("Error creating page instance: " + e.getMessage(), e);
			return null;
		}
	}

	@SuppressWarnings("rawtypes")
	public static Class classForName(String classname) {
		try {
			ClassLoader classLoader = Thread.currentThread()
					.getContextClassLoader();
			return Class.forName(classname, true, classLoader);
		} catch (ClassNotFoundException cnfe) {
			return null;
		} catch (NoClassDefFoundError ncdfe) {
			// NoClassDefFoundError is thrown when there is a class
			// that matches the name when ignoring case differences.
			// We do not care about that.
			return null;
		}
	}
}
