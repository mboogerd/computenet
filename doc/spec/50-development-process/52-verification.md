# 52 — Verification: Invariants over Examples

> **Status**: Implemented (invariants-as-cells + kotest adapter + generative graph harness; shadow machinery M9; the Effectful processed-frontier, W2.6 — live *continuous* production shadowing still awaits a long-running runtime; the replica-convergence invariant harness with its departed-stream rule, W3.3; the lateness harness, KE4.6; exclusive-payload discharge and in-host tap observation are implemented; the observation-membrane refinements and monitor bands remain design decided in 93, unimplemented)
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

*(G-31 machinery, implemented M4.4)*: [52-INV-01] An invariant is a **cell** —
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
replicated cell attaches by the same rule. [52-CONV-01] It links to **each replica's**
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
[52-CONV-02] **Departed-stream rule** (G-45): a replica evicted mid-run (42's gated
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

*(Lateness harness, KE4.6 — [24 §Lateness and
waterlines](../20-dataflow-semantics/24-data-cells.md#lateness-and-waterlines))*:
two standing invariants over the seeded lateness harness
(`LatenessHarnessTest` in `:kernel` tests, generator `LatenessGen`, 100 seeds per
envelope, writer sources fanned straight into a `WaterlineCell` and the
evicting cell, the floor oracle independent of the cells under test).
(1) **Incremental == batch over the late-filtered input**, under
`[24-WL-10]`'s restriction — for the window-keyed `GroupByCell`, both sides
restricted to window keys strictly above the final floor; for `JoinSetCell`,
the whole state against a batch join with the evicted rows also removed — and
the `late` outlet carrying exactly the dropped adds, tags verbatim: *B6 - no
violations - every seed equals the restricted batch and nothing is late*,
*B6 - with violations - every seed equals the restricted batch and late drops
are exact*, and their `B6 join -` twins. (2) **Window-keyed state bounded by
the lateness horizon** (`[24-WL-19]`): no window at or below the final
floor remains in the cell's groups or contents (asserted on every B6 seed),
and the live-window count stays within a bound stated
as a formula of the envelope (lateness, disorder, source lag, window) at 1k
and at 10k elements — a bound on windows, never on elements: *B7 - live
windows stay within the envelope's bound at 1k and at 10k*; for the join
shape only the declaring-inlet row horizon and minted tags == batch pairs are
asserted, no whole-state bound (*B7 join - live rows stay above the floor and
within the horizon, and minted tags equal the batch pairs, at 1k and at 10k*).
The bound is qualified as `[24-WL-19]` is: an idle source suspends it
(`[24-WL-14]`), refused windows are retained (`[24-WL-17]`), and `Replicable`
cells evict nothing (`[24-WL-18]`); the harness carries no exclusives, no
idle source and no `Replicable` cell. Two standing controls, one per shape,
prove the equivalence check can fail: *B3 control - evict without retract
diverges the two subscribers* and *B14 control - exit without tags leaves the
consumer holding dead pairs* (test-side relays; no production cell carries a
fault switch). The bound checks have no standing control: their
red-capability was shown once, by production mutations that disabled
eviction, in the task reviews of `computenet-fh1fo.1`/`.2`.

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
mechanism above; the remaining membrane rules below are still unimplemented)*:
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
construction. **Still unimplemented**, the further membrane rules: **shadow edges
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

*(Exclusive payloads in shadow mode — decided in 93 I-20; discharging sinks
and in-host tap observation implemented, with G-47 refinements below)*:
[52-DISCH-01] NoOp-serving an inlet whose contract carries an exclusive payload MUST
install a **discharging** sink, not a plain drop: `Owned` →
`take()`-and-drop (consume-once satisfied), `Leased` → `release()` (buffer
returned to its pool) — generated from the same exclusive bit (20/23).
[52-TAP-01] Observation of exclusive flows is the decided province of **taps**: an
Observe-role link receives a `Borrowed` projection of the outlet contract,
fired before the sole consumer and uncounted by the SPSC rule, so
invariants, shadows, and judges watch an exclusive pipeline without
contending for consumption.

*(Conflict C-11 resolved. The closed history is: `computenet-woto` added
explicit accessors for `Pair`/`Triple`/`Result`/`Optional` and walked the value
of an outer `Owned`; `computenet-zyg1` extended that walk through a `Leased`
value after a successful release; `computenet-h6sf` stopped at function and
synthetic/hidden capture carriers and counted already-discharged exclusives
without swallowing them; `computenet-dmwl` bounded discharging proxies and
ADMIT drops to descriptor-marked parameter positions; and
`computenet-u6np` added accounting predicates, the successful-discharge
counter, and propagation of pool-callback failures. The remaining named
residuals are those in `Proxy.discharge`'s KDoc.)*

⚠ GAP (G-47): the in-host uncounted read-only Tap attachment is implemented:
Observe-role links are admitted outside the SPSC count and fire before the
sole consumer. The remaining gap is the full Borrowed projection contract
surface — KSP projection derivation and link-time validation — plus catch-up
semantics and the copy-fork/cloneability contract for mutable exclusive
shadow candidates. *Proposal*: KSP derives Borrowed-projected observer
descriptors from exclusive-carrying contracts (nested/generic payloads,
link-time validation that a tap's contract equals the outlet projection);
taps on exclusive flows are attach-forward-only (no retained history to
replay); a Cloneable/copy capability for `Shadow.forkExclusive` with a
stated failure mode for uncloneable payloads and unspecified Leased forks
(93 I-20).

*(Monitor attention — decided in 93 I-9, unimplemented)*: a live invariant
monitor holds its subgraph awake through `setSelf` attention only, never a
bespoke exception. [52-MON-01] A *passive* monitor emits NONE — it observes while the
subgraph is independently awake and catches up on resume via late-join (21),
tolerating observation gaps; an *active* monitor (liveness, security) emits
LOW, yielding to real HIGH work under the stride floor (30/34). [52-MON-02] A monitor
MUST NOT pin HIGH.

*(Effectful recovery — decided in 93 I-7; processed-frontier implemented,
W2.6, closes C-9)*: the `Effectful` marker connects to durability. [52-EFF-01] An
`Effectful` sink journals a **processed-frontier** — per inlet, the last
applied `(sourceId, counter)` — so both journal replay and post-recovery
live re-delivery are *deduped* (dropped as already-processed) rather than
re-acted. Under amended 93 I-7 R4, the landed M10 design replays intake frames
through the ordinary decode/intake path with emission un-suppressed (replay
identity is per frame: a replayed frame and its same-journal derivations are
not re-appended, while live traffic accepted during recovery is journaled),
PN-2 baseline-marks contextual replay frames, and `[24-DUR-05]`, `[24-DUR-07]`,
and `[24-DUR-08]` govern their delivery at an `Effectful` inlet. Replay-stable
identity + idempotent merges + catch-up dedup make replay safe for *state*;
the processed-frontier and discharged-baseline set govern replayed *effects*.
Topology-journal checkpoint restoration also re-handshakes every folded link,
creating fresh catch-up baseline positions; whether that re-fires an
already-acted `Effectful` sink remains open under `computenet-n2jwi`.

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
