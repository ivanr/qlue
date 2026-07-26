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
watches where each `$reference` lands and picks an encoder for it. Auto-escaping is on by default.
It is a real defence and it is **not** a complete XSS defence; the paragraph above used to say
"to prevent XSS" without qualification, and this section is the correction.

**What is encoded.**

| Where the reference lands | What Canoe applies |
|---|---|
| Body text, and any element content Canoe is not tracking specially | `HtmlEncoder.htmlWhite()` — an allowlist: ASCII letters, digits and the four whitespace characters survive, everything else becomes a character reference |
| An attribute value on an attribute Canoe does not recognise | `HtmlEncoder.html()` — the same allowlist without the whitespace exemption |
| `href`, `src`, `background`, `dynsrc`, `lowsrc` — and only those five | `HtmlEncoder.url()` — percent-encoding |

**What is suppressed.** A reference inside a `<script>` or `<style>` element, inside one of the
twenty-one `on*` attributes Canoe recognises, inside a `style` or `data` attribute, in a tag name or
attribute name position, in an unquoted attribute value, or after a recognised `javascript:`,
`livescript:`, `mocha:`, `asfunction:` or `data:` value prefix renders as **the empty string**.
Canoe does not try to escape into JavaScript or CSS; it refuses to write there at all. This is the
centrepiece of the design and it is why `<script>var x = '$name';</script>` silently produces
`var x = '';`. It is also worth knowing about before you debug an empty value for an hour.

**What is rejected.** Canoe is a strict tokenizer and raises an encoding error on markup it will not
parse — a DOCTYPE that is not the first thing in the document, a comment above the DOCTYPE, `<br/>`
on a void element, an unexpected character after a tag name, and about a dozen other shapes. The
error is **not** recovered: it propagates and the request fails with a 500. If a template renders in
your browser today it will keep rendering; if you are writing new templates, expect strictness.

**What is not covered.**

- **Any attribute Canoe does not recognise.** The recognised list is the five URL names above,
  `style`, `data`, and twenty-one `on*` handlers. Everything else — including `action`,
  `formaction`, `poster`, `srcdoc`, `xlink:href`, `ping`, `srcset`, `sandbox`, `rel`, `nonce` and
  the 76 event handler attributes the HTML Standard defines that are not on the list — is treated as
  plain text. `html()`'s character references are decoded by the HTML parser *before* the value is
  handed to the JavaScript, CSS or URL parser, so encoding does not help there. **Do not interpolate
  into an attribute unless you know it is on the list**, and note that `<div style="color:$c">` is
  on the list only up to the colon — see the review's F4.
- **Origin.** `url()` is a scheme filter, not an origin filter: `<a href="$u">` with
  `//attacker.example/x` produces a link to another origin, unchanged.
- **Content you include from elsewhere.** Canoe encodes references in the template it is rendering.
  Anything an application fetches and writes to the response itself, or passes through `$_x.asis()`,
  is not touched.
- **Non-Velocity output.** Writing to `context.response.getWriter()` directly, `JsonView`, and any
  other view bypass Canoe entirely.

**The escape hatches.** `$_x` is an `HtmlEncoder` bound into the template context only when
`QlueApplication.allowDirectOutput()` returns true — it returns false by default, so you have to
override it. A reference written as `$_x.something(...)` is **skipped by Canoe entirely**: whatever
the method returns is written verbatim. `$_x.asis($value)` is therefore the documented way to emit
unencoded output, and `$_x.html($value)` the way to encode explicitly. With direct output not
allowed, `$_x` is unbound and — because Qlue runs Velocity in strict mode — the template fails to
render rather than silently emitting nothing. `setAutoEscaping(false)` turns Canoe's reference
interception off for a whole factory. All of these are template-author and application decisions;
none is reachable from request data.

**Read this before relying on it.** `CANOE-SECURITY-REVIEW-2026-07-25.md` is a security review of
Canoe with twenty-four findings, ten of them exploitable by an attacker who controls only data.
`PLAN.md` describes the test suite written against it. `./gradlew test` regenerates
`build/reports/canoe/matrix.md` — a generated matrix of every template shape the suite covers, the
encoder Canoe applies to it, and whether attacker data reaches the sink live.
