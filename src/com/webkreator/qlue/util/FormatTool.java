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

/**
 * This tool assists with formating in HTML responses. 
 */
public class FormatTool {
	
	/**
	 * Encodes integer as hexadecimal number.
	 * 
	 * @param i
	 * @return
	 */
	public String hex(int i) {
		return Integer.toHexString(i);
	}

	/**
	 * Creates an indent, using four non-breaking spaces.
	 *  
	 * @param j
	 * @return
	 */
	public String indent(int j) {		
		if (j >=1) j -= 1;
		String s = "";
		for (int i = 0; i < j; i++) {
			s = s + "&nbsp;&nbsp;&nbsp;&nbsp;";
		}
		return s;
	}

	/**
	 * Writes text to output, with a limit.
	 * 
	 * @param s
	 * @param limit
	 * @return
	 */
	public String limit(String s, int limit) {
		if (s.length() > limit) {
			return s.substring(0, limit);
		}

		return s;
	}

	/**
	 * Writes text to output, with a limit and also
	 * uses an indication that the text was truncated.
	 * 
	 * @param s
	 * @param limit
	 * @return
	 */
	public String limitE(String s, int limit) {
		if (s.length() > limit) {
			return s.substring(0, limit) + " ...";
		}

		return s;
	}

	/**
	 * Formats millisecond time as whole seconds.
	 * 
	 * @param t
	 * @return
	 */
	public static String formatLongTimeAsWholeSeconds(long t) {
		return Integer.toString((int) t / 1000);
	}

	/**
	 * Formats millisecond time as seconds and milliseconds.
	 * 
	 * @param t
	 * @return
	 */
	public static String formatLongTimeAsSeconds(long t) {
		StringBuffer sb = new StringBuffer();
		sb.append((int) t / 1000);
		sb.append(".");
		sb.append(t - ((int) t / 1000) * 1000);
		return sb.toString();
	}
}
