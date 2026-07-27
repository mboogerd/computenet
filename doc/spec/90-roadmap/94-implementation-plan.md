# 94 — Implementation Plan (post-M11)

> **Status**: Historical — all waves merged; not the work list (see
> [96-incremental-engines-plan.md](96-incremental-engines-plan.md) for the forward queue)
> **Sources**: [91-gap-analysis.md](91-gap-analysis.md) (G-34..G-62, C-9..C-12, open residuals),
> [92-way-forward.md](92-way-forward.md) §Milestone 12+, [93-feature-interactions.md](93-feature-interactions.md)
> **Implementation**: n/a — historical wave decomposition, not a live work list

## How to read this plan

- Every work item references the spec file(s) whose recently-integrated text defines
  it. The item's scope is: *make the cited spec text true of the code*. The cited
  sections carry the full design; this plan does not restate it.
- **Waves** group items whose code paths do not overlap — items within a wave can be
  implemented in parallel by independent sessions. Waves are ordered: an item may
  depend on earlier waves (noted per item), never on its own wave.
- **Priority** P1 (unblocks the most / fixes a live divergence) → P3 (valuable,
  deferrable). **Certainty** High (the spec text is a complete design) / Medium
  (design decided, some realization details left to the implementer).
- Items whose *solution* is genuinely open live in
  [95-research-plan.md](95-research-plan.md), not here. Where a work item has a
  research-gated corner, the corner is named and excluded from the item's scope.
- Code-path labels (for parallelism): `gen` (KSP/codegen), `host` (ManagedHost
  queue/intake/journal), `dispatch` (proxy/invocation routing), `context`
  (MessageContext/outlet stamping), `glitchfree` (GlitchFreeCell/frontier), `data`
  (data cells/tags), `own` (ownership/fan/proxies), `repl` (Replication), `wire`
  (bridges/codec), `link` (handshake/registry), `membrane` (composition layer),
  `evo` (Promotion/GraphSpec), `sched` (attention).

---

## Wave 1 — divergence fixes + substrate (all paths disjoint)

