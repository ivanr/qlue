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
package com.webkreator.qlue;

import javax.servlet.http.HttpServletRequest;

/**
 * Page resolver maps request URIs to pages. A subclass
 * should inherit this class and provide a translaction
 * mechanism.
 */
public abstract class PageResolver {
	
	/**
	 * This abstract method needs to be subclassed to implement
	 * the algorithm to find a class that can handle the URI.
	 * 
	 * @param uri
	 * @return
	 * @throws Exception
	 */
	protected abstract Class<Page> resolvePageClass(String uri) throws Exception;
	
	public abstract String resolvePackage(String uri) throws Exception;
	
	/**
	 * Find a Page instance that can handle the given URI.
	 * 
	 * @param req
	 * @param uri
	 * @return
	 * @throws Exception
	 */
	public Page resolvePage(HttpServletRequest req, String uri) throws Exception {
		Class<Page> pageClass = resolvePageClass(uri);
		if (pageClass == null) {
			return null;
		}

		try {
			return pageClass.newInstance();
		} catch (InstantiationException e) {
			e.printStackTrace();
			return null;
		} catch (IllegalAccessException e) {
			e.printStackTrace();
			return null;
		}
	}	
}
