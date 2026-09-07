# 24 — Standard Data Cells, Merge Semantics, Partitioning

> **Status**: Partial (set family tagged and convergent; counters implemented incl. replicable PN form; relational operator suite + grouped aggregation + windowing-as-grouping done (M11); map/list with documented limits; tagged-map (OR-map) convergence class design decided, unbuilt (96 §E1); partitioning unified as the disjoint-interest setting of the 40/42 instance-set mesh, and tag-epoch continuity design decided, unbuilt; restart supersession built (W2.1, `[24-TAG-02]`))
> **Sources**: ADR 1 (§3, §5, §14), ADR — Cellular Software Development Process (incremental dataflow layer; LASP/Differential Dataflow inspirations)
> **Implementation**: `civictech.cell.data`: `SetCell`, `UnionSetCell`, `CounterCell`, `PnCounterCell`, `MapCell`, `ListCell`, `Propagate`; M11 suite: `FlatMapSetCell`, `SemiJoinCell`, `JoinSetCell`, `GroupByCell`, `Aggregator(s)`, `Windows`, `MintedTags`; `civictech.cell.graph.leftJoin`/`rightJoin`/`fullJoin` (outer joins)

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
   removing an unobserved element is a no-op. `[24-SET-01]` The outlet SHALL
   emit only effective deltas: a remove of an element the cell has not
   observed as added SHALL be a no-op (Ubiquitous).
3. **Merge on the delta type is commutative, associative, idempotent** —
   tag-set union — so membership converges regardless of arrival order.
   `[24-SET-02]` `SetDelta` merge (tag-set union) SHALL be commutative,
   associative, and idempotent, such that membership converges to the same
   result regardless of delivery order (Ubiquitous). Add-wins is not a
   configured bias but a consequence: a concurrent add's
   tag is never observed by the remove. `[24-SET-03]` A remove SHALL only
   retract the tags it observed, such that a concurrent add's tag — never
   observed by that remove — survives the merge (add-wins as a consequence
   of tag-set union, Ubiquitous). Tags are `Timestamp`s minted
   cell-locally (unique per add instance — see 22 for why wave ids are not
   reused). This is the CRDT-style ingredient for decentralized replication
   (40/42) without imposing CRDTs everywhere. (Contrast — decided in
   [93 I-4](../90-roadmap/93-feature-interactions.md): the attention
   protocol (30/34) is *not* this pattern; per-link LWW level state over a
   bounded key space — the direct downstream link set — needs no tags, and
   slot replacement + a commutative fold is its merge law.)
4. **Derived cells consume delta contracts**: `UnionSetCell` tracks live
   tags per element, forwards only new tag information (duplicate deliveries
   across diamond fan-ins dedup), and any consumer derives membership from
   the forwarded tag algebra. `[24-OP-UNION-01]` `UnionSetCell` SHALL track
   live tags per element and forward only new tag information, such that
   duplicate deliveries of the same tag across a diamond fan-in are
   deduplicated (Ubiquitous). `CounterCell` (`increment`/`decrement` →
   `CounterDelta`) is commutative by construction: merge is addition —
   commutative but **not idempotent**, so `CounterCell` is single-instance
   (never replicated; fine for derived per-peer views). `[24-OP-COUNTER-01]`
   `CounterCell` merge SHALL be addition (commutative, not idempotent), and
   `CounterCell` SHALL be single-instance — never replicated (Ubiquitous).
   The replicable
   counter is `PnCounterCell` (session delta 4): per-source cumulative
   inc/dec totals under a private per-instance source id, `PnCounterDelta`
   merging by pointwise max — commutative, associative, idempotent — so it
   joins the set family in the mergeable class (`Replicable`, 42) and
   survives gossip-mesh echoes, partitions, and late-join catch-up.
   `[24-OP-PNCOUNTER-01]` `PnCounterDelta` merge (pointwise max over
   per-source cumulative totals) SHALL be commutative, associative, and
   idempotent, such that `PnCounterCell` replicas converge across
   gossip-mesh echoes, partitions, and late-join catch-up (Ubiquitous).

*(G-23 resolved for the set and counter families, M4.1: convergence validated
by a 200-seed interleaving test with a control run proving arrival-order
application diverges. `MapDelta`/`ListDelta` instead carry **documented
convergence limits** — arrival-order key puts and index-addressed edits are
single-stream semantics. The keyed multi-writer form is no longer an open
wait: the convergent design is decided — see §Tagged maps below, an
additive `TaggedMapDelta` beside `MapDelta`, which stays single-stream; the
positionally-indexed list form still waits for replication pressure (42).
`[24-OP-MAP-01]` `[24-OP-LIST-01]` `MapDelta`'s arrival-order
key puts and `ListDelta`'s index-addressed edits SHALL remain single-stream
semantics only — neither is a convergent merge under concurrent writers
(Ubiquitous).)*

## Required next steps in the family

- ~~G-22: State + catch-up~~ **Resolved (M4.2)**: every data cell wires the
  post-install `onLinked` hook (13, 21) to unicast state-as-delta-from-empty
  to a late-joining subscriber, and implements `Stateful` so state survives
  drain/migrate (30/33) — no longer trapped in private fields.
  `[24-CATCHUP-01]` WHEN a subscriber links to a data cell's outlet after
  deltas have already flowed, the cell SHALL unicast its
  state-as-delta-from-empty to that subscriber, and SHALL preserve that
  state across drain/migrate via `Stateful` (Event-driven). On-demand pull
  without relinking remains with G-18/G-13 (21).
- ~~Reading a large cell's state without copying it~~ **Resolved
  (V1C-KERNEL)**: a data cell additionally serves an instrument's **bounded
  read** of its own state — whole entries, at most a requested number of them
  per page, resumed by a cell-minted opaque cursor, and stamped with the fold's
  tag frontier — emitting nothing and moving no wave position (21 §Pull,
  [21-PULL-02]). The seam is additive and opt-in beside `Stateful`, because four
  subsystems depend on `snapshot()` being a whole restorable value (drain,
  migration, promotion state transfer, durability checkpoints); a family that
  does not implement it is refused with a stated reason, never answered with a
  silent whole copy.
  `[24-BOUND-01]` WHEN a data cell serves a bounded read, each page SHALL
  contain whole entries, no entry SHALL be returned twice within one walk, and
  every page SHALL carry the cell's tag frontier (Event-driven).
  `[24-BOUND-02]` WHEN a data cell's bounded read is walked to completion and
  the cell accepts no operation for the duration of that walk, the union of the
  walk's pages SHALL equal the cell's whole state, whatever page limit the walk
  used (Event-driven).
  Three obligations sit under those and belong to the cell, not to the kernel.
  **A total enumeration order is imposed, not inherited** — the family's backing
  maps are insertion-ordered, so a remove-then-re-add moves a key to the tail and
  could hand one key to a walk twice, and a restored instance enumerates
  differently again; freezing the key sequence at walk start is how the
  reference implementation discharges it. **A cursor is key-based, not
  index-based** — an index into live state is invalidated by any removal earlier
  in the enumeration, a key survives every mutation but its own; a positional
  cursor is a documented exception for a family with no element identity (the
  list form), and the weaker guarantee it then carries is declared on the page
  rather than buried in the cell. **Resume costs O(page), not O(state)** — a
  cursor that rescans from the start turns a paged walk into O(n²) and forfeits
  the reason for paging.
  How *exact* a page's frontier stamp is, is bounded rather than absolute: the
  first and last page of a walk carry it exactly, and an intermediate page may
  carry the walk's most recent exactly-determined frontier instead, declaring on
  the page that it did. Recomputing it per page is O(n) per page and O(n²) per
  walk — the cost the bounded read exists to avoid — and maintaining it
  incrementally would put a secondary index on the fold path (P2). Because a tag
  frontier is monotone, the walk's two exact endpoint stamps are what
  [21-PULL-03]'s stability check reads, and the intermediate stamps cost it
  nothing.
  **An exclusive value is never paged.** An entry whose value is `Owned` or
  `Leased` is replaced by a presence descriptor — key, wrapper type name,
  disposition — and counted; a page is a copy, and copying an exclusive payload
  is the prohibition itself (23). Nothing is taken, borrowed, released or
  unwrapped to build the descriptor, and the count is an honest signal rather
  than a silent gap. This makes a bounded read's ownership contract deliberately
  stronger than `snapshot()`'s, which still serializes whatever the fold holds.
