# 05 — Evolution, Verification & Tooling (assessor slice)

Pinned to main @ cbc7bb52 (2026-09-29). Read-only.

## Summary
1. The evolution/verification *kernel primitives* are real and well tested (Shadow, 4-phase Promotion swap incl. partitioned + replicated variants, InvariantCell, PromotionJudge, ReplicaConvergence) — but they are a **toolbox with zero production callers**: no demo, no workbench path and no runtime service composes shadow → invariant → judge → promote.
2. "Graph verified by invariants under live data" is **test-only**: InvariantCell has 0 main-source callers outside kernel; no demo deploys a live invariant cell.
3. The live-edit surface (Workbench write plane in :inspect) can spawn/link/despawn on a running shopping/skillmatch JVM, but **promotion is an explicit placeholder** (F9 open) and there is no shadow/invariant stage; the UI edit mode (F10/F11) is unbuilt.
4. Tooling is siloed: inspector runs in 2 of 14 demos and has no durability/journal awareness; :timetravel is an offline CLI used only as agora's test dep; :loader has no Gradle dependents; JAR2/JAR3/WKB3/TTD2 epics are open with 0 children.
5. Concord cannot even state this slice: chapters 51/52/53 carry **no requirement ids**, so evolution/verification contribute 0 rows to the 143-row concordance; the promotion swap is a filed `schema-gap`+`provenance-gap` dispute (B14).

## Concept status table

| Concept | Implemented? | Complete? | Canonical / parallel | Evidence |
|---|---|---|---|---|
| Shadow deployment (effect-suppressed candidate) | Yes | Core yes; "continuous production shadowing awaits a long-running runtime" (52 status) | Canonical (kernel). Only non-test caller: :timetravel replay suppression | kernel/src/main/kotlin/civictech/cell/evolve/Evolution.kt:53-126 (`Shadow.spawn` :76); timetravel/.../reconstruct/EffectSuppression.kt |
| Promotion swap PRECHECK→PREPARE→COMMIT→RETIRE | Yes (kernel) | Yes incl. reversal; `promoteReplica` :314; partitioned via PartitionedShardSet.kt:267 | Canonical, but **no production caller** (only KDoc refs in inspect ApplyOutcome.kt:76, Replication.kt:826) | Evolution.kt:128-140; tests ShadowPromotionTest, PartitionedPromotionTest, ReplicatedPromotionTest, PromotionWaveStateTest, B14ModulePromotionTest |
| PromotionPolicy / PromotionJudge | Yes | Judge is a manual accumulator: caller must hand-wire InvariantCell.violations into it (PromotionPolicy.kt:77-114) | Canonical, **0 callers outside kernel tests** (PromotionPolicyTest only) | kernel/.../evolve/PromotionPolicy.kt:53,94 |
| State migration (StateMigrating) | Yes | G-33 resolved (53:172) | Canonical | Evolution.kt:39 |
| Invariant cells (live checks) | Yes (57-line cell) | Synthetic side complete; "observation membrane, discharge + taps, monitor bands" unimplemented (52 status line 3) | Canonical, **test-only**: 0 callers in any `src/main` outside verify/ | kernel/.../verify/InvariantCell.kt:28; tests InvariantCellTest, CheckInvariants, GenerativeGraphTest |
| ReplicaConvergence | Yes | Yes | Used by harnesses (concord Checks.kt, oracle ConvergenceCheck, testkit churn) — not a live cell in any demo | kernel/.../verify/ReplicaConvergence.kt:32 |
| Workbench write plane (WKB2) | Partial | F1-F4 closed (contract, cold precheck, staged applier+unwinder, applyRemote progress); **promotion F9, UI F10/F11, ledger F13 open** | Canonical for live edit; uses Link/spawn directly, not Promotion | inspect/.../edit/Draft.kt:28-36 (`PromotionRequest` placeholder, "non-empty promotions refused"); WritePlane.kt:25 ("promote deliberately absent until WKB2 F9") |
| Live version-swap UI (WKB3) | No | — | — | epic computenet-324, 0 children |
| Inspector (read) | Yes | Sees remote refs, replica index, repartition holds; **no durability/journal view** | Canonical | inspect/.../Peers.kt:15-22; InspectorModel.kt:166; Cold.kt:23; 0 hits for Journal/durab in inspect/src/main |
| Time-travel (TTD1) | Yes, offline CLI | Reads HostDurability journals (shared decoder HostDurability.kt:212), reconstruct/diff/fidelity | **Parallel silo**: not in inspector (0 refs to InspectorServer), only consumer `demo/agora/build.gradle.kts:32` testImplementation | timetravel/src/main/kotlin/civictech/timetravel/{journal,reconstruct,cli} |
| Scrubbing UI in inspector (TTD2) | No | — | — | epic computenet-hzq, 0 children |
| Module loader (JAR1) | Yes | load/unload/cellFactory/track(registry) | **Leaf with no dependents**; workbench Catalogue.kt:30 only sees modules indirectly | loader/.../ModuleLoader.kt:470-1016 |
| Module versioning / jar-attached GraphSpec (JAR2/JAR3) | No | — | — | epics computenet-9yf, computenet-ask, 0 children |
| Concord conformance | Yes | 143 req rows, 88 covered; chapters 51/52/53 excluded (no ids) | Canonical arbiter, blind to this slice | doc/spec/CONCORDANCE.md:9-37 |

