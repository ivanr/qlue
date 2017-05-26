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
package com.webkreator.qlue.view.velocity;

import com.webkreator.qlue.QlueApplication;
import com.webkreator.qlue.view.View;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.exception.ResourceNotFoundException;
import org.apache.velocity.runtime.RuntimeConstants;

import java.util.Properties;

/**
 * This variant of VelocityViewFactory expects templates to be stored on the
 * classpath.
 */
public class ClasspathVelocityViewFactory extends VelocityViewFactory {

    /**
     * Initialize factory.
     */
    @Override
    public void init(QlueApplication qlueApp) throws Exception {
        Properties properties = buildDefaultVelocityProperties(qlueApp);
        log.info("Creating VelocityEngine with properties: " + properties);
        velocityEngine = new VelocityEngine(properties);

        // There appears to be some sort of problem with Velocity where it loads
        // some classes using one classloader and some other classes using a different
        // classloader. Because of that it is unable to match a perfectly valid
        // class to an interface.
        //
        // Workaround taken from: https://github.com/whitesource/whitesource-bamboo-agent/issues/9
        //
        Thread thread = Thread.currentThread();
        ClassLoader loader = thread.getContextClassLoader();
        thread.setContextClassLoader(this.getClass().getClassLoader());
        try {
            SLF4JLogChute.setLoggingEnabled(false);
            velocityEngine.getTemplate("FORCE_CLASSES_BE_LOADED_BY_THE_SAME_CLASSLOADER");
        } catch (ResourceNotFoundException e) {
            // This is expected, so ignore.
        } finally {
            thread.setContextClassLoader(loader);
            SLF4JLogChute.setLoggingEnabled(true);
        }
    }

    @Override
    public View constructView(String viewName) throws Exception {
        if (!viewName.endsWith(suffix)) {
            viewName = viewName + suffix;
        }

        return new VelocityView(this, velocityEngine.getTemplate(viewName, inputEncoding));
    }

    @Override
    protected Properties buildDefaultVelocityProperties(QlueApplication qlueApp) {
        Properties properties = super.buildDefaultVelocityProperties(qlueApp);

        String caching = properties.getProperty(RuntimeConstants.FILE_RESOURCE_LOADER_CACHE);
        if ((caching == null) || (!Boolean.valueOf(caching))) {
            properties.setProperty("class.resource.loader.class", "com.webkreator.qlue.view.velocity.NonCachingClasspathResourceLoader");
        } else {
            properties.setProperty("class.resource.loader.class", "org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader");
        }

        return properties;
    }
}
