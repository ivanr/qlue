package com.webkreator.qlue.example.pages;

import com.webkreator.qlue.Page;
import com.webkreator.qlue.QlueParameter;
import com.webkreator.qlue.util.HtmlEncoder;
import com.webkreator.qlue.view.NullView;
import com.webkreator.qlue.view.View;

public class helloName extends Page {

    @QlueParameter
    public String name;

    @Override
    public View onGet() throws Exception {
        context.response.setContentType("text/html");
        context.response.getWriter().println("Hello " + HtmlEncoder.encodeForHTML(name) + "!");
        return new NullView();
    }
}
