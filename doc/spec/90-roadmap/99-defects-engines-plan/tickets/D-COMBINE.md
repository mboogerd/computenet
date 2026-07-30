# D-COMBINE — a wave-coalescing scalar combine: one delta per completed wave, so no torn intermediate sum is ever observable

**Status**: Specified — not-started
**Model:** claude-opus-5 (effort xhigh) · **Escalate to:** claude-fable-5,
fresh session
**Wave:** B1 · **Branches:** ticket/d-combine

## Context

ComputeNet is a Kotlin/JVM dataflow runtime. Glitch-freedom is a normative,
opt-in guarantee (`doc/spec/20-dataflow-semantics/22-consistency.md`):

> `[22-GF-01]` WHILE a wave from a single source is partially delivered across
> a fork-join, a cell that has declared itself glitch-free SHALL NOT expose
> derived state that mixes pre-wave and post-wave inputs (`:132-135`); the
> named mechanism is **version buffering** — "buffer per-wave inputs until the
> input set for that wave is complete" (`:139-140`). `[22-GF-02]` glitch-freedom
> **composes** across nested/chained fork-joins (`:152-155`).

The kernel machinery for this exists and is healthy for the **set** shape:

- `kernel/src/main/kotlin/civictech/cell/consistency/WaveFrontier.kt:58` — the
  wave-completeness fold, **already extracted** from `GlitchFreeCell` as a
  reusable per-inlet policy (CP-A4; its own KDoc `:38-43` says so). It buffers
  waves per `Timestamp` (`:129`), folds `EdgeOpen`/`EdgeClose` and per-edge
  per-source watermarks (`:143-152`, `:314-319`), and releases complete waves
  in per-source counter order (`flushReady`, `:408-417`) — each buffered
  invocation individually, "each under its own context" (`:415`).
- `kernel/src/main/kotlin/civictech/cell/consistency/GlitchFree.kt:47-82` —
  `GlitchFreeCell`, the sugar packaging of that policy: a bare inlet→outlet
  pass-through that `install`s a `WaveFrontier` on its inlet (`:80`). Any plain
  cell can opt in the same way (`FanInlet.install`,
  `kernel/src/main/kotlin/civictech/cell/port/FanInlet.kt:120`; at most one
  ALIGN-tier policy per inlet, `:117-122`) — proven by
  `kernel/src/test/kotlin/civictech/cell/consistency/InletFrontierPolicyTest.kt:54-68`,
  where a plain observer cell installs a WAIT frontier and is glitch-free with
  no `GlitchFreeCell` involved.
- `kernel/src/main/kotlin/civictech/cell/data/op/QuorumSetCell.kt:81-114` —
  the set fan-in join whose `evaluate()` only `propagate()`s once quorum is
  met, i.e. the set shape coalesces **at the operator**.

What does **not** exist is the scalar analogue. The kernel's only
`combine-latest` is the keyed-map outer combine
(`kernel/src/main/kotlin/civictech/cell/data/op/CombineLatestCell.kt:45` —
`MapDelta` in, `MapDelta` out; it cannot combine two `CounterDelta` streams).
The only scalar combine binding anywhere is the **concord driver's**
`ScalarSumCombineCell`
(`concord/src/main/kotlin/civictech/concord/driver/kernel/KernelAdapters.kt:89-116`)
— a driver-side adapter, not a kernel cell — which emits a `CounterDelta` per
input arm (`emitDelta`, `:103-108`). Its own KDoc (`:81-87`) confesses the
consequence: each arm's emission rides a distinct wave, a downstream
`GlitchFreeCell` replays per-invocation, and intermediate (odd) sums stay
observable mid-wave.

This is documented in three places as the last genuine glitch-free kernel gap:

- `concord/corpus/DISPUTES.md:213-231` — the entry
  "`24-OP-COMBINE-01` / `CTL-GF-01` — scalar `combine-latest` `kernel-gap`":
  "A version-buffered combine that emits once per completed wave is absent.
  This is the one genuine remaining glitch-free `kernel-gap`" (also `:130-136`
  in the category index).
