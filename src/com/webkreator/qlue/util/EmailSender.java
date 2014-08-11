package com.webkreator.qlue.util;

import org.apache.commons.mail.Email;

public interface EmailSender {

    public String send(Email email) throws Exception;
}