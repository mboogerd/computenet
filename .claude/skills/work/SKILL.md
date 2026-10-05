---
name: work
description: Runs one unattended beads work session end to end — claims an epic, breaks it into features and tasks via Fable subagents, and implements each feature in its own worktree, branch, and draft PR, with its tasks running as parallel reviewed branches. Claims are crash-safe and machine-scoped, so two machines can run this concurrently on a schedule without colliding or deadlocking. Use this skill whenever a cron job, scheduled task, or routine kicks off a work slot, or the user says "/work", "work the queue", "pick up the next beads task", "start working through the backlog", "keep the machines busy", or otherwise wants autonomous progress on beads-tracked work — even if they don't mention beads, epics, or this skill by name.
---

# /work

One session claims one epic and works it until it is done or its context is
spent; the scheduler then starts a fresh session.
You orchestrate: agents do breakdowns, implementations and reviews; you select,
claim, dispatch, merge reviewed branches, ship, and keep the tracker true.
Anything that needs reading or changing code, running builds, or produces long
output goes to an agent — your context has to last hours.

## Principles

These decide every situation this file does not spell out.

1. **Keep moving.** Park or skip a stuck item and work the next. Three of five
   features landed beats a slot spent on the first.
2. **Integrate continuously.** Merge reviewed work as soon as it is ready, open
   draft PRs early, keep branches close to `main`.
3. **Park only what a person must decide** — unclear *and* costly, risky or
   hard to revert — on the narrowest item, then continue elsewhere
   ([recovery.md](references/recovery.md), "Parks"). Cheap does not make a
   person's decision yours. A design question evidence can settle, or a gate on
   the *form* of a change, is workable: settle it and record how.
4. **Author is never judge.** Nothing is certified by whoever wrote it — code,
   bead text, a repair, a conflict resolution, including yours. Reviewers
   certify; you ship.
5. **Relay evidence, not framing.** What you write reaches agents as fact. Pass
   artifacts (run id, failing line, sha); claim what you observed, not a
   mechanism you did not test; mark beliefs "unverified".
6. **State lives on beads.** Parks, decisions, handoffs and corrections go on
   the bead; your context gets compacted and your transcript discarded.
7. **An empty result is first a claim about the query** ([traps.md](references/traps.md)).
   Before an empty answer routes a decision, confirm it a different way.
8. **When a rule does not fit, act on its purpose**, and record what you did and why.

## Hard constraints

Facts about this repository you cannot derive.

- `BEADS_ACTOR` identifies the machine; never fall back to `git config user.name`.
- **Acquisitions are bracketed `bd dolt pull` → verify → write → push**: claiming
  an epic, an item outside the epic you hold, or a child of a closed epic; any
  write under the SDLC epic; creating an id other sessions reference. A rejected
  push → pull, re-verify the target is still yours, push once more; still
  rejected → stop and report. Writes inside what you hold stay local until step
  6. Push with `publish-beads.sh`, never bare `bd dolt push`.
- Claim with `claim-epic.sh` / `claim-item.sh`, never `bd update --claim` or
  `bd ready --claim`: they stamp `metadata.holder`. Before claiming anything that
  already has a holder, `session-holder.sh --check` it — LIVE or FOREIGN is
  someone else's.
- Never `bd create --parent=<shared parent>`; use `create-ticket.sh`. Bodies
  containing code go through files ([traps.md](references/traps.md), "bd").
- **One worktree, one live agent**, until its completion notification arrives.
  Meanwhile its bead's title, description and acceptance stay unchanged; comments are fine.
- Never read a running agent's output file (`TaskOutput`, `Read`, `tail`) — it
  is the whole transcript.
- **A ready PR merges itself on this repo.** Only you run `gh pr ready`, only on
  a reviewer's READY for that head (or that head plus a merge of `main` you
  checked per 5e), followed by `gh pr merge <n> --auto --squash`.
- Never ship on a red required check, or a green that did not run this diff's
  tests ([evidence.md](references/evidence.md), "CI evidence").
- Never delete local branches (squash merges make unmerged-looking branches
  normal), and never push task branches. Remove worktrees only in step 6.
- Agents read skill files from their own worktree, cut from `origin/main`; an
  agent without one uses `git -C <main-checkout> show origin/main:<path>`.

## Scripts

In `.claude/skills/work/scripts/`, run from the main checkout. Each header
documents outputs and exit codes; an exit meaning "nothing was checked"
(usually 3) is never an answer.

