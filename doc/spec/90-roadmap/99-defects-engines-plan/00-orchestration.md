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
  (R-ENG) that tickets the E1/E2 code spine against the merged spec. Done for
  this plan = spec merged + R-ENG has appended the next tickets.

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
Track C:            B1 (E1-SPEC) ──CB1──► B2 (E2-SPEC) ──CB2──► R-ENG (replan) ──► C-waves [intent only]
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
| D-CONCORD | Bind the coalescing combine in the driver; positive glitch-free assertion on `24-OP-COMBINE-01`; retire `CTL-GF-01` as failing sentinel; author `24-REPLAY-01.yaml`; resolve both DISPUTES entries | opus | fresh | ticket/d-concord | opus | Specified — not-started |
| E2-SPEC | 96 §E2.1 becomes normative spec text (§The observation frontier, absorb-ack rule) | sonnet | fresh | ticket/e2-spec | opus | Specified — not-started |

**Checkpoint CB2 — verification.** D-CONCORD: `./gradlew :concord:check` green,
zero dangling `covers:` ids, zero orphan scenarios, `doc/spec/CONCORDANCE.md`
regenerated not hand-edited, DISPUTES.md entries updated per its own ledger
rules. E2-SPEC: as E1-SPEC. Merge on pass; repo gate before close.

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

## Wave C1+ — engines code spine [intent only, not yet ticketed]

E1: `TaggedMapDelta` + `OrMapCell` core, then replication (96 §E1.2–E1.3).
E2: **the 96-plan overstates the remaining work** — ticket-writing found the
absorb-ack helper (§E2.2; `kernel/.../cell/control/AbsorbAck.kt`, adopted
across the operator suite, `OperatorAbsorbAckTest`) and the `WaveFrontier`
extraction (§E2.3 first half; `kernel/.../cell/consistency/WaveFrontier.kt`,
`GlitchFreeCell` delegates to it) both already landed via the composition run
(CP-A3/CP-A4). What remains of E2 code: the aligned multi-view sink
(`AlignedCompositeCell`/`observeAligned`, §E2.3 second half), frontier-gated
emission (§E2.4), the balanced-transfer acceptance suite (§E2.5). R-ENG must
re-verify this delta against the repo before writing tickets; the 96-plan
sections remain the content source for what is genuinely left.

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
