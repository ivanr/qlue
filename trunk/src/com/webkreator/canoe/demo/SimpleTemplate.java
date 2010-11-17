package com.webkreator.canoe.demo;

import java.io.PrintWriter;
import java.text.CharacterIterator;
import java.text.StringCharacterIterator;

import javax.servlet.http.HttpServletRequest;

import com.webkreator.canoe.Canoe;

public class SimpleTemplate {

	public SimpleTemplate() {
	}

	public void process(String template, PrintWriter out, HttpServletRequest req)
			throws Exception {
		Canoe canoe = new Canoe(out);

		boolean seenMonkey = false;
		StringCharacterIterator it = new StringCharacterIterator(template);
		for (char c = it.first(); c != CharacterIterator.DONE; c = it.next()) {
			if (seenMonkey) {
				seenMonkey = false;
				
				if (c == 'a') {					
					canoe.writeEncoded(req.getParameter("a"));
				} else if (c == 'b') {
					canoe.writeEncoded(req.getParameter("b"));
				} else if (c == 'c') {
					canoe.writeEncoded(req.getParameter("c"));
				} else {
					canoe.write('@');
					canoe.write(c);
				}							
			} else {
				if (c == '@') {
					seenMonkey = true;
				} else {
					canoe.write(c);
				}
			}
		}
	}
}