- ~~Operator library~~ **Implemented (M4.3, extended to the full relational
  suite in M11)** — each an ordinary cell with declared incremental
  semantics, all late-join capable and `Stateful`. Correspondence to the
  differential-dataflow vocabulary: `distinct` needs no cell (SetDelta is
  set-semantic; union is the distinct-preserving merge) and `consolidate`
  is the effective-only emission rule (21); `negate` has no meaning without
  signed multiplicities (see the weighted-family deferral below).
  Convergence classes: **freely replicable** duplicates emit identical tag
  info (filter, flatMap, union), **convergent duplicates** agree on
  membership but mint distinct tags (intersect, quorum, semijoin/antijoin,
  equi-join), **single-instance** outputs are single-writer streams
  (groupBy, the counters outside the PN form):
  - `FilterCell` — predicate filter over a tagged set stream, tags intact.
    `[24-OP-FILTER-01]` `FilterCell` SHALL pass through only elements
    satisfying the predicate, with element tags unchanged (Ubiquitous).
  - `CountCell` — distinct-element count; emits commutative `CounterDelta`s
    on membership-size change only. `[24-OP-COUNT-01]` `CountCell` SHALL
    emit a `CounterDelta` only when membership size changes, counting
    distinct elements (Ubiquitous).
  - `IntersectSetCell` — binary (`left`/`right` inlets; n-ary by chaining);
    advertises one cell-minted tag per entry — never the inputs' own, per its
    *convergent duplicates* class above and 21 §Tag hygiene — deletes all
    advertised tags on exit, absorbs tag churn that doesn't flip membership.
    `[24-OP-INTERSECT-01]` `IntersectSetCell` SHALL advertise a freshly
    minted, cell-owned tag for an element on entry and delete all advertised
    tags on exit, absorbing tag churn that does not flip membership
    (Ubiquitous).
  - `QuorumSetCell` — dynamic **k-of-n** fan-in (one link per source, each
    carrying its own tag lane): an element is admitted exactly while at least
    one distinct live source link asserts it *and* the number of such links
    meets a `threshold(n)` evaluated against the current live-source count `n`,
    so union (`{ 1 }`), intersection
    (`{ n -> n }`), majority (`{ n -> n / 2 + 1 }`), fixed k-of-n (`{ k }`) and
    near-miss (`{ n -> n - 1 }`) are one lambda over the same cell. The
    at-least-one floor is not redundant with the threshold: a threshold may
    evaluate to zero or below — near-miss at `n == 1`, or a fixed `k <= 0` —
    and an element no live source asserts is still never in the quorum, so an
    element whose last asserting lane closes exits rather than being re-admitted
    on a vacuous count. Because the
    threshold reads `n`, a link opening or closing re-evaluates the whole
    working set, not only the elements whose own count moved (an empty source
    joining tightens an intersection). Output tags are **minted per entry** —
    one freshly minted, cell-owned tag advertised on entry, exactly that tag
    deleted on exit, tag churn that doesn't flip membership absorbed — per its
    *convergent duplicates* class above and 21 §Tag hygiene; borrowing is
    doubly unsound here, because a quorum's flip-ON rides *another* lane's
    assertion rather than a fresh input add-tag on the flipping element, and
    because a borrowed tag reaching a consumer twice across a diamond is
    retracted for both paths at once (measured: `union(A, quorum(A, B))` lost
    an element live in `A` — computenet-vvre, computenet-s6l2).
    `[24-OP-QUORUM-01]` `QuorumSetCell` SHALL admit an element exactly while at
    least one distinct live source link asserts it and the number of such links
    meets its threshold of the live-source count, advertising a freshly minted,
    cell-owned tag for the element on entry and deleting exactly that tag on
    exit (Ubiquitous).
    A replayed frame flagged as a baseline is a recovery rather than a live
    wave and is admitted regardless of the threshold (`[24-REPLAY-01]`).
  - `JoinCell` — the **LWW dictionary join**: keyed inner join over two
    single-writer map streams where either side's put *refreshes* the pair —
    value-replacement semantics, inherently arrival-order (`MapDelta`'s
    documented limit). Useful for config/dictionary lookups; not the
    relational join. `[24-OP-JOIN-01]` `JoinCell` SHALL be a keyed inner
    join over two single-writer map streams where either side's put
    refreshes the pair under value-replacement (arrival-order) semantics
    (Ubiquitous).
  - `JoinSetCell` / `joinSet` / `crossProduct` (M11.5) — the **relational
    equi-join** over convergent tagged set streams: a pair is live iff both
    rows are live under matching keys; one minted tag per live pair
    ([MintedTags] — pairs re-enter when a removed row returns), emitted under
    `combine(a, b)`. `[24-OP-JOINSET-01]` `JoinSetCell` SHALL emit a pair
    under `combine(a, b)` iff both rows are live under matching keys, minting
    one tag per live pair such that a pair re-enters when a removed row
    returns (Ubiquitous). Many-to-one `combine` collapses via per-pair tags (the
    output survives until its last pair dies — whole-element deletion is the
    divergent naive form, control-tested); many-to-many keys yield all
    pairs; cross product = the unit key. `[24-OP-JOINSET-02]` Many-to-one
    `combine` outputs SHALL survive until their last contributing pair dies
    (per-pair tag collapse, not whole-element deletion); many-to-many keys
    SHALL yield all pairs; cross product SHALL be the unit-key case
    (Ubiquitous). `combine` outputs crossing the wire
    must be `@Serializable` app types (`Pair` is not WireCodec-registered).
  - `SemiJoinCell` / `differenceSet` (M11.2) — keyed semijoin (`A ⋉ B`) and
    antijoin (`A ▷ B`, `negated`); difference (`A ⊖ B`, SQL EXCEPT DISTINCT)
    is the antijoin on identity keys. `[24-OP-SEMIJOIN-01]` `SemiJoinCell`
    SHALL implement keyed semijoin (`A ⋉ B`) and antijoin (`A ▷ B`), with
    difference (`A ⊖ B`) as the antijoin on identity keys (Ubiquitous).
    Non-monotone: re-entry rides the other
    side's removal, so output tags are **minted per entry** (`MintedTags`,
    tag hygiene, 21) — never borrowed from inputs (control test: input-tag
    reuse leaves re-entries dead under tombstone folding).
    `[24-OP-SEMIJOIN-02]` Semijoin/antijoin output tags SHALL be minted per
    entry, never borrowed from input tags, so a re-entry after the other
    side's removal is not left dead under tombstone folding (Ubiquitous).
    Output membership
    at idle is a deterministic function of the converged add-wins input
    memberships; duplicates converge on membership, not tags; not
    glitch-free (22's wrapper is the remedy). `[24-OP-SEMIJOIN-03]` WHILE
    both input streams are idle, semijoin/antijoin output membership SHALL
    be a deterministic function of the converged add-wins input
    memberships (duplicates converge on membership, not tags); the operator
    is not glitch-free on its own (State-driven). Set semantics only — bag
    semantics (EXCEPT ALL) would need a weighted family (see below).
    Antijoin membership flips are **absence assertions**: emitting or
    retracting a row because the *other* side does or doesn't hold a
    matching key needs knowing non-membership, which is non-monotone in the
    CALM sense — confluent, coordination-free composition is available only
    to monotone operators (`03-lasp-crdt-lattice.md` §5); antijoin needs
    sealing. [24-OP-SEMIJOIN-04] WHERE `emitOnFrontier` gating is enabled, a
    `SemiJoinCell` antijoin's output SHALL emit only at wave completeness,
    coalesced to the wave's net minted enter/exit set, such that a
    transient enter-then-exit within one wave is never observed on the
    outlet (Optional feature). The gate is opt-in; the default stays
    ungated, so a transient flicker within one wave may still reach the
    outlet, remediated only by 22's glitch-free wrapper rather than by a
    smarter convergent cell — research rejects a convergent fix here
    (absence-based emission is non-monotone; some sealing is unavoidable,
    and per-wave sealing over `cell.consistency.WaveFrontier` is the
    cheapest ComputeNet has). See 20/22 §The observation frontier for the
    guarantee this gate serves. *(Implemented — `SemiJoinCell(…,
    emitOnFrontier = true)`: the wave's input deltas across both inlets are
    buffered by a `WaveFrontier`-shaped fold mirrored at cell scope
    (`cell.data.op.WaveGate` — the frontier itself is untouched, for
    `CoalescingCombineCell`'s structural reason), applied together at
    completeness, and reconciled once on membership before any tag is
    minted. The gate inherits the static-link-set frontier's phantom
    expected edge (G-13): it is for a shared-source diamond, not for two
    independent roots.)*
  - `CombineLatestCell` — incremental keyed **outer** combine over two map
    streams (the outer sibling of `JoinCell`); a key present on only one
    side still emits, computed as `combine(k, v, null)` / `combine(k, null,
    w)`. A null-extension is an **absence assertion** exactly like
    antijoin's — it asserts the other side holds no value for `k` at this
    frontier (CALM, `03-lasp-crdt-lattice.md` §5) — and is the
    internal-consistency essay's exact outer-join failure mode: a
    null-extended row can be emitted and then retracted within one wave as
    the other side's real value arrives
    (`04-cross-cutting-watermarks-consistency.md` §3). Ungated (the
    default), a null-extension may ride the outlet and be retracted moments
    later, remediated only by 22's wrapper; gated (`emitOnFrontier`,
    mirroring `SemiJoinCell` above), the null-extension emits only once the
    wave has settled, so a same-wave retraction never reaches the outlet —
    a genuinely one-sided key still null-extends at completeness, so outer
    semantics are unchanged and only the timing gates.
    See 20/22 §The observation frontier. *(Implemented, as for
    `SemiJoinCell` above — 96 §E2.4, same mirrored fold and same
    phantom-expected-edge caveat. This cell now also absorb-acks a wave it
    silently swallows, closing the last divergence from 20/22 §Completeness
    over silent or stuck edges.)*
  - `UntagCell` (96 §E1.5) — the G-23 adoption seam, not an incremental
    algebra of its own: an adapter from `TaggedMapDelta` (§Tagged maps below)
    to `MapDelta`, so a converged `OrMapCell` can feed the untagged join
    family above (`CombineLatestCell`/`LookupJoinCell`/`JoinCell`) without
    reintroducing arrival-order bias. Effective-only: one `MapDelta` per
    input delta, a put for a key whose exposed value changed, a removal only
    when the key's last live dot dies, never a removal followed by a put for
    the same delta. Rides the arriving wave rather than originating one
    (single-inlet, so no completeness gate is needed). `Stateful`, snapshotting
    the exposed-value map it diffs against so a restored instance does not
    replay the whole map as novelty. *(Implemented.)*
  - `FlatMapSetCell` / `mapSet` (M11.1) — element-wise flatMap/map over a
    tagged set stream, input tags passing through. Sound because tag algebra
    is per-(element, tag): colliding outputs **union** their preimages' tag
    sets (last-wins remapping is the divergent naive form, proven by control
    test), so an output stays live until its last live preimage dies —
    distinct-projection semantics. `[24-OP-FLATMAP-01]` `FlatMapSetCell`
    SHALL union the tag sets of colliding outputs' preimages, such that an
    output stays live until its last live preimage dies (Ubiquitous).
    Transform must be pure; dels translate by
    re-applying it. `[24-OP-FLATMAP-02]` The `FlatMapSetCell` transform
    function MUST be pure; deletions SHALL translate by re-applying it
    (Ubiquitous).
  - The shared live-tag fold lives in `TagState` (internal); `MapperCell`
    remains the scalar map/filter.
  Verified: a seeded writers→union→filter→count pipeline equals a batch
  recompute over final writer state on every seed (the prototype invariant
  for the generative harness, 52).

## Tagged maps

**Built** (closes G-23 for keyed structures; 96 §E1) — 96 §E1.2 (`OrMapCell`
core), §E1.3 (replication: echo-terminating gossip, pull baseline,
re-origination, dead-source fencing), §E1.4 (embedded `MergeablePayload`
value-folding on `OrMapCell`/`TaggedMapDelta`) and §E1.5 (`TaggedMapView` +
`View.taggedMap()`, and the `UntagCell` adapter into the untagged join family
above) have all shipped against this section, as `OrMapCell`/`TaggedMapDelta`
in `civictech.cell.data`, `TaggedMapView`/`View.taggedMap()` in
`civictech.cell.data.view`/`civictech.cell.observe`, and `UntagCell` in
`civictech.cell.data.op`. §E1.6 (the two-JVM replicated demo adoption) has now
shipped too, as `demo/tiering`'s two-host variant — its manual re-tier lane is
a replicated `OrMapCell<String, String>` fused into the computed board through
`UntagCell` — proved by `TwoJvmTieringConvergenceTest`,
`TieringCrashRestartTest` and `TieringLateJoinerTest`. It is an **additive new
delta type**:
`MapDelta` and its single-writer cells (`MapCell`, `JoinCell`, `GroupByCell`)
are untouched, and `KeyedSetCell` is untouched — this resolves backlog
`06-or-map-tagged-map-delta.md`'s open choice in favor of addition over
replacement.

A tagged map generalizes the observed-remove idiom above from *elements* to
*per-key values*: the Riak-map / delta-ORMap design, adapted to
ComputeNet's existing tag machinery
(`doc/research/incremental-engines/03-lasp-crdt-lattice.md` §4;
`05-gap-mapping.md` §Gap 2). `KeyedSetCell` already does per-key
observed-remove with atomic retract+add, and `Timestamp(sourceId, counter)`
tags are already dot-shaped, so this section reads as `SetDelta`'s idiom
lifted one level, from a live/tombstoned tag per element to a live/
tombstoned **dot** per key:

```kotlin
interface MapOps<K, V> { fun put(key: K, value: V); fun remove(key: K) }
data class TaggedMapDelta<K, V>(              // per-key dots (G-23)
    val puts: Map<K, Map<Timestamp, V>>,      // live dots carrying values
    val dels: Map<K, Set<Timestamp>>,         // tombstoned observed-remove dots
) : Serializable {
    fun merge(other: TaggedMapDelta<K, V>): TaggedMapDelta<K, V>  // pointwise dot union
}
```

Each put mints a unique `Timestamp` dot carrying that put's value; a key's
live dots are its `puts[key]` entries not covered by `dels[key]`. All dots
for the whole map share **one causal namespace** — there is no per-key
context (decided point 1 below).

**The four laws** (verbatim from 96 §E1.1):

- **Merge** is pointwise dot union, idempotent because a dot's value is
  immutable. `[24-TMAP-01]` `TaggedMapDelta` merge (pointwise dot union)
  SHALL be commutative, associative, and idempotent, such that a key's
  presence and value converge to the same result regardless of delivery
  order (Ubiquitous).
- **Presence** is add-wins: a key is live iff it has any live (not
  tombstoned) dot. `[24-TMAP-02]` A key SHALL be present iff it has at
  least one live dot — add-wins (Ubiquitous).
- **Value** is Last-Writer-Wins **by dot order** `(counter, sourceId)` —
  never wall clock. `[24-TMAP-03]` A key's exposed value SHALL be the value
  of its live dot with the greatest `(counter, sourceId)` order, and MUST
  NOT be selected by wall-clock time (Ubiquitous).
- **`remove(k)` is reset-remove**: it tombstones every dot the remover
  observed live at `k`; a concurrent put's dot, not observed by that
  remove, survives the merge as `k`'s remaining live value.
  `[24-TMAP-04]` A `remove(k)` SHALL tombstone every dot observed live at
  `k` at the time of the remove, such that a concurrent put's dot — not
  observed by that remove — survives the merge (reset-remove,
  Ubiquitous).

**Decided points** (96 §E1.1, each traced to its research citation):

1. **One shared causal namespace for the whole map**, never per-key —
   per-key contexts re-admit stale values on key re-creation
   (`03-lasp-crdt-lattice.md` §4; `05-gap-mapping.md` §Gap 2).
2. **Tombstoned dels subsume deferred context ops.** The tagged map follows
   `SetCell`'s tombstoned idiom (dels stored as covered dots, not a
   context-only causal record), so Riak's deferred-operations list is
   unnecessary here: a remove's dots arriving before their put simply sit
   in `dels` and cover the put on arrival, exactly as `SetCell`'s
   `applyRemote` already behaves for elements
   (`kernel/src/main/kotlin/civictech/cell/data/SetCell.kt:107-116`).
