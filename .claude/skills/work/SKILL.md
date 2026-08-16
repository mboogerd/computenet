---
name: work
description: Runs one unattended beads work session end to end — claims an epic, breaks it into features and tasks via Fable subagents, and implements each feature in its own worktree, branch, and draft PR, with its tasks running as parallel reviewed branches. Claims are crash-safe and machine-scoped, so two machines can run this concurrently on a schedule without colliding or deadlocking. Use this skill whenever a cron job, scheduled task, or routine kicks off a work slot, or the user says "/work", "work the queue", "pick up the next beads task", "start working through the backlog", "keep the machines busy", or otherwise wants autonomous progress on beads-tracked work — even if they don't mention beads, epics, or this skill by name.
---

# /work

One session = one epic, worked until it's dry or the budget's gone. You are
the orchestrator, not the implementer: every item — breakdowns and reviews
included — goes to a dispatched subagent. No inline "just this once"; that is
how a multi-hour session's context balloons and drifts.

Rules here are one-line distillations of real incidents; the cited bead id
(`computenet-…`) holds the full story. Don't relax a rule without reading its
bead.

## The three rules

When this file doesn't cover your situation, decide from these:

1. **Get things out of the way fast; make progress where you can.** Stuck is
   a reason to move to the next thing, not to stop. Three of five features
   landed beats stalling on the first.
2. **Integrate continuously.** Merge reviewed work the moment it's ready,
   keep branches close to `main`, open PRs early so CI runs while the work is
   built. Divergence is the expensive part, and it grows quietly.
3. **Unclear AND costly, risky, or hard to revert → park the question on the
   narrowest stuck item and continue elsewhere**
   ([references/ask-human.md](references/ask-human.md)). Parking is how you
   keep moving, not how you stop.

These prevent the two orchestrator failure modes: spinning on one blocked
item until the budget dies, and guessing on an expensive fork because asking
felt like giving up.

## bd traps

- `bd ready` hides `in_progress`/`blocked`/`deferred`; `bd list` hides
  *closed* unless `--all`. Every check below uses the one it means.
- `--parent` scope differs by subcommand: `bd list --parent` is **one level
  deep**; `bd ready --parent` and `bd blocked --parent` are
  **descendant-scoped**. And `bd blocked` lists only items blocked by an open
  dependency edge — a hand-set `--status=blocked` (an ask-human park) is
  invisible to it.
- `bd show <id> --json` returns a **list** — unwrap `.[0]` or every field
  reads `null`. It never includes comment bodies, only `comment_count`.
- Epic- and feature-sized output overflows the inline tool-result limit
  (`bd show` on one epic: ~83KB; `bd ready --type=epic --json`: ~43KB) and
  gets truncated or persisted. Redirect any `--json` call that *can* be big
  to `"$SCRATCH/<name>.json"` and read the file, same as comments below
  (computenet-csm).
- `bd` prints warnings on stdout **before** the JSON, so `jq` and
  `json.loads` fail on the raw stream; slice from the first line starting
  `[` or `{` (`sed -n '/^[[{]/,$p'`) before parsing.
- Comments are read one way only: `bd comments <id> --json >
  "$SCRATCH/c-<id>.json"`, then read the file. Inline reads truncate on
  long-lived beads (~34KB observed) and present as *fewer comments than
  exist* — the "has a human answered this?" misread that wrongly deferred an
  epic.
- `bd create` takes the title **positionally** or via `--title` (`-t` is
  `--type`); `bd comment` takes the body positionally or via `--file` (not
  `--body-file`); clearing a metadata key is `--unset-metadata <key>`
  (`--set-metadata key=` merges, it does not clear).
- `bd` calls are slow and `bd dolt pull`/`push` can run past 120s — give
  sync commands a ≥300s timeout (computenet-9oq, computenet-9r8).

`$SCRATCH` throughout is a **session-unique** temp dir: create it once as
`SCRATCH=$(mktemp -d "<harness scratchpad>/work.XXXXXX")` — concurrent
sessions sharing a plain scratchpad path overwrite each other's dumps
(computenet-wpvy.43).

## Scripts and references

The scripts do the fiddly parts — where a wrong flag or missed filter
silently loses work. Prefer them to hand-rolling. After editing one, run its
sibling test (`<name>.test.sh`, or `next-batch.test.py`).

| `.claude/skills/work/scripts/…` | Does |
|---|---|
| `sweep-stale-claims.sh` | Reopens this machine's task claims abandoned by a dead run (skips reviewed-and-waiting and skill-friction items) |
| `sweep-merged-prs.sh` | Closes beads whose PR merged after their session ended; removes their worktrees |
| `next-batch.py` | Next set of tasks safe to run in parallel — file-disjoint AND within machine capacity |
| `ensure-worktree.sh` | Attaches a worktree on a branch, new or resumed, or fails loudly |
| `epic-of.sh` | Resolves a bead's effective epic (`.parent` chain, else dotted prefix) |
| `claim-epic.sh` | Claims or takes over an epic and pushes the acquisition (the claim-as-lock bracket) |
| `feature-branch.sh` | Resolves a feature's branch + worktree, minting `-rN` when the old PR squash-merged |
| `publish-beads.sh` | Publication push with rejection recovery, reading output not exit codes |
| `file-friction.sh` | Files a friction item collision-free under the SDLC epic and claims it |

(`scripts/beads-nightly-sync.sh` is the **repo-root** catch-up job; no
scheduler runs it — never assume a sync will happen on its own.)

References carry the deep protocols; read one when its situation arises:

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

Dispatched output gets a reviewer; your own — commit messages, PR bodies,
dispatch-prompt framing, conflict resolutions — lands in `main`'s history and
in other agents' heads as written. In force throughout:

- **Claim the observation, never an untested mechanism.** A causal sentence
  needs a distinguishing run (quoted, with run id and verbatim `FAILED` line)
  or a mechanism that cannot be otherwise, cited to its artifact. "Whether
  that affects the flakes is untested" is a true sentence; count counts from
  the output before writing them.
- **Dispatch prompts relay artifacts, not framing.** A subagent cannot tell
  your speculation from your evidence; hand it the run id, job, and `FAILED`
  line plus "mechanism unknown, read the log".
- **A claim about what a tool or CI platform does is the same kind of
  claim** — it needs a run that shows it or a doc citation, else write "I
  believe X; verify it". An untested `workflow_dispatch` assertion reached
  three dispatch prompts and two PR bodies before a reviewer's one-line
  experiment disproved it (computenet-4l3l). Try it first.
- **Code you write yourself** (conflict resolutions, unblocking fixes) goes
  to a reviewer on the same terms as task work (5c).
- **An Agent dispatch can be refused on its prompt's *wording*, not its
  action** — "denied by the Claude Code auto mode classifier" on a dispatch
  shaped like six allowed ones means the vivid risk language tripped it
  ("catastrophic failure mode", "bypassing the gate"; computenet-my7).
  Unattended, nobody can grant it: re-dispatch the same substance in plainer
  words ("check that all five jobs still run on every pull request") rather
  than skipping the step — do not soften what the review must *check*, only
  how it is phrased. If rephrasing is also refused, the step was skipped:
  say so in the summary, naming the refused dispatch, like a refused
  `bd comment` (step 7).

