package com.webkreator.qlue;

import java.io.PrintWriter;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Random;

import org.apache.commons.codec.binary.Hex;


public class QlueSession {
	
	private QlueApplication qlueApp;
	
	private Locale locale = Locale.ENGLISH;
	
	private MessageSource messageSource;

	private String nonce;

	private Integer developmentMode = null;

	public QlueSession(QlueApplication qlueApp) {
		this.qlueApp = qlueApp;
		
		generateNonce();
	}

	/**
	 * Get the nonce for this session.
	 * 
	 * @return
	 */
	public String getNonce() {
		return nonce;
	}

	/**
	 * Generate a new nonce for this session.
	 */
	private void generateNonce() {
		Random random = new SecureRandom();

		byte[] randomBytes = new byte[16];
		random.nextBytes(randomBytes);

		nonce = new String(Hex.encodeHex(randomBytes));
	}

	public Integer getDevelopmentMode() {
		return developmentMode;
	}

	public void setDevelopmentMode(Integer developmentMode) {
		this.developmentMode = developmentMode;
	}

	public void writeDevelopmentInformation(PrintWriter out) {
		out.println(" Nonce: " + nonce);
		out.println(" Development mode: " + developmentMode);
	}

	public MessageSource getMessageSource() {
		if (messageSource == null) {
			messageSource = qlueApp.getMessageSource(locale);
		}
		
		return messageSource;
	}
}
