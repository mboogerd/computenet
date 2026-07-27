# ComputeNet architecture

> Snapshot as of commit `742f7ca` (2026-07-27). If the module list in
> `settings.gradle.kts` or the kernel package tree has drifted from this
> document, trust the code and update this file.

ComputeNet is an experimental Kotlin/JVM dataflow runtime. Programs are graphs
of **cells** with typed **ports**, connected by explicit **links**. Messages
carry a **wave-stamped context**; payloads are **ownership-typed** (`Owned`,
`Leased`, `Borrowed`, `Frozen`, `Redacted`). The same graph runs in-process,
across hosts in one JVM, or across JVMs over a WebSocket wire — with the same
observable semantics. The design is specification-led: `doc/spec/` is
normative, and the `:concord` module is an executable conformance suite that
checks the implementation against requirement ids embedded in the spec.

## 1. Module graph

```
                :nature  (descriptor vocabulary, no deps)
                 ▲    ▲
     implementation    api
         │              │
       :gen ───ksp────► :kernel ◄── api ── :testkit
      (KSP)   impl ──►    ▲  ▲            (test helpers)
                          │  │
                  :wire ──┘  └── :concord
                    ▲
                    │        :demo:shell (no :kernel dep — pure HTTP/SSE plumbing)
      :demo:shopping┤            ▲
      :demo:exchange┘            │  (every runnable demo depends on :demo:shell)
      :demo:{agora,slotfinder,skillmatch,tiering,backlog-triage}
```

| Module | Purpose | Depends on (main scope) |
|---|---|---|
| `:nature` | Descriptor/nature vocabulary shared by `:gen` (processor-time) and `:kernel` (runtime): `ContractDescriptor`, `CellDescriptor`, `NatureVector`/`NatureAxis`, `Manifest`, `ContractRegistry`, `StableHash`. | — |
| `:gen` | KSP processor: `@Contract`/`@CellBase`/`@Key`/`@Protocol` → generated descriptor tables, AOT proxies, port-id constants, `<Name>CellBase` classes. `:gen`'s own test suite (`ContractProcessorTest`, `NatureDescriptorSweepTest`) is the real generator-regression gate; `:kernel:compileKotlin` depends on `:gen:test`, so generator regressions fail before kernel compiles. | `:nature`, kotlinpoet, ksp-api |
| `:kernel` | The entire cell model and runtime (see §2). Transport-dependency-free by policy. | `api(:nature)`, `:gen` (+ksp), coroutines, kotlinx-serialization |
| `:testkit` | Shared test scaffolding: `SimWorld`, `awaitUntil`, `HttpProbe`, `JvmPeer`. Lives in `src/main` so a plain project dep reaches it from consumers' test source sets. | `api(:kernel)`, `api(junit)` |
| `:wire` | The one concrete transport: `WsTransport` over Java-WebSocket. Another transport = another small module behind the same kernel bridge cells. | `:kernel`, Java-WebSocket |
| `:concord` | Executable specification / conformance suite (see §5). | `:kernel`, kotlinx-serialization; kaml (test) |
| `:demo:shell` | Shared JDK `httpserver` + SSE shell (`DemoShell`, `demoPort`) used by every runnable demo. Not an application itself. `DemoShell`'s API takes no cell-model type today, so it has no `:kernel` dependency. | — |
| `:demo:*` (7 apps) | Demo applications (see §6). Only `:demo:shopping` and `:demo:exchange` use `:wire`; only `:demo:agora` and `:demo:backlog-triage` define their own KSP cells; only `:demo:exchange` needs `:nature` (it asserts composed manifests). | `:kernel`, `:demo:shell`, + per-demo extras |

