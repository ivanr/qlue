# Canoe Remediation Plan

**Subject:** fixing the defects recorded in `CANOE-SECURITY-REVIEW-2026-07-25.md`
**Date:** 2026-07-26
**Branch:** `canoe-hardening`
**Status of the code being fixed:** no remediation has started; every finding below is live.

---

## 0. Where things stand

Verified today, on this working tree:

| Check | Result |
|---|---|
| `./gradlew test` | **BUILD SUCCESSFUL** — 5,489 tests, 0 failures, 0 errors, 0 skipped |
| `./gradlew browserTest` | 155 tests, 0 failures, 2 skipped (Firefox and WebKit are not installed in this environment; Chromium ran) |
| Findings in the review | 24, of which 10 are exploitable by a data-only attacker and 8 give arbitrary script execution |
| Corpus ledger | 996 invocations: 564 `SAFE`, **281 `KNOWN_VULNERABLE`**, 77 `SUPPRESSED_BY_DESIGN`, 44 `REJECTED`, 30 `SUPPRESSED_UNINTENDED` |

**A green suite does not mean a safe encoder.** The suite records the vulnerabilities rather than
failing on them: every exploitable case carries an explicit `KNOWN_VULNERABLE` verdict citing a
finding, and `Verdict`'s own javadoc says such a case **fails when the vulnerability disappears**.
That is the design. It also means the number to drive down is not the failure count — it is this:

| Finding | `KNOWN_VULNERABLE` invocations | Closed by |
|---|---|---|
| F3 — unrecognised URL/markup/refresh attributes | 93 | R5, R6, R7 |
| F2 — `on*` allowlist misses 76 of 94 handlers | 92 | R4 |
| F4 — prefix scan discards the attribute's context | 38 | R2 |
| F6 — `url()` is a scheme filter, not an origin filter | 37 | R9, R11, R12 |
| F5 — prefix detection reads buffer residue | 6 | R3 |
| F20 — policy-bearing attributes arrive verbatim | 5 | R5 |
| F1 — `onselect`/`onsubmit` never classified as JS | 4 | R4 |
| F17 — the reset defeats JS suppression too | 4 | R2 |
| F19 — `onreadystatechange` never classified as JS | 2 | R4 |
| **Total** | **281** | |

Plus 30 `SUPPRESSED_UNINTENDED` (F7, F11 — values silently vanishing) and 44 `REJECTED` (F13, F18 —
ordinary templates taking the page down). Those are the availability half of the work, and they
matter for security indirectly: every silent drop and every 500 is a reason a developer reaches for
`$_x.asis()` and turns Canoe off for that value.

I re-derived each finding from the source rather than taking the review on trust. All 24 hold, at
the locations cited. Three additional observations that are not in the review are recorded in §5.

---

## 1. How to work this plan

- **One task per commit.** Each task below is sized to be reviewable in one sitting and to leave the
  tree green.
- **Every fix breaks the ledger, and that is the signal.** When a `KNOWN_VULNERABLE` case starts
  passing, `CanoeCorpusTest` fails. Update the verdict in
  `src/test/java/com/webkreator/qlue/view/canoe/corpus/CanoeCorpus.java` **in the same commit as the
  fix**, and set it by reviewing the new rendered output against the sink — never by copying whatever
  the run now produces. `VerdictEvaluator` derives the observed verdict independently, so a
  rubber-stamped entry fails rather than sitting there as unasserted data.
- **Tests that assert the *mechanism* of a defect must be inverted, not deleted.** For example
  `AttributePrefixTest.theResetTurnsSuppressionIntoHtmlEncoding` should become an assertion that the
  reset no longer exists and the name-derived context survives. Deleting it loses the regression net
  for the exact bug just fixed.
- **`./gradlew test` after every task** (about 7 seconds). `./gradlew browserTest` after any task in
  Phase A, B or C (about two minutes, Chromium only here).
- **The JaCoCo gate will move.** `build.gradle` carries per-method branch-coverage floors and an
  inventory of 37 branches proven dead, 26 of which *are* findings. Tasks R4 and R5 delete most of
  them. R27 reconciles the gate; until then, expect `canoeCoverageGate` to need its floors adjusted
  in the same commit as the fix that moved them, with the inventory comment updated to match.

### Ordering constraints — the four traps

1. **R2 (delete the `detectAttributePrefix()` reset) goes first.** It is the only task that closes
   F17, and F17 makes a *correctly recognised* `onclick` injectable. The `on*` prefix rule (R4) does
   not reach it: under F17 the name is already classified as JavaScript and the value scan throws the
   answer away afterwards.
2. **R24 (F12, the `#set` double-encoding) must land after R4 and R5.** The double encoding
   accidentally neutralises the largest vulnerability class in the review — a value routed through an
   interpolated `#set` into an unrecognised event handler arrives as literal `&#39;` and never closes
   the string literal. Fixing F12 first turns some templates from safe to injectable with no other
   change. `VelocityIntegrationTest.doubleEncodingAccidentallyNeutralisesAnUnrecognisedHandler` is the
   test that says so.
3. **R14 (settle `CTX_CSS`) must not precede R13 (fix `css()`).** Routing `ATTR_CSS` to a real CSS
   encoder before `css()` stops emitting unterminated hex escapes replaces a suppression with a
   defective escaper, which is worse than either. F23 shows a `style` value is decoded *twice*, so the
   recommendation in R14 is to keep suppressing and delete the dead arm.
4. **R5 (fail closed on unknown names) must land with R6 and R7**, or ordinary pages lose values with
   no diagnostic — the F11/F7 failure mode, at scale. Fail-closed is right; fail-closed with no
   allowlist and no escape hatch is how a security control gets switched off in production.

---

## 2. Tasks

### Phase A — Attribute routing

This phase is where the exploitable findings live. Three of its tasks close thirteen findings and
239 of the 281 `KNOWN_VULNERABLE` invocations.

---

