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
package com.webkreator.qlue.pages;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

import javax.servlet.http.HttpServletResponse;

import org.apache.velocity.exception.ParseErrorException;

import com.webkreator.qlue.util.HtmlEncoder;
import com.webkreator.qlue.exceptions.AccessForbiddenException;
import com.webkreator.qlue.Page;
import com.webkreator.qlue.exceptions.PersistentPageNotFoundException;
import com.webkreator.qlue.exceptions.ValidationException;
import com.webkreator.qlue.util.WebUtil;
import com.webkreator.qlue.view.View;

/**
 * Handle an application exception.
 */
public class handleThrowable extends Page {

	@Override
	public View service() throws Exception {
		Integer statusCode = (Integer) context.request
				.getAttribute("javax.servlet.error.status_code");
		if (statusCode == null) {
			throw new Exception("handleThrowable: direct access not allowed");
		}

		Throwable t = (Throwable) context.request
				.getAttribute("javax.servlet.error.exception");
		if (t != null) {
			if (t instanceof ValidationException) {
				return _handleValidationException((ValidationException) t);
			} else if (t instanceof PersistentPageNotFoundException) {
				return _handlePersistentPageNotFoundException((PersistentPageNotFoundException) t);
			} else if (t instanceof ParseErrorException) {
				return _handleVelocityParseError((ParseErrorException) t);
			} else if (t instanceof AccessForbiddenException) {
				return _handleAccessForbiddenException((AccessForbiddenException) t);
			}
		}

		return _handleThrowable(t);
	}

	private View _handleAccessForbiddenException(AccessForbiddenException t)
			throws IOException {
		context.response.setContentType("text/html");
		context.response.setStatus(HttpServletResponse.SC_FORBIDDEN);
		PrintWriter out = context.response.getWriter();
		out.println("<html>");
		out.println("<head><title>Forbidden</title></head>");
		out.println("<body><h1>Forbidden</h1>");

		if (isDevelopmentMode()) {
			StringWriter sw = new StringWriter();
			t.printStackTrace(new PrintWriter(sw));
			out.println("<pre>");
			out.println(HtmlEncoder.encodeForHTMLPreserveWhitespace(sw
					.toString()));
			out.println("</pre>");
		}

		WebUtil.writePagePaddingforInternetExplorer(out);
		out.println("</body></html>");

		return null;
	}

	private View _handleVelocityParseError(ParseErrorException t)
			throws Exception {
		if (isDevelopmentMode() == false) {
			return _handleThrowable(t);
		}

		context.response.setContentType("text/html");
		PrintWriter out = context.response.getWriter();
		out.println("<html>");
		out.println("<head><title>Template Parse Error</title></head>");
		out.println("<body><h1>Template Parse Error</h1>");
		out.println("<pre>");
		out.println(HtmlEncoder.encodeForHTMLPreserveWhitespace(t.getMessage()));
		out.println("</pre>");
		WebUtil.writePagePaddingforInternetExplorer(out);
		out.println("</body></html>");

		return null;
	}

	private View _handlePersistentPageNotFoundException(
			PersistentPageNotFoundException t) throws Exception {
		context.response.setContentType("text/html");
		PrintWriter out = context.response.getWriter();
		out.println("<html>");
		out.println("<head><title>Activity Not Found</title></head>");
		out.println("<body><h1>Activity Not Found</h1>");
		WebUtil.writePagePaddingforInternetExplorer(out);
		out.println("</body></html>");

		return null;
	}

	public View _handleValidationException(ValidationException ve)
			throws Exception {
		context.response.setContentType("text/html");
		PrintWriter out = context.response.getWriter();
		out.println("<html>");
		out.println("<head><title>Parameter Validation Failed</title></head>");
		out.println("<body><h1>Parameter Validation Failed</h1>");
		WebUtil.writePagePaddingforInternetExplorer(out);
		out.println("</body></html>");

		return null;
	}

	public View _handleThrowable(Throwable t) throws Exception {
		context.response.setContentType("text/html");
		PrintWriter out = context.response.getWriter();
		out.println("<html>");
		out.println("<head><title>Internal Server Error</title></head>");
		out.println("<body><h1>Internal Server Error</h1>");

		if ((t != null)&&(isDevelopmentMode())) {
			StringWriter sw = new StringWriter();
			t.printStackTrace(new PrintWriter(sw));
			out.println("<pre>");
			out.println(HtmlEncoder.encodeForHTMLPreserveWhitespace(sw
					.toString()));
			out.println("</pre>");
		}

		WebUtil.writePagePaddingforInternetExplorer(out);
		out.println("</body></html>");

		return null;
	}
}
