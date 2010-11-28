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

import com.webkreator.canoe.HtmlEncoder;

public class FormatTool {
	
	public String hex(int i) {
		return Integer.toHexString(i);
	}

	public String indent(int j) {		
		if (j >=1) j -= 1;
		String s = "";
		for (int i = 0; i < j; i++) {
			s = s + "&nbsp;&nbsp;&nbsp;&nbsp;";
		}
		return s;
	}

	public String limit(String s, int limit) {
		if (s.length() > limit) {
			return s.substring(0, limit);
		}

		return s;
	}

	public String limitE(String s, int limit) {
		if (s.length() > limit) {
			return s.substring(0, limit) + " ...";
		}

		return s;
	}

	public static String formatLongTimeAsWholeSeconds(long t) {
		return Integer.toString((int) t / 1000);
	}

	public static String formatLongTimeAsSeconds(long t) {
		StringBuffer sb = new StringBuffer();
		sb.append((int) t / 1000);
		sb.append(".");
		sb.append(t - ((int) t / 1000) * 1000);
		return sb.toString();
	}

	public static String html(Object o) {		
		if (o == null) return null;
		return HtmlEncoder.encodeForHTML(o.toString());
	}

	public static String url(String input) {
		return HtmlEncoder.encodeForURL(input);
	}

	/**
	 * 
	 * @param input
	 * @return
	 */
	public static String js(String input) {
		return HtmlEncoder.encodeForJavaScript(input);
	}
}
