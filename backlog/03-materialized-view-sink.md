# Materialize / ObserveCell — a first-class observation sink

**Type:** API change / DX (remove per-demo boilerplate)
**Origin:** `:demo:skillmatch` `SetHubCell`, `MapHubCell`, `SetFold`; the same
trio reappears in every incremental demo.

## Origin of the idea

Every demo terminates its dataflow at an "edge" where the browser/HTTP layer
needs current materialized state plus a change notification. skillmatch
hand-rolls this three times:

```kotlin
class SetFold<E> { fun apply(d: SetDelta<E>); fun current(): Set<E> }   // ~12 lines
class SetHubCell<E>(onUpdate: (Set<E>) -> Unit) : Cell { ... }         // ~18 lines
class MapHubCell<K,V>(onUpdate: (Map<K,V>) -> Unit) : Cell { ... }     // ~18 lines
```

Then wires six of them:

```kotlin
setHub<Match>(refs.matches) { matches = it }
mapHub<String, Long>(refs.required) { required = it }
// ...four more...
```

This is pure plumbing: fold a delta stream into a live collection, expose a
snapshot and an `onChange`. It is the reactive equivalent of `.toList()` /
`.toMap()` on a stream, and it is missing from the framework, so each demo
re-derives it (and can get thread-safety subtly wrong — see the SSE write race
found in skillmatch).

## What it is

A kernel-provided terminal cell that materializes one delta stream and exposes
its current value + a change hook, for both set and map streams:

```kotlin
class SetView<E>(private val onChange: (Set<E>) -> Unit = {}) : Cell {
    val inlet: Serve<Propagate<SetDelta<E>>>
    fun current(): Set<E>          // thread-safe snapshot
}
class MapView<K, V>(private val onChange: (Map<K, V>) -> Unit = {}) : Cell {
    val inlet: Serve<Propagate<MapDelta<K, V>>>
    fun current(): Map<K, V>
}
```

Optionally a `graph { }` sugar: `val matches = materialize(matchesRef)` returning
a handle with `.current()` and `.onChange { }`, hiding spawn+connect.

## Why it is a proper fit

- It is the read side of the model: the framework has rich sources
  (`SetCell`, `MapCell`) and transforms (join/groupBy/…) but **no standard
  terminal**. Naming it makes "where does state leave the graph" a framework
  concept, not an app accident.
- It centralizes the delta-fold contract (membership vs tag churn for sets;
  puts/removals for maps) and its concurrency guarantees **once**, correctly —
  the demos already proved they get this wrong when copied.
- It composes with backlog 04 (glitch-free edge): a multi-stream `View` is the
  obvious home for wave-aligned snapshots.

## Solution sketch

`SetView` wraps the existing fold logic (mirror `TagState`/`SetFold`): apply
`SetDelta` to an internal membership multiset, expose `current()` behind a lock,
call `onChange(current())` after each apply. `MapView` folds `puts`/`removals`
into a map. Both are eager (serve in `init`, usable unhosted like
`GlitchFreeCell`), `Stateful` for snapshot/restore, and idempotent under
late-join replays.

## Expected inputs / outputs

- Input: one `SetDelta`/`MapDelta` outlet.
- Output: no downstream port; `current()` returns the materialized collection,
  `onChange` fires once per applied delta with the new snapshot.

```kotlin
val gap = MapView<String, Long> { broadcast() }   // was SetHubCell + field + closure
host.connect(refs.demand, "outlet", gap.ref, "inlet")
// later: gap.current()
```

## Acceptance criteria

- `current()` equals the batch fold of all applied deltas, on every seed
  (reuse the existing incremental-vs-batch harness with `SetView`/`MapView` as
  the sink instead of ad-hoc folds).
- `onChange` fires exactly once per applied delta, after state is visible to
  `current()`; concurrent `current()` reads never observe a torn state.
- Late-join delta-from-empty replays are absorbed without double-counting.
- `Stateful` round-trip.
- Refactor proof: skillmatch deletes `SetFold`, `SetHubCell`, `MapHubCell` and
  the `setHub`/`mapHub` helpers, wiring its six edges through `SetView`/`MapView`;
  net LOC drops and the hand-rolled classes disappear.
