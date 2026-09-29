# 07 — Feature interactions (93 / 91 / 95)

Snapshot: main @ cbc7bb52, 2026-09-29. Read-only.

## Summary

- **The design layer is complete; integration is not.** 93 defines 27 feature nodes (N1..N27) and gives every one of the 351 unordered pairs a verdict: S (specified in a chapter), I-n (one of 28 challenges), or R (resolved by 91). No pair is blank, orthogonal or in conflict, so there are no undecided pairs *on paper*. All 28 challenges are marked `decided`.
- **Most individual I-n decisions have code behind them**: 18 of 28 have the named mechanism in `kernel/src/main`. Tests, however, almost always pair a decision with the one or two features it is literally about (wire, replication, durability, glitch-freedom). The composition-heavy nodes are:
  - PartitionedCell (N16)
  - promotion and shadow (N26/N27)
  - membranes (N7)
  - cycles (N10)
  - Owned/Leased (N14)

  Each is tested **alone or in only 1–3 files alongside a second feature**. In test files, the following pairs have **zero** co-occurrence:
  - cycles × {glitch, durability, wire, partition, replication (1)}
  - partition × {durability, ownership, attention, promotion}
  - membrane × {graph, invariants, shadow, backpressure, partition}
  - ownership × partition
- **Demos use a narrow common core.** Across 14 demo modules, none uses:
  - PartitionedCell
  - Promotion or Shadow
  - Owned/Leased payloads
  - BoundaryPolicy or membranes
  - host mobility (`drainHost`/`migrate`)
  - InvariantCell
  - color markers (`BlockingCell`/`SuspendingCell`)

  The "composition probe" `demo/exchange` hand-rolls partitioning with `GroupByCell` shards (Main.kt:114–205) instead of the `PartitionedCell` composite that I-8/I-19 decided.
- **Nothing tracks integration.** Of 33 open epics, none is an integration or composition epic. Each one extends a single slice (KE2/KE4/KE5, MEM2/GOS1, BDS*, WKB*, SOC*). Research gates (95 R1–R17) cover election, fixpoints, coupling liveness, placement and economics, and every one of them sits in a composition seam.
- 91 is partly stale. G-34, G-39, G-35, G-37, G-38 and G-13 read OPEN, yet code for SATURATED intakes, EdgeOpen/EdgeClose, the PORT_PROTOCOL wire path, `StateRequestProtocol` and baselines is on main.

## Interaction grid

Here is 93's matrix collapsed to the feature axes a demo would combine. Cell values: **T** = a decision is realized and at least 3 test files exercise both features; **t** = 1–2 co-occurring test files; **0** = decided in 93 but no test file touches both; **—** = not sampled.

The co-occurrence heuristic counts test files (kernel, wire, identity, testkit, demo, concord) that match both feature regexes. It is an upper bound on real two-feature tests.

| | REPL | DUR | GLITCH | CYCLE | PART | PROMO/SHADOW | OWN | ATTN | MEMB | MOB | WIRE | BACKP |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **REPL** (N24) | · | T 27 | T 36 | t 1 | t 1 | T 3 / t 2 | T 10 | T 3 | t 1 | T 66 | T 56 | 0 |
| **DUR** (N17) | | · | T 8 | **0** | **0** | T 16 / t 1 | T 8 | T 4 | t 1 | T 26 | T 17 | T 5 |
| **GLITCH** (N12) | | | · | **0** | t 1 | t 2 / t 1 | t 1 | T 5 | t 2 | T 37 | T 27 | t 1 |
| **CYCLE** (N10) | | | | · | **0** | **0** / t 1 | t 2 | t 1 | **0** | T 3 | **0** | **0** |
| **PART** (N16) | | | | | · | **0** / t 1 (PartitionedPromotionTest) | **0** | **0** | **0** | t 2 | t 1 | **0** |
| **OWN** (N14) | | | | | | T 5 / t 1 | · | t 2 | T 6 | T 12 | T 11 | t 2 |
| **MEMB** (N7) | | | | | | **0** / t 2 | | T 8 | · | T 5 | T 6 | **0** |
| **COLOR** (N19) | t 1 | T 5 | **0** | t 1 | **0** | **0** | **0** | t 1 | **0** | T 6 | t 2 | t 2 |
| **INV** (N26) | **0** | **0** | **0** | t 1 | **0** | t 2 | t 1 | **0** | **0** | T 3 | t 1 | **0** |

In 93's own matrix, these zero cells are decided by I-5/I-6 (the whole CYCLE row), I-8/I-19 (PART), I-10/I-28 (MEMB), I-15 (COLOR) and I-20 (OWN). They were decided, then left unexercised together.

## Decision realization table

I sampled all 28 I-n decisions at mechanism level: a key-type grep over main and test, plus I-n citation counts. Seven get deeper probes: I-8, I-11, I-12, I-13, I-16, I-19 and I-20.

