# V3-BE — The error lane learns to say "this wave never arrived": a heuristic wave-health class, a supervision timeline, and dead letters that say what failed

**Status**: Implemented — merged. (`:concord:docLints` accepts only
`Specified|Partial|Implemented|Exploratory|Historical|Living` as the first word
of this line; the ticket's own lifecycle word follows it. Move to
`Partial — in-progress` while working, `Implemented — merged` once merged.)
**Model:** `claude-opus-5` (effort xhigh) · **Escalate to:** `claude-opus-5`,
fresh session; if that fails, stop and re-split the ticket.
**Wave:** 5 · **Branches:** `ticket/v3-be`

## Context

You are working on `:inspect`, the Inspector backend: a read-only HTTP/SSE view
of a live ComputeNet host process. Read
`doc/spec/90-roadmap/98-inspector-v4-plan/10-design-notes.md` in full first — it
is the decided design for this run, and its "Binding constraints" section
governs this ticket absolutely. This is the V3 vertical: **errors, including
wave/glitch anomalies as an error class**.

### What the error lane is today

`inspect/src/main/kotlin/civictech/inspect/Errors.kt` feeds
`GET /api/inspect/errors` and three SSE kinds from three sources, none of which
is a per-message hook on the data path:

- **Dead letters** arrive push-style through one Observe-role tap per host on
  `ManagedHost.deadLetterOutlet` (`Errors.kt:76-81`). The handler
  (`Errors.kt:127-139`) extracts `ref`, `cause` (the throwable's simple class
  name), `description`, the wave stamp and a capture timestamp, then **drops the
  `DeadLetter` reference right there** — nothing about the payload survives the
  call. Retained in a `RingBuffer` of 200 (`Errors.kt:220-231`).
- **Parked** rows are a live gauge, recomputed from `LocationRegistry.parkedFor`
  on a 2 s poll (`Errors.kt:144-180`).
- **Restarts** are observed, not reported: `pollRestarts` (`Errors.kt:192-205`)
  compares each known ref's `ManagedHost.generationOf` against the previous
  sample on the same 2 s tick and appends a `RestartRow(ref, generation, atMs)`
  when it increases. A first sighting only seeds the baseline.

Row shapes are in `inspect/src/main/kotlin/civictech/inspect/Dto.kt:239-296`;
the SSE emission points are `InspectorModel.kt:385-398`; the route is
`InspectorServer.kt:246-249`; the poll is the `"errorPoll"` entry in the `Tick`
list (`InspectorServer.kt:502-528`), whose `tickAll()` (`InspectorServer.kt:571`)
is the single synchronous test seam for every scheduled action.

### The two data sources this ticket's new part builds on — both already held

1. **Last wave per tapped outlet.** `FlowCollector` (`Flow.kt:107-215`) installs
   one payload-agnostic `FanOutlet.observe` attachment per *producing outlet*
   (`Flow.kt:265-273`; the kernel seam is
   `kernel/src/main/kotlin/civictech/cell/port/FanOutlet.kt:396-402`). The
   handler is handed a `MessageContext` and nothing else — structurally
   incapable of reaching the payload — and does exactly two things per message:
   one `AtomicLong.incrementAndGet` and one volatile store of that context
   (`Flow.kt:237-239`, `Flow.kt:265-273`). So the collector **already holds the
   last observed `MessageContext` per tapped outlet**: its `Timestamp(sourceId,
   counter)` wave position, its `hop`, its `baseline` marker and its
   `reBaseline` notice (`kernel/src/main/kotlin/civictech/cell/MessageContext.kt:50-56`).
   `FlowCollector.sites` maps each producing `PortRef` to the set of contract
   `Edge.id`s it feeds (`Flow.kt:117-124`, `Flow.kt:126-146`);
   `tappedOutlets` (`Flow.kt:218`) is already exposed for diagnostics.
2. **Per-observed-cell frontier stamps.** `Observations` (`Observations.kt:118-203`)
   wraps every open observation's fold in `StampedView`
   (`Observations.kt:385-410`), which records, on every *effective* change, the
   ambient wave position as `frontier` and the wall clock as `changedAtMs`
   (`Observations.kt:398-405`). Because emission is the context-stamping point
   and transparent flow preserves the timestamp
   (`doc/spec/20-dataflow-semantics/22-consistency.md:7-45`, rule 2), that
   frontier is the wave position of the producing outlet for the delta the sink
   just folded. `openRefs` (`Observations.kt:127-130`) is the set of cells a
   client has explicitly asked to observe — and, per P6, the *only* set the
   inspector may read state-ish signals from.

