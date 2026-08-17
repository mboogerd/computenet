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

**You may be a resumed task.** If the worktree already has commits, an
earlier session started this and stopped at a clean point — read the beads
comments for what's done and what's left, and continue from there rather
than restarting. That's the whole reason the worktree is preserved.

1. Read the task and its context:
   ```bash
   bd show <id> --json
   ```
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
3. **If this is a bug fix, make its reproduction fail before you fix
   anything.** A bead that prescribes the sequence to reproduce a bug carries
   the authority of whoever filed it, and can still be a false lead:
   computenet-dqy.20 prescribed "announce, do NOT runToIdle, partition, heal,
   runToIdle", and that exact sequence **passes** against the unfixed code,
   because `partition()` left the link carrying frames and the peer's
   retraction silently repaired the very state the test was meant to catch. An
   implementer who trusted it would have written a test, watched it pass,
   closed the item having verified nothing, and left permanent false assurance
   in the suite.

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

   **Any time you deliberately break production code to prove a test catches
   it — a mutation check — leave a marker first**, so an agent that inherits
   your worktree after a crash can tell a live mutation from finished work.
   The two are indistinguishable from the diff alone, and committing a
   mutation is a silently broken production change:

   ```bash
   echo "<the call site you mutated, and what you removed>" > <your-worktree>/.mutation-in-progress
   # ... apply the mutation, run the test, watch it FAIL, restore the code ...
   rm <your-worktree>/.mutation-in-progress
   ```

   Never commit the marker, and never commit while it exists.

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
   Your reviewer will demand the accounting in
   [review-task.md](review-task.md) §2 — produce it yourself, from the same
   run, using the counting snippet there:
   - Read Gradle's `N actionable tasks: X executed, Y from cache` line at the
     end of the run — measured 2026-08-14, the last line under
     `--no-configuration-cache`, second-to-last in the default mode where
     `Configuration cache entry reused.` follows it; `tail -3` catches both.
   - Check the *specific* test task's state line, reading it as an absence:
     this build prints `> Task :<module>:test` at the default log level, and a
     task that really executed prints **with no marker**, so grepping for
     `FROM-CACHE`/`UP-TO-DATE` returns nothing whether it ran or the log never
     had task lines. Four states, only two marked: `FROM-CACHE`, `UP-TO-DATE`,
     no marker (it ran), no line at all. So redirect the run to a file — `| tail -30` drops the
     line while keeping `BUILD SUCCESSFUL` (measured 2026-08-14: 88 lines
     above the end of a 178-line run), and `-q` prints no task lines, no
     task-count line and no `BUILD SUCCESSFUL` at all — then grep for the task:

     ```bash
     # $SCRATCH = YOUR OWN dir, created once:
     #   SCRATCH=$(mktemp -d "<harness scratchpad>/<task-id>-impl.XXXXXX")
     # Sibling and reviewer agents share the harness scratchpad; a colliding
     # name hands your log to another agent as evidence (computenet-84z6).
     ./gradlew :kernel:test --tests '<TestName>' > "$SCRATCH/run.log" 2>&1
     grep -E '^> Task :kernel:test( |$)' "$SCRATCH/run.log"; tail -3 "$SCRATCH/run.log"
     ```
   - **Give the Bash call an explicit timeout, up to 600000 ms.** Past its
     120s default the tool backgrounds the call, and a turn that ends waiting
     on a background job never resumes — your turn ending is your completion.
     `:demo:beadsmirror:test` alone takes ~3m40s (computenet-hob2). That means
     the **tool's** timeout argument: there is no `timeout` binary on this
     host (nor `gtimeout`), and piped it fails OPEN — `timeout 600 ./gradlew …`
     prints `command not found`, and `timeout … | tee log` hands you the last
     stage's status, i.e. 0, so a suite that never ran reports success. Under
     zsh, `${PIPESTATUS[0]}` is empty rather than 127 (computenet-fbuo). For a
     job that outlasts 600000 ms, use `run_in_background` and poll its output
     file with ordinary foreground calls — never end a turn waiting on it.
   - Quote test counts *and the newest timestamp* read from the JUnit XML
     rather than the build result — the timestamp is what separates a run from
     a replay (measured 2026-08-14: a cached repeat run left `newest`
     unchanged with identical counts and a green build; `--rerun` advanced
     it). An unquantified "suite green" is not a verification record: nobody
     re-runs your tests, so your report *is* the evidence the next session
     trusts.
   - `--rerun` binds to the task it follows, not to the command line
     (`:kernel:test :wire:test --rerun` re-ran only `:wire:test`), and it does
     not force the upstream tasks the named task depends on. Put one
     `--rerun` per test task; use `--rerun-tasks` for a repo-wide run.

   **A build that stalls, times out, or dies before your tests run is probably
   this skill's own parallelism, not a defect you introduced.** Sibling task and
   review agents are running right now, each in its own worktree, all driving
   Gradle against the same shared caches and daemons. Two observed symptoms:

   - **A run lost to `buildLogic.lock`** — a review agent waited 4 minutes and
     got nothing. Expect the wait, and retry once before concluding anything.
   - **A Kotlin-daemon `OutOfMemoryError`** caused by daemons left resident by
     a build in a *different* directory. `pkill -f KotlinCompileDaemon`
     cleared it; the retry then succeeded.

   Claim contention on a signature like those two, or on a **wall-clock
   timeout**: `awaitUntil`/`awaitDrained` raise `AssertionFailedError` when a
   starved host makes no progress, so contention does reach you as a failing
   assertion (2026-08-11: three suites timed out under load, passed in 78s
   once quiet). A wrong *value* is never contention — that one is yours. A red
   suite in a module your diff never touched is usually the opposite — your
   edit invalidated that module's cache, so it executed instead of replaying
   and exposed a latent flake (PR #27, a `:testkit` edit reddening
   `:kernel:test`).

   `pkill -f KotlinCompileDaemon` is **machine-wide**: a Kotlin daemon's
   command line carries no project path, so you cannot kill only your own, and
   a sibling mid-compile loses its daemon too. Fire it on the `OutOfMemoryError`
   signature, not on any red build. Then retry once, and say in your report
   which of the two you hit — a "the suite fails" line that was really
   contention costs your reviewer the same detour again.

   **Hunting a rare failure, don't destroy the occurrence you waited for.**
   Told to "run it 100 times": don't hand-roll the loop and don't pass `-q`.
   `scripts/flake-loop/` is the committed harness — it runs the suite
   in-process over a package selector and writes one evidence file per
   *failing* iteration, so the occurrence you waited for survives the next
   run overwriting `<module>/build/test-results`. Invocation, the `SUMMARY`
   fields to quote, and the two cases where a Gradle loop is still the right
   instrument are in [review-task.md](review-task.md) §2.
