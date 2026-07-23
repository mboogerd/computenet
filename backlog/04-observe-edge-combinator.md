# observe() — a first-class, glitch-free observation edge for app state

## Origin

Every demo hand-rolls the same "fold one outlet into app state and notify the UI"
edge cell. In `:demo:tiering` these are `SetHubCell` and `MapHubCell` plus a
`SetFold` helper (`demo/tiering/.../TieringApp.kt`), and the app then keeps six
separate mutable fields (`items`, `valuations`, `prefs`, `tierAvg`, `prefAvg`,
`fused`) each updated by its own hub callback. Finding **F-5** records the deeper
problem: these views update asynchronously and independently, so a snapshot read
by the UI (or a test) can be **momentarily inconsistent** across views — the
kernel's glitch-free machinery (`GlitchFreeCell`) exists but there is no
ergonomic way to apply it at the *observation edge* where apps actually read
state.

There is also a correctness dimension: because the folded view lags the write
side, the tiering `unitem` cascade originally read stale state and ghosted items
(fixed by hand). An observation edge that could answer "current authoritative
state" synchronously would remove that whole failure mode.

## What it is

A small runtime combinator — `host.observe(ref) { snapshot -> ... }` — that spawns
the boilerplate hub cell for you, returns a **synchronously queryable**
materialized view (`view.current()`), and (opt-in) delivers **wave-aligned,
glitch-free** multi-outlet snapshots so N views fold into one coherent app state
per wave instead of N independently-lagging fields.

## Why it fits the framework

- The plumbing already exists in three forms; this only names and unifies it.
  `SetHubCell`/`MapHubCell` are literally the same cell minus the fold type.
- Glitch-freedom is a *core* kernel capability (`GlitchFreeCell`, spec 22) that is
  currently unused exactly where it matters most — the read edge. F-5 explicitly
  proposes "a glitch-free multi-inlet hub/collector idiom or a `GlitchFree`-wrapped
  composite view cell." This is that.
- A synchronous `current()` read can be backed by the existing pull protocol
  (`StateRequestProtocol`, spec 20/21 §Pull) rather than a shadow copy — reusing a
  primitive no demo currently exercises.
- It is edge/library code (belongs beside `graph {}`/`ManagedHost` helpers), keeping
  `kernel` transport-neutral.

## Solution sketch

```kotlin
// single view
val fused: MaterializedView<Map<String, Tiered>> =
    host.observe(refs.fused, fold = MapFold()) { onChange() }
fused.current()                       // synchronous authoritative read

// glitch-free composite: one snapshot across several outlets, per wave
val ui: MaterializedView<UiState> = host.observeAll {
    val items = set(refs.items); val fused = map(refs.fused); ...
    UiState(items.current, fused.current, ...)
}                                     // delivered wave-aligned (spec 22)
```

Internally: a generic `HubCell<Delta, State>(fold)` replaces the per-type hubs;
`observeAll` wires the group behind a `GlitchFreeCell` so downstream `onChange`
fires once per wave with a consistent cross-view snapshot.

## Inputs / outputs

- **Input**: a `CellRef` (or several) whose outlet(s) carry `SetDelta`/`MapDelta`,
  plus a fold and an `onChange` callback.
- **Output**: a `MaterializedView<State>` with `current(): State` (synchronous)
  and change notifications; multi-ref form guarantees snapshots are wave-aligned.

## Acceptance criteria

- `host.observe(ref, fold)` reproduces `SetHubCell`/`MapHubCell` behaviour with no
  app-defined cell; tiering's three set-folds and three map-folds collapse to
  `observe` calls.
- `current()` returns a self-consistent snapshot (no torn read) and reflects every
  delta the view has processed.
- `observeAll` composite: for any interleaving of upstream deltas, no `onChange`
  ever exposes a cross-view state the batch computation could not produce (the F-5
  "matched:1 while gap still lists it" contradiction is unobservable) — verified by
  the same joint-condition test F-5 describes.
- Glitch-free path reuses `GlitchFreeCell`; single-view path stays cheap (no
  wave-alignment cost when only one outlet is observed).
- `kernel` gains no transport dependency; the combinator lives in the graph/host
  helper layer.
- Migration: `:demo:tiering` (and ideally `:demo:skillmatch`, which F-5 was filed
  against) drop their hub cells and manual folds.
