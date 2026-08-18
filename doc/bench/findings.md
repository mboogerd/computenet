# Benchmark findings

**Status**: Living

Measured results from `:bench`, one entry per measurement, oldest first
(`[BEN1-30]`). New entries are **appended at the end**; nothing above the
insertion point is edited, reordered, or deleted.

Append-only is the whole point of the file, not a filing convention. A findings
file whose past entries can be revised is a file in which an inconvenient
measurement can quietly stop existing, and then no later reader can tell whether
a number was derived or chosen. If a later measurement contradicts an earlier
one, **append the contradiction** — say which entry it contradicts and what
changed — rather than correcting the earlier entry.

Entries are rendered through `civictech.bench.Findings.entry`
(`bench/src/main/kotlin/civictech/bench/Findings.kt`), which refuses an entry
that is incomplete, that carries a result too dispersed to report against
`NOISE_FLOOR` (`[BEN1-25]`), or that cites a gap without stating exactly one of
FIRES / RETIRES / INCONCLUSIVE (`[BEN1-31]`). An entry answering no gap trigger
question is rendered explicitly **MARKED INCOMPLETE** and is not a finding
(`[BEN1-32]`).

The `**Status**` line above uses the vocabulary
`concord/src/main/kotlin/civictech/concord/lint/DocLints.kt` enforces for
`doc/spec`. `docLints` scans only `doc/spec`, so for this file the line is a
courtesy per the epic decision (`[BEN1-34]`) — spelled the enforced way so that a
later widening of that scan finds it already compliant.

---

## 2026-08-18 — SmokeBenchmark.baseline noise-floor calibration - NOISE_FLOOR provenance
Harness: cbea02900f695fe156a1b94cdf77c60be9781f10 · JVM Eclipse Adoptium/21.0.11 · heap maxHeapBytes=4294967296 · Apple M2 Pro, 10 cores, Mac OS X 26.6.1
JMH: mode=AverageTime forks=5 warmup=5 iters=5 · drive=REAL
| subject | value | notes |
| --- | --- | --- |
| run 1 | 4.321050323941347 ± 0.004992364297944783 ns/op | |
| run 2 | 4.32487047304117 ± 0.010675229190884424 ns/op | |
| run 3 | 4.31976862172609 ± 0.0032949299161283406 ns/op | |
Trigger: none cited — entry MARKED INCOMPLETE, not presented as a finding

### Reading the entry above

Everything between the `##` heading and the `Trigger:` line is `Findings.entry`'s
output, pasted verbatim — the three `BenchResult`s were constructed from the raw
JMH output below, put in a `FindingsTable` labelled `"run 1"`/`"run 2"`/`"run 3"`,
and rendered. The renderer accepted them, which is itself the check that all three
classify `Reportable` against the `NOISE_FLOOR` this entry derives: `Findings.entry`
refuses the whole entry on the first `Unreportable` result, and the render was
executed against a `:bench` build carrying `NOISE_FLOOR = 0.005`, not the
provisional value it replaced. (This entry was re-rendered through
`Findings.renderTable`'s fixed writer on `computenet-x9e.3.4`, after a feature review
found the table it originally shipped with mislabelled its own results; the JMH runs
were not repeated — only the render changed, from the same scores and errors below.)

One thing the renderer's table shape does not say, so it is said here:

- **`drive=REAL` is literal.** JMH forks five real JVMs and measures on real
  threads against the real system clock. No `SimWorld`, no `SimulationController`
  and no virtual time is involved anywhere in this measurement (`[BEN1-26]`);
  nothing here was driven by the simulation harness.

### What was measured, and how

`civictech.bench.micro.SmokeBenchmark.baseline` — the permanent discovery
sentinel, a deterministic branch-free bit mixer. It is deliberately the cheapest
and most repeatable thing this repository can measure, because the quantity being
established is the harness's own noise, not the cost of any operation.

