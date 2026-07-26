# Per-node composition — orchestration state (live)

Live state for the [PERNODE-TICKETS.md](PERNODE-TICKETS.md) run. Kept current as tickets land.

- **Scope**: `doc/PERNODE-TICKETS.md` (waves W0–W8, lanes α..κ). Design: `COMPOSITION-PERNODE-PLAN.md`. Sequencing: `PERNODE-IMPLEMENTATION-PLAN.md`.
- **Baseline**: `main` @ `809f25c` (prior composition run complete @ `d40e4ad`).
- **Model**: impl + validation subagents both Opus (`model: opus`, the Opus-5 tier via the Agent enum; exact `claude-opus-5` id not selectable through the tool).

## Mechanics

- **Worktree parent**: `/Users/merlijn/Documents/local-projects/computenet-pn-wt/`
- **Impl**: `wt-<TICKET>` on branch `comp/<TICKET>`, cut from latest `main` at wave start.
- **Valid**: `wt-<TICKET>-val` cut from the impl branch.
- **Build/test**: `./gradlew test` (full gate) / narrow `--tests`. Isolated `GRADLE_USER_HOME=$PWD/.gradle-home --no-daemon --console=plain`. macOS has no `timeout`: every test run uses the Bash tool's own `timeout` param (narrow ≤180s, full ≤420s).
- **Merge**: host owns `main` (only worktree with it checked out). Validators validate + touch up + rebase/resolve on their branch. Host merges each READY branch serially (`git merge --no-ff`, files are wave-disjoint so clean), then runs one combined full gate per wave. Never `--amend`; verify tree after each merge.

## Status log

Legend: pending · impl-running · validating · READY · merged · escalated

### Wave 0 — defect surfacing (5 × FRESH, disjoint) — **COMPLETE** (combined gate green @ 6fc8669)
| Ticket | State | Branch | Merged | Notes |
|--------|-------|--------|--------|-------|
| PN-0a | merged | comp/PN-0a | efc3031 | dead-letter via counted `unmatchedDrops`+`onDropped`; control load-bearing; validator READY |
| PN-0b | merged | comp/PN-0b | 0a7f569 | guard widened to Stateful-snapshot OR Effectful-frontier (else broke EffectfulRecoveryTest); validator READY |
| PN-0c | merged | comp/PN-0c | 2211b64 | close() in despawn branch only (not suspend); R13 lag reproduced deterministically; validator READY |
| CP-G1 | merged | comp/CP-G1 | b5109fc | MergeableGroupByCell+MapDelta.merge; demo op=replace-per-key (disjoint ranges); GroupByCell byte-identical; validator READY |
| CP-G2 | merged | comp/CP-G2 | 6fc8669 | NatureVector rides EdgeOpen frame (sparse/forward-compat); Link.kt needed no edit (CP-F3 seam); validator READY |

### Wave 1 — PN-1 (α FRESH) ∥ PN-3a/c (β FRESH) — **COMPLETE** (combined gate green @ c6457ea)
| Ticket | State | Branch | Merged | Notes |
|--------|-------|--------|--------|-------|
| PN-1    | merged | comp/PN-1    | cdc852c | PortRef.of derived at stamp time; ctor param ref→initialRef (shadowing); validator READY |
| PN-3a/c | merged | comp/PN-3ac  | c6457ea | Interest algebra closed; StateRequest.scope; per-instance RetainedFrontiers; Total/Slots bit-identical; validator READY |

### Wave 2 — PN-2 (α CONT) ∥ PN-4 (γ FRESH) ∥ PN-3b (β CONT) — **COMPLETE** (gate green @ 701f0b7)
| Ticket | State | Branch | Merged | Notes |
|--------|-------|--------|--------|-------|
| PN-2  | merged | comp/PN-2  | 6218b02 | ReplayScope thread-local; baselineTo switch + unified Baseline shape DEFERRED to PN-3/PN-6 (validator: legit); ctrl(b) stays green per ticket |
| PN-4  | merged | comp/PN-4  | 8849ca9 | ShardCell Stateful+Replicable; rebuildFrom; non-checkpointed shed-recovery partial-durable (journaled assignment = PN-6); single-host byte-identical |
| PN-3b | merged | comp/PN-3b | 701f0b7 | MapDelta:Scoped mirrors SetDelta.within; covers CP-G1 merge path |