**R1 — Add the regression harness for the routing change** — ✅ **DONE**
*Closes:* nothing. *Depends on:* nothing.
*Implemented as:* `RoutingTargetTest` — 9-row table asserting current behaviour, with the target
context and the flipping task recorded per row; reviewed, no defects.

Before touching `Canoe.java`, add a single test class that pins the *intended* end state of Phase A:
for a representative name from each category — a recognised handler, an unrecognised handler, a URL
attribute, `style`, a policy attribute, a plain-text attribute — assert the context Canoe should
reach and the encoder that implies. Mark it `@Ignore` with the reason, or assert the current
behaviour with a comment naming the task that flips each row.

The point is to have one place that says what Phase A is aiming at, rather than discovering the
target one ledger row at a time. Everything else in the suite asserts what Canoe *does*.

---

**R2 — `detectAttributePrefix()` must never widen the context** — ✅ **DONE**
*Closes:* F4, F17, and F24's exploitable path. *Depends on:* R1.
*Landed:* the reset is deleted; the method now only narrows. Ledger: KNOWN_VULNERABLE 281→239
(F4 38→0, F17 4→0), SUPPRESSED_BY_DESIGN 77→117, SAFE 564→566. `VerdictEvaluator` now
percent-decodes `javascript:` URL sinks before the JS judgement (HTML Standard's javascript:-URL
steps), keeping `residue.js-url-armed-buffer` correctly KNOWN_VULNERABLE under F5 — reviewed and
approved with the blast radius measured (exactly 2 invocations reach the decode path). Both
suites green; browser tier re-verified on Chromium.

`Canoe.java:224` unconditionally assigns `attributeContext = ATTR_HTML` before testing the five value
prefixes. Delete that line. Start from the name-derived context and let the method only ever *narrow*
it: assign `ATTR_JS`/`ATTR_DATA`/`ATTR_ACTIONSCRIPT` when a prefix matches, and leave the context
untouched when none does.

Three findings collapse onto that one line:

- `<div style="color:$c">` stops being HTML-encoded and goes back to being suppressed (F4).
- `<a onclick="f({a:1,b:'$id'})">` stops being HTML-encoded and goes back to being suppressed (F17) —
  a Critical-class outcome reached through an attribute Canoe classifies **correctly**.
- `<a href="$base$path">` stops downgrading `$path` from `url()` to `html()` when `$base` emitted a
  raw colon (F24). This is mitigation rather than root cause; R11 removes the raw colon itself.

*Done when:* `AttributePrefixTest.theResetTurnsSuppressionIntoHtmlEncoding`,
`.theResetAlsoDefeatsTheJavascriptSuppression`, `.theResetHappensEvenWhenNoPrefixMatches` and
`.theFirstColonInAValueDiscardsTheNameDerivedContext` are inverted;
`CssContextTest.theResetDowngradesEveryClassificationAndNotOnlyTheCssOne` and
`.thePropertyNameDecidesWhetherStyleIsSuppressed` are inverted; the 38 F4 and 4 F17 ledger rows are
re-verdicted; `ParserSteeringTest.attackerDataCanSteerTheAttributeContextByEmittingARawColon` no
longer steers.

*Watch for:* the value scan still sets `bufLen = -1` after the first colon. Keep that — it bounds the
scan — but make sure the early-out does not skip the narrowing case.

---

**R3 — Classify value prefixes by length-checked comparison, not by fixed buffer indices** — ✅ **DONE**
*Closes:* F5. *Depends on:* R2.
*Landed:* the five prefixes are compared as bounded strings against `bufLen`, and `buf` is cleared
on every reuse (new tag name, new attribute name, new attribute value). Ledger:
KNOWN_VULNERABLE 239→233 (F5 6→0), SUPPRESSED_BY_DESIGN 117→123. `BufferResidueTest`'s 20 rows
collapse to one outcome; the F5 tables in `AttributePrefixTest`, `CanoeStateMachineTest` and
`NearMissNameSweepTest` are inverted with their former names in the javadoc; `ConcurrencyTest`'s
cross-write instrument was F5 and is replaced by the parser state, with the old instrument kept as
the assertion that it no longer measures anything; `DomEquivalenceTest`'s F5 blind-spot row is
replaced by an F2 one. Coverage gate: Canoe 660/697 → 588/625 (94.69% → 94.08%, the same 37 missed
outcomes — 88 hand-unrolled branches became 16), no floor moved. Both suites green; browser tier
110/25/52/58 on Chromium.

`detectAttributePrefix()` reads `buf[4]`, `buf[5]` and `buf[10]` to confirm a prefix ended, but the
`TAG_ATTR_VALUE` path never writes a NUL terminator (`Canoe.java:933`, versus `Canoe.java:809` in
`TAG_ATTR_NAME` which does). `buf` is a 36-char field shared across the whole render and never
cleared, so whether `javascript:` is recognised depends on what an earlier, unrelated attribute name
left at index 10 — an 11-character `placeholder` upstream arms the bug, a 10-character name repairs
it, and reordering two elements changes the security of the page.

Compare the buffered prefix as a bounded string against the five known prefixes, using `bufLen` as
the length. While you are there, clear `buf` (or track a valid-length watermark) on every reuse:
the shared uncleaned buffer is the root cause of a whole class of these, and R4 removes the other
half of it.

*Done when:* `BufferResidueTest` — currently 20 rows split at exactly 10/11 characters — collapses to
one outcome for all 20; `AttributePrefixTest.aPrecedingAttributeNameDecidesWhetherJavascriptIs`
`Recognised`, `.theCurrentAttributeNameDecidesTheShortPrefixes` and `.theValueScanNeverWritesTheIndex`
`ItsOwnCheckReads` are inverted; the 6 F5 ledger rows are re-verdicted.

---

