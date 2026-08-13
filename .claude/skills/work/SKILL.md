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

**Two `bd` JSON traps that lie silently.** `bd show <id> --json` returns a
**list** — unwrap `.[0]` before accessing fields, or `.status`/`.metadata`
yield `null` and read as a legitimate answer. And it never includes comment
bodies, only `comment_count`; piping it through `.comments[]?` yields empty
for *every* issue. The only correct way to read comments — e.g. "has a human
answered this parked question?" — is:

```bash
bd comments <id> --json
```

An empty result from the wrong query is indistinguishable from "no answer",
and has already caused an epic to be wrongly deferred over gates that were
cleared.

**Bundled scripts do the fiddly parts** — the ones where a wrong flag or a
missed filter silently loses work. Prefer them to hand-rolling the
equivalent:

| Script | Does |
|---|---|
| `scripts/sweep-stale-claims.sh` | Reopens this machine's tasks abandoned by a dead run |
| `scripts/next-batch.py` | Picks the next set of tasks that can safely run in parallel |
| `scripts/ensure-worktree.sh` | Attaches a worktree on a branch, new or resumed, or fails loudly |

**References carry the deep protocols** — this file is the decision spine;
read a reference at the moment its situation arises, not upfront:

| Reference | Read when |
|---|---|
| `references/claim-sync.md` | before deciding a claim is safe; when any sync fails |
| `references/red-check-attribution.md` | a required check is red in a module the diff doesn't touch |
| `references/ship-feature.md` | right after `gh pr ready`; on any draft verdict |
| `references/orchestrator-authorship.md` | before writing a durable causal claim |
| `references/ask-human.md` | parking a question a human must answer |
| `references/epic.md` / `feature.md` / `task.md` | handed to breakdown/implementer dispatches |
| `references/review-task.md` / `review-feature.md` | handed to reviewer dispatches |

## What you write yourself is the one thing nobody reviews

Everything dispatched gets a reviewer; your own output — commit messages,
PR bodies, dispatch-prompt framing, conflict resolutions — lands in
`main`'s history and in other agents' heads exactly as you wrote it. Three
rules, in force throughout this file:

- **Claim the observation; never the mechanism unless you tested it.** A
  causal sentence needs a distinguishing run (quoted, with run id and the
  verbatim `FAILED` line) or a mechanism that cannot be otherwise, cited to
  its artifact. Otherwise write what you actually have — "whether that
  affects the flakes is untested" is a true sentence — and count counts
  from the output before writing them.
- **Dispatch prompts relay artifacts, not framing.** A subagent cannot
  tell your speculation from your evidence; hand it the run id, job, and
  `FAILED` line plus "mechanism unknown, read the log".
- **Code you write yourself** (conflict resolutions, unblocking fixes)
  goes to a reviewer on the same terms as task work (5c) — it is the one
  code path no dedicated reader sees.

The full case for these — the PR #14 invented-mechanism incident and the
8-wasted-runs brief — is
[references/orchestrator-authorship.md](references/orchestrator-authorship.md);
read it before writing any durable causal claim.

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

**Sync brackets acquisition, not writes; ownership makes writes free.**
Writes inside territory you own (items under your claimed epic) stay local
and ride the Finalize push. Acquisitions — this step's epic claim, a
cross-epic item claim (5f routes 3–4), filing under the SDLC epic (step
7) — each get their own pull → verify → write → push bracket; at ~30s a
round-trip, a handful per session is noise.
[references/claim-sync.md](references/claim-sync.md) is the full statement:
what a claim does and does not guarantee, the accepted gaps, and what to do
when a sync fails. (The catch-up job `scripts/beads-nightly-sync.sh` exists
but **no scheduler runs it** — never treat it as a push that will happen on
its own; `doc/ops/beads-sync-runbook.md` §5.)

**If this pull fails, stop the session and report it.** It is the only look
you get at the other machine's state, so proceeding without it means claiming
against state that may be hours or days stale — the computenet-kg7 /
computenet-3v8 failure, where a whole slot ran against a local-only DB with
claim safety silently gone.

