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
package com.webkreator.canoe;

import org.apache.velocity.app.event.ReferenceInsertionEventHandler;

/**
 * This class is a bridge between Canoe and Velocity.
 */
public class CanoeReferenceInsertionHandler implements
		ReferenceInsertionEventHandler {

	public static final String SAFE_REFERENCE_PREFIX = "$_x.";

	protected Canoe qlueWriter;

	public CanoeReferenceInsertionHandler(Canoe qlueWriter) {
		this.qlueWriter = qlueWriter;
	}

	/**
	 * Encodes text for output. 
	 */
	@Override
	public Object referenceInsert(String arg0, Object arg1) {
		// We ignore references that start with the prefix
		// we consider to be safe. This allows developers to
		// bypass the automatic encoding mechanism and prepare
		// output themselves.
		if (arg0.startsWith(SAFE_REFERENCE_PREFIX)) {
			return arg1;
		}

		// Give up if there's nothing to output
		if (arg1 == null) {
			return null;
		}
		
		// Now encode the text using the correct encoder
		return Canoe.encode(arg1.toString(), qlueWriter.currentContext());
	}
}
