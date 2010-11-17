package com.webkreator.qlue.example.pages;

import java.io.PrintWriter;
import java.util.Date;

import com.webkreator.qlue.Page;
import com.webkreator.qlue.view.View;

public class CurrentDate extends Page {
	
	@Override
	public View onGet() throws Exception {
		PrintWriter out = context.response.getWriter();
		
		context.response.setContentType("text/html");
		
		out.println(new Date());

		return null;
	}
}
