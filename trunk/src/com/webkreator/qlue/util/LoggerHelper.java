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

import org.apache.log4j.Logger;

/**
 * This class contains one helper method to assist with logging. 
 */
public class LoggerHelper {

	/**
	 * Returns the calling class's logger.
	 * 
	 * @return
	 */
	public static Logger getLogger() {
		final Throwable t = new Throwable();
		t.fillInStackTrace();
		return Logger.getLogger(t.getStackTrace()[1].getClassName());
	}
}
