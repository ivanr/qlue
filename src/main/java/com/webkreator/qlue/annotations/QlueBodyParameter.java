package com.webkreator.qlue.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface QlueBodyParameter {

    boolean mandatory() default true;

    boolean nonempty() default true;

    String format() default "identity";
}
