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
- [Manual UI checks](#manual-ui-checks)
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
  class is ignored when a sibling filter matches, so after any multi-filter run
  pass `junit-count.py` (never `./gradlew`) `--expect-classes <count of classes named>`.

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
criterion's own assertion at class scope, and is provably reverted. Mutate
inside your `metadata.files` claim; outside it, the reviewer mutates, as its
dispatch grants. Bash editing (`perl -pi`, `sed -i ''`) is for a file you may
edit when the Edit tool refuses, never for one you may not.

1. Commit your deliverable; `git checkout -- <file>` would take it with the
   mutation, and `git stash` is one stack shared by every session here.
2. Leave the marker, for test mutations too, and never commit while it exists
   (SKILL.md 5a reads it as a half-applied mutation):
   `echo "<file and call site, what you removed>" > <your-worktree>/.mutation-in-progress`
3. Copy each file aside: `cp <file> "<scratch>/pre-mutation-<basename>"`.
4. Edit the file where it lives, never through git's index or stash and never
   as a relocated copy: a script resolving siblings from its own directory
   fails wholesale when moved, and that red reads as discrimination. An old
   revision is written over it: `git show <rev>:<path> > <path>`. Don't overlap
   the original under any matcher: rename `DenialReason` to `Foo`, not
   `DenialReasonRenamed`; a scripted anchor's `grep -cF '<anchor>' <file>` is 1.
5. Prove it landed: `git diff HEAD -- <file>` is non-empty and its hunk is in
   the declaration you meant; read the whole output, not a grep of it. For an
   untracked file, grep it for the mutated text.
6. Run the test's whole class, never the one test alone (an async or
   order-dependent false pass shows only beside its siblings), with
   `--rerun --no-build-cache` into `"<scratch>/mut.log"`, then `grep -aE
   '^e:|BUILD' "<scratch>/mut.log" | grep -v 'e: Daemon compilation failed'`
   (that line is the daemon's in-process fallback, not an error). Any other
   `e:` line means it never compiled: no test ran, the XML on disk is stale,
   and nothing was caught.
7. Name the assertion that went red and its message. A red at setup, a
   fixture await, a throwing helper, an earlier assertion or another test is
   not the criterion discriminating: narrow the mutation until the criterion's
   own assertion fails. A mutant that passes at class scope does not count.
8. Revert from your copy, never with `git stash` or `git checkout`, and prove
   it: the `diff` prints nothing. If the mutation created the file, `rm` it.
   `cp "<scratch>/pre-mutation-<basename>" <file> && diff "<scratch>/pre-mutation-<basename>" <file>`
9. Remove the marker; `ls <your-worktree>/.mutation-in-progress` must say no
   such file (`git status` cannot: it is gitignored).
10. Run the confirming test green against the restored file.

Report the file and call site, the test, the assertion and its message. If the
strongest mutation was unavailable, name it rather than silently substituting.

**A test that SKIPs on this host** (multicast-gated, on macOS) is mutated in a
local Linux container, not by pushing a mutated commit to CI. Copy the worktree
in, or mount it `:ro` with build output on a container path; mount other host
paths `:ro` or not at all; install toolchains inside it. If a host path was
written, report it rather than repairing it.

**Concord scenarios:** mutate twice — flip the asserted value, then move the
observation window until it reddens with a non-zero observed count, which
shows a count assertion reads a live counter.

**Normative prose** (a `doc/spec/` requirement, a `covers:` line, a regenerated
`CONCORDANCE.md`) has no test to redden: point a `covers:` at a nonexistent id,
show `./gradlew :concord:concordanceGate --rerun --no-build-cache` fail,
revert. Whether the sentence is true of the code is still a clause-by-clause
reading against the implementation.

**Test-only tasks,** whose production file is outside the claim: cite a
sibling's mutation evidence on the same branches, labelled corroborating, or
trace each test to the production conditional it asserts on, and say which.
The reviewer runs the real mutation and checks your substitution.

## CI evidence

Read [ci-evidence.md](ci-evidence.md) in full before using a CI result to certify a change or ship.

## Manual UI checks

A criterion asking for a manual or "with screenshots" browser check is met
with files, not with the Browser pane: its screenshots never reach a file, and
the pane is shared by every concurrent agent on the machine, so another
agent's navigation can take over your tab mid-check.

1. Stage and launch in one call. `scripts/stage-preview.sh` builds the demos'
   `installDist` and copies them to `~/.cache/computenet-preview/<app>`, a
   directory shared with `.claude/launch.json` and every sibling. Pick a free
   port (`lsof -iTCP:<port> -sTCP:LISTEN` prints nothing; never 8080), then
   run the main class `.claude/launch.json` names, port last, in the background
   per [agent.md](agent.md#waiting):
   `java -cp "$HOME/.cache/computenet-preview/<app>/lib/*" <main-class> <port>`.
2. Drive the check with headless Playwright from the main checkout's
   `demo/agora/ui/node_modules/playwright` (worktrees have no `node_modules`):
   a node script that `require`s it by absolute path, `chromium.launch()`es
   headless, and calls `page.screenshot({ path: "<scratch>/ui-<step>.png" })`
   at each step. Two browser contexts give a two-browser check.
3. Put each screenshot's absolute path, and what it shows, in the report.
4. Kill the server by pid and confirm the port is free again; close any pane
   tab you opened. Leave the staged directory: `.claude/launch.json` points at
   it.

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

**A timing-flake fix must show its wait waits;** green runs cannot. Show one
of: red when the awaited condition is mutated to hold too early; a poll count
above 1 on some run; or a before/after stress ratio. An awaited value the
pristine state already holds is vacuous: pair it with a condition the default
state cannot meet.

**Contention comes from sibling agents** sharing Gradle caches and daemons: a
run that stalls, times out or dies before tests run is probably not your
defect. Read `uptime` before each long run — but **the load number is not the
gate**. Contention has been measured producing a red suite at 0.75x cores, far
under every rung named here, and a moderate load average read as an all-clear
is what turns a flake into a false finding against good work (computenet-sbgxs).

**The test is reachability, not load, and not the shape of the failure.** A red
suite in a module your diff does not touch: is there a dependency path from a
module you changed to the module that failed? Answer it before re-running
anything.

**Three changes have no honest reachability answer, and a "no path" reading of
any of them licenses dismissing a real defect:**

- **`:gen`.** `buildSrc`'s `ksp-cell` convention injects `ksp(project(":gen"))`
  into all 19 modules that apply it, and exactly one of their build files names
  `:gen`. Generated descriptors are authoritative runtime metadata, so a
  generator regression's natural manifestation is a wrong value in an arbitrary
  downstream module — reading build files finds no path and is wrong.
- **`buildSrc/` itself**, and anything outside every module (`settings.gradle.kts`,
  `gradle.properties`, shared test resources): "a module you changed" is empty,
  so the question has no input rather than a negative answer.
- **Test-scope and `api`-transitive edges**, which a build file's own text does
  not show. `./gradlew :<module>:dependencies` is the authoritative answer — a
  configuration-time run needing the sandbox disabled, not a seconds-long read.

Outside those, reading the build files is quick and a genuine "no path" is
strong evidence. It is never the whole clearing procedure: run the suite alone
and then the gate, below.

Before you report a red in an untouched module, spend the two reads that
usually name it — a known flaky seed is recorded far more often than it is
rediscovered:

```bash
git log -1 --format='%h %s' -- <path/to/FailingTest.kt>
bd list --all --json | grep -i -e '<TestName>' -e 'seed <n>'
```

Quote whichever names it in your report.

| symptom | do |
|---|---|
| a long wait on a Gradle lock, then failure | retry once; name the signature in your report |
| Kotlin daemon `OutOfMemoryError` | `pkill -f KotlinCompileDaemon`, then retry once — it kills every daemon on the machine, so only for this signature |
| an `awaitUntil`-style timeout, at any load | re-run that suite alone before reporting it |
| a generative/property suite (or a known flaky seed) failing an assertion, in a module your diff cannot reach | the same contention shape as a timeout, and it reads exactly like a regression (seed 132 of `OrMapGcSafetySweepTest` at load ~12 on 16 cores); clear it by reachability, isolated re-run, then `--rerun-tasks`, quoting its `N actionable tasks: N executed` line — and if it passed alone after failing under load, also file or attribute it per the row below. The gate being green and the flake being recorded are both required, not alternatives |
| a red suite in a module your diff did not touch | your change invalidated its cache and exposed a latent flake; attribute it, do not dismiss it |
| it reproduces under load and passes alone | a genuine race presents exactly this way, and isolated re-runs discard the only condition that shows it; attribute or file it, naming the load — never record it as cleared |
| a wrong value in a suite your diff CAN reach | never contention; it is yours |
| you want CI-like load to reproduce a flake | host-wide generators (CPU hogs, `stress-ng`, busy loops) degrade every session on the shared host (load ~260); constrain inside the test JVM (`-XX:ActiveProcessorCount=2`, a small executor) or in a container with `--cpus`; never host-wide |
