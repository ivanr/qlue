package com.webkreator.qlue.example.pages.subFolder;

import com.webkreator.qlue.Page;
import com.webkreator.qlue.view.NamedView;
import com.webkreator.qlue.view.View;

public class namedViewTest extends Page {
	
	@Override
	public View onGet() throws Exception {
		return new NamedView("namedViewTest");
		//return new DefaultView();
	}
}