3. **Embedded values are restricted to the idempotent-mergeable class**
   (`MergeablePayload`) — Riak's embedded-counter anomaly (a non-idempotent
   embedded CRDT cannot get full reset-remove without a dot per increment)
   is the documented counterexample (`03-lasp-crdt-lattice.md` §4). Research
   `05-gap-mapping.md` §Gap 2 phrases the same restriction as "the
   `Replicable` class"; `MergeablePayload` is the decided wording carried
   here (96 §E1.1) — `Replicable` is the wire-replication contract a cell
   implements, `MergeablePayload` is the payload-level merge capability an
   embedded value must have.
4. **Dot-metadata bloat is a codec-layer concern from day one** — Riak
   names actor-metadata repetition "a serious issue" for size
   (`03-lasp-crdt-lattice.md` §4); deduping (e.g. grouping dots by
   `sourceId`) is a wire-encoding responsibility, not part of this delta
   type's merge semantics.
5. **The Lasp determinism caveat is normative for downstream adopters**
   (`03-lasp-crdt-lattice.md` §1, "Determinism caveat"): state convergence
   (SEC) alone does not make value-keyed derivation deterministic — whether
   a concurrent remove cancels a concurrent put can depend on the merge
   schedule for an operator that reads a *value*, not just presence.
   Tag-precise removes — a remove carries exactly the dots it observed,
   never a value-level predicate — are what keep value-keyed derivation
   deterministic here; an operator deriving from `value(k)` inherits this
   caveat and must not assume a wall-clock or arrival-order resolution.

**Excluded from this milestone.** The tombstone-free (context-only) wire
form — `dels` shipped as causal context alone, with no tombstone payload —
is deliberately not part of `TaggedMapDelta`. It needs the causal-merging
condition (`03-lasp-crdt-lattice.md` §2: join `Δⱼ^{a,b}` into `Xᵢ` only if
`Xᵢ ⊒ Xⱼᵃ`), whose delivered-watermark prerequisite lands with E3; tracked
as [95 §R10](../90-roadmap/95-research-plan.md). Multi-value exposure
mechanics, the `MapOps` contract surface, catch-up/snapshot mechanics, and
replication wiring (gossip, baseline, re-origination) are 96 §E1.2/§E1.3
code-path material — named here, not specified here.

## Grouped aggregation (M11.3)

`GroupByCell(keyFn, aggregator)` folds a tagged set stream into per-key
aggregates on a `MapDelta<K, A>` outlet; `GroupByCell.global` is
fold-to-scalar (one constant-key group). `[24-OP-GROUPBY-01]` `GroupByCell`
SHALL fold a tagged set stream into per-key aggregates on a `MapDelta<K, A>`
outlet; `GroupByCell.global` SHALL fold to one constant-key group
(Ubiquitous). The normative rule of the family: an
`Aggregator` is a **deterministic function of group membership** —
`value(acc)` may depend only on which elements are live, never on
insertion/retraction order. Arrival-order aggregates (first/last/scan) are
excluded by this rule; it is what makes incremental-equals-batch testable and
per-peer recompute converge. `[24-AGG-01]` An `Aggregator`'s `value(acc)`
SHALL depend only on which elements are currently live, never on
insertion/retraction order, so arrival-order aggregates (first/last/scan) are
excluded (Ubiquitous).

- Membership flips (not tag churn) drive `insert`/`retract`; a group's last
  retraction removes the group (`MapDelta` removal — SQL group-death
  semantics); emission is effective-only by value equality (21).
  `[24-OP-GROUPBY-02]` Membership flips, not tag churn, SHALL drive
  `insert`/`retract`, and a group's last retraction SHALL remove the group
  from the `MapDelta` outlet (Ubiquitous). All groups
  touched by one input delta emit as one `MapDelta` under the input's wave
  id (22), so a glitch-free wrap composes normally. `[24-OP-GROUPBY-03]` All
  groups touched by one input delta SHALL emit as one `MapDelta` under that
  input's wave id (Ubiquitous).
- Aggregator classes: **self-inverting** (count, sumOf, avgOf — O(1)
  accumulators, retraction is arithmetic; Long selectors — float sums are
  order-sensitive) and **non-invertible** (minOf/maxOf/topK/collectToSet,
  M11.4) whose accumulator is the full support multiset (value →
  multiplicity in a `TreeMap`): needed even under set semantics because
  distinct elements can share an extracted value, and retraction of the
  current extremum must reshuffle without a re-scan. Bounded-memory top-k is
  rejected as unsound under retractions (an evicted value can become top
  again — control-tested). `[24-OP-GROUPBY-04]` IF a non-invertible
  aggregator (min/max/topK/collectToSet) is implemented with bounded-memory
  eviction, THEN it is unsound under retractions (an evicted value can
  become top again) — the accumulator SHALL instead retain the full support
  multiset (Unwanted behavior). Selectors must be total orders with deterministic
  tie-break. `[24-OP-GROUPBY-05]` Non-invertible aggregator selectors MUST be
  total orders with a deterministic tie-break (Ubiquitous).
