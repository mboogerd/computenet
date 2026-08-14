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
bd comments <id> --json > "$SCRATCH/comments.json"   # then read the file
```

An empty result from the wrong query is indistinguishable from "no answer",
and has already caused an epic to be wrongly deferred over gates that were
cleared.

`$SCRATCH` throughout this skill is **your harness-provided scratchpad
directory** — the session-scoped path for temporary files, outside the user's
project. Set it once at the top of the session (`SCRATCH=<that path>`); use
`$(mktemp -d)` if your harness gives you none.

**Redirect it, don't read it inline.** On exactly the beads where comments
matter — the long-lived ones — the JSON overruns the tool-result limit and
what lands is a *truncated* array. That does not present as an error: it
presents as a parse failure, or worse, as **fewer comments than exist**,
which is the same "has a human answered this?" misread as the wrong query
above. Measured 2026-08-13: `computenet-dqy.31` returns 34,019 bytes over 16
comments and `computenet-dqy.44` 25,319 over 9. Write it to a file and parse the
file; when you only need the tail of each comment, pipe through `python3` and
print the fields you want rather than widening the read.

**Two `bd` invocation shapes that differ from their neighbours**, each worth
a retry if you guess: `bd create` takes the **title positionally** (`-t` is
the short form of `--type`, so `bd create -t "<title>"` fails with `title
required`), and `bd comment` takes the body positionally or via **`--file`**
(not `--body-file`).

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
- **A claim about what a tool or CI platform DOES is the same kind of
  claim.** It needs a run that shows it, or a citation to the platform's
  documentation — otherwise write it as "I believe X; verify it". An
  orchestrator asserted that "a `workflow_dispatch` job always runs the
  DEFAULT BRANCH's copy of the file, so a new arm on this branch cannot be
  dispatched until it merges", and put it in three dispatch prompts and two
  PR bodies. It is false: `workflow_dispatch` executes the **selected ref's**
  copy. Verified — run `31673273722` reports `event=workflow_dispatch`,
  `headBranch=feature/computenet-dqy.52`, `headSha=1bb38c5`, and executed a
  step that existed only on that branch. What is true, and is what the claim
  was over-generalised from, is that the workflow must exist on the default
  branch to be **dispatchable at all** (the earlier `HTTP 404: workflow not
  found on the default branch`). computenet-dqy.52 shipped believing its own
  experiment could not be run before merge, and only the reviewer trying it
  produced the result. A one-line try-it-first is cheaper than the session it
  costs.
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

**`persistent: true` is load-bearing — do not drop it, and do not "fix" the
snippet by adding `timeout_ms`.** Determined by test, 2026-08-13, three
probes: omitting `timeout_ms` with `persistent: true` is **accepted**, not
rejected, and a monitor armed that way **fired at 6 minutes**; the control
with `persistent: false` and `timeout_ms` omitted was armed at
`timeout 300000ms` and killed before its 6-minute line. So the 300000ms
default is real but `persistent` genuinely overrides it, and the documented
3600000ms *maximum* never applies here — which matters, because 1h is well
short of a 5h slot. (This rules the snippet out as the cause of
computenet-m5l.)

**Between notifications you have no sense of elapsed time, so read the clock
before any budget-gated decision.** "Don't poll a clock" means don't burn
turns on it, not "you don't need to know the time" — and the T-90m/T-45m
rules in 5f are exactly decisions that need it. One session misread its own
elapsed time as ~3h20m at the 1h31m mark and came one step from declining to
start a fifth feature, which would have idled ~1h45m of a 5h slot:

```bash
date -u +%H:%M    # before 5f's feature selection, and before any T-gate call
```

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

**Then check you are running the current skill.** Claude Code's session
worktrees (`.claude/worktrees/`) are branched from **whatever HEAD the
launching checkout is on** — for the main checkout that is local `main`, and
nothing in this flow fast-forwards it. (Older session branches were cut from
`refs/remotes/origin/main`; the behaviour changed on 2026-08-14, so do not
rely on either.) The `SessionStart` hook `.claude/hooks/ff-main.sh` keeps the
checkout current, but it deliberately no-ops whenever `main` has staged or
modified tracked files, so a slot can still begin on stale text. Measured
2026-08-14: the checkout sat **44 commits** behind `origin/main`, **8 of them
touching this file** and 14 touching `.claude/skills/` — and
`.claude/skills/remediate-friction/` did not exist in it at all.

```bash
git hash-object .claude/skills/work/SKILL.md      # what you are running
git rev-parse origin/main:.claude/skills/work/SKILL.md   # what is current
```

Same hash → carry on. **Different → re-read the skill from `origin/main`
before you act on it**, and treat that copy as authoritative for the rest of
the session, references included:

```bash
git show origin/main:.claude/skills/work/SKILL.md
git show origin/main:.claude/skills/work/references/<name>.md
```

Say in the session summary which revision you ran, and log it as friction
(step 7) if the gap was more than a commit or two — a slot spent executing
superseded instructions is exactly the recurrence the friction log exists to
surface. Record the *current* hash in `skill_version` below either way, since
that is the revision your report should be judged against.

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
# Filter the SDLC epic OUT in the command, not by eye: bd ready really does
# return computenet-wpvy, and a filter you apply while reading JSON is one you
# can forget with nothing to catch it.
bd ready --type=epic --json | jq '[.[] | select(.id != "computenet-wpvy")]'
bd update <id> --claim                    # claim that id specifically
# --claim refused with "issue already claimed by <other machine>"? See below —
# take it over; do NOT skip to the next epic.
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

**`--claim` refuses any issue that still carries an assignee, and a clean
Finalize on the other machine leaves exactly that.** The release below sets
the epic back to `open` but the assignee is what `bd ready` does not filter
on, so the epic is simultaneously offered by `bd ready` and unclaimable by
the documented command — on every machine except the one that last held it.
Reproduced 2026-08-13: `bd ready --type=epic` returned `computenet-dqy` as
the top P0 pick and `bd update computenet-dqy --claim` answered
`Error claiming computenet-dqy: issue already claimed by Anva@A0030`, with
`status=open`. There is no `--force-claim`.

**Take it over — do not read the refusal as "someone is working it".** That
reading silently skips the highest-priority epic in the queue in favour of a
lower one, which is the expensive mistake here. An assignee on an `open` epic
is *provenance*, not a live claim. Note that the `in_progress` query above is
filtered to `$BEADS_ACTOR` and so can never see the *other* machine; what
protects against a genuinely live concurrent run here is reading the target
epic's own `updated_at`:

```bash
bd show <id> --json | jq -r '.[0] | "\(.status) \(.assignee) \(.updated_at)"'
# status=open and updated_at older than 15 minutes -> not live; take it:
bd update <id> --assignee=$BEADS_ACTOR --status=in_progress
```

**The takeover replaces the `--claim` line and nothing else.** Go back and run
the remaining three — `--add-label=owner:`, `--set-metadata skill_version`,
and `bd dolt push`. **A takeover is an acquisition, so it needs the same
push**, and this is the path where forgetting it costs most: a cross-machine
handoff that stays local leaves the epic reading `open` with a stale assignee
on the remote all session, so the other machine runs this very check, reaches
the same "not live" conclusion, and takes the epic too. That is the
computenet-kg7 double-claim it is meant to prevent, and it would also defeat
5b's parent-epic check (computenet-f8tf), which can only work if an epic
claim is remotely true.

If the epic is `in_progress` rather than `open`, that is a different case and
`--claim`'s refusal is correct: it is either this machine's crash leftover
(released above) or the other machine's live run.

The `skill_version` line records **which revision of this skill the session ran
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

**Before retrying, check whether the breakdown parked the epic on purpose.**
`epic.md` now requires it to verify the epic's load-bearing environmental
premises first and to park rather than produce children that inherit a false
one — so a *correct refusal* looks exactly like a dead breakdown if you only
count children:

Key on what a park actually leaves. `ask-human.md`'s park is
`--status=blocked --add-label=human --assignee=human` plus a `QUESTION:`
comment — it does **not** write `metadata.parked_at`, which is a
*feature*-level marker written by hand at step 5 and by nothing on this path:

```bash
bd show <epic> --json | jq -r '.[0] | "\(.status) \(.assignee) \(.labels)"'
bd comments <epic> --json > "$SCRATCH/epic-comments.json"   # read the QUESTION: comment
```

`blocked` with `assignee=human` and the `human` label → **parked deliberately.
Do not dispatch a second breakdown** and do not log it as friction; the agent
did its job. Park the epic per step 3's `bd defer` route and select the next
one.

Otherwise the breakdown produced nothing. **Try once
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
  prompt: `You have no worktree, so read the reference from the REMOTE,
