# Core-model abstraction

This document maps the core model to the ComputeNet kernel at commit `bac06571`. The mapping is anchored to named symbols rather than line numbers: when the kernel changes, re-verify every row against the named symbol before treating the mapping as current. The model deliberately checks the decided semantics; this map is not evidence that the implementation already conforms to every modelled rule.

## Model symbols

| Model name | ComputeNet concept | Kernel path and symbol at `bac06571` | Abstracted away |
| --- | --- | --- | --- |
| `Sources` | Outlet emission epochs; a model source is the `sourceId` half of a wave identity. | `kernel/src/main/kotlin/civictech/cell/MessageContext.kt`: `Timestamp.sourceId`; minting in `cell/port/FanOutlet.kt`: `sourceId` | UUID generation and epoch succession. |
| `Cells` | Runtime cell identities. | `kernel/src/main/kotlin/civictech/cell/CellRef.kt`: `CellRef` | Cell APIs, descriptors, hosting, and lifecycle. |
| `Edges` | First-class directed links. | `kernel/src/main/kotlin/civictech/cell/link/Link.kt`: `Link.id` | Link admission, policy negotiation, and transport realization. |
| `From` | The outlet endpoint of each directed edge. | `kernel/src/main/kotlin/civictech/cell/link/Link.kt`: `Link.from` | Port type and nature metadata. |
| `To` | The inlet endpoint of each directed edge. | `kernel/src/main/kotlin/civictech/cell/link/Link.kt`: `Link.to` | Port type and nature metadata. |
| `WaveBound` | Finite model-checking bound on source counters. | No kernel counterpart; `Timestamp.counter` is a `Long`. | Unbounded execution and counter overflow. |
| `EmitFrom` | Sources permitted to emit in a configuration. | `kernel/src/main/kotlin/civictech/cell/port/FanOutlet.kt`: `FanOutlet.call` | It is a static set: a source outside it is silent for the whole run, not merely for one wave after previously emitting. |
| `MayAbsorb` | Cells whose effective output may be empty for a delivery. | `kernel/src/main/kotlin/civictech/cell/data/op/Emit.kt`: `emitOrAbsorb` | Operator-specific, data-dependent absorption is represented by a nondeterministic choice. |
| `AbsorbAck` | Control knob for whether absorption emits settlement metadata. | `kernel/src/main/kotlin/civictech/cell/control/AbsorbAck.kt`: `FanOutlet.absorbAck`; `FALSE` has no production-mode counterpart | The Boolean exists to make the load-bearing behavior falsifiable. |
| `Fifo` | Control knob for per-edge FIFO delivery. | `kernel/src/main/kotlin/civictech/cell/control/AttentionScheduler.kt`: `AttentionScheduler.dataQueues`, `stage`, and `dispatchOne` preserve per-cell FIFO, which is stronger than the model's per-edge FIFO | Attention-band choice between cells, intake, parking, and coalescing; `FALSE` is a deliberately broken control. |
| `Frontier` | Cells that perform the glitch-free completeness fold. | `kernel/src/main/kotlin/civictech/cell/consistency/GlitchFree.kt`: `GlitchFreeCell`; `cell/consistency/WaveFrontier.kt`: `WaveFrontier` | The model has a set of cell ids rather than installed inlet policies and arms. |
| `Carries` | Per-edge source reachability used to derive the expected-edge set ([22-GF-01] step 1). | `kernel/src/main/kotlin/civictech/cell/consistency/WaveFrontier.kt`: `expectedLocalEdges` | **UNVERIFIED DIVERGENCE:** code inspection finds that the kernel expects every open, non-suspended local `LinkRole.Consume` edge whose floor is below the wave, for every source; it has no general `Carries` filter. `22-LIVE-01` exercises a `QuorumSetCell`, not this frontier. No reproducing kernel test was run; tracked by `computenet-pu4fi` (G-13 residual). |
| `IgnoreArm` | Control-only exclusion from the completeness predicate. | No kernel counterpart | `{}` in every model configuration; a non-empty value deliberately creates torn completion. |
| `WatermarkByMax` | Control over monotone versus assign-last watermark updates. | `kernel/src/main/kotlin/civictech/cell/consistency/WaveFrontier.kt`: `advanceWatermark` uses `merge(..., ::maxOf)` | `TRUE` in every model configuration; `FALSE` deliberately regresses a watermark. |
| `counter` | Per-source monotone wave counter. | `kernel/src/main/kotlin/civictech/cell/port/FanOutlet.kt`: `waveCounter` and `Timestamp(sourceId, waveCounter.incrementAndGet())` | `Long` overflow and persistence/restoration of outlet wave state. |
| `channel` | Sequence of messages waiting on each edge. | `kernel/src/main/kotlin/civictech/cell/control/AttentionScheduler.kt`: `dataQueues`; envelope in `cell/proxy/HostedPortInvocation.kt`: `HostedPortInvocation` | The runtime queues per cell, selects attention bands, and can park/coalesce; the model retains only per-link delivery order. |
| `absorbed` | History of source/counter pairs absorbed by each cell. | `kernel/src/main/kotlin/civictech/cell/data/op/Emit.kt`: `emitOrAbsorb` | The kernel performs the choice but keeps no equivalent global history; the history exists for properties. |
| `acked` | History of source/counter pairs acknowledged on each edge. | `kernel/src/main/kotlin/civictech/cell/control/AbsorbAck.kt`: `FanOutlet.absorbAck` and `Protocols.sendDownstream(..., Protocols.Progress, ...)` | The kernel sends metadata but keeps no equivalent global history; visited-edge traversal details are absent. |
| `watermark` | Per-edge, per-source highest settled counter. | `kernel/src/main/kotlin/civictech/cell/consistency/WaveFrontier.kt`: `watermark` and `advanceWatermark` | Map representation and concurrent access. |
| `floor` | Per-edge, per-source high-water captured when an edge opens. | `kernel/src/main/kotlin/civictech/cell/consistency/WaveFrontier.kt`: `EdgeState.floors`, populated by `registerHandlers` | Floors never change in these configurations. |
| `open` | Whether an edge still participates in completeness. | `kernel/src/main/kotlin/civictech/cell/consistency/WaveFrontier.kt`: `EdgeState.open` | Every edge remains open in these configurations. |
| `suspended` | Recoverably stalled edges excluded in `DEGRADE` mode. | `kernel/src/main/kotlin/civictech/cell/consistency/WaveFrontier.kt`: `suspendedEdges`; mode in `GlitchFreeCell.WaveMode` | The set remains empty in these configurations. |
| `pending` | Buffered per-wave contributions, retaining their arrival edge. | `kernel/src/main/kotlin/civictech/cell/consistency/WaveFrontier.kt`: `pending` and `Buffered` | Invocation arguments, ownership, arm release callbacks, replacement accounting, and checkpoint acceptance. |
| `released` | Per-frontier, per-source history of released counters. | `kernel/src/main/kotlin/civictech/cell/consistency/WaveFrontier.kt`: `flushReady` invokes each buffered arm's `release` callback | The kernel has no equivalent history variable; payloads and individual callbacks are collapsed to counters. |
| `applied` | Last released counter per frontier, arrival edge, and source. | `kernel/src/main/kotlin/civictech/cell/consistency/WaveFrontier.kt`: `pending` retains contributions by edge and `flushReady` releases them | This is exposed model history for P01, not a kernel map. |
| `flushedHigh` | Highest released wave per frontier and source. | `kernel/src/main/kotlin/civictech/cell/consistency/WaveFrontier.kt`: `flushedHighWater` | Representation and checkpoint/recovery behavior. |
| `Delta(s,t)` | An opaque data invocation carrying a wave timestamp. | `kernel/src/main/kotlin/civictech/cell/MessageContext.kt`: `MessageContext.timestamp`; `Timestamp(sourceId, counter)` | Payload values, method identity, source port, baseline/rebaseline fields, and hop count. |
| `Progress(s,t)` | Metadata-plane absorb acknowledgement through counter `t`. | `kernel/src/main/kotlin/civictech/cell/control/Progress.kt`: `Progress(sourceId, thru)`; handler in `WaveFrontier.registerHandlers` | Protocol encoding and traversal state. |
| `InitWaves` | Empty channels and histories with zeroed source counters. | `FanOutlet.waveCounter`; link/queue construction; no single kernel initializer | Hosting and topology are assumed already constructed. |
| `Emit(s)` | Mint the next spontaneous wave and fan it over all outgoing edges. | `kernel/src/main/kotlin/civictech/cell/port/FanOutlet.kt`: `FanOutlet.call` and `Timestamp(sourceId, waveCounter.incrementAndGet())` | Payload, disclosure, taps-before-consumers ordering, ownership, and delivery failures. |
| `ForwardAt(e,i)` | Transparent cell forwards one selected message unchanged. | `FanOutlet.call`; `kernel/src/main/kotlin/civictech/cell/control/AbsorbAck.kt`: `relayAbsorbAcks` for `Progress` | Data and metadata forwarding are one abstract queue operation. |
| `AbsorbAt(e,i)` | Consume an effective-empty delta and optionally send `Progress`. | `emitOrAbsorb`; `FanOutlet.absorbAck` | The model chooses absorption nondeterministically and makes delivery plus acknowledgement atomic. |
| `DeliverNonFrontier(e,i)` | Deliver to a pass-through or absorbing non-frontier cell. | `AttentionScheduler.dispatchOne`; operator handler; `emitOrAbsorb` | Handler code, scheduling bands, failures, and ownership accounting. |
| `Init` | `InitWaves` plus empty frontier state and initially open edges. | `kernel/src/main/kotlin/civictech/cell/consistency/WaveFrontier.kt`: `registerHandlers`, `EdgeState`, and frontier maps | Edge-open events and policy installation are collapsed into the initial state. |
| `EmitStep(s)` | `Emit` while frontier variables remain unchanged. | Same minting path as `Emit` | The frame's later delivery is a separate model step. |
| `FrontierDelivery(e,i)` | Advance settlement, buffer or release a delta, and flush all newly complete waves atomically. | `kernel/src/main/kotlin/civictech/cell/consistency/WaveFrontier.kt`: `offer`, `advanceWatermark`, `ready`, and `flushReady`; `Protocols.Progress` handler in `registerHandlers` | The model emits one `Delta(s,t)` per released wave per out-edge; the kernel replays each buffered invocation separately under the same timestamp. |
| `Deliver(e)` | Choose one queued message, then use frontier or non-frontier delivery. | `AttentionScheduler.dispatchOne`; `WaveFrontier.offer` | Cross-cell scheduling and the concrete invocation call stack. |
| `Terminating` | Explicit stuttering state after emitting sources finish, channels empty, and no frontier wave remains pending. | No kernel counterpart | A finite TLC run needs a legitimate terminal stutter; the runtime remains live. |
| `Next` | Nondeterministic choice among emission, delivery, and terminal stutter. | `FanOutlet.call` plus hosted scheduling and dispatch | Threads, task queues, backpressure, and wall-clock time. |
| `waveVars`; `frontierVars`; `vars` | State tuples used by `UNCHANGED`, stuttering, and action properties. | No kernel counterpart | TLA+ bookkeeping only. |
| `InEdges(c)`; `OutEdges(c)` | Topology projections by link endpoint. | `kernel/src/main/kotlin/civictech/cell/link/Link.kt`: `Link.from` and `Link.to` | Registry lookup and port identity. |
| `Expects(c,s)` | Incoming edges whose declared reachability contains the source. | `WaveFrontier.expectedLocalEdges` is the closest implementation symbol | General source-to-edge reachability is the unverified `Carries` divergence above. |
| `Upstream(e)` | Recursive structural source reachability through the model DAG. | `FanOutlet.sourceProvenanceResolver` and the fan-in overload of `relayAbsorbAcks` provide limited in-process provenance; there is no general kernel traversal | The model computes exact reachability from its complete static topology. |
| `RemoveAt`; `Enqueue`; `PickPositions`; `SetToSeq` | Queue mutation, delivery-position choice, and deterministic representation helpers. | `AttentionScheduler.stage`/`dispatchOne` are the closest queue operations | These are model helpers; set order is semantically irrelevant except where explicitly sorted. |
| `CompleteAt`; `Complete` | Every expected open, non-suspended edge whose floor for the source is below the wave's counter has settled it. | `WaveFrontier.expectedLocalEdges`, `isSettled`, and `ready` | Replica gates and interest-scoped quorum settlement. |
| `MinNat`; `MaxNat`; `SortedCounters`; `MessagesForSources`; `ReadyMessages` | Sort ready counters and construct downstream abstract messages. | `WaveFrontier.flushReady` sorts ready timestamps and releases buffered invocations | Model set-to-sequence mechanics and the one-message-per-release abstraction. |
| `Range` | Convert a released-counter sequence to membership history. | No kernel counterpart | Property helper only. |
| `Fairness` | Weak fairness for each edge's `Deliver(e)` action. | `AttentionScheduler` supplies bounded cross-cell selection via its stride, not this per-edge temporal assumption | Host/thread progress and failures are outside the model. |
| `Spec` | `Init`, stuttering `Next`, and delivery fairness. | No single kernel symbol | Composition of the model only. |
| `P01`–`P06`, `P08` | Checked safety/liveness operators; P07 is deliberately control-only. | See the property map below. | Runtime observations are represented by model histories. |

