# V1C-OPS — a cursor that must order across sub-states: bounded reads for the composite operator cells

**Status**: Specified — not-started
**Model:** `claude-opus-5` (effort xhigh) · **Escalate to:** `claude-opus-5`,
fresh session; if that fails, stop and re-split the ticket.
**Wave:** 9 · **Branches:** `ticket/v1c-ops`

## Context

ComputeNet is a Kotlin/JVM dataflow runtime: cells with typed ports, explicit
links, hosted execution, ownership-aware payloads. `ManagedHost`
(`kernel/src/main/kotlin/civictech/cell/host/ManagedHost.kt`) runs cells on
their own execution contexts.

Wave 8 landed `V1C-KERNEL`
(`doc/spec/90-roadmap/98-inspector-v4-plan/tickets/V1C-KERNEL.md`), which added
a **bounded state read** to the kernel: an opt-in cell interface
`BoundedStateful : Stateful` with `readBounded(StateRead): StatePage`, the
value types `StateRead` / `StatePage` / `Cursor` / `Provenance` /
`StateReadResult`, and `ManagedHost.readState(ref, request)` beside
`snapshotOf`. **Read that ticket in full before you start** — its nine
decisions are settled and you inherit every one of them. Do not re-litigate
them, and do not restate them at length in your code comments; cite them.

The four you will lean on hardest, in one line each:

- **Decision 3 (ownership)** — a page never carries an `Owned`/`Leased` value
  or a copy of one; an exclusive becomes a presence descriptor and increments
  `exclusivesElided`.
- **Decision 4 (bounding)** — the cursor is opaque, **cell-minted**, and
  **key-based, not index-based**; `limit` is a hard cap on entries,
  `byteBudget` is advisory and cell-estimated.
- **Decision 5 (stability)** — a walk is a sequence of per-page-consistent
  reads, not a snapshot. Per page: entries whole, never split; no entry twice
  in one walk; the fold's `TagFrontier` carried. Across pages: equal frontier
  ⇒ the union is exactly a snapshot; advanced frontier ⇒ the documented smear.
- **Decision 8 (scope)** — `V1C-KERNEL` implemented `SetCell` and *only*
  `SetCell`, as the reference. Wave 9 splits the rest between two tickets that
  run in parallel.

`V1C-KERNEL` also fixed the shape of the interface. If your work forces a
change to it, that is a **cross-ticket event** — see Report on completion.

### What C8 corrected in this ticket, before you design anything

`V1C-KERNEL` merged at `4f633d2` with a `StatePage` **wider than the sketch this
ticket was written against**. Three sentences below are now wrong, and one of
them would push you into a worse design if you followed it.

- **`StatePage` has an `attributes: Map<String, Serializable>` field.** So
  Decision D's scalar riders — `JoinSetCell`'s and `SemiJoinCell`'s mint
  counter, and anything else that is cell-level rather than per-entry — go
  **there**, on every page, rather than being smuggled into entries. `SetCell`
  sets the precedent with its tag `counter`, and `V1C-CELLS` puts `ShardCell`'s
  `interest`/`assignedEpoch` there. Attributes do not count against `limit`,
  which is the property Decision D asks for and could not get from an entry.
  **The instruction "Do not invent a third `StatePage` field for it" stands** —
  you still add no field; you use the one that shipped.
- **Decision A's sub-state label stays inside the entry.** `attributes` is
  keyed cell-level state, not a per-entry discriminator, so it is the wrong
  home for `"left"`/`"right"`/`"ledger"`. Everything this ticket says about
  labelling entries is unchanged; only the *rider* half moves.
- **`StatePage` also has `caveats: Set<ReadCaveat>`**, with arms
  `STALE_FRONTIER` and `POSITIONAL_CURSOR`. Two consequences. A composite whose
  frontier is expensive to recompute per page may stamp it exactly on the first
  and last page only and declare `STALE_FRONTIER` in between — that is what
  `SetCell` does, and Decision 5's stability check survives it because a
  `TagFrontier` is monotone, so equal endpoints prove every intermediate stamp
  equal. And if any sub-state of yours ends up positionally cursored, the page
  must say `POSITIONAL_CURSOR` rather than only saying it in KDoc.
- **`BoundedStateful` carries `supportsSince`/`supportsScope`, both defaulting
  to `false`,** and `ManagedHost.readState` refuses a `since`/`scope` a cell has
  not declared, on the caller's thread. A composite that cannot honour either
  simply does not override them — you write no check for it.

One correction to Decision 5 as restated above: the across-page claim is now
"equal endpoint frontiers ⇒ the union is exactly a snapshot **for a family in
which every state change mints or absorbs a tag**". The check detects tag
*gains* and only tag gains. If one of your composites can change state without
minting a tag — an operator whose sub-state is retracted rather than tombstoned
is the case to look for — its `readBounded` KDoc must say so, in the terms
`SetCell`'s does.

### Your place in wave 9

Wave 9 branches from `main` after wave 8 merged, and runs three tickets
concurrently:

- **`V1C-CELLS`** — the map/set-backed data cells: `cell/data/MapCell.kt`,
  `cell/data/KeyedSetCell.kt`, `cell/data/ListCell.kt`,
  `cell/data/Watermark.kt`, `cell/replication/InstanceSet.kt`,
  `cell/partition/ShardCell.kt`.
- **`V1C-OPS`** — this ticket: `kernel/src/main/kotlin/civictech/cell/data/op/**`.
- **`V4-PEERID`** — unrelated, no kernel/data overlap.

