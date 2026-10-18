# 22 — Consistency: Context, Source Identity, Topology Versioning

> **Status**: Partial; context machinery, emission epochs, the recovery-baseline rule, and the retained in-band `EdgeOpen`/`EdgeClose` marker rule are implemented (local ordering is pinned by `StagedLinkCloseOrderTest`; bridge transport by `ProtocolBridgeTest`); the cycle-head rules below are decided design (93), unimplemented
> **Sources**: ADR — Task Connectivity (§2, MessageContext), 93 (feature-interaction resolutions I-1/4/5/11/13/14/24)
> **Implementation**: `cell.MessageContext`/`Timestamp`/`CurrentContext`, `cell.proxy.Invocation.context`, stamping in `cell.port.FanOutlet`

**What this chapter no longer specifies.** The former §Local glitch-freedom, §The observation frontier, PN-7, and FU-2 were retired by [ADR — Remove Glitch Freedom](../../adr/ADR%20-%20Remove%20Glitch%20Freedom.md).

## MessageContext (normative, as implemented)

Every data-path invocation carries:

```kotlin
data class MessageContext(
    val timestamp: Timestamp,  // logical time / propagation-wave id
    val sourcePort: PortRef    // identity of the emitting port
)
data class Timestamp(val sourceId: UUID, val counter: Long)  // per-source waves (G-20 decision)
```

Rules:

1. **Origination**: an external event entering the graph (a source cell
   emitting spontaneously) mints a fresh timestamp (wave id) from the emitting
   outlet's own monotonic counter.
2. **Transparent flow**: when a cell emits in response to an inlet invocation,
   the outlet invocation carries the *same* timestamp; `sourcePort` is
   rewritten to the emitting port. Framework responsibility — cell logic never
   touches context. The single stated exception to transparent flow is
   re-origination at a declared cycle head (decided in
   [93 I-5](../90-roadmap/93-feature-interactions.md); see Cycles below).
   *(Implemented: outlets stamp at emission; `Invocation`
   carries `context`; `Invocation.invoke` is the single restore point, so
   delivery and buffered replay both run under the invocation's own context;
   management invocations have null context and clear any stale wave.)*
3. **Fan-out** duplicates context; **fan-in** delivers each input's own
   context.
4. Delegation is not a context no-op: a delegated path still presents the
   correct (new) source port per hop (10/14) — every outlet hop rewrites
   `sourcePort`.
5. **Generic-protocol traffic is outside the wave domain** (decided in 93
   I-1, I-4): metadata-plane messages — attention (the exemplar),
   state-request, link management — carry null
   `MessageContext` and never mint, carry, or clear waves; `sourcePort` has
   no meaning for their upstream travel (the receiver keys on the link the
   message arrived on). True of the shipped synchronous `ProtocolSupport`
   delivery (M6.1), recorded here as normative for every carrier. A protocol
   payload MAY carry its own monotonic discriminator — attention's
   per-emitter LWW `version` — a third use of the clock shape beside waves
   and merge tags (see Interaction below), never a wave.

Two decided field extensions to `MessageContext` (93, unimplemented):
`hop: Int = 0` — incremented per transparent-flow hop, reset to 0 by
cycle-head re-origination, a pure divergence guard that does not alter
timestamp identity (93 I-5) — and `baseline: TagFrontier?` — non-null marks a
delta as a catch-up baseline rather than a live wave position (93 I-24; see
Pull/late-join below).

⚠ GAP (G-36): all metadata-plane notices are single-hop (M6) — attention
retraction, upstream disinterest, and state-request pulls do not propagate
through absorbing or stateless intermediaries to remote producers. Proposal:
one hop-by-hop re-emission rule for metadata-plane notices with loop prevention
and the band-quantization interaction pinned per protocol — attention levels
and transitive retraction across damped hops, disinterest quiescing remote
producer cones, and requestState forwarding through stateless cells
(93 I-1/I-4/I-9/I-16).

