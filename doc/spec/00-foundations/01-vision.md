# 01 — Vision

> **Status**: Specified (as a vision; not code)
> **Sources**: ADR 0, ADR 1, ADR — Cellular Software Development Process
> **Implementation**: n/a (this document constrains everything else)

## The problem

Modern systems are assembled from siloed tools — one for messaging, one for
streaming, one for storage, one for computation, one for state synchronization.
Each is good at its narrow concern; the integration burden lands on the
developer. In decentralized settings the burden is worst: partial state,
inconsistent execution semantics, and coordination overhead make decentralized
applications notoriously hard to build.

## The bet

ComputeNet bets that **one coherent abstraction — a live, collaborative dataflow
graph of Cells — can absorb messaging, computation, and state** for a specific
niche: **decentralized applications that do not want blockchain-style global
consensus**. Coherence, locality, responsiveness, and openness are preferred
over rigid consensus or central coordination.

Generality is pursued *within* the niche, not instead of it ("niche first,
generality second"). Target applications include UI state management,
collaborative editing, federated analytics, decentralized social networks,
distributed knowledge graphs, and hybrid client/server apps.

## What the graph is

A ComputeNet program is a graph in which:

- **Cells** own state and logic, and interact only through explicit **Ports**.
- **Links** connect ports; the topology is explicit, inspectable, and mutable
  at runtime.
- Data propagates **incrementally** (deltas) by default; complete-value
  propagation is the special case for small bounded data.
- Execution is **interest-driven**: subscribing to a result implies partial
  responsibility for computing its inputs; resources follow attention.
- The graph is **long-lived and continuously evolving**: new cells, links, and
  implementations are integrated into a running system, the way knowledge is
  integrated into an organization — not the way binaries are released.

## What success looks like

1. A developer builds a responsive, collaborative application against a single
   mental model (cells + ports + deltas) without separately operating a broker,
   a database, and a compute framework.
2. The same program runs unchanged whether its cells are co-located on one
   thread, spread across JVMs, or spread across machines owned by different
   people (location transparency).
3. Peers replicate the parts of the graph they care about; network topology
   emerges from shared interest rather than static configuration.
4. Systems are verified by **invariants** that hold under live data, and evolve
   by promoting implementations that satisfy them (deployment as evolutionary
   selection).

## The deeper ambition

Beyond mechanics, the framework aims to support **distributed cognition**:
graphs of cells as evolving shared models of belief and intent, enabling
"mental alignment" across people and machines. This ambition does not change
the kernel design, but it explains the emphasis on openness, live evolution,
and interest-driven structure.

## Inspirations (and what is taken from each)

| Domain | Lesson taken |
|---|---|
| Reactive frontend frameworks | responsiveness, composition |
| Kafka-style streaming | append-only mechanical efficiency |
| RocksDB / Cassandra | locality, batching, partitioning |
| Distributed ledgers | eventual consistency among untrusting peers |
| HFT systems | minimal critical path, ownership discipline |
| Game engines | real-time coordination of compositional complexity |
| LASP / Differential Dataflow | delta-driven distributed data structures |
| Membrane computing (P systems) | boundaries, uniport/symport/antiport interaction styles |

## Non-goals

- Global total ordering or blockchain-style consensus.
- Being a general-purpose replacement for every database or message broker
  outside the decentralized niche.
- Mandatory global time protocols; looser convergence-oriented models are
  acceptable wherever deterministic global ordering is unnecessary.