- `concord/schema/cell-catalog.md:114-122` — "The one honest gap":
  "A genuine glitch-free scalar combine does not exist in the kernel."
- `concord/corpus/controls/CTL-GF-01.yaml` — a deliberate **failing sentinel**
  (`kind: control`): one counter source forked through two identity arms into
  a scalar summing `combine-latest`, asserting
  `observations-all-satisfy(v, even)`. It FAILS-as-asserted on every sweep,
  keeping the gap honest and visible.

This ticket closes the kernel half of that gap. The concord half — driver
binding, the positive `24-OP-COMBINE-01` assertion, retiring the `CTL-GF-01`
sentinel, resolving the DISPUTES entry — is **D-CONCORD** (wave B2), which
consumes the cell this ticket builds.

## Problem

Trace the tear through `CTL-GF-01`'s graph shape. A counter source `n`
increments by 1 (wave *t*). The wave forks through two identity arms `l` and
`r` into the summing combine `s`, whose downstream observer `v` folds a running
total:

1. Arm `l`'s `CounterDelta(1)` arrives at `s` first. `ScalarSumCombineCell`
   updates `leftTotal` and immediately emits the change in the sum:
   `CounterDelta(1)` (`KernelAdapters.kt:99-108`).
2. `v` folds it. With both arms previously at *k*, the observed value is now
   `2k + 1` — **odd**, a state that mixes `l`'s post-wave input with `r`'s
   pre-wave input. Exactly what `[22-GF-01]` forbids a glitch-free scalar
   observer to see.
3. Arm `r`'s delta arrives; `s` emits the second `CounterDelta(1)`; `v` reaches
   `2k + 2`. Quiescent state is correct (`final-view` holds) — only the
   intermediate is torn.

Wrapping `s` in a downstream `GlitchFreeCell` cannot rescue this: the two
emissions in steps 1 and 3 are produced under **distinct** waves (each rides
its own arm's context), so the wrapper sees two complete one-edge waves and
replays both — the torn intermediate passes straight through (verified: the
`CTL-GF-01` sweep fails on event #1, `DISPUTES.md:134-135`). Coalescing must
happen **at the combine**, before emission, just as `QuorumSetCell` does for
sets — that is the version-buffering clause of `[22-GF-01]` applied to the
operator itself.

## Solution direction

Add **one new kernel cell** under
`kernel/src/main/kotlin/civictech/cell/data/op/`: a version-buffered,
wave-coalescing scalar combine. The decided design, not open for redesign:

- **Buffer per wave, emit once per wave.** The cell buffers each wave's
  per-arm `CounterDelta` contributions and, when the wave's input set is
  complete, emits exactly **one** combined `CounterDelta`
  (`kernel/src/main/kotlin/civictech/cell/data/delta/CounterDelta.kt:9` —
  commutative, mergeable) carrying the net change in the sum. No torn
  intermediate is ever emitted, so no downstream observer can fold one.
- **Coherent wave identity.** The combined delta is emitted under the
  completed input wave's id — emit from within that wave's (last) delivered
  invocation so `CurrentContext` carries the source wave, exactly the pattern
  `GroupByCell` and `CombineLatestCell` document and implement: "all groups
  touched by one input delta emit as a single `MapDelta` under the input's
  wave id (22)" (`GroupByCell.kt:27-30`, emission at `:92-96`;
  `CombineLatestCell.kt:33-34`; also the spec's own prose citing GroupByCell,
  `22-consistency.md:159-162`). Both identity arms of one source wave carry
  the same source `Timestamp`, so the coalesced emission is unambiguous.
- **Effective-only + absorb-ack.** A completed wave whose net combined change
  is zero emits nothing and instead absorb-acks the wave
  (`outlet.absorbAck()`, CP-A3), so a downstream `GlitchFreeCell` never stalls
  on a swallowed wave — follow `QuorumSetCell.kt:101-113` (via
  `emitOrAbsorb`, `data/op/Emit.kt:19`) and `GroupByCell.kt:95`.
- **Completeness by composition with the existing machinery.** Two admissible
  routes, implementer's choice:
  1. Install the existing `WaveFrontier` on the cell's fan-in inlet
     (`FanInlet.install`, the CP-A4 policy route —
     `InletFrontierPolicyTest.kt:54-68` is the precedent), then coalesce in
     the handler. Note the frontier releases a completed wave's invocations
     *individually* (`WaveFrontier.kt:408-417`), so the cell must still know
     when the wave's released batch ends (e.g. track live `Consume` edges via
     `inlet.onEdgeEvent` and count buffered contributions per `Timestamp`).
  2. An internal per-edge-per-source frontier fold **following**
     `WaveFrontier`'s pattern at the scale this cell needs: track
     `EdgeOpen`/`EdgeClose` and per-edge floors/watermarks
     (`WaveFrontier.kt:129-152`, `:355-362`), buffer arm deltas per
     `Timestamp`, fold `Protocols.Progress` acks, and emit when every
     expected open edge has settled the wave.
