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
package com.webkreator.qlue.view;

import com.webkreator.qlue.Page;
import com.webkreator.qlue.TransactionContext;

import java.io.File;
import java.util.StringTokenizer;

/**
 * In the Qlue framework, a View instance is responsible for rendering output.
 * We do not want to tie ourselves to a particular technology, working with this
 * interface instead.
 */
public interface View {

    String CONTENT_TYPE_TEXT_HTML_UTF8 = "text/html; charset=UTF-8";

    int STATUS_200_OK = 200;

    int STATUS_201_CREATED = 201;

    int STATUS_204_NO_CONTENT = 204;

    int STATUS_205_RESET_CONTENT = 205;

    int STATUS_400_BAD_REQUEST = 400;

    int STATUS_401_UNAUTHORIZED = 401;

    int STATUS_403_FORBIDDEN = 403;

    int STATUS_404_NOT_FOUND = 404;

    int STATUS_405_METHOD_NOT_ALLOWED = 405;

    int STATUS_429_TOO_MANY_REQUESTS = 429;

    int STATUS_500_INTERNAL_SERVER_ERROR = 500;

    /**
     * This method is invoked by the framework to render page output.
     *
     * @param page
     * @throws Exception
     */
    void render(TransactionContext tx, Page page) throws Exception;

    static String getViewName(Page page, String viewName) {
        StringBuilder sb = new StringBuilder();

        StringTokenizer st = new StringTokenizer(page.getClass().getName(), ".");
        String lastToken = null;

        while (st.hasMoreTokens()) {
            if (lastToken != null) {
                sb.append('/');
                sb.append(lastToken);
            }

            lastToken = st.nextToken();
        }

        sb.append('/');

        if (viewName == null) {
            sb.append(new File(page.getViewName()).getName());
        } else {
            sb.append(new File(viewName).getName());
        }

        return sb.toString();
    }
}
