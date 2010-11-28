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
package com.webkreator.qlue.example.pages;

import com.webkreator.qlue.Page;
import com.webkreator.qlue.QlueParameter;
import com.webkreator.qlue.view.View;

public class postPage extends Page {

	@QlueParameter(mandatory = true, state = "POST")
	public String name;

	@Override
	public View onGet() throws Exception {
		context.response.setContentType("text/html");
		context.response
				.getWriter()
				.write("<form action=/postPage.html method=POST><input type=text name=name value='123'><input type=submit></form>");
		return null;
	}

	@Override
	public View onPost() throws Exception {
		context.response.setContentType("text/html");
		context.response.getWriter().write(name);
		return null;
	}
}
