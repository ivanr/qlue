package com.webkreator.qlue.router;

import com.webkreator.qlue.TransactionContext;
import com.webkreator.qlue.util.VariableExpander;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HeaderConfigRouter implements Router {

    private static Logger log = LoggerFactory.getLogger(HeaderConfigRouter.class);

    private static final Pattern configPattern = Pattern.compile("^([a-zA-Z0-9_-]+)(\\s+(.+))?$");

    private String name;

    private String value;

    private boolean containsPageProperties = false;

    public HeaderConfigRouter(String name, String value) {
        this.name = name;
        this.value = value;
        if ((value != null)&&(value.contains("${"))) {
            containsPageProperties = true;
        }
    }

    public static HeaderConfigRouter fromString(RouteManager manager, String text) {
        Matcher m = configPattern.matcher(text);
        if (m.matches() == false) {
            throw new RuntimeException("Qlue: Invalid @header directive: " + text);
        }

        String name = m.group(1);
        String value = null;
        if (m.groupCount() > 1) {
            value = m.group(3);
        }

        if (log.isInfoEnabled()) {
            log.debug("HTTP request routing: @header: name=" + name + "; value=" + value);
        }

        return new HeaderConfigRouter(name, value);
    }

    @Override
    public Object route(TransactionContext context, String pathSuffix) {
        if (log.isDebugEnabled()) {
            log.debug("Setting header: name: " + name + "; value:" + value);
        }

        if (containsPageProperties == false) {
            context.setResponseHeader(name, value);
        } else {
            context.setResponseHeader(name, VariableExpander.expand(value, context.getProperties()));
        }

        // Configuration routes can change the context but typically
        // return null, leaving some other route to handle the request.
        return null;
    }
}
