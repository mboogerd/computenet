# ComputeNet architecture

> Snapshot as of commit `b1efd10` (2026-07-28). If the module list in
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
                :nature  (descriptor + runtime wire vocabulary, no deps)
                 ▲    ▲
        implementation api
         │              │
       :gen ───ksp────► :kernel ◄── api ── :testkit
      (KSP)              ▲  ▲  ▲         (test helpers)
                          │  │  └─────────────────┐
                  :wire ──┘  └── :concord          │
                    ▲                              │
                    │        :demo:shell ──────► :inspect  (Inspector backend;
                    │      (no :kernel dep —          opt-in consumer:
      :demo:shopping┤       pure HTTP/SSE plumbing)   :demo:shopping,
      :demo:exchange┘            ▲                    :demo:skillmatch —
      :demo:{agora,slotfinder,skillmatch,tiering,backlog-triage}    --inspect-port)
                                  (every runnable demo depends on :demo:shell)
```

`:gen` is a `ksp(...)`-only (processor-time) dependency of `:kernel` and every
other cell-authoring module (T09 §A) — it never lands on their compile or
runtime classpath, so KotlinPoet/`symbol-processing-api`/`kotlin-reflect`
(`:gen`'s own dependencies) don't either.

| Module | Purpose | Depends on (main scope) |
|---|---|---|
| `:nature` | Descriptor/nature vocabulary shared by `:gen` (processor-time) and `:kernel` (runtime): `ContractDescriptor`, `CellDescriptor`, `NatureVector`/`NatureAxis`, `Manifest`, `ContractRegistry`, `StableHash`. Also holds the `civictech.gen.wire` runtime vocabulary a cell author actually touches — `@Contract`/`@CellBase`/`@Key`/`@Protocol` and `ProxyRegistry` (T09 §A; package kept as `civictech.gen.wire` for import compatibility, module is `:nature`). | — |
| `:gen` | KSP processor: `@Contract`/`@CellBase`/`@Key`/`@Protocol` → generated descriptor tables, AOT proxies, port-id constants, `<Name>CellBase` classes. `ContractProcessor.process()` delegates its five inline lints to `ContractLints` and its three descriptor-table builders to `contractTable()`/`protocolTable()`/`cellTable()` (T09 §D). `:gen`'s own test suite (`ContractProcessorTest`, `NatureDescriptorSweepTest`) is the real generator-regression gate; `:kernel:compileKotlin` depends on `:gen:test`, so generator regressions fail before kernel compiles. | `:nature`, kotlinpoet, ksp-api |
| `:kernel` | The entire cell model and runtime (see §2). Transport-dependency-free by policy. `@CellBase` is landed and adopted here — every source cell/operator in `civictech.cell.data`/`.data.op` is authored this way. | `api(:nature)`, `ksp(:gen)` (processor-time only), coroutines, kotlinx-serialization |
| `:testkit` | Shared test scaffolding: `SimWorld`, `awaitUntil`, `HttpProbe`, `JvmPeer`. Lives in `src/main` so a plain project dep reaches it from consumers' test source sets. | `api(:kernel)`, `api(junit)` |
| `:bench` | The JMH benchmark harness module (BEN1). Three source sets: fixtures in `bench/src/main/kotlin` (`civictech.bench`), `@Benchmark` bodies in `bench/src/jmh/kotlin` (`civictech.bench.micro`), fast unit tests in `bench/src/test/kotlin`. Kotlin benchmarks are discovered by `me.champeau.jmh`'s **bytecode** generator (`jmhRunBytecodeGenerator` over compiled classes), not by an annotation processor, so no kapt is involved and `jmhAnnotationProcessor` is empty. `:bench:verifyBenchmarkDiscovery` runs as part of `check` and fails the build naming `bench/src/jmh/kotlin` if zero benchmarks were generated — a benchmark module that silently generates nothing would otherwise report a successful run. Benchmark *execution* is deliberately outside the build lifecycle: neither `:bench:jmhJar` nor `:bench:jmh` is reachable from `:bench:build`. Infrastructure, not an application — depended on by nothing. | `:kernel`, `:testkit`, jmh |
| `:oracle` | Batch-oracle differential tester over the operator algebra (ORA1, epic `computenet-4ru`). `civictech.oracle.bind.OperatorCatalog` binds a catalog id to a kernel `CellFactory` and an independent `ReferenceOp` *together* — half a binding fails at registration time naming the id — and `ShapeRule`/`ElementShape` state an operator's ports as data, so a newly registered operator reaches the generator without a generator edit. `civictech.oracle.model` may reference value/key/delta types but no `civictech.cell.data.op` type: that independence is what makes the oracle a check on the implementation rather than a second copy of it. Lives in `src/main` like `:testkit`; `gen`/`run`/`shrink`/`corpus` are placeholders owned by later ORA1 features. Deliberately a separate module rather than part of `:concord` (ORA1 D1): the oracle is kernel-coupled by construction, and §5's rule that only `civictech.concord.driver.kernel` may import `civictech.cell.*` is what folding it in would cost. | `api(:kernel)`, `api(:testkit)`. The `:kernel` edge runs both ways but in different scopes — `:kernel`'s **test** source set takes `testImplementation(project(":oracle"))`, which is the point of the module (a consumer reaches it through a plain project dep and nothing else), while nothing on `:kernel`'s main classpath knows it exists. Must not depend on `:concord`, `:wire`, `:inspect` or `:demo:*`; its own `ModuleDependencyTest` enforces that against both the build file and the runtime classpath |
| `:query` | Compiles non-recursive Datalog/relational queries to a kernel `civictech.cell.graph.GraphSpec` (QRY1, epic `computenet-cab`). This module skeleton lands the schema catalog (`civictech.query.schema`: `RelationSchema`, `Attribute`, `AttrType`, `Catalog`), the query AST (`civictech.query.ast`), and the `CompileResult`/`RejectionCode` diagnostic shape (`civictech.query.diag`) that later QRY1 features fill in — parser, planner, lowering to `GraphSpec`. Authors no cell of its own (`[QRY1-LOWER-03]`); `civictech.query.ref` imports no `civictech.cell.data`/`.data.op` type (`[QRY1-ORA-03]`), keeping the module a check on `:kernel`'s operator algebra rather than a copy of it, the same independence `:oracle` keeps from `civictech.cell.data.op`. Lives in `src/main` like `:testkit`/`:oracle` so a consumer reaches it from its own main source set through a plain `implementation(project(":query"))` (`[QRY1-API-01]`); no consumer exists yet, so the property is proven by module shape and `ModuleDependencyTest`, not by a live caller. | `api(:kernel)`; `testImplementation(:testkit)`, `testImplementation(:oracle)` — `:oracle` is `:query`'s own test-time differential harness, not a main-scope dependency. Must not depend on `:concord`, `:wire`, `:inspect` or `:demo:*`; its own `ModuleDependencyTest` enforces that, plus the `:oracle`-is-test-scope-only and no-`ksp-cell` shape, against both the build file and the runtime classpath |
| `:wire` | The one concrete transport: `WsTransport` over Java-WebSocket. Another transport = another small module behind the same kernel bridge cells. | `:kernel`, Java-WebSocket |
| `:loader` | Dynamic jar loading (JAR1, epic `computenet-051`): one `ModuleClassLoader` per loaded jar, an explicit and enumerable shared-prefix set delegated parent-first, everything else child-first, and rejection of a jar that smuggles a class under a shared prefix. Sits **above** the runtime — `:kernel` and `:concord` must not depend on it (`:kernel` would be embedding the loader inside the thing being loaded into; `:concord` would lose the implementation-neutrality §5 reserves it). `civictech.loader.ModuleDependencyTest` enforces both directions: `:loader`'s own forbidden set against its build file and runtime classpath, and the absence of a `:loader` edge in `kernel/build.gradle.kts` and `concord/build.gradle.kts`. | `api(:nature)`, `implementation(:kernel)`, `testImplementation(:testkit)` |
| `:loader:fixtures:*` | The module jars `:loader`'s tests load. Ordinary subprojects of the main build (not an included build — `ksp-cell`'s `ksp(project(":gen"))` does not resolve across that boundary without dependency substitution), each built by the real KSP pipeline so its `META-INF/services/civictech.nature.ContractModule` entry is generator output rather than a checked-in file (epic risk 051-R7; `civictech.loader.FixtureJarsTest` pins it from both sides). `:loader:fixtures:valid-basic` is the well-formed baseline (one `@Contract`, one cell); `:loader:fixtures:no-attrs` is that shape with no module manifest attributes (ERR-02); `:loader:fixtures:empty-module` declares manifest attributes but contributes no `ContractModule` (DISC-05; plain `kotlin-jvm`, no KSP) and its version string is a deliberately non-version string recorded verbatim (DISC-04); `:loader:fixtures:util-a` and `:loader:fixtures:util-b` each bundle their own build of the non-shared `com.example.Util` with observably different behaviour; `:loader:fixtures:smuggler` bundles a class named `civictech.cell.Cell` and is one of the fixtures on plain `kotlin-jvm` — it carries no contract, because the rejection it exercises is at classloader level; `:loader:fixtures:throwing-provider` is a valid contract plus a hand-written (not generator-emitted) `META-INF/services/civictech.cell.wire.WireSerializers` entry whose provider throws at construction, the atomicity probe for ERR-03; `:loader:fixtures:removed-api` is a `compileOnly`-only helper (`civictech.nature.removed.RemovedBase`) — no manifest attributes, never itself loaded — consumed by `:loader:fixtures:missing-shared-type`, whose cell extends that type without it landing in the built jar, so resolving the cell inside a `ModuleClassLoader` fails `NoClassDefFoundError` (ERR-04/B12); `:loader:fixtures:doctored-nature` is a real generated `ContractTable_<hash>` delegated to by a hand-written `DoctoredContractModule` that swaps one `PortDescriptor`'s `natures` for a non-default value no annotation could produce, with the jar's services entry rebuilt in the `jar` task's `doLast` to name it (B2's anti-reflection tripwire); `:loader:fixtures:colliding-contract` reuses `:loader:fixtures:valid-basic`'s `GreetingApi` FQN verbatim (`contractId` is `StableHash.of(fqn)`, so an identical FQN is the only way to force a same-`contractId` collision) with a different method shape, so `ModuleRegistration.register` refuses it as a `CONTRACT_ID` conflict — the [JAR1-ERR-05] registration-refusal arm through `ModuleLoader.load` (computenet-9fqe); `:loader:fixtures:wire-delta` carries no `@Contract`/`Cell` at all — plain `kotlin-jvm` plus the `kotlinx-serialization` compiler plugin — and contributes only a hand-written (not generator-emitted, for the same reason `throwing-provider`'s is) `META-INF/services/civictech.cell.wire.WireSerializers` entry naming a table for its own `@Serializable` delta type, the jar-loaded B13 end-to-end ([JAR1-REG-08], computenet-051.6.4); `:loader:fixtures:flow` (computenet-051.5.2) is the first *linkable* fixture — every fixture above it is deliberately portless — carrying `FlowSetCell` (SPAWN-01/02/03) and `FlowPromotionCandidateCell` (SPAWN-04/B14), both extending kernel's own generated `civictech.cell.data.SetCellBase` (rather than `SetCell` itself, which is `final`) so their `inlet`/`outlet` ports are contract-identical to `SetCell<String>`'s, built from the same shared `civictech.cell.` contract and payload types; the candidate differs from the base cell only in observable behaviour (upper-casing elements before folding them), so a `Promotion.promote` swap between the two is detectable by content, not by port shape. Each loadable fixture's jar path reaches `:loader`'s tests as a `loader.fixture.*` system property wired in `loader/build.gradle.kts`. A later feature of the epic still owes the byte-equal-shared-contract fixture. | `:kernel`, `ksp(:gen)` (processor-time only); `smuggler`, `empty-module`, `removed-api`: none |
| `:identity` | JDK-only Ed25519 keypairs, a fail-closed file-backed key store with machine-distinguishable refusal reasons (`WORLD_READABLE`, `MALFORMED`, `UNSUPPORTED`, `KEYPAIR_MISMATCH`, `INCOMPLETE_PAIR`, `NO_POSIX_PERMISSIONS`), and key-derived `KeyId` fingerprints; a peer's `PeerId` resolves through the kernel's `PeerIdentityBinding` seam (DSC4 interim, computenet-376c); implements the kernel's `SignatureVerifier` seam (DSC1, epic `computenet-ssa`). JDK-only — no third-party crypto. | `api(:kernel)`; `:kernel` must not depend on it |
| `:iroh` | Wraps the iroh sidecar crate at `iroh/sidecar/` — dial/accept by NodeId, length-prefixed frames on one bi-directional QUIC stream per peer link (DSC0, epic `computenet-egl`). A Kotlin/JVM module (`buildsrc.convention.kotlin-jvm`) whose `civictech.iroh` package is the JVM half of the sidecar's local-socket protocol (`iroh/sidecar/PROTOCOL.md`, the normative contract): `SidecarProtocol`/`SidecarCodec` are the pure, IO-free codec of all thirteen message kinds — malformed input is a typed `Decoded.Malformed`, never a thrown decode; `SidecarProcess` spawns the binary and reads §1's single handshake line; `SidecarClient` owns the loopback socket, runs one reader thread, and offers request/reply for the control verbs plus per-link send with exactly-once `LINK_DOWN`. Both `DATA` directions build their header at one site in the codec, so `computenet-ey4v`'s pending refusal-contract decision has a single place to land. Cargo tasks (`cargoBuild`, `cargoTest`) are registered — and wired into `build`/`check` — only behind the `-Piroh.enabled=true` project property, which also sets the `iroh.sidecar.binary` system property on the module's `Test` tasks; that property is the only channel by which a test locates the sidecar, so the default build and CI stay pure-JVM with no Rust toolchain required and every sidecar-backed test reports SKIPPED there (the codec tests run unconditionally). Boundary admission over this transport is a **public-key allowlist**: `IrohTransport.Session` is admitted on the `KeyId` fingerprinted from the connection's own iroh NodeId (`fingerprint(Ed25519.publicKeyFromRaw(remoteNodeId))`), never on a hello token, and the identity it stamps is resolved through the kernel's single `PeerIdentityBinding` seam (`computenet-egl.3`, on `computenet-376c`'s vocabulary); the hello it sends carries no name, and a hello that asserts one is refused `ID_MISMATCH` unless it matches. | `implementation(:kernel)`, `implementation(:identity)`, `testImplementation(:testkit)`; `:kernel` must not depend on it. Rust deps pinned in `iroh/sidecar/Cargo.toml` |
| `:concord` | Executable specification / conformance suite (see §5). | `:kernel`, kotlinx-serialization; kaml (test) |
| `:demo:shell` | Shared JDK `httpserver` + SSE shell (`DemoShell`, `demoPort`) used by every runnable demo, and also consumed by `:inspect` (not itself a demo). `DemoShell`'s API takes no cell-model type today, so it has no `:kernel` dependency. | — |
| `:demo:*` (10 apps) | Demo applications (see §6). `:demo:shopping`, `:demo:exchange` and `:demo:beadsmirror` use `:wire` — beadsmirror's dependency is opt-in only, for its two-node gossip mode (§6), and it additionally depends on `:iroh` for the second binding of the same `MirrorTransport` seam (`IrohMirrorTransport`, `computenet-egl.4.1` — pure-JVM on the default path, since `:iroh`'s cargo tasks exist only under `-Piroh.enabled`); `:demo:shopping` and `:demo:skillmatch` additionally depend on `:inspect` (opt-in, `--inspect-port`); only `:demo:backlog-triage` defines its own KSP cell (`RatingCell`, `@CellBase` — T09 §C; `agora` annotates nothing and dropped the `ksp-cell` convention plugin accordingly); only `:demo:exchange` needs `:nature` (it asserts composed manifests); `:demo:allocator-observe` is the newest leaf, ingesting a socaity-owned JSONL spend log (epic `computenet-fpml`). | `:kernel`, `:demo:shell`, + per-demo extras |
| `:inspect` | The Inspector backend — a read-only HTTP/SSE view of a host process's live dataflow graph (`doc/spec/90-roadmap/97-inspector-plan/`, all six milestones M0–M5 merged). Reuses `:demo:shell`'s JDK-`httpserver`/SSE framing rather than duplicating it; adds no third-party dependency beyond kotlinx.serialization. Its frontend `inspect/ui/` (SolidJS + Vite + TypeScript) is npm-only and deliberately not wired into Gradle — same decision as `demo/agora/ui`. Required five kernel accessors added specifically for it: `ManagedHost.outletAt`, `ManagedHost.snapshotOf`, `ManagedHost.isDrained`, `ManagedHost.isSuspended`, `LocationRegistry.describe` — rationale in `doc/spec/90-roadmap/97-inspector-plan/90-progress-log.md`'s orchestrator closing note. | `:kernel`, `:demo:shell`, kotlinx.serialization |

Non-module directories: `buildSrc/` (two convention plugins —
`buildsrc.convention.kotlin-jvm`: JDK 21 toolchain, JUnit platform, shared test
stack (kotest-assertions, JUnit, kotlin-test), test heap 2g / forkEvery 80
(bounded because `ProtocolSupport` keys ports in a JVM-global map), and a
5-minute-per-test-method timeout backstop; `buildsrc.convention.ksp-cell`:
the KSP plugin + `ksp(project(":gen"))` (processor-time only — no
`implementation` dependency, T09 §A) + the generated-source dir, for
cell-authoring modules (`:kernel`, `:demo:backlog-triage`, and the
JAR1 fixtures `:loader:fixtures:valid-basic`, `:loader:fixtures:util-a`,
`:loader:fixtures:util-b`)), `scripts/`
(`stage-preview.sh`, `plan-orchestrator/`), `backlog/` (idea inbox, one file
per prospective feature), `bugs/` (fixed-defect reports), `doc/` (see §7),
`legacy/` and `runtime/` (**untracked, sources deleted — only stale build
output; ignore them**).

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
  with tier ADT `PolicyTier { ADMIT, ALIGN, ACTIVATE }`, cycles
  (`FeedbackInlet`, `CycleHead`).
- `.link` — edge semantics: `Link` ADT with `LinkRole { Consume, Observe }`,
  handshake + nature reconciliation (`LinkResult.Rejected` is returned, never
  thrown), `LinkPolicy`, `Interest`/`Scoped` (the one knob that makes
  replication vs partitioning vs sharded replication), `CatchUp`, identity.
- `.protocol` — generic protocol bus: `Protocols`, `EdgeOpen`/`EdgeClose`,
  `TopologyOrderProtocol`, `StateRequestProtocol`, `RetainedFrontiers`.
- `.proxy` — JDK dynamic-proxy toolkit + `Invocation` types +
  handler behaviors (`Buffering`, `Callback`, `NoOp`).

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
  fold), `GlitchFreeCell`, `ReplicaFrontier`, `ReplicaQuorum` (the R13/PN-19/
  FU-2 cross-replica settlement predicate; `Replication.replicaFrontier` is a
  one-line factory over it), `CausalStability` (the `[42-WM-05]` stability
  read: pointwise MIN over every open membership row, a GC/compaction trigger
  rather than a per-wave predicate).

**Execution and operations**

- `.control` — operations plane: attention (`Attention`, `AttentionScheduler`,
  `AttentionPolicy`, bands, aggregation), `Magnitude`, `AbsorbAck`,
  `ParkQueue`, progress (`Progress`, `VersionMinter`), suspension
  (`StallNotice`, `SuspensionProtocol`).
- `.host` — hosted execution: `ManagedHost` (the largest file; a host is itself
  a `Cell` with `managementInlet`/`routerInlet`), `LinkAdmission` (cycle/
  headedness/damping-witness admission + topology recording behind
  `ManagedHost.connect`), schedulers (`VirtualThreadScheduler` 🔵,
  `CoroutineScheduler` 🟣, `SimulationController` deterministic),
  `LocationRegistry` (`Local`/`Remote`, park-and-replay),
  `TopologyIndex`/`TopologyWalks`, `IntakeControl`/`IntakeSaturation`,
  `HostDurability`, `KeyedCells` (durable per-key families), supervision, dead
  letters (sanitized — no live `Owned`/`Leased` escapes), `CellError`,
  remoting proxies (`HostProxy`, `HostedCellProxy`, `RoutedInlet`), `TypedLink`.
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
  (`LeaderMark`), `InstanceSet`.
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
  { FLATTEN, MEDIATE }` (REMINT: spec-only, no code — see 93),
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
stall) → ALIGN (`WaveFrontier` folds `EdgeOpen`/`EdgeClose` into a completeness
frontier, releases waves in per-source order) → ACTIVATE (cold-park buffering).
Backpressure is *not* a tier of this chain — it lives in
`IntakeControl`/`ParkQueue`; spec 10/12 §Policies still specifies a GATE tier,
which has no code and can return with its first real user.

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

