# V1A-BE — `state.summary` becomes a coalesced, self-announcing freshness feed

**Status**: Partial — in-progress (implemented on `ticket/v1a-be`, awaiting
merge). (`:concord:docLints` accepts only
`Specified|Partial|Implemented|Exploratory|Historical|Living` as the first word
of this line; the ticket's own lifecycle word follows it. Move to
`Partial — in-progress` while working, `Implemented — merged` once merged.)
**Model:** `claude-opus-5` (effort xhigh) · **Escalate to:** `claude-opus-5`,
fresh session; if that fails, stop and re-split the ticket.
**Wave:** 2 · **Branches:** `ticket/v1a-be`

## Context

You are working on `:inspect`, the Inspector backend: a read-only HTTP/SSE view
of a live ComputeNet host process. Read `doc/spec/90-roadmap/98-inspector-v4-plan/10-design-notes.md`
in full first — it is the decided design for this run and its "Binding
constraints" section governs this ticket.

The inspector's state vertical works like this today:

- `POST /api/inspect/cell/{ref}/observe` spawns an `ObserveCell` sink into the
  target's host and links it to the target's outlet
  (`inspect/src/main/kotlin/civictech/inspect/Observations.kt:158-203`). This is
  the *only* subscription path, and it is causal: the link raises attention on
  the upstream cone, which is why P6 insists it is an explicit user act and why
  `DELETE` plus a 5-minute idle sweep both exist
  (`Observations.kt:206-245`).
- The sink's fold is wrapped in `StampedView`
  (`Observations.kt:385-410`), which records the wave `frontier` and the
  wall-clock `changedAtMs` of the last *effective* change (a fold that returned
  `true`). `Observations.Observation.reading(...)`
  (`Observations.kt:282-287`) turns that into a `StateReading`
  (`kind`, `value`, `frontier`, `staleMs = now - changedAtMs`).
- Every settled effective change invokes the `onChange` callback
  (`Observations.kt:201`), wired straight to
  `InspectorModel.stateSummary` (`InspectorServer.kt:166-171`), which emits one
  `state.summary` SSE frame per change
  (`InspectorModel.kt:365-372`).

The frontend consumes it as a freshness trigger: a `state.summary` for the
selected cell causes a refetch of `GET /cell/{ref}/state`
(`inspect/ui/src/sync/detailClient.ts:125-131`). The v4 FE ticket V1A-FE builds
live re-render, row-flash and an onChange log on top of that trigger, in
parallel with you, against the *existing* contract fields.

The inspector already has the answer to this problem elsewhere. `FlowCollector`
(`inspect/src/main/kotlin/civictech/inspect/Flow.kt:107-215`) aggregates
per-message tap counts into one 1 Hz window, publishes **even when the window
was quiet** so the client's decay logic keys on *received windows* rather than
on silence, emits exactly one trailing window after the last tap detaches, and
then falls silent (`Flow.kt:123`, `Flow.kt:179-202`, `Flow.kt:205-215`). Its
window is driven off the inspector's single existing daemon scheduler through
the `Tick` list (`InspectorServer.kt:500-528`, the `"flowSample"` entry at
`InspectorServer.kt:511`), which also gives every scheduled action one
synchronous test seam, `tickAll()` (`InspectorServer.kt:571`).

