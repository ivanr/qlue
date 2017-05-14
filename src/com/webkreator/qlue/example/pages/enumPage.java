package com.webkreator.qlue.example.pages;

import com.webkreator.qlue.Page;
import com.webkreator.qlue.QlueParameter;
import com.webkreator.qlue.view.NullView;
import com.webkreator.qlue.view.View;

public class enumPage extends Page {

    @QlueParameter(mandatory = false)
    public Selection selection = Selection.standard;

    private enum Selection {
        standard,
        extra;
    }

    @Override
    public View onGet() throws Exception {
        context.response.getWriter().println("Selection: " + selection);
        return new NullView();
    }
}