**Architecture ratchets (T10).** Three of this document's boundary claims are
executable, not just prose, each wired into the normal test task so it gates
`./gradlew test`/`check` the same way `concordanceGate` and `docLints` do:
`NeutralityGateTest` (`concord/src/test/kotlin/civictech/concord/provenance/`)
fails the build if any `:concord` file outside `civictech.concord.driver.kernel`
imports `civictech.cell.*`, enforcing the L3 rule above; `DemoSurfaceAllowlistTest`
(`kernel/src/test/kotlin/civictech/cell/architecture/`) fails if a demo's
`src/main` reaches past the allowed `civictech.cell` surface (root vocabulary,
`.host`/`.port`/`.graph`/`.data*`/`.observe`/`.link`/`.wire`/`.consistency`/
`.control`/`.durability`, plus `civictech.testkit`); `ArchitectureRatchetTest`
(same directory) pins the current `civictech.cell` package→package import edge
set against a checked-in baseline
(`kernel/src/test/resources/architecture/package-edges.txt`) — see
`90/91` gap `G-63` (all 20 non-leaf `civictech.cell.*` packages form one SCC;
this is a ratchet, not a claim of acyclicity) — and fails on any *new* edge, while a baseline
edge no longer present in code is warn-only (delete the stale line by hand;
the ratchet only ever tightens). To widen any of the three deliberately,
change the allowlist/baseline in the same PR that adds the edge/import,
citing why.

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
- `:demo:dialogue` — argumentation extraction from recorded dialogue
  transcripts (AGO1, epic `computenet-2aw`); depends on `:kernel`,
  `:demo:shell` and `:demo:agora` (reuses agora's claim/edge vocabulary
  rather than minting a parallel one).
