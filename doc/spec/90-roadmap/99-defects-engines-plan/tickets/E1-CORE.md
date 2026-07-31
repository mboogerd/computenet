# E1-CORE — `TaggedMapDelta` + `OrMapCell`: the convergent keyed structure, local core

**Status**: Implemented — awaiting review
**Model:** claude-opus-5 (effort xhigh) · **Escalate to:** claude-fable-5,
fresh session
**Wave:** C2 · **Branches:** ticket/e1-core

## Context

ComputeNet is a Kotlin/JVM dataflow runtime. E1-SPEC (merged) made the OR-map
design normative spec text: `doc/spec/20-dataflow-semantics/24-data-cells.md`
§Tagged maps (`:234-335`). That section opens "**Design decided, unbuilt**
(closes G-23 for keyed structures; 96 §E1) — this section is the normative
content 96 §E1.2 (`OrMapCell` core) and §E1.3 (replication) build against."
This ticket is §E1.2 — the local core. Replication is E1-REPL (wave C3);
embedded mergeable values, adoption seams, and the demo proof (96 §E1.4–E1.6)
stay with the 96-plan.

The R-ENG checkpoint verified (2026-07-30): no `OrMapCell`, `TaggedMapDelta`,
or tagged-map-shaped file exists anywhere under `kernel/` — this is genuinely
greenfield, built on landed substrate:

- `kernel/src/main/kotlin/civictech/cell/data/SetCell.kt` — the element-level
  observed-remove idiom the spec says this design lifts "one level, from a
  live/tombstoned tag per element to a live/tombstoned **dot** per key"
  (`24-data-cells.md:248-252`): derived replay-stable tag source + counter
  (`:55-65`), tombstoned dels, `catchUpOnLinked` delta-from-empty with
  tombstones (`:175-184`), counter in `snapshot()` (`:205-209`). **Read-only
  prior art — `SetCell.kt` must not be edited** (cross-track claim rule 1).
- `kernel/src/main/kotlin/civictech/cell/data/KeyedSetCell.kt` — per-key
  observed-remove with atomic retract+add: "a [put] over an existing key
  ships the previous element's retraction and the new element's add in ONE
  [SetDelta]" (`:41-51`).
- `kernel/src/main/kotlin/civictech/cell/data/MapCell.kt:14-19` — the
  existing `@Contract interface MapOps<K, V> { put; remove }`, reused as-is:
  **no new contract interface, no `gen/` descriptor work** (96 §E1.2).
- `kernel/src/main/kotlin/civictech/cell/data/delta/SetDelta.kt:14-24` — the
  delta-type register: `@kotlinx.serialization.Serializable` +
  `@SerialName("SetDelta")`, `java.io.Serializable`, `MergeablePayload`.
