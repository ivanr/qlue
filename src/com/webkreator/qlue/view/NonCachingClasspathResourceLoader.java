package com.webkreator.qlue.view;

import org.apache.velocity.runtime.resource.Resource;
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader;

public class NonCachingClasspathResourceLoader extends ClasspathResourceLoader {

    @Override
    public boolean isSourceModified(Resource resource)
    {
        return true;
    }

    @Override
    public long getLastModified(Resource resource)
    {
        return System.currentTimeMillis() - 1000;
    }
}
