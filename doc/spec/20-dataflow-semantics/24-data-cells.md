# 24 — Standard Data Cells, Merge Semantics, Partitioning

> **Status**: Partial (set family tagged and convergent; counters implemented incl. replicable PN form; map/list with documented limits; partitioning unbuilt)
> **Sources**: ADR 1 (§3, §5, §14), ADR — Cellular Software Development Process (incremental dataflow layer; LASP/Differential Dataflow inspirations)
> **Implementation**: `civictech.cell.data`: `SetCell`, `UnionSetCell`, `CounterCell`, `PnCounterCell`, `MapCell`, `ListCell`, `Propagate`

## Role

Data cells are the standard library of the incremental dataflow layer:
stateful cells whose contracts are operations + delta streams, composing via
operators (union, intersect, map, …) into incrementally-maintained derived
state. They are ordinary cells — no kernel privileges.

## Established pattern (normative template)

`SetCell` is the reference shape:

```kotlin
interface SetOps<E> { fun add(element: E); fun remove(element: E) }
data class SetDelta<E>(               // observed-remove tags (G-23)
    val adds: Map<E, Set<Timestamp>>, // every add mints a unique tag
    val dels: Map<E, Set<Timestamp>>, // a remove carries the tags it observed
) : Serializable {
    fun merge(other: SetDelta<E>): SetDelta<E>  // tag-set union
}
interface SetApi<E> {
    val inlet: Use<SetOps<E>>                       // commands in
    val outlet: Subscribe<Propagate<SetDelta<E>>>   // deltas out
}
```

*(M5.2: every delta type is `@kotlinx.serialization.Serializable` with a
stable `@SerialName` (`SetDelta`, `CounterDelta`, `MapDelta`, `ListDelta`) —
deltas cross the wire as polymorphic values in the `WireFrame` envelope
(40/41), tags included, so causal merge semantics hold across processes.)*

Elements of the pattern:

1. **Command contract** on the inlet (semantic operations, not raw deltas) —
   the cell derives effective deltas from owned state.
2. **Delta contract** on the outlet; emissions are effective-only (21) —
   removing an unobserved element is a no-op.
3. **Merge on the delta type is commutative, associative, idempotent** —
   tag-set union — so membership converges regardless of arrival order.
   Add-wins is not a configured bias but a consequence: a concurrent add's
   tag is never observed by the remove. Tags are `Timestamp`s minted
   cell-locally (unique per add instance — see 22 for why wave ids are not
   reused). This is the CRDT-style ingredient for decentralized replication
   (40/42) without imposing CRDTs everywhere.
4. **Derived cells consume delta contracts**: `UnionSetCell` tracks live
   tags per element, forwards only new tag information (duplicate deliveries
   across diamond fan-ins dedup), and any consumer derives membership from
   the forwarded tag algebra. `CounterCell` (`increment`/`decrement` →
   `CounterDelta`) is commutative by construction: merge is addition —
   commutative but **not idempotent**, so `CounterCell` is single-instance
   (never replicated; fine for derived per-peer views). The replicable
   counter is `PnCounterCell` (session delta 4): per-source cumulative
   inc/dec totals under a private per-instance source id, `PnCounterDelta`
   merging by pointwise max — commutative, associative, idempotent — so it
   joins the set family in the mergeable class (`Replicable`, 42) and
   survives gossip-mesh echoes, partitions, and late-join catch-up.

*(G-23 resolved for the set and counter families, M4.1: convergence validated
by a 200-seed interleaving test with a control run proving arrival-order
application diverges. `MapDelta`/`ListDelta` instead carry **documented
convergence limits** — arrival-order key puts and index-addressed edits are
single-stream semantics; stable multi-writer forms wait for replication
pressure (42).)*

## Required next steps in the family

- ~~G-22: State + catch-up~~ **Resolved (M4.2)**: every data cell wires the
  post-install `onLinked` hook (13, 21) to unicast state-as-delta-from-empty
  to a late-joining subscriber, and implements `Stateful` so state survives
  drain/migrate (30/33) — no longer trapped in private fields. On-demand pull
  without relinking remains with G-18/G-13 (21).
