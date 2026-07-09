# 52 — Verification: Invariants over Examples

> **Status**: Exploratory (philosophy fixed; machinery unbuilt)
> **Sources**: ADR — Cellular Software Development Process (testing philosophy, live invariants)
> **Implementation**: conventional example-based tests only (kotest/JUnit)

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

*Proposal (G-31)*: represent an invariant as a **cell** — subscribing to the
flows it constrains, emitting violations on an outlet. Then one mechanism
serves tests, live monitoring, and promotion gates; invariants compose like
everything else; and "attach invariant to subgraph" is just linking. A thin
kotest adapter (`checkInvariants(graph, invariants, generators)`) makes them
runnable in CI. The single-threaded-simulation property of the kernel (P1)
makes generative graph testing deterministic and cheap — this is a payoff of
keeping concurrency out of the kernel. The deterministic harness exists:
`cell.host.SimulationController` drives any number of `ManagedHost`s
single-threadedly, seed-randomized across hosts, reproducible per seed.
(Virtual time is deliberately omitted — nothing in the kernel is timer-driven
yet; add it when something is, e.g. G-19 throttling.)

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
these. Current tests' `Thread.sleep(...)` synchronization is fragile —
replace with completion signals or the deterministic host (above) as part of
C-8's ordering fix.
