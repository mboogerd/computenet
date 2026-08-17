# Task review

One task, on its own branch, before it merges into the feature branch. You
didn't write it: read what's there, not what you expect to be there.

You judge it against **the task's own acceptance criteria** — nothing wider.
Gaps between tasks, and criteria that live at the feature level, belong to
the feature review ([review-feature.md](review-feature.md)). Don't reach for
them here, and don't expand the task's scope to close them.

**Your tracker writes are scoped the same way.** Write to **the bead under
review and to items you create** — nothing else. Closing, re-prioritising,
reassigning, re-parenting or claiming any other bead is the orchestrator's.
That cuts both ways when you judge the diff: if the implementer wrote to
another bead, check the task's acceptance criteria and the cross-bead line in
its dispatch before calling it scope creep — a criterion can commission a
comment on named sibling beads, and on computenet-dqy.72 exactly that
happened and was briefly read as an overstep (computenet-szdd). Criterion-
prescribed writes are commissioned work. A close or a priority change on
another bead is not, whatever prescribed it: report it rather than repairing
it.

## Contents

1. The standard, then the diff — criteria from the bead, diff against the fetched base
2. Prove the tests ran — not replayed from cache, and not zero tests
3. Your run is on macOS; the required checks are not
4. Repair, don't bounce — the authorship bound and `review:` commits
5. Report — the verdict, stated in as many words

Plus: **When the diff under review edits `.claude/skills/work/`** — the extra
reading a self-modifying change needs, before §5.

## 1. The standard, then the diff

```bash
bd show <task-id> --json                          # criteria, files claim (a LIST — unwrap .[0])
bd comments <task-id> --json                      # the implementer's landing notes
git -C <task-worktree> fetch origin main
git -C <task-worktree> fetch origin <feature-branch> || true   # may not exist yet
FB=$(git -C <task-worktree> rev-parse --verify -q origin/<feature-branch> \
     || git -C <task-worktree> rev-parse --verify -q <feature-branch>)
BASE=$(git -C <task-worktree> merge-base "$FB" HEAD)
git -C <task-worktree> diff "$BASE" HEAD
```

`bd show` never returns comment bodies, and the implementer's reasoning —
what it decided, what it deliberately left — usually lives only there. Read
both before the diff.

**An empty diff is not proof of no work — check the worktree first.**

```bash
git -C <task-worktree> status --short
```

A finished deliverable can exist only as an uncommitted working-tree file:
computenet-8kj.4.1 was reported complete with a 788-line document that was
never committed, on a branch byte-identical to `origin/main`. Every
downstream step reads that as nothing — the merge merges nothing, and a
reviewer diffing `origin/<feature-branch>...HEAD` sees an empty diff and can
reasonably fail the task for having produced nothing. If `status` is not
empty, **say so and name the files**: the work exists and the defect is that
it was not committed ([task.md](task.md) makes commit-and-push the required
final step), which is a different verdict from "produced nothing". Do not
commit it for the implementer without saying you did.

**Diff against the resolved base, never a bare local `main`.** A task worktree's
`main` is whatever the machine last fetched, and its copy of the feature
branch can be behind the merges the orchestrator has already made. Both
produce a diff that looks plausible and is wrong: the obvious
`git diff main...HEAD` once returned 21 files of unrelated repository history
in place of a 4-file task change, and on 2026-08-12 a branch forked before
`origin/main`'s last 20 commits produced a 24-file, 1,862-deletion diff that
reverted merged wire and demo-shell tests — one `gh pr ready` from landing
under auto-merge. If the diff's size or contents surprise you, the base is
the first thing to suspect: re-fetch and diff again before reviewing a line
of it. Fetch `main` in the same command so
`git log --oneline origin/main..origin/<feature-branch>` is available — if the
feature branch itself forked long before current `origin/main`, say so in your
report: that is a merge hazard the feature review has to resolve, not
something to fix on a task branch.