- ~~Operator library~~ **Implemented (M4.3)** — each an ordinary cell with
  declared incremental semantics, all late-join capable and `Stateful`:
  - `FilterCell` — predicate filter over a tagged set stream, tags intact.
  - `CountCell` — distinct-element count; emits commutative `CounterDelta`s
    on membership-size change only.
  - `IntersectSetCell` — binary (`left`/`right` inlets; n-ary by chaining);
    advertises entry tags, deletes all advertised tags on exit, absorbs tag
    churn that doesn't flip membership.
  - `JoinCell` — incremental keyed inner join over two map streams; inherits
    `MapDelta`'s arrival-order convergence limit.
  - `SemiJoinCell` / `differenceSet` (M11.2) — keyed semijoin (`A ⋉ B`) and
    antijoin (`A ▷ B`, `negated`); difference (`A ⊖ B`, SQL EXCEPT DISTINCT)
    is the antijoin on identity keys. Non-monotone: re-entry rides the other
    side's removal, so output tags are **minted per entry** (`MintedTags`,
    tag hygiene, 21) — never borrowed from inputs (control test: input-tag
    reuse leaves re-entries dead under tombstone folding). Output membership
    at idle is a deterministic function of the converged add-wins input
    memberships; duplicates converge on membership, not tags; not
    glitch-free (22's wrapper is the remedy). Set semantics only — bag
    semantics (EXCEPT ALL) would need a weighted family (see below).
  - `FlatMapSetCell` / `mapSet` (M11.1) — element-wise flatMap/map over a
    tagged set stream, input tags passing through. Sound because tag algebra
    is per-(element, tag): colliding outputs **union** their preimages' tag
    sets (last-wins remapping is the divergent naive form, proven by control
    test), so an output stays live until its last live preimage dies —
    distinct-projection semantics. Transform must be pure; dels translate by
    re-applying it.
  - The shared live-tag fold lives in `TagState` (internal); `MapperCell`
    remains the scalar map/filter.
  Verified: a seeded writers→union→filter→count pipeline equals a batch
  recompute over final writer state on every seed (the prototype invariant
  for the generative harness, 52).

## Grouped aggregation (M11.3)

`GroupByCell(keyFn, aggregator)` folds a tagged set stream into per-key
aggregates on a `MapDelta<K, A>` outlet; `GroupByCell.global` is
fold-to-scalar (one constant-key group). The normative rule of the family: an
`Aggregator` is a **deterministic function of group membership** —
`value(acc)` may depend only on which elements are live, never on
insertion/retraction order. Arrival-order aggregates (first/last/scan) are
excluded by this rule; it is what makes incremental-equals-batch testable and
per-peer recompute converge.

- Membership flips (not tag churn) drive `insert`/`retract`; a group's last
  retraction removes the group (`MapDelta` removal — SQL group-death
  semantics); emission is effective-only by value equality (21). All groups
  touched by one input delta emit as one `MapDelta` under the input's wave
  id (22), so a glitch-free wrap composes normally.
- Aggregator classes: **self-inverting** (count, sumOf, avgOf — O(1)
  accumulators, retraction is arithmetic; Long selectors — float sums are
  order-sensitive) and **non-invertible** (minOf/maxOf/topK/collectToSet,
  M11.4) whose accumulator is the full support multiset (value →
  multiplicity in a `TreeMap`): needed even under set semantics because
  distinct elements can share an extracted value, and retraction of the
  current extremum must reshuffle without a re-scan. Bounded-memory top-k is
  rejected as unsound under retractions (an evicted value can become top
  again — control-tested). Selectors must be total orders with deterministic
  tie-break.
- **Replication story: recompute, not gossip.** The output is single-writer
  `MapDelta` (its documented contract, satisfied by construction), so
  `GroupByCell` is not `Replicable` — and needn't be: aggregates are
  deterministic functions of convergent membership, so each peer derives its
  own from its replicated input and all converge at idle with zero
  aggregate-level coordination. Gossipable aggregate outputs (per-source
  keyed cumulative sums, `PnCounterDelta` generalized) stay deferred with
  trigger: *first aggregate-only replica under input-size pressure*.

## Partitioned state

ADR 1 §5: large keyed datasets shard by key for concurrency, locality, and
scale-out; non-partitioned is for atomic structures.

⚠ GAP (G-24, deferred with trigger — build when the first keyed dataset
feels placement pressure; the M4 exit app never sharded): nothing exists.
*Proposal sketch* (kernel-untouched, per P1): a
**PartitionedCell** is a composite cell whose organelles each own a key range;
its inlet routes commands by key (a routing proxy — same mechanism as
`HostRoutingApi`); its outlet merges child delta streams. Placement of
partitions across hosts is then ordinary cell placement (30/33, 40/42) —
partitioning must not become a second distribution mechanism.

## Durability spectrum

ADR 1 §3 requires in-memory / durable / hybrid state.

*(G-25 resolved, M10)*: durability is a host concern, exactly as proposed —
a host constructed with a `Journal` write-ahead appends every accepted
invocation **as a wire frame** (the same `WireCodec` encoding that crosses
the network: a journal is a bridge to disk) before staging it; recovery
rebuilds the graph, then `recoverFrom` restores the latest checkpoint's
`Stateful` snapshots and replays the frame tail through the ordinary decode
path. `checkpoint` compacts the log atomically; tombstone and PN-slot growth
compact with it. Cells stay oblivious — with one honest exception:
**replay-stable identity**. A recovered instance must re-mint the identities
the network already observed, so set tags and PN source slots derive from
the cell's ref (never `randomUUID`) and the tag counter is snapshot state.
Random identity + replay = resurrected removals and double counts
(`CrashRecoveryTest` proves both directions). Remaining with a trigger:
journal segmentation/rotation and the disk-overflow mailbox (33) — the
first workload where one fsync'd file hurts.
