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
   **R24 has landed, after R9, R11 and R12.** The F6 masking is gone with it: the two paths are now
   byte-identical at that sink and both are F6's live vector, which
   `.theSetPathAndTheDirectPathAgreeAtEverySinkTheAccidentCovered` asserts in place of the retired
   test. The ledger did not move, because no corpus template uses a Velocity directive at all.
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

**R18 — Fix the DOCTYPE precondition** — ✅ **DONE**
*Closes:* F18. *Depends on:* nothing.
*Landed:* `tagCount` is deleted — nothing else read it — and the precondition is two booleans with
one meaning each. `elementSeen` is set in `TAG_NAME` at `bufLen == 0`, with the `'!'` test moved
above the `'/'` test so that the assignment sits between them: a start tag's first name character
and the `/` of an end tag set it, the `!` of a bang declaration does not. That is the HTML
Standard's own boundary — "initial" insertion mode inserts a comment and *stays* there, while any
tag, start or end, is "anything else" and moves the parser on, after which a DOCTYPE token is a
parse error a browser ignores. `doctypeSeen` is set where the declaration is admitted, at the `d` of
`<!d`, which is safe because a misspelling raises from `DOCTYPE_TEST` and ends the render. The two
give the two rejections their own messages: `DOCTYPE declaration must precede the first element`
(reworded from "must be at the beginning", which stopped describing the rule the moment a comment
was allowed above the declaration) and `Duplicate DOCTYPE declaration`. **The reorder is
behaviour-preserving and the task only widens what Canoe accepts**: `'/'` and `'!'` are mutually
exclusive, `TAG_NAME` is entered from exactly one place (`HTML` on `<`), and `bufLen == 0` holds
exactly once per tag, so the two orderings differ only by the assignment. Measured rather than
argued as well — both tokenizers run over 8,420 generated documents give **29 newly accepted, 0
newly rejected, 0 changes to any accepted output**, the 29 being the comment-above-the-DOCTYPE
family. Neither surviving rejection is new: `tagCount` was already past 1 for both, so R18 splits
one misleading message into two accurate ones rather than adding a refusal. Whether Canoe should
reject a second DOCTYPE at all, where a browser merely ignores it, is a rejection-table question and
is in R20's triage table below. Leading text before the DOCTYPE stays accepted, unchanged and
deliberately: a browser would ignore that DOCTYPE and go quirks, but the input renders today and
turning it into a 500 belongs to R20 too. Ledger: `reject.doctype-after-a-comment` (REJECTED ×2)
becomes `doctype.after-a-comment` (**SAFE** ×3, `TAG_BREAKOUT` into a `<p>` text sink, verdict set
against the rendered output — the payload arrives `htmlWhite()`-escaped), keeping its F18 citation
so the finding keeps a live regression case, and a new `reject.second-doctype` (REJECTED ×2) bounds
the fix. 1,002 → **1,005 invocations**, SAFE 459 → 462, REJECTED 44 unchanged. Tests:
`CanoeRobustnessTest.aCommentBeforeTheDoctypeMakesTheDoctypeIllegal` is inverted to
`.aCommentBeforeTheDoctypeIsNowLegal` with the former name and F18's mechanism in its javadoc, and
carries the five surviving rejections — `<html><!DOCTYPE>`, `</p><!DOCTYPE>`,
`<!-- c --><p><!DOCTYPE>`, `<!DOCTYPE><!DOCTYPE>`, `<!DOCTYPE><!-- c --><!DOCTYPE>` — as its net;
three rows added to the rejection table and one reworded there, two added to the acceptance table;
`CanoeStateMachineTest` gains three transition rows; `TemplateFuzzTest` now emits a comment above
its DOCTYPE, a shape F18 had made unreachable. Coverage: the one condition became two, all four
outcomes reached, Canoe 276/287 → 278/289 = 96.19%, `reallyProcessChar()` 161/166 → 163/168 =
97.02%, the eleven dead outcomes unchanged, no floor moved. `./gradlew test` and
`canoeCoverageGate` green; `browserTest` green on Chromium (88 passed, 2 skipped).

`tagCount++` runs for every `<` in `HTML` state and `COMMENT_OPEN_OR_DOCTYPE` demands `tagCount == 1`,
so a licence header or generator stamp above the DOCTYPE — legal HTML, common in template files —
takes the whole page down with `DOCTYPE declaration must be at the beginning`. The check wants "no
*element* has been emitted yet", not "no `<` has been seen yet". Track that instead.

*Done when:* `CanoeRobustnessTest.aCommentBeforeTheDoctypeMakesTheDoctypeIllegal` is inverted.

---

**R19 — Handle `TAG_ATTR_VALUE_BEFORE` in `currentContext()`** — ✅ **DONE**
*Closes:* F11 (the attribute-value half; see below). *Depends on:* Phase A.

`<a href=$x>` inserts the reference while the parser is still in `TAG_ATTR_VALUE_BEFORE` — the quote
that would advance it never arrives — and `currentContext()` has no case for that state, so it falls
to `CTX_SUPPRESS` and the value silently vanishes. `<a href=/p/$y>` works, because the `/` gets it
into `TAG_ATTR_VALUE` first. Only a reference immediately after `=` is dropped.

Treat an unquoted value as its name-derived context, or raise an encoding error. Do not leave it
silent: this is the failure mode that pushes developers to `allowDirectOutput()` + `$_x.asis()`,
which turns Canoe off for that value entirely.

*Done when:* the `unquoted-after-equals` corpus row moves off `SUPPRESSED_UNINTENDED`.

