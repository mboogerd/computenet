---
name: work
description: Run one unattended beads work session — claim an epic, break it into features and tasks via Fable subagents, and implement each feature in its own worktree, branch, and draft PR with its tasks running as parallel commits. Safe for two machines running it concurrently on a schedule. Use when a cron/routine starts a work slot, or the user says "/work", "work the queue", "pick up beads work".
---

# /work

One session = one epic, worked until it's dry or the budget's gone.

You are the orchestrator, not the implementer. Every item — including
breakdowns — goes to a dispatched subagent. Resist doing one inline "just
this once, it's quick": that's how a multi-hour session's context balloons
and starts drifting.

## 1. Identity

`BEADS_ACTOR` (falling back to `git config user.name`) must be **unique per
machine** — it's how every race check tells two machines apart. If it can't
be resolved, stop; don't guess a machine identity. Subagents inherit it from
the environment.

## 2. Budget

```bash
SESSION_START=$(date +%s)
```

Target **4h45m (17100s)**, or `(slot length − 15m)` if the routine names
one. Check `$(( $(date +%s) - SESSION_START ))` before each dispatch; at or
over, go to **Finalize**. Overrunning is wasteful, not dangerous.

## 3. Sync, then claim one epic

```bash
git fetch origin main
bd dolt pull
```

Resume an epic this machine already owns, else claim the highest-priority
unclaimed one:

```bash
bd list --type=epic --status=in_progress --assignee="$BEADS_ACTOR" --json
bd ready --type=epic --claim --json
```

A fresh claim must be pushed and confirmed before anything else — follow
[references/claim-sync.md](references/claim-sync.md) — and tagged per
[references/epic.md](references/epic.md).

Nothing claimable → report and stop.

`theme` = that epic id. **It never changes for this session.** If its queue
goes dry the session ends; it does not go find another epic. That, plus the
epic staying `in_progress` while claimed, is what keeps two concurrent
sessions off each other's subtrees.

## 4. Ensure the epic has features

Breakdown state is the tree itself — no children means it needs one. (That
also makes a half-finished breakdown self-healing: it just runs again.)

**Break down one level, one item, at a time.** This step produces features
and stops. Features get decomposed into tasks only when one is actually
picked up (5b), never in advance. Planning is deliberately staggered so each
breakdown is written against state that has really landed rather than
against assumptions about work that hasn't happened yet. Don't batch
breakdowns to "get planning out of the way" — that trades the whole benefit
for a little wall-clock.

```bash
bd list --parent=<theme> --json
```

Empty → dispatch a breakdown and nothing else this round:

```
Agent({
  description: "Break down epic <theme>",
  model: "fable",
  run_in_background: true,
  prompt: `Read .claude/skills/work/references/epic.md and follow it to break
epic ${theme} into features. It is already claimed — skip claiming.
Report the feature ids created.`
})
```

## 5. Work features

```bash
bd ready --parent=<theme> --json     # --parent matches all descendants
```

Empty → go to **5f**.

Take the **first unblocked feature**, highest priority first. `bd ready`
already excludes anything with an open blocker, so the first `feature` row
it returns is the one to work.

**A feature is the unit of integration**: its own worktree, branch, and
draft PR, into which reviewed task branches are merged.

**A task is its own worktree and branch**, cut from the feature branch,
merged back once it passes review.

**Work exactly one feature at a time** — the parallelism lives in its tasks.
When a feature can't progress, move on to another rather than adding it
alongside.

### 5a. Set up the feature

Claim it (claim-sync.md), then check for a worktree from an earlier session:

```bash
bd show <feature-id> --json     # .metadata.branch / .worktree / .pr
```

**`metadata.branch` exists** → reuse it; its draft PR holds real work, never
start over:

```bash
git worktree add <worktree-path> <branch> 2>/dev/null || true
git -C <worktree-path> pull --ff-only 2>/dev/null || true
```