Spec 24-data-cells.md conflicts (3 EOF-appended sections) resolved by union.

### Wave 3 — PN-5 (γ CONT) ∥ PN-9 (ε FRESH) — **COMPLETE** (gate green @ [PN-5 merge])
| Ticket | State | Branch | Merged | Notes |
|--------|-------|--------|--------|-------|
| PN-9 | merged | comp/PN-9 | 6d2b267 | inlet tier chain ADMIT/GATE/ALIGN/ACTIVATE; PullOnOpen/PullServe extracted; large diff (on-link multicast to StreamTo/Replication/demos); Protocols leak fix; validator found+fixed a shopping-demo silent-drop |
| PN-5 | merged | comp/PN-5 | (post-9) | scatter-gather pull. **Reworked**: v1 read co-located shard objects (decorative "bridges"); v2 fans serialized StateRequest over registry::deliver, shards reply baselineTo over reverse bridge — re-validated genuinely distributed. Residuals: scope rides @Transient (Total-only over wire; partial=follow-on) |

### Wave 4 — PN-6+G4 (γ CONT) ∥ PN-10 (ε CONT) — **COMPLETE** (gate green)
| Ticket | State | Branch | Merged | Notes |
|--------|-------|--------|--------|-------|
| PN-6  | merged | comp/PN-6  | (W4) | one linker (shared sliceTo); `routed` deleted, `ledger` scoped (leaderless replay=R1 out of scope); journaled assignment closes PN-4 shed residual; Interest arms now kotlinx-@Serializable (also closes PN-5 @Transient scope). Residual: InstanceSet lattice unit-tested but not wired into runtime router (R13/R1 deferred) |
| PN-10 | merged | comp/PN-10 | (W4) | Link.role (default Consume); expectedLocalEdges counts Consume only; tap/streamTo negotiated=false unflipped (byte-for-byte); validator READY |

### Wave 5 — PN-7 (δ FRESH) ∥ PN-12 (ζ FRESH — THE behavior change) — **COMPLETE** (gate green)
| Ticket | State | Branch | Merged | Notes |
|--------|-------|--------|--------|-------|
| PN-7  | merged | comp/PN-7  | (W5) | covering-subset quorum resolves F2; R13 read-side fence (known-rowless→hold), safety verified 600 seeds; unknown-member window = PN-19 race (documented); defaults byte-identical |
| PN-12 | merged | comp/PN-12 | (W5) | WAVE_PARTICIPATION+INSTANCE_SCOPING refusing axes; CellManifest (disjoint from reconcile); **negotiated-default FLIPPED to true**, demo gate green post-flip; DURABLE spawn = counted diagnostic (not hard refuse — the COLOR principle); INSTANCE_SCOPING e2e untested (rides axis-agnostic reconcile) |

### Wave 6 — PN-8 (δ CONT) ∥ PN-18→PN-13 (ζ CONT bundle) ∥ PN-14 (η FRESH) ∥ PN-11 (solo) ∥ PN-16 (λ research)
| Ticket | State | Branch | Merged | Notes |
|--------|-------|--------|--------|-------|
| PN-8  | pending | comp/PN-8    | | sharded replication end-to-end |
| PN-18 | pending | comp/PN-1813 | | ownership × instance set refusal (bundled w/ PN-13) |
| PN-13 | pending | comp/PN-1813 | | InstanceSetStep in GraphSpec |
| PN-14 | pending | comp/PN-14   | | rolling replicated/partitioned promotion |
| PN-11 | pending | comp/PN-11   | | ParkQueue extraction (mechanical, byte-identical) |
| PN-16 | pending | comp/PN-16   | | research spike (decision/spec paragraph; may or may not merge) |

