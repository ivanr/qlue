package com.webkreator.qlue.example.pages;

import com.webkreator.qlue.Page;
import com.webkreator.qlue.QlueCommandObject;
import com.webkreator.qlue.QlueParameter;
import com.webkreator.qlue.view.NullView;
import com.webkreator.qlue.view.View;

public class commandObject extends Page {

    public class CommandObject {
        @QlueParameter(mandatory = true)
        public Integer id;
    }

    @QlueCommandObject
    public CommandObject command;

    @Override
    public View onGet() throws Exception {
        context.response.getWriter().println("helloWorld: id=" + command.id);
        return new NullView();
    }
}