package com.webkreator.qlue.util;

import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ErrorReportValve;
import org.apache.jasper.runtime.ExceptionUtils;

public class TomcatErrorPageValve extends ErrorReportValve {

    @Override
    protected void report(Request request, Response response, Throwable throwable) {
        try {
            String errorPagesLocation = System.getProperties().getProperty("QLUE_ERROR_PAGES");
            if (errorPagesLocation != null) {
                QlueErrorPageServlet.outputErrorPage(request, response, errorPagesLocation);
            } else {
                QlueErrorPageServlet.outputErrorPage(request, response);
            }
        } catch(Exception e) {
            ExceptionUtils.handleThrowable(e);
        }
    }
}
