# E2-SUITE — the balanced-transfer internal-consistency acceptance suite

**Status**: Implemented — merged
**Model:** claude-opus-5 (effort xhigh) · **Escalate to:** claude-fable-5,
fresh session
**Wave:** C3 · **Branches:** ticket/e2-suite

## Context

ComputeNet is a Kotlin/JVM dataflow runtime. The observation-frontier spec
(E2-SPEC, merged) names its own acceptance benchmark
(`doc/spec/20-dataflow-semantics/22-consistency.md` §Acceptance benchmark:
the balanced-transfer suite, `:314-325`):

> a generative stream of balanced transfers — every debit paired with a
> credit in one wave — driven through a credit/debit join, a grouped
> aggregate, and an outer self-join, with the invariant (the running total is
> always zero; no observed output corresponds to a partially-applied
> transfer) checked at *every* observed output, not only at idle. A
> point-consistent composite and an ungated (non-monotone) outer join are
> the suite's documented failure controls.

This is 96 §E2.5 (`doc/spec/90-roadmap/96-incremental-engines-plan.md:
247-257`), test-only. Both mechanisms it certifies are merged by the time
this ticket dispatches:

- **E2-ALIGN** (wave C1, **merged `dea1e58`**): `observeAligned` /
  `AlignedCompositeCell` in `civictech.cell.observe` — the wave-aligned
  multi-view sink satisfying `[22-OBS-01]`/`[22-OBS-02]`. Read the merged
  `kernel/src/main/kotlin/civictech/cell/observe/AlignedObserve.kt` first.
  **Shipped surface** (carried here by the CC1 evaluator):
  - `ManagedHost.observeAligned { … }` (and the
    `Use<HostManagementApi>` form) → `AlignedCompositeCell`, an
    `ObservationSink<Map<String, Any?>>` with `current()`, `onChange`,
    `inline reified get<T>(name)`, `close()`, `inlets: Map<String,
    FanInlet<Propagate<Any>>>`, and two diagnostics —
    `bufferedWaves: Int`, `unmatchedDeltas: Long`. The port name **is** the
    view name.
  - Builder registrars, mirroring `ObserveAllBuilder` exactly:
    `set/map/count(name, source: CellRef, outletName = "outlet")` plus five
    typed `TypedRef` overloads (`SetApi`, `QuorumSetApi`, `FilterSetApi`,
    `MapApi`, `GroupByApi<E, K, Long>`).
  - **Two guarantee boundaries the suite must design around** (both recorded
    in the class KDoc and in `22-consistency.md:298-…`): catch-up traffic and
    mid-stream edge floors mean a sink attached *while the graph is already
    writing* can transiently expose arms seeded at different points — attach
    the sink before the generator starts, or the "invariant at every observed
    output" clause will trip on the attach transient rather than on a real
    defect. And it is the WAIT shape: two views over *independent* roots hold
    each other's waves (G-13 phantom expected edge), so every observed view in
    one aligned composite must share the pipeline's source.
  - **Do not reroute an absorbing arm alone** through the host queue in the
    suite's harness — see the CC1 harness-landmine note in
    `../00-orchestration.md` (wave C1). It reorders the `Progress` lane ahead
    of that same edge's data and produces failures that belong to the reroute
    device, not to the sink or the operators.
- **E2-GATE** (wave C2): the opt-in `emitOnFrontier` mode on `SemiJoinCell`
  and `CombineLatestCell` (`[24-OP-SEMIJOIN-04]`, the `CombineLatestCell`
  gating clause, `doc/spec/20-dataflow-semantics/24-data-cells.md:184-215`),
  plus the completed absorb-ack adoption (`CombineLatestCell`/
  `LookupJoinCell`). Its exact constructor surface is in E2-GATE's report;
  read the merged cells.

The research source the spec transcribes is
`doc/research/incremental-engines/04-cross-cutting-watermarks-consistency.md`
§3 (the "$1-transfer" experiment) — internal consistency: "every output is
the correct output for some subset of the inputs provided so far".

## Problem

