package com.webkreator.qlue.view;

import com.webkreator.qlue.Page;
import com.webkreator.qlue.TransactionContext;

public class StatusCodeOnlyView implements View {

    private int statusCode;

    public StatusCodeOnlyView(int statusCode) {
        this.statusCode = statusCode;
    }

    @Override
    public void render(TransactionContext context, Page page) throws Exception {
        context.response.setStatus(statusCode);
    }
}
