# E2-ALIGN — the aligned multi-view sink: one composite snapshot per settled wave, never assembled from mixed waves

**Status**: Implemented — merged
**Model:** claude-opus-5 (effort xhigh) · **Escalate to:** claude-fable-5,
fresh session
**Wave:** C1 · **Branches:** ticket/e2-align

## Context

ComputeNet is a Kotlin/JVM dataflow runtime. E2-SPEC (merged) made the
observation frontier normative spec text
(`doc/spec/20-dataflow-semantics/22-consistency.md` §The observation
frontier, `:250-325`):

> `[22-OBS-01]` The composite observation edge SHALL achieve internal
> consistency as **per-source-vector-frontier alignment**: every emitted
> composite output SHALL be the correct output for some per-source vector
> frontier of this replica's inputs (`:265-268`).
> `[22-OBS-02]` A composite for wave `(s, t)` SHALL be assembled only after
> every contributing view has settled every wave at or below the shared
> frontier, and the composite's inlets SHALL be **per-name**, preserving each
> contributing view's identity, rather than replaying every view's raw deltas
> into one anonymous stream (`:284-296`).

The spec marks itself spec-ahead-of-code: *"this is the specification for the
future `observeAligned` sink — 96 §E2.3 — written before its implementation"*
(`22-consistency.md:298-301`). This ticket is that implementation. Both
prerequisites the guarantee is built from are **already landed** (verified by
the R-ENG checkpoint against the live repo, 2026-07-30):

- **The completeness primitive**: `cell.consistency.WaveFrontier`
  (`kernel/src/main/kotlin/civictech/cell/consistency/WaveFrontier.kt:58`,
  CP-A4) — the per-inlet wave-completeness fold: per-edge per-source
  watermarks advancing on a real delta, a metadata-plane `Progress`
  absorb-ack, or a later wave (`:143-152`, `:190-194`, `:314-319`); waves
  released in per-source counter order (`flushReady`, `:408-417`); unwaved
  and baseline traffic passed straight through, never buffered (`offer`,
  `:231-271`). The spec itself says the guarantee is "one inlet short of the
  many-named-inlet aligned composite" (`22-consistency.md:303-312`).
- **The absorb-ack rule**: `cell.control.absorbAck`
  (`kernel/src/main/kotlin/civictech/cell/control/AbsorbAck.kt:25-31`,
  CP-A3), adopted across the operator suite (`22-consistency.md:214-218`) —
  without it, a frontier spanning an absorbing view could stall short of the
  shared frontier forever.

What exists today at the observation edge is the honest point-consistent
fallback: `cell.observe.CompositeSink` behind `ManagedHost.observeAll`
(`kernel/src/main/kotlin/civictech/cell/observe/Observe.kt:382-559`). Its own
KDoc says it is "point-consistent per outlet, NOT wave-aligned … a read may
therefore pair `common` at wave `t` with `filtered` at wave `t-1`"
(`:387-393`) — the F-5 flash. This ticket builds the wave-aligned sibling
beside it; `observeAll` is kept.

Note two stale path/claim artifacts you will encounter:

- `96-incremental-engines-plan.md:217` names the new file
  `host/AlignedObserve.kt`; the observe machinery actually lives in
  `civictech.cell.observe` (`kernel/.../cell/observe/Observe.kt`). Build in
  the `observe` package. Do not edit the 96-plan.
- `observeAll`'s deferral KDoc (`Observe.kt:535-554`) documents two blockers.
  Blocker 1 (name erasure through `GlitchFreeCell`) is what `[22-OBS-02]`'s
  per-name inlets dissolve structurally. Blocker 2 claims "slotfinder's
  `FilterCell` drops slots and emits no `Progress` absorb-ack" — **stale**:
  `FilterCell` has absorb-acked since CP-A3 (it imports and calls
  `absorbAck`; the spec's adopted list `22-consistency.md:214-218` names
  it). Your KDoc update trues this.

## Problem