Counts: **REALIZED+TESTED 15 · PARTIAL 10 · REALIZED-UNTESTED 1 · NOT-IMPLEMENTED 0 (whole) · RESEARCH-GATED residuals attached to 9.** "Tested" means tested in the decision's own feature pair. It does not mean tested in a composed graph.

| I-n | Decision | Status | Evidence |
|---|---|---|---|
| I-1 | Multiplex / metadata plane | REALIZED+TESTED (minimal) | `ProtocolSupport` in 20 main / 38 test files. PORT_PROTOCOL crosses the wire (`BridgeCells.kt`, `WireCodec.kt`, `ProtocolBridgeTest`). G-13 and G-64 (JVM-global registry) are open. |
| I-2 | logicalId identity | REALIZED+TESTED | `instancesOf` (7/12), `IdentityBinding` (15/21). The G-57 selection policy is research-gated (R9). |
| I-3 | Replication skeleton | REALIZED+TESTED | REPL×WIRE 56, REPL×DUR 27. The churn argument (G-45) is open under the MEM2 and GOS1 epics. |
| I-4 | Attention algebra | PARTIAL | `AttentionFrontier` and `VersionMinter` exist. Multi-hop notices (G-36) are open. |
| I-5 | Cycle re-origination (CycleHead) | PARTIAL | `CycleHead` in 7 main / 6 test files, but zero tests combine it with glitch, durability, wire or partition. Cross-host cycles (G-41) → R16. KE5 epic is open. |
| I-6 | Magnitude divergence guard | REALIZED+TESTED (local) | `MagnitudeSchedulingTest`, `CycleDampingAdmissionTest`. Weak-tier fixpoints → R2. |
| I-7 | Invocation journal | REALIZED+TESTED | `Journal`/`recoverFrom` in 48 test files. `DurableGlitchFreeReplayTest`. Recovery lifecycle just landed (#1165). G-59 (non-deterministic cells) is open. |
| I-8 | PartitionedCell composite | PARTIAL | `kernel/.../partition/PartitionedCell.kt` exists, but only 3 test files use it, with PART×DUR 0 and PART×REPL 1. The exchange demo bypasses it. |
| I-9 | Attention as resource lever | REALIZED+TESTED | `AttentionPolicy` (5/23). The economic layer (G-62) → R6. |
| I-10 | Membrane Flatten/Mediate | PARTIAL | `Mediate`/`Flatten` exist. `Symport` is in 2 main files and **0 tests**. G-9, G-10 and G-52 are open. Coupling liveness (G-53) → R3. |
| I-11 | Atomic multi-link swap | REALIZED+TESTED (in-process) | `Promotion.promote` state machine. `PartitionedPromotionTest`, `ReplicatedPromotionTest`, `ShadowPromotionTest`. Cross-host swap is blocked on G-48 (no reverse-topology index). |
| I-12 | Backpressure (SATURATED) | PARTIAL | `IntakeSaturation.kt`, `IntakeControl.kt`, 13 test files. BACKP×REPL 0, BACKP×WIRE 1. 91 still lists G-34 as OPEN. |
| I-13 | Topology events in the wave domain | REALIZED+TESTED | `EdgeOpen`/`EdgeClose` in 17 main files. Crossed with wire (`BridgedNatureRefusalTest`) and durability (`DurableGlitchFreeReplayTest`). G-39 in 91 looks stale. The TLA+ epic FRM1 is open. |
| I-14 | Epoch source identity | REALIZED+TESTED | `EpochTransitionFreshMintTest`. Epoch GC (G-42) is open. |
| I-15 | Colors as data | PARTIAL | `HostColor` (7/6). COLOR×{GLITCH, PART, PROMO, OWN} = 0. The placement engine (G-61) → R5. |
| I-16 | State-request protocol | REALIZED+TESTED | `protocol/StateRequestProtocol.kt`, `PullServiceRefusalTest`, `OrMapConvergenceTest`. 91's G-37 row looks stale. |
| I-17 | Non-perturbing shadow | REALIZED+TESTED | `Shadow.spawn` and `Effectful`. SHADOW×DUR 16, `EffectfulBaselineGuardTest`. |
| I-18 | Completeness when an edge will not deliver | REALIZED+TESTED | `Progress`/`Watermark` in 15 and 28 main files. G-40 and G-66 residuals remain. KE2 epic is open. |
| I-19 | Partition placement = ordinary placement | REALIZED+TESTED (thin) | One test only: `PartitionedShardsAcrossHostsTest`. |
| I-20 | Exclusive flows vs multi-consumer | PARTIAL | `Tap` has 25 test files and OWN×REPL is 10, but G-46 (exclusive payloads off the happy path) and G-47 are open. OWN×PART = 0. |
| I-21 | GraphSpec expressiveness | REALIZED+TESTED | `SpawnStep` + `IdentityBinding`. Partial-apply atomicity → R4. WKB2 epic is open. |
| I-22 | RESTART / dead-letter rollback | PARTIAL | 8 main files cite it. Precedence (G-43) is open. |
| I-23 | Frontier traversal through membranes | REALIZED-UNTESTED / decided away | 0 citations. 95 PN-16 decided that the static-link model suffices. MEMB×GLITCH = 2 test files. |
| I-24 | Baseline catch-up | REALIZED+TESTED | 12 main files, 2 concord scenarios. G-38 in 91 looks stale. |
| I-25 | Leader+follower | PARTIAL | `LeaderMark.kt`, fenced only. Election (G-44) → R1. The `SAFETY_PARK` posture was dropped (G-67). |
| I-26 | Link time / eager cells | PARTIAL | 0 citations. The admission/activation split (G-55) is open. |
| I-27 | State migration | REALIZED+TESTED | `StateMigrating`, 3 test files. |
| I-28 | Boundary security | REALIZED+TESTED (phase 1) | `BoundaryPolicy` (8/17), `WsBoundaryPolicyTest`. Revocation (G-54) is open. |

## Undecided interactions (latent holes)

93 decides every pair, so the holes are **higher-order tuples and whole-graph properties** that it never addresses:

1. **Triples and quads.** I found no decision on N16×N24×N17 (a partitioned, replicated and durable composite recovering together). The same goes for N10×N12×N22 (glitch-free cycles across hosts; only partly in R16) and N27×N24×N17 (promoting a replicated, durable cell). None of these composite scenarios has a test either.
2. **An end-to-end "all natures composed" contract.** `ComposedNatureManifestTest` declares that structural natures (glitch-free, durable, replicated, partitioned) are *never* reconciled at link time. No normative text says what guarantees hold when all four are stacked. `ExchangeCompositionExitTest` is the only empirical stand-in, and it does not use `PartitionedCell`.
3. **Economics and placement × everything** (G-61/G-62). Every interest-driven policy defers to an economic layer that is still research-gated (R5, R6) with no design.
4. **Instance-scoped registries** (G-64) × multi-host-in-one-JVM tests. Global maps make composed simulations share state.

## Open G-nn markers

- **In `kernel`/`wire` main sources** (counts are marker occurrences): G-22 39, G-23 25, G-04 18, G-25 16, G-28 15, G-46 14, G-26 13, G-59 11, G-45 11, G-35 11, G-13 10, G-51 8, G-4 8, G-29 8, G-8 7, G-58 7, G-42 7, G-40 7, G-21 7, G-19 7, G-17 7, …, 60+ distinct ids in total.
  - Zero-padded forms (G-04, G-01, G-02, G-08, G-09) are a second numbering space, or drift, alongside G-4 and G-8.
  - Many markers cite gaps 91 records as resolved (G-22, G-23, G-25, G-26, G-28). Those are provenance comments, not open work.
- **Still-open gaps that are anchored in code and cross-feature:**
  - G-46: ownership × supervision/journal/dead-letter
  - G-45: replication × membership churn
  - G-35: protocols × wire
  - G-59: durability × determinism
  - G-42: epochs × replication × checkpoints
  - G-40: glitch-freedom × effective-only emission
  - G-19: cycles × glitch
  - G-29: security × wire
  - G-13, G-58
- **Open in 91 with little or no code anchor** (design only): G-9, G-10, G-52, G-53 (membranes); G-48 (cross-host swap); G-55; G-57; G-61; G-62; G-63; G-64; G-65; G-66; G-67.

## Bridging proposals

1. **(S) Refresh 91 against main.** Close or annotate the stale rows (G-34, G-35, G-37, G-38, G-39, G-13) and normalize the zero-padded G-0n markers. This is doc maintenance only.
2. **(M) Make `demo/exchange` use the real `PartitionedCell`** instead of `GroupByCell` shards. Extend `ExchangeCompositionExitTest` to cover a crash-restart of one shard host (PART×DUR) and a replica of one shard (PART×REPL). This closes the three zero cells for PART.
3. **(M) Add a "composed natures" generative harness in testkit.** One `SimWorld` scenario would combine a CycleHead loop, a glitch-free join, a journal, a replica, a `BoundaryPolicy` crossing and a promotion, then assert invariants through `InvariantCell`. It targets the grid's zero cells, CYCLE row first.
4. **(M) Add an Owned/Leased flow to a demo.** For example, an exchange order as an `Owned` payload through a Tap, a shadow and a dead-letter path. This exercises I-20 and G-46 end to end.
5. **(L) Implement the membrane (G-52) and use it in a demo.** KSP-generate the Mediate proxy, then put agora or social behind a moderation membrane (SOC2 already plans this). Membranes are the most-decided and least-integrated node (MEMB zeros with graph, invariants, shadow, partition).
6. **(L) Cross-host promotion (G-48 reverse-topology index).** WKB2 needs it. Without it, evolution cannot compose with distribution.
7. **(S) File an integration epic** ("all-features demo readiness") that owns items 2–6. Today no open epic owns composition, so parallel slice work will keep landing side by side.
8. **(M) Instance-scoped `ProtocolSupport`/`PortRegistry` (G-64).** This is a prerequisite for honest multi-host composed simulations in one JVM.
