/* 
 * Qlue Web Application Framework
 * Copyright 2009,2010 Ivan Ristic <ivanr@webkreator.com>
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
