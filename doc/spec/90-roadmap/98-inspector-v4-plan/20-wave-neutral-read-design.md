# The wave-neutral bounded state read (MRB-157) — design note

**Status**: Exploratory — Draft. A proposal for the C-replan checkpoint; no
implementation is ticketed by this plan.

Produced by ticket `tickets/V1C-DESIGN.md` (Wave 6, doc-only). It feeds the
C-replan checkpoint in `00-orchestration.md`, which decides whether any of this
becomes a ticket. Line numbers were read in the `ticket/v1c-design` worktree at
`ab6b71b` (2026-07-29), after waves 1–5 of this run landed.

Three headline findings, stated up front because the rest of the document is
their argument:

1. **The blocking claim the repository makes about itself is wrong in its
   mechanism.** `kernel/src/main/kotlin/civictech/cell/link/CatchUp.kt:26-32`
   says a `baselineTo` reply "inflates the `waveState().highWater` that
   `civictech.cell.replication` reads directly as a source's delivered
   high-water". Nothing under `kernel/src/main/kotlin/civictech/cell/replication/`
   calls `waveState()`; the single production reader is
   `kernel/src/main/kotlin/civictech/cell/evolve/Evolution.kt:198`, and for that
   reader the counter consumption is not a hazard but a *requirement*. §1.2
   gives the corrected wording. `inspect/src/main/kotlin/civictech/inspect/DataSearch.kt:40-45`
   and `../97-inspector-plan/90-progress-log.md:1124-1128` repeat the same
   claim; all three are downstream of one sentence.
2. **A pull reply is still the wrong primitive for an instrument** — but for a
   different, smaller, and more precisely nameable reason: it is a *message*.
   It needs a link or a tap to be received at all, it enters the requester's
   inlet, and it advances an `Effectful` cell's durable processed frontier
   (`kernel/src/main/kotlin/civictech/cell/host/ManagedHost.kt:770-800`) — the
   one observer of the wave counter that does **not** exempt baselines. It
   fails on P6 (topology for a read), not on wave neutrality.
3. **The wave-neutral read already exists and is already shipped**;
   what is missing is a *bound*. `ManagedHost.snapshotOf`
   (`kernel/src/main/kotlin/civictech/cell/host/ManagedHost.kt:1201-1263`) has
   every property this design wants except boundedness, a consistency stamp,
   an answer for non-hot cells, and remoteness. The proposal is therefore an
   increment on a landed seam, not a new mechanism.

## 1. Problem, with evidence

### 1.1 A pull reply is an emission

`FanOutlet.baselineTo` (`kernel/src/main/kotlin/civictech/cell/port/FanOutlet.kt:252-265`)
mints `MessageContext(Timestamp(sourceId, waveCounter.incrementAndGet()), ref,
baseline = frontier)` and delivers through `at(replyTo)` (`:262-265`). The reply
therefore consumes one value from the producing outlet's own wave counter — the
I-16 reply-sequencing rule, stated normatively at
`doc/spec/20-dataflow-semantics/21-propagation.md:118-124`.

`pullServe` (`kernel/src/main/kotlin/civictech/cell/link/CatchUp.kt:49-56`) is
the only registration path for a `StateRequest` handler, and exists so the serve
block can call `baselineTo` with the outlet as receiver. Its two production call
sites are `kernel/src/main/kotlin/civictech/cell/data/SetCell.kt:191` and
`kernel/src/main/kotlin/civictech/cell/partition/ShardCell.kt:114`.

`StateRequest` (`kernel/src/main/kotlin/civictech/cell/protocol/StateRequestProtocol.kt:45-51`)
already carries two bounding dimensions: `since: TagFrontier?` (incremental —
only tags beyond a frontier, `:17-19`) and `scope: Interest?` (PN-3c — the
sub-state the requester's interest admits, crossing a bridge as a polymorphic
`Interest`, `:21-26` and `:33-43`). Both matter to §3.

### 1.2 The `waveState().highWater` claim: **stale, and right for the wrong reason**

The PN-2 note at `kernel/src/main/kotlin/civictech/cell/link/CatchUp.kt:26-32`
defers riding push catch-up on `baselineTo` because that "consumes the outlet's
own wave counter (the I-16 reply-sequencing rule), which inflates the
`waveState().highWater` that `civictech.cell.replication` reads directly as a
source's delivered high-water".

Verified against the code:

- `FanOutlet.waveState()` is declared at
  `kernel/src/main/kotlin/civictech/cell/port/FanOutlet.kt:215-216`. Its **only**
  production reader in the whole repository is
  `kernel/src/main/kotlin/civictech/cell/evolve/Evolution.kt:198`
  (`to.adoptWaveState(from.waveState())`, the preserved-epoch transfer of 93
  I-11/I-27). Every other reader is a test. No file under
  `kernel/src/main/kotlin/civictech/cell/replication/` calls it.
- Replication's delivered high-water advances through a **tap**:
  `WatermarkCell.trackDeliveriesOf`
  (`kernel/src/main/kotlin/civictech/cell/data/Watermark.kt:203-208`), wired from
  `Replication.trackDeliveries`
  (`kernel/src/main/kotlin/civictech/cell/replication/Replication.kt:220-241`).
  The tap reads `CurrentContext.get()?.timestamp` (`Watermark.kt:205`), and its
  own KDoc says broadcast emissions "fire taps" while "targeted `at`-catch-up
  does not" (`Watermark.kt:191-193`).
- That KDoc is correct. `FanOutlet.at` (`FanOutlet.kt:267-298`) resolves one
  target out of `consumers`/`taps` and invokes it directly; it never iterates
  `tapOrder`/`consumerOrder`, which is what `call` does (`FanOutlet.kt:160-168`).
  A `baselineTo` reply therefore fires **no** tap and advances **no** delivered
  watermark.
- The settlement predicate that actually reads the lattice —
  `ReplicaQuorum.frontier` (`kernel/src/main/kotlin/civictech/cell/consistency/ReplicaQuorum.kt:86-96`)
  — reads `companion.rows()`, not `waveState()`, and is quantified over *origin
  tags* extracted from payloads, a key space `Replication.kt:236-238` explicitly
  documents as distinct from the per-outlet-epoch lane the tap feeds.

