# 42 — Interest-Driven Replication

> **Status**: Exploratory (vision fixed; mechanism undesigned)
> **Sources**: ADR 0 (§5, §6), ADR 1 (§11, §12), ADR — Cellular Software Development Process (runtime characteristics)
> **Implementation**: none

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

## Open design (⚠ GAP G-7)

- Replica discovery & membership (who else replicates this logicalId?) —
  likely the same location registry as 33/41, extended from "one location"
  to "location set".
- Upstream responsibility: the formal meaning of "volunteering partial
  responsibility" — candidate: subscribing peers accept links for a share of
  upstream partitions (24) proportional to interest.
- Causal tagging prerequisites: replica convergence needs delta tags (G-23)
  — OR-set-style ids or wave-context (22) reused as causal metadata.
- Anti-entropy for partition/offline recovery (snapshot + delta replay — the
  same catch-up protocol as 21's late-join).

## Constraint on everything else

Nothing in layers 10–30 may assume a cell has exactly one live instance,
except where single-writer is declared. This is already respected by the
current design (refs, links, and invocations are instance-agnostic), and MUST
be preserved as G-8 (ref model) is implemented.
