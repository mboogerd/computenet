# ComputeNet — Feature Status Overview

> **Generated**: 2026-07-25 · read-only survey, no code changed.
> **Method**: derived the intended feature set from the spec tree (`doc/spec/`)
> and roadmap (`doc/spec/90-roadmap/`), then cross-checked each against actual
> `kernel/`+`gen/` source, the named exit tests, and git history
> (`doc/ORCHESTRATION.md` merge records). The full `./gradlew test` gate was run
> and is **green (BUILD SUCCESSFUL)** as of this survey; all 21 milestone exit
> tests exist and no test is `@Ignore`/`@Disabled`.

## What ComputeNet is

An experimental Kotlin/JVM **dataflow runtime**. A program is a live, mutable
graph of **Cells** that own state and interact only through typed **Ports**
connected by explicit **Links**. Data propagates **incrementally** (deltas) and
**glitch-free**; execution is **interest-driven** (attention follows
subscription); and the same graph runs unchanged in one thread, across JVMs, or
across machines owned by different peers (**location transparency**). The bet:
one abstraction absorbs messaging + computation + state for *decentralized apps
that don't want blockchain-style consensus*. It is spec-led — code exists to make
cited spec text true. (`doc/spec/00-foundations/01-vision.md`)

## The single most important caveat — documentation drift

The roadmap docs are **internally inconsistent about M12+**, and you should trust
code + git + tests over the narrative prose:

- `92-way-forward.md` labels **Milestones 12–16 "⚠ PROPOSED, NOT COMMITTED"** and
  `94-implementation-plan.md`'s header says *"Implementation: none — this document
  is the work list."*
- **But `doc/ORCHESTRATION.md` records Waves 1–4 (tickets W1.1–W4.6) as all
  merged**, and I verified those merge commits (e.g. `aa47579`, `dc23b3c`,
  `a7658c3`) **are genuine ancestors of `main`**. The corresponding code exists
  (`PartitionedCell`, `BoundaryPolicy`, `CycleHead`, `SingleWriterReplication`/
  `LeaderMark`, `ReBaseline`, `IntakeSaturation`, wire protocol frames, …).
- Meanwhile `91-gap-analysis.md` still lists G-34..G-62 as open/partial, and those
  `G-nn` markers are still anchored in the source as TODOs for the **residual**
  corners.

**Reconciled reading**: the *core* of Milestones 12–15 has actually **landed in
`main`** (via the W1–W4 ticket run dated 2026-07-12); the docs that call it
"proposed" simply weren't updated. Each ticket closed the decided core and left a
**research-gated residual** (tracked in `95-research-plan.md`, R1–R17) still marked
in code. 92 itself admits this: *"reconciling 94's drift is a separate maintenance
task."* So the practical frontier is **not** "M12 not started" — it's "M12–M15 core
done, residuals + M16 + E1–E6 open."

---

## Feature status by area

Legend: **✅ Done** (spec realized, exit test green) · **🟩 Core done** (landed +
tested; named residual open) · **🟨 Partial** · **⬜ Proposed/not built** ·
**🔬 Research-gated** (solution genuinely open, in `95`).

### Foundations & programming model (`doc/spec/10`, `kernel/cell.port`, `.graph`, `.proxy`, `gen/`)

| Feature | Intended capability | State | What's left |
|---|---|---|---|
| Cells / Ports / Links | Explicit identity, typed ports, runtime-mutable topology with handshake (`onLink/onUnlink`, cardinality) | ✅ Done (M1–M2) | — |
| MessageContext & wave ids | Context flows inlet→outlet; per-source `(sourceId,counter)` wave timestamps | ✅ Done (M2) | — |
| Dispatch tiers | DATA / MANAGEMENT / PORT_PROTOCOL dispatch classes | 🟩 Core done (W1.5) | Full protocol-descriptor lint surface (part of G-60) |
| Graph DSL & GraphSpec | Thin builder; graphs-as-data; typed spawn/link | ✅ Done (M4.5) + DX sweep (typed refs) | GraphSpec remote-apply atomicity 🔬 (R4) |
| KSP codegen | Contract/method-id tables, descriptors, port scan, reflection-free proxies | ✅ Done (M5.1, W4.6) + `@CellBase` generated bases | Full G-60 lint suite (protocol/effect/Eager/determinism bits) 🟨 |
| Developer-experience (KSP-DX) | Remove authoring/wiring boilerplate: `TypedRef`, generated `<Cell>Ports`, `@CellBase` static handler binding | 🟩 Phases 0–4 landed | Phase 5 (KSP Mediate-proxy gen, tap descriptors) not scheduled |

