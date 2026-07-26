# Qlue v4.x (development)

Qlue is a lightweight framework for Java web applications. Its purpose is to provide a structure
in which applications can be developed with as little complexity as possible.

I decided to write Qlue probably somewhere around 2007, mostly because all other Java frameworks
for web applications were too complex and difficult to use. I just couldn't get myself to work
with any of them. I wanted something simple, but couldn't find it.

Qlue is stable and has a number of nice features, even some that are pretty difficult to find elsewhere
(e.g., automatic context-sensitive output encoding in Velocity templates). On the other hand, it's not
documented and there are virtually no examples.

## Output encoding: what it does and does not do

Qlue's Velocity view path wraps the response writer in **Canoe**, a streaming HTML tokenizer that
watches where each `$reference` lands and picks an encoder for it. Auto-escaping is on by default,
and only application code can turn it off (`VelocityViewFactory.setAutoEscaping(false)`) — no Qlue
property reaches it. It is a real defence and it is **not** a complete XSS defence; the blurb above
used to promise "automatic context-sensitive output encoding to prevent XSS", and this section is the
qualification that claim needed. `qlue_user_guide.md` has the same material at length, with the parts
a template author has to act on.

**What is encoded.**

| Where the reference lands | What Canoe applies |
|---|---|
| Body text, and any element content Canoe is not tracking specially | `HtmlEncoder.htmlWhite()` — an allowlist: ASCII letters, digits and the four whitespace characters survive, everything else becomes a character reference |
| An attribute on the plain-text allowlist — `title`, `class`, `id`, `alt`, `value`, the form-control and table attributes, and every `aria-*` and `data-*` name | `HtmlEncoder.html()` — the same allowlist without the whitespace exemption |
| One of the seventeen URL-bearing attribute names — `href`, `src`, `action`, `formaction`, `cite`, `ping`, `poster`, `srcset`, `xlink:href`, `data` and the rest | `HtmlEncoder.url()` — a scheme allowlist (`http`, `https`, `mailto`, and relative references), then percent-encoding per URL component and per UTF-8 byte |
| A URL on one of the six element/attribute pairs that fetch code or reroute the page — `<script src>`, `<iframe src>`, `<embed src>`, `<object data>`, `<link href>`, `<base href>` | `HtmlEncoder.urlResource()` — the above, plus an origin filter: a value that names an authority at all is dropped unless its host is on a configured allowlist |

**What is suppressed** — these render as **the empty string**. Canoe does not try to escape into
JavaScript or CSS; it refuses to write there at all.

- **An attribute whose name is on none of the lists above.** This is the default, and it is the one
  thing to take away from this section: an unrecognised attribute name is *suppressed*, not encoded.
  `<div hx-target="$x">` renders `hx-target=""` until you say otherwise.
- Any attribute whose name begins `on`, with no exceptions — all 94 event handlers the HTML Standard
  defines and every one it adds in future, where a hand-written table used to recognise eighteen.
- A `style` attribute, all of it, including after the first colon.
- Inside a `<script>` or `<style>` element, so `<script>var x = '$name';</script>` produces
  `var x = '';`. Worth knowing before you debug an empty value for an hour.
- A tag-name or attribute-name position, a comment, and a DOCTYPE declaration.
- A value that already begins `javascript:`, `livescript:`, `mocha:`, `asfunction:` or `data:`.
- An off-origin URL on one of the six resource-loading sinks above.

**How to tell a value was dropped.** Raise the logger `com.webkreator.qlue.view.Canoe` to DEBUG.
Canoe logs one line per reference it suppresses in an unrecognised attribute, naming the attribute
and the line and position. There is nothing on the page to see otherwise, which is why this is here
rather than in a footnote.

**Quote your attribute values.** An unquoted value is encoded by its attribute's name exactly as a
quoted one is, but an unquoted value that comes out *empty* is not an empty attribute:
`<img src=$u alt="a">` renders `<img src= alt="a">`, and every tokenizer reads `alt="a"` as `src`'s
value. Two quote characters remove the whole class of problem.

**The two escape hatches**, for when the default is wrong for your page. Both are configuration, both
validate what they are given at startup, and both are narrower than `$_x.asis()`:

