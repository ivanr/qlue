package com.webkreator.qlue;

public interface Validator {
	
	@SuppressWarnings("rawtypes")
	public boolean supports(Class klass);
	
	public void validate(Object obj, Errors e);
}
