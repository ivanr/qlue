package com.webkreator.qlue.router;

import java.io.File;

import com.webkreator.qlue.TransactionContext;
import com.webkreator.qlue.view.DownloadView;
import com.webkreator.qlue.view.StatusCodeView;

public class StaticFileRouter implements Router {

	private String path;

	public StaticFileRouter(String path) {
		this.path = path;
	}

	@Override
	public Object route(TransactionContext context, String extraPath) {
		if (extraPath.indexOf("..") != -1) {
			throw new SecurityException("StaticFileRouter: Invalid path: "
					+ extraPath);
		}

		File file = new File(path, extraPath);
		if (file.exists()) {
			if (file.isDirectory()) {
				file = new File(file, "/index.html");
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
