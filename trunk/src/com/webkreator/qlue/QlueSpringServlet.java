/* 
 * Qlue Web Application Framework
 * Copyright 2009,2010 Ivan Ristic <ivanr@webkreator.com>
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
package com.webkreator.qlue;

import javax.servlet.ServletException;
import javax.servlet.UnavailableException;

import org.springframework.web.context.support.XmlWebApplicationContext;

/**
 * This servlet will load a Spring application context from
 * applicationContext.xml, locate an instance of QlueApp (bean with "qlueApp"
 * ID, initialise it, then delegate processing of all requests to it.
 */
public class QlueSpringServlet extends QlueServlet {

	private static final long serialVersionUID = 1L;

	@Override
	public void subclassInit() throws ServletException {
		try {
			// Create application context
			XmlWebApplicationContext ctx = new XmlWebApplicationContext();
			ctx.setServletContext(getServletContext());
			ctx.refresh();

			// Find the entry point
			QlueApplication qlueApp = (QlueApplication) ctx.getBean("qlueApp");
			if (qlueApp == null) {
				throw new ServletException(
						"Unable to find Qlue application (bean \"qlueApp\").");
			}

			// Initialise application
			qlueApp.init(this);

			setApplication(qlueApp);
		} catch (Throwable t) {
			throw new UnavailableException(t.getMessage());
		}
	}
}
