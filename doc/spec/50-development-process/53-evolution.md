# 53 — Deployment as Evolution

> **Status**: Core implemented (M9): shadow → judge → buffered swap → rollback-by-symmetry; `cell.evolve.{Shadow,Promotion,StateMigrating,Effectful}`. The full swap transaction, contract-granular effect suppression, state-transform tiers, and `PromotionPolicy` are decided design ([93](../90-roadmap/93-feature-interactions.md) I-2/I-9/I-11/I-17/I-21/I-27), unimplemented.
> **Sources**: ADR — Cellular Software Development Process (deployment model, versioning), ADR 0 (§7)
> **Implementation**: `civictech.cell.evolve` (`Shadow`, `Promotion`, `StateMigrating`, `Effectful`); `ShadowPromotionTest`

## Model

Cellular programs deploy into a running "organism" that supports live
injection/removal of cells, dynamic linking, activation/suspension, and
partial graph replacement. **Deployments are incremental graph operations,
not binary releases.**

Versioning is evolutionary selection:

1. Multiple implementations of a logical cell **coexist** (G-8's
   logicalId/instanceId split is the prerequisite).
2. Candidates run against **synthetic invariants** (52) and then as **live
   shadows** against production data.
3. The **active** instance is selected on invariant satisfaction under real
   data; promotion and rollback are link-swap operations (13) — atomic per
   membrane (11's atomic multi-port transitions is the primitive that makes a
   swap glitch-free at the boundary).

**Shadow isolation** (decided in 93 I-17, amending the landed rule). The
shipped G-32 mechanism (52) NoOp-serves *every* fan-in inlet of an
`Effectful` cell as the only suppression mode — which deletes the derived
emissions a judge needs for any mid-graph effectful cell. Decided: effect
suppression MUST cut at `@Contract(effect = true)` *boundary contracts*
(flagged by the same KSP scan that emits `management`); the shadow membrane
NoOp-serves exactly the shadow inlets whose contract is effectful, interior
data inlets run fully so the judge sees every derived delta, and the shadow
owns fresh instances of its effect-boundary cells (the tap from production
is the only inbound edge). The `Effectful` *cell* marker is demoted to a
coarse fallback for opaque, non-portable I/O: such a cell is NoOp-replaced
wholesale and terminates judgeability downstream of itself. A shadow cut
MUST be SCC-closed — feedback cycles close inside the membrane as
shadow→shadow links, or the shadow is flagged open-loop-only (judged as a
transfer function; closed-loop claims need a canary promotion instead).

**Judgment is declarative policy** (decided in 93 I-17).
`PromotionPolicy(gates, window, threshold, judge, baseline?)` is the decided
shape: gate invariants (glitch-free where their inlets share an upstream
fork; convergence-at-quiescence across independent sources), an observation
window measured in observed production waves and/or coverage stabilization —
never wall-clock, never a barrier — a satisfaction criterion (strict
default: zero gate violations over the window), the judge cell, and an
optional differential baseline. The **differential shadow** runs incumbent
and candidate as parallel effect-suppressed shadows tapped from the same
production outlets and judged by the same gates: promote iff the candidate
meets the threshold and is no worse than the incumbent. **Cycle promotion
gates on quiescence**: a cell on a live cycle MAY be relinked only once the
cycle's delta magnitude sits below the G-19 threshold; without G-19
throttling, cycle promotion is deferred, not attempted.

**Attention across evolution** (decided in 93 I-9). A shadow subscription
mints no upstream interest — it emits NONE `setSelf`, so observing
production never inflates production's upstream resourcing (34). The
candidate subgraph is held awake by the promotion judge, an active invariant
pinned at LOW (never HIGH — it yields to real work under the stride floor).
Promotion copies no attention number: the swap relinks downstream
subscribers onto the candidate's full ref, each relink fires `onLinked`, and
each subscriber re-announces its current level, so the candidate re-derives
real attention from real downstream interest. The swap window MUST carry the
management-activity veto against automatic suspension.

## Mechanical decomposition (all future, but all named elsewhere)

| Need | Mechanism | Spec |
|---|---|---|
| Run two candidate instances side by side | replicated spawn, distinct instance ids | G-8, 42 |
| Feed candidate live inputs | fan-out links, shadow mode | 52, G-32 |
| Judge | invariant cells + promotion policy | 52, G-31 |
| Swap | buffer inlets (traffic-light) → relink → replay | 33, 14 |
| Roll back | same swap, reversed; journaled invocations replay | 24, 43 §5 |
| Continuity of identity | explicit relink of full refs inside the swap window — logicalId is a grouping/continuity key, never a link target (decided in 93 I-2) | G-8, 13 |

The load-bearing observation: **every deployment primitive is already a
kernel/graph primitive** (spawn, link, buffer, replay, subscribe). Evolution
needs orchestration and policy on top — not new mechanisms below. This is the
strongest validation of the kernel-first strategy, and conversely: any
deployment feature that *would* require a new kernel mechanism should trigger
a design review (P1 violation likely).