A multi-view observation edge folds several named outlets into one composite
read. With `CompositeSink`, each named outlet is folded by its own
`ObserveCell` and the composite assembles the *latest* per-outlet snapshot on
every per-outlet change — so mid-wave, an app (an SSE frame, a demo `/state`
endpoint) can observe `items` post-wave next to `filtered` pre-wave: a
composite that is "the correct output" for **no** per-source frontier of the
inputs, violating `[22-OBS-01]`. Wrapping in `GlitchFreeCell` cannot fix it:
that cell replays raw deltas to a single outlet, erasing which named source
each delta came from (blocker 1, `Observe.kt:540-544`).

## Solution direction

One new file, `kernel/src/main/kotlin/civictech/cell/observe/AlignedObserve.kt`,
implementing 96 §E2.3's second half
(`doc/spec/90-roadmap/96-incremental-engines-plan.md:211-229`) against the
merged spec. The decided design, not open for redesign:

- **`AlignedCompositeCell`** — one hosted cell with one **named** inlet per
  contributing view (`registerPort` per name, each a
  `FanInlet.create<Propagate<D>>()` served with its `View<D, S>` fold —
  `View` is the existing fold adapter, `Observe.kt:72-111`). Per-name inlets
  are the `[22-OBS-02]` mechanism: each buffered delta's view identity is
  structural (which inlet it arrived on), never inferred from the delta type.
- **Per-wave delta buffers + one completeness condition spanning ALL inlets'
  edges.** Buffer each wave's per-inlet deltas keyed by `Timestamp`; a wave
  is released only when every open expected `Consume` edge **across every
  named inlet** has settled it (watermark ≥ counter, advancing on a real
  delta, a `Progress` ack, or a later wave). This is `WaveFrontier`'s
  condition widened to the union of the inlets' edge sets.
  - Structural note on reuse, decided in D-COMBINE and recorded in
    `CoalescingCombineCell`'s KDoc
    (`kernel/src/main/kotlin/civictech/cell/data/op/CoalescingCombineCell.kt:69-95`):
    an installed `WaveFrontier` releases a completed wave's invocations
    *individually* and holds the inlet's one `Protocols.Progress` handler —
    and it is per-inlet, so per-inlet installs cannot express a cross-inlet
    shared frontier. **Mirror the fold at cell scope across the named
    inlets** (edges/floors/watermarks/flushed-high-water per
    `CoalescingCombineCell.kt:119-267` — including `noteAbsorbed`'s
    buffer-the-acked-wave move, `:219-232`), rather than modifying
    `WaveFrontier`. `WaveFrontier` itself is untouched.
- **At completeness**: apply the wave's buffered deltas per-source-counter
  order to each named view's fold, then publish **one** composite snapshot
  (`Map<String, Any?>`-shaped, like `CompositeSink.current`) — effective-only:
  a completed wave in which no view's `apply` returned an effective change
  publishes nothing.
