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

*(G-48 resolved, W1.6)*: the containing registry maintains inbound and
outbound links per full ref from successful handshake and idempotent unlink
events, with peer announcements mirroring remote edges. The coordinator reads
the incumbent's complete incident set from `TopologyIndex.swapSet(ref)`; this
enumeration is proportional to that swap set rather than to all cells or links.

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

*(G-49 resolved for the swap transaction, W3.5)*: `Promotion.promote` is the
four-phase protocol as an explicit state machine, not by convention. PRECHECK
validates structural port sameness on the swap outlet and — when no T0/T1
state transfer is available — refuses the promotion outright for a candidate
declaring `Promotion.NonIdempotentCatchUp` (the fallback-tier soundness
marker), all before the gate ever turns red. PREPARE reds the gate. COMMIT is
non-vetoing (an exception there is an infrastructure fault, not admission,
and triggers rollback rather than a veto) and runs the T0/T1 state handoff
(`importFrom` + `adoptWaveState`) or the T2 fallback (`mintFreshEpoch` +
`ReBaselineEmitting.reBaseline`, wave-observable per 93 I-22) before relinking
downstream and dropping the incumbent from the gate. The incumbent stays
retained and linked until COMMIT fully succeeds: a mid-COMMIT failure
(`Promotion.PromotionAborted`) reverses every completed relink, restores the
incumbent's gate subscription, and re-greens onto it unchanged — the same
swap, reversed, exactly as spec'd. RETIRE (`despawn`) runs strictly after,
so a rollback can never race it. Verified in `ShadowPromotionTest` (mid-commit
failure → retained-incumbent rollback) and `PromotionWaveStateTest` (T0/T1
epoch preservation).

Still by-convention, not built: a contract-version discipline guarding
`importFrom` `schemaVersion` against same-FQN hash collisions; an explicit
non-promotable declaration for hidden-state cells; a retention window for the
retired incumbent's export snapshot with rollback-*after*-retire
journal-reversal semantics (today's rollback only covers the in-window,
pre-RETIRE case); and a transform-correctness generative harness (93
I-11/I-27/I-21). Coupled-flow windows during a buffered swap remain G-53.

## Trust boundary

Promotion authority is a membrane/policy concern (43): who may inject cells,
approve privileged links, or trigger promotion in a runtime — per-runtime
policy, from single-developer (today) to federated governance (vision).

*(G-50 policy half resolved, W4.4)*: `civictech.cell.evolve.PromotionPolicy`
is the serializable artifact (`gates`, `ObservationWindow` measured in
observed waves, `SatisfactionCriterion` with the strict zero-violations
default, `judge`, optional `baseline`), evaluated by `PromotionJudge` —
`Promotion.promote`'s `judge` parameter consults it during PRECHECK and
aborts (incumbent untouched) unless the verdict is `Accept`. Differential
shadow: `baseline = true` additionally requires the candidate's observed
violation count to be no worse than the incumbent's over the same window.
Cycle promotion gates on quiescence: a `PromotionJudge` constructed with a
`cycleHead` defers (never accepts) until `FeedbackInlet.lastQuiescent ==
true`; `null` (no delta observed yet, or a non-`Magnitude` payload — no
confirmed G-19 throttling) is treated as not-yet-quiescent, matching "without
G-19 throttling, cycle promotion is deferred, not attempted."

⚠ GAP (G-50 residual): the *authority* half is still open — who may
register or trigger a swap is ungated (any caller can construct a
`PromotionJudge`/policy and call `Promotion.promote`), and canary staging
plus multi-partition rolling-promotion orchestration are unbuilt. *Proposal*
(unchanged): registration/trigger authority gated by the membrane/policy
layer under federated governance (see §Trust boundary); a small-blast-radius
canary staged-promotion path for unshadowable closed-loop candidates; and an
ordering/monitoring/abort policy for partitioned rolling promotion (93
I-21/I-17/I-27) — M15.5 in the roadmap.

## Replicated promotion (PN-14, resolved for the rolling form)

