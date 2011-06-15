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
		case 404:
			return "Not Found";
		// XXX Add all known status code messages here
		}

		return null;
	}
}
