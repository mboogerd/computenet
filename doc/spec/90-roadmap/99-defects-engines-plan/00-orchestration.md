# 99 — Combined run: inspector v4 completion, confirmed defects, engines E1/E2 entry

**Status**: Living

Runbook for a `claude-sonnet-5` orchestrator session. New tickets live in
`doc/spec/90-roadmap/99-defects-engines-plan/tickets/`; track A tickets live in
`../98-inspector-v4-plan/tickets/`. Read a ticket only when dispatching it.

## Goal

Three tracks to done:

- **Track A — inspector v4 waves 7–11.** Executed exactly per
  `../98-inspector-v4-plan/00-orchestration.md` (waves 7–11, checkpoints
  C7–C11, C-replan-2). That document is authoritative for track A; this plan
  only schedules around it. Done = its own definition of done, including the
  C7 GO/RESIZE/NO-GO branch. **C7 resolved GO on 2026-07-30** — waves 8–11 run
  as written, unresized; no branch of this plan's schedule changes.
- **Track B — confirmed defects.** Four tickets: D-REPLAY (baseline-blind
  quorum recovery, `24-REPLAY-01`), D-COMBINE (wave-coalescing scalar combine,
  `24-OP-COMBINE-01`/`CTL-GF-01`), D-UNION (union-scoped observed remove, the
  shopping silent-no-op remove), D-CONCORD (corpus closure for the first two),
  then D-C12 (RESTART/`ReBaseline` reconciliation). Done = the three defects
  fixed with kernel tests, the corresponding DISPUTES.md entries resolved
  honestly, `CTL-GF-01` no longer a failing sentinel.
- **Track C — engines E1/E2 entry.** Two spec tickets (E1-SPEC, E2-SPEC)
  making 96-plan §E1.1 and §E2.1 normative spec text, then a replan checkpoint
  (R-ENG) that tickets the E1/E2 code spine against the merged spec. R-ENG ran
  2026-07-30 and appended waves C1–C3 (E2-ALIGN; E1-CORE ∥ E2-GATE; E1-REPL ∥
  E2-SUITE), so done for this plan = those waves merged through CC3.
  E1.4–E1.6, E2.6, and E3+ stay with the 96-plan until a later replan.

## Sandbox / isolation

**Explicit decision, not a downgrade:** this run uses the repo's established
worktree harness, identical to track A's live plan
(`../98-inspector-v4-plan/00-orchestration.md` §Sandbox / isolation) — the
repo's Docker harness is retired (`AGENTS.md`). One uniform mechanic across
all three tracks. In full:

- Every ticket runs in its own **git worktree** on branch `ticket/<id>`,
  created under
  `/Users/merlijn/Documents/local-projects/computenet-worktrees/`. Workers act
  freely inside their worktree; the branch is the unit of review. Workers do
  not commit/merge/rebase outside their own branch; the evaluator owns git for
  its ticket (merge authority delegated by this plan).
- Setup per worktree: none beyond `git worktree add` — Gradle wrapper as
  needed.
- Test ports: concurrent sessions squat common ports; any live server must use
  an ephemeral or explicitly chosen non-default port.
- Commit by explicit pathspec only; never `git add -A`; never amend (other
  sessions may share state).
- Implementation tickets run at `effort: xhigh`; evaluators at `high`.
- Workers are dispatched as fresh Claude Code sessions pointed at their ticket
  file, with the worktree as cwd. Session strategy per ticket is in the wave
  tables; "fresh" means no inherited context beyond the ticket.

## Standing rules

- Ticket status is authoritative in the ticket file; mirror it in the wave
  tables. `:concord:docLints` restricts the Status vocabulary:
  `**Status**: Specified — not-started` → `**Status**: Specified — in-progress`
  → `**Status**: Implemented — merged`.
- Merge target is `main`. Merge each ticket as soon as it passes evaluation.
- Before a wave's merges land, run the repo gate: `./gradlew test` (plus
  `npm test` in `inspect/ui/` when the wave touched the FE — track A wave 10).
- Unpredicted file collision between concurrent tickets: serialize — let the
  first merge, rebase the second, record the miss in the ticket report.