**R4 — Replace the `on*` table with a prefix rule** — ✅ **DONE**
*Closes:* F1, F2, F19. *Depends on:* R2.
*Landed:* the ~200-line table is gone; `setTagAttributeContext()` opens with "any name beginning
`on` is `ATTR_JS`" and compares the remaining eight names as bounded strings against `bufLen`, so
neither attribute-classification method reads a fixed buffer index any more. (One fixed-index read
survives elsewhere in the class: `TAG_NAME`'s `<script>`/`<style>` detection at `Canoe.java:608-618`.
It is residue-safe — the buffer is zero-filled on every `<` and the tag name is NUL-terminated —
and R8, which restructures tag-name handling anyway, is where it should become a bounded compare.) Ledger: KNOWN_VULNERABLE 233→135
(F2 92→0, F1 4→0, F19 2→0), SUPPRESSED_BY_DESIGN 123→224, SAFE 566→563 — the three `SAFE` rows
were the `ENTITY_PRE_ENCODED` overrides on `handler.onsubmit`, `.onselect` and `.onfocus`, which
were safe because `html()` escaped their ampersands and are suppressed like everything else now.
All 98 re-verdicted rows keep their `finding()` citation for traceability and lose their
`notBrowserObservable` flags, which the corpus only permits on `KNOWN_VULNERABLE` rows.
`CanoeStateMachineTest`'s 24-branch table and `EventHandlerMatrixTest`'s 21/91 split are inverted
rather than deleted, and both files now assert the halves *agree*; the new
`theSourceClassifiesHandlersByPrefixAndNotByName` reads `Canoe.java` and requires exactly one
`ATTR_JS` assignment and no handler name at all, which is what stops an allowlist coming back
under another name. `NearMissNameSweepTest`'s ~180 `on*` rows are inverted the same way and now
assert the stronger property that only the first two characters decide.
`everySpecEventHandlerAttributeHasACorpusCase` still passes and is permanently satisfiable.
Three tests that used an unrecognised handler as a *demonstration* rather than as their subject —
`CanoeTestSupportTest.decodedAttrExposesWhatAStringAssertionWouldMiss`,
`CanoeCorpusTest.theLedgerOracleDetectsAWrongVerdict` and two `DomEquivalenceTest` blind-spot rows
— moved their sink to F3's `xlink:href` and `meta content`, which is the same asymmetry at a sink
Phase A has not reached yet. `VelocityIntegrationTest.doubleEncodingAccidentallyNeutralisesAn`
`UnrecognisedHandler` is renamed `…AnUnrecognisedUrlAttribute` and half-inverted: **trap 2 in §1
still stands**, because the accident still covers F3's unrecognised URL names, which is R5 and R6's
half. Coverage gate: Canoe 588/625 → 247/259 (94.08% → 95.37%), `setTagAttributeContext()`
366/392 → 17/18 (93.37% → 94.44%); floors raised to 0.95 and 0.94, and the dead-branch inventory
falls from 37 outcomes to 12 — the 25 `onselect`/`onsubmit` outcomes are gone with the block, and
F7's unreachable `data` comparison is the only finding-related one left. Both suites green;
browser tier 103/18/45/58 on Chromium (130 tests, 0 failures, 2 skipped).

Delete the ~200 lines of hand-unrolled comparisons at `Canoe.java:334-539` and put a prefix rule at
the top of `setTagAttributeContext()`: any attribute whose name begins `on` is `ATTR_JS`. There is no
benign exception worth the risk.

Three of the 24 declared branches are dead and always were — `onselect` and `onsubmit` test `buf[0]`
inside a block that has already established `buf[0] == 'o'` (F1), and the `onreadystatechange` chain
spells `onredystatechange`, missing the `a` of "ready" (F19). Of the remaining 21 live branches, the
table catches 18 of the 94 event handler content attributes the HTML Standard defines (F2). A table
of 24 comparison chains of which 3 are silently dead is not a structure worth repairing one branch at
a time.

Do the classification on the name as a bounded string, not on `buf` indices — same reasoning as R3,
and it removes the name-side of the residue problem entirely.

*Done when:* `CanoeStateMachineTest.everyDeclaredOnStarBranchNameIsClassified` and
`.onreadystatechangeIsSpeltWithoutItsA` are inverted or retired with their reasoning moved to the new
test; `EventHandlerMatrixTest.everyUnrecognisedHandlerReachesTheJavaScriptParser` inverts across all
91 rows and `.theMatrixPartitionsIntoTwentyOneRecognisedNamesAndEverythingElse` becomes a partition of
115 into "all handlers" and "nothing"; the 92 F2, 4 F1 and 2 F19 ledger rows are re-verdicted.
`EventHandlerMatrixTest.everySpecEventHandlerAttributeHasACorpusCase` must still pass — it is the
guard against the HTML Standard widening the finding silently, and the prefix rule is what makes it
permanently satisfiable.

---

**R5 — Make `ATTR_HTML` unreachable for unknown attribute names**
*Closes:* F20, and the policy/markup half of F3. *Depends on:* R4. *Lands with:* R6, R7.

`Canoe.java:283` defaults every unrecognised attribute name to `ATTR_HTML` → `html()`. Invert it:
add `ATTR_UNKNOWN`, map it to `CTX_SUPPRESS`, and reach `ATTR_HTML` only through a **documented
allowlist of names known to be plain-text sinks** — `id`, `class`, `title`, `alt`, `value`, `name`,
`placeholder`, `label`, `aria-*`, `data-*`, and so on.

It has to be written as an allowlist of plain-text names, **not** as a denylist of dangerous ones, or
`sandbox`, `rel`, `integrity` and `nonce` end up on the wrong side of it. Those four are F20: the
HTML parser consumes their decoded value as a *directive*, so no encoding of `allow-same-origin`
means anything other than `allow-same-origin`, and suppression is not the preferred fix but the only
one. In particular **`nonce` must not be in the allowlist** — it is inert as text, which is true and
is the wrong test; an attacker who chooses the nonce can author a `<script nonce>` the policy admits.

Two things to build with it, or the control gets switched off in production:

- **An application-level extension point** — a Qlue property or a factory setter that adds names to
  the plain-text allowlist — so a developer who needs `<div my-widget-config="$x">` has somewhere to
  go other than `$_x.asis()`.
