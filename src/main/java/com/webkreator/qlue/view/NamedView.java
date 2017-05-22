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

import com.webkreator.qlue.Page;
import com.webkreator.qlue.TransactionContext;

/**
 * This class is used as a placeholder. A page that wishes to use some view
 * other than the default one will return an instance of this class. The name
 * will be passed to the view resolution class, which will find a real view to
 * respond with.
 */
public class NamedView implements View {

	private String viewName;

	public NamedView(String viewName) {
		this.viewName = viewName;
	}

	public String getViewName() {
		return viewName;
	}

	@Override
	public void render(TransactionContext tx, Page page) {
		throw new RuntimeException("This method shouldn't have been invoked.");
	}
}
