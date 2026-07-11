# 95 — Research Plan

> **Status**: living document — the questions where no committed solution exists;
> each entry proposes solution directions and concrete research actions
> **Sources**: [91-gap-analysis.md](91-gap-analysis.md),
> [93-feature-interactions.md](93-feature-interactions.md),
> [94-implementation-plan.md](94-implementation-plan.md) (items gated on entries here)
> **Implementation**: none

Entries are ordered by how much scheduled work they gate. Each states: the open
question, what it blocks in [94](94-implementation-plan.md), proposed solution
directions (ranked by current preference), and research actions that would raise
certainty enough to promote the entry into 94.

---

## R1 — Leader election & failover (G-44 residual; touches G-45)

**Question**: how does a single-writer replica set choose and replace its leader
without a central coordinator, and how are two leaders after a partition fenced?
**Blocks**: automatic failover in 94 W4.3 (the core ships with explicit failover);
the churn-liveness argument of W3.3.
**Directions**: (1) lease-style leadership as a fold over registry announcements —
`LeaderMark(epoch)` already fences appliers, so election reduces to agreeing on the
next epoch holder; a deterministic rendezvous (lowest live `instanceId` among the
membership fold) needs no messages beyond what the mesh already gossips.
(2) an explicit orchestrator cell (the 53 promotion machinery re-used: leadership as
promotion of a follower), keeping election out of the kernel entirely. (3) classical
consensus is explicitly *not* preferred (P4: no global coordination; P10: niche).
**Actions**: simulate partition/heal/leader-crash on the `SimulationController` with
the LeaderMark fold and measure split-brain windows under (1); prove or refute that
epoch fencing plus parked-writes (30/33) yields no lost/duplicated write in every
interleaving; only then pick between (1) and (2).

## R2 — Weak-tier fixpoint convergence (G-19 residual)

**Question**: for cycles whose deltas are not idempotent-merge (the weak tier), when
does damped re-origination *converge*, and what should the `quiescence` policy
vocabulary express?
**Blocks**: convergence guarantees in 94 W3.1 (guard mechanics ship with the honest
bounded-lap contract only); the epoch-hygiene compaction corner of W2.1.
**Directions**: (1) classify delta algebras by contraction behavior — magnitude-
decreasing folds converge geometrically (contraction-mapping argument); expose the
class, not a numeric threshold, in the descriptor. (2) adopt the DBSP/differential-
dataflow iterative-semantics framing already cited in 20/24 — a cycle is a fixpoint
of a monotone operator over a partially-ordered delta space; convergence = reaching
the least fixpoint, throttling = chaotic-iteration scheduling. (3) per-application
damping schedules (exponential backoff of re-origination) as a pragmatic fallback.
**Actions**: build a generative cycle harness (seeded graphs with feedback edges,
divergent control) over representative delta types; map which of the M11 aggregate
vocabulary is weak-tier; survey DBSP §iteration and Bellman-Ford-style asynchronous
iteration results for reusable termination criteria.

## R3 — Coupling liveness (G-53)

**Question**: a Symport/Antiport coupling waits for both flows of a wave — what
happens when one side never fires? Any timeout/abort must compose with the no-loss
invariant and the drain protocol.
**Blocks**: enabling couplings in 94 W3.4 (membranes ship with couplings gated or
documented-hazardous).
**Directions**: (1) membrane-scoped deadline with *compensating release*: on expiry
the held side is released to its port with a `CouplingAborted` notice on the metadata
plane (no loss, explicit degrade). (2) coupling as a `GlitchFreeCell` special case —
reuse W2.7's Stall/RE-SCOPE machinery instead of inventing a second wait-policy
vocabulary (preferred if the semantics fit: a coupling is a two-edge join). (3) veto:
declare couplings only legal between ports proven co-live by construction.
**Actions**: enumerate the starvation cases on the `SimulationController` (producer
suspended, unlinked, saturated, crashed mid-wave); test whether direction (2)'s
Stall taxonomy covers all of them without new states.

## R4 — Partial-apply atomicity for remote GraphSpec (G-51 residual)

**Question**: when some remote steps of a `GraphSpec` application reject, should
`applyTo` leave the partial graph (loud, reported) or compensate (unlink the
successful prefix)?
**Blocks**: nothing in 94 (W3.6 ships partial + `ConstructionReport`); this decides
whether a transactional mode is ever added.
**Directions**: (1) keep partial+report as the *only* semantics — evolution (53)
already provides the transactional idiom (build candidate → judge → swap), so
transactional construction may be redundant (preferred; YAGNI). (2) compensating
unlink of the applied prefix, reusing journal reversal (G-49 machinery). (3) wrap
remote application in a membrane swap: build hidden, expose atomically (needs G-52).
**Actions**: catalogue real failure cases from the generative harness once W3.6
lands; if every observed need is covered by build-then-swap, close this as (1) with a
spec note.