- `VelocityViewFactory.addPlainTextAttributes("hx-target", …)`, or the Qlue property
  `qlue.canoe.plainTextAttributes`, adds attribute names whose values are text. It refuses `sandbox`,
  `rel`, `integrity`, `nonce`, `srcdoc`, `style`, anything beginning `on`, and every name Canoe
  classifies before it consults the allowlist — those refusals are the fix for a finding, and putting
  one back through configuration would re-open it.
- `VelocityViewFactory.addTrustedResourceOrigins("cdn.example.com", …)`, or
  `qlue.canoe.trustedResourceOrigins`, lets the six resource-loading sinks load from a named host or
  origin. An entry is `host`, `https://host` or `https://host:port`.

**What is rejected.** Canoe is a strict tokenizer and raises an encoding error on markup it will not
parse — a DOCTYPE after the first element, a literal `<` in body text, `</ p>` or `</>`, an XML
prolog, a control character in the template's own text, an unexpected character after a tag name, and
about a dozen other shapes. `<br/>`, a tag or attribute name of up to 127 characters, a second
DOCTYPE and text above the DOCTYPE are all accepted; the last two are logged as warnings, because a
browser ignores a second declaration and renders a document with text above the declaration in quirks
mode. The error is **not** recovered into a partial page: it propagates as a `CanoeEncodingException`
carrying the reason, the line and the position, nothing is flushed, and the response buffer is reset
so the request can fail cleanly. If a template renders in your browser today it will keep rendering;
if you are writing new templates, expect strictness.

**What is not covered.**

- **Origin, everywhere except those six sinks.** `url()` filters schemes, not origins, so an
  `<a href>`, `<img src>`, `<form action>`, `ping`, `cite`, `poster` or `srcset` built from
  attacker-controlled data can point at another origin. That is an open redirect and a referrer leak,
  it is deliberate, and it is where the line is: those sinks fetch or navigate, they do not execute.
- **Content you include from elsewhere.** Canoe encodes references in the template it is rendering.
  Anything an application fetches and writes to the response itself is not touched, and `#include`
  copies a fragment's bytes with no Velocity parse, so the fragment's own `$data` is literal text.
- **`srcdoc`**, whose value is parsed as a whole HTML document. It is suppressed rather than encoded,
  because single encoding there is same-origin XSS and double encoding is a feature to design.
- **DOM clobbering.** `<div id="$data">` is encoded as text, which is all an encoder can do; what an
  attacker-chosen `id`, `name` or `form` does to *other* scripts on the page is out of scope.
- **Non-Velocity output.** Writing to `context.response.getWriter()` directly, `JsonView`, and any
  other view bypass Canoe entirely.
- **The template itself.** The threat model throughout is that the attacker controls data and never
  the template. `$_x.asis()`, `#evaluate($data)`, `#parse($data)` and `#include($data)` are
  template-author decisions, and no output encoder defends them.

**The bypass.** `$_x` is an `HtmlEncoder` bound into the template context only when
`Page.allowDirectOutput()` returns true — it defers to `QlueApplication.allowDirectOutput()`, which
returns false, so you have to override it. A reference written `$_x.method(…)` is **skipped by Canoe
entirely** in all four of Velocity's spellings (`$_x.`, `$!_x.`, `${_x.`, `$!{_x.`): whatever the
method returns is written verbatim. `$_x.asis($value)` is therefore the documented way to emit
unencoded output, and `$_x.html($value)` the way to encode explicitly. With direct output not allowed,
`$_x` is unbound and — because Qlue runs Velocity in strict mode — the template fails to render rather
than silently emitting nothing. Note that a value built by `#set($msg = "…$data…")` is now encoded
where it is *printed* rather than where the `#set` ran, so `$_x.asis($msg)` on such a value puts the
data on the page raw; it used to arrive encoded, by accident rather than by design.

**Read this before relying on it.** `CANOE-SECURITY-REVIEW-2026-07-25.md` is a security review of
Canoe with twenty-four findings, and `REMEDIATION-PLAN.md` records what was done about each.
`PLAN.md` describes the test suite. `./gradlew test` regenerates `build/reports/canoe/matrix.md` — a
generated matrix of every template shape the suite covers, the encoder Canoe applies to it, and
whether attacker data reaches the sink live.
