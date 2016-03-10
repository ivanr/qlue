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

import java.util.Formatter;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.PropertyResourceBundle;

/**
 * This class translates message codes into actual messages,
 * which can be sent to the user.
 */
public class MessageSource {

	protected PropertyResourceBundle resourceBundle;

	protected Locale locale;

	/**
	 * Create a message source instance associated with the given
	 * resource bundle and locale.
	 */
	protected MessageSource(PropertyResourceBundle resourceBundle, Locale locale) {
		this.resourceBundle = resourceBundle;
		this.locale = locale;
	}

	/**
	 * Resolve a non-parameterized message code.
	 */
	public String get(String code) {
		try {
			return resourceBundle.getString(code);
		} catch (MissingResourceException mre) {
			return code;
		}
	}

	/**
	 * Resolve a message code using the given parameters.k
	 */
	public String get(String code, Object... params) {					
		StringBuffer sb = new StringBuffer();
		Formatter formatter = new Formatter(sb, locale);
		formatter.format(get(code), params);
		return sb.toString();
	}
}
