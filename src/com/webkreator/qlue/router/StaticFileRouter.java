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

import java.io.File;

import com.webkreator.qlue.exceptions.QlueSecurityException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.webkreator.qlue.TransactionContext;
import com.webkreator.qlue.view.DownloadView;
import com.webkreator.qlue.view.StatusCodeView;

/**
 * Routes transaction to a static file.
 */
public class StaticFileRouter implements Router {

	private Log log = LogFactory.getLog(StaticFileRouter.class);

	protected RouteManager manager;

	private String path;

	public StaticFileRouter(RouteManager manager, String path) {
		this.manager = manager;
		this.path = path;
	}

	@Override
	public Object route(TransactionContext context, String routeSuffix) {
		if (routeSuffix.contains("/../")) {
			throw new QlueSecurityException("StaticFileRouter: Invalid path: "
					+ routeSuffix);
		}

		if (routeSuffix.toLowerCase().contains("web-inf")) {
			throw new QlueSecurityException("StaticFileRouter: Invalid path: "
					+ routeSuffix);
		}

		File file = new File(path, routeSuffix);

		if (log.isDebugEnabled()) {
			log.debug("StaticFileRouter: Trying file: " + file);
		}

		if (file.exists()) {
			if (file.isDirectory()) {
				file = new File(file, manager.getIndex() + "."
						+ manager.getSuffix());
				if (file.exists()) {
					// By default, allow static resources to be cached for up to 1 hour
					context.response.setHeader("Cache-Control",
							"max-age: 3600, must-revalidate");
					// Download file
					return new DownloadView(file);
				} else {
					return new StatusCodeView(403);
				}
			} else {
				return new DownloadView(file);
			}
		} else {
			return null;
		}
	}
}
