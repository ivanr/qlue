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

import com.webkreator.qlue.Page;
import com.webkreator.qlue.QlueApplication;
import com.webkreator.qlue.TransactionContext;
import com.webkreator.qlue.exceptions.QlueException;
import com.webkreator.qlue.view.ClasspathView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.util.StringTokenizer;

/**
 * Routes transaction to an entire package, with
 * an unlimited depth.
 */
public class PackageRouter implements Router {

    private static Logger log = LoggerFactory.getLogger(PackageRouter.class);

    private String rootPackage;

    private String rootPackageAsPath;

    protected RouteManager manager;

    public PackageRouter(final RouteManager manager, final String packageName) {
        this.manager = manager;

        // If there is a package root (prefix), use it first to look
        // for our package. If we attempt to use an abbreviated package
        // name, we may find one of the standard Java packages (e.g., "org").
        String prefixedPackageName = null;
        String packageRoot = manager.getProperties().getProperty(RouteManager.PACKAGE_ROOT);
        if (packageRoot != null) {
            prefixedPackageName = addPrefixToName(packageRoot, packageName);
            rootPackage = prefixedPackageName;
            rootPackageAsPath = convertPackageNameToPath(rootPackage);
            if (packagePathExists(rootPackageAsPath)) {
                return;
            }
        }

        // Now try the package name as provided.
        rootPackage = packageName;
        rootPackageAsPath = convertPackageNameToPath(rootPackage);
        if (packagePathExists(rootPackageAsPath)) {
            return;
        }

        // We couldn't find the package.
        if (prefixedPackageName == null) {
            throw new IllegalArgumentException("Package doesn't exist: " + rootPackage);
        } else {
            throw new IllegalArgumentException("Package doesn't exist. Tried " + rootPackage + " and " + prefixedPackageName);
        }
    }

    @Override
    public Object route(TransactionContext tx, Route route, String pathSuffix) {
        return resolveUri(tx, route, pathSuffix);
    }

    /**
     * Returns page instance for a given URL.
     *
     * @param path
     * @return page instance, or null if page cannot be found
     */
    public Object resolveUri(TransactionContext tx, Route route, String path) {

        // Refuse directory backreferences.
        if (path.indexOf("..") != -1) { // not exactly a directory backreference, but it will do
            throw new QlueException("Directory backreferences not allowed in path")
                    .setSecurityFlag();
        }

        String classpath = convertUrlPathToClasspath(path); // converts dashes as well
        if (classpath == null) {
            return null;
        }

        String normalizedPath = convertDashes(path);
        String filepath = rootPackageAsPath + normalizedPath;

        // Try the class exactly as requested.

        if (log.isDebugEnabled()) {
            log.debug("Trying class: " + classpath);
        }

        Class clazz = QlueApplication.classForName(classpath);
        if (isPage(clazz)) {
            if (path.endsWith("/" + manager.getIndex())) {
                // Redirect to canonical.
                return RedirectionRouter.newWithoutSuffix(tx, manager.getIndex(), 307).
                        route(tx, route, path);
            } else {
                return makePage(clazz);
            }
        }

        // Try to see if it's a direct view.

        String viewPath = filepath + ".vmx";

        if (log.isDebugEnabled()) {
            log.debug("Trying direct view: " + viewPath);
        }

        if (getClass().getClassLoader().getResource(viewPath) != null) {
            if (path.endsWith("/" + manager.getIndex())) {
                // Redirect to canonical.
                return RedirectionRouter.newWithoutSuffix(tx, manager.getIndex(), 307)
                        .route(tx, route, path);
            } else {
                return new ClasspathView(viewPath);
            }
        }

        // Try to see if it's a folder.

        classpath = classpath + "." + manager.getIndex();
        filepath = filepath + "/" + manager.getIndex();

        if (log.isDebugEnabled()) {
            log.debug("Trying class: " + classpath);
        }

        clazz = QlueApplication.classForName(classpath);
        if (isPage(clazz)) {
            if ((path.length() == 0) || (path.endsWith("/"))) {
                return makePage(clazz);
            } else {
                // Redirect to canonical.
                return RedirectionRouter.newAddTrailingSlash(tx, 307)
                        .route(tx, route, path);
            }
        }

        // Try to see if it's a direct folder view.

        viewPath = filepath + ".vmx";

        if (log.isDebugEnabled()) {
            log.debug("Trying direct view: " + viewPath);
        }

        if (getClass().getClassLoader().getResource(viewPath) != null) {
            if ((path.length() == 0) || (path.endsWith("/"))) {
                return new ClasspathView(viewPath);
            } else {
                // Redirect to canonical.
                return RedirectionRouter.newAddTrailingSlash(tx, 307)
                        .route(tx, route, path);
            }
        }

        // Not found.
        return null;
    }

    private String convertDashes(String path) {
        if (manager.isConvertDashesToUnderscores()) {
            return path.replaceAll("-", "_");
        } else {
            return path;
        }
    }

    private boolean isPage(Class pageClass) {
        if ((pageClass != null) && Page.class.isAssignableFrom(pageClass)) {
            return true;
        } else {
            return false;
        }
    }

    private Page makePage(Class pageClass) {
        try {
            return (Page) pageClass.newInstance();
        } catch (Exception e) {
            log.error("Failed to instantiate class: ", e);
            return null;
        }
    }

    private String convertUrlPathToClasspath(String path) {
        // Start building class name.
        StringBuilder sb = new StringBuilder();

        // Start with the root package.
        sb.append(rootPackage);

        // Each folder in the URI corresponds to a package name.
        String lastToken = null;
        StringTokenizer st = new StringTokenizer(path, "/");
        while (st.hasMoreTokens()) {
            if (lastToken != null) {
                // We don't allow path segments with periods, because
                // they might interfere with class path construction.
                if (lastToken.indexOf('.') != -1) {
                    return null;
                }

                if (manager.isConvertDashesToUnderscores()) {
                    lastToken = lastToken.replace('-', '_');
                }

                sb.append(".");
                sb.append(lastToken);
            }

            lastToken = st.nextToken();

            // We don't serve path segments whose names begin with $.
            // Such packages are considered to be private.
            if ((lastToken.length() > 0) && (lastToken.charAt(0) == '$')) {
                return null;
            }
        }

        if (lastToken != null) {
            if (manager.isConvertDashesToUnderscores()) {
                lastToken = lastToken.replace('-', '_');
            }

            sb.append(".");
            sb.append(lastToken);
        }

        return sb.toString();
    }

    public static String addPrefixToName(String prefix, String name) {
        if (name.length() != 0) {
            return prefix + "." + name;
        } else {
            return prefix;
        }
    }

    private static String convertPackageNameToPath(String packageName) {
        return packageName.replace('.', '/') + "/";
    }

    private static boolean packagePathExists(String packagePath) {
        try {
            URL u = Thread.currentThread().getContextClassLoader().getResource(packagePath);
            if (u == null) {
                return false;
            } else {
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

}
