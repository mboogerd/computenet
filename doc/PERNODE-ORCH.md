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

### Wave 1 — PN-1 (α FRESH) ∥ PN-3a/c (β FRESH)
| Ticket | State | Branch | Merged | Notes |
|--------|-------|--------|--------|-------|
| PN-1    | pending | comp/PN-1    | | replay-stable port identity |
| PN-3a/c | pending | comp/PN-3a-c | | interest closes; StateRequest scope + vector since |