Full case: [references/orchestrator-authorship.md](references/orchestrator-authorship.md).

## 1. Identity

```bash
echo "${BEADS_ACTOR:?BEADS_ACTOR must be set, uniquely, per machine}"
```

Unique per machine — it's how claims tell two machines apart. Never fall back
to `git config user.name` (identical on every machine here). Unset → stop and
report; a wrong identity is worse than a dead run.

## 2. Arm the budget

Don't burn turns polling a clock — arm a timer that tells you:

```
Monitor({
  description: "work session budget",
  persistent: true,
  command: `sleep 11700; echo "BUDGET T-90m: finish the current feature; start no new one"
sleep 2700;  echo "BUDGET T-45m: no new dispatches; review and merge what is in flight"
sleep 2700;  echo "BUDGET EXPIRED: go to Finalize now"`
})
```

Fires at 3h15m / 4h / 4h45m of a 5h slot (the last 15m is Finalize); scale
proportionally if the routine names a different slot. **Note the monitor's
task id** — `TaskStop` it when you reach Finalize. **`persistent: true` is
load-bearing; do not add `timeout_ms`**: verified by probe 2026-08-13,
`persistent` genuinely overrides the 300000ms default, and the documented 1h
maximum would otherwise kill the monitor mid-slot.

| Notification | Do, at the next decision point |
|---|---|
| T-90m | finish the feature you're on; start no new work — this gates 5f routes 1, 3, 4 and step 5's direct-child loop alike |
| T-45m | dispatch nothing new; review and merge what's already running |
| EXPIRED | **Finalize**, whatever state you're in |

Notifications land at your next turn — they don't interrupt a wait, and a
hung dispatch still wakes you at the deadline (5b).

Three standing disciplines:

- **This is the only persistent monitor.** Every other watch is bounded
  (`for i in $(seq 1 N)`), exits on a terminal state, and there is at most
  one alive; `TaskStop` the old one and let the stop land before arming a
  replacement.
- **A failed `gh` call is not a reading.** Socket exhaustion (`dial tcp …
  can't assign requested address`) says nothing about the PR; the idiom
  `gh … 2>/dev/null || echo "?"` folds it into a green nobody earned. Every
  wait loop separates "the query failed" from "the condition is not met".
- **Between notifications you have no sense of elapsed time.** Run
  `date -u +%H:%M` before any budget-gated decision — one session misread
  1h31m as ~3h20m and nearly idled a third of its slot (computenet-776).

## 3. Sync, release stale claims, take one epic

```bash
git fetch origin main
bd dolt pull        # >=300s timeout; if it hard-fails on a merge conflict,
                    # that needs operator resolution (computenet-gq0)
```

**If this pull fails, stop the session and report.** It's the only look you
get at the other machine's state; claiming against stale state is the
computenet-kg7/3v8 failure, where claim safety silently vanished for a slot.

**Sync brackets acquisition, not writes; ownership makes writes free.**
Writes under your claimed epic stay local and ride Finalize's publication
push. Acquisitions — the epic claim below, a cross-epic item claim (5f), a
child claim under a *closed* epic (5b), the friction filing (step 7) — each
get their own pull → verify → write → push bracket. [references/claim-sync.md](references/claim-sync.md) is the full
statement.

**Check you are running the current skill.** Session worktrees branch from
the launching checkout's HEAD, which nothing here fast-forwards; a slot has
started 44 commits behind, 8 of them touching this file (computenet-wpvy.35):

```bash
git hash-object .claude/skills/work/SKILL.md               # running
git rev-parse origin/main:.claude/skills/work/SKILL.md     # current
```

Different → re-read this skill and its references via
`git show origin/main:<path>` and treat that copy as authoritative for the
session. Note the revision in the summary; log a multi-commit gap as friction.

**Release what dead runs left behind, in this order:**

```bash
.claude/skills/work/scripts/sweep-stale-claims.sh      # --dry-run to preview
bd list --status=in_progress --assignee="$BEADS_ACTOR" --limit 0 --json \
  | jq '[.[] | select(((.labels // []) | index("skill-friction")) | not)]'
```

The sweep reopens this machine's abandoned task claims (`bd ready` hides
`in_progress`, so nothing else ever would). Report what it released — the
same item released repeatedly means work is *failing*, not crashing. Items it
reports "complete, awaiting decision" are reviewed work awaiting a ship call,
not fresh work.

