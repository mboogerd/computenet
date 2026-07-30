# E2-GATE — close the absorb-ack residual and add opt-in frontier-gated emission to the non-monotone operators

**Status**: Specified — not-started
**Model:** claude-opus-5 (effort xhigh) · **Escalate to:** claude-fable-5,
fresh session
**Wave:** C2 · **Branches:** ticket/e2-gate

## Context

ComputeNet is a Kotlin/JVM dataflow runtime. E2-SPEC (merged) made two things
normative that this ticket implements. Both live in the operator suite under
`kernel/src/main/kotlin/civictech/cell/data/op/`, which this wave is the
first to touch since track A's `V1C-OPS` (wave 9) merged — read the merged
state, not a remembered one.

**First, the absorb-ack MUST and its one flagged divergence**
(`doc/spec/20-dataflow-semantics/22-consistency.md` §Completeness over silent
or stuck edges, `:198-233`):

> An operator cell whose waved input yields no effective output MUST advance
> downstream watermarks via a metadata-plane `Progress(sourceId, thru)`
> absorb-ack, riding the exact wave it would otherwise have silently
> swallowed.

The helper (`cell.control.absorbAck`,
`kernel/src/main/kotlin/civictech/cell/control/AbsorbAck.kt:25-31`, CP-A3)
and the `emitOrAbsorb` shape (`data/op/Emit.kt:19`) are landed and adopted
across eleven operators (`22-consistency.md:214-218`). The spec flags the
residual explicitly (`:219-221`): *"One absorbing operator does not yet
satisfy this MUST: `cell.data.op.CombineLatestCell` drops an
effective-only-silent wave without an ack. A known divergence, not an
exemption — the rule binds it."* The G-40 residual paragraph repeats it
(`:246-248`). R-ENG verified the mechanism (2026-07-30):
`CombineLatestCell.emitChanges` is
`publisher.publish(touched, ::recompute)?.let { outlet.call.propagate(it) }`
(`CombineLatestCell.kt:75-77` as of `main @ 6459c5b`; V1C-OPS will have
shifted the line — the quoted expression is the anchor) — when the
value-equal diff is empty,
`publish` returns null and nothing rides the outlet, no ack. **The same shape
exists, unflagged, in `LookupJoinCell`** (`LookupJoinCell.kt:117`, same
`MapDiffPublisher` fold) — the MUST is ubiquitous and binds it identically;
96 §E2.2 names both as the value-equal-swallow adopters
(`96-incremental-engines-plan.md:203-204`).

**Second, the opt-in `emitOnFrontier` gate**
(`doc/spec/20-dataflow-semantics/24-data-cells.md`, spec-ahead-of-code):

> `[24-OP-SEMIJOIN-04]` WHERE `emitOnFrontier` gating is enabled, a
> `SemiJoinCell` antijoin's output SHALL emit only at wave completeness,
> coalesced to the wave's net minted enter/exit set, such that a transient
> enter-then-exit within one wave is never observed on the outlet
> (`:184-197`).

and the mirrored `CombineLatestCell` clause (`:198-215`): a null-extension is
an absence assertion (the internal-consistency essay's exact outer-join
failure); gated, it emits only once the wave has settled, so a same-wave
retraction never reaches the outlet. Both carry *(Spec-ahead-of-code … 96
§E2.4. Today every `SemiJoinCell` runs the ungated default.)* markers
(`:195-197`, `:212-215`) that this ticket trues.

The completeness machinery to gate on is landed: `WaveFrontier`
(`cell/consistency/WaveFrontier.kt`, CP-A4) and the D-COMBINE precedent for
mirroring its fold at cell scope
(`cell/data/op/CoalescingCombineCell.kt:69-95` — why composing the installed
policy is structurally unavailable to a coalescing operator: released
invocations arrive individually, and `ProtocolSupport` keeps one `Progress`
handler per inlet, already taken).

## Problem

1. **A downstream frontier spanning a `CombineLatestCell` or `LookupJoinCell`
   arm can stall forever on the last wave.** A waved input whose recompute
   leaves every touched key's combined value unchanged emits nothing and acks
   nothing; a downstream glitch-free join (or the E2-ALIGN aligned sink, wave
   C1) can then only settle that arm from a *later* real change — the G-40
   family failure the MUST exists to prevent.
2. **The non-monotone operators flicker within a wave.** `SemiJoinCell`'s own
   KDoc: "Not glitch-free — opposing in-flight updates may flicker
   transiently" (`SemiJoinCell.kt:33-36`). An antijoin row can enter and exit
   within one wave; a `CombineLatestCell` null-extension can be emitted and
   retracted within one wave as the other side's value arrives. Downstream
   wrappers replay both complete one-edge waves faithfully (the D-COMBINE
   lesson) — remediation must happen at the operator, before emission.