Non-module directories: `buildSrc/` (two convention plugins —
`buildsrc.convention.kotlin-jvm`: JDK 21 toolchain, JUnit platform, shared test
stack (kotest-assertions, JUnit, kotlin-test), test heap 2g / forkEvery 80
(bounded because `ProtocolSupport` keys ports in a JVM-global map), and a
5-minute-per-test-method timeout backstop; `buildsrc.convention.ksp-cell`:
the KSP plugin + `implementation`/`ksp(project(":gen"))` + the generated-source
dir, for cell-authoring modules (`:kernel`, `:demo:agora`,
`:demo:backlog-triage`)), `scripts/` (`stage-preview.sh`, `plan-orchestrator/`),
`backlog/` (idea inbox, one file per prospective feature), `bugs/` (fixed-defect
reports), `doc/` (see §7), `legacy/` and `runtime/` (**untracked, sources
deleted — only stale build output; ignore them**).

## 2. `:kernel` package map

All under `kernel/src/main/kotlin/civictech/cell/`.

**Vocabulary and ports**

- `civictech.cell` — pure vocabulary: `Cell`, `CellRef`, `CellContext`,
  `MessageContext` (waves, `Timestamp`, `TagFrontier`, `ReBaselineNotice`),
  `Ownership` (`Owned`/`Leased`/`Borrowed`/`Frozen`/`Redacted`),
  `Propagate<T>`, `Consumer<T>`, `Stateful`, `MergeablePayload`, color markers
  (`BlockingCell`/`SuspendingCell`), serializers.
- `.nature` — runtime twin of the KSP scan: `manifestOf(Class)` derives
  `Manifest` tags from marker interfaces; `NatureNegotiation`/`Reconciliation`.
- `.port` — port ADT and mechanism: `Port`, `PortRef`, `PortRegistry`,
  `Use`/`Serve`/`Subscribe`/`StreamTo`, `FanInlet`/`FanOutlet`, `InletPolicy`
  with tier ADT `PolicyTier { ADMIT, GATE, ALIGN, ACTIVATE }`, cycles
  (`FeedbackInlet`, `CycleHead`).
- `.link` — edge semantics: `Link` ADT with `LinkRole { Consume, Observe }`,
  handshake + nature reconciliation (`LinkResult.Rejected` is returned, never
  thrown), `LinkPolicy`, `Interest`/`Scoped` (the one knob that makes
  replication vs partitioning vs sharded replication), `CatchUp`, identity.
- `.protocol` — generic protocol bus: `Protocols`, `EdgeOpen`/`EdgeClose`,
  `TopologyOrderProtocol`, `StateRequestProtocol`, `RetainedFrontiers`.
- `.proxy` — JDK dynamic-proxy toolkit + `Invocation` types +
  handler behaviors (`Buffering`, `Broadcast`, `Callback`, `NoOp`, `Throwing`).

**Data plane**

- `.data` — source/state cells: `SetCell` (OR-set), `MapCell`, `ListCell`,
  `CounterCell`, `PnCounterCell`, `KeyedSetCell`, `WatermarkCell`;
  `Aggregators` (count/sum/avg/min/max/topK/collect); `Windows`
  (tumbling/sliding); `Replicable`.
- `.data.delta` — `SetDelta`, `MapDelta`, `ListDelta`, `CounterDelta`,
  `PnCounterDelta`, `WatermarkDelta`; tag machinery (`TagState`, `MintedTags`,
  `DeliveredFrontier`).
- `.data.op` — operator suite: `UnionSetCell`, `IntersectSetCell`,
  `QuorumSetCell` (threshold lambda subsumes union/intersection/majority/k-of-n),
  `FilterCell`, `FlatMapSetCell`, `CountCell`, `PresenceCountCell`,
  `GroupByCell`, `MergeableGroupByCell`, `JoinSetCell`, `SemiJoinCell`,
  `JoinCell`, `CombineLatestCell`, `LookupJoinCell`; shared bases
  `TaggedSetOperator`, `KeyedBinarySetJoin`, `JoinLedger`.
- `.data.view` — read models: `SetView`, `MapView`, `CountView`
  (`apply(delta)` returns *effective* change so callers can gate broadcasts),
  `MapDiffPublisher`, hub cells. Views are not thread-safe; single-threaded
  apply.