| `.claude/skills/work/scripts/…` | `<args>` — does |
|---|---|
| `sweep-stale-claims.sh` | `[--hours N] [--dry-run]` — reopens this machine's task claims abandoned by a dead run |
| `sweep-merged-prs.sh` | `[--dry-run] [--limit N]` — closes beads whose PR merged after their session; removes their worktrees (holder-blind) |
| `reclaim-worktrees.sh` | `[--dry-run] [--min-age-minutes N]` — removes worktrees of closed beads, when provably safe |
| `session-holder.sh` | `[--check <token> [<updated-at>]]` — this session's holder token; `--check` → MINE/LIVE/DEAD/STALE/UNKNOWN/FOREIGN (a write within 15min reads LIVE, not STALE) |
| `resumable-epics.sh` | `(no arguments)` — epics holding a feature left `in_progress` |
| `undefer-unblocked.sh` | `[--dry-run]` — reopens deferred epics whose `undefers:<epic>` blockers all closed |
| `close-bead.sh` | `<bead-id> [bd-close-args...]` — closes a bead, then immediately runs the undefer sweep so a blocked epic reopens at close time, not at the next session's startup check |
| `claim-epic.sh` | `<epic-id>` — claims or takes over an epic and pushes the acquisition; `--release <epic-id>` reopens a dead run's epic unless a live session works beneath it |
| `claim-item.sh` | `<id>` — claims an item with the session holder token |
| `ready-in-epic.sh` | `<epic-id> [--ids-only]` — ready work at any depth beneath an epic |
| `epic-of.sh` | `<bead-id>` — a bead's effective epic, or `(unparented)` |
| `feature-branch.sh` | `<feature-id>` — the feature's branch and worktree, minting `-rN` after a squash-merged PR |
| `ensure-worktree.sh` | `<path> <branch> [base-ref]` — attaches a worktree, new or resumed, or fails loudly |
| `verify-branch-sync.sh` | `<worktree> <branch>` — does the worktree contain origin's branch |
| `next-batch.py` | `<feature-id> [--actor NAME] [--siblings N]`, or `--capacity` alone — tasks safe to run together, within file claims and machine capacity |
| `check-files-claim.sh` | `<bead-id>...` — warns when a bead's text names a file its claim omits |
| `acceptance-placement.sh` | `<bead-id>...` — MISPLACED (criteria in the description) vs ABSENT (a guess) |
| `propagate-correction.py` | `<epic-id> [--exclude <bead-id>]... <needle>...` — open beads still repeating a claim proven wrong |
| `merge-task.sh` | `[--dry-run] [--keep-open] <task-id> <feature-branch>` — gated merge of a passed task, durability proof, close |
| `wait-checks.sh` | `<pr-url> [max-rounds]` — waits on the head's checks; SETTLED / UNBOUND / TIMEOUT-PENDING / NO-RUN / QUERY-FAILED |
| `bead.sh` | `[-C <dir>] <id> [-r] [jq-filter]` — a bead's own fields; exit 3 = spilled to the file named on stderr |
| `junit-count.py` | `[--expect-classes N] <results-dir \| result-file.xml>...` — JUnit XML counts and freshness |
| `ci-executed.sh` | `<pr-url\|run-id> <check-name> <:task>...` — per task in a CI job: EXECUTED / FROM-CACHE / …; exit 0 only when all EXECUTED |
| `twin-scan.py` | `<parent-id>` — children filed twice by a double breakdown |
| `create-ticket.sh` | `--type <bug\|feature\|task\|chore> --title "<one line>" (--parent <id> \| --top-level) [--desc-file F] [--accept-file F] [--priority N] [--label L]... [--metadata '<json>'] [--model M] [--breakdown T] [--claim] [--no-dup-check]` — the create path under a shared parent |
| `breakdown-marker.sh` | `<subcommand> <epic-id>` — check, acquire (pull+push), or survivor (adjudicate) the epic's write-time breakdown marker |
| `file-retro.sh` | `--skill S --file F [--skill-version <sha>] [--started T] [--model M]` — files the session's retro record (references/retro.md) |
| `file-friction.sh` | `--type bug\|feature --title T --desc D\|--desc-file F --accept A\|--accept-file F [--parent computenet-wpvy] [--priority N] [--skill-version <sha>]` — files a friction item |
| `publish-beads.sh` | `(no arguments)` — the publication push, with rejection recovery |

Also: `slot-elapsed.sh <scratch-dir>`, `verify-ready.sh <id>...`,
`have-tool.sh <tool>`, `park-thread.sh <id>`. Capacity reads below are
`next-batch.py --capacity --siblings <N>`.

## References

