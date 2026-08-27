# The BEN1 regression-tracking series

**Status**: Living. **Filed as** `computenet-b7k4`, under epic `computenet-x9e` (BEN1).

`doc/bench/findings.md` is a record of **one-shot measurements**. This document
describes the other thing BEN1 needs and did not have: the **same** benchmarks,
on **one** pinned machine and toolchain JDK, run repeatedly, with each run
compared against that benchmark's own accumulated history rather than against an
absolute floor.

---

## Contents

1. [Why a series, and what it is not](#1-why-a-series-and-what-it-is-not)
2. [The storage decision](#2-the-storage-decision)
3. [The pinned environment](#3-the-pinned-environment)
4. [The comparator: what "beyond the band" means](#4-the-comparator-what-beyond-the-band-means)
5. [The run path](#5-the-run-path)
6. [Scheduling](#6-scheduling)
7. [Why this is not a GitHub Actions workflow](#7-why-this-is-not-a-github-actions-workflow)
8. [The series is EMPTY at the commit that introduces it](#8-the-series-is-empty-at-the-commit-that-introduces-it)
9. [What this lane does not do](#9-what-this-lane-does-not-do)
10. [Two operational gotchas: a relative `--jar`, and a bench test that prints
    nothing to the console](#10-two-operational-gotchas-same-shape-computenet-x9e15)

---

## 1. Why a series, and what it is not

The 2026-08-21 findings review named the failure mode directly: one-shot absolute
numbers on developer laptops proved noisy and environment-fragile. Two entries in
`findings.md` were superseded by a JDK-vendor substitution. M2 Pro and M3 Max
entries are not comparable to each other. Quiesced-host discipline was manual and
therefore sometimes absent.

None of those is a measurement error. They are all the same structural problem:
**a single number carries no information about what that number normally is on
that machine.** A series fixes that by measuring the same quantity repeatedly
under a pinned environment, so that "is this run different?" becomes a question
the data can answer.

What a series is **not**:

- **Not a performance gate.** Nothing here fails a build, and nothing here runs
  inside a required CI check — see §7 and §9.
- **Not an absolute floor.** A band is what this benchmark has actually done on
  this machine; it says nothing about whether that is fast enough.
- **Not a claim that unmoved means unregressed.** A `WithinBand` verdict means
  the data does not resolve a difference. A regression smaller than the band is
  invisible to it, and the comparator's own KDoc says so where it is defined.

## 2. The storage decision

The ticket left storage open between three options: the repository, CI artifacts,
or a data branch. **The decision is: in the repository.**

```
bench/series/series.csv          the append-only series index — one row per benchmark per run
bench/series/runs/<runId>/       the raw JMH artifacts that row was derived from
        <selector>.csv               the -rf csv results file
        <selector>.log               the teed stdout — the ONLY record of JVM, knobs and host
```

Why the repository, and why not the other two:

- **The comparator has to read the history to do its job, and it runs where the
  benchmark runs.** A locally-run sweep on a pinned laptop cannot reach a CI
  artifact store without credentials and a network round trip, and the whole lane
  is deliberately local (§7). History in the working tree is history the tool can
  always read.
- **CI artifacts expire.** GitHub's default retention is finite and configurable;
  a tolerance band whose evidence silently ages out is a band nobody can audit.
  Artifacts remain a fine *backup* — they are simply not a substrate a
  months-long series can rest on.
- **A data branch buys separation and costs traceability.** Its appeal is keeping
  `main`'s history clean. But the single most useful property of an in-repo
  series is that a row and the `harnessCommitSha` that produced it are in the
  *same* history: `git log bench/series/series.csv` interleaves with the commits
  that changed the code being measured. A data branch severs that, and adds a
  push target, a merge policy and a failure mode to a lane whose entire value is
  that it is boring.
- **The size is not a concern at this scale.** A series row is ~250 bytes. A raw
  JMH CSV for one selector is a few KB. A daily run of a handful of selectors is
  on the order of a megabyte a year. If the selector set ever grows to where that
  stops being true, the honest response is to prune the *selector set* — a series
  is only worth keeping for quantities somebody will act on — not to move the
  storage.

**The series file is append-only**, for the same reason `findings.md` is: a series
whose past entries can be revised is one in which an inconvenient run quietly
stops existing, and then the band means nothing. `SeriesCsv.append` never rewrites
a line. Removing an entry is a deliberate, reviewed edit by a human, visible in
the pull request that does it.

## 3. The pinned environment

Every series row carries its full `RunEnvironment`, read off the run's own JMH log
by `MeasuringJvm.fromJmhLog`, `RunKnobs.fromJmhLog` and `HostFacts.fromJmhLog` —
never captured from the ingesting process. (Two `findings.md` entries shipped with
the *rendering* JVM in place of the measuring one; both had to be corrected by
later entries. For a series the stake is higher, because a wrong environment does
not merely mislabel a row — it puts the row in the wrong population.)

`SeriesEntry.environmentFingerprint` is the subset that must **match** before two
rows may be compared:

| Fingerprint field | Pinned to | Why it is in the fingerprint |
| --- | --- | --- |
| `cpuModel`, `coreCount`, `os` | one machine | The M2 Pro / M3 Max incomparability this ticket was filed from. |
| `jvmVendor`, `jvmVersion` | the module's toolchain JDK 21 — **major version enforced by `run-series.sh`**, vendor and patch level recorded, not enforced | The JDK-vendor substitution that superseded two entries. |
| `heapSettings` | whatever the forks were launched with, stated | Heap configuration moves scores directly. |
| `jmhMode`, `forkCount`, `warmupIterations`, `measurementIterations` | `-f 5 -wi 5 -i 5`, written down in `run-series.sh` | `-f`/`-wi`/`-i` override annotations, so annotations state the declaration, not the run. |

The JDK pin is **checked, not assumed**. `run-series.sh` launches the JMH jar with
`BENCH_SERIES_JAVA` (default `java`) and refuses the run when that launcher is not
JDK 21 — measured on this repository's own host, 2026-08-22, bare `java` was JDK
25.0.2 while the toolchain is 21. The refusal matters because the failure it prevents
is the *reassuring* one: a run under the wrong JDK does not corrupt an existing band,
it lands in a fresh population and answers `InsufficientHistory` forever, which reads
as a young series rather than a misconfigured lane. Under the scheduler of §6 — where
a `launchd` agent does not inherit a login shell's `PATH` — that is the likeliest way
for the wrong `java` to be picked up, and nobody is watching when it happens.

**What that check does and does not cover.** It parses the *major* version out of
`<launcher> -version` and refuses anything but 21; it does not check the vendor or the
patch level, so Corretto 21.0.5 and Microsoft OpenJDK 21.0.11 both pass it while
producing two different fingerprints. That is deliberate rather than a hole: `jvmVendor`
and `jvmVersion` are read off the run's own JMH banner at ingest, so a vendor or
patch-level swap is *recorded* and starts a fresh population exactly as this section
describes — visibly incomparable, never silently averaged in. What the check adds is the
one case that needed catching in advance, a whole major version off the toolchain. The
check also fails closed on an unparseable banner: a JDK 21 launcher whose first
`-version` line is `Picked up JAVA_TOOL_OPTIONS: …` is refused rather than accepted.

`harnessCommitSha` is deliberately **excluded** — it changes on every commit, and
it is the thing the series exists to vary. Including it would make every run its
own incomparable population.

Changing any pinned knob **starts a fresh population** rather than corrupting the
existing one: the comparator simply reports `InsufficientHistory` until three runs
accumulate under the new fingerprint. That is intended behaviour, not a bug.

### The quiescence attestation

Each row carries a `hostState` of `QUIESCED` or `SHARED`. It is an **attestation
by whoever ran the sweep**, not a measurement — nothing can prove a host was idle
after the fact.

`run-series.sh` performs a **one-directional** sanity check: it refuses to record a
run as `quiesced` when the 1-minute load average exceeds a quarter of the core
count. It can catch a wrong `quiesced` claim; it can never confirm a right one.

`SHARED` rows are **retained as observations and excluded from band formation**
(`HistoricalBand.of`). Refusing to record such a run at all would push people to
mislabel it; recording it, marked, keeps the observation while keeping it out of
the band.

## 4. The comparator: what "beyond the band" means

**A band** (`HistoricalBand`) over one measurement's `QUIESCED` history:

- `centre` = the **median** of the scores. Median, so one pathological run moves
  the centre by one position rather than by its own magnitude.
- `runToRunHalfWidth` = `(max - min) / 2`.
- `worstWithinRunError` = the largest 99.9% error bar any contributing run reported.
- `halfWidth` = **max** of those two. Not their sum, which would double-count
  overlapping quantities; not the run-to-run term alone, which for three unusually
  consistent runs would claim more resolution than any member had.
- `MIN_BAND_ENTRIES = 3`. One entry has no run-to-run spread at all; two have a
  distance between two points, which estimates nothing. Fewer than three yields
  **no band**, reported as `InsufficientHistory` — which is explicitly **not** a
  pass.

**The criterion is `computenet-785b`'s, with history on one side.** This is the
part that matters most, so it is stated plainly: "beyond the band" is *not* a
second rule sitting next to the effect-size rule the 2026-08-22 findings entry
derived. It is **that** rule, applied with the band as the right-hand side:

```
|run.value - band.centre| > COMBINED_ERROR_MARGIN × (run.dispersion + band.halfWidth)
```

The arithmetic is not restated in the series code. `SeriesComparator` calls
`civictech.bench.resolveEffect(effect, combinedError)` — the magnitude overload
added to `Dispersion.kt` in this same change, which the two-row
`resolveEffect(left, right)` also delegates to. There is one definition of "beyond
the error bars" in this repository, and `BandTest` pins the agreement row for row.
If `COMBINED_ERROR_MARGIN` or the strictness of the comparison is ever changed,
the series comparator changes with it, in the same commit.

The overload exists because a band has **no single measurement** behind it, and so
no honest `RunEnvironment` and no `Drive`; constructing a synthetic `BenchResult`
to reach the two-row form would mean inventing an environment for a row that was
never measured.

**Verdicts are directional and unit-agnostic.** `MovedHigher` on `ops/s` is an
improvement; on `ns/op` it is a regression. The comparator is not told which it is
holding, and does not guess.

## 5. The run path

```bash
scripts/bench-series/run-series.sh --host-state quiesced
```

It builds `:bench:jmhJar`, runs each pinned selector (one JMH invocation each —
`RunKnobs.fromJmhLog` refuses a log stating a knob two different ways, and this
module's benchmark classes declare three different modes), tees each run's stdout
beside its results file, then compares and appends through the `:bench:benchSeries`
Gradle task.

Useful flags: `--dry-run` (prints the plan, runs nothing), `--no-append` (compare
without recording), `--selector <name>` (one selector only). Set
`BENCH_SERIES_JAVA=/path/to/jdk21/bin/java` when `java` is not the pinned JDK; the
script prints the measuring JVM it resolved and refuses any other major version (§3).

The comparator can also be driven directly:

```bash
./gradlew :bench:benchSeries -PseriesArgs="--help"
./gradlew :bench:benchSeries -PseriesArgs="compare --results <csv> --series bench/series/series.csv \
    --run-id <id> --timestamp <iso8601> --host-state quiesced --harness-sha <sha>"
```

`compare` and `append` are separate subcommands so that the comparison is always
computed against the state **before** the run is folded in. A single `record`
command that appended first would have every run sitting inside its own band.

**Movement is reported, never signalled by an exit code.** The tool exits `0` on a
successful run whether or not anything moved, and `1` only on a refusal (a missing
artifact, a bannerless log, a malformed series file). Making movement a non-zero
exit would turn the lane into a gate, which this ticket's scope excludes.

### Where the raw results go, and the commit convention

`bench/series/runs/<runId>/` is tracked. Commit the run directory and the appended
series row **together**, so a row and the artifacts it was derived from land in one
commit and a later reader can re-derive the row.

## 6. Scheduling

The lane is driven by a **local scheduler on the pinned machine**, which is one of
the two options the ticket named. Nothing is installed by this change: installing
a scheduler on someone's laptop is their decision, not a repository's.

On macOS, a `launchd` agent is the mechanism. **I believe** the following plist
shape schedules a nightly run, and **it has not been exercised** — no such agent
was loaded on any machine as part of this work, so treat it as a starting point to
verify rather than as a tested recipe:

```xml
<!-- ~/Library/LaunchAgents/net.computenet.bench-series.plist — UNVERIFIED, see above -->
<dict>
  <key>Label</key>            <string>net.computenet.bench-series</string>
  <key>ProgramArguments</key>
  <array>
    <string>/bin/bash</string>
    <string>/absolute/path/to/computenet/scripts/bench-series/run-series.sh</string>
    <string>--host-state</string><string>quiesced</string>
  </array>
  <!-- Not optional here: a launchd agent's PATH is not a login shell's, and the
       script refuses the run outright when its launcher is not JDK 21 (§3). -->
  <key>EnvironmentVariables</key>
  <dict>
    <key>BENCH_SERIES_JAVA</key><string>/absolute/path/to/jdk21/bin/java</string>
  </dict>
  <key>StartCalendarInterval</key>
  <dict><key>Hour</key><integer>4</integer><key>Minute</key><integer>0</integer></dict>
  <key>StandardOutPath</key>  <string>/tmp/bench-series.log</string>
  <key>StandardErrorPath</key><string>/tmp/bench-series.err</string>
</dict>
```

Two things to check before trusting it, both of which are why the belief is
labelled: whether `launchd` runs the job at all when the machine is asleep at the
scheduled hour (and whether it then fires late on wake), and whether the job's
environment has `git` and a JDK on `PATH` — a `launchd` agent does not inherit a
login shell's environment. The second of those is why `BENCH_SERIES_JAVA` is set
in the plist rather than left to `PATH`: without it the nightly run does not
mis-measure, it *refuses* — every night, into `/tmp/bench-series.err`, where
nobody is looking. Whoever installs this agent should run the script by hand once
under the same environment first and see the `Measuring JVM:` line name a JDK 21.

The `--host-state quiesced` in that plist is doing real work: a scheduled 04:00 run
is the case where quiescence is most plausible and least observed, and the script's
load-average check is what refuses the run if the machine turns out to be busy.

## 7. Why this is not a GitHub Actions workflow

The ticket permitted either `workflow_dispatch` or a local scheduler. **No workflow
was added**, and the reason is the ticket's own central requirement:

> the same benchmarks, **one pinned machine and toolchain JDK**

A GitHub-hosted runner is not a pinned machine. It is a fresh VM of a declared
class, co-tenanted, with no guarantee about the physical CPU, and its performance
variance is well in excess of the effects this series exists to detect. A series
run on hosted runners would produce bands so wide that nothing would ever move,
which is the *reassuring* failure — the one that looks like it is working.

A self-hosted runner on the pinned machine would satisfy the requirement, and is
the natural future step if this lane ever needs to be triggered from GitHub. It is
out of scope here: no such runner exists for this repository, and adding one is an
infrastructure decision with its own security posture.

Consequently **`.github/workflows/` is untouched by this change**, and the
acceptance criterion "no required CI check runs a benchmark" holds by
construction rather than by argument. See §9 for how that was verified.

## 8. The series was EMPTY at the commit that introduces it — seeded 2026-08-26

**Current state: seeded.** `bench/series/series.csv` holds three QUIESCED rows for
`SmokeBenchmark.baseline` and the comparator reports a band. Jump to §8.1 for the
band and what a fourth run has to match; the rest of this section is the reasoning
that kept the file empty until then, retained because it is the argument, not a
status line.

At the commit that introduced this lane, `bench/series/series.csv` contained its
header and no rows. **That was deliberate, and it was the honest state.**

The machine available when this lane was built was demonstrably not quiesced. At
the time of writing: 16 cores, 1-minute load average 4.98 (the script's own check
refused a `--host-state quiesced` run against it, which is how the number above was
obtained), Microsoft Defender's scanner at ~100% of a core, and two other agent
sessions live on the host.

A first entry measured under that interference would not be a slightly worse
baseline. It would be a **poisoned** one: it is the seed of the band every later
run is judged against, so a centre pulled by interference silently reclassifies
healthy runs as movement and real movement as healthy. That is strictly worse than
an empty series, because an empty series says `InsufficientHistory` — which is
visibly not a pass — while a poisoned one says `WithinBand`.

**What would have to be true to seed it:**

1. The pinned machine is genuinely idle — no other agent session, no build, no
   scheduled scan (Defender's included), no user at the keyboard.
2. `scripts/bench-series/run-series.sh --host-state quiesced` completes without the
   load-average refusal firing.
3. That is repeated **three** times (`MIN_BAND_ENTRIES`), because one run and two
   runs form no band. Three runs of the current single-selector set is roughly
   3 × 5 forks × 10 iterations × 10 s ≈ 45 minutes of wall clock, plus jar builds.

Until then, the lane is complete and exercised — the comparator, the codec, the
refusals and the script's own guard are all covered by tests in
`bench/src/test/kotlin/civictech/bench/series/` — and the series simply has no
rows. Seeding it is filed as its own beads item under `computenet-x9e`.

### 8.1 Seeded, 2026-08-26 (`computenet-0nww`)

All three conditions above were met between 01:18 and 02:14 local on the pinned
host, and the three runs were made. The full account — every host reading before
and after each run, the attestation and the interpretation it rests on, and two
observed confounds — is the `doc/bench/findings.md` entry *"the regression-tracking
series is seeded"*. Only what a later run needs is repeated here.

```
SmokeBenchmark.baseline (avgt, ns/op)   centre 3.976441   half-width 0.07488   samples 3
```

The half-width is `max(runToRunHalfWidth, worstWithinRunError)` and here the second
term wins (0.07488 against 0.01914): the three runs agree more closely than any one
of them claims to resolve, so the band is the wider, honest one. §4's `WithinBand`
caveat applies with full force at this width.

**Three things a fourth run must match, or it starts a fresh population rather than
extending this band** (the failure is silent — it reads as `InsufficientHistory`,
i.e. a young series, not as a misconfigured one):

- `BENCH_SERIES_JAVA` set to **Amazon Corretto 21.0.5** specifically. Bare `java` on
  this host is JBR 25.0.2 and is refused outright, which is the safe failure; the
  unsafe one is a *different* JDK 21 — Microsoft 21.0.11 is also installed here —
  which passes the script's major-version check and lands in its own population.
- The same host, `NL-MGD6FQJW91` (Apple M3 Max, 16 cores, Mac OS X 26.6.2).
- The pinned knobs unchanged: `-f 5 -wi 5 -i 5`. Note that condition 3 above
  describes the wall clock as `10 iterations × 10 s`; the knobs the lane actually
  pins are 5 warmup and 5 measurement iterations, so a run is nearer 7 minutes than
  15. The ~45-minute figure for three runs still held, because the gaps between runs
  dominate — see the next point.

**Do not launch the next run immediately after the previous one.** The 1-minute load
average still carries the finished sweep, so the guard reads load the measurement
itself created and refuses an attestation that is true. On this host the load also
oscillates between roughly 3.0 and 5.2 on a two-minute cycle while otherwise idle, so
a single `uptime` reading is weak evidence in either direction; runs 2 and 3 were
launched by polling for a genuine trough rather than by a one-shot reading. The
script's guard reads the load **once, at start**, and cannot protect against a spike
at minute 20 — that limitation is unchanged.

## 9. What this lane does not do

- **It does not gate anything.** `:bench:benchSeries` is a `JavaExec` task that is
  not a dependency of `check`, `build`, `test`, or any other lifecycle task —
  exactly as `:bench:jmh` and `:bench:jmhJar` are unreachable from the lifecycle
  [BEN1-01]. The tool itself only *reads* JMH artifacts a separate run produced; it
  launches no benchmark and forks no JVM. Both properties hold: the guarantee is
  that no task depends on it, and the fact that it could not run a benchmark even
  if one did is defence in depth.
- **It does not touch the required CI checks.** The six required contexts —
  `build-test-fast`, `build-test-serial`, `concord-full`, `ui-test`,
  `agora-ui-test`, `kernel-test` — are unchanged, because no file under
  `.github/workflows/` is modified by this change. That is checkable with
  `git diff --stat <base>..HEAD -- .github/` returning nothing.
- **It does not write to `doc/bench/findings.md` automatically.** A series run
  prints a report. Turning a movement into a finding is a human act, and it goes
  through `Findings.entry` like every other entry in that file.
- **It does not decide whether movement is good.** See §4.

## 10. Two operational gotchas, same shape (`computenet-x9e.15`)

Both of these look like the tool failed. It didn't — it behaved correctly and said
nothing useful, so the natural reading is "my invocation was wrong" when the real
problem is somewhere else entirely. Both cost a different agent a full pass of
misdiagnosis in one session (2026-08-27).

**A relative `--jar` to `:bench:floorTool` is resolved against `bench/` first, then
against the repo root.** `:bench:floorTool`'s `JavaExec` runs with the `bench/`
*project* directory as its working directory, not the directory `./gradlew` was
invoked from. `--jar bench/build/libs/bench-jmh.jar` — exactly what this tooling's
own usage text and `derive-class-floor.sh`'s echoed commands print, and so exactly
what anyone will copy — used to resolve straight into a doubled
`bench/bench/build/libs/bench-jmh.jar` that does not exist. `JarPath.resolve`
(`FloorTool.kt`) now tries the working directory first and falls back to the repo
root (found by walking up to the nearest `settings.gradle.kts`) before refusing, so
the copied command works either way; a genuine miss names every absolute path it
tried. This matters more than an ordinary path typo because floorTool's refusals
are the thing under test in several BEN1 items — a wrong `--jar` used to produce a
refusal that read exactly like the refusal being deliberately triggered, so it
masked the result instead of announcing the mistake (nine refusals were read as
findings before the doubled segment was noticed). An absolute `--jar` always
bypasses both attempts and remains the safest choice for a script.

**A `@Tag("bench")` test's `println` output does not reach the Gradle console.**
Every BEN1 sampling/rendering test's deliverable is a printed tally or table (see
`CellFootprintProbeTest`, `ThroughputReportTest`'s render entry points, etc.).
Gradle's `test` task does not forward a test JVM's stdout to the console by
default, so running one of these under `-PbenchOnly=true --tests '<Name>'` prints
only `<Name> PASSED` — the tally is not merely truncated, it never appears at all,
and the natural conclusion is that the invocation produced nothing. The tally is
still there: it is captured as `system-out` inside the JUnit XML at
`bench/build/test-results/test/TEST-<fully.qualified.Name>.xml`. Gradle writes a
**single** `<system-out>` element per XML file — one per test *class*, not one per
test method — holding everything that class's tests printed, in run order (measured
2026-08-27: `TEST-civictech.bench.micro.CellFootprintProbeTest.xml`, 2 `<testcase>`
elements, 1 `<system-out>`). That is why taking the first one below is enough; if you
need to attribute output to a particular method, the class's own banner text is the
only separator. Read it with
```
python3 -c "import xml.etree.ElementTree as ET; \
  r = ET.parse('bench/build/test-results/test/TEST-<fully.qualified.Name>.xml').getroot(); \
  print(next(r.iter('system-out')).text)"
```
or any other tool that parses the JUnit XML, rather than re-running the test and
scrolling the console. A code fix (`testLogging { showStandardStreams = true }` on
the `Test` task) was considered, but the tag-gating and task configuration for
every module's `test` task — including `:bench:test` — live in the single shared
`buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`, not in `bench/build.gradle.kts`;
turning that on there would echo every test's stdout across every module's default
`./gradlew test`, not just a deliberately-invoked `@Tag("bench")` run. That is a
bigger and noisier change than this note, so it is left as a documented workaround
here rather than made unilaterally.
