# 92 — Proposed Way Forward

> **Status**: proposal (the one document meant to be argued with and rewritten)
> Premise: the germ kernel validated the right things first — invocation capture, delegation, cross-host proxies, host queues, boundary buffering. The next work should **harden the four enablers** (context, links, port registry, drain) before widening scope, because every ambition in 40/50 lands on them.

## Milestone 1 — One kernel (consolidation) ✅ DONE

*Goal: a single, coherent codebase that matches spec sections 10 and 31.*

1. ~~Fold `ManagedRunner` into `ManagedHost` (C-2)~~ (`ManagedRunner` had
   already been deleted; the remainder became the `HostScheduler`
   configuration + `SimulationController`); `Thread.sleep` tests replaced
   with deterministic ones (C-8 fix: sequence-number FIFO tiebreaker).
2. Port registry from delegates/factories; retire reflective `findPort`
   (G-17). Explicit-style ports register too (C-6).
3. `onDeactivate` hook (G-16); error handling to a defined dead-letter path
   (G-26 minimal).
4. Rename/move: germ → `civictech.cell` (or chosen name); delete legacy
   packages or quarantine under `legacy/` pending G-3 port (G-1 start).

*Exit criterion — met: kernel tests green with no sleeps, no reflection in
port resolution, one host class; legacy quarantined in `:legacy`.*

## Milestone 2 — Context and links (the semantic spine) ✅ DONE

*Goal: spec sections 13, 14, 22 implementable claims become true.*

1. `MessageContext` on `Invocation`; host-managed current-context;
   transparent inlet→outlet flow (G-4).
2. `Link` objects with handshake (`onLink/onUnlink`, `LinkResult`),
   `unlink()`, cardinality enforcement (G-12); policies as link-time
   functions with an identity slot (G-14 phase 1).
3. Wave ids: per-source `(sourceId, counter)` timestamps (G-20 decision).
4. `GlitchFree` wrapper cell: dependency tracking to the frontier + version
   buffering (22), validated on the diamond topology.

*Exit criterion — met: the diamond invariant test runs 200 seeds of
randomized cross-host scheduling glitch-free, with a control run proving the
harness detects glitches (`GlitchFreeDiamondTest`).*

## Milestone 3 — Colors and mobility (execution completeness) ✅ DONE (core)

*Goal: spec sections 32, 33 real; graphs survive placement changes.*

1. ~~Coroutine ManagedHost + HostColor + color markers + spawn validation
   (G-3, G-27); legacy runtime deleted (G-1 done)~~ — done (M3.1). Bridges
   degenerated to nothing while intakes are unbounded; see 32.
2. ~~Closable intake + fail-fast send + re-resolution via a location registry
   (G-5); Buffering-based park/replay~~ — done (M3.2).
3. ~~Full drain protocol: suspend/resume/migrate on `HostManagementApi`;
   state capture via serializable snapshots (starts G-25)~~ — done (M3.3).
4. ~~Traffic-light generalized: suspension as a standard membrane behavior~~
   — done (M3.4): `cell.membrane.TrafficLightCell`; same `Buffering`
   primitive at port and location granularity (33).

*Exit criterion — met: `SubchainMigrationTest` migrates a running stateful
subchain across colors under load, 100 seeds, zero loss, per-link FIFO, with
a control run proving the harness detects loss. Supervision policies landed
as M3.5 (G-26 resolved, narrowed to error outlets → M4).*

## Milestone 4 — Data + verification (the developer payoff) ✅ DONE (core)

*Goal: the incremental dataflow layer becomes genuinely usable.*

1. ~~Causal tags on deltas (G-23): observed-remove tags on the set family,
   commutative CounterCell, documented limits for map/list~~ — done (M4.1).
   ~~State + late-join catch-up (G-22, G-18 core): post-install `onLinked`
   hook, state-as-delta unicast, Stateful data cells~~ — done (M4.2).
   ~~Operator library: filter/count/intersect/join~~ — done (M4.3).
