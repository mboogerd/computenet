# 52 — Verification: Invariants over Examples

> **Status**: Implemented (invariants-as-cells + kotest adapter + generative graph harness; shadow machinery M9; the Effectful processed-frontier, W2.6 — live *continuous* production shadowing still awaits a long-running runtime; the replica-convergence invariant harness with its departed-stream rule, W3.3; observation membrane, exclusive-payload discharge + taps, and monitor bands remain design decided in 93, unimplemented)
> **Sources**: ADR — Cellular Software Development Process (testing philosophy, live invariants)
> **Implementation**: `cell.verify.InvariantCell`/`Violation`; `checkInvariants` kotest adapter (test sources); `cell.verify.ReplicaConvergence` (replica-convergence invariant harness); seeded harness = `cell.host.SimulationController`

## Philosophy

Verification shifts from example-based tests toward **invariants**: properties
that must hold across all valid executions — data-structure consistency,
convergence guarantees, security constraints, resource bounds. Rationale: in
long-lived, evolving, concurrent graphs, examples cover points; invariants
cover the space (and are exactly what evolutionary deployment, 53, selects on).

## Invariant testing (synthetic)

Techniques the process ADR commits to:

- generative inputs (property-based testing);
- **synthetic graph extraction**: instantiate the real subgraph under test,
  mock side-effecting boundary cells;
- long-running randomized execution;
- stopping criteria: coverage stabilization, heuristic saturation.

*(G-31 machinery, implemented M4.4)*: an invariant is a **cell** —
`cell.verify.InvariantCell(name, initial, fold, check)` subscribes to the
flows it constrains and emits `Violation`s on its `violations` outlet. One
mechanism serves tests, live monitoring, and promotion gates; invariants
compose like everything else; "attach invariant to subgraph" is just linking
(and a late-linked invariant receives catch-up like any subscriber, 21). The
thin kotest adapter (`checkInvariants(controller, invariants) { ... }`, test
sources — kernel main carries no test dependencies) runs the block, drives
the simulation to idle, and fails with the violation payloads. Cell errors
feed the same machinery: an `ErrorReporting` cell's `errorOutlet` (31) links
straight into an invariant cell.

*(Replica convergence — decided in
[93 I-3](../90-roadmap/93-feature-interactions.md), **built W3.3**:
`cell.verify.ReplicaConvergence`)*: a convergence invariant over a
replicated cell attaches by the same rule. It links to **each replica's**
delta outlet — replicas enumerated via `replicasOf(id)` (42) — folds the
per-replica streams independently, and asserts the folds agree at
quiescence. An in-process harness attaches straight to a local replica's own
outlet (no proxy hop needed to read it); a same-process `Replicable` re-emits
every effective local mutation, including merged-in remote deltas, on that
outlet, so the fold reconstructs exactly the replica's converged local
state. Each link fires the ordinary idempotent catch-up, so the anti-entropy
catch-up doubles as the invariant's late-join feed; no merged global view is
needed. Replicas of one `logicalId` are the special case of the harness's
cross-view convergence assertion where the views share a `logicalId`.
**Departed-stream rule** (G-45): a replica evicted mid-run (42's gated
despawn) drops out of `replicasOf(id)` — `ReplicaConvergence.converged()`
only requires agreement among replicas still counted as live membership, so
an orderly departure no longer false-positives a divergence against the
departed replica's frozen last fold.

