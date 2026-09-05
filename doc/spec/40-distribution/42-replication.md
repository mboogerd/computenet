# 42 — Interest-Driven Replication

> **Status**: Implemented for the mergeable set family (M7); single-writer leader/follower design decided, unimplemented (§Single-writer replication); keyed/partitioned structures unified under §Interest-scoped instance sets (replication and partitioning as two settings of one mesh — design decided, code deferred with the G-24 trigger)
> **Sources**: ADR 0 (§5, §6), ADR 1 (§11, §12), ADR — Cellular Software Development Process (runtime characteristics)
> **Implementation**: `cell.replication.Replication` (symmetric gossip-mesh
> linker over registry announcements, plus its eviction gate —
> `Replication.evict`, W3.3), `SetCell.deltaInlet` + tombstoned
> OR-set state, `LocationRegistry.onPublish`/`replicasOf`/`onUnpublish`,
> multi-peer `Peering` with `Loopback.partition()/heal()` and
> `RegistryAnnounce.unpublished` reconciliation, `cell.verify.ReplicaConvergence`
> (the replica-convergence invariant harness, 50/52). Verified:
> `ReplicationTest`, `ReplicatedSessionTest` (3 peers, 100 seeds,
> partition+heal, divergence control; plus a 3-peer partition/heal/evict
> case under the convergence harness)

## Principles (fixed)

- **Local vs replicated cells**: a cell runs locally only, or is replicated
  across machines (ADR 1 §11). Replication is opt-in per cell.
- **Interest drives replication** (P6): a peer subscribing to results
  volunteers **partial responsibility** for upstream computation/data; the
  network topology forms around shared activity, lowering latency where
  collaboration actually happens.
- **Local-first, open by default** (P7): process data where it is needed;
  default to sharing; self-interest over altruism.
- **No global consensus**: convergence-oriented consistency (mergeable deltas,
  20/24) among untrusting-but-cooperating peers; encryption/allowlists where
  needed (40/43).

## What replication must mean here (working definition)

Replicating a cell = running an instance of the same **logical cell**
(G-8's `logicalId`) on several hosts, with:

1. **State convergence** via the cell's declared merge semantics — only cells
   of the *concurrent/mergeable* mutability class (10/11) replicate freely;
   single-writer cells replicate as leader + followers (followers serve reads
   / hold warm state). [42-REPL-04] WHERE a cell is replicated, replicas of one
   logical cell SHALL converge to equal folds at quiescence regardless of which
   replica accepted each write.
2. **Delta gossip between replicas**: replicas link each other's delta
   outlets/inlets over ordinary network bridges (41) — replication reuses
   dataflow; there is no second sync protocol.
3. **Subscription-scoped extent**: a replica exists because local interest
   (34) justifies it; interest decay ⇒ replica suspension (33) ⇒ eventual
   eviction.

## Design as implemented (G-7 resolved for the mergeable class, M7 + session delta 4)

- **Replica discovery & membership**: each replica is a full `CellRef`
  (same logical id, distinct instance id) published like any cell;
  `LocationRegistry.replicasOf(logicalId)` + the `onPublish` hook are the
  membership view. No location sets: one location per full ref.
  Membership is never a consensus (decided in
  [93 I-3](../90-roadmap/93-feature-interactions.md)): `replicasOf(id)` is an
  eventually-consistent local fold of announcements — no view number, quorum,
  or barrier (P4). Peers MAY transiently disagree on the set; gossip
  idempotence makes that safe (a late-learned peer catches up; a stale peer's
  extra link delivers a duplicate that merges to a no-op).
- **Gossip wiring**: every peer runs the same local rule — link each local
  replica's delta outlet to every discovered replica's `deltaInlet`
  (`cell.replication.Replication`, contract: `data.Replicable`) — a full mesh
  emerges symmetrically with no coordinator. Echoes terminate because a
  replica re-emits only *new* information (effective-only, 21); this is why
  only idempotent-mergeable cells replicate. The class today: the tagged set
  family (tag union) and `PnCounterCell` (per-source cumulative totals,
  pointwise-max merge). Plain `CounterCell` stays single-instance — raw
  addition double-counts echoes — remaining valid for derived per-peer views.
  A third member is decided, unbuilt: the tagged map (`TaggedMapDelta`,
  20/24 §Tagged maps) joins the class — merge is pointwise dot union,
  idempotent for the same reason `SetDelta`'s is — once its cell (96
  §E1.2-E1.3) lands.
- **Tombstones**: multi-path delivery means a removed tag can arrive late by
  another route; `SetCell` therefore keeps del-tags (full OR-set). Tag sets
  grow monotonically; compaction is future work alongside durability (G-25).
