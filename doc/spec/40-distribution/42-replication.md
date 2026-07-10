# 42 — Interest-Driven Replication

> **Status**: Implemented for the mergeable set family (M7); leader/follower and keyed structures open
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

Still open: upstream responsibility (subscribing peers accepting shares of
upstream partitions, with 24's partitioned cells); interest-driven replica
*placement* (34's economic layer); replica eviction (suspension via
attention works today, M7.5; eviction stays manual until memory pressure
justifies a policy); leader/follower for single-writer cells.

## Constraint on everything else

Nothing in layers 10–30 may assume a cell has exactly one live instance,
except where single-writer is declared. This is already respected by the
current design (refs, links, and invocations are instance-agnostic), and MUST
be preserved as G-8 (ref model) is implemented.
