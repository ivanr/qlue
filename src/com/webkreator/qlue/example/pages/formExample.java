package com.webkreator.qlue.example.pages;

import com.webkreator.qlue.Page;
import com.webkreator.qlue.QlueParameter;
import com.webkreator.qlue.view.DefaultView;
import com.webkreator.qlue.view.FinalRedirectView;
import com.webkreator.qlue.view.NamedView;
import com.webkreator.qlue.view.RedirectView;
import com.webkreator.qlue.view.View;

public class formExample extends Page {
	
	@QlueParameter(mandatory = true, state = Page.STATE_SUBMIT)
	public String username;
	
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
		//return new FinalRedirectView(this);
		return new FinalRedirectView("/formExample_finished.html");
	}
}
