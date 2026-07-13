# 34 — Scheduling and Attention-Driven Execution

> **Status**: Implemented (M6): decisions 1–4 below are code; decision 7 is code
> (M17); decisions 5–6 and additions marked "(decided in 93 I-n)" are decided
> design, unimplemented
> **Sources**: ADR 0 (§5), ADR 1 (§6, §7, §8), ADR — Computelet Kernel (attention propagation as a generic protocol); 93 (I-4, I-6, I-9, I-16, I-18, I-28)
> **Implementation**: `cell.attention.AttentionSupport`/`AttentionBand` over generic-protocol
> sub-channels (`cell.port.Protocols`, G-13 minimal); host mapping in `ManagedHost` +
> `AttentionPolicy` (band dispatch, stride floor, NONE-window park/replay);
> magnitude-band dispatch in `ManagedHost.stage/dispatchOne` over `cell.data.Magnitude`
> (opt-in via `AttentionPolicy.magnitudeBands`, M17);
> `GlitchFreeCell.WaveMode` WAIT/DEGRADE. Verified: `AttentionGenerativeTest`
> (100 seeds + starvation control), `GlitchFreeSuspensionTest`, `MagnitudeSchedulingTest`.
> Remaining: notices are single-hop (a join sees only direct upstream
> parks, not transitive ones — G-36 below). Attention crossing the wire is
> resolved (G-35, W3.2, see below and 41 point 4).

## Principle

Computation follows **interest** (P6). Subscribing to a result implies partial
responsibility for its inputs; unattended subgraphs quiesce; attended ones get
resources proportional to attention. The suspension machinery (33) is the
enforcement arm: no interest → suspend; renewed interest → resume.

## What is fixed by the ADRs

1. **Attention is a generic protocol** (12-multiplex): it propagates
   **upstream** (consumer → producer) along data links without cell-specific
   logic. Precise directionality is why ports/links know their data direction.
2. **Interest is compositional**: a cell's attention level derives from its
   downstream subscribers' attention (fan-out: programmable, max default; see
   below).
3. **Suspendability is the resource lever**: scheduling decisions are
   expressed as suspend/resume/migrate operations (33), not as thread
   priorities inside cell logic.
4. Cycles need **magnitude-based throttling** to quiesce (21, G-19) — the
   scheduler must not let feedback loops claim attention forever.

## Design sketch (implemented, M6 — G-6 resolved)

Minimal attention model compatible with the above:

- `Attention(level: Float, deadlineHint?)` as a well-known upstream protocol
  message; emitted by sinks (UIs, subscriptions, invariant monitors).
- Each cell/port aggregates downstream attention (default: max; sum for
  load-signaling variants) and re-emits upstream on change — itself
  incremental dataflow (an attention update carries a *current-level
  snapshot*, not a delta — the band change is the increment; magnitude
  throttling applies to attention itself to prevent oscillation storms).
- Hosts map attention to concrete resources:
  - 0 for longer than a policy window → suspend (33);
  - >0 on a suspended subgraph → resume;
  - relative levels → queue priority bands (extending the existing
    management > router > data bands with per-attention data bands);
  - persistent high attention + remote hotspot → migration candidate (40/42).

*(G-35 resolved, W3.2)*: bridge egress/ingress gained a `PORT_PROTOCOL`
frame path (additive `WireFrame` fields, no new type variant needed) with a
reverse-channel realization for upstream protocols over the reverse bridge
path a cross-host link already maintains (`Link.protocolBridge`,
`cell.wire.WireEdgeLink`); the link's negotiated `protocolCapabilities`
carries the peer's protocol-id set, defaulting to every protocol this
process's `ProtocolRegistry` knows. Verified by `ProtocolBridgeTest`
(cross-host attention convergence, EdgeOpen/EdgeClose ordering, unlink
mid-stream); a generative frame-reorder/duplication harness and a fully
versioned ProtocolId↔contractId negotiation handshake remain open follow-up
(93 I-1/I-4/I-17/I-9).

## Decisions (1–4 from M6 planning, formerly "open questions"; 5–6 decided in 93; 7 implemented in M17)

