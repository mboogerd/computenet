# 53 — Deployment as Evolution

> **Status**: Exploratory (vision fixed; depends on nearly everything else)
> **Sources**: ADR — Cellular Software Development Process (deployment model, versioning), ADR 0 (§7)
> **Implementation**: none

## Model

Cellular programs deploy into a running "organism" that supports live
injection/removal of cells, dynamic linking, activation/suspension, and
partial graph replacement. **Deployments are incremental graph operations,
not binary releases.**

Versioning is evolutionary selection:

1. Multiple implementations of a logical cell **coexist** (G-8's
   logicalId/incarnation split is the prerequisite).
2. Candidates run against **synthetic invariants** (52) and then as **live
   shadows** against production data.
3. The **active** incarnation is selected on invariant satisfaction under real
   data; promotion and rollback are link-swap operations (13) — atomic per
   membrane (11's atomic multi-port transitions is the primitive that makes a
   swap glitch-free at the boundary).

## Mechanical decomposition (all future, but all named elsewhere)

| Need | Mechanism | Spec |
|---|---|---|
| Run two incarnations side by side | replicated spawn, distinct incarnations | G-8, 42 |
| Feed candidate live inputs | fan-out links, shadow mode | 52, G-32 |
| Judge | invariant cells + promotion policy | 52, G-31 |
| Swap | buffer inlets (traffic-light) → relink → replay | 33, 14 |
| Roll back | same swap, reversed; journaled invocations replay | 24, 43 §5 |
| Continuity of identity | links bound to logicalId, not incarnation | G-8 |

The load-bearing observation: **every deployment primitive is already a
kernel/graph primitive** (spawn, link, buffer, replay, subscribe). Evolution
needs orchestration and policy on top — not new mechanisms below. This is the
strongest validation of the kernel-first strategy, and conversely: any
deployment feature that *would* require a new kernel mechanism should trigger
a design review (P1 violation likely).

## ⚠ GAP (G-33): state migration across incarnations

Promoting incarnation B over A with divergent internal state representations
needs a state-transform hook (export from A's schema → import into B's).
*Proposal*: cell-declared `exportState()/importState(prior)` (versioned,
serializable, P9), invoked during the swap's drain window (33). Cells that
cannot transform state declare it — promotion then requires catch-up replay
from upstream instead (21's snapshot protocol).

## Trust boundary

Promotion authority is a membrane/policy concern (43): who may inject cells,
approve privileged links, or trigger promotion in a runtime — per-runtime
policy, from single-developer (today) to federated governance (vision).
