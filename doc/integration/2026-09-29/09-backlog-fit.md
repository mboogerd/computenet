# 09 — Backlog fit against the integration re-plan

Tracker read 2026-09-29 at main `181c6459` (read-only; no bd writes). Inputs: `REPLAN.md`, reports 01–08, `demo/alignment/TRIAGE.md`, `gh pr list`, `bd list` for open / in_progress / blocked / deferred.

**Population.** 241 non-closed beads: 228 open, 5 blocked, 8 deferred, **0 in_progress**. 53 of them are the SDLC process epic (computenet-wpvy) and its 52 children (34 bugs, 17 features, 1 chore). All are work-skill and process items. None concerns demo or platform integration, so they are excluded from placement. That leaves **188 beads**: 39 epics, 92 children, and 57 orphans with no parent.

Placement key: **P0–P4** feed a re-plan phase. **PREREQ** is needed by a phase but not named in it. **CONTINUE** is orthogonal and can run in parallel. **PAUSE** would be redone by the spine. **POSTPONE** is postponed scope or comes after flagship milestone 1. **DROP/MERGE** is obsolete, a duplicate, or absorbed into a spine bead. **PROCESS** is a retro record.

---

## 1. Summary

| Placement | Count | Notes |
|---|---|---|
| CONTINUE | 65 | Mostly CI-flake and wire-hygiene bugs, gated concord schema changes, benchmarks, query, and deliberate *model-calibration* research |
| POSTPONE | 36 | Mobility, membranes, loader/JAR, discovery/NAT, gossip, MEM1/2, social, TTD, placement economics, connectors, XDEP |
| PAUSE | 29 | Deliberate structural and feature work, WKB2 UI/write-plane features, BDS4 lease plane and BDS5, agora AGO3/4 |
| P3 | 17 | ECO1 budgets (6 children and 1 orphan), KE2 aligned views, WKB2 F9 promotion, rate windows, ObserveCell termination |
| PREREQ | 9 | Durability correctness under the future structure log and stacked gate, the ref-capture security bug, the async-effect primitive |
| P1 | 7 | Wire and iroh link-lifecycle bugs that the PeerTransport seam must absorb |
| MERGE | 7 | 8xstm, n0iaw, g92i, j1f2, d2j2, gb1, 3ylve are absorbed into spine beads |
| P2 | 5 | Owned/Leased discharge, emission and waterline recovery, concord dual-journal vocabulary |
| P4 | 5 | Demograph (un-defer), collective ranking, the BDS3 glitch-free ready set, an alignment board bug |
| PROCESS | 5 | Retro records |
| P0 | 2 | Stale spec/KDoc recipes, the concordance attribution bug |
| DROP | 1 | cj7 test issue |

**The calls that matter most**

1. **Neither the integration epic nor the flagship has a bead.** No open bead covers any Phase 1 bullet (link primitive, GraphSpec-with-features, bootstrap, placement driver) or the Phase 2 gate. No open alignment epic exists either: the ALN2 epic (computenet-9y79n) is closed and the triage board (computenet-i00bh) is closed. Most of the spine has to be filed (§5).
2. **Un-defer demograph (computenet-drz8)** and make it a P4 feeder. Its collective-ranking child (computenet-drz8.2) is the flagship's aggregation. Its deliberation-graph child (computenet-drz8.3) is agora-flavoured and can wait.
3. **The deliberate-demo platform-gaps epic (computenet-6aj8h) is really the durability half of Phase 1.2.** Fold it under the integration epic. The interim StructureLog (computenet-8xstm) becomes "journal the GraphSpec". Re-decide DurableMap (computenet-n0iaw) against journaled keyed families. Its four checkpoint and replay bugs are PREREQ for the Phase 2 kill -9 gate.
4. **ECO1 budgets (computenet-66m) is the one epic landing code right now**: three PRs merged on 09-28/29 and it was re-scoped today. Finish it, but kernel-only. "Installed by the bootstrap" belongs to Phase 1.3, not to per-demo wiring.
5. **Pause deliberate's structural refactors and new features** (dq2fy.22, .24, ra3o2, ilknp, and others). That is the busiest lane (claimed; ~25 worktrees). Its claim store and per-question folds would be rebuilt on graph{} + journaled GraphSpec. Its multi-user feature (computenet-ilknp) duplicates the flagship's social semantics. Keep only the model-calibration research.
6. **Pause most of the workbench epic WKB2 (computenet-7p8)**: F7, F8, F10, F11 and F13 build UI and preconditions on today's GraphSpec step vocabulary, which Phase 1.2 changes. F9, promotion through Promotion.promote (computenet-8joqm), is the only child the Phase 3 evolution orchestrator needs.
7. **The announced-ref overwrite (computenet-zlm2)**, where a peer can capture another peer's inbound traffic, is a hard PREREQ for Phase 3 "authority check at replication admission". The flagship's "only you write your slice" is meaningless while any peer can hijack a location.

