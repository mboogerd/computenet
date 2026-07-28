# Orchestration plan — audit 2026-07-28 remediation

Runbook for a Sonnet 5 orchestrator session. Tickets live in
`doc/remediation/tickets/` (T13–T24; T01–T12 are the previous run, inert).
Read a ticket only when dispatching it. Design source:
`doc/remediation/AUDIT-2026-07-28.md` (work packages W1, W3–W6; W2 landed with
the audit). Finding ledger: `doc/architecture-decisions.md` — checkpoint
C-final updates its Status column.

## Goal

Land the audit's five work packages: CI that actually gates (W1), a re-trued
record (W3), an executable inspector contract (W4), kernel observer seams
(W5), and inspector hygiene (W6). Done = all tickets merged to `main`, the
repo-wide gate green in CI on `main`, and `doc/architecture-decisions.md`
statuses updated with a fresh audit marker.

## Sandbox

Every subagent runs in a Docker sandbox with free rein inside it.

- Image: `eclipse-temurin:21-jdk` (T23 may instead use `node:22-bookworm` —
  its verification is npm-side only)
- Mounts: its own worktree at `/work` (rw); `doc/remediation/tickets/` (ro);
  a shared Gradle cache volume at `/root/.gradle` (rw) to avoid cold
  dependency downloads per ticket
- Network: egress allowed (Gradle/npm dependency resolution)
- Setup: `apt-get install -y git 2>/dev/null || true; cd /work && ./gradlew help -q`

Agents do not ask for confirmation inside the sandbox. The container is
disposable and the branch is the unit of review.

If Docker is unavailable on the host, fall back to plain worktrees on the
host (the previous remediation run's mode per AGENTS.md); the rest of the
plan is unchanged.

## Standing rules

- One worktree per ticket on `ticket/<id>`. The orchestrator owns the Git
  lifecycle (AGENTS.md multi-agent discipline): workers never commit, merge,
  or switch branches; the orchestrator commits by pathspec against the
  ticket's file claim and verifies the tree.
- Ticket status is authoritative in the ticket file; mirror it in the wave
  tables here.
- Merge target is `main`; merge each ticket as soon as it passes evaluation.
- Before a wave's merges land, run the repo-wide gate: `./gradlew test` (this
  includes the three audit guardrails, `concordanceGate`, and `docLints`).
  After T13 merges, a green CI run on `main` is also part of every wave gate.
- Guardrail amendment policy (headers in `DemoSurfaceAllowlistTest`,
  `ModuleInventoryTest`, `ObservationsCompletenessTest`): a failing guardrail
  is evidence the change is wrong; tickets may only *shrink* allowlists.
  Widening one requires orchestrator escalation, not an edit.
- No `concord/` edits anywhere except T15's `DISPUTES.md` entry and T16's
  lint change.
- Kernel API additions (T17, T18, T21) follow the accessor sign-off
  discipline: the ticket's completion report records the public-surface
  delta and its rationale, and the evaluator checks it against the ticket's
  stated contract.
- Unpredicted file collision between concurrent tickets: serialize. Let the
  first merge, rebase the second, record the miss in the ticket's report.

## Failure policy

1. Verification fails → evaluator fixes small gaps itself and re-checks.
2. Fails twice, or the implementer stalls → re-run at the ticket's escalation
   tier in a fresh session.
3. Fails at the escalation tier → stop. Re-split the ticket or hand it back.
   No third retry.

**Orchestrator escalation.** Process problems — an untangleable merge,
tickets that contradict each other, a wave that no longer matches the repo, a
guardrail that would need widening — spawn an Opus 5 session, hand it the
conflicting tickets/diffs plus `doc/remediation/AUDIT-2026-07-28.md`, take
its decision, continue. Do not improvise a design decision; do not stall
waiting for a human.

## Wave 1 — gates, record, and independent seams · branches from `main` (after this plan is committed)

Parallel: T13, T14, T15, T17, T18, T19, T20 — file claims disjoint.

