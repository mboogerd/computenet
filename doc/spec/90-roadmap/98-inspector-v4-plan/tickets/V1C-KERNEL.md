# V1C-KERNEL — a bounded state read: the kernel learns to answer "the first 200 entries" instead of "all of it"

**Status**: Specified — not-started
**Model:** `claude-opus-5` (effort xhigh) · **Escalate to:** `claude-opus-5`,
fresh session; if that fails, stop and re-split the ticket.
**Wave:** 8 · **Branches:** `ticket/v1c-kernel`

## Context

ComputeNet is a Kotlin/JVM dataflow runtime: cells with typed ports, explicit
links, hosted execution, ownership-aware payloads. `ManagedHost`
(`kernel/src/main/kotlin/civictech/cell/host/ManagedHost.kt`) runs cells on their
own execution contexts.

There is exactly one way to read a cell's state from outside the graph today:

```kotlin
fun snapshotOf(ref: CellRef): CompletableFuture<Serializable?>   // ManagedHost.kt:1244-1263
```

It runs the cell's own `Stateful.snapshot()`
(`kernel/src/main/kotlin/civictech/cell/Stateful.kt:11-14`) on the cell's
execution context. Its KDoc states the properties that make it the right seam
for an instrument (`ManagedHost.kt:1201-1243`): "nothing is linked, nothing is
emitted, no wave counter moves" (`:1219-1220`), callable from any thread against
any scheduler including `SimulationController` (`:1223-1234`),
cancellation-honouring (`:1236-1242`), and completing with null rather than
exceptionally (`:1247`, `:1259-1262`).

It is also **unbounded**. All 24 `Stateful` implementations under
`kernel/src/main` return the whole state as one `Serializable`: `SetCell.kt:200`
(both OR-set tag maps), `MapCell.kt:49` (`HashMap(state)`),
`KeyedSetCell.kt:117`, `ListCell.kt:62`, `ShardCell.kt:185`,
`Watermark.kt:210`, and so on. The truncation happens *after* the copy, in the
instrument: `inspect/src/main/kotlin/civictech/inspect/ValueEncoder.kt:53`
(`MAX_ROWS = 200`) and `:56` (`MAX_BYTES = 50_000`), enforced by `Budget`
(`:341-348`). The cell's thread pays for 10⁵ rows so 200 can be rendered.

That is the whole of `MRB-157`, and it is what
`doc/spec/90-roadmap/98-inspector-v4-plan/20-wave-neutral-read-design.md` — the
V1C design note, produced by wave 6 of this run and accepted at the C-replan
checkpoint — decided to close. **Read that document in full before you start.**
Its go/no-go (§7) is: *Go, for the local read; no-go on the remote arm.* This
ticket is the local read.

The design note also establishes two things you must not re-derive:

1. **The wave-neutral read is not the problem — boundedness is.** `snapshotOf`
   already has every property an instrument wants except a bound, a consistency
   stamp, an answer for non-hot cells, and remoteness (§1.4). This ticket is an
   increment on a landed seam, not a new mechanism.
2. **A pull reply (`StateRequest`/`baselineTo`) is the wrong primitive, and not
   for the reason the repository used to give.** §1.2 and §1.3 enumerate every
   observer of a wave `(sourceId, counter)` and show the real objection is
   topology (P6), not wave perturbation: `FanOutlet.at` delivers only to a
   `consumers`/`taps` entry, so an instrument would have to install a link or a
   tap first. The stale KDoc that gave the old reason has already been corrected
   on `main` by the C-replan checkpoint (`CatchUp.kt`, `DataSearch.kt`,
   `90-progress-log.md`); do not re-open that argument.

This is the **only** ticket in wave 8 and the only one permitted to touch
`kernel/**` in this wave. The bar every kernel addition here is judged against
is the cumulative-kernel-diff table at
`doc/spec/90-roadmap/97-inspector-plan/90-progress-log.md:1076-1088`: small,
read-only, transport-neutral, threaded through structures that already exist,
each with a focused kernel test.

### The gate you are downstream of

