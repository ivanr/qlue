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

import java.util.StringTokenizer;

import com.webkreator.qlue.*;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.webkreator.qlue.exceptions.QlueSecurityException;

/**
 * Routes transaction to an entire package, with
 * an unlimited depth.
 */
public class PackageRouter implements Router {

    private Log log = LogFactory.getLog(ClassRouter.class);

    private String packageName;
	
	protected RouteManager manager;

	public PackageRouter(RouteManager manager, String packageName) {
		this.manager = manager;
		this.packageName = packageName;
	}

	@Override
	public Object route(TransactionContext tx, String routeSuffix) {
		return resolveUri(tx, routeSuffix, packageName);
	}

	/**
	 * Returns page instance for a given URL.
	 *  
	 * @param path
	 * @param rootPackage
	 * @return page instance, or null if page cannot be found
	 */
	public Object resolveUri(TransactionContext tx, String path, String rootPackage) {
		@SuppressWarnings("rawtypes")
		Class pageClass = null;

		if (path.indexOf("/../") != -1) {
			throw new QlueSecurityException("Directory backreferences not allowed in path");
		}

		// Start building class name.
		StringBuilder sb = new StringBuilder();
        String urlSuffix = null;

		// Start with the root package.
		sb.append(rootPackage);

		// Each folder in the URI corresponds to a package name.
		String lastToken = null;
		StringTokenizer st = new StringTokenizer(path, "/");
		while (st.hasMoreTokens()) {
			if (lastToken != null) {
                // We also don't allow path segments with periods, because
                // they might interfere with class construction.
                if (lastToken.indexOf('.') != -1) {
                    return null;
                }

				sb.append(".");
				sb.append(lastToken);
			}

			lastToken = st.nextToken();
			
			// We don't serve path segments whose names begin with $.
			// Such packages are considered to be private.
			if ((lastToken.length() > 0)&&(lastToken.charAt(0) == '$')) {
				return null;
			}
		}

		if (lastToken != null) {
            // We allow one dot in the last segment, which could be a file suffix.
            int i = lastToken.indexOf('.');
            if (i != -1) {
                urlSuffix = lastToken.substring(i);
                lastToken = lastToken.substring(0, i);
                log.debug("Detected suffix: " + urlSuffix);
            }

			sb.append(".");
			sb.append(lastToken);
		}

		String className = sb.toString();
		
		log.debug("Trying class: " + className);
		
		// Look for a class with this name
		pageClass = classForName(className);
		if (pageClass == null) {
			// We're here because we couldn't directly translate a request path
			// into a class. So this might be directory access. But if we do
			// find an index file, before we route to it we need to check if
			// there is a terminating forward slash in the request URI. If not,
			// we need to issue a redirect.
			
			// Look for the index page
			pageClass = classForName(className + "." + manager.getIndex());
			log.debug("Trying class: " + className + "." + manager.getIndex());
			if (pageClass == null) {
				// Not found, probably a 404
				return null;
			} else {
				// We have found the index page
				log.debug("Found index page");
				
				// Check if we need to issue a redirection
				if (tx.getRequestUri().endsWith("/") == false) {
					log.debug("Redirecting to " + tx.getRequestUri() + "/");
					return new RedirectionRouter(tx.getRequestUri() + "/", 302).route(tx, path);
				}
			}
		}

		// Check class is instance of Page
		if (!Page.class.isAssignableFrom(pageClass)) {
			throw new RuntimeException("Class " + className + " is not a subclass of Page");
		}

        // Check that suffixes match.

        String pageSuffix = manager.getSuffix();
        QlueMapping mapping = (QlueMapping)pageClass.getAnnotation(QlueMapping.class);
        if (mapping != null) {
            if (!mapping.suffix().equals("inheritAppSuffix")) {
                pageSuffix = mapping.suffix();
            }
        }

        log.debug("Suffixes: URL: " + urlSuffix + "; page: " + pageSuffix);

        if (urlSuffix != null) {
            // URL suffix present.

            if (pageSuffix == null) {
                log.debug("Suffix mismatch: URI has suffix but page doesn't");
                return null;
            }

            if (!pageSuffix.equals(urlSuffix)) {
                log.debug("Suffix mismatch: URI: " + urlSuffix + "; page: " + pageSuffix);
                return null;
            }
        } else {
            // URL suffix not present.

            if (pageSuffix != null) {
                log.debug("Suffix mismatch: page has suffix but URI doesn't");
                return null;
            }
        }

		try {
			log.debug("Creating new instance of " + pageClass);
			return pageClass.newInstance();
		} catch (Exception e) {
			log.error("Error creating page instance: " + e.getMessage(), e);
			return null;
		}
	}

	/**
	 * Returns class given its name.
	 * 
	 * @param name
	 * @return
	 */
	@SuppressWarnings("rawtypes")
	public static Class classForName(String name) {
		try {
			ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
			return Class.forName(name, true, classLoader);
		} catch (ClassNotFoundException e) {
			return null;
		} catch (NoClassDefFoundError e) {
			// NoClassDefFoundError is thrown when there is a class
			// that matches the name when ignoring case differences.
			// We do not care about that.
			return null;
		}
	}
}
