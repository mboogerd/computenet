---
name: work
description: Run one unattended beads work session — claim an epic, break it into features and tasks via Fable subagents, and implement each feature in its own worktree, branch, and draft PR with its tasks running as parallel reviewed branches. Safe for two machines running it concurrently on a schedule. Use when a cron/routine starts a work slot, or the user says "/work", "work the queue", "pick up beads work".
---

# /work

One session = one epic, worked until it's dry or the budget's gone.

You are the orchestrator, not the implementer. Every item — including
breakdowns and reviews — goes to a dispatched subagent. Resist doing one
inline "just this once, it's quick": that's how a multi-hour session's
context balloons and starts drifting.

**Two queries decide everything, and both have a trap.** `bd ready` hides
`in_progress`, `blocked`, and `deferred` items; `bd list` hides *closed*
ones unless you pass `--all`. Every check below uses the one it means. Don't
"simplify" them into each other.

## 1. Identity

```bash
echo "${BEADS_ACTOR:?BEADS_ACTOR must be set, uniquely, per machine}"
```

It must be **unique per machine** — it's how claims tell two machines apart.
Do not fall back to `git config user.name`: that is identical on every
machine here, which would silently collapse the safety everything else
depends on. Unset → stop and report. A wrong identity is worse than a dead
run.

## 2. Start the clock

```bash
date +%s
```

**Note that number.** Shell variables do not survive between commands here,
so there is nothing to assign it to — you carry it, and substitute the
literal value into later checks:

```bash
echo $(( $(date +%s) - 1786209406 ))     # <- your noted number
```

Budget is **4h45m (17100s)**, or `(slot length − 15m)` if the routine names
one. Check before each dispatch and before each wait; at or over, go to
**Finalize**.

## 3. Sync, release stale claims, take one epic

```bash
git fetch origin main
bd dolt pull
```

**Release stale claims first.** A run that crashed left items `in_progress`,
and `bd ready` hides those — they are invisible to every later session until
something reopens them, and nothing else does:

```bash
bd list --status=in_progress --assignee="$BEADS_ACTOR" --exclude-type=epic,feature --limit 0 --json
```

For each, compare `updated_at` to now. Older than **6h** — longer than any
slot, so a live run's items are never touched — means abandoned:

```bash
bd update <id> --status=open
```

Report how many you released. The same item released repeatedly across
sessions means work is failing, not merely crashing.

Then take the epic. Resume this machine's own before starting anything new:

```bash
bd list --type=epic --status=in_progress --assignee="$BEADS_ACTOR" --json
```

If it returns exactly one, check its `updated_at`. **Within the last 15
minutes means another run on this machine is probably live on it** —
overlapping runs share `$BEADS_ACTOR` and cannot tell each other apart. Stop
and report rather than driving one epic twice. Otherwise resume it.

If it returns **more than one**, a previous session left an epic claimed
without finishing it. Resume the one with the oldest `updated_at` (the most
neglected) and report the others by id — don't release them, and don't try
to work them; one epic per session still holds.

Nothing to resume → take the highest-priority unclaimed epic:

```bash
bd ready --type=epic --limit 1 --json     # read the id
bd update <id> --claim                    # claim that id specifically
bd update <id> --add-label=owner:$BEADS_ACTOR
bd dolt push
```

Always claim **the id you selected**. Never `bd ready --claim`: it claims
whatever is first *at claim time*, which may not be what you just read, and
you would then work an item you don't own. Confirm the claim per
[references/claim-sync.md](references/claim-sync.md).

Nothing claimable → report and stop.

`theme` = that epic id. **It never changes for this session.** If its queue
goes dry the session ends; it does not go find another epic.

## 4. Ensure the epic has features

```bash
bd list --parent=<theme> --all --json
```

`--all` matters: without it closed features are hidden, so a *finished* epic
reads as "never broken down" and you would re-create its whole feature set.

Empty → dispatch the breakdown, **wait for its completion notification**,
then re-run the query. Do not fall through to step 5 while it runs — an epic
with no children yet looks finished and would get closed.