- `:demo:beadsmirror` — mirrors a `bd`/Dolt-backed beads workspace: polls the
  workspace's Dolt commit graph (`dolt_diff_issues`/`dolt_diff_dependencies` in
  `dolt_log` order), projects each change through kernel cells into a
  materialized composite-key `OrMapCell` fold, and serves the fold over
  `:demo:shell`'s HTTP/SSE plumbing (`BeadsMirrorAppKt --workspace <path>`).
  `:wire` is an opt-in dependency for its two-node mode only
  (`--rig`/`--listen`/`--peer`, `MirrorPeering`): the projector's two replica
  cells then gossip their deltas over an injected transport seam
  (`MirrorTransport`), whose only binding — `WsMirrorTransport`, in
  `MirrorTransport.kt`, the module's one file naming a `:wire` type — carries
  them over a real WebSocket. `MirrorPeering` itself names no socket type, and
  a solo run loads none of the binding. CI asserts its e2e evidence ran: `TwoNodeRigTest` in
  `build-test-fast` and, tagged `@Tag("multi-jvm")`, `TwoJvmMirrorTest` in
  `build-test-serial`. Most of the rest of its suite drives a real `bd`/`dolt`
  scratch workspace (`BdScratchWorkspace`) and self-skips, visibly, if those
  binaries are absent from PATH — CI installs both specifically so the
  real-workspace tests run rather than skip.

