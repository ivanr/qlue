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
package com.webkreator.qlue.exceptions;

/**
 * Thrown when we fail to validate a parameter.
 */
public class ValidationException extends QlueException {

	private String param;

	private static final long serialVersionUID = 1L;

	/**
	 * Use this constructor if validation errors have already been added
	 * to the page and you just wish to interrupt page processing.
	 */
	public ValidationException() {
		super();
	}

	/**
	 * Use this constructor if the page has encountered a validation
	 * error and you wish to interrupt processing immediately. The message
	 * will be added to the page errors.
	 *
	 * @param message
	 */
	public ValidationException(String message) {
		super(message);
	}

	/**
	 * Use this constructor if the page has encountered a validation
	 * error and you wish to interrupt processing immediately. The
	 * message will be added to the page errors against the specified
	 * parameter.
	 *
	 * @param param
	 * @param message
	 */
	public ValidationException(String param, String message) {
		super(message);
		this.param = param;
	}

	public String getParam() {
		return param;
	}
}