**The feature branch may not be on origin at all.** 5a pushes it and 5d opens
its PR only after the FIRST task merges, so for task 1 of every feature
`origin/<feature-branch>` does not exist and a bare
`git fetch origin <feature-branch>` fails hard with `couldn't find remote ref`.
That is why the fetch above is best-effort and the base comes from
`merge-base`: worktrees of one repository share their refs, so the local
`<feature-branch>` the orchestrator created is readable from the task
worktree whether or not it has ever been pushed, and the merge-base of it
with HEAD is the right baseline in both cases. Do not substitute
`origin/main` by hand — it is the correct base only when the feature branch
happens to be sitting on it, which is true for a fresh feature and false for
a resumed one carrying prior task merges, where it silently pulls that prior
work into your diff. Three reviewers in one session each rediscovered the
failure and each improvised that substitution (computenet-u1ai).

Check:

- **Each acceptance criterion**, actually met — not plausibly gestured at.
- **Writing metadata: `--set-metadata key=value` on `bd update`.** `bd update
  --metadata` exists too but takes a **JSON object**, so
  `bd update <id> --metadata review=passed` fails with
  `Error: invalid JSON in --metadata: must be valid JSON`. Use
  `--set-metadata`, which sets one key without quoting a document. Both
  *merge* into the existing metadata rather than replacing it (measured on bd
  1.1.2: `--metadata '{"k":"v"}'` upserts `k` and leaves the other keys
  standing, and `--metadata '{}'` is a no-op) — so neither flag will clear a
  key for you: clearing is `bd update <id> --unset-metadata <key>`, and
  `--set-metadata key=` leaves an empty-string sentinel behind instead.
  (`bd create` is the mirror image: `--metadata '{"k":"v"}'` there, and no
  `--set-metadata` at all.)
- **The file claim.** Files touched outside `metadata.files` are a real
  problem: sibling tasks were scheduled in parallel on the assumption that
  claim was accurate. Report every one, even if the change itself is fine.
- **Tests.** Run the narrowest relevant suite per AGENTS.md, and verify it
  actually ran (§2). A task that changed behavior without a test asserting it
  hasn't finished.
- **Scope.** Changes nothing asked for, debug leftovers, unrelated
  reformatting.
- **Caveats in the changed file, not just the paperwork.** Any limit the
  change relies on — one workload, single trial, "not itself measured" —
  belongs next to the claim it qualifies, in the shipped text. Check for it
  *there* specifically: an honest bead comment and PR body are what conceal
  its absence, because body-plus-diff then reads as a consistent story
  (computenet-k9d.2, then k9d.7 immediately after — the only two instances
  seen; both times the claim sat in prose — a docstring, a skill file —
  though k9d.2's change was code, and both were found only because a reader
  was told to look). Absent, repair it under §4; it is not a wording preference. The
  body is read once, at merge; the file is read by every agent afterwards
  and by whoever next wants to change the number. Same rule as
  [orchestrator-authorship.md](orchestrator-authorship.md), displaced by one
  artifact: the caveat exists and the author is honest, it is simply written
  where nobody who needs it will look.
- **The criteria themselves.** If they don't meet
  [issue-quality.md](issue-quality.md) — uncheckable, or just the title again
  — that's a breakdown defect. Where the parent feature makes the intent
  clear, tighten them (`bd update <task-id> --acceptance=…`) and review
  against the tightened version, saying so. Where it doesn't, you can't judge
  this task: say that rather than passing it on vibes.

## 2. Prove the tests ran

**First settle which standard applies.** Everything in this section is
Gradle-shaped, and a task whose deliverable is a *written finding* — a
measurement, a spike, a documented experiment, a runbook — has no suite to
cache-prove. It is not therefore unreviewable, and it is not exempt:

> **For an empirical or documentation deliverable, the standard is
> independent re-execution.** Take the commands, queries and sequences the
> document records, run them yourself, and compare what you observe against
> what it claims. A verdict that only *read* the document has reviewed
> nothing.

