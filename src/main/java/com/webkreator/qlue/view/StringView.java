package com.webkreator.qlue.view;

import com.webkreator.qlue.Page;
import com.webkreator.qlue.TransactionContext;

import java.io.PrintWriter;

/**
 * Sends the provided string as the response body, optionally configuring the
 * content type.
 */
public class StringView implements View {

	private String text;

	private String contentType = View.CONTENT_TYPE_TEXT_HTML_UTF8;

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
		if (contentType != null) {
			tx.getResponse().setContentType(contentType);
		}

		PrintWriter out = tx.response.getWriter();
		out.write(text);
		out.close();
	}
}
