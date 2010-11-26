/* 
 * Qlue Web Application Framework
 * Copyright 2009 Ivan Ristic <ivanr@webkreator.com>
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

import java.security.InvalidParameterException;

import com.webkreator.qlue.Page;
import com.webkreator.qlue.util.UriBuilder;

public class RedirectView implements View {

	private UriBuilder redirection;

	private Page page;

	/**
	 * Redirect to an URI.
	 */
	public RedirectView(String uri) {
		if (uri == null) {
			throw new InvalidParameterException("Cannot redirect to null URL");
		}
		
		redirection = new UriBuilder(uri);	
	}

	/**
	 * Redirect to an existing page.
	 * 
	 * @param page
	 */
	public RedirectView(Page page) {
		if (page == null) {
			throw new InvalidParameterException("Cannot redirect to null page");
		}
		
		redirection = new UriBuilder(page.getUri());

		if (page.getId() != null) {
			redirection.clearParams();
			redirection.addParam("_pid", page.getId());
		}
	}

	/**
	 * Add one parameter to the redirection URI.
	 * 
	 * @param name
	 * @param value
	 */
	public void addParam(String name, String value) {
		redirection.addParam(name, value);
	}

	/**
	 * Returns the page to which redirection is to take place.
	 * 
	 * @return page instance, or null if redirection is to a URL
	 */
	public Page getPage() {
		return page;
	}

	/**
	 * Returns the URL to which redirection is to take place.
	 * 
	 * @return URL string
	 */
	public String getUri() {
		if (redirection == null) {
			return null;
		}

		return redirection.getUri();
	}

	@Override
	public void render(Page page) throws Exception {
		page.getContext().response.sendRedirect(redirection.getUri());
	}
}