*(G-4 resolved. Context is captured into invocations at the cross-host proxy
(`HostedCellProxy`) and the `Buffering` recorder; wire bridges are M5.)*

### Source identity: emission epochs (decided in 93 I-14; implemented)

A "source" is one outlet during one **emission epoch** of one instance —
never the logical cell, and finer than the instance (each emitting outlet
has its own counter). An outlet MUST mint a fresh collision-free `sourceId`
with `counter = 0` at each epoch start, and MAY preserve `(sourceId,
counter)` across an epoch boundary only when the framework restores a
provable counter high-water for that outlet (Rules S1/S2). Fresh epochs:
cold start, supervision RESTART (no proven high-water), new-instance spawn
(replicas, shadow candidates), promotion without state transfer. Preserved
epochs: suspend/resume, migration (the high-water rides the migration
payload), and a promotion whose state transfer carries each outlet's
`(sourceId, highWater)` inside the buffered swap window. Thus `(sourceId,
counter)` is never reused, and distinct instances or epochs mint disjoint
source ids.

- **Origination at merge (Rule S4)**: a `Replicable` cell re-emitting an
  effective delta after a CRDT merge is a wave origination point — it MUST
  mint a fresh wave from its own outlet. Each outlet epoch of each instance
  is a distinct physical source, so a logical upstream with several instances
  presents several sources and consumers get convergence. Gossip convergence
  is carried by merge tags, not shared wave identity, so no source id
  circulates around the gossip mesh.

- **Visibility of instance swaps (decided in 93 I-11)**: a
  preserved-epoch adoption — the candidate adopts the incumbent's
  `(sourceId, highWater)` inside the drained swap window — is invisible to
  downstream consumers: no EdgeEvent, and the same source lane continues
  monotonically. A fresh-epoch succession of a live lane MUST be announced
  via the `ReBaseline` supersession notice (93 I-22) naming the superseded
  source ids — never silently.

  *(C-12 resolved, W2.1 + D-C12: the landed RESTART announces its fresh epoch
  as this rule requires; the arms below are the landed set, each with its
  pinning test or scenario. Reclamation of the superseded epochs stays G-42,
  below.)*

**Landed arms.**

Fresh epoch (a fresh `sourceId`, `counter = 0`; a succession of a live lane
announces `ReBaseline`):

- Supervision RESTART: `ManagedHost` mints per-epoch ids and emits
  `ReBaseline` naming the superseded ones —
  `RestartReBaselineTest`, scenario `21-REBASE-01`.
- Replica / shadow-candidate spawn (fresh by construction) and the T2
  catch-up-fallback promotion swap (fresh, with `ReBaseline`) —
  `EpochTransitionFreshMintTest`, arms (b) and (c).
- Volatile cold start: a volatile cell's outlet mints a fresh epoch per
  incarnation — `OutletWaveRecoveryTest`
  (`a volatile cell's outlet still mints a fresh epoch per incarnation`).

Preserved epoch (no `ReBaseline`; the same source lane continues):

