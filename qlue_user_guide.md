# Qlue User Guide

Qlue is a lightweight framework for Java web applications. Its main purpose is to provide a structure
in which applications can be easily developed with as little complexity as possible.

Features (some not implemented yet):

- Structure via application, session, and page (request) objects.

- Request routing (static and dynamic) and caching configuration.

- Parameter binding and validation.

- Integration with Apache Velocity.

- Support for stateful operations (e.g., multi-page forms).

  - Built-in redirection after POST.

- Security:

  - Automatic context-aware output encoding in Velocity templates, on by default. It encodes body
    text and unrecognised attribute values, percent-encodes five URL attributes, and refuses to
    write into JavaScript and CSS contexts at all. It is **not** a complete XSS defence — see
    [Output encoding](#output-encoding) below for what it does not cover.

  - Built-in CSRF defence (w/token masking for Breach mitigation).

## Output encoding

Qlue's Velocity view path wraps the response writer in **Canoe**, a streaming HTML tokenizer that
tracks where each `$reference` lands and chooses an encoder for it. Auto-escaping is on by default
and can only be turned off by application code (`setAutoEscaping(false)`), not by configuration.

This section replaces an earlier one-line claim of "built-in XSS defence". The defence is real, and
its scope is narrower than that phrasing suggests.

### What Canoe encodes

| Position of the reference | Encoder | Effect |
|---|---|---|
| Body text and general element content | `HtmlEncoder.htmlWhite()` | Allowlist: ASCII letters, digits, space, tab, CR and LF pass; everything else becomes a character reference. A raw `<` can never be produced, so a body reference cannot open a tag. |
| An attribute value on any attribute not named below | `HtmlEncoder.html()` | The same allowlist, without the whitespace exemption. |
| `href`, `src`, `background`, `dynsrc`, `lowsrc` — those five names only | `HtmlEncoder.url()` | Percent-encoding of everything outside `a-zA-Z0-9/.-#?=`. |

### What Canoe suppresses

These render as **the empty string**, by design — Canoe does not attempt to escape into a scripting
or styling language, it declines to write there:

- inside a `<script>` or `<style>` element;
- inside one of the twenty-one `on*` attributes Canoe recognises;
- inside a `style` or `data` attribute;
- in a tag-name or attribute-name position, and in an unquoted attribute value;
- after a `javascript:`, `livescript:`, `mocha:`, `asfunction:` or `data:` value prefix.

`<script>var user = '$name';</script>` therefore renders as `var user = '';`. If a value is
disappearing from your page, this is why.

### What Canoe rejects

Canoe is a strict tokenizer and raises an encoding error rather than emitting markup it cannot
parse: a DOCTYPE that follows the document's first tag or a second DOCTYPE (a comment above the
DOCTYPE is legal HTML and is accepted, and so is leading text), `<br/>` (a slash straight after a tag
name — `<br />` with a space is fine), an unexpected character after a tag name, a literal `<` in
body text, an XML prolog, and about a dozen more. The error is **not** recovered into a partial page:
it propagates out of the view factory and the request fails.

### What is not covered

- **Attributes Canoe does not recognise are treated as plain text.** That includes `action`,
  `formaction`, `poster`, `cite`, `longdesc`, `ping`, `srcset`, `xlink:href`, `srcdoc`, `sandbox`,
  `rel`, `integrity`, `nonce`, `content` on `<meta http-equiv=refresh>`, and every event handler
  attribute outside the recognised twenty-one. The HTML parser decodes `html()`'s character
  references before handing the value to the JavaScript, CSS or URL parser, so entity encoding does
  not protect these positions. **Treat an attribute as unsafe unless it is on the list above.**
- **`style` is protected only up to the first colon.** `<div style="color:$c">` reaches
  `html()`, not suppression.
- **`url()` filters schemes, not origins.** `<a href="$u">` with `//attacker.example/x` produces a
  working link to another origin.
