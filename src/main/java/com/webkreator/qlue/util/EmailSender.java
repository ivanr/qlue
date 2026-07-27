package com.webkreator.qlue.util;

import org.apache.commons.mail2.jakarta.Email;

public interface EmailSender {

    public String send(Email email) throws Exception;
}