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
package com.webkreator.qlue;

import com.webkreator.qlue.util.HtmlEncoder;
import com.webkreator.qlue.util.TextUtil;
import com.webkreator.qlue.util.WebUtil;
import com.webkreator.qlue.view.FinalRedirectView;
import org.apache.commons.codec.binary.Hex;
import org.slf4j.MDC;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.Part;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.SecureRandom;
import java.util.*;

/**
 * This class is used mostly to keep all the other stuff (relevant to a single
 * transaction) in one place.
 */
public class TransactionContext {

    public String txId;

    public String nonce;

    public ServletConfig servletConfig;

    public ServletContext servletContext;

    public HttpServletRequest request;

    public HttpServletResponse response;

    public Page page;

    public QlueApplication app;

    public String requestUri;

    public String requestUriWithQueryString;

    private String requestContentTypeNoCharset;

    private Map<String, String> urlParams = new HashMap<>();

    private Map<String, Object> ctxParams = new HashMap<>();

    private String effectiveRemoteAddr;

    private String effectiveForwardedFor;

    private boolean frontendEncrypted;

    private Map<String, String> responseHeaders = new HashMap<>();

    private Properties properties = new Properties();

    /**
     * Initialise context instance.
     */
    public TransactionContext(QlueApplication app, ServletConfig servletConfig,
                              ServletContext servletContext, HttpServletRequest request,
                              HttpServletResponse response) throws ServletException
    {
        this.app = app;
        this.servletConfig = servletConfig;
        this.servletContext = servletContext;
        this.request = request;
        this.response = response;

        generateTxId();
        generateNonce();

        initRequestUri();
        handleFrontendEncryption();
        handleForwardedFor();
        parseContentType();

        // Expose transaction information to the logging subsystem.
        MDC.put("txId", getTxId());
        MDC.put("remoteAddr", getEffectiveRemoteAddr());
    }

    public Properties getProperties() {
        return properties;
    }

    private void generateTxId() {
        if (app.isTrustedProxyRequest(this)) {
            txId = request.getHeader("X-Transaction-ID");
            if (txId != null) {
                return;
            }
        }

        txId = app.generateTransactionId();
    }

    private void generateNonce() {
        Random random = new SecureRandom();
        byte[] nonceBytes = new byte[16];
        random.nextBytes(nonceBytes);
        nonce = TextUtil.toHex(nonceBytes);
        properties.setProperty("nonce", nonce);
    }

    public String getNonce() {
        return nonce;
    }

    private void handleFrontendEncryption() {
        setFrontendEncrypted(false);

        switch (app.getFrontendEncryptionCheck()) {
            case QlueApplication.FRONTEND_ENCRYPTION_NO:
                // Do nothing, assume there is no encryption.
                break;
            case QlueApplication.FRONTEND_ENCRYPTION_CONTAINER:
                // Trust the container.
                if (request.isSecure()) {
                    setFrontendEncrypted(true);
                }
            case QlueApplication.FRONTEND_ENCRYPTION_FORCE_YES:
                // Assume there is encryption.
                setFrontendEncrypted(true);
                break;
            case QlueApplication.FRONTEND_ENCRYPTION_TRUSTED_HEADER:
                // Look for a trusted header to tell us.
                if (app.isTrustedProxyRequest(this)) {
                    String frontendProtocol = request.getHeader("X-Forwarded-Proto");
                    if (frontendProtocol != null) {
                        if (frontendProtocol.equals("https")) {
                            setFrontendEncrypted(true);
                        }
                    }
                }
                break;
            default:
                throw new RuntimeException("Unknown value for the frontend encryption check: " + app.getFrontendEncryptionCheck());
        }
    }

