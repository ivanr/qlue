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
