package com.webkreator.qlue.util;

import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ErrorReportValve;
import org.apache.jasper.runtime.ExceptionUtils;

import java.io.PrintWriter;

public class TomcatErrorPageValve extends ErrorReportValve {

    @Override
    protected void report(Request request, Response response, Throwable throwable) {
        int statusCode = response.getStatus();

        if ((statusCode < 400) || (response.getContentWritten() > 0) || (!response.setErrorReported())) {
            return;
        }

        String message = WebUtil.getStatusMessage(statusCode);
        if (message == null) {
            message = "Unknown Status Code (" + statusCode + ")";
        }

        try {
            response.setContentType("text/html");
            PrintWriter out = response.getWriter();
            out.print("<!DOCTYPE html><html><head><title>");
            out.print(HtmlEncoder.html(message));
            out.println("</title></head>");
            out.print("<body><h1>");
            out.print(HtmlEncoder.html(message));
            out.println("</h1>");
            WebUtil.writePagePaddingforInternetExplorer(out);
            out.println("</body></html>");
        } catch(Exception e) {
            ExceptionUtils.handleThrowable(e);
        }
    }
}