**Otherwise** create and record it *before* dispatching, so a crash
mid-feature is recoverable:

```bash
git worktree add ../computenet-work/<feature-id> -b feature/<feature-id>-<slug> origin/main
bd update <feature-id> \
  --set-metadata branch=feature/<feature-id>-<slug> \
  --set-metadata worktree=../computenet-work/<feature-id>
bd dolt push
```

### 5b. Break down, then batch tasks

No tasks yet → dispatch one Fable breakdown, nothing else this round:

```
Agent({
  description: "Break down feature <id>",
  model: "fable",
  run_in_background: true,
  prompt: `Read .claude/skills/work/references/feature.md and follow it to
break feature ${id} into tasks. It is already claimed — skip claiming.
Report the task ids created.`
})
```

Otherwise build a batch from its ready tasks, highest priority first:

- Add a task only when its `metadata.files` claim is **disjoint** from every
  task already in the batch. Overlap → leave it for a later round; two
  branches editing the same file merge into a conflict later.
- No `files` metadata → can't be scheduled safely. Run it alone and comment
  that the claim was missing, so the breakdown gets fixed.

Claim them all (claim-sync.md). Then, for each, give it a worktree of its
own cut from the feature branch — or reuse the one it already has, so a
resumed task keeps the context it built up:

```bash
bd show <task-id> --json                # .metadata.worktree / .branch
# resume:
git worktree add <task-worktree> <task-branch> 2>/dev/null || true
# or first time:
git -C <feature-worktree> push -u origin <feature-branch>   # first task only
git worktree add ../computenet-work/<task-id> -b task/<task-id> <feature-branch>
bd update <task-id> \
  --set-metadata worktree=../computenet-work/<task-id> \
  --set-metadata branch=task/<task-id>
```

Dispatch the batch in one message:

```
Agent({
  description: "Implement <id>",
  model: <task's metadata.model — "sonnet" or "opus">,
  run_in_background: true,
  prompt: `You are implementing beads task ${id}, already claimed — do not
claim another. Work ONLY in your own worktree at ${taskWorktree}, on branch
${taskBranch}. Do not touch the main checkout, the feature worktree, or
another task's worktree.
Read it: bd show ${id} --json
Then read .claude/skills/work/references/task.md and follow it.
Stay inside your metadata.files claim — other tasks are running on sibling
branches and will merge into the same feature branch.
If you won't finish within ~45-60 minutes, stop at a clean point and leave
the task in_progress with a bd comment saying what's done and what's left.
Your worktree is preserved, so a later session resumes you here rather than
starting over.
Report back: the task id, the outcome, and the files you actually touched.`
})
```

Don't set `isolation: "worktree"` — you are creating and recording the
worktree yourself, precisely so it survives the agent and can be resumed.

**On batch completion** (wait for the whole batch; a staggered re-batch
computes overlap against a moving set):

- **Files touched outside a claim** → fix that task's `files` metadata
  before the next batch.
- **A task parked a question** → that's one task, not the feature. Keep
  batching the feature's other ready tasks; if none remain, move on (5f).
  Never let one parked question stall an epic with runnable work.
- **A task reported done** → review and merge it (5c).

### 5c. Review each task, then merge it

Every completed task is reviewed on its own branch before it reaches the
feature branch. Dispatch one reviewer per completed task, concurrently, at
the task's own model or above — never the agent that wrote it:

```
Agent({
  description: "Review task <id>",
  model: <task's metadata.model>,
  run_in_background: true,
  prompt: `Read .claude/skills/work/references/review-task.md and follow it
to review beads task ${id} against its own acceptance criteria.
Worktree: ${taskWorktree}  ·  Branch: ${taskBranch}
Repair what you can within the task's scope. Report pass or fail, what you
repaired, and — on fail — exactly what is missing.`
})
```

**Merge the passes yourself, one at a time.** Reviewers must not merge:
concurrent merges into one feature branch race each other.

