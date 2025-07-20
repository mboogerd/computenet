# ADR 0: Philosophy and Motivations for the Dataflow Graph Framework

## Status
Draft

## Context

Modern software systems often require a diverse set of tools to handle streaming, messaging, storage, computation, and state synchronization. These tools are typically siloed, each addressing a narrow concern. This fragmentation imposes significant cognitive and integration burdens on developers, especially in decentralized settings.

This ADR outlines the high-level philosophy and motivations behind a unified dataflow graph abstraction, with a specific focus on supporting general-purpose decentralized applications. This vision emphasizes coherence, locality, responsiveness, and openness over rigid consensus or central coordination.

## Decision

We will build a dataflow graph framework guided by the following core motivations and principles:

### 1. Niche First, Generality Second
The framework’s niche is decentralization — supporting decentralized programs without relying on blockchain-style ledgers. It aims to be general-purpose within this niche by integrating dataflow, state, and computation into a single coherent abstraction.

### 2. Minimize Integration Overhead
Rather than combining separate tools for messaging, compute, and storage, the framework unifies these under one consistent model. This reduces mental load and allows developers to build rich, responsive systems without excessive boilerplate.

### 3. Inspiration from Diverse Domains
Lessons are drawn from:
- **Frontend/reactive frameworks**: for responsiveness and composition
- **Kafka-style streaming**: for mechanical efficiency and append-only processing
- **Databases (e.g. RocksDB, Cassandra)**: for locality, batching, and partitioning
- **Distributed ledgers**: for eventual consistency in untrusted environments
- **HFT systems**: for minimizing latency
- **Game engines**: for real-time coordination and compositional complexity

### 4. Data Locality and Minimal Critical Path
Design for fast paths: allocate data off critical paths, use in-place mutation, minimize stack depth and allocations, and eliminate unnecessary jumps between async boundaries. Fuse where possible to avoid context switches and backpressure stalls.

### 5. Interest-Driven Resource Allocation
Resource allocation (both compute and data) is guided by user interest. Subscribing to a result implies partial responsibility for its inputs. Network topology adapts to interest, lowering latency where collaboration naturally forms.

### 6. Open, Local-First Replication
Replication operates under:
- **Transparency** and **open data**: default to sharing
- **Local-first** architecture: process data where it's needed
- **Self-interest over altruism**: compute only what's useful locally
- **Topology shaped by interaction**: network links form around shared activity
- **Optional security and privacy controls**: including encryption and access policies

### 7. Continuous Integration of Knowledge
The graph is not static; it supports continuous, live updates. New logic, structure, and state can be introduced over time. This parallels software CI and supports long-lived systems that evolve as knowledge and requirements grow.

### 8. Mental Alignment and Collective Rationality
Beyond technical correctness, the framework aims to support shared understanding and distributed cognition. Tasks and dataflows reflect evolving beliefs, facilitating collaborative reasoning and adaptable models of intent.

## Consequences

This principled foundation supports:
- Coherent developer experience across state, compute, and data
- Strong locality and scalability characteristics
- Emergent topologies that mirror user interest
- The ability to incrementally evolve both code and structure in production

It also introduces complexity in execution modeling and runtime dynamics that will need to be addressed through clear tooling, documentation, and layered abstractions.