- **Liveness over silent arms.** An arm that absorbs a wave (emitting nothing,
  acking via the `Progress` lane — see `QuorumSetCell`'s absorb behavior and
  `WaveFrontier.kt:190-194`) must not wedge the combine forever. Route 2
  handles this naturally via watermarks; route 1 must handle it too (observe
  the `Progress` lane, or gate on the frontier's own completeness rather than
  a bare arrival count). Whichever route you take, a wave completed with fewer
  deltas than open edges still coalesces and releases.

**Latitude** (yours to decide): the cell's name and file name; port shape
(single fan-in `inlet` with per-source lanes like `QuorumSetCell`, or named
`left`/`right` inlets like `CombineLatestCell` — the test's two-identity-arm
fan-in must be expressible either way); whether to use `@CellBase` codegen
(the `data/op/` convention) or a plain `Cell` with `registerPort` (the
`GlitchFreeCell`/`ScalarSumCombineCell` style); whether the fold is
sum-specialized or a general scalar merge — a sum-shaped scalar combine is
sufficient for this ticket; `Stateful` snapshot/restore of the running totals
(recommended, and the transient wave buffer follows `WaveFrontier.reset()`'s
rule: drop on reset, `:274-282`).

**NOT open for redesign / NOT in scope:**

- **No modification of `WaveFrontier`, `GlitchFreeCell`, or `FanInlet`.** The
  frontier fold is already extracted as the shared CP-A4 policy — reuse it
  as-is by composition or mirror its pattern internally; do not change it.
  (The briefing lineage here: engines-plan §E2.3,
  `doc/spec/90-roadmap/96-incremental-engines-plan.md:211-229`, scheduled the
  extraction *plus* the aligned multi-view sink. The extraction half has
  landed; the sink half — `host/AlignedObserve.kt`, `AlignedCompositeCell`,
  `ManagedHost.observeAligned` — has **not**, belongs to E2.3, and must not
  be pre-empted or partially built here. Smallest coherent change wins.)
- **No `concord/**` edits of any kind.** The driver binding, the positive
  scenario assertion, and retiring the `CTL-GF-01` sentinel are D-CONCORD
  (wave B2). After this ticket merges, `CTL-GF-01` is *still* a failing
  sentinel — that is correct and expected.
- **No edits to `kernel/.../host/ManagedHost.kt` or `kernel/.../data/SetCell.kt`**
  (claimed by track A's V1C-KERNEL for the whole run —
  `../00-orchestration.md`, cross-track claim rule 1) and **no edits to
  `QuorumSetCell.kt`/`PresenceCountCell.kt`** (claimed by D-REPLAY in this
  same wave) or **`UnionSetCell.kt`/`data/delta/**`** (claimed by D-UNION).
  `CounterDelta` already exists; you need no delta changes.
- **No behavior change to any existing cell.** Additive only: one new cell,
  one new test file.

### Test requirement

A kernel test mirroring `CTL-GF-01`'s shape, in the register of
`kernel/src/test/kotlin/civictech/cell/consistency/GlitchFreeDiamondTest.kt`
(seeded `SimulationController` scheduling with partial draining, `:65-119`;
invariant swept over many seeds, `:121-136`; a control proving the harness
can produce the failure, `:138-151`):