*Landed:* `currentContext()` answers `TAG_ATTR_VALUE_BEFORE` with the same thing it answers
`TAG_ATTR_VALUE` — the attribute's name-derived context — by sharing its `case` label. One line of
code and forty of comment, and the ratio is the point: the change is trivial and the argument that it
is safe is not. **Why the name-derived context is available at all:** the state is entered only from
`TAG_ATTR_NAME_AFTER` on `=`, which is only reachable from `TAG_ATTR_NAME`, which calls
`setTagAttributeContext()` before it leaves — so `attributeContext` there is this attribute's own and
never a leftover, which is F5's failure mode and is asserted rather than read off the control flow
(`CanoeStateMachineTest.theContextInValueBeforeBelongsToTheAttributeInFront`). What it has *not* been
through is `detectAttributePrefix()`, and it does not need to be: that runs on value characters, can
only narrow since R2, and a reference sitting directly after the `=` has no value characters in front
of it. **Why routing it is safe** — the load-bearing argument, checked against the encoders rather
than assumed: an unquoted value ends at whitespace or `>`, for Canoe (`TAG_ATTR_VALUE`,
`QUOTE_NONE`) and for the standard's attribute-value-unquoted state, which treats `"`, `'`, `<`, `=`
and `` ` `` as a parse error that stays *inside* the value; and the first character decides the
quoting, so a leading quote would be read as an opening one. `htmlAttr()` is `html()`, which escapes
every non-alphanumeric — space is `&#32;`, `>` is `&gt;`, a C0 control is the four printable
characters `\xNN` — and whose whole output alphabet is alphanumerics plus `&`, `#`, `;` and `\`;
`url()` emits the unreserved set, the four per-component safe sets (`AUTHORITY_SAFE`, `PATH_SAFE`,
`RELATIVE_PATH_SAFE`, `QUERY_SAFE` — no quote, no space, no angle bracket in any of them), `%XX`
escapes, `&amp;`, and the structural `:`/`//`/`?`/`#` it writes from its own parse; `urlResource()`
returns `url()`'s output or nothing; every other `ATTR_*` returns the empty string. No terminator, and
no leading quote, from any of them. The argument is executable, not prose:
`UnquotedAttributeValueTest.noEncoderReachableFromAnAttributeValueCanTerminateAnUnquotedOne` sweeps
all 60 catalogue payloads through every context an attribute name can produce — collected by running
the machine, not written down, so a sixth context fails
`.theSweepCoversEveryContextAnAttributeNameCanProduce` rather than escaping the sweep — through both
the static dispatcher and the instance path with a CDN allowlist configured, so the `CTX_URI_RESOURCE`
rows are not vacuous against the empty string. **Fuzz counterexample, found by the run and fixed with
it.** `TemplateFuzzTest` failed at iteration 79 on `<img src=${data} alt="a">`: an unquoted attribute
whose value renders *empty* is not an empty attribute — the browser reads `alt="a"` as `src`'s value —
so the skeleton loses `alt` when the payload suppresses and the marker does not. It is a property of
HTML, it fires for a legitimately empty model value too, and F11 produced it *unconditionally* for
every unquoted value, so R19 shrinks it rather than introduces it. It is not an injection either: the
attacked output is byte-identical to the empty-value output, so no attacker byte is in the document,
and property 3 of the fuzz oracle now carries the suppression precedence `VerdictEvaluator.observe()`
has always had (compare against the empty render first; an exact match is a suppression, not an
injection). Property 2 still runs unconditionally and two self-test rows on
`<img src=$_x.asis($data) alt="a">` hold the exemption to its scope. The first, with
`ATTR_UNQUOTED_BREAKOUT`, was reviewed and found **insufficient on its own** and is kept rather than
replaced: that payload carries two apostrophes, so property *2* reports it and `check()` returns
before the skeleton is ever compared — a true statement about the oracle as a whole and no statement
at all about the exemption. The second, added in review, uses a delimiter-free
`x onmouseover=__canoePwned(1)`, which changes no character count property 2 watches and is not
suppressed, and asserts the violation **by name** (`assertViolationIs("the document skeleton
diverged", …)`) so the row cannot be satisfied by a different property going off. That is the row
that proves property 3 still fires in the exempted position. Emitting `""` for a suppressed value would repair
`<img src=$x alt="a">` and break `<a href=$base/p>` (currently `<a href=/p>`, which would become
`<a href=""/p>` plus a stray `p` attribute), and would make `Canoe.encode()` depend on parser position
and on emptiness; the template-level answer — quote the value — has no such trade, so this is the
**residual R19 deliberately leaves**, pinned by
`UnquotedAttributeValueTest.anEmptyUnquotedValueSwallowsTheNextAttribute` and documented in
`qlue_user_guide.md`. **F11 is closed in part, and the plan's heading now says so.** Its other holes —
`TAG_ATTR_NAME`, `TAG_EMPTY_ENDING`, the `COMMENT_*`/`DOCTYPE_*` states, `INVALID` — stay
`CTX_SUPPRESS`, because `TAG_ATTR_VALUE_BEFORE` had a name-derived answer waiting for it and none of
the others has an encoder at all; the four remaining `SUPPRESSED_UNINTENDED` cases are exactly those.
Ledger: 1005 → **1008** invocations, 276 → **277** cases. `SUPPRESSED_UNINTENDED` **18 → 12** (the six
F11 invocations leave), `SAFE` **462 → 469**, `KNOWN_VULNERABLE` **66 → 68**, `SUPPRESSED_BY_DESIGN`
415 and `REJECTED` 44 unchanged. Per row: `unquoted.immediately-after-equals` and
`unquoted.whitespace-then-reference` go SUPPRESSED_UNINTENDED ×3 → **SAFE ×2 + KNOWN_VULNERABLE ×1**
each, re-verdicted by reading the rendered output against the sink payload by payload — the unquoted
form now renders byte-for-byte what `<a href="$data">` renders minus the quotes, so
`PROTOCOL_RELATIVE/slashes` arrives as `//attacker.invalid/x.js` (F6, the residual R26 tracks) and the
two backslash spellings are percent-escaped to same-origin paths. **The citation moves with the
verdict**, F11 → **F6**, on the same rule R7 applied: a case cites the finding its *current* verdict is
about. F11 keeps a case rather than joining `FINDINGS_WITHOUT_CASES` — the new
`unquoted.plain-text-after-equals` (`<span title=$data>x</span>`, `ATTR_BREAKOUT` ×3, **SAFE**, citing
F11), which is where the safety argument lives in the ledger: `ATTR_BREAKOUT/unquoted-attr` is
`x onmouseover=…`, the payload built to end an unquoted value, and `html()` escapes its space to
`&#32;` and its `=` to `&#61;`. Finding coverage: F6 30 cases/424 invocations/66 KV →
**32/430/68**; F11 2/6/0 → **1/3/0**. Tests: `CanoeStateMachineTest`
`.unquotedValuesAreSuppressedOnlyImmediatelyAfterTheEquals` inverted to
`.unquotedValuesTakeTheirNameDerivedContextImmediatelyAfterTheEquals`, keeping the former name and
the reasoning in its javadoc; its two `(F11)` transition rows re-labelled `(R19)` and moved from
`CTX_SUPPRESS` to `CTX_HTML_ATTR`, with five more added for the other classifications; the class
javadoc's prediction that a context-only table would report this fix as a regression is marked as
having come true; `statesWithNoCaseFallThroughToSuppress` gains `TAG_ATTR_VALUE_BEFORE` in its
`withACase` set, which is where the security decision is visible. New `UnquotedAttributeValueTest`
owns the position: a twelve-row rendered-output table across every shape (`<a href=$x>`,
`<a href="$x">` unchanged, `<a href= $x>`, `<div id=$x>`, `<span title=$x>`, `<script src=$x>` with a
relative and an off-origin value, `<div style=$x>`, `<a onclick=$x>`, an unlisted name, an empty
value, and a value followed by another attribute), a per-payload equivalence between the quoted and
unquoted forms at the DOM, the terminator sweep, the two parser-position tests, the `/`-before-`>`
trap, and the diagnostic. Two additions made in review: `.theSweepCoversEveryContextAnAttributeName`
`CanProduce` now also asserts the set of `ATTR_*` classifications `Canoe` declares, because its
javadoc claimed a new classification would fail it and a probe-template list cannot do that on its
own — a ninth `ATTR_*` would simply reach no probe; and
`.aSecondReferenceInsideTheSwallowedRegionKeepsTheSwallowingAttributesContext` pins the half of the
swallowing residual the original argument skipped, that the swallowed region may hold **another
reference** rather than only literal text. It is one attribute value to both tokenizers, so
`<a href=$a onclick=$b>` encodes `$b` with `url()` and produces no `onclick` attribute at all, and
`<a onclick=$a href=$b>` suppresses `$b` too because `attributeContext` is still `ATTR_JS` — the
agreement that keeps the residual data loss rather than an F10-class tokenizer divergence.
Prose corrected where R19 falsified it: `README.md` and
`qlue_user_guide.md` both listed "an unquoted attribute value" among what Canoe suppresses (the guide
gains the empty-value warning instead), and the F11 mentions in `AttributePrefixTest`,
`BodyContextTest`, `AttributeNameMatrixTest`, `Verdict` and `ViewFactoryRenderTest` are re-tensed and
scoped. Coverage: **nothing moved**, and the reason is recorded in `build.gradle` rather than left to
look like an oversight — and it was checked against the JaCoCo XML rather than asserted: javac compiles the two `case` keys to one jump target and JaCoCo counts a
switch's distinct targets, so `currentContext()` is 11/13 exactly as before, Canoe 278/289 = 96.19%,
`reallyProcessChar` 163/168 = 97.02%, the eleven dead outcomes unchanged (the new label routes to the
*same* inner switch, so it cannot make the inner default reachable), no floor moved. `./gradlew test`
(6,083 tests, the last of them added in review) and `canoeCoverageGate` green. `browserTest` green on Chromium — 91 passed, 2 skipped
(Firefox and WebKit are not installed here) — and it is not a formality this time:
`unquoted.immediately-after-equals` and `unquoted.plain-text-after-equals` were made browser-relevant,
taking the tier 62/18/44/0 → **65/19/46/0**, and Chromium fires the sentinel-origin detector on
`<a href=//attacker.invalid/x.js>` exactly as it does on the quoted twin `url.href-full`. That is the
safety argument checked by a real parser rather than by jsoup.