2. ~~Invariants-as-cells + kotest adapter + per-cell error outlets (G-26
   completed)~~ — done (M4.4). ~~Generative graph harness on the simulated
   host (G-31)~~ — done (M4.6).
3. ~~Graph DSL as thin builder (G-30) — also yields graphs-as-data
   (`GraphSpec`)~~ — done (M4.5).
4. Partitioned cell (G-24) **deferred as planned**: the exit app's sets and
   counters are atomic structures; trigger = the first keyed dataset with
   placement pressure (24).

*Exit criterion — met: `CollaborativeAppTest` runs a three-user
shopping-list-with-votes session built purely from cells (DSL-constructed
views, mixed 🔵/🟣 hosts) with a mid-session joiner catching up, a mid-session
host migration, and an injected failure consumed via an error outlet under
RESTART — invariants asserted through the kotest adapter, 100 seeds, with a
control run proving the harness detects non-convergent views. A demo UI over
the same graph is M4.8 (`:demo`).*

## Milestone 5 — Wire (distribution begins)

*Goal: two processes, one graph.*

Sequenced (decisions: kotlinx.serialization as codec; WebSocket transport in
a new `:wire` module keeping `:kernel` dependency-free; G-8 deferred behind a
wire-frame version byte; demo splits as symmetric peers):

1. ~~M5.1 — contract identity: KSP method-id tables (`@Contract` →
   `ContractDescriptor`, ids hashed from FQN + erased JVM signature),
   `ContractRegistry`, `Invocation.contractId/methodId` (C-5: stable ids
   exist; in-process dispatch stays reflective)~~ — done.
2. ~~M5.2 — serializers + `WireFrame` envelope (kotlinx.serialization, JSON
   array polymorphism, stable `@SerialName` discriminators; version byte
   reserves G-8; uniform polymorphic codec instead of per-method generated
   bindings)~~ — done.
3. ~~M5.3 — loopback bridge cells: egress/ingress as ordinary cells on
   SimulationController hosts; generative harness with a bridge at a random
   cut, 100 seeds + drop/corrupt control runs (the wire's P1 proof:
   `BridgedGraphTest`)~~ — done.
4. ~~M5.4 — remote addressing: `LocationRegistry` resolves Local|Remote;
   announcements as ordinary wire invocations (`Peering`/`RegistryMirrorCell`);
   `lookup` returns remote-backed proxies~~ — done. Cross-registry `connect`
   request/response frames deliberately skipped: the registry-proxy +
   `Use.fixed` linking pattern covers the exit app; `LinkResult.Deferred`
   keeps its existing contract (handshake runs on the target host, rejections
   dead-letter there). Revisit only if M5.7 proves it necessary.
5. M5.5 — WebSocket transport driver in `:wire` (org.java-websocket server);
   disconnect ⇒ unpublish ⇒ park.
6. M5.6 — ownership enforcement at link time (G-21 phases 1+2) — `Owned`
   fan-out rejectable everywhere: local, cross-host, and bridge links.
7. M5.7 — exit: `DistributedCollaborativeAppTest` (loopback split, 100
   seeds + control run) + the demo as two symmetric WebSocket peers.

*Exit criterion: the M4 demo app running across two JVMs/machines unchanged.*

## Milestone 6+ — The decentralized horizon (research tracks)

Not sequenced — these are open designs to be developed against running code:
interest/attention protocol and suspension-driven scheduling (G-6);
interest-driven replication + gossip + anti-entropy (G-7); identity, trust,
sandboxed hosts (G-29, G-28); shadow deployment, promotion, state migration
(G-32, G-33); the programming environment and visualization.

## Working agreements (process, immediate)

- **Specs lead code**: changes to semantics update the relevant spec file in
  the same change-set; `⚠` markers are added/removed as facts change. The ADRs
  remain immutable history; this spec is the living surface.
- **Kernel review gate**: any addition to the kernel model must cite the
  principle (00/02) it serves and must survive the P1 test (meaningful in a
  single-threaded simulation).
- **One migration at a time**: C-4/G-1 (two kernels in-tree) is the largest
  source of drift risk; Milestone 1 exists to close it before new semantics
  are added.
