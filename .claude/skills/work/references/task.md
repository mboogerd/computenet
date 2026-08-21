# Implementing a task / bug / chore

You implement one task on **your own branch, in your own worktree**, cut
from the feature branch. Sibling tasks are running on sibling branches right
now; yours merges into the feature branch after it passes review.

Two rules follow:

- **Stay inside your `metadata.files` claim.** Siblings were scheduled in
  parallel on the assumption it's accurate — a file outside it is likely
  being edited on another branch this second, and will merge into a conflict.
  Exception: an *empty* claim whose description opens
  `files unknowable before diagnosis` means you were dispatched alone and
  the confinement rule does not apply — your scope is the acceptance
  criteria, and the orchestrator records the real claim from your diff.
- **Work only in your own worktree.** Never the main checkout, the feature
  worktree, or another task's.

## Contents

1–2. [Read the task, then check its dependencies](#12-read-the-task-then-check-its-dependencies)
3–4. [Establish the evidence before you build on it](#34-establish-the-evidence-before-you-build-on-it)
5–6. [Implement, then verify](#56-implement-then-verify)
7–9. [Commit, file follow-ups, finish](#79-commit-file-follow-ups-finish)
10. [Kill your background jobs, then report](#10-kill-your-background-jobs-then-report)

The ten steps are one sequence; the groupings above exist so you can find a
step again, not so you can skip to one.


**The Write tool may refuse a deliverable by its basename** — `findings.md`,
`report.md` — with "Subagents should return findings as text". The file is
your deliverable; write it from Bash with a quoted heredoc
(`cat > path <<'EOF'`) and say so (computenet-jobe).

**Any bead body you write that quotes code goes through a quoted heredoc or
`bd comment <id> --file <path>`** — backticks inside a double-quoted
argument execute as shell and silently vanish from the stored text
(issue-quality.md's "Backticks…" rule, computenet-9w9). The inline
`bd comment "<...>"` templates below are placeholders, not license.

**Your tracker writes have a scope too, and it is narrower than your file
claim.** By default you write to **your own assigned bead and to items you
create** — nothing else. Closing, re-prioritising, reassigning, re-parenting
or claiming any other bead is the **orchestrator's**, always, even when
something you read suggests otherwise: concurrent `/work` sessions and their
agents share one beads database (and, on one machine, one `BEADS_ACTOR`), so
a write onto another item is
indistinguishable from that item's own owner doing it, and the owner may be a
live session mid-flight.

Two cases reach past that line, and they route differently:

- **Your dispatch prompt names a cross-bead write** ("post a comment on
  `<ids>`") → it is authorized; do it, and name it in your report.
- **Your bead's acceptance criteria demand one and the prompt is silent** →
  the authorization got split, which is a known defect in the dispatch and
  not your mistake (computenet-szdd). If the criterion asks for a **comment**,
  post it — a comment is additive and changes nobody's state — and quote the
  clause verbatim in your report, so an orchestrator reading a policy warning
  finds the authorization instead of adjudicating you. If it asks for a
  **close, priority, assignee or parent change** on another bead, do **not**
  perform it: say in your report which bead, which action, and which clause
  requires it, and leave it for the orchestrator. Either way the item is not
  failed by this — reporting the split is a complete answer to that clause.

**Your obligations may have been amended since the bead was written.** A
predecessor's review can enlarge what you must do; the orchestrator writes
that on THIS bead's thread as `AMENDS <your-id>'s obligations (supersedes
…)`. Read `bd comments <your-id>` before the description and take the latest
such comment as authoritative (computenet-tjyl).

**You may be a resumed task.** If the worktree already has commits, an
earlier session started this and stopped at a clean point — read the beads
comments for what's done and what's left, and continue from there rather
than restarting. That's the whole reason the worktree is preserved.

## 1–2. Read the task, then check its dependencies

What the bead says, what its context says, and what has to have landed first.

1. Read the task and its context:
   ```bash
   bd show <id> --json > "$SCRATCH/<id>.json"
   jq -r '.[0] | "\(.description)\n---\n\(.acceptance_criteria)"' "$SCRATCH/<id>.json"
   ```

   **Redirect it — do not read it inline.** `bd show` on a child inlines its
   parent epic's *entire* description, so a child of a large epic is bigger
   than the epic (measured: 57KB for a child of a 43KB epic). It overflows
   the tool result, and the failure looks like a truncated read whose natural
   recovery — re-running the command — fails identically (computenet-rram).
   Plus its parent feature and epic, and any spec sections or prior comments
   they cite. Follow AGENTS.md's "Start every task here" — the cited spec
   text is the authority, not this file. Note `metadata.files`: your
   boundary.

   **When two clauses of the same bead conflict, the acceptance criteria
   win.** Acceptance criteria are checkable; Implement/design prose describes
   *how* and is advisory, which makes it the clause most likely to be wrong —
   it can describe a mechanism no codebase ever had. computenet-lxq
   prescribed expressing a bind as a structural seam, while its criterion
   demanded the pin be mutation-killed (deleting the bind argument at the
   production call site must fail a test). A test asserting what
   `bindAddressFor(port)` *returns* stays green when the call site stops
   calling it, so the two cannot both be satisfied. Note that this is
   detectable by reading the bead alone — re-verifying the prescription
   against the code would not catch it.

   **If you diverge, say so on the bead**, naming which clause you could not
   satisfy and why:

   ```bash
   bd comment <id> "Diverged from the Implement clause: <what it prescribes>. It cannot satisfy <the criterion, verbatim>, because <mechanism>. Implemented instead: <what you did>, which the criterion checks by <how>."
   ```

   The branch this rule closes is the silent one: an implementer that follows
   Implement prose literally produces a change that passes review-by-reading
   and fails the criterion it was written to satisfy — which is how a vacuous
   test lands (computenet-dqy.36, computenet-qaz).
2. Check dependencies (`bd show <id>` lists blockers). If something it
   depends on isn't actually done, that's a data problem in beads, not
   something to route around — park it ([ask-human.md](ask-human.md))
   rather than implementing against an assumption.

## 3–4. Establish the evidence before you build on it

A bug's reproduction must fail unfixed; a measurement must be sized before it is run.

3. **If this is a bug fix, make its reproduction fail before you fix
   anything.** A bead that prescribes the sequence to reproduce a bug carries
   the authority of whoever filed it, and can still be a false lead: a
   prescribed sequence has passed against the unfixed code because a side
   effect silently repaired the very state it was meant to catch
   (computenet-dqy.20) — an implementer who trusted it would have verified
   nothing and left permanent false assurance in the suite.


   So write the reproduction, run it against the **unfixed** code, and read the
   failure:

   ```bash
   ./gradlew :<module>:test --tests '<your new test>' --rerun    # expect FAILED
   ```

   Quote the failing test name and its assertion message in your report. That
   output — not the fact that the test passes afterwards — is the evidence your
   fix is a fix and not a no-op.

   **A prescribed reproduction is a hypothesis, not an instruction.** The bead
   describes the code *as it was when the bead was written*, and a sibling item
   in the same family may have landed since. So when the prescribed
   reproduction passes unfixed, or the prescribed mutation changes nothing,
   the first conclusion is that **the repro is stale — not that your fix
   failed** (computenet-vyr). Check what has landed: `git log --oneline
   <your-base>..origin/main -- <the files it names>`, and read the sibling
   beads in the family. Then find a mutation or a reproduction that
   *demonstrably discriminates* — one that fails before your change and passes
   after — and **report the substitution**: what the bead prescribed, why it
   no longer discriminates, and what you used instead. Quietly substituting is
   as bad as following it blindly; the next reader has to know the bead's own
   recipe is spent.

   **The same goes for any clause that predicts what the code does today**,
   not only a reproduction. `feature.md` requires the breakdown to run the
   one command that confirms such a clause, or to label it `unverified:` /
   `untested-hypothesis:` when it didn't (computenet-j69i) — so a labelled
   clause is a hypothesis handed to you deliberately: **check it before you
   build on it**, with the same one command, and report what you found. An
   *unlabelled* clause is a claim the breakdown says it verified; if it
   nonetheless turns out false, that is a breakdown defect worth a
   `bd comment` on your bead naming the clause and the command that
   falsified it, not just a silent workaround.

   **Any time you deliberately break code to prove something catches it — a
   mutation check — follow [mutation-check.md](mutation-check.md)**, which is
   the one written procedure: commit first, marker, mutate (through Bash when
   the Edit tool refuses), `--rerun --no-build-cache`, verify the revert, then
   the confirming run. **Leave the marker first**, so an agent that inherits your
   worktree after a crash can tell a live mutation from finished work. The two
   are indistinguishable from the diff alone.

   **This covers a mutation to a TEST as well as to production code.** The
   usual shape is breaking production to prove the test fails; the inverse is
   just as real — a *test-instrument* defect (a probe that would pass against
   a broken system) is diagnosed by mutating the **test**, and nothing about
   the marker's purpose changes (computenet-wpvy.34). If anything the test
   case is worse: a committed production mutation usually breaks a build
   loudly, while a committed test mutation leaves a green suite that checks
   less than it claims. Marker for both; the note says which file you broke
   and what you removed:

   ```bash
   echo "<the call site you mutated, and what you removed>" > <your-worktree>/.mutation-in-progress
   # ... apply the mutation, run the test, watch it FAIL, restore the code ...
   rm <your-worktree>/.mutation-in-progress
   ```

   Never commit the marker, and never commit while it exists. SKILL.md 5a
   reads it when it inherits a dirty worktree.

   **Choose the instrument before you spend the slot — a bug has three, and
   step 3's local reproduction is only one of them.** For a *flake* filed on
   CI evidence, the reproduction may be impossible not because the bead is
   wrong but because the defect is already gone:

   1. **A deterministic reproduction**, forcing the interference where the
      mechanism allows it. Cheapest and strongest; use it whenever the
      mechanism is reachable.
   2. **A bounded statistical loop** (`scripts/flake-loop/`, step 6). State
      the sizing before you start it: N runs x per-run cost against your slot,
      and what rate a null result would actually bound.
   3. **The CI failure archive** — the right instrument when the bead *may
      already be fixed*:

      ```bash
      gh run list -R mboogerd/computenet --branch main --status failure --limit 50 \
        --json databaseId,createdAt,headSha -q '.[] | "\(.createdAt) \(.databaseId) \(.headSha[0:8])"'
      # Do NOT pipe --log-failed to `tail`: the log ends in post-job cleanup.
      # On run 31774126595 the failing test sat at line 1482 of 1907 and
      # `tail -60` showed only "Cleaning up orphan processes".
      gh run view RUN_ID -R mboogerd/computenet --log-failed > /tmp/lf.txt
      grep -nE 'FAILED|tests completed|Execution failed' /tmp/lf.txt   # then read around the hit
      git log --oneline --since=YYYY-MM-DD -- PATHS_ITS_MECHANISM_TOUCHES
      ```

   **Do (3) BEFORE (2).** A bead filed weeks ago can have been fixed by
   unrelated landed work, and a 20-minute loop cannot tell you that — only the
   archive and the log can. computenet-pvs cost exactly that: both its
   signatures had already been fixed, one structurally by computenet-dqy.22,
   which deleted the throw site so the failure could no longer be reached. A
   200-iteration loop returned 0/200 in 1199s and read like a step-3 failure
   rather than the finding it was.

   **"Already fixed by <commit>, no longer reproducible" is a successful
   outcome of step 3, not a failure of it.** Say so on the bead, name the
   commit that fixed it, and close it — do not manufacture a reproduction for
   a defect that cannot fire.

   **If it passes unfixed, the prescribed reproduction is wrong.** Correct it
   at the source rather than quietly substituting your own, so the next reader
   does not pay for it again:

   ```bash
   bd comment <id> "Prescribed reproduction does not fail against unfixed code: <the sequence, verbatim> passes. Why it cannot reach the defect: <mechanism>. Real reproduction: <sequence>, which fails with <test name + assertion message>."
   ```

   Then derive the real one and report both.
4. **Size any measurement the task prescribes before you run it.** N runs x
   the per-run cost is a number you can compute in a minute; a dispatch slot
   is ~45-60 minutes. If the prescribed sample does not fit, **say so on the
   bead and report it** — do not substitute a cheaper sample and present its
   result as the answer. An affordable measurement standing in for an
   unaffordable one produces a number that looks like a finding and is not
   (computenet-dqy.37: 0/260 in-process runs bounds the rate at 1.15%, which
   does not exclude even the unrepaired rate the item was testing).

   ```bash
   bd comment <id> "Prescribed measurement does not fit a task slot: <N> runs x <per-run cost> = <total>. Ran <what was affordable> instead, which bounds <quantity> at <figure> and cannot discriminate <the question>. The full sample needs its own item."
   ```

## 5–6. Implement, then verify

The smallest coherent change, and proof the tests actually executed.

5. Implement the smallest coherent change that satisfies the task.
   - Hit a fork that clears the [ask-human.md](ask-human.md) bar (ambiguous,
     expensive, risky, hard to revert)? Park it instead of guessing, then
     report and finish.
   - Need a file outside your claim? **Stop and report it** rather than
     expanding silently.
   - Writing a number, a bound, or a claim your evidence only partly
     supports? **Put its limits in the file, next to it** — not only in the
     bead comment and the PR body. Being honest in the paperwork is not
     enough: the paperwork is read once, at merge, while the file is what
     every later agent reads, and whoever next wants to change that number.
     Two items stated their limits correctly everywhere except the file they
     changed, and so shipped a stronger claim than their author believed
     (computenet-k9d.2, then k9d.7); both cost their reviewer a repair.
6. Verify per AGENTS.md's "Verification" section — narrowest relevant test
   first, then the affected module's suite. Don't report success on an
   untested claim, and don't let `BUILD SUCCESSFUL` be the claim: Gradle
   replays cached results, so a green build is not evidence a test executed.
   - **Your FIRST Gradle or npm call, and every one after it, passes
     `dangerouslyDisableSandbox`.** The default Bash sandbox fails KSP with
     `Operation not permitted`, and the daemon it starts stays poisoned for
     every later call until `./gradlew --stop` — two agents each lost their
     first three builds discovering this in a fresh worktree
     (computenet-l0jf, recurred as computenet-lq0p; mechanism in
     [agent-execution.md](agent-execution.md)).
   - **Prove the run happened per [gradle-evidence.md](gradle-evidence.md)**
     — the task-count line, the per-task state line read as an absence, and
     the JUnit XML counts + `timestamp` via `.claude/skills/work/scripts/junit-count.py` — from a
     log you redirected to your own `$SCRATCH` (never `| tail -N`, never
     `-q`). Your reviewer will demand this accounting (review-task.md §2);
     produce it yourself, from the same run, and quote the numbers and the
     newest timestamp in your report.
   - **A change that ADDS or RENAMES a Gradle module runs `:kernel:test` as
     well as its own module's suite.** `ModuleInventoryTest` lives in
     `:kernel` and fails on a file your bead never mentions —
     `doc/ARCHITECTURE.md` — so a verification scoped to "the module I just
     created" yields a green local build, a green `:<newmodule>:test`, and a
     RED required check in CI with the cause several steps removed from
     anything you were asked to do. Two sessions escaped this the same day
     only because their dispatch prompts happened to ask for `:kernel:test`;
     nothing required it (computenet-m9px, computenet-d7qn).
   - **Run the suite under [agent-execution.md](agent-execution.md)'s rules**
     — one foreground Bash call with an explicit timeout up to 600000 ms
     (there is no `timeout` binary on this host); a suite you KNOW exceeds
     10 minutes is backgrounded only AFTER you commit (commit-before-you-wait
     — a stop then costs the evidence, never the work; it does not override
     the mutation-marker rule), and waited on with the bounded until-loop.
     Record every background job in `$SCRATCH/jobs` as you start it.


   **If you stop with the suite still running, say so ON THE BEAD** — which
   suite, which log, what is committed (sha) — rather than returning the
   wait as your result. A result that is only "I am waiting" reads to the
   orchestrator exactly like a finished one.

   **A build that stalls, times out, or dies before your tests run is
   probably this skill's own parallelism, not a defect you introduced** —
   sibling agents drive Gradle concurrently against shared caches and
   daemons. The signatures, the machine-wide scope of `pkill -f
   KotlinCompileDaemon`, and what is never contention (a wrong *value*) are
   in [gradle-evidence.md](gradle-evidence.md). Retry once on a signature and
   say in your report which one you hit — a "the suite fails" line that was
   really contention costs your reviewer the same detour again.


   **Hunting a rare failure, don't destroy the occurrence you waited for.**
   Told to "run it 100 times": don't hand-roll the loop and don't pass `-q`.
   `scripts/flake-loop/` is the committed harness — it runs the suite
   in-process over a package selector and writes one evidence file per
   *failing* iteration, so the occurrence you waited for survives the next
   run overwriting `<module>/build/test-results`. Invocation, the `SUMMARY`
   fields to quote, and the two cases where a Gradle loop is still the right
   instrument are in [review-task.md](review-task.md) §2.

## 7–9. Commit, file follow-ups, finish

Your own index, work discovered on the way, and the bead state you leave behind.

7. **Commit on your branch. Do NOT push it — that is the orchestrator's.**
   Your worktree has its own index, so ordinary staging is safe here:
   ```bash
   git -C <your-worktree> add <your paths>
   git -C <your-worktree> commit -m "<what changed and why>"
   git -C <your-worktree> status --short            # expect empty
   ```

   A dispatched implementer's `git push -u origin <task-branch>` is **denied
   by the permission classifier**, so pushing was never reliable and the
   branches that did get pushed were luck (computenet-zmso). Nothing
   downstream needs the remote branch anyway: your reviewer works in this
   worktree, and SKILL.md 5c merges `task/<id>` into the feature branch from
   the *local* ref — worktrees of one repository share refs. The feature
   branch is what gets pushed, by the orchestrator, after the merge.

   **The commit is therefore the whole handoff.** Uncommitted work is
   invisible to every downstream step, and there is no push to catch it later.
   **Committing here is authorized, not a liberty you are taking.** Reading
   AGENTS.md's conservative profile as forbidding it is how finished work gets
   left uncommitted (computenet-h5s4) — but both of its clauses defer to an
   explicit grant ("unless explicitly asked"; "unless your assignment
   explicitly grants it"), your dispatch prompt states that grant in as many
   words, and this step is it. What the profile still forbids is the push:
   that is the orchestrator's, above. The reasoning
   generalizes: **the orchestrator merges your BRANCH**, so anything
   uncommitted contributes nothing and the task reviews as a no-op. The file
   is not the deliverable; the commit is.

   **This is a gate, not a formality: reporting a task done with an
   uncommitted deliverable is an error.** Every downstream step reads the
   branch, so a finished file that was never committed is indistinguishable
   from no work at all — computenet-8kj.4.1 reported complete with a 788-line
   document living only in the working tree, on a branch byte-identical to
   `origin/main`. Run `git -C <your-worktree> status --short` before you
   report, and expect it empty.

   Commit even when the task is unfinished — an uncommitted change exists
   only in the working tree, so the work is invisible and gets redone if
   anything else picks the task up. Your branch is local to **this machine**:
   it survives this session, but if the whole machine is lost before the
   orchestrator merges you, the commit goes with it. That is accepted — your
   reviewer and the merge both run here, and the alternative (a push the
   classifier denies) buys nothing.

   Touch **your own branch only**. Never merge into the feature branch, push
   it, or touch its PR: the orchestrator merges after review and serializes
   it so concurrent merges don't race.
8. If implementation reveals genuine new follow-up work, create it as a
   beads item with its own `model` and `files` metadata — don't fold
   unrelated scope into this task. Parent it by what it *is*:
   `--parent=<feature-id>` **only** when it is remaining work for that
   feature's own acceptance criteria — an open child blocks the feature's
   completion verdict, so a follow-up that is itself the deliverable
   ("fixed here or filed as its own bead") re-blocks the feature it was
   discovered in and its review never fires (computenet-hrd). Everything
   else — discovered defects, improvements, out-of-scope findings — files
   under the epic (`--parent=<epic-id>`) or top-level.
9. Finish:
   ```bash
   bd comment <id> "<what landed, and if unfinished, exactly what's left>"
   ```
   The comment stays in the local beads DB; the orchestrator's Finalize push
   (SKILL.md step 6) is what sends it to the shared tracker. Don't sync it
   yourself — only acquisitions are synced mid-session, and a comment on
   your own bead is not one (claim-sync.md).

   Leave the task `in_progress` — the reviewer and the orchestrator close it
   once it's merged.

## 10. Kill your background jobs, then report

Nothing stops them once you are gone, and your report is the evidence the next session trusts.

10. **Before you report, kill every background job you started** — drain
    the `$SCRATCH/jobs` ledger per [agent-execution.md](agent-execution.md):
    an empty or absent file is a positive answer, "I don't think I started
    any" is not, and a job that outlives you delivers stale copies of your
    report to the orchestrator forever (computenet-k9d.8). Never end your
    turn waiting on one — nothing resumes you.


    Report: the task id, the outcome, and the files you **actually** touched,
    not just the ones you claimed. Drift is how the orchestrator fixes
    scheduling for later batches.

    Add a **friction** line for anything that made you slower or forced a
    guess: an underspecified task, a wrong `files` claim, a command in these
    instructions that didn't work, a step that didn't cover your case. Don't
    file it yourself — the orchestrator logs it centrally so recurrences are
    visible (SKILL.md step 7). Nothing to report is a fine answer; inventing
    one is not.