---

**R20 — Triage the template-rejection table** — ✅ **DONE**
*Closes:* the availability rows of F13's table. *Depends on:* R21.
*Landed:* three of the five rows below move, two stay as recorded decisions, and the two rows R18
added move as well.

**`<br/>` is accepted.** `TAG_NAME`'s "character after the name" test gains `c != '/'`, and nothing
else was needed — the branch already sets `charNeedsProcessing` and enters `TAG`, where `/` has always
meant `TAG_EMPTY_ENDING`. Read rather than assumed before it was written, and the one thing worth
checking was the exit: `TAG_EMPTY_ENDING` leaves for `nextState`, so `<script/>` lands in `SCRIPT`
exactly as `<script />` did, which is also what a browser does (the self-closing flag is acknowledged
and ignored on a non-void element). **`<img src="a.png"/ alt="x">` is deliberately still rejected**
and is not the same shape: a `/` followed by another attribute is the standard's
*unexpected-solidus-in-tag* parse error, no serializer emits it, and it was not in R20's package.

**`MAX_TAGNAME_LEN` is 128**, so both length caps sit at 127 characters. The attribute sibling is the
one that mattered (§5 observation 1): `data-controller-target-value-for-the-widget` is 43 characters,
unremarkable in any framework, and was a failed request. The cap is kept rather than removed because
the buffer is fixed-size by design; the cost of the raise is 256 bytes per instance instead of 72.

**A second DOCTYPE is ignored with a warning**, and **text before the DOCTYPE now warns** while
staying accepted. Both go through the slf4j logger Canoe already uses for R5's suppression
diagnostic, at warn level with the coordinates in the message. `doctypeSeen` is kept — it is what
makes the second declaration detectable — and a new `textSeen` records that non-whitespace output has
been written; whitespace deliberately does not set it, because a template whose first line is a
directive emits a newline above the DOCTYPE and a diagnostic that fires on that is noise.
`isInitialModeWhitespace()` writes the standard's set out rather than calling
`Character.isWhitespace()`, which is a wider fold, and omits FF because the C0 guard above it makes FF
unreachable — including it would have added a twelfth dead branch to the coverage inventory.

**Kept, with the reasoning recorded on `CanoeRobustnessTest.rejections()` rather than left implicit:**
`<p>5 < 6</p>` (this check is what makes the body context safe to reason about — a raw `<` is exactly
where Canoe's model and the browser's would part company), `</ p>` and `</>` (not end tags to a browser
either, and no serializer emits them), and a C0 control in body text (it is in the *template's own*
text; a control inside an encoded reference becomes the four printable characters `\xNN` before the
tokenizer sees it). What makes rejecting them affordable is R21: the failure is a catchable
`CanoeEncodingException` on an unflushed, resettable response.

*Tests:* `CanoeRobustnessTest`'s table loses the `<br/>` row and both duplicate-DOCTYPE rows and gains
a `<p"x">` row, which keeps the `TAG_NAME` "Invalid character after tag name" call site reached; the
call-site pin moves 16 → 15 and the message pin 14 → 13.
`.theNameLengthLimitIsOneLessThanTheBufferLength` keeps its name (the relationship it states is still
exactly true) and asserts the new boundary plus the three shapes the old one broke.
`.aCommentBeforeTheDoctypeIsNowLegal` keeps its F18 reasoning and its three surviving `elementSeen`
rejections; its two duplicate-DOCTYPE assertions are inverted into two new tests,
`.theSecondDoctypeIsIgnoredWithAWarning` and
`.theQuirksModeConsequenceOfTextBeforeTheDoctypeIsWarnedAbout`, which assert the diagnostics by
**capturing** them from the logger rather than by asserting a field — a warning nobody reads is a
warning that stops being emitted. `CanoeStateMachineTest` gains six transition rows (three for the
`/`-after-name path including `<script/>`, three for the DOCTYPE arms). `TemplateFuzzTest` now
generates `<br/>`, a self-closed `<img/>` and a second DOCTYPE, on R18's own reasoning that the shapes
a fixed finding makes reachable are the ones the fuzzer had never explored.
`AttributeNameMatrixTest` reads the length bound from the constant instead of a 36-character literal,
and asserts the accepted side of it too. Every test that used `<br/>` merely as *a template Canoe
rejects* moved to `5 < 6`, chosen because it is the same length and fails at the same offset, so every
coordinate, every `write(char[], int, int)` range and every "characters that reached the writer"
assertion reads exactly as before; the fixture file is renamed `rejected-literal-lt.vm`.
`BufferResidueTest` and `NearMissNameSweepTest` turned out **not** to encode the old cap — the first
sweeps names of 1–20 characters and mentions 36 only in prose, the second has no length boundary at
all — so the change there is prose.
*Ledger:* eight `REJECTED` invocations become `SAFE` (`reject.void-br`, `reject.void-hr`,
`reject.void-img` → `void.*-no-space`; `reject.second-doctype` → `doctype.second-is-ignored`), and
two rows are added for shapes the ledger had none for: `doctype.after-leading-text`, which bounds the
new warning by asserting it changed no output, and `shape.framework-length-attribute-name`, which is
§5 observation 1 as a case. Each re-verdict was set by reading the render against the sink — all four
put their payload in a `<p>`/`<div>` text sink where `htmlWhite()` escapes it and the DOM skeleton is
the benign one — not by copying the run. The length rows keep their verdicts and move to 127/128.
**1,008 → 1,012 invocations: SAFE 469 → 481, REJECTED 44 → 36**, KNOWN_VULNERABLE 68,
SUPPRESSED_BY_DESIGN 415, SUPPRESSED_UNINTENDED 12 all unchanged. F13's exemption in
`MatrixReportTest.FINDINGS_WITHOUT_CASES` is deleted, because the finding now has corpus cases
(`void.br-no-space` and `shape.framework-length-attribute-name` cite it) and a stale exemption is what
that map's second assertion exists to catch.
*Coverage:* fourteen branch outcomes added, all fourteen reached, and the eleven dead outcomes are
still the same eleven, re-read from the XML method by method. Canoe 278/289 → **292/303 = 96.37%**
(floor 95), `reallyProcessChar()` 163/168 → **169/174 = 97.13%** (floor 96), `isInitialModeWhitespace()`
8/8, HtmlEncoder untouched at 315/320 = 98.44%. No floor moves. `build.gradle`'s inventory records the
re-measurement, refreshes the line numbers in the dead-outcome list, and corrects one stale figure it
found on the way: `setTagAttributeContext()` reads 10/10, not the 8/8 recorded under R5 — the two
extra outcomes are R9's `isResourceLoadingSink()` ternary, fully covered then and now.
`./gradlew test` (6,128 tests, 0 failures) and `canoeCoverageGate` green; `browserTest` green on
Chromium — 91 passed, 2 skipped, unchanged by R20, which is what it should be: the rows R20 moved are
not `browserRelevant` and the output of every row that is, is byte-identical. See R28 for what
happened when the other two engines turned out to be installed here after all.

