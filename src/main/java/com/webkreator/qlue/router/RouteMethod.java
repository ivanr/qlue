package com.webkreator.qlue.router;

import com.webkreator.qlue.TransactionContext;

public enum RouteMethod {
    DELETE,
    GET,
    PATCH,
    POST,
    PUT,
    $OTHER;

    public static RouteMethod fromTransaction(TransactionContext tx) {
        try {
            String httpMethod = tx.getRequest().getMethod();
            if (httpMethod.equals("HEAD")) {
                httpMethod = "GET";
            }
            
            RouteMethod method = RouteMethod.valueOf(httpMethod);
            if (method == $OTHER) {
                throw new IllegalArgumentException("Internal route method names now allowed: "
                        + tx.getRequest().getMethod());
            }
            return method;
        } catch(Exception e) {
            return $OTHER;
        }
    }
}