Only real W6 file collision: NatureNegotiation.kt (PN-8 + PN-18) — resolved union at merge (bb0cc17). **COMPLETE** (gate green @ bb0cc17).
- PN-8 merged (1886ee6→c26473d): overlap-without-merge refused (MERGE_IDEMPOTENCE); control(c) on SetCell (aggregate re-mints identity); board=MAX. Residual: `reconcileOverlap` proven-in-test, not wired into runtime `InstanceSet.assign` (declaration-time enforcement is PN-13).
- PN-18+PN-13 merged (comp/PN-1813→bb0cc17): OWNERSHIP SPSC refusal; InstanceSetStep lowers to N spawns + construction-time formation assignments; DURABLE-journal-less declaration refusal (stricter than PN-12 soft count, consistent).
- PN-14 merged (85a8db7): reuse-ref rebind promotion; partitioned extension documented-not-coded (ShardCell accepted by same path; named test is ReplicatedPromotionTest only).
- PN-11 merged (e1e45ae): ParkQueue unifies 5 sites, byte-identical (596/0 pins).
- PN-16 merged (d30ad1f): decision (B) — static frontier model sufficient, no G-13; doc-only.

### Wave 7 — PN-17 (η CONT) ∥ PN-19 (ι FRESH) — **COMPLETE** (gate green @ [PN-17 merge])
| Ticket | State | Branch | Merged | Notes |
|--------|-------|--------|--------|-------|
| PN-17 | merged | comp/PN-17 | (W7) | leader-fires/follower-suppress (Shadow NoOp) exactly-once across handoff, LeaderMark fencing; suppression verified through real host path. **Reworked**: guard was proven-in-test but unwired → now wired into live `Replication.replicate` (Effectful+Replicable on mesh w/o authority refused at formation) |
| PN-19 | merged | comp/PN-19 | a394d9c | interest-scatter + per-instance park + Stall/Resume covering-quorum shrink; closes PN-7's DEGRADE gap; PN-0c close() = degenerate terminal case; WAIT=today/DEGRADE opt-in |

### Wave 8 — PN-15 (κ FRESH — the evidence join, LAST) — **COMPLETE** (final gate green @ 96512e7)
| Ticket | State | Branch | Merged | Notes |
|--------|-------|--------|--------|-------|
| PN-15 | merged | comp/PN-15 | 96512e7 | evidence graph: bridged + filtered + genuinely sharded(3)-AND-replicated(2) arm over real bridges + manifest assertion (4 natures); all 3 controls diverge (tear/stall/wedge); pair-matrix updated with real test names; no kernel touched; validator READY |

## RUN COMPLETE — all 8 waves / 22 tickets merged into `main` @ 96512e7

Final authoritative gate: `./gradlew test --rerun-tasks` → **BUILD SUCCESSFUL, 49/49 tasks executed** (2026-07-26).

Per-ticket: Opus impl in an isolated worktree (TDD, Bash-timeout-bounded tests, controls that diverge) → Opus validation in the impl worktree (faithfulness + full gate) → host `--no-ff` merge + combined per-wave gate.

**Reworks (2):**
- **PN-5** — v1's scatter-gather pull read co-located shard objects (the "behind bridges" claim was decorative); reworked to fan a serialized `StateRequest` over `registry::deliver` with shards replying `baselineTo` over the reverse bridge; re-validated genuinely distributed.
- **PN-17** — the effect-authority formation guard was correct+unit-tested but wired into no live path; reworked to invoke it at the live `Replication.replicate` mergeable-mesh formation site (Effectful+Replicable w/o authority now refused at real formation).

