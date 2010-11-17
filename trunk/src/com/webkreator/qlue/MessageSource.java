package com.webkreator.qlue;

import java.util.Formatter;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.PropertyResourceBundle;

public class MessageSource {

	protected PropertyResourceBundle resourceBundle;

	protected Locale locale;

	protected MessageSource(PropertyResourceBundle resourceBundle, Locale locale) {
		this.resourceBundle = resourceBundle;
		this.locale = locale;
	}

	public String get(String code) {
		try {
			return resourceBundle.getString(code);
		} catch (MissingResourceException mre) {
			return code;
		}
	}

	public String get(String code, Object... params) {					
		StringBuffer sb = new StringBuffer();
		Formatter formatter = new Formatter(sb, locale);
		formatter.format(get(code), params);
		return sb.toString();
	}
}
