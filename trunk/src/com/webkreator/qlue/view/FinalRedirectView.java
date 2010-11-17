/* 
 * Qlue Web Application Framework
 * Copyright 2009 Ivan Ristic <ivanr@webkreator.com>
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


/**
 * A ReplacementView instance is used to indicate that a persistent page
 * is about to shut down. In order to prevent the breaking of the persistent
 * URI that was used to reach it (i.e., the /pageName.html?_pid=12345678 URI),
 * the page can respond with another page or URI that will handle it. If a
 * page is used, it will be invoked to handle the broken URI. If a URI is
 * used, a redirection will be issed to it.
 */
public class FinalRedirectView extends RedirectView {
	
	public FinalRedirectView(Page page) {	
		super(page);
	}
		
	public FinalRedirectView(String toUri) {		
		super(toUri);
	}
}
