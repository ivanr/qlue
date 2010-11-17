package com.webkreator.qlue.example;

import javax.servlet.ServletException;

import com.webkreator.qlue.QlueServlet;

public class QlueExampleServlet extends QlueServlet {

	private static final long serialVersionUID = 1L;

	@Override
	protected void subclassInit() throws ServletException {
		setApplication(new QlueExampleApplication());
	}
}
