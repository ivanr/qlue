package com.webkreator.qlue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class QlueScheduleMethodTaskWrapper implements Runnable {

    private QlueApplication app;

    private Object target;

    private Method method;

    private Object[] args;

    public QlueScheduleMethodTaskWrapper(QlueApplication app, Object o, Method m) {
        this.app = app;
        this.target = o;
        this.method = m;
    }

    public QlueScheduleMethodTaskWrapper(QlueApplication app, Object o,
                                         String methodName) throws NoSuchMethodException {
        this.app = app;
        this.target = o;
        this.method = o.getClass().getMethod(methodName, (Class<?>[]) null);
    }

    public QlueScheduleMethodTaskWrapper(QlueApplication app, Object o,
                                         String methodName, Object[] args) throws NoSuchMethodException {
        this.app = app;
        this.target = o;
        this.method = o.getClass().getMethod(methodName);
        this.args = args;
    }

    @Override
    public void run() {
        try {
            method.invoke(target, args);
        } catch (Throwable t) {
            if (t instanceof InvocationTargetException) {
                app.processUnhandledApplicationException(null, null, t.getCause());
            } else {
                app.processUnhandledApplicationException(null, null, t);
            }
        }
    }

}