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
package com.webkreator.qlue;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.tomcat.util.http.fileupload.FileItem;
import org.apache.tomcat.util.http.fileupload.FileItemFactory;
import org.apache.tomcat.util.http.fileupload.disk.DiskFileItemFactory;
import org.apache.tomcat.util.http.fileupload.servlet.ServletFileUpload;

import com.webkreator.canoe.HtmlEncoder;
import com.webkreator.qlue.util.TextUtil;
import com.webkreator.qlue.util.WebUtil;
import com.webkreator.qlue.view.FinalRedirectView;

/**
 * This class is used mostly to keep all the other stuff (relevant to a single
 * transaction) in one place.
 */
public class TransactionContext {

	public int txId;

	public ServletConfig servletConfig;

	public ServletContext servletContext;

	public HttpServletRequest request;

	public HttpServletResponse response;

	public HttpSession session;

	public QluePageManager qluePageManager;

	public QlueApplication app;

	public String requestUri;

	public String requestUriWithQueryString;

	private boolean isMultipart;

	private List<FileItem> multipartItems;

	private Map<String, String> urlParams = new HashMap<String, String>();

	private String effectiveRemoteAddr;

	/**
	 * Initialise context instance.
	 * 
	 * @param app
	 * @param servletConfig
	 * @param servletContext
	 * @param request
	 * @param response
	 */
	public TransactionContext(QlueApplication app, ServletConfig servletConfig,
			ServletContext servletContext, HttpServletRequest request,
			HttpServletResponse response) throws ServletException {
		this.app = app;
		this.servletConfig = servletConfig;
		this.servletContext = servletContext;
		this.request = request;
		this.response = response;
		this.session = request.getSession();

		// Get the QlueSession instance
		synchronized (session) {
			qluePageManager = (QluePageManager) session
					.getAttribute(QlueConstants.QLUE_SESSION_PAGE_MANAGER);
			// Not page manager? Then create a new one...
			if (qluePageManager == null) {
				qluePageManager = new QluePageManager();
				session.setAttribute(QlueConstants.QLUE_SESSION_PAGE_MANAGER,
						qluePageManager);
			}
		}

		initRequestUri();

		txId = app.allocatePageId();

		// Determine the effective remote address if the
		// request has been received from a trusted proxy
		if (app.isTrustedProxyRequest(this)) {
			String combinedAddresses = request.getHeader("X-Forwarded-For");
			if (TextUtil.isEmpty(combinedAddresses) == false) {
				String[] sx = combinedAddresses.split("[;,\\x20]");
				// Use the last IP address provided as effective IP address
				for (String s : sx) {
					// TODO Validate IP address
					effectiveRemoteAddr = s;
				}
			}
		}
	}

	public String getEffectiveRemoteAddr() {
		if (effectiveRemoteAddr != null) {
			return effectiveRemoteAddr;
		}

		return request.getRemoteAddr();
	}

	/**
	 * Detect and process multipart/form-data request.
	 * 
	 * @throws Exception
	 */
	void processMultipart() throws Exception {
		isMultipart = ServletFileUpload.isMultipartContent(request);

		if (isMultipart) {
			FileItemFactory factory = new DiskFileItemFactory();

			// Create a new file upload handler
			ServletFileUpload upload = new ServletFileUpload(factory);

			// Parse the request
			multipartItems = upload.parseRequest(request);
		}
	}

	/**
	 * Does the request associated with this transaction use GET or HEAD (the
	 * latter has the same semantics as GET)?
	 * 
	 * @return
	 */
	public boolean isGet() {
		if ((request.getMethod().compareTo("GET") == 0)
				|| (request.getMethod().compareTo("HEAD") == 0)) {
			return true;
		} else {
			return false;
		}
	}

	/**
	 * Does the request associated with this transaction use POST?
	 * 
	 * @return
	 */
	public boolean isPost() {
		if (request.getMethod().compareTo("POST") == 0) {
			return true;
		} else {
			return false;
		}
	}

	/**
	 * Find persistent page with the given ID.
	 * 
	 * @param pid
	 * @return
	 */
	public Page findPersistentPage(String pid) {
		return qluePageManager.findPage(Integer.parseInt(pid));
	}

	/**
	 * Keep the given page in persistent storage.
	 * 
	 * @param page
	 */
	public void persistPage(Page page) {
		qluePageManager.storePage(page);
	}

	/**
	 * Retrieve request associated with this transaction.
	 * 
	 * @return
	 */
	public HttpServletRequest getRequest() {
		return request;
	}

	/**
	 * Retrieve response associated with this transaction.
	 * 
	 * @return
	 */
	public HttpServletResponse getResponse() {
		return response;
	}

	/**
	 * Retrieve servlet config associated with this transaction.
	 * 
	 * @return
	 */
	public ServletConfig getServletConfig() {
		return servletConfig;
	}

	/**
	 * Retrieve servlet context associated with this transaction.
	 * 
	 * @return
	 */
	public ServletContext getServletContext() {
		return servletContext;
	}