Both live behind the same single daemon scheduler thread that drives every
`Tick`. Nothing new needs to be subscribed to compute a wave-health diagnostic:
the inputs are already in the process.

### Why this is a heuristic and must say so

`doc/spec/20-dataflow-semantics/22-consistency.md:175-207` states the decided
(unimplemented) rule for completeness over silent or stuck edges, and closes
with **⚠ GAP (G-40)** (`:198-207`): "a glitch-free join cannot distinguish an
effective-only-silent arm from a dead one — wave completeness blocks forever on
absorbing, suspended, restarting, or dead-lettered frontier edges." Real
detection is per-source per-edge watermarks, `Progress` absorb-acks and typed
`Stall` markers — kernel work that belongs with `.verify`, and is **explicitly
out of scope for this plan** (`10-design-notes.md` §Verticals V3). What the
inspector can honestly offer meanwhile is a *diagnostic*: from outside the
graph, on data it already holds, "this looks stuck — go look." Every row this
ticket produces must be labelled as such, in the data, not only in the UI.

## Problem

1. **The inspector can see a wave leave and cannot say it never landed.** It
   holds the last wave per tapped outlet (`Flow.kt:237-239`) and the frontier of
   every observed cell (`Observations.kt:391`), and it correlates them nowhere.
   A user watching a graph that has silently stopped propagating sees a healthy
   topology, a flow rate that simply went to zero (edges with no traffic are
   omitted from `flow.rates` entirely, `Flow.kt:186-198`) and an empty error
   lane. The one class of failure the runtime's own machinery cannot name
   (G-40) is also the one the inspector says nothing about.

2. **A restart is a bare generation number.** `RestartRow`
   (`Dto.kt:290-296`) carries `ref`, `generation`, `atMs`. The kernel's
   supervision path actually produces an ordered, causally connected sequence —
   `kernel/src/main/kotlin/civictech/cell/host/ManagedHost.kt:661-712`: the
   failure dead-letters *first* with its throwable (`:668`), then the restart
   counter increments (`:687`), then the generation is bumped before
   reactivation (`:690`), then every outlet mints a fresh emission epoch
   (`:693-698`), then, for a `ReBaselineEmitting` cell, a re-baseline is emitted
   over the ordinary catch-up path (`:707`). The inspector captures the first
   and third of those as two unrelated rows in two different ring buffers. The
   frontend cannot draw a timeline from that.

3. **A dead letter says a cause and a sentence, and nothing about what failed.**
   `DeadLetterRow` (`Dto.kt:272-279`) carries `cause`/`description`/`wave`. The
   `DeadLetter` reaching the tap carries a whole `HostedPortInvocation`
   (`kernel/src/main/kotlin/civictech/cell/proxy/HostedPortInvocation.kt:11-19`)
   — port name, invocation type, method name, parameter types — and its args
   have already been sanitized by the kernel's boundary rules
   (`kernel/src/main/kotlin/civictech/cell/host/DeadLetters.kt:43-60`): an
   `Owned` degenerated to `Frozen`, a `Leased` released and replaced by a
   `Redacted` marker. **That sanitization outcome is exactly the ownership
   disposition an operator needs to see** — "the exclusive payload on this
   failed call was frozen / was released / was already consumed before capture"
   — and the inspector currently discards all of it at `Errors.kt:127-139`.

## Solution direction

The **what** is decided below; the **how** — data structures, collaborator
decomposition, where evaluation lives — is yours. A new file under
`inspect/src/main/kotlin/civictech/inspect/` is the expected shape if the
heuristic deserves its own collaborator, the way `Flow.kt` does.

Three parts. Part 1 is the new capability and carries almost all of the risk;
parts 2 and 3 are enrichment of existing capture points.

---

### Part 1 — The wave-health heuristic (new error class)

A diagnostic computed on the inspector's own scheduler thread from data it
already holds, emitted as a **new kind of error row** that opens when a
condition holds and **clears when it resolves**.

#### The row and event shape (contract-binding — V3-FE codes against this in parallel)

