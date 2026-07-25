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

### Wave 3 — PN-5 (γ CONT) ∥ PN-9 (ε FRESH)
| Ticket | State | Branch | Merged | Notes |
|--------|-------|--------|--------|-------|
| PN-5 | pending | comp/PN-5 | | scatter-gather pull |
| PN-9 | pending | comp/PN-9 | | policy tiers on inlets; policy lists on outlets |