Five ordinary inputs raise an encoding error, and per F13 each is currently an unhandled 500:

| Input | Error | Verdict to reach |
|---|---|---|
| `<br/>` | `Invalid character after tag name` | **Bug — fix.** A `/` immediately after a tag name is valid HTML. `<br />` works; only the no-space form fails. |
| `<p>5 < 6</p>` | `Tag name too short` | Decide. Strict is defensible (it is a template-authoring error), but it must fail at build or dev time, not at request time. |
| a 37-character tag or attribute name | `Tag name too long` / `Attribute name too long` | `MAX_TAGNAME_LEN` is 36 and the buffer is shared. Raise the cap or grow the buffer; custom-element and framework attribute names exceed it routinely. |
| `</ p>`, `</>` | `Tag name too short` | Keep. |
| a C0 control in body text | `Invalid character detected in output` | Keep. |

**Added by R18**, which preserved these two rejections rather than deciding them, because F18 was
about a comment above the DOCTYPE and nothing else:

| Input | Error | Verdict to reach |
|---|---|---|
| `<!DOCTYPE html><!DOCTYPE html>` | `Duplicate DOCTYPE declaration` | Decide. A browser ignores the second declaration; Canoe refuses the page. Defensible as an authoring diagnostic — a layout and an included fragment each declaring one is the usual cause — but it is strictness a browser does not have, and the argument for keeping it is the same "must fail at build or dev time, not at request time" argument as `<p>5 < 6</p>` above. |
| `hello<!DOCTYPE html>` | *accepted* | Decide, in the other direction. The HTML Standard ignores whitespace in "initial" and treats other text as a parse error that moves the parser past the point a DOCTYPE can be honoured, so a browser renders this in quirks mode and Canoe says nothing. Accepting it is the availability-safe choice and R18 kept it; a *warning* rather than a rejection is the shape that would close the gap without a new 500. |

Fix the first, size the third, and record the decision for the rest. Whatever is decided, R21 has to
land first so the failure is a diagnosable error rather than a 500 on a half-written response.

---

### Phase E — Framework integration, documentation, guardrails

---

**R21 — Make encoding errors catchable** — ✅ **DONE**
*Closes:* F13. *Depends on:* nothing.
*Landed:* `Canoe.raiseError()` throws a `CanoeEncodingException extends IOException` carrying the
reason, the line and the position as **fields** (`getReason()`, `getLine()`, `getPosition()`) as well
as in the message, and `VelocityViewFactory.render()` recognises it with
`CanoeEncodingException.findIn(e)` — a depth-bounded walk of the cause chain, matching on the **type**
— then rethrows it unwrapped, so a caller catches Canoe's exception instead of pattern-matching
Velocity's message. `ERROR_PREFIX` is **kept** and its javadoc says why: the message is its own
compatibility surface (public constant, every log line and operator grep in the field), the defect was
never about the string, and `CanoeEncodingException` is now the single place that builds it — but
nothing in `src/main` matches on it any more.

**The recovery is to fail the request outright**, decided on the record with both alternatives
rejected in `discardPartialResponse()`'s javadoc. The `[Encoding Error]` marker is deleted: the
response ends *inside* an element, so the marker would land in an attribute list under a 200 and the
client would get a page that looks served and is not. Truncation to the last known-good tag boundary
was rejected as worse than the 500 it replaces — a truncated document is one a browser renders as
though it were whole, so the reader silently loses the content and the footer with nothing saying so,
and Canoe would have to buffer from the last boundary onwards to be able to rewind to it, giving up
the streaming property.

**What was actually broken, and is not in the finding's table:** `render()`'s `finally` block flushed
**unconditionally**, which committed the half-written page, and `QlueApplication.service()` only sends
its 500 while `!response.isCommitted()` (`QlueApplication.java:666`) — so the unhandled 500 F13
describes could not in fact be sent. The flush is now suppressed on the error path, and the production
entry point `render(Page, VelocityView)` calls `response.resetBuffer()` while the response is still
uncommitted, which is also what stops a `page.handleException()` view being appended to the fragment
of the broken page. The two halves of `service()`'s recovery need the uncommitted response in
different ways, and `discardPartialResponse()`'s javadoc now says which is which: `sendError()` is
skipped outright by an explicit `isCommitted()` guard, whereas a `handleException()` view has no guard
at all and would simply be appended to the broken page. The residual is stated rather than hidden: a
response commits when its buffer fills, and the buffer is a few kilobytes — the servlet specification
sets no size, Tomcat defaults to 8KB, other containers pick their own and `setBufferSize()` is the
application's only lever — so a template that raises after that much output has already put bytes on
the wire; that case is logged at error level and the exception still propagates. Truncation would not
have recovered it either.

**Two things the finding understated.** Velocity's wrapper is not one message but at least four — a
rejection inside a `#parse`d fragment surfaces as `Exception rendering #parse(...)` and one inside a
macro body as `VelocimacroProxy.render() : exception VM = #m()`, and **neither contains `IO Error` nor
Canoe's message at all**, so no repair of the message test could have found them
(`CanoeEncodingExceptionTest.velocityWrapsCanoesExceptionInMessagesThatShareNoCommonPrefix`). And the
reported line and position are coordinates in the **rendered output**, not in any template file: the
`/` of a `<br/>` inside an included fragment is reported at its position in the response. That is the
right answer to the question Canoe can answer and not the question a developer asks — worth knowing
before R20 reports these to anyone.

*Tests:* `CanoeRobustnessTest.noErrorCanoeRaisesIsSwallowedInProduction` is inverted to
`.everyErrorCanoeRaisesEscapesRenderAsACatchableCanoeEncodingException`, keeping its former name and
F13's mechanism in its javadoc, and joined by
`.aRejectedTemplateIsNotFlushedSoTheResponseCanStillBeReset`; a new `CanoeEncodingExceptionTest` owns
the type (the cause-chain walk, the four wrapper shapes — measured against real Velocity renders, not
against hand-built exceptions — the structured coordinates, the cycle bound);
`ViewFactoryRenderTest` and `ProductionRenderProbe` learned the typed path and the reset.
`ViewFactoryRenderTest.theProductionEntryPointWiresTheResponseReset` **drives** the two-argument
`render(Page, VelocityView)` rather than reading the source for it: `ProductionRenderProbe
.renderThroughResponse()` builds a real `TransactionContext` over four `Proxy`-stubbed servlet
interfaces — its constructor needs a remote address, a request URI and a session that remembers
attributes, and nothing else — so the reset is asserted by its effect (an empty response body) and
the committed residual by its effect too (the partial page still there, and still Canoe's exception
rather than an `IllegalStateException` from `resetBuffer()`).
*Ledger:* **unchanged** — which templates Canoe rejects did not change, only how the rejection is
delivered, so the 44 `REJECTED` invocations and every other verdict stand (1008 invocations: SAFE 469,
KNOWN_VULNERABLE 68, SUPPRESSED_BY_DESIGN 415, SUPPRESSED_UNINTENDED 12, REJECTED 44). `Verdict`'s
`REJECTED` javadoc and the matrix scoreboard now say what a rejection *does* since R21.
*Coverage:* Canoe 278/289 = 96.19% (floor 95), HtmlEncoder 315/320 = 98.44% (floor 98),
`reallyProcessChar()` 163/168 = 97.02% (floor 96) — `raiseError()` carries no branch counter at all in
the JaCoCo XML, before or after, so no branch moved and no floor moved; the eleven dead outcomes are
the same eleven. The branches R21 adds are outside the gated classes and are fully covered:
`CanoeEncodingException.findIn()` 6/6, `discardPartialResponse()` 4/4, and
`render(Page, VelocityView, Writer)` 13/14 (was 11/14 — the `context != null` arm is reached for the
first time), with `render(Page, VelocityView)` itself going from 0 of 19 instructions to all 19.
`build.gradle`'s inventory records the re-measurement. `./gradlew test` (6,096 tests, 0 failures) and
`canoeCoverageGate` green; `browserTest` green on Chromium — 91 passed, 2 skipped (Firefox and WebKit
are not installed here), unchanged by R21, which touches nothing the browser tier renders.
**R20 now has** a typed exception with a stable `getReason()` to group rejections by, structured
coordinates to report, and a recovery that fails the request cleanly — so its triage is a decision
about *which* inputs to reject, with the delivery mechanism already settled.

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

