# Materialize-and-observe: a first-class sink for derived streams

## Origin
`demo/shopping/src/main/kotlin/civictech/demo/Main.kt` hand-rolls three pieces of
plumbing that every demo re-invents:

- `Membership` (folds `SetDelta<String>` tags back into a current `Set<String>`),
- `SetHubCell` (a `Cell` whose only job is to fold a `SetDelta` stream and call
  `onUpdate(current)`),
- `CounterHubCell` (the same for `CounterDelta` → `Long`).

All three exist because the app needs the **current materialized value** of a
derived stream (to render it / push it over SSE), but the framework only hands
out *deltas*. The irony: `UnionSetCell`, `CountCell`, `GroupByCell` already keep
that current value internally (`TagState`, a counter, `groups`) — the demo
re-derives what the cell already holds, once per delta type, by hand.

## What it is
A single generic terminal cell — `ObserveCell<D, V>` — plus a small
`Materialized<V>` facet on stateful data cells:

- `ObserveCell(initial: V, fold: (V, D) -> V, onChange: (V) -> Unit)` subscribes
  to any `Propagate<D>` outlet, folds deltas into `V`, and fires `onChange` with
  the new value (effective-only: only when `V` actually changes).
- Ready-made folds shipped for the delta types the kernel already defines:
  `Materialize.set<E>()` (tag-aware membership, i.e. today's `Membership`),
  `Materialize.count()`, `Materialize.map<K,A>()` (for `GroupByCell`'s
  `MapDelta`). The app supplies only `onChange`.
- `current(): V` query so a late subscriber (a fresh SSE tab) reads the value
  directly instead of racing the stream.

## Why it fits the framework
- It adds **no new dataflow semantics** — it is a sink that folds deltas, the
  exact shape the roadmap already blesses for hubs ("hubs fold them into UI
  state", Main.kt:145). Same spirit as `GraphBuilder.leftJoin`: composition, not
  a new primitive class.
- It closes a real asymmetry: the kernel is delta-in/delta-out end to end, but
  every *edge* of a real app is "give me the value." Right now that edge is
  copy-pasted per app and per delta type.
- The tag-aware set fold (`Membership`) is genuinely non-trivial (add/del tag
  sets, drop on empty) and belongs next to `TagState`/`SetDelta`, not in a demo.

## Solution sketch
```
class ObserveCell<D, V>(
    initial: V,
    private val fold: (V, D) -> V,
    private val onChange: (V) -> Unit,
) : Cell {
    val inlet = registerPort("inlet", FanInlet.create<Propagate<D>>())
    @Volatile private var value = initial
    fun current(): V = value
    init { inlet.serve { d -> val next = fold(value, d); if (next != value) { value = next; onChange(next) } } }
}

object Materialize {
    fun <E> set(): (Set<E>, SetDelta<E>) -> Set<E>          // membership fold, tag-aware
    fun count(): (Long, CounterDelta) -> Long
    fun <K, A> map(): (Map<K, A>, MapDelta<K, A>) -> Map<K, A>
}
```
Shopping then becomes: `ObserveCell(emptySet(), Materialize.set()) { items = it; broadcast() }`
— `Membership`, `SetHubCell`, `CounterHubCell` all deleted.

## Inputs / outputs
- **Input:** a link from any `Subscribe<Propagate<D>>` outlet; a fold + callback.
- **Output:** side-effecting `onChange(V)` on effective change; `current(): V`
  on demand. No outlet (terminal), so it never re-enters the graph.

## Acceptance criteria
- `ObserveCell` fed a `UnionSetCell` outlet reproduces exact membership under
  concurrent add/remove with observed-remove tags (port the demo's `Membership`
  behavior; a re-added-after-remove element reappears once).
- `current()` immediately after linking returns the caught-up value (uses the
  `onLinked` state-as-delta path, G-22), with no missed or double-counted delta.
- Effective-only: tag churn that does not change `V` fires no `onChange`.
- Ships folds for `SetDelta`, `CounterDelta`, `MapDelta`; each has a unit test.
- `demo/shopping` and at least one other demo (skillmatch/tiering) drop their
  hand-rolled hub/membership classes and still pass their tests.
