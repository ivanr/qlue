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
package com.webkreator.qlue.util;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;

import javax.servlet.http.HttpServletResponse;

public class DownloadUtil {

	public static void sendInlineFile(HttpServletResponse response, File f)
			throws Exception {
		// Set MIME type
		if (f.getName().endsWith(".png")) {
			response.setContentType("image/png");
		} else if (f.getName().endsWith(".pdf")) {
			response.setContentType("application/pdf");
		}

		// Set size
		response.setContentLength((int) f.length());

		// Send data
		OutputStream os = response.getOutputStream();
		BufferedInputStream bis = new BufferedInputStream(
				new FileInputStream(f));
		byte b[] = new byte[1024];

		while (bis.read(b) > 0) {
			os.write(b);
		}

		bis.close();
		os.close();
	}

	public static void sendAttachment(HttpServletResponse response, File f,
			String name, boolean isAttachment) throws Exception {
		// Set MIME type
		if (f.getName().endsWith(".txt")) {
			response.setContentType("text/plain");
		} else if (f.getName().endsWith(".png")) {
			response.setContentType("image/png");
		} else if (f.getName().endsWith(".pdf")) {
			response.setContentType("application/pdf");
		}

		// Set name
		// XXX Validate name
		if (isAttachment) {
			response.setHeader("Content-Disposition", "attachment; filename="
					+ name);
		} else {
			response.setHeader("Content-Disposition", "inline; filename="
					+ name);
		}

		// Set size
		response.setContentLength((int) f.length());

		// Send data
		OutputStream os = response.getOutputStream();
		BufferedInputStream bis = new BufferedInputStream(
				new FileInputStream(f));
		byte b[] = new byte[1024];

		while (bis.read(b) > 0) {
			os.write(b);
		}

		bis.close();
		os.close();
	}
}
