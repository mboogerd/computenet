# Computenet — Cross-Ambition Milestone Plan (v2)

A partial order over every ambition discussed: evidence lanes, kernel E-milestones, distribution,
and product lanes. Each ambition is chunked only as far as needed to expose real cross-project
dependencies; chunks are the scheduling unit. Where no dependency exists, §5 imposes a value order.

**v2 changes** (2026-07-31): BEN3 restructured into a benchmark-bridge lane (BB-*) built on the
standardized suites (Nexmark, YCSB, incremental TPC-H, LDBC SNB, Graphalytics, TPC-C); SOC1
redefined as LDBC-SNB-backed; new §3 kernel-enablement matrix answering "what must the kernel
grow for each suite"; kernel lane gains two small chunks (KAGG-R, KBLK) and one riding note
(KRD via inspector-v4 V1C-KERNEL); WKB1 marked delivered by inspector-v4 waves 1–6; V4-PILOT
noted as the distribution lane's first replicated evidence.

Conventions: chunk IDs are `LANE#`. "Kernel-lane" marks work that must serialize through the
single kernel SCC (G-63). Spec references (G-x, E-x, C-x, R-x, PN-x, M-x) point at
`doc/spec/90-roadmap/`; V-x tickets at `98-inspector-v4-plan/tickets/`.

---

## 1. Chunk catalogue

### Evidence lanes (facts producers — all fully parallel, test-only or doc-only)

| ID | Chunk | Depends on | Unblocks / feeds |
|---|---|---|---|
| BEN1 | `:bench` module: JMH harness, benchmark source set, slow-test gating convention; per-operator delta throughput, fan-out scaling, per-cell footprint | — | G-21 pooling trigger; PLC1 cost inputs; **gives V1C-BENCH-class measurement gates a permanent home** (the v4 C-replan declined JMH as "a permanent tax" for a one-shot — BEN1 reverses that calculus) |
| BEN2 | Macro workloads: graph shape × cardinality × host count sweeps; re-baseline cost under fan-out (G-43) | BEN1 | PLC1; journal-segmentation trigger |
| ORA1 | Batch-oracle differential tester: random graph + input sequence, naive recompute reference, over the existing operator algebra | — | KE1 acceptance; BB-TPCH/QRY1 oracle; subsumes per-cell seed tests |
| ORA2 | Oracle coverage for tagged/keyed family (OR-map, weighted) | ORA1, KE1 core types | KE4-era work |
| CHA1 | Adversarial DST rig on `SimulationController`/`SimWorld`: partition, reorder, duplicate, crash mid-drain, journal truncation, arbitrary-frontier restart | — | CHA2, CHA3, MEM2, KE3 harness |
| CHA2 | Reproduction suite for the decided-but-divergent rules: C-9 (effect replay), C-11 (shadow drops exclusives), C-12 (RESTART aliasing) | CHA1 | KFX |
| CHA3 | Generative membership-churn harness (the G-45 ask, verbatim) | CHA1 | MEM2 |
| FRM1 | TLA+/Alloy model of wave completeness + glitch-freedom (G-38/G-39/G-40) | — | KE2/KE3 spec tickets |
| WIR1 | Wire test-vector conformance corpus | — | WIR2 |
| WIR2 | Non-JVM peer (TS or Rust) against the corpus | WIR1 | G-35 pressure |

### Benchmark-bridge lane (BB — replaces old BEN3; each suite doubles as the acceptance corpus for its kernel/product parent)

