/* 
 * Qlue Web Application Framework
 * Copyright 2009-2011 Ivan Ristic <ivanr@webkreator.com>
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
package com.webkreator.qlue.example.pages;

import com.webkreator.qlue.Page;
import com.webkreator.qlue.view.DefaultView;
import com.webkreator.qlue.view.View;

public class helloWorldWithTemplate extends Page {
	
	@Override
	public View onGet() throws Exception {			
		return new DefaultView();
		
		/*
		File f = new File("c:/test.vm");
		if (f.exists()) {
			System.err.println(" file exists");
		}
		
		return new NamedView(f);
		*/
	}
}