# 95 — Research Plan

> **Status**: Living document — the questions where no committed solution exists;
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

## R8 — Keyed-structure convergence (G-23 deferrals) — PROMOTED (96 §E1, §R17)

**Question**: observed-remove semantics for `MapCell` values and bags (duplicates)
— OR-map value merge and multiplicity tags have no decided design.
**Blocks**: nothing scheduled; trigger recorded in 91 (first replicated keyed
structure in anger).
**Directions**: (1) OR-map as key→OR-set-of-tagged-values with per-key LWW or
multi-value exposure (the classic CRDT choice — decide per the DD/DBSP set-semantics
correspondence recorded in 20/24). (2) bags as element→PN-column multiplicity
(reuses the PnCounter discipline).
**Actions**: resolved by the incremental-engines research
(`doc/research/incremental-engines/`): direction (1) is decided — per-key dot map
with LWW-by-dot-order plus opt-in merged/multi-value exposure — and scheduled as
[96 §E1](96-incremental-engines-plan.md); the bags half (direction 2) is sharpened
into R17. Entry retained for the record.

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

---

Entries R10-R17 originate from the incremental-engines plan
([96-incremental-engines-plan.md](96-incremental-engines-plan.md)); research
grounding is in `doc/research/incremental-engines/` (docs 01-05).

## R10 — Context-only removal: tombstone-free OR structures (96 §E1 residual)

**Question**: can `SetDelta`/`TaggedMapDelta` dels ship as causal context only
(delta-AWSet, research 03 §2), eliminating tombstone payloads, given that the
causal-merging condition (`Xᵢ ⊒ Xⱼᵃ` before join) must then hold on every replica
link?
**Blocks**: the wire-size half of compaction; nothing in 96 E1-E3 (the tombstoned
form is correct, unbounded until E3.7).
**Directions**: (1) per-peer delta-intervals over the E3 delivered-watermark rows
plus `StateRequest(since)` full-state fallback — the mapping named in research 03
§2. (2) tombstones stay on the wire, reclaimed only at rest via causal stability
(96 E3.7) — smaller step, preferred first.
**Actions**: measure tombstone wire share on the replicated demos after E1/E3 land;
pursue (1) only if the measured share justifies per-peer interval bookkeeping.

## R11 — Frontier alignment across origination points (96 §E2 residual)

**Question**: the aligned sink's shared wave vocabulary assumes sibling views see
the same sourceIds; a re-origination point on one branch only (a `Replicable`
cell's post-merge re-emission per C-10, a future `CycleHead`) breaks it — what
provenance must cross an origination point for cross-view alignment to survive?
**Blocks**: `observeAligned` (96 E2.3) over pipelines whose branches diverge before
a replicated cell; the cross-replica case is 96 E3.4's territory.
**Directions**: (1) require the origination point to be a common ancestor of all
aligned views (structural check at build, loud rejection) — smallest honest step.
(2) carry a `TagFrontier` provenance summary through origination (relates 20/21
§Pull baselines). (3) fold with E3's delivered-watermark rows (waves regain
comparability at replica granularity).
**Actions**: ship E2.3 with the direction-(1) structural check; collect the graphs
it rejects from the demos to decide whether (2)/(3) are worth their weight.

## R12 — Observation-edge triggering policy (96 §E2 residual)

**Question**: the Dataflow Model's where/when/how decoupling (research 04 §2)
suggests a trigger knob — emit per settled wave, on demand, or on cadence with
accumulating panes; which modes does the observation edge need, and is
retraction-on-refire (native via tags) sufficient for all of them?
**Blocks**: nothing scheduled; the UX of high-rate dashboards.
**Directions**: (1) per-wave only until a demo demands otherwise (YAGNI).
(2) cadence panes as a sink-local concern, never a kernel one.
**Actions**: none until a demo saturates the per-wave SSE path; then prototype (2)
inside the sink.

## R13 — Membership completeness & departed-replica liveness for the stability read (96 §E3 residual; touches R1, G-45)

