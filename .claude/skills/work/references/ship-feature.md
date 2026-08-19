# After the ship decision: making a certified feature actually land

Read this right after running `gh pr ready` on a certified feature
(SKILL.md 5e), and whenever a feature reviewer hands back a **draft**
verdict. It covers the two halves of 5e that are not the happy path: a
ready PR that does not merge itself, and the four shapes of a draft
verdict.

## 1. `gh pr ready` is the ship decision, not the ship

A PR can go ready and then sit open forever:
`.github/workflows/auto-merge.yml` is `continue-on-error`, so when its
merge command fails the job still reports green, nothing retries it, and no
later event fires. Measured 2026-08-10 on PRs #20 and #21 — both `CLEAN`,
all five required checks green, both `review=passed`, neither merged, both
with `autoMergeRequest` null, while three sibling PRs marked ready in the
same burst armed and merged normally.

So the state check reads **`autoMergeRequest`**, not just the checks: it is
the only field that separates "still waiting on its checks" from "never
armed". `gh pr checks` cannot make that distinction — in exactly this case
the auto-merge job appears there as a **pass**.

**Cheaper than recovering it: mark PRs ready one at a time**, waiting for
each to merge before marking the next. The burst is what creates the race.

Neither you nor the reviewer closes the feature on the verdict — ready is
not merged, and a red required check can leave the PR open indefinitely.
Check whether it landed:

```bash
gh pr view <pr-url> --json state,mergeStateStatus,autoMergeRequest,statusCheckRollup
```

`MERGED` → `bd close <feature-id>`. Leave its worktree and local branch
alone here: the reviewer that just certified it is very likely still live
in that worktree, and a merged PR's worktree is not urgent to reclaim.
Finalize (SKILL.md step 6) removes it, after every dispatched agent has
reported.

Still `OPEN` → auto-merge will not fix itself; read the two fields
together — either alone is ambiguous:

| `autoMergeRequest` | `mergeStateStatus` | Meaning | Do |
|---|---|---|---|
| object | `UNKNOWN` | GitHub hasn't computed mergeability yet | re-query once after ~10s; still `UNKNOWN` → treat as pending, move on |
| object | `DIRTY` / `BEHIND` | conflicts with `main`, or needs updating | **resolve it yourself** (§2) |
| object | `BLOCKED` / `UNSTABLE` | a required check is red or still running | red → [red-check-attribution.md](red-check-attribution.md); running → move on, it merges on its own |
| object (`enabledAt`, `enabledBy`) | `CLEAN` | armed and waiting on the merge | move on |
| **`null`** | **`CLEAN`** | **arming failed; nothing will retry it** | recover it, below |
| `null` | anything else | undecidable yet — the checks are still running | re-check once they settle |

`UNKNOWN` is the common first answer, not an error — GitHub computes
mergeability lazily, so a fresh push almost always returns it. Reading it
as "no conflict" is how a conflicted PR gets left to rot.

**`null` + `CLEAN`: recover it.** Which of two shapes it is decides the
remedy, and the run list is what tells them apart:

```bash
gh run list --workflow=auto-merge.yml --branch feature/<feature-id> \
  --json databaseId,conclusion,createdAt
```

- **A run with conclusion `success`** → the job passed but its merge
  command did not. Confirm in the step output, which says so verbatim:
  `GraphQL: Base branch was modified. Review and try the merge again.
  (mergePullRequest)` (`gh run view <run-id> --log`). That error is
  explicitly transient — `--auto` on an already-mergeable PR merges
  immediately instead of queueing, so a burst of ready PRs makes each one
  race the others' merges. `gh run rerun <run-id>` fixes it: confirmed
  2026-08-10, re-running run 31349461091 landed PR #21.
- **Only runs with conclusion `skipped`, or no runs at all** → the workflow
  never fired for the ready transition. The run you can see fired from a
  push made while the PR was still draft, and the workflow's condition
  requires `draft == false`. **Re-running a skipped run re-evaluates the
  original draft-time payload and skips again**, so there is no re-run
  remedy here; it needs a fresh event — a push to the branch, or a manual
  merge.

If neither route is open to you — `gh pr merge --squash` has been denied by
the sandbox permission classifier on this repo, while `gh pr ready` is
permitted — **say so in the session summary, naming the PR url and the
exact blocked command**, and leave the feature `in_progress` with
`review=passed` so the next session finds it. Two finished, green, reviewed
PRs once sat open through a whole session because nothing said this out
loud.

## 2. Conflicts are yours