- **A diagnostic.** A suppressed value today is indistinguishable from an empty one. Log the
  attribute name at debug level when a reference is suppressed by the unknown-name rule, so the
  silent drop is discoverable.

*Done when:* the 5 F20 ledger rows and the markup/policy F3 rows are re-verdicted;
`AttributeNameMatrixTest` reports every unlisted name as `CTX_SUPPRESS`; the corpus rows
`plain.type`, `plain.target` and `plain.formtarget` — the three attributes deliberately excluded from
F20 — behave as the allowlist decides, and the decision for each is recorded.

---

**R6 — Extend the URL-bearing name set**
*Closes:* the URL half of F3. *Depends on:* R5.

`ATTR_URI` covers five names: `background`, `dynsrc`, `lowsrc`, `href`, `src`. Add the ones the
review enumerates and R5 would otherwise merely suppress: `action`, `formaction`, `poster`, `cite`,
`usemap`, `longdesc`, `codebase`, `manifest`, `ping`, `srcset`, `xlink:href`, and `data` (see R7).

Note `xlink:href` needs no tokenizer change — `isTagNameChar()` already accepts `:`
(`Canoe.java:200`), so it scans as one name and simply does not match `href` today.

`srcdoc` is the exception and should **suppress**, not URL-encode: its value is parsed as a whole HTML
document, so the correct encoding is a second full HTML encode, and a single-encoded value is
same-origin XSS. Suppression is the honest answer until someone wants to build double-encoding
deliberately.

*Done when:* the URL rows of F3's table in the ledger are re-verdicted; `UrlSinkTest` covers each new
name; `srcdoc` is `SUPPRESSED_BY_DESIGN` with the reasoning attached.

---

**R7 — Fix the `content` / `data` branch pair**
*Closes:* F7. *Depends on:* R5, R6.

`Canoe.java:297-308` carries the author's own `XXX` marker: two byte-identical comparison chains both
testing for `data`, so `data=` always resolves to `ATTR_CONTENT` (suppressed — a functional bug
developers route around) and there is **no test for `content` at all**.

Resolve it explicitly: `data` → `ATTR_URI` (it is `<object data>`, a URL), and `content` → suppressed
by default. `content` is only a URL on `<meta http-equiv="refresh">`, which needs the tag name and the
sibling attribute's value to decide — that is R10, and until R10 lands, suppressing is correct and
fail-safe.

*Done when:* `AttributePrefixTest.theSecondDataBranchIsUnreachable` is retired with its reasoning
moved; the `refresh.meta-content` corpus row is re-verdicted.

---

### Phase B — Tag-name awareness and URL origin policy

---

**R8 — Track the current tag name through attribute parsing**
*Closes:* nothing on its own. *Depends on:* Phase A.

Canoe discards the tag name the moment it starts parsing attributes, because `buf` is reused at
`Canoe.java:787`. Add a separate field holding the element name for the duration of the tag. No
behaviour change; this is the enabler for R9 and R10, and it is worth its own commit so that those
two are small.

---

**R9 — Reject off-origin and protocol-relative URLs in resource-loading sinks**
*Closes:* F6. *Depends on:* R8, R12.

`url()` is a scheme filter: it neutralises `javascript:` and `data:`, and passes
`//attacker.example/x.js` and `https://attacker.example/x.js` through byte for byte, because every
character in them is on its allowlist. Canoe cannot currently tell `<a href>` from `<script src>` —
measured, in `UrlSinkTest.everyElementGetsTheSameEncoderForTheSameAttributeName` — so both get the
same encoder. That test is the one this task has to break.

With the tag name available (R8), treat `src`/`href` on `script`, `iframe`, `object`, `embed`,
`link` and `base` as a distinct sink: reject protocol-relative and off-origin values by default, with
a configurable origin allowlist for the CDN case. `<a href>` and `<img src>` keep the ordinary URL
encoder; they are open-redirect and referrer-leak surfaces, not code-execution ones.

The bound worth keeping in the tests: only the **full-URL** and **path-prefix** substitution
positions reach the URL's authority. `href="/p/$data"`, `href="/search?q=$data"` and
`href="/page#$data"` are safe because of the template's literal text, not because of the encoder —
`theQueryPositionIsSafeBecauseOfTheTemplateAndNotBecauseOfTheEncoder` measures exactly that, and it
should keep passing unchanged.

*Done when:* the 37 F6 ledger rows are re-verdicted;
`UrlSinkTest.anOffOriginCdnBaseSurvivesIntoAScriptSrcByteForByte` is inverted.

---

**R10 — Handle `<meta http-equiv="refresh" content="…">`**
*Closes:* the refresh row of F3, completes R7. *Depends on:* R8.

`content` carries a URL on exactly one element/attribute-value combination. Either give that
combination a URI context, or leave `content` suppressed and document it. Suppressing is the smaller
change and is already the R7 default; this task exists so that the decision is made deliberately
rather than by omission, since a forced redirect to an attacker origin is the outcome.

---

### Phase C — `HtmlEncoder`

---

**R11 — Delete the `uriPattern` scheme passthrough**
*Closes:* F24 at the root, F15(a), F15(e). *Depends on:* nothing. *Lands with:* R12.

`HtmlEncoder.java:187-195` matches `^(https?://)([^/]+)(/.*)?$` and appends group 1 **unencoded**.
That is the only path in the whole component that emits a raw colon, and Canoe's value scan reads
that colon as a prefix delimiter and re-runs `detectAttributePrefix()` — which is F24, the
counterexample to the review's own corollary that attacker data can never steer the parser.

The same three-group match is also why an explicit port (`https://host:8443/`) becomes
`https://host%3A8443/` and every IPv6 literal is destroyed: the host is escaped with the rules that
apply to a path.

Delete the special case. If absolute URLs must survive — and they must, or `<a href="$absoluteUrl">`
breaks — that is R12, and the two should land together or in immediate succession. **Do not ship R11
alone**: on its own it percent-encodes the colon of every legitimate absolute URL.