- `.consistency` — glitch freedom: `WaveFrontier` (ALIGN-tier wave-completeness
  fold), `GlitchFreeCell`, `ReplicaFrontier`.

**Execution and operations**

- `.control` — operations plane: attention (`Attention`, `AttentionScheduler`,
  bands, aggregation), `Magnitude`, `AbsorbAck`, `ParkQueue`, progress
  (`Progress`, `VersionMinter`), suspension (`StallNotice`,
  `SuspensionProtocol`). Note: `AttentionPolicy` lives in `.host`, not here.
- `.host` — hosted execution: `ManagedHost` (the largest file; a host is itself
  a `Cell` with `managementInlet`/`routerInlet`), schedulers
  (`VirtualThreadScheduler` 🔵, `CoroutineScheduler` 🟣, `SimulationController`
  deterministic), `LocationRegistry` (`Local`/`Remote`, park-and-replay),
  `TopologyIndex`/`TopologyWalks`, `IntakeControl`/`IntakeSaturation`,
  `HostDurability`, `KeyedCells` (durable per-key families), supervision, dead
  letters (sanitized — no live `Owned`/`Leased` escapes), `CellError`,
  remoting proxies (`HostProxy`, `HostedCellProxy`, `RoutedInlet`), `TypedLink`,
  `AttentionPolicy`.
- `.observe` — app-facing reads: `ObservationSink` (`current()`, `onChange`
  with late-join catch-up), `View.set()/map()/count()`, `host.observe` /
  `host.observeAll`. Caveat: `observeAll` is point-consistent per outlet, not
  wave-aligned across outlets (G-13).
- `.durability` — `Journal` (`append`/`replay`/`reset`), `InMemoryJournal`,
  `FileJournal`. Records are opaque bytes; the host writes `WireCodec` frames —
  "a journal is a bridge to disk".

**Distribution**

- `.wire` — transport-neutral distribution: `WireCodec`/`WireFrame` (ids-only:
  contractId + methodId from `ContractRegistry`, no `Method`, no class names;
  carries `MessageContext` and `routingEpoch`), `BridgeEgressCell`/
  `BridgeIngressCell` (the network as ordinary cells — policies, membranes,
  supervision apply unchanged), `WireEdgeLink`, `Peering`/`RegistryAnnounce`/
  `RegistryMirrorCell`. `Leased` payloads are refused at egress.
- `.replication` — `Replication` (symmetric gossip mesh from ordinary
  announcements; `keyOf` generalizes to partitioning), `SingleWriterReplication`
  (`LeaderMark`, `WritePosture { AVAILABLE_FENCED, SAFETY_PARK }`),
  `InstanceSet`.
- `.partition` — `PartitionedCell`, `PartitionedShardSet` (interest-based
  router, versioned `routingEpoch`; `repartition` = interest reassignment +
  state-as-delta replay), `ShardCell`.

**Construction and evolution**

- `.graph` — `GraphDsl`: `graph(host.managementInlet) { spawn(...); link(...) }`
  produces a replayable `GraphSpec` (graphs-as-data); `TypedRef` +
  `host.lookup`; combinator sugar in `SetAlgebraGraphs`
  (`filter`/`intersect`/`union`/`count`) and `RelationalGraphs`
  (`leftJoin`/`rightJoin`/`fullJoin`). `link(...)` is typed — payload/direction
  mismatch is a compile error.
- `.membrane` — composition: `CompositeCell`, `Exposure`, `SurfaceMode
  { FLATTEN, MEDIATE }`, `WaveScope` (REMINT specified, not implemented),
  `BoundaryPolicy` (disclosure/integrity, projections, signed deltas).
- `.evolve` — live evolution: `Shadow` (candidate with `Effectful` inlets
  NoOp-served), `Promotion` (buffered-window swap + `StateMigrating`),
  `PromotionPolicy`/`PromotionJudge`.
- `.verify` — invariants as cells: `InvariantCell` (emits `Violation` on an
  outlet; one mechanism for CI, live monitoring, promotion gates),
  `ReplicaConvergence`.

