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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.webkreator.qlue.Page;
import com.webkreator.qlue.TransactionContext;

public class ClassRouter implements Router {

	private Log log = LogFactory.getLog(ClassRouter.class);

	private Class<Page> page;

	@SuppressWarnings({ "unchecked", "rawtypes" })
	public ClassRouter(String className) {
		Class candidate = null;

		// Look for class
		try {
			ClassLoader classLoader = Thread.currentThread()
					.getContextClassLoader();
			candidate = Class.forName(className, true, classLoader);
		} catch (Exception e) {
			throw new RuntimeException("ClassRouter: Unknown class: "
					+ className);
		}

		// Check class is instance of Page
		if (!Page.class.isAssignableFrom(candidate)) {
			throw new RuntimeException("ClassRouter: Class " + className
					+ " is not a subclass of Page.");
		}

		page = candidate;
	}

	@Override
	public Object route(TransactionContext context, String extraPath) {
		try {
			return page.newInstance();
		} catch (Exception e) {
			log.error("Error creating page instance: " + e.getMessage(), e);
			return null;
		}
	}
}