### Dataflow semantics (`doc/spec/20`, `kernel/cell.consistency`, `.data`)

| Feature | Intended capability | State | What's left |
|---|---|---|---|
| Glitch-free propagation | Diamond/frontier consistency under randomized cross-host scheduling | ✅ Done (M2, `GlitchFreeDiamondTest`) | — |
| Incremental data cells | OR-set (tombstones), PN/counter, Map/List, filter/count/intersect/join | ✅ Done (M4.1–4.3) | Map/List keep documented single-writer limits |
| Relational + aggregation suite | flatMap, semijoin/difference, groupBy + count/sum/avg/min/max/topK, equi/cross/outer join, windows | ✅ Done (M11, `DataflowSuiteExitTest`) | Watermark eviction, session windows, weighted/bag semantics deferred (E4/E6) |
| Ownership discipline | `Owned` move-by-serialize, `Leased` release; no silent drop of exclusives | ✅ Done (M5.6); exclusives-off-happy-path 🟩 (W2.5) | Full crash/dead-letter contract residual (G-46), discharge shadow sinks (C-11) 🟨 |
| Observation taps | Read-only `Borrowed` tap fires before sole consumer; invariant taps | 🟩 Core done (W2.4) | KSP Borrowed-projection gen, `Shadow.forkExclusive` (G-47 residual) |
| Source epochs / ReBaseline | Fresh sourceId per emission epoch; RESTART re-baselines instead of aliasing waves | 🟩 Core done (W2.1) | Epoch-hygiene / superseded-epoch GC 🔬 (R2/G-42) |
| Pull + catch-up baseline | `StateRequest(since)`; catch-up as a baseline excluded from wave completeness | 🟩 Core done (W2.2) | — (details pinned by impl) |
| Cycles | `CycleHead` + feedbackInput; two-tier quiescence; hop guard | 🟩 Guard done (W3.1) | Weak-tier fixpoint **convergence guarantee** 🔬 (R2/G-19); nested/cross-host cycles 🔬 (E5/R16) |

### Execution model (`doc/spec/30`, `kernel/cell.host`, `.attention`, `.membrane`)

| Feature | Intended capability | State | What's left |
|---|---|---|---|
| Hosts, colors, scheduling | Coroutine + virtual-thread hosts, color markers, host queue | ✅ Done (M3.1) | — |
| Mobility / drain | suspend/resume/migrate a running stateful subchain, zero loss, per-link FIFO | ✅ Done (M3.3, `SubchainMigrationTest`) | — |
| Supervision / dead letters | Error outlets, RESTART, no silent exclusive drop | ✅ Done (M3.5/M4.4) | Dead-letter envelope for in-flight exclusives (G-46) 🟨 |
| Bounded intakes / backpressure | OPEN/SATURATED/CLOSED intake; coalesce vs park-at-sender; `SaturationSignal` | 🟩 Core done (W1.4, `IntakeSaturation`) | Transitive multi-hop saturation notice polish (G-36 residual) |
| Attention (interest-driven) | `Attention(level)` aggregated to bands → host priority; park/resume unattended cones | ✅ Done (M6, `AttentionGenerativeTest`) + realization details (W4.5) | Migrate/relink frontier, deadlineHint, retraction×park race (G-58 residual) 🟨 |
| Completeness watermark / Stalls | Per-edge watermark; typed `Stall(reason, recoverable)` WAIT/DEGRADE/RE-SCOPE | 🟩 Core done (W2.7) | Multi-source watermark residuals (E2/E3) 🔬 |
| Topology edge events | In-band `EdgeOpen/EdgeClose`; per-source floors; unlink-during-wave no stall | 🟩 Core done (W1.7 in-proc, W3.2 wire) | Delivered-watermark substrate (E3) 🔬 |
| Membranes / composition | Exposure map, Flatten/Mediate, hidden-by-default | 🟩 Core done (W3.4) | KSP Mediate-proxy gen residual; coupling liveness 🔬 (R3/G-53) |
| Host hierarchy / quota | Parent/child hosts, shutdown cascade, cell-count quota, spawn veto | ✅ Done (M8.1, `HierarchyTest`) | Spawn *redirection* / placement engine ⬜🔬 (R5/G-61) |