`state.summary` has no equivalent. That has been an open item since M1 and was
restated at every subsequent evaluation —
`doc/spec/90-roadmap/97-inspector-plan/90-progress-log.md:351-355`,
`:383`, `:756-760`, `:900-901`, `:1150-1151` ("`flow.rates`' publish-even-when-
quiet window remains the pattern to copy"). This ticket closes it.

## Problem

1. **Unbounded frame rate.** A hot cell emits one SSE frame per settled
   effective change (`Observations.kt:201` → `InspectorModel.kt:365`). Nothing
   blocks — the broadcaster's per-client queues are bounded drop-oldest — but a
   client watching a busy cell receives, and refetches on, every single change.
   Since the FE's reaction to a summary is a `GET /cell/{ref}/state`
   (`inspect/ui/src/sync/detailClient.ts:128-131`), an N-changes-per-second cell
   currently produces N state reads per second.

2. **Silence is ambiguous.** No summary can mean "nothing changed", "the
   observation was released", "the SSE frame was dropped under backpressure", or
   "the server stopped". The client cannot tell, so it cannot honestly decay or
   age what it is showing. `flow.rates` solved exactly this for its own feed by
   publishing quiet windows (`Flow.kt:168-178`); `state.summary` did not.

3. **No stated freshness guarantee.** Nothing in the code or the contract
   promises that an effective change on an observed cell will be *announced*
   within a bounded time. The FE's whole live-value story rests on that promise.

## Solution direction

Decided; the mechanism and code shape are yours.

**1. A 1 Hz window per observed cell, on the flow-feed pattern.** Coalesce
summaries into a per-cell window of `FlowCollector.WINDOW_MS`. Within a window,
an arbitrary number of settled effective changes produce **one** summary,
carrying the *latest* reading (cardinality, frontier, staleness) — never a
stale intermediate one. Drive the window from the existing `Tick` list
(`InspectorServer.kt:500-528`) — no new thread, and `tickAll()` must remain the
single synchronous test seam for it.

**2. Publish even when quiet, while any observation is open.** While a cell has
an open observation, its window publishes every window regardless of whether
anything changed, so the client's staleness/decay logic keys off received
windows rather than off silence. After an observation is released — by `DELETE`,
by the idle sweep (`Observations.kt:237-245`), or by inspector shutdown — emit
exactly **one trailing window** for that cell, then nothing. A closed inspector
is silent, not one trailing frame short of it (`Flow.kt:205-215` is the
precedent, including its `everSampled = false` reset).

**3. The summary is a reliable freshness trigger.** An effective state change on
an observed cell MUST produce a summary within one window. Equivalently: a
client that refetches `GET /cell/{ref}/state` on every summary it judges to
indicate change can never be left holding a stale value indefinitely.

**4. A changed window must be distinguishable from a quiet one using the
existing contract fields alone.** V1A-FE runs in parallel and codes against
today's `state.summary` payload — `ref`, `cardinality`, `frontier`, `staleMs`
(`20-api-contract.md:172`). The property the FE relies on, which you must
preserve deliberately rather than by accident:

> `staleMs` is computed at publish time from the last effective change, so it
> **decreases** exactly when a change settled since the previous published
> window, and **grows monotonically** across consecutive quiet windows.

`StampedView.changedAtMs` (`Observations.kt:393-403`) and
`Observation.reading` (`Observations.kt:282-287`) already give you this; the
requirement is that windowing does not break it (e.g. by computing `staleMs` at
change time and publishing it a window later). `frontier` and `cardinality`
changing are additional, weaker signals — a baseline-only fold reports a null
frontier by design (`Observations.kt:361-383`), and two different values can
share a cardinality.

**5. You may propose, but not unilaterally add, an explicit marker.** If your
analysis says the feed should also carry something like a per-window change
count or a `changed` flag, that is an *additive* contract change: implement it
if you judge it right, keep the four existing fields semantically sufficient on
their own (V1A-FE does not know about it), and **flag it in your completion
report**. Never edit
`doc/spec/90-roadmap/97-inspector-plan/20-api-contract.md` — it is
orchestrator-owned (design notes constraint 8).

**Invariants that bound the design:**

- **P2** — no per-message hook on the data path beyond the existing observation
  path. You are coalescing what `ObserveCell`'s fold already reports; do not add
  taps, wrappers, or interception anywhere else.
- **P6** — nothing subscribes that was not explicitly observed. A window exists
  only for a cell with an open observation, and disappears with it. Do not start
  polling unobserved cells to synthesize summaries, and do not extend an
  observation's life to keep a window alive.
- **Viz never blocks** — the per-change path must stay non-blocking and cheap
  (record the latest reading; do not emit, allocate heavily, or take a lock the
  HTTP or graph side contends on). Publication happens on the inspector's own
  scheduler thread, and the SSE broadcaster's bounded drop-oldest queues remain
  the backpressure answer.
- **Ownership** — unchanged: the sink folds `Borrowed` deltas; nothing here
  reads, copies, or retains a payload beyond what `StampedView` already holds.
- Multiple concurrent observations are already supported
  (`Observations.kt:127`); the windowing must be per cell, not one global
  window that starves cells.

## Files expected to touch

- `inspect/src/main/kotlin/civictech/inspect/Observations.kt` — the change
  callback becomes a record-latest rather than an emit-now path; window
  bookkeeping per open observation, including its trailing window on release.
- `inspect/src/main/kotlin/civictech/inspect/InspectorServer.kt` — one new
  `Tick` for the summary window (and the wiring at `:166-171`).
- `inspect/src/main/kotlin/civictech/inspect/InspectorModel.kt` — only if the
  emission point's shape changes.
- `inspect/src/test/kotlin/civictech/inspect/**` — new focused tests (see
  Acceptance criteria); existing `InspectorObserveTest`,
  `ObservationsIdleTest`, `ObservationsCompletenessTest` will need updating
  where they assert the per-change cadence.

A new file under `inspect/src/main/kotlin/civictech/inspect/` is fine if the
windowing deserves its own collaborator, the way `Flow.kt` does.

Touching files outside `inspect/src/**`: note it in the completion report
rather than expanding silently. V1A-FE owns `inspect/ui/**` and runs
concurrently on the same wave.

## Read first

- `doc/spec/90-roadmap/98-inspector-v4-plan/10-design-notes.md` §"Binding
  constraints", §"Verticals → V1a" — the governing constraints and this
  vertical's scope.
- `doc/spec/90-roadmap/97-inspector-plan/20-api-contract.md:172` (the
  `state.summary` row) and `:176` (the `flow.rates` row, whose cadence
  paragraph is the wording your feed should end up deserving).
- `inspect/src/main/kotlin/civictech/inspect/Flow.kt:107-215` — the pattern to
  copy: window state, quiet publication, trailing window, close semantics.
- `inspect/src/main/kotlin/civictech/inspect/Observations.kt` (whole file) —
  especially `:158-203` (subscription construction), `:237-245` (idle sweep),
  `:282-287` (`reading`), `:385-410` (`StampedView`).
- `inspect/src/main/kotlin/civictech/inspect/InspectorServer.kt:485-571` — the
  `Tick` list and `tickAll()` test seam.
- `inspect/src/test/kotlin/civictech/inspect/InspectorObserveTest.kt` — the
  end-to-end observe/SSE harness (`HttpProbe`, SSE tap, `awaitUntil`).
- `inspect/src/test/kotlin/civictech/inspect/ObservationsIdleTest.kt` — the
  injected-clock pattern; use it rather than sleeping.
- `doc/spec/90-roadmap/97-inspector-plan/90-progress-log.md:756-760` and
  `:1150-1151` — the carried open item this ticket closes, in the evaluators'
  own words.

Do not modify: `inspect/ui/**` (V1A-FE), `concord/**` (design notes constraint
7), `doc/spec/90-roadmap/97-inspector-plan/20-api-contract.md` (orchestrator-
owned), any plan document other than this ticket's `**Status**:` line.

Known, out of scope: `./gradlew :concord:docLints` currently reports fatal
Status-header findings on this plan folder's own documents (`00-orchestration.md`
and `10-design-notes.md` use the word `Planned`, which is outside the lint's
vocabulary). They are pre-existing, not yours to fix, and `./gradlew test` does
not run that lint — do not "helpfully" edit those files.

