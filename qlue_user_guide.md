# Qlue User Guide

Qlue is a lightweight framework for Java web applications. Its main purpose is to provide a structure
in which applications can be easily developed with as little complexity as possible.

## Getting started

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