Spec prose drift: 53-evolution.md:3 and heading :99 still say the full swap transaction and `PromotionPolicy` are "unimplemented"; code has both (Evolution.kt:128, PromotionPolicy.kt). Trust code.

## Concord coverage numbers
- Normative chapters with requirement ids: **9 of 22** (CONCORDANCE.md:9). Excluded: all of 00, 11, 14, 23, 31, 32, 34, 43, **51, 52, 53**.
- Requirement rows: **143 total — 88 covered, 55 gap** (awk over CONCORDANCE table).
- Gaps by chapter: 12-ports 1 gap/4 covered; **13-links 9/2**; **15-lifecycle 9/2** (incl. 15-PROMOTE-01, 15-HOT-01, 15-APPLY-01, 15-PARK-01 — the lifecycle hooks evolution needs); 21-propagation 2/7; 22-consistency 1/7; **24-data-cells 26/59**; 33-mobility 0/1; 41-location 0/1; **42-replication 7/5**.
- Evolution/verification (52/53) and inspector: **0 ids, 0 rows** — DISPUTES "Structural gap: 13 normative chapters carry no requirement ids" and "Structural gap: inspector subsystem semantics carry no requirement ids and no scenario coverage"; "WKB2 — requirements cite 51/53 by section … (`spec-gap`, `[WKB2-63]`)".
- DISPUTES.md: 3916 lines, **42 top-level entries, ~32 still open** (10 resolved/retired/index headings); 74 headings total, 22 marked RESOLVED/RETIRED. Slice-relevant open: `B14-promotion-glitch-freedom` (DISPUTES.md:2236) — promotion swap has no step verb, no gate/candidate catalog cell, no L0 id in 53; property pinned only in loader B14ModulePromotionTest + kernel ShadowPromotionTest.
- Corpus: 96 YAML scenario files; none exercise Shadow/Promotion/InvariantCell (only DISPUTES.md mentions them).

## Integration gaps