The graphs-as-data cut (decided in 93 I-21) keeps that guardrail sharp: the
candidate subgraph and its `PromotionPolicy` are declarative data — an
ordinary GraphSpec (51), applied with `NewInstanceOf(incumbentLogicalId)` so
the candidate spawns as a fresh instance of the incumbent's logical cell —
while the buffer → judge → swap → replay control loop is imperative and MUST
NOT appear as GraphSpec steps. The loop is the `Promotion` orchestrator cell
emitting ordinary management invocations over time (buffer = spawn+connect a
traffic-light, relink = unlink+connect, replay = `onLinked` catch-up); its
recording/replay medium is the invocation journal (24, 43 §5), not GraphSpec
— a feedback-driven swap cannot be a static spec because the judge's verdict
is only known at runtime — and rollback is journal reversal. Two
graphs-as-data forms, two jobs: GraphSpec = what topology; the journal =
what was done.

## The promotion swap (decided in 93 I-11, unimplemented)

Promotion/rollback is a **local, membrane-scoped, pre-validated two-phase
swap transaction with a non-vetoing commit and a retained incumbent** — pure
orchestration over shipped primitives (spawn / link / unlink / `Buffering` /
replay / `importFrom` / `despawn`). The coordinator is the containing
membrane / promotion-policy cell; it holds the swap set: every link inbound
to and outbound from the incumbent's full ref, enumerated from the
membrane's containment record or a reverse-topology index.

⚠ GAP (G-48): no cross-host index enumerates all links pointing at a full
ref, so promotion swaps cannot find their relink set, shadow cuts cannot
compute SCC closure, and logical-level orchestration is O(everything).
*Proposal*: a maintained cross-host reverse-topology index (inbound and
outbound links per full ref, including bridged links) with defined
maintenance/migration cost, serving the promotion swap set, SCC computation
for observation-membrane cuts, and `instancesOf`-based orchestration (93
I-2/I-11/I-17).

1. **PRECHECK** — no side effects, freely abortable. The candidate MUST
   present, for every rebindable link, a port with the same
   `(portName, contractId)` (structural port sameness, 93 I-2); link
   policies dry-run against each inbound `LinkRequest` (identity slot and
   exclusive-ownership bit included, 23); the promotion authority authorizes
   the swap (§Trust boundary). Any failure aborts with the incumbent
   untouched and zero traffic buffered — admission is decided strictly
   before the window, so mid-swap rejection cannot occur.
2. **PREPARE** — the membrane goes red: inbound faces serve a `Buffering`
   proxy (33), all coupled inlets parking together in one window. The
   incumbent drains its accepted invocations, flushes all its sourced waves
   downstream, then quiesces **hot** (no deactivation). After PREPARE no
   incumbent wave is in flight.
