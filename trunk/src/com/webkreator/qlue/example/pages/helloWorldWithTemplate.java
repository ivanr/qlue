package com.webkreator.qlue.example.pages;

import com.webkreator.qlue.Page;
import com.webkreator.qlue.view.DefaultView;
import com.webkreator.qlue.view.View;

public class helloWorldWithTemplate extends Page {
	
	@Override
	public View onGet() throws Exception {			
		return new DefaultView();
	}
}
