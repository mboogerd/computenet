# BB-TPCC — TPC-C bridge document

> **Status**: BB-lane analysis artifact (non-normative). Decision: hybrid — (B) general, (A) bounded host-local; see §7
> **Sources**: `computenet-milestone-plan.md` §1 (BB-TPCC row), §3 (TPC-C row), §6; feature computenet-1ys
> **Implementation**: none — this document proposes nothing that it builds

## 1. Scope

This is a BB-lane analysis artifact. It is non-normative, and it proposes
nothing that it builds: it changes neither ComputeNet's model nor its runtime.

It answers “can ComputeNet do OLTP?” through the bounded hybrid decided in §7:
the general cross-host primitive is out of scope by design, while a strictly
host-local transaction scope remains a candidate gap.

Every claim about ComputeNet's present model cites a full requirement id, an
exact spec section, or a repository-root-relative path. The scenarios in §4
are analysis scenarios, not executable tests; they are a way to expose the
first missing guarantee, not a substitute for a conformance corpus.

## 2. What TPC-C measures

TPC-C is a mixed OLTP workload over nine related entities. The schema summary
below records the shape that matters for this bridge, not every field in the
standard (TPC-C §§1.2–1.3).

### 2.1 Schema

| Entity | Key | What it holds | Transactions that touch it |
|---|---|---|---|
| `WAREHOUSE` | `W_ID` | Warehouse identity, address, tax, and year-to-date sales total | New-Order (R), Payment (R/W) |
| `DISTRICT` | `(D_W_ID, D_ID)` | District identity, tax, year-to-date sales total, and next order id | New-Order (R/W), Payment (R/W), Stock-Level (R) |
| `CUSTOMER` | `(C_W_ID, C_D_ID, C_ID)` | Customer identity, credit, discount, balance, and payment/delivery counters | New-Order (R), Payment (R/W), Order-Status (R), Delivery (R/W) |
| `STOCK` | `(S_W_ID, S_I_ID)` | Per-warehouse inventory for an item, including quantity and stock counters/data | New-Order (R/W), Stock-Level (R) |
| `ORDER` | `(O_W_ID, O_D_ID, O_ID)` | Order header, customer, entry/carrier state, and line count | New-Order (W), Order-Status (R), Delivery (R/W) |
| `ORDER-LINE` | `(OL_W_ID, OL_D_ID, OL_O_ID, OL_NUMBER)` | An order's item, supplying warehouse, quantity, amount, and delivery state | New-Order (W), Order-Status (R), Delivery (R/W), Stock-Level (R) |
| `NEW-ORDER` | `(NO_W_ID, NO_D_ID, NO_O_ID)` | The outstanding-order marker used by Delivery | New-Order (W), Delivery (R/W) |
| `HISTORY` | No benchmark-declared primary key; append-only record | A payment event and its participating customer, district, and warehouse | Payment (W) |
| `ITEM` | `I_ID` | Item catalog identity, price, name, and data | New-Order (R) |

Here `R` means read and `W` means write. The same logical transaction can
therefore span several rows and several entities even when each row has a
straightforward local key.

### 2.2 The five transactions

The standard's minimum mix is 43% Payment, 4% Order-Status, 4% Delivery, and
4% Stock-Level. It leaves New-Order without a minimum because its measured rate
is the reported workload rate; the stated intent is approximately one Payment
per New-Order and one each of the other three transactions per ten New-Orders
(TPC-C §5.2.3).

#### New-Order

- **Reads**: the warehouse tax, district tax and next order id, customer
  discount/name/credit, each item row, and each supplying warehouse's stock
  row (TPC-C §2.4.2.2).
- **Writes**: the district's next order id, one `NEW-ORDER` row, one `ORDER`
  row, the `ORDER-LINE` rows, and each affected stock quantity/counter
  (TPC-C §2.4.2.2).
- **Mix share**: no minimum is specified; New-Order is the rate against which
  the workload is measured.
- **Declared special behaviour**: the order is one database transaction over
  a variable five-to-fifteen item set. A fixed 1% of New-Orders deliberately
  uses an invalid final item id, and the resulting error must roll back the
  update transaction (TPC-C §§2.4.1.3–2.4.1.4, 2.4.2.1–2.4.2.2).

**Isolation requirement**: Level 3 among New-Order, Payment, Order-Status, and Delivery, and Level 2 relative to an arbitrary transaction (TPC-C §3.4.1)

#### Payment

- **Reads**: warehouse, district, and customer records, with customer lookup
  allowed by number or last name (TPC-C §2.5.2.2).
- **Writes**: the warehouse and district year-to-date totals, the customer's
  balance and payment counters, and one `HISTORY` record (TPC-C §2.5.2.2).
- **Mix share**: 43% minimum of the workload mix.
- **Declared special behaviour**: a single database transaction; the customer
  may be remote from the warehouse where the payment is entered (TPC-C
  §§2.5.1.2, 2.5.1.5–2.5.1.6, 2.5.2.1–2.5.2.2).

**Isolation requirement**: Level 3 among New-Order, Payment, Order-Status, and Delivery, and Level 2 relative to an arbitrary transaction (TPC-C §3.4.1)

#### Order-Status

- **Reads**: the selected customer, that customer's most recent order, and all
  of its order lines, including the customer balance and order-line delivery
  state (TPC-C §2.6.2.2).
- **Writes**: none; this is a read-only transaction.
- **Mix share**: 4% minimum of the workload mix.
- **Declared special behaviour**: one database transaction, with customer
  selection by number or by last name, followed by the latest-order and
  order-line reads (TPC-C §§2.6.1.2, 2.6.2.1–2.6.2.2).