`V1C-BENCH` (wave 7) measured what a whole-state copy actually costs and issued
a **GO / RESIZE / NO-GO** recommendation in
`doc/spec/90-roadmap/98-inspector-v4-plan/30-bounded-read-measurement.md`. Read
it. If it says RESIZE, the C7 checkpoint will have narrowed this ticket's scope
before dispatching you — but read the numbers anyway: they are the argument for
"one page = one scheduler task" and you will want them in your KDoc.

## Problem

Four concrete gaps, all in the design note's §1.4, all closed by this ticket.

1. **No bound.** `Stateful.snapshot()` has no cursor, no limit and no byte
   budget. A caller cannot learn what a read will cost before paying for it, and
   cannot ask for less. Downstream, `DataSearch`'s
   `MAX_CELLS = 50` / `BUDGET_MS = 2_000` (`DataSearch.kt:345`, `:348`) and its
   user-visible closing notice (`DataSearch.kt:300-339`, which reports cells
   capped, cells unanswered inside the budget, and cells "read only to the first
   200 rows") exist entirely because of this.

2. **No consistency stamp.** A `snapshotOf` caller gets a value with no frontier
   attached. The inspector's per-cell stamp comes from a completely different
   path — `StampedView.frontier`
   (`inspect/src/main/kotlin/civictech/inspect/Observations.kt:548-550`, written
   at `:577`), a *wave position* read from `CurrentContext` at fold time —
   whereas the pull/baseline currency is a `TagFrontier`
   (`kernel/src/main/kotlin/civictech/cell/MessageContext.kt:58-72`). Two stamps
   for two read paths, and the snapshot path has neither.

3. **No defined answer for a non-hot cell.** The kernel says nothing, so the
   instrument invented `Heat`
   (`inspect/src/main/kotlin/civictech/inspect/Cold.kt:68-119`;
   `Heat.of` at `:109-117`, `isReadable` true only for `HOT` at `:98`) and
   guesses from registry metadata. A **suspended** cell — whose fold is
   quiescent by construction and is therefore the *most* stable thing in the
   graph to read — is skipped and counted (`DataSearch.kt:67-73`, predicate at
   `:185-188`) purely because the instrument could not tell the difference. A
   **drained** host already holds a checkpoint blob of every cell it held
   (`ManagedHost.kt:499-502`, `snapshots[cellRef] = cell.snapshot()` at `:501`)
   and nothing can read it.

4. **No ownership contract for a read.** `Stateful.snapshot()` returns
   `Serializable`, which already implies *copy*, and
   `doc/spec/20-dataflow-semantics/23-ownership.md` has no rule for a fold whose
   state contains `Owned`/`Leased` values (G-46 at `:220` covers only the
   crash-loss half). Today's drain/migrate/checkpoint seam therefore has an
   undefined ownership story. This ticket does not fix that older seam — it
   **declines to inherit it** (see §3.3 of the design note and the Solution
   direction below).

## Solution direction

The **what** is decided by the design note's §3 and restated below with the
decisions made explicit. The **how** — internal structure, helper placement,
how the drained arm reaches the checkpoint map — is yours.

### The types

A new top-level file `kernel/src/main/kotlin/civictech/cell/BoundedRead.kt`,
beside `Stateful.kt`. It is a *cell capability*, not an app-facing view: the
`observe` package is the app-facing seam and depends on this, never the reverse.

```kotlin
interface BoundedStateful : Stateful {
    fun readBounded(request: StateRead): StatePage
}

data class StateRead(
    val cursor: Cursor? = null,          // opaque, cell-minted; null = start of walk
    val limit: Int = 200,                // hard cap on entries in the page
    val byteBudget: Int = 50_000,        // cell-estimated, advisory
    val scope: Interest? = null,         // null = Interest.Total
    val since: TagFrontier? = null,      // null = full state, not a delta
    val allowWholeCopy: Boolean = false, // see "a cell that does not implement it"
)

data class StatePage(
    val entries: List<Serializable>,     // whole entries, never partial
    val next: Cursor?,                   // null = walk complete
    val frontier: TagFrontier?,          // the fold's tag frontier at page time
    val provenance: Provenance,
    val exclusivesElided: Int,
)

@JvmInline value class Cursor(val token: Serializable)

enum class Provenance { LIVE, LIVE_SUSPENDED, CHECKPOINT }
```

and, on `ManagedHost`, beside `snapshotOf`:

```kotlin
fun readState(ref: CellRef, request: StateRead): CompletableFuture<StateReadResult>
```

with `StateReadResult` a sealed hierarchy: `Page(StatePage)` |
`Unbounded(Serializable)` | `Unavailable(Reason)`.

Exact naming, nullability and packaging of `StateReadResult`/`Reason` are yours;
the field set above is not. If you find a genuinely better shape, take it and
**justify it in the report** — the shape is contract-binding for `V1C-CELLS`,
`V1C-OPS` and `V1C-BE`, all of which are written against this ticket text, so a
change must be reported prominently enough that the orchestrator can propagate
it.

### Decision 1 — `BoundedStateful` extends `Stateful`; nothing is added to `Stateful`

Adding `readBounded` to `Stateful` would force all 24 implementations to
implement it, would ripple into `View`
(`kernel/src/main/kotlin/civictech/cell/observe/Observe.kt:72-111`, which mirrors
the `Stateful` shape deliberately at `:79`) and into
`concord/src/main/kotlin/civictech/concord/driver/kernel/KernelAdapters.kt`'s
five `Stateful` adapters, and would make a paged read a precondition for being
drainable. Four subsystems depend on `snapshot()` being a whole, restorable
value — drain (`ManagedHost.kt:499-502`), migration (`:1070-1072`,
`doc/spec/30-execution-model/33-mobility.md:100-102`), promotion state transfer
(`kernel/src/main/kotlin/civictech/cell/evolve/Evolution.kt:190-198`) and
durability checkpoints (`HostDurability.kt`,
`doc/spec/20-dataflow-semantics/24-data-cells.md:567`).

Opt-in keeps all four seams byte-for-byte unchanged, and keeps `concord/` out of
the diff entirely — which binding constraint 7 of `../10-design-notes.md`
requires anyway.

**A cell that does not implement `BoundedStateful`.** `readState` answers
`Unbounded(snapshot)` **only** when the caller passed `allowWholeCopy = true`;
otherwise `Unavailable` with a `NOT_BOUNDED`-flavoured reason. Never a silent
whole copy. The entire point is that a caller learns what a read costs *before*
paying, which is what `DataSearch`'s closing notice currently has to reconstruct
after the fact.

### Decision 2 — execution context, threading, cancellation: identical to `snapshotOf`

`scheduler.submit(0)` returning a `CompletableFuture`, cancellation checked
before the cell is entered, null/`Unavailable`-completing on a terminated
scheduler — the block at `ManagedHost.kt:1249-1262` is the line-for-line model.
Foreign-thread submission stays legal; on a simulated host each page lands on a
later `step()`/`runToIdle()`, which is correct and testable.

**One page = one scheduler task.** This is the design's actual win over
`snapshot()` and the thing `V1C-BENCH` measured: a 10⁵-row read becomes ~500
short tasks interleaved with the cell's real work instead of one task that owns
the cell's thread for the duration. Do not batch pages inside one task.

### Decision 3 — ownership: exclusive values are described, never paged

**A page never carries an `Owned`/`Leased` value or a copy of one.** For an
entry whose value is exclusive, the page carries a *presence descriptor* — key,
declared type name, and disposition (held / discharged) — and increments
`exclusivesElided`.

Rationale, and it is absolute: `Serializable` implies copy, and copying an
exclusive payload is the prohibition itself
(`doc/spec/20-dataflow-semantics/23-ownership.md:19-21`, taps `:112-151`). A tap
is already borrow-only ("never retained, mutated, or released", `:119`); a read
is weaker than a tap, so anything a tap may not do a read certainly may not.
`exclusivesElided` being non-zero is an honest signal rather than a silent gap —
the same discipline `DataSearch`'s notice already applies to skipped cells.

This deliberately makes the bounded read's ownership contract **stronger** than
`Stateful.snapshot()`'s, which today serializes whatever the fold holds. Closing
that older gap is **out of scope**; it is a research question (see Report).

### Decision 4 — bounding: an opaque, cell-minted, key-based cursor

The kernel never interprets a cursor. Recommended contents for the data-cell
family — and `V1C-CELLS`/`V1C-OPS` will follow this — are the last-returned
*key* plus the frontier at page time. **Key-based, not index-based**: an index is
invalidated by any removal earlier in the enumeration, a key survives every
mutation except its own removal. The kernel does not mandate the encoding
because only the cell knows its layout — the same reasoning that keeps
`Interest` polymorphic across the bridge
(`kernel/src/main/kotlin/civictech/cell/protocol/StateRequestProtocol.kt:33-43`).

`limit` is a **hard** cap on entries. `byteBudget` is **advisory** and
cell-estimated; a cell that cannot estimate ignores it. Both live kernel-side so
the *copy* is bounded, not just the *rendering* — which is the defect
`ValueEncoder`'s budget papers over today.

**Two exceptions the wave-9 tickets found while being written, which your KDoc
must anticipate rather than leave them to invent:**

- **A cell with no key.** `ListCell` (`kernel/src/main/kotlin/civictech/cell/data/ListCell.kt`)
  has no element identity at all; a key-based cursor is structurally
  unavailable, and minting identity would mean new fold-path state (P2) and a
  changed `snapshot()` shape (forbidden). A **positional** cursor is therefore
  permitted as a documented, per-cell exception, and the weaker guarantee it
  carries — a removal earlier in the sequence can shift or skip an entry — must
  be stated on the page, not buried in the cell. Decide whether that weakening
  is expressible in the types (e.g. a flag on `StatePage`, or a distinct
  `Cursor` flavour) or only in KDoc, and say which.
- **Insertion order is not a stable enumeration order.** The backing maps are
  `LinkedHashMap`s, so a remove-then-re-add moves a key to the tail and can hand
  one key to a walk twice, and `restore()` rebuilds every map from a
  `HashMap`/`HashSet`, so a restored instance enumerates differently. The
  "no entry twice in one walk" guarantee is therefore **an obligation on the
  cell to impose an order**, not a property it inherits. State that explicitly
  in `BoundedStateful`'s KDoc — every wave-9 implementation depends on knowing
  it.

### Decision 5 — stability across pages: verifiable, not promised

A walk is a *sequence of per-page-consistent reads*, not a snapshot. Document
this contract on `StatePage`, in these terms:

Per page, the caller is promised:
- the page is internally consistent — produced on the cell's own execution
  context, between invocations, so no partially-applied delta is ever visible
  (the same guarantee `ObservationSink.current()` documents at
  `kernel/src/main/kotlin/civictech/cell/observe/Observe.kt:44-49`);
- entries are whole; an entry is never split across pages;
- under a key-ordered cursor, no entry is returned twice in one walk;
- the page carries the fold's `TagFrontier` at the moment it was produced.

Across pages:
- **if `frontier` is equal on every page, the union of pages is exactly a
  snapshot of that fold** — a *verifiable* claim the caller checks, not a
  promised one;
- **if it advanced, the union is a smeared read**: it contains every entry
  present for the whole walk, may contain entries added mid-walk, and may miss
  entries removed mid-walk after being passed over. Never torn at entry
  granularity, never duplicated.

**Why not isolation.** Snapshot isolation across pages needs either
copy-on-write versioning inside every state cell — a per-message cost on the
fold path, forbidden by P2 (`doc/spec/00-foundations/02-design-principles.md:21-28`)
— or holding the cell's execution context across the whole walk, which is "viz
blocks the graph" by construction. Detection is cheaper than locking.

**The escalation path for a caller who needs a real snapshot** (document it, do
not build a second mechanism for it): walk the pages recording `frontier₀` from
page 1, then issue one final read with `since = frontier₀`; folding that delta
over the smeared union yields a genuine snapshot at the closing frontier.

### Decision 6 — `since` and `scope` are reused verbatim, as a third orthogonal dimension

`StateRequest` (`StateRequestProtocol.kt:45-51`) already bounds by **time**
(`since: TagFrontier?`, `:17-19`) and by **interest** (`scope: Interest?`,
`:21-26`, `:33-43`). The cursor bounds by **size**. The three are orthogonal and
a big-cell read wants all three: search wants `scope`, a live view wants `since`,
the UI wants `limit`. Reuse the existing types; do not fork them, do not
generalize them, and do not change `StateRequest`.

`StateRead` does **not** subsume `StateRequest`: a pull reply installs a
*baseline* in a consumer's fold
(`kernel/src/main/kotlin/civictech/cell/MessageContext.kt:32-38`), which a read
must never do.

**A cell that cannot honour `since` or cannot produce a `TagFrontier` must say
so, never silently widen.** Several state families carry no tag frontier at all
(`MapCell`, `ListCell`, `Watermark`, `InstanceSet` — the wave-9 ticket authors
verified this). For them, `StatePage.frontier` is null, which is already legal —
but it also means Decision 5's verifiable-stability check and the `since`-based
escalation path are **unavailable**, and a caller passing a non-null `since`
cannot be served. Decide the shape: refuse the request
(`Unavailable(SINCE_UNSUPPORTED)`-flavoured) or answer with an explicit marker
that the delta bound was not applied. **What is forbidden is answering full,
unbounded-by-`since` state as though the bound had been honoured** — the
precedent for being explicit about it is `MessageContext.kt:65-67`'s
"full-state fallback otherwise, `since = null`". Whichever you choose, it is
contract-binding for `V1C-CELLS` and `V1C-BE`; report it verbatim.

### Decision 7 — suspended / drained / migrating: answer with provenance, do not refuse

The kernel says nothing today, so `Cold.kt` guesses. Decided answers:

- **Suspended** (`ManagedHost.suspend` at `:1032-1041`, `isSuspended` at `:231`;
  only the cell's data intake is parked, the host scheduler still runs):
  **answer from the live cell**, `provenance = LIVE_SUSPENDED`. A suspended
  cell's fold is quiescent by construction.
- **Drained host** (`beginDrain`, `:487-511`): **answer from the checkpoint the
  host already holds** — `snapshots[cellRef]` written at `:501` — with
  `provenance = CHECKPOINT` and the drain-time frontier if one is recoverable
  (null if not; do not invent one). Paging a blob that already exists costs
  nothing on any cell thread. If the retained blob is not itself pageable
  (it is a `Serializable` from `snapshot()`, not a `StatePage`), decide and
  document how it is bounded — an honest option is to answer
  `Unbounded(blob)` under `allowWholeCopy` and `Unavailable` otherwise, since
  no cell thread is at risk; state which you chose and why.
- **Held for a migration flip** (`LocationRegistry.isHeld`; the predicate the
  instrument uses is `Cold.kt:109-117`) and cells removed from `cells` by
  `migrate` (`ManagedHost.kt:1063-1077`): `Unavailable(MIGRATING)`. The
  authoritative instance is the target host's; answering from a stale local
  object would be a lie with a timestamp on it.
- **Unhosted / not `Stateful` / terminated scheduler**: `Unavailable`, matching
  `snapshotOf`'s existing null-completion cases (`:1247`, `:1259-1262`).

### Decision 8 — the reference implementation is `SetCell`, and only `SetCell`

Implement `BoundedStateful` on `civictech.cell.data.SetCell`
(`kernel/src/main/kotlin/civictech/cell/data/SetCell.kt`, `snapshot()` at
`:200`) as this ticket's single cell-side implementation. It makes the interface
concrete, gives the wave-neutrality and mid-mutation tests a real subject, and
sets the pattern the two wave-9 tickets copy.

**Do not implement any other cell.** `V1C-CELLS` (map/set-backed data cells) and
`V1C-OPS` (composite operator cells) own the rest and run in parallel in wave 9;
touching their files here creates the exact collision the file split exists to
prevent.

### Decision 9 — explicitly out of scope

- **Remote / across-a-bridge reads.** No-go per the design note §3.7: a bounded
  read is wave-neutral *because* it is not an emission, and the repository's only
  disclosure seam is an emission seam
  (`kernel/src/main/kotlin/civictech/cell/port/FanOutlet.kt:105-117`, `:293`; 93
  I-28 "filtered, not forked",
  `doc/spec/20-dataflow-semantics/21-propagation.md:72-76`). Shipping a read
  across a membrane today would bypass the only disclosure mechanism that exists
  — a security regression wearing a feature's clothes. Nothing in
  `civictech.cell.wire`, no new protocol message, **no wire or binary
  compatibility break**.
- **`View` / `ObserveCell` paging.** `View` (`Observe.kt:72-111`) mirrors the
  `Stateful` shape; growing it an optional `readBounded` is a legitimate later
  increment but is not needed by any wave-10 consumer (the inspector reads
  observed cells from the materialized fold, which is already free). Leave it.
- **Changing `Stateful`, `StateRequest`, `CatchUp`, `FanOutlet`, or any
  replication/evolution seam.**
- **`concord/**`** — binding constraint 7. Conformance scenarios for this
  primitive are `V1C-CONCORD` (wave 11), which owns the schema change they need.

## Files expected to touch

- **New**: `kernel/src/main/kotlin/civictech/cell/BoundedRead.kt`.
- `kernel/src/main/kotlin/civictech/cell/host/ManagedHost.kt` — `readState`
  beside `snapshotOf` (`:1244`), plus the drained-checkpoint arm reading the
  existing `snapshots` map (`:499-502`) and the held/migrating arm.
- `kernel/src/main/kotlin/civictech/cell/data/SetCell.kt` — the reference
  `BoundedStateful` implementation. **Additive only**: `snapshot()` at `:200`
  must be untouched and must keep behaving identically, because
  `LateJoinCatchUpTest`'s migrate round-trip and every drain/migration path
  depend on it.
- **New tests** under `kernel/src/test/kotlin/civictech/cell/` — see below.

Nothing else. In particular nothing under `inspect/**` (`V1C-BE` owns the
consumers), `wire/**`, `concord/**`, `demo/**`, or any other cell class.

Touching files outside this list: note it in the completion report rather than
expanding silently.

## Read first

- `doc/spec/90-roadmap/98-inspector-v4-plan/20-wave-neutral-read-design.md` —
  **the whole document.** §1.3's observer table, §2's constraint survey, §3
  (the primitive), §5 (kernel surface touched), §6.2 (the tests owed) and §7
  (go/no-go and open questions) are all load-bearing for this ticket.
- `doc/spec/90-roadmap/98-inspector-v4-plan/30-bounded-read-measurement.md` —
  `V1C-BENCH`'s numbers.
- `doc/spec/90-roadmap/98-inspector-v4-plan/10-design-notes.md` §"Binding
  constraints" (all ten; 1/P2, 2/P6, 5/kernel-transport-neutral, 6/viz-never-
  blocks and 7/no-concord-edits govern you) and §"Standing file split".
- `doc/spec/90-roadmap/97-inspector-plan/90-progress-log.md:1076-1088` — the
  cumulative kernel diff table your addition is judged against.
- `kernel/src/main/kotlin/civictech/cell/host/ManagedHost.kt:1201-1263` —
  `snapshotOf` in full: KDoc `:1201-1243`, the any-thread/any-scheduler contract
  `:1223-1234`, cancellation `:1236-1242`, the submit block `:1249-1257`, the
  terminated-scheduler fallback `:1259-1262`. This is your template.
- `kernel/src/main/kotlin/civictech/cell/host/ManagedHost.kt:487-511`
  (`beginDrain`, blob retention at `:501`), `:1032-1041` (`suspend`), `:218`
  (`suspendedCells`), `:231` (`isSuspended`), `:1063-1077` (`migrate`).
- `kernel/src/main/kotlin/civictech/cell/Stateful.kt` (all 14 lines) and
  `kernel/src/main/kotlin/civictech/cell/observe/Observe.kt:44-49`, `:72-111` —
  the seam you are *not* widening, and the fold-consistency guarantee you are
  reusing.
- `kernel/src/main/kotlin/civictech/cell/MessageContext.kt:32-38`, `:58-72` —
  baseline and `TagFrontier`.
- `kernel/src/main/kotlin/civictech/cell/protocol/StateRequestProtocol.kt:17-26`,
  `:33-51` — `since`, `scope`, and the polymorphic-`Interest`-across-a-bridge
  precedent.
- `kernel/src/main/kotlin/civictech/cell/Ownership.kt` — `Owned`, `Leased`,
  `Borrowed`, `Frozen`, `Redacted`; and
  `doc/spec/20-dataflow-semantics/23-ownership.md:19-21`, `:112-151`.
- `kernel/src/main/kotlin/civictech/cell/data/SetCell.kt` — the whole file; the
  OR-set tag maps are what your cursor enumerates.
- `kernel/src/test/kotlin/civictech/cell/host/SnapshotOfHardeningTest.kt` — the
  exemplar for testing this accessor's edges (cancellation, terminated
  scheduler, non-`Stateful`, unhosted).