**Verdict: stale in its named mechanism, and correct in its conclusion for a
reason it does not give.** The counter *is* consumed; `waveState().highWater`
*does* rise. But nothing in replication reads it, and the delivered watermark it
names is advanced by a path the reply does not take. Worse for the note's own
argument: for `waveState()`'s single real reader the consumption is
**load-bearing, not harmful** — `adoptWaveState` (`FanOutlet.kt:223-226`) sets a
promoted candidate's counter from the incumbent's, and a counter *excluded* from
that high-water is a counter the candidate would re-mint, aliasing a
`(sourceId, counter)` pair a pull reply already used in the same source lane.
93 I-14 Rule S1 (`FanOutlet.kt:215`, `doc/spec/20-dataflow-semantics/22-consistency.md:70-92`)
is what makes that an error rather than a curiosity.

Proposed replacement for `CatchUp.kt:26-32` — **written here, deliberately not
applied**, because this ticket changes no code:

> ponytail (PN-2): the plan calls for this push catch-up to ride
> [FanOutlet.baselineTo] so push and pull catch-up are marked identically as a
> baseline. That change is deferred, but not for the reason recorded here
> before: [FanOutlet.baselineTo] consumes the outlet's own wave counter (the
> I-16 reply-sequencing rule) and the resulting gaps in the broadcast counter
> sequence are invisible to every wave-plane observer — `WaveFrontier` keys
> pending waves by arrived timestamp and settles edges by `>=` comparison
> (`WaveFrontier.offer`/`isSettled`), a baseline is released immediately and
> admitted to no completeness set, and `civictech.cell.replication`'s delivered
> watermark advances from a **tap**, which a targeted `at` delivery does not
> fire. The one production reader of `waveState()` is
> [civictech.cell.evolve.Evolution]'s preserved-epoch transfer, for which
> counting the reply is required rather than harmful. What the switch would
> genuinely cost is one counter per link install and one baseline-stamped
> arrival where an unstamped one arrives today; the residual hazard is an
> `Effectful` inlet's processed frontier (`ManagedHost`'s PORT_API branch),
> which is the only counter observer that does not exempt baselines.

The same correction is owed to
`inspect/src/main/kotlin/civictech/inspect/DataSearch.kt:38-45`, which quotes
`CatchUp.kt` verbatim as the justification for M5-SEARCH's design, and to
`../97-inspector-plan/90-progress-log.md:1124-1128`, which states it as a
roadmap finding. The M5-SEARCH *decision* survives the correction — see §1.3 —
but its stated reason does not.

### 1.3 What a pull reply actually perturbs

Enumerated by reading every observer of a wave `(sourceId, counter)` in
`kernel/src/main/kotlin` and `wire/src/main/kotlin`:

| Observer | Site | Effect of a `baselineTo` reply |
|---|---|---|
| The outlet's own counter | `FanOutlet.kt:103`, `:263` | Advances by exactly 1. This is the whole perturbation. |
| Preserved-epoch transfer | `Evolution.kt:198` via `FanOutlet.kt:215-226` | Sees the higher high-water — **required**, prevents counter aliasing after promotion. |
| Wave completeness / glitch-free arming | `WaveFrontier.kt:239-246` | Baseline arm: released immediately, never buffered, admitted to no completeness set. Counter never enters `watermark`. |
| Gaps in the broadcast sequence | `WaveFrontier.kt:318-319`, `:355-362`, `:411` | Invisible. Completeness is keyed by *arrived* timestamps and settled by `>=`; nothing enumerates expected counters, so a skipped counter strands nothing. |
| Delivered watermarks (replication) | `Watermark.kt:203-208`, `Replication.kt:220-241` | Not advanced — `at` fires no taps (`FanOutlet.kt:267-298`). |
| Cross-replica settlement | `ReplicaQuorum.kt:86-130` | Quantified over origin tags, a distinct key space (`Replication.kt:236-238`). Unaffected. |
| Absorb-ack | `AbsorbAck.kt:25-31` | Explicitly returns on `ctx.baseline != null` (`:27`). Unaffected. |
| `ReBaseline` / epoch supersession | `MessageContext.kt:74-88`, `FanOutlet.kt:233-250` | Keyed by `sourceId` only. Unaffected. |
| Progress backpressure | `InletPolicy.kt:128` | Rides the incoming reactive context; a baseline never gets there. |
| **`Effectful` processed frontier** | `ManagedHost.kt:770-800`, `HostDurability.kt:252-263` | **Advanced.** The branch tests `cell is Effectful && timestamp != null`; it does **not** test `ctx.baseline`. A reply into an `Effectful` inlet writes `processedFrontier[cell, port][sourceId] = counter` and journals it. |

The last row is the one real hazard, and it is new information: `WaveFrontier`
and `absorbAck` both explicitly exempt baselines, the durable
suppression frontier does not. In-order arrival makes it benign (per-link FIFO,
`doc/spec/20-dataflow-semantics/21-propagation.md:124`, guarantees later live
waves carry larger counters), so it is reachable only where counters can regress
in a lane — which is exactly the landed-RESTART defect C-12 recorded at
`21-propagation.md:163-167`. It should be stated in the corrected KDoc and, if
PN-2's switch is ever taken, tested.

**The honest one-sentence statement of the perturbation.** *A pull reply
consumes one value from the producing outlet's wave counter and delivers it to
one target; it moves no watermark, arms no join, gates no wave and is admitted to
no completeness set, so the only lasting traces are a higher
`waveState().highWater` — which the sole reader needs — a gap in the broadcast
counter sequence that no observer enumerates, and an advance of the target
inlet's `Effectful` processed frontier if that target is `Effectful`.*

**Why the instrument still cannot use it.** Not wave perturbation — topology.
`FanOutlet.at` delivers only to a `consumers`/`taps` entry (`FanOutlet.kt:278`);
an unlinked requester gets nothing and is counted as a target miss
(`FanOutlet.kt:280-291`). So a read-only instrument issuing `StateRequest` must
first install a link or a tap on the producing outlet — which raises attention,
extends the cone, and (for a tap) receives every live emission for the duration
of the request. `DataSearch.kt:46-49` already says exactly this, as its *second*
reason; that second reason is the load-bearing one, and it is a P6 argument.

### 1.4 The read that *is* wave-neutral is unbounded, unstamped and local-only

