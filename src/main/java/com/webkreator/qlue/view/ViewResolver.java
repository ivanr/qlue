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

/**
 * The role of a view resolver is to map a URL to a view. This simplest
 * implementation will remove the suffix and return the resulting string as view
 * name.
 */
public class ViewResolver {

	/**
	 * Determine view name, given URI.
	 * 
	 * @param requestUri
	 * @return
	 */
	public String resolveView(String requestUri) {
		// We do not allow backreferences in view names
		if (requestUri.indexOf("..") != -1) {
			throw new RuntimeException(
					"ViewResolver: backreferences not allowed in URI: "
							+ requestUri);
		}

		// Remove suffix
		int i = requestUri.lastIndexOf('.');
		if (i != -1) {
			requestUri = requestUri.substring(0, i);
		}

		// What's left is the view name in this implementation
		return requestUri;
	}
}
