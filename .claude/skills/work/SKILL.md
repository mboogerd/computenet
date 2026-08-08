---
name: work
description: Runs one unattended beads work session end to end — claims an epic, breaks it into features and tasks via Fable subagents, and implements each feature in its own worktree, branch, and draft PR, with its tasks running as parallel reviewed branches. Claims are crash-safe and machine-scoped, so two machines can run this concurrently on a schedule without colliding or deadlocking. Use this skill whenever a cron job, scheduled task, or routine kicks off a work slot, or the user says "/work", "work the queue", "pick up the next beads task", "start working through the backlog", "keep the machines busy", or otherwise wants autonomous progress on beads-tracked work — even if they don't mention beads, epics, or this skill by name.
---

# /work

One session = one epic, worked until it's dry or the budget's gone.

You are the orchestrator, not the implementer. Every item — including
breakdowns and reviews — goes to a dispatched subagent. Resist doing one
inline "just this once, it's quick": that's how a multi-hour session's
context balloons and starts drifting.

## The three rules behind everything below

When this file doesn't cover the situation you're in, decide from these:

1. **Get things out of the way fast, and make progress where you can.**
   Something stuck is not a reason to stop — it's a reason to move to the
   next thing that isn't. A session that lands three of five features beats
   one that stalls on the first.
2. **Integrate continuously.** Merge reviewed work the moment it's ready,
   keep branches close to `main`, open the PR early so CI runs while the work
   is still being built. Divergence is the expensive part, and it grows
   quietly.
3. **When something is unclear *and* costly, risky, or hard to revert: post
   the question on the item and continue elsewhere.** At any level — epic,
   feature, or task. Park on the narrowest item that's actually stuck, never
   its parent ([references/ask-human.md](references/ask-human.md)). Parking is
   how you keep moving, not how you stop.

The failure modes those rules exist to prevent: spinning on one blocked item
until the budget dies, and guessing on an expensive fork because asking felt
like giving up.

**Two queries decide everything, and both have a trap.** `bd ready` hides
`in_progress`, `blocked`, and `deferred` items; `bd list` hides *closed*
ones unless you pass `--all`. Every check below uses the one it means. Don't
"simplify" them into each other.

**Bundled scripts do the fiddly parts** — the ones where a wrong flag or a
missed filter silently loses work. Prefer them to hand-rolling the
equivalent:

| Script | Does |
|---|---|
| `scripts/sweep-stale-claims.sh` | Reopens this machine's tasks abandoned by a dead run |
| `scripts/next-batch.py` | Picks the next set of tasks that can safely run in parallel |
| `scripts/ensure-worktree.sh` | Attaches a worktree on a branch, new or resumed, or fails loudly |

## 1. Identity

```bash
echo "${BEADS_ACTOR:?BEADS_ACTOR must be set, uniquely, per machine}"
```

It must be **unique per machine** — it's how claims tell two machines apart.
Do not fall back to `git config user.name`: that is identical on every
machine here, which would silently collapse the safety everything else
depends on. Unset → stop and report. A wrong identity is worse than a dead
run.

## 2. Arm the budget

Don't poll a clock — arm a timer that tells you, and spend the tokens on
work instead:

```
Monitor({
  description: "work session budget",
  persistent: true,
  command: `sleep 11700; echo "BUDGET T-90m: finish the current feature; start no new one"
sleep 2700;  echo "BUDGET T-45m: no new dispatches; review and merge what is in flight"
sleep 2700;  echo "BUDGET EXPIRED: go to Finalize now"`
})
```

Three notifications at 3h15m, 4h, and 4h45m — the slot is 5h, and the last
15m is Finalize. Scale all three proportionally if the routine names a
different slot. **Note the monitor's task id**; when you reach Finalize on
your own, `TaskStop` it so it doesn't outlive the session.

Act on each as it arrives, at the next decision point:

| Notification | Do |
|---|---|
| T-90m | finish the feature you're on; don't start another (5f stops selecting) |
| T-45m | dispatch nothing new; review and merge what's already running |
| EXPIRED | **Finalize**, whatever state you're in |

