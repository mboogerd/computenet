# Composition plan — ticket decomposition with agent orchestration

> **Source of truth for scope**: [COMPOSITION-PLAN.md](COMPOSITION-PLAN.md) (the braid)
> and [COMPOSITION-STATUS.md](COMPOSITION-STATUS.md) (problem + empty-pair matrix).
> House style follows `94-implementation-plan.md`: each ticket = *make the cited
> spec/plan text true of the code*, test named up front, 100-seed generative where
> applicable, controls that must diverge.
>
> **Orchestration metadata per ticket** (the optimization contract):
> - **Context**: `FRESH` (new agent, cold start) or `CONTINUE <ticket>` (same agent,
>   same functional context — the next ticket is a natural progression and the
>   accrued file/domain knowledge is the asset).
> - **Parallel**: tickets/tracks safe to run concurrently — independent in
>   functionality *and* in the files they touch. Anything not listed as parallel is
>   assumed conflicting or dependent.
> - **Files**: primary files, for collision reasoning. Concurrent agents follow the
>   existing worktree/merge discipline in `doc/ORCHESTRATION.md`.

## Orchestration at a glance

```
PHASE 1  ──  three agents in parallel (α ∥ β ∥ γ), tracks internally sequenced
  Agent α (glitchfree×wire):   A1 → A2 → A3 → A4
  Agent β (replica frontier):  B1 → B2 ──────────┐
  Agent γ (journal tee):       C1                │
                                        B3 (join: needs A4 + B2; CONTINUE β on rebased main)

PHASE 2  ──  after Phase 1 gate; two agents (δ ∥ ε)
  Agent δ (instance-sets):     D1 → D2 → D3 → D4
  Agent ε (probe demo):        E1 (scaffold, parallel with δ) …… E2 (after D4; CONTINUE ε)

PHASE 3  ──  after probe evidence; one agent (ζ)
  Agent ζ (nature typing):     F1 → F2 → F3      (auto-insertion: explicitly NOT ticketed)
```

Rationale for the shape: tracks are split by **code path** exactly like the
94-plan's wave labels — α owns `wire`+`consistency`, β owns `repl`+new watermark
files, γ owns the `host` journal seam, so their diffs are disjoint. Within a
track, tickets are deliberately small-but-sequenced: each is a shippable,
testable increment, and the same agent carries forward the accumulated context
(the frontier fold in α, the watermark lattice in β) instead of re-reading it.

---

## PHASE 1 — Location-transparent frontiers

### Track α — glitch-freedom crosses the bridge (closes A–F, A–O)

#### CP-A1 — Spec: the bridged frontier + the handshake leak — P1 · High · `spec`
- **Context**: FRESH (Agent α start) · **Parallel**: with B1, C1 · **Files**:
  `doc/spec/20-dataflow-semantics/22-consistency.md`, `doc/spec/40-distribution/41-location-transparency.md`
- **Spec change**: 20/22 gains §"Bridged frontier": `EdgeOpen`/`EdgeClose`/`Progress`
  cross bridges in frame order; the observation frontier is location-transparent.
  40/41 names the current `WireEdgeLink.bridgeTo/bridgeFrom` bypass of
  `handshake()` a **location-transparency bug** (policies, EdgeEvents, allowlist
  skipped on bridged edges), to be closed by CP-A2.
- **Implement**: spec text only; add/adjust ⚠ markers. No code.
- **Test**: n/a (doc ticket). Exit: the two sections read as one decided design.

#### CP-A2 — Bridged links run the handshake; EdgeEvents + Progress cross the wire — P1 · High · `wire`+`glitchfree`
- **Context**: CONTINUE CP-A1 (same agent — implements the text it just wrote)
- **Parallel**: with B1→B2, C1 · **Files**: `kernel/.../cell/wire/WireEdgeLink.kt`,
  `kernel/.../cell/wire/BridgeCells.kt`, `kernel/.../cell/wire/WireCodec.kt` (frame
  types, additive), `kernel/.../cell/port/Link.kt` (handshake overload taking a
  remote-resolved counterpart), touches `kernel/.../cell/consistency/GlitchFree.kt`
  edge bookkeeping.
