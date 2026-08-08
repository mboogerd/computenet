---
name: work
description: Run one beads work session — claim an epic, break it into features then tasks via Fable subagents, and implement each feature in its own worktree, branch, and draft PR, with its tasks running as parallel commits scheduled by file-claim disjointness on per-task routed models. Claims are session-scoped and released deterministically at session end, so concurrent sessions on two machines never collide or deadlock. Parks questions on humans via beads instead of guessing on ambiguous, expensive, risky, or hard-to-revert decisions, without letting one blocked item stall the rest. Use when a cron/routine starts a work slot, or the user says "/work", "work the queue", "pick up beads work".
---

# /work

One session = one epic, worked until it's dry or the budget's gone.

You are the orchestrator, not the implementer. Every item — including
epic/feature breakdown — routes through a dispatched subagent. Resist doing
an item's work inline "just this once, it's quick": that's how this
session's context balloons and starts drifting over a multi-hour run.

## 1. Identity

`BEADS_ACTOR` (falling back to `git config user.name`) must be **unique per
machine** — it's how every race check tells two machines apart. If it can't
be resolved, stop and ask; don't guess a machine identity.

It's read from the environment, so every subagent you dispatch inherits it.
Nothing to pass per-dispatch.

## 2. Budget

```bash
SESSION_START=$(date +%s)
```

Target **4h45m (17100s)** — 5h minus a buffer for the final report. If the
routine that invoked you names a different slot length, use `(length − 15m)`.

Check before each dispatch:

```bash
echo $(( $(date +%s) - SESSION_START ))
```

At or over budget → **Finalize**. Overrunning isn't dangerous (claims are
session-scoped, step 6), it's just wasted slot if the next one starts.

## 3. Sync, then claim one epic

Always start from current state — never work off whatever this checkout
happened to be left at last slot:

```bash
git fetch origin main
bd dolt pull
```

Then pick the epic. Prefer one this machine already owns and hasn't
finished (resuming beats starting something new), otherwise take the
highest-priority unclaimed one:

```bash
# resume: already mine, still open
bd list --type=epic --status=in_progress --assignee="$BEADS_ACTOR" --json
# else start fresh: highest-priority unclaimed epic
bd ready --type=epic --claim --json
```

`bd ready` sorts by priority by default, so `--claim` takes the
highest-priority one atomically. Nothing extra is needed to stay off the
other machine's epic: `bd ready` excludes `in_progress` items, and an epic
another machine owns stays `in_progress` for as long as it owns it (the
SessionEnd hook deliberately never releases epics).

If the claim came from the second command, immediately follow
[references/claim-sync.md](references/claim-sync.md)'s push-and-verify
discipline (`bd dolt push`, then re-check on conflict) and apply the
`owner:` label per [references/epic.md](references/epic.md) — an epic claim
is not safe until it's pushed and confirmed.

If neither command yields an epic, there is no work for this machine right
now. Report that and stop.

`theme` = that epic id. **It never changes for the rest of this session.**
If its queue goes dry, the session ends — it does not go find another epic.
That commitment, plus the epic staying `in_progress` while claimed, is what
keeps two concurrent sessions off each other's subtrees.

## 4. Ensure the epic is broken down

Breakdown state is derived from the tree, not from a status field — an epic
with no children needs a breakdown, full stop. This is crash-safe: an epic
left `in_progress` with no children (a breakdown that died mid-way) is
unambiguous, and simply gets retried.

```bash
bd list --parent=<theme> --json
```

If it returns nothing, dispatch a breakdown before anything else — on
**`model: "fable"`**, as with every breakdown:

```
Agent({
  description: "Break down epic <theme>",
  model: "fable",
  run_in_background: true,
  prompt: `Read .claude/skills/work/references/epic.md and follow it to break
epic ${theme} into features. The epic is already claimed — skip the claim,
go straight to the breakdown. Report the feature ids created.`
})
```

If it returns children, the breakdown is done — go to step 5. (Features that
themselves have no children get broken into tasks by the normal loop: they
surface as ready items and route to `feature.md`.)

## 5. Work features

`--parent` on `bd ready` matches *descendants*, so this sees the whole epic
subtree — features awaiting work and tasks awaiting implementation:

```bash
bd ready --parent=<theme> --json
```

Empty → skip to **When a feature runs dry** below.

