# 31 — Hosts

> **Status**: Partial (single-host machinery implemented and consolidated; error protocol minimal, hierarchy/colors open; the RESTART redefinition's fresh-epoch + `ReBaseline` core implemented (W2.1), its checkpoint-tier and direction residuals open under G-43; recovery precedence decided in 93, unimplemented)
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
  cells → cells are effectively single-threaded for *invocations* (serializable
  transitions, 10/11) with no per-cell synchronization on the invocation path.
  Off-host **synchronous reads** are a separate plane and are served from
  published immutable snapshots, never by routing the read through the queue —
  see §The read plane below;
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
snapshots) + idempotent merges + anti-entropy/catch-up dedup. `Effectful`
sinks *(G-59 resolved in part, W2.6, closes C-9)* are the one case
un-suppressed emission is not safe for: an `Effectful` inlet now journals a
processed-frontier — the last applied `(sourceId, counter)` per inlet — and
both `recoverFrom` replay and post-recovery live delivery consult it,
suppressing (dropping as already-acted) an invocation at or behind the
frontier instead of re-driving the sink.

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
   cells never share threads with other hosts. "Cell logic" is every
   *invocation* — mutation and computation alike. A synchronous read taken by
   an off-host observer is not an invocation and does not execute on the host's
   context; it reads a value the host's context already published (rule 6).
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
   *(Decided in 93 I-22 (R1–R4, R6–R9), redefining RESTART — **and the
   redefinition's core has landed (W2.1), so rule 5's parenthetical above is
   the historical M3.5 shape, not the current one**: RESTART is
   restore-the-freshest-checkpoint
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
   *(C-12 resolved, W2.1 + D-C12 — **what landed**: the invocation-failure
   handler's RESTART branch bumps the host-held generation, deactivates, mints
   a fresh epoch on every `FanOutlet` of the cell (collecting the superseded
   `sourceId`s), reactivates, restores the checkpoint, and — for a cell
   declaring the `ReBaselineEmitting` marker — calls
   `reBaseline(supersedes, supersede = true)` before resuming, so the recovered
   state re-enters downstream over the ordinary catch-up path and a convergent
   consumer drops the un-reasserted tags and fences the dead lanes. The
   fresh-epoch and supersession halves of I-22 are therefore no longer
   aspirational, and rule 5's "restore the spawn-time `Stateful` checkpoint"
   parenthetical above records the M3.5 shape for history, not today's
   behaviour. Exercised by the `21-REBASE-01` scenario (21). **Two residuals of the
   decision remain, both under G-43 below**: the restore still takes the local
   supervision-time checkpoint rather than choosing among the freshest tiers
   (I-22's own degenerate case; peer recovery is I-25, next), and the
   direction is always the push-authoritative `supersede = true` rather than
   per-cell. Epoch reclamation is G-42 (20/22).)*
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
6. **Read plane**: the host queue serves *invocations* only. A synchronous
   read issued from a thread that is not the host's — a test's `awaitUntil`
   thread, an HTTP handler, a poller — MUST be answered from an immutable
   snapshot the writer published, and MUST NOT be routed through the queue.
   The publication convention is a `@Volatile` field written at the end of
   each effective pass; see §The read plane. Asynchronous, caller-bounded
   state reads (`ManagedHost.snapshotOf` / the paged `readBounded` sibling)
   are *not* covered by this rule: they are ordinary queued tasks that hand
   back a future, so they never block the caller on host liveness and never
   re-enter from the host thread.

## The read plane

An observer outside the host asks a cell a question and wants the answer now:
`awaitUntil` polling a data cell's `membership()`, the beads mirror's poller
thread reaching `MirrorProjector.edgeView()` (which is a `SetCell.membership()`
underneath), the Inspector's `ManagedHost.outletAt`. These calls run on the
*caller's* thread while the host's consumer folds deltas into the same state,
which is how `ConcurrentModificationException` escaped `OrMapCell.membership`
(computenet-yk5r) and `SetCell.membership` (computenet-bdth), and is latent in
`MapCell`/`ListCell`/`KeyedSetCell`/`PnCounterCell` (computenet-ndf6).

**The rule** (decided 2026-08-19). There are two planes, and they do not mix:

- **The invocation plane** is the host queue. It carries all mutation and all
  computation. It keeps the properties rule 1 states: one consumer, serializable
  transitions, no per-cell synchronization on that path.
- **The read plane** is publication. A cell that exposes a synchronous
  off-host read computes an **immutable** value at the end of each *effective*
  pass and hands it over through a `@Volatile` field; the accessor returns that
  field and touches no live fold state. A reader therefore sees the previous
  complete value or the next one — never a half-applied pass, never a map
  mid-rehash. Concurrency can make the answer *stale* by at most the pass in
  flight, which is what an incremental derived value means anyway; it cannot
  make it inconsistent.

**Why this is not a hole in "no per-cell synchronization".** The volatile write
is a *data publication* edge, not synchronization machinery. Nothing waits,
nothing is excluded, no lock is acquired or ordered against another, and the
published value is never mutated again — so no reader can be blocked by a
writer, no writer by a reader, and no lock cycle can form. The rule the
invocation plane keeps is that a cell's transitions need no mutual exclusion;
a one-word store of a reference to a frozen value does not reintroduce any.
Reads are wait-free; the cost is one O(|published value|) copy per effective
change, and nothing at all for a delta that changes nothing.

**The convention has a worked implementation**: `ReadySetCell.readySet()` in
`demo/beadsmirror` (commit `0dbab5d5`, computenet-vsbx) — `reconcile` builds an
immutable copy of the advertised key set at the end of every effective pass and
stores it in the `@Volatile published` field, and the accessor returns it.
`ReadySetCellTest`'s "`readySet` answers a whole value while the projector is
fed from another thread" is the standing proof; it fails against the version
without `published` with both signatures — a torn read and a CME. Note what the
snapshot deliberately does *not* extend to: everything the accessor does not
serve (catch-up deltas that need tags, evaluation counters, linking) stays
writer-thread only. A new off-thread accessor publishes its own snapshot rather
than widening someone else's.

**Rejected: routing synchronous reads through the host queue.** This was the
original "everything is an invocation" instinct, and it is rejected for three
reasons:

1. **Observation would perturb the schedule.** A read becomes a queued task —
   a simulation event, a priority-0 jump ahead of live data — so measuring the
   graph changes the graph, and "viz never blocks the graph" (90/97) becomes
   untrue by construction.
2. **Read availability would be coupled to host liveness.** The state would be
   unreadable exactly when it is most worth reading: while the host is wedged,
   draining (33), or terminated. A diagnostic that dies with its subject is
   not a diagnostic.
3. **It would self-deadlock from the host thread.** A read issued by cell logic
   already running on the consumer would enqueue behind itself and wait forever,
   unless re-entrancy machinery were added — machinery that exists to repair a
   problem publication does not have.

The asynchronous accessors are the surviving, legitimate use of the queue for
observation, and they are legitimate *because* they are asynchronous:
`snapshotOf` returns a `CompletableFuture` the caller bounds and may cancel, so
none of the three costs above applies to it.

**Transitional: the per-cell `stateLock`s.** The data cells in
`civictech.cell.data` — `SetCell`, `OrMapCell`, `MapCell`, `ListCell`,
`KeyedSetCell`, `PnCounterCell` — today guard their state with a
`synchronized(stateLock)` covering both the fold and the read accessors,
with the discipline that the monitor is never held across an outbound call.
That was the containment fix for the CMEs above, **not** a ratification of
per-cell locking: it is explicitly transitional, and **computenet-z530**
("data cells: migrate off-host reads from `stateLock` to published immutable
snapshots") is the migration that removes it. Until then the locks are the
implementation's temporary answer to this section's rule; new cells implement
the rule directly and do not copy the lock.

## Effects on instance sets

An `Effectful` cell (a cell that writes, notifies, or actuates the outside
world — 31, G-59) replicated across an instance set is otherwise undefined:
every replica applies the delta, so every replica fires the external effect.
PN-17 resolves the two interest settings (40/42 §Interest-scoped instance sets)
separately:

- **Disjoint interest** is **effect-once by construction.** Each logical delta
  is admitted by exactly one covering instance (partitioning — no two interests
  overlap), so only that instance fires; the per-inlet processed-frontier
  (G-59, fixes C-9) additionally dedups a replayed frame at the same
  `(sourceId, counter)`. No authority is declared and none is needed.

- **Total / overlapping interest** requires a declared **effect authority.** A
  delta rides *every* covering instance's link (the replication setting), so an
  authority names the single instance permitted to act on the world: the
  `SingleWriterReplication` leader serves the real, effect-firing implementation
  on its effect inlet, while every follower **suppresses** that inlet through
  the Shadow NoOp-serve machinery (52 §"NoOp-served sinks",
  `Shadow.suppress(inlet)`) — the delta still arrives and keeps the replica warm,
  but the effect does not fire. Leadership is the `LeaderMark` epoch fold (42):
  a handoff demotes the old leader (its inlet re-suppressed) and promotes the
  new one (its inlet re-served) under a strictly greater epoch, and a stale
  (`<= current`) mark is fenced inert — so the effect fires **exactly once per
  logical delta across a handoff**, never zero (a gap) and never twice (a
  resurrected deposed leader).

**Formation refusal.** Combining `Effectful` with Total/overlapping interest
and **no** declared authority is refused at instance-set formation
(`SingleWriterReplication.requireEffectAuthority`), the same loud typed refusal
family as the OWNERSHIP corollary (23 §SPSC corollary) and the non-idempotent
overlap refusal (42, CP-G1) — moved to the moment the combination is formed
rather than discovered as N duplicate effects on the world later. A
non-effectful cell, a disjoint assignment, or a single-writer set never raises,
so no existing instance set changes and a single non-replicated `Effectful`
cell is unaffected.

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

~~⚠ GAP (G-56): PartitionedCell's distribution edges open.~~ **Resolved by
design (CP-D1)**: partitioning is the disjoint-interest setting of the
replication mesh and inherits its edges (40/42 §Interest-scoped instance sets;
24 §Partitioned state). The one host-layer residual — supervision that travels
with (re)placement rather than being re-applied composite-locally — is the
general placement concern G-61 (below), not a partition-specific edge.

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
