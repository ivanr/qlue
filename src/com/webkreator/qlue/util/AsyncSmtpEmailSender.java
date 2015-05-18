package com.webkreator.qlue.util;

import org.apache.commons.mail.Email;

import java.util.LinkedList;
import java.util.Queue;

public class AsyncSmtpEmailSender extends SmtpEmailSender implements Runnable {

    private int counter = 1;

    private Queue<Email> queue = new LinkedList<Email>();

    private static final int BACKOFF_MILLISECONDS = 5000;

    private static final int QUEUE_LIMIT = 1000;

    private synchronized Email getEmail() {
        Email email = null;

        for(;;) {
            email = queue.poll();
            if (email == null) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    // Do nothing.
                }
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
        for (;;) {
            Email email = getEmail();

            try {
                String id = email.send();
                // TODO Log sent email
            } catch(Throwable t) {
                // Failed to send email. Sleep for a while,
                // then queue the email again.

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
