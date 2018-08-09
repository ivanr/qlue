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
package com.webkreator.qlue;

/**
 * An instance of this class holds information an error message,
 * which can optionally be associated with a field name. 
 */
public class Error {

	private String param;

	private String message;

	/**
	 * Create a new error that's associated with the entire page.
     */
	public Error(String message) {
		this(null, message);
	}

	/**
	 * Create a new error, associated with the given field.
	 */
	public Error(String param, String message) {
		this.param = param;
		this.message = message;
	}
	
	/**
	 * Retrieve field name. If the returned value is null that means
	 * the error is associated with the entire page.
	 */
	public String getParam() {
		return param;
	}
	
	/**
	 * Retrieve the error message.
	 */
	public String getMessage() {
		return message;
	}
}