# BB-TPCC — TPC-C bridge document

> **Status**: BB-lane analysis artifact (non-normative). Decision: _pending (T6)_
> **Sources**: `computenet-milestone-plan.md` §1 (BB-TPCC row), §3 (TPC-C row), §6; feature computenet-1ys
> **Implementation**: none — this document proposes nothing that it builds

## 1. Scope

This is a BB-lane analysis artifact. It is non-normative, and it proposes
nothing that it builds: it changes neither ComputeNet's model nor its runtime.

It answers “can ComputeNet do OLTP?” by forcing §7 to choose a named candidate
gap, a research-gated entry, an out-of-scope-by-design decision, or an
explicitly bounded hybrid of those outcomes. The pending status above records
that this first assembly step has not made that decision for the later analysis.

Every claim about ComputeNet's present model cites a full requirement id, an
exact spec section, or a repository-root-relative path. The scenarios in §4
are analysis scenarios, not executable tests; they are a way to expose the
first missing guarantee, not a substitute for a conformance corpus.

## 2. What TPC-C measures

_Pending: owned by task T2._

## 3. What ComputeNet's model guarantees today

This inventory was re-checked at
`f6fc66bf9688559a7d9d43b6a0b1b1d486c383a9`. It distinguishes a guarantee
about how already-emitted changes may be observed from a guarantee that a set
of changes executes atomically. ComputeNet has several of the former and no
general transaction-abort primitive of the latter kind.

### 3.1 What the model gives

**Per-source waves, not transactions.** An external origination mints a
`Timestamp(sourceId, counter)`, while reactive emission carries that timestamp
and rewrites the immediate `sourcePort`. A source is one outlet during one
emission epoch of one instance, so a logical cell with several instances is
several physical sources. A wave therefore identifies propagation from one
origination; it is not a transaction id (`22-consistency.md` §MessageContext
(normative, as implemented); `22-consistency.md` §Source identity: emission
epochs (decided in 93 I-14; implemented)).

**Glitch-freedom is opt-in and local to a cell.** A declaring cell buffers a
partially delivered wave so that it does not expose a mixture of pre-wave and
post-wave inputs [22-GF-01]. The guarantee composes across a chain of declaring
cells [22-GF-02], and the completeness condition is required to be identical
when a frontier edge crosses a host boundary [22-GF-03]. Non-declaring cells
remain eager (`22-consistency.md` §Local glitch-freedom (opt-in);
`22-consistency.md` §Bridged frontier (location transparency)).

**One input delta can be atomic as an operator emission.** `GroupByCell` emits
all groups touched by one input delta as one `MapDelta` under that input's wave
id [24-OP-GROUPBY-03]. That is per-delta operator atomicity; it does not combine
several independent writes into one commit (`24-data-cells.md` §Grouped
aggregation (M11.3)).

**Independent sources are not aligned into a global instant.** A cross-source
join is promised convergence, not simultaneity, unless an explicit coordinator
cell is inserted [22-SRC-01]. A silent independent source is likewise forbidden
from indefinitely blocking another source at quiescence [22-LIVE-01]. Together
these rules deliberately refuse an implicit transaction boundary across roots
(`22-consistency.md` §Topology versioning; `22-consistency.md` §Completeness
over silent or stuck edges (decided in 93 I-18; implemented — in-process
CP-A3/CP-A4, bridged CP-A2)).

**Read-side alignment exists.** A composite observation may emit only a result
that is correct for some per-source vector frontier of the replica's inputs
[22-OBS-01], and its named views must all settle through the shared frontier
before assembly [22-OBS-02]. This is internal consistency without a scalar
global time or a timestamp oracle (`22-consistency.md` §The observation
frontier).

The balanced-transfer benchmark is specifically an **observation** guarantee.
It drives paired debit and credit changes in one wave through joins and grouped
views and checks that every observed output remains balanced. It establishes
that an aligned observer need not see a partly propagated transfer; it does not
establish that the debit and credit were atomically executed, nor that either
could be aborted before observation (`22-consistency.md` §Acceptance benchmark:
the balanced-transfer suite; [22-OBS-01]; [22-OBS-02]). This distinction is the
spine of the bridge analysis.

