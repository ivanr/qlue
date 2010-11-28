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
package com.webkreator.qlue.view;

public class ViewResolver {

	private String suffix = ".html";

	public String resolveView(String requestUri) {
		// TODO Do not allow .. in requestUri

		// Remove the suffix from the end
		if (requestUri.endsWith(suffix)) {
			requestUri = requestUri.substring(0,
					requestUri.length() - suffix.length());
		}

		return requestUri;
	}

	// -- Getters and setters --

	public String getSuffix() {
		return suffix;
	}

	public void setSuffix(String suffix) {
		this.suffix = suffix;
	}
}
