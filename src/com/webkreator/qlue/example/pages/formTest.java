package com.webkreator.qlue.example.pages;

import com.webkreator.qlue.Page;
import com.webkreator.qlue.QlueParameter;
import com.webkreator.qlue.QluePersistentPage;
import com.webkreator.qlue.view.DefaultView;
import com.webkreator.qlue.view.RedirectView;
import com.webkreator.qlue.view.View;

@QluePersistentPage
public class formTest extends Page {

    @QlueParameter(state = Page.STATE_INIT)
    public Integer id;

    @QlueParameter
    public String name;

    @Override
    public View init() throws Exception {
        System.out.println("# init");

        name = "Smith";

        System.out.println("# init: id=" + id);
        System.out.println("# init: name=" + name);

        return null;
    }

    @Override
    public View onGet() throws Exception {
        System.out.println("# get: id=" + id);
        System.out.println("# get: command: name=" + name);
        System.out.println("# get:  shadow: name=" + getShadowInput().get("name"));

        return new DefaultView();
    }

    @Override
    public View onPost() throws Exception {
        System.out.println("# post: id=" + id);
        System.out.println("# post: name=" + name);

        return new RedirectView(this);
    }
}