## 3. Code generation (KSP)

Authors annotate port-contract interfaces with `@Contract(management, effect)`
(plus `@Key` on routing args, `@Protocol` for metadata protocols) and cell Api
interfaces with `@CellBase`. The processor (`gen/src/main/kotlin/civictech/gen/wire/ContractProcessor.kt`)
runs two rounds:

1. Round 1 emits only `<Name>CellBase` abstract classes (ports
   declared/registered, inlets statically bound to `on<Name>(value)` /
   `<name>Handler()`), so round 2 can resolve subclasses.
2. Round 2 walks all files and emits into `civictech.gen.wire.generated`:
   `ContractTable_<hash>` (a `ContractModule` with every `ContractDescriptor` +
   `CellDescriptor`, registered via `META-INF/services`), `ProxyTable_<hash>` +
   one AOT proxy class per contract, and per-cell `<Cell>Ports` id constants in
   the cell's own package.

At runtime `ContractRegistry`/`ProxyRegistry` load modules via `ServiceLoader`;
`WireCodec` encodes frames with registry ids only. Generated descriptors are
authoritative runtime metadata — thread new fields through the registry, never
recompute reflectively. Never hand-edit `build/generated/`; change `:gen` and
its tests. Modules applying KSP add `build/generated/ksp/main/kotlin` as a
source dir.

## 4. Runtime lifecycle

**Construction.** `ManagedHost(ref, scheduler, registry, attention, quota,
journal/journalFor, intakeBound, hopBound)`. Cells enter via `spawn(cell)`
(local) or `spawnBound(factory, identity, parent)` (wire-crossing: serializable
factory + `IdentityBinding`, host mints the ref). Graphs are usually built with
the `graph { }` DSL, which yields a replayable, idempotent `GraphSpec`.

**Linking.** `connect(from, outletName, to, inletName)` runs the handshake:
`LinkRequest` → policy admission → nature reconciliation → `Link` with id and
`LinkRole`. `Consume` links count toward wave frontiers; `Observe` taps never
gate a wave. Rejection is a returned `LinkResult.Rejected`. `onLinked` catch-up
means late joiners re-sync without a separate protocol.

**Dispatch.** A port invocation becomes a `HostedPortInvocation` enqueued on
the host. `IntakeControl` gates it (OPEN/SATURATED/CLOSED; `Coalesce` or
`Park`). Accepted messages stage in `AttentionScheduler`'s per-cell FIFO
queues; dispatch picks the next *cell* by attention band — band selection
happens between cells, never within one, so per-cell FIFO holds. Scheduler,
intake, and durability share one `dataLock` monitor.

**Scheduling colors.** `HostScheduler` owns "when does the queue drain":
`VirtualThreadScheduler` (🔵 blocking), `CoroutineScheduler` (🟣 suspending —
one task at a time; a suspended task parks the host: actor semantics by
design), and `SimulationController` (deterministic, seedable, single-threaded
across N hosts; randomness across hosts only). The simulation controller is
what makes the whole system — including the wire format — testable without a
network or wall clock.

**Message context and glitch freedom.** Emission at a `FanOutlet` is the
stamping point: reactive calls keep the incoming context; spontaneous calls
mint a fresh wave. `FanInlet` runs the tier chain ADMIT (may drop, but must
mint an absorb-ack for any dropped waved invocation or downstream frontiers
stall) → GATE (backpressure) → ALIGN (`WaveFrontier` folds `EdgeOpen`/
`EdgeClose` into a completeness frontier, releases waves in per-source order)
→ ACTIVATE (cold-park buffering).

**Failure.** Every failure path accounts: `SupervisionPolicy` (PROPAGATE
default; SUSPEND parks traffic for `resume(ref)`), sanitized `DeadLetters`,
`CellError`/`ErrorReporting`. No path may silently drop an `Owned`/`Leased`
payload.