    private void handleForwardedFor() {
        if (app.isTrustedProxyRequest(this)) {
            String combinedAddresses = request.getHeader("X-Forwarded-For");
            if (TextUtil.isEmpty(combinedAddresses) == false) {
                String[] sx = combinedAddresses.split("[,\\x20]");
                if (sx.length == 0) {
                    // This will probably never happen, but still.
                    return;
                }

                // Extract the last IP address.
                try {
                    // Use round-trip conversion to validate the provided string.
                    effectiveRemoteAddr = InetAddress.getByName(sx[sx.length - 1]).getHostAddress();
                } catch (UnknownHostException e) {
                    // TODO Log
                    // e.printStackTrace();
                    return;
                }

                // If there's more than one IP address provided, combine them
                // in order to produce the effective X-Forwarded-For header value.
                if (sx.length > 1) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < sx.length - 1; i++) {
                        if (TextUtil.isEmpty(sx[i]) == false) {
                            if (i != 0) {
                                sb.append(", ");
                            }

                            sb.append(sx[i]);
                        }
                    }

                    effectiveForwardedFor = sb.toString();
                } else {
                    // When there's only one IP address that means that the
                    // effective X-Forwarded-For is now null. We're using an
                    // empty string to indicate this, so that we know to return
                    // the correct value (and not the actual header).
                    effectiveForwardedFor = "";
                }

                // TODO Remove or modify the original X-Forwarded-For request
                // header. Add X-Qlue-Forwarded-For to keep the original value.
            }
        }
    }

    public String getLastForwardedFor() {
        return getLastForwardedFor(getEffectiveForwardedFor());
    }

    public String getLastForwardedFor(String input) {
        if (input == null) {
            return null;
        }

        String[] sx = input.split("[,\\x20]");
        if (sx.length < 1) {
            return null;
        }

        // Extract the last IP address.
        try {
            // Use round-trip conversion to validate the provided string.
            return InetAddress.getByName(sx[sx.length - 1]).getHostAddress();
        } catch (UnknownHostException e) {
            // TODO Log
            // e.printStackTrace();
            return null;
        }
    }

    public String getEffectiveForwardedFor() {
        if (effectiveForwardedFor != null) {
            if (TextUtil.isEmpty(effectiveForwardedFor)) {
                return null;
            }

            return effectiveForwardedFor;
        }

        return request.getHeader("X-Forwarded-For");
    }

    public String getEffectiveRemoteAddr() {
        if (effectiveRemoteAddr != null) {
            return effectiveRemoteAddr;
        }

        return request.getRemoteAddr();
    }

    /**
     * Does the request associated with this transaction use GET or HEAD (the
     * latter has the same semantics as GET)?
     */
    public boolean isGetOrHead() {
        if (request.getMethod().equals("GET") || request.getMethod().equals("HEAD")) {
            return true;
        } else {
            return false;
        }
    }

    public boolean isDelete() {
        return request.getMethod().equals("DELETE");
    }

    public boolean isPatch() {
        return request.getMethod().equals("PATCH");
    }

    public boolean isPost() {
        return request.getMethod().equals("POST");
    }

    public boolean isPut() {
        return request.getMethod().equals("PUT");
    }

    public QluePageManager getQluePageManager() {
        HttpSession httpSession = request.getSession();

        QluePageManager qluePageManager = (QluePageManager) httpSession.getAttribute(QlueConstants.QLUE_SESSION_PAGE_MANAGER);
        if (qluePageManager == null) {
            qluePageManager = new QluePageManager();
            httpSession.setAttribute(QlueConstants.QLUE_SESSION_PAGE_MANAGER, qluePageManager);
        }

        return qluePageManager;
    }

    /**
     * Find persistent page with the given ID.
     */
    public Page findPersistentPage(String pid) {
        return getQluePageManager().findPage(Integer.parseInt(pid));
    }

    /**
     * Keep the given page in persistent storage.
     */
    public void persistPage(Page page) {
        getQluePageManager().storePage(page);
    }

    /**
     * Retrieve request associated with this transaction.
     */
    public HttpServletRequest getRequest() {
        return request;
    }

    /**
     * Retrieve response associated with this transaction.
     */
    public HttpServletResponse getResponse() {
        return response;
    }

    /**
     * Retrieve servlet config associated with this transaction.
     */
    public ServletConfig getServletConfig() {
        return servletConfig;
    }

    /**
     * Retrieve servlet context associated with this transaction.
     */
    public ServletContext getServletContext() {
        return servletContext;
    }

    /**
     * Retrieve request URI associated with this transaction.
     */
    public String getRequestUriWithQueryString() {
        return requestUriWithQueryString;
    }

    public String getRequestUri() {
        return requestUri;
    }

    /**
     * Initialise request URI.
     */
    private void initRequestUri() throws ServletException {
        // Retrieve URI and normalise it
        requestUri = WebUtil.normaliseUri(request.getRequestURI());

        // We want our URI to include the query string
        if (request.getQueryString() != null) {
            requestUriWithQueryString = requestUri + "?" + request.getQueryString();
        } else {
            requestUriWithQueryString = requestUri;
        }

        // We are not expecting back-references in the URI, so
        // respond with an error if we do see one
        if (requestUriWithQueryString.indexOf("/../") != -1) {
            throw new ServletException(
                    "Security violation: directory backreference "
                            + "detected in request URI: "
                            + requestUriWithQueryString);
        }
    }

    /**
     * Replaces persistent page with a view.
     */
    public void replacePage(Page page, FinalRedirectView view) {
        getQluePageManager().replacePage(page, view);
    }

    /**
     * Check if the current contexts is an error handler.
     */
    public boolean isErrorHandler() {
        Integer errorStatusCode = (Integer) request
                .getAttribute("javax.servlet.error.status_code");

        if (errorStatusCode != null) {
            return true;
        } else {
            return false;
        }
    }

    /**
     * Retrieves transaction ID.
     */
    public String getTxId() {
        return txId;
    }

    /**
     * Retrieves the record of the persistent page with the given ID.
     */
    public PersistentPageRecord findPersistentPageRecord(int pid) {
        return getQluePageManager().findPageRecord(pid);
    }

    /**
     * Outputs transaction-related debugging information.
     */
    public void writeRequestDevelopmentInformation(PrintWriter out) {
        out.println(" Method: " + HtmlEncoder.html(request.getMethod()));
        out.println(" URI: " + HtmlEncoder.html(request.getRequestURI()));
        out.println(" Query String: " + HtmlEncoder.html(request.getQueryString()));
        out.println(" Remote Addr: " + HtmlEncoder.html(request.getRemoteAddr()));
        out.println(" Remote Port: " + request.getRemotePort());
        out.println(" Protocol: " + HtmlEncoder.html(request.getProtocol()));
        out.println("");
        out.println("<b>Request Headers</b>\n");
        for (Enumeration<String> e = request.getHeaderNames(); e.hasMoreElements(); ) {
            String name = e.nextElement();
            for (Enumeration<String> e2 = request.getHeaders(name); e2.hasMoreElements(); ) {
                out.println(" " + HtmlEncoder.html(name) + ": " + HtmlEncoder.html(e2.nextElement()));
            }
        }
        out.println("");
        out.println("<b>Request Parameters</b>\n");
        for (Enumeration<String> e = request.getParameterNames(); e.hasMoreElements(); ) {
            String name = e.nextElement();
            String[] values = request.getParameterValues(name);
            for (String value : values) {
                out.println(" " + HtmlEncoder.html(name) + ": " + HtmlEncoder.html(value));
            }
        }
    }

    /**
     * Retrieves parameter with the given name.
     */
    public String getParameter(String name) throws Exception {
        return getRequest().getParameter(name);
    }

    /**
     * Retrieves all values of parameters with the given name.
     */
    public String[] getParameterValues(String name) {
        return getRequest().getParameterValues(name);
    }

    public String getUrlParameter(String name) {
        return urlParams.get(name);
    }

    public void setUrlParameter(String name, String value) {
        urlParams.put(name, value);
    }

    public void addUrlParameter(String name, String value) {
        setUrlParameter(name, value);
    }

    public String getHost() {
        String host = request.getHeader("host");
        if (host == null) {
            return null;
        }

        host = host.toLowerCase();

        int i = host.indexOf(":");
        if (i != -1) {
            host = host.substring(0, i);
        }

        return host;
    }

    public void setParam(String name, Object value) {
        ctxParams.put(name, value);
    }

    public Object getParam(String name) {
        return ctxParams.get(name);
    }

    public Map<String, Object> getParams() {
        return ctxParams;
    }

    protected void setFrontendEncrypted(boolean b) {
        frontendEncrypted = b;
    }

    public boolean isFrontendEncrypted() {
        return frontendEncrypted;
    }

    public Part getPart(String name) throws IOException, ServletException {
        return request.getPart(name);
    }

    public void setResponseHeader(String name, String value) {
        if (value != null) {
            responseHeaders.put(name, value);
        } else {
            responseHeaders.remove(name);
        }
    }

    public Map<String, String> getResponseHeaders() {
        return responseHeaders;
    }

    private void parseContentType() {
        String ct = request.getContentType();
        if (ct == null) {
            requestContentTypeNoCharset = null;
            return;
        }

        ct = ct.trim();

        int i = ct.indexOf(";");
        if (i == -1) {
            requestContentTypeNoCharset = ct;
        } else {
            requestContentTypeNoCharset = ct.substring(0, i).trim();
        }
    }

    public String getRequestContentTypeNoCharset() {
        return requestContentTypeNoCharset;
    }

    public void invalidateHttpSession() {
        HttpSession httpSession = request.getSession(false);
        if (httpSession != null) {
            httpSession.invalidate();
        }
    }

    public boolean isHttpSessionAvailable() {
        if (request.getSession(false) != null) {
            return true;
        } else {
            return false;
        }
    }
}
