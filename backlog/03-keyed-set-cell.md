# KeyedSetCell — per-writer upsert source that feeds set-stream operators

## Origin

Two things in `:demo:tiering` point at the same missing primitive:

1. Finding **F-3** (`doc/demo-findings.md`): an agent re-tiering an item must
   *remove the old* `Valuation` element and *add the new one*, because the OR-set
   (`SetCell`) can only retract an element the app still remembers. So the app
   keeps an `(agent,item) → Valuation` index (`currentValuation`) purely to issue
   removals — and I had to add a second such index (`livePrefs`) for preferences.
2. A real bug I hit and fixed in the demo: the `unitem` cascade originally read
   the *async-folded* view to decide what to retract, so a just-added valuation
   was missed and ghosted the removed item onto the board. The fix was to drive
   removals from those hand-maintained authoritative indices. **The shadow index
   is load-bearing, and every app re-implements it.**

The root cause both share: "latest value per (writer,key)" is an **upsert**, not
a set add. `MapCell` has upsert semantics but emits `MapDelta`, which
`GroupByCell` (SetDelta-only) cannot consume — so the natural encoding is blocked
and apps hand-roll remove-old-then-add plus an index to remember the old value.

## What it is

A source cell whose input is keyed upserts — `put(key, element)` /
`remove(key)` — and whose output is a **`SetDelta`** stream that a
re-`put` on an existing key automatically retracts-then-adds. It is the
keyed-upsert set source F-3 asks for: the cell owns the "what was the previous
element under this key" memory that every demo currently keeps by hand, and it
emits set deltas so it plugs straight into `GroupByCell`, `FlatMapSetCell`,
`FilterCell`, etc.

## Why it fits the framework

- It closes the impedance mismatch between the two existing stream algebras:
  upsert-keyed writes (à la `MapCell`) and tagged set streams (à la `SetCell`),
  producing the `SetDelta` the whole M11 operator suite already speaks.
- Retract-then-add on re-put is exactly OR-set membership hygiene (spec 21): the
  old element's tags die, the new element's add-tag is fresh. The cell is the
  single writer of its element identities, so tag minting is clean.
- It removes an entire class of app-side bookkeeping and the bug class that comes
  with it (write/read consistency-domain bridging), which is squarely the
  demo-findings mandate: turn a recurring workaround into a primitive.
- Standard `Stateful` (the key→element map is the state) and `onLinked` catch-up.

## Solution sketch

```kotlin
@Contract interface KeyedSetOps<K, E> { fun put(key: K, element: E); fun remove(key: K) }

class KeyedSetCell<K, E : Serializable>(ref: CellRef = ...) : Cell, Stateful {
    val inlet:  Use<KeyedSetOps<K, E>>
    val outlet: Subscribe<Propagate<SetDelta<E>>>
    // current: Map<K,E>. put(k,e): if current[k]==e -> no-op; else emit
    //   SetDelta(adds={e}, dels = current[k]?.let{{it}} ?: {}) and store.
    // remove(k): current.remove(k)?.let { emit SetDelta(dels={it}) }.
}
```

`:demo:tiering` then models valuations as
`KeyedSetCell<Pair<agent,item>, Valuation>` feeding `tierAvg`, and preferences as
`KeyedSetCell<Pair<agent,Pair<w,l>>, Pref>` feeding the contribution flatMap —
`currentValuation`, `livePrefs`, and the manual remove-old dance all disappear,
and the `unitem` cascade becomes `remove(key)` for the keys the cell owns whose
key-tuple mentions the item — the write-side memory the app kept by hand now
lives in the cell that already owns those elements.

## Inputs / outputs

- **Input**: `KeyedSetOps<K, E>` — `put(key, element)`, `remove(key)`.
- **Output**: `SetDelta<E>` — element-level tagged set stream; a re-put under an
  existing key retracts the previous element and adds the new one atomically.

## Acceptance criteria

- `put(k, e1)` then `put(k, e2)` emits an add of `e1`, then a delta retracting
  `e1` and adding `e2`; downstream `GroupByCell` sees exactly one live element
  for `k` at all times.
- `put(k, e)` with the identical element is a no-op (no spurious churn).
- `remove(k)` retracts the current element for `k` and nothing else.
- Output feeds `GroupByCell`/`FlatMapSetCell` and produces the same aggregates a
  manual remove-old-then-add sequence would (equivalence test).
- Tag hygiene: the re-put's add-tag is fresh; the retracted element's tags are
  fully dead (no lingering liveness) — the FlatMapSetCell control-test class.
- `Stateful` round-trips the key→element map; `onLinked` yields all current
  elements as one delta-from-empty.
- Migration: `:demo:tiering` drops both shadow indices; the cascade-race
  regression test (add→tier→remove with no settle) shows zero ghosts.