These arrive as notifications, so they land at your next turn — they do not
interrupt a wait. That's the point: it costs nothing while agents run, and
you find the deadline the moment you're free to act on it. It also means a
hung dispatch still wakes you at the deadline instead of swallowing the slot
(step 5b).

## 3. Sync, release stale claims, take one epic

```bash
git fetch origin main
bd dolt pull
```

**Release stale claims first.** A run that crashed left items `in_progress`,
and `bd ready` hides those — they are invisible to every later session until
something reopens them, and nothing else does:

```bash
.claude/skills/work/scripts/sweep-stale-claims.sh      # --dry-run to preview
```

Report what it released. The same item released repeatedly across sessions
means work is failing, not merely crashing.

Then take the epic. Resume this machine's own before starting anything new:

```bash
bd list --type=epic --status=in_progress --assignee="$BEADS_ACTOR" --json
```

**If *any* result has an `updated_at` within the last 15 minutes, another run
on this machine is probably live** — overlapping runs share `$BEADS_ACTOR`
and cannot tell each other apart. Stop and report rather than driving one
epic twice. Check every row, not just the first: the recent one is the live
run's, and it need not be the one you'd have picked.

Otherwise, if it returns exactly one, resume it. If it returns **more than
one**, previous sessions left epics claimed without finishing them. Resume
the one with the oldest `updated_at` (the most neglected) and report the
others by id — don't release them, and don't work them.

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

That epic id is `<epic>` below. **One epic per session.** When its queue goes
dry the session ends; it does not go looking for another. Epics are
independent, so nothing is gained by chaining two in one slot — and a session
holding two claims is exactly what a concurrent run on this machine cannot
tell apart from a crash.

## 4. Ensure the epic has features

```bash
bd list --parent=<epic> --all --json
```

`--all` matters: without it closed features are hidden, so a *finished* epic
reads as "never broken down" and you would re-create its whole feature set.

Empty → dispatch the breakdown, **wait for its completion notification**,
then re-run the query. Do not fall through to step 5 while it runs — an epic
with no children yet looks finished and would get closed.

If the re-run is still empty, the breakdown produced nothing. **Try once
more, then stop** — park a question on the epic
([references/ask-human.md](references/ask-human.md)) and end the session.
Dispatching a third time is how a broken breakdown burns a whole slot, and
one epic per session means there is nothing else to switch to. Note it in the
friction log (step 7): an epic that can't be broken down twice running is a
defect in the epic or in `epic.md`, not bad luck.

```
Agent({
  description: "Break down epic <epic>",
  model: "fable",
  run_in_background: true,
  prompt: `Read .claude/skills/work/references/epic.md and follow it to break
epic ${epic} into features. It is already claimed and labeled — skip both.
Report the feature ids created.`
})
```

## 5. Work features

**Mark a feature `parked` when it can't progress**, and record it on the
feature itself — not just in your head. Sessions run for hours and get
compacted; an in-context list is the one piece of state whose loss makes the
session spin between 5a and 5f until the budget dies.

```bash
bd update <feature-id> --set-metadata parked_at=$(date +%s)
```

Every selection below skips a feature whose `parked_at` is **within the last
6 hours** — this session's own parks, plus a recent session's, while still
letting a stale one be retried. Clear it (`--set-metadata parked_at=`) when a
feature does progress again.

A resumed feature carrying `metadata.review=passed` was already reviewed and
marked ready last session; it just hadn't merged yet. Re-check its PR
(`gh pr view <pr> --json state -q .state`) — `MERGED` → `bd close` it and
move on, don't re-review or re-work it.

Resume an in-progress feature before starting a new one. `bd ready` cannot
see them, so this query is the **only** way one is ever picked back up —
without it a crashed feature is stranded forever and its epic can never
close:

```bash
bd list --parent=<epic> --type=feature --status=in_progress --json
```

Otherwise take the first unblocked one:

```bash
bd ready --parent=<epic> --type=feature --limit 1 --json
```