- Promotion state transfer — `PromotionWaveStateTest`. Drain and
  migration are listed as preserved above but no test pins their epoch
  continuity (migration's is part of G-42, below).
- Durable recovery with a checkpoint (`RECORD_OUTLET_WAVE`, `[KFX-12]`,
  `[24-DUR-04]`) — `OutletWaveRecoveryTest`
  (`a checkpointed journaled source still fires each delta exactly once across a crash`,
  `an epoch rotated before the checkpoint is restored as-is, never re-derived over`),
  `OutletHighWaterRecoveryTest`, scenario `DUR-SRCID-02`. These pin the
  restored `sourceId` and counter high-water; the absence of a `ReBaseline`
  on this branch is asserted by `OutletWaveRecoveryTest`
  (`BS-22 checkpointed - recovery restores the epoch and announces no ReBaseline`).
- Durable recovery without a checkpoint (ref-derived `sourceId`; full-WAL
  replay re-derives the counter) — `OutletWaveRecoveryTest`
  (`BS-22 - durable recovery is a preserved-epoch continuation, ref-derived and not re-baselined`,
  asserting no `ReBaseline` on recovery, and
  `a journaled source feeding an effectful sink fires each delta exactly once across a crash`),
  scenario `DUR-SRCID-01`; a pre-KFX-12 journal — `JournalCompatibilityTest`.

Durable recovery is a preserved-epoch continuation with or without a
checkpoint — the ref-derived `sourceId` plus a checkpoint-carried or
replay-re-derived counter high-water — and announces no `ReBaseline`
(qfi22-D6, `[KFX-12]`, `[24-DUR-04]`).

Recovery replay is a baseline. Once-delivery of a journaled-to-journaled tail
(`[24-DUR-03]`) is pinned by `JournaledEdgeReplayOnceTest`.

Open corner, unpinned (qfi22-D10): a RESTART after the last checkpoint
followed by a crash re-derives the derived epoch over the fresh epoch's
counters. No test pins that interaction; it is not resolved.

⚠ GAP (G-42): epoch source-ids and restart generations accrete unboundedly —
OR-set/PN source columns are never reclaimed, and counter/generation
continuity across migration and host failure is unpinned. Proposal: safe
reclamation of provably-superseded epochs (compaction riding G-25
checkpoints), a concrete migration-payload field carrying the outlet counter
high-water, durable-counter batching kept off the emission hot path, and
generation derivation from the journal high-water (fresh high base on
non-durable hosts) so post-restart tags never alias
(93 I-14/I-22/I-3/I-7).


## Topology versioning

Structural changes must be causally consistent with value updates:

- Each link/unlink applied by a host is stamped into the same logical-time
  domain as data waves through the in-band effect markers below.
- *(G-20 decided and implemented: wave ids are per-source monotonic counters —
  `Timestamp(sourceId, counter)`, minted by the emitting outlet. No global
  time protocol. [22-SRC-01] Cross-source joins SHALL get **convergence** —
  equal folds on all observers at quiescence — not simultaneity. Cycle wave
  semantics are decided in 93 I-5 — see Cycles below; fixpoint semantics stay
  open with G-19.)*

In-band edge markers (decided in 93 I-13; the retained marker and transport
rules are implemented): the stamp lives on the *effect*, not the invocation.
Link/unlink stays a null-context
management op (§MessageContext); its effect emits a per-link
`EdgeOpen`/`EdgeClose` marker riding the affected link's own FIFO —
`EdgeOpen` ahead of any data, `EdgeClose` after the last. The marker carries
no wave id of its own. The marker frame forms cross a wire bridge today as
ordinary `PORT_PROTOCOL` frames on the `topology-order` protocol, preserving
that per-link FIFO ordering (W3.2; 40/41 point 4; `ProtocolBridgeTest`).
Local close ordering is pinned by `StagedLinkCloseOrderTest`.
Emission is gated on a downstream having expressed topology-order interest;
eager and mergeable consumers ignore EdgeEvents entirely. Migration, wire
re-resolution, and instance swaps emit NO EdgeEvent — versioning operates on
the logical edge set, which they leave intact — qualified by the epoch
guard: a fresh-epoch succession is still not an edge event but is
wave-observable via `ReBaseline` (§Source identity above; 93 I-14/I-22).


## Interaction with other parts

- **Suspension/migration (30/33)**: parked invocations retain their context;
  buffering happens at ports (`Buffering` proxy), and context rides inside
  each recorded invocation.
- **Pull/late-join (21)**: a catch-up snapshot is *not* stamped with the
  wave it represents — a multi-source fold has no representable wave.
  *(Implemented, W2.2 — decided in 93 I-24)*: a catch-up state-as-delta is a
  **baseline** — marked by the nullable `MessageContext.baseline:
  TagFrontier?` field, a merge-tag frontier carried for dedup and
  incremental pull, never a wave position — causally anchored at the
  link-install topology event. A consumer installs the baseline as arm state;
  subsequent live emissions continue from there. Tag frontiers are valid only
  for per-source-monotone families (full-state fallback otherwise,
  `since = null`).
- **Causal merge tags (24)**: observed-remove set tags reuse the `Timestamp`
  type but are minted cell-locally, *not* taken from the current wave —
  OR-set correctness needs a tag unique per add instance, and a wave id
  repeats across every cell the wave touches. The wave context still rides
  delta invocations unchanged; tags and waves are separate uses of one clock
  shape — attention's per-emitter LWW `version` (93 I-4) is a third: a
  payload discriminator, never a wave.
- **Waterline floor (24)**: the lateness floor (`[24-WL-02]`) is one more
  non-wave use of the monotone-clock shape — a monotone event-time *value*
  carried as payload, never a wave position (`[24-WL-04]`). A
  `WaterlineDelta` emission is an ordinary transparent-flow emission
  (§MessageContext rule 2) riding the wave whose data advanced the floor.
  Evictions the floor drives emit under the `WaterlineDelta`'s wave id
  (`[24-WL-06]`).
- **Cycles (21)**: decided in 93 I-5, unimplemented. Every cycle declares at
  least one **cycle head**; the head's feedback→emission transition mints a
  fresh wave instead of preserving the incoming one — the single stated
  exception to transparent flow (§MessageContext rule 2). The incoming wave
  terminates at the head, and each loop iteration is a fresh wave. Wave
  uniqueness follows: no `(sourceId, counter)` traverses any cell twice;
  same-wave re-entry is structurally prevented, not detected.
  `MessageContext.hop` (incremented per transparent-flow hop, reset by head
  re-origination) is a liveness guard that dead-letters undeclared cross-host
  cycles past a host-configured bound; it does not alter timestamp identity.
  Quiescence below the G-19 magnitude threshold terminates the wave sequence:
  the fixpoint is the state after the last emitted wave settles.

## Recovery is a baseline, not a wave (PN-2)

> **Status**: Implemented (PN-2). Journal replay re-enters the intake as a
> catch-up **baseline**.

[22-REC-01] WHEN a cell recovers state by journal replay, every replayed
emission SHALL be installed as a baseline and produce no wave, so observers
converge to the pre-crash fold and `Effectful` processed frontiers deduplicate
it (the same baseline rule applies to `StateRequest` replies,
§Pull/late-join). Before PN-2, `ManagedHost.recoverFrom` re-injected each
journaled frame as an ordinary live wave; before PN-1, frames could instead
drop on a `sourcePort` mismatch.

**Rule of recovery.** Every emission in the replayed cone carries
`MessageContext.baseline` (a `TagFrontier`), so it is installed as state and
not treated as a live wave. Mechanism:

- `recoverFrom` runs its whole replay inside a thread-local `ReplayScope`
  (the exact analogue of `PendingReBaseline`) holding the baseline frontier.
- A replayed frame that already carries a wave context — a **mid-graph** cell's
  frame, reactive from an upstream wave — is stamped `baseline` up front. A
  reactive re-emission then inherits it through the ordinary `MessageContext`
  copy, so the mark rides the entire cone across the asynchronous dispatch that
  follows the synchronous re-injection. A cell that *originates* mid-replay
  reads the frontier from `ReplayScope` at its spontaneous-emission point.
- A **root** cell's externally-driven frame carries no wave context and is
  replayed verbatim — byte-for-byte behavior for graphs that do not opt in, and
  the reason the mid-graph case (non-null context frames) is the one this rule
  newly covers.

`Effectful` processed-frontier consultation is unchanged: replay still dedups
an already-applied `(sourceId, counter)` against it, so an effect fires once
across crash + replay + resume. The invariant `released + suppressed ==
journal.replay().size` holds — a silent drop lowers `released` and fails loudly.
