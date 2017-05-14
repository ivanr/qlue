package com.webkreator.qlue.editors;

import java.lang.reflect.Field;

public class EnumEditor implements PropertyEditor {

    @Override
    public Class getEditorClass() {
        return Enum.class;
    }

    @Override
    public Enum fromText(Field field, String text, Object currentValue) {
        if (text == null) {
            return null;
        }

        if (!field.getType().isEnum()) {
            throw new IllegalArgumentException("field not enum: " + field.getType());
        }

        return Enum.valueOf((Class<Enum>)field.getType(), text);
    }

    @Override
    public String toText(Object o) {
        return ((Enum)o).toString();
    }
}
