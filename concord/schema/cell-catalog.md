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
| `rebaseline-source` | `add` | An add-only tagged set source whose merge tags are minted under its **current emission epoch**, so a recovery that succeeds that epoch is observable downstream. The restartable source (`restart` step, `21-REBASE-01`); pair it with a `union` — the convergent consumer that folds an inbound re-baseline through its tag algebra. See note 2. |
| `ormap-source` | `put`, `remove` | An **observed-remove per-key map** (kernel `OrMapCell`): `put(key, value)` mints a fresh dot for the key and covers every dot the writer currently observes live there (reset-remove), `remove(key)` covers exactly the dots observed live here and now, so a concurrent `put`'s dot survives. Presence is add-wins; a key holding several surviving dots exposes one by dot order, which within a single stream is last-writer-wins. Observed through a `tagged-map-view`. |
| `keyed-set` | `put`, `remove` | A **keyed upsert** to a set (kernel `KeyedSetCell`): `put(key, element)` sets the element under a key (last-writer-wins per key), `remove(key)` drops it; the output is the flat set of currently-held elements, observed through a `set-view`. (W3-0 refinement — this is NOT an add/remove-by-value partitioned set; see note 6.) |

`counter-source`/`pn-counter`: `increment`/`decrement` take an optional `value:` amount
(default a unit step), or repeat a unit step with `times:` — both fold to the same total.

**The set-source family, and what an unkeyed `effect-count` derives from it.** An
`effect-count` check written without a `key:` derives the keys its sink must have
fired from the script's `add`s, and accepts exactly two ids as the sink's direct
upstream: **`set-source`** and **`journal-set-source`** (the durable binding of the
same cell — see Durability below). Every other id refuses, and
**`rebaseline-source` is deliberately excluded**: it re-announces its state under a
fresh emission epoch, so it may legitimately re-drive a sink with elements no
single scripted `add` accounts for, which the one-hop derivation cannot name. The
sink itself must be declared `effect-sink` for the same reason — that is the only id
whose contract is one effect per added element, keyed by the element. A refusal is a
**failing** check telling the author to name the keys (`key:`), never a quiet pass.
One unkeyed form is exempt from all of this: `exactly: 0` needs no derivation — it
asserts the log is empty — so it never refuses whatever the graph's shape. The rest of
the full shape gate (mid-script topology changes, `restart`/`restore` in the cone,
repeated adds, …) is in `scenario.md` §"`effect-count`: what the unkeyed form
quantifies over".

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
| `combine-latest` | `fn`, `glitch-free?` | Combines the latest value of each inlet with a pure function. **Only `fn: sum` is bound**, in two forms: plain — independent inlets, order-independent at quiescence but not wave-aligned; `glitch-free: true` — a wave-coalescing fork-join combine emitting one delta per completed wave. See note 1. |
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
| `tagged-map-view` | Folds a **tagged** map-delta stream (an `ormap-source` outlet) into the current `{key → exposed value}` map: a key is present while it holds at least one uncovered dot, and its exposed value is the one that dot order selects. Rendered like a `map-view`. |

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

## Durability (`dur` profile, `24-DUR-*`)

The four ids a `dur`-profile scenario is built from. All of them live on the
reserved host id `dur` (`host: dur`, `scenario.md`). They were bound driver-side by
W4-B and recorded in `concord/corpus/DISPUTES.md` §"How it is driven"; this section
**catalogues** them rather than minting them — no id, op or descriptor field changes
with it.

