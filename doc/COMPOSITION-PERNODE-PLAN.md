# Per-node composition — gap analysis and plan

> **Generated**: 2026-07-25 · pinned to `main` @ `809f25c` (composition run complete @ `d40e4ad`).
> **Goal**: not just any-natures-in-one-*graph* (the composition run's result) but
> any-natures-on-one-*node*: a single logical cell simultaneously partitioned AND
> replicated, durable AND glitch-free, pull-capable AND sharded — any combination.
> **Method**: gap analysis against `doc/COMPOSITION-STATUS.md` / `doc/FEATURE-STATUS.md` /
> `doc/CHANGELOG-composition.md`, then three independent Opus design explorations
> (instance-set algebra · same-node pairwise semantics · uniform policy-slot stratum),
> then a spec sweep (`doc/spec/23/34/43/31/33`) for per-node-combinable features the
> explorations missed (§4b), synthesized here. Every load-bearing claim was verified against code with `file:line`
> citations in the underlying explorations; the most important ones are repeated here.

---

## 1. The gap, in four layers

1. **Heterogeneous attachment points.** Each nature attaches through a different
   mechanism at a different stratum: glitch-free = per-inlet policy slot
   (`FanInlet.frontierPolicy`); durability = *host* ctor selector (`journalFor`);
   replication = external coordinator (`Replication` + `LocationRegistry.setInterest`);
   partitioning = a composite cell *structure* (`PartitionedCell` + router); push/pull =
   per-link extension fn (`catchUpOnLinked`). No single place declares a cell's composed
   nature.

2. **Structural blockers.** `ShardCell` is not a dataflow cell: no outlet, not
   `Stateful`, not `Replicable` — its output escapes only via the direct object call
   `membership()`. So partitioned+durable, partitioned+replicated, and
   pull-from-partitioned are *unbuildable*, not merely untested. This — not the
   `Interest` predicate — is why partitioning is the worst axis in the pair matrix
   (11 of 14 pairs empty).

3. **Undefined same-node interaction semantics.** Journal replay through a
   `WaveFrontier`; replica failover vs `routingEpoch`; `StateRequest` into a
   partitioned cell; promotion of a replicated cell. All undefined — and two are
   *actively wrong* today (see §2).

4. **Detection can't see the structural natures.** `NatureVector` covers 4 axes,
   none structural, and doesn't cross the wire (CP-G2). Worse, two link paths bypass
   the handshake entirely (`FanOutlet.tap`, `streamTo`) — so nature reconciliation
   has never run on the exchange demo's own mesh, which is built on `streamTo`.

## 2. Findings that reframe the work (discovered during exploration)

These are defects/conflicts in what exists, found by the three explorations and
worth acting on regardless of the larger plan:

- **F1 — Silent drop at `WaveFrontier.offer`** (`WaveFrontier.kt:178`).
  `MessageContext.sourcePort` is an *ephemeral* random UUID (`PortRef.generate()`),
  never derived from `(cellRef, portName)`. Any delivery whose edge can't be matched —
  a replayed journal frame after restart, a `streamTo`/tap producer, a duplicate
  edge — is discarded with no diagnostic. Journal replay into a frontier inlet
  loses the entire journal. The exchange demo survives only because of an
  unwritten invariant: only context-free root cells are journaled.
- **F2 — Latent liveness conflict between two decided designs.** Replica-set
  completeness quantifies over *all* members (`Replication.kt:99-106`,
  `members.all { … }`), but under disjoint `Interest` an instance never delivers
  waves outside its slice — its watermark row stays at bottom forever, so a
  `WaveMode.WAIT` consumer stalls permanently. Latent only because shards aren't
  yet replication instances; it fires the moment they become one.
- **F3 — `WatermarkCell.close()` is never called from main.** `Replication.evict`
  despawns but never closes the watermark slot, so any member departure wedges
  every downstream replica-fed frontier forever.
- **F4 — Checkpoint data-loss hazard.** `ManagedHost.checkpoint` snapshots only
  `Stateful` cells, then unconditionally `journal.reset(...)`. Checkpointing a
  journal whose only cells are non-`Stateful` (e.g. a journaled `ShardCell`)
  truncates the WAL and destroys the data.
- **F5 — Handshake bypasses.** `streamTo` (`StreamTo.kt:14-20`) and `tap`
  (`FanOutlet.kt:193`) skip `handshake()`: no policies, no allowlist, no
  `reconcile`, no `EdgeOpen`. `LinkRole.Observe` is declared and passed nowhere.
- **F6 — Interest algebra is open and wire-hostile.** The flip window installs
  anonymous non-serializable `predicateInterest`/`unionInterest` objects on every
  shard (`PartitionedCell.kt:447-455`), whose `overlaps` conservatively returns
  `true` — blocking CP-G4 and lying to the linker.
- **F7 — Silent over-delivery to partial interests.** `Replication.scopeToInterest`
  rides a non-`Scoped` delta *whole* to a partial-interest target
  (`Replication.kt:277-278`); `MapDelta` is not `Scoped`, so aggregate deltas
  ignore interest entirely.

## 3. Thesis

**A logical cell is a set of instances; per-node composition is the cartesian
product of one interest lattice with per-instance property slots that already
exist.** Three of the six target natures are *already* per-instance
(journal — `journalFor` keys on `CellRef`; frontier — per inlet; interest — per
instance); they go uncombined because nothing produces heterogeneous instance
sets and the instance is not uniform. The plan therefore has two legs:

- **Make the instance uniform**: every instance (shard, replica, shard-replica)
  is a full dataflow cell — outlet, `Stateful`, `Replicable`, `StateRequest`-serving —
  with replay-stable identity derived from its ref.
- **Make the properties orthogonal and declarable**: per-inlet/outlet policies with
  a fixed tier discipline; per-cell durability/interest declarations resolved by
  host/registry; one baseline primitive for all six state-transfer sites; a
  layered nature vocabulary where *refusal* stays minimal and structural natures
  are manifest, not refusing.

Two supporting rules the explorations converged on:

- **Rule of settlement**: wave `(s,t)` touching keys `K` is replica-complete iff
  for every `k ∈ K`, every live, open instance *whose interest admits `k`* has
  delivered it. The same expression collapses to today's behavior (all-Total),
  the partition rule (disjoint), and the sharded-replication quorum (overlap) —
  resolving F2 rather than papering over it.
- **Rule of recovery**: journal replay is a *baseline*, not a wave. Glitch-freedom
  is a property of the live wave plane; durability is a property of the state
  plane; a recovery produces no waves and therefore no glitches. (Same rule
  already decided for `StateRequest` replies.)

One reconciliation between the explorations: interest **assignment** is both a
hosted invocation (so it is journaled, replayed, addressable by ref — required by
durability and CP-G4) *and* distributed as a `Replicable` epoch-max lattice (so
routers/replicas converge without an arbiter — required by partitioned+replicated).
These are the same design at two layers: the assignment cell is a hosted
`Replicable` cell; the epoch moves off `RoutedCommand` payloads onto the
`(interest, epoch)` register, and admission always checks the *current* interest
(which `ShardCell.onRouted` already does — the epoch on traffic is decorative today).

## 3b. Spec sweep — the axes the structural six left out

A pass over `doc/spec/` (ownership 23, scheduling/attention 34, security 43,
hosts 31, mobility 33) for features meaningfully combinable *within one node*
that the three explorations under-covered:

| Axis | Per-node combination | Status in spec | Disposition |
|---|---|---|---|
| **Effectful** (31, G-59) | effectful+replicated: every replica fires the external effect; effectful+partitioned: disjoint interest is effect-once *by construction* | processed-frontier per inlet landed (W2.6); replica-set effect authority undefined | **PN-17** |
| **Ownership** (23) | `Owned` routed into disjoint shards = legal move; `Owned`/`Leased` under Total/overlap violates consumed-exactly-once | SPSC rule normative; `OWNERSHIP` axis already exists | **PN-18** — refusal, not mechanism |
| **Attention** (34) | per-instance park; interest scattering by key range; parked covering-quorum member | decisions 3/5 + typed `Stall`/`Resume` family (93 I-16/I-18) **decided, unimplemented**; per-key economics = G-62/M16 | **PN-19** — and it supplies the designed answer to PN-7's DEGRADE gap |
| **Disclosure** (43) | replication across a trust boundary with partial visibility | `disclosure: Project(ProjectionId)` is a registered pure `Delta → Delta`; "one filter covers both the onLinked catch-up and the live stream (a snapshot IS a delta)" | fold into **PN-6/PN-9**: disclosure projection and interest scoping are the *same* FILTER-tier slicing — a trust-scoped replica's effective interest = declared interest ∩ disclosure. One mechanism, not two. |
| **Promotion × partitioned** (43, G-50 residual) | rolling rollout across shards | spec already names "ordering/monitoring/abort policy for partitioned rolling promotion" as the open residual; "promotion *is* a rebind and MUST re-authorize" | extend **PN-14**'s rolling form from replicas to instance sets; re-authorization rides the existing link-time seam |
| **Magnitude** (34 d.7) | rides *with* the data; routed slices keep their payload | placement in the authority lattice decided | nothing to build — falls out |
| **Cycles** (21/34, E5/R16) | a cyclic region spanning a replicated/partitioned node | nested/cross-host cycles research-gated | **out** (research), recorded §5 |
| **Mobility × durability** (33, 31) | migrating a journaled instance: WAL is host-local | host is the unit of mobility; journal handoff unspecified | detection covered by PN-12's spawn check (DURABLE cell on journal-less host refused); checkpoint-then-move handoff recorded §5 as residual |

Two of these (attention/`Stall`, disclosure) are *decided* spec design waiting
for exactly the substrate this plan builds — strong evidence the instance-set
direction is aligned with the spec's own trajectory rather than a new layer on
top of it. Note also spec 34 decision 5's authority lattice: "promotion,
shadows, partitions, fusion, and construction are placements in this lattice,
not exceptions to it" — the plan's policy/instance machinery must slot under
that precedence order (management > admission > suspension gate > banding >
FIFO), never bypass it.

## 4. The plan

Phases are ordered by dependency; each ticket is house-style (make cited spec text
true; named test up front; 100-seed generative where applicable; controls that must
diverge; no-behavior-change for graphs that don't opt in). Lanes within a phase are
file-disjoint and can run in parallel.

### Phase 0 — surface the defects (small, immediate, parallel-safe)

- **PN-0a** F1 diagnostic: `WaveFrontier.offer`'s unmatched-edge path dead-letters
  (or at minimum logs) instead of bare `return`. Not the fix — the tripwire.
- **PN-0b** F4 guard: `checkpoint` refuses (`require`) to reset a journal whose
  selected cells include a non-`Stateful` cell.
- **PN-0c** F3 one-liner: wire `WatermarkCell.close()` into `Replication.evict`
  and unpublish. Control: departure without close → downstream replica-fed
  frontier stops producing on every seed.

### Phase 1 — identity + baseline substrate (the two root causes)

- **PN-1 Replay-stable port identity.** Derive `PortRef` from `(cellRef, portName)`
  via `nameUUIDFromBytes` — the exact pattern `SetCell.tagSource`, `MintedTags`,
  and `watermarkRef` already use. Fixes F1's root for *every* consumer of
  `sourcePort`. Highest-leverage single change in this document.
- **PN-2 Replay is a baseline.** `ReplayScope` thread-local (analogue of
  `PendingReBaseline`): during `recoverFrom`, every emission in the replayed cone
  carries `baseline = frontier`, taking the frontier's existing exclusion path.
  Unify the now-seven state-as-delta-from-empty sites on one `Baseline(frontier,
  scope: Interest?, epoch?)` shape; switch `catchUpOnLinked` from bare
  `at(link.to).propagate` to `baselineTo` so push and pull catch-up are marked
  identically (removing an accidental works-because-context-was-null dependence).
  - Test `DurableGlitchFreeReplayTest`: journaled *mid-graph* cell (non-null
    context frames — the case no test covers) feeding one arm of a WAIT diamond,
    volatile other arm; kill, recover, resume. Assert
    `released + suppressed == journal.replay().size` so silent drops fail loudly.
    Controls: ReplayScope off → stall-or-drop; PN-1 reverted alone → still green
    (proves the halves are independently load-bearing).
  - **Closes A–D (durable+glitch-free) — the vision's first-named pairing.**

### Phase 2 — the shard becomes a cell; the algebra closes

Three lanes, file-disjoint:

- **PN-3 (lane data) Interest algebra + scoped aggregates + scoped pull.**
  (a) `Interest` gains `Empty/Union/Intersect/Complement/Ranges` as serializable
  data classes with honest `overlaps`; delete the anonymous combinators (F6).
  (b) `MapDelta : Scoped` (F7) — prerequisite for sharding any aggregate.
  (c) `StateRequest` gains `scope: Interest?` (absent ⇒ Total ⇒ today verbatim)
  and consumer-side `since` becomes **per-instance vector**, never a merged
  scalar (a pointwise-max merge across shards silently loses tags — shard tag
  holdings are non-contiguous by construction).
- **PN-4 (lane cell) `ShardCell` grows up.** `Stateful` (snapshot =
  `TagState + interest + assignedEpoch`) + outlet + `deltaInlet` +
  `StateRequest` handler. Router gains `rebuildFrom(shards)` — the routing table
  becomes recoverable by asking the shards.
  - Test `ShardJournalReplayTest`: per-shard WALs, repartition, checkpoint, kill
    all, recover, rebuild router from shards. Controls: rebuild from
    `initialInterest` (today) → shed range resurrects, double-count; checkpoint
    reset without Stateful contributor → shard returns empty.
  - **Closes durable+partitioned.**
- **PN-5 (lane pull) Scatter-gather pull.** A pull against a partitioned logical
  id fans to every interest-overlapping instance; each replies `baselineTo` with
  its own frontier; freshness is per-shard-consistent, cross-shard-arbitrary
  (honest and sufficient: a baseline is never a wave).
  - Test `PartitionedPullTest` incl. incremental second pull with retained
    per-shard `since`. Control: merged scalar `since` → tags lost on some seed.
  - **Closes pull+partitioned. CP-G3 becomes buildable as written** (the losing
    shard now *has* a link path to serve); fold CP-G3's ledger deletion into
    Phase 3 rather than running it before PN-4.

### Phase 3 — one linker, one assignment, one settlement rule

Sequential (same files as each other; heavy `PartitionedCell.kt`/`Replication.kt`):

- **PN-6 Assignment as a hosted `Replicable` lattice.** `(interest, epoch)`
  epoch-max register per instance, applied as a hosted invocation (journaled,
  replayed, ref-addressed) and gossiped on the existing mesh (no second
  protocol). Epoch comes off `RoutedCommand` payloads (kept as a deprecated
  ignored field one release; `WireCodec` keeps decoding old frames). Admission
  rule everywhere: older epoch → filter through current interest (today's
  behavior); newer → adopt assignment, then apply. Generalize the linker: keep
  exactly one link/slice mechanism (`maybeLink` + `Scoped.within(keyOf)`),
  re-express `PartitionedShardSet.route/emit` on it, delete `routed` and scope
  or delete `ledger` (**subsumes CP-G3**; router state asserted O(instances)).
  Coverage invariant for reassignment: an instance's interest may shrink only
  after the gainer covers the shed range — gated on the gainer's watermark
  (settlement machinery reused). The flip window keeps a single routing
  authority per logical cell; making it leaderless is R1-grade research and is
  explicitly out of scope — say so in the spec.
  - Test `InstanceSetSubstrateTest`: one logical id, three instances,
    assignment parameterized over {all-Total, disjoint, overlapping};
    convergence to batch oracle; link count == overlap count; journaled
    instance's crash+replay preserves a shed. Controls: shed as direct call →
    replay resurrects; overlap + non-commutative merge → order divergence.
  - `PartitionedShardsAcrossHostsTest` / `PartitionedCellTest` /
    `InterestScopedGossipTest` / `ExchangeCompositionExitTest` green unchanged.
  - **CP-G4 becomes a few lines** (instances addressed by ref; interests
    serializable since PN-3a).
- **PN-7 Interest-scoped settlement** (fixes F2). `ReplicaFrontier.completeAt`
  gains the key; quorum = the covering subset (see Rule of settlement, §3).
  Defaults (empty `originKeys`) ⇒ unfiltered quantifier ⇒ today verbatim.
  - Test `ShardedReplicaFrontierTest`: board fed by an instance set both sharded
    (2 slots) and replicated (2 copies/slot); never surfaces an uncovered value,
    120 seeds. Controls: today's `members.all` → **wave never releases** (the
    executable demonstration of F2); trivial frontier → board tears.
  - **Honest gate**: filtering *shrinks* the quorum, so an unannounced instance
    whose interest would admit `k` causes premature release. R13
    (creation-fenced membership: a new instance's watermark row must exist
    before its first delta is admitted) is **promoted from optional to
    blocking** and lands inside this ticket. DEGRADE has no quorum-shrink
    analogue — under sharded replication it behaves as WAIT; documented, not
    hidden.
- **PN-8 Sharded replication end-to-end** *(needs CP-G1 for the overlap case)*.
  3 shards × 2 replicas, overlapping range, repartition racing failover, real
  bridges; overlap without a declared merge is `Rejected(mismatch)` (reuses
  `MERGE_IDEMPOTENCE`) — **partition-with-overlap IS replication, and
  replication requires a merge; refuse the combination when the merge is
  absent.**
  - **Closes partitioned+replicated (the convergent half; flip-window
    atomicity stays single-authority).**

### Phase 4 — uniform stacking + declaration (parallel lane, mostly `port/` + `gen/`)

Can start alongside Phase 2; PN-12 must land **after CP-G2**.

- **PN-9 Policy tiers.** `FanInlet` gains an ordered policy list with fixed tiers
  **ADMIT** (may drop, never hold; must declare `mintsProgressAck` — the CP-A3
  absorb-ack lesson made a law) → **GATE** (hold FIFO, never drop) → **ALIGN**
  (reorder; at most one — two independent reorderings compose to nothing) →
  **ACTIVATE** (cold-park). Install order irrelevant; tier order authoritative
  (the code already hard-wires ALIGN-before-ACTIVATE). `frontierPolicy` stays as
  deprecated sugar installing one ALIGN policy. Outlet side: FILTER — interest scoping and
  disclosure projection unified as one slicing tier (see §3b), disclosure pinned
  last so no later policy can widen a redaction — / ON-LINK (multicast via the *existing* `onLinkedListeners` list —
  fixing `catchUpOnLinked`'s documented slot-stomp that `Replication` today works
  around by reaching in and re-firing the hook). Extract the requester/server
  halves of pull out of `WaveFrontier`/`SetCell` into installable policies
  (currently you cannot get pull without glitch-freedom or vice versa).
  Also: `FanInlet.at()` must route through the policy chain or be documented
  policy-exempt (today it bypasses the frontier gate entirely).
- **PN-10 `Link.role` + close the bypasses** (F5). Add `role` to `Link`; filter
  `expectedLocalEdges` to `Consume` (an Observe edge must never gate a wave);
  `tap`/`streamTo` gain `negotiated = true` opt-in through `handshake(role =
  Observe)`; flip the default in PN-12 with the demo's 100-seed + two-JVM tests
  as the gate (the one deliberately observable change in the plan).
- **PN-11 Buffering extraction** *(independent, any time)*: one `ParkQueue<T>`
  (append-in-order / hold / drain-once) under the five hand-rolled park sites.
  Mechanical; deliberately not forced into the policy model.
- **PN-12 Vocabulary** *(after CP-G2)*. Two tiers:
  - **Refusing axes — add exactly two**, both clearing the "fails silently
    today" bar the existing four met: `WAVE_PARTICIPATION` (an unwaved/
    non-announcing producer into an ALIGN inlet is silently dropped today —
    F1/F5) and `INSTANCE_SCOPING` (a non-`Scoped` delta to a partial-interest
    instance silently over-delivers — F7). **Durable/replicated/partitioned do
    NOT refuse at links** — a volatile consumer of a durable producer is normal
    (the exchange demo is exactly that); the COLOR lesson generalizes.
  - **`CellManifest` — declares, never refuses**: sparse set
    {GLITCH_FREE, DURABLE, REPLICATED, PARTITIONED, PULL_SERVING, GATED} on
    `CellDescriptor`, KSP-derived from marker interfaces (CP-F2 pattern, no new
    annotations), consumed by spawn-time checks (a `DURABLE` cell on a host
    whose selector yields null = today's silent data-loss misconfig → refusal),
    diagnostics, and the wire (free on CP-G2's sparse encoding).
    `ManifestDriftTest` pins declared == installed across kernel + demos.
- **PN-13 `InstanceSetStep` in GraphSpec.** The composed-node declaration:
  `instances = f(interestPartition, replicationFactor)`, each instance carrying
  (interest, placement hint, journal id, frontier policy). Legal only because
  PN-6 made assignment a management invocation — "the DSL gains parameters, not
  verbs" is preserved. Control: a fully-default declaration lowers to a
  GraphSpec `equals` to the hand-written one.

### Phase 5 — cheap closures + evidence

- **PN-14 Rolling replicated promotion** *(parallel-safe with everything;
  `evolve/`+`replication/` only)*. Closes K–B **by constraint, not
  construction**: promote one instance at a time; candidate reuses the
  incumbent's `CellRef` (every mesh identity — tag source, minted tags,
  watermark slot — derives from the ref, so the swap is indistinguishable from
  crash-recovery of the same instance, which is the mechanism, not a trick);
  surviving replicas play the retained-incumbent role; T2 (fresh epoch) is
  *refused* for replicated cells (one replica re-epoching while peers gossip
  the old lane is exactly the double-count `NonIdempotentCatchUp` exists to
  refuse). Set-atomic promotion stays in the R1 bucket. The same rolling form
  extends to *partitioned* nodes — shard-by-shard rollout is exactly the
  "partitioned rolling promotion" residual spec 43/G-50 already names, with its
  ordering/abort policy expressed as `PromotionPolicy` data; and promotion is a
  rebind, so each per-instance swap MUST re-run the link-time authority seam.
- **PN-15 Evidence join.** CP-G5 as written, plus a sharded-AND-replicated arm
  into the exchange board (Phase 3's payoff visible in the one graph that is
  the composition evidence) and a manifest assertion (board `{GLITCH_FREE}`,
  writers `{DURABLE}`, union `{REPLICATED}`, shards `{PARTITIONED}`). Update
  the pair matrix in `COMPOSITION-STATUS.md`.
- **PN-16 Re-scope CP-G7 before spending it.** The spike now has (a) its
  requested counterexample — the asymmetric diamond: `expectedLocalEdges`
  counts every open edge, and an edge that structurally never carries a source
  can never settle it; and (b) a changed question — after PN-7, settlement
  already quantifies over a registry-discovered instance set, i.e. a dynamic
  frontier component achieved *without* multiplex ports; and (c) PN-10 removes
  the non-announcing-link counterexamples. Ask: is static-links + absorb-acks +
  interest-scoped quorum + per-edge declared source sets sufficient? Likely a
  spec paragraph, not a mechanism.

### Phase 5b — axes from the spec sweep (§4b)

Three tickets covering natures the structural six left out. All post-Phase-3
(they need instance sets to exist); PN-18's refusal half can land with PN-12.

- **PN-17 Effect authority on an instance set.** `Effectful` sinks × replication
  is undefined: every replica of an effectful cell fires the external effect.
  Two spec-grounded rules: (a) **disjoint interest gives effect-once by
  construction** — each delta reaches exactly one instance, and the per-inlet
  processed-frontier (W2.6/G-59) already dedups replay — so effectful+partitioned
  is free; (b) effectful + Total/overlapping interest requires a declared
  **effect authority**: the leader of a `SingleWriterReplication` set fires,
  followers suppress using the exact machinery `Shadow.spawn` already has
  (NoOp-serving `Effectful` inlets, `Evolution.kt:55-71`). Absent an authority
  declaration, the combination is *refused* at instance-set formation.
  - Test `ReplicatedEffectTest`: 3-replica effectful sink under
    `SingleWriterReplication`; the external effect fires exactly once per
    logical delta across leader handoff. Controls: authority off → effect fires
    N times (today's behavior, made visible); disjoint-interest variant with no
    authority → still exactly-once, proving (a).
- **PN-18 Ownership × instance set: refuse by existing vocabulary.** The SPSC
  rule (spec 23: `Owned`/`Leased` = exactly one downstream consumer; MUST NOT
  cross into Broadcast) extends verbatim: an exclusive-carrying port may join
  an instance set only under **disjoint** interest (the router delivers each
  payload to exactly one covering instance — a legal move-by-serialize);
  Total/overlapping interest would deliver one `Owned` to N instances and is
  `Rejected(mismatch)` on the *existing* `OWNERSHIP` axis at instance-set
  formation — no new vocabulary. `Leased` stays refused across any instance
  boundary (already the rule for wire/cycle edges). Buffering at the router flip
  window preserves exclusivity (spec 23: "the Buffering proxy MAY hold them").
  - Test: `OwnedRoutedShardTest` — `Owned` payloads routed through a
    repartition, every payload consumed exactly once, none duplicated or lost
    (the G-46 dead-letter contract applies to the parked window). Control:
    overlap admitted → double-consume detected.
- **PN-19 Attention, parking, and the quorum: the `Stall` family.** Three
  sub-parts, all *decided* design (93 I-16/I-18, spec 34 decisions 3/5),
  unimplemented:
  (a) **attention scatters by interest** — upstream interest through a
  partitioned node propagates only to instances whose interest overlaps the
  attending consumer's scope (the metadata plane reuses the data plane's
  overlap rule; the economic per-key routing beyond this stays G-62/M16);
  (b) **an unattended instance parks like any cell** (attention is per-cell
  already; once shards are real cells with outlets, per-instance park falls
  out) — but the router/mesh must see a parked peer as *stalled, not dead*;
  (c) **this closes PN-7's DEGRADE gap by design, not by fiat**: the typed
  `Stall(reason, recoverable)`/`Resume` frontier-event family (spec 34
  decision 3, "the notice generalizes") is exactly the quorum-shrink
  vocabulary the interest-scoped settlement lacks — a suspended or departing
  covering instance publishes `Stall`; a WAIT consumer holds (today), a
  DEGRADE consumer removes it from the covering quorum and restores on
  `Resume`, treating post-resume replay as catch-up baseline (PN-2's
  primitive); terminal stalls (dead-lettered, evicted) get RE-SCOPE +
  `GlitchViolation`. PN-0c's `WatermarkCell.close()` is the degenerate
  terminal case of this family.
  - Test `PartitionedAttentionTest`: interest in one key range parks only the
    non-covering shards; a DEGRADE board keeps producing across a covering
    instance's park/resume with no torn value. Controls: WAIT variant stalls
    during the park (correct, documented); Stall suppressed → DEGRADE board
    tears or wedges.

### Existing tickets — resequenced

| Ticket | Disposition |
|---|---|
| CP-G1 (mergeable aggregates) | **Run now, in parallel** — but it is a *prerequisite* for PN-8's overlap case, not an independent lane; and its `MapDelta` must land `Scoped` (PN-3b) or it delivers a Replicable that cannot be sharded. Its Files list should add `demo/exchange/Main.kt` (where `MapMergeCell`, the class it deletes, actually lives). |
| CP-G2 (vectors cross the wire) | **Run now, in parallel.** Gates PN-12; its unknown-axis-ignore rule is what makes manifest axes wire-safe by construction. |
| CP-G3 (retire `routed` ledger) | **Do not run as written.** Needs PN-4 first (the losing shard must have a link path); its deletion half is subsumed by PN-6; deleting the ledger before PN-4 moves the durability hole instead of closing it. |
| CP-G4 (bridged repartition) | Unblocked by PN-3a (serializable interests); near-free after PN-6. |
| CP-G5 (evidence join) | Becomes PN-15 (superset). |
| CP-G6 (Adapt arm) | **Stays trigger-gated, with a better trigger**: watch PN-9 install sites — two independent graphs installing the same ADMIT+GATE+ALIGN triple is the new trigger; different triples close it with better evidence than it has now. |
| CP-G7 (frontier traversal spike) | Becomes PN-16 (re-scoped). |

### Dependency sketch

```
Phase 0  PN-0a ∥ PN-0b ∥ PN-0c                    (immediate)
Phase 1  PN-1 → PN-2                              ∥ CP-G1 ∥ CP-G2
Phase 2  PN-3 ∥ PN-4 ∥ (PN-9, PN-10, PN-11)      (PN-5 after PN-3+PN-4)
Phase 3  PN-6 → PN-7 → PN-8                      (PN-8 needs CP-G1)
Phase 4  PN-12 (after CP-G2) → PN-13 (after PN-6)
Phase 5  PN-14 (any time) · PN-15 (last) · PN-16 (before any G7 spend)
Phase 5b PN-17, PN-19 (after Phase 3) · PN-18 (refusal half with PN-12)
```

## 5. What stays out, honestly

- **Leaderless flip-window / set-atomic promotion** — R1-grade; both semantics in
  this plan are chosen specifically not to need them (single routing authority;
  rolling promotion).
- **Aggregates without a lattice** (top-k, percentiles, order-dependent folds)
  cannot be sharded-replicated — excluded by construction, matching the spec's
  existing aggregator-family exclusion. A limit, not a backlog item.
- **DEGRADE quorum-shrink** under sharded replication — interim-documented at
  PN-7; the designed closure is PN-19's `Stall` family, so this is now a
  sequenced gap rather than an open one.
- **Cycles × instance set** (a cyclic region spanning replicated/partitioned
  instances) — nested/cross-host cycles are E5/R16 research; no ticket.
- **Per-key attention economics** (spawn-vs-subscribe, resharding triggers,
  attention budgets) — G-62/M16, deliberately out; PN-19 covers only the
  non-economic scattering/parking core.
- **Mobility × durability WAL handoff** (migrating a journaled instance:
  checkpoint-then-move, journal ownership transfer) — detection is covered by
  PN-12's spawn check; the handoff protocol is a recorded residual.
- **Adapter synthesis (CP-G6)** — still zero observed hand-stacks; the policy
  stack likely satisfies the need cheaper.
- **Fence transfer on interest widening** (dead-source fences don't follow
  interest; entangled with G-42/R14) — recorded as a research residual; blocks
  nothing in this plan because no phase widens an interest across a fence
  boundary without a full re-baseline.
- **Operational note**: `replicasOf`/`instancesOf` is a linear registry scan
  called per settlement check; index by `logicalId` before PN-7 ships.
