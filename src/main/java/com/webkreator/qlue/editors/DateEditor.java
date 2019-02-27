package com.webkreator.qlue.editors;

import javax.xml.bind.DatatypeConverter;
import java.lang.reflect.Field;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Converts Date objects to and from text.
 */
public class DateEditor implements PropertyEditor {

    @Override
    public Class getEditorClass() {
        return Date.class;
    }

    @Override
    public Date fromText(Field field, String text, Object currentValue) {
        if (text == null) {
            return (Date) currentValue;
        }

        if (text.length() == 0) {
            return null;
        }

        try {
            return DatatypeConverter.parseDateTime(text).getTime();
        } catch (IllegalArgumentException iae) {
            throw new IllegalArgumentException("qp.validation.date.invalid");
        }
    }

    @Override
    public String toText(Object o) {
        DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mmZ");
        return df.format((Date) o);
    }
}