```jsonc
// WaveHealthRow — a heuristic diagnostic, never a kernel-grade detection
{
  "id": "frontierLag:<edgeId>:<ref>",  // stable per (kind, edge, ref): the open row,
                                       // its updates and its clear all carry this id
  "kind": "frontierLag" | "stalledWave",
  "state": "open" | "cleared",
  "ref": "uuid:0",                     // the observed cell whose frontier is behind
  "edge": "uuid",                      // the tapped edge the comparison used (Edge.id)
  "wave":     { "source": "9c41…", "counter": 288 } | null,  // that edge's last observed wave
  "frontier": { "source": "9c41…", "counter": 281 } | null,  // the cell's frontier at evaluation
  "lagWaves": 7 | null,                // counter delta, only when both stamps share a source
  "heldMs": 6000,                      // how long the condition has held continuously
  "atMs": 1753600000000,
  "heuristic": true,                   // always true — this feed never claims certainty
  "description": "heuristic: observed frontier trails this edge's last wave by 7 waves for 6.0s"
}
```

Additive changes to the existing shapes:

- `ErrorSnapshot` gains `"waveHealth": [ WaveHealthRow ]` — the **currently
  open** rows only, a gauge in the manner of `parked`, never a history log.
- `ErrorCounters` gains `"waveHealth": 2` — the count of currently open rows
  (a gauge that falls as conditions resolve, like `parked`; not a monotonic
  total like `deadLetters`).
- New SSE kind `error.waveHealth`, payload = one `WaveHealthRow`. A row with
  `"state": "cleared"` clears the open row carrying the same `id` — the exact
  convention `error.parked`'s `count: 0` already established
  (`20-api-contract.md:174`), so the client's discipline is one it already has.

**Bounding, reconciled with the ticket brief.** The brief says "ring-buffered
like the others". Retaining *history* and clearing *state* pull in opposite
directions, so the decision is: the snapshot serves currently-open rows, bounded
at a named `WAVE_HEALTH_MAX_OPEN = 200` — the same cap and the same
oldest-evicted discipline as the other feeds, applied to the open set. When the
cap forces an eviction, **emit that row's `cleared` event too**: the client must
never be left holding a row the server has forgotten. Do not add a second
snapshot field for history.

#### The two conditions (both required)

**(a) `frontierLag`** — an observed cell whose frontier trails an *upstream
tapped outlet's* last observed wave by more than a threshold, continuously, for
longer than a grace period.

**(b) `stalledWave`** — a tapped edge carried wave *W* into an observed cell,
and that cell's frontier never reaches *W* within a window **while the graph is
otherwise active**. "Otherwise active" must be defined from data you already
have — e.g. some other tapped site published traffic in the interim — never from
wall-clock alone; a genuinely idle graph must produce no rows.

You may add further conditions only if they cost nothing new; these two are the
floor.

#### Named, conservative constants

All thresholds are named constants in one place, with a comment stating what
each is protecting against. Start conservative — a false positive in a
diagnostic labelled "heuristic" is still a lie a user has to chase:

| Constant | Value | Meaning |
|---|---|---|
| `LAG_THRESHOLD_WAVES` | 32 | Minimum same-source counter delta before (a) is even considered |
| `LAG_GRACE_MS` | 5 000 | The delta must hold continuously this long before a row opens |
| `STALL_WINDOW_MS` | 10 000 | (b)'s window for the frontier to reach the observed wave |
| `ROW_TTL_MS` | 30 000 | An open row not re-confirmed for this long clears itself |
| `WAVE_HEALTH_MAX_OPEN` | 200 | Cap on simultaneously open rows |

Evaluation cadence: one new `Tick` in the existing list
(`InspectorServer.kt:502-528`), named for what it does, placed after
`"flowSample"` so `tickAll()` remains a faithful synchronous stand-in for the
scheduled order. **No new thread.**

#### Clearing

A row clears — one `state: "cleared"` event, removed from the open set — when
any of: the frontier catches up; the tapped edge's source epoch changes (see
below); the edge is unbound (`Flow.kt:148-166`); the cell's observation is
released (`Observations.kt:206-245`); the cell is despawned or reported
`SUSPENDED`; or `ROW_TTL_MS` elapses without re-confirmation. The TTL is the
backstop that makes "rows always clear" true even for a path you did not
enumerate.