- `kernel/src/test/kotlin/civictech/cell/data/StatePullTest.kt`,
  `LateJoinCatchUpTest.kt`, `InterestScopedCatchUpTest.kt`,
  `kernel/src/test/kotlin/civictech/cell/link/CatchUpTest.kt`,
  `kernel/src/test/kotlin/civictech/cell/port/PullServiceRefusalTest.kt`,
  `PullPolicyCompositionTest.kt`,
  `kernel/src/test/kotlin/civictech/cell/replication/DeliveredWatermarkTest.kt`
  — the existing tests that bound this design (design note §6.1). **All must
  stay green and unmodified.** `PullServiceRefusalTest` in particular: a bounded
  read must not become a back door around a `PullOnOpen(requireServing = true)`
  refusal.
- `AGENTS.md` §"Core invariants to protect" and §"Verification".

Do not modify: `inspect/**`, `wire/**`, `gen/**`, `nature/**`, `concord/**`,
`testkit/**`, `demo/**`, any cell class other than `SetCell`, any plan document
other than this ticket's `**Status**:` line, and
`doc/spec/90-roadmap/97-inspector-plan/20-api-contract.md` (orchestrator-owned).

## Acceptance criteria

Design-note §6.2 names the tests owed; these are the checkable form of them.

- [ ] **Paging works.** Walking a `SetCell` at a stable frontier yields pages
      whose union equals `snapshot()`'s content exactly; `limit` is respected on
      every page; the cursor resumes; the final page carries `next == null`.
