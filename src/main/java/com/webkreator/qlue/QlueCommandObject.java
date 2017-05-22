package com.webkreator.qlue;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface QlueCommandObject {
    String value() default "ANY";
}
