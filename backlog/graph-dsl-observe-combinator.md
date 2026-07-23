# Idea: an `observe` / `tap` combinator in the `graph { }` DSL

> Type: API change for more understandable implementations
> Origin: `:demo:slotfinder` — the asymmetry between declarative pipeline construction and
> imperative observation wiring
> Relates to: `civictech.cell.graph.graph`, the `observation-sink-materialized-edge.md` idea

## Origin

Slotfinder builds its pipeline with a clean, declarative DSL:

```kotlin
graph(host.managementInlet) {
    val sources = PARTICIPANTS.associateWith { spawn(it) { SetCell<Slot>() } }
    val common  = spawn("common") { IntersectSetCell<Slot>() }
    connect(pairAB, "outlet", common, "left")
    …
}
```

But the moment it needs to **observe** those cells, it drops out of the DSL into raw host calls,
scattered across `init`, with stringly-typed ports and a bespoke `Cell` per observed stage:

```kotlin
observed.forEach { (name, ref) ->
    val hub = SlotHubCell({ synchronized(state){ slots[name] = it }; broadcast() })
    manage.spawn(hub)
    manage.connect(ref, "outlet", hub.ref, "inlet")
}
```

Reading the app, the *shape* of the dataflow (what feeds the UI) is invisible in the builder and
must be reconstructed from imperative glue. Construction is declarative; observation is not.

## What it is

Extend the graph DSL with a combinator that attaches an observation to a node **in the same
declarative block**, returning a handle the app reads after `graph { }` returns:

```kotlin
lateinit var view: ObservationView
graph(host.managementInlet) {
    val sources  = PARTICIPANTS.associateWith { spawn(it) { SetCell<Slot>() } }
    val common   = spawn("common")   { IntersectSetCell<Slot>() }
    val filtered = spawn("filtered") { FilterCell<Slot> { it.hour in BUSINESS_HOURS } }
    val byDay    = spawn("byDay")    { GroupByCell(keyFn = { it.day }, aggregator = count()) }
    connect(common, "outlet", filtered, "inlet")
    connect(filtered, "outlet", byDay, "inlet")

    view = observe {
        set("common",   common)     // typed: SetDelta<Slot>  -> Set<Slot>
        set("filtered", filtered)
        map("byDay",    byDay)       // typed: MapDelta<String,Long> -> Map<String,Long>
    }
}
// app side:
view.current(); view.onChange { broadcast(it) }
```

`observe { }` (and a fire-and-forget `tap(node) { delta -> … }` for the simplest case) desugars
to the spawn+connect the demo writes by hand, but keeps the whole dataflow — inputs, derivations,
**and** what is observed — in one readable block.

## Why it fits the framework

- **Symmetry / least surprise.** The DSL already owns `spawn` and `connect`; observation is just
  a `connect` to a sink. Making it a first-class verb closes an obvious gap rather than adding a
  new concept.
- **Type safety at the wiring point.** `set(node)`/`map(node)` can carry the element type, so the
  fold and the snapshot type are checked at compile time instead of the current pattern of a
  `Cell` whose inlet type is asserted only by convention.
- **It makes intent auditable.** A reviewer sees "these four stages feed the UI" in the builder,
  which matters for the project's spec-led ethos (the graph *is* the specification of the app).
- **Thin.** It is sugar over `observation-sink-materialized-edge.md`; no new runtime semantics,
  and it degrades to the existing manual API for anything the sugar doesn't cover.

## Inputs / outputs

- **Input:** node handles already in scope in the `graph { }` block + a declared read-model per
  observed node (`set` / `map` / `count`).
- **Output:** an `ObservationView`/`ObservationSink` handle (see the sink idea) usable after the
  block returns: `current()` + `onChange`. `tap(node){ … }` returns nothing (side-effecting
  subscriber) for quick logging/tests.

## Acceptance criteria

- [ ] Slotfinder's observation block shrinks to a single `observe { … }` inside the existing
      `graph { }`; the standalone hub classes and manual `spawn`/`connect` for observation are gone.
- [ ] `observe`/`tap` are pure DSL sugar: a test asserts the resulting graph (nodes + links) is
      identical to the hand-wired version.
- [ ] Wrong element type at a `set(...)`/`map(...)` site is a **compile error**, not a runtime
      class-cast (improves on today's convention-only typing).
- [ ] The DSL block still composes with `promote`/graph-evolution (observation nodes participate
      in the same swap window), so adding an observed stage later is declarative too.
- [ ] Doc example in the demo shows the full pipeline **and** its observation in one block, under
      ~20 lines, replacing the current split between `SlotPipeline.build` and `SlotFinderApp.init`.
