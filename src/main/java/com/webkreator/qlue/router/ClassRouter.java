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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Routes transaction to a single class.
 */
public class ClassRouter implements Router {

    private Logger log = LoggerFactory.getLogger(ClassRouter.class);

    private Class<Page> pageClass;

    public ClassRouter(final RouteManager manager, final String className) {
        Class candidate = null;
        String prefixedClassName = null;

        // If there is a package root (prefix), use it first to
        // look for our class. Given that we look only for Page instances,
        // it's very unlikely that we'll hit something that we're not
        // supposed to, but this is the approach used in PackageRouter.
        String packageRoot = manager.getProperties().getProperty(RouteManager.PACKAGE_ROOT);
        if (packageRoot != null) {
            prefixedClassName = PackageRouter.addPrefixToName(packageRoot, className);

            try {
                candidate = Class.forName(prefixedClassName, true, Thread.currentThread().getContextClassLoader());
            } catch (ClassNotFoundException e) {
                // Intentionally ignored.
            } catch (Exception e) {
                log.error("Qlue: Unexpected exception trying to find page class", e);
            }
        }

        // Now try the class name as provided.
        if (candidate == null) {
            try {
                candidate = Class.forName(className, true, Thread.currentThread().getContextClassLoader());
            } catch (ClassNotFoundException e) {
                // Intentionally ignored.
            } catch (Exception e) {
                log.error("Qlue: Unexpected exception trying to find page class", e);
            }
        }

        // We couldn't find the class.
        if (candidate == null) {
            if (prefixedClassName == null) {
                throw new RuntimeException("ClassRouter: Unknown class: " + className);
            } else {
                throw new RuntimeException("ClassRouter: Unknown class. Tried " + className + " and " + prefixedClassName);
            }
        }

        // Check that the class is a subclass of Page.
        if (!Page.class.isAssignableFrom(candidate)) {
            throw new RuntimeException("ClassRouter: Class " + className + " is not a subclass of Page");
        }

        pageClass = candidate;
    }

    @Override
    public Object route(TransactionContext context, Route route, String pathSuffix) {
        try {
            return pageClass.newInstance();
        } catch (Exception e) {
            throw new QlueException("Error creating page instance: " + e.getMessage(), e);
        }
    }
}
