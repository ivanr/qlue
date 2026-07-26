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
  **After R5–R7 the inventory is 11 outcomes and none of them is a finding** — the last one, F7's
  unreachable `data` comparison, went with the branch pair R7 resolved. `setTagAttributeContext()`
  is 8 branch outcomes and fully covered; `normalisePlainTextAttributeNames()` is gated too, because
  it is the guard that stops an application putting a suppressed name back on `html()`.

### Ordering constraints — the four traps

1. **R2 (delete the `detectAttributePrefix()` reset) goes first.** It is the only task that closes
   F17, and F17 makes a *correctly recognised* `onclick` injectable. The `on*` prefix rule (R4) does
   not reach it: under F17 the name is already classified as JavaScript and the value scan throws the
   answer away afterwards.
2. **R24 (F12, the `#set` double-encoding) must land after R4 and R5.** — **discharged; R24 is
   unblocked.** The double encoding accidentally neutralised the largest vulnerability class in the
   review — a value routed through an interpolated `#set` into an unrecognised event handler arrived
   as literal `&#39;` and never closed the string literal. Fixing F12 first would have turned some
   templates from safe to injectable with no other change.
   `VelocityIntegrationTest.doubleEncodingAccidentallyNeutralisesAnUnrecognisedHandler` was the test
   that said so; it is now `.doubleEncodingNoLongerCoversAnyClassOfMissingClassification`, and it
   records what happened. R4 closed the handler half and R5 with R6 closed the URL half, so **no
   class of missing classification is behind the accident any more**: there is no sink left where
   `html()` output is decoded once into a second parser. What remains, asserted rather than assumed,
   is that F12 still masks **F6** — an off-origin URL survives `url()` on the direct path and is
   mangled on the `#set` path — so R24 exposes nothing the ledger does not already record as
   `KNOWN_VULNERABLE` by its direct route. Landing R24 before R9/R11/R12 is now a judgement call
   about F6 rather than an ordering constraint.
3. **R14 (settle `CTX_CSS`) must not precede R13 (fix `css()`).** Routing `ATTR_CSS` to a real CSS
   encoder before `css()` stops emitting unterminated hex escapes replaces a suppression with a
   defective escaper, which is worse than either. F23 shows a `style` value is decoded *twice*, so the
   recommendation in R14 is to keep suppressing and delete the dead arm.
4. **R5 (fail closed on unknown names) must land with R6 and R7**, or ordinary pages lose values with
   no diagnostic — the F11/F7 failure mode, at scale. Fail-closed is right; fail-closed with no
   allowlist and no escape hatch is how a security control gets switched off in production.
   **Honoured:** the three landed together, with a plain-text allowlist that keeps every ordinary
   attribute a page uses, an `aria-*`/`data-*` prefix rule, a per-factory extension point reachable
   from application code or a Qlue property, and a debug diagnostic naming every attribute the rule
   drops. The cost is still real and is recorded rather than hidden — `AttributeNameMatrixTest`
   `.everyNameOutsideTheAllowlistsIsSuppressed` is the list of shapes that now render empty.

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

**R5 — Make `ATTR_HTML` unreachable for unknown attribute names** — ✅ **DONE**
*Closes:* F20, and the policy/markup half of F3. *Depends on:* R4. *Landed with:* R6, R7.
*Landed:* the default is `ATTR_UNKNOWN` → `CTX_SUPPRESS`, and `ATTR_HTML` is reached only through a
documented allowlist of plain-text names — the ordinary text, form, table, media and link
attributes, plus the `aria-*` and `data-*` families, each argued in `Canoe.PLAIN_TEXT_ATTRIBUTE_`
`NAMES`' javadoc. `sandbox`, `rel`, `integrity` and `nonce` are off it (F20), and so are
`http-equiv`, `charset`, `crossorigin`, `referrerpolicy` and `is`, which are directives by the same
criteria. `type`, `target` and `formtarget` were re-decided from the other end — "is this a
plain-text sink we are willing to list" rather than "is this a policy sink" — and all three are
**on** the allowlist with the reasoning recorded on their corpus rows; `plain.type`,
`plain.target` and `plain.formtarget` are unchanged, which is the load-bearing half of the trade.
Two mitigations ship with it: `VelocityViewFactory.addPlainTextAttributes(...)` and the
`qlue.canoe.plainTextAttributes` property, per factory and never static, validated by
`Canoe.normalisePlainTextAttributeNames()` which **refuses** any name whose suppression is a
recorded decision — F3's and F20's names, anything beginning `on`, the URL set, and the five
URL-bearing names R6 chose to suppress rather than route (`imagesrcset`, `xml:base`, `archive`,
`classid`, `profile`), because the allowlist grants `html()` and that is *weaker* than the `url()`
they were denied — loudly at configuration time, from the `Canoe(Writer, Set)` constructor as well
as from the factory, so the guard does not depend on which door a caller uses; and an slf4j debug
diagnostic in `currentContext()` naming the attribute whenever a reference is dropped by the
unknown-name rule. Ledger: KNOWN_VULNERABLE 135→61, SUPPRESSED_BY_DESIGN 224→257,
SUPPRESSED_UNINTENDED 30→27, SAFE 563→613, REJECTED 44 unchanged; 996→1002 invocations, the six
being `url.xlink-href` widening from one payload family to three now that it belongs in the URL
group. Per finding: F3 93→0, F20 5→0, F7's 3 → moved to F6, and F6 37→61 (see R6). Coverage gate:
Canoe 247/259 → 265/276 (95.37% → 96.01%), `setTagAttributeContext()` 17/18 → 8/8; floors 0.95→0.96
and 0.94→0.99, a new floor on `normalisePlainTextAttributeNames()`, and the dead-branch inventory
falls from 12 outcomes to 11 with no finding left in it. Browser tier: the relevant subset moves
103/18/45/58 → 63/0/19/44 — the not-browser-observable axis is empty for the first time, and every
row that must fire is F6. Both suites green; browser tier re-verified on Chromium (91 tests, 0
failures, 2 skipped — Firefox and WebKit are not installed here).

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

