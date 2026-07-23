# CombineLatestCell — outer per-key combine over two map streams

**Type:** missing combinator
**Origin:** `:demo:skillmatch` "skill market" view (supply vs demand) and the
qualification view; `:demo:tiering` `FuseCell`. Cross-references
`doc/demo-findings.md` **F-1** — this is the concrete, spec-shaped version of
that finding.

## Origin of the idea

While building the skillmatch **market** view I needed, per skill, to show
`supply` (candidates who have it) beside `demand` (jobs that want it). Both are
`MapDelta<String, Long>` streams from two `GroupByCell`s. The interesting case
— a skill demanded by jobs but supplied by nobody (`supply = 0`) — is exactly
the one an **inner** join drops. `JoinCell` is inner-only, so I computed the
combine at the observation edge in `stateJson()`:

```kotlin
val market = (supply.keys + demand.keys).sorted().map { skill ->
    Triple(skill, supply[skill] ?: 0L, demand[skill] ?: 0L)
}
```

That is a hand-rolled outer join over two map streams, recomputed on every
snapshot rather than flowing as deltas. The qualification view has the same
shape (`matchCounts` vs `required`), and `:demo:tiering` prototyped it as an
app-level `FuseCell`. Three demos, one missing primitive.

## What it is

A stateful cell that holds the latest value per key from **two** `MapDelta`
input streams and emits a combined value on any change, with **outer**
semantics: a key present on only one side still produces output.

```kotlin
class CombineLatestCell<K, V, W, R>(
    private val combine: (K, V?, W?) -> R?,   // null result ⇒ drop the key
)
interface CombineLatestApi<K, V, W, R> {
    val left:  Serve<Propagate<MapDelta<K, V>>>
    val right: Serve<Propagate<MapDelta<K, W>>>
    val outlet: Subscribe<Propagate<MapDelta<K, R>>>
}
```

Contrast with existing joins: `JoinCell` = inner, `V×W` only; `CombineLatestCell`
= outer, `(V?, W?) → R?`. `combine` returning `null` lets a key be present in
inputs but absent from output (e.g. "only emit skills that are actually
demanded").

## Why it is a proper fit

- It is the **outer** sibling of `JoinCell`, which already lives in
  `civictech.cell.data` and shares the exact port shape
  (`MapDelta` in ×2, `MapDelta` out). It slots into the operator family.
- It honours the framework's contracts: single-writer of its output stream (so
  not `Replicable`, like `GroupByCell`); **effective-only** emission by value
  equality of `R`; **group-death** removal when `combine` flips to `null` or
  both sides drop the key; `Stateful` snapshot of the two latest-value maps;
  **late-join catch-up** (G-22) by replaying the current combined map as a
  delta-from-empty.
- It inherits `MapDelta`'s documented convergence limit (G-23) exactly as
  `JoinCell` does — no new consistency story to invent.

## Solution sketch

State: `left: MutableMap<K,V>`, `right: MutableMap<K,W>`, `emitted: MutableMap<K,R>`.
On a delta from either side: for each touched key, update that side's map,
recompute `r = combine(k, left[k], right[k])`; diff `r` against `emitted[k]`
(value equality) → put on change, remove on `null`/absent-both. Emit all
touched keys as one `MapDelta` under the input wave id (spec 22).

## Expected inputs / outputs

| left `MapDelta<K,V>` | right `MapDelta<K,W>` | combine | outlet `MapDelta<K,R>` |
|---|---|---|---|
| `puts {python:1}` | — | `(k,s,d)->Pair(s?:0,d?:0)` | `puts {python:(1,0)}` |
| — | `puts {python:2}` | idem | `puts {python:(1,2)}` |
| `removals {python}` | — | idem | `puts {python:(0,2)}` (still demanded) |
| — | `removals {python}` | idem | `removals {python}` (both gone → group death) |

## Acceptance criteria

- Outer: a key on one side only appears in output; a key that leaves one side
  but stays on the other updates (not retracts).
- Group death: output removal iff `combine → null` or the key is absent from
  both sides.
- Effective-only: no emission when the recomputed `R` equals the last emitted.
- Wave/tag: all keys touched by one input delta emit as one `MapDelta` under
  that wave id; per-link FIFO preserved.
- Late-join: a fresh subscriber receives the current combined map as one
  delta-from-empty.
- `Stateful`: snapshot/restore round-trips both latest-value maps.
- Test: seeded incremental-vs-batch equivalence (mirror
  `SkillMatchPipelineTest`) where batch = outer-join of the two final maps;
  plus a `combine→null` filtering case.
- Refactor proof: skillmatch `market` and `qualification`, and tiering's
  `FuseCell`, re-expressed with this cell and the app edge stops recomputing
  them.
