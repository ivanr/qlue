package com.webkreator.qlue.util;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;

public class QlueErrorPageServlet extends HttpServlet {

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        outputErrorPage(request, response);
    }

    public static void outputErrorPage(HttpServletRequest request, HttpServletResponse response) throws IOException {
        int statusCode = response.getStatus();

        String message = WebUtil.getStatusMessage(statusCode);
        if (message == null) {
            message = "Unknown Status Code (" + statusCode + ")";
        }

        response.setContentType("text/html");
        PrintWriter out = response.getWriter();
        out.print("<!DOCTYPE html>\n<html><head><title>");
        out.print(HtmlEncoder.html(message));
        out.println("</title></head>");
        out.print("<body><h1>");
        out.print(HtmlEncoder.html(message));
        out.println("</h1>");
        WebUtil.writePagePaddingforInternetExplorer(out);
        out.println("</body></html>");
    }
}
