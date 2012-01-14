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

import java.io.PrintWriter;
import java.security.InvalidParameterException;

import com.webkreator.canoe.HtmlEncoder;
import com.webkreator.qlue.Page;
import com.webkreator.qlue.TransactionContext;
import com.webkreator.qlue.util.UriBuilder;

/**
 * This specialized view implementation is actually a redirection, supporting
 * both pages and URIs as targets. There are no shortcuts when redirecting to
 * pages; when given a page we construct a URI that leads back to it, then use
 * that URI to issue a redirection to the client.
 */
public class RedirectView implements View {

	public static final int REDIRECT = 302;

	public static final int REDIRECT_TEMPORARY = 307;

	public static final int REDIRECT_PERMANENT = 301;

	private UriBuilder redirection;

	private Page page;

	private int redirectStatus = REDIRECT;

	/**
	 * Redirect to an URI.
	 */
	public RedirectView(String uri) {
		if (uri == null) {
			throw new InvalidParameterException(
					"RedirectView: Cannot redirect to null URI");
		}

		redirection = new UriBuilder(uri);
	}

	/**
	 * Redirect to an existing page.
	 * 
	 * @param page
	 */
	public RedirectView(Page page) {
		if (page == null) {
			throw new InvalidParameterException(
					"RedirectView: Cannot redirect to null page");
		}

		redirection = new UriBuilder(page.getContext().getRequestUri());

		if (page.getId() != null) {
			redirection.clearParams();
			redirection.addParam("_pid", page.getId());
		}
	}

	/**
	 * Add one parameter to the redirection URI.
	 * 
	 * @param name
	 * @param value
	 */
	public void addParam(String name, String value) {
		redirection.addParam(name, value);
	}

	/**
	 * Returns the page to which redirection is to take place.
	 * 
	 * @return page instance, or null if redirection is to a URL
	 */
	public Page getPage() {
		return page;
	}

	/**
	 * Returns the URL to which redirection is to take place.
	 * 
	 * @return URL string
	 */
	public String getUri() {
		if (redirection == null) {
			return null;
		}

		return redirection.getUri();
	}

	/**
	 * Set the status code that will be used for the redirection.
	 * 
	 * @param redirectStatus
	 */
	public void setStatus(int redirectStatus) {
		if ((redirectStatus != REDIRECT_PERMANENT) && (redirectStatus != REDIRECT)
				&& (redirectStatus != REDIRECT_TEMPORARY)) {
			throw new InvalidParameterException("Invalid redirection status: "
					+ redirectStatus);
		}

		this.redirectStatus = redirectStatus;
	}

	/**
	 * Issue a redirection to a page or a URI.
	 */
	@Override
	public void render(TransactionContext context, Page page) throws Exception {		
		if ((page != null) && (page.isDevelopmentMode())) {
			context.response.setContentType("text/html");
			PrintWriter out = context.response.getWriter();
			out.print("<html><head><title>");
			out.print("Development Mode Redirection");
			out.println("</title></head>");
			out.print("<body><h1>");
			out.print("Development Mode Redirection");
			out.println("</h1>");
			out.println("<p>Destination: <code>");
			out.print(HtmlEncoder.encodeForHTML(redirection.getUri()));
			out.println("</code></p>");
			out.print("<form action=\"");
			out.print(HtmlEncoder.encodeForURL(redirection.getUri()));
			out.println("\"><br><input type=submit value=\"Proceed &gt;&gt;\"></form>");
			out.println("</body></html>");
		} else {
			context.response.setStatus(redirectStatus);
			context.response.setHeader("Location", redirection.getUri());
		}
	}
}