never the main checkout's working tree — its local branch is stale:
  git -C <main-checkout> show origin/main:.claude/skills/work/references/epic.md
Follow it to break epic ${epic} into features. Run bd with
-C <main-checkout>. It is already claimed and labeled — skip both.
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

**An epic can have no feature layer at all, and that is not a defect.** Some
epics are worked as bugs, tasks and chores parented *directly* to the epic —
computenet-dqy is 69 children of which exactly one is a feature, and that one
is closed, which is how both machines have worked it for many sessions. On
such an epic both feature queries return empty while real work is sitting
ready, and the literal reading below would send it to 5f and then Finalize
having done nothing. Reproduced 2026-08-14: `bd ready --parent=computenet-dqy
--type=feature` → 0, `bd list --parent --type=feature --status=in_progress`
→ 0, while step 3's own workable-surface check (`bd ready --parent=<epic>`,
no type filter) → 2. **Step 3 admits the epic on a query step 5 cannot see**,
and that disagreement is the defect.

So before falling through, ask the untyped question:

```bash
bd ready --parent=<epic> --json      # no --type filter
```

Non-empty while the feature queries were empty → **work these directly**.
Route by shape, and note that route 4's wording assumes a parent feature that
these items do not have:

- **A bug or chore** → its own worktree, branch and PR, exactly like a
  feature. Run it through 5a's flow with the item id in place of the feature
  id.
