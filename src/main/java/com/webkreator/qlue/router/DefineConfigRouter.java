package com.webkreator.qlue.router;

import com.webkreator.qlue.TransactionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DefineConfigRouter implements Router {

    private static Logger log = LoggerFactory.getLogger(DefineConfigRouter.class);

    private static final Pattern configPattern = Pattern.compile("^([-a-zA-Z0-9_.]+)\\s+(.*)$");

    private DefineConfigRouter() {
    }

    public static void updateProperties(RouteManager manager, String text) {
        Matcher m = configPattern.matcher(text);
        if (m.matches() == false) {
            throw new RuntimeException("Qlue: Invalid @define directive: " + text);
        }

        String name = m.group(1);
        String value = m.group(2);

        if (log.isInfoEnabled()) {
            log.info("HTTP request routing: @define: name=" + name + "; value=" + value);
        }

        manager.getProperties().setProperty(name, value);
    }

    @Override
    public Object route(TransactionContext context, String pathSuffix) {
        throw new IllegalStateException("DefineConfigRouter should not be invoked to route requests");
    }
}