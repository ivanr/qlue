/* 
 * Qlue Web Application Framework
 * Copyright 2009-2012 Ivan Ristic <ivanr@webkreator.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.webkreator.qlue.annotations;

import com.webkreator.qlue.Page;
import com.webkreator.qlue.ParamSource;
import com.webkreator.qlue.QlueApplication;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Used to indicate which page fields are parameters, and should
 * be populated by the framework using the provided metadata.
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface QlueParameter {

    /**
     * Is this parameter mandatory?
     */
    boolean mandatory() default true;

    /**
     * When set, this message overrides the default
     * message that is used when a mandatory field
     * is not present.
     */
    String fieldMissingMessage() default "";

    /**
     * Regular expression pattern to use when
     * validating parameter value.
     */
    String pattern() default "";

    /**
     * Maximum value for integer parameters.
     */
    int maxSize() default -1;

    /**
     * A comma-separated list of transformation
     * functions to apply before validation.
     */
    String tfn() default "";

    /**
     * When true, validation errors in the field
     * will be ignored; the field will not be
     * populated.
     */
    boolean ignoreInvalid() default false;

    /**
     * In order for a parameter to be updated, its
     * state must match that of the page in which they
     * are used.
     */
    String state() default Page.STATE_DEFAULT;

    /**
     * Determines the permitted origins for a parameter. By
     * default, any HTTP parameter with a matching name is
     * used, irrespective of the source.
     */
    ParamSource source() default ParamSource.GET_POST;

    /**
     * Sometimes absence of parameter has a meaning. For example,
     * HTML checkbox fields are not sent when they're disabled. For
     * us it makes more sense to process such value as "off".
     */
    String valueWhenAbsent() default QlueApplication.NULL_SUBSTITUTE;
}
