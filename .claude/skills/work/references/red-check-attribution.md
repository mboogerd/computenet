# Red check: prove whose it is before you treat it as not yours

Read this when a required check is red on a feature PR **in a module the
diff does not touch** (SKILL.md 5c/5e), or when a red check lands *after*
you marked the PR ready. The default in 5c — a red check on touched code is
this feature's work; file a task — needs no reference. This file is the
deliberately narrow exception.

A red required check in an untouched module is still red, and "known flake"
is exactly what a real regression looks like from the outside. This is the
rule most able to launder a genuine failure, so attribution is not a
judgement call — it is four artifacts. Produce **all four** before treating
a red check as something other than this feature's defect — and whatever
you then write onward, the bead you file or the brief you hand another
agent, carries the run id, job and verbatim `FAILED` line and no mechanism
you have not tested ([orchestrator-authorship.md](orchestrator-authorship.md);
guessing one here cost a reviewer 8 runs):

1. **The failing test and its assertion message**, read from the run, not
   from the check's one-line summary:
   ```bash
   gh pr checks <pr-url>                        # which check failed, and its run url
   gh run view <run-id> --log-failed | tail -60
   ```
   Quote the `FAILED` line. A red check whose log you have not read is
   unattributed, full stop.
2. **Proof the diff does not touch that test's module**:
   ```bash
   gh pr diff <pr-url> --name-only
   ```
   The failing test's module path must not appear in that list. If it does,
   the red is yours: file the task per 5c and stop here.
3. **A prior occurrence that already exists** — found, not remembered:
   ```bash
   bd search "<failing test class>" --status all --json    # titles + ids
   bd list --all --desc-contains "<failing test class>" --json   # descriptions
   ```
   **Both queries, and they are different commands.** `bd search` **excludes
   closed issues by default** (hence `--status all`) and matches **titles
   only** — its `--desc-contains` is an extra *filter* on that title match,
   not a description search, so adding it can only narrow the result. A
   flake this test-specific is usually named in a *description*, which is
   what `bd list --all --desc-contains` finds. Measured 2026-08-12 for
   `WsReconnectSmokeTest`: `bd search` with `--status all` returned 1 bead;
   with `--desc-contains` added it returned **0**; `bd list --all
   --desc-contains` returned the 10 beads that name it in a description,
   including `computenet-8ru`, the one carrying the diagnosis. A bead naming
   this test, or an earlier run of the identical failure you can link, is
   the artifact. "I have seen this before" is not. Nothing found in
   **either** → this is a first sighting, not a flake: file it as an
   unparented bug bead (the fix belongs on `main`, not on a feature branch,
   because every other PR is equally blocked) and treat the check as red
   work.
4. **What that prior bead instructs** — read it even when it is closed. A
   closed bead can carry a standing constraint that outlives it:
   computenet-dqy.3 closed with "if either test reddens again, reopen
   investigation rather than rerunning past it", and its epic's criteria
   said "no retries-as-fix". A session that skipped this step would have
   re-run, gone green, merged, and violated both silently. A standing
   do-not-rerun instruction overrides everything below.

With all four in hand and no standing instruction against it:

```bash
gh run rerun <run-id> --failed
```

**At most two re-runs.** A third red is not a flake; it is a failure you
have now reproduced three times, and it goes back to being red work under
the default. Comment each occurrence on the flake bead (run id, sha,
pass/fail) — the occurrence count is what eventually gets it fixed, and it
only exists if you write it.

- **It goes green** → that is a green required check; carry on normally.
- **It stays red** → the feature is *blocked on infrastructure, not
  defective*. Leave the PR as it is, leave the feature `in_progress`
  (keeping any `review=passed`), set its `parked_at`, comment "blocked on
  `<flake-bead-id>`: `<check name>`, runs `<ids>`", and go to **5f**. Do
  not file a task under the feature for it — that misattributes an
  unrelated failure to finished work and creates a task nobody should pick
  up. The PR merges on its own once the flake bead is fixed on `main`.

**Never ship on the assertion that a red check is a flake.** The four
artifacts, or it is red. What you may do is attribute, re-run, and park
honestly — not wave through: a required check that is red at ship time
stops the ship in every case. That is the orchestrator side of a rule the
reviewer already has ([review-feature.md](review-feature.md) §4: a red
required check is not the *reviewer's* to wave through either, and it
certifies draft). Nothing here relaxes it; the division is that you can see
the flake beads, the other PRs, and the run history, and the reviewer
cannot.

## Where a fix for a red check gets dispatched

Check who is live in the worktree first. A red required check arriving
*after* you marked a feature PR ready (5e) is a normal event, and when it
lands the feature reviewer is usually **still running** in that feature
worktree: it hands back its verdict and then keeps going for a while on
bead bookkeeping and follow-up checks. Return the PR to draft
(`gh pr ready --undo <pr-url>`) so it cannot merge, then pick one of
these — never dispatch a second agent into a worktree whose agent,
dispatched *this session*, has not reported ("One worktree, one live
agent", SKILL.md step 5):

- **Wait for the reviewer's notification, then dispatch into its worktree.**
  This is the default and it is nearly always right: the PR is back in
  draft and cannot merge, so waiting costs nothing but the wait. Measured
  2026-08-12 on PR #58, the report arrived about 30 minutes later.
- **Only if you cannot wait, give the fix its own worktree on the same
  branch**, and never the occupied one. **Do the `SendMessage` first, and
  wait for the reviewer's answer, before you create the worktree** — the
  ordering below is what makes this safe, not the `--force`:
  ```bash
  # 1. SendMessage the still-running reviewer FIRST:
  #    "Push everything you have on feature/<feature-id> now, then make no
  #     further commits in <feature-worktree>; a fix agent is joining this
  #     branch in <feature-id>-fix. Reply when you have pushed."
  # 2. Only after it replies:
  git worktree add --force "$PWD/../computenet-worktrees/<feature-id>-fix" feature/<feature-id>
  ```
  `--force` is required — git otherwise refuses a branch that is already
  checked out — and what it buys is separation of *files*, not of
  *commits*: **two worktrees now share one branch ref**, and the second
  worktree's index and working tree do **not** follow when the first
  commits. Measured 2026-08-13 in a throwaway repo, running exactly the
  two-agent sequence this bullet describes: after the fix agent committed
  and pushed, the reviewer's `git status --short` reported `M src.txt` for
  a file it had never touched, its working copy still held the pre-fix
  content, and its own `git commit -am` (which is what
  [review-feature.md](review-feature.md) §5 tells it to run) committed that
  stale content as a descendant — **silently reverting the fix, and pushing
  clean**. There is no non-fast-forward rejection to catch this: the ref is
  shared locally, so the reverting commit is a legitimate fast-forward.
  That is why the reviewer must stop committing before the second worktree
  exists; a warning it receives afterwards arrives too late. Then: tell the
  fix agent to commit and push promptly, and **do not mark the PR ready
  again until both agents have reported**. Marking it ready while an agent
  is still working is what stranded a substantive repair commit through PR
  #58's squash (computenet-zqf) — the AGENTS.md hazard, "the squash
  captures only what was on the branch at that instant, and the rest is
  stranded". Remove the extra worktree at Finalize with the rest —
  expecting that a shared ref leaves the *non-committing* worktree looking
  dirty for files it never edited. Finalize's gate 2 cannot tell that apart
  from real unsaved work, so it will keep that worktree and report it. That
  is the safe direction: leave it and say so in the summary.

The one thing that is never an option is dispatching a second agent into a
worktree an agent is still working in.
