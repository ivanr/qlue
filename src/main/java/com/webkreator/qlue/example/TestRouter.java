package com.webkreator.qlue.example;

import com.webkreator.qlue.TransactionContext;
import com.webkreator.qlue.router.Router;
import com.webkreator.qlue.view.RedirectView;

public class TestRouter implements Router {

	@Override
	public Object route(TransactionContext tx, String pathSuffix) {
		return new RedirectView("/");
	}
}
