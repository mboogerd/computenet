# Implementing a task

You implement one task, bug or chore in your own worktree, on a task branch cut
from the feature branch. Siblings run on sibling branches at the same time;
yours merges after a reviewer passes it. Read [agent.md](agent.md) first — it
owns your scope, running and waiting on commands, and your final message.

## Contents

- [Read the task](#read-the-task)
- [Build](#build)
- [Verify](#verify)
- [Hand off](#hand-off)

## Read the task

If your worktree already has commits, you are resumed: continue from what the
bead's comments say is done and left. A `.mutation-in-progress` file there is
someone's interrupted mutation: `git checkout -- .`, delete the file, and say so
before anything else. Read skill files from your own worktree,
not the main checkout. Read the bead through `bead.sh` and its comments into a
file ([traps.md](traps.md#bd) says why a plain read misleads):

```bash
.claude/skills/work/scripts/bead.sh <id>
```

```bash
bd comments <id> --json > "<scratch>/<id>-comments.json"
```

A comment opening `AMENDS <id>` changes your obligations and supersedes the
description; the latest one wins. Read the parent feature and epic the same
way, and every spec section they cite (AGENTS.md "Start every task here").

Then settle what you are building:

- **The acceptance criteria are the spec.** Implement and design prose
  describes how, and is the clause most likely to be wrong. When the two
  conflict, satisfy the criteria and write a bead comment naming the clause
  you could not follow, why, and what you did instead.
- **A clause about today's code is a hypothesis until checked.** A clause
  marked `unverified:` was handed to you to check: run the one command that
  confirms it before you build on it. A clause marked `observed:` that turns
  out false is a breakdown defect; comment the clause and the command that
  falsified it.
- **A dependency that is not actually done** is a tracker problem, not
  something to route around. Park the task ([recovery.md](recovery.md#parks))
  rather than implement against an assumption. Park the same way at a fork
  that is ambiguous, expensive, risky or hard to revert.
- **A missing local toolchain** is a `needs:<tool>` label on your bead, a
  comment saying what is absent, and a stop. It is never a park.

Diff your own work against the baseline your dispatch prompt names — the
merge-base with the feature branch, or `origin/<base>` for an item worked flat —
computed in your worktree; the base commit it names is not a diff baseline.

## Build

**A bug fix starts red.** Write the reproduction and run it against the
unfixed code. Quote the failing test name and its assertion message; that
output, not the later green, is the evidence your fix is not a no-op.

A prescribed reproduction, mutation or measurement is a hypothesis. It
describes the code as it was when the bead was written. When a prescribed
reproduction passes unfixed, or a prescribed mutation changes nothing,
conclude first that the recipe is stale, not that your fix failed:

```bash
git log --oneline <your-base>..origin/main -- <files-the-bead-names>
```

Read the sibling beads in the family, find a reproduction that fails before
your change and passes after, and comment the substitution on the bead: what
was prescribed, why it no longer discriminates, what you used.

For a flake filed on CI evidence, choose the instrument before you spend the
slot ([evidence.md](evidence.md#flakes-and-contention)). Check the CI failure
archive before running a statistical loop. "Already fixed by `<commit>`, no
longer reproducible" is a successful outcome: comment it with the commit and
report it.

Size a prescribed measurement first (runs times per-run cost against your
slot). If it does not fit, say so on the bead; never present a cheaper sample's
number as the answer.

Implement the smallest coherent change that satisfies the criteria.

- **Stay inside your `metadata.files` claim.** Siblings were scheduled on the
  assumption it is accurate. If a criterion needs a file outside it, comment
  on your bead at once, naming the file and the clause, then keep working on
  what the claim does cover. If nothing is left, stop and put the widening
  under `REQUIRED ORCHESTRATOR ACTION`. An empty claim whose description says
  the files could not be known before diagnosis means you were dispatched
  alone: your scope is the criteria, and you report every file you touched.
- **Put the limits of a claim next to the claim, in the file.** A number, bound
  or statement your evidence only partly supports carries its caveat where it
  is written. The bead comment and PR body are read once; the file is read by
  everyone who later changes it.
- **Mutate only files inside your claim.** When the proof needs a mutation of a
  file you were not given — the normal case for a test-only task — take the
  substitute routes in [evidence.md](evidence.md#mutation-checks) and name the
  property left unproven. The reviewer runs that mutation.
- **New work you discover** becomes its own item with `model` and `files`
  metadata, filed per [review.md](review.md#residuals-and-follow-ups). Parent
  it to your feature only when it is remaining work for that feature's own
  acceptance, since an open child holds the feature's review back.

## Verify

Prove the change per [evidence.md](evidence.md): narrowest test first, then the
module gate with `--rerun`, then every suite that reads what you changed. Your
dispatch prompt says whether your final gate is repo-wide or module-scoped.

- A test whose verdict turns on concurrency, scheduling or timing is proven by
  its module gate, not a narrow `--tests` run.
- Check `uptime` before each long Gradle run, but do not gate on the number: a
  red suite in a module your diff cannot reach is contention at any load, and
  it presents as an assertion failure as readily as a timeout. Ask first
  whether a module you changed has a dependency path to the one that failed;
  if not, re-run that suite alone
  ([evidence.md](evidence.md#flakes-and-contention)).
- Qualify results by platform: "green on darwin/arm64", with `uname -sm` in
  the report. You have not run the required checks.

Quote the `junit-count.py` totals, module list and newest age for each run.

## Hand off

**The commit is the handoff.** The orchestrator merges your local task branch;
anything uncommitted contributes nothing and the task reviews as a no-op. Your
dispatch prompt grants the commit. Add new files, then commit every changed
path, new ones included, by pathspec:

```bash
git -C <your-worktree> add -- <new-paths> && git -C <your-worktree> commit -m "<what changed and why>" -- <paths>
```

Then `git -C <your-worktree> status --short` must show nothing of yours.
Commit unfinished work too, and before any long wait ([agent.md](agent.md#waiting)).

Artifacts under the worktree die with it. Copy evidence that must outlive the
task to `$HOME/computenet-runs/<task-id>/` and name that path; mark any other
recorded path ephemeral.

**Stop cleanly if you will not finish within about 45–60 minutes** of starting.
Read `date -u +%s` in your first Bash call, write it into your scratch directory,
and compare against it before each long run — a budget you take yourself is the
only one that cannot arrive wrong. Commit,
leave the task `in_progress`, and write a state comment: what is done, what is
left, and the branch and sha it describes. State each next step against that
sha or the file as it is there, never as a bare imperative, because the tree
will have moved when it is read. A later batch resumes you in this worktree.

Finish with a bead comment on what landed, written to a file per
[traps.md](traps.md#bd). Leave the task `in_progress`; the reviewer and
orchestrator close it. Then write your final message per
[agent.md](agent.md#your-final-message). Its first word is your outcome token:
DONE (every criterion met and committed), PARTIAL (stopped cleanly with work
left, state comment written) or BLOCKED (parked, or the claim or a premise
stops you). Add the files you actually
touched (drift from the claim is how the orchestrator corrects later
batches), every substitution you made, and the run accounting above.
