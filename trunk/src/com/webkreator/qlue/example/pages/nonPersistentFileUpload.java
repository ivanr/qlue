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
package com.webkreator.qlue.example.pages;

import java.io.PrintWriter;

import com.webkreator.qlue.Page;
import com.webkreator.qlue.QlueFile;
import com.webkreator.qlue.QlueParameter;
import com.webkreator.qlue.view.View;

public class nonPersistentFileUpload extends Page {
	
	@QlueParameter(mandatory = true, state = Page.STATE_POST)
	public QlueFile file;
	
	@Override
	public View onGet() throws Exception {
		PrintWriter out = context.response.getWriter();
		context.response.setContentType("text/html");

		out.println("<form enctype=multipart/form-data method=POST>");
		out.println("<input type=text value=xxx>");
		out.println("<input type=file name=file>");
		out.println("<input type=submit>");
		out.println("</form>");

		return null;
	}
	
	@Override
	public View onPost() throws Exception {
		PrintWriter out = context.response.getWriter();

		out.println("Hello World!");
		out.println(file.length());

		return null;
	}
}