```bash
git -C <feature-worktree> merge --no-ff <task-branch> -m "Merge <task-id>"
git -C <feature-worktree> push
git worktree remove <task-worktree>          # merged; nothing left to resume
bd close <task-id>
bd dolt push
```

A merge conflict here means two claims overlapped that shouldn't have. Fix
the `files` metadata of both tasks, resolve in the feature worktree, and say
so — the scheduling data was wrong, not just the merge.

A **failed** review keeps its worktree and branch; the reviewer's findings
are on the beads item. Leave the task `in_progress` for a later batch to
resume in that same worktree.

Then: budget check (step 2), then 5b again.

### 5d. Draft PR, early

As soon as the first task has committed:

```bash
git -C <worktree> push -u origin <branch>
gh pr create --draft --base main --title "<feature title>" \
  --body "Delivers <feature-id>. Tasks land as individual commits."
bd update <feature-id> --set-metadata pr=<url>
bd dolt push
```

Early so CI gives feedback during the build; recorded so a later session
finds the branch instead of starting over. Push again after each batch.

It stays **draft** until review passes — never mark it ready yourself.

### 5e. Feature review

Every task closed does not mean the feature is done: per-task criteria all
pass while the feature still has seams nobody owned, or criteria no task
claimed. Dispatch a fresh reviewer — never the agent that wrote the code:

```
Agent({
  description: "Review feature <id>",
  model: "opus",
  run_in_background: true,
  prompt: `Read .claude/skills/work/references/review-feature.md and follow it to
review feature ${id} against its own acceptance criteria.
Worktree: ${worktree}  ·  Branch: ${branch}  ·  PR: ${pr}
Repair what you can within the feature's scope. You decide the outcome: mark
the PR ready if it's good enough, or leave it draft and file beads tasks for
what's missing. Report which you chose and why.`
})
```

The reviewer owns the ready/draft call and runs `gh pr ready` itself.

- **Ready** → it closed the feature; auto-merge lands it. Don't wait unless
  something depends on it (5f).
- **Draft** → it filed tasks for the gap; those are ready work, go to 5b.
  Draft *without* tasks is a dead end — treat the feature as stuck, move on.

### 5f. Next feature, or wait, or stop

Features branch from `origin/main`, so one only sees another's work once
that one **merges**. If the next ready feature depends on a feature this
session just marked ready, wait for the merge rather than building against a
`main` that lacks it — but only if there's no other ready work:

```bash
until [ "$(gh pr view <pr-url> --json state -q .state)" != "OPEN" ]; do sleep 60; done
gh pr view <pr-url> --json state -q .state
```

Run it with `run_in_background: true`. Loop on `!= OPEN`, not `== MERGED`:
a PR closed unmerged or stuck on a red check would otherwise spin until the
session dies. `MERGED` → `git fetch origin main` and start. `CLOSED` → park
a question ([references/ask-human.md](references/ask-human.md)) rather than
building on something rejected.

Otherwise:

- Tasks remain but none ready → leave the feature `in_progress` with its
  draft PR standing; take another ready feature.
- No ready features left → every descendant closed means `bd close <theme>`
  and drop its `owner:` label. Otherwise leave the epic claimed. Either way,
  **Finalize** — a dry epic ends the session.

## 6. Finalize

```bash
git -C <worktree> status --short   # per worktree touched this session
git -C <worktree> push
bd dolt push
```

Uncommitted leftovers mean a task agent died mid-edit — report that rather
than committing work you didn't verify. Leave worktrees in place; the next
session reuses them via `metadata.worktree`.

Summarize: tasks completed, features left in draft (with PR urls), items
blocked on parked questions (and what they ask), and why the session
stopped.

Don't release claims by hand. The `SessionEnd` hook
(`scripts/beads-release-session-claims.sh`) reopens every task stamped with
this session's id, even if the session dies before reaching this step —
that's the deadlock guarantee. Epics and features are exempt by design
(claim-sync.md).