---

## 2. In-progress items and recommendation

No bead has status `in_progress`. Work in flight has to be read from claims (`owner:` labels), open PRs, recent merges and worktrees.

| Item | Evidence of activity | Recommendation |
|---|---|---|
| **ECO1 per-principal budgets** (computenet-66m) | owner:MacBoo. Re-scope block dated 2026-09-29. Merged #1172 (:economy module, kwhw6), #1173 (kernel charge sites, 5o1rf), #1175, #1179 (budget denials, 2zasa). 6 open children | **FINISH, kernel-scoped.** Fix the three ledger/charge bugs first: promoteReplica charging Spawn in COMMIT (computenet-4yvsx), zero-price undo racing the bucket monitor (computenet-8s74x), Link charge during a cold precheck (computenet-fh9cc). Then the restart-is-not-a-refill checkpoint (computenet-xbs8t) and replication Interest/Retention charges (computenet-jtof7). Keep the adversarial suite (computenet-o0lzp) but drop any per-demo enabling: Phase 1.3 installs the ledger. The ECO1-D11 follow-up (computenet-8aboz, budget-charging a promotion re-authorization) joins the epic. |
| **Key rotation over discovery** (computenet-qzr7n) | Closed; landed #953 | Nothing to do. The brief's "in progress" is out of date. |
| **deliberate demo** (computenet-dq2fy) | owner:MacBoo. ~25 `deliberate-*` worktrees. About 10 deliberate PRs merged 09-24..09-29. 13 open children, 3 blocked awaiting a human | **PAUSE features and structure; CONTINUE calibration.** Merge anything already in a PR, then stop claiming dq2fy.22, dq2fy.24, ra3o2, vyxyl, ilknp, mkimq and i9ujc. Calibration research can go on (prior dominance nxege p1, pyyi0, n99jt, dq2fy.17, x91yk, dw2wh). It changes no wiring. |
| **Instrumenting the ScriptedSequenceTest flatten flake** (PR #1126, computenet-3vng) | Ready (non-draft), updated today | Let it merge; it is CI health (CONTINUE). |
| **MEM1 apply-time fence tiebreak** (PR #796, computenet-7zssw) | Draft, idle since 09-10 | Leave it in draft. It is a real correctness bug in SingleWriterReplication, but its epic (MEM1, computenet-f7h) is deferred and the flagship uses CRDT replication. Revisit when the Phase 2 gate needs single-writer state. |
| **Readiness as an incremental cell** (BDS3, computenet-98u) | owner:MacBoo on the epic, 98u.3 and 98u.5. No PR | Keep. 98u.3 (glitch-free ready set) is the flagship's beads candidate source (P4). |
| **Stale claims** on BDS4 lease plane (computenet-6wc, 6wc.4, 6wc.6), XDEP (computenet-3bso.3, 3bso.4), CON1 connectors (computenet-3f4), WKB2 (computenet-7p8, 7p8.2), KE4 (computenet-lxo), KE2 (computenet-8x9), BEN2 (computenet-wl8), WIR1 (computenet-ncz, ncz.3, ncz.4), QRY1 decision (computenet-cab.7.10), :wire flake re-measure (computenet-dqy.37.2, owner Anva@A0030) | owner labels with no PR and no in_progress status | Release the claims on the PAUSE and POSTPONE ones (6wc, 3bso, 3f4, the WKB2 UI features) so /work does not keep picking them. Keep 8x9, lxo, ncz, wl8 and cab. |

---

## 3. Placement table per epic

Counts are open children (including blocked and deferred). SDLC (computenet-wpvy, 52 children) is excluded as PROCESS.

### Spine-feeding epics

| Epic | Title | # | Placement | Phase / bullet | Rationale |
|---|---|---|---|---|---|
| computenet-6aj8h | Platform gaps exposed by deliberate (recovery lifecycle, structure logs, effects, DevEx) | 10 | **P1** (re-parent under integration) | 1.2 journal GraphSpec as structure log; 2 kill -9 recovery | The epic's own text names the I-7 structure-log gap. 8xstm is merged into 1.2. The checkpoint/replay bugs (hknt0, bv7qb, 4fpyy, xy7w4.5) are PREREQ for the Phase 2 recovery leg. The async-effect primitive (zw4d0) is PREREQ for the Phase 4 "Jev variant". Its agora/deliberate items pause (§4). |
| computenet-66m | ECO1 — per-principal budgets + identity-minting cost | 6 | **P3** (finish now) | 3 budgets installed by bootstrap; 4 budgets per principal | Active and kernel-level. The ledger is what the bootstrap installs. |
| computenet-8x9 | KE2 — vector-frontier observation, absorb-acks, aligned multi-view sink | 7 | **P3** | 3 canonical observation API; aligned views across roots (F-27) | lw0mv is the rejection half of F-27. rpa7n (bridged aligned observation) is what a multi-peer flagship view needs. uyuvp moves demos off raw observe. The two bugs (2mgd9, vp8oz) CONTINUE. The shopping ActorIngress decision (2tfmf) pauses: it is a non-flagship demo decision that the multi-cell-write-as-one-wave bullet will settle. |
| computenet-7p8 | WKB2 — workbench graph edit → GraphSpec apply via Promotion | 8 | **P3** for F9 only; rest **PAUSE** | 3 evolution orchestrator ("WKB2 F9 on top") | See §4. |
| computenet-drz8 | DGR — Demograph stances and preferences with aggregate views | 2 | **P4** (un-defer) | 4 per-principal keyed slice; incremental aggregation | User decision 4. It is deferred today and the module is one KDoc file (08 §doc drift 5). |
| computenet-98u | BDS3 — readiness as an incremental derived cell, tested against `bd ready` | 3 | **P4** | 4 beads as candidate source (TRIAGE Phase 3) | The glitch-free ready set is the natural candidate feed. It is kernel cells, not glue. |

### CONTINUE (orthogonal, runs in parallel)

| Epic | Title | # | Rationale |
|---|---|---|---|
| computenet-lxo | KE4 — event-time waterline eviction | 2 | Kernel operator semantics. The dur×waterline recovery child (638io) moves to P2 (§4). The [24-WL-04] scenario (tcovt) is concord authoring. |
| computenet-ncz | WIR1 — wire test-vector conformance corpus | 2 | Encoding vectors are independent of the PeerTransport seam and harden it. |
| computenet-cab | QRY1 — Datalog/relational frontend (:query → GraphSpec) | 2 | Its output is a GraphSpec, so it gains once GraphSpec carries features and does not fight it. Both children are decisions. |
| computenet-8m7 | QRY2 — recursive queries → cycle regions | 0 | Same as QRY1, lower priority. |
| computenet-7fe | FRM1 — TLA+ model of wave completeness / glitch-freedom | 0 | Formal model with no code contention. It would sharpen P2's "3-way interactions". |
| computenet-wl8 | BEN2 — macro workloads, re-baseline under fan-out | 0 | Measurement only. |
| computenet-3xi | BB — benchmark bridge (Nexmark, YCSB, TPC-H, Graphalytics, TPC-C) | 6 | Acceptance corpora, orthogonal. The LDBC-SNB bridge (3xi.4) is POSTPONE because it sits on SOC1's artifact. |

### PAUSE (would be redone by the spine)

| Epic | Title | # | What to do |
|---|---|---|---|
| computenet-dq2fy | deliberate — LLM-explored deliberation graph demo | 13 (+3 under dq2fy.24) | Keep calibration research (6). Pause claim-store and fold refactors and all new features until Phase 1.2/1.3 land, then rebuild on graph{}. Fold ilknp's multi-user design into the flagship. |
| computenet-6wc | BDS4 — bidirectional beads replication + gossiped lease plane | 5 | Pause. It extends beadsmirror's bespoke peering path and /work lease plumbing. The lease plane (6wc.4, blocked) is single-writer state that wants MEM1. 4zmnd, 6y3ez and ekxxt are /work breakdown-marker items and belong under SDLC (re-parent to wpvy). Resume on the Phase 1.3 bootstrap. |
| computenet-bii | BDS5 — cross-machine beads replication over iroh | 0 | Pause until the Phase 1.3 PeerTransport seam has an iroh binding. Then it becomes "run beadsmirror on the bootstrap with transport=iroh", which is much smaller than the epic as written. |
| computenet-5rn | AGO3 — revision/retraction, stable claim identity | 0 | Agora is not the flagship. Revisit after deliberate/agora move onto the spine. |
| computenet-6sz (feature, no parent) | AGO4 — strong-tier fixpoint semantics replacing app-side heads | — | Same, and it wants KE5 cycles. |

### POSTPONE (user-postponed scope, or after flagship milestone 1)

| Epic | Title | # | Why |
|---|---|---|---|
| computenet-a3n | SOC2 — federated social: multi-peer, moderation membranes, real identity | 0 | Membranes postponed (G-52). Its identity half is covered by Phase 3's authority bullet. |
| computenet-6a2 | SOC3 — decentralized social (open mesh, elections, budget-bounded interest) | 4 | The alignment flagship supersedes it as north star for milestone 1. The budget-bounded interest idea (6a2.3) is reused in Phase 4 budgets. |
| computenet-07k | SOC1 — LDBC-SNB single-node social (deferred) | 1 | Stays deferred. Its ingress decision (3ylve) merges into P3 F-19/F-22. |
| computenet-f7h | MEM1 — G-44 epoch-claim election + failure detection (deferred) | 1 | Flagship is CRDT-replicated. 7zssw is a live correctness bug with a draft PR: CONTINUE at low priority. |
| computenet-psl | MEM2 — G-45 churn reconvergence + last-replica handoff | 0 | After MEM1. |
| computenet-yk6 | GOS1 — HyParView partial-view membership | 0 | Full mesh is adequate at flagship scale. Plugs into the Phase 1.3 bootstrap later. |
| computenet-bm3 | GOS2 — PlumTree eager/lazy dissemination | 0 | After GOS1. |
| computenet-aas | DSC2 — peer discovery over iroh (deferred) | 0 | Milestone 1 can use configured peers from the manifest. |
| computenet-83t | DSC3 — NAT traversal / relay | 0 | Same. |
| computenet-ssu | WIR2 — non-JVM (Rust) peer | 0 | After WIR1. |
| computenet-ask | JAR3 — jar-attached GraphSpec deploy | 0 | :loader parked (decision 4). |
| computenet-9yf | JAR2 — module versioning, state migration, rollback | 0 | :loader parked. *Watch:* if the Phase 2 "promotion of a journaled cell" needs a state-shape migration, pull only the schemaVersion/state-migration half forward. |
| computenet-324 | WKB3 — live version-swap UI | 0 | UI over the Phase 3 orchestrator. Comes later. |
| computenet-hzq | TTD2 — scrubbing UI in the inspector | 0 | Phase 4 stretch at best. |
| computenet-ocv | TTD1 — time-travel core (deferred) | 2 | Phase 4 stretch ("time-travel over journals"). Its two verdict decisions wait for that. |
| computenet-z1w | PLC2 — offline placement/economics policy engine (+ z1w.1 spawn-redirection hook) | 1 | The Phase 1.4 placement driver is config-driven. Policy comes after. |
| computenet-3sq | PLC1 — mesh simulator + cost model | 0 | Gated on BEN2 anyway. |
| computenet-3f4 | CON1 — Postgres CDC + Kafka connector pair | 0 | Should be the first consumer of the Phase 1.2 kernel durable-input checkpoint. Starting now would build a private offset store: a fifth bypass. Release the stale claim. |
| computenet-rrf | CON2 — connector SPI / registry | 0 | After CON1. |
| computenet-dud | KE5 — cycles, LoopContext, iteration-cut fixpoints | 0 | No milestone-1 need (Bradley–Terry can iterate outside a cycle region, or batch per wave). |
| computenet-3bso | XDEP — cross-workspace readiness (deferred, p1) | 2 | Depends on BDS4/5. Remains deferred. |

### MERGE into other epics

| Epic | Title | # | Where |
|---|---|---|---|
| computenet-gb1 | AGO2 — live multi-speaker, per-principal stance attribution | 0 | Its per-principal stance attribution is exactly demograph actor/stance. Merge the concept into the Phase 4 per-principal slice bead and close AGO2 as superseded, or leave it as the agora adoption of it later. |

### Orphan features, tasks and bugs (no parent, or parent closed)

| Id | What it is | Placement | Phase / reason |
|---|---|---|---|
| computenet-zlm2 | An announced ref overwrites any location, letting a peer capture another's inbound traffic | **PREREQ** | P3 authority at admission; P4 "only you write your slice" |
| computenet-s4n8y | HostDurability.checkpoint keys on journal identity; a fresh FileJournal for the same path checkpoints nothing | **PREREQ** | P1.2 journaled spec + P2 kill -9 |
| computenet-rhhry | Recovery silently drops journal-tail state of an Effectful+Stateful cell (spec gap) | **PREREQ** | P2 recovery leg; P4 Jev effect cell |
| computenet-o2aj | FileJournal.append doesn't check an existing journal's format version | **PREREQ** | P1.2 (the journaled GraphSpec is a new format) |
| computenet-8aboz | LinkSupport retains the PeerStamp so promotion re-auth can be budget-charged (ECO1-D11) | **P3** | ECO1; also touches P3 evolution |
| computenet-hrp9n | ratePerWindow is a per-lifetime cap over an unbounded per-principal map | **P3** | "per-principal rate limits" |
| computenet-iltfm | ObserveCell has no awaitable termination (social finds dispatchers by thread name) | **P3** | Canonical observation API |
| computenet-3ylve | SOC1: re-decide :demo:social ingress boundary (F-22) | **MERGE** | Into the P3 "multi-cell write as one wave" bead |
| computenet-g92i | Should a routed streamTo target learn of an edge close (bypass path) | **MERGE** | P1.1: one link primitive retires the bypass path |
| computenet-j1f2 | WsTransport.listen() leaks java-websocket types to demos | **MERGE** | P1.3 PeerTransport seam, where demos stop naming transports |
| computenet-d2j2 | tiering derives replica identity from peering role; a three-JVM star collides | **MERGE** | P1.3/1.4: the bootstrap assigns replica identity from the manifest |
| computenet-vzb | A superseded WsTransport connection leaves mirror/ingress cells spawned forever | **P1** | 1.3 seam lifecycle |
| computenet-1mbp | A bridged reconnect over one PortAddress pair would double-relay (supersession guard unwired) | **P1** | 1.3 |
| computenet-8uv6 | Should an app-initiated WsConnection close disarm its reconnect loop | **P1** | 1.3 (seam-level policy, "hoist shared transport policy" in 08) |
| computenet-amf8l | MutualDialSidecarTest: tie-break loser logs ERROR "no such link" (iroh) | **P1** | 1.3 iroh binding |
| computenet-axifn | Mutual dial: far side's tie-break close before own LINK_UP leaves counter at 0 | **P1** | 1.3 iroh binding |
| computenet-g1aua | IrohMirrorTransportTest: a ref published while partitioned crosses on CI | **P1** | 1.3 iroh binding (partition semantics) |
| computenet-6qe8 | Should carriesMarker widen like carriesExclusive (93 I-6/I-8) | **P2** | 3-way interactions in 93 |
| computenet-dmwl | Proxy.discharge's reflective walk reads runtime vs declared types | **P2** | Owned/Leased flow in the gate |
| computenet-u6np | Non-consuming discharged-state predicate for Owned/Leased | **P2** | Same |
| computenet-elc | Concord vocabulary for per-cell journal mapping (two journals in one dur scenario) | **P2** | Stacked gate needs dur scenarios with multiple journals |
| computenet-nta1 | Concordance attributes a requirement id to a citing chapter, not its declaring one | **P0** | Needed before minting ids for 43/52/53 |
| computenet-aa6gl | Alignment boardGate per-viewer completeness makes row states unreachable (parent 10mvq closed) | **P4** | Flagship. Fix in the current board too: it is in use for real triage today |
| computenet-0prx | BS-18 does ~72k fsyncs for a 30-utterance transcript (group commit?) | CONTINUE | Journal perf. Promote to PREREQ if the flagship's per-peer journals hit it |
| computenet-2971 | OQ-2: clear interest-assignment entries on unpublish? | CONTINUE | Adjacent to P3 interest→spawn; decide inside that bead |
| computenet-5v0f | OQ-3: should install() respect an active delivery hold? | CONTINUE | Kernel decision, orthogonal |
| computenet-2oqz | Default-off intra-host task-selection seam on SimulationController | CONTINUE | Test infra |
| computenet-z530 | Data cells: off-host reads via immutable snapshots | CONTINUE | Opportunistic convention |
| computenet-pz2n | Staged-work depth instrument blind spots | CONTINUE | Instrumentation |
| computenet-24mor, d3u7c, jsdn2, jv7w5, nn8sl, noy6p, qu7be | Gated concord schema changes (effect boundary, read-state paging/bounds/refusal/walk control, agg top-k) | CONTINUE | Concord vocabulary. The flagship's bounded reads benefit |
| computenet-m0h | Concord cannot prove a scenario discriminates | CONTINUE | Concord tooling |
| computenet-ezw1d, w2twp | WireCodec invalid UTF-8 accepted; kotlin.<Type> discriminators on the wire | CONTINUE | WIR1-adjacent wire hygiene |
| computenet-n14y | Handshake.kt supersession KDoc overstates | CONTINUE | Doc chore |
| computenet-3nuon, 3vng, akch, esby, xybk, wqhaf, 0her, pzbxj, mvie, qb6l, ei16s, nvepr, 66lbe, 0r4i.1, dqy.37.2, bdth.1 | CI flakes, flake tooling, e2e assertion and doc hygiene | CONTINUE | A green, trustworthy CI is a precondition for adding the P2 *required* lane |
| computenet-7lb | Concord schema: leader-handoff verb + dist effect-sink binding (42-replication) | POSTPONE | Single-writer/MEM scope |
| computenet-p449q | DiscoveredPeering keeps an abandoned dialled link in upLinks | POSTPONE | DSC2 |
| computenet-7tz0 (deferred), ylka (deferred) | iroh reconnect accepted flake; ORA1 divergence control | POSTPONE | Stay deferred |
| computenet-cj7 | "Test cross-machine beads sync": arbitrary 2026-08-08 test issue for git refs/dolt/data sync | **DROP** | Sync moved to a native DoltHub remote (`sync.remote`, CLAUDE.md). The test's premise is gone |
| computenet-bjxgc, frzwo, ha4nr, pfodj, yiov2 | retro records | PROCESS | — |

---

## 4. Notable child-level splits

**WKB2 workbench (computenet-7p8).**
- **P3**: F9, promotion cut-over through Promotion.promote/promoteReplica with honest rollback reporting (computenet-8joqm). It is the "judge → promote" tail of the evolution orchestrator.
- **PAUSE**: F7 concurrency preconditions (computenet-u3svi), F8 confirmation tokens for destructive steps (computenet-vbmf1), F10 frontend edit mode (computenet-aus0m), F11 plan review UI (computenet-hpw1d) and F13 verification ledger / close-out (computenet-n7iuo). All five are keyed on today's GraphSpec step vocabulary (spawn/connect only). Phase 1.2 adds replicate, partition, durable, shadow, unlink and keyed families, so plan review, blast radius and preconditions would be re-authored.
- **CONTINUE**: the WritePlaneBoundaryTest gap for supervise/suspend/drain/migrate (computenet-7p8.2) and the step-key encoding fix (computenet-yz3xc). Both are small, and the latter is correctness.

**Deliberate platform gaps (computenet-6aj8h).**
- **MERGE into P1.2**: interim topology journaling / StructureLog (computenet-8xstm) and DurableMap (computenet-n0iaw). Decide whether DurableMap survives as the keyed-family journal or is subsumed by journaled keyed families. Either way it must be the kernel's one answer that retires the 5 hand-rolled logs.
- **PREREQ for P2**: checkpoint carries frames held outside the scheduler (computenet-hknt0), unpark re-journals parked frames (computenet-bv7qb), tee duplication of a volatile inlet's staged frame (computenet-4fpyy), and the parked I-7 R4 literal (computenet-xy7w4.5, which collides with [24-DUR-07]).
- **PREREQ for P4**: the async external-effect cell primitive (computenet-zw4d0). The Jev aggregation variant runs through it, and it needs a spec decision first.
- **P0**: 31-hosts recovery sentence and stale recoverFrom recipes (computenet-qp4xo), part of the doc refresh.
- **PAUSE**: agora adopts deliberate's semantics catalog (computenet-259rq) and the deliberate quiescence-fence test (computenet-yqt8h).

**KE2 aligned views (computenet-8x9).**
- **P3**: lw0mv (build-time rejection of an ungated non-monotone ancestor), rpa7n (bridged aligned observation), zvdt1 (the aligned sink declares its guarantee wave) and uyuvp (adopt observeAligned in skillmatch/tiering). The last is migration toward the canonical API.
- **CONTINUE**: bugs 2mgd9 and vp8oz.
- **PAUSE**: the shopping ActorIngress decision (computenet-2tfmf).

**KE4 waterline eviction (computenet-lxo).**
- **P2**: crash recovery without a checkpoint re-emits historical adds onto an evicting cell (computenet-638io). This is a durability × waterline interaction and exactly what the emission-epoch rule must decide.
- **CONTINUE**: the [24-WL-04] settlement scenario (computenet-tcovt).

**deliberate (computenet-dq2fy).**
- **CONTINUE**: calibration research. Prior dominance (nxege), question-free plausibility (pyyi0), premise-vs-bearing (n99jt), saturation defaults (dq2fy.17), model A share-sharpening (x91yk) and model C VoI near clamps (dw2wh).
- **PAUSE**: single claim-store owner (dq2fy.22), per-question folds (dq2fy.24 with its 3 blocked human-decision chores), global claim store (ra3o2), common cores (vyxyl), implication relations (i9ujc), framing readings (mkimq), the restart test rework (4xsnu), and **multi-user participants (ilknp)**. The last should be redesigned as a consumer of the flagship's per-principal slice pattern rather than built bespoke first.

**BDS4 (computenet-6wc).** The lease plane (6wc.4, blocked) and pre-ready lease re-check (6wc.6) pause. The children 4zmnd, 6y3ez and ekxxt are /work breakdown-marker protocol items, so move them to the SDLC epic (process work mis-parented under a product epic).

**BDS3 readiness (computenet-98u).** The glitch-free ready set (98u.3) is P4 (candidate source). The KDoc claim test (98u.5) and the rarest DEPENDENT coverage family (lcxv) CONTINUE.

**Demograph (computenet-drz8).** Collective ranking and preference fusion extracted from backlog-triage (drz8.2) is P4 and should land first. The DF-QuAD deliberation graph from agora (drz8.3) is POSTPONE.

**SOC3 (computenet-6a2).** All four children POSTPONE. Budget-bounded interest with visible truncation (6a2.3) is the design to borrow for Phase 4 per-principal budgets on replicated interest.

**ECO1 (computenet-66m).** All six are P3. Order: the three bugs, then xbs8t, then jtof7, then o0lzp.

---

## 5. Re-plan bullets with no bead, and proposed re-parenting

### 5a. Bullets to file

Create these under the new epic with `.claude/skills/work/scripts/create-ticket.sh`: it is a shared parent, so do not use a hand-typed `bd create --parent`.

**Phase 0**
- **Integration epic** (owner of everything below). None exists.
- **Script-generated feature × demo adoption matrix** as a CI artifact, replacing FEATURE-STATUS.md (stale since 2026-07-27). No bead.
- **Doc refresh** (91 gap analysis, 96 E-item headers, 41 C-13, 53 status, ARCHITECTURE:53/61/63, AGENTS repository map missing :query, :timetravel, :loader, :iroh, dialogue, alignment, allocator-observe). Only computenet-qp4xo touches a sliver. File one bead that absorbs qp4xo.
- **Mint requirement ids for spec 43 (security), 52, 53** so concord can express them. No bead. Depends on computenet-nta1.
- **Inspector as a DemoShell flag** (every demo inspectable). No bead.
- **Arch test: no raw subscribe/streamTo in demo main code.** No bead.

**Phase 1**
- **1.1 One link primitive** (role + staging options, always through admission + topology), then migrate agora and deliberate off routed-only links. No bead. Absorbs computenet-g92i. The untracked `backlog/agora-dynamic-cycle-head-admission.md` (report 01) should be linked.
- **1.2a GraphSpec carries feature parameters** (replicate, partition, durable, shadow, unlink, keyed families). No bead. UnlinkStep and routed/keyed/replicated steps are untracked per report 01.
- **1.2b Journal the GraphSpec as the structure log; retire the 5 hand-written logs** (agora, dialogue ×2, backlog-triage, KeyedCells, deliberate) and alignment's private JSONL. Re-scope computenet-8xstm into this bead rather than filing new. Decide computenet-n0iaw inside it.
- **1.2c Kernel durable-input checkpoint** (cursor/offset + replay into a data cell), retiring beadsmirror FeedCheckpoint and allocator-observe OffsetCheckpoint / DeclarationHistoryJournal. No bead. CON1 (computenet-3f4) waits on it.
- **1.3 Runtime bootstrap from spec + manifest** (hosts, journal, transport, inspector, budget ledger) **and the kernel PeerTransport seam with ws + iroh bindings**, hoisting credential admission, reconnect backoff and admit-less-flap policy. No bead. Absorbs j1f2 and d2j2; parents vzb, 1mbp, 8uv6, amf8l, axifn, g1aua.
- **1.4 Placement driver: same spec → N JVMs by configuration** (GraphDsl.kt:97-106 driver unbuilt). No bead. PLC2/PLC3 are policy on top, not this.

**Phase 2**
- **Stacked composition gate**: exchange on real PartitionedCell, real JVM peers, kill -9 journal recovery, an Owned/Leased flow, mid-run promotion and a live invariant, as a required CI lane. No bead.
- **Replicable GroupByCell / N-ary MapDelta merge (F-7).** No bead.
- **Source emission-epoch rule (22 I-14, 0 code hits).** No bead. computenet-638io is its first test case.
- **Promotion of a journaled cell.** No bead. Watch JAR2's state-migration half.

**Phase 3**
- **Evolution orchestrator** (shadow → invariant gates → PromotionJudge → promote). PromotionJudge has no production callers. Only F9 (computenet-8joqm) exists; the orchestrator is unfiled.
- **Interest → spawn kernel seam (F-24).** No bead (the social workaround is demo-side). computenet-2971 is adjacent.
- **Multi-cell write as one wave (F-19/F-22).** Kernel primitive unfiled; computenet-3ylve is only the social-side decision (merge it in).
- **Aligned views across independent roots (F-27), build side.** computenet-lw0mv covers only rejection. File the construction half, or widen lw0mv.
- **Identity/authority configurable in apps**: per-principal signed writes plus an authority check at replication admission. No bead. It depends on computenet-zlm2, and membranes are *not* needed.
- **Budget ledger installed by the bootstrap.** ECO1 covers kernel and :economy; bootstrap installation is unfiled (a child of the 1.3 bead or of ECO1).

**Phase 4 (flagship).** Nothing is filed; the only related items are aa6gl, drz8.2 and 98u.3.
- **Flagship epic**: "decentralized prioritization — alignment rebuilt on the spine", built only through graph{} + bootstrap, with an arch test.
- **Demograph types**: actor / stance / weight as a per-principal keyed slice. drz8 has no such child; drz8.2 is only ranking fusion.
- **Shared replicated object set with owner-only edit/delete and an ownership-transfer authority op** verified at admission.
- **Interest-driven replication of rounds/topics per peer.**
- **Incremental aggregation** (groupBy / means / Bradley–Terry) behind glitch-free aligned views.
- **Per-peer durability, crash recovery, offline edit and reconvergence.**
- **Live invariants**: no foreign writes in a slice; aggregate consistent with slices.
- **Aggregation-algorithm swap via shadow/promotion** (Jev variant; needs computenet-zw4d0).
- **Per-principal budgets** (anti-spam/Sybil; borrows 6a2.3's design).
- **/work consumes the ranked order** (TRIAGE.md Phase 2). Unfiled.
- **Beadsmirror as candidate source via replication** (TRIAGE.md Phase 3; feeds from 98u.3).
- **Inspector attached; time-travel over journals as a stretch** (TTD1).

### 5b. Re-parent under the new integration epic

Re-parenting is proposed rather than cross-epic blocking edges because bd refuses blocking edges across the epic boundary. Where the source epic is claimed (owner:MacBoo), coordinate with that claim first.

| Move | Items |
|---|---|
| Whole epic becomes a child of Integration | computenet-6aj8h (platform gaps: durability half of 1.2 and P2 prereqs), minus the three PAUSE children (259rq, yqt8h stay with deliberate or agora) |
| Integration / P0 | computenet-qp4xo, computenet-nta1 |
| Integration / 1.1 | computenet-g92i (then close as merged) |
| Integration / 1.2 | computenet-8xstm (re-scoped), computenet-n0iaw, computenet-o2aj, computenet-s4n8y |
| Integration / 1.3 | computenet-vzb, 1mbp, 8uv6, amf8l, axifn, g1aua; merge j1f2, d2j2 |
| Integration / P2 | computenet-638io (from KE4), elc, 6qe8, dmwl, u6np, rhhry, hknt0, bv7qb, 4fpyy, xy7w4.5 |
| Integration / P3 | computenet-zlm2, hrp9n, iltfm, 3ylve (merge), computenet-8joqm (from WKB2; or keep in WKB2 and relate) |
| Flagship epic (P4) | computenet-aa6gl, computenet-drz8.2, computenet-98u.3 (or relate), computenet-zw4d0 |
| Keep as their own epics, with a "related" link to Integration | ECO1 (computenet-66m), KE2 (computenet-8x9): claimed, coherent and already on the spine's path |
| Out of product epics, into SDLC | computenet-4zmnd, 6y3ez, ekxxt (from BDS4) |
| Un-defer | computenet-drz8 |
| Close | computenet-cj7 (obsolete). computenet-gb1 AGO2 as superseded by the flagship slice, or leave it POSTPONE |

---

## 6. Proposed sequencing — first ~10 items

1. **File the integration epic and the Phase 0 beads** (matrix script, arch test, inspector DemoShell flag, doc refresh absorbing qp4xo, requirement-id minting after the concordance attribution bug computenet-nta1). This is cheap and makes the rest visible.
2. **Finish the ECO1 ledger bugs** (computenet-4yvsx, 8s74x, fh9cc), then the restart checkpoint (computenet-xbs8t). It is already in flight, kernel-only, and stops before any per-demo wiring.
3. **Fix the journal correctness prereqs**: checkpoint keyed on journal identity (computenet-s4n8y), journal format version (computenet-o2aj), and a spec decision on Effectful+Stateful tail loss (computenet-rhhry). Journaling the GraphSpec should not start on a store that silently loses state.
4. **P1.1 one link primitive** (new bead, absorbing computenet-g92i). It is the narrowest spine piece and unblocks the arch test.
5. **P1.2a/b GraphSpec feature parameters + journaled GraphSpec** (re-scoped computenet-8xstm; decide computenet-n0iaw). This is the heart of the spine.
6. **Un-defer demograph and give it real types** (per-principal keyed slice + collective ranking, computenet-drz8.2). It is a leaf module on :kernel and runs in parallel with 4–5 without contention.
7. **Fix the announced-ref capture bug** (computenet-zlm2). It is independent, security-relevant, and gates the Phase 3 authority work.
8. **P1.3 bootstrap + PeerTransport seam** (new bead, absorbing j1f2 and d2j2 with the wire and iroh link bugs as children), with the ECO1 ledger as an installable component.
9. **P1.2c kernel durable-input checkpoint** (new). Beadsmirror moves onto it, which prepares the Phase 4 candidate source.
10. **File the flagship epic with its P4 children**, and fix the alignment board gate (computenet-aa6gl) in the current demo, since it drives real backlog triage today.

In parallel, at no cost to the spine: the CI-flake set (needed before Phase 2's required lane), deliberate's calibration research, WIR1 vectors, gated concord schema changes and benchmarks.