- **Anti-entropy**: partition ⇒ Remote locations drop ⇒ gossip parks
  (spec 33); heal ⇒ re-announce ⇒ parked replay + idempotent catch-up.
  Verified, not rebuilt — the late-join path (21) doubles as recovery.
  [42-REPL-05] WHEN a consumer links to a replica after deltas have already
  gossiped through the mesh, the replica SHALL bring it current (state-as-delta
  catch-up) such that its fold is indistinguishable from a consumer linked to
  any replica from the start.
  **M10**: a *re*-announce of an already-linked replica re-fires the
  catch-up unicast through the existing link (`Replication.maybeLink`) —
  a crashed-and-recovered peer regains whatever its dying transport
  swallowed; idempotent merges make the repeat cost one redundant delta.
  Decided end-state (93 I-16, unbuilt): recovery becomes
  **pull-on-reestablish**, gated by a per-link liveness (drop) epoch — a
  parked link replays its held buffer exactly and MUST NOT pull; a dropped
  link's subscriber issues a management-class `requestState(since =
  TagFrontier)` upstream on the link's metadata plane, where `TagFrontier` is
  its per-source merge-tag high-water (tags, never wave ids), and the producer
  replies with one waved state-as-delta of only the tags beyond that frontier.
  Incremental, idempotent by tag union, and still no second sync protocol;
  producer-side `onLinked` catch-up (G-22) remains the co-hosted fast path.
  Today's re-announce-triggered catch-up re-fire (M10.4, above) is the
  producer-push interim of that arm.

Still open: upstream responsibility (subscribing peers accepting shares of
upstream partitions, with 24's partitioned cells); interest-driven replica
*placement* and when to spawn a local replica vs subscribe remotely (34's
economic layer); leader *election* (the deferred liveness half of
§Single-writer replication below). Eviction and leader/follower have moved
from open to decided-but-unbuilt design (below).

⚠ GAP (G-62): every interest-driven policy defers to an economic layer that
does not exist — replica spawn-vs-subscribe, migration-toward-attention,
partition-host split/merge, resharding triggers, and per-Principal attention
budgets (the Sybil economics). Proposal: an attention/quota-driven economic
layer (the G-6 residual, on the G-28 quota walk) deciding when to spawn a
local replica vs subscribe remotely and where replicas live, migration
candidacy under persistent high attention with remote hotspots, partition
split/merge and bulk-rebalance triggering by load/size/attention, and
per-Principal resource budgets bounding authenticated interest claims with a
concrete cost to mint an identity (93 I-3/I-9/I-19/I-8/I-28).

## Interest-scoped instance sets: one mesh, replication and partitioning as two settings

Replication and partitioning are not two distribution mechanisms — they are two
settings of a **single knob** over a **single substrate**. This section states
that substrate; 24 §Partitioned state is its disjoint-interest instantiation.
The two sections describe one mesh, read twice.

The substrate is the **instance set** of a logical cell: the live instances of
one `logicalId` (G-8), each a full `CellRef` published to the registry
(`LocationRegistry.instancesOf(logicalId)`; `replicasOf` is its replication-class
view). The linker over that set is the same membership-reactive rule already
built — link each local instance's delta outlet to every discovered instance's
`deltaInlet` (`cell.replication.Replication`, `Replication.maybeLink`). Nothing
above is new; what is new is one predicate.

- **Per-instance `Interest`.** Each instance carries an `Interest` — a
  Serializable predicate over the delta/key space declaring which deltas that
  instance wants (hash-slot set / key range / arbitrary predicate; default =
  **total interest**, every delta). `maybeLink` links two instances only where
  their interests overlap, and each emission is filtered to the *target's*
  interest before it rides the link: a delta a peer has no interest in never
  crosses. Interest is the demand signal P6 already names (30/34) made concrete
  per instance and per link. [42-INT-01] WHERE an instance declares a partial
  `Interest`, that instance SHALL hold exactly the subset of the logical cell's
  state its interest admits — no more (a delta outside its interest never links)
  and no less (every admitted delta gossips in).

- **Union is the merge.** Convergence is still the cell's declared merge over
  the instance set. The three named regimes below are exactly three profiles of
  `(Interest, union)`:

  | Regime | Interest profile | Union degenerates to | Why it is safe |
  |---|---|---|---|
  | **Replication** | **total** interest on every instance | idempotent merge (tag union, pointwise-max) | echoes terminate on effective-only (21); merge is commutative/associative/idempotent (G-23) |
  | **Partitioning** | **disjoint** key-interest — each instance owns one key range, no two overlap | **disjoint** union (no key ever merges concurrently) | disjointness *is* the merge-safety proof: a delta only ever mentions its own keys, so union is conflict-free and needs no merge function — this is why a non-mergeable keyed structure still shards |
  | **Sharded replication** | **overlapping partial** interest — shards that also keep replicas of some ranges | idempotent merge on the overlap, disjoint union on the remainder | the overlap is the replication case (idempotent), the remainder is the partition case (disjoint); both hold pointwise |

  Partitioning is the **conflict-free degenerate case** of replication: set every
  instance's interest disjoint and the idempotent-union mesh becomes a disjoint
  router with the merge function never exercised. Sharded replication is the
  interpolation — free, because it is just the two safe cases holding on
  disjoint sub-ranges of one instance's interest.

- **"Many partition types" = many `Interest` assignment functions, not many
  cells.** A hash partitioner, a range partitioner, a locality partitioner are
  three functions assigning `Interest` to instances over the *one* mesh — not
  three composite cell types. The partition-structure taxonomy (deferred until a
  second real structure demands it) is a taxonomy of assignment functions, and
  each new one composes with wire, journal, catch-up, and park/replay for free
  because it rides the same substrate.

Everything replication already earned is inherited by construction. Delta gossip
over ordinary bridges (41), park-on-partition + idempotent catch-up (anti-entropy
above), state-as-delta-from-empty late join (G-22), and the eviction gate
(93 I-3) are properties of the mesh, so the disjoint-interest setting gets them
without re-earning a single pair. Repartition (24 §Partitioned state) is
therefore **interest reassignment**: change an instance's `Interest`, replay the
moved range as one state-as-delta catch-up (21) into its new owner, park the
flip window (33) — the same machinery a re-announce already drives, not a bespoke
routing-table protocol.

Contract for the unbuilt realization (design decided here, code deferred with
the G-24 trigger): `Interest` is a per-instance field the linker consults;
`maybeLink` filters deltas by target interest; `PartitionedCell` (24) is the
disjoint-interest naming/composition convenience over an instance set, its router
*is* the disjoint-interest linker, and its `routingEpoch` is the versioned
interest-assignment table. No second distribution mechanism, no second sync
protocol — one mesh, one knob.

### The interest algebra is closed (PN-3a/c)

Three properties the linker and the wire depend on, made true of the code:

- **The algebra closes.** `Interest` is `Total`, `Empty`, `Slots` (hash-slot
  set), `Ranges` (half-open ordered ranges), and the combinators `Union`,
  `Intersect`, `Complement` — each a `data class`/`object`, none an anonymous
  predicate. `overlaps` is *structural and symmetric*: a single shared decision
  matches the arm pair without regard to order, so `a.overlaps(b) ==
  b.overlaps(a)` always. It is *honest* where a shared key is decidable (`Empty`
  overlaps nothing; disjoint slot sets / disjoint ranges do not overlap;
  `Union`/`Intersect` distribute over their members) and only *conservatively*
  `true` where it is genuinely undecidable (a `Complement`, mixed slot/range
  kinds) — never the blanket `true` an anonymous combinator returned. Because
  every arm is a `data class`, an interest round-trips through serialization to
  an `equals` value, so it can ride the versioned interest-assignment table
  across the wire. `Total` and `Slots` are behaviourally unchanged for every
  pre-existing interaction (non-opting graphs see no difference).

- **`StateRequest` is interest-scoped.** A pull carries `scope: Interest?`. The
  producer restricts its baseline reply — both the state-as-delta and the
  reported `TagFrontier` currency — to the keys `scope` admits, so a
  partial-interest consumer (a shard peer, a scatter-gather leg) pulls exactly
  its slice instead of the whole state. `scope` absent ⇒ `Total` ⇒ the whole
  state, byte-identical to the pre-scope reply; every existing call site is
  unchanged.

- **Retained pull currency is per instance, never merged.** A consumer that
  pulls an instance set keeps one `TagFrontier` *per source instance*
  (`Map<instanceRef, TagFrontier>`), not one merged frontier. Shard holdings are
  non-contiguous: instance A may hold counters {1,3,5} of a shared upstream
  source while B holds {2,4}. A single pointwise-max `since` merged across
  instances would carry A's `5` into the currency handed to B, so B's next
  incremental pull reports every tag ≤ 5 as already-seen and silently drops B's
  own holdings. A per-instance `since` is monotone *within* its instance and
  never contaminated by a sibling's progress.

## Delivered watermarks and causal stability

A replica set converges (§Design as implemented), but convergence alone answers
neither of the two questions a settling consumer actually asks: *has this wave
reached every replica that could hold a key it touches* (the **frontier read**,
used by the cross-replica completeness gate in 22), and *can no concurrent
operation for this timestamp still arrive anywhere* (the **stability read**, the
compaction/GC trigger — causal stability in the sense of research 03 §3 Def 5.1).
Both are answered off one gossiped substrate: a **delivered-watermark companion**
riding the ordinary replica mesh, with no second protocol, no consensus and no
total order. This section states that substrate and the two reads over it. It is
built for the frontier read (96 E3.2–E3.4, `cell.data.WatermarkCell`,
`cell.data.delta.WatermarkDelta`, `cell.data.delta.DeliveredFrontier`,
`cell.consistency.ReplicaQuorum`); the stability read, the heartbeat and the
departure notice are decided design, labelled below where they are not code.

**The companion.** Each data replica of a logical id gets one companion
`cell.data.WatermarkCell`, itself `Replicable` and itself replicated, so the
companions of one logical id find each other by `LocationRegistry.replicasOf`
exactly as the data replicas do (`Replication.watermarkRef` derives the companion
ref from the data id, `watermark:{logicalId}`, borrowing the data replica's
`instanceId`; `Replication.trackDeliveries` wires it). The companion's replica
slot is `WatermarkCell.slotId(ref)` — **derived** from the ref, not random, so a
replica replaying its journal credits the same row the network already saw. A
companion is never itself tracked; that would recurse.

### The primitive: one contiguous delivered prefix per (slot, source)

[42-WM-01] The delivered-watermark state SHALL carry, per replica slot and per
wave source id, a `deliveredThru` counter naming the **contiguous** delivered
prefix — the highest `t` such that every counter `1..t` from that source has been
delivered at that slot — and a counter arriving above a gap SHALL NOT advance the
row until the gap is filled.

Contiguity is the whole point of the rule: a source's tags are unit-counter dots
`(sourceId, 1), (sourceId, 2), …`, and over a multi-path gossip mesh they arrive
out of order, so a plain max would claim a hole had been delivered and release a
wave that had not. `cell.data.delta.DeliveredFrontier` is the implementation —
prefix plus an out-of-order holdback, returning the new `thru` **only** when the
contiguous prefix actually advanced. Tag sources and wave sources share the same
`(UUID, Long)` monotone shape, so one vocabulary serves both.

Two distinct lanes ride the one companion and must not be confused. The
**per-origin** lane is the fold seam: `Replication.trackDeliveries` registers
`DeliveryTracking.onDeliver` (anchor `onDeliver`) so each raised origin prefix —
read where the incoming delta's origin tags are still visible — feeds
`WatermarkCell.advance(source, thru)`. The **per-outlet-epoch** lane is
`WatermarkCell.trackDeliveriesOf`, the retained CP-B2 tap, which answers "how
many waves has this replica re-emitted", not "which origin waves has the replica
set delivered" (the KDoc at `Replication` anchor `CP-B2 re-emission tracking`
says the same). They are different key spaces sharing one mesh.

### Merge is a join-semilattice

[42-WM-02] The merge of two delivered-watermark states SHALL be pointwise max per
`(replicaSlot, sourceId)` — an absent entry reading as bottom (`Long.MIN_VALUE`,
**not** `0`, so an unseen row can never coincide with a real zero and look caught
up) — with `closed` and `members` union-merged and `suspended` max-merged, such
that merge is commutative, associative and idempotent and a gossip echo
terminates.

[42-WM-03] IF a replica receives a delivered-watermark delta that would lower a
row, or re-receives one it has already absorbed, THEN its state SHALL be
unchanged and it SHALL re-emit nothing. Rows are never retracted: progress is
monotone.

`cell.data.delta.WatermarkDelta.merge` and its `dominates` order are the lattice;
`WatermarkCell.advance` is effective-only (`if (thru <= …) return`) and
`applyRemote` re-emits **exactly** the entries that raised something, returning on
a fixpoint (anchor `echo terminates here`). Effective-only re-emission (21) is
what makes the mesh quiesce.

**What is carried from Naiad, and what is given up (research 02 §2–3).** The
safety property is Naiad's, verbatim in sense: *no local frontier ever moves ahead
of the global frontier*, so a notification released locally is always safe. What
is **not** carried is Naiad's mechanism. Its progress protocol broadcasts signed
`(pointstamp, delta)` occurrence counts and accumulates them by addition, which is
**not idempotent**: over a gossip mesh with redelivery and multi-path arrival it
double-counts, and recovering it would demand exactly-once FIFO per peer pair —
precisely the channel assumption (fixed, fully-known worker set; reliable FIFO;
fault tolerance by checkpoint/restore rather than membership change) that a
membership-changing replica mesh cannot supply. The reformulation to a
join-semilattice of per-source low-watermarks merged by pointwise max (research 05
gap 7; the same shape as `PnCounterDelta`) buys idempotence and costs precision in
two places, both named honestly here: a row can only **rise**, so there is no
retraction of progress and no downward correction of an over-eager advance; and
liveness degrades exactly where the lattice cannot distinguish "silent" from
"gone" — an idle replica (answered by the heartbeat, [42-WM-06]) and a replica
that departs without closing its row (frozen stability, [42-WM-08]). Causal
stability itself and its idle-replica caveat are research 03 §3 (S3/S6, Bauwens &
Gonzalez Boix); the layering of the cross-replica frontier (gap 7) over
compaction/GC (gap 6) is research 05 gaps 6–7.

### The four lanes, and the rule for a fifth

`cell.data.WatermarkCell` carries exactly **four independent lattices**, each
grow-only or pointwise-monotone in its own right, and `ReplicaQuorum.frontier`
reads all four independently:

1. **`rows`** — the base per-`(replicaSlot, sourceId)` delivered-counter map
   ([42-WM-01]–[42-WM-03]); `advance`/`applyRemote` merge it.
2. **`closed`** (PN-0c) — the grow-only set of cleanly departed slots, so a read
   stops waiting on a row that provably can never advance again ([42-WM-08]).
3. **`suspendEpoch`** (PN-19, 34 decision 3) — the per-slot suspend epoch, odd =
   suspended: the resumable analogue of `closed` for a member that may return.
   Only a slot's own owner writes it (single-writer, alternating), so max-merge is
   a genuine monotone join.
4. **`members`** (FU-2) — the grow-only set of covering member slots that have
   announced their existence, converging membership itself faster than the
   point-to-point topology announcements `instancesOf` feeds off ([42-WM-07]).

The cell SHALL NOT gain a fifth lane. Each lane above answers a different "what do
I know about this replica slot" question; a fifth per-slot settlement concern
unrelated to delivery, departure, suspension or membership is the signal that this
cell is doing more than one job, and is a **sibling-cell** design question (epic
[KE3-07]), not a fifth `Mutable*` field. Note what this rule does *not* forbid:
both the heartbeat ([42-WM-06]) and the stability read ([42-WM-05]) are behaviour
**over** lanes 1–4, not new state, and neither needs a fifth lane to be built.

### The frontier read (decided in 96 E3.1, built in E3.4)

[42-WM-04] A wave `(s, t)` touching key `k` SHALL be treated as replica-complete
only when every **open covering member** — a live member whose `Interest` admits
`k`, whose slot is not `closed`, and, under a DEGRADE settlement policy, whose
slot is not `suspended` — satisfies `row[s] >= t`; a covering member with no row
for `s` SHALL read as bottom and hold the wave; an empty covering subset SHALL
hold rather than release vacuously; and WHILE the companion names a member slot
the local membership view has not accounted for, every keyed wave SHALL hold.

`cell.consistency.ReplicaQuorum.frontier` is the implementation and its KDoc the
detailed truth; the four policy switches (`creationFence`, `degrade`,
`membershipBarrier`, and the unfiltered `key == null` path) are documented there
and their rationale in 22, which this rule restates rather than extends. Read it
against 22 §Interest-scoped settlement (PN-7) for the covering-subset quorum, 22
§R13 creation fence for the rowless-member hold, and 22 §Converged-membership
barrier (FU-2) for the unaccounted-slot hold. The two conservative asymmetries are
the same asymmetry twice: a *known* covering member with no row holds on bottom,
and an *unknown* one — announced on the companion CRDT but absent from this node's
`instancesOf` view — holds every keyed wave. Both release the moment the view
converges; an unkeyed wave (no covering quorum) is never held, so default
settlement is unchanged.

### The stability read (decided in 96 E3.1, unbuilt — E3.5, `computenet-9sm.3`)

[42-WM-05] For a source `s`, `stableFrontier[s]` SHALL be the pointwise **minimum**
over every open membership row of `row[s]`, with an absent row reading as bottom,
such that at all times `stableFrontier[s] <= row[slot][s]` for every open slot and
`stableFrontier[s]` is monotone non-decreasing.

"Open" here is the same predicate the frontier read uses: a member, not `closed`,
and — under DEGRADE — not `suspended`. Reading an absent row as bottom **freezes**
the minimum, which is the conservative direction and is the point: it is the same
Naiad safety property in its terminal form — no local read may run ahead of the
true global frontier. A timestamp `τ` is causally stable once no concurrent
operation for it can still arrive anywhere, i.e. once the frontier read has
terminated for it everywhere (research 03 §3 Def 5.1); it is the trigger a
compaction or GC pass waits on. The frontier read and the stability read are the
*same primitive at two freshness levels* — one wave, all covering members, versus
all sources, all open members — which is why research 05 gaps 6 and 7 fold into
one substrate rather than two. Nothing in `kernel/` computes `stableFrontier`
today; this paragraph is design.

### Idle liveness: heartbeat rows (decided in 96 E3.1, unbuilt — E3.3(c), `computenet-9sm.2`)

[42-WM-06] WHEN a heartbeat cadence fires on a replica whose row has not changed
since that replica's last emission, the replica SHALL re-emit that row verbatim,
and every receiver SHALL absorb it as a fixpoint and re-emit nothing.

Without it a silent replica is indistinguishable from a slow one, and the pointwise
minimum in [42-WM-05] never moves: "if one single node does not issue any updates
for some time, no causal stability can be determined at any replica" (research 03
§3, S6). The heartbeat is safe precisely because [42-WM-02] and [42-WM-03] make a
repeated row a fixpoint — a non-idempotent accumulator could not afford to resend
one. It is behaviour over lane 1, not a fifth lane. No heartbeat exists in
`kernel/` or `wire/` today.

### Membership is one snapshot per read

[42-WM-07] Each evaluation of the frontier read or the stability read SHALL take
**one** membership snapshot — the `instancesOf` fold for the logical id together
with the companion's announced `members` set — and SHALL evaluate every member
against that one snapshot; and because the `instancesOf` fold is only eventually
consistent (R13), a covering member the local view has not yet learned of SHALL be
waited on only through the announced `members` set, never conjured by the fold.

`ReplicaQuorum.frontier` reads `membersOf(logicalId)` once per predicate call;
`Replication.trackDeliveries` calls `WatermarkCell.announceMember()` on join, and
`WatermarkCell.members()` is what the barrier reads. The announced set is the more
complete of the two because it rides the *transitively* gossiped companion CRDT,
whereas `instancesOf` mirrors only direct peers — which is exactly why it can name
a slot the fold has not accounted for, and why that discrepancy is a hold rather
than a bug.

### Departure: closed, suspended, or frozen

[42-WM-08] IF a replica departs, THEN its slot SHALL be marked `closed` — and its
row SHALL thereafter constrain neither read — only where the departure was clean
(the last local replica of the id drained and despawned through
`Replication.evict`); an unreachable-but-possibly-alive replica SHALL be
*suspended* rather than closed; an unclean departure SHALL leave the row in place,
freezing the stability read at it; and a frozen row SHALL NOT be released by
timeout or lease.

The three cases are three different pieces of knowledge, and collapsing them would
be unsound in a different direction each time.

- **Clean.** `Replication.evict` (anchor `closeDepartedRow`) closes the row via
  `watermarks[id]?.close()` once `localReplicas[id]` is empty — once the *last*
  local replica leaves, because the companion carries this peer's single row. The
  `closed` marker rides the same idempotent watermark mesh as the data, so it
  converges even where a topology unpublish is lost. [42-REPL-06] is the
  convergence half of orderly departure (the departed replica's frozen final
  stream is not a divergence); this rule is the settlement half, and the two are
  complementary, not duplicates.
- **Suspended, not closed.** A replica with no reachable peer is partitioned as
  far as the local view can tell, so `evict` parks it (a `managementInlet` suspend plus
  `watermarks[id]?.suspend()`) instead of departing it. Closing
  would be a lie: the replica may still hold unique state, and its frozen row is
  the *correct* answer for as long as it is unreachable. A WAIT read still holds
  on it; only a DEGRADE read drops it. `Replication.linkOut` (anchor
  `heal (G-45)`, reached from the `registry.onPublish` hook when a peer becomes
  visible again) and `supersedeLocalInstance` call `resume()`, and the
  post-resume catch-up advances the row that froze while parked. `close`,
  `suspend` and `resume` are all effective-only, so a repeated call is a
  fixpoint like every other lane.
- **Unclean.** A crash without `evict`, or churn, leaves the row present and never
  closed, so the pointwise minimum of [42-WM-05] freezes at it. This is the decided
  disposition, not an oversight: a rebuilt replica at the same instance id reuses
  the memoised companion, so the row it froze at crash time is what a frontier read
  still sees (`Replication.rehomeCompanion`'s KDoc states this; only the
  companion's *residency* is re-established). It is surfaced to the application as
  a `Stall`-family notice — decided, unbuilt (96 E3.6(c), `computenet-9sm.5`).
  Lease- or timeout-based row eviction is explicitly **out of scope** and is
  research R13: a lease races a partitioned-but-alive replica, and losing that race
  releases a wave the survivor never delivered, which is the one failure the whole
  substrate exists to prevent. Freezing is the honest cost.

### The three named costs

Each of the three costs 96 E3.1 point 3 names is accepted with a stated
disposition; none is silently absent.

1. **Membership completeness.** The reads are gated on the `replicasOf`/
   `instancesOf` announcement fold, which is eventually consistent. Disposition:
   conservative holds on both halves ([42-WM-04], [42-WM-07]) — a known rowless
   member holds on bottom, an announced-but-unaccounted member holds every keyed
   wave. Whether the fold is *sufficient* under join and churn is open, and is 95
   §R13; do not read this section as resolving it.
2. **Idle-replica liveness.** A replica that delivers nothing publishes nothing,
   and the stability minimum never advances. Disposition: heartbeat rows
   ([42-WM-06]), safe by idempotence — decided, unbuilt.
3. **Frozen stability on unclean departure.** A row nobody closes freezes the
   stability read indefinitely. Disposition: freeze and notify ([42-WM-08]); never
   unfreeze by timeout. The alternative — lease-fenced eviction — is 95 §R13, named
   only, not adopted here.

The epoch-hygiene question (when a `ReBaseline`-superseded source's watermark
column may be dropped) is 95 §R14, likewise named and not resolved.

### Open interactions

The R14 superseded-source-column rule and the FRM1/G-36 two-hop-cut interaction
with this section are recorded here. Both are recorded open: neither is
resolved by anything in this section.

**R14 — superseded columns and the stability MIN.** Whether a `ReBaseline`-
superseded source's column is excluded from the pointwise minimum of [42-WM-05]
is **unpinned** — see `concord/corpus/DISPUTES.md` entry `42-WM-R14`. No rule
here decides it, and no `[42-WM-nn]` id is minted for one. The shipped
behaviour is that a superseded column **stays in the MIN**: one supersession
therefore freezes stability for every replica of the id until R14 is answered
— unbounded-but-correct, the same disposition 95 §R14 records for the corner.

The reason the exclusion cannot be pinned today is that the supersession fence
is not global. `cell.data.delta.TagState` records `notice.supersedes` into its
own `deadSources` set and thereafter filters tags of those sources out of
`apply`; `cell.data.OrMapCell`'s re-baseline KDoc states the consequence in as
many words — the fence is *replica-local*, binding only the replicas that
actually processed a notice, so a dot of a superseded source held by a peer
that never saw one stays live there and can still be gossiped onward as new
information. Excluding the column from the MIN would authorize reclamation
([KE3-30]'s precondition) in exactly the window that straggler can arrive in.
Closing it needs the notice to reach every replica as data — a fenced-source
lattice on the gossip mesh, which does not exist (96 §E1 follow-on) — and the
alternative of gating exclusion on the `ReBaseline` itself being causally
stable (95 §R14 direction 1) presupposes the very stability read this section
defines. Neither the set family (whose `applyReBaseline` forwards the notice
downstream transitively) nor the map family closes the window on its own, and
`cell.data.WatermarkCell` has no supersession vocabulary at all: its rows are
grow-only in every lane and no column is ever removed.

The practical consequence for the reclamation work is a precondition, not a
rule: stability-scoped reclamation (96 E3.7) ships gated on **no superseded
column present** for the id being reclaimed.

**FRM1 BS-7 — the two-hop upstream cut (G-36).** FRM1's BS-7 configuration is
`A → M → D` with `M` absorbing and `D` also fed by `B`; the link `A → M` is cut
while `D` waits on the `M` arm for wave `t`. It expects a **counterexample**,
because every metadata-plane notice is single-hop (91 §G-36) and so no notice
of the cut reaches `D` through `M`.

The delivered-watermark lattice **does not currently carry transitive progress
for the wave plane** and is not offered here as G-36's missing channel. A
`WatermarkCell` row advances only from its own replica's `DeliveredFrontier` /
outlet tap; nothing writes a row on behalf of an upstream hop, and the mesh
carries per-replica delivery, not per-hop wave progress. The open question this
section records, and does not answer, is whether the two-hop cut leaves a
`deliveredThru` column pinned forever, and if so whether that freezes the
stability read the way an unclean departure does ([42-WM-08]) — because if it
does, the `Stall`-family notice named there must cover that cause too, and the
notice is currently specified for departure only.

This paragraph is a **stated unknown**, not a finding: FRM1 (`computenet-7fe`)
is open at the time of writing and its BS-7 trace has not been produced. When
it closes, the recorded trace in `doc/formal/findings.md` replaces the
prediction above; it does not by itself resolve the interaction, which stays
open until either G-36 gains a transitive channel or [42-WM-08]'s notice is
widened. Neither disposition adopts this lattice as that channel.

## Decided in 93, not yet built

The feature-interaction analysis settled the following for this layer; all of
it is decided design, none of it is code.

- **Topology follows the mutability class** (decided in 93 I-3): one
  membership-reactive linker, parameterized by class — mergeable (`Replicable`)
  cells form the symmetric full mesh above; single-writer cells form an
  asymmetric leader→follower tree in which every follower serves its own write
  inlet by a command-forward `delegate` to the leader (14) — forwarded, not
  applied locally (§Single-writer replication).
- **Gossip convergence is tag-carried, not wave-carried** (decided in 93 I-14):
  merge tags are data — minted once at the genuinely new local add, then
  carried verbatim by gossip, snapshot, and catch-up; a cell MUST NOT re-mint
  tags for state it received. A `Replicable` cell re-emitting an effective
  post-merge delta is a wave **origination** point (93 I-14 Rule S4): it MUST
  mint a fresh wave from its own outlet. Replicas are thus independent wave
  sources (convergence, not simultaneity) and no source id circulates around
  the mesh cycle — closing this file's former open item on reusing wave
  context or OR-set ids as causal metadata.

- **Exclusive payloads are pipeline currency, never shared-state currency**
  (decided in 93 I-20): a replicated cell with an `Owned`/`Leased` data inlet
  (20/23) consumes its own local exclusive inputs to advance local state;
  replicas converge via the Frozen/mergeable delta gossip above — deltas are
  effective-only immutable forms, always fan-out-safe. Exclusive-payload cells
  are therefore replicable through the ordinary delta path, not a
  non-replicable class; only the exclusive edge itself is unshared (a local
  pipeline segment — an `Owned` value that move-by-serializes is
  single-location by ownership semantics, not by a missing feature).
- **Replica placement is color-filtered** (decided in 93 I-15): candidate
  hosts are restricted by the same `admits(hostColor, cellColor)` predicate
  every spawn-admission gate evaluates (30/32). Near-vacuous today: the
  replicable/mergeable class is pure (tag/counter algebra only), so its
  replicas admit on any host; the filter bites only if a marked
  blocking/suspending cell is ever made replicable, in which case all its
  replica hosts must match its single marker color.
- **Eviction is a gated drain+despawn** (decided in 93 I-3, **built W3.3**:
  `Replication.evict`): membership-gated and drain-gated, no ack protocol. If
  no peer in `replicasOf(id) − {local}` is reachable, the replica MUST
  suspend (park, `HostManagementApi.suspend`) rather than evict — it may hold
  unique un-gossiped state; await heal (a later re-announce that grows
  `replicasOf` back above one resumes it automatically). Otherwise: intake
  closes (spec 33's drain, applied at cell instead of host granularity —
  every effective delta already streamed to peers as it was produced, so
  nothing buffered needs an extra flush; in-flight deltas to a momentarily
  closed peer still park-and-replay as usual), a final state-as-delta
  catch-up (21) re-fires at one reachable peer's existing link, then
  despawns ⇒ unpublish ⇒ `RegistryAnnounce.unpublished` tells peers, whose
  linkers reconcile by dropping the now-stale outbound gossip link. The
  trigger (sustained attention band NONE, 34 — or manual) is wiring; when to
  fire it stays with the economic layer (G-62).
- **Integrity among untrusting replicas** (decided in 93 I-28): a boundary MAY
  declare `RequireSigned` for inbound replica gossip; ingress then verifies a
  signature over (contract, method, payload, minting peer, per-source counter)
  before delivery and dead-letters failures. Verify-at-ingress,
  drop-and-reconverge is safe precisely because `Replicable` merges are
  idempotent — a later re-gossiped signed copy converges; the per-source
  counter defeats replay. Signing is per emitting peer (instance/host key),
  never per logical cell. No ack, no round-trip: replication keeps "no second
  sync protocol" (policy vocabulary: 40/43).

⚠ GAP (G-45, narrowed W3.3): the gossip-mesh skeleton still lacks its
liveness/churn **argument** — membership-churn reconvergence is unproven —
and graceful last-replica handoff to durable storage is undesigned. The gate
half is built (above: suspend-when-partitioned, drain-gated despawn, peer
reconciliation) and the convergence-invariant harness now carries a
departed-stream rule (`cell.verify.ReplicaConvergence`, 50/52), so a
replica's orderly departure no longer false-positives a live divergence.
[42-REPL-06] IF a replica departs orderly (evict/despawn) while its peers keep
accepting writes, THEN the surviving replicas SHALL still converge and the
departed replica's frozen final stream SHALL NOT be counted as a divergence.
Proposal: state the bounded-gossip-hop reconciliation argument
(duplicate/stale mesh links safe by tag idempotence) with a generative
membership-churn harness (R1, 95); define graceful
last-replica handoff to durable storage (G-25) vs accidental deletion.

## Single-writer replication (decided in 93 I-25, not built)

Primary-backup log-shipping over the same mesh machinery, minus the symmetry:
no consensus, no lease, no leader registry, no second sync protocol. When
built, this supersedes M7.3's interim posture (single-writer cells refuse
replicated spawn). The decided rules:

- **Leader = the single applying instance and single wave source.** Exactly
  one instance serves the write inlet with the real implementation; the host's
  single-consumer queue (31) is the serialization point. Followers serve their
  write inlet with a command-forward `delegate` to the leader — a write
  landing on a follower is redirected, not rejected. Type-determined
  exceptions (20/23): an `Owned` payload forwards by move-by-serialize; a
  `Leased` payload cannot cross a machine boundary, so an off-leader Leased
  write is `Rejected`.
- **One-direction shipping, follower FIFO apply.** `leader.deltaOutlet` links
  to each follower's `deltaInlet` learned from `replicasOf(id)`; followers do
  not gossip back. The leader is a single wave source, so its delta stream is
  totally ordered and followers apply in per-link-FIFO order (31) — no merge
  function needed, which is exactly why a non-idempotent cell can replicate
  this way when the mesh would double-count. Followers serve reads from this
  warm state.
- **Leadership is a `LeaderMark` epoch fold.** `LeaderMark(id, epoch,
  leaderRef)` is one more announcement kind folded into the same
  eventually-consistent membership index; `leaderOf(id)` = the mark with max
  reachable epoch. Every leader stamps its produced deltas with the epoch it
  applied under; deltas or commands stamped below the current epoch are fenced
  (inert), and a leader that folds a strictly greater epoch steps down to a
  command-forwarding follower and catches up from the winner.
- **RESTART = peer catch-up, not checkpoint trust.** RESTART preserves
  `instanceId`, so the leader's ref, links, and `LeaderMark` all survive — no
  re-election, no relink. The recovered leader MUST re-catch-up from the
  most-advanced reachable follower (the shipped late-join path, 21) rather
  than treat its stale spawn-time checkpoint as authoritative; checkpoint
  restore is the solo fallback when no follower is reachable. Writes served
  but not yet shipped to any follower at failure are lost — the stated async
  primary-backup window; shrinking it (ack-from-k) is explicitly not adopted.
- **Always-fenced writes — no per-cell posture.** Each partition's leader
  keeps serving; on heal the higher epoch wins, the loser steps down and
  re-catches-up, and its divergent post-partition writes are surfaced on the
  error/dead-letter path, never silently dropped. An opt-in `SAFETY_PARK`
  posture — a leader that cannot confirm it is un-superseded parks writes
  instead of serving divergent ones, trading availability for no split-brain
  and no loss — **was specified, never implemented**: the `WritePosture` enum
  and both its values (`AVAILABLE_FENCED`/`SAFETY_PARK`) had zero production
  installs and were deleted (remediation T03). It can be reintroduced with
  its first real user — see `91-gap-analysis.md` G-67.
- **Failover is explicit by default.** Leader death (ref unpublishes) parks
  writes until a successor is designated by promotion (53); an opt-in posture
  lets a follower claim leadership by minting a higher epoch and
  re-announcing — a deterministic epoch bump folded from ordinary
  announcements, not a vote.

⚠ GAP (G-43): RESTART's restore-freshest-checkpoint + generation-stamped
re-baseline leaves precedence and cost open — supersede vs concurrent
multi-source remove, re-baseline cost under wide fan-out, hybrid push/pull
direction, poison-write loops, and the recovery-cell pattern are
unstandardized. Proposal: state a supersede-vs-remove precedence with a
generative convergence test; bound the push-authoritative re-baseline
(diff-against-last-acked / delta-since-generation); define the per-cell
direction policy for hybrid derivation+owned-state cells; add a poison-write
escape (dead-letter the replaying write after N RESTARTs); standardize the
deadLetter→requestState recovery cell — replicated cells re-baseline from mesh
peers, resolving the RESTART-within-replication question carried by four
earlier challenges (93 I-22/I-2/I-7/I-18/I-19/I-25).

⚠ GAP (G-44): single-writer replication (leader→follower log-shipping) defers
its liveness half — no automatic leader election, no failure detector, and
split-brain reconciliation beyond last-epoch-wins is undesigned. Proposal:
opt-in epoch-claim election folded from the eventually-consistent membership
index with a stated convergence/liveness bound and a generative leader-churn
harness; a failure-detection window that does not become a second heartbeat
protocol; an application-level reconciliation hook for fenced divergent
writes; an optional ack-from-k durability tier; and per-shard leader routing
when partitions replicate (93 I-25/I-2/I-3/I-8).

⚠ GAP (G-67): the leader/follower split specified an opt-in
`WritePosture.SAFETY_PARK` (a leader that cannot confirm it is un-superseded
parks writes instead of serving divergent ones); `WritePosture` and both its
values (`AVAILABLE_FENCED`/`SAFETY_PARK`) had zero production installs and
were deleted (remediation T03) — every leader today runs the always-fenced
behavior unconditionally, with no per-cell posture switch. Proposal: not a
redesign — the always-fenced default stays exactly as landed; an opt-in
`SAFETY_PARK` posture (and the follower-unpark rule it would need) can be
reintroduced with its first real user once a concrete case needs
park-on-uncertainty over the fenced-and-reconcile default.

## Constraint on everything else

Nothing in layers 10–30 may assume a cell has exactly one live instance,
except where single-writer is declared. This is already respected by the
current design (refs, links, and invocations are instance-agnostic), and MUST
be preserved as G-8 (ref model) is implemented.

## Scatter-gather pull over an instance set (PN-5)

A pull against a *partitioned* logical id has no single answerer. A router
serving it from a total-interest ledger of its own would hold O(total state) at
one node — the very thing partitioning exists to avoid. So a pull **fans out**:
the router sends a state request to every instance whose interest overlaps the
requester's scope, and each instance answers **its own slice** with **its own
frontier**. The requester unions the disjoint-key slices into the board. No
node ever materializes the whole board; each leg is bounded by one shard's
range (O(shard-count) legs, never O(total) at one place).

**Freshness contract — per-shard-consistent, cross-shard-arbitrary.** Each leg
is internally consistent: a shard's slice is a coherent snapshot of *its* range
at *its* frontier. Across shards there is no joint cut — legs are independent
and may reflect different moments, and a shard that is unreachable (mid-
migration, its ref held on the funnel) simply defers its leg to a later pull.
This is honest and sufficient because **a baseline is never a wave**: a leg
carries `MessageContext.baseline` (a catch-up frontier), not a live wave, so it
is excluded from wave-completeness and imposes no cross-shard ordering
obligation the union could violate. Glitch-freedom is a within-source property
(spec 20/22); a scatter-gather board, assembled from disjoint sources, never
promises a single global wave and so cannot be torn by the absence of one.

**Per-instance retained currency.** The requester retains the frontier it has
caught up to **per instance** it pulls from (`RetainedFrontiers`), never one
frontier merged across instances. Shard holdings of a shared upstream source
are non-contiguous by construction (instance A holds counters {1,3,5}, B holds
{2,4}); a single pointwise-max `since` merged across instances carries A's high
water into B's next incremental request, so B reports its own unseen counters as
already-seen and silently drops them — most visibly for a deferred (migrated)
shard pulled for the first time under a sibling's accumulated water. A
per-instance `since` is monotone within its instance and never contaminated by a
sibling's progress, so an incremental scatter-gather pull returns exactly the
tags that instance has not yet delivered.

## One linker, one assignment: interest-scoped instance sets (PN-6)

PN-4 gave a shard state, durability and a pull answerer; PN-5 made the pull
cross the wire. PN-6 closes the substrate: the gossip linker and the shard
router are recognized as **one** slice-and-route mechanism, the interest
assignment becomes a **durable, addressable lattice**, and the router stops
holding O(total) state on its correctness paths.

**One linker (`sliceTo` + `keyOf`).** Replication and partitioning were already
"two settings of one knob" (above); PN-6 makes them literally one function.
`sliceTo(delta, interest, keyOf)` is the single primitive: restrict a delta to
the sub-delta an interest admits over a key projection. The gossip linker
(`Replication.maybeLink`) calls it with `keyOf` = identity — in a replica mesh
the element *is* the key; the shard router (`PartitionedShardSet`) calls it with
`keyOf` = the group key. The linker generalizes on `keyOf` so the *same* linker
serves a partitioned substrate; there is no second slice mechanism to keep in
step. A link forms exactly where two instances' interests overlap — one link per
overlapping ordered instance pair, and nothing else.

**One assignment (a hosted `Replicable` max-register).** Each instance's
`(interest, epoch)` is an entry in an `InstanceSet`: a per-instance max-register
lattice. Its merge is the **admission rule**, applied everywhere — a *newer*
epoch is adopted; an *older* epoch is dropped (it cannot resurrect a shed range,
whose absence the current higher-epoch interest already reflects); an *equal*
epoch takes the commutative join (a canonical union) of the interests. The
register is a proper join-semilattice value — commutative, idempotent,
associative — so the table converges regardless of delivery order, and it
gossips as an ordinary `Replicable` over the existing mesh (no second protocol).
A non-commutative merge is the negative control: it forks the assignment by
delivery order, and dropping control-plane frames while data flows
half-applies a flip so the board forks — the merge and the control plane are
both load-bearing.

**Assignment is journaled, ref-addressed, replayed (the payoff).** Before PN-6
a router narrowed a shard's interest by a direct in-process method call. That
call was invisible to the shard's write-ahead log, so a *non-checkpointed*
shard's shed was invisible to recovery: replaying its routed frames under its
reconstructed (wide) interest re-admitted the range it had shed (PN-4's
residual — durable only when the shard happened to have been checkpointed). PN-6
sends the reassignment as an `Assignment` carried on an ordinary **hosted
invocation to the shard's `assignInlet`**, addressed by `CellRef` over the
registry — in-process or across a bridge, the same transport the routed slices
take. Because it flows through the host intake it lands in the shard's WAL and
**replays on recovery**: a shard reconstructed with its original wide interest
still re-performs the shed when the journaled assignment replays. The interest
algebra therefore crosses the wire — every `Interest` arm is a registered
serializable value. `RoutedCommand.epoch` is thereby demoted to a decorative,
**deprecated** field: admission checks the shard's *current* interest, never the
payload epoch; the wire codec stops sniffing it onto `WireFrame.routingEpoch`
(the frame field is retained and old frames still decode for one release).

**Router state is O(instances) on its correctness paths.** Routing consults
only the per-instance interest table; a pull fans a request to each
interest-overlapping shard (PN-5). The in-process `PartitionedCell` no longer
keeps a `routed` ledger at all — a repartition sources its replay from the
shards' own contents. The distributed `PartitionedShardSet` retains a **scoped**
ledger for one reason only: a repartition replay must be complete and
synchronous even while routed slices are still in flight across a bridge to a
shard, and a snapshot gathered from the shards' own async-lagging contents would
miss those in-flight elements. No routing or pull path reads it; it also backs
the PN-5 control-b anti-pattern (answering a whole pull from one node).

**Coverage invariant and the single routing authority (R1).** A reassignment
must not shrink an instance's interest until the gainer covers the shed range;
during a flip the moving range is buffered (per-range, funnel rule) and the old
owner keeps serving until the gainer has the range, so the window loses and
double-counts nothing (verified over bridged repartition racing a migration,
100 seeds). The flip window keeps a **single routing authority** for the moving
range — the router that opened it. A fully **leaderless** instance set, where
any peer may reassign concurrently and the max-register alone arbitrates without
a single authority per flip window, is **R1 — out of scope here**; it needs the
watermark-gated, mesh-sourced replay that would also retire the scoped ledger
entirely, and is deferred.