| Reference | Read by, when |
|---|---|
| [agent.md](references/agent.md) | every dispatched agent, first |
| [traps.md](references/traps.md) | everyone: `bd`, git, `gh` and shell behaviours that return wrong answers |
| [breakdown.md](references/breakdown.md) | breakdown agents |
| [implement.md](references/implement.md) | implementers |
| [review.md](references/review.md) | task and feature reviewers, second readers |
| [evidence.md](references/evidence.md) | implementers and reviewers: tests ran, mutation checks, CI evidence |
| [ci-evidence.md](references/ci-evidence.md) | implementers and reviewers: CI execution proof |
| [dispatch.md](references/dispatch.md) | you, step 5b: batch selection, capacity, claims and dispatch |
| [recovery.md](references/recovery.md) | you: resume, stalls, red checks, Dolt conflicts, parks, collisions |
| [pre-dispatch.md](references/pre-dispatch.md) | you, step 5b: making each bead true before dispatch |
| [friction.md](references/friction.md) | you, step 7: searching, commenting and filing friction |

## Models

Implementation runs on Codex, and every review runs on the other provider at
least as strong as the author. You are `sonnet`, set by the routine that
starts you.

| Role | Model |
|---|---|
| Breakdown, epic and feature | `fable` |
| Implementer | the task's `metadata.model`: `luna` or `sol` (Codex, 5b); `sonnet` when Codex is unavailable. A legacy `opus` stamp → `sol` |
| Task reviewer | `luna`/`sol` task → `opus`; `sonnet` task → `sol` |
| Second reader | `sol` for an Opus reviewer's repairs, `opus` for a Sol reviewer's |
| Feature reviewer, epic-close gate | `opus` |

Codex is unavailable when `have-tool.sh codex` fails or a run exits on quota:
restamp the task `sonnet`, comment why, dispatch through `Agent`.

## 1. Identity

```bash
echo "${BEADS_ACTOR:?BEADS_ACTOR must be set, uniquely, per machine}"
mktemp -d "<harness scratchpad>/work.XXXXXX"
```

Unset actor → stop and report. Note the scratch directory's absolute path in
your output; a resume reads it by that path. Work from the main checkout `<M>`
(`cd <M>`): the scripts and `bd` run there, and other sessions share it.

```bash
git -C <M> fetch origin main --quiet
git -C <M> merge-base --is-ancestor origin/main HEAD && echo CURRENT || echo STALE
git -C <M> status --porcelain
git -C <M> rev-parse HEAD > <scratch>/step1-head
```

STALE → `git -C <M> merge --ff-only origin/main`; refused → stop and report.
Tracked modifications it does not refuse over (or with HEAD CURRENT) → leave
them untouched and list them in the summary.

## 2. Budget

```bash
echo 43200 > <scratch>/slot-seconds   # a 12h backstop, not a work budget
date -u +%s > <scratch>/slot-start
```

There is no work slot: a session ends at 5f's HANDOFF (context spent, epic
closed, or the context was compacted), and the scheduler starts a fresh one.
The 12h clock only catches a run that never reaches a boundary.
`slot-elapsed.sh <scratch>` reads it; run it first in any turn where you might
start work (dispatch, claim, route) and act on its rung. `claim-epic.sh` (given
`SCRATCH=<scratch>`) refuses to claim once the rung is EXPIRED. Never use your
sense of time or a notification's `duration_ms`; compute elapsed time that turn.

| Rung | Means |
|---|---|
| OPEN | new units allowed |
| T-90m | start no new unit; the current feature's tasks, reviews and breakdowns still dispatch |
| T-45m | dispatch only reviewers for finished work; merge and ship what is in flight |
| EXPIRED | step 6 now |

With agents live, arm the one-shot `Monitor` (`sleep <s>; echo wake`) the clock's
`wake:` line prints; `TaskStop` it in step 6. Settle once whether `SendMessage` exists
(tool list, then `ToolSearch "select:SendMessage"`); without it, continuing an agent is
a stop plus a fresh dispatch framed as a resume ([recovery.md](references/recovery.md),
"Stalled agents and load"). A `status=stopped` notification from the previous session
means the host died: read recovery.md, "Resuming after the host died", first.

Background jobs you start are supervised by nothing: bound each, record it in
`<scratch>/jobs`, stop them all in step 6. Wait on PR checks only with
`wait-checks.sh`; SETTLED means nothing is pending, not that anything passed.

## 3. Sync and claim one epic

`bd dolt pull` (Bash timeout ≥ 300000 ms). A failed pull stops the session, except the
conflicts [recovery.md](references/recovery.md) "Dolt pull conflicts" covers. If
`git hash-object .claude/skills/work/SKILL.md` differs from `git rev-parse
origin/main:.claude/skills/work/SKILL.md`, read the skill from `origin/main`.

