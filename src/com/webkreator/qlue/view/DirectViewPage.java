package com.webkreator.qlue.view;

import com.webkreator.qlue.Page;

public class DirectViewPage extends Page {

    private View view;

    public DirectViewPage(View view) {
        this.view = view;
    }

    @Override
    public View service() {
        return view;
    }
}
