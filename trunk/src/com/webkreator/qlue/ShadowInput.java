package com.webkreator.qlue;

import java.util.HashMap;

public class ShadowInput {
	
	private HashMap<String, String> params = new HashMap<String, String>();

	public String get(String name) {
		return params.get(name);
	}

	public void set(String name, String value) {
		params.put(name, value);
	}
}
