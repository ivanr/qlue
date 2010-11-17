/* 
 * Qlue Web Application Framework
 * Copyright 2009 Ivan Ristic <ivanr@webkreator.com>
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
import javax.servlet.http.HttpServletRequest;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class WelcomeFilter implements Filter {

	public static final String DEFAULT_PAGE = "DEFAULT_PAGE";

	private String defaultPage = "index.html";
	
	private Log log = LogFactory.getLog(WelcomeFilter.class);

	public void init(FilterConfig filterConfig) {
		String s = filterConfig.getInitParameter(DEFAULT_PAGE);
		if (s != null) {
			defaultPage = s;
		}
	}

	public void doFilter(ServletRequest request, ServletResponse response,
			FilterChain chain) throws IOException, ServletException {		
		String path = ((HttpServletRequest) request).getRequestURI();		
		if ((defaultPage != null) && (path.endsWith("/"))) {
			String newPath = path + defaultPage;
			
			if (log.isDebugEnabled()) {
				log.debug("Redirecting " + path + " to " + newPath);
			}
			
			request.getRequestDispatcher(newPath).forward(request,
					response);
		} else {
			chain.doFilter(request, response);
		}
	}

	public void destroy() {
		// Nothing to do here, but we still
		// have to provide an implementation
	}
}
