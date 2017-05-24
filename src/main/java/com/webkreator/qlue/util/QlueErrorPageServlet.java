package com.webkreator.qlue.util;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;

public class QlueErrorPageServlet extends HttpServlet {

    public static final String ERROR_PAGES_LOCATION = "/WEB-INF/error-pages/";

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        outputErrorPage(request, response);
    }

    public static void outputErrorPage(HttpServletRequest request, HttpServletResponse response) throws IOException {
        outputErrorPage(request, response, ERROR_PAGES_LOCATION);
    }

    public static void outputErrorPage(HttpServletRequest request, HttpServletResponse response, String errorPagesLocation) throws IOException {
        int statusCode = response.getStatus();

        // Servlet error properties of interest:
        // - javax.servlet.error.exception
        // - javax.servlet.error.status_code
        // - javax.servlet.error.servlet_name
        // - javax.servlet.error.request_uri
        Exception exception = (Exception)request.getAttribute("javax.servlet.error.exception");
        if (exception != null) {
            statusCode = 500;
        }

        File errorFile = findErrorPage(request, statusCode, errorPagesLocation);
        if (errorFile != null) {
            response.setContentType("text/html; charset=utf-8");
            sendFile(response, errorFile);
        } else {
            // No file, send the hardcoded error response.
            String message = WebUtil.getStatusMessage(statusCode);
            if (message == null) {
                message = "Unknown Status Code (" + statusCode + ")";
            }

            response.setContentType("text/html; charset=utf-8");
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

    protected static File findErrorPage(HttpServletRequest request, int statusCode, String errorPagesLocation) {
        File errorPagesHome = new File(request.getServletContext().getRealPath(errorPagesLocation));
        File f = null;

        f = new File(errorPagesHome, "error-" + statusCode + ".html");
        if (f.exists() && f.canRead()) {
            return f;
        }

        f = new File(errorPagesHome, "catch-all.html");
        if (f.exists() && f.canRead()) {
            return f;
        }

        return null;
    }

    protected static void sendFile(HttpServletResponse response, File file) throws IOException {
        PrintWriter out = response.getWriter();
        try (InputStream in = new FileInputStream(file);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.write(line);
            }
        }
    }
}
