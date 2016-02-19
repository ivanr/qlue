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

import java.io.PrintWriter;
import java.util.List;

import com.webkreator.qlue.util.HtmlEncoder;
import com.webkreator.qlue.*;
import com.webkreator.qlue.Error;
import com.webkreator.qlue.exceptions.AccessForbiddenException;
import com.webkreator.qlue.exceptions.QlueSecurityException;
import com.webkreator.qlue.view.FinalRedirectView;
import com.webkreator.qlue.view.RedirectView;
import com.webkreator.qlue.view.View;

@QlueMapping(suffix = ".html")
public class devMode extends Page {

	@QlueParameter(mandatory = true, state = Page.STATE_POST)
	public String password;

	@Override
	public View onGet() throws Exception {
		// Check that the IP address is in range
		if (getQlueApp().isDeveloperRequest(context) == false) {
			throw new AccessForbiddenException();
		}

		Integer appStatus = getQlueApp().getApplicationDevelopmentMode();
		Integer sessionStatus = getQlueSession().getDevelopmentMode();

		context.response.setContentType("text/html");
		PrintWriter out = context.response.getWriter();
		out.println("<html>");
		out.println("<head><title>Qlue Development Mode Control</title></head>");
		out.println("<body>");
		out.println("<h1>Qlue Development Mode Control</h1>");

		// Application development mode
		out.println("Application mode: ");

		if (appStatus == QlueConstants.DEVMODE_ENABLED) {
			out.println("Enabled");
		} else if (appStatus == QlueConstants.DEVMODE_DISABLED) {
			out.println("Disabled");
		} else if (appStatus == QlueConstants.DEVMODE_ONDEMAND) {
			out.println("On Demand");
		} else if (appStatus == null) {
			out.println("none");
		} else {
			out.println("Unknown: " + appStatus);
		}
		out.println("<br>");

		// Session development mode
		out.println("Session mode: ");

		if (sessionStatus == QlueConstants.DEVMODE_ENABLED) {
			out.println("Enabled");
		} else if (sessionStatus == QlueConstants.DEVMODE_DISABLED) {
			out.println("Disabled");
		} else if (sessionStatus == QlueConstants.DEVMODE_ONDEMAND) {
			out.println("On Demand");
		} else if (sessionStatus == null) {
			out.println("none");
		} else {
			out.println("Unknown: " + sessionStatus);
		}

		out.println("<br><br>");

		if (errors.hasErrors()) {
			List<Error> errorL = errors.getAllErrors();
			for (Error e : errorL) {
				out.println("Error: "
						+ HtmlEncoder.encodeForHTML(e.getMessage()));
			}
		}

		// Display form, when allowed by configuration
		if ((appStatus == QlueConstants.DEVMODE_ENABLED)
				|| (appStatus == QlueConstants.DEVMODE_ONDEMAND)) {
			out.println("<form action=/_qlue/devMode.html method=POST>");

			if (getId() != null) {
				out.println("<input type=hidden name=_pid value=" + getId()
						+ ">");
			}

			out.println("<input type=hidden name=_secret value="
					+ HtmlEncoder.encodeForHTML(getQlueSession()
							.getSessionSecret().getMaskedToken()) + ">");

			if (((sessionStatus == null) && (appStatus == QlueConstants.DEVMODE_ENABLED))
					|| (getQlueSession().getDevelopmentMode() == QlueConstants.DEVMODE_ENABLED)) {
				out.println("<input type=hidden name=password value=disabled>");
				out.println("<input type=submit value=\"Disable session development mode\">");
			} else {
				out.println("Password <font color=red>*</font><br>");
				out.println("<input type=password name=password>");
				out.println("<input type=submit value=\"Enable session development mode\">");
			}

			out.println("</form>");
		}

		out.println("</body>");
		out.println("</html>");

		return null;
	}

	@Override
	public View onPost() throws Exception {
		// Check that the IP address is in range
		if (getQlueApp().isDeveloperRequest(context) == false) {
			throw new AccessForbiddenException();
		}

		Integer appStatus = getQlueApp().getApplicationDevelopmentMode();
		Integer sessionStatus = getQlueSession().getDevelopmentMode();

		// Check that session development mode state
		// manipulation is allowed by configuration
		if ((appStatus != QlueConstants.DEVMODE_ENABLED)
				&& (appStatus != QlueConstants.DEVMODE_ONDEMAND)) {
			throw new QlueSecurityException("Configuration doesn't allow change of development mode");
		}

		if (((sessionStatus == null) && (appStatus == QlueConstants.DEVMODE_ENABLED))
				|| (getQlueSession().getDevelopmentMode() == QlueConstants.DEVMODE_ENABLED)) {
			// Disable session development mode
			getQlueSession().setDevelopmentMode(QlueConstants.DEVMODE_DISABLED);

			return new FinalRedirectView("/_qlue/devMode.html");
		} else {
			if (this.getErrors().hasErrors() == false) {
				// Check that the supplied password matches
				// the one specified in the configuration
				if (qlueApp.checkDeveloperPassword(password) != true) {
					if (qlueApp.getDeveloperPassword() == null) {
						errors.addError("Development mode password not set");
					} else {
						errors.addError("Password mismatch");
					}

					return new RedirectView(this);
				} else {
					// Enable session development mode
					getQlueSession().setDevelopmentMode(
							QlueConstants.DEVMODE_ENABLED);

					return new FinalRedirectView("/_qlue/devMode.html");
				}
			}
		}

		return new RedirectView(this);
	}
}
