package com.webkreator.qlue.example.pages;

import java.io.PrintWriter;

import com.webkreator.qlue.Page;
import com.webkreator.qlue.QlueFile;
import com.webkreator.qlue.QlueParameter;
import com.webkreator.qlue.view.View;

public class nonPersistentFileUpload extends Page {
	
	@QlueParameter(mandatory = true, state = Page.STATE_POST)
	public QlueFile file;
	
	@Override
	public View onGet() throws Exception {
		PrintWriter out = context.response.getWriter();
		context.response.setContentType("text/html");

		out.println("<form enctype=multipart/form-data method=POST>");
		out.println("<input type=text value=xxx>");
		out.println("<input type=file name=file>");
		out.println("<input type=submit>");
		out.println("</form>");

		return null;
	}
	
	@Override
	public View onPost() throws Exception {
		PrintWriter out = context.response.getWriter();

		out.println("Hello World!");
		out.println(file.length());

		return null;
	}
}