**A feature is the unit of integration**: its own worktree, its own branch,
its own draft PR. **A task is a commit** on that branch.

**Work exactly one feature at a time.** The parallelism lives in its tasks
(step 5b), which is plenty — a second concurrent feature buys little and
costs another worktree, another PR, and another set of file claims to keep
straight. When the current feature can't progress, *move on* to another one
rather than adding it alongside.

### 5a. Set up the feature

Claim the feature per [references/claim-sync.md](references/claim-sync.md),
then check whether it already has a worktree from an earlier session:

```bash
bd show <feature-id> --json    # look at .metadata.branch / .worktree / .pr
```

**If `metadata.branch` exists**, reuse it — a previous session already
started this feature and its draft PR holds real work. Never start it over:

```bash
git worktree add <worktree-path> <branch> 2>/dev/null || true
git -C <worktree-path> pull --ff-only 2>/dev/null || true
```

**Otherwise**, create it and record it *before* dispatching anything, so a
crash mid-feature is recoverable:

```bash
git fetch origin main
git worktree add ../computenet-work/<feature-id> -b feature/<feature-id>-<slug> origin/main
bd update <feature-id> \
  --set-metadata branch=feature/<feature-id>-<slug> \
  --set-metadata worktree=../computenet-work/<feature-id>
bd dolt push
```

The draft PR comes after the first commit exists (`gh pr create` rejects a
branch with nothing on it) — see 5c.

### 5b. Break down, then batch tasks

If `bd list --parent=<feature-id> --json` is empty, dispatch one Fable
breakdown and nothing else this round — it creates the tasks the next round
schedules:

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

- Read each task's `metadata.files` claim.
- Add a task only when its claim is **disjoint** from every task already in
  the batch. Overlap → leave it for a later round.
- A task with no `files` metadata can't be scheduled safely. Run it alone,
  and comment on it that the claim was missing so the breakdown gets fixed.

This disjointness check is load-bearing here in a way it wasn't before:
every task in the batch shares **one working tree**, so overlapping claims
mean two agents editing the same file at the same time, not just a merge
conflict later.

Claim every task in the batch (each per claim-sync.md), then dispatch them
in one message so they run concurrently:

```
Agent({
  description: "Implement <id>",
  model: <task's metadata.model — "sonnet" or "opus">,
  run_in_background: true,
  prompt: `You are implementing beads task ${id}, already claimed — do not
claim another. Work in the existing worktree at ${worktree}, on branch
${branch}. Do NOT create a worktree or branch of your own, and do not touch
the main checkout.
Read the task: bd show ${id} --json
Then read .claude/skills/work/references/task.md and follow it.
Other agents are working other tasks in this same worktree right now. Stay
strictly inside your metadata.files claim, and commit only your own paths.
If you won't finish within ~45-60 minutes, stop at a clean point, leave the
task in_progress with a bd comment saying what's done and what's left.
Report back: the task id, the outcome, and the files you actually touched.`
})
```

Note there is no `isolation: "worktree"` here — that would defeat the point.
Tasks of one feature deliberately share the feature's worktree so their
commits accumulate on one branch and one PR.

### 5c. Draft PR, early

As soon as the first task in a feature has committed, open the draft PR and
record it on the feature — do this before the feature is anywhere near
finished:

```bash
git -C <worktree> push -u origin <branch>
gh pr create --draft --base main --title "<feature title>" \
  --body "Delivers <feature-id>. Tasks land as individual commits." 
bd update <feature-id> --set-metadata pr=<url>
bd dolt push
```

Early and recorded matters for two reasons: CI starts giving feedback while
the feature is still being built, and a later session (or another agent)
picking this feature up finds the existing branch and PR instead of starting
from scratch.

After each subsequent batch, push again so the PR keeps reflecting reality.
The PR stays **draft** until the feature review passes (5d) — never mark it
ready yourself just because the tasks are closed.

### On task-batch notifications

Wait for the whole batch before scheduling the next one — a staggered
re-batch computes file-overlap against a moving set.

- **A report names files outside its claim** → correct the task's `files`
  metadata (`bd update <id> --set-metadata files=...`) before the next
  batch, so scheduling isn't built on a claim already known wrong.
