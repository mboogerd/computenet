# 21 — Propagation: Push/Pull, Incremental/Complete

> **Status**: Partial (push+incremental specified and demonstrated; late-join catch-up, on-demand pull, the catch-up baseline, and the RESTART re-baseline core implemented — the re-baseline's checkpoint-tier and direction residuals stay open under G-42/G-43; the cycle-head model decided in 93, unimplemented)
> **Sources**: ADR 1 (§1, §2, §4), ADR — Cellular Software Development Process (incremental dataflow layer); 93 resolutions I-5, I-6, I-16, I-22, I-24, I-28
> **Implementation**: push+deltas in `civictech.cell.data` (SetCell → UnionSetCell chains); catch-up via `LinkSupport.onLinked`; on-demand pull via `StateRequestProtocol`/`FanOutlet.baselineTo` (`civictech.cell.port`), catch-up baseline via `MessageContext.baseline`/`TagFrontier` and `GlitchFreeCell`'s baseline branch (W2.2)

## Push (default, implemented)

[21-PROP-01] State changes propagate automatically along links as **invocations
on downstream contracts**: the graph SHALL deliver every accepted delta to every
transitively linked consumer, such that at quiescence each consumer's fold
equals the fold of the source's accepted-op multiset. The canonical data-path
contract is:

```kotlin
interface Propagate<D> { fun propagate(delta: D) }
```

A cell receives deltas on inlets, updates owned state, and emits *derived*
deltas on outlets — synchronously within its host (same call stack) or across
a host boundary (queue hop). Whether a hop is sync or async is a **hosting
decision, not a cell-logic decision** (P1): co-hosted chains fuse into direct
calls; cross-host chains pay exactly one enqueue.

## Incremental vs complete

- **Incremental** (default): unbounded/evolving structures propagate deltas
  (`SetDelta(adds, dels)`, counter increments, map diffs). Bandwidth and work
  scale with change size, not state size.
- **Complete**: small bounded values (configs, scalars, small structs)
  propagate whole values; the delta *is* the value. This is a degenerate case
  of incremental, not a separate mechanism.

Normative requirements on a delta type:

1. It has a deterministic application to state: `state × delta → state`.
2. Emission is **effective-only**: a cell emits the delta describing the
   *actual* state change, not the input (see `UnionSetCell`: ref-counting
   emits only when membership actually flips; empty deltas are not emitted).
3. If the cell accepts concurrent producers, deltas (or the cell's state) must
   declare merge semantics (20/24).
4. **Tag hygiene** (M11.2): an emitter of tagged deltas never re-emits a tag
   it previously deleted — that is what keeps a stream safe for
   tombstone-folding consumers (24). Operators may pass input tags through
   only where **both** preconditions hold: (i) every membership flip-ON rides
   a fresh input add-tag on the flipping element, and (ii) the operator
   retracts a borrowed tag only when that tag's own liveness at the input
   ends — never because some *other* input moved. (ii) is what keeps a
   reconvergent (diamond) path safe: consumers fold a tagged set by
   `(element, tag)` and deduplicate a diamond fan-in into ONE fact by design
   (`[24-OP-UNION-01]`), so an operator that retracts a borrowed tag while a
   second path still asserts it retracts that path's still-live contribution
   too. Reconvergence itself is a property of the *graph* and no operator can
   forbid it; what an operator can guarantee is that every reconvergent copy
   of a tag dies together, and only operators whose output membership is
   exactly the borrowed tag's own liveness — filter, map/flatMap, union — do
   so in an arbitrary graph. An operator whose membership flips on some
   *other* input's move cannot, whatever the graph looks like. Everything
   outside that shape
   (intersect, quorum, difference, semijoin/antijoin — re-entry rides the
   *other* side's removal) must mint fresh cell-owned output tags per entry
   and delete exactly what they minted (`MintedTags`, replay-stable derived
   source per the SetCell M10.1 pattern), which is also where 24's
   convergence classes put them (**convergent duplicates**: agree on
   membership, mint distinct tags). Intersect and quorum moved from borrowing
   to minting once borrowing was measured unsound across a diamond
   (computenet-vvre, computenet-s6l2); `JoinLedger`'s KDoc states the same two
   preconditions in code.

## Pull

[21-PULL-01] ADR 1 requires on-demand reads and recomputation: WHEN a consumer
asks for current state (or a recompute) rather than waiting for further pushes,
the producer SHALL bring it to the current state — needed for late joiners, UI
queries, and suspended subgraph reactivation.

[21-CATCHUP-02] WHEN a subscriber links to an outlet after deltas have already
flowed, the outlet SHALL bring the subscriber current such that its subsequent
fold is indistinguishable from an early subscriber's.

**Late-join = catch-up on link** *(implemented, M4.2 — the core of G-18)*:
the post-install `onLinked` hook (13) fires once the new subscriber is
reachable; a data cell wires it to unicast its **state-as-delta-from-empty**
to just that subscriber (`outlet.at(link.to)`), after which the live stream
follows. No snapshot type exists — a snapshot *is* a delta, satisfying the
deterministic-application rule above. Observed-remove tags (24) make the
catch-up idempotent against replayed or duplicated live deltas. Validated:
a late joiner is indistinguishable from an early joiner at idle, 100 seeds,
with a control run proving the harness detects a missed prefix. Across a
membrane, the catch-up unicast MUST pass the boundary's disclosure filter
(40/43) — **filtered, not forked** (decided in
[93 I-28](../90-roadmap/93-feature-interactions.md)): because a snapshot is
a delta, one disclosure transform covers the catch-up and the live stream
uniformly.

**Catch-up is a baseline, not a wave input** *(implemented, W2.2 — decided
in 93 I-24)*. A multi-source fold has no representable wave under
per-source waves (20/22), so a snapshot is never stamped "with the wave it
represents". Instead, catch-up is a **topology-versioned baseline**:
stamped with the producer-outlet wave (FIFO/sequencing, per the I-16 reply
rule below) plus a nullable `MessageContext.baseline: TagFrontier` — a
merge-tag frontier for dedup and incremental pull, never a wave position
(tags and waves stay separate uses of one clock shape, 20/22) — causally
anchored at the stamped link-install event (20/22 §Topology versioning),
and **never admitted to any wave-completeness set**. A glitch-free consumer
installs the baseline as arm state (deduping by tag union, 24) and resumes
evaluation at the first wave that completes over the post-install topology;
the install→first-complete-wave window gets convergence, not simultaneity,
exactly as any topology change does (G-20). *(`MessageContext.baseline`/
`TagFrontier` (`civictech.cell`); `GlitchFreeCell`'s baseline branch forwards
it immediately, bypassing floors/watermark/pending — never buffered, never
counted toward completeness.)*

⚠ GAP (G-39): link/unlink are null-context management ops with no stamp in
the wave domain — glitch-free consumers cannot know from which wave a
new/removed edge counts, source-set changes do not propagate downstream,
and EdgeEvents/floors have no wire form. *Proposal*: in-band
EdgeOpen/EdgeClose markers injected into the affected link's own FIFO
carrying a per-source flushed-high-water floor; design the floor
representation and retention/compaction horizon, hop-by-hop downstream
source-set delta propagation with a liveness proof (an upstream cut must
not strand a waiting join), bridged EdgeEvent frame types ordered against
data across disconnect/park/replay, the floors×cycles×merge-tag
interaction, and the explicit topology-serializing coordinator
(JoinBarrier) cell that doubles as the diamond-over-replica escape hatch
(93 I-13/I-14).

**On-demand pull** *(implemented, W2.2 — closes the G-18 residual, decided
in 93 I-16)*: a consumer asking for a recompute or state *without*
relinking. ⚠ EARS-GAP: the *without-relinking* recompute has no driver-SPI
trigger verb (the SPI exposes `connect`/`apply`/`readView`, not a `requestState`),
so only the link-based catch-up (21-PULL-01 / 21-CATCHUP-02) is boundary-checkable;
the no-relink pull path is unobservable as the SPI stands. *(Half-closed,
V1C-CONCORD: the conformance SPI grew a bounded-read verb, so the no-relink
**read** is now boundary-checkable — [21-PULL-02] below. The recompute half
survives unchanged: nothing in the SPI asks a derived cell to re-emit its
current state.)* Not a new mechanism: a management-class
**`StateRequest(replyTo, since: TagFrontier?)`** on the link's metadata
plane (12, G-13) — null context, no `Owned`/`Leased`, bypasses data-path
parking, idempotent — travels upstream; the reply is ordinary data, a
**single-wave state-as-delta** stamped with one fresh
`Timestamp(producerOutletSourceId, N)` and delivered only to the requester
via `FanOutlet.baselineTo`/`outlet.at(replyTo)` (`since = null` ⇒ full
state-from-empty; `since` present ⇒ only tags beyond the frontier per
source; cells without a per-source-monotonic tag clock fall back to full
state). Per-link FIFO (30/31) makes the reply one contiguous unit ahead of
subsequent live waves. The shipped trigger is subscriber-side: `GlitchFreeCell`
issues a `StateRequest(since = null)` on every fresh `EdgeOpen`, which by
construction never re-fires across a park/replay (the link object survives
a park) — satisfying the fresh-link-⇒-pull and parked→replayed-⇒-no-pull
rows. Producer-side `onLinked` push (above) is retained purely as the
co-hosted fast path — correctness no longer depends on which side observes
the install; the two races harmlessly (observed-remove tags dedupe, 24).
Pull requires `Stateful` and is single-hop by default; recomputation-on-demand
for derived cells = re-emission of current derived state, never
re-execution of history.

*Residual, not gap-tracked*: the dropped→re-resolved row still issues a
full pull rather than an incremental one (no per-link **liveness epoch**
distinguishes a fresh link from a re-resolved one yet — always correct,
merely not minimal); buffer-survival detection, pull-storm coalescing on
mesh heal, and a pull-serves-copy-only rule for non-idempotent/effectful
cells remain open per the original G-37 proposal (93 I-16/I-1) and are
follow-up work, not required by W2.2's single-hop `Stateful` scope.

**Bounded state read** *(implemented, V1C-KERNEL — the read an instrument
uses)*: an instrument reading a cell's state does not need, and must not use,
the pull path above. A pull reply is a **message**: it needs topology to be
received at all (P6), and it installs a baseline in the requester's fold —
correct for a consumer joining a stream, wrong for something that must not
perturb what it measures. A bounded read is neither. It is a direct, paged read
of a locally hosted cell's own state, served on that cell's execution context
between invocations — so a page is never a partially-applied delta — with **one
page per scheduler task**, so a large read interleaves with the cell's real work
instead of owning its thread. (`kernel/src/main/kotlin/civictech/cell/BoundedRead.kt`;
the host accessor sits beside the whole-state one on the managed host.)

[21-PULL-02] WHEN an instrument reads a cell's state under a cursor and a
limit, the framework SHALL answer without emitting, without linking, and
without advancing any wave position, delivered watermark or completeness set.

Wave neutrality is what distinguishes a read from a pull, and it is
boundary-observable rather than an internal claim: every delivery in this model
carries a fresh per-source wave position minted by the emitting outlet (20/22
§Structural changes), so a cell whose wave plane has not advanced across a read
delivered nothing; a delivered watermark and a completeness set both advance
only on a delivery, an absorb-ack or a later wave (20/22 §Watermarks), so
neither can have moved either; and a link installed by a read would announce
itself as catch-up, which is an emission. A `StateRequest` reply, by contrast,
consumes exactly one wave position — it is an ordinary stamped emission — which
is exactly right for the pull path and exactly wrong for an instrument.

A read is bounded in three orthogonal dimensions: by **time** (`since`, the
same tag frontier the pull path takes), by **interest** (`scope`, the sub-state
the reader is entitled to), and by **size** (a cell-minted opaque cursor plus a
hard entry cap). The first two are reused verbatim from the pull request rather
than generalized; the third cannot be expressed on a pull request at all,
because a pull reply is a single message. A family that cannot honour `since`
or `scope` refuses the request rather than answering unbounded state as though
the bound had been applied.

[21-PULL-03] WHEN a bounded read over a state family in which every state
change mints or absorbs a tag is walked to completion and every page carries an
equal frontier stamp, the union of its pages SHALL equal that cell's state at
that frontier.

Stability across a walk is **verifiable, not promised** — the caller checks it
rather than trusting it. A walk is a sequence of per-page-consistent reads, not
a snapshot: snapshot isolation would need either copy-on-write versioning inside
every state cell (a per-message cost on the fold path, forbidden by P2) or
holding the cell's execution context for the whole walk, which is "the
instrument blocks the graph" by construction. Detection is cheaper than locking,
and is the same deal `since` already offers pull consumers. If the frontier
advanced across the walk the union is a **smeared** read: it holds every entry
present for the walk's whole duration, may hold entries added mid-walk, and may
miss entries the walk had already passed — never torn at entry granularity and
never duplicated. The escalation path for a caller who needs a real snapshot is
to record the opening frontier, walk to completion, then issue one further read
with `since` set to it and fold the delta over the union.

The family qualification on [21-PULL-03] is load-bearing, and is why the
requirement is not simply "the union equals the state". Comparing a walk's
opening and closing stamps detects **tag gains, and only tag gains**, which is
the whole of what a tag frontier measures. The set family's observed-remove
now mints its own del-dot alongside the add-tags it already holds
(effective-only removal, above; 24 §Established pattern) — the dot never
enters the add-map, so `membership()` is unchanged, but a *locally applied*
mid-walk retraction now moves the closing stamp and the walk's check catches
it. The surviving counterexample is narrower: the frontier is a per-source
**max**, not a set, so a *reordered remote* `dels` entry whose dot counter is
below a tag this replica already holds from that source changes membership
while raising no maximum. For that counterexample equal stamps are still
*necessary but not sufficient*; the `since` escalation path inherits the same
limit, because it filters out the tombstone's re-used tags along with the adds
they cover; and the cell declares the weakness on its own read rather than
letting the union claim more than it can. This is a property of that family's
tag algebra, not of paging — the pull reply has always reported currency the
same way — and closing it fully (making the frontier a set rather than a
per-source max) is a state-family question filed as research.

⚠ EARS-GAP ([21-PULL-03]): the requirement's antecedent is unreachable from the
conformance boundary as it stands. Every tag-frontier-carrying family in the
standard library is an observed-remove set, so none satisfies "every state
change mints or absorbs a tag"; and a conformance script cannot interleave a
mutation with a walk, so even for a qualifying family only the trivial
(quiescent) instance would be exercised. Filed in `concord/corpus/DISPUTES.md`
rather than covered by a scenario that would read as covered while asserting
only the trivial instance. The at-rest half of the property is separately
carried, and covered, by requirement 24-BOUND-02 (24).

**RESTART re-baselines over this same path** (decided in 93 I-22; the core
implemented, W2.1 — see below). RESTART is *restore + re-baseline*, never a bare local
rollback: the host restores the **freshest** available checkpoint (durable
recovery, imported baseline, pull-merge from mesh/upstream, or the local
`Stateful` checkpoint — spawn-time state only in the degenerate
non-durable, non-replicated, upstream-less case), mints a fresh per-epoch
outlet `sourceId` (93 I-14 S1 — post-restart tags and waves alias nothing
pre-crash), and reconciles downstream through the catch-up path before
resuming live traffic: a `ReBaseline` carrying state-as-delta-from-empty,
the dead epochs' `sourceId`s (`supersedes`), and a mode —
**push-authoritative** (`supersede = true`, single-writer roots: a
convergent consumer drops un-reasserted tags from superseded sources,
merges the state by tag union, and thereafter rejects deltas from those
dead lanes) or **pull-merge** (derived/replicated cells: ordinary
idempotent catch-up via `requestState`/mesh anti-entropy, no retraction).
[21-REBASE-01] WHEN a source re-baselines (RESTART or re-baseline), the framework
SHALL reconcile downstream consumers so their folds converge to a value
consistent with the restored state (equal to a delta-only twin). This
re-establishes deterministic application and effective-only emission downstream
after a producer reverts.

*(Conflict C-12 resolved, W2.1 + D-C12 — the core landed, the residuals stayed
where they were. The supervision path is the one described above, not the bare
rollback the M3.5 text used to describe: a RESTART bumps a host-held generation,
mints a fresh per-epoch `sourceId` on every outlet and collects the superseded
ids, restores the checkpoint, then re-baselines over this catch-up path with
`supersede = true`, and a convergent consumer drops the un-reasserted tags of
the superseded sources and fences them as dead lanes. Post-restart tags and
waves therefore alias nothing pre-crash, and downstream is reconciled rather
than left divergent. `[21-REBASE-01]` is covered by
`concord/corpus/21-propagation/21-REBASE-01.yaml`. **Still open, as the
residuals they always were**: R3's freshest-checkpoint *tiers* — the landed
restore takes the local supervision-time checkpoint, which I-22 itself names the
degenerate non-durable case (93 I-25, G-43); R4's pull-merge direction, the
landed call always passing `supersede = true` (G-43); and epoch/generation
reclamation (G-42).)*

## Fusion and the critical path

Per P2, propagation MUST NOT introduce avoidable hops:

- Within a host, a chain `A → B → C` executes as nested direct calls
  (delegation flattening, 10/14, removes pass-through cells entirely).
- Backpressure stalls and context switches are minimized by fusing co-hosted
  work; the host queue is the only asynchronous boundary (30/31).
- The one decided exception: a `CycleHead`'s re-origination is a fusion
  barrier — it enqueues even co-hosted (see §Cycles; decided in 93 I-6).

## Cycles

Graphs MAY contain cycles (feedback loops, UI↔model sync, learning). The
cycle model is decided (93 I-5/I-6), unimplemented — except the `Magnitude`
interface itself, which landed with M17 (`cell.control.Magnitude`, the exact
I-6 contract) as the carrier for magnitude-band dispatch (34 decision 7);
the head/feedback/hop-guard/admission machinery below remains unbuilt.
The first weak-tier consumer is the `:agora` argumentation app (M17): it
approximates the head model in application code — its topology-owning
service designates each cycle-closing edge a head and puts the `quiescence`
threshold on that edge's inbound feedback inlet, gating re-origination and
never the outbound broadcast — and its step-budgeted 100-seed exit test is
the empirical probe for weak-tier quiescence (G-19 residual):

- **Re-origination at declared heads** (93 I-5). Every cycle MUST declare at
  least one **`CycleHead`** with a **`feedbackInput`** — a feedback inlet
  declared distinctly from ordinary wave-joining inlets. Feedback is
  **absorbed into loop state, not joined**: the incoming wave terminates at
  the head (it never enters the head's glitch-free completeness condition),
  and the head mints a **fresh** `Timestamp(headSourceId, ++counter)` for
  the next iteration — the one precisely-located exception to transparent
  flow (20/22 rule 2). Iteration = wave: no `(sourceId, counter)` traverses
  any cell twice, so glitch-free cells inside loops need no re-entry
  detection, and "the topology version iteration *N* sees" is simply the
  edge set feeding wave *t_N* (20/22 §Topology versioning). **Fixpoint =
  the state after the terminating sequence of head-minted waves settles.**
- **Two-tier quiescence** (93 I-6), keyed on KSP-verified descriptor bits
  (`magnitude`, `idempotentMerge` — read at link time, never reflection):
  - *Strong (structural) tier* — idempotent-merge deltas (tag-union,
    pointwise-max, 24) quiesce **by construction, threshold-free**:
    effective-only emission (rule 2 above) derives an empty delta from a
    lap carrying no new tag information, and empty deltas are not emitted.
    Gossip-mesh quiescence (40/42) is this tier's structural special case,
    not a second mechanism.
  - *Weak (best-effort) tier* — `Magnitude`-only deltas
    (`size(): Double`, ≥ 0, `0.0` ⇔ no effective change) get a
    per-feedback-inlet `quiescence` threshold as a divergence *damper*, not
    a proof; termination for this tier rests on the hop guard alone.
- **The throttle gates re-origination, never fan-out**: the
  absorb/re-originate decision is made at the head's `feedbackInput` on the
  *returning* lap — "absorb without **re-originating**", refining the
  earlier "absorb instead of emitting". The head's outlet broadcast is
  never gated, so no external subscriber is silenced by cycle throttling.
- **Hop guard**: `MessageContext.hop` increments per transparent-flow hop,
  is reset to 0 by head re-origination, and is never part of the wave join
  key; exceeding a host-configured bound dead-letters the invocation as a
  `CycleError`. In a correctly-headed graph it never fires; it is the
  backstop for headless loops and cross-host cycles no link-time check can
  see.
- **Admission and payloads**: [21-CYCLE-01] IF a `connect` closes a
  locally-visible cycle, THEN it SHALL be `Rejected` unless the cycle contains a
  head (`CycleWithoutHead`) and every data edge is throttle-capable
  (`CycleRequiresMagnitude`); `Leased` is forbidden on cycle edges
  (`CycleRejectsLeased`, 20/23); `Owned` is permitted — absorption *is* the
  single consumption (`take()` into loop state).
- **A `CycleHead` is a fusion barrier** (§Fusion): re-origination MUST
  enqueue on the host queue even co-hosted, bounding stack depth to O(1)
  per lap and serializing laps through the queue.

The honest guarantee: [21-CYCLE-02] **every admitted cycle SHALL reach quiescence
in a bounded number of laps** (bounded-lap termination — structural quiescence
for the strong tier; the hop-guard dead-letter as the hard backstop otherwise);
[21-CYCLE-03] **WHERE a cycle's feedback deltas are idempotent-merge (strong
tier), the loop SHALL converge to a fixpoint** — the weak tier converges only if
its loop map is contractive w.r.t. `size()`, which the framework does not verify. Quiescence is
lap-based, not time-based: no timers, no virtual-time dependency.

⚠ GAP (G-19, residual): weak-tier fixpoint convergence — a *meaningful*
fixpoint for non-idempotent (numeric) loops — remains an open research
item; the decided structure guarantees termination, not convergence, for
that tier.

⚠ GAP (G-41): the adopted CycleHead/threshold structure leaves admission
and well-formedness holes — cross-host cycles are invisible to link-time
checks, multi-head/nested/multi-tier cycles have no stated rule, hop
bounds are uncalibrated magic numbers, and head behavior under
RESTART/promotion is unpinned. *Proposal*: distributed cross-host cycle
detection (or an explicit hop-guard-only stance) tied to
peering/announcements; a ≥1-head-per-elementary-cycle well-formedness rule
with detection over the cycle basis; a multi-tier mix admission policy;
hop-bound calibration against loop diameter; feedback-join
consistent-snapshot semantics; membrane-scoped lap quiescence; plus
generation-bump (RESTART) and swap-on-live-cycle behavior at a head —
weak-tier fixpoint convergence itself stays open under G-19
(93 I-5/I-6/I-17/I-22).
