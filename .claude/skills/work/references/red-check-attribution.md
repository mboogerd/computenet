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
   gh run view <run-id> --log-failed -R mboogerd/computenet | tail -60

   # capturing the whole log: run FROM THE REPO, redirect OUT to the scratchpad
   gh run view <run-id> --log -R mboogerd/computenet > "$SCRATCH/<run-id>.log"
   ```
   Quote the `FAILED` line. A red check whose log you have not read is
   unattributed, full stop.

   **`gh` resolves the repo from `cwd`, not from the output path.** Running
   this *from* the scratchpad — the right place to put a 6,000-line log —
   fails with `failed to determine base repo: ... not a git repository`, which
   does not name `cwd` as the cause. `-R mboogerd/computenet` is the fix that
   makes `cwd` stop mattering; the `-R` above is why every command in this
   file carries it.

   **`--log` echoes the workflow's own `run:` source, so a grep for a marker
   the workflow prints will match whether or not that branch ever executed.**
   This fails quietly and in the dangerous direction: you conclude the marker
   fired. Measured on run `31728734234`, a **green** 6,359-line CI log — the
   strings `no :wire: JUnit XML in wire/build/test-results/test` and
   `multi-jvm-tagged tests outside the serial lane's scoped modules` each have
   exactly **one** hit, and in both cases that hit is the echoed source of a
   failure branch that never ran.

   Separate source from output by the marker `gh` puts on every echoed source
   line — the **literal two characters `^[` followed by `[36;1m`**, not an ESC
   byte, so a filter written as `\x1b` or `\033` matches nothing (28 of those
   6,359 lines are source):

   ```bash
   grep 'MARKER' "$SCRATCH/<run-id>.log" | grep -vF '^[[36;1m'   # real output only
   ```

   Each line is `<job>\t<step name>\t<timestamp> <content>`, so filtering by
   step name works too. Read the hit, never just its count.

   **`--log` yields no log lines while a run is still in progress.** It does
   say so (`run <id> is still in progress; logs will be available when it is
   complete`) rather than printing nothing, but that is one line where you
   expected thousands — do not read it as an empty log. Poll
   `gh run view <run-id> --json jobs -R mboogerd/computenet` step conclusions
   instead (`--json` resolves the repo from `cwd` exactly like `--log`).
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
   unparented bug bead — `create-ticket.sh --type bug --top-level --desc-file …` (the fix belongs on `main`, not on a feature branch,
   because every other PR is equally blocked) and treat the check as red
   work. The bead carries the primary evidence per issue-quality.md's
   "CI evidence must outlive the run it cites" — failing task, exception
   class and full stack, surrounding task headers with timestamps, runner
   spec — not just the run id and `FAILED` line: the log ages out before
   the bead is worked (computenet-ttz).
4. **What that prior bead instructs** — read it even when it is closed. A
   closed bead can carry a standing constraint that outlives it:
   computenet-dqy.3 closed with "if either test reddens again, reopen
   investigation rather than rerunning past it", and its epic's criteria
   said "no retries-as-fix". A session that skipped this step would have
   re-run, gone green, merged, and violated both silently. A standing
   do-not-rerun instruction overrides everything below.

With all four in hand and no standing instruction against it:

```bash
gh run rerun <run-id> --failed -R mboogerd/computenet
```

**If that command is refused rather than failing**, the route does not
dead-end here. An unattended session has seen it come back
`Permission for this action was denied by the Claude Code auto mode
classifier` (2026-08-13, PR #84) — a *refusal*, which is not the same as
GitHub answering `cannot be rerun; This workflow run cannot be retried`, and
not universal: the same command runs in an interactive session. On a refusal,
do not manufacture a push to trigger a fresh run on a branch that is
genuinely finished — an empty commit is a lie about the branch. Instead:

- **Park the PR as blocked-on-infrastructure**, exactly as the "stays red"
  case below prescribes — leave the PR as it is, leave the feature
  `in_progress` (keeping any `review=passed`), set `parked_at`, and comment
  the four artifacts you produced.
- **Name the blocked command verbatim in the session summary**, the way
  SKILL.md step 7 already requires for a refused `bd comment` — that is the
  only way an allowlist ever gets the entry it needs.

A refused re-run is a session that could not finish the route, not a session
that may ship on a red check. The rule below is unchanged by it.

**At most two re-runs.** A third red is not a flake; it is a failure you
have now reproduced three times, and it goes back to being red work under
the default. Comment each occurrence on the flake bead (run id, sha,
pass/fail) — the occurrence count is what eventually gets it fixed, and it
only exists if you write it. (Occurrence comments are counting, not
diagnosis — the run-id-only form is deliberate here and exempt from
issue-quality.md's CI-evidence rule; the *bead itself* carries the full
excerpt.)

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