**Release what dead runs left.** Run `sweep-stale-claims.sh`, then list `bd list
--status=in_progress --assignee="$BEADS_ACTOR" --limit 0 --json` to a file and
check each non-`skill-friction` row's `metadata.holder` with `session-holder.sh
--check <token> <updated_at>`:

| Answer | Do |
|---|---|
| MINE / LIVE | leave it |
| FOREIGN | another machine's run; leave it, report it |
| DEAD / STALE | an epic → `claim-epic.sh --release <id> --observed <holder> <answer>`; non-zero → leave it claimed, report the printed reason, don't select it. Anything else → leave (tasks are swept; an `in_progress` feature is a resume marker) |
| UNKNOWN / none | touched within 15 minutes → LIVE, else DEAD; say you fell back |

`<N>`, the sibling count used for capacity, is the number of distinct LIVE
holder tokens. Then run `reclaim-worktrees.sh` (SKIPs for LIVE holders are
expected), `sweep-merged-prs.sh` (`--dry-run` first when `<N>` > 0: it does not
check holders) and, after its real run, `undefer-unblocked.sh` — the FALLBACK
sweep, for closes that never went through `close-bead.sh`: a human typing `bd
close` by hand, or a close that happened on another machine and synced in via
Dolt; `close-bead.sh` itself already ran this sweep at close time for every
close this skill makes. Capture each to a file with its exit code and report
all three.

**Select the epic** from `bd ready --type=epic --json`: `resumable-epics.sh`
entries first unless their in-progress feature's holder is LIVE or FOREIGN, then
`bv --robot-triage` order if `bv` exists and its export is fresh (CLAUDE.md),
then priority. Skip children of an epic another session holds, epics labelled
`tracking-umbrella` (never claimed or broken down; their sub-epics stand alone)
and epics whose `needs:<tool>` label `have-tool.sh` fails on. `SCRATCH=<scratch>
claim-epic.sh <id>`: 0 claimed; 1 → act on the printed reason; 2 unpublished → stop, report.

**Check workable surface** with `ready-in-epic.sh <epic>`, resolving any row it
could not classify with `epic-of.sh`. Empty and nothing resumable:

- Every child closed (at least one) → 5g if any child is a feature; none is →
  step 4, breakdown. Keep the `owner:` label; `check-dotted-ids.sh` reads it.
- Children open, none ready → `verify-ready.sh` them (the blocked flag goes
  stale); any READY → work it. None → comment why, naming the blockers; label
  `undefers:<epic>` each blocker outside it (`epic-of.sh` ≠ `<epic>`, unparented
  included; for `[unmerged on <feature>]`, that feature) unless human-gated;
  `bd update <epic> --assignee="" --unset-metadata holder`; `bd defer <epic>`.

Closing or deferring does not spend your one claim; select again. No children →
step 4. Nothing claimable → report and stop. Lacking an epic's toolchain → label
it `needs:<tool>` and skip it, never a human park (that hides it from machines
that can run it). A sub-epic under your epic is covered by your claim: break it
down (step 4) unclaimed, and push a comment naming the session working it.

## 4. Ensure the epic has features

List `bd list --parent=<epic> --all --json` to a file. Break the epic down when
it has no children, or when its children consume the epic's deliverable rather
than make it up (say so in the prompt). `twin-scan.py <epic>` flags children
filed twice: one twin closed soon after creation with no comments → trust the
survivor; otherwise treat it as a collision ([recovery.md](references/recovery.md), "Collisions").

Before dispatching, run `breakdown-marker.sh acquire <epic>` (Bash timeout ≥ 300000 ms;
it pushes). Exit 0 → read capacity, bound the agent (5b) and dispatch below with
the printed `TOKEN`; 11 (FOREIGN) → already broken down elsewhere: list children
again and continue at step 5, or park per "Still no children" below if that
listing is empty; 12 (BOTH) → run `breakdown-marker.sh survivor <epic>` (exit 1:
losers listed, not a failure; 0: nothing to adjudicate), route its `CLOSE` list
per recovery.md "Collisions", then continue as for 11; 2 → unpublished
acquisition, stop and report; 3 from either → treat it as 2. `acquire` exit 0
on OWN with children present → dispatch the breakdown anyway; the agent creates
only the outcomes no existing feature covers.

```
Agent({
  description: "Break down epic <epic-id>",
  model: "fable",
  run_in_background: true,
  prompt: `You are breaking down epic <epic-id> into features. It is claimed for you; do not claim it.
You own no worktree: you work in <main-checkout>, SHARED with live sessions — never modify its working tree (no git checkout/restore/stash/clean).
Read .claude/skills/work/references/agent.md and .../breakdown.md from <main-checkout> with the Read tool, not cat or git show: under host load plain Bash reads hang 30-120s and the Read tool does not.
The breakdown token is <token>; stamp every feature you create with it.
Report the feature ids created, and any re-scope of the epic.`
})
```

For a sub-epic the claim sentence becomes: "It is a sub-epic under <parent-id>,
which this session holds; do not claim, assign, label or comment on it."
Wait for completion and list again. A re-scope → re-read the epic with `bead.sh`.
Still no feature children: a `needs:<tool>` label was added → select another epic; a
deliberate park (blocked, `human`, `QUESTION:` comment) → leave it parked and
select another; otherwise retry once, then park, log friction, go to 5f.

## 5. Work features

Record parks with `bd update <id> --set-metadata parked_at=$(date +%s)`; skip
items parked in the last 6 hours. Once per session, re-triage human parks under
the epic ([recovery.md](references/recovery.md), "Parks").

**Select:** an `in_progress` feature under the epic first, else the first row of
`ready-in-epic.sh <epic>` — a feature → 5a; a sub-epic → step 4; another type →
"Direct children". A resumed feature with `metadata.review=passed` whose PR
merged → `close-bead.sh <feature-id>`.

A feature is the unit of integration: one worktree, branch and draft PR, into
which reviewed task branches merge. Integrate one feature at a time; a capacity
lane that frees meanwhile may take one disjoint unit (5f, route 0). A worktree
this session did not create is only yours if its bead's holder is DEAD, STALE or
absent; when unsure, treat it as occupied.

### 5a. Set up or resume the feature

If the feature has a holder, check it; LIVE or FOREIGN → select something else.
Run `claim-item.sh <feature-id>`, then `feature-branch.sh <feature-id>`, and use
the branch and worktree it prints for everything below:

```bash
.claude/skills/work/scripts/ensure-worktree.sh <worktree> <branch> origin/main
.claude/skills/work/scripts/verify-branch-sync.sh <worktree> <branch>
```

With `metadata.base_branch` set, base on `origin/<that branch>` and target the
PR at it — after `verify-ready.sh`'s `STALE-BASE`/`LIVE-BASE` note: the field is
a snapshot, usually stale within minutes, and a merged branch's ref survives on
origin, so trusting it silently cuts from spent code. `STALE-BASE` → clear the
field, cut from `origin/main`, say so on the bead. Verdicts: `OK-*` → proceed; `SQUASH-LEFTOVER` → use a new branch name
recorded in `metadata.branch`, or delete the dead remote ref, and say which;
`STOP-UNMERGED` → stop; `STOP-UNREACHABLE` → nothing was checked.

Any inherited worktree — feature or task — holding a `.mutation-in-progress`
file is an interrupted mutation check: `git -C <worktree> checkout -- .`, delete
the file, say so. A dirty worktree without it: read the diff; coherent → keep it
and report it; unclear → leave it and park.

Bring the branch current and push it even with no commits (`merge-task.sh`
needs the ref on origin). A conflict you resolve here is reviewed by the feature
review; name the merge sha in its prompt.

```bash
git -C <worktree> merge origin/main -m "Merge main into <branch>"
git -C <worktree> push -u origin <branch>
```

### 5b. Batch and dispatch tasks

Read [dispatch.md](references/dispatch.md) in full before dispatching a batch. Follow it for selection, capacity, claims, agent prompts, and completion; then continue at 5c.

### 5c. Review and merge each task

One reviewer per completed task, per "Models", never its author; they count
against capacity and are bounded (5b). Stamp `reviewer_model=<model>` on the
task first — the retrospective compares pairings.

```
Agent({
  description: "Review task <task-id>",
  model: "<per Models>",
  run_in_background: true,
  prompt: `You are the task reviewer for beads task <task-id>.
Worktree <task-worktree>, branch task/<task-id>, feature branch <feature-branch> (on origin).
Read <task-worktree>/.claude/skills/work/references/agent.md, then <task-worktree>/.claude/skills/work/references/review.md.
Your verdict comment and review metadata on this task are yours to write. Cross-bead writes authorized: <cross_bead or "none">.
You may commit repairs on the task branch and temporarily mutate files its tests constrain (evidence.md).
Do not push, merge, rebase or switch branches.`
})
```

For a second reader, add "You are the second reader for commits <shas>; review
only those."

Act only on a stated PASS or FAIL, after running any `REQUIRED ORCHESTRATOR
ACTION`. Then:

- A PASS: read the repair commits it names. Any that change behaviour, a test,
  or a file the acceptance names get a second reader first, whatever the
  reviewer called them — except a test-only repair certified by its reviewer
  as `test-only repair <sha>: mutation <m>, red <assertion>, expected from
  <source>` (review.md), or a *conforming* one
  ([review.md](references/review.md#repair-dont-bounce)) with the governing
  rule quoted. Read that quote: if it decides the edit, the reviewer's own read
  is the second read. If it does not, dispatch. A second reader's `Reader's
  repairs:` line: read that diff yourself and ship on your own read; no
  further reader (review.md "If you are the second reader").
- A FAIL whose only blocker is its `Repairs needing a second reader:` line →
  second reader for those commits; merge on its PASS.
- Any other FAIL stays `in_progress` with its branch for the next batch.

Merge passes yourself, one at a time: `merge-task.sh --dry-run <task-id>
<feature-branch>`, read every gate line and the `--stat`, then run it for real.
Origin ahead or someone else's PR on the head → stop and park; picking a winner
discards somebody's work. Deletions that look like loss → park. Failed durability
proof → do not close; retry. A parked task with a commit worth keeping → `--keep-open`.

Task branches exist only on this machine, so merge passes this session. Comments
describing commits this machine lacks mean the work lives elsewhere; say so in
the next dispatch. The script regenerates a conflict confined to generated `doc/spec/CONCORDANCE.md`
with Gradle, so run it unsandboxed with a 600000 ms timeout. Any other conflict means claims
overlapped: resolve, fix both claims, and name the merge sha in the feature review's prompt. Then
back to 5b until `next-batch.py` routes to 5e. Once the PR exists, glance at `gh pr checks <pr>` on
each return; a red check on touched code becomes a task under the feature, carrying the log excerpt.

### 5d. Draft PR

After the feature's first merge, if `metadata.pr` is unset: `gh pr create --draft
--base <metadata.base_branch, else main> --head <branch> --title "<title>"
--body-file <file>`, check the body with `gh pr view <url> --json body`, then
`bd update <feature-id> --set-metadata pr=<url>`. It stays draft until 5e.

### 5e. Feature review and ship

All tasks closed is not the feature done: seams between tasks belong to nobody
until this review. Collect and paste, don't summarize:

```bash
git -C <feature-worktree> fetch origin main
git -C <feature-worktree> log --oneline $(git -C <feature-worktree> merge-base HEAD origin/main)..origin/main
gh pr list --state open --json number,headRefName,isDraft
```

Read capacity and bound the agent (5b), then:

```
Agent({
  description: "Review feature <feature-id>",
  model: "opus",
  run_in_background: true,
  prompt: `You are the feature reviewer for <feature-id>: worktree <feature-worktree>, branch <branch>, PR <pr-url>.
Read <feature-worktree>/.claude/skills/work/references/agent.md, then <feature-worktree>/.claude/skills/work/references/review.md.
origin/main at dispatch: <sha>. Landed on main since this branch forked: <log output, or "none">.
Open PRs that may merge meanwhile: <list>. Children left open as human parks (confirm each is one): <list or "none">.
Your verdict comment and review/residual metadata on this feature are yours to write. Cross-bead writes authorized: <cross_bead or "none">. <Merge shas you resolved; gate scope if an implementer is live.>
You may commit and push repairs to the feature branch. Never run gh pr ready.`
})
```

| Result | Do |
|---|---|
| no READY/DRAFT token | continue the agent until it states one |
| you `TaskStop`ped it | DRAFT; route on what it wrote to the bead |
| `REQUIRED ORCHESTRATOR ACTION` | run the commands; a merge of `main` goes through Ship step 1 |
| READY | read the repairs it names (second reader for any that change behaviour, a test or an acceptance-named file, unless certified conforming with the rule quoted — same test as 5c), then ship. A second reader's `Reader's repairs:` line: read that diff yourself and ship on your own read; no further reader |
| READY naming a pending out-of-band measurement or a re-run of a check attributed to a flake bead | ship once it reports, else leave for the next session |
| DRAFT whose only blocker is its `Repairs needing a second reader:` line | second reader for those commits; ship on its READY |
| DRAFT, tasks filed for gaps | 5b |
| DRAFT on a red required check | [recovery.md](references/recovery.md), "A red required check" |
| DRAFT, nothing actionable | `parked_at`, 5f |

