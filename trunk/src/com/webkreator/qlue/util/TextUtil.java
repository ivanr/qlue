package com.webkreator.qlue.util;

public class TextUtil {
	
	public static boolean isEmpty(String input) {
		if (input == null) return true;
		if (input.length() == 0) return true;
		return false;
	}
	
	public static boolean isEmptyOrWhitespace(String input) {
		if (input == null) return true;
		if (input.length() == 0) return true;
		
		// Check every character
		for(int i = 0, n = input.length(); i < n; i++) {
			char c = input.charAt(i);
			// If we find one character that is not a whitespace
			// then the string is not empty
			if (Character.isWhitespace(c) == false) {
				return false;
			}
		}
		
		// All characters are whitepace
		return true;
	}
}