- **A task parented straight to the epic** → the *same* treatment, not 5b's
  task flow. 5b cuts a task branch from its feature's branch and merges it
  back there; with no feature there is no base to cut from and nothing to
  merge into (computenet-9xj records exactly this). Give it its own
  worktree/branch/PR off `origin/main` like a bug.

Apply the same filters as everywhere else — skip `human`-labelled items and
anything with `parked_at` within 6h. Only when *this* query is empty too does
5f apply.

**When that item finishes, come back here, not to 5f.** Re-run the untyped
query and take the next one; a slot that works one direct child and then
falls through has the same defect one level down. 5f's route 1 asks for
another *feature*, and route 4 explicitly excludes items parented to this
epic, so neither will bring you back.

**After the T-90m notification, stop taking new ones** — the same guard 5f
route 1 carries, and for the same reason: an item you cannot review and merge
before the slot ends leaves a stranded branch. Finish the one you are on and
go to 5f.

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
  worktree resumed from an earlier session, which 5a recomputes) →
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

**`metadata.branch` exists** → **check whether its PR already merged before
you reuse it.** This repo squash-merges (the ruleset requires linear history),
and a squash puts the branch's content on `main` under a *new* commit sharing
no ancestry with the branch. So a fully-landed branch reads as simultaneously
"already in main" and "N commits ahead", and 5a's `merge origin/main` below
hands you a conflict whose only correct resolution is *discard both sides,
this is already landed* — which is exactly what "Conflicts here are yours to
resolve" does not suggest. Resolving it as a real conflict re-lands reviewed
content as a fresh diff. Measured 2026-08-13 on computenet-dqy.55, whose
PR #94 had merged: `git merge origin/main` conflicted in
`.github/workflows/announcement-probe.yml`, and `git diff --stat
origin/main...HEAD` reported 332 insertions, all of it already on `main`.

```bash
# metadata.pr may be unset even when a PR exists — a crash between creating and
# recording it is the case 5a already warns about. Ask the branch, not the bead.
bd show <feature-id> --json | jq -r '.[0].metadata.branch'      # = <branch> below
gh pr list --head <branch> --state all --json number,state -q '.[] | "\(.number) \(.state)"'
```

- **`MERGED`** → the branch is spent. Do **not** merge `origin/main` into it.
  Cut a fresh one from `origin/main` and repoint the bead. Derive the suffix
  from the *current* value rather than hardcoding it, or the second resume
  re-uses the branch it just retired:

  ```bash
  # [0-9][0-9]* not \+ — BSD sed (macOS) does not support \+ in a basic regex,
  # and the silent failure is N="" -> always -r2, i.e. reusing the spent branch
  BR=<branch>        # paste the recorded value; do NOT rely on a variable
  N=$(printf '%s' "$BR" | sed -n 's/.*-r\([0-9][0-9]*\)$/\1/p'); N=$((${N:-1} + 1))
  NEW="feature/<feature-id>-r$N"                 # id stays derivable: strip -r<n>
  bd update <feature-id> --set-metadata branch="$NEW" \
    --set-metadata worktree=$PWD/../computenet-worktrees/<feature-id>-r$N \
    --set-metadata pr=
  bd comment <feature-id> "PR <url> merged by squash; <branch> is spent. Continuing on $NEW cut from origin/main."
  ```

  **Clearing `pr=` is not optional.** 5d creates a PR *only* if `metadata.pr`
  is unset, so leaving the merged url there means the fresh branch never gets
  a PR, 5e dispatches a reviewer at the merged one, and Finalize reads
  `MERGED` for work that never shipped.

  Leave the old local branch and its worktree alone — Finalize's sweep takes
  them, and deleting a branch whose PR merged buys nothing mid-session.
- **`OPEN`**, or no PR at all → reuse it as below.

**From here on, everything uses `metadata.branch` — never the literal
`feature/<feature-id>`.** That is what makes the repoint above take effect:
attach, verify, merge, push, 5b's task base ref and 5d's `--head` all read the
recorded value. Writing the literal name re-attaches the spent branch two
commands after you retired it, and walks into the very conflict this check
exists to avoid.

Reused, that branch and draft PR hold real
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

**All three ways** — reused, freshly recorded, or repointed after a squash —
attach the same way, and all three read `metadata.branch`:

