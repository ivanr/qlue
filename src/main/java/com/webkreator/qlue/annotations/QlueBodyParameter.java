package com.webkreator.qlue.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface QlueBodyParameter {

    String format() default "identity";

    String mimeType() default "auto";
}
