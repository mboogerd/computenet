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
| `pn-counter` | `increment`, `decrement` | A replicated increment/decrement counter that converges across replicas. Observed through a `value-view`. |
| `keyed-set` | `put`, `remove` | A **keyed upsert** to a set (kernel `KeyedSetCell`): `put(key, element)` sets the element under a key (last-writer-wins per key), `remove(key)` drops it; the output is the flat set of currently-held elements, observed through a `set-view`. (W3-0 refinement — this is NOT an add/remove-by-value partitioned set; see note 6.) |

`counter-source`/`pn-counter`: `increment`/`decrement` take an optional `value:` amount
(default a unit step), or repeat a unit step with `times:` — both fold to the same total.

`quorum-set` is an **operator**, not a source — see Operators below.

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
| `group-by` | `fn` (`key-of`), `agg` | Partitions elements by key and folds each group with an aggregator (`agg` = `count`\|`sum`\|`min`\|`max`, default `count`; non-count aggregators fold the elements' value components). Observed through a `count-view`/`map-view`. |
| `combine-latest` | `fn`, `glitch-free?` | Combines the latest value of each inlet with a pure function. **Only `fn: sum` is bound, final-view only** — see note 1. |
| `count` | — | Distinct-element count of a set stream; emits a counter delta. |
| `presence-count` | — | Count of currently-present elements (presence, not distinct history). |
| `quorum-set` | `k` | **Fan-in operator** over set streams (kernel `QuorumSetCell`): one link per source; an element is emitted once `k` of the `n` live source links assert it. `k` optional, default `n` (all sources ⇒ an intersection). k-of-n admission observable (24-OP-QUORUM-01). |
| `window` | `window` (descriptor), `agg` | Windowing = key derivation (M11.6): assigns each `[at, value]` element to one (`tumbling`) or several (`sliding`) window-start keys derived from `at` alone (no wall clock), then folds each window with `agg` (as `group-by`). `window: {kind: tumbling\|sliding, size, slide?}` — `slide` required for `sliding`, ignored for `tumbling`. Windows never close: a late element is an ordinary add, retractions flow like any other view (`24-OP-WINDOW-01`/`-02`). |
| `partition` | `fn` (`key-of`), `agg` | A **sharded group-by** (kernel `PartitionedCell`): partitions elements by key across shards and folds each with `agg`; the union of shard aggregates equals the unpartitioned `group-by` twin. Observed through a `count-view`. |

## Views (terminal sinks the checks read)

| id | semantic |
|---|---|
| `set-view` | Folds a set-delta stream into live membership (`readView` → a set). |
| `map-view` | Folds a map-delta stream into a queryable map. |
| `count-view` | Folds a per-key count stream into queryable counts. |
| `value-view` | Folds a scalar stream (`counter-source`/`combine-latest`/`pn-counter`/`feedback` output) into a single value. |
| `list-view` | Folds a positional list-delta stream (`list-source`) into an ordered list (W3-0). |

## Nature / ownership sinks (W4-A followup, `12-NEGOTIATE-01`/`23-SPSC-01`)

| id | ops | semantic |
|---|---|---|
| `nature-gate` | — | A sink whose inlet **declares a required nature** (idempotent merge). A plain default-nature producer's `connect` to it is refused at link time by the kernel's real `NatureNegotiation` (CP-F3) — resolves `12-NEGOTIATE-01`. |
| `exclusive-source` | `push` | A source whose `outlet` carries an `Owned` (exclusive, SPSC) payload — resolves `23-SPSC-01`. `push` wraps `value:` in a fresh `Owned`. |
| `exclusive-sink` | — | Consumes `exclusive-source`'s `Owned` payload; a **second** consume-link to the same `exclusive-source` outlet is rejected by the kernel's own FanOutlet exclusivity check (M5.6). Observed through a count (`final-view`/`readView` → the running delivery count). Only the reject half is covered — the observe/tap ADMIT half is the unbuilt G-47 gap (see `23-SPSC-01.yaml`). |

## Cycles (34-CYCLE)

| id | params | semantic |
|---|---|---|
| `feedback` | — | A `CycleHead` (kernel `FeedbackInlet`) making a damped feedback loop drivable. Ports: `inlet` (seed, a `counter-source` outlet), `loopOutlet` → `feedbackInput` (the cycle-closing self-loop edge), `outlet` (running total → `value-view`). The loop laps carry a Magnitude payload that halves each iteration and decays to a fixpoint; the payload is the **damping witness**, so the closing edge is admitted. Seeding with a single `increment value:S` yields the total `S + ⌊S/2⌋ + … + 1` (`S=64 → 127`). |
| `feedback-undamped` | — | The same head with a **non-Magnitude** lap payload and no witness, so the cycle-closing edge is **rejected** at connect (`CycleWithoutDamping`, FU-8) — for `34-CYCLE-REJECT-01`. Author the closing edge as a `connect` step with `expect: rejected`. |

The cycle-closing edge names its ports explicitly: `{from: fb, to: fb, outlet: loopOutlet, inlet: feedbackInput}`.

---

## Kernel-binding status (W3-0 — the driver is catalog-complete)

The kernel driver (`civictech.concord.driver.kernel`) binds every catalog id below
except the one honest gap in bold. Sources bind to `civictech.cell.data` cells;
views and the two adapters (`map fn:identity`, scalar `combine-latest`) and the
scalar/list view folds and the `feedback` head live in the driver's
`KernelAdapters`.

**Bound directly**: `set-source`→`SetCell`, `counter-source`→`CounterCell`,
`map-source`→`MapCell`, `list-source`→`ListCell`, `pn-counter`→`PnCounterCell`,
`keyed-set`→`KeyedSetCell`, `filter`→`FilterCell`, `union`→`UnionSetCell`,
`intersect`→`IntersectSetCell`, `count`→`CountCell`,
`presence-count`→`PresenceCountCell`, `group-by`→`GroupByCell`,
`partition`→`PartitionedCell`, `quorum-set`→`QuorumSetCell`,
`set-view`/`map-view`/`count-view`→`View.set/map/count`.

**Bound via a driver adapter** (kernel unmodified): `map fn:identity`→`IdentityCell`
(pass-through, works for set *and* scalar arms); `map fn:<other>`/`flatmap`→
`FlatMapSetCell` (singleton / general); `join`/`lookup-join`→`JoinSetCell` (over set
streams of pairs, with different `combine`); `semi-join`→`SemiJoinCell`;
`combine-latest fn:sum`→`ScalarSumCombineCell`; `value-view`→a scalar `View` folding
both `CounterDelta` and `PnCounterDelta`; `list-view`→a list `View`; `feedback`/
`feedback-undamped`→`FeedbackCell` (a `CycleHead`); `nature-gate`→
`NatureGatedSinkCell` (a hand-registered `ContractRegistry` descriptor projects a
required nature onto its inlet — CP-F2/F3, W4-A followup); `exclusive-source`/
`exclusive-sink`→`ExclusiveSourceCell`/`ExclusiveSinkCell` (an `Owned`-carrying
`FanOutlet` contract, likewise hand-registered — M5.6, W4-A followup); `window
kind:tumbling`→a bare `GroupByCell` whose `keyFn` composes `Windows.tumbling`;
`window kind:sliding`→`WindowSlidingCell` (a real `FlatMapSetCell` over
`Windows.sliding` linked into a real `GroupByCell`, packaged as one `Cell` — the
same two-cell composition kernel `WindowingTest` exercises directly).

The `join` family binds over **set streams of pairs `[k, v]`** (matching the batch
oracle), not the kernel's map-stream `JoinCell`/`LookupJoinCell` — those are keyed
map joins with a different element shape, and the corpus's join operators consume
set sources.

### The one honest gap (§5 — corpus disputes)

1. **No wave-aligned scalar `combine-latest`.** `ScalarSumCombineCell` is
   order-independent at quiescence (`final-view` holds) but **not** wave-aligned:
   intermediate mid-wave sums may be observed, so `observations-all-satisfy(even)`
   is NOT guaranteed for a nested diamond. A genuine glitch-free scalar combine
   does not exist in the kernel. `22-GF-NESTED-01` (product/even invariant over the
   stream) is a kernel gap; treat it as a dispute. `combine-latest` with any `fn`
   other than `sum` throws `UnsupportedCatalogBinding`.

`window` was resolved (R2-B, `24-OP-WINDOW-01`/`-02`): the dispute filed it as a
`kernel-gap`, but spec 24 §Grouped aggregation names the exact composition —
"windowing = key derivation" — and the kernel's `Windows.tumbling`/`sliding` are
real event-time → key-derivation functions, not a stub. The gap was a **driver
binding + schema descriptor** gap, not a kernel one: `CellSpec.window` (below)
freezes `{kind, size, slide?}`, and `KernelCatalog`/`WindowSlidingCell` bind it to
the real `GroupByCell` aggregation (tumbling directly; sliding via a real
`FlatMapSetCell` expansion stage) — see the binding table above.

### W3-0 catalog refinements (the driver made the catalog honest)

- **`keyed-set`** is a keyed **upsert** (`put(key, element)` LWW-per-key /
  `remove(key)`) whose output is the flat set of current elements (a `set-view`) —
  the kernel `KeyedSetCell` reality, not the "partitioned per-key set" the v1 table
  implied. Ops changed `add`/`remove` → `put`/`remove`.
- **`quorum-set`** is a fan-in **operator** (one link per source; `k` of `n` live
  sources), not an add/remove **source**. The k-of-n threshold is the new `k`
  descriptor field.
- **`group-by`/`partition`** take the new `agg` descriptor field
  (`count`\|`sum`\|`min`\|`max`, default `count`). `partition` is a sharded
  group-by, folding identically to its unpartitioned twin.
- **`list-view`** was added (the kernel `View` companion has no list fold).
- **`feedback`/`feedback-undamped`** were added for the cycle scenarios.
- **`window`** takes the new `window` descriptor field (`{kind: tumbling|sliding,
  size, slide?}`, `scenario.md`) alongside the existing `agg`; resolves the
  `24-OP-WINDOW-01`/`-02` dispute (R2-B).

`counter-source`/`pn-counter` `increment`/`decrement` take the amount from `value:`
(default a unit step), repeated by `times:` — the oracle and driver agree.