Take the first result **not recently parked** (`metadata.parked_at` within 6h). Nothing left after that filter →
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
work, so never start over. **Otherwise** record the metadata *first* — a
crash between recording and creating leaves an unrecorded branch, and the
retry then builds a second branch and a second PR for the same feature:

```bash
bd update <feature-id> \
  --set-metadata branch=feature/<feature-id> \
  --set-metadata worktree=$PWD/../computenet-worktrees/<feature-id>
bd dolt push
```

Either way, attach the worktree the same way, then bring it up to date:

```bash
.claude/skills/work/scripts/ensure-worktree.sh \
  "$PWD/../computenet-worktrees/<feature-id>" feature/<feature-id> origin/main
git -C <worktree> pull --ff-only 2>/dev/null || true   # no upstream yet is fine
git -C <worktree> merge origin/main -m "Merge main into feature/<feature-id>"
git -C <worktree> push -u origin feature/<feature-id>
```

**That `merge origin/main` is not optional on a resumed feature.** `pull` only
refreshes the branch from its own remote — nothing else in this flow ever
brings `main` in, so a feature carried across sessions drifts for days and
first discovers it at the final gate, where the conflict gets resolved after
review has already passed. Paying it down here keeps each merge small and
keeps the reviewer looking at integrated code. Conflicts here are yours to
resolve; re-run the affected module suite afterwards, since a hand-resolved
merge is code nobody reviewed.

The script is idempotent and verifies the branch, so resume and first-run
take the same path. Use the feature id as the branch name rather than a
model-chosen slug: a fresh slug on retry is exactly what spawns a duplicate
branch and a second PR.

### 5b. Break down, then batch tasks

```bash
bd list --parent=<feature-id> --all --json
```

Empty (with `--all`, so closed tasks still count) → dispatch one Fable
breakdown, wait for it, then re-run. Still empty after a second attempt →
park a question on the feature, set its `parked_at`, and go to **5f**:

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

Otherwise ask for the next batch:

```bash
.claude/skills/work/scripts/next-batch.py <feature-id>
```

It returns `{batch: [{id, model, files, worktree, branch, resumed}], skipped,
verdict}`. The batch is the set that can safely run at once: resumable tasks
first (`bd ready` can't see `in_progress` ones, so nothing else would ever
pick them back up), then ready ones whose `files` claims don't overlap
anything already in the batch — two branches editing one file merge into a
conflict. A task with no claim comes back alone, since it can't be proven
disjoint from anything; comment on it that the claim is missing so the
breakdown gets fixed.

A batch entry with an empty `model` means the breakdown omitted it. Dispatch
it at `sonnet`, comment on the task that the field was missing, and log it as
friction (step 7) — a breakdown that keeps omitting it is a `feature.md`
problem, not a one-off.

**Empty batch** → read `verdict`, don't infer it:

| `verdict` | Meaning | Do |
|---|---|---|
| `all-closed` | every task under this feature is closed | **5e** (feature review) |
| `blocked` | tasks remain, all blocked or parked | set `parked_at`, go to **5f** |
| `no-tasks` | the feature has no tasks at all | breakdown died — treat as the empty case above |

Sending a feature with unfinished tasks to 5e puts half a feature in front of
the last gate before `main`, so this distinction is not a formality.

Claim each id in the batch, record its metadata, then attach its worktree:

```bash
bd dolt pull                              # state may be hours old by now
bd update <task-id> --claim
bd update <task-id> \
  --set-metadata worktree=$PWD/../computenet-worktrees/<task-id> \
  --set-metadata branch=task/<task-id>
bd dolt push
.claude/skills/work/scripts/ensure-worktree.sh \
  "$PWD/../computenet-worktrees/<task-id>" task/<task-id> feature/<feature-id>
```

`ensure-worktree.sh` handles all three states — already attached, branch
exists but detached (a task resumed from an earlier session), or brand new —
and fails loudly if it can't put the worktree on the requested branch. That
matters: an agent handed a path that isn't there works somewhere
unintended, and nothing would notice until the merge.

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

