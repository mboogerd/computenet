# :demo:allocator-observe

Observes a socaity-owned allocation: it ingests the JSONL **spend log** (one
line per work session) and the YAML **allocation declaration** (project weights
plus a monthly cap), folds both into kernel cells, and derives from them what
the allocation actually did — declared versus enacted share per project, the
drift between them, and cap tracking with a projected month-end burn. Epic
`computenet-fpml` carries the design: F1 ingest (checkpointed tail reader,
truncation/replacement detection), F2 declarations, F3 the derived views
(`view/`), F4 the HTTP/SSE serving over `:demo:shell` (`GET /state`,
`GET /state/ingest`, `GET /state/report` and an `/events` SSE stream; merged
to `main` 2026-09-18 as PR #915), F5 the differential oracle this README's next
section documents.

The log's location is never named in this module's sources; it reaches the
ingester as a parameter, and `NoHardcodedLogPathTest` keeps it that way.

## Oracle exchange shape

Feature `computenet-fpml.5` checks this module's arithmetic **differentially**:
an independently written reference computes the same report from the same
inputs, and the two are compared field for field. The socaity replay script
(socaity-9wu) is the second, cross-repo instance of the same comparison. All
three — the views, the in-repo reference, the socaity script — target the
document described here. This section is the contract; it is not derived from
the implementation, and the implementation is not the place to look it up.

### The `report` document

`GET /state/report` serves it, and it is byte-identical to the `report` member
of `GET /state` and of every `/events` frame.

```
{
  "publishedAt": "<ISO-8601 instant>",
  "window": {
    "window": { "from": "<instant>", "to": "<instant>" },
    "subIntervals": [
      {
        "range": { "from": "<instant>", "to": "<instant>" },
        "declaration": {
          "weights": { "<project>": <number>, ... },   // raw, as declared
          "monthlyCapHours": <number>,
          "window": "<string>" | null
        },
        "enactedHours":  { "<project>": <hours> },
        "enactedShare":  { "<project>": <fraction> },
        "declaredShare": { "<project>": <fraction> },
        "diff":          { "<project>": <fraction> }
      }
    ],
    "perProject": {
      "<project>": {
        "enactedHours": <hours>,
        "enactedShare": <fraction>,
        "declaredShare": <fraction>,
        "drift": <fraction>,
        "residual": <fraction>,
        "residualLabel": "<string>"
      }
    },
    "totalHours": <hours>,
    "beforeFirstDeclarationHours": { "<project>": <hours> }
  },
  "cap": {
    "monthStart": "<instant>", "monthEnd": "<instant>", "now": "<instant>",
    "capHours": <hours> | null,
    "hoursToDate": <hours>,
    "elapsedFraction": <fraction>,
    "projectedMonthEndHours": <hours> | null,
    "projectionRule": "<string>",
    "capReached": <boolean>
  },
  "unattributable": { "<REASON>": <count> },
  "unattributableRecords": [
    { "v": 1, "project": "...", "machine": "...", "workItem": "...",
      "started": "...", "ended": "..." }
  ]
}
```

Encoding conventions: instants are `Instant.toString()`; every project-keyed
object has its keys in sorted order, and `unattributableRecords` is sorted by
`(project, started, ended, machine, workItem)`, so the document is a function of
the state alone; numbers are emitted **raw**, never rounded or formatted — the
comparison normalises instead (below). Note that the spend log spells the work
item `work_item` and the document spells it `workItem`.

### Conventions that make the numbers well defined

R6 pins only the outcome — declared versus enacted share per project, derived
purely from the weights history and the spend log, plus cap tracking. These are
the choices that make two implementations of it comparable (F3 design entries
fpml.3-D1/D2/D3/D7/D8, fpml.5-D9). A second implementation that departs from
any of them will diverge, and correctly so.

- **Boundaries are UTC.** Month and window boundaries are computed in UTC, never
  in a local zone.
- **The window is `[now - length, now)`** — half-open, so a session starting
  exactly at `now` is outside it.
- **Hours are attributed by overlap.** A session contributes to a range the
  duration of its intersection with that range, `[from, to)` half-open; a
  session straddling a boundary is split across both sides, not assigned whole
  to either.
- **Declaration boundaries are the history's `observedAt` instants.** Each
  declaration is in force from when it was observed until the next one is; the
  window is cut into one sub-interval per declaration in force during it.
- **Weights are normalised to fractions**: a project's declared share under one
  declaration is its weight over the sum of that declaration's weights. A
  project absent from a declaration weighs 0.
- **Over a range spanning several declarations, declared share is the
  duration-weighted mean** of the per-declaration shares — not a plain mean. For
  the fixture week, `computenet` is declared 0.6 for 96h and 0.3 for 72h, so its
  window declared share is `(0.6*96 + 0.3*72) / 168 = 0.471429`.
- **Time before the first declaration is excluded** from every share, diff and
  drift, and reported per project under `beforeFirstDeclarationHours`. The
  denominators — both the hours total and the duration weighting — count only
  covered time.
- **Every project-keyed object shares one key set: the GLOBAL project set.**
  `perProject`, each sub-interval's `enactedHours`/`enactedShare`/
  `declaredShare`/`diff`, and `beforeFirstDeclarationHours` (whenever it is
  non-empty) are all keyed over *every project with at least one attributed
  session anywhere in the log, plus every project named in any declaration of
  the history* — not "only the projects that happen to have hours in this
  particular sub-range or sub-interval". A project with zero hours in a given
  sub-range still gets an explicit key with value `0.0` there; a key is never
  omitted because its value would be zero. (This is the production rule,
  already shipped in `AllocatorReportViews.publish` / `windowReport` as
  `ledger.projects() + declaredProjects`; pinned here rather than the
  alternative — computing each object's key set from only what appears in
  that sub-range — decided at computenet-nv04w.) `beforeFirstDeclarationHours`
  is the empty object exactly when there is no gap before the first
  declaration (a declaration's `observedAt` is at or before the window
  start), never because every project's hours there happen to be zero — when
  the gap exists, every global project appears in it, zeros included.
- **Enacted share** within a sub-interval is the project's hours over that
  sub-interval's total hours (0 when the total is 0); in `perProject` it is the
  project's hours over the sum of all sub-intervals' hours. `diff` and `drift`
  are `enacted - declared`.
- **Residual is the whole drift.** Starvation attribution is unavailable
  (fpml.3-D4): no socaity draw-exclusion record shape is pinned, so no part of
  the drift can be attributed to a project having been starved rather than
  having under-drawn. `residual` equals `drift` and `residualLabel` is the fixed
  string `starvation attribution unavailable: no socaity draw-exclusion record
  shape is pinned`.
- **Cap tracking.** `capHours` is the `monthlyCapHours` of the declaration in
  force at `now` (null if there is none); `hoursToDate` is the overlap of every
  session with `[UTC month start, now)`; `elapsedFraction` is elapsed over month
  length; `projectedMonthEndHours` is `hoursToDate / elapsedFraction`, null when
  the fraction is 0; `projectionRule` is `"linear"`; `capReached` is
  `capHours != null && hoursToDate >= capHours`.
- **Unattributable records.** A v1 record whose `started` or `ended` is not an
  ISO-8601 instant, or whose `ended` precedes its `started`, contributes no
  hours anywhere and is counted under `unattributable` by reason
  (`UNPARSEABLE_STARTED`, `UNPARSEABLE_ENDED`, `ENDED_BEFORE_STARTED`) and
  listed in `unattributableRecords`. A line that is not a v1 record at all — not
  JSON, wrong key set, `v != 1` — is not a record and appears nowhere in this
  document.

### Normalisation before comparison

Both sides emit raw doubles, and two independent implementations summing in a
different order will differ in the last bits. Comparison therefore normalises
first, **by field name wherever that name occurs** — including when the name
belongs to an object whose values are the numbers, as with `diff`,
`enactedHours` and `weights` (fpml.5-D5):

| Fields | Rule |
| --- | --- |
| `enactedShare`, `declaredShare`, `diff`, `drift`, `residual`, `elapsedFraction`, `weights` | `BigDecimal`, 6 decimal places, `HALF_UP` |
| `enactedHours`, `totalHours`, `hoursToDate`, `capHours`, `projectedMonthEndHours`, `monthlyCapHours`, `beforeFirstDeclarationHours` | whole seconds, `Math.round(hours * 3600)` |
| everything else — strings, booleans, nulls | exact |

The tolerance is thus declared rather than fuzzy: 1e-6 of a share, half a second
of time. Objects are compared key by key and a key present on one side only is a
divergence at that key's path; arrays report a length difference as one
divergence at the array's path and then compare element-wise. The result is the
full list of divergences in document order, each a `(path, expected, actual)`
with paths like `report.window.perProject.computenet.enactedShare` and
`report.window.subIntervals[1].diff.glass-factory`.

### The in-repo reference imports nothing from the implementation

`src/test/kotlin/civictech/demo/allocatorobserve/oracle/ReferenceReport.kt` is
written **from this section**, not from `view/`. It has its own line parser, its
own declaration timeline and its own arithmetic, and it imports no type from
`civictech.demo.allocatorobserve` — not `view`, `http`, `ingest`, `declaration`
or the root package. `ReferenceIndependenceTest` enforces that with a lexical
import scan and a non-vacuity control, because the rule is broken by a one-line
"reuse" that nothing else would make visible, and a differential test whose two
sides share a definition cannot detect a mistake in that definition.

## Running against the socaity replay script

**Status as of 2026-09-18: `socaity-9wu` (the replay script) is open and no
script exists in socaity.dev yet, so the command below has never been run
against a real script.** `oracle/ExternalOracleComparisonTest` is written and
gated — see the class KDoc — and `oracle/ExternalHarnessSelfTest` proves its
plumbing against `oracle/ReferenceReport` standing in for the external
producer, but that self-test is emphatically not a run of the real
comparison; do not read a green build of this module as evidence the two
implementations agree, only as evidence the harness itself works.

### Inputs

The harness needs four values, each an external-oracle Gradle project
property (`-P`, not `-D` — see `build.gradle.kts`'s forwarding block):

- `allocator.oracle.report` — the `report` exchange document (see "The
  `report` document" above) that the socaity replay script produced for the
  same log and history below.
- `allocator.oracle.log` — the spend log the script was run over, byte for
  byte the same file.
- `allocator.oracle.declarations` — the declaration history the script was
  run over, as a JSON array of `{"observedAt", "declaration": {"weights",
  "monthlyCapHours", "window"}}` objects, one per observed declaration, in any
  order:

  ```json
  [
    {
      "observedAt": "2026-08-08T00:00:00Z",
      "declaration": { "weights": { "computenet": 60, "glass-factory": 40 }, "monthlyCapHours": 100, "window": null }
    },
    {
      "observedAt": "2026-08-12T00:00:00Z",
      "declaration": { "weights": { "computenet": 30, "glass-factory": 70 }, "monthlyCapHours": 100, "window": null }
    }
  ]
  ```

- `allocator.oracle.now` — the ISO-8601 instant the script published its
  report at (the window's exclusive upper bound and `cap.now`).

`allocator.oracle.windowHours` is optional and defaults to `168` (one week).

### Command

```
./gradlew :demo:allocator-observe:test --tests '*ExternalOracleComparisonTest' --rerun \
  -Pallocator.oracle.report=<file> \
  -Pallocator.oracle.log=<file> \
  -Pallocator.oracle.declarations=<file> \
  -Pallocator.oracle.now=<ISO-8601 instant> \
  [-Pallocator.oracle.windowHours=168]
```

### What SKIPPED means, and what CI does with this

Run the module's test task with `-Pallocator.oracle.report` omitted (the
default: `./gradlew :demo:allocator-observe:test`), and the test is reported
**skipped**, not passing — `Assumptions.assumeTrue` fails the assumption, and
JUnit records that as a distinct outcome from green, visible as a `<skipped>`
element in the result XML. **CI never supplies these properties, so this test
is always skipped there and CI never claims the external comparison ran.**
That is deliberate, not a gap to close: the comparison is evidence this
module cannot produce on its own, and a CI-reported pass with no real
socaity report behind it would be worse than no evidence at all.

### Reading a divergence

A non-empty divergence list is a finding, not a failure to suppress — either
side can be wrong. Record it with the input that produced it: file a bead
under epic `computenet-fpml` naming the exact log line (or declaration event)
and the `report.<path>` field that diverged, whether the divergence looks
like a ComputeNet bug or a socaity-script bug, and the command (with its
input files, or copies of them) that reproduces it.