*Done when:* `ParserSteeringTest.onlyTheUriContextCanEmitARawColon` becomes "no context can emit a raw
colon"; `TemplateFuzzTest.isTheKnownColonSteering` stops matching and the fuzzer's assertion that the
count is non-zero is inverted to an assertion that it is zero;
`HtmlEncoderUrlTest.anExplicitPortIsDestroyed` and `.everyIpv6LiteralIsDestroyed` are inverted.

---

**R12 — Rewrite `url()` to parse the URL and encode each component by its own rules**
*Closes:* F15(a–e), completes F24, supports R9. *Depends on:* R11.

The private worker at `HtmlEncoder.java:206-231` allows `a-z A-Z 0-9 / . - # ? =`, percent-escapes a
Java `char` directly for anything up to 255, and substitutes a literal `?` above that. Five ordinary
inputs are corrupted:

| Input | Today | Effect |
|---|---|---|
| `https://host:8443/path` | `https://host%3A8443/path` | the URL does not parse — every link with a port is dead |
| `/search?q=hello&lang=en` | `…q=hello%26lang=en` | `&` stops separating parameters |
| `a%20b` | `a%2520b` | correctly pre-encoded input is encoded again |
| `/search/<CJK>/results` | `/search/?/results` | the substituted `?` is on the allowlist, so the path becomes a query string |
| `https://[::1]/x` | `https://%5B%3A%3A1%5D/x` | every IPv6 URL is destroyed |

Two things fix all five: **UTF-8 encode and percent-escape per byte** (not per `char`), and **split
the URL into scheme, authority, path, query and fragment and apply each component's own rules**,
emitting the scheme separator from the encoder's knowledge of the parse rather than copying it out of
the input. The second half is what keeps F24 closed permanently rather than by accident.

Prefer `java.net.URI` over another hand-rolled matcher, and reject any scheme not on a short
allowlist (`http`, `https`, `mailto`, and relative references) rather than pattern-matching for the
dangerous ones.

*Done when:* all five `HtmlEncoderUrlTest` corruption tests are inverted;
`CanoeCorpusTest.urlEncodingAccidentsThatMakeOffsiteVectorsSafe` is re-examined — it pins
neutralisations that happen *by accident* today, and after this task they must happen by design or
the test is recording luck.

---

**R13 — Fix `js()` and `css()`**
*Closes:* F16. *Depends on:* nothing.

Both are reachable from templates **today** through `$_x.js(…)` and `$_x.css(…)`, because
`HtmlEncoder implements QlueVelocityTool` and binds itself into every context as `_x`. They corrupt
real output now, and they are the encoders the commented-out code at `Canoe.java:1074-1081`
contemplates promoting to automatic use.

- `js()` (`HtmlEncoder.java:156-168`) emits `\u` + `hex(c >> 8)` + `hex(c)`, and `hex()` writes only
  the low byte of its argument — four hex digits for a code point that may have twenty-one bits. Every
  astral character silently becomes the BMP character sharing its low sixteen bits: U+10027 becomes an
  apostrophe, U+1005C a backslash, U+10000 a NUL. Emit a surrogate pair or the ES6 `\u{…}` form.
- `css()` (`HtmlEncoder.java:263-276`) emits two-digit hex escapes with no terminator, so `'a`
  becomes `'\27a'` — one character U+027A, not an apostrophe followed by `a`. Pad to six digits or
  append a terminating space. It also replaces every code point above U+00FF with a literal `?`,
  destroying any CSS string containing CJK, Cyrillic, Greek, Arabic, Hebrew or an emoji. Escape those
  as their own six-digit escape.
- Escape the backslash **first** in `css()`. F23 shows a `style` attribute is decoded twice — HTML
  references, then CSS escapes — so a backslash that reaches the CSS tokenizer is an escape
  introducer, not an inert character.

*Done when:* `HtmlEncoderTest.jsTruncatesAstralCodePointsToTheirLowSixteenBits` and
`.cssHexEscapesAreUnterminatedAndSwallowTheNextCharacter` are inverted; the allowlist sweeps
`jsPassesThroughOnlyAlphanumerics`, `cssPassesThroughOnlyAlphanumerics` and
`everyJsEscapeIsAFixedWidthHexForm` still pass or are updated deliberately.

---

**R14 — Settle `CTX_CSS`**
*Closes:* F21. *Depends on:* R13.

`encode()` maps six contexts; `currentContext()` can produce five. `ATTR_CSS` is grouped with
`ATTR_DATA`, `ATTR_CONTENT` and `ATTR_ACTIONSCRIPT` and returns `CTX_SUPPRESS`, so the `CTX_CSS` arm
of `encode()` is dead code and the commented-out line inside it would change nothing if uncommented —
while its `CTX_JS` twin, in the same apparently symmetrical edit, would take effect immediately.
Half live, half dead, with no diagnostic either way.

**Recommendation: keep suppressing and delete the trap.** Remove the `CTX_CSS` constant and its
`encode()` arm, delete both commented-out lines, and put the reasoning in a comment: Canoe's design
refuses to interpolate into CSS, F23 shows a `style` value passes through three parsers in series, and
a CSS encoder that is correct against all three is a project, not a line. If the project is wanted
later, R13 is its precondition and this task is where the decision is recorded.

*Done when:* `AttributeNameMatrixTest.currentContextCanNeverReturnCtxCss` — which asserts the claim
three ways, including against the source text — is retired with its reasoning moved into the new
comment, and the six-context table in the review's "systemic flaw" section is corrected to five.

---

### Phase D — Writer and tokenizer fidelity

Nothing in this phase is exploitable today. Each item is either an availability defect that takes a
page down, or a latent defect that becomes exploitable the moment an encoder in Phase C stops
suppressing.

---

**R15 — Fix `write(char[], int, int)`**
*Closes:* F9. *Depends on:* nothing.

