package com.webkreator.qlue.view.closure;

import com.google.template.soy.SoyFileSet;
import com.google.template.soy.tofu.SoyTofu;
import com.webkreator.qlue.QlueApplication;
import com.webkreator.qlue.view.View;
import com.webkreator.qlue.view.ViewFactory;

import java.io.File;

public class SoyViewFactory implements ViewFactory {

    private SoyTofu tofu;

    private String priorityTemplatePath;

    private String appRoot;

    private boolean devMode = false;

    @Override
    public void init(QlueApplication qlueApp) throws Exception {
        if (qlueApp.isDevelopmentMode()) {
            devMode = true;
        }

        SoyFileSet.Builder builder = SoyFileSet.builder();

        priorityTemplatePath = qlueApp.getProperty("qlue.soy.priorityTemplatePath");
        if (priorityTemplatePath != null) {
            searchDir(builder, new File(priorityTemplatePath));
        }

        appRoot = qlueApp.getApplicationRoot();
        searchDir(builder, new File(appRoot));

        tofu = builder.build().compileToTofu();
    }

    protected static void searchDir(SoyFileSet.Builder builder, File dir) {
        for (File f: dir.listFiles()) {
            if (f.isDirectory()) {
                searchDir(builder, f);
            }

            if (f.getAbsolutePath().endsWith(".soy")) {
                builder.add(f);
            }
        }
    }

    protected static String convertViewName(String viewName) {
        // Convert Qlue viewName to Soy viewName
        //   From: /com/webkreator/qlue/example_app/pages/helloWorld
        //   To:    com.webkreator.qlue.example_app.pages.helloWorld
        viewName = viewName.replace('/', '.');
        if (viewName.charAt(0) == '.') {
            viewName = viewName.substring(1);
        }

        return viewName;
    }

    @Override
    public View constructView(String viewName) throws Exception {
        SoyTofu myTofu = tofu;

        if (devMode) {
            File f = null;

            if (priorityTemplatePath != null) {
                f = new File(priorityTemplatePath, viewName + ".soy");
                if (!f.exists()) {
                    f = new File(appRoot, viewName + ".soy");
                }

                if (!f.exists()) {
                    return null;
                }
            }

            myTofu = SoyFileSet
                    .builder()
                    .add(f)
                    .build()
                    .compileToTofu();
        }

        SoyTofu.Renderer renderer = myTofu.newRenderer(convertViewName(viewName));
        if (renderer == null) {
            return null;
        }

        return new SoyView(renderer);
    }
}
