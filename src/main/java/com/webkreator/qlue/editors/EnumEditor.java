package com.webkreator.qlue.editors;

import java.lang.reflect.Field;

public class EnumEditor implements PropertyEditor {

    @Override
    public Class getEditorClass() {
        return Enum.class;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Enum fromText(Field field, String text, Object currentValue) {
        if (text == null) {
            return (Enum)currentValue;
        }

        if (!field.getType().isEnum()) {
            throw new IllegalArgumentException("Field not enum: " + field.getType());
        }

        // Enum.valueOf() requires Class<T extends Enum<T>>, but we only know at runtime
        // that the reflected field type is an enum, so this cast is unavoidable.
        return Enum.valueOf((Class<Enum>)field.getType(), text);
    }

    @Override
    public String toText(Object o) {
        return o.toString();
    }
}