Your file claim is **`kernel/src/main/kotlin/civictech/cell/data/op/**` and the
tests you add under `kernel/src/test/kotlin/civictech/cell/data/op/`, and
nothing else.** The two `V1C-*` tickets are disjoint by directory precisely so
they can land independently; a stray edit outside your claim is the collision
the split exists to prevent.

### Why this ticket is not "V1C-CELLS but for a different directory"

A map- or set-backed cell pages trivially: **one key space, one enumeration
order, one cursor.** `SetCell` is the mildest possible composite — two tag maps
over the same element type `E` plus a scalar tag counter
(`kernel/src/main/kotlin/civictech/cell/data/SetCell.kt:44-45`, `:57`,
snapshot at `:200-207`) — and `V1C-KERNEL` solved it.

The operator cells are not that. The V1C design note
(`doc/spec/90-roadmap/98-inspector-v4-plan/20-wave-neutral-read-design.md`)
closes §7 with the one thing it could not determine from the code, and that
sentence is the reason this ticket exists:

> Whether every state family has a stable enumeration order. Verified for the
> map/set-backed cells; **not verified for the operator cells whose state is a
> composite of two or three sub-snapshots** (`JoinSetCell.kt:109-110`,
> `IntersectSetCell.kt:82-83`, `QuorumSetCell.kt:116`), where a cursor must
> order across sub-states as well as within them.

You are answering that question, for real, with tests.

## Problem

A composite operator cell's `snapshot()` is an `ArrayList` of two or three
**heterogeneous sub-snapshots** — typically a left index, a right index, and an
output ledger — whose key spaces are *different types* and, where they are the
same type, *not disjoint in content*. A cursor over such a cell must define a
**total order across sub-states as well as within them**. Get it wrong and
three distinct defects follow, all of which are silent:

1. **An entry is returned twice.** `IntersectSetCell` holds the same element
   `E` in `leftState`, in `rightState` and in the advertised `ledger`
   (`IntersectSetCell.kt:42-44`). A cursor that names only "the last key `e`"
   cannot say *which* of the three sub-states it had reached, so a resume
   either re-emits `e` from a sub-state already walked, or —
2. **A resume skips a whole sub-state.** A cursor that resolves "the key after
   `e`" against whichever sub-state it happens to consult first walks off the
   end of that sub-state and never enters the next one. The walk terminates
   with `next == null` having returned a strict subset of `snapshot()`, and
   nothing in the page reports the omission.
3. **A page is internally inconsistent across sub-states.** Even though the
   page was produced in one invocation on the cell's own execution context —
   so `V1C-KERNEL`'s per-page consistency holds — a page that mixes "the tail
   of `leftState`" with "the head of `ledger`" is only meaningful if the
   consumer can tell which is which. Today's `StatePage.entries: List<Serializable>`
   has no place to say so. (Nor does the shipped `attributes` map, which is
   cell-level; Decision A resolves this inside the entry.)

