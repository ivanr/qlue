package com.webkreator.qlue;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class ClassRouter implements Router {

	private Log log = LogFactory.getLog(ClassRouter.class);

	private Class<Page> page;

	@SuppressWarnings({ "unchecked", "rawtypes" })
	public ClassRouter(String className) {
		Class candidate = null;

		// Look for class
		try {
			ClassLoader classLoader = Thread.currentThread()
					.getContextClassLoader();
			candidate = Class.forName(className, true, classLoader);
		} catch (Exception e) {
			throw new RuntimeException("ClassRouter: Unknown class: "
					+ className);
		}

		// Check class is instance of Page
		if (!Page.class.isAssignableFrom(candidate)) {
			throw new RuntimeException("ClassRouter: Class " + className
					+ " is not a subclass of Page.");
		}

		page = candidate;
	}

	@Override
	public Object route(TransactionContext tx, String extraPath) {
		tx.setPathInfo(extraPath);

		try {
			return page.newInstance();
		} catch (Exception e) {
			log.error("Error creating page instance: " + e.getMessage(), e);
			return null;
		}
	}
}
