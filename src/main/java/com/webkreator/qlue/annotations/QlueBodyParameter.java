package com.webkreator.qlue.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface QlueBodyParameter {

    public static final String IDENTITY = "identity";

    public static final String JSON = "json";

    public static final String NOT_SET = "";

    boolean mandatory() default true;

    boolean nonempty() default true;

    String format() default IDENTITY;

    /* This field can contain an optional regular expression pattern to
       apply against the MIME type specified in the C-T header.

       If the specified format already enforces an appropriate MIME type, then
       it's not necessary to use this field. For example, the "json" format
       will enforce "application/json". However, if this field is used,
       it will override whatever MIME type checking is built-in.

       You should always use this parameter with the "identity" format, because
       it doesn't enforce any MIME types by default.
     */
    String mimeType() default NOT_SET;
}
