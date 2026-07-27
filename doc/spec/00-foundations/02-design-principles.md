# 02 — Design Principles

> **Status**: Specified
> **Sources**: ADR 0, ADR — Computelet Kernel, ADR — Computelet Mobility, ADR — Glitch Freedom
> **Implementation**: these principles are *enforced by review*; they are the acceptance criteria for every other spec section

These are the constraints that recur across every ADR. Any proposal that
violates one of these must either be rejected or explicitly amend this document.

## P1 — Concurrency-free logical kernel

The kernel models **cells, ports, links, and invocations only**. Concurrency,
queues, scheduling, and distribution are layered on top by Hosts.
*Rationale*: earlier designs that entangled computelet behavior with queues and
runner orchestration became impossible to reason about; suspension and
migration must reduce to **link manipulation**, not bespoke protocols.

Test: any kernel-level statement must be meaningful in a single-threaded
simulation.

## P2 — Near-zero-cost fast path

Steady-state message flow pays for almost nothing: a volatile read and an
enqueue when crossing hosts; a direct method call within a host. Rare
operations (linking, migration, suspension, invalidation, re-resolution) may be
arbitrarily expensive.
*Corollaries*: allocate off the critical path, prefer in-place mutation of
owned data, minimize stack depth and async boundary hops, fuse where possible.

## P3 — Explicit topology

Connectivity is first-class: links are objects, ports are named and typed, the
graph is inspectable and mutable at runtime. Address-based messaging into
hidden mailboxes (the actor pattern) is rejected precisely because it hides
topology.

## P4 — Local, compositional consistency

Consistency guarantees (glitch-freedom, causal buffering, convergence) are
**opt-in per cell** and compose across boundaries. There is no global
propagation lock, no global clock, and no global barrier. Inside a boundary,
stronger assumptions are allowed; across boundaries, asynchrony and
incrementality dominate.

## P5 — Correctness by construction

Prefer encoding rules in types and structural boundaries over runtime
discipline: execution colors as types, ownership contracts as payload types,
cardinality enforced at link time, port contracts as plain interfaces.

## P6 — Interest drives resources

Computation, replication, and network topology follow expressed interest
(subscriptions). Nothing runs, replicates, or connects "just in case".
Self-interest over altruism: peers compute what is locally useful.

## P7 — Open and local-first by default

Default to sharing and to processing data where it is needed. Security,
privacy, and encryption are **controls applied at boundaries** (membranes,
policies), not assumptions baked into the core.

## P8 — Live evolution

Every structure — cells, links, implementations, even consistency topology —
can change at runtime. Deployment, versioning, and rollback are graph
operations. Nothing in the kernel may assume a static graph.

## P9 — Serialization-friendly everywhere

Anything that crosses a boundary (invocations, state transitions, deltas) must
have a stable serializable form. Lambdas, `java.lang.reflect.Method` handles,
and classloader-dependent artifacts are forbidden on the wire.

## P10 — Niche first

When a trade-off arises, decide it in favor of decentralized, incremental,
long-lived, interest-driven systems — the niche — rather than generic
throughput benchmarks or feature parity with existing silos.

## How principles interact

- P1 + P2 together produce a **conceptual** layering: kernel (logic) → host
  (queues, colors) → distribution (proxies, replication). This is a layering
  of responsibilities, not of package dependencies — the kernel package graph
  is not a DAG along this axis (all 20 non-leaf `civictech.cell.*` packages
  form one strongly-connected component; see 90/91 G-63 for the divergence
  and the two cheap first cuts on the table).
- P3 + P8 make mobility tractable: because links are explicit, suspension =
  unlink-all, migration = unlink/move/relink (see 30/33).
- P4 + P6 shape the consistency story: glitch-freedom frontiers instead of
  global locks (see 20/22).
- P5 + P9 shape connectivity: typed port contracts whose calls are captured as
  serializable invocations (see 10/14, 40/41).
