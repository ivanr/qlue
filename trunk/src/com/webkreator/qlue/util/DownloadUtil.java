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
package com.webkreator.qlue.util;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.text.CharacterIterator;
import java.text.StringCharacterIterator;

import javax.servlet.http.HttpServletResponse;

/**
 * This utility class can send a file from the filesystem, 
 * either inline or as an attachment.
 */
public class DownloadUtil {

	/**
	 * Sends file in HTTP response.
	 * 
	 * @param response
	 * @param f
	 * @throws Exception
	 */
	public static void sendFile(HttpServletResponse response, File f)
			throws Exception {
		sendFile(response, f, null, null, false);
	}

	/**
	 * Sends file in HTTP response, with C-D header control.
	 * 
	 * @param response
	 * @param f
	 * @throws Exception
	 */
	public static void sendFile(HttpServletResponse response, File f,
			String contentType, String name, boolean isAttachment) throws Exception {
		// If a content type was provided, use it;
		// otherwise use our map to set the correct one
		if (contentType == null) {
			int i = f.getName().lastIndexOf(".");
			if (i != -1) {
				String suffix = f.getName().substring(i + 1);
				contentType = MimeTypes.getMimeType(suffix);
			}
		}

		// Set C-T, if we have it
		if (contentType != null) {
			response.setContentType(contentType);
		}

		if (name != null) {
			// Do not allow control characters in the name
			StringBuffer sb = new StringBuffer();
			CharacterIterator it = new StringCharacterIterator(name);
			for (char c = it.first(); c != CharacterIterator.DONE; c = it
					.next()) {
				if (c < 0x20) {
					throw new SecurityException(
							"Invalid character in filename: " + c);
				}

				if ((c == '\\') || (c == '"')) {
					sb.append('\\');
					sb.append(c);
				} else {
					sb.append(c);
				}
			}

			String escapedName = sb.toString();

			// Set name
			if (isAttachment) {
				response.setHeader("Content-Disposition",
						"attachment; filename=" + escapedName);
			} else {
				response.setHeader("Content-Disposition", "inline; filename="
						+ escapedName);
			}
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
