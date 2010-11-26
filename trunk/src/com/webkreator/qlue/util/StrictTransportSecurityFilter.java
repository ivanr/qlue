package com.webkreator.qlue.util;

import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;

/**
 * This Servlet filter will add Strict-Transport-Security response
 * header to all transactions it sees.
 */
public class StrictTransportSecurityFilter implements Filter {

	public static final String PARAM_STS_HEADER = "STS_HEADER";

	private String stsHeader;

	@Override
	public void init(FilterConfig filterConfig) throws ServletException {
		stsHeader = filterConfig.getInitParameter(PARAM_STS_HEADER);
	}

	@Override
	public void doFilter(ServletRequest request, ServletResponse response,
			FilterChain chain) throws IOException, ServletException {		
		if (stsHeader != null) {
			((HttpServletResponse) response).setHeader(
					"Strict-Transport-Security", stsHeader);
		}
		
		chain.doFilter(request, response);
	}

	@Override
	public void destroy() {
		// Nothing to do here
	}
}