**R6 — Extend the URL-bearing name set** — ✅ **DONE**
*Closes:* the URL half of F3. *Depends on:* R5.
*Landed:* `ATTR_URI` is seventeen names — the original five plus `action`, `formaction`, `poster`,
`cite`, `usemap`, `longdesc`, `codebase`, `manifest`, `ping`, `srcset`, `xlink:href` and `data`
(R7). `xlink:href` needed no tokenizer change, as predicted. `srcdoc` **suppresses**, with the
double-encoding reasoning attached to `markup.srcdoc` and to `URL_ATTRIBUTE_NAMES`' javadoc; so do
`imagesrcset`, `xml:base`, `archive`, `classid` and `profile`, which R6 deliberately did not list —
suppression is strictly stronger than `url()` and no ordinary template interpolates into them, and
the decision is recorded on each row. All five are in `NAMES_THAT_MAY_NOT_BE_ADDED` as well, so the
stronger answer cannot be swapped for the weaker one through configuration: the plain-text allowlist
grants `html()`, and `html()` on a URL sink is F3. `srcset`'s list syntax is the one accepted availability cost:
`url()` percent-encodes the comma and the space, so a multi-candidate value loses its descriptors,
and the alternative is a feature rather than a default. **The honest half of the result:** every
name routed to `url()` inherits F6's off-origin passthrough, so the 93 F3 rows become SAFE against
script schemes and `KNOWN_VULNERABLE` **citing F6** against the two off-origin payloads — F6's count
rises 37→61 as F3's falls 93→0. The ledger records the routing defect as closed and the encoder
defect as open, which is what it is; R9, R11 and R12 own the rest.

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

