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
package com.webkreator.qlue.editors;

import java.lang.reflect.Field;

/**
 * Converts Boolean objects to and from text.
 */
public class BooleanEditor implements PropertyEditor {

	@Override
	public Boolean fromText(Field field, String text, Object currentValue) {
		if (text == null) {
			return (Boolean)currentValue;
		}
		
		if ((text.compareToIgnoreCase("on") == 0)
				|| (text.compareToIgnoreCase("true") == 0)
				|| (text.compareToIgnoreCase("yes") == 0)
				|| (text.compareToIgnoreCase("da") == 0)
				|| (text.compareToIgnoreCase("1") == 0))
		{
			return Boolean.TRUE;
		}

		if ((text.compareToIgnoreCase("off") == 0)
				|| (text.compareToIgnoreCase("false") == 0)
				|| (text.compareToIgnoreCase("no") == 0)
				|| (text.compareToIgnoreCase("ne") == 0)
				|| (text.compareToIgnoreCase("0") == 0))
		{
			return Boolean.FALSE;
		}

		throw new IllegalArgumentException("Invalid boolean value: " + text);
	}

	@Override
	public Class getEditorClass() {
		return Boolean.class;
	}

	@Override
	public String toText(Object o) {
		return o.toString();
	}
}
