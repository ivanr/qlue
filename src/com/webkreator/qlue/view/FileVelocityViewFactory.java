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
package com.webkreator.qlue.view;

import java.util.Properties;

import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;

import com.webkreator.qlue.Page;
import com.webkreator.qlue.QlueApplication;

public class FileVelocityViewFactory extends VelocityViewFactory {

	protected String prefix = "/WEB-INF/vm/";

	@Override
	public void init(QlueApplication qlueApp) throws Exception {
		// Initialise properties
		Properties properties = new Properties();
		properties.setProperty("file.resource.loader.path",
				qlueApp.getApplicationRoot() + "/" + prefix);
		properties.setProperty("input.encoding", inputEncoding);
		properties.setProperty(RuntimeConstants.RUNTIME_LOG_LOGSYSTEM_CLASS,
				logChute);

		// Initialise engine
		velocityEngine = new VelocityEngine(properties);
	}

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

	public String getPrefix() {
		return prefix;
	}

	public void setPrefix(String prefix) {
		this.prefix = prefix;
	}
}
