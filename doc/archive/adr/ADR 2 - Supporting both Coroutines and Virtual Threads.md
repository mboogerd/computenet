# ADR 2: Supporting both Coroutines and Virtual Thread runners

- **Status**: Accepted
- **Date**: 2025-07-19
- **Context**: Need to support two concurrency models (Kotlin coroutines & Java virtual threads) in a modular graph runtime
- **Decision**: Color-based task/runners + adapters + inter-runner bridges
- **Consequences**: Runtime flexibility, safe interop, maintainability, and optimized performance

## Overview

ComputeNet is a dynamic dataflow graph framework for distributed and local computation. At its core, it enables the composition and execution of tasks across a graph of connected data structures, supporting high configurability, efficient runtime behavior, and flexible concurrency models.

## Context

In dynamic and evolving task graphs, it's essential to:
- Allow nodes (tasks) to operate independently yet coordinate through shared data propagation.
- Avoid overhead when it isn't necessary — particularly in high-frequency, low-latency environments.
- Guarantee safe interaction between coroutine-based and thread-based execution models.
- Support runtime switching between coroutine-based and virtual-thread-based runners, without requiring changes to task logic.
- Maintain clear and safe concurrency rules despite mutable and interdependent graph topologies.

## Decision

- Use a three-color model to represent task execution style: Pure (🟢), Blocking (🔵), and Suspending (🟣).
- Enforce runner/task compatibility: coroutine runners host 🟣/🟢 tasks, virtual thread runners host 🔵/🟢 tasks.
- Use adapters to lift 🟢 pure logic into either 🔵 or 🟣 tasks without code duplication.
- Support inter-runner communication through unidirectional, type-safe bridges with appropriate coroutine/thread wrapping.

## Solution Design

### Task Kinds and Color Semantics

Tasks are classified by their *execution color*, which defines their interaction style:

- 🟢 **Pure**: Performs only local, non-blocking, non-suspending work. Returns a result synchronously. Can be lifted into either Blocking or Suspending contexts.
- 🔵 **Blocking**: May use blocking operations (e.g., file IO). Must run on a virtual thread.
- 🟣 **Suspending**: May use suspendable operations. Must run in a coroutine context.

Developers may implement:
- `PureTask` if the logic is side-effect free and synchronous.
- `BlockingTask` if the logic is blocking.
- `SuspendingTask` if the logic is suspendable.

Importantly:
- If you implement `BlockingTask`, your logic should only call other blocking APIs or compute synchronously.
- If you implement `SuspendingTask`, your logic must only call suspend functions.
- `PureTask` should neither block nor suspend — it should simply compute and return a value.

### Runner Kinds

Two runner types exist:
- **Coroutine-based runners**: Host only 🟣 suspending or 🟢 coerced pure tasks.
- **Virtual thread runners**: Host only 🔵 blocking or 🟢 coerced pure tasks.

### Compatibility Rules and Coercion

- Task kinds must match the runner type they are hosted in.
- 🟢 Pure tasks can be *coerced* into either 🔵 Blocking or 🟣 Suspending tasks based on the runner type they are placed into.
- Blocking and Suspending are *incompatible* and cannot coexist within a single runner.
- Task color is propagated *upstream* from consumers to producers — i.e., a pure task adapts its color based on what it connects to.

This coercion system maintains safety while allowing reuse of pure logic across execution models.

### Task Implementation Model

```kotlin
interface PureTask {
    fun process(from: Input, payload: Any): List<Pair<Output, Any>>
}
```

Note that here we model without generics, payload typing is modeled at a higher level of the kernel. 

Pure tasks are lifted into either a `BlockingTask` or a `SuspendingTask` via adapters:

```kotlin
class BlockingTaskAdapter(...) : BlockingTask { ... }
class SuspendingTaskAdapter(...) : SuspendingTask { ... }
```

The adapters manage propagation of outputs according to the execution context.

### Message Passing and Runner Bridges

Inter-runner communication is handled via *bridges*. Each bridge is unidirectional and tailored to the source and target runner types:

- **Symmetric**
  - `CoroutineToCoroutineBridge`
  - `BlockingToBlockingBridge`
- **Asymmetric**
  - `BlockingToCoroutineBridge`
  - `CoroutineToBlockingBridge`

These bridges enforce proper adaptation:
- The symmetric cases are trivial. They wrap the `Channel` or `BlockingQueue` from the downstream task in the runner and are safe to respectively, `send` or `put` because their 'color' matches.
- The asymmetric cases likewise include the downstream `Channel` or `BlockingQueue` but require some wrapping to use them safely:
- A coroutine sending to a blocking task wraps `put` with `suspendCancellableCoroutine`
- A blocking task sending to a coroutine wraps `send` with `runBlocking`

## Rationale

This architecture offers:
- **Safety**: Avoids coroutine blocking and thread starvation.
- **Flexibility**: Tasks adapt to execution context dynamically via composition, not internal logic.
- **Performance**: Skips suspend overhead where not needed; allows full coroutine cooperation when desired.
- **Reusability**: Pure logic is written once and reused across runners without ceremony.
- **Correctness by Construction**: Runner/task compatibility is encoded by adapter types and enforced through clear boundaries.

This model is ideal for complex, dynamic dataflow graphs where concurrency, performance, and correctness must coexist.

## Consequences

- Developers gain flexibility to write pure, blocking, or suspending tasks depending on their needs.
- Pure tasks can be reused without internal modification, improving modularity and testability.
- Asymmetric bridges add complexity but are isolated and clearly modeled.
- The system avoids accidental misuse (e.g., blocking inside coroutines) through explicit adapter enforcement.
- Runtime performance is optimized by avoiding suspend machinery in blocking contexts.