	/**
	 * Retrieve the servlet session associated with this transaction.
	 * 
	 * @return
	 */
	public HttpSession getSession() {
		return session;
	}

	/**
	 * Retrieve request URI associated with this transaction.
	 * 
	 * @return
	 */
	public String getRequestUriWithQueryString() {
		return requestUriWithQueryString;
	}

	public String getRequestUri() {
		return requestUri;
	}

	/**
	 * Initialise request URI.
	 * 
	 * @throws ServletException
	 */
	private void initRequestUri() throws ServletException {
		// Retrieve URI and normalise it
		requestUri = WebUtil.normaliseUri(request.getRequestURI());

		// We want our URI to include the query string
		if (request.getQueryString() != null) {
			requestUriWithQueryString = requestUri + "?"
					+ request.getQueryString();
		} else {
			requestUriWithQueryString = requestUri;
		}

		// We are not expecting back-references in the URI, so
		// respond with an error if we do see one
		if (requestUriWithQueryString.indexOf("/../") != -1) {
			throw new ServletException(
					"Security violation: directory backreference "
							+ "detected in request URI: "
							+ requestUriWithQueryString);
		}
	}

	/**
	 * Replaces persistent page with a view.
	 * 
	 * @param page
	 * @param view
	 */
	public void replacePage(Page page, FinalRedirectView view) {
		qluePageManager.replacePage(page, view);
	}

	/**
	 * Check if the current contexts is an error handler.
	 * 
	 * @return
	 */
	public boolean isErrorHandler() {
		Integer errorStatusCode = (Integer) request
				.getAttribute("javax.servlet.error.status_code");

		if (errorStatusCode != null) {
			return true;
		} else {
			return false;
		}
	}

	/**
	 * Retrieves transaction ID.
	 * 
	 * @return
	 */
	public int getTxId() {
		return txId;
	}

	/**
	 * Retrieves the record of the persistent page with the given ID.
	 * 
	 * @param pid
	 * @return
	 */
	public PersistentPageRecord findPersistentPageRecord(String pid) {
		return qluePageManager.findPageRecord(Integer.parseInt(pid));
	}

	/**
	 * Outputs transaction-related debugging information.
	 * 
	 * @param out
	 */
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

	/**
	 * Retrieves parameter with the given name.
	 * 
	 * @param name
	 * @return
	 * @throws Exception
	 */
	public String getParameter(String name) throws Exception {
		// If we're not dealing with a multipart/form-data
		// request, simply refer to the underlying request object
		if (isMultipart == false) {
			return getRequest().getParameter(name);
		}

		// Otherwise, find the parameter in our own storage
		for (int i = 0, n = multipartItems.size(); i < n; i++) {
			FileItem fi = multipartItems.get(i);
			if (fi.getFieldName().compareToIgnoreCase(name) == 0) {
				if (fi.isFormField() == false) {
					throw new RuntimeException(
							"Qlue: Unexpected file parameter");
				}

				// Return parameter value using application's
				// character encoding.
				return fi.getString(app.getCharacterEncoding());
			}
		}

		return null;
	}

	/**
	 * Retrieves all values of parameters with the given name.
	 * 
	 * @param name
	 * @return
	 * @throws Exception
	 */
	public String[] getParameterValues(String name) throws Exception {
		// If we're not dealing with a multipart/form-data
		// request, simply refer to the underlying request object
		if (isMultipart == false) {
			return getRequest().getParameterValues(name);
		}

		// Otherwise, find the parameter in our own storage
		ArrayList<String> valuesList = new ArrayList<String>();

		for (int i = 0, n = multipartItems.size(); i < n; i++) {
			FileItem fi = multipartItems.get(i);
			if (fi.getFieldName().compareToIgnoreCase(name) == 0) {
				if (fi.isFormField() == false) {
					throw new RuntimeException(
							"Qlue: Unexpected file parameter");
				}

				// Add to the list
				valuesList.add(fi.getString(app.getCharacterEncoding()));
			}
		}

		// Return all values in an array
		String[] values = new String[valuesList.size()];
		return (String[]) valuesList.toArray(values);
	}

	/**
	 * Retrieves file with the given name.
	 * 
	 * @param name
	 * @return
	 * @throws Exception
	 */
	public FileItem getFile(String name) throws Exception {
		// It is an error to request a file from a
		// a transaction that is not multipart/form-data
		if (isMultipart == false) {
			throw new RuntimeException("Qlue: multipart/form-data expected");
		}

		// Find the requested file among out parameters
		for (int i = 0, n = multipartItems.size(); i < n; i++) {
			FileItem fi = multipartItems.get(i);
			if (fi.getFieldName().compareToIgnoreCase(name) == 0) {
				if (fi.isFormField() == true) {
					throw new RuntimeException(
							"Qlue: Unexpected simple parameter");
				}

				return fi;
			}
		}

		return null;
	}

	public String getUrlParameter(String name) {
		return urlParams.get(name);
	}

	public void setUrlParameter(String name, String value) {
		urlParams.put(name, value);
	}

	public void addUrlParameter(String name, String value) {
		setUrlParameter(name, value);
	}
}
