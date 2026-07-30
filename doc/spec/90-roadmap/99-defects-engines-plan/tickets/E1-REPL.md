# E1-REPL — `OrMapCell` replication: gossip, baseline, re-origination

**Status**: Specified — not-started
**Model:** claude-opus-5 (effort xhigh) · **Escalate to:** claude-fable-5,
fresh session
**Wave:** C3 · **Branches:** ticket/e1-repl

## Context

ComputeNet is a Kotlin/JVM dataflow runtime. E1-CORE (wave C2, merged before
this ticket dispatches) landed the tagged map's local core:
`TaggedMapDelta<K, V>` (pointwise dot union, `[24-TMAP-01..04]`,
`doc/spec/20-dataflow-semantics/24-data-cells.md:234-335`) and
`OrMapCell<K, V>` in `civictech.cell.data` — single-instance, its KDoc says
so. **Read E1-CORE's merged code first, not its ticket's predictions**: the
cell's actual state layout, port names, and counter/snapshot shape are what
you extend (E1-CORE's completion report, quoted in the merge commit, names
them).

This ticket is 96 §E1.3
(`doc/spec/90-roadmap/96-incremental-engines-plan.md:104-118`): the cell
joins the mergeable class — replica gossip, pull baseline, re-origination —
making it the keyed sibling of `SetCell`'s replication story. The landed
prior art, all verified by R-ENG (2026-07-30):

- `kernel/src/main/kotlin/civictech/cell/data/Replicable.kt` — the contract:
  `outlet` + `deltaInlet`, idempotent-merge deltas, and the T05-finding-6
  caveat (`:17-26`): a non-`Scoped` delta is safe only on a
  `Total`-interest (pure replication) mesh — the gossip linker refuses to
  ship it across a partial-interest link.
