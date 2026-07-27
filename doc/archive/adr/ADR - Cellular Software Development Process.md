# ADR-0001 — Cellular Software Development Process

## Status
Proposed

## Context

Traditional software development processes assume relatively static architectures composed of modules, services, or actors. While these abstractions provide encapsulation and scalability, they struggle with:

- incremental evolution of running systems,
- safe runtime composition,
- fine-grained control over connectivity and authority,
- continuous verification under live data,
- and large-scale systems that evolve without global coordination.

The Cellular Programming vision proposes a development process centered around Cells: stateful units of logic that expose explicit ports and communicate through controlled links. Systems are composed both through networking (cells connected into larger structures) and encapsulation (cells containing other cells).

Cells are intended to support:

- incremental, delta-driven computation,
- runtime topology awareness,
- composable hierarchies,
- and live evolution of software without global shutdown.

This ADR defines the development process around Cellular Programming, not the detailed internal anatomy of cells (which is covered separately).

---

## Decision

Adopt a Cellular Software Development Process where development, testing, deployment, and runtime evolution are structured around Cells, explicit connectivity, and invariants.

Key principles:

1. Development is boundary-oriented: systems are designed as networks of Cells with explicit interaction contracts.
2. Changes propagate incrementally through delta flows rather than full recomputation.
3. Verification emphasizes invariants and system stability rather than example-based testing.
4. Deployment is evolutionary and runtime-native, enabling live replacement and rollback.
5. Runtime topology is explicit and manipulable, enabling selective activation, isolation, and adaptation.

---

## Programming Model

### Cells

Cells are:

- containers of state and logic,
- executed with serializable state transitions (conceptually single-threaded),
- connected via explicit ports,
- composed through networking and encapsulation.

Ports define:

- accepted or emitted data types,
- directionality,
- multiplicity (one-to-one, fan-in, fan-out),
- and connection constraints.

Cells differ from traditional actors in several ways:

| Aspect | Actors | Cells |
|---|---|---|
| Interface | Single mailbox | Explicit ports |
| Connectivity | Address-based messaging | Link-based topology |
| Composition | Mostly networking | Networking + encapsulation |
| Model | Message-driven | Delta-driven (events and state optional) |
| Runtime awareness | Topology often implicit | Topology explicit and inspectable |

Cells allow runtime-aware graph operations such as activation, suspension, restoration, and subgraph extraction.

---

### Composition Modes

Two primary composition mechanisms:

#### Networking
Cells exchange information through links between ports, forming dynamic processing networks.

#### Encapsulation
Cells may contain other cells (organelles), hiding internal structure while selectively exposing external ports.

This enables recursive modularity where larger cells act as modules in higher-level systems.

---

### Incremental Dataflow Layer

On top of the Cell abstraction, an incremental dataflow DSL is supported:

- Cells receiving deltas may compose via high-level operators (e.g., union, intersect).
- Operators produce new Cells whose internal representations are incrementally maintained.
- Data structures are concurrency-safe and delta-aware.

Inspirations:

- LASP (distributed data structures)
- Differential Dataflow (incremental computation)

Differences:

- Cellular DSL supports richer structure (including graphs with cycles and bidirectional communication).
- Time protocols are not mandatory; looser convergence-oriented models are acceptable where deterministic global ordering is unnecessary.

Consistency is viewed as local and compositional rather than globally enforced.

---

## Programming Environment

The runtime hosts an integrated development environment that supports:

- Declarative graph construction (no code required for certain workflows),
- Scaffolding of new Cell types,
- Automatic dependency discovery based on connected ports,
- Integration with standard tooling (Git, Gradle, testing, deployment).

Developers define systems by connecting Cells and refining behavior rather than constructing monolithic applications.

---

## Testing Philosophy

Testing shifts from example-driven validation to invariant-driven verification.

### Invariants

Invariants define properties that must hold across all valid executions.

Examples:

- data structure consistency,
- convergence guarantees,
- security constraints,
- resource bounds.

Invariant testing includes:

- generative inputs,
- synthetic graph extraction,
- mocked side effects,
- long-running randomized execution.

Stopping criteria may include coverage stabilization and heuristic saturation.

---

### Live Invariants

Separate runtimes can execute modified graphs against live production data in read-only or sidecar mode.

These runtimes validate invariants continuously before promotion to active execution.

---

## Deployment Model

Cellular programs are deployed into a runtime (“organism”) that supports:

- live injection/removal of cells,
- dynamic link creation or removal,
- activation and suspension,
- partial graph replacement.

Deployments are incremental rather than binary releases.

---

### Versioning and Evolution

Implementations are evaluated against synthetic and live invariants.

Multiple versions may coexist.

The active implementation is selected based on invariant satisfaction under real data, enabling automated rollback or promotion.

Deployment resembles evolutionary selection rather than discrete releases.

---

## Security Model

In decentralized or extensible runtimes, untrusted code must be contained.

Security mechanisms include:

### Isolation
Cells may run in constrained environments (e.g., WASI, containers).

### Serialization Boundaries
All communication passes through structured serialization, preventing memory-level access.

### Risk Management
Connection permissions are controlled; privileged cells require explicit user approval.

### Recovery
Transaction logs allow replay without malicious cells, supporting system restoration.

---

## Runtime Characteristics

Runtimes may vary along axes including:

- local vs federated deployment,
- trusted vs mixed-trust environments,
- centralized vs decentralized operation.

The long-term vision includes a decentralized default runtime supporting safe participation by many independent contributors.

---

## Consequences

### Benefits

- Strong modularity through explicit connectivity.
- Incremental, efficient computation.
- Runtime evolution without downtime.
- High observability through explicit dataflow.
- Safer extensibility via controlled linking.
- Natural alignment with adaptive and distributed systems.

### Trade-offs

- Increased conceptual overhead compared to traditional module-based systems.
- Requires disciplined invariants and delta algebra design.
- Tooling complexity shifts from build-time to runtime orchestration.

### Risks

- Loose consistency models must avoid unstable feedback loops.
- Runtime graph complexity may grow without strong visualization tools.
- Developer education is required to shift mental models from functions/services to cells and flows.

---

## Non-goals

This ADR does not define:

- internal anatomy of cells,
- membrane and boundary mechanics in detail,
- low-level scheduling implementation,
- specific consistency protocols.

These are addressed in dedicated ADRs.

---

## Summary

The Cellular Software Development Process reframes software construction as:

- designing interacting Cells,
- validating invariant-preserving behavior,
- evolving systems incrementally under live data,
- and treating deployment as continuous adaptation rather than release cycles.

The result is a development model suited for dynamic, decentralized, and continuously evolving systems where correctness emerges from maintained invariants and explicit structure rather than static architecture.