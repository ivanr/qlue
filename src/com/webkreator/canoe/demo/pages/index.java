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
package com.webkreator.canoe.demo.pages;

import java.io.PrintWriter;

import com.webkreator.qlue.util.HtmlEncoder;
import com.webkreator.canoe.demo.SimpleTemplate;
import com.webkreator.qlue.Page;
import com.webkreator.qlue.QlueParameter;
import com.webkreator.qlue.view.DefaultView;
import com.webkreator.qlue.view.View;

public class index extends Page {

	@QlueParameter(mandatory = true, state = "POST")
	public String _template;

	@Override
	public View onGet() throws Exception {
		return new DefaultView();
	}

	@Override
	public View onPost() throws Exception {
		context.response.setContentType("text/html");

		SimpleTemplate st = new SimpleTemplate();
		try {
			st.process(_template, context.response.getWriter(), context.request);
		} catch (Throwable t) {
			PrintWriter out = context.response.getWriter();
			out.println("'\"--></script></textarea><pre><hr>");
			out.println(HtmlEncoder.encodeForHTML(t.getMessage()));
			out.println("\nTemplate: ");
			out.println(HtmlEncoder.encodeForHTMLPreserveWhitespace(_template));
		}

		return null;
	}
}