- **Unwaved and baseline traffic installs as arm state immediately** — folded
  into the named view at once, admitted to no completeness set, exactly
  `WaveFrontier.offer`'s two catch-up arms (`WaveFrontier.kt:197-247`) and
  `CoalescingCombineCell.passThrough` (`:178-201`). A baseline that changes a
  view's value may publish a fresh composite (it is arm state, not a wave) —
  the `GlitchFreeCell`-analogous behavior 96 §E2.3 names ("baselines install
  as arm state").
- **WAIT-shape only.** Like `CoalescingCombineCell` (its KDoc `:89-95`),
  deliberately do not mirror `WaveMode.DEGRADE` frontier shrinking, RE-SCOPE,
  replica-fed settlement (E3.4), or pull-on-open. `EdgeClose` shrinks the
  condition; a stalled arm's waves stay buffered until it resumes, produces a
  later wave, acks, or its edge closes. Document this in the class KDoc.
- **The app surface**: `ObservationSink` semantics (`Observe.kt:43-63`) —
  `current()` as an immutable torn-read-free snapshot, `onChange` with
  immediate catch-up, listener dispatch off the host scheduler thread on a
  dedicated single-consumer executor with the exact lock/ordering discipline
  `ObserveCell` documents (T08 finding 4, `Observe.kt:126-141`, `:154-262`
  including `close`/`reopen` across RESTART). Do not regress that discipline.
- **The builder**: `fun ManagedHost.observeAligned(block: ...): ...` beside
  `observeAll` (`Observe.kt:556-559`) — an **extension function**, mirroring
  `observe`/`observeAll`'s spawn-and-connect assembly (`:285-307`); the
  builder names one fold per source outlet (`set`/`map`/`count` registrars
  like `ObserveAllBuilder`, `:326-380`). **`ManagedHost.kt` itself must not
  be edited** (cross-track claim rule 1, `../00-orchestration.md`).
- **Doc truing**, minimal and exact:
  - `Observe.kt`: `observeAll`'s deferral KDoc (`:535-554`) now points to
    `observeAligned` as the shipped wave-aligned composite and drops/trues
    the stale blocker text (blocker 1 dissolved by per-name inlets; blocker 2
    stale since CP-A3); `CompositeSink`'s honesty KDoc (`:387-393`) points to
    the alternative.
  - `doc/spec/20-dataflow-semantics/22-consistency.md:298-301`: rewrite the
    *(Spec-ahead-of-code …)* paragraph to record the landed implementation
    (name the class and builder), keeping the `CompositeSink`-remains-the-
    point-consistent-fallback clause. Touch nothing else in the file; §The
    observation frontier is this ticket's only spec claim (E2-GATE owns
    §Completeness and the 20/24 operator rows).

