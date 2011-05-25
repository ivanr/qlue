/* 
 * Qlue Web Application Framework
 * Copyright 2009,2010 Ivan Ristic <ivanr@webkreator.com>
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

import java.util.ArrayList;
import java.util.List;

import com.webkreator.qlue.PrefixPageResolver;
import com.webkreator.qlue.QlueApplication;
import com.webkreator.qlue.QlueSchedule;
import com.webkreator.qlue.UriMapping;
import com.webkreator.qlue.view.ClasspathVelocityViewFactory;

public class QlueExampleApplication extends QlueApplication {
	
	public QlueExampleApplication() {
		//super("com.webkreator.qlue.example.pages");
		
		// Custom page mapping
        PrefixPageResolver pageResolver = new PrefixPageResolver();
        List<UriMapping> mappings = new ArrayList<UriMapping>();
        mappings.add(new UriMapping("/_qlue/", "com.webkreator.qlue.pages"));

        UriMapping onlineReadingMapping = new UriMapping("/test/", null);
        onlineReadingMapping.setClassMapping("com.webkreator.qlue.example.pages.pathParamTest");
        mappings.add(onlineReadingMapping);

        // The rest of the application
        mappings.add(new UriMapping("/", "com.webkreator.qlue.example.pages"));

        pageResolver.setMappings(mappings);
        setPageResolver(pageResolver);
		
		ClasspathVelocityViewFactory viewFactory = new ClasspathVelocityViewFactory();		
		setViewFactory(viewFactory);
	}
	
	@QlueSchedule("* * * * * ")
	public void scheduleTest() {
		System.err.println("Hello World!");
	}
}
