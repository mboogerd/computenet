# The direct-child route: an epic with no feature layer

Read this when step 5's feature queries return empty while
`ready-in-epic.sh <epic>` still lists work — bugs/tasks/chores parented
directly to the epic (computenet-dqy: 69 children, one feature). Both
feature queries return empty while work sits ready, so this route, not 5f,
is what applies.

**Work these directly**: each gets its own worktree, branch, and PR off
`origin/main` via 5a's flow. **Read `metadata.base_branch` first**: when it
names a feature branch, cut from `origin/<that branch>` and target the PR at
it — the subject is not on `main` (computenet-nb44). Otherwise proceed with the item id in place of the feature id —
including tasks parented straight to the epic (5b's task flow needs a
feature branch to cut from and merge into, which doesn't exist here —
computenet-9xj). Same filters as everywhere: skip `human`-labeled and
recently-parked items. **When one finishes, re-run `ready-in-epic.sh` and
take the next** — don't fall to 5f while the epic still has direct children;
after T-90m, stop taking new ones. Only when that query too is empty does 5f
apply.

## Three mechanics this route leaves undecided otherwise (computenet-acc8)

- **It is reviewed with [review-feature.md](review-feature.md)**, not
  review-task.md — the item is feature-shaped here, it owns a PR, and the
  feature review is the one that judges an integrated whole. **Say so in the
  dispatch prompt**, and say whether the item has child tasks — usually
  none, and then the reviewer must not go looking for a task layer to
  reconcile (review-feature.md §1 covers the empty-`bd list --parent` shape
  and names the three §2 bullets that do not apply); if it grew one under
  the third bullet below, say that instead and the file reads normally.
- **Its draft PR opens after the implementer's FIRST commit**, not after a
  5c merge — 5d's "on the first merge" trigger has nothing to fire on, since
  no task branches merge here. Open it as soon as there is a commit, so CI
  starts and the PR exists to attach the review to. **Exception: a first
  commit KNOWN to be red** — the implementer said so, or you already know the
  branch fails — defers the open to the first commit believed green. A run
  you know is red spends the full six-check cycle (~9–12 min,
  computenet-678u) to report nothing, so the CI-churn bound (computenet-nxac)
  governs over the bright line here. computenet-a4cj's precondition binds
  unchanged: the PR must exist before the reviewer is dispatched
  (computenet-j378). Everything else in 5d —
  the `metadata.pr` guard, the `bd update --set-metadata pr=`, draft until
  5e — is unchanged.

  **The PR is a PRECONDITION of dispatching the reviewer, not a step that
  merely comes earlier.** Check `metadata.pr` before you write the review
  dispatch prompt; if it is empty, open the PR first. Skipped once in three
  items this way, the reviewer reached review-feature.md §4 and §6 with no PR
  and correctly recorded "no Linux/CI evidence exists at all" — the six
  required checks were entirely ahead of its verdict, and the only thing
  between that and a certified item shipped with zero Linux evidence was how
  prominently it chose to report the gap (computenet-a4cj).
- **It may grow a task layer under it.** If the item turns out to want
  parallel children, break it down and cut those task branches from **this
  item's** branch, merging them back into it — exactly 5b/5c's flow with
  this item standing in for the feature. The whole still ships as **one**
  PR, the item's own.

## The idle-lane question

**`ready-in-epic.sh` returning exactly one row is where the idle-lane
question actually bites** (computenet-0a76): the single item is dispatched,
one of the two lanes is busy, and 5f — where the other lane's work would
come from — is not reachable until it returns. Do not infer an answer and do
not wait for 5f: apply 5f route 0's four-part test
([next-unit.md](next-unit.md)) to the free lane now, from this spot. It
sanctions a second, genuinely disjoint unit and tells you when to leave the
lane idle on purpose.

## Bound concurrent same-file PRs

**If you run several of these at once, keep at most ~2 open PRs against any
one file.** Nothing else bounds the count here, and 5b's batching by
disjoint `metadata.files` cannot help — items on a friction epic *share* the
file by construction. Beyond ~2, sequence: hold the next item until one
lands. Each landing forces every sibling PR on that file to merge
`origin/main`, and every such merge restarts all six required checks
(~4 min) — which the next sibling landing can invalidate before it finishes,
so the churn grows faster than the PR count (computenet-nxac: three such
cycles on one PR). 5e has the cheaper re-check tier for the merges you do
still pay.
