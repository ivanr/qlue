package com.webkreator.qlue.editors;

import java.lang.reflect.Field;

public class EnumEditor implements PropertyEditor {

    @Override
    public Class<?> getEditorClass() {
        return Enum.class;
    }

    @Override
    public Enum<?> fromText(Field field, String text, Object currentValue) {
        if (text == null) {
            return (Enum<?>) currentValue;
        }

        if (!field.getType().isEnum()) {
            throw new IllegalArgumentException("Field not enum: " + field.getType());
        }

        return valueOf(field.getType(), text);
    }

    // Enum.valueOf() requires Class<T extends Enum<T>>, but we only know at runtime that the
    // reflected field type is an enum. Capturing that as a type variable here keeps the
    // unchecked cast local to this helper instead of using a raw Class<Enum> at the call site.
    @SuppressWarnings("unchecked")
    private static <T extends Enum<T>> T valueOf(Class<?> enumType, String name) {
        return Enum.valueOf((Class<T>) enumType, name);
    }

    @Override
    public String toText(Object o) {
        return o.toString();
    }
}
