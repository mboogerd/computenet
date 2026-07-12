# 42 — Interest-Driven Replication

> **Status**: Implemented for the mergeable set family (M7); single-writer leader/follower design decided, unimplemented (§Single-writer replication); keyed structures open
> **Sources**: ADR 0 (§5, §6), ADR 1 (§11, §12), ADR — Cellular Software Development Process (runtime characteristics)
> **Implementation**: `cell.replication.Replication` (symmetric gossip-mesh
> linker over registry announcements), `SetCell.deltaInlet` + tombstoned
> OR-set state, `LocationRegistry.onPublish`/`replicasOf`, multi-peer
> `Peering` with `Loopback.partition()/heal()`. Verified:
> `ReplicationTest`, `ReplicatedSessionTest` (3 peers, 100 seeds,
> partition+heal, divergence control)

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
   / hold warm state).
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
- **Tombstones**: multi-path delivery means a removed tag can arrive late by
  another route; `SetCell` therefore keeps del-tags (full OR-set). Tag sets
  grow monotonically; compaction is future work alongside durability (G-25).
- **Anti-entropy**: partition ⇒ Remote locations drop ⇒ gossip parks
  (spec 33); heal ⇒ re-announce ⇒ parked replay + idempotent catch-up.
  Verified, not rebuilt — the late-join path (21) doubles as recovery.
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
- **Eviction is a gated drain+despawn** (decided in 93 I-3): membership-gated
  and drain-gated, no ack protocol. If no peer in `replicasOf(id) − {local}`
  is reachable, the replica MUST suspend (park) rather than evict — it may
  hold unique un-gossiped state; await heal. Otherwise: drain (33) closes
  intake and flushes the delta outlet to surviving peers (in-flight deltas to
  a momentarily closed peer park-and-replay), optionally pushes a final
  state-as-delta catch-up (21) to one reachable peer, then despawns ⇒
  unpublish ⇒ peers' linkers reconcile. The trigger (sustained attention band
  NONE, 34 — or manual) is wiring; when to fire it stays with the economic
  layer (G-62).
- **Integrity among untrusting replicas** (decided in 93 I-28): a boundary MAY
  declare `RequireSigned` for inbound replica gossip; ingress then verifies a
  signature over (contract, method, payload, minting peer, per-source counter)
  before delivery and dead-letters failures. Verify-at-ingress,
  drop-and-reconverge is safe precisely because `Replicable` merges are
  idempotent — a later re-gossiped signed copy converges; the per-source
  counter defeats replay. Signing is per emitting peer (instance/host key),
  never per logical cell. No ack, no round-trip: replication keeps "no second
  sync protocol" (policy vocabulary: 40/43).

⚠ GAP (G-45): the gossip-mesh skeleton lacks its liveness and churn arguments
— membership-churn reconvergence is unproven, shutdown of the last connected
replica is unpoliced, and convergence invariants can false-positive on
replicas that legitimately depart mid-run. Proposal: state the
bounded-gossip-hop reconciliation argument (duplicate/stale mesh links safe by
tag idempotence) with a generative membership-churn harness; define graceful
last-replica handoff to durable storage (G-25) vs accidental deletion; and
give cross-replica convergence invariants a departed-stream rule (93 I-3).

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
- **`WritePosture` split, declared per cell.** `AVAILABLE_FENCED` (default):
  each partition's leader keeps serving; on heal the higher epoch wins, the
  loser steps down and re-catches-up, and its divergent post-partition writes
  are surfaced on the error/dead-letter path, never silently dropped.
  `SAFETY_PARK` (opt-in): a leader that cannot confirm it is un-superseded
  parks writes — no split-brain, no loss, unavailable for writes by design.
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
its liveness half — no automatic leader election, no failure detector, no
follower-unpark rule under SAFETY_PARK, and split-brain reconciliation beyond
last-epoch-wins is undesigned. Proposal: opt-in epoch-claim election folded
from the eventually-consistent membership index with a stated
convergence/liveness bound and a generative leader-churn harness; a
failure-detection window that does not become a second heartbeat protocol; a
witness-set-superset unpark rule for SAFETY_PARK; an application-level
reconciliation hook for fenced divergent writes; an optional ack-from-k
durability tier; and per-shard leader routing when partitions replicate
(93 I-25/I-2/I-3/I-8).

## Constraint on everything else

Nothing in layers 10–30 may assume a cell has exactly one live instance,
except where single-writer is declared. This is already respected by the
current design (refs, links, and invocations are instance-agnostic), and MUST
be preserved as G-8 (ref model) is implemented.
