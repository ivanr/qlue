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
package com.webkreator.qlue.util;

import java.io.PrintWriter;

/**
 * This class contains various utility methods useful in web applications.
 */
public class WebUtil {

	/**
	 * Normalise a URI, by collapsing multiple occurrences of the forward slash
	 * character to one. XXX Fix by implementing proper RFC normalisation.
	 * 
	 * @param uri
	 * @return
	 */
	public static String normaliseUri(String uri) {
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
	 * Internet will not display the output of non-200 pages that are
	 * too small. Thus, we have to pad output in order to break over the
	 * limit. This method outputs enough padding.
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
}
