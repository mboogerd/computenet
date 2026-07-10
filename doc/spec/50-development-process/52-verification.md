# 52 — Verification: Invariants over Examples

> **Status**: Partial (invariants-as-cells + kotest adapter + generative graph harness built; live/shadow machinery unbuilt)
> **Sources**: ADR — Cellular Software Development Process (testing philosophy, live invariants)
> **Implementation**: `cell.verify.InvariantCell`/`Violation`; `checkInvariants` kotest adapter (test sources); seeded harness = `cell.host.SimulationController`

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
yet; add it when something is, e.g. G-19 throttling.) The first seeded
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

⚠ GAP (G-32): shadow-mode (side-effect suppression) needs a first-class
marker — which cells are effectful sinks — plausibly the same
data/management/effect classification as G-11.

## What stays example-based

Kernel machinery itself (ports, hosts, proxies — the current test suite), and
cell-logic unit tests during development. Invariants complement, not replace,
these. `Thread.sleep(...)` synchronization is gone from the suite: host tests
run on the deterministic `SimulationController` (drive with `runToIdle()`,
then assert); the single intentionally-threaded test verifies the
virtual-thread scheduler itself.