## Acceptance criteria

- [ ] A burst of N settled effective changes on one observed cell inside one
      window produces exactly one `state.summary` for that cell, carrying the
      latest cardinality and frontier — not N summaries, not an intermediate
      reading.
- [ ] While an observation is open and nothing changes, a summary is published
      every window, with `staleMs` growing monotonically and
      `frontier`/`cardinality` unchanged.
- [ ] `staleMs` decreases in exactly the windows in which an effective change
      settled, and never in a quiet one (the FE's change predicate).
- [ ] Releasing an observation — via `DELETE`, via the idle sweep, and via
      inspector close — produces exactly one trailing summary for that cell and
      then silence. No summary is ever published for a cell that was never
      observed (P6).
- [ ] Summary-then-state-read freshness: after a mutation, the summary that
      announces it arrives within one window, and a `GET /cell/{ref}/state`
      issued on receiving it reflects that mutation.
- [ ] Two cells observed concurrently each get their own windows; neither
      starves nor is coalesced into the other.
- [ ] Every new test is deterministic — driven by `tickAll()` and/or an injected
      clock plus `awaitUntil`, never by wall-clock sleeps or by asserting on
      scheduler timing.
- [ ] No new thread: the window runs on the existing inspector scheduler.
- [ ] Any contract-shape addition is additive, is flagged in the completion
      report, and `20-api-contract.md` is unmodified in the diff.
- [ ] No unrelated files in the diff; no generated/build output.

## Verify

```bash
./gradlew :inspect:test
# and the narrow loop while iterating, e.g.
./gradlew :inspect:test --tests 'civictech.inspect.InspectorObserveTest'
```

Also confirm nothing downstream of `:inspect` regressed:

```bash
./gradlew :demo:skillmatch:test
```

## Report on completion

- Checks run and their results; which existing tests changed, and why the
  cadence assertion they held is now wrong rather than merely different.
- The per-change cost on the observation path after your change (reads, writes,
  allocations), stated explicitly — the P2 claim should be checkable, not
  asserted.
- Files actually touched, and any not in the claim above.
- **Flag to the orchestrator** (contract, `20-api-contract.md` — do not edit it
  yourself):
  1. The `state.summary` row needs a cadence paragraph of the kind `flow.rates`
     already has ("publishes every window while any cell is observed, even a
     quiet one, then one trailing window after release"). Propose the exact
     wording.
  2. The row currently says summaries are emitted "only for cells with an
     active observe subscription". The decided **trailing window fires after
     release** — say whether you read that as a contract contradiction needing
     new wording, and propose it.
  3. Any additive field you added (change count / changed flag), with the
     reason the four existing fields were insufficient.
  4. Whether the immediate catch-up summary at observe-start still exists after
     windowing, and whether the contract should promise it (the FE currently
     does its own `GET state` on observe success and does not depend on it).
  5. `state.summary` shares the monotonic `seq` with topology deltas
     (`InspectorModel.kt:365`); note any ordering consequence of moving
     emission onto the scheduler thread.
- Anything specified here you could not do, and why.
