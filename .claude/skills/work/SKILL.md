---
name: work
description: Runs one unattended beads work session end to end — claims an epic, breaks it into features and tasks via Fable subagents, and implements each feature in its own worktree, branch, and draft PR, with its tasks running as parallel reviewed branches. Claims are crash-safe and machine-scoped, so two machines can run this concurrently on a schedule without colliding or deadlocking. Use this skill whenever a cron job, scheduled task, or routine kicks off a work slot, or the user says "/work", "work the queue", "pick up the next beads task", "start working through the backlog", "keep the machines busy", or otherwise wants autonomous progress on beads-tracked work — even if they don't mention beads, epics, or this skill by name.
---

# /work

One session = one epic, worked until it's dry or the budget's gone. You are
the orchestrator, not the implementer: every item — breakdowns and reviews
included — goes to a dispatched subagent. No inline "just this once"; that is
how a multi-hour session's context balloons and drifts.

## Contents

**Read before you start** — standing constraints, not steps:

- [The three rules](#the-three-rules) · [`bd` traps](#bd-traps) ·
  [Scripts and references](#scripts-and-references) ·
  [What you write yourself is the one thing nobody reviews](#what-you-write-yourself-is-the-one-thing-nobody-reviews)

**The session, in order:**

| | Step | What it settles |
|---|---|---|
| 1 | [Identity](#1-identity) | `BEADS_ACTOR`, and that this checkout is not stale |
| 2 | [Arm the budget](#2-arm-the-budget) | the clock, and how to resume after the host dies |
| 3 | [Sync, release stale claims, take one epic](#3-sync-release-stale-claims-take-one-epic) | one pull, one epic claim, the sweeps |
| 4 | [Ensure the epic has features](#4-ensure-the-epic-has-features) | breakdown, or the epic is already decomposed |
| 5 | [Work features](#5-work-features) | the loop: 5a set up · 5b batch · 5c review+merge · 5d PR · 5e feature review · 5f next |
| 6 | [Finalize](#6-finalize) | publish, sweep worktrees, hand off |
| 7 | [Log the friction](#7-log-the-friction) | what to file so the next session is faster |

Step 5 is most of this file. Its six sub-steps are a cycle, not a checklist:
5b→5c repeats per batch, 5f sends you back to 5a for the next feature.

**This file is long because each rule is an incident.** Cited bead ids
(`computenet-…`) hold the full story; don't relax a rule without reading its
bead. If you are looking for one thing, use the table above rather than
reading start to finish.

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

`bd` has a set of behaviours that return a wrong answer rather than an error —
a `null` that means "absent", a search that misses, a JSON shape that flips, an
id that two machines can mint twice. They are in
**[references/bd-traps.md](references/bd-traps.md)**, and every step below
assumes you have read them.

The two that bite hardest, inline because skipping them costs the most:

- **`bd show <id> --json` returns a LIST** — unwrap `.[0]` or every field reads
  `null` — and `bd` prints warnings on stdout **before** the JSON, so slice with
  `sed -n '/^[[{]/,$p'` before `jq`. An empty `jq` result is never evidence of
  an empty query.
- **`bd create --parent=<shared epic>` is banned** — it mints ids from a
  per-database counter and two machines collide. Use
  `.claude/skills/work/scripts/create-ticket.sh`.

`$SCRATCH` throughout is a **session-unique** temp dir: create it once as
`SCRATCH=$(mktemp -d "<harness scratchpad>/work.XXXXXX")` — concurrent sessions
sharing a plain scratchpad path overwrite each other's dumps
(computenet-wpvy.43).


## Scripts and references

The scripts do the fiddly parts — where a wrong flag or missed filter
silently loses work. Prefer them to hand-rolling. After editing one, run its
sibling test (`<name>.test.sh`, or `next-batch.test.py`).

| `.claude/skills/work/scripts/…` | Does |
|---|---|
| `sweep-stale-claims.sh` | Reopens this machine's task claims abandoned by a dead run (skips reviewed-and-waiting and skill-friction items) |
| `check-files-claim.sh` | Warns when a bead's own text names a file its `metadata.files` claim omits |
| `ready-in-epic.sh` | Ready work anywhere beneath an epic — `bd ready --parent` reaches one level |
| `reclaim-worktrees.sh` | Removes worktrees whose bead is already closed — the join `sweep-merged-prs.sh` cannot make |
| `sweep-merged-prs.sh` | Closes beads whose PR merged after their session ended; removes their worktrees |
| `next-batch.py` | Next set of tasks safe to run in parallel — file-disjoint AND within machine capacity |
| `ensure-worktree.sh` | Attaches a worktree on a branch, new or resumed, or fails loudly |
| `epic-of.sh` | Resolves a bead's effective epic (`.parent` chain, else dotted prefix) |
| `claim-epic.sh` | Claims or takes over an epic and pushes the acquisition (the claim-as-lock bracket) |
| `feature-branch.sh` | Resolves a feature's branch + worktree, minting `-rN` when the old PR squash-merged |
| `publish-beads.sh` | Publication push with rejection recovery; fails on a nonzero exit **or** a rejection in the output |
| `create-ticket.sh` | THE create path for a ticket under a shared epic — unparented, then re-parented |
| `file-friction.sh` | Files a friction item collision-free under the SDLC epic, open and unclaimed |
| `resumable-epics.sh` | Epics holding a feature left `in_progress` — step 3 ranks these above priority |
| `bead.sh` | projected `bd show` — the bead's own fields as one object, `dependencies` dropped (57KB -> 7KB); no `.[0]` unwrap |
| `wait-checks.sh` | THE settle loop on `gh pr checks` — classifies on output, never `$?`; ends `SETTLED`/`TIMEOUT-PENDING`/`QUERY-FAILED` |
| `verify-branch-sync.sh` | 5a's worktree-contains-origin check plus the squash-leftover classification, as one enumerated verdict |
| `merge-task.sh` | 5c's gated merge of a passed task into the feature branch: guards, merge, durability proof, close |
| `session-holder.sh` | this session's unique holder token, and `--check <token>` → MINE/LIVE/DEAD/UNKNOWN/FOREIGN; what tells a live sibling from a crash leftover, which `assignee` cannot |
| `junit-count.py` | JUnit XML accounting (counts + newest timestamp, both glob depths); refuses to report zero result files |

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
| `references/merge-task.md` | 5c — the orchestrator's half: reviewer dispatch, verdicts, merging a pass |
| `references/next-unit.md` | 5f's routing table — which of routes 0–4 applies when a unit finishes |
| `references/bd-traps.md` | `bd` behaviours that return a wrong answer rather than an error |
| `references/dolt-conflict.md` | step 3, when `bd dolt pull` reports issue conflicts |
| `references/mutation-check.md` | the one mutation-check procedure; cited by task.md, both review references |
| `references/epic.md` / `feature.md` / `task.md` | handed to breakdown/implementer dispatches |
| `references/review-task.md` / `review-feature.md` | handed to reviewer dispatches |
| `references/agent-execution.md` | execution discipline every dispatched agent runs under; task.md and both review references point at it |
| `references/gradle-evidence.md` | the cache-accounting rules — proving a Gradle run happened; cited by task.md and both review references |
| `references/resume.md` | a `status=stopped` task-notification from the PREVIOUS session — the host process died; re-arm the clock, query side effects. Also: a host REBOOT, and `Operation not permitted` on everything under `~/Documents` mid-slot |
| `references/long-jobs.md` | before starting any long background Bash job of your own — wrapper timeout, stall watch |
| `references/direct-child.md` | step 5, when the epic has no feature layer — direct bugs/tasks worked as their own PRs |

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

**And check the checkout you are about to run scripts from is current**, here,
before anything reads it:

```bash
M=<main-checkout>                       # the -C target, NOT wherever you were launched
git -C "$M" fetch origin main --quiet
git -C "$M" rev-parse HEAD origin/main
git -C "$M" merge-base --is-ancestor origin/main HEAD \
  && echo "OK: main checkout contains origin/main" \
  || echo "STALE: this checkout does NOT contain origin/main — its scripts and SKILL.md are not the ones main has"
git -C "$M" status --porcelain          # anything tracked here => another session may be mid-work
```

**`-C <main-checkout>` is the whole point of the check** — run it without it
and you test whichever tree you happen to stand in. You may well have been
launched from a worktree cut fresh from `origin/main`, in which case the
bare command prints `OK` while the checkout the scripts actually come from is
44 commits behind: the exact reported failure, now wearing a green light. The
asymmetry *is* the bug (computenet-6xm).

Every `scripts/*.sh` in this file runs from the **main checkout**, because
that is where `bd` lives — and the main checkout's working copy drifts (44
commits behind, measured; computenet-kcu). So a session can execute a version
of a script that `main` has already fixed, and find out several steps later as
a rejected push or a wrong answer, with nothing connecting the two.

`STALE` → **fast-forward before continuing** (`git -C "$M" merge --ff-only
origin/main`). Refused — local commits, or a modification to a file the
fast-forward would overwrite — → stop and report: a session running scripts
from an unknown revision is worse than a session that did not start.

**Do not fast-forward a dirty checkout, even though git lets you.** Other
sessions share this working tree and its index, so `status --porcelain` above
is a check, not decoration. A `--ff-only` aborts only when it would overwrite
a file *you* modified; with unrelated modifications staged or unstaged it
succeeds and moves a shared HEAD out from under a concurrent session
(measured). Tracked modifications present → stop and report them rather than
merging. Worktrees attached to the same `.git` are unaffected either way —
each has its own HEAD, index, and files — so only the main checkout's own
occupants are at risk.

This is a **one-shot pin, not a live guarantee**: it bounds which revision the
session STARTS from, and nothing more. `origin/main` will move under a long
run — and so, more surprisingly, will the local checkout's own HEAD.

**The main checkout is SHARED, and another session on this machine can pull it
forward mid-run.** Measured: step 1 passed cleanly at 07:20Z with the checkout
on `main` at `c1dd1fa5`; at 10:03Z the same checkout was at `87230510`, moved
by a concurrent session. Every `scripts/*.sh` and `next-batch.py` call after
that point ran from a different revision than the ones before it, and the
`STALE` check above could not fire, because the checkout was never stale — it
was AHEAD. Forward drift is benign in effect (newer scripts carry fixes), but
for about four hours the session could not have said which revision produced
any given result, and only noticed by accident, on finding a script present
that its pinned base predated (computenet-0rmu).

So **record the HEAD here and re-read it before Finalize**, and report the pair
when they differ:

```bash
git -C "$M" rev-parse HEAD | tee "$SCRATCH/step1-head"   # re-read at Finalize
```

Re-fast-forwarding mid-run is still the wrong move — it shifts scripts under
your own in-flight steps — so this is a *notice*, not a fix. If a script
behaves unlike its description later in the run, re-run this check before
believing the description is wrong (15 minutes went that way once).

Note the asymmetry with the dispatch rule below: **agents** read
`.claude/skills/work/**` from their own worktree, cut fresh from
`origin/main`, so they are current by construction. Only the orchestrator runs
from the drifting checkout, which is why only the orchestrator needs this.

## 2. Arm the budget

**The subtraction below is THE budget mechanism; the monitor is a
convenience that may or may not fire.** It has now gone permanently silent
twice by two different routes — once after a host suspension
(computenet-6664), once with no suspension at all, `uptime` reading "up 7
days" throughout and the machine busy the whole slot: not one of the three
tiers ever spoke again after the start confirmation, and the slot was 16
minutes over before the hand recomputation caught it (computenet-ltv9). Both
times the hand subtraction was the only thing that noticed. Arm the monitor
anyway — a tier that does fire is free — but never let its silence mean time
remains.

```
Monitor({
  description: "work session budget",
  persistent: true,
  command: `sleep 11700; echo "BUDGET T-90m ($(( ($(date -u +%s) - $(cat "$SCRATCH/slot-start")) / 60 ))m REAL elapsed): finish the current feature; start no new one"
sleep 2700;  echo "BUDGET T-45m ($(( ($(date -u +%s) - $(cat "$SCRATCH/slot-start")) / 60 ))m REAL elapsed): no new dispatches; review and merge what is in flight"
sleep 2700;  echo "BUDGET EXPIRED ($(( ($(date -u +%s) - $(cat "$SCRATCH/slot-start")) / 60 ))m REAL elapsed): go to Finalize now"`
})
```

Each tier reports the elapsed it computes at the moment it fires, not the
sleeps it slept, so a tier that fires late is distinguishable from one that
fires on time — the suspension case reads as a wrong number rather than as a
correct one. Write `$SCRATCH`'s absolute path into the command; the variable
does not survive into the monitor's shell.

**Record the slot start durably, before you arm it** — you have no other
memory of when this session began, and a resume needs it (below):

```bash
SLOT=18000                              # seconds the routine allocated (5h default)
date -u +%s > "$SCRATCH/slot-start"; echo "$SLOT" > "$SCRATCH/slot-seconds"
```

Record the *length* as well as the start: a resume that assumes 5h on a 3h
slot re-arms a clock that never expires. The files outlive the host process —
the harness scratchpad is not cleaned between sessions, though a REBOOT
clears `/private/tmp` and with it `$SCRATCH` (resume.md, computenet-hd2f) — but `$SCRATCH` is a
shell variable and nothing exports it across calls, let alone across a
restart, so **note the directory's absolute path** here in as many words. A
resume reads the *previous* session's dir by that literal path.

Fires at 3h15m / 4h / 4h45m of a 5h slot (the last 15m is Finalize); scale
proportionally if the routine names a different slot. **Note the monitor's
task id** — `TaskStop` it when you reach Finalize. 
> **`persistent: true` is load-bearing. Do NOT add `timeout_ms`.**
> Verified by probe 2026-08-13: `persistent` genuinely overrides the
> 300000ms default, and the documented 1h maximum would otherwise kill the
> monitor mid-slot — **the clock dies at the cap and the rest of the slot
> runs untimed**, which is the computenet-m5l/776 failure. One session armed
> both anyway, against this instruction, and carried a live 4-hour hazard
> without noticing until Finalize; it read as one clause mid-paragraph
> (computenet-3gf5).
>
> **The tool schema contradicts this, and the schema loses.** Monitor's JSON
> schema lists `timeout_ms` in its `required` array alongside `description`
> and `persistent`, so the literal reading of the line above looks like a call
> the schema rejects — and a session that resolves the conflict in the
> schema's favour ends up with exactly the capped monitor this instruction
> exists to prevent, with nothing about it looking wrong at arming time.
> **Omitting `timeout_ms` succeeds** (measured 2026-08-19; `required` is not
> enforced for it, or the harness fills it). Omit it, and if some harness
> version does reject the call, pass it and record here what evidence showed
> `persistent: true` overriding it there — do not silently accept the cap
> (computenet-bz0n).

| Notification | Do, at the next decision point |
|---|---|
| T-90m | finish the feature you're on; start no new work — this gates 5f routes 1, 3, 4 and step 5's direct-child loop alike. A *breakdown* is exempt (5f route 2b): it opens no branch, so it cannot strand |
| T-45m | dispatch nothing new; review and merge what's already running — and this one binds the breakdown too |
| EXPIRED | **Finalize**, whatever state you're in |

Notifications land at your next turn — they don't interrupt a wait, and a
hung dispatch still wakes you at the deadline (5b).

**The monitor measures WALL CLOCK, so it cannot be trusted across a host
suspension** — `sleep` counts time the machine spent asleep. When a laptop
suspends mid-slot every tier elapses during the suspension and all three
notifications arrive together at resume, so the session goes from *no signal*
straight to *EXPIRED* — precisely the state the three tiers exist to prevent.
Measured: slot started 17:10Z with 300m allocated, host suspended, session
resumed 07:05Z the next day with all three notifications in one batch followed
immediately by the monitor's own "stream ended"; true elapsed 834m against a
300m slot (computenet-3gf5).

The signature is **two or more budget notifications arriving together**, or
any budget notification arriving with "stream ended" right behind it.

**On ANY budget notification — not only at budget-gated decisions — recompute
elapsed from `$SCRATCH/slot-start` before acting on it, and act on the number
rather than on which tier fired — and recompute it anyway at every dispatch
and at the moment you READ any
completion notification, notification or not. A notification's `duration_ms`
is the agent's own runtime, NOT the slot's wall clock: one reporting 7m15s
arrived after 148 minutes of wall clock, and a session trusting it ran 54m
over slot (computenet-vzhs).** After a second host suspension the
monitor went permanently silent and no tier ever fired; the slot had expired
~20 minutes before an accidental check noticed (computenet-6664). A rule keyed
on a notification cannot see the case where none arrives; the subtraction is
one line and costs nothing:

```bash
echo $(( ($(date -u +%s) - $(cat "$SCRATCH/slot-start")) / 60 ))m elapsed \
     of $(( $(cat "$SCRATCH/slot-seconds") / 60 ))m
```

**Never WRITE an elapsed figure you did not compute in that same turn.** The
failure mode is drift, not disagreement: a session recomputes correctly five
times and then keeps reporting numbers extrapolated from the last real
reading. Estimating produces no symptom — the session feels identical either
way — and one that ships every 30 minutes has nothing to make it notice. On
2026-08-27 a session reported "81m", "88m", "98m" while the real figure was
195m of 300m: ~100 minutes low for two hours, at which point it believed it
had a whole wind-down stage in hand that it did not (computenet-hs90,
recurrence of computenet-776). If you have no reading this turn, write "no
elapsed reading this turn" — an absent number is visible, a plausible wrong
one is not.

That is one subtraction, and it is what turned a confusing batch into a
correct diagnosis the one time this happened. A session that instead trusted
the tiers in order would "finish the current feature", then "stop
dispatching", then "finalize" in three consecutive turns with no time between
them.

Three standing disciplines:

- **This is the only persistent monitor.** Every other watch is bounded
  (`for i in $(seq 1 N)`), exits on a terminal state, and there is at most
  one alive; `TaskStop` the old one and let the stop land before arming a
  replacement.
- **A failed `gh` call is not a reading — and neither is a nonzero exit.**
  Socket exhaustion (`dial tcp … can't assign requested address`) says
  nothing about the PR; `gh pr checks` **exits 8 while anything is
  pending**, with well-formed rows on stdout (computenet-15it); and for
  roughly the first minute after a push the required rows are legitimately
  absent, so a bare `grep -q pending` cannot tell that from all-green
  (computenet-1zhu). Classify on the OUTPUT, never on `$?` — never `&&`-chain
  `gh pr checks` and never gate a wait on its exit status. The one settle
  loop is a script:

  ```bash
  .claude/skills/work/scripts/wait-checks.sh <pr-url>
  ```

  **A cold start normally takes TWO invocations**: the ~9m20s window is sized
  to the 600000 ms foreground cap and `build-test-fast` measures 8m56s–13m25s,
  so waiting from the run's start times out on a healthy PR by construction
  (computenet-hil5). On exhaustion the script names each pending check with
  its age and prints `ORDINARY` (re-run it) or `STUCK`; only `STUCK` is a
  defect.

  **That rule covers `gh pr checks`. It applies to EVERY `gh` call, and the
  others were all written bare.** During one 80-minute GraphQL degradation
  roughly one call in three returned 503 — while REST stayed healthy and
  githubstatus.com reported every component operational, so the outage is
  invisible where you would look for it. `gh pr ready` took **five** attempts;
  `gh pr list`, `gh pr view` and `gh pr comment` each failed at least once
  (computenet-rkbp, computenet-fdv9). Retry any of them a few times, and
  classify on output:

  ```bash
  for i in 1 2 3; do out=$(gh pr ready <n> 2>&1) && break; sleep $((i*5)); done
  ```

  Two specifics that cost real work that day:

  - **Never test a `gh` pipeline's exit status.** `if gh pr comment … | tail -1;
    then echo OK; fi` printed OK over a 503 and the comment was never posted —
    the pipeline's status is `tail`'s. Re-read the write (`.comments|length`)
    rather than trusting the call. This is the `${PIPESTATUS[0]}`/zsh trap the
    skill already warns about for the sweep scripts, reaching `gh`.
  - **`gh pr comment` has a REST fallback and draft→ready does not.**
    `gh api -X POST repos/{owner}/{repo}/issues/<n>/comments -F body=@file`
    worked first time while the GraphQL-backed `gh pr comment` would not.
    Marking a PR ready is the GraphQL `markPullRequestReadyForReview` with no
    REST equivalent, so retrying is the only option there.

  It requires all six required rows PRESENT and none pending, keeps the
  three non-settled states apart (query failed / not yet reporting /
  unsettled — one state to any test on `$?`, and two of them look green),
  and ends `SETTLED` (exit 0), `TIMEOUT-PENDING` (4) or `QUERY-FAILED`
  (3 — **nothing was checked**). Use it wherever this file waits on checks.
- **A long job YOU started is not supervised by anything.** A dispatched
  agent's completion notification always arrives; a background Bash job that
  **hangs** produces no notification at all, so absence of news is not
  progress — one such job silently consumed half a session (computenet-6v1).
  Never start one bare: ledger it first (the same ledger Finalize step 7
  drains), then read [references/long-jobs.md](references/long-jobs.md) for
  the wrapper timeout and the stall-watch Monitor.

  ```bash
  # your own ledger — same one you drain in Finalize step 7
  echo "<Monitor|shell|loop> <id or pid> <what it waits for>" >> "$SCRATCH/jobs"
  ```
- **Between notifications you have no sense of elapsed time.** Run
  `date -u +%H:%M` before any budget-gated decision — one session misread
  1h31m as ~3h20m and nearly idled a third of its slot (computenet-776).

### Resuming after the host process died

A `task-notification` with **`status=stopped`** whose summary says it comes
*from the previous session* means the Claude Code host process exited and
this is a resume — not that anything failed (computenet-024s). Read
**[references/resume.md](references/resume.md)** before doing anything else:
it re-arms the clock from the *original* slot start (a naive re-arm grants a
fresh 5h), treats `status=stopped` as an unknown outcome rather than a
failure, and has you query for a killed agent's side effects before
re-dispatching anything — resume, don't restart.

## 3. Sync, release stale claims, take one epic

```bash
git fetch origin main
bd dolt pull        # >=300s timeout; on "merge conflicts in issues require
                    # operator resolution" see references/dolt-conflict.md
```

**If this pull fails, stop the session and report** — with one exception you
can clear yourself. `merge conflicts in issues require operator resolution`,
naming **`issues` and nothing else**, is an ordinary two-machine
concurrent-edit conflict, not corruption, and
[references/dolt-conflict.md](references/dolt-conflict.md) is the worked
resolution (dolt CLI on the embedded DB; last-write-wins by `updated_at`; done
unattended on 2026-08-12 for 11 conflicts, computenet-3v8). Resolve it, prove
`pull` and `push` both work again, name the ids you resolved and which side
won — then continue. Read that file before touching the DB: it has its own
stop conditions, and a variant naming another table (`child_counters`,
`dependencies`) or an added/added id collision is *not* the covered case. Any
*other* pull failure still stops the session. The pull is the only look you
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

**Read `metadata.holder` first — it decides this exactly, where the timestamp
only guesses.** `assignee` is `BEADS_ACTOR`, which is per-MACHINE, so two
sessions on one box are the same string and a live sibling's claim is
indistinguishable from a crash leftover. `metadata.holder` names the SESSION,
and its process either exists or does not:

```bash
.claude/skills/work/scripts/session-holder.sh --check "<the row's metadata.holder>"
# MINE (0) = this session's own | LIVE (0) = a live sibling: leave it alone
# DEAD (1) = crash leftover: releasable | UNKNOWN (3) = nothing established
# STALE (1) = host-process residue (token older than any slot): releasable
#   like DEAD; the worktree is still not yours to enter (computenet-nkz3)
# FOREIGN (3) = minted on ANOTHER machine: not yours, never release it
```

`UNKNOWN` and an absent `holder` (rows claimed before 2026-08-19) are **not
an all-clear** — fall back to the 15-minute rule below and say you did.
`FOREIGN` is not a fallback case: `BEADS_ACTOR` is only *assumed* unique per
machine, and two boxes ran as the same actor on 2026-08-21 — the row is the
other machine's live run, whatever its age; report it and leave it
(computenet-bz5c). A `metadata.worktree` path that does not exist locally
is the same signal on a row with no holder.

Falling back: any row with `updated_at` within 15 minutes probably belongs to
a live overlapping run on this machine — stop and report rather than colliding
with it. Check every row. Older rows: an *epic* is a crash leftover — release
it (`bd update <id> --status=open --assignee="" --unset-metadata holder` —
clearing the holder too, or the residue token blocks the next claim,
computenet-nkz3); leave non-epic rows alone —
stale *tasks* the sweep above already reopened, and a stale *feature* is the
5a resume marker, not a leak.

**Count the live siblings you found and carry the number to 5b.** A sibling
is not only a reason to stop; if the operator has sanctioned concurrent
running, it is a capacity input. `next-batch.py --siblings N` splits the
machine's parallelism budget instead of letting each session claim all of it:
four sessions on a 10-core box each computed a cap of 2 independently, every
one correct by its own accounting, for 4x the measured safe parallelism
(computenet-arow). Re-run this step-3 listing AFTER the claim and use that
count for `--siblings`, so a sibling that finished during selection is not
counted (computenet-nkz3).

**This check is a race, not a lock, and the holder does not change that.** It
only ever trips on a sibling that has ALREADY claimed something — two sibling
claims 84 seconds apart mean whichever session reaches this point first reads
"alone" and proceeds, and a sibling still in step 1 or 2 is invisible
entirely. What the holder buys is that once a claim exists, it is decidable.
`claim-epic.sh` re-runs the test at the moment it writes, so an arbitrarily
slow step 3 cannot widen the window between checking and claiming — one
session's step 3 ran three hours on a slow host, and it claimed an epic a live
sibling was working the whole time (computenet-83ay, computenet-yurq).

**Only after the liveness check, reconcile beads against merged PRs:**

```bash
# $SCRATCH must already exist — create it once here if you haven't (see "bd traps")
.claude/skills/work/scripts/sweep-merged-prs.sh > "$SCRATCH/sweep.txt" 2>&1  # --dry-run to preview
rc=$?; tail -40 "$SCRATCH/sweep.txt"; echo "rc=$rc"
```

Auto-merge lands PRs minutes *after* their session ends, so no session
observes its own merge; this closes what drifted and removes those worktrees.
It runs here and nowhere earlier — it removes worktrees, and a concurrent
session's just-merged worktree is clean *by definition*, so sweeping before
the liveness check deletes a live run's state. It is deliberately
unconditional (no epic/claim/review filter): three narrow re-checks all
missed the same four leaked features (computenet-wpvy.25). Read `rc`: 3 =
nothing was checked (`gh`/`bd` unreachable), 1 = some closes or removals
failed — neither is "clean sweep"; say which you got.

**Then reclaim what the sweep structurally cannot**, in the same step:

```bash
.claude/skills/work/scripts/reclaim-worktrees.sh > "$SCRATCH/reclaim.txt" 2>&1
rc=$?; cat "$SCRATCH/reclaim.txt"; echo "rc=$rc"   # --dry-run to preview
```

`sweep-merged-prs.sh` joins from the **bead** side, so a bead that reaches
`closed` with its worktree still on disk — a human `bd close`, a session
closing its own work, the SDLC lane's supersede-closes — is reclaimed by
nothing else, ever (four directories measured stranded, computenet-8l4r).
This script inverts the join: it walks `git worktree list` and removes each
`computenet-worktrees/<id>` whose bead is closed, whose tree is **clean**,
which has **no rebase/merge in progress**, and whose **HEAD is contained in
some `origin/*` branch** — *any* origin branch, not the same-named one,
because a task branch is never pushed and its commits reach origin inside
the *feature* branch (computenet-zmso, computenet-13kh). The containment
guard is what a clean tree does not give you: without it a worktree carrying
unpushed commits is deleted silently. Detached HEAD and commits on no origin
branch are each a SKIP; an unreachable origin aborts everything at `rc=3`.
The 15-minute quiet hold is a cheap filter on the close/write race, **not**
a liveness test — an agent sitting on an idle worktree reads as quiet.
`rc=1` means a candidate was dirty, mid-operation, not provably pushed, or a
removal failed: look, do not re-run.

A `SKIP … on NO remote ref` line means what it says — the commits are on no
origin branch — so do **not** blanket-ignore the `rc=1`. The one case where
those commits may nonetheless be redundant is a branch squash-merged and
then deleted on origin: the content is on `main` under a new sha, these
commits are not, and the script cannot tell the two apart. Judge that one by
hand.

**Capture to a file rather than piping, for every script whose exit code you
have to report** — this one, `claim-epic.sh`, `publish-beads.sh`. A pipe
makes `$?` the exit code of `tail`, always 0, and the bash escape hatch does
not exist here: under zsh `${PIPESTATUS[0]}` expands to the empty string,
which reads at a glance like a successful zero (computenet-8d88). Never
write `${PIPESTATUS[n]}` in this skill.

**Select and claim one epic** — **continuations first**, then `bv`'s graph
ranking where available, then priority, skipping the SDLC epic (see "The
SDLC exclusion" below; the filters are in the commands because a filter
applied by eye gets forgotten):

```bash
bd ready --type=epic --json > "$SCRATCH/ready-epics.json"   # ~43KB inline — overflow
.claude/skills/work/scripts/resumable-epics.sh > "$SCRATCH/resumable.json"
jq --slurpfile r "$SCRATCH/resumable.json" \
   '[.[] | select(.id != "computenet-wpvy")
         | . + {resumable: (.id | IN($r[0][]))}
         | {id, title, priority, resumable, assignee, updated_at}]
    | sort_by((.resumable | not), .priority)' "$SCRATCH/ready-epics.json"
#   parens matter: | binds looser than , so `.resumable | not, .priority`
#   indexes the boolean and dies
# full descriptions stay in the file — read the chosen epic's from there
bv --robot-triage 2>/dev/null \
  | jq -r '.triage.recommendations[].id' > "$SCRATCH/bv-rank.txt" || true
.claude/skills/work/scripts/claim-epic.sh <the id you selected>
```

**`resumable: true` outranks everything below — take the top row.** A resumable epic
holds a feature some session left `in_progress`: a branch, a worktree, usually
a green draft PR, and merged tasks beneath it. That is the most expensive work
in the queue and the only work in it that *decays* — a draft PR rots into
conflicts against a moving `main` — and it is invisible to `bd ready`, which
lists ready work, not work already in flight. Nothing else will ever surface
it.

Without this rule priority alone decides, and a half-finished epic is one draw
among every other epic at its level. Measured 2026-08-19: `98u.1` (PR #313,
green, CLEAN) and `t6b.3.2` (PR #306, green, CLEAN) had sat 24h and 36h, each
two of *twelve* P2 candidates, and both needed a hand-applied priority bump to
be selected at all. Finishing beats starting; this is the rule that says so.

A **stale** resumable is not an exception here — 5a's resume route reads the
feature's state and may find it dead rather than paused. That is 5a's call,
not a reason to skip the epic at selection. **A HOT one is**: `claim-epic.sh`
exits 1 with `SKIP: … subtree is hot` when any descendant bead or
`origin/feature/<epic>*` tip moved within 15 minutes — the other machine
released the epic at its Finalize and is still inside a child (computenet-hl8x).
Take the next row and say which signal fired; `CLAIM_SKIP_HOT=1` only when
the hot child is your own crash leftover.

**Among the non-resumable remainder: candidates that appear in
`$SCRATCH/bv-rank.txt` first, in that file's order; the rest by priority.**
`bd ready` stays the candidate set —
`bv`'s recommendation list is capped (3 of 22 ready epics appeared on
2026-08-19) and includes blocked and claimed items, so it *reorders* the
ready set and never replaces it or admits a candidate. The epic layer is the
dependency-richest one, which is exactly where `bv`'s unblocks-count /
PageRank / betweenness ranking beats a flat priority sort. Preconditions,
both from AGENTS.md "Choosing work: bv": run it from the **main checkout**
(worktrees have no export — selection runs there anyway), and only after the
**export freshness check**. `bv` absent or export stale → an empty/missing
rank file, and the order is priority alone, exactly as before — do not stop
to install or repair mid-session. Every guard below (parent ownership,
`needs:` labels, staleness) runs unchanged on whatever candidate ranks
first.

**A candidate that is a CHILD of another epic may be someone's owned
territory.** The sub-epic rule below has a session break one down under the
claim it already holds, deliberately leaving it `open` with no assignee — so
it reads as free here, and `bd update --claim` succeeds on it with no
staleness test at all (that test only runs on an epic that already carries an
assignee). Read the candidate's parent before claiming:

```bash
bd show <candidate> --json | jq -r '.[0].parent // "(none)"'   # key OMITTED when unset; a dotted id's prefix is its parent
bd show <that parent> --json | jq -r '.[0] | "\(.issue_type) \(.status) \(.assignee)"'
bd comments <candidate> --json > "$SCRATCH/prov-<candidate>.json"   # holder's provenance comment; --json + file because
                                                                    # the default view TRUNCATES bodies mid-word (computenet-wq14)
```

**A candidate may need a toolchain THIS machine does not have.** The fleet is
heterogeneous (`bv` is on some machines and not others, computenet-j9ku), and
selection reads descriptions, never comments — so a prior session's recorded
"this machine cannot build this epic" verdict is invisible and gets re-derived
from scratch. Check the durable form before claiming:

```bash
bd show <candidate> --json | sed -n '/^[[{]/,$p' \
  | jq -r '.[0].labels[]? | select(startswith("needs:")) | ltrimstr("needs:")' \
  | while read -r tool; do
      .claude/skills/work/scripts/have-tool.sh "$tool" || echo "SKIP: needs $tool, absent or not runnable here"
    done
```

Anything printed → **skip this candidate** and take the next, saying which
tool. Do not park it: the epic is fine, this machine is simply not the one to
run it.

**When YOU are the session that discovers the missing toolchain, record it in
that form** — `bd update <epic> --add-label "needs:<tool>"` — and skip.
The label names the tool as have-tool.sh probes it (needs:docker means a
running daemon, not a binary).
Do **not** ask-human it. A `human` park is `blocked` + `assignee=human` +
the `human` label, which removes the epic from **every** machine's queue until
a person answers, so a machine-capability fact becomes a repo-wide block by
the one machine that could not run it. That happened to `computenet-egl`
(DSC0, iroh transport, priority 1): a 2026-08-18 session recorded in a
*comment* that it had no `cargo` and skipped; the next session on that machine
could not see the comment, ranked the epic first, claimed it, pushed the
acquisition, dispatched a breakdown, and the breakdown re-ran the identical
toolchain probe and parked the epic for a human. The second outcome is
strictly worse than the first — after the skip the epic stayed selectable by a
capable machine, and after the park it was selectable by none. Measured
2026-08-19 on `Anva@A0030`: `cargo` **is** present here, so the epic a
`cargo`-less machine parked repo-wide was buildable on its sibling the whole
time (computenet-yv63).

If a capability question genuinely does need a person, say so in the QUESTION
*and* leave the epic selectable — "route to a machine that has `<tool>`" is a
resolution the fleet can supply without a human.

Parent is an epic that is `in_progress`, or one carrying another machine's
`owner:` label → **skip this candidate** and take the next: the parent's
pushed claim fences its whole subtree, and the sub-epic's comment says who is
inside it. Nothing else stands between two machines breaking the same
sub-epic down twice.

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
surface** — with the depth-independent query, not `bd ready --parent`:

```bash
.claude/skills/work/scripts/ready-in-epic.sh <epic>   # workable, ANY depth
bd list --parent=<epic> --type=feature --status=in_progress --json  # resumable
```

**Not `bd ready --parent`** — it reaches direct children only, so an epic with
ready work two levels down reports empty (`bd traps`, computenet-28vn). That
matters most right here: the `bd defer` below hides the epic on **both**
machines, so it must never fire on evidence from an under-reporting query.
Empty output with exit 0 is a real answer; **exit 3 means nothing was
checked** — stop, do not defer. It also prints a `could not resolve the epic
of <id>` line to **stderr** for any ready item whose parent chain is broken
(a vanished ancestor, a cycle): that item was *not* classified, so resolve it
by hand (`.claude/skills/work/scripts/epic-of.sh <id>`) before treating the listing as complete —
never defer with one outstanding.

Zero workable items and nothing resumable splits into **two** cases. Separate
them with step 4's listing, run now rather than later — same command, so no
extra round-trip:

```bash
bd list --parent=<epic> --all --json     # statuses of ALL children, closed included
```

- **At least one child, and every child closed** → the epic is *finished*, not
  stalled. Close it and drop the owner label — step 6's identical branch,
  including its guard: **an epic with no children at all is mid-breakdown, not
  finished** (`every child closed` is vacuously true there), so that case goes
  to step 4, never to `bd close`.

  ```bash
  bd close <epic> && bd update <epic> --remove-label=owner:$BEADS_ACTOR
  ```

  `bd defer` here would park a completed epic and hide it from both machines
  until a human noticed. **Closing a drained epic does not consume the
  session's one epic claim** — it is bookkeeping, not work — so the session
  then selects and claims a real work epic and proceeds (computenet-q93p).
- **Children open but none workable** (human-gated, or blocked on another
  epic) → park it and select the next. "Human-gated" is not the same as
  "gated": an item whose gate constrains the *form* of the change is
  workable, and only a gate needing an *answer* from a person is not — 5f
  route 4's **"A gated item: the discriminator is WHO MAY DECIDE"** states
  the test, and it applies to this epic's own children exactly as it does to
  continuation candidates. Apply it before concluding a child is unworkable;
  the `human` label only catches gates somebody already formalised.

**Re-run the readiness query in a SEPARATE Bash call before you defer.** Never
defer on a reading taken in the same invocation as a mutation that could have
unblocked something. Observed once and not reproduced: in one call,
`bd close computenet-t6b.3.1` printed "Closed" and the very next command,
`ready-in-epic.sh computenet-t6b`, did **not** list `computenet-t6b.3.2` — a
bead whose sole dependency was the one just closed, and which `bd show`
rendered as satisfied-and-unblocked in the next call. The mechanism is a
hypothesis (a readiness answer computed against pre-close state) and is not
asserted here; what is certain is the symptom — "epic reports no ready work" —
and that this file's documented response to it hides the epic from **both**
machines until a human notices. The moment this is most likely to appear is
exactly the moment a session finishes something and asks what is next. One
extra command closes the whole class regardless of mechanism (computenet-2mou).

**And check the EDGES before you defer — an empty readiness answer is
routinely wrong.** `bd ready`, `bd blocked` and `ready-in-epic.sh` all derive
blockedness from bd's denormalized `is_blocked` column, which goes stale
against the live edge set the moment a blocker closes — the event a work
session generates constantly. Measured seven times across two sessions on
2026-08-19: beads whose sole blocking edge pointed at a *closed* bead, and
beads created minutes earlier with *no* blocking edge at all, were all
reported blocked. `bd dolt pull` did not clear it, and the separate-invocation
guard above does not help, because re-running returns the same wrong answer
(computenet-r79z, computenet-38ze). This is
`doc/demo-findings.md` F-12 reaching the skill's own selection path.

```bash
.claude/skills/work/scripts/verify-ready.sh <the epic's open children...>
```

It reads each id's real edges with `bd dep list` and applies
READY-COVERAGE.md section 2's test — an edge blocks only if its type is
`blocks`/`conditional-blocks` AND its target is neither closed nor pinned.
**Any `READY` line means there is a workable surface: do NOT defer.**
Dispatching by hand on a bead this proves ready is correct, not a deviation.
Exit 3 means a `bd dep list` failed and nothing was checked — that is not an
answer either.

```bash
bd comment <epic> "Parking: no workable surface. <ids: human-gated / blocked on <other-epic>>"
bd defer <epic>
```

`defer` hides it from `bd ready` on both machines while keeping provenance; a
human reopens it. (An epic with *no* children just needs breakdown — step 4.)

Nothing claimable at all → report and stop.

**A SUB-EPIC under the epic you already hold is owned territory, not a second
claim.** `epic.md` assumes the epic it breaks down is claimed, and the rule
below forbids claiming another — so a sub-epic child sat in a gap with no
route (computenet-k9uh). It is covered by the claim you already have: its
effective epic (`.claude/skills/work/scripts/epic-of.sh`) is the one you hold, so **break it down
in place and do not claim it** — no `--claim`, no assignee, no `owner:` label,
leave it `open`. **You** record provenance, not the breakdown agent: a comment
on the sub-epic naming this session and the parent claim it is working under,
**pushed at once** — `bd dolt pull` → comment → `bd dolt push`. Its whole
purpose is that the *other* machine sees it, which makes it a shared-surface
write, not owned territory riding Finalize (claim-sync.md); a comment that
stays local until the session ends protects nothing while the session runs.
`epic.md`'s dispatch template carries the no-claim variant, so this is not
hand-written each time.
And **a sub-epic child counts as workable surface** under the claimed epic:
5f route 1 takes it rather than falling through — to step 4's breakdown under
this rule, not to 5a, since a sub-epic is not a feature to implement.

**One epic *claim* per session** — the claim is how a concurrent run tells a
live session from a crash. The rule limits claims, not work: when the epic
runs dry, 5f says what you may still pick up; idling for hours is a failure
mode, not compliance. It counts *work* epics only — an epic this step closed
as drained, or deferred as unworkable, was never worked, so selecting the next
one is not a second claim. There is deliberately no resume preference across
sessions — a released epic re-competes on priority.

## 4. Ensure the epic has features

```bash
bd list --parent=<epic> --all --json
```

`--all` matters: without it a *finished* epic reads as never-broken-down and
you'd re-create its feature set.

**Children are not decomposition — look for the epic's OWN deliverable among
them.** An epic whose children are its *dependents* (items that consume what
the epic is supposed to produce) reads as broken-down, so the breakdown never
runs and the deliverable is never scheduled, while the children sit
permanently unworkable waiting on it (computenet-45rf). Two readings, both
from the listing you already have (`issue_type` and `description` are both in
it): **no child is a `feature`**, or **every child's description names the
epic as a prerequisite** rather than describing a part of it. Either →
dispatch the breakdown as if the epic were empty, and say in the prompt that
existing children are consumers, not parts. The second reading is the
load-bearing one — computenet-umx's dependents were filed *as features*, so
the type test alone would have missed the case that motivated this.

The same shape is why step 3's workable-surface check must not commit to an
epic whose only workable children are declared consumers of undelivered work:
they are workable in `bd ready`'s sense and unstartable in fact.

**A third shape: children exist TWICE, one set superseding the other.** A
double breakdown — the same epic decomposed twice, by a re-dispatch or by two
concurrent sessions reading the same epic state — leaves near-identical
children in pairs. `computenet-4ru` carried four such pairs (`.6/.7`,
`.8/.9`, `.10/.11`, `.12/.13`), created seconds apart, one twin of each closed.
Neither reading above catches it, and inheriting the aftermath cost a session
real time reading two ~1500-word bodies side by side before any work could
start (computenet-f434). Don't eyeball the listing for it — scan, and read a
flag as a **question** (deliberately parallel beads look identical to it):

```bash
.claude/skills/work/scripts/twin-scan.py <epic>   # finds all six 4ru twins; bv finds none
```
Then:

- **One twin closed** — very likely a clean supersede if it has
  `comment_count` 0 and a `closed_at` within minutes of its `created_at`
  (closed fast, before anyone worked it). Trust the survivor and say so.
  A closed twin with **comments**, or a nonzero diff, carries state the
  survivor may lack: read it before trusting the survivor, and if the two
  disagree that is a human's call ([references/ask-human.md](references/ask-human.md)).
- **Both twins open** — do **not** guess. Keep the set that maps 1:1 onto the
  epic's own suggested decomposition, repair any dependency edges straddling
  both sets, and let **each session close only the beads it created**: an
  orchestrator deleting another session's beads underneath it is the
  destructive move the runbook warns about. If the other session is a live
  same-machine peer, `ListAgents`/`SendMessage` reaches it and two messages
  settle who keeps what — that is what actually resolved `computenet-4ru`,
  and it beats a 20-minute reconciliation by inference (computenet-t9d5).

**A prerequisite between an epic and a non-epic is unexpressible as a bd
blocking edge** — `bd` refuses a blocking dependency across the epic boundary.
So the breakdown cannot wire "these tasks wait on the epic"; it has to give
the epic's own deliverable a **feature** child and block the dependents on
*that*, which is a legal same-class edge. `epic.md` says this too; it belongs
here because it is what makes the fix above expressible at all.

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
-C <main-checkout>. ${claimNote}
Report the feature ids created.`
})
```

`${claimNote}` is one of two lines, and the difference is load-bearing:

- **A claimed epic** → `It is already claimed and labeled — skip both.`
- **A sub-epic under the epic you hold** → `This is a SUB-EPIC under
  <parent-epic-id>, which this session already holds. Do NOT claim it, do not
  set an assignee, do not add an owner label, do not comment on it — leave it
  open exactly as you found it. The orchestrator records provenance itself.`

Substitute `<parent-epic-id>` like every other angle-bracket placeholder here.
The provenance comment is **yours**, not the agent's, because it is a
shared-surface write you have to push the moment it exists (step 3).

**Read the breakdown's report for a re-scope.** `epic.md` requires it to
rewrite an epic's title, description and acceptance in place when the epic
cites its own decided upstream finding, and to say so in as many words. If it
did, re-read the epic (`bd show <epic>`) before step 5 — the acceptance every
feature review traces back to is no longer the text you claimed
(computenet-taug). No such statement means no re-scope; don't infer one.

**Still empty? Check for a deliberate park before retrying.** `epic.md`
requires the breakdown to verify the epic's load-bearing premises and park
rather than produce children inheriting a false one — a correct refusal
looks exactly like a dead breakdown if you only count children
(computenet-wpvy.10). A park is `status=blocked` + `assignee=human` + the
`human` label + a `QUESTION:` comment:

```bash
bd show <epic> --json | sed -n '/^[[{]/,$p' | jq -r '.[0] | "\(.status) \(.assignee) \(.labels)"'
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
bd list --status=blocked --limit 0 --json | sed -n '/^[[{]/,$p' | jq -r '.[] | .id'
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
leftover assignee makes it permanently `--claim`-refused.) **And reconcile the
bead's TEXT with the answer before anyone is dispatched on it**: a park is
usually about WHAT to build, and the acceptance still demands the rejected
option until someone rewrites it — a reviewer scores against `bd show`, not a
comment thread, and two beads shipped against criteria that contradicted the
decision (computenet-wv9c). Concretely:

- `bd update <id> --acceptance=…` (and `--description=…` where it prescribes
  the rejected approach), **keeping the superseded text verbatim under a
  `Superseded <date> by human answer:` label** so provenance survives;
- check the PARENT feature's and epic's criteria for the same clause — the
  park is filed on the narrowest item but the answer's blast radius is every
  criterion that cites it — and amend those too or say on the bead why not;
- an overridden criterion that still names real work nobody now carries is
  **filed as its own bead**, not deleted (4ru.10's reviewer did this unprompted
  as computenet-ylka);
- and the dispatch prompt says `this bead was parked and answered — read its
  comment thread`, so the decision is not invisible by default. The 6h `parked_at`
window doesn't apply here — these are cross-session and evidence-gated.

**Select the feature.** Resume before starting new — this query is the
*only* path back to an in-progress feature:

```bash
bd list --parent=<epic> --type=feature --status=in_progress --json   # resume first
.claude/skills/work/scripts/ready-in-epic.sh <epic>                  # else, ANY depth
```

The second query is deliberately unfiltered by type: `bd ready --parent
--type=feature` reaches direct children only *and* hides everything that is
not a feature, so it can report an empty epic twice over (computenet-28vn).
It prints `<id>\t<type>\t<priority>\t<title>` — take the first row whose
type is `feature`; if there is none but there are other rows, that is the
no-feature-layer shape below, and you already have its answer. The epic
itself is never one of the rows, but a ready **sub-epic** under it can be —
that is a real child needing breakdown (step 4), not a feature to implement.

**The MIXED shape — features AND ready non-feature children under one epic —
takes `ready-in-epic.sh`'s order, not the feature filter.** Both branches
above are written for a pure shape, and read literally a direct child is
unselectable for as long as any feature remains ready: the feature filter
skips it on every pass, and the no-feature-layer route never fires because
features exist. `ready-in-epic.sh` has already sorted by priority, so **if the
first row is not a feature, take it** on the direct-child route
([references/direct-child.md](references/direct-child.md)) before the
features. On `computenet-7em` the skipped row was a priority-1 bug that made
CI actually execute the module's e2e suites — without it both features would
have shipped on green-but-skipped evidence. Working it first is what made the
following PR the first `:demo:beadsmirror` work in the repo's history whose
tests really ran on a required lane (computenet-mv1s).

A resumed feature carrying `metadata.review=passed` was certified last
session — check its PR (`gh pr view <pr> --json state`); `MERGED` →
`bd close` and move on, don't re-review.

**An epic can have no feature layer at all** — bugs/tasks/chores parented
directly to the epic, which both feature queries miss while work sits ready
(computenet-dqy: 69 children, one feature). If `ready-in-epic.sh` (already
run above) is non-empty with no feature rows, read
**[references/direct-child.md](references/direct-child.md)** and work those
items directly on that route — each as its own worktree, branch and PR, with
the reference deciding the review standard, the PR trigger, the idle-lane
question, and the ~2-PRs-per-file bound. Only when that query too is empty
does 5f apply.

Apply the same two filters to the feature picked above; nothing survives →
**5f**.

**Structure**: a feature is the unit of integration — its own worktree,
branch, and draft PR, into which reviewed task branches merge. A task is its
own worktree and branch, cut from the feature branch. Work **one feature at a
time**; the parallelism lives in its tasks. That rule bounds *features*, not
the machine: when a capacity lane is free while the current unit is still
running, the free-lane test is [5f route 0](#5f-next-feature-or-wait-or-stop)
— read it there rather than inferring an answer, and rather than waiting for
5f to become reachable, which it is not until the unit returns
(computenet-0a76).

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
its bead: `metadata.holder` via
`.claude/skills/work/scripts/session-holder.sh --check` (LIVE or FOREIGN → occupied), or
failing that `in_progress` with `updated_at` in the last 15 minutes →
occupied, leave it.

**"One worktree, one live agent" is a WITHIN-session rule, and nothing
extends it across two sessions on one machine.** A recovery session
established death by every signal step 3 prescribes and then some — no bead
written for 45 minutes against a 15-minute threshold, no `java` or `gradle`
process running at all, a clean worktree with no mutation marker, the feature
branch exactly equal to origin — dispatched a reviewer into that worktree,
and *while the review ran* a concurrent session merged the task branch and
removed the worktree underneath it. The reviewer's next `cd` failed with a
bare "no such file or directory" and it had to reconstruct why from scratch
(computenet-dj9h). Nothing was lost, but that is which way the race fell, not
a property of the design. Two consequences:

- **A quiet bead is not a dead session.** A session thinking, waiting on an
  agent, or between dispatches writes nothing for far longer than 15 minutes,
  and its child claims stay LOCAL until Finalize while its epic is open
  (claim-sync.md), so a `bd dolt pull` shows you nothing either. The holder
  check is the signal that survives all of that — use it before adopting or
  removing anything another session may own.
- **Before you remove a worktree another session might hold**, check for a
  live holder on the item it belongs to, and prefer leaving it: an orphaned
  worktree costs disk, a removed one costs a live agent its ground. Guessing wrong costs real work: two agents in one worktree converge
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
# Third argument: metadata.base_branch when the bead carries one (a residual
# whose subject is feature-branch-only, review-feature.md §7 / direct-child.md,
# computenet-nb44) — then origin/<that branch>, and the PR targets it too.
.claude/skills/work/scripts/ensure-worktree.sh <worktree> <branch> origin/main

.claude/skills/work/scripts/verify-branch-sync.sh <worktree> <branch>
```

`verify-branch-sync.sh` runs the worktree-contains-origin check **and**, on
a mismatch, the squash test — this repo squash-merges, so a fully-landed
branch's commits are ancestors of nothing and a merged leftover trips the
ancestor check exactly like genuinely unmerged work (computenet-q8uv,
computenet-aeg, computenet-dtl). Read its final verdict line, and only it:

| verdict (exit) | meaning → do |
|---|---|
| `OK-CONTAINS` (0) | verified — the remote tip is an ancestor of `HEAD`; proceed |
| `OK-NO-REMOTE-BRANCH` (0) | verified — origin answered and has no such branch (first run); proceed |
| `SQUASH-LEFTOVER` (2) | the remote ref outlives its squash-merged content; carries nothing to orphan. Remediate: take a distinct branch name and record it in `metadata.branch`, or delete the dead ref (`git push origin --delete <branch>`) and proceed on the original name — say which you did |
| `STOP-UNMERGED` (1) | the branch carries real unmerged commits — **hard stop**; entering 5b silently orphans reviewed work |
| `STOP-UNREACHABLE` (3) | **nothing was checked** — do not proceed on it |

Do not substitute a `git diff` against `origin/main` for the script's
squash test, in either form: bare it is never empty, and scoped to the
branch's files it decays as `main` churns them — empty proves the content
landed; non-empty proves nothing (computenet-q8uv).

`feature-branch.sh` already retires a *recorded* branch whose PR merged, so
what reaches the squash test is the case it cannot see: an id with no
`metadata.branch` yet whose `feature/<id>` name is already taken on origin —
the re-minted id of computenet-q8uv.

`ensure-worktree.sh` is idempotent: leaves an attached worktree alone,
attaches local branches, tracks remote-only branches at the remote tip,
fast-forwards strictly-behind, keeps strictly-ahead (unpushed work), and
fails loudly on divergence.

**A dirty inherited worktree may be a half-applied MUTATION** — this repo
verifies pins by mutating code — production **or test**, since a
test-instrument defect is probed by breaking the test (task.md step 3,
computenet-wpvy.34) — and an agent killed mid-mutation looks identical to one
killed mid-improvement. So a dirty *test* file is not evidence of finished
work any more than a dirty production file is; the marker, not the file's
kind, is the discriminator. The marker is **gitignored**, so a clean
`git status --short` is not evidence there is no marker — look for the file
itself ([references/mutation-check.md](references/mutation-check.md) step 5,
computenet-9ytv). Classify before acting
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
git -C <worktree> push -u origin <branch>   # even with no commits yet — 5c's
                                           # origin-state gate requires the ref
```

**Push even when the branch has nothing on it.** On a fresh feature the
branch is cut from `origin/main` and the merge is a no-op, so the push looks
like pure ceremony — and the skill rewards not doing redundant work elsewhere,
so an agent optimising for that skips it every time. The reason arrives two
agents later: `merge-task.sh`'s origin-state gate hard-fails on the missing
ref (`GATE origin-state: FAIL — origin has no feature/<id>`), and no task can
merge until somebody pushes it. The push is what makes the branch *mergeable*,
not a publication of content (computenet-5iuy).

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
# A `resumed: false` entry whose `branch_has_commits` is TRUE is finished work
# wearing a fresh label — sweep-stale-claims.sh reset the status. INSPECT before
# dispatching (git log in the worktree, bd comments on the bead) and route to 5c
# rather than to an implementer, or you put a second implementer onto a branch
# that already carries the deliverable (computenet-jw9x).
# `merged_into_feature` TRUE with both of those FALSE is the cross-machine
# twin: a dead session elsewhere merged the task into the feature branch and
# never closed it. No task worktree exists here — route to 5c review against
# the merged range, the feature worktree standing in (computenet-kklt).
# --siblings N if step 3 found N live sibling sessions on this box, or the
# operator sanctioned concurrent running: the capacity cap is PER SESSION and
# the machine is shared (computenet-arow). The verdict echoes what it used
# under "capacity".
```

Returns `{batch: [{id, model, files, worktree, branch, resumed}], skipped,
verdict, parked, capacity}`. The batch is what can safely run at once:
resumables first (nothing else ever picks them back up), then ready tasks
whose `files` claims don't overlap the batch; a task with no claim comes back
alone. That is correct scheduling either way — but a claimless task still
needs the bookkeeping split out under **Before claiming each task** below
(deliberate vs forgotten); don't comment on one before reading it. The batch
is also bounded
by **machine capacity** (`capacity.max_parallel = max(1, cores // 5)`,
measured — see `capacity_limit()` in the script): parallel Gradle contention
lands as timeouts in exactly the suites the epics exist to characterise,
corrupting the evidence (computenet-k9d.2). Entries `skipped` as
`over machine capacity` are a hold, not a problem — next round takes them.
**Don't raise the cap by hand**; it rests on measurement, and the derivation
and its limits live in the script.

**The cap bounds agents; the contention unit is the repo-wide gate.** Three
file-disjoint implementers each running `./gradlew test` contend on the whole
build at any claim disjointness (route 0 already says this for a free lane;
it holds for a batch: load 338-724 on 16 cores, three gates, rotating
:demo:beadsmirror timeouts — computenet-qmjd). In a batch of two or more, at
most ONE dispatch keeps the repo-wide gate; every other prompt scopes the
gate to the modules its claim touches and says where the wide evidence comes
from instead (the feature PR's six required checks). Set `${gateScope}`
accordingly per dispatch.

An entry with empty `model` → dispatch at `sonnet`, comment on the task, log
friction. **Empty batch** → read `verdict`, don't infer:

| `verdict` | Meaning | Do |
|---|---|---|
| `all-closed` | every task closed | **5e** |
| `parked-residue` | all non-closed children are ask-human parks; the feature's own work is done | **5e** |
| `blocked` | work remains this session can't start | **confirm against the edges first** (below), then set `parked_at`, go to **5f** |
| `no-tasks` | no tasks at all | breakdown died — treat as empty above |

**A `blocked` verdict is a claim about bd's `is_blocked` column, not about
the edges.** That column is stale the moment a blocker closes, so the verdict
arrives wrong exactly when a session is productive — measured three times in
one session, including on a task created minutes earlier with no blocking edge
at all (computenet-38ze). Confirm before parking:

```bash
.claude/skills/work/scripts/verify-ready.sh <the feature's non-closed tasks...>
```

Any `READY` line and the batch was not empty: dispatch that task by hand and
do not park. Only an all-`BLOCKED` result earns the `parked_at` route.

`parked-residue` exists because parking a finished feature over follow-up
questions *its own implementation filed* strands CI-green work with no path
to `main` (computenet-eic). Those children are deliverables; pass the
script's `parked` array into 5e's `${parkedChildren}` — don't re-derive it
with your own filter, which can drift from the predicate that produced the
verdict. (`parked` is only meaningful on an empty batch.)

**Before claiming each task:**

- **A PRESCRIPTIVE handoff comment is a hypothesis, not an instruction.**
  Ordinary prose ages harmlessly — a record of what was measured stays true as
  a record. A *prescription* does not: it is a claim about what the next agent
  should do, written in the imperative against a tree, a branch and a `main`
  that all move afterwards, and nothing marks it stale. Measured on one bead
  (computenet-lc3o): "update doc/demo-findings.md F-9 to CLOSED naming the
  fix", repeated across two handoff comments hours apart — on the branch the
  next session actually had, F-9 was an unrelated finding and F-10 was taken,
  because the previous session's F-9 existed only on its own unpushed branch.
  Following it literally would have corrupted an existing entry. The same bead
  also said "resume this branch, do NOT re-implement" for work that did not
  exist on that machine (computenet-2qen). Both were the most
  authoritative-looking things on the bead. **Test it against the artifact it
  names in one command before relaying it into a dispatch prompt**, and when
  it is stale, correct it ON THE BEAD — a correction that lives only in your
  dispatch prompt leaves the false instruction sitting there for the next
  reader. This is the same rule the bullet below already applies to a stated
  blocker; a free-text handoff comment was not covered by it.
- **Re-validate a stated blocker or precondition against the ARTIFACT it
  names, before you write it into a dispatch prompt.** A bead's "blocked until
  X lands" was true when it was written; by dispatch time X may have landed
  differently, partially, or not at all. Check the thing itself — the file,
  the symbol, the test, the config — **not a commit subject line**, which
  records what someone intended, not what is now true. Two items in one
  session were dispatched on preconditions that no longer held
  (computenet-rjyl). This is the orchestrator-authorship rule applied to
  preconditions: a claim about what a change *does* needs a run or a citation.
  When your check was indirect, relay it as such — **"I believe X; verify it
  first"**, not as a checked fact. An agent cannot tell your verified claims
  from your plausible ones, and it will build on both. The same applies at 5f
  **route 4's admission gates**, which are where items with no feature parent
  get their preconditions read.
- **Re-derive `metadata.files` against the bead's current decided design.**
  The claim was set at filing; a design answered later can reach outside it
  (computenet-dqy.37 required violating its own claim). Design reaches wider
  → widen the claim and comment why. The dispatch prompt below also tells the
  implementer to report-and-widen rather than choose silently.
- **Disjoint paths are not enough — read each candidate's acceptance for a
  cross-reference into another candidate's claim.** `next-batch.py` proves the
  batch will not merge into a conflict; it cannot see that task A's acceptance
  names a fixture, symbol or file that lives inside task B's claim, which is an
  overlap *by construction* and no diff will reveal until both have landed
  (computenet-nyd). You are already reading every candidate's criteria to write
  the dispatch, so read them for this too, and **serialise the pair** — run one
  in this batch and the other in the next — if any such reference exists.

  The tell that you have one: **a dispatch prompt you cannot write without
  saying both "X is in scope" and an exclusion that covers X.** A prompt
  carrying both is not a boundary, it is a contradiction the agent has to
  resolve by guessing. If you cannot make the boundary unambiguous in one
  sentence, that *is* the signal to sequence rather than batch — do not ship
  the contradiction and hope.
- **An empty `files` claim is two different things — read the description
  before scheduling one.** `next-batch.py` batches a claimless task alone
  either way, which is right for both, but they need different bookkeeping.
  A description opening `files unknowable before diagnosis` or `no diff: …`
  is a **deliberate** empty claim (feature.md's two shapes: diagnosis-first,
  and a measurement whose deliverable is a comment or a run id rather than a
  file). Write the real claim from the diff afterwards for the first;
  for the second there is nothing to write. **Nothing to that effect anywhere
  in the description means the task forgot its claim** — comment on it, fix
  it before dispatch, and log the breakdown defect (computenet-wpvy.30).
  Nothing machine-checks these openers; you are reading for the *claim*, not
  matching a literal. A near-miss (`no-diff:`, `No diff —`, `files unknown
  until diagnosed`) is the deliberate shape, not a forgotten claim — treat it
  as such, and normalise the wording to the canonical opener so the next
  reader doesn't have to make the same call. Reserve "forgot" for a
  description that says nothing about why the claim is empty.

  **A review-filed residual is a THIRD shape, and it is neither of the two.**
  review-feature.md §7 residuals now carry `--metadata` at filing, but one
  that arrives without it is not a breakdown defect: nothing was forgotten and
  there is no breakdown to blame, and cross-bead writes are not authorized to
  a reviewer anyway. Applying the "forgot" branch literally means logging a
  breakdown defect against a reviewer that behaved correctly
  (computenet-419f). Recognise it by the description's `Residual from
  <feature-id>` opener, **author the claim yourself and say so on the bead** —
  and note that this authorship is weaker than a breakdown's, being derived
  from the acceptance criterion by someone who has not read the code. One such
  guess was right; the next reached a second file immediately and had to be
  reported out and filed separately. If the residual's prose names its files —
  several do, in a trailing `Files: …` line — use those.

  **Ask WHERE THE PINNING TEST WILL LIVE, before anything else.** In both
  recurrences that got past the invariant grep below, the omitted file was
  the same kind: the test that pins the acceptance, in a module or seam the
  claim did not reach — a general-path assertion whose claim carried only the
  wire test, and a residency property whose test needed a new kernel file
  (computenet-geky). An acceptance asserting a general-path property needs a
  general-path test; if the claim's only test file sits in a different module
  or seam from the property, the claim cannot satisfy the bead as written.
  `check-files-claim.sh` cannot catch this — it warns when the bead's TEXT
  names a path the claim omits, and these files did not exist yet and were
  named nowhere.

  **This applies to any claim you did not derive from the code, whoever wrote
  it** — orchestrator-authored, copied from a sibling, or inherited from a
  residual filed by the implementer of a *different* bead. That last is the
  one geky found: reporting out rather than reaching is good practice and it
  produces a claim written by someone reasoning about the mechanism they had
  just touched, which is precisely this failure mode from an author the rule
  did not address. Inheriting a claim is not re-deriving it.

  **The failure shape of that weakness is specific**: a claim derived from
  the design space covers the MECHANISM's files and misses the files holding
  the INVARIANT the change moves — the test pinning a counter's magnitude was
  the file ssa.6's claim lacked (computenet-af9q). So, before choosing a
  mechanism: name the invariant the acceptance constrains (there, the counter
  value a receiver records), grep for the symbols that OBSERVE it —
  `grep -rn 'highWaterFor\|sigCounter' --include='*.kt' .` — and claim what
  comes back as well. When the grep returns a lot, **prefer the wider claim
  and say the breadth is deliberate**: an over-broad lock costs a sibling a
  batch slot; an under-broad one costs an implementer a mid-task stall it
  cannot resolve alone. Keep saying on the bead that the claim is
  orchestrator-authored and how it was derived — that record is what lets
  the next miss be diagnosed.

  **A claim COPIED from a sibling bead is orchestrator-authored too, and gets
  the same grep.** Copying feels like the conservative move — the claim is
  evidence-backed and came from a bead that shipped — and that framing is
  exactly what suppresses the check (computenet-jm7k, a recurrence of af9q).
  A sibling's claim describes the files THAT item touched, and an item that
  CHANGES a shared rule touches strictly more than the siblings that APPLY
  it: three files short here, including the fold every future derivation goes
  through. A sibling's claim is a lower bound, never the answer. When the
  acceptance says "uniformly", "everywhere" or "no grandfathering", grep for
  every call site of the thing being changed instead of trusting any existing
  claim. Keep telling the implementer to report a short claim the moment it
  finds one rather than working around it silently — that is what held the
  cost down both times.

  Never let a
  task take a nominal claim over files it merely reads: a claim is a lock, so
  a read-only lock blocks a sibling for no benefit. A *descriptive string*
  where a path list belongs (`none (tracker mutations only)`) is that same
  defect wearing a non-empty claim — `next-batch.py` reads it as a path and
  batches on it, so it never reaches this bullet; if you see one, rewrite the
  field empty and move the sentence into the description.
- **A describe-ahead breakdown (5f route 0 / 2b, before its blocker landed)
  has stale anchors by construction** — every line number, every "contracts
  from predecessor tasks" section, every `verified at <sha>` is older than
  the code it now describes (four of four tasks needed correcting,
  computenet-w8cp). Before dispatching one, re-run its premise check against
  `origin/main` yourself, fix or label each drifted anchor, and put the
  landed predecessors' real signatures in the dispatch prompt.
- **Date a prescribed reproduction before you repeat it.** A bead that
  prescribes its own repro or mutation froze an assumption about the code on
  the day it was filed, and within one epic the siblings are deliberately
  fixing the same defect class in adjacent files — so a sibling merging
  *invalidates* it at a rate that is structural, not incidental
  (computenet-vyr, computenet-dqy.36: the prescribed mutation had been made
  inert by a sibling the orchestrator had merged an hour earlier, and
  following it produced a green run that read as "my fix does not work").
  Before dispatch, compare the bead's `created_at` against what has landed on
  the files it names:

  ```bash
  git -C <main-checkout> log --oneline --since=<bead created_at> origin/main \
    -- <the files the repro names>
  ```

  Anything comes back → **say so in the prompt instead of restating the repro
  as mandatory**: name the sibling, and set `${repoAge}` to something like
  "the repro predates <sha> (<sibling id>) on these files — treat it as a
  hypothesis, verify it still discriminates before trusting a negative
  result." Nothing comes back → `${repoAge}` is empty. Never copy a bead's
  mutation into the prompt under the word MANDATORY without running that
  check; the prompt is what makes a stale instruction sound authoritative.
- **Restate any cross-bead write the bead's criteria demand — ids and
  action — in the dispatch prompt. Read it from the batch entry's
  `cross_bead`, not from the prose.** `next-batch.py` surfaces the field the
  breakdown wrote (feature.md); an empty string is the normal value and means
  *none authorized*. Without a field you would be hand-grepping every task's
  description for a clause you cannot reliably spot, to fill in an input this
  skill treats as load-bearing (computenet-eetn). If a task's criteria plainly
  demand a cross-bead write and `cross_bead` is empty, that is a breakdown
  defect: write the field before dispatching, and say you did.
  Authorization living only in the bead is
  invisible to the policy check, which reads the prompt; an agent doing
  commissioned cross-posting got flagged and the orchestrator adjudicated its
  own commission as an overstep (computenet-dqy.72, computenet-szdd). Write
  it once as `${crossBeadWrites}` and carry the **same string** into the 5c
  and 5e reviewer prompts (`none` is the normal value and means something:
  no cross-bead write is authorized). Reserved actions stay yours: closing,
  re-prioritising, reassigning, re-parenting or claiming any *other* bead is
  the orchestrator's — a criterion demanding one is done by you after the
  merge, and the prompt says so ("<id> is closed by me, not by you").

Claim, record, attach — **one write per Bash call, each with a ≥300s
timeout**, never chained in one block: `bd` writes contend on the Dolt DB
and a chain of them has blown the 120s default mid-sequence, leaving a
claimed task with no recorded worktree (computenet-9r8):

```bash
bd update <task-id> --claim
```

```bash
bd update <task-id> \
  --set-metadata worktree=<worktree-root>/<task-id> \
  --set-metadata branch=task/<task-id>
```

```bash
.claude/skills/work/scripts/ensure-worktree.sh \
  <worktree-root>/<task-id> task/<task-id> <feature-branch>
# <feature-branch> = the feature's recorded metadata.branch, re-read here —
# an empty 3rd arg silently becomes origin/main. (Runs its own git fetch —
# give it the long timeout too.)
```

These child claims are not re-synced — they're inside the epic this machine
claimed, and the epic claim is the lock that keeps the other machine out of
the whole subtree. One synced claim per level you descend, not one per
sibling.

**Unless the epic is closed.** A closed epic locks nothing, so a child claim
under one is an acquisition and gets pushed like any other:

```bash
bd show <epic> --json | sed -n '/^[[{]/,$p' | jq -r '.[0].status'    # local read, no network
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

**A file the bead's own text names must be in its `metadata.files`.** The
claim is what bounds the implementer, so a bead demanding a file the claim
omits is unsatisfiable from the moment it was written — and the implementer
finds out in its first ten minutes, left choosing between stalling and
working outside its claim. Neither is its call to make
(computenet-yh6.1.12 shipped five files outside its claim, each one forced by
its own acceptance). Check it mechanically before dispatching, on every bead
in the batch:

```bash
.claude/skills/work/scripts/check-files-claim.sh <task-id>...   # exit 1 = look
```

It greps the description and acceptance for path-shaped strings and prints
each one the claim does not cover. It **warns** rather than blocks — a bead
may legitimately name a file it only reads — so read what it prints and
either widen the claim or satisfy yourself the file is read-only. This
applies wherever you author a bead, not only here: 5c's red-check task, 5e's
residuals, step 7's friction items.

**It is blind to anything that is not a path** — a bracketed requirement id
(`[24-TMAP-03]`), a marker, a type name — and passes such a bead CLEAN
(computenet-hws5, then computenet-vjrs). Resolve each one by hand before
dispatching: `git grep -l -F '[THE-ID]'` lists the files that *cite* it,
which is usually not where the artifact lives; the pinning test's KDoc says
where ("which is `MapCellModel`'s file KDoc"). Widen the claim to that file.

(Any grep YOU run here is copied, not typed: `grep -rn "needle" --include='*.kt' .`
— the glob **quoted**. Unquoted, zsh aborts with `no matches found` and the
empty output reads as "nothing references this"; the orchestrator hit it
authorising a rename — computenet-l5rc, u0b0, dy7q.)

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
Committing on your own task branch is EXPECTED AND AUTHORIZED — this sentence
IS the explicit grant both AGENTS.md clauses defer to ("unless explicitly
asked", "unless your assignment explicitly grants it"), so the conservative
profile is satisfied, not overridden. A task's deliverable IS commits on its
branch, not a dirty worktree. What you must not do is push (not even your own
task branch — the classifier denies it and nothing downstream needs it),
merge, rebase, or switch branches.
Your branch's BASE COMMIT, observed at dispatch — the commit the branch was
cut from, NOT a diff baseline: ${taskBase}. Anything merged into main before
it is already in your worktree; check with git rather than assuming either
way. To diff your own work, use git merge-base <feature-branch> HEAD,
computed inside your worktree.
Read it: bd show ${id} --json, then bd comments ${id} --json — an AMENDS
comment there supersedes the description (run bd with -C <main-checkout>;
only that checkout has the beads database)
Then read the skill files FROM YOUR OWN WORKTREE — ${taskWorktree}/.claude/
skills/work/references/task.md — and follow it. Do NOT read them from the
main checkout: it is where bd lives, and its local branch is stale.
Stay inside your metadata.files claim — sibling tasks are running on sibling
branches and merge into the same feature branch. If the bead's own design or
acceptance clause REQUIRES a file outside the claim, do not choose between
them silently: report which file and which clause, and I will widen the claim.
Report that the MOMENT you find it, not in your final summary — a claim that
cannot be satisfied at all is my defect to fix, not a judgement call for you,
and reporting it at the end means the whole task ran on a boundary we both
knew was wrong. While you wait for my answer, keep working on whatever part
of the task the claim DOES cover; if nothing is left, stop and say so rather
than proceeding outside it.
Tracker writes: ${crossBeadWrites or "none authorized — write only to this
bead and to items you create."} Whatever that line says, never close,
re-prioritise, reassign, re-parent or claim any bead other than your own.
If this is a bug fix, task.md step 3 is not optional: run the reproduction
against the UNFIXED code first and quote the failing test name and assertion
message. A prescribed reproduction that passes unfixed is a false lead — and
the likeliest reason is that it went stale, not that your fix failed, so
check what landed since the bead was filed, substitute a mutation that
demonstrably discriminates, and report the substitution on the bead rather
than making it quietly. ${repoAge}
Run every verification command — Gradle above all — in ONE foreground Bash
call with an explicit timeout, up to 600000 ms. If you already know the suite
outruns that 10-minute cap, COMMIT FIRST (do not `git push`, and do not
`bd dolt push` either, NOT EVEN for an authorized cross_bead write: AGENTS.md's
"shared-surface writes push at once" is the orchestrator's duty, and this
dispatch's no-push rule is the exception it names for dispatched agents —
your write rides out on the orchestrator's next bracket),
then background it and IMMEDIATELY, in this same turn, RUN the bounded
log-waiter from agent-execution.md ("The bounded until-loop" — the python3
block) as a foreground Bash call; when it expires, reissue it. Waiting is that
command RUNNING — it is never something you end a turn to do, and a final
message that says you are waiting is a stalled result, not a status. Never
wait before committing, or a stop strands uncommitted work that reads as
nothing (computenet-v5ah).
The Bash tool auto-backgrounds anything that outruns its 120s default, and a turn that ends waiting on a
background job never resumes: your turn ending IS your completion, so there is
nothing to come back to. Never end a turn saying you will wait for a job.
${gateScope — either "" for the one wide-gate dispatch, or: "Scope your final
gate to <modules>; the repo-wide evidence comes from the feature PR's required
checks. Before any long Gradle run read `uptime`; a timeout in a module you
did not touch under high load is machine contention, not a failure — re-run
that suite in isolation before reporting it."}
If you won't finish within ~45-60 minutes, stop at a clean point and leave
the task in_progress with a bd comment saying what's done and what's left.
State any NEXT STEP with the state it depends on — the branch and sha, or the
file as it exists at that sha — never as a bare imperative, because the tree
you are describing will have moved by the time it is read.
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

**Every dispatch prompt carries the foreground-timeout line**, implementer
and reviewer alike — reviewers drive the same suites. Telling an agent to
"run it in the foreground" does not work and was already tried: the
foreground/background choice belongs to the Bash tool's 120s default, not to
the agent's intent, so only an explicit `timeout` argument changes it.
`:demo:beadsmirror:test` in full takes ~11m45s — over the cap, background
it (gradle-evidence.md "How long the suites take" is the named list; an
earlier ~3m40s figure here was one part of it, computenet-wv64); without that
argument the call is
backgrounded, the agent ends its turn saying it will wait, and nothing ever
wakes it. Five stalls across two items in one session, ~40 minutes lost
(computenet-hob2). The three agent-facing references carry the same rule
together with the reason `timeout(1)` is not the answer — it is not installed
on this host and fails open — so **stop pasting that warning into dispatch
prompts by hand**; it was hand-carried into four prompts in one session
(computenet-fbuo).

**While a batch runs, never read a running agent's output** — not
`TaskOutput`, not `Read`, not `tail`. For a local agent that file is the
full JSONL transcript (thinking blocks, tool payloads); one call dumped
tens of thousands of tokens into orchestrator context, unrecoverably, in a
session built to run for hours (computenet-dal). `TaskOutput`'s own text
reads as a mild preference — treat it as a context hazard. The safe
progress checks are: the completion notification (it always comes), the
task's own bd comments (`bd comments <id> --json > "$SCRATCH/..."` —
task.md has agents comment at parks and at finish), and
`git -C <task-worktree> log --oneline` plus `git -C <task-worktree> status
--short` for commits and edits landing (movement there = alive). Those same
three are how you establish whether an agent that returned *without* an
outcome still has work in flight — you never need its transcript to answer
that, and `SendMessage` to the agent is the fourth signal and also the
remedy, because it keeps the agent's context where `TaskStop` discards it
(computenet-77cx). An agent that seems slow but has SOME side effect (a commit, an edit, a bead
comment) is waited on or `TaskStop`ped at the budget deadline — there is
nothing useful between. An agent with NO side effect on any of the three
signals is different: it may never have started (one stalled before its first
tool call and occupied ~120m of a 300m slot, its watchdog notification
arriving only afterwards — computenet-znlh). When a batch is running, arm one
bounded Monitor (`persistent: false`, a single `sleep 1200; echo "PROGRESS
CHECK <batch>"`); when it fires, recompute elapsed (step 2) and read the three
signals — all still empty → `SendMessage` the agent; no substantive reply →
`TaskStop` and re-dispatch rather than keep waiting.

**Find a stated outcome in an implementer's result before acting on it** —
the same rule 5c gives reviewers, and the same failure. A completion
notification looks identical whether the agent finished or stopped itself
mid-task; one returned "I will wait for the background test run notification
before finalizing" as its entire result — no outcome, no files, no commit, no
bead state (computenet-itwc). Done / blocked / premise-wrong, plus the files
touched, or it is not a report: `SendMessage` the same agent (context intact)
to finish and report, and run nothing downstream on that task until it does.

**On batch completion** (wait for the whole batch — a staggered re-batch
computes overlap against a moving set): files touched outside a claim → fix
that task's `files` metadata, and for a diagnosis-first task write the real
claim from the diff — the empty claim was unknowable, not violated. A *zero-diff* task that produced a diff is the
opposite reading: its premise was wrong, so say so on the bead and write the
claim, rather than recording it as the shape it was filed as. A task parked a question → that's one task, not
the feature; a task reported done → 5c. **If a budget notification arrives
while you're still waiting, the batch is over its limit**: `TaskStop` the
stragglers, leave them `in_progress` with a comment (worktrees and branches
survive; a later batch resumes them), continue with what returned, log
friction — a task shape that reliably runs long is a sizing defect in
`feature.md`.

### 5c. Review each task, then merge it

**[references/merge-task.md](references/merge-task.md)** is this step: the
reviewer dispatch, the verdict rules, and the merge into the feature branch.
Come back to 5b for the next batch.

Three things that go wrong silently if skipped, inline:

- **Reviewers must not merge** — concurrent merges into one feature branch
  race. You merge the passes yourself, **one at a time**, and you re-verify
  the feature branch immediately before each merge, because 5a set it up an
  hour and several merges ago.
- **Find an actual verdict in the result before acting on it.** A completion
  notification looks identical whether the reviewer finished or stopped
  itself. No stated pass/fail → `SendMessage` the same agent; agent-completed
  is not task-reviewed.
- **The task branch is local by design and the FEATURE branch must be
  durable** — confirm the merge is on origin before `bd close`, since the
  close is what tells every later session the work landed.


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

**On the direct-child route (step 5's no-feature-layer shape) the trigger is
the implementer's first commit instead**, because no task ever merges into
the item's branch and this trigger would never fire. Everything else here —
the `metadata.pr` guard, the `bd update --set-metadata pr=`, draft until 5e —
is unchanged.

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
  prompt: `Read (with the Read tool — cat truncates it to a ~2KB preview) .claude/skills/work/references/review-feature.md — from
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
Run every verification command — Gradle above all — in ONE foreground Bash
call with an explicit timeout, up to 600000 ms. If you already know the suite
outruns that 10-minute cap, COMMIT AND PUSH FIRST, then background it and
IMMEDIATELY, in this same turn, RUN the bounded log-waiter from
agent-execution.md ("The bounded until-loop" — the python3 block) as a
foreground Bash call; when it expires, reissue it. Waiting is that command
RUNNING — it is never something you end a turn to do, and a final message
that says you are waiting is a stalled result, not a status. Never wait
before committing, or a stop strands uncommitted work that reads as nothing
(computenet-v5ah).
The Bash tool auto-backgrounds anything that outruns its 120s default, and a turn that ends waiting on a
background job never resumes: your turn ending IS your completion, so there is
nothing to come back to. Never end a turn saying you will wait for a job.
Committing your repairs on the feature branch, and pushing that branch, are
EXPECTED AND AUTHORIZED — this sentence is the explicit grant AGENTS.md's
conservative profile and multi-agent clause defer to, and your reference
authorizes both, including the `origin/main` merge on the branch where §6
permits it — §6's NORMAL path hands that merge to the orchestrator, so read it
before reaching for `git merge`. Do not
rebase, switch branches, touch another worktree, or run gh pr ready.
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

**Act only on a verdict.** Three cases — plus the fourth, where no
notification ever arrives: a feature reviewer that neither reports nor stops
is the shape computenet-sjwd measured (1h47m, nothing written, a green PR
that could not ship). [5c](#5c-review-each-task-then-merge-it)'s dispatch
timestamp, ~60-minute bead check, `SendMessage`-then-`TaskStop` ladder,
re-dispatch framing and clear-the-blocker-first rule all apply here
unchanged; the `TaskStop` case below is where that ladder lands.

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
gh pr list --head <branch> --state open \
  --json number,author -q '.[] | "\(.number) \(.author.login)"'   # expect exactly one: yours
gh pr checks <pr-url>
gh pr ready <pr-url>
```

Those two `gh` lines are 5c's pre-merge guard at the ship gate
(computenet-wpvy.29). `headRefOid` **is** origin's tip of this branch, so the
equality is the stronger form of 5c's origin-ahead check; a second open PR on
this head, or one you did not open, is the collision 5c refuses to resolve —
park it, do not `gh pr ready`.

Sha mismatch = "checks not yet available for this commit", not a verdict —
the PR head has been observed lagging the pushed ref by ~10 minutes with
nothing in the output saying so (computenet-qnyn); green-for-commit-N while
the branch is at N+1 would ready a PR on evidence that never covered the
merged code. Wait for agreement, re-read. Commits in the `log` output the
verdict doesn't mention, touching this diff's files (`gh pr diff <pr-url>
--name-only`) → **one of two tiers**, because same-file is not the same as
interacting. Read both sides **before** you merge `origin/main` in — after the
merge the landed side is no longer addressable, because `merge-base HEAD
origin/main` has become `origin/main` itself:

```bash
W=<worktree>; B=$(git -C $W merge-base HEAD origin/main)   # BEFORE the merge
git -C $W diff -U0 $B..origin/main -- <the shared file> | grep '^@@'   # what landed
git -C $W diff -U0 $B..HEAD        -- <the shared file> | grep '^@@'   # what this PR touches
git -C $W diff      $B..origin/main -- <the shared file>               # read it, don't just count
```

(One fenced block on purpose — `$B` does not survive into a second `bash`
call. Do **not** reach for `origin/main@{1}` as the landed side: it is a
reflog entry, so it is absent in a fresh clone — `fatal: ambiguous argument
'origin/main@{1}'` — and where it does resolve it steps back exactly one
fetch, which is this PR's base only by coincidence.)

- **Disjoint hunk ranges *and* neither side's text is about the other's
  subject** → **merge and ship.** Record the check in the PR: the landed sha,
  the hunk ranges each side touches, and that they neither overlap nor
  interact. A re-review that reads two diffs and confirms they never met is
  bookkeeping, and it is not free (below).
- **Overlapping or interacting hunks**, or you cannot tell → **send back for a
  re-check**, as before. "Cannot tell" is the overlapping case, not the
  disjoint one.

**Disjoint ranges are necessary, not sufficient — the check is a read of both
diffs, not a `grep` for overlap.** Two edits 900 lines apart in one
instruction file interact whenever the landed change: states a rule on the
same subject as yours (now the file answers one question twice, differently);
renames, moves or deletes a heading, script path, file, flag or bead id that
your text cites, or vice versa; changes a step your text says to run "as in
step N"; or redefines a term you use. None of those leave a shared hunk range,
and shipping one is a contradiction merged into the file every session
executes. Any of them, or any doubt → second tier.

**The cost this tier exists to avoid is real and compounds.** Every merge of
`origin/main` pushes a new head, and every new head restarts all six required
checks — **9–12 minutes**, governed by `build-test-fast` (measured across four
runs, computenet-678u; this said ~4 minutes until then) — which a sibling merge
can invalidate before it finishes, so the churn is superlinear in the number of concurrent same-file
PRs (computenet-nxac: one PR paid the cycle three times). Which is the other
half of the answer: **keep at most ~2 PRs open against any one file** —
beyond that, sequence. Stated where it bites in
[references/direct-child.md](references/direct-child.md), where nothing else
bounds the count.

**Cheaper still: prevent the collision instead of surviving it.** When two
in-flight branches must each add an entry to the same *ordered list* — the
`include()` block in `settings.gradle.kts`, the module table in
`doc/ARCHITECTURE.md` — the default behaviour (both append at the end, or both
anchor at the same neighbour) guarantees a conflict in every such file.
Agreeing on **different insertion points** converts N guaranteed conflicts
into zero for the cost of one message. Two sessions adding `:oracle` and
`:identity` on 2026-08-17 did exactly that — one row between `:testkit` and
`:wire`, the other after `:wire` — and the peer verified **zero** conflicts in
both files after merging main, with both entries present and nothing lost
(computenet-t9d5). A same-machine peer is reachable: `ListAgents` finds it,
`SendMessage` reaches it. Everything else in this tier is about paying for a
collision; this is the one move that avoids it.

Red required check → red-check-attribution.md; pending → wait with
`.claude/skills/work/scripts/wait-checks.sh <pr-url>` (step 2's rules: classify on output, never
`$?`; computenet-luhx, computenet-15it, computenet-1zhu). A verdict
carrying a **§6 hand-back** is yours to complete, and it is the **normal**
path, not an exception: review-feature.md §6 assigns the merge to you
outright, because the classifier refuses reviewers `git merge`
(computenet-whx4, computenet-dtvd). Do the merge, re-run the affected module
suite on the merged base, and check the reviewer's disjointness claim
yourself (`gh pr diff <pr-url> --name-only` against `git show --name-only
<sha>`) — if it doesn't hold, send back for a scoped re-check rather than
shipping on it. Expect the hand-back on any verdict where commits landed
mid-review, and treat the reviewer's "origin/main unchanged at `<sha>`" as
expired the moment it was written: the `log` above, not that line, is what
settles it.

**A verdict naming a pending measurement is not shippable yet.** `review=passed`
means the review is finished, not that the feature may ship: a criterion
riding an out-of-band measurement (a soak, a CI matrix run, an overnight job)
leaves the ship gate with you. Read the run it names, and ship only once it
reports — or, if it will not report inside this session, say so in the PR and
leave the feature for the next one. Reading `review=passed` as "ship it" here
merges code whose acceptance nobody has finished checking (computenet-wpvy.28).

**Before you ship, confirm the checks EXECUTED this diff's tests**, by the
`SKIPPED`/`NO-SOURCE` log read in merge-task.md §4 (the two load-bearing
greps over `gh run view --log`). A green check on a suite that skipped
itself is not verification of anything, and this is the last point at which
saying so is cheap (computenet-hacm).

**`gh pr ready` is the ship decision, not the ship.** The moment it returns,
read [references/ship-feature.md](references/ship-feature.md) and follow its
state table until `MERGED` or honestly parked. Short form: ready PRs **one at
a time**; `MERGED` → `bd close` the feature, leave its worktree for step 6;
conflicts are yours and get a reviewer like any code you write.

  A residual filed on an **unparented** item (5f route 4 works those) is
  parented to the *item*, not to an epic — there is none — so the chain stays
  walkable and a later continuation session finds it (review-feature.md §7,
  computenet-wpvy.42). That parent edge *replaces* §7's `discovered-from`
  edge rather than joining it — same ordered pair, and `bd` holds one edge
  per pair (computenet-ofzz). Don't re-parent it to your current epic: it did
  not come from there.

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
epic is `computenet-wpvy` (use `epic-of.sh` — `bd ready --parent` is one level
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

When the unit you were working finishes, **[references/next-unit.md](references/next-unit.md)**
is the routing table: which of routes 0–4 applies, what each requires before it
fires, and what closes after T-90m.

Two things that decide most sessions, inline so they are not missed:

- **Routes 1, 3 and 4 are all closed after T-90m.** New work you cannot review
  and merge before the slot ends is a stranded branch. A *breakdown* is the
  exception (it creates no branch and cannot strand) and is bounded to
  T-90m..T-45m.
- **One epic *claim* per session.** The rule limits claims, not work: when the
  epic runs dry, the routes there say what you may still pick up. Idling for
  hours is a failure mode, not compliance. Closing a drained epic, or
  deferring an unworkable one, is bookkeeping and does not spend the claim.


## 6. Finalize

**Re-read the main checkout's HEAD and compare it with step 1's.** They differ
whenever a concurrent session on this machine pulled the shared checkout
forward mid-run, which is routine and benign — but it means the session ran
scripts from more than one revision, and the summary must say so rather than
report a revision it did not hold throughout (computenet-0rmu):

```bash
was=$(cat "$SCRATCH/step1-head"); now=$(git -C "$M" rev-parse HEAD)
[ "$was" = "$now" ] && echo "scripts: one revision, $now" \
  || echo "scripts: checkout MOVED mid-run, $was -> $now (report both)"
```

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
bd show <epic> --json | sed -n '/^[[{]/,$p' | jq -r '.[0] | "\(.status) \(.assignee)"'
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
  --assignee="" --unset-metadata holder` — the claim binds the epic to this
  *session*; a kept assignee makes it `--claim`-refused everywhere else, and
  a kept holder is residue that blocks the next claim (computenet-nkz3). The
  `owner:` label stays as provenance.

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

The script fails on **either** signal — a nonzero exit or a rejection in the
output — recovers a non-fast-forward inline (expected under concurrent
operation), and escalates real conflicts. Neither signal alone is trusted:
`dolt push` against a real non-fast-forward exits 1 and prints
`! [rejected] … (non-fast-forward)` (measured 2026-08-17), but `bd dolt push`
was once observed exiting 0 while printing a rejection, and staging a real
non-fast-forward against the shared remote needs the *other* machine to push,
so that propagation cannot be re-measured from here. `scripts/beads-nightly-sync.sh`
uses the same pair; it previously tested the exit code only, and the two
callers disagreed (computenet-kbk0). After a *recovered*
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
# run artifacts under it go too: task.md step 7 had the implementer either
# copy them to $HOME/computenet-runs/<id>/ or mark the recorded paths
# ephemeral (computenet-mzuc); nothing here preserves them
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

**7. `TaskStop` the budget monitor, then drain the ledger** — `cat
"$SCRATCH/jobs"` (step 2), `TaskStop` or kill every line still alive, then
`rm -f` it. An absent or empty file is a positive answer: you started none.
"I don't think I left any running" is not, and a background job outlives the
session that forgot it. Then summarize: epic worked and its
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
bd search "<ONE distinctive word>" --status all --json   # --status all, or
                                                        # fixed-and-closed twins are invisible and get re-filed
```

**`bd search` matches a case-insensitive literal SUBSTRING of the TITLE (and
id) only — never the description.** Two consequences, both false-negative
(measured on bd 1.1.2, 2026-08-17, computenet-ytlk):

- A multi-word query hits only when those words appear verbatim *and
  adjacent* in a title. `bd search "pushed-ness"` → 1; `bd search "worktrees
  pushed-ness"` → **0** on the very bead whose title holds both words, and
  reversing an adjacent pair (`"search bd"` for a title reading `bd search`)
  → **0** too. So "a few distinctive words" was the worst possible
  instruction.
- Descriptions are invisible: `bd search "epic-of.sh"` → **0** despite the
  string appearing in many bodies. Description search is a *filter* on top of
  a title query — `bd search "<title word>" --desc-contains "<body phrase>"`,
  AND-ed — and it cannot stand alone (`bd search --desc-contains X` errors
  `search query is required`). For a body-only sweep, grep the export:
  `grep -i "<phrase>" .beads/issues.jsonl`.

**Run several single-word searches, one per distinctive term**, and treat an
empty multi-word result as **no evidence at all** rather than as absence.
Substrings match inside words (`orktree` finds every `worktrees` title), so
prefer a stem over an inflected form. The not-found branch below requires at
least one *single-word* search to have come back empty before you file.

**One issue per kind of friction.** Found (and still open) → **upvote it**:
comment this session's instance (what you were doing, what happened, what it
cost — `bd comment <id> "<text>"`, body positional, or `--file` for any body
that quotes code) — comment count is the remediation priority — then claim it for this
machine if unclaimed (`bd update <id> --claim`; already claimed by the other
machine → done, its lane owns it). If the item is labeled `needs-evidence`,
the remediation lane judged the existing reports unconvincing and its latest
comment says exactly what to capture — answer those questions in your
instance comment, then `bd update <id> --remove-label needs-evidence` in the
same breath: the label is what hides the item from the lane and its gate, so
evidence left under a still-parked item is buried. Found but closed → a
recurrence of a fixed-or-rejected issue: file fresh, say so in the
description, and if the close reason starts `rejected:` answer it — your
recurrence is the appeal. Not found:

```bash
# Write bodies to FILES and pass --desc-file/--accept-file (create-ticket.sh
# and file-friction.sh expose both): the text never crosses a shell word, so
# backticks and $(...) in it are inert (computenet-s5dh). A heredoc
# interpolated into "--desc" is the trap issue-quality.md names — the shell
# executes backticks before the script runs, silently deleting the quoted
# phrases (computenet-9w9). Those two flag names are the WRAPPERS' — bare
# `bd create`, the sanctioned path for a breakdown child under an epic you
# have claimed, answers --desc-file with "unknown flag" and has no acceptance
# file flag at all. There it is --body-file plus
# --acceptance "$(cat <<'EOF' … EOF)", which is safe for the same reason the
# file form is: the delimiter is quoted so the shell never expands the body,
# and command substitution does not re-evaluate what `cat` reads. It looks
# like the trap above and is not (computenet-g1gf):
cat > "$SCRATCH/friction-desc.md" <<'EOF'
<what the skill says, what actually happened, what you did instead, what it cost>
EOF
cat > "$SCRATCH/friction-accept.md" <<'EOF'
<what would have to change in the skill for this not to recur>
EOF
.claude/skills/work/scripts/file-friction.sh --type <bug|feature> \
  --title "<the friction in one line — NO 'work skill:' prefix; the script adds it>" \
  --desc-file "$SCRATCH/friction-desc.md" --accept-file "$SCRATCH/friction-accept.md" \
  --skill-version <the epic's metadata.skill_version>
```

`bug` = the skill misbehaved; `feature` = it worked as written but lacks a
capability. It files the item **open and unassigned** — filing is not picking
up, and a filing-time claim is what broke the friction lane's drain listing
(computenet-oxbv). The script labels, stamps the version, then delegates
the create itself to `create-ticket.sh` — the sanctioned path for any ticket
under a shared parent, which creates **unparented** (a `--parent` create
allocates the child id from a per-database counter, and two machines filing
between syncs mint the same id for different beads — a primary-key collision
whose "resolution" destroys real beads, computenet-azt / computenet-wpvy.45)
and then re-parents, keeping the hash id. This applies to shared parents only
— breakdown children under your claimed epic keep their dotted ids.

If `bd comment` is refused by the permission classifier (observed in
unattended sessions while every other subcommand ran), the step still
happens: read the thread first (`bd comments <id> --json > file`), then
`bd update <id> --append-notes "<instance>"` — **`--append-notes`, never
`--notes`** (which overwrites), plain text only (command substitution and
backticks are refused inside values, computenet-9w9), and carry
`--remove-label needs-evidence` on that same `bd update` when you are
answering a parked item — and name the refused command verbatim in the
summary; that is the only way an allowlist entry gets made. Don't fall back
to one-bead-per-session.

Write each item for someone editing this skill next week with none of your
context: name the step, quote the instruction, say what actually happened.
Review the accumulated log with:

```bash
bd list --label=skill-friction --all --limit 0 --json \
  | jq '[.[] | select(.status != "closed")]'     # open AND claimed; --status=open alone hides what step 7 just claimed
```

One report is an anecdote; the same issue commented by four sessions is the
next thing to fix.