- **Windowing = key derivation (M11.6).** There is no wall clock (P1) and
  wave ids are per-source, so event time is an explicit attribute of the
  element and a window is just part of the group key: tumbling = composite
  key via `Windows.tumbling`; sliding = per-element expansion
  (`FlatMapSetCell` over `Windows.sliding`) then group. `[24-OP-WINDOW-01]`
  A window SHALL be expressed as part of the group key alone — tumbling as a
  composite key, sliding as per-element expansion then group — since there
  is no wall clock and event time is an explicit element attribute
  (Ubiquitous). **Windows never
  close** — late elements are ordinary adds, retractions flow (view
  semantics). `[24-OP-WINDOW-02]` Windows SHALL NOT close: a late element
  SHALL be an ordinary add and retractions SHALL flow as in any other view
  (Ubiquitous). Deferred with triggers: watermark-driven eviction (an ordinary
  watermark-as-data source feeding upstream dels; trigger: real
  window-state memory pressure) and session windows (assignment is not a
  per-element function; trigger: first proximity-session consumer).
  Wave/tick-based windows are rejected: contents would be
  placement-dependent, breaking P1 and batch equivalence.
- **Outer joins are compositions, not cells (M11.6)**: `leftJoin` /
  `rightJoin` / `fullJoin` graph factories union the relational join's
  matched rows with null-completed antijoin rows. `[24-OP-OUTERJOIN-01]`
  `leftJoin`/`rightJoin`/`fullJoin` SHALL union the relational join's
  matched rows with null-completed antijoin rows (Ubiquitous). Eventually consistent,
  not glitch-free — transient `(a, null)`/`(a, b)` overlap while opposing
  updates are in flight; an atomic outer-join cell waits for a consumer
  that can't tolerate the transient. `[24-OP-OUTERJOIN-02]` WHILE opposing
  updates to an outer-join composition are in flight, the composition SHALL
  be only eventually consistent — a transient overlap of `(a, null)` and
  `(a, b)` rows for the same key MAY be observed before convergence
  (State-driven).
- **Replication story: recompute, not gossip.** The output is single-writer
  `MapDelta` (its documented contract, satisfied by construction), so
  `GroupByCell` is not `Replicable` — and needn't be: aggregates are
  deterministic functions of convergent membership, so each peer derives its
  own from its replicated input and all converge at idle with zero
  aggregate-level coordination. `[24-OP-GROUPBY-06]` `GroupByCell`'s outlet
  SHALL be single-writer `MapDelta` and `GroupByCell` SHALL NOT be
  `Replicable`; each peer SHALL derive its own aggregate from its
  replicated input, converging at idle with zero aggregate-level
  coordination (Ubiquitous). Gossipable aggregate outputs (per-source
  keyed cumulative sums, `PnCounterDelta` generalized) stay deferred with
  trigger: *first aggregate-only replica under input-size pressure*.

⚠ GAP (G-44): Single-writer replication (leader→follower log-shipping)
defers its liveness half: no automatic leader election, no failure
detector, no follower-unpark rule under SAFETY_PARK, and split-brain
reconciliation beyond last-epoch-wins is undesigned. Proposal: opt-in
epoch-claim election folded from the eventually-consistent membership index
with a stated convergence/liveness bound and a generative leader-churn
harness; a failure-detection window that does not become a second heartbeat
protocol; a witness-set-superset unpark rule for SAFETY_PARK; an
application-level reconciliation hook for fenced divergent writes; an
optional ack-from-k durability tier; and per-shard leader routing when
partitions replicate (93 I-25/I-2/I-3/I-8).

## Partitioned state

ADR 1 §5: large keyed datasets shard by key for concurrency, locality, and
scale-out; non-partitioned is for atomic structures.

**Partitioning is the disjoint-interest setting of one mesh.** It is not a
second distribution mechanism, nor even a second *replication* mechanism: it is
the conflict-free degenerate case of the instance-set-with-interest substrate in
40/42 §Interest-scoped instance sets. Shards are interest-scoped instances of one
logical id, each carrying a disjoint key-`Interest`; the router is the
disjoint-interest linker (`Replication.maybeLink`); disjoint union is the merge
with the merge function never exercised. Everything this section specifies about
keyed structure — how keys route, how disjointness proves merge-safety, how a
range moves — is the keyed vocabulary of that one model; wire, journal, catch-up,
and park/replay are inherited from replication, not re-earned here (42).

⚠ GAP (G-24, deferred with trigger — build when the first keyed dataset
feels placement pressure; the M4 exit app never sharded): nothing is built,
but the design is decided (the instance-set substrate, 42 §Interest-scoped
instance sets; composite naming per 93 I-8, evaluated under placement in
93 I-19). Everything below is decided design, not code; kernel untouched,
per P1.

⚠ EARS-GAP: this status claim ("nothing is built... kernel untouched")
appears stale — `kernel/.../cell/data/PartitionedCell.kt` and its test suite
(`PartitionedCellTest`, `PartitionedPromotionTest`, `PartitionedPullTest`,
`PartitionedPullScopeWireTest`) exist, and §PN-4 below (same chapter)
describes `ShardCell`/`PartitionedCell` in the present tense as landed. The
`[24-PART-*]` ids below are minted on the assumption that PN-4's landed
description is authoritative and this G-24 status line is out of date; a
spec editor with fuller context should confirm or retract them.

A **PartitionedCell** is one composite cell — one membrane, one logical id —
a naming/composition convenience over a **disjoint-interest instance set**: its
organelle cells are the interest-scoped instances, each holding a **disjoint key
range** (its key-`Interest`). Keyed structures only (Set = element-keyed, Map =
key-keyed): positionally-indexed `ListCell` is out of scope — a global position
index across shards is ill-defined, and `ListDelta`'s convergence limit is not
dissolved by disjointness; partition a list by keying entries on a stable id,
never on position.

- **Two membrane ports.** A routing inlet carrying the organelles' own
  command contract and a merging outlet carrying the delta contract; from
  outside the composite is indistinguishable from a single data cell.
  External links bind the composite's ports, so rebalancing never
  re-handshakes counterparts; organelles are never externally addressed.
  `[24-PART-01]` A `PartitionedCell` composite SHALL be externally
  indistinguishable from a single data cell — one membrane, its external
  links bound to the composite's own ports — such that repartitioning never
  re-handshakes external counterparts (Ubiquitous).
- **Routing is a served proxy keyed by a `@Key` descriptor slot.** A `@Key`
  annotation on one data-contract parameter emits a `keyIndex` into the
  method descriptor; the router extracts the key, applies the Serializable
  total partitioner, and forwards to exactly one organelle inlet — O(1)
  dispatch, the one place per-message routing is intrinsic to the feature
  (same accepted status as `HostRoutingApi.route`). Key-less methods
  (`clear()`) broadcast and MUST be non-exclusive (KSP lint).
  `[24-PART-02]` The router SHALL forward a keyed invocation to exactly one
  organelle inlet selected by the total partitioner over the `@Key` slot;
  key-less methods SHALL broadcast to every organelle and MUST be
  non-exclusive (Ubiquitous).
- **Demux preserves SPSC.** The partitioner maps each key to exactly one
  organelle, so the exclusive bit (G-21, 23) holds end-to-end: an `Owned`
  payload moves exactly once into exactly one organelle. Fan-out is not
  demux; broadcasting an exclusive payload is refused.
- **Wave-transparent merge; disjointness is the merge-safety proof.** The
  merging outlet forwards each organelle's delta preserving its
  `MessageContext` — it neither re-mints a wave nor coalesces sources; each
  organelle outlet is its own wave source. Because ranges are disjoint, a
  delta from one organelle only ever mentions its own keys: merging is
  conflict-free union, no downstream diamond joins two partitions on the
  same key, and cross-source glitches are impossible without coordination.
  `[24-PART-03]` Because organelle key ranges are disjoint, the composite's
  merging outlet SHALL forward each organelle's delta preserving its
  `MessageContext` as conflict-free union, such that a partitioned cell's
  converged view equals its unpartitioned twin's (Ubiquitous).
- **Repartition = interest reassignment + per-range Buffering.** Moving range
  R is `Interest` reassignment on the disjoint-interest mesh (42): set R to
  Buffering on the router (other ranges flow), replay R's
  state-as-delta-from-empty into its new owner as one catch-up (the same
  mechanism a re-announce drives, not a bespoke protocol), flip the versioned
  routing table atomically and bump its `routingEpoch`, replay parked commands
  in order; a stale-epoch command re-routes. External links observe none of it.
  `[24-PART-04]` WHEN a key range R is repartitioned, the router SHALL set R
  to Buffering (other ranges continue flowing), replay R's
  state-as-delta-from-empty into its new owner, then atomically flip the
  routing table and bump its `routingEpoch` and replay parked commands in
  order — all invisible to external links (Event-driven).
- **Late join = per-organelle catch-up.** Each organelle unicasts its
  key-range state-as-delta-from-empty (the G-22 mechanism); the union of
  disjoint-key catch-ups IS the coherent cross-partition snapshot.
  `[24-PART-05]` WHEN a subscriber links to a partitioned cell, each
  organelle SHALL unicast its own key-range state-as-delta-from-empty, such
  that the union of disjoint-key catch-ups is the coherent cross-partition
  snapshot (Event-driven).

Under real placement (93 I-19) the composite reduces to shipped primitives —
partitioning must not become a second distribution mechanism, and doesn't:

