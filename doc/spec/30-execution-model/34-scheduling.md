# 34 — Scheduling and Attention-Driven Execution

> **Status**: Implemented (M6): decisions 1–4 below are code; decision 7 is code
> (M17); decisions 5–6 and additions marked "(decided in 93 I-n)" are decided
> design, unimplemented
> **Sources**: ADR 0 (§5), ADR 1 (§6, §7, §8), ADR — Computelet Kernel (attention propagation as a generic protocol); 93 (I-4, I-6, I-9, I-16, I-28)
> **Implementation**: `cell.control.AttentionSupport`/`AttentionBand`/`AttentionScheduler`
> over generic-protocol sub-channels (`cell.protocol.Protocols`, G-13 minimal); host
> mapping in `ManagedHost` + `cell.control.AttentionPolicy` (band dispatch, stride floor,
> NONE-window park/replay); magnitude-band dispatch in `ManagedHost.stage/dispatchOne`
> over `cell.control.Magnitude`
> (opt-in via `AttentionPolicy.magnitudeBands`, M17);
> interest scatter in `AttentionSupport.scatter`. Verified:
> `AttentionGenerativeTest` (100 seeds + starvation control),
> `PartitionedAttentionTest` (scatter park/replay), and
> `MagnitudeSchedulingTest`. Remaining: attention and state-request notices are
> single-hop (G-36 below). Attention crossing the wire is resolved (G-35, W3.2,
> see below and 41 point 4).

## Principle

Computation follows **interest** (P6). Subscribing to a result implies partial
responsibility for its inputs; unattended subgraphs quiesce; attended ones get
resources proportional to attention. The suspension machinery (33) is the
enforcement arm: no interest → suspend; renewed interest → resume.

Everything scheduled here is an **invocation**. An off-host synchronous read is
not one: it is answered from a published immutable snapshot without entering the
queue, so it has no band, raises no attention (P6), cannot park or be parked,
and cannot perturb the schedule it is observing (31 §The read plane). The
asynchronous state reads that *do* enqueue (`snapshotOf` and its paged sibling)
are ordinary tasks and are scheduled as such — which is precisely why they hand
back a future the caller bounds.

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
   downstream link set, so topology is part of the aggregate.

   *(G-58 core resolved, W4.5)*: the per-emitter monotonic `version`
   (`AttentionSupport.VersionMinter`) is minted once per outgoing update and
   compared wraparound-safely (signed-difference, the classic sequence-number
   trick — correct across a `Long` overflow); the per-link slot algebra
   (`AttentionFrontier`) applies an incoming update iff its version is newer,
   absorbing duplicate/stale delivery (idempotency) while a genuinely later
   version supersedes; `onUnlink` retracts and garbage-collects the slot,
   re-folding the remainder; the decay aggregator's `cadenceTicks` knob
   quantizes decay recompute into discrete tick buckets (choosing values is
   research, 95 §R6 — the knob itself is not).

   ⚠ GAP (G-58, residual): frontier state across migrate/relink,
   deadlineHint folding, retraction racing a per-cell park,
   policy-window calibration, and band pinning for hard monitors remain
   open. Proposal: decide frontier-as-migratable-snapshot-state vs
   rebuild-by-re-announce and its trigger boundary; a min/earliest fold for
   deadlineHint distinct from the level fold; settle the retraction × park
   race under the cell's `NonSuspendable` veto; calibrate
   policyWindowSteps against graph depth; and decide whether hard real-time
   monitors may pin above LOW composing with the stride floor (93 I-4/I-9).
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
3. **Attention-driven suspension parks per cell.** A host parks a cell after
   that cell remains at band NONE for its policy window; the cell's inlets then
   buffer at the boundary (33). `NonSuspendable` vetoes automatic suspension
   for that cell only. The host does not walk the graph, a neighbouring cell's
   veto does not spread, and no suspended/resumed notice travels downstream.
   Renewed interest reaches that cell's band listener and unparks it; buffered
   invocations replay in order.

   **PN-19 — interest scatter and per-instance park** (plan §3b):
   - **(a) attention scatters by interest.** Attention travelling upstream
     through a partitioned/replicated node reaches only the instances whose
     interest overlaps the attending consumer's scope — the metadata plane
     reuses the data plane's overlap rule (`Interest.overlaps`) via the
     per-link `AttentionSupport.scatter` predicate; a link outside the scope
     learns an explicit NONE (distinct from neutral NORMAL). The per-key
     economics beyond this stays G-62/M16.
   - **(b) an unattended instance parks like any cell.** A shard/replica with
     no covering consumer falls to band NONE and parks through the ordinary
     NONE-window suspension gate — the router/mesh treat a parked peer as
     temporarily unavailable, not dead (its intake parks, never drops; renewed
     interest replays it).

   ⚠ GAP (G-36): all metadata-plane notices are single-hop (M6) — attention
   retraction, upstream disinterest, and state-request pulls do not propagate
   through absorbing or stateless intermediaries to remote producers. Proposal:
   one hop-by-hop re-emission rule for metadata-plane notices with loop
   prevention and the band-quantization interaction pinned per protocol —
   attention levels and transitive retraction across damped hops, disinterest
   quiescing remote producer cones, and requestState forwarding through
   stateless cells (93 I-1/I-4/I-9/I-16).
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
   transition**: a never-attended cell starts no window (a subgraph under
   construction is held by a builder lease until its spec completes);
   renewed attention *during* the window cancels the pending suspend
   (hysteresis); renewal *after* the drain commits does not tear the drain —
   the cell parks, then resume is emergent exactly as decision 3 states.
   And every keep-awake duty MUST be expressed as `setSelf` attention or a
   veto (`NonSuspendable`, held lease, construction lease, management
   activity) — never a bespoke scheduler exception; an active invariant
   monitor pins at LOW (yielding to real HIGH work under the stride floor),
   never HIGH; promotion, shadows, partitions, fusion, and construction are
   placements in this lattice, not exceptions to it.

   ~~⚠ GAP (G-56): PartitionedCell's distribution edges open.~~ **Resolved by
   design (CP-D1)**: partitioning is the disjoint-interest setting of the
   replication mesh and inherits its edges (40/42 §Interest-scoped instance
   sets; 24 §Partitioned state). The one scheduling-layer residual — per-key
   attention routing, forwarding interest per key to a shard — is the economic
   layer G-62, not a partition-specific edge.

6. **Attention is a request, not an entitlement**
   (decided in 93 I-28, unimplemented). The answer to "an attacker claiming
   attention could summon computation": a membrane MUST be able to attenuate
   remotely-asserted interest — a per-protocol ceiling clamps the asserted
   level on the per-link LWW slot (`slot.level = min(asserted, ceiling)`;
   the fold and band-gating are untouched), a per-`Principal` rate bounds
   update frequency, and a `minAuth` floor refuses updates from
   insufficiently authenticated peers; sustained remote-driven resource
   claims are charged to the claiming `Principal`'s budget via the G-28
   host-hierarchy quota walk. The `minAuth`/`ceiling`/rate clamp is built
   (W4.1, `BoundaryPolicy.protocolAuthority` at a `mediateOutlet()`
   crossing — a *local* assertion is exempt, per 93 I-28 §4.2); the
   per-`Principal` G-28 quota charge for sustained remote-driven resource
   claims remains open (G-62).

7. **Magnitude joins interest at the dispatch max** (implemented, M17).
   Data urgency is the dual of subscriber interest: interest flows upstream
   over the metadata plane, magnitude rides *with* the data (a
   `cell.control.Magnitude` payload declares `size(): Double`, ≥ 0, `0.0` ⇔ no
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
