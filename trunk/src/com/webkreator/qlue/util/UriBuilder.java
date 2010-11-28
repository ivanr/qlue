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

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

public class UriBuilder {

	private String uri;

	private List<UriBuilderParam> params = new ArrayList<UriBuilderParam>();

	class UriBuilderParam {
		String name;
		String value;

		UriBuilderParam(String name, String value) {
			this.name = name;
			this.value = value;
		}
	}

	public UriBuilder(String uri) {
		setUri(uri);
	}

	public void addParam(String name, int value) {
		params.add(new UriBuilderParam(name, Integer.toString(value)));
	}

	public void addParam(String name, Integer value) {
		params.add(new UriBuilderParam(name, value.toString()));
	}

	public void addParam(String name, String value) {
		params.add(new UriBuilderParam(name, value));
	}

	public void clearParams() {
		params.clear();
	}

	public void setUri(String uri) {
		int i = uri.indexOf('?');

		if (i == -1) {
			this.uri = WebUtil.normaliseUri(uri);
		} else {
			this.uri = WebUtil.normaliseUri(uri.substring(0, i));
			String qs = uri.substring(i + 1);
			String[] pairs = qs.split("&");
			try {
				for (String p : pairs) {
					i = p.indexOf('=');
					if (i == -1) {
						addParam(URLDecoder.decode(p, "UTF-8"), "");
					} else {
						addParam(URLDecoder.decode(p.substring(0, i), "UTF-8"),
								URLDecoder.decode(p.substring(i + 1), "UTF-8"));
					}
				}
			} catch (UnsupportedEncodingException une) {
				// Should never happen
			}
		}
	}

	public String getUri() {
		if (params.size() == 0) {
			// Shortcut, when there are no parameters
			return uri;
		} else {
			StringBuilder sb = new StringBuilder();

			try {
				// Start with the base URI.
				sb.append(uri);
				sb.append("?");

				// Iterate through the list of parameters and
				// add them to the URI, properly transforming them
				// in the process.
				for (int i = 0, n = params.size(); i < n; i++) {
					if (i != 0) {
						sb.append('&');
					}

					UriBuilderParam param = params.get(i);
					sb.append(URLEncoder.encode(param.name, "UTF-8"));
					sb.append("=");
					sb.append(URLEncoder.encode(param.value, "UTF-8"));
				}
			} catch (UnsupportedEncodingException uee) {
				// Should never happen, as we know that UTF-8 is supported
				uee.printStackTrace(System.err);
			}

			return sb.toString();
		}
	}
}