- **A membrane, not a host.** The composite adds no node to the host
  hierarchy; organelles are ordinary cells spawned on ordinary hosts by
  ordinary placement (30/33, 40/42). Partitioning contributes a third map —
  keys→cells, the routing table — which is not a distribution mechanism at
  all: placement distributes cells across machines (registry), replication
  distributes copies of a cell (mesh), both untouched.
- **Routing epoch and registry location are orthogonal.** Repartition
  mutates ranges and bumps the epoch, never the registry; migration
  re-resolves the organelle's registry location, never the epoch (the table
  holds ref-bound proxies, not locations). Migration is invisible to
  routing; repartition is invisible to the registry.
- **Per-link drain suffices for single-organelle migration.** Migrating one
  organelle is the ordinary per-link drain (30/33); disjointness reduces
  the composite's ordering obligation to the per-link FIFO the drain
  already guarantees — no barrier, other partitions flow untouched.
- **Supervision is placement config the composite re-applies.** Each
  organelle is supervised by its own host; the composite holds a policy per
  partition and MUST re-apply it after every (re)placement, since
  supervision is per-host and does not migrate.
- **Replication composes per organelle — same knob, wider interest.** A
  mergeable organelle joins its own gossip mesh (40/42) independently by
  widening its `Interest` to overlap peers: a shard that also keeps replicas is
  the **sharded-replication** setting (42), reached by overlapping partial
  interest, not by a second mechanism. The composite never coordinates
  replication.

~~⚠ GAP (G-56): PartitionedCell's adopted design leaves its distribution edges
open.~~ **Resolved by design (superseded by 42 §Interest-scoped instance
sets)**: the "distribution edges" are not partition-specific edges to design —
they are the replication mesh's edges, which partitioning inherits as the
disjoint-interest setting of one substrate. Each former residual dissolves or
relocates: routing-table epoch consistency *is* the versioned interest-assignment
table's `routingEpoch` (realized CP-D3); repartition-window buffering *is* the
mesh's park/replay on interest reassignment (realized CP-D4); per-shard replica
targeting *is* the overlapping-interest / sharded-replication setting, not a
targeting mechanism; the scatter-gather range read *is* the union of disjoint-key
catch-ups (already stated above). The genuinely-open remainders are not partition
edges: supervision-travels-with-placement is the placement concern G-61, and
bulk-rebalance / resharding triggers / per-key attention routing are the economic
layer G-62 — both cited from those gaps, neither owed by this section.

## Tag continuity across epochs, restart, and swap

Four tag-algebra rules govern replication, RESTART, instance swap, and
compaction. They are decided design (93 I-14, I-22, I-27, 96 E3.7);
`[24-TAG-02]` is implemented (W2.1), `[24-TAG-04]` is implemented (its del-dot
and every-tag discard rule landed with computenet-v2ka; its re-admission fence
with computenet-pay7 — see that bullet), and `[24-TAG-01]` and `[24-TAG-03]`
are not.

*(The ⚠ EARS-GAP that used to stand here asked a spec editor with fuller
context to confirm or retract the `[24-TAG-*]` ids, on the suspicion that the
blanket "unimplemented" was stale. **Answered (D-C12): the suspicion was
right, for `[24-TAG-02]`.** The kernel's RESTART supervision path mints a fresh
per-epoch `sourceId` per outlet, restores, and emits the `ReBaseline`
supersession notice; `TagState.applyReBaseline` is the consumer half this
section states, drop-then-merge-then-fence, exactly as written. So the ids are
**confirmed, not retracted** — `[24-TAG-02]` is boundary-checkable today, and
its drop-and-merge half is exercised by the `21-REBASE-01` scenario (21). It
remains a *coverage-gap* row in the concordance rather than a covered one: no
scenario yet drives the dead-lane half (a late delta stamped with a superseded
`sourceId`, which a script has no verb to inject), so nothing claims to cover
the whole rule. Kernel `RestartReBaselineTest` exercises that half internally.
`[24-TAG-01]`'s verbatim-tag-travel rule and
`[24-TAG-03]`'s non-idempotent-swap rule are still decided-but-unimplemented:
the landed shadow-promotion fallback remains silent (see the third bullet), and
that is what the per-rule notes below now say instead of one blanket line.)*

- **Tags are data, never re-minted for received state** (decided in 93 I-14
  Rule S3). A genuinely new local add mints its tag under the cell's
  current source epoch; thereafter the tag travels **verbatim** — copied
  unchanged by gossip (40/42), by `Stateful.snapshot()`, by
  `StateMigrating.importFrom` (50/53), and by state-as-delta-from-empty
  catch-up (21). A cell MUST NOT re-mint tags for state it received or
  imported. `[24-TAG-01]` A genuinely new local add SHALL mint its tag
  under the cell's current source epoch; thereafter that tag SHALL travel
  verbatim through gossip, `Stateful.snapshot()`, `importFrom`, and
  state-as-delta-from-empty catch-up — a cell MUST NOT re-mint a tag for
  state it received or imported (Ubiquitous). This is the replication-level complement of the landed
  operator-level tag-hygiene rule (`MintedTags` above): derived output
  mints fresh tags; relayed or imported state preserves them. Outlet-counter
  durability is optional — a fresh epoch on recovery is the always-correct
  default (`(sourceId, counter)` is never reused by construction); a
  durable host MAY persist the counter high-water purely to preserve wave
  continuity, never as a correctness dependency. The wave-side complement
  (93 I-14 Rule S4) — a `Replicable` cell's post-merge re-emission
  *originates* a fresh wave per replica, convergence riding the tags.
- **Generational supersede** (decided in 93 I-22). RESTART is decided as
  restore-the-freshest-checkpoint plus a generation-stamped `ReBaseline`
  notice over the ordinary catch-up path — never a bare local rollback. The
  consumer half lives in this file's tag algebra: on receiving
  `ReBaseline(source, supersedes, state, supersede = true)` a convergent
  consumer MUST (a) drop every live tag from the listed superseded sources
  that the re-baseline does not re-assert, (b) apply `state` by ordinary
  tag-union merge, and (c) fence the superseded source ids as dead lanes,
  rejecting any late delta stamped with a dead `sourceId`. `[24-TAG-02]`
  WHEN a convergent consumer receives
  `ReBaseline(source, supersedes, state, supersede = true)`, it MUST drop
  every live tag from the listed superseded sources that the re-baseline
  does not re-assert, apply `state` by ordinary tag-union merge, and fence
  the superseded source ids as dead lanes — rejecting any later delta
  stamped with a dead `sourceId` (Event-driven). Tags are
  source-scoped, so the retraction removes only the reverted producer's
  lost contribution — healthy peers' tags survive — and the rule composes
  with multi-writer merge; `supersede = false` (pull-merge) retracts
  nothing, forward idempotent merge only. *(Implemented, W2.1; C-12 resolved
  in D-C12. The host's RESTART branch mints the fresh epochs and emits the
  supersession; `TagState.applyReBaseline` is (a)/(b)/(c) above — drop the
  un-reasserted tags of the superseded sources, union-merge the re-asserted
  state, then fence those sources as dead lanes — and `UnionSetCell` routes an
  inbound re-baseline through it rather than through the ordinary fold. The
  "bare rollback" reading recorded here was of the M3.5 prose in 30/31 rule 5,
  not of the code. The drop-and-merge half is exercised by the `21-REBASE-01`
  scenario (21); the dead-lane half has no scenario verb yet, so this id stays a
  coverage-gap row. The `supersede = false`
  arm is specified and honoured by the fold but never selected by the landed
  host, which always restarts push-authoritative — a G-43 residual, not part of
  this rule.)*