**Ship**, after the reviewer's completion notification:

1. Merge `origin/main` only when a commit landed since the fork touches this
   PR's files and is not independent of it (a shared hunk, or a change to a rule,
   name or path the other relies on) — then push and send it back to a reviewer.
   Disjoint commits need no merge (read the ruleset — no required check wants an
   up-to-date branch — and chasing a busy `main` never ends): ship the green head.
2. Local HEAD must equal `gh pr view <pr> --json headRefOid`, and `gh pr list
   --head <branch>` must show only your PR.
3. `wait-checks.sh <pr-url>`, again after TIMEOUT-PENDING; every required row must
   pass. NO-RUN → empty commit, wait again. UNBOUND → not evidence (traps.md); re-run.
4. Confirm the checks ran this diff's tests ([evidence.md](references/evidence.md), "CI evidence").
5. `gh pr ready <pr>`, then `gh pr merge <pr> --auto --squash`. Ready PRs one at a
   time: a burst makes their merges race.

Every new head restarts the required checks; keep at most about two open PRs on
any one file, sequencing the rest. `close-bead.sh <feature-id>` once MERGED,
not on the verdict. Still open well after shipping: `DIRTY` → Ship step 1 again (`BEHIND` never blocks);
red → recovery.md; `CLEAN` → arm again, then push a fresh commit. Cannot land it
→ leave `in_progress` with `review=passed`, name the PR and blocked command in the summary.