**Release stale claims first.** A run that crashed left items `in_progress`,
and `bd ready` hides those — they are invisible to every later session until
something reopens them, and nothing else does:

```bash
.claude/skills/work/scripts/sweep-stale-claims.sh      # --dry-run to preview
```

Report what it released. The same item released repeatedly across sessions
means work is failing, not merely crashing. The releases are local writes like
everything else here; they reach the other machine at Finalize's push, not
immediately. Items it reports as **"complete, awaiting decision"** are
reviewed work waiting on a ship or human call — it deliberately does *not*
release those, so don't treat them as fresh work; check their PR state
instead (a `MERGED` one just needs `bd close`).

Then take the epic — but first check nothing live is holding one, and free
whatever a dead run left claimed:

```bash
bd list --type=epic --status=in_progress --assignee="$BEADS_ACTOR" --json
```

**If *any* result has an `updated_at` within the last 15 minutes, another run
on this machine is probably live** — overlapping runs share `$BEADS_ACTOR`
and cannot tell each other apart. Stop and report rather than driving one
epic twice. Check every row, not just the first.

Anything else this query returns is a crash leftover — a clean session
releases its epic at Finalize. **Release each one** (`bd update <id>
--status=open`, a local write like everything else here) so it competes on
priority again instead of sticking to this machine.

There is deliberately **no resume preference**: an epic is bound to a session
while the session runs, and to nothing afterwards. If a released epic is
still the most important thing, the selection below picks it straight back
up; if priorities moved since last session, the new top epic wins.

Take the highest-priority unclaimed epic — **skipping `computenet-wpvy`,
the SDLC epic**. That epic and its children belong exclusively to the SDLC
orchestrator lane (`.claude/skills/remediate-friction/SKILL.md` today, a
reactive orchestrator eventually) and are never /work's to claim, **on any
route** — not here, not as a cross-epic blocker (5f route 3), not as
continuation work (5f route 4):

```bash
bd ready --type=epic --json               # take the first id that is NOT computenet-wpvy
bd update <id> --claim                    # claim that id specifically
bd update <id> --add-label=owner:$BEADS_ACTOR
bd update <id> --set-metadata skill_version=$(git hash-object .claude/skills/work/SKILL.md)
bd dolt push                              # the claim is an acquisition — push it now
```

**That push is what turns the claim from a record into a lock.** Without it,
two machines starting slots between each other's Finalize pushes could both
claim this epic and neither would find out all session (the computenet-kg7
class). If the push fails, `bd dolt pull` and retry once — a rejection
usually means the other machine pushed since your step-3 pull, so re-verify
the epic is still unclaimed before re-claiming. Still failing → stop and
report; an unpushed epic claim is exactly the window this bracket exists to
close.

That last line records **which revision of this skill the session ran
under**. Friction items filed in step 7 carry it, so a fix is attributable
to the revision that produced the report, and a report against a superseded
revision can be re-validated instead of silently carried forward.

**Before committing to an epic that was already broken down, check it has
workable surface.** An epic whose remaining ready items all carry the
`human` label, or are blocked solely on items in *other* epics, has zero
autonomously workable work, and every session would keep re-selecting it:

```bash
bd ready --parent=<epic> --json   # workable = items NOT labeled 'human'
bd list --parent=<epic> --type=feature --status=in_progress --json  # resumable
```

Zero workable ready items *and* nothing resumable (for an epic that *has*
children — one with none just needs breakdown, step 4) → **park it and
select the next**:

```bash
bd comment <epic> "Parking: no workable surface. <each remaining id: human-gated / blocked on <other-epic-id>>"
bd defer <epic>
```

`defer` is the right verb: it hides the epic from `bd ready` on both
machines while preserving the assignee and owner label, so the provenance
survives and no session keeps re-selecting a dead queue. A human reopens it
once the gates clear. Then re-run the selection above for the next epic.