- [ ] **Mid-fold mutation.** Applying ops between pages: the frontier stamp
      advances, every entry is whole, no key appears twice in the walk, and the
      union is the documented smear (contains every survivor; may contain
      additions). Assert the documented contract, not a stricter one.
- [ ] **Wave neutrality, asserted not claimed.** Read a pull-serving `SetCell`
      that has an `observe`-attached tap and a `WatermarkCell` companion; assert
      `outlet.waveState()` is unchanged across the whole walk, the tap fired
      zero times, no delivered-watermark row moved, and there are no dead
      letters.
- [ ] **The contrast case, in the same test.** The same state fetched via
      `StateRequest` moves `waveState().highWater` by exactly one and still
      moves no watermark row. This pins the design note's §1.2 correction in a
      test so the KDoc cannot go stale again.
- [ ] **Suspended.** Suspend the cell mid-walk; the next page answers with
      `provenance = LIVE_SUSPENDED` and an unchanged frontier, and the walk
      completes.
- [ ] **Drained host.** A read against a cell on a drained host answers with
      `provenance = CHECKPOINT` (or the documented `Unbounded` arm — whichever
      you chose in Decision 7), carries the drain-time frontier if one exists,
      and schedules **no task on any cell thread**.
- [ ] **Migrating.** A held/migrated ref answers `Unavailable(MIGRATING)`, never
      a stale local read.
