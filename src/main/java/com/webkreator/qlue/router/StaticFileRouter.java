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

import com.webkreator.qlue.TransactionContext;
import com.webkreator.qlue.exceptions.QlueSecurityException;
import com.webkreator.qlue.view.DownloadView;
import com.webkreator.qlue.view.StatusCodeView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * Routes transaction to a static file.
 */
public class StaticFileRouter implements Router {

    private Logger log = LoggerFactory.getLogger(StaticFileRouter.class);

    protected RouteManager manager;

    private String root;

    public StaticFileRouter(RouteManager manager, String root) {
        this.manager = manager;
        this.root = root;
    }

    @Override
    public Object route(TransactionContext context, String pathSuffix) {
        if (pathSuffix.contains("/../")) {
            throw new QlueSecurityException("StaticFileRouter: Invalid path: " + pathSuffix);
        }

        if (pathSuffix.toLowerCase().contains("web-inf")) {
            throw new QlueSecurityException("StaticFileRouter: Invalid path: " + pathSuffix);
        }

        File file = new File(root, pathSuffix);

        if (log.isDebugEnabled()) {
            log.debug("StaticFileRouter: Trying file: " + file);
        }

        if (!file.exists()) {
            return null;
        }

        if (!file.isDirectory()) {
            return new DownloadView(file);
        }

        // The request is for a directory; ask the manager for
        // the default file and attempt to serve that.

        // If there's no terminating slash in directory access, issue a redirection.
        if (manager.isRedirectFolderWithoutTrailingSlash() && !context.getRequestUri().endsWith("/")) {
            return RedirectionRouter.newAddTrailingSlash(context, 307).route(context, pathSuffix);
        }

        File defaultFile = new File(file, manager.getIndexWithSuffix());
        if (defaultFile.exists()) {
            return new DownloadView(defaultFile);
        }

        // This router delivers static files so we'll also allow
        // "index.html" as the default file, which is potentially
        // different from what is configured in the manager for
        // pages, etc.
        defaultFile = new File(file, "index.html");
        if (defaultFile.exists()) {
            return new DownloadView(defaultFile);
        } else {
            return new StatusCodeView(403);
        }
    }
}
