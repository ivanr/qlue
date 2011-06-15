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
package com.webkreator.qlue.view;

import java.io.File;
import java.util.Properties;
import java.util.StringTokenizer;

import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;

import com.webkreator.qlue.Page;
import com.webkreator.qlue.QlueApplication;

/**
 * This variant of VelocityViewFactory expects templates to be stored on the
 * classpath.
 */
public class ClasspathVelocityViewFactory extends VelocityViewFactory {

	protected String macroPath = "";

	/**
	 * Initialize factory.
	 */
	@Override
	public void init(QlueApplication qlueApp) throws Exception {
		// Initialize properties
		Properties properties = new Properties();
		properties.setProperty("input.encoding", inputEncoding);
		properties.setProperty("resource.loader", "class,string");
		properties
				.setProperty("string.resource.loader.class",
						"org.apache.velocity.runtime.resource.loader.StringResourceLoader");
		properties.setProperty("string.resource.loader.repository.name",
				VELOCITY_STRING_RESOURCE_LOADER_KEY);
		properties
				.setProperty("class.resource.loader.class",
						"org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader");
		properties.setProperty("velocimacro.library", macroPath);
		properties.setProperty(RuntimeConstants.RUNTIME_LOG_LOGSYSTEM_CLASS,
				logChute);

		// Initialize engine
		velocityEngine = new VelocityEngine(properties);
	}

	/**
	 * Create a view instance, given page and view name.
	 */
	@Override
	public View constructView(Page page, String viewName) throws Exception {
		String lastToken = null;
		StringTokenizer st = new StringTokenizer(page.getClass().getName(), ".");
		StringBuffer sb = new StringBuffer();
		while (st.hasMoreTokens()) {
			if (lastToken != null) {
				sb.append("/");
				sb.append(lastToken);
			}

			lastToken = st.nextToken();
		}

		String name = sb.toString() + "/" + new File(viewName).getName()
				+ suffix;

		return new VelocityView(this, velocityEngine.getTemplate(name));
	}

	/**
	 * Configure folder path where Velocity macros are stored.
	 * 
	 * @param macroPath
	 */
	public void setMacroPath(String macroPath) {
		this.macroPath = macroPath;
	}
}