- [ ] **Abandoned read.** `cancel(false)` before the page task runs: the cell is
      never entered. Extend the T18 pattern at `ManagedHost.kt:1250-1256`.
- [ ] **Not bounded.** A `Stateful`-but-not-`BoundedStateful` cell answers
      `Unavailable` with `allowWholeCopy = false` and `Unbounded(snapshot)` with
      `allowWholeCopy = true`. Never a silent whole copy.
- [ ] **Ownership.** A cell holding `Owned` values pages descriptors only:
      `exclusivesElided > 0`, `Owned.take` was never called, no discharge or
      dead-letter accounting moved, and no exclusive value or copy of one
      appears in any page.
- [ ] **Cursor invalidated mid-read.** Remove the key a cursor names, then
      resume: the walk continues from the next key rather than restarting or
      throwing.
- [ ] **Simulated host.** A walk against a `SimulationController`-driven host
      makes progress one page per `step()`/`runToIdle()` and never blocks the
      caller — the `snapshotOf` foreign-thread contract (`:1223-1234`) still
      holds per page.
- [ ] `snapshot()` on `SetCell` is byte-for-byte behaviourally unchanged;
      `LateJoinCatchUpTest`'s migrate round-trip still passes unmodified.
- [ ] Every added public member carries KDoc naming this ticket (`V1C-KERNEL`)
      and the reason it exists, in the register of `isSuspended`
      (`ManagedHost.kt:220-231`) and `attentionOf` (`:673-706`). `StatePage`'s
      KDoc states Decision 5's per-page and across-page contract in full,
      including the `since`-based escalation path.