**Cross-track claim rules** (the reason this plan exists as one document):

1. `kernel/.../host/ManagedHost.kt` and `kernel/.../data/SetCell.kt` belong to
   track A's `V1C-KERNEL` for the whole of waves 7–11. No track B/C ticket
   touches either file. (D-UNION is written to avoid `SetCell.kt`; if its
   implementer finds it cannot, it stops and reports — that is a replan
   trigger, not a license.)
2. `kernel/.../cell/data/op/**` belongs to track A's `V1C-OPS` during track A
   wave 9. Track B's kernel tickets (D-REPLAY, D-COMBINE, D-UNION) all claim
   files there, so: **do not dispatch track A wave 9 while any B1 ticket is
   in flight.** B1 runs concurrently with track A waves 7–8, which is an ample
   window; if B1 overruns, hold wave 9 until CB1 closes.
3. `concord/**` is single-writer across the whole run: at most one
   concord-editing ticket in flight at any time. The concord writers, in
   order of readiness: D-CONCORD → `V1C-CONCORD` (track A wave 11) → D-C12.
   If wave 11 becomes ready while D-CONCORD is in flight, wave 11 waits; D-C12
   always waits for both. (Under a C7 NO-GO, `V1C-CONCORD` disappears and the
   chain is D-CONCORD → D-C12.)
4. `doc/spec/**` outside this plan's folder: E1-SPEC and E2-SPEC both edit
   `20-dataflow-semantics/24-data-cells.md`, so they are sequenced (E1-SPEC
   first), never parallel. No track A ticket edits `doc/spec/**` outside the
   98 folder, so there is no cross-track spec collision.
5. Track A's own standing rules and addenda
   (`../98-inspector-v4-plan/00-orchestration.md` §Standing rules, §Standing
   rules addenda for waves 7+) remain in force for track A tickets unchanged.

## Failure policy

1. Verification fails → the evaluator repairs in place within the ticket's
   stated scope and re-checks. Repair is the default: rejecting forfeits both
   the implementation and the evaluation spend. The evaluator may only reject
   with a written repair-versus-redo estimate concluding redo is cheaper —
   design-level wrongness, or repair rewriting most of the diff.
2. Design-level wrongness, or repair fails twice, or the implementer stalls →
   re-run at the ticket's escalation tier in a fresh session, handing it the
   evaluator's diagnosis rather than the failed diff.
3. Fails at the escalation tier → stop. Re-split the ticket or hand it back.
   No third retry.

**Orchestrator escalation.** Process problems — an untangleable merge,
contradictory tickets, a wave that no longer matches the repo — spawn a
`claude-opus-5` session, hand it the conflicting tickets + diffs + this plan,
take its decision, continue. Cross-*track* contradictions (a track A ticket
and a track B ticket that turn out to claim the same seam) escalate to
`claude-fable-5` with both plans. Do not improvise a design decision you were
not given; do not stall waiting for a human.

## Interleaving — the one picture

```
Track A (98-plan):  W7 ──C7──► W8 ──C8──► W9 ──C9──► W10 ──C10──► W11 ──C11──► C-replan-2
                     │gate                 ▲ hold until CB1 closed (rule 2)
Track B:            B1 (D-REPLAY ∥ D-COMBINE ∥ D-UNION) ──CB1──► B2 (D-CONCORD) ──CB2──► B3 (D-C12) ──CB3
Track C:            B1 (E1-SPEC) ──CB1──► B2 (E2-SPEC) ──CB2──► R-ENG ──► C1 ──CC1──► C2 ──CC2──► C3 ──CC3
                                                                          ▲ C2 also holds for track A's C9 (rule 6)
```

Track A wave 7 (`V1C-BENCH`, doc-producing) starts immediately, in parallel
with wave B1. Everything else follows the arrows and the cross-track rules.

## Wave B1 — defect fixes + E1 spec · branches from `main @ 16e7eff`

Parallel: D-REPLAY ∥ D-COMBINE ∥ D-UNION ∥ E1-SPEC — file claims disjoint
(`QuorumSetCell.kt`+`PresenceCountCell.kt` vs a new `data/op/` cell file vs
`UnionSetCell.kt`+`data/delta/`+`demo/shopping` vs `doc/spec/**`). Track A
wave 7 runs concurrently (its claim is a doc in the 98 folder).

