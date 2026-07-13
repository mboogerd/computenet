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

### Test timeout discipline (mandatory for every test run)

A dataflow runtime can genuinely deadlock (park/replay, drain-counting, glitch-free
frontier, cyclic feedback), so **no test run may be able to hang indefinitely**.
macOS has no `timeout`/`gtimeout`, so wrap every gradle test invocation in a
watchdog that captures a thread dump before killing, and use `--no-daemon` so no
work survives in a detached daemon:

```bash
run_gated() {  # run_gated <seconds> <gradle args...>
  local secs=$1; shift
  ./gradlew "$@" --no-daemon --console=plain > "$LOG" 2>&1 & local g=$!
  for i in $(seq 1 "$secs"); do kill -0 "$g" 2>/dev/null || return 0; sleep 1; done
  local j; j=$(pgrep -f 'GradleWrapperMain' | head -1)
  [ -n "$j" ] && jstack "$j" > "$DUMP" 2>&1          # thread dump BEFORE killing
  pkill -9 -f 'GradleWrapperMain'; kill -9 "$g" 2>/dev/null
  return 124   # timed out
}
```

Caps: narrow class run ≤ 180s, full `./gradlew test` gate ≤ 300s (the suite
normally finishes in ~10–30s, so either cap is a deadlock backstop, not a budget).
A run that hits the cap is a **ticket failure**: keep the thread dump and escalate
to an Opus subagent with the ticket, diff, test, and dump. Also kill leftover
`:agora:run`/daemon JVMs between waves (`pkill -f GradleDaemon`, `pkill -f AgoraApp`)
— a stale long-running app JVM looks exactly like a hung test.

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
- **Background subagents are unreliable here**: the first W2.1/W2.4/W2.5 batch,
  launched with `run_in_background`, all died with "no progress for 600s (stream
  watchdog did not recover)" having written nothing. Switched to **synchronous
  subagents** (dispatched together in one turn so they still run concurrently,
  but without the background stream watchdog). Same worktrees reused.
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

### Wave 2 — COMPLETE (all merged; authoritative clean gate green @ 12f13d0)
| Ticket | Title | Merged commit | Notes |
|--------|-------|--------|-------|
| W2.1 | Source epochs, generations & ReBaseline (G-42+G-43, C-12) | 1cb3e08 | |
| W2.2 | StateRequest pull + catch-up baseline (G-37+G-38, G-18) | 12f13d0 | added `baseline` beside W2.1's epoch fields in MessageContext. |
| W2.3 | Transitive metadata notices (G-36) | 89a0a5a | prior run. |
| W2.4 | Taps: Observe-role links (G-47) | 08bc281 | |
| W2.5 | Exclusive payloads off the happy path (G-46) | 9cd6218 | |
| W2.6 | Effectful processed-frontier (G-59, C-9) | 7f25320 | edited doc/spec to close C-9. |
| W2.7 | Completeness watermark + typed Stall family (G-40) | ac0f647 | ManagedHost RESTART-branch conflict resolved inline (reBaseline+Resume coexist). |
| W2.8 | Admission vs activation enforcement (G-55) | 27b245b | FanInlet now parks cold sends instead of throwing. |

All Wave-2 workers ran as **synchronous** Sonnet subagents (3 concurrent per batch),
TDD, isolated Gradle homes; no Opus escalation needed. One rebase conflict (W2.7) was
resolved inline by the host.

### Wave 3 — COMPLETE (all merged; authoritative clean gate green @ f789092)
| Ticket | Title | Merged | Notes |
|--------|-------|--------|-------|
| W3.1 | CycleHead & two-tier quiescence (G-41) | e0da3dd | |
| W3.2 | Wire phase: protocols + edge events cross machines (G-35B, G-39B) | 8978731 | additive WireFrame fields. |
| W3.5 | Promotion transaction hardening (G-49) | 5c01125 | |
| W3.3 | Gossip-mesh hardening (G-45) | 4c6f8e1 | added peer unpublish announce. |
| W3.4 | Membranes: Flatten/Mediate exposure (G-52) | 4f9031b | unblocked W4.1, W4.2. |
| W3.6 | GraphSpec identity & remote application (G-51 core) | f789092 | |

No Opus escalation needed; all rebases auto-merged (no manual conflict this wave).

### Wave 4 — COMPLETE (all merged; authoritative clean gate green @ aa47579)
| Ticket | Title | Merged | Notes |
|--------|-------|--------|-------|
| W4.1 | BoundaryPolicy: the three seams (G-54) | a7658c3 | |
| W4.3 | Single-writer replication core (G-44 core) | 46ec0cc | manual/orchestrated failover only. |
| W4.6 | Reflection-free KMP proxies (C-5 completion) | 9f5cd52 | KSP proxies replace JDK dynamic; merged first so peers gate against it. |
| W4.2 | PartitionedCell (G-56, realizes G-24) | dc23b3c | sharded==unsharded over 100 seeds. |
| W4.4 | Promotion policy as data (G-50) | 5df6cb5 | |
| W4.5 | Attention realization details (G-58 core) | aa47579 | |

No Opus escalation needed; all rebases auto-merged (no manual conflict this wave).

## DONE — all waves merged into `main` @ aa47579

Waves 2, 3, 4 complete: 19 tickets landed this run (W2.3 was pre-merged). Every
merge passed an independent full `./gradlew test` gate; three authoritative
`clean test` gates (one per wave) all green. One inline conflict resolution total
(W2.7, ManagedHost RESTART arm); no Opus escalation was required. All implementers
ran as synchronous Sonnet subagents, 3 concurrent per batch, TDD.

Stale prior-run codex worktrees under the system temp dir (`codex/plan-W2.*`) were
left untouched and unused.

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