- [ ] No `Stateful` change, no `StateRequest` change, no wire/codec change, no
      `concord/` edit, nothing under `inspect/`, no other cell class. No
      generated/build output. No gap (`G-*`) or consistency (`C-*`) markers
      removed.

## Verify

```bash
./gradlew :kernel:test --tests 'civictech.cell.host.*'
./gradlew :kernel:test --tests 'civictech.cell.data.*'
./gradlew :kernel:test
./gradlew test
```

Then the strongest available neutrality evidence — the composition exit gate,
which runs partitioned + replicated + durable + glitch-free in one graph:

```bash
./gradlew :demo:exchange:test
```

`:kernel:compileKotlin` depends on `:gen:test`, so a generator regression
surfaces here as a kernel compile failure (`AGENTS.md` §Repository map).

## Report on completion

- Checks run and their results.
- **The exact kernel diff**, in the format of the closing report's table
  (`90-progress-log.md:1076-1088`): file, addition, line count, justification.
  The C8 checkpoint audits this diff for P2/P6, read-only-ness and
  transport-neutrality before anything merges.
- **The verbatim final signatures** of `BoundedStateful`, `StateRead`,
  `StatePage`, `Cursor`, `Provenance`, `StateReadResult` and
  `ManagedHost.readState` — `V1C-CELLS`, `V1C-OPS` and `V1C-BE` are written
  against this ticket's sketch and must be able to code against your actual
  shape without reading your diff. Flag every deviation from the sketch loudly.
- **The P2 claim, checkable**: name every call site you added and state that
  none of them is on a per-message path.
- The `SetCell` cursor encoding you chose, and why it is stable under removal —
  `V1C-CELLS`/`V1C-OPS` copy this.
- What you did for the drained/checkpoint arm (Decision 7's open sub-question)
  and why.
- **Flag to the orchestrator**, do not act on: any contract-visible consequence
  for `doc/spec/90-roadmap/97-inspector-plan/20-api-contract.md` (`V1C-BE` will
  propose the HTTP shape; you only flag what the kernel now makes possible).
- **Flag separately, as research input** (the design note §7 proposes these for
  `doc/spec/90-roadmap/95-research-plan.md`; that file is owner-maintained and
  neither you nor the orchestrator edits it here): anything you learned about
  (a) disclosure for non-emitting reads, (b) ownership in `Stateful.snapshot()`
  (the older, undefined seam this ticket declines to inherit), (c) cursor
  semantics across a scatter-gather boundary, (d) the `Effectful` processed
  frontier and baselines.
- Anything specified here you could not do, and why.
