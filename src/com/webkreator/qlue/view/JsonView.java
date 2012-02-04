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

import java.io.Writer;

import com.google.gson.Gson;
import com.webkreator.qlue.Page;
import com.webkreator.qlue.TransactionContext;

/**
 * View class specialized for JSON objects. Constructor takes a Java object and
 * render() function will send that object to browser in JSON notation, and set
 * response content type to application/json.
 */
public class JsonView implements com.webkreator.qlue.view.View {

	private Object jsonObject;

	private Gson gson;

	/**
	 * Create a view from the provided object.
	 * 
	 * @param jsonObject
	 *            Object we want to send to browser
	 */
	public JsonView(Object jsonObject) {
		this.jsonObject = jsonObject;
		this.gson = new Gson();
	}

	/**
	 * Create a view from the provided object, using the provided Gson instance
	 * for the conversion.
	 * 
	 * @param jsonObject
	 *            Object we want to send to browser
	 */
	public JsonView(Object jsonObject, Gson gson) {
		this.jsonObject = jsonObject;
		this.gson = gson;
	}

	/**
	 * Render page by asking Gson to convert our object into its JSON
	 * representation.
	 */
	@Override
	public void render(TransactionContext tx, Page page) throws Exception {
		page.getContext().response.setContentType("application/json");

		Writer writer = page.getContext().response.getWriter();

		try {
			writer.append(gson.toJson(jsonObject));
		} finally {
			writer.flush();
		}
	}
}