| id | ops | semantic |
|---|---|---|
| `journal-set-source` | `add`, `remove` | The durable `set-source`: an observed-remove set whose accepted ops tee to the write-ahead journal, so a crash replays them. Its outlet's wave identity is replay-stable (ref-derived `sourceId`; the epoch is journaled at checkpoint and rewound on recovery), which is what lets a downstream `effect-sink` recognise a replayed re-emission as already-acted (`24-DUR-04`/`24-DUR-05`). A member of the set-source family above — an unkeyed `effect-count` accepts it as a direct upstream. |
| `journal-set-view` | — | The durable `set-view`: a journaled set fold recovered from its checkpoint plus the journal tail after it, so `readView` after a crash reads the pre-crash membership (`24-DUR-01`/`24-DUR-02`). Observed through the ordinary view checks. |
| `effect-sink` | — | The **effect boundary**: a journaled sink that fires one external effect per delivered added element, **keyed by that element**, into the log `effect-count` reads. Guarded by the `Effectful` processed frontier, so an invocation at or behind the frontier — replayed or live-duplicated — is suppressed instead of re-fired (`24-DUR-05`). Required by the unkeyed `effect-count` derivation. One of two `retransmit` targets the kernel binding admits — the other is a `replica-of` replica's `Replicable.deltaInlet`, where the dot algebra decides the duplicate instead (`scenario.md`'s driver-capability note). It is also the kernel binding's only `drive-contextless` target (`computenet-em9i`), for the same reason: an effect boundary reads a delta's added elements and decides on the message context, so a delivery carrying none is judged by the `Effectful` admission rule (`24-DUR-06`) rather than by a tag the scenario never named. |
| `journal` | — | The **crash handle**: a controller pseudo-cell, not a real cell (no ports, no links, no ops). `despawn`-ing it crashes and recovers the whole durable host in one step — every live instance discarded, the graph rebuilt under the same refs, then recovered from the surviving journal. A `snapshot` of a journaled cell lowers to a host checkpoint (state + frontier compaction). |

`set-source`, `set-view` and `quorum-set` may also be placed on `host: dur`. There
they are **volatile** members of the durable host — rebuilt fresh on a crash, never
journaled or replayed. That is how per-cell durability is expressed
(`24-DUR-01`/`24-DUR-03`), and the volatile fan-in is what a journaled arm replays
*into* (`24-REPLAY-01`; with `glitch-free: true`, `DUR-GF-01`).

These four bind in `KernelDriverDur` rather than `KernelCatalog`, so they are absent
from the core/dist binding table below.

---

## Kernel-binding status (W3-0 — the driver is catalog-complete)

The kernel driver (`civictech.concord.driver.kernel`) binds every catalog id below.
Sources bind to `civictech.cell.data` cells;
views and the two adapters (`map fn:identity`, scalar `combine-latest`) and the
scalar/list view folds and the `feedback` head live in the driver's
`KernelAdapters`.

**Bound directly**: `set-source`→`SetCell`, `counter-source`→`CounterCell`,
`map-source`→`MapCell`, `list-source`→`ListCell`, `pn-counter`→`PnCounterCell`,
`keyed-set`→`KeyedSetCell`, `ormap-source`→`OrMapCell`,
`tagged-map-view`→`View.taggedMap`, `filter`→`FilterCell`, `union`→`UnionSetCell`,
`intersect`→`IntersectSetCell`, `count`→`CountCell`,
`presence-count`→`PresenceCountCell`, `group-by`→`GroupByCell`,
`partition`→`PartitionedCell`, `quorum-set`→`QuorumSetCell`,
`set-view`/`map-view`/`count-view`→`View.set/map/count`.

