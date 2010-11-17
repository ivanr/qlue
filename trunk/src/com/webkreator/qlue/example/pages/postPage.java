package com.webkreator.qlue.example.pages;

import com.webkreator.qlue.Page;
import com.webkreator.qlue.QlueParameter;
import com.webkreator.qlue.view.View;

public class postPage extends Page {
	
	@QlueParameter(mandatory = true, state = "POST")
	public String name;
	
	@Override
	public View onGet() throws Exception {
		context.response.setContentType("text/html");
		context.response.getWriter().write("<form action=/postPage.html method=POST><input type=text name=name value='123'><input type=submit></form>");
		return null;
	}
	
	@Override
	public View onPost() throws Exception {
		context.response.setContentType("text/html");
		context.response.getWriter().write(name);
		return null;
	}
}