**R22 — Configure the `class` resource loader in the base factory** — ✅ **DONE**
*Closes:* F22. *Depends on:* nothing.
*Landed:* one line, where the finding said it should go —
`buildDefaultVelocityProperties()` now sets `resource.loader.class.class` to
`org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader`, next to the `string` loader's
own `.class` key and above the caching lines that were the only thing it had ever configured about
the `class` loader. The plain loader is the right default because the key names *which* loader reads
the `class` entry in `resource.loaders`, and that entry is the classpath; the reloading variant is a
development convenience and belongs to the subclass that wants it, not to every subclass.

**`ClasspathVelocityViewFactory` keeps its override**, checked rather than assumed, and what the check
found is worth recording: the override picks
`NonCachingClasspathResourceLoader` or `ClasspathResourceLoader` on the value of
`RuntimeConstants.FILE_RESOURCE_LOADER_CACHE`, which in Velocity 2.4.1 is
`resource.loader.file.cache` — the **file** loader's cache key, not the class loader's. The base class
sets that key only when the application declares a priority template path, and sets it to `false`
there, so for every shipped configuration the test reads `null` or `false` and the non-caching loader
is what a Qlue application actually runs. The caching arm is therefore reachable only through a raw
`qlue.velocity.raw.` passthrough, and it is the arm that now restates the inherited default. It is
kept, with a comment saying so, because the choice between the two loaders reads as one decision and
splitting it across two classes would hide half of it; whether that arm should key off
`resource.loader.class.cache` instead is a separate question about the subclass, not about F22, and is
not in this task's package. No other shipped factory is affected — `ClasspathVelocityViewFactory` is
the only subclass of `VelocityViewFactory` in `src/main`.

*Tests:* `ViewFactoryRenderTest.theBaseFactorysDefaultPropertiesDeclareAClassLoaderItNeverConfigures`
is inverted to `.theBaseFactorysDefaultPropertiesConfigureTheClassLoaderTheyDeclare`, keeping its
former name and F22's mechanism — Velocity's `"Unable to find 'resource.loader.class.class'
specification in configuration"` — in its javadoc. The assertion on the property map flips from
`assertNull` to the loader's name, and the `assertThrows` around `engine.init()` becomes a bare
`init()` that has to succeed: a subclass that supplies `init()` and `constructView()` and inherits the
properties starts, which is what the class comment invites. A third assertion is added, because the
new default could otherwise silently change what production runs — the shipped subclass's properties
still name `NonCachingClasspathResourceLoader`, read through a new
`ProductionRenderProbe.classpathFactoryVelocityProperties()` (the method is `protected`, so the probe
is where a test can reach it, for the same reason `defaultVelocityProperties()` lives there).
`ProductionRenderProbe.createClasspathEngine()`'s comment, which said in so many words that the base
class's properties alone do not produce a working engine, is corrected rather than left to rot.
*Ledger:* **unchanged**, verified rather than assumed — 1,012 invocations, SAFE 481,
KNOWN_VULNERABLE 68, SUPPRESSED_BY_DESIGN 415, SUPPRESSED_UNINTENDED 12, REJECTED 36, re-tallied from
`build/reports/canoe/matrix.csv` after the change. This is engine configuration: it decides whether an
engine starts, not what any template renders, and no corpus template is loaded through the `class`
loader by a factory built from the base properties.
*Coverage:* **unchanged** — Canoe 292/303 = 96.37% (floor 95), HtmlEncoder 315/320 = 98.44%
(floor 98), `reallyProcessChar()` 169/174 = 97.13% (floor 96), `setTagAttributeContext()` 10/10,
`normalisePlainTextAttributeNames()` 20/20. No branch was added or removed; the changed line is a
straight-line `setProperty()` in a class the gate does not cover, and the eleven dead outcomes are the
same eleven. No floor moves and `build.gradle` needs no edit.
*F22's exemption in `MatrixReportTest.FINDINGS_WITHOUT_CASES` is kept and reworded*, not deleted:
that map fails for a *stale* exemption only when the finding has acquired corpus cases, and F22 cannot
— it was decided at `init()`, before any template exists, and a case is a template plus a payload. The
entry now says the finding is closed by R22 and names the test, in the shape F21's entry uses.
*Review:* F22's section in `CANOE-SECURITY-REVIEW-2026-07-25.md` carries a `Resolved — R22` note, its
glance-table row says **fixed in R22**, and its `Verified` block — which named the test by its former
name and said `init()` must throw — is rewritten to the successor, in the shape F21's is. The
`resource.loader.file.cache` observation above is recorded there too, as an open question about the
subclass rather than a second finding.
*Gates:* `./gradlew test` (6,128 tests, 0 failures, 0 skipped) and `canoeCoverageGate` green.
`browserTest` was **not** run: there is a known hang in this environment, owned by R28, and R22 changes
nothing the browser tier renders — it renders through `CanoeTestSupport`, whose engine declares the
`string` loader only and never calls `buildDefaultVelocityProperties()`, so no browser-tier
configuration is touched at all.

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

**R23 — Make the `$_x` bypass recognise formal notation** — ✅ **DONE**
*Closes:* a footgun recorded in F12's notes. *Depends on:* nothing.
*Landed:* the recommended fix and not the alternative — matching, not a diagnostic.
`CanoeReferenceInsertionHandler` gains `SAFE_REFERENCE_PREFIX3` (`${_x.`) and `SAFE_REFERENCE_PREFIX4`
(`$!{_x.`) beside the two public constants that were already there, kept public because those two are
API, and the four are collected into one private `SAFE_REFERENCE_PREFIXES` list that
`referenceInsert()` walks. All four spellings now behave identically.

**What Velocity actually passes was measured, not assumed.** `ASTReference.render()` calls
`EventHandlerUtil.referenceInsert(rsvc, context, literal, value)` with the node's `literal` — the
reference's source text verbatim — so velocity-engine-core 2.4.1 hands the handler `$_x.asis($data)`,
`$!_x.asis($data)`, `${_x.asis($data)}` and `$!{_x.asis($data)}` respectively, braces included and
nothing else attached. Leading schmoo is stripped before the literal is built (`#$_x.asis($v)` arrives
as `$_x.asis($v)`, `\\${_x.asis($v)}` as `${_x.asis($v)}`) and a preceding block comment contributes
nothing, so neither can hide the prefix.