That is not hypothetical rigor. On epic computenet-8kj re-running is what
caught every real defect: a factually wrong order-sensitivity assertion,
transcripts that were not replayable in the order printed, and a finished
deliverable left uncommitted. Four reviewers on that epic each derived this
standard from the orchestrator's dispatch prompt rather than from this file,
which worked only because the orchestrator happened to say it every time.

**Tolerance on transcripts.** Real `bd` output is pretty-printed and carries
warning preambles; documents paste one-line JSON. Reformatting, eliding a
preamble, and truncating a long array with an explicit ellipsis are **not**
defects. What is a defect: a changed value, a changed ordering that the
document's own argument depends on, an invented field, or output presented as
verbatim that cannot be reproduced by running the stated command.

Then, for anything with a suite:

`BUILD SUCCESSFUL` is not evidence that a test executed. Gradle replays cached
results for unchanged inputs, and a cached green build is indistinguishable
from a real one in the output you normally read. Measured 2026-08-12: a green
`build-test-fast` finished in 21s with `:demo:tiering:test FROM-CACHE` and
`48 executed, 53 from cache`; the same sha re-dispatched went from
`76 executed` to `48 executed`. So "I ran it and it was green" and "I ran it
and nothing happened" are the same sentence unless you read further.

What to consume, per test run:

- **The task-count line.** Gradle prints
  `N actionable tasks: X executed, Y from cache` (or `up-to-date`) at the end
  of the run — measured 2026-08-14 in this worktree, it is the **last** line
  under `--no-configuration-cache` and the second-to-last in the default mode,
  where `Configuration cache entry reused.` follows it. `tail -3` catches it
  in both; don't hard-code a line offset.
  `gh pr checks` reports a conclusion and a duration with
  no cache information at all, so a green required check on a diff that
  touches no compiled input is evidence of nothing.
- **The per-task state line — read it as an *absence*, and keep the log that
  carries it.** This build *does* print `> Task :<module>:test` at the default
  log level, but the check is not a grep for the markers: **a task that really
  executed prints with no marker at all.** So `grep FROM-CACHE` / `grep
  UP-TO-DATE` returns nothing both when the task ran and when the build never
  printed task lines, and those two are the opposite conclusion. Grep for the
  *task*, then look at what follows it — there are four states an agent can
  see, and only two of them carry a marker. Measured 2026-08-14 in this
  worktree, four invocations of the same command:

  ```bash
  # Capture the whole run. Never `| tail -N` (see below), never -q.
  # $SCRATCH = YOUR OWN dir, created once:
  #   SCRATCH=$(mktemp -d "<harness scratchpad>/<task-id>-review.XXXXXX")
  # The shared scratchpad holds other agents' logs under these very names,
  # and reading one quotes the implementer's build as your evidence
  # (computenet-84z6).
  ./gradlew :kernel:test --tests 'civictech.cell.FifoOrderTest' > "$SCRATCH/run.log" 2>&1
  grep -E '^> Task :kernel:test( |$)' "$SCRATCH/run.log"; tail -3 "$SCRATCH/run.log"
  ```

  | run | grep result | task-count line |
  | --- | --- | --- |
  | 1, cold | `> Task :kernel:test` (no marker) | `26 actionable tasks: 8 executed, 3 from cache, 15 up-to-date` |
  | 2, repeat | `> Task :kernel:test UP-TO-DATE` | `17 actionable tasks: 17 up-to-date` |
  | 3, `--rerun` | `> Task :kernel:test` (no marker) | `26 actionable tasks: 1 executed, 25 up-to-date` |
  | 4, outputs deleted, cache hit | `> Task :kernel:test FROM-CACHE` | `17 actionable tasks: 2 from cache, 15 up-to-date` |

  The fourth state is **no line at all** — the task was never in the graph, or
  the log lost it. That is the one the marker grep cannot distinguish from a
  real execution, and it is why you grep for the task rather than the marker.
  (Run 4 was provoked with `rm -rf kernel/build/test-results/test
  kernel/build/classes/kotlin/test`; `org.gradle.caching=true` in
  `gradle.properties` is what makes `FROM-CACHE` reachable at all.)

  Two habits destroy that line, and both leave the *aggregate* line intact so
  the run still looks verifiable:

  - **`| tail -N`.** Measured 2026-08-14: `./gradlew testClasses` printed 178
    lines with `> Task :kernel:testClasses UP-TO-DATE` at line 90 — 88 lines
    above the end, so the near-universal `| tail -30` drops it and keeps
    `BUILD SUCCESSFUL`. Redirect to a file and grep it.
  - **`-q`.** Measured 2026-08-14: `./gradlew -q :gen:test --tests '…'`
    printed **zero** `Task :` lines, no task-count line, and no
    `BUILD SUCCESSFUL` — nothing to verify from at all.

  If you only have a truncated log, do not claim the per-task check. Fall back
  to the task-count line plus the JUnit XML timestamp below, which together
  prove the module's tests ran in *this* invocation — or re-run with `--rerun`
  and make the question moot.