`Promotion.promote` is single-instance: its swap set is the incumbent's
`downstream` outlet subscriptions behind one gate. A **replicated** cell (spec
42) has no such shape — its inputs are local writes and peer *gossip* links
(never enumerated in `downstream`), it has no single upstream gate, and its
"incumbent" is not one cell but a live instance set. Promoting it as a single
transaction fails three ways: the gossip mesh is not relinked, a candidate's
fresh `CellRef` re-mints the tag lane (`SetCell.tagSource` is derived from
`(id, instanceId)`) and the delivered-watermark slot (`watermarkRef`), and the
retired incumbent's watermark row then holds downstream frontiers forever.

Set-atomic promotion (every replica swapped as one transaction) is a
distributed consensus barrier — the P4-forbidden global lock — and is **out of
scope**. What is decided and built is the **rolling** form:

**Rolling, by constraint — one instance at a time, the swap IS crash-recovery.**
`Promotion.promoteReplica` promotes a single replica; the caller rolls the set
peer by peer. The candidate **reuses the incumbent's `CellRef`**. Because every
mesh identity derives from the ref — the ref-derived tag lane, the
delivered-watermark row, and every port ref (PN-1) — reusing it makes the swap
**indistinguishable from crash-recovery, and that is the mechanism**:

- peers' inbound gossip links keep resolving to the ref (now the candidate)
  with no relink — a routed proxy resolves by ref at call time;
- this peer's delivered-watermark companion (and its row) is **retained** — the
  swap never closes it (unlike an `evict` departure), so a downstream
  replica-frontier read never sees the member vanish;
- the candidate re-syncs by the same two halves a recovered replica uses:
  **journal replay** (`Stateful.snapshot`/`restore` carries the incumbent's tag
  *counter* forward, so a fresh mint never collides with an already-emitted tag)
  and **anti-entropy** (the re-announce fires the gossip catch-up at every known
  peer). The **surviving replicas play the retained incumbent** — they are the
  state source, so no cross-peer coordination is needed.

`Promotion.promoteReplica` is PRECHECK-then-COMMIT like `promote`, and
`Replication.rebind` is **additive** — single-instance `promote` is byte-for-byte
unchanged, and a graph that never opts into replicated promotion is unaffected.
PRECHECK (no side effects, freely abortable):

- consults the `PromotionJudge` (the same declarative policy as `promote`);
- **refuses a candidate with a different ref** — a fresh ref breaks
  crash-recovery equivalence, orphaning the incumbent's watermark row and
  restarting the tag lane;
- **refuses the T2 fresh-epoch fallback** — a replicated cell re-syncs by
  anti-entropy over the *same* ref-derived lane, so a fresh source is never
  sound (a `NonIdempotentCatchUp` candidate is refused outright);
- checks structural port sameness (93 I-2) on the delta outlet.

COMMIT is the rebind (reuse-ref crash-recovery). Inbound gossip arriving during
the object swap parks at the registry on the despawn's unpublish and replays on
the candidate's republish, so no peer delta is lost.

**Partitioned nodes extend by the same rolling form, shard-by-shard.** A
partitioned node's shards are ref-addressed instances (PN-6); promoting one is
this same rebind. **Promotion is a rebind**, so each swap re-runs link-time
authority — the coverage/interest check the linker already applies (PN-6/PN-7)
is exactly the re-authorization the swap needs, no separate protocol. The
**ordering and abort policy** for a roll (which shard/replica next, when to
halt) is `PromotionPolicy` **data** consulted per instance via the judge — not
new control flow. This resolves the *mechanism* half of the G-50 residual's
"ordering/monitoring/abort policy for partitioned rolling promotion"; the
*authority* half (who may trigger a roll) stays open under §Trust boundary, and
a converged-membership barrier for the flip window remains the R13/PN-19
residual (a covering member the local view has not yet learned of cannot be
waited on).

Verified in `ReplicatedPromotionTest`: a three-peer mesh, a
`GlitchFreeCell.useReplicaFrontier` consumer gating on the merged replica
frontier, a rolling promotion across peer 0 then peer 1 while the others write —
100 seeds converge to the batch union and surface no undelivered element across
both swaps. Controls diverge: **(a)** the T2 fresh-epoch fallback (the tag lane
restarts, so a mint collides with an already-emitted tag) tears the frontier;
**(b)** a fresh `CellRef` orphans the watermark row and re-mints the tag/slot
identity — the removes-fail-to-cover / resurrection failure the reused ref
prevents.