| Ticket | Nature | Model | Session | Branch | Evaluator | Status |
|---|---|---|---|---|---|---|
| D-REPLAY | `PresenceLanes`/`QuorumSetCell` consult `MessageContext.baseline`; recovered arm state installs instead of vanishing | opus | fresh | ticket/d-replay | opus | Implemented — merged |
| D-COMBINE | Wave-coalescing scalar combine cell (version-buffered, one delta per completed wave) | opus | fresh | ticket/d-combine | opus | Implemented — merged |
| D-UNION | Union-scoped observed remove primitive + `demo/shopping` adoption | opus | fresh | ticket/d-union | opus | Implemented — merged |
| E1-SPEC | 96 §E1.1 becomes normative spec text (§Tagged maps, `TaggedMapDelta`) | sonnet | fresh | ticket/e1-spec | opus | Implemented — merged |

**Checkpoint CB1 — verification.** Fresh evaluator per ticket at the tier in
the table. Kernel tickets: their named tests plus `./gradlew :kernel:test`;
D-UNION additionally `./gradlew :demo:shopping:test`. E1-SPEC:
`./gradlew :concord:docLints` clean, cross-references resolve. Merge to `main`
as each passes; run `./gradlew test` before the wave's merges are declared
closed. CB1 closing unblocks track A wave 9 (rule 2) and wave B2.

## Wave B2 — corpus closure + E2 spec · branches from `main` after CB1

Parallel: D-CONCORD ∥ E2-SPEC — `concord/**` vs `doc/spec/**`, disjoint.
D-CONCORD is the only concord writer in flight (rule 3).

| Ticket | Nature | Model | Session | Branch | Evaluator | Status |
|---|---|---|---|---|---|---|
| D-CONCORD | Bind the coalescing combine in the driver; positive glitch-free assertion on `24-OP-COMBINE-01`; retire `CTL-GF-01` as failing sentinel; author `24-REPLAY-01.yaml`; resolve both DISPUTES entries | opus | fresh | ticket/d-concord | opus | Implemented — merged |
| E2-SPEC | 96 §E2.1 becomes normative spec text (§The observation frontier, absorb-ack rule) | sonnet | fresh | ticket/e2-spec | opus | Implemented — merged |

**Checkpoint CB2 — verification.** D-CONCORD: `./gradlew :concord:check` green,
zero dangling `covers:` ids, zero orphan scenarios, `doc/spec/CONCORDANCE.md`
regenerated not hand-edited, DISPUTES.md entries updated per its own ledger
rules. E2-SPEC: as E1-SPEC. Merge on pass; repo gate before close.

**CB2 closed (PASS).** Both tickets merged. D-CONCORD: `:concord:check` and
`:concord:docLints` green, 0 fatal findings (zero dangling `covers:`, zero
orphan scenarios), `:concord:test -Pconcord.profiles=core,dist,dur` green on
every run of the sweep, `CONCORDANCE.md` regenerated fresh post-merge (the
textual auto-merge against E2-SPEC/V1C-KERNEL was discarded).

*On the Track B "Done" bar — `CTL-GF-01` is no longer a failing sentinel.* Be
precise about which "failing" applies: it is retired as a **gap sentinel** (it
no longer stands guard over an unclosed kernel capability — nothing in the repo
now describes the wave-coalescing scalar combine as missing), which is what the
Track B Done clause names, and that bar is **met**. It remains, by design, a
**deliberately failing `kind: control`**: it asks for the plain non-wave-aligned
`combine-latest` and asserts wave-aligned semantics of it, so it must fail its
own check for `CorpusRunner` to report it PASSED under P7. The wrongness moved
from the lineage (a real gap) into the expectation (a mismatched assertion),
joining `CTL-GOLDEN-01`'s register. Its graph, script and checks are byte-identical
to before; only header/title/narrative changed, so no new flake risk was introduced.

## Wave B3 — RESTART/ReBaseline reconciliation · branches from `main` after CB2

