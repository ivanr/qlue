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
package com.webkreator.qlue.util;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URISyntaxException;

import com.webkreator.qlue.TransactionContext;

/**
 * This class contains various utility methods useful in web applications.
 */
public class WebUtil {

	/**
	 * Normalize a URI, first by using the RFC 2396 algorithm, following by
	 * compressing multiple occurrences of the forward slash character to one.
	 * 
	 * @param uri
	 * @return
	 */
	public static String normaliseUri(String uri) {
		// This is a dirty way to avoid having
		// to write RFC 2396 normalization code.
		try {
			URI u = new URI("http://localhost/" + uri);
			uri = u.normalize().getPath();
		} catch (URISyntaxException e) {
			throw new RuntimeException("Invalid uri", e);
		}

		// Remove consecutive forward slash characters
		StringBuffer sb = new StringBuffer();

		boolean seenSlash = false;
		for (int i = 0; i < uri.length(); i++) {
			char c = uri.charAt(i);

			if (c == '/') {
				if (!seenSlash) {
					sb.append(c);
					seenSlash = true;
				}
			} else {
				sb.append(c);
				seenSlash = false;
			}
		}			

		return sb.toString();
	}

	/**
	 * Internet will not display the output of non-200 pages that are too small.
	 * Thus, we have to pad output in order to break over the limit. This method
	 * outputs enough padding.
	 * 
	 * @param out
	 */
	public static void writePagePaddingforInternetExplorer(PrintWriter out) {
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
	}

	public static void writeMessage(TransactionContext context, String message)
			throws IOException {
		context.response.setContentType("text/html");
		PrintWriter out = context.response.getWriter();
		out.print("<html><head><title>");
		out.print(message);
		out.println("</title></head>");
		out.print("<body><h1>");
		out.print(message);
		out.println("</h1>");
		WebUtil.writePagePaddingforInternetExplorer(out);
		out.println("</body></html>");
	}

	public static String getStatusMessage(int status) {
		switch (status) {
		case 100:
			return "Continue";
		case 101:
			return "Switching Protocols";
		case 200:
			return "OK";
		case 201:
			return "Created";
		case 202:
			return "Accepted";
		case 203:
			return "Non-Authoritative Information";
		case 204:
			return "No Content";
		case 205:
			return "Reset Content";
		case 206:
			return "Partial Content";
		case 300:
			return "Multiple Choices";
		case 301:
			return "Moved Permanently";
		case 302:
			return "Found";
		case 303:
			return "See Other";
		case 304:
			return "Not Modified";
		case 305:
			return "Use Proxy";
		case 306:
			return "(Unused)";
		case 307:
			return "Temporary Redirect";
		case 400:
			return "Bad Request";
		case 401:
			return "Unauthorized";
		case 402:
			return "Payment Required";
		case 403:
			return "Forbidden";
		case 404:
			return "Not Found";
		case 405:
			return "Method Not Allowed";
		case 406:
			return "Not Acceptable";
		case 407:
			return "Proxy Authentication Required";
		case 408:
			return "Request Timeout";
		case 409:
			return "Conflict";
		case 410:
			return "Gone";
		case 411:
			return "Length Required";
		case 412:
			return "Precondition Failed";
		case 413:
			return "Request Entity Too Large";
		case 414:
			return "Request-URI Too Long";
		case 415:
			return "Unsupported Media Type";
		case 416:
			return "Requested Range Not Satisfiable";
		case 417:
			return "Expectation Failed";
		case 500:
			return "Internal Server Error";
		case 501:
			return "Not Implemented";
		case 502:
			return "Bad Gateway";
		case 503:
			return "Service Unavailable";
		case 504:
			return "Gateway Timeout";
		case 505:
			return "HTTP Version Not Supported";
		}

		return null;
	}
}
