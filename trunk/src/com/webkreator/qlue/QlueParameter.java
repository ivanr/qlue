/* 
 * Qlue Web Application Framework
 * Copyright 2009,2010 Ivan Ristic <ivanr@webkreator.com>
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
package com.webkreator.qlue;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface QlueParameter {
	/**
	 * Is this parameter mandatory?
	 * @return
	 */
	boolean mandatory() default true;
	
	// XXX
	String fieldMissingMessage() default "";
	
	/**
	 * Regular expression pattern to use when
	 * validating parameter value. 
	 * @return
	 */
	String pattern() default "";
	
	/**
	 * Maximum value for integer parameters.
	 * @return
	 */
	int maxSize() default -1;
	
	/**
	 * A comma-separated list of transformation
	 * functions to apply before validation.
	 * @return
	 */
	String tfn() default "";
	
	/**
	 * XXX What is this used for?
	 * @return
	 */
	boolean ignoreInvalid() default false;
	
	/**
	 * In order for a parameter to be updated, its
	 * state must match that of the page in which they
	 * are used.
	 * @return
	 */
	String state() default Page.STATE_NEW;
}