### 5f. Next unit

Re-read your epic (`bead.sh <epic> -r '.assignee, .metadata.holder'`); not
yours → dispatch nothing new into it, top the summary with it, re-claim only if
no other holder holds it. **Then, before any route: are all the epic's children closed?** If so, run
5g now, inline — do not wait for step 6. A child count that merely went dry
(nothing ready) is not this; check closed, not ready. CLOSE → the epic is
genuinely done, not just out of scheduled stories: `close-bead.sh <epic>`, then
treat it as a HANDOFF (`next-batch.py --continuation --headroom-pct <N>
--epic-closed`, which returns HANDOFF regardless of headroom) straight to
step 6 — a verified close is a clean boundary, worth a fresh session's full
context budget on whatever comes next ([recovery.md](references/recovery.md),
"Continuation across ticket boundaries"). GAPS → new children were filed
under THIS epic for the part of the intent the stories missed; they are not
continuation work, they are route 1 — fall through to the table below, which
will find them. A park under `REQUIRED ORCHESTRATOR ACTION` → park the epic
(recovery.md "Parks"), then step 6.

Otherwise, take the first route that applies. After T-90m no route starts a
new unit; routes 2b, 3 and 4 may still dispatch a breakdown. Before routes
1, 2b, 3 or 4 actually dispatch, read headroom (`next-batch.py --continuation
--headroom-pct <N> --route <1|2b|3|4>`, which derives relatedness from the
route itself rather than asking you to judge it; add `--compacted` if your
context was ever compacted this session) and follow CONTINUE/HANDOFF;
ESCALATE (only reachable from route 1) → `--ask-jev`. HANDOFF → step 6
*before* routes 3/4's acquire step, not after — acquiring then handing off
leaves a stale claim for the next session. Routes 0 and 2 never read this (see
their own rows below).

