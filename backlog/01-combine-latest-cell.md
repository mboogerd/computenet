# CombineLatestCell — per-key outer-join / combine-latest over N map streams

## Origin

`:demo:tiering` needs to fuse two derived `MapDelta` streams — `tierAvg`
(mean absolute score per item) and `prefAvg` (mean pairwise sign per item) —
into one `Tiered` value per item. There is no kernel cell for this, so the demo
hand-rolls `FuseCell` (see `demo/tiering/.../FuseCell.kt`) and records the gap as
finding **F-1** in `doc/demo-findings.md`. `:demo:skillmatch` independently needs
the same shape (per-pair match counts vs per-job required counts). The kernel's
`JoinCell` is an *inner* join: a key missing from one side vanishes from the
output — but both demos need **outer** semantics (an item with only a tier
signal, or only a preference signal, must still be tiered).

## What it is

A cell that holds the latest value per key from two (or N) `MapDelta` input
streams and emits a combined value on any change, with **outer-join** semantics:
a key present on *any* input is present in the output; a key dropped from *all*
inputs is removed. Emission is **effective-only** — a key re-emits only when its
combined value actually changes. It is the natural dual of `JoinCell` (which this
would sit beside as the "combine-latest, outer" member of the join family) and a
direct generalization of the FuseCell prototype.

## Why it fits the framework

- It consumes and produces `MapDelta`, the framework's existing map-stream
  contract, and is the single writer of its output — exactly `MapDelta`'s
  documented single-writer model (inherits the G-23 convergence caveat, no worse
  than `JoinCell`).
- The `combine` function is a **deterministic function of the latest per-key
  inputs** — the same "value depends only on current state, never arrival order"
  discipline the `Aggregator` contract already demands. Effective-only emission
  by value-equality mirrors `GroupByCell`.
- Group-death (remove when all sides drop the key) is the same last-retraction
  removal `GroupByCell` already implements for `MapDelta`.
- Late-join catch-up (G-22) via `onLinked` delta-from-empty is already prototyped
  in FuseCell and matches `MapCell`/`GroupByCell`.

## Solution sketch

```kotlin
class CombineLatestCell<K, V1, V2, R>(
    ref: CellRef = CellRef(UUID.randomUUID()),
    private val combine: (K, V1?, V2?) -> R?,   // null R => key absent from output
) : Cell, Stateful {
    val left:   Serve<Propagate<MapDelta<K, V1>>>
    val right:  Serve<Propagate<MapDelta<K, V2>>>
    val outlet: Subscribe<Propagate<MapDelta<K, R>>>
    // per-side latest maps + `published: Map<K,R>`; on each input delta,
    // recompute combine() for every touched key, diff vs published,
    // emit only changed puts / vanished removals (effective-only).
}
```

`FuseCell` collapses to `CombineLatestCell(combine = { _, t, p -> Tiering.fuse(t, p) })`
— the whole hand-rolled cell disappears from the demo. An N-ary variant
(`combine: (K, List<V?>) -> R?` over a list of inlets) generalizes to
skillmatch's multi-signal case.

## Inputs / outputs

- **Input**: two (or N) `MapDelta<K, Vi>` streams, each single-writer.
- **Output**: one `MapDelta<K, R>` stream.
- **combine**: `(K, V1?, V2?) -> R?`. Nulls mark a side that currently has no
  value for the key; returning `null` means "not in output" (enables the
  one-signal-only and both-signals cases in one function).

## Acceptance criteria

- Outer semantics: a key present on exactly one side yields output computed from
  that side alone (`combine(k, v, null)`); present on both → `combine(k, v, w)`.
- Group-death: a key removed from every side is removed from the output exactly
  once; no ghost keys.
- Effective-only: an input delta that leaves a key's combined `R` unchanged emits
  nothing for that key; changing it emits exactly one put.
- One input `MapDelta` touching several keys emits as **one** output `MapDelta`
  under the input's wave (matches `GroupByCell`'s wave rule, spec 22).
- `Stateful` snapshot/restore round-trips the per-side latest maps and rebuilds
  `published` by recomputation.
- Late-join `onLinked` delivers current combined state as one delta-from-empty.
- Test: incremental result equals a batch recompute of `combine` over the final
  per-key inputs, for a randomized add/update/remove schedule across both sides.
- Migration: `:demo:tiering` deletes `FuseCell` and wires `CombineLatestCell`;
  existing `TieringServerTest` passes unchanged.
