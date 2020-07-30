/* 
 * Qlue Web Application Framework
 * Copyright 2009-2012 Ivan Ristic <ivanr@webkreator.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.webkreator.qlue.pages;

import com.webkreator.qlue.Page;
import com.webkreator.qlue.exceptions.ForbiddenException;
import com.webkreator.qlue.exceptions.PersistentPageNotFoundException;
import com.webkreator.qlue.exceptions.BadRequestException;
import com.webkreator.qlue.util.HtmlEncoder;
import com.webkreator.qlue.util.WebUtil;
import com.webkreator.qlue.view.View;
import org.apache.velocity.exception.ParseErrorException;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Handle an application exception.
 */
public class handleError extends Page {

    @Override
    public View service() throws Exception {
        Integer statusCode = (Integer) context.request.getAttribute("javax.servlet.error.status_code");
        if (statusCode == null) {
            throw new Exception("Direct access to this error page is not allowed");
        }

        Throwable t = (Throwable) context.request.getAttribute("javax.servlet.error.exception");
        if (t != null) {
            if (t instanceof BadRequestException) {
                return _handleValidationException((BadRequestException) t);
            } else if (t instanceof PersistentPageNotFoundException) {
                return _handlePersistentPageNotFoundException((PersistentPageNotFoundException) t);
            } else if (t instanceof ParseErrorException) {
                return _handleVelocityParseError((ParseErrorException) t);
            // } else if (t instanceof SoyCompilationException) {
            //    return _handleSoyCompilationError((SoyCompilationException) t);
            } else if (t instanceof ForbiddenException) {
                return _handleAccessForbiddenException((ForbiddenException) t);
            } else {
                return _handleGenericThrowable(t);
            }
        } else {
            return _handleStatusCode(statusCode);
        }
    }

    private View _handleAccessForbiddenException(ForbiddenException t) throws IOException {
        context.response.setContentType("text/html");
        context.response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        PrintWriter out = context.response.getWriter();
        out.println("<html>");
        out.println("<head><title>Forbidden</title></head>");
        out.println("<body><h1>Forbidden</h1>");

        if (isQlueDevMode()) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            out.println("<pre>");
            out.println(HtmlEncoder.htmlWhite(sw.toString()));
            out.println("</pre>");
        }

        WebUtil.writePagePaddingforInternetExplorer(out);
        out.println("</body></html>");

        return null;
    }

    private View _handleVelocityParseError(ParseErrorException t) throws Exception {
        if (!isQlueDevMode()) {
            return _handleGenericThrowable(t);
        }

        context.response.setContentType("text/html");
        PrintWriter out = context.response.getWriter();
        out.println("<html>");
        out.println("<head><title>Template Parse Error</title></head>");
        out.println("<body><h1>Template Parse Error</h1>");
        out.println("<pre>");
        out.println(HtmlEncoder.htmlWhite(t.getMessage()));
        out.println("</pre>");
        WebUtil.writePagePaddingforInternetExplorer(out);
        out.println("</body></html>");

        return null;
    }

    /*
    private View _handleSoyCompilationError(SoyCompilationException t) throws Exception {
        if (!isQlueDevMode()) {
            return _handleGenericThrowable(t);
        }

        context.response.setContentType("text/html");
        PrintWriter out = context.response.getWriter();
        out.println("<html>");
        out.println("<head><title>Template Parse Error</title></head>");
        out.println("<body><h1>Template Parse Error</h1>");
        out.println("<pre>");
        out.println(HtmlEncoder.htmlWhite(t.getMessage()));
        out.println("</pre>");
        WebUtil.writePagePaddingforInternetExplorer(out);
        out.println("</body></html>");

        return null;
    }*/

    private View _handlePersistentPageNotFoundException(PersistentPageNotFoundException t) throws Exception {
        context.response.setContentType("text/html");
        PrintWriter out = context.response.getWriter();
        out.println("<html>");
        out.println("<head><title>Activity Not Found</title></head>");
        out.println("<body><h1>Activity Not Found</h1>");
        WebUtil.writePagePaddingforInternetExplorer(out);
        out.println("</body></html>");

        return null;
    }

    private View _handleValidationException(BadRequestException ve) throws Exception {
        context.response.setContentType("text/html");
        PrintWriter out = context.response.getWriter();
        out.println("<html>");
        out.println("<head><title>Parameter Validation Failed</title></head>");
        out.println("<body><h1>Parameter Validation Failed</h1>");

        if (isQlueDevMode()) {
            out.println(ve.getMessage());
        }

        WebUtil.writePagePaddingforInternetExplorer(out);
        out.println("</body></html>");

        return null;
    }

    private View _handleGenericThrowable(Throwable t) throws Exception {
        context.response.setContentType("text/html");
        PrintWriter out = context.response.getWriter();
        out.println("<html>");
        out.println("<head><title>Internal Server Error</title></head>");
        out.println("<body><h1>Internal Server Error</h1>");

        if ((t != null) && (isQlueDevMode())) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            out.println("<pre>");
            out.println(HtmlEncoder.htmlWhite(sw.toString()));
            out.println("</pre>");
        }

        WebUtil.writePagePaddingforInternetExplorer(out);
        out.println("</body></html>");

        return null;
    }

    private View _handleStatusCode(int statusCode) throws Exception {
        String message = WebUtil.getStatusMessage(statusCode);
        if (message == null) {
            message = "Unknown Status Code (" + statusCode + ")";
        }

        WebUtil.writeMessage(context, message);

        return null;
    }
}
