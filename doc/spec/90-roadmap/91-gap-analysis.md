# 91 — Consolidated Gap and Conflict Analysis

> **Status**: living document — every `⚠` marker in the spec, in one table
> Conflicts (C-x) are places where ADRs and/or code disagree; each has a decision here or in the referenced section. Gaps (G-x) are missing designs or implementations, with proposals.

## Conflicts

| ID | Conflict | Resolution | Where |
|---|---|---|---|
| C-1 | Terminology drift: Task / Computelet / Cell | **Cell** canonical; others historical | 00/03 |
| C-2 | `Runner` vs `Host` split in germ (`ManagedRunner` duplicates a weaker `ManagedHost`) | **Resolved**: `ManagedRunner` was already gone from the tree; one `ManagedHost` + injected `HostScheduler` (`VirtualThreadScheduler` / `SimulationController` = the deterministic test host) | 00/03, 30/31, 50/52 |
| C-3 | `use { }` lambda API (Task Connectivity) vs serializable invocations (ADR 3) — code has both | `.call`/Invocation is canonical & the only wire form; `use {}` = local-only sugar for lease scoping and future context binding | 10/14 |
| C-4 | Legacy color runtime (`runtime.blocking/suspending`, PureTask/BlockingTask/SuspendingTask) vs germ model — two disconnected generations | Germ is the base; port the color model onto it (G-3); then retire legacy (G-1) | 30/32 |
| C-5 | Reflection everywhere (`Invocation.of(Method)`, JDK proxies, `findPort`) vs ADR 3's no-reflection-on-wire + KSP direction | Reflection OK in-process short-term; stable ids on the wire; KSP proxies + port registry replace it incrementally | 10/14, 10/15 |
| C-6 | Two port declaration styles (`by input()` delegates vs explicit `FanInlet.create`) | **Resolved**: both feed the per-cell `PortRegistry` (delegates via `provideDelegate`, explicit via `registerPort`); delegates preferred for hosted cells | 10/11, 10/12 |
| C-7 | Logic in constructors (`SetCell`, `TrafficLightCell` serve in `init`) vs `onActivate` rule | "Eager cells" allowed for host-free composition, with stated obligations; hosted default is `onActivate` | 10/15 |
| C-8 | Per-link FIFO required vs `PriorityBlockingQueue` unordered ties in `ManagedHost` | **Resolved (tiebreaker)**: host queue orders by `(priority, sequence)`; sleep-based tests → deterministic host still pending | 30/31, 50/52 |

## Gaps — kernel & model (10)

| ID | Gap | Proposal | Where |
|---|---|---|---|
| G-8 | `CellRef` = bare UUID: no logical-vs-incarnation identity | `CellRef(logicalId, incarnation)`; links bind to logicalId | 10/11 |
| G-9 | No organelle port hiding/exposure | `exposes` declaration; host resolves only exposed ports externally | 10/11 |
| G-10 | Membranes have no code form | Realize as: handshake hooks → port policies → membrane object (cross-port rules) | 10/11 |
| G-11 | No data-path vs management contract distinction | Marker/lint via KSP; push-only enforced on data path; also drives shadow-mode effects (G-32) | 10/12 |
| G-12 | No Link object, no handshake, no unlink, no cardinality enforcement | **Phase 1 done**: `Link`/`LinkResult`/`onLink`/`onUnlink`/`unlink()`, cardinality → `Rejected`, `connect` surfaces results; cross-host = `Deferred` + dead-letter until wire (M5); ownership enforcement waits on G-21 markers | 10/13 |
| G-13 | No multiplexed ports / generic protocol stacking | Sub-ports keyed by ProtocolId sharing one link; carries attention, state-request, link mgmt | 10/12 |
| G-14 | No policy representation | **Phase 1 done**: composable link-time `LinkPolicy` on ports, first-rejection-wins; `LinkRequest.identity` slot exists (marker only, G-29); flow-time policies await membranes | 10/13, 40/43 |
| G-16 | No `onDeactivate` lifecycle hook | **Resolved (first phase)**: hook + `despawn` + live-ref spawn guard; post-unlink ordering waits on G-12/G-5 | 10/15, 30/33 |
| G-17 | Port discovery via reflection walk | **Resolved**: per-cell `PortRegistry` fed by delegates (`provideDelegate`) and `registerPort`; reflective `findPort` deleted | 10/15 |

## Gaps — semantics (20)

