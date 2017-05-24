package com.webkreator.qlue.view.closure;

import com.google.template.soy.tofu.SoyTofu;
import com.webkreator.qlue.Page;
import com.webkreator.qlue.TransactionContext;
import com.webkreator.qlue.view.View;

public class SoyView implements View {

    private SoyTofu.Renderer renderer;

    public SoyView(SoyTofu.Renderer renderer) {
        this.renderer = renderer;
    }

    @Override
    public void render(TransactionContext tx, Page page) throws Exception {
        renderer.render(tx.getResponse().getWriter());
    }
}
