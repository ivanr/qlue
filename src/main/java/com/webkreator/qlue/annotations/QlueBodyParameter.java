package com.webkreator.qlue.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface QlueBodyParameter {

    public static final String FORMAT_IDENTITY = "identity";

    public static final String FORMAT_JSON = "json";

    boolean mandatory() default true;

    boolean nonempty() default true;

    String format() default FORMAT_IDENTITY;
}