| ID | Suite | What it measures | Depends on | Notes |
|---|---|---|---|---|
| BB-NEX | **Nexmark** (auction stream, Q1–Q8+) | Continuous-query throughput/latency; the suite DBSP/Feldera- and Flink-class engines publish against | BEN1; Q1–Q3 (map/filter/equi-join) **runnable today**; windowed Qs need **KE4**; latency variant wants the KE2 ack slice (or sink-side observation as stopgap); KBLK for throughput fidelity | *The* acceptance suite for KE4. `Windows` already assigns tumbling/sliding by key derivation with explicit event time; state boundedness is exactly the deferred eviction trigger |
| BB-YCSB | **YCSB** | Point read/write/scan throughput under tunable mixes | KRD (read path), KE1 (multi-writer keyed), KBLK (load phase); ordered scan needs KAGG-R's range-scan check | Thin adapter over keyed cells + the bounded-read primitive |
| BB-TPCH | **Incremental TPC-H** (DBToaster framing: 22 queries maintained under RF1/RF2 update streams) | IVM efficiency; deletion propagation through join chains | QRY1 (query expression), KE1 (tagged multiset deltas — RF2 deletions are the acid test), KAGG-R, ORA1 | Becomes QRY1's acceptance corpus |
| BB-SNB | **LDBC SNB** (interactive + BI) | Graph-shaped reads, traversals, ordered top-K, update streams | SOC1 (= the schema/workload host, see below), KRD, KAGG-R; BI adds KE5 | Data generator with scale factors + update streams = interest-scoping experiment substrate for free |
| BB-GRA | **LDBC Graphalytics** (PageRank, BFS, SSSP, WCC, CDLP, LCC) | Iterative/fixpoint analytics, bulk-load ingest | KE5, KBLK | PageRank-on-cells is the E5 stress test with a published reference answer |
| BB-TPCC | **TPC-C** bridge document | Deliberately a *negative-result* study: what Computenet lacks to be an interactive multi-key transactional system, and whether it should want it | — (doc-only) | Deliverable is a spec artifact naming the missing coordination primitive as a candidate G-item; no number expected |

### Kernel lane (one SCC — strictly serial; order in §4)

