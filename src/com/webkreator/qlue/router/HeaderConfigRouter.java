package com.webkreator.qlue.router;

import com.webkreator.qlue.TransactionContext;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HeaderConfigRouter implements Router {

    private Log log = LogFactory.getLog(HeaderConfigRouter.class);

    private static final Pattern configPattern = Pattern.compile("^([a-zA-Z0-9_-]+)\\s+(.*)$");

    private String name;

    private String value;

    public HeaderConfigRouter(String name, String value) {
        this.name = name;
        this.value = value;
    }

    public static HeaderConfigRouter fromString(RouteManager manager, String text) {
        Matcher m = configPattern.matcher(text);
        if (m.matches() == false) {
            throw new RuntimeException("Qlue: Invalid header route: " + text);
        }

        return new HeaderConfigRouter(m.group(1), m.group(2));
    }

    @Override
    public Object route(TransactionContext context, String pathSuffix) {
        if (log.isDebugEnabled()) {
            log.debug("Setting header: name: " + name + "; value:" + value);
        }

        context.response.setHeader(name, value);

        // Configuration routes can change the context but typically
        // return null, leaving some other route to handle the request.
        return null;
    }
}