## Solution direction

Two parts, one ticket, one owner of `cell/data/op/**` for the wave.

### Part 1 — the absorb-ack residual (96 §E2.2's genuine remainder)

Bring `CombineLatestCell` and `LookupJoinCell` under the MUST: at the end of
each waved reactive handler, when the wave produced no outlet emission
(publisher returned null — the value-equal swallow), call
`outlet.absorbAck()` — the `emitOrAbsorb` shape every sibling uses
(`Emit.kt:19`; e.g. `CoalescingCombineCell.kt:210-217`). `absorbAck` itself
already skips baseline/unwaved contexts (`AbsorbAck.kt:26-28`), so catch-up
paths need no special-casing — but verify against each cell's `onLinked`
catch-up path and say so in the test.

Then true the spec, minimally: in `22-consistency.md`, delete the
`:219-221` italic divergence flag, add both cells to the adopted list
(`:214-218`), and drop the "bring `cell.data.op.CombineLatestCell` … under
the rule" clause from the G-40 residual (`:246-248`). In
`24-data-cells.md:212-215`, drop the "does not yet absorb-ack" sentence.
No other spec hunks: §Completeness and the 20/24 operator rows are this
ticket's spec claim (E2-ALIGN, possibly still in flight from wave C1, owns
20/22 §The observation frontier — if both branches touch
`22-consistency.md`, the later merger rebases; the sections are disjoint).

### Part 2 — `emitOnFrontier` (96 §E2.4)

An **opt-in** constructor mode on `SemiJoinCell` and on `CombineLatestCell`'s
null-extension path, built on the mirrored completeness fold:

- **Buffer the wave's input deltas across both inlets** (`left`/`right`),
  keyed by `Timestamp`; track edges/floors/watermarks/flushed-high-water
  across both inlets' `Consume` edges and fold `Protocols.Progress` acks —
  the `CoalescingCombineCell` mirror (`:119-267`, including `noteAbsorbed`
  `:219-232` and the WAIT-shape exclusions `:89-95`). `WaveFrontier` itself
  is not modified.
