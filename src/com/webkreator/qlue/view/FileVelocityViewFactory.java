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
package com.webkreator.qlue.view;

import java.util.Properties;

import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;

import com.webkreator.qlue.Page;
import com.webkreator.qlue.QlueApplication;

/**
 * Implementation of VelocityViewFactory that keeps templates in a single
 * location.
 */
public class FileVelocityViewFactory extends VelocityViewFactory {

	protected String prefix = "/WEB-INF/vm/";

	/**
	 * Initialize factory.
	 */
	@Override
	public void init(QlueApplication qlueApp) throws Exception {
		// Prepare the Velocity properties.
		Properties properties = new Properties();
		properties.setProperty("resource.loader", "file,string");
		properties
				.setProperty("string.resource.loader.class",
						"org.apache.velocity.runtime.resource.loader.StringResourceLoader");
		properties.setProperty("string.resource.loader.repository.name",
				VELOCITY_STRING_RESOURCE_LOADER_KEY);
		properties.setProperty("file.resource.loader.path",
				qlueApp.getApplicationRoot() + "/" + prefix);
		properties.setProperty("input.encoding", inputEncoding);
		properties.setProperty(RuntimeConstants.RUNTIME_LOG_LOGSYSTEM_CLASS,
				logChute);
				
		if (qlueApp.getProperty("qlue.velocity.cache") != null) {
			properties.setProperty("file.resource.loader.cache", qlueApp.getProperty("qlue.velocity.cache"));
		}
		
		if (qlueApp.getProperty("qlue.velocity.modificationCheckInterval") != null) {
			properties.setProperty("file.resource.loader.modificationCheckInterval", qlueApp.getProperty("qlue.velocity.modificationCheckInterval"));
		}

		// Initialise the Velocity template engine.
		velocityEngine = new VelocityEngine(properties);
	}

	/**
	 * Find or construct the view, given page and view name.
	 * 
	 * @param page
	 * @param viewName
	 * @return
	 * @throws Exception
	 */
	@Override
	public View constructView(Page page, String viewName) throws Exception {
		String templateName = null;

		if (viewName.charAt(0) == '/') {
			// Absolute view names are used as is
			templateName = viewName + suffix;
		} else {
			// Relative view names are added to their page's path
			String defaultView = page.getQlueApp().getViewResolver()
					.resolveView(page.getNoParamUri());

			int i = defaultView.lastIndexOf("/");
			if (i != -1) {
				defaultView = defaultView.substring(0, i);
			}

			templateName = defaultView + "/" + viewName + suffix;
		}

		return new VelocityView(this, velocityEngine.getTemplate(templateName));
	}

	/**
	 * Get the location where we expect the templates to be stored. The location
	 * is in the form of a prefix relative to the root of the web application.
	 * For example, /WEB-INF/vm/ is used by default.
	 * 
	 * @return
	 */
	public String getPrefix() {
		return prefix;
	}

	/**
	 * Set the location where the templates are stored. The location is in the
	 * form of a path relative to the root of the web application. For example,
	 * /WEB-INF/vm/ is used by default.
	 * 
	 * @param prefix
	 */
	public void setPrefix(String prefix) {
		this.prefix = prefix;
	}
}