There is a fourth defect that only shows up when you read the backing
structures rather than the snapshot shapes: **insertion order is not a stable
enumeration order.** Every one of these cells stores its sub-states in
`mutableMapOf` (`= LinkedHashMap`) — `TagState.live`
(`kernel/src/main/kotlin/civictech/cell/data/delta/TagState.kt:19`),
`JoinCell.leftMap`/`rightMap` (`JoinCell.kt:30-31`),
`AdvertisedLedger.advertised` (`JoinLedger.kt:64`),
`MintedTags.advertised` (`kernel/src/main/kotlin/civictech/cell/data/delta/MintedTags.kt:24`),
`GroupByCell.groups` (`GroupByCell.kt:47`), and so on. Under insertion order a
key that is removed and re-added **moves to the end of the iteration**, so a
mid-walk remove/re-add returns that entry a second time — violating Decision
5's "no entry twice in one walk" for a mutation the design note explicitly
expects (§3.4's smear). The element and key types are arbitrary generics with
no `Comparable` bound, so a total order has to be *imposed*, not read off the
structure.

## Solution direction

The **what** is decided below. The **how** — helper placement, whether a
shared `op`-package paging helper is worth extracting, the concrete cursor
encoding — is yours.

### Decision A — an entry is `(subState, key)`, not `key`

The page must carry, per entry, **which sub-state it came from**. This is what
dissolves defect 1: the same element `e` appearing in `leftState`, `rightState`
and `ledger` is **three distinct entries**, not one entry returned three times,
because their identities differ in the sub-state component. Deduplicating
across sub-states would be *wrong* — the three carry different tag sets and a
consumer that cannot tell them apart cannot reconstruct the cell's state.

Each cell declares a fixed, documented **sub-state ordinal** per sub-state, in
the same order its `snapshot()` `arrayListOf(...)` uses, so the two orderings
never drift. Label the entry with a stable name (`"left"`, `"right"`,
`"ledger"`, `"groups"`, `"lanes"`, …), not a bare integer, so the page is
self-describing and a later encoder change cannot silently renumber it.

`StatePage.entries` is `List<Serializable>`; the label lives inside the entry
you construct. Do not change `StatePage` for this — `V1C-CELLS` runs
concurrently against the same type. (The shipped `StatePage.attributes` is
*not* the home for this label: it is cell-level, not per-entry. See "What C8
corrected in this ticket".)

### Decision B — the cursor is lexicographic `(subStateOrdinal, intraStateKey)`

Order sub-states by their declared ordinal; within a sub-state, order by the
intra-state key order of Decision C. The resulting order is total over the
whole composite, and both properties fall out of it directly:

- **No entry twice**: the order is a strict total order over `(ordinal, key)`
  pairs, and the pairs are unique by construction.
- **Resume lands in the right sub-state**: the cursor names the ordinal, so a
  resume that exhausts sub-state *i* continues at the head of *i+1* rather
  than terminating; a resume mid-sub-state continues within *i*.

**The vector-cursor escape hatch.** `20-wave-neutral-read-design.md` §7 item 3
anticipates that some composite may need "a vector of per-instance tokens"
rather than one token. If a cell here genuinely does — for instance because two
sub-states must be walked in lock-step for the page to mean anything — **take
the vector, document why, and flag it prominently in the report.** It is a
legitimate finding, not a failure; but a single lexicographic token is the
default and the burden of proof is on the vector.

### Decision C — intra-sub-state ordering: adopt `V1C-KERNEL`'s, verbatim

`V1C-KERNEL` had to impose a stable order over an arbitrary element type `E`
for `SetCell`'s two tag maps, and its report states the encoding it chose and
why it is stable under removal. **Read that, and use the same mechanism for
every element/key space here.** Do not invent a second ordering discipline:
`V1C-CELLS` is copying it too, and three divergent orderings across one
release is a maintenance defect regardless of which is individually better.

What you must additionally guarantee, and assert per cell:

- The order does **not** depend on `LinkedHashMap` insertion order (see
  Problem, defect 4). A remove-then-re-add mid-walk must not produce a
  duplicate.
- The order is deterministic within a process run for the same content, so a
  test can assert it rather than sort the result before comparing.

If `V1C-KERNEL`'s encoding turns out not to generalize to a key type used here
(e.g. `JoinSetCell`'s ledger is keyed by `Pair<A, B>`,
`JoinSetCell.kt:45`; `PresenceLanes`' outer key is a `UUID`,
`PresenceCountCell.kt:47-49`), extend it minimally and say so — do not fork it.

### Decision D — scalar riders ride **every** page

Two sub-states here are not enumerable collections but scalars that are part of
the restorable state:

- `MintedTags.snapshot()` is `arrayListOf(HashMap(advertised), counter)`
  (`MintedTags.kt:41`) — the mint counter is state (a restored instance must
  not reuse a spent tag, `MintedTags.kt:15-19`). It reaches you through
  `MintedLedger.snapshot()` (`JoinLedger.kt:58`), i.e. inside
  `JoinSetCell`'s and `SemiJoinCell`'s third sub-state.
- `GroupByCell`'s per-group `count` (`GroupByCell.kt:45`, `:113`) is a scalar
  *within* an entry, which is fine — it travels with its group.

A scalar rider must appear on **every page**, not only the first — the same
rule the design note states for `ShardCell`'s `interest`/`assignedEpoch`
(`20-wave-neutral-read-design.md:643-646`). A consumer that abandons a walk
after page 1 or resumes from a mid-walk cursor must still see it. Riders do not
count against `limit`.

**Riders go in `StatePage.attributes`** (C8): the shipped page has a keyed
cell-level map for exactly this, so a rider is neither an entry nor a new field,
and "does not count against `limit`" falls out for free instead of needing an
accounting rule.

### Decision E — the walk's content domain is exactly `snapshot()`'s

The union of a walk at a stable frontier equals **`snapshot()`'s content** —
not "the cell's state", and not "what a reader might want". Two consequences,
both load-bearing:

- **Do not add fields `snapshot()` omits.** `TagState.deadSources`
  (`TagState.kt:29`) is live fold state but is *not* in `TagState.snapshot()`
  (`:123`) and is *not* restored (`:126-129`). Leave it out of the walk. (That
  asymmetry is pre-existing and out of scope — see Report on completion.)
- **Do not add derived state `snapshot()` omits.** `LookupJoinCell` rebuilds
  `byDim` and the `MapDiffPublisher` from the restored inputs
  (`LookupJoinCell.kt:129-133`); `CombineLatestCell` rebuilds its publisher the
  same way (`CombineLatestCell.kt:86-89`); `KeyedBinarySetJoin.rebuildIndexes`
  rebuilds both key indexes (`KeyedBinarySetJoin.kt:41-46`);
  `PresenceCountCell` rebuilds `counts` from the lanes
  (`PresenceCountCell.kt:190-191`). None of these appear in the respective
  `snapshot()`s and none of them appear in your pages. Say so in the KDoc: a
  bounded read of a `LookupJoinCell` shows the **fact and dimension inputs**,
  not the enriched output map.

### Decision F — a nested sub-state needs a nested cursor component

`PresenceLanes.snapshot()` (`PresenceCountCell.kt:105`) is
`HashMap(lanes.mapValues { it.value.snapshot() })` — a map from lane id
(`UUID`) to a whole `TagState` snapshot, i.e. **two levels of enumeration**
before you reach an element. `QuorumSetCell` holds that as its *first* of two
sub-states (`QuorumSetCell.kt:116`), so its cursor is three components deep:
`(subStateOrdinal, laneId, element)`. Handle it with the same lexicographic
rule applied recursively; do not special-case it into a flat encoding that
loses the lane boundary, because the lane is exactly what makes the quorum
count meaningful.

### Decision G — "entries whole" versus an unbounded accumulator: name the tension

`GroupByCell`'s second sub-state maps a group key `K` to
`arrayListOf(count, acc)` (`GroupByCell.kt:111-114`), where `ACC : Serializable`
is the aggregator's accumulator. For the **non-invertible** aggregator family
(`minOf`/`maxOf`/`topK`/`collectToSet`) the accumulator is *the full support
multiset* in a `TreeMap` — required, not incidental
(`doc/spec/20-dataflow-semantics/24-data-cells.md:220-233`, `[24-OP-GROUPBY-04]`
at `:229`; `kernel/src/main/kotlin/civictech/cell/data/Aggregator.kt`). So **one
entry can be arbitrarily large**. `MergeableGroupByCell` has the same shape with
an arbitrary `A : Serializable` per key (`MergeableGroupByCell.kt:60`, `:99`) —
a set-union `merge` produces exactly this.

Decision 5 says entries are whole and never split; Decision 4 says `byteBudget`
is advisory. Those are consistent — a single oversized entry rides whole and
blows the advisory budget — but the behaviour must be **decided, documented on
the cell, and tested**, not discovered. Pick one and justify it:

- emit the entry whole and let `byteBudget` be exceeded (simplest, matches
  "advisory"); or
- emit a size-describing descriptor for an accumulator over a named threshold,
  in the manner of Decision 3's presence descriptors, and account for it.

Do **not** invent a third `StatePage` field for it.

### Decision H — a `JoinLedger` sub-state pages **through its owning cell**

`kernel/src/main/kotlin/civictech/cell/data/op/JoinLedger.kt` declares
`JoinLedger<X>` with its own `snapshot()`/`restore()` (`:42-43`) and two
implementations, `MintedLedger` (`snapshot()` at `:58`) and `AdvertisedLedger`
(`snapshot()` at `:78`). **Neither implements `Stateful`**, and neither is a
cell — they are injected collaborators (`JoinLedger.kt:9-22`), held by
`JoinSetCell` (`:45`), `SemiJoinCell` (`:45`), `IntersectSetCell` (`:44`) and
`QuorumSetCell` (`:59`).

**Recommended, and the default you should take: do not grow `JoinLedger` a
`readBounded`.** Page the ledger as one sub-state *of the owning cell*, the way
you page `TagState`. Reasons: the interface exists to hold the shared
advertise/exit SHAPE while keeping the tag-assignment POLICY genuinely
different per implementation (`JoinLedger.kt:12-22`); the cursor's outer
component belongs to the cell that declares the sub-state ordinals; and a
second paging interface in the same package invites two orderings for one walk.
If you conclude otherwise, say so and justify it — but the same reasoning
applies to `TagState`, `PresenceLanes`, `KeyedBinarySetJoin` and
`TaggedSetOperator`, and growing all five a paging interface is a much larger
diff than this ticket is sized for.

Whatever you choose, note that `MintedLedger`'s and `AdvertisedLedger`'s
snapshot shapes differ (`arrayListOf(map, counter)` versus a bare `HashMap`),
so a `QuorumSetCell` page and a `JoinSetCell` page do not carry the same ledger
entry shape. That is fine; it must be documented per cell.

### Decision I — per-cell inclusion is a judgement, and every cell is accounted for

#### Primary set — implement or explicitly exclude, no silence

Verified current `Stateful` declaration and `override fun snapshot()` lines:

| Cell | `Stateful` | `snapshot()` | Sub-states, in `snapshot()` order |
|---|---|---|---|
| `JoinCell.kt` | `:29` | `:77` | `leftMap: K→V` (`:30`), `rightMap: K→W` (`:31`) — **same key type `K`**, overlapping content: a joined key is in both |
| `LookupJoinCell.kt` | `:59` | `:120` | `facts: K→V` (`:60`), `dims: J→D` (`:61`) — different key types; `byDim`/`publisher` derived, excluded (`:129-133`) |
| `GroupByCell.kt` | `:42` | `:111` | `state: TagState<E>` (`:43`), `groups: K→[count, acc]` (`:47`) — different key types; see Decision G |
| `MergeableGroupByCell.kt` | `:50` | `:99` | `groups: K→A` (`:60`) — **single** sub-state, but `A` is unbounded; see Decision G |
| `JoinSetCell.kt` | `:43` | `:109-110` | `join.leftState: TagState<A>`, `join.rightState: TagState<B>` (`KeyedBinarySetJoin.kt:24-25`), `ledger: MintedLedger<Pair<A,B>>` (`:45`) — **three**, third keyed by a pair, carrying a scalar mint counter (Decision D) |
| `QuorumSetCell.kt` | `:55` | `:116` | `lanes: PresenceLanes<E>` (`:56`) — itself `laneId→TagState<E>` (`PresenceCountCell.kt:105`) — and `ledger: AdvertisedLedger<E>` (`:59`). **Three cursor levels**; see Decision F |
| `IntersectSetCell.kt` | `:41` | `:82-83` | `leftState`, `rightState`, `ledger` (`:42-44`) — **all three keyed by the same `E`**, all three may hold the same element with different tag sets. The Decision A case |
| `CombineLatestCell.kt` | `:48` | `:79` | `leftMap: K→V`, `rightMap: K→W` (`:49-50`) — same key type; publisher derived (`:86-89`) |

#### Optional extension — at your discretion, **only if the pattern is mechanical**

The remaining cells in the package. Include a cell only when adding it is a
mechanical application of the pattern you already built; the moment one needs a
new idea, exclude it and say why. **You must justify each inclusion and each
exclusion in the report** — a silent skip is a defect.

| Cell | `Stateful` | `snapshot()` | Backing state |
|---|---|---|---|
| `UnionSetCell.kt` | `:40` | `:58` | `TaggedSetOperator<E>` (`:41`) — one `TagState` |
| `FilterCell.kt` | `:30` | `:50` | `TaggedSetOperator<E>` (`:31`) |
| `FlatMapSetCell.kt` | `:36` | `:63` | `TaggedSetOperator<A>` (`:37`) |
| `SemiJoinCell.kt` | `:43` | `:98` | `KeyedBinarySetJoin` pair + `MintedLedger<A>` (`:44-45`) — structurally `JoinSetCell` with an `A`-keyed ledger |
| `PresenceCountCell.kt` | `:140` | `:186` | `PresenceLanes<E>` only (`:144`); `counts` rebuilt on restore (`:190-191`) — nested, but a single sub-state |
| `CountCell.kt` | `:38` | `:57` | `TaggedSetOperator<E>` (`:39`) |

**Correcting the brief this ticket was written from:** `CountCell` is *not*
scalar. Its *output* is a `CounterDelta`, but its `snapshot()` is
`op.snapshot()` (`CountCell.kt:57`) → `TaggedSetOperator.snapshot()`
(`TaggedSetOperator.kt:27`) → `TagState.snapshot()` (`TagState.kt:123`) — the
whole live element→tag-set map, exactly as large as `FilterCell`'s or
`UnionSetCell`'s. If you exclude it, exclude it for a real reason, not for
being "a counter". All four `TaggedSetOperator`-backed cells share one
snapshot shape, so they are one implementation, four one-line adoptions —
which is the strongest argument for including them.

#### Explicitly excluded from this ticket

- `kernel/src/main/kotlin/civictech/cell/data/SetCell.kt` — `V1C-KERNEL`'s.
- Everything `V1C-CELLS` claims: `cell/data/MapCell.kt`,
  `cell/data/KeyedSetCell.kt`, `cell/data/ListCell.kt`,
  `cell/data/Watermark.kt`, `cell/replication/InstanceSet.kt`,
  `cell/partition/ShardCell.kt`.
- `inspect/**`, `wire/**`, `concord/**` (binding constraint 7,
  `../10-design-notes.md:140`), `gen/**`, `nature/**`, `testkit/**`, `demo/**`.
- `ManagedHost.kt`, `BoundedRead.kt`, `Stateful.kt`, `StateRequest` and every
  replication/evolution seam — `V1C-KERNEL` owns the interface; you consume it.
- `Emit.kt`'s `emitOrAbsorb`, the emit paths, the `catchUpOnLinked` blocks, and
  every `restore()`. **Additive only.**

## Files expected to touch

- `kernel/src/main/kotlin/civictech/cell/data/op/JoinCell.kt`,
  `LookupJoinCell.kt`, `GroupByCell.kt`, `MergeableGroupByCell.kt`,
  `JoinSetCell.kt`, `QuorumSetCell.kt`, `IntersectSetCell.kt`,
  `CombineLatestCell.kt` — the primary set, `BoundedStateful` added,
  **`snapshot()` untouched**.
- Optionally, per Decision I: `UnionSetCell.kt`, `FilterCell.kt`,
  `FlatMapSetCell.kt`, `SemiJoinCell.kt`, `PresenceCountCell.kt`,
  `CountCell.kt`.
- Possibly `JoinLedger.kt`, `KeyedBinarySetJoin.kt`, `TaggedSetOperator.kt`,
  `PresenceCountCell.kt`'s `PresenceLanes` — **read-accessor additions only**,
  if a sub-state's contents are not otherwise reachable for enumeration. Prefer
  adding a narrow internal read accessor over restructuring the collaborator.
  Note that `TagState` (`TagState.kt:18`), `MintedTags` (`MintedTags.kt:20`)
  and `PresenceLanes` (`PresenceCountCell.kt:44`) are `internal` — same module,
  so this is a visibility non-issue, but it does mean their shapes are *not*
  part of any published API and you must not leak them into `StatePage`
  entries as-is if that would export an internal type.
- A new paging helper under
  `kernel/src/main/kotlin/civictech/cell/data/op/` if one earns its place —
  the package already houses exactly this kind of shared skeleton
  (`TaggedSetOperator.kt`, `KeyedBinarySetJoin.kt`, `Emit.kt`).
- **New tests** under `kernel/src/test/kotlin/civictech/cell/data/op/`.

**A correction to note before you plan your tests:** the existing operator
tests are **not** under `kernel/src/test/kotlin/civictech/cell/data/op/` —
that directory currently holds exactly one file, `OperatorAbsorbAckTest.kt`.
The operator tests live one level up, under
`kernel/src/test/kotlin/civictech/cell/data/`: `JoinSetCellTest.kt`,
`GroupByCellTest.kt`, `QuorumSetCellTest.kt`, `SemiJoinCellTest.kt`,
`LookupJoinCellTest.kt`, `CombineLatestCellTest.kt`, `MergeableGroupByTest.kt`,
`PresenceCountCellTest.kt`, `OperatorTest.kt`,
`GlitchFreeOperatorSuiteTest.kt`, `WindowingTest.kt`, plus
`kernel/src/test/kotlin/civictech/cell/graph/SetAlgebraGraphsTest.kt` and
`kernel/src/test/kotlin/civictech/cell/app/DataflowSuiteExitTest.kt`. **All of
those must stay green and unmodified** — and that directory is shared with
`V1C-CELLS` (`KeyedSetCellTest.kt` is in it), so **put every new test file
under `.../data/op/`** and edit nothing under `.../data/`.

Touching files outside this list: note it in the completion report rather than
expanding silently.

## Read first

- `doc/spec/90-roadmap/98-inspector-v4-plan/tickets/V1C-KERNEL.md` — **the
  whole ticket**, plus its completion report if one is available: the verbatim
  final signatures of `BoundedStateful`, `StateRead`, `StatePage`, `Cursor`,
  `Provenance`, `StateReadResult` and `ManagedHost.readState`, and the
  `SetCell` cursor encoding you are copying (Decision C).
- `kernel/src/main/kotlin/civictech/cell/BoundedRead.kt` and
  `kernel/src/main/kotlin/civictech/cell/data/SetCell.kt` — the landed
  interface and the landed reference implementation. `SetCell`'s state is two
  tag maps plus a scalar counter (`:44-45`, `:57`) snapshotted as a labelled
  three-entry map (`:200-207`); it is the smallest composite and its solution
  is your starting point.
- `doc/spec/90-roadmap/98-inspector-v4-plan/20-wave-neutral-read-design.md` —
  §3.3 (ownership), §3.4 (cursor, mid-fold mutation, why not isolation, the
  `since`-based escalation path), §5 (the implementations list, and the
  `ShardCell` rider precedent at `:643-646`), §6.2 (the tests owed), §7 (the
  open question you are closing, and item 3's vector-cursor anticipation).
- `doc/spec/90-roadmap/98-inspector-v4-plan/10-design-notes.md` §"Binding
  constraints" (all ten; **1/P2**, **3/ownership**, **6/viz-never-blocks** and
  **7/no-concord-edits** govern you directly) and §"Standing file split".
- `doc/spec/20-dataflow-semantics/24-data-cells.md` — the normative
  per-operator requirements you must not break. Verified ids and lines:
  `[24-OP-INTERSECT-01]` `:127`, `[24-OP-JOIN-01]` `:135`,
  `[24-OP-JOINSET-01]` `:143`, `[24-OP-JOINSET-02]` `:149`,
  `[24-OP-SEMIJOIN-01]` `:157`, `[24-OP-FILTER-01]` `:119`,
  `[24-OP-COUNT-01]` `:122`, `[24-OP-UNION-01]` `:65`,
  `[24-OP-FLATMAP-01]` `:181`, `[24-OP-GROUPBY-01]` `:198`,
  `[24-AGG-01]` `:206`, `[24-OP-GROUPBY-02]` `:214`, `[24-OP-GROUPBY-03]`
  `:218`, `[24-OP-GROUPBY-04]` `:229`, `[24-OP-GROUPBY-05]` `:234`,
  `[24-OP-GROUPBY-06]` `:271`, `[24-SCOPED-01]` `:723`. Read the surrounding
  prose, not only the EARS sentences.
  **Note, and handle honestly:** `LookupJoinCell`, `CombineLatestCell`,
  `QuorumSetCell`, `PresenceCountCell` and `MergeableGroupByCell` have **no
  dedicated `[24-OP-*]` id**. The nearest normative text is
  `[24-OP-OUTERJOIN-01]`/`-02` (`:256`, `:261`) for outer-join compositions and
  `[24-SCOPED-01]` (`:723`) for `MapDelta` scoping. For those cells, name the
  cell's own KDoc contract as the thing you preserved, and say in the report
  that no requirement id covers it.
- Every source file in `kernel/src/main/kotlin/civictech/cell/data/op/` —
  **all of them, in full.** In particular the four collaborators that hold the
  actual state: `TaggedSetOperator.kt` (whole, 39 lines),
  `KeyedBinarySetJoin.kt` (whole, 61 lines), `JoinLedger.kt` (whole, 85 lines),
  and `PresenceCountCell.kt:44-116` (`PresenceLanes`, including its
  `snapshot`/`restore` at `:105-115`).
- `kernel/src/main/kotlin/civictech/cell/data/delta/TagState.kt` (whole, 130
  lines) — `live` at `:19`, `deadSources` at `:29` (**not snapshotted**),
  `snapshot`/`restore` at `:123-129`.
- `kernel/src/main/kotlin/civictech/cell/data/delta/MintedTags.kt` (whole, 50
  lines) — the scalar mint counter, `snapshot()` at `:41`.
- `kernel/src/main/kotlin/civictech/cell/data/Aggregator.kt` — the accumulator
  families behind Decision G.
- `kernel/src/main/kotlin/civictech/cell/data/view/MapDiffPublisher.kt` — the
  derived publisher that is *not* in `LookupJoinCell`'s or
  `CombineLatestCell`'s snapshot.
- `kernel/src/main/kotlin/civictech/cell/Ownership.kt` and
  `doc/spec/20-dataflow-semantics/23-ownership.md:19-21`, `:112-151` —
  Decision 3's basis. An operator's element/value type is app-supplied, so an
  `Owned` payload can reach any of these cells.
- `kernel/src/test/kotlin/civictech/cell/data/op/OperatorAbsorbAckTest.kt` —
  the only existing test in your test package; match its harness style.
- `kernel/src/test/kotlin/civictech/cell/data/JoinSetCellTest.kt`,
  `QuorumSetCellTest.kt`, `GroupByCellTest.kt` — the existing per-operator
  harnesses. **Read them for setup you can reuse; do not edit them.**
- `AGENTS.md` §"Core invariants to protect" and §"Verification".

Do not modify: `inspect/**`, `wire/**`, `gen/**`, `nature/**`, `concord/**`,
`testkit/**`, `demo/**`, any cell outside `cell/data/op/**`,
`kernel/src/test/kotlin/civictech/cell/data/*.kt`, any plan document other than
this ticket's `**Status**:` line, and
`doc/spec/90-roadmap/97-inspector-plan/20-api-contract.md`
(orchestrator-owned).

## Acceptance criteria

- [ ] **Union equals snapshot.** For every implemented cell: a walk to
      completion at a stable frontier yields pages whose union equals
      `snapshot()`'s content exactly — every sub-state, nothing extra
      (Decision E), nothing missing.
- [ ] **The cross-sub-state ordering is asserted, not assumed.** Per composite
      cell, a test proves (a) **no entry appears twice across a sub-state
      boundary** — for `IntersectSetCell` and `JoinCell` specifically, drive a
      state where the *same key* is live in two or three sub-states and assert
      the walk returns one labelled entry per `(subState, key)` pair, never a
      collapsed or repeated one; and (b) **a resume mid-sub-state continues in
      the right sub-state** — cut a page so a cursor lands inside sub-state *i*
      and assert the next page finishes *i* before entering *i+1*, and that a
      cursor at the exact end of *i* enters *i+1* rather than terminating.
- [ ] **Enumeration order is stable and asserted per cell.** Including the
      insertion-order trap: remove a key and re-add it mid-walk and assert it
      is not returned twice (Problem, defect 4).
- [ ] **`limit` respected per page; cursor resumes; final page `next == null`.**
      Sweep at least `limit` ∈ {1, 2, n−1, n, n+1} for one composite cell, and
      assert a `limit` of 1 still terminates and still visits every sub-state.
- [ ] **Mid-walk mutation yields the documented smear.** Apply operator input
      deltas between pages: the frontier stamp advances, every entry is whole,
      no `(subState, key)` appears twice, and the union is Decision 5's smear —
      contains every survivor, may contain additions, may miss entries removed
      after being passed over. Assert the documented contract, not a stricter
      one.
- [ ] **Cursor invalidated mid-read.** Remove the key a cursor names, then
      resume: the walk continues from the next key **in the same sub-state**,
      or from the head of the next sub-state if that was the last key — never
      restarting, never throwing, never skipping a sub-state.
- [ ] **Scalar riders on every page** (Decision D): `JoinSetCell`'s and
      `SemiJoinCell`'s mint counter is present on page 1, on a mid-walk page,
      and on the final page, and does not count against `limit`.
- [ ] **Nested cursor** (Decision F): a `QuorumSetCell` with ≥2 open lanes
      holding overlapping elements walks every `(lane, element)` pair exactly
      once and resumes correctly *inside* a lane, then continues to the next
      lane, then to the ledger.
- [ ] **Ownership** (Decision 3): a cell whose element or value type is an
      `Owned`/`Leased` payload pages presence descriptors only —
      `exclusivesElided > 0`, `Owned.take` never called, no discharge or
      dead-letter accounting moved, and no exclusive value or copy of one in
      any page. Cover at least one map-valued cell (`JoinCell` /
      `CombineLatestCell` / `LookupJoinCell`) and one tagged-set cell.
- [ ] **Oversized entry** (Decision G): a `GroupByCell` with a non-invertible
      aggregator whose accumulator exceeds `byteBudget` behaves as documented —
      the entry rides whole (or is descriptor-elided, whichever you chose), the
      page is still valid, and `limit` is still respected.
- [ ] **Normative requirements still hold**, named per cell. At minimum:
      `[24-OP-JOIN-01]` (`JoinCell`), `[24-OP-JOINSET-01]`/`-02`
      (`JoinSetCell`), `[24-OP-INTERSECT-01]` (`IntersectSetCell`),
      `[24-OP-GROUPBY-01]`/`-02`/`-03`/`-06` and `[24-AGG-01]`
      (`GroupByCell`), `[24-SCOPED-01]` (`MergeableGroupByCell`), plus
      `[24-OP-SEMIJOIN-01]`, `[24-OP-FILTER-01]`, `[24-OP-COUNT-01]`,
      `[24-OP-UNION-01]`, `[24-OP-FLATMAP-01]` for any optional cell you
      include. For the cells with no id, name the KDoc contract instead.
- [ ] **`snapshot()` is behaviourally unchanged on every touched cell**, and
      every `restore()` is untouched. The `snapshot`/`restore` round-trip that
      drain, migration, promotion state transfer and durability checkpoints
      depend on still passes.
- [ ] **Existing tests pass unmodified**: everything under
      `kernel/src/test/kotlin/civictech/cell/data/`,
      `kernel/src/test/kotlin/civictech/cell/data/op/OperatorAbsorbAckTest.kt`,
      `kernel/src/test/kotlin/civictech/cell/graph/SetAlgebraGraphsTest.kt` and
      `kernel/src/test/kotlin/civictech/cell/app/DataflowSuiteExitTest.kt`.
- [ ] **Wave neutrality per cell**: reading an operator cell fires no tap,
      moves no `waveState()`, emits no delta and produces no absorb-ack. These
      operators call `outlet.absorbAck()` on membership-neutral folds
      (`Emit.kt:18-20`, `IntersectSetCell.kt:75-79`, `QuorumSetCell.kt:109-113`,
      `CountCell.kt:50-54`) — a read must reach none of that. Assert it for at
      least one cell in the primary set.
- [ ] **Every cell in the primary set is either implemented or explicitly
      excluded with a written reason in the report.** None silently skipped.
      Same for each optional cell.
- [ ] **P2**: every call site you added is on a read path, never on a fold or
      emission path. State it as a checkable claim naming the sites.
- [ ] Every added public member carries KDoc naming this ticket (`V1C-OPS`),
      the cell's sub-state decomposition and its cursor encoding, in the
      register of `V1C-KERNEL`'s additions.
- [ ] Nothing outside `kernel/src/main/kotlin/civictech/cell/data/op/**` and
      `kernel/src/test/kotlin/civictech/cell/data/op/**` in the diff. No
      generated/build output. No gap (`G-*`) or consistency (`C-*`) markers
      removed.

## Verify

```bash
./gradlew :kernel:test --tests 'civictech.cell.data.op.*'
./gradlew :kernel:test --tests 'civictech.cell.data.*'
./gradlew :kernel:test
./gradlew test
```

Then the composition exit gate — partitioned + replicated + durable +
glitch-free in one graph, and the repository's toughest property gate:

```bash
./gradlew :demo:exchange:test
```

`demo/exchange` exercises `MergeableGroupByCell` (7 references),
`GroupByCell` (5), `UnionSetCell` (5), `FilterCell` (2) and `FlatMapSetCell`
(2) in `Main.kt`, gated by `ExchangeCompositionExitTest`.

**Two incremental demos whose operator suites would catch a regression** —
run both:

```bash
./gradlew :demo:skillmatch:test     # SkillMatchPipelineTest, SkillMatchServerTest, SkillMatchInspectorTest
./gradlew :demo:slotfinder:test     # SlotFinderPipelineTest, SlotFinderServerTest
```

`demo/skillmatch` is the broadest single check on this ticket: its
`SkillMatchApp.kt` wires `CombineLatestCell` (6), `GroupByCell` (5),
`LookupJoinCell` (4), `JoinSetCell` (2) and `SemiJoinCell` (2) — five of the
eight primary cells in one pipeline. `demo/slotfinder` is the **only** demo
that exercises `QuorumSetCell` (4 references in `SlotFinderApp.kt`, alongside
`GroupByCell` and `FilterCell`), so it is the only end-to-end coverage the
hardest cell in your set has.

Also worth running if you touch the group-by family or the `TaggedSetOperator`
cells:

```bash
./gradlew :demo:tiering:test          # CombineLatestCell, FlatMapSetCell, GroupByCell
./gradlew :demo:backlog-triage:test   # GroupByCell, CombineLatestCell, FlatMapSetCell
```

`:kernel:compileKotlin` depends on `:gen:test`, so a generator regression
surfaces here as a kernel compile failure (`AGENTS.md` §Repository map).

## Report on completion

- Checks run and their results.
- **Per implemented cell, in a table**: the sub-state decomposition (name,
  declared ordinal, key type, value shape), the chosen cross-sub-state total
  order, the cursor encoding, and any scalar riders. `V1C-BE` and any later
  consumer must be able to interpret a page without reading your diff.
- **Every cell excluded from the primary set, and why.** Likewise every
  optional cell included or excluded — one line each, no silent omissions.
- **Whether the `V1C-KERNEL` interface shape needed any change.** This is a
  **cross-ticket event**: `V1C-CELLS` runs concurrently against the same
  `BoundedStateful` / `StateRead` / `StatePage` / `Cursor` types, so a change
  must be flagged loudly enough that the orchestrator can propagate it to that
  branch before merging either. If you needed a change and worked around it
  instead, say what the workaround cost.
- **Whether any composite needed a *vector* cursor** (one token per sub-state)
  rather than a single lexicographic token — `20-wave-neutral-read-design.md`
  §7 item 3 anticipated this and it is a genuine possible finding. Name the
  cell, the reason, and whether the same reason would apply to a partitioned
  or replicated cell under scatter-gather.
- **The insertion-order finding**: whether `V1C-KERNEL`'s intra-key ordering
  generalized to every key space here (`Pair<A, B>`, `UUID` lane ids,
  arbitrary `K`/`E`), what you had to extend, and whether any cell still has an
  order you could only make deterministic by imposing one.
- **Decision G's outcome**: which behaviour you chose for an accumulator
  larger than `byteBudget`, and whether any real aggregator in
  `Aggregators` reaches that size in practice.
- **Decision H's outcome**: whether the join ledgers page through their owning
  cell as recommended, and if not, what interface grew and why.
- **The P2 claim, checkable**: name every call site you added and state that
  none is on a per-message fold or emission path.
- **Flag separately, do not act on** — pre-existing seams you will notice and
  must not fix here:
  1. `TagState.deadSources` (`TagState.kt:29`) is fold state that
     `snapshot()`/`restore()` (`:123-129`) do not round-trip. A restored
     operator loses its dead-lane fence. This is adjacent to G-42
     (epoch-hygiene reclamation, `TagState.kt:27-28`) and is **not** this
     ticket's to close; report whether you saw a consequence.
  2. Ownership in `Stateful.snapshot()` itself — the older, undefined seam
     `V1C-KERNEL` Decision 3 declines to inherit
     (`20-wave-neutral-read-design.md` §7 item 2). Operators hold app-supplied
     element and value types, so if you found a concrete operator path where an
     exclusive reaches `snapshot()`, that is valuable research input for
     `doc/spec/90-roadmap/95-research-plan.md` (owner-maintained — neither you
     nor the orchestrator edits it here).
  3. Any cell whose `snapshot()` shape you believe is wrong or lossy
     independently of paging. Report it; do not change it.
- **Flag to the orchestrator, do not act on**: any consequence for
  `doc/spec/90-roadmap/97-inspector-plan/20-api-contract.md` — you only flag
  what the kernel now makes possible for a paged operator-cell view; `V1C-BE`
  proposes the HTTP shape.
- Anything specified here you could not do, and why.
