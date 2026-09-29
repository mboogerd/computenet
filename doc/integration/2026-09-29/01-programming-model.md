# 01 Programming model assessment (cells, ports, links, construction, KSP)

Pinned at main cbc7bb52 (2026-09-29). Paths relative to repo root; kernel = kernel/src/main/kotlin/civictech/cell.

## Summary
1. Each concept exists and works on its own: cells (registerPort + KSP scan), typed ports, negotiated links, the graph DSL/GraphSpec, generated `<Cell>Ports` ids. But there is **no single way to wire a graph**. There are at least six wiring paths, and they differ in *semantics*, not just in ergonomics.
2. The admitted path (`host.connect`/DSL `link`) records topology and runs cycle admission, but co-hosted it fuses hops into synchronous calls, which bypasses the scheduler. The routed path (`routeTo`/`streamTo`) stays staged but is an **Observe-role** link that skips admission, topology recording and the wave frontier. No path has all four properties.
3. The richest demos (agora, deliberate, and dialogue through AgoraService) chose the routed path for scheduling. So their graphs are outside glitch-free gating, outside cycle admission, and outside the inspector's topology view.
4. GraphSpec (graphs-as-data) only knows spawn, connect and instance-set. Replication, partitioning (PartitionedCell), shadow/promotion, KeyedCells families, routed links and unlink all happen through imperative host-level calls. Demos throw GraphSpec away and hand-roll structure logs (5 copies).
5. Feature primitives with **zero** demo users: PartitionedCell, InstanceSetStep, spawnBound/applyRemote, Shadow/Promotion, membrane CompositeCell/MediateProxy. Exchange (the "composition probe") hand-rolls shards and mesh replication instead.

