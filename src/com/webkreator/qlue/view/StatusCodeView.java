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

import com.webkreator.qlue.Page;
import com.webkreator.qlue.TransactionContext;
import com.webkreator.qlue.util.WebUtil;

/**
 * This view will return with a custom response status code and error message,
 * not unlike the default error response of the Apache web server.
 */
public class StatusCodeView implements View {

	private int statusCode;

	private String title;

	private String message;

	/**
	 * Create a view using the provided status code, using a stock message.
	 * 
	 * @param statusCode
	 */
	public StatusCodeView(int statusCode) {
		this.statusCode = statusCode;
	}

	/**
	 * Create a view using the provided status code and message.
	 * 
	 * @param status
	 * @param message
	 */
	public StatusCodeView(int statusCode, String message) {
		this.statusCode = statusCode;
		this.message = message;
	}

	/**
	 * Set custom title for this message. By default, the status message
	 * (determined from the status code) is used for the title.
	 * 
	 * @param title
	 */
	public void setTitle(String title) {
		this.title = title;
	}

	@Override
	public void render(TransactionContext context, Page page) throws Exception {
		context.response.setStatus(statusCode);

		String myTitle = title;
		
		if (myTitle == null) {
			myTitle = WebUtil.getStatusMessage(statusCode);
			if (myTitle == null) {
				myTitle = "Unknown Status Code";
			}
		}

		WebUtil.writeMessage(context, myTitle, message);
	}
}
