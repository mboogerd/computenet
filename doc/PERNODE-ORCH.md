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

### Wave 0 — defect surfacing (5 × FRESH, disjoint)
| Ticket | State | Branch | Merged | Notes |
|--------|-------|--------|--------|-------|
| PN-0a | pending | comp/PN-0a | | dead-letter frontier silent drop |
| PN-0b | pending | comp/PN-0b | | checkpoint refuses non-Stateful journal |
| PN-0c | pending | comp/PN-0c | | WatermarkCell.close() into evict/unpublish |
| CP-G1 | pending | comp/CP-G1 | | mergeable aggregates |
| CP-G2 | pending | comp/CP-G2 | | nature vectors cross the wire |
