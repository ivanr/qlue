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
package com.webkreator.qlue.router;

import com.webkreator.qlue.QlueApplication;
import com.webkreator.qlue.TransactionContext;
import com.webkreator.qlue.util.VariableExpander;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Implements the default routing functionality, which accepts
 * a single routing file (routes.conf) that contains one route
 * per line.
 */
public class QlueRouteManager implements RouteManager {

    private Logger log = LoggerFactory.getLogger(QlueRouteManager.class);

    private QlueApplication app;

    private List<Route> routes = new ArrayList<>();

    private String suffix;

    private String index = "index";

    private boolean convertDashesToUnderscores = false;

    private boolean redirectFolderWithoutTrailingSlash = true;

    public QlueRouteManager(QlueApplication app) {
        this.app = app;
    }

    /**
     * Loads routes from a file.
     */
    public void load(File routesFile) throws Exception {
        routes.clear();

        // Loop through the lines in the configuration file, processing
        // each line as a single routing instruction.

        try (BufferedReader in = new BufferedReader(new FileReader(routesFile))) {
            String line;

            while ((line = in.readLine()) != null) {
                // Ignore comment lines; those that are empty or on
                // which the first non-whitespace character is #.
                line = line.trim();
                if ((line.length() == 0) || (line.charAt(0) == '#')) {
                    continue;
                }

                line = expandProperties(line);

                add(RouteFactory.create(this, line));
            }
        }

        //tuneRoutesForMethodNotFound();
    }

    /**
     * Adds a new route.
     */
    public void add(Route route) {
        if (route == null) {
            return;
        }

        routes.add(route);
    }

    /**
     * Routes transaction using previously configured routes.
     */
    public Object route(TransactionContext context) {
        Object r;

        if (log.isDebugEnabled()) {
            log.debug("QlueRouter: Asked to route: " + context.getRequestUri());
        }

        // Loop through the configured routes
        for (Route route : routes) {
            if (log.isDebugEnabled()) {
                log.debug("QlueRouter: Trying " + route.getPath());
            }

            r = route.route(context);
            if (r != null) {
                return r;
            }
        }

        return null;
    }

    /**
     * Replace variables (in the format "${variableName}") with
     * their values from the Qlue properties file.
     */
    String expandProperties(String input) {
        return VariableExpander.expand(input, app.getProperties());
    }

    public Properties getProperties() {
        return app.getProperties();
    }

    @Override
    public String getIndex() {
        return index;
    }

    @Override
    public void setIndex(String index) {
        this.index = index;
    }

    @Override
    public String getSuffix() {
        return suffix;
    }

    @Override
    public void setSuffix(String suffix) {
        this.suffix = suffix;
    }

    @Override
    public String getIndexWithSuffix() {
        return index + (suffix != null ? suffix : "");
    }

    @Override
    public boolean isConvertDashesToUnderscores() {
        return convertDashesToUnderscores;
    }

    @Override
    public void setConcertDashesToUnderscores(boolean b) {
        convertDashesToUnderscores = b;
    }

    @Override
    public boolean isRedirectFolderWithoutTrailingSlash() {
        return redirectFolderWithoutTrailingSlash;
    }

    @Override
    public void setRedirectFolderWithoutTrailingSlash(boolean b) {
        this.redirectFolderWithoutTrailingSlash = b;
    }

    @Override
    public String getPriorityTemplatePath() {
        return app.getPriorityTemplatePath();
    }

    public void tuneRoutesForMethodNotFound() {
        Route previousRoute = null;
        for (Route route : routes) {
            if (previousRoute != null) {
                if ((!route.getPath().equals(previousRoute.getPath()))
                        && (previousRoute.isSelectiveAboutMethods()))
                {
                    previousRoute.setForceMethodNotFound(true);
                }
            }

            previousRoute = route;
        }

        // Check the last route.
        if ((previousRoute != null) && (previousRoute.isSelectiveAboutMethods())) {
            previousRoute.setForceMethodNotFound(true);
        }
    }
}