Procedure: `./gradlew :bench:jmhJar` **once**, then **three sequential
executions** of the built jar —
`java -jar bench/build/libs/bench-jmh.jar SmokeBenchmark -rf json -rff runN.json`
— not `./gradlew :bench:jmh`, so that no Gradle daemon shares the host with the
forks. The JVM was the module's declared toolchain (Temurin 21.0.11+10-LTS at
`~/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x.2`), launched with no VM
options. JMH 1.37 defaults applied throughout: `Mode.AverageTime`, ns/op, 5 forks
x (5 warmup + 5 measurement) iterations x 10 s, 1 thread, compiler blackholes.
Wall clock 08:31:24Z to 08:56:28Z, about 8m 20s per run. The host was quiesced
for the measurement: no concurrent Gradle build, test suite or benchmark run.

Raw JMH summary lines, verbatim:

```
Benchmark                Mode  Cnt  Score   Error  Units
SmokeBenchmark.baseline  avgt   25  4.321 ± 0.005  ns/op     (run 1)
SmokeBenchmark.baseline  avgt   25  4.325 ± 0.011  ns/op     (run 2)
SmokeBenchmark.baseline  avgt   25  4.320 ± 0.003  ns/op     (run 3)
```

Full precision from JMH's own JSON output, and the relative dispersion
(`error / score`) each one implies:

| run | score (ns/op) | error(99.9%) (ns/op) | relative dispersion |
| --- | --- | --- | --- |
| 1 | 4.321050323941347 | 0.004992364297944783 | 0.0011554 |
| 2 | 4.324870473041170 | 0.010675229190884424 | 0.0024683 |
| 3 | 4.319768621726090 | 0.003294929916128341 | 0.0007628 |

The runs did not disagree. Their scores span 0.0051 ns/op — a run-to-run relative
spread of 0.0012, the same order as the within-run errors, which is the check
that a single run's error bar is not badly understating the variation between
runs. Run 2's wider error comes from one fork mean at 4.3459 ns/op against four
near 4.319; per-fork means are 4.3192 / 4.3188 / 4.3180 / 4.3459 / 4.3225. That
is ordinary fork-to-fork variation — one fork's JIT landing differently — and not
the signature of another process competing for the host, which would have moved
the whole run's score rather than one fork's.

### Derivation of NOISE_FLOOR

**Observed noise floor** = the maximum relative dispersion across the three runs
= **0.0024683** (run 2).

**Threshold** = 2 x the observed floor, rounded up to three decimals =
**`NOISE_FLOOR = 0.005`** (`bench/src/main/kotlin/civictech/bench/Dispersion.kt`).
A `BenchResult` whose `error / score` exceeds 0.005 classifies `Unreportable`,
and `Findings.entry` refuses to render an entry containing one (`[BEN1-25]`).

