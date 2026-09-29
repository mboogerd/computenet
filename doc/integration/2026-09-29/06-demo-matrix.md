# 06 — Demos as integration evidence (DEMO x FEATURE matrix)

Pinned to main @ cbc7bb52 (2026-09-29). Method: import surveys per module
(`grep -rhoE 'import civictech\...'`), per-feature keyword counts over
`demo/*/src/main`, then spot reads of every hit that decided a cell. Test-only
use is marked `t` (it proves the feature *can* be composed with the demo, but
the running demo doesn't use it). Transitive-only use is marked `(U)`.

## Summary

The demos are **side-by-side, not integrated**. 13 demos plus the shell;
each one uses a narrow, mostly different slice of the platform. The common core
is solid: `ManagedHost` + `LocationRegistry`, `data`/`data.op` operators with
deltas, `streamTo`/`link` wiring, and `observe` views appear in nearly every
demo. Beyond that core the platform's distinctive features are used by one or
two demos each, or none:

- **Used by zero running demos:** Owned/Leased, colors (Blocking/Suspending
  markers), mobility, evolution/promotion (shadow, `Promotion.promote`),
  invariants/`verify`, membranes, budgets, `:demograph`, time-travel
  (agora *tests* only), query/Datalog (4 demos' *tests* only), explicit
  `:identity` (only transitively through `:wire`/`:iroh` handshakes).
- **Generated `@Contract`/`@CellBase` cells** appear in only two demos
  (alignment `WeightedFusionCell`, backlog-triage `RatingCell`); every other
  demo composes kernel-provided operators or hand-built cells.
- **Durability is bypassed more often than used:** alignment, allocator-observe,
  agora and deliberate each hand-roll part or all of their persistence
  (JSONL write logs, offset checkpoints, LWW metadata stores, topology
  "structure logs") because the kernel journal carries no topology (F-25) and
  there is no cell for authoritative write-side state.
- **The one demo designed to compose features** (`demo/exchange`,
  partitioned + replicated + durable + glitch-free) is 382 lines and still
  designs around a gap (GroupBy is not Replicable, so it partitions the input
  and recomputes per shard). Its partition/replication types are imported only
  in its tests.
- `:demograph` is an 83-line KDoc charter (`Vocabulary.kt`, zero declarations)
  with zero consumers.
- `doc/demo-findings.md`: of 28 findings only F-1, F-3, F-11 are CLOSED and
  F-9 PARTIAL; ~20 kernel gaps surfaced by demos remain open.

## Matrix

Legend: U used in `src/main`; B bypassed (demo reimplements the concept);
t test-only; (U) transitive only; - absent.

| Feature \ Demo | agora | align | alloc-obs | backlog | beadsmir | deliberate | dialogue | exchange | shopping | skillmatch | slotfinder | social | tiering |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| @Contract/@CellBase gen cells | - | U | - | U | - | - | - | - | - | - | - | - | - |
| Explicit links / typed wiring | U | U | U(raw) | U | U | U | U | U | U | U | U | U(families) | U |
| Deltas & data-cell operators | U | U+B | U | U | U | U+B | U | U | U | U | U | U+B | U |
| Glitch-free waves / observe | U | B | U | U | - | U | U | U | U(F-27) | U | - | U | U |
| Interest-driven execution | - | - | - | - | - | - | - | U | - | - | - | U+B | - |
| Owned/Leased | - | - | - | - | - | - | - | - | - | - | - | - | - |
| Hosts (ManagedHost) | U | U | B(no host) | U | U | U | U | U | U | U | U | U | U |
| Colors (Blocking/Suspending) | - | - | - | - | - | - | - | - | - | - | - | - | - |
| Partitioning | - | - | - | - | - | - | - | U+B, t | - | - | - | - | - |
| Mobility | - | - | - | - | - | - | - | - | - | - | - | - | - |
| Durability / recovery | U+B | B | B | - | B(Dolt) | U+B | U | U | U | - | - | U | - |
| Replication | - | - | - | - | U | - | - | U(t) | U | - | - | - | U |
| Multi-JVM / wire / iroh | - | - | - | - | U(ws+iroh) | - | - | U | U | - | - | - | U |
| Identity / security | - | - | - | - | (U) | - | - | (U) | (U) | - | - | - | (U) |
| Invariants / verify | - | - | - | - | - | - | - | - | - | - | - | - | - |
| Evolution / promotion | - | - | - | - | B(re-baseline) | - | - | - | - | - | - | - | - |
| Inspector | - | - | - | - | - | - | - | - | U | U | - | - | - |
| Time-travel | t | - | - | - | - | - | - | - | - | - | - | - | - |
| Query / Datalog | - | - | - | t | - | - | - | - | - | t | t | - | t |
| Demograph | - | B | - | - | - | - | - | - | - | - | - | - | - |
| Budgets | - | - | - | - | - | - | - | - | - | - | - | - | - |

Row counts (U in main): links 13, operators 13, hosts 12, observe 10,
durability 6, wire 4, replication 3, inspector 2, gen cells 2, interest 2,
partitioning 1, everything else 0.

Key citations:
- Gen cells: `demo/alignment/.../AlignmentCells.kt:64` `@CellBase`;
  `demo/backlog-triage/.../RankingCells.kt:30` `@CellBase` (and `:116` —
  `MetaRankCell` deliberately NOT, runtime-sized inlets = F-8).
- Interest: `demo/social/.../ViewerInterest.kt:91` `registry.setInterest`,
  `SnbPipeline.kt:146`; `demo/exchange/.../Main.kt:126` `Interest.Slots.forShard`.
- Partition: exchange `Main.kt:114-126` (shard hosts per Interest slice);
  `PartitionedShardSet`/`ShardCell` only in `ExchangeComposition{Exit,Dst}Test`.
- Replication: `demo/shopping/.../Main.kt:23`, `demo/tiering/.../TieringApp.kt:22`,
  `demo/beadsmirror/.../MirrorPeering.kt:6` (`Replication` + `rebind`).
- Wire: `WsTransport` in shopping/tiering/exchange/beadsmirror;
  `civictech.iroh.IrohTransport` in beadsmirror `IrohMirrorTransport.kt:4`.
- Identity: no demo imports `civictech.identity`; `:wire` pulls it for
  `HelloProtocol`/`AnnouncementIdentity`, so it rides along unconfigured.
- Durability (kernel): `FileJournal` agora `AgoraApp.kt:35`, deliberate
  `DeliberateApp.kt`; `Journal` dialogue `DialogueRuntime.kt:14`, social
  `SocialRecovery.kt:4`; `KeyedCells.hostJournal` exchange `Main.kt:88`.
- Inspector: shopping `Main.kt:493`, skillmatch `SkillMatchApp.kt:22`.
- GlitchFreeCell: exchange `Main.kt:4`.
- Test-only: `civictech.query.*` in backlog-triage/skillmatch/slotfinder/tiering
  tests (differential vs hand-wired pipelines, F-20);
  `civictech.timetravel.reconstruct.*` in agora tests (`AgoraGraphSource`).

## Zero-use features

Not used by any demo's running code:

1. **Owned/Leased** exclusive payloads — the only mention is a comment
   (`social/Feed.kt:425` "no Owned payloads in this schema").
2. **Colors** (`BlockingCell`/`SuspendingCell` in `ColorMarkers.kt`) —
   deliberate calls LLM CLIs (`CliProposer.kt`, `ProcessBuilder`) on its own
   threads/semaphores instead of as blocking-colored cells.
3. **Mobility** (migration/relocation) — none; social measured placement
   pressure (F-26) but does not move cells.
4. **Evolution / promotion** (`civictech.cell.evolve`, shadow, promote) — none.
   Only `inspect.edit`'s staged applier (product findings, WKB2) uses
   `Promotion.promote`, outside demos.
5. **Invariants** (`civictech.cell.verify`) — none. Vision success criterion 4
   ("verified by invariants... evolve by promoting") has no demo.
6. **Membranes** (`civictech.cell.membrane`) — none.
7. **Budgets** (`Budget.kt`, landed #1171 as an inert Unlimited ledger) — none.
8. **`:demograph`** — no module depends on it; the module has no declarations.
9. **Time-travel** — agora tests only, and needs a demo-side graph adapter (F-25).
10. **Query/Datalog** — tests only (4 demos); no demo serves a `query { }` graph.
11. **`:identity`** as a configured feature (keys, admission) — transitive only.

## Bypasses

| Demo | Evidence | Platform feature that should carry it | Why not (if discernible) |
|---|---|---|---|
| alignment | `AlignmentApp.kt:133-146`: authoritative write-side indices `topics/ratings/weights/dots/judgements` in `TreeMap`/`HashMap` under `Object()` lock, "dots ... never in the dataflow"; hand-rolled JSONL journal via `Files.newOutputStream` (`:144-159`); `scored` read model "folded off the fusion outlet" (`:141`); `Pairwise.kt:59-80` local win-count fold | Keyed state cells (OrMap/KeyedSetCell) + kernel `Journal`/`FileJournal`; per-actor stance aggregation = `:demograph` | No keyed cell with atomic cross-key update (F-6); demograph has no API |
| allocator-observe | No `ManagedHost` at all — raw `Cell`/`FanInlet`/`registerPort`; `restart/DeclarationHistoryJournal`, `ingest/OffsetCheckpoint.kt` (byte offset + head fingerprint) | Hosted execution + kernel durability/recovery | Source-offset checkpointing for an external tail has no kernel counterpart |
| agora | `AgoraService.kt:102-143` `graph.jsonl` structure log appended/replayed by hand beside `FileJournal` | Topology journaling (93 I-7) | F-25: journal carries no topology — decided, unlanded |
| deliberate | Same structure-log pattern (`CredenceGraph.kt:27-31`); `Durability.kt:47` `MetaStore` LWW map of per-claim metadata; `Claim.kt:138-146` `EngineState` — a parallel in-memory claim/edge tree under an engine lock; LLM calls on own threads/semaphore | Topology journal; state cells; colored (blocking) cells | F-25; the engine's orchestration is outside the dataflow by design |
| social | `InterestDrivenFamily.kt` joins Interest to spawning on the demo side off-thread; `BoundedReader` as KRD stand-in; complex reads order/limit demo-side; 5 `ConcurrentHashMap` sink/session registries (`SocialGraph.kt:118-121`, `SocialApp.kt:191`) | Kernel interest->spawn path; bounded reads; ordered top-K/range-scan aggregates | F-24, F-21, F-23 |
| exchange | Partitions the *input* by `value.within(shardInterests[i])` and recomputes per shard (`Main.kt:114-126,209`) | Replicable GroupBy / MapDelta merge | "GroupBy-not-Replicable / no-MapDelta-merge gap is designed around" (`Main.kt:120`) — F-7 |
| shopping | `{items, produce}` composite cannot be wave-aligned | Glitch-free composite over independent roots | F-27 (and F-5) |
| backlog-triage | Bucketing inside app `FuseCell`; `MetaRankCell` runtime inlets | Threshold/bucket operator; descriptor-visible dynamic ports | F-2, F-8 |
| dialogue | Extraction mapper failure accounting around `FlatMapSetCell` | Failable/accounted flatMap seam | F-13 |
| beadsmirror | Re-baseline = replace projector and `Replication.rebind` (hand-rolled swap); persistence is Dolt | Evolution/promotion swap | swap semantics predate/avoid `evolve`; external source of truth |

Notably *not* a bypass: beadsmirror's two-node gossip uses the real
`Replication` + `:wire`/`:iroh` (no hand-rolled gossip found anywhere).
`DemoShell` HTTP/SSE is the sanctioned app edge, not ad-hoc state.

## Flagship candidate analysis

Closest to all features (count of U in main): **exchange** (links, operators,
glitch-free, interest, hosts, partitioning, durability, replication, wire —
9) then **beadsmirror** (8: operators, links, hosts, durability-ish,
replication, ws+iroh, identity transitively, OrMap folds) and **shopping** (8,
incl. inspector). All three miss: generated cells, Owned/Leased, colors,
mobility, evolution, verify, time-travel, query, demograph, budgets.

Exchange is a probe (382 lines, one file, synthetic orders), not a product.
Flagship candidates against the vision's targets:

1. **shopping -> collaborative editing / hybrid client-server** — already has
   replication, wire, durability, inspector; missing gen cells, interest
   (peers replicate everything), verify, evolution, time-travel. Blocked on
   F-27.
2. **social -> decentralized social** (vision target) — the only real
   interest-driven demo, durable, large (4.6k lines), but single-JVM: no
   replication, wire, partitioning or mobility despite measured placement
   pressure (F-26). Adding peers + Interest-scoped replication + mobility +
   partitioned families would exercise vision criteria 2 and 3 directly.
3. **agora/deliberate/dialogue -> distributed knowledge graph / distributed
   cognition** — richest semantics (credence cells, LLM proposers) but
   single-node, and the two structure-log bypasses sit here.
   `:demograph` (stances, preferences, aggregation) naturally belongs here and
   in alignment.

Recommendation: **social** as the flagship integrated demo (decentralized
social covers interest, partitioning, replication, mobility, identity,
Owned (e.g. moderation tokens), durability, time-travel, inspector, query
for SNB reads, invariants for feed consistency), with **shopping** as the
small hybrid-client showcase. Prerequisite kernel work: F-24 interest->spawn,
F-25 topology journal, F-7 replicable GroupBy/MapDelta merge, F-27 composite
waves, F-21/F-23 bounded/ordered reads, F-6 cross-key atomic keyed state.

## Open demo-findings

`doc/demo-findings.md` (28 findings): CLOSED F-1 (CombineLatestCell), F-3
(KeyedSetCell), F-11 (IntersectSetCell tags). PARTIAL F-9 (tagged-map regroup
operator still missing). Open (no closure marker; kernel grep finds no
remedy): F-2 threshold/bucketing, F-4 topology evolution unexercised, F-5
edge-of-graph view composition not glitch-free, F-6 cross-key atomic keyed
state, F-7 N-ary MapDelta combine, F-8 dynamic inlets invisible to descriptors,
F-10 hand-wired SetCell catch-up on attach, F-12 bd `is_blocked` (external),
F-13 failable flatMap seam, F-14 extractor claim minting (demo-side), F-15
CP-A3 edge-local absorb-ack withholds output, F-19/F-22 no batch-as-one-wave
path, F-20 query residual vs hand-wired skillmatch, F-21 bounded read
primitive, F-23 ordered top-K / range scan / count-distinct (topKBy and
countDistinct landed, rest in flight), F-24 Interest joined to no spawn path,
F-25 journal carries no topology (I-7 topology journaling unlanded despite
#1165's recovery lifecycle — no topology symbols in `cell/durability`), F-26
placement pressure (G-24 trigger), F-27 independent-root composite not
wave-aligned, F-28 lateness `GroupByCell` unreachable via typed ref.
F-16/F-17/F-18 are measurements, not gaps.

`doc/product/findings.md`: one settlement (WKB2/R4, 2026-09-24): kernel keeps
partial+report `GraphSpec.applyTo` semantics; staged applier with
`Promotion.promote` lives in `civictech.inspect.edit`; follow-up spec note to
95 §R4 left to a human; R4 failure-case catalogue appended.
