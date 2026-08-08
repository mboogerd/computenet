# Feature review

Every task here is closed and each passed its own acceptance criteria. That
is not the same as the feature being done — tasks pass individually and
still leave seams nobody owned or criteria no task claimed.

You judge the **feature's** criteria and decide whether its PR goes ready or
stays draft. You are the last gate before this merges to `main`. You didn't
write this code: read what's there, not what you expect to be there.

## 1. Establish the standard

```bash
bd show <feature-id> --json          # acceptance criteria, description
bd list --parent=<feature-id> --all --json  # the tasks (--all: they are closed by now)
```

Read the parent epic too, and any spec sections the feature cites — those
are the authority (AGENTS.md), above the feature's own prose.

## 2. Read the actual diff

```bash
git -C <worktree> fetch origin main
git -C <worktree> diff origin/main...HEAD
gh pr checks <pr-url>
```

Look for what task-level review structurally cannot see:

- **Criteria with no owner** — a feature criterion no task claimed, so nobody implemented it.
- **Seams** — task A's producer and task B's consumer that never got tested together, mismatched error handling or naming across the boundary, a shared type each half interpreted differently.
- **Scope drift** — files in the diff no task claimed, or changes nothing asked for.
- **Whole-feature verification** — the module suites the tasks ran individually may not cover their interaction. Run the affected module tests, and the repo-wide gate if the feature touched anything cross-cutting.

## 3. Repair by default

A rejection forfeits everything already spent on the feature, so fix what you
can rather than sending it back. Within the feature's stated scope, repair:
missed criteria, broken seams, failing tests, gaps between tasks.

Commit repairs on the feature branch, in the feature worktree:

```bash
git -C <feature-worktree> commit -am "review: <what you fixed>"
```

You reach this point only once every task has merged, so the feature
worktree is yours alone — no other agent is committing here.

Escalate instead of repairing when the approach is wrong at the design level,
or when repair would rewrite most of the diff. Then apply the
[ask-human.md](ask-human.md) bar — that's a decision for a human, not a
rewrite you do unilaterally.

## 4. Decide

**Good enough → ready.** Every feature criterion met, checks green, no
unowned seams:

```bash
git -C <worktree> push
gh pr ready <pr-url>
bd comment <feature-id> "Review passed: <what you verified, what you repaired>. PR marked ready."
bd update <feature-id> --set-metadata review=passed
bd dolt push
```

Auto-merge and the required status checks take it from here. Don't wait for
the merge.

**Do not `bd close` the feature.** Ready is not merged: a required check can
still fail and leave the PR open forever. Closing here would let the epic
close on top of it and abandon the branch. The orchestrator closes the
feature once it has confirmed the PR actually merged.

**Not good enough → stays draft.** Say concretely why, and leave the work
recoverable rather than vague:

```bash
bd comment <feature-id> "Review: staying in draft. <what's missing and why repair wasn't the right call>"
```

Create beads tasks for the remaining work (`bd create --parent=<feature-id>`
with `model` and `files` metadata, per [feature.md](feature.md)) so the next
batch picks them up — a feature left in draft with no tasks describing what's
missing is a dead end. Leave the feature `in_progress`; do not close it.

Draft is a legitimate outcome, not a failure. Half a feature merged is worse
than half a feature parked on a branch.

## 5. Report

The feature id, ready-or-draft and why, what you repaired, and any tasks you
created. If you left it draft, name the single thing that would most change
the verdict.
