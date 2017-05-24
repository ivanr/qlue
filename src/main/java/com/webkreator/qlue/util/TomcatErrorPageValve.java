package com.webkreator.qlue.util;

import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ErrorReportValve;
import org.apache.jasper.runtime.ExceptionUtils;

public class TomcatErrorPageValve extends ErrorReportValve {

    @Override
    protected void report(Request request, Response response, Throwable throwable) {
        int statusCode = response.getStatus();

        if ((statusCode < 400) || (response.getContentWritten() > 0) || (!response.setErrorReported())) {
            return;
        }

        try {
            QlueErrorPageServlet.outputErrorPage(request, response);
        } catch(Exception e) {
            ExceptionUtils.handleThrowable(e);
        }
    }
}
