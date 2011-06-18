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

import java.util.StringTokenizer;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.webkreator.qlue.Page;
import com.webkreator.qlue.SecurityException;
import com.webkreator.qlue.TransactionContext;

public class PackageRouter implements Router {
	
	protected RouteManager manager;

	private Log log = LogFactory.getLog(ClassRouter.class);

	private String packageName;	

	public PackageRouter(RouteManager manager, String packageName) {
		this.manager = manager;
		this.packageName = packageName;
	}

	@Override
	public Object route(TransactionContext context, String extraPath) {
		return resolveUri(extraPath, packageName);
	}

	public Object resolveUri(String routeSuffix, String rootPackage) {
		@SuppressWarnings("rawtypes")
		Class pageClass = null;

		if (routeSuffix.indexOf("..") != -1) {
			throw new SecurityException(
					"Directory backreferences not allowed in path");
		}

		// Handle URI suffix
		String suffix = manager.getSuffix();
		if ((suffix != null) && (routeSuffix.endsWith(suffix))) {
			// Remove suffix from URI
			routeSuffix = routeSuffix.substring(0, routeSuffix.length() - 5);
		}

		// Start building class name.
		StringBuilder sb = new StringBuilder();

		// Start with the root package.
		sb.append(rootPackage);

		// Each folder in the URI corresponds to a package name.
		String lastToken = null;
		StringTokenizer st = new StringTokenizer(routeSuffix, "/");
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
			pageClass = classForName(className + "." + manager.getIndex());
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