| ID | Chunk | Depends on | Unblocks / feeds |
|---|---|---|---|
| KX | C-13 extraction: `LocationRegistry` → registry + `InstanceIndex` + `DeliveryHold` | — | MEM1 concurrency; de-conflicts distribution chunks |
| KE1 | E1: OR-map / tagged-map convergence class (E1.1–E1.6) | ORA1 leverage | AGO3, SOC1 keyed state, ORA2, BB-TPCH deletions, BB-YCSB |
| KFX | C-9 fix + minimal G-59 processed-frontier for `Effectful` | CHA2 failing tests | CON1 |
| KAGG-R | Aggregate/scan residuals audit + fill: `Aggregator` already has retraction-aware min/max/topK (TreeMap-backed) — verify multi-column ordered top-K over rows, count-distinct, and an ordered key-range scan for keyed families; implement only what's missing | — (small; operator-family surface) | BB-TPCH, BB-SNB, BB-YCSB scan |
| KBLK | Benchmark/bulk mode: WAL off/async host config, batched ingress path, deterministic-time drive hooks | — (config-level, small) | Every BB throughput number; BB-GRA bulk load |
| KE3 | E3: delivered-watermark substrate, causal stability, tag/tombstone reclamation | KX; FRM1 informs; CHA1 harness | MEM1 (E3.6 gates), long-running SOC/AGO, KE4 |
| MEM1 | G-44 epoch-claim election + failure detection | KX, KE3 | SOC3 |
| MEM2 | G-45 churn-reconvergence + last-replica handoff | MEM1, CHA3 | SOC3 hardening |
| KE5 | E5: cycles, `LoopContext`, iteration-cut fixpoints, stratification | — | AGO4, QRY2, BB-GRA, BB-SNB BI |
| KE2 | E2: vector-frontier observation edge, absorb-acks, aligned multi-view sink. **Note:** the BB lane supplies its first concrete consumers — write-visibility acks for BB-YCSB/BB-NEX latency. A minimal sink-side ack slice may be split out early if latency fidelity demands it | FRM1 informs | WKB2 snapshots; BB latency measurement |
| KE4 | E4: event-time waterline eviction (the deferred trigger in `Windows`' KDoc — BB-NEX pulls it) | KE3 | BB-NEX windowed queries; streaming state bounds |
| KRD | *(riding, not scheduled here)* Request/response bounded read — **V1C-KERNEL (v4 wave 8, already ticketed) delivers the core**: wave-neutral, bounded, any-scheduler `snapshotOf` successor. Bench lane generalizes it into a public query primitive after it merges | inspector-v4 lane | BB-YCSB, BB-SNB interactive |
| PLC3 | Spawn-redirection enforcement hook (G-61) | PLC2 | SOC3 placement |

### Distribution lanes

| ID | Chunk | Depends on | Unblocks / feeds |
|---|---|---|---|
| DSC1 | Cryptographic peer identity + signed announcements (G-29 crypto half; ingress-side) | — | SOC2, AGO2, ECO1 |
| DSC2 | Peer discovery (mDNS / rendezvous) | DSC1 | SOC3 |
| DSC3 | NAT traversal / relay | DSC2 | open-internet deployment |
| SEC1 | `BoundaryPolicy` three seams (93 I-28) | — (small kernel-lane slot) | SOC2 moderation |
| PLC1 | Mesh simulator + cost model | BEN1/BEN2 cost inputs | PLC2 |
| PLC2 | Offline placement/economics policy engine (G-61/G-62) | PLC1 | PLC3, ECO1 |
| ECO1 | Per-Principal budgets + identity-minting cost (G-62 Sybil half) | DSC1, PLC2 | SOC3 |
| — | *Note:* **V4-PILOT** (v4 wave 10) — first replicated inspector over a real socket — is this lane's first merged evidence artifact; treat its findings file as PLC/DSC input | | |

### Product lanes

| ID | Chunk | Depends on | Unblocks / feeds |
|---|---|---|---|
| AGO1 | Dialogue → argumentation extraction pipeline from recorded transcripts | — | AGO2, AGO3 |
| AGO2 | Live multi-speaker: per-speaker channels, per-Principal stance attribution | AGO1; DSC1 optional | flagship demo |
| AGO3 | Revision/retraction: stable claim identity across re-extraction | AGO1, KE1 | live-map correctness |
| AGO4 | Strong-tier fixpoint semantics replacing app-side heads | KE5 | closes agora's G-19 residual |
| SOC1 | **LDBC-SNB-backed** single-node social network: implement the SNB schema (persons, knows-edges, forums, posts) and interactive workload as the demo — follows-as-interest, scatter-gather feed (PN-5), generator-driven load | — (pulls triggers: keyed families, G-24 placement pressure) | SOC2, BB-SNB (same artifact) |
| SOC2 | Federated: multi-peer, moderation membranes, real identity | SOC1, DSC1, SEC1 | SOC3 |
| SOC3 | Decentralized: open mesh, election-backed groups, budget-bounded interest | SOC2, MEM1, DSC2, ECO1 | north star |
| WKB1 | ~~Inspector v3 completion~~ **Delivered**: inspector-v4 waves 1–6 merged (paged data, activity stream, wave-health/errors, canvas, tooltips, FE tests); waves 7–11 (V1c bounded read behind V1C-BENCH gate, V4-PEERID, V4-PILOT) in flight per the C-replan | — | WKB2, TTD2 |
| WKB2 | Workbench: graph create/edit → `GraphSpec` apply on a running system via `Promotion` (R4 partial-apply atomicity is the open risk) | WKB1 waves 7–11 | WKB3, JAR3 |
| WKB3 | Live version-swap UI | WKB2, JAR2 | full live-programming story |
| JAR1 | Dynamic jar load: classloader isolation + `ContractRegistry` registration | — | JAR2, JAR3 |
| JAR2 | Module versioning: `schemaVersion` discipline (G-49 residual), state migration, rollback | JAR1 | WKB3 |
| JAR3 | Jar-attached `GraphSpec` deploy; upgrade variant | JAR1 (+JAR2) | deployment story |
| QRY1 | Datalog/relational frontend, non-recursive | ORA1 oracle | QRY2, BB-TPCH |
| QRY2 | Recursive queries → cycle regions | QRY1, KE5 | E5's external consumer |
| CON1 | First connector pair (Postgres CDC + Kafka sink) with idempotency keys | KFX | CON2 |
| CON2 | Connector fan-out | CON1 | ecosystem |
| TTD1 | Time-travel core: journal reader, offline reconstruction, run diffing (deterministic cells) | — | TTD2 |
| TTD2 | Scrubbing UI in inspector | TTD1, WKB1 | debugging demo |

---

## 2. The DAG

```mermaid
flowchart TB
  subgraph EV[Evidence]
    BEN1 --> BEN2
    ORA1
    CHA1 --> CHA2
    CHA1 --> CHA3
    FRM1
    WIR1 --> WIR2
  end

  subgraph BB[Benchmark bridge]
    BBNEX[BB-NEX]
    BBYCSB[BB-YCSB]
    BBTPCH[BB-TPCH]
    BBSNB[BB-SNB]
    BBGRA[BB-GRA]
    BBTPCC[BB-TPCC doc]
  end

  subgraph KL[Kernel lane — serial]
    KX --> KE1 --> KFX --> KAGGR[KAGG-R] --> KE3 --> MEM1 --> KE5 --> KE2 --> KE4
    MEM1 --> MEM2
    KBLK
    KRD[KRD via V1C-KERNEL]
  end

  subgraph DIST[Distribution]
    DSC1 --> DSC2 --> DSC3
    SEC1
    PLC1 --> PLC2 --> PLC3
    DSC1 --> ECO1
    PLC2 --> ECO1
  end

  subgraph PROD[Products]
    AGO1 --> AGO2
    AGO1 --> AGO3
    SOC1 --> SOC2 --> SOC3
    WKB1 --> WKB2 --> WKB3
    JAR1 --> JAR2 --> JAR3
    QRY1 --> QRY2
    CON1 --> CON2
    TTD1 --> TTD2
  end

  %% evidence → kernel
  ORA1 -.acceptance.-> KE1
  CHA2 -.failing tests.-> KFX
  CHA3 --> MEM2
  FRM1 -.spec input.-> KE3
  FRM1 -.spec input.-> KE2
  BEN2 -.cost inputs.-> PLC1

  %% benchmark bridge edges
  BEN1 --> BBNEX
  KE4 --> BBNEX
  KBLK -.throughput fidelity.-> BBNEX
  KE2 -.ack slice.-> BBNEX
  KRD --> BBYCSB
  KE1 --> BBYCSB
  KAGGR --> BBYCSB
  QRY1 --> BBTPCH
  KE1 --> BBTPCH
  KAGGR --> BBTPCH
  ORA1 -.oracle.-> BBTPCH
  SOC1 --> BBSNB
  KRD --> BBSNB
  KAGGR --> BBSNB
  KE5 --> BBSNB
  KE5 --> BBGRA
  KBLK --> BBGRA

  %% kernel → products
  KE1 --> AGO3
  KE5 --> AGO4
  KE5 --> QRY2
  KFX --> CON1
  KE3 -.E3.6 gates.-> MEM1
  ORA1 -.oracle.-> QRY1

  %% distribution → products
  DSC1 --> SOC2
  SEC1 --> SOC2
  MEM1 --> SOC3
  DSC2 --> SOC3
  ECO1 --> SOC3
  DSC1 -.attribution.-> AGO2
  JAR2 --> WKB3
  WKB1 --> TTD2
```

Solid = hard prerequisite; dotted = strong-leverage soft dependency.

---

## 3. Kernel-enablement matrix: what the kernel must grow, per suite

The question each row answers: *if this benchmark is the goal, what kernel work is on its
critical path — and what already exists that I might have rebuilt by mistake?*

| Suite | Already in the kernel (do not rebuild) | Kernel gap on the critical path | Non-kernel gap |
|---|---|---|---|
| **Nexmark** | Windowing as key derivation (`Windows.tumbling`/`sliding`, event time as explicit element attribute, late elements as ordinary adds with retraction flow); equi-join, groupBy, retraction-aware max/topK for Q5/Q7 | **KE4** — watermark-driven eviction is the *only* kernel blocker for the windowed queries, and it is literally the deferred trigger named in `Windows`' KDoc. **KBLK** for honest throughput (WAL sync would otherwise dominate). KE2's ack slice only if end-to-end latency percentiles are wanted | Generator source + query graphs in `:bench` |
| **YCSB** | `snapshotOf` seam; **V1C-KERNEL** (ticketed, wave 8) delivers the bounded wave-neutral read this needs | **KRD** — generalize V1C-KERNEL's read into a public request/response primitive with completion semantics (mostly arrives via the inspector-v4 lane; small generalization after). **KE1** for multi-writer keyed. Ordered range **scan** over keyed families — the one genuinely new operator surface (KAGG-R audits it) | YCSB client adapter |
| **Incremental TPC-H** | Retraction-aware sum/avg/count/min/max/topK (`Aggregator`); join/semijoin/antijoin family; `TaggedSetOperator` | **KE1** — tagged multiset deltas; RF2's deletions propagating through multi-join chains is the acceptance property. **KAGG-R** residuals: multi-column ordered top-K over rows, count-distinct | **QRY1** carries most of the weight (22 queries by hand is the alternative); ORA1 as cross-check |
| **LDBC SNB interactive** | Keyed cells, scatter-gather (PN-5), interest-driven instantiation | **KRD** + **KAGG-R** (ordering + LIMIT is in nearly every complex read). Per-update multi-cell atomicity: verify a single ingress delta fanning to person+edge cells lands in one wave (likely already holds — verify, don't build) | **SOC1** is the host artifact; parameterized query graphs |
| **LDBC SNB BI / Graphalytics** | — | **KE5** — recursion/fixpoints for path queries, PageRank, WCC. **KBLK** bulk load | Reference-answer harness in `:bench` |
| **TPC-C** | — | None buildable today: the missing primitive is interactive cross-cell transactions with abort. The bridge doc's job is to specify that gap precisely (candidate: coordinator-cell pattern over quorum cells vs. an honest "out of scope by design") | Doc only |

Net new kernel surface across *all six suites*: **KE1, KE4, KE5** (already planned, now with
acceptance suites attached), plus two small chunks (**KAGG-R**, **KBLK**) and one generalization
(**KRD**) that mostly rides the already-ticketed V1C-KERNEL. The benchmark ambition adds almost
no kernel scope — it adds *pull* to scope that was already queued, which is exactly what a
trigger-gated spec wants.

---

## 4. Kernel-lane order

1. **KX** — cheap, spec-blessed, de-conflicts everything.
2. **KE1** — most-demanded chunk: AGO3, SOC1, ORA2, BB-TPCH, BB-YCSB.
3. **KFX** — small; CON1 hard-blocked; CHA2 hands it failing tests.
4. **KAGG-R** — small audit+fill on the operator-family surface just touched by KE1; unblocks
   three BB suites early.
5. **KE3** — watermark substrate; MEM1 and KE4 both need it.
6. **MEM1 → MEM2** — election, then churn with CHA3.
7. **KE5** — AGO4, QRY2, BB-GRA, BB-SNB-BI all wait on it.
8. **KE2** — now has concrete consumers (BB latency acks, WKB2 snapshots); if BB-YCSB latency
   fidelity is wanted earlier, split the sink-side ack slice out and run it after KAGG-R.
9. **KE4** — immediately after KE3 if BB-NEX is prioritized (see §5); otherwise here.
10. **PLC3** when PLC2 has a policy worth enforcing.

**KBLK** is config-surface and slots into any gap. **KRD** rides inspector-v4 wave 8
(V1C-KERNEL) and gets a small generalization pass afterward.

The one genuine tension this ordering carries: BB-NEX wants KE4 early (it's the flagship
comparative suite), but KE4 sits behind KE3, and MEM1 also sits behind KE3. If benchmark
comparability against Feldera/Flink becomes the priority, the swap is MEM1 ↔ KE4 (run KE4
straight after KE3, delay election) — legal, since nothing in BB depends on MEM1.

---

## 5. Value order where dependencies don't decide

| # | Chunk | Why now |
|---|---|---|
| 1 | ORA1 | Correctness oracle; three consumer lanes; highest leverage per effort. |
| 2 | KX (kernel lane opens) | Cheap, decided, buys concurrency. |
| 3 | BEN1 | Three trigger-gated deferrals + PLC1 wait on its numbers; also un-taxes future measurement gates (V1C-BENCH precedent). |
| 4 | AGO1 | Differentiated bet, zero kernel deps, transcript asset in hand. |
| 5 | CHA1 → CHA2 | Failing tests ready before KFX enters the lane. |
| 6 | SOC1 (LDBC-SNB-backed) | Trigger-puller *and* benchmark substrate in one artifact — the v2 respec strengthens its rank. |
| 7 | BB-NEX (Q1–Q3 slice) | Runnable today on existing operators; establishes the harness, generator, and comparability baseline while KE4 queues. |
| 8 | JAR1 | Independent; opens the versioning/workbench arc. |
| 9 | DSC1 | First distribution chunk; SOC2 and AGO2 want it; ingress-side. |
| 10 | QRY1 | Starts once ORA1 is usable as oracle; BB-TPCH gives it a finish line. |
| 11 | FRM1 | Before KE3's spec ticket is written. |
| 12 | TTD1 | High demo value, low urgency. |
| 13 | PLC1 → PLC2 | Gated on BEN2's measured costs. |
| 14 | SEC1 | Before SOC2 needs membranes. |
| 15 | WKB2 | After v4 waves 7–11 land; R4 flagged. |
| 16 | BB-YCSB | After KRD generalization + KE1; thin adapter by then. |
| 17 | WIR1 (→ WIR2) | Background/agent-idle work. |
| 18 | CON1 | Hard-blocked on KFX; forcing-function value partially pre-captured by CHA2+KFX. |
| 19 | BB-TPCH, BB-SNB, BB-GRA, BB-TPCC | Each fires when its parents land (QRY1/KAGG-R; SOC1/KRD; KE5; anytime for the TPC-C doc — it's a good agent-idle writing task). |
| 20 | Second wave | DSC2/DSC3, ECO1, CON2, MEM2, AGO4/QRY2, WKB3, JAR3, SOC3, TTD2. |

### Deliberate orderings between non-dependent pairs

- **ORA1 before BEN1**: correctness evidence outranks performance evidence while the E-plan is unbuilt.
- **AGO1 before SOC1**: differentiation + the transcript asset; flip if proving the distribution story outranks the application story.
- **BB-NEX Q1–Q3 before BB-YCSB**: no kernel deps vs. two; and it makes the comparative story visible earliest.
- **KAGG-R before KE3** (within the lane): small, adjacent to KE1's surface, and it converts three BB suites from blocked to parent-pending.
- **KE5 before KE2** stands, but more weakly than in v1 — BB gave KE2 its first real consumers; revisit at the KE5/KE2 boundary.
- **MEM1 before KE4** stands *unless* Feldera/Flink comparability becomes a stated goal — then swap (§4).
- **DSC1 before DSC2/DSC3**: identity before discovery.
- **PLC1 gated on BEN2**: no simulating against fictional costs.

---

## 6. Standing hygiene

- Every lane writes findings to its own append-only `doc/<lane>/findings.md`, citing G-ids,
  never editing `91-gap-analysis.md` or `CONCORDANCE.md` directly.
- A periodic single-agent integration pass is the only writer of the gap table and the
  concordance matrix.
- Kernel-lane admission follows §4; evidence and benchmark lanes may reorder §4 (a number or an
  oracle failure can promote a chunk); product lanes file demand as findings, they don't jump
  the queue.
- BB results are findings too: every suite run that hits a wall names the wall as a candidate
  G-item or pulls an existing trigger — that's the lane's actual product; the numbers are a
  side effect.