### W1.1 — Origination-at-merge in gossip re-emission (C-10) — P1 · High · `data`
**Spec**: 20/22 §Source identity (Rule S4), 40/42 §"Gossip convergence is tag-carried".
**Spec change**: a `Replicable` cell re-emitting an effective post-merge delta is an
*origination point* — it mints a fresh wave from its own outlet; tags travel verbatim.
**Implement**: change `SetCell.applyRemote` (and `PnCounterCell`'s equivalent path) to
re-originate instead of re-emitting under the incoming wave; keep tag pass-through
byte-identical. Remove the C-10 rows/markers on completion. Test: gossip mesh
convergence suite still green; add a wave-id assertion (replica emissions carry the
replica's own sourceId).

### W1.2 — Discharging shadow sinks (C-11) — P1 · High · `own`
**Spec**: 20/23 §Taps (discharging-sink rule), 50/52 §"Exclusive payloads in shadow mode".
**Spec change**: suppression MUST discharge exclusives — `Owned` → `take()`-and-drop,
`Leased` → `release()` — never a plain drop.
**Implement**: replace `Shadow.spawn`'s plain `Proxy.noop` on contracts whose
descriptor carries the exclusive bit with a generated discharging proxy. Test:
shadow-mode run over an Owned/Leased pipeline; assert lease pool balance and
consume-once accounting.

### W1.3 — Descriptor bits & lint suite (G-60) — P1 · High · `gen`
**Spec**: 10/12 §Cardinality (descriptor-bit paragraph), 10/15 §Port discovery, 20/23
§Implementation (family extending the M5.6 exclusive-bit scan).
**Spec change**: the KSP scan emits, beside the exclusive bit: `magnitude` /
`idempotentMerge` (cycle tiers), `keyIndex` via `@Key` (partitioning), `effect`
(`@Contract(effect = true)`), and `CellDescriptor.color`; lints fail compilation on
broadcast-of-exclusive and non-Unit data-contract returns (G-11 remainder).
**Implement**: extend the `gen` module descriptor emission + lints; thread new bits
through `ContractRegistry`. No runtime behavior change. **Unblocks** W2.4, W3.1, W4.2.

### W1.4 — Bounded intakes & saturation (G-34) — P1 · High · `host`
**Spec**: 30/32 §Bridges (the full three-state design), 30/31 (G-34 marker at
`enqueueHostedInvocation`), 10/13 §Link lifecycle summary (no-loss invariant naming
SATURATED).
**Spec change**: intake state `OPEN | SATURATED | CLOSED` in the existing closure
volatile; opt-in `IntakeBound`; saturated-send dispatch by payload class (mergeable
coalesce / exclusive fail-fast + park-at-sender / management exempt); retractable
`SaturationSignal`; ADR-2 bridges reinterpreted as color-correct saturation handlers.
**Implement**: `ManagedHost` intake + `enqueueHostedInvocation` seam + sender-side
park integration with `LocationRegistry`. The upstream `SaturationSignal` notice can
stub to a no-op until W2.3 (transitive notices). Test: generative saturation run with
a mergeable-coalesce and an exclusive-park scenario; no silent loss.

### W1.5 — PORT_PROTOCOL dispatch class, in-process (G-35 phase A; realizes G-13's decided plane) — P1 · High · `dispatch`
**Spec**: 10/12 §Multiplexed ports (the recorded decision), 10/14 §Dispatch tiers
(three-way branch), 20/22 §MessageContext rule 5 (null context).
**Spec change**: third dispatch class `PORT_PROTOCOL` → `ProtocolSupport.deliver`;
per-protocol `ProtocolDescriptor` (direction, band, lane, protocol-intrinsic
cardinality) in a `ProtocolRegistry`; protocols are push-only, null-context, never
`Owned`/`Leased`, bypass data parking; data sub-ports rejected.
**Implement**: extend `HostedPortInvocation.Type`, generate descriptors for the
existing protocols (attention, suspension notices), route through the M6.1
`ProtocolSupport` map. Wire crossing is **W3.2**, not here. **Unblocks** W2.2, W2.3.

### W1.6 — Reverse-topology index (G-48) — P2 · High · `link`
**Spec**: 10/11 §Identity (G-48 marker), 40/41 point 3 (addressing), 50/53 §The
promotion swap (swap-set enumeration).
**Spec change**: an index enumerating all links pointing at a full ref, so promotion
swaps can find their swap set and rebinds don't scan.
**Implement**: registry-side link index maintained from handshake/unlink events
(in-process first; announcement-fed for remote links). Test: promotion swap-set
enumeration equals actual links under generative churn.

### W1.7 — Topology edge events, in-process (G-39 phase A) — P1 · High · `glitchfree`
**Spec**: 20/22 §Topology versioning (full mechanism), 10/13 §Link lifecycle summary
(edge events paragraph).
**Spec change**: successful `onLink`/`onUnlink` on a topology-interested edge emits
`EdgeOpen(fromWave)`/`EdgeClose` in-band on the per-link FIFO; consumer-computed
per-source floors; pre-join arrivals discarded; `EdgeClose` unblocks waiting waves;
emission gated on downstream topology-order interest.
**Implement**: link hooks + `GlitchFreeCell` floor bookkeeping replacing the
live-link frontier recompute. Wire form is **W3.2**. Test: the seed-12 cross-source
join scenario; unlink-during-wave no longer stalls a join.

---

## Wave 2 — recovery, completeness, exclusive hardening (after W1)

### W2.1 — Source epochs, generations & ReBaseline (G-42 + G-43, fixes C-12) — P1 · High · `context`+`host`
**Spec**: 20/22 §Source identity: emission epochs (Rules S1/S2/S5), 30/31 rule 5 (the
RESTART redefinition + recovery precedence), 20/24 §Tag continuity, 20/21 §Pull
(closing paragraph), 00/03 (Generation).
**Spec change**: fresh `sourceId` per emission epoch with the preserved-epoch guard
(drain-captured continuation adopts; RESTART/replica/T2 mint fresh); host-held
per-instance `generation` outside the checkpoint; `ReBaseline(source, supersedes,
state, supersede)` over the ordinary catch-up path; downstream dead-lane filter;
restore-freshest precedence (durable → journal tail → peer/upstream catch-up → local
checkpoint). Epoch-hygiene compaction corners are research-gated (95 §R2 note) —
implement unbounded-but-correct first.
**Implement**: outlet epoch minting + `OutletWaveState` transfer in drain/migrate/
promotion; host generation bookkeeping; `ReBaseline` emission on RESTART; convergent-
consumer supersede filter in the data-cell fold. Removes C-12. Test: RESTART of a
producer mid-stream — no tag/wave aliasing, downstream converges to the re-baselined
state; promotion adoption keeps the glitch-free frontier invariant.

### W2.2 — StateRequest pull + catch-up baseline (G-37 + G-38, closes G-18) — P1 · Medium · `data`+`dispatch`
**Spec**: 20/21 §Pull (StateRequest design + "Catch-up is a baseline, not a wave
input"), 20/22 §Interaction (rewritten pull bullet + `baseline: TagFrontier?` field),
40/41 (G-37 marker).
**Spec change**: management-class `StateRequest(replyTo, since: TagFrontier?)` on the
metadata plane; single-wave state-as-delta reply ahead of live waves via per-link
FIFO; trigger rule gated by the per-link liveness epoch; baselines excluded from wave
completeness, anchored at the stamped link-install event; glitch-freedom resumes at
the first post-install complete wave.
**Depends**: W1.5 (metadata plane), coordinate with W2.1 (both touch `MessageContext`
— the `baseline` field should land with W2.1's epoch fields to avoid churn).
**Implement**: protocol descriptor + data-cell handler + `GlitchFreeCell` baseline
handling. Certainty Medium: reply routing details (`outlet.at(replyTo)` vs dedicated
reply lane) are named but not pinned — pick the simplest that honors per-link FIFO.

### W2.3 — Transitive metadata notices (G-36) — P2 · Medium · `sched`
**Spec**: 30/34 (G-36 marker, decision 3 extension), 10/12 §Directionality (shared
termination discipline: terminal predicate + epoch + visited set), 20/22 rule 5.
**Spec change**: attention retraction, upstream disinterest, Stall/Progress, and
saturation notices propagate multi-hop with one termination discipline.
**Depends**: W1.5. **Implement**: generic relay in `ProtocolSupport` honoring the
per-protocol descriptor; wire W1.4's `SaturationSignal` into it. Certainty Medium:
per-protocol aggregation-along-paths is decided only for attention (LWW/max).

### W2.4 — Taps: Observe-role links (G-47) — P2 · High · `own`
**Spec**: 20/23 §Taps (Consume/Observe roles, `borrow()`, taps-fire-first,
cross-boundary freeze), 10/12 §Cardinality rule 2 extension, 50/52 (invariant taps).
**Spec change**: SPSC counts Consume links only; an uncounted read-only tap fires a
`Borrowed` projection before the sole consumer.
**Depends**: W1.3 (Borrowed projection generation). **Implement**: `LinkRole` on the
handshake, tap emission order in `FanOutlet`, invariant-cell attachment path. Test:
invariant observes an Owned pipeline without perturbing consume-once.

### W2.5 — Exclusive payloads off the happy path (G-46) — P2 · High · `own`+`host`
**Spec**: 20/23 §Recovery and dead letters (R6–R8), 30/31 rule 5 (dead-letter
envelope).
**Spec change**: RESTART never re-consumes (state-restore, not input-replay);
dead-letter capture: `Owned` move-by-serialize, `Leased` released + redacted marker,
dead-letter outlet fans `Frozen` only.
**Implement**: dead-letter envelope conversion + park/crash accounting. Test:
supervision suite extended with exclusive payloads in flight at failure.

### W2.6 — Effectful processed-frontier (G-59, fixes C-9) — P1 · High · `host`
**Spec**: 20/24 §Durability spectrum ("Boundary of the landed mechanism"), 30/31
(recovery precedence + C-9 note), 50/52 ("Effectful recovery").
**Spec change**: `Effectful` sinks journal a processed-frontier deduping both journal
replay and post-recovery live re-delivery; replay of effect-boundary contracts is
suppressed-emission.
**Implement**: frontier record in the journal (per Effectful inlet: last applied
`(sourceId, counter)`), consulted by `recoverFrom` and live delivery. Removes C-9.
Test: crash-recovery run with an effectful sink — no double-fire (the double-fire
control from M9.2 inverted).

### W2.7 — Completeness watermark + typed Stall family (G-40) — P1 · High · `glitchfree`
**Spec**: 20/22 §"Completeness over silent or stuck edges", 30/34 decision 3
(Stall/Resume generalization), 30/31 rule 5 (dead-letter emits `Stall(DEAD_LETTERED)`).
**Spec change**: per-edge watermark advance (delta / `Progress` / later wave); the
`floor < t ⟹ watermark ≥ t` completeness predicate; `Stall(reason, recoverable)` with
WAIT/DEGRADE/RE-SCOPE keyed on recoverability; RESTART of a glitch-free cell drops
its transient buffer and re-enters by catch-up.
**Depends**: W1.7 (floors), W2.3 (notice transport — can stub single-hop first).
**Implement**: extend the M6.4 WAIT/DEGRADE machinery to the full typed family.

### W2.8 — Admission vs activation enforcement (G-55; advances G-12's remaining handshake corners) — P2 · High · `link`
**Spec**: 10/15 §Admission vs activation (C-7 resolved section incl. the port-layer
table), 10/13 §Explicit (deferred stateful `onLink`), 40/41 point 5 (remote rejection
surface).
**Spec change**: structural admission is phase-independent and binding from
construction; behavioral activation gates dispatch on handler establishment; stateful
`onLink` on a cold cell defers on the management inlet and replays at activation; the
parked-tail window is the third use of the Buffering primitive.
**Implement**: split the current spawn-time enforcement into the two layers; the
parked-tail window for pre-activation traffic. Test: cold-composed graph with
pre-activation sends replays in order at activation.

---

## Wave 3 — cycles, wire phase, replication & structure (after W2)

### W3.1 — CycleHead & two-tier quiescence (G-41, closes G-19's guard half) — P2 · Medium · `data`+`link`
**Spec**: 20/21 §Cycles (the full decided model), 20/23 (cycle-edge corollary), 10/13
(`CycleWithoutHead` rejection), 20/22 rule 2 exception (head re-origination) + `hop`
field.
**Spec change**: `CycleHead` + `feedbackInput`; feedback absorbed, not joined; fresh
wave per iteration; strong tier threshold-free for idempotent-merge deltas; weak tier
per-feedback-inlet `quiescence` damper + hop guard; `Leased` forbidden on cycle edges.
**Depends**: W1.3 (magnitude/idempotentMerge bits). Weak-tier *convergence guarantees*
are research (95 §R2) — implement the guard mechanics with the honest bounded-lap
contract. Test: M11-style cyclic graph, divergent-control comparison.

### W3.2 — Wire phase: protocols + edge events cross machines (G-35 phase B, G-39 phase B) — P1 · High · `wire`
**Spec**: 40/41 point 4 (both decided-design paragraphs: `PORT_PROTOCOL` frame type,
protocol-aware bridge pair, reverse path for upstream protocols; `EdgeOpen`/`EdgeClose`
as a frame type with park + in-order replay), 30/34 (G-35 marker).
**Depends**: W1.5, W1.7. **Implement**: `WireFrame` type additions (version byte only
if not additively compatible), bridge egress/ingress protocol handling, capability
negotiation at handshake (`Link.protocolCapabilities`). Test: two-process attention +
late-join with an unlink mid-stream.

### W3.3 — Gossip-mesh hardening (G-45) — P2 · Medium · `repl`
**Spec**: 40/42 §"Decided in 93, not yet built" (membership fold, eviction gate:
membership-gated + drain-gated despawn, suspend-when-partitioned), 50/52 (replica-
convergence invariants via `replicasOf`).
**Depends**: W2.1 (epochs). Churn/liveness *arguments* are research-flavored (95 §R1
note); implement the decided gates + the invariant harness. Test: 3-peer mesh with
partition/heal/evict under the new invariant cells.

### W3.4 — Membranes: Flatten/Mediate exposure (G-52, realizes G-9/G-10) — P1 · Medium · `membrane`
**Spec**: 10/11 §Membranes ("Decided model" block: exposure map, Flatten/Mediate,
hidden-by-default via the G-28 containment record, couplings, Preserve/Remint), 10/14
(mediate-is-serve), 50/51 (DSL lowering + G-52 marker), 40/41 (exposed-name
resolution note).
**Implement**: exposure map on composite cells; host resolution surface consults
containment; Mediate proxy (hand-written first; KSP generation is the G-52 marker's
residual); Flatten = existing delegation. Coupling *liveness* is research (95 §R3) —
ship couplings with the documented wait-forever caveat or gate them off. Certainty
Medium only for the proxy-generation half. **Unblocks** W4.1, W4.2.

### W3.5 — Promotion transaction hardening (G-49) — P2 · High · `evo`
**Spec**: 50/53 §The promotion swap (PRECHECK/PREPARE/COMMIT/RETIRE, retained
incumbent, non-vetoing commit), 10/13 (Phase-0 policy dry-run), 20/24 §Tag continuity
(typed handoff tiers).
**Depends**: W1.6 (swap-set enumeration), W2.1 (OutletWaveState adoption + ReBaseline
fallback). **Implement**: harden `Promotion.promote` to the four-phase protocol; the
T2 fallback emits `ReBaseline` and is typed-restricted (non-idempotent cells refuse).
Test: `ShadowPromotionTest` extended with a mid-commit failure → retained-incumbent
rollback.

### W3.6 — GraphSpec identity & remote application (G-51 core) — P2 · High · `evo`
**Spec**: 50/51 §Graph construction DSL (SpawnStep identity/parent/factory,
`IdentityBinding`s, `spawnBound` wire form, scoped loud-failure), 40/41 point 5, 10/15
(G-51 marker).
**Implement**: the spawn-step parameters + `Exact` idempotent re-apply + dead-letter
rejection reporting. Partial-apply *atomicity* is research (95 §R4) — ship
partial+report semantics, documented. Test: remote replay with one rejecting step;
loud asynchronous failure observed.

---

## Wave 4 — authority, partitioning, polish (after W3)

### W4.1 — BoundaryPolicy: the three seams (G-54, realizes G-14's flow-time half) — P2 · Medium · `membrane`
**Spec**: 40/43 §BoundaryPolicy (five predicates, three seams, attention clamping,
disclosure-as-one-filter, `RequireSigned` verify-at-ingress), 10/11 (Exposure carries
policy; flow-time forces Mediate), 20/21 (catch-up passes the disclosure filter),
30/34 decision 6 (attenuation).
**Depends**: W3.4. Identity *strength* (keys/DIDs) is research (95 §R7); implement
with `TransportVouched` principals. Test: disclosure filter covers catch-up + live
uniformly; attention ceiling clamps.

### W4.2 — PartitionedCell (G-56, realizes G-24; trigger armed) — P2 · High · `data`+`membrane`
**Spec**: 20/24 §Partitioned state (composite design + placement composition list),
30/31 §Hierarchy (organelle containment cascade), 30/34 decision 5 (lattice slot).
**Depends**: W1.3 (`keyIndex`), W3.4 (membrane composite). The trigger (GroupByCell
placement pressure) is armed per M11.8. Test: sharded GroupByCell equals unsharded on
100 seeds, mid-run repartition included.

### W4.3 — Single-writer replication core (G-44 core) — P2 · Medium · `repl`
**Spec**: 40/42 §Single-writer replication (leader as single applying instance,
follower redirect-vs-reject by `WritePosture`, one-direction shipping, `LeaderMark`
epoch fold + fencing, RESTART-by-peer-catch-up), 30/33 (leader-targeted park/replay),
20/23 (SW-exclusive), 20/24 (G-44 marker).
**Depends**: W2.1, W3.3. Automatic *election/failover* is research (95 §R1) — ship
with explicit (manual/orchestrated) failover, which the spec declares the default.

### W4.4 — Promotion policy as data (G-50) — P3 · Medium · `evo`
**Spec**: 50/53 ("Judgment is declarative policy": PromotionPolicy shape, differential
shadow, cycle-promotion-gates-on-quiescence), 40/43 (G-50 marker: promotion authority).
**Depends**: W3.5. Serializable policy artifact + judge wiring.

### W4.5 — Attention realization details (G-58 core) — P3 · Medium · `sched`
**Spec**: 30/34 decision 1 continuation (per-link LWW slot algebra, idempotency law,
retraction on `onUnlink`) + the G-58 marker's listed details.
**Implement**: version minting/wraparound, slot GC on unlink, decay cadence knobs.
Economic coupling is research (95 §R6).

### W4.6 — Reflection-free KMP proxies (C-5 completion) — P3 · High · `gen`
**Spec**: 10/14 §Reflection budget, 50/51 §Code generation.
**Spec change** (pre-existing): KSP-generated proxies replace JDK dynamic proxies
in-process; ids already on the wire.
**Depends**: W1.3 (shares the generator). Mechanical; large test surface already
exists.

### Deferred (recorded, not scheduled)
- **G-21 phase 3** — lease pooling; trigger: profiling shows allocation pressure
  (20/23 §Implementation).
- **G-23 keyed-structure deferrals** — OR-map/bag convergence; trigger recorded in 91
  (M11.8 note). See also 95 §R8.
- **Windowing residuals** — watermark eviction + session windows; triggers recorded in
  20/24 (M11.6).

---

## Dependency summary

```
W1.3 gen ──► W2.4 taps, W3.1 cycles, W4.2 partitioned, W4.6 proxies
W1.5 protocol plane ──► W2.2 pull, W2.3 notices ──► W2.7 stalls (transport)
W1.7 edge events ──► W2.7 stalls, W3.2 wire phase
W2.1 epochs/ReBaseline ──► W2.2 (MessageContext fields), W3.3 mesh, W3.5 promotion, W4.3 single-writer
W1.6 reverse index ──► W3.5 promotion
W3.4 membranes ──► W4.1 boundary policy, W4.2 partitioned
W3.5 promotion ──► W4.4 policy-as-data
```

Priorities at a glance: **P1** = W1.1, W1.2, W1.3, W1.4, W1.5, W1.7, W2.1, W2.2,
W2.6, W2.7, W3.2, W3.4 · **P2** = W1.6, W2.3, W2.4, W2.5, W2.8, W3.1, W3.3, W3.5,
W3.6, W4.1, W4.2, W4.3 · **P3** = W4.4, W4.5, W4.6.
