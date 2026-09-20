---
name: work
description: Runs one unattended beads work session end to end — claims an epic, breaks it into features and tasks via Fable subagents, and implements each feature in its own worktree, branch, and draft PR, with its tasks running as parallel reviewed branches. Claims are crash-safe and machine-scoped, so two machines can run this concurrently on a schedule without colliding or deadlocking. Use this skill whenever a cron job, scheduled task, or routine kicks off a work slot, or the user says "/work", "work the queue", "pick up the next beads task", "start working through the backlog", "keep the machines busy", or otherwise wants autonomous progress on beads-tracked work — even if they don't mention beads, epics, or this skill by name.
---

# /work

One session claims one epic and works it until it is done or the slot ends.
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
| `session-holder.sh` | `[--check <token>]` — this session's holder token; `--check` → MINE/LIVE/DEAD/STALE/UNKNOWN/FOREIGN |
| `resumable-epics.sh` | `(no arguments)` — epics holding a feature left `in_progress` |
| `claim-epic.sh` | `<epic-id>` — claims or takes over an epic and pushes the acquisition |
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
| `twin-scan.py` | `<parent-id>` — children filed twice by a double breakdown |
| `create-ticket.sh` | `--type <bug\|feature\|task\|chore> --title "<one line>" (--parent <id> \| --top-level) [--desc-file F] [--accept-file F] [--priority N] [--label L]... [--metadata '<json>'] [--model M] [--breakdown T] [--claim]` — the create path under a shared parent |
| `breakdown-marker.sh` | `<subcommand> <epic-id>` — check, acquire (pull+push), or survivor (adjudicate) the epic's write-time breakdown marker |
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
| [recovery.md](references/recovery.md) | you: resume, stalls, red checks, Dolt conflicts, parks, collisions |

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

Tracked modifications → stop and report. STALE and clean → `git -C <M> merge
--ff-only origin/main`; refused → stop and report.

## 2. Budget

```bash
echo 18000 > <scratch>/slot-seconds   # the slot length allocated
date -u +%s > <scratch>/slot-start
```

`slot-elapsed.sh <scratch>` is the clock. Run it first in any turn where you
might start work — dispatch, claim, route — and act on its rung, never on your
sense of time or a notification's `duration_ms`. Never write an elapsed figure
you did not compute that turn.

| Rung | Means |
|---|---|
| OPEN | new units allowed |
| T-90m | start no new unit; the current feature's tasks, reviews and breakdowns still dispatch |
| T-45m | dispatch only reviewers for finished work; merge and ship what is in flight |
| EXPIRED | step 6 now |

