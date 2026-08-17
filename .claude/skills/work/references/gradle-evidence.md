# Gradle evidence: proving a test run happened

The single copy of the cache-accounting rules. task.md step 6,
review-task.md §2 and review-feature.md §3 all point here; each keeps only
what is specific to its role. Cited beads carry the full incidents
(`bd show <id>`).

## Contents

- [Why `BUILD SUCCESSFUL` proves nothing](#why-build-successful-proves-nothing)
- [The three signals to consume, per run](#the-three-signals-to-consume-per-run)
- [`--rerun` semantics](#--rerun-semantics)
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
re-run with `--rerun`.

**3. The JUnit XML counts and timestamp**, which separate a run from a
replay — a cached repeat run leaves `newest` unchanged with identical counts
and a green build; `--rerun` advances it (measured 2026-08-14). Count with
the committed script rather than an inline program:

```bash
.claude/skills/work/scripts/junit-count.py '<module>/build/test-results'
# repo-wide: pass each module root, or the checkout root — the script
# globs BOTH depths itself (demo/* modules are nested one level deeper;
# a single-depth glob undercounted 496 for 586 with no visible sign)
```

It prints per-directory and total `tests/failures/errors/skipped` plus the
newest `timestamp`, refuses to report zero result files (`NO-RESULTS`,
exit 4 — a glob matching nothing is indistinguishable from a passing empty
suite, computenet-wpvy.41), and prints the module list. **Read that list**:
on a long-lived checkout `legacy/` and `runtime/` are stale build output
(AGENTS.md) and one fresh module's `newest` hides fourteen stale ones. If
`newest` is not from minutes ago, nothing here ran. The npm UI suites
(`inspect/ui`, `demo/agora/ui`) emit no JUnit XML and are invisible to this
count — their absence is not a wrong glob.

Quote the numbers, the module count, and `newest` in your report. An
unquantified "suite green" is not a verification record, and nobody re-runs
it after you: your report *is* the evidence the next session trusts.

## `--rerun` semantics

- **`--rerun` binds to the task it follows, not to the command line.**
  `:kernel:test :wire:test --rerun` re-ran only `:wire:test` while
  `:kernel:test` came back `UP-TO-DATE`, with both names on screen and
  `BUILD SUCCESSFUL` at the end. It also does not force upstream tasks. One
  `--rerun` per test task; `--rerun-tasks` for a repo-wide run.
- **`--rerun` alone is not proof of execution.** Measured 2026-08-15 on
  `:concord:test`: an *unmarked* task line and `1 executed`, while the JUnit
  XML still held the previous run's 253 tests with older internal
  `timestamp` attributes under fresh file mtimes — a build-cache restore the
  marker did not show. Read the XML's content, never the file's mtime, and
  for any load-bearing run — a mutation check, a before/after comparison —
  add `--no-build-cache` alongside `--rerun`
  ([mutation-check.md](mutation-check.md) step 4, computenet-qsfu).

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