The derivation runs forward, and the order is the point. The 2x margin was fixed
and recorded as a comment on `computenet-x9e.3.3` **before the first run reported
a number**, so that it could not be reverse-engineered from one; the observation
then determined the value, rather than a desired value determining what counted
as the observation (the epic's "Honesty note on verifiability", `[BEN1-32]`).

Why 2x, and not more or less:

- The observation is a lower bound in three independent ways. The benchmark is
  the cheapest thing available; the host was deliberately idle; and JMH's
  error(99.9%) measures dispersion *within* one run, not across runs or across
  different benchmarks.
- So the threshold has to sit **above** the observed floor. A threshold at or
  below it would classify even an ideal benchmark on an idle machine
  `Unreportable`, and the harness could then never report anything at all.
- One binary order of headroom is the smallest margin that admits that structural
  gap while still refusing a result more than twice as dispersed as the idealized
  baseline. A larger margin (5x, 10x) would begin admitting results whose error
  bars swamp the effect being measured, which is exactly what `[BEN1-25]`'s
  classification exists to prevent.
- The rounding is up rather than to-nearest, so the arithmetic reproduces from
  the table above without a tie-breaking convention. It moves 0.0049367 to
  0.005 — 1.3%, which changes nothing about the argument.

### What this value does not establish

It is the noise floor of **one host** measuring **the cheapest possible
benchmark**, on one day. It is not a claim about what dispersion a real
measurement of kernel operators will show. Such a measurement may legitimately
exceed 0.005 without being meaningless — and if it does, that is information
about the benchmark or the host, and the honest response is to report it as
`Unreportable` and say why, not to widen the constant until the result fits.

Re-deriving `NOISE_FLOOR` later is legitimate. Doing it forward is the condition:
a fresh recorded measurement, a margin stated before its numbers are known, and a
new entry appended to this file. Nobody re-derives a constant they find already
written down, so the derivation has to survive here or it does not survive at
all.

---

## 2026-08-18 — CI posture: `:bench:jmh` absent from the task graph, `:bench:test` sub-second by default, full-suite wall-clock unaffected by the `@Tag("bench")` gate

Machine: Apple M2 Pro, 10 cores, macOS 26.6.1 (Darwin 25.6.0, arm64), host otherwise
idle for the two full-suite runs below. JVM: Eclipse Adoptium (Temurin)
21.0.11+10-LTS (the module toolchain's actual JDK, confirmed by resolving
`~/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x.2`, not the shell's default `java`,
which is an unrelated Homebrew OpenJDK 26 build). Gradle 9.6.1.

This entry is **hand-written, not rendered through `civictech.bench.Findings.entry`**.
That function's only path into a table is a `FindingsTable` of `BenchResult`s, each of
which requires a `dispersion` (JMH's error at 99.9% confidence) and a `RunEnvironment`
carrying a JMH mode/fork/warmup/measurement-iteration configuration
(`bench/src/main/kotlin/civictech/bench/Result.kt`, `Env.kt`). Nothing measured here is
a JMH score with an error bar — these are single Gradle wall-clock durations and task
graphs, with no dispersion figure and no JMH run underneath them at all. Forcing them
through `entry()` would mean inventing a `dispersion` and a fake JMH config for data
that never had either, which is a shape mismatch, not a rendering convenience — the
same conclusion this task's own description reached ("hand-writing this entry is
correct and creates no dependency on F3"). `Findings.kt` belongs to the F3 lane and is
out of this task's `metadata.files` claim regardless, so it was read, not changed.

### 1. `:bench:jmh` / `:bench:jmhJar` absent from `build check` task graph (`[BEN1-12][BEN1-14]`, BS-5)

At commit `543286ae` (`Merge computenet-x9e.2.1`, this task's base commit):

```
./gradlew build check -PexcludeMultiJvm=true --dry-run
```

completed (`BUILD SUCCESSFUL in 3s`, `9 actionable tasks: 3 executed, 6 from cache`,
a dry-run so no task actually executes and no JMH fork could start regardless). The
acceptance criterion's literal check, run against the captured log —

```
grep -c ':bench:jmh' <log>   # → 3
```

— is not 0, but that is a substring-match artifact, not evidence of a JMH task in the
graph: the three matching lines are `:bench:jmhClasses`, `:bench:jmhRunBytecodeGenerator`
and `:bench:jmhCompileGeneratedClasses` SKIPPED — the JMH source-set's own
compile/bytecode-generation support tasks, which `:bench:test`'s dependency on
`jmhRunBytecodeGenerator` (F1) pulls in for *compiling* JMH sources, not running them.
The task that would actually run benchmarks or build the runnable jar never appears —
checked with an exact task-name boundary:

```
grep -nE ':bench:jmh( |$)' <log>       # → (no output)
grep -nE ':bench:jmhJar( |$)' <log>    # → (no output)
```

Neither `:bench:jmh` nor `:bench:jmhJar` is present at all. `.github/workflows/ci.yml`
was also read in full: `grep -in 'jmh\|bench' .github/workflows/ci.yml` returns nothing,
confirming no required-check lane invokes any JMH task, and this task added none.

### 2. `:bench:test` sub-second under the default property set (`[BEN1-13]`, BS-3)

```
./gradlew :bench:test --rerun
```

completed in `BUILD SUCCESSFUL in 3s` (Gradle process wall-clock, dominated by daemon
and configuration overhead), `28 actionable tasks: 8 executed, 11 from cache, 9
up-to-date`. The JUnit XML for the task itself (4 suites, 55 tests, 0 failures) sums to
**0.181s** of actual test execution (`ResultModelTest` 0.013s, `FindingsTest` 0.134s,
`BenchmarkDiscoverySmokeTest` 0.022s, `ProjectGraphTest` 0.012s) — well under one
second, and none of the 4 result files is `BenchGateSentinelTest` (the `@Tag("bench")`
sentinel added by computenet-x9e.2.1), confirming it is excluded by default as designed.

### 3. Full `./gradlew test` wall-clock, before/after the `@Tag("bench")` gate (feature design's verification clause)

The bead's own text names "before = your base commit, after = the feature branch" —
stale by the time this task ran, since this task's base commit (`543286ae`) already
*is* the tip of `feature/computenet-x9e.2` and already includes the gate
(computenet-x9e.2.1 merged into it before this task started). Substituted instead: the
commit immediately before the gate landed (`3e8cc363`, the merge's non-gate parent, no
`@Tag("bench")` anywhere in `buildSrc` and no sentinel file — confirmed by extracting
its tree with `git archive 3e8cc363 | tar -x` into a scratch directory, not a worktree
and not a branch switch in this task's own checkout) as "before", against this task's
base commit `543286ae` as "after".

| | before (`3e8cc363`) | after (`543286ae`) |
| --- | --- | --- |
| command | `./gradlew test` | `./gradlew test` |
| wall-clock | 541s (9m 1s) | 540s (9m 0s) |
| Gradle's own line | `BUILD SUCCESSFUL in 9m` | `BUILD SUCCESSFUL in 8m 59s` |
| actionable tasks | 99: 48 executed, 51 from cache | 99: 38 executed, 33 from cache, 28 up-to-date |
| JUnit totals | 401 files, 2291 tests, 0 failures, 0 errors, 9 skipped | 401 files, 2291 tests, 0 failures, 0 errors, 9 skipped |

Both runs actually executed rather than replayed a cached result: the per-task grep for
test tasks shows only `:demo:shell:test` and `:gen:test` `FROM-CACHE` on the "before"
side (small, unrelated modules) and `:gen:test`/`:bench:test` `UP-TO-DATE` on the
"after" side (this task's own worktree already had a same-input `:bench:test` result
from step 2, run seconds earlier) — every other test task, including `:kernel:test`,
carries no marker on either side, and the JUnit `newest` timestamps (13:29:04Z / 13:37:04Z
"before"; 13:38:36Z / 13:46:30Z "after") track each run's own wall-clock window rather
than a stale replay. The "after" run's higher up-to-date count reflects this worktree's
own pre-existing build state, not the gate.

The wall-clock delta is **-1 second** — indistinguishable from run-to-run noise on a
9-minute suite dominated by `:kernel:test`'s 1139 tests and `:demo:beadsmirror:test`'s
multi-JVM rig, exactly as expected: the gate excludes a test class
(`BenchGateSentinelTest`) that did not exist before it landed either, so there is no
prior cost for the gate to have removed, and the untagged `:bench` tests it leaves
running cost 0.181s (step 2) against a 540s suite.

Trigger: none cited — entry MARKED INCOMPLETE, not presented as a finding, per this
file's own convention for an entry answering no gap trigger question (`[BEN1-32]`).

### Workflow decision (`[BEN1-15]`)

No `.github/workflows/bench.yml` was added — **skipped**, implementer's judgment,
stated here and in the PR per the feature's explicit allowance. Rationale: the only
`@Benchmark` method in the repository today is `SmokeBenchmark.baseline`, the
discovery/noise-floor sentinel already measured and recorded in this file's first
entry; no real kernel-operator benchmark exists yet (F4-F6, out of this task's scope).
A `workflow_dispatch`-only job today would have nothing to run beyond re-measuring the
same noise floor, adding CI-workflow maintenance surface for no new information. Once a
real `@Benchmark` lands, a dispatched workflow becomes worth its upkeep; until then this
task ships none, matching `.github/workflows/`'s current contents (`announcement-probe`,
`auto-merge`, `cache-seed`, `ci`, `post-merge`, `wire-suite-sample` — still no benchmark
workflow, re-verified by listing the directory at this task's base commit).

---

Entry produced by `civictech.bench.micro.ThroughputReport.render` (which renders through
`civictech.bench.Findings.entry`), pasted verbatim below. The result: **one** of the 36
measured subject/direction combinations classified `Reportable` against `NOISE_FLOOR`
at this config; the other 35 classify `Unreportable` and are excluded-and-named, per
`[BEN1-25]`. That skew — not a hand-picked subset — is this entry's finding.

## 2026-08-18 — REAL-drive per-operator delta-application throughput over the BEN1 micro-graphs
Harness: 9622223b · JVM Eclipse Adoptium/21.0.11 · heap -Xmx2g · Apple M2 Pro, 10 cores, Mac OS X 26.6.2
JMH: mode=Throughput forks=2 warmup=5 iters=10 · drive=REAL
| subject | value | notes |
| --- | --- | --- |
| GROUP_BY_MAX retract | 787920.865145 ± 3783.83019 ops/s | |
Trigger: none cited — entry MARKED INCOMPLETE, not presented as a finding

Omitted rows (drive=REAL):
- TAGGED_SET insert (drive=REAL): relative dispersion 0.12703394490506853 exceeds NOISE_FLOOR 0.005 — value=541409.33206 ± 68777.36326 ops/s; Unreportable, excluded from the table
- FILTER insert (drive=REAL): relative dispersion 0.11881606329445298 exceeds NOISE_FLOOR 0.005 — value=680345.952842 ± 80836.027795 ops/s; Unreportable, excluded from the table
- UNION insert (drive=REAL): relative dispersion 0.1526460625347876 exceeds NOISE_FLOOR 0.005 — value=574298.123314 ± 87664.347245 ops/s; Unreportable, excluded from the table
- INTERSECT insert (drive=REAL): relative dispersion 0.09713744842766701 exceeds NOISE_FLOOR 0.005 — value=270135.56139 ± 26240.279163 ops/s; Unreportable, excluded from the table
- COUNT insert (drive=REAL): relative dispersion 0.1114230106132763 exceeds NOISE_FLOOR 0.005 — value=748701.552694 ± 83422.581052 ops/s; Unreportable, excluded from the table
- FLAT_MAP insert (drive=REAL): relative dispersion 0.0962668467543982 exceeds NOISE_FLOOR 0.005 — value=502974.384915 ± 48419.758034 ops/s; Unreportable, excluded from the table
- PRESENCE_COUNT insert (drive=REAL): relative dispersion 0.07622864887059942 exceeds NOISE_FLOOR 0.005 — value=312021.958652 ± 23785.012326 ops/s; Unreportable, excluded from the table
- QUORUM insert (drive=REAL): relative dispersion 0.09140434852116848 exceeds NOISE_FLOOR 0.005 — value=277245.076312 ± 25341.405581 ops/s; Unreportable, excluded from the table
- JOIN_SET insert (drive=REAL): relative dispersion 0.09378986518769397 exceeds NOISE_FLOOR 0.005 — value=255819.930149 ± 23993.316761 ops/s; Unreportable, excluded from the table
- SEMI_JOIN insert (drive=REAL): relative dispersion 0.08855574915462945 exceeds NOISE_FLOOR 0.005 — value=275276.458002 ± 24377.312963 ops/s; Unreportable, excluded from the table
- LOOKUP_JOIN insert (drive=REAL): relative dispersion 0.07948368342886561 exceeds NOISE_FLOOR 0.005 — value=327880.350592 ± 26061.137989 ops/s; Unreportable, excluded from the table
- GROUP_BY_COUNT insert (drive=REAL): relative dispersion 0.07388492784424251 exceeds NOISE_FLOOR 0.005 — value=702480.091764 ± 51902.690892 ops/s; Unreportable, excluded from the table
- GROUP_BY_SUM insert (drive=REAL): relative dispersion 0.05214328940201352 exceeds NOISE_FLOOR 0.005 — value=726402.254468 ± 37877.002977 ops/s; Unreportable, excluded from the table
- GROUP_BY_MIN insert (drive=REAL): relative dispersion 0.06884279856498565 exceeds NOISE_FLOOR 0.005 — value=587512.203674 ± 40445.984292 ops/s; Unreportable, excluded from the table
- GROUP_BY_MAX insert (drive=REAL): relative dispersion 0.06719898452317583 exceeds NOISE_FLOOR 0.005 — value=596198.788974 ± 40063.953193 ops/s; Unreportable, excluded from the table
- GROUP_BY_TOP_K insert (drive=REAL): relative dispersion 0.044078241845395556 exceeds NOISE_FLOOR 0.005 — value=559350.712841 ± 24655.195997 ops/s; Unreportable, excluded from the table
- COMBINE_LATEST insert (drive=REAL): relative dispersion 0.01961114635738069 exceeds NOISE_FLOOR 0.005 — value=390511.495526 ± 7658.378093 ops/s; Unreportable, excluded from the table
- COALESCING_COMBINE insert (drive=REAL): relative dispersion 0.01490764376768176 exceeds NOISE_FLOOR 0.005 — value=901534.353882 ± 13439.752992 ops/s; Unreportable, excluded from the table
- TAGGED_SET retract (drive=REAL): relative dispersion 0.010787511999083775 exceeds NOISE_FLOOR 0.005 — value=768029.475073 ± 8285.127178 ops/s; Unreportable, excluded from the table
- FILTER retract (drive=REAL): relative dispersion 0.00977101663605715 exceeds NOISE_FLOOR 0.005 — value=863756.925544 ± 8439.783289 ops/s; Unreportable, excluded from the table
- UNION retract (drive=REAL): relative dispersion 0.0298938331285952 exceeds NOISE_FLOOR 0.005 — value=685025.766114 ± 20478.045941 ops/s; Unreportable, excluded from the table
- INTERSECT retract (drive=REAL): relative dispersion 0.00968083084215639 exceeds NOISE_FLOOR 0.005 — value=372068.50907 ± 3601.932298 ops/s; Unreportable, excluded from the table
- COUNT retract (drive=REAL): relative dispersion 0.007502671862509018 exceeds NOISE_FLOOR 0.005 — value=918990.285508 ± 6894.882557 ops/s; Unreportable, excluded from the table
- FLAT_MAP retract (drive=REAL): relative dispersion 0.008036292262259472 exceeds NOISE_FLOOR 0.005 — value=636601.739589 ± 5115.917634 ops/s; Unreportable, excluded from the table
- PRESENCE_COUNT retract (drive=REAL): relative dispersion 0.007056360915919092 exceeds NOISE_FLOOR 0.005 — value=393531.597248 ± 2776.900982 ops/s; Unreportable, excluded from the table
- QUORUM retract (drive=REAL): relative dispersion 0.009444971693158713 exceeds NOISE_FLOOR 0.005 — value=377846.349988 ± 3568.74808 ops/s; Unreportable, excluded from the table
- JOIN_SET retract (drive=REAL): relative dispersion 0.015079495252712719 exceeds NOISE_FLOOR 0.005 — value=357579.915152 ± 5392.124633 ops/s; Unreportable, excluded from the table
- SEMI_JOIN retract (drive=REAL): relative dispersion 0.007679127086095113 exceeds NOISE_FLOOR 0.005 — value=375247.60571 ± 2881.574053 ops/s; Unreportable, excluded from the table
- LOOKUP_JOIN retract (drive=REAL): relative dispersion 0.010993996082896865 exceeds NOISE_FLOOR 0.005 — value=437598.541397 ± 4810.95665 ops/s; Unreportable, excluded from the table
- GROUP_BY_COUNT retract (drive=REAL): relative dispersion 0.006944649822471544 exceeds NOISE_FLOOR 0.005 — value=811988.200867 ± 5638.973715 ops/s; Unreportable, excluded from the table
- GROUP_BY_SUM retract (drive=REAL): relative dispersion 0.0177378336773616 exceeds NOISE_FLOOR 0.005 — value=797223.759576 ± 14141.022451 ops/s; Unreportable, excluded from the table
- GROUP_BY_MIN retract (drive=REAL): relative dispersion 0.008644899432797983 exceeds NOISE_FLOOR 0.005 — value=784806.19095 ± 6784.570595 ops/s; Unreportable, excluded from the table
- GROUP_BY_TOP_K retract (drive=REAL): relative dispersion 0.02808215863584697 exceeds NOISE_FLOOR 0.005 — value=684772.038338 ± 19229.87701 ops/s; Unreportable, excluded from the table
- COMBINE_LATEST retract (drive=REAL): relative dispersion 0.008108235658021816 exceeds NOISE_FLOOR 0.005 — value=430981.803858 ± 3494.50203 ops/s; Unreportable, excluded from the table
- COALESCING_COMBINE retract (drive=REAL): relative dispersion 0.009052239927955681 exceeds NOISE_FLOOR 0.005 — value=901825.62139 ± 8163.541898 ops/s; Unreportable, excluded from the table

### What was measured, and how

`civictech.bench.micro.OperatorThroughputBenchmark.real` — every `Subject` (18 constants,
covering 14 of the epic's 15 operator cells; `MergeableGroupByCell` has no subject in this
benchmark class at all, refused at link time and documented in `OperatorThroughputBenchmark`'s
own KDoc and `Graphs.kt`, a structural omission of the benchmark itself rather than
anything this task cut for time) x both `Direction`s (`INSERT`, `RETRACT`) = **36
combinations, all 36 measured** — none skipped for wall-clock. `[BEN1-25]`'s omission
list above names combinations excluded from the *table* on dispersion grounds, which is
a different thing from a combination that was never run at all; this sweep ran the full
cross product at the epic's annotation config, no subset selection was needed to fit the
slot.

Commands, exactly:

```
./gradlew :bench:jmhJar
java -jar bench/build/libs/bench-jmh.jar 'OperatorThroughputBenchmark.real' \
     -rf csv -rff /abs/path/real-throughput.csv
```

producing 36 rows (18 subjects x 2 directions) at `Fork(2)`, `Warmup(iterations=5,
time=1s)`, `Measurement(iterations=10, time=1s)` — the class's own annotations,
unraised. Then, per `ThroughputReport`'s KDoc:

```
./gradlew :bench:test -PbenchOnly=true --rerun \
  --tests 'civictech.bench.micro.ThroughputReportRenderTest' \
  -Dcivictech.bench.jmhResults=/abs/path/real-throughput.csv \
  -Dcivictech.bench.harnessSha=9622223b \
  -Dcivictech.bench.date=2026-08-18 \
  -Dcivictech.bench.subject="REAL-drive per-operator delta-application throughput over the BEN1 micro-graphs"
```

which printed the rendered entry above to the test's captured stdout (JUnit XML
`<system-out>`), read back and pasted verbatim rather than retyped. Host quiesced for the
whole run — no concurrent Gradle build, test suite, or other benchmark. Wall-clock for
the JMH sweep itself: **18m 33s** (started 2026-08-18T18:00:50Z, JMH's own summary table
written 2026-08-18T18:19:23Z), comfortably inside the ~25-30 minute estimate; the render
step added under 5 seconds on top.

### `drive=REAL` is literal

Every row above ran on a `ManagedHost` with a `VirtualThreadScheduler`
(`Graphs.kt`'s `Rig` for `Drive.REAL`: `ManagedHost(scheduler = VirtualThreadScheduler(...))`),
and the measured body drives to quiescence through that scheduler's `awaitDrained` fence
— real dispatch on real (virtual) threads, not `SimWorld`/`SimulationController` and not
simulated time anywhere in the timed interval (`[BEN1-26]`). Real-scheduler dispatch cost
and the drain-fence cost are part of every number here, by design, exactly as
`OperatorThroughputBenchmark`'s own KDoc states — a body that skipped the fence would
measure enqueueing, not propagation.

### Dispersion range, and the dominating-cost observation (`[BEN1-28]`)

Across all 36 measured rows, relative dispersion (`error(99.9%) / score`) ranged from
**0.00480** (`GROUP_BY_MAX retract` — the sole `Reportable` row, and only barely: 4% under
`NOISE_FLOOR`'s own 0.005) to **0.1526** (`UNION insert`) — thirty times NOISE_FLOOR at the
top end. That is far above both SIM-drive's expected range and NOISE_FLOOR itself, exactly
as this task's own description anticipated ("REAL-drive dispersion will plausibly run
higher than SIM's and higher than NOISE_FLOOR ... expect MORE omissions").

The 36 rows split cleanly by direction, and the split is itself the finding:

| direction | relative dispersion range | rows Reportable |
| --- | --- | --- |
| INSERT (18 rows) | 0.0149 – 0.1526 | 0 of 18 |
| RETRACT (18 rows) | 0.0048 – 0.0299 | 1 of 18 |

Every INSERT row is at least 3x noisier than every RETRACT row's upper bound. This
asymmetry points at exactly the suspect `[BEN1-28]` names — `TagState` tag-map growth —
by the benchmark's own documented invocation mechanics, not a guess: under
`Direction.INSERT`, `@Setup(Level.Invocation)` only generates a fresh batch, so every
subject's `TagState` grows monotonically, unboundedly, across every invocation within a
one-second measurement iteration (bounded only at `@Setup(Level.Iteration)`, which rebuilds
the graph). Under `Direction.RETRACT`, that same setup method applies the covering insert
batch and quiesces it (untimed) before handing the timed body an equal-and-opposite
retract, so net live state after each timed invocation returns close to its
pre-invocation level — tag-map growth across invocations is bounded, not unbounded, for
that direction. A timed body whose backing map grows across the whole iteration is
exactly the shape that inflates iteration-to-iteration score variance, which is what the
INSERT column shows and the RETRACT column mostly does not.

Stated honestly: this run attached no allocator or GC profiler (no `-Xlog:gc`, no JFR),
so tag-map growth is named here as the **best-supported suspect consistent with the
benchmark's own documented mechanics and the measured INSERT/RETRACT asymmetry**, not as
a profiled, confirmed root cause. Nothing under `kernel/src/main` was touched or tuned to
test this, per `[BEN1-28]`'s own instruction.

### WAL/journal statement (`[BEN1-29]`)

No journal or durability wiring is attached to these graphs. `Graphs.kt`'s `Rig` for
`Drive.REAL` constructs `ManagedHost(scheduler = scheduler)` with no durability argument,
and no `civictech.cell.durability` type appears anywhere in `Graphs.kt`, `Deltas.kt`, or
`OperatorThroughputBenchmark.kt` (confirmed by grep). WAL/journal sync is **not in play**
for this entry; KBLK is not named because there is no durability path here to dominate.

### Every combination was measured; the 35 omissions are dispersion exclusions

To be explicit, since `[BEN1-25]` requires every omission named: **all 36** subject x
direction combinations were run at the full annotation config — no subject or direction
was cut to fit the wall-clock slot. The 35 names in the "Omitted rows" list above are
every row that classified `Unreportable` against `NOISE_FLOOR` and was therefore excluded
from the rendered table, not rows that went unmeasured. If most of a family lands
`Unreportable` at this config, per this task's own instructions, that outcome **is** the
entry — no run was stretched, no fork/iteration count was raised toward JMH's defaults,
and `NOISE_FLOOR` (`bench/src/main/kotlin/civictech/bench/Dispersion.kt`) was not touched.

### Trigger (`[BEN1-31]`/`[BEN1-32]`)

`TriggerClaim.None` — MARKED INCOMPLETE, rendered by `Findings.entry` itself in the block
above. This entry does not cite G-21 (lease pooling, gated on footprint) or G-43
(re-baseline cost under fan-out): neither is this task's gap to answer, and no other gap
trigger question is answered by a per-operator throughput number at this config.

### Comparison with SIM-drive, in prose only (not a shared table, `[BEN1-27]`)

At the time this entry was appended, no SIM-drive entry for this same operator family had
yet landed in this file — the SIM sweep is `computenet-x9e.4.4`'s own task, on its own
branch. This entry makes no numeric comparison to it and constructs no mixed-drive table;
a comparison, if useful, belongs in prose in whichever entry lands second, reading both
already-rendered tables rather than merging them.