#### False-positive guards — these are the ticket, not decoration

Each of the following is a case where a lagging frontier is **correct
behaviour**. A row raised for any of them is a defect, not a tuning question:

1. **Waves are per-source and epochs are minted fresh on restart.** `Timestamp`
   is `(sourceId, counter)`; two different `sourceId`s are incomparable — there
   is no ordering between them. A supervision restart mints a fresh `sourceId`
   per outlet (`FanOutlet.kt:233-238`, driven from `ManagedHost.kt:693-698`;
   see also the **Generation** row of `doc/spec/00-foundations/03-glossary.md:40`:
   "a restart mints a fresh per-epoch `sourceId`"). So: compare counters only
   within one `sourceId`, and **reset all tracking for a site the moment its
   observed `sourceId` changes**. Never subtract across epochs.
2. **A null frontier is not a lag.** A freshly opened observation reports
   `frontier: null` by design: its state arrived as a catch-up baseline, and a
   baseline is deliberately not a wave position
   (`Observations.kt:361-383`; `doc/spec/20-dataflow-semantics/21-propagation.md:52-60`;
   93 I-24). Require at least one non-null frontier for a subject before it is
   eligible at all.
3. **A baseline or re-baseline emission is not "the upstream wave".** Ignore an
   observed `MessageContext` whose `baseline` is non-null
   (`MessageContext.kt:50-56`) when taking a site's wave position.
4. **Absorption is legitimate and common.** `StampedView.frontier` advances only
   on an *effective* change — a fold that returned `true` (`Observations.kt:398-405`).
   A de-duplicated add, a no-op update, an absorbed delta acknowledged rather
   than propagated: all leave the frontier legitimately behind an upstream
   outlet that did emit. This is the single largest source of honest lag, and
   the reason the class is heuristic.
5. **Filtering and aggregating operators drop waves by construction.** A
   `civictech.cell.data.op.FilterCell` downstream of a busy source lags
   permanently and correctly. Your false-positive guard test must include a
   topology of this shape.
6. **A suspended or drained cell is intentionally not propagating.** Skip any
   cell the model reports `SUSPENDED` (`InspectorModel.kt:312-313`).
7. **Independent sources are allowed to be silent.**
   `doc/spec/20-dataflow-semantics/22-consistency.md:177` ([22-LIVE-01]):
   completeness is per-source and over-alignment across independent sources is
   forbidden. Never derive a row from one source's silence relative to another's
   activity.

#### MUST NOT — hard boundaries

- **P6.** Evaluate only over cells that already have an open observation
  (`Observations.openRefs`) and outlets `FlowCollector` already taps. Do not
  call `Observations.start`, do not install a tap, do not extend an
  observation's lifetime to keep a subject alive, and do not read state from an
  unobserved cell. Observation is causal: a subscription raises attention on the
  upstream cone. A diagnostic that changes the graph to diagnose it is worthless.
- **P2.** Nothing new on the data path. Evaluation runs entirely on the
  inspector's scheduler thread over state the tap handler already records. The
  one exception this ticket grants is part 2's re-baseline flag, whose cost is
  pinned explicitly there.
- **No pull, ever.** Do not use `StateRequest` or any pull path.
  `doc/spec/90-roadmap/97-inspector-plan/90-progress-log.md:1124-1128` records
  why: `pullServe` replies mint waves from the producing outlet's counter, so a
  read perturbs replication watermarks. An instrument that alters the wave plane
  cannot diagnose the wave plane.
- **No kernel edits.** Every seam this part needs already exists.
- **Never claim certainty.** `heuristic: true` on every row, and the word
  "heuristic" in every `description`. No row text may assert that a wave *is*
  lost, that a cell *is* stuck, or that glitch-freedom *is* violated.

---

### Part 2 — Supervision timeline capture

The frontend must be able to draw, per cell: **crash cause → restart at
generation N → (re-baseline)**. The rows exist; the connective tissue does not.

Additive fields on `RestartRow` (contract-binding — V3-FE codes against this):

```jsonc
{
  "ref": "uuid:0", "generation": 1, "atMs": 1753600000000,
  "cause": "IllegalStateException" | null,   // best-effort correlation, see below
  "causeAtMs": 1753600000000 | null,         // when that dead letter was captured
  "reBaselineAtMs": 1753600000000 | null     // when a re-baseline beat was observed; null
                                             // means NOT OBSERVED, never "did not happen"
}
```

