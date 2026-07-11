# 31 — Hosts

> **Status**: Partial (single-host machinery implemented and consolidated; error protocol minimal, hierarchy/colors open; recovery precedence and RESTART redefinition decided in 93, unimplemented)
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

⚠ GAP (G-34): intakes are unbounded — no saturation signal, no admission
gate; the ADR-2 color bridges are degenerate and every parking bound
(pre-activation park, router funnel, park-at-sender) is unenforceable.
Proposal: a three-state OPEN/SATURATED/CLOSED intake flag on the existing
closure fast-path read; saturated sends dispatch by payload class (mergeable
deltas coalesce into bounded per-source pending slots, exclusive/non-mergeable
park in-order at the sender, management band exempt); a `SaturationSignal`
rides the metadata plane upstream with a terminal park-overflow policy
(visible dead-letter default, Block(timeout) opt-in only, with Block ×
glitch-free-wave semantics pinned), realized at the `enqueueHostedInvocation`
seam keyed by (senderHostColor, targetHostColor), including the cross-wire
saturation frame vs transport flow control (93 I-12/I-15/I-9/I-19/I-26).

**Durable hosts** *(G-25 resolved, M10)*: a host constructed with a
`Journal` write-ahead appends every accepted invocation as a wire frame at
the intake (the single funnel — journal order = acceptance order), so a
process death loses nothing it acknowledged. `checkpoint(journal)` compacts
the log to one snapshot record of every `Stateful` cell; `recoverFrom`
(after the graph is rebuilt) restores the checkpoint and replays the tail
through the ordinary decode path. Durability is a hosting decision, not a
cell concern (24).

The decided recovery-regime precedence (decided in
[93 I-7](../90-roadmap/93-feature-interactions.md)) is one ordered pipeline:
**checkpoint restore → journal-tail replay → re-announce + catch-up →
parked (SUSPEND) live drain**. Durability subsumes RESTART: a durable
cell's RESTART MUST restore its latest snapshot + journal tail instead of
the spawn-time checkpoint — same mechanism, richer checkpoint source; the
non-durable cell is the degenerate case (spawn snapshot, empty tail).

Two divergences between the landed M10 design and the decided I-7 model are
recorded, not silently resolved. *Classification*: I-7 journals only
`PORT_API` data invocations plus topology events; the landed tee appends
*every* intake frame (management included) and does not journal topology at
all — the graph must be rebuilt out-of-band before `recoverFrom` (the demo
rebuilds it from a users file plus re-routed writer→union links), not
replayed from topology entries in a preserve-refs GraphSpec mode.
*Emission*: I-7's linchpin is replay with outlets NoOp-served (the G-32
suppression) so recovery never re-transmits; the landed `recoverFrom`
replays frames through the ordinary decode path with un-suppressed emission
(the recovering flag only prevents re-journaling), made safe for *state* by
replay-stable identity (ref-derived tags/PN slots, tag counter in
snapshots) + idempotent merges + anti-entropy/catch-up dedup.

⚠ CONFLICT (C-9): M10 journal replay re-drives effectful sinks
un-suppressed (replay-stable identity makes state safe, not effects),
contradicting the decided `Effectful` processed-frontier suppression
(93 I-7).

