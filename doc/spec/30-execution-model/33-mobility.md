# 33 — Mobility: Suspension and Migration

> **Status**: Specified (decision stable across two ADRs); primitives partially implemented
> **Sources**: ADR — Computelet Mobility (supersedes ADR — Blocking Mailbox), ADR — Computelet Kernel
> **Implementation**: Buffering proxy + serve/delegate switching (TrafficLightCell) = the boundary primitive; legacy `germ.Handle`; no closable-queue / drain protocol yet

## Definitions

- **Suspension**: isolating cells from the graph — their ports stop consuming;
  traffic is parked or bounced. Supports graphs larger than memory, lazy
  activation, live maintenance.
- **Migration**: moving cells to another host: suspend → move → resume
  elsewhere. In kernel terms both are **link manipulation** (P1):
  suspension = unlink/buffer at all ports; migration = unlink, move, relink.

## The core decision: the host is the unit of mobility

Fine-grained per-cell migration protocols (temporary queues, reference
rewriting, RCU epochs — all explored in the superseded Blocking Mailbox ADR)
were rejected as complex and race-prone. Instead:

1. To move or suspend cells, **suspend the whole host**: stop intake, drain
   in-flight messages, capture cell state.
2. Spawn new host(s) with the desired cell subsets; relink.
3. Need finer granularity? **Make hosts smaller.** Host-per-subchain is cheap
   (virtual threads/coroutines), so granularity is a placement decision, not a
   protocol feature.

Suspension and migration thereby share one mechanism, and the fast path stays
minimal (P2): senders pay only a volatile read + enqueue, ever.

## Stale references: closable queues (normative)

- A suspended/moved host's intake is **closed**; sends to it **fail fast**
  (exception), never block, never silently drop.
- Senders (i.e. link/proxy internals — never cell logic) catch closure,
  **re-resolve** the target's current location from the graph, and retry.
  Re-resolution MAY return a persistent overflow mailbox (disk queue) when the
  target is suspended indefinitely (13, 24-durability).
- Coroutine channels already fail on closed; blocking hosts need a closable
  queue wrapper with identical semantics.

This is the Link-and-Lease invalidation story (10/14) applied to location:
optimistic send, lazy re-resolution, O(rare-event) cost.

## The drain protocol (per host)

```
1. close intake            → new sends fail fast; senders park/retry via re-resolution
2. drain queue             → process (or park) everything already accepted
3. quiesce cells           → onDeactivate hook (G-16); capture serializable state
4. detach links            → record topology for re-link
5. [migrate] transfer      → cold cells + state + parked invocations
6. respawn & relink        → activation (10/15); replay parked invocations in order
7. republish location      → senders' re-resolution now finds the new host
```

Ordering invariant: for any link, messages accepted before closure are
delivered before messages sent after re-resolution (matches 13's no-loss
invariant; per-link FIFO preserved end-to-end).

## Port-level buffering: the traffic-light primitive (implemented)

`TrafficLightCell` demonstrates boundary suspension with zero fast-path cost:

- **red**: serve a `Buffering` proxy — invocations park in order.
- **green**: replay buffer downstream, then `delegate(dataOutlet)` — the cell
  removes itself from the message path entirely.

This is the reusable primitive for steps 1/6 at port granularity, and the
first membrane behavior in code (11).

## Gaps to close (G-5)

1. Closable-queue wrapper for `ManagedHost` + failure surfaced through
   proxies (today `enqueueHostedInvocation` cannot fail).
2. Re-resolution: requires a **location registry** — which host currently
   serves a `CellRef`. Design together with 40/41's addressing. (`germ.Handle`
   was the pre-invocation-model attempt; supersede it.)
3. `onDeactivate` + state capture (P9: cell state must be serializable — tie
   to 24 durability snapshots).
4. Host suspend/resume/migrate operations on `HostManagementApi`.
5. Parked-invocation transfer format (serialized invocations — already the
   wire format, 14).

## Constraints from elsewhere

- `Owned`/`Leased` payloads: parked messages retain exclusivity through
  migration; exactly-once consumption must survive replay (20/23).
- Glitch-freedom: parked invocations carry their MessageContext; version
  buffers are cell state, so they capture/restore with the cell (20/22).
- Attention (34): suspension of an uninterested subgraph and re-activation on
  renewed interest is the intended *driver* of this machinery.
