package com.webkreator.qlue.view;

import com.webkreator.qlue.Page;
import com.webkreator.qlue.TransactionContext;
import com.webkreator.qlue.util.WebUtil;

public class StatusCodeView implements View {

	private int status;

	private String message;

	public StatusCodeView(int status) {
		this.status = status;
		this.message = WebUtil.getStatusMessage(status);
		if (this.message == null) {
			this.message = "Error";
		}
	}

	public StatusCodeView(int status, String message) {
		this.status = status;
		this.message = message;
	}

	@Override
	public void render(TransactionContext context, Page page) throws Exception {
		context.response.sendError(status, message);
		WebUtil.writeMessage(context, message);
	}
}
