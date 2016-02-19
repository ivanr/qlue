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

import com.webkreator.qlue.exceptions.QlueException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.webkreator.qlue.Page;
import com.webkreator.qlue.TransactionContext;

/**
 * Routes transaction to a single class.
 */
public class ClassRouter implements Router {

	private Log log = LogFactory.getLog(ClassRouter.class);

	private Class<Page> pageClass;

	public ClassRouter(String className) {
		Class candidate;

		// Look for the desired class.
		try {
			candidate = Class.forName(className, true, Thread.currentThread().getContextClassLoader());
		} catch (Exception e) {
			throw new RuntimeException("ClassRouter: Unknown class: " + className);
		}

		// Check that the class is a subclass of Page.
		if (!Page.class.isAssignableFrom(candidate)) {
			throw new RuntimeException("ClassRouter: Class " + className + " is not a subclass of Page");
		}

		pageClass = candidate;
	}

	@Override
	public Object route(TransactionContext context, String pathSuffix) {
		try {
			return pageClass.newInstance();
		} catch (Throwable t) {
            throw new QlueException("Error creating page instance: " + t.getMessage(), t);
		}
	}
}