If the re-run is still empty, the breakdown produced nothing. **Try once
more, then stop** — park a question on the epic
([references/ask-human.md](references/ask-human.md)) and end the session.
Dispatching a third time is how a broken breakdown burns a whole slot.

```
Agent({
  description: "Break down epic <theme>",
  model: "fable",
  run_in_background: true,
  prompt: `Read .claude/skills/work/references/epic.md and follow it to break
epic ${theme} into features. It is already claimed and labeled — skip both.
Report the feature ids created.`
})
```

## 5. Work features

**Keep a `parked` list** of feature ids this session has already found
unable to progress. It starts empty and only grows. Every selection below
skips anything on it — without that, the query that resumes an in-progress
feature immediately re-selects the one you just gave up on, and the session
spins between 5a and 5f until the budget dies.

A resumed feature carrying `metadata.review=passed` was already reviewed and
marked ready last session; it just hadn't merged yet. Re-check its PR
(`gh pr view <pr> --json state -q .state`) — `MERGED` → `bd close` it and
move on, don't re-review or re-work it.

Resume an in-progress feature before starting a new one. `bd ready` cannot
see them, so this query is the **only** way one is ever picked back up —
without it a crashed feature is stranded forever and its epic can never
close:

```bash
bd list --parent=<theme> --type=feature --status=in_progress --json
```

Otherwise take the first unblocked one:

```bash
bd ready --parent=<theme> --type=feature --limit 1 --json
```

Take the first result **not in `parked`**. Nothing left after that filter →
**5f**.

**A feature is the unit of integration**: its own worktree, branch, and
draft PR, into which reviewed task branches are merged. **A task is its own
worktree and branch**, cut from the feature branch, merged back once it
passes review.

**Work exactly one feature at a time** — the parallelism lives in its tasks.
When a feature can't progress, move on rather than adding one alongside.

### 5a. Set up or resume the feature

```bash
bd update <feature-id> --claim          # idempotent if already yours
bd show <feature-id> --json             # .metadata.branch / .worktree / .pr
```

**`metadata.branch` exists** → reuse it; that branch and draft PR hold real
work. Never start over:

```bash
git worktree list --porcelain | grep -q "^worktree <worktree>$" \
  || git worktree add <worktree> <branch>
git -C <worktree> rev-parse --abbrev-ref HEAD    # must equal <branch>
git -C <worktree> pull --ff-only
```

Don't paper over failures here with `|| true`. If the worktree can't be
attached, or is on the wrong branch, stop and report — every task of this
feature is about to be pointed at that path, and the first thing to notice a
bad one would be the merge in 5c, long after the work was done.

**Otherwise** record the metadata *first*, then create. A crash between the
two otherwise leaves an unrecorded branch, and the retry builds a second
branch and a second PR for the same feature:

```bash
bd update <feature-id> \
  --set-metadata branch=feature/<feature-id> \
  --set-metadata worktree=$PWD/../computenet-worktrees/<feature-id>
bd dolt push
git worktree add "$PWD/../computenet-worktrees/<feature-id>" -b feature/<feature-id> origin/main
git -C "$PWD/../computenet-worktrees/<feature-id>" push -u origin feature/<feature-id>
```

Use **absolute paths**, and the feature id as the branch name — no
model-chosen slug. A relative path resolves differently inside a subagent's
own worktree, and a fresh slug on retry is exactly what spawns a duplicate
branch.

### 5b. Break down, then batch tasks

```bash
bd list --parent=<feature-id> --all --json
```

Empty (with `--all`, so closed tasks still count) → dispatch one Fable
breakdown, wait for it, then re-run. Still empty after a second attempt →
park a question on the feature, add it to `parked`, and go to **5f**:

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

Otherwise gather work, resumable ones first:

```bash
bd list --parent=<feature-id> --status=in_progress --assignee="$BEADS_ACTOR" --json
bd ready --parent=<feature-id> --json
```

Build a batch, highest priority first:

- Add a task only when its `metadata.files` claim is **disjoint** from every
  task already in the batch. Overlap → leave it for a later round; two
  branches editing one file merge into a conflict.
