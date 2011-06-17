/* 
 * Qlue Web Application Framework
 * Copyright 2009-2011 Ivan Ristic <ivanr@webkreator.com>
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

import com.webkreator.qlue.TransactionContext;
import com.webkreator.qlue.view.DownloadView;
import com.webkreator.qlue.view.StatusCodeView;

public class StaticFileRouter implements Router {
	
	protected RouteManager manager;

	private String path;

	public StaticFileRouter(RouteManager manager, String path) {
		this.manager = manager;
		this.path = path;
	}

	@Override
	public Object route(TransactionContext context, String extraPath) {
		if (extraPath.contains("..")) {
			throw new SecurityException("StaticFileRouter: Invalid path: "
					+ extraPath);
		}

		if (extraPath.toLowerCase().contains("web-inf")) {
			throw new SecurityException("StaticFileRouter: Invalid path: "
					+ extraPath);
		}

		File file = new File(path, extraPath);
		if (file.exists()) {
			if (file.isDirectory()) {
				file = new File(file, manager.getIndex() + "."
						+ manager.getSuffix());
				if (file.exists()) {
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