## Concept status table
| concept | spec § | implementation | canonical? | used by demos | notes |
|---|---|---|---|---|---|
| Cell declaration | 11 §Kernel interface; 51 §Authoring | `Cell` + `registerPort("x", FanInlet.create())`; KSP scans all cells (gen/.../ContractProcessor.kt:237-306) | ~yes (one idiom); `@CellBase` optional (kernel 23 uses; demos: alignment 4, backlog-triage 6) | all | ports are named by string literals, then mirrored by generated `<Cell>Ports` (ContractProcessor.kt:379-389,685-704) |
| Typed port ids | 12 §Port identity; G-17 | `InletId`/`OutletId` (graph/PortIds.kt:12-25), generated per cell | yes | agora, deliberate (routed); few use typed `connect` | there are typed overloads for both the admitted and the routed paths |
| Link: admitted | 13 §Explicit | `HostManagementApi.connect` → `LinkAdmission.connect` (host/ManagedHost.kt:1807; host/LinkAdmission.kt:41-70): cycle admit + `topology.link(edge)` | intended canonical | dialogue GraphApplier.kt:199, alignment, tiering, shopping, backlog-triage | co-hosted it **fuses into a synchronous call** that bypasses scheduler/attention (backlog/agora-scheduler-staged-links.md:10-20) |
| Link: typed instance | 13 | `HostManagementApi.link(Subscribe,Serve)` (host/TypedLink.kt:38); `GraphBuilder.link` (graph/GraphDsl.kt:472) | alias of admitted | shopping, exchange, tiering | lowers to ConnectStep |
| Link: routed | 13 §Ad hoc | `FanOutlet.routeTo(registry, ref, InletId)` = `streamTo(registry.inlet(..))` (host/RoutedInlet.kt:187-191); `LinkRole.Observe` (port/StreamTo.kt:25-48) | **parallel path** | agora (AgoraService.kt:182-183), deliberate (CredenceGraph.kt:261-279), exchange writers (exchange/Main.kt KeyedCells factory) | no LinkAdmission, no TopologyIndex record, no EdgeClose (StreamTo.kt:55-110), excluded from WaveFrontier (doc/demo-findings.md:1184-1188) |
| Link: raw subscribe | — | `Subscribe.subscribe(Use)` (port/Subscribe.kt:16) | parallel (test idiom) | beadsmirror (2), social (1) | skips on-link multicast, so no late-join catch-up (demo-findings F-10, :213-237) |
| Link: connect to Use | 13 | `connect(ref,outlet,Use<*>)` (ManagedHost.kt:1810) | parallel | — | no admission and no topology |
| Graph DSL / GraphSpec | 51 §Graph construction DSL (G-30) | `graph{}`/`graphOf{}`, `GraphSpec(SpawnStep, ConnectStep, InstanceSetStep)` (graph/GraphDsl.kt:71-160, 232-514) | canonical *for spawn+connect only* | dialogue pipeline (DialoguePipeline.kt:305, spec discarded `val (refs, _)`), skillmatch, slotfinder, social, agora/deliberate `graph(` = read-side name clash only | `UnlinkStep` unbuilt (51-construction.md:5,62); placement "driver unbuilt" (GraphDsl.kt:97-98); used by :query (query/.../CompiledQuery.kt:32) |
| Instance sets (PN-13) | 51 §InstanceSetStep; 42 | `InstanceSetStep`/`InstanceSpec` (GraphDsl.kt:84-200) | yes | **none** (kernel/concord only) | exchange hand-rolls shards with `Interest.Slots.forShard` and N ManagedHosts (exchange/Main.kt:113-124) |
| Keyed families | 24/31 | `KeyedCells` (host/KeyedCells.kt:40-104): `host.spawn` + its own fsync'd key log | parallel to GraphSpec | social (43 refs), exchange, shopping, tiering, dialogue | not a GraphSpec step; runs its own structure log |
| Partitioning | 42 | `PartitionedCell`/`ShardCell` (partition/) | exists | **none** | — |
| Replication | 42 | `Replication.replicate(cell, host)` (replication/Replication.kt:440) — an imperative host call | yes | beadsmirror, shopping, tiering | not expressible in GraphSpec |
| Evolution | 53 | `Shadow.spawn(host,cell)`, `Promotion` (evolve/Evolution.kt:53-314); `IdentityBinding.NewInstanceOf` | exists | **none** | WKB2 epic (computenet-7p8) open |
| Membrane composition | 11 §Membranes; 51 G-9/G-52 | `membrane/CompositeCell`, `MediateProxy`, `BoundaryPolicy` | exists | **none** (only inspect/wire tests); shopping uses observe `AlignedCompositeCell` | `parent` recorded but not enforced (51-construction.md:77-80, G-9 unbuilt) |
| Invocation / proxies | 14 | `HostedCellProxy`, `RoutedPropagate` (RoutedInlet.kt:37-60), `registry.inlet` | two front doors, same queue | agora, deliberate (ingress) | fine: `inlet` is documented as byte-identical to the proxy path |
| Lifecycle / structure recovery | 15; 93 I-7 | per-port journals (ManagedHost.kt:991-995 journalTee); **topology not journaled** | gap | 5 hand-rolled structure logs (agora, dialogue×2, backlog-triage, KeyedCells, deliberate) | computenet-8xstm |
| Module loading | 51/JAR | :loader (ModuleLoader.kt) | — | none | JAR3 GraphSpec deploy open (computenet-ask) |

