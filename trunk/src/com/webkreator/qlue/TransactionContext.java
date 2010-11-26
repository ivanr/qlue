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
package com.webkreator.qlue;

import java.io.PrintWriter;
import java.util.Enumeration;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import com.webkreator.canoe.HtmlEncoder;
import com.webkreator.qlue.view.FinalRedirectView;

public class TransactionContext {

	public static final String QLUE_SESSION_STORAGE_ID = "QLUE_PAGE_MANAGER";

	public int txId;

	public ServletConfig servletConfig;

	public ServletContext servletContext;

	public HttpServletRequest request;

	public HttpServletResponse response;

	public HttpSession session;

	public QluePageManager qluePageManager;

	public String requestUri;

	public TransactionContext(ServletConfig servletConfig, ServletContext servletContext,
			HttpServletRequest request, HttpServletResponse response) {
		this.servletConfig = servletConfig;
		this.servletContext = servletContext;
		this.request = request;
		this.response = response;
		this.session = request.getSession();

		// Get the QlueSession instance.
		synchronized (session) {
			qluePageManager = (QluePageManager) session
					.getAttribute(QLUE_SESSION_STORAGE_ID);
			// Or create a new one.
			if (qluePageManager == null) {
				qluePageManager = new QluePageManager();
				session.setAttribute(QLUE_SESSION_STORAGE_ID, qluePageManager);
			}
		}
	}

	public boolean isGet() {
		if ((request.getMethod().compareTo("GET") == 0)
				|| (request.getMethod().compareTo("HEAD") == 0)) {
			return true;
		} else {
			return false;
		}
	}

	public boolean isPost() {
		if (request.getMethod().compareTo("POST") == 0) {
			return true;
		} else {
			return false;
		}
	}

	public Page findPersistentPage(String pid) {
		return qluePageManager.findPage(Integer.parseInt(pid));
	}

	public void persistPage(Page page) {
		qluePageManager.storePage(page);
	}

	// -- Getters and setters --

	public HttpServletRequest getRequest() {
		return request;
	}

	public HttpServletResponse getResponse() {
		return response;
	}

	public ServletConfig getServletConfig() {
		return servletConfig;
	}

	public void setServletConfig(ServletConfig servletConfig) {
		this.servletConfig = servletConfig;
	}

	public ServletContext getServletContext() {
		return servletContext;
	}

	public void setServletContext(ServletContext servletContext) {
		this.servletContext = servletContext;
	}

	public void setRequest(HttpServletRequest request) {
		this.request = request;
	}

	public void setResponse(HttpServletResponse response) {
		this.response = response;
	}

	public HttpSession getSession() {
		return session;
	}

	public void setSession(HttpSession session) {
		this.session = session;
	}

	public String getRequestUri() {
		return requestUri;
	}

	public void setRequestUri(String requestUri) {
		this.requestUri = requestUri;
	}

	public void replacePage(Page page, FinalRedirectView view) {
		qluePageManager.replacePage(page, view);
	}

	public boolean isErrorHandler() {
		Integer errorStatusCode = (Integer) request
				.getAttribute("javax.servlet.error.status_code");

		if (errorStatusCode != null) {
			return true;
		} else {
			return false;
		}
	}

	public int getTxId() {
		return txId;
	}

	public void setTxId(int txId) {
		this.txId = txId;
	}

	public PersistentPageRecord findPersistentPageRecord(String pid) {
		return qluePageManager.findPageRecord(Integer.parseInt(pid));
	}

	public void writeRequestDevelopmentInformation(PrintWriter out) {
		out.println(" Method: "
				+ HtmlEncoder.encodeForHTML(request.getMethod()));
		out.println(" URI: "
				+ HtmlEncoder.encodeForHTML(request.getRequestURI()));
		out.println(" Query String: "
				+ HtmlEncoder.encodeForHTML(request.getQueryString()));
		out.println(" Remote Addr: "
				+ HtmlEncoder.encodeForHTML(request.getRemoteAddr()));
		out.println(" Remote Port: " + request.getRemotePort());
		out.println(" Protocol: "
				+ HtmlEncoder.encodeForHTML(request.getProtocol()));
		out.println("");
		out.println("<b>Request Headers</b>\n");
		for (Enumeration<String> e = request.getHeaderNames(); e
				.hasMoreElements();) {
			String name = e.nextElement();
			for (Enumeration<String> e2 = request.getHeaders(name); e2
					.hasMoreElements();) {
				out.println(" " + HtmlEncoder.encodeForHTML(name) + ": "
						+ HtmlEncoder.encodeForHTML(e2.nextElement()));
			}
		}
		out.println("");
		out.println("<b>Request Parameters</b>\n");
		for (Enumeration<String> e = request.getParameterNames(); e
				.hasMoreElements();) {
			String name = e.nextElement();
			String[] values = request.getParameterValues(name);
			for (String value : values) {
				out.println(" " + HtmlEncoder.encodeForHTML(name) + ": "
						+ HtmlEncoder.encodeForHTML(value));
			}
		}
	}
}