### Distribution (`doc/spec/40`, `kernel/cell.wire`, `.replication`, `.durability`)

| Feature | Intended capability | State | What's left |
|---|---|---|---|
| Wire / two-process graph | kotlinx.serialization codec, WebSocket transport, loopback bridges, remote addressing | ✅ Done (M5, `DistributedCollaborativeAppTest`, `TwoJvmConvergenceTest`) | — |
| Protocol plane over wire | PORT_PROTOCOL frames, reverse channel, capability negotiation | 🟩 Core done (W3.2) | — |
| Replication (mergeable) | Same logical cell on many hosts converging by delta gossip; anti-entropy on reconnect | ✅ Done (M7, `ReplicatedSessionTest`) | Mesh liveness/churn argument, last-replica handoff (G-45 residual) 🔬 |
| Single-writer replication | Leader as sole applier; follower redirect/reject; `LeaderMark` fencing | 🟩 Core done (W4.3) — **manual/orchestrated failover only** | Automatic **election + failover** ⬜🔬 (R1/G-44) |
| Trust boundaries / identity | `PeerId` on link requests, deny-by-default allowlist at boundary | ✅ Done (M8, `TrustBoundaryTest`) | Identity **strength** (keys/DIDs, Sybil) 🔬 (R7); capability revocation (G-54 residual) |
| BoundaryPolicy / disclosure | Five predicates, three seams, attention clamping, `RequireSigned` | 🟩 Core done (W4.1) | Revocable capabilities, mgmt-plane authority across bridges, at-rest encryption (G-54 residual) |
| Durability + reconnect | Write-ahead journal, checkpoints, WS reconnect w/ backoff, crash recovery | ✅ Done (M10, `CrashRecoveryTest`, `CrashRestartConvergenceTest`) | Determinism marker/output-mode journaling + effectful processed-frontier residual (G-59); see C-9 |
| Partitioned / keyed sharding | `PartitionedCell`, sharded==unsharded, mid-run repartition | 🟩 Core done (W4.2) | Routing-epoch consistency under concurrent migration, scatter-gather, per-key attention (G-56/G-57 residual) |

### Development process / evolution (`doc/spec/50`, `kernel/cell.evolve`, `.verify`)

| Feature | Intended capability | State | What's left |
|---|---|---|---|
| Invariants-as-cells + generative harness | Kotest adapter, per-cell error outlets, 100-seed simulation harness | ✅ Done (M4.4/M4.6) | — |
| Shadow deployment + promotion | Candidate shadows production, judged by invariants, promoted by link swap; state migration | ✅ Done (M9, `ShadowPromotionTest`) | Discharging shadow sinks for exclusives (C-11) 🟨 |
| Promotion transaction hardening | PRECHECK/PREPARE/COMMIT/RETIRE, retained incumbent, typed handoff | 🟩 Core done (W3.5) | Rollback/atomicity residual, linkable ApplyReport (G-49/G-51 residual) |
| Promotion policy-as-data | Serializable `PromotionPolicy`, differential shadow | 🟩 Core done (W4.4) | Registration/trigger **authority** (gated on membrane layer), canary/rolling (G-50 residual) |
| GraphSpec remote apply | SpawnStep identity, idempotent re-apply, dead-letter rejection reporting | 🟩 Core done (W3.6) | Partial-apply atomicity 🔬 (R4) |

### First real application

| Feature | Intended capability | State | What's left |
|---|---|---|---|
| Agora — argumentation graphs (`:demo:agora`) | Collaborative argument graph; edge-is-a-cell (attacks-on-attacks); DF-QuAD credence; cyclic via head threshold; HTTP+SSE UI, durable | ✅ Done (M17, `AgoraExitTest` + Cycle/Magnitude/Durability tests) | Known backend bugs filed in `bugs/` (duplicate edges/double-count, self-edges, error-message leakage) |
| Demo suite | shopping (collaborative list), slotfinder/skillmatch/tiering (operator showcases), backlog-triage | ✅ Done / active | These exist to *surface* kernel gaps → `doc/demo-findings.md` (F-1..F-5: outer-join/combine-latest, bucketing, keyed-upsert, glitch-free view fold) |