⚠ GAP (G-45, narrowed W3.3): the gossip-mesh skeleton still lacks its
liveness/churn **argument** — membership-churn reconvergence is unproven —
and graceful last-replica handoff to durable storage is undesigned (42's
gate half and this section's departed-stream rule are built). *Proposal*:
state the bounded-gossip-hop reconciliation argument (duplicate/stale mesh
links safe by tag idempotence) with a generative membership-churn harness
(R1, 95); define graceful last-replica handoff to durable storage (G-25) vs
accidental deletion.

*(Generative graph harness, M4.6 — G-31 complete)*: seeded random pipelines
from the data-cell vocabulary are emitted as `GraphSpec`s (51) — built on one
view host, replayed verbatim onto another — and driven with random op
scripts, a mid-stream late joiner, and a mid-stream host migration. The
standard suite asserted on every generated graph: cross-view convergence,
incremental == batch recompute, late joiner == early joiner, a non-negative
count `InvariantCell`, and zero dead letters; a control run proves
arrival-order application would be caught (`GenerativeGraphTest`, 100 seeds).
The single-threaded-simulation property of the kernel (P1)
makes generative graph testing deterministic and cheap — this is a payoff of
keeping concurrency out of the kernel. The deterministic harness exists:
`cell.host.SimulationController` drives any number of `ManagedHost`s
single-threadedly, seed-randomized across hosts, reproducible per seed.
(Virtual time is deliberately omitted — nothing in the kernel is timer-driven
yet; add it when something is, e.g. G-19 throttling. The attention
suspension policy window needs none of it: it is counted in host scheduling
steps, not wall time — decided in 93 I-9 — so it is testable here
deterministically by step count.) The first seeded
invariant harness is the glitch-freedom diamond test (20/22): 200 seeds
asserted invariant-style, plus a control run proving the harness can produce
the failure it guards against.

## Live invariants (production)

Separate runtimes execute **modified graphs against live production data** in
read-only / sidecar mode, validating invariants continuously **before
promotion** to active execution.

Mechanically this needs: subscribing a shadow subgraph to production outlets
(cheap — links + fan-out), suppression of shadow side effects (boundary
policies, 13 — sinks in shadow mode get NoOp-served inlets, 14's proxy
behaviors again), and invariant cells reporting to the promotion machinery
(53).

*(G-32 resolved, M9.1–M9.2)*: the `Effectful` cell marker classifies
side-effecting sinks; `cell.evolve.Shadow.spawn` NoOp-serves every fan-in
inlet of an `Effectful` cell, so a shadow subgraph is judged (invariant
cells on its outlets) without acting twice on the world. Verified:
`ShadowPromotionTest` — including the control where an unsuppressed shadow
sink double-fires.

*(Observation membrane — decided in 93 I-17; suppression-granularity half
implemented (computenet-3jv2), amending the granularity of the resolved G-32
mechanism above; the rest of the membrane below is still unimplemented)*:
effect classification refines from the cell marker to a contract flag —
`@Contract(effect = true)` on world-touching boundary contracts, emitted by
the same KSP scan as the management flag (12). **Implemented**: suppression
cuts at exactly those boundary contracts, never at a cell's data inlets —
`cell.evolve.Shadow.spawn` NoOp-serves every `FanInlet` whose
`ContractRegistry` descriptor carries the effect bit — so interior cells run
fully and the judge still sees every derived delta (the cell-granularity
rule above no longer deletes the emissions a judge needs for a mid-graph
effectful cell). The `Effectful` cell marker demotes to a coarse fallback for
opaque in-logic I/O: such a cell is still replaced wholesale by a NoOp/mock
instance and terminates judgeability downstream of itself, flagged at cut
construction. **Still unimplemented**, the further membrane rules: **taps
are downstream-only** — the shadow-side port
negotiates no upstream protocol capabilities, so shadow-raised attention or
state-requests drop at the membrane and a read-only shadow can never summon
production computation (it initializes only via the downstream late-join
catch-up, 21); the cut MUST be **SCC-closed** — feedback cycles close inside
the membrane as real shadow→shadow links, and a loop that would re-enter
production marks the shadow open-loop only (judged as a transfer function,
never granted closed-loop claims); the **shadow owns its own boundary
instances** — fresh shadow instances of every effect-boundary cell, the tap
being the only production→shadow edge, which prevents double-fire
structurally; and **gate invariants** (promotion-deciding, unlike eager
monitoring invariants which may see glitches) are trusted only at consistent
evaluation points — glitch-free per-wave evaluation (20/22) when their
inlets share an upstream fork, convergence-at-quiescence for independent
sources.

*(Exclusive payloads in shadow mode — decided in 93 I-20, unimplemented)*:
NoOp-serving an inlet whose contract carries an exclusive payload MUST
install a **discharging** sink, not a plain drop: `Owned` →
`take()`-and-drop (consume-once satisfied), `Leased` → `release()` (buffer
returned to its pool) — generated from the same exclusive bit (20/23).
Observation of exclusive flows is the decided province of **taps**: an
Observe-role link receives a `Borrowed` projection of the outlet contract,
fired before the sole consumer and uncounted by the SPSC rule, so
invariants, shadows, and judges watch an exclusive pipeline without
contending for consumption.

⚠ CONFLICT (C-11): `Shadow.spawn`'s plain NoOp proxy drops Owned/Leased
payloads without `take()`/`release()`, contradicting the decided
discharging-sink rule for exclusive payloads (93 I-20).

⚠ GAP (G-47): the uncounted read-only Tap (a Borrowed projection fired
before the sole consumer) that lets invariants/shadows/judges observe
exclusive flows is adopted but unbuilt — projection derivation, catch-up,
and copy-fork are open. *Proposal*: KSP derives Borrowed-projected observer
descriptors from exclusive-carrying contracts (nested/generic payloads,
link-time validation that a tap's contract equals the outlet projection);
taps on exclusive flows are attach-forward-only (no retained history to
replay); a Cloneable/copy capability for `Shadow.forkExclusive` with a
stated failure mode for uncloneable payloads and unspecified Leased forks
(93 I-20).

*(Monitor attention — decided in 93 I-9, unimplemented)*: a live invariant
monitor holds its subgraph awake through `setSelf` attention only, never a
bespoke exception. A *passive* monitor emits NONE — it observes while the
subgraph is independently awake and catches up on resume via late-join (21),
tolerating observation gaps; an *active* monitor (liveness, security) emits
LOW, yielding to real HIGH work under the stride floor (30/34). A monitor
MUST NOT pin HIGH.

*(Effectful recovery — decided in 93 I-7; processed-frontier implemented,
W2.6, closes C-9)*: the `Effectful` marker connects to durability. An
`Effectful` sink journals a **processed-frontier** — per inlet, the last
applied `(sourceId, counter)` — so both journal replay and post-recovery
live re-delivery are *deduped* (dropped as already-processed) rather than
re-acted. Divergence, recorded: the I-7 resolution's linchpin was replay
with outlets NoOp-served (this section's suppression mechanism) so recovery
never re-transmits; the landed M10 design instead replays intake frames
with emission un-suppressed by default (the recovering flag only prevents
re-journaling), made safe for *state* by replay-stable identity + idempotent
merges + catch-up dedup, and safe for *effects* specifically by the
processed-frontier check at the `Effectful` inlet — the frontier is the
decided closure for that case.

⚠ GAP (G-59): the M10 journal replays intake frames, which is sound only
for deterministic, input-driven cells — wall-clock/random logic,
spontaneously-emitting sources, Effectful sinks without idempotency keys,
glitch-free partial-wave buffers, and cross-host recovery-frontier drift are
unhandled. *Proposal*: a determinism marker/lint forcing non-deterministic
cells to output-mode journaling (or a captured-entropy WAL record); an
emitted-delta log format for sources and a processed-frontier shape for
Effectful sinks with a generative recovery-dedup test; document the
external-idempotency ceiling as a stated limit; verify deterministic replay
reconstructs partial-wave buffers or include them in `Stateful.snapshot`;
evaluate an opt-in coordinated checkpoint for tightly-coupled subgraphs
(never global, per P4) (93 I-7).

## What stays example-based

Kernel machinery itself (ports, hosts, proxies — the current test suite), and
cell-logic unit tests during development. Invariants complement, not replace,
these. `Thread.sleep(...)` synchronization is gone from the suite: host tests
run on the deterministic `SimulationController` (drive with `runToIdle()`,
then assert); the single intentionally-threaded test verifies the
virtual-thread scheduler itself.