`Canoe.java:174` loops `for (i = offset; i < len; i++)` where it means `i < offset + len`, so the
parser sees only the first `len - offset` characters and the number that escape it is exactly the
offset. At `offset >= len` **nothing** is parsed and everything is written: the state machine freezes
and every later reference is encoded for a stale context. The error path is wrong the same way —
`len - (len - i)` is `i`, an absolute index passed as a length.

Not reachable through Velocity today: every inherited `Writer` default funnels to `write(cbuf, 0, n)`
and the engine's render path uses only the one-argument forms. But `Canoe` is a public `Writer` with
no documented restriction, and a parser whose safety depends on nobody calling a standard method with
a non-zero offset is one refactor from failing open.

Fix to `i < offset + len`, and the error path to `writer.write(cbuff, offset, i - offset)`.

*Done when:* `ChunkInvarianceTest`'s measured desync count through `write(char[], off, len)` goes from
**243 of 275** corpus templates to zero — the test already states it that way.

---

**R16 — Fix `COMMENT_CLOSE_2`**
*Closes:* F14. *Depends on:* nothing.

`Canoe.java:666-672` drops back to `COMMENT` on a third `-`, so `<!--a--->` never closes and every
reference for the rest of the page silently renders empty. The HTML Standard's comment-end state stays
in comment-end. Stay in `COMMENT_CLOSE_2` on `-`.

*Done when:* `CanoeStateMachineTest.aCommentEndingInThreeDashesNeverCloses` is inverted.

---

**R17 — Fix `SCRIPT_END` and `CSS_END`**
*Closes:* F10. *Depends on:* nothing.

`Canoe.java:947-958` matches `/script` and immediately leaves script data state with no check that the
next character is whitespace, `/` or `>`, so `</scriptfoo>` closes the element for Canoe and not for
the browser. The converse desync exists too: `<script>x = 1 <</script>` mismatches on the second `<`
and returns to `SCRIPT` **without re-processing that character**, suppressing the rest of the page.
`CSS_END` (`Canoe.java:967-978`) is identical.

Require a delimiter after the name, and re-process the mismatching character rather than dropping it.

Not attacker-reachable today — no encoder can emit a raw `<` — but the reachability argument for the
forward desync rests on the *template's* literal text being a JavaScript syntax error, which is a
weaker guarantee than the one the review states, and it is the first thing to break if `CTX_JS` ever
stops suppressing.

*Done when:* both halves of `ScriptAndStyleElementTest` are inverted, including
`bothDesyncsHaveExactCssTwins`; `onlyTemplateTextCanCauseADesync` still passes.

---

**R18 — Fix the DOCTYPE precondition**
*Closes:* F18. *Depends on:* nothing.

`tagCount++` runs for every `<` in `HTML` state and `COMMENT_OPEN_OR_DOCTYPE` demands `tagCount == 1`,
so a licence header or generator stamp above the DOCTYPE — legal HTML, common in template files —
takes the whole page down with `DOCTYPE declaration must be at the beginning`. The check wants "no
*element* has been emitted yet", not "no `<` has been seen yet". Track that instead.

*Done when:* `CanoeRobustnessTest.aCommentBeforeTheDoctypeMakesTheDoctypeIllegal` is inverted.

---

**R19 — Handle `TAG_ATTR_VALUE_BEFORE` in `currentContext()`**
*Closes:* F11. *Depends on:* Phase A.

`<a href=$x>` inserts the reference while the parser is still in `TAG_ATTR_VALUE_BEFORE` — the quote
that would advance it never arrives — and `currentContext()` has no case for that state, so it falls
to `CTX_SUPPRESS` and the value silently vanishes. `<a href=/p/$y>` works, because the `/` gets it
into `TAG_ATTR_VALUE` first. Only a reference immediately after `=` is dropped.

Treat an unquoted value as its name-derived context, or raise an encoding error. Do not leave it
silent: this is the failure mode that pushes developers to `allowDirectOutput()` + `$_x.asis()`,
which turns Canoe off for that value entirely.

*Done when:* the `unquoted-after-equals` corpus row moves off `SUPPRESSED_UNINTENDED`.

---

**R20 — Triage the template-rejection table**
*Closes:* the availability rows of F13's table. *Depends on:* R21.

Five ordinary inputs raise an encoding error, and per F13 each is currently an unhandled 500:

| Input | Error | Verdict to reach |
|---|---|---|
| `<br/>` | `Invalid character after tag name` | **Bug — fix.** A `/` immediately after a tag name is valid HTML. `<br />` works; only the no-space form fails. |
| `<p>5 < 6</p>` | `Tag name too short` | Decide. Strict is defensible (it is a template-authoring error), but it must fail at build or dev time, not at request time. |
| a 37-character tag or attribute name | `Tag name too long` / `Attribute name too long` | `MAX_TAGNAME_LEN` is 36 and the buffer is shared. Raise the cap or grow the buffer; custom-element and framework attribute names exceed it routinely. |
| `</ p>`, `</>` | `Tag name too short` | Keep. |
| a C0 control in body text | `Invalid character detected in output` | Keep. |

Fix the first, size the third, and record the decision for the rest. Whatever is decided, R21 has to
land first so the failure is a diagnosable error rather than a 500 on a half-written response.

---

### Phase E — Framework integration, documentation, guardrails

---

**R21 — Make encoding errors catchable**
*Closes:* F13. *Depends on:* nothing.

`VelocityViewFactory.java:218-224` tests `e.getMessage().startsWith(Canoe.ERROR_PREFIX)` on the
**top-level** exception, but Velocity always wraps: the production `Template.merge()` path yields
`IO Error rendering template '…'`. The `[Encoding Error]` branch has therefore never run, and every
encoding error propagates as an unhandled 500 with whatever Canoe had already flushed sitting in the
response.

Have `Canoe.raiseError()` throw a `CanoeEncodingException extends IOException`, and match on the cause
chain rather than on a message prefix. Then decide the recovery, because appending `[Encoding Error]`
to a response that already contains an unterminated element is not a recovery: failing the request
outright, or truncating to the last known-good tag boundary, are both more honest.

