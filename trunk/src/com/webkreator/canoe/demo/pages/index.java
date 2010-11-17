package com.webkreator.canoe.demo.pages;

import java.io.PrintWriter;

import com.webkreator.canoe.EncodingTool;
import com.webkreator.canoe.demo.SimpleTemplate;
import com.webkreator.qlue.Page;
import com.webkreator.qlue.QlueParameter;
import com.webkreator.qlue.view.DefaultView;
import com.webkreator.qlue.view.View;

public class index extends Page {

	@QlueParameter(mandatory = true, state = "POST")
	public String _template;

	@Override
	public View onGet() throws Exception {
		return new DefaultView();
	}

	@Override
	public View onPost() throws Exception {
		context.response.setContentType("text/html");

		SimpleTemplate st = new SimpleTemplate();
		try {
			st.process(_template, context.response.getWriter(), context.request);
		} catch (Throwable t) {
			PrintWriter out = context.response.getWriter();
			out.println("'\"--></script></textarea><pre><hr>");
			out.println(EncodingTool.encodeForHTML(t.getMessage()));
			out.println("\nTemplate: ");
			out.println(EncodingTool.encodeForHTMLPreserveWhitespace(_template));
		}

		return null;
	}
}
