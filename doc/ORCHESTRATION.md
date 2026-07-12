# Plan orchestration state

Live state for the wave-based bug-fix plan. **Keep this current**: update the
status table and notes as each ticket lands, merges, escalates, or blocks. A
fresh orchestrator or subagent should be able to resume from this file alone
without re-discovering anything below.

Last updated: 2026-07-12.

## What is being run

The bug-fix plan is a set of `Wn.m` work items ("tickets"). Waves run
**sequentially**; within a wave, independent tickets run **concurrently, max 3**.
Each ticket is implemented test-first (TDD), gated by the full test suite, then
fast-forward-merged into `main`.

- **Plan / ticket sources**
  - Extracted per-ticket briefs: `.codex-orchestrator/items/Wn.m.md` (self-contained ticket text — spec citations, spec change, depends, implement, test).
  - Full implementation plan (authoritative prose): `doc/spec/90-roadmap/94-implementation-plan.md`.
  - Cross-feature decisions: `doc/spec/90-roadmap/93-feature-interactions.md`.
  - Glossary: `doc/spec/00-foundations/03-glossary.md`.
  - Research-gated / excluded corners: `doc/spec/90-roadmap/95-research-plan.md`.
- **Contributor contract**: `AGENTS.md` (invariants, repo map, conventions, the "Headless orchestration contract").

## Merge model

- **Target branch**: `main`.
- Per ticket: a git worktree + branch `plan/Wn.m` is cut from current `main`.
- The subagent edits only inside its worktree and does **not** touch git.
- On success the host (this orchestrator) runs an **independent** full test gate
  in the worktree, then rebases the branch onto current `main` and
  **fast-forwards** `main`. Rebase because peers in the same wave may have merged
  meanwhile. Conflicts → escalate.

## Build / test commands

Java toolchain 21, Gradle wrapper. Full suite is **fast (~30s)**.

```bash
# narrowest first
./gradlew :kernel:test --tests 'fully.qualified.TestName'
./gradlew :gen:test :gen-test:test
./gradlew :wire:test :demo:test :agora:test
# full repository gate (the merge gate)
./gradlew test --console=plain
```

Subagents run in parallel worktrees, so each **must** use an isolated Gradle
home to avoid lock contention:

```bash
GRADLE_USER_HOME=$PWD/.gradle-home ./gradlew test --console=plain
```

**Baseline**: `main` @ `89a0a5a` is green (`BUILD SUCCESSFUL`, full `./gradlew test`).

## Capability constraints (important)

- **No Docker for Claude subagents.** The Agent tool can only isolate subagents
  via **git worktrees** (or a remote env), not Docker containers. The prior run
  used Codex-in-Docker (`scripts/plan-orchestrator/run-plan.sh`, a *separate*
  tool); that path is not used here. Subagents run host-side, each pinned to one
  worktree. Isolation + parallel-safety come from the worktree + isolated Gradle
  home, matching the AGENTS.md "host owns git, worker edits a mounted worktree"
  contract.
- Implementers are **Sonnet**; failed tickets escalate to **Opus** in the same
  worktree with full failure context.
- **GOTCHA: `.codex-orchestrator/` is gitignored** → it does NOT exist in fresh
  `plan/Wn.m` worktrees. Never point a subagent at `.codex-orchestrator/items/*`;
  **embed the full ticket text inline in the dispatch prompt**. The tracked
  `doc/spec/` tree and `AGENTS.md` ARE present in every worktree for detail.
  (Read the item briefs from the primary checkout when composing prompts.)

## Prior run history (Codex-in-Docker)

- Waves 1 merged W1.1–W1.7. Wave 2: W2.3 merged.
- Codex left worktrees under
  `/private/var/folders/.../T/computenet-plan-worktrees/` on branches
  `codex/plan-W2.*`:
  - `codex/plan-W2.1`, `codex/plan-W2.2`: marked "ready" (1 commit ahead of `main`,
    branched from W1.7). **Not merged as-is** — `codex/plan-W2.1` deletes
    `ProtocolRelayTest.kt` and guts `Protocols.kt`, i.e. it reverts W1.5's merged
    work, violating the contract. Re-run fresh via TDD instead.
  - `codex/plan-W2.4`–`W2.8`: clean at `main` HEAD, no salvageable work (failed).
- These stale branches/worktrees are left in place but unused. New work uses
  `plan/Wn.m` branches cut from current `main`.

## Status

Legend: pending · in-progress · gate-green · merged · escalated(opus) · blocked · failed

### Wave 1 — merged
W1.1–W1.7 all merged (prior run).

### Wave 2
| Ticket | Title | Status | Branch | Notes |
|--------|-------|--------|--------|-------|
| W2.1 | Source epochs, generations & ReBaseline (G-42+G-43, C-12) | in-progress | plan/W2.1 | context+host. Sonnet. |
| W2.2 | StateRequest pull + catch-up baseline (G-37+G-38, G-18) | blocked | plan/W2.2 | blocked by W2.1 (shared MessageContext epoch/baseline fields). |
| W2.3 | Transitive metadata notices (G-36) | merged | — | prior run @ 89a0a5a. |
| W2.4 | Taps: Observe-role links (G-47) | in-progress | plan/W2.4 | own. Sonnet. Depends W1.3 (merged). |
| W2.5 | Exclusive payloads off the happy path (G-46) | in-progress | plan/W2.5 | own+host. Sonnet. |
| W2.6 | Effectful processed-frontier (G-59, C-9) | pending | plan/W2.6 | host. |
| W2.7 | Completeness watermark + typed Stall family (G-40) | pending | plan/W2.7 | glitchfree. Depends W1.7+W2.3 (merged); stub single-hop first. |
| W2.8 | Admission vs activation enforcement (G-55) | pending | plan/W2.8 | link. |

### Wave 3 — not started
W3.1–W3.6 (`.codex-orchestrator/items/W3.*.md`).

### Wave 4 — not started
W4.1–W4.6 (`.codex-orchestrator/items/W4.*.md`).

## Intra-wave dependencies / coordination

- **W2.2 after W2.1**: both touch `MessageContext`; the `baseline: TagFrontier?`
  field should land with W2.1's epoch fields to avoid churn. Merge W2.1 first.
- W2.4 depends on W1.3 (Borrowed projection generation) — merged.
- W2.7 depends on W1.7 (floors) + W2.3 (notice transport) — merged; may stub
  single-hop first.

## Escalation protocol

A ticket that fails its gate (or whose rebase conflicts) is handed to an **Opus**
subagent in the **same worktree**, briefed with: the ticket text, what the Sonnet
agent tried, the exact failing test/compile output, and the branch. It diagnoses
and fixes; on green the host merges as usual.
