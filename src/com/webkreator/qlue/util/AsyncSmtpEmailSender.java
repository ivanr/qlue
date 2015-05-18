package com.webkreator.qlue.util;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.mail.Email;

import java.util.LinkedList;
import java.util.Queue;

public class AsyncSmtpEmailSender extends SmtpEmailSender implements Runnable {

    protected Log log = LogFactory.getLog(AsyncSmtpEmailSender.class);

    private int counter = 1;

    private Queue<Email> queue = new LinkedList<Email>();

    private static final int BACKOFF_MILLISECONDS = 5000;

    private static final int QUEUE_LIMIT = 1000;

    private synchronized Email getEmail() {
        Email email = null;

        while (true) {
            email = queue.poll();
            if (email != null) {
                return email;
            }

            try {
                wait();
            } catch (InterruptedException e) {
                // Do nothing.
            }
        }
    }

    private synchronized String queueEmail(Email email) {
        if (queue.size() >= QUEUE_LIMIT) {
            throw new RuntimeException("Unable to send email; queue full: " + queue.size());
        }

        queue.add(email);

        notify();

        return "Queued " + counter++;
    }

    @Override
    public String send(Email email) throws Exception {
        prepareEmail(email);
        return queueEmail(email);
    }

    @Override
    public void run() {
        while (true) {
            Email email = getEmail();

            try {
                String id = email.send();
                log.info("Email sent: " + email.getToAddresses() + " " + id);
            } catch (Throwable t) {
                // Failed to send email. Sleep for a while,
                // then queue the email again.
                log.error("Failed to send email", t);

                try {
                    Thread.currentThread().sleep(BACKOFF_MILLISECONDS);
                } catch (InterruptedException e) {
                    // Do nothing.
                }

                queueEmail(email);
            }
        }
    }
}
