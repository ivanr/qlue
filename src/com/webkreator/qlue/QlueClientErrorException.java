package com.webkreator.qlue;

public class QlueClientErrorException extends QlueException {

    public QlueClientErrorException(Throwable t) {
        super(t);
    }

    public QlueClientErrorException(String m, Throwable t) {
        super(m, t);
    }
}
