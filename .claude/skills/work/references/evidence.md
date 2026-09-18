# Evidence

Implementers and reviewers read this whenever a claim rests on a run. Evidence
counts only if it could have come out the other way: a check that is silent,
green or zero is not an answer until it has been shown to speak where it
should fail.

## Contents

- [Did the tests run](#did-the-tests-run)
- [What your change reaches](#what-your-change-reaches)
- [Mutation checks](#mutation-checks)
- [CI evidence](#ci-evidence)
- [Flakes and contention](#flakes-and-contention)

## Did the tests run

**`BUILD SUCCESSFUL` is not a run.** Gradle replays up-to-date and cached
results whether or not you filtered, so a green build can execute zero tests.
Execution is proven by fresh JUnit XML. Every Gradle and npm call needs the
sandbox setting in [agent.md](agent.md#running-commands). Keep a test run's
log in a file; `| tail` and `-q` drop the lines you need.

```bash
./gradlew :<module>:test --rerun --no-build-cache > "<scratch>/<run-name>.log" 2>&1
```

```bash
.claude/skills/work/scripts/junit-count.py <module>/build/test-results
```

Its header documents outputs and exit codes. What they mean for the decision:

| output | means | do |
|---|---|---|
| counts with `newest` minutes old | the run executed | quote totals, module list, age |
| `newest` older than your run, or counts unchanged | a replay | re-run with the flags above |
| a module you did not run in the list | stale results from an earlier run | judge it by its own task line |
| `SHORT-COVERAGE` (with `--expect-classes`) | a `--tests` class was silently dropped | fix the filter |
| `NO-RESULTS` / `NO-SUCH-PATH` | a fact about the path you passed | fix the path; it says nothing about the suite |

The per-task line (`grep -aE '^> Task :[^ ]*:(compileKotlin|compileTestKotlin|test)( |$)'`
on the log) corroborates. An executed task prints no marker; `FROM-CACHE` or
`UP-TO-DATE` means it did not run; no line means it was never in the graph.
Judge from concrete tasks, never a lifecycle aggregate (`check`, `build`,
`testClasses`), whose state says nothing about its members.

Whether the run you asked for is the run you got:

- **`--rerun` binds to the task it follows.** Put one after each test task, or
  use `--rerun-tasks` repo-wide. On a lifecycle task it reruns nothing, so name
  the concrete tasks:
  `:concord:test --rerun :concord:concordanceGate --rerun :concord:docLints --rerun`.
- **`--rerun` alone can restore cached XML** under an unmarked task line. Add
  `--no-build-cache` to every load-bearing run (mutation checks, before/after
  comparisons) and trust the XML `timestamp` that `junit-count.py` reads, not
  file mtimes.
- **The same task twice on one command line runs once**, under the first
  `--tests` filter. Run a narrow and a broad suite as two calls.
- **`--tests` filters mislead the count.** A filtered run deletes the XML of
  every class it did not match, so count the broad run first. A nonexistent
  class is ignored when a sibling filter matches, so pass
  `--expect-classes <distinct classes named>` on any multi-filter run.

Test stdout never reaches the console on this build. Read it from the XML:
`sed -n '/<system-out>/,/<\/system-out>/p' <module>/build/test-results/test/*.xml`.

The npm UI suites and the `:iroh` cargo tasks write no JUnit XML; the tool's
own pass/fail summary is the evidence. Gradle `Exec` tasks resolve commands
from the daemon's environment, so a stub on your `PATH` is ignored: prove
failure propagation by breaking the real tool's input.

`:demo:beadsmirror:test` and repo-wide `./gradlew test` outrun the 600000 ms
foreground call: commit first, then run them per [agent.md](agent.md#waiting).
A killed test task leaves a truncated results store, and later runs of that
task fail with an `EOFException` from `getPreviousFailedTestClasses`. Run
`./gradlew --stop`, then remove `<module>/build/test-results`; if the removal is
refused, hand the exact command back under `REQUIRED ORCHESTRATOR ACTION`.

## What your change reaches

The affected modules are those whose test inputs your diff changes — not always
where the diff sits. Ask what reads the files you change, not what imports them.

| your diff | also run | because |
|---|---|---|
| adds or deletes a file under `concord/corpus/` | `./gradlew :oracle:test --rerun` | `CorpusCrossCheckTest` enumerates that directory at test time |
| adds or renames a Gradle module | `./gradlew :kernel:test --rerun` | `ModuleInventoryTest` checks the module list in `doc/ARCHITECTURE.md` |
| edits `doc/spec/**` or `concord/corpus/*.yaml` | the three concrete `:concord` tasks above | they are inputs to fatal gates; the diff is not docs-only |
| changes only comments or recorded figures in a compiled file | re-derive every figure from its operands | no suite reads comment text |

A diff is docs-only only when nothing consumes its files at build time; green
checks on it prove the build is not broken, and nothing about content.

## Mutation checks

A mutation counts when it lands where you aimed, compiles, reddens the
criterion's own assertion, and is provably reverted. **Who mutates:** A file inside your `metadata.files` claim is yours; outside
it, the reviewer's, whose dispatch grants it. Bash editing (`perl -pi`,
`sed -i ''`) is for a file you may edit when the Edit tool refuses, never a
way to change a file you may not.

The order is the safety:

1. Commit your deliverable.
2. Leave the marker, for test mutations too. Never commit while it exists;
   SKILL.md 5a treats a worktree holding it as a half-applied mutation.
   ```bash
   echo "<file and call site, what you removed>" > <your-worktree>/.mutation-in-progress
   ```
3. Copy each file aside before touching it:
   `cp <file> "<scratch>/pre-mutation-<basename>"`.
4. Mutate by editing the working tree, never with a git command. The mutation
   must not overlap the original under any matcher the code might use: rename
   `DenialReason` to `Foo`, not `DenialReasonRenamed`. A scripted edit's anchor
   must count once under `grep -cF '<anchor>' <file>`.
5. Prove it landed where you aimed: `git diff HEAD -- <file>` is non-empty and
   its hunk is in the declaration you meant. Read the whole output rather than
   grepping it. For an untracked file, grep the file for the mutated text.
6. Run with `--rerun --no-build-cache` into `"<scratch>/mut.log"`, then:
   ```bash
   grep -aE '^e:|BUILD' "<scratch>/mut.log"
   ```
   An `e:` line means it never compiled: no test ran, the XML on disk is old,
   and nothing was caught.
7. Name the assertion that went red and its message. A red from an earlier
   assertion, a throwing helper, a fixture, or another test is not the
   criterion discriminating; narrow the mutation until the criterion's own
   assertion fails.
8. Revert and prove it; `diff` must print nothing. If the mutation created the
   file, `rm` it instead. Never `git stash` ([traps.md](traps.md#git-and-worktrees)).
   ```bash
   cp "<scratch>/pre-mutation-<basename>" <file> && diff "<scratch>/pre-mutation-<basename>" <file>
   ```
9. Remove the marker, then confirm `ls <your-worktree>/.mutation-in-progress`
   reports no such file; it is gitignored, so `git status` cannot show it.
10. Run the confirming test green against the restored file.

Report the file and call site, the test, the assertion and its message. If the
strongest mutation was unavailable, name it rather than silently substituting.

**Concord scenarios:** mutate the scenario twice — flip the asserted value, then
move the observation window until it reddens with a non-zero observed count,
which shows a count assertion reads a live counter.

**Normative prose** (a `doc/spec/` requirement, a `covers:` line, a regenerated
`CONCORDANCE.md`) has no test to redden. Perturb the binding it creates — point
a `covers:` at a nonexistent id, show
`./gradlew :concord:concordanceGate --rerun --no-build-cache` fail, revert.
Whether the sentence is true of the code is still a clause-by-clause reading
against the implementation.

**Test-only tasks,** whose production file is outside the claim: cite a
sibling's mutation evidence on the same branches, labelled corroborating, or
trace each test to the production conditional it asserts on, and say which.
The reviewer runs the real mutation and checks your substitution.

## CI evidence

Local runs here are darwin; every required check runs on `ubuntu-latest`.
Report "green on darwin/arm64", never "the required checks pass". For code
touching sockets, ports, filesystem semantics, paths or process spawning,
measure the gap: a JDK-21 Linux container when `docker info` shows a running
daemon, otherwise the branch's own CI run.

Wait for checks with `.claude/skills/work/scripts/wait-checks.sh <pr-url>`; its
header documents it.

| last line | means |
|---|---|
| `SETTLED` | every required check finished; read the rows above for red |
| `TIMEOUT-PENDING`, or the call never returns | no verdict; a reviewer's one invocation for this head is spent ([review.md](review.md#feature-review)) |
| `NO-RUN` | GitHub never built this head; never wait it out |
| `QUERY-FAILED` | nothing was read |

**A green check does not prove the diff's tests ran.** An `assumeTrue`-guarded
suite reports `SKIPPED` under a green conclusion, and only the job log shows
it. Select the check's row by name, never by position:

```bash
gh pr checks <pr-url> --json name,link -q '.[]|select(.name=="<check-name>")|.link'
```

Exactly one link must print; its trailing number is the job id. This per-job
form works while sibling jobs are still pending:

```bash
gh api repos/mboogerd/computenet/actions/jobs/<job-id>/logs > "<scratch>/ci-<check-name>.log"
```

Read it only if it is a non-empty log, not a JSON error body. The greps find
skipped tests, then suites that never ran; anything skipped in the diff's
modules goes in the PR body and your report, never under "CI green".

```bash
grep -aE 'SKIPPED|NO-SOURCE' "<scratch>/ci-<check-name>.log" | grep -v '> Task '; grep -aE '> Task [^ ]*:test (SKIPPED|NO-SOURCE|UP-TO-DATE|FROM-CACHE)' "<scratch>/ci-<check-name>.log"
```

**A lane is evidence only for the tests its filter admits**, and a filter in
the lane's Gradle command leaves no `SKIPPED` line: `build-test-fast` runs
`-PexcludeMultiJvm=true`, so a `@Tag("multi-jvm")` test runs only in
`build-test-serial`. For a tagged or flag-gated test, find the admitting lane
in `.github/workflows/` (it may be a separate workflow run), read its log for
the test's `PASSED` line, and name the lane.

A log you could not fetch goes under `NOT VERIFIED`, never as passed. A red
required check is attributed per [recovery.md](recovery.md#a-red-required-check).

## Flakes and contention

Choose the instrument before spending the slot: a deterministic reproduction
when the mechanism is reachable; else the CI failure archive, before any loop,
because a loop cannot tell you a landed commit removed the cause; else a
bounded loop, sized first (runs times per-run cost, and the rate a null result
would bound).

```bash
gh run list -R mboogerd/computenet --branch main --status failure --limit 50 --json databaseId,createdAt,headSha
```

```bash
gh run view <run-id> -R mboogerd/computenet --log-failed > "<scratch>/failed-<run-id>.log"
```

Search the whole file for `FAILED` (its end is job cleanup), then `git log` the paths the mechanism touches since that run.

**Loops use `scripts/flake-loop/`.** `SuiteLoop.java` runs a package or one
method in a single JVM, keeps a file per failing iteration, and refuses a
sample whose first iteration ran nothing; `run-method-loop.sh` uses a fresh JVM
per iteration and `run-linux-loop.sh` runs in a Linux container. Each header
documents usage. A package sample:

```bash
./gradlew -q --no-configuration-cache :<module>:testClasses && CP=$(./gradlew -q --no-configuration-cache -I scripts/flake-loop/print-test-classpath.init.gradle.kts :<module>:printTestClasspath | grep -v '^WARNING' | tr '\n' ':') && java -cp "$CP" scripts/flake-loop/SuiteLoop.java --package <package> --runs <n> --out "<scratch>/flake-loop" --label <label>
```

Quote the final `SUMMARY` line; `unexpectedTestCountIterations` must be 0. A
Gradle loop is right only for a suite that is not a JUnit package on a
classpath (`:concord`, the npm suites); copy `<module>/build/test-results`
aside after each failing iteration.

**Contention comes from sibling agents** sharing Gradle caches and daemons: a
run that stalls, times out or dies before tests run is probably not your
defect. Read `uptime` before each long run — but **the load number is not the
gate**. Contention has been measured producing a red suite at 0.75x cores, far
under every rung named here, and a moderate load average read as an all-clear
is what turns a flake into a false finding against good work (computenet-sbgxs).

**The test is reachability, not load, and not the shape of the failure.** A red
suite in a module your diff does not touch: is there a dependency path from a
module you changed to the module that failed? That is answerable from the build
files in seconds and is conclusive when the answer is no. Answer it before
re-running anything — the session that had been told this cleared a red
`:inspect` suite in one isolated run, where the session that had not spent two
full repo-wide runs plus a git-history investigation on the same shape.

Contention does not only present as a timeout. A generative or property suite
failing an **assertion** is the same phenomenon and reads exactly like a
regression, which is why it costs the most: seed 132 of `OrMapGcSafetySweepTest`
did this at load ~12 on a 16-core host. So does a known flaky seed in an
untouched suite. Clear it the same way: reachability first, then the suite alone,
then the whole gate with `--rerun-tasks` — quoting its `N actionable tasks: N
executed` line, because a plain re-run is mostly cache and proves nothing.

**If it reproduces under load and passes alone, do not stop there.** That is
also the signature of a genuine race, and "re-run in isolation until it passes"
is a procedure that discards the only condition under which such a defect is
observable. Attribute it to an existing flake bead or file one, naming the load
at which it reproduced; never dismiss it as cleared.

| symptom | do |
|---|---|
| a long wait on a Gradle lock, then failure | retry once; name the signature in your report |
| Kotlin daemon `OutOfMemoryError` | `pkill -f KotlinCompileDaemon`, then retry once — it kills every daemon on the machine, so only for this signature |
| an `awaitUntil`-style timeout, at any load | re-run that suite alone before reporting it |
| a generative/property suite failing an assertion, in a module your diff cannot reach | the same contention shape as a timeout; clear it by reachability, isolated re-run, then `--rerun-tasks` |
| a red suite in a module your diff did not touch | your change invalidated its cache and exposed a latent flake; attribute it, do not dismiss it |
| it reproduces under load and passes alone | a genuine race presents exactly this way; attribute or file it, naming the load — do not record it as cleared |
| a wrong value in a suite your diff CAN reach | never contention; it is yours |
