package com.webkreator.qlue.util;

import org.apache.commons.mail.Email;

public class SmtpEmailSender implements EmailSender {

	private String smtpServer;

	private Integer smtpPort = 25;

	private String smtpProtocol;

	private String smtpUsername;

	private String smtpPassword;

	public String getSmtpServer() {
		return smtpServer;
	}

	public void setSmtpServer(String smtpServer) {
		this.smtpServer = smtpServer;
	}

	public Integer getSmtpPort() {
		return smtpPort;
	}

	public void setSmtpPort(Integer smtpPort) {
		this.smtpPort = smtpPort;
	}

	public String getSmtpProtocol() {
		return smtpProtocol;
	}

	public void setSmtpProtocol(String smtpProtocol) {
		this.smtpProtocol = smtpProtocol;
	}

	public String getSmtpUsername() {
		return smtpUsername;
	}

	public void setSmtpUsername(String smtpUsername) {
		if ((smtpUsername != null) && (smtpUsername.trim().length() == 0)) {
			smtpUsername = null;
		}

		this.smtpUsername = smtpUsername;
	}

	public String getSmtpPassword() {
		return smtpPassword;
	}

	public void setSmtpPassword(String smtpPassword) {
		if ((smtpPassword != null) && (smtpPassword.trim().length() == 0)) {
			smtpPassword = null;
		}

		this.smtpPassword = smtpPassword;
	}

	@Override
	public void send(Email email) throws Exception {
		email.setHostName(smtpServer);
		email.setSmtpPort(smtpPort);
		if ((smtpProtocol != null) && (smtpProtocol.compareTo("TLS") == 0)) {
			email.setTLS(true);
		}

		if (smtpUsername != null) {
			email.setAuthentication(smtpUsername, smtpPassword);
		}

		email.send();
	}
}