**Question**: the stability read (96 E3.5) is only safe if the membership view is
*complete* (a replica the fold has not learned of yet could still hold concurrent
ops) and only *live* if departed replicas' rows are closed or evictable — is the
`replicasOf` announcement fold sufficient under join/churn, and is lease-fenced row
eviction sound given it can race a partitioned-but-alive replica?
**Blocks**: 96 E3.7/E4 compaction liveness under churn; the unclean-failover
interplay with R1.
**Directions**: (1) creation-fenced membership: a new replica's row must exist
(bottom) before its first delta is admitted to any peer's fold — the fold becomes
complete-by-construction for stability purposes. (2) lease-fenced eviction reusing
the R1 `LeaderMark` epoch vocabulary — one shared membership/heartbeat substrate.
(3) never evict: stability freezes until manual `Replication.evict` — the shipped
default; measure how often it hurts.
**Actions**: run the E3.5 harness under join/churn seeds with (3); count frozen-
stability windows; prove or refute (1)'s completeness claim on the
`SimulationController` before considering (2).

## R14 — Watermark columns across emission epochs (96 §E3 residual; G-42 corner)

**Question**: a `ReBaseline` supersedes a sourceId; delivered-watermark columns are
keyed by sourceId — when may a superseded source's column (and the `deadSources`
fence set it mirrors) be dropped from rows and reads without a window where a
straggler delta from the dead epoch is re-admitted as new?
**Blocks**: the epoch-hygiene corner of G-42 (already research-flagged in 94 W2.1);
unbounded-but-correct is the shipped behavior.
**Directions**: (1) a dead source's column is reclaimable once the `ReBaseline`
itself is causally stable — the substrate eating its own dog food. (2) fold with
R10's delta-interval bookkeeping.
**Actions**: extend the E3.5 harness with RESTART/re-baseline seeds; assert whether
(1) closes every straggler window it can generate.

## R15 — Waterline liveness under idle sources (96 §E4 residual; cross-links R13)