**There is no fifth spelling.** `ASTReference.getRoot()` branches on the opening token in one chain
— `startsWith("$!{")`, `startsWith("$!")`, `equals("${")`, `startsWith("$")`, and a RUNT fallback for
text that is not a reference at all — so `$name`, `$!name`, `${name}` and `$!{name}` are the whole
grammar and the four-prefix list is not a sample of it. Whitespace inside the braces is *not* a missed spelling: Velocity's lexer enters the
reference state only on the exact token `${` or `$!{`, so `${ _x.asis($v) }`, `$!{ _x.asis($v) }` and
a newline after the brace are literal template text and the handler never fires for the tool call at
all (it fires for the inner `$data`, which is encoded, and the braces reach the page). `${_x .asis($v)}`
is a parse error. Both are asserted rather than reasoned about, in
`whitespaceInsideTheBracesIsNotAReferenceAtAll`.

**A list, deliberately, and not a matcher.** This is the one comparison in Canoe whose *true* branch
emits attacker-reachable data unencoded, so the two error directions are not comparable: a missed
spelling costs a second encoding pass, which is visible and harmless, while a spurious match is XSS.
Trimming, a regular expression or a lenient name comparison would buy convenience with the asymmetry
pointing the wrong way. The trailing dot carries the safety — it is what keeps `$_xy.`, `${_xyz.}`
and `${_xtra.` off the list, since each differs from the bypass exactly where the dot would be — and
the reasoning is in `SAFE_REFERENCE_PREFIXES`'s javadoc where a future editor will meet it.

*Tests:* `VelocityIntegrationTest.formalNotationSilentlyDefeatsTheBypassBecauseThePrefixIsMatched`
`Literally` is inverted to `.everySpellingOfTheBypassBypassesIncludingFormalNotation`, keeping the
former name and the mechanism in its javadoc. Its last assertion inverts in shape as well as in
sense: the old one asserted the formal form was byte-identical to *never having called the tool*, the
new one asserts it is byte-identical to the *short form* and `assertNotEquals` to the plain
reference. `aLongerToolNameIsNotABypass` is extended from one spelling to all four, against `_xy` and
`_xtra`, because over-matching is the only way this change could have done harm; a new
`whitespaceInsideTheBracesIsNotAReferenceAtAll` pins the non-spelling above.
`theTwoDeclaredBypassPrefixesBypass` is renamed `theTwoShortBypassPrefixesBypass`, former name in its
javadoc: the handler declared two prefixes when that test was written and declares four now, so the
old name pointed at the wrong set. The class javadoc's "three traps" list becomes two, with the
closed one recorded as closed rather than dropped.
*Ledger:* **unchanged**, verified rather than assumed — 1,012 invocations, SAFE 481,
KNOWN_VULNERABLE 68, SUPPRESSED_BY_DESIGN 415, SUPPRESSED_UNINTENDED 12, REJECTED 36, re-tallied from
`build/reports/canoe/matrix.csv` after the change. No corpus template uses `$_x` in any spelling: the
corpus is about what the encoder does to a payload, and a case that bypassed the encoder would be
measuring nothing. The three mentions of `$_x.asis()` in `CanoeCorpus.java` are prose about why a
suppressed value drives developers to it.
*Coverage:* **unchanged** — Canoe 292/303 = 96.37% (floor 95), HtmlEncoder 315/320 = 98.44%
(floor 98), `reallyProcessChar()` 169/174 = 97.13% (floor 96), `setTagAttributeContext()` 10/10,
`normalisePlainTextAttributeNames()` 20/20. `CanoeReferenceInsertionHandler` is not a gated class, and
the change replaces a two-term `||` with a loop, so the gate's own branch inventory is untouched and
`build.gradle` needs no edit.
*F12's exemption in `MatrixReportTest.FINDINGS_WITHOUT_CASES` is left alone*, checked rather than
assumed: it says F12 is "about Velocity reference forms rather than about a sink", which is still
true and still names `VelocityIntegrationTest`. R23 closed a footgun recorded in F12's notes, not
F12; **F12's status does not move** and R24 still owns it.
*Docs:* `qlue_user_guide.md`'s `$_x` section said in so many words that the formal spellings "are
encoded like any other reference, and `asis()` written that way silently does nothing". That sentence
is now false, so it is rewritten — all four spellings listed as bypasses, plus the two things a reader
now needs instead: that a name merely beginning with `_x` is not a bypass, and that whitespace inside
the braces is not a reference. The edit is confined to the paragraph R23 falsifies; **R25 still owns
the documentation rewrite**.
*Review:* F12's `Verified` block in `CANOE-SECURITY-REVIEW-2026-07-25.md` carries a `Resolved — R23`
note scoped explicitly to its `${_x.` paragraph, saying that F12 itself is untouched and still live.
The glance-table row for F12 still points at R24 and adds the same scoping.
*Gates:* `./gradlew test` (6,129 tests, 0 failures, 0 errors, 0 skipped) and `canoeCoverageGate`
green. `browserTest` was **not** run: there is a known hang in this environment, owned by R28, and
R23 changes nothing the browser tier renders. The browser tier replays corpus templates, and no
corpus template uses `$_x` in any spelling — a case that bypassed the encoder would be measuring
nothing — so the bytes handed to Chromium are identical before and after.

*Done when:* `VelocityIntegrationTest.formalNotationSilentlyDefeatsTheBypassBecauseThePrefixIs`
`MatchedLiterally` is inverted. ✅

---

**R24 — Encode `#set` references where they are output, not where the `#set` ran** — ✅ **DONE**
*Closes:* F12. *Depends on:* **R4 and R5 — see trap 2 in §1** (discharged before this landed).

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

