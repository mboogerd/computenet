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

## Milestone 3 — Colors and mobility (execution completeness)

*Goal: spec sections 32, 33 real; graphs survive placement changes.*

1. Coroutine ManagedHost + HostColor + color markers + spawn validation +
   bridges in proxies (G-3, G-27); retire legacy runtime (G-1 done).
2. Closable intake + fail-fast send + re-resolution via a location registry
   (G-5); Buffering-based park/replay.
3. Full drain protocol: suspend/resume/migrate on `HostManagementApi`;
   state capture via serializable snapshots (starts G-25).
4. Traffic-light generalized: suspension as a standard membrane behavior.

*Exit criterion: migrate a running subchain between hosts (including across
colors) with zero message loss and preserved per-link order, under load.*

## Milestone 4 — Data + verification (the developer payoff)

*Goal: the incremental dataflow layer becomes genuinely usable.*

1. Causal tags on deltas (G-23); state snapshots + late-join catch-up
   (G-18, G-22); operator library growth (filter/join/count).
2. Invariants-as-cells + kotest adapter + generative graph harness on the
   simulated host (G-31).
3. Graph DSL as thin builder (G-30) — also yields graphs-as-data.
4. First partitioned cell if a use case demands it (G-24), else defer.

*Exit criterion: a small collaborative app (e.g. shared sets/counters UI)
built purely from cells, with invariants running in CI.*

## Milestone 5 — Wire (distribution begins)

*Goal: two processes, one graph.*

1. KSP: method-id tables, serializers, generated proxies (C-5 completion,
   G-15); port metadata (ownership, color, effect markers).
2. Bridge cells over one transport (likely WebSocket or TCP first);
   remote addressing in the location registry (unifies with mobility's).
3. Ownership enforcement at link time (G-21 phase 2) — now that links,
   metadata, and the wire exist, `Owned` fan-out is rejectable everywhere.

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
