# V1C-CELLS — six map- and set-backed data cells learn to page: bounded reads for `MapCell`, `KeyedSetCell`, `ListCell`, `WatermarkCell`, `InstanceSet` and `ShardCell`

**Status**: Specified — not-started
**Model:** `claude-opus-5` (effort xhigh) · **Escalate to:** `claude-opus-5`,
fresh session; if that fails, stop and re-split the ticket.
**Wave:** 9 · **Branches:** `ticket/v1c-cells`

## Context

ComputeNet is a Kotlin/JVM dataflow runtime: cells with typed ports, explicit
links, hosted execution, ownership-aware payloads. `ManagedHost`
(`kernel/src/main/kotlin/civictech/cell/host/ManagedHost.kt`) runs cells on their
own execution contexts.

Wave 8's `V1C-KERNEL` (`tickets/V1C-KERNEL.md`, **read it in full before you
write a line of code**) landed the *bounded state read*: a new
`kernel/src/main/kotlin/civictech/cell/BoundedRead.kt` carrying
`BoundedStateful`, `StateRead`, `StatePage`, `Cursor`, `Provenance` and
`StateReadResult`, plus `ManagedHost.readState(ref, request)` beside
`snapshotOf` (`ManagedHost.kt:1244-1263`). It implemented the interface on
**exactly one** cell — `civictech.cell.data.SetCell`
(`kernel/src/main/kotlin/civictech/cell/data/SetCell.kt:31`, `snapshot()` at
`:200-208`) — as the reference implementation, and deliberately left every other
cell alone so that wave 9 could split the per-cell work two ways without a merge
collision.

You are one of those two halves. **You implement `BoundedStateful` on the six
map-/set-backed data cells listed below, and nothing else.**

The design behind all of this is
`doc/spec/90-roadmap/98-inspector-v4-plan/20-wave-neutral-read-design.md` — the
V1C design note, produced by wave 6 and accepted at the C-replan checkpoint. Its
§5 "Implementations (additive per cell, ~15 cells for full coverage)"
(`:640-650`) is the list this ticket carves six entries out of. Read §3 (the
primitive, `:316-518`), §3.3 (ownership, `:390-405`), §3.4 (cursor and the
mid-fold mutation case, `:406-456`), §6.2 (the tests owed, `:714-742`) and §7's
open question about enumeration order (`:858-862`) in full. Read §5 for the
scope boundary.

### Read the merged `SetCell` implementation first — it is your template

`V1C-KERNEL` merged into `main` before this wave started. Before designing
anything, read what actually shipped:

- `kernel/src/main/kotlin/civictech/cell/BoundedRead.kt` — the real signatures.
  `V1C-KERNEL`'s ticket text is a *sketch*; the merged file is the contract. If
  they differ, the merged file wins and the divergence goes in your report.
- `SetCell`'s `readBounded` — its cursor encoding, how it orders an
  element-keyed map whose element type is not `Comparable`, how it stamps
  `frontier`, how it counts `exclusivesElided`, and how its KDoc states the
  per-page/across-page contract. **Copy this pattern.** Six cells implementing
  one interface six different ways is a worse outcome than six cells that all
  look like `SetCell`.

### What C8 resolved for you, before you go looking

`V1C-KERNEL` merged at `4f633d2`. It shipped four things this ticket was written
without, three of which close questions this ticket explicitly left open and told
you to raise as interface-shape findings. **They are answered; do not re-raise
them, and do not invent a second mechanism beside them.**

1. **A cell says "I cannot honour `since`/`scope`" by declaring it, and the host
   refuses the request before anything is submitted.** `BoundedStateful` carries
   `val supportsSince: Boolean get() = false` and `val supportsScope: Boolean get() = false`.
   `ManagedHost.readState` reads them **on the caller's thread** and answers
   `Unavailable(SINCE_UNSUPPORTED)` / `Unavailable(SCOPE_UNSUPPORTED)` without a
   scheduler round trip; `Interest.Total` is not a narrowing and is never
   refused. This is the mechanism this ticket's "decide how the shipped
   `BoundedRead.kt` lets a cell say so" asked for, and it answers the `scope`
   half by the same rule. So: `MapCell`, `ListCell`, `WatermarkCell` and
   `InstanceSet` **override neither** — the safe default refuses for them, and
   your `readBounded` never has to check. `KeyedSetCell` and `ShardCell`
   override `supportsSince = true`; `ShardCell` also `supportsScope = true`
   (it already scopes a pull with its `keyFn`), and `MapCell`/`KeyedSetCell`
   override `supportsScope` only if you can apply `Interest.admits(key)` without
   inventing a key semantics the cell does not have. **Both properties must be
   constants for the cell's lifetime** — they are read off-thread.
2. **`StatePage.attributes: Map<String, Serializable>` exists**, and is where
   cell-level state that is not a per-entry row goes. This answers this ticket's
   "did `StatePage` carry them naturally or did you have to encode them into
   entries" for both open cases: `ShardCell`'s `interest`/`assignedEpoch` and
   `KeyedSetCell`'s `tagCounter` ride `attributes` on **every** page, not
   inside entries and not only on page 1. `SetCell` sets the precedent with its
   tag `counter`. Attributes do not count against `limit`.
3. **A positional cursor declares itself in the types, not only in KDoc.**
   `ReadCaveat.POSITIONAL_CURSOR` shipped for exactly this, and `StatePage`
   carries `caveats: Set<ReadCaveat>`. `ListCell` must set it on **every** page
   it returns. Keep the KDoc sentence this ticket specifies as well — the
   caveat is what a machine reads, the KDoc is what a maintainer reads.
4. **`readBounded` returns a bare `StatePage`.** `Unavailable` and `Unbounded`
   are minted by `ManagedHost.readState`, never by a cell — so where this ticket
   says a non-implementing cell "answers `Unavailable(NOT_BOUNDED)`", read that
   as *the host answers that on its behalf*. A cell that cannot produce a page
   throws or returns an empty one; a throw is caught and reported as
   `Unavailable(READ_FAILED)`.

