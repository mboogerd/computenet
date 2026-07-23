# Idea: consumer-side materialized views (`SetView` / `MapView` / `CountView`)

> Type: missing primitive (small, high-leverage DX) + demo op that belongs in the framework
> Origin: `:demo:slotfinder` — `SlotMembership` and the day-count fold, re-implemented per demo
> Relates to: `TagState` (internal), `SetDelta`/`MapDelta`, demo-findings **F-3**;
> the read-model layer beneath `observation-sink-materialized-edge.md`

## Origin

To turn a delta stream back into a value it can read, slotfinder hand-writes a tag fold:

```kotlin
class SlotMembership {                       // folds SetDelta<Slot> -> live Set<Slot>
    private val live = mutableMapOf<Slot, MutableSet<Timestamp>>()
    fun apply(delta: SetDelta<Slot>) {
        delta.adds.forEach { (e, tags) -> live.getOrPut(e){ mutableSetOf() } += tags }
        delta.dels.forEach { (e, tags) -> live[e]?.let { it -= tags; if (it.isEmpty()) live.remove(e) } }
    }
    fun current(): Set<Slot> = live.keys.toSet()
}
```

and a second, different fold for the per-day counts (`counts.putAll(puts); removals.forEach(remove)`).
This is exactly what the kernel's **internal `TagState`** already does — `IntersectSetCell`,
`UnionSetCell`, and `PresenceCountCell` all fold sources with it — but `TagState` is not exposed
to consumers, so every app re-derives a weaker, subtly-different version (the demo's ignores tag
identity, which is fine here but wrong for OR-set convergence). The same re-implementation shows
up across demos and is called out in F-3.

## What it is

A tiny public family of **consumer-side read models** that correctly fold the kernel's own delta
types into a materialized, queryable value — the canonical fold, shared by cells and apps:

- `SetView<E>` : `apply(SetDelta<E>)` → `current(): Set<E>` (OR-set tag algebra, the real one).
- `MapView<K,V>` : `apply(MapDelta<K,V>)` → `current(): Map<K,V>` (LWW/last-put + removal).
- `CountView<K>` : `apply(MapDelta<K, Long>)` → `current(): Map<K, Long>` (the byDay fold).

No ports, no host, no wave logic — just the fold, extracted from `TagState` and friends and made
public and tested. Anything that has a `SetDelta`/`MapDelta` in hand (an app subscriber, a test,
the observation sink) uses them instead of rolling its own.

## Why it fits the framework

- **It is already in the kernel, just private.** Publishing the canonical fold removes duplicated,
  divergent app code and guarantees apps agree with cells on what "current membership" means
  (important once tags carry convergence identity across `wire`).
- **It is the substrate the bigger ideas stand on.** `observation-sink-materialized-edge.md` and
  the `observe { }` DSL both need "given this outlet's delta type, materialize it"; these views
  are that function. Shipping them first is the smallest useful step and de-risks the rest.
- **It respects the layering.** Pure data structures with no host/transport dependency — safe to
  live in `civictech.cell.data` next to the deltas they fold, usable unhosted and in tests.
- **It nudges F-3.** Once `MapView` (upsert semantics) is a first-class read model, the
  "keyed re-valuation needs app-side bookkeeping" friction has an obvious home to grow into
  (`KeyedSetView` that retracts the previous element per key).

## Solution sketch

```kotlin
class SetView<E> {                    // extracted/derived from TagState<E>
    fun apply(delta: SetDelta<E>): Boolean   // returns whether membership changed (effective)
    fun current(): Set<E>
    fun snapshot(): Serializable ; fun restore(s: Serializable)   // reuse Stateful shape
}
class MapView<K, V> { fun apply(delta: MapDelta<K, V>): Boolean; fun current(): Map<K, V>; … }
```

`apply` returning "did the effective value change?" lets callers skip redundant broadcasts — the
demo currently broadcasts on every delta even when membership is unchanged.

## Inputs / outputs

- **Input:** a stream of the corresponding delta type, applied one at a time (any thread, but not
  concurrently on one view — document it, like the cells).
- **Output:** `current()` materialized value; `apply` returns whether the effective value moved.
- **Optional:** `snapshot`/`restore` mirroring `Stateful`, so a view can seed from a
  `StateRequest` reply.

## Acceptance criteria

- [ ] `SlotMembership` and the `DayCountHubCell` fold in slotfinder are replaced by `SetView`
      /`CountView`; behaviour is unchanged (existing `SlotFinderServerTest` / pipeline test pass).
- [ ] `SetView` agrees with `IntersectSetCell`/`UnionSetCell`'s internal membership on the same
      tagged stream (shared `TagState` core — assert via a property test over random deltas).
- [ ] `apply` reports `false` for a delta that carries only tag churn with no membership change;
      a demo broadcast guarded on that return fires strictly fewer times than today.
- [ ] `MapView` upsert + removal round-trips a `MapCell`/`GroupByCell` output stream to the same
      map a batch recompute produces, on every seed.
- [ ] Views have zero dependency on `host`/`port`/`wire`; usable in a plain unit test with no
      running host.
- [ ] `snapshot`/`restore` let a view rebuild from a single state-from-empty delta (the
      `StateRequest` reply shape), enabling the sink's late-join catch-up.