- **External content inclusion.** Anything the application fetches from elsewhere and writes into
  the response itself is not passed through Canoe. This was the one caveat the framework used to
  state, in a demo page that no longer exists.
- **Non-Velocity output.** Writing to `context.response.getWriter()`, `JsonView`, and every other
  view bypass Canoe completely.

### `$_x` and `allowDirectOutput()`

`$_x` is an `HtmlEncoder` instance bound into the template context, and only when
`QlueApplication.allowDirectOutput()` returns true — it returns `false` by default, so an
application has to override it deliberately.

A reference written as `$_x.method(...)` or `$!_x.method(...)` is **skipped by Canoe entirely**:
whatever the method returns goes to the response verbatim. The match is on those two literal
prefixes, so the formal spellings `${_x.method(...)}` and `$!{_x.method(...)}` do **not** bypass —
they are encoded like any other reference, and `asis()` written that way silently does nothing.
Use the short form.

- `$_x.asis($value)` — emit unencoded. The supported bypass, for when the template author has
  encoded the value themselves or knows it is safe.
- `$_x.html($value)`, `$_x.htmlWhite($value)`, `$_x.url($value)`, `$_x.js($value)`,
  `$_x.css($value)` — encode explicitly for a named context.

If direct output is not allowed, `$_x` is unbound, and because Qlue runs Velocity with
`runtime.strict_mode.enable=true` the template **fails to render** rather than silently producing
nothing. That is deliberate: a bypass that fails open would be worse than one that fails loudly.

The threat model is that the attacker controls data and never the template. Everything in this
subsection is a decision made in template or application code.

### Further reading

`CANOE-SECURITY-REVIEW-2026-07-25.md` in the repository root is a security review of Canoe recording
twenty-four findings, ten of them exploitable by an attacker who controls only data. `PLAN.md`
describes the test suite written against it. Running `./gradlew test` regenerates
`build/reports/canoe/matrix.md` and `matrix.csv`: a generated matrix of every template shape the
suite covers, the context Canoe assigns it, the encoder applied, and whether attacker data reaches
the sink live.

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

To deploy this page we use QlueServlet to act as brige to a Servlet container. We use the QLUE_PAGES_ROOT_PACKAGE parameter to specify the location of your application pages:

	<web-app xmlns="http://java.sun.com/xml/ns/javaee" version="3.1">
    	<servlet>
        	<servlet-name>QlueServlet</servlet-name>
        		<servlet-class>com.webkreator.qlue.QlueServlet</servlet-class>
				<init-param>
					<param-name>QLUE_PAGES_ROOT_PACKAGE</param-name>
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
	
#### Routing

Qlue provides a routing layer that decides how to process each request. In a simple application the routing layer employs only one instance of PackageRouter, which maps requests onto one Java package.

* Requests are mapped to page classes that reside in the root package.
* Comparisons between request URL and class names are case-sensitive.
* There is a global option to treat underscores in class names as dashes.
* Pages and packages whose names begin with $ are considered private and are ignored by the router.
* Subpackages are supported and used to emulate web site subdirectories.
* By default, suffixes (e.g., .html) are not used, but they can be enabled globally or on per-page basis.
* If an attempt is made to access a directory, the "index" page will be sought and delivered if present.
* If directory access comes without a trailing slash, a redirection will be made to the correct URL.
* Similarly, if the index page is explicitly specified, a redirection will be made to remove it.
* A 404 response will be delivered if a suitable page can't be found.
* For convenience, it's possible to deliver a response directly from a template. This is helpful for the responses that are static or nearly static. Such templates identified by different suffixes.


#### Responding to specific HTTP methods only

When you override Page.service(), your page will respond to any HTTP method, which is generally not a good idea. Pages usually only need to respond to GET requests. If that's the case, override onGet() insteaf service(). If any other HTTP method is used, Qlue will respond with the 405 status code. The Page class also defines onPost(), but this method is rarely used; it's usually more convenient to use persistent pages, which will be explained later. If you need to respond to arbitrary request methods, override service() and determine course of action by checking the request method.

