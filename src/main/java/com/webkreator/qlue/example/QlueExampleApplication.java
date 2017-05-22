/* 
 * Qlue Web Application Framework
 * Copyright 2009-2012 Ivan Ristic <ivanr@webkreator.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.webkreator.qlue.example;

import com.webkreator.qlue.Page;
import com.webkreator.qlue.QlueApplication;
import com.webkreator.qlue.example.pages.$BackgroundPage;
import com.webkreator.qlue.view.velocity.VelocityView;
import com.webkreator.qlue.view.velocity.VelocityViewFactory;

import javax.servlet.http.HttpServlet;
import java.io.StringWriter;

public class QlueExampleApplication extends QlueApplication {

    @Override
    public void init(HttpServlet servlet) throws Exception {
        super.init(servlet);
        testBackgroundVelocity();
    }

    private void testBackgroundVelocity() throws Exception {
        VelocityViewFactory viewFactory = (VelocityViewFactory) getViewFactory();
        VelocityView view = (VelocityView) viewFactory.constructView("com/webkreator/qlue/example/pages/$BackgroundPage.vm");
        Page page = new $BackgroundPage(this);
        StringWriter writer = new StringWriter();
        viewFactory.render(page, view, writer);
        System.err.println(writer.toString());
    }
}
