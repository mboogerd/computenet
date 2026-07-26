# Demo findings — kernel requirements discovered by the demo apps

> **Status**: living document — the demo modules (`:demo:slotfinder`, `:demo:skillmatch`,
> `:demo:tiering`) exist to showcase the incremental dataflow layer **and** to surface
> kernel gaps: bugs, developer-experience friction, missing reusable incremental data
> structures, and missing incremental operations. Every non-trivial workaround in a demo
> should leave an entry here, formatted as a candidate G-entry for
> [91-gap-analysis.md](spec/90-roadmap/91-gap-analysis.md).
>
> Entry format: **observation** (what the demo needed) → **why it's a gap** (what the
> kernel lacks) → **proposed shape** (candidate operator/mechanism).

## F-1 — No map-stream fusion / per-key combine-latest / outer join — **CLOSED**

**Closed**: promoted to kernel `CombineLatestCell` (`civictech.cell.data.op.CombineLatestCell`,
restructure RS-5.4), generalizing the `FuseCell` prototype described below.

**Observation**: two demos independently need to combine two `MapDelta` streams per key:
`:demo:tiering` fuses tier-average and preference-average maps into one score
(prototyped as the app-level `FuseCell`), and `:demo:skillmatch` compares per-pair match
counts against per-job required counts to derive qualification (computed in the hub).
**Why it's a gap**: `JoinCell` is an inner-join LWW dictionary join — a key missing from
one side vanishes from the output, but both consumers need outer semantics (an item with
only one signal, a job with zero matches). There is no kernel cell that holds the latest
value per key from N map streams and emits a combined value on any change.
**Proposed shape**: a `CombineLatestCell<K, V1, V2, R>(combine: (K, V1?, V2?) -> R?)`
over `MapDelta` streams with outer-join semantics, effective-only emission, group-death
removal when all sides drop a key, `Stateful`, and late-join catch-up — the FuseCell
prototype generalized. (Relates to the G-23 OR-map deferral in 91.)

## F-2 — No threshold / bucketing operator

**Observation**: `:demo:tiering` maps a continuous fused score to a discrete tier
(S–F by fixed cutoffs); the bucketing lives inside the app's `FuseCell`.
**Why it's a gap**: score→band quantization is a recurring incremental pattern (the
attention system quantizes to bands the same way) but has no reusable dataflow form; a
naive map-over-values cell would re-emit on every score wiggle instead of only on
band change.
**Proposed shape**: a `BucketCell<K>(thresholds: SortedMap<Double, Band>)` over
`MapDelta<K, Double>` emitting `MapDelta<K, Band>` effective-only on band transitions —
i.e. hysteresis-friendly discretization as a first-class operator.

## F-3 — Keyed re-valuation over OR-sets needs app-side bookkeeping — **CLOSED**

**Closed**: promoted to kernel `KeyedSetCell` (`civictech.cell.data.KeyedSetCell`),
the keyed-upsert set source proposed below.

**Observation**: in `:demo:tiering`, an agent re-tiering an item must remove the *old*
`Valuation` element and add the new one; the OR-set can only remove an element the app
still remembers, so the app keeps an `(agent, item) → Valuation` index purely to issue
removals. Same for pairwise preferences.
**Why it's a gap**: "latest value per key, per writer" is an upsert, not a set add —
`MapCell` has upsert semantics but its `MapDelta` output cannot feed `GroupByCell`
(which consumes `SetDelta` only), so the natural encoding is blocked and every app
re-implements remove-old-then-add.
**Proposed shape**: either a keyed-upsert set source (`KeyedSetCell<K, E>` where adding
under an existing key retracts the previous element) or `GroupByCell` over map streams.

## F-5 — Edge-of-graph view composition is not glitch-free

**Observation**: `:demo:skillmatch`'s UI folds four independent outlets (matches,
match-counts, required-counts, gap) into one state snapshot. The views update
asynchronously, so a snapshot can be momentarily inconsistent — the server test
first observed a state where a match was counted (`matched: 1`) while the gap view
still listed that same skill as uncovered. Tests must await joint conditions, and
the UI can flash contradictory panels.
**Why it's a gap**: not a kernel bug — this is exactly what `GlitchFreeCell` exists
for — but there is no ergonomic way to apply glitch-freedom at the *observation
edge* (a hub folding N outlets). Each demo hand-rolls per-view folds with no wave
alignment, so the strongest consistency machinery in the kernel is unused where
apps actually read state.
**Proposed shape**: a glitch-free multi-inlet hub/collector idiom (or a
`GlitchFree`-wrapped composite view cell) that delivers wave-aligned snapshots to
the app edge; possibly just a documented recipe over the existing `GlitchFreeCell`.

