package com.webkreator.qlue.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface QlueMapping {

    String suffix() default "inheritAppSuffix";
}