*Landed:* the handler detects the nested render and returns the value untouched, so it is encoded
once, later, where it is printed. **The approach this section suggested does not exist**, and that
was measured in velocity-engine-core 2.4.1 rather than argued: `InternalContextAdapter` — through
`InternalHousekeepingContext`, `InternalWrapperContext` and `InternalEventContext` — carries a
template-name stack, a macro-name stack, the introspection cache, the current `Resource` and the
macro libraries, and **nothing about which node is evaluating**. `ASTStringLiteral` has no
`render(context, writer)`; interpolation exists only in `value(InternalContextAdapter)`, which
allocates a private `StringBuilderWriter` and renders the literal's node tree into it, and
`evaluate()` routes through `value()`. `ASTReference.render()` writes the handler's return value to
whichever writer it was handed and never shows it to the handler. The second suggestion — Canoe
exposing "the context this writer will be in later" — is not a thing Canoe can know: at `#set` time
the bytes that decide it have not been written.
*Detector:* an `ASTStringLiteral` frame below the handler is not a heuristic for the nested render,
it *is* the `StringBuilderWriter` whose `toString()` becomes the `#set` variable. `StackWalker`
without `RETAIN_CLASS_REFERENCE`, `StackFrame.getClassName()` compared with `equals` against
`org.apache.velocity.runtime.parser.node.ASTStringLiteral`. **And one frame further, because
deferring is a claim about what becomes of the string and not about the literal.**
`ASTStringLiteral.value()` has exactly one caller per invocation, so the frame directly below the
literal *is* its consumer, and three of them never print the string: `Evaluate` compiles it as VTL,
`Parse` resolves it to a template and renders that, `Include` resolves it to a resource and copies
the bytes. For those the value is encoded here, exactly as it was before R24 — see *the directives
that must not defer* below.
*Bound:* the walk skips to the first `org.apache.velocity.` frame and stops at the first frame after
that run ends, with `limit(64)` as a backstop; it short-circuits at the first literal. The boundary
argument was checked against the shapes that put frames in between rather than assumed — measured
below `referenceInsert()`, the node is 5 frames down for a bare interpolated `#set` and for an
interpolated macro argument, 8 through a `#parse`d fragment inside one, 9 through a `#foreach` inside
one, 10 through a macro invoked inside one, and inside the first unbroken run of Velocity frames in
every case, because every directive passes the literal's writer down unchanged. When the answer is
"no", the walk reads the Velocity run and one frame past it: 11 frames for a reference in ordinary
template text, 20 for one inside a macro inside a `#foreach`. Without the rule it would read ~100 to
the bottom of the JUnit stack, and more in a container. **Every bound fails towards encoding, which
is the safety argument and is in the javadoc:** a missed nested render costs a double encoding — F12,
visible and harmless — while a spurious one puts raw attacker data in the page, so an uncertain stack
must resolve to encoding. That is true of the consumer check too: no literal, a literal past the
limit, a literal below the run, a literal with *no readable consumer frame*, and a literal whose
consumer is on the list all answer "encode".
*Benchmark:* `NestedRenderDetectionTest.theStackWalkCostsAboutTwoMicrosecondsPerReference` renders a
reference-dense template and times the walk **in situ**, from inside a `Canoe.encode()` override one
frame below the handler, because the cost is a function of the stack it is called from (from a bare
JUnit stack, with no Velocity frames at all, the walk runs to the 64-frame limit and costs 8.7 µs —
a figure that describes nothing that can happen in production). Measured: **~2.1 µs per reference**
for the walk; a whole render goes **3.1 → 5.2 µs per reference** for the dense template (+67%) and
**14.0 → 16.2 µs** for one with realistic markup density (+15%), A/B against the same build with the
detector call disabled. About 0.6 ms for a page with 300 references. Not free, and not hidden: the
numbers are regenerated into `build/reports/canoe/reference-insertion-cost.txt` on every run.
*Tests:* `VelocityIntegrationTest.anInterpolatedStringLiteralIsEncodedTwice` is inverted to
`.anInterpolatedStringLiteralIsEncodedOnceAtThePositionItIsPrintedAt`, keeping the former name and
the review's golden in its javadoc, and asserting exact bytes on both sides of the tag boundary —
`<p>Hello &lt;b&gt;</p>` in body text, where `htmlWhite()` passes the author's space through, and
`<a title="Hello&#32;&lt;b&gt;">` in an attribute, where `html()` does not. The author's own literal
text is encoded with the value because the built string is one value by the time it is printed; both
render identically in a browser. `.anInterpolatedSetInsideAScriptOrHandlerSilentlyProducesNothing` is
inverted to `.anInterpolatedSetInsideAScriptNoLongerLosesItsValue` — the `<script>` case flips from
`<p>x</p>` to `<p>x&lt;b&gt;</p>`, exactly as the plan predicted.
`.aPlainSetAssignmentSingleEncodesForThePositionTheValueIsPrintedAt` keeps its name and its first
assertion; its second inverts, and the two spellings now agree.
`.doubleEncodingNoLongerCoversAnyClassOfMissingClassification` is **retired because its precondition
is gone** — there is no double encoding left to neutralise anything with — and replaced by
`.theSetPathAndTheDirectPathAgreeAtEverySinkTheAccidentCovered`, which carries its whole reasoning in
its javadoc and asserts the stronger property the retired test was circling: the two paths are
byte-identical at each of the three sinks the accident touched. Five new tests:
`.asisOnAnInterpolatedSetValueNowEmitsRawData`,
`.everyShapeThatRendersIntoALiteralCarriesTheValueRawToWhereItIsPrinted` (macro argument, `#foreach`
body, `#parse`d fragment, and a comparison — `#if("$data" == "<b>")` answered `no` before R24 and
answers `yes` now, because it was comparing the *encoded* value), and the three that own the
consumer rule: `.evaluateOfAnInterpolatedLiteralIsStillEncodedRatherThanCompiled`,
`.aParsedOrIncludedTemplateNameBuiltFromAReferenceIsEncodedAndNotDeferred` and
`.aSetInsideAParsedOrEvaluatedTemplateStillDefers`. A `set-interpolated` row joins the
`directives()` table, where it produces what a bare reference produces. New file
`NestedRenderDetectionTest` (11 tests) owns the detector: it feeds
`encodingMustBeDeferred(Stream<String>)` synthetic stacks so the bound is asserted **exactly and
without timing** — a literal below the Velocity run, a literal past the frame limit, a stack with no
Velocity in it, four near-miss class names, each of the three non-printing consumers, a literal with
no consumer frame, and a `Parse` frame that is *not* the consumer — and counts the frames consumed
(6 for a realistic stack with 500 frames under it).
*The directives that must not defer, which is the sharpest thing in this task.* The false-positive
check the plan asked for holds for `#macro` — a macro body renders to whatever writer the call site
had, and a macro *argument* built from a literal is a genuine nested render that defers correctly.
But three directives call `ASTStringLiteral.value()` for something that is never printed through a
reference, so deferring to them defers to nothing:

* **`#evaluate("…$data…")`** interpolates the literal and then **parses the result as VTL**. A
  deferred value would be compiled: a payload of `#set($injected = 1)$injected` would render as `1`,
  which is server-side template injection and a strictly worse outcome than the XSS this handler
  exists to prevent.
* **`#parse("$data")`** interpolates the literal into a **template name**, then parses and renders
  that template; **`#include("$data")`** into a **resource name**, then copies its bytes to the
  writer unparsed. A deferred value is an attacker-chosen path — traversal, disclosure, and for
  `#parse` template injection by a second route.

