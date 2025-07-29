# ADR: Blocking Mailbox and Computelet Migration

## Context

The dataflow graph abstraction must host computelets that may be either blocking or suspending, and these computelets need to be able to move between hosts at runtime.

For suspending computelets, migration is straightforward: the host can suspend on all channels it owns, stop listening for a computelet's channel when migrating it, and reattach it elsewhere without complex coordination.

For blocking computelets, the host is a thread running a blocking loop on a single queue. Migration is challenging because:

- References to a computelet typically wrap a reference to the host's queue. During migration, these references need to be updated to send to the new host.
- Messages can still be sent to the old host while migration is in progress. These in-flight messages must be captured and delivered in order.
- To avoid reordering, messages captured during migration cannot be delivered to the new host until the old host has drained its queue.

An initial solution required a complex protocol with temporary queues, multi-step message redirection, and careful ordering guarantees. While correct, it was error-prone and involved many coordination points.

## Decision

We will adopt a **per-computelet mailbox** design, optimized to avoid performance penalties in the common case.

- Each computelet owns a mailbox that acts as its **stable ingress point**.
- Senders always send messages to the mailbox, not directly to the host.
- In the normal state, the mailbox is a **pass-through handle** that forwards messages directly into the host's single queue without extra synchronization.
- During migration, the mailbox temporarily **buffers** incoming messages in its own local queue and detaches from the old host.
- The old host drains its existing queue entries for the computelet, while new messages accumulate in the mailbox buffer.
- Once the old host signals it is done, the buffered messages are flushed into the new host's queue in order.
- The mailbox then switches back to a pass-through mode, pointing at the new host's queue.

This ensures:
- **Ordering** is preserved: old messages are processed by the old host, new messages by the new host, with no interleaving.
- **No reference rewrites** are required: senders are unaware of migration.
- **No temporary handshakes** or multiple intermediate queues are needed.
- **Minimal runtime overhead**: the hot path remains as fast as direct host queue enqueuing.

## Consequences

- Migration becomes a simple `detach → drain → attach` sequence.
- The host remains the single consumer of computelets' messages, avoiding complex synchronization.
- The mailbox abstraction adds a small amount of complexity to the implementation, but only around state transitions.
- The design maintains a lock-free, high-performance fast path while supporting safe and ordered computelet migration.

## Status

Accepted.

## Alternatives Considered

- **Temporary queues with reference rewriting**: Correct but too complex and error-prone, requiring coordination between senders, old host, and new host.
- **Multi-wait on blocking queues**: Not supported by Java; would require complex native or custom selector implementations.
- **Per-computelet concurrent mailboxes always**: Simpler migration but adds an extra synchronization per message in the steady state.

The chosen approach combines the performance benefits of a single host queue with the migration simplicity of a per-computelet mailbox.