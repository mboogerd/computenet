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

We will adopt a **port-based computelet kernel** as the foundational abstraction:

- **Computelet**: an entity with a set of named ports and a behavior function that reacts to updates on its ports.
- **Port**: an endpoint that can be linked to other ports, enabling update propagation.
- **Links**: dynamic associations between ports that can be created or removed at runtime.
- **Updates**: events sent through ports that may trigger computelet behavior and further emissions.

Suspension and migration are modeled as link manipulation:
- **Suspension**: unlink all ports of a computelet (or all computelets of a runner), isolating them from the graph.
- **Migration**: unlink, move, relink ports to integrate the computelet(s) into a new runner.

Concurrency and execution scheduling are **layered on top** of this kernel. Runners manage message queues, isolation, and mobility without affecting the core semantics.

## Rationale

- This kernel cleanly separates **what** the graph does (reactive updates between ports) from **how** it executes (concurrency, mobility protocols).
- It simplifies reasoning about behavior, as the entire graph can be modeled and tested single-threaded.
- It provides natural hooks for runtime reconfiguration without special-case mechanisms.
- It aligns with the earlier decision to manage mobility at the runner level.

## Consequences

- Higher-level features (typed ports, DSLs, optimization strategies) will build on this kernel.
- The kernel itself is simple but powerful, enabling dynamic reconfiguration without concurrency entanglement.
- Runner-level orchestration integrates naturally: ports crossing runner boundaries become message sends, while internal links remain direct.

## Status

Draft.