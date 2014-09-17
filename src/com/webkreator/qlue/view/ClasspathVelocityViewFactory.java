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

import java.io.File;
import java.util.Properties;
import java.util.StringTokenizer;

import org.apache.velocity.app.VelocityEngine;

import com.webkreator.qlue.Page;
import com.webkreator.qlue.QlueApplication;

/**
 * This variant of VelocityViewFactory expects templates to be stored on the
 * classpath.
 */
public class ClasspathVelocityViewFactory extends VelocityViewFactory {

	/**
	 * Initialize factory.
	 */
	@Override
	public void init(QlueApplication qlueApp) throws Exception {
		Properties properties = buildDefaultVelocityProperties(qlueApp);		
		properties
				.setProperty("file.resource.loader.class",
						"org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader");
		velocityEngine = new VelocityEngine(properties);
	}

	/**
	 * Create a view instance, given page and view name. We use page class name
	 * to create a folder hierarchy, finishing with the view name and the
	 * suffix.
	 */
	@Override
	public View constructView(Page page, String viewName) throws Exception {
		StringBuffer sb = new StringBuffer();

		StringTokenizer st = new StringTokenizer(page.getClass().getName(), ".");
		String lastToken = null;

		while (st.hasMoreTokens()) {
			if (lastToken != null) {
				sb.append('/');
				sb.append(lastToken);
			}

			lastToken = st.nextToken();
		}

		sb.append('/');
		sb.append(new File(viewName).getName());
		sb.append(suffix);

		return new VelocityView(this, velocityEngine.getTemplate(sb.toString()));
	}
}
