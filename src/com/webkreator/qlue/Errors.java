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

import java.util.ArrayList;
import java.util.List;

/**
 * An instance of this class holds all errors that were
 * raised during the execution og one page.
 */
public class Errors {

	protected ArrayList<Error> errors = new ArrayList<Error>();

	/**
	 * Add an error message.
	 * 
	 * @param message
	 */
	public void addError(String message) {
		errors.add(new Error(null, message));
	}

	/**
	 * Add an error message associated with a field.
	 * 
	 * @param field
	 * @param message
	 */
	public void addError(String field, String message) {
		errors.add(new Error(field, message));
	}

	/**
	 * Return a list with all errors.
	 * 
	 * @return
	 */
	public List<Error> getAllErrors() {
		return errors;
	}

	/**
	 * Return all errors pertaining to the given field.
	 * 
	 * @param field
	 * @return
	 */
	public List<String> getFieldErrors(String field) {
		ArrayList<String> list = new ArrayList<String>();

		for (Error error : errors) {
			if ((error.field != null) && (field.compareTo(error.field) == 0)) {
				list.add(error.message);
			}
		}

		return list;
	}

	/**
	 * Return all non-specific errors (which are those that
	 * are not associated with a field).
	 * 
	 * @return
	 */
	public List<String> getFormErrors() {
		ArrayList<String> list = new ArrayList<String>();

		for (Error error : errors) {
			if (error.field == null) {
				list.add(error.message);
			}
		}

		return list;
	}

	/**
	 * Clear all error messages.
	 */
	public void clear() {
		errors.clear();
	}

	/**
	 * Checks if there are any errors.
	 * 
	 * @return
	 */
	public boolean hasErrors() {
		if (errors.size() != 0) {
			return true;
		}

		return false;
	}
}
