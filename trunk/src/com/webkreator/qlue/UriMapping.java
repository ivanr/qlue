package com.webkreator.qlue;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class UriMapping {

	protected Pattern pattern;

	protected String classMapping;

	protected String packageMapping;
	
	public UriMapping(String pattern, String packageMapping) {
		setPattern(pattern);
		setPackageMapping(packageMapping);
	}

	public String getPattern() {
		return pattern.pattern();
	}

	public void setPattern(String pattern) throws PatternSyntaxException {
		this.pattern = Pattern.compile(pattern + "(.*)");
	}

	public String getClassMapping() {
		return classMapping;
	}

	public void setClassMapping(String classMapping) {
		this.classMapping = classMapping;
	}

	public String getPackageMapping() {
		return packageMapping;
	}

	public void setPackageMapping(String packageMapping) {
		this.packageMapping = packageMapping;
	}
	
	public Matcher getMatcher(CharSequence input) {
		return pattern.matcher(input);
	}
	
	public String toString() {
		return pattern + " => " + packageMapping;
	}
}
