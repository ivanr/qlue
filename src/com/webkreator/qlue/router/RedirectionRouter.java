package com.webkreator.qlue.router;

import com.webkreator.qlue.TransactionContext;
import com.webkreator.qlue.view.RedirectView;

public class RedirectionRouter implements Router {
	
	private String uri;
	
	private int status = 301;
	
	public RedirectionRouter(String uri) {
		this.uri = uri;
	}
	
	public RedirectionRouter(String uri, int status) {
		this.uri = uri;
		this.status = status;
	}

	@Override
	public Object route(TransactionContext tx, String extraPath) {
		RedirectView redirectView = new RedirectView(uri);
		redirectView.setStatus(status);
		return redirectView;
	}
}
