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
package com.webkreator.qlue.example.pages;

import java.io.IOException;
import java.io.PrintWriter;

import com.webkreator.qlue.util.HtmlEncoder;
import com.webkreator.qlue.Errors;
import com.webkreator.qlue.Page;
import com.webkreator.qlue.QlueParameter;
import com.webkreator.qlue.view.DefaultView;
import com.webkreator.qlue.view.NullView;
import com.webkreator.qlue.view.View;

public class index extends Page {
	
	@QlueParameter(mandatory = false)
	public String p;
	
	@QlueParameter(mandatory = false)
	public Boolean b;
	
	@Override
	public View onValidationError() throws IOException {
		PrintWriter out = context.response.getWriter();
		context.response.setContentType("text/html");

		out.println("<h1>Validation Error</h1>");
		
		out.println("<ol>");
		
		Errors errors = getErrors();
		for (com.webkreator.qlue.Error e : errors.getAllErrors()) {
			out.println("<li>");
			
			out.print(HtmlEncoder.encodeForHTML(e.getMessage()));
			
			if (e.getField() != null) {
				out.println(" [field " + HtmlEncoder.encodeForHTML(e.getField()) + "]");
			}
			
			out.println("</li>");
		}

		return new NullView();
	}

	@Override
	public View onGet() {			
		return new DefaultView();
	}
}