## Integration gaps
1. **G1: No link mode is both staged and admitted/topological (glitch-free-gated).** Severity **H**.
   - Evidence: LinkAdmission.kt:57-68 vs RoutedInlet.kt:187-191 + StreamTo.kt:48 (`LinkRole.Observe`). demo-findings.md:1184-1188 shows that a streamTo-fed GlitchFreeCell "gates nothing". backlog/agora-scheduler-staged-links.md:10-20 shows DSL links fuse and bypass magnitude scheduling. The kernel already has the needed mechanism (the FeedbackInlet fusion barrier) but does not expose it.
   - Why it blocks an all-features demo: a demo cannot have attention/magnitude scheduling (agora's core feature), glitch-free completeness, cycle admission and inspector-visible topology on the same edges.
   - Tracking: partly. backlog item only is **untracked** in bd. computenet-g92i covers only EdgeClose on the bypass path. backlog/agora-dynamic-cycle-head-admission.md is also untracked.
2. **G2: GraphSpec is not the universal construction record.** Severity **H**.
   - Evidence: GraphDsl.kt:71-160 has only 3 step kinds. There is no step for routed links, unlink, KeyedCells, replicate, partition, shadow, journal binding or membrane parent-enforcement. Dialogue discards its spec (DialoguePipeline.kt:305). Five structure logs are hand-rolled.
   - Why it blocks: evolution (WKB2 "GraphSpec apply via Promotion"), deploy (JAR3), recovery of structure (I-7 topology), inspector replay and :query all assume graphs-as-data. A graph built from routeTo, KeyedCells or replicate calls cannot be re-applied, promoted or recovered as a unit.
   - Tracking: computenet-8xstm (structure log, interim), computenet-7p8, computenet-ask. UnlinkStep and "routed/keyed/replicated steps" are **untracked**.
3. **G3: Feature primitives are not composed through construction.** Severity **H**.
   - Evidence: PartitionedCell, InstanceSetStep, spawnBound, Shadow/Promotion and membrane CompositeCell have zero demo callers (grep over demo/*/src/main). Exchange, the declared composition gate, hand-rolls shards and mesh replication (exchange/Main.kt:111-145) and wires writers with streamTo.
   - Why it blocks: the "all features together" claim has never been exercised through the intended constructors. Composition bugs between e.g. InstanceSetStep + Replication + durability are latent.
   - Tracking: **untracked** as a composition item. Per-feature epics exist.
4. **G4: Too many wiring front doors with silently different guarantees.** Six paths: connect-by-name, connect-to-Use, link(Subscribe,Serve), DSL connect/link, routeTo/streamTo, and raw subscribe. Severity **M**.
   - Evidence: ManagedHost.kt:1807-1816, TypedLink.kt:38, GraphDsl.kt:449-472, RoutedInlet.kt:187, Subscribe.kt:16.
   - The raw-subscribe path loses catch-up (F-10). The connect-to-Use path loses admission. Nothing in the types tells the author which guarantees they gave up.
   - Why it blocks: every demo picks a different subset, so one app cannot reuse another's subgraph without changing its semantics. Dialogue embedding AgoraService mixes admitted and routed edges in one host.
   - Tracking: **untracked**.
5. **G5: Membrane/organelle composition not enforced and unused.** Severity **M**.
   - Evidence: `parent` is only bookkept (51-construction.md:77-80). G-9 and G-52 are unbuilt. No demo uses membrane.CompositeCell.
   - Why it blocks: without an enforced boundary, a reusable sub-graph (e.g. the agora credence engine inside dialogue) cannot be exposed as one cell to partition, replicate, evolve or inspect.
   - Tracking: SOC2 mentions "moderation membranes" (computenet-a3n). G-9 itself is **untracked** as a bead.
6. **G6: Port names are string literals duplicated in registerPort + generated ids.** Severity **L**.
   - Evidence: agora ClaimCell.kt:41-43 vs the generated ClaimCellPorts.
   - This is a KSP-checked mirror, so the risk is low. The only gap is that `@CellBase`/descriptor usage is uneven across demos.
   - Tracking: untracked.

## Bridging proposals (ordered)
1. **(M) One link primitive with orthogonal options.** Add `LinkOptions(role = Consume|Observe, staged = true|false)` to `connect`/DSL `link`/`routeTo`, all going through LinkAdmission (admission + TopologyIndex + EdgeOpen/EdgeClose). Expose the existing fusion barrier as `staged = true` (backlog/agora-scheduler-staged-links.md). Make `routeTo` a thin alias that records topology and defaults to Consume when the target is a hosted port. Migrate agora and deliberate to it. Exit test: a GlitchFreeCell fed by staged links gates, and the inspector shows agora edges.
2. **(S) Deprecate raw `subscribe` and `connect(ref,outlet,Use)` in app code.** Add an arch test barring them from demo/*/src/main, or route them through the on-link multicast (F-10).
3. **(M) Extend GraphSpec vocabulary: "parameters, not verbs".** Add `UnlinkStep`, plus a link-options field on ConnectStep, `KeyedFamilyStep` (KeyedCells as a spec step), and `replicate`/`partition` as SpawnStep/InstanceSetStep parameters that lower to existing management invocations.
4. **(M) Journal the GraphSpec as the structure log** (the I-7 topology journal, computenet-8xstm). Replace the 5 hand-rolled logs. Recovery = re-apply the spec (Exact identities), then replay port journals.
5. **(L) Composition-gate demo rebuilt on intended constructors.** Rework exchange, or add a new "civic" demo that embeds agora's credence engine, using: `graph{}` only; InstanceSetStep for shards; Replication via spec; durable journals by `journalId`; Shadow/Promotion of one cell version (WKB2); membrane-wrapped sub-graph; inspector attached. Add an arch test that the demo contains no `streamTo`/`subscribe`/direct `host.spawn`.
6. **(L) Enforce membranes (G-9/G-52) so a sub-graph is one addressable unit** for the placement, replication and evolution parameters in step 3.

## Raw findings log
- F1 Wiring paths (>=6): HostManagementApi.connect(ref,"outlet",ref,"inlet") -> LinkAdmission (cycle admit + topology record) [kernel/.../host/ManagedHost.kt:1807, LinkAdmission.kt:41-68]; connect(ref,outlet,Use<*>) (no admission, ManagedHost.kt:1810); HostManagementApi.link(Subscribe,Serve) [host/TypedLink.kt:38]; GraphBuilder.connect/link over strings [graph/GraphDsl.kt:449,472] -> GraphSpec ConnectStep(strings) [GraphDsl.kt:82]; FanOutlet.routeTo(registry, ref, InletId) = streamTo(registry.inlet(..)) [host/RoutedInlet.kt:187-191] - the "bypass" path (port/StreamTo.kt:55-110 comments: no toPort, no EdgeClose, fromPort null); raw Subscribe.subscribe(Use) [port/Subscribe.kt:16].
- F2 routeTo/streamTo does NOT go through LinkAdmission: no cycle admission, no TopologyIndex record (topology?.link(edge) only in LinkAdmission.kt:67). Agora (AgoraService.kt:182-183) and deliberate (CredenceGraph.kt:261-279) wire their entire argument graphs this way.
- F3 streamTo/routeTo installs an Observe-role link; WaveFrontier excludes Observe edges, so a routeTo-fed GlitchFreeCell gates nothing (doc/demo-findings.md:1184-1188). => agora + deliberate argument graphs are outside glitch-free completeness by construction. host.connect (LinkAdmission.kt:41-70) = negotiated linkTo, records TopologyLink; routeTo does not -> invisible to TopologyIndex-driven inspector (inspect/.../Graphs.kt builds components from recorded links).
- F4 raw outlet.subscribe(inlet) skips on-link multicast -> no late-join catch-up (demo-findings F-10, :213-237), used by beadsmirror/social.
- F5 GraphSpec vocabulary = SpawnStep/ConnectStep/InstanceSetStep only (graph/GraphDsl.kt:71-160); UnlinkStep unbuilt (51-construction.md:5,62); no step for routed links, KeyedCells families, PartitionedCell, Replication.replicate, Shadow.spawn, durability binding (only InstanceSpec.journalId hint; placement "driver unbuilt" GraphDsl.kt:97-98).
- F6 Typed port ids (generated <Cell>Ports, gen ContractProcessor.kt:379-389,685) exist for BOTH paths (graph/PortIds.kt:18-25 typed connect; RoutedInlet.kt:136,187). Cells declared via registerPort("name", FanInlet.create()) + KSP scan; kernel 23 @CellBase, demos: only alignment(4), backlog-triage(6) use @CellBase.