Runs alone (concord single-writer; serialized after `V1C-CONCORD` if track A
wave 11 is in flight — rule 3).

| Ticket | Nature | Model | Session | Branch | Evaluator | Status |
|---|---|---|---|---|---|---|
| D-C12 | Reconcile conflict C-12: land the decided RESTART re-baseline surface (catalog `ReBaselineEmitting` source + driver verb + `21-REBASE-01.yaml`), or produce the honest reconciliation report if the kernel contradicts the decided design | opus | fresh | ticket/d-c12 | fable | Specified — not-started |

**Checkpoint CB3 — verification.** `./gradlew :concord:check` +
`./gradlew :kernel:test` green; if the ticket took its report arm instead of
its implementation arm, the report must name the exact kernel mechanism gap
and update DISPUTES.md/`91-gap-analysis.md` accordingly — an evaluator finding
the report arm was taken to dodge feasible work is a rejection ground.

**Checkpoint R-ENG — replan (track C).** Trigger: E1-SPEC and E2-SPEC merged
(end of CB2; does not wait for B3 or track A). Fresh `claude-fable-5` session,
re-enter the `create-implementation-plan` skill. Inspect: the merged spec text
(20/22 §The observation frontier, 20/24 §Tagged maps), 96-plan §E1.2–E1.3 and
§E2.2–E2.5, the current state of track A (which waves have merged; who owns
`data/op/**` and `host/Observe.kt` right now), and track B's merged defect
fixes (D-COMBINE's coalescing cell is prior art for E2's wave-buffering).
Output: concrete tickets for the E1 spine (E1.2 → E1.3) and E2 spine
(E2.2 → E2.3 → E2.4/E2.5), appended to this plan as waves C1+, scheduled
around whatever track A still has in flight. E1.4–E1.6, E2.6, and E3+ stay
with the 96-plan until a later replan.

**R-ENG ran 2026-07-30** against `main @ 6459c5b` (CB2 closed; track A waves
7–8 merged, wave 9 — V1C-CELLS ∥ V1C-OPS ∥ V4-PEERID — dispatched and in
flight). It re-entered the `create-implementation-plan` skill and read: the
merged spec text (20/22 §The observation frontier and §Completeness over
silent or stuck edges; 20/24 §Tagged maps and the
`SemiJoinCell`/`CombineLatestCell` gating rows), 96-plan §E1.2–E1.3 and
§E2.2–E2.5, the landed kernel seams (`AbsorbAck.kt`, `WaveFrontier.kt`,
`GlitchFree.kt`, `CoalescingCombineCell.kt`, `Emit.kt`,
`cell/observe/Observe.kt`), the wave-9 worktrees, and track B's merged
defect fixes. Verified, per this section's own warning:

- **E2.2 is landed and richer than 96 §E2.2 describes** — `cell.control
  .absorbAck` (CP-A3) + `emitOrAbsorb` (`data/op/Emit.kt`), adopted across
  eleven operators, `OperatorAbsorbAckTest` in place. The genuine residual
  is the two value-equal-swallow cells: `CombineLatestCell` (the divergence
  20/22 flags at `:219-221`) and `LookupJoinCell` (same `MapDiffPublisher`
  shape, unflagged, equally bound by the MUST). Both go to E2-GATE.
- **E2.3's first half is landed** — `cell.consistency.WaveFrontier` (CP-A4;
  now also carrying E3.4 replica-fed gates, PN-7 interest scoping, PN-9
  policy tiers, PN-10 observe-role exclusion), `GlitchFreeCell` delegating.
  The sink half (`AlignedCompositeCell`/`observeAligned`) does not exist
  anywhere; E2-ALIGN builds it. One path correction: the observe machinery
  is `kernel/.../cell/observe/Observe.kt`, not `host/Observe.kt` — 96
  §E2.3's `host/AlignedObserve.kt` is stale the same way; no track A wave
  9–11 ticket claims the `cell/observe` package.
- **E1 is genuinely greenfield** — no `OrMapCell`/`TaggedMapDelta`-shaped
  file exists; `@Contract MapOps` exists for reuse (`MapCell.kt:14-19`), so
  no `gen/` work; the additive payload-registration seam is
  `cell/wire/WireCodec.kt:142`.
