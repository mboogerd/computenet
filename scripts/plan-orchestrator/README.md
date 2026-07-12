# Plan orchestrator

This runs each `Wn.n` heading in the implementation plan as its own ephemeral,
headless Codex container. Waves are sequential; a wave uses at most three workers.
The host creates worktrees, commits successful edits, rebases, and fast-forwards
`main`. Containers receive one writable worktree and read-only Codex authentication;
Git metadata and the main checkout are deliberately not mounted.
Completion is accepted only after the structured worker result says `completed`,
there is a non-empty diff, the plan itself is unchanged, and `./gradlew test` passes
in a Java 21 validation container. Each work item has an isolated Gradle cache to
avoid cross-worker lock contention. Preserved completed worktrees are resumed after
an interrupted runner rather than deleted.

The worker's `--dangerously-bypass-approvals-and-sandbox` is intentional: Docker is
the outer sandbox. The Docker socket is not mounted. Network is enabled because
Codex needs the API; workers otherwise have full permissions inside their container.

## Preview

```bash
scripts/plan-orchestrator/run-plan.sh --dry-run
```

## Run

Start on a clean `main` checkout with Docker running:

```bash
scripts/plan-orchestrator/run-plan.sh
```

If unrelated local changes are intentionally present, the safety check can be
explicitly bypassed with `ALLOW_DIRTY_MAIN=1`; merges will still stop on conflicts.

Useful controls:

```bash
MODEL=gpt-5.2-codex scripts/plan-orchestrator/run-plan.sh --wave 1
MAX_PARALLEL=2 scripts/plan-orchestrator/run-plan.sh
VALIDATE_COMMAND='./gradlew check' scripts/plan-orchestrator/run-plan.sh
```

Runtime logs, structured results, extracted tickets, and root-cause reports live in
`.codex-orchestrator/`. Worktrees default to the system temporary directory.

An initial failure starts a fresh recovery Codex process in the same worktree. Two
recovery attempts are allowed. Rebase conflicts use the same maximum. Exhaustion
stops the current wave, prevents later waves, preserves the worktree, and writes a
root-cause report with the collected agent summaries and route-forward pointer.
