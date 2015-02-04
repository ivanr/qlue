package com.webkreator.qlue.editors;

import java.lang.reflect.Field;
import java.security.InvalidParameterException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

import javax.xml.bind.DatatypeConverter;


public class DateEditor implements PropertyEditor {

	@SuppressWarnings("rawtypes")
	@Override
	public Class getEditorClass() {
		return Date.class;
	}

	@Override
	public Date fromText(Field field, String text, Object currentValue) {
		if (text == null) {
			return (Date)currentValue;
		}
		
		try {
			return DatatypeConverter.parseDateTime(text).getTime();
		} catch (IllegalArgumentException iae) {
			throw new InvalidParameterException("qp.validation.date.invalid");
		}		
	}

	@Override
	public String toText(Object o) {		
		DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mmZ");			
		return df.format((Date)o);
	}
}