**Mergeable replication is consensus-free CRDT gossip.** Replication is opt-in,
and the fixed principle is “no global consensus”; replicas converge by declared
merge semantics [42-REPL-04], late joiners receive state-as-delta catch-up
[42-REPL-05], and orderly departure does not turn a frozen departed stream into
a live divergence [42-REPL-06]. Membership is an eventually consistent local
fold of announcements, with no view number, quorum, or barrier
(`42-replication.md` §Principles (fixed); `42-replication.md` §Design as
implemented (G-7 resolved for the mergeable class, M7 + session delta 4);
`93-feature-interactions.md` §I-3 — Replication skeleton: membership,
location-set resolution, gossip topology, eviction (G-7)).

Single-writer replication is a different, one-directional primary-backup path:
one leader applies writes and emits the ordered stream, while followers forward
commands and apply stamped deltas. The implementation folds `LeaderMark`s,
fences stale epochs, supports peer catch-up, defaults to manual designation,
and offers an opt-in observation-counted epoch claim; it still introduces no
vote or write quorum
(`kernel/src/main/kotlin/civictech/cell/replication/SingleWriterReplication.kt`;
`kernel/src/main/kotlin/civictech/cell/replication/LeaderElection.kt`). The
model explicitly accepts the asynchronous loss window for writes not yet
shipped to a follower; ack-from-k is not adopted (`42-replication.md`
§Single-writer replication (decided in 93 I-25, not built)). The section title
and implementation status in that spec citation are stale; §3.4 records the
correction.

**Partitioning is disjoint interest.** A partial-interest instance must hold
exactly the admitted subset [42-INT-01]. The partitioned-cell model gives the
composite one external identity [24-PART-01], routes a keyed command to exactly
one organelle [24-PART-02], and treats disjoint ranges as the merge-safety proof
for conflict-free union [24-PART-03]. Repartitioning buffers the moving range,
replays it to the new owner, atomically flips the routing table, bumps its
epoch, and replays parked commands [24-PART-04]; late-join state is the union of
per-organelle catch-ups [24-PART-05] (`24-data-cells.md` §Partitioned state;
`42-replication.md` §Interest-scoped instance sets: one mesh, replication and
partitioning as two settings).

**A cross-shard read has no joint cut.** Scatter-gather pull obtains a coherent
slice at each shard's own frontier, then unions those slices. The legs are
independent and may represent different moments: the contract is
per-shard-consistent and cross-shard-arbitrary (`42-replication.md`
§Scatter-gather pull over an instance set (PN-5)).

**Ownership can hold traffic without preparing a transaction.** `Owned` and
`Leased` links are single-consumer. If a bounded intake refuses an exclusive
enqueue, ownership remains with the sender's link; the sender parks the
invocation and the receiver's `Owned.take()` fires once at replay. Drop is
forbidden: the alternative is the visible dead-letter boundary
(`23-ownership.md` §The SPSC link rule (normative)). SUSPEND similarly holds
each exclusive invocation once in the one Buffering primitive and replays it
once, while dead-letter capture move-by-serializes `Owned` and releases
`Leased` (`23-ownership.md` §Recovery and dead letters (93 I-22 R6–R8;
implemented)). These are no-loss and ownership-accounting rules, not tentative
writes or prepare votes.

**Bounded reads do not isolate a walk.** A bounded read emits nothing, links
nothing, and advances no wave or watermark [21-PULL-02]. A completed paged walk
can be checked against equal frontier stamps [21-PULL-03], but the walk is not
a snapshot: concurrent mutation can produce a “smeared” union, and the caller
must detect and reconcile the changed frontier (`21-propagation.md` §Pull).

### 3.2 What the model does not give: no abort

The central finding is negative: `doc/spec/20-dataflow-semantics/` and
`doc/spec/40-distribution/` define no operation that aborts or rolls back an
already-emitted data delta. Effective-only emission means the emitted delta
describes an actual state change (`21-propagation.md` §Incremental vs complete).
The model can delay, account for, supersede, or later counteract data; none of
those makes an already-observed change never have happened.

The admissible dispositions of an in-flight delta are:

- **buffer** — a glitch-free cell holds a partial wave until its local
  completeness condition is met [22-GF-01];
- **park** — an exclusive payload refused by saturation remains owned by the
  sender's link and is replayed later (`23-ownership.md` §The SPSC link rule
  (normative));
- **absorb-ack** — an operator that has no effective output advances the
  downstream watermark with `Progress` rather than inventing an output
  [22-LIVE-01] (`22-consistency.md` §Completeness over silent or stuck edges
  (decided in 93 I-18; implemented — in-process CP-A3/CP-A4, bridged CP-A2));
- **dead-letter** — an undeliverable exclusive crosses a visible boundary that
  freezes or redacts it while discharging ownership (`23-ownership.md`
  §Recovery and dead letters (93 I-22 R6–R8; implemented));