Always claim **the id you selected**. Never `bd ready --claim`: it claims
whatever is first *at claim time*, which may not be what you just read, and
you would then work an item you don't own.

With the claim pushed, the race window is the seconds between your pull and
your push, not the session — and a crash after this point leaves a *visible*
claim the other machine can reason about, instead of an invisible one.
Everything you write **under** this epic from here on is owned territory:
local until Finalize, no per-write sync.
[references/claim-sync.md](references/claim-sync.md) describes exactly what
is and is not protected.

Nothing claimable → report and stop.

That epic id is `<epic>` below. **One epic *claim* per session.** The claim
is what a concurrent run on this machine uses to tell a live session from a
crash, so never hold two epic claims. But the rule limits *claims*, not
work: when the epic's queue goes dry with budget left, 5f says exactly what
you may still pick up (a cross-epic blocker, or continuation items from
other epics' ready features and tasks) — going
idle for hours because the epic dried up early is a failure mode, not
compliance.

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

Take the first result **not recently parked** (`metadata.parked_at` within
6h) **and not carrying the `human` label** — `bd ready` returns human-gated
decision beads as if they were workable, and dispatching one hands an agent a
decision a human explicitly reserved. Nothing left after both filters →
**5f**.

**A feature is the unit of integration**: its own worktree, branch, and
draft PR, into which reviewed task branches are merged. **A task is its own
worktree and branch**, cut from the feature branch, merged back once it
passes review.

**Work exactly one feature at a time** — the parallelism lives in its tasks.
When a feature can't progress, move on rather than adding one alongside.

**One worktree, one live agent.** A worktree belongs to exactly one dispatched
agent at a time, and it stays that agent's until **its completion notification
has arrived in this session** — not until its tree looks clean, not until its
PR merged, not until it pushed its last commit. Before you dispatch a second
agent into a worktree, and before you remove one, ask the same question: has
the agent you dispatched into it reported back?

- **You never dispatched an agent into it this session** (a first run, or a
  worktree resumed from an earlier session via `metadata.worktree`, 5a) →
  nobody is live in it and it is yours to use. An earlier session's agent
  died with that session and its notification will **never** arrive here, so
  do not wait for one. This is the normal resume case; it must not stall.
- **You dispatched one and its notification has arrived**, or you
  `TaskStop`ped it and the stop landed → it is free. Reuse it (5b resumes a
  stopped task in a later batch this way) or remove it.
- **You dispatched one and it is still running** → it is occupied. Do not
  dispatch a second agent into it, and do not remove it. Wait for the
  notification, `TaskStop` it and wait for the stop to land, or give the new
  agent a separate worktree (5c's repair path).

If you cannot say which of the three you are in, you are in the third: treat
the worktree as occupied and wait.

`git -C <worktree> status --short` does **not** answer that question. It asks
whether the tree has uncommitted edits — a fact about the *tree*. An agent
that has finished committing and pushing and is now writing bead comments or
running a final verification leaves a perfectly clean tree, so the check
reports "safe" at exactly the moment removal is most disruptive
(computenet-ys7: a worktree removed out from under a live reviewer seconds
after `gh pr ready`, both checks having passed honestly). Keep running it —
it is the second guard, against destroying uncommitted work nobody has looked
at — but read it as "nothing unsaved here", never as "nobody is working
here".

### 5a. Set up or resume the feature

```bash
bd update <feature-id> --claim          # idempotent if already yours
bd show <feature-id> --json             # .metadata.branch / .worktree / .pr
```

A resumed feature may still be assigned to the **other** machine — epics move
freely between machines across sessions, and feature claims outlive them.
Holding the epic claim makes it yours to take: claim it, and expect only what
that machine pushed (`origin/feature/<feature-id>`), never its local
worktree. Ignore a foreign `metadata.worktree` path; the commands below
recompute a local one, and `ensure-worktree.sh` rebuilds from the remote
branch.

**`metadata.branch` exists** → reuse it; that branch and draft PR hold real
work, so never start over. **Otherwise** record the metadata *first* — a
crash between recording and creating leaves an unrecorded branch, and the
retry then builds a second branch and a second PR for the same feature:

```bash
bd update <feature-id> \
  --set-metadata branch=feature/<feature-id> \
  --set-metadata worktree=$PWD/../computenet-worktrees/<feature-id>
```

Recording it locally is enough for that ordering to do its job: a retry on
this machine reads the local DB and finds the branch. It reaches the other
machine at Finalize's push.

Either way, attach the worktree the same way, then bring it up to date:

```bash
.claude/skills/work/scripts/ensure-worktree.sh \
  "$PWD/../computenet-worktrees/<feature-id>" feature/<feature-id> origin/main

# Verify the worktree actually holds the branch's remote work before using it.
if git -C <worktree> fetch origin feature/<feature-id> 2>/dev/null; then
  git -C <worktree> merge-base --is-ancestor FETCH_HEAD HEAD \
    && echo "OK: worktree contains origin/feature/<feature-id>" \
    || echo "STOP: on the branch at the wrong commit — origin/feature/<feature-id> is not in HEAD"
else
  echo "OK: origin has no feature/<feature-id> yet (first run, nothing to compare)"
fi

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

**Read the verification line it prints, and only that line.** `STOP` means
the worktree is on the right branch at the wrong commit — proceeding
silently orphans reviewed work while looking perfectly clean
(computenet-aeg); do not enter 5b. Either `OK` is fine; the second is the
first-run case, where origin has no such branch yet.

The script is idempotent, so resume and first-run take the same path: it
leaves an attached worktree alone, attaches local branches, tracks
remote-only branches **at the remote tip**, fast-forwards a strictly-behind
local branch, keeps a strictly-ahead one (unpushed work), and **fails
loudly on divergence** rather than picking a side. Use the feature id as
the branch name rather than a model-chosen slug: a fresh slug on retry is
exactly what spawns a duplicate branch and a second PR. After any change to
the script, run `.claude/skills/work/scripts/ensure-worktree.test.sh`
(expect "8 passed, 0 failed").

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
bd update <task-id> --claim
bd update <task-id> \
  --set-metadata worktree=$PWD/../computenet-worktrees/<task-id> \
  --set-metadata branch=task/<task-id>
.claude/skills/work/scripts/ensure-worktree.sh \
  "$PWD/../computenet-worktrees/<task-id>" task/<task-id> feature/<feature-id>
```

`ensure-worktree.sh` handles every state a resumed task can be in — already
attached, a local branch with no worktree, a **remote-only** branch (a task
whose worktree was removed, or that ran on another machine: it is attached at
the remote tip, not recreated from `feature/<feature-id>`), or brand new — and
fails loudly if it can't put the worktree on the requested branch holding the
remote's commits. See 5a for the full resolution order. That matters: an agent
handed a path that isn't there works somewhere unintended, and one handed the
right path at the wrong commit rewrites work that was already reviewed —
neither would be noticed until the merge.

These claims are not re-synced first, and don't need to be: the tasks are
children of an epic this machine claimed at step 3, so the other machine has
no reason to be in here. What the local state *can* be stale about is the
epic's own ownership, and that was settled by the step-3 pull.

Then dispatch the batch in one message. Anything you add to this template
reaches the agent as established fact — relay artifacts, not mechanism
(§ "What you write yourself"):

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
If this is a bug fix, task.md step 3 is not optional: run the reproduction
against the UNFIXED code first and quote the failing test name and assertion
message. A prescribed reproduction that passes unfixed is a false lead — say
so on the bead rather than quietly substituting your own.
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
git -C <task-worktree> status --short          # expect empty
```

The close is local; it propagates at Finalize's push. That still protects the
ordering above, because the machine that resumes after a crash is this one,
reading this DB — and the merge commit is on the pushed branch either way.

If that `status` isn't empty, the agent died mid-edit — report it. Don't
`--force` away work nobody has looked at.

**Do not remove the task worktree here.** A merged task's worktree is not
urgent to reclaim, and the merge is also roughly when its reviewer is
finishing its own bead bookkeeping, so removing it now races the very agent
that just told you to merge. **Every worktree removal in this session happens
at Finalize (step 6)**, after all dispatched agents have returned; note the
worktree as removable and move on. That empty `status` is the second guard for
Finalize's removal, not a licence to remove now — per "One worktree, one live
agent" above, it says nothing about whether the reviewer is still live.

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

That is the default and it is right nearly every time. The one exception —
a red check in a module this diff does not touch — is deliberately narrow
and evidence-heavy: read
[references/red-check-attribution.md](references/red-check-attribution.md)
and produce its four artifacts before treating any red as not this
feature's defect. It also covers where a fix gets dispatched when the
feature worktree is still occupied (return the PR to draft first, and never
put a second agent in a live agent's worktree). Never ship on the assertion
that a red check is a flake: attribute, re-run within its limits, or park
as blocked-on-infrastructure — a required check red at ship time stops the
ship in every case.

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
```

Early so CI gives feedback while the feature is still being built; recorded
so a later session finds it instead of starting over. Recorded locally, that
is — it reaches the other machine at Finalize's push, which is soon enough,
since only this machine works this epic.

It stays **draft** until the feature review passes — you mark it ready only
in 5e, on a reviewer's passing verdict, never before.

### 5e. Feature review

Every task closed does not mean the feature is done: per-task criteria all
pass while the feature still has seams nobody owned, or criteria no task
claimed. Dispatch a fresh reviewer — never one that wrote the code.

**Refresh `main` and collect the in-flight siblings first.** The reviewer
re-fetches `origin/main` itself before certifying
([review-feature.md](references/review-feature.md) §6), but only you know what
*else* this session has in flight and about to land under it, so hand it the
list rather than making it guess:

```bash
git -C <feature-worktree> fetch origin main
git -C <feature-worktree> log --oneline \
  $(git -C <feature-worktree> merge-base HEAD origin/main)..origin/main
gh pr list --state open --json number,headRefName,isDraft \
  -q '.[] | "\(.number) \(.headRefName) draft=\(.isDraft)"'
```

Paste both outputs into the prompt below — the commits that landed since this
branch forked, and the open PRs. Empty first output is itself worth saying
("origin/main unchanged at `<sha>`"): it tells the reviewer its §6 re-fetch is
checking something you already looked at, and when it comes back non-empty
instead, that is a real change and not a first sighting. Paste those outputs;
do not summarize them into a conclusion the reviewer then inherits as fact
(§ "What you write yourself").

```
Agent({
  description: "Review feature <id>",
  model: "opus",
  run_in_background: true,
  prompt: `Read .claude/skills/work/references/review-feature.md and follow
it to review feature ${id} against its own acceptance criteria.
Worktree: ${worktree}  ·  Branch: ${branch}  ·  PR: ${pr}
origin/main as of dispatch: ${mainSha}; landed since this branch forked:
${logOutput or "nothing"}.
Open PRs that may merge under you while you review: ${prList}. Section 6's
re-fetch is where you find out whether one of them did — do it.
Repair what you can within the feature's scope. You decide the verdict —
ready or draft — but do NOT run gh pr ready; the orchestrator ships. On a
draft verdict, file beads tasks for what's missing. Report your verdict, why,
what you repaired, and any tasks you created.`
})
```

**The reviewer certifies; you ship.** The reviewer reports a verdict and sets
`metadata.review=passed`, but never runs `gh pr ready` — on this repo a
ready PR merges itself, so a reviewer marking its own certification ready is
self-approval, worse when it also committed repairs. You are the second
party: read the verdict, spot-check it, then ship it yourself. Three commands,
in this order — the fetch pairs with the reviewer's own §6 re-fetch, and it is
the last chance to notice that `main` moved between the verdict and the ship:

```bash
git -C <feature-worktree> fetch origin main
git -C <feature-worktree> log --oneline \
  $(git -C <feature-worktree> merge-base HEAD origin/main)..origin/main
gh pr checks <pr-url>
gh pr ready <pr-url>
```

Read the first two before running the third. Commits the reviewer's verdict
does not mention landed *after* it certified: if any of them touches the same
files as this diff (`gh pr diff <pr-url> --name-only` against that log),
merge `origin/main` in and send it back for a re-check rather than shipping a
verdict against a base that no longer exists. A red or pending required check
in `gh pr checks` is not shippable either — red goes to
[references/red-check-attribution.md](references/red-check-attribution.md),
pending waits.

`review=passed` and the verdict comment reach the shared tracker at
Finalize's push, like every other bead write.

**`gh pr ready` is the ship decision, not the ship** — arming can silently
fail, checks can redden, conflicts can appear, and none of it fixes itself.
The moment the ready call returns, read
[references/ship-feature.md](references/ship-feature.md) and follow its
state table until the PR is `MERGED` or honestly parked. The short form:
mark PRs ready **one at a time** (a burst races auto-merge against itself);
`MERGED` → `bd close` the feature but leave its worktree for Finalize (the
reviewer may still be live in it); a red check goes to
[references/red-check-attribution.md](references/red-check-attribution.md);
conflicts are yours to resolve and get a reviewer like any code you write.

- **Draft verdict** → four shapes routing differently (gaps → 5b, the
  reviewer's own substantive repairs → a second reader, red check →
  attribution, nothing actionable → park and 5f). Read the verdict comment,
  then [references/ship-feature.md](references/ship-feature.md) §3–4 to
  route it — the substantive-repair case in particular is a finished
  feature needing only an independent reader, never 5b and never a park.

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
building on it, and continue down this list.

**3. The epic's remaining work is blocked solely by an item in a *different*
epic** → claim and work **that specific blocking item** (task or feature, via
5a/5b as appropriate), not the other epic itself. Without this route a
cross-epic dependency is a permanent stall: no session on this epic can ever
unblock it, and the one-claim rule is about *epic* claims, which this does
not add. The item lives outside your owned territory, so the claim is an
acquisition — bracket it: `bd dolt pull`, re-verify it is still ready and
unclaimed, claim by id, `bd dolt push`. Claimed by the other machine after
the pull → it's being handled; go back to waiting or continue down this
list.

**4. The epic is dry but real budget remains (before T-90m)** → top up with
**continuation work**, claimed by specific id. Route 3 already established
that claiming an *item* inside another epic adds no epic claim; this route
uses the same move at scale. Never claim a second *epic* — that is the line
the concurrent-run check (step 3) depends on. If the epic went dry because
everything left is human-gated or cross-epic blocked, also park the epic per
step 3's `bd defer` route so the next session doesn't resume a dead queue.

Build the candidate pool from `bd ready --json`: ready **features and tasks
parented to other epics**, plus unparented bugs and chores. Drop from it:

- anything with the `human` label (this exclusion holds on every route);
- anything parented to the SDLC epic `computenet-wpvy` or labeled
  `skill-friction` — process work belongs to the SDLC orchestrator lane
  (step 7), never to a session running under the skill it would edit;
- anything with `parked_at` within 6h;
- **anything that is review or verification of output this session
  produced** — warm context is exactly what makes self-approval likely, and
  this is the one place the affinity ordering below argues for the wrong
  thing, so it's excluded outright, whatever its score;
- anything whose `metadata.files` overlaps a claim already `in_progress`
  (`bd list --status=in_progress --json`) — an ordering that seeks file
  overlap also seeks collisions, so check first and skip the collider.

Order what survives:

1. direct dependents of items this session completed;
2. file-surface overlap with this session's changed-file set — computed from
   paths actually changed on branches this session pushed
   (`git diff --name-only origin/main...<branch>`), never from titles or
   labels;
3. unparented ready bugs and chores;
4. general ready order.

Ties within a tier break by the next criterion down: an item that is both a
direct dependent *and* overlaps the changed-file set outranks a dependent
that doesn't, and so on.

**Admit a candidate only if its estimate fits the remaining budget with
margin.** Remaining budget comes from the step-2 monitor's notifications;
the estimate is the item's 45–60m sizing from breakdown (5b). Margin
defaults to 15 minutes; `WORK_CONTINUATION_MARGIN_MIN` overrides it. Never
admit on elapsed fraction alone — that converts idle time into half-finished
branches at slot end, which is worse than idling. The T-90m gate above still
applies on top.

A continuation claim is an acquisition on a surface this session does not
own — bracket it: `bd dolt pull`, re-verify the candidate is still ready and
unclaimed (and re-run the collision check above against the fresh state),
claim by id, `bd dolt push`. If the pull shows it claimed, take the next
candidate.

Work the admitted item by its shape: a feature via 5a, a task via its parent
feature's flow (5b's claim/metadata/worktree/dispatch, branched from that
feature's branch), an unparented bug or chore as its own worktree/branch/PR
like a feature. Every shape records the standard `branch`/`worktree`
metadata, so an overrun leaves resumable state a later session picks up
through the normal resume queries — never a stranded branch.

**5. Nothing can progress → Finalize**, closing the epic only when it
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

`MERGED` → `bd close <feature-id>`; its worktree comes off in the removal
sweep below, not here. `DIRTY`/`BEHIND` → resolve it per 5e if the budget
allows; it merges on its own afterwards. Then close any epic whose features
are now all closed (5f's check).

**Release the epic claim.** If the epic didn't close above, set it back to
open (`bd update <epic> --status=open`) — the claim binds it to *this
session*, not to this machine, and the next session on either machine must
select by priority, not by leftover assignee. The owner label stays as
provenance.

**Record slot utilisation on the epic** — 5f route 4's open question
(top-up vs batch-upfront vs epic resizing) gets settled with this data, so
every session writes it:

```bash
bd comment <epic> "utilisation: worked <N>m of <slot>m allocated; continuation items: <ids, or none>"
```

Worked minutes run from session start to entering Finalize; allocated is the
slot the routine named (default 300).

Do the friction log (step 7) **before** the push below, so its beads items go
out with everything else.

```bash
git -C <worktree> status --short   # per worktree touched this session
git -C <worktree> push
bd dolt push
```

**That `bd dolt push` is the session's publication** — every owned-territory
write since step 3 (closes, parks, metadata, breakdown children) rides on
it; only acquisition brackets pushed earlier. If it fails, say so at the top
of your summary in plain words, and ask for a human to run
`scripts/beads-nightly-sync.sh` — **nothing is scheduled to do it for you**
(`doc/ops/beads-sync-runbook.md` §5). Until someone does, this session's
unpublished tracker state is local-only, the other machine still sees this
epic's items as they were at step 3, and losing this machine loses the lot.
Never swallow the error and never report the session as clean without it.

Uncommitted leftovers mean an agent died mid-edit — report rather than
committing work you didn't verify.

**The worktree removal sweep — the session's only removals.** 5c and 5e
deliberately defer every removal to here, so this is the one place a worktree
comes off, and it runs after 5f, i.e. after the session has stopped
dispatching, and after the pushes above. Remove only what passes **both**
gates:

1. **Its agent has reported.** Every agent you dispatched into that worktree
   this session has returned a completion notification (or you `TaskStop`ped
   it and the stop landed). One still running → **leave the worktree**, name
   it in the summary, and let the next session's 5a reuse it. Do not wait on
   it here; Finalize is not the place to spend the remainder of the slot. A
   worktree no agent was dispatched into this session — resumed from an
   earlier session, or never used — passes this gate: that session's agent is
   gone and its notification will never arrive here ("One worktree, one live
   agent", step 5).
2. **`git -C <worktree> status --short` is empty.** Not empty → leave it and
   report; that is uncommitted work nobody has looked at. This gate says
   nothing about gate 1 — both, or neither.

```bash
git -C <worktree> status --short                 # gate 2; expect empty
git worktree remove "$PWD/../computenet-worktrees/<id>"
```

Remove the worktrees of **tasks merged in 5c**, of **features that closed**
(their local branch too), and of any extra fix worktree 5c's repair path
created. Leave unfinished features' and tasks' worktrees in place; the next
session reuses them via `metadata.worktree`.

Then `TaskStop` the budget monitor.

Summarize: the epic worked, tasks completed, features left in draft (with PR
urls), items blocked on parked questions (and what they ask), stale claims
released at startup, friction logged, and why the session stopped.

Unfinished **tasks** left `in_progress` are released by the next session's
startup sweep (step 3) once they age past 6h. That sweep — not a hook — is
what stops a crashed run from locking work forever.

The sweep deliberately does **not** touch epics or features. An **epic**
claim is released explicitly: here at a clean Finalize, or at the next
startup on this machine for a crashed run (step 3) — either way it re-enters
`bd ready` and the next session selects purely by priority. A **feature**
claim outlives the session on purpose: it is the resume marker, and
whichever machine holds the epic claim takes it over in 5a. A crashed
session's epic claim was never pushed and keeps nobody out anyway
([references/claim-sync.md](references/claim-sync.md)); its release
propagates with this machine's next Finalize push. Name any feature you
leave `in_progress` in the summary so that's visible rather than silent.

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

**Filing under the SDLC epic is a shared-surface write** — the epic belongs
to no session, both machines write to it, and its orchestrator lane reacts
to what lands there. So the whole registration is bracketed
(pull → dedup → write → claim → push), and the claim decides **which
machine's orchestrator drains the item** — exactly one:

```bash
bd dolt pull                                      # see the other machine first
bd search "<a few distinctive words>" --json      # dedup against fresh state
```

**One issue per kind of friction, deduped** — the point is seeing what
recurs, and ten identical issues are worse than one issue with ten comments.

Found one → **upvote it**: comment with this session's instance (what you
were doing, what happened, what it cost) — comment count is the remediation
priority. Then read its assignee: claimed by the other machine → done, its
orchestrator owns it and your comment raised its priority. Unclaimed →
claim it for this machine (`bd update <id> --claim`).

Not found → create it as a **bug or feature under the SDLC epic**
`computenet-wpvy`: a `bug` when the skill misbehaved — an instruction that
failed, contradicted reality, or didn't cover your case — and a `feature`
when the skill worked as written but is missing a capability that would
have made the session better. Then claim it. (Parenting plus the label
keeps friction out of 5f's continuation pool; the drain is
`.claude/skills/remediate-friction/SKILL.md` today, a reactive orchestrator
eventually.)

```bash
SKILL_V=$(bd show <epic> --json | jq -r '.[0].metadata.skill_version')   # recorded at claim (step 3)
bd create --type=<bug|feature> --priority=2 --label=skill-friction \
  --parent=computenet-wpvy \
  --metadata "{\"skill_version\":\"$SKILL_V\"}" \
  --title="work skill: <the friction in one line>" \
  --description="<what the skill says, what actually happened, what you did instead, what it cost>" \
  --acceptance="<what would have to change in the skill for this not to recur>"
```

Close the bracket with `bd dolt push` once every friction item is filed and
claimed (one push for the batch, not one per item). That push is what makes
the claim exclusive — the other machine's orchestrator sees it on its next
pull and stays off — and what carries the report off this machine: a
friction issue that never syncs is exactly the lost-transcript problem this
step exists to solve. Push rejected → pull, re-check your items against
what arrived, push again.

Write it for someone editing `SKILL.md` next week with none of your context:
name the step, quote the instruction, say what actually happened.

Review the accumulated log — open count and per-item comment totals — with
one command:

```bash
bd list --parent=computenet-wpvy --label=skill-friction --status=open --json
```

Comment count is the signal. One report is an anecdote; the same issue
commented on by four sessions is the next thing to fix in the skill.

**Log honestly, including your own mistakes.** A misread instruction is the
most useful entry there is — it means the instruction can be written so the
next run can't misread it. Nothing here is graded; a session that logs three
frictions is more valuable than one that logs none.