**Documented residuals (all honest, none blocking; verified by validators):**
- PN-4: non-checkpointed shed-recovery was partial-durable → **closed by PN-6** (journaled assignment).
- PN-6: `InstanceSet` epoch-admission lattice is unit-tested but not wired into the runtime router; leaderless mesh-sourced replay = R1, out of scope (ledger kept scoped, not deleted).
- PN-7: R13 read-side creation fence covers known-rowless members; the entirely-unknown-member premature window is the eventual-membership race → **closed by PN-19**'s DEGRADE Stall/Resume shrink for the recoverable case.
- PN-8: `reconcileOverlap` (MERGE_IDEMPOTENCE) proven-in-test; runtime enforcement is via PN-13's declaration-time `InstanceSetStep.validate` (PN-8 file scope excluded InstanceSet.kt).
- PN-12: DURABLE spawn check is a counted diagnostic, not a hard refusal (a durable-capable cell run volatile is legitimate — the COLOR principle); INSTANCE_SCOPING refusal rides the axis-agnostic reconcile loop but has no dedicated e2e test.
- PN-14: partitioned rolling promotion is documented + accepted by the same code path (ShardCell is Replicable), but only the replicated case has a test (the ticket's named test is ReplicatedPromotionTest only).
- PN-16: decision (B) — static-link frontier model is sufficient; no G-13 multiplex ticket needed.

## Follow-up run (FU-1..FU-3) — **COMPLETE** (combined gate green @ db235ae)

Tickets: [PERNODE-FOLLOWUP-TICKETS.md](PERNODE-FOLLOWUP-TICKETS.md). FU-4 (adapter synthesis) is deliberately NOT run here — it's the collaborative exploration the user is driving (untracked ADR + spike present in the working tree, left untouched).

| Ticket | State | Branch | Merged | Notes |
|--------|-------|--------|--------|-------|
| FU-1 | merged | comp/FU-1 | 60d881d | partial-interest pull crosses the wire — `StateRequest.scope` now `@Polymorphic` (PN-6 had already made Interest wire-serializable); shard narrows to `shardInterest ∩ scope` cross-host; null⇒Total byte-identical; validator READY |
| FU-2 | merged | comp/FU-2 | b74932c | converged-membership barrier closes the unknown-joiner premature-release race; grow-only `members` set on the delivered-watermark companion (transitively gossiped); safety (0/100 torn on, control ~43/100 off) + liveness (releases on convergence) verified; default `key==null` untouched; residual = Byzantine/announce-then-vanish (WAIT's existing contract) |
| FU-3 | merged | comp/FU-3 | db235ae | partitioned rolling promotion test coverage (`PartitionedPromotionTest`, shard-by-shard over real bridges, 100 seeds, both controls diverge); one minimal additive fix `PartitionedShardSet.rebindShard` (repoints the one non-ref-resolved `memberships()` handle); ReplicatedPromotionTest unchanged |

Changelog updated: FU-1/FU-2/FU-3 limitation lines removed/rewritten to "closed". Remaining documented residuals: instance-set epoch lattice unwired (R1), DURABLE-diagnostic-not-refusal, no adapter synthesis (→ FU-4 exploration), frontier decided-as-is (PN-16).

## ADR-1 re-read batch (FU-5..FU-9) — autonomous subset

User added FU-5..FU-9. Running only the non-collaborative build tickets unsupervised: **FU-5, FU-6, FU-8**. NOT run (explicitly EXPLORATORY/COLLABORATIVE/RESEARCH, need user review): FU-7 (delta↔snapshot adapters), FU-9 (confidentiality lattice). FU-4 already phase-1 delivered (no-go), awaiting user review — untracked ADR + spike in working tree left untouched.

| Ticket | State | Branch | Merged | Notes |
|--------|-------|--------|--------|-------|
| FU-5 | pending | comp/FU-5 | | PULL_SERVICE axis — pull-needing inlet onto non-serving producer refuses |
| FU-6 | pending | comp/FU-6 | | single-writer inlet (SPSC mirror) |
| FU-8 | pending | comp/FU-8 | | cycle admission checks damping, not just headedness |
