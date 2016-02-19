package com.webkreator.qlue.example.pages;

import com.webkreator.qlue.Page;
import com.webkreator.qlue.QlueMapping;
import com.webkreator.qlue.view.NullView;
import com.webkreator.qlue.view.View;

@QlueMapping(suffix = ".html")
public class helloWorldSuffix extends Page {

    @Override
    public View onGet() throws Exception {
        context.response.getWriter().println("helloWorldSuffix");
        return new NullView();
    }
}