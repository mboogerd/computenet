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
outruns that 10-minute cap, COMMIT FIRST (do not push — see your reference),
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
otherwise sit waiting — the bounded stall-watch form in step 2 is the shape
to use (`persistent: false`, one `sleep 3600`, one `echo`), and it is then
**the** bounded watch: `TaskStop` it before arming a PR-checks loop.

**A bead untouched since the implementer's own comment is the signal** that
the review has produced nothing durable. Then, in order — do not kill first:

1. **`SendMessage` the agent**: stop verifying, state a verdict now plus a
   `NOT VERIFIED` section for what you did not reach. Give it a short window.
   This keeps its context, which `TaskStop` discards.
2. **No answer → `TaskStop`**, which 5e already defines as a **draft** verdict.
   Route on whatever it wrote to the bead.
3. **Then choose** between a fresh reviewer and leaving the PR draft, on
   remaining budget — not on principle.

**A re-dispatched reviewer needs framing the first one didn't**, or it repeats
the open-ended verification that stalled: tell it **it is the second
reviewer**, that there is **no prior verdict or partial state to reconcile**,
what blocker you already cleared for it, and that **a stated verdict on
honestly-scoped evidence outranks exhaustive coverage**.

**Clear the known blocker BEFORE dispatching, not after.** §6's `git merge` is
refused to reviewer agents by the classifier — predictable, not bad luck. If
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
[ship-feature.md](references/ship-feature.md) §4, written for a feature
reviewer's substantive repair; read task for feature and the task worktree
and branch for the feature's. A prose or design-record deliverable is
substantive by default, because there rewriting the text is rewriting the
thing under review.

## 3. Merge the pass yourself, one at a time

Re-verify the feature branch first; the merge is yours, never the reviewer's.

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

Two consequences you own, because nothing else can:

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

First confirm the feature
worktree is still on the recorded branch (computenet-wpvy.29), and close the
task before touching worktrees, so a crash can't leave merged work looking
unclaimed:

```bash
git -C <feature-worktree> rev-parse --abbrev-ref HEAD   # must equal <feature-branch>
if git -C <feature-worktree> fetch origin <feature-branch> 2>/dev/null; then
  git -C <feature-worktree> merge-base --is-ancestor FETCH_HEAD HEAD \
    && echo "OK: local contains origin/<feature-branch>" \
    || echo "STOP: origin/<feature-branch> is AHEAD — somebody pushed under you"
elif git -C <feature-worktree> ls-remote origin >/dev/null 2>&1; then
  echo "CHECK: origin has no <feature-branch> — 5a pushed it, so absence is not normal"
else
  echo "STOP: origin is UNREACHABLE — this check proved nothing"
fi
gh pr list --head <feature-branch> --state open \
  --json number,author -q '.[] | "\(.number) \(.author.login)"'   # expect: only yours, or none
git -C <feature-worktree> diff --stat <feature-branch>..task/<task-id>   # BEFORE the merge — see below
git -C <feature-worktree> merge --no-ff task/<task-id> -m "Merge <task-id>"
git -C <feature-worktree> push
git -C <feature-worktree> fetch origin <feature-branch> \
  && git -C <feature-worktree> merge-base --is-ancestor HEAD FETCH_HEAD \
  && echo "OK: the merge is on origin — durable" \
  || echo "STOP: the merge is NOT on origin — do not close the task"
bd close <task-id>
git -C <task-worktree> status --short                   # expect empty; else an agent died mid-edit — report
```

**Re-verify the feature branch immediately before you merge into it, and
refuse on surprise.** 5a set this branch up, but that was potentially an hour
and several merges ago, and nothing between then and here re-reads it
(computenet-wpvy.29). Two things can have changed underneath: `origin` can
hold a tip your local ref does not contain, and a PR you did not open can have
this branch as its head. **Either is a STOP, not something to resolve** —
both sides may hold pushed, unreviewed work, and picking a winner discards
somebody's. Park the choice (`ask-human.md`) rather than merging or
force-updating.

**An absent *PR* is normal here; an absent *branch* is not.** 5d opens the PR
only after the first task merges, so `gh pr list` printing nothing is the
expected first-run state (review-task.md §1 covers the same shape for
reviewers). The branch itself is different: 5a ends with `git push -u origin
<branch>`, so by the time you reach 5c `origin/<feature-branch>` exists — its
absence means that push never landed or something deleted the ref. That is a
**`CHECK`**, deliberately not a `STOP`: `STOP` is reserved above for the two
findings that mean *park via ask-human.md* — origin ahead, or a competing PR —
because in those two somebody else may hold pushed unreviewed work. A missing
branch endangers nobody's work; find out why and push it, then merge. And the
third branch exists because a bare `if fetch` cannot tell "no such branch" from
"the network is down" — it would diagnose an unreachable origin as an absent
branch, an answer on a check that never ran (the same defect
`computenet-dtl` fixed in 5a's block). `ls-remote` succeeds only if origin
answered.

That is also why this is written as `if/elif/else` rather than an `&&`/`||`
chain: absent, ahead and unreachable are three different findings, and a chain
reports them as one line.

**The `--stat` that actually caught this is the two-dot diff *before* the
merge.** Run `git diff --stat <feature-branch>..task/<task-id>` and read it
every time, not only when something feels wrong: a two-dot diff shows both
directions, so content sitting on the feature branch and absent from the task
branch appears as a **deletion**. That is the signature of a base that moved
— in the observed case, deletions under `references/` and a 262-line test
file the implementer never wrote.

**Do not substitute the post-merge `git diff --stat HEAD~1 HEAD` for it.** On
a merge commit `HEAD~1` is the *first* parent, so that diff is the
first-parent diff, which for a clean merge is exactly the task's own changes —
a file the task never touched can never appear in it, and the check silently
never fires (computenet-rbfa is the same first-parent trap read from the
other side). One benign reading of the two-dot diff does remain: a sibling
task from the same batch that already merged into this branch after this task
forked shows up as reversals too. Check the reversed paths against that
sibling's `files` claim before parking.

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

```bash
gh run view <run-id> --log > "$SCRATCH/ci.log"
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
[references/red-check-attribution.md](references/red-check-attribution.md)'s
four artifacts before treating any red as not this feature's. Never ship on
"it's a flake".

Then 5b again.
