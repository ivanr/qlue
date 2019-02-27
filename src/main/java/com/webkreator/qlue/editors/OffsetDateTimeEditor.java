package com.webkreator.qlue.editors;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

public class OffsetDateTimeEditor implements PropertyEditor {

    @Override
    public Class getEditorClass() {
        return OffsetDateTime.class;
    }

    @Override
    public Object fromText(Field field, String text, Object currentValue) {
        if (text == null) {
            return (OffsetDateTime) currentValue;
        }

        if (text.length() == 0) {
            return null;
        }

        try {
            return OffsetDateTime.parse(text);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("qp.validation.date.invalid");
        }
    }

    @Override
    public String toText(Object datetime) {
        return ((OffsetDateTime) datetime).toInstant().atZone(ZoneOffset.UTC).toString();
    }
}