**R7 — Fix the `content` / `data` branch pair** — ✅ **DONE**
*Closes:* F7. *Depends on:* R5, R6.
*Landed:* both branches are gone with the whole comparison chain — `data` is in the URL name set and
`content` is on no list, so R5's fail-closed default suppresses it. The `ATTR_CONTENT` constant went
with them, along with its `currentContext()` arm and the author's `XXX` marker; `ATTR_UNKNOWN`
occupies its slot. `AttributePrefixTest.theSecondDataBranchIsUnreachable` is retired as
`.theDataBranchPairIsResolved`, carrying the whole of F7's reasoning in its javadoc — the identical
guards, the first branch returning, the two consequences — and asserting R7's answer instead;
`AttributeNameMatrixTest.theSourceDeclaresExactlyTheNonHandlerBranchesTheMatrixExpects`, which parsed
the branch comments out of the source, is retired as `.theSourceDeclaresTheTwoNameListsTheMatrix`
`Expects` and now reads the two declared name sets, asserts they match the matrix, and requires the
`ATTR_CONTENT` constant and the `XXX` marker to stay gone. `attr.data-on-object` moves off
`SUPPRESSED_UNINTENDED` (F7's availability half) to the URL shape citing F6; `refresh.meta-content`
moves off `KNOWN_VULNERABLE` (F3's impact) to `SUPPRESSED_BY_DESIGN`, with the reason `content` is
not on the URL list written out: it is a URL on one element/attribute-value combination, Canoe
discards the tag name before attribute parsing begins, and routing every `content` attribute to
`url()` would percent-encode the prose in every meta description on the page. F7 now has no corpus
case and an entry in `MatrixReportTest.FINDINGS_WITHOUT_CASES` saying which tests carry it and why —
a case cites the finding its *current* verdict is about.

`Canoe.java:297-308` carries the author's own `XXX` marker: two byte-identical comparison chains both
testing for `data`, so `data=` always resolves to `ATTR_CONTENT` (suppressed — a functional bug
developers route around) and there is **no test for `content` at all**.

Resolve it explicitly: `data` → `ATTR_URI` (it is `<object data>`, a URL), and `content` → suppressed
by default. `content` is only a URL on `<meta http-equiv="refresh">`, which needs the tag name and the
sibling attribute's value to decide — that is R10, which weighed that machinery and kept the
suppression deliberately (see R10). Suppressing is correct and fail-safe.

*Done when:* `AttributePrefixTest.theSecondDataBranchIsUnreachable` is retired with its reasoning
moved; the `refresh.meta-content` corpus row is re-verdicted.

---

### Phase B — Tag-name awareness and URL origin policy

---

**R8 — Track the current tag name through attribute parsing** — ✅ **DONE**
*Closes:* nothing on its own. *Depends on:* Phase A.
*Landed:* a `tagName` field holds the element name, lower-cased, for the duration of the tag —
set when TAG_NAME completes (and on the SCRIPT_END/CSS_END paths that reach TAG without passing
TAG_NAME), cleared on `>` and on the next `<` so body text, script/style bodies, comments and
DOCTYPEs never see a stale name. No behaviour change (ledger unchanged); it is the value R9 and
R10 will read. As a bonus it retired the last fixed-index `buf[]` read — TAG_NAME's script/style
detection is now `tagName.equals(...)`. `TagNameTrackingTest` pins the field's lifecycle;
coverage floors moved down (Canoe 0.96→0.95, reallyProcessChar 0.97→0.96) purely because 13
always-covered comparison branches became 2, with no new dead branch.

Canoe discards the tag name the moment it starts parsing attributes, because `buf` is reused at
`Canoe.java:787`. Add a separate field holding the element name for the duration of the tag. No
behaviour change; this is the enabler for R9 and R10, and it is worth its own commit so that those
two are small.

---

**R9 — Reject off-origin and protocol-relative URLs in resource-loading sinks** — ✅ **DONE**
*Closes:* the code-execution half of F6. *Depends on:* R8, R12.
*Landed:* R8's `tagName` now decides the encoder for `src`/`href`/`data`. Six element/attribute
combinations — `<script src>`, `<iframe src>`, `<object data>`, `<embed src>`, `<link href>`,
`<base href>` (`Canoe.RESOURCE_LOADING_SINKS`) — route to a new `ATTR_URI_RESOURCE`/`CTX_URI_RESOURCE`
context and a new encoder, `HtmlEncoder.urlResource(input, allowlist)`. `urlResource()` is `url()`
plus an origin filter: it runs the value through `url()` (so every scheme rejection and per-component
encoding is inherited) and then rejects to the empty string any value whose **`url()` output**
introduces an authority whose host is not on a configured allowlist. "Off-origin" is defined
soundly at encode time as *specifies an authority at all* — Canoe never knows the deploying app's own
origin — so a protocol-relative `//host`, an absolute `scheme://host`, and the special-scheme
`scheme:host`/`scheme:/host` forms a browser reads as an authority are all rejected, while a relative
reference (`/path`, `path`, `?q`, `#f`) always passes. Checking the authority on `url()`'s output is
what makes it both catch the reviewer's `http:evil` case (asserted) and *not* over-reject the tricks
`url()` already neutralised — a backslash is a `%5C` path and a userinfo `@` is a `%40` forbidden
host char, so neither reaches a live authority. The CDN escape hatch mirrors R5:
`VelocityViewFactory.addTrustedResourceOrigins(...)` and the `qlue.canoe.trustedResourceOrigins`
property, per factory and never static, validated by `HtmlEncoder.parseTrustedOrigins` (a bad origin
throws at startup) and plumbed to Canoe via a third constructor arg; an entry is a host
(`cdn.example.com`) or an origin (`https://cdn.example.com`, optional `:port`).

**`<a href>` and `<img src>` keep `url()` by design** — an off-origin link is an open redirect and an
off-origin image is a referrer leak, not code execution — so **F6 does not reach zero**. Ledger,
every one of the 18 re-verdicted rows read against its sink: `KNOWN_VULNERABLE` **84→66**,
`SUPPRESSED_BY_DESIGN` **390→408**, `SAFE` 457, `SUPPRESSED_UNINTENDED` 27, `REJECTED` 44 (1002
invocations). The 18 R9 closed are the resource sinks: `url.script-src-prefix` (3),
`url.iframe-src` (3), `url.embed-src` (3), `url.link-href` (3), `url.base-href` (4, including the
`BASE_HIJACK` host) and `attr.data-on-object` (2). The **66 that remain are all F6 on
open-redirect/referrer/fetch-not-code surfaces** — `a href` (27), `img src`, `form action`,
`button formaction`, `video poster`, `a ping`, `blockquote cite`, `img srcset`/`usemap`/`longdesc`,
`table background`, `img dynsrc`/`lowsrc`, `a xlink:href`, `applet codebase`, `html manifest` — which
R9 scopes out by design. This is a residual, not a defect: it is an open redirect and a referrer leak,
not XSS, and it stays `KNOWN_VULNERABLE` citing F6 for the record, tracked by **R26** (drive the
ledger to zero / decide the acceptable residue) and re-confirmed cross-engine by **R28**. `<meta
http-equiv=refresh content>` (a forced navigation) is **R10**, still suppressed.
Tests: `UrlSinkTest.everyElementGetsTheSameEncoderForTheSameAttributeName` inverted to
`.theTagNameNowDecidesTheEncoderForSrcAndHref`, `.anOffOriginCdnBaseSurvivesIntoAScriptSrcByteForByte`
inverted to `.anOffOriginValueIsRejectedFromAScriptSrcByDefault`, three new allowlist tests (CDN host
survives, origin form pins the scheme, per-factory isolation),
`.theQueryPositionIsSafeBecauseOfTheTemplateAndNotBecauseOfTheEncoder` unchanged and still green; a
new `HtmlEncoderResourceUrlTest` covers `urlResource()` and `TrustedOrigin`; `TagNameTrackingTest`'s
"nothing consumes it yet" row inverted; the `<object data>` rows in `CanoeStateMachineTest` and
`AttributePrefixTest` moved to `CTX_URI_RESOURCE`; `ConcurrencyTest` learned to check a static `Map`;
`SinkSpecificBrowserTest.aBaseHrefHijackRetargetsLaterRelativeUrls` inverted to
`.aBaseHrefHijackIsClosedSoRelativeUrlsStayOnOrigin`; `BrowserCorpusTest`'s budget 72/28/44/0 →
62/18/44/0. Coverage: HtmlEncoder 230/232 → 315/320 (floor 0.99→0.98, three new dead `percentDecode`
guards inventoried); Canoe 251/262 against 0.95, same dead outcomes as after R8. Both suites green;
browser tier re-verified on Chromium (90 tests, 0 failures, 2 skipped — Firefox and WebKit are not
installed here).

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

**R10 — Handle `<meta http-equiv="refresh" content="…">`** — ✅ **DONE**
*Closes:* the refresh row of F3, completes R7. *Depends on:* R8.
*Landed:* the decision is **suppress**, taken deliberately, and no behaviour changed — `content` was
already `ATTR_UNKNOWN`/`CTX_SUPPRESS` under R5's fail-closed default (R7 confirmed the pair), and R10
is the point at which leaving it there is a reviewed decision rather than an R7 placeholder. The
`refresh.meta-content` ledger row stays `SUPPRESSED_BY_DESIGN` citing F3; its note is rewritten from
"suppressing is correct and fail-safe **until R10** does the sibling-attribute check" to R10's final
reasoning. No branch was added and no coverage moved (pure documentation of an existing suppression),
so `build.gradle`'s floors and dead-branch inventory are untouched. Both suites green; browser tier
re-verified on Chromium (Firefox and WebKit not installed here) — `SinkSpecificBrowserTest`
`.metaRefreshNoLongerNavigatesAnywhere` and `DetectorSelfTest`'s meta-refresh detector both green,
confirming the suppressed `content` reaches no sentinel origin.

**Why suppress, not a URI context.** `content` carries a URL on exactly one element/attribute-value
combination, `<meta http-equiv="refresh" content="N; url=...">`. Giving that combination a URI context
was investigated and rejected as substantial machinery for one attribute, on three counts:

1. **Sibling-attribute-value tracking Canoe does not have.** The URL is only present when a *sibling*
   attribute `http-equiv` has the value `refresh`. Canoe scans attributes one at a time and never
   retains a prior attribute's value — after the prefix scan it sets `bufLen = -1` and stops buffering
   — and the attributes can appear in either order (`content` may be scanned before `http-equiv`). So
   recognising the combination is not a tag-name check (R8 gives the tag name) but a whole
   sibling-attribute-value tracking facility, which would mean buffering attribute values and a
   second pass or deferred decision. The task brief's instruction was explicit: do not half-build
   sibling tracking. Building it fully is out of proportion to one attribute.
2. **`N; url=` parsing is incompatible with the per-reference encoding model.** Even with the
   combination recognised, the value is `<seconds>; url=<URL>` and only the URL portion is a
   navigation target. Canoe encodes each Velocity reference as an opaque whole against a single
   context (`CanoeReferenceInsertionHandler.referenceInsert()` → `Canoe.encode(value)`); it never sees
   where in the attribute value a reference sits relative to the literal `N; url=` prefix. In
   `content="$data"` the reference is the whole value; in `content="0;url=$data"` it is only the URL —
   and Canoe cannot tell which. There is no place to cut the prefix out.
3. **Routing every `content` to `url()` would corrupt ordinary pages.** `content` is prose on
   `<meta name="description">`, `<meta name="keywords">`, `<meta property="og:*">` and most other
   metas; `url()` would percent-encode the spaces and punctuation of every one. That is the F11/F7
   failure mode (silent value corruption) at scale, and it is what pushes a developer to `$_x.asis()`.

Suppression is fail-safe: a suppressed `content` renders empty, so the `<meta>` element remains but
carries no refresh target and no forced redirect occurs. A meta refresh that legitimately needs a
dynamic URL is a case for application code (a computed `Location` header, or `$_x.asis()` on a value
the application has itself validated), not silent interpolation. The reasoning is recorded in
`Canoe.URL_ATTRIBUTE_NAMES`' javadoc (the `content` bullet) and on the `tagName` field, which notes
that R10 considered reading it and deliberately did not.

**R28 note.** The refresh row turns on character-reference decoding in a real HTML parser — a browser
decodes the `content` value's entities before reading `N; url=`, which is exactly the asymmetry F3
exploited. Because the decision is *suppression*, there is no encoded output for that decoding to act
on: the value is empty in every engine, so R28's cross-engine concern (which is about how Firefox and
WebKit decode a *non-empty* encoded value) does not bear on this row. R28 should still keep the
meta-refresh browser case as a regression witness that the suppressed value stays empty cross-engine.

---

### Phase C — `HtmlEncoder`

---

**R11 — Delete the `uriPattern` scheme passthrough** — ✅ **DONE**
*Closes:* F24 at the root, F15(a), F15(e). *Depends on:* nothing. *Landed with:* R12.
*Landed:* the `uriPattern` field and its three-group passthrough are gone — the one path in the
component that emitted a raw colon. It went with the `url()` rewrite (R12) so that absolute URLs still
survive; on its own it would have percent-encoded the colon of every legitimate absolute URL, which
is why the two land together. The deletion also removed the last non-final static in either class
(`uriPattern` was mutable), which `ConcurrencyTest.everyStaticFieldIsFinalAndImmutable` now records by
the exemption for it being gone. `ParserSteeringTest.onlyTheUriContextCanEmitARawColon` became
`theOnlyRawColonAnyEncoderEmitsIsAnAllowlistedSchemeSeparator`: the literal "no colon at all" the
plan sketched is not reachable while an absolute `http(s)` URL survives — that is F6/R9 territory — so
the equivalent, stronger bound is asserted instead: `url()` emits a colon only as an
`http`/`https`/`mailto` scheme separator, from its parse, and a rejected scheme or a colon anywhere
else is suppressed or escaped. `TemplateFuzzTest`'s `isTheKnownColonSteering` exemption is deleted:
property 4 holds outright now, and the fuzzer's non-zero colon-steering assertion is inverted to
`assertEquals(0, …)` via a `steersTheParserViaAColon` probe that requires both a colon increase and a
context divergence — which is exactly the F24 signature and is never true.
`HtmlEncoderUrlTest.anExplicitPortIsDestroyed` and `.everyIpv6LiteralIsDestroyed` are inverted to
`.anExplicitPortSurvives` and `.everyIpv6LiteralSurvives`.

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

**R12 — Rewrite `url()` to parse the URL and encode each component by its own rules** — ✅ **DONE**
*Closes:* F15(a–e), completes F24, supports R9. *Depends on:* R11. *Landed with:* R11.
*Landed:* `url()` now detects a leading scheme, rejects anything off a `{http, https, mailto}`
allowlist to the empty string (so `javascript:`, `data:`, `vbscript:`, `view-source:` and every
unregistered scheme are neutralised by suppression, not by escaping one delimiter), and otherwise
splits the value into scheme / authority / path / query / fragment and encodes each component by its
own rules — percent-escaping **per UTF-8 byte**, passing an existing `%XX` through untouched, and
emitting the scheme separator from its parse rather than copying it from the input. Two deliberate
rules beyond a pure URL encoder: `&` is emitted as `&amp;` (it is the terminal encoder written
straight into an HTML attribute, so a raw `&` would let `&#106;avascript:` be reconstituted into a
scheme, while `&amp;` decodes back to a working query separator), and `@` is escaped in every path so
a value concatenated after a scheme-and-host base cannot introduce userinfo. All five F15 corruptions
are fixed — an explicit port, an IPv6 literal, a multi-parameter query, a pre-encoded value and a
non-Latin-1 path character all survive — and F24 is closed by design: the only raw colon `url()` can
now emit is an allowlisted scheme separator, which `detectAttributePrefix()` matches none of.

It remains a scheme filter and not an origin filter (F6): a protocol-relative or absolute off-origin
`http(s)` URL still passes through byte for byte, correctly parsed now but not origin-filtered — R9
owns that. The one accident R12 did not preserve is the old case-sensitive scheme regex: an uppercase
scheme is normalised now, so `HTTPS://attacker` is a real off-origin URL and is `KNOWN_VULNERABLE`
under F6 like its lowercase sibling.

All five `HtmlEncoderUrlTest` corruption tests are inverted; the rejected-scheme, uppercase-scheme and
by-design-neutralisation cases are rewritten around the allowlist;
`CanoeCorpusTest.urlEncodingAccidentsThatMakeOffsiteVectorsSafe` is re-examined as
`.urlNeutralisesOffsiteVectorsByDesign`, asserting the neutralisations through `url()` itself and
recording the uppercase flip. `UrlSinkTest`, `AttributeNameMatrixTest.hrefAndXlinkHrefReachTheSame`
`Encoder` and the two `SinkSpecificBrowserTest` scheme rows are updated from "colon escaped to %3A" to
"scheme rejected to empty". `VelocityIntegrationTest`'s F12 double-encoding row (trap 2) is adjusted to
the new byte pattern; its `assertNotEquals` — the authority does not survive the `#set` path — still
holds, so R24 is not unblocked by anything here.

**Ledger re-verdict (post-change totals, 1002 invocations):** `SAFE` 613→**457**, `KNOWN_VULNERABLE`
61→**84**, `SUPPRESSED_BY_DESIGN` 257→**390**, `SUPPRESSED_UNINTENDED` **27**, `REJECTED` **44**. The
133 rows that moved `SAFE`→`SUPPRESSED_BY_DESIGN` are the clean rejected-scheme payloads (five
`JS_URL` variants across the URL cases, plus the ten-character-then-colon length-stress payload); the
23 that moved `SAFE`→`KNOWN_VULNERABLE` are the uppercase-scheme off-origin rows, all citing **F6**.
Every one of the 84 `KNOWN_VULNERABLE` rows is F6, so R9 is still the only exploitable surface left.
Browser-relevant subset 63→**72**, must-fire 19→**28** (the nine uppercase KNOWN_VULNERABLE rows on
browser-relevant cases), unobservable **0**. Coverage: `HtmlEncoder` 171/172 → **230/232 (99.14%)**
against a 0.99 floor, with two dead outcomes inventoried (the private `css()` null guard and one
short-circuit outcome in `appendHierPart()`'s fragment guard); `Canoe` untouched. Both suites green;
browser tier re-verified on Chromium (100 tests, 0 failures, 2 skipped — Firefox and WebKit are not
installed here). **What remains:** R9 (reject off-origin/protocol-relative URLs in resource-loading
sinks, which needs the tag name from R8) closes the F6 rows; R28 re-confirms across Firefox and WebKit.

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

**R13 — Fix `js()` and `css()`** — ✅ **DONE**
*Closes:* F16. *Depends on:* nothing.
*Landed:* both defective encoders are corrected in place, and neither is wired into Canoe's automatic
path (that interplay is R14; the commented-out `Canoe.java:1074-1081` is untouched, `url()` is
untouched). **`js()`** now emits astral code points as a UTF-16 **surrogate pair** — two `\uXXXX`
escapes — chosen over the ES6 `\u{…}` form because a surrogate pair is safe in every JavaScript
version and in every string-literal context: `js(U+10027)` = `'𐀧'` (no longer an
apostrophe), `js(U+1F600)` = `'😀'`, `js(U+10000)` = `'𐀀'`. BMP output is
unchanged (`\xNN` below 0x80, `\uNNNN` to 0xFFFF), so every escape is still a fixed-width hex form and
`js()` still cannot terminate its string literal. **`css()`** now emits a backslash and **exactly six
hex digits** for every non-alphanumeric — six is the maximum a CSS hex escape consumes, so it is
self-delimiting and swallows nothing: `css("'a")` = `'\000027a'` (an apostrophe then `a`, not U+027A).
The code-point value is written directly, so an astral character is one escape with no surrogate
handling (`css(U+1F600)` = `'\01F600'`) and a non-Latin-1 character is escaped rather than replaced
with `?` (`css(U+4E2D)` = `'\004E2D'`). The backslash is itself escaped this way (`\` → `\00005C`), so
no raw backslash is ever emitted and none can combine with a following character even though a `style`
value is decoded twice, HTML references then CSS escapes (F23). A `hex6()` helper was added; the
old two-digit `hex()` is unchanged and still used by `js()`, `html()` and `url()`.

*Ledger/F16:* the corpus has **no** `$_x.js()`/`$_x.css()` row — `js()`/`css()` are unreachable from
`Canoe.encode()` (`CTX_JS`/`CTX_CSS` both suppress), so no verdict changed and no re-verdict was
needed. F16 stays tracked with reasoning in `MatrixReportTest.FINDINGS_WITHOUT_CASES` (still accurate:
the encoders are fixed but still not reachable from a corpus payload). One stale note on
`CanoeCorpus`'s `script.body-string-literal` row — which said "F16 shows `js()` is not fit for it" —
was rewritten to record that R13 fixed `js()` and that suppression remains a deliberate design choice.

*Tests:* in `HtmlEncoderTest`, `jsTruncatesAstralCodePointsToTheirLowSixteenBits` is inverted to
`jsEmitsAstralCodePointsAsASurrogatePair` and `cssHexEscapesAreUnterminatedAndSwallowTheNextCharacter`
to `cssHexEscapesAreSixDigitAndSelfDelimiting` (former names in the javadoc), both asserting byte-exact
corrected output plus a round-trip (`unescapeJs`/`unescapeCss` parse the output back to the input).
`everyJsEscapeIsAFixedWidthHexForm` was updated deliberately: a BMP escape is still one fixed-width
form, an astral one is now two `\uXXXX` escapes, and the invariant is stated as "the interior is a
sequence of well-formed fixed-width escapes" — strictly stronger than the old single-escape check.
`jsPassesThroughOnlyAlphanumerics` and `cssPassesThroughOnlyAlphanumerics` pass unchanged (still only
alphanumerics pass through raw), as does `noEncoderCanEverEmitAMarkupDelimiter`;
`jsAndCssEscapeMultiCharacterInputCodePointWise`'s two concrete literals were updated.

*Coverage:* `HtmlEncoder` holds at **315/320 = 98.44%** against the 0.98 floor — `js()` gained the
astral branch, `css()` lost the `c <= 255`/`?` branch, net zero — so no floor moved; the inventory
comment in `build.gradle` records the re-measure. `./gradlew test` and `canoeCoverageGate` green;
`browserTest` green on Chromium (Firefox and WebKit are not installed here).

**R14 (settle `CTX_CSS`) is now unblocked** and comes next: its ordering constraint (trap 3 in §1)
was that a real CSS encoder must exist before `ATTR_CSS` is routed to one, and R13 is that
precondition. R14's own recommendation is still to keep suppressing and delete the dead `CTX_CSS` arm.

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

**R14 — Settle `CTX_CSS`** — ✅ **DONE**
*Closes:* F21. *Depends on:* R13.
*Landed:* the decision is **keep suppressing, and delete the trap.** The `CTX_CSS` constant is gone
(its value 5 left as a documented gap so no old caller silently rebinds to it), the dead `CTX_CSS`
arm of `encode()` is gone, and **both** commented-out contemplation lines are gone — the `CTX_CSS`
one (dead: uncommenting it changed nothing) and its `CTX_JS` twin (a latent enable: uncommenting it
*would* have taken effect). The `CTX_JS` arm stays a live suppression, but with no pre-written line to
uncomment; the reasoning — Canoe refuses to interpolate into CSS, F23 shows a `style` value is
decoded in series (HTML character references, then the CSS tokenizer), so a CSS encoder correct
against all of it is a project not a line, R13 is its precondition and is now met, and this task is
where the decision is recorded — sits on `currentContext()`'s `ATTR_CSS` case and, in pointer form,
on `encode()` where the arm was. `ATTR_CSS` → `CTX_SUPPRESS` is unchanged: `style` values stay
suppressed. `AttributeNameMatrixTest.currentContextCanNeverReturnCtxCss` is retired as
`.thereIsNoCtxCssAndStyleStillSuppresses`, carrying its reasoning and inverting its source assertion
("`encode()` must still carry the arm" → "no `CTX_CSS` constant, arm or return may reappear"). The
review's "systemic flaw" six-context table is corrected to five, and the R13 leftover is finalized:
`ScriptAndStyleElementTest`, `CssContextTest` and `AttributeNameMatrixTest` now describe CSS
suppression as R14's settled decision referencing R13's corrected `css()`, not F16 in present tense.
The `css.*` corpus rows (SUPPRESSED_BY_DESIGN from R2/F4) are confirmed settled with an R14 note on
`cssContexts()`; no verdict changed. Coverage: `encode()` lost the (test-covered) `CTX_CSS` branch, so
Canoe is 250/261 = 95.79% (was 251/262), still above the 0.95 floor — no floor moved; the inventory's
`currentContext` F21 tag and the `currentContextCanNeverReturnCtxCss` cross-reference are updated.
`./gradlew test` and `canoeCoverageGate` green; `browserTest` green on Chromium (Firefox and WebKit
not installed here).

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

**R15 — Fix `write(char[], int, int)`** — ✅ **DONE**
*Closes:* F9. *Depends on:* nothing.
*Landed:* the loop bound became `i < offset + len` (was `i < len`) and the error path became
`writer.write(cbuff, offset, i - offset)` (was `writer.write(cbuff, offset, len - (len - i))`, i.e.
`len - (len - i)` = `i`, an absolute index handed back as a length). `write(char[], offset, len)` now
parses exactly the range `[offset, offset + len)` at every offset, so the offset entry point is as
faithful as the offset-0 one. `ChunkInvarianceTest.aMidPointSliceDesynchronisesMostOfTheCorpus` is
inverted to `.noMidPointSliceDesynchronisesTheCorpus`, asserting the measured desync count through the
three-argument write is **zero** where it was 243 of 275 (F9's signature); its two companion F9 tests
(`.aNonZeroOffsetSkipsExactly…` → `.aNonZeroOffsetParsesExactlyTheRequestedRange`, and
`.theSameTwoPieces…NotAsSlices` → `…AndAsSlices`) and the class javadoc are inverted with the former
assertions moved to the javadoc. `CanoeWriterContractTest` — F9's per-entry-point contract — has all
six F9 tests inverted the same way: each now asserts the offset write reaches the same state/context
as the offset-0 write of the same characters, and the error-path test asserts the partial flush is
exactly the parsed prefix (`<p>ok</p><br`, not `<p>ok</p><br/`). Ledger: **no verdict changed** — the
corpus render path drives Velocity, which reaches Canoe only through `write(String)` →
`write(cbuf, 0, n)`, so no corpus row exercises the three-argument write at a non-zero offset; F9 has
no corpus case by construction and stays tracked in `MatrixReportTest.FINDINGS_WITHOUT_CASES` (the two
named tests still own it). Coverage: the change is arithmetic only, no branch added or removed and the
catch arm covered before and after, so Canoe holds at 250/261 = 95.79% against its 0.95 floor and no
floor moves; the `build.gradle` inventory records the re-measure. `./gradlew test` and
`canoeCoverageGate` green; `browserTest` green on Chromium (Firefox and WebKit are not installed here).

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

**R16 — Fix `COMMENT_CLOSE_2`** — ✅ **DONE**
*Closes:* F14. *Depends on:* nothing.
*Landed:* the `COMMENT_CLOSE_2` state now stays in `COMMENT_CLOSE_2` on a `-` instead of dropping back
to `COMMENT` — the one-arm change the HTML Standard's comment-end state describes (another `-`
appends and stays), so the `>` that follows any run of two or more dashes closes the comment and the
parser returns to HTML. Before: `> HTML`, else `COMMENT` (a third `-` fell into the else and reset the
close). After: `> HTML`, `- ` stay, else `COMMENT`. The dash-run cases, verified against the fixed
code: `<!--a--->` (three), `<!--a---->` (four), `<!------>` (dashes with no body) and `<!---->` (the
empty comment `<!--`+`-->`) all **close** → `CTX_HTML`; `<!--->` (the shortest abrupt-close form) does
**not** close and stays `CTX_SUPPRESS`, because Canoe models `<!--` as landing directly in `COMMENT`
and has no comment-start-dash state, so its single interior dash only reaches `COMMENT_CLOSE_1` — a
residual divergence of the same "not a faithful tokenizer" class as F10, fail-closed, and deliberately
out of R16's one-arm scope.
`CanoeStateMachineTest.aCommentEndingInThreeDashesNeverCloses` is inverted to
`.aCommentEndingInThreeDashesNowCloses` (former name in the javadoc), asserting the dash-run cases
above including the `<!--->` residual; `CanoeRobustnessTest.aCommentEndingInThreeDashesEmpties`
`TheRestOfThePage` is inverted to `.aCommentEndingInThreeDashesNowClosesAndTheRestOfThePageRenders`;
`BodyContextTest`'s F14 line is inverted to assert the `<p>` reference renders escaped once the comment
closes. Two transitions rows are added so the split `COMMENT_CLOSE_2` arm keeps its plain-character
`else` branch covered. Ledger/F14: the corpus row `comment.three-dashes-swallows-the-page`
(`<!--a---><p>$data</p>`, a `TAG_BREAKOUT` family into a text sink) moves off `SUPPRESSED_UNINTENDED`
to **SAFE** — the reference now renders in the `<p>` text context where `html()` escapes the payload,
so the structural oracle sees no shape change — re-verdicted against the fixed output and keeping its
`finding("F14")` citation for traceability (SAFE rows already cite findings in this corpus, e.g. the
two `desync.*-end-tag-with-a-suffix` F10 rows), so F14 keeps a live regression case and needs no
`FINDINGS_WITHOUT_CASES` entry. Coverage: the `COMMENT_CLOSE_2` block went from two branch outcomes to
four (all reached), so Canoe is 252/263 = 95.82% (was 250/261) against the 0.95 floor and
`reallyProcessChar()` is 155/160 = 96.88% against 0.96 — no floor moved; the inventory's eleven dead
outcomes are unchanged. `./gradlew test` and `canoeCoverageGate` green.

`Canoe.java:666-672` drops back to `COMMENT` on a third `-`, so `<!--a--->` never closes and every
reference for the rest of the page silently renders empty. The HTML Standard's comment-end state stays
in comment-end. Stay in `COMMENT_CLOSE_2` on `-`.

*Done when:* `CanoeStateMachineTest.aCommentEndingInThreeDashesNeverCloses` is inverted.

---

**R17 — Fix `SCRIPT_END` and `CSS_END`** — ✅ **DONE**
*Closes:* F10. *Depends on:* nothing.
*Landed:* three changes, one per direction of the desync plus one the review had explicitly cleared.
(1) The name match no longer ends the element on its own: `SCRIPT_END`/`CSS_END` hand off to new
`SCRIPT_END_NAME`/`CSS_END_NAME` states, which require the HTML Standard's delimiter — tab, LF, FF,
CR, space, `/` or `>`, written out in `isEndTagNameDelimiter()` rather than delegated to
`Character.isWhitespace()`, which is wider — before entering `TAG`, and re-process that delimiter
there so `>`, `/` and whitespace each mean in `TAG` what they mean in the standard. `closingTag`/
`tagName` are assigned only on the confirmed path, so `</scriptfoo` names no tag. (2) Both `*_END`
states and both `*_END_NAME` states now set `charNeedsProcessing = true` on the mismatch arm, so a
`<` that is not part of the name opens a fresh end tag instead of being swallowed; `<<</script>`
closes, and so does the ordinary `a < b`. Termination is by inspection: every re-process either
leaves the state machine in `SCRIPT`/`CSS`, which never re-processes, or in `TAG`, which re-processes
nothing for the three delimiters that can reach it. (3) **Found in review and fixed with it:** the
name was matched with `Character.toLowerCase()`, a Unicode fold, and `Character.toLowerCase(U+0130)`
is `'i'` — so `</scr\u0130pt>` matched `/script`, closed the element for Canoe and not for the
browser, and re-opened F10's forward desync through a character no delimiter check can see. The two
states now fold with an ASCII-only `asciiToLowerCase()`; a sweep of the BMP confirms U+0130 was the
only code point whose fold lands anywhere in `/script` or `/style`. The opening tag is left folding
Unicode on purpose — there the divergence suppresses, which is fail-closed, and it belongs with §5's
`isNameChar()` observation. Ledger: the four F10 rows, re-verdicted against the sink —
`desync.script-end-tag-with-a-suffix` and `desync.style-end-tag-with-a-suffix` SAFE →
**SUPPRESSED_BY_DESIGN** (the reference is inside the element body for both parsers now, and an
element body suppresses), with their sink kinds moved from `HTML_TEXT` to `JAVASCRIPT`/`CSS` to match,
which is what makes them fail rather than pass the day `CTX_JS` is relaxed; `desync.script-stuck-on-a`
`-double-less-than` and its style twin SUPPRESSED_UNINTENDED → **SAFE** (the end tag is recognised and
the reference renders `htmlWhite()`-escaped in the `<p>` after it, structural oracle unchanged). No
F10 row records a defect any more, and `ScriptAndStyleElementTest.theFourDesyncRowsRecordNoDefectAt`
`All` asserts exactly that. Tests: both halves of `ScriptAndStyleElementTest` inverted with their
former names in the javadoc, `bothDesyncsHaveExactCssTwins` still comparing both halves,
`onlyTemplateTextCanCauseADesync` passing **unchanged in substance** (two templates added, no
assertion touched — the argument is about what an encoder can emit, and R17 does not touch an
encoder); `CanoeStateMachineTest.scriptEndAcceptsATagNameItShouldNot` and
`.scriptAndStyleEndSwallowTheCharacterThatMismatched` inverted; `TagNameTrackingTest` asserts both
halves of the deferred naming; `theFourStatesAScriptOrStyleBodyCanBeIn` is now
`.theSixStatesAScriptOrStyleBodyCanBeIn`. Coverage: two states (6 outcomes),
`isEndTagNameDelimiter()` (14) and `asciiToLowerCase()` (4), all reached; Canoe 252/263 → 276/287 =
96.17%, `reallyProcessChar()` 155/160 → 161/166 = 96.99%, the eleven dead outcomes unchanged, no
floor moved. `./gradlew test` (5,922) and `canoeCoverageGate` green; `browserTest` green on Chromium.

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
| F6 — `url()` is a scheme filter, not an origin filter | High | R9 (with R8, R12) closes the code-execution half; open-redirect/referrer residue on `a href`/`img src`/etc. stays for R26 |
| F7 — `content` branch tests for `data` | Medium | R7 |
| F8 — no tests, no docs, no threat model | Medium | R25 (tests: already delivered) |
| F9 — `write(char[],int,int)` length/end confusion | Low (latent) | R15 |
| F10 — `SCRIPT_END` accepts `</scriptfoo>` | Low (latent) | R17 ✅ (delimiter required, mismatch re-processed, fold bounded to ASCII) |
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
| F21 — `currentContext()` can never return `CTX_CSS` | Low (latent) | R14 ✅ (constant + dead arm deleted) |
| F22 — base factory declares an unconfigured loader | Low | R22 |
| F23 — `style` values are decoded twice | Low | R2 closes the exposure; R13, R14 record the rest |
| F24 — `url()` emits a raw scheme colon | Medium | R11, R12 (R2 mitigates) |

**Three tasks close thirteen findings.** R2 closes F4, F17 and F24's exploitable path on one deleted
line. R4 closes F1, F2 and F19 by deleting 200. R5 closes F3's policy half and F20 by inverting a
default. If the work has to stop early, stop after R5.

**Phase A is complete.** Every finding it owned is closed: F1, F2, F3, F4, F5, F7, F17, F19 and F20.
The ledger's `KNOWN_VULNERABLE` count is **61 invocations across 30 cases, every one of them F6** —
`url()` is a scheme filter and not an origin filter — so what is left of the exploitable surface is
one defect in one encoder, owned by R9, R11 and R12. The count rose against F6 while F3's fell to
zero, because twelve names R6 routed to `url()` inherited its off-origin passthrough; that is the
honest arithmetic of closing a classification defect before the encoder defect underneath it.

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

   **Half-discharged by R17, and the half that mattered.** The same observation applies to the
   *case fold*, not only to the character class, and there it was live rather than cosmetic:
   `SCRIPT_END`/`CSS_END` matched the end tag name with `Character.toLowerCase()`, which maps U+0130
   to `'i'`, so `</scr\u0130pt>` closed the script element for Canoe and not for the browser — F10's
   forward desync, through a character the delimiter rule R17 added cannot see. R17 bounds that fold
   with `asciiToLowerCase()`; a BMP sweep confirms U+0130 was the only code point whose fold reaches
   `/script` or `/style`, so the end-tag side is now closed rather than narrowed. What is left is
   the **opening** tag: `TAG_NAME` still folds Unicode and `isTagNameChar()` still accepts any
   letter, so `<scr\u0130pt>` puts Canoe into `SCRIPT` where the browser has an unknown element.
   That direction *suppresses* — fail-closed, an availability divergence and not a desync into a
   live context — which is why R17 deliberately did not touch it. Still worth doing; still not
   urgent.

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
