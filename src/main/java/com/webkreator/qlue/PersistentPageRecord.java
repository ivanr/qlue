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
package com.webkreator.qlue;

import java.io.Serializable;

/**
 * Keeps track of a persistent page and the related metadata.
 */
public class PersistentPageRecord implements Serializable {

    private int pageId;

    private long createTime;

    private long lastActivityTime;

    private Page page;

    private String replacementUri;

    PersistentPageRecord(long time, Page page) {
        if (page == null) {
            throw new IllegalArgumentException("page");
        }

        if (page.getId() == null) {
            throw new IllegalArgumentException("page.id");
        }

        this.createTime = time;
        this.lastActivityTime = time;
        this.page = page;
        // We're keeping a copy of the page ID because the page
        // itself can disappear later on, and we'll still need its ID.
        this.pageId = page.getId();
        this.replacementUri = null;
    }

    public Page getPage() {
        return page;
    }

    public int getPageId() {
        return pageId;
    }

    public long getLastActivityTime() {
        return lastActivityTime;
    }

    public void setLastActivityTime(long time) {
        lastActivityTime = time;
    }

    public String getReplacementUri() {
        return replacementUri;
    }

    public long getCreateTime() {
        return createTime;
    }

    public void replacePage(String uri) {
        if (uri == null) {
            throw new IllegalArgumentException("replacementUri");
        }

        page = null;
        replacementUri = uri;
    }
}
