package com.webkreator.qlue.example;

import com.webkreator.qlue.QlueApplication;
import com.webkreator.qlue.view.ClasspathVelocityViewFactory;

public class QlueExampleApplication extends QlueApplication {
	
	public QlueExampleApplication() {
		super("com.webkreator.qlue.example.pages");
		
		ClasspathVelocityViewFactory viewFactory = new ClasspathVelocityViewFactory();		
		setViewFactory(viewFactory);
	}
}