**Question**: the min-over-sources waterline freezes when a linked, open source
stops emitting — eviction stalls for everyone. What retires or ages an idle
source's contribution without wall clocks; are `EdgeClose` retirement +
`ReBaseline` supersession (96 E4.4) plus a manual `retire` op sufficient in
practice?
**Blocks**: nothing scheduled (E4 ships frozen-but-correct with explicit
retirement); the same idle-participant hole gates E3's stability read — one shared
answer is strongly preferred.
**Directions**: (1) reuse E3's heartbeat/ack substrate so "idle" is distinguishable
from "silent" — one liveness mechanism for both consumers. (2) per-inlet (not
per-source) lateness, collapsing the vector to the declaring edge at the cost of
coarser promises. (3) lease-based contribution expiry folded from the registry's
membership view (shares R13's question).
**Actions**: instrument frozen-floor duration in the E4.6 harness under idle-source
seeds; adopt (1) if E3.3's heartbeat is already deployed alongside.

## R16 — Cross-host and nested cycle structure (96 §E5 residual; G-41 corner)

**Question**: link-time admission cannot see a cycle closed across hosts, and
single-level `LoopContext` forbids nested loops — what distributed
cycle-visibility mechanism (if any) is worth its coordination cost, and is the
single-level restriction acceptable long-term?
**Blocks**: nothing scheduled (the hop guard is the decided backstop; 96 E5.3 is
explicitly in-process). Gates future multi-host recursive applications.
**Directions**: (1) registry-fed topology fold: the reverse-topology index gossiped
enough to run `wouldCloseCycle` over the announced global edge set —
eventually-consistent admission with the hop guard unchanged as the safety net.
(2) Naiad's answer for nesting — one counter per enclosing context, lexicographic
product order (research 02 §1) — as a `LoopContext` list, wire-additive. (3) keep
the restriction and lint: cross-host feedback edges declared explicitly
(`FeedbackInlet` is already the marker), nesting rejected forever.
**Actions**: none until a demo needs a cross-host or nested loop; record hop-guard
dead-letter incidence from the demos as the trigger metric.

## R17 — Replicable weighted bag: per-source cumulative weights (96 §E6 residual; sharpens R8 direction 2)

**Question**: a per-source cumulative-weights map (`sourceId → element → cumulative
pos/neg`, merged by pointwise max — the PN-counter generalized per element) would
be a gossipable bag. Is the vector-width-per-element cost acceptable, and can
per-replica weighted circuits consume/emit OR-set tag deltas through the 96 E6.3
boundary without double-counting under gossip redelivery?
**Blocks**: nothing scheduled (96 E6 ships weights replica-local by
classification); replicated bag semantics / EXCEPT ALL across replicas.
**Directions**: (1) PN-column per element (R8 direction 2 sharpened), with
codec-layer per-source metadata dedup as the cost mitigation. (2) replicate only
tag streams at the boundary and keep every weighted interior per-replica — E6's
default, promoted to the permanent answer if (1)'s cost is unacceptable.
(3) hybrid: cumulative columns only for elements crossing a declared replication
boundary, lazily materialized.
**Actions**: after E6.6, measure the per-element column cost on a realistic bag
workload; run the E6.2 double-count control through a mocked (1) encoding to test
the redelivery claim.

---

## PN-16 — Frontier traversal: is the static-link model sufficient? — DECIDED (B: yes)

> **Supersedes** CP-G7 ([COMPOSITION-TICKETS-NEXT.md](../../archive/runs/COMPOSITION-TICKETS-NEXT.md) §CP-G7).
> This entry closes the question rather than proposing directions: the outcome is
> a spec paragraph to promote, not a mechanism to build. Decision recorded against
> the post-PN-1/2/7/10 code; grounded by tests run read-only.

### The question, restated

`WaveFrontier` computes wave completeness from a **static link set** — the edges
present when the wave opened (`consistency/WaveFrontier.kt:56`, the `ponytail:`
marker). CP-G7 asked: does the frontier need *transitive upstream traversal*
(each cell exposing its complete upstream frontier through a multiplex port,
G-13), or is **static links + absorb-acks (CP-A3) + interest-scoped quorum (PN-7)
+ per-edge declared source sets (E3.4)** sufficient for every structure we build?
Exit is one of: a ticket for the real thing (multiplex G-13), or a spec paragraph
promoting the current design from shortcut to decided.

### Evidence examined

- **`consistency/WaveFrontier.kt`** (whole file). Completeness is defined per
  origin wave `(sourceId, counter)`, not per hop. An edge is counted only if it
  is `Consume`-role (`expectedLocalEdges`, PN-10 excludes announced `Observe`
  taps), open, non-suspended, not replica-fed, and its floor `< counter`. A
  replica-fed edge (`ReplicaGate`) is settled off the merged watermark lattice,
  not off sibling links. Every "an edge structurally never carries this source"
  case is discharged *without* traversal: Observe-role exclusion, replica-fed
  exclusion (phantom sibling), floor ≥ counter, or a watermark advance from a
  real delta / a `Progress` absorb-ack / a later monotone wave.
- **`MessageContext.kt`** (the crux). The origin `Timestamp(sourceId, counter)`
  rides *every* data-path invocation. Outlets stamp a fresh timestamp only on a
  *spontaneous* emission; a **reactive** (transparent-flow) emission carries the
  incoming timestamp forward, rewriting only `sourcePort` (and bumping `hop`,
  which is not part of the wave join key). So an intermediate cell that forwards
  reactively — a mapper, a filter, `GroupByCell`/`MergeableGroupByCell` (which
  "emits all groups touched by one input delta as a single `MapDelta` under the
  input's wave id", `22-consistency.md`) — propagates the *origin* wave identity
  unchanged to the join. The static frontier over the join's immediate inlinks is
  therefore already the transitive frontier: the origin identity arrives in-band.
- **`DurableGlitchFreeReplayTest`** (asymmetric-durability diamond — CP-G7's
  requested "graph the static frontier gets wrong"). A fork-join diamond where
  one arm is journaled and the volatile sibling can never replay. The static
  frontier *would* wedge (an edge that never carries the replayed source can
  never settle it) — and does, in control (a). PN-2 discharges it not by
  traversal but by stamping replay as a **baseline** (`MessageContext.baseline`),
  which the frontier excludes from every wave set (`WaveFrontier.offer`, the
  `ctx.baseline != null` branch). *Ran read-only: 3/3 green, including the
  control that stalls on every seed without the baseline.*
- **`ShardedReplicaFrontierTest`** (the re-originating aggregator — a `SetCell`
  instance set that is both sharded and replicated). Each replica *re-originates*
  delivered waves under its own outlet epoch, so origin identity is **not** in
  the `MessageContext` here — it is carried in the **payload** (origin tags on
  the `SetDelta`) and settled against a **registry-discovered** covering quorum
  (PN-7 `completeAt(source, counter, key)`). This is a dynamic frontier over an
  instance set discovered at settlement time — achieved *without* multiplex
  ports. *Ran read-only: 4/4 green, all three controls (members-all,
  trivial-frontier, creation-fence-off) diverge.*
- **`demo/exchange/.../Main.kt`** (the structure we actually build). Board =
  `shards --streamTo--> MergeableGroupByCell --link--> GlitchFreeCell`. The
  scatter arms are `streamTo` taps (Observe role, excluded); the merge forwards
  the input wave id (transparent flow), so the board's single static inlink
  carries the origin waves. Channel 1. The sharded-and-replicated arm (PN-15)
  exercises channel 2. No third channel appears.

### Decision — (B). The static model is sufficient for every structure we build.

The origin wave identity reaches a glitch-free join by exactly one of two in-band
channels, and each is served without transitive port traversal:

1. **Transparent flow** — the origin `Timestamp` is forwarded in the
   `MessageContext` through every reactive intermediate. The join's static
   immediate-inlink frontier *is* the transitive frontier. (Local diamonds,
   mapper/filter/group-by chains, the demo board's merge arm.)
2. **Re-origination with tag-carrying payloads** — an aggregator that collapses
   origin identity (a replica) carries the origin waves in its payload; PN-7
   settles them against a registry-discovered covering quorum. A dynamic instance
   set, no multiplex port. (The sharded-and-replicated board arm.)

"An edge that structurally never carries a source can never settle it" — CP-G7's
motivating failure — is real, and is discharged four ways in the current code
(Observe-role exclusion PN-10, replica-fed exclusion E3.4, baseline exclusion
PN-2, and watermark/absorb-ack retirement CP-A3), none of which is traversal.

Transitive traversal (G-13 multiplex ports, a cell answering "describe your
frontier") would be forced only by a structure that **re-originates under a fresh
wave AND cannot carry origin tags in its payload AND still requires origin-level
glitch-freedom across that boundary** — e.g. a windowing/batch operator emitting
on its own schedule, wrapped so a downstream consumer demands glitch-freedom
w.r.t. the *original* sources rather than the operator's re-originated waves. **We
build no such structure**, and the guarantee it would want is arguably wrong: a
deliberate origin collapse means glitch-freedom relative to the collapsed origin
is the correct contract. This is a *decided boundary*, not a latent defect. Our
aggregators are CRDT/delta types that carry tags precisely because that is what
makes them replicable — the property that also keeps them inside channel 2.

Should such a structure ever be built, the trigger is concrete and the mechanism
named (G-13); this entry records the boundary so the future case is recognized
rather than rediscovered. Until then, no ticket.

### Spec paragraph to promote

Replace the parenthetical caveat in `20-dataflow-semantics/22-consistency.md`
(the block ending `Upstream frontier traversal awaits multiplex ports (G-13) —
its traversal model is decided, see plan item 4; unwaved traffic passes
through.`) with:

> **Frontier traversal is by carried origin identity, not port traversal
> (decided, PN-16).** A glitch-free join computes completeness per origin wave
> `(source, counter)` over its immediate static inlinks. This is already the
> transitive frontier: the origin `Timestamp` rides every reactive emission in
> the `MessageContext`, so an intermediate cell that forwards transparently
> (mapper, filter, group-by) delivers the *origin* wave identity to the join
> unchanged. An aggregator that re-originates under its own epoch (a replica)
> instead carries its origin waves in the payload, settled against a
> registry-discovered covering quorum (interest-scoped, spec 22 §Interest-scoped
> settlement) — a dynamic instance set without multiplex ports. Edges that
> structurally never carry a wave's source are excluded rather than awaited:
> announced `Observe` taps (link role), replica-fed edges (phantom siblings),
> edges whose floor already covers the counter, and catch-up baselines (recovery
> replay); a silent edge is retired by a `Progress` absorb-ack or any later
> monotone wave. Transitive port traversal (multiplex ports, G-13) is therefore
> **not required** by any structure in this system. It would be forced only by an
> operator that re-originates under a fresh wave, cannot carry origin tags in its
> payload, and is nonetheless required to be glitch-free relative to the original
> sources — a combination this system does not build, and one whose guarantee is
> arguably a category error (a deliberate origin collapse makes glitch-freedom
> relative to the collapsed origin the correct contract). The trigger to revisit
> is a concrete graph with exactly that shape; the `ponytail:` marker at
> `WaveFrontier.kt` is retired by this decision. Unwaved traffic (null context)
> still passes straight through.

