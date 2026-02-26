# Status

Proposed

# Context

The Cellular Software Development Process introduces Cells as the primary unit of construction and evolution in software systems. While ADR-0001 describes the development lifecycle and runtime model, a clear structural understanding of what a Cellular program consists of is required.

This ADR defines the anatomy of Cellular programs:
- what a Cell is structurally,
- what roles ports and policies play,
- why membranes are introduced as a conceptual and architectural layer,
- and what trade-offs exist between different modeling approaches.

The goal is not biological imitation, but a software architecture that enables:
- strong encapsulation,
- explicit interaction constraints,
- compositional scalability,
- runtime evolution,
- and safe integration of independently developed components.

⸻

# Decision

A Cellular program is modeled as a hierarchy of Cells that interact through Ports, governed by Policies, and conceptually separated from their environment through a Membrane.

The anatomy is defined in layers:
1.	Cell — unit of state and computation.
2.	Ports — typed crossing points for data and control flow.
3.	Policies — constraints governing interaction and linking.
4.	Membrane — boundary-level coordination and enforcement across ports.

The membrane is treated as a conceptual first-class concern, while the exact implementation may vary (embedded within the cell or separable at runtime).

⸻

# Core Concepts

## Cells

A Cell is:
- a container of state,
- a locus of logic and incremental computation,
- a unit with serializable state transitions,
- a participant in a larger network via explicit ports.

Cells are the primary abstraction developers reason about.

### Key characteristics
- Internal state is owned exclusively by the Cell.
- External mutation is impossible except via ports.
- Internal implementation may change while preserving external contracts.
- Cells may contain other Cells (hierarchical composition).

### Design motivations
- Establish clear ownership boundaries.
- Enable local reasoning and consistency.
- Support incremental propagation and stable identity.

⸻

## Ports

Ports are the explicit crossing points between Cells.

A port defines:
- direction (input/output/bidirectional),
- data schema and delta semantics,
- multiplicity (one-to-one, fan-in, fan-out),
- local validation rules.

Ports are intentionally explicit rather than mailbox-based.

### Motivations
- Make topology visible and controllable.
- Allow safe linking and runtime introspection.
- Enable static or runtime compatibility checks.
- Support advanced interaction styles such as:
  - uniport-like flows (single direction),
  - symport-like coupled updates,
  - antiport-like backpressured exchanges.

Ports are not just communication endpoints; they define permitted forms of interaction.

⸻

## Policies

Policies constrain how ports may be used.

Examples:
- authentication or capability checks,
- rate limits,
- link authorization,
- schema compatibility,
- resource quotas.

Policies may exist:
- at individual ports,
- or at higher boundary layers (see membrane).

### Motivation

Without policies, explicit ports still permit uncontrolled connectivity. Policies ensure:
- controlled composition,
- safety in decentralized environments,
- predictable behavior under load.

Policies turn links into negotiated relationships instead of arbitrary connections.

⸻

## Membrane

The membrane is the conceptual boundary separating a Cell from its environment.

It models the idea that:

crossing into a Cell is governed by boundary rules, not just individual port definitions.

The membrane introduces:
- boundary-level reasoning,
- cross-port coordination,
- unified enforcement of interaction rules.

What membranes add beyond ports

Ports are local. Membranes enable:
- cross-port invariants,
- coupled flow constraints,
- global resource accounting,
- consistent security and authority enforcement,
- link negotiation and lifecycle control,
- boundary-wide observability.

Examples:
- accepting input on one port only if another condition holds,
- enforcing backpressure relationships between ingress and egress,
- atomic multi-port transitions,
- unified tracing and replay at the boundary.

⸻

# Competing Conceptual Models

Two conceptual models were considered.

⸻

## Model A — Anatomical Model

```
Cell {
    Membrane
    Ports
    State
    Logic
}
```

The membrane is part of the Cell.