**A dispatch that never returns is the one failure that can eat the whole
slot.** The 45–60 minute limit in the prompt is a request to the agent, not a
constraint on it. If a budget notification (step 2) arrives while you are
still waiting on a batch, that batch is over its limit: `TaskStop` the agents
still running, leave their tasks `in_progress` with a comment saying they were
stopped at the deadline — their worktrees and branches survive, so a later
batch resumes them — and continue with whatever did return. Log it as
friction (step 7); a task shape that reliably runs long is a sizing problem in
`feature.md`, and it will keep costing whole slots until someone sees it.

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

**Then look at the integrated result** — once the PR exists (5d):

```bash
gh pr checks <pr-url>
```

The task reviewer tested the task branch, which doesn't contain the siblings
merged before it, so this is the first signal that the merged whole still
builds. Red is work, not a footnote: file a task for it (`bd create
--parent=<feature-id>` with `model` and `files`) and let the next batch take
it. Left alone it sits behind three more merges and lands on the feature
reviewer as a wall.

Then 5b again.

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
  gh pr view <pr-url> --json state,mergeStateStatus,statusCheckRollup
  ```
  `MERGED` → `bd close <feature-id>`, remove its worktree and local branch.

  Still `OPEN` → **auto-merge will not fix itself; look at why before you
  walk away.** `mergeStateStatus` says which of three it is:

  | Status | Meaning | Do |
  |---|---|---|
  | `UNKNOWN` | GitHub hasn't computed mergeability yet | re-query once after ~10s; still `UNKNOWN` → treat as pending, move on |
  | `DIRTY` / `BEHIND` | conflicts with `main`, or needs updating | **resolve it yourself** (below) |
  | `BLOCKED` / `UNSTABLE` | a required check is red or still running | red → file tasks for the failure and go to 5b; running → move on, it merges on its own |
  | `CLEAN` | just waiting on the merge queue | move on |

  `UNKNOWN` is the common first answer, not an error — GitHub computes
  mergeability lazily, so a fresh push almost always returns it. Reading it as
  "no conflict" is how a conflicted PR gets left to rot.

  Resolving a conflict is the orchestrator's job, not a reviewer's — it's the
  one thing standing between finished work and `main`:

  ```bash
  git -C <feature-worktree> fetch origin main
  git -C <feature-worktree> merge origin/main       # resolve, then test
  git -C <feature-worktree> push
  ```
  Re-run the affected module suite after resolving; a conflict resolved by
  hand is new code nobody has reviewed. If the conflict is substantive enough
  that resolving it means redesigning either side, that clears the
  [ask-human.md](references/ask-human.md) bar — park it rather than guessing
  which side wins.

  Anything still `OPEN` after that → leave the feature `in_progress`, add it
  its `parked_at`, and move on; Finalize re-checks it, and a later session sees
  `metadata.review=passed`, re-checks the PR, and closes it then. Don't block
  the slot waiting unless something depends on it (5f).
- **Draft** → it filed tasks for the gap; those are ready work, go to 5b.
  Draft *without* tasks is a dead end — leave the feature `in_progress`, add
  its `parked_at`, and go to **5f**.

### 5f. Next feature, or wait, or stop

Take the first of these that applies:

**1. Another feature under this epic is ready or in progress** (and not
recently parked) → back to 5a with it. **After the T-90m notification, skip this** —
starting a feature you can't review or merge leaves a stranded branch.

**2. The only remaining work depends on a feature this session just marked
ready.** Features branch from `origin/main`, so one only sees another's work
once that one **merges**. Wait for it:

```bash
gh pr view <pr-url> --json state,mergeStateStatus
```

Poll every 60s **in the foreground**, and give up after 30 attempts or on the
T-45m notification, whichever comes first. Do not background an unbounded
`until` loop: a PR sitting on a red required check stays `OPEN` indefinitely
and would burn the rest of the slot in silence. Each poll, if
`mergeStateStatus` is `DIRTY` or `BEHIND`, resolve it per 5e — waiting on a
conflict that only you can clear is a deadlock. `MERGED` →
`git fetch origin main` and start. `CLOSED`, or the cap is reached → park a
question ([references/ask-human.md](references/ask-human.md)) rather than
building on it, and fall through to 3.

**3. Nothing can progress → Finalize**, closing the epic only when it
actually has children and every one is closed:

```bash
bd list --parent=<epic> --all --json      # must be non-empty
```

All closed → `bd close <epic>` and drop its owner label
(`bd update <epic> --remove-label=owner:$BEADS_ACTOR`), freeing the next
session to take a different epic. An epic with *no* children is
mid-breakdown, not finished — never close that.

## 6. Finalize

**Re-check every feature you marked ready this session** — one of them has
probably merged while you were working elsewhere, and closing it here saves
the next session a round trip:

```bash
gh pr view <pr-url> --json state,mergeStateStatus     # per review=passed feature
```

`MERGED` → `bd close <feature-id>` and remove its worktree. `DIRTY`/`BEHIND`
→ resolve it per 5e if the budget allows; it merges on its own afterwards.
Then close any epic whose features are now all closed (5f's check).

```bash
git -C <worktree> status --short   # per worktree touched this session
git -C <worktree> push
bd dolt push
```

Uncommitted leftovers mean an agent died mid-edit — report rather than
committing work you didn't verify. Leave unfinished features' worktrees in
place; the next session reuses them via `metadata.worktree`. Remove the
worktree and local branch of any feature that closed.

Then **log the friction (step 7)** and `TaskStop` the budget monitor.

Summarize: the epic worked, tasks completed, features left in draft (with PR
urls), items blocked on parked questions (and what they ask), stale claims
released at startup, friction logged, and why the session stopped.

Unfinished **tasks** left `in_progress` are released by the next session's
startup sweep (step 3) once they age past 6h. That sweep — not a hook — is
what stops a crashed run from locking work forever.

It deliberately does **not** release epics or features: their claim is what
keeps the other machine out, and it has to outlive the session. The cost is
that an epic abandoned by a crash is only recoverable by the machine that
claimed it (step 3 resumes it by `assignee`) — if that machine is gone for
good, a human has to reassign it. Name any epic or feature you leave
`in_progress` in the summary so that's visible rather than silent.

## 7. Log the friction

Nobody watched this run. **Your transcript is thrown away, so anything wrong
with the process itself dies with it** unless you write it down — and the
same wall gets hit again next slot, and the one after that.

Log the *process* problems, not the product ones. Product questions are
already durable: they're parked beads items. This is for the things that made
*you* slower or forced a guess:

- a command in this skill that failed, needed a flag it doesn't mention, or
  behaved differently than described
- a step where the instructions and reality disagreed, or didn't cover the
  case you were in
- anything you had to retry, work around, or decide with no guidance
- a breakdown that came back unusable, tasks that reliably run long, file
  claims that keep being wrong, reviews that keep failing for the same reason
- a dead end that cost real time

**One issue per kind of friction, deduped** — the point is seeing what recurs,
and ten identical issues are worse than one issue with ten comments:

```bash
bd search "<a few distinctive words>" --json      # look for it first
```

Found one → comment on it with this session's instance (what you were doing,
what happened, what it cost). Not found → create it:

```bash
bd create --type=chore --priority=3 --label=skill-friction \
  --title="work skill: <the friction in one line>" \
  --description="<what the skill says, what actually happened, what you did instead, what it cost>" \
  --acceptance="<what would have to change in the skill for this not to recur>"
bd dolt push
```

Write it for someone editing `SKILL.md` next week with none of your context:
name the step, quote the instruction, say what actually happened.

Review the accumulated log with:

```bash
bd list --label=skill-friction --status=open --json
```

Comment count is the signal. One report is an anecdote; the same issue
commented on by four sessions is the next thing to fix in the skill.

**Log honestly, including your own mistakes.** A misread instruction is the
most useful entry there is — it means the instruction can be written so the
next run can't misread it. Nothing here is graded; a session that logs three
frictions is more valuable than one that logs none.
