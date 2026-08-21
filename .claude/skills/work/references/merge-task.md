# 5c — review each task, then merge it

What the orchestrator does with a task an implementer has returned: dispatch
its reviewer, act on the verdict, and merge the pass into the feature branch.
SKILL.md 5b sends you here once a batch completes; you return to 5b for the
next batch.

The reviewer itself reads [review-task.md](review-task.md); this file is the
orchestrator's half.

## Contents

1. [Dispatch the reviewer, and notice when it goes quiet](#1-dispatch-the-reviewer-and-notice-when-it-goes-quiet)
2. [Act only on a stated verdict](#2-act-only-on-a-stated-verdict)
3. [Merge the pass yourself, one at a time](#3-merge-the-pass-yourself-one-at-a-time)
4. [Read the integrated result](#4-read-the-integrated-result)

One task goes through all four; you return to SKILL.md 5b for the next batch.

One reviewer per completed task, concurrently, at the task's own model —
never the agent that wrote it. **Reviewers count against the same
`capacity.max_parallel` as implementers** — a reviewer drives Gradle exactly
as an implementer does, and the cap was measured on mixed lanes
(computenet-avs). Count every dispatched agent still running; when the cap is
full, hold the reviewer and dispatch as a lane frees (you merge passes one at
a time anyway).

`${featureBranchOnOrigin}` below is one line, and it saves the reviewer a
failed command: either `The feature branch ${featureBranch} IS on origin.` or
`The feature branch ${featureBranch} is NOT on origin yet — this is the first
task under it, so resolve your baseline from the LOCAL feature branch as your
reference's §1 does; the task branch's base commit is ${taskBase}.` You know which (5d has
not run yet for the first task); the reviewer would otherwise discover it by a
fetch that fails (computenet-e3my). `${featureBranch}` is the feature's
recorded `metadata.branch` and `${taskBase}` the base commit you gave 5b —
the same two values, not new ones to compute.

```
Agent({
  description: "Review task <id>",
  model: <task's metadata.model>,
  run_in_background: true,
  prompt: `Read .claude/skills/work/references/review-task.md (from
${taskWorktree}, not the main checkout) and follow it to review beads task
${id} against its own acceptance criteria.
Worktree: ${taskWorktree}  ·  Branch: ${taskBranch}
${featureBranchOnOrigin}
Cross-bead writes authorized on this item: ${crossBeadWrites or "none"}.
That is the same line the implementer was given: treat what it names as
commissioned work rather than scope creep, and anything beyond it as
unauthorized.
Run every verification command — Gradle above all — in ONE foreground Bash
call with an explicit timeout, up to 600000 ms. If you already know the suite
outruns that 10-minute cap, COMMIT FIRST (do not `git push`, and do not
`bd dolt push` either — the orchestrator serializes syncs; see your
reference),
then background it and wait
with a BOUNDED until-loop on its log (your reference gives the form) — never
wait first, or a stop strands uncommitted work that reads as nothing.
The Bash tool auto-backgrounds anything that outruns its 120s default, and a turn that ends waiting on a
background job never resumes: your turn ending IS your completion, so there is
nothing to come back to. Never end a turn saying you will wait for a job.
Committing your repairs on the task branch is EXPECTED AND AUTHORIZED — this
sentence is the explicit grant AGENTS.md's conservative profile and
multi-agent clause both defer to. Still do
not push — not even the task branch (your reference says why) — and do not
merge, rebase, or switch branches.
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

## 1. Dispatch the reviewer, and notice when it goes quiet

The dispatch, the ~60-minute check, and the escalation when nothing comes back.

**A reviewer that neither reports nor stops is invisible unless you look.**
The completion notification never arrives, so nothing wakes you — the review
is unbounded and the PR sits (computenet-sjwd). **At ~60 minutes after
dispatch, check the bead rather than waiting.**

**You cannot feel 60 minutes pass** — between notifications you have no sense
of elapsed time at all (step 2), and the budget monitor is the *only*
persistent monitor, so this deadline does not get one. Give it a written
timestamp instead, at dispatch, and read it at every decision point you reach
while the reviewer is out (a merge, a dispatch, a poll round):

```bash
date -u +%s > "$SCRATCH/review-dispatched-<id>"      # at dispatch
# later, at any decision point — no clock needed, just subtraction:
echo $(( ($(date -u +%s) - $(cat "$SCRATCH/review-dispatched-<id>")) / 60 ))m
```

`$SCRATCH` is a shell variable that does not survive between Bash calls —
spell the directory's absolute path out, as step 2 does. Past ~60, check the
bead, using signals that cost nothing and are not the agent's transcript
(a context hazard, above):

```bash
bd show <id> --json | sed -n '/^[[{]/,$p' | jq -r '.[0]|"\(.status) \(.metadata.review // "-") \(.comment_count)"'
git -C <task-worktree> log --oneline -3
```

If nothing else will bring you back — every other lane is idle and you would
otherwise sit waiting — the bounded stall-watch Monitor in
[long-jobs.md](long-jobs.md) is the shape to use (`persistent: false`, one
`sleep 3600`, one `echo`), and it is then **the** bounded watch: `TaskStop`
it before arming a PR-checks loop.

**A bead untouched since the implementer's own comment is the signal** that
the review has produced nothing durable. Then, in order — do not kill first:

1. **`SendMessage` the agent**: stop verifying, state a verdict now plus a
   `NOT VERIFIED` section for what you did not reach. Give it a short window.
   This keeps its context, which `TaskStop` discards.
2. **No answer → `TaskStop`**, which 5e already defines as a **draft** verdict.
   Route on whatever it wrote to the bead.
3. **Then choose** between a fresh reviewer and leaving the PR draft, on
   remaining budget — not on principle.

**When the `SendMessage` in step 1 is itself what fails, stop resending.** A
resumed agent replays its whole prior transcript, and a reviewer whose first
pass was substantial can exceed what the stream watchdog tolerates. Measured
2026-08-19: a scoped re-check of one criterion failed twice with
`Agent stalled: no progress for 600s (stream watchdog did not recover)` —
the second attempt with a deliberately much shorter message, which changed
nothing, consistent with the cost being in the REPLAY rather than in the new
message. Both stalls wrote **nothing**: no comment, no verdict, no metadata.
Two dead 10-minute waits, and the ladder had no rung for the resume itself
failing (computenet-s7mq).

So: **one failed resume is the signal to dispatch a FRESH reviewer**, scoped
to the open criterion and carrying the prior verdict's findings **in the
prompt** rather than relying on the agent's memory of them. That route is not
free — it re-reads the diff and may re-run checks the first pass already did —
so budget it as a fresh review, not as a continuation.

**And clear the stale marker.** A task whose re-review never lands must not be
left with `metadata.review=failed` from the superseded pass: it is stale with
respect to the code and reads as a verdict on the *current* branch. Set it to
a value that says so (`review=stale:<why>`) or unset it
(`bd update <id> --unset-metadata review`), and say on the bead what the
marker does and does not mean. A marker that contradicts the branch is worse
than no marker.

**A re-dispatched reviewer needs framing the first one didn't**, or it repeats
the open-ended verification that stalled: tell it **it is the second
reviewer**, that there is **no prior verdict or partial state to reconcile**,
what blocker you already cleared for it, and that **a stated verdict on
honestly-scoped evidence outranks exhaustive coverage**.

**Clear the known blocker BEFORE dispatching, not after.** A `git merge` is
refused to reviewer agents by the classifier ([review-feature.md](review-feature.md)
§6 records the denial) — predictable, not bad luck. If
`origin/main` has moved and the reviewer will need the merge, **do the merge,
re-run the affected module suite, and push first**, then tell the reviewer it
is done. That converts a guaranteed mid-review wall into a precondition
(computenet-whx4, computenet-sjwd).

## 2. Act only on a stated verdict

Including the pass that carries a substantive repair, which is not final.

**Find an actual verdict in the result before acting on it.** The completion
notification looks identical whether the reviewer finished or stopped itself
mid-review (one returned "Waiting on Arm A…" as its entire result). No
pass/fail stated → `SendMessage` the same agent (context intact) to finish
and state a verdict plus a NOT VERIFIED section. Agent-completed is not
task-reviewed; a result skimmed as done here merges unreviewed code.

**A pass carrying a SUBSTANTIVE repair is not final** (computenet-r197). If
the task reviewer names repair shas and withholds `metadata.review=passed`, it
has told you it authored part of the deliverable — dispatch a second reader
scoped to *those shas only* before merging, rather than treating the pass as
final. The dispatch template is
[ship-feature.md](ship-feature.md) §4, written for a feature
reviewer's substantive repair; read task for feature and the task worktree
and branch for the feature's. A prose or design-record deliverable is
substantive by default, because there rewriting the text is rewriting the
thing under review.

## 3. Merge the pass yourself, one at a time

Re-verify the feature branch first; the merge is yours, never the reviewer's.

**Before any merge: search the reviewer's report for `REQUIRED ORCHESTRATOR
CORRECTION` and act on it.** A reviewer verifying a `cross_bead` deliverable —
a result its task was commissioned to write onto ANOTHER bead — can find that
deliverable defective and has no route to fix it, because a reviewer's writes
do not reach another bead (review-task.md). It reports under that literal
heading; you run the correction. Left undone, a wrong number sits permanently
in a feature-level deliverable the feature cannot close without, and the only
thing that ever caught one was how prominently a reviewer happened to mention
it (computenet-59f5). Correct it, then merge.

**Merge the passes yourself, one at a time** — reviewers must not merge
(concurrent merges into one feature branch race).

**The task branch is LOCAL by design; the feature branch is what must be
durable.** A dispatched implementer's `git push -u origin <task-branch>` is
denied by the permission classifier, so task.md now has implementers commit and
*not* push (computenet-zmso). Nothing needs the remote task branch: its
reviewer works in that worktree, and the merge below reads the local ref, which
worktrees of one repository share — including the reviewer's `review:` repair
commits, which is why merging the *local* ref and not `origin/task/<id>` is
mandatory, not merely convenient (a fetched merge would have silently dropped a
certified repair; observed 2026-08-17 on computenet-7em.2.3).

**That is true within ONE MACHINE and false across two.** Worktrees of one
repository share refs; two machines do not, and no task branch is ever pushed.
So a feature RESUMED on the other machine has **no access to prior
task-branch commits** — the work is reachable only from the machine that wrote
it. Measured on a resumed feature (computenet-2qen): one task was `in_progress`
with five comments describing a complete implementation and an explicit "do NOT
re-implement — resume this branch", and neither the branch nor the commits
existed on the machine that read it; another was plain `open` with no recorded
branch while its comments described three commits, all invalid here. Both were
re-implemented, ~40 and ~25 minutes, plus the orchestrator's own time
establishing the state twice.

**Three things conspire to hide it**, which is why it needs stating rather than
noticing: `next-batch.py` reports `resumed: false` / `branch_has_commits: false`,
literally correct and reading as "never started"; `metadata.branch` survives the
handoff pointing at a ref that does not exist here (`feature-branch.sh`
recomputes the *worktree*, so that half is handled and the branch half is not);
and the bead comments were written by an agent that HAD the commits, so they
describe them in the present tense.

**Discriminate before dispatching, in one command.** A task that is
`in_progress`, or whose comments describe commits, while

```bash
git -C <worktree> rev-parse --verify task/<id>
```

fails locally, can only be "started on another machine" — never "never
started". Then say so in the dispatch prompt: name the machine the branch lives
on, or state plainly that the prior work is unreachable and this is a
re-implementation. A session that inherits this state should not have to
discover it by running `ls-remote` on a hunch.

Two consequences you own, because nothing else can:

- **A PARK that carries a commit worth keeping is a third state, and it has a
  route now.** The two states this file models are pass-and-merge-and-close and
  fail-and-keep-`in_progress`. A task whose honest outcome is an ask-human park
  — exactly as its own acceptance specified — may still leave durable,
  CI-green evidence: one left 402 lines pinning the measurement its park rests
  on, including a tripwire that reddens the moment the parked question becomes
  answerable. `merge-task.sh` closed the bead unconditionally, so the only
  routes were lose the work or destroy the park, and the session lost the work
  (computenet-wdhu). Hand-merging is not the answer either: a parked task's
  code is uncertified by construction, so that is landing unreviewed code under
  your own authorship. Use the flag:

  ```bash
  .claude/skills/work/scripts/merge-task.sh --keep-open <task-id> <feature-branch>
  ```

  It runs every gate and the durability check, merges and pushes, and leaves
  `status` and `assignee` untouched — the park survives and the commit does
  not die with this machine. Not for a task that merely failed review: that
  stays `in_progress` with its branch unmerged, which is the case above.

- **The durability check.** After the push, confirm the merge is actually on
  origin **before** you close the task — a close is what tells every later
  session the work landed. `STOP` → do not close, and do not remove the
  worktree. That line prints `STOP` for an unreachable origin too; that is the
  safe direction here (unlike 5a's block, where an unreachable origin printing
  `OK` was the bug), so treat it as "not proven durable", diagnose, retry.
- **An unmerged task branch dies with this machine.** It is not on origin, so a
  *later session on the other machine* resuming that task finds neither the
  local branch nor `origin/task/<id>`, and `ensure-worktree.sh` takes its
  create-from-base path: a fresh empty branch, narrated on stderr as
  "creating branch … (exists neither locally nor on origin)". Nothing is
  corrupted and nothing is silently wrong, but the commits are gone and the
  task is redone from scratch. So: **merge each pass in this session**, and if
  you must end with a task reviewed-but-unmerged, say so in the summary with
  the machine name — that branch is only resumable here.

The gate sequence, the merge, the durability proof and the close are one
script — run it `--dry-run` first, read every gate line and the incoming
two-dot `--stat` it prints (the deletion signature below is a *pre-merge*
read: once the real run merges and pushes, parking is no longer on offer),
then run it for real:

```bash
.claude/skills/work/scripts/merge-task.sh --dry-run <task-id> <feature-branch>
# gates green and the --stat reads clean → the same command without --dry-run
```

Its gates, in order (each prints `GATE <name>: PASS` or aborts before any
mutation): the feature worktree is still on the recorded branch
(computenet-wpvy.29 — 5a set it up potentially an hour and several merges
ago, and nothing else re-reads it); the local ref contains
`origin/<feature-branch>` (three-way classifier — ahead / absent / origin
unreachable are three different findings, computenet-dtl); no open PR on the
feature head that is not yours; then the **two-dot** `--stat` of what will
merge. After the gates it merges `--no-ff`, pushes, proves the merge is on
origin, and only then runs `bd close <task-id>`.

How to read its verdicts:

- **Origin AHEAD, or a competing PR** → **STOP and park** (`ask-human.md`):
  both sides may hold pushed, unreviewed work, and picking a winner discards
  somebody's. Never merge or force-update through it.
- **Absent feature branch** → a `CHECK`, not a STOP: 5a ends with
  `git push -u origin <branch>`, so absence means that push never landed or
  the ref was deleted. It endangers nobody's work — find out why, push it,
  re-run. (An absent *PR* is simply the normal first-run state; 5d has not
  fired yet.)
- **Origin unreachable** → nothing was checked; do not proceed on it.
- **Deletions in the two-dot `--stat`** → the signature of a base that moved:
  content on the feature branch and absent from the task branch shows as a
  deletion (a post-merge `git diff --stat HEAD~1 HEAD` can never show this —
  first-parent trap, computenet-rbfa). One benign reading: a sibling from the
  same batch that merged after this task forked shows as reversals too —
  check the reversed paths against that sibling's `files` claim before
  parking.
- **Durability proof failed** → do not close the task and do not remove the
  worktree; diagnose and retry. The script refuses the close itself.

5e carries the same guard in its shipping form before `gh pr ready`: the
`headRefOid`-equals-local-`HEAD` check is the origin-ahead half, and the
`gh pr list --head` line there is the competing-PR half.

Do **not** remove the task worktree — every removal happens in step 6's
sweep, after all agents have returned (removing now races the reviewer's own
bookkeeping). A merge conflict means two claims overlapped: resolve in the
feature worktree, fix **both** tasks' `files` metadata, say so. A failed
review keeps its worktree, branch, and `in_progress` status — 5b's resume
query picks it up.

## 4. Read the integrated result

The first signal the whole still builds — and what a green check does not prove.

**Then look at the integrated result** once the PR exists (5d):

```bash
gh pr checks <pr-url>
```

**Green is not "the tests ran" — check they were not SKIPPED.** A required
check goes green just as happily when the diff's own suites *skipped
themselves*: an `assumeTrue`-guarded test whose precondition CI does not
provide reports `SKIPPED` and the build reports success. Measured 2026-08-17 on
PR #254 — a lane was widened specifically so a new two-JVM test would run on
every PR, all six checks went green (`build-test-serial` in 2m19s), and the
orchestrator announced that Linux execution was now proven. The job log said
`TwoJvmMirrorTest > … SKIPPED`: green proved the *wiring*, not the behaviour
(computenet-hacm). This is the CI twin of the `FROM-CACHE`/`UP-TO-DATE` trap
this skill already warns about for local Gradle runs, and it is less visible,
because `gh pr checks` reports a conclusion and a duration and nothing else.

`gh run view <run-id> --log` works non-interactively and returns the whole
run, every job — measured on #254's run 32008091003: 7553 lines, 828 KB, 3s.
Column 1 of each line is the job name, so the output also says *which lane*
skipped. Save it, then read it with two greps:

**Get `<run-id>` from a REQUIRED check's row, never from `.[0]`.** The
auto-merge workflow appears in `gh pr checks` as an ordinary row (conclusion
`skipping`) and on PR #347 it sorted FIRST, so
`gh pr checks <n> --json link -q '.[0].link'` returned the auto-merge run.
`gh run view <that-id> --log` then SUCCEEDS and returns ZERO lines, and both
greps below match nothing — which reads exactly like "nothing skipped, all
good". The anti-cache-replay check silently becomes a no-op (computenet-7ust).
Same false-negative shape this file already warns about for `bd search`: an
empty result is never evidence of an empty query.

```bash
RUN=$(gh pr checks <pr-url> 2>&1 | grep concord-full | grep -oE 'runs/[0-9]+' | cut -d/ -f2)
[ -n "$RUN" ] || { echo "NOT CHECKED: no concord-full row; do not read the greps below"; }
gh run view "$RUN" --log > "$SCRATCH/ci.log"
# A non-empty log is the precondition for reading EITHER grep as evidence.
wc -l "$SCRATCH/ci.log"   # zero lines = wrong run id, not a clean run
```

Read the two greps only after `wc -l` shows a non-empty log:

```bash
grep -E 'SKIPPED|NO-SOURCE' "$SCRATCH/ci.log" | grep -v '> Task '          # tests that skipped
grep -E '> Task [^ ]*:test (SKIPPED|NO-SOURCE|UP-TO-DATE|FROM-CACHE)' "$SCRATCH/ci.log"   # suites never run
```

**Both filters are load-bearing; the naive grep hides exactly the line you
came for.** Every build prints ~200 boilerplate
`checkKotlinGradlePluginConfigurationErrors SKIPPED` and
`processResources NO-SOURCE` *task* lines, so the bare marker pattern matched
227 times on that run and a `| head -20` showed nothing but boilerplate — the
`TwoJvmMirrorTest … SKIPPED` line was line 7519 of 7553. Dropping `> Task `
lines leaves 11, with the real one in view. (Don't add `no tests`/`0 tests` to
the pattern either: `0 tests` matches vitest's `10 tests` on every ui-test
line.) The second grep is the other half — a whole suite that never ran prints
as a task line, `:module:test NO-SOURCE`/`FROM-CACHE`, and the first grep
deliberately drops it.

Read both for the **modules this diff touches**. Anything skipped there → say
so plainly in the PR body and in the session summary, or file it — never
report CI green as verification of the behaviour. An `assumeTrue` guard that
CI can never satisfy is a real finding about the test, not a detail.

**A review finding that changes what a LATER task must do is written on that
task's own thread, by you** — `bd comment <successor-id> --file …` — not
only on the feature's. A task bead is written before its predecessors run;
when ssa.4.2's and .4.3's reviews each enlarged ssa.4.4, the amended list
sat one hop away on the feature thread and the implementer had to be told
which of two comments superseded the other (computenet-tjyl). Open the
comment with `AMENDS <task-id>'s obligations (supersedes <earlier comment
date>, from review of <predecessor-id>):` so the successor's `bd comments`
read is complete on its own; reviewers report the amendment, you write it
(cross-bead writes are not theirs).

The task reviewer tested a branch without its merged siblings; this is the
first signal the whole still builds. Red is work: file a task for the next
batch — one call, because `bd create` has no `--set-metadata` and a follow-up
`bd update` can fail on its own (see `bd traps`):

```bash
bd create --parent=<feature-id> --type=task --title "<one line>" \
  --description "<what is red, plus the pasted failing log excerpt>" \
  --metadata '{"model":"sonnet","files":"<the files it may touch>"}'
```

That create is under a feature THIS session claimed, so the dotted id is
exclusive and `--parent` is correct here. The description carries the log
excerpt issue-quality.md's CI-evidence rule requires — the run link alone
ages out before the task runs (computenet-ttz). The one narrow exception —
a red check in a module this diff doesn't touch — requires
[red-check-attribution.md](red-check-attribution.md)'s
four artifacts before treating any red as not this feature's. Never ship on
"it's a flake".

Then 5b again.
