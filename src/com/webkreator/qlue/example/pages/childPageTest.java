package com.webkreator.qlue.example.pages;

import com.webkreator.qlue.QlueParameter;
import com.webkreator.qlue.view.StatusCodeView;
import com.webkreator.qlue.view.View;

public class childPageTest extends $parentPageTest {

    @QlueParameter(mandatory = true)
    public String name2;

    //@QlueParameter(mandatory = true)
    //protected String name3;

    @Override
    public View onGet() {
        if ((name == null)||(name2 == null)) {
            return new StatusCodeView(500);
        }

        return new StatusCodeView(200);
    }
}