| Route | Situation | Do |
|---|---|---|
| 0 | a capacity lane frees while a unit runs | start a second unit if capacity allows, its claim is disjoint from running units, build contention is handled (scoped gate or no Gradle), and it gets its own branch and PR; candidate from route 3 or 4. Else leave the lane idle and note it on the epic. A concurrency question, not a continuation one — does not read headroom |
| 2b | your feature is blocked by a sibling feature (check before 1) | park it naming the blocker; work the blocker if it fits the budget (5a), else break it down unclaimed: the step 4 template with its claim sentence replaced by "It is not claimed and stays unclaimed; check with bead.sh and do not claim it." |
| 1 | another feature under the epic is ready or in progress | 5a (sub-epic → step 4) |
| 2 | remaining work waits on a feature you just shipped | wait for its merge, until T-45m; `DIRTY` → resolve; merged → fetch, start; else park. No new unit dispatches — does not read headroom |
| 3 | remaining work is blocked only by an item in another epic | read headroom first (above); CONTINUE → acquire the item: pull; `epic-of.sh` — skip if its epic is held by someone or touched within 15 minutes (an `(unparented)` item skips this test); `claim-item.sh`; push |
| 4 | the epic is dry of READY work (children may remain, blocked elsewhere) and not closeable yet, budget remains | read headroom first (above); CONTINUE → continuation work, below |
| 5 | nothing can progress | step 6 |

**Continuation work:** `bd ready --json --exclude-type=retro` items with no epic ancestor (`epic-of.sh`
→ `(unparented)`) and features or tasks of other epics. Drop `human`-labelled,
SDLC, recently parked, claim-overlapping, and reviews of your own session's
output. Prefer dependents of what you finished and items touching your branches'
files. Admit one only if its blocker still holds against the artifact, its
compute demand fits (else scope it and set `metadata.compute=dedicated`), and
its 45–60 minute estimate fits the time left. Write acceptance onto a directly
filed item that lacks it, before dispatch. Acquire like route 3; work a
non-feature item as in "Direct children". An epic dry only because the rest is
human-gated or blocked elsewhere → defer it as step 3 does.