## R5 — Placement engine (G-61)

**Question**: what decides where cells land — colors, interest, locality, load — and
what is the placement API (constraints on GraphSpec? a placement cell?)?
**Blocks**: nothing scheduled (94 items place manually); gates the 30/32 co-hosting
SHOULD and multi-host GraphSpec replay ergonomics.
**Directions**: (1) placement as an ordinary cell subscribing to host telemetry and
emitting `migrate` management invocations — keeps the kernel free of policy (P1) and
makes placement evolvable (P8). (2) static constraint solving over GraphSpec
(color + affinity annotations) at apply time. (3) interest-following placement:
migrate toward attention sources, the 34/42 economics as the cost signal.
**Actions**: instrument fusion wins (co-hosted direct-call vs enqueue) to quantify
what co-hosting is worth; prototype (1) with the simplest greedy policy on the
existing telemetry; defer (2)/(3) until measured.

## R6 — Economic layer (G-62)

**Question**: what bounds interest-driven resource consumption — replica
spawn-vs-subscribe, attention budgets, storage retention — and how are budgets
expressed and charged?
**Blocks**: Sybil-resistance of attention (R7 interacts); 94 W4.5's decay/cadence
knobs get defaults without theory.
**Directions**: (1) per-`Principal` token-bucket budgets charged at membrane seams
(the 40/43 quota hook + G-28 hierarchy walk) — local, boundary-enforced, no global
accounting (P4/P7). (2) attention as priced bids clearing against host capacity
(market framing) — matches P6/P10 but heavyweight. (3) reputation-weighted budgets
folded from gossip (decentralized, but new convergence obligations).
**Actions**: start with (1) as a pure policy vocabulary (no enforcement) and measure
what real workloads need; survey mechanism-design literature for decentralized
rate-allocation only if (1) proves insufficient.

## R7 — Identity strength, phase 2 (G-29 residual)

**Question**: what backs a `Principal` beyond transport vouching — keys, DIDs,
certificate chains — and how do keys rotate against the instanceId lifecycle? What
signs deltas (`RequireSigned`), and is at-rest encryption in scope?
**Blocks**: 94 W4.1 ships with `TransportVouched` only; federated/open deployments.
**Directions**: (1) per-peer static keypairs, identity = fingerprint, exchanged in
the existing `Peering` handshake — smallest step, no infrastructure. (2) DIDs for
portable identity across transports (matches the civic-tech niche, P10). (3) delta
signatures per emitting peer (already the decided granularity in 40/42) using (1)'s
keys; at-rest encryption stays out of the kernel (host concern).
**Actions**: threat-model pass over the three seams of 40/43 §BoundaryPolicy
(spoofed announcements, replayed deltas, attention floods) to determine the minimum
that defeats each; prototype (1) inside `WsTransport`.

## R8 — Keyed-structure convergence (G-23 deferrals)

**Question**: observed-remove semantics for `MapCell` values and bags (duplicates)
— OR-map value merge and multiplicity tags have no decided design.
**Blocks**: nothing scheduled; trigger recorded in 91 (first replicated keyed
structure in anger).
**Directions**: (1) OR-map as key→OR-set-of-tagged-values with per-key LWW or
multi-value exposure (the classic CRDT choice — decide per the DD/DBSP set-semantics
correspondence recorded in 20/24). (2) bags as element→PN-column multiplicity
(reuses the PnCounter discipline).
**Actions**: none until the trigger — record the two directions and the invariant
suite they must pass (order-bias control, replica convergence).

## R9 — Instance-selection policy (G-57 residual)

**Question**: which instance serves a client holding only a logicalId — nearest
replica, sticky session, leader-only — and what staleness contract does each imply?
**Blocks**: the read-path ergonomics of 94 W4.3; `NewInstanceOf` collision discipline
ships mechanically in W3.6.
**Directions**: (1) policy parameter on `lookup` (NEAREST / STICKY / LEADER) with the
staleness contract documented per mutability class — mergeable reads accept NEAREST,
single-writer writes require LEADER (already implied by 40/42). (2) always-explicit:
no default, force the caller to choose (correct-by-construction, P5).
**Actions**: enumerate the call sites once W4.3 exists; if every non-test caller
picks explicitly anyway, adopt (2) and close.
