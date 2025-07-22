# ADR 1: Feature Set and Motivations for the Collaborative Dataflow Graph Abstraction

## Status
Draft

## Context

Building decentralized systems remains notoriously difficult due to fragmented tools, poor support for partial state, inconsistent execution semantics, and high coordination overhead. The aim of this dataflow graph framework is to offer a unifying abstraction that addresses these challenges while being expressive and efficient enough for a wide variety of use cases.

This ADR outlines the concrete features the framework will support, along with the motivations behind each.

## Decision

The dataflow graph framework will offer the following key capabilities:

### 1. Push and Pull Semantics
- **Push**: State and updates propagate automatically along graph edges.
- **Pull**: Consumers can request on-demand state or recomputation.
- **Motivation**: Enables a hybrid reactive + queryable model, useful for UI applications and backends alike.

### 2. Sync and Async Task Composition
- **Synchronous**: Tasks can be chained to execute within the same call stack.
- **Asynchronous**: Tasks can yield, delay, or operate in separate execution contexts.
- **Motivation**: Gives developers control over latency and resource scheduling while allowing defaults to “just work.”

### 3. Stateless or Stateful Tasks
- **Stateless**: For pure transformations or streaming filters.
- **Stateful**: Supports in-memory, durable, or hybrid state.
- **Motivation**: Encourages minimalism where possible, while enabling durability and memory-aware persistence where needed.

### 4. Incremental or Complete Propagation
- **Incremental**: Default for unbounded structures (e.g., counters, maps).
- **Complete**: Suitable for bounded data (e.g., small structs, configs).
- **Motivation**: Reduces bandwidth and processing for large, evolving structures; simplifies logic for small atomic units.

### 5. Partitioned or Non-Partitioned State
- **Partitioned**: For maps or datasets sharded by key.
- **Non-Partitioned**: For atomic structures.
- **Motivation**: Enables high concurrency, data locality, and scale-out behavior for large datasets.

### 6. Suspendable Graphs
- Tasks and subgraphs can be suspended and resumed dynamically.
- **Motivation**: Supports graphs larger than available memory; enables lazy and partial activation.

### 7. Attention-Driven Execution
- Computational resources are allocated based on user interest and subscriptions.
- **Motivation**: Aligns resource usage with relevance; avoids unnecessary work.

### 8. Support for Cycles
- Graphs can contain cycles with magnitude-based throttling to avoid runaway updates.
- **Motivation**: Required for feedback loops, learning algorithms, UI-model synchronization, and model-driven engineering.

### 9. Runtime Graph Modification
- Tasks and links can be added or removed at runtime.
- **Motivation**: Supports dynamic reconfiguration, experimentation, and long-lived systems with evolving behavior.

### 10. Flexible Concurrency Model
- **Coroutine Runners**: Cooperative, efficient for CPU-bound logic.
- **Virtual Thread Runners**: Preemptive, ideal for IO-bound or non-yielding logic.
- **Motivation**: Tailors concurrency to task profile, minimizes overhead.

### 11. Local vs. Replicated Tasks
- Tasks may run locally only or be replicated across machines.
- **Motivation**: Supports both personal computation and shared processing across decentralized peers.

### 12. Interest-Driven Replication
- Replication based on user interest; machines volunteer partial responsibility for upstream data.
- **Motivation**: Network topology adapts to active collaboration patterns, improving latency and relevance.

### 13. Privacy and Security Controls
- Tasks can specify:
  - Whitelisted peers
  - Encryption requirements for transit and storage
- **Motivation**: Makes the framework viable for both public and sensitive workloads.

### 14. Mutable or immutable
- Tasks can incorporate mutability, or can represent sources, sinks, or invariants. If they are mutable, they
  can still have subclasses where they are single-writer (at a time, e.g. serialized), or permit concurrent
  (decentralized) updates.
- **Motivation** Data flows will tend to start from external events that demand a change to internal state.

## Consequences

This rich feature set enables the framework to serve as a foundation for many types of decentralized applications, including:
- UI state management
- Collaborative editing
- Federated analytics
- Decentralized social networks
- Distributed knowledge graphs
- Hybrid client/server apps

It increases the complexity of the runtime, requiring sophisticated scheduling, monitoring, and debugging tools to maintain developer experience. However, the resulting expressive power and flexibility make this a worthwhile tradeoff.