*Done when:* `CanoeRobustnessTest.noErrorCanoeRaisesIsSwallowedInProduction` — which drives every
input in the rejection table through the real `render()` via `ProductionRenderProbe` — is inverted.

---

**R22 — Configure the `class` resource loader in the base factory**
*Closes:* F22. *Depends on:* nothing.

`buildDefaultVelocityProperties()` declares `resource.loader.class` in the loader list
(`VelocityViewFactory.java:76`) and configures that loader's *caching*
(`VelocityViewFactory.java:97`), but never sets `resource.loader.class.class`. Velocity 2.4.1 ships no
default, so an engine built from the base class's own properties fails at `init()`. The only shipped
subclass that works is `ClasspathVelocityViewFactory`, which supplies the key in its override — so a
subclass that does exactly what the class comment invites cannot start.

One line: set `resource.loader.class.class` to
`org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader` in the base class, and let the
subclass keep overriding it with the non-caching variant.

*Done when:* `ViewFactoryRenderTest.theBaseFactorysDefaultPropertiesDeclareAClassLoaderItNever`
`Configures` is inverted — its last assertion already builds an engine from those properties and
requires `init()` to succeed once the key is present.

---

**R23 — Make the `$_x` bypass recognise formal notation**
*Closes:* a footgun recorded in F12's notes. *Depends on:* nothing.

`CanoeReferenceInsertionHandler` matches the literal prefixes `$_x.` and `$!_x.`
(`CanoeReferenceInsertionHandler.java:29-33`). Velocity's formal notation — `${_x.asis($data)}` and
`$!{_x.asis($data)}` — starts with neither, so the bypass silently does not apply and the output is
byte-identical to never having called the tool.

Fail-safe, but silent, and the developer's next move is to work around it. Either match the formal
forms too, or detect them and raise a diagnostic. Matching is the smaller change and the one
developers expect.

*Done when:* `VelocityIntegrationTest.formalNotationSilentlyDefeatsTheBypassBecauseThePrefixIs`
`MatchedLiterally` is inverted.

---

**R24 — Encode `#set` references where they are output, not where the `#set` ran**
*Closes:* F12. *Depends on:* **R4 and R5 — see trap 2 in §1.**

`referenceInsert()` queries `qlueWriter.currentContext()`, the state of the main output stream at that
instant. Velocity renders interpolated string literals into an internal writer, so
`#set($msg = "Hello $name")` encodes `$name` for wherever the template happened to be when the `#set`
ran, and encodes it again when `$msg` is printed: `data = "<b>"` renders as
`&amp;lt&#59;b&amp;gt&#59;`, visibly double-encoded. Only *interpolated string literals* are affected;
a plain `#set($u = $data)` does not fire the handler at all.

The likely shape of the fix is for the handler to detect that it is being called for a nested render
and defer — Velocity's `InternalContextAdapter` knows which node is being evaluated — or for Canoe to
expose "the context this writer will be in when the value is finally written". Investigate before
committing to an approach; this is the least mechanical task in the plan.

**Do not do this before R4 and R5.** The double encoding accidentally neutralises F2's entire class,
so fixing it first turns some templates from safe to injectable with no other change.

*Done when:* `VelocityIntegrationTest.doubleEncodingAccidentallyNeutralisesAnUnrecognisedHandler` is
retired *because its precondition is gone*, not because F12 was fixed under it.

---

**R25 — Correct the documentation**
*Closes:* the documentation half of F8. *Depends on:* Phases A–D, so the docs describe the fixed
component.

`README.md:11` promises "automatic context-sensitive output encoding to prevent XSS" and
`qlue_user_guide.md:22` promises "Built-in XSS defence". The only honest statement of scope ever
written lives in a demo page deleted at commit `6d4cfcc`. Neither `$_x`, `allowDirectOutput()`, the
JS/CSS suppression behaviour, nor the external-content limitation is documented anywhere a user of
the framework would find it.

Write, in both files: what is encoded and with which encoder; what is suppressed and why; that
suppression is silent and how to detect it; what is *not* covered (external content inclusion, and
whatever R9's origin policy ends up not covering); and how `$_x` and `allowDirectOutput()` work,
including the formal-notation trap if R23 does not close it.

---

**R26 — Drive the ledger to zero and hold it there**
*Closes:* the scoreboard. *Depends on:* everything above.

When the last `KNOWN_VULNERABLE` entry is re-verdicted, add a CI assertion that the count **is** zero
and that any future `KNOWN_VULNERABLE` entry must cite a finding that exists in the review document.
The suite's own design note says a ledger rots into a rubber stamp; the count going back above zero
without a finding attached is what that rot looks like from the outside.

Regenerate `build/reports/canoe/matrix.md` and record the before/after in the review document as a
closing addendum.

---

**R27 — Reconcile the coverage gate**
*Closes:* build hygiene. *Depends on:* R4, R5.

`build.gradle` carries branch-coverage floors (`Canoe` class 0.94, `setTagAttributeContext` 0.93,
`reallyProcessChar` 0.97) and a comment inventorying 37 branches proven unreachable, 26 of which are
findings — 25 of those being the `onselect`/`onsubmit` block alone. R4 deletes that block, R5 deletes
the `data`→`ATTR_URI` chain, and several `raiseError` arms move. Recompute the floors, rewrite the
inventory to list only what is genuinely unreachable in the fixed code, and keep the rule the comment
states: an unreached branch in Canoe is a security decision nobody tested.

---

**R28 — Re-confirm in a browser, on more than one engine**
*Closes:* verification. *Depends on:* Phases A–C.

The browser tier ran here with Chromium only; Firefox and WebKit skipped with the reason attached, so
everything the plan says about cross-engine divergence is **unmeasured**. Install the other two
engines and re-run `BrowserCorpusTest.theBrowserAgreesWithTheLedger` against the fixed component. The
rows worth watching are F1, F2, F3 (`srcdoc`, `xlink:href`), F4 and F23 — they all turn on
character-reference decoding order in a real HTML parser, which is worth seeing rather than reasoning
about.

Two known gaps to close while you are there: F20's `nonce` row has no browser demonstration (it needs
a page with an author nonce and a real CSP), and §A.3 of the test plan is missing `onbegin` and
`onrepeat`, the SVG animation siblings of `onend`.