**Bound via a driver adapter** (kernel unmodified): `map fn:identity`→`IdentityCell`
(pass-through, works for set *and* scalar arms); `map fn:<other>`/`flatmap`→
`FlatMapSetCell` (singleton / general); `join`/`lookup-join`→`JoinSetCell` (over set
streams of pairs, with different `combine`); `semi-join`→`SemiJoinCell`;
plain `combine-latest fn:sum`→`ScalarSumCombineCell` (the wave-aligned form,
`combine-latest fn:sum, glitch-free:true`, binds **directly** to the kernel's
`CoalescingCombineCell` — see note 1); `value-view`→a scalar `View` folding
both `CounterDelta` and `PnCounterDelta`; `list-view`→a list `View`; `feedback`/
`feedback-undamped`→`FeedbackCell` (a `CycleHead`); `nature-gate`→
`NatureGatedSinkCell` (a hand-registered `ContractRegistry` descriptor projects a
required nature onto its inlet — CP-F2/F3, W4-A followup); `exclusive-source`/
`exclusive-sink`→`ExclusiveSourceCell`/`ExclusiveSinkCell` (an `Owned`-carrying
`FanOutlet` contract, likewise hand-registered — M5.6, W4-A followup); `window
kind:tumbling`→a bare `GroupByCell` whose `keyFn` composes `Windows.tumbling`;
`window kind:sliding`→`WindowSlidingCell` (a real `FlatMapSetCell` over
`Windows.sliding` linked into a real `GroupByCell`, packaged as one `Cell` — the
same two-cell composition kernel `WindowingTest` exercises directly);
`rebaseline-source`→`ReBaselineSourceCell` (a `Stateful`, `ReBaselineEmitting`
tagged source over the kernel's public `FanOutlet.originate`/`waveState`/
`reBaseline` seams — the catalog twin of kernel `RestartReBaselineTest`'s
`TaggedProducerCell`; the recovery itself is run by `ManagedHost`'s own
supervision path, see note 2).

The `join` family binds over **set streams of pairs `[k, v]`** (matching the batch
oracle), not the kernel's map-stream `JoinCell`/`LookupJoinCell` — those are keyed
map joins with a different element shape, and the corpus's join operators consume
set sources.

### Binding notes (no gap remains here)

1. **The scalar `combine-latest` has two bound forms**, selected by the existing
   `glitch-free` descriptor param — the driver binds both, and the catalog id and
   its params are unchanged.
   - **Plain** (`glitch-free` absent/false) → `ScalarSumCombineCell`: it folds each
     arm's arrival straight into the running sum, so it is order-independent at
     quiescence (`final-view` holds) but deliberately **not** wave-aligned —
     `observations-all-satisfy(even)` is not guaranteed across a fork, which is what
     the `CTL-GF-01` control pins. This is the form that combines two *independent*
     inlets (`24-OP-COMBINE-01`).
   - **`glitch-free: true`** → the kernel's `CoalescingCombineCell` (D-COMBINE):
     version-buffered, emitting exactly one delta per **completed** input wave, so
     no torn intermediate sum is observable. `24-OP-COMBINE-02` asserts that
     positively (`observations-all-satisfy(even)` over a real fork-join diamond,
     `[22-GF-01]`). Like every wave-aligned fan-in its completeness set is its open
     inlinks, so it belongs on the arms of one forked source, not on independent
     ones. Both neutral arms resolve onto its single unrestricted `inlet` (as
     `union`/`quorum-set` arms do), each still its own link and its own expected
     edge.

   `combine-latest` with any `fn` other than `sum` throws
   `UnsupportedCatalogBinding`.

2. **`rebaseline-source` exists because `set-source` cannot witness a restart**
   (D-C12). A restart's observable content is twofold: the recovered state, and
   the **succession of the source's emission epoch** that keeps post-recovery
   tags from aliasing pre-recovery ones (spec 20/22 §Source identity; spec 20/24
   §Tag continuity). `set-source`'s tag source is deliberately *replay-stable* —
   it mints tags under a fixed identity so a replay reproduces them — so a
   re-baseline naming its superseded epochs would name nothing its own tags
   carry, and the retraction the requirement asks for could not be observed.
   `rebaseline-source` is the source of the other kind: tags minted under the
   outlet's current epoch, state re-announced on recovery. Both kinds are
   legitimate and the catalog now carries one of each. The convergent-consumer
   half of the property is the existing `union` id, which folds an inbound
   re-baseline through its tag algebra rather than as an ordinary delta; a plain
   view behind the source alone would see the announcement but not act on it.

   *Resolved (D-CONCORD, wave B2): this entry used to read "No wave-aligned scalar
   `combine-latest`" — the one remaining glitch-free `kernel-gap`. The kernel
   capability landed (D-COMBINE), the driver binds it, and the positive assertion
   is authored; see `concord/corpus/DISPUTES.md`.*

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
