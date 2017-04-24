package com.webkreator.qlue.util;

import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class VariableExpander {
	
	private static Pattern propertyPattern = Pattern.compile("([^$]*)\\$\\{([^}]*)\\}(.+)?");
	
	public static String expand(String input, Properties properties) {
		Set<String> expandedNames = new HashSet<String>();
		return expand(input, properties, expandedNames);
	}
	
	private static String expand(String input, Properties properties, Set<String> expandedNames) {
		if (input == null) {
			return null;
		}
		
		StringBuilder sb = new StringBuilder();
		String haystack = input;
		Matcher m = propertyPattern.matcher(haystack);
		while ((m != null) && (m.find())) {					
			sb.append(m.group(1));					
			
			String propertyName = m.group(2);
			
			if (expandedNames.contains(propertyName)) {
				throw new RuntimeException("Variable name recursion detected during expansion: " + propertyName);
			}
			
			if (properties.getProperty(propertyName) != null) {
				Set<String> nextExpandedNames = new HashSet<String>(expandedNames);
				nextExpandedNames.add(propertyName);
				sb.append(VariableExpander.expand(properties.getProperty(propertyName), properties, nextExpandedNames));
			}
			
			haystack = m.group(3);					
			
			if (haystack != null) {
				m = propertyPattern.matcher(haystack);
			} else {
				m = null;
			}
		}
		
		if (haystack != null) {
			sb.append(haystack);
		}
		
		return sb.toString();
	}
}