Resolving a conflict is the orchestrator's job, not a reviewer's — it's the
one thing standing between finished work and `main`:

```bash
git -C <feature-worktree> fetch origin main
git -C <feature-worktree> merge origin/main       # resolve, then test
git -C <feature-worktree> push
```

Re-run the affected module suite after resolving, and dispatch a task
reviewer over the resolution (5c): a conflict resolved by hand is new code
nobody has reviewed, and it is yours
([orchestrator-authorship.md](orchestrator-authorship.md)). Describe what
you resolved without asserting a cause you did not test. If the conflict is
substantive enough that resolving it means redesigning either side, that
clears the [ask-human.md](ask-human.md) bar — park it rather than guessing
which side wins.

Anything still `OPEN` after that → leave the feature `in_progress`, add its
`parked_at`, and move on; Finalize re-checks it, and a later session sees
`metadata.review=passed`, re-checks the PR, and closes it then. Don't block
the slot waiting unless something depends on it (5f).

## 3. Draft verdicts: four shapes, routing differently

Read the **verdict comment** (`bd comments <feature-id> --json`) to tell
them apart; `metadata.review` is absent in all four and distinguishes
nothing.

| Draft because | Evidence in the verdict | Route |
|---|---|---|
| the feature has gaps, and the reviewer filed tasks for them | the task ids it created | ready work → **5b** |
| the reviewer's own repairs were substantive ([review-feature.md](review-feature.md) §5) | the repair commit shas and their per-commit `--stat` | independent check of *those commits* → §4 below |
| a required check is red | the check name and conclusion it quoted from `gh pr checks` | [red-check-attribution.md](red-check-attribution.md) — attribute, re-run, or park as blocked-on-infrastructure |
| none of the above | nothing actionable named | dead end: leave `in_progress`, set `parked_at`, go to **5f** |

## 4. The substantive-repair draft: a finished feature with no second reader

Two verdicts route here, not one:

- a **DRAFT** hand-back, where the reviewer repaired past §5's authorship
  bound and said so; and
- a **READY** verdict carrying `metadata.second_reader` — the reviewer
  certified but flagged its own work (review-feature.md §5). That one is
  ready in every other respect, so it is easy to walk straight into `gh pr
  ready`; don't. Dispatch the reader below **first**, using the flag's value
  as the scope, and ship on its answer. Until this row existed a reviewer
  that certified while asking for a spot-check had its request land nowhere,
  and the orchestrator adjudicated it while deciding whether to ship
  (computenet-a4h1).

In both cases the code is done and the only thing missing is that its last
author also holds the certification. Do not send it to 5b — there is no implementation work — and
do not park it. Dispatch a reader for the reviewer's own commits — into the
same feature worktree, so only once the first reviewer's completion
notification has arrived, not merely once its verdict comment is readable
("One worktree, one live agent", SKILL.md step 5). If that reviewer belongs
to an earlier session, its agent died with that session and its notification
will never arrive here, so do not wait for one:

```
Agent({
  description: "Independent check of review repairs on <feature-id>",
  model: "opus",
  run_in_background: true,
  prompt: `Feature ${id}'s reviewer repaired it past the authorship bound in
.claude/skills/work/references/review-feature.md section 5 and handed back a
draft verdict, so its own repairs need a reader who did not write them.
Worktree: ${worktree} · Branch: ${branch} · PR: ${pr}
Review ONLY these commits, authored by the previous reviewer: ${shas}
  git -C ${worktree} show --stat --format='%h %s' <each sha>
The rest of the feature is already certified — do not re-review it, and say
in your report which shas you actually read.
Follow review-feature.md scoped to those commits: are they correct, within
the feature's scope, and covered by a test that fails without them? Repair
what you can. You wrote none of this, so you may certify: on a pass, set
metadata.review=passed on ${id} with a comment naming the shas. Do NOT run
gh pr ready — the orchestrator ships.`
})
```

The shas come from the first reviewer's verdict comment. If it did not name
them, recover them from the branch — §5 commits repairs with a `review:`
subject — and log it as friction (step 7), because a §5 draft that omits
its own shas is a `review-feature.md` defect, not something to guess
around:

```bash
git -C <feature-worktree> log --oneline --no-merges origin/main..HEAD --grep='^review:'
```

On the second reviewer's **pass** → ship it yourself with 5e's ready
sequence; the feature is then certified by an agent that wrote none of the
code it certified, which is the whole point. On its **fail** → it filed
tasks, go to **5b**.
