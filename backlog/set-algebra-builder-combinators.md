# Set-algebra builder combinators (filter / count / intersect / union)

## Origin
`demo/shopping` builds its derived views two different ways in the same `init`:

- `produce` and `count` go through the DSL: `graph(host.managementInlet) { spawn("produce") { FilterCell(...) }; spawn("count") { CountCell() } }`
  — but even here the *connects* happen afterward, by hand, with
  `manage.connect(...)`.
- the `items ∩ votes` intersection is fully hand-wired outside the DSL:
  `IntersectSetCell()` + `manage.spawn` + three `manage.connect` calls.

Meanwhile `RelationalGraphs.kt` already shows the intended shape for higher-order
composition: `GraphBuilder.leftJoin/rightJoin/fullJoin` spawn their sub-cells and
wire them internally, returning a single `CellHandle`. The *join* family got this
treatment; the **core operators the demos actually lean on every day —
filter, count, intersect, union-as-derived — did not.** So app code drops out of
the replayable DSL exactly for the simplest operators.

## What it is
Extend the `GraphBuilder` vocabulary so a derived view reads as algebra and stays
inside the recorded `GraphSpec`:
```
val produce = items.filter("produce") { it.first().lowercaseChar() in 'a'..'m' }
val wanted  = items.intersect("wanted", votes)
val voteN   = wanted.count("count")
```
Each returns a `CellHandle`, spawns the underlying cell, records the
spawn+connect steps (graphs-as-data), and reads left-to-right.

## Why it fits the framework
- It is the **established pattern**, just extended: `RelationalGraphs` proves
  "operators as `GraphBuilder` extension functions returning `CellHandle`" is the
  blessed composition style. This fills the obvious gaps in that vocabulary.
- Keeps the "no new semantics in the DSL layer, ever" rule (GraphDsl.kt:216):
  these are thin wrappers over `spawn` + `connect`.
- Payoff is directly the roadmap's "developer payoff" theme (M4): the incremental
  operator library becomes *usable as algebra*, and every derived view a demo
  builds is then a replayable `GraphSpec` instead of imperative `manage.connect`.

## Solution sketch
`GraphBuilder` extensions (pairs nicely with, but does not require, typed links):
```
fun <E> CellHandle.filter(name: String, pred: (E) -> Boolean): CellHandle
fun <E> CellHandle.intersect(name: String, other: CellHandle): CellHandle   // IntersectSetCell, wires left/right
fun <E> CellHandle.union(name: String, other: CellHandle): CellHandle       // UnionSetCell
fun <E> CellHandle.count(name: String): CellHandle                          // CountCell
```
Since `CellHandle` currently only exists for DSL-spawned cells, add a
`GraphBuilder.adopt(cell): CellHandle` (or let `spawn` accept an already-built
cell) so app-owned cells like `itemsUnion`/`votesUnion` can enter the algebra.

## Inputs / outputs
- **Input:** one or two `CellHandle`s + operator params (predicate, etc.).
- **Output:** a new `CellHandle` for the derived cell; recorded `SpawnStep`s and
  `ConnectStep`s appended to the `GraphSpec`.

## Acceptance criteria
- `items.intersect("wanted", votes).count("n")` builds the same cell graph as the
  current hand-wired version and produces identical SSE state in `demo/shopping`.
- The produced `GraphSpec` replays (`applyTo`) onto a fresh host and reconstructs
  the same topology (graphs-as-data round-trip test).
- `demo/shopping` derived-view wiring is expressed through the combinators with
  no `manage.connect` calls remaining for those views; tests pass.
- Combinators are documented as sugar only — the spawned cell types
  (`FilterCell`, `IntersectSetCell`, …) are unchanged.
