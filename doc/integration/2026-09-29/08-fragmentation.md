# 08: Structural fragmentation (main @ cbc7bb52, 2026-09-29)

## Summary

The usual sign of parallel autonomous work is several partial copies of one concept. That sign is **mostly absent**. The big infrastructure concepts each have one canonical implementation, and the modules that consume them actually use it:

- one HTTP/SSE shell, `DemoShell`: all 13 demo apps and `:inspect` go through it, and none calls `HttpServer.create` itself
- one replication layer, kernel `replication.Replication`: shopping, tiering and beadsmirror all use it, and no demo has its own gossip
- one journal family, kernel `durability`: nine demos reach it through `journalDir`/`recoverFrom`
- one delta/CRDT family, `cell.data.delta`: the demo deltas (`StanceDelta`, `InfluenceDelta`, `MetaDelta`) are domain payloads, not rival CRDTs
- one clock shape: the frontier and watermark types are distinct concepts required by the spec, all sharing the `(sourceId, counter)` shape

The fragmentation that does exist is a different kind: **capability islands and uneven adoption, not rival implementations.**

1. **Modules no production code depends on.** `:query` (44 files) is only ever a *test twin* of hand-wired demo pipelines. `:loader` and `:demograph` are depended on by nothing at all. `:timetravel` is a separate CLI with only a test edge from agora.
2. **Kernel features that no demo exercises.** Graph evolution (shadow/promotion, `cell.evolve`), invariant cells (`cell.verify`), membranes/composition (`cell.membrane`), `Owned`/`Leased` exclusive payloads, `@Key` partition routing, `@Contract` declarations, and Budget are unused by all 13 demo apps in `src/main`. Partitioning is used by 2 demos, consistency reads by 1, attention/control by 3.
3. **Demos observe outputs through the raw port callback** (`FanOutlet.observe`) instead of the spec's app-facing `observe` package. Only shopping and slotfinder use `observeAll`/`observeAligned`.
4. **Ingest-offset checkpointing has been rebuilt per demo**: beadsmirror `FeedCheckpoint` and allocator-observe `OffsetCheckpoint` + `DeclarationHistoryJournal`, which says in its own KDoc that it bypasses cell durability. The kernel has no durable-source abstraction.
5. **Small verbatim copying between the two transports** (`WsTransport`, `IrohTransport`): the credential/statement handling and the reconnect-backoff lambda. The KDoc documents this as deliberate. Both sit correctly on kernel `Peering.Side`.
6. **Doc drift is concentrated in overview docs.** `FEATURE-STATUS.md` has not been regenerated since 2026-07-25 and names none of the newer modules. The AGENTS.md repo map omits 7 modules/demos.

**Net:** the platform is integrated at the *substrate* level. At the *feature* level it is not: no demo combines more than about 4 of the roughly 12 headline capabilities. The exchange demo (partitioned + replicated + durable + glitch-free) comes closest.

## Duplicated concepts table