One inherited-decision correction, from the same audit. `StatePage`'s
across-page contract now reads "equal endpoint frontiers ⇒ the union is exactly
a snapshot **for a family in which every state change mints or absorbs a tag**",
because the check detects tag *gains* and only tag gains. `SetCell`'s
observed-remove mints no tag, so a mid-walk removal is invisible to it. Two
consequences for you: the frontier need only be **exact on the first and last
page** of a walk (an intermediate page may carry the opening stamp plus
`ReadCaveat.STALE_FRONTIER`, which is what `SetCell` does, precisely because a
per-page exact frontier costs an O(n) rescan per page and `ShardCell`'s
`currentFrontier` has the same shape); and any cell of yours whose mutations do
not all mint tags must say so on its own `readBounded`, in `SetCell`'s terms.

### The parallel tickets, and the file split that keeps you out of each other's way

Wave 9 runs three tickets concurrently from the post-wave-8 `main`:

| Ticket | Owns |
|---|---|
| **V1C-CELLS** (you) | the six files listed under "The work" |
| `V1C-OPS` | `kernel/src/main/kotlin/civictech/cell/data/op/**` — the composite operator cells (`JoinCell`, `LookupJoinCell`, `GroupByCell`, `MergeableGroupByCell`, `JoinSetCell`, `QuorumSetCell`, `IntersectSetCell`, `CombineLatestCell`) |
| `V4-PEERID` | `LocationRegistry.kt`, `Peering.kt`, `Peers.kt`, the demo launchers |

`V1C-OPS` codes against the **same** `BoundedRead.kt` you do, at the same time.
That is why any change to the interface is a cross-ticket event (see Report on
completion) and not a local decision.

`../10-design-notes.md` §"Standing file split" (`:148-153`) and §"Binding
constraints" (`:127-146`) govern this ticket: 1/P2 (nothing new on the
per-message path), 2/P6 (a read raises no attention), 3/ownership (never
consume, copy or delay an `Owned`/`Leased`), 5/kernel stays transport-neutral,
6/viz never blocks, 7/no `concord/` edits.

## Problem

**1. The primitive has one implementation, on the one cell family that needed it
least.** `SetCell` proves the interface; it does not make the inspector useful.
The cells a real graph gets big in — a `MapCell` holding a materialized view, a
`ShardCell` holding a partition's whole key range, a `WatermarkCell` in a
many-replica mesh — all still answer `Unavailable(NOT_BOUNDED)`, or
`Unbounded(snapshot)` if the caller opts into paying for the whole copy. The
inspector's user-visible confession ("read only to the first 200 rows",
`inspect/src/main/kotlin/civictech/inspect/DataSearch.kt:321-322`) is unchanged
for every one of them.

**2. Five of the six are not plain maps, and the differences are exactly where a
naive implementation gets it wrong.**

