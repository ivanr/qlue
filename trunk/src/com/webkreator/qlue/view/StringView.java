package com.webkreator.qlue.view;

import java.io.PrintWriter;

import com.webkreator.qlue.Page;
import com.webkreator.qlue.TransactionContext;

/**
 * Sends the provided string as the response body, optionally configuring the
 * content type.
 */
public class StringView implements View {

	private String text;

	private String contentType = "text/plain";

	/**
	 * Create a new view, using the provided text.
	 * 
	 * @param text
	 */
	public StringView(String text) {
		this.text = text;
	}

	/**
	 * Create a new view, using the provided text and content type.
	 * 
	 * @param text
	 * @param contentType
	 */
	public StringView(String text, String contentType) {
		this.text = text;
		this.contentType = contentType;
	}

	@Override
	public void render(TransactionContext tx, Page page) throws Exception {
		tx.getResponse().setContentType(contentType);
		PrintWriter out = tx.response.getWriter();
		out.write(text);
		out.close();
	}
}
