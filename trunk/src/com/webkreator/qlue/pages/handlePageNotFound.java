/* 
 * Qlue Web Application Framework
 * Copyright 2009 Ivan Ristic <ivanr@webkreator.com>
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
package com.webkreator.qlue.pages;

import java.io.PrintWriter;

import com.webkreator.qlue.Page;
import com.webkreator.qlue.view.View;

/**
 * Handle a 404. 
 */
public class handlePageNotFound extends Page {

	@Override
	public View service() throws Exception {				
		// We need to handle the case when our folder (package) is
		// accessed without the trailing slash. In such cases the
		// welcome filter is not going to be able to redirect to
		// a welcome page and, as a consequence, we won't be given
		// an opportunity to handle the request. It will eventually
		// come to us here, though.
		String originalUri = (String) context.request
				.getAttribute("javax.servlet.forward.request_uri");
		if (originalUri != null) {
			if ((originalUri.endsWith("/") == false)
					&& (getQlueApp().isFolderUri(originalUri))) {
				context.response.sendRedirect(originalUri + "/");
				return null;
			}
		} else {
			// TODO Refuse direct access, log
		}			

		context.response.setContentType("text/html");
		PrintWriter out = context.response.getWriter();
		out.println("<html><head><title>Not Found</title></head>");
		out.println("<body><h1>Not Found</h1>");
		out.println("<!-- IE padding");
		out.println("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX");
		out.println("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX");
		out.println("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX");
		out.println("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX");
		out.println("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX");
		out.println("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX");
		out.println("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX");
		out.println("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX");
		out.println("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX");
		out.println("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX");
		out.println("-->");
		out.println("</body></html>");			
		
		return null;
	}
}
