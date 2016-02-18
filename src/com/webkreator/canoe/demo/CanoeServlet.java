package com.webkreator.canoe.demo;

import javax.servlet.ServletException;

import com.webkreator.qlue.QlueApplication;
import com.webkreator.qlue.QlueServlet;

public class CanoeServlet extends QlueServlet {

	private static final long serialVersionUID = 1L;

	@Override
	protected void createApplicationObject() throws ServletException {
		setApp(new QlueApplication("com.webkreator.canoe.demo.pages"));
	}
}