- `kernel/src/main/kotlin/civictech/cell/data/SetCell.kt` — the reference
  implementation this ticket mirrors at the dot level (read-only —
  cross-track claim rule 1 forbids editing it): `deltaInlet` serve (`:45`,
  `:171-174`), `applyRemote` re-emitting **only new tag information** so
  mesh echoes terminate (`:114-130`), re-origination via
  `outlet.originate { … }` (`:129` — the C-10 rule: a re-emission is a new
  origination under the replica's own outlet epoch, tags byte-identical),
  `currentFrontier`/`sinceFilter` for the pull reply (`:138-169`), the
  `pullServe` baseline stamped `MessageContext.baseline` (`:185-203`).
- `kernel/src/main/kotlin/civictech/cell/replication/Replication.kt` and
  `SingleWriterReplication.kt` — the mesh wiring a `Replicable` joins.
- `ReBaseline` dead-source fencing: the discipline lives in
  `cell/data/delta/TagState.kt` (`deadSources` `:49-55`, `applyReBaseline`
  `:148-187`) with `UnionSetCell` as the operator-side adopter
  (`UnionSetCell.kt:56-100`) — spec §Tag continuity across epochs, restart,
  and swap (`24-data-cells.md:574+`, 93 I-22 R5). `TagState` is
  element-shaped; the OR-map needs the same discipline dot-shaped.

## Problem

An `OrMapCell` cannot yet converge across replicas: it has no `deltaInlet`
(peer deltas cannot reach it), no echo-terminating merge (a mesh would loop
or re-emit endlessly), no pull/`StateRequest` reply (a late-joining replica
cannot baseline), and no dead-source fencing (a superseded source's dots
would resurrect after a `ReBaseline`). 96 §E1.3 is the decided closure, and
it unblocks E1.4, E1.5, E1.6, and E3.3 — none of which are this ticket.

## Solution direction

Implement 96 §E1.3 exactly, mirroring `SetCell`'s replication surface at the
dot level. The decided design, not open for redesign:

- **`OrMapCell` implements `Replicable<TaggedMapDelta<K, V>>`**: a
  `deltaInlet` served with `applyRemote`.
- **`applyRemote` re-emits only new dot information** (echo termination):
  subtract already-known put-dots and del-dots per key before folding; if
  nothing new, return silently (`SetCell.kt:122`). Fold the new dots, then
  **re-originate** the novelty via `outlet.originate { propagate(…) }` — the
  C-10 rule; dot payloads byte-identical, wave identity the replica's own.
- **Pull baseline**: the `pullServe` outlet policy with
  `sinceFilter`/`currentFrontier` ported to dots — the frontier is max
  counter per dot source over `puts ∪ dels` (dels included, exactly as
  `SetCell.kt:138-169` folds `adds ∪ dels`), the reply a since-filtered
  state-as-delta stamped as a baseline, delivered only to the requester.
  Honor scope the way `SetCell` does **only if** `TaggedMapDelta` is
  `Scoped` (see latitude); otherwise the scope-absent (`null`/`Total`) reply
  path is the required surface.
- **`ReBaseline` dead-source fencing in the fold**: on a delta riding a
  `ReBaselineNotice`, apply the `TagState.deadSources` discipline dot-shaped
  — record the superseded sources, refuse their dots in every later fold, and
  translate the notice's supersession into the tombstone/carry behavior
  `applyReBaseline` implements for elements (`TagState.kt:148-187`; adopter
  shape `UnionSetCell.kt:96-100`). A superseded source's dot must never
  resurrect a key.
- **One spec truing, exact and minimal.** §Tagged maps opens "**Design
  decided, unbuilt** (closes G-23 for keyed structures; 96 §E1) — this
  section is the normative content 96 §E1.2 (`OrMapCell` core) and §E1.3
  (replication) build against" (`24-data-cells.md:236-239`). With this
  ticket merged both named items are built, so that header sentence is
  stale. Rewrite it to record the landed state — §E1.2 and §E1.3 shipped,
  `OrMapCell` and `TaggedMapDelta` in `civictech.cell.data`; §E1.4–E1.6
  (embedded mergeable values, `TaggedMapView`/`UntagCell`, demo adoption)
  remain with the 96-plan, and the `MapDelta`-untouched additive framing
  stands. **That
  sentence only** — no other hunk in the file, and nothing in §Operator
  library (E2-GATE's 20/24 claim, merged a wave earlier; the two sections
  are disjoint).

**Latitude** (yours to decide): whether `TaggedMapDelta` implements
`civictech.cell.link.Scoped` (keyed `within` by `K`, the `SetDelta` pattern
`SetDelta.kt:26-42`) — implementing it makes partial-interest meshes legal
and is recommended if cheap; if you skip it, the KDoc must state
Total-interest-mesh-only (the `Replicable.kt:17-26` caveat) and the test
suite runs Total meshes only. Internal factoring of the dot-shaped
dead-source fence (a small dot-level sibling of `TagState`, or inline in the
cell). Whether `applyRemote` and the local `put`/`remove` share one fold.

**NOT open for redesign / NOT in scope:**

- **No embedded-mergeable value mode** (96 §E1.4 — LWW-by-dot-order stays
  the only exposure), **no `TaggedMapView`/`UntagCell`** (§E1.5), **no
  demo/tiering work** (§E1.6), **no delivered-watermark integration** (E3.3
  — `SetCell`'s `DeliveredFrontier` listener surface, `SetCell.kt:67-86`, is
  explicitly *not* required here), **no context-only wire form** (95 §R10).
- **No `SetCell.kt`/`ManagedHost.kt` edits** (cross-track claim rule 1 —
  still in force through track A wave 11), no `cell/replication/**` source
  edits (you consume `Replication`; if it structurally cannot mesh this
  cell, stop and report — replan trigger, not a license), no `concord/**`,
  no `gen/**`, no `wire/**` module edits, no `demo/**`.
- **Wire compatibility preserved**: `TaggedMapDelta`'s `@SerialName` and
  registration landed in E1-CORE; this ticket adds no frame or codec change.
  (If `Scoped` is implemented it is a pure-Kotlin interface addition, not a
  wire change.)

### Test requirement

`kernel/src/test/kotlin/civictech/cell/replication/OrMapConvergenceTest.kt`,
mirroring `ReplicatedSessionTest` (the three-peer mesh harness:
`kernel/src/test/kotlin/civictech/cell/replication/ReplicatedSessionTest.kt:
23-60` — `SimulationController`-seeded peers, `Peering.loopback`, mid-run
partition of one peer and a heal) and `SetConvergenceTest`'s
control discipline:

- **Convergence invariant**: seeded runs over 3-replica meshes with
  partition/heal; at idle, `∀ replicas: membership() and every value(k)
  equal` — across ≥ 100 seeds (the `ReplicatedSessionTest` scale), with
  concurrent same-key puts on different replicas (the LWW-by-dot-order
  winner must be identical everywhere) and concurrent put/remove (add-wins,
  reset-remove) mixed into the op schedule.
- **Duplicate delivery across a diamond dedups**: a delta reaching a replica
  over two mesh paths folds once — membership, values, and re-emission
  volume all unchanged by the second arrival.
- **Replica wave-id assertion**: re-emissions carry the replica's own
  sourceId (the re-origination rule) while dot payloads stay byte-identical
  — assert both, the `SetCell` gossip contract.
- **Baseline/late join**: a replica joining after traffic converges via the
  pull baseline (tombstones included — a removed key stays removed on the
  joiner).
- **Dead-source fencing**: after a `ReBaselineNotice` supersedes a source, a
  straggler delta carrying that source's dots does not resurrect a key.
- **Divergence controls** (96 §E1.3 names both): (a) an `applyRemote`
  without the new-dots filter loops/re-emits unboundedly — assert the
  harness detects it (emission-count explosion or non-quiescence) on a
  test-local variant; (b) untagged application (the same schedule through
  `MapCell` replicas or an arrival-order fold) diverges on at least one
  seed.

Deterministic seeds; do not replace a discovered failing seed with a
friendlier one. Bounded waits and existing simulation controls only.

## Files expected to touch

- **Modified**: `kernel/src/main/kotlin/civictech/cell/data/OrMapCell.kt`
  (and `cell/data/delta/TaggedMapDelta.kt` if E1-CORE placed the delta
  there and `Scoped` is implemented).
- **New** (optional latitude): a dot-shaped dead-source fence helper beside
  the cell or in `cell/data/delta/`.
- **New**: `kernel/src/test/kotlin/civictech/cell/replication/OrMapConvergenceTest.kt`
- **Modified**: `doc/spec/20-dataflow-semantics/24-data-cells.md` — the
  §Tagged maps "Design decided, unbuilt" header sentence (`:236-239`) only.
- This ticket's `**Status**:` line.

Nothing else. No generated/build output in the diff.

## Read first

- E1-CORE's merged diff (`git log --oneline` → the E1-CORE merge; read
  `OrMapCell.kt` and the delta as they landed) and its completion report in
  the merge/plan record.
