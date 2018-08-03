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
import com.webkreator.qlue.annotations.QlueMapping;
import com.webkreator.qlue.TransactionContext;
import com.webkreator.qlue.exceptions.QlueSecurityException;
import com.webkreator.qlue.view.ClasspathView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
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

    public PackageRouter(RouteManager manager, String packageName) {
        this.manager = manager;
        this.rootPackage = packageName;
        this.rootPackageAsPath = packageName.replace('.', '/') + "/";
    }

    @Override
    public Object route(TransactionContext tx, String pathSuffix) {
        return resolveUri(tx, pathSuffix);
    }

    /**
     * Returns page instance for a given URL.
     *
     * @param path
     * @return page instance, or null if page cannot be found
     */
    public Object resolveUri(TransactionContext tx, String path) {
        @SuppressWarnings("rawtypes")
        Class pageClass;

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

            // If there's a dot in the last segment, we consider that it
            // marks the beginning of a file suffix.
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
        pageClass = QlueApplication.classForName(className);
        if (pageClass == null) {
            // Try a direct view.
            String classpathFilename;
            String classpathBase;

            // Determine if we need to strip the suffix from the path or leave everything as is.
            String suffix = manager.getSuffix();
            if ((suffix != null)&&(path.endsWith(suffix))) {
                classpathBase = rootPackage + path.substring(0, path.length() - suffix.length());
            } else {
                classpathBase = rootPackageAsPath + path;
            }

            classpathFilename = classpathBase + ".vmx";

            if (log.isDebugEnabled()) {
                log.debug("Trying direct view: " + classpathFilename);
            }

            if (getClass().getClassLoader().getResource(classpathFilename) != null) {
                return new ClasspathView(classpathFilename);
            }

            // Try the priority path. This is a little inefficient, but this
            // feature is intended for use in development only.
            String priorityPath = manager.getPriorityTemplatePath();
            if (priorityPath != null) {
                // We just need to check if the file exists on the alternative
                // patch. If it does, the Velocity engine should be able to find it.
                // So our goal here basically is not to signal "file not found".
                File f = new File(priorityPath, classpathFilename);
                if (f.exists()) {
                    return new ClasspathView(classpathFilename);
                }
            }

            // Check for directory access by looking for an index page.
            pageClass = QlueApplication.classForName(className + "." + manager.getIndex());

            if (log.isDebugEnabled()) {
                log.debug("Trying class: " + className + "." + manager.getIndex());
            }

            if (pageClass == null) {
                // Before we give up, another try with the priority path,
                // this time looking for "index.vmx".
                if (priorityPath != null) {
                    classpathFilename = classpathBase + "/index.vmx";
                    File f = new File(priorityPath, classpathFilename);
                    if (f.exists()) {
                        // If there's no terminating slash in directory access, issue a redirection.
                        if (tx.getRequestUri().endsWith("/") == false) {
                            if (log.isDebugEnabled()) {
                                log.debug("Redirecting to " + tx.getRequestUri() + "/");
                            }
                            return new RedirectionRouter(tx.getRequestUri() + "/", 302).route(tx, path);
                        }

                        return new ClasspathView(classpathFilename);
                    }
                }

                return null;
            }

            if (log.isDebugEnabled()) {
                log.debug("Found index page");
            }

            // If there's no terminating slash in directory access, issue a redirection.
            if (tx.getRequestUri().endsWith("/") == false) {
                if (log.isDebugEnabled()) {
                    log.debug("Redirecting to " + tx.getRequestUri() + "/");
                }
                return new RedirectionRouter(tx.getRequestUri() + "/", 302).route(tx, path);
            }
        }

        // Check that class is instance of Page
        if (!Page.class.isAssignableFrom(pageClass)) {
            throw new RuntimeException("Class " + className + " is not a subclass of Page");
        }

        if (!checkSuffixMatch(pageClass, urlSuffix)) {
            return null;
        }

        if ((lastToken != null)&&(lastToken.equals(manager.getIndex()))) {
            String newPath = null;
            if (urlSuffix != null) {
                newPath = path.substring(0, path.length() - lastToken.length() - urlSuffix.length());
            } else {
                newPath = path.substring(0, path.length() - lastToken.length());
            }

            if (log.isDebugEnabled()) {
                log.debug("Redirecting to " + newPath);
            }

            return new RedirectionRouter(newPath, 302).route(tx, newPath);
        }

        try {
            if (log.isDebugEnabled()) {
                log.debug("Creating new instance of " + pageClass);
            }

            return pageClass.newInstance();
        } catch (Exception e) {
            log.error("Error creating page instance: " + e.getMessage(), e);
            return null;
        }
    }

    protected boolean checkSuffixMatch(Class pageClass, String urlSuffix) {
        String pageSuffix = manager.getSuffix();
        QlueMapping mapping = (QlueMapping) pageClass.getAnnotation(QlueMapping.class);
        if (mapping != null) {
            if (!mapping.suffix().equals("inheritAppSuffix")) {
                pageSuffix = mapping.suffix();
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("Suffix check: URL: " + urlSuffix + "; page: " + pageSuffix);
        }

        if (urlSuffix != null) {
            // URL suffix present.

            if (pageSuffix == null) {
                if (log.isDebugEnabled()) {
                    log.debug("Suffix mismatch: URI has suffix but page doesn't");
                }
                return false;
            }

            if (!pageSuffix.equals(urlSuffix)) {
                if (log.isDebugEnabled()) {
                    log.debug("Suffix mismatch: URI: " + urlSuffix + "; page: " + pageSuffix);
                }
                return false;
            }
        } else {
            // URL suffix not present.

            if (pageSuffix != null) {
                if (log.isDebugEnabled()) {
                    log.debug("Suffix mismatch: page has suffix but URI doesn't");
                }
                return false;
            }
        }

        return true;
    }
}