The detector reads one frame past the literal and refuses to defer for those three, which is not a
new control but **the pre-R24 behaviour, kept**: the value is encoded at the reference as it always
was, and that is sufficient because `html()`/`htmlWhite()` are allowlists of `[a-zA-Z0-9]` plus a
little whitespace — `$`, `#`, `(`, `.` and `/` all come back as numeric character references, so
neither VTL nor a path can be reconstituted from the output. The check is deliberately *one* frame
and not "anywhere in the run": a `#set` inside a `#parse`d fragment — the commonest nested render
there is — carries a `Parse` frame four frames below the literal, three below its real consumer,
and widening the rule
would be safe but would leave F12 alive in most of an application's templates. Two residuals are
recorded in the javadoc rather than left to be found: a *custom* `userdirective` that compiles or
resolves its literal argument is not on the list and is §2.5's, like `#evaluate($t)`; and under a
repackaged (shaded) Velocity no class name matches at all, so nothing is ever deferred and the whole
detector degrades to the pre-R24 double encoding — a silent loss of the fix, in the safe direction.
**The underlying hole is pre-existing and untouched.** The plain spellings `#set($t = $data)#evaluate($t)`
and `#parse($data)` hand these directives the raw value and always have, because a bare reference
argument never reaches `value()` through a literal and so never fires the handler at all. §2.5 is the
answer there — the attacker controls data and never the template — and R25 owns saying so in the
guide. What R24 must not do, and now does not, is widen it to a second spelling.
*`$_x.asis()` consequence, prominently:* **`$_x.asis($msg)` on an interpolated `#set` value now emits
raw data**, where it emitted singly-encoded data before — the `#set` had encoded it once and `asis()`
was declining to do the second pass. `asis()` is the documented, unguarded bypass and this follows
from the design, but it is a real change in what that combination does: it was safer than it said it
was, and a developer who checked the rendered page saw escaped markup and could have concluded the
framework was still protecting them.
*Ledger:* **unchanged**, verified rather than assumed — 1,012 invocations, SAFE 481,
KNOWN_VULNERABLE 68 (all F6), SUPPRESSED_BY_DESIGN 415, SUPPRESSED_UNINTENDED 12, REJECTED 36,
re-tallied from `build/reports/canoe/matrix.csv` after the change. **No corpus template contains a
Velocity directive at all** — checked over the generated CSV's own `template` column, where the only
18 rows containing a `#` are `url.href-fragment`'s URL fragment — so no corpus row can produce an
interpolated string literal and none could move. The row R12's note warned about is the one asserted
in `theSetPathAndTheDirectPathAgree…`: it is a test, not a ledger row, and its `#set` half moved from
mangled to live-under-F6 exactly as predicted. Nothing went from safe-by-accident to
`KNOWN_VULNERABLE` in the ledger because the ledger never had that accident in it.
*Coverage:* **unchanged** where it is gated — Canoe 292/303 = 96.37% (floor 95), HtmlEncoder 315/320
= 98.44% (floor 98), `reallyProcessChar()` 169/174 = 97.13% (floor 96), `setTagAttributeContext()`
10/10, `normalisePlainTextAttributeNames()` 20/20. `CanoeReferenceInsertionHandler` is not a gated
class; for the record it goes from 6 to 18 branch outcomes (the new `if`, the walk's two `startsWith`
predicates, the literal scan and the consumer test) and is **18/18, 106/106 instructions, 100%** —
the new code has no untested branch. `build.gradle` needs no edit and the gate's dead-branch
inventory is untouched.
*`MatrixReportTest.FINDINGS_WITHOUT_CASES`:* F12's exemption is **kept and reworded**, which is the
decision the two halves of that map's assertion actually call for. It is not stale in the sense the
second assertion catches — a stale exemption is one whose finding has *acquired* corpus cases, and
F12 has none, verified against `matrix.csv` rather than assumed. And the reason it gave is still the
true one: a corpus case is a template plus a payload at a sink, F12 is about a Velocity reference
*form*, and counting it would mean restating the same defect once per sink. The rewording follows
F21's and F22's: it records that R24 closed the finding, says why the exemption survives its closure,
and names the two tests that own it.
*Docs:* two paragraphs in `qlue_user_guide.md`, both confined to what R24 changes. The encoding table
gains the rule that a `#set` value is encoded where it is printed, with the note that the author's
own literal text goes through the encoder with it; the `$_x.asis()` bullet gains the consequence
above, because that bullet now describes something that puts attacker bytes on the page where it used
to describe something that did not. **R25 still owns the documentation rewrite.**
*Review:* F12 carries a `Resolved — R24` note at the head of the finding in the F18/F21/F22 style,
recording the measurement, the detector, the bound, the consumer rule, the asymmetry, the cost, the
one behaviour change and the removal of the F6 masking. The R23 note inside it is left as R23 wrote it, with a parenthesis
pointing at the new note rather than a rewrite of a dated record. The glance-table row says
**fixed in R24**.
*Gates:* `./gradlew test` (6,146 tests, 0 failures, 0 errors, 0 skipped) and `canoeCoverageGate`
green. `browserTest` was **not** run: known hang in this environment, owned by R28. **R24 changes
nothing the browser tier renders** — the tier replays corpus invocations through
`VerdictEvaluator.render`, and no corpus template uses `#set` or any other directive, so the bytes
handed to Chromium are identical before and after.

*Done when:* `VelocityIntegrationTest.doubleEncodingAccidentallyNeutralisesAnUnrecognisedHandler` is
retired *because its precondition is gone*, not because F12 was fixed under it. ✅ — retired under the
name R4 and R5 left it with, `.doubleEncodingNoLongerCoversAnyClassOfMissingClassification`, and for
that reason.

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

**Observed while running R20's gates, and worth having before R28 starts.** The other two engines are
*not* missing here any more: `PLAYWRIGHT_BROWSERS_PATH=/opt/playwright` holds `firefox-1532`,
`firefox-1538`, `webkit-2311` and `webkit-2336` alongside the Chromium builds, and with that variable
exported `browserTest` launches Firefox and runs the corpus against it — the first ~100 assertions
pass. It then **hangs** on `BrowserCorpusTest.theBrowserAgreesWithTheLedger > FIREFOX url.action /
JS_URL/plain`, a `javascript:` URL in a `<form action>` that `fullInteraction()` submits, with no
progress for ten minutes and no timeout to end it. Nothing in R20 touches that row (it is
`browserRelevant`, R20's changed rows are not, and its rendered output is unchanged), so this is an
engine/harness interaction and not a regression — but it means R28 needs a per-case timeout and a
decision about form submission under Firefox before the three-engine run can be a gate rather than a
hang. R20's own browser gate was therefore run with `PLAYWRIGHT_BROWSERS_PATH` pointed at a directory
holding only the Chromium builds, which reproduces the single-engine environment every browser-tier
figure in this plan was measured in: 91 passed, 2 skipped.

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
| F11 — unquoted attribute references vanish | Low | R19 ✅ (`TAG_ATTR_VALUE_BEFORE` shares `TAG_ATTR_VALUE`'s case label); the `COMMENT_*`/`DOCTYPE_*` half of the finding is deliberately left suppressing |
| F12 — `#set` interpolation uses the wrong context | Low | R24 ✅ (an `ASTStringLiteral` frame below the handler means the value is going to Velocity's own writer, so it is returned unencoded and encoded once where it is printed — unless the frame below *that* is `Evaluate`, `Parse` or `Include`, which compile or resolve the string rather than printing it and so keep the pre-R24 encoding; one behaviour change recorded with it — `$_x.asis()` on such a value now emits raw data); the `${_x.` footgun recorded in F12's notes was closed separately by R23 ✅ (all four Velocity reference spellings bypass) |
| F13 — `[Encoding Error]` branch unreachable | Medium | R21 ✅ (typed `CanoeEncodingException` found on the cause chain; the marker is gone, the flush is suppressed and the response is reset so the request can fail cleanly) + R20 ✅ (the rejection table triaged: `<br/>`, names to 127 characters and a second DOCTYPE render; the literal `<`, `</ p>`, `</>` and C0 controls stay rejected, each with its reason recorded) |
| F14 — comment ending in three dashes never closes | Low | R16 |
| F15 — `url()` corrupts legitimate URLs five ways | Low | R11, R12 |
| F16 — `js()` truncates astral; `css()` escapes unterminated | Low | R13 |
| F17 — the reset defeats JS suppression | High | R2 |
| F18 — a comment before the DOCTYPE is illegal | Low | R18 ✅ (`elementSeen`/`doctypeSeen` replace `tagCount`; a comment above the DOCTYPE renders) |
| F19 — `onreadystatechange` dead branch | Critical | R4 |
| F20 — policy-bearing attributes arrive verbatim | Medium | R5 |
| F21 — `currentContext()` can never return `CTX_CSS` | Low (latent) | R14 ✅ (constant + dead arm deleted) |
| F22 — base factory declares an unconfigured loader | Low | R22 ✅ (`resource.loader.class.class` set to `ClasspathResourceLoader` in `buildDefaultVelocityProperties()`, so an engine built from the base class's own properties starts; `ClasspathVelocityViewFactory` keeps its override of the reloading variant) |
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
R20 rejection-table triage              <- done
-------------------------------------------- tokenizer faithful, pages stop dying here
R22 resource loader key
R23 formal-notation bypass
R24 #set context                        <- done; was after R4 and R5
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

   **Discharged by R20.** `MAX_TAGNAME_LEN` is 128, so both caps sit at 127 characters, and the
   observation's own example is now a corpus row rather than a note:
   `shape.framework-length-attribute-name` renders `<div data-controller-target-value-for-the-widget>`,
   43 characters, which was a failed request before. The cap is kept rather than removed because the
   buffer is fixed-size by design — a bounded, allocation-free name scan is the property, and 128 is
   where the bound went.

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