```bash
# Read the recorded branch and substitute it literally below; the worktree is
# recomputed, not read. Do NOT assign either to a shell variable: each fenced block is a separate Bash call
# and shell state does not survive between calls — an empty "$BR" reaches
# ensure-worktree.sh's ${3:-origin/main} as "unset" and silently cuts from
# origin/main, and git -C "" silently operates on the MAIN CHECKOUT.
bd show <feature-id> --json | jq -r '.[0].metadata.branch'      # = <branch>
# <worktree> = $PWD/../computenet-worktrees/$(basename <branch>) — RECOMPUTED
# locally. Never metadata.worktree: it may be the other machine's path, and on
# this queue it is (computenet-dqy.65/.69 both record /Users/MerlijnB/...),
# which makes ensure-worktree.sh die on "mkdir: Permission denied".

.claude/skills/work/scripts/ensure-worktree.sh <worktree> <branch> origin/main

# Verify the worktree actually holds the branch's remote work before using it.
if git -C <worktree> fetch origin <branch> 2>/dev/null; then
  git -C <worktree> merge-base --is-ancestor FETCH_HEAD HEAD \
    && echo "OK: worktree contains origin/<branch>" \
    || echo "STOP: on the branch at the wrong commit — origin/<branch> is not in HEAD"
else
  echo "OK: origin has no <branch> yet (first run, nothing to compare)"
fi
```

**Read the verification line it prints, and only that line.** `STOP` means
the worktree is on the right branch at the wrong commit — proceeding
silently orphans reviewed work while looking perfectly clean
(computenet-aeg); do not enter 5b. Either `OK` is fine; the second is the
first-run case, where origin has no such branch yet.

**A dirty worktree you inherited may be a half-applied MUTATION, and
committing it would break production code.** This repo routinely verifies a
pin by mutation — delete an argument at the production call site, confirm the
test fails, restore it — and an agent killed mid-mutation leaves a worktree
dirty in exactly the same shape as one killed mid-improvement: same files,
same size of diff. Committing the first lands a silently broken change that a
green local run will not catch, because the mutation is designed to make one
specific test fail and that test may be the one nobody re-ran. Discarding the
second throws away finished work. Classify before you act:

```bash
ls <worktree>/.mutation-in-progress 2>/dev/null && cat <worktree>/.mutation-in-progress
git -C <worktree> status --short
git -C <worktree> diff
```

- **`.mutation-in-progress` exists** → the previous agent was mid-mutation.
  `git -C <worktree> checkout -- .` to restore, delete the marker, and say so.
- **No marker, and the diff reads as deliberate and self-consistent** — the
  KDoc updated to match, the change coherent as an improvement — → keep it,
  and say in your report that you kept uncommitted work you did not write.
- **No marker and you cannot tell** → do not commit and do not discard. Leave
  it, park a question ([references/ask-human.md](references/ask-human.md)),
  and move on. Reading the diff is the only thing that distinguishes these,
  so budget for it rather than guessing.

The marker is what makes this recoverable *without* reading; the reading rules
are the fallback for worktrees predating it.

Only once it is classified, bring the worktree up to date:

```bash
git -C <worktree> pull --ff-only 2>/dev/null || true   # no upstream yet is fine
git -C <worktree> merge origin/main -m "Merge main into <branch>"
git -C <worktree> push -u origin <branch>
```

**That `merge origin/main` is not optional on a resumed feature.** `pull` only
refreshes the branch from its own remote — nothing else in this flow ever
brings `main` in, so a feature carried across sessions drifts for days and
first discovers it at the final gate, where the conflict gets resolved after
review has already passed. Paying it down here keeps each merge small and
keeps the reviewer looking at integrated code. Conflicts here are yours to
resolve; re-run the affected module suite afterwards, since a hand-resolved
merge is code nobody reviewed.

`ensure-worktree.sh` is idempotent, so resume and first-run take the same
path: it
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

**Re-derive `metadata.files` against the bead's current decided design before
you claim it.** The claim is set when the bead is *filed*; a design question
answered later can move the work outside it, and nothing re-computes it at
that point. computenet-dqy.37 claimed `wire/src/{main,test}/kotlin/civictech/
wire` and was then decided as shapes (b)+(c), where (c) is "prepare the
one-line upstream fix" — which necessarily produces a file outside `wire/`.
Satisfying the bead required violating its own claim. Read the acceptance and
design clauses against the claim; if the design reaches wider, **widen the
claim and say so**:

```bash
bd update <task-id> --set-metadata files=<the widened list>
bd comment <task-id> "Widened files claim before dispatch: <old> -> <new>, because <the design clause that reaches outside it>."
```

Widening before dispatch is what keeps the disjointness the batch rule
depends on honest. The failure mode to close is an implementer having to pick
between the file claim and the acceptance criteria with nothing covering it —
so the dispatch template below also tells it to report and widen rather than
choose silently.

Claim each id in the batch, record its metadata, then attach its worktree:

