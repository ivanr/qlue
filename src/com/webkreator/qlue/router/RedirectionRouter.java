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

import java.security.InvalidParameterException;

import com.webkreator.qlue.TransactionContext;
import com.webkreator.qlue.view.RedirectView;

/**
 * Routes transaction to a redirection.
 */
public class RedirectionRouter implements Router {

	private String uri;

	private int status = 301;

	public RedirectionRouter(String uri) {
		this.uri = uri;
	}

	public RedirectionRouter(String uri, int status) {
		// Check that the status code is appropriate
		if ((status != 301) && (status != 302) && (status != 303) && (status != 307))
        {
			throw new InvalidParameterException("RedirectionRouter: Status code cannot be used for redirection: " + status);
		}

		this.uri = uri;
		this.status = status;
	}

	@Override
	public Object route(TransactionContext context, String extraPath) {
		RedirectView redirectView = new RedirectView(uri);
		redirectView.setStatus(status);
		return redirectView;
	}
}