- `doc/spec/20-dataflow-semantics/24-data-cells.md:234-335` — §Tagged maps
  in full (the four laws your convergence invariant proves; decided point 2,
  dels-before-puts; the exclusions).
- `doc/spec/90-roadmap/96-incremental-engines-plan.md:104-118` — §E1.3, the
  item this ticket realizes.
- `kernel/src/main/kotlin/civictech/cell/data/SetCell.kt` — the reference:
  `applyRemote` (`:114-130`), frontier/since (`:138-169`), serve + catch-up
  + `pullServe` init block (`:171-203`). Read-only.
- `kernel/src/main/kotlin/civictech/cell/data/Replicable.kt` — the contract
  and the `Scoped` caveat driving your latitude decision.
- `kernel/src/main/kotlin/civictech/cell/replication/Replication.kt` and
  `SingleWriterReplication.kt` — the mesh your test wires.
- `kernel/src/main/kotlin/civictech/cell/data/delta/TagState.kt:49-55,
  148-187` and `kernel/src/main/kotlin/civictech/cell/data/op/UnionSetCell.kt:
  56-100` — the dead-source discipline you port to dots.
- `kernel/src/test/kotlin/civictech/cell/replication/ReplicatedSessionTest.kt`
  and `kernel/src/test/kotlin/civictech/cell/data/SetConvergenceTest.kt` —
  the harness and control registers.
- `doc/spec/20-dataflow-semantics/21-propagation.md` §Pull and
  `doc/spec/40-distribution/42-*.md` §Design as implemented (the mergeable
  class) — cited by 96 §E1.3 as the governing spec.
- `doc/spec/90-roadmap/99-defects-engines-plan/00-orchestration.md` — wave C3
  table and cross-track claim rules.
- `AGENTS.md` §"Core invariants to protect" and §"Verification".

Do not modify: `kernel/.../data/SetCell.kt`, `kernel/.../host/ManagedHost.kt`,
`cell/replication/**` sources, `cell/data/op/**`, `cell/observe/**`,
`cell/consistency/**`, `concord/**`, `gen/**`, `wire/**`, `demo/**`,
`doc/spec/**` other than the single §Tagged maps header sentence named above,
any plan document other than this ticket's `**Status**:` line.

## Acceptance criteria

- [ ] `OrMapCell` is `Replicable<TaggedMapDelta<K, V>>`; `applyRemote` folds
      only new dot information and re-originates exactly that novelty
      (`outlet.originate`), tags byte-identical, echoes terminating.
- [ ] The pull baseline works: since-filtered, tombstones included, stamped
      as a baseline to the requester only; a late-joining replica converges
      including removed keys.
- [ ] Dead-source fencing: a superseded source's dots never resurrect a key
      after a `ReBaselineNotice`.
- [ ] The convergence invariant holds on every seed (≥ 100): equal
      membership and values at idle under partition/heal, concurrent
      same-key puts, concurrent put/remove.
- [ ] Duplicate-delivery dedup and the replica wave-id assertion pass.
- [ ] Both divergence controls trip: the filter-less `applyRemote` variant
      and the untagged fold, each on at least one seed.
- [ ] The `Scoped` decision is explicit: implemented and exercised, or the
      Total-only caveat is in the KDoc and the report.
- [ ] §Tagged maps no longer says "Design decided, unbuilt"; it records
      §E1.2/§E1.3 as landed and §E1.4–E1.6 as still with the 96-plan. No
      other spec hunk. `./gradlew :concord:docLints` clean.
- [ ] `./gradlew :kernel:test` green.
- [ ] `git status` shows only the claimed files.

## Verify

```bash
./gradlew :kernel:test --tests 'civictech.cell.replication.OrMapConvergenceTest'
./gradlew :kernel:test --tests 'civictech.cell.data.OrMapCellTest'
./gradlew :kernel:test
./gradlew :concord:docLints
git status --porcelain     # only the claimed files
```

(The repo-wide `./gradlew test` gate runs at checkpoint CC3.)

## Report on completion

- The `Scoped` decision and its consequence (partial-interest legal or
  Total-only), in two sentences.
- How the dot-shaped dead-source fence is factored relative to `TagState`.
- Exact test FQNs, seed counts, and which seeds the two controls tripped on.
- Confirmation that E1-CORE's local semantics tests still pass unmodified.
- What E1.4/E1.5 (still with the 96-plan) will find as the extension seams.
- Anything specified here you could not do, and why.
