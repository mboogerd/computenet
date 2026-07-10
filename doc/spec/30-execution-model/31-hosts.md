# 31 — Hosts

> **Status**: Partial (single-host machinery implemented and consolidated; error protocol minimal, hierarchy/colors open)
> **Sources**: ADR — Computelet Kernel (Runner), ADR — Computelet Mobility, ADR 2
> **Implementation**: `cell.host.ManagedHost` + `cell.host.HostScheduler` (`VirtualThreadScheduler` 🔵 / `CoroutineScheduler` 🟣 / `SimulationController`, which issues either color), `cell.host.Host` interface

## Definition

A **Host** is a cell that hosts other cells. It is the layer where concurrency
enters the system (P1): the kernel defines *what* cells and links mean; hosts
define *when and where* invocations execute.

A host:

- **manages lifecycle**: `spawn(cell)` → register → `onActivate` on the
  host's execution context;
- **owns the queue**: exactly one consumer processes all invocations for its
  cells → cells are effectively single-threaded (serializable transitions,
  10/11) with no per-cell synchronization;
- **orchestrates links** among its cells and to remote cells;
- **is itself a cell**: its management surface is ordinary ports
  (`managementInlet: Use<HostManagementApi>`, `routerInlet: Use<HostRoutingApi>`),
  so host control is location-transparent like everything else — a host can be
  administered remotely with no special mechanism.

## The host protocol (as implemented)

```kotlin
interface HostManagementApi {
    fun spawn(cell: Cell): CellRef
    fun <T : Any> lookup(ref: CellRef, clazz: Class<T>): T?   // proxy to a hosted cell's API
    fun connect(from: CellRef, outletName: String, to: CellRef, inletName: String)
    fun connect(from: CellRef, outletName: String, to: Use<*>)
}
interface HostRoutingApi {
    fun route(target: CellRef, inletName: String, invocation: Invocation)
}
```

`ManagedHost` runs one **virtual thread** draining a `PriorityBlockingQueue`;
current priorities: management = 0, router = 10, hosted port invocations = 20
(management preempts queued data — reconfiguration is never starved by
traffic).

Boundary crossing: a port used across hosts resolves to a proxy that wraps the
call as `HostedPortInvocation` and enqueues it on the target host
(`enqueueHostedInvocation`). Within a host, ports resolve to direct calls
(14). This realizes "ports crossing runner boundaries become message sends,
while internal links remain direct".

## Normative rules

1. **Single consumer**: all cell logic of a host executes on its one context;
   cells never share threads with other hosts.
2. **Fast path**: cross-host send = volatile read + enqueue; intra-host send =
   direct call. Nothing else on the steady-state path (P2).
3. **Ordering**: per-link FIFO MUST be preserved. *(C-8 resolved: the host
   queue orders by `(priority, sequence)` with a monotonic per-host sequence
   number as tiebreaker; equal-priority entries are FIFO.)*
4. **No result-blocking on the data path**: only management calls may await
   futures (spawn/lookup currently block up to 5s — acceptable for
   management; forbidden for `PORT_API`).
5. **Failure isolation**: a cell throwing MUST NOT kill the host loop.
   *(G-26 minimal in place: failed and undeliverable invocations — including
   unknown cell/port, previously silent drops — are published as `DeadLetter`
   on the host's `deadLetterOutlet` port, plus a stderr line when unobserved.)*
   ⚠ GAP (G-26, remainder): per-cell error outlets and supervision policies
   (restart / suspend / propagate) configured by policy (13) — M3.

## Colors of hosts

Hosts come in colors (32): virtual-thread hosts (🔵 hosting blocking/pure) and
coroutine hosts (🟣 hosting suspending/pure). One `ManagedHost` class serves
both — the color lives on the injected `HostScheduler`
(`VirtualThreadScheduler` / `CoroutineScheduler`), and spawn validates cell
color markers against it (G-3/G-27, resolved M3.1).

## Host vs Runner duplication (C-2, resolved)

`ManagedRunner` had already been removed from the tree by the time this was
implemented; the remaining substance — one host class with execution as a
*configuration* — is realized as a `HostScheduler` injected into `ManagedHost`:
`VirtualThreadScheduler` (production: one virtual thread draining the priority
queue) or a `SimulationController`-issued scheduler (deterministic,
single-threaded, optionally seed-randomized across hosts; per-host
`(priority, sequence)` order is inviolable under every seed). The simulated
configuration is the deterministic test host of 50/52.

## Hierarchy of hosts

Hosts hosting hosts is the intended composition (organelles, 10/11): a machine
host contains process hosts contains color hosts. Combined with "host control
is just ports", the management plane recurses naturally.
⚠ GAP (G-28): parent/child host relationships (resource limits, shutdown
cascade, spawn placement) are undesigned. Needed for mobility (33) and
security sandboxes (40/43).