- **The JUnit XML**, which carries the counts and a timestamp proving the
  results are from *this* run:

  ```bash
  # Pass one or more globs. For ONE module, 'wire/build/test-results/*/TEST-*.xml'.
  # For a repo-wide run you must pass BOTH depths — see the warning below.
  python3 -c '
  import glob, sys
  from xml.etree import ElementTree as ET
  t = f = e = 0; newest = ""; mods = set()
  for pat in sys.argv[1:]:
      for p in glob.glob(pat):
          r = ET.parse(p).getroot()
          t += int(r.get("tests", 0)); f += int(r.get("failures", 0)); e += int(r.get("errors", 0))
          newest = max(newest, r.get("timestamp", ""))
          mods.add(p.split("/build/")[0])
  print(f"{t} tests, {f} failures, {e} errors, newest {newest}")
  print(f"{len(mods)} modules: {sorted(mods)}")' \
    '*/build/test-results/*/TEST-*.xml' '*/*/build/test-results/*/TEST-*.xml'
  ```

  **A single-depth glob silently undercounts a repo-wide run.** The eight
  `demo/*` modules are nested one level deeper than the rest, so
  `*/build/test-results/...` alone misses them and *says nothing about it*.
  Measured 2026-08-14 over the main checkout's accumulated results:
  single-depth reports **496 tests across 7 result directories**, both depths
  report **586 across 15** — 90 tests and 8 modules (`demo/agora`,
  `demo/backlog-triage`, `demo/exchange`, `demo/shell`, `demo/shopping`,
  `demo/skillmatch`, `demo/slotfinder`, `demo/tiering`) omitted with no
  visible sign. Reporting "496 tests, 0 failures" for a tree that ran 586 is a
  number you did not measure — exactly what SKILL.md's authorship rule exists
  to prevent. That is why the snippet prints its module list: **read it, and
  if a module you expected is missing, your glob is wrong, not the run.**

  **Read that list for strangers, too, and read `newest` against the clock.**
  `*/build/` is a glob, not the module list: on a long-lived checkout it also
  matches `legacy/` and `runtime/`, which AGENTS.md says are stale build
  output with no sources — they are two of the 15 above, and `runtime`'s XML
  was a year old when that figure was taken. The snippet prints only the
  *newest* timestamp, so one fresh module hides fourteen stale ones. If
  `newest` is not from minutes ago, nothing here ran; and a run you cannot
  date is not a verification record. Measured 2026-08-14 across the three runs
  above: run 1 left `newest 2026-08-14T12:40:26.685Z`, the cached run 2 left
  that timestamp **unchanged** while still printing `BUILD SUCCESSFUL`, and
  the `--rerun` run 3 advanced it to `2026-08-14T12:40:45.016Z`. Counts were
  identical (`1 tests, 0 failures, 0 errors`) in all three — the timestamp,
  not the count, is what separates a real run from a replay. The npm UI suites (`inspect/ui`,
  `demo/agora/ui`, i.e. the `ui-test` and `agora-ui-test` checks) emit no
  JUnit XML at all and are invisible to this snippet — their absence is not a
  wrong glob.

  Quote the numbers *and the module count* in your report. An unquantified "suite green" — yours or
  the implementer's — is not a verification record, and the orchestrator never
  re-runs it: your report *is* the evidence the next session trusts.

