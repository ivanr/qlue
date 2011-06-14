package com.webkreator.qlue;

public interface Router {

	public Object route(TransactionContext tx, String extraPath);
}