**Cause correlation.** The kernel dead-letters the failure before it bumps the
generation (`ManagedHost.kt:668` then `:690`), and the inspector already
captures both, on the same 2 s poll for the restart half. Correlate a generation
bump for a ref with the most recent dead letter captured for that same ref
within a named `RESTART_CAUSE_WINDOW_MS` (5 000 — comfortably above the 2 s poll
period) preceding it. State in code that this is a *correlation*, not a
kernel-reported restart cause: no seam reports the two as one event, and a
coincidental dead letter inside the window would be attributed. Null when no
candidate exists — never guess.

**The re-baseline beat: INCLUDE it. It is cheaply observable and needs no kernel
surface.** Verified: `FanOutlet.reBaseline` (`FanOutlet.kt:245-250`) emits
through the ordinary origination path with a `ReBaselineNotice` staged, so the
minted `MessageContext` carries it as `reBaseline`
(`MessageContext.kt:50-56`, `:84-89`) — and the payload-agnostic observer
attachment the flow feed already installs is handed that whole context
(`FanOutlet.kt:396-402`, `Flow.kt:265-273`). So a re-baseline beat on any
tapped outlet of a restarted cell is visible with **one null check** in the
existing tap handler.

- **The pinned P2 cost**: one reference-null comparison per message, and, only
  inside the taken branch, one volatile long store of the clock. No allocation,
  no lock, no map lookup, no payload access on the message path. Do not exceed
  this. (Sampling `lastContext` from the scheduler instead would miss the beat
  whenever live traffic resumes within the window — which is exactly the case
  after a restart — so the branch is the correct trade.)
- **Coverage is genuinely partial, and the field must be honest about it.** Only
  a cell implementing `ReBaselineEmitting` re-baselines at all
  (`ManagedHost.kt:707`), and today
  `civictech.cell.data.op.UnionSetCell` is the only kernel implementation; and
  the cell must have at least one tapped outgoing edge for the inspector to see
  it. `null` therefore means "not observed" and must be documented as such in
  the DTO KDoc, so the frontend renders absence rather than a negative claim.

No other supervision surface is in scope. If you conclude some further piece of
the timeline genuinely requires new kernel surface, **SKIP it and flag it in the
completion report** — no kernel edits in this ticket.

---

### Part 3 — Richer dead-letter detail

At the existing tap (`Errors.kt:127-139`), extract two more things from the
`DeadLetter` before dropping the reference, still at capture time, still
primitives only.

Additive fields on `DeadLetterRow` (contract-binding — V3-FE codes against this):

```jsonc
{
  "ref": "uuid:0", "cause": "…", "description": "…", "wave": {…}, "atMs": …,
  "invocation": {                       // null when the dead letter carried no invocation
    "port": "inlet",                    //   (a plain host-level drop)
    "type": "PORT_API" | "PORT_MANAGEMENT" | "PORT_PROTOCOL",
    "method": "propagate",
    "parameterTypes": ["civictech.cell.data.SetDelta"],
    "argCount": 1,
    "hop": 2 | null
  } | null,
  "disposition": [                      // one entry per argument, in argument order;
    {                                   // [] when there was no invocation or no args
      "index": 0,
      "ownership": "frozen" | "redacted" | "borrowed" | "owned" | "leased" | "plain",
      "reason": "Leased payload released at dead-letter capture" | null
    }
  ]
}
```

Sources, all already on the object reaching the tap:
`HostedPortInvocation.portName` / `.type` / `.invocation`
(`HostedPortInvocation.kt:11-19`, `:44-57`), `Invocation.methodName` /
`.parameterTypes` / `.args.size` / `.context?.hop`
(`kernel/src/main/kotlin/civictech/cell/proxy/Invocation.kt:12-25`,
`MessageContext.kt:50-56`).

