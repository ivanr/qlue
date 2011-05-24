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
package com.webkreator.qlue.example.pages;

import com.webkreator.qlue.Page;
import com.webkreator.qlue.QlueParameter;
import com.webkreator.qlue.QluePersistentPage;
import com.webkreator.qlue.view.DefaultView;
import com.webkreator.qlue.view.FinalRedirectView;
import com.webkreator.qlue.view.RedirectView;
import com.webkreator.qlue.view.View;

@QluePersistentPage
public class formExample extends Page {
	
	@QlueParameter(mandatory = true, state = Page.STATE_SUBMIT)
	public String username;
	
	@QlueParameter(state = Page.STATE_SUBMIT)
	public Integer[] something;
	
	@Override
	public View onGet() {		
		// If we are done, display the final
		// message instead of the form
		// if (isFinished()) {			
		//	return new NamedView("formExample_finished");
		//}
		
		// Display the form
		return new DefaultView();
	}
	
	@Override
	public View onPost() {		
		// Check for Qlue validation errors; if there
		// are any we just redirect back to ourselves, which
		// will show the form with the errors.
		if (hasErrors()) {
			return new RedirectView(this);
		}			
		
		// A real form would perform some further validation
		// here then do some work before responding with a
		// final redirection. 
		// return new FinalRedirectView(this);
		return new FinalRedirectView("/formExample_finished.html");
	}
}
