package com.webkreator.qlue.example.pages;

import com.webkreator.qlue.Page;
import com.webkreator.qlue.QlueParameter;
import com.webkreator.qlue.util.HtmlEncoder;
import com.webkreator.qlue.view.NullView;
import com.webkreator.qlue.view.View;

public class enumPage extends Page {

    @QlueParameter(mandatory = false)
    public Selection selection = Selection.standard;

    @QlueParameter(mandatory = false)
    public String text = "test";

    private enum Selection {
        standard,
        extra;
    }

    @Override
    public View onGet() throws Exception {
        context.response.getWriter().println("Selection: " + selection + "<br>");
        context.response.getWriter().println("Text: " + HtmlEncoder.html(text) + "<br>");
        return new NullView();
    }
}