- **Swap handoff tiers are typed by merge class** (decided in 93 I-27). An
  instance swap's catch-up-fallback tier (discard the incumbent's snapshot,
  fresh source id, downstream re-baselines) is sound only for cells whose
  catch-up is idempotent against existing downstream state under a
  source-identity change: the tagged set family and complete-value scalars.
  Cells whose merge is non-idempotent across source identity — the counters
  — MUST hand off by state transform (`restore`/`importFrom`) with source
  continuity (the candidate adopts the incumbent's outlet `sourceId` +
  counter high-water): a fallback re-baseline under a fresh source would
  double-count the incumbent's already-delivered contribution (§Established
  pattern). A fallback swap MUST announce its fresh source via the I-22
  `ReBaseline` supersession notice — `[24-TAG-03]` IF a cell's merge is
  non-idempotent across source identity (the counters), THEN its swap
  handoff MUST use state transform with source continuity (the candidate
  adopts the incumbent's outlet `sourceId` and counter high-water) rather
  than the catch-up-fallback tier, and any fallback swap MUST announce its
  fresh source via the `ReBaseline` supersession notice (Unwanted
  behavior) — the landed shadow-promotion fallback
  (50/53) is silent: the candidate emits under its own fresh sourceId with
  no supersession signal. The drain-window export snapshot is the same
  `Stateful.snapshot()` that G-25 journals — one capture serves the
  handoff, the rollback checkpoint, and the journal.
- **Compaction below the stable frontier** (decided in 96 E3.7; partly built
  — the del-dot landed with computenet-v2ka, the re-admission fence is open
  under feature computenet-9sm.6, OR-map half computenet-9sm.8).
  `[24-TAG-04]` WHEN a convergent cell compacts its tag state, it SHALL
  discard a `dels` entry only if **every** tag in that entry — the covered
  add-tags **and the del-dot the remove minted** — is at or below the stable
  frontier (`[42-WM-05]`) at the moment of discard, and IF a later delta,
  baseline or catch-up carries a discarded tag, THEN the cell SHALL NOT
  re-admit it as new information (Event-driven / Unwanted). A remove SHALL
  mint a **del-dot** from the removing cell's own tag-source counter, carry it
  in the `dels` entry beside the tags it covers, and fold it into the
  per-origin delivered frontier as an ordinary tag, so that a remove is
  something a replica can be observed to have DELIVERED.
  Compaction rides the G-25 checkpoint path, never the emission hot path; a
  `StateRequest(since)` that asks for state below the compaction floor is
  answered with full state rather than a since-delta (E3.7, 40/42
  §Scatter-gather pull `RetainedFrontiers`), and a since-filtered reply SHALL
  ship a `dels` entry whole or not at all, never splitting the dot from the
  tags it covers.

  **Superseded 2026-09-06 by computenet-v2ka** — the sentence that stood here,
  verbatim: *"Stability, not local delivery, is the trigger: reclaiming at the
  locally delivered frontier can resurrect a removed element on some schedule
  (96 E3.5's control), where reclaiming at the stable frontier cannot, because
  every covering replica has already converged past it."* Its second half was
  **false for the shipped tag algebra and is the defect this rule was amended
  to close**. `SetCell.foldDelivered` was fed only from `add()`'s local mint
  and `applyRemote()`'s `newAdds`; `remove()` minted and folded nothing. So a
  del-tag at or below the stable frontier certified that every open member had
  delivered the ADD, never the REMOVE — and a member holding the add while
  missing the remove re-shipped add-only state at heal into replicas that had
  already reclaimed the tombstone. MEASURED across six independent 200-seed
  sweeps before the fix (`doc/kernel-lane-findings.md` `## KE3-GC`) and
  deterministically by `CompactionTriggerPinTest`'s `P2 LOST del`. The
  del-dot restores the sentence's intent by making "converged past it" mean
  past the remove.

  **The second clause landed 2026-09-06 with computenet-pay7: a causal-context
  re-admission fence.** Before it, nothing stopped a duplicated, reordered or
  replayed frame carrying a discarded tag from being re-admitted as new
  information — novelty is `tags − adds[e]`, and a discarded tag is absent from
  `adds[e]` again. `SetCell.compactBelow` now records the exact dots it
  discards, and `applyRemote` subtracts that recorded set from the novelty it
  computes on **both** lanes. The shape is load-bearing and is stated as a
  requirement, not an implementation note: the retained set SHALL be the set of
  discarded dots, and SHALL NOT be a per-source high-water floor.
  computenet-v2ka **measured that a floor does not close this safely** — it
  drives the resurrections to zero and fences *live* add-tags in the process,
  leaving 31-33 of 200 seeds with permanently diverged memberships against a
  no-reclaimer control floor of 2-5, in each of three variants (raised to the
  discarded counter; capped at the replica's own delivered prefix; and that cap
  with the delivered frontier restricted to tags the replica holds). Below any
  floor a source has minted, reclaimed and live counters interleave, and a
  high-water cannot tell "this tag was reclaimed" from "this tag is below a
  position I reached".

  **A fence that only drops the frame is also not safe, and that is the second
  measured finding.** A replica whose frame is fenced is one that still holds
  the add-tag LIVE with no tombstone for it; dropping its frame silently leaves
  the element live there and absent here for ever. MEASURED: fencing alone took
  the sweep's STABLE resurrections to 0 and its membership divergence from 3 of
  200 to **30 of 200** — the same order as the rejected floor. So a cell that
  fences an add-tag SHALL answer it with a covering `dels` entry naming exactly
  that tag: the fence is the evidence the tag was covered by a remove observed
  delivered, so the covering entry is reconstructible from the tag alone, and
  no new del-dot is minted because no new remove occurred. With the repair the
  sweep reads 0 resurrecting and 5-8 membership-diverging of 200 against a
  no-reclaimer control of 1-4 in the same runs
  (`doc/kernel-lane-findings.md` `## KE3-GC-DEL-DOT`).

  **Cost, stated where the rule is.** The retained dot set is compressed as
  per-source contiguous counter runs, so reclamation is a real reduction in
  retained state and **not a bound**; a bounded form needs epoch hygiene
  (G-42, below).

⚠ GAP (G-42): Epoch source-ids and restart generations accrete unboundedly:
OR-set/PN source columns, stale glitch-free partial-wave buffers, and
frontier entries for vanished epochs are never reclaimed, and
counter/generation continuity across migration and host failure is
unpinned. Proposal: safe reclamation of provably-superseded epochs
(compaction riding G-25 checkpoints), frontier GC for orphaned partial
waves triggered by relink-driven recompute, a concrete migration-payload
field carrying the outlet counter high-water, durable-counter batching kept
off the emission hot path, and generation derivation from the journal
high-water (fresh high base on non-durable hosts) so post-restart tags
never alias (93 I-14/I-22/I-3/I-7).

⚠ GAP (G-43): RESTART's restore-freshest-checkpoint + generation-stamped
re-baseline leaves precedence and cost open: supersede vs concurrent
multi-source remove, re-baseline cost under wide fan-out, hybrid push/pull
direction, poison-write loops, and the recovery-cell pattern are
unstandardized. Proposal: state a supersede-vs-remove precedence with a
generative convergence test; bound the push-authoritative re-baseline
(diff-against-last-acked / delta-since-generation); define the per-cell
direction policy for hybrid derivation+owned-state cells; add a
poison-write escape (dead-letter the replaying write after N RESTARTs);
standardize the deadLetter→requestState recovery cell — replicated cells
re-baseline from mesh peers, resolving the RESTART-within-replication
question carried by four earlier challenges
(93 I-22/I-2/I-7/I-18/I-19/I-25).

⚠ GAP (G-49): The two-phase swap + state-transform design is by-convention
at its load-bearing spots: non-vetoing commit, contract-schema identity
across builds, source continuity under representation change, fallback
soundness, hidden-state cells, coupled-flow windows, and
rollback-after-retire. Proposal: KSP-distinguish admission policies
(Phase 0) from setup-only commit hooks; a contract-version discipline
guarding importFrom schemaVersion against same-FQN hash collisions; pin
sourceId adoption vs fresh-source reset when a candidate changes delta
representation (drain-convergence fallback otherwise); a fallback-tier
soundness marker refusing catch-up for non-idempotent cells; an explicit
non-promotable declaration for hidden-state cells; a retention window for
the retired incumbent's export snapshot with rollback-by-journal-reversal
semantics pinned against 53/24; and a transform-correctness generative
harness (93 I-11/I-27/I-21).

## Durability spectrum

ADR 1 §3 requires in-memory / durable / hybrid state.

*(G-25 resolved, M10; refined per-cell CP-C1)*: durability is a **per-cell**
concern. A host takes a `journalFor(cellRef)` selector naming the write-ahead
`Journal` each cell's accepted invocations tee to — or `null` to make that
cell **volatile** (never journaled, never replayed). `[24-DUR-01]` A host's
`journalFor(cellRef)` selector SHALL name the `Journal` a cell's accepted
invocations tee to, or `null` to make that cell volatile — never journaled,
never replayed (Ubiquitous). The whole-host `Journal`
is the degenerate case: the constant selector returning that one journal for
every cell, byte-identical to the pre-CP-C1 tee. For a journaled cell the host
appends every accepted invocation **as a wire frame** (the same `WireCodec`
encoding that crosses the network: a journal is a bridge to disk) before
staging it; recovery rebuilds the graph, then `recoverFrom` restores the
latest checkpoint's `Stateful` snapshots and replays the frame tail through
the ordinary decode path. `[24-DUR-02]` On recovery, the host SHALL rebuild
the graph, then `recoverFrom` SHALL restore the latest checkpoint's
`Stateful` snapshots and replay the frame tail through the ordinary decode
path (Ubiquitous). Because the write path is per-cell, a journal only
ever holds its own cells' records, so replaying it restores exactly those
cells and re-delivers nothing to a co-hosted volatile cell — recover each
distinct journal once. `[24-DUR-03]` A journal SHALL only ever hold its own
cells' records, such that replaying it restores exactly those cells and
re-delivers nothing to a co-hosted volatile cell (Ubiquitous). `checkpoint` is keyed the same way: it snapshots only
the cells teeing to the passed journal and compacts that journal atomically;
tombstone and PN-slot growth compact with it (`MixedDurabilityTest` proves the
per-cell scoping; its control shows a constant selector restores every cell).
Cells stay oblivious — with one honest exception:
**replay-stable identity**. A recovered instance must re-mint the identities
the network already observed, so set tags and PN source slots derive from
the cell's ref (never `randomUUID`) and the tag counter is snapshot state.
Random identity + replay = resurrected removals and double counts
(`CrashRecoveryTest` proves both directions). `[24-DUR-04]` A recovered
instance MUST re-mint the identities the network already observed — set
tags and PN source slots derived from the cell's ref (never `randomUUID`),
tag counter restored as snapshot state — such that replay does not
resurrect removals or double-count (Ubiquitous). Remaining with a trigger:
journal segmentation/rotation and the disk-overflow mailbox (33) — the
first workload where one fsync'd file hurts.

**Boundary of the landed mechanism** (decided in 93 I-7): un-suppressed
replay through the ordinary decode path is safe exactly for the
replay-stable idempotent vocabulary above — ref-derived identities,
idempotent merges, and anti-entropy/catch-up dedup absorb the
re-emissions. For `Effectful` sinks *(G-59 resolved, W2.6, closes C-9)*: an
`Effectful` inlet journals a processed-frontier — the last applied
`(sourceId, counter)` per inlet — consulted by both `recoverFrom` replay and
post-recovery live delivery; an invocation at or behind the frontier is
suppressed-emission (dropped as already-acted) instead of re-driving the
sink. `[24-DUR-05]` IF an invocation at an `Effectful` inlet is at or behind
that inlet's processed-frontier (the last applied `(sourceId, counter)`),
THEN it SHALL be suppressed — dropped as already-acted — rather than
re-driving the sink, whether encountered during `recoverFrom` replay or
post-recovery live delivery (Unwanted behavior).

`[24-DUR-05]` is written unconditionally, and an **admission rule** is what
makes it evaluable unconditionally *(KFX-16 resolved, `computenet-yh6.1.3.5`)*.
The frontier is keyed on `MessageContext.timestamp`, so a frame carrying no
`MessageContext` has no position on it and the antecedent could not be
evaluated for such a frame at all — the externally-driven root case, an
`Effectful` cell driven directly by an outside caller. The decision is that an
`Effectful` cell is **not directly manipulable by a caller that cannot supply
frontier information**: driving the graph directly is itself an act that has a
frontier — a stable per-actor source id (a user, a machine, a connector
principal) plus a monotonic counter over that actor's actions, stamped onto the
frame *before* the journal tee so replay carries the identical position.
`[24-DUR-06]` IF a `PORT_API` invocation arriving at an `Effectful` inlet
carries no `MessageContext` — hence no `(sourceId, counter)` position on that
inlet's processed-frontier — THEN it SHALL be refused as undeliverable: not
delivered to the sink, its exclusive payloads discharged and the refusal
accounted, rather than acted on without a position (Unwanted behavior).
Management-plane and protocol-plane traffic is unaffected — the rule binds the
`PORT_API` data plane only — as is any non-`Effectful` cell, for which a
spontaneous contextless call remains legitimate.

This **narrows 93 I-7's external-idempotency ceiling** rather than living under
it: an external effect driven by an *unidentified* external frame is no longer
possible, so what remains under that ceiling is un-suppressed replay of the
replay-stable idempotent vocabulary, not unidentified external drives. Two
limits are honest to state next to the rule. **Minting and persisting the actor
identity is the connector ingress's (CON1's), not the kernel's**: the kernel
supplies the stamping seam (`civictech.cell.host.ActorIngress`) and enforces
the refusal, and a caller that passes a *fresh* id per process is admitted and
correct across replay but opens one frontier lane per session — bounded by
actors only if the actor id is actually stable. And enforcement is at
**delivery**, the point where the target cell's `Effectful`-ness and the frame's
context are both known: link admission cannot pre-empt it, because every
*linked* producer stamps a context by construction (`FanOutlet` mints one for a
spontaneous emission) and the contextless producers are direct proxy drives,
which admit no link to check. A refused frame has already been journaled by the
intake tee, so replay meets it again and refuses it again — the refusal is
idempotent, not a one-shot admission gate.

**Catch-up baselines at an `Effectful` inlet** *(KFX-BASELINE resolved,
`computenet-yh6.1.3.4`; decided 2026-08-10, with 93 I-24)*. `[24-DUR-05]` reads
`MessageContext.timestamp` alone, and a frame may also carry a
`MessageContext.baseline` — non-null exactly on a catch-up baseline: the I-24
pull baseline that answers a late-joining consumer's `StateRequest`, and PN-2's
replay stamp, which marks a replayed frame as catch-up rather than as a live
wave. The decision is that a newly-joined `Effectful` cell **fires for the state
it caught up to** and responds to deltas individually from then on: one rule for
every `Effectful` cell, not a per-cell option. Suppressing instead would leave a
late-joining notifier permanently blind to everything that existed before it
linked, with no protocol by which it could ever learn — a silent, unrecoverable
omission, where firing is loud and bounded (a burst proportional to existing
state, on a path an operator chose by linking). `[24-DUR-07]` WHEN an invocation
carrying a `MessageContext.baseline` arrives at an `Effectful` inlet, THEN the
sink SHALL act on it, and that invocation's timestamp SHALL NOT advance the
inlet's processed-frontier (Event-driven). The second half was never really
open: a baseline is causally anchored at the stamped link-install event and is
never a wave position, so advancing a wave-position high-water from it can
subsequently suppress genuine live frames from that source whose counters sit
below the baseline's.

Those two halves together open a hole that the `Effectful` sink closes **with
its own mechanism**: because a baseline firing advances no frontier, a journaled
baseline frame would have nothing to suppress its own `recoverFrom` replay, and
a crash after a catch-up join would re-fire the entire catch-up. `[24-DUR-08]`
IF an invocation at an `Effectful` inlet is at a position that inlet has already
discharged as a baseline firing, THEN it SHALL be suppressed — dropped as
already-acted, its exclusive payloads discharged — rather than re-driving the
sink, whether met during `recoverFrom` replay or post-recovery live re-delivery
(Unwanted behavior). The discharge record is durable, is written on the same
per-cell journal tee the frame itself rode, and survives checkpoint compaction;
it is deliberately **the sink's own state, separate from the wave frontier**, so
no obligation is pushed onto producers, ingress, or the catch-up protocol. It
records an **exact position**, not a per-source high-water: a high-water would
suppress live frames below the baseline's counter, which is exactly what
`[24-DUR-07]` forbids, whereas an exact position can only ever match a
re-delivery of the very frame that fired. Keying on `(sourceId, counter)` rather
than on the baseline's link-install anchor also survives an anchor recurring —
two shards answering with equal frontiers share an anchor but never a position,
since 93 I-14 Rule S1 forbids re-issuing a pair.

Both baseline kinds take this one branch, so replay-versus-pull never becomes an
observable distinction at an effect boundary, and PN-2 keeps `[24-DUR-05]`
unchanged: a replayed frame the sink already acted on live is at or behind the
restored frontier and is suppressed, while a journal-tail frame the sink never
acted on fires — the effect catch-up half of the rule.

**Bounding the discharged-baseline set** *(decided 2026-08-17,
`computenet-yh6.1.3.4.2`)*. Because a baseline firing advances no frontier, the
only compaction available — drop, at checkpoint, the positions the
processed-frontier already covers — never fires for a source lane that emits
baselines and no live frames after them: N-shard pull replies, repeated link
installs against a state-holder that only answers `StateRequest`s, and repeated
crash recoveries firing journal tails each leave permanent entries, in memory
and in every subsequent checkpoint blob. The set is therefore **capped per
inlet** (1024 positions), evicting the frontier-covered positions first — those
are redundant by construction, since the frontier test is applied before the
discharge test — and then oldest-discharge-first.

The **loss mode is explicit**: an evicted position is no longer suppressed, so
if that exact frame is re-delivered — an upstream retransmit of a baseline
reply, or a journal-tail replay of it — the sink **re-fires the effect for it**.
That is a duplicate external effect, bounded to positions older than the inlet's
last 1024 baseline firings, and it sits under 93 I-7's stated
external-idempotency ceiling. The direction is deliberate: eviction only ever
*shrinks* the suppression set, so no live frame can become collaterally
suppressed by it and `[24-DUR-07]` cannot be re-broken by the bound.

Two alternatives were rejected on that criterion. A **per-source contiguity
collapse** — folding a run of consecutive discharged counters into a per-source
high-water — is genuinely lossless, since contiguity means every counter at or
below the high-water was itself discharged and Rule S1 forbids re-issuing a
pair. It is not a bound, though not because the growth case is always sparse —
a lane that only ever answers `StateRequest`s stamps consecutive counters, so
that case *is* a contiguous run and would collapse. What the collapse cannot
bound is the **source dimension**: a `FanOutlet`'s `sourceId` is minted per
outlet instance and re-minted on every epoch bump, so the distinct source lanes
one inlet sees over a long life — N shards, each remote peer, each restart or
replica spawn — are themselves unbounded, and one high-water each still grows
without limit. A collapse is therefore a legitimate *complement* to the cap
(it would postpone eviction on a single lane) and never a replacement for it;
it is not implemented here. Consulting such a high-water only for
baseline-marked frames would bound it, and is what makes it wrong — a baseline
at an undischarged counter below the high-water would be suppressed without
ever having fired, which is the silent unrecoverable omission `[24-DUR-07]`
chose firing over, and it would make replay-versus-pull observable at an effect
boundary. A **retention
horizon tied to source-lane liveness** is the semantically exact answer but is
likewise not a bound (a live lane retains forever), and it needs link-teardown
knowledge the sink does not have and cannot acquire without pushing an
obligation back onto the catch-up protocol — precisely what `[24-DUR-08]` was
designed to avoid.

The figure 1024 is a judgement, not a measurement: it is far above the
units-to-tens of baselines a shard fan-out or link-install burst produces, and
holds the per-inlet checkpoint cost in the low hundreds of kilobytes. No
workload has been profiled against it.

**The write-ahead window is at-least-once, and that is the decided guarantee**
*(decided 2026-08-25, `computenet-xxeo`; measured by `computenet-umx.1.6`'s BS-2
sweep at pinned seed 101)*. A hosted frame is journaled at intake, delivered on a
later scheduler task, and the frontier advance recording the delivery is journaled
beside it — so an `Effectful` sink's external act happens *between* two journal
records, and a crash inside that window leaves the frame durable and the "already
acted on" advance not. Replay re-delivers the frame, the restored frontier does not
cover it, and the sink acts a second time. `[24-DUR-05]` is not violated by that: its
antecedent is "at or behind the processed-frontier", and a position whose advance
never became durable is not on the restored frontier at all. What the window bounds
is the rule's *scope* — exactly-once effect delivery is exactly as durable as the
frontier journal and no stronger — and the boundary is stated here rather than left
to be rediscovered.

The alternative orderings were considered and rejected on the criterion
`[24-DUR-07]` already decided this class of trade on: **a duplicate is loud and
bounded, a suppression is a silent unrecoverable omission**. Journaling the advance
*before* invoking the handler (at-most-once) trades this re-fire for a crash in the
mirrored window leaving a durable "already acted on" record for an effect that never
happened — the sink is then permanently blind to that position with no protocol by
which it could learn, which is precisely what `[24-DUR-07]` chose firing over and
what `[24-DUR-08]`'s eviction bound keeps choosing ("eviction only ever *shrinks*
the suppression set"). A two-phase construction closer to exactly-once needs the
external effect and its dedupe record to commit together; the kernel seam cannot
express that for an arbitrary external world, and that is 93 I-7's stated
external-effect idempotency ceiling — "R8 dedups re-delivery only if the sink holds
a durable processed-frontier *and* the external world accepts idempotent
re-delivery" — under which this window sits, alongside the evicted-baseline
duplicate above. `[24-DUR-09]` IF a host crash lands between an `Effectful` sink
acting on an invocation and that inlet's frontier advance for it becoming durable,
THEN the replayed invocation SHALL be delivered to the sink rather than suppressed
— the effect is at-least-once across that window and the duplicate is bounded to the
one delivery the crash caught in flight (Unwanted behavior).

Two limits are honest to state beside it. The bound is **per crash, not per run**: a
host that crashes repeatedly can duplicate one position per crash, each time a
different in-flight frame. And the window is not closable by narrowing it — shrinking
the gap between the effect and its advance reduces the probability of landing inside
it and changes nothing about the guarantee, so no fsync placement, batching change or
scheduler ordering should be read as retiring `[24-DUR-09]`.

The decided journal classification still diverges from the landed
tee: 93 I-7 journals only `PORT_API` data plus topology events, while the
shipped journal appends every intake frame (management included) and does
not journal topology at all — the graph is rebuilt out-of-band before
`recoverFrom`.

⚠ GAP (G-59): The M10 journal replays intake frames, which is sound only
for deterministic, input-driven cells: wall-clock/random logic,
spontaneously-emitting sources, Effectful sinks without idempotency keys,
glitch-free partial-wave buffers, and cross-host recovery-frontier drift
are unhandled. Proposal: a determinism marker/lint forcing
non-deterministic cells to output-mode journaling (or a captured-entropy
WAL record); an emitted-delta log format for sources and a
processed-frontier shape for Effectful sinks with a generative
recovery-dedup test; document the external-idempotency ceiling as a stated
limit; verify deterministic replay reconstructs partial-wave buffers or
include them in Stateful.snapshot; and evaluate an opt-in coordinated
checkpoint for tightly-coupled subgraphs (never global, per P4) (93 I-7).

⚠ GAP (G-46): Exclusive (Owned/Leased) payloads have no defined story off
the happy path: a payload parked-but-unsnapshotted at crash is lost with no
stated at-most-once contract, and the DeadLetter envelope for
freezing/serializing/redacting them is unspecified. Proposal: state the
sender-durability contract that makes crash loss at-most-once acceptable
(or require the producing host to be durable), and pin the DeadLetter
envelope: Owned → move-by-serialize at capture, Leased → released, with a
redaction rule for non-serializable payloads — mergeable parked traffic is
already covered end-to-end by the M10 journal + anti-entropy pair
(93 I-7/I-22/I-12).

G-54 core is landed (W4.1): `BoundaryPolicy.disclosure` filters a data
cell's emitted deltas — catch-up and live uniformly — once exposed through
a `mediateOutlet()` membrane crossing (40/43, 20/21 §Pull). Residual, still
open: capability hand-out/revocation for exposed ports and taps (tearing
down *live* links, not just refusing new ones); management-plane authority
for remote graph mutation across a bridge; composition of disclosure/
integrity across nested/transitive membranes and multi-hop relays; and an
at-rest encryption stance for durable journals and parked/overflow state
(93 I-28 §8).

## Durable replay of a mid-graph data cell (PN-2)

A journaled data cell is recovered by replaying its write-ahead frames through
the ordinary intake (24 durability, M10.1). PN-2 (22 §Recovery is a baseline)
makes that replay a **baseline** rather than a live wave: a mid-graph cell whose
frames carry a non-null wave context re-emits its restored deltas flagged
`MessageContext.baseline`, so a downstream glitch-free join installs them as arm
state and never waits for a volatile sibling arm to replay the same wave.
`[24-REPLAY-01]` WHEN a journaled mid-graph cell's replayed frames carry a
non-null wave context, it SHALL re-emit its restored deltas flagged
`MessageContext.baseline`, such that a downstream glitch-free join installs
them as arm state without waiting for a volatile sibling arm to replay the
same wave (Event-driven). This
is what lets a durable data cell feed one arm of a fork-join diamond whose other
arm is volatile — the exchange demo's previously-unwritten "journal only
context-free roots" invariant is retired: mid-graph journaled cells recover
without stalling the join. Tag continuity across the replay is unchanged — tag
sources are derived from the cell ref (M10.1), so a recovered instance re-mints
the exact tags the network already observed; the baseline marks the *wave-plane*
disposition of the replay, not the *state-plane* merge, which stays ordinary
observed-remove/tag-set union.

## The shard is a dataflow cell (PN-4)

`ShardCell` — the hosted interest-scoped instance of a partitioned logical id
(§Partitioned state, 42 §Interest-scoped instance sets) — is a full dataflow
cell, not a write-only sink. It is `Stateful` and `Replicable`:

- **`Stateful` snapshot = `(TagState, interest, assignedEpoch)`.** A shard's
  recoverable state is not just its tags but the key-`Interest` it holds and the
  routing epoch it has adopted. Interest is *snapshotted state*, never re-read
  from the constructor on recovery — a checkpoint-restored shard keeps the range
  it holds instead of resurrecting the range it shed. `[24-SHARD-01]` A
  `ShardCell`'s `Stateful` snapshot SHALL be `(TagState, interest,
  assignedEpoch)`; `interest` SHALL be snapshotted state, never re-read from
  the constructor on recovery, such that a checkpoint-restored shard keeps
  the range it holds instead of resurrecting the range it shed (Ubiquitous).
- **Outlet + `deltaInlet` + `StateRequest`.** Every membership change (a routed
  slice, a gossip merge, a shed) re-emits on the outlet, so a shard composes as
  an ordinary source: partitioned+durable, partitioned+replicated (overlapping
  interest, the sharded-replication setting), and pull-from-partitioned are
  buildable rather than merely untested. The `StateRequest` handler answers a
  pull with an interest-scoped, `since`-filtered state-as-delta stamped as a
  catch-up baseline (the `SetCell` pull contract, 20/21 §Pull) — never a wave.
  `[24-SHARD-02]` Every shard membership change SHALL re-emit on the outlet;
  the `StateRequest` handler SHALL answer a pull with an interest-scoped,
  `since`-filtered state-as-delta stamped as a catch-up baseline, never a
  wave (Ubiquitous).

**Recovery of a partitioned node.** The router (`PartitionedShardSet`) holds no
durable state of its own: the routing table *is* the shards' interests and the
epoch *is* their max-register. After a crash it is recomputed by
`rebuildFrom(shards)` — asking each restored shard what interest and epoch it
holds. `[24-SHARD-03]` The partitioned router SHALL hold no durable state of
its own — the routing table SHALL be recomputed after a crash by
`rebuildFrom(shards)`, asking each restored shard what interest and epoch it
holds (Ubiquitous). A shard's shed is invisible to its own WAL (the router narrows interest
by a direct in-process reassignment, not a routed frame), so a shard reconstructed
under its **current** (post-repartition) interest drops its pre-repartition
frames for the range it lost on replay; reconstructed under its constructor
`initialInterest` it re-admits them and the moved range resurrects on two shards
at once (the executable control). `[24-SHARD-04]` WHEN a shard recovers, it
SHALL reconstruct under its current post-repartition interest, dropping
pre-repartition frames for any range it shed (reconstructing under the
constructor's `initialInterest` instead resurrects the moved range on two
shards at once — rejected, executable control) (Event-driven). This composes with the checkpoint guard
(PN-0b): a checkpoint captures the shard's `Stateful` snapshot and compacts the
WAL to it; before PN-4 a write-only shard contributed no snapshot, so the guard
is what kept the compaction from truncating the frames that were its only
recovery.

The router's own interest combinators (moving-range, union, complement during a
repartition flip) are expressed in the closed serializable `Interest` algebra
(PN-3a) rather than anonymous predicates: the moving range is
`Complement(⋃ᵢ (oldᵢ ∩ newᵢ))` — a key stays put iff the same shard admits it in
both tables — honest and wire-safe, no longer a predicate whose `overlaps`
unconditionally lied. `[24-SHARD-05]` The moving range during a repartition
SHALL be `Complement(⋃ᵢ (oldᵢ ∩ newᵢ))` in the closed serializable `Interest`
algebra: a key stays put iff the same shard admits it in both the old and new
routing tables (Ubiquitous).
## Interest-scoped aggregate deltas (PN-3b)

`MapDelta` — the delta of `MapCell`, `GroupByCell`, and CP-G1's replicable
`MergeableGroupByCell`, including its per-key `merge` path — implements
`Scoped` over the *map key* space, the same per-emission interest filter
`SetDelta` already carries over its element space (42 §Interest-scoped
instance sets). `[24-SCOPED-01]` `MapDelta` SHALL implement `Scoped` over the
map key space, the same per-emission interest filter `SetDelta` carries over
its element space (Ubiquitous). The gossip linker (`Replication.scopeToInterest`) slices an
aggregate delta to a partial-interest target so only the admitted group keys
ride the link: `within(Interest.Total)` returns the delta whole (the
replication default, so non-opting graphs are unchanged), a partial `Interest`
restricts `puts`/`removals` to the admitted keys, and an empty slice returns
`null` so the emission never rides at all. `[24-SCOPED-02]` The gossip linker
SHALL slice an aggregate delta to a partial-interest target such that
`within(Interest.Total)` returns the delta whole, a partial `Interest`
restricts `puts`/`removals` to the admitted keys, and an empty slice returns
`null` so the emission does not ride at all (Ubiquitous). This is what lets a `Replicable`
aggregate be interest-*sharded* rather than replicated whole to every peer;
before it, a non-`Scoped` `MapDelta` rode the entire map to a partial-interest
target (over-delivery). `SetDelta` semantics are untouched.
