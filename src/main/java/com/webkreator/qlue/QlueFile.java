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

import java.io.File;

/**
 * Represents one file uploaded via multipart/form-data encoding.
 */
public class QlueFile extends File {
	
	public static final long serialVersionUID = 1L;
		
	private String contentType;

	private String submittedFilename;

	public QlueFile(String pathname) {
		super(pathname);		
	}

	public String getContentType() {
		return contentType;
	}

	void setContentType(String contentType) {
		this.contentType = contentType;
	}

	public String getSubmittedFilename() {
		return submittedFilename;
	}

	public void setSubmittedFilename(String submittedFilename) {
		this.submittedFilename = submittedFilename;
	}

	public long getSize() {
		return length();
	}

	public long getLength() {
		return length();
	}
}
