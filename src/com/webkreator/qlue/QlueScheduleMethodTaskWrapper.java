package com.webkreator.qlue;

import java.lang.reflect.Method;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class QlueScheduleMethodTaskWrapper implements Runnable {

	private Log log = LogFactory.getLog(QlueScheduleMethodTaskWrapper.class);

	private Object o;

	private Method m;

	private Object[] args;

	public QlueScheduleMethodTaskWrapper(Object o, Method m) {
		this.o = o;
		this.m = m;
	}

	public QlueScheduleMethodTaskWrapper(Object o, String methodName)
			throws NoSuchMethodException {
		this.o = o;
		this.m = o.getClass().getMethod(methodName, (Class<?>[]) null);
	}

	public QlueScheduleMethodTaskWrapper(Object o, String methodName,
			Object[] args) throws NoSuchMethodException {
		this.o = o;
		this.m = o.getClass().getMethod(methodName);
		this.args = args;
	}

	@Override
	public void run() {
		try {
			m.invoke(o, args);
		} catch (Exception e) {
			log.error("QlueSchedule: Failed to invoke method", e);
		}
	}

}