7. Commit on your branch, then push it. Your worktree has its own index, so
   ordinary staging is safe here:
   ```bash
   git -C <your-worktree> add <your paths>
   git -C <your-worktree> commit -m "<what changed and why>"
   git -C <your-worktree> push -u origin <your-branch>
   ```
   **This is a gate, not a formality: reporting a task done with an
   uncommitted deliverable is an error.** Every downstream step reads the
   branch, so a finished file that was never committed is indistinguishable
   from no work at all — computenet-8kj.4.1 reported complete with a 788-line
   document living only in the working tree, on a branch byte-identical to
   `origin/main`. Run `git -C <your-worktree> status --short` before you
   report, and expect it empty.

   Push even when the task is unfinished — an unpushed branch exists only on
   this machine, so the work is invisible and gets redone if anything else
   picks the task up.

   Push **your own branch only**. Never merge into the feature branch, push
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
10. **Before you report, kill every background job you started.** You cannot
    enumerate them from memory and there is no "list my background jobs"
    affordance here — `TaskStop` needs an id you must already hold, and a poll
    shell you backgrounded 40 tool calls ago is not something you will
    reliably recall (computenet-k9d.10). Write each one down **as you start
    it** and read the file back here:

    ```bash
    echo "<Monitor|shell|loop> <id or pid> <what it waits for>" >> "$SCRATCH/jobs"
    # ... at report time: cat "$SCRATCH/jobs", kill each line, then rm -f it
    ```

    An empty or absent file is a positive answer — you started none. "I don't
    think I started any" is not. Then: `TaskStop`
    each monitor, kill each backgrounded shell, exit each poll loop. Starting
    them is fine (waiting on CI, tailing a long run); leaving one alive is not,
    and never end your turn waiting on one, because nothing resumes you.
    Nothing stops it once you are gone either: every time it fires it delivers
    another task-notification to the orchestrator carrying a stale copy of your
    final report, and `TaskStop` on a completed agent answers "not running", so
    the orchestrator has no handle. Six such wakes in one session
    (computenet-k9d.8) — one stuck wait-loop, one `Monitor` that worked exactly
    as designed and merely outlived its purpose; that pair is the whole
    evidence base, but both classes cost the same. Two traps that make loops
    stick: a `pgrep -f <pattern>` waiter matches any *sibling* process
    carrying that pattern in its argv — your own backgrounded poll shell among
    them — so the condition never goes false. (It does not match the waiting
    shell itself or its ancestors: measured 2026-08-14 on darwin/arm64, macOS
    `pgrep` excludes both unless given `-a`.) And
    `gh pr checks --watch` returns immediately when only `auto-merge` has
    reported on a fresh head, so it is not usable as a wait — which is why
    these loops get hand-rolled in the first place.

    Report: the task id, the outcome, and the files you **actually** touched,
    not just the ones you claimed. Drift is how the orchestrator fixes
    scheduling for later batches.

    Add a **friction** line for anything that made you slower or forced a
    guess: an underspecified task, a wrong `files` claim, a command in these
    instructions that didn't work, a step that didn't cover your case. Don't
    file it yourself — the orchestrator logs it centrally so recurrences are
    visible (SKILL.md step 7). Nothing to report is a fine answer; inventing
    one is not.
