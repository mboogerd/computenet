# shopping — backend bug: broadcast emits cross-view-inconsistent snapshots

**Module:** `:demo:shopping` — `demo/shopping/src/main/kotlin/civictech/demo/Main.kt`
**Severity:** low (transient; self-heals on the next frame)
**Found:** 2026-07-23, single-process mode, port 8137

## Observations

A single `add Dates` produced two SSE frames on a held `/events` connection:

```
data: {"items":["Bananas","Cherries"],       "produce":["Bananas","Cherries","Dates"], …}   <-- produce has Dates, items does not
data: {"items":["Bananas","Cherries","Dates"],"produce":["Bananas","Cherries","Dates"], …}
```

For one frame the aggregate snapshot is internally inconsistent: the filtered
"A–M aisle" (`produce`) already shows `Dates` while the master `items` list
does not. It converges on the very next frame, so a real browser usually only
flickers, but the pushed state is momentarily contradictory.

## Expectation

Each SSE frame should be a self-consistent aggregate snapshot: a derived view
(`produce`) should never contain an item the source list (`items`) is missing.

## Root-cause analysis

`broadcast()` is called from every hub's `onUpdate`, and the four hubs
(`itemsHub`, `produceHub`, `votesHub`, `countHub`) are updated by independent
propagations of the same logical change. Adding an item propagates to the
`produce` filter branch and the `items` hub as separate deliveries; whichever
hub fires first triggers a `broadcast()` of the whole `state` object while the
other fields still hold their pre-change values.

This does **not** violate per-view glitch-freedom — each individual view is
internally correct at all times. The inconsistency is only in the *aggregate*
snapshot the UI assembles from four separate cells with no cross-view barrier.

## Solution direction

Coalesce broadcasts so one logical change yields one frame:

- Debounce/collapse `broadcast()` within a wave (e.g. mark dirty and flush once
  the current propagation batch quiesces), or
- Gate the snapshot on a frontier/quiescence signal across the four hubs before
  pushing.

Low priority for a demo whose stated UI transport is "full-state SSE" and whose
verification lives in the kernel tests, but worth a note since the roadmap's
incremental browser client (M6+) would make such frames observable.