- **degrade** — a recoverable stalled edge may be excluded under the explicit
  DEGRADE policy, with the shrunken condition disclosed [22-LIVE-01]
  (`22-consistency.md` §Completeness over silent or stuck edges (decided in
  93 I-18; implemented — in-process CP-A3/CP-A4, bridged CP-A2)); or
- **retract-as-data** — a later remove retracts the tags it observed
  [24-SET-03], which can restore a value-level condition but cannot erase an
  earlier observation (`24-data-cells.md` §Established pattern (normative
  template)).

Retract-as-data is therefore not abort. The operator model explicitly permits
an observable transient overlap in an ungated outer join
[24-OP-OUTERJOIN-02], while an antijoin's same-wave sealing is opt-in and the
default remains ungated [24-OP-SEMIJOIN-04]. A compensating delta may repair
the final value, but it does not restore never-having-been-observed
(`24-data-cells.md` §Required next steps in the family).

The exhaustive literal search used for the negative claim, including the
repository-required command wrapper, was:

```text
rtk grep -rniE 'abort|rollback|undo' doc/spec/20-dataflow-semantics doc/spec/40-distribution
```

It produced the following nine hits; the search is substring-based, so the
`undotted` false positive is retained and classified rather than hidden.

| hit | why it is not undo of an emitted delta |
|---|---|
| `doc/spec/20-dataflow-semantics/24-data-cells.md:1075` | A RESTART sends a `ReBaseline` and reconciles forward by supersession [24-TAG-02]; the sentence explicitly rejects bare local rollback. |
| `doc/spec/20-dataflow-semantics/24-data-cells.md:1097` | “Bare rollback” names the superseded reading of older M3.5 prose; the current rule is the same drop-then-merge-then-fence `ReBaseline` [24-TAG-02]. |
| `doc/spec/20-dataflow-semantics/24-data-cells.md:1125` | The “rollback checkpoint” is an export snapshot retained for a cell-version promotion handoff, not an undo verb for data emitted by the cell (`53-evolution.md` §The promotion swap (decided in 93 I-11, implemented)). |
| `doc/spec/20-dataflow-semantics/24-data-cells.md:1208` | `undotted repair entry` merely contains the character sequence `undo`; it is a lexical false positive in tag-compaction prose (`24-data-cells.md` §Tag continuity across epochs, restart, and swap). |
| `doc/spec/20-dataflow-semantics/24-data-cells.md:1248` | `rollback-after-retire` is a still-open promotion/version-retention concern, not an emitted-delta abort (`24-data-cells.md` §Tag continuity across epochs, restart, and swap). |
| `doc/spec/20-dataflow-semantics/24-data-cells.md:1255` | `rollback-by-journal-reversal` is the proposed mechanism for restoring a retired implementation during promotion, not for making downstream data effects unobserved (`53-evolution.md` §The promotion swap (decided in 93 I-11, implemented)). |
| `doc/spec/20-dataflow-semantics/21-propagation.md:283` | RESTART restores state and emits a `ReBaseline`; the text says it is never a bare local rollback [21-REBASE-01]. |
| `doc/spec/20-dataflow-semantics/21-propagation.md:304` | “Bare rollback” again names the obsolete interpretation that the implemented re-baseline rule replaced [21-REBASE-01]. |
| `doc/spec/40-distribution/43-security.md:250` | “Abort policy” belongs to an unbuilt canary/rolling-promotion orchestration proposal, not to the data plane (`43-security.md` §`BoundaryPolicy`: three seams, one per dispatch class (decided in 93 I-28, unimplemented)). |

The closest recovery mechanism, [21-REBASE-01], converges downstream to a
restored state through a new baseline. That is forward reconciliation after a
failure, not atomic cancellation of a set of writes that may already have been
observed (`21-propagation.md` §Pull).

### 3.3 The three unbuilt placeholders

**Explicit coordinator cell.** Rule S5 says that completeness quantifies over
physical outlet-epoch sources; a logical upstream with several instances gives
convergence rather than the diamond guarantee unless the consumer subscribes
to one instance or an explicit coordinator cell is inserted
(`22-consistency.md` §Source identity: emission epochs (decided in 93 I-14;
implemented)). The cross-source rule repeats the escape hatch [22-SRC-01]. It
falls short of transaction abort because it only names where source alignment
would have to become explicit: it specifies neither tentative participant
state, a durable commit decision, nor reversal of emitted effects.