---

## 3. Traceability

| Finding | Severity | Task(s) |
|---|---|---|
| F1 — `onselect`/`onsubmit` dead branch | Critical | R4 |
| F2 — `on*` allowlist misses 76 of 94 | Critical | R4 |
| F3 — URL/markup/refresh attributes unrecognised | Critical | R5, R6, R7, R10 |
| F4 — prefix scan discards the name-derived context | High | R2 |
| F5 — prefix detection reads buffer residue | High | R3 |
| F6 — `url()` is a scheme filter, not an origin filter | High | R9 (with R8, R12) |
| F7 — `content` branch tests for `data` | Medium | R7 |
| F8 — no tests, no docs, no threat model | Medium | R25 (tests: already delivered) |
| F9 — `write(char[],int,int)` length/end confusion | Low (latent) | R15 |
| F10 — `SCRIPT_END` accepts `</scriptfoo>` | Low (latent) | R17 |
| F11 — unquoted attribute references vanish | Low | R19 |
| F12 — `#set` interpolation uses the wrong context | Low | R24 |
| F13 — `[Encoding Error]` branch unreachable | Medium | R21, R20 |
| F14 — comment ending in three dashes never closes | Low | R16 |
| F15 — `url()` corrupts legitimate URLs five ways | Low | R11, R12 |
| F16 — `js()` truncates astral; `css()` escapes unterminated | Low | R13 |
| F17 — the reset defeats JS suppression | High | R2 |
| F18 — a comment before the DOCTYPE is illegal | Low | R18 |
| F19 — `onreadystatechange` dead branch | Critical | R4 |
| F20 — policy-bearing attributes arrive verbatim | Medium | R5 |
| F21 — `currentContext()` can never return `CTX_CSS` | Low (latent) | R14 (after R13) |
| F22 — base factory declares an unconfigured loader | Low | R22 |
| F23 — `style` values are decoded twice | Low | R2 closes the exposure; R13, R14 record the rest |
| F24 — `url()` emits a raw scheme colon | Medium | R11, R12 (R2 mitigates) |

**Three tasks close thirteen findings.** R2 closes F4, F17 and F24's exploitable path on one deleted
line. R4 closes F1, F2 and F19 by deleting 200. R5 closes F3's policy half and F20 by inverting a
default. If the work has to stop early, stop after R5.

---

## 4. Suggested order

```
R1  harness
R2  delete the reset                    <- highest impact, one line
R3  length-checked prefix compare
R4  on* prefix rule
R5  fail closed on unknown names        }
R6  URL-bearing name set                } land together
R7  content/data pair                   }
-------------------------------------------- exploitable surface closed here
R11 delete uriPattern passthrough       }
R12 rewrite url() per component         } land together
R8  track the tag name
R9  origin policy for resource sinks
R10 meta refresh
R13 js() and css()
R14 settle CTX_CSS
-------------------------------------------- encoders correct here
R15 write(char[],int,int)
R16 COMMENT_CLOSE_2
R17 SCRIPT_END / CSS_END
R18 DOCTYPE precondition
R19 TAG_ATTR_VALUE_BEFORE
R21 CanoeEncodingException              <- before R20
R20 rejection-table triage
-------------------------------------------- tokenizer faithful, pages stop dying here
R22 resource loader key
R23 formal-notation bypass
R24 #set context                        <- must be after R4 and R5
R25 documentation
R26 ledger to zero + CI guard
R27 coverage gate
R28 browser re-confirmation
```

---

## 5. Three observations not in the review

Recorded here rather than as new findings, because none is exploitable and each is small enough to
fold into a task above.

1. **`Attribute name too long` is a second length cap with the same 36-character limit**
   (`Canoe.java:800`). The review's F13 table lists `Tag name too long` but not its attribute
   sibling, which is the one a real template is likelier to hit — `data-*` attribute names in modern
   frameworks routinely exceed 36 characters, and each one is a 500. Folded into R20.

2. **`isTagNameChar()` accepts any Unicode letter** (`Canoe.java:196`, via `Character.isLetter`),
   where the HTML tokenizer accepts only ASCII. Not reachable by attacker data — no encoder emits a
   character that can start a tag name — but it is another place where the state machine is not a
   faithful model of the tokenizer, the same class as F10 and F14. Worth a comment at minimum; worth
   restricting to ASCII while R17 is open.

3. **Residue false positives in `detectAttributePrefix()` fail closed, which is why R3 is not
   urgent.** Because the method can only assign `ATTR_JS`, `ATTR_DATA` or `ATTR_ACTIONSCRIPT` — all
   of which suppress — a stale buffer can only ever cause a *spurious* prefix match, never a missed
   dangerous one in the widening direction. The dangerous direction is the missed match (F5) and the
   unconditional reset (F4/F17), which is why R2 leads and R3 follows.

---

## 6. What this plan does not address

- **Cross-engine browser divergence.** One engine has ever run against this component. R28 opens it;
  until then every browser-tier conclusion is a single-engine observation.
- **`srcdoc` double-encoding.** R6 suppresses it. If an application genuinely needs to interpolate
  into `srcdoc`, that is a feature to design, not a bug to fix.
- **DOM clobbering.** `<div id="$data">` is out of scope by the review's own criteria — an `id` is a
  name in the document's namespace, not a directive a browser algorithm consumes — but it endangers
  other scripts on the page and no task here touches it.
- **The template as an attack surface.** The threat model throughout is that the attacker controls
  data and never the template. `$_x.asis()` and `allowDirectOutput()` remain unguarded by design.
- **`useAutoEscaping` is on by default and cannot be disabled through any Qlue property** — only by
  application code calling `setAutoEscaping(false)`. That is the right default and no task changes
  it, but nothing in the suite asserts that a misconfiguration cannot turn it off.