**Latitude** (yours to decide): class/file internals — builder class name,
inlet naming scheme, whether typed `TypedRef` overloads are included (the
untyped `CellRef` registrars are sufficient; mirror `ObserveAllBuilder`'s
typed set only if cheap); the composite's exposed type (a `CompositeSink`-like
named-map sink with a checked `get`, or a dedicated class); whether
`Stateful` snapshot/restore of the view folds is implemented (recommended —
`ObserveCell` is `Stateful`; the transient wave buffer is dropped on
deactivate per `CoalescingCombineCell.onDeactivate`'s rule `:289-297`);
internal factoring of the mirrored frontier fold (a private helper class is
fine — inside this file, not a change to `cell.consistency`).

**NOT open for redesign / NOT in scope:**

- **No modification of `WaveFrontier.kt`, `GlitchFree.kt`, `FanInlet`, or
  anything in `cell.consistency`/`cell.port`/`cell.control`.** Mirror, don't
  extend — the D-COMBINE precedent.
- **No `emitOnFrontier` work on `SemiJoinCell`/`CombineLatestCell`** (that is
  E2-GATE, wave C2) and **no balanced-transfer suite** (E2-SUITE, wave C3).
- **No `cell/data/**` or `cell/data/op/**` edits of any kind** — track A wave
  9 (`V1C-CELLS`/`V1C-OPS`) owns those paths and is in flight concurrently
  with this ticket. Your test composes existing cells; it changes none.
- **No `ManagedHost.kt`, no `SetCell.kt`** (rule 1), no `concord/**`, no
  `wire/**`, no `demo/**`, no `gen/**`.
- **`observeAll`/`CompositeSink` behavior unchanged** — KDoc edits only; the
  point-consistent fallback remains shipped and tested.

### Test requirement

`kernel/src/test/kotlin/civictech/cell/observe/AlignedObserveTest.kt` (a new
test package directory — deliberate: the `cell/data/` and `cell/data/op/`
test directories are claimed by track A wave 9), in the register of
`kernel/src/test/kotlin/civictech/cell/consistency/GlitchFreeDiamondTest.kt`
(seeded `SimulationController`, partial randomized draining `:108-119`,
invariant swept over many seeds, a control proving the harness produces the
failure):

- **Invariant run** (96 §E2.3): one source cell fans to two derived cells
  (e.g. a `SetCell` source through a `FilterCell` arm and an identity/second
  arm) into `observeAligned { set("items", …); set("filtered", …) }`. Across
  ≥ 200 seeded schedules, **every** published composite satisfies a per-wave
  relation that mixed-wave assembly breaks (e.g. `filtered == items.filter(p)`
  — since both views derive from the same source wave, any composite pairing
  different waves violates it). Also assert composites are published in
  per-source monotone wave order.
- **Control**: `observeAll` (the `CompositeSink` path) over the same graph
  and seed range trips the invariant on at least one seed. If it never trips,
  the harness is too weak to certify the sink — tune interleaving as
  `GlitchFreeDiamondTest` does.
- **Absorbed-arm liveness**: a wave the filter arm swallows entirely (no
  membership change on that arm) still settles — the composite for that wave
  (or the next effective one) publishes after `runToIdle`, with no wave left
  permanently buffered. This exercises the `Progress`-ack consumption path.
- **Baseline/catch-up**: a sink attached after deltas have flowed reports the
  producer's current state via `current()` and the immediate `onChange`
  catch-up (the `observe` seeding path), without admitting the baseline to
  any wave set.
- **Two-JVM bridged variant** (96 §E2.3 allows an alternative): either a
  bridged test showing an aligned composite over one remote arm (`EdgeOpen`/
  `EdgeClose` and `Progress` already cross as frames — `22-consistency.md:
  227-233`), or an explicit documented limitation in the class KDoc plus the
  completion report. Do not silently skip it.

Bounded waits and existing simulation controls only (`SimulationController`,
`runToIdle`, `testkit`'s `awaitUntil` for real threads). Do not replace a
discovered failing seed with a friendlier one.

## Files expected to touch

- **New**: `kernel/src/main/kotlin/civictech/cell/observe/AlignedObserve.kt`
- **Modified**: `kernel/src/main/kotlin/civictech/cell/observe/Observe.kt` —
  KDoc truing only (deferral text, `CompositeSink` pointer). No behavior
  change.
- **Modified**: `doc/spec/20-dataflow-semantics/22-consistency.md` — the
  `:298-301` spec-ahead-of-code paragraph only.
- **New**: `kernel/src/test/kotlin/civictech/cell/observe/AlignedObserveTest.kt`
- This ticket's `**Status**:` line.

Nothing else. No generated/build output in the diff.

## Read first

- `doc/spec/20-dataflow-semantics/22-consistency.md:250-325` — §The
  observation frontier in full: `[22-OBS-01]`, `[22-OBS-02]`, the
  per-name-inlet rationale, the WaveFrontier/absorb-ack grounding.
- `doc/spec/90-roadmap/96-incremental-engines-plan.md:211-229` — §E2.3, the
  content source (its extraction half is landed; you build the sink half).
- `kernel/src/main/kotlin/civictech/cell/observe/Observe.kt` — in full. The
  `View` folds you reuse, `ObserveCell`'s threading/lifecycle discipline you
  must match, `ObserveAllBuilder`/`observeAll` whose assembly you mirror and
  whose KDoc you true.
- `kernel/src/main/kotlin/civictech/cell/consistency/WaveFrontier.kt` — the
  completeness fold you mirror: `offer`'s three arms (`:197-271`), watermark
  fold (`:143-152`, `:190-194`, `:314-319`), `expectedLocalEdges` (`:355-362`),
  `flushReady` (`:408-417`), `reset` (`:274-282`).
- `kernel/src/main/kotlin/civictech/cell/data/op/CoalescingCombineCell.kt` —
  the D-COMBINE precedent for mirroring the fold at cell scope: why
  composition was structurally unavailable (`:69-95`), edge/floor/watermark
  state (`:119-156`), `noteAbsorbed` (`:219-232`), context-carrying emission
  (`:269-287`), `onDeactivate` (`:289-297`).
- `kernel/src/main/kotlin/civictech/cell/control/AbsorbAck.kt` — the emitting
  side of the ack your frontier consumes.
- `kernel/src/test/kotlin/civictech/cell/consistency/GlitchFreeDiamondTest.kt`
  — the seeded diamond harness register, including its control.
- `kernel/src/test/kotlin/civictech/cell/host/ObserveCellTest.kt` — the
  existing observe test register (catch-up, listener ordering).
- `doc/spec/90-roadmap/99-defects-engines-plan/00-orchestration.md` — wave C1
  table and cross-track claim rules (rule 1; rule 6 — the wave-9 concurrency
  rule this ticket's file claim was designed around).
- `AGENTS.md` §"Core invariants to protect" and §"Verification".

Do not modify: `kernel/src/main/kotlin/civictech/cell/consistency/**`,
`kernel/src/main/kotlin/civictech/cell/port/**`,
`kernel/src/main/kotlin/civictech/cell/control/**`, `cell/data/**`,
`cell/data/op/**`, `kernel/.../host/ManagedHost.kt`,
`kernel/.../data/SetCell.kt`, `concord/**`, `wire/**`, `demo/**`, `gen/**`,
`inspect/**`, any plan document other than this ticket's `**Status**:` line.

## Acceptance criteria

- [ ] `AlignedCompositeCell` exists in `cell.observe` with one named inlet
      per registered view; view identity is structural (per-name inlets),
      satisfying `[22-OBS-02]`'s inlet clause.
- [ ] A composite for wave `(s, t)` is published only after every
      contributing view's expected open `Consume` edges have settled every
      wave at or below it — watermarks advancing on a real delta, a
      `Progress` absorb-ack, or a later wave — satisfying `[22-OBS-02]`'s
      assembly clause; buffered deltas apply in per-source counter order.
- [ ] Unwaved and baseline traffic installs as arm state immediately and is
      excluded from every completeness set; publication is effective-only.
- [ ] `ManagedHost.observeAligned { … }` exists as an extension function
      (no `ManagedHost.kt` edit) and returns an `ObservationSink`-conforming
      surface with `ObserveCell`'s documented listener/threading discipline.
- [ ] The invariant test passes: ≥ 200 seeds, every published composite
      satisfies the per-wave relation; monotone per-source publication order.
- [ ] The control test trips: `observeAll` over the same graph and seeds
      violates the relation on at least one seed.
- [ ] Absorbed-arm liveness and late-join catch-up cases pass; the two-JVM
      bridged variant passes or its limitation is explicitly documented in
      the class KDoc and the report.
- [ ] `Observe.kt` KDoc is trued (stale blocker text gone, pointer to
      `observeAligned`); `observeAll`/`CompositeSink` behavior is unchanged
      and their existing tests still pass.
- [ ] `22-consistency.md:298-301` records the landed implementation; no
      other spec hunk. `./gradlew :concord:docLints` is clean.
- [ ] `./gradlew :kernel:test` is green.
- [ ] `git status` shows only the claimed files.

## Verify

```bash
./gradlew :kernel:test --tests 'civictech.cell.observe.AlignedObserveTest'
./gradlew :kernel:test --tests 'civictech.cell.host.ObserveCellTest'
./gradlew :kernel:test
./gradlew :concord:docLints
git status --porcelain     # only the claimed files
```

(The repo-wide `./gradlew test` gate runs at checkpoint CC1 before the wave's
merge closes — run it yourself if time permits.)

## Report on completion

- How the cross-inlet completeness fold is structured (what was mirrored from
  `WaveFrontier`/`CoalescingCombineCell`, what was omitted as WAIT-shape) in
  three sentences.
- The exact test FQNs run, seed/wave counts, and how many seeds the
  `observeAll` control tripped on.
- Whether the bridged two-JVM variant ran or the documented limitation taken.
- The builder surface shipped (registrar names, typed overloads or not) —
  E2-SUITE (wave C3) codes against it and needs the exact shape.
- Anything specified here you could not do, and why.