- **D-COMBINE's `CoalescingCombineCell` is the load-bearing prior art** for
  both E2-ALIGN and E2-GATE: its KDoc (`:69-95`) records why an installed
  `WaveFrontier` cannot be composed by a coalescing operator and mirroring
  the fold at cell scope is the available composition — both tickets are
  written to mirror, never to modify `WaveFrontier`.

Output: five tickets (below). E1.4–E1.6, E2.6, and E3+ stay with the 96-plan
until a later replan, as specified.

**Cross-track claim rules, appended by R-ENG** (extending rules 1–5 above):

6. **No wave-C ticket claiming `cell/data/**` or `cell/data/op/**` (source
   or test directories) dispatches while track A wave 9 is in flight** —
   the rule-2 discipline extended to track C: `V1C-CELLS` owns
   `kernel/src/test/.../cell/data/` and `V1C-OPS` owns `cell/data/op/**`
   for the duration of wave 9. Wave C2 therefore branches from `main` after
   track A's C9 closes. **`cell/observe/**`, source and test, is claimed by
   no track A wave 9–11 ticket** — that, on its own, is what licenses wave C1
   to dispatch immediately. Two adjacent directories are *not* as clean, and
   the C-wave schedule already clears both: the `cell/replication` **test**
   directory **is** claimed by `V1C-CELLS` (wave 9 — its
   `InstanceSetBoundedReadTest.kt`, beside the `InstanceSet.kt` source it
   also owns), and E1-REPL adds a test there, but E1-REPL is wave C3, after
   C9; the `cell/consistency` test directory is claimed by no track A
   ticket, though `GlitchFreeBridgedDiamondTest.kt` in it must stay green
   and unmodified (`V4-PEERID`'s acceptance), which E2-SUITE honors by
   adding a new file rather than editing one. `cell/wire/WireCodec.kt`
   (E1-CORE's one registration line) is likewise unclaimed by track A —
   `V4-PEERID` owns `cell/wire/Peering.kt`, and both `V4-PEERID` and
   `V4-PILOT` carry explicit no-wire-change acceptance criteria. Rule 1
   (`ManagedHost.kt`, `SetCell.kt`) stays in force for every C ticket
   through track A wave 11; `observeAligned` is an extension function
   precisely so no C ticket edits `ManagedHost.kt`.
7. **Spec-file seam, by section — and it is cross-track, not just inside
   track C.** Two spec files are claimed by more than one live ticket. No
   two claims overlap *within* a file, so none of this forces
   serialization; the rule is that each ticket edits only its named section
   and the later merger rebases.

   | File | Ticket (track, wave) | Section claimed |
   |---|---|---|
   | `20/22-consistency.md` | E2-ALIGN (C, C1) | §The observation frontier — the spec-ahead-of-code note, `:298-301` |
   | | E2-GATE (C, C2) | §Completeness over silent or stuck edges + the G-40 residual, `:214-248` |
   | | D-C12 (B, B3) | the C-12 prose site, `:95-125` |
   | `20/24-data-cells.md` | E2-GATE (C, C2) | §Operator library — the `SemiJoinCell`/`CombineLatestCell` rows, `:184-215` |
   | | E1-REPL (C, C3) | §Tagged maps — the "Design decided, unbuilt" header sentence only, `:236-239` |
   | | D-C12 (B, B3) | §Tag continuity / the tag-algebra rules, `:431-510` |
   | | `V1C-CONCORD` (A, W11) | additive `[24-BOUND-01]` requirement text |

   Note this corrects rule 4's claim that "no track A ticket edits
   `doc/spec/**` outside the 98 folder": `V1C-CONCORD` (wave 11) does, in
   both `21-propagation.md` and `24-data-cells.md`, additively. Its
   requirement-text additions are disjoint from every C-wave section above,
   and wave 11 is the last track A wave, but a C-wave worker finding an
   unexpected `24-data-cells.md` conflict should rebase rather than treat
   it as a contradiction. No C ticket edits the 96-plan or 95-research-plan,
   and no C ticket touches `concord/**` (rule 3 is unaffected).

## Wave C1 — the aligned observation sink · branches from `main @ 6459c5b`

Runs alone, dispatched immediately — concurrent with track A waves 9–10 and
wave B3. Its kernel claim (`cell/observe/**`, source and test) is claimed by
no other live ticket in any track (rule 6). Its one shared file is
`22-consistency.md`, which D-C12 (wave B3) also claims — different sections,
`:298-301` vs `:95-125`, so the later merger rebases (rule 7).

| Ticket | Nature | Model | Session | Branch | Evaluator | Status |
|---|---|---|---|---|---|---|
| E2-ALIGN | `AlignedCompositeCell` + `ManagedHost.observeAligned` — one composite snapshot per settled wave, per-name inlets, mirrored cross-inlet frontier fold (96 §E2.3 second half, `[22-OBS-01/02]`) | opus | fresh | ticket/e2-align | opus | Implemented — merged |

**Checkpoint CC1 — verification.** Fresh opus evaluator. Its named tests plus
`./gradlew :kernel:test` (including the pre-existing observe suite —
`ObserveCellTest` — unmodified-green) and `./gradlew :concord:docLints` (the
ticket trues one 20/22 paragraph). Audit: `WaveFrontier.kt`/`GlitchFree.kt`/
`cell/port/**`/`cell/control/**` untouched; no `cell/data/**` edit of any
kind (wave 9 is likely still in flight); `observeAll`/`CompositeSink`
behavior unchanged. Merge on pass; repo gate `./gradlew test` before the
wave closes. The evaluator carries E2-ALIGN's shipped builder surface into
E2-SUITE's ticket file if it differs from the sketch (the C8 propagation
pattern).

**CC1 closed 2026-07-31 — E2-ALIGN merged (`dea1e58`).** Verified beyond the
named gates: the `ready() = true` mutation fails 4 of 8 tests including the
invariant (so the aligned path itself is under test, not only the control);
the `observeAll` control was re-instrumented and trips on **50 of 50** seeds;
`ArchitectureRatchetTest`'s two added baseline edges (`observe -> control`,
`observe -> protocol`) are real, forced by any cell that folds completeness,
already carried identically by `consistency` and `data`, and cyclic with
nothing (no package imports `observe`). Two evaluator repairs landed on the
branch, documentation only (`b97b40f`). Two facts propagated to wave C3's
E2-SUITE ticket: the shipped builder surface, and the reroute-harness
landmine below.

