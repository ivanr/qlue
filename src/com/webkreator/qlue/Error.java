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

	private String field;

	private String message;

	/**
	 * Create new error, given field name and message.
	 */
	public Error(String field, String message) {
		this.field = field;
		this.message = message;
	}
	
	/**
	 * Retrieve field name.
	 */
	public String getField() {
		return field;
	}
	
	/**
	 * Retrieve message.
	 */
	public String getMessage() {
		return message;
	}
}