```bash
bd update <task-id> --claim
bd update <task-id> \
  --set-metadata worktree=$PWD/../computenet-worktrees/<task-id> \
  --set-metadata branch=task/<task-id>
.claude/skills/work/scripts/ensure-worktree.sh \
  "$PWD/../computenet-worktrees/<task-id>" task/<task-id> <feature-branch>
# <feature-branch> is the feature's recorded metadata.branch — re-read it here
# (5a), never a variable: an empty 3rd arg silently becomes origin/main.
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

**The one hole that leaves, and what closes it.** These child claims are
local until Finalize, so they are invisible to the *other* machine — and
5f routes 3–4 let a session claim an individual item inside an epic it does
not hold. That route's re-verify reads pulled state, where a locally-claimed
child still looks unclaimed. It has fired: on 2026-08-13 two sessions both
worked `computenet-dqy.40`, PR #83 force-updated the shared branch under
PR #79, and the same lines of `Peering.kt` had to be hand-resolved on an
already-certified branch, invalidating its verdict. Neither session did
anything wrong by the written skill
([references/claim-sync.md](references/claim-sync.md)).

So on routes 3–4, before claiming an item that lives under **someone else's
epic**, check the *epic*, which is visible — an epic claim is always pushed
at acquisition, a child claim is not:

**Resolving the epic takes a walk, and two things about `bd` make the obvious
routes wrong.** A bead's *effective* parent is `.parent` when set, otherwise
the dotted-id prefix, and each overrides the other in a different case:

- **`.parent` is omitted from `bd show --json` when unset**, so one sample
  where it is missing does not mean the field does not exist —
  `computenet-dqy.40` has no `parent` key at all, while `computenet-f8tf` has
  `parent=computenet-wpvy`.
- **A dotted id alone is not a proxy either**, in both directions:
  `computenet-f8tf` is a child of `computenet-wpvy` with no dot, and
  `computenet-oxv.6` has an explicit `parent=computenet-oxv.3` that
  *overrides* its `computenet-oxv` prefix.
- **`bd list --parent` is not transitive.** Verified: `--parent=computenet-oxv
  --all` returns the seven direct children and *not* `computenet-oxv.6`, whose
  parent is the feature `computenet-oxv.3`. So a membership scan over the
  epics silently answers "unparented" for exactly the grandchildren this check
  exists to protect.

```bash
epic_of() {                       # effective parent = .parent, else dotted prefix
  local id="$1" row p n=0
  while [ $n -lt 12 ]; do n=$((n+1))
    row=$(bd show "$id" --json 2>/dev/null)
    # guard EVERY hop, not just the first: a vanished ancestor must not
    # fall through to "(unparented)", i.e. to "no check needed"
    if [ -z "$(printf '%s' "$row" | jq -r '.[0].id // empty' 2>/dev/null)" ]; then
      echo "(no such id: $id)"; return 1
    fi
    if [ "$(printf '%s' "$row" | jq -r '.[0].issue_type // empty')" = epic ]; then
      echo "$id"; return 0
    fi
    p=$(printf '%s' "$row" | jq -r '.[0].parent // empty')
    [ -z "$p" ] && case "$id" in *.*) p="${id%.*}";; esac
    [ -z "$p" ] && { echo "(unparented)"; return 0; }
    id="$p"
  done
  echo "(cycle? $1)"; return 1
}
epic_of <candidate-id>
bd show <that epic> --json | jq -r '.[0] | "\(.status) \(.assignee)"'
```

Verified on seven shapes at ~3s each: `dqy.40`→`dqy`, `f8tf`→`wpvy`,
`wpvy.6`→`wpvy`, `oxv.6`→`oxv` (via a feature), `0ja`→`oxv` (via a feature),
an epic resolving to itself, and a nonexistent id reporting
`(no such id: …)` rather than `(unparented)` — **a typo must never present as
"no check needed"**.

Claimed by the other machine → its children are being worked whatever they
say; take the next candidate. The assignee reads as JSON `null` when clear,
not `""`. **A genuine `(unparented)` needs no check and is not a
gap**: an unparented bug or chore can never be somebody's locally-claimed
child, so any competing claim on it is itself an acquisition and is therefore
already pushed. Only that answer is safe to act on. **`(no such id: …)` and
`(cycle? …)` both return non-zero and mean "unresolved", never "no check
needed"** — fix the id, or break the cycle (`bd update --parent` accepts one:
it does no cycle validation), and re-run. Key on the exit status, not on the
string. And if you later find a sibling PR touching
your own item's files, treat it as the collision it is: stop working that
item and park a question — do not pick a winner, since the losing side may
hold committed, pushed, unreviewed work.

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
Read it: bd show ${id} --json (run bd with -C <main-checkout>; only that
checkout has the beads database)
Then read the skill files FROM YOUR OWN WORKTREE — ${taskWorktree}/.claude/
skills/work/references/task.md — and follow it. Do NOT read them from the
main checkout: it is where bd lives, and its local branch is not refreshed by
anything in this flow.
Stay inside your metadata.files claim — sibling tasks are running on sibling
branches and merge into the same feature branch. If the bead's own design or
acceptance clause REQUIRES a file outside the claim, do not choose between
them silently: report it and say which file and which clause, and I will
widen the claim.
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

**`bd` lives in the main checkout; the skill files do not** — this governs
*every* dispatch template in this file, not just 5b's. Worktrees are cut
from `origin/main`, so an agent reading `.claude/skills/work/**` from *its own
worktree* gets the current text — while the main checkout's local branch is
refreshed by nothing here and drifts. Measured at the start of one session:
the main checkout was at `2e3a160` while `origin/main` was at `0a1a56a`,
**44 commits behind**, spanning PRs that changed `SKILL.md` and `references/`
themselves; the `.claude/skills/remediate-friction/` directory did not exist
in that checkout at all. The failure is selective and invisible — an agent in
a worktree is fine, and only the ones sent to the main checkout read days-old
instructions. So: **run `bd` with `-C <main-checkout>`, read every skill file
from your own worktree**, and if you must read one on the main checkout, read
it as `git show origin/main:<path>` rather than from the working tree. Say
which in every dispatch prompt.

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
  prompt: `Read .claude/skills/work/references/review-task.md (from
${taskWorktree}, not the main checkout) and follow it to review beads task
${id} against its own acceptance criteria.
Worktree: ${taskWorktree}  ·  Branch: ${taskBranch}
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
gh pr create --draft --base main --head <branch> \
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
  prompt: `Read .claude/skills/work/references/review-feature.md — from
${worktree}, never the main checkout, whose local branch is stale — and follow
it to review feature ${id} against its own acceptance criteria.
Worktree: ${worktree}  ·  Branch: ${branch}  ·  PR: ${pr}
origin/main as of dispatch: ${mainSha}; landed since this branch forked:
${logOutput or "nothing"}.
Open PRs that may merge under you while you review: ${prList}. Section 6's
re-fetch is where you find out whether one of them did — do it.
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

**A review you had to `TaskStop` is a DRAFT verdict, not an absent one.**
Read whatever it wrote to the bead before stopping and route on that: it is
the substantive-repair case if it authored commits, gaps if it named them,
and a plain resume otherwise. Do not treat the feature as unreviewed — that
loses the work already done — and do not treat it as certified. The one state
to refuse is silence: a stopped reviewer that wrote nothing leaves an
ambiguity the next session has to *detect*, so say so explicitly in the
summary and leave the PR in draft. Four of five features in one session were
fine; the fifth cost the epic its close exactly here.

**The reviewer certifies; you ship.** The reviewer reports a verdict and sets
`metadata.review=passed`, but never runs `gh pr ready` — on this repo a
ready PR merges itself, so a reviewer marking its own certification ready is
self-approval, worse when it also committed repairs. You are the second
party: read the verdict, spot-check it, then ship it yourself. In this order —
the fetch pairs with the reviewer's own §6 re-fetch, and it is the last chance
to notice that `main` moved between the verdict and the ship:

```bash
git -C <feature-worktree> fetch origin main
git -C <feature-worktree> log --oneline \
  $(git -C <feature-worktree> merge-base HEAD origin/main)..origin/main

# the checks are only a verdict on the commit they ran against — and the
# reviewer's own §6 merge of origin/main will have moved the head
git -C <feature-worktree> rev-parse HEAD
gh pr view <pr-url> --json headRefOid -q .headRefOid    # must equal the line above
gh pr checks <pr-url>
gh pr ready <pr-url>
```

**A mismatch between those two shas is "checks not yet available for this
commit", not a verdict.** The PR head can lag the pushed branch ref: measured
2026-08-13, `git ls-remote` returned the reviewer's repair commit while the
API (with `Cache-Control: no-cache`) still reported the previous one, and
`gh pr checks` listed that older commit's results — for about ten minutes,
with nothing in the output saying so. That time the skew pointed the safe way,
a red result from an older commit. The other direction is silent: green for
commit N while the branch is at N+1 marks a PR ready on evidence that never
covered the code being merged, and auto-merge then lands it. Wait for the
shas to agree and re-read the checks.

Read the `log` output and the two shas before running `gh pr ready`. Commits
the reviewer's verdict does not mention landed *after* it certified: if any of them touches the same
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

#### The SDLC exclusion, which applies on every route

**`computenet-wpvy` and everything beneath it is not `/work`'s to claim — not
as an epic, not as a cross-epic blocker (route 3), not as continuation work
(route 4), not ever.** A session must not edit the skill it is executing
under, and process work has its own lane
(`.claude/skills/remediate-friction/SKILL.md`).

**Key on parentage, not on a label.** Anything under that epic is SDLC work
whether or not it carries `skill-friction`; the label is provenance, not the
gate. Checking the label instead is how three unlabelled children sat open
while the lane reported itself drained (computenet-wpvy.37).

`--parent` is **not transitive**, so a grandchild does not appear under the
epic's own listing. Use the ancestor walk from 5b — the one that resolves a
bead's effective parent (`.parent` when set, else the dotted-id prefix):

```bash
epic_of <candidate-id>          # 5b defines it; returns the epic, or (unparented)
# -> computenet-wpvy  => SKIP, on every route
```

## 5f. Next feature, or wait, or stop

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
5a/5b as appropriate), not the other epic itself. **Unless that item is under
`computenet-wpvy`** — see "The SDLC exclusion" above, which applies here as
much as anywhere; a blocker being inconvenient is not a reason to take SDLC work. Without this route a
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
- **anything under the SDLC epic `computenet-wpvy`, at any depth** — see
  "The SDLC exclusion" above;
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

**Reconcile the item's own compute budget against the session's before you
dispatch it, and say in the prompt which one wins.** You are the only party
that can see both. A bead written by an earlier session can demand
">= 2000 Linux suite runs" — many hours of a shared 10-core box — while your
own dispatch prompt says "do NOT launch a multi-hour brute-force loop that
will starve the machine". Both are defensible and they directly contradict,
and nothing else in this file resolves them, so the implementer resolves it by
judgement with no rule to appeal to: one agent burns the slot on a blind loop,
another substitutes a cheaper sample and nobody learns the criteria went
unmet. Read the acceptance and TEST clauses at selection, multiply the sample
by its per-run cost, and then either:

- **it fits** → dispatch normally; or
- **it does not** → say so in the dispatch prompt in as many words ("the
  bead's <N>-run sample does not fit this slot; run <what fits>, and file the
  full sample as a follow-up rather than reporting the smaller one as the
  answer"), and mark the bead so a later session can route it to a dedicated
  slot: `bd update <id> --set-metadata compute=dedicated`.

Never leave the collision for the implementer to discover.

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

**If this session is ending abnormally — budget exhausted, an unrecoverable
error, an interrupt — run the publication push FIRST and skip the rest.**

```bash
bd dolt push  2>&1 | grep -iE "complete|rejected|error"
```

One command, ~34s. Everything else in Finalize is bookkeeping a later session
can reconstruct from the tracker; unpushed local commits are the one thing it
cannot. They are not lost — the next session on this machine carries them out
on its own Finalize push — but "whenever someone next runs a slot here" is a
poor publication guarantee when one command fixes it now.

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
open **and clear the assignee** — the claim binds it to *this session*, not
to this machine, and the next session on either machine must select by
priority. Leaving the assignee is what makes the epic unclaimable everywhere
else (step 3's takeover exists to repair epics released before this line did):

```bash
bd update <epic> --status=open --assignee=""
```

The `owner:` label stays as provenance; that is what records which machine
last held it, and unlike the assignee it blocks nothing.

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
it; only acquisition brackets pushed earlier.

**A non-fast-forward rejection here is the expected outcome of concurrent
operation, not an incident.** Two machines are meant to run slots on a
schedule, so if the other one pushed *anything* at any point during a
multi-hour session, this push is behind by construction. Recover inline — it
is not a conflict and needs no judgement:

**Read the push's output; do not trust its exit code.** `bd dolt push` has
been observed to exit **0** while printing a rejection, so `&&`, `$?` and a
bare `| tail -1` all report a failed push as success — which is precisely how
a session reports itself clean with nothing published:

```bash
# Error 1105: ! [rejected]  main -> main (non-fast-forward)
bd dolt pull  2>&1 | grep -iE "complete|conflict|error"
bd dolt push  2>&1 | grep -iE "complete|rejected|error"
```

Give the push room to finish: after a conflict resolution it has been
measured at **over 120 seconds**, which silently blows a default shell
timeout (`doc/ops/beads-sync-runbook.md` §3.3).

Then verify **your own** writes survived the merge — name them, don't assume,
and note that a session's writes are usually not all under one epic:

```bash
bd list --parent=<epic> --all --json                    # closes and metadata here
bd list --parent=computenet-wpvy --all --json           # step 7's friction beads
bd show <id> --json                                     # each acquisition outside the epic
bd comments <id> --json > "$SCRATCH/c-<id>.json"        # per bead you commented on
```

**If a write is missing, that is an escalation, not a note.** Dolt's own
conflict policy is last-write-wins on `updated_at`
(`doc/ops/beads-sync-runbook.md` §3.3), so a silently reconciled row is lost
at the *pull* and already published by the time you notice. Do not re-apply
it blind — say exactly which write vanished, at the top of the summary, and
park it for a human.

Escalate to a human **also** if the pull reports a real merge conflict
(`merge conflicts ... require operator resolution` — computenet-gq0, resolved
through the `dolt` CLI per §3.3) or the second push also fails. The
*rejection* needs no judgement; whether the *merge* does is what the pull
tells you.

Do not reach for `scripts/beads-nightly-sync.sh` as the recovery: it is those
same two commands with logging and no conflict resolution, **nothing is
scheduled to run it** (`doc/ops/beads-sync-runbook.md` §5), and an unattended
session has had the wrapper refused by the permission classifier while both
commands inside it ran fine. Run the two commands.

If the recovery itself fails, say so at the top of your summary in plain
words. Until someone syncs, this session's unpublished tracker state is
local-only, the other machine still sees this epic's items as they were at
step 3, and losing this machine loses the lot. Never swallow the error and
never report the session as clean without it.

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
session recomputes the same path and reattaches there (5a).

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

**If `bd comment` is refused, this whole step still has to happen.** An
unattended session has had every form of it (`bd comment <id> <text>`,
`--file`, `bd comments add`) answered `Permission for this action was denied
by the Claude Code auto mode classifier`, while `bd update`, `bd list`,
`bd show`, `bd search`, `bd create` and `bd dolt pull` were all permitted in
the same session — so it is that one subcommand, not beads. It is also not
universal: `bd comment` runs in an interactive session. On a refusal:

```bash
bd comments <id> --json > "$SCRATCH/existing.json"   # READ the notes/thread first
bd update <id> --append-notes "<this session's instance>"
```

Use **`--append-notes`, never `--notes`**: `--notes` *overwrites*, so it
destroys an existing notes field you were trying to add to. Two further
things the classifier refuses inside the value itself — a shell command
substitution, and backticks (computenet-9w9) — so write the value as plain
text. Then say in the session summary which command was refused, verbatim;
that is the only way an allowlist entry ever gets made. Do **not** fall back
to filing a fresh bead per session: this step says why ten identical issues
are worse than one issue with ten comments.

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

**File it UNPARENTED, then re-parent.** Do not pass `--parent` on the create:

```bash
SKILL_V=$(bd show <epic> --json | jq -r '.[0].metadata.skill_version')   # recorded at claim (step 3)

# 1. create with NO --parent, so the id is a hash and child_counters is untouched
NEW=$(bd create "work skill: <the friction in one line>" \
  --type=<bug|feature> --priority=2 --label=skill-friction \
  --metadata "{\"skill_version\":\"$SKILL_V\"}" \
  --description="<what the skill says, what actually happened, what you did instead, what it cost>" \
  --acceptance="<what would have to change in the skill for this not to recur>" \
  --json | jq -r '.id')          # bd CREATE returns an object; bd SHOW returns a list

# 2. then attach it. The id does not change.
bd update "$NEW" --parent=computenet-wpvy
```

**Why, and why only here.** `bd create --parent=X` allocates the child id from
`child_counters`, a **per-database** table reconciled only at sync. Two
machines filing under the same parent between syncs both read the same
`last_child` and both count upward, so they mint *the same ids for different
beads*. Measured 2026-08-14: from a common ancestor at `last_child=39`, this
machine went to 45 and the other to 42, and `computenet-wpvy.40`, `.41`, `.42`
each named two unrelated friction items. That is a **primary-key** collision,
not a content conflict, so `bd dolt pull` aborts naming `child_counters`, and
the runbook's last-write-wins resolution would *destroy* three real beads.

`computenet-wpvy` is the worst case precisely because this step works: both
machines file here every slot, so the collision rate rises with the health of
the friction loop.

Verified that the unparented route avoids it entirely — the counter does not
move, and `bd list --parent=computenet-wpvy` still finds the item:

```
counter before: 45 · unparented create -> computenet-9kmv · counter: 45
after --parent=computenet-wpvy · counter: 45 · listed under the epic: YES
```

**This applies to a SHARED parent only.** Breakdown children created under an
epic or feature *this session claimed* are exclusive by that claim, cannot
collide, and keep their readable dotted ids — `epic.md`, `feature.md` and
`task.md` are all correct as they stand. Do not spread the hash route to them.

**If the two steps come apart, nothing is lost — re-attach it.** A crash, or a
`jq` that does not match, leaves the bead created and *unparented* rather than
not created at all. It keeps its hash id, so recovery is one command:

```bash
bd list --all --json | jq -r '.[] | select(.parent == null) | "\(.id)\t\(.title)"'
bd update <the id> --parent=computenet-wpvy
```

(I hit exactly this while testing the snippet above: `jq -r '.[0].id // .id'`
errors on an object — `//` catches null, not an error — so `$NEW` was empty,
the `bd update` no-oped, and `computenet-iub5` sat unparented. The `.id` form
above is the corrected one, and it was run verbatim before shipping.)

(`bd create` inherits a parent's labels; creating unparented skips that, which
is why the `--label=skill-friction` above is explicit rather than inherited.)

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
