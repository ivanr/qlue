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
package com.webkreator.qlue.view.velocity;

import com.webkreator.qlue.view.View;
import org.apache.velocity.Template;

import com.webkreator.qlue.Page;
import com.webkreator.qlue.TransactionContext;

import javax.validation.Valid;

/**
 * Handle a view implemented as a Velocity template.
 */
public class VelocityView implements View {

	private VelocityViewFactory viewFactory;

	private Template template;

	/**
	 * Create new view using given view factory and template.
	 * 
	 * @param viewFactory
	 * @param template
	 */
	VelocityView(VelocityViewFactory viewFactory, Template template) {
		this.viewFactory = viewFactory;
		this.template = template;
	}

	/**
	 * Returns the template used by this view.
	 * 
	 * @return Template instance
	 */
	Template getTemplate() {
		return template;
	}

	/**
	 * Render output for the given page.
	 */
	@Override
	public void render(TransactionContext tx, Page page) throws Exception {
		tx.response.setContentType(View.CONTENT_TYPE_TEXT_HTML_UTF8);
		viewFactory.render(page, this);
	}
}
