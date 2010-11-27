package com.webkreator.qlue.example.pages;

import com.webkreator.qlue.Page;
import com.webkreator.qlue.view.View;

public class exceptionTest extends Page {
	
	@Override
	public View onGet() throws Exception {					
		errors.addError("Test error message");
		throw new Exception("test");		
	}
}
