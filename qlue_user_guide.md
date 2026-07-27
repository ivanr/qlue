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
    text and the attribute values it recognises as text, percent-encodes seventeen URL-bearing
    attributes with an origin filter on the six that fetch code, and refuses to write into
    JavaScript and CSS contexts at all. An attribute name it does not recognise is **suppressed**,
    not encoded. It is **not** a complete XSS defence — see [Output encoding](#output-encoding)
    below for what it does not cover, and for how to widen the defaults when they are wrong for
    your page.

  - Built-in CSRF defence (w/token masking for Breach mitigation).

## Output encoding

Qlue's Velocity view path wraps the response writer in **Canoe**, a streaming HTML tokenizer that
tracks where each `$reference` lands and chooses an encoder for it. Auto-escaping is on by default
and can only be turned off by application code (`VelocityViewFactory.setAutoEscaping(false)`); no
Qlue property reaches the switch. Turning it off detaches the encoder, not the tokenizer — Canoe
still parses the output, so a template it rejects is still rejected.

This section replaces an earlier one-line claim of "built-in XSS defence". The defence is real, and
its scope is narrower than that phrasing suggests.

### What Canoe encodes

| Position of the reference | Encoder | Effect |
|---|---|---|
| Body text and general element content | `HtmlEncoder.htmlWhite()` | Allowlist: ASCII letters, digits, space, tab, CR and LF pass; everything else becomes a character reference. A raw `<` can never be produced, so a body reference cannot open a tag. |
| An attribute on the plain-text allowlist | `HtmlEncoder.html()` | The same allowlist, without the whitespace exemption. |
| One of the seventeen URL-bearing attribute names | `HtmlEncoder.url()` | A scheme allowlist, then percent-encoding per URL component and per UTF-8 byte. |
| `src` on `<script>`, `<iframe>` or `<embed>`, `data` on `<object>`, `href` on `<link>` or `<base>` | `HtmlEncoder.urlResource()` | `url()`, plus an origin filter. |

**The plain-text allowlist** is the ordinary text and enumerated attributes — `title`, `class`, `id`,
`alt`, `value`, `placeholder`, `label`, `lang`, `dir`, the form-control attributes (`type`, `name`,
`min`, `max`, `pattern`, `required`, …), the table attributes (`colspan`, `headers`, `scope`, …), the
media attributes (`width`, `height`, `loading`, `preload`, …) — plus every name beginning `aria-` or
`data-`. The hyphen is required, exactly as in the HTML Standard: the bare name `data` is a URL.
Anything else is suppressed; see below.

**The seventeen URL-bearing names** are `action`, `background`, `cite`, `codebase`, `data`, `dynsrc`,
`formaction`, `href`, `longdesc`, `lowsrc`, `manifest`, `ping`, `poster`, `src`, `srcset`, `usemap`
and `xlink:href`. `url()` treats the value as a URL reference in its own right:

- A leading scheme is checked against an allowlist of `http`, `https` and `mailto`. Anything else —
  `javascript:`, `data:`, `vbscript:`, and every scheme nobody has registered yet — makes the whole
  value **the empty string**. A value with no scheme is a relative reference and is fine.
- The value is then split into scheme, authority, path, query and fragment, and each component keeps
  the delimiters that are structural *in it*: `:` and `[` `]` survive in an authority so a port and an
  IPv6 literal are not destroyed, `&` and `=` survive in a query. Everything else is percent-escaped
  **per UTF-8 byte**, so `é` becomes `%C3%A9` rather than a mangled Latin-1 byte.
- An existing `%XX` escape is passed through rather than escaped a second time.
- An `&` is emitted as `&amp;`, because `url()`'s output goes straight into an HTML attribute.

One consequence is worth knowing before you meet it: because the encoder cannot see the literal text
around the reference, it cannot tell `<a href="$u">` from `<a href="/search?q=$q">`. Keeping `&` as a
separator is right for the first and permissive for the second — a `$q` of `1&b=2` adds a parameter to
your query rather than staying inside one. The origin and the path are still yours. If you interpolate
into a query string and care which parameters the URL carries, validate the value.

**The origin filter** applies to those six element/attribute pairs and only to them, because they are
the ones a browser dereferences into code or into control of the page. A value that introduces an
authority — `//host`, `https://host`, or the `http:host` form a browser still reads as one — is
dropped to the empty string unless its host is on a configured allowlist. A relative reference
(`/path`, `path`, `?query`, `#frag`) carries no authority and cannot leave the page's origin, so it is
always allowed. Canoe does not know your application's own origin, which is why the rule is "names an
authority at all" rather than "names a different one".

A value assembled by `#set` is encoded **where it is printed**, not where the `#set` ran — including
`#set($msg = "Hello $name")`, whose reference is interpolated into a string Velocity builds
internally. The whole string is then one value: the template's own `Hello ` is encoded along with the
data, so in an attribute the space arrives as `&#32;`, which a browser renders as a space. Until this
was fixed such a value was encoded twice and `<b>` reached the page as the visible text `&lt;b&gt;`.

### What Canoe suppresses

These render as **the empty string**, by design — Canoe does not attempt to escape into a scripting
or styling language, it declines to write there:

- **an attribute whose name is on none of the lists above.** This is the default, and it is the
  behaviour most likely to surprise you: an unrecognised attribute name is *suppressed*, not treated
  as plain text. `<div hx-target="$x">` renders `hx-target=""`.
- any attribute whose name begins `on`, with no exceptions — all 94 event handlers the HTML Standard
  defines and every one it adds in future, where a hand-written table used to recognise eighteen;
- a `style` attribute, all of it, including after the first colon;
- inside a `<script>` or `<style>` element;
- in a tag-name or attribute-name position, and inside a comment or a DOCTYPE declaration;
- after a `javascript:`, `livescript:`, `mocha:`, `asfunction:` or `data:` value prefix;
- an off-origin URL on one of the six resource-loading sinks.

`<script>var user = '$name';</script>` therefore renders as `var user = '';`. If a value is
disappearing from your page, this is why.

**Finding out which attribute swallowed it.** Raise the logger `com.webkreator.qlue.view.Canoe` to
DEBUG. Canoe logs one line for every reference it suppresses in an unrecognised attribute, naming the
attribute, the line and the position, and pointing at the extension point below. Nothing appears on
the page, so without the log a suppressed value and an empty model entry look identical — which is
exactly what sends a developer to `$_x.asis()`.

### Widening the defaults

Two escape hatches exist so that the fail-closed defaults have an answer smaller than turning the
encoder off for a value. Both are configured on the view factory before the first render, both accept
a Qlue property instead, and both validate what they are given at startup rather than silently doing
nothing on every page.

**Plain-text attribute names**, for `<div my-widget-config="$x">`:

    factory.addPlainTextAttributes("my-widget-config", "hx-target");

    qlue.canoe.plainTextAttributes = my-widget-config, hx-target

Names are separated by commas, whitespace or both, and are lower-cased. The grant is `html()`, not a
bypass: the value still cannot leave the attribute it was written into. These names are **refused**,
with an exception naming the reason:

| Refused | Why |
|---|---|
| anything beginning `on` | Every one is a JavaScript context. The prefix rule has no exceptions. |
| `style`, and the seventeen URL-bearing names | Canoe classifies these before it consults the allowlist, so adding one would have no effect. Failing loudly beats looking as though it worked. |
| `sandbox`, `rel`, `integrity`, `crossorigin`, `referrerpolicy` | The parser consumes the decoded value as a *directive*. No encoding of `allow-same-origin` means anything other than `allow-same-origin`; encoding is not insufficient here, it is inapplicable. |
| `nonce` | Inert as text, which is the wrong test: an attacker who chooses the nonce can author a `<script nonce>` your content security policy then admits. |
| `http-equiv`, `charset`, `content` | Parser and navigation directives. `refresh` turns a sibling `content` into a redirect; the declared encoding decides how every later byte is tokenized. |
| `is` | Selects which custom element definition upgrades the element — a choice of code, not a piece of text. |
| `srcdoc` | Its value is parsed as a whole HTML document, so a single encode is same-origin XSS. |
| `imagesrcset`, `xml:base`, `archive`, `classid`, `profile` | URL-bearing names deliberately left suppressed. Putting one on `html()` would be strictly worse than the URL encoder they were declined. |

A name the tokenizer could never buffer is refused for the same reason: one holding a character that
is not legal in an attribute name, or one longer than 127 characters. Either would be an entry
nothing on any page could ever match, which is a silently ineffective security setting.

**Trusted resource origins**, for an application that serves its scripts from a CDN:

    factory.addTrustedResourceOrigins("cdn.example.com", "https://static.example.com:8443");

    qlue.canoe.trustedResourceOrigins = cdn.example.com, https://static.example.com:8443

An entry is a bare host (matching under any allowed scheme and port), an origin `https://host`
(pinning the scheme, so an `http` downgrade is rejected), or `https://host:port` (pinning the port
too). A path, a userinfo `@`, an empty host or a scheme other than `http`/`https` is a
misconfiguration and throws at startup. The grant is to the named host only — every other off-origin
value is still dropped.

Both settings live on the factory, and therefore on the engine, so two applications in one JVM cannot
widen each other's — the sets are per factory instance and never static. The *timing* is a convention
rather than a guard: each render reads the factory's sets as they are then, so a call made after the
first page has rendered takes effect on the next one. Configure both from `init()` and treat them as
startup configuration.

### Quote your attribute values

An **unquoted** attribute value is encoded, not suppressed: `<a href=$url>` is encoded exactly as
`<a href="$url">` is, by the attribute's name. Quote it anyway. If the value comes out empty — a
suppressed attribute, a rejected URL scheme, or simply an empty model entry — an unquoted value is
not an empty attribute: `<img src=$u alt="a">` renders as `<img src= alt="a">`, and every tokenizer,
Canoe included, reads `alt="a"` as `src`'s value. The `alt` is gone. Two quote characters remove the
whole class of problem, and one literal character in front of the reference (`href=/p$u`) does too,
which is why the shape is easy to miss.

### What Canoe rejects

Canoe is a strict tokenizer and raises an encoding error rather than emitting markup it cannot
parse: a DOCTYPE that follows the document's first tag, an unexpected character after a tag name, a
literal `<` in body text, `</ p>` and `</>`, an XML prolog, a control character in the template's own
text, and about a dozen more. The error is **not** recovered into a partial page: it propagates out
of the view factory as a `CanoeEncodingException` — carrying the reason, the line and the position,
and findable in a wrapped exception's cause chain with `CanoeEncodingException.findIn(e)` — the
output is not flushed, and the response buffer is reset so that a `handleException()` view or a
`sendError(500)` can still replace the page wholesale. That last part has a bound: a response commits
when its buffer fills (a few kilobytes), and a template that raises after that much output has already
put a fragment on the wire.

An encoding error is never attacker-controlled: every shape that reaches it is a template-authoring
error, so catching one means "this page's template is wrong", not "somebody is attacking us".

Four shapes that used to be rejected are accepted: `<br/>` with no space (it always worked with one),
a tag or attribute name of up to 127 characters (`data-*` names from any modern framework run past
the old limit of 35), a **second** DOCTYPE, and text above the DOCTYPE. The last two are accepted
because a browser ignores them, and each is logged as a warning that says so — a second declaration
is discarded by the browser, and a declaration below any text leaves the document in quirks mode, so
both are worth fixing in the template and neither is worth failing a request over.

### Directives

Every directive that can carry a reference encodes it, and a value routed through `#set` is encoded
once, at the position it is printed. Three directives are different, and the difference is not
visible at the call site:

- **`#evaluate("…$data…")`** compiles the string as VTL. The value is encoded at the reference rather
  than deferred, so a payload of `#set($injected = 1)$injected` reaches `#evaluate` as
  `&#35;set&#40;&#36;injected &#61; 1&#41;&#36;injected` — no `#` and no `$` left in it, so what is
  compiled is inert text.
- **`#parse("$data")`** and **`#include("$data")`** use the string as a template or resource name.
  The value is encoded there too, so `/` and `.` come back as character references and the lookup
  fails rather than resolving the attacker's file.
- **`#include`** of a plain file name copies the resource's bytes with no Velocity parse at all, so a
  `$data` written inside the fragment arrives as literal text and no reference is inserted. The bytes
  still go through Canoe, so an included fragment steers the tokenizer exactly as inline template
  text does.

**None of that makes those directives safe.** The plain spellings — `#set($t = $data)#evaluate($t)`,
`#parse($data)` — hand the raw value straight to the directive and always have, because a bare
assignment never fires the encoder at all. Passing request data into `#evaluate`, `#parse` or
`#include` is application-level template injection, and it is outside what an output encoder can
defend. Do not do it.

### `$_x` and `allowDirectOutput()`

`$_x` is an `HtmlEncoder` instance bound into the template context, and only when
`Page.allowDirectOutput()` returns true — it defers to `QlueApplication.allowDirectOutput()`, which
returns `false`, so an application has to override it deliberately.

A reference written as `$_x.method(...)`, `$!_x.method(...)`, `${_x.method(...)}` or
`$!{_x.method(...)}` is **skipped by Canoe entirely**: whatever the method returns goes to the
response verbatim. Those are all four of the reference spellings Velocity accepts, and they behave
identically — the notation you choose does not change the security behaviour of the line.

The match is on those four literal prefixes, including the trailing dot, and nothing else is a
bypass. A name that merely begins with `_x` — `$_xy.method(...)`, `${_xtra.method(...)}` — is
encoded like any other reference. Whitespace inside the braces, as in `${ _x.asis($value) }`, is not
a Velocity reference at all: Velocity's lexer only enters the reference state on the exact token
`${`, so the braces and the `_x.asis(...)` around it reach the page as literal text and the only
thing substituted is the inner `$value`, which is encoded — the line renders as
`${ _x.asis(&lt;b&gt;) }` rather than bypassing anything.

- `$_x.asis($value)` — emit unencoded. The supported bypass, for when the template author has
  encoded the value themselves or knows it is safe. This includes a value built by
  `#set($value = "…$data…")`: such a value now carries the data as it arrived, so `asis()` on it puts
  attacker-controlled bytes in the page. It used to come out encoded once, because the `#set`
  double-encoded it and `asis()` was declining to do the second pass — an accident, not a control.
- `$_x.html($value)`, `$_x.htmlWhite($value)`, `$_x.url($value)` — encode explicitly for a named
  context. Their outputs are the same ones the table above describes.
- `$_x.js($value)`, `$_x.css($value)` — encode for a JavaScript or CSS literal, quotes included:
  `js("a'b")` is `'a\x27b'`, and the quotes are part of the output, so write `var x = $_x.js($v);`
  and not `var x = '$_x.js($v)';`. Canoe never calls these itself — it suppresses those contexts
  instead — so inside a `<script>` element the tool is the only way to write a value at all, and
  getting it right is yours.

If direct output is not allowed, `$_x` is unbound, and because Qlue runs Velocity with
`runtime.strict_mode.enable=true` the template **fails to render** rather than silently producing
nothing. That is deliberate: a bypass that fails open would be worse than one that fails loudly.

### What is not covered

- **Origin, on every URL attribute except the six resource sinks.** `url()` filters schemes, not
  origins. An `<a href>`, `<img src>`, `<form action>`, `ping`, `cite`, `poster`, `srcset`,
  `formaction` or `usemap` built from attacker-controlled data can point at another origin:
  `//attacker.example/x` and `https://attacker.example/x` both survive `url()` byte for byte. That is
  an open redirect and a referrer leak, and it is deliberate — those attributes fetch or navigate,
  they do not execute, and rejecting an off-origin value from them would break linking to another
  site and hotlinking an image. If your page interpolates into one of them and the destination
  matters, validate the value.
- **External content inclusion.** Anything the application fetches from elsewhere and writes into
  the response itself is not passed through Canoe. This was the one caveat the framework used to
  state, in a demo page that no longer exists.
- **`srcdoc`.** Its value is parsed as a whole HTML document, so the correct encoding is a second
  full HTML encode; a single-encoded value there is same-origin XSS. Canoe suppresses it instead. If
  an application genuinely needs to interpolate into `srcdoc`, that is a feature to design.
- **DOM clobbering.** `<div id="$data">` is encoded as text, which is all an encoder can do. What an
  attacker-chosen `id`, `name`, `for`, `form`, `headers`, `list` or `popovertarget` does to *other*
  scripts on the page — re-associating a control with a different form, pointing a label somewhere
  else — is out of scope by the review's own criterion: a name in the document's namespace is not a
  directive a browser algorithm consumes.
- **Behaviour an attacker can change without escaping anything.** `target` and `formtarget` retarget
  a navigation; `method` and `formmethod` can turn a `POST` into a `GET`, which moves the form's
  fields — including its CSRF token — into a URL that reaches history, logs and the referrer. The
  destination is still yours. These are on the plain-text allowlist deliberately, because
  suppressing them would cost every ordinary form, and the residual is recorded rather than hidden.
- **`style`, in the other direction.** It is suppressed rather than escaped, so it is safe and it
  does not work: `<div style="color:$c">` renders `style="color:"`. A CSS value that has to be
  dynamic belongs in a class name.
- **Non-Velocity output.** Writing to `context.response.getWriter()`, `JsonView`, and every other
  view bypass Canoe completely.
- **The template itself.** The threat model is that the attacker controls data and never the
  template. `$_x.asis()`, `allowDirectOutput()`, `#evaluate($data)`, `#parse($data)` and
  `#include($data)` are template and application decisions, unguarded by design.
- **Cross-engine behaviour.** The browser tier of the test suite runs against Chromium, Firefox and
  WebKit, and they agree: of the 67 corpus rows it loads, the 65 every engine can be asked about
  produce identical results. Three engines are not every engine, and two rows have no Firefox result
  at all — the driver wedges on a form submission to an off-loopback `http:` action — which
  `BrowserCorpusTest.ENGINE_LIMITATIONS` records by name.

### Further reading

`CANOE-SECURITY-REVIEW-2026-07-25.md` in the repository root is a security review of Canoe recording
twenty-four findings, ten of them exploitable by an attacker who controls only data;
`REMEDIATION-PLAN.md` records what was done about each. `PLAN.md` describes the test suite written
against it. Running `./gradlew test` regenerates `build/reports/canoe/matrix.md` and `matrix.csv`: a
generated matrix of every template shape the suite covers, the context Canoe assigns it, the encoder
applied, and whether attacker data reaches the sink live.

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
 