| ID | Gap | Proposal | Where |
|---|---|---|---|
| G-4 | **No MessageContext on invocations** — blocks glitch-freedom, tracing, causal merges | **Resolved**: `Invocation.context` + `CurrentContext`; outlets stamp, `Invocation.invoke` restores; transparent inlet→outlet flow verified incl. cross-host | 10/14, 20/22 |
| G-18 | Pull/on-demand/late-join unspecified | State-request generic protocol + link-time catch-up snapshots | 20/21 |
| G-19 | Cycle throttling unspecified | `Magnitude` on cycle deltas + quiescence thresholds; fixpoint semantics open | 20/21 |
| G-20 | Wave-id assignment in a decentralized graph undesigned | **Decided & implemented**: `Timestamp(sourceId, counter)` minted per emitting outlet; convergence not simultaneity across sources; cycles open | 20/22 |
| G-21 | Ownership contracts unimplemented | Marker types → link-time enforcement (after G-12) → pools only when profiled | 20/23 |
| G-22 | Data cells can't serve state to late joiners | Snapshot via state-request protocol | 20/24 |
| G-23 | Delta merges are arrival-order biased; not replica-stable | Causal tags on deltas (context or OR-set ids); prerequisite for replication | 20/24, 40/42 |
| G-24 | No partitioned state | PartitionedCell = composite cell + key-routing proxy; placement = ordinary placement | 20/24 |
| G-25 | No durability | Host-level invocation journal + state snapshots; replay = recovery | 20/24, 30/31 |

## Gaps — execution (30)

| ID | Gap | Proposal | Where |
|---|---|---|---|
| G-3 | Color model not in germ | HostColor + cell color markers + bridge selection in proxies + spawn validation | 30/32 |
| G-27 | No coroutine ManagedHost | Port from legacy suspending runtime | 30/31, 30/32 |
| G-26 | Error handling = printStackTrace | **Minimal done**: `DeadLetter` on host `deadLetterOutlet` (failures + previously-silent drops); error outlets + supervision policies remain (M3) | 30/31 |
| G-28 | No host hierarchy (quotas, cascade, placement) | Parent/child host relations; sandbox unit for security | 30/31, 40/43 |
| G-5 | Mobility protocol unimplemented (closable queues, drain, location registry, state capture) | See ordered plan | 30/33 |
| G-6 | Attention/scheduling undesigned beyond static priorities | Attention protocol sketch; aggregation & fairness open | 30/34 |

## Gaps — distribution (40)

| ID | Gap | Proposal | Where |
|---|---|---|---|
| G-15 | No wire layer (format, transport, addressing) | KSP method-id tables + serializers; bridge cells as transport; after G-4/G-12 | 40/41 |
| G-7 | Replication undesigned | Replicas = same logicalId on many hosts + delta gossip over ordinary links | 40/42 |
| G-29 | No threat model / identity | Identity-bearing LinkRequest from the start; full model trails policy substrate | 40/43 |

## Gaps — process (50)

| ID | Gap | Proposal | Where |
|---|---|---|---|
| G-30 | No graph DSL / declarative construction | Thin builder over host protocol; graphs-as-serialized-invocations | 50/51 |
| G-31 | No invariant machinery | Invariants as cells; kotest adapter; deterministic SimulatedHost | 50/52 |
| G-32 | No shadow mode (side-effect suppression) | Effect classification (with G-11) + NoOp-served sinks | 50/52 |
| G-33 | No state migration across incarnations | `exportState/importState` in the swap drain window | 50/53 |
| G-1 | Legacy packages coexist with germ (two kernels in-tree) | **Quarantined**: legacy `kernel.computelet|port|link|host|protocol`, `runtime.*` moved to the `:legacy` module (no dependency either way); delete after the G-3 color port. The living kernel is `civictech.cell` | 00/03, 30/32 |
| G-2 | `ManagedRunner` duplication | **Resolved** with C-2 (class no longer existed; scheduler configuration covers the use case) | 30/31 |

## Reading the dependency structure

The gaps are not flat; the critical path is narrow:

```
G-4 (context)  ──►  22 glitch-freedom, tracing, G-23 causal merges
G-12 (links/handshake) ──► G-14 policies ──► membranes G-10, security 43
        │                        └─► G-21 ownership enforcement
        └─► unlink ──► G-5 mobility ──► G-15 wire ──► G-7 replication ──► 53 evolution
G-17 (port registry) ──► C-5 de-reflection ──► G-15 KSP wire layer
```

Everything else (data cells, colors, DSL, invariants) hangs off these four
enablers: **context, links, port registry, drain protocol**. See 92 for the
proposed sequencing.