| Ticket | Nature | Model | Session | Branches | Evaluator | Status |
|---|---|---|---|---|---|---|
| T13 | CI revival: classify two-JVM failures, tag multi-JVM tests, fast + serial lanes | sonnet | fresh | ticket/T13 | opus | not-started |
| T14 | Orientation docs re-true: `:inspect` into ARCHITECTURE/AGENTS/README, drop G2 exception | sonnet | fresh | ticket/T14 | sonnet | not-started |
| T15 | Spec integrity: WritePosture rewrite + G-markers, stale citations, DISPUTES entry | sonnet | fresh | ticket/T15 | opus | not-started |
| T17 | FanOutlet payload-agnostic observe + hot-path COW iteration fix | opus | fresh | ticket/T17 | opus | not-started |
| T18 | snapshotOf hardening: cancellation + single-threaded-scheduler safety | opus | fresh | ticket/T18 | opus | not-started |
| T19 | Inspector security posture: loopback bind, CORS split, falsified KDoc | sonnet | fresh | ticket/T19 | opus | not-started |
| T20 | Inspector contract executability: strict-decode fixture test, fold 3 blind cells | sonnet | fresh | ticket/T20 | sonnet | not-started |

**Checkpoint C1 — verification.** Fresh evaluator session per ticket at the
tier in the table. Judge against the ticket only. Merge to `main` on pass,
then run the repo-wide gate once for the wave.

**Checkpoint C1-replan (conditional).** Trigger: T13 classifies the two-JVM
CI failures as a real kernel/wire race rather than runner contention (the
audit's stated abandon-trigger). Then: pause T13 only (the rest of wave 1 is
unaffected), spawn a Fable session with T13's diagnosis, the failing seeds,
and AGENTS.md's failing-seed rule; it re-enters `create-implementation-plan`
and appends correctness ticket(s) here. Do not buy green by raising timeouts.

## Wave 2 — dependents · branches from `main` after C1

Parallel: T16, T21, T22, T23 — file claims disjoint. Sequencing reasons:
T16 needs T15's citation fixes merged (the tightened lint would fail on
them); T21 shares `InspectorModel`/`InspectorServer` with T19/T20's wave;
T22 needs T13's serial multi-JVM lane; T23 edits `ci.yml` after T13.

| Ticket | Nature | Model | Session | Branches | Evaluator | Status |
|---|---|---|---|---|---|---|
| T16 | DocLints: resolve package pointers to declared types | sonnet | fresh | ticket/T16 | sonnet | not-started |
| T21 | LocationRegistry notification seam; retire the inspector's 1 Hz shadow sweep | opus | fresh | ticket/T21 | opus | not-started |
| T22 | Two-JVM inspector topology assertion in `:demo:shopping` | sonnet | fresh | ticket/T22 | sonnet | not-started |
| T23 | npm CI job for `inspect/ui` (+ agora/ui if runnable) + Node pin | haiku | fresh | ticket/T23 | sonnet | not-started |

**Checkpoint C2 — verification.** As C1. T21's evaluator additionally runs
`./gradlew :wire:test :inspect:test` before the repo gate — the registry
hook-firing change crosses the wire path.

## Wave 3 — inspector hygiene · branches from `main` after C2

Single ticket; consumes T21's deletions.

| Ticket | Nature | Model | Session | Branches | Evaluator | Status |
|---|---|---|---|---|---|---|
| T24 | InspectorModel/Server hygiene: reconcile residue, routing split, tick list | sonnet | fresh + handoff from T21's report | ticket/T24 | opus | not-started |

**Checkpoint C-final — close the ledger.** Trigger: T24 merged. Fresh Sonnet
session: update `doc/architecture-decisions.md` Status column for every
accepted finding this run resolved, append the new audit marker (the merge
commit of the last wave), and verify each guardrail allowlist shrank as its
ticket claimed (`ObservationsCompletenessTest.knownBlind` empty after T20,
`ModuleInventoryTest.documentedExceptions` empty after T14). Then run the
`improve-architecture` skill in incremental mode from the new marker as the
closing verification; file anything it surfaces as findings for the next
cycle, not as new tickets in this run.

## Not ticketed (intent only)

- `Canvas.tsx` per-overlay extraction (audit W6.4): trigger is a *scheduled*
  FE milestone, none exists. The replanner picks this up if FE work is
  scheduled before this run closes.
- `LocationRegistry.Remote.sink` `.proxy` type-leak projection: stays batched
  with the deferred `InstanceIndex`/`DeliveryHold` extraction
  (`doc/remediation/COVERAGE.md` ⏸ row), per the audit.