### 5g. Epic-close gate

Every child closed is not the epic done: a clause no feature owned was never
built. Dispatch at `opus`, from the main checkout, with the step 4 template's
worktree and Read-tool lines, and this prompt: "You are the epic-close gate for
<epic-id>; it is claimed for you, do not claim or close it. Judge against
origin/main at <sha>. Read agent.md, then review.md "Epic-close gate". Prior
gate: <metadata.epic_gate, or none>." CLOSE → `close-bead.sh <epic>`. GAPS → work
the children it filed (step 5). A park under `REQUIRED ORCHESTRATOR ACTION` →
park the epic per recovery.md "Parks". No token → continue the agent.

### Direct children (no feature layer)

When the epic's ready rows are bugs, tasks or chores, work each as its own unit:
5a with the item in place of the feature (its branch from `feature-branch.sh`,
based on `origin/main` or its `base_branch`), then the 5b implementer template
with that worktree and branch, "Diff your work against origin/<base>" and "this
item is worked flat". Open the draft PR at its first commit believed green; it
must exist before the reviewer. Review it with the feature reviewer (told there
is no task layer) and ship per 5e. Parallel subtasks, if it needs them, run
5b/5c against its branch. Then take the next ready row.

Bead text as the deliverable: no worktree, PR or CI. Dispatch an implementer
told "no worktree; the deliverable is the bead text of <ids>", then a task
reviewer on the result; close the items on its PASS.

### The SDLC exclusion

Never work an item whose effective epic is `computenet-wpvy` or that carries the
`skill-friction` label, or whose `metadata.files` lie under `.claude/skills/`, on
any route; re-parent such an item under `computenet-wpvy` (an SDLC write:
bracket it), comment why, and skip it; that lane is
`.claude/skills/remediate-friction/SKILL.md`. Filing friction is the only touch.

## 6. Finalize

Ending abnormally: release the epic if work remains (item 1), then
`publish-beads.sh`, then what time allows. Certified and green → ship.
Uncertified → leave in draft; push what is committed. Running agents → do not
wait: `TaskStop` each that `slot-elapsed.sh` flags OVER, commenting "hung past bound",
its worktree and last sha on its bead; the next session resumes the rest. Report
the main checkout's HEAD against `<scratch>/step1-head` if it moved.

1. **Epic:** closed by someone else, or already closed by 5f's own inline 5g
   run above → leave it. All children closed but 5g was not reached inline
   (EXPIRED or similar cut 5f short) → run it now if time allows, else release
   it for the next session's gate. Keep the `owner:` label. Work remains →
   `bd update <epic> --status=open --assignee="" --unset-metadata holder`.
2. **Utilisation:** `bd comment <epic> "utilisation: worked <N>m of <slot>m; continuation items: <ids or none>"`.
3. **Friction:** step 7. Then the **retro record**, always, even for an empty
   run: fill [retro.md](references/retro.md)'s template and
   `.claude/skills/work/scripts/file-retro.sh --skill work --file <scratch>/retro.md --skill-version <sha> --started <t> --model <id>`.
4. **Publish:** in each feature worktree you touched, `git status --short`
   (leftovers: report, do not commit) and push. Then `publish-beads.sh`; exit 2 →
   its ESCALATE line names a conflict (recovery.md) or a failure, and the
   summary's first line says tracker state is local-only. After a recovered push,
   confirm your writes survived (children, friction items, acquisitions, your
   epic's own row, `bd comments --json`); a vanished write tops the summary and gets parked, never
   re-applied blind.
5. **Worktrees:** remove those of merged tasks and closed features whose agents
   all reported and whose trees are clean.
6. **Merge check** (skip if EXPIRED): `gh pr view <pr> --json
   state,mergeStateStatus,statusCheckRollup` on PRs you shipped. MERGED →
   `close-bead.sh <feature-id>`, remove worktree. Red → attribute, one PR, briefly. Else name it. Publish again
   if anything closed.
7. **Stop** the monitor and every job in `<scratch>/jobs`. Summarize: epic and
   disposition, tasks done, draft PRs, parked questions, startup releases and
   sweeps, merge-check results, friction logged, skill revision(s), why you stopped.

## 7. Log friction

Nobody watched this run. Record process problems that cost real time or produced
a wrong result and that another session could plausibly hit, including your own
misreadings and agents' friction lines — per [friction.md](references/friction.md)
(search first, then comment or `file-friction.sh`). Step 6 pushes.
