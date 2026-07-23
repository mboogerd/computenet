# Idea: a first-class observation sink (materialized edge with snapshot + catch-up)

> Type: demo backend operation that belongs in the framework
> Origin: `:demo:slotfinder` — the hand-rolled `SlotHubCell` / `DayCountHubCell` / `SlotMembership`
> + `/state` endpoint, and the SSE-stall bug that hand-rolling caused
> Relates to: `Stateful`, `StateRequestProtocol` (G-18 pull), `GlitchFreeCell`, demo-findings **F-5**

## Origin

Every serving demo ends the same way: it takes one or more cell **outlets** and folds their
delta streams back into a **materialized current value** so the app can (a) push it to clients
and (b) answer "what is the state right now?" for a page load. In slotfinder this is three
hand-written pieces:

- `SlotMembership` — folds `SetDelta<Slot>` (with tags) into a live `Set<Slot>`.
- `SlotHubCell` / `DayCountHubCell` — a bespoke `Cell` per observed outlet that folds and calls
  `onUpdate`, wired with manual `manage.spawn(hub)` + `manage.connect(ref,"outlet",hub,"inlet")`.
- a synchronized mutable `state` object + `stateJson()` + a `/state` endpoint for late joiners.

This hand-rolled edge is also where the **real bug** lived: seven hubs broadcasting from
scheduler threads with no wave alignment and no atomic snapshot produced interleaved writes and
a silently wedged SSE stream (fixed in the demo, but the *shape* of the bug is the point). The
kernel already owns every ingredient — `Stateful.snapshot()`, `StateRequestProtocol` for pull,
`GlitchFreeCell` for wave alignment — but there is no ergonomic sink that assembles them, so
each demo reinvents a weaker, race-prone version. F-5 names exactly this ("no ergonomic way to
apply glitch-freedom at the observation edge").

## What it is

A hosted **`ObservationSink`** (a.k.a. `MaterializeCell`) that subscribes to one or more outlets
and exposes, to the *app* (not another cell):

1. `current(): Snapshot` — a **consistent** materialized value, safe to read from any thread.
2. `onChange(listener)` — fired once per settled change with the new snapshot.
3. built-in **late-join catch-up**: a fresh listener (new SSE client, reloaded tab) is handed the
   current snapshot immediately, sourced from the sink's materialized state — and, when the sink
   is remote, from the producer via `StateRequest` rather than a full relink.

For the multi-outlet case (slotfinder folds participants + pair + common + filtered + byDay into
one JSON object) the sink composes them behind `GlitchFreeCell` so the app never observes a
snapshot where `common` contains a slot that `filtered` hasn't processed yet (the F-5 flash).

## Why it fits the framework

- **It is the read/observe dual of the `graph { }` builder.** Construction is already a
  first-class, declarative kernel concern; observation is currently left to app glue. The kernel
  defines wave/tag/frontier semantics precisely *inside* the graph but abandons them at the edge
  where apps actually read — this sink extends the same guarantees to the boundary.
- **It reuses, not duplicates.** `current()` is `Stateful.snapshot()` surfaced for reads;
  catch-up is `StateRequestProtocol` (already specified, already built, currently unused by
  demos); consistency is `GlitchFreeCell`. The sink is assembly + ergonomics, not new semantics.
- **It removes a whole class of app bugs.** Atomic snapshot + single settled-change callback
  means no per-thread interleave, no torn reads, no "dropped client never reconnects" — the
  failure modes I hit in slotfinder become unrepresentable at the app layer.
- **It is transport-neutral.** SSE, WebSocket, or a test poller all sit on `current()` +
  `onChange`; the kernel stays free of transport, matching the `kernel`/`wire` split.

## Solution sketch

```kotlin
// single outlet
val slots = host.observe<Set<Slot>>(refs.common)      // folds SetDelta -> Set via TagState
slots.current()                                       // consistent snapshot, any thread
slots.onChange { set -> broadcast(set) }              // one call per settled change

// composite, glitch-free across heterogeneous outlets
val view = host.observeAll {
    set("common",   refs.common)      // SetDelta<Slot>  -> Set<Slot>
    set("filtered", refs.filtered)
    map("byDay",    refs.byDay)       // MapDelta<String,Long> -> Map<String,Long>
}                                     // GlitchFreeCell-aligned composite snapshot
view.current()                        // { common, filtered, byDay } as one wave-aligned value
```

The fold strategy per outlet is the read-model utility proposed in
`consumer-materialized-views.md` (`SetView`/`MapView`), so `observe`/`observeAll` are thin
hosting + catch-up wrappers over those views.

## Inputs / outputs

- **Input:** one or more `CellRef` outlets + a declared read-model per outlet (set / map / count).
- **Output to the app:** `current()` snapshot (thread-safe, atomic) and `onChange(snapshot)`
  callbacks; internally, a `StateRequest` reply for any late subscriber.
- **Consistency:** composite snapshots are wave-aligned (no partial-pipeline reads); single-outlet
  snapshots are point-consistent per settled change.

## Acceptance criteria

- [ ] Slotfinder's `SlotHubCell`, `DayCountHubCell`, `SlotMembership`, and the manual
      `spawn`+`connect` observation wiring are all deleted in favour of `host.observeAll { … }`;
      `/state` is served from `view.current()`.
- [ ] A test reproduces F-5: with the naive hand-fold, a composite snapshot can show
      `common ∋ s` while `filtered ∌ s` mid-wave; with `observeAll`, no such snapshot is ever
      observable (assert over many seeds / interleavings).
- [ ] `current()` is safe under concurrent reads while `onChange` fires (no torn snapshot); a
      stress test with concurrent ops + a reader never observes a partially-applied delta.
- [ ] A late subscriber added after N changes receives a snapshot equal to `current()` with no
      replay of individual historical deltas (catch-up via materialized state / `StateRequest`).
- [ ] Works unchanged across an in-process outlet and a `wire`-bridged remote outlet (same
      observable snapshot), exercising the remote `StateRequest` path.
- [ ] No transport code enters `kernel`; the sink exposes only `current()`/`onChange`.
