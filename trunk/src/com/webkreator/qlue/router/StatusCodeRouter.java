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
package com.webkreator.qlue.router;

import com.webkreator.qlue.TransactionContext;
import com.webkreator.qlue.view.StatusCodeView;

/**
 * Routes transaction to a view that
 * responds with a custom error code.
 */
public class StatusCodeRouter implements Router {

	private int status;

	private String message;

	public StatusCodeRouter(int status) {
		this.status = status;
	}

	public StatusCodeRouter(int status, String message) {
		this.status = status;
		this.message = message;
	}

	@Override
	public Object route(TransactionContext context, String extraPath) {
		return new StatusCodeView(status, message);
	}
}