**The ownership vocabulary is the kernel's sanitization outcome, read back.**
`DeadLetters.sanitizeForDeadLetter` (`DeadLetters.kt:43-60`) has already run
before the outlet fans the letter: an `Owned` arrives as `Frozen` (or as
`Redacted` if it had been consumed before capture), a `Leased` arrives released
and replaced by `Redacted`. So classify each argument by its runtime class
(`kernel/src/main/kotlin/civictech/cell/Ownership.kt:16-92`):
`Frozen` → `"frozen"`, `Redacted` → `"redacted"` (with `Redacted.reason` copied
into `reason`, truncated at a named constant — 200 chars), `Borrowed` →
`"borrowed"`, anything else → `"plain"`. `"owned"` and `"leased"` stay in the
vocabulary as the honesty case: a live exclusive handle must never reach this
outlet, and if one ever does the row says so rather than mislabelling it.

**Ownership constraint, absolute.** Borrowed-only, never retain. Record the
argument's *class name* and the kernel-authored `Redacted.reason` string —
never the argument's value, never `toString()` of a value, never an encoded
form of it, never the argument reference itself past the extraction. The
existing invariant at `Errors.kt:121-126` ("neither the `DeadLetter` nor its
`HostedPortInvocation` survives this call") must remain literally true after
your change. `parameterTypes` are declared type names, not values, and are
safe.

---

## Files expected to touch

- `inspect/src/main/kotlin/civictech/inspect/Dto.kt` — `WaveHealthRow`; the
  additive fields on `ErrorSnapshot`, `ErrorCounters`, `DeadLetterRow`,
  `RestartRow`; the `error.waveHealth` `Event` kind constant
  (`Dto.kt:222-235`).
- `inspect/src/main/kotlin/civictech/inspect/Errors.kt` — richer dead-letter
  capture; restart-cause correlation; the wave-health rows' ring/open-set and
  emission if you house them here.
- `inspect/src/main/kotlin/civictech/inspect/Flow.kt` — expose the per-site last
  wave and edge ids to the evaluator; the re-baseline branch in the tap handler.
- `inspect/src/main/kotlin/civictech/inspect/Observations.kt` — expose the
  per-observation frontier stamp to the evaluator if it is not already reachable
  through `reading` (`Observations.kt:217-221`).
- `inspect/src/main/kotlin/civictech/inspect/InspectorModel.kt` — the
  `error.waveHealth` emission point, beside `Dto.kt`'s siblings at
  `InspectorModel.kt:385-398`.
- `inspect/src/main/kotlin/civictech/inspect/InspectorServer.kt` — the new
  `Tick`, wiring, and `close()` symmetry (`InspectorServer.kt:542-555`).
- A new `inspect/src/main/kotlin/civictech/inspect/…​.kt` for the heuristic, if
  it deserves its own collaborator.
- `inspect/src/test/kotlin/civictech/inspect/**` — new focused tests, plus the
  two `FixtureContractTest` decoder entries below.

**Cross-ticket coupling you must honour.** `FixtureContractTest`
(`inspect/src/test/kotlin/civictech/inspect/FixtureContractTest.kt:64-95`)
asserts that its hand-written `decoders` map covers *exactly* the contents of
`inspect/ui/fixtures/`. V3-FE runs in parallel and will add exactly two files
there. **Add these two decoder entries, both mapped to `Event`, as part of this
ticket:**

- `"error-event-wave-health.json"` → `Event`
- `"error-event-wave-health-cleared.json"` → `Event`

V3-FE will also extend `inspect/ui/fixtures/errors.json` in place with the new
fields; that file's decoder entry already exists and your DTO changes are what
make it strict-decode. Consequence to expect and not to "fix": until both
branches are merged, `:inspect:test` in a worktree holding only one of them can
fail on `errors.json`. Do not edit `inspect/ui/**` to work around it — note it
in your report.

Touching files outside `inspect/src/**` (other than the fixture coupling above,
which is read-only for you): note it in the completion report rather than
expanding silently. V3-FE owns `inspect/ui/**` and runs concurrently.

## Read first

- `doc/spec/90-roadmap/98-inspector-v4-plan/10-design-notes.md` §"Binding
  constraints" (all ten) and §"Verticals → V3".
- `doc/spec/20-dataflow-semantics/22-consistency.md:7-45` (MessageContext rules
  — origination, transparent flow, fan-out/fan-in, and rule 5's metadata-plane
  exclusion), `:125-146` (local glitch-freedom, [22-GF-01]), `:147-173` (the
  glitch-free frontier and its composition, [22-GF-02]), `:175-207`
  (completeness over silent or stuck edges, [22-LIVE-01], and **G-40** — the gap
  this diagnostic sits beside without pretending to close).
- `doc/spec/20-dataflow-semantics/21-propagation.md:52-60` — pull and catch-up:
  why a baseline is not a wave position.
- `doc/spec/00-foundations/03-glossary.md:40` (**Generation** — restart mints a
  fresh per-epoch `sourceId`), `:57` (**MessageContext**), `:58` (**Glitch-free
  frontier**), `:81-99` (the frontier / watermark / region disambiguation — use
  its vocabulary; your rows are about a *frontier* in the fold sense and a
  *watermark* in the counter sense, and conflating them in the field names is
  how this gets confusing).
- `inspect/src/main/kotlin/civictech/inspect/Flow.kt` (whole file — its class
  doc is the argument for why an Observe-role attachment is the only admissible
  seam, and you are extending it).
- `inspect/src/main/kotlin/civictech/inspect/Observations.kt:361-410` —
  `StampedView` and the precise meaning of `frontier`/`changedAtMs`.
- `inspect/src/main/kotlin/civictech/inspect/Errors.kt` (whole file).
- `inspect/src/main/kotlin/civictech/inspect/InspectorServer.kt:485-577` — the
  `Tick` list, `tickAll()`, and the existing test accessors (`observedRefs`
  `:557-558`, `tappedOutlets` `:573-574`, `errorSnapshot` `:576-577`).
- `kernel/src/main/kotlin/civictech/cell/host/ManagedHost.kt:661-712` — the
  supervision path in order; this is the timeline part 2 reconstructs.
- `kernel/src/main/kotlin/civictech/cell/host/DeadLetters.kt:43-60` — the
  sanitization whose outcome part 3 reads back.
- `kernel/src/main/kotlin/civictech/cell/Ownership.kt:16-92` — the four
  ownership wrappers plus `Redacted`.
- `inspect/src/test/kotlin/civictech/inspect/InspectorErrorsTest.kt` — the
  end-to-end harness (real in-process graph, `HttpProbe`, SSE tap, `awaitUntil`)
  and the existing recipes for forcing a dead letter and a restart.
- `inspect/src/test/kotlin/civictech/inspect/InspectorDataSearchTest.kt:262-285`
  — the P6 leak-check pattern (`serving.observedRefs.shouldBeEmpty()`) your
  no-new-subscriptions test must mirror.
- `inspect/src/test/kotlin/civictech/inspect/ObservationsIdleTest.kt` — the
  injected-clock pattern; use it rather than sleeping.
- `doc/spec/90-roadmap/97-inspector-plan/90-progress-log.md:1124-1128` — why
  pull is forbidden here, in the previous run's own words.

Do not modify: `inspect/ui/**` (V3-FE owns it), `kernel/**`, `concord/**`
(design notes constraint 7),
`doc/spec/90-roadmap/97-inspector-plan/20-api-contract.md` (orchestrator-owned,
constraint 8), any plan document other than this ticket's `**Status**:` line.

## Acceptance criteria

- [ ] A driven frontier-lag scenario **produces** a `frontierLag` row —
      `state: "open"`, `heuristic: true`, the word "heuristic" in
      `description`, the offending `edge`, `wave`, `frontier` and `lagWaves`
      populated — on both `GET /api/inspect/errors` (`waveHealth[]` and
      `counters.waveHealth`) and the `error.waveHealth` SSE stream.
- [ ] That same scenario, once the condition resolves, **clears** the row: one
      `error.waveHealth` event with `state: "cleared"` and the same `id`, and
      the row gone from the snapshot with `counters.waveHealth` back to its
      prior value.
- [ ] A driven `stalledWave` scenario produces and clears its row the same way.
- [ ] **False-positive guard**: a healthy graph produces **zero** wave-health
      rows. Cover, as distinct cases: a quiet/idle graph; a busy graph whose
      downstream absorbs (an effective-change-free fold); a filtering operator
      that legitimately drops most waves; a freshly opened observation with a
      null frontier; and a cell restarted mid-run (fresh source epoch — no row
      from the epoch change, and any pre-existing row for that site cleared).
- [ ] **No new subscriptions**: across a full heuristic evaluation cycle
      (several `tickAll()` passes with rows opening and clearing),
      `observedRefs` and `tappedOutlets` are unchanged, and no `ObserveCell`
      sink is spawned — asserted in the `InspectorDataSearchTest.kt:262-285`
      style.
- [ ] Every threshold is a named constant with a comment, and no test asserts on
      scheduler timing: tests drive `tickAll()` and/or an injected clock, with
      `awaitUntil` for the SSE side. No wall-clock sleeps.
- [ ] Open rows are bounded by `WAVE_HEALTH_MAX_OPEN`, and an eviction forced by
      the cap emits that row's `cleared` event.
- [ ] A dead letter forced from an invocation failure carries the new
      `invocation` block (port, type, method, parameterTypes, argCount, hop) and
      a `disposition` entry per argument; a plain host-level drop carries
      `invocation: null` and `disposition: []`.
- [ ] A dead letter whose failing invocation carried an `Owned` argument reports
      `"ownership": "frozen"`, and one carrying a `Leased` argument reports
      `"redacted"` with the kernel's release reason — and no argument *value*
      appears anywhere in the serialized row.
- [ ] A supervision restart produces a `RestartRow` with `cause`/`causeAtMs`
      correlated from the preceding dead letter, and `null` for both when no
      dead letter preceded it within the window.
- [ ] `reBaselineAtMs` is populated for a restarted `ReBaselineEmitting` cell
      with a tapped outgoing edge, and is `null` — documented as "not observed"
      — otherwise.
- [ ] The existing error-lane behaviour is unchanged: dead-letter/parked/restart
      events, counters and ring-buffer eviction all still pass their current
      tests.
- [ ] `FixtureContractTest`'s `decoders` map carries the two new
      `error-event-wave-health*.json` entries.
- [ ] No kernel, `concord/`, `inspect/ui/` or contract-document edits in the
      diff. No generated/build output. No unrelated files.

## Verify

```bash
./gradlew :inspect:test
# narrow loop while iterating
./gradlew :inspect:test --tests 'civictech.inspect.InspectorErrorsTest'
```

Nothing downstream of `:inspect` may regress:

```bash
./gradlew :demo:skillmatch:test
```

Any live server you start for manual checking must bind an ephemeral or
explicitly non-default port — concurrent sessions squat 7071/8080
(`00-orchestration.md` §Sandbox).

## Report on completion

- Checks run and their results; the exact scenarios you used to drive
  `frontierLag` and `stalledWave`, so the C5 evaluator can reproduce them.
- **The per-message cost after your change, stated as a count** (reads, writes,
  branches, allocations) on the tap handler — the P2 claim must be checkable,
  not asserted. Call out the re-baseline branch specifically.
- The false-positive analysis: which honest-lag cases you tested, and which ones
  you know remain reachable at the chosen thresholds. Understating this is worse
  than a wide threshold.
- Files actually touched, and any not in the claim above.
- **Flag to the orchestrator** (contract additions for
  `20-api-contract.md` — do not edit it yourself):
  1. `WaveHealthRow`'s full shape and the `error.waveHealth` SSE row, including
     the `state: "cleared"` clearing convention and its kinship with
     `error.parked`'s `count: 0`.
  2. `ErrorSnapshot.waveHealth` (open rows, a gauge) and
     `ErrorCounters.waveHealth` (a gauge, unlike its monotonic siblings) —
     propose exact wording, including the sentence that says this class is
     heuristic and is not kernel-grade detection.
  3. `DeadLetterRow.invocation` and `.disposition`, with the ownership
     vocabulary and the never-retain guarantee.
  4. `RestartRow.cause`/`.causeAtMs`/`.reBaselineAtMs`, with the explicit
     statement that `cause` is a time-window correlation and that
     `reBaselineAtMs: null` means "not observed", never "did not happen".
  5. Whether `GraphList.health` (`20-api-contract.md:126`) should roll up
     wave-health rows — a component-scoped question this ticket deliberately
     does not answer.
- **Flag separately, as kernel/`.verify` input**: what a kernel-grade version of
  this detection would need that you could see was missing (per-edge per-source
  watermarks, `Progress` absorb-acks, typed `Stall` markers — G-40). This is the
  most valuable output of the ticket for the C-replan checkpoint; be concrete.
- Anything specified here you could not do, and why — in particular anything you
  SKIPPED rather than reaching into `kernel/`.