3. **COMMIT** — non-vetoing: the state handoff (§G-33) runs inside the
   window, then each link rebinds; `onLink` runs as setup only and MUST NOT
   newly reject (the admission decision was PRECHECK's). Serving the
   candidate anew invalidates downstream leases (14). Green: replay the
   buffered inbound in order, then the membrane delegates itself off the
   per-message path.
4. **RETIRE** — `despawn` the incumbent (15). Only now is it gone.

⚠ GAP (G-53): cross-port couplings (Symport/Antiport) can wait forever when
one coupled port never fires for a wave, and the fate of a half-completed
coupled transaction caught in a promotion-swap buffering window is
undefined. *Proposal*: a timeout/veto/abort policy for stalled couplings
that composes with no-message-loss and the drain protocol, plus ordering
semantics for coupled transactions across a buffered swap window (93
I-10/I-11).

**Rollback** makes "same swap, reversed" concrete: the incumbent is retained
hot with its links until COMMIT fully succeeds, so a commit-time failure
reverses any partial relinks, re-greens the membrane onto the incumbent, and
replays the buffered inbound to it — buffered traffic always has a home, and
the in-window case needs no journal. Rollback *after* a successful promotion
is a fresh swap in the reverse direction, identical protocol (see §G-33 for
the post-retire checkpoint).

The swap is atomic per membrane at the membrane's host
(management-preempts-data single-consumer serialization; no global lock, no
coordinator) and emits no topology event: an instance swap leaves the
logical edge set intact, so downstream wave completeness never observes it.
A remote candidate is first migrated onto the membrane host so the swap
stays a purely local transaction; remote inbound links re-resolve through
registry re-announcement, in-flight remote traffic parking at the bridge for
the window.

⚠ GAP (G-61): nothing decides where cells land: the color-aware co-hosting
engine (32 SHOULD), GraphSpec placement constraints, multi-host replay
routing, spawn redirection (the G-28 remainder), and membrane co-location
cost policies are all unbuilt. *Proposal*: a placement engine consuming
`CellDescriptor.color` and optional GraphSpec placement constraints to
co-host same-colored chains, replicate pure cells per color neighbourhood,
and route multi-host replays; realize M8.1's deferred spawn redirection as
its enforcement hook; and a placement/cost policy bounding how much
in-flight remote traffic a candidate co-location swap may park when membrane
links span many peers (93 I-15/I-11/I-19).

## G-33: state migration across instances (resolved, M9.4)

`StateMigrating.importFrom(prior)` on the candidate consumes the incumbent's
`Stateful.snapshot()` inside the swap's buffered window
(`Promotion.promote`: red → transfer → relink → green). Verified across
representations (`ShadowPromotionTest`: v1 Long → v2 string form, post-swap
stream identical to the unswapped control, 100 seeds).

The decided state-transform contract (93 I-27, unimplemented) deepens this:
`exportState`/`importState` are the two halves of a state transform whose
interchange is the invariant delta contract — `snapshot()` is
state-as-delta-from-empty in the outlet's delta contract, which is what
bridges divergent private representations. Export is a pure read at a
quiescent boundary, strictly before deactivation (the incumbent stays hot
until RETIRE); import runs on the already-hot candidate, never through
`onActivate`.

**The invariance line**: a promotion swap holds the port contracts invariant
— the inbound command contract (`contractId` plus every `methodId` that
could be in flight), the outbound delta `contractId`, and the delta's
declared merge semantics. PRECHECK therefore requires the candidate's
rebindable ports to be a methodId-superset of the incumbent's, with
merge-semantics equality on any replicated outlet; parked and buffered
invocations then replay into the candidate unchanged (ids key on the shared
contract interface, not the implementation). What a promotion MAY evolve:
private representation, cell logic, snapshot schema. A contract or
merge-semantics change is not a promotion — it is ordinary topology surgery
(an adapter cell, or a new logical cell).

**Three handoff tiers, typed by continuation class**:

- **T0 (restore)** — snapshot schemas match: `restore(snapshot)` directly
  (the migration path, no `StateMigrating` needed).
- **T1 (transform)** — schemas diverge and the candidate declares
  `StateMigrating`: `importFrom(prior)` reads the predecessor
  `schemaVersion` and produces the candidate's state.
- **T2 (catch-up fallback)** — neither: the prior snapshot is discarded, the
  candidate keeps its shadow state, and downstream re-baselines via
  `onLinked` catch-up (21). T2 is sound *only* for cells whose catch-up is
  idempotent against existing downstream state under a source-identity
  change (tag-based OR-sets, complete-value cells); a cell whose merge is
  non-idempotent across source identity (`CounterCell`) MUST use T0/T1 — a
  T2 re-baseline under a fresh source double-counts the incumbent's
  already-delivered contribution.

On T0/T1 the snapshot carries each outlet's `OutletWaveState(sourceId,
highWater)` and the candidate adopts it (decided in 93 I-11) — same source
lane, counter continued from the high-water mark — so downstream glitch-free
consumers see one monotone source and need no re-baseline (the PREPARE flush
guarantees no wave straddles the handoff). T2 mints a fresh `sourceId`, and
the fresh epoch MUST NOT be silent: the candidate MUST emit the `ReBaseline`
supersession notice naming the superseded sourceIds (93 I-22), making the
succession wave-observable rather than a silent fresh-source reset — this
obligation is decided, not built.

The export snapshot doubles as the rollback checkpoint: one capture serves
both `importFrom` and post-retire rollback (a bad candidate found live after
RETIRE re-spawns the incumbent from the retained snapshot as the reverse
candidate). For a `PartitionedCell` the swap membrane and the export/import
unit are per organelle — per partition, never the whole composite (a
whole-composite atomic transaction would be the distributed barrier P4
forbids); mixed-version partitions coexist safely under the invariance line.

⚠ GAP (G-49): the two-phase swap + state-transform design is by-convention
at its load-bearing spots: non-vetoing commit, contract-schema identity
across builds, source continuity under representation change, fallback
soundness, hidden-state cells, coupled-flow windows, and
rollback-after-retire. *Proposal*: KSP-distinguish admission policies
(PRECHECK) from setup-only commit hooks; a contract-version discipline
guarding `importFrom` `schemaVersion` against same-FQN hash collisions; pin
sourceId adoption vs fresh-source reset when a candidate changes delta
representation (drain-convergence fallback otherwise); a fallback-tier
soundness marker refusing catch-up for non-idempotent cells; an explicit
non-promotable declaration for hidden-state cells; a retention window for
the retired incumbent's export snapshot with rollback-by-journal-reversal
semantics pinned against this file and 24; and a transform-correctness
generative harness (93 I-11/I-27/I-21).

## Trust boundary

Promotion authority is a membrane/policy concern (43): who may inject cells,
approve privileged links, or trigger promotion in a runtime — per-runtime
policy, from single-developer (today) to federated governance (vision).

⚠ GAP (G-50): promotion is mechanically complete but has no declarative
policy or authority story: judge criteria, observation windows, differential
no-worse-than comparison, who may register or trigger a swap, canary
staging, and multi-partition rollout orchestration are open. *Proposal*:
`PromotionPolicy` as a serializable artifact beside the candidate GraphSpec
(`ObservationWindow`, `SatisfactionCriterion` grammar, differential
comparison over partial violation orders) so a promotion is fully described
by spec + policy; registration/trigger authority gated by the
membrane/policy layer under federated governance; a small-blast-radius
canary staged-promotion path for unshadowable closed-loop candidates; and an
ordering/monitoring/abort policy for partitioned rolling promotion (93
I-21/I-17/I-27).
