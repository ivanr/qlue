package com.webkreator.qlue.sessionlessTestPages;

import com.webkreator.qlue.Page;
import com.webkreator.qlue.view.StringView;
import com.webkreator.qlue.view.View;

/**
 * Fixture for {@link com.webkreator.qlue.SessionlessTest}'s Tomcat integration cases.
 *
 * <p>Reachable at {@code /hello}, outside the {@code /machine/} prefix the test filter marks
 * sessionless, so a request here goes through the ordinary session path and is expected to
 * receive a {@code JSESSIONID} cookie. Its body is a fixed string so responses can be compared
 * byte-for-byte, the same trick {@code tomcatSmoke} uses in {@code TomcatIntegrationTest}.
 */
public class hello extends Page {

    public static final String BODY = "sessionless-test-hello";

    @Override
    public View onGet() throws Exception {
        return new StringView(BODY, "text/plain; charset=utf-8");
    }
}
