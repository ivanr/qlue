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
package com.webkreator.qlue.util;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.CharacterIterator;
import java.text.StringCharacterIterator;

import javax.servlet.http.HttpServletResponse;

import com.webkreator.qlue.exceptions.QlueSecurityException;
import org.apache.commons.codec.binary.Base64;

import com.webkreator.qlue.exceptions.PageNotFoundException;
import com.webkreator.qlue.TransactionContext;

/**
 * This utility class can send a file from the filesystem, either inline or as
 * an attachment.
 */
public class DownloadUtil {

	/**
	 * Sends file in HTTP response.
	 * 
	 * @param response
	 * @param f
	 * @throws Exception
	 */
	public static void sendFile(TransactionContext context, File f)
			throws Exception {
		sendFile(context, f, null /* contentType */, null /* name */, false /* isAttachment */);
	}

	/**
	 * Sends file in HTTP response, with C-D header control.
	 * 
	 * @param response
	 * @param f
	 * @throws Exception
	 */
	public static void sendFile(TransactionContext context, File f,
			String contentType, String name, boolean isAttachment)
			throws Exception {
		OutputStream os = null;
		BufferedInputStream bis = null;

		try {
			// If C-T was not provided, try to use file
			// extension to select the correct value
			if (contentType == null) {
				int i = f.getName().lastIndexOf(".");
				if (i != -1) {
					String suffix = f.getName().substring(i + 1);
					contentType = MimeTypes.getMimeType(suffix);
				}
			}

			// Set C-T, if we have it
			if (contentType != null) {
				context.response.setContentType(contentType);
			}

			// Send file name in C-D header
			if (name != null) {
				// Do not allow control characters in the name
				StringBuffer sb = new StringBuffer();
				CharacterIterator it = new StringCharacterIterator(name);
				for (char c = it.first(); c != CharacterIterator.DONE; c = it
						.next()) {
					if (c < 0x20) {
						throw new QlueSecurityException("Invalid character in filename: " + c);
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
					context.response.setHeader("Content-Disposition",
							"attachment; filename=\"" + escapedName + "\"");
				} else {
					context.response.setHeader("Content-Disposition",
							"inline; filename=\"" + escapedName + "\"");
				}
			}

			// Prepare data
			String filename = f.getAbsolutePath();
			long length = f.length();
			long lastModified = f.lastModified();
			String eTag = constructHash(filename + "_" + length + "_"
					+ lastModified);

			// Check If-None-Match to determine if we can respond with 304
			String ifNoneMatch = context.request.getHeader("If-None-Match");
			if ((ifNoneMatch != null)
					&& ((ifNoneMatch.compareTo("*") == 0) || (ifNoneMatch
							.compareTo(eTag) == 0))) {
				context.response.setHeader("ETag", eTag);
				context.response.sendError(HttpServletResponse.SC_NOT_MODIFIED);
				return;
			}

			// Check If-Modified-Since to determine if we can respond with 304
			long ifModifiedSince = context.request
					.getDateHeader("If-Modified-Since");
			if ((ifNoneMatch == null)
					&& ((ifModifiedSince != -1) && (ifModifiedSince + 1000 > lastModified))) {
				context.response.setHeader("ETag", eTag);
				context.response.sendError(HttpServletResponse.SC_NOT_MODIFIED);
				return;
			}

			// Set size
			if (length > Integer.MAX_VALUE) {
				throw new RuntimeException("File longer than Integer.MAX_VALUE");

			}

			context.response.setContentLength((int) length);

			context.response.setDateHeader("Last-Modified", lastModified);
			context.response.setHeader("ETag", eTag);

			// Send data
			os = context.response.getOutputStream();
			bis = new BufferedInputStream(new FileInputStream(f));
			byte b[] = new byte[1024];

			while (bis.read(b) > 0) {
				os.write(b);
			}
		} catch (FileNotFoundException e) {
			throw new PageNotFoundException();
		} finally {
			if (os != null) {
				os.close();
			}

			if (bis != null) {
				bis.close();
			}
		}
	}

	public static String constructHash(String input) {
		MessageDigest md = null;

		try {
			md = MessageDigest.getInstance("SHA-1");
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}

		return Base64.encodeBase64URLSafeString(md.digest(input.getBytes()));
	}
}
