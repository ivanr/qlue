package com.webkreator.qlue.example.pages;

import java.io.PrintWriter;

import com.webkreator.qlue.Page;
import com.webkreator.qlue.QlueParameter;
import com.webkreator.qlue.view.View;

public class pathParamTest extends Page {
	
	@QlueParameter(mandatory = false, state = Page.STATE_URL)
	public String routeSuffix = "";
	
	@Override
	public View onGet() throws Exception {
		PrintWriter out = context.response.getWriter();
		out.println("Hello! test: [" + routeSuffix + "]");
		return null;
	}
}
