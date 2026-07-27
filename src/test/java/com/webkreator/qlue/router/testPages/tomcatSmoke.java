package com.webkreator.qlue.router.testPages;

import com.webkreator.qlue.Page;
import com.webkreator.qlue.view.StringView;
import com.webkreator.qlue.view.View;

/**
 * The page {@link com.webkreator.qlue.TomcatIntegrationTest} asks a real container for.
 *
 * <p>It exists because the other pages in this package are bare {@link Page} subclasses with no
 * template beside them: they are routing fixtures, asserted on by the class the router returns,
 * and never actually rendered. Serving one over HTTP would resolve a view that is not there. This
 * one carries its own body, so a 200 with the expected text is attributable to the request having
 * gone all the way through the container, the servlet, the router and a view — and to nothing else.
 */
public class tomcatSmoke extends Page {

    public static final String BODY = "qlue-on-tomcat-11";

    @Override
    public View onGet() throws Exception {
        return new StringView(BODY, "text/plain; charset=utf-8");
    }
}