- **Implement**: route `bridgeTo`/`bridgeFrom` through `handshake()` (policies +
  `EdgeOpen`/`EdgeClose` emission) instead of raw `linking.register`; forward
  `Progress` absorb-acks across the frame path so a remote frontier settles.
  Additive `WireFrame` fields only.
- **Test**: `GlitchFreeBridgedDiamondTest` — the M2 diamond with one arm crossing
  a bridge into a glitch-free consumer on the far host; 100 seeds of cross-host
  scheduling + frame reorder/duplication; invariant: no composite from mixed
  waves. **Control**: EdgeEvents/Progress not forwarded → WAIT stalls forever /
  DEGRADE emits a glitch. Existing `TrustBoundaryTest` must stay green (the
  allowlist now also fires on bridged edges — assert that explicitly).

#### CP-A3 — Absorb-acks through the operator suite — P1 · High · `data`+`glitchfree`
- **Context**: CONTINUE CP-A2 (same agent — the settlement predicate it just
  touched is the seam this extends)
- **Parallel**: with B-track, C1 (α-internal sequence; do NOT run beside A2 —
  both edit `GlitchFree.kt` settlement) · **Files**: `kernel/.../cell/data/`
  (absorbing operators: filter/semijoin/join swallow paths), `GlitchFree.kt`.
- **Implement**: absorbing operator cells emit `Progress` for waves they consume
  without emitting (the E2.2 item); frontier-gated antijoin emission.
- **Test**: `GlitchFreeOperatorSuiteTest` — the M11 pipeline (union → mapSet →
  join → antijoin → groupBy) observed glitch-free; equals batch recompute AND no
  mid-wave flicker; 100 seeds. **Control**: absorb without `Progress` → final
  wave stalls.

#### CP-A4 — `WaveFrontier` extraction: frontier as per-inlet policy — P1 · Medium · `glitchfree`+`host`
- **Context**: CONTINUE CP-A3 (same agent — the refactor of the machinery it now
  knows end-to-end; this is the payoff ticket of the track)
- **Parallel**: NONE within α; safe beside B1/B2 but **not** beside C1 if C1 has
  not merged (both edit `ManagedHost.kt` — different regions, but sequence the
  merges: land C1 first, it is smaller) · **Files**: `GlitchFree.kt` (split into
  `WaveFrontier` + sugar cell), `kernel/.../cell/port/FanInlet.kt` (policy slot),
  `kernel/.../cell/host/ManagedHost.kt` (deliver path consult;
  remove the `suspensionRegionOf` `GlitchFreeCell` special-case).
- **Implement**: extract the frontier fold into `WaveFrontier`; opt-in
  `frontierPolicy` on `FanInlet` consulted at delivery; `GlitchFreeCell` becomes
  sugar over it; `suspensionRegionOf` keys on the policy, not the class.
- **Test**: `InletFrontierPolicyTest` — a plain cell with `frontierPolicy = WAIT`
  passes the `GlitchFreeDiamondTest` invariant (200 seeds); the sugar path keeps
  `GlitchFreeDiamondTest` + `GlitchFreeSuspensionTest` + `GlitchFreeStallTest`
  green unchanged; the bridged variant of CP-A2 re-run through the policy form.

### Track β — cross-replica frontier (closes A–B)

#### CP-B1 — `WatermarkDelta` + `WatermarkCell` (the delivered-watermark lattice) — P1 · High · `data`
- **Context**: FRESH (Agent β start) · **Parallel**: with all of α, C1 · **Files**:
  NEW `kernel/.../cell/data/Watermark.kt` (+ serializer registration in
  `kernel/.../cell/wire/Serializers.kt` — additive), spec touch:
  `doc/spec/90-roadmap/96-incremental-engines-plan.md` E3 status markers.
- **Implement**: per-source delivered-watermark map, merged by pointwise max
  (idempotent → gossip-safe by construction); `WatermarkCell` as an ordinary
  `Replicable` cell. (= E3.2.)
- **Test**: `WatermarkCellTest` — pointwise-max merge laws (commutative,
  idempotent, monotone) under generative merge orders; gossip echo produces no
  regression.

