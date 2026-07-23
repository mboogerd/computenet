# OR-Map — a tagged, convergent map stream for multi-writer keys

## Origin

`MapDelta` is the framework's map-stream contract, but it carries a documented
convergence limit marked **G-23** right in the source
(`MapCell.kt`): "unlike `SetDelta`, map deltas carry no causal tags — concurrent
puts to the same key resolve by arrival order and are not replica-stable." Both
`JoinCell` and (the proposed) `CombineLatestCell` inherit this. Finding **F-1**
notes the FuseCell gap "relates to the G-23 OR-map deferral."

The tiering demo dodges this because its map producers (`tierAvg`, `prefAvg`,
`fused`) are single-writer cells. But the moment you want **replicated
collaborative tiering** — the same multi-JVM convergence story the shopping and
agora demos already test — the map-carrying edges cannot converge under
concurrent per-key writes from different peers. `SetDelta` converges (tags);
`MapDelta` does not. That asymmetry is the ceiling on distributing any
map-shaped feature.

## What it is

A tagged map-delta type and matching cell — an **OR-Map** (observed-remove map
with last-writer-wins values) — that gives per-key puts the same causal-tag
treatment `SetDelta`/OR-set already give element membership, so concurrent
same-key writes from multiple replicas converge deterministically instead of
resolving by arrival order.

## Why it fits the framework

- It closes a gap the framework itself has already named and deferred (G-23), in
  the framework's own idiom: causal tags for convergence (spec 42, the same
  machinery `UnionSetCell`/`SetDelta` use).
- It preserves "in-process and remote paths preserve the same observable
  semantics" (AGENTS.md core invariant): today a map edge behaves differently
  under replication than a set edge; an OR-Map makes them uniform.
- Value conflict resolution is LWW by an explicit causal stamp (not wall clock),
  keeping determinism and replica-stability — consistent with the framework's
  refusal of arrival-order aggregates (`Aggregator` contract).
- It is additive: introduce `TaggedMapDelta` alongside `MapDelta` so existing
  single-writer cells are untouched and wire compatibility holds (additive
  encoding, AGENTS.md).

## Solution sketch

```kotlin
data class TaggedMapDelta<K, V>(
    val puts: Map<K, Tagged<V>>,     // value + causal stamp (dot/version-vector entry)
    val removals: Map<K, Set<Tag>>,  // observed tags being retracted (OR-map remove)
)

class OrMapCell<K, V> : Cell, Stateful, Replicable {
    val inlet:  Use<MapOps<K, V>>
    val outlet: Subscribe<Propagate<TaggedMapDelta<K, V>>>
    // per key: set of live (tag -> value) dots; value() = LWW by dot order.
    // A remove retracts observed tags; concurrent put re-adds a fresh dot
    // (add-wins on presence, LWW on value) — exactly OR-set semantics lifted
    // to keys, mirroring UnionSetCell's tag algebra.
}
```

Downstream map operators gain tagged variants (or a documented adapter that
projects `TaggedMapDelta` → `MapDelta` for single-consumer, non-replicated
sinks), so `CombineLatestCell`/`JoinCell` can operate over convergent maps when
replication is in play.

## Inputs / outputs

- **Input**: `MapOps<K, V>` puts/removes, as `MapCell` today, but stamped with a
  per-replica causal dot.
- **Output**: `TaggedMapDelta<K, V>` — puts carry a value + causal stamp;
  removals carry the observed tags being retracted.
- **Convergence**: two replicas that apply the same *set* of dots (any order)
  reach identical per-key values and membership.

## Acceptance criteria

- Deterministic convergence: for any interleaving/duplication/reordering of a
  fixed dot set across replicas, all replicas converge to the same map (the
  property the existing multi-JVM convergence tests assert for sets, now for
  maps).
- Add-wins presence, LWW value: concurrent `put(k, a)` on replica 1 and
  `put(k, b)` on replica 2 converge to a single deterministic winner by dot
  order (not wall clock); concurrent put vs remove → key present (add-wins).
- Duplicate delivery across a diamond dedups (no double application), matching
  `UnionSetCell`.
- `Stateful` + `Replicable`; late-join catch-up delivers current tagged state as
  one delta-from-empty.
- Backward compatible: `MapDelta` and its single-writer cells remain valid and
  unchanged; `TaggedMapDelta` is additive on the wire.
- Demo proof: a replicated `:demo:tiering` variant (two hosts, bridged) where two
  peers concurrently re-tier the same item converges to one board — the map
  analogue of the shopping list's convergence test.
