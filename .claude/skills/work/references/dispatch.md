# Batch and dispatch tasks


No tasks → a feature breakdown (the step 4 template, features → tasks); retry
once, then park and go to 5f. Otherwise run `next-batch.py <feature-id>
--siblings <N>` into a file and dispatch its `batch`; capacity skips and
directory-claim `warnings` wait for the next round. On an empty batch, act on
`verdict`: `all-closed` or `parked-residue` → 5e, passing `parked` to the
reviewer; `no-tasks` → the breakdown died; `blocked` → run `verify-ready.sh`
first, since the flag goes stale, and dispatch any READY task whose files no live
unit holds. An entry whose work is already committed or merged into the feature
is finished: confirm it and send it to 5c, not to a second implementer. With
`merged_into_feature_suppressed` true, `bd dolt pull` and re-read its
`comment_count`: non-zero means another machine merged it → 5c. No `model` → use
`luna` and stamp it.

**Capacity.** Before every `Agent` dispatch in this skill, breakdowns and reviewers
included, read `next-batch.py --capacity --siblings <N>` and follow its advice. Then
bound the agent with `echo "$(date -u +%s) <minutes>" > <scratch>/dispatched-<id>`,
deleted on its notification: 30 for a one-file fix or a task review, 20 for a probe,
else 60. At most one live agent runs the repo-wide `./gradlew test`; the others scope
their gate to touched modules. Go under the cap when results depend on wall-clock
waits. Agents `slot-elapsed.sh` flags OVER, or dying with no side effects →
[recovery.md](recovery.md), "Stalled agents and load".

**Make each bead true before dispatch** — reviewers score its text and the
implementer builds on it literally. Run [pre-dispatch.md](pre-dispatch.md)'s
checklist (acceptance placement, load-bearing claims, files claim, `AMENDS`) on each.

Claim, record, attach — one command per call, timeout at least 300s:

```bash
.claude/skills/work/scripts/claim-item.sh <task-id>
```

```bash
bd update <task-id> --set-metadata worktree=<worktree-root>/<task-id> --set-metadata branch=task/<task-id>
```

```bash
.claude/skills/work/scripts/ensure-worktree.sh <worktree-root>/<task-id> task/<task-id> <feature-branch>
```

`<worktree-root>` is `computenet-worktrees` beside the main checkout. Under a
closed epic, push after the claim. The `ensure-worktree: base commit` stderr line
is the task's base. Dispatch the batch in one message, without
`isolation: "worktree"` (you own the worktree):

```
Agent({
  description: "Implement <task-id>",
  model: "<metadata.model>",
  run_in_background: true,
  prompt: `Implement beads task <task-id>; it is claimed for you.
Worktree <task-worktree>, branch task/<task-id>; base commit (cut from, not a diff baseline): <sha> <subject>.
Diff your work against git merge-base <feature-branch> HEAD.
Read <task-worktree>/.claude/skills/work/references/agent.md, then <task-worktree>/.claude/skills/work/references/implement.md.
Read the bead: .claude/skills/work/scripts/bead.sh -C <main-checkout> <task-id>; its feature's design: bead.sh -C <main-checkout> <feature-id> -r '.design'; comments: bd -C <main-checkout> comments <task-id> --json.
Change only files in metadata.files. If the acceptance needs another, comment the file and clause on the bead at once and keep working inside the claim.
Tracker writes: <cross_bead, or "only this bead and items you create">.
Gate: <"the repo-wide ./gradlew test" | "scope to <modules>; the PR's required checks give repo-wide evidence">.
You may commit on your task branch; do not push, merge, rebase or switch branches.
<resume framing or hypotheses, if any>`
})
```

`sonnet` goes through that `Agent` call. `luna`/`sol` — and any Sol reviewer —
run the same prompt, plus "You run under Codex: read agent.md "Under Codex";
your scratch root is <scratch>", written to `<scratch>/<id>.prompt`, as a Bash
call with `run_in_background: true` (its exit is the completion notification).
It must start with `codex exec`, which the permission rule allows:

```bash
codex exec -C <task-worktree> --ephemeral -s workspace-write -c sandbox_workspace_write.network_access=true --add-dir <main-checkout>/.git --add-dir <main-checkout>/.beads --add-dir ~/.gradle --add-dir <scratch> -m gpt-5.6-<luna|sol> -c 'model_reasoning_effort="xhigh"' -o <scratch>/<id>.last.md - < <scratch>/<id>.prompt > <scratch>/<id>.log 2>&1
```

Its report is `<scratch>/<id>.last.md`; `<id>.log` is the transcript and stays
unread. Proven 2026-09-29 under that sandbox: commits in a worktree, `bd` reads,
network, and a KSP build with tests. Continuing a Codex agent is a fresh
dispatch framed as a resume.

**While agents run**, read progress only from notifications, bead comments, and
`git log`/`status` in their worktrees. At each notification, read the comments of
every live implementer: a report that its claim is too narrow → widen it if the
file is outside every live claim (then tell the agent, or carry it in its
resume); otherwise leave that file to a later batch and say so. An implementer's
result must state DONE, PARTIAL or BLOCKED; anything else is not a report —
continue that agent before acting. **On batch completion:** fix `metadata.files`
for files touched outside the claim; a parked question is one task, not the
feature; DONE → 5c.
