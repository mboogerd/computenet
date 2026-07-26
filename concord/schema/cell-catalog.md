# Concord cell catalog — v1

The closed set of **neutral cell ids** a scenario may use as `type:` (CONCORD-PLAN
§1.2, P5). Each driver binds an id to its own cell; the kernel driver (W1-A) binds
via `ContractRegistry` descriptors. Every entry has a one-line, implementation-
neutral semantic. **Adding an id is a schema-change ticket**, because every entry
costs each future driver.

Op verbs (used in `apply` steps) are listed per source; operators and views take
no ops (they react to their inlets).

## Sources

| id | ops | semantic |
|---|---|---|
| `set-source` | `add`, `remove` | An observed-remove set (add-wins under concurrency); emits set deltas. |
| `map-source` | `put`, `remove` | A keyed map; last-writer-wins per key within a stream; emits map deltas. |
| `list-source` | `append`, `insert`, `remove` | An ordered list; emits positional list deltas. |
| `counter-source` | `increment`, `decrement` | A single integer counter; emits counter deltas. |
| `pn-counter` | `increment`, `decrement` | A replicated increment/decrement counter that converges across replicas. |
| `keyed-set` | `add`, `remove` | A set partitioned by an extracted key; emits per-key set deltas. |
| `quorum-set` | `add`, `remove` | A set whose membership is admitted only on k-of-n witnessing (quorum). |

## Operators

| id | params | semantic |
|---|---|---|
| `filter` | `fn` (predicate) | Passes through only elements satisfying the predicate. |
| `map` | `fn` (transform) | Applies a pure transform to each element of the stream. |
| `flatmap` | `fn` (transform→set) | Expands each element to zero-or-more elements, folded into a set. |
| `union` | — | Set union of its inlets' streams (inlets `left`/`right`). |
| `intersect` | — | Set intersection of its inlets' streams. |
| `join` | `fn` (`key-of`) | Inner-joins two keyed streams on a shared key. |
| `semi-join` | `fn` (`key-of`) | Emits left elements whose key is present on the right. |
| `lookup-join` | `fn` (`key-of`) | Enriches a stream with values looked up from a keyed side. |
| `group-by` | `fn` (`key-of`), agg | Partitions elements by key and folds each group with an aggregator. |
| `combine-latest` | `fn`, `glitch-free?` | Combines the latest value of each inlet with a pure function. |
| `count` | — | Distinct-element count of a set stream; emits a counter delta. |
| `presence-count` | — | Count of currently-present elements (presence, not distinct history). |
| `window` | (window spec) | Step-windowed fold over the stream (step-count windows only — nothing timer-driven). |
| `partition` | `fn` (`key-of`) | Splits a stream into partitions; a partitioned view equals its unpartitioned twin. |

## Views (terminal sinks the checks read)

| id | semantic |
|---|---|
| `set-view` | Folds a set-delta stream into live membership (`readView` → a set). |
| `map-view` | Folds a map-delta stream into a queryable map. |
| `count-view` | Folds a per-key count stream into queryable counts. |
| `value-view` | Folds a scalar stream (counter/combine output) into a single value. |

---

## Kernel-binding notes and gaps (for W1-A / W1-C / W3)

These are the places where a v1 catalog id does **not** map cleanly to an existing
`civictech.cell.data` / `civictech.cell.host` cell as of the pinned commit. W1-A
(driver) and the corpus authors must know them:

1. **`value-view` has no kernel binding.** `Observe.kt` ships only
   `View.set()` / `View.map()` / `View.count()` — there is **no scalar view fold**.
   The diamond exemplar (`22-GF-DIAMOND-01`) and any scalar golden (a `count`
   output, a `combine-latest` sum) need one. **W1-A must add a scalar `View`**
   (fold a counter/scalar delta stream to a single `Value`) to bind `value-view`.

2. **`map` (element-wise transform operator) has no dedicated cell.** The kernel
   has `FlatMapSetCell` (flatMap over a *set* stream) but no plain element-map,
   and the diamond exemplar applies `map, fn: identity` to a **counter** stream
   (identity arms of a fork). W1-A must decide the binding: `flatmap` singleton
   for set streams, and for identity/pass-through arms a trivial identity binding
   (or the corpus models identity arms as bare links). Flag for W3-2 (`24-OP-MAPFN`).

3. **Source op verbs are provisional.** The neutral verbs above (`add`/`remove`/
   `put`/`increment`/…) are named here for the corpus; W1-A maps each to the
   kernel cell's contract op (`MapOps.put`, `CounterCell.increment`, …). `map-source`
   remove is keyed (`remove(key)`), distinct from `set-source` `remove(value)`.

4. **`quorum-set` k-of-n admission** (`QuorumSetCell`) is observable (24-OP-QUORUM-01),
   but the witnessing shape (how a scenario supplies k witnesses) is a §3 authoring
   question for W3-2 — the descriptor param is not yet frozen here.

5. **`window`** binds to `Windows` (step-count windows only — 52: nothing
   timer-driven exists); the window-spec descriptor param is deferred to W3-2.

Everything else in the table maps directly: `set-source`→`SetCell`,
`map-source`→`MapCell`, `list-source`→`ListCell`, `counter-source`→`CounterCell`,
`pn-counter`→`PnCounterCell`, `keyed-set`→`KeyedSetCell`, `quorum-set`→
`QuorumSetCell`, `filter`→`FilterCell`, `flatmap`→`FlatMapSetCell`,
`union`→`UnionSetCell`, `intersect`→`IntersectSetCell`, `join`→`JoinCell`,
`semi-join`→`SemiJoinCell`, `lookup-join`→`LookupJoinCell`, `group-by`→`GroupByCell`
(mergeable variant `MergeableGroupByCell`), `combine-latest`→`CombineLatestCell`,
`count`→`CountCell`, `presence-count`→`PresenceCountCell`, `partition`→
`PartitionedCell`, `set-view`/`map-view`/`count-view`→`View.set/map/count`.
