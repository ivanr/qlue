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

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Properties;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.velocity.app.VelocityEngine;

import com.webkreator.qlue.Page;
import com.webkreator.qlue.QlueApplication;

/**
 * Implementation of VelocityViewFactory that keeps templates in a single
 * location.
 */
public class FileVelocityViewFactory extends VelocityViewFactory {	

	protected String path = "./WEB-INF/vm/";

	/**
	 * Initialize factory.
	 */
	@Override
	public void init(QlueApplication qlueApp) throws Exception {
		Properties properties = buildDefaultVelocityProperties(qlueApp);
		if (qlueApp.getProperty("qlue.velocity.path") != null) {
			path = qlueApp.getProperty("qlue.velocity.path");

		}
		
		Path p = FileSystems.getDefault().getPath(path);
		if (p.isAbsolute()) {
			properties.setProperty("file.resource.loader.path", path);
		} else {
			properties.setProperty("file.resource.loader.path",
					qlueApp.getApplicationRoot() + "/" + path);
		}
		
		log.info("Creating VelocityEngine with properties: " + properties);

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
					.resolveView(page.getNoParamUri().replace('-', '_'));

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
	public String getPath() {
		return path;
	}

	/**
	 * Set the location where the templates are stored. The location is in the
	 * form of a path relative to the root of the web application. For example,
	 * /WEB-INF/vm/ is used by default.
	 * 
	 * @param prefix
	 */
	public void setPath(String prefix) {
		this.path = prefix;
	}
}
