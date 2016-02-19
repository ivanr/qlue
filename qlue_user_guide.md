# Qlue User Guide

Qlue is a lightweight framework for Java web applications. Its main purpose is to provide a structure
in which applications can be easily developed with as little complexity as possible.

Features (some not implemented yet):

- Structure via application, session, and page (request) objects.

- Request routing (static and dynamic).

- Parameter binding and validation (classic and JSON).

- Integration with Velocity; other view technologies possible.

- Support for stateful operations (e.g., multi-page forms).

  - Built-in redirection after POST.

- Support for application objects that live in user sessions.

- Scheduled (cron-like) activity.

  - Once a day, once a week, once a month, etc.

  - Activity train.

  - Graceful shutdown (complete in progress, don't start new).

- Detailed logging of errors for troubleshooting.

- Simple event notification framework to support development of modular applications.

- Internal state exposed via HTTP and JMX.

- Security:

  - Built-in support for resource usage monitoring and limiting.

  - Built-in CSRF defence (w/token masking for Breach mitigation).

  - Built-in XSS defence (via automatic content-aware output encoding).

  - Hardened session management.

## Getting started

To give you a taste of the simplicity that Qlue offers, in this section we will implement the simplest possible Qlue application. The entire application will consist of one class -- a page in Qlue terminology -- which will print "Hello World" in response to a HTTP request. Here it is, in all its glory:

	package com.example.site.pages;
	
	public class helloWorld extends Page {

		@Override
		public View onGet() throws Exception {
			PrintWriter out = context.response.getWriter();

			out.println("Hello World!");

			return new NullView();
		}
	}

To deploy this page we use QlueServlet to act as brige to a Servlet container. We use the QLUE_PAGES_PACKAGE parameter to specify the location of your application pages:

	<web-app xmlns="http://java.sun.com/xml/ns/javaee" version="3.1">
    	<servlet>
        	<servlet-name>QlueServlet</servlet-name>
        		<servlet-class>com.webkreator.qlue.QlueServlet</servlet-class>
				<init-param>
					<param-name>QLUE_PAGES_PACKAGE</param-name>
					<param-value>com.example.site.pages</param-value>
				</init-param>
    		</servlet>
    		<servlet-mapping>
        		<servlet-name>QlueServlet</servlet-name>
        		<url-pattern>/*</url-pattern>
    		</servlet-mapping>
	</web-app>

That's all. Now when you start the web server and invoke "/helloWorld" in your browser, you should get "Hello World" back.

## Concepts

### Pages

In Qlue, you write your web application by creating pages that handle HTTP requests. By default, one unique URL maps to one page, and one page is implemented via one Java class. Consider this simple page:

	public class helloWorld extends Page {

		@Override
		public View service() throws Exception {
			PrintWriter out = context.response.getWriter();

			out.println("Hello World!");

			return new NullView();
		}
	}
	
Here's what you should know about page creation:
	
 * To create a page, create a new class inheriting Page
 * The name of the class should correspond to the URL; in the above example, the page will be executed when the path /helloWorld is invoked.
 * Simple pages are stateless; the framework will create a new page instance for each HTTP request.
 * To do something in your page, override the method onGet().
 * In this simple example we output directly to a HTTPS response by working directly with an instance of HttpServletResponse.
 * To indicate to the framework that no further response handling is needed, we return an instance of NullView.
	
#### Page name mapping

By default, case-sensitive comparison is done between the last URL segment and the page name. Thus, only "/helloWorld" will map to the above helloWorld class. If you wish you can have your pages use a suffix externally (e.g., ".html"). In that case, in your application class, invoke setSuffix() on the correct RouteManager.

#### Responding to specific HTTP methods only

When you override Page.service(), your page will respond to any HTTP method, which is generally not a good idea. Pages usually only need to respond to GET requests. If that's the case, override onGet() insteaf service(). If any other HTTP method is used, Qlue will respond with the 405 status code. The Page class also defines onPost(), but this method is rarely used; it's usually more convenient to use persistent pages, which will be explained later. If you need to respond to arbitrary request methods, override service() and determine course of action by checking the request method.
	
### Views

Although it's possible to write pages that do some work and generate output, in general that's not recommended. Instead, each page should delegate output generation to an instance of View. Qlue is bundled with Apache Velocity, which is a generic templating language. When used with Velocity, it's a page's job to create a set of objects (model), and determine which Velocity template should be invoked to turn the model into a HTTP response.

By convention, Velocity templates use the same name as the pages they're written for. When that's the case, a page can simply return an instance of DefaultView to indicate to the framework that the same-name template should be used:

	return new DefaultView();
	
If a page wants to use a different template, it can indicate that by returning an instance of NamedView:

	return new NamedView("helloWorld");
	
To issue a redirection, return an instance of RedirectView:

	RedirectView rv = new RedirectView("https://elsewhere.example.com");
	rv.addParam("param1", "value1");
	rv.addParam("param2", "value2");
	return rv;
	
### Model

Pages that wish to generate output need to build a model, which is simply a map of named objects. There are two ways to add to the model:

 1. Implicitly, because Qlue will automatically add all public fields of the page to the model.
 
 2. Explicitly, by using Page.addToModel(String name, Object object).

Just before view generation is started, Qlue will automatically add a number of useful objects to the model. The names of these objects start with an underscore to avoid collision with application objects.

| Name    | Description            |
| ----    | -----------            |
| _f      | Formatting helper      |
| _app    | Application            |
| _page   | Page itself            |
| _i      | Shadow input           |
| _ctx    | Qlue context           |
| _sess   | Application session    |
| _m      | Message source         |
| _req    | Servlet HTTP request   |
| _res    | Servlet HTTP response  |
| _cmd    | Command object, if any |
| _errors | Processing errors      |
| _secret | Session CSRF token     |