#### CP-B2 — Delivered-tracking seams — P1 · High · `repl`
- **Context**: CONTINUE CP-B1 (same agent — feeds the lattice it just built)
- **Parallel**: with α (disjoint files), C1 · **Files**:
  `kernel/.../cell/replication/Replication.kt` (delivery hook),
  `kernel/.../cell/data/Watermark.kt`.
- **Implement**: replica delivery paths advance the local watermark; watermark
  cells gossip over the existing mesh links (no new protocol). (= E3.3.)
- **Test**: `DeliveredWatermarkTest` — three-replica mesh; each peer's watermark
  converges to the true delivered frontier under 100 seeds incl. partition/heal;
  control: a non-advancing peer's absence is visible in the merged lattice.

#### CP-B3 — `ReplicaFrontier` read in wave settlement — P1 · High · `glitchfree`+`repl` **(JOIN POINT)**
- **Context**: CONTINUE CP-B2 — but **only after CP-A4 is merged**; rebase onto
  main so the agent extends `WaveFrontier`, not the pre-split `GlitchFreeCell`.
- **Parallel**: NONE (this is the cross-track join; it touches both tracks' files)
- **Files**: `WaveFrontier` (from A4), `Watermark.kt`, `Replication.kt`.
- **Implement**: settlement predicate gains a replica-frontier read: "my replica
  delivered it ≠ the wave is complete" — a wave settles only at the merged
  watermark. (= E3.4.)
- **Test**: `GlitchFreeReplicaFrontierTest` — two peers feed one glitch-free join
  from different replicas of one logical `SetCell`; 100 seeds with
  partition/heal; invariant: no join output at a wave a replica-set member has
  not delivered. **Control**: replica-frontier read off → mixed-frontier output
  on some seed.

### Track γ — per-cell durability

#### CP-C1 — Per-cell journal tee — P2 · Medium · `host`
- **Context**: FRESH (Agent γ; small, self-contained) · **Parallel**: with A1–A3,
  B1–B2. **Merge before CP-A4** (both edit `ManagedHost.kt`; C1's diff is
  localized to the tee/recovery regions — land it first, α rebases).
- **Files**: `kernel/.../cell/host/ManagedHost.kt` (tee at
  `enqueueHostedInvocation`, `recoverFrom`, `checkpoint`),
  `kernel/.../cell/durability/Journal.kt`, spec:
  `doc/spec/20-dataflow-semantics/24-data-cells.md` §Durability (per-cell, not
  per-host).
- **Implement**: `journalFor(cellRef)` predicate replacing the single host
  `journal` param (host-wide journal = the predicate returning one journal —
  today's behavior byte-identical as default); per-cell recovery/checkpoint
  keying (frontier records are already per-cell).
- **Test**: `MixedDurabilityTest` — one host, one journaled + one volatile cell;
  crash/replay restores exactly the journaled cell's state and re-delivers
  nothing to the volatile one twice; mirror of `CrashRecoveryTest`'s shape with
  the journal scoped per-cell. **Control**: whole-host journal restores both
  (proving the scoping is real). `CrashRecoveryTest`/`EffectfulRecoveryTest`
  stay green via the default predicate.

**Phase-1 gate**: full `./gradlew test` green; `GlitchFreeBridgedDiamondTest`,
`GlitchFreeOperatorSuiteTest`, `InletFrontierPolicyTest`,
`GlitchFreeReplicaFrontierTest`, `MixedDurabilityTest` all landed with controls.

---

## PHASE 2 — Partition ⊂ replication + the probe

### Track δ — instance-sets-with-interest

#### CP-D1 — Spec: interest-scoped instance sets — P1 · High · `spec`
- **Context**: FRESH (Agent δ; new conceptual frame, deliberately not carrying
  Phase-1 assumptions) · **Parallel**: with E1 · **Files**:
  `doc/spec/40-distribution/42-replication.md`,
  `doc/spec/20-dataflow-semantics/24-data-cells.md` (§Partitioned state).
- **Spec change**: new §Interest-scoped instance sets unifying replication and
  partitioning: per-instance `Interest` predicate; total interest +
  idempotent-union = replication; disjoint key-interest + disjoint-union =
  partitioning (the conflict-free degenerate); overlapping partial interest =
  sharded replication. Retires the G-56 "distribution edges remain open" text in
  favor of this design.
- **Test**: n/a (doc). Exit: 42 and 24 tell one story; 91 gap rows updated.

#### CP-D2 — `Interest` filter on the gossip linker — P1 · High · `repl`
- **Context**: CONTINUE CP-D1 · **Parallel**: with E1 · **Files**:
  `kernel/.../cell/replication/Replication.kt`,
  `kernel/.../cell/host/LocationRegistry.kt` (per-instance interest),
  NEW `kernel/.../cell/replication/Interest.kt`.
- **Implement**: `Interest` (hash-slot set / key range / predicate) per instance;
  `Replication.maybeLink` filters deltas by target interest; default = total
  interest (today's behavior byte-identical).
- **Test**: `InterestScopedGossipTest` — 3-instance mesh with disjoint
  slot-interest converges to the union; an out-of-interest delta never rides the
  link; **control**: total interest reproduces `ReplicatedSessionTest` behavior
  unchanged.

#### CP-D3 — `PartitionedCell` on the instance-set substrate; shards across hosts — P1 · High · `data`+`repl` **(the big one)**
- **Context**: CONTINUE CP-D2 · **Parallel**: with E1 · **Files**:
  `kernel/.../cell/data/PartitionedCell.kt` (rebuilt on instances),
  `Interest.kt`, `Replication.kt`, routing-epoch serialization (additive frame
  field in `kernel/.../cell/wire/`).
- **Implement**: shards become interest-scoped hosted instances of one logical
  id (independently spawnable onto real `ManagedHost`s); the router is the
  disjoint-interest linker; routing table + `routingEpoch` cross the wire;
  scatter-gather catch-up unions shards; `repartition` = interest reassignment +
  `StateRequest`-driven replay (retiring the bespoke `routed` ledger replay).
- **Test**: `PartitionedShardsAcrossHostsTest` — shards on different hosts fed
  over bridges; 100 seeds of writes; board equals batch groupBy over final
  input. Existing `PartitionedCellTest` invariants stay green (single-host is
  the degenerate placement). **Control**: routing-epoch-blind routing after a
  flip loses/double-counts.

#### CP-D4 — Repartition under concurrent placement — P1 · Medium · `data`
- **Context**: CONTINUE CP-D3 · **Parallel**: with E1 · **Files**:
  `PartitionedCell.kt`, `kernel/.../cell/host/LocationRegistry.kt` (park on the
  flip window).
- **Implement**: real `Buffering` on the routing-table flip racing a shard
  migration; zero loss; per-ref park surfacing per the funnel rule (93 I-19).
- **Test**: extend `PartitionedShardsAcrossHostsTest` with a mid-run repartition
  racing a shard migration; zero loss on every seed; **control**: unbuffered
  flip drops or double-routes on some seed.

### Track ε — the composition probe

#### CP-E1 — `:demo:exchange` scaffold — P2 · Medium · `demo`
- **Context**: FRESH (Agent ε; a demo-builder context, deliberately consumer-eyed
  rather than kernel-eyed) · **Parallel**: with ALL of δ (new module, disjoint
  files; builds against Phase-1 kernel) · **Files**: NEW `demo/exchange/**`,
  `settings.gradle.kts` (one include line — the only shared file; trivial merge).
- **Implement**: two symmetric JVM peers forked from the `shopping` scaffold:
  per-peer writer `SetCell`s (region-keyed orders) → union → per-region
  GroupBy(sum) (plain, un-partitioned for now) → `observeAligned` board → SSE;
  writer intake journaled per-cell (CP-C1); glitch-free board via the inlet
  policy (CP-A4); aggregates replicated to the peer (existing mesh).
- **Test**: `ExchangeScaffoldTest` — two-JVM convergence + kill -9/recover for
  the un-partitioned graph (mirrors `TwoJvmConvergenceTest` +
  `CrashRestartConvergenceTest` shapes). Fills A–O/A–F/A–B/D–F/D–B in one demo
  even before partitioning arrives.

#### CP-E2 — Partitioned exchange + `ExchangeCompositionExitTest` — P1 · High · `demo` **(PHASE EXIT)**
- **Context**: CONTINUE CP-E1 — after CP-D4 merges; rebase onto main.
- **Parallel**: NONE (the join of both Phase-2 tracks) · **Files**:
  `demo/exchange/**` only.
- **Implement**: swap the plain GroupBy for the instance-set `PartitionedCell`
  with shards on different hosts; region shard aggregates replicated to the
  peer.
- **Test**: `ExchangeCompositionExitTest` — 100 seeds, two JVMs: the
  partitioned-replicated-durable-glitch-free board equals batch recompute on
  every seed, through a repartition, a shard migration, a peer kill -9/recover,
  and a late joiner on the other peer. **Controls**: point-consistent (non-
  aligned) sink shows a torn board on some seed; routing-epoch-blind repartition
  diverges. This is the phase gate — the 7 empty pairs (A–B, A–C, A–F, A–O,
  C–B, C–F, C–D) demonstrably closed in one graph.

---

## PHASE 3 — The minimal type system (demand-scoped; start only after CP-E2 evidence)

#### CP-F1 — Nature vocabulary + typed `Rejected`, zero behavior change — P2 · High · `gen`+`link`
- **Context**: FRESH (Agent ζ; type-design context, informed by the probe's
  recorded mismatches) · **Parallel**: none needed (Phase 3 is one lane) ·
  **Files**: `gen/.../wire/ContractDescriptor.kt` (NEW `Nature*` types,
  `PortDescriptor.natures`), `kernel/.../cell/port/Link.kt` (`Rejected` gains
  nullable `mismatch` + preserved `reason` string).
- **Implement**: axes scoped to the irreducible set + probe evidence — ownership
  class, merge-idempotence, color, monotonicity (+ any axis CP-E2 actually
  tripped on). Sparse `NatureVector`, `DEFAULT` singleton, absent axis = today's
  behavior. No negotiation yet.
- **Test**: `NatureDefaultsPreserveBehaviorTest` — default vectors → today's
  results verbatim; full suite green (rejection strings intact).

#### CP-F2 — KSP emits natures — P2 · High · `gen`
- **Context**: CONTINUE CP-F1 · **Files**: `gen/.../wire/ContractProcessor.kt`
  (marker scan mirroring the existing `CellColor` scan), `gen-test` fixtures.
- **Implement**: nature marker interfaces scanned into `PortDescriptor.natures`;
  `registerPort` projects `CellDescriptor` onto `Port.natures`.
- **Test**: `NatureDescriptorSweepTest` (mirrors `ContractProcessorTest`).

#### CP-F3 — `reconcile()` in the handshake: typed refusals only — P2 · High · `link`
- **Context**: CONTINUE CP-F2 · **Files**: `Link.kt` (`handshake()` hook),
  NEW `kernel/.../cell/port/NatureNegotiation.kt`.
- **Implement**: pure `reconcile(offered, required)` → `Direct` (reference-check
  fast path) | `Refuse(NatureMismatch)`. **No `Adapt` arm, no planner, no
  auto-insertion** — that is gated on post-CP-E2 evidence of repeated manual
  stack insertion, and would be ticketed as CP-F4+ only then. Fold today's
  silent drops on the scoped axes into loud typed refusals (e.g. `StateRequest`
  to a pull-incapable producer, if the probe hit it).
- **Test**: `TypedRefusalTest` — each scoped axis mismatch yields
  `Rejected(mismatch)` at link time where today it silently drops or fails at
  first emission; `BridgedHandshakeTest` asserts `localVerdict == remoteVerdict`
  (location-transparent negotiation, riding CP-A2's bridged handshake).

---

## Explicitly NOT ticketed (triggers recorded in COMPOSITION-PLAN.md)

Auto-insertion planner + rank table (trigger: probe shows repeated manual
stacks) · membrane couplings (G-53, research) · leader election (R1) ·
placement engine (R5) · partition-structure taxonomy (trigger: second real
structure demand) · promotion × replication (trigger: `:demo:exchange`
iteration 2's effectful alert sink) · weighted family (E6).