## Property map

| ID | Definition name | Model configuration(s) | Control configuration(s) | Concord scenario or runtime sibling |
| --- | --- | --- | --- | --- |
| `P01` | `NoTornWave` | `MCFrontierSmall.cfg`; `MCFrontierSmall-nested.cfg`; `MCFrontierSmall-absorb.cfg` | `MCTornRelease.cfg` | `22-GF-DIAMOND-01`; related nested and fan-in siblings are `22-GF-NESTED-01` and `22-WAVE-FANIN-01`. Caveat: these corpus scenarios are set-shaped `QuorumSetCell` graphs because a downstream wrapper cannot coalesce an already-torn scalar combine stream. |
| `P02` | `CompletenessSound` | All four `MCFrontierSmall*.cfg` model configurations | `MCTornComplete.cfg` | scenario-gap candidate; no corpus scenario directly asserts the completeness predicate. |
| `P03` | `WatermarkMonotone` | `MCFrontierSmall.cfg`; `MCFrontierSmall-nested.cfg`; `MCFrontierSmall-absorb.cfg` | `MCWatermarkRegress.cfg` | scenario-gap candidate. |
| `P04` | `ReleaseOrder` | All four `MCFrontierSmall*.cfg` model configurations | `MCOutOfOrder.cfg` | scenario-gap candidate. |
| `P05` | `EventuallyReleased` | `MCFrontierSmall-absorb.cfg`; `MCFrontierSmall-twoSources.cfg` | `MCNoAbsorbAck.cfg` | scenario-gap candidate; `22-LIVE-01` is the nearest corpus sibling but checks a `QuorumSetCell`. |
| `P06` | `AbsorbAckEmitted` | `MCFrontierSmall-absorb.cfg` | `MCNoAbsorbAck.cfg` is the load-bearing-knob control, but lists P05 and reaches deadlock rather than checking P06 directly | scenario-gap candidate; `22-LIVE-01` is the nearest corpus sibling but checks a `QuorumSetCell`. |
| `P07` | `AbsorbAckLoadBearing` (control only; no TLA+ definition) | `MCFrontierSmall-absorb.cfg` is the positive baseline | `MCNoAbsorbAck.cfg` | Runtime sibling: `GlitchFreeStallTest`'s `a later wave's watermark retroactively completes an earlier wave an edge silently absorbed`. |
| `P08` | `SilentSourceNeverBlocks` | `MCFrontierSmall-twoSources.cfg` | `MCOverAligned.cfg` | `22-LIVE-01`, with the unverified `Carries`/`WaveFrontier.expectedLocalEdges` divergence above; the corpus graph uses `QuorumSetCell`. |

## Non-properties

None of P01–P08 is a non-property: each is stated and checked at this abstraction, with P07 expressed by its deliberately broken control. P06 is near-definitional because acknowledgement is enqueued in the same model action that records absorption; its load-bearing consequence is P07. P01 recognizes absorption only at the producer one hop above the lagging edge. Those are limitations of the abstraction, not reasons to discard either property.

## Limitations

- A non-frontier pass-through cell forwards `Progress` unconditionally. The kernel's `relayAbsorbAcks` unary form relays only while the hop has exactly one open `LinkRole.Consume` input edge. Every pass-through cell in these configurations is unary, so the behaviors coincide here.
- The model emits one abstract `Delta(s,t)` per release per outgoing edge. `WaveFrontier.flushReady` instead invokes every buffered contribution's release callback under the shared timestamp.
- A wave absorbed on every arm creates no pending delta, is never released, and emits no downstream acknowledgement. The nested all-absorbing case remains a scenario-gap candidate.
- A `Progress` delivered to a frontier advances its watermark and is consumed; it is never forwarded. This matches the `Protocols.Progress` handler in `WaveFrontier.registerHandlers`.
- Absorption is nondeterministic, while kernel operators decide from their payloads; P01 observes absorption only one hop upstream.
- Delivery, absorption acknowledgement, and release are atomic model actions. Kernel attention bands, intake, parking, coalescing, handler failures, and inter-thread timing are absent; only FIFO order is retained.
- Ownership, wire encoding, and durability/replay mechanics are absent.
- Local and bridged edges are the same model object. Location transparency is assumed, not proved ([22-GF-03]).
- `floor`, `open`, and `suspended` are present for later evolution but inert in every configuration in this feature: floors remain zero, edges remain open, and none is suspended.
- A source excluded from `EmitFrom` stays silent for the whole run. The model does not cover a source that emits in one wave and then becomes silent.
- `FrontierDelivery` advances the watermark before its late-delta branch. At `bac06571`, `WaveFrontier.offer` releases a delta at or below `flushedHighWater` without calling `advanceWatermark` or `flushReady`. P01 compares only last-applied counters, so a two-wave flush in one transition could mask a torn earlier wave; per-link FIFO makes that behavior unreachable in the default configurations.
- The `Carries` mismatch is an unverified code-reading divergence, not a reproduced runtime failure or a formal-model finding (`computenet-pu4fi`).
- The harness accepts any control exit 11, 12, or 13 without checking which property produced it. Direct TLC probes are therefore needed to validate the property-to-control rows; the harness gap is tracked by `computenet-nc0pg`.