- No `files` metadata → dispatch it as the **only** task this round and
  comment that the claim was missing, so the breakdown gets fixed. If the
  batch already has members, leave it for the next round instead.

**Both lists empty** → every task is closed, blocked, or parked:

- All closed → **5e** (feature review).
- Otherwise → add this feature to `parked` and go to **5f**; it cannot progress right now.

Claim each selected id and give it a worktree, metadata first:

```bash
bd dolt pull                              # state may be hours old by now
bd update <task-id> --claim
bd update <task-id> \
  --set-metadata worktree=$PWD/../computenet-worktrees/<task-id> \
  --set-metadata branch=task/<task-id>
bd dolt push
```

Then get it a worktree. `git worktree add` refuses when the path is already
a worktree, and `-b` refuses when the branch exists — so a resumed task
fails **both** naive forms. Check first rather than chaining `||`:

```bash
git worktree list --porcelain | grep -q "^worktree $PWD/../computenet-worktrees/<task-id>$" \
  && echo "already attached, nothing to do" \
  || { git show-ref --quiet refs/heads/task/<task-id> \
       && git worktree add "$PWD/../computenet-worktrees/<task-id>" task/<task-id> \
       || git worktree add "$PWD/../computenet-worktrees/<task-id>" -b task/<task-id> feature/<feature-id>; }
```

Three cases: already attached (resumed within this session), branch exists
but is detached (resumed from an earlier session), or neither (new task).
Confirm the worktree exists and is on the right branch before dispatching —
`git -C <worktree> rev-parse --abbrev-ref HEAD`. Never let a worktree
failure pass silently: an agent handed a path that doesn't exist works
somewhere unintended.

Then dispatch the batch in one message:

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
Stay inside your metadata.files claim — sibling tasks are running on sibling
branches and merge into the same feature branch.
If you won't finish within ~45-60 minutes, stop at a clean point and leave
the task in_progress with a bd comment saying what's done and what's left.
Your worktree and branch are preserved, so a later batch resumes you here.
Report back: the task id, the outcome, and the files you actually touched.`
})
```

Don't set `isolation: "worktree"` — you create and record the worktree
yourself precisely so it outlives the agent and can be resumed.

**On batch completion** (wait for the whole batch; a staggered re-batch
computes overlap against a moving set):

- **Files touched outside a claim** → fix that task's `files` metadata
  before the next batch.
- **A task parked a question** → that's one task, not the feature. Carry on
  with the rest.
- **A task reported done** → review and merge it (5c).

### 5c. Review each task, then merge it

Dispatch one reviewer per completed task, concurrently, at the task's own
model or above — never the agent that wrote it:

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
concurrent merges into one feature branch race each other. Close the task
*before* removing anything, so a crash mid-sequence can't leave merged work
looking unclaimed and get it re-implemented:

```bash
git -C <feature-worktree> merge --no-ff task/<task-id> -m "Merge <task-id>"
git -C <feature-worktree> push
bd close <task-id>
bd dolt push
git -C <task-worktree> status --short          # expect empty
git worktree remove "$PWD/../computenet-worktrees/<task-id>"
```

If that `status` isn't empty, the agent died mid-edit — report it and leave
the worktree. Don't `--force` away work nobody has looked at.

A merge conflict means two claims overlapped that shouldn't have. Resolve in
the feature worktree, fix **both** tasks' `files` metadata, and say so.

A **failed** review keeps its worktree, branch, and `in_progress` status.
5b's resume query picks it up in a later batch.

Then: budget check (step 2), then 5b again.

### 5d. Draft PR, on the first merge

The feature branch has no commits until a task merges, and `gh pr create`
rejects a branch with nothing on it. Open the PR right after the **first
merge in 5c** — not when a task first commits, since task commits land on
task branches.

**Only if `metadata.pr` is unset.** A resumed feature already has one, and
`gh pr create` on a branch that already has a PR is an error, not a no-op:

