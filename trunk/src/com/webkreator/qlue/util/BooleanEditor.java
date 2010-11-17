package com.webkreator.qlue.util;

import java.lang.reflect.Field;

public class BooleanEditor implements PropertyEditor {

	@Override
	public Boolean fromText(Field field, String text) {
		if ((text.compareToIgnoreCase("on") == 0)
				|| (text.compareToIgnoreCase("true") == 0)
				|| (text.compareToIgnoreCase("yes") == 0)
				|| (text.compareToIgnoreCase("da") == 0)
				|| (text.compareToIgnoreCase("1") == 0)) {
			return Boolean.TRUE;
		}
		
		if ((text.compareToIgnoreCase("off") == 0)
				|| (text.compareToIgnoreCase("false") == 0)
				|| (text.compareToIgnoreCase("no") == 0)
				|| (text.compareToIgnoreCase("ne") == 0)
				|| (text.compareToIgnoreCase("0") == 0)) {
			return Boolean.TRUE;
		}
		
		// TODO Perhaps we should allow for conversion errors?
		return Boolean.FALSE;
	}

	@SuppressWarnings("rawtypes")
	@Override
	public Class getEditorClass() {
		return Boolean.class;
	}
}

