# ADR: Computelet Kernel

## Context

The dataflow graph framework requires a clear and minimal foundation on which higher-level constructs (typed graphs, runners, concurrency, DSLs) can be built. This foundation must:

- Express computelets as reactive entities with inputs and outputs.
- Support dynamic reconfiguration (linking and unlinking connections) to enable suspension, migration, and runtime topology changes.
- Avoid entangling the base model with concurrency concerns, which should be layered on top.
- Serve as a stable kernel from which higher-level features naturally emerge.

## Problem

Earlier designs interwove computelet behavior with concurrency mechanisms (queues, runner orchestration). This made the semantics harder to reason about, and complicated the introduction of features like suspension and migration.

We need a lower-level model that:
- Captures the **logical behavior** of computelets and their connections.
- Is independent of concurrency, making it easier to reason about and simulate.
- Provides hooks that make suspension/migration a matter of link manipulation rather than complex protocols.

## Considered Approaches

1. **Embedding concurrency in the computelet model**
   - Computelets directly tied to message queues and runners.
   - **Cons**: hard to reason about, difficult to change topology dynamically.

2. **Explicit mobility state machines per computelet**
   - Encode attachment, detachment, and migration states within computelets.
   - **Cons**: complex, error-prone, mixes control-plane and data-plane logic.

3. **Port-based computelet kernel (chosen)**
   - Computelets expose bi-directional **ports**.
   - Ports can be linked to other ports at runtime, and updates propagate along these links.
   - Concurrency is not modeled at this layer; it is introduced later by runners wrapping subsets of computelets.
   - **Pros**: minimal, composable, naturally supports reconfiguration.

## Decision

We will adopt a **port-based computelet kernel** as the foundational abstraction, incorporating explicit directionality, per-link addressing, multiplexed protocols, and port cardinality constraints:

- **Computelet/Cell**: an entity that acts as its own specification. It declares named ports and provides behavior via an `onActivate` hook.
- **Port**: an endpoint with a declared **direction** and **cardinality**. Ports are declared via delegates for discovery.
- **Runner**: a specialized cell that hosts other cells. It manages their lifecycle (Cold to Hot transition) and orchestrates links.
- **Link**: a first-class, directional connection between two ports.
- **Messages and Protocols**: messages are tagged with a protocol identifier. Protocols may be generic (e.g., attention propagation, time-based requests) or specific to a computelet. Ports multiplex these protocols, and handlers for them can be composed.
- **Cardinality Constraints**: ports enforce constraints at link time. Typical downstream ports are multi-consumer, upstream ports are often single-linked for ownership safety, and unions may explicitly allow multiple producers.

Suspension and migration remain modeled as link manipulation:
- **Suspension**: unlink all ports of a computelet (and all computelets of a runner), isolating them from the graph.
- **Migration**: unlink, move, and relink ports to integrate computelet(s) into a new runner.

Concurrency and execution scheduling are still **layered on top**. Runners manage queues and isolation without affecting the logical model.

## Rationale

- The model now explicitly supports both **broadcast downstream** and **per-link upstream communication**, matching real scenarios like attention propagation and targeted state requests.
- **Directionality** ensures generic protocols know how to propagate correctly without accidental inversion.
- **Multiplexed protocols** allow new protocols to be added or stacked without requiring changes in computelet-specific logic.
- **Cardinality constraints** enable optimizations (e.g., safe transfer of mutable state) and express union semantics.
- This refinement preserves the kernel’s simplicity while increasing its expressiveness and alignment with real use cases.

## Consequences

- Higher-level features (typed ports, DSLs, optimization strategies) will build on this kernel.
- The kernel itself is simple but powerful, enabling dynamic reconfiguration without concurrency entanglement.
- Runner-level orchestration integrates naturally: ports crossing runner boundaries become message sends, while internal links remain direct.

## Status

Draft.