Advantages
- Highly intuitive for developers.
- Mirrors familiar biological analogy.
- Simpler documentation and mental model.
- Natural API-centric design.

Disadvantages
- Membrane behavior may become entangled with internal logic.
- Harder to reason about boundary concerns separately.
- Less flexibility for runtime adaptation.

---

## Model B — Boundary-First Model

```
Membrane
    defines inside/outside
    governs crossings

Cell internals exist behind membrane
```
The membrane conceptually precedes the Cell.

Advantages
- Security and policy become orthogonal to logic.
- Allows membrane replacement or adaptation without rewriting internals.
- Encourages clean separation of concerns.
- Enables runtime wrappers, compatibility layers, and protocol adapters.

Disadvantages
- Less intuitive to newcomers.
- Can appear overly abstract if exposed directly.

⸻

# Decision

Adopt a dual-perspective approach:
- Developers reason using the anatomical model: membranes are part of cells.
- Runtime architecture treats membranes as separable concerns where beneficial.

This preserves intuition while enabling advanced capabilities.

⸻

## Subtle Motivations for Membranes

Membranes are introduced not for terminology, but because they solve recurring architectural problems.

1. Boundary as authority

Security and permissions belong to boundaries, not business logic.

2. Boundary as causal scope

Inside a boundary:
- stronger consistency assumptions are possible.

Across boundaries:
- asynchronous and incremental interactions dominate.

3. Boundary as evolution surface

Cells may change internally while membranes preserve compatibility.

4. Boundary as observability layer

Tracing and replay are naturally defined at crossings.

⸻

# Hierarchy and Composition

Cells may contain subcells.

This implies nested boundaries:

```
Cell
  └── Organelle Cell
        └── Organelle Cell
```

Each layer:
- defines its own ports,
- maintains local invariants,
- may hide or expose internal ports.

This enables scalable modularity through recursive encapsulation.

⸻

## Interaction Patterns

The anatomy supports several higher-level interaction styles.

## Independent flow

Simple one-way propagation.

## Coupled flow (symport-like)

Multiple flows must occur together or satisfy joint conditions.

## Exchange flow (antiport-like)

Ingress and egress are linked through backpressure or conservation constraints.

These patterns are expressed through policies and membrane rules rather than hardcoded protocols.

⸻

## Identity and Persistence

A Cell’s identity is defined by:
- its boundary contract,
- invariants,
- and persistent continuity of behavior,

not by its internal implementation.

This allows:
- internal replacement,
- hot upgrades,
- evolutionary deployment strategies.

⸻

# Consequences

Benefits
- Strong encapsulation with explicit interaction rules.
- Clear separation between internal logic and external contracts.
- Security and observability become structural.
- Supports dynamic runtime evolution.
- Enables recursive compositional architectures.

Trade-offs
- Additional conceptual layer beyond traditional modules.
- Requires robust tooling for boundary visualization.
- Developers must reason in terms of interaction contracts, not just functions.

⸻

# Alternatives Considered

## Pure actor-style model

Rejected because:
- implicit mailboxes hide topology.
- insufficient control over interaction contracts.

## Pure dataflow model

Rejected because:
- lacks ownership boundaries.
- difficult to enforce authority and modularity.

## Ports-only model (no membrane concept)

Rejected because:
- fails to model cross-port relationships.
- scatters boundary logic across many ports.

⸻

# Non-goals

This ADR does not define:
- exact runtime representation of membranes,
- scheduling models,
- serialization protocols,
- specific security implementations.

⸻

# Summary

The anatomy of Cellular programs is defined by a layered structure:
- Cells provide stateful computation and identity.
- Ports define explicit crossing points.
- Policies constrain interactions.
- Membranes coordinate and enforce boundary-level behavior.

Developers primarily reason in terms of Cells, while runtime architectures may leverage membrane-level separation to enable security, observability, evolution, and compositional scalability.

This anatomy establishes the structural foundation for Cellular systems that are modular, adaptive, and safe to evolve over time.