- `:demo:allocator-observe` — spend-log observability for the socaity
  allocator MVP (ALOB, epic `computenet-fpml`): ingests the socaity-owned
  JSONL spend log (schema-versioned `{v, project, machine, work_item,
  started, ended}` records, one per worker session) via a total per-line
  classifier (`Valid`/`Malformed`/`UnknownVersion`) into the v1 `SpendRecord`
  model. Feature `computenet-fpml.1` lands the whole ingest half:
  `SpendLogTailReader` reads only new complete lines from a persisted
  byte-offset checkpoint (`OffsetCheckpoint`, atomic-move writes, persisted
  only *after* the batch reaches its consumer) and re-baselines from offset 0
  when length or head fingerprint says the log was truncated or replaced;
  `SpendLogIngester` folds the classified records into a kernel `SetCell`
  keyed by the full record tuple, reconciling rather than appending on a
  re-baseline, and exposes monotonic per-reason failure counts
  (`SpendIngestFailures`) so no bad line is silently dropped. The checkpoint
  and re-baseline idioms are copied from `:demo:beadsmirror` by example, not
  imported — the epic defers a shared connector SPI to CON2
  (`computenet-rrf`). Still to come: allocation declarations
  (`computenet-fpml.2`), the derived R5/R6 views (`computenet-fpml.3`),
  HTTP/SSE serving over `:demo:shell` (`computenet-fpml.4`, which is why the
  module has no `application` block yet), and the differential oracle against
  socaity's replay script (`computenet-fpml.5` — socaity has implemented
  neither the log nor the script yet, so that comparison is external evidence
  this module cannot produce on its own).

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
- `doc/iroh-adoption.md` — DSC0 (iroh) adoption-residue finding: measured
  cross-compilation record, crate layout as consumed, DSC2 residual scope,
  relay hosting policy.
- `backlog/` — idea inbox (some files marked IMPLEMENTED/absorbed).

Snapshots and partly-executed plans (read with their dates in mind):
`doc/FEATURE-STATUS.md` (shipped-vs-claimed survey, 2026-07-25),
`doc/CONCORD-PLAN.md` (§1–§2 still the concord reference; milestones are
history; W5 deferred), `doc/ksp-dx-catalog.md` (per-phase annotations
authoritative; phase 5 not landed).

`doc/archive/{runs,frontend,adr}/` — historical material, not guidance; each
bucket carries its own README (`runs/`: completed COMPOSITION-*/PERNODE-*/
CHANGELOG-*/RESTRUCTURE-PLAN/ORCHESTRATION records, `PERNODE-FOLLOWUP-TICKETS.md`
excepted — it still has open FU items and stays at `doc/`; `frontend/`: the
agora-UI/design research cluster, broken paths and false grounding claims;
`adr/`: the pre-spec ADRs, consolidated into the spec — `doc/adr/ADR - Adapter
Synthesis.md` is the one live exception, PROPOSED not decided, and stays put).
`doc/research/incremental-engines/` is current background feeding plan 96.