**Durability.** `HostDurability` writes wire-encoded invocation frames,
checkpoints (state + processed-frontier atomically), and `Effectful` frontier
advances to an opaque `Journal`. Recovery: rebuild the graph, then
`host.recoverFrom(journal)`, then `host.checkpoint(journal)` to compact.
`KeyedCells` packages the correct ordering for per-key cell families
(pre-spawn known keys before replay so re-minted tags cannot resurrect removed
elements).

**Location and wire.** `LocationRegistry` maps `CellRef` → `Local(host)` |
`Remote(sink)`; on absence/closure invocations park in per-ref order and
replay into the next published location. Bridge cells are ordinary cells;
`:wire`'s `WsTransport` is the only socket-aware code. First message each way
is a text `HELLO` (mirror ref + peer name; listener allowlists refuse at hello
time). Disconnect unpublishes learned refs, so senders park until
re-announcement — late-starting peers replay history in order and converge.

**Replication and partitioning are one mechanism.** `Interest` is the knob:
total interest on every instance ⇒ replication; disjoint key interest ⇒
partitioning; overlapping partial ⇒ sharded replication. `Replication` runs the
same local rule on every peer (link every replica delta outlet to every other
replica's `deltaInlet` it learns about) — a gossip mesh emerges from ordinary
announcements with no coordinator; park/replay + tag idempotence double as
anti-entropy. Only `Replicable` cells qualify. `SingleWriterReplication` adds
explicit leadership (greatest epoch wins; election deferred). Cross-replica
settlement is read off the merged watermark lattice (`ReplicaFrontier`).

**Mobility.** `drainHost()` → `migrate(to)` (snapshot → serialize → restore;
target republishes and replays parked traffic) → `resumeHost()`. The host is
the unit of mobility.

**Evolution.** `Shadow.spawn` runs a candidate against live inputs with
effectful inlets NoOp-served; `Promotion.promote` swaps inside a buffered
window; `InvariantCell` provides the promotion evidence.

## 5. `:concord` — the executable specification

An implementation-neutral conformance suite. Requirement ids like
`[24-OP-UNION-01]` are embedded in `doc/spec/` chapters (EARS style); YAML
scenarios in `concord/corpus/` declare which requirements they `covers:`; the
generated matrix `doc/spec/CONCORDANCE.md` (do not hand-edit; regenerate with
`./gradlew :concord:concordance`) reports Requirement × Scenario × Status,
where `gap` rows are the testing worklist.

Layers: L0 requirement ids in spec → L1 scenario language
(`civictech.concord.value/schema`: neutral `Value` model, `Scenario`/`Step`/
`Check` ADTs, profiles `CORE`/`DIST`/`DUR`) → L2 corpus (~57 YAML scenarios
grouped by spec chapter, plus `controls/` scenarios that MUST fail) → L3
harness (`driver.Driver` — ~12 verbs, the entire per-implementation surface;
`driver.kernel.KernelDriver` is binding #1 and the only package allowed to
import `civictech.cell.*`; `check`, `oracle` (order-independent batch
reference), `generator`) → L4 concordance + lints.

`concord/corpus/DISPUTES.md` is the honesty ledger: requirements that cannot be
checked honestly are filed there, never weakened into a passing scenario.
`./gradlew :concord:check` runs `concordanceGate`, which fails the build on a
dangling `covers:` id or orphan scenario. Profiles default to
`core,dist,dur` (the full corpus); local fast loops opt *out* with
`-Pconcord.profiles=core`. Generative depth: `-Pconcord.gen.instances=N`.
Schema contracts live in `concord/schema/*.md` (single-writer,
schema-change-gated). Cross-process driver (W5) is deferred until a second
implementation exists.

## 6. Demos

All runnable demos serve HTTP + SSE via `DemoShell`; port = first non-flag arg,
else `$PORT`, else 8080. See the README for run commands.

- `:demo:shopping` — collaborative shopping list; the original multi-JVM peer
  demo (`--listen`/`--peer` over `:wire`), host-WAL journaling, kill-9-safe.
- `:demo:exchange` — **the composition probe**: two symmetric peers,
  region-keyed orders, per-cell journaling for writers, volatile aggregates
  recomputed from replay, glitch-free board. `ExchangeCompositionExitTest` is
  the repo's toughest gate (100-seed sweeps through repartition, migration,
  recovery, late join — each paired with a deliberate failing control).
