package com.webkreator.qlue.view;

import com.webkreator.qlue.Page;
import com.webkreator.qlue.TransactionContext;

public class ClasspathView implements View {

    private String viewName;

    public ClasspathView(String viewName) {
        this.viewName = viewName;
    }

    public String getViewName() {
        return viewName;
    }

    @Override
    public void render(TransactionContext tx, Page page) {
        throw new RuntimeException("This method shouldn't have been invoked.");
    }
}
