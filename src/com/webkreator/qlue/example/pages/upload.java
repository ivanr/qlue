package com.webkreator.qlue.example.pages;

import com.webkreator.qlue.Page;
import com.webkreator.qlue.QlueFile;
import com.webkreator.qlue.QlueParameter;
import com.webkreator.qlue.view.DefaultView;
import com.webkreator.qlue.view.View;

public class upload extends Page {

    @QlueParameter(state = Page.STATE_POST)
    QlueFile file;

    @Override
    public View onGet() throws Exception {
        return new DefaultView();
    }

    public View onPost() throws Exception {
        return new DefaultView();
    }
}