1. **Aggregation is programmable per cell, damped by quantization.**
   Aggregation is a per-cell strategy (`AttentionAggregator` on
   `AttentionSupport`): a pure fold of the cell's own level and its downstream
   links' levels into one, declaratively defined, assembled (strategies
   compose — decay wraps a base), and configured at spawn or live. Shipped
   strategies: **max** (the default — attention is a priority signal, not a
   load meter; sum double-counts shared subgraphs on diamonds), **sum** (load
   semantics, for consumers that want the double-count), and **decay** (the
   base's result halves per configured half-life without a fresh signal).
   Time-aware strategies read **scheduling-step ticks bound by the host** —
   never wall time — so the deterministic simulation stays deterministic (P1);
   re-evaluation between signals is an explicit `refresh` owned by the
   host/harness cadence, not the dispatch hot path. Whatever the strategy, the
   *effective* value is quantized into discrete **bands** —
   `NONE < LOW < NORMAL < HIGH` — and a cell re-emits upstream **only when its
   aggregated band changes**. Quantization is the damping: intra-band jitter
   is structurally incapable of propagating, which is the magnitude-throttling
   rule (21, G-19) applied to attention itself — and it is what keeps
   non-monotone strategies (sum, decay) from re-exciting cycles. A sink that
   loses interest emits level 0 or unlinks (unlink already shrinks the link
   set).

   The algebra beneath the fold
   (decided in [93 I-4](../90-roadmap/93-feature-interactions.md),
   unimplemented): attention is **per-link last-writer-wins level state**,
   not a delta stream. An aggregating cell/port holds one level slot per
   direct downstream link plus a *self* slot (`setSelf` — sinks and monitors
   pin intrinsic interest there); an `AttentionUpdate(level, version,
   deadlineHint?)` carries the emitter's *current* aggregate level with a
   per-emitter monotonic `version`, and a receiver MUST apply it iff the
   version exceeds the slot's stored one. The idempotency law is structural:
   duplicate delivery on one link is absorbed by LWW, while genuine fan-out
   to two distinct consumers is two slots, counted by design (`sum` counts
   both — load semantics; `max` collapses them — priority semantics); the
   fold is commutative and associative, so arrival order never matters.
   Retraction is slot removal: `onUnlink` drops that link's slot and
   re-folds the remainder, and an in-flight update for a removed link is
   dropped (no slot to key it) — the attention frontier is the current
   downstream link set, topology part of the aggregate exactly as in the
   glitch-free frontier (22).

   ⚠ GAP (G-58): the per-link LWW attention algebra leaves realization
   details open — version minting and wraparound, frontier state across
   migrate/relink, deadlineHint folding, retraction racing suspension-region
   atomicity, policy-window calibration, and band pinning for hard monitors.
   Proposal: pin the per-emitter monotonic version's minting scope,
   wraparound, and migration collision-freedom; decide
   frontier-as-migratable-snapshot-state vs rebuild-by-re-announce and its
   trigger boundary; a min/earliest fold for deadlineHint distinct from the
   level fold; settle the retraction × atomic-park race via the
   veto/NonSuspendable contagion; calibrate policyWindowSteps against graph
   depth; and decide whether hard real-time monitors may pin above LOW
   composing with the stride floor (93 I-4/I-9).
2. **Fairness floor = park-not-drop + a deterministic service stride.**
   Two floors, one per resource lever:
   - *Suspension floor*: attention-driven suspension uses the same
     park-and-replay buffering as 33 — a quiesced subgraph loses latency,
     never messages. Suspension requires band NONE sustained for a policy
     window (measured in host scheduling steps, not wall time, so the
     simulated host stays deterministic). Recovery on resume
     (decided in 93 I-16, unimplemented): a resumed subgraph MUST recover by
     park-replay when its buffer is intact, and by a state pull only when
     the buffer was dropped — never both; buffer survival (the registry's
     park-vs-drop distinction, a per-link liveness epoch) selects the arm.
   - *Queue floor*: within the data region of the host queue, bands map to
     priorities, but after `stride` consecutive dequeues of
     higher-band tasks the host MUST service the oldest queued lower-band
     task (by sequence number). `stride` is host policy (default 16;
     `∞` disables). This bounds starvation at `stride × task-time` without
     timers or randomness.
3. **Glitch-freedom × suspension: the region is the unit; WAIT/DEGRADE is
   the cross-host fallback.** The **suspension region** — a glitch-free join
   plus its transitive upstream contributors, bounded by further glitch-free
   cells (the frontier, 22) — suspends **atomically**: a host parking a
   starved cell first resolves the cell's local region, and either the whole
   region parks together (a partial-diamond stall cannot exist by
   construction) or nothing parks. Any member that is still attended, or is
   marked **non-suspendable** (`NonSuspendable` — the veto is contagious to
   the whole region), vetoes suspension and the region keeps running. Region
   *resume* is emergent, not orchestrated: renewed interest at any member
   propagates upstream over the attention protocol and every member's own
   band listener unparks it. Region resolution is local to the host —
   **cross-host branches fall back to WAIT/DEGRADE**: because suspension
   parks rather than drops, a glitch-free join above a remotely suspended
   branch sees an incomplete wave and holds the group — correct,
   latency-unbounded (**WAIT**, the default). A glitch-free cell MAY opt
   into **DEGRADE**: hosts publish suspended/resumed notices for cells they
   park, the notice travels *downstream* (with data, against attention), and
   a degrading join removes the suspended link from its wave frontier —
   reusing the frontier-shrink semantics unlink already has (22) — and
   restores it on resume, treating post-resume replayed waves as late-join
   catch-up (21). No new kernel mechanism: regions are a link-graph walk,
   the veto is a marker interface, and a notice is an ordinary
   generic-protocol message.

   The notice generalizes (decided in 93 I-18, unimplemented):
   the shipped suspended/resumed pair becomes a typed `Stall(reason,
   recoverable)` / `Resume` frontier-event family covering suspension,
   supervision RESTART, dead-letter, and exactly-once loss; a join disposes
   per edge keyed on recoverability — **WAIT** (the default, park-not-drop)
   or **DEGRADE** for recoverable stalls (suspended, restarting), and
   **RE-SCOPE** (advance past the lost wave, evaluate over the reduced set,
   and surface a `GlitchViolation`) as the only admissible disposition for
   terminal ones (dead-lettered, lost).

   ⚠ GAP (G-40): a glitch-free join cannot distinguish an
   effective-only-silent arm from a dead one — wave completeness blocks
   forever on absorbing, suspended, restarting, or dead-lettered frontier
   edges. Proposal: per-source per-edge watermarks advanced by real deltas,
   by metadata-plane Progress absorb-acks emitted at a precisely defined
   quiescence boundary, or by later waves (monotone close); typed
   Stall(reason, recoverable) markers with per-edge WAIT | DEGRADE |
   RE-SCOPE policies keyed on recoverability; pin the DEGRADE correctness
   contract (how downstream distinguishes a degraded emission), calibrate
   the backstop deadline against frontier depth, and build the generative
   completeness harness (93 I-18).

   ⚠ GAP (G-36): all metadata-plane notices are single-hop (M6) — attention
   retraction, upstream disinterest, Stall/Progress, and state-request pulls
   do not propagate through absorbing or stateless intermediaries to distant
   joins or remote producers. Proposal: one hop-by-hop re-emission rule for
   metadata-plane notices with loop prevention and the band-quantization
   interaction pinned per protocol — attention levels and transitive
   retraction across damped hops, disinterest quiescing remote producer
   cones, stall/progress watermarks reaching deep joins, and requestState
   forwarding through stateless cells (93 I-1/I-4/I-9/I-16/I-18).
4. **Economic layer** (peers advertising willingness to compute for others'
   interest) — still deferred to 40/42; the band protocol above is
   forward-compatible (levels are floats, bands are a local mapping).

   ⚠ GAP (G-62): every interest-driven policy defers to an economic layer
   that does not exist — replica spawn-vs-subscribe,
   migration-toward-attention, partition-host split/merge, resharding
   triggers, and per-Principal attention budgets (the Sybil economics).
   Proposal: an
   attention/quota-driven economic layer (the G-6 residual, on the G-28
   quota walk) deciding when to spawn a local replica vs subscribe remotely
   and where replicas live, migration candidacy under persistent high
   attention with remote hotspots, partition split/merge and bulk-rebalance
   triggering by load/size/attention, and per-Principal resource budgets
   bounding authenticated interest claims with a concrete cost to mint an
   identity (93 I-3/I-9/I-19/I-8/I-28).

5. **One authority lattice; attention advises, the host disposes**
   (decided in 93 I-9, unimplemented). Decisions 2–3 compose under a single
   enforcement precedence at the intake seam, highest wins: **explicit
   management operations** (drain/migrate/resume/despawn/supervise, the
   promotion swap — band 0, always-open inlet, unconditional; attention and
   every veto govern *automatic* suspension only) > **admission/
   backpressure** (a closed or full data intake refuses regardless of band;
   the sender parks and re-resolves, 33) > **the suspension gate** (NONE
   sustained for the policy window, unless vetoed) > **attention banding**
   (sub-priorities within the data region only — a band can never lift a
   task to or above the router/management bands; the stride floor refines
   it) > **`(priority, sequence)` FIFO**. Attention *proposes* (a band, a
   suspend-eligibility); the host *disposes*, honoring the higher
   authorities first — enforcement stays strictly local per host (P4). The
   suspension window is a state machine, **edge-triggered on the NONE
   transition**: a never-attended region starts no window (a subgraph under
   construction is held by a builder lease until its spec completes);
   renewed attention *during* the window cancels the pending suspend
   (hysteresis); renewal *after* the drain commits does not tear the drain —
   the region parks, then resume is emergent exactly as decision 3 states.
   And every keep-awake duty MUST be expressed as `setSelf` attention or a
   veto (`NonSuspendable`, held lease, construction lease, management
   activity) — never a bespoke scheduler exception; an active invariant
   monitor pins at LOW (yielding to real HIGH work under the stride floor),
   never HIGH; promotion, shadows, partitions, fusion, and construction are
   placements in this lattice, not exceptions to it.

   ⚠ GAP (G-56): PartitionedCell's adopted design (G-24, trigger armed)
   leaves its distribution edges open — routing-table epoch consistency
   under concurrent organelle migration, repartition-window buffering
   bounds, bulk-rebalance atomicity, supervision-travels-with-placement,
   per-shard replica targeting, range queries, and per-key attention
   routing. Proposal: generative wire tests for the stale-epoch re-route
   racing registry re-resolution and for migrate-during-repartition
   (ownership and placement maps changing near-simultaneously); a
   buffering-bound analysis for long state transfers under quotas and
   backpressure; a supervision-follows-placement API replacing
   composite-local re-apply discipline; router targeting rules when shards
   replicate (leader per shard); a scatter-gather range-read protocol over
   the state-request substrate; and the attention-routing proxy forwarding
   interest per key (93 I-8/I-19/I-9).

6. **Attention is a request, not an entitlement**
   (decided in 93 I-28, unimplemented). The answer to "an attacker claiming
   attention could summon computation": a membrane MUST be able to attenuate
   remotely-asserted interest — a per-protocol ceiling clamps the asserted
   level on the per-link LWW slot (`slot.level = min(asserted, ceiling)`;
   the fold and band-gating are untouched), a per-`Principal` rate bounds
   update frequency, and a `minAuth` floor refuses updates from
   insufficiently authenticated peers; sustained remote-driven resource
   claims are charged to the claiming `Principal`'s budget via the G-28
   host-hierarchy quota walk. Decided, not built.

7. **Magnitude joins interest at the dispatch max** (implemented, M17).
   Data urgency is the dual of subscriber interest: interest flows upstream
   over the metadata plane, magnitude rides *with* the data (a
   `cell.data.Magnitude` payload declares `size(): Double`, ≥ 0, `0.0` ⇔ no
   effective change — the I-6 contract). Opt-in via
   `AttentionPolicy.magnitudeBands: ((Double) -> AttentionBand)?`
   (null = off, order byte-identical to pre-M17): at staging the host folds
   the largest `Magnitude` among a cell's queued payloads to a band; at
   dispatch the cell's effective band is
   `max(attention band, pending-magnitude band)`. Placement in the decision-5
   authority lattice: a **sub-priority within the data region only** — it can
   never lift a task to or above the router/management bands, the stride
   floor bounds what it can starve, a boosted cell cannot attention-park
   (its effective band is above NONE), and an already-parked cell stays
   parked (magnitude is urgency; interest owns park/resume — boost is not
   folded into parked queues and is re-derived on unpark replay). Boost
   lifetime is the pending queue: cleared when the queue drains or parks, so
   no state outlives the traffic that justified it. Boosting the whole queue
   rather than one message is deliberate — per-cell FIFO is inviolable
   (spec 31), so the big message cannot overtake its own queue anyway; band
   selection reorders *cells*, never messages. Detection is a runtime
   `is`-check, advisory and P5-safe (a payload without `Magnitude` simply
   gets no boost); the KSP `magnitude` descriptor bit (G-60) can replace the
   check without an API change. Deterministic: bands derive from staged data
   only — verified single-threaded in `MagnitudeSchedulingTest` (kernel) and
   end-to-end in agora's `MagnitudePriorityTest` (M17).