**Harness landmine for E2-GATE and E2-SUITE (measured at CC1).** `Progress`
absorb-acks are delivered *synchronously on the sender's thread*
(`ProtocolSupport`'s own recorded residual) while a test-rerouted arm's data
is queued. Rerouting an **absorbing** arm while a sibling arm stays fused
therefore breaks spec-31 per-link FIFO *between the two planes on one edge* —
the ack for wave `t+1` overtakes that edge's still-queued wave-`t` delta and
the monotone-`max` watermark releases wave `t` without it. Measured: 50/50
seeds publish mixed composites. Rerouting **both** arms is safe (one host
queue keeps them in relative order), and so is rerouting only a
non-absorbing arm. This is a property of the reroute device, not of any
frontier fold — a real in-process edge is fused and a bridged one carries
both planes through the same `InvocationSink` — and it bites `WaveFrontier`
and `CoalescingCombineCell` identically. Do not read such a failure as a
defect in the cell under test.

## Wave C2 — OR-map core + operator gating · branches from `main` after track A C9

Parallel: E1-CORE ∥ E2-GATE — `cell/data/{OrMapCell,delta/TaggedMapDelta}.kt`
+ `cell/wire/WireCodec.kt` + test `cell/data/OrMapCellTest.kt` vs three named
`cell/data/op/` cells + `cell/data/op/` tests + the 20/22 §Completeness and
20/24 spec rows: disjoint. Dispatch gate: track A C9 closed (rule 6); does
NOT wait for CC1 (E2-GATE builds on the merged frontier substrate, not on
E2-ALIGN).

| Ticket | Nature | Model | Session | Branch | Evaluator | Status |
|---|---|---|---|---|---|---|
| E1-CORE | `TaggedMapDelta` + `OrMapCell` local core: dot-per-key observed-remove, LWW-by-dot-order, atomic re-put, catch-up, additive wire registration (96 §E1.2, `[24-TMAP-01..04]`) | opus | fresh | ticket/e1-core | opus | Specified — not-started |
| E2-GATE | Absorb-ack residual closed (`CombineLatestCell`, `LookupJoinCell`) + opt-in `emitOnFrontier` on `SemiJoinCell`/`CombineLatestCell` (96 §E2.2 residual + §E2.4, `[24-OP-SEMIJOIN-04]`) | opus | fresh | ticket/e2-gate | opus | Specified — not-started |

**Checkpoint CC2 — verification.** Fresh opus evaluator per ticket. E1-CORE:
named tests + `./gradlew :kernel:test`; audit the wire registration is
additive (no frame change) and no replication surface leaked in. E2-GATE:
named tests + `./gradlew :kernel:test` +
`./gradlew :concord:test -Pconcord.profiles=core` (the corpus binds these
operators; `CTL-GF-01`/`CTL-GOLDEN-01` still report PASSED under their
inverted expectations) + `./gradlew :concord:docLints`; audit ungated
defaults byte-identical and any V1C-OPS `BoundedStateful` surface intact.
Merge each on pass; repo gate before the wave closes. The E1-CORE evaluator
carries the shipped cell surface into E1-REPL's ticket; the E2-GATE
evaluator carries the `emitOnFrontier` constructor surface into E2-SUITE's.

## Wave C3 — replication + the acceptance suite · branches from `main` after CC2 (and CC1)

Parallel: E1-REPL ∥ E2-SUITE — `cell/data/OrMapCell.kt` + test
`cell/replication/` vs test `cell/consistency/` only: disjoint. E1-REPL
requires E1-CORE merged; E2-SUITE requires E2-ALIGN and E2-GATE merged.

| Ticket | Nature | Model | Session | Branch | Evaluator | Status |
|---|---|---|---|---|---|---|
| E1-REPL | `OrMapCell` joins the mergeable class: `deltaInlet`/`applyRemote` echo-terminating gossip, C-10 re-origination, pull baseline, `ReBaseline` dead-source fencing (96 §E1.3) | opus | fresh | ticket/e1-repl | opus | Specified — not-started |
| E2-SUITE | Balanced-transfer internal-consistency acceptance suite — invariant at every observed output; `observeAll` and ungated-outer-join failure controls (96 §E2.5, 20/22 §Acceptance benchmark) | opus | fresh | ticket/e2-suite | opus | Specified — not-started |

**Checkpoint CC3 — verification.** Fresh opus evaluator per ticket. E1-REPL:
named tests + `./gradlew :kernel:test`; audit `SetCell.kt`/
`cell/replication/**` sources untouched, both divergence controls trip.
E2-SUITE: the suite + `./gradlew :kernel:test`; the evaluator's sharpest
check is honesty — both failure controls must trip, and a pinned finding
against a merged mechanism is a *valid pass with a report*, while a softened
invariant or replaced seed is a rejection ground. Merge on pass; repo gate
`./gradlew test` before the wave closes. CC3 closing ends track C's code
spine; C-final (below) closes the ledgers.

**Checkpoint C-final — close the ledgers.** Trigger: last wave of all three
tracks merged. Verify and update: DISPUTES.md (D-REPLAY/D-COMBINE/D-C12
entries resolved or honestly re-filed), `doc/demo-findings.md` (F-5 if E2
shipped through C-waves), `91-gap-analysis.md` rows the merged work closes,
`backlog/union-scoped-observed-remove.md` marked implemented, CONCORDANCE.md
regenerated. Track A's C-replan-2 output is read, not acted on, here — its
follow-ups go to the next planning session.

## Kickoff

Fresh `claude-sonnet-5` session in the repo root with permission to create
worktrees and merge. First actions: dispatch track A wave 7 (`V1C-BENCH`, per
the 98-plan) and wave B1's four tickets in parallel; hold track A wave 9
behind CB1 (rule 2).