- **At wave completeness, reconcile and emit the wave's net effect as one
  delta under the wave's own identity** (emission inside the buffered
  context — `CoalescingCombineCell.emit`, `:269-287`):
  - `SemiJoinCell`: the net minted enter/exit set — a transient
    enter-then-exit within the wave cancels **before** any tag is minted on
    the outlet. Mind the `MintedTags` hygiene contract
    (`data/delta/MintedTags.kt:8-19`, `[24-OP-SEMIJOIN-02]`): reconcile on
    membership first, then mint/exit tags only for the net change, so no tag
    that never reached the wire is ever tombstoned and re-entry stays live
    under tombstone-folding consumers.
  - `CombineLatestCell`: recompute the touched keys once, against both
    sides' settled state — a null-extension for a key whose other side
    arrived in the same wave is never emitted; a genuinely one-sided key
    still emits its null-extension at completeness (outer semantics
    unchanged, only the timing gates).
  - Effective-only + absorb-ack still hold: a completed wave with zero net
    change absorb-acks instead of emitting (part 1's machinery).
- **Default stays ungated, byte-identical.** `emitOnFrontier = false` (the
  default) must leave every existing behavior and test untouched — the gate
  is `WHERE`-scoped in the spec, and the research verdict is normative:
  *not* a smarter convergent cell; absence-based emission is non-monotone
  and sealing is unavoidable (`24-data-cells.md:179-194`, 96 §E2.4).
- True the two *(Spec-ahead-of-code …)* markers in `24-data-cells.md`
  (`:195-197`, `:212-215`) to record the landed opt-in.

**Latitude** (yours to decide): the constructor surface (a boolean, an enum,
or a companion factory — discoverable and documented is the bar); whether the
mirrored fold is a private helper class shared by both cells inside
`cell/data/op/` (recommended over two copies — but it stays in `data/op`,
not promoted into `cell.consistency`); whether `LookupJoinCell` also gains
the gate (not required — its output is not an absence assertion per key in
the same way; part 1's ack is required, the gate is optional and only if
free); `Stateful` treatment of the transient wave buffer (drop on
deactivate, the `CoalescingCombineCell.onDeactivate` rule `:289-297`).

**NOT open for redesign / NOT in scope:**

- **No modification of `WaveFrontier.kt`, `GlitchFree.kt`, `AbsorbAck.kt`,
  `FanInlet`, `ProtocolSupport`** — mirror, don't extend.
- **No changes to the aligned sink** (`cell/observe/**` is E2-ALIGN's, wave
  C1) and **no balanced-transfer suite** (E2-SUITE, wave C3 — it consumes
  this ticket's gate).
- **No default-behavior change to any operator.** Ungated paths byte-
  identical; every existing `kernel` test passes unmodified (if one asserts
  the value-equal swallow emits *nothing at all* on the `Progress` lane,
  that assertion may be updated — it asserts the defect; say so in the
  report).
- **No edits to `ManagedHost.kt`/`SetCell.kt`** (cross-track claim rule 1),
  no `concord/**`, no `gen/**`, no `wire/**`, no `demo/**`. `CTL-GF-01`
  and `CTL-GOLDEN-01` remain deliberately-failing controls — nothing here
  touches concord.

### Test requirement

New tests under `kernel/src/test/kotlin/civictech/cell/data/op/` (extend
`OperatorAbsorbAckTest.kt` for part 1 if that reads better — it is the
established register: a source → operator → downstream glitch-free join
diamond that must settle a swallowed wave, `:80-161`):

- **Part 1, per cell** (`CombineLatestCell`, `LookupJoinCell`): a waved
  input producing a value-equal (empty-diff) recompute advances a
  downstream frontier — the join/aligned observer settles the wave without
  waiting for a later real change. Control: assert the pre-fix stall shape
  is what the harness detects (a downstream frontier that does *not* settle
  when the ack is withheld — a test-local ack-less variant or a
  before/after structure, mirroring `OperatorAbsorbAckTest`'s pattern).
- **Part 2, `SemiJoinCell`**: seeded opposing-update schedules through a
  shared-source diamond (both inlets fed from one source's fork — the
  `GlitchFreeDiamondTest` harness register); invariant across ≥ 200 seeds:
  the outlet never shows an enter-then-exit for one row within one wave, and
  the net emission's tags obey `MintedTags` hygiene (no tombstone for a tag
  never advertised). Control: the ungated default flickers on at least one
  seed of the same range.
- **Part 2, `CombineLatestCell`**: a key whose left and right values arrive
  in the same wave never has a null-extended row emitted then retracted;
  ungated control shows the emit-then-retract on at least one seed. A
  genuinely one-sided key still null-extends at completeness (outer
  semantics preserved).
- **Liveness**: gated cells settle at `runToIdle` — no wave left buffered,
  including a wave one inlet absorbs entirely (settled by the part-1 ack of
  an upstream, or an `EdgeClose`).

Deterministic seeds; do not replace a discovered failing seed with a
friendlier one.

## Files expected to touch

- **Modified**: `kernel/src/main/kotlin/civictech/cell/data/op/CombineLatestCell.kt`
- **Modified**: `kernel/src/main/kotlin/civictech/cell/data/op/LookupJoinCell.kt`
- **Modified**: `kernel/src/main/kotlin/civictech/cell/data/op/SemiJoinCell.kt`
- **New** (optional, your latitude): a shared gating-fold helper file under
  `kernel/src/main/kotlin/civictech/cell/data/op/`
- **Modified**: `doc/spec/20-dataflow-semantics/22-consistency.md` — the
  divergence flag, the adopted list, the G-40 residual clause; nothing else.
- **Modified**: `doc/spec/20-dataflow-semantics/24-data-cells.md` — the two
  spec-ahead-of-code markers and the "does not yet absorb-ack" sentence;
  nothing else.
- **New/Modified tests**: `kernel/src/test/kotlin/civictech/cell/data/op/`
  (extending `OperatorAbsorbAckTest.kt` and/or new
  `FrontierGatedEmissionTest.kt`), possibly
  `CombineLatestCellTest.kt`/`SemiJoinCellTest.kt`/`LookupJoinCellTest.kt`
  additions in `kernel/src/test/kotlin/civictech/cell/data/`.
- This ticket's `**Status**:` line.

No generated/build output in the diff.

## Read first

- `doc/spec/20-dataflow-semantics/22-consistency.md:175-248` — §Completeness
  over silent or stuck edges in full: the MUST, the adopted list, the flagged
  divergence, the G-40 residual you narrow.
- `doc/spec/20-dataflow-semantics/24-data-cells.md:158-215` — the
  `SemiJoinCell` and `CombineLatestCell` rows: `[24-OP-SEMIJOIN-01..04]`, the
  CALM/absence-assertion rationale, the gating clauses, both
  spec-ahead-of-code markers.
- `doc/spec/90-roadmap/96-incremental-engines-plan.md:197-245` — §E2.2 and
  §E2.4 (note: §E2.2's helper/adoption core is landed; only the two
  value-equal-swallow cells remain — R-ENG verified).
- `kernel/src/main/kotlin/civictech/cell/data/op/CombineLatestCell.kt`,
  `LookupJoinCell.kt`, `SemiJoinCell.kt` — **as merged after track A wave 9**
  (V1C-OPS touched this package; read the current state, including any
  `BoundedStateful` surface it added, and leave that surface intact).
- `kernel/src/main/kotlin/civictech/cell/data/op/CoalescingCombineCell.kt` —
  the mirror-the-fold precedent: rationale `:69-95`, state `:119-156`,
  `noteAbsorbed` `:219-232`, context-carrying emission `:269-287`.
- `kernel/src/main/kotlin/civictech/cell/data/op/Emit.kt` and
  `cell/control/AbsorbAck.kt` — the emit-or-absorb shape and the ack's
  baseline/link guards.
- `kernel/src/main/kotlin/civictech/cell/data/delta/MintedTags.kt` and
  `SemiJoinCell`'s ledger use — the tag-hygiene contract your net emission
  must respect.
- `kernel/src/test/kotlin/civictech/cell/data/op/OperatorAbsorbAckTest.kt`
  and `kernel/src/test/kotlin/civictech/cell/consistency/GlitchFreeDiamondTest.kt`
  — the two test registers.
- `doc/spec/90-roadmap/99-defects-engines-plan/00-orchestration.md` — wave C2
  table, cross-track claim rules (incl. the spec-file seam split with
  E2-ALIGN).
- `AGENTS.md` §"Core invariants to protect" and §"Verification".

Do not modify: `cell/consistency/**`, `cell/control/**`, `cell/port/**`,
`cell/observe/**`, `kernel/.../host/ManagedHost.kt`,
`kernel/.../data/SetCell.kt`, `cell/data/op/` files other than the three
named (+ your optional helper), `concord/**`, `gen/**`, `wire/**`, `demo/**`,
any plan document other than this ticket's `**Status**:` line.

## Acceptance criteria

- [ ] `CombineLatestCell` and `LookupJoinCell` absorb-ack every waved
      reactive input whose recompute yields no effective output; the
      per-cell part-1 tests prove a downstream frontier settles.
- [ ] `22-consistency.md` no longer flags a divergence; both cells are in
      the adopted list; the G-40 residual no longer names
      `CombineLatestCell`. `24-data-cells.md` no longer says
      "does not yet absorb-ack". No other spec hunks.
- [ ] `SemiJoinCell(emitOnFrontier)` satisfies `[24-OP-SEMIJOIN-04]`: only
      at wave completeness, net enter/exit, no within-wave flicker on any of
      ≥ 200 seeds; minted-tag hygiene preserved (no never-advertised tag
      tombstoned).
- [ ] `CombineLatestCell(emitOnFrontier)` never emits a null-extension
      retracted within the same wave; genuinely one-sided keys still
      null-extend at completeness.
- [ ] Both ungated controls flicker/emit-then-retract on at least one seed —
      the harness has teeth.
- [ ] Defaults are byte-identical: every pre-existing kernel test passes
      unmodified (any test asserting the pre-fix silence is updated with a
      note in the report).
- [ ] Gated cells are live at `runToIdle` — no permanently buffered wave.
- [ ] `WaveFrontier.kt`, `GlitchFree.kt`, `AbsorbAck.kt` untouched.
- [ ] `./gradlew :kernel:test` green; `./gradlew :concord:docLints` clean;
      `./gradlew :concord:test -Pconcord.profiles=core` green (the corpus
      binds these operators; the deliberately-failing controls must still
      report PASSED under their own inverted expectation).
- [ ] `git status` shows only the claimed files.

## Verify

```bash
./gradlew :kernel:test --tests 'civictech.cell.data.op.OperatorAbsorbAckTest'
./gradlew :kernel:test --tests 'civictech.cell.data.op.FrontierGatedEmissionTest'
./gradlew :kernel:test
./gradlew :concord:test -Pconcord.profiles=core
./gradlew :concord:docLints
git status --porcelain     # only the claimed files
```

(Adjust FQNs to the names you use; state the exact FQNs in the report. The
repo-wide `./gradlew test` gate runs at checkpoint CC2.)

## Report on completion

- How the gating fold is factored (shared helper vs per-cell; what was
  mirrored, what omitted) in three sentences.
- Exact test FQNs, seed counts, and which seeds each ungated control tripped.
- How `SemiJoinCell`'s net reconciliation interacts with `MintedTags` (the
  mint-at-net decision), in two sentences.
- Whether `LookupJoinCell` gained the optional gate or only the ack.
- Any V1C-OPS surface (`BoundedStateful` etc.) encountered in the three
  cells and confirmation it is intact.
- The exact constructor surface for `emitOnFrontier` — E2-SUITE (wave C3)
  codes against it.
- Anything specified here you could not do, and why.