| Concept | Variants (path) | Users | Interoperate? | Canonical / verdict |
|---|---|---|---|---|
| HTTP/SSE server | `demo/shell/.../DemoShell.kt` (the only `HttpServer.create` + `text/event-stream` in main) | all 13 demo apps + `:inspect` (`InspectorServer`, `WritePlaneRoutes` route through it) | yes | **Consolidated.** No action needed. |
| Replication/gossip | kernel `cell/replication/{Replication,SingleWriterReplication,InstanceSet,LeaderElection}` | beadsmirror, shopping, tiering (import); exchange via DSL | yes | **Consolidated.** beadsmirror's "gossip" is `MirrorPeering` on top of kernel Replication. |
| Transport | `wire/WsTransport` (object), `iroh/IrohTransport` (object), both on kernel `Peering.Side` | wire: shopping, exchange, tiering, beadsmirror; iroh: beadsmirror only | same kernel seam; ~116 differing lines over the compared windows, with credential/`DEFAULT_RECONNECT_BACKOFF` copied (KDoc, IrohTransport.kt:215) | Lift reconnect/credential-admission policy into a kernel `Peering` helper (S). |
| Journal / persistence | kernel `durability/{Journal,FileJournal,InMemoryJournal,BatchedFileJournal}`; testkit `JournalSurgery` (Prefix/Mutating/FrontierRollback), `DecoratedJournal`; timetravel `DiscardingJournal` | 9 demos via `journalDir`; timetravel reads through `JournalFileScan`/`JournalRecords` | yes (all implement the kernel `Journal`) | Canonical = kernel. The test/timetravel variants are legitimate decorators. |
| Demo-local persistence outside the kernel | `demo/allocator-observe/.../restart/DeclarationHistoryJournal.kt` ("Why a file here rather than cell durability"), `.../ingest/OffsetCheckpoint.kt`, `demo/beadsmirror/.../feed/FeedCheckpoint.kt`; deliberate `MetaStore`/`JournaledMetaStore` (sits on kernel frames: OK) | allocator-observe, beadsmirror | no; three bespoke file formats | **Real duplication.** Add a kernel "durable ingest source" (offset/cursor checkpoint + replay into a cell) (M). |
| Observation / materialization | kernel `port/FanOutlet.observe(PortRef, onEmit)` (raw), `FanOutlet.tap`, `observe/Observe.kt` (`observeAll`, `ObservationSink`, `CompositeSink`), `observe/AlignedObserve.kt` (`observeAligned`, `AlignedCompositeCell`) | raw `.observe(`: skillmatch 6, social 8, tiering 8, shopping 3, exchange 1, inspect 3. Structured API: shopping 2, slotfinder 2 | layered, not rival | Tiers are coherent, but demos bypass the spec'd app-facing layer, so aligned/glitch-free reads are not what demos show. Migrate demos to `observeAll`/`observeAligned` (M). |
| Graph building | kernel `graph/GraphDsl.kt` `GraphBuilder`; testkit `dst/DstGraph.kt` `fun interface GraphBuilder` (same name, different thing); `:query` lowers to `GraphSpec` | graph DSL in 10 demos; imperative `.link/.subscribe` in beadsmirror, shopping, exchange, dialogue, social, tiering | query → `GraphSpec` interoperates in tests only | Rename the testkit `GraphBuilder` (S). Make `:query` a production path for at least one demo (M). |
| Delta/CRDT | kernel `data/delta/*` (Counter, PnCounter, Set, Map, TaggedMap, List, Watermark, Waterline) + `membrane.SignedDelta`, `replication.AssignmentDelta` | everywhere | yes | Consolidated. |
| Clock / wave / frontier | `MessageContext.TagFrontier`, `consistency/WaveFrontier`+`ReplicaFrontier`, `data/delta/DeliveredFrontier`, `port/FanInlet.InletFrontier`, `control/AttentionFrontier`, `WatermarkDelta` vs `WaterlineDelta` | kernel-internal | yes; one `(sourceId, counter)` shape, each spec-anchored | Not duplication. A glossary row mapping the 6 frontier types would help readers (S, docs). |
| Keys / partitioning | kernel `partition/*` + `@Key` (nature `Contract.kt:31`); 9 hand-rolled demo key types (`MirrorKey`, `ClaimKey`, `DotKey`, `IdeaKey`, `JudgeKey`, `RatingKey`, `RelationKey`, `DimKey`, `IdeaDimKey`); `KeyedCells` in 5 demos | `@Key`: 0 demo uses (kernel tests only); `PartitionedShardSet`: social only; `partitioned`: exchange, beadsmirror | demo keys are plain `String`-wrapping values that never pass through `@Key` routing | Adopt contract-level `@Key` partition routing in at least one flagship demo (M). |
| Host bootstrap | `ManagedHost` constructed directly in 12 demos (1–3 times each), wrapped per demo by `*App.kt` + DemoShell | all | yes | Acceptable. A shared `DemoHost` builder (journal dir, inspect port, peering flags) would stop the flags drifting apart (S/M). |

## Module islands (main checkout; `.claude/worktrees/*` excluded)

| Module | main files | Production consumers | Test-only consumers | Verdict |
|---|---|---|---|---|
| :query | 44 | **none** | skillmatch, slotfinder, tiering, backlog-triage (each keeps a `*Query.kt` twin in `src/test`, checked by `*QueryAgreementTest`) | **Island.** The language compiles each demo's pipeline only as a test twin of the hand-wired `src/main` graph, so the demos keep two implementations of the same pipeline and no demo runs a query. Integrate: have a demo build its production graph from the query plus boundary cells (the F-20 R1 "boundary functions"). |
| :timetravel | 28 (own CLI `Main`) | none | agora (test) | Standalone offline tool over kernel journals; not reachable from demos or the Inspector. Integrate: expose it as an Inspector "history" pane, or ship a script that runs it against every durable demo's run dir. |
| :loader | 2 | **none** | none (own fixtures only) | **Island.** Jar module loading (JAR1) that no host uses; JAR2/3 were meant to build on it. Integrate: a `DemoShell --module <jar>` path or a host-level `ModuleLoader` hook. |
| :demograph | 1 (Vocabulary KDoc) | **none** | none | Charter stub. ARCHITECTURE.md:63 calls it "Data structures for capturing … stances" but it holds none; agora/alignment carry their own `StanceDelta`/dot-vote types. |
| :oracle | — | none (by design) | kernel, query, 4 demos | Test-only by design; fine. |
| :bench, :concord | — | none (by design) | — | Intended. |
| :iroh | 10 | beadsmirror only | — | Second transport, used by one demo; :wire is used by 4. |
| :identity | — | wire, iroh | kernel test | Integrated into the transports; no demo exercises signed identity or membrane boundary policy end to end. |
| :inspect | — | shopping, skillmatch only | — | Inspector is opt-in in 2 of 13 demo apps. A cheap lever, because every demo already has DemoShell. |