- `ShardCell`'s snapshot is a *composite triple* — `(TagState, interest,
  assignedEpoch)`, normative at
  `doc/spec/20-dataflow-semantics/24-data-cells.md:673-682` (`[24-SHARD-01]` at
  `:677`). Two thirds of it is not enumerable at all; it is context that every
  page needs.
- `WatermarkCell` carries **four independent lattices** (`Watermark.kt:36-53`,
  fields at `:68-73`), one of them a nested map. A cursor has to order across
  sub-states as well as within them — the precise thing design-note §7
  (`:858-862`) records as *unverified*.
- `KeyedSetCell`'s snapshot is `{"current": …, "counter": …}`
  (`KeyedSetCell.kt:117-123`): a map plus a scalar that is state
  (`:113-116`).
- `ListCell` has **no key at all** (`ListCell.kt:29`) — the one family for which
  the decided key-based cursor is not available.
- `WatermarkCell` and `InstanceSet` are replication-lattice cells whose reads sit
  next to the machinery a wave-neutrality regression would corrupt.

**3. Enumeration order is assumed, not established.** `mutableMapOf()` yields a
`LinkedHashMap`, so today's iteration order is insertion order — but `restore()`
rebuilds every one of these maps from a `HashMap`/`HashSet`
(`MapCell.kt:52-55`, `KeyedSetCell.kt:125-133`, `Watermark.kt:218-231`,
`InstanceSet.kt:155-159`, `TagState.kt:126-129`), so a checkpoint-restored cell
enumerates in a *different* order than the instance that wrote it. Worse, inside
a single instance an entry removed and re-added moves to the **tail** of
insertion order (`TagState.kt:57` removes, `:72` re-inserts;
`MapCell.kt:39`/`:34`), which can hand a key to a walk twice. Neither is a
theoretical concern: both are reachable in the tests this repository already
runs.

## Solution direction

The **what** is decided. The **how** — helper placement, encoding, whether a
shared internal paging helper is worth extracting — is yours, subject to one
rule: **do not create a shared abstraction that `V1C-OPS` would also have to
edit.** If a helper is genuinely worth having, put it somewhere `V1C-OPS`'s
files do not need to change to use it, and say so in the report.

### Inherited decisions — cite them, do not re-litigate them

All six are `V1C-KERNEL`'s, restated here only so you can check your work
against them without a second file open:

1. **The cursor is opaque, cell-minted, and key-based — never index-based.** An
   index is invalidated by any removal earlier in the enumeration; a key survives
   every mutation except its own removal. Recommended payload: the last-returned
   key plus the frontier at page time. (`V1C-KERNEL` Decision 4; design note
   §3.4 `:408-415`.)
2. **`limit` is a hard cap on entries. `byteBudget` is advisory and
   cell-estimated** — a cell that cannot estimate ignores it. (Decision 4;
   `:452-456`.)
3. **Entries are whole, never split across pages; no key twice in one walk; each
   page carries the fold's `TagFrontier` at page time.** (Decision 5;
   `:419-426`.)
4. **Across pages, stability is verifiable, not promised**: equal `frontier` on
   every page ⇒ the union of pages is exactly a snapshot of that fold; an
   advanced frontier ⇒ a documented *smear* (contains every entry present for
   the whole walk, may contain mid-walk additions, may miss mid-walk removals
   that were passed over) — never torn at entry granularity, never duplicated.
   (Decision 5; `:428-436`.)
5. **Ownership: a page NEVER carries an `Owned`/`Leased` value or a copy of
   one.** An exclusive entry pages as a *presence descriptor* — key, declared
   type name, disposition — and increments `exclusivesElided`. `Serializable`
   implies copy, and copying an exclusive payload is the prohibition itself
   (`doc/spec/20-dataflow-semantics/23-ownership.md:19-21`, taps `:112-151`); a
   read is weaker than a tap, so anything a tap may not do a read certainly may
   not. (Decision 3; design note §3.3.)
6. **`snapshot()` stays byte-for-byte behaviourally unchanged on every cell.**
   Four subsystems depend on it being a whole, restorable value: drain
   (`ManagedHost.kt:499-502`, `snapshots[cellRef] = cell.snapshot()` at `:501`),
   migration (`:1070-1072`), promotion state transfer
   (`kernel/src/main/kotlin/civictech/cell/evolve/Evolution.kt:190-198`,
   `importFrom((incumbent as Stateful).snapshot())` at `:191`) and durability
   checkpoints (`24-data-cells.md:566-569`, `[24-DUR-02]`). **Additive only** —
   your diff adds an interface and a method to each class and changes no existing
   line of behaviour.

### The work: exactly six cells

| File | `Stateful` at | `snapshot()` at | Backing state |
|---|---|---|---|
| `kernel/src/main/kotlin/civictech/cell/data/MapCell.kt` | `:26` | `:49` (`HashMap(state)`) | `mutableMapOf<K, V>()` (`:27`) |
| `kernel/src/main/kotlin/civictech/cell/data/KeyedSetCell.kt` | `:54` | `:117-123` | `mutableMapOf<K, Entry<E>>` (`:60`) + `tagCounter` (`:68`) |
| `kernel/src/main/kotlin/civictech/cell/data/ListCell.kt` | `:28` | `:62` (`ArrayList(state)`) | `mutableListOf<E>()` (`:29`) |
| `kernel/src/main/kotlin/civictech/cell/data/Watermark.kt` | `:56` | `:210-216` | four lattices (`:68-73`) |
| `kernel/src/main/kotlin/civictech/cell/replication/InstanceSet.kt` | `:82` | `:153` (`HashMap(table)`) | `mutableMapOf<CellRef, Assignment>` (`:84`) |
| `kernel/src/main/kotlin/civictech/cell/partition/ShardCell.kt` | `:49` | `:185` | `TagState<E>` (`:51`) + two `@Volatile` fields (`:53-57`) |

Note `InstanceSet.kt` lives under `cell/replication/`, **not** `cell/data/`, and
`ShardCell.kt` under `cell/partition/`. Both are in scope; their package is the
reason their tests are in unexpected places (see Verify).

### Explicitly excluded, with the reason

- **`kernel/src/main/kotlin/civictech/cell/data/SetCell.kt`** — `V1C-KERNEL`
  owns it as the reference implementation and already shipped it. Touching it is
  a merge collision with a *merged* ticket and a rewrite of the pattern you are
  supposed to be copying. Read it; do not edit it.
- **`CounterCell.kt`** (`:27`, `snapshot()` at `:48` — a bare `Long`) and
  **`PnCounterCell.kt`** (`:33`, `snapshot()` at `:110-111` — two maps keyed by
  *replica slot*, so O(instances-ever-seen), bounded by the mesh rather than by
  the data). Both page in one. Implementing the interface on them buys nothing
  and adds surface. Design note §5 `:648-650` says the same.
- **Everything under `kernel/src/main/kotlin/civictech/cell/data/op/**`** —
  `V1C-OPS` owns it, in this same wave, in parallel. Not one line.
- **`kernel/src/main/kotlin/civictech/cell/BoundedRead.kt` and
  `ManagedHost.kt`** — `V1C-KERNEL`'s, merged. If you believe one of them must
  change to serve these six, see "Report on completion": that is a cross-ticket
  event, flagged loudly, not an edit you make quietly while `V1C-OPS` is coding
  against the same file.

---

### Per-cell design: layout, entry granularity, cursor key, enumeration order

For each cell state, in your KDoc, **what one entry is**, **what the cursor
names**, **what order the walk uses**, and **what `frontier` is** (including
`null`, when null is the honest answer).

#### 1. `MapCell` — the simple case, and the order trap

State: `private val state = mutableMapOf<K, V>()` (`MapCell.kt:27`), written by
`put`/`remove` (`:33-41`), snapshotted as `HashMap(state)` (`:49`), restored
from a `Map<K, V>` (`:52-55`).

- **Entry** = one `(key, value)` pair.
- **Cursor key** = `K`. `K` is arbitrary and not necessarily `Comparable` — use
  the same total-order discipline `SetCell` uses for its element-keyed maps.
- **Enumeration order**: `mutableMapOf()` is a `LinkedHashMap`, so *today's*
  order is insertion order — but `restore()` (`:52-55`) refills it from the
  `HashMap` that `snapshot()` produced, so a restored instance enumerates
  differently, and `remove` then `put` of the same key moves it to the tail. An
  insertion-order walk can therefore return a key twice. **Impose an order**;
  do not inherit one.
- **`frontier`**: `MapCell` mints no tags. `TagFrontier` is documented as valid
  "only for per-source-monotone tag families (full-state fallback otherwise,
  `since = null`)" (`kernel/src/main/kotlin/civictech/cell/MessageContext.kt:58-72`,
  the sentence at `:65-67`). So `frontier = null`, and the KDoc must say what
  that costs the caller — see "The untagged-cell consequence" below.

#### 2. `KeyedSetCell` — a map plus a scalar that is state

State: `current: MutableMap<K, Entry<E>>` where `Entry` is `(element, tag)`
(`KeyedSetCell.kt:56`, `:60`), plus `tagCounter` (`:68`) which the snapshot
comment at `:113-116` explains is genuinely state (a restored instance must not
re-mint tags the network already saw).

- **Entry** = one `(key, element, tag)` triple. The tag is one `Timestamp`, so an
  entry is small and never needs splitting.
- **Cursor key** = `K`, as for `MapCell`.
- **Enumeration order**: same `LinkedHashMap` trap as `MapCell`; note `put`
  (`:74-90`) writes `current[key] = Entry(...)` in place for an existing key
  (which preserves position) but `remove` (`:92-96`) then `put` does not.
- **`tagCounter` is not an entry.** Do not page it as one and do not omit it: it
  is per-walk context in the same sense as `ShardCell`'s `interest` (below).
  Either carry it on every page the way `ShardCell` carries its triple, or leave
  it out of the paged view entirely and say so in the KDoc — pick one, justify
  it, and be consistent with what you do for `ShardCell`.
- **`frontier`**: `KeyedSetCell` *does* mint per-source-monotone tags from a
  derived `tagSource` (`:66-68`), so `TagFrontier(mapOf(tagSource to
  tagCounter))` is a real, honest frontier. Prefer it to null.

#### 3. `ListCell` — the family with no key. Read this one twice.

State: `private val state = mutableListOf<E>()` (`ListCell.kt:29`) — an
`ArrayList`. Elements have no identity: duplicates are legal, `set(index, …)`
replaces positionally (`:46-49`), `removeAt(index)` shifts every later element
(`:51-54`).

**There is no key.** The decided key-based cursor (inherited decision 1) is
*unavailable* here, and the reason it was decided — "an index is invalidated by
any removal earlier in the enumeration" — describes exactly what a `ListCell`
cursor must live with.

Decided for you, so you do not spend the ticket on it:

- The cursor is **positional** for `ListCell`, and this is the single documented
  exception to decision 1.
- **Do not** add a per-element identity to repair it. That would mean new state
  on the fold path (P2, binding constraint 1) and a changed `snapshot()` shape
  (inherited decision 6) — both forbidden, for a diagnostic capability.
- The KDoc on `ListCell.readBounded` must state the weaker guarantee
  explicitly: *at a stable frontier the walk returns each element exactly once in
  list order; under mid-walk mutation a removal before the cursor can cause an
  element to be skipped and an insertion before the cursor can cause one to be
  returned twice.* Say it in those terms. Do not claim the key-based guarantee
  and do not quietly weaken the interface's KDoc to match `ListCell`.
- Test it: a mid-walk `removeAt(0)` and a mid-walk `add(0, …)`, asserting the
  documented behaviour rather than a stricter one.
- **`frontier` = null** — `ListCell` mints no tags either.

If you find a way to give `ListCell` a stable key that costs nothing on the fold
path and changes no snapshot bytes, take it, test it, and report it prominently —
but do not spend the ticket looking.

#### 4. `WatermarkCell` — four lattices, one walk

State (`Watermark.kt:68-73`), documented as four independent lattices at
`:36-53`:

1. `rows: MutableMap<UUID, MutableMap<UUID, Long>>` — per-`(replica, source)`
   delivered counters. **Nested.**
2. `closed: MutableSet<UUID>` — cleanly-departed replica slots.
3. `suspendEpoch: MutableMap<UUID, Long>` — per-slot suspend epoch (odd =
   suspended).
4. `members: MutableSet<UUID>` — announced covering members.

- **Entry granularity**: for `rows`, one entry is a **`(replica, source, thru)`
  triple**, not a whole replica row. A replica row is itself unbounded in the
  number of sources; making the row the entry reintroduces exactly the unbounded
  copy this primitive exists to remove. For the other three lanes an entry is
  one slot (plus its epoch, for `suspendEpoch`).
- **Cursor key** = `(lane, key)` — the lane discriminator plus, for `rows`, the
  composite `(replicaSlot, sourceId)`. **The lanes must be walked in a fixed,
  documented order** (the snapshot's own order — `rows`, `closed`, `suspended`,
  `members`, `:210-216` — is the obvious choice); a cursor that does not name its
  lane cannot resume across a lane boundary. This is design-note §7's
  "order across sub-states as well as within them" (`:858-862`), made concrete.
- **Enumeration order**: every key here is a `UUID`, which is `Comparable`.
  Sort. There is no excuse for an insertion-ordered walk in this cell.
- **`frontier`**: null. The lattice's contents *look* like a frontier and are
  not one — `rows` is a delivered-watermark lattice over `(replica, source)`,
  not this fold's tag frontier, and `Replication.kt:236-238` is explicit that the
  per-outlet-epoch lane and the per-origin lane are distinct key spaces.
  Reporting the lattice as the page's `TagFrontier` would be a category error
  with a type that happens to fit. Null, not a guess.

#### 5. `InstanceSet` — the cleanest of the six

State: `private val table = mutableMapOf<CellRef, Assignment>()`
(`InstanceSet.kt:84`), where `Assignment` is `(interest, epoch)`
(`InstanceSet.kt:33-38`). Snapshot is `HashMap(table)` (`:153`).

- **Entry** = one `(CellRef, Assignment)` pair. `Assignment` is small and whole.
- **Cursor key** = `CellRef`, which is `(id: UUID, instanceId: Long)`
  (`kernel/src/main/kotlin/civictech/cell/CellRef.kt:17-22`) — a clean, sortable,
  total order.
- **Enumeration order**: sorted by `(id, instanceId)`.
- **`frontier`**: null. The `epoch` on an `Assignment` is a routing epoch, not a
  tag counter, and there is no per-source tag lane here.
- **Do not compute `overlapCount()` (`:113-120`) or `isDisjoint()` (`:127`) as
  part of a read.** They are O(instances²) and are not state; a bounded read
  that runs a quadratic scan to answer "the first 200 entries" has missed the
  point of the ticket.

#### 6. `ShardCell` — composite state (hazard 1, below)

Covered in its own section.

### The untagged-cell consequence — document it, do not paper over it

Four of the six (`MapCell`, `ListCell`, `WatermarkCell`, `InstanceSet`) cannot
produce a `TagFrontier`. Three things follow, and all three belong in the KDoc:

1. **Stability across pages is neither promised nor verifiable** for those cells.
   Decision 5's "equal frontier on every page ⇒ union == snapshot" is a check the
   caller performs on the stamps; with `frontier = null` on every page there is
   nothing to check. The walk is a smear and the caller cannot tell.
2. **The `since`-based escalation path** (`V1C-KERNEL` Decision 5; design note
   `:445-450`) is unavailable for them, because it is built out of the same
   stamp.
3. **A non-null `StateRead.since` cannot be honoured** by those cells. The
   existing repository rule for exactly this case is
   `MessageContext.kt:65-67`: non-tag families take the *full-state fallback*
   with `since = null`. A cell must not silently return full state as though it
   were the requested delta. Decide how the shipped `BoundedRead.kt` lets a cell
   say so — if it does not, that is an interface-shape finding and goes in the
   report under the cross-ticket flag, **not** an edit to `BoundedRead.kt`.

The same reasoning applies to `StateRead.scope`. `ShardCell` can honour it — it
holds a `keyFn` (`ShardCell.kt:46`) and already scopes a pull with it
(`contentsSince` at `:162-168`). `MapCell` and `KeyedSetCell` have a key domain
and *may* apply `Interest.admits(key)`
(`kernel/src/main/kotlin/civictech/cell/link/Interest.kt:41`) if you can do it
without inventing a key semantics the cell does not have. `ListCell`,
`WatermarkCell` and `InstanceSet` have no key domain an `Interest` is defined
over. Same rule as `since`: never return unscoped data as though it were scoped.

---

## Hazard 1 — `ShardCell` is composite, and its context must ride every page

`ShardCell`'s recoverable state is a triple, and the spec says so normatively:

> `[24-SHARD-01]` A `ShardCell`'s `Stateful` snapshot SHALL be `(TagState,
> interest, assignedEpoch)`; `interest` SHALL be snapshotted state, never
> re-read from the constructor on recovery …
> (`doc/spec/20-dataflow-semantics/24-data-cells.md:673-682`, the id at `:677`)

In code: `snapshot()` is `arrayListOf(state.snapshot(), interestField,
assignedEpochField)` (`ShardCell.kt:185`), over `state: TagState<E>` (`:51`) and
two `@Volatile` fields (`:53-57`) exposed as `interest` (`:66`) and
`assignedEpoch` (`:69`), whose KDoc explains they are *snapshotted state, not
constructor constants* because `PartitionedShardSet.rebuildFrom` reads them back
to recompute the routing table.

**Decided: `interest` and `assignedEpoch` ride EVERY page, not just the first.**

The reason is a consumer that reassembles pages. `assign` (`:146-153`) can run
between two pages of a walk: it sheds every element the new interest no longer
admits (`:148-149`) and then swaps `interestField` and raises
`assignedEpochField` (`:151-152`). A consumer holding page 1's interest and page
7's entries would attribute entries to the wrong key range and the wrong routing
epoch — a partition-membership claim that was never true. Carrying the pair on
every page makes that error unrepresentable: a consumer either sees a constant
pair across the walk (and may attribute) or sees it change (and knows the walk
straddled a repartition). Same discipline as `frontier`, same reason.

- **Entry** = one `(element, tags)` pair out of `TagState.live`
  (`TagState.kt:19`) — the same shape `asDelta()` (`:37`) produces and the same
  shape `SetCell` pages.
- **Cursor key** = the element `E`; `E` is arbitrary and not `Comparable`, so
  reuse `SetCell`'s ordering discipline verbatim. `TagState` is `internal`
  (`TagState.kt:18`) and `ShardCell` is in the same Gradle module, so you can
  reach it; prefer adding what you need to `TagState` **only** if `SetCell`
  already did, since `V1C-OPS`'s cells consume `TagState` too.
- **Enumeration order**: `live` is a `LinkedHashMap` (`TagState.kt:19`), and
  `foldDels` removes an element whose tags all die (`:57`) while `apply`
  re-inserts it at the tail (`:72`). Insertion order is therefore *not* stable
  under ordinary set churn. Impose an order.
- **`frontier`**: real, and already computed here — `currentFrontier(scope)`
  (`:171-179`) is the exact "highest tag counter per source over the
  scope-admitted keys" the page wants, and is what the pull reply already reports
  (`:114`). Reuse it; do not write a second one.

**What a bounded read must not disturb about repartitioning.** Two requirements
sit either side of this cell:

- `[24-SHARD-03]` (`:698`): the partitioned router holds no durable state of its
  own — the routing table is recomputed by `rebuildFrom(shards)`, "asking each
  restored shard what interest and epoch it holds". A read must therefore **not
  mutate `interestField`/`assignedEpochField`, not call `assign` (`:146-153`),
  and not cause the router to observe a different pair than it would have.** The
  read is a reader of the same two fields `rebuildFrom` reads.
- `[24-SHARD-04]` (`:706`): a recovering shard reconstructs under its *current*
  post-repartition interest and drops pre-repartition frames for the range it
  shed; reconstructing under the constructor's `initialInterest` resurrects the
  moved range on two shards at once. A bounded read must not shed, must not
  re-admit, and must not touch the WAL — it is not an invocation, does not arrive
  through `routeInlet`/`assignInlet` (`:72`, `:83`), and must not be made to.

Also: `ShardCell` installs a `StateRequest` handler at `:110-117` that replies
via `outlet.baselineTo` (`:114`). Your `readBounded` is **not** that path and
must not reuse it. Nothing you add may call `baselineTo`, `originate`, `call` or
`emit` (`:135-137`).

## Hazard 2 — `WatermarkCell` and `InstanceSet` are replication lattice state

Both are `Replicable` (`Watermark.kt:55-56`, `InstanceSet.kt:79-82`) and both sit
inside the replication mesh. A read here must **advance no lattice row and fire
no tap.**

Read these before implementing either:

- `Watermark.kt:188-208` — `trackDeliveriesOf`. Its KDoc (`:188-202`) states the
  seam precisely: a tap on the tracked data outlet advances the watermark for
  every effective wave, from the emission's `CurrentContext` (`:205`), and
  "broadcast through the outlet's `call`/`originate` … fire taps; targeted
  `at`-catch-up does not". The tap is installed at `:203-208`.
- `kernel/src/main/kotlin/civictech/cell/replication/Replication.kt:220-241` —
  `trackDeliveries`, which wires a `WatermarkCell` companion per replicated cell:
  `announceMember()` at `:229`, the per-origin `onDeliver` bridge at `:233-235`,
  and `trackDeliveriesOf(cell.outlet)` at `:240`.

Concretely, `readBounded` on these two cells must not call: `advance` (`:111-116`),
`close` (`:119-122`), `suspend`/`resume` (`:130-145`), `announceMember`
(`:102-105`), `applyRemote` (`:148-174`) on `WatermarkCell`; `assign` (`:100`),
`applyOne` (`:145-151`) or `onGossip` (`:139-143`) on `InstanceSet`. It must not
emit on `outlet`, must not enter `CurrentContext`, and must not construct a
`WatermarkDelta`/`AssignmentDelta`. Reading `rows()` (`:79`), `closed()` (`:80`),
`suspended()` (`:83`), `members()` (`:92`) and `entries()` (`:110`) is safe but
allocates a full copy — which is the very cost you are removing, so enumerate the
backing structures directly rather than paging a copy of the whole thing.

**A neutrality regression shows up in
`kernel/src/test/kotlin/civictech/cell/replication/DeliveredWatermarkTest.kt`** —
its three tests (`:62`, `:97`, `:135`) assert that each peer's watermark
converges to every source's true delivered frontier, hold that under a 100-seed
mesh with a mid-run partition and heal, and keep a never-delivering peer
individually absent from the merged lattice. It must stay green and
**unmodified**. Add a direct assertion of your own too (see Acceptance criteria):
a full walk of a `WatermarkCell` leaves `rows()` equal before and after, fires
the delivery tap zero times, and produces no dead letters.

---

## Files expected to touch

- `kernel/src/main/kotlin/civictech/cell/data/MapCell.kt`
- `kernel/src/main/kotlin/civictech/cell/data/KeyedSetCell.kt`
- `kernel/src/main/kotlin/civictech/cell/data/ListCell.kt`
- `kernel/src/main/kotlin/civictech/cell/data/Watermark.kt`
- `kernel/src/main/kotlin/civictech/cell/replication/InstanceSet.kt`
- `kernel/src/main/kotlin/civictech/cell/partition/ShardCell.kt`

Each change is: add `BoundedStateful` to the class's supertype list, add
`readBounded`, add KDoc. **No existing line changes behaviour.**

- **New tests** under `kernel/src/test/kotlin/civictech/cell/data/`,
  `.../replication/` and a **new** `kernel/src/test/kotlin/civictech/cell/partition/`
  directory (it does not exist today — see Verify).
- `kernel/src/main/kotlin/civictech/cell/data/delta/TagState.kt` — **only** if
  `SetCell`'s merged implementation already added an enumeration/paging helper
  there that `ShardCell` should reuse. Adding a *new* one here is a shared-file
  edit `V1C-OPS` may also want; if you need one, say so in the report before
  assuming it is safe.

Nothing else. In particular nothing under `kernel/src/main/kotlin/civictech/cell/data/op/**`
(`V1C-OPS`), `SetCell.kt`, `BoundedRead.kt`, `ManagedHost.kt`, `inspect/**`
(`V1C-BE`), `wire/**`, `concord/**`, `gen/**`, `nature/**`, `testkit/**`,
`demo/**`.

Touching files outside this list: note it in the completion report rather than
expanding silently.

## Read first

- `doc/spec/90-roadmap/98-inspector-v4-plan/tickets/V1C-KERNEL.md` — the whole
  ticket. Decisions 3, 4, 5, 8 and 9 are the ones you implement against.
- `kernel/src/main/kotlin/civictech/cell/BoundedRead.kt` (merged) and
  `kernel/src/main/kotlin/civictech/cell/data/SetCell.kt` (whole file;
  `pullServe` at `:185-196`, `snapshot()` at `:200-208`) — the real signatures
  and the pattern to copy.
- `doc/spec/90-roadmap/98-inspector-v4-plan/20-wave-neutral-read-design.md` §3
  (`:316-518`), §3.3 (`:390-405`), §3.4 (`:406-456`), §5 (`:626-680`), §6
  (`:681-793`), §7 (`:795-862` — the open question at `:858-862` is *your*
  question).
- `doc/spec/90-roadmap/98-inspector-v4-plan/10-design-notes.md` §"Binding
  constraints" (`:127-146`) and §"Standing file split" (`:148-153`).
- `doc/spec/20-dataflow-semantics/24-data-cells.md:673-712` — the shard
  obligations: `[24-SHARD-01]` `:677`, `[24-SHARD-02]` `:689`, `[24-SHARD-03]`
  `:698`, `[24-SHARD-04]` `:706`. Also `:566-569` (`[24-DUR-02]`, why
  `snapshot()` may not move).
- `doc/spec/20-dataflow-semantics/23-ownership.md:19-21` and `:112-151` — the
  copy prohibition and the borrow-only tap contract your page is weaker than.
- `kernel/src/main/kotlin/civictech/cell/MessageContext.kt:58-72` — `TagFrontier`
  and, at `:65-67`, the existing rule for non-tag families.
- `kernel/src/main/kotlin/civictech/cell/data/delta/TagState.kt` — the whole file
  (`live` at `:19`, `elements`/`tags` at `:31-34`, `asDelta()` at `:37`, the
  remove at `:57` and the re-insert at `:72` that make insertion order unstable).
- Each of the six cell files, in full.
- `kernel/src/main/kotlin/civictech/cell/replication/Replication.kt:220-241`.
- `kernel/src/main/kotlin/civictech/cell/link/Interest.kt:38-41`, `:84-89` —
  `admits`/`overlaps` and `Total`.
- Existing tests that bound your change, all of which must stay green **and
  unmodified**:
  `kernel/src/test/kotlin/civictech/cell/replication/DeliveredWatermarkTest.kt`
  (`:62`, `:97`, `:135`);
  `kernel/src/test/kotlin/civictech/cell/data/LateJoinCatchUpTest.kt` (the
  `Stateful` migrate round-trip at `:97-115`, `migrate` at `:111` — it exercises
  `SetCell`, so it guards the *seam*, not your six cells);
  `kernel/src/test/kotlin/civictech/cell/data/InstanceSetSubstrateTest.kt:72-75`
  (the `InstanceSet` snapshot/restore round-trip — this one *does* guard one of
  yours); `kernel/src/test/kotlin/civictech/cell/data/ShardJournalReplayTest.kt`
  (`:140` checkpoints a shard to its `Stateful` snapshot, `:203` recomputes the
  router epoch from the restored shards);
  `kernel/src/test/kotlin/civictech/cell/data/WatermarkCellTest.kt`,
  `MapCellTest.kt`, `ListCellTest.kt`, `KeyedSetCellTest.kt`,
  `PartitionedCellTest.kt`, `StatePullTest.kt`, `InterestScopedCatchUpTest.kt`,
  `kernel/src/test/kotlin/civictech/cell/port/PullServiceRefusalTest.kt` (a
  bounded read must not become a back door around a
  `PullOnOpen(requireServing = true)` refusal).
- `AGENTS.md` §"Core invariants to protect" and §"Verification".

Do not modify: `inspect/**`, `wire/**`, `gen/**`, `nature/**`, `concord/**`,
`testkit/**`, `demo/**`, `kernel/src/main/kotlin/civictech/cell/data/op/**`,
`SetCell.kt`, `BoundedRead.kt`, `ManagedHost.kt`, any plan document other than
this ticket's `**Status**:` line, and
`doc/spec/90-roadmap/97-inspector-plan/20-api-contract.md` (orchestrator-owned).

## Acceptance criteria

- [ ] **All six implement `BoundedStateful`.** `MapCell`, `KeyedSetCell`,
      `ListCell`, `WatermarkCell`, `InstanceSet`, `ShardCell` — no more, no
      fewer.
- [ ] **Union equals snapshot, per cell.** For each of the six: walking to
      completion at a stable frontier yields pages whose union equals the
      content of `snapshot()` exactly. (For `ShardCell` compare against
      `TagState`'s half of the triple, plus the pair carried on the pages.)
- [ ] **Per cell: `limit` is respected on every page**, the cursor resumes from
      where the previous page ended, the final page carries `next == null`, and
      no key appears twice in one walk. Test with `limit` smaller than the state
      so at least three pages are produced, and with `limit` larger than the
      state so the walk is a single page.
- [ ] **Per cell: mid-walk mutation yields the documented smear** — every entry
      whole, no torn entry, no duplicate key — and never an exception. Assert the
      documented contract, not a stricter one. `ListCell`'s documented contract
      is the weaker positional one; assert *that*, and assert it is what the
      KDoc says.
- [ ] **Cursor invalidated mid-read.** For each cell whose cursor names a key:
      remove the named key between pages, then resume — the walk continues from
      the next key, never restarts, never throws, never returns an entry it
      already returned.
- [ ] **`ShardCell`: `interest` and `assignedEpoch` are present and identical on
      every page** of a walk over an unchanging shard; and a walk that straddles
      an `assign` (`ShardCell.kt:146-153`) shows the change on the pages after it
      rather than silently mixing key ranges under page 1's interest.
- [ ] **`ShardCell` reads disturb no repartition state**: after any number of
      walks, `interest` and `assignedEpoch` are unchanged, `membership()`
      (`:156`) is unchanged, nothing was emitted on `outlet`, and a
      `rebuildFrom`-style read of the shard set yields the same routing table it
      would have without the walk (`[24-SHARD-03]`).
- [ ] **`WatermarkCell`/`InstanceSet` neutrality, asserted not claimed.** A full
      walk of each: `rows()`/`closed()`/`suspended()`/`members()` (resp.
      `entries()`) are equal before and after; the delivery tap installed by
      `trackDeliveriesOf` (`Watermark.kt:203-208`) fires zero times; no
      delivered-watermark row advances; nothing is emitted on `outlet`; there are
      no dead letters.
- [ ] **`DeliveredWatermarkTest` stays green and unmodified** — all three tests
      (`:62`, `:97`, `:135`).
- [ ] **Enumeration order is stable and asserted, per cell.** Not "iteration
      order happens to work": an explicit total order, with a test that (a) two
      walks of an unchanged cell return keys in the same order, (b) a
      `snapshot()`/`restore()` round-trip into a fresh instance produces the same
      walk order as the original (the `HashMap`-rebuild trap:
      `MapCell.kt:52-55`, `KeyedSetCell.kt:125-133`, `Watermark.kt:218-231`,
      `InstanceSet.kt:155-159`, `TagState.kt:126-129`), and (c) removing a key
      and re-adding it does not move it across an in-flight cursor
      (`TagState.kt:57`/`:72`).
- [ ] **`frontier` is honest per cell**: a real `TagFrontier` for `ShardCell`
      (from `currentFrontier`, `:171-179`) and `KeyedSetCell`; `null` for
      `MapCell`, `ListCell`, `WatermarkCell`, `InstanceSet` — with each cell's
      KDoc stating that a null frontier means stability across pages is neither
      promised nor verifiable, and that the `since`-escalation path is
      unavailable.
- [ ] **`since`/`scope` are never silently ignored.** A cell that cannot honour a
      non-null `since` or a non-Total `scope` does not return unscoped/full state
      as though it were the requested view. Whatever mechanism you use, name it in
      the report.
- [ ] **Ownership.** A cell of one of the six holding `Owned`/`Leased` values
      pages presence descriptors only: `exclusivesElided > 0`, `Owned.take` was
      never called, no discharge or dead-letter accounting moved, and no
      exclusive value or copy of one appears in any page. (`MapCell<K, V>` with
      an exclusive `V` is the natural subject; `ShardCell` over an exclusive
      element type is the second, and
      `kernel/src/test/kotlin/civictech/cell/replication/OwnedRoutedShardTest.kt`
      shows how that graph is built.)
- [ ] **`snapshot()` behaviour is unchanged on all six**, byte-for-byte
      behaviourally: `InstanceSetSubstrateTest.kt:72-75`,
      `ShardJournalReplayTest.kt`, `WatermarkCellTest.kt`, `MapCellTest.kt`,
      `ListCellTest.kt`, `KeyedSetCellTest.kt` and
      `LateJoinCatchUpTest.kt`'s migrate round-trip (`:97-115`) all pass
      **unmodified**.
- [ ] **P2, checkable.** No fold path (`inletHandler`, `serve`, `apply`,
      `onGossip`, `onRouted`, `applyRemote`) gained a statement. State this as a
      claim about the call sites you added, and name them.
- [ ] **P6.** A walk installs no link, fires no tap, raises no attention, and
      emits nothing on any outlet, for all six cells.
- [ ] Every added public member carries KDoc naming this ticket (`V1C-CELLS`),
      what one entry is, what the cursor names, what order the walk uses and what
      `frontier` means for that cell — in the register of `isSuspended`
      (`ManagedHost.kt:220-231`) and `attentionOf` (`:702-706`).
- [ ] Nothing under `kernel/src/main/kotlin/civictech/cell/data/op/`, no
      `SetCell.kt`, no `BoundedRead.kt`, no `ManagedHost.kt`, nothing under
      `inspect/`, `wire/` or `concord/` in the diff. No generated/build output.
      No gap (`G-*`) or consistency (`C-*`) markers removed.

## Verify

```bash
./gradlew :kernel:test --tests 'civictech.cell.data.*'
./gradlew :kernel:test --tests 'civictech.cell.replication.*'
./gradlew :kernel:test --tests 'civictech.cell.partition.*'
./gradlew :kernel:test
./gradlew test
./gradlew :demo:exchange:test
```

Two notes on the third line, verified against the current tree:

1. **`kernel/src/test/kotlin/civictech/cell/partition/` does not exist today.**
   Every existing `ShardCell` test lives in `civictech.cell.data`
   (`PartitionedCellTest.kt`, `ShardJournalReplayTest.kt`,
   `PartitionedPullTest.kt`, `BridgedRepartitionTest.kt`,
   `PartitionedShardsAcrossHostsTest.kt`, `PartitionedPromotionTest.kt`), even
   though the cell is in `civictech.cell.partition`. A `--tests` filter that
   matches nothing **fails** the Gradle build. Put your `ShardCell` bounded-read
   test in `kernel/src/test/kotlin/civictech/cell/partition/`, matching the class
   under test, so the command above is meaningful. Do not move the existing
   tests.
2. `:kernel:compileKotlin` depends on `:gen:test`, so a generator regression
   surfaces here as a kernel compile failure (`AGENTS.md` §Repository map).

`./gradlew :demo:exchange:test` is the composition exit gate — partitioned +
replicated + durable + glitch-free in one graph, and the strongest available
evidence that reads over `ShardCell` and `WatermarkCell` perturbed nothing.

## Report on completion

- Checks run and their results.
- **The cursor encoding chosen per cell**, verbatim, and **why it is stable
  under removal** — for each of the six, and for `ListCell` why it is not and
  what the documented weaker guarantee says.
- **Any cell whose enumeration order had to be imposed rather than inherited,
  and how** — the total order you used for the non-`Comparable` key types
  (`MapCell`'s `K`, `KeyedSetCell`'s `K`, `ShardCell`'s `E`), and whether it
  matches what merged `SetCell` does. If you diverged from `SetCell`, say why.
- **Whether the `V1C-KERNEL` interface shape needed any change to serve these
  six.** This is a **cross-ticket event and must be flagged loudly to the
  orchestrator**: `V1C-OPS` is running concurrently against the same
  `BoundedRead.kt` and `V1C-BE` (wave 10) is written against it too. Report the
  need; do not make the edit. In particular say what you did about:
  (a) a cell that cannot produce a `TagFrontier`; (b) a cell that cannot honour
  `since`; (c) a cell that cannot honour a non-Total `scope`; (d) `ShardCell`'s
  per-page `(interest, assignedEpoch)` context and `KeyedSetCell`'s
  `tagCounter` — whether `StatePage` carried them naturally or you had to
  encode them into entries.
- **The P2 claim, checkable**: every call site you added, and the statement that
  none is on a per-message path.
- **The neutrality evidence** for `WatermarkCell`/`InstanceSet`: exactly which
  properties you asserted before/after a walk, and whether
  `DeliveredWatermarkTest` needed anything.
- Whether you touched `TagState.kt`, and if so why it was safe given `V1C-OPS`
  consumes it concurrently.
- **Flag separately, as research input** (design note §7 proposes these for
  `doc/spec/90-roadmap/95-research-plan.md`; that file is owner-maintained and
  neither you nor the orchestrator edits it here): what you learned about
  (a) stable enumeration order for composite state families — §7's open question
  is yours to answer for these six and to report on for the rest;
  (b) cursor semantics across a scatter-gather boundary, now that you have
  implemented one shard's half (`42-replication.md:390-423`);
  (c) ownership in `Stateful.snapshot()` — the older, undefined seam this
  ticket, like `V1C-KERNEL`, declines to inherit.
- Anything specified here you could not do, and why — in particular anything you
  SKIPPED rather than reaching into a file another wave-9 ticket owns.
