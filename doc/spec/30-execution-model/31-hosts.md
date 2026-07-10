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
    fun despawn(ref: CellRef)
    fun supervise(ref: CellRef, policy: SupervisionPolicy) // G-26 (M3.5)
    fun resume(ref: CellRef)               // replay a SUSPEND-ed cell's parked traffic
    fun drainHost()                        // spec 33 steps 1–3 (M3.3)
    fun resumeHost()                       // spec 33 steps 6–7, in place
    fun migrate(to: Use<HostManagementApi>) // drain + move all cells to the target host
    fun connect(from: CellRef, outletName: String, to: CellRef, inletName: String)
    fun connect(from: CellRef, outletName: String, to: Use<*>)
}
interface HostRoutingApi {
    fun route(target: CellRef, inletName: String, invocation: Invocation)
}
```

`ManagedHost` runs one **virtual thread** draining a `PriorityBlockingQueue`;
current priorities: management = 0, router = 10, hosted port invocations = 20,
drain completion = 30 (management preempts queued data — reconfiguration is
never starved by traffic; the drain's phase-2 task sits *below* data so the
accepted queue flushes before deactivation, 33).

Boundary crossing: a port used across hosts resolves to a proxy that wraps the
call as `HostedPortInvocation` and enqueues it on the target host
(`enqueueHostedInvocation`). Within a host, ports resolve to direct calls
(14). This realizes "ports crossing runner boundaries become message sends,
while internal links remain direct".

**Durable hosts** *(G-25 resolved, M10)*: a host constructed with a
`Journal` write-ahead appends every accepted invocation as a wire frame at
the intake (the single funnel — journal order = acceptance order), so a
process death loses nothing it acknowledged. `checkpoint(journal)` compacts
the log to one snapshot record of every `Stateful` cell; `recoverFrom`
(after the graph is rebuilt) restores the checkpoint and replays the tail
through the ordinary decode path. Durability is a hosting decision, not a
cell concern (24).

## Normative rules

1. **Single consumer**: all cell logic of a host executes on its one context;
   cells never share threads with other hosts.
2. **Fast path**: cross-host send = volatile read + enqueue; intra-host send =
   direct call. Nothing else on the steady-state path (P2). (The closable
   intake, M3.2, costs the sender one volatile read — a closed intake throws
   `IntakeClosedException` and the registry parks; see 33.)
3. **Ordering**: per-link FIFO MUST be preserved. *(C-8 resolved: the host
   queue orders by `(priority, sequence)` with a monotonic per-host sequence
   number as tiebreaker; equal-priority entries are FIFO.)*
4. **No result-blocking on the data path**: only management calls may await
   futures (spawn/lookup currently block up to 5s — acceptable for
   management; forbidden for `PORT_API`).
5. **Failure isolation**: a cell throwing MUST NOT kill the host loop.
   *(G-26 in place: failed and undeliverable invocations — including
   unknown cell/port, previously silent drops — are published as `DeadLetter`
   on the host's `deadLetterOutlet` port, plus a stderr line when unobserved.
   Per-cell **supervision policies** (M3.5): `supervise(ref, policy)` sets
   PROPAGATE (default, dead-letter only), RESTART (deactivate → activate →
   restore the spawn-time `Stateful` checkpoint), or SUSPEND (subsequent
   traffic parks per-cell, in order, until `resume(ref)` replays it). Every
   policy still dead-letters — observability is not a policy. Supervision is
   per-host and does not migrate; a suspended cell leaving the host
   dead-letters its parked traffic. Policies are host-management
   configuration, not link-time policy (13).)*
   *(G-26 completed, M4.4: per-cell error outlets. A cell declaring the
   opt-in `ErrorReporting` marker exposes an `errorOutlet`; its host emits
   `CellError(cellRef, cause, invocation)` there on every invocation failure,
   under every supervision policy, in addition to the dead letter. Errors
   thus flow through visible topology (P3) to whoever links a consumer — the
   natural one being an invariant cell (52). Opt-in per P6: `Cell` itself
   stays minimal.)*

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
*(G-28 resolved, M8.1)*: a host spawning a host records the parent/child
relation. A subtree `quota` (max cells, hosts included) is checked against
every ancestor's budget on spawn — the sandbox enforcement point of 43 §4;
`drainHost` cascades children-first, so a child cannot outlive or keep
accepting after its parent. Verified: `HierarchyTest`. Remaining: richer
resource models (memory/cpu) and spawn *redirection* (placement) — add when
a scheduler needs them.
