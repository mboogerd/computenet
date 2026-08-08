# Task review

One task, on its own branch, before it merges into the feature branch. You
didn't write it: read what's there, not what you expect to be there.

You judge it against **the task's own acceptance criteria** — nothing wider.
Gaps between tasks, and criteria that live at the feature level, belong to
the feature review ([review-feature.md](review-feature.md)). Don't reach for
them here, and don't expand the task's scope to close them.

## 1. The standard, then the diff

```bash
bd show <task-id> --json                          # criteria, files claim
git -C <task-worktree> diff <feature-branch>...HEAD
```

Check:

- **Each acceptance criterion**, actually met — not plausibly gestured at.
- **The file claim.** Files touched outside `metadata.files` are a real
  problem: sibling tasks were scheduled in parallel on the assumption that
  claim was accurate. Report every one, even if the change itself is fine.
- **Tests.** Run the narrowest relevant suite per AGENTS.md. A task that
  changed behavior without a test asserting it hasn't finished.
- **Scope.** Changes nothing asked for, debug leftovers, unrelated
  reformatting.

## 2. Repair, don't bounce

Rejecting forfeits everything already spent on the task, so fix what you can
within its stated scope: a missed criterion, a thin test, a small wrong
edge. Commit on the task branch:

```bash
git -C <task-worktree> commit -am "review: <what you fixed>"
git -C <task-worktree> push
```

Fail it only when the approach is wrong at the design level, or repair would
rewrite most of the diff. If the task turns out to be underspecified or the
right call is genuinely ambiguous, apply the
[ask-human.md](ask-human.md) bar rather than inventing an answer.

## 3. Report

**Pass** — say what you verified and what you repaired. The orchestrator
merges the branch; do **not** merge it yourself, and do not touch the
feature branch or its PR. Concurrent merges into one feature branch race
each other, so merging is serialized by the orchestrator alone.

**Fail** — say exactly what is missing and what you already repaired. Leave
the branch and worktree in place; a later batch resumes the task there with
its context intact.

Either way, name every file touched outside the claim.