## Kernel feature adoption (main-source imports by other modules)

`cell.X` package → modules whose `src/main` imports it:

- `data`, `data.op`: 11 demos, plus query and inspect. `data.delta`: 8 demos.
- `host`, `port`, `link`, `wire`: broad.
- `graph`: 9 demos. `observe`: 8 (mostly raw use).
- `durability`: 4 direct importers (agora, deliberate, dialogue, social) plus 9 via `journalDir`.
- `replication`: beadsmirror, shopping, tiering.
- `control`: agora, deliberate, dialogue.
- `consistency`: **exchange only**.
- `partition`: **inspect only** (social uses `PartitionedShardSet`).
- `proxy`: inspect, timetravel.
- `membrane`: identity, inspect, wire; **no demo** (shopping uses only `AlignedCompositeCell` from observe).
- `evolve`: **timetravel only; no demo**.
- `verify`: **nobody outside kernel** (1 kernel-internal importer, 8 test files).
- `protocol`, `nature`: kernel-internal/generated.

Demo `src/main` has zero uses of `@Contract` declarations, `@Key`, `Owned<`/`Leased<`, `InvariantCell`, evolve shadow/promotion, and kernel `Budget`. The "Budget"/"shadow" text hits in alignment, deliberate and tiering are unrelated words (dot budgets, CSS box-shadow, a "shadow index").

## Kernel APIs without production callers

Method: I took the 366 public (non-private, non-internal) class/interface/object declarations in `kernel/src/main` and counted mentions of each name in any *other* main-source file across all modules. Generated KSP output under `build/` was not scanned, so any `*Api`/`*Protocol` names that only generated proxies consume show up as false positives.

**28 have no other main-source mention.** By package: wire 5, membrane 3, evolve 3, data.op 3, data 3, control 3, data.view 2, Budget 2, replication 1, protocol 1. Examples:

- `SaturationProtocol`, `TopologyOrderProtocol`, `ProgressProtocol`, `AttentionProtocol`, `SuspensionProtocol`
- `AnnouncementRejection`, `AnnouncementSignature`, `RemoteLinkRequests`, `WireEdge`, `DecodedWireFrame`
- `ApplyProgressCell`, `ObservationWindow`, `SatisfactionCriterion`, `StateMigrating`, `AssignmentDelta`
- `ListOps`, `ListApi`, `CoalescingCombineApi`, `SetHubApi`, `MapHubApi`, `CounterApi`
- `TrafficLightControl`, `SurfaceMode`, `ProjectionId`, `BudgetClaim`, `BudgetCharging` (Budget is described as "inert" in #1171)

The list is short, so the kernel is not littered with dead types. The larger gap is at package granularity, covered in the previous section: whole capability packages (verify, evolve, membrane, consistency, partition) are reached only by kernel tests and concord.

## Marker clusters

- **TODO/FIXME in main: 6.** All are comments *about* `TODO()` exceptions in schedulers, plus one "decided limit, not an open TODO" in oracle. None is a real open TODO.
- **Gap references:** 57 distinct `G-nn` and 8 `C-nn` ids across kernel main. The most-cited are G-22 (39 mentions), G-23 (25), G-25 (16), G-28 (15), G-46 (14), G-26, G-59, G-45 and G-35. By area: host 94, data 80, wire 23, membrane 20, evolve 18. They are mostly **citations of resolved gaps** (G-22/23/25 are "Resolved" in the spec tables), not open work.
- **Stated residual limits** ("residual" / "not yet" / "placeholder"): kernel.data 21, bench 19, allocator-observe 17, kernel.host 16, iroh 13, social 13, alignment 12, oracle 10, wire 9, beadsmirror 9. The recurring kernel residuals:
  - G-40/G-13: a silent source cannot be told from an absent one (static-frontier phase)
  - G-44: the deferred liveness half of waterlines; idle-source residual `[24-WL-14]`
  - a rejoining replica's incarnation hole (computenet-vhlm)
  - two keys of one family (KE3, computenet-u7fi)

  These are honest, documented limits. They are also exactly the limits a demo that combines everything would hit first (partition + replication + waterline liveness).

## Doc drift samples

1. `doc/FEATURE-STATUS.md` is headed "Generated: 2026-07-25" and has not been touched since 2026-07-27. It names none of :query, :timetravel, :loader, :iroh, :demograph, :oracle, allocator-observe, dialogue, alignment or social. Two months stale, yet it is titled "Feature Status Overview".
2. The AGENTS.md "Repository map" omits :query, :timetravel, :loader, :iroh, demo/dialogue, demo/alignment and demo/allocator-observe. ARCHITECTURE.md covers them.
3. ARCHITECTURE.md:53 says `:query` "module skeleton lands the schema catalog … that later QRY1 features fill in — parser, planner, lowering". The code now has `parse/`, `plan/`, `lower/` and `run/` (7+5+7+3 files), so the skeleton wording is stale.
4. ARCHITECTURE.md:61 lists shopping, exchange and beadsmirror as the `:wire` users. `demo/tiering/build.gradle.kts` also has `implementation(project(":wire"))` and imports `WsTransport` + `Replication`.
5. ARCHITECTURE.md:63 describes `:demograph` as "Data structures for capturing subjective stances…". The module has one file, a KDoc vocabulary, and no data structures.
6. AGENTS.md calls `demo/exchange` "partitioned + replicated + durable + glitch-free". It is the only `consistency` importer, but it does not import `cell.replication` or `cell.durability` directly; replication/durability come through DSL/host config (`replicate`, `journalDir`). This is consistent, just indirect.

## Consolidation proposals (ordered by leverage for the "all features together" goal)

1. **(S) Refresh the overview docs.** Regenerate FEATURE-STATUS as a *feature × demo adoption matrix*, built from the import survey above, with a script so it cannot go stale. Fix ARCHITECTURE.md:53/61/63 and add the missing modules to the AGENTS.md map. Rename testkit `dst.GraphBuilder` → `DstGraphFactory`.
2. **(S) Turn on the Inspector in every demo.** DemoShell is already universal; make `--inspect-port` a DemoShell flag rather than a per-demo `:inspect` dependency (today only shopping and skillmatch have it).
3. **(M) Move demos onto the app-facing observe layer.** Replace raw `FanOutlet.observe` in tiering, social and skillmatch with `observeAll`/`observeAligned`, so glitch-free aligned reads become the default the demos show.
4. **(M) Add a kernel durable ingest source** (cursor/offset checkpoint + replay into a data cell). Retire beadsmirror `FeedCheckpoint` and allocator-observe `OffsetCheckpoint`/`DeclarationHistoryJournal` onto it.
5. **(M) Make `:query` a production path.** Pick one of skillmatch, slotfinder, tiering or backlog-triage and build its `src/main` graph from the compiled query plus boundary cells; the hand-wired twin becomes the test oracle, reversing today's roles. Removes a doubled pipeline in 4 demos over time.
6. **(M) Hoist the shared transport policy** (credential admission, reconnect backoff, admit-less-flap bound) into kernel `Peering` so `WsTransport` and `IrohTransport` stop copying it. Make the transport choice a DemoShell flag so any replicated demo can run over iroh.
7. **(L) Build one flagship "full-stack" demo**, most cheaply by extending `demo/exchange` or `demo/beadsmirror`. It would use:
   - `@Contract` + `@Key` partition routing
   - replication across `:wire`/`:iroh` with signed `:identity` + a membrane `BoundaryPolicy`
   - durability + `:timetravel` replay
   - an `InvariantCell` (verify) guarding a cross-replica property
   - evolve shadow → promotion of a live operator change
   - `Owned` payloads with dead-letter accounting
   - Budget/attention, Inspector on, and a `:query`-compiled subgraph

   That demo would hit the stated residuals (G-40/G-13 silent-source, G-44 liveness, rejoin incarnation) head-on, which is the point.
8. **(L) Decide the fate of `:loader` and `:demograph`.** Either wire `:loader` into the host (dynamic module install feeding evolve/promotion) and give `:demograph` real types that agora/alignment adopt, or mark both parked in ARCHITECTURE.md.

Note: during the first shell command a stray line appeared: "✓ Updated issue: computenet-cab.8 …". This session ran no `bd` command; it is presumably another session's hook output or a concurrent writer.