- **A task parked a question** → it's `blocked` and assigned to a human (see
  [references/ask-human.md](references/ask-human.md)). That's one task, not
  the feature: keep batching this feature's other ready tasks. If the feature
  then has no ready work left, move to another feature (5f) — never let one
  parked question stall an epic that still has runnable work.
- **Otherwise** → back to step 2 (budget), then step 5b.

### 5d. Feature review

When every task under the feature is closed, the feature is *not* done — it
needs a review that judges the whole thing against the **feature's**
acceptance criteria, not each task's. Task-level criteria can all pass while
the feature has unowned seams or criteria no task claimed.

Dispatch a fresh reviewer at `model: "opus"` — at or above the tasks' tier,
and never the agent that wrote the code:

```
Agent({
  description: "Review feature <id>",
  model: "opus",
  run_in_background: true,
  prompt: `Read .claude/skills/work/references/review.md and follow it to
review feature ${id} against its own acceptance criteria.
Worktree: ${worktree}  ·  Branch: ${branch}  ·  PR: ${pr}
Repair what you can within the feature's scope. You decide the outcome: mark
the PR ready if it's good enough, or leave it in draft and file beads tasks
for what's missing. Report which you chose and why.`
})
```

The reviewer owns the ready/draft call and does the `gh pr ready` itself.

- **Marked ready** → it closed the feature. Auto-merge and required checks
  land it; don't wait unless something depends on it (5e).
- **Left draft** → it filed tasks for the gap. Those are ready work under
  this same feature: go back to 5b and batch them. If it left draft *without*
  filing tasks, that's a dead end — treat the feature as stuck and move on
  (5f).

### 5e. Waiting on a dependency

Features branch from `origin/main`, so a feature only sees another's work
once that one **merges**. If the next ready feature `blocks`-depends on a
feature whose PR this session just marked ready, don't start it against a
`main` that lacks the dependency — wait for the merge:

```bash
until [ "$(gh pr view <pr-url> --json state -q .state)" != "OPEN" ]; do sleep 60; done
gh pr view <pr-url> --json state,mergedAt -q '.state'
```

Run that with `run_in_background: true` — it exits once the PR leaves OPEN,
and you're notified. Loop on `!= OPEN`, not on `== MERGED`: a PR closed
without merging, or stuck on a failing check, would otherwise spin until the
session dies.

On the notification: `MERGED` → `git fetch origin main` and start the
dependent feature. `CLOSED` → the dependency didn't land; park a question
([references/ask-human.md](references/ask-human.md)) on the dependent
feature rather than building on something that was rejected.

If there's other ready work not behind this dependency, prefer doing that
over waiting — waiting is the last resort, not the default.

### 5f. When a feature can't progress

All tasks closed and review passed → pick the next ready feature.

Tasks remain but none are ready (all blocked or parked) → leave the feature
`in_progress` with its draft PR standing, and move to another ready feature.
The committed work stays on the branch for whoever resumes it.

No ready features left in the epic → if every descendant is closed,
`bd close <theme>` and drop its `owner:` label; the next session is free to
take a different epic. Otherwise leave the epic claimed. Either way go to
**Finalize**: per step 3, a dry epic ends the session.

## 6. Finalize

- For every feature worktree this session touched: push the branch so no
  commits are stranded on this machine.
  ```bash
  git -C <worktree> status --short
  git -C <worktree> push
  ```
  Uncommitted leftovers in a worktree mean a task agent died mid-edit — say
  so explicitly rather than committing work you didn't verify.
- Leave the worktrees in place. They're recorded in each feature's
  `metadata.worktree`, and the next session reuses them (step 5a). Don't
  `git worktree remove` a feature that isn't finished.
- `bd dolt push` — subagents push per item, but confirm nothing got left
  behind by one that died mid-way.
- Summarize: tasks completed, features left in draft (with their PR urls),
  items `blocked` on parked questions (and what they're asking), and why the
  session stopped — budget, epic exhausted, or empty queue.

You do **not** release claims by hand. The `SessionEnd` hook
(`scripts/beads-release-session-claims.sh`, configured once in
`.claude/settings.json`) deterministically reopens every `task`/`bug`/`chore`
stamped with this session's id — including when the session dies before
reaching this step. That's the deadlock guarantee; the push above is just a
head start. Epics and features are exempt by design (see
[references/claim-sync.md](references/claim-sync.md)) and stay claimed by
this machine across sessions.
