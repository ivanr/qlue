package com.webkreator.qlue.editors;

import java.lang.reflect.Field;
import java.security.InvalidParameterException;

/**
 * Handle conversion to and from Integer fields.
 */
public class LongEditor implements PropertyEditor {

    @Override
    public Long fromText(Field field, String text, Object currentValue) {
        if (text == null) {
            return (Long)currentValue;
        }

        try {
            return Long.valueOf(text);
        } catch (NumberFormatException nfe) {
            throw new InvalidParameterException(
                    "LongEditor: Invalid long value: " + text);
        }
    }

    @SuppressWarnings("rawtypes")
    @Override
    public Class getEditorClass() {
        return Long.class;
    }

    @Override
    public String toText(Object o) {
        return ((Long) o).toString();
    }
}