The repo has per-mechanism tests (E2-ALIGN's diamond, E2-GATE's flicker
controls) but no end-to-end certification that the composed pipeline — the
shape applications actually build — is internally consistent at every
observation. Without the suite, a regression that reintroduces mixed-wave
composites or within-wave absence flicker anywhere along the composed path
would pass every unit gate. The suite is the repo's `[22-OBS-01]` teeth, the
E2 analogue of `ExchangeCompositionExitTest`'s role for composition.

## Solution direction

One new test file,
`kernel/src/test/kotlin/civictech/cell/consistency/InternalConsistencyTest.kt`
(the `cell/consistency` test package already hosts the glitch-free harnesses;
`GlitchFreeDiamondTest` is the register). Transcribe the balanced-transfer
experiment:

- **The generative stream**: seeded schedules of balanced transfers over a
  small account set — each transfer is one wave carrying a debit
  `(from, -amt)` and a credit `(to, +amt)`; amounts and account pairs
  seed-randomized. Drive them through a source shape where both legs ride
  **one wave** (one source cell emitting a two-entry delta, or a fork whose
  arms carry the same source `Timestamp` — the invariant depends on the
  pairing being a wave-level fact, so state which and why in the test KDoc).
- **Three observed pipelines** (96 §E2.5; concrete cell choices are your
  latitude, candidates in parentheses — every cell must be an existing
  merged kernel cell, none new):
  - (a) a **credit/debit join** — debits and credits keyed by account,
    joined (`JoinSetCell` on tagged set streams, or the keyed-map path);
  - (b) a **grouped aggregate** — per-account balance / running total
    (`GroupByCell` with a sum aggregator; `CoalescingCombineCell` for the
    scalar grand total);
  - (c) an **outer self-join** — the transfer stream outer-joined with
    itself on transfer id (`CombineLatestCell(emitOnFrontier)` — the E2-GATE
    mode; this is the pipeline that exercises the null-extension gate).
- **Observation**: each pipeline's views folded through `observeAligned`
  into composite snapshots.
- **The invariant, checked at every observed output** — every published
  composite, not only at idle:
  - the running total over all account balances is **zero** (a debit and its
    credit are never observed split);
  - no composite corresponds to a partially-applied transfer — formulate it
    as a wave-prefix property: each observed composite equals the recompute
    over some prefix of completed transfer waves (per source, in counter
    order). Assert against an oracle fold maintained by the test.
  - the outer-join pipeline never shows a null-extended row for a transfer
    whose matching leg arrived in the same wave.
- **The two documented failure controls** (the suite must have teeth —
  `22-consistency.md:322-325`):
  - the same graph observed through **`observeAll`** (the point-consistent
    `CompositeSink`) violates the zero-total invariant on at least one seed;
  - the same graph with the **ungated** outer join
    (`CombineLatestCell` default) shows an emitted-then-retracted
    null-extension on at least one seed.
  If a control never trips across the seed range, the harness is too weak to
  certify anything — tune interleaving (partial randomized draining, the
  `GlitchFreeDiamondTest` discipline) until it does.
- **Scale**: ≥ 200 seeds, ≥ 50 transfer waves per seed, per the
  repo's diamond-harness register; `SimulationController` deterministic
  scheduling; bounded waits only.

**Latitude** (yours to decide): the concrete cell composition per pipeline
(within the candidates and the merged operator suite); the oracle's
formulation of "some wave prefix" (per-source counter prefix is the decided
meaning — how you track it is yours); whether the three pipelines share one
graph or run as three seeded sub-cases; helper classes local to the test
file.

**NOT open for redesign / NOT in scope:**

- **Test-only.** No `kernel/src/main/**` change of any kind. If the suite
  exposes a genuine defect in a merged mechanism, **do not weaken the
  invariant, do not shrink the seed range, do not special-case the failing
  schedule**: pin the failing seed in a clearly-marked disabled-or-expected-
  failure case with the diagnosis in its KDoc, and report it as a finding —
  the orchestrator sizes the fix (failure policy, `../00-orchestration.md`).
  A green suite obtained by softening is a rejection ground.
- **No `concord/**` scenarios.** Corpus closure over `[22-OBS-01/02]` is a
  future concord-writer ticket (single-writer rule 3); this suite is the
  kernel-side acceptance only.
