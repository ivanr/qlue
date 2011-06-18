package com.webkreator.qlue.example.pages.$internal;

import java.io.PrintWriter;

import com.webkreator.qlue.Page;
import com.webkreator.qlue.view.View;

public class index extends Page {

	@Override
	public View onGet() throws Exception {
		PrintWriter out = context.response.getWriter();
		out.println("Hello World from internal package!");
		return null;
	}
}