**JoinBarrier under G-39.** The remaining G-39 proposal names an explicit
topology-serializing `JoinBarrier` cell so a changing source set can be ordered
and the diamond-over-replica escape hatch can be made concrete
(`91-gap-analysis.md` §Gaps — semantics (20), G-39 row;
`22-consistency.md` §Topology versioning;
`41-location-transparency.md` §Wire layer (G-15 — resolved in M5; commitments
annotated below)). It falls short of transaction abort because its subject is
which edges count toward frontier completeness, not whether participant writes
are tentative or how a committed downstream delta could be undone.

**R3 compensating release.** R3 proposes that a starved coupling deadline
release its one held side to its port and emit `CouplingAborted` on the metadata
plane, preserving no-loss while degrading explicitly (`95-research-plan.md`
§R3 — Coupling liveness (G-53)). It falls short because it releases one held
side of one coupling; it does not roll back state already applied by another
cell or retract effects already observed downstream.

### 3.4 Corrections to the feature's grounding

| feature claim | what the spec/code says at `f6fc66bf9688559a7d9d43b6a0b1b1d486c383a9` | consequence |
|---|---|---|
| Rule S5 and the explicit-coordinator escape hatch are under topology versioning. | Rule S5 is under `22-consistency.md` §Source identity: emission epochs (decided in 93 I-14; implemented); §Topology versioning begins later and repeats the cross-source escape hatch through [22-SRC-01]. | Cite Rule S5 to source identity, not topology versioning. |
| `bench/` does not exist and `doc/bench/findings.md` may need to be created. | `bench/` is a module and `doc/bench/findings.md` already exists; `doc/ARCHITECTURE.md` §1. Module graph and §7. Documentation map describe them. | The bridge is a new document in an existing lane directory; the later findings task must append to the existing log. |
| `14-invocations.md` says “transactions are not the framework's core.” | The actual sentence is “transactions are not the framework's core (dataflow is)” in `14-invocations.md` §The core move (ADR 3). | Quote the full sentence and its contrast with dataflow. |
| Single-writer replication is decided but unbuilt, and automatic election is wholly absent. | `42-replication.md` still labels §Single-writer replication “not built,” but `kernel/src/main/kotlin/civictech/cell/replication/SingleWriterReplication.kt` implements the leader/follower engine and `kernel/src/main/kotlin/civictech/cell/replication/LeaderElection.kt` implements default-manual plus opt-in observation-counted epoch claims; `kernel/src/test/kotlin/civictech/cell/replication/SingleWriterReplicationTest.kt` and `kernel/src/test/kotlin/civictech/cell/replication/LeaderElectionTest.kt` exercise them. | Inventory the implementation, while retaining its no-quorum, no-ack, asynchronous-loss boundary; do not repeat the stale “unbuilt” status as fact. |
| Partitioned state is only decided design with no code. | `24-data-cells.md` §Partitioned state itself flags that status as stale, and the current tree contains `kernel/src/main/kotlin/civictech/cell/partition/PartitionedCell.kt`, `kernel/src/main/kotlin/civictech/cell/partition/PartitionedShardSet.kt`, and `kernel/src/main/kotlin/civictech/cell/partition/ShardCell.kt`. | Treat [24-PART-01], [24-PART-02], [24-PART-03], [24-PART-04], and [24-PART-05] as the model requirements, and do not infer non-existence from the stale status paragraph. |
| G-39 still lacks a wire form for edge events, and bridged `Progress`/handshake parity remain unimplemented. | `91-gap-analysis.md` §Gaps — semantics (20), G-39 row, says the EdgeEvent wire sliver is built; `41-location-transparency.md` §Wire layer records C-13 as resolved and bridged `Progress` as implemented. Older sentences in `21-propagation.md` §Pull and `22-consistency.md` §Bridged frontier (location transparency) still describe those parts as absent, even though the latter file's status and completeness section say CP-A2 landed. | Keep only the floor/retention/source-set propagation/JoinBarrier part as the G-39 residual; do not weaken [22-GF-03] with the stale implementation labels. |

No further drift was found in the §3.1–§3.3 grounding owned here at
`f6fc66bf9688559a7d9d43b6a0b1b1d486c383a9`.

## 4. The five transactions, walked

_Pending: owned by task T3._

## 5. The gap, stated precisely

_Pending: owned by task T5._

## 6. Candidate evaluation

_Pending: owned by task T4._

## 7. Decision

_Pending: owned by task T6._

## 8. What this document does not do

_Pending: owned by task T6._