- **No `demo/**` adoption** (96 §E2.6 stays with the 96-plan), no doc/spec
  edits (the benchmark section already describes this suite; it needs no
  truing to land a test).

## Files expected to touch

- **New**: `kernel/src/test/kotlin/civictech/cell/consistency/InternalConsistencyTest.kt`
- This ticket's `**Status**:` line.

Nothing else — main source trees untouched; no generated/build output.

## Read first

- `doc/spec/20-dataflow-semantics/22-consistency.md:250-325` — §The
  observation frontier in full, especially the acceptance-benchmark
  subsection (`:314-325`) this ticket realizes.
- `doc/research/incremental-engines/04-cross-cutting-watermarks-consistency.md`
  §3 — the experiment being transcribed; §3's failure narrative is the
  controls' shape.
- `doc/spec/90-roadmap/96-incremental-engines-plan.md:247-257` — §E2.5.
- `kernel/src/main/kotlin/civictech/cell/observe/AlignedObserve.kt` — as
  merged (E2-ALIGN), plus E2-ALIGN's completion report for the builder
  surface.
- `kernel/src/main/kotlin/civictech/cell/data/op/CombineLatestCell.kt` and
  `SemiJoinCell.kt` — as merged (E2-GATE), for the `emitOnFrontier`
  constructor surface.
- `kernel/src/main/kotlin/civictech/cell/observe/Observe.kt` — `observeAll`/
  `CompositeSink`, the point-consistent control path.
- `kernel/src/test/kotlin/civictech/cell/consistency/GlitchFreeDiamondTest.kt`
  — the seeded-harness register (partial randomized draining, control
  discipline).
- `kernel/src/test/kotlin/civictech/cell/observe/AlignedObserveTest.kt` —
  E2-ALIGN's own test, whose graph-assembly idiom you can reuse.
- `kernel/src/main/kotlin/civictech/cell/data/op/` — the operator suite you
  compose (JoinSetCell, GroupByCell, CoalescingCombineCell, …), as merged
  after track A wave 9 and wave C2.
- `doc/spec/90-roadmap/99-defects-engines-plan/00-orchestration.md` — wave C3
  table and the failure policy your defect-finding path follows.
- `AGENTS.md` §"Core invariants to protect" and §"Verification".

Do not modify: anything under `kernel/src/main/**`, `concord/**`, `demo/**`,
`gen/**`, `wire/**`, `inspect/**`, `doc/spec/**`, any plan document other
than this ticket's `**Status**:` line.

## Acceptance criteria

- [ ] The suite exists and drives balanced transfers through all three
      pipeline shapes, observed via `observeAligned`, invariant checked at
      **every** published composite across ≥ 200 seeds × ≥ 50 waves.
- [ ] The zero-total invariant and the wave-prefix (no-partial-transfer)
      oracle hold on every seed; the gated outer join never shows a
      same-wave-retracted null-extension.
- [ ] Control 1 trips: `observeAll` violates the invariant on ≥ 1 seed.
- [ ] Control 2 trips: the ungated outer join emits-then-retracts on
      ≥ 1 seed.
- [ ] No main-source change; no invariant softened, no seed replaced. Any
      genuine defect found is pinned + diagnosed + reported, not absorbed.
- [ ] `./gradlew :kernel:test` green (including the new suite, or with the
      explicitly-marked pinned finding per the escape hatch above).
- [ ] `git status` shows only the claimed files.

## Verify

```bash
./gradlew :kernel:test --tests 'civictech.cell.consistency.InternalConsistencyTest'
./gradlew :kernel:test
git status --porcelain     # only the claimed files
```

(The repo-wide `./gradlew test` gate runs at checkpoint CC3.)

## Report on completion

- The concrete cell composition of each of the three pipelines, one line
  each.
- How the wave-prefix oracle is formulated, in two sentences.
- Exact seed/wave counts; which seeds each control tripped on.
- Any finding against a merged mechanism (the pinned-seed path), with the
  diagnosis — or confirmation none surfaced.
- Anything specified here you could not do, and why.