```bash
gh pr create --draft --base main --head feature/<feature-id> \
  --title "<feature title>" \
  --body "Delivers <feature-id>. Tasks land as reviewed commits."
bd update <feature-id> --set-metadata pr=<url>
bd dolt push
```

Early so CI gives feedback while the feature is still being built; recorded
so a later session finds it instead of starting over. It stays **draft**
until the feature review passes — never mark it ready yourself.

### 5e. Feature review

Every task closed does not mean the feature is done: per-task criteria all
pass while the feature still has seams nobody owned, or criteria no task
claimed. Dispatch a fresh reviewer — never one that wrote the code:

```
Agent({
  description: "Review feature <id>",
  model: "opus",
  run_in_background: true,
  prompt: `Read .claude/skills/work/references/review-feature.md and follow
it to review feature ${id} against its own acceptance criteria.
Worktree: ${worktree}  ·  Branch: ${branch}  ·  PR: ${pr}
Repair what you can within the feature's scope. You decide the outcome: mark
the PR ready if it's good enough, or leave it draft and file beads tasks for
what's missing. Report which you chose and why.`
})
```

The reviewer owns the ready/draft call and runs `gh pr ready` itself. It
does **not** close the feature — ready is not merged, and a red required
check can leave the PR open indefinitely.

- **Ready** → check whether it landed, and close the feature only then:
  ```bash
  gh pr view <pr-url> --json state -q .state
  ```
  `MERGED` → `bd close <feature-id>`, remove its worktree and local branch.
  Still `OPEN` → leave the feature `in_progress`, add it to `parked`, and
  move on; a later session sees `metadata.review=passed`, re-checks the PR,
  and closes it then. Don't block the slot waiting unless something depends
  on it (5f).
- **Draft** → it filed tasks for the gap; those are ready work, go to 5b.
  Draft *without* tasks is a dead end — leave the feature `in_progress`, add
  it to `parked`, and go to **5f**.

### 5f. Next feature, or wait, or stop

Features branch from `origin/main`, so one only sees another's work once
that one **merges**. If the next ready feature depends on a feature this
session just marked ready, and there is no other ready work, wait for it:

```bash
gh pr view <pr-url> --json state -q .state
```

Poll every 60s **in the foreground, re-checking your budget each time**, and
give up after 30 attempts. Do not background an unbounded `until` loop: a PR
sitting on a red required check stays `OPEN` indefinitely and would burn the
rest of the slot in silence. `MERGED` → `git fetch origin main` and start.
`CLOSED`, or the cap is reached → park a question
([references/ask-human.md](references/ask-human.md)) rather than building on
it, and move on.

Otherwise:

- Another feature is ready or in progress → back to 5a with it.
- None can progress → **Finalize**, closing the epic only when it actually
  has children and every one is closed:
  ```bash
  bd list --parent=<theme> --all --json      # must be non-empty
  ```
  All closed → `bd close <theme>` and drop its `owner:` label, freeing the
  next session to take a different epic. An epic with *no* children is
  mid-breakdown, not finished — never close that.

## 6. Finalize

```bash
git -C <worktree> status --short   # per worktree touched this session
git -C <worktree> push
bd dolt push
```

Uncommitted leftovers mean an agent died mid-edit — report rather than
committing work you didn't verify. Leave unfinished features' worktrees in
place; the next session reuses them via `metadata.worktree`. Remove the
worktree and local branch of any feature that closed.

Summarize: tasks completed, features left in draft (with PR urls), items
blocked on parked questions (and what they ask), stale claims released at
startup, and why the session stopped.

Unfinished **tasks** left `in_progress` are released by the next session's
startup sweep (step 3) once they age past 6h. That sweep — not a hook — is
what stops a crashed run from locking work forever.

It deliberately does **not** release epics or features: their claim is what
keeps the other machine out, and it has to outlive the session. The cost is
that an epic abandoned by a crash is only recoverable by the machine that
claimed it (step 3 resumes it by `assignee`) — if that machine is gone for
good, a human has to reassign it. Name any epic or feature you leave
`in_progress` in the summary so that's visible rather than silent.