- `:demo:agora` — argumentation graph (claims/edges with `DfQuad` gradual
  semantics; every edge is itself a claim), cycle heads, magnitude-band
  attention, structure-log + journal durability. Has a SolidJS/Vite frontend
  in `demo/agora/ui/` (not a Gradle module).
- `:demo:slotfinder` — smallest showcase: one `QuorumSetCell` fan-in read at
  two thresholds (intersection and near-miss), filter, group-by.
- `:demo:skillmatch` — relational operators: equi-join, negated semijoin,
  `LookupJoinCell`, `CombineLatestCell`.
- `:demo:tiering` — score fusion: two group-by averages combined into a tier
  board.
- `:demo:backlog-triage` — collective ranking with pluggable rating engines
  (elo, Bradley–Terry, TrueSkill, …) and a JSON agent API.

The incremental-dataflow demos exist to showcase the operator suite and surface
kernel gaps into `doc/demo-findings.md`.

## 7. Documentation map

Authoritative:

- `doc/spec/` — the normative spec: `00-foundations`, `10-programming-model`,
  `20-dataflow-semantics`, `30-execution-model`, `40-distribution`,
  `50-development-process`, `90-roadmap`. Entry point `doc/spec/README.md`.
  Chapters carry `Status`/`Implementation` headers and EARS requirement ids.
- `doc/spec/90-roadmap/91-gap-analysis.md` — living G-*/C-* marker ledger.
- `doc/spec/90-roadmap/93-feature-interactions.md` — cross-feature decisions
  (I-1..I-28); cited as "decided in 93" throughout the spec.
- `doc/spec/90-roadmap/95-research-plan.md` — research-gated scope.
- `doc/spec/90-roadmap/96-incremental-engines-plan.md` — proposed forward queue
  (E1–E6, unstarted).
- `doc/spec/CONCORDANCE.md` — generated; regenerate, never hand-edit.
- `concord/schema/*.md` + `concord/corpus/DISPUTES.md` — scenario-authoring
  contracts and the dispute ledger.
- `doc/demo-findings.md` — living register of demo-discovered kernel gaps.
- `backlog/` — idea inbox (some files marked IMPLEMENTED/absorbed).

Snapshots and partly-executed plans (read with their dates in mind):
`doc/FEATURE-STATUS.md` (shipped-vs-claimed survey, 2026-07-25),
`doc/CONCORD-PLAN.md` (§1–§2 still the concord reference; milestones are
history; W5 deferred), `doc/ksp-dx-catalog.md` (per-phase annotations
authoritative; phase 5 not landed).

Historical run records — do not treat as guidance; they cite pre-restructure
paths: `doc/COMPOSITION-*.md`, `doc/PERNODE-*.md` (FU-4/7/9 in
`PERNODE-FOLLOWUP-TICKETS.md` are still open), `doc/CHANGELOG-*.md`,
`doc/RESTRUCTURE-PLAN.md`, `doc/ORCHESTRATION.md`,
`doc/spec/90-roadmap/94-implementation-plan.md` (waves all merged; despite its
header it is no longer the work list), `doc/spec/90-roadmap/92-way-forward.md`
(M1–M11 history; stale about M12+). The agora-UI/design research cluster
(`doc/gui-design-guide.md`, `doc/agora-ui-*.md`, `doc/references/`,
`doc/frontend-research/`) predates the restructure and contains broken paths
and false grounding claims. `doc/adr/` is the pre-spec decision record,
consolidated into the spec (map in `doc/spec/README.md`); the untracked
`ADR - Adapter Synthesis.md` is live but PROPOSED, not decided.
`doc/research/incremental-engines/` is current background feeding plan 96.
