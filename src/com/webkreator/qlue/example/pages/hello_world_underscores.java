package com.webkreator.qlue.example.pages;

import com.webkreator.qlue.Page;
import com.webkreator.qlue.view.NullView;
import com.webkreator.qlue.view.View;

public class hello_world_underscores extends Page {

    @Override
    public View onGet() throws Exception {
        context.response.getWriter().println("hello_world_underscores");
        return new NullView();
    }
}