- **`--rerun` binds to the task it follows, not to the command line.**
  `./gradlew :kernel:test :wire:test --rerun` re-ran only `:wire:test` while
  `:kernel:test` came back `UP-TO-DATE`, with both task names on screen and
  `BUILD SUCCESSFUL` at the end. It also does not force the *upstream* tasks
  the named task depends on. Put one `--rerun` per test task, or run one task
  per invocation; use `--rerun-tasks` for a repo-wide run.
- **The strongest signal is cache-proof: break it and watch it fail.** For a
  test-bearing task, mutate the production code the test is supposed to
  constrain, re-run, see the *named* test fail, revert. A test that passes
  both ways proves nothing, and no cache can fake a red run. Report the
  mutation you made, the test name that went red, and its assertion message —
  "I did the mutation check and it failed as expected" is the same
  unfalsifiable sentence this section exists to stop.

  **Leave the marker while it is applied** — the same rule
  [task.md](task.md) step 3 gives implementers, and it matters more here,
  because the incident that produced it (computenet-leg) was a *feature
  review* killed mid-run. A budget expiry between the mutation and the revert
  leaves a worktree dirty in exactly the shape of finished work, and the next
  session cannot tell them apart:

  ```bash
  echo "<the call site you mutated, and what you removed>" > <task-worktree>/.mutation-in-progress
  # ... mutate, re-run, watch the named test FAIL, revert ...
  rm <task-worktree>/.mutation-in-progress
  ```

  It is gitignored, so it can never be committed. Never commit while it
  exists. SKILL.md 5a is what reads it.

