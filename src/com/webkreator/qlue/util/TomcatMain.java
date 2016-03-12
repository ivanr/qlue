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

import org.apache.catalina.startup.Tomcat;
import org.apache.commons.cli.*;

import java.io.File;

/**
 * Run a web application (determined using the given home directory)
 * using Tomcat on the given IP address and port. 
 */
public class TomcatMain {

	public static void main(String args[]) throws Exception {
		System.setProperty("tomcat.util.scan.StandardJarScanFilter.jarsToSkip", "*");

		// Handle command line options
		Options options = new Options();
		options.addOption("h", "home", true, "application home path");
		options.addOption("I", "ip", true, "IP address");
		options.addOption("P", "port", true, "port");

		CommandLineParser parser = new PosixParser();
		CommandLine commandLine = null;

		try {
			commandLine = parser.parse(options, args);
		} catch (ParseException pe) {
			System.err.println("Failed to parse command line: " + pe.getMessage());
			System.exit(1);
		}
		
		String home = new File("./web").getAbsolutePath();
		
		if (commandLine.hasOption("home")) {
			home = commandLine.getOptionValue("home");
			home = new File(home).getAbsolutePath();
		}			

		Integer port = 8080;
		if (commandLine.hasOption("port")) {
			port = Integer.parseInt(commandLine.getOptionValue("port"));
		}

		String hostname = null;
		if (commandLine.hasOption("ip")) {
			hostname = commandLine.getOptionValue("ip");
		}

		Tomcat tomcat = new Tomcat();
		tomcat.setBaseDir(System.getProperty("java.io.tmpdir"));
		if (hostname != null) {
			tomcat.setHostname(hostname);
		}			
		
		tomcat.setPort(port);
		tomcat.addWebapp("/", home);
		
		if ((port == 443)||(port == 8443)) {
			String keystoreFile = home + "/WEB-INF/dev-keystore";
			String keystorePass = "changeit";
			
			tomcat.getConnector().setScheme("https");
			tomcat.getConnector().setSecure(true);
			tomcat.getConnector().setProperty("SSLEnabled", "true");
			tomcat.getConnector().setProperty("keystoreFile", keystoreFile);
			tomcat.getConnector().setProperty("keystorePass", keystorePass);
		}
		
		tomcat.start();
		tomcat.getServer().await();		
	}
}