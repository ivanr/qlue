package com.webkreator.qlue.util;

import org.apache.commons.mail.Email;

public interface EmailSender {

    public void send(Email email) throws Exception;
}