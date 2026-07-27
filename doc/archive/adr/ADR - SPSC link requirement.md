# ADR: SPSC Link Requirement and Message-Passing Contracts

## Status
Draft

## Context
In high-performance dataflow systems, especially those inspired by domains such as high-frequency trading (HFT), there is a strong emphasis on minimizing latency and ensuring consistency under concurrency. We explored how ownership and mutability of messages passed between tasks affects the correctness and performance of the system.

Several message-passing contracts were defined based on ownership semantics:
- `Borrowed<T>`: A read-only, temporary snapshot view; fan-out is safe.
- `Owned<T>`: Transfer of ownership; must be consumed exactly once; fan-out is unsafe.
- `Leased<T>`: Exclusive mutable access from a shared pool; must be released; fan-out is unsafe.
- `Frozen<T>`: Immutable form of an owned value; fan-out is safe.

These contracts can be encoded at the type level, and primarily affect the design of the payload types passed between tasks. However, they have implications on how the graph is constructed and scheduled.

## Decision
While message-passing contracts are primarily encoded in the payload types, the dataflow graph system must enforce **unary downstream links** for messages using `Owned` or `Leased` semantics to ensure exclusive control transfer and prevent illegal sharing or double consumption.

To support this:
- The task graph should distinguish between unary and multi downstream edges.
- Fan-out should be allowed only for message types that are safe to share (e.g., `Borrowed<T>`, `Frozen<T>`).
- Graph validation will enforce that exclusive ownership messages (`Owned<T>`, `Leased<T>`) are not connected to multiple downstream consumers.

This constraint does not require changing the core task or edge interfaces but affects the graph-building API and the scheduler/runtime behavior.

## Consequences
- Enables static or early validation of incorrect usage of exclusive messages.
- Protects against concurrency bugs and performance issues related to shared mutability.
- Introduces complexity in managing graph topology for developers using `Owned` or `Leased` messages.
- Encourages disciplined use of ownership semantics and message types to match performance and safety requirements.
