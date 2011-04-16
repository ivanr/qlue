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
package com.webkreator.qlue.util;

import java.lang.reflect.Field;

/**
 * Property editors convert text into objects.
 */
public interface PropertyEditor {

	/**
	 * Returns the class that this editor deals with.
	 * 
	 * @return
	 */
	@SuppressWarnings("rawtypes")
	public Class getEditorClass();
	
	/**
	 * Creates object out of its textual representation.
	 * 
	 * @param field
	 * @param text
	 * @return
	 */
	public Object fromText(Field field, String text);
	
	// TODO Standardise on what exception is thrown if
	//      the conversion is not possible. At the moment
	//      BooleanEditor throws InvalidParameterException, whereas
	//      we leave Integer to throw NumberFormatException.
}