---

## Not built / deliberately deferred

| Track | What it is | State |
|---|---|---|
| **M16 — Economics + attention hardening** | Per-Principal attention **budgets**, cost-to-mint-identity (Sybil economics), replica spawn-vs-subscribe economics (G-62) | ⬜ Proposed, **not built** (no budget/economic layer in `kernel`). G-58 attention residuals also here. |
| **E1–E6 — Incremental engines** (`96-incremental-engines-plan.md`) | E1 OR-map (closes G-23) · E2 vector-frontier observation edge · E3 delivered-watermark substrate · E4 lateness/waterline eviction · E5 nested cycles/incremental fixpoints · E6 weighted (Z-set) family | ⬜ **All PROPOSED, none committed.** Spec-first; each writes spec before code. |
| **Research R1–R17** (`95-research-plan.md`) | Genuinely-open questions: leader election (R1), weak-tier fixpoint convergence (R2), coupling liveness (R3), placement engine (R5), economic layer (R6), identity strength (R7), keyed/bag convergence (R8/R17), + E-milestone spin-offs R10–R16 | 🔬 Open (R8 partially promoted to E1). These gate the residual corners above. |
| Explicitly deferred-with-trigger | Lease pooling (G-21 ph3), OR-map/bag (G-23), watermark eviction & session windows | ⬜ Deferred; triggers recorded in spec |

---

## Ambiguities & things to double-check

1. **The big one — doc/code drift (see caveat above).** `91`/`92`/`94` describe
   M12–M16 as unbuilt, but W1–W4 (their content) merged into `main`. If you want
   the roadmap to be trustworthy, the maintenance task is to reconcile `91`'s gap
   table and `92`/`94`'s status labels against ORCHESTRATION + git. I trusted git
   + code + tests.

2. **Conflicts C-9 / C-11 / C-12.** `91-gap-analysis.md` still shows these as *"code
   diverges; fix pending"*, yet W2.1/W2.6 claim to fix C-12/C-9 and the markers now
   sit in **test files** (`RestartReBaselineTest`, `ShadowOwnershipTest`) that look
   like they *verify* the fix rather than flag an open bug. Likely resolved-in-code,
   stale-in-doc — worth a 5-minute confirmation. **C-10 does not exist** in the doc
   at all (table jumps C-8→C-9), though `94` and code reference it — a genuine loose
   thread.

3. **G-60 lint suite is partial.** Port descriptors + `<Cell>Ports` objects landed,
   but the fuller compile-fail lint family (protocol/effect/Eager/determinism/`@Key`/
   magnitude bits) is only partway. `gen` has a single test file — the thinnest test
   coverage of any module for a component AGENTS.md calls "authoritative runtime
   metadata."

4. **`MagnitudeSchedulingTest` (M17) lives in `kernel`, not `:demo:agora`** with the
   other four M17 tests. Consistent with it being a kernel feature (magnitude-band
   dispatch) rather than app logic, but worth confirming it's where you expect.

5. **Integration/demo tests use `Thread.sleep`** (11 files, incl. exit tests
   `DurabilityTest`, `TwoJvmConvergenceTest`, `CrashRestartConvergenceTest`) — they're
   green now but are timing-sensitive by nature; the deterministic-simulation
   guarantee applies to the kernel sim tests, not these.

## Bottom line

Everything through **M11** and the **M17 Agora application** is **done and verified**
(exit tests green, full gate green). The **M12–M15 substrate + hardening core has
also landed** in `main` (bounded flow control, wire-crossing protocol plane, epochs/
re-baseline, cycles guard, membranes, boundary policy, single-writer core, partitioned
cell, promotion hardening) — despite the roadmap prose still calling it "proposed."
What genuinely **remains**: the **research-gated residuals** of those features (leader
election, fixpoint convergence, placement, coupling liveness, identity strength), the
**economic/budget layer (M16)**, the **incremental-engines family (E1–E6)**, and
finishing the **KSP lint/DX** tail — plus reconciling the roadmap docs with what
actually shipped.
