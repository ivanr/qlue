package com.webkreator.qlue.exceptions;

/**
 * Thrown on authentication failure, essentially when we want to respond with 401 HTTP status code.
 */
public class UnauthorizedException extends QlueException {

    public UnauthorizedException() {
        super();
    }

    public UnauthorizedException(String message) {
        super(message);
    }
}
