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

import com.webkreator.qlue.util.BearerToken;

import java.io.PrintWriter;
import java.io.Serializable;
import java.util.Locale;

/**
 * Represents a user session. This class implements the basics needed by the
 * framework, but applications will typically inherit it to add additional
 * functionality.
 */
public class QlueSession implements Serializable {

	private static final long serialVersionUID = -6165030155311291224L;

	private Locale locale = Locale.ENGLISH;

	private BearerToken sessionSecret = new BearerToken();

	private Integer developmentMode = null;

	/**
	 * Retrieve session development mode.
	 */
	public Integer getDevelopmentMode() {
		return developmentMode;
	}

	/**
	 * Set session development mode.
	 */
	public void setDevelopmentMode(Integer developmentMode) {
		if ((developmentMode != QlueConstants.DEVMODE_DISABLED)
				&& (developmentMode != QlueConstants.DEVMODE_ENABLED)
				&& (developmentMode != QlueConstants.DEVMODE_ONDEMAND))
		{
			throw new RuntimeException("Qlue: Invalid development mode setting: " + developmentMode);
		}

		this.developmentMode = developmentMode;
	}

	public void writeDevelopmentInformation(PrintWriter out) {
		out.println(" Session secret (unmasked): " + sessionSecret.getUnmaskedToken());
		out.println(" Development mode: " + developmentMode);
	}

	public Locale getLocale() {
		return locale;
	}
	
	public BearerToken getSessionSecret() {
		return sessionSecret;
	}
}
