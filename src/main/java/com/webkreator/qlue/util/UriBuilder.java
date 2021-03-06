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
package com.webkreator.qlue.util;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UriBuilder {

    private String prefix;

    private String path;

    private String fragment;

    private List<UriBuilderParam> params = new ArrayList<UriBuilderParam>();

    private Pattern uriPattern = Pattern.compile("^(https?://[^/]+)(/.*)?$");

    /**
     * Instances of this class represent individual URI parameters (key-value
     * pairs).
     */
    class UriBuilderParam {

        String name;

        String value;

        UriBuilderParam(String name) {
            this.name = name;
        }

        UriBuilderParam(String name, String value) {
            this.name = name;
            this.value = value;
        }
    }

    /**
     * Create new instance of the URI builder, starting with the given base URI
     * (which may contain parameters).
     *
     * @param uri
     */
    public UriBuilder(String uri) {
        setUri(uri);
    }

    /**
     * Add parameter to URI.
     *
     * @param name
     * @param value
     */
    public void addParam(String name, int value) {
        params.add(new UriBuilderParam(name, Integer.toString(value)));
    }

    public void addParam(String name) {
        params.add(new UriBuilderParam(name));
    }

    /**
     * Add parameter to URI.
     *
     * @param name
     * @param value
     */
    public void addParam(String name, Integer value) {
        if (value == null) {
            params.add(new UriBuilderParam(name, ""));
        } else {
            params.add(new UriBuilderParam(name, value.toString()));
        }
    }

    /**
     * Add parameter to URI.
     *
     * @param name
     * @param value
     */
    public void addParam(String name, String value) {
        params.add(new UriBuilderParam(name, value));
    }

    /**
     * Clear all parameters.
     */
    public void clearParams() {
        params.clear();
    }

    /**
     * Initialize builder using the given URI.
     *
     * @param uri
     */
    protected void setUri(String uri) {
        // Check if the URI is absolute, because
        // we only need to work with the path part,
        // not the protocol or the domain name
        Matcher m = uriPattern.matcher(uri);
        if (m.matches()) {
            prefix = m.group(1);
            uri = m.group(2);
        }

        // Look for parameters
        if (uri != null) {
            int j = uri.indexOf('#');
            if (j != -1) {
                fragment = uri.substring(j + 1);
                uri = uri.substring(0, j);
            }

            int i = uri.indexOf('?');
            if (i == -1) {
                // No parameters, just store the normalized path
                this.path = WebUtil.normaliseUri(uri);
            } else {
                // Extract parameters into individual objects
                this.path = WebUtil.normaliseUri(uri.substring(0, i));
                String qs = uri.substring(i + 1);
                String[] pairs = qs.split("&");

                try {
                    for (String p : pairs) {
                        i = p.indexOf('=');
                        if (i == -1) {
                            //addParam(URLDecoder.decode(p, "UTF-8"), "");
                            addParam(URLDecoder.decode(p, "UTF-8"));
                        } else {
                            addParam(URLDecoder.decode(p.substring(0, i),
                                    "UTF-8"), URLDecoder.decode(
                                    p.substring(i + 1), "UTF-8"));
                        }
                    }
                } catch (UnsupportedEncodingException une) {
                    // Should never happen
                }
            }
        }
    }

    /**
     * Construct URI as string.
     *
     * @return
     */
    public String getUri() {
        try {
            StringBuilder sb = new StringBuilder();

            // Start with the prefix (protocol, domain name)
            if (prefix != null) {
                sb.append(prefix);
            }

            // Append base URI
            if (path != null) {
                sb.append(path);
            }

            // Parameters
            if (params.size() != 0) {
                sb.append("?");

                // Iterate through the list of parameters and
                // add them to the URI, properly transforming them
                // in the process.
                for (int i = 0, n = params.size(); i < n; i++) {
                    if (i != 0) {
                        sb.append('&');
                    }

                    UriBuilderParam param = params.get(i);
                    sb.append(URLEncoder.encode(param.name, "UTF-8"));
                    if (param.value != null) {
                        sb.append('=');
                        sb.append(URLEncoder.encode(param.value, "UTF-8"));
                    }
                }
            }

            if (fragment != null) {
                sb.append('#');
                sb.append(URLEncoder.encode(fragment, "UTF-8"));
            }

            String uriBeforeEncoding = sb.toString();
            sb = new StringBuilder();
            byte[] bytes = uriBeforeEncoding.getBytes("UTF-8");
            for (byte b : bytes) {
                int i = b & 0xff;
                if (i <= 0x7f) {
                    sb.appendCodePoint(i);
                } else {
                    sb.append('%');
                    HtmlEncoder.hex(i, sb);
                }
            }

            return sb.toString();
        } catch (UnsupportedEncodingException uee) {
            // Should never happen, as we know that UTF-8 is supported
            throw new RuntimeException(uee);
        }
    }
}