| # | Gap | Evidence | Why it blocks an all-features demo | Sev | Tracking |
|---|---|---|---|---|---|
| G1 | No end-to-end evolution pipeline: nothing composes Shadow.spawn → InvariantCell gates → PromotionJudge → Promotion.promote(Replica) | PromotionJudge 0 non-test callers; judge must be hand-wired (PromotionPolicy.kt:77-80); no demo references Shadow./Promotion. | The flagship vision loop ("evolves by promoting implementations that satisfy invariants") cannot be shown on a running system | H | partly WKB2 F9 computenet-8joqm (promotion only; no shadow/invariant stage anywhere) — orchestration **untracked** |
| G2 | Invariant cells never run live | InvariantCell 0 main callers outside kernel; 52:3 says membrane/monitor bands unimplemented | "Verified by invariants under live data" is unproven in any demo; judge has no live input | H | untracked (no bead for demo-deployed invariants) |
| G3 | Workbench can edit but cannot promote; no UI edit mode | Draft.kt:28-36, WritePlane.kt:25; F10/F11 open | The only runtime mutation surface skips the evolution discipline (direct link/despawn) | H | computenet-8joqm (F9), computenet-aus0m (F10), computenet-hpw1d (F11), computenet-n7iuo (F13) |
| G4 | Promotion × durability untested | no test file combines Promotion.promote with journal/durability; exchange (durable+replicated+partitioned probe) never evolves | Swapping a journaled cell may break replay/checkpoint provenance; all-features demo must promote a durable cell | H | untracked |
| G5 | Inspector covers 2/14 demos (shopping, skillmatch); exchange/agora/deliberate/beadsmirror not inspectable | InspectorServer( only in demo/shopping Main.kt:493, skillmatch SkillMatchApp | The composition probe cannot be observed or edited live | M | untracked |
| G6 | Inspector blind to durability; time-travel is an offline CLI silo | 0 Journal refs in inspect/src/main; :timetravel only agora testImplementation | "Topology inspectable" excludes recovery state; no scrub-back in the live tool | M | TTD2 computenet-hzq (0 children, not broken down) |
| G7 | Loader isolated; no versioning, no jar-attached GraphSpec deploy | no project depends on :loader; JAR2/JAR3/WKB3 epics 0 children | Can't deliver "new implementations into a running system" from outside the classpath | M | computenet-9yf, computenet-ask, computenet-324 |
| G8 | Concord blind to 51/52/53 and inspector | CONCORDANCE.md:23-37; DISPUTES B14 (:2236), WKB2-63 | No executable arbiter for the evolution story; regressions in swap semantics only caught by kernel tests | M | DISPUTES entries only; minting ids **untracked** |
| G9 | Demo-findings F-4: no demo exercises live topology growth (slotfinder hard-wires participants) | doc/demo-findings.md:88-96 | Exactly the missing showcase | M | untracked (no bead found) |
| G10 | Spec status lines stale (53:3, :99 "unimplemented") | vs Evolution.kt:128, PromotionPolicy.kt | Planners under-estimate what exists → re-implementation risk | L | untracked |

## Bridging proposals (ordered)
1. **(S) Mint EARS ids for 52/53** (shadow suppression, swap no-torn/dup/missing, judge verdict, rollback) and add a `promote` step verb + gate/candidate catalog cell to concord schema (retires B14, G8). Unblocks honest tracking of everything below.
2. **(M) Kernel `Evolution` orchestrator**: one API `evolve(host, incumbent, candidateFactory, policy, gates: List<InvariantCell>)` doing Shadow.spawn → wire gate violations into PromotionJudge → promote/promoteReplica on `Promote` verdict (G1). Pure composition of existing primitives.
3. **(M) Land WKB2 F9 on top of (2)** so Workbench "promote" goes shadow→judge→swap rather than raw Promotion (G3); then F10/F11 UI.
4. **(M) Promotion × durability test + fix** in kernel: promote a journaled cell, crash, recover, assert replay provenance (G4).
5. **(M) Live invariants in the exchange demo**: deploy InvariantCells (conservation, replica convergence) as production cells surfaced via DemoShell/inspector (G2).
6. **(S) Inspector in every demo via DemoShell** (`--inspect-port` generic) — start with exchange (G5).
7. **(L) Evolution showcase demo** (or exchange/slotfinder iteration, F-4): add participant live via Workbench, shadow under invariants, promote across partitioned+replicated+durable graph, observed in inspector (G9 — the all-features demo).
8. **(L) Break down TTD2 + JAR2/JAR3/WKB3**: journal panel in inspector backed by :timetravel reconstruct; loader→catalogue→promote path for module candidates (G6, G7).
9. **(S) Refresh 52/53 status lines** (G10).

## Raw notes
- evolve: kernel/.../evolve/Evolution.kt (579 l: Shadow.spawn :76, Promotion 4-phase swap :140), PromotionPolicy.kt (PromotionJudge :94). verify: InvariantCell.kt (57 l), ReplicaConvergence.kt (77 l).
- Production callers outside kernel: InvariantCell = 0; PromotionJudge = 0; Shadow.* = 1 (timetravel EffectSuppression.kt); Promotion.promote = 0 real calls (only KDoc refs; kernel-internal promoteReplica in Replication.kt:826). ReplicaConvergence used by concord Checks, oracle, testkit (test harnesses, not demos).
- Tests: InvariantCell 9 test files, Shadow 8, Promotion.promote 10, PromotionJudge 2.
- Workbench write plane (inspect/edit, 2133 l): StagedApplier does spawn/link/despawn with staged CUT_OVER/RETIRE/abort. Promotion is a PLACEHOLDER: Draft.kt:28-36 `class PromotionRequest` "Feature F9 fills it; until then non-empty promotions refused"; WritePlane.kt:25 "promote deliberately absent until WKB2 F9". No shadow/invariant hooks in write plane.
- Write plane wired in demo/shopping Main.kt:449-499 (--inspect-write opt-in) and skillmatch SkillMatchApp.
- Inspector hosted by only 2 of 14 demo dirs: shopping (Main.kt:424-499) and skillmatch; generic hook in demo/shell DemoShell.kt. Sees remote refs (Peers.kt:15-22 LocationRegistry.Remote -> peer net host), replica index (InspectorModel.kt:166), repartition HELD (Cold.kt:23). Zero refs to Journal/durability in inspect/src/main.
- :timetravel (journal/, reconstruct/, diff/, fidelity/, cli/Main.kt): reads durability journals offline (HostDurability.kt:212 shared frame decode). Only consumer: demo/agora build.gradle.kts:32 testImplementation. NOT wired to the inspector (0 refs to InspectorServer), no demo runtime use. Uses Shadow for effect suppression during replay (EffectSuppression.kt).
- :loader (ModuleLoader.kt 1036 l, ModuleClassLoader 285 l): load/unload/cellFactory, track(registry). No Gradle project depends on :loader (only its own fixtures); inspect Catalogue.kt:30 mentions JAR1 modules visible to palette via registry but no ModuleLoader call. Module-candidate promotion proven in tests only (computenet-051.5.5 closed).
- Beads: WKB2 epic computenet-7p8 OPEN, in progress (F1-F4 closed; F9 promotion computenet-8joqm OPEN, F10 computenet-aus0m, F11 computenet-hpw1d, F13 computenet-n7iuo open). WKB3 computenet-324, JAR2 computenet-9yf, JAR3 computenet-ask, TTD2 computenet-hzq: open, 0 children (not broken down). 33 open epics total.