#### Page state

Page state is an arbitrary string. Some values are reserved for use by the framework and have special
meanings. Other than that, any custom value is possible. The starting state of any page is always NEW.

Non-persistent pages have no use for this field because they terminate after processing one HTTP transaction.

Simper persistent pages also might not care about the state much, because they are typically designed to
collect some data from the user (e.g., using a form) then perform some action. They finish immediately after
the action is carried out.

More complicated persistent pages might consist of multiple forms and can move from one step to another, finally
finishing in the FINISHED state. Qlue generally doesn't care about page states, except in two cases. First, when a
page changes its state to FINISHED, the cleanup() method is invoked. Second, each page parameter can be designed so
that it is updated from HTTP parameters when only on certain states.
	
#### Page processing

Pages execution is split into many methods, with each method designed to serve a specific purpose.
A non-persistent page will typically use the following methods:

 * initBackend() - invoked first, for example to configure database access.
 * checkAccess() - this is an early hook that is invoked before any work is carried out; intended for access control.
 * validateParameters() - invoked after parameter binding and validation. This is an opportunity for the page to
                          perform additional work checking the data.
 * init() - called only once per persistent page, to be used to do some initialisation, for example fetch
            some data from the database.
 * prepareForService() - this method is intended for use when a group of pages share common functionality. Such work
                     can be implemented only once in a parent class, leaving subclasses to focus on the main
                     functionality. This method is called after successful parameter binding and validation.
 * service() - the main page entry point where the main work is done.
 * commit() - executed immediately after the service() method completes. This method should commit
              all the work carried out by the page.
 * rollback() - executed if there is an unhandled exception during page processing. This method should
                undo all work (if any) attempted by the page.
 * cleanup() - called at the end of transaction that transitioned the page to the FINISHED state.

Additional methods of interest:

 * handleValidationError() - this method is called when parameter binding and validation fails, allowing the page to
                             construct a custom view to respond. This method is typically intended for non-persistent
                             pages, which typically use the GET method and which are typically not intended to fail
                             parameter validation. The default implementation will return a 400 status code, but
                             application might want to show a friendly error message. If this method returns null
                             then page processing continues as if there were no errors.

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

## Routing

Usually only trivial applications can rely 100% on routing by convention. In all other cases you'll need to use custom routing, defined by editing routes.conf placed in the application's WEB-INF folder. This file borrowed most of the syntax of the routing configuration as used by the Play framework some years ago.

#### Setting response headers

The routes file can be used to set custom HTTP response headers. If a line begins with @header, the rest of the line is interpreted as a custom response header. For example:

	@header Cache-Control no-cache
	
Header configuration directives apply to all routes below themm. Thus, to establish defaults, place your confifuration directives at the top of the routes file. A directive for a header of the same name will overwrite the previous header version; this is useful, for example, to use different caching strategies for different parts of the application.

## Velocity configuration

The default Velocity configuration should be sufficient for most situations. Custom configuration can be deployed programmatically, by building a custom ViewFactory inherting from VelocityViewFactory. Then override and implement tweakVelocityContext().

Qlue supports several configuration parameters that control Velocity:

 * qlue.velocity.cache - controls if template caching is enabled; should be disabled in development and enabled in production. Defaults to false.

 * qlue.velocity.modificationCheckInterval - if caching is enabled, controls the interval between checks for modified templates.
 
 * qlue.velocity.priorityTemplatePath - specifies a priority path on the filesystem from which the templates will be loaded. This feature is intended for use in development when application is run from an IDE.
 
It is possible to configure Velocity directly from Qlue configuration; if there are any properties that start with the "qlue.velocity.raw" prefix they will be passed through unmodified (with the prefix removed) to the Velocity engine as the last step in the configuration process.
 
 VelocityViewFactory will dump Velocity configuration to the log at level INFO just prior to creating an instance of the Velocity engine.
 