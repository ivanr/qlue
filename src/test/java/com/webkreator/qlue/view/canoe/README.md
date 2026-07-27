# The Canoe test suite

This directory and its subpackages are the adversarial test suite for **Canoe**, the context-aware
output encoder in `com.webkreator.qlue.view`. It was written against
`CANOE-SECURITY-REVIEW-2026-07-25.md` (twenty-four findings) to the plan in `PLAN.md`.

Read this before you change anything here, and *especially* before you "fix" a failing test.

---

## The one thing to know: a failing `KNOWN_VULNERABLE` test is good news

Canoe was broken in ten ways an attacker who controls only data could exploit. A suite that
asserted *desired* behaviour would have been red from its first commit and useless as a regression
net. A suite that asserted only *current* behaviour would have enshrined the vulnerabilities.

So every case in the corpus carries an explicit, reviewed **verdict**:

| Verdict | Meaning | What the test asserts |
|---|---|---|
| `SAFE` | Attacker data reaches the sink inert | It stays that way |
| `KNOWN_VULNERABLE` | Attacker data reaches the sink **live**, at a sink that executes | The vulnerability is *still present*, and the case cites a finding. **The count is zero and is asserted to be** (R26) |
| `ACCEPTED_RESIDUAL` | Attacker data reaches the sink live, and the sink is **not code execution** | The data *still arrives* — the row fails when it stops. Cites a finding, names a `ResidualSink`, and is on a pinned list that may only shrink |
| `SUPPRESSED_BY_DESIGN` | Canoe emits the empty string, and that is the intent | The suppression holds |
| `SUPPRESSED_UNINTENDED` | Canoe emits the empty string where it should have encoded | Fail-safe, but a defect; tracked separately so the defect count can reach zero |
| `REJECTED` | Canoe raises an encoding error | The rejection, its message and its reported position |

**If a `KNOWN_VULNERABLE` test fails, the vulnerability has gone away.** That is the design
(`PLAN.md` §2.1) and it is the moment the suite exists for. What to do:

1. **Do not delete or weaken the test.** It is doing exactly its job.
2. Find out *why* it stopped reproducing. Usually a fix landed in `Canoe.java` or
   `HtmlEncoder.java`; occasionally a payload or an oracle changed and the flip is spurious.
3. If it is a real fix, change the case's verdict in `CanoeCorpus` from `KNOWN_VULNERABLE` to
   whatever it now is, and say so in the commit message with the finding ID.
4. Check whether the finding is *fully* closed. `build/reports/canoe/matrix.md` groups the roster by
   finding; one row flipping does not close a finding with twelve.
5. Update `CANOE-SECURITY-REVIEW-2026-07-25.md` — the finding gets a "fixed in" note — and the
   remediation list if the item is done.

**The same is true of a failing `ACCEPTED_RESIDUAL` test, and for the same reason.** That verdict
says the data still reaches a sink somebody decided is not code execution — an off-origin link, an
off-origin image, an off-origin form action, the 68 F6 rows R9 scoped out by design. It is not a
weaker `SAFE`: the oracle still observes `KNOWN_VULNERABLE` for those rows and `matches()` accepts
that observation *and nothing else*, so the row goes red the day the value stops arriving. When it
does, that is a residual closing, and the answer is to delete its line from
`CanoeCorpusTest.PINNED_RESIDUALS` and re-verdict the case — not to widen anything.

Going the other way is harder on purpose. A new `ACCEPTED_RESIDUAL` row needs a sink class, a
finding, and a pin-list entry, and three tests fail until it has all three; the count of
`KNOWN_VULNERABLE` rows is asserted to be **zero**, so a newly discovered live-and-executing vector
is a build failure and a conversation rather than a table entry.

The failure message on every ledger assertion says this too, at the point of failure, because
nobody reads a README at 2am.

The converse also holds and is the reason the ledger is *asserted* rather than recorded:
`CanoeCorpusTest.ledgerMatchesObservedBehaviour` derives the verdict independently, from
`VerdictEvaluator`, and fails when the corpus and the observation disagree. Four reviews of this
suite found wrong verdicts among the hand-written ones; that test is what caught the fifth.

---

## Conventions

### The non-blind-oracle rule

**Every oracle in this suite must have a test proving it can fail.** A security test that never
fails is indistinguishable from a security test that is broken, and both are green.

The pattern is always a *pair*:

- the oracle must fire on a deliberately unencoded render — normally the payload routed through
  `$_x.asis()`, which is the supported way to put raw bytes into the output; and
- the oracle must **not** fire on the identical payload through an ordinary encoded reference.

The second half is the one that gets left out, and without it "the oracle noticed" is
indistinguishable from "the oracle notices any change at all". Examples:
`CanoeCorpusTest.theLedgerOracleDetectsAWrongVerdict`,
`DomEquivalenceTest.aDeliberatelyUnencodedRenderBreaksTheOracle` plus
`theSamePayloadsThroughAnEncodedReferenceDoNotMoveTheSkeleton`,
`ParserSteeringTest.aDeliberatelyUnencodedRenderBreaksTheProperty`,
`TemplateFuzzTest.theOracleCatchesAnUnencodedRenderInEveryPositionItGenerates`, and the whole of
`DetectorSelfTest` in the browser tier.

### Assert on what the browser will consume, not on what Canoe emitted

```java
// Wrong: asserts on Canoe's bytes.
assertFalse(rendered.contains("javascript:"));

// Right: asserts on what the parser hands the next consumer.
String value = result.decodedAttr("a", "title");
assertEquals("javascript:alert(1)", value);
```

`html()` turns `javascript:alert(1)` into `javascript&#58;alert&#40;1&#41;`, which a naive string
assertion calls safe. The HTML parser decodes it back before the value is used. That single asymmetry
is what the whole review is about; `RenderResult.decodedAttr()` and `decodedText()` exist so tests get
it right by default.

The example has moved twice, and where it ended up is the point of Phase A. It was
`decodedAttr("form", "onsubmit")` against `');alert(1)`, which was F1 and is the sharper illustration
— an event handler value is *compiled*; R4 suppresses every `on*` value, so that template renders
nothing to decode. It then became `xlink:href`, which was F3; R6 routes that name to `url()`. The
example above is a `title`, where the decode is harmless and the asymmetry is identical — and there
is no longer a *dangerous* sink in the component to illustrate it with, which is what R5, R6 and R7
were for.

### Pure ASCII in source

Assertion *data* is pure ASCII. Non-ASCII payloads are built from escapes — `"İ"`,
`Character.toChars(0x10027)`, `new String(new char[]{0xD800})` — and never pasted, because a source
tree edited by several tools with different encoding assumptions is a place where a pasted code
point silently mutates, and a payload that silently mutates is a test that silently stops testing
what it says. `build.gradle` sets `options.encoding = "UTF-8"` precisely so that such a character
compiles instead of failing loudly, which is why the rule has to be a rule rather than an accident
of the toolchain. Javadoc prose may use typographic characters.
`TemplateFuzzTest.everyFragmentIsPureAscii` enforces it for the generated corpus.

### Every case whose data reaches the sink live cites a finding

Both live verdicts, `KNOWN_VULNERABLE` and `ACCEPTED_RESIDUAL`. `XssCase` refuses to build
otherwise. A verdict with no citation is a review failure, not a test detail: it is how a suite
turns into a record of "whatever the code did". If you find something new, open a finding in the
review document first, then cite it.
`MatrixReportTest.everyRowThatReachesItsSinkLiveCitesAFindingTheReviewHas` goes one step further and
resolves the citation against the review's own glance table, so a renumbered or invented finding ID
fails rather than reading plausibly.

### Cases live in the corpus, properties live in `property/`

`CanoeCorpus` holds *data*: one template, its sink, the payloads worth attacking it with, and a
verdict per (template, payload) pair. Files under `velocity/` consume it and add the thing a ledger
cannot state about itself — a partition, a completeness guard against an external spec, a property
over the whole cross-product. Do not re-declare templates in a test file; add a case.

The exception is a finding that needs a payload shaped for one template. The corpus deliberately runs
one shared payload catalogue against everything, because that is what makes it a fair comparison, so
a finding like F17 or F24 that needs a bespoke value gets a dedicated test instead. Say so in the
javadoc when you do it.

---

## The two tiers

| | `src/test/java` (Velocity tier) | `src/browserTest/java` (browser tier) |
|---|---|---|
| Command | `./gradlew test` | `./gradlew browserTest` |
| Needs | nothing — hermetic, no network, no browser | Playwright + browser binaries |
| Asserts on | bytes, contexts, jsoup-decoded values | script execution, dialogs, requests to a sentinel origin, navigations, DOM divergence |
| Runtime | a few seconds | a couple of minutes |

They share one corpus. The Velocity tier renders every case; the browser tier consumes the *same*
`XssCase` objects, serves the rendered bytes from a loopback origin, and asserts on effects. Declaring
the cases twice would guarantee the two drift apart.

`browserTest` is deliberately **not** wired into `check`, and Playwright is scoped to the
`browserTest` source set, so `./gradlew test` never sees the ~100 MB driver bundle.

```
./gradlew test                  # everything hermetic: ~5,500 tests, seconds
./gradlew check                 # test + the T30 branch-coverage gate
./gradlew playwrightInstall     # once, to get the browsers
./gradlew browserTest           # 155 tests
```

If Firefox or WebKit are not installed they **skip with a reason attached** rather than silently
contributing nothing — `EngineRosterTest` has one row per engine, always, because a report with no
Firefox rows is otherwise indistinguishable from a report where Firefox was never asked for.

### The fuzzer

`TemplateFuzzTest` (T31) generates templates and runs them through the same oracles. It is seeded
from a system property so the hermetic run is reproducible:

```
./gradlew test --tests '*TemplateFuzzTest*'                              # what CI runs
./gradlew test --tests '*TemplateFuzzTest*' \
    -Dcanoe.fuzz.seed=$RANDOM -Dcanoe.fuzz.iterations=1000000            # a hunt
```

It found F24 on its first run. A counterexample is minimised automatically and printed with its seed;
promote it into `CanoeCorpus` as a permanent case unless the corpus structurally cannot express it.

---

## How to add a case

1. Add it to `CanoeCorpus`, in the method for its Appendix A section, with:
   `.section(...)`, `.template(...)`, `.sink(kind, selector, attribute)`, `.payloads(...)`,
   `.verdict(...)`, `.finding(...)` if any pairing reaches its sink live, and `.residualSink(...)`
   if any pairing is `ACCEPTED_RESIDUAL`.
2. Run `./gradlew test`. `CanoeCorpusTest.ledgerMatchesObservedBehaviour` will tell you if your
   verdict disagrees with what Canoe actually does. **Do not change the verdict to match the
   observation without thinking about it** — that is how the ledger rots into a rubber stamp for
   bugs. Work out which one is wrong.
3. Add `.browserRelevant()` if a browser can confirm the outcome, and
   `.notBrowserObservable(...)` for pairings no shipping engine acts on (a `javascript:` URL in
   `srcset`, `expression()`, a dead event). The guard only permits that flag on a
   `KNOWN_VULNERABLE` browser-relevant pairing, because anywhere else the browser tier already
   expects silence and the flag would hide the reasoning rather than record it.
4. Add a `.note(...)` when the reason for the verdict is not obvious from the template. The notes are
   read; several of them are the only record of why a verdict is what it is.
5. Check `build/reports/canoe/matrix.md`, which is regenerated by the same run.

## Where to look

| Question | File |
|---|---|
| What does the state machine do here? | `CanoeStateMachineTest` |
| Which attribute names are recognised? | `velocity/AttributeNameMatrixTest`, `velocity/EventHandlerMatrixTest` |
| What do the encoders emit? | `HtmlEncoderTest`, `HtmlEncoderUrlTest` |
| What does Canoe reject? | `CanoeRobustnessTest` |
| Does chunking / concurrency / parser steering matter? | `property/ChunkInvarianceTest`, `property/ConcurrencyTest`, `property/ParserSteeringTest` |
| Is the URL oracle right? | `corpus/UrlOracleTest` (expectations derived from Node's WHATWG parser) |
| What does a real browser do? | `src/browserTest/java/.../browser/` |
| What is the current state of everything? | `build/reports/canoe/matrix.md` |