- **Invariant run**: one counter-shaped source forked through two identity
  arms (`civictech.cell.MapperCell`, or `CounterCell` +
  identity mappers — `kernel/src/main/kotlin/civictech/cell/data/CounterCell.kt:27`)
  into the new coalescing combine, observed by a test observer folding the
  running sum. Assert **every** observed folded value satisfies the invariant
  (even sums, since each source wave moves both arms by the same amount) —
  across seeded schedules at `GlitchFreeDiamondTest` scale (≥ 200 seeds,
  ≥ 50 waves per seed), plus per-source monotone wave order of the emissions.
- **Control**: the same graph with a per-arm-emitting combine (a small
  test-local cell replicating `ScalarSumCombineCell`'s `emitDelta` shape —
  do **not** import the concord driver; kernel tests cannot and must not
  depend on `:concord`) tears — an odd observed sum — on at least one seed
  under the same seed range. If the control never tears, the harness is too
  weak to certify the fix; tune interleaving, as `GlitchFreeDiamondTest:149`
  does.
- **Liveness**: after `runToIdle`, the observer's final value equals the
  full expected total (no wave left permanently buffered), including a case
  where completeness is reached without one arm's delta (the absorbed-arm
  case above) or an explicit bounded-scope statement in the test KDoc if you
  can only exercise always-emitting arms — do not silently skip it.

Bounded waits and existing simulation controls only
(`SimulationController.step`/`runToIdle`; `testkit`'s `awaitUntil` if you use
real threads — the deterministic controller is preferred and sufficient).
Do not replace a discovered failing seed with a friendlier one.

## Files expected to touch

- **New**: `kernel/src/main/kotlin/civictech/cell/data/op/<YourCellName>.kt`
  — the coalescing combine (name is yours; something like
  `CoalescingCombineCell.kt` in the register of its siblings).
- **New**: `kernel/src/test/kotlin/civictech/cell/consistency/CoalescingScalarCombineTest.kt`
  (or under `data/op/`'s test package — match where you put the cell; keep
  the invariant/control/liveness cases in this one file).
- If you use `@CellBase` and the generator needs no change (it should not —
  the annotation is data-driven), nothing under `gen/` changes.
- This ticket's `**Status**:` line.

Nothing else. No `concord/**`, no `doc/spec/**` beyond this ticket file, no
generated/build output in the diff.

## Read first

- `concord/corpus/DISPUTES.md:213-231` — the defect entry this ticket
  half-closes (and `:106-136`, the category index framing).
- `concord/corpus/controls/CTL-GF-01.yaml` — the sentinel whose graph shape
  your test mirrors.
- `concord/src/main/kotlin/civictech/concord/driver/kernel/KernelAdapters.kt:70-116`
  — `ScalarSumCombineCell` and its KDoc's confession; the shape your control
  replicates and your cell obsoletes.
- `doc/spec/20-dataflow-semantics/22-consistency.md:125-169` — `[22-GF-01]`
  (`:132-135`), version buffering (`:139-140`), `[22-GF-02]` composition
  (`:152-155`), the GroupByCell one-delta-per-wave prose (`:159-162`).
- `kernel/src/main/kotlin/civictech/cell/consistency/WaveFrontier.kt` — the
  completeness fold you compose with or pattern-match: `offer` (`:231-271`),
  watermarks (`:143-152`), `expectedLocalEdges` (`:355-362`), `flushReady`
  (`:408-417`), `reset` (`:274-282`).
- `kernel/src/main/kotlin/civictech/cell/consistency/GlitchFree.kt:34-82` —
  the sugar packaging; how a frontier is installed on an inlet.
- `kernel/src/test/kotlin/civictech/cell/consistency/InletFrontierPolicyTest.kt:33-129`
  — a plain cell composing `WaveFrontier` via install; route 1's precedent.
- `kernel/src/main/kotlin/civictech/cell/data/op/GroupByCell.kt:24-97` and
  `CombineLatestCell.kt:22-77` — the "one output delta under the input's
  wave id" emission pattern and effective-only/absorb-ack discipline.
