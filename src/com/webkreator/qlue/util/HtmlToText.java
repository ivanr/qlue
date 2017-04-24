package com.webkreator.qlue.util;

import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import java.io.IOException;
import java.io.Reader;

public class HtmlToText extends HTMLEditorKit.ParserCallback {
	
	private StringBuilder sb;

	public HtmlToText() {
	}

	public void parse(Reader in) throws IOException {
		sb = new StringBuilder();
		ParserDelegator delegator = new ParserDelegator();		
		delegator.parse(in, this, Boolean.TRUE);
	}

	public void handleText(char[] text, int pos) {
		sb.append(text);
	}

	public String toString() {
		return sb.toString();
	}
}