- `kernel/src/main/kotlin/civictech/cell/wire/WireCodec.kt:142` — where a
  payload class registers polymorphically (`subclass(SetDelta::class, …)`);
  the additive-encoding seam 96 §E1.2 names ("new `@SerialName` payload
  registered beside `SetDelta`; no frame-type change").

## Problem

`MapDelta` and its cells are untagged and arrival-order: "concurrent same-key
puts resolve by arrival order; single-writer-per-key or single-stream inputs
converge" (`MapCell`/`JoinCell`/`CombineLatestCell` KDocs, G-23). There is no
keyed structure whose per-key **value** converges under concurrent
multi-writer puts and removes. The spec now defines one — the tagged map,
with four normative laws (`24-data-cells.md:269-289`):

- `[24-TMAP-01]` merge (pointwise dot union) SHALL be commutative,
  associative, and idempotent.
- `[24-TMAP-02]` a key SHALL be present iff it has at least one live dot —
  add-wins.
- `[24-TMAP-03]` a key's exposed value SHALL be the value of its live dot
  with the greatest `(counter, sourceId)` order, and MUST NOT be selected by
  wall-clock time.
- `[24-TMAP-04]` a `remove(k)` SHALL tombstone every dot observed live at `k`
  at the time of the remove, such that a concurrent put's dot survives the
  merge (reset-remove).

Nothing in the kernel implements them.

## Solution direction

Implement 96 §E1.2 (`doc/spec/90-roadmap/96-incremental-engines-plan.md:
82-102`) exactly, against the merged spec section. The decided design, not
open for redesign:

- **`TaggedMapDelta<K, V>`** per the spec's sketch (`24-data-cells.md:
  254-262`): `puts: Map<K, Map<Timestamp, V>>` (live dots carrying values),
  `dels: Map<K, Set<Timestamp>>` (tombstoned observed-remove dots),
  `merge(other)` = pointwise dot union. One causal namespace for the whole
  map (decided point 1, `:293-295`); tombstoned dels subsume deferred context
  ops (point 2, `:296-302` — a remove's dots arriving before their put sit in
  `dels` and cover the put on arrival, `SetCell.applyRemote`'s behavior
  lifted to dots). Register: `@kotlinx.serialization.Serializable`
  `@SerialName("TaggedMapDelta")`, `java.io.Serializable`, `MergeablePayload`
  (`mergeWith` casting like `SetDelta.kt:24`), registered in
  `WireCodec.kt` beside `SetDelta` (`:142` pattern) — additive, no
  frame-type change, wire compatibility preserved.
- **`OrMapCell<K, V>`** in `civictech.cell.data`: a `@CellBase` API with
  `inlet: Use<MapOps<K, V>>` (the existing contract, `MapCell.kt:14-19`) and
  `outlet: Subscribe<Propagate<TaggedMapDelta<K, V>>>`; `Stateful`.
  - **Local `put(k, v)`** mints a dot from the derived replay-stable source —
    `UUID.nameUUIDFromBytes("or-map-tags:${ref.id}:${ref.instanceId}")`,
    counter in snapshot (the `SetCell`/`MintedTags` M10.1/M10.2 pattern,
    `SetCell.kt:55-65`, `MintedTags.kt:16-24`) — and tombstones the key's
    previously-observed live dots **in the same delta** (`KeyedSetCell`'s
    atomic retract+add lifted to dots): never two live local values, never a
    windowed zero.
  - **`remove(k)`** tombstones observed live dots only; effective-only no-op
    when none (`SetCell`'s remove, `:105-111`).
  - **Reads**: `membership(): Set<K>` per `[24-TMAP-02]` and `value(k): V?`
    per `[24-TMAP-03]` (dot order `(counter, sourceId)`, never wall clock).
  - **`onLinked` catch-up** as delta-from-empty, tombstones included
    (`SetCell.kt:175-184`; G-22 / `[24-CATCHUP-01]`).
  - **`snapshot()`/`restore()`** including the dot counter (M10.2 —
    `SetCell.kt:205-209`; a restored instance never re-mints a spent dot).
- **Compact dot encoding is a codec-layer concern** (decided point 4,
  `24-data-cells.md:312-316`): group dots by `sourceId` in the serialized
  form (a custom `KSerializer` or serializable surrogate), never in the
  delta's merge semantics. A round-trip test through `WireCodec` proves
  encode/decode identity.
- **The Lasp determinism caveat is normative for the KDoc** (point 5,
  `:317-325`): document that tag-precise removes keep value-keyed derivation
  deterministic, and that an operator deriving from `value(k)` inherits the
  caveat.

**Latitude** (yours to decide): whether `TaggedMapDelta` lives in
`cell/data/delta/TaggedMapDelta.kt` (the package convention — sibling of
`SetDelta.kt`) or inside `OrMapCell.kt` (96's literal wording); the compact
encoding mechanism (custom serializer vs surrogate — measured only by the
round-trip test and the grouped-by-source shape); internal state layout
(per-key dot maps vs a flat dot index); whether `membership`/`value` also
surface as a small read-view helper; exposing multi-value reads is **not**
required here (that is E1.4's `values(k)`).

**NOT open for redesign / NOT in scope:**

- **No replication.** No `Replicable`, no `deltaInlet`, no `applyRemote`, no
  pull/`StateRequest` handler, no re-origination, no `ReBaseline` fencing —
  all E1-REPL (wave C3). This cell is single-instance until then; say so in
  the KDoc.
- **No embedded-mergeable value mode** (`V : MergeablePayload` folding — 96
  §E1.4), **no `TaggedMapView`/`UntagCell`/join adapters** (§E1.5), **no
  demo work** (§E1.6), **no context-only (tombstone-free) wire form** (95
  §R10; excluded by the spec, `24-data-cells.md:327-335`).
- **No `gen/` changes.** `MapOps` is already a `@Contract`; `@CellBase` is
  data-driven. If the generator genuinely cannot express this cell, stop and
  report — that is a replan trigger, not a license to edit `gen/`.
- **No edits to `SetCell.kt`, `ManagedHost.kt`** (cross-track claim rule 1),
  **`MapCell.kt`, `KeyedSetCell.kt`, `ListCell.kt`, `Watermark.kt`,
  `InstanceSet.kt`, `ShardCell.kt`** (track A `V1C-CELLS`, merged just before
  this wave — do not disturb its landed diff), or anything under
  `cell/data/op/**` (`V1C-OPS`, ditto). Additive only: new cell, new delta,
  one `WireCodec` registration line, new test files.
- **No `Scoped` implementation** on the delta (partial-interest slicing —
  `Replicable.kt:17-26`); it becomes relevant with E1-REPL's mesh work and
  is decided there.

### Test requirement

`kernel/src/test/kotlin/civictech/cell/data/OrMapCellTest.kt`, property-style
in the register of `SetCellTest`/`SetConvergenceTest`'s interleaving runs
(`SetConvergenceTest.kt:43-113` — seeded schedules, a control proving the
harness detects divergence):

- **Convergence of the delta algebra** (`[24-TMAP-01]`): any interleaving,
  duplication, and reordering of a fixed dot set — applied via `merge` —
  yields identical membership and per-key values, over seeded permutations.
- **Add-wins presence** (`[24-TMAP-02]`) and **reset-remove**
  (`[24-TMAP-04]`): a remove tombstoning observed dots concurrent with a put
  minting an unobserved dot → the key survives with the put's value.
- **Dot-order value** (`[24-TMAP-03]`): the exposed value is the max
  `(counter, sourceId)` live dot's value regardless of application order.
- **Re-put atomicity**: one delta per local `put` over an existing key —
  downstream folds never observe two live values for the key, never zero
  (the `KeyedSetCell` invariant, lifted).
- **Divergence control**: the same concurrent schedule driven through
  `MapCell`/`MapView` order-flips (arrival-order LWW) on at least one seed —
  proving the harness distinguishes the tagged map from the untagged one.
- **Catch-up and snapshot/restore**: late-join catch-up equals current state
  including tombstone coverage; snapshot → restore → the counter continues
  (no dot reuse — assert a post-restore put mints a fresh dot).
- **Wire round-trip**: `TaggedMapDelta` through `WireCodec`
  encode/decode is identity, and the encoded form groups dots by source
  (assert the surrogate/serializer shape, not byte-for-byte output).

Deterministic seeds; do not replace a discovered failing seed with a
friendlier one.

## Files expected to touch

- **New**: `kernel/src/main/kotlin/civictech/cell/data/OrMapCell.kt`
- **New** (or folded into the above — your latitude):
  `kernel/src/main/kotlin/civictech/cell/data/delta/TaggedMapDelta.kt`
- **Modified**: `kernel/src/main/kotlin/civictech/cell/wire/WireCodec.kt` —
  the one polymorphic registration, beside `SetDelta` (`:142`).
- **New**: `kernel/src/test/kotlin/civictech/cell/data/OrMapCellTest.kt`
- This ticket's `**Status**:` line.

Nothing else. No `gen/**`, no `concord/**`, no `doc/spec/**` edits (the spec
section is already written and normative; this ticket makes it true, not
different), no generated/build output in the diff.

## Read first

- `doc/spec/20-dataflow-semantics/24-data-cells.md:234-335` — §Tagged maps
  in full: the delta sketch, the four laws, all five decided points, the
  exclusions. This is the ticket's authority.
- `doc/spec/90-roadmap/96-incremental-engines-plan.md:82-102` — §E1.2, the
  item this ticket realizes (note `:104-118` §E1.3 to see what you must NOT
  build).
- `kernel/src/main/kotlin/civictech/cell/data/SetCell.kt` — in full: the
  idiom being lifted (tag minting `:55-65`, effective-only remove `:105-111`,
  catch-up `:175-184`, snapshot discipline `:205-209`). Read-only.
- `kernel/src/main/kotlin/civictech/cell/data/KeyedSetCell.kt` — the atomic
  retract+add per key (`:41-51`, `:77-81`) whose shape `put` lifts to dots.
- `kernel/src/main/kotlin/civictech/cell/data/delta/SetDelta.kt` and
  `MintedTags.kt` — delta register and derived-source pattern.
- `kernel/src/main/kotlin/civictech/cell/data/MapCell.kt` — the `MapOps`
  contract you reuse; `MapDelta`'s documented arrival-order limit your
  control test exhibits.
- `kernel/src/main/kotlin/civictech/cell/wire/WireCodec.kt:130-160` — the
  polymorphic payload registration seam.
- `kernel/src/test/kotlin/civictech/cell/data/SetCellTest.kt` and
  `SetConvergenceTest.kt` — the test register to mirror.
- `doc/spec/90-roadmap/99-defects-engines-plan/00-orchestration.md` — wave C2
  table and cross-track claim rules.
- `AGENTS.md` §"Core invariants to protect" and §"Verification".

Do not modify: `kernel/.../data/SetCell.kt`, `kernel/.../host/ManagedHost.kt`,
`kernel/.../data/{MapCell,KeyedSetCell,ListCell,Watermark}.kt`,
`cell/data/op/**`, `cell/replication/**`, `cell/observe/**`, `gen/**`,
`concord/**`, `wire/**` (the module), `demo/**`, `doc/spec/**`, any plan
document other than this ticket's `**Status**:` line.

## Acceptance criteria

- [ ] `TaggedMapDelta<K, V>` exists with pointwise-dot-union merge proven
      commutative, associative, idempotent by the interleaving tests
      (`[24-TMAP-01]`); registered additively in `WireCodec` under
      `@SerialName("TaggedMapDelta")`; wire round-trip is identity with
      dots grouped by source in the encoded form.
- [ ] `OrMapCell<K, V>` exposes the reused `MapOps` inlet and a
      `TaggedMapDelta` outlet; local `put` mints a derived-source dot and
      tombstones previously-observed dots in the same delta; `remove` is
      tag-precise and effective-only; `membership()`/`value(k)` satisfy
      `[24-TMAP-02]`/`[24-TMAP-03]` (no wall clock anywhere).
- [ ] Reset-remove semantics hold under concurrency (`[24-TMAP-04]`): the
      concurrent-put dot survives a remove that did not observe it.
- [ ] Late-join catch-up ships full state incl. tombstones; snapshot/restore
      includes the counter and a restored instance never re-mints a spent
      dot.
- [ ] The `MapCell` divergence control flips on at least one seed.
- [ ] No replication surface exists on the cell (E1-REPL's seam is clean):
      no `deltaInlet`, no pull handler; the KDoc states single-instance
      until E1-REPL.
- [ ] `./gradlew :kernel:test` green; `./gradlew :gen:test` untouched-green.
- [ ] `git status` shows only the claimed files.

## Verify

```bash
./gradlew :kernel:test --tests 'civictech.cell.data.OrMapCellTest'
./gradlew :kernel:test
git status --porcelain     # only the claimed files
```

(The repo-wide `./gradlew test` gate runs at checkpoint CC2 before the wave's
merges close — run it yourself if time permits.)

## Report on completion

- Where `TaggedMapDelta` lives and the compact-encoding mechanism chosen, in
  two sentences.
- The exact test FQNs run and seed counts; which seed(s) the `MapCell`
  control flipped on.
- The exact cell surface E1-REPL will extend: constructor shape, port names,
  how dots/tombstones are stored internally (E1-REPL adds `applyRemote`
  against that state), and the counter/snapshot layout.
- Anything specified here you could not do, and why.
