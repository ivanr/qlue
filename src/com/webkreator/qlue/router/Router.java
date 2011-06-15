package com.webkreator.qlue.router;

import com.webkreator.qlue.TransactionContext;

public interface Router {

	public Object route(TransactionContext tx, String extraPath);
}