## F-4 — Fixed intersect chains: topology evolution not exercised

**Observation**: `:demo:slotfinder` hard-wires three participants because adding a
participant means splicing a new `IntersectSetCell` into the chain at runtime.
**Why it's a gap**: not a missing operator — the graph-evolution machinery (GraphSpec,
promotion swap) exists — but no demo exercises *live* topology growth on a serving
pipeline. Dynamic participants would be exactly that exercise.
**Proposed shape**: a follow-up demo iteration that adds/removes participants live via
the promotion swap window, doubling as an evolution-machinery showcase.

## F-6 — No keyed-state cell with atomic cross-key (two-key) updates

**Observation**: `:demo:backlog-triage`'s `RatingCell` (`RankingCells.kt`) hosts
pairwise rating engines (elo, trueskill) whose updates are pairwise-local by
design — one game moves only the two participants' accumulators — but are
*cross-key*: updating the winner's rating needs the loser's current
accumulator in the same atomic step, which a per-key aggregator (`GroupByCell`)
can never see. The demo works around this by hosting the whole engine as one
app-level cell over the raw preference-set stream instead of a per-key kernel
aggregator.
**Why it's a gap**: the kernel's keyed aggregators (`GroupByCell`,
`MergeableGroupByCell`) assume single-key locality; there is no reusable shape
for "read/write two keys' state atomically per input event."
**Proposed shape**: a `PairKeyedStateCell<K, S>(update: (S, S) -> Pair<S, S>)` or
similar two-key-atomic aggregator over a keyed-pair event stream, so pairwise
online algorithms (elo/trueskill-shaped) can be expressed as a reusable kernel
operator instead of a bespoke app-level engine host.

## F-7 — No N-ary `MapDelta` combine

**Observation**: `:demo:backlog-triage`'s `MetaRankCell` (`RankingCells.kt`)
Borda-combines up to seven named `MapDelta<String, Double>` rating streams
(mean, elo, bt, trueskill, glicko, wenglin, wilson) by hand-folding each named
inlet into a `LinkedHashMap` and re-publishing the combined ranking via
`MapDiffPublisher` on every upstream change.
**Why it's a gap**: `CombineLatestCell` (the F-1 promotion) is binary
(`V1, V2 -> R`); there is no kernel operator that folds an arbitrary, possibly
dynamic, number of `MapDelta` streams per key.
**Proposed shape**: an N-ary `CombineLatestCell`-family operator (or a
`combineLatest(vararg sources)` / `List<Outlet<...>>`-driven variant) that
folds per-key values across N map streams with the same outer-join,
effective-only, late-join-catch-up semantics as the binary form.

## F-8 — `MetaRankCell`'s dynamically-registered inlets are invisible to descriptor generation

**Observation** (RS-4.1 finding): `MetaRankCell` registers one `FanInlet` per
source-algorithm name from a constructor-supplied `List<String>`
(`val inlets: Map<String, FanInlet<...>> = sources.associateWith { ... registerPort(name, ...) }`,
`RankingCells.kt`). `ContractProcessor.scanPorts` walks declared properties at
compile time and cannot see ports registered inside a runtime loop, so the
generated `CellDescriptor` for `MetaRankCell` lists only its one static
`outlet` property — the seven dynamic inlets are absent.
**Why it's a gap**: this is not a bug today — the G-17 spawn-time check
(`ManagedHost.spawn`, via `ContractRegistry.cellDescriptor`) is a *subset*
check (`registeredNames ⊇ descriptorNames`), so an under-complete descriptor
with extra runtime-registered ports is legal and spawns fine. It is, however,
a standing limitation: any future consumer that treats the descriptor's
`ports` list as *complete* (e.g. cold-cell enumeration without instantiation,
per `doc/ksp-dx-catalog.md` §5c) will silently miss `MetaRankCell`'s dynamic
inlets.
**Proposed shape**: none proposed — recorded as a known ceiling of
declaration-only KSP scanning (`doc/ksp-dx-catalog.md`'s "KSP is
declaration-only" constraint), not a defect to fix.