**A run that stalls, times out, or dies before the tests run is probably this
skill's own parallelism.** Sibling task and review agents run concurrently, each in
its own worktree, all driving Gradle against the same shared caches and
daemons. Two observed symptoms: a run lost to `buildLogic.lock` after a
4-minute wait, and a Kotlin-daemon `OutOfMemoryError` caused by daemons left
resident by a build in a *different* directory. Clear those daemons — `pkill
-f KotlinCompileDaemon` is machine-wide and takes a sibling's in-flight
compile with it, so fire it on that signature and not on a red build
generally — then retry once before you read a failure as the task's. A
**wall-clock timeout** can be this too: `awaitUntil`/`awaitDrained` raise
`AssertionFailedError` when a starved host makes no progress (2026-08-11:
three suites timed out under load, passed in 78s quiet). A wrong *value* is
never contention; a red suite in an untouched module is more often a latent
flake the diff un-cached (PR #27). If you fail a task on a build result, say
which attempt it was, because contention reported as a defect sends the
implementer after something that is not there.

**Don't destroy a rare failure's evidence.** If a run's *failure* is what
matters — a flake hunt, a repetition loop — do not pass `-q`: it keeps the
detail off the console and it does not reach the Gradle daemon log either, and
the JUnit XML is the only place the suppressed exception and pre-interrupt
thread dump live. The next iteration overwrites `<module>/build/test-results`,
so the one occurrence you waited for is the one you lose.

**Don't hand-roll the loop either — it is committed.** `scripts/flake-loop/`
drives the JUnit Platform in-process over a package selector: one iteration
costs seconds instead of Gradle's ~40s, and every *failing* iteration gets its
own append-only file under `<out>/failures/` with the full stack trace, which
is the overwrite problem solved rather than worked around. A sample where
nothing *ran* cannot be read as a sample where nothing *failed*: the harness
takes iteration 1's own executed count as the baseline for the rest of the run
and refuses to start if that count is 0. Don't pass `--expect-tests N` unless
you are pinning a specific figure — the number changes whenever the module
gains a test, which is how every committed copy of it went stale
(computenet-dqy.56).

```bash
./gradlew -q --no-configuration-cache :wire:testClasses
CP=$(./gradlew -q --no-configuration-cache \
       -I scripts/flake-loop/print-test-classpath.init.gradle.kts \
       :wire:printTestClasspath | grep -v '^WARNING' | tr '\n' ':')
java -cp "$CP" scripts/flake-loop/SuiteLoop.java --package civictech.wire \
  --runs 260 --out build/flake-loop --label local
```

Substitute your module and package; quote the final `SUMMARY` line (it carries
the sample size, the failing-iteration count, the baseline it checked against,
and `unexpectedTestCountIterations`, which must be 0 or the sample is not what
it claims). Read that baseline rather than trusting the zero — a run whose
iteration 1 was itself truncated agrees with itself for the rest of the sample.
`scripts/flake-loop/run-linux-loop.sh` runs the same instrument in a JDK-21
container, with defaults shaped for `:wire`.

This supersedes the `gradlew`-in-a-loop below for any suite selectable as a
JUnit package. Keep the Gradle loop only where the harness cannot express the
sample — a flake that needs a **fresh JVM per iteration** (SuiteLoop reuses
one JVM, and the rates differ: the same macOS flake measured 0.83% fresh-JVM
against 0.26% in-process), or a suite that is not JUnit-on-a-classpath
(`:concord`, the npm UI suites). Then archive before re-running:

```bash
archive=$(mktemp -d)
for i in $(seq 1 100); do
  ./gradlew :wire:test --rerun || {
    cp -R wire/build/test-results "$archive/fail-$i"; echo "kept $archive/fail-$i"; break; }
done
```

## 3. Your run is on macOS; the required checks are not

Run `uname -sm` and put its output in your report — this repo is developed on
darwin, and that is where you almost certainly are. Every required check
(`build-test-fast`, `build-test-serial`, `concord-full`, `ui-test`,
`agora-ui-test`) runs on `ubuntu-latest`. For most diffs
that gap is invisible; for anything touching sockets, ports, filesystem
semantics, path handling, or process spawning it is exactly where the defect
hides — a `:wire:test` that passed 15/15 locally failed `build-test-fast`
deterministically on ubuntu because the new test encoded BSD/macOS TCP
behaviour, and nothing runnable locally could have shown that.

So:

- Report what you actually observed, qualified by the platform you observed it
  on: "green on darwin/arm64", never bare "green" and never "the required
  checks pass". You have not run them and must not claim to have.
- Defer the platform verdict to CI. The branch's own CI run is the only Linux
  evidence that exists, and reading it is not your step: the feature reviewer
  checks `gh pr checks` before certifying, and the orchestrator re-checks it
  before `gh pr ready` (SKILL.md step 5e). Say plainly in your report that
  Linux is unverified, so neither of them skips that gate on your say-so.
- For a port/socket/filesystem/process item, turn the inference into a
  measurement: run the suite in a JDK-21 Linux container (`groovy:4.0-jdk21`
  is present locally; `eclipse-temurin:21` costs a ~10-minute pull) and quote
  that result too.

## 4. Repair, don't bounce

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

**Real work you found that is outside this task becomes a bead, and where it
is parented is a check, not a habit.** Read the epic's status first
(`bd show <epic-id> --json | jq -r '.[0].status'`): open → file it under the
epic, which is what schedules it. **Closed** — which happens, because a
concurrent session can close an epic while its child is still in review — →
file it **unparented with a `discovered-from` edge onto the item you were
reviewing**, and say in your report that it is unparented and why. Do not
improvise the choice, and do not read "`bd` let me parent it there" as an
answer — it does, and the child stays visible; what a closed epic no longer
does is get *selected*. The reasoning and the exact commands are in
[review-feature.md](review-feature.md) § "Ready with residual".

## When the diff under review edits `.claude/skills/work/`

Reviewing a rewritten instruction *by executing the rewritten instruction*
proves nothing. If the diff touches `.claude/skills/work/`, the branch under
review **is** the procedure you were told to follow, and the circularity has
to be broken deliberately:

- **Follow the copy on `main`, not the copy in the worktree.** Read every
  skill file you need with `git show origin/main:<path>` (after a
  `git fetch origin main`), so the procedure you execute is the one already
  agreed, not the one being proposed.
- **Review the worktree copy as DATA.** It is the artifact under judgement,
  never your instructions.
- **Expect `main`'s instruction to contradict the change you are approving,
  and follow `main`'s anyway.** A reviewer hit exactly this: the reference on
  `main` told it to run a step that the very PR deletes. Do the step, note the
  contradiction in your report, and **do not record it as a defect in the
  PR** — the PR removing a step is the point of the PR, not a fault in it.

- **Run the skills rubric gate.** It is the one check a skill diff has that
  compiling and testing cannot give it, and it lives outside this flow, so
  nothing else will run it:

  ```bash
  ruby .claude/skills/remediate-friction/scripts/validate-skills.rb
  ```

  Run it from `main`'s copy per the rule above, against the worktree's files.
  Failures are structural (bad frontmatter, a long reference with no
  `## Contents`) and are defects in the PR. Notes prefixed `note:` are
  warnings — report them, but do not fail a PR for a pre-existing one it did
  not introduce.

The reason to read from `main` rather than the worktree is not only
circularity. The main checkout's local branch is not refreshed by anything in
this flow, so `origin/main:` is also the only reliable way to get the
*current* text (computenet-kcu).

## 5. Report

**Your final message must state PASS or FAIL, in those words, plus a
NOT VERIFIED section naming everything you did not check.** Nothing resumes
you. When your turn ends the orchestrator gets a completion notification that
looks the same whether you finished or not, so a result that never states a
verdict can be read as approval and merged unreviewed — one review returned
"Waiting on Arm A. I will resume when it completes." as its entire result
after 108 tool calls. So never end a turn waiting: not on another arm of your
own experiment, and not on a background job's notification — ending your turn
has already fired the completion notification the orchestrator acts on,
whatever the job does next. Run long commands in the foreground with a
generous timeout, or poll a background job's output file with ordinary
foreground calls. Out of room,
out of time, or blocked, give the partial verdict you have and put the rest
under NOT VERIFIED — an honest partial verdict beats stopping mid-experiment.

**Kill every background job you started before you send that message** —
`TaskStop` each monitor, kill each backgrounded shell, exit each poll loop.
Background jobs are legitimate (waiting on CI, tailing a long run); leaving one
alive is not. Nothing stops it once you are gone: every time it fires it
delivers another task-notification to the orchestrator carrying a stale copy of
the verdict above, and `TaskStop` on a completed agent answers "not running",
so the orchestrator has no handle at all. Six such wakes in one session
(computenet-k9d.8) — one agent's stuck wait-loop, one agent's `Monitor` that
behaved exactly as designed and merely outlived its purpose; that pair is the
whole evidence base, but both classes cost the same. Two traps that make loops
stick: a `pgrep -f <pattern>` waiter matches any *sibling* process carrying
that pattern in its argv — your own backgrounded poll shell among them — so
the condition never goes false. (It does not match the waiting shell itself or
its ancestors: measured 2026-08-14 on darwin/arm64, macOS `pgrep` excludes both
unless given `-a`.) And
`gh pr checks --watch` returns immediately when only `auto-merge` has reported
on a fresh head, so it is not usable as a wait — which is why these loops get
hand-rolled in the first place.

**Pass** — say what you verified, with the test counts and the executed/from-cache
accounting behind it, and what you repaired. The orchestrator
merges the branch; do **not** merge it yourself, and do not touch the
feature branch or its PR. Concurrent merges into one feature branch race
each other, so merging is serialized by the orchestrator alone.

**Fail** — say exactly what is missing and what you already repaired. Leave
the branch and worktree in place; a later batch resumes the task there with
its context intact.

Either way, name every file touched outside the claim.

**Friction:** end your report with anything that made you slower or forced a
guess — an unusable parent item, a command here that did not work, a case these
instructions do not cover. Report it; do not file it. The orchestrator logs it
centrally so recurrences are visible (SKILL.md step 7).
