/* 
 * Qlue Web Application Framework
 * Copyright 2009,2010 Ivan Ristic <ivanr@webkreator.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
