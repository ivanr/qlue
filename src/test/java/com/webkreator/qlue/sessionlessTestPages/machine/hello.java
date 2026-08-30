package com.webkreator.qlue.sessionlessTestPages.machine;

import com.webkreator.qlue.Page;
import com.webkreator.qlue.view.StringView;
import com.webkreator.qlue.view.View;

/**
 * Fixture for {@link com.webkreator.qlue.SessionlessTest}'s Tomcat integration cases.
 *
 * <p>Reachable at {@code /machine/hello}, under the prefix the test filter marks with
 * {@code QLUE_SESSIONLESS_REQUEST}. A request here must never touch the {@code HttpSession},
 * whether or not it carries a {@code JSESSIONID} cookie captured elsewhere.
 */
public class hello extends Page {

    public static final String BODY = "sessionless-test-machine-hello";

    @Override
    public View onGet() throws Exception {
        return new StringView(BODY, "text/plain; charset=utf-8");
    }
}
