# Gradle evidence: proving a test run happened

The single copy of the cache-accounting rules. task.md step 6,
review-task.md §2 and review-feature.md §3 all point here; each keeps only
what is specific to its role. Cited beads carry the full incidents
(`bd show <id>`).

## Contents

- [Why `BUILD SUCCESSFUL` proves nothing](#why-build-successful-proves-nothing)
- [The three signals to consume, per run](#the-three-signals-to-consume-per-run)
- [Aggregate tasks lie about their members](#aggregate-tasks-lie-about-their-members)
- [Clearing results is denied to dispatched agents](#clearing-results-is-denied-to-dispatched-agents)
- [Measurements whose failure mode is a PASS](#measurements-whose-failure-mode-is-a-pass)
- [Cargo is not Gradle](#cargo-is-not-gradle)
- [`--rerun` semantics](#--rerun-semantics)
- [A killed test task corrupts the results store](#a-killed-test-task-corrupts-the-results-store)
- [How long the suites take](#how-long-the-suites-take)
- [Contention from this skill's own parallelism](#contention-from-this-skills-own-parallelism)
- [Rare-failure evidence](#rare-failure-evidence)

## Why `BUILD SUCCESSFUL` proves nothing

Gradle replays cached results for unchanged inputs, and a cached green build
is indistinguishable from a real one in the output you normally read.
Measured 2026-08-12: a green `build-test-fast` finished in 21s with
`:demo:tiering:test FROM-CACHE` and `48 executed, 53 from cache`; the same
sha re-dispatched went from `76 executed` to `48 executed`. "I ran it and it
was green" and "I ran it and nothing happened" are the same sentence unless
you read further. `gh pr checks` reports a conclusion and a duration with no
cache information at all.

## The three signals to consume, per run

**Signal 3 is the verdict; signals 1 and 2 corroborate it.** The task-count
line is a weak proxy for "did tests run", and it is misread in BOTH directions
by agents doing exactly what this file says (computenet-0frx, two agents in one
session plus three more on the second case):

- **It reads GREEN when nothing ran.** `./gradlew :kernel:test` — the plain,
  *unfiltered* module gate — printed `BUILD SUCCESSFUL` with `:kernel:test
  UP-TO-DATE` and zero tests executed. AGENTS.md frames this trap around a
  `--tests`-filtered invocation, so an agent running the whole module suite
  reads itself as outside the warning. It is not: **up-to-date checking does
  not care whether you filtered.**
- **It reads like a cache replay when the run was real.** `:concord:test
  --rerun` prints `1 executed, N up-to-date` — N measured as 23, as 14, and as
  14 again on re-verification 2026-08-29. It tracks configuration-cache state,
  not your suite, so don't match on the number. The one executed task IS
  `:concord:test`; three agents each spent a cross-check establishing that.

Four times out of four, what actually settled it was the JUnit `newest`
timestamp, which `junit-count.py` already prints. Read that first. Quote the
task-count line, but never let it be the answer on its own.

**1. The task-count line.** `N actionable tasks: X executed, Y from cache`
(or `up-to-date`) at the end of the run — the last line under
`--no-configuration-cache`, second-to-last in the default mode where
`Configuration cache entry reused.` follows it; `tail -3` catches both.

**2. The per-task state line — read it as an absence, and keep a log that
carries it.** This build prints `> Task :<module>:test` at the default log
level, and **a task that really executed prints with no marker at all** — so
grepping for `FROM-CACHE`/`UP-TO-DATE` returns nothing both when the task
ran and when the log never had task lines. Grep for the *task*, then read
what follows it. Four states, only two marked: `FROM-CACHE`, `UP-TO-DATE`,
no marker (it ran), no line at all (never in the graph, or the log lost it).

```bash
./gradlew :<module>:test --tests '<TestName>' > "$SCRATCH/run.log" 2>&1
grep -E '^> Task :<module>:test( |$)' "$SCRATCH/run.log"; tail -3 "$SCRATCH/run.log"
```

Two habits destroy that line while leaving `BUILD SUCCESSFUL` intact, so the
run still looks verifiable: **`| tail -N`** (measured: the task line sat 88
lines above the end of a 178-line run, so `| tail -30` drops it) and **`-q`**
(prints no task lines, no task-count line, and no `BUILD SUCCESSFUL` at
all). Redirect to a file and grep it. With only a truncated log, do not
claim this check — fall back to the task-count line plus the XML below, or
re-run with `--rerun` — **one `--rerun` per task name**: it binds to the task
it follows, so `:a:test :b:test --rerun` re-runs only `:b:test`
(computenet-jobe; [semantics](#--rerun-semantics) below).

**The console is not the suite, and test stdout is not in it at all.**
Gradle's console shows the tail of what it chose to print, never a suite
total: one implementer read `27 tests` off the tail of a `:oracle:test`
run whose XML held 408 across 45 files, and the undercount passed as "all
green" (computenet-ozgs). Counts come from `junit-count.py` below, nothing
else — not a hand-rolled XML regex: `<testcase>`/`<failure>` adjacency
reads failures as passes. And a `println` inside a test goes **nowhere** on this build —
`testLogging.showStandardStreams` is off — so a print probe, or a test whose
acceptance is "reports its own figure", reads as silence; its output is in
the XML's `<system-out>`: `grep -A3 '<system-out>' <module>/build/test-results/test/TEST-<Class>.xml`.

**3. The JUnit XML counts and timestamp**, which separate a run from a
replay — a cached repeat run leaves `newest` unchanged with identical counts
and a green build; `--rerun` advances it (measured 2026-08-14). Count with
the committed script rather than an inline program — and **count BEFORE any
filtered rerun**: `--tests '<filter>'` deletes that module's XML for every
class the filter did not match, so a 1209-result kernel run followed by one
narrow rerun leaves ~100 files, and a later sweep reports the module as thin
(computenet-5b34). A sharp drop between two reads is that signature, not a
shrunken suite. Capture the broad run's numbers into your report first.

**Two different faults produce "fewer JUnit files than I expected", so read
the count against both.** This one is DELETION by a filtered rerun; the other
is a broad run that never happened at all, because the same task appeared
twice on one command line (see `--rerun` semantics below, computenet-i4cq).
Deletion leaves the narrow run's files fresh and the rest gone; the duplicate
form leaves exactly the narrow run and no trace that anything else was asked
for.

(The script is executable and carries a `#!/usr/bin/env python3` shebang, so
the bare path below runs as written — two reviewers hesitated over that in one
session, neither having tried it; computenet-s16r.)

```bash
.claude/skills/work/scripts/junit-count.py '<module>/build/test-results'
# repo-wide: pass every module's build/test-results dir — demo/* included
# (*/build/test-results */*/build/test-results as two shell globs); the
# demo/* modules sit one level deeper, and a glob that misses them
# undercounted 496 for 586 with no visible sign. The script's own two
# depths are per results dir (files directly in it, and per-task subdirs);
# a module ROOT works too (v38r's build/test-results patterns cover it);
# only the checkout root matches nothing and exits NO-RESULTS.
```

It prints per-directory and total `tests/failures/errors/skipped` plus the
newest `timestamp`, refuses to report zero result files (`NO-RESULTS`, exit 4
— a glob matching nothing is indistinguishable from a passing empty suite,
computenet-wpvy.41), and prints the module list. A path it could not RESOLVE OR
READ is a different answer: `NO-SUCH-PATH`, exit 2, naming the cwd it resolved
against. That used to be NO-RESULTS, so one `..` too many reaching into a
sibling worktree read as "the suite did not run" for a suite that had run 489
tests (computenet-dh5x). **Neither code is evidence about a suite.**

**Read that list**: on a long-lived checkout `legacy/` and `runtime/` are stale build output
(AGENTS.md) and one fresh module's `newest` hides fourteen stale ones. If
`newest` is not from minutes ago, nothing here ran. The npm UI suites
(`inspect/ui`, `demo/agora/ui`) emit no JUnit XML and are invisible to this
count — their absence is not a wrong glob.

**Those timestamps are UTC. Your shell clock is not.** This host runs CEST
(UTC+2), so a run you performed *seconds* ago reads two hours old against
`date`, and the freshness check reaches its own opposite conclusion: that your
Gradle invocation silently did nothing and you are looking at leftovers. A
reviewer hit exactly that and had to work it out mid-report — `newest
2026-08-18T15:07:16.653Z` against a local clock reading 17:07 (computenet-8dtq).
The asymmetry is what makes it worth a line: misreading *fresh as stale* costs
a wasted re-run, or an agent debugging a build that works; the reverse error
this confusion cannot produce. Take the reference reading yourself, in UTC,
immediately before the Gradle call, and compare against that — then no timezone
reasoning is needed at all:

```bash
date -u +%Y-%m-%dT%H:%M:%S     # before the run; every XML timestamp must exceed it
```

Quote the numbers, the module count, and `newest` in your report. An
unquantified "suite green" is not a verification record, and nobody re-runs
it after you: your report *is* the evidence the next session trusts.

## Aggregate tasks lie about their members

`> Task :<module>:testClasses UP-TO-DATE` can print on a run where
`:<module>:compileTestKotlin` genuinely **executed**. `testClasses` is a
LIFECYCLE AGGREGATE: its own up-to-date state says nothing about the tasks it
depends on. At a glance it reads as "test sources were not recompiled", which
is exactly the wrong conclusion — here in the false-negative direction, making
a real mutation look like it never compiled. A reviewer had to grep the
per-task compile lines to disprove it (computenet-ymv4).

**Judge execution from the per-task `compile*`/`test` lines, never from an
aggregate.** Same for `:build`, `:check`, `:classes`, `:assemble`.

```bash
grep -E '^> Task :[^ ]*:(compileTestKotlin|compileKotlin|test)( |$)' "$SCRATCH/run.log"
```

This also means a repo-wide `./gradlew testClasses` reporting `79 up-to-date`
and `BUILD SUCCESSFUL` verifies **nothing** — measured twice in one session on
a task whose headline criterion was "every existing caller compiles unchanged"
(computenet-ukft). Force execution, or read the per-task lines and say which
ones carried no marker.

## Clearing results is denied to dispatched agents

`rm -rf <module>/build/test-results` — the obvious way to force a provably
clean `junit-count.py` — is refused by the permission classifier for a
dispatched agent. **The sanctioned substitute is the newest `timestamp`
`junit-count.py` already prints**: a cache replay leaves the previous run's
timestamp unchanged while `--rerun` advances it, which is the same
discrimination a clean directory would buy (computenet-ymv4).

## Measurements whose failure mode is a PASS

The cache traps above are one instance of a general shape, and the others are
not about Gradle at all. **A check whose failure mode is SILENCE must be shown
to print something on the branch where it should fail, before its silence is
read as an answer.** Two measured instances, both reported independently by
reviewers on 2026-08-19 (computenet-rf0a):

- **A per-line read of a multi-line dump under-reports it.** A Kotlin
  data-class `toString` spans newlines, so measuring a diagnostic dump PER LINE
  reads only its first line. A reviewer measuring whether `OracleSweep.describe()`
  spills a 200-event script concluded the violation dumps were *shorter* than
  the ordinary ones (145 vs 259 characters) — i.e. no problem. Re-measured per
  BLOCK: ~1.9–2.0 kB with the entire `Script(...)` inline, against ~0.57 kB and
  no script. An order of magnitude the other way. It caught this only because
  the number was implausible to it. **Treat an implausibly reassuring number as
  a measurement bug, not a result.**

- **A grep that never runs prints exactly what "no matches" prints.** Under
  zsh, `grep -rn 'foo' --include=*.kt .` dies with `no matches found:
  --include=*.kt` — the glob is expanded by the shell before grep sees it — so
  the grep NEVER RUNS and produces no hits. That is how the false premise
  ":oracle is a leaf that nothing depends on" survived two implementer reports
  and would have survived a review. **Quote the glob**: `--include='*.kt'`.
  This is a recurrence of computenet-l5rc, whose fix landed only in
  agent-execution.md, which orchestrators never read (computenet-u0b0) — it is
  now in AGENTS.md's zsh subsection as well, and here, so every reading chain
  reaches it.

## Cargo is not Gradle

None of the accounting above maps onto the `:iroh` cargo tasks
(computenet-9swr); do not read its absence as a gap in your review:

- `cargo test` re-executes its test binaries on every invocation — there is
  no cached-vs-executed accounting and no `--rerun` analogue. The suite's own
  `N passed; M failed` line IS the evidence; note a cold `target/` when it
  applies.
- cargo emits no JUnit XML, so `junit-count.py` and the timestamp check do
  not apply.
- Gradle `Exec` tasks resolve their command from the DAEMON's environment,
  not the invoking shell's: a stub `cargo` on the client PATH is silently
  ignored and the real suite passes — a false green, measured. To prove
  failure propagation, induce a genuine failure in the underlying tool
  instead of stubbing the binary.

## `--rerun` semantics

- **`--rerun` binds to the task it follows, not to the command line.**
  `:kernel:test :wire:test --rerun` re-ran only `:wire:test` while
  `:kernel:test` came back `UP-TO-DATE`, with both names on screen and
  `BUILD SUCCESSFUL` at the end. It also does not force upstream tasks. One
  `--rerun` per test task; `--rerun-tasks` for a repo-wide run.
- **The SAME task twice on one command line runs ONCE, under the FIRST
  invocation's `--tests` filter.** `./gradlew :m:test --tests 'FooTest'
  --rerun :m:test --rerun` does not run the filtered suite and then the whole
  suite — it runs the filtered suite, and the bare invocation never happens.
  Every visible signal says otherwise: `BUILD SUCCESSFUL`, one `> Task :m:test`
  line, green. Measured on `:testkit:test` — combined form 1 XML file / 5
  tests, standalone full run seconds later on the same tree 28 files / 226
  tests — and **the actionable-task line points the wrong way**: 24 actionable
  for the combined form against 15 for the standalone, so the anti-cache check
  this file teaches actively reassures while the evidence is a twentieth of
  what you think. `junit-count.py` cannot catch it either: the XML is fresh and
  green, there is simply less of it, and nobody knows the expected count by
  heart — and a low count here is ambiguous with the deletion fault above
  (computenet-5b34), so distinguish them rather than assuming either. Six
  agents across two sessions hit it (computenet-i4cq four, computenet-1z8s
  two), and the pressure that produces it is this skill's own:
  narrow-suite-plus-broad-suite is logically one verification step, and you are
  told to run verification in ONE foreground call. **Use two calls.** The
  non-regression half is the half that silently disappears.
- **`--rerun` on a LIFECYCLE task is very nearly a no-op**, and it looks
  exactly like correct usage. `./gradlew :oracle:test --rerun :concord:check
  --rerun` did not force `:concord:test`: a lifecycle task has no work of its
  own to rerun, so the `--rerun` binds correctly and reruns nothing. The
  console read plausibly and the only signal was `:concord`'s newest JUnit
  timestamp, still from the previous reviewer's run fifteen minutes earlier
  (computenet-1mjv). This is about the KIND of task, not the position of the
  flag — the position rule above was followed. `:concord:check` is named
  directly in several beads' acceptance clauses here, so name the concrete
  tasks instead: `:concord:test --rerun :concord:concordanceGate --rerun
  :concord:docLints --rerun :concord:check --no-build-cache`.
- **`--rerun` alone is not proof of execution.** Measured 2026-08-15 on
  `:concord:test`: an *unmarked* task line and `1 executed`, while the JUnit
  XML still held the previous run's 253 tests with older internal
  `timestamp` attributes under fresh file mtimes — a build-cache restore the
  marker did not show. Read the XML's content, never the file's mtime, and
  for any load-bearing run — a mutation check, a before/after comparison —
  add `--no-build-cache` alongside `--rerun`
  ([mutation-check.md](mutation-check.md) step 4, computenet-qsfu).

## A killed test task corrupts the results store

The 600000 ms foreground cap makes killing a Gradle run routine on this repo's
long suites, and a killed test task leaves a **truncated binary results store**
at `<module>/build/test-results/test/binary`. Every LATER run of that task then
dies inside `Test.getPreviousFailedTestClasses`, and the failure does not name
its cause — it reads as a broken build, not as leftover state:

- `java.io.EOFException` / a Kryo buffer underflow from
  `Test.getPreviousFailedTestClasses`
- `NoSuchFileException: in-progress-results-generic.bin` — what you get if you
  attempt the cleanup while a daemon still holds the directory, i.e. a *second*
  misleading error on top of the first

The cure is to remove `<module>/build/test-results` with no live daemon holding
it. Cost several minutes to diagnose the first time, and was then hand-carried
into four dispatch prompts in the same session because nothing recorded it
(computenet-r8zj). This is the other half of the cap from
[agent-execution.md](agent-execution.md)'s background-and-wait rule: that
covers an agent *stalling* on a long suite, this is what it leaves behind when
it *kills* one — and the more confusing half, because the damage surfaces on a
later, apparently unrelated run.

## How long the suites take

You need this **before** you choose foreground or background, and guessing
wrong costs a stall or a corrupted results store (both above). These are
measurements, not guarantees — machine-specific and they will drift, so each
carries its date and host. Treat an entry older than a few weeks as a hint
about *order of magnitude* only, and re-measure rather than quoting it as
current (computenet-ov2g).

| Suite | Time | Fits the 600000 ms cap? |
|---|---|---|
| `:kernel:test` | ~3–4 min (~40s with `--rerun` on a warm tree) | yes |
| `:demo:exchange:test` | well under the cap | yes |
| `:demo:beadsmirror:test` | **~11m45s** | **no** — background and wait |
| repo-wide `./gradlew test` | **~12m45s** | **no** — background and wait |

*Measured 2026-08-18 on MacBoo (Darwin arm64, 16 cores), warm daemon.*
CI, for comparison: `build-test-fast` ~8m, `kernel-test` ~3–5m,
`concord-full` ~2.5m (Linux runners).

## Contention from this skill's own parallelism

A run that stalls, times out, or dies before your tests run is probably a
sibling agent, not a defect you introduced: sibling task and review agents
drive Gradle concurrently against shared caches and daemons. Two observed
signatures: a run lost to `buildLogic.lock` after a 4-minute wait, and a
Kotlin-daemon `OutOfMemoryError` from daemons left resident by a build in a
*different* directory. `pkill -f KotlinCompileDaemon` clears the second —
but it is **machine-wide** (a daemon's command line carries no project path)
and takes a sibling's in-flight compile with it, so fire it on that
signature only, never on a red build generally. Then retry once, and say in
your report which signature you hit.

A **wall-clock timeout** can be contention too: `awaitUntil`/`awaitDrained`
raise `AssertionFailedError` when a starved host makes no progress
(2026-08-11: three suites timed out under load, passed in 78s quiet). A
wrong *value* is never contention — that one is yours. A red suite in a
module your diff never touched is more often the opposite: your edit
invalidated that module's cache, so it executed instead of replaying and
exposed a latent flake (PR #27).

## Rare-failure evidence

If a run's *failure* is what matters — a flake hunt, a repetition loop — do
not pass `-q`, and do not let the next iteration overwrite
`<module>/build/test-results`: the JUnit XML is the only place the
suppressed exception and pre-interrupt thread dump live.

Don't hand-roll the loop either. `scripts/flake-loop/` drives the JUnit
Platform in-process over a package selector — one iteration costs seconds
instead of Gradle's ~40s, every *failing* iteration gets its own append-only
file under `<out>/failures/`, and it refuses to start when iteration 1's
executed count is 0 (a sample where nothing ran cannot be read as a sample
where nothing failed). Invocation, the `SUMMARY` fields to quote, and the
two cases where a Gradle loop is still right — a flake needing a fresh JVM
per iteration (rates differ: 0.83% fresh-JVM vs 0.26% in-process on the same
flake), or a suite not selectable as a JUnit package (`:concord`, the npm UI
suites) — are in [review-task.md](review-task.md) §2, with the archive-then-
rerun form for the Gradle case.
