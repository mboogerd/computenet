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
| `jvmVendor`, `jvmVersion` | the module's toolchain JDK 21 | The JDK-vendor substitution that superseded two entries. |
| `heapSettings` | whatever the forks were launched with, stated | Heap configuration moves scores directly. |
| `jmhMode`, `forkCount`, `warmupIterations`, `measurementIterations` | `-f 5 -wi 5 -i 5`, written down in `run-series.sh` | `-f`/`-wi`/`-i` override annotations, so annotations state the declaration, not the run. |

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
without recording), `--selector <name>` (one selector only).

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
login shell's environment.

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

## 8. The series is EMPTY at the commit that introduces it

`bench/series/series.csv` contains its header and no rows. **This is deliberate,
and it is the honest state.**

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
