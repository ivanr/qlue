package com.webkreator.qlue.util;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;

public class QlueErrorPageServlet extends HttpServlet {

    private static final String ERROR_PAGES_LOCATION = "/WEB-INF/error-pages/";

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String errorPagesLocation = request.getServletContext().getRealPath(ERROR_PAGES_LOCATION);
        TomcatErrorPageValve.outputErrorPage(request, response, errorPagesLocation);
    }
}