`ManagedHost.snapshotOf` (`kernel/src/main/kotlin/civictech/cell/host/ManagedHost.kt:1201-1263`
— the ticket's `:1032-1094` moved when V2-KERNEL landed) already gives an
instrument almost everything:

- it runs `Stateful.snapshot()` on the cell's own execution context
  (`:1203-1206`, `:1249`);
- "nothing is linked, nothing is emitted, no wave counter moves" (`:1219-1220`);
- callable from any thread against any scheduler, including
  `SimulationController` (`:1223-1234`);
- cancellation-honouring — an abandoned read costs a dequeue, not a copy
  (`:1236-1242`, `:1250-1256`);
- completes with null, never exceptionally (`:1212-1216`, `:1247`, `:1259-1261`).

What it does not give:

- **Boundedness.** `Stateful.snapshot()`
  (`kernel/src/main/kotlin/civictech/cell/Stateful.kt:11-14`) returns the whole
  state as one `Serializable`. All 30 main-source implementations are full
  copies: `SetCell.kt:200-205` (both OR-set tag maps), `MapCell.kt:49`
  (`HashMap(state)`), `KeyedSetCell.kt:117-122`, `ListCell.kt:62`,
  `ShardCell.kt:185`, `Watermark.kt:210-216`, `InstanceSet.kt:153`,
  `JoinCell.kt:77`, `LookupJoinCell.kt:120`, `GroupByCell.kt:111-114`,
  `MergeableGroupByCell.kt:99`, and so on. The host's own KDoc states the cost
  plainly (`ManagedHost.kt:1217-1221`). Truncation happens *after* the copy, in
  `inspect/src/main/kotlin/civictech/inspect/ValueEncoder.kt` — `MAX_ROWS = 200`
  (`:53`), `MAX_BYTES = 50_000` (`:56`), enforced by `Budget` (`:341-348`) over
  an already-materialized value. The cell's thread pays for 10⁵ rows so the
  instrument can show 200.
- **A consistency stamp.** The caller gets a value with no frontier attached.
  The inspector's per-cell stamp comes from the *observation* path instead:
  `StampedView.frontier` is a `Timestamp?` read from `CurrentContext` at fold
  time (`inspect/src/main/kotlin/civictech/inspect/Observations.kt:543-582`) —
  and note it is a **wave position**, whereas the pull/baseline currency is a
  `TagFrontier` (`kernel/src/main/kotlin/civictech/cell/MessageContext.kt:58-72`).
  Two different stamps for two different read paths.
- **Remoteness.** A cell whose host is null answers `unavailable`; there is no
  wave-neutral read across a bridge (`90-progress-log.md:1167-1169`).
- **A defined story for non-hot cells.** The inspector invented one for itself:
  `inspect/src/main/kotlin/civictech/inspect/Cold.kt:68-119`'s `Heat`
  (`HOT`/`SUSPENDED`/`DRAINED`/`HELD`/`UNHOSTED`, `isReadable` true only for
  `HOT`, `:98`). The kernel says nothing, so the instrument guesses from
  registry metadata and skips.

### 1.5 The consequence, quantified

`DataSearch`'s bounds — `MAX_CELLS = 50`, `BUDGET_MS = 2_000` (`DataSearch.kt:345-348`)
— are sized around whole-state copies on cell threads, and the closing notice it
emits (`DataSearch.kt:309-339`) is the user-visible confession: it reports cells
capped, cells unanswered inside the budget, and cells "read only to the first
200 rows" (`:321-322`). Three inspector capabilities are blocked on the same
missing bound: browse-everything state chips (a summary on many cells at once),
honest data search, and big-cell state views. That is MRB-157, and
`90-progress-log.md:1131-1133` and `:1173-1174` name it in exactly these terms.

## 2. Constraint survey

Each constraint, its source, and what it forbids a proposal from doing.

- **P2 — near-zero-cost fast path** (`doc/spec/00-foundations/02-design-principles.md:21-28`;
  restated as binding constraint 1 in `10-design-notes.md:129`). Nothing on the
  per-message path. This rules out any design that maintains a secondary index,
  a version chain or a copy-on-write history *inside the fold* to make paged
  reads stable. Rare operations may be arbitrarily expensive; folds may not.
- **P6 — interest drives resources** (`02-design-principles.md:51-55`;
  `10-design-notes.md:130-132`). A read must raise no attention, create no link
  and extend no cone. This is precisely what makes "browse everything" hard: the
  point is a read that is *not* a subscription. It is also, per §1.3, the real
  reason `StateRequest` is unusable for an instrument — and the reason
  `snapshotOf` is usable (`ManagedHost.kt:1219-1220`).
- **Ownership** (`doc/spec/20-dataflow-semantics/23-ownership.md:19-21`, taps
  §`:112-151`; `10-design-notes.md:133`). Taps get `Borrowed` only — "never
  retained, mutated, or released" (`23-ownership.md:119`) — and `Borrowed` must
  not cross a machine boundary (`:130`). A read is weaker than a tap and must
  never consume, copy, delay or leak an `Owned`/`Leased` payload. **The
  uncomfortable finding:** `Stateful.snapshot()`'s `Serializable` return type
  already implies an answer — serialize, i.e. *copy* — and
  `23-ownership.md` has no rule covering a fold whose state contains exclusive
  values (G-46 at `:220` covers only the crash-loss half). So today's
  drain/migrate/checkpoint seam has an undefined ownership story, and a bounded
  read that reuses the seam inherits it. §3 declines to inherit it; §7 files the
  pre-existing question as research rather than solving it here.
- **Per-cell consistency only (F-5)** — accepted, and stated in the plan tree
  rather than in chapter 22: `10-design-notes.md:134-135`,
  `../97-inspector-plan/10-target-v3.md:98-100` ("cross-panel wave alignment is
  NOT guaranteed … each state view carries its own frontier stamp"). A **paged**
  read sharpens this along a second axis: not "do two cells agree?" but "does one
  cell agree with itself between page 1 and page 7?". §3.4 answers it head-on.
- **Glitch-freedom and wave completeness**
  (`doc/spec/20-dataflow-semantics/22-consistency.md:323-343`;
  `21-propagation.md:78-94`). The existing answer for catch-up is that a baseline
  is excluded from every wave-completeness set and carries
  `MessageContext.baseline`; `WaveFrontier.kt:239-246` implements exactly that.
  The question this poses for a read is sharper than it looks: **does a
  wave-neutral read need to be a message at all?** The answer this design takes
  is no — see §3.6. A non-message cannot violate completeness because it never
  reaches an inlet, and the constraint it must instead satisfy is the *fold*
  invariant (no partially-applied delta), which the host's execution context
  already provides.
- **Replication watermarks and epochs**
  (`doc/spec/40-distribution/42-replication.md:390-423` — scatter-gather pull,
  "per-shard-consistent, cross-shard-arbitrary" at `:401`, per-instance
  `RetainedFrontiers` at `:413-423`; `Watermark.kt:203-208`,
  `Replication.kt:220-241`, `Evolution.kt:190-198`). A read must advance no row
  of the lattice and must not perturb the epoch a promotion would adopt. §1.3
  establishes the standard: fire no tap, mint no wave.
- **Membrane disclosure** (`doc/spec/40-distribution/43-security.md:63-110`
  seam 3, decided 93 I-28 at `93-feature-interactions.md:10793`;
  `21-propagation.md:72-76` "filtered, not forked"). A catch-up unicast crossing
  a boundary must pass the disclosure filter; `FanOutlet.at` routes targeted
  delivery through `disclosureFilter` (`FanOutlet.kt:267-298`, `:293`) precisely
  for this. **The structural tension this design must name:** the disclosure
  seam is an *outlet* seam applied to emissions (`FanOutlet.kt:105-117`). A read
  that is not an emission has no outlet to be filtered through — so
  wave-neutrality removes the read from the only disclosure mechanism the
  repository has. This is already true of shipped `snapshotOf`, which reads a
  cell's whole state with no filter at all; it is tolerable today only because
  `InspectorServer` binds loopback-only (`10-design-notes.md:73-75`) and the read
  never leaves the JVM. It stops being tolerable the moment the primitive crosses
  a membrane or a bridge. §3.7 defers remoteness on exactly this ground.
- **Execution context and schedulers** (`ManagedHost.kt:1223-1234`). The read
  runs on the cell's own context; on a simulated host it lands on the next
  `step()`/`runToIdle()`, not before. Any per-page design must keep that true
  per page, and must keep foreign-thread submission legal.
- **Viz never blocks** (`10-design-notes.md:139`). Bounded, abandonable,
  cancellable — the properties `snapshotOf` already has (`:1236-1242`) and that a
  paged read must preserve per page rather than per walk.
- **Kernel stays transport-neutral, changes are explicitly-listed read-only
  accessors** (`10-design-notes.md:136-138`). No reflection, no HTTP/JSON in
  `kernel/`. V2-KERNEL's `attentionOf` (`ManagedHost.kt:702-706`) and the
  lifecycle listener (`:282-330`) are the shape precedent: a named accessor
  threaded through existing structures.

## 3. The proposed primitive

**Name.** A *bounded state read*: `readState`, an additive sibling of
`snapshotOf` on `ManagedHost`, backed by an opt-in cell interface.

### 3.1 Signatures and where they live

A new top-level file `kernel/src/main/kotlin/civictech/cell/BoundedRead.kt`,
beside `Stateful.kt` (it is a cell capability, not an app-facing view; the
`observe` package is the app-facing seam and depends on this, not the reverse):

```kotlin
interface BoundedStateful : Stateful {
    fun readBounded(request: StateRead): StatePage
}

data class StateRead(
    val cursor: Cursor? = null,          // opaque, cell-minted; null = start
    val limit: Int = 200,                // max entries in the page
    val byteBudget: Int = 50_000,        // cell-estimated, advisory
    val scope: Interest? = null,         // null = Interest.Total
    val since: TagFrontier? = null,      // null = full state, not a delta
)

data class StatePage(
    val entries: List<Serializable>,     // whole entries, never partial
    val next: Cursor?,                   // null = walk complete
    val frontier: TagFrontier?,          // the fold's tag frontier at page time
    val provenance: Provenance,          // LIVE | LIVE_SUSPENDED | CHECKPOINT
    val exclusivesElided: Int,           // §3.3
)

@JvmInline value class Cursor(val token: Serializable)

enum class Provenance { LIVE, LIVE_SUSPENDED, CHECKPOINT }
```

and on `ManagedHost`, beside `snapshotOf`:

```kotlin
fun readState(ref: CellRef, request: StateRead): CompletableFuture<StateReadResult>
```

with `StateReadResult` a sealed result: `Page(StatePage)` |
`Unbounded(Serializable)` | `Unavailable(Reason)`.

**Decision: `BoundedStateful` extends `Stateful`; it is not a method added to
`Stateful`.** Adding `readBounded` to `Stateful` would force all 30 main-source
implementations (§5) to implement it, would ripple into `View`
(`kernel/src/main/kotlin/civictech/cell/observe/Observe.kt:72-111`, which mirrors
the `Stateful` shape deliberately, `:79`) and into every driver adapter, and
would make a paged read a precondition for being drainable. That is a very large
blast radius for a diagnostic capability. Opt-in keeps the seam that
drain/migration/durability depend on (`doc/spec/30-execution-model/33-mobility.md:100-102`,
`doc/spec/20-dataflow-semantics/24-data-cells.md:567`) byte-for-byte unchanged.

**A cell that does not implement it.** `readState` answers
`Unbounded(snapshot)` only if the caller passed `allowWholeCopy = true`;
otherwise `Unavailable(NOT_BOUNDED)`. Never a silent whole copy — the whole
point is that the caller learns what a read costs *before* paying, which is
what `DataSearch`'s closing notice currently has to reconstruct after the fact
(`DataSearch.kt:309-339`).

### 3.2 Execution context, threading, cancellation

Identical to `snapshotOf` and for the same reasons: `scheduler.submit(0)`
returning a `CompletableFuture`, cancellation checked before the cell is
entered, null-completing on a terminated scheduler
(`ManagedHost.kt:1244-1263`). **One page = one scheduler task.** That is the
design's actual P2/viz-never-blocks win over `snapshot()`: a 10⁵-row read
becomes 500 short tasks interleaved with the cell's real work instead of one
task that owns the cell's thread for the duration. On a simulated host each page
lands on a later `step()`, which is the correct and testable behaviour.

### 3.3 Ownership: exclusive values are described, never paged

**Decision: a page never carries an `Owned`/`Leased` value or a copy of one.**
For an entry whose value is exclusive, the page carries a *presence descriptor*
— key, declared type name, and disposition (held / discharged) — and increments
`exclusivesElided`. Rationale: `Serializable` implies copy, and copying an
exclusive payload is the prohibition itself
(`23-ownership.md:19-21`, `:112-151`); a read is weaker than a tap, which is
already borrow-only, so anything a tap may not do a read certainly may not.
`exclusivesElided` is non-zero as an honest signal rather than a silent gap —
the same discipline `DataSearch`'s notice applies to skipped cells.

This deliberately makes the bounded read's ownership contract *stronger* than
`Stateful.snapshot()`'s, which today serializes whatever the fold holds. Closing
that older gap is out of scope and filed in §7.

### 3.4 Bounding: cursor and limit, and the mid-fold mutation case

**What a cursor is.** An opaque token minted by the cell and never interpreted
by the kernel. Recommended contents for the data-cell family: the last-returned
*key* plus the frontier at page time. **Key-based, not index-based**, because an
index is invalidated by any removal anywhere earlier in the enumeration whereas a
key survives every mutation except its own removal. The kernel does not mandate
the encoding because only the cell knows its state layout — this is the same
reasoning that keeps `Interest` polymorphic across the bridge
(`StateRequestProtocol.kt:33-43`).

**Stability across folds: none, and the design says so instead of pretending.**
A walk is a *sequence of per-page-consistent reads*, not a snapshot. The caller
is promised, per page:

- the page is internally consistent — produced on the cell's own execution
  context, between invocations, so no partially-applied delta is ever visible
  (the same guarantee `ObservationSink.current()` documents at `Observe.kt:44-49`);
- entries are whole; an entry is never split across pages;
- under a key-ordered cursor, no entry is returned twice in one walk;
- the page carries the fold's `TagFrontier` at the moment it was produced.

And across pages:

- **if `frontier` is equal on every page of the walk, the union of pages is
  exactly a snapshot of that fold.** This is a *verifiable* stability claim, not
  a promised one — the caller checks it rather than trusting it.
- **if it advanced, the union is a smeared read**: it contains every entry
  present for the whole walk, may contain entries added mid-walk, and may miss
  entries removed mid-walk after being passed over. It is never torn at entry
  granularity and never duplicated.

**Why not isolation.** Snapshot isolation across pages requires either
copy-on-write versioning inside every state cell — a per-message cost on the
fold path, forbidden by P2 — or holding the cell's execution context across the
whole walk, which is "viz blocks the graph" by construction. Detection is
cheaper than locking and is exactly the deal `since: TagFrontier` already offers
pull consumers (`21-propagation.md:121-124`).

**The escalation path for callers who need a real snapshot.** For a
tag-carrying cell, walk the pages recording `frontier₀` from page 1, then issue
one final bounded read with `since = frontier₀`; folding that delta over the
smeared union yields a genuine snapshot at the closing frontier. This reuses
`since` rather than inventing a second mechanism, and it is the reason `since`
belongs in `StateRead` (§3.5).

**Limit and byte budget.** `limit` is a hard cap on entries; `byteBudget` is
advisory and cell-estimated (a cell that cannot estimate ignores it). Both live
kernel-side rather than encoder-side so the *copy* is bounded, not just the
*rendering* — which is the whole defect `ValueEncoder`'s 200-row budget
(`ValueEncoder.kt:53-56`) papers over today.

### 3.5 Relationship to `since` and `scope`: a third, orthogonal dimension

`StateRequest` already bounds by **time** (`since: TagFrontier?` — only tags
beyond a frontier, `StateRequestProtocol.kt:17-19`) and by **interest**
(`scope: Interest?` — the sub-state the requester is entitled to,
`:21-26`). The cursor bounds by **size**. The three are orthogonal and a
big-cell read wants all three at once: search wants `scope`, a live view wants
`since`, the UI wants `limit`.

**Decision: the cursor is a third dimension, not a generalization and not a
replacement.** `since` and `scope` stay exactly where they are, on
`StateRequest`, and are reused verbatim in `StateRead`. A cursor cannot be
expressed on `StateRequest` at all, because a `StateRequest` reply is a single
message (`21-propagation.md:118-124`); paging it would require either a
multi-message reply protocol with its own sequencing and abandonment semantics,
or a stateful server-side iterator across `StateRequest`s. Both are larger than
the primitive being proposed. Conversely `StateRead` does not subsume
`StateRequest`: a pull reply installs a *baseline* in a consumer's fold
(`MessageContext.kt:32-38`), which a read must never do.

### 3.6 Suspended, drained, migrating: answer with provenance, do not refuse

The kernel says nothing today, so `Cold.kt:68-119` guesses. Decided answers:

- **Suspended** (`ManagedHost.kt:1032-1041`; only the cell's data intake is
  parked, the host scheduler still runs): **answer from the live cell**,
  `provenance = LIVE_SUSPENDED`. A suspended cell's fold is quiescent by
  construction, which makes it the *most* stable thing in the graph to read.
  Today it is skipped and counted (`DataSearch.kt:67-73`) purely because the
  instrument could not tell the difference.
- **Drained host** (`ManagedHost.beginDrain`, `:487-511`): **answer from the
  drain checkpoint the host already holds** — `snapshots[cellRef] = cell.snapshot()`
  at `:499-502` — with `provenance = CHECKPOINT` and the drain-time frontier.
  Paging a blob that already exists costs nothing on any cell thread.
- **Held for a migration flip** (`registry.isHeld`, `Cold.kt:112`) and cells
  removed from `cells` by `migrate` (`ManagedHost.kt:1063-1077`):
  `Unavailable(MIGRATING)`. The authoritative instance is the target host's;
  answering from a stale local object would be a lie with a timestamp on it.
- **Unhosted / not `Stateful` / terminated scheduler**: `Unavailable`, matching
  `snapshotOf`'s existing null-completion cases (`:1247`, `:1259-1261`).

The caller distinguishes all of these from the `provenance` field and the
`Unavailable` reason — which is precisely the information `Heat` currently
reconstructs from registry metadata outside the kernel.

### 3.7 Remote cells: explicitly deferred, with the reason

**Deferred, and not for effort reasons.** A bounded read is wave-neutral
*because* it is not an emission — and the repository's only disclosure seam is
an emission seam (`FanOutlet.kt:105-117`, `:293`; 93 I-28 "filtered, not
forked", `21-propagation.md:72-76`). A read crossing a membrane or a bridge
therefore has no filter to pass through, and shipping one would be a disclosure
regression rather than a feature. Sketch of what a remote answer needs, so the
replan can size it: a management-class request riding `PORT_PROTOCOL` like
`StateRequest` does (`StateRequestProtocol.kt:33-43`); a read-side disclosure
transform registered on the boundary alongside the outbound one; and a decision
about whether a cursor may cross a bridge at all given that the answering
instance may change between pages under scatter-gather
(`42-replication.md:390-423`, "per-shard-consistent, cross-shard-arbitrary" at
`:401`). §7 files the disclosure question as research.

### 3.8 Wave-neutrality, argued

Against the observer list of §1.3, a `readState` call:

- never enters `FanOutlet.call` or `at`, so `waveCounter` does not move
  (`FanOutlet.kt:143-144`, `:263`) and `waveState()` is unchanged — the epoch a
  promotion would adopt (`Evolution.kt:198`) is untouched;
- fires no tap, so no delivered-watermark row advances
  (`Watermark.kt:203-208`) and no replica settlement predicate changes
  (`ReplicaQuorum.kt:86-96`);
- is never offered to a `WaveFrontier`, so it neither buffers, releases,
  advances an edge watermark nor becomes an expected sibling
  (`WaveFrontier.kt:231-271`, `:355-362`);
- reaches no inlet, so it cannot touch the `Effectful` processed frontier
  (`ManagedHost.kt:770-800`) — the one observer a `baselineTo` reply does
  perturb;
- installs no link and issues no `EdgeOpen`, so no `PullOnOpen` fires
  (`GlitchFree.kt:80-81`) and no attention is raised (P6);
- emits nothing, so `absorbAck`, `ReBaseline` and `Progress` are all
  structurally unreachable.

The only shared mutable thing it touches is the cell's own execution context for
the duration of one page — the same thing `snapshot()` touches today, for a
bounded fraction of the time.

## 4. Alternatives considered and rejected

### 4.1 A counter-neutral baseline reply lane (the change `CatchUp.kt:26-32` defers)

The closest alternative: make `baselineTo` mint no counter, so `StateRequest`
becomes usable by a read-only instrument, and unify push and pull catch-up as
PN-2 wants.

*What it buys.* `StateRequest` already crosses bridges
(`kernel/src/test/kotlin/civictech/cell/data/PartitionedPullScopeWireTest.kt`),
already carries `since` and `scope`, and already passes the disclosure filter
on the way out (`FanOutlet.kt:293`) — i.e. it solves §3.7's remoteness problem
that the proposal defers. It also removes one of two catch-up dialects, which
`WaveFrontier.kt:200-229` documents as a permanent, load-bearing wart.

*What it costs.* `MessageContext.timestamp` is non-null
(`MessageContext.kt:51`), so a counter-neutral emission needs either a nullable
timestamp — a wire-shape change touching every consumer and codec, against this
repository's additive-evolution default — or a reserved sentinel counter, which
is a lie in the same field the completeness machinery reads. Note that a
counter-neutral targeted delivery *already exists*: `catchUpOnLinked`
(`CatchUp.kt:34-38`) sends through `at` with no stamped context at all and lands
in `WaveFrontier`'s null arm (`:233-237`). So the mechanism is not the hard part;
the *stamp* is.

*Why it loses as the answer to MRB-157.* Even counter-neutral, the reply is
still a message: it must be received by a link or a tap
(`FanOutlet.kt:278-291`), it enters the requester's inlet, and it advances an
`Effectful` processed frontier. A read that must first create topology is not
"browse everything" — it fails P6, which is the constraint MRB-157 exists to
respect. **Recommended as a separate, complementary item**, not as this one:
it is the right substrate for *remote* bounded reads and for PN-2's unification.
And per §1.2 its stated blocker has evaporated — the honest cost of the switch
is one counter per link install plus the `Effectful`-frontier residual, which is
a much smaller thing than the note it is deferred by claims.

### 4.2 An inspector-side index or materialized mirror

*Buys.* O(1) search, instant browse-everything chips, no kernel change at all.

*Costs.* An index is fed by subscriptions: every indexed cell becomes an
attended cell, which is a direct P6 violation with a search box in front of it —
the exact framing `DataSearch.kt:21-26` uses to rule out naive fan-out. Memory
in the instrument grows with the graph's whole state. The mirror lags, so every
view acquires a second, unstated consistency story on top of F-5.

*Why it loses.* It moves the cost from the graph to the instrument by making
the instrument a participant. The inspector's entire value proposition is that
it is not one.

### 4.3 Keep `snapshot()`, bound only at the encoder (today's behaviour)

*Buys.* Zero kernel change; already shipped.

*Costs.* The whole copy happens on the cell's own thread
(`ManagedHost.kt:1217-1221`) and then 99% of it is discarded by a 200-row
budget (`ValueEncoder.kt:53-56`, `:341-348`). The bound protects the *instrument*
from big payloads; nothing protects the *graph* from the copy. `MAX_CELLS = 50`
and `BUDGET_MS = 2000` (`DataSearch.kt:345-348`) exist because of it, and the
closing notice (`:309-339`) is the confession.

*Why it loses.* It is the status quo the finding is about. Keeping it is the
no-op branch of the go/no-go, and it is a defensible one — see §7 — but it does
not close MRB-157.

### 4.4 Read from `.durability`'s journals or a checkpoint reader

*Buys.* Trivial wave-neutrality (the live cell is never touched), and it is the
only option that can show a *cold* graph's state at all — the v3 cold screen's
"unavailable" (`90-progress-log.md:1129-1131`, item 3 at `:1170-1172`).

*Costs.* Staleness bounded by checkpoint cadence; works only for durable cells
(a volatile cell has no journal, `24-data-cells.md:557`); and it answers "what
was it" when the UI asked "what is it".

*Why it loses as the primitive, and where it wins.* It is the wrong answer for a
live browse and the right answer for a cold one. Recommend both, in that order:
the bounded read for hot cells, the checkpoint reader for cold ones. Note the
proposal's `provenance = CHECKPOINT` arm (§3.6) is a small, already-available
down-payment on it, since `beginDrain` already retains the blob
(`ManagedHost.kt:499-502`).

## 5. Kernel surface touched

Additive unless marked.

**New (additive):**

- `kernel/src/main/kotlin/civictech/cell/BoundedRead.kt` — `BoundedStateful`,
  `StateRead`, `StatePage`, `Cursor`, `Provenance`, `StateReadResult`.
- `ManagedHost.readState(ref, request)` beside `snapshotOf`
  (`kernel/src/main/kotlin/civictech/cell/host/ManagedHost.kt:1244`), plus the
  drained-checkpoint arm reading the existing `snapshots` map (`:499-502`) and
  the held/migrating arm reading the registry (`Cold.kt:109-117` shows the
  predicate).

**Implementations (additive per cell, ~15 cells for full coverage):** the ones
whose state is genuinely large and enumerable — `SetCell.kt:200-205`,
`MapCell.kt:49`, `KeyedSetCell.kt:117-122`, `ListCell.kt:62`,
`ShardCell.kt:185` (composite: `(TagState, interest, assignedEpoch)` per
`24-data-cells.md:677-679`, so `interest` and `assignedEpoch` must ride *every*
page, not just the first), `Watermark.kt:210-216`, `InstanceSet.kt:153`,
`JoinCell.kt:77`, `LookupJoinCell.kt:120`, `GroupByCell.kt:111-114`,
`MergeableGroupByCell.kt:99`, `JoinSetCell.kt:109-110`, `QuorumSetCell.kt:116`,
`IntersectSetCell.kt:82-83`, `CombineLatestCell.kt:79`. Scalar cells
(`CounterCell.kt:48`, `PnCounterCell.kt:110-111`) need nothing — they page in
one.

**Ripple, no behaviour change:**

- `kernel/src/main/kotlin/civictech/cell/observe/Observe.kt:72-111` — `View`
  mirrors the `Stateful` shape (`:79`). If `ObserveCell` (`:143-146`, snapshot at
  `:206`) is to page identically, `View` grows an optional `readBounded`
  alongside its `snapshot`/`restore` pair. Optional, so the three shipped
  factories (`:85-109`) are unchanged.
- `concord/src/main/kotlin/civictech/concord/driver/kernel/KernelAdapters.kt`
  (five `Stateful` adapters) — untouched, because the interface is opt-in. This
  is the main argument for opt-in: it keeps the concord driver boundary
  (`AGENTS.md`, `civictech.concord.driver.kernel` is the only importer of
  `civictech.cell.*`) out of the diff.

**Explicitly NOT touched:**

- `Stateful` itself (`kernel/src/main/kotlin/civictech/cell/Stateful.kt:11-14`) —
  no new method, so drain (`ManagedHost.kt:499-502`), migration
  (`:1070-1072`, `33-mobility.md:100-102`), promotion state transfer
  (`Evolution.kt:190-198`) and durability checkpoints
  (`HostDurability.kt:237-245`, `24-data-cells.md:567`) all keep the seam they
  have. G-25's shared seam is the strongest reason not to widen `Stateful`: four
  subsystems depend on `snapshot()` being a whole, restorable value.
- `civictech.cell.wire` and the codec — **no wire or binary compatibility
  break**, because remoteness is deferred (§3.7). If a later ticket takes the
  remote arm, it adds a new polymorphic protocol message the way `StateRequest`
  was added (`StateRequestProtocol.kt:33-43`) — additive, plus a *new* boundary
  transform, which is the part that is not additive to the security model.
- `concord/**` — nothing, per binding constraint 7 (`10-design-notes.md:140`).

## 6. Test and conformance obligations

### 6.1 Existing tests that bound the design (must stay green, unmodified)

- `kernel/src/test/kotlin/civictech/cell/data/StatePullTest.kt` — the baseline
  reply is delivered ahead of later live waves, is `ctx.baseline`-tagged, and is
  excluded from wave completeness (a later wave stays pending until an
  absorb-ack settles the edge). This is the test that pins §1.3's exclusion row.
- `kernel/src/test/kotlin/civictech/cell/data/LateJoinCatchUpTest.kt` — late
  joiner ≡ early joiner over 100 seeds, with a control proving the harness
  detects a missed prefix; plus a `Stateful` migrate round-trip preserving OR-set
  tags. Any change to the `Stateful` seam breaks this first.
- `kernel/src/test/kotlin/civictech/cell/data/InterestScopedCatchUpTest.kt` —
  `scope` narrows a reply to exactly the admitted elements, and the
  `RetainedFrontiers` control shows why a merged `since` across instances loses
  tags. §3.5's "orthogonal, reused verbatim" claim answers to this test.
- `kernel/src/test/kotlin/civictech/cell/link/CatchUpTest.kt` — catch-up fires
  once per link, and a null snapshot sends nothing.
- `kernel/src/test/kotlin/civictech/cell/port/PullServiceRefusalTest.kt` — the
  `PULL_SERVICE` nature axis: a `PullOnOpen(requireServing = true)` inlet is
  `Rejected` against a non-serving producer. A bounded read must not become a
  back door around that refusal.
- `kernel/src/test/kotlin/civictech/cell/port/PullPolicyCompositionTest.kt` —
  `catchUpOnLinked`, a replication re-announce and `pullServe` compose on one
  outlet.
- `kernel/src/test/kotlin/civictech/cell/replication/DeliveredWatermarkTest.kt` —
  the lattice is per-peer and converges to each source's true delivered
  frontier. This is the test a wave-neutrality regression would show up in.
- `demo/exchange`'s composition exit gate (`./gradlew :demo:exchange:test`) —
  partitioned + replicated + durable + glitch-free in one graph. A bounded read
  running concurrently with that property run and not changing its outcome is
  the strongest available evidence of neutrality.

### 6.2 New tests owed

- **`BoundedStateReadTest`** — pages union to the whole snapshot at a stable
  frontier; `limit` is respected; the cursor resumes; a final `next == null`
  terminates.
- **Mid-fold mutation** — apply ops between pages; assert the frontier stamp
  advanced, every entry is whole, no key appears twice, and the union is the
  documented smear (contains the survivors, may contain the additions). This is
  §3.4's contract, made falsifiable.
- **`WaveNeutralityTest`** — read a pull-serving `SetCell` that has an
  `observe`-attached tap and a `WatermarkCell` companion; assert
  `outlet.waveState()` is byte-identical before and after, the tap fired zero
  times, no watermark row moved, and there are no dead letters. Include the
  **contrast case**: the same state fetched via `StateRequest` moves
  `waveState().highWater` by exactly one and still moves no watermark row —
  which pins §1.2's corrected claim in a test rather than in prose, so the KDoc
  cannot go stale again.
- **Suspended between pages** — suspend the cell mid-walk; the next page answers
  with `provenance = LIVE_SUSPENDED` and an unchanged frontier.
- **Drained host** — `provenance = CHECKPOINT`, drain-time frontier, and no
  scheduler task on any cell thread.
- **Abandoned read** — `cancel(false)` before the page task runs; assert the
  cell was never entered (extend the T18 pattern at `ManagedHost.kt:1250-1256`).
- **Ownership-bearing state** — a cell holding `Owned` values; assert pages carry
  descriptors, `exclusivesElided > 0`, `Owned.take` was never called, and no
  discharge or dead-letter accounting moved.
- **Cursor invalidated mid-read** — remove the key a cursor names, then resume;
  assert the walk continues from the next key rather than restarting or
  throwing.

### 6.3 Conformance: scenarios are owed, and cannot be authored today

**Answer: yes, scenarios are owed — and authoring them requires a concord
schema-change ticket first.** Nothing under `concord/**` was touched by this
ticket, including `DISPUTES.md`.

Proposed requirement ids (all verified free — `[21-PULL-01]` at
`doc/spec/20-dataflow-semantics/21-propagation.md:54` and `[21-CATCHUP-02]` at
`:59` are the only members of their families, and `21-CATCHUP-01` does not
exist):

- **`[21-PULL-02]`**, in `21-propagation.md` §Pull: *WHEN an instrument reads a
  cell's state under a cursor and limit, the framework SHALL answer without
  emitting, without linking, and without advancing any wave counter, delivered
  watermark or completeness set.*
- **`[21-PULL-03]`**, same section: *WHEN a bounded read is walked to
  completion and every page carries an equal frontier stamp, the union of its
  pages SHALL equal the cell's state at that frontier.*
- **`[24-BOUND-01]`**, in `24-data-cells.md` (which already owns the per-family
  catch-up and shard obligations at `:97-105`, `:374-385`, `:677-691`): *WHEN a
  data cell serves a bounded read, each page SHALL contain whole entries, no
  entry twice within a walk, and the cell's tag frontier at page time.*

Scenario shapes, mirroring `concord/corpus/21-propagation/21-PULL-01.yaml`
(`id`/`title`/`covers`/`profile: core`/`kind: example`, a `narrative` triple, a
two-cell `graph`, a `script` ending in `quiesce`, and a check block closing with
`no-dead-letters`):

- `21-PULL-02` — a set source at quiescence, a bounded read walked to
  completion, checks: the source's wave plane is unchanged and there are no
  dead letters.
- `21-PULL-03` — same graph, checks: the union of pages equals the golden view.
- `24-BOUND-01` — a generative variant sweeping `limit` against a source of N
  entries, checking union-equals-batch.

**Why they cannot be authored now.** `concord/schema/scenario.md` defines a
closed step-verb set (`apply`, `quiesce`, `connect`, `disconnect`, `snapshot`,
`restore`, `despawn`) and a closed check vocabulary (`:173-188`) — there is no
`read-state` step and no check that can express "no wave counter moved" or "the
union of pages equals the view". Growing either is "a deliberate schema-change
ticket between waves — not a corpus-authoring convenience"
(`concord/schema/scenario.md:6-9`). So the conformance work is: one schema-change
ticket adding a `{type: read-state, on: …, limit: …}` step plus
`{type: wave-plane-unchanged, cell: …}` and `{type: pages-equal-view, view: …}`
checks, then the three scenarios.

**Nothing belongs in `DISPUTES.md`.** That ledger is for requirements that
*exist* and cannot be checked honestly (`concord/corpus/DISPUTES.md:3-7`). These
requirements do not exist yet; filing them would be filing a dispute against
unwritten spec text.

## 7. Recommendation and open questions

### Go / no-go

**Go — conditionally, and split in two.** Ticket the *local* bounded read now:
`BoundedStateful` + `ManagedHost.readState` + the ~15 data-cell implementations
+ the tests in §6.2 + rewiring the inspector's three consumers (`DataSearch`,
the detail-panel state view, browse-everything chips). Size class **M**: the
kernel surface is one small file and one accessor modelled line-for-line on a
landed one (`ManagedHost.kt:1201-1263`), the risk is concentrated in per-cell
enumeration correctness, and nothing existing changes behaviour because the
interface is opt-in. **No-go on the remote arm** until the read-side disclosure
question below is answered — a bounded read crossing a membrane today would
bypass the only disclosure seam the repository has (§2, §3.7), which is a
security regression wearing a feature's clothes. And **before either**, the
zero-cost item: correct the three documents that record a mechanism which does
not exist (`CatchUp.kt:26-32`, `DataSearch.kt:38-45`,
`../97-inspector-plan/90-progress-log.md:1124-1128`), because that sentence is
currently being cited as a design constraint by new work.

### Proposed entries for `doc/spec/90-roadmap/95-research-plan.md`

That file is owned by itself; these are proposals for the replan session to
place, not edits.

1. **Disclosure for non-emitting reads.** 93 I-28's seam 3 filters *emissions*
   (`FanOutlet.kt:105-117`). A wave-neutral read has no emission and therefore no
   filter — true already of shipped `snapshotOf`. What is the disclosure contract
   for a read seam, and does "filtered, not forked" have a read-side twin?
   Blocks remote bounded reads and any exposure of `readState` outside a
   loopback-bound instrument.
2. **Ownership in `Stateful.snapshot()`.** `23-ownership.md` has no rule for a
   fold whose state contains `Owned`/`Leased` values, yet drain, migration and
   checkpointing all serialize that state (`33-mobility.md:100-102`). G-46
   (`23-ownership.md:220`) covers only the crash-loss half. §3.3 gives the
   bounded read a stricter contract; the older seam's contract is undefined.
3. **Cursor semantics across a scatter-gather boundary.** A partitioned pull is
   per-shard-consistent and cross-shard-arbitrary (`42-replication.md:401`), and
   the answering instance may change between pages. Is a cursor over a
   partitioned logical id one token or a vector of per-instance tokens, and does
   `RetainedFrontiers`' per-instance discipline (`:413-423`) extend to it?
4. **The `Effectful` processed frontier and baselines.** `WaveFrontier` and
   `absorbAck` exempt baselines; `ManagedHost.kt:770-800` does not. Should a
   baseline-stamped arrival advance a durable suppression frontier at all? This
   is the residual blocker for PN-2's push/pull catch-up unification once the
   `waveState()` claim is corrected.

### Stated plainly: what could not be determined from the code

- **Whether any *demo* or downstream consumer reads `waveState()` for a purpose
  the counter consumption would break.** The repository-wide grep finds only
  `Evolution.kt:198` in production and tests elsewhere, but three inspector
  tests (`InspectorFlowStreamTest`, `InspectorWaveHealthTest`,
  `InspectorFlowTest`) use `waveState().highWater` as a *synchronisation
  predicate*. They would not break, but they show the accessor is treated as
  "how many waves has this outlet emitted", which the pull reply makes untrue.
  Whether V3-BE's shipped wave-health heuristic depends on that reading was not
  traced.
- **The real magnitude of the copy cost.** No benchmark exists. `MAX_CELLS = 50`
  and `BUDGET_MS = 2000` were chosen by ticket, not measured
  (`DataSearch.kt:344-348` cites "Ticket:" for both). The claim that paging is
  cheaper than copying is structurally sound but unquantified, and the replan
  should ask for one measurement on a 10⁵-row `SetCell` before sizing the work.
- **Whether every state family has a stable enumeration order.** Verified for
  the map/set-backed cells; not verified for the operator cells whose state is a
  composite of two or three sub-snapshots (`JoinSetCell.kt:109-110`,
  `IntersectSetCell.kt:82-83`, `QuorumSetCell.kt:116`), where a cursor must
  order across sub-states as well as within them.
