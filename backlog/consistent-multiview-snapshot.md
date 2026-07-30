# Consistent multi-view snapshot (`combineLatest` gated on the wave frontier)

> **Absorbed into the roadmap**: scheduled as
> [96 §E2](../doc/spec/90-roadmap/96-incremental-engines-plan.md) (the aligned
> multi-view sink; acceptance = E2.3/E2.5/E2.6). The guarantee itself is now
> **normative** at
> [20/22 §The observation frontier](../doc/spec/20-dataflow-semantics/22-consistency.md)
> (96 §E2.1); this backlog entry stays the acceptance-scoped pointer to the
> unbuilt sink (E2.3/E2.5/E2.6).

## Origin
`demo/shopping` renders four independently-derived views (`items`, `produce`,
`votes`/`wanted`, `voteCount`) and pushes them as one JSON object over SSE. Each
view has its own hub that calls `broadcast()` on update. Because one logical op
propagates to several hubs as **separate** deliveries, a single `add` emits
multiple SSE frames, and an intermediate one is cross-view inconsistent — e.g. a
frame where the filtered aisle already contains the new item but the master list
does not:
```
{"items":["Bananas","Cherries"],       "produce":[...,"Dates"], ...}   <- skewed
{"items":["Bananas","Cherries","Dates"],"produce":[...,"Dates"], ...}   <- converged
```
(Documented in `Main.kt`'s `broadcast()` ponytail comment.) Each *view* is
individually glitch-free; the **aggregate assembled from N cells** is not,
because nothing waits for the wave to quiesce across all four.

## What it is
A combinator that samples several cells and emits **one** combined value per
input wave, only once every contributing cell has settled for that wave:
```
combineLatest(itemsCell, produceCell, votesCell, countCell) { i, p, v, c -> stateJson(i, p, v, c) }
```
It is the multi-input dual of `GlitchFree`: `GlitchFree` makes one consumer wait
for its diamond frontier; `combineLatest` makes one *tuple* wait for the frontier
across independent upstreams sharing a wave id.

## Why it fits the framework
- The machinery already exists: wave ids are `(sourceId, counter)` and the
  glitch-free wrapper already "tracks dependencies to the frontier + buffers
  versions" (M2.4, `consistency/GlitchFree.kt`). This applies the same frontier
  reasoning to a fan-in of several cells instead of a diamond.
- "Glitch-free completeness must survive … wire boundaries" is a core invariant
  (AGENTS.md). The current per-hub broadcast is a place where an app is forced to
  *observe* a glitch the kernel otherwise forbids — a combinator closes that
  hole at the framework level instead of asking every app to debounce by hand.
- General beyond the demo: any UI/report that fuses several derived streams into
  one record (dashboards, the tiering/skillmatch score panels) wants exactly this.

## Solution sketch
```
class CombineLatestCell<R>(
    private val arity: Int,
    private val project: (List<Any?>) -> R,
) : Cell {
    // one inlet per source; hold latest value + last-seen wave per input
    // emit project(latest...) once all inputs that participate in wave W have
    // reported W (or are known not to participate); effective-only on R.
}
```
Inputs arrive as their natural deltas; the cell folds each to a latest value
(reusing `Materialize` folds from the sink backlog item) and gates emission on
the shared frontier. Degenerate case (a wave touching one input) emits once
immediately.

## Inputs / outputs
- **Input:** N outlets (mixed delta types allowed, each with its fold) + a
  projection function.
- **Output:** a single `Propagate<R>` (or an `ObserveCell`-style callback) that
  fires at most once per wave with a self-consistent tuple.

## Acceptance criteria
- A diamond-style test: one source fans into two derived cells then into
  `combineLatest`; under 200 randomized cross-host schedules the combined output
  is never assembled from mixed waves (mirror `GlitchFreeDiamondTest`, incl. a
  control run that trips without the gate).
- `demo/shopping` pushes exactly one SSE frame per op, and no frame ever shows a
  `produce`/`wanted` item absent from `items`.
- Effective-only: a wave that leaves `R` unchanged emits nothing.
- Works across the wire boundary (two-JVM peer mode) with the same one-frame
  guarantee, or the limitation is documented if frontier info doesn't cross.