The listing is the **liveness check**, and it covers *every* type, not just
epics: a session working a standalone bug holds no epic claim at all, so an
epic-only query reads "alone" while another run is live in a shared worktree
(computenet-sec: duplicated fix, two Gradle builds corrupting one `build/`
dir, a commit mixing both sessions' work). `skill-friction` items are
excluded because their claim is routing to an orchestrator lane, not a work
session — one is often stamped minutes ago by a session that just finished.

Any row with `updated_at` within 15 minutes probably belongs to a live
overlapping run on this machine (same `BEADS_ACTOR`, indistinguishable) —
stop and report rather than colliding with it. Check every row. Older rows:
an *epic* is a crash leftover — release it
(`bd update <id> --status=open --assignee=""`); leave non-epic rows alone —
stale *tasks* the sweep above already reopened, and a stale *feature* is the
5a resume marker, not a leak.

**Only after the liveness check, reconcile beads against merged PRs:**

```bash
.claude/skills/work/scripts/sweep-merged-prs.sh        # --dry-run to preview
```

Auto-merge lands PRs minutes *after* their session ends, so no session
observes its own merge; this closes what drifted and removes those worktrees.
It runs here and nowhere earlier — it removes worktrees, and a concurrent
session's just-merged worktree is clean *by definition*, so sweeping before
the liveness check deletes a live run's state. It is deliberately
unconditional (no epic/claim/review filter): three narrow re-checks all
missed the same four leaked features (computenet-wpvy.25). Read its exit
code: 3 = nothing was checked (`gh`/`bd` unreachable), 1 = some closes or
removals failed — neither is "clean sweep"; say which you got.

**Select and claim one epic** — highest priority, skipping the SDLC epic
(see "The SDLC exclusion" below; the filter is in the command because a
filter applied by eye gets forgotten):

```bash
bd ready --type=epic --json > "$SCRATCH/ready-epics.json"   # ~43KB inline — overflow
jq '[.[] | select(.id != "computenet-wpvy")
        | {id, title, priority, assignee, updated_at}]' "$SCRATCH/ready-epics.json"
# full descriptions stay in the file — read the chosen epic's from there
.claude/skills/work/scripts/claim-epic.sh <the id you selected>
```

`claim-epic.sh` claims, or **takes over** an `open` epic whose stale assignee
is residue (a `--claim` refusal on an open epic is provenance, not a live
claim — skipping it silently demotes the queue's top pick, computenet-1d6),
stamps the `owner:` label and `skill_version` metadata, and **pushes the
acquisition — the push is what turns the claim into a lock**. Exit 1 → not
claimed (live run or lost race): pick the next candidate or stop. Exit 2 →
claimed locally but unpublished: stop the session and report.

Never `bd ready --claim` (it claims whatever is first *at claim time*, not
what you read).

**Before committing to an already-broken-down epic, check it has workable
surface** (`bd ready --parent` is descendant-scoped):

```bash
bd ready --parent=<epic> --json      # workable = items NOT labeled 'human'
bd list --parent=<epic> --type=feature --status=in_progress --json  # resumable
```

Zero workable items and nothing resumable, for an epic that *has* children →
park it and select the next:

```bash
bd comment <epic> "Parking: no workable surface. <ids: human-gated / blocked on <other-epic>>"
bd defer <epic>
```

`defer` hides it from `bd ready` on both machines while keeping provenance; a
human reopens it. (An epic with *no* children just needs breakdown — step 4.)

Nothing claimable at all → report and stop.

**One epic *claim* per session** — the claim is how a concurrent run tells a
live session from a crash. The rule limits claims, not work: when the epic
runs dry, 5f says what you may still pick up; idling for hours is a failure
mode, not compliance. There is deliberately no resume preference across
sessions — a released epic re-competes on priority.

## 4. Ensure the epic has features

```bash
bd list --parent=<epic> --all --json
```

`--all` matters: without it a *finished* epic reads as never-broken-down and
you'd re-create its feature set.

Empty → dispatch the breakdown, **wait for its completion notification**,
re-run the query. Don't fall through to step 5 while it runs.

```
Agent({
  description: "Break down epic <epic>",
  model: "fable",
  run_in_background: true,
  prompt: `You have no worktree, so read the reference from the REMOTE,
never the main checkout's working tree — its local branch is stale:
  git -C <main-checkout> show origin/main:.claude/skills/work/references/epic.md
Follow it to break epic ${epic} into features. Run bd with
-C <main-checkout>. It is already claimed and labeled — skip both.
Report the feature ids created.`
})
```

**Still empty? Check for a deliberate park before retrying.** `epic.md`
requires the breakdown to verify the epic's load-bearing premises and park
rather than produce children inheriting a false one — a correct refusal
looks exactly like a dead breakdown if you only count children
(computenet-wpvy.10). A park is `status=blocked` + `assignee=human` + the
`human` label + a `QUESTION:` comment:

```bash
bd show <epic> --json | jq -r '.[0] | "\(.status) \(.assignee) \(.labels)"'
bd comments <epic> --json > "$SCRATCH/epic-comments.json"
```

Parked deliberately → don't re-dispatch, don't log friction; `bd defer` the
epic (step 3's route) and select the next one. Otherwise retry **once**;
twice-failed → park a question on the epic, log it as friction (a defect in
the epic or `epic.md`, not bad luck), and end the session — one epic per
session means there's nothing to switch to.

## 5. Work features

**Parking state lives on the bead, not in your head** — sessions get
compacted, and in-context lists are the state whose loss makes a session spin
until the budget dies. When any item can't progress:

```bash
bd update <id> --set-metadata parked_at=$(date +%s)     # clear: --unset-metadata parked_at
```

Every selection below skips items with `parked_at` in the last **6 hours**
(this session's parks plus a recent session's, while letting stale ones
retry).

**Re-triage human-parked items — once per session, on the first pass through
step 5.** Ask-human parks (`blocked` + `human` + `assignee=human`) are
invisible to `bd ready` (status) and to `bd blocked` (no dependency edge), so
they rot after their blocker clears (computenet-6i1: 3 of 4 parked items were
finishable). List them repo-wide and keep the ones under this epic:

```bash
bd list --status=blocked --limit 0 --json | jq -r '.[] | .id'
.claude/skills/work/scripts/epic-of.sh <each id>       # keep those under <epic>
bd comments <id> --json > "$SCRATCH/parked-<id>.json"  # read the QUESTION
```

A good park names its blocker and unblocking condition — one read, one
yes/no. **Unpark only on observable evidence**: a human answered (note
`bd human respond` *closes* the item, so answered parks live in `bd human
list`, not the blocked query — reopening is the unpark); the named PR merged
/ bead closed / secret exists — check it; or the item was superseded →
`bd close` with the reason. Elapsed time, staleness, or your own view that
the answer is obvious are **not** evidence — it was parked because an
unattended session may not make that call. Unpark fully:

```bash
bd update <id> --status=open --assignee="" --remove-label=human
```

(All three: a leftover `human` label re-hides it from every filter; a
leftover assignee makes it permanently `--claim`-refused.) The 6h `parked_at`
window doesn't apply here — these are cross-session and evidence-gated.

**Select the feature.** Resume before starting new — this query is the
*only* path back to an in-progress feature:

```bash
bd list --parent=<epic> --type=feature --status=in_progress --json   # resume first
bd ready --parent=<epic> --type=feature --limit 1 --json             # else first ready
```

A resumed feature carrying `metadata.review=passed` was certified last
session — check its PR (`gh pr view <pr> --json state`); `MERGED` →
`bd close` and move on, don't re-review.

**An epic can have no feature layer at all** — bugs/tasks/chores parented
directly to the epic (computenet-dqy: 69 children, one feature). Both feature
queries return empty while work sits ready, so before falling through:

```bash
bd ready --parent=<epic> --json      # no --type filter
```

Non-empty → work these directly: each gets its own worktree, branch, and PR
off `origin/main` via 5a's flow with the item id in place of the feature id —
including tasks parented straight to the epic (5b's task flow needs a feature
branch to cut from and merge into, which doesn't exist here —
computenet-9xj). Same filters as everywhere: skip `human`-labeled and
recently-parked items. **When one finishes, re-run this query and take the
next** — don't fall to 5f while the epic still has direct children; after
T-90m, stop taking new ones. Only when this query too is empty does 5f apply.

Apply the same two filters to the feature picked above; nothing survives →
**5f**.

**Structure**: a feature is the unit of integration — its own worktree,
branch, and draft PR, into which reviewed task branches merge. A task is its
own worktree and branch, cut from the feature branch. Work **one feature at a
time**; the parallelism lives in its tasks.

**One worktree, one live agent.** A worktree belongs to the agent dispatched
into it until **that agent's completion notification arrives in this
session** (or its `TaskStop` lands). Three states:

- never dispatched into this session (first run, or resumed from an earlier
  session — that session's agent is gone and its notification will never
  arrive here; don't wait for one) → free;
- dispatched and the notification arrived / stop landed → free;
- dispatched and still running → occupied: no second agent in it, no removal.

Can't say which → treat as occupied. `git -C <worktree> status --short`
answers "any unsaved edits?" — a fact about the *tree*. It reads clean at
exactly the moment an agent is finishing bookkeeping (computenet-ys7: a
worktree removed under a live reviewer seconds after `gh pr ready`). Keep it
as the second guard; never read it as "nobody is working here".

A worktree already on disk that this session did not create may also belong
to a *concurrent session* on this machine, not just a dead one — step 3's
liveness check races a run that starts mid-slot. Before adopting one, check
its bead: `in_progress` with `updated_at` in the last 15 minutes → occupied,
leave it. Guessing wrong costs real work: two agents in one worktree converge
on the same fix independently, and two concurrent Gradle builds there clobber
each other's `build/` dirs, failing with errors that read as genuine test
failures (computenet-sec: `NoSuchFileException` on an in-progress-results
bin in `:gen:test`).

### 5a. Set up or resume the feature

```bash
bd update <feature-id> --claim                              # idempotent if yours
.claude/skills/work/scripts/feature-branch.sh <feature-id>  # -> "<branch>\t<worktree>"
```

A resumed feature may still be assigned to the other machine — holding the
epic claim makes it yours: claim it, and expect only what that machine
*pushed*, never its local worktree (`metadata.worktree` may be its path —
that's why the script recomputes locally).

The printed worktree lives under `../computenet-worktrees` beside the main
checkout — that directory is `<worktree-root>` wherever this file says it
(always recomputed locally, never read from `metadata.worktree`).

`feature-branch.sh` reads `metadata.branch` and handles the resume trap this
repo's squash-merges create: a fully-landed branch reads as "N commits
ahead", and merging `origin/main` into it re-lands reviewed content as a
conflict (computenet-dqy.55). Merged PR → it mints `feature/<id>-rN`,
repoints metadata, and clears `metadata.pr` (5d only creates a PR when that
is unset). Fresh feature → it records metadata *before* anything exists, so
a crash can't strand an unrecorded branch. **Use the branch it prints for
everything below** — attach, merge, push, 5b's task base, 5d's `--head` —
never the literal `feature/<feature-id>`.

Attach and verify:

```bash
# Substitute the printed values literally — each fenced block is a separate
# Bash call and shell variables do not survive between calls; an empty var
# here silently retargets origin/main or the main checkout.
.claude/skills/work/scripts/ensure-worktree.sh <worktree> <branch> origin/main

if git -C <worktree> fetch origin <branch> 2>/dev/null; then
  git -C <worktree> merge-base --is-ancestor FETCH_HEAD HEAD \
    && echo "OK: worktree contains origin/<branch>" \
    || echo "STOP: on the branch at the wrong commit — origin/<branch> is not in HEAD"
else
  echo "OK: origin has no <branch> yet (first run, nothing to compare)"
fi
```

Read the verification line, and only it. `STOP` → do not enter 5b; proceeding
silently orphans reviewed work while looking clean (computenet-aeg). Either
`OK` is fine.

`ensure-worktree.sh` is idempotent: leaves an attached worktree alone,
attaches local branches, tracks remote-only branches at the remote tip,
fast-forwards strictly-behind, keeps strictly-ahead (unpushed work), and
fails loudly on divergence.

**A dirty inherited worktree may be a half-applied MUTATION** — this repo
verifies pins by mutating production code, and an agent killed mid-mutation
looks identical to one killed mid-improvement. Classify before acting
(computenet-leg):

```bash
ls <worktree>/.mutation-in-progress 2>/dev/null && cat <worktree>/.mutation-in-progress
git -C <worktree> status --short && git -C <worktree> diff
```

Marker exists → `git -C <worktree> checkout -- .`, delete the marker, say so.
No marker and the diff reads deliberate and self-consistent → keep it, and
report that you kept work you didn't write. Can't tell → leave it, park a
question, move on — reading the diff is the only discriminator; never commit
or discard on a guess.

Then bring the branch up to date:

```bash
git -C <worktree> pull --ff-only 2>/dev/null || true   # no upstream yet is fine
git -C <worktree> merge origin/main -m "Merge main into <branch>"
git -C <worktree> push -u origin <branch>
```

The `merge origin/main` is not optional on a resume — nothing else in this
flow brings `main` in, and a feature carried across sessions otherwise
discovers days of drift at the final gate. Conflicts are yours; re-run the
affected module suite after (a hand-resolved merge is code nobody reviewed).

### 5b. Break down, then batch tasks

```bash
bd list --parent=<feature-id> --all --json
```

Empty → dispatch one Fable breakdown, wait, re-run. Still empty after a
second attempt → park a question on the feature, set `parked_at`, go to 5f.

```
Agent({
  description: "Break down feature <id>",
  model: "fable",
  run_in_background: true,
  prompt: `You have no worktree, so read the reference from the REMOTE,
never the main checkout's working tree — its local branch is stale:
  git -C <main-checkout> show origin/main:.claude/skills/work/references/feature.md
Follow it to break feature ${id} into tasks. Run bd with
-C <main-checkout>. It is already claimed — skip claiming.
Report the task ids created.`
})
```

Otherwise ask for the next batch:

```bash
.claude/skills/work/scripts/next-batch.py <feature-id>
```

Returns `{batch: [{id, model, files, worktree, branch, resumed}], skipped,
verdict, parked, capacity}`. The batch is what can safely run at once:
resumables first (nothing else ever picks them back up), then ready tasks
whose `files` claims don't overlap the batch; a task with no claim comes back
alone. That is correct scheduling either way — but check the description
before commenting: `files unknowable before diagnosis` marks a deliberate
diagnosis-first task (feature.md's exception, computenet-ahu), dispatched
alone by design; any other missing claim means the breakdown needs fixing —
comment on it. The batch is also bounded
by **machine capacity** (`capacity.max_parallel = max(1, cores // 5)`,
measured — see `capacity_limit()` in the script): parallel Gradle contention
lands as timeouts in exactly the suites the epics exist to characterise,
corrupting the evidence (computenet-k9d.2). Entries `skipped` as
`over machine capacity` are a hold, not a problem — next round takes them.
**Don't raise the cap by hand**; it rests on measurement, and the derivation
and its limits live in the script.

An entry with empty `model` → dispatch at `sonnet`, comment on the task, log
friction. **Empty batch** → read `verdict`, don't infer:

| `verdict` | Meaning | Do |
|---|---|---|
| `all-closed` | every task closed | **5e** |
| `parked-residue` | all non-closed children are ask-human parks; the feature's own work is done | **5e** |
| `blocked` | work remains this session can't start | set `parked_at`, go to **5f** |
| `no-tasks` | no tasks at all | breakdown died — treat as empty above |

`parked-residue` exists because parking a finished feature over follow-up
questions *its own implementation filed* strands CI-green work with no path
to `main` (computenet-eic). Those children are deliverables; pass the
script's `parked` array into 5e's `${parkedChildren}` — don't re-derive it
with your own filter, which can drift from the predicate that produced the
verdict. (`parked` is only meaningful on an empty batch.)

**Before claiming each task:**

- **Re-derive `metadata.files` against the bead's current decided design.**
  The claim was set at filing; a design answered later can reach outside it
  (computenet-dqy.37 required violating its own claim). Design reaches wider
  → widen the claim and comment why. The dispatch prompt below also tells the
  implementer to report-and-widen rather than choose silently.
- **Restate any cross-bead write the bead's criteria demand — ids and
  action — in the dispatch prompt.** Authorization living only in the bead is
  invisible to the policy check, which reads the prompt; an agent doing
  commissioned cross-posting got flagged and the orchestrator adjudicated its
  own commission as an overstep (computenet-dqy.72, computenet-szdd). Write
  it once as `${crossBeadWrites}` and carry the **same string** into the 5c
  and 5e reviewer prompts (`none` is the normal value and means something:
  no cross-bead write is authorized). Reserved actions stay yours: closing,
  re-prioritising, reassigning, re-parenting or claiming any *other* bead is
  the orchestrator's — a criterion demanding one is done by you after the
  merge, and the prompt says so ("<id> is closed by me, not by you").

Claim, record, attach:

```bash
bd update <task-id> --claim
bd update <task-id> \
  --set-metadata worktree=<worktree-root>/<task-id> \
  --set-metadata branch=task/<task-id>
.claude/skills/work/scripts/ensure-worktree.sh \
  <worktree-root>/<task-id> task/<task-id> <feature-branch>
# <feature-branch> = the feature's recorded metadata.branch, re-read here —
# an empty 3rd arg silently becomes origin/main.
```

These child claims are not re-synced — they're inside the epic this machine
claimed, and the epic claim is the lock that keeps the other machine out of
the whole subtree. One synced claim per level you descend, not one per
sibling.

**Unless the epic is closed.** A closed epic locks nothing, so a child claim
under one is an acquisition and gets pushed like any other:

```bash
bd show <epic> --json | jq -r '.[0].status'    # local read, no network
# closed → bd dolt push        (>=300s timeout) right after the claim
```

This is the `computenet-dqy.40` window (computenet-k9d.3). A session stays
inside its epic finishing in-flight children until its own Finalize, which
can be hours after the epic closed — and for all of it the children read as
unclaimed to the other machine, which 5f route 3 then lets it take. The push
costs one round-trip and only ever fires in that anomalous case: while the
epic is open the check is a local read and nothing syncs.

**Read the task's base commit off `ensure-worktree.sh`'s stderr** — the line
`ensure-worktree: base commit (this branch is cut FROM it; it is NOT a diff
baseline): <sha> <subject>` — verbatim, matching that prefix. On a resumed
branch a further line reports prior work at HEAD; that sha is a *work*
commit, not the base (quoting it as a baseline once made `git diff` read
empty — one step from certifying a no-op). If it scrolled away:

```bash
git -C <task-worktree> log --oneline -1 \
  "$(git -C <task-worktree> merge-base <feature-branch> HEAD)"
```

Say what the sha *is* every time; a bare commit id reads equally as branch
head, work commit, or baseline. Don't narrate the base from memory or from
the order you picked features — a dispatch once told an agent that merged
production code "will not be visible" in a worktree that contained it,
inviting re-implementation (computenet-88v).

Dispatch the batch in one message. Anything you add reaches the agent as
established fact — relay artifacts, not mechanism:

```
Agent({
  description: "Implement <id>",
  model: <task's metadata.model — "sonnet" or "opus">,
  run_in_background: true,
  prompt: `You are implementing beads task ${id}, already claimed — do not
claim another. Work ONLY in your own worktree at ${taskWorktree}, on branch
${taskBranch}. Do not touch the main checkout, the feature worktree, or
another task's worktree.
Your branch's BASE COMMIT, observed at dispatch — the commit the branch was
cut from, NOT a diff baseline: ${taskBase}. Anything merged into main before
it is already in your worktree; check with git rather than assuming either
way. To diff your own work, use git merge-base <feature-branch> HEAD,
computed inside your worktree.
Read it: bd show ${id} --json (run bd with -C <main-checkout>; only that
checkout has the beads database)
Then read the skill files FROM YOUR OWN WORKTREE — ${taskWorktree}/.claude/
skills/work/references/task.md — and follow it. Do NOT read them from the
main checkout: it is where bd lives, and its local branch is stale.
Stay inside your metadata.files claim — sibling tasks are running on sibling
branches and merge into the same feature branch. If the bead's own design or
acceptance clause REQUIRES a file outside the claim, do not choose between
them silently: report which file and which clause, and I will widen the claim.
Tracker writes: ${crossBeadWrites or "none authorized — write only to this
bead and to items you create."} Whatever that line says, never close,
re-prioritise, reassign, re-parent or claim any bead other than your own.
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

Don't set `isolation: "worktree"` — you create and record the worktree so it
outlives the agent.

**`bd` lives in the main checkout; current skill files do not** — this
governs every dispatch in this file. Worktrees are cut from `origin/main`, so
an agent reads `.claude/skills/work/**` from *its own worktree*; the main
checkout's working copy drifts (measured 44 commits behind, computenet-kcu).
An agent with no worktree reads via `git show origin/main:<path>`. Say which
in every prompt.

**While a batch runs, never read a running agent's output** — not
`TaskOutput`, not `Read`, not `tail`. For a local agent that file is the
full JSONL transcript (thinking blocks, tool payloads); one call dumped
tens of thousands of tokens into orchestrator context, unrecoverably, in a
session built to run for hours (computenet-dal). `TaskOutput`'s own text
reads as a mild preference — treat it as a context hazard. The safe
progress checks are: the completion notification (it always comes), the
task's own bd comments (`bd comments <id> --json > "$SCRATCH/..."` —
task.md has agents comment at parks and at finish), and
`git -C <task-worktree> log --oneline` for commits landing. An agent that seems slow is waited on or `TaskStop`ped at
the budget deadline below — there is nothing useful between.

**On batch completion** (wait for the whole batch — a staggered re-batch
computes overlap against a moving set): files touched outside a claim → fix
that task's `files` metadata, and for a diagnosis-first task write the real
claim from the diff — the empty claim was unknowable, not violated; a task parked a question → that's one task, not
the feature; a task reported done → 5c. **If a budget notification arrives
while you're still waiting, the batch is over its limit**: `TaskStop` the
stragglers, leave them `in_progress` with a comment (worktrees and branches
survive; a later batch resumes them), continue with what returned, log
friction — a task shape that reliably runs long is a sizing defect in
`feature.md`.

### 5c. Review each task, then merge it

One reviewer per completed task, concurrently, at the task's own model —
never the agent that wrote it. **Reviewers count against the same
`capacity.max_parallel` as implementers** — a reviewer drives Gradle exactly
as an implementer does, and the cap was measured on mixed lanes
(computenet-avs). Count every dispatched agent still running; when the cap is
full, hold the reviewer and dispatch as a lane frees (you merge passes one at
a time anyway).

```
Agent({
  description: "Review task <id>",
  model: <task's metadata.model>,
  run_in_background: true,
  prompt: `Read .claude/skills/work/references/review-task.md (from
${taskWorktree}, not the main checkout) and follow it to review beads task
${id} against its own acceptance criteria.
Worktree: ${taskWorktree}  ·  Branch: ${taskBranch}
Cross-bead writes authorized on this item: ${crossBeadWrites or "none"}.
That is the same line the implementer was given: treat what it names as
commissioned work rather than scope creep, and anything beyond it as
unauthorized.
Repair what you can within the task's scope. Report pass or fail, what you
repaired, and — on fail — exactly what is missing.
If you won't finish within ~45-60 minutes, stop at a clean point and write your
state to the bead BEFORE you stop: your verdict so far, what you have and have
not verified, and whether you authored any commits (with their shas and
--stat). A review stopped without that leaves the worst state available —
reviewer-authored code on the branch that nobody has certified, and a bead
that reads as neither pass nor fail.`
})
```

**Find an actual verdict in the result before acting on it.** The completion
notification looks identical whether the reviewer finished or stopped itself
mid-review (one returned "Waiting on Arm A…" as its entire result). No
pass/fail stated → `SendMessage` the same agent (context intact) to finish
and state a verdict plus a NOT VERIFIED section. Agent-completed is not
task-reviewed; a result skimmed as done here merges unreviewed code.

**Merge the passes yourself, one at a time** — reviewers must not merge
(concurrent merges into one feature branch race). First confirm the feature
worktree is still on the recorded branch (computenet-wpvy.29), and close the
task before touching worktrees, so a crash can't leave merged work looking
unclaimed:

```bash
git -C <feature-worktree> rev-parse --abbrev-ref HEAD   # must equal <feature-branch>
git -C <feature-worktree> merge --no-ff task/<task-id> -m "Merge <task-id>"
git -C <feature-worktree> push
bd close <task-id>
git -C <task-worktree> status --short                   # expect empty; else an agent died mid-edit — report
```

Do **not** remove the task worktree — every removal happens in step 6's
sweep, after all agents have returned (removing now races the reviewer's own
bookkeeping). A merge conflict means two claims overlapped: resolve in the
feature worktree, fix **both** tasks' `files` metadata, say so. A failed
review keeps its worktree, branch, and `in_progress` status — 5b's resume
query picks it up.

**Then look at the integrated result** once the PR exists (5d):

```bash
gh pr checks <pr-url>
```

The task reviewer tested a branch without its merged siblings; this is the
first signal the whole still builds. Red is work: file a task
(`bd create --parent=<feature-id>` with `model` and `files`) for the next
batch. The one narrow exception — a red check in a module this diff doesn't
touch — requires
[references/red-check-attribution.md](references/red-check-attribution.md)'s
four artifacts before treating any red as not this feature's. Never ship on
"it's a flake".

Then 5b again.

### 5d. Draft PR, on the first merge

The feature branch has no commits until a task merges (`gh pr create`
rejects an empty branch), so open the PR right after the **first 5c merge** —
and **only if `metadata.pr` is unset** (a resumed feature has one; `gh pr
create` on such a branch errors):

```bash
gh pr create --draft --base main --head <branch> \
  --title "<feature title>" \
  --body "Delivers <feature-id>. Tasks land as reviewed commits."
bd update <feature-id> --set-metadata pr=<url>
```

Early so CI runs while the feature is built; recorded so a later session
finds it. It stays **draft** until 5e's verdict — you mark ready only there.

### 5e. Feature review

Every task closed ≠ feature done: per-task criteria can all pass while the
feature has seams nobody owned. Dispatch a fresh reviewer — never one that
wrote the code.

Collect what only you know first, and **paste the outputs — don't summarize
them into conclusions the reviewer inherits as fact**:

```bash
git -C <feature-worktree> fetch origin main
git -C <feature-worktree> log --oneline \
  $(git -C <feature-worktree> merge-base HEAD origin/main)..origin/main
gh pr list --state open --json number,headRefName,isDraft \
  -q '.[] | "\(.number) \(.headRefName) draft=\(.isDraft)"'
```

An empty first output is worth saying ("origin/main unchanged at `<sha>`").
`${parkedChildren}` is the `parked` array from the `next-batch.py` call that
routed you here (empty on `all-closed` → the literal `"none"`).

```
Agent({
  description: "Review feature <id>",
  model: "opus",
  run_in_background: true,
  prompt: `Read .claude/skills/work/references/review-feature.md — from
${worktree}, never the main checkout, whose local branch is stale — and follow
it to review feature ${id} against its own acceptance criteria.
Worktree: ${worktree}  ·  Branch: ${branch}  ·  PR: ${pr}
Cross-bead writes authorized on this feature and its tasks:
${crossBeadWrites or "none"}. Treat what it names as commissioned work rather
than scope creep, and anything beyond it as unauthorized.
origin/main as of dispatch: ${mainSha}; landed since this branch forked:
${logOutput or "nothing"}.
Open PRs that may merge under you while you review: ${prList}. Section 6's
re-fetch is where you find out whether one of them did — do it.
Children left open as ask-human.md parks, deferred by design rather than
missed (5b's parked-residue): ${parkedChildren or "none"}. Confirm each is
really a park and not a child blocked on a real dependency that inherited the
human label from its parent; a real block means work remains.
Repair what you can within the feature's scope. You decide the verdict —
ready or draft — but do NOT run gh pr ready; the orchestrator ships. On a
draft verdict, file beads tasks for what's missing. Report your verdict, why,
what you repaired, and any tasks you created.
If you won't finish within ~45-60 minutes, stop at a clean point and write your
state to the bead BEFORE you stop: your verdict so far, what you have and have
not verified, and whether you authored any commits (with their shas and
--stat). A review stopped without that leaves the worst state available —
reviewer-authored code on the branch that nobody has certified, and a bead
that reads as neither pass nor fail.`
})
```

**Act only on a verdict.** Three cases:

- **You had to `TaskStop` it** → that is a DRAFT verdict, not an absent one.
  Route on what it wrote to the bead (commits authored → substantive-repair
  case; gaps named → gaps; nothing → say so explicitly in the summary and
  leave the PR in draft — silence is the one state to refuse).
- **Its final message lacks the literal word READY or DRAFT**
  (review-feature.md §8 makes this a token test) → `SendMessage` the same
  agent to state its verdict and NOT VERIFIED section; run nothing below
  until it does. `metadata.review=passed` does not settle it — the marker is
  written before §8's report, so passed-with-no-verdict is a disagreement,
  not a tiebreak toward shipping.
- **READY** → ship it yourself, below.

**The reviewer certifies; you ship** — on this repo a ready PR merges
itself, so a reviewer readying its own certification is self-approval. You
are the second party. Only touch the feature worktree once the reviewer's
completion notification has arrived — one-worktree-one-live-agent applies to
you too (computenet-ihw5). In this order:

```bash
git -C <feature-worktree> fetch origin main
git -C <feature-worktree> log --oneline \
  $(git -C <feature-worktree> merge-base HEAD origin/main)..origin/main

# checks are a verdict on the commit they ran against, and the reviewer's own
# §6 merge moved the head
git -C <feature-worktree> rev-parse HEAD
gh pr view <pr-url> --json headRefOid -q .headRefOid    # must equal the line above
gh pr checks <pr-url>
gh pr ready <pr-url>
```

Sha mismatch = "checks not yet available for this commit", not a verdict —
the PR head has been observed lagging the pushed ref by ~10 minutes with
nothing in the output saying so (computenet-qnyn); green-for-commit-N while
the branch is at N+1 would ready a PR on evidence that never covered the
merged code. Wait for agreement, re-read. Commits in the `log` output the
verdict doesn't mention, touching this diff's files (`gh pr diff <pr-url>
--name-only`) → merge `origin/main` in and send back for a re-check. Red
required check → red-check-attribution.md; pending → wait.

**`gh pr ready` is the ship decision, not the ship.** The moment it returns,
read [references/ship-feature.md](references/ship-feature.md) and follow its
state table until `MERGED` or honestly parked. Short form: ready PRs **one at
a time**; `MERGED` → `bd close` the feature, leave its worktree for step 6;
conflicts are yours and get a reviewer like any code you write.

- **Draft verdict** → four shapes routing differently; read the verdict
  comment, then ship-feature.md §3–4. The substantive-repair case is a
  finished feature needing only an independent reader — never 5b, never a
  park.

### The SDLC exclusion

**`computenet-wpvy` and everything beneath it is never /work's to *work* —
not as an epic, not as a cross-epic blocker (5f route 3), not as continuation
(route 4).** A session must not edit the skill it is executing under; process
work has its own lane (`.claude/skills/remediate-friction/SKILL.md`). Step
7's filing-and-claiming of friction items is *routing*, not working them, and
is the one sanctioned touch.

The test for an **item** is two-part, both halves load-bearing: its effective
epic is `computenet-wpvy` (use `epic-of.sh` — `bd list --parent` is one level
deep and misses grandchildren), **or** it carries the `skill-friction` label
anywhere (dozens live outside the epic — unparented bugs, children of the WSK
epics — and each proposes edits to `.claude/skills/work/`). Checking only the
label left unlabeled children unworked (computenet-wpvy.37); checking only
parentage misses the rest.

```bash
.claude/skills/work/scripts/epic-of.sh <candidate-id>
# -> computenet-wpvy  => SKIP, on every route (exit 1 = unresolved: fix the
#    id or break the cycle and re-run — never read an error as "no check needed")
```

No exemptions. The WSK epics `computenet-k9d` and `computenet-ait` used to
be one, on the grounds that their children were reachable by no other lane;
that is spent. On 2026-08-15 every open child was re-reviewed, the ones
already satisfied by merged text were closed, and the rest were reparented
under `computenet-wpvy` — so both epics hold no open work, and the lane
question the exemption existed to defer (computenet-wpvy.44) is answered by
parentage like everything else.

### 5f. Next feature, or wait, or stop

Take the first that applies. **Routes 1, 3 and 4 are all closed after
T-90m** — new work you can't review and merge before the slot ends is a
stranded branch.

**1. Another feature under this epic is ready or in progress** (and not
recently parked) → 5a.

**2. The only remaining work depends on a feature this session just marked
ready.** Features branch from `origin/main`, so one sees another's work only
once it *merges*. Poll in the foreground, 60s apart (a floor — your polls
and any monitors share the machine's sockets), max 30 rounds or until T-45m.
A failed `gh` call is not a `state` reading — print the error, retry the
round. `DIRTY`/`BEHIND` → resolve per 5e (waiting on a conflict only you can
clear is deadlock). `MERGED` → `git fetch origin main`, start. `CLOSED` or
cap reached → park a question, continue down this list. Never background an
unbounded loop — a PR on a red check stays `OPEN` forever.

**3. The epic's remaining work is blocked solely by an item in a different
epic** → claim and work **that item** (not its epic; this adds no epic
claim), unless the SDLC exclusion catches it. The claim is an acquisition —
bracket it: `bd dolt pull`, re-verify still ready and unclaimed, claim by id,
`bd dolt push`. And because a concurrent session's *child* claims are local
while its epic is open, **check the item's epic first** — an epic claim is
always pushed, a child claim only once its epic closes (5b):

```bash
.claude/skills/work/scripts/epic-of.sh <candidate-id>
bd show <that epic> --json | jq -r '.[0] | "\(.status) \(.assignee) \(.updated_at)"'
```

- open or in_progress with the other machine's assignee, or *any* status
  touched within 15 minutes → treat as live; take the next candidate.
- closed, older than 15 minutes → the assignee is provenance, so read the
  **candidate's own** `status`/`assignee` instead and skip it if another
  machine holds it. That reading is trustworthy here and only here: 5b makes
  a child claim under a closed epic an acquisition, so it was pushed. The
  15-minute floor stays as the guard for the seconds between that claim and
  its push, and for a session that died in between.
- `(unparented)` → safe: an unparented item can't be someone's local child
  claim, so any competing claim was itself pushed.

If you later find a sibling PR touching your item's files, stop and park —
don't pick a winner; the losing side may hold pushed, unreviewed work.

**4. The epic is dry but real budget remains** → continuation work, claimed
by specific id (same acquisition bracket as route 3; never a second *epic*).
If the epic went dry because everything left is human-gated or cross-epic
blocked, also `bd defer` it (step 3's route) so the next session doesn't
resume a dead queue.

Build the pool from `bd ready --json`: features and tasks of other epics,
plus unparented bugs and chores. Drop: `human`-labeled; SDLC-excluded (both
halves of the test); `parked_at` within 6h; **anything that is review or
verification of this session's own output** (warm context makes
self-approval likely — excluded whatever its score); anything whose
`metadata.files` overlaps a claim already `in_progress`. Order the rest:
direct dependents of this session's completed items; file-surface overlap
with this session's pushed branches (`git diff --name-only
origin/main...<branch>`, never titles); unparented ready bugs/chores;
general ready order — ties break by the next criterion down.

Two admission gates:

- **The item's own compute demand fits the slot.** A bead can demand
  thousands of suite runs while your dispatch prompt forbids starving the
  machine; you are the only party seeing both (computenet-9vt). Read the
  acceptance/TEST clauses, multiply sample by per-run cost. Doesn't fit →
  say so in the dispatch prompt in as many words (run what fits, file the
  rest as follow-up) and `bd update <id> --set-metadata compute=dedicated`
  so a later session routes it to a dedicated slot. Never leave the
  collision for the implementer to discover.
- **Its 45–60m estimate fits the remaining budget with margin** (15 min
  default; `WORK_CONTINUATION_MARGIN_MIN` overrides). Never admit on elapsed
  fraction alone — that converts idle time into half-finished branches,
  which is worse than idling.

Work the admitted item by shape: feature via 5a; a task via its parent
feature's flow; an unparented bug/chore as its own worktree/branch/PR like a
feature. Every shape records `branch`/`worktree` metadata, so an overrun
leaves resumable state, never a stranded branch.

**5. Nothing can progress → Finalize.**

## 6. Finalize

**Ending abnormally — budget exhausted, unrecoverable error, interrupt — run
the publication push FIRST and skip the rest:**

```bash
.claude/skills/work/scripts/publish-beads.sh    # >=300s timeout
```

Everything else here is bookkeeping a later session reconstructs from the
tracker; unpushed tracker state is the one thing it cannot.

Otherwise, in order:

**1. The epic decision.** One query, three branches:

```bash
bd show <epic> --json | jq -r '.[0] | "\(.status) \(.assignee)"'
bd list --parent=<epic> --all --json     # children; must be non-empty to close
```

- **Closed, and you did not close it** → a concurrent session closed it out
  from under you (same machine, same actor — it happens: computenet-v0yc).
  Leave it closed; drop only your owner label
  (`bd update <epic> --remove-label=owner:$BEADS_ACTOR`) and confirm the
  status is still `closed` — if it isn't, say so at the top of the summary
  instead of writing again (a reopened epic with a stale assignee is step 3's
  takeover bait). Leave the assignee: it records who held the epic, and 5f's
  route-3 check reads status+age before assignee, so open children under it
  stay selectable. **In-flight children are still finished and shipped** —
  the closed epic changes who schedules the next item, not the status of
  this one; residuals from those reviews route per review-feature.md § "Ready
  with residual". Their claims are no longer invisible while you finish
  them: the epic is closed, so 5b's rule already pushed each one as an
  acquisition.
- **Open, every child closed** (and the epic *has* children — one with none
  is mid-breakdown, never close it) → `bd close <epic>` and drop the owner
  label.
- **Open, work remains** → release: `bd update <epic> --status=open
  --assignee=""` — the claim binds the epic to this *session*; a kept
  assignee makes it `--claim`-refused everywhere else. The `owner:` label
  stays as provenance.

**2. Record utilisation** (data for the top-up-vs-resize question):

```bash
bd comment <epic> "utilisation: worked <N>m of <slot>m allocated; continuation items: <ids, or none>"
```

**3. Log the friction — step 7 — now**, so its items ride the publication
push.

**4. Publish:**

```bash
git -C <worktree> status --short   # per worktree touched; leftovers = an agent died mid-edit — report, don't commit
git -C <worktree> push
.claude/skills/work/scripts/publish-beads.sh
```

The script reads output rather than exit codes (`bd dolt push` exits 0 while
printing rejections), recovers a non-fast-forward inline (expected under
concurrent operation), and escalates real conflicts. After a *recovered*
push, verify your own writes survived the pull's merge — dolt reconciles
last-write-wins, so a lost row is lost silently (name each write; don't
assume):

```bash
bd list --parent=<epic> --all --json
bd list --label=skill-friction --all --limit 0 --json    # step 7's items, wherever parented
bd show <id> --json                                      # each acquisition outside the epic
bd comments <id> --json > "$SCRATCH/c-<id>.json"         # per bead you commented on
```

A missing write is an **escalation**: say which vanished, at the top of the
summary, and park it for a human — never re-apply blind. If the recovery
itself failed (exit 2), the summary's top line says the session's tracker
state is local-only.

**5. The worktree removal sweep.** The only place worktrees come off during
a session (5c and 5e defer here; step 3's sweep handles earlier sessions').
Remove the worktrees of tasks merged in 5c, features that closed, and any
extra fix worktree — only what passes **both** gates:

1. every agent dispatched into it this session has reported (or its
   `TaskStop` landed) — one still running → leave it, name it in the
   summary; a worktree nobody was dispatched into this session passes;
2. `git -C <worktree> status --short` is empty — else leave and report:
   uncommitted work nobody has looked at.

```bash
git worktree remove <worktree-root>/$(basename <branch>)   # the recorded metadata.branch
```

Leave unfinished features' and tasks' worktrees for the next session's 5a.
**Never `git branch -d/-D` the local branch**: this repo squash-merges, so
"branch holds commits not on main" is the *normal* landed state, and a
post-merge commit stranded by a crash looks identical — deleting the branch
takes its reflog, leaving such a commit findable only by `git fsck`. A stale
ref costs nothing; the worktree was the leak.

**6. One merge check** (bounded; not a poll). Auto-merge lands PRs after
sessions end, so no session observes its own merge — the step-3 sweep
reconciles them eventually, but only *this* session can diagnose its own red
check cheaply, while context is live. If `BUDGET EXPIRED` has fired or
Finalize is past its 15 minutes, **skip it** — say "merge check skipped; N
PRs open: <urls>"; the sweep absorbs them by design. Otherwise, one pass
over the PRs you readied:

```bash
gh pr view <pr-url> --json state,mergeStateStatus,statusCheckRollup
```

- `MERGED` → `bd close <id>`; remove its worktree under the two gates. Do
  **not** close the epic here even if that was its last open child — the
  claim is already released; name it in the summary and the next session
  closes it in one query.
- a required check red → investigate now per red-check-attribution.md,
  **time-boxed to 15 minutes and one PR per session**; at the box, write the
  partial attribution to the bead. A second red PR is named, not
  investigated.
- `DIRTY`/`BEHIND` → resolve per 5e if budget allows.
- still `OPEN`, or `CLOSED` → say so per PR; the sweep owns the rest.

If the check closed anything, run `publish-beads.sh` once more.

**7. `TaskStop` the budget monitor**, then summarize: epic worked and its
disposition (closed / released / closed-under-you), tasks completed, features
left in draft (PR urls), parked questions (and what they ask), stale claims
released at startup, what the startup sweep closed or flagged, what the merge
check resolved or left open, friction logged, which skill revision ran, and
why the session stopped. Name any feature left `in_progress` — a feature
claim is the resume marker and outlives the session on purpose; task claims
age out via the next sweep; the epic claim never outlives a clean Finalize.

## 7. Log the friction

Nobody watched this run, and your transcript is thrown away — anything wrong
with the *process* dies with it unless written down, and the next slot hits
the same wall. Log process problems (product questions are already parked
beads): a command here that failed or misled; a step where instructions and
reality disagreed; anything retried, worked around, or decided with no
guidance; breakdowns that came back unusable; a dead end that cost real time.
**Log honestly, including your own mistakes** — a misread instruction is the
most useful entry there is.

The SDLC epic is a shared surface, so the whole registration is one bracket —
pull → dedup → write → claim — closed by the publication push (step 6 runs
this just before it; one push covers both):

```bash
bd dolt pull
bd search "<a few distinctive words>" --status all --json   # --status all, or
                                                            # fixed-and-closed twins are invisible and get re-filed
```

**One issue per kind of friction.** Found (and still open) → **upvote it**:
comment this session's instance (what you were doing, what happened, what it
cost) — comment count is the remediation priority — then claim it for this
machine if unclaimed (`bd update <id> --claim`; already claimed by the other
machine → done, its lane owns it). Found but closed → a recurrence of a
fixed issue: file fresh and say so in the description. Not found:

```bash
.claude/skills/work/scripts/file-friction.sh --type <bug|feature> \
  --title "<the friction in one line>" \
  --desc "<what the skill says, what actually happened, what you did instead, what it cost>" \
  --accept "<what would have to change in the skill for this not to recur>" \
  --skill-version <the epic's metadata.skill_version>
```

`bug` = the skill misbehaved; `feature` = it worked as written but lacks a
capability. The script creates **unparented** (a `--parent` create allocates
the child id from a per-database counter, and two machines filing between
syncs mint the same id for different beads — a primary-key collision whose
"resolution" destroys real beads, computenet-wpvy.45), then re-parents to
`computenet-wpvy`, labels, stamps the version, and claims. This applies to
shared parents only — breakdown children under your claimed epic keep their
dotted ids.

If `bd comment` is refused by the permission classifier (observed in
unattended sessions while every other subcommand ran), the step still
happens: read the thread first (`bd comments <id> --json > file`), then
`bd update <id> --append-notes "<instance>"` — **`--append-notes`, never
`--notes`** (which overwrites), plain text only (command substitution and
backticks are refused inside values, computenet-9w9) — and name the refused
command verbatim in the summary; that is the only way an allowlist entry gets
made. Don't fall back to one-bead-per-session.

Write each item for someone editing this skill next week with none of your
context: name the step, quote the instruction, say what actually happened.
Review the accumulated log with:

```bash
bd list --label=skill-friction --all --limit 0 --json \
  | jq '[.[] | select(.status != "closed")]'     # open AND claimed; --status=open alone hides what step 7 just claimed
```

One report is an anecdote; the same issue commented by four sessions is the
next thing to fix.