- `kernel/src/main/kotlin/civictech/cell/data/op/QuorumSetCell.kt:52-114` —
  the set-shaped operator that already coalesces at the operator; the
  `emitOrAbsorb` discipline (`:101-113`).
- `kernel/src/test/kotlin/civictech/cell/consistency/GlitchFreeDiamondTest.kt`
  — the seeded diamond harness your test mirrors, including its control.
- `doc/spec/90-roadmap/96-incremental-engines-plan.md:211-229` — §E2.3, to
  see precisely what you must NOT build (the aligned multi-view sink half).
- `doc/spec/90-roadmap/99-defects-engines-plan/00-orchestration.md` — wave B1
  table, cross-track claim rules 1-3, and the D-CONCORD follow-up you feed.
- `AGENTS.md` §"Core invariants to protect" and §"Verification".

Do not modify: `concord/**`, `wire/**`, `demo/**`, `inspect/**`, `gen/**`,
`kernel/src/main/kotlin/civictech/cell/consistency/**`,
`kernel/src/main/kotlin/civictech/cell/port/**`,
`kernel/.../host/ManagedHost.kt`, `kernel/.../data/SetCell.kt`,
`kernel/.../data/op/QuorumSetCell.kt`, `kernel/.../data/op/PresenceCountCell.kt`,
`kernel/.../data/op/UnionSetCell.kt`, `kernel/.../data/delta/**`, any plan
document other than this ticket's `**Status**:` line.

## Acceptance criteria

- [ ] A new cell under `kernel/src/main/kotlin/civictech/cell/data/op/`
      buffers each wave's per-arm scalar inputs and emits exactly **one**
      combined `CounterDelta` per completed source wave — never a per-arm
      intermediate.
- [ ] The combined delta is emitted under the completed input wave's identity
      (the GroupByCell pattern), so wave/source/tag continuity is preserved
      and a downstream `GlitchFreeCell` composes normally (`[22-GF-02]`).
- [ ] A zero-net completed wave absorb-acks instead of emitting
      (effective-only, CP-A3) — no downstream frontier stall.
- [ ] The invariant test passes: `CTL-GF-01`'s graph shape over the new cell,
      every observed folded value even, ≥ 200 seeds × ≥ 50 waves, per-source
      monotone emission order.
- [ ] The control test tears: a test-local per-arm-emitting combine (the
      `ScalarSumCombineCell` shape) produces an odd observed sum on at least
      one seed of the same range — proving the harness detects the defect the
      new cell removes.
- [ ] The liveness case passes: `runToIdle` reaches the full expected total
      with no wave left buffered; the absorbed-arm case is exercised or its
      exclusion explicitly documented in the test KDoc.
- [ ] No existing kernel source file is modified; `WaveFrontier`,
      `GlitchFreeCell`, `FanInlet` and every file in the "Do not modify" list
      are untouched; no `concord/**` change; `CTL-GF-01` remains a failing
      sentinel (D-CONCORD retires it).
- [ ] `./gradlew :kernel:test` is green.
- [ ] `git status` shows only the claimed files: the new cell, the new test,
      this ticket's `**Status**:` line. No generated/build output.

## Verify

```bash
./gradlew :kernel:test --tests 'civictech.cell.consistency.CoalescingScalarCombineTest'
./gradlew :kernel:test
git status --porcelain     # only the claimed files
```

(Adjust the first command's FQN if you name the test differently; state the
exact FQN you ran in the report. The repo-wide `./gradlew test` gate runs at
checkpoint CB1 before the wave's merges close — run it yourself if time
permits.)

## Report on completion

- Which completeness route you took (composed `WaveFrontier` policy vs
  internal frontier fold) and why, in two sentences.
- The exact test FQNs run and their seed/wave counts; how many seeds the
  control tore on.
- How the emitted delta's wave identity is established (which invocation's
  context carries it).
- How the absorbed-arm liveness case is handled — or the explicit limitation
  if the ticket's allowed exclusion was used.
- Confirmation that `concord/**` is untouched and `CTL-GF-01` still fails as
  a sentinel, and anything D-CONCORD needs to know to bind the new cell
  (constructor shape, port names, inlet mode).
- Anything specified here you could not do, and why.
