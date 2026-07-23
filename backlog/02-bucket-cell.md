# BucketCell — threshold quantization over a map stream (score → band)

## Origin

`:demo:tiering` maps a continuous fused score in `[0,1]` to a discrete tier
(`S`..`F`) by fixed cutoffs. Today that quantization is buried inside the app's
`FuseCell` (`Tiering.tierOf(score)`), and the gap is recorded as finding **F-2**
in `doc/demo-findings.md`. The note observes that "score → band" is a recurring
incremental pattern (the attention system in the kernel quantizes magnitudes to
bands the same way — see `civictech.cell.data.Magnitude` / `AttentionBand`), yet
there is no reusable dataflow form for it.

## What it is

A cell that turns a `MapDelta<K, Double>` (or any `MapDelta<K, C : Comparable>`)
into a `MapDelta<K, Band>` by assigning each value to a bucket via a sorted
threshold table, emitting **effective-only on band transitions**. A naive
map-over-values cell would re-emit on every score wiggle; `BucketCell` emits only
when a key actually crosses a threshold — hysteresis-friendly discretization as a
first-class operator.

## Why it fits the framework

- Pure map-over-values with effective-only gating is exactly the emission
  discipline `GroupByCell`/`JoinCell` already use; the framework prizes
  "re-emit only on observable change" (spec 21).
- The band assignment is a **deterministic, order-independent function of the
  current value** — same rule as `Aggregator.value` and combine functions;
  incremental output provably equals batch recompute.
- The kernel already quantizes to bands internally for attention
  (`Magnitude`/`AttentionBand`); lifting that into a public combinator reuses an
  idiom the runtime itself trusts.
- It is a thin `Stateful` cell (remembers last emitted band per key) with the
  standard `onLinked` catch-up.

## Solution sketch

```kotlin
class BucketCell<K, C : Comparable<C>, B>(
    ref: CellRef = CellRef(UUID.randomUUID()),
    private val thresholds: List<Pair<C, B>>,   // ascending lower-bounds → band
    private val below: B,                        // band for values under the first bound
) : Cell, Stateful {
    val inlet:  Serve<Propagate<MapDelta<K, C>>>
    val outlet: Subscribe<Propagate<MapDelta<K, B>>>
    // published: Map<K,B>; on each delta, band(value) via binary search,
    // diff vs published, forward puts on band change and removals on key drop.
}
```

`Tiering.tierOf` becomes data:
`BucketCell(thresholds = listOf(0.10 to "F"... 0.85 to "S"), below = "F")`, and
the tiering cutoffs stop being imperative `when` branches.

Optional hysteresis extension (acceptance-gated, not required for v1): a
two-threshold (enter/exit) variant so a key on a boundary doesn't flap; the F-2
note explicitly calls hysteresis out as desirable.

## Inputs / outputs

- **Input**: `MapDelta<K, C>` — per-key continuous/ordinal values.
- **Output**: `MapDelta<K, B>` — per-key band labels.
- **Config**: an ascending threshold→band table plus the below-range band.
  Total function over `C`; ties resolved by a documented rule (`>=` lower bound).

## Acceptance criteria

- A value change that stays within one band emits **nothing**; a change that
  crosses a threshold emits exactly one put with the new band.
- Key removal upstream removes the key downstream exactly once.
- Band assignment is total and monotone in the value (v1 assumes monotone
  thresholds; assert config is sorted at construction).
- `Stateful` round-trips `published`; late-join `onLinked` yields current bands
  as one delta-from-empty.
- Test: for a random walk of scores per key, the stream of emitted bands equals
  the sequence of *distinct* bands the batch function would produce (no
  duplicate same-band emissions).
- Migration: `:demo:tiering` composes `CombineLatestCell` → `BucketCell` (fuse
  then bucket) and drops `tierOf` from app code; board output is unchanged.
