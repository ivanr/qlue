package com.webkreator.qlue.view;

import java.io.File;

import com.webkreator.qlue.Page;
import com.webkreator.qlue.TransactionContext;
import com.webkreator.qlue.util.DownloadUtil;

public class DownloadView implements View {
	
	private File file;
	
	public DownloadView(File file) {
		this.file = file;
	}

	@Override
	public void render(TransactionContext context, Page page) throws Exception {
		DownloadUtil.sendInlineFile(context.response, file);
	}
}