**Isolation requirement**: Level 3 among New-Order, Payment, Order-Status, and Delivery, and Level 2 relative to an arbitrary transaction (TPC-C §3.4.1)

#### Delivery

- **Reads**: the oldest outstanding order for each district, its order header
  and lines, and the associated customer (TPC-C §2.7.4.2).
- **Writes**: deletion of the `NEW-ORDER` marker, the order's carrier, every
  order-line delivery date, and the customer's balance and delivery count
  (TPC-C §2.7.4.2).
- **Mix share**: 4% minimum of the workload mix.
- **Declared special behaviour**: one business transaction processes one new
  order from each of ten districts. Each order is delivered in full within a
  read-write database transaction; the implementation may group the ten
  database transactions, and the business transaction is queued for deferred
  execution rather than completed interactively (TPC-C §§2.7, 2.7.2.1–2.7.2.2,
  2.7.4).

**Isolation requirement**: Level 3 among New-Order, Payment, Order-Status, and Delivery, and Level 2 relative to an arbitrary transaction (TPC-C §3.4.1)

#### Stock-Level

- **Reads**: the district's next order id, the order lines in its last twenty
  orders, and the distinct corresponding stock rows (TPC-C §2.8.2.2).
- **Writes**: none; this is a read-only count.
- **Mix share**: 4% minimum of the workload mix.
- **Declared special behaviour**: a heavy, low-frequency read-only transaction
  with relaxed consistency requirements; non-repeatable reads are explicitly
  allowed for it (TPC-C §§2.8, 2.8.2.1–2.8.2.3, 3.1.1).

**Isolation requirement**: Level 1 relative to the other transaction types and arbitrary transactions (TPC-C §3.4.1)

### 2.3 What the benchmark is really asserting

TPC-C asserts two properties that are easy to blur together. First, it exercises
multi-row read-modify-write under a declared abort path: New-Order reads the
inputs needed to construct an order, changes several related entities, and on
the deliberately invalid input must leave no partial effects (TPC-C
§§2.4.1.4, 2.4.2.2, 3.2.1–3.2.2). Second, its consistency conditions require
the cross-table relationships to remain true: warehouse and district totals,
district/order/new-order numbering, new-order contiguity, order/order-line
cardinality, carrier and delivery markers, and the warehouse, district, and
customer totals reconciled with `HISTORY` and `ORDER-LINE` amounts (TPC-C
§3.3). The rest of this document uses these two properties as its acceptance
lens: can the workload's related changes be all-or-nothing on an abort, and do
the stated relationships remain true while the five transaction shapes run?

### 2.4 Set aside: tpmC and the pricing metric

TPC-C's `tpmC` throughput metric and the benchmark's pricing metric are out of
scope for this bridge. This document reports no throughput, latency, cost, or
price/performance figure; it describes workload shape and correctness
obligations only. [BB-TPCC-17]

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

The exhaustive literal search used for the negative claim was:

```text
grep -rniE 'abort|rollback|undo' doc/spec/20-dataflow-semantics doc/spec/40-distribution
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

These are analysis scenarios, not executable tests; no concord scenario, JUnit
test, or corpus YAML is produced. [BB-TPCC-14]

### 4.1 Transaction by transaction

#### New-Order

New-Order needs one Level 3 read-modify-write across a district counter, an
order and its lines, and five to fifteen stock entries, plus the declared abort
path in §2. `GroupByCell` can make every group touched by one input delta one
operator emission [24-OP-GROUPBY-03], and a one-shard partition has a
conflict-free single owner [24-PART-03]. Those mechanisms do not turn the
native multi-cell shape into one commit, and §3.2 establishes that there is no
undo of an emitted delta. The specific missing guarantees are multi-cell
atomic visibility and abort without an observable partial effect.

#### Payment

Payment is Level 3 across warehouse, district, customer, and `HISTORY` state.
It is partially representable by either of the constructions established in
S3: one `GroupByCell` can encode all four logical changes in one multi-key
`MapDelta` [24-OP-GROUPBY-03], or one linked ingress can fan out synchronously
to several keyed cells on one host and an aligned observer can publish them at
one wave position [22-OBS-01] [22-OBS-02]. Neither construction supplies a
general commit over independently sourced cells or a cross-host fan-out;
[22-SRC-01] promises convergence rather than simultaneity there. The missing
guarantee is one atomic commit covering the three updates and the history
append.

#### Order-Status

An aligned composite is internally consistent for some per-source vector
frontier [22-OBS-01] and waits for every named view through that frontier
[22-OBS-02]. That is not the one serial transaction snapshot required by
Order-Status's Level 3 relation to New-Order, Payment, and Delivery in §2:
the vector need not denote one scalar moment. A bounded walk is weaker still;
it is explicitly a verifiable, potentially “smeared” read rather than a
snapshot [21-PULL-03]. The missing guarantee is a cross-source Level 3
snapshot/serialization point, although the observation frontier supplies a
useful, strictly weaker read-side alignment mechanism.

#### Delivery

Delivery is the best fit of the five transactions. Its deferred batch is
native dataflow work rather than an interactive request that must wait, and
TPC-C permits the ten districts to be separate database transactions. If
district is the partition key, each district has one disjoint owner under
[24-PART-03]; if the order, lines, and customer update are represented as one
district-scoped state transition, the per-district unit stays single-key. What
is not covered is the ordinary representation in which those records are
separate cells: the missing guarantee there is per-district multi-cell atomic
commit.

#### Stock-Level

Stock-Level's Level 1 requirement in §2 expressly admits non-repeatable reads.
A bounded read is wave-neutral [21-PULL-02] and may smear concurrent mutations
across pages [21-PULL-03]; that weaker behavior is within the stated Level 1
isolation rather than a hidden claim of snapshot isolation. A maintained
multi-view result can additionally use the per-source observation frontier
[22-OBS-01] [22-OBS-02]. Thus the isolation shape is covered, while callers
must not promote either mechanism into a Level 3 or single-global-moment claim.
[BB-TPCC-03]

### 4.2 Scenarios S1–S8

### S1 — New-Order happy path

**Given** a warehouse, district, and ten stock entries with current values.
**When** one New-Order input names all ten items.
**Then** the district counter and all stock quantities change once, and no
observer sees the order while a required stock change is outstanding.

`GroupByCell` emits all keys touched by one input delta as one `MapDelta`
[24-OP-GROUPBY-03]. The partitioned implementation first splits that delta by
shard, however, and forwards a separate output from each shard rather than
coalescing them. `PartitionedCell.route` calls those shards synchronously while
a linked ingress's `CurrentContext` frame is active, so their outputs share the
incoming timestamp; the earlier inference that several shards necessarily
mean several waves was wrong. That shared timestamp is still not one
coalesced operator emission, and no existing test pins the separate shard
outputs as one joint publication. A context-free call instead lets each shard
outlet originate independently.

There is a second, tested construction beyond one shard. On one host, one
linked ingress delta can synchronously fan out inside the same frame to
several keyed cells, and [22-OBS-01]/[22-OBS-02] aligned observation publishes
their changes at one wave position. The restriction is the whole construction:
one host, one linked ingress delta, synchronous same-frame fan-out, and an
aligned observer. It does not establish partitioned-shard publication,
cross-host fan-out, or visibility to unaligned observers; [22-SRC-01] still
promises convergence rather than simultaneity outside that construction, and
the scatter-gather contract has no joint cut (`42-replication.md`
§Scatter-gather pull over an instance set (PN-5)).

Files read for this finding:
`kernel/src/main/kotlin/civictech/cell/data/op/GroupByCell.kt`,
`kernel/src/main/kotlin/civictech/cell/data/op/MergeableGroupByCell.kt`,
`kernel/src/main/kotlin/civictech/cell/partition/Partitioned.kt`,
`kernel/src/main/kotlin/civictech/cell/partition/PartitionedCell.kt`,
`kernel/src/main/kotlin/civictech/cell/partition/PartitionedShardSet.kt`,
`kernel/src/main/kotlin/civictech/cell/partition/ShardCell.kt`,
`kernel/src/main/kotlin/civictech/cell/partition/RoutedCommand.kt`,
`kernel/src/main/kotlin/civictech/cell/port/FanOutlet.kt`,
`kernel/src/main/kotlin/civictech/cell/consistency/WaveFrontier.kt`,
`kernel/src/main/kotlin/civictech/cell/observe/AlignedObserve.kt`,
`kernel/src/test/kotlin/civictech/cell/app/KeyedFanOutOneWaveTest.kt`,
`kernel/src/test/kotlin/civictech/cell/data/PartitionedCellTest.kt`,
`kernel/src/test/kotlin/civictech/cell/partition/ShardCellBoundedReadTest.kt`,
and
`demo/exchange/src/test/kotlin/civictech/demo/exchange/ExchangeCompositionExitTest.kt`.
`KeyedFanOutOneWaveTest`'s `one ingress SetDelta fanned to a MapCell view and a
KeyedSetCell view lands at one wave position` test pins one host, synchronous
direct same-frame fan-out from one linked ingress delta to two keyed cells, and
the joint result read through `observeAligned`. It does not pin partitioned
shards, cross-host fan-out, or unaligned observers. `PartitionedCellTest`,
`ShardCellBoundedReadTest`, and `ExchangeCompositionExitTest` cover functional
shard equivalence, bounded-read/repartition behavior, and eventual
batch-equivalent composition respectively, not joint publication of the
separate outputs from several shards.

**Verdict**: PARTIAL — [24-OP-GROUPBY-03] or same-frame keyed fan-out, restricted to one host, one linked ingress delta, and [22-OBS-01]/[22-OBS-02] aligned observation

### S2 — New-Order 1% rollback

**Given** the same New-Order state.
**When** the final item id is invalid after earlier changes have been applied.
**Then** every change is absent and no downstream observer ever sees a partial
order.

Retract-as-data restores the value but does not restore
never-having-been-observed. The operator contract permits a transient outer-join
overlap [24-OP-OUTERJOIN-02], and even antijoin sealing is opt-in while the
default remains ungated [24-OP-SEMIJOIN-04]. Section 2.3 states the TPC-C
requirement as rollback leaving no partial effects, with TPC-C clauses rather
than an `unverified` tag. A transiently visible effect followed by compensation
therefore does not meet that requirement. The missing mechanism is the §3.2
absence: no undo of an emitted delta.

**Verdict**: MISSING — no abort that restores never-having-been-observed; §3.2

### S3 — Payment derived update

**Given** warehouse, district, and customer totals plus a history collection.
**When** one payment updates the three totals and appends its history row.
**Then** all four changes become visible together and record the same amount.

The source reading has the same result as S1. One `GroupByCell` input can emit
one multi-key `MapDelta` [24-OP-GROUPBY-03], but `PartitionedCell` splits an
input across shards and forwards the resulting shard emissions rather than
coalescing them. Its synchronous `route` calls do preserve a linked ingress's
timestamp across those emissions, but no test pins their joint publication;
[24-PART-03] proves disjoint union and context-preserving forwarding, not a
commit spanning the emissions. The tested broader case is a deliberate
one-host construction in which all four changes derive from one linked ingress
delta, fan out synchronously in the same frame to keyed cells, and are read
through an aligned observer. The ordinary multi-cell shape, cross-host fan-out,
and unaligned observation are not covered.

Files read for this finding:
`kernel/src/main/kotlin/civictech/cell/data/op/GroupByCell.kt`,
`kernel/src/main/kotlin/civictech/cell/data/op/MergeableGroupByCell.kt`,
`kernel/src/main/kotlin/civictech/cell/partition/Partitioned.kt`,
`kernel/src/main/kotlin/civictech/cell/partition/PartitionedCell.kt`,
`kernel/src/main/kotlin/civictech/cell/partition/PartitionedShardSet.kt`,
`kernel/src/main/kotlin/civictech/cell/partition/ShardCell.kt`,
`kernel/src/main/kotlin/civictech/cell/partition/RoutedCommand.kt`,
`kernel/src/main/kotlin/civictech/cell/port/FanOutlet.kt`,
`kernel/src/main/kotlin/civictech/cell/consistency/WaveFrontier.kt`,
`kernel/src/main/kotlin/civictech/cell/observe/AlignedObserve.kt`,
`kernel/src/test/kotlin/civictech/cell/app/KeyedFanOutOneWaveTest.kt`,
`kernel/src/test/kotlin/civictech/cell/data/PartitionedCellTest.kt`,
`kernel/src/test/kotlin/civictech/cell/partition/ShardCellBoundedReadTest.kt`,
and
`demo/exchange/src/test/kotlin/civictech/demo/exchange/ExchangeCompositionExitTest.kt`.
`KeyedFanOutOneWaveTest`'s `one ingress SetDelta fanned to a MapCell view and a
KeyedSetCell view lands at one wave position` test pins one host, synchronous
direct same-frame fan-out from one linked ingress delta to two keyed cells, and
the joint result read through `observeAligned`. It does not pin partitioned
shards, cross-host fan-out, or unaligned observers. The partitioned tests and
exchange exit test pin functional shard behavior, bounded reads, and eventual
batch-equivalent composition, not joint publication of the separate outputs
from several shards.

**Verdict**: PARTIAL — [24-OP-GROUPBY-03] or same-frame keyed fan-out, restricted to one host, one linked ingress delta, and [22-OBS-01]/[22-OBS-02] aligned observation

### S4 — Order-Status coherent read

**Given** a customer and the lines of that customer's latest order.
**When** Order-Status reads both views.
**Then** the lines belong to that order and reflect one coherent read.

[22-OBS-01] and [22-OBS-02] can produce an internally consistent composite for
some per-source vector frontier. They do not establish a scalar transaction
snapshot, and [21-PULL-03] says a paged walk may be smeared. Because §2 assigns
Order-Status Level 3 relative to New-Order, Payment, and Delivery, “some
per-source frontier” is strictly weaker than its required serialization point.
The mechanism is useful read-side alignment, but it does not by itself meet the
declared isolation level.

**Verdict**: PARTIAL — [22-OBS-01] and [22-OBS-02] align named views, but no cross-source Level 3 snapshot exists

### S5 — Delivery deferred batch

**Given** ten districts, each with an oldest undelivered order.
**When** the deferred Delivery batch runs.
**Then** each district's order and customer state change atomically, while the
ten-district batch may finish asynchronously.

This is the benchmark's best fit: deferred batch execution is native dataflow,
and the standard permits one database transaction per district. With district
as the partition key, each district is a single-key scope with one disjoint
owner [24-PART-03]. The remaining restriction is representational: the order,
lines, and customer change must be one district-scoped state transition on one
shard. Separate cells still lack per-district atomic commit.

**Verdict**: PARTIAL — best fit via deferred dataflow and [24-PART-03], restricted to one district key on one shard

### S6 — Stock-Level relaxed read

**Given** a district's last twenty orders and the corresponding stock entries.
**When** Stock-Level counts the entries below its threshold.
**Then** it returns a count under the transaction's declared Level 1 isolation.

The bounded-read contract is non-invasive [21-PULL-02] and openly permits a
smeared multi-page walk [21-PULL-03]. That does not meet snapshot isolation,
but §2 states Level 1 and explicitly allows non-repeatable reads for
Stock-Level. A maintained result may additionally align its named views at a
per-source vector frontier [22-OBS-01] [22-OBS-02]. For this transaction's
actual isolation level, the weaker read shape is sufficient; it is not evidence
for any stronger transaction.

**Verdict**: COVERED — [21-PULL-02] bounded reads and [22-OBS-01] vector-frontier observation meet Stock-Level's stated Level 1 shape

### S7 — Concurrent New-Orders on one district

**Given** two New-Orders arriving concurrently for the same district.
**When** both allocate the next district order id and update their stock rows.
**Then** the counter yields distinct ids and each allocation remains joined to
its own stock changes.

The counter itself has a real single-writer serialization point. A partition
routes a key to its one disjoint owner [24-PART-03], while
`42-replication.md` §Single-writer replication makes the leader the one
applying instance and its queue the serialization point (implemented in
`kernel/src/main/kotlin/civictech/cell/replication/SingleWriterReplication.kt`,
as corrected in §3.4). That covers distinct counter values. It does not define
how two scopes whose participant sets overlap interleave the counter with their
stock changes. That missing joint guarantee is [BB-TPCC-33].

**Verdict**: MISSING — [BB-TPCC-33] overlapping-participant serialization for the counter-plus-stock case

### S8 — Crash during New-Order

**Given** a New-Order whose district or stock changes have partly applied.
**When** its host crashes and recovers from journals.
**Then** the whole transaction is either present or absent.

[24-DUR-01] selects a journal per cell (or makes that cell volatile).
[24-DUR-02] rebuilds the graph, restores the latest cell snapshots, and replays
each frame tail. [24-DUR-03] deliberately scopes each journal to its own cells.
[24-DUR-04] preserves identities so replay neither resurrects removals nor
double-counts, and [24-DUR-05] suppresses an already-processed effect at that
inlet's frontier. These are strong per-cell recovery rules. None records one
commit decision spanning the district, stock, order, and order-line cells, so
recovery may faithfully restore a partial transaction. The missing recovery
contract is [BB-TPCC-37].

**Verdict**: MISSING — [BB-TPCC-37] durable cross-cell commit decision and terminating recovery

## 5. The gap, stated precisely

The hypothetical primitive is a **transaction scope** with a **coordinator**
and explicit **participant cells**. Those names deliberately imply neither the
promotion swap nor classical distributed commit. The requirements below are a
reading rule for what would have to be true inside ComputeNet's model; they
specify observable guarantees, not a coordination protocol, and do not decide
whether the primitive should exist.

### 5.1 Structure and identity

- [BB-TPCC-20] WHEN a transaction scope is formed, the runtime SHALL assign it
  an identity distinct from every wave `Timestamp`, because a wave identifies
  one outlet during one emission epoch while a transaction may span independent
  sources (`22-consistency.md` §Source identity: emission epochs).
- [BB-TPCC-21] WHEN a transaction scope is formed, it SHALL name its participant
  cells explicitly and SHALL NOT acquire participants implicitly through
  propagation reach (`02-design-principles.md` §P3 — Explicit topology).
- [BB-TPCC-22] WHERE no transaction scope is declared on a link or cell, the
  runtime SHALL add no transaction metadata, lookup, buffering, or decision
  check to that path, preserving P2 and the opt-in discipline of [22-GF-01]
  (`02-design-principles.md` §P2 — Near-zero-cost fast path).

### 5.2 Atomicity across cells

- [BB-TPCC-23] WHILE a transaction scope is open, the runtime SHALL keep every
  participant's transaction-local state tentative and invisible to every
  non-participant reader; ordinary state cannot supply this guarantee because
  its emitted changes are already effective (`21-propagation.md` §Push
  (default, implemented)).
- [BB-TPCC-24] WHEN a transaction scope commits, the runtime SHALL make every
  participant's state change observable such that no observer can see a proper
  non-empty subset of those changes. This is the multi-cell analogue of
  [22-GF-01] and is stronger than the convergence-without-simultaneity promised
  by [22-SRC-01].
- [BB-TPCC-25] WHEN a transaction scope commits, every participant SHALL emit
  its resulting deltas downstream. **OPEN DESIGN POINT**: whether those
  emissions carry the transaction identity, one shared wave identity, or each
  participant outlet's own wave identity; waves currently belong to outlet
  emission epochs (`22-consistency.md` §Source identity: emission epochs).
- [BB-TPCC-26] IF a glitch-free downstream cell subscribes to more than one
  participant in the same transaction scope, THEN the runtime SHALL deliver
  that scope's contributions as one frontier-complete unit or SHALL refuse the
  composition at link time; today's frontier is complete per source and does
  not align independent sources ([22-GF-01], [22-SRC-01]).

### 5.3 Abort

- [BB-TPCC-27] WHEN a transaction scope aborts, the runtime SHALL leave every
  participant's state unchanged and SHALL make no participant delta observable
  downstream. The first missing mechanism is the one named in §6 Q1's
  `**First step with no mechanism**`: staging a participant change as tentative,
  durable, and invisible before ordinary effective-only emission
  (`21-propagation.md` §Push (default, implemented)).
- [BB-TPCC-28] WHEN a transaction scope aborts, the runtime SHALL NOT use a
  downstream retraction as its undo. Retraction is a later observable change,
  and [24-OP-OUTERJOIN-02] expressly permits transient effects; §4 S2 therefore
  shows that compensation cannot restore never-having-been-observed.
- [BB-TPCC-29] WHEN a transaction scope aborts, the runtime SHALL release every
  `Leased` payload it holds and SHALL discharge every `Owned` payload exactly
  once, never silently dropping an exclusive obligation (`23-ownership.md`
  §Taps: consumers vs observers and §Recovery and dead letters).
- [BB-TPCC-30] WHILE an `Owned` payload remains live in an open transaction
  scope, the runtime SHALL defer its participant's `take()` until the durable
  scope-wide decision is commit. IF `take()` occurs after that decision,
  THEN the scope SHALL be **unabortable-once-consumed**; an abort before
  `take()` SHALL instead discharge the still-live payload exactly once under
  [BB-TPCC-29]. Within this non-normative sketch, that is the sole disposition:
  `23-ownership.md` §Recovery and dead letters makes consumed, discharged, and
  dead-lettered mutually exclusive fates, R6 makes consumption terminal across
  RESTART, and R8 can serialize only a live `Owned` wrapper at capture. The
  hypothetical scope therefore forbids consumed-then-aborted rather than
  assigning one item a second fate.
- [BB-TPCC-31] IF a transaction scope exceeds its configured open-time bound,
  THEN the runtime SHALL abort it and publish a visible metadata-plane notice
  rather than stall its participants indefinitely, following the explicit
  no-loss/degrade shape of `95-research-plan.md` §R3 — Coupling liveness
  (G-53), including its `CouplingAborted` notice.

### 5.4 Interaction with waves, glitch-freedom, and replication

- [BB-TPCC-32] WHILE a transaction scope is open, waves from independent
  sources that touch no participant SHALL continue to flow; a graph-wide
  propagation lock is rejected because it serializes the graph
  (`22-consistency.md` §Local glitch-freedom (opt-in);
  `02-design-principles.md` §P4 — Local, compositional consistency).
- [BB-TPCC-33] IF two open transaction scopes name overlapping participant
  sets, THEN the runtime SHALL define their interleaving. **OPEN DESIGN POINT**:
  serialize them at the shared participant, refuse the second scope at
  formation, or admit both under a declared merge rule. Section 4 S7 shows why
  the existing single-writer counter serialization point does not order each
  counter change jointly with its scope's stock changes
  (`42-replication.md` §Single-writer replication (decided in 93 I-25, not
  built)).
- [BB-TPCC-34] WHERE a participant is a replica of a `Replicable` cell, the
  runtime SHALL define transaction visibility without weakening the rule that
  every replica converges to an equal fold at quiescence regardless of which
  replica accepted a write [42-REPL-04]. A scope-wide decided outcome and a
  mergeable outcome are different guarantees, and their tension SHALL remain
  explicit (`42-replication.md` §Principles (fixed)).
- [BB-TPCC-35] WHERE every logical change can be represented as one state
  transition on one shard, the runtime MAY use disjoint ownership as the
  single-key safety proof [24-PART-03]; it SHALL NOT treat disjointness alone as
  multi-cell atomicity. Sections 4 S1 and S3 find only a restricted one-host,
  one-ingress, aligned-observer construction, and a scope spanning shards still
  requires a joint cut that does not exist (`42-replication.md`
  §Scatter-gather pull over an instance set (PN-5)).
- [BB-TPCC-36] The runtime SHALL declare whether a transaction scope may span
  hosts. **OPEN DESIGN POINT**: cross-host scope remains undecided; if admitted,
  it must be stated as an explicit exception or amendment to
  `42-replication.md` §Principles (fixed), which says **No global consensus**,
  and to `02-design-principles.md` §P4 — Local, compositional consistency.

### 5.5 Failure during commit

- [BB-TPCC-37] IF the coordinator fails after any participant commits but
  before every participant commits, THEN recovery SHALL terminate with every
  participant at the same outcome. The decision SHALL live in a durable,
  transaction-scope-wide record, and every recovering participant SHALL be
  able to resolve from that record; today's [24-DUR-01], [24-DUR-02],
  [24-DUR-03], [24-DUR-04], and [24-DUR-05] provide per-cell recovery but no
  scope-wide decision, as §4 S8 and §6 Q1 establish.
- [BB-TPCC-38] IF a participant fails while a transaction scope is open, THEN
  the runtime SHALL abort the scope with the disposition in §5.3; RESTART alone
  cannot perform that abort because it is restore plus re-baseline, never local
  rollback ([21-REBASE-01]; `21-propagation.md` §Pull).
- [BB-TPCC-39] WHEN a transaction commit decision is replayed during recovery,
  the runtime SHALL preserve replay-stable identities as required by
  [24-DUR-04] and SHALL suppress an `Effectful` participant at or behind its
  processed frontier as required by [24-DUR-05], rather than re-drive the
  effect (`24-data-cells.md` §Durability spectrum).
- [BB-TPCC-40] WHERE a transaction scope is confined to one host, the runtime
  SHALL satisfy [BB-TPCC-37] from a host-local durable scope-wide decision and
  SHALL NOT require a cross-peer reply channel. IF a transaction scope spans
  hosts, THEN its proposal SHALL either satisfy [BB-TPCC-37] without the
  synchronous cross-peer reply channel and global barrier rejected in §6 Q3,
  or explicitly justify amending P4 and the no-global-consensus principle
  (`93-feature-interactions.md` §I-11, Candidate C — Cross-host atomic
  multi-link two-phase commit). Section 6 Q3 identifies no current mechanism
  for the cross-host case, but leaves room for an adjacent proposal that avoids
  both rejected mechanisms.

The breakdown's `C-11`/`C-9` parentheticals were rechecked: neither marker
appears in generated `CONCORDANCE.md`, and both remain in
`91-gap-analysis.md` §Conflicts, but those rows are not a uniformly current
status account. C-9 still says “code diverges; fix pending” and is stale
against `24-data-cells.md` §Durability spectrum, which records G-59's
effectful-sink replay mechanism as resolved. C-11 marks its core conflict
resolved but still lists `computenet-woto` and `computenet-h6sf` as residuals
filed, not fixed; `23-ownership.md` §Taps: consumers vs observers now places
both in its closed history, so the two sources disagree about residual status.
Requirements [BB-TPCC-29] and [BB-TPCC-39] therefore cite the current chapter
guarantees and do not present either marker as a live CONCORDANCE conflict.

### 5.6 What this sketch deliberately does not say

This sketch does not select a coordination protocol, a wire exchange, a
journal format, or a coordinator lifecycle. It states only the guarantees that
an adopted primitive would owe. The emission identity in [BB-TPCC-25], the
overlap policy in [BB-TPCC-33], and cross-host admission in [BB-TPCC-36]
remain explicit open points; this section does not resolve them by implication.

## 6. Candidate evaluation

### Q1 — Two-phase commit from QuorumSetCell + a glitch-free frontier

No. `QuorumSetCell` is useful evidence because its name sounds closer to an
agreement mechanism than its contract is. Its KDoc says it emits elements
“whose presence count (distinct live source links asserting them) meets
threshold” (`kernel/src/main/kotlin/civictech/cell/data/op/QuorumSetCell.kt`,
lines 166–169). A source retraction lowers that count, and closing a link
removes its lane and re-evaluates membership (lines 185–187 and 264–275). The
majority and k-of-n factories are therefore views over current assertions, not
votes or irrevocable decisions. `SetDelta` membership can leave the quorum;
there is no proposal whose acceptance becomes durable. [BB-TPCC-11]

The optional frontier gate in that same file can delay one `QuorumSetCell`
fold until every open input edge has settled the wave. That is the local
delivery-completeness guarantee of [22-GF-01], not a commit decision. A
PREPARE would instead have to live at each participant before its normal inlet
handler changes owned state, but the participant surface has no transaction-
scoped, tentative state: `21-propagation.md` §Push (default, implemented) says
the ordinary path updates state and emits the actual, effective-only change.
Thus neither a quorum element nor the glitch-free frontier can carry a vote
with transaction semantics. [BB-TPCC-10]

The remaining failure points are independently missing. Section 4 S7 finds no
interleaving rule for overlapping counter-plus-stock participant sets. A
coordinator crash has no durable scope-wide decision to recover: [24-DUR-01],
[24-DUR-02], [24-DUR-03], [24-DUR-04], and [24-DUR-05] journal, restore, and
suppress replay per cell, which is why S8 remains MISSING. If a participant
has already emitted, ABORT has nothing to invoke: S2 and §3.2 establish that a
later retraction can restore values but cannot restore
never-having-been-observed. Counting more assertions cannot supply any of
those three guarantees.

**First step with no mechanism**: staging a participant's change as tentative,
durable, and invisible before its ordinary effective-only emission.

### Q2 — What participants would need

Participants would need capabilities that are absent from today's cell
contract, rather than a different composition of existing operators:

- **Tentative state** scoped to the transaction and invisible to every
  non-participant until the outcome is known ([BB-TPCC-23]). Ordinary cell
  state and ordinary emitted deltas are already effective.
- **Transaction custody for exclusive payloads.** The existing hold is park,
  not prepare: `23-ownership.md` §The SPSC link rule (normative) and
  `doc/spec/90-roadmap/93-feature-interactions.md` §I-12 keep a refused
  `Owned` payload at the sender's link and transfer it exactly once on replay.
  A transaction would need an explicit disposition that is neither that
  sender-owned park nor completed ownership transfer, while still discharging
  exactly once on abort ([BB-TPCC-29], [BB-TPCC-30]).
- **Retractable-before-effective emission.** `21-propagation.md` §Push
  (default, implemented) makes output effective-only. Retract-as-data is a new
  observable change, not erasure of the old one; [24-OP-OUTERJOIN-02] even
  permits the transient overlap that an eager observer can see. S2 therefore
  requires the stronger no-observable-effect abort of [BB-TPCC-27] and
  [BB-TPCC-28], not compensation after publication.

These are requirements on a future participant abstraction, not protocol
steps; §5 records the open choices rather than resolving them here.

### Q3 — Does the promotion two-phase swap generalize?

No. `53-evolution.md` §The promotion swap (decided in 93 I-11, implemented)
defines a local, membrane-scoped code-version swap of one incumbent cell for
one pre-validated candidate. [53-SWAP-01] checks compatibility before the
window, [53-SWAP-02] buffers and drains that one swap set, and [53-SWAP-05]
makes the atomicity local to the membrane's host. It does not stage business
state in several independently live participants. The chapter draws the
boundary itself in [53-STATE-04]: “a whole-composite atomic transaction would
be the distributed barrier P4 forbids.”

That boundary is also the prior answer in
`doc/spec/90-roadmap/93-feature-interactions.md` §I-11, Candidate C —
“Cross-host atomic multi-link two-phase commit.” Candidate C was rejected
because M5.4 did not build the synchronous cross-peer reply channel needed to
collect remote votes, while a vote-collecting coordinator would recreate the
global barrier forbidden by P4 and G-20. Any adjacent proposal must answer
differently: it must avoid both that reply channel and cross-peer barrier, or
explicitly justify amending those principles, while still satisfying the
durable-decision and terminating-recovery questions in [BB-TPCC-37] and
[BB-TPCC-40]. Renaming the coordinator or its inputs is not a different
answer. [BB-TPCC-12]

The two nearby implementations do not close the gap. `ReplicaQuorum` in
`kernel/src/main/kotlin/civictech/cell/consistency/ReplicaQuorum.kt` supplies
the PN-7 covering-subset settlement predicate consumed through
`ReplicaFrontier.completeAt` in
`kernel/src/main/kotlin/civictech/cell/consistency/WaveFrontier.kt`: it asks
whether the covering replicas delivered an origin wave for a read, not whether
participants agreed to a write. Likewise `demo/exchange/` wires per-peer order
sets through union, partitioned aggregation, and a glitch-free board in
`demo/exchange/src/main/kotlin/civictech/demo/exchange/Main.kt`; it contains no
matching or settlement transaction. Its
`demo/exchange/src/test/kotlin/civictech/demo/exchange/ExchangeCompositionExitTest.kt`
asserts over 100 seeds that, through repartition, shard migration, peer
partition/recovery, and a late joiner, each converged board equals a batch
group-by over the converged order set. That is strong convergence evidence and
explicitly not an atomicity result. [BB-TPCC-10]

### Q4 — Cost when unused

P2 permits expense on rare operations but not on steady-state message flow
(`doc/spec/00-foundations/02-design-principles.md` §P2 — Near-zero-cost fast
path). The viable shape is therefore opt-in per participating link or cell,
with no transaction metadata, lookup, buffering, or decision check on an
undeclared path. This is the discipline already used by [22-GF-01]:
non-declaring cells process eagerly with zero coordination cost, and
`QuorumSetCell`'s `emitOnFrontier` flag in
`kernel/src/main/kotlin/civictech/cell/data/op/QuorumSetCell.kt` defaults to
`false`. Any future primitive that cannot preserve that unused-path shape
fails [BB-TPCC-22] before its protocol details matter.

## 7. Decision

**Decision**: Hybrid: (B) general + (A) bounded single-host transaction

The candidate case comes first because S1 and S3 establish a real local
foothold rather than a disguised refusal: one linked ingress can preserve one
wave across same-frame fan-out on one host, and [22-OBS-01]/[22-OBS-02] can
publish the resulting keyed views together. S5 adds a strong application fit —
Delivery is already deferred dataflow — and S7 credits the per-key
single-writer serialization point. Those mechanisms still stop short of
all-or-nothing execution. Section 6 Q1's `**First step with no mechanism**` is
staging a participant change as tentative, durable, and invisible before
effective-only emission; S2 has no abort and S8 has no scope-wide recovery
decision. That is a narrow, decidable host-local gap worth preserving without
turning it into a cross-peer protocol.

A future integration pass may consider this quoted gap-table row; this
document neither assigns its id nor edits the gap table:

```text
| ID | Gap | Proposal | Where |
|---|---|---|---|
| G-NN (unassigned) | No bounded host-local transaction scope can stage changes in several participant cells, publish all of them together, and abort before any effective emission. | Specify an opt-in scope confined to one host and one ingress wave, with explicit participants, tentative invisible state, a host-local durable outcome, commit-only publication, abort accounting for `Owned`/`Leased`, and no cross-host form. | 20/21, 20/22, 20/23, 20/24, 30/31 |
```

The row would be cited from `21-propagation.md` §Push (default,
implemented), `22-consistency.md` §Source identity: emission epochs and
§Local glitch-freedom (opt-in), `23-ownership.md` §Recovery and dead letters,
`24-data-cells.md` §Durability spectrum, and `31-hosts.md` §The host protocol
(as implemented). It deliberately stays inside one host: the single-host
construction in S1/S3 shows the boundary, not that the missing tentative/abort
semantics already exist.

The general primitive is refused. Section 6 Q3 engages the prior Candidate C
directly: a cross-host all-participant decision needs the synchronous
cross-peer reply channel that Candidate C rejected and recreates the global
barrier forbidden by `02-design-principles.md` §P4 — Local, compositional
consistency. `42-replication.md` §Principles (fixed) instead commits to **No
global consensus**, while [53-STATE-04] keeps even promotion atomicity local
and says a whole-composite transaction would be that forbidden barrier. This
is not a judgment that transactions are unimportant; it follows the model's
stated boundary that “transactions are not the framework's core (dataflow
is)” (`14-invocations.md` §The core move (ADR 3)).

A future integration pass could add this sentence to
`42-replication.md` §Principles (fixed):

```text
Interactive cross-host, cross-cell transactions with abort are outside ComputeNet's core model: their durable all-participant decision and terminating recovery require the cross-peer reply barrier that P4 and the no-global-consensus principle refuse; applications that require that contract use a transactional database at the boundary and feed its committed changes into ComputeNet.
```

What the model offers instead is useful but transaction-specific, and its
weaker points remain explicit:

| TPC-C transaction | ComputeNet offers instead | Where it is genuinely weaker |
|---|---|---|
| New-Order | One-input `GroupByCell` emission or the one-host, same-frame, aligned-observer construction from S1; S7 also gives a per-key single-writer counter [24-OP-GROUPBY-03] [22-OBS-01] [22-OBS-02] [24-PART-03]. | S2 has no abort that preserves never-observed, and S8 has no durable cross-cell outcome; cross-host fan-out has only [22-SRC-01] convergence. |
| Payment | The same one-input or one-host aligned construction from S3 [24-OP-GROUPBY-03] [22-OBS-01] [22-OBS-02]. | Independent cells have no atomic commit, and the construction does not extend to cross-host participants [22-SRC-01]. |
| Order-Status | A per-source vector-frontier composite plus a bounded read [22-OBS-01] [22-OBS-02] [21-PULL-03]. | It is not the Level 3 scalar serialization point that S4 requires; a bounded walk may be smeared. |
| Delivery | Deferred execution is native dataflow, and one district-scoped transition can have one disjoint owner [24-PART-03] (S5). | Separate order, line, and customer cells still lack per-district atomic commit. |
| Stock-Level | Wave-neutral bounded reads and aligned maintained views [21-PULL-02] [21-PULL-03] [22-OBS-01] [22-OBS-02] (S6). | This is sufficient for the stated Level 1 shape, but it offers no stronger snapshot guarantee. |

A user who needs full TPC-C semantics should therefore use a transactional
database and consume its committed CDC stream through a connector ingress.
The connector part is the CON1 boundary: `24-data-cells.md` §Durability
spectrum assigns minting and persistence of stable external actor identity to
that ingress, while the kernel supplies the stamping and replay rules.

## 8. What this document does not do

This document is a non-normative analysis artifact. It does not implement a
transaction scope, specify message rounds or a coordinator state machine,
modify any file under `doc/spec/`, mint a `G-*` id or a normative requirement
id, add a concord scenario, implement TPC-C, or report a `tpmC` figure. The
candidate row and principle sentence in §7 are quoted proposals for a future
integration pass; neither changes ComputeNet's model today. No source module
or benchmark module is modified by this study. [BB-TPCC-08]

The boundary is also substantive: the narrow candidate ends at one host and
one ingress wave, while the general cross-host transaction is refused. Nothing
in the local candidate should be read as a back door to the cross-peer barrier
rejected in §6 Q3.

### 8.1 Unverified assertions

The audit command is `grep -n 'unverified' doc/bench/bb-tpcc-bridge.md`.
Outside §8, its sole result is in §4 S2: that paragraph says the rollback claim
uses TPC-C clauses “rather than an `unverified` tag.” It records verification,
not an unverified assertion. The document therefore contains no assertion
tagged `unverified`; its TPC-C claims cite the clauses read in §2, and its
ComputeNet claims cite requirement ids, named spec sections, or repository
paths.