⚠ GAP (G-59): the M10 journal replays intake frames, which is sound only
for deterministic, input-driven cells — wall-clock/random logic,
spontaneously-emitting sources, `Effectful` sinks without idempotency keys,
glitch-free partial-wave buffers, and cross-host recovery-frontier drift
are unhandled. Proposal: a determinism marker/lint forcing
non-deterministic cells to output-mode journaling (or a captured-entropy
WAL record); an emitted-delta log format for sources and a
processed-frontier shape for `Effectful` sinks with a generative
recovery-dedup test; document the external-idempotency ceiling as a stated
limit; verify deterministic replay reconstructs partial-wave buffers or
include them in `Stateful.snapshot`; and evaluate an opt-in coordinated
checkpoint for tightly-coupled subgraphs (never global, per P4) (93 I-7).

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
   *(Decided in 93 I-15: a returning management contract's await is
   color-realized host machinery — a blocking `get` on the virtual-thread
   host 🔵, a suspend `await` on the coroutine host 🟣. The "5s blocking
   wait" is the 🔵 realization of a color-agnostic contract, not part of
   the contract itself.)*
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
   *(Decided in 93 I-18, extending G-26: a dead-letter on a glitch-free
   frontier edge MUST additionally emit `Stall(DEAD_LETTERED)` on the
   frontier protocol alongside the `DeadLetter`; the contribution is gone,
   so the downstream join RE-SCOPEs — advances past the poisoned wave and
   surfaces a `GlitchViolation` through the `ErrorReporting`/`CellError`
   path — rather than waiting forever or silently degrading. RESTART of a
   glitch-free cell drops its transient version buffer (never captured by
   `Stateful.snapshot`) and re-enters by frontier re-discovery +
   re-catch-up; the unflushed buffered inputs were never observed
   downstream, so dropping them is safe.)*
   *(Decided in 93 I-22 (R1–R4, R6–R9), redefining RESTART — the
   restore-the-spawn-time-checkpoint text above stays as-implemented but is
   no longer endorsed: RESTART MUST become restore-the-freshest-checkpoint
   (durable → snapshot + journal tail; post-import → the imported baseline;
   replicated/derived → pull-merge from mesh/upstream; plain root → local
   checkpoint) + a host-held per-instance generation bump outside the
   `Stateful` checkpoint + a generation-stamped `ReBaseline` over the one
   catch-up path (push-authoritative supersede for single-writer roots,
   with a dead-lane filter rejecting deltas from superseded sourceIds;
   pull-merge for replicas/derivations). The fresh per-epoch sourceId makes
   tag/wave aliasing structurally impossible. RESTART restores state, never
   replays inputs, so a consumed `Owned`/`Leased` payload is never
   re-delivered; a dead-lettered exclusive payload is frozen/serialized at
   capture (`Owned` → move-by-serialize, `Leased` → released) before it
   fans out; supervision binds the full `CellRef(id, instanceId)`.)*
   ⚠ CONFLICT (C-12): landed RESTART restores the spawn-time checkpoint and
   continues emitting under the same outlet sourceId/counter (tag/wave
   aliasing possible), contradicting the decided fresh-epoch + `ReBaseline`
   supersession rule (93 I-22, reconciled).
   *(Decided in 93 I-25, RESTART of a replicated single-writer instance:
   recover state by peer catch-up — re-catch-up from the most-advanced
   reachable follower over the ordinary late-join path — with the
   spawn-time checkpoint only as the solo fallback when no peer is
   reachable. The async durability bound is stated, not hidden: writes
   served but not yet shipped to any follower at the instant of failure are
   lost; the window is bounded by the leader's shipping lag and is zero for
   any write already reflected in a follower.)*
   ⚠ GAP (G-43): RESTART's restore-freshest-checkpoint +
   generation-stamped re-baseline leaves precedence and cost open —
   supersede vs concurrent multi-source remove, re-baseline cost under wide
   fan-out, hybrid push/pull direction, poison-write loops, and the
   recovery-cell pattern are unstandardized. Proposal: state a
   supersede-vs-remove precedence with a generative convergence test; bound
   the push-authoritative re-baseline (diff-against-last-acked /
   delta-since-generation); define the per-cell direction policy for hybrid
   derivation+owned-state cells; add a poison-write escape (dead-letter the
   replaying write after N RESTARTs); standardize the
   deadLetter→requestState recovery cell — replicated cells re-baseline
   from mesh peers, resolving the RESTART-within-replication question
   carried by four earlier challenges (93 I-22/I-2/I-7/I-18/I-19/I-25).
   ⚠ GAP (G-46): exclusive (`Owned`/`Leased`) payloads have no defined
   story off the happy path — a payload parked-but-unsnapshotted at crash
   is lost with no stated at-most-once contract, and the `DeadLetter`
   envelope for freezing/serializing/redacting them is unspecified.
   Proposal: state the sender-durability contract that makes crash loss
   at-most-once acceptable (or require the producing host to be durable),
   and pin the `DeadLetter` envelope: `Owned` → move-by-serialize at
   capture, `Leased` → released, with a redaction rule for non-serializable
   payloads — mergeable parked traffic is already covered end-to-end by the
   M10 journal + anti-entropy pair (93 I-7/I-22/I-12).

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

The same recursion is decided to cover composites (decided in 93 I-10): the
G-28 parent/child record MUST extend to composite-cell (organelle)
containment — a spawn whose parent is a composite cell records membrane
containment — with the same children-first cascade for
drain/suspend/despawn, unifying the two halves of the recursion: co-hosted
organelles are the degenerate one-host case, organelles on child hosts are
the host-tree case, and the membrane surface is identical in both.

⚠ GAP (G-56): PartitionedCell's adopted design (G-24, trigger armed) leaves
its distribution edges open — routing-table epoch consistency under
concurrent organelle migration, repartition-window buffering bounds,
bulk-rebalance atomicity, supervision-travels-with-placement, per-shard
replica targeting, range queries, and per-key attention routing. Proposal:
generative wire tests for the stale-epoch re-route racing registry
re-resolution and for migrate-during-repartition (ownership and placement
maps changing near-simultaneously); a buffering-bound analysis for long
state transfers under quotas and backpressure; a
supervision-follows-placement API replacing composite-local re-apply
discipline; router targeting rules when shards replicate (leader per
shard); a scatter-gather range-read protocol over the state-request
substrate; and the attention-routing proxy forwarding interest per key
(93 I-8/I-19/I-9).

⚠ GAP (G-61): nothing decides where cells land — the color-aware co-hosting
engine (30/32 SHOULD), GraphSpec placement constraints, multi-host replay
routing, spawn redirection (the G-28 remainder), and membrane co-location
cost policies are all unbuilt. Proposal: a placement engine consuming
`CellDescriptor.color` and optional GraphSpec placement constraints to
co-host same-colored chains, replicate pure cells per color neighbourhood,
and route multi-host replays; realize M8.1's deferred spawn redirection as
its enforcement hook; and a placement/cost policy bounding how much
in-flight remote traffic a candidate co-location swap may park when
membrane links span many peers (93 I-15/I-11/I-19).
