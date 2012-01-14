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

import java.util.HashMap;

public class ShadowInput {
	
	private HashMap<String, Object> params = new HashMap<String, Object>();

	public String get(String name) {
		return (String)params.get(name);
	}
	
	public String[] getArray(String name) {
		return (String[])params.get(name);
	}

	public void set(String name, String value) {
		params.put(name, value);
	}
	
	public void set(String name, String[] values) {
		params.put(name, values);
	}
}
