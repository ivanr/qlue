package com.webkreator.qlue.example.pages;

import java.io.PrintWriter;

import com.webkreator.qlue.Page;
import com.webkreator.qlue.QlueParameter;
import com.webkreator.qlue.QlueUrlParams;
import com.webkreator.qlue.view.View;

@QlueUrlParams("/test/(.*)")
public class pathParamTest extends Page {
	
	@QlueParameter(urlParam = 1, state = Page.STATE_URL)
	public String test;
	
	@Override
	public View onGet() throws Exception {
		PrintWriter out = context.response.getWriter();
		out.println("Hello! test: [" + test + "]");
		return null;
	}
}