A persistent `Monitor` echoing at those points is optional; if armed, `TaskStop`
it in step 6. Settle once now whether `SendMessage` exists (your tool list, then
`ToolSearch "select:SendMessage"`); without it, continuing an agent is a fresh
dispatch framed as a resume ([recovery.md](references/recovery.md), "Stalled
agents and load"). A `status=stopped` notification from the previous session
means the host died: read recovery.md, "Resuming after the host died", first.

Background jobs you start are supervised by nothing: bound each, record it in
`<scratch>/jobs`, stop them all in step 6. Wait on PR checks only with
`wait-checks.sh`; SETTLED means nothing is pending, not that anything passed.

## 3. Sync and claim one epic

```bash
bd dolt pull   # timeout >= 300s
```

A failed pull stops the session, except the conflicts [recovery.md](references/recovery.md)
"Dolt pull conflicts" covers. If `git hash-object .claude/skills/work/SKILL.md`
differs from `git rev-parse origin/main:.claude/skills/work/SKILL.md`, read the
skill from `origin/main`.

**Release what dead runs left.** Run `sweep-stale-claims.sh`, then list
`bd list --status=in_progress --assignee="$BEADS_ACTOR" --limit 0 --json` to a
file and check each non-`skill-friction` row's `metadata.holder` with
`session-holder.sh --check`:

| Answer | Do |
|---|---|
| MINE / LIVE | leave it |
| FOREIGN | another machine's run; leave it, report it |
| DEAD / STALE | an epic → `bd update <id> --status=open --assignee="" --unset-metadata holder`; anything else → leave (tasks are swept; an `in_progress` feature is a resume marker) |
| UNKNOWN / none | touched within 15 minutes → LIVE, else DEAD; say you fell back |

`<N>`, the sibling count used for capacity, is the number of distinct LIVE
holder tokens. Then run `reclaim-worktrees.sh` (its SKIPs for LIVE holders are
expected) and `sweep-merged-prs.sh` — with `--dry-run` first when `<N>` > 0,
since it does not check holders — each captured to a file with its exit code,
and report what they did.

**Select the epic** from `bd ready --type=epic --json`: `resumable-epics.sh`
entries first (in-flight work decays, and nothing else surfaces it) unless their
in-progress feature's holder is LIVE or FOREIGN, then `bv --robot-triage` order
if `bv` exists and its export is fresh (AGENTS.md), then priority. Skip the SDLC
epic, children of an epic another session holds, and epics with a `needs:<tool>`
label `have-tool.sh` fails on. Claim with `claim-epic.sh <id>`: exit 0 claimed;
exit 1 not claimed — read the reason (a body naming open blockers → next
candidate; all closed → add the missing edges and re-run with
`CLAIM_BLOCKERS_CHECKED=1`); exit 2 claimed but unpublished → stop and report.

**Check workable surface** with `ready-in-epic.sh <epic>`. A `could not resolve
the epic of <id>` line on stderr means that row was not classified: resolve it
with `epic-of.sh` first. Empty and nothing resumable:

- Every child closed (at least one) → `bd close <epic>`; on success,
  `bd update <epic> --remove-label=owner:$BEADS_ACTOR`.
- Children open, none ready → the blocked flag goes stale, so run
  `verify-ready.sh` on them. Any READY → work it. None → comment why, `bd defer <epic>`.

Closing or deferring does not spend your one claim; select again. No children →
step 4. Nothing claimable → report and stop. A machine missing an epic's
toolchain labels it `needs:<tool>` and skips it; never a human park, which
would hide it from machines that can run it. A sub-epic under your epic is
covered by your claim: break it down (step 4) without claiming it, and push a
comment saying which session is working it.

## 4. Ensure the epic has features

List `bd list --parent=<epic> --all --json` to a file. Break the epic down when
it has no children, or when its children consume the epic's deliverable rather
than make it up (say so in the prompt). `twin-scan.py <epic>` flags children
filed twice: one twin closed soon after creation with no comments → trust the
survivor; otherwise treat it as a collision ([recovery.md](references/recovery.md), "Collisions").

Before dispatching, run `breakdown-marker.sh acquire <epic>` (timeout >= 300s;
it pushes). Exit 0 → dispatch below with the printed `TOKEN`; 11 (FOREIGN) →
already broken down elsewhere: list children again and continue at step 5, or
park per "Still no children" below if that listing is empty; 12 (BOTH) → run
`breakdown-marker.sh survivor <epic>` and route its `CLOSE` list per
recovery.md "Collisions"; 2 → unpublished acquisition, stop and report; 3 is
never an answer — treat it as 2.

```
Agent({
  description: "Break down epic <epic-id>",
  model: "fable",
  run_in_background: true,
  prompt: `You are breaking down epic <epic-id> into features. It is claimed for you; do not claim it.
You have no worktree: work from <main-checkout>, read .claude/skills/work/references/agent.md and
.claude/skills/work/references/breakdown.md with git show origin/main:<path>.
The breakdown token is <token>; stamp every feature you create with it.
Report the feature ids created, and any re-scope of the epic.`
})
```

For a sub-epic the claim sentence becomes: "It is a sub-epic under <parent-id>,
which this session holds; do not claim, assign, label or comment on it."
Wait for completion and list again. A re-scope → re-read the epic with `bead.sh`.
Still no children: a `needs:<tool>` label was added → select another epic; a
deliberate park (blocked, `human`, `QUESTION:` comment) → leave it parked and
select another; otherwise retry once, then park, log friction, go to 5f.

## 5. Work features

Record parks with `bd update <id> --set-metadata parked_at=$(date +%s)`; skip
items parked in the last 6 hours. Once per session, re-triage human parks under
the epic ([recovery.md](references/recovery.md), "Parks").

**Select:** an `in_progress` feature under the epic first, else the first row of
`ready-in-epic.sh <epic>` — a feature → 5a; a sub-epic → step 4; another type →
"Direct children". A resumed feature with `metadata.review=passed` whose PR
merged → close it.

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

With `metadata.base_branch` set, base on `origin/<that branch>`; the PR targets
it too — but **validate the field before you use it**, from `verify-ready.sh`'s
`STALE-BASE`/`LIVE-BASE` note or by hand. It is a timestamped snapshot, not
standing metadata: a review-filed residual names the branch under review, which
is normally about to merge, so the field is routinely stale within minutes. A
merged branch's ref still exists on origin, so trusting it fails silently —
you get a plausible worktree cut from spent code — rather than loudly. `STALE-BASE`
→ clear the field, cut from `origin/main`, say so on the bead. Verdicts: `OK-*` → proceed; `SQUASH-LEFTOVER` → use a new branch name
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
`sonnet` and stamp it.

**Capacity.** Read `next-batch.py --capacity --siblings <N>` before every
dispatch, reviewers included, and follow its advice. At most one live agent runs
the repo-wide `./gradlew test`; the others scope their gate to touched modules.
Go under the cap when results depend on wall-clock waits. Agents dying with no
side effects → [recovery.md](references/recovery.md), "Stalled agents and load".

**Make each bead true before dispatch** — reviewers score its text and the
implementer builds on it literally:

- `acceptance-placement.sh`: MISPLACED → move criteria into the field; ABSENT →
  check the description, then write criteria to [breakdown.md](references/breakdown.md)'s
  standard and say so in the prompt.
- Test **every load-bearing claim the bead makes about state outside itself** —
  one the implementer or the review would act on or build against — for the cost of a grep or
  a file read, against the artifact that would show it, not a commit subject: a
  blocker, a precondition, a prescribed repro, a handoff instruction, a cited
  baseline or prior measurement, an assertion about what earlier work did or
  did not establish. That list is illustrative, and deliberately so —
  enumerating kinds is what let a superseded baseline and a false "prior work
  never tested this" through (computenet-d5y5); neither is a blocker, a
  precondition or a repro. Background prose nobody will act on is not
  load-bearing. Stale → correct the bead. Only checkable by doing the work, or
  dearer than a grep → mark it `unverified:` in the prompt
  ([breakdown.md](references/breakdown.md)); disproving it is a result.
- A cited record needs two answers, not one: does it still say what the bead
  says it says, **and is it still the current version of itself?** A superseded
  record usually sits exactly where it was with its original numbers intact,
  and is corrected by a LATER entry elsewhere in the record — so search the
  whole record for a later entry that corrects it, not only the lines cited.
  Where that record is a bead the correction is a later comment, and where it
  is a findings journal it is a later entry in the same file.
- A measurement also carries its host and configuration: figures from another
  machine, JVM or config are not a baseline for this one, and dispersion is the
  quantity most sensitive of all. Say so in the prompt as a limit on what the
  comparison can support, and where a same-host control arm is available
  authorize that instead — a control arm defeats confounds a single arm cannot
  even detect.
- Files claim: run `check-files-claim.sh`, then reason about what else must
  change ([breakdown.md](references/breakdown.md), "The files claim"); widen and
  comment why, then amend any acceptance clause the widening contradicts (old
  wording in a comment). An unexplained empty claim gets fixed now. Tasks whose
  acceptance reaches into another's claim go in separate batches.
- A disproved prediction, or an obligation a review added to a later task, is
  written on each affected unstarted bead as an `AMENDS <id>` comment before it
  is dispatched; `propagate-correction.py` finds the siblings repeating a claim.

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
  prompt: `Implement beads task <task-id>; it is claimed for you. Dispatched at <date -u +%s>.
Worktree <task-worktree>, branch task/<task-id>; base commit (cut from, not a diff baseline): <sha> <subject>.
Diff your work against git merge-base <feature-branch> HEAD.
Read <task-worktree>/.claude/skills/work/references/agent.md, then <task-worktree>/.claude/skills/work/references/implement.md.
Read the bead: .claude/skills/work/scripts/bead.sh -C <main-checkout> <task-id>; comments: bd -C <main-checkout> comments <task-id> --json.
Change only files in metadata.files. If the acceptance needs another, comment the file and clause on the bead at once and keep working inside the claim.
Tracker writes: <cross_bead, or "only this bead and items you create">.
Gate: <"the repo-wide ./gradlew test" | "scope to <modules>; the PR's required checks give repo-wide evidence">.
You may commit on your task branch; do not push, merge, rebase or switch branches.
<resume framing or hypotheses, if any>`
})
```

**While agents run**, read progress only from notifications, bead comments, and
`git log`/`status` in their worktrees. At each notification, read the comments of
every live implementer: a report that its claim is too narrow → widen it if the
file is outside every live claim (then tell the agent, or carry it in its
resume); otherwise leave that file to a later batch and say so. An implementer's
result must state DONE, PARTIAL or BLOCKED; anything else is not a report —
continue that agent before acting. **On batch completion:** fix `metadata.files`
for files touched outside the claim; a parked question is one task, not the
feature; DONE → 5c.

### 5c. Review and merge each task

One reviewer per completed task, at the task's model, never its author; they
count against capacity.

```
Agent({
  description: "Review task <task-id>",
  model: "<metadata.model>",
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
  reviewer called them — except a repair the reviewer certified as a
  *conforming* one ([review.md](references/review.md#repair-dont-bounce)) with
  the governing rule quoted. Read that quote: if it decides the edit, the
  reviewer's own read is the second read. If it does not, dispatch.
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
the next dispatch. A merge conflict means claims overlapped: resolve, fix both
claims, and name the merge sha in the feature review's prompt. Then return to
5b: `next-batch.py` again until its verdict routes to 5e. Once the PR exists,
glance at `gh pr checks <pr>` output on each return; a red check on touched code
becomes a task under the feature, carrying the log excerpt.

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

Read capacity, then:

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
| READY | read the repairs it names (second reader for any that change behaviour, a test or an acceptance-named file, unless certified conforming with the rule quoted — same test as 5c), then ship |
| READY naming a pending out-of-band measurement | ship once it reports, else leave for the next session |
| DRAFT whose only blocker is its `Repairs needing a second reader:` line | second reader for those commits; ship on its READY |
| DRAFT, tasks filed for gaps | 5b |
| DRAFT on a red required check | [recovery.md](references/recovery.md), "A red required check" |
| DRAFT, nothing actionable | `parked_at`, 5f |

**Ship**, after the reviewer's completion notification:

1. List commits landed on `main` since the fork. If any touch this PR's files
   and are not independent of it (a shared hunk, or a change to a rule, name or
   path the other relies on), send it back to a reviewer. Otherwise merge
   `origin/main` and push; the READY stands, and the checks on the new head
   (step 3) are the evidence.
2. Local HEAD must equal `gh pr view <pr> --json headRefOid`, and `gh pr list
   --head <branch>` must show only your PR.
3. `wait-checks.sh <pr-url>`, again after TIMEOUT-PENDING; every required row must
   pass. NO-RUN → empty commit, wait again. UNBOUND → the rows name no commit, so
   they are not evidence for this diff (traps.md); re-run.
4. Confirm the checks ran this diff's tests ([evidence.md](references/evidence.md), "CI evidence").
5. `gh pr ready <pr>`, then `gh pr merge <pr> --auto --squash`. Ready PRs one at a
   time: a burst makes their merges race.

Every new head restarts the required checks; keep at most about two open PRs on
any one file, sequencing the rest. Close the feature once MERGED, not on the
verdict. Still open well after shipping: `DIRTY`/`BEHIND` → Ship step 1 again;
red → recovery.md; `CLEAN` → arm again, then push a fresh commit. Cannot land it
→ leave `in_progress` with `review=passed`, name the PR and blocked command in the summary.

### 5f. Next unit

Take the first route that applies. After T-90m no route starts a new unit;
routes 2b, 3 and 4 may still dispatch a breakdown.

| Route | Situation | Do |
|---|---|---|
| 0 | a capacity lane frees while a unit runs | start a second unit if capacity allows, its claim is disjoint from running units, build contention is handled (scoped gate or no Gradle), and it gets its own branch and PR; candidate from route 3 or 4. Else leave the lane idle and note it on the epic |
| 2b | your feature is blocked by a sibling feature (check before 1) | park it naming the blocker; work the blocker if it fits the budget (5a), else break it down unclaimed |
| 1 | another feature under the epic is ready or in progress | 5a (sub-epic → step 4) |
| 2 | remaining work waits on a feature you just shipped | wait for its merge, until T-45m; `DIRTY`/`BEHIND` → resolve; merged → fetch, start; else park |
| 3 | remaining work is blocked only by an item in another epic | acquire the item: pull; `epic-of.sh` — skip if its epic is held by someone or touched within 15 minutes (an `(unparented)` item skips this test); `claim-item.sh`; push |
| 4 | the epic is dry, budget remains | continuation work, below |
| 5 | nothing can progress | step 6 |

**Continuation work:** `bd ready --json` items with no epic ancestor (`epic-of.sh`
→ `(unparented)`) and features or tasks of other epics. Drop `human`-labelled,
SDLC, recently parked, claim-overlapping, and reviews of your own session's
output. Prefer dependents of what you finished and items touching your branches'
files. Admit one only if its blocker still holds against the artifact, its
compute demand fits (else scope it and set `metadata.compute=dedicated`), and
its 45–60 minute estimate fits the time left. Write acceptance onto a directly
filed item that lacks it, before dispatch. Acquire like route 3; work a
non-feature item as in "Direct children". An epic dry only because the rest is
human-gated or blocked elsewhere → `bd defer` it.

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
`skill-friction` label, on any route; that lane is
`.claude/skills/remediate-friction/SKILL.md`. Filing friction is the only touch.

## 6. Finalize

Ending abnormally: release the epic if work remains (item 1), then
`publish-beads.sh`, then what time allows. Certified and green → ship.
Uncertified → leave in draft; push what is committed. Running agents → do not
wait; the next session resumes them. Report the main checkout's HEAD against
`<scratch>/step1-head` if it moved.

1. **Epic:** closed by someone else → remove only your `owner:` label. All
   children closed (at least one) → close it, remove the label. Work remains →
   `bd update <epic> --status=open --assignee="" --unset-metadata holder`.
2. **Utilisation:** `bd comment <epic> "utilisation: worked <N>m of <slot>m; continuation items: <ids or none>"`.
3. **Friction:** step 7.
4. **Publish:** in each feature worktree you touched, `git status --short`
   (leftovers: report, do not commit) and push. Then `publish-beads.sh`; exit 2 →
   its ESCALATE line names a conflict (recovery.md) or a failure, and the
   summary's first line says tracker state is local-only. After a recovered push,
   confirm your writes survived (children, friction items, acquisitions,
   `bd comments --json`); a vanished write tops the summary and gets parked, never
   re-applied blind.
5. **Worktrees:** remove those of merged tasks and closed features whose agents
   all reported and whose trees are clean.
6. **Merge check** (skip if EXPIRED): `gh pr view <pr> --json
   state,mergeStateStatus,statusCheckRollup` on PRs you shipped. MERGED → close,
   remove worktree. Red → attribute, one PR, briefly. Else name it. Publish again
   if anything closed.
7. **Stop** the monitor and every job in `<scratch>/jobs`. Summarize: epic and
   disposition, tasks done, draft PRs, parked questions, startup releases and
   sweeps, merge-check results, friction logged, skill revision(s), why you stopped.

## 7. Log friction

Nobody watched this run. Record process problems that cost real time or produced
a wrong result and that another session could plausibly hit: a step that
misled, a command that failed as written, a gap where you had to guess —
including your own misreadings, and agents' friction lines. Not one-off
hiccups you handled, not preferences.

The SDLC epic is shared, so pull first. Search one distinctive word at a time
(`bd search` matches title substrings only): `bd search "<word>" --status all --json`.

- **Open match** → comment your instance (what you did, what happened, what it
  cost) with `bd comment <id> --file <file>`. If labelled `needs-evidence`,
  answer its last comment and `bd update <id> --remove-label=needs-evidence`.
- **Closed match** → file anew, citing it.
- **No match** → write description (what the skill says, what happened, what it
  cost) and acceptance (what would prevent it) to files, then:

```bash
.claude/skills/work/scripts/file-friction.sh --type <bug|feature> --title "<one line>" --desc-file <desc> --accept-file <accept> --skill-version <the epic's metadata.skill_version>
```

`bd comment` refused → `bd update <id> --append-notes "<plain text>"` (never
`--notes`, which overwrites) and name the refused command in the summary. Step 6
pushes.
