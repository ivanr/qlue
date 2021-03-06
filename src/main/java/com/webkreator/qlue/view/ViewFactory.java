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
package com.webkreator.qlue.view;

import com.webkreator.qlue.QlueApplication;

/**
 * View factories are used to create a layer of abstraction between the
 * controller and view parts. Views are referred to by name, and its the job of
 * the factory to locate the actual view that will construct output.
 */
public interface ViewFactory {

	void init(QlueApplication qlueApp) throws Exception;

	View constructView(String viewName) throws Exception;
}
