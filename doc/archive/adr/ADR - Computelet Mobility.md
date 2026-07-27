# ADR: Computelet Mobility

## Context

The dataflow graph framework must support dynamic **mobility** of computelets, meaning they can be moved between runners at runtime, and also **suspension/resume**, where computelets can be paused and later restored. The goals are:

- Keep the **fast path** (normal message processing) as close to a zero-cost abstraction as possible.
- Allow moves and suspensions to incur higher cost since they are comparatively rare.
- Handle computelets that may form chains or have multiple inlets, where concurrent messages may arrive from different parts of the graph.

## Problem

Early designs attempted to move individual computelets between runners. These involved:
- Temporary queues and reference rewrites to capture and redirect messages.
- Multi-step protocols to drain in-flight messages and ensure ordering.
- Synchronization to ensure no thread was still sending to an old queue.

These solutions became complex, error-prone, and introduced subtle race conditions, particularly around:
- Detecting when all senders were done using an old queue.
- Ensuring messages were neither lost nor reordered when moving computelets.

## Solution Directions Considered

1. **Per-computelet mailboxes with immutable handles**
   - Each mailbox would be an immutable handle with its own queue.
   - Migration creates a new handle while the old one remains drainable.
   - Race-free but introduces an extra queue hop and more memory overhead.

2. **Mutable mailboxes with transitional states**
   - States like `Detaching` and `Attaching` were explored to model transitions explicitly.
   - Did not resolve the core race because senders could still enqueue to stale targets after state changes.

3. **RCU-style (Read-Copy-Update) synchronization**
   - Proposed tracking per-thread epochs to detect when no sender still uses the old handle.
   - Correct but requires careful management of runner-relations and epochs.

4. **Runner-level orchestration**
   - Rather than migrating individual computelets, treat the entire runner as the unit of migration/suspension.
   - When moving, suspend the old runner, drain or park in-flight messages, and start new runners with the desired subchains.
   - Greatly simplifies the protocol: suspension and migration share the same mechanism, and no per-computelet complexity is needed.

5. **Closable queues for blocking runners**
   - Suspending/coroutine runners already fail sends on closed channels, allowing senders to detect stale references and retry.
   - For blocking runners, a closable queue wrapper can provide the same semantics: sending to a closed runner throws, prompting senders to retrieve a new reference.

## Decision

We will:
- Treat **the runner as the unit of mobility**. Moves and suspensions will operate by suspending the entire runner, draining in-flight messages, and spawning new runners with the desired computelet subchains.
- Use **closable queues** (or channels) so that senders can optimistically send to the last known runner and detect closure via an exception.
- Senders that encounter a closed runner will request an updated reference from the dataflow graph and retry.
- Keep the **fast path** minimal: message sends only involve a volatile read and enqueue into the runner’s single queue.

## Rationale

- This design unifies migration and suspension under a single, simple protocol.
- It avoids complex per-computelet move logic and subtle races.
- It keeps the fast path as cheap as possible, avoiding additional queue hops or expensive synchronization.
- Rare operations (moves/suspensions) bear the complexity cost, which is acceptable.
- Closure semantics provide a clean mechanism for senders to detect stale references without global coordination.

## Consequences

- Moving a sub-chain now suspends the entire runner, which may be less efficient than fine-grained computelet moves, but this tradeoff is acceptable.
- If needed, more advanced optimizations for partial moves can be introduced later without affecting the fast path.
- The protocol is robust, easier to reason about, and aligns with the system’s performance goals.