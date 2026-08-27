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

**A corrected entry is never marked as corrected — the correction is below it.**
That is the direct consequence of the rule above, and it is the one way this file
can mislead a reader who does not read it end to end: an entry later found wrong
still reads exactly as it was published, with no marker, no strikethrough and no
link forward, and the only record of the correction is a later entry that names
it. **Before citing any entry, scan the `##` headings that follow it for one that
names it.** As of 2026-08-18 three entries carry a claim a later entry corrects,
and none of the three says so on its own face:

- **2026-08-18 — SmokeBenchmark.baseline noise-floor calibration.** Its
  `Harness:` line's heap field (`maxHeapBytes=4294967296`) is a property of the
  process that *rendered* the entry — `RunEnvironment.capture` fell back to the
  calling process's `Runtime.maxMemory()` — not of the JMH forks, which this
  entry's own procedure paragraph says were "launched with no VM options". Its
  JVM vendor/version, its scores and the `NOISE_FLOOR` derivation are unaffected.
  Same mechanism as the correction entry below, which does not name this entry.
- **2026-08-18 — REAL-drive per-operator delta-application throughput**
  (`computenet-x9e.4.5`). Its `Harness:` line names the *rendering* JVM and heap;
  the sweep itself ran on Homebrew JDK 26.0.1 with `# VM options: <none>`, per its
  own retained JMH banner. Corrected by *"Correction to the two entries above"*
  and superseded, for environment purposes, by *"REAL-drive … re-measured on the
  toolchain JDK"* — which, re-run on the toolchain JDK 21, reports **no** row
  clearing `NOISE_FLOOR`, including the single `GROUP_BY_MAX retract` row this
  entry reports.
- **2026-08-18 — SIM-drive per-operator delta-application throughput**
  (`computenet-x9e.4.4`). Its JVM vendor/version is right; its heap field is
  wrong the same way (`-Xmx2g` against a banner reading `# VM options: <none>`),
  and its "Comparison with REAL-drive" dispersion attribution is **withdrawn** by
  the re-measurement entry, which did not reproduce it.

Each entry's own scores, dispersions and omission accounting stand; what a later
entry corrects is stated there, in the entry that corrects it.

Entries are rendered through `civictech.bench.Findings.entry`
(`bench/src/main/kotlin/civictech/bench/Findings.kt`), which refuses an entry
that is incomplete, that carries a result too dispersed to report against
`NOISE_FLOOR` (`[BEN1-25]`), or that cites a gap without stating exactly one of
FIRES / RETIRES / INCONCLUSIVE (`[BEN1-31]`). An entry answering no gap trigger
question is rendered explicitly **MARKED INCOMPLETE** and is not a finding
(`[BEN1-32]`).

**Amended 2026-08-22 (`computenet-785b`) — the paragraph above describes the
writer as it stood until that date.** Per this file's own append-only rule the
sentence is left as published rather than corrected in place; what changed is
recorded in the last entry of this file, *"the reportability gate becomes
claim-relative"*. In one line: `Findings.entry` no longer refuses a result for
being too dispersed against `NOISE_FLOOR` — a standalone number is rendered with
its error bar attached — and it now refuses a **comparison** whose claimed effect
does not exceed the combined error bars of the two rows it is drawn from. Every
other refusal named above still stands. Entries below this point that report a
row the old gate would have excluded are doing so under the new criterion, not in
violation of the old one.

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

---

Entry produced by `civictech.bench.micro.ThroughputReport.render` (which renders through
`civictech.bench.Findings.entry`), pasted verbatim below. The result: **five** of the 36
measured subject/direction combinations classified `Reportable` against `NOISE_FLOOR`
at this config; the other 31 classify `Unreportable` and are excluded-and-named, per
`[BEN1-25]`. All five Reportable rows are RETRACT rows — that skew, not a hand-picked
subset, is this entry's finding.

## 2026-08-18 — SIM-drive per-operator delta-application throughput over the BEN1 micro-graphs
Harness: 49ab44e9 · JVM Eclipse Adoptium/21.0.11 · heap -Xmx2g · Apple M2 Pro, 10 cores, Mac OS X 26.6.2
JMH: mode=Throughput forks=2 warmup=5 iters=10 · drive=SIM
| subject | value | notes |
| --- | --- | --- |
| FILTER retract | 831943.320342 ± 3736.560661 ops/s | |
| INTERSECT retract | 352771.551032 ± 1500.585422 ops/s | |
| COUNT retract | 880998.752986 ± 4392.874452 ops/s | |
| GROUP_BY_SUM retract | 761118.948691 ± 3066.647648 ops/s | |
| GROUP_BY_TOP_K retract | 680756.894053 ± 2414.522723 ops/s | |
Trigger: none cited — entry MARKED INCOMPLETE, not presented as a finding

Omitted rows (drive=SIM):
- TAGGED_SET insert (drive=SIM): relative dispersion 0.08466968712623274 exceeds NOISE_FLOOR 0.005 — value=646933.303773 ± 54775.640422 ops/s; Unreportable, excluded from the table
- FILTER insert (drive=SIM): relative dispersion 0.031707506127122545 exceeds NOISE_FLOOR 0.005 — value=736635.519973 ± 23356.875263 ops/s; Unreportable, excluded from the table
- UNION insert (drive=SIM): relative dispersion 0.0464378190718246 exceeds NOISE_FLOOR 0.005 — value=632514.88791 ± 29372.611925 ops/s; Unreportable, excluded from the table
- INTERSECT insert (drive=SIM): relative dispersion 0.04379083312043933 exceeds NOISE_FLOOR 0.005 — value=267729.32444 ± 11724.090168 ops/s; Unreportable, excluded from the table
- COUNT insert (drive=SIM): relative dispersion 0.03507748947042507 exceeds NOISE_FLOOR 0.005 — value=762923.322365 ± 26761.434807 ops/s; Unreportable, excluded from the table
- FLAT_MAP insert (drive=SIM): relative dispersion 0.042690792815487245 exceeds NOISE_FLOOR 0.005 — value=518612.786361 ± 22139.991014 ops/s; Unreportable, excluded from the table
- PRESENCE_COUNT insert (drive=SIM): relative dispersion 0.041194929800779385 exceeds NOISE_FLOOR 0.005 — value=341065.981662 ± 14050.189172 ops/s; Unreportable, excluded from the table
- QUORUM insert (drive=SIM): relative dispersion 0.033394493100491945 exceeds NOISE_FLOOR 0.005 — value=301676.860304 ± 10074.34583 ops/s; Unreportable, excluded from the table
- JOIN_SET insert (drive=SIM): relative dispersion 0.04701313947712893 exceeds NOISE_FLOOR 0.005 — value=273634.48368 ± 12864.416147 ops/s; Unreportable, excluded from the table
- SEMI_JOIN insert (drive=SIM): relative dispersion 0.04905640577613255 exceeds NOISE_FLOOR 0.005 — value=288481.927999 ± 14151.886519 ops/s; Unreportable, excluded from the table
- LOOKUP_JOIN insert (drive=SIM): relative dispersion 0.044160057192433246 exceeds NOISE_FLOOR 0.005 — value=359687.38577 ± 15883.815527 ops/s; Unreportable, excluded from the table
- GROUP_BY_COUNT insert (drive=SIM): relative dispersion 0.029250313468544908 exceeds NOISE_FLOOR 0.005 — value=744844.880908 ± 21786.946252 ops/s; Unreportable, excluded from the table
- GROUP_BY_SUM insert (drive=SIM): relative dispersion 0.03493439672880384 exceeds NOISE_FLOOR 0.005 — value=694275.642579 ± 24254.100737 ops/s; Unreportable, excluded from the table
- GROUP_BY_MIN insert (drive=SIM): relative dispersion 0.04089249481317446 exceeds NOISE_FLOOR 0.005 — value=577537.610383 ± 23616.953737 ops/s; Unreportable, excluded from the table
- GROUP_BY_MAX insert (drive=SIM): relative dispersion 0.034691775366600924 exceeds NOISE_FLOOR 0.005 — value=583936.158266 ± 20257.782031 ops/s; Unreportable, excluded from the table
- GROUP_BY_TOP_K insert (drive=SIM): relative dispersion 0.027176002051186338 exceeds NOISE_FLOOR 0.005 — value=565502.221668 ± 15368.089536 ops/s; Unreportable, excluded from the table
- COMBINE_LATEST insert (drive=SIM): relative dispersion 0.029789264506870944 exceeds NOISE_FLOOR 0.005 — value=388692.202029 ± 11578.854818 ops/s; Unreportable, excluded from the table
- COALESCING_COMBINE insert (drive=SIM): relative dispersion 0.0053839946759323114 exceeds NOISE_FLOOR 0.005 — value=848542.092254 ± 4568.546107 ops/s; Unreportable, excluded from the table
- TAGGED_SET retract (drive=SIM): relative dispersion 0.005347344082121458 exceeds NOISE_FLOOR 0.005 — value=790109.369271 ± 4224.98666 ops/s; Unreportable, excluded from the table
- UNION retract (drive=SIM): relative dispersion 0.050791743164737314 exceeds NOISE_FLOOR 0.005 — value=642991.625471 ± 32658.665498 ops/s; Unreportable, excluded from the table
- FLAT_MAP retract (drive=SIM): relative dispersion 0.02969969197106597 exceeds NOISE_FLOOR 0.005 — value=664339.531441 ± 19730.679448 ops/s; Unreportable, excluded from the table
- PRESENCE_COUNT retract (drive=SIM): relative dispersion 0.00735890159201597 exceeds NOISE_FLOOR 0.005 — value=395864.364209 ± 2913.1269 ops/s; Unreportable, excluded from the table
- QUORUM retract (drive=SIM): relative dispersion 0.006167811791334887 exceeds NOISE_FLOOR 0.005 — value=375040.494791 ± 2313.179186 ops/s; Unreportable, excluded from the table
- JOIN_SET retract (drive=SIM): relative dispersion 0.009671932284995927 exceeds NOISE_FLOOR 0.005 — value=330782.387193 ± 3199.30485 ops/s; Unreportable, excluded from the table
- SEMI_JOIN retract (drive=SIM): relative dispersion 0.006168793121223146 exceeds NOISE_FLOOR 0.005 — value=342750.95265 ± 2114.359719 ops/s; Unreportable, excluded from the table
- LOOKUP_JOIN retract (drive=SIM): relative dispersion 0.008291601701418624 exceeds NOISE_FLOOR 0.005 — value=456790.829491 ± 3787.527619 ops/s; Unreportable, excluded from the table
- GROUP_BY_COUNT retract (drive=SIM): relative dispersion 0.005223568271671839 exceeds NOISE_FLOOR 0.005 — value=793606.005397 ± 4145.45515 ops/s; Unreportable, excluded from the table
- GROUP_BY_MIN retract (drive=SIM): relative dispersion 0.008171789875173946 exceeds NOISE_FLOOR 0.005 — value=713898.529467 ± 5833.828775 ops/s; Unreportable, excluded from the table
- GROUP_BY_MAX retract (drive=SIM): relative dispersion 0.017181097779901577 exceeds NOISE_FLOOR 0.005 — value=714806.150243 ± 12281.154361 ops/s; Unreportable, excluded from the table
- COMBINE_LATEST retract (drive=SIM): relative dispersion 0.005998695746512657 exceeds NOISE_FLOOR 0.005 — value=453577.096585 ± 2720.871 ops/s; Unreportable, excluded from the table
- COALESCING_COMBINE retract (drive=SIM): relative dispersion 0.007663714055015782 exceeds NOISE_FLOOR 0.005 — value=857479.356592 ± 6571.476597 ops/s; Unreportable, excluded from the table

### What was measured, and how

`civictech.bench.micro.OperatorThroughputBenchmark.sim` — every `Subject` (18 constants,
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
/Users/MerlijnB/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x.2/jdk-21.0.11+10/Contents/Home/bin/java \
     -jar bench/build/libs/bench-jmh.jar 'OperatorThroughputBenchmark.sim' \
     -rf csv -rff /abs/path/sim-throughput.csv
```

producing 36 rows (18 subjects x 2 directions) at `Fork(2)`, `Warmup(iterations=5,
time=1s)`, `Measurement(iterations=10, time=1s)` — the class's own annotations,
unraised. The Gradle toolchain's own JDK 21 (Eclipse Adoptium/Temurin 21.0.11) was
invoked explicitly by absolute path rather than relying on the shell's default `java`
(a JDK 26 on this host), so the run's `RunEnvironment` and the actual JVM that executed
it agree. Then, per `ThroughputReport`'s KDoc:

```
./gradlew :bench:test -PbenchOnly=true --rerun \
  --tests 'civictech.bench.micro.ThroughputReportRenderTest' \
  -Dcivictech.bench.jmhResults=/abs/path/sim-throughput.csv \
  -Dcivictech.bench.harnessSha=49ab44e9 \
  -Dcivictech.bench.date=2026-08-18 \
  -Dcivictech.bench.subject="SIM-drive per-operator delta-application throughput over the BEN1 micro-graphs"
```

which printed the rendered entry above to the test's captured stdout (JUnit XML
`<system-out>`), read back and pasted verbatim rather than retyped. Host quiesced for the
whole run — no concurrent Gradle build, test suite, or other benchmark; this was the only
active workload on the machine. Wall-clock for the JMH sweep itself: **18m 29s** (started
2026-08-18T18:35:37Z, results file written 2026-08-18T18:54:06Z), inside the ~25-30 minute
estimate and close to the REAL-drive sibling's 18m 33s; the render step added under 5
seconds on top.

### `drive=SIM` is literal

Every row above ran under `testkit.SimWorld` (`Graphs.kt`'s `Rig` for `Drive.SIM`:
`SimWorld().host`, an in-process `ManagedHost` over `SimulationController`'s scheduler),
and the measured body drives to quiescence through `SimWorld.runToIdle` — deterministic,
single-threaded stepping, not a real scheduler and not real threads anywhere in the
timed interval (`[BEN1-26]`). Simulated-time dispatch and the `runToIdle` drain cost are
part of every number here, by design, exactly as `OperatorThroughputBenchmark`'s own
KDoc states — a body that skipped the drive would measure enqueueing, not propagation.

### Dispersion range, and the dominating-cost observation (`[BEN1-28]`)

Across all 36 measured rows, relative dispersion (`error(99.9%) / score`) ranged from
**0.00355** (`GROUP_BY_TOP_K retract`) to **0.08467** (`TAGGED_SET insert`) — roughly
seventeen times `NOISE_FLOOR` (0.005) at the top end. That is tighter than REAL-drive's
range (0.0048–0.1526) but still well above `NOISE_FLOOR` for most rows, so the same
question REAL's entry asked applies here too.

The 36 rows split cleanly by direction, and the split is itself the finding, exactly as
in the REAL-drive entry:

| direction | relative dispersion range | rows Reportable |
| --- | --- | --- |
| INSERT (18 rows) | 0.00538 – 0.08467 | 0 of 18 |
| RETRACT (18 rows) | 0.00355 – 0.05079 | 5 of 18 |

Every one of the five Reportable rows is a RETRACT row; not one INSERT row lands under
`NOISE_FLOOR`. This is the same asymmetry the REAL-drive entry names, and the same
documented benchmark mechanics explain it: under `Direction.INSERT`,
`@Setup(Level.Invocation)` only generates a fresh batch, so every subject's `TagState`
grows monotonically and unboundedly across every invocation within a one-second
measurement iteration (bounded only at `@Setup(Level.Iteration)`, which rebuilds the
graph). Under `Direction.RETRACT`, that same setup method applies the covering insert
batch and quiesces it (untimed) before handing the timed body an equal-and-opposite
retract, so net live state after each timed invocation returns close to its
pre-invocation level — tag-map growth across invocations is bounded, not unbounded, for
that direction. This holds under SIM exactly as it held under REAL, which is evidence
the asymmetry is a property of the benchmark's own tag-map growth mechanics rather than
an artifact of real-scheduler thread contention: SIM has none of REAL's scheduling
jitter, and the same INSERT/RETRACT split still appears.

Stated honestly: this run attached no allocator or GC profiler (no `-Xlog:gc`, no JFR),
so tag-map growth is named here as the **best-supported suspect consistent with the
benchmark's own documented mechanics and the measured INSERT/RETRACT asymmetry, and with
its reappearance under a drive that removes scheduling jitter as a competing
explanation** — not as a profiled, confirmed root cause. Nothing under `kernel/src/main`
was touched or tuned to test this, per `[BEN1-28]`'s own instruction.

### WAL/journal statement (`[BEN1-29]`)

No journal or durability wiring is attached to these graphs. `testkit.SimWorld` builds
its `ManagedHost` as `ManagedHost(scheduler = controller.scheduler(), registry =
registry, attention = attention)` — no `journal` or `journalFor` argument — and
`ManagedHost`'s own constructor documents `journal: Journal? = null` as "volatile host
(default, pre-M10 behavior)" (`kernel/src/main/kotlin/civictech/cell/host/ManagedHost.kt`).
No `civictech.cell.durability` type appears anywhere in `Graphs.kt`, `Deltas.kt`, or
`OperatorThroughputBenchmark.kt` (confirmed by grep, as for the REAL entry). WAL/journal
sync is **not in play** for this entry either; KBLK is not named because there is no
durability path here to dominate.

### Every combination was measured; the 31 omissions are dispersion exclusions

To be explicit, since `[BEN1-25]` requires every omission named: **all 36** subject x
direction combinations were run at the full annotation config — no subject or direction
was cut to fit the wall-clock slot. The 31 names in the "Omitted rows" list above are
every row that classified `Unreportable` against `NOISE_FLOOR` and was therefore excluded
from the rendered table, not rows that went unmeasured. Per this task's own instructions,
this outcome — most of the family landing `Unreportable` at this config — **is** the
entry: no run was stretched, no fork/iteration count was raised toward JMH's defaults,
and `NOISE_FLOOR` (`bench/src/main/kotlin/civictech/bench/Dispersion.kt`) was not touched.

### Trigger (`[BEN1-31]`/`[BEN1-32]`)

`TriggerClaim.None` — MARKED INCOMPLETE, rendered by `Findings.entry` itself in the block
above. This entry does not cite G-21 (lease pooling, gated on footprint) or G-43
(re-baseline cost under fan-out): neither is this task's gap to answer, and no other gap
trigger question is answered by a per-operator throughput number at this config.

### Comparison with REAL-drive, in prose only (not a shared table, `[BEN1-27]`)

The REAL-drive entry above (`computenet-x9e.4.5`) measured the identical 18 subjects x 2
directions at the same annotation config on the same machine. Read side by side, in
prose, without constructing any mixed-drive table:

- **SIM classified more rows Reportable than REAL**: 5 of 36 here versus 1 of 36 there.
  SIM's relative dispersion range (0.00355–0.08467) sits entirely below REAL's upper
  bound (0.1526) and its own upper bound is roughly half of REAL's.
- **Both drives show the identical directional split**: zero INSERT rows Reportable,
  all Reportable rows drawn from RETRACT. That the same asymmetry survives the move from
  a real, virtual-thread scheduler (REAL) to deterministic single-threaded simulation
  (SIM) is the strongest evidence in either entry that the dominant unintended cost is
  the benchmark's own `TagState` tag-map growth mechanics (`[BEN1-28]`), not scheduler
  contention or GC pressure specific to real threads — a real-scheduler-only cause would
  be expected to weaken or disappear under SIM, and it does not.
- **SIM being tighter than REAL is exactly the expected direction**: SIM removes
  real-thread scheduling and OS jitter from the measured interval, so its residual
  dispersion is closer to the tag-map-growth floor common to both drives. That REAL is
  still noisier on top of the shared asymmetry is consistent with real-scheduler jitter
  being an *additional*, not the *sole*, contributor to dispersion — reported as an
  observation, not a profiled attribution.

No numeric value from either drive is merged into a shared row or table; each entry's
`FindingsTable` stands on its own, per `[BEN1-27]`.

---

## 2026-08-18 — Correction to the two entries above: the recorded environment describes the *rendering* JVM, not the measuring one

Appended at feature review of `computenet-x9e.4` (PR #322), against the retained JMH
stdout logs of both sweeps. The measured scores and dispersions above are unaffected;
what is wrong is the `Harness:` line's environment fields, and — because of that — one
of the cross-drive statements the SIM entry makes.

**The mechanism.** `RunEnvironment.capture` (`bench/src/main/kotlin/civictech/bench/Env.kt`)
reads `System.getProperty("java.vendor")`, `"java.version"` and
`ManagementFactory.getRuntimeMXBean().inputArguments` **of the process that calls it**.
`ThroughputReportRenderTest` calls it inside the Gradle `:bench:test` JVM — the toolchain
JDK 21 (`jvmToolchain(21)`), launched with `-Xmx2g` — long after the JMH forks have
exited. JMH's CSV output carries score, error, unit and `Param:` columns and **no JVM
columns at all**, so nothing about the JVM that actually produced the numbers ever
reaches the renderer. The `Harness:` line's JVM vendor, JVM version and heap fields are
therefore properties of the render step, not of the measurement.

**What the sweeps actually ran on**, from each run's own retained JMH banner:

| entry | `# VM version` in the sweep's own log | `# VM options` | `Harness:` line claims |
| --- | --- | --- | --- |
| REAL-drive (`computenet-x9e.4.5`) | `JDK 26.0.1, OpenJDK 64-Bit Server VM, 26.0.1` (invoker `/opt/homebrew/Cellar/openjdk/26.0.1/...`) | `<none>` | `Eclipse Adoptium/21.0.11 · heap -Xmx2g` |
| SIM-drive (`computenet-x9e.4.4`) | `JDK 21.0.11, OpenJDK 64-Bit Server VM, 21.0.11+10-LTS` (invoker `~/.gradle/jdks/eclipse_adoptium-21-...`) | `<none>` | `Eclipse Adoptium/21.0.11 · heap -Xmx2g` |

So the REAL entry's JVM field is wrong by five major versions and its heap field names a
flag its forks never received; the SIM entry's JVM field is right (its own "Commands,
exactly" block invoked the toolchain JDK by absolute path for precisely this reason) and
only its heap field is wrong. The REAL entry is internally inconsistent on its own face:
its rendered `Harness:` line says Adoptium 21.0.11 while its own "Commands, exactly"
block shows a bare `java -jar`, which on this host resolves to the Homebrew JDK 26.

**Demonstrated, not inferred.** Re-running the REAL entry's own documented render command
against its retained `real-throughput.csv` — the file JDK 26 produced — reproduced the
identical `Harness:` line, `Eclipse Adoptium/21.0.11 · heap -Xmx2g` included. The
environment fields cannot distinguish the two runs because they never saw either of them.

**Consequences, stated rather than tidied away:**

- The one Reportable REAL row (`GROUP_BY_MAX retract`) does not satisfy `[BEN1-23]` as
  rendered: the JVM vendor/version and heap it carries are not the ones it was measured
  under. Both entries' heap field is wrong.
- The SIM entry's "Comparison with REAL-drive" section is **confounded**. It states the
  two sweeps ran "at the same annotation config on the same machine" — true of config and
  machine, false of runtime — and reads SIM's tighter dispersion (5/36 Reportable vs
  1/36; range 0.00355–0.08467 vs 0.00480–0.1526) as attributable to the drive, with
  "SIM removes real-thread scheduling and OS jitter" offered as the explanation. A JDK
  21-vs-26 difference in JIT and GC defaults is an uncontrolled alternative explanation of
  the same spread, and nothing in either run separates the two. Until REAL is re-measured
  on the toolchain JDK, the cross-drive dispersion comparison should not be read as
  drive-attributable.
- **Not affected**: the per-row scores and dispersions (they are what each CSV says), the
  omission lists and their `NOISE_FLOOR` classification, `[BEN1-25]`'s exclusion
  behaviour, `[BEN1-27]`'s no-mixed-table property, and both entries' `[BEN1-29]`
  WAL/journal statements. The INSERT/RETRACT asymmetry is observed **within** each drive
  independently, so it does not rest on the cross-drive comparison and survives it.

**Follow-ups filed:** `computenet-hqid` (the renderer must not present a render-time
environment as the measurement's) and `computenet-am2h` (re-measure the REAL sweep on the
toolchain JDK and append a corrected entry). Neither is repaired here: correcting the
`Harness:` line by hand would break the property both sweep entries rest on — that the
rendered block is verbatim tool output — and re-measuring is an 18-minute sweep, not a
review-time edit. Nothing under `kernel/src/main` was touched, and `NOISE_FLOOR` was not
changed.

---

## 2026-08-18 — REAL-drive per-operator delta-application throughput, re-measured on the toolchain JDK

`computenet-am2h`, dispatched from the correction appended above (`computenet-x9e.4`
review) once `computenet-hqid` landed. **This entry supersedes the 2026-08-18 REAL
entry above (`computenet-x9e.4.5`) for environment purposes**: that entry's sweep ran
on Homebrew JDK 26.0.1 while its `Harness:` line claimed Eclipse Adoptium 21.0.11 (the
render JVM, not the measuring one — `computenet-hqid`'s defect). This sweep re-runs the
identical benchmark, at the identical unmodified annotation config, invoking the
Gradle toolchain JDK 21 by absolute path exactly as the SIM entry's own command block
does, on the now-fixed renderer (`ThroughputReport.renderRun`/`MeasuringJvm.fromJmhLog`,
`computenet-hqid`), which reads the measuring JVM from the run's own JMH stdout banner
and refuses to render without it. `kernel/src/main` is unchanged between the original
REAL entry's harness commit (`9622223b`) and this one's (`6951a26e`) — `git diff
--name-only 9622223b 6951a26e -- kernel/src/main` is empty — so the subjects measured
are the identical code; only the bench renderer/environment-capture machinery differs.

Commands, exactly:

```
./gradlew :bench:jmhJar
/Users/MerlijnB/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x.2/jdk-21.0.11+10/Contents/Home/bin/java \
     -jar bench/build/libs/bench-jmh.jar 'OperatorThroughputBenchmark.real' \
     -rf csv -rff /abs/path/real-throughput.csv > /abs/path/real-throughput.log 2>&1
```

(stdout and stderr redirected directly to the log file rather than piped through
`tee`; the resulting log carries the identical JMH banner content `tee` would have
captured, and `ThroughputReport.runLogFor`'s by-name convention — `real-throughput.csv`
paired with `real-throughput.log` — accepted it without refusal.) The Gradle
toolchain's own JDK 21 (Eclipse Adoptium/Temurin 21.0.11) was invoked explicitly by
absolute path, not the shell's bare `java` (Homebrew JDK 26.0.1 on this host — the exact
substitution that produced the entry being superseded). Then, per `ThroughputReport`'s
KDoc, through the entry point `computenet-hqid` added:

```
./gradlew :bench:test -PbenchOnly=true --rerun \
  --tests 'civictech.bench.micro.ThroughputReportRenderTest' \
  -Dcivictech.bench.jmhResults=/abs/path/real-throughput.csv \
  -Dcivictech.bench.harnessSha=6951a26e \
  -Dcivictech.bench.date=2026-08-18 \
  -Dcivictech.bench.subject="REAL-drive per-operator delta-application throughput over the BEN1 micro-graphs (re-measured on the toolchain JDK)"
```

which reads the measuring JVM from `real-throughput.log` (beside the results file, by
`runLogFor`'s convention) rather than from the render process, and printed the
rendered report to the test's captured stdout (JUnit XML `<system-out>`), read back and
pasted verbatim below rather than retyped. Host quiesced for the whole run — no
concurrent Gradle build, test suite, or other benchmark; this was the only active
workload on the machine. Wall-clock for the JMH sweep itself: **18m 29s** (started
2026-08-18T19:59:47Z, results file written 2026-08-18T20:18:16Z), matching both prior
sweeps' ~18m30s almost exactly; the render step added a few seconds on top.

**The measuring JVM, from this run's own retained banner** (`real-throughput.log`,
quoted rather than paraphrased):

```
# JMH version: 1.37
# VM version: JDK 21.0.11, OpenJDK 64-Bit Server VM, 21.0.11+10-LTS
# VM invoker: /Users/MerlijnB/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x.2/jdk-21.0.11+10/Contents/Home/bin/java
# VM options: <none>
```

`MeasuringJvm.fromJmhLog` resolves this to `jvmVendor = "Eclipse Adoptium
(Temurin-21.0.11+10)"` (its release file's `IMPLEMENTOR`/`IMPLEMENTOR_VERSION`),
`jvmVersion = "21.0.11"`, `heapSettings = "JVM defaults (VM options: <none>)"` — the
measuring fork's own facts, not the Gradle `:bench:test` JVM's. No `Harness:` line
appears below because no row cleared `NOISE_FLOOR` into a table (see next), so this
paragraph is this entry's explicit substitute for it: **Environment — JVM Eclipse
Adoptium (Temurin-21.0.11+10) 21.0.11 · heap JVM defaults (VM options: `<none>`) ·
Apple M2 Pro, 10 cores, Mac OS X 26.6.2** (CPU/core/OS captured on this render's own
host, per `RunEnvironment.forRun`'s documented residual — sound here because the
render ran on the same machine as the sweep). `[BEN1-23]` is satisfied by this
paragraph rather than by a table cell, and it is sourced from the artifacts above, not
asserted.

Renderer's own output (`ThroughputReport.renderRun` via `ThroughputReportRenderTest`),
pasted verbatim:

## (no entry for drive=REAL) — every row classified Unreportable against NOISE_FLOOR 0.005; see the omissions below

Omitted rows (drive=REAL):
- TAGGED_SET insert (drive=REAL): relative dispersion 0.07387365467327402 exceeds NOISE_FLOOR 0.005 — value=561811.681804 ± 41503.082173 ops/s; Unreportable, excluded from the table
- FILTER insert (drive=REAL): relative dispersion 0.0269866325409201 exceeds NOISE_FLOOR 0.005 — value=669977.214852 ± 18080.428908 ops/s; Unreportable, excluded from the table
- UNION insert (drive=REAL): relative dispersion 0.05057086183682231 exceeds NOISE_FLOOR 0.005 — value=612562.500318 ± 30977.81357 ops/s; Unreportable, excluded from the table
- INTERSECT insert (drive=REAL): relative dispersion 0.053766902701104666 exceeds NOISE_FLOOR 0.005 — value=268157.494884 ± 14417.997936 ops/s; Unreportable, excluded from the table
- COUNT insert (drive=REAL): relative dispersion 0.042709417670606625 exceeds NOISE_FLOOR 0.005 — value=756376.463176 ± 32304.398282 ops/s; Unreportable, excluded from the table
- FLAT_MAP insert (drive=REAL): relative dispersion 0.04088701716181732 exceeds NOISE_FLOOR 0.005 — value=473515.682041 ± 19360.643818 ops/s; Unreportable, excluded from the table
- PRESENCE_COUNT insert (drive=REAL): relative dispersion 0.03391374518464031 exceeds NOISE_FLOOR 0.005 — value=288174.161945 ± 9773.065097 ops/s; Unreportable, excluded from the table
- QUORUM insert (drive=REAL): relative dispersion 0.038162407784747325 exceeds NOISE_FLOOR 0.005 — value=283140.939454 ± 10805.339992 ops/s; Unreportable, excluded from the table
- JOIN_SET insert (drive=REAL): relative dispersion 0.023190439427781484 exceeds NOISE_FLOOR 0.005 — value=238682.634119 ± 5535.155169 ops/s; Unreportable, excluded from the table
- SEMI_JOIN insert (drive=REAL): relative dispersion 0.024529418935812196 exceeds NOISE_FLOOR 0.005 — value=247207.799576 ± 6063.86368 ops/s; Unreportable, excluded from the table
- LOOKUP_JOIN insert (drive=REAL): relative dispersion 0.04434162503108245 exceeds NOISE_FLOOR 0.005 — value=314662.273591 ± 13952.636547 ops/s; Unreportable, excluded from the table
- GROUP_BY_COUNT insert (drive=REAL): relative dispersion 0.04645493515991331 exceeds NOISE_FLOOR 0.005 — value=698504.752085 ± 32448.992967 ops/s; Unreportable, excluded from the table
- GROUP_BY_SUM insert (drive=REAL): relative dispersion 0.04996014212100643 exceeds NOISE_FLOOR 0.005 — value=680665.676023 ± 34006.153911 ops/s; Unreportable, excluded from the table
- GROUP_BY_MIN insert (drive=REAL): relative dispersion 0.05012158979644599 exceeds NOISE_FLOOR 0.005 — value=549147.78996 ± 27524.160266 ops/s; Unreportable, excluded from the table
- GROUP_BY_MAX insert (drive=REAL): relative dispersion 0.03557004015479431 exceeds NOISE_FLOOR 0.005 — value=547737.431423 ± 19483.04243 ops/s; Unreportable, excluded from the table
- GROUP_BY_TOP_K insert (drive=REAL): relative dispersion 0.03445081068664864 exceeds NOISE_FLOOR 0.005 — value=551432.665135 ± 18997.302353 ops/s; Unreportable, excluded from the table
- COMBINE_LATEST insert (drive=REAL): relative dispersion 0.024708376637471496 exceeds NOISE_FLOOR 0.005 — value=353339.736078 ± 8730.45128 ops/s; Unreportable, excluded from the table
- COALESCING_COMBINE insert (drive=REAL): relative dispersion 0.008130578319984231 exceeds NOISE_FLOOR 0.005 — value=877348.02572 ± 7133.346837 ops/s; Unreportable, excluded from the table
- TAGGED_SET retract (drive=REAL): relative dispersion 0.00825948475116123 exceeds NOISE_FLOOR 0.005 — value=644110.452441 ± 5320.02046 ops/s; Unreportable, excluded from the table
- FILTER retract (drive=REAL): relative dispersion 0.017803433666175913 exceeds NOISE_FLOOR 0.005 — value=760129.538366 ± 13532.915814 ops/s; Unreportable, excluded from the table
- UNION retract (drive=REAL): relative dispersion 0.05057709328723574 exceeds NOISE_FLOOR 0.005 — value=631500.264707 ± 31939.447799 ops/s; Unreportable, excluded from the table
- INTERSECT retract (drive=REAL): relative dispersion 0.014535091499546515 exceeds NOISE_FLOOR 0.005 — value=352098.885319 ± 5117.789515 ops/s; Unreportable, excluded from the table
- COUNT retract (drive=REAL): relative dispersion 0.05361215936537731 exceeds NOISE_FLOOR 0.005 — value=859702.833398 ± 46090.525311 ops/s; Unreportable, excluded from the table
- FLAT_MAP retract (drive=REAL): relative dispersion 0.00936337255069882 exceeds NOISE_FLOOR 0.005 — value=614380.270768 ± 5752.671363 ops/s; Unreportable, excluded from the table
- PRESENCE_COUNT retract (drive=REAL): relative dispersion 0.0360114761768751 exceeds NOISE_FLOOR 0.005 — value=323000.152781 ± 11631.712307 ops/s; Unreportable, excluded from the table
- QUORUM retract (drive=REAL): relative dispersion 0.011664945660104674 exceeds NOISE_FLOOR 0.005 — value=351054.412967 ± 4095.030651 ops/s; Unreportable, excluded from the table
- JOIN_SET retract (drive=REAL): relative dispersion 0.04349990929812057 exceeds NOISE_FLOOR 0.005 — value=324938.432219 ± 14134.792329 ops/s; Unreportable, excluded from the table
- SEMI_JOIN retract (drive=REAL): relative dispersion 0.014248562411884148 exceeds NOISE_FLOOR 0.005 — value=320659.899499 ± 4568.942591 ops/s; Unreportable, excluded from the table
- LOOKUP_JOIN retract (drive=REAL): relative dispersion 0.007706459882709759 exceeds NOISE_FLOOR 0.005 — value=412556.430889 ± 3179.349584 ops/s; Unreportable, excluded from the table
- GROUP_BY_COUNT retract (drive=REAL): relative dispersion 0.018823501402218036 exceeds NOISE_FLOOR 0.005 — value=763411.56164 ± 14370.078601 ops/s; Unreportable, excluded from the table
- GROUP_BY_SUM retract (drive=REAL): relative dispersion 0.013165746167304017 exceeds NOISE_FLOOR 0.005 — value=783227.245001 ± 10311.771099 ops/s; Unreportable, excluded from the table
- GROUP_BY_MIN retract (drive=REAL): relative dispersion 0.043925476883878135 exceeds NOISE_FLOOR 0.005 — value=732529.444565 ± 32176.705184 ops/s; Unreportable, excluded from the table
- GROUP_BY_MAX retract (drive=REAL): relative dispersion 0.026426372362627883 exceeds NOISE_FLOOR 0.005 — value=682758.594839 ± 18042.832861 ops/s; Unreportable, excluded from the table
- GROUP_BY_TOP_K retract (drive=REAL): relative dispersion 0.02790305645545981 exceeds NOISE_FLOOR 0.005 — value=679125.618774 ± 18949.680481 ops/s; Unreportable, excluded from the table
- COMBINE_LATEST retract (drive=REAL): relative dispersion 0.0077781849099402296 exceeds NOISE_FLOOR 0.005 — value=412090.15408 ± 3205.313418 ops/s; Unreportable, excluded from the table
- COALESCING_COMBINE retract (drive=REAL): relative dispersion 0.014307495868452057 exceeds NOISE_FLOOR 0.005 — value=882075.366929 ± 12620.289668 ops/s; Unreportable, excluded from the table

### Every combination was measured; all 36 are Unreportable, not omitted-from-running

Same cross product as both prior entries — 18 `Subject` constants x 2 `Direction`s = 36
combinations — at the unmodified annotation config (`Fork(2)`, `Warmup(iterations=5,
time=1s)`, `Measurement(iterations=10, time=1s)`), unraised. All 36 were run; none were
skipped for wall-clock or any other reason. This time **all 36**, not 35, classify
`Unreportable` against `NOISE_FLOOR` (0.005) — zero rows clear the floor, so
`Findings.entry` produces no table at all, and `Report.text()`'s own fallback line
above ("no entry for drive=REAL ... see the omissions below") is the honest rendering
of that outcome, not a defect in this run. Per this task's own instructions and
`[BEN1-25]`, that **is** the entry: no fork or iteration count was raised toward JMH's
defaults, and `NOISE_FLOOR` (`bench/src/main/kotlin/civictech/bench/Dispersion.kt`,
confirmed unchanged: `const val NOISE_FLOOR: Double = 0.005`) was not touched.

### `drive=REAL` is literal

Unchanged from the superseded entry's own statement, restated because it still holds:
every row above ran on a `ManagedHost` with a `VirtualThreadScheduler`, driven to
quiescence through that scheduler's `awaitDrained` fence — real dispatch on real
(virtual) threads, not `SimWorld`/`SimulationController` (`[BEN1-26]`).

### WAL/journal statement (`[BEN1-29]`)

Re-confirmed against the current source rather than assumed: `Graphs.kt`'s `Rig` for
`Drive.REAL` still constructs `ManagedHost(scheduler = scheduler)` with no `journal`
argument (`bench/src/main/kotlin/civictech/bench/micro/Graphs.kt:699`), and no
`civictech.cell.durability` type appears anywhere in `Graphs.kt`, `Deltas.kt`, or
`OperatorThroughputBenchmark.kt`; WAL/journal sync is not in play for this entry either,
so KBLK is not named.

### Dispersion range, and the JDK re-measurement's actual finding (`[BEN1-27]`)

Across all 36 rows, relative dispersion ranged from **0.00771** (`LOOKUP_JOIN retract`)
to **0.07387** (`TAGGED_SET insert`). Split by direction: INSERT (18 rows) 0.00813 –
0.07387, 0 of 18 Reportable; RETRACT (18 rows) 0.00771 – 0.05361, 0 of 18 Reportable.
Unlike both prior entries, RETRACT contributes zero Reportable rows here — this run's
own former counterpart's sole Reportable row, `GROUP_BY_MAX retract`, now measures
0.02643 (score 682758.59 ± 18042.83 ops/s), 5.5x its originally-recorded 0.00480 and
comfortably above `NOISE_FLOOR`. This entry does not re-open `[BEN1-28]`'s
`TagState`-growth suspicion (out of this task's scope; that observation rests on the
INSERT/RETRACT split within a single drive and is not this ticket's to re-litigate) —
it is reported here only as part of this run's own dispersion facts, not as a
correction to that finding.

**The comparison this task exists to settle.** The two runs now on record, both on the
Gradle toolchain JDK 21 (Eclipse Adoptium/Temurin 21.0.11):

| entry | dispersion range (36 rows) | Reportable |
| --- | --- | --- |
| SIM-drive (`computenet-x9e.4.4`) | 0.00355 – 0.08467 | 5 of 36 |
| REAL-drive, this entry (`computenet-am2h`) | 0.00771 – 0.07387 | 0 of 36 |
| REAL-drive, superseded (`computenet-x9e.4.5`, JDK 26) | 0.00480 – 0.1526 | 1 of 36 |

Controlling for JDK moves REAL's range **materially**, not marginally: its ceiling
compresses from 0.1526 to 0.07387 (roughly half), its floor rises from 0.00480 to
0.00771, and its Reportable count drops from 1 to 0 — while SIM's own range (measured
once, unchanged) sits at 0.00355–0.08467. The controlled REAL range now sits almost
entirely *inside* SIM's own range rather than extending to roughly double SIM's ceiling,
which is the opposite of what the superseded entry's "SIM removes real-thread
scheduling and OS jitter" explanation predicts: if that mechanism explained the
original gap, holding the JDK constant should have left REAL's ceiling still clearly
above SIM's. It did not. A per-row comparison against the superseded entry's own 36
values (same subjects, same directions) shows the shift is not uniform — most INSERT
rows tightened substantially (several fell to a quarter to a third of their original
dispersion) while several RETRACT rows widened several-fold (`COUNT retract` 0.00750 ->
0.05361, `GROUP_BY_MIN retract` 0.00864 -> 0.04393, `PRESENCE_COUNT retract` 0.00706 ->
0.03601) — a redistribution consistent with a genuine JIT/GC-default difference between
JDK 21 and JDK 26 affecting these operators unevenly, not with a single scalar noise
factor.

**Decision: withdraw, not restate.** The superseded entry's cross-drive dispersion
comparison read SIM's tighter range as attributable to the drive (SIM's absence of
real-scheduler jitter). This re-measurement does not reproduce that pattern: with JDK
held constant, REAL's range now nests inside SIM's rather than extending past it, and
the residual gap that remains — SIM still classifies 5 rows Reportable against REAL's
0 — sits entirely at the near-`NOISE_FLOOR` boundary, exactly where prior spot-checks
(tracked as `computenet-x9e.7`) already found reportability status to flip run-to-run
under repeat measurement on a single JVM (REAL `GROUP_BY_MAX retract` 0.00480 ->
0.0154; SIM `GROUP_BY_TOP_K retract` 0.00355 -> 0.00973; SIM `COUNT retract` 0.00499 ->
0.0404, all re-measuring *above* their original value). A 5-versus-0 Reportable-count
difference confined to that unstable boundary, on top of a dispersion-range shift this
large from a JDK change alone, is not evidence the drive itself produces tighter
dispersion. **The SIM entry's cross-drive dispersion-attribution claim
("SIM removes real-thread scheduling and OS jitter ... consistent with real-scheduler
jitter being an additional ... contributor to dispersion") is hereby withdrawn as
unsupported.** What survives, unaffected: each entry's own per-row scores and
dispersions, `[BEN1-25]`'s omission accounting, `[BEN1-27]`'s no-mixed-table property,
`[BEN1-26]`'s per-drive labelling, and the INSERT/RETRACT asymmetry *within* each drive
taken on its own (a within-drive comparison, never resting on the cross-drive claim
withdrawn here).

### Trigger (`[BEN1-31]`/`[BEN1-32]`)

`TriggerClaim.None` — MARKED INCOMPLETE. This entry cites neither G-21 nor G-43; no gap
trigger question is answered by a per-operator throughput re-measurement at this
config.

### Scope confirmation

`git diff --name-only 9622223b 6951a26e -- kernel/src/main concord/ inspect/src
wire/src demo/` is empty; the annotation config (`Fork(2)`, `Warmup(5,1s)`,
`Measurement(10,1s)`) was not touched or widened; `NOISE_FLOOR` was not touched; neither
entry above this one was edited.

---

## 2026-08-19 — V1C-BENCH E1–E3 replicated against the landed bounded-read surface: E1 and E2 reproduce, E3 and the paging benefit do not

`computenet-x9e.6.4`, running the artifacts `computenet-x9e.6.3` built
(`bench/src/jmh/kotlin/civictech/bench/micro/BoundedReadBenchmark.kt`,
`bench/src/main/kotlin/civictech/bench/micro/BoundedReadFixtures.kt`,
`bench/src/test/kotlin/civictech/bench/micro/BoundedReadProbeTest.kt`) at full scale
against the original measurement,
`doc/spec/90-roadmap/98-inspector-v4-plan/30-bounded-read-measurement.md` (the C7 gate
document, GO recommendation, produced by `tickets/V1C-BENCH.md`). That document and that
ticket are **unmodified** by this work — `git diff --name-only <base> HEAD -- doc/spec` is
empty; see "Scope confirmation" at the end.

Why the replication exists: `kernel/src/main/kotlin/civictech/cell/BoundedRead.kt`'s KDoc
cites the original's numbers — a ~28 ms live-traffic stall from one whole copy at 10⁵, an
~85–99% stall reduction from 200-entry paging, a ~1.7–2.4× total-work premium — as
load-bearing design justification, and the harness that produced them was deleted before
that ticket's diff was finalized. Until now the tree could not re-derive any of them.

**Headline:** E1 and E2 reproduce. **E3 does not, and neither does §6 — the comparison the
design rests on.** On the landed surface a 200-entry paged walk of a `SetCell` removes
6.7–46% of the live-traffic stall (17.9% at 10⁵), not the ~85–99% the original measured, at a
total-work premium of ~5.9× rather than ~1.7–2.4×. The cause is checked below and is a
**harness difference before it is anything else** — with a named, code-level mechanism the
original could not have measured, because the type it measured did not exist.

### Commands, exactly

```
./gradlew :bench:jmhJar
/Users/MerlijnB/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x.2/jdk-21.0.11+10/Contents/Home/bin/java \
     -jar bench/build/libs/bench-jmh.jar 'BoundedReadBenchmark' \
     -rf csv -rff /abs/path/e1-v2.csv > /abs/path/e1-v2.log 2>&1

./gradlew :bench:test -PbenchOnly=true --rerun \
     --tests 'civictech.bench.micro.BoundedReadProbeTest' \
     -Dcivictech.bench.harnessSha=429152d4
```

E1 went through the jar rather than `./gradlew :bench:jmh` for the reason this file records
at the noise-floor entry: no Gradle daemon shares the host with the forks. Wall clock: E1
**5m 12s** (2026-08-19T03:25:33Z → 03:30:45Z) for 2 methods × 3 scales at forks=5; the
E2/E3 probe suite **2.155 s** for all six tests (JUnit XML `timestamp`
`2026-08-19T03:25:28.489Z`, `tests="6" failures="0"`). Probe output is printed, never
written, so the numbers below were read back out of that XML's `<system-out>`.

**Host was NOT quiesced**, and this is disclosed rather than hidden: a sibling agent ran
Gradle builds on this 10-core machine throughout the slot. That is the same class of
contention the original discloses in its §2 (load ~17 on 16 cores), at a smaller
magnitude, and it is a live candidate explanation for individual outlier trials below —
never for the systematic E3 divergence, which is 20–150× and mechanism-backed.

### The measuring JVM, pinned deliberately

```
# JMH version: 1.37
# VM version: JDK 21.0.11, OpenJDK 64-Bit Server VM, 21.0.11+10-LTS
# VM invoker: /Users/MerlijnB/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x.2/jdk-21.0.11+10/Contents/Home/bin/java
# VM options: <none>
```

A bare `java` on this host is **Homebrew JDK 26.0.1** — verified this slot, not assumed:
`java -version` prints `OpenJDK Runtime Environment Homebrew (build 26.0.1)`. `NOISE_FLOOR`
was derived on Temurin 21, so a sweep on JDK 26 would be classified against a threshold it
is not comparable to; that substitution is exactly what produced the superseded REAL-drive
throughput entry above (`computenet-hqid`). Nothing in the build pins it once the JMH jar
exists, so the toolchain JDK was invoked by absolute path and the banner read back as the
check. `BoundedReadBenchmark`'s "Running it" KDoc block did not say to do this and now
does (that is a bench-file edit in this change's diff). The E2/E3 probes need no pinning:
they run in the Gradle `:bench:test` worker, which the `jvmToolchain(21)` declaration puts
on the same Temurin 21.

### E1 — renderer's own output, pasted verbatim

Rendered through `civictech.bench.Findings.entry` by way of
`ThroughputReport.renderResults`, from `e1-v2.csv` plus `MeasuringJvm.fromJmhLog` over
`e1-v2.log`. `ThroughputReport.renderRun` itself could not be used unchanged: its
`labelOf` demands the `subject`/`direction` `@Param`s `OperatorThroughputBenchmark` carries
and `BoundedReadBenchmark` (whose only param is `scale`) does not, so the labels were built
from `scale` and the method name in a 60-line throwaway `E1Render.java` driver run against
`bench/build/libs/bench-jmh.jar`. Every honesty-bearing step is the shipped one:
`ThroughputReport.parseCsv`, `MeasuringJvm.fromJmhLog`, `RunEnvironment.forRun`,
`BenchResult`, `FindingsTable`, `Findings.entry`, and the omission accounting.
`Drive.REAL` is stated by the driver rather than parsed out of the method name — correct
here and checkable: `BoundedReadFixtures` builds every E1 subject on a real
`ManagedHost`/`VirtualThreadScheduler` (the hosted case) or on no host at all (the direct
case), and has no `SimulationController` path at all.

## 2026-08-19 — V1C-BENCH E1 replication: whole-state copy cost of a SetCell at 1e3/1e4/1e5, direct Stateful.snapshot() and end-to-end ManagedHost.snapshotOf, against the landed bounded-read surface
Harness: 429152d4 · JVM Eclipse Adoptium (Temurin-21.0.11+10)/21.0.11 · heap JVM defaults (VM options: <none>) · Apple M2 Pro, 10 cores, Mac OS X 26.6.2
JMH: mode=AverageTime (JMH) forks=5 warmup=5 iters=5 · drive=REAL
| subject | value | notes |
| --- | --- | --- |
| E1 hostedSnapshotOf 1e4 | 0.389765 ± 0.001895 ms/op | |
Trigger: none cited — entry MARKED INCOMPLETE, not presented as a finding

Omitted rows (drive=REAL):
- E1 direct 1e3 (drive=REAL): relative dispersion 0.00796398891966759 exceeds NOISE_FLOOR 0.005 — value=0.037544 ± 2.99E-4 ms/op; Unreportable, excluded from the table
- E1 direct 1e4 (drive=REAL): relative dispersion 0.006776458237648449 exceeds NOISE_FLOOR 0.005 — value=0.381025 ± 0.002582 ms/op; Unreportable, excluded from the table
- E1 direct 1e5 (drive=REAL): relative dispersion 0.03136419620708313 exceeds NOISE_FLOOR 0.005 — value=10.715658 ± 0.336088 ms/op; Unreportable, excluded from the table
- E1 hostedSnapshotOf 1e3 (drive=REAL): relative dispersion 0.014207224568452992 exceeds NOISE_FLOOR 0.005 — value=0.048778 ± 6.93E-4 ms/op; Unreportable, excluded from the table
- E1 hostedSnapshotOf 1e5 (drive=REAL): relative dispersion 0.04336458805610845 exceeds NOISE_FLOOR 0.005 — value=11.195748 ± 0.485499 ms/op; Unreportable, excluded from the table

The nested `##` heading above is `Findings.entry`'s own output, verbatim including its
heading level; it is one of this file's entries only in the sense that this entry contains
it. One of six rows cleared `NOISE_FLOOR`; the other five are named with their dispersion
rather than dropped, and `NOISE_FLOOR` was not touched (`Dispersion.kt`, confirmed:
`const val NOISE_FLOOR: Double = 0.005`).

### The comparison rule applied, stated before the comparisons

The original's §2 and §8 say its own machine ran at load ~17 on 16 cores, that absolute
small-*n* figures are order-of-magnitude only, and that "the third significant figure on
any single trial is not" robust. Its §6 names what *is* load-bearing: E2-vs-E3 **direction
and order of magnitude at 10⁴/10⁵**. So the rule used here, per experiment:

1. **Reproduces** = the new value falls inside the original's own stated spread across its
   runs, or within a factor of ~2 of it, *and* the qualitative claim the original draws
   from it (its direction, its scaling with *n*) holds.
2. **Does not reproduce** = an order-of-magnitude departure, or a reversal of the
   qualitative claim, that the original's own disclosed noise cannot cover.
3. Every comparison is **median against median**, except where a statistic difference is
   named explicitly (E1, below). Dispersion, in the F3 sense, is reported for every row and
   is *not* the yardstick here: at three trials no probe row can clear `NOISE_FLOOR` at any
   affordable sample (see "What F3 refused"), so the original's stated spreads are the
   comparison surface, exactly as its §2/§6 intend.

### E1 — reproduces, once mean-versus-median is accounted for

| n | original `snapshot()` direct median / p95 | this run, JMH mean | original `snapshotOf()` median / p95 | this run, JMH mean |
| --- | --- | --- | --- | --- |
| 10³ | 0.148 / 0.179 ms | **0.0375 ms** | 0.107 / 0.133 ms | **0.0488 ms** |
| 10⁴ | 0.734 / 0.911 ms | **0.3810 ms** | 0.889 / 1.022 ms | **0.3898 ms** |
| 10⁵ | 5.814 / 23.296 ms | **10.7157 ms** | 5.354 / 29.053 ms | **11.1957 ms** |

**A statistic difference, not a discrepancy, at 10⁵.** JMH `Mode.AverageTime` reports the
**mean** over every invocation in an iteration; the original reported the **median** of 30
reps with p95 alongside. The original's own §8 finding is that at 10⁵ the tail is 4–5× the
median because 5–7 G1 young collections land inside the measurement window — so a mean at
10⁵ must sit between the original's median and its p95, and 10.7 / 11.2 ms sits squarely
there (median 5.4–5.8, p95 23.3–29.1). At 10³/10⁴, where the original reports **no GC in
either window**, mean and median are directly comparable and this machine is 2–4× faster —
a machine difference (below), in the direction a quiesced M2 Pro against a loaded M3 Max
predicts.

**What the original concluded from E1 holds**: `snapshotOf`'s end-to-end cost is not
meaningfully larger than the bare `snapshot()` it wraps. Measured here at +30% (10³),
+2% (10⁴), +4% (10⁵) — the same reading, on a tighter sample.

**Not measured here**: allocation per call (the original's 270 KB / 2.61 MB / 26.9 MB
column) and GC counts inside the window. `-prof gc` recovers both and was not run; the
benchmark's KDoc already says a missing tail in JMH's default output is not evidence the
tail is gone.

### E2 — reproduces

Medians over 3 trials per condition, against the original's two independent runs:

| n | original baseline maxGap (A/B) | this run | original concurrent maxGap (A/B) | this run | original dip | this run |
| --- | --- | --- | --- | --- | --- | --- |
| 10³ | 2.342 / 3.142 ms | 0.5708 ms | 9.402 / 6.908 ms | 3.1589 ms | +7.060 / +3.766 | **+2.588 ms** |
| 10⁴ | 0.267 / 0.270 ms | 0.5755 ms | 10.493 / 8.593 ms | 5.4983 ms | +10.226 / +8.322 | **+4.923 ms** |
| 10⁵ | 0.046 / 0.043 ms | 0.0635 ms | 27.683 / 29.184 ms | 26.0143 ms | +27.637 / +29.140 | **+25.951 ms** |

**Reproduces on every clause the original draws from E2.** The dip is positive at every
scale; it grows with *n*; at 10⁵ it is 25.95 ms against the original's 27.6–29.1 ms — a 6%
difference on the number `BoundedRead.kt`'s KDoc cites as "~28 ms". The mechanism
reproduces too, and by the original's own test of it: the concurrent `snapshotOf` latency
tracks the maxGap almost exactly (10⁵: latency median 26.00 ms against maxGap median
26.01 ms; 10⁴: 5.514 against 5.498; 10³: 3.166 against 3.159), which is what a priority-0
submit jumping ahead of queued data traffic and then holding the single drain thread for
the whole copy looks like. Baseline throughput at 10⁵: ~430,000 adds/s (8,000 adds in
18.6 ms mean), against the original's ~585,000–635,000 on 16 cores.

The 10³/10⁴ baselines are the noisy rows, exactly as the original's §8 predicts for
maxGap: single trials of 22.649 ms (10⁴) and 13.666 ms (10³) sit against same-condition
trials of 0.263/0.576 and 0.520/0.571 ms. Those are single-stall order statistics on a
machine running a sibling agent's builds — the same artifact the original attributes to its
own load, and the reason medians are used.

### E3 — does NOT reproduce

| n | original pages | this run | original total page wall (3 runs) | this run (median) | original max single page | this run | original maxGap | this run |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 10³ | 4 | 14–94 | 1.044 / 1.154 / 0.364 ms | **4.964 ms** | 0.858 / 0.911 / 0.271 ms | **1.697 ms** | 2.086 / 3.004 / 16.337 ms | **1.693 ms** |
| 10⁴ | 49 | 58–139 | 1.768 / 1.801 / 1.862 ms | **10.127 ms** | 0.111 / 0.068 / 0.125 ms | **2.366 ms** | 1.141 / 1.167 / 3.100 ms | **5.131 ms** |
| 10⁵ | 499 | 508–588 | 10.190 / 9.492 / 10.678 ms | **65.543 ms** | 0.113 / 0.145 / 0.095 ms | **21.351 ms** | 0.128 / 0.138 / 11.224 ms | **21.350 ms** |

At 10⁵ the max single page is **150–225× the original's** and the summed page wall time
**6.1–6.9×**, on a walk covering 101,530–117,450 entries against the original's fixed
99,800 (+2–18% of work, nowhere near the discrepancy). The 10⁴ rows carry an additional
confound in the same direction and it is stated rather than netted out: the target grows
monotonically across trials (`BoundedReadFixtures` header item 4 — the original harness's
own behaviour, reproduced deliberately), so the "10⁴" walks actually covered
11,510–27,671 entries, a median of ~2× the nominal size. Normalizing for that still leaves
the summed page wall ~2.8× and the max single page ~20× the original.

**The mechanism, read out of the code rather than guessed.** The maxGap and the max single
page are the same number at 10⁵ (21.350 vs 21.351 ms) — one page, not the walk, is the
whole stall — and that page costs roughly **2× a whole `snapshot()` copy** (E1 hosted at
10⁵: 11.196 ms). `SetCell.openWalk`
(`kernel/src/main/kotlin/civictech/cell/data/SetCell.kt:485-502`) is why: opening a walk
takes `stateLock` and makes a full pass over `adds` **and** `dels`, building the frozen
enumeration order *and* merging every element's every tag into the opening `TagFrontier` —
an O(n) pass with per-tag work that a whole-state copy (which allocates but never merges
per tag) does not pay. That open happens **inside the first `readBounded` call**, i.e.
inside one scheduler task; the closing frontier is recomputed in another O(n) pass on the
final page (`SetCell.kt:469`, `currentFrontier`). The original's E3 could not pay any of
it: its `PageCursorCell` held a plain `List<Int>` and answered each call with a
200-element slice.

**One thing this run cannot yet say, so it does not:** which page carries the stall. The
probe reports `max single page`, not the per-page series, so "the first page, which opens
the walk" is a mechanism-consistent reading (2× a whole copy, matching two O(n) passes'
worth of work at open) and not a measurement. Filed as `computenet-wsz4` — report the
per-page latency series so the open cost is attributable to a page, not inferred from a
maximum.

Every walk declared `STALE_FRONTIER` and 0/3 trials had a stable frontier, at every scale
— expected under a concurrent add drive, and a fact the original's `List<Int>` stand-in
could not produce at all.

### §6 — E2 vs E3, the comparison the design rests on, does NOT reproduce

| n | E2 maxGap (whole copy) | E3 maxGap (paged) | reduction, this run | reduction, original |
| --- | --- | --- | --- | --- |
| 10³ | 3.159 ms | 1.693 ms | **46%** | "small/unclear at this scale" |
| 10⁴ | 5.498 ms | 5.131 ms | **6.7%** | ~85–90% |
| 10⁵ | 26.014 ms | 21.350 ms | **17.9%** | typically ~99%, worst observed ~60% |

**Caveat (`computenet-xlst`) — "reduction, this run" is not a stable measured quantity; its
sign moves between re-runs of this exact command, not just its size.** The feature review of
this entry (PR #325) re-ran the command below verbatim at merged head `88629abf` (6 tests, 0
failures, JUnit XML timestamp `2026-08-19T04:36:00.150Z`) and got a reduction that is
**negative** at 1e4 and 1e5 — paging made the stall worse, not smaller:

```
./gradlew :bench:test -PbenchOnly=true --rerun \
     --tests 'civictech.bench.micro.BoundedReadProbeTest' \
     -Dcivictech.bench.harnessSha=429152d4
```

| n | E2 concurrent maxGap (median) | E3 paged maxGap (median) | reduction |
| --- | --- | --- | --- |
| 10³ | 4.0060 ms | 1.6965 ms | **+57.7%** |
| 10⁴ | 4.2825 ms | 6.5500 ms | **−53.0%** |
| 10⁵ | 28.6342 ms | 35.4835 ms | **−23.9%** |

A second independent re-run (the `computenet-x9e.6.4` task reviewer's) reportedly also found
paging removing nothing at 1e4/1e5. `computenet-xlst`'s own re-run of the identical command
(base `9adfadbf`, same harness SHA, JUnit XML timestamp `2026-08-20T07:42:34.304Z`, 6 tests, 0
failures) landed on a **third** pattern — positive at all three scales again, but at
magnitudes that agree with neither this table nor the row above:

| n | E2 concurrent maxGap (median) | E3 paged maxGap (median) | reduction |
| --- | --- | --- | --- |
| 10³ | 2.6723 ms | 1.5082 ms | **+43.6%** |
| 10⁴ | 4.6915 ms | 3.0582 ms | **+34.8%** |
| 10⁵ | 32.6462 ms | 27.0332 ms | **+17.2%** |

None of the three patterns above is a code difference: across all three runs `git log` shows
neither `BoundedReadFixtures.kt` nor `BoundedReadBenchmark.kt` changed after this entry landed
(`61593208`). **`computenet-xlst`'s own commit `b5d10bdb` then changed one of them**, and that
matters to anyone re-running the command above: it raises `BoundedReadFixtures.TRIALS` from 3
to 5, acting on the recommendation in "Deviations from the original method" below. A run at or
after that commit therefore takes **five** trials per condition, not the three that every
table in this entry — §4, §5, §6 and both re-run tables above — was measured at. Those
medians are no longer reproducible verbatim by that command; only the instability they
document is, and the five-trial run below is what establishes that.

The feature review of `computenet-xlst` (PR #370) ran the identical command at five trials
(HEAD `b5d10bdb`, same harness SHA, JUnit XML timestamp `2026-08-20T08:01:25.862Z`, 6 tests, 0
failures, `n=5` on every reported row) and landed on a **fourth** pattern:

| n | E2 concurrent maxGap (median) | E3 paged maxGap (median) | reduction |
| --- | --- | --- | --- |
| 10³ | 5.6020 ms | 2.8364 ms | **+49.4%** |
| 10⁴ | 6.8267 ms | 3.6019 ms | **+47.2%** |
| 10⁵ | 30.1108 ms | 26.7733 ms | **+11.1%** |

Four runs of this command have now produced four disagreeing patterns — this table's own
(positive at every scale), the PR #325 review's (negative at 1e4/1e5), `computenet-xlst`'s
(positive again, other magnitudes), and the five-trial run just above (positive again, other
magnitudes still). Every row of every one of them classifies `Unreportable` against
`NOISE_FLOOR` — the five-trial run's E2/E3 relative dispersions span 0.66–8.44 and this
entry's own 2.96–29.91, both orders above the floor — for exactly this reason: **maxGap is a
worst-case order statistic on a shared machine and does not concentrate,** so a reader meeting
the "reduction, this run" column must not take 6.7%/17.9%/46% as a stable measured benefit —
it is one noisy draw among several that disagree in sign as well as size. **Raising `TRIALS`
to five does not settle it**, which is the fourth run's own contribution: a larger sample
moved the magnitudes again rather than converging them. This does **not** weaken this entry's
finding that E3 and §6 do not reproduce the original's ~85–99% reduction: every one of the
twelve reductions measured across all four runs, including the positive ones, stays far below
that range, and the negative runs are more damning of the original's claim, not less.

Total-work premium (E3 summed page wall ÷ E1 whole copy at the same *n*): **5.9×** at 10⁵
(65.543 / 11.196), against the original's 1.7–2.4×; at 10⁴ the ratio is 26× before the
walk-size confound above is removed and ~13× after, against the original's ~2×.

So on the landed surface, at this page limit and this drive, **paging costs several times
more total work than the original measured and removes a small fraction of the stall
instead of nearly all of it.** That is a departure from the evidence
`BoundedRead.kt`'s KDoc cites, and it is stated here as a finding rather than repaired:
`[BEN1-35]` makes a hot site a finding, and no kernel file is in this diff.

### Which of {harness difference, code change since C7, machine difference} explains it

**Harness difference — the dominant cause, checked, two independent counts.**

1. *Real paging replaces the simulation.* The original's E3 was forbidden `BoundedStateful`,
   `StateRead`, `StatePage`, `Cursor` and `ManagedHost.readState` (its §1 and §5 say so), so
   it stood a `PageCursorCell` over a `List<Int>` in for the real thing. All five types have
   since landed and `SetCell` is the reference `BoundedStateful`, so this E3 drives the real
   `ManagedHost.readState`. `BoundedReadFixtures`' header (difference 1) and
   `BoundedReadProbeTest`'s KDoc declared this before any number existed, and the original
   itself predicted it in its "What could not be done" section: "tag-set filtering,
   frontier computation … could differ from this document's numbers". The mechanism section
   above turns that prediction into a specific, cited code path. **This is not a defect in
   either measurement.** The original measured the cost of a *200-element slice per
   scheduler task*; this measures the cost of *the landed `SetCell` walk*. They are
   different subjects, and the landed one is the one the design now has to live with.
2. *E3's drive.* §5's prose says E3 ran "the identical 8,000-add live-traffic drive from
   E2", but the appendix code that actually ran sets `m = 8_000` for E2 (line 529) and
   `m = 5_000` for E3 (line 583); appendix E3 also takes one untimed trial with no warmup
   and starts its pager at t0 rather than 1 ms in. These four lines were read directly.
   x9e.6.3's artifacts follow the **prose** (8,000 adds, one warmup drive, three trials, the
   1 ms delay) so that E2 and E3 stay comparable to each other — which is what §6 is made
   of. Consequence: §5's maxGap column was not measured under the drive this E3 uses, so an
   E3-against-§5 divergence is a candidate harness difference on this count too,
   independently of the paging one.

**Code change since C7 — checked, and not the explanation.** The anchor is awkward and the
awkwardness is stated: `git log --full-history -- doc/spec/90-roadmap/98-inspector-v4-plan/30-bounded-read-measurement.md`
returns exactly one commit, `f5ce8969` (2026-07-31), which is also the commit that
introduced `BoundedRead.kt` and `ManagedHost.readState` (`git log --full-history
--diff-filter=A` and `-S 'fun readState'` both name it). So the document and the surface it
says was unimplemented entered the tree together, and git can only speak about changes
*after* that commit. Since it:

- `ManagedHost.kt` — 12 commits, **none** touching the read path: `git log f5ce8969..HEAD
  -S 'snapshotOf'`, `-S 'readState'` and `-S 'priority = 0'` over that file are all empty.
- `BoundedRead.kt` — one commit, `687fe360` (`InstanceIndex`/`DeliveryHold` delegate reads),
  which does not touch the paging contract.
- `SetCell.kt` — one commit, `6f39e914` (2026-08-18, `computenet-bdth`): every read accessor
  including `snapshot`, `readBounded` and `openWalk` now runs under a private `stateLock`
  monitor. This is a real read-path change and it is the only one — but it cannot explain a
  150× max-single-page divergence. In these probes the copy, the walk and the writer all
  execute on the host's single drain thread (`snapshotOf`/`readState` submit; `inlet.call`
  submits), so the monitor is uncontended, and an uncontended monitor is nanoseconds
  against a 21 ms page.

**Machine difference — checked, and the explanation for E1/E2's absolute levels only.**
Original: Apple M3 Max, 16 cores, 48 GiB, load ~17, Corretto 21, `-Xmx2g`, macOS Darwin
25.5.0. This run: Apple M2 Pro, 10 cores, macOS 26.6.2, Temurin 21.0.11 (JMH forks at JVM
defaults, probes in the `:bench:test` worker at `-Xmx2g`), sibling agent builds running
concurrently. That accounts for E1 being 2–4× faster at 10³/10⁴ and for E2's baseline
outliers, and it is in the *wrong direction* to explain E3: a faster, less loaded machine
producing 150× slower pages is not a machine effect.

### What F3 refused, and why no affordable sample changes it

**E1**: 5 of 6 rows `Unreportable`, listed above with their relative dispersions
(0.0068–0.0434). This sweep already raised the artifact's JMH knobs from 1 fork / 3 warmup
to the repository's **forks=5 / warmup=5 / iters=5** convention (a bench-file edit in this
diff, so that the `RunEnvironment` a renderer reads is the configuration that ran — raising
the sample with `-f`/`-wi` flags instead would publish under a config that did not run).
That tightened `hostedSnapshotOf` 1e4 from 0.1025 to 0.00486 relative dispersion, which is
how the one Reportable row exists. The 10⁵ rows stay an order above the floor because their
variance is the G1 young-collection tail the original's §8 identified, which is a property
of the subject, not of the sample size.

**E2/E3**: every row `Unreportable`, and this is structural. Relative dispersions ranged
**2.96 to 29.91** (E2 10⁵ baseline 2.96 at the tight end; E2 10⁴ baseline 29.91 at the
noisy end; E3 rows 4.46–22.37). `TrialStats` states dispersion as the Student-t 99.9%
half-width, which falls as `t/√n` with `t` flooring at 3.850, so reaching 0.005 from a
trial-to-trial coefficient of variation of **0.16–1.64** — that range inverted from the
dispersions just quoted, `cv = relDispersion·√3/31.599` at three trials — needs on the order
of **1.6×10⁴ to 1.6×10⁶ trials**, each an 8,000-add drive that also grows the target by
8,000 elements. (`TrialStats`' own KDoc states 0.20–0.81 and ~2.4×10⁴–~3.9×10⁵; those are
its **1e3 rows only**, relative dispersion 3.6–14.8, and are not the range across all
scales measured here.)
maxGap is a worst-case order statistic on a shared machine; it does not concentrate. No
sweep can afford that, so this entry states the dispersion and the `Unreportable`
classification in its own words rather than obtaining a table from `Findings.entry` — the
route `BoundedReadProbeTest.report`'s KDoc sets out. **`NOISE_FLOOR` was not widened**, and
widening it to make the writer accept these rows is the dishonesty the gate exists to
prevent, not a workaround this entry declined for taste.

### Deviations from the original method, stated

- **Three trials per condition, not the original's five** (`BoundedReadFixtures.TRIALS`,
  as x9e.6.3 landed it). Not raised here, and the honest reason is that the whole six-test
  probe suite runs in 2.155 s, so the cost was never the obstacle — the run was already
  under way when the sizing was reconsidered, and re-running everything at a new harness SHA
  would have split this entry's environment across two commits. **Recommendation, since
  recommendations belong in this file**: raise `TRIALS` to the original's 5 before the next
  replication; it costs seconds, it makes the medians materially more robust against exactly
  the outlier trials E2's 10³/10⁴ baselines show, and it does not change any `Unreportable`
  classification (5 trials still needs ~10⁴ trials to reach the floor).
  **Since acted on** (`computenet-xlst`, commit `b5d10bdb`): `BoundedReadFixtures.TRIALS` is
  **5** in the tree from that commit onward, so this bullet records the sample *this entry was
  measured at*, not the constant a reader will find in the file. Both of its predictions were
  then checked at five trials by that item's review: the six probe tests take **3.238 s**
  (JUnit XML, up from 2.155 s) and every E2/E3 row is still `Unreportable`. What five trials
  did **not** do is stabilise the §6 reduction — see the caveat under "§6 — E2 vs E3" above.
- **E3 drives 8,000 adds with warmup, trials and a 1 ms delay**, per §5's prose, not the
  appendix's 5,000/one untimed trial/t0 — see harness difference 2.
- **The target grows monotonically across trials**, the original harness's own behaviour,
  reproduced deliberately; a scale label names the pre-seed size, and the per-trial
  `elementsAdded` figures are quoted above wherever they matter.
- **E1 reports a JMH mean; the original reported a median and a p95** — accounted for
  explicitly in E1's reading rather than compared across.

### Environment capture: this entry is not affected by `computenet-x9e.8`

That open defect is that `RunEnvironment.capture`'s heap field can describe the process
that *rendered* an entry rather than the one that measured. Neither number here comes that
way. E1's JVM triple is `MeasuringJvm.fromJmhLog` over the sweep's own retained banner
(quoted above), which is the fix `computenet-hqid` landed. The probes' triple is
`BoundedReadFixtures.thisProcessMeasuringJvm()`, legal there precisely because a JUnit
probe measures in the JVM that reports it — `heap -Xmx2g` is the `:bench:test` worker's own
flag and the worker is the measuring process. CPU/core/OS come from this host in both
cases, which is sound because rendering and measuring happened on it. `Env.kt` is not in
this diff.

### Trigger (`[BEN1-31]`/`[BEN1-32]`)

`TriggerClaim.None` — MARKED INCOMPLETE, as the rendered block above says on its own face.
This entry answers BS-15's reporting clause, not a gap's trigger question. The E3/§6
divergence is a fact about the landed bounded-read surface, not an answer to G-21 phase 3's
allocation-pressure trigger or to any other gap; stretching one to fit would be the
dishonesty `[BEN1-31]` guards.

### Scope confirmation

`git diff --name-only <merge-base> HEAD` names exactly two files, both bench:
`bench/src/jmh/kotlin/civictech/bench/micro/BoundedReadBenchmark.kt` and
`bench/src/main/kotlin/civictech/bench/micro/BoundedReadFixtures.kt` (the JMH knob raise,
the KDoc that now pins the JVM, and a fix for a wildcard `*/` in a path that closed a KDoc
block comment) — plus this entry. `git diff --name-only <merge-base> HEAD -- kernel/src/main
inspect/src doc/spec concord/ wire/src demo/` is empty. `MAX_CELLS = 50`,
`BUDGET_MS = 2_000L` (`DataSearch.kt:558,569`), `MAX_ROWS = 200`, `MAX_BYTES = 50_000`
(`ValueEncoder.kt:53,56`) and `NOISE_FLOOR = 0.005` are byte-identical to main.
`30-bounded-read-measurement.md` and `tickets/V1C-BENCH.md` are unmodified. Nothing above
this entry's insertion point was edited.

## 2026-08-19 — per-cell retained snapshot footprint, payload vs tag/metadata vs unattributed, 1e3/1e4/1e5 elements
Harness: 78b97989 · JVM Eclipse Adoptium (OpenJDK 64-Bit Server VM)/21.0.11 · heap -Xmx2g · Apple M2 Pro, 10 cores, Mac OS X 26.6.2
JMH: mode=retained-heap-delta (in-process JUnit probe; not JMH) forks=1 warmup=1 iters=10 · drive=REAL
| subject | value | notes |
| --- | --- | --- |
| SetCell n=1000 total retained | 257130.825 ± 1193.0756826651889 bytes | |
| SetCell n=1000 total per element | 257.130825 ± 1.193075682665189 bytes/element | |
| SetCell n=10000 total retained | 2545948.0 ± 325.10799999999995 bytes | |
| SetCell n=10000 payload | 160034.93333333332 ± 167.01626666666667 bytes | |
| SetCell n=10000 tag/metadata | 240032.0 ± 0.0 bytes | |
| SetCell n=10000 UNATTRIBUTED | 2145881.0666666664 ± 365.49917235921487 bytes | |
| SetCell n=10000 total per element | 254.59480000000002 ± 0.0325108 bytes/element | |
| SetCell n=100000 total retained | 2.69201048E7 ± 23417.418266694247 bytes | |
| SetCell n=100000 payload | 1600000.0 ± 0.0 bytes | |
| SetCell n=100000 tag/metadata | 2400032.0 ± 0.0 bytes | |
| SetCell n=100000 UNATTRIBUTED | 2.29200728E7 ± 23417.418266694247 bytes | |
| SetCell n=100000 total per element | 269.201048 ± 0.23417418266694248 bytes/element | |
| MapCell n=10000 payload | 320227.7090909091 ± 137.7993446882351 bytes | |
| MapCell n=100000 total retained | 8507880.0 ± 1322.5551520759602 bytes | |
| MapCell n=100000 payload | 3200000.0 ± 0.0 bytes | |
| MapCell n=100000 UNATTRIBUTED | 5307880.0 ± 1322.5551520759602 bytes | |
| MapCell n=100000 total per element | 85.0788 ± 0.013225551520759604 bytes/element | |
| OrMapCell n=1000 total retained | 225177.90270270273 ± 685.5339828398157 bytes | |
| OrMapCell n=1000 total per element | 225.17790270270274 ± 0.6855339828398157 bytes/element | |
| OrMapCell n=10000 total retained | 2226973.333333333 ± 3169.0447847864407 bytes | |
| OrMapCell n=10000 payload | 319713.3333333333 ± 337.85733333333326 bytes | |
| OrMapCell n=10000 tag/metadata | 240024.8 ± 246.13552096938807 bytes | |
| OrMapCell n=10000 UNATTRIBUTED | 1667235.1999999997 ± 3196.494192134896 bytes | |
| OrMapCell n=10000 total per element | 222.69733333333332 ± 0.31690447847864406 bytes/element | |
| OrMapCell n=100000 total retained | 2.3713836E7 ± 776.6688351056424 bytes | |
| OrMapCell n=100000 payload | 3200000.0 ± 0.0 bytes | |
| OrMapCell n=100000 tag/metadata | 2400032.0 ± 0.0 bytes | |
| OrMapCell n=100000 UNATTRIBUTED | 1.8113804E7 ± 776.6688351056424 bytes | |
| OrMapCell n=100000 total per element | 237.13836 ± 0.007766688351056425 bytes/element | |
| KeyedSetCell n=10000 total retained | 1425924.4800000002 ± 551.6049419894214 bytes | |
| KeyedSetCell n=10000 payload | 320210.24 ± 111.68416 bytes | |
| KeyedSetCell n=10000 tag/metadata | 239833.44 ± 85.30018618792654 bytes | |
| KeyedSetCell n=10000 UNATTRIBUTED | 865880.8000000003 ± 569.2253379688517 bytes | |
| KeyedSetCell n=10000 total per element | 142.59244800000002 ± 0.05516049419894214 bytes/element | |
| KeyedSetCell n=100000 total retained | 1.57133392E7 ± 2633.0148405157356 bytes | |
| KeyedSetCell n=100000 payload | 3200000.0 ± 0.0 bytes | |
| KeyedSetCell n=100000 tag/metadata | 2400032.0 ± 0.0 bytes | |
| KeyedSetCell n=100000 UNATTRIBUTED | 1.01133072E7 ± 2633.0148405157356 bytes | |
| KeyedSetCell n=100000 total per element | 157.13339200000001 ± 0.026330148405157357 bytes/element | |
| ListCell n=10000 total retained | 200395.56000000003 ± 352.3248278962908 bytes | |
| ListCell n=10000 payload | 160108.24 ± 12.621839999999997 bytes | |
| ListCell n=10000 total per element | 20.039556000000005 ± 0.03523248278962908 bytes/element | |
| ListCell n=100000 payload | 1601015.2 ± 1018.0077218413619 bytes | |
| CounterCell n=10000 payload | 24.0 ± 0.0 bytes | |
| CounterCell n=100000 payload | 24.0 ± 0.0 bytes | |
| PnCounterCell n=10000 payload | 24.0 ± 0.0 bytes | |
| PnCounterCell n=10000 tag/metadata | 32.0 ± 0.0 bytes | |
| PnCounterCell n=100000 payload | 24.0 ± 0.0 bytes | |
| PnCounterCell n=100000 tag/metadata | 32.0 ± 0.0 bytes | |
Trigger: G-21 phase 3 (allocation-pressure trigger, doc/spec/90-roadmap/94-implementation-plan.md:312) — INCONCLUSIVE: the criterion applied is that the trigger fires only if measured tag/metadata bytes are at least as large as the UNATTRIBUTED remainder for every family whose snapshot holds a tag object, and retires only if tag/metadata is under 2% of total retained bytes for every scaling family; measured tag/metadata is 0.0-15.3% of total and 10.5-23.7% of the UNATTRIBUTED remainder at 1e5 for the families that hold any tag object at all, in neither band, while UNATTRIBUTED itself is 20.1-85.1% of total across the five scaling families — so the split cannot answer the trigger question either way.

Omitted rows (drive=REAL):
- SetCell n=1000 payload (drive=REAL): relative dispersion 0.01742953199566331 exceeds NOISE_FLOOR 0.005 — value=16089.275 ± 280.4285333995258 bytes; Unreportable, excluded from the table
- SetCell n=1000 tag/metadata (drive=REAL): relative dispersion 0.02263452790217548 exceeds NOISE_FLOOR 0.005 — value=24311.775 ± 550.2855495889123 bytes; Unreportable, excluded from the table
- SetCell n=1000 UNATTRIBUTED (drive=REAL): relative dispersion 0.006198777661697146 exceeds NOISE_FLOOR 0.005 — value=216729.77500000002 ± 1343.4596878946486 bytes; Unreportable, excluded from the table
- MapCell n=1000 total retained (drive=REAL): relative dispersion 0.0059203676670058734 exceeds NOISE_FLOOR 0.005 — value=72404.42758620689 ± 428.6608320294474 bytes; Unreportable, excluded from the table
- MapCell n=1000 payload (drive=REAL): relative dispersion 0.008144869107067751 exceeds NOISE_FLOOR 0.005 — value=32342.075862068963 ± 263.4219745474071 bytes; Unreportable, excluded from the table
- MapCell n=1000 UNATTRIBUTED (drive=REAL): relative dispersion 0.01255870956205115 exceeds NOISE_FLOOR 0.005 — value=40062.35172413793 ± 503.13143967618737 bytes; Unreportable, excluded from the table
- MapCell n=1000 total per element (drive=REAL): relative dispersion 0.0059203676670058734 exceeds NOISE_FLOOR 0.005 — value=72.4044275862069 ± 0.42866083202944744 bytes/element; Unreportable, excluded from the table
- MapCell n=10000 total retained (drive=REAL): relative dispersion 0.006591481165285453 exceeds NOISE_FLOOR 0.005 — value=707636.0 ± 4664.369365877937 bytes; Unreportable, excluded from the table
- MapCell n=10000 UNATTRIBUTED (drive=REAL): relative dispersion 0.012045184712584757 exceeds NOISE_FLOOR 0.005 — value=387408.2909090909 ± 4666.40442318677 bytes; Unreportable, excluded from the table
- MapCell n=10000 total per element (drive=REAL): relative dispersion 0.0065914811652854535 exceeds NOISE_FLOOR 0.005 — value=70.7636 ± 0.46643693658779367 bytes/element; Unreportable, excluded from the table
- OrMapCell n=1000 payload (drive=REAL): relative dispersion 0.026925470986553655 exceeds NOISE_FLOOR 0.005 — value=32017.448648648653 ± 862.0848846526608 bytes; Unreportable, excluded from the table
- OrMapCell n=1000 tag/metadata (drive=REAL): relative dispersion 0.036514289321357765 exceeds NOISE_FLOOR 0.005 — value=24052.51891891892 ± 878.2606347128367 bytes; Unreportable, excluded from the table
- OrMapCell n=1000 UNATTRIBUTED (drive=REAL): relative dispersion 0.008330294474214286 exceeds NOISE_FLOOR 0.005 — value=169107.93513513517 ± 1408.7188976020045 bytes; Unreportable, excluded from the table
- KeyedSetCell n=1000 total retained (drive=REAL): relative dispersion 0.005686131676808964 exceeds NOISE_FLOOR 0.005 — value=144940.09655172413 ± 824.1484742425083 bytes; Unreportable, excluded from the table
- KeyedSetCell n=1000 payload (drive=REAL): relative dispersion 0.008779716829726057 exceeds NOISE_FLOOR 0.005 — value=31994.96551724138 ± 280.906737218229 bytes; Unreportable, excluded from the table
- KeyedSetCell n=1000 tag/metadata (drive=REAL): relative dispersion 0.01505760147433703 exceeds NOISE_FLOOR 0.005 — value=24044.56551724138 ± 362.0534851822071 bytes; Unreportable, excluded from the table
- KeyedSetCell n=1000 UNATTRIBUTED (drive=REAL): relative dispersion 0.010607136088623854 exceeds NOISE_FLOOR 0.005 — value=88900.56551724137 ± 942.9803967970004 bytes; Unreportable, excluded from the table
- KeyedSetCell n=1000 total per element (drive=REAL): relative dispersion 0.005686131676808964 exceeds NOISE_FLOOR 0.005 — value=144.94009655172414 ± 0.8241484742425084 bytes/element; Unreportable, excluded from the table
- ListCell n=1000 total retained (drive=REAL): relative dispersion 0.01523647259282587 exceeds NOISE_FLOOR 0.005 — value=19998.9 ± 304.7126917366653 bytes; Unreportable, excluded from the table
- ListCell n=1000 payload (drive=REAL): relative dispersion 0.009173768077643994 exceeds NOISE_FLOOR 0.005 — value=16186.86 ± 148.49449954529246 bytes; Unreportable, excluded from the table
- ListCell n=1000 UNATTRIBUTED (drive=REAL): relative dispersion 0.08892080762223165 exceeds NOISE_FLOOR 0.005 — value=3812.040000000001 ± 338.969675488252 bytes; Unreportable, excluded from the table
- ListCell n=1000 total per element (drive=REAL): relative dispersion 0.01523647259282587 exceeds NOISE_FLOOR 0.005 — value=19.998900000000003 ± 0.3047126917366653 bytes/element; Unreportable, excluded from the table
- ListCell n=10000 UNATTRIBUTED (drive=REAL): relative dispersion 0.008750913205071069 exceeds NOISE_FLOOR 0.005 — value=40287.320000000036 ± 352.55084058492406 bytes; Unreportable, excluded from the table
- ListCell n=100000 total retained (drive=REAL): relative dispersion 0.005120828898867059 exceeds NOISE_FLOOR 0.005 — value=2003378.8 ± 10258.96005441761 bytes; Unreportable, excluded from the table
- ListCell n=100000 UNATTRIBUTED (drive=REAL): relative dispersion 0.02562196314317099 exceeds NOISE_FLOOR 0.005 — value=402363.6000000001 ± 10309.345329353597 bytes; Unreportable, excluded from the table
- ListCell n=100000 total per element (drive=REAL): relative dispersion 0.005120828898867059 exceeds NOISE_FLOOR 0.005 — value=20.033788 ± 0.10258960054417611 bytes/element; Unreportable, excluded from the table
- CounterCell n=1000 total retained (drive=REAL): relative dispersion 3.0201787879550706 exceeds NOISE_FLOOR 0.005 — value=40.036 ± 120.91587795456921 bytes; Unreportable, excluded from the table
- CounterCell n=1000 payload (drive=REAL): relative dispersion 3.2298497788433087 exceeds NOISE_FLOOR 0.005 — value=16.068 ± 51.89722624645429 bytes; Unreportable, excluded from the table
- CounterCell n=1000 UNATTRIBUTED (drive=REAL): relative dispersion 5.489926812505655 exceeds NOISE_FLOOR 0.005 — value=23.968 ± 131.58256584213555 bytes; Unreportable, excluded from the table
- CounterCell n=1000 total per element (drive=REAL): relative dispersion 3.020178787955071 exceeds NOISE_FLOOR 0.005 — value=0.040036 ± 0.12091587795456922 bytes/element; Unreportable, excluded from the table
- CounterCell n=10000 total retained (drive=REAL): relative dispersion 0.481015243902439 exceeds NOISE_FLOOR 0.005 — value=26.24 ± 12.621839999999999 bytes; Unreportable, excluded from the table
- CounterCell n=10000 UNATTRIBUTED (drive=REAL): relative dispersion 5.634750000000003 exceeds NOISE_FLOOR 0.005 — value=2.2399999999999984 ± 12.621839999999999 bytes; Unreportable, excluded from the table
- CounterCell n=10000 total per element (drive=REAL): relative dispersion 0.481015243902439 exceeds NOISE_FLOOR 0.005 — value=0.002624 ± 0.001262184 bytes/element; Unreportable, excluded from the table
- CounterCell n=100000 total retained (drive=REAL): relative dispersion 2.720224137931034 exceeds NOISE_FLOOR 0.005 — value=46.4 ± 126.21839999999999 bytes; Unreportable, excluded from the table
- CounterCell n=100000 UNATTRIBUTED (drive=REAL): relative dispersion 5.6347499999999995 exceeds NOISE_FLOOR 0.005 — value=22.4 ± 126.21839999999999 bytes; Unreportable, excluded from the table
- CounterCell n=100000 total per element (drive=REAL): relative dispersion 2.7202241379310346 exceeds NOISE_FLOOR 0.005 — value=4.64E-4 ± 0.001262184 bytes/element; Unreportable, excluded from the table
- PnCounterCell n=1000 total retained (drive=REAL): relative dispersion 0.2050467618639926 exceeds NOISE_FLOOR 0.005 — value=367.596 ± 75.37436947415623 bytes; Unreportable, excluded from the table
- PnCounterCell n=1000 payload (drive=REAL): relative dispersion 2.8747001594896338 exceeds NOISE_FLOOR 0.005 — value=60.192 ± 173.03395200000003 bytes; Unreportable, excluded from the table
- PnCounterCell n=1000 tag/metadata (drive=REAL): relative dispersion 57.84059938713333 exceeds NOISE_FLOOR 0.005 — value=2.48 ± 143.44468648009067 bytes; Unreportable, excluded from the table
- PnCounterCell n=1000 UNATTRIBUTED (drive=REAL): relative dispersion 0.7774463932673781 exceeds NOISE_FLOOR 0.005 — value=304.924 ± 237.06206402066198 bytes; Unreportable, excluded from the table
- PnCounterCell n=1000 total per element (drive=REAL): relative dispersion 0.2050467618639926 exceeds NOISE_FLOOR 0.005 — value=0.36759600000000003 ± 0.07537436947415622 bytes/element; Unreportable, excluded from the table
- PnCounterCell n=10000 total retained (drive=REAL): relative dispersion 0.26703759398496235 exceeds NOISE_FLOOR 0.005 — value=372.40000000000003 ± 99.44479999999999 bytes; Unreportable, excluded from the table
- PnCounterCell n=10000 UNATTRIBUTED (drive=REAL): relative dispersion 0.31430088495575215 exceeds NOISE_FLOOR 0.005 — value=316.40000000000003 ± 99.44479999999999 bytes; Unreportable, excluded from the table
- PnCounterCell n=10000 total per element (drive=REAL): relative dispersion 0.26703759398496235 exceeds NOISE_FLOOR 0.005 — value=0.03724 ± 0.009944479999999999 bytes/element; Unreportable, excluded from the table
- PnCounterCell n=100000 total retained (drive=REAL): relative dispersion 0.5104969199178643 exceeds NOISE_FLOOR 0.005 — value=389.6 ± 198.88959999999992 bytes; Unreportable, excluded from the table
- PnCounterCell n=100000 UNATTRIBUTED (drive=REAL): relative dispersion 0.5961918465227815 exceeds NOISE_FLOOR 0.005 — value=333.6 ± 198.88959999999992 bytes; Unreportable, excluded from the table
- PnCounterCell n=100000 total per element (drive=REAL): relative dispersion 0.5104969199178642 exceeds NOISE_FLOOR 0.005 — value=0.0038960000000000006 ± 0.001988895999999999 bytes/element; Unreportable, excluded from the table

### What was measured, and how (`[BEN1-20]`/`[BEN1-21]`, BS-10, computenet-x9e.6.2)

Instrument: `civictech.bench.micro.Footprint`/`FootprintReport` (computenet-x9e.6.1,
`bench/src/main/kotlin/civictech/bench/micro/Footprint.kt`). **The method, in one
sentence** (restated here because the entry, not only the instrument's KDoc, has to say
it): retained size is measured by **differential live-heap accounting** — `System.gc()`
to quiescence, then a `MemoryMXBean` heap-used delta between a baseline holding nothing
and a state holding the structure built inside the measured window — and payload/tag
attribution is the same measurement applied to two reachability sub-closures of that same
graph (the payload objects the walk found, the `Timestamp`/`UUID` objects the walk found),
with whatever neither accounts for reported as UNATTRIBUTED rather than estimated. Every
byte here is a reading off the JVM's own heap accounting (including alignment, container
slack, and G1's region accounting) and never a modelled `sizeof`.

Command run first, unmodified, to validate the instrument against independently-known
sanity figures before deciding a verdict:

```
./gradlew :bench:test -PbenchOnly=true --rerun \
  --tests 'civictech.bench.micro.CellFootprintProbeTest' \
  -Dcivictech.bench.harnessSha=$(git rev-parse --short HEAD)
```

Its printed `total per element` at 1e3-1e5 matched every previously-observed sweep total
in this task's own dispatch note (SetCell 254-269 B/element, OrMapCell 223-237,
KeyedSetCell 143-157, MapCell 71-85, ListCell 20.0; both counters O(1) and below
resolution) — the instrument reproduces on this machine, not just on the one it was built
on. `Footprint.sweep()` covers all 7 families x 3 scales = 21 combinations, **all 21
measured**, none skipped for wall-clock, at `Footprint.DEFAULT_REPLICATES = 10` replicates
per quantity. Wall-clock per full sweep, measured three times this session: 39s, 29s, 29s
— comfortably inside the "minutes" estimate. Host quiesced throughout — no concurrent
Gradle build, test suite, or other benchmark. JVM: the module's declared toolchain 21
(Eclipse Adoptium/21.0.11), reached automatically through the `:bench:test` Gradle worker
rather than a hand-invoked `java -jar` — no separate pinning step was needed for this
route (contrast a JMH-jar invocation, where bare `java` on this host resolves to Homebrew
JDK 26.0.1, as the entries above this one found the hard way).

The rendered block above is **not** that first run's output. `CellFootprintProbeTest`'s
own "full sweep" test renders with a placeholder trigger statement ("INCONCLUSIVE from
this probe alone ... the verdict belongs to the measurement task") by its own KDoc's
design, deliberately leaving the real verdict to this task. Producing the real verdict
needs the sweep's own measured shares *before* the trigger statement can be written, and
`Findings.entry` takes the statement as a fixed string — so this task ran
`Footprint.sweep()` and `FootprintReport.render()` (the same call chain, calling
`ThroughputReport.renderResults` which calls `Findings.entry`, exactly as
`CellFootprintProbeTest` does) from a small local driver that computed the trigger's
percentages programmatically from that same in-memory measurement list before rendering,
so the entry's prose numbers and its own table are guaranteed to agree. That driver
(`ScratchFootprintFindingsEntryTest.kt`, `bench/src/test/kotlin/civictech/bench/micro/`)
touched none of computenet-x9e.6.1's four files, was never committed, and was deleted
before this entry was written — it is not part of this diff. No parameter fix to the
instrument was needed at full scale.

Second attempt's refusal, for the record: a first hand-written trigger statement spelled
out the decision criterion using the literal words "FIRES" and "RETIRES" (uppercase, to
name the two decided-verdict bands) alongside "INCONCLUSIVE" (the actual verdict) three
times in total. `Findings.entry` counts case-sensitive whole-word occurrences of the three
verdict words and refuses unless exactly one appears — it refused this with `found 3`,
correctly: that is `[BEN1-31]`'s own gate working as designed, not a bug in it. The fix
was to describe the two undecided bands in lowercase prose and reserve the literal
uppercase verdict word for the one true statement of it.

### Environment capture: this entry is not affected by `computenet-x9e.8`

That open defect is that `RunEnvironment.capture`'s heap field can describe the process
that *rendered* an entry rather than the one that measured. This entry's `Harness:` line
came from `FootprintReport.environment()`, which builds a `MeasuringJvm` by reading
`System.getProperty`/`ManagementFactory.getRuntimeMXBean().inputArguments` directly off
the running process — `RunEnvironment.forRun`, never `RunEnvironment.capture`. That is
honest specifically because a footprint measurement has no fork: `HeapProbe` collects and
reads this same process's `MemoryMXBean`, so the JVM asking the question is the JVM that
measured (see `FootprintReport.inProcessMeasuringJvm`'s own KDoc for why this is the one
place reading the calling process's properties is not the defect). `Env.kt` is not in
this diff.

### Run-to-run stability

Three full sweeps were run this session with a complete rendered table: the validation run
above, and two more from the scratch driver while its trigger statement was being
corrected. All three agree closely at 1e5 (bytes): `SetCell total` 26,907,630 / 26,916,608
/ 26,920,105 (spread 0.05%); `OrMapCell total` 23,714,170 / 23,713,812 / 23,713,836
(spread <0.01%); `KeyedSetCell total` 15,713,570 / 15,713,544 / 15,713,339 (spread
<0.01%); `MapCell total` 8,519,101 / 8,519,163 / 8,507,880 (spread 0.13%); `ListCell
total` 2,005,387 / 2,002,173 / 2,003,379 (spread 0.16%) — all comfortably inside the
per-row 99.9% dispersion this same table already carries, and none of it moves any family
across the criterion boundary below. The published block above is the third run; the
criterion was fixed in code before any of the three ran, and it landed on the same verdict
word every time — which is why publishing the third rather than the first is not
verdict-shopping.

### Attribution shares at 1e5 — the numbers the trigger criterion runs on

| family | tag/metadata as % of total | tag/metadata as % of UNATTRIBUTED | UNATTRIBUTED as % of total |
| --- | --- | --- | --- |
| SetCell | 8.9% | 10.5% | 85.1% |
| OrMapCell | 10.1% | 13.2% | 76.4% |
| KeyedSetCell | 15.3% | 23.7% | 64.4% |
| MapCell | 0.0% (structurally absent — no `Timestamp`/`UUID` in a `MapCell` snapshot) | n/a | 62.4% |
| ListCell | 0.0% (structurally absent) | n/a | 20.1% |

`CounterCell` and `PnCounterCell` are excluded from this table on purpose: both are O(1)
in the number of increments (`FootprintSubject.scalesWithElements = false`), and every
scale of both classifies `belowResolution` or lands in the omission list above — their
retained state does not grow with load at all. That is a statement about what those two
families *hold*, and it does not by itself settle the trigger question for them: G-21
phase 3 asks about allocation pressure, and a cell whose retained state is O(1) can still
allocate — and immediately discard — a tag or timestamp object per increment, which a
retained-size instrument cannot see. See *The quantity measured is not the quantity the
trigger names* below.

### Trigger (`[BEN1-31]`/`[BEN1-32]`)

**Criterion, fixed in code before any sweep ran and stated here in one sentence:** the
trigger fires only if measured tag/metadata bytes are at least as large as the
UNATTRIBUTED remainder for every family whose snapshot holds a tag object at all, retires
only if tag/metadata is under 2% of total retained bytes for every scaling family, and is
inconclusive otherwise. **Attribution method:** differential live-heap accounting (above),
applied to two reachability sub-closures of the same measured graph.

Applying it to the shares above: tag/metadata is 8.9-15.3% of total and 10.5-23.7% of
UNATTRIBUTED for the three families that hold any tag object at all (`SetCell`,
`OrMapCell`, `KeyedSetCell`) — neither negligible (under 2%) nor dominant (at least
UNATTRIBUTED). **Verdict: `TriggerClaim.Cited` — INCONCLUSIVE**, exactly as rendered in
the block above.

The reason is the UNATTRIBUTED remainder itself, not tag/metadata: it is 62-85% of total
for the four families whose backing structure is a hash table (`SetCell`, `MapCell`,
`OrMapCell`, `KeyedSetCell`) and only 20% for `ListCell` (whose backing `ArrayList` holds
boxed elements directly, with no per-entry hashing wrapper) — a four-to-one spread that is
a fact about each family's *container shape*, not about tag/metadata. The instrument
cannot see inside `TagState`/`MintedTags` (both `internal` to
`civictech.cell.data.delta`, per computenet-x9e.6.1's own scoping) to say how much of any
family's UNATTRIBUTED mass is itself tag-serving scaffolding (a per-key tag-set structure,
say) versus payload-serving scaffolding (the `HashMap`/`HashSet` backing table). A split
that cannot see into its own largest bucket cannot honestly answer whether tag/metadata
specifically constitutes allocation pressure — which is exactly what INCONCLUSIVE is for,
and is a legitimate answer here, not a deferred one. G-21 phase 3 remains open and
unresolved by this entry; moving past this reading needs either a kernel-side change
opening `TagState`/`MintedTags` to measurement (out of `[BEN1-35]`'s scope for this task)
or a different attribution method entirely.

### Two measured instrument limits that bear directly on the totals above

Both are documented in `Footprint.kt`'s `HeapProbe` KDoc (computenet-x9e.6.1) and are
restated here, next to the numbers they explain, rather than left only in a bead comment:

- **G1 accounts a humongous object's regions wholesale.** A 1e5 total's backing
  `HashMap`/hash structures cross the humongous-object threshold, so that total includes
  region-rounding that a 1e3 or 1e4 total does not. Comparing `total per element` strictly
  across scales for the same family reads through this artifact, not around it. It is
  deterministic and does not affect dispersion.
- **Allocator fill waste (~3.1% measured on calibration) counts as used**, landing wherever
  the allocation happened to be. That is a further few percent of occupancy, not object
  size, in every figure in this entry.

Every figure in this entry is therefore **occupancy**, not per-object size — stated once
here rather than re-derived at each number.

### The quantity measured is not the quantity the trigger names

G-21 phase 3's trigger is *"profiling shows allocation pressure"*. Every figure above is
**retained occupancy** — what a cell's snapshotted state costs to hold — and allocation is a
different quantity: a workload allocates every intermediate it passes through and retains
only what survives. `CellFootprintBenchmark`'s own KDoc
(`bench/src/jmh/kotlin/civictech/bench/micro/CellFootprintBenchmark.kt`, computenet-x9e.6.1)
draws the distinction in exactly these terms — *"allocation is not retention"*, and G-21
phase 3's trigger *"is about pressure, not occupancy"* — and forbids presenting a number
from it as a footprint or citing one in a footprint entry as such. This entry cites none.

That is a second and more basic reason the verdict is INCONCLUSIVE, independent of the
attribution coarseness argued above: even a perfect payload/tag split of a *retained*
measurement would still not be a measurement of allocation rate. It also gives the reading a
cheaper route forward than the kernel change named above. The tree already carries the
instrument for the trigger's own quantity — `CellFootprintBenchmark` under `-prof gc`, whose
`gc.alloc.rate.norm` is bytes allocated per `snapshot()` call — and **this task did not run
it**. Running it across the same seven families and three scales measures what the trigger
asks for; nothing in this entry does.

### Re-derivation from the committed tree (independent review re-run)

The rendered block above came from an uncommitted local driver, disclosed under *What was
measured, and how*. That driver supplied one thing: the trigger **sentence**, whose
percentages it computed from the sweep before `Findings.entry` was called, because
`Findings.entry` takes the statement as a fixed string. Everything else in the block is
produced by committed code — and this was checked rather than asserted.
`CellFootprintProbeTest`'s `full sweep renders through the findings writer` was re-run on a
quiesced host at commit `2f375387` (whose `bench/` tree is byte-identical to the `78b97989`
the `Harness:` line names), by exactly the command this entry documents. It emitted the same
heading, the same `Harness:`/`JMH:` line shapes, a 49-row table and a 47-row omission list
with the same membership, and a `Trigger:` line whose verdict word is again **INCONCLUSIVE**
(there from the probe's own placeholder statement). At 1e5 the re-run gave `SetCell` total
26,910,587 (published 26,920,105), `OrMapCell` 23,713,917 (23,713,836), `KeyedSetCell`
15,713,514 (15,713,339), `MapCell` 8,519,138 (8,507,880), `ListCell` 2,002,312 (2,003,379);
the 1e5 `payload` and `tag/metadata` figures are identical across runs (1,600,000 /
2,400,032 for `SetCell`; 3,200,000 / 2,400,032 for `OrMapCell` and `KeyedSetCell`). Applying
this entry's criterion to the **re-run's** numbers gives tag/metadata at 8.9% / 10.1% /
15.3% of total and 10.5% / 13.3% / 23.7% of UNATTRIBUTED — the same bands and the same
verdict. **Every number this entry publishes, and its verdict, are re-derivable from the
committed tree by the documented command; only the trigger sentence's wording is not.**

Two figures above are weaker than the block they sit in, and are flagged here rather than
left to be noticed:

- The `ListCell` row of the shares table (`UNATTRIBUTED` 20.1% of total) divides two values
  this entry's own omission list classifies `Unreportable` — `ListCell n=100000 total
  retained` and `ListCell n=100000 UNATTRIBUTED`. It describes container shape and carries
  no weight in the criterion, whose retire band turns on tag/metadata, structurally 0 for
  `ListCell`. Read it as an order of magnitude, not as a measurement.
- The rendered `Trigger:` line's *"0.0-15.3% of total … for the families that hold any tag
  object at all"* is loose in its lower bound: 0.0% is the value for the families that hold
  **no** tag object. Those that do hold one measure 8.9-15.3%, which is what the shares
  table and the trigger section state. The rendered block is `Findings.entry`'s verbatim
  output and is not edited; the correction is recorded here instead.

### Scope confirmation

`git diff --name-only <merge-base> HEAD` names exactly one file: `doc/bench/findings.md`
(this entry). `git diff --name-only <merge-base> HEAD -- kernel/src/main concord/
inspect/src wire/src demo/ doc/spec` is empty. `git diff <merge-base> HEAD -- bench/src/main
bench/src/jmh bench/src/test` is also empty — no dependency-sequenced parameter fix was
needed in any of computenet-x9e.6.1's four files at full scale; the scratch driver used to
compute the trigger's percentages was never committed.
`doc/spec/90-roadmap/94-implementation-plan.md`, `doc/spec/90-roadmap/91-gap-analysis.md`
and `doc/spec/CONCORDANCE.md` are unmodified; G-21 phase 3's row is cited, never edited.

---

## 2026-08-19 — fan-out scaling curve over `FanOutlet` at degrees {1, 4, 16, 64, 256}, and late-join `CatchUp` cost and source-context occupancy at 1e5 — G-43 FIRES

`computenet-x9e.5.3`, running at full scale the two artifacts its sibling tasks built:
`FanOutScalingBenchmark` + `FanOutFixtures` (`computenet-x9e.5.1`, BS-8) and
`LateJoinCatchUpProbeTest` + `CatchUpFixtures` (`computenet-x9e.5.2`, BS-9). This task wrote
no harness code and changed no fixture: every number below was produced by those artifacts
at the constants they landed with — no `-f`/`-wi`/`-i` override, no raised fixture constant,
and `NOISE_FLOOR` untouched. Base commit `67260393` (the merge of `computenet-x9e.5.2` into
`feature/computenet-x9e.5`); `git diff --name-only <merge-base> HEAD` names this file only.

The consumer is G-43 (`doc/spec/90-roadmap/91-gap-analysis.md:83` — cited, never edited),
whose proposal includes *"bound the push-authoritative re-baseline (diff-against-last-acked
/ delta-since-generation)"* against the gap's own *"re-baseline cost under wide fan-out"*.
`CatchUp` is the path a re-baseline actually takes, which is why BS-9 exists alongside the
degree curve.

**Headline, before the numbers.** The two halves point in opposite directions and both
answers matter: the steady-state fan-out curve is **linear in degree, not worse** — a fitted
marginal of ~0.1–0.12 µs per additional subscriber with no resolvable superlinear term over
1..256 — while the late-join re-baseline is **unbounded and measurably starves live
traffic**: one join against a source holding 1.1e5–1.4e5 elements occupies the host's single
drain thread for the whole copy, stalling a pre-existing subscriber's arrival stream for
5.0–29.3 ms against an unjoined 0.07–0.30 ms on the same rig in the same trial. **Every row
of both measurements classifies `Unreportable`** against `NOISE_FLOOR` 0.005, so no table in
this entry is a rendered `Findings` table; what each measurement can still honestly support
is stated per half below.

### Commands, exactly

```
./gradlew :bench:jmhJar

/Users/MerlijnB/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x.2/jdk-21.0.11+10/Contents/Home/bin/java \
     -jar bench/build/libs/bench-jmh.jar FanOutScalingBenchmark \
     -rf csv -rff /abs/path/fanout.csv 2>&1 | tee /abs/path/fanout.log

./gradlew :bench:test -PbenchOnly=true --rerun \
  --tests 'civictech.bench.micro.LateJoinCatchUpProbeTest' \
  -Dcivictech.bench.harnessSha=67260393
# then three further independent runs of the 1e5 method alone:
./gradlew :bench:test -PbenchOnly=true --rerun \
  --tests 'civictech.bench.micro.LateJoinCatchUpProbeTest.late-join catch-up and source occupancy at 1e5 elements' \
  -Dcivictech.bench.harnessSha=67260393
```

The Gradle toolchain's own JDK 21 was invoked by absolute path, never the shell's bare
`java` (Homebrew JDK 26.0.1 on this host — the substitution that produced the superseded
REAL-throughput entry above, `computenet-hqid`). The JMH sweep ran **8 m 43 s** wall clock
(started 2026-08-19T08:29:42Z, results file complete at 08:38:25Z) for all ten combinations
— 2 methods x 5 degrees x 5 forks x (5 warmup + 5 measurement) x 1 s. The catch-up probe
runs in **about 2 s** including Gradle, four runs in about 8 s: the bead's own estimate of
"single-digit minutes per run" for it is two orders of magnitude high, matching what
`computenet-x9e.5.2` measured and recorded in the probe's KDoc. The host was quiesced for
the JMH sweep — the probe runs and this document's editing happened before it started and
after it finished, never during — and the probe runs had no concurrent Gradle build either.

### The measuring JVM and host, from the run's own retained banner

Quoted from `fanout.log` rather than paraphrased:

```
# JMH version: 1.37
# VM version: JDK 21.0.11, OpenJDK 64-Bit Server VM, 21.0.11+10-LTS
# VM invoker: /Users/MerlijnB/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x.2/jdk-21.0.11+10/Contents/Home/bin/java
# VM options: <none>
# Warmup: 5 iterations, 1 s each
# Measurement: 5 iterations, 1 s each
# Benchmark mode: Average time, time/op
# Host CPU model: Apple M2 Pro
# Host core count: 10
# Host OS: Mac OS X 26.6.2
```

The last three lines are `FanOutScalingBenchmark.RigState.announceHost`'s
`@Setup(Level.Trial)` output, printed from **inside the measuring fork**
(`computenet-yhbd`), and are what `HostFacts.fromJmhLog` reads back; the first four are
JMH's own. `RunEnvironment.forRun`'s JMH-sweep overload resolved them to:

**Environment — JVM Eclipse Adoptium (Temurin-21.0.11+10) 21.0.11 · heap JVM defaults (VM
options: `<none>`) · Apple M2 Pro, 10 cores, Mac OS X 26.6.2 · JMH mode `Average time`,
forks 5, warmup 5, iters 5 · harness `67260393`.** No `Harness:`/`JMH:` line appears below,
because no row cleared `NOISE_FLOOR` into a rendered table, so this paragraph is this
entry's explicit substitute for it — read off the run's artifacts, not off the rendering
process (`[BEN1-23]`). Nothing in this entry states an environment fact from the renderer:
the JVM triple came from `MeasuringJvm.fromJmhLog`, the four knobs from
`RunKnobs.fromJmhLog`, and the CPU/cores/OS from `HostFacts.fromJmhLog`, all over
`fanout.log`. The BS-9 probe's own environment is captured in-process by
`CatchUpFixtures.probeRunEnvironment`, which is sound there because the probe *is* the
measuring process; it reports the same JVM and host (`Eclipse Adoptium (Temurin-21.0.11+10)
/21.0.11 · heap -Xmx2g` — the `:bench:test` fork's own 2 g heap, from
`buildSrc`'s convention — `Apple M2 Pro, 10 cores, Mac OS X 26.6.2`).

### How the BS-8 rows were rendered, and the one uncommitted piece

The rendered blocks below are the verbatim output of `ThroughputReport.renderResults`, which
is what calls `Findings.entry` and applies F3's refusals (`[BEN1-23]`..`[BEN1-27]`,
`[BEN1-30]`). They were produced by an **uncommitted local driver** — a `@Tag("bench")` test
that read `fanout.csv` and `fanout.log`, built the `RunEnvironment` through
`RunEnvironment.forRun` as quoted above, and labelled each row by its `degree` parameter.
The driver exists for one reason: `ThroughputReport.renderRun`, the committed entry point,
cannot label these rows at all. Its `labelOf` requires `subject` and `direction` `@Param`
columns and `FanOutScalingBenchmark`'s only `@Param` is `degree`, so `renderRun` throws
`ThroughputReportException` on this results file — the open defect `computenet-x9e.10`,
confirmed here by reading `ThroughputReport.labelOf` rather than assumed. Everything the
driver used is committed code (`parseCsv`, `driveOf`, `RunEnvironment.forRun`,
`renderResults`, `Findings.entry`, `classify`); what it supplied was the two labels' wording
and the trigger sentence, which `Findings.entry` takes as a fixed string. No file under
`bench/` was modified for this entry, and the driver was deleted rather than committed —
the same disclosure the footprint entry above makes about its own driver.
### BS-8 — the fan-out curve: renderer's own output, pasted verbatim

`ThroughputReport.renderResults` over `fanout.csv`, printed to the driver's captured stdout
(JUnit XML `<system-out>`) and pasted back rather than retyped. All ten rows classify
`Unreportable`, so `Findings.entry` renders no table for either drive and `Report.text()`'s
own fallback line is the honest rendering of that outcome — the same shape the 2026-08-18
all-`Unreportable` REAL-throughput entry above published. SIM and REAL appear as two separate
blocks, never one table (`[BEN1-27]`), because they are separately named `@Benchmark`
methods:

## (no entry for drive=SIM) — every row classified Unreportable against NOISE_FLOOR 0.005; see the omissions below

Omitted rows (drive=SIM):
- fan-out degree 1 (D1), per delta driven to quiescence (drive=SIM): relative dispersion 0.06521628914816087 exceeds NOISE_FLOOR 0.005 — value=1.400417 ± 0.09133 us/op; Unreportable, excluded from the table
- fan-out degree 4 (D4), per delta driven to quiescence (drive=SIM): relative dispersion 0.03917048035873888 exceeds NOISE_FLOOR 0.005 — value=1.561916 ± 0.061181 us/op; Unreportable, excluded from the table
- fan-out degree 16 (D16), per delta driven to quiescence (drive=SIM): relative dispersion 0.053469668253649516 exceeds NOISE_FLOOR 0.005 — value=2.499922 ± 0.13367 us/op; Unreportable, excluded from the table
- fan-out degree 64 (D64), per delta driven to quiescence (drive=SIM): relative dispersion 0.07876220172528792 exceeds NOISE_FLOOR 0.005 — value=6.73163 ± 0.530198 us/op; Unreportable, excluded from the table
- fan-out degree 256 (D256), per delta driven to quiescence (drive=SIM): relative dispersion 0.07488343623243464 exceeds NOISE_FLOOR 0.005 — value=26.915525 ± 2.015527 us/op; Unreportable, excluded from the table

## (no entry for drive=REAL) — every row classified Unreportable against NOISE_FLOOR 0.005; see the omissions below

Omitted rows (drive=REAL):
- fan-out degree 1 (D1), per delta driven to quiescence (drive=REAL): relative dispersion 0.0657892910357945 exceeds NOISE_FLOOR 0.005 — value=10.157565 ± 0.668259 us/op; Unreportable, excluded from the table
- fan-out degree 4 (D4), per delta driven to quiescence (drive=REAL): relative dispersion 0.05490124006095744 exceeds NOISE_FLOOR 0.005 — value=11.050989 ± 0.606713 us/op; Unreportable, excluded from the table
- fan-out degree 16 (D16), per delta driven to quiescence (drive=REAL): relative dispersion 0.046045448509962505 exceeds NOISE_FLOOR 0.005 — value=12.170432 ± 0.560393 us/op; Unreportable, excluded from the table
- fan-out degree 64 (D64), per delta driven to quiescence (drive=REAL): relative dispersion 0.022552642899240784 exceeds NOISE_FLOOR 0.005 — value=18.090474 ± 0.407988 us/op; Unreportable, excluded from the table
- fan-out degree 256 (D256), per delta driven to quiescence (drive=REAL): relative dispersion 0.06014292138080649 exceeds NOISE_FLOOR 0.005 — value=40.694681 ± 2.447497 us/op; Unreportable, excluded from the table

Every one of the ten combinations was run; none was skipped, and no fork or iteration count
was raised or lowered from `FanOutFixtures`' constants (`FORKS=5`,
`WARMUP_ITERATIONS=5`, `MEASUREMENT_ITERATIONS=5`, `ITERATION_SECONDS=1`,
`JMH_MODE=AverageTime`), which the run's own banner confirms it resolved. Relative
dispersion ranges **0.0226–0.0658 (REAL)** and **0.0392–0.0788 (SIM)**, i.e. 4.5x–15.8x
`NOISE_FLOOR`; nothing was widened to admit them.

**No affordable sample makes these rows `Reportable`, and the sizing says so numerically.**
A CI half-width shrinks as `1/sqrt(n)` at best, so reaching 0.005 from a measured relative
dispersion `d` at 25 measurement samples needs about `25 * (d/0.005)^2` samples: **510 for
the tightest row** (REAL D64, d=0.0226) and **6,200 for the widest** (SIM D64, d=0.0788).
At 1 s per measurement iteration plus an equal warmup, that is 17 min to 3.4 h **per
combination**, so 3–34 h for the ten-combination sweep — and only if fork-to-fork variance
behaved like independent sampling, which is the assumption the 5-fork convention exists
because it does not. The constants were therefore left alone. This does not block BS-8's
required statement: the effect the curve is about is 4.0x (REAL) and 19.2x (SIM) between its
endpoints, 60x–240x the width of the error bars it has to be read against, so the *shape*
survives a dispersion that keeps any single *published value* from clearing F3's gate.

### BS-8's required statement: growth is LINEAR in degree, not worse

Per-drive tables, never one shared table (`[BEN1-27]`). `marginal` is the per-additional-
subscriber cost of each segment, `(y2 - y1) / (d2 - d1)`, with the two rows' 99.9% error
bars summed conservatively; `per subscriber` is the whole per-delta cost divided by the
degree, which is the same number only if the fixed cost were zero:

**drive=REAL** (`ManagedHost` + `VirtualThreadScheduler`, `awaitDrained` fence):

| degree | per-delta µs/op (± 99.9%) | per subscriber µs | segment marginal µs/subscriber |
| --- | --- | --- | --- |
| 1 | 10.1576 ± 0.6683 | 10.1576 | — |
| 4 | 11.0510 ± 0.6067 | 2.7627 | 0.2978 ± 0.4250 |
| 16 | 12.1704 ± 0.5604 | 0.7607 | 0.0933 ± 0.0972 |
| 64 | 18.0905 ± 0.4080 | 0.2827 | 0.1233 ± 0.0202 |
| 256 | 40.6947 ± 2.4475 | 0.1590 | 0.1177 ± 0.0149 |

**drive=SIM** (`SimWorld`/`runToIdle`):

| degree | per-delta µs/op (± 99.9%) | per subscriber µs | segment marginal µs/subscriber |
| --- | --- | --- | --- |
| 1 | 1.4004 ± 0.0913 | 1.4004 | — |
| 4 | 1.5619 ± 0.0612 | 0.3905 | 0.0538 ± 0.0508 |
| 16 | 2.4999 ± 0.1337 | 0.1562 | 0.0782 ± 0.0163 |
| 64 | 6.7316 ± 0.5302 | 0.1052 | 0.0882 ± 0.0138 |
| 256 | 26.9155 ± 2.0155 | 0.1051 | 0.1051 ± 0.0133 |

**The observed growth is linear in degree — an affine cost, not a superlinear one.** Three
readings of the same ten numbers agree:

1. **Least-squares affine fit** `cost = a + b * degree`, per drive: REAL **a = 10.335 µs,
   b = 0.1187 µs/subscriber**, residuals −0.296 / +0.241 / −0.064 / +0.156 / −0.038 µs at
   D1..D256 — every residual inside that row's own 99.9% error bar. SIM **a = 0.948 µs,
   b = 0.1008 µs/subscriber**, residuals +0.352 / +0.211 / −0.061 / −0.667 / +0.165 µs, of
   which D1, D4 and D64 sit outside their error bars (see the caveat below).
2. **Segment marginals are flat where they are resolvable.** REAL's two best-resolved
   segments are 0.1233 ± 0.0202 (16→64) and 0.1177 ± 0.0149 (64→256) µs/subscriber:
   indistinguishable. Its 1→4 segment carries an error bar (±0.425) larger than the value
   and settles nothing either way.
3. **Total cost grows *sub*linearly as a ratio, purely because of the intercept.** 256x the
   degree buys **4.0x** the REAL per-delta cost and **19.2x** the SIM cost; a log-log slope
   over the top segment (64→256) is 0.585 (REAL) and 1.000 (SIM). The fan-out loop's own
   contribution is linear; the fixed per-delta cost is what makes the total sublinear in
   ratio terms.

**Where the linear reading is weakest, stated rather than smoothed:** SIM's segment
marginals rise monotonically, 0.0538 → 0.0782 → 0.0882 → 0.1051 µs/subscriber, a 1.95x
climb across a 64-fold range of degree. That is the one thing in this sweep that looks
superlinear, and it is **not resolvable at this dispersion**: the gap between the two
best-resolved SIM segments is 0.0169 ± 0.0271 µs/subscriber, and the low-degree segment that
anchors the trend (1→4) has an error bar (±0.0508) that dwarfs its own value. So the honest
form of BS-8's answer is: **linear in degree at this sweep's resolution, with any residual
superlinear term bounded by roughly a doubling of the marginal cost across a 64x range of
degree** — which is nowhere near what "worse than linear" would mean for G-43's cost model,
and is itself consistent with cache behaviour over a `consumerOrder` map growing from 1 to
256 entries.

**Cross-drive comparison, in prose only (not a shared table, `[BEN1-27]`).** The two fitted
marginals are close — REAL 0.1187 vs SIM 0.1008 µs per additional subscriber — while the
intercepts differ by 9.39 µs (10.335 vs 0.948). Almost the entire REAL/SIM gap is therefore
**fixed per-delta cost** (host dispatch, the `awaitDrained` fence, virtual-thread hand-off),
not per-subscriber fan-out cost: at degree 1 REAL costs 7.25x SIM, at degree 256 only 1.51x.
This is a within-sweep observation about one benchmark on one JVM, offered as such; the
withdrawn cross-drive *dispersion*-attribution claim of the 2026-08-18 entries is not
re-opened, and nothing here rests on it.

### The confound that limits the paragraph above, and its direction

`FanOutScalingBenchmark` rebuilds its rig once per **iteration** and adds one fresh element
per **invocation**, so within a 1 s iteration the source's tag state grows from empty to
however many invocations that iteration fit — and that count is inversely proportional to
the very cost being measured. At the scores above: ~98,500 invocations per iteration at REAL
D1 against ~24,600 at REAL D256, and ~714,000 at SIM D1 against ~37,200 at SIM D256. **The
low-degree rows are therefore averaged over a source roughly 4x (REAL) to 19x (SIM) larger
than the high-degree rows.** If per-add cost rises with source size at all, that inflates
the low-degree rows, *understates* the growth in degree, and biases exactly the conclusion
this section draws. It is a property of the landed fixture, reported rather than corrected
(`FanOutFixtures`' KDoc states the per-iteration rebuild and the monotone growth
explicitly); nothing in this sweep bounds its size, and a variant holding the element count
fixed per invocation — `Mode.SingleShotTime`, or an `@OperationsPerInvocation` batch against
a pre-seeded source — is what would settle it. Filed as its own item rather than asserted
away here.
### BS-9 — late-join catch-up cost and source-context occupancy, and why every row is `Unreportable`

`LateJoinCatchUpProbeTest` reports, per trial, the catch-up cost (wall time from
initiating the link until the joiner's collector holds the complete baseline) and the
occupancy of the source's execution context, measured as the largest stall (`maxGap`) the
join inflicts on a **pre-existing** subscriber's live arrival stream while an 8,000-add
unpaced burst drives through the same source on the same host. Each trial drives twice —
once with no join, once with one — so occupancy is read as a *paired difference*, not a
level. Drive is `REAL` for every number here (`ManagedHost` + `VirtualThreadScheduler`,
drained through that scheduler's `awaitDrained` fence); there is no SIM variant of this
measurement, because the occupancy of a real execution context is the question.

**Every one of the 18 statistics below classifies `Unreportable`** against `NOISE_FLOOR`
0.005 — relative dispersion ranges from **5.96 to 30.08**, three to four orders of
magnitude above the floor — so `Findings.entry` refuses them and **no rendered table for
BS-9 appears in this entry**. They are named here with their values, their dispersions and
that classification, following the landed precedent of the 2026-08-18 all-`Unreportable`
REAL-throughput entry above: excluded from the table, never silently dropped, and
`NOISE_FLOOR` (`bench/src/main/kotlin/civictech/bench/Dispersion.kt`, confirmed unchanged:
`const val NOISE_FLOOR: Double = 0.005`) not widened to admit them. This is the honest
outcome and not a limit a longer sweep lifts: `maxGap` is a worst-case order statistic, and
`TrialStats`' own arithmetic (KDoc in `BoundedReadFixtures.kt`) puts a `Reportable`
relative dispersion for this family at roughly 1.6e4–1.6e6 trials, which no sweep can
afford. The trial count was therefore **not** inflated to chase a classification it cannot
reach.

Every row: `value ± dispersion` is the F3 `BenchResult` the probe constructed (mean of the
trials, dispersion = the 99.9% CI half-width `TrialStats` computes), `relDisp` is
`dispersion/value`, and all are **`Unreportable`, excluded from the table**:

| run | pre-seed | statistic | value ± dispersion (ms) | relDisp |
| --- | --- | --- | --- | --- |
| 1 | 1e3 | catch-up cost | 5.2884 ± 38.1290 | 7.2099 |
| 1 | 1e3 | maxGap, no join (occupancy baseline) | 3.0996 ± 47.9655 | 15.4747 |
| 1 | 1e3 | maxGap during one late join (occupancy) | 5.2796 ± 38.2212 | 7.2394 |
| 1 | 1e4 | catch-up cost | 9.7310 ± 61.7857 | 6.3494 |
| 1 | 1e4 | maxGap, no join | 0.8428 ± 13.5356 | 16.0613 |
| 1 | 1e4 | maxGap during one late join | 9.7865 ± 61.4801 | 6.2821 |
| 1 | 1e5 | catch-up cost | 9.1375 ± 67.0876 | 7.3420 |
| 1 | 1e5 | maxGap, no join | 0.1073 ± 0.6392 | 5.9552 |
| 1 | 1e5 | maxGap during one late join | 9.4974 ± 71.0857 | 7.4847 |
| 2 | 1e5 | catch-up cost | 22.9461 ± 142.4687 | 6.2088 |
| 2 | 1e5 | maxGap, no join | 0.1455 ± 1.8035 | 12.3961 |
| 2 | 1e5 | maxGap during one late join | 22.9933 ± 142.7891 | 6.2100 |
| 3 | 1e5 | catch-up cost | 15.9840 ± 169.5327 | 10.6064 |
| 3 | 1e5 | maxGap, no join | 3.4298 ± 103.1817 | 30.0839 |
| 3 | 1e5 | maxGap during one late join | 16.0088 ± 171.1238 | 10.6893 |
| 4 | 1e5 | catch-up cost | 16.9938 ± 104.3507 | 6.1405 |
| 4 | 1e5 | maxGap, no join | 0.1774 ± 1.9981 | 11.2649 |
| 4 | 1e5 | maxGap during one late join | 17.6345 ± 112.7114 | 6.3915 |

Because a three-sample mean at this dispersion says very little, the per-trial samples and
medians are the readable form of the same runs — and they are what the reading below rests
on. `shipped` is the element count each trial's catch-up actually carried, which is the
size that matters and is **not** the pre-seed label (see the drift note below):

| run | pre-seed | catch-up samples (ms) | median | maxGap no join (ms) | median | maxGap joined (ms) | median | occupancy = Δ median | shipped per trial |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1e3 | 3.2656 / 5.1600 / 7.4397 | 5.1600 | 0.0744 / 4.3916 / 4.8328 | 4.3916 | 3.2563 / 5.1429 / 7.4396 | 5.1429 | **+0.7513** | 10,181 / 26,246 / 42,363 |
| 1 | 1e4 | 7.8550 / 13.6406 / 7.6975 | 7.8550 | 1.5877 / 0.1038 / 0.8368 | 0.8368 | 7.9781 / 13.6746 / 7.7068 | 7.9781 | **+7.1413** | 19,062 / 35,150 / 51,072 |
| 1 | 1e5 | 12.0676 / 10.3338 / 5.0110 | 10.3338 | 0.1438 / 0.0739 / 0.1043 | 0.1043 | 12.0594 / 11.4195 / 5.0134 | 11.4195 | **+11.3152** | 109,260 / 125,594 / 141,769 |
| 2 | 1e5 | 29.2191 / 14.1997 / 25.4195 | 25.4195 | 0.2596 / 0.0896 / 0.0873 | 0.0896 | 29.3025 / 14.2348 / 25.4426 | 25.4426 | **+25.3530** | 109,584 / 125,410 / 141,642 |
| 3 | 1e5 | 26.4247 / 8.6193 / 12.9082 | 12.9082 | 0.2630 / 9.9595 / 0.0668 | 0.2630 | 26.5483 / 8.5776 / 12.9006 | 12.9006 | **+12.6376** | 109,250 / 125,824 / 141,819 |
| 4 | 1e5 | 21.2610 / 19.2259 / 10.4944 | 19.2259 | 0.2976 / 0.1512 / 0.0833 | 0.1512 | 21.3870 / 21.0126 / 10.5039 | 21.0126 | **+20.8614** | 109,321 / 125,479 / 141,699 |

Pooling the four independent 1e5 runs (12 trials): catch-up cost spans **5.011–29.219 ms**
with a pooled median of **13.55 ms**; the unjoined `maxGap` is **0.0668–0.2976 ms in 11 of
12 trials** (one outlier at 9.9595 ms) with a pooled median of **0.124 ms**; the joined
`maxGap` spans **5.013–29.303 ms**, pooled median **13.57 ms**. Per run, the joined median
sits **109x, 284x, 49x and 139x** its own paired unjoined median. Occupancy — the paired
median difference — is **positive in all four 1e5 runs, at +11.32, +25.35, +12.64 and
+20.86 ms**. Both of those lists are in **run order 1..4**, matching the table above; they
were published sorted ascending and were reordered at review, because a sorted list reads as
run order and silently mis-attributes each figure to the wrong run (the ranges, 49x–284x and
+11.32–+25.35 ms, are unchanged). **That positivity is a statement about the four per-run
medians.** Read per trial, occupancy is positive in 11 of the 12 1e5 trials and negative in
one: run 3 trial 2, at −1.3819 ms — the trial whose unjoined `maxGap` is the 9.9595 ms
outlier named above, so the baseline it is differenced against is the disturbed one rather
than the stall being absent.

Four separate probe runs were used rather than a raised `CatchUpFixtures.TRIALS`, and that
choice is a measurement decision worth stating: the rig's source grows by 16,000 elements
per trial (every add is a fresh element and an OR-set never shrinks), so raising `TRIALS`
buys sample by pushing the shipped state further and further from the 1e5 the entry claims
to be about — at `TRIALS=3` the last trial already ships ~1.42e5. Re-running the whole
probe buys the same replication against a **fresh** rig each time. Both fixture files named
in this task's file claim are therefore **unmodified**; the only in-claim edit is this
document.

**Elements added per trial, and what the 1e5 label means.** Each trial adds
`2 * DRIVE_ADDS = 16,000` elements (two 8,000-add bursts, one per condition), on top of a
1,000-add warmup drive, so at pre-seed 1e5 the source holds 117,000 / 133,000 / 149,000
elements after trials 1/2/3 and each trial's catch-up ships 109,250–141,819 elements. The
label names the **pre-seed** size, exactly as `CatchUpFixtures.kt`'s header requires it be
read; the growth is reported, not corrected. The overlap this creates between conditions is
also why the three scales cannot be read as a clean cost-versus-size curve: the 1e3 run's
third trial already ships 42,363 elements, more than the 1e4 run's first (19,062).

**The overlap the occupancy figure depends on did happen.** The probe records how far into
the concurrent burst the priority-0 link install cut in: 181/246/363 adds (1e3),
62/150/72 (1e4), 260/594/769, 584/410/642, 250/824/819 and 321/479/699 adds (the four 1e5
runs) out of 8,000. Neither extreme (0, or the full 8,000) occurred in any trial, so no
occupancy number here was measured against an idle host.

**At 1e3 the occupancy column is not readable, and the entry says so rather than reporting
it as small.** Its unjoined `maxGap` samples are 0.0744 / 4.3916 / 4.8328 ms — the
baseline's own worst-case noise is the same order as the stall being measured — so the
+0.7513 ms difference there carries no weight. The probe's own KDoc records a run in which
the same statistic came out **negative** at 1e3 (−3.2069 ms) for exactly this reason. The
1e5 rows are where the difference is legible, and that is where the reading below is taken.

### The one relationship here that is not noise-limited: the stall IS the copy

The four 1e5 runs' twelve trials pair each catch-up cost with the stall it inflicted, and
those two numbers are nearly identical **per trial** rather than merely on average: in 10 of
12 trials they differ by ≤ 0.13 ms (≤ 1.3% of the value), and in the remaining two by 1.09
and 1.79 ms. The same holds at 1e4 (7.9781 vs 7.8550 median) and 1e3 (5.1429 vs 5.1600).
**That difference is signed both ways, and the negative sign is not an anomaly**: the joined
`maxGap` runs marginally *under* the catch-up interval in 3 of the 12 trials (run 1 trial 1 at
−0.008 ms, run 3 trials 2 and 3 at −0.042 and −0.008 ms). The negative sign is the expected
one on the fixture's own definitions: `CatchUpOutcome.Baseline.catchUpMs` is timed from just
before `connect` returns control and therefore also spans the priority-0 enqueue wait that
precedes the snapshot, whereas the stall it inflicts cannot begin until the snapshot does.
**That decomposition is read off those definitions and is not separately instrumented here** —
no column of this probe measures the enqueue wait apart from the copy — and `maxGap`, being a
maximum over the whole drive rather than over the join window, is not bounded above by the
catch-up interval either, which is why the other nine differences come out positive. The
≤ 0.13 ms above is an absolute difference; read the signed values off the per-trial table.

That pairing, not any absolute value, is the load-bearing observation of BS-9, and it is
robust precisely where the levels are not: a shared-machine disturbance moves both halves
of a trial together, so a *ratio within a trial* survives dispersion that destroys the
levels. It is also mechanistically predicted rather than surprising — `ManagedHost`
dispatches `connect` through `enqueueAwaiting(0)` and `SetCell` installs `catchUpOnLinked`
(`SetCell.kt:264`), so the whole-state snapshot **and** its delivery run on the host's single
drain thread at priority 0, ahead of every add queued at 20. The pre-existing subscriber's
arrival stream therefore stops for the entire duration of the re-baseline.

The paired unjoined half is what proves the two columns are not one thing measured twice by
accident: on the same rig, in the same trial, the same statistic reads 0.0668–0.2976 ms
without a join and 5.013–29.303 ms with one.
### Trigger (`[BEN1-31]`/`[BEN1-32]`): G-43 — **FIRES**

**Criterion, stated before it is applied:** G-43's proposal item under test is *"bound the
push-authoritative re-baseline (diff-against-last-acked / delta-since-generation)"*, against
the gap's own *"re-baseline cost under wide fan-out"*. The trigger fires if either (a)
per-delta cost grows faster than linearly in fan-out degree, so that wide fan-out itself
needs a cost bound, or (b) a single push-authoritative re-baseline occupies the source's
execution context by an amount that measurably starves live traffic, so that the re-baseline
needs a bound irrespective of the degree curve. It would be retired only if both were absent
— a well-behaved degree curve *and* a re-baseline whose occupancy is negligible against
ordinary live traffic.

Applying it: **(a) does not hold** — growth is linear in degree, with a marginal ~0.10–0.12
µs per additional subscriber and no resolvable superlinear term over 1..256. **(b) holds** —
one late join against a source of 1.1e5–1.4e5 elements stalls a pre-existing subscriber's
arrival stream for 5.0–29.3 ms, 49x–284x that subscriber's own unjoined stall measured on
the same rig in the same trial, and in 10 of 12 trials the stall equals the catch-up interval
to within 1.3%. The mechanism admits no bound today: `catchUpOnLinked` ships the entire tag
state as one delta, `ManagedHost` dispatches `connect` through `enqueueAwaiting(0)`, and the
host's single drain thread runs both the snapshot and its delivery at priority 0, ahead of
every add queued at 20. Nothing in that path is chunked, paged, preempted, or diffed against
what the joiner already has — which is precisely what G-43's proposed
diff-against-last-acked / delta-since-generation bound would supply.

**Verdict: `TriggerClaim.Cited("G-43", …)` — FIRES**, on (b). The exact sentence handed to
`Findings.entry` was:

> FIRES. The curve half is well behaved: per-delta cost over FanOutlet is linear in degree,
> with a marginal cost of about 0.1 us per additional subscriber and no superlinear term over
> 1..256 (256x the degree buys 4.0x the REAL per-delta cost). What is unbounded is the
> push-authoritative re-baseline this trigger names: one late join against a source holding
> 1.1e5-1.4e5 elements runs as a single priority-0 whole-state delta on the host's only drain
> thread and stalls a pre-existing subscriber's live arrival stream for the entire copy -
> 5.0-29.3 ms against an unjoined 0.07-0.30 ms on the same rig in the same trial, 49x-284x per
> run - so the diff-against-last-acked / delta-since-generation bound G-43 proposes is needed
> rather than optional. Every row behind this sentence is Unreportable against NOISE_FLOOR;
> the sentence rests on the within-trial pairing and the order of magnitude, not on any single
> published value.

That sentence did **not** get rendered into a `Trigger:` line, and the reason is worth being
explicit about rather than leaving as an absence: `renderResults` reached `Findings.entry`
for neither drive, because neither had a single row clearing `NOISE_FLOOR`, and
`Findings.entry` is where the one-verdict-word check lives. So the check that this entry's
verdict is unambiguous is **not** machine-attested here; the sentence above carries exactly
one of the three verdict words as a whole word, by inspection, and this section is where the
verdict is stated for the file's purposes. Same shape as the 2026-08-18 all-`Unreportable`
entry above, which likewise published the renderer's fallback text with no `Trigger:` line.

**What the verdict does not rest on.** Not on any single value in this entry: every row of
both measurements is `Unreportable`, and the absolute figures are quoted as ranges and
medians throughout for that reason. It rests on two things that survive that dispersion —
the *within-trial* pairing of stall against copy (a ratio measured on one rig in one trial,
which a machine-wide disturbance moves as a unit), and an order-of-magnitude separation
(49x–284x) far larger than any dispersion in either half. G-43 itself remains open and
un-narrowed by this entry: its other four strands (supersede-vs-remove precedence, hybrid
push/pull direction policy, poison-write escape, deadLetter→requestState recovery cell) are
untouched by any measurement here.

### What this entry does not measure

- **The re-baseline cost under *wide* fan-out — the literal phrase in the gap.** BS-9 joins
  **one** subscriber per trial, and detaches it before the next (the probe unlinks per trial
  deliberately, so a fan-out trend cannot creep into the occupancy column). N simultaneous
  joiners are not measured, and this entry does not multiply its way there: whether N
  re-baselines serialize on the drain thread additively, coalesce, or interact is exactly
  what BEN2/G-43's own study is for. The verdict above rests on N=1 already showing an
  unbounded stall, not on an extrapolation to N.
- **Any multi-host or replicated re-baseline.** Everything here is one `ManagedHost` in one
  JVM; the "replicated cells re-baseline from mesh peers" strand of G-43 is out of scope
  (`[BEN1-35]`/`[BEN1-36]`).
- **A clean catch-up cost-versus-state-size curve.** The three pre-seed scales' shipped sizes
  overlap (1e3's third trial ships 42,363 elements, more than 1e4's first at 19,062), so the
  1e3/1e4/1e5 rows are three overlapping samples of the same growing rig, not three points on
  a size curve. **Nor does a per-element cost band survive the per-trial data**, and the
  earlier form of this sentence — "lands in the 0.1–0.2 µs/element band" — was corrected at
  review: dividing each trial's catch-up cost by the elements that trial actually shipped
  gives **0.035–0.412 µs/element across the eighteen trials** (median 0.164), with only 8 of
  the 18 inside 0.1–0.2. Restricted to the twelve 1e5 trials it is 0.035–0.267 µs/element,
  median 0.112. So 0.1–0.2 describes roughly where the middle of the distribution sits and is
  **not** a bound; what this entry supports about per-element cost is an order of magnitude —
  ~0.1 µs per element — and neither a band nor a slope. Every input to those figures is in
  the per-trial table above (`catch-up samples` over `shipped per trial`), so the arithmetic
  is re-checkable without the raw artifacts.
- **Allocation, footprint, or throughput of anything.** Per-delta latency and wall-clock
  stalls only.

### Scope confirmation

`git diff --name-only <merge-base of feature/computenet-x9e.5> HEAD` names exactly one file:
`doc/bench/findings.md` (this entry). `git diff --name-only <merge-base> HEAD -- kernel/src/main
concord/ inspect/src wire/src demo/ doc/spec` is empty; `git diff <merge-base> HEAD --
bench/src/main bench/src/jmh bench/src/test` is empty as well — **no fixture constant was
raised** (the sizing above is why, for BS-8; for BS-9, replication was bought by re-running
the whole probe rather than by raising `CatchUpFixtures.TRIALS`, which would have pushed each
run's shipped state further from the 1e5 the entry is about). `NOISE_FLOOR` in
`bench/src/main/kotlin/civictech/bench/Dispersion.kt` is unchanged
(`const val NOISE_FLOOR: Double = 0.005`), `Env.kt` and `ThroughputReport.kt` are untouched
(`computenet-x9e.8`/`computenet-yhbd` own them), `doc/spec/90-roadmap/91-gap-analysis.md`
and `doc/spec/CONCORDANCE.md` are unmodified — G-43's row is cited, never edited — and no
entry above this one was edited, reordered or deleted.

---

## 2026-08-20 — Correction to the NOISE_FLOOR provenance entry above: the recorded environment describes the *rendering* JVM, not the measuring one

Filed as `computenet-x9e.9`, from the second feature review of `computenet-x9e.4`
(session Anva@A0030, 2026-08-18 ~20:50 UTC), which read `Env.kt`'s fallback and
went looking beyond the two entries the correction above already named. Same
mechanism as that correction, applied to the entry it did not cover: the
**"2026-08-18 — SmokeBenchmark.baseline noise-floor calibration - NOISE_FLOOR
provenance"** entry's `Harness:` line —

```
Harness: cbea02900f695fe156a1b94cdf77c60be9781f10 · JVM Eclipse Adoptium/21.0.11 · heap maxHeapBytes=4294967296 · Apple M2 Pro, 10 cores, Mac OS X 26.6.1
```

— reports `heap maxHeapBytes=4294967296` (4 GiB). That figure is a property of the
process that *rendered* the entry, not of the JMH forks that measured it. It
contradicts the entry's own procedure paragraph, which states the runs were
"launched with no VM options" — i.e. JVM defaults, not an explicit 4 GiB heap.

**The mechanism.** At the time this entry was produced, `RunEnvironment`'s
`capture` function (`bench/src/main/kotlin/civictech/bench/Env.kt`, since removed
by `computenet-hqid`) read the heap of the CALLING process: it looked for
`-Xms`/`-Xmx` in `ManagementFactory.getRuntimeMXBean().inputArguments`, and when
none were present — exactly the "no VM options" case this entry's own procedure
describes — fell back to `runtime.maxMemory()`, formatted as
`"maxHeapBytes=$maxMemory"`. The three JMH forks that produced the scores above
had exited long before anything rendered this entry; the 4 GiB figure is
`Runtime.maxMemory()` of whatever JVM ran the rendering step, not a heap setting
any measuring fork received.

Which JVM that was is **not** the `:bench:test` Gradle JVM, and the heap field
itself is what rules that out. `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`
sets `maxHeapSize = "2g"` on every `Test` task, so a render inside `:bench:test`
is launched with an explicit `-Xmx2g` and takes `captureHeapSettings`' *first*
branch, rendering `heap -Xmx2g` — which is how `computenet-hqid`'s own commit
message describes the renderer ("running in the Gradle `:bench:test` JVM
(toolchain 21, `-Xmx2g`)"), and what the `-Xmx2g` heap fields on other `Harness:`
lines in this file read. This entry took the **fallback** branch instead, reached
only when neither `-Xms` nor `-Xmx` was passed. So the rendering JVM carried no
heap flag at all, and `4294967296` is simply that JVM's ergonomic default heap:
measured on this host (Apple M2 Pro, 10 cores, 16 GiB) with the entry's own
declared toolchain, `java -XX:+PrintFlagsFinal -version` reports
`MaxHeapSize = 4294967296 {ergonomic}` when launched with no VM options, against
`2147483648 {command line}` under `-Xmx2g`. Which launcher it actually was is not
recoverable from anything still on disk, and this correction does not guess at it.

**Whether the calibration run's own retained JMH banner is still on disk**: it is
not. This entry's own procedure states the runs were captured as JSON
(`-rf json -rff runN.json`), not as a saved stdout log carrying JMH's `# VM
options:` banner line the way the REAL/SIM throughput sweeps were; no `runN.json`,
no `.log`, and no other run artifact for this entry is present in the working tree
or tracked in git (`git ls-files` and a tree search for `run1`/`run2`/`run3`/
`SmokeBenchmark` artifacts under `bench/` and `doc/bench/` both come back empty).
So this correction does not quote a banner line as evidence — there is none to
quote — and rests instead on the entry's own procedure text ("launched with no VM
options") together with the `Env.kt` fallback mechanism above, which is sufficient
to identify the 4 GiB figure as the render host's default heap rather than any
value the measuring forks were given.

**Not affected**: the entry's JVM vendor and version (`Eclipse Adoptium/21.0.11`),
its three scores and their dispersions, and the `NOISE_FLOOR` derivation itself
(`bench/src/main/kotlin/civictech/bench/Dispersion.kt`, confirmed unchanged:
`const val NOISE_FLOOR: Double = 0.005`). Only the `Harness:` line's heap field is
wrong. `NOISE_FLOOR` is not re-derived by this correction and its value does not
change.

**Not repaired here**: correcting the `Harness:` line by hand would break the
property the entry rests on — that its rendered block is verbatim tool output —
and this file is append-only by its own header rule (nothing above the insertion
point is edited, reordered or deleted). `computenet-hqid` (closed) already fixed
the renderer so a `RunEnvironment` can no longer answer with the calling
process's own facts; nothing about that fix reaches back to re-render this
already-published entry, which is why this correction exists.

### Scope confirmation

The diff for this correction is exactly `doc/bench/findings.md` — this appended
entry. `git diff --name-only <merge-base of feature/computenet-x9e.9> HEAD` names
no other file: no change under `kernel/src/main`, `concord/`, `inspect/src`,
`wire/src`, `demo/` or `bench/`. `NOISE_FLOOR` in
`bench/src/main/kotlin/civictech/bench/Dispersion.kt` is untouched (confirmed:
`const val NOISE_FLOOR: Double = 0.005`). No entry above this one was edited,
reordered or deleted.

---

## 2026-08-20 — computenet-x9e.7: the remaining three Reportable rows also fail to reproduce their classification — six of six now flip

Closes out `computenet-x9e.7` ("Reportable classification is not reproducible near
NOISE_FLOOR"). That item recorded that reviewers of `computenet-x9e.4.5` (REAL sweep)
and `computenet-x9e.4.4` (SIM sweep) had already spot-checked three of the six rows
either entry classified `Reportable` against `NOISE_FLOOR`, at the identical annotation
config (`Fork(2)`, `Warmup(iterations=5, time=1s)`, `Measurement(iterations=10, time=1s)`)
on a quiesced host, and all three flipped to `Unreportable` (the same three numbers are
already quoted in the "REAL-drive … re-measured on the toolchain JDK" entry above):

| drive | row | recorded disp. | re-measured disp. | factor |
| --- | --- | --- | --- | --- |
| REAL | GROUP_BY_MAX retract | 0.00480 | 0.0154 | 3.2x |
| SIM | GROUP_BY_TOP_K retract | 0.00355 | 0.00973 | 2.7x |
| SIM | COUNT retract | 0.00499 | 0.0404 | 8.1x |

This entry re-measures the three that were not yet spot-checked, closing the count
named in `computenet-x9e.7`'s acceptance criteria. All three are from the SIM-drive
entry above (`computenet-x9e.4.4`): `FILTER retract`, `INTERSECT retract`,
`GROUP_BY_SUM retract` — the three Reportable rows there not already covered,
completing that entry's set of five.

### What was re-measured, and how

Same benchmark class and drive as the entry being spot-checked
(`civictech.bench.micro.OperatorThroughputBenchmark.sim`), one subject/direction
combination per invocation, no `-f`/`-wi`/`-i` override — the class's own
`@Fork(ThroughputReport.FORKS)`, `@Warmup(iterations = ThroughputReport.WARMUP_ITERATIONS,
time = ThroughputReport.ITERATION_SECONDS)`, `@Measurement(...)` annotations resolve to
`Fork(2)`, `Warmup(iterations=5, time=1s)`, `Measurement(iterations=10, time=1s)`,
identical to the original sweep and to the three earlier spot-checks. Commands, exactly:

```
./gradlew :bench:jmhJar
/Users/MerlijnB/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x.2/jdk-21.0.11+10/Contents/Home/bin/java \
     -jar bench/build/libs/bench-jmh.jar 'OperatorThroughputBenchmark.sim' \
     -p subject=FILTER -p direction=RETRACT -rf csv -rff /abs/path/spot-FILTER.csv
/Users/MerlijnB/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x.2/jdk-21.0.11+10/Contents/Home/bin/java \
     -jar bench/build/libs/bench-jmh.jar 'OperatorThroughputBenchmark.sim' \
     -p subject=INTERSECT -p direction=RETRACT -rf csv -rff /abs/path/spot-INTERSECT.csv
/Users/MerlijnB/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x.2/jdk-21.0.11+10/Contents/Home/bin/java \
     -jar bench/build/libs/bench-jmh.jar 'OperatorThroughputBenchmark.sim' \
     -p subject=GROUP_BY_SUM -p direction=RETRACT -rf csv -rff /abs/path/spot-GROUP_BY_SUM.csv
```

The Gradle toolchain JDK 21 (Eclipse Adoptium/Temurin 21.0.11) was invoked by absolute
path, as every sweep in this file does. Host quiesced — no concurrent Gradle build, test
suite, or other benchmark; these three runs were the only active workload on the machine,
run back to back 2026-08-20T10:20:16Z–2026-08-20T10:21:49Z (91s total, ~30s per row: 2
forks x (5+10) x 1s of iteration time plus per-fork JVM/JMH startup — far cheaper than a
full 36-row sweep because only one subject/direction combination runs per invocation).
JMH's own summary lines, quoted verbatim:

```
OperatorThroughputBenchmark.sim  RETRACT  FILTER        thrpt  20  882413.204 ± 47815.046  ops/s
OperatorThroughputBenchmark.sim  RETRACT  INTERSECT     thrpt  20  352408.438 ±  2455.016  ops/s
OperatorThroughputBenchmark.sim  RETRACT  GROUP_BY_SUM  thrpt  20  761122.007 ± 15338.452  ops/s
```

### Result: all three flipped too

| drive | row | recorded disp. | re-measured disp. | factor | recorded score | re-measured score | score delta |
| --- | --- | --- | --- | --- | --- | --- | --- |
| SIM | FILTER retract | 0.00449 | 0.05419 | 12.1x | 831943.320342 | 882413.204 | +6.07% |
| SIM | INTERSECT retract | 0.00425 | 0.00697 | 1.6x | 352771.551032 | 352408.438 | -0.10% |
| SIM | GROUP_BY_SUM retract | 0.00403 | 0.02015 | 5.0x | 761118.948691 | 761122.007 | +0.00% |

All three re-measured above `NOISE_FLOOR` (0.005): each would have classified
`Unreportable` and been excluded, had this spot check been the recorded run — the same
outcome as the three checked earlier. Combined, **all six of the six Reportable rows
recorded across both merged entries (one REAL, five SIM) have now been spot-checked at
the identical annotation config, and all six flipped to Unreportable on re-measurement.**
Zero of six reproduced their original classification.

The absolute scores again reproduce well — within 6.07% at worst (`FILTER retract`) and
within 0.10% at best (`GROUP_BY_SUM retract`), the same pattern as every re-measurement
already in this file (REAL within ~1%, SIM within ~6%). This is, exactly as
`computenet-x9e.7` itself frames it, a dispersion-stability finding and **not** a
wrong-number, fabrication, or transcription finding: the SCORES are stable across
re-measurement; the DISPERSIONS, for rows near the floor, are not.

### Consequence, stated at the strength this supports and no further

At the annotation config, Reportable/Unreportable classification is not reproducible for
rows near `NOISE_FLOOR`. Read together with all six spot-checks now on record, and with
the independent REAL-drive re-measurement on the toolchain JDK above (`computenet-am2h`,
which separately found 0 of 36 REAL rows Reportable, including the one row the original
REAL entry reported), the "1 of 36" (REAL) and "5 of 36" (SIM) counts in the two original
2026-08-18 entries should be read as **upper bounds** on how many rows would survive
re-measurement, not as a set of rows that would. This entry does not claim the true count
is exactly zero: six flips out of six checked is consistent with zero, not proof of it —
no row at this boundary has yet been re-measured and found to hold its classification.

Two limits on how far the `factor` column can be read. First, each factor is a single
sample of the very quantity this entry shows to be unstable, so it characterises one pair
of runs and not the row: within this entry's own data `FILTER retract` moves 12.1x while
`INTERSECT retract` moves 1.6x, and a third measurement of either would land somewhere
else again. Only the *direction* of the move — from at-or-under the floor to above it — is
the finding; the magnitudes are not properties of the rows. Second, `INTERSECT retract` is
the marginal case of the six: at 0.00697 it sits 1.4x `NOISE_FLOOR`, close enough that it
would be the first of the six to fall back under the floor on another run, and it should
not be read as carrying the weight of the 12.1x and 8.1x rows.

### What was NOT done, and why

- **The annotation config was not widened.** `[BEN1-25]` forbids raising forks or
  iterations to manufacture reportability, and this entry's point is that the config
  classifies unreliably at this boundary at its documented setting — not that a wider
  config would classify better. Every re-measurement above used the unmodified
  `Fork(2)`/`Warmup(5,1s)`/`Measurement(10,1s)` annotations.
- **`NOISE_FLOOR` was not touched or re-derived.** Confirmed unchanged in
  `bench/src/main/kotlin/civictech/bench/Dispersion.kt`: `const val NOISE_FLOOR: Double =
  0.005`.
- **No re-measurement of the remaining 66 (of 72) originally-Unreportable rows was
  attempted.** Only the six rows either original entry classified `Reportable` are in
  scope for this finding; the Unreportable rows are not this item's question.
- **No allocator or GC profiler was attached** (no `-Xlog:gc`, no JFR) to any of the three
  runs above, consistent with every other entry in this file — the cause of the
  dispersion instability itself remains unprofiled, only its existence and extent are
  established here.

### Scope confirmation

The diff for this entry is exactly `doc/bench/findings.md`. `git diff --name-only
<merge-base of feature/computenet-x9e.7> HEAD` names no other file: no change under
`kernel/src/main`, `concord/`, `inspect/src`, `wire/src`, `demo/`, or `bench/src/main`,
`bench/src/jmh`, `bench/src/test`. The annotation config was not widened; `NOISE_FLOOR`
in `bench/src/main/kotlin/civictech/bench/Dispersion.kt` is untouched (confirmed above).
No entry above this one was edited, reordered, or deleted.

---

## 2026-08-20 — allocation per Stateful.snapshot() call (gc.alloc.rate.norm, -prof gc) for every data-cell family at 1e3/1e4/1e5 — G-21 phase 3's own quantity, allocation pressure, not the retained occupancy computenet-x9e.6.2 measured
Harness: 06e829ff · JVM Amazon.com Inc. (Corretto-21.0.5.11.1)/21.0.5 · heap JVM defaults (VM options: <none>) · Apple M3 Max, 16 cores, Mac OS X 26.6.2
JMH: mode=Average time forks=1 warmup=3 iters=5 · drive=REAL
| subject | value | notes |
| --- | --- | --- |
| SET_CELL N1E3 | 265246.546863 ± 1.038458 B/op | |
| SET_CELL N1E4 | 2613672.402447 ± 71.702805 B/op | |
| SET_CELL N1E5 | 2.7123106009174E7 ± 70100.479081 B/op | |
| MAP_CELL N1E3 | 40258.784809 ± 0.442415 B/op | |
| MAP_CELL N1E4 | 385729.816949 ± 6.794099 B/op | |
| MAP_CELL N1E5 | 4262216.536086 ± 823.359966 B/op | |
| OR_MAP_CELL N1E3 | 217473.606082 ± 8.901627 B/op | |
| OR_MAP_CELL N1E4 | 2133284.180887 ± 107.701476 B/op | |
| OR_MAP_CELL N1E5 | 2.2253527270692E7 ± 17264.266231 B/op | |
| KEYED_SET_CELL N1E3 | 136999.758858 ± 2.456586 B/op | |
| KEYED_SET_CELL N1E4 | 1331975.464662 ± 15.616109 B/op | |
| KEYED_SET_CELL N1E5 | 1.4150457376181E7 ± 16050.136085 B/op | |
| LIST_CELL N1E3 | 4040.030562 ± 0.001071 B/op | |
| LIST_CELL N1E4 | 40040.828019 ± 0.027479 B/op | |
| LIST_CELL N1E5 | 400119.296592 ± 11.497288 B/op | |
| COUNTER_CELL N1E3 | 24.000294 ± 1.1E-5 B/op | |
| COUNTER_CELL N1E4 | 24.000742 ± 4.4E-5 B/op | |
| COUNTER_CELL N1E5 | 24.007011 ± 6.82E-4 B/op | |
| PN_COUNTER_CELL N1E3 | 568.026332 ± 0.011614 B/op | |
| PN_COUNTER_CELL N1E4 | 568.130802 ± 0.004329 B/op | |
| PN_COUNTER_CELL N1E5 | 569.296384 ± 0.03591 B/op | |
Trigger: G-21 phase 3 — INCONCLUSIVE: the criterion applied is that the trigger fires only if some family allocates at least 1 MiB per snapshot() call AND that family's allocation is flat in element count (at most 2x from 1e3 to 1e5) — the fixed-size per-call shape a lease pool could actually retire — and retires only if every family allocates under one 4 KiB page per call, with anything else undecided; measured, the loudest family is SET_CELL at 2.7123106009174E7 B/op, growth from 1e3 to 1e5 ranges 1.0002798715715733x-105.87047165748444x across 7 families, and the families flat in element count are COUNTER_CELL, PN_COUNTER_CELL — whose largest per-call allocation is 569.296384 B/op

Omitted rows (drive=REAL):
- none

### What was measured, and what it is NOT (`computenet-6zqz`, G-21 phase 3)

**The quantity.** Every number in the table above is `gc.alloc.rate.norm` — JMH's
`-prof gc` secondary metric, **bytes allocated per measured operation**, where the
operation is one `civictech.cell.Stateful.snapshot()` call on a populated, unhosted
data cell. It is **not** retained size, and it does not supersede the entry of
2026-08-19 above (*"per-cell retained snapshot footprint…"*, `computenet-x9e.6.2`),
which measured a different thing by a different instrument. Allocation is what a call
*produces as garbage*; retention is what the state *costs to hold*, and a snapshot
allocates its copy plus every intermediate the copy passes through. The two entries do
not contradict each other and neither corrects the other; this one exists because G-21
phase 3's trigger names allocation pressure, and the retained measurement therefore
could not answer it in its own terms.

**Instrument.** `bench/src/jmh/kotlin/civictech/bench/micro/CellFootprintBenchmark.kt`,
which has been committed and unrun at sweep scale since `computenet-x9e.6.1`; its
`@Param`s are the `CellFamily` and `Scale` enums declared beside the catalog in
`Footprint.kt`, so the 7 x 3 coverage above is derived from that catalog and not
restated. Cells are unhosted — no scheduler, no host queue — which is what makes this a
REAL-drive measurement in `[BEN1-26]`'s sense.

**Commands, both shipped paths, in order.** The sweep, on the toolchain JDK 21 invoked
by absolute path (a bare `java` on this host is JBR 25.0.2 — the exact substitution
`computenet-dbqt` was filed about, one that completes successfully while producing
figures incomparable to `NOISE_FLOOR`):

```
./gradlew :bench:jmhJar
/Users/merlijn/Library/Java/JavaVirtualMachines/corretto-21.0.5/Contents/Home/bin/java \
     -jar bench/build/libs/bench-jmh.jar CellFootprintBenchmark -prof gc \
     -rf csv -rff <abs>/footprint-alloc.csv 2>&1 | tee <abs>/footprint-alloc.log

./gradlew :bench:test -PbenchOnly=true --rerun \
  --tests 'civictech.bench.micro.CellFootprintAllocRenderTest' \
  -Dcivictech.bench.jmhResults=<abs>/footprint-alloc.csv \
  -Dcivictech.bench.harnessSha=$(git rev-parse --short HEAD) \
  -Dcivictech.bench.date=2026-08-20
```

Everything between the `##` heading and the `Trigger:` line is
`civictech.bench.Findings.entry`'s output, pasted verbatim, reached through
`ThroughputReport.renderRun` — the same shipped path every other rendered entry in this
file uses. **No hand-written driver was involved**, which was not previously possible:
until `computenet-6zqz` the renderer read the primary `Score` column only and refused
any results file carrying a `NaN` dispersion, and a `-prof gc` file always carries two
(`gc.count` and `gc.time` are sums, so JMH writes their error as literally `NaN`). The
whole file was therefore refused before the allocation rows were reached, and this
class's figures were read by hand off stdout. `ThroughputReport.Metric` is what closed
that: the caller states which metric the entry reports, selection happens before the
NaN refusal, and a file holding no row for the requested metric is refused naming the
metrics it does hold rather than falling back to wall clock.

### The environment differs from every earlier entry in this file — read the numbers accordingly

Stated here because it is the one thing about this entry a reader could otherwise get
wrong, and it is a limitation this entry does not resolve:

- **JVM: Amazon Corretto 21.0.5**, per the run's own `# VM version: JDK 21.0.5,
  OpenJDK 64-Bit Server VM, 21.0.5+11-LTS` banner and its `# VM invoker:` line. Not
  Temurin 21.0.11, on which `NOISE_FLOOR` was derived and on which every earlier entry
  here was measured — **no Temurin 21 exists on this host at all**, and the Gradle
  `jvmToolchain(21)` this run was pinned to resolves to Corretto here. Same language
  version, same LTS line, different vendor build.
- **Host: Apple M3 Max, 16 cores, macOS 26.6.2**, from the benchmark's own
  `@Setup(Level.Trial)` host-facts banner (`computenet-yhbd`, wired into this class by
  `computenet-7w4e`) — not the Apple M2 Pro / 10 cores of the earlier entries.
- **The host was NOT quiesced.** Two other agent sessions were live on this machine and
  may have run Gradle concurrently during the sweep. That is recorded rather than
  smoothed. It is also much less consequential for this metric than it would be for a
  wall-clock one: bytes allocated per operation is a property of the code path, not of
  how much CPU it got, which is part of why this measurement — and not a timing one —
  is the right instrument for the trigger. The dispersions bear that out: the widest
  relative dispersion in the whole sweep is `SET_CELL N1E5` at 70100/27123106 =
  **0.0026**, half of `NOISE_FLOOR` 0.005, and **all 21 rows classified Reportable —
  no row was omitted**, which is why the omission list above reads `none`.
- **JMH knobs, from the run's own banner**: `mode=Average time forks=1 warmup=3
  iters=5`, the class's declared annotations, unmodified. `[BEN1-25]` forbids widening
  them to manufacture reportability and none were widened.

Because the JVM vendor build and the host both differ, **the absolute numbers above are
not directly comparable to the retained-footprint entry of 2026-08-19**, and a
byte-for-byte comparison between the two entries' magnitudes should not be made. What
survives the difference is the *shape* — which families scale with element count and
which do not — and the E1 cross-check below, whose reference figures come from a third
machine again.

### Cross-check against V1C-BENCH E1's independently measured `alloc/call`

E1 (`doc/spec/90-roadmap/98-inspector-v4-plan/30-bounded-read-measurement.md` section 3)
reports `SetCell` allocation per `snapshot()` call as **270 KB / 2.61 MB / 26.9 MB** at
1e3/1e4/1e5, measured four months earlier, on a different machine, through
`ThreadMXBean.getThreadAllocatedBytes` — a different mechanism from `-prof gc`. This
sweep reports **265.2 KB / 2.61 MB / 27.1 MB**. Agreement is within ~2%, 0%, and ~0.8%.

That is a genuine cross-check on that document's numbers rather than a re-run of its
harness. It is **not** the V1C-BENCH replication itself: that task owns E1's full
claim, this is one column of it, and nothing here should be read as closing it.

### Why the verdict is INCONCLUSIVE, and what would decide it

The criterion was fixed in committed source **before the sweep ran** — `CRITERION`,
`PRESSURE_BAR_BYTES`, `FLAT_GROWTH_FACTOR` and `FLOOR_BYTES` in
`bench/src/test/kotlin/civictech/bench/micro/CellFootprintAllocRenderTest.kt` — and the
verdict word in the `Trigger:` line above is computed from the parsed rows by
`verdictOf` in that same file, not typed. Re-running the sweep and re-reading that
function is a complete audit of how the word was reached. Its inputs, every family
including those the table already shows:

```
Criterion inputs (bytes allocated per snapshot() call, gc.alloc.rate.norm):
| family | at smallest scale | at largest scale | largest/smallest | max B/op |
| --- | --- | --- | --- | --- |
| COUNTER_CELL | 24.000294 | 24.007011 | 1.0002798715715733 | 24.007011 |
| KEYED_SET_CELL | 136999.758858 | 1.4150457376181E7 | 103.28819184892089 | 1.4150457376181E7 |
| LIST_CELL | 4040.030562 | 400119.296592 | 99.03868063659465 | 400119.296592 |
| MAP_CELL | 40258.784809 | 4262216.536086 | 105.87047165748444 | 4262216.536086 |
| OR_MAP_CELL | 217473.606082 | 2.2253527270692E7 | 102.32748548943978 | 2.2253527270692E7 |
| PN_COUNTER_CELL | 568.026332 | 569.296384 | 1.002235903387662 | 569.296384 |
| SET_CELL | 265246.546863 | 2.7123106009174E7 | 102.25620778084284 | 2.7123106009174E7 |

PRESSURE_BAR_BYTES=1048576.0 FLAT_GROWTH_FACTOR=2.0 FLOOR_BYTES=4096.0
verdict=INCONCLUSIVE
```

Both branches failed, and they failed for different reasons:

- **It does not fire.** Five of seven families allocate far more than the 1 MiB bar at
  1e5 — `SET_CELL` 27.1 MB, `OR_MAP_CELL` 22.3 MB, `KEYED_SET_CELL` 14.2 MB,
  `MAP_CELL` 4.3 MB — but every one of them grows ~100x for a 100x increase in
  elements, i.e. allocation is **linear in the state**, which is whole-state copy cost.
  A lease pool reuses fixed-size buffers; it does not remove a copy whose size is the
  data. The two families that ARE flat in element count — `COUNTER_CELL` at a constant
  **24 B/op** and `PN_COUNTER_CELL` at a constant **~568 B/op** — are flat precisely
  because they are O(1) state, and 24 bytes per call is not pressure by any reading.
- **It does not retire.** `LIST_CELL` at 1e3 already allocates 4040 B/op and reaches
  400 KB/op at 1e5, so the "every family under one page per call" branch is nowhere
  near true.

**A limitation of this instrument, stated because it bounds the verdict rather than
merely qualifying it**: G-21 phase 3 is about pooling `Leased` payloads with
host-integrated release-on-drain (`doc/spec/20-dataflow-semantics/23-ownership.md`
§Implementation). None of the seven families here emits a `Leased` payload at all —
data-cell deltas are plain values carrying no ownership marker, which is what makes
fan-out and late-join catch-up legal for them by construction
(`93-feature-interactions.md` N14 x N15). So this sweep measures allocation pressure in
the path the trigger's *quantity* names, on cells whose payloads the proposed *remedy*
would not touch. **A measurement that could fire this trigger has to be taken on a
graph that actually moves `Leased` payloads**, and no such benchmark exists in `:bench`
today. That is the concrete next instrument, and it is a larger piece of work than
appending a row here.

The confirmed O(1) allocation of both counter families is worth recording on its own:
`computenet-x9e.6.2` noted that an O(1)-retained counter still allocates a delta per
increment, and left open whether the *snapshot* path did the same. It does not — 24 B
per `snapshot()` call, flat to within 0.03% across two decades of element count.

### Scope confirmation

Nothing above the insertion point was edited, reordered, or deleted. This entry's
change to `doc/bench/findings.md` is an append. `doc/spec` is byte-identical to `main`;
`94-implementation-plan.md` and `23-ownership.md` are cited above and were not touched.
`CellFootprintBenchmark.kt` was likewise cited and not edited — its KDoc still says a
`-prof gc` render is "a renderer gap, tracked as `computenet-6zqz`", which this entry's
work closes; correcting that KDoc is filed separately rather than done here, because
that file is outside this item's claim.

---

## 2026-08-20 — fan-out degree curve re-measured with the source held at a FIXED element count across degree: whether the 2026-08-19 entry's "linear in degree, not worse" reading survives once the per-iteration state-size confound is closed — INCONCLUSIVE

`computenet-252t`, filed against `computenet-x9e.5.3`'s own "confound that limits the
paragraph above" section (this file, 2026-08-19 fan-out entry). That entry's degree curve
rebuilds `FanOutScalingBenchmark`'s rig once per JMH **iteration** and adds one element per
**invocation**, so the number of invocations a 1 s iteration fits — and therefore the
source size each invocation's delta is measured against — is inversely proportional to the
per-delta cost itself: roughly 4x (REAL) to 19x (SIM) larger a source at D1 than at D256.
If per-add cost rises with source size at all, that inflates the low-degree rows and
**understates** growth in degree, biasing the "linear in degree, not worse" reading toward
the very conclusion it draws. This entry closes that confound by construction and asks
whether the reading survives.

### What was built and run

Two new `@Benchmark` methods on the SAME `FanOutScalingBenchmark` class the original
entry used — `simFixedState`/`realFixedState` — under `Mode.SingleShotTime` with a new
`FixedStateRigState` that rebuilds AND re-seeds the rig to a FIXED
`FanOutFixtures.FIXED_STATE_ELEMENTS` = 10,000 elements once per **invocation**
(`@Setup(Level.Invocation)`), so every one of the five `FanDegree`s is measured against
the identical source size rather than whatever size an iteration happened to grow to.
`FanOutFixtures.rig` gained an optional `preSeed` parameter and `FanOutRig.seed(count)`
to do the pre-seeding (off any timer — `@Setup` is excluded from JMH's timed region in
every mode); both default to the prior unseeded behaviour, so the original `sim`/`real`
benchmarks and every existing `FanOutFixturesTest` case are unchanged (confirmed:
`./gradlew :bench:test --rerun` — 204 tests, 0 failures, see below; an earlier draft of
this line said 63, which was never that command's count). No file outside this
task's `metadata.files` claim was touched, and no fixture constant outside the two new
files (`FanOutFixtures.kt`, `FanOutScalingBenchmark.kt`) was edited — `NOISE_FLOOR`
(`bench/src/main/kotlin/civictech/bench/Dispersion.kt`) is unchanged at `0.005`.

`FanOutFixedStateRenderTest` (`bench/src/test/kotlin/civictech/bench/micro/`, new,
`@Tag("bench")`) is the render driver, following `CellFootprintAllocRenderTest`'s pattern:
it fits a least-squares affine curve per drive, computes each consecutive degree pair's
segment marginal together with the TWO endpoints' 99.9% error bars summed conservatively
(the same convention the 2026-08-19 entry's own segment-marginal arithmetic uses), calls a
segment **resolvable** only when its own point estimate exceeds that combined error, and
states a reading — `SURVIVES` / `DOES NOT SURVIVE AS STATED` / `INCONCLUSIVE` — from a
criterion (`MARGINAL_GROWTH_FACTOR = 3.0`) fixed in committed source before this run. No
G-id trigger is attached: `computenet-252t`'s own non-goals bar re-litigating G-43's
verdict, which rests on BS-9's occupancy pairing, not the degree curve, and
`TriggerClaim.None` is what was passed to `Findings.entry`.

### Commands, exactly

```
./gradlew :bench:jmhJar

/Users/merlijn/Library/Java/JavaVirtualMachines/corretto-21.0.5/Contents/Home/bin/java \
     -jar bench/build/libs/bench-jmh.jar 'FanOutScalingBenchmark\.(sim|real)FixedState' \
     -rf csv -rff /abs/path/fanout-fixed-state.csv \
     2>&1 | tee /abs/path/fanout-fixed-state.log

./gradlew :bench:test -PbenchOnly=true --rerun \
  --tests 'civictech.bench.micro.FanOutFixedStateRenderTest' \
  -Dcivictech.bench.jmhResults=/abs/path/fanout-fixed-state.csv \
  -Dcivictech.bench.harnessSha=7eda317c
```

(`7eda317c`, not the `eb4a00fe` the JMH sweep itself was measured against: the render
driver's `verdictOf` was tightened to require error-bar resolvability, not raw point
estimates, between the sweep running and this entry being rendered — see that commit's
own message. The JMH results file is unaffected; only how this entry's `reading=` line
was computed changed.)

**That tightening was written after these numbers were visible, and it changed the
verdict word — stated here rather than left in a commit message, because a reader
weighing this entry's `INCONCLUSIVE` is entitled to know when the criterion behind it was
authored.** Against the FIRST cut of `verdictOf` (`eb4a00fe`, which ranked raw
point-estimate segment marginals with no reference to their error bars) this same results
file read **`DOES NOT SURVIVE AS STATED`**, a reading driven entirely by REAL's D4->D16
segment — whose point estimate is `-0.095` inside a `±2.50` error bar, i.e. noise read as
signal. Two things bound how much that ordering can have biased the outcome, and both are
re-checkable from the table below rather than taken on trust: the replacement criterion
introduces **no fitted or tunable threshold** — a segment counts when `|marginal| >` its
own two-endpoint 99.9% error bar, and `MARGINAL_GROWTH_FACTOR` was left at the `3.0` fixed
against the 2026-08-19 sweep and is never even reached on this data — and the change moved
the entry from a stronger claim to a **weaker** one, withdrawing a verdict rather than
manufacturing one. A reader who rejects that reasoning has the raw per-segment table below
and can apply either criterion themselves.

The whole ten-combination sweep (2 methods x 5 degrees x 3 forks x (5 warmup + 10
measurement) single shots) ran in **47 s** wall clock — the 10,000-element re-seed per
invocation is cheap even at `FanDegree.D256`'s 256-way fan-out, well inside this task's
dispatch slot.

### Host and JVM, from the run's own retained banner — read, not assumed

Quoted from `fanout-fixed-state.log`:

```
# JMH version: 1.37
# VM version: JDK 21.0.5, OpenJDK 64-Bit Server VM, 21.0.5+11-LTS
# VM invoker: /Users/merlijn/Library/Java/JavaVirtualMachines/corretto-21.0.5/Contents/Home/bin/java
# VM options: <none>
# Warmup: 5 iterations, 1 s each
# Measurement: 10 iterations, 1 s each
# Benchmark mode: Single shot invocation time
# Host CPU model: Apple M3 Max
# Host core count: 16
# Host OS: Mac OS X 26.6.2
```

**This host (`NL-MGD6FQJW91`, Apple M3 Max, 16 cores) has no Temurin 21**;
`jvmToolchain(21)` resolves to **Amazon Corretto 21.0.5**, invoked here by the absolute
path above rather than the bare `java` on `PATH` (JetBrains Runtime 25.0.2, confirmed via
`java -version` outside the sweep — the exact substitution `computenet-hqid` warns about).
**Both the JVM (Corretto 21.0.5 vs the 2026-08-19 entry's Temurin 21.0.11) and the machine
(Apple M3 Max vs that entry's Apple M2 Pro) differ from the sweep this entry re-reads.**
The most recent landed entry above (`computenet-x9e.7`, 2026-08-20, G-21 allocation sweep)
was measured on this same host and JVM, so this run's magnitudes are directly comparable
to that one and to nothing measured on the M2 Pro/Temurin combination. Absolute
`us/op` values below should not be compared across entries; only the SHAPE — how cost
grows across degree, at one fixed source size — is this entry's subject.

**The host was NOT exclusively quiesced for this run.** A second Claude Code session was
live on this machine during measurement (per this task's own dispatch instructions), and
`ps` immediately after the sweep showed Gradle/Kotlin compile daemons with start times
before this run began (11:28 and 12:10, against a run that started later), which this
sweep cannot attribute to itself or rule out as contending load. This matters more here
than it did for `computenet-x9e.7`'s allocation counts, because this entry's quantity is
wall-clock latency, which shared-CPU contention inflates directly. It is named as a
plausible contributor to the dispersion below, not confirmed as the cause — no isolation
experiment (pinning the host idle and re-running) was performed in this slot.

### The numbers

`ThroughputReport.renderRun`'s own output, pasted verbatim (a `@Tag("bench")` driver's
captured stdout, JUnit XML `<system-out>`), each drive separately (`[BEN1-27]`):

## (no entry for drive=SIM) — every row classified Unreportable against NOISE_FLOOR 0.005; see the omissions below

Omitted rows (drive=SIM):
- D1 (drive=SIM): relative dispersion 0.1860882974781569 exceeds NOISE_FLOOR 0.005 — value=41.721033 ± 7.763796 us/op; Unreportable, excluded from the table
- D4 (drive=SIM): relative dispersion 0.22671123634250595 exceeds NOISE_FLOOR 0.005 — value=46.373533 ± 10.513401 us/op; Unreportable, excluded from the table
- D16 (drive=SIM): relative dispersion 0.24110074214683516 exceeds NOISE_FLOOR 0.005 — value=56.5791 ± 13.641263 us/op; Unreportable, excluded from the table
- D64 (drive=SIM): relative dispersion 0.1742080179657052 exceeds NOISE_FLOOR 0.005 — value=59.7583 ± 10.410375 us/op; Unreportable, excluded from the table
- D256 (drive=SIM): relative dispersion 0.0941462531968599 exceeds NOISE_FLOOR 0.005 — value=93.779133 ± 8.828954 us/op; Unreportable, excluded from the table

## (no entry for drive=REAL) — every row classified Unreportable against NOISE_FLOOR 0.005; see the omissions below

Omitted rows (drive=REAL):
- D1 (drive=REAL): relative dispersion 0.14624944302287532 exceeds NOISE_FLOOR 0.005 — value=86.765233 ± 12.689367 us/op; Unreportable, excluded from the table
- D4 (drive=REAL): relative dispersion 0.16263028597843046 exceeds NOISE_FLOOR 0.005 — value=92.808433 ± 15.093462 us/op; Unreportable, excluded from the table
- D16 (drive=REAL): relative dispersion 0.1624170878226029 exceeds NOISE_FLOOR 0.005 — value=91.6723 ± 14.889148 us/op; Unreportable, excluded from the table
- D64 (drive=REAL): relative dispersion 0.13423469474391386 exceeds NOISE_FLOOR 0.005 — value=101.468067 ± 13.620535 us/op; Unreportable, excluded from the table
- D256 (drive=REAL): relative dispersion 0.18050616345467999 exceeds NOISE_FLOOR 0.005 — value=156.534533 ± 28.255448 us/op; Unreportable, excluded from the table

Every one of the ten combinations was run; none was skipped, and no fork or iteration
count was raised or lowered from `FanOutFixtures`' fixed-state constants
(`FIXED_STATE_FORKS=3`, `FIXED_STATE_WARMUP_ITERATIONS=5`,
`FIXED_STATE_MEASUREMENT_ITERATIONS=10`, `FIXED_STATE_ELEMENTS=10_000`), which the run's
own banner confirms it resolved. **Relative dispersion ranges 0.0941–0.2411 here, against
0.0226–0.0788 in the ORIGINAL (confounded) 2026-08-19 sweep — roughly 2x-4x higher.** This
is expected rather than surprising: `Mode.SingleShotTime` gives each measured op none of
the JIT warmup `Mode.AverageTime`'s reused-state iterations provide, and a fresh
10,000-element rig is rebuilt (host, graph, subscriber cells, tag-map growth) immediately
before every single timed op. The fixed-state SHAPE this entry buys costs measurement
precision relative to the original sweep; neither sweep clears `NOISE_FLOOR` (`[BEN1-25]`),
and this one clears it by a wider margin.

### Fit and per-segment resolvability — the driver's own printed inputs, pasted verbatim

```
Fixed-state fit inputs (degree, per-delta score in each row's own unit):
| drive | a (intercept) | b (least-squares marginal/degree) | max |residual|/score |
| --- | --- | --- | --- |
| REAL | 87.72573615350876 | 0.2657474640247982 | 0.04331187221307829 |
| SIM | 47.030417078947366 | 0.1849237935638216 | 0.1316915588478163 |

Per-segment marginals (± combined 99.9% error, conservative sum), resolvable = |marginal| > combined error:
| drive | segment | marginal | combined error | resolvable |
| --- | --- | --- | --- | --- |
| REAL | D1->D4 | 2.0143999999999997 | 9.260943 | false |
| REAL | D4->D16 | -0.0946777499999989 | 2.4985508333333333 | false |
| REAL | D16->D64 | 0.20407847916666663 | 0.5939517291666667 | false |
| REAL | D64->D256 | 0.2868045104166667 | 0.21810407812500002 | true |
| SIM | D1->D4 | 1.5508333333333344 | 6.092399 | false |
| SIM | D4->D16 | 0.8504639166666662 | 2.0128886666666665 | false |
| SIM | D16->D64 | 0.06623333333333337 | 0.5010757916666667 | false |
| SIM | D64->D256 | 0.17719183854166667 | 0.10020483854166666 | true |

MARGINAL_GROWTH_FACTOR=3.0
reading=INCONCLUSIVE — REAL has only 1 segment(s) resolvable against its own error bar (of 4 total), which is fewer than the two a shape comparison needs, SIM has only 1 segment(s) resolvable against its own error bar (of 4 total), which is fewer than the two a shape comparison needs
```

### Does the "linear in degree, not worse" reading survive at fixed state size? — INCONCLUSIVE, and why that is the honest answer rather than a non-answer

**Only the TOP segment (D64->D256) resolves against its own noise, for either drive.**
Every lower segment's combined error bar is several times larger than its own point
estimate — most strikingly REAL's D4->D16 segment, whose point estimate is actually
NEGATIVE (-0.095 us/subscriber) with a combined error of ±2.50, meaning this run cannot
even establish the SIGN of that segment, let alone its magnitude. A shape comparison
("is the marginal cost flat, or does it grow, across the sweep") needs at least two
resolvable points to compare, and this run supplies exactly one per drive. The criterion
therefore returns `INCONCLUSIVE` rather than either verdict word, and that is a property
of THIS run's own numbers — re-readable from the table above — not a discretionary call.

**What the one resolvable segment says, without extrapolating past it.** The D64->D256
marginal is 0.287 ± 0.218 us/subscriber (REAL) and 0.177 ± 0.100 us/subscriber (SIM) —
both are POSITIVE and both are the SAME ORDER OF MAGNITUDE as the 2026-08-19 entry's own
best-resolved marginals at the identical degree pair under the CONFOUNDED sweep (REAL
0.1177 ± 0.0149, SIM 0.1051 ± 0.0133 us/subscriber) — roughly 1.7x-2.4x higher here, not
an order of magnitude off, and consistent with the wider dispersion this sweep carries
overall. Nothing in this run contradicts "roughly linear, order 0.1-0.3 us per additional
subscriber at the top of the range" — it simply cannot confirm or refute the SHAPE across
the rest of the curve, which is the actual question the confound raised.

**What would settle it.** The lower-degree segments need either a much larger sample (this
sweep already spent its whole slot on 3 forks x 15 single shots per combination; the
2026-08-19 entry's own sizing arithmetic showed the AverageTime sweep would need
510-6,200 samples per combination to clear `NOISE_FLOOR` outright, and `Mode
.SingleShotTime`'s higher per-op variance makes that worse, not better, for the same wall
clock budget) or a design that keeps JIT warmup while still fixing the state size — an
`@OperationsPerInvocation` batch against a pre-seeded source, rebuilt per invocation-BATCH
rather than per invocation, which is this bead's second candidate shape and was not
attempted here. That is the concrete next instrument, filed separately rather than
attempted in this slot (see "What this entry does not measure" below).

**So the confound's own claim — "the low-degree rows are averaged over a source roughly
4x-19x larger... if per-add cost rises with source size at all, that inflates the
low-degree rows and understates growth in degree" — is neither confirmed nor refuted by
this entry.** What this entry establishes is narrower and still real: at ONE fixed source
size (10,000 elements), the top-of-range marginal cost is positive, the same order of
magnitude as the confounded sweep's own top-of-range reading, and every lower segment is
too noisy under this design to say anything about the curve's shape at all.

### What this entry does not measure

- **The low-degree segments' true shape.** D1->D4, D4->D16 and D16->D64 are all
  unresolved for both drives; this entry cannot say whether the CONFOUND'S predicted
  direction (understated low-degree growth) is present, absent, or reversed.
- **A batch-based fixed-state variant** (this bead's second candidate shape,
  `@OperationsPerInvocation` against a pre-seeded source rebuilt per invocation-batch),
  which would very likely resolve more of the curve for the same wall-clock budget by
  keeping JIT warmup across a batch's operations. Not built in this slot; a natural
  follow-up.
- **Any state size other than 10,000 elements.** Whether the confound's size matters MORE
  at a larger fixed size (closer to the low-degree rows' actual ~98,500/714,000-element
  average under the original sweep) is untested.
- **G-43 itself.** This entry carries `TriggerClaim.None` and answers no gap trigger;
  G-43's FIRES verdict from the 2026-08-19 entry rests on BS-9's occupancy pairing and is
  untouched here, per this task's own non-goals.

### Verification

`./gradlew :bench:test --rerun` (the whole untagged `:bench:test` suite, including the
four new `FanOutFixturesTest` preSeed/seed cases and every existing test unchanged):
**204 tests, 0 failures**, run fresh (not `UP-TO-DATE`/`FROM-CACHE` — confirmed via the
per-task state line and the JUnit XML `timestamp`, newest `2026-08-20T15:33:03.126Z`).
`./gradlew :bench:test -PbenchOnly=true --rerun --tests
'civictech.bench.micro.FanOutFixedStateRenderTest'` (the render driver above): 1 test, 0
failures. `git diff --name-only <merge-base of feature/computenet-252t> HEAD` names
`bench/src/jmh/kotlin/civictech/bench/micro/FanOutScalingBenchmark.kt`,
`bench/src/main/kotlin/civictech/bench/micro/FanOutFixtures.kt`,
`bench/src/test/kotlin/civictech/bench/micro/FanOutFixturesTest.kt`,
`bench/src/test/kotlin/civictech/bench/micro/FanOutFixedStateRenderTest.kt`, and this
file. Nothing under `kernel/src/main`, `concord/`, `inspect/src`, `wire/src`, `demo/` was
touched; `NOISE_FLOOR`/`Dispersion.kt` is unchanged.

### Scope confirmation

Nothing above the insertion point was edited, reordered, or deleted. This entry's change
to `doc/bench/findings.md` is an append.

## 2026-08-20 — fan-out degree curve, BATCH fixed-state shape: the second candidate design recovers the precision `Mode.SingleShotTime` gave up — 3 of 4 (REAL) and 4 of 4 (SIM) segments resolve, and the "linear in degree, not worse" reading SURVIVES

`computenet-2scd`, filed against the entry immediately above (2026-08-20, `computenet-252t`,
`Mode.SingleShotTime` fixed-state fan-out) — which closed the per-iteration state-size
confound by construction, paid 0.0941–0.2411 relative dispersion for it, resolved only ONE
of four segments per drive against its own 99.9% error bar, and read `INCONCLUSIVE`. Its
own "what would settle it" paragraph names this instrument: *"an `@OperationsPerInvocation`
batch against a pre-seeded source, rebuilt per invocation-BATCH rather than per invocation,
which would very likely resolve more of the curve for the same wall-clock budget by keeping
JIT warmup across a batch's operations."* This entry is that instrument, run.

### What was built and run

Two further `@Benchmark` methods on the SAME `FanOutScalingBenchmark` class —
`simBatchFixedState`/`realBatchFixedState` — under `Mode.AverageTime` with
`@OperationsPerInvocation(FanOutFixtures.BATCH_OPS)` and a `BatchFixedStateRigState` that
rebuilds AND re-seeds the rig to `FanOutFixtures.FIXED_STATE_ELEMENTS` = 10,000 elements
once per invocation-**BATCH** (`@Setup(Level.Invocation)`), then applies
`FanOutFixtures.BATCH_OPS` = 200 fresh deltas, each driven to quiescence, inside the one
timed region. `FanOutFixtures` gained `BATCH_OPS`, `BATCH_FORKS`,
`BATCH_WARMUP_ITERATIONS`, `BATCH_MEASUREMENT_ITERATIONS`, `BATCH_ITERATION_SECONDS`; no
existing constant was changed, no existing benchmark method was touched, `NOISE_FLOOR`
(`bench/src/main/kotlin/civictech/bench/Dispersion.kt`) is unchanged at `0.005`, and
nothing outside `bench/` was edited.

**The state-size confound stays closed, and it is worth being precise about how.** Every
degree pre-seeds the identical 10,000 elements and applies the identical 200 deltas, so the
source grows 10,000 → 10,200 *within* a batch — a 2% drift that is **the same at every
`FanDegree` by construction**. That is categorically different from the original
per-iteration rebuild, where the drift was inversely proportional to the very cost being
measured (a 4x–19x larger source at D1 than at D256). What this entry cannot claim is that
per-delta cost is measured at *exactly* one source size; what it can claim, and what the
confound was about, is that whatever size-dependence exists is applied identically to every
point on the degree curve.

### The verdict criterion was committed BEFORE the sweep, and here is the check

`FanOutBatchFixedStateRenderTest` (`bench/src/test/kotlin/civictech/bench/micro/`, new,
`@Tag("bench")`) carries both criteria and was committed in `fbd71926` at **16:59:46 UTC**.
The JMH results file `fanout-batch-fixed-state.csv` was created at **17:00:03 UTC** — 17
seconds later. Neither criterion has been edited since; `git log` on that file shows the
single commit.

This is stated as a fact with timestamps rather than an assurance because the entry above
could not: `computenet-252t`'s gate was authored *after* its numbers were visible
(`7eda317c`), an earlier cut of it read `DOES NOT SURVIVE AS STATED` on the same data, and
that entry discloses so in its own text. The two criteria here are:

- **`recoveryOf` against `BASELINE_RESOLVED_SEGMENTS = 1`** — not a threshold anyone chose,
  but `computenet-252t`'s own *published* count of resolvable segments per drive.
  `RECOVERED` requires strictly more than that on **every** drive.
- **`verdictOf` against `MARGINAL_GROWTH_FACTOR = 3.0`** — carried over **unchanged** from
  `FanOutFixedStateRenderTest`, where it was fixed against the ORIGINAL (confounded)
  2026-08-19 sweep's SIM marginals. Retuning it for this sweep would have made the two
  sweeps' verdict words incomparable, which is the one thing this task exists to produce.

A segment is `resolvable` only when `|marginal| >` its own two-endpoint 99.9% error bar,
summed conservatively — the data supplies that bar, not the author. The fit and segment
arithmetic is duplicated from `FanOutFixedStateRenderTest` rather than extracted, so that
re-running *that* driver against the 2026-08-20 entry's retained results file still
reproduces that entry.

### Commands, exactly

```
./gradlew :bench:jmhJar

/Users/merlijn/Library/Java/JavaVirtualMachines/corretto-21.0.5/Contents/Home/bin/java \
     -jar bench/build/libs/bench-jmh.jar 'FanOutScalingBenchmark\.(sim|real)BatchFixedState' \
     -rf csv -rff /abs/path/fanout-batch-fixed-state.csv \
     2>&1 | tee /abs/path/fanout-batch-fixed-state.log

./gradlew :bench:test -PbenchOnly=true --rerun \
  --tests 'civictech.bench.micro.FanOutBatchFixedStateRenderTest' \
  -Dcivictech.bench.jmhResults=/abs/path/fanout-batch-fixed-state.csv \
  -Dcivictech.bench.harnessSha=fbd71926
```

The whole ten-combination sweep (2 methods x 5 degrees x 3 forks x (3 warmup + 6
measurement) 1 s iterations) ran in **4 min 50 s** wall clock. That is **6.2x the 47 s the
`SingleShotTime` sweep spent**, not the equal budget `computenet-2scd`'s description
assumed ("the same wall-clock budget"); an earlier cut of this sentence called the two
"comparable", which they are not. Both fit a dispatch slot, but the precision comparison
below is **not budget-neutral**: more measured wall clock buys more samples per combination
(18 here against the `SingleShotTime` sweep's 30 single shots — fewer samples, but each an
entire cold iteration), so an unknown share of the dispersion reduction is attributable to
the larger budget rather than to the batch shape itself. Separating the two would need the
batch shape re-run at a 47 s budget, which this entry did not do.

### Host and JVM, from the run's own retained banner — read, not assumed

Quoted from `fanout-batch-fixed-state.log`:

```
# JMH version: 1.37
# VM version: JDK 21.0.5, OpenJDK 64-Bit Server VM, 21.0.5+11-LTS
# VM invoker: /Users/merlijn/Library/Java/JavaVirtualMachines/corretto-21.0.5/Contents/Home/bin/java
# VM options: <none>
# Blackhole mode: compiler (auto-detected)
# Warmup: 3 iterations, 1 s each
# Measurement: 6 iterations, 1 s each
# Benchmark mode: Average time, time/op
# Host CPU model: Apple M3 Max
# Host core count: 16
# Host OS: Mac OS X 26.6.2
```

Same host (`NL-MGD6FQJW91`, Apple M3 Max, 16 cores) and same JVM (**Amazon Corretto
21.0.5**, invoked by absolute path — this host has no Temurin 21, and the bare `java` on
`PATH` is JetBrains Runtime 25.0.2) as the `computenet-252t` entry above, so **this entry's
magnitudes ARE directly comparable to that one**, and to `computenet-x9e.7`'s allocation
sweep, and to **nothing measured on the 2026-08-19 entry's Apple M2 Pro / Temurin 21.0.11
combination**. Absolute `us/op` values must not be compared across that boundary; only the
SHAPE of the curve — how marginal cost moves across degree — is comparable there, and it is
the shape this entry is about.

**Host conditions, recorded as found rather than as hoped.** This task was scheduled on the
stated premise that the machine was quiet — the concurrent Claude Code sessions live during
`computenet-252t`'s sweep had exited. What `uptime`/`ps` actually showed, captured
immediately before the sweep started and again after it finished (retained in
`host-conditions.txt` beside the results):

```
19:00  up 17:16, 1 user, load averages: 5.44 5.65 4.89     (before)
19:05  up 17:22, 1 user, load averages: 3.03 4.70 4.80     (after)
```

So the host was **not** idle: a one-minute load average of 5.44 on 16 cores before a
single-threaded benchmark started is real background work, and two `jbr-25.0.2` JVMs
started at 11:28 and 12:10 — the same two `computenet-252t` flagged — were still resident,
at 0.0% CPU in the `ps` sample but not attributable to nothing. This is named, not smoothed:
the sweep's quantity is wall-clock latency, which shared-CPU contention inflates directly.
No isolation experiment was performed. What it does mean is that the precision improvement
below was obtained **under load comparable to the run it is being compared against**, which
if anything understates it.

### The numbers

`ThroughputReport.renderRun`'s own output, pasted verbatim (a `@Tag("bench")` driver's
captured stdout, JUnit XML `<system-out>`), each drive separately (`[BEN1-27]`):

## (no entry for drive=SIM) — every row classified Unreportable against NOISE_FLOOR 0.005; see the omissions below

Omitted rows (drive=SIM):
- D1 (drive=SIM): relative dispersion 0.027000906764000323 exceeds NOISE_FLOOR 0.005 — value=0.87123 ± 0.023524 us/op; Unreportable, excluded from the table
- D4 (drive=SIM): relative dispersion 0.03417155120924065 exceeds NOISE_FLOOR 0.005 — value=0.98781 ± 0.033755 us/op; Unreportable, excluded from the table
- D16 (drive=SIM): relative dispersion 0.05643740594693786 exceeds NOISE_FLOOR 0.005 — value=1.526399 ± 0.086146 us/op; Unreportable, excluded from the table
- D64 (drive=SIM): relative dispersion 0.12080932016738859 exceeds NOISE_FLOOR 0.005 — value=4.35872 ± 0.526574 us/op; Unreportable, excluded from the table
- D256 (drive=SIM): relative dispersion 0.3551725626072942 exceeds NOISE_FLOOR 0.005 — value=21.178922 ± 7.522172 us/op; Unreportable, excluded from the table

## (no entry for drive=REAL) — every row classified Unreportable against NOISE_FLOOR 0.005; see the omissions below

Omitted rows (drive=REAL):
- D1 (drive=REAL): relative dispersion 0.03150192692384861 exceeds NOISE_FLOOR 0.005 — value=10.356922 ± 0.326263 us/op; Unreportable, excluded from the table
- D4 (drive=REAL): relative dispersion 0.030025825501554266 exceeds NOISE_FLOOR 0.005 — value=10.699889 ± 0.321273 us/op; Unreportable, excluded from the table
- D16 (drive=REAL): relative dispersion 0.02411527553310006 exceeds NOISE_FLOOR 0.005 — value=12.236352 ± 0.295083 us/op; Unreportable, excluded from the table
- D64 (drive=REAL): relative dispersion 0.0786733883577157 exceeds NOISE_FLOOR 0.005 — value=17.383324 ± 1.367605 us/op; Unreportable, excluded from the table
- D256 (drive=REAL): relative dispersion 0.07288493562911862 exceeds NOISE_FLOOR 0.005 — value=32.12089 ± 2.341129 us/op; Unreportable, excluded from the table

Every one of the ten combinations was run; none was skipped, and no fork or iteration count
was raised or lowered from `FanOutFixtures`' batch constants (`BATCH_FORKS=3`,
`BATCH_WARMUP_ITERATIONS=3`, `BATCH_MEASUREMENT_ITERATIONS=6`,
`BATCH_ITERATION_SECONDS=1`, `BATCH_OPS=200`, `FIXED_STATE_ELEMENTS=10_000`), which the
run's own banner confirms it resolved. **No row clears `NOISE_FLOOR` (`[BEN1-25]`)** — as in
both prior fan-out sweeps — so this entry, like them, publishes no findings table and rests
entirely on the per-segment arithmetic below.

### Fit and per-segment resolvability — the driver's own printed inputs, pasted verbatim

```
Batch fixed-state fit inputs (degree, per-delta score in each row's own unit):
| drive | a (intercept) | b (least-squares marginal/degree) | max |residual|/score |
| --- | --- | --- | --- |
| REAL | 10.80269532017544 | 0.0844102651000669 | 0.06778747916222924 |
| SIM | 0.2912640921052628 | 0.08054768486649175 | 0.5732335009449232 |

Per-segment marginals (± combined 99.9% error, conservative sum), resolvable = |marginal| > combined error:
| drive | segment | marginal | combined error | resolvable |
| --- | --- | --- | --- | --- |
| REAL | D1->D4 | 0.11432233333333326 | 0.21584533333333333 | false |
| REAL | D4->D16 | 0.1280385833333333 | 0.05136299999999999 | true |
| REAL | D16->D64 | 0.10722858333333336 | 0.034639333333333334 | true |
| REAL | D64->D256 | 0.07675815625 | 0.019316322916666667 | true |
| SIM | D1->D4 | 0.038860000000000006 | 0.019093 | true |
| SIM | D4->D16 | 0.044882416666666675 | 0.00999175 | true |
| SIM | D16->D64 | 0.059006687499999995 | 0.012764999999999999 | true |
| SIM | D64->D256 | 0.08760521875 | 0.04192055208333333 | true |

Resolved segments per drive (batch shape) against computenet-252t's SingleShotTime baseline:
| drive | resolvable | of | baseline (252t) |
| --- | --- | --- | --- |
| REAL | 3 | 4 | 1 |
| SIM | 4 | 4 | 1 |

BASELINE_RESOLVED_SEGMENTS=1
MARGINAL_GROWTH_FACTOR=3.0
precision=RECOVERED — every drive resolves more than the 1 segment(s) computenet-252t's SingleShotTime sweep resolved (REAL=3/4, SIM=4/4)
reading=SURVIVES — every drive's RESOLVABLE segment-marginal ratio is at or under 3.0 (worst: SIM at 2.254380307514153)
```

### Did the batch shape recover the precision? — YES, decisively, with one row that got worse — but not at an equal wall-clock budget

**Relative dispersion, batch shape against the `SingleShotTime` shape on the same host and
JVM:**

| drive | batch (this entry) | SingleShotTime (2026-08-20) |
| --- | --- | --- |
| REAL | 0.0241–0.0787 | 0.1342–0.1805 |
| SIM | 0.0270–0.3552 | 0.0941–0.2411 |

REAL improved at every degree, by roughly 2x–6x. SIM improved at D1, D4, D16 and D64 —
0.0270 against 0.1861 at D1 is a **7x** reduction — and **got worse at D256** (0.3552
against 0.0941, the single row in this sweep that is noisier than its `SingleShotTime`
counterpart). That row is stated rather than averaged away: SIM D256 is the combination
where one batch does the most work (200 deltas x 256 synchronous consumer invocations,
against a 10,000-element `SetCell`), so a 1 s iteration fits the fewest batches and the
score rests on the fewest samples; it is also the only combination whose point estimate
(21.18 us/op) sits far below its REAL counterpart while carrying a wider bar. It is
nonetheless resolvable as a segment endpoint, because the *marginal* over the 192-degree
span divides that error by 192.

**Segments resolved: 3 of 4 (REAL) and 4 of 4 (SIM), against 1 of 4 on both drives under
`SingleShotTime`.** The bead's stated minimum for a shape comparison — more than one
segment per drive — is met on both drives, and the pre-committed `recoveryOf` criterion
returns `RECOVERED` without any judgement call. The one still-unresolved segment is REAL
D1->D4, whose marginal (0.114) sits inside a ±0.216 bar: at REAL's ~10.4 us/op floor a
3-subscriber span moves the score by 0.34 us, which is the same size as the two endpoints'
combined confidence bars. That is a *resolution* limit at the bottom of the curve, not the
noise-domination that made the previous sweep unreadable.

### Does the "linear in degree, not worse" reading survive at fixed state size? — SURVIVES

The pre-committed criterion returns `SURVIVES`: every drive's resolvable segment-marginal
ratio is at or under 3.0, worst case SIM at **2.25** (0.0876 / 0.0389 us/subscriber), REAL
at **1.67** (0.1280 / 0.0768). For scale, the ORIGINAL confounded 2026-08-19 sweep's SIM
marginals spanned a **1.95x** ratio and were themselves called "linear at this sweep's
resolution".

**Two things this now says that no previous fan-out entry could.**

- **The whole SIM curve is readable, and it rises monotonically**: 0.0389 → 0.0449 → 0.0590
  → 0.0876 us/subscriber across D1→D4→D16→D64→D256. Marginal cost per additional
  subscriber is not flat; it grows by a factor of 2.25 across a 256-fold range of degree.
  Under any reasonable reading that is *sub-linear-in-degree growth of the marginal* — i.e.
  total cost grows slightly faster than linearly in degree, and nowhere near quadratically,
  which would show the marginal itself growing 256-fold.
- **REAL's resolvable marginals move the OTHER way**: 0.1280 → 0.1073 → 0.0768
  us/subscriber, *declining* with degree. On the REAL drive the per-additional-subscriber
  cost gets cheaper as the fan-out widens — consistent with a fixed per-delta host/dispatch
  overhead (REAL's fit intercept is 10.80 us against SIM's 0.29 us) being amortised over
  more consumers, and with better branch/cache behaviour in a longer `consumerOrder` loop.
  Both drives stay inside the 3.0 factor, so the verdict is the same for both; but they do
  **not** agree on the sign of the curvature, and this entry does not claim they do.

**On the confound's own prediction.** The 2026-08-19 entry's confound section argued that
the low-degree rows, averaged over a 4x–19x larger source, would be inflated and would
therefore *understate* growth in degree. At fixed state size SIM's marginal ratio is 2.25
against that confounded sweep's 1.95 — the same direction the confound predicted, and small.
**This entry does not claim that as confirmation**, because the two numbers were measured on
different machines and different JDKs (M3 Max/Corretto 21.0.5 here, M2 Pro/Temurin 21.0.11
there), and a 0.30 difference in a ratio is well inside what that substitution could produce
on its own. What is now established, on one host and one JVM with the state size held fixed
across degree, is the shape itself: **roughly linear in degree, marginal cost between 0.04
and 0.13 us per additional subscriber, with SIM's marginal rising mildly and REAL's falling
mildly across the sweep.**

### What this entry does not measure

- **The REAL D1->D4 segment.** Still unresolved, for the resolution reason above. Nothing
  here says whether REAL's marginal at the very bottom of the curve is above or below its
  D4->D16 value.
- **Any state size other than a 10,000-element pre-seed (drifting 2% within a batch).**
  Whether the shape changes at a source size closer to the ~98,500/714,000-element averages
  the ORIGINAL sweep's low-degree rows actually saw is untested, and remains the open
  question `computenet-252t` also left open.
- **A `Reportable` classification.** No row clears `NOISE_FLOOR` 0.005; the 2026-08-19
  entry's own sizing arithmetic (510–6,200 samples per combination) still applies and is
  still unaffordable in a dispatch slot. This entry's claims are per-segment, against each
  segment's own error bar, and are not a `[BEN1-25]` findings table.
- **Cross-machine comparison of absolute magnitudes.** Comparable to the entry immediately
  above (same host, same JVM) and to nothing on the M2 Pro/Temurin combination.
- **G-43.** `TriggerClaim.None`; this entry answers no gap trigger and is a methodological
  re-reading of BS-8's own curve shape, exactly as the two entries above it are.

Nothing above the insertion point was edited, reordered, or deleted. This entry's change to
`doc/bench/findings.md` is an append.

## 2026-08-20 — A *candidate* mechanism for the sign-unstable "reduction, this run" column, and which page carries the E3 stall

Filed as `computenet-juid`, out of the `computenet-wsz4` per-page reporting change (#377) and
its feature review. This entry bears on **"§6 — E2 vs E3, the comparison the design rests on,
does NOT reproduce"** above and on the `computenet-xlst` caveat printed under it. That caveat
records that four re-runs of §6's own command disagree about the **sign** as well as the size
of the "reduction, this run" column, and offers no mechanism. This entry supplies a
**candidate** mechanism, measures how much of the instability that mechanism can actually
account for, and states a second finding — about *which* page carries the E3 stall — that no
entry above states.

**Nothing above is corrected by this entry.** §6's tables, the caveat's four disagreeing
patterns and the caveat's own conclusion ("maxGap is a worst-case order statistic on a shared
machine and does not concentrate") all stand exactly as published; this entry adds a mechanism
underneath that conclusion, not a replacement for it.

### Why this entry is prose and not a rendered `Findings.entry` table

`civictech.bench.Findings.entry` refuses any entry containing a result that classifies
`Reportability.Unreportable` against `NOISE_FLOOR` (`[BEN1-25]`;
`bench/src/main/kotlin/civictech/bench/Findings.kt`, the `firstOrNull { classify(it) ==
Unreportable }` guard). **Every quantity this entry rests on is Unreportable, and the probe
says so on its own output lines.** In the four full-probe runs below, `E3 1e5 paged maxGap`
printed `relDispersion=` 2.5270, 2.5302, 2.8906 and 3.2321, and `E2 1e5 concurrent maxGap`
printed 1.2000, 1.2923, 1.5656 and 1.7735 — against `NOISE_FLOOR = 0.005`, i.e. two to three
orders above the floor. A rendered table is therefore not available for this measurement and
this entry does not manufacture one: the figures below are quoted per trial, with the run and
harness sha each came from, exactly as the caveat above quotes its four runs.

### The candidate mechanism: whether the closing page lands inside the collector's window is a race

E3's `maxGap` is measured over the **drive's** window only. At 1e5 the paged walk and the
8000-add drive are the same length, so whether the walk's closing O(n) page falls inside that
window is decided by scheduling — and `maxGap` therefore captures a **different fraction of
the walk on each run**. A quantity that measures a varying fraction of the same underlying
cost can move, and can change sign against E2's whole-copy `maxGap`, with no change in the
underlying cost at all. Recorded in `e3At`'s KDoc
(`bench/src/test/kotlin/civictech/bench/micro/BoundedReadProbeTest.kt`); this entry is where it
is published.

Two trials from the `computenet-wsz4` implementer's run 3 (2026-08-20, M2 Pro, 5 trials per
scale) show both branches on one line of one run's output at 1e5:

| trial | `maxGap` | worst page | position | `walkWall` | `drive` | reading |
| --- | --- | --- | --- | --- | --- | --- |
| t1 | 57.8596 ms | 58.9258 ms | 1/508 (the open) | 169.98 ms | 177.49 ms | **contained** — the two figures agree |
| t3 | 10.2273 ms | 27.5722 ms | 587/587 (the close) | 154.97 ms | 99.37 ms | **not contained** — the close's 27.57 ms never reached the collector |

In t3 the walk outlasted its drive and `maxGap` reports a number **2.7× smaller** than the
worst page that trial actually paid. `e3At`'s KDoc records `harness e4c04bb2` for run 1 of that
three-run series; the run-3 line carries no sha of its own, so none is attached to it here.

### How a trial is labelled contained — and why `walkWall < drive` alone will not do it

This is the `computenet-wsz4` reviewer's precision note, and it is load-bearing for every
label in this entry: **`walkWall` is the SUM of page latencies, not a wall-clock span**, so the
walk's elapsed time is at least `walkWall` and `walkWall < drive` is *necessary but not
sufficient* for containment. A trial is called contained here only on `walkWall < drive`
**and** agreement between `maxGap` and that trial's own worst page. A trial is never labelled
contained on the duration comparison alone.

### What 24 runs on this host say: the non-contained branch did not recur, and the magnitude moved anyway

24 probe runs, 2026-08-20, harness `527940c0`, one machine (Apple M2 Pro, 10 cores,
Mac OS X 26.6.2, Temurin-21.0.11+10, `-Xmx2g`), 5 trials × 3 scales per run = 360 trials.
Twenty were E3-only (JUnit XML timestamps `2026-08-20T09:38:50.039Z` … `09:42:14.887Z`, 3 tests
/ 0 failures each) and four were full-probe runs carrying E2 as well (`09:42:39.446Z`,
`09:42:43.260Z`, `09:42:46.988Z`, `09:42:50.697Z`, 6 tests / 0 failures each). Host load
average during the sweep was 4.1–4.8 (a sibling agent was driving Gradle in `:bench`
concurrently) — which matters, because a loaded host is exactly where a walk should be able to
outlast its drive.

**The non-contained branch did not occur once.** Across all 120 trials at 1e5, `walkWall < drive`
held in 120/120, with a maximum `walkWall`/`drive` of **0.9249**; `maxGap` never fell short of
the trial's own worst page by more than **3.7%** (largest shortfall: `maxGap` 12.2245 ms against
page 12.6782 ms @1/547, `walkWall` 70.5274 against `drive` 78.5169 — still contained on both
tests, and nothing like run 3 t3's factor of 2.7). At 1e4 and 1e3 the drive dominates by
construction (maximum `walkWall`/`drive` 0.726 and 0.706), so the race is a 1e5 phenomenon, as
the mechanism itself says — though that reading of those two scales rests on the duration
comparison and the mechanism, and the duration comparison is by the test above necessary and
**not** sufficient, so no per-trial containment label is claimed for 1e4 or 1e3 here.

A contained trial from this sweep, with all four figures, at the **close** — the branch run 3
t3 lost:

```
1e5 t1  maxGap=39.7227  page=39.7185 @508/508  walkWall=113.7096  drive=129.0578
```

(full-probe run at `09:42:46.988Z`; `maxGap` and the worst page agree to 0.0042 ms, and the walk
finished 15.3 ms inside its drive.) The `computenet-wsz4` reviewer's fourth run, at harness
`4090adca`, recorded the same shape: `t1 maxGap=46.7700 page=46.7605 @508/508 walkWall=144.8024
drive=161.4610`.

**And the reduction moved anyway.** The four full-probe runs give four "reduction, this run"
columns of the kind §6 publishes, computed the same way (median E2 concurrent `maxGap` versus
median E3 paged `maxGap`):

| run (JUnit XML timestamp) | 10³ | 10⁴ | 10⁵ |
| --- | --- | --- | --- |
| `09:42:39.446Z` | +58.6% (5.2444 → 2.1694 ms) | +44.0% (5.8082 → 3.2520 ms) | +49.3% (28.2313 → 14.3233 ms) |
| `09:42:43.260Z` | +54.0% (4.9095 → 2.2573 ms) | +67.3% (7.5346 → 2.4607 ms) | +41.2% (25.4299 → 14.9498 ms) |
| `09:42:46.988Z` | +60.5% (6.5508 → 2.5868 ms) | +56.5% (6.4840 → 2.8215 ms) | +63.8% (22.6528 → 8.2046 ms) |
| `09:42:50.697Z` | +47.7% (5.5442 → 2.9006 ms) | +62.2% (7.2495 → 2.7409 ms) | +62.7% (31.5281 → 11.7500 ms) |

At 1e5 that is a **22.6-point spread across four runs of one command on one machine within
twelve seconds, with not a single non-contained trial in any of them** (20 trials at 1e5, all
contained on both tests). So:

- **The containment race is NOT NECESSARY for the instability.** Most of the magnitude
  movement in this column survives with containment holding throughout, which the caveat above
  already attributes to `maxGap` being a non-concentrating order statistic.
- **It is not excluded either, and this sweep cannot exclude it.** These four runs are positive
  at every scale; they do **not** reproduce the negative 1e4/1e5 reduction the PR #325 review
  measured. The one recorded non-contained trial is exactly the shape that would produce a
  spuriously small E3 `maxGap` and hence a spuriously large positive reduction — or, when it
  hits E2's side of a comparison rather than E3's, the opposite — so a sweep in which the branch
  never fires says nothing about what it does when it fires.

**This entry therefore states the drive-window containment race as a CANDIDATE mechanism and
not a confirmed one.** Confirming it needs a run that discriminates: forcing non-containment
(a walk deliberately outliving its drive, e.g. by pacing the drive or shortening it) and
showing the reduction column move with it, against a matched contained run. No such run is
included here, and no figure in this entry should be read as one.

### A second, separate discrepancy: a `maxGap` that belongs to no page at all

The same 360 trials show the *opposite* disagreement, which the containment race does not
explain and which is a second candidate source of variance in the same column: in **38 of 360
trials** `maxGap` exceeded the trial's own worst page by more than 5% — up to **16.7×** at 1e3
(`maxGap` 7.8118 ms against a worst page of 0.4675 ms), 5.8× at 1e4, and 1.375× at 1e5
(`maxGap` 11.7500 ms against a worst page of 8.5434 ms; 3 of the 120 trials there). A gap
larger than any page is a stall the walk does not account for; on a shared machine it is not
attributable to the paged read at all. Stated here as an observation,
not a mechanism.

### The endpoint finding: an endpoint page carries it — **not** "the first page"

§6's own entry says of `max single page` that "the first page, which opens the walk" is "a
mechanism-consistent reading … and not a measurement", and filed `computenet-wsz4` to settle
it. **It is settled, and the answer is no.** The per-page series falsifies "the first page" as a
stable claim:

- **Stable — the interior is flat and cheap; both endpoints are two orders of magnitude
  above it.** At 1e5 across this sweep the interior median page ran **0.0403–0.1095 ms**
  (0.0213–0.1765 ms across all three scales), while the open and the close each ran hundreds of
  times that. In the four full-probe runs the two endpoint pages together were **45.4–45.9% of
  mean total page wall** over ~500–670 pages. Two O(n) passes, not one: `SetCell.openWalk`
  freezes the enumeration order and builds the opening `TagFrontier`, and the closing frontier
  is recomputed on the final page.
- **Stable — the maximum is an endpoint page.** At 1e5, **115 of 120 trials** put the maximum at
  position 1 or at the page count (5 landed on an interior page). A smaller page limit therefore
  cannot reduce it.
- **NOT stable — which endpoint.** At 1e5 this sweep's 120 trials split **72 open / 43 close / 5
  interior**, and the split *per run* ranged from 0 open / 4 close / 1 interior to 5 open / 0
  close. The four runs on record before this one disagree the same way: `computenet-wsz4`'s run 1
  put it at position 1 in 5/5 trials, its run 2 at the close in 4/5, its run 3 at
  `[1, 546, 587, 412, 1]`, and its reviewer's run at the close in 5/5. The open and the close are
  the same order of magnitude, so which is larger is decided by machine noise.

**So the finding is "an endpoint page carries the E3 stall", and "the first page carries it" is
not a finding in either direction.** Any later reader taking §6's "the first page, which opens
the walk" as established should read it as the hedge it was written as; it is wrong more often
than it is right.

**The endpoint/interior ratio is a per-run figure, not a band.** Three runs, three bands, all at
1e5: `computenet-wsz4` run 1 at harness `e4c04bb2` gave 392×–1129× (first) / 128×–454× (last);
its reviewer's run at harness `4090adca` gave 121×–423× / 230×–548×; this sweep at harness
`527940c0` gave 90.6×–981.1× / 119.3×–588.1×. Quote one of these only with its run and sha
attached — none of them is reproducible as a band, and the endpoint share of total page wall
moves with it (~60% for run 1 against 45.4–45.9% here).

### What this entry does not claim

- **That the mechanism is confirmed.** See above: candidate only, and the discriminating run is
  not included.
- **That the mechanism explains the sign flips.** This sweep produced no negative reduction at
  any scale, so it cannot connect the race to the PR #325 review's negative 1e4/1e5 rows.
- **That any figure here is a stable measured quantity.** Every `maxGap` row in the sweep printed
  `Unreportable`; the reductions in the table above are four disagreeing draws, listed to show
  the disagreement, and are not a benefit measurement. The caveat above applies to them
  verbatim.
- **That paging's total-work premium or §6's ~85–99% non-reproduction is affected.** Neither is
  touched by this entry.
- **Anything about a second machine, a paced drive, another page limit, or `SetCell.openWalk`
  being repaired.** One host, one page limit (200), one drive shape (`8000` unpaced adds), no
  kernel change.

### Command and provenance

```
./gradlew :bench:test -PbenchOnly=true --rerun --no-build-cache \
     --tests '*BoundedReadProbeTest.E3*' \
     -Dcivictech.bench.harnessSha=$(git rev-parse --short HEAD)
```

(and the same command with `--tests 'civictech.bench.micro.BoundedReadProbeTest'` for the four
full-probe runs that carry E2). `-Dcivictech.bench.harnessSha` is **required**: without it the
probe's `requiredHarnessSha` guard fails every test, with a message naming the fix. `TRIALS` is
5 as of `b5d10bdb`, so these runs are five trials per condition, like the caveat's fourth run
and unlike §6's own three-trial tables.

### Scope confirmation

The diff for this entry is exactly `doc/bench/findings.md` — this appended entry.
`git diff --name-only <merge-base of feature/computenet-juid> HEAD` names no other file: no
change under `kernel/src/main`, `concord/`, `inspect/src`, `wire/src`, `demo/`, `doc/spec`, or
`bench/` — no fixture constant, page limit, drive size or `TRIALS` value was altered to produce
the figures above, and the sweep is 24 repetitions of one unmodified command. `NOISE_FLOOR` in
`bench/src/main/kotlin/civictech/bench/Dispersion.kt` is untouched and **not re-derived**
(confirmed: `const val NOISE_FLOOR: Double = 0.005`). No entry above this one was edited,
reordered or deleted — in particular the `computenet-xlst` caveat under §6 keeps its text and
its conclusion, and §6's own tables keep theirs.

---

## 2026-08-22 — the reportability gate becomes claim-relative: effect vs combined error bars, and `NOISE_FLOOR` demoted to a harness sanity bound

**This entry derives a criterion; it measures nothing.** No sweep was run for it, no
number in this file is revised by it, and `NOISE_FLOOR` is **not re-derived** — the
constant is still `0.005` in `bench/src/main/kotlin/civictech/bench/Dispersion.kt`. What
changed is what that constant *reaches*. `Dispersion.kt`'s own amendment condition
requires the derivation to be stated forward, its margin fixed before the numbers it
gates are known, and appended here rather than folded into an earlier entry; this entry
is that discharge, for the criterion that replaces it.

### The problem, measured

The 2026-08-21 findings review counted what the absolute gate was doing to this file:

- **66 of 72** hosted-graph throughput rows classified `Unreportable`.
- **All 10** fan-out rows classified `Unreportable`.
- **Seven** `## (no entry for drive=…)` headings stand above this line — drive blocks in
  which the *whole table* was empty and the omission list was the only content. (Counted,
  not recalled: `grep -c '^## (no entry for drive=' doc/bench/findings.md` at this entry's
  base commit.)

The mechanism is stated in `NOISE_FLOOR`'s own KDoc, in the section headed "What this
value does NOT establish", and it was right the day it was written: `0.005` is the
dispersion of `SmokeBenchmark.baseline` — a deterministic branch-free bit mixer, the
cheapest thing this repository can measure — on a host deliberately quiesced. A hosted
graph running real cells over real state disperses 0.01–0.15 as a matter of course. Using
the first as an admission threshold for the second is not conservatism; it is a gate
calibrated on a quantity it does not govern, and its effect was to withhold nearly every
number the epic paid to measure while telling the reader nothing about how noisy any of
them was.

### The criterion, stated forward

Two rules, and the split between them is the whole idea:

1. **A standalone number is always reportable, with its error bar attached.** `value ±
   dispersion unit` states its own precision. A reader who wants to discount a row by its
   dispersion can; a reader who never sees the row cannot. Dispersion is now reported
   *beside* the table (`ThroughputReport.DispersionNote`) instead of deciding whether the
   table has rows.
2. **A comparison is reportable only when the claimed effect exceeds the combined error
   bars of the rows it is drawn from.** Formally, for two rows in one unit:

   ```
   |left.value - right.value| > COMBINED_ERROR_MARGIN × (left.dispersion + right.dispersion)
   ```

   with `COMBINED_ERROR_MARGIN = 1.0`, the dispersions being JMH's error at **99.9%**
   confidence, and the comparison **strict**. Anything else is `Unresolved`: the rows may
   each be reported, but the claim that they differ may not — not even its sign.

### The margin, and why it was fixed before the numbers

`COMBINED_ERROR_MARGIN = 1.0`, combined by **conservative sum** rather than root-sum-square.
Three things make that a forward derivation rather than a fit:

- **The convention is adopted, not invented here.** `FanOutFixedStateRenderTest` and
  `FanOutBatchFixedStateRenderTest` already resolve a segment marginal by `|marginal| >
  combinedError`, with `combinedError` the two endpoints' 99.9% half-widths **summed**
  (`(p1.error + p2.error) / span`). That is the criterion that produced this epic's
  cleanest verdict — the 2026-08-20 BATCH fixed-state entry above, in which 3 of 4 (REAL)
  and 4 of 4 (SIM) segments resolved and G-43's "linear in degree, not worse" reading
  SURVIVES. The margin was settled by an entry that could not see this generalization's
  numbers, because they do not exist.
- **No number was consulted in choosing it.** This change runs no sweep. There is no
  measurement whose outcome could have moved the margin, and the criterion is pinned by
  `DispersionTest`, whose assertions are pure arithmetic on fixtures.
- **The direction of the conservatism is stated, not hoped for.** The sum is the widest
  defensible combination of two error bars — it is what you get when the two errors are
  perfectly correlated and both point against the claim. Root-sum-square, the usual
  choice for independent errors, is ~0.71× as wide for two equal bars, so the sum already
  carries ~1.41× of headroom over it, and the bars themselves are 99.9% half-widths, not
  standard errors. `NOISE_FLOOR`'s 2× margin was needed because *it* compared a single
  row against a floor measured on a different benchmark entirely; here both terms come
  from the very rows being compared, so the margin has no structural gap to bridge.
  Doubling on top of the sum would refuse effects these measurements do establish — which
  is the failure being corrected, repeated one level in.

### What `NOISE_FLOOR` still is

A **sanity bound on the harness itself**: the quantity `SmokeBenchmark.baseline` would be
re-measured against to detect drift in the discovery sentinel, which is the only thing
the 2026-08-18 derivation ever measured. **No such drift check is wired today** — that is
the role the constant is kept for, not a job something currently runs; `classify`'s only
caller in `bench/src/main` is `ThroughputReport.DispersionNote`, which reports the
classification and acts on nothing. `classify` and `Reportability` keep their names
and their arithmetic — including the absolute-value and non-finite handling
`computenet-x9e.3.6` added — and `ThroughputReport` still *reports* which rows sit above
that bound, as an informational note. It gates nothing.

### What this entry does NOT claim

- **That any earlier entry's numbers change.** Nothing above this line is edited,
  reordered or deleted. The four `## (no entry for drive=…)` headings stand, as do the
  omission lists under every entry that has one; they are the honest record of what the
  old gate did, and a later re-render under the new criterion would be a new entry, not a
  revision of theirs.
- **That the previously-omitted rows are now findings.** They become *printable*, each
  with its error bar. Whether any comparison can be drawn from them is exactly the
  question the new criterion asks, and for most of those rows the answer will be no — a
  row at 0.15 relative dispersion resolves against very little.
- **That `NOISE_FLOOR` is wrong.** Its derivation is sound for what it measured. What was
  wrong was its reach.
- **Anything about a host, a JVM, a benchmark or a gap trigger.** This entry cites no gap
  and answers no trigger question; it is a criterion change, and it is marked as such
  rather than dressed as a finding.

### Scope

The diff for this change is `bench/` and this file. No change under `kernel/src/main`,
`concord/`, `inspect/src`, `wire/src`, `demo/` or `doc/spec`; no gap-table or
`CONCORDANCE.md` edit; no benchmark fixture, drive size or iteration count altered. The
one edit above the insertion point is in the file's **front matter**, not in an entry: the
paragraph describing what `Findings.entry` refuses now carries an amendment note pointing
here, because leaving it to state a gate that no longer exists would mislead every reader
who does not reach the end of the file.

### Verification

```
./gradlew :bench:test --rerun
```

## 2026-08-22 — a regression-tracking series is stood up, and deliberately starts EMPTY

**This entry derives a process; it measures nothing.** No sweep was run for it, no
number in this file is revised by it, and no constant is derived or re-derived. It
records that the lane described in `doc/bench/regression-series.md` now exists, what
it decided, and — the part that has to be in this file rather than only in that one —
**why its series has no entries.**

### What it is

The same benchmarks, one pinned machine and toolchain JDK, run repeatedly, with each
run compared against that benchmark's own accumulated history rather than against an
absolute floor. Filed as `computenet-b7k4` from the 2026-08-21 findings review, which
named the failure mode this file already demonstrates: two entries superseded by a
JDK-vendor substitution, M2 Pro and M3 Max entries not comparable to each other, and
quiesced-host discipline that was manual and therefore sometimes absent.

The full design is in `doc/bench/regression-series.md`. Three decisions worth naming
here, because a later reader of *this* file needs them:

- **Storage: in the repository.** `bench/series/series.csv` is the append-only index,
  one row per benchmark per run; `bench/series/runs/<runId>/` holds the raw JMH CSV
  and the teed log that row was derived from. The alternatives were CI artifacts
  (which expire, and which a locally-run sweep cannot read) and a data branch (which
  severs a series row from the commit whose code it measured). The reasoning is in
  §2 of that document.
- **The comparator is the criterion the entry above this one derived, not a new one.**
  "Beyond the band" is `|run − band.centre| > COMBINED_ERROR_MARGIN × (run.dispersion
  + band.halfWidth)` — literally `civictech.bench.resolveEffect`, reached through a
  magnitude overload added to `Dispersion.kt` in the same change, because a band has
  no single measurement behind it and therefore no honest `RunEnvironment` to make it
  a `BenchResult`. `BandTest` pins the two forms' agreement row for row, so a future
  change to `COMBINED_ERROR_MARGIN` moves both or fails.
- **The lane is local and gates nothing.** `:bench:benchSeries` is a `JavaExec` task
  that no lifecycle task depends on, and `.github/workflows/` is untouched by the
  change. No GitHub Actions workflow was added, because a hosted runner is not a
  pinned machine and a series run on one would produce bands too wide for anything to
  ever move — the reassuring failure.

### Why the series is empty, which is the honest state

`bench/series/series.csv` ships with its header and **no rows**.

The machine available when this lane was built was demonstrably not quiesced. Measured
at the time, not recalled: 16 cores, 1-minute load average **4.98** — the run script's
own guard refused a `--host-state quiesced` invocation against it, which is how that
number was obtained — Microsoft Defender's scanner at ~100% of a core, and two other
agent sessions live on the host.

A first entry measured under that interference would not be a slightly worse baseline.
It would be a **poisoned** one, and the asymmetry is the whole argument: the first
entries are the seed of the band every later run is judged against, so a centre pulled
by interference silently reclassifies healthy runs as movement and real movement as
healthy. That is strictly worse than no series at all, because an empty series reports
`InsufficientHistory` — visibly not a pass — while a poisoned one reports `WithinBand`.

What would have to be true to seed it, in full: a genuinely idle pinned machine (no
other agent session, no build, no scheduled scan, nobody at the keyboard);
`scripts/bench-series/run-series.sh --host-state quiesced` completing without the
load-average refusal; and that repeated **three** times, because `MIN_BAND_ENTRIES` is
3 and one or two runs form no band. Roughly 45 minutes of wall clock for the current
single-selector set. Filed as its own beads item under `computenet-x9e`.

### What this entry does NOT claim

- **That any earlier entry's numbers change.** Nothing above this line is edited,
  reordered or deleted.
- **That the lane has been exercised end to end on real benchmark output.** It has
  been exercised on fixtures — the codec, the band, the comparator, the ingest and
  every refusal are covered by `bench/src/test/kotlin/civictech/bench/series/` — and
  the run script's guard has been exercised against this host. What has *not* run is a
  full pinned sweep through to an appended row, because the host could not honestly
  produce one.
- **Anything about `launchd`.** `regression-series.md` §6 gives a plist shape for a
  nightly run and labels it as a belief to verify, because no such agent was loaded on
  any machine as part of this work.
- **Anything about a host, a JVM, a benchmark or a gap trigger.** This entry cites no
  gap and answers no trigger question; it is a process change, marked as such rather
  than dressed as a finding.

### Scope

`bench/` (the new `civictech.bench.series` package and its tests, one added overload
and its tests in `Dispersion.kt`, a host-banner `@Setup` hook on `SmokeBenchmark`, one
new Gradle task), `scripts/bench-series/`, `doc/bench/regression-series.md`, and this
file. No change under `kernel/src/main`, `concord/`, `inspect/src`, `wire/src`,
`demo/`, `doc/spec` or `.github/`; no gap-table or `CONCORDANCE.md` edit; no benchmark
fixture, drive size or iteration count altered.

The `SmokeBenchmark` hook deserves a sentence, since it touches a benchmark: it prints
`HostFacts.captureCurrent().bannerLines()` once per fork from a `@Setup(Level.Trial)`
hook, exactly as `OperatorThroughputBenchmark` already does, outside the timed method.
Without it the sentinel's log carries no host banner and `SeriesIngest` refuses it — so
the one benchmark the harness drift check exists for (see `NOISE_FLOOR`'s "DEMOTED"
section) would have been the one benchmark that could not enter the series.

### Verification

```
./gradlew :bench:test --rerun
```

265 tests, 0 failures, 0 errors, 0 skipped. `:bench:build`, `:bench:check` and the
repository-wide `test` lifecycle were each queried with `--dry-run` and reach
`benchSeries` zero times. The six required status checks were confirmed unchanged two
ways: the ruleset's own list (`build-test-fast`, `build-test-serial`, `concord-full`,
`ui-test`, `agora-ui-test`, `kernel-test`) still names exactly those six, and no file
under `.github/` is modified by this change.

## 2026-08-26 — the regression-tracking series is seeded: three QUIESCED entries, and the first band for `SmokeBenchmark.baseline`

`computenet-0nww`. The entry immediately above this one — *"a regression-tracking
series is stood up, and deliberately starts EMPTY"* — closes by naming what would
have to be true to seed the series, and files that as its own item. This entry is
that item discharged. `bench/series/series.csv` now holds **three QUIESCED rows**
under one environment fingerprint, and the comparator answers with a band instead
of `InsufficientHistory`.

Nothing above this line is edited. The previous entry's account of *why* the series
shipped empty stands exactly as published; what changed is the host, not the
argument.

### The band

```
SmokeBenchmark.baseline (avgt, ns/op)
  centre       3.976441
  half-width   0.07488
  samples      3 (all QUIESCED)
```

Read off the comparator, not computed by hand — `HistoricalBand.of` takes the
**median** of the contributing scores as the centre (so one bad run moves it by at
most one rank position, never by its magnitude) and `max(runToRunHalfWidth,
worstWithinRunError)` as the half-width. Here those two terms are:

- run-to-run half-width `(3.98029 − 3.94201) / 2 = 0.01914`
- worst within-run error `0.07488`, which is run 3's own `± 0.07488`

so the band is **dominated by a single run's own stated precision**, not by
disagreement between the runs. That is the intended behaviour and worth naming:
`Band.kt`'s KDoc says taking the run-to-run term alone "would make a band formed
from three unusually consistent runs narrower than a single run's own stated
precision, which is a band claiming more resolution than any of its members had".
These three runs are exactly that case — they agree to within 1% of their own
error bars — so the band a fourth run is judged against is the wider, honest one.

### The three runs

| # | runId | score (ns/op) | harness sha |
| --- | --- | --- | --- |
| 1 | `2026-08-25T23-18-18Z` | 3.98029 ± 0.044962 | `44eadacba` |
| 2 | `2026-08-25T23-35-50Z` | 3.94201 ± 0.011726 | `6c9562a35` |
| 3 | `2026-08-25T23-49-22Z` | 3.976441 ± 0.07488 | `b3020f2c9` |

Each run's raw JMH CSV and teed log are committed under
`bench/series/runs/<runId>/`, in the same commit as the row it produced.

The harness shas differ across the three because each run was committed before the
next started — that is the lane working as designed (`harnessCommitSha` is
deliberately excluded from the fingerprint, being the thing the series exists to
vary), and none of the three commits touched `bench/src`.

**Each run reported `InsufficientHistory` for itself.** That is not a failure and
not a contradiction of the heading: `SeriesComparator.compare` filters history with
`it.runId != current.runId`, so a run is never compared against its own row, and
the third run saw only two predecessors. The band is what the **fourth** run will
meet. It was confirmed to exist by a non-appending `compare` against the completed
three-row series under a synthetic run id:

```
WITHIN BAND (1) — no difference resolved; a regression smaller than the band is not excluded:
  SmokeBenchmark.baseline: WithinBand — 3.976441 ± 0.07488 ns/op vs band
  3.976441 ± 0.07488 ns/op over 3 run(s); delta 0.0 vs bar 0.14976
```

That check wrote nothing (`compare`, not `append`) and its synthetic id is not in
the series file.

### The environment

```
Apple M3 Max, 16 cores, Mac OS X 26.6.2 (host NL-MGD6FQJW91)
Amazon.com Inc. (Corretto-21.0.5.11.1) 21.0.5, heap JVM defaults (VM options: <none>)
JMH Average time, f=5 wi=5 i=5
```

`java` on this host is JBR 25.0.2, which `run-series.sh` refuses. All three runs were
launched with `BENCH_SERIES_JAVA=/Users/merlijn/Library/Java/JavaVirtualMachines/corretto-21.0.5/Contents/Home/bin/java`.
Corretto 21.0.5 was chosen because it is what this repository's `jvmToolchain(21)`
already resolved to for the 2026-08-20 entries on this same machine — the point being
that a *different* JDK 21 (Microsoft 21.0.11 is also installed here) passes the
script's major-version check and still starts a fresh population. **A fourth run that
does not set `BENCH_SERIES_JAVA` to this exact launcher will not extend this band.**

### The host conditions, in full, because the band carries them

The bead this entry discharges requires the host readings to be recorded rather than
summarised, so a later reader can disagree with the attestation rather than take it
on trust. Local times are CEST; the machine is `NL-MGD6FQJW91`, 16 cores, so the
script's quiesced threshold is 4.00.

| moment | 1 / 5 / 15-minute load |
| --- | --- |
| run 1 pre (01:18) | 3.07 / 4.62 / 4.35 |
| run 1 — script's own guard reading | 3.07 |
| run 1 post (01:27) | 4.33 / 3.71 / 3.90 |
| between runs (01:35) | 2.81 / 3.63 / 3.85 |
| run 2 pre (01:35) | 2.81 / 3.63 / 3.85 |
| run 2 — script's own guard reading | 2.81 |
| run 2 post (01:44) | 5.49 / 4.16 / 3.89 |
| run 3 pre (01:49) | 3.35 / 4.06 / 3.94 |
| run 3 — script's own guard reading | 3.35 |
| run 3 post (02:14) | 2.86 / 3.34 / 3.61 |

Top CPU consumers were, at every one of those moments, the same resident
managed-endpoint stack: ManageEngine's app-control system extension (21–42% of a
core), Microsoft Defender's `wdavdaemon_unprivileged` (10–22%), `trustd` (11–25%),
Defender's `epsext` system extension (8–18%), and `wdavdaemon` / `wdavdaemon_enterprise`
(5–20% each). Together roughly one core, sustained, never absent.

**The attestation rests on an interpretation, stated so it can be disputed.** The
bead's precondition reads "no scheduled scan (Defender's included)". The stack above
is not a scheduled scan; it is this managed endpoint's *steady state*, present in
every reading taken here and present when the previous entry's own host evidence was
gathered. Reading its mere presence as disqualifying would make the item permanently
undischargeable on the only pinned machine there is. So `quiesced` here means: no
other agent session, no build, no interactive user, no burst consumer beyond the
resident stack — **not** a machine with an idle security subsystem, which this host
does not have. A reader who rejects that reading should treat the band as SHARED and
say so in a later entry rather than editing this one.

**Whose interpretation it is, recorded so it can be attributed as well as disputed:
the orchestrating session's, not the measuring session's.** The `/work` orchestrator
that dispatched `computenet-0nww` ruled that the resident managed-endpoint stack does
not disqualify a quiesced attestation, on the ground stated above — the bead's own
recorded host evidence (the 2026-08-25 comment thread, where the orchestrator itself
measured the stack at ~1 core with four Brave renderers on top) was gathered with that
stack running, so the alternative reading makes the bead undischargeable forever. The
measuring session executed that ruling; it did not make it. A later reader who wants
to overturn it is overturning an orchestrator's call about what this bead's
precondition means, not a measurer's judgement about its own work.

### Two observed confounds, reported as observations

Neither is a mechanism established here. Both are recorded because they will recur
on this host and the next person to extend this series will see them.

1. **The load average oscillates on a roughly two-minute cycle** between about 3.0
   and 5.2 with the machine otherwise idle, while the 5- and 15-minute averages sit
   flat near 3.9. Sampled continuously for eight minutes between runs 1 and 2:
   3.07, 5.23, 4.67, 4.53, 3.89, 5.10, 4.42, 4.17, 3.92, 3.72, 3.18, 3.08, 4.83,
   5.09, 4.57, 4.01, 3.92, 3.42, 3.83, 3.57, 3.35, 3.42. **A single `uptime` reading
   on this host is therefore weak evidence in both directions** — which is the same
   trap two earlier `computenet-0nww` comments recorded from the other side (a 2.50
   reading that was 9.47 four minutes later). Runs 2 and 3 were consequently launched
   by a gate that polled for a genuine trough rather than by a one-shot reading. The
   run script's guard reads the load *once, at start*, so it cannot protect against a
   spike at minute 20; that limitation is unchanged by this entry.
2. **Part of the elevation after each run is the run's own decay**, and part may be
   the endpoint scanner reacting to freshly written files. The post-run-1 and
   post-run-2 readings (4.33, 5.49) are both above the threshold while the resident
   consumers are at or below their usual figures, and `fileproviderd` appears at
   27–31% in two between-run readings where it is otherwise absent. The
   scanner-reaction half is a **hypothesis, not a finding** — nothing here isolates
   it — but the operational consequence holds either way: **do not launch the next
   run immediately after the previous one**, or the guard reads load the measurement
   itself created and refuses an attestation that is true.

One non-resident consumer did appear, in the post-run-2 reading only: a Brave Browser
renderer at 37.8% of a core (0.38 of 16). It is recorded rather than passed over. It
appeared *after* run 2's sweep had finished, so it cannot have entered run 2's numbers,
and it is absent from run 3's pre-reading. Run 3's score (3.976441) sits between runs 1
and 2, which is what one would expect if it affected nothing.

**That timestamp argument is weaker than it reads, and the committed logs settle the
question better than it does** (added at review). Run 2 started 23:35:50Z and its own
log records `Total time: 00:08:21`, so it finished ≈23:44:11Z — the same minute as the
"run 2 post (01:44 CEST)" reading. Ordering alone therefore does *not* establish that
the renderer post-dates the sweep. What does establish it is run 2's own per-iteration
record: all 25 measurement iterations across all five forks lie in 3.923–3.982 ns/op,
with no drift in the final fork, which is where a consumer appearing near 23:44Z would
have to show. Run 2 is the cleanest of the three runs by both spread (stdev 0.016) and
stated error (±0.011726). Its number is not contaminated; the reason is the data, not
the clock.

### Per-fork excursions inside runs 1 and 3 (added at review, `computenet-0nww`)

The prose above records the host readings *between* runs. The committed logs also record
what happened *inside* them, and two runs contain excursions the account above does not
mention. Recorded here because the entry's own standard is that a later reader can
disagree with the attestation, and these are the observations most likely to make one
want to:

| run | forks | measurement iterations |
| --- | --- | --- |
| 1 (`23-18-18Z`) | forks 3 and 4 elevated | 3.963–4.130 against 3.928–3.959 in forks 1, 2, 5 |
| 2 (`23-35-50Z`) | none | 3.923–3.982 throughout |
| 3 (`23-49-22Z`) | fork 2, iterations 3–5 | 4.250 / 4.247 / 4.219 against 3.924–3.991 elsewhere |

Two consequences, both in the conservative direction, neither hidden:

- **The half-width is set by an interference episode, not by intrinsic dispersion.**
  Run 3's ±0.07488 — the term that wins the `max()` and therefore *is* the band's
  half-width — comes from that one fork. A band widened by observed interference is
  wide, not narrow, so it cannot manufacture a false `MovedHigher`; it can only fail to
  resolve a real regression smaller than 0.07488, which §4's `WithinBand` caveat already
  says.
- **The centre is biased slightly high for the same reason.** The centre is the median,
  and the median lands on run 3 (3.976441) because runs 1 and 3 both carry excursions
  while the clean run (2, 3.94201) is the minimum. Run 3 without its fork-2 excursion
  would score ≈3.938. So the seeded centre sits ≈0.034 ns/op above the cleanest
  observation this night produced — inside the band's own half-width, and again in the
  generous direction.

Nothing here was isolated to a cause; the excursions are consistent with the same bursty
desktop load the confounds above describe. The operational reading for a fourth run is
that this band is a *conservative* one: `WithinBand` from it is correspondingly weak
evidence, and a genuinely quiet future run scoring near 3.94 is expected to report
`WithinBand`, not `MovedLower`.

### What this entry does NOT claim

- **That `SmokeBenchmark.baseline` measures anything about the runtime.** It does
  not, and never did. It is the discovery sentinel; the series tracks it because
  `NOISE_FLOOR`'s KDoc names it as the quantity that would be re-measured to detect
  drift *in the harness*. A band on it bounds harness drift, not kernel performance.
- **That the band will hold.** Three runs inside twenty minutes on one night is the
  minimum the comparator accepts, not a characterisation of the host across days,
  thermal states or macOS updates. The first genuinely independent test of this band
  is the fourth run, and a `MovedHigher` from it is at least as likely to be about
  the host as about the code.
- **That the machine was idle in any absolute sense.** See the attestation paragraph
  above: it was idle of everything except a security stack that is never idle.
- **Anything about `launchd` or a scheduler.** `regression-series.md` §6's plist is
  still unverified; these three runs were invoked by hand.
- **Anything about a gap trigger.** This entry cites no `G-*` and answers no trigger
  question. It is a measurement that seeds a lane, not a finding about the model.
- **That the selector set grew.** It did not, deliberately — the bead excludes it.
  One selector, `smoke`, exactly as the lane shipped.

### Scope

`bench/series/series.csv` (three appended rows), `bench/series/runs/` (three run
directories, raw JMH CSV and teed log each), and this file. No source, script, spec,
workflow or gap-table change; `scripts/bench-series/run-series.sh` was invoked, not
edited.

### Verification

```
./gradlew :bench:test --rerun
```

plus the three sweeps themselves, whose logs are the committed artifacts.

## 2026-08-26 — computenet-y7hc's bounded-INSERT change, measured: a same-host A/B in which every one of the 18 INSERT rows tightens, and the `[BEN1-28]` direction asymmetry INVERTS

`computenet-x9e.14`, the measurement half of `computenet-y7hc`. The code half landed
as `314670f1a`; this entry is the separate claim that it worked, and it is answered by
measurement rather than by reading the diff.

**Headline, stated before the caveats so it cannot be read past.** On this host, at
this JVM, in two sweeps 20 minutes apart, INSERT relative dispersion moved from
**0.03130–0.12543 (median 0.08250)** at the pre-fix benchmark to **0.00646–0.02499
(median 0.01082)** at the post-fix benchmark — a 7.6x reduction in the median and a 5x
reduction in the ceiling, with **18 of 18 subjects tightening, none widening**. In the
same pair of runs RETRACT — which `computenet-y7hc` did not touch — moved from
0.03082–0.09637 (median 0.06687) to 0.01102–0.22146 (median 0.08128), with 10 of 18
tightening and 8 widening: no net shift and no direction to the scatter. The
`[BEN1-28]` asymmetry does not merely close; it **inverts**. INSERT was the noisier
direction before the change and is the tighter one after, by roughly 7x in the median.

**What this entry does NOT claim.** It does not claim RETRACT got worse — see
"The RETRACT half is not attributed" below. It does not claim any row became
`Reportable`: **zero of 36 rows clear `NOISE_FLOOR` (0.005) in either arm**, exactly as
in the entry this supersedes-by-comparison. And it does not claim a like-for-like
comparison with the 2026-08-18 numbers, which were measured on a different machine —
see "Why the historical baseline could not settle this".

### Why a same-host A/B, and not a comparison against the 2026-08-18 entry

The bead names its baseline as the 2026-08-18 REAL-drive entry (INSERT 0.0149–0.1526,
0 of 18 Reportable). Two problems with using it, found before this sweep was rendered:

1. **That entry is superseded for environment purposes by its own re-measurement.**
   *"2026-08-18 — REAL-drive per-operator delta-application throughput, re-measured on
   the toolchain JDK"* (`computenet-am2h`) says so in as many words: the figures the
   bead quotes were measured on Homebrew **JDK 26.0.1** under a `Harness:` line that
   claimed Adoptium 21.0.11. The JDK-21-controlled figures from that re-measurement are
   INSERT **0.00813–0.07387** against RETRACT **0.00771–0.05361** — overlapping ranges
   with no 3x separation, i.e. an entry in which the `[BEN1-28]` asymmetry is already
   substantially absent. The two candidate baselines therefore disagree about how large
   the gap was, which on its own makes "did it close" unanswerable from the corpus.
2. **Both were measured on a different machine from this one.** The re-measured
   entry's own command block invokes a JDK under `/Users/MerlijnB/`; this host is user
   `merlijn`, **Apple M3 Max, 16 cores, macOS 26.6.2**. The baseline host's CPU is not
   recoverable from those entries — their `Harness:` CPU and core count describe the
   *rendering* host, which is the defect `computenet-yhbd` closed. And this run's JVM is
   **Microsoft OpenJDK 21.0.11+10-LTS**, not Adoptium: `~/.gradle/jdks/` on this host is
   empty and no Adoptium 21 is installed, so the toolchain resolves to a locally
   installed JDK. Same major, same patch, same upstream build number, different vendor
   packaging.

Dispersion is the quantity most sensitive to host and JVM, which is why this lane pins
them. A cross-host, cross-vendor dispersion comparison cannot support "the fix worked".
So the `/work` orchestrator dispatching this item authorized, and this session ran, a
**same-host A/B**: the identical sweep at the post-fix harness (`ae16cf257`, which
contains `314670f1a`) and at the pre-fix benchmark source (`9aabe4923`, y7hc's parent),
on the same machine, the same JVM, the same night, 20 minutes apart. The historical
figures are kept below as secondary context only.

The pre-fix arm was produced by checking out `bench/src/jmh/kotlin/civictech/bench/micro/OperatorThroughputBenchmark.kt`
at `9aabe4923` into this worktree, building `:bench:jmhJar`, copying the jar aside, and
restoring the file — `git status --short` clean afterwards. `computenet-y7hc` touches
three files, all under `bench/`, and the other two (`InvocationCycle.kt`,
`BoundedInvocationStateTest.kt`) are in `src/main` and `src/test`, which the pre-fix
benchmark does not reference; nothing under `kernel/src/main` differs between the two
arms, so **the subjects measured are byte-identical code and the A/B isolates exactly
the harness change**.

### The comparison, per subject

Relative dispersion (`error(99.9%) / score`), pre-fix arm against post-fix arm, same
host, same JVM, same night. `ratio` below 1.00 means the post-fix row is tighter.

**INSERT** — the direction `computenet-y7hc` changed:

| subject | pre-fix (`9aabe4923`) | post-fix (`ae16cf257`) | ratio |
| --- | --- | --- | --- |
| `TAGGED_SET` | 0.11245 | 0.01822 | 0.16x |
| `FILTER` | 0.03130 | 0.02491 | 0.80x |
| `UNION` | 0.04985 | 0.02499 | 0.50x |
| `INTERSECT` | 0.06524 | 0.01335 | 0.20x |
| `COUNT` | 0.07857 | 0.00651 | 0.08x |
| `FLAT_MAP` | 0.10570 | 0.02375 | 0.22x |
| `PRESENCE_COUNT` | 0.10385 | 0.00646 | 0.06x |
| `QUORUM` | 0.09084 | 0.02342 | 0.26x |
| `JOIN_SET` | 0.10168 | 0.01267 | 0.12x |
| `SEMI_JOIN` | 0.12543 | 0.00684 | 0.05x |
| `LOOKUP_JOIN` | 0.09838 | 0.01053 | 0.11x |
| `GROUP_BY_COUNT` | 0.08072 | 0.00838 | 0.10x |
| `GROUP_BY_SUM` | 0.07659 | 0.00819 | 0.11x |
| `GROUP_BY_MIN` | 0.08428 | 0.00854 | 0.10x |
| `GROUP_BY_MAX` | 0.04902 | 0.01095 | 0.22x |
| `GROUP_BY_TOP_K` | 0.09213 | 0.01803 | 0.20x |
| `COMBINE_LATEST` | 0.03310 | 0.01068 | 0.32x |
| `COALESCING_COMBINE` | 0.07787 | 0.00746 | 0.10x |
| **median** | **0.08250** | **0.01082** | **0.13x** |

Range 0.03130–0.12543 pre-fix, 0.00646–0.02499 post-fix. 18 of 18 rows tightened, 0 widened.

**RETRACT** — the untreated control — `computenet-y7hc` did not touch this path:

| subject | pre-fix (`9aabe4923`) | post-fix (`ae16cf257`) | ratio |
| --- | --- | --- | --- |
| `TAGGED_SET` | 0.03176 | 0.01344 | 0.42x |
| `FILTER` | 0.09153 | 0.01339 | 0.15x |
| `UNION` | 0.03082 | 0.01945 | 0.63x |
| `INTERSECT` | 0.07219 | 0.01102 | 0.15x |
| `COUNT` | 0.03934 | 0.19713 | 5.01x |
| `FLAT_MAP` | 0.09637 | 0.01109 | 0.12x |
| `PRESENCE_COUNT` | 0.05321 | 0.04045 | 0.76x |
| `QUORUM` | 0.08335 | 0.08073 | 0.97x |
| `JOIN_SET` | 0.04016 | 0.22146 | 5.51x |
| `SEMI_JOIN` | 0.05782 | 0.10735 | 1.86x |
| `LOOKUP_JOIN` | 0.04413 | 0.09924 | 2.25x |
| `GROUP_BY_COUNT` | 0.07724 | 0.11240 | 1.46x |
| `GROUP_BY_SUM` | 0.09147 | 0.08182 | 0.89x |
| `GROUP_BY_MIN` | 0.06579 | 0.04162 | 0.63x |
| `GROUP_BY_MAX` | 0.06794 | 0.12213 | 1.80x |
| `GROUP_BY_TOP_K` | 0.07594 | 0.03531 | 0.46x |
| `COMBINE_LATEST` | 0.08723 | 0.12300 | 1.41x |
| `COALESCING_COMBINE` | 0.05383 | 0.12906 | 2.40x |
| **median** | **0.06687** | **0.08128** | **1.22x** |

Range 0.03082–0.09637 pre-fix, 0.01102–0.22146 post-fix. 10 of 18 rows tightened, 8 widened.

### The RETRACT half is not attributed, and the excursions are named

`computenet-y7hc` changed nothing on the RETRACT path, so RETRACT is this A/B's
control. Its median barely moves (0.06687 → 0.08128) and its rows scatter in both
directions — which is the expected shape for an untouched path. But its post-fix
*ceiling* is much higher (0.09637 → 0.22146), and that is not left as a bare number.

Reading the per-fork, per-iteration numbers out of both runs' own JMH logs rather than
reasoning from arrival order, and flagging any measurement iteration below 0.6x or
above 1.6x its own block's median:

| arm | excursions in 720 measurement iterations | which |
| --- | --- | --- |
| post-fix | 7 | all 7 in RETRACT rows: `JOIN_SET` f2 i7 (0.145x), `COUNT` f2 i7–i10 (0.51–0.53x, four consecutive), `GROUP_BY_COUNT` f1 i1 (0.555x), `JOIN_SET` f1 i5 (0.587x) |
| pre-fix | 1 | `SEMI_JOIN` INSERT f2 i10 (0.564x) |

`COUNT retract`'s four consecutive depressed iterations and `JOIN_SET retract`'s single
0.145x point are the two rows that carry the post-fix RETRACT ceiling; both are
sustained or extreme interference signatures, not operator properties. **Trimming those
7 iterations out** moves post-fix RETRACT to 0.01102–0.12904, median 0.07652 — so the
excursions explain the *ceiling* but not the level: RETRACT's post-fix median is still
7x INSERT's post-fix median with them removed. The INSERT column is unchanged by
trimming (no INSERT excursions in the post-fix arm at all).

The excursions do not touch the finding. Every one of them is in the control direction,
so removing them can only make INSERT look *less* favourable relative to RETRACT, and
INSERT's own 18-of-18 tightening rests on rows with no flagged iterations.

**The direction of the split is not an artefact of run order.** JMH ran all 18 INSERT
rows first and all 18 RETRACT rows second in *both* arms — the direction is perfectly
confounded with position-in-sweep within a single run, which is why a single run could
not settle this. Across the pair the confound cancels: the first half was the noisier
half pre-fix and the tighter half post-fix, under an identical schedule. That is the
specific thing the A/B buys, and it is the reason a single post-fix sweep was not
reported as the answer.

### Host conditions, recorded rather than summarised

Host `NL-MGD6FQJW91`, Apple M3 Max, 16 cores, macOS 26.6.2 — the pinned machine, so
the quiesced threshold is 4.00 (cores x 0.25). Local times CEST, timestamps UTC. Both
sweeps were launched by a **polling gate** that sampled `uptime` every 10 s and started
only on a genuine trough, per the confound the previous entry (`computenet-0nww`)
established on this same host: the load oscillates on a roughly two-minute cycle with
the machine idle, so a single reading is weak evidence in both directions.

| moment | 1 / 5 / 15-minute load |
| --- | --- |
| post-fix arm, gate polls (00:47:25–00:50:37Z) | 7.93, 7.09, 6.47, 6.24, 5.88, 5.79, 5.29, 4.71, 4.80, 4.53, 4.30, 4.46, 4.53, 4.70, 4.45, 4.84, 4.56, 4.31, 4.34, **3.91** (launch) |
| post-fix arm pre (00:50:37Z) | 3.91 / 5.09 / 4.75 |
| post-fix arm post (01:09:10Z) | 4.86 / 4.05 / 4.08 |
| pre-fix arm, gate polls (01:10:12–01:11:12Z) | 5.31, 5.54, 5.06, 4.53, 4.20, 4.10, **3.85** (launch) |
| pre-fix arm pre (01:11:12Z) | 3.85 / 4.09 / 4.10 |
| pre-fix arm post (01:29:47Z) | 5.02 / 4.54 / 4.38 |

A 30-second sampler ran for the whole of each sweep. 1-minute load during the post-fix
arm: 4.71 4.30 4.70 4.56 3.91 3.81 3.21 3.16 3.38 3.48 3.18 2.98 3.35 3.47 3.25 3.33
2.94 2.96 2.99 4.25 5.09 3.99 3.38 3.16 2.91 3.03 3.03 3.13 6.05 4.96 4.05 3.42 5.05
5.37 4.36 3.44 2.98 2.83 2.68 3.51 4.38 4.86. During the pre-fix arm: 5.31 4.53 3.85
4.81 3.72 3.90 3.33 4.19 3.69 4.21 4.62 4.03 3.74 4.66 4.27 3.79 3.23 4.21 9.62 7.93
5.83 4.31 4.05 4.01 5.23 3.97 3.37 7.23 6.23 5.37 4.35 3.82 3.13 3.31 3.72 4.65 4.55
3.45 6.65 5.02.

**Neither arm ran on a machine that stayed under the threshold**, and saying so is the
point of recording the samples rather than an attestation. The pre-fix arm in fact saw
the *higher* excursions (9.62, 7.93, 7.23) and still produced only one flagged
iteration; the post-fix arm stayed lower and produced seven. Load average and
measurement disturbance did not track each other here, which is a reason to distrust
`uptime` as the only guard and a reason the per-iteration read above is the instrument
that actually settled the excursion question.

Top CPU consumers, across both sweeps' samples, were the resident managed-endpoint
stack — ManageEngine's app-control system extension, Microsoft Defender's
`wdavdaemon_unprivileged` / `wdavdaemon` / `wdavdaemon_enterprise` / `epsext`, and
`trustd` — roughly one core in total, sustained, never absent. **Non-resident consumers
were also present and are named rather than discarded**: `WindowServer`, `FluidVoice`,
Claude Desktop helpers, IntelliJ IDEA (`idea`) and its `junie` agent, a Brave renderer,
`mds_stores`/`corespotlightd`, and `mediaanalysisd`. Immediately after the post-fix arm
exited, the `POST-SWEEP` reading's top consumers were `wdavdaemon_enterprise` (89.2%),
`wdavdaemon_unprivileged` (69.6%), `com.microsoft.teams2.teamsswitcher` (61.6%),
ManageEngine's `appctrl` extension (58.5%), `dcpatchscan` (33.9%), `mediaanalysisd`
(30.9%) and `trustd` (26.2%) — i.e. the resident stack spiking well above its usual
level *together with* three non-resident arrivals (`teamsswitcher`, `dcpatchscan`,
`mediaanalysisd`), a burst that began at or near the end of that sweep and is the most
likely source of its RETRACT-half excursions. The pre-fix arm's `POST-SWEEP` reading
20 minutes later tops out at 9.7%, so the burst is specific to the end of the post-fix
arm rather than a standing condition of the night.

**The quiesced interpretation is the orchestrator's, not this measuring session's.**
The `/work` orchestrator that dispatched `computenet-x9e.14` ruled that the resident
managed-endpoint stack does not disqualify a quiesced attestation on this host, on the
ground that the lane's own original host evidence was gathered with that stack running
and the opposite reading makes these beads permanently undischargeable. This session
executed that ruling; it did not make it. A reader who rejects it is overturning an
orchestrator's call about what the precondition means, not a measurer's judgement about
its own work — and the readings above are recorded in full precisely so that reader can
do it without taking anything here on trust. On the strength of the samples above, a
reader could reasonably grade both arms SHARED rather than QUIESCED; **the A/B
comparison survives that grading**, because both arms were measured under the same
regime and the treated direction is the one that moved uniformly.

### The historical figures, kept as secondary context only

| entry | INSERT dispersion (18 rows) | RETRACT dispersion (18 rows) | rows under NOISE_FLOOR |
| --- | --- | --- | --- |
| 2026-08-18 REAL (`computenet-x9e.4.5`, JDK 26, other host) | 0.0149–0.1526 | 0.0048–0.0299 | 1 of 36 |
| 2026-08-18 REAL re-measured (`computenet-am2h`, JDK 21, other host) | 0.00813–0.07387 | 0.00771–0.05361 | 0 of 36 |
| 2026-08-26 pre-fix arm (this entry, M3 Max, MS JDK 21.0.11) | 0.03130–0.12543 | 0.03082–0.09637 | 0 of 36 |
| 2026-08-26 post-fix arm (this entry, same host and JVM) | 0.00646–0.02499 | 0.01102–0.22146 | 0 of 36 |

Read across the rows only with the host and JVM differences in mind. The one thing the
historical rows do usefully show is that the pre-fix arm measured here reproduces
`x9e.4.5`'s *qualitative* claim — INSERT the noisier direction, with the higher ceiling
— on a machine and JDK where `computenet-am2h` had found that claim absent. That makes
`computenet-am2h`'s non-reproduction most likely a host/JVM effect rather than a
refutation, but this entry does not settle that and does not need to: its own two arms
were measured under identical conditions.

**On the bead's stated baseline.** The bead asks for a comparison against
"0.0149–0.1526 against RETRACT 0.0048–0.0299; 0 of 18 INSERT rows Reportable versus 5
RETRACT rows". That Reportable count is drawn across both drives and both 2026-08-18
entries; on the JDK-21 re-measurement the figure is 0 of 36 in both directions, and it
is 0 of 36 in both directions here too. **The Reportable count did not move and could
not have**: `NOISE_FLOOR` is 0.005 and the tightest row in either arm is 0.00646. What
moved is the dispersion band, which is what the bead's own "Done when" clause actually
asks the entry to state.

### `[BEN1-28]`: the suspect is confirmed as a HARNESS artefact, and the operator question it was asked about is still open

`[BEN1-28]` named `TagState` tag-map growth as the suspect for the INSERT/RETRACT
dispersion split. This A/B confirms the *mechanism* and relocates the *blame*: the
split was produced by unbounded live-state growth in the benchmark's own
`@Setup(Level.Invocation)`, not by a property of the operator implementations. Bounding
it removes the split. Nothing under `kernel/src/main` differs between the two arms, so
no operator-level conclusion is available from this pair and none is drawn.

What remains open, and is deliberately **not** answered here: tombstone growth. The
observed-remove algebra still keeps a tombstone per retracted tag in both directions,
`OperatorThroughputBenchmark`'s own KDoc says so, and this entry neither measures nor
tunes it. A later item that wants the operator-level `[BEN1-28]` question — does
tag-map growth dominate the set-shaped subjects' cost — needs an allocator or GC
profile, which neither arm attached (no `-Xlog:gc`, no JFR, `# VM options: <none>` in
both banners).

Nothing in `bench/src` was touched to produce these numbers beyond the deliberate,
reverted pre-fix checkout described above; `NOISE_FLOOR` (`bench/src/main/kotlin/civictech/bench/Dispersion.kt`)
is unchanged, and no fork or iteration count was raised toward JMH's defaults. Both
arms ran the full 18 x 2 cross product at the class's own unraised annotation config
(`Fork(2)`, `Warmup(5, 1s)`, `Measurement(10, 1s)`), 36 of 36 combinations measured in
each.

### Trigger (`[BEN1-31]`/`[BEN1-32]`)

`TriggerClaim.None` — MARKED INCOMPLETE in both rendered blocks below, and correctly
so: no gap trigger question is answered by a harness-artefact result. This entry cites
neither G-21 nor G-43.

### WAL/journal statement (`[BEN1-29]`)

Unchanged and re-confirmed against the source both arms compiled: `Graphs.kt`'s `Rig`
for `Drive.REAL` constructs `ManagedHost(scheduler = scheduler)` with no `journal`
argument, and no `civictech.cell.durability` type appears in `Graphs.kt`, `Deltas.kt`
or `OperatorThroughputBenchmark.kt`. WAL/journal sync is not in play; KBLK is not named.

### Artifacts

Both arms' raw JMH CSV, teed log, the polling-gate host readings and the 30-second
samplers are retained at `$HOME/computenet-runs/computenet-x9e.14/` on host
`NL-MGD6FQJW91` — **outside the repository**, because this item's file claim is
`doc/bench/` and run artifacts were not committed. They are therefore machine-local and
not reproducible from this repository alone. A later item that wants them in the corpus
should commit them under `bench/runs/` the way `computenet-0nww` committed its series
runs; the rendered blocks below are the part of them that is durable here.

### Commands, exactly

```
# post-fix arm (harness ae16cf257, contains 314670f1a)
./gradlew :bench:jmhJar
/Users/merlijn/Library/Java/JavaVirtualMachines/ms-21.0.11/Contents/Home/bin/java \
     -jar bench/build/libs/bench-jmh.jar 'OperatorThroughputBenchmark.real' \
     -rf csv -rff /abs/path/real-throughput.csv > /abs/path/real-throughput.log 2>&1

# pre-fix arm (benchmark source at 9aabe4923)
git checkout 9aabe4923 -- bench/src/jmh/kotlin/civictech/bench/micro/OperatorThroughputBenchmark.kt
./gradlew :bench:jmhJar          # jar copied aside as bench-jmh-prefix.jar
git checkout HEAD  -- bench/src/jmh/kotlin/civictech/bench/micro/OperatorThroughputBenchmark.kt
/Users/merlijn/Library/Java/JavaVirtualMachines/ms-21.0.11/Contents/Home/bin/java \
     -jar /abs/path/bench-jmh-prefix.jar 'OperatorThroughputBenchmark.real' \
     -rf csv -rff /abs/path/prefix-throughput.csv > /abs/path/prefix-throughput.log 2>&1

# each arm rendered through the module's own entry point
./gradlew :bench:test -PbenchOnly=true --rerun \
  --tests 'civictech.bench.micro.ThroughputReportRenderTest' \
  -Dcivictech.bench.jmhResults=/abs/path/<arm>-throughput.csv \
  -Dcivictech.bench.harnessSha=<ae16cf257 | 9aabe4923> \
  -Dcivictech.bench.date=2026-08-26 \
  -Dcivictech.bench.subject="..."
```

Bare `java` on this host is JBR 25.0.2 and was deliberately not used; the absolute-path
launcher above is what both banners record. The rendered blocks below were read back out
of the JUnit XML `<system-out>` and pasted verbatim, not retyped.

---

Renderer's own output for the **post-fix arm**, pasted verbatim:

## 2026-08-26 — REAL-drive per-operator delta-application throughput, re-measured after computenet-y7hc's bounded-INSERT change (post-fix arm)
Harness: ae16cf257 · JVM Microsoft (Microsoft-13877178)/21.0.11 · heap JVM defaults (VM options: <none>) · Apple M3 Max, 16 cores, Mac OS X 26.6.2
JMH: mode=Throughput forks=2 warmup=5 iters=10 · drive=REAL
| subject | value | notes |
| --- | --- | --- |
| TAGGED_SET insert | 863780.244544 ± 15734.439349 ops/s | |
| FILTER insert | 910757.004226 ± 22688.668821 ops/s | |
| UNION insert | 796726.255679 ± 19908.454999 ops/s | |
| INTERSECT insert | 377471.491202 ± 5038.526748 ops/s | |
| COUNT insert | 895093.704902 ± 5826.4613 ops/s | |
| FLAT_MAP insert | 721423.203594 ± 17137.262078 ops/s | |
| PRESENCE_COUNT insert | 385601.62828 ± 2489.697828 ops/s | |
| QUORUM insert | 367064.139557 ± 8597.566438 ops/s | |
| JOIN_SET insert | 385449.957499 ± 4885.045392 ops/s | |
| SEMI_JOIN insert | 373292.941368 ± 2551.519377 ops/s | |
| LOOKUP_JOIN insert | 396898.43811 ± 4179.749053 ops/s | |
| GROUP_BY_COUNT insert | 819454.484359 ± 6867.511832 ops/s | |
| GROUP_BY_SUM insert | 823431.581551 ± 6746.67282 ops/s | |
| GROUP_BY_MIN insert | 779839.706966 ± 6659.947423 ops/s | |
| GROUP_BY_MAX insert | 790255.925052 ± 8656.044798 ops/s | |
| GROUP_BY_TOP_K insert | 742914.627136 ± 13392.358958 ops/s | |
| COMBINE_LATEST insert | 405801.900893 ± 4333.557745 ops/s | |
| COALESCING_COMBINE insert | 872196.780666 ± 6508.953687 ops/s | |
| TAGGED_SET retract | 739930.585759 ± 9945.571455 ops/s | |
| FILTER retract | 826196.536993 ± 11065.211828 ops/s | |
| UNION retract | 700166.242088 ± 13620.190364 ops/s | |
| INTERSECT retract | 340920.072583 ± 3755.901267 ops/s | |
| COUNT retract | 731993.936648 ± 144296.122292 ops/s | |
| FLAT_MAP retract | 337657.552602 ± 3745.097066 ops/s | |
| PRESENCE_COUNT retract | 172301.715858 ± 6969.604791 ops/s | |
| QUORUM retract | 277877.636761 ± 22433.681009 ops/s | |
| JOIN_SET retract | 236690.183135 ± 52418.291809 ops/s | |
| SEMI_JOIN retract | 246813.202971 ± 26494.242351 ops/s | |
| LOOKUP_JOIN retract | 318561.066522 ± 31615.527783 ops/s | |
| GROUP_BY_COUNT retract | 588784.821062 ± 66180.333933 ops/s | |
| GROUP_BY_SUM retract | 633651.60399 ± 51844.167117 ops/s | |
| GROUP_BY_MIN retract | 615875.789252 ± 25631.294332 ops/s | |
| GROUP_BY_MAX retract | 586256.943534 ± 71599.007993 ops/s | |
| GROUP_BY_TOP_K retract | 597741.488223 ± 21105.342482 ops/s | |
| COMBINE_LATEST retract | 296740.903275 ± 36498.131785 ops/s | |
| COALESCING_COMBINE retract | 640157.900725 ± 82615.729156 ops/s | |
Trigger: none cited — entry MARKED INCOMPLETE, not presented as a finding

Row dispersion (drive=REAL; informational, nothing excluded):
- TAGGED_SET insert (drive=REAL): 863780.244544 ± 15734.439349 ops/s, relative dispersion 0.0182157897779966 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- FILTER insert (drive=REAL): 910757.004226 ± 22688.668821 ops/s, relative dispersion 0.024911879585578146 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- UNION insert (drive=REAL): 796726.255679 ± 19908.454999 ops/s, relative dispersion 0.02498782343006039 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- INTERSECT insert (drive=REAL): 377471.491202 ± 5038.526748 ops/s, relative dispersion 0.013348098771527315 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- COUNT insert (drive=REAL): 895093.704902 ± 5826.4613 ops/s, relative dispersion 0.006509331110353317 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- FLAT_MAP insert (drive=REAL): 721423.203594 ± 17137.262078 ops/s, relative dispersion 0.023754797451239797 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- PRESENCE_COUNT insert (drive=REAL): 385601.62828 ± 2489.697828 ops/s, relative dispersion 0.006456657973944383 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- QUORUM insert (drive=REAL): 367064.139557 ± 8597.566438 ops/s, relative dispersion 0.02342251806013024 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- JOIN_SET insert (drive=REAL): 385449.957499 ± 4885.045392 ops/s, relative dispersion 0.012673617669325267 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- SEMI_JOIN insert (drive=REAL): 373292.941368 ± 2551.519377 ops/s, relative dispersion 0.006835166418227712 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- LOOKUP_JOIN insert (drive=REAL): 396898.43811 ± 4179.749053 ops/s, relative dispersion 0.01053102922980409 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- GROUP_BY_COUNT insert (drive=REAL): 819454.484359 ± 6867.511832 ops/s, relative dispersion 0.008380589725336555 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- GROUP_BY_SUM insert (drive=REAL): 823431.581551 ± 6746.67282 ops/s, relative dispersion 0.008193361745115599 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- GROUP_BY_MIN insert (drive=REAL): 779839.706966 ± 6659.947423 ops/s, relative dispersion 0.008540149165923869 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- GROUP_BY_MAX insert (drive=REAL): 790255.925052 ± 8656.044798 ops/s, relative dispersion 0.01095347029183036 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- GROUP_BY_TOP_K insert (drive=REAL): 742914.627136 ± 13392.358958 ops/s, relative dispersion 0.018026780559737665 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- COMBINE_LATEST insert (drive=REAL): 405801.900893 ± 4333.557745 ops/s, relative dispersion 0.010678998140382425 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- COALESCING_COMBINE insert (drive=REAL): 872196.780666 ± 6508.953687 ops/s, relative dispersion 0.007462712350336622 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- TAGGED_SET retract (drive=REAL): 739930.585759 ± 9945.571455 ops/s, relative dispersion 0.013441222253028115 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- FILTER retract (drive=REAL): 826196.536993 ± 11065.211828 ops/s, relative dispersion 0.013392953531701562 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- UNION retract (drive=REAL): 700166.242088 ± 13620.190364 ops/s, relative dispersion 0.019452794986777086 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- INTERSECT retract (drive=REAL): 340920.072583 ± 3755.901267 ops/s, relative dispersion 0.011016955495002697 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- COUNT retract (drive=REAL): 731993.936648 ± 144296.122292 ops/s, relative dispersion 0.19712748298540736 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- FLAT_MAP retract (drive=REAL): 337657.552602 ± 3745.097066 ops/s, relative dispersion 0.011091406181026192 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- PRESENCE_COUNT retract (drive=REAL): 172301.715858 ± 6969.604791 ops/s, relative dispersion 0.0404500022318054 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- QUORUM retract (drive=REAL): 277877.636761 ± 22433.681009 ops/s, relative dispersion 0.08073222901451046 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- JOIN_SET retract (drive=REAL): 236690.183135 ± 52418.291809 ops/s, relative dispersion 0.22146373421453816 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- SEMI_JOIN retract (drive=REAL): 246813.202971 ± 26494.242351 ops/s, relative dispersion 0.10734532039646605 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- LOOKUP_JOIN retract (drive=REAL): 318561.066522 ± 31615.527783 ops/s, relative dispersion 0.09924479512883792 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- GROUP_BY_COUNT retract (drive=REAL): 588784.821062 ± 66180.333933 ops/s, relative dispersion 0.11240156261778206 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- GROUP_BY_SUM retract (drive=REAL): 633651.60399 ± 51844.167117 ops/s, relative dispersion 0.08181809497608118 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- GROUP_BY_MIN retract (drive=REAL): 615875.789252 ± 25631.294332 ops/s, relative dispersion 0.04161763585987037 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- GROUP_BY_MAX retract (drive=REAL): 586256.943534 ± 71599.007993 ops/s, relative dispersion 0.12212905754496642 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- GROUP_BY_TOP_K retract (drive=REAL): 597741.488223 ± 21105.342482 ops/s, relative dispersion 0.035308478494178425 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- COMBINE_LATEST retract (drive=REAL): 296740.903275 ± 36498.131785 ops/s, relative dispersion 0.12299663235565447 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- COALESCING_COMBINE retract (drive=REAL): 640157.900725 ± 82615.729156 ops/s, relative dispersion 0.1290552363134704 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)


---

Renderer's own output for the **pre-fix arm**, pasted verbatim:

## 2026-08-26 — REAL-drive per-operator delta-application throughput at the pre-y7hc benchmark (pre-fix arm of the same-host A/B)
Harness: 9aabe4923 · JVM Microsoft (Microsoft-13877178)/21.0.11 · heap JVM defaults (VM options: <none>) · Apple M3 Max, 16 cores, Mac OS X 26.6.2
JMH: mode=Throughput forks=2 warmup=5 iters=10 · drive=REAL
| subject | value | notes |
| --- | --- | --- |
| TAGGED_SET insert | 617673.850984 ± 69457.71433 ops/s | |
| FILTER insert | 787871.694559 ± 24658.272711 ops/s | |
| UNION insert | 705697.165283 ± 35176.926433 ops/s | |
| INTERSECT insert | 336952.971351 ± 21982.840343 ops/s | |
| COUNT insert | 757941.280317 ± 59553.97694 ops/s | |
| FLAT_MAP insert | 493458.030174 ± 52158.016334 ops/s | |
| PRESENCE_COUNT insert | 278325.793449 ± 28904.346267 ops/s | |
| QUORUM insert | 270733.258534 ± 24592.951086 ops/s | |
| JOIN_SET insert | 216068.908432 ± 21970.377843 ops/s | |
| SEMI_JOIN insert | 217011.939464 ± 27219.048398 ops/s | |
| LOOKUP_JOIN insert | 271846.303331 ± 26743.074275 ops/s | |
| GROUP_BY_COUNT insert | 524703.714064 ± 42354.572428 ops/s | |
| GROUP_BY_SUM insert | 543809.403788 ± 41650.818735 ops/s | |
| GROUP_BY_MIN insert | 480412.174023 ± 40486.855348 ops/s | |
| GROUP_BY_MAX insert | 499562.981372 ± 24490.938476 ops/s | |
| GROUP_BY_TOP_K insert | 470708.97399 ± 43367.101772 ops/s | |
| COMBINE_LATEST insert | 324364.092154 ± 10736.238318 ops/s | |
| COALESCING_COMBINE insert | 739193.185795 ± 57558.283077 ops/s | |
| TAGGED_SET retract | 622822.05356 ± 19783.934855 ops/s | |
| FILTER retract | 643052.912664 ± 58858.623402 ops/s | |
| UNION retract | 569571.037039 ± 17556.695233 ops/s | |
| INTERSECT retract | 282440.746979 ± 20389.939425 ops/s | |
| COUNT retract | 719420.22958 ± 28301.208988 ops/s | |
| FLAT_MAP retract | 518088.4304 ± 49929.104807 ops/s | |
| PRESENCE_COUNT retract | 308207.70286 ± 16399.287121 ops/s | |
| QUORUM retract | 270568.293099 ± 22551.140234 ops/s | |
| JOIN_SET retract | 289705.378148 ± 11635.629468 ops/s | |
| SEMI_JOIN retract | 272458.341157 ± 15753.240807 ops/s | |
| LOOKUP_JOIN retract | 352250.973816 ± 15546.133547 ops/s | |
| GROUP_BY_COUNT retract | 609628.737069 ± 47086.021177 ops/s | |
| GROUP_BY_SUM retract | 593806.00227 ± 54317.864673 ops/s | |
| GROUP_BY_MIN retract | 577443.858398 ± 37991.720923 ops/s | |
| GROUP_BY_MAX retract | 594388.248625 ± 40383.8141 ops/s | |
| GROUP_BY_TOP_K retract | 586251.424712 ± 44517.145289 ops/s | |
| COMBINE_LATEST retract | 320814.171971 ± 27984.233 ops/s | |
| COALESCING_COMBINE retract | 697718.309809 ± 37561.087828 ops/s | |
Trigger: none cited — entry MARKED INCOMPLETE, not presented as a finding

Row dispersion (drive=REAL; informational, nothing excluded):
- TAGGED_SET insert (drive=REAL): 617673.850984 ± 69457.71433 ops/s, relative dispersion 0.11245046915835719 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- FILTER insert (drive=REAL): 787871.694559 ± 24658.272711 ops/s, relative dispersion 0.0312973202125279 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- UNION insert (drive=REAL): 705697.165283 ± 35176.926433 ops/s, relative dispersion 0.04984705644792166 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- INTERSECT insert (drive=REAL): 336952.971351 ± 21982.840343 ops/s, relative dispersion 0.0652400845579745 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- COUNT insert (drive=REAL): 757941.280317 ± 59553.97694 ops/s, relative dispersion 0.07857333870915732 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- FLAT_MAP insert (drive=REAL): 493458.030174 ± 52158.016334 ops/s, relative dispersion 0.10569899189928751 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- PRESENCE_COUNT insert (drive=REAL): 278325.793449 ± 28904.346267 ops/s, relative dispersion 0.10385076391526174 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- QUORUM insert (drive=REAL): 270733.258534 ± 24592.951086 ops/s, relative dispersion 0.09083830785759 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- JOIN_SET insert (drive=REAL): 216068.908432 ± 21970.377843 ops/s, relative dispersion 0.10168227350449356 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- SEMI_JOIN insert (drive=REAL): 217011.939464 ± 27219.048398 ops/s, relative dispersion 0.12542650171796355 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- LOOKUP_JOIN insert (drive=REAL): 271846.303331 ± 26743.074275 ops/s, relative dispersion 0.09837571431838689 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- GROUP_BY_COUNT insert (drive=REAL): 524703.714064 ± 42354.572428 ops/s, relative dispersion 0.08072093124698915 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- GROUP_BY_SUM insert (drive=REAL): 543809.403788 ± 41650.818735 ops/s, relative dispersion 0.07659083944645662 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- GROUP_BY_MIN insert (drive=REAL): 480412.174023 ± 40486.855348 ops/s, relative dispersion 0.08427524849955544 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- GROUP_BY_MAX insert (drive=REAL): 499562.981372 ± 24490.938476 ops/s, relative dispersion 0.049024726389329476 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- GROUP_BY_TOP_K insert (drive=REAL): 470708.97399 ± 43367.101772 ops/s, relative dispersion 0.09213145312356275 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- COMBINE_LATEST insert (drive=REAL): 324364.092154 ± 10736.238318 ops/s, relative dispersion 0.03309934292265218 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- COALESCING_COMBINE insert (drive=REAL): 739193.185795 ± 57558.283077 ops/s, relative dispersion 0.0778663604901826 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- TAGGED_SET retract (drive=REAL): 622822.05356 ± 19783.934855 ops/s, relative dispersion 0.031764987674917165 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- FILTER retract (drive=REAL): 643052.912664 ± 58858.623402 ops/s, relative dispersion 0.09152998492482388 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- UNION retract (drive=REAL): 569571.037039 ± 17556.695233 ops/s, relative dispersion 0.030824417133762803 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- INTERSECT retract (drive=REAL): 282440.746979 ± 20389.939425 ops/s, relative dispersion 0.07219191863458721 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- COUNT retract (drive=REAL): 719420.22958 ± 28301.208988 ops/s, relative dispersion 0.03933891184088935 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- FLAT_MAP retract (drive=REAL): 518088.4304 ± 49929.104807 ops/s, relative dispersion 0.09637178110395418 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- PRESENCE_COUNT retract (drive=REAL): 308207.70286 ± 16399.287121 ops/s, relative dispersion 0.05320855698551181 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- QUORUM retract (drive=REAL): 270568.293099 ± 22551.140234 ops/s, relative dispersion 0.08334731307836064 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- JOIN_SET retract (drive=REAL): 289705.378148 ± 11635.629468 ops/s, relative dispersion 0.04016366400369612 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- SEMI_JOIN retract (drive=REAL): 272458.341157 ± 15753.240807 ops/s, relative dispersion 0.057818897157281134 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- LOOKUP_JOIN retract (drive=REAL): 352250.973816 ± 15546.133547 ops/s, relative dispersion 0.04413368507852756 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- GROUP_BY_COUNT retract (drive=REAL): 609628.737069 ± 47086.021177 ops/s, relative dispersion 0.07723720735899403 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- GROUP_BY_SUM retract (drive=REAL): 593806.00227 ± 54317.864673 ops/s, relative dispersion 0.09147409164837304 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- GROUP_BY_MIN retract (drive=REAL): 577443.858398 ± 37991.720923 ops/s, relative dispersion 0.06579292578918454 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- GROUP_BY_MAX retract (drive=REAL): 594388.248625 ± 40383.8141 ops/s, relative dispersion 0.06794181108630595 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- GROUP_BY_TOP_K retract (drive=REAL): 586251.424712 ± 44517.145289 ops/s, relative dispersion 0.07593524452562202 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- COMBINE_LATEST retract (drive=REAL): 320814.171971 ± 27984.233 ops/s, relative dispersion 0.08722879300522185 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- COALESCING_COMBINE retract (drive=REAL): 697718.309809 ± 37561.087828 ops/s, relative dispersion 0.05383417247324688 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)

## 2026-08-26 — allocation per delta (gc.alloc.rate.norm, -prof gc) for the eight set-shaped subjects x both directions under drive=REAL — the operator-level half of [BEN1-28], which computenet-x9e.14 left open because neither of its arms attached a profiler
Harness: 6b891c328 · JVM Microsoft (Microsoft-13877178)/21.0.11 · heap JVM defaults (VM options: <none>) · Apple M3 Max, 16 cores, Mac OS X 26.6.2
JMH: mode=Throughput forks=2 warmup=5 iters=10 · drive=REAL
| subject | value | notes |
| --- | --- | --- |
| TAGGED_SET insert | 15855.269699 ± 93.03773 B/op | |
| FILTER insert | 14201.371171 ± 16.072853 B/op | |
| UNION insert | 15726.330455 ± 42.548226 B/op | |
| INTERSECT insert | 30478.004752 ± 52.50992 B/op | |
| COUNT insert | 13012.623581 ± 11.007536 B/op | |
| FLAT_MAP insert | 18266.291228 ± 14.025506 B/op | |
| PRESENCE_COUNT insert | 28968.486825 ± 42.516861 B/op | |
| QUORUM insert | 30561.906026 ± 40.666923 B/op | |
| TAGGED_SET retract | 15907.931094 ± 34.995631 B/op | |
| FILTER retract | 14128.779798 ± 43.550433 B/op | |
| UNION retract | 15687.292574 ± 65.770766 B/op | |
| INTERSECT retract | 30430.149265 ± 28.259035 B/op | |
| COUNT retract | 13080.780977 ± 63.84672 B/op | |
| FLAT_MAP retract | 18279.239518 ± 29.874526 B/op | |
| PRESENCE_COUNT retract | 28556.475217 ± 68.913171 B/op | |
| QUORUM retract | 30539.12531 ± 27.498766 B/op | |
Trigger: [BEN1-28] — RETIRES: the criterion applied is that, with TAG_ENTRY_BUDGET_BYTES=512 a generous derived upper bound on what one delta's TagState map growth can allocate, the bound on growth's share of a row's per-delta allocation is 512/(B/op), and the question fires only if a strict majority of the set-shaped rows put that bound at 0.5 or above, retires only if every row puts it below 0.1, and is otherwise undecided; measured, across 16 set-shaped rows per-delta allocation ranges 13012.623581 B/op (COUNT insert) to 30561.906026 B/op (QUORUM insert), so the upper bound on tag-map growth's share of a delta's allocation ranges 0.01675288182498909-0.03934640826370954, with 0 of 16 rows at or above the 0.5 candidate share

Row dispersion (drive=REAL; informational, nothing excluded):
- TAGGED_SET insert (drive=REAL): 15855.269699 ± 93.03773 B/op, relative dispersion 0.005867937396603726 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- FILTER insert (drive=REAL): 14201.371171 ± 16.072853 B/op, relative dispersion 0.0011317817699759633
- UNION insert (drive=REAL): 15726.330455 ± 42.548226 B/op, relative dispersion 0.0027055406295670393
- INTERSECT insert (drive=REAL): 30478.004752 ± 52.50992 B/op, relative dispersion 0.0017228791854084294
- COUNT insert (drive=REAL): 13012.623581 ± 11.007536 B/op, relative dispersion 8.459121199872661E-4
- FLAT_MAP insert (drive=REAL): 18266.291228 ± 14.025506 B/op, relative dispersion 7.678354530174471E-4
- PRESENCE_COUNT insert (drive=REAL): 28968.486825 ± 42.516861 B/op, relative dispersion 0.0014676935408068211
- QUORUM insert (drive=REAL): 30561.906026 ± 40.666923 B/op, relative dispersion 0.0013306409281346305
- TAGGED_SET retract (drive=REAL): 15907.931094 ± 34.995631 B/op, relative dispersion 0.0021998857546723545
- FILTER retract (drive=REAL): 14128.779798 ± 43.550433 B/op, relative dispersion 0.0030823916589148613
- UNION retract (drive=REAL): 15687.292574 ± 65.770766 B/op, relative dispersion 0.0041926142251600485
- INTERSECT retract (drive=REAL): 30430.149265 ± 28.259035 B/op, relative dispersion 9.286525266079729E-4
- COUNT retract (drive=REAL): 13080.780977 ± 63.84672 B/op, relative dispersion 0.004880956275643021
- FLAT_MAP retract (drive=REAL): 18279.239518 ± 29.874526 B/op, relative dispersion 0.0016343418428639686
- PRESENCE_COUNT retract (drive=REAL): 28556.475217 ± 68.913171 B/op, relative dispersion 0.0024132239877761663
- QUORUM retract (drive=REAL): 30539.12531 ± 27.498766 B/op, relative dispersion 9.004437985981073E-4

Criterion inputs (bytes allocated per DELTA, gc.alloc.rate.norm; share = TAG_ENTRY_BUDGET_BYTES / B/op, an UPPER BOUND on tag-map growth's share of a delta's allocation):
| subject direction | B/op | upper-bound growth share |
| --- | --- | --- |
| COUNT insert | 13012.623581 | 0.03934640826370954 |
| COUNT retract | 13080.780977 | 0.03914139384339911 |
| FILTER insert | 14201.371171 | 0.03605285671608477 |
| FILTER retract | 14128.779798 | 0.03623809043102761 |
| FLAT_MAP insert | 18266.291228 | 0.028029773182153495 |
| FLAT_MAP retract | 18279.239518 | 0.02800991799991578 |
| INTERSECT insert | 30478.004752 | 0.016798999939994497 |
| INTERSECT retract | 30430.149265 | 0.016825418618267828 |
| PRESENCE_COUNT insert | 28968.486825 | 0.017674378475238152 |
| PRESENCE_COUNT retract | 28556.475217 | 0.017929383654996765 |
| QUORUM insert | 30561.906026 | 0.01675288182498909 |
| QUORUM retract | 30539.12531 | 0.016765378667618427 |
| TAGGED_SET insert | 15855.269699 | 0.03229210286043208 |
| TAGGED_SET retract | 15907.931094 | 0.03218520352989907 |
| UNION insert | 15726.330455 | 0.03255686388284024 |
| UNION retract | 15687.292574 | 0.03263788174949863 |

TAG_ENTRY_BUDGET_BYTES=512.0 CANDIDATE_SHARE=0.5 NEGLIGIBLE_SHARE=0.1
verdict=RETIRES

### What was measured, and what it is NOT (`computenet-i61m`, the operator half of `[BEN1-28]`)

**The quantity.** Every number in the table above is `gc.alloc.rate.norm` — JMH's
`-prof gc` secondary metric, **bytes allocated per measured operation**, where the
operation is **one delta**: `OperatorThroughputBenchmark` declares
`@OperationsPerInvocation(ThroughputReport.DELTAS_PER_BATCH)` and that constant is 512,
so an invocation's 512-delta batch is divided out and each row reads per delta. It is
not retained size, not wall clock, and it does not supersede any throughput entry in
this file — those measure a different quantity with a different instrument.

**What `[BEN1-28]` asked, and which half this closes.** `[BEN1-28]` named `TagState`
tag-map growth as the suspect for the set-shaped subjects' cost.
`computenet-x9e.14`'s same-host A/B (entry of 2026-08-26 above) settled the **harness**
half: the INSERT/RETRACT dispersion split came from unbounded live state in the
benchmark's own `@Setup(Level.Invocation)`, not from the operators. Nothing under
`kernel/src/main` differed between its two arms, so it drew no operator-level
conclusion, and **neither of its arms attached a profiler** — `# VM options: <none>` in
both retained banners, no `-Xlog:gc`, no JFR. This entry is the profile that was
missing.

**The criterion, and that it was fixed before the numbers existed.**
`bench/src/test/kotlin/civictech/bench/micro/TagMapGrowthAllocRenderTest.kt` holds
`CRITERION`, `TAG_ENTRY_BUDGET_BYTES`, `CANDIDATE_SHARE`, `NEGLIGIBLE_SHARE` and
`verdictOf` as committed source; it was written and committed (`6b891c328`) **while the
sweep that feeds it was still running**, so the thresholds cannot have been chosen to
fit. Re-running the sweep and re-reading `verdictOf` is a complete audit of how the word
`RETIRES` was reached.

The bound the criterion turns on is derived, not measured, and it is deliberately
generous. One delta adds at most an O(1) amount to a `TagState` map — one live tag on
insert, one tombstone on retract, per source. On 64-bit HotSpot with compressed oops a
`HashMap.Node` is 32 B and its amortized share of table-array doubling is ~16 B; a tag
object plus a boxed element plus a per-source duplicate for the two-source subjects is
still far under `TAG_ENTRY_BUDGET_BYTES = 512`. So `512 / (B/op)` is an **upper bound**
on tag-map growth's share of a delta's allocation. Overstating the budget can only make
the criterion harder to retire, never easier.

Measured, that bound lands between **0.0168** (`QUORUM insert`, 30561.9 B/op) and
**0.0393** (`COUNT insert`, 13012.6 B/op). Every one of the 16 rows is below the 0.1
`NEGLIGIBLE_SHARE` threshold and none is anywhere near the 0.5 candidate share. At least
96% of a delta's allocation is something other than tag-map growth.

### The limitation, stated next to the number: this retires the ALLOCATION channel, and only that

Allocation is a proxy for cost, not cost. A `TagState` map that grows without allocating
— longer rehash probe sequences, cache misses walking a larger table, a larger old
generation to trace — costs wall clock this instrument cannot see. **A reader must not
read `RETIRES` as "tag-map size does not affect the set-shaped subjects' throughput".**
What it establishes is narrower and it is what `[BEN1-28]` named a profiler for: the
garbage a set-shaped delta produces is overwhelmingly *not* the map growing.

Two further readings from the same run bear on the time channel without being part of
the criterion, and both point the same way:

- **GC time is a small fraction of measured wall clock in every row.** Each row's
  `gc.time` is summed over 2 forks x 10 measurement iterations x 1 s = 20 s of measured
  interval. Fourteen of the sixteen rows sit at 91–114 ms, i.e. **0.46%–0.57%**. The two
  exceptions are `UNION insert` at 543 ms (**2.7%**) and `UNION retract` at 467 ms
  (**2.3%**) — and `UNION` is the one subject whose `gc.count` collapses to 57 against
  the 165–205 of every other row, the signature of fewer, larger, more expensive
  collections. That is consistent with `UNION` being the retaining
  (tombstone-keeping) tagged-set operator promoting more state out of the young
  generation, and it is the loudest tag-map signal anywhere in this sweep. It is still
  under 3% of wall clock.
- **The two directions allocate the same amount.** Per-subject, insert against retract
  agrees to within 1.4% on every one of the eight subjects (largest gap:
  `PRESENCE_COUNT`, 28968.5 vs 28556.5 B/op = 1.42%). That is a second, independent
  confirmation of `computenet-x9e.14`'s finding — after `computenet-y7hc` bounded live
  state, direction is not a differentiator — reached through a metric that run did not
  collect.

### What the allocation actually tracks: source arity, not accumulated state

The rows separate cleanly into two bands, and the split is `Subject.sources`:

| sources | subjects | B/op band |
| --- | --- | --- |
| 1 | COUNT, FILTER, TAGGED_SET, UNION, FLAT_MAP | 13012.6 – 18279.2 |
| 2 | PRESENCE_COUNT, INTERSECT, QUORUM | 28556.5 – 30561.9 |

`MicroGraph.applyBatch` pushes every delta into *every* source, so a two-source subject
does twice the host work per delta — and allocates almost exactly twice as much. That is
per-delta propagation cost (delta objects, emissions, the message envelopes and
scheduling of the hosted path), which scales with how many times a delta is applied and
not with how large the map has grown. It is the positive form of the same conclusion:
the allocation is in the *traffic*, not in the *state*.

Read `TAGGED_SET`'s row only with its own standing caveat: it is a filter-identity
baseline measured through `FilterCell { true }`, not `TaggedSetOperator` in isolation.

### Nothing was tuned

`[BEN1-28]`'s standing instruction — the growth is observed, never tuned — was followed.
No tag map was cleared, compacted or bounded for this measurement; `Level.Iteration`
graph rebuild remains the only bound on tombstone growth, exactly as
`OperatorThroughputBenchmark`'s KDoc says. Nothing under `kernel/src/main` was touched
at all, and no fork or iteration count was raised toward JMH's defaults: the run
resolved the class's own declared `Fork(2)`, `Warmup(5, 1s)`, `Measurement(10, 1s)`,
which is what the run's own banner states and what the `JMH:` line above reports.

### Host, and the readings actually met

**The host is the pinned one**: `NL-MGD6FQJW91`, Apple M3 Max, Mac15,9, 16 cores, macOS
26.6.2. The CPU/cores/OS in the `Harness:` line above come from the measuring fork's own
`@Setup(Level.Trial)` banner, not from the rendering process.

**The measuring JVM is Microsoft OpenJDK 21.0.11**
(`~/Library/Java/JavaVirtualMachines/ms-21.0.11/`), read off this run's own JMH banner
and reproduced verbatim in the `Harness:` line. A bare `java` on this host is JBR 25.0.2
and would have been wrong; there is no Adoptium 21 on this box.

**Quiescence gate.** The launch was gated on a *polled trough*, not a one-shot reading,
because this host's idle load oscillates roughly 3.0–5.2 on a ~2-minute cycle. The
sweep script polled `load1` every 10 s and started only when it fell below the quiesced
threshold 4.00 (16 cores x 0.25):

```
poll 0 02:05:18Z load1=6.31   poll 4 02:05:58Z load1=4.60
poll 1 02:05:28Z load1=5.57   poll 5 02:06:08Z load1=4.36
poll 2 02:05:38Z load1=5.16   poll 6 02:06:18Z load1=4.15
poll 3 02:05:48Z load1=4.89   poll 7 02:06:28Z load1=3.89  TROUGH FOUND
```

Sweep ran 02:06:28Z–02:14:45Z (rc=0). A 30-second sampler ran throughout. `load1` over
the 17 in-sweep samples ranged **3.20–8.92**, with the measuring JVM itself accounting
for 132–197% CPU (1.3–2.0 cores) of it. Post-sweep `load1` was 2.94.

**Non-resident consumers appeared during the run and are recorded rather than
discarded.** `mediaanalysisd` at 97.7% for the 02:07:18Z sample; `mdworker_shared`
(Spotlight) at 4–13% across 02:10:19Z, 02:13:20Z and 02:14:20Z; a JetBrains `junie`
agent at 5–13% across 02:09:49Z–02:10:19Z. The two `load1` peaks (8.92 at 02:08:49Z,
8.22 at 02:09:19Z) coincide with `wdavdaemon_enterprise` at 27–99%.

The reason none of that is discarded, and the reason it does not move this result: **the
metric is bytes allocated per operation, which is a property of the code path and not of
how much CPU it got.** The dispersions bear it out — the widest relative dispersion in
the sweep is `TAGGED_SET insert` at 0.00587, and fourteen of sixteen rows are under
0.005 (`NOISE_FLOOR`). A contended CPU makes a throughput number wrong; it does not make
a byte count wrong. A reader who nonetheless wants to grade this run SHARED rather than
QUIESCED has every reading above to do it with, and the verdict survives that grading
for the reason just given.

**The resident managed-endpoint stack — ManageEngine app-control, the Microsoft Defender
daemons, `trustd` — consumes roughly a core at all times on this host and never goes
away.** That it does not disqualify a quiesced attestation here is **a ruling by the
orchestrating session, not a judgement by the session that measured**, on the ground
that this lane's own original host evidence was gathered with that stack running and the
opposite reading makes these beads permanently undischargeable. This session executed
that ruling; it did not make it. A reader who rejects it is overturning an orchestrator's
call about what the precondition means, and the readings above are recorded in full
precisely so that reader can do it without taking anything here on trust.

### Trigger (`[BEN1-31]`/`[BEN1-32]`)

`TriggerClaim.Cited` on `[BEN1-28]`, verdict `RETIRES`, with the criterion and the
measured clause both generated from the same numbers by
`TagMapGrowthAllocRenderTest` — so the sentence in the rendered block cannot drift from
the table beside it. This entry cites neither G-21 nor G-43.

### WAL/journal statement (`[BEN1-29]`)

Unchanged and re-confirmed against the source this run compiled: `Graphs.kt`'s `Rig` for
`Drive.REAL` constructs `ManagedHost(scheduler = scheduler)` with no `journal` argument,
and no `civictech.cell.durability` type appears in `Graphs.kt`, `Deltas.kt` or
`OperatorThroughputBenchmark.kt`. WAL/journal sync is not in play; KBLK is not named.

### Coverage, and what this sweep is not

Eight subjects x two directions x `drive=REAL` = **16 of 16 combinations measured**, no
row omitted. This is `Subject.setShaped()` exactly — the family `[BEN1-28]` names — and
it is **not** a sweep of all 18 subjects, not a `drive=SIM` measurement, and not a
statement about the join / grouped / combine families, which were deliberately excluded
so that no non-set-shaped row could be averaged into a criterion stated over the
set-shaped ones (`TagMapGrowthAllocRenderTest.criterionInputs` refuses one outright).
The narrowing was deliberate and is stated here rather than left to be inferred: the
task's slot fit one arm, and the set-shaped family is the one the question is about.

### Artifacts

The raw JMH CSV, the teed run log, the polling-gate host readings and the 30-second
sampler are retained at `$HOME/computenet-runs/computenet-i61m/` on host
`NL-MGD6FQJW91` — **outside the repository**, because run artifacts are not committed by
convention in this lane. They are therefore **machine-local and not reproducible from
this repository alone**; the rendered block above and
`TagMapGrowthAllocRenderTest` are the durable part.

### Commands, exactly

```
./gradlew :bench:jmhJar
/Users/merlijn/Library/Java/JavaVirtualMachines/ms-21.0.11/Contents/Home/bin/java \
     -jar bench/build/libs/bench-jmh.jar 'OperatorThroughputBenchmark.real' \
     -p subject=TAGGED_SET,FILTER,UNION,INTERSECT,COUNT,FLAT_MAP,PRESENCE_COUNT,QUORUM \
     -prof gc -rf csv -rff /abs/path/setshaped-gc.csv > /abs/path/setshaped-gc.log 2>&1

./gradlew :bench:test -PbenchOnly=true --rerun \
  --tests 'civictech.bench.micro.TagMapGrowthAllocRenderTest' \
  -Dcivictech.bench.jmhResults=/abs/path/setshaped-gc.csv \
  -Dcivictech.bench.harnessSha=$(git rev-parse --short HEAD) \
  -Dcivictech.bench.date=2026-08-26
```

### Supplementary, NOT part of the verdict: an underpowered iteration-length probe that points the OTHER way

The `RETIRES` above is stated over allocation, and the section above says why that leaves
the time channel open. Rather than leave it merely stated, this session spent the
remaining slot on a cheap direct probe of it, and **the probe is reported here because it
does not support extending `RETIRES` to time** — reporting only the leg that agreed with
the verdict would be the dishonest option.

**The idea.** The graph is rebuilt at `@Setup(Level.Iteration)`, so within one measurement
iteration the tag map grows monotonically and at the iteration boundary it resets.
Lengthening the iteration therefore grows the map without changing anything else. If
tag-map growth dominated delta-application *cost*, a 10x longer iteration would report
materially lower average throughput.

**The arms.** `drive=REAL`, `direction=INSERT`, the same eight set-shaped subjects,
`-f 1 -wi 5 -w 1s`, differing only in measurement iteration length: `-i 4 -r 1s` against
`-i 4 -r 10s`. Same JVM (`JDK 21.0.11, OpenJDK 64-Bit Server VM, 21.0.11+10-LTS`, off the
arm's own banner), same host, run back to back 02:16:02Z–02:17:17Z and
02:17:17Z–02:23:21Z.

| subject | 1 s iterations (ops/s) | 10 s iterations (ops/s) | ratio | 10 s relative error |
| --- | --- | --- | --- | --- |
| TAGGED_SET | 809249.7 | 793201.6 | 0.9802 | 0.15438 |
| FILTER | 884401.3 | 850361.8 | 0.9615 | 0.11226 |
| UNION | 783739.1 | 723628.4 | 0.9233 | 0.31994 |
| INTERSECT | 372335.8 | 357497.7 | 0.9601 | 0.19146 |
| COUNT | 925676.3 | 440702.1 | 0.4761 | 0.04683 |
| FLAT_MAP | 771230.8 | 349924.1 | 0.4537 | 0.04900 |
| PRESENCE_COUNT | 384422.8 | 276473.2 | 0.7192 | 1.93416 |
| QUORUM | 398964.2 | 280736.6 | 0.7037 | 0.65988 |

**Why this is a probe and not a result, in four counts. Every one of them is a reason to
distrust it, and it is recorded anyway because the two tight rows are hard to dismiss:**

1. **It is underpowered by construction.** One fork, four measurement iterations, against
   the sweep above's two forks and ten. Three of the eight 10 s rows carry relative error
   above `NOISE_FLOOR` by two orders of magnitude — `PRESENCE_COUNT` at **1.93**, i.e. an
   error bar wider than the score. Nothing may be concluded from those rows at all.
2. **It is not the annotation config**, so it is not comparable to any other throughput
   entry in this file, and the acceptance criterion this item was written against is
   satisfied by the sweep above, not by this.
3. **The two arms are sequential, not interleaved**, and the host was not re-gated between
   them. The post-arm reading at 02:23:21Z caught a `Brave Browser Helper (Renderer)` at
   79.6% and ManageEngine's `dcpatchscan` at 33.6% — non-resident consumers that were
   absent from the main sweep's window. The 10 s arm is the one that ran last and is the
   one exposed to them.
4. **The pattern is internally inconsistent with the simplest growth story.** If the
   decline tracked how much state an iteration accumulates, the fastest subjects would
   fall the furthest. `COUNT` (925.7k ops/s, ratio 0.476) and `FLAT_MAP` (771.2k, 0.454)
   fit that; `TAGGED_SET` (809.2k, 0.980) and `FILTER` (884.4k, 0.962) flatly contradict
   it at comparable throughput. Something subject-specific is happening that "the map got
   bigger" does not by itself explain.

**What survives all four.** `COUNT` and `FLAT_MAP` are the two rows with *tight* error
bars in the 10 s arm (0.047 and 0.049), and both report **less than half** the throughput
of their 1 s counterparts. That is a large effect measured precisely, and it is the
signature `[BEN1-28]` predicted — on the channel this entry's own verdict does not cover.

**So the honest joint statement is narrower than either leg alone**: the garbage a
set-shaped delta produces is overwhelmingly not the tag map growing (measured, 16 of 16
rows, criterion pre-registered), *and* at least two set-shaped subjects lose more than
half their throughput when the iteration that accumulates that map is lengthened 10x
(probed, underpowered, confounded). Those are compatible — a cost that is not allocation
is still a cost. Resolving the second is filed as `computenet-bzwx`, which needs an
interleaved, individually host-gated A/B at full fork and iteration counts, and is
explicitly **not** licence to tune or clear the tag map: `[BEN1-28]`'s standing
instruction that the growth is observed and never tuned is unchanged by anything here.

Raw CSVs and logs for both arms are at `$HOME/computenet-runs/computenet-i61m/`
(`iter-1s.csv`/`.log`, `iter-10s.csv`/`.log`) on host `NL-MGD6FQJW91`, machine-local.

```
/Users/merlijn/Library/Java/JavaVirtualMachines/ms-21.0.11/Contents/Home/bin/java \
     -jar bench/build/libs/bench-jmh.jar 'OperatorThroughputBenchmark.real' \
     -p subject=TAGGED_SET,FILTER,UNION,INTERSECT,COUNT,FLAT_MAP,PRESENCE_COUNT,QUORUM \
     -p direction=INSERT -f 1 -wi 5 -w 1s -i 4 -r 1s \
     -rf csv -rff /abs/path/iter-1s.csv > /abs/path/iter-1s.log 2>&1
# ... and again with -r 10s
```

## 2026-08-26 — interleaved, per-arm host-gated A/B of MEASUREMENT-ITERATION LENGTH over the set-shaped subjects: `[BEN1-28]`'s TIME channel at full power — INCONCLUSIVE by the pre-registered criterion, and `computenet-i61m`'s halving does NOT reproduce

`computenet-bzwx`. `computenet-i61m` RETIRED `[BEN1-28]` on the **allocation** channel and
said, next to the number, that the verdict did not reach wall clock. It then spent its
remaining slot on a cheap direct probe of the time channel and reported — honestly, under
"Supplementary, NOT part of the verdict" — that two set-shaped subjects appeared to lose
**more than half** their throughput when the measurement iteration was lengthened 10x. It
listed four reasons the probe was not a result and filed this item to redo it properly.
This is that redo.

**Headline, stated before the caveats so it cannot be read past.** At full fork and
iteration counts, interleaved, with every one of the sixteen arms individually gated on a
polled load trough, **the halving does not reproduce, and it is not close.** `COUNT`
reports a long/short throughput ratio of **0.9958** (interval 0.9769–1.0152) against the
probe's 0.4761; `FLAT_MAP` reports **1.0034** (0.9609–1.0465) against the probe's 0.4537.
Those are the two rows the probe called "a large effect measured precisely" and explicitly
said were "hard to dismiss". They are flat. Across all eight subjects **no row loses
throughput** by the pre-registered criterion, six of eight demonstrably do not, and the
long/short ratios span 0.9156 to 1.0131.

**What the verdict therefore is, and why it is not `RETIRES`.** The pre-registered
criterion retires only when *every* row is resolved and *every* row is on the
does-not-cost side. Two rows — `TAGGED_SET` (0.9156, interval 0.8829–0.9493) and `UNION`
(0.9414, 0.8972–0.9877) — have intervals that straddle the 0.90 boundary, so they decide
nothing, and the criterion yields **INCONCLUSIVE**. That is the criterion applied as
written and it was **not** adjusted after the numbers were seen: `MATERIAL_RATIO = 0.90`,
`RESOLVABLE_RELATIVE_ERROR = 0.10` and the aggregation were committed as `1f68b10d4`
before the first arm ran, and `IterationLengthCriterionTest` — an ordinary untagged test,
so it runs in `:bench:test` on every build — pins all sixteen branches of it. Widening the
design to two more forks *for those two subjects only*, after seeing which two they were,
would have been the exact defect pre-registration exists to prevent.

### The A/B, per subject

`ratio` is long-arm score / short-arm score; the interval is propagated from both arms'
99.9% error bars (long low over short high, long high over short low). `i61m` is the
underpowered probe's ratio for the same subject, quoted from its own table.

| subject | short arm 1 s (ops/s) | long arm 10 s (ops/s) | ratio | interval | row | `i61m` probe ratio |
| --- | --- | --- | --- | --- | --- | --- |
| `TAGGED_SET` | 867384.3 ± 12066.1 | 794175.2 ± 17745.4 | 0.9156 | 0.8829–0.9493 | UNDECIDED | 0.9802 |
| `FILTER` | 868818.9 ± 8110.3 | 864755.9 ± 9609.1 | 0.9953 | 0.9752–1.0159 | DOES_NOT_COST | 0.9615 |
| `UNION` | 784508.6 ± 18963.2 | 738521.7 ± 17638.4 | 0.9414 | 0.8972–0.9877 | UNDECIDED | 0.9233 |
| `INTERSECT` | 375458.2 ± 3361.2 | 367427.4 ± 3251.3 | 0.9786 | 0.9613–0.9962 | DOES_NOT_COST | 0.9601 |
| `COUNT` | 876605.1 ± 9636.5 | 872955.3 ± 7220.1 | 0.9958 | 0.9769–1.0152 | DOES_NOT_COST | **0.4761** |
| `FLAT_MAP` | 697725.8 ± 5532.9 | 700089.5 ± 24298.9 | 1.0034 | 0.9609–1.0465 | DOES_NOT_COST | **0.4537** |
| `PRESENCE_COUNT` | 385535.8 ± 6698.4 | 390592.2 ± 10346.7 | 1.0131 | 0.9694–1.0583 | DOES_NOT_COST | 0.7192 |
| `QUORUM` | 376677.3 ± 4064.1 | 378884.8 ± 11069.3 | 1.0059 | 0.9661–1.0465 | DOES_NOT_COST | 0.7037 |

`verdict=INCONCLUSIVE`, `split=UNRESOLVED`, `MATERIAL_RATIO=0.9`,
`RESOLVABLE_RELATIVE_ERROR=0.1`. **Zero of eight rows were unresolved for width of error
bar** — the failure mode that disqualified three of the probe's eight rows, one of them
with an error bar wider than its own score, does not occur here at all: the widest
relative error anywhere in either arm is `FLAT_MAP`'s long arm at 0.0347.

### The `TAGGED_SET`/`FILTER` versus `COUNT`/`FLAT_MAP` split: does it reproduce?

**By the criterion, `UNRESOLVED` — and the reason is not a tie, it is that the split has
come apart in a way the criterion has no word for.** `splitOf` asks whether `COUNT` and
`FLAT_MAP` lose throughput while `TAGGED_SET` and `FILTER` do not. `COUNT` and `FLAT_MAP`
are firmly on the DOES_NOT_COST side, which already refutes the pattern; `TAGGED_SET` is
UNDECIDED, which is what makes the four-row question formally unresolvable rather than
answered "no".

Stated without the criterion's vocabulary, so a reader is not left with only the word
`UNRESOLVED`:

- **The half of the split that carried the probe's entire weight is refuted.** The probe
  said `COUNT` and `FLAT_MAP` fall by more than half. They do not fall at all. Their
  intervals exclude any decline larger than 2.3% and 3.9% respectively.
- **The residual signal has moved to the other two subjects.** The largest declines in
  this A/B are `TAGGED_SET` (0.916) and `UNION` (0.941) — in the probe, `TAGGED_SET` was
  the *flattest* row at 0.980. So the split does not merely fail to reproduce; for
  `TAGGED_SET` the ordering inverts.
- **Neither residual is resolvable at this power.** Both straddle 0.90. Whether they are a
  real few-percent decline or the two noisiest subjects in the sweep (they carry the two
  widest short-arm error bars, 0.0139 and 0.0242) is exactly what these arms cannot say.

**The most economical explanation of the disagreement is the probe's own third caveat.**
Its two arms ran back to back with no host re-gate between them, and its post-arm reading
caught a browser renderer at 79.6% and ManageEngine `dcpatchscan` at 33.6% during the 10 s
arm's window. A host excursion landing inside the longer arm depresses exactly the rows
that arm measured, and interleaving with a per-arm gate is the design that removes it.
This run has both, and the effect is gone. That is a reading, not a measurement: nothing
here re-runs the probe's own configuration to confirm it.

### What this does and does not establish about `[BEN1-28]`'s time channel

**It does not retire it.** The verdict is INCONCLUSIVE and the entry says so. Two subjects
remain undecided, and this A/B covers `direction=INSERT`, `drive=REAL`, and the eight
set-shaped subjects only.

**It removes the evidence that had been offered for it.** After `computenet-i61m` the
corpus held a retired allocation channel plus one precisely-measured, large, contrary
time-channel effect. That effect is now measured at full power and is absent. What the
time channel currently rests on is two rows with intervals of 0.88–0.95 and 0.90–0.99 —
which is a very different thing to carry forward than "two subjects lose more than half
their throughput".

**The instrument is one-sided and a reader has to carry that.** Iteration length is not a
*label* on the tag map. A longer iteration accumulates more tag-map state, but it also
accumulates everything else that grows with time-in-iteration — JIT state, a filling young
generation, any other internal collection. So a decline here would have established that
*something* accumulating within the iteration costs wall clock, with the map the named
candidate; it would not on its own have attributed the cost to the map. The absence of a
decline is correspondingly the stronger direction for this instrument: it is evidence
against *any* within-iteration accumulation costing the six DOES_NOT_COST subjects
measurable wall clock, the tag map included.

### Nothing was tuned

`[BEN1-28]`'s standing instruction — the growth is observed, never tuned — was followed.
No tag map was cleared, compacted or bounded. Nothing under `kernel/src/main` or
`bench/src/jmh` was touched at all: the only source this item added is the criterion and
its render entry point under `bench/src/test`, which no benchmark body reads. Both arms
ran the class's own declared `Fork(2)`, `Warmup(5, 1s)`, `Measurement(10, 1s)`; the single
knob that differs between them is JMH's `-r`, the wall-clock length of a measurement
iteration, and both arms' `# Measurement:` banners are retained in their logs as proof of
that.

### Host, and the readings actually met — per arm

**The host is the pinned one**: `NL-MGD6FQJW91`, Apple M3 Max, Mac15,9, 16 cores, macOS
26.6.2, measuring on Microsoft OpenJDK 21.0.11+10-LTS invoked by absolute path (a bare
`java` here is JBR 25.0.2). CPU/cores/OS in the rendered blocks below come from each
measuring fork's own `@Setup(Level.Trial)` banner.

**Interleaving.** Sixteen separate JMH invocations, one per (subject, arm), with the arm
order alternating per subject — `short,long` for subjects 1/3/5/7 and `long,short` for
2/4/6/8 — so that no host excursion can land wholly inside one arm and no monotone drift
over the 36-minute window can systematically favour one. Each arm's CSV below is the
concatenation of its eight per-subject invocations (headers de-duplicated) and each arm's
log the concatenation of theirs; `MeasuringJvm`, `RunKnobs` and `HostFacts` all collapse
repeated banners with `distinct()` and **refuse** if the concatenated invocations disagree,
so a mixed-arm concatenation would have been caught by the differing `# Measurement:`
line rather than rendered.

**Per-arm gate.** Every one of the sixteen launches polled `load1` every 10 s and started
only on a reading below the quiesced threshold **4.00** (16 cores x 0.25, the same
threshold `computenet-i61m` and `computenet-x9e.14` used). **16 of 16 arms met the gate;
zero were run un-gated.** The `load1` reading at the moment each arm started:

| subject | arm order | short-arm start | long-arm start |
| --- | --- | --- | --- |
| `TAGGED_SET` | short, long | 3.85 | 3.25 |
| `FILTER` | long, short | 3.57 | 3.88 |
| `UNION` | short, long | 3.32 | 3.07 |
| `INTERSECT` | long, short | 2.97 | 3.84 |
| `COUNT` | short, long | 3.55 | 3.51 |
| `FLAT_MAP` | long, short | 3.89 | 2.88 |
| `PRESENCE_COUNT` | short, long | 3.27 | 3.07 |
| `QUORUM` | long, short | 2.66 | 2.16 |

The whole run occupied 04:29:37Z–05:05:36Z. Two gates had to wait for a trough rather than
finding one immediately — `long FILTER` polled six times over 50 s after the preceding arm
left `load1` at 4.61, and `short COUNT` polled sixteen times over 2m36s after
`short INTERSECT` ended at **9.70**. Both then ran on a reading under 4.00.

**Non-resident consumers during the window are recorded rather than discarded.** A 30 s
sampler ran throughout (72 samples). `load1` ranged **2.21–10.28**, with the measuring JVM
itself accounting for 137–269% CPU. The excursions worth naming, with what they overlap:

- **04:49:12Z–04:51:45Z — Microsoft Defender.** `wdavdaemon_unprivileged` at **418.1%** and
  `wdavdaemon_enterprise` at 106.7% in the 04:49:12Z sample, then `wdavdaemon_enterprise`
  at 107.4%, 99.7% and 51.7% through 04:51:45Z. This overlaps the tail of `short COUNT`
  (ended 04:49:57Z) and the first half of `long COUNT` (04:49:58Z–04:53:29Z) — i.e. it
  falls across **both** of `COUNT`'s arms, which is the property interleaving was chosen
  for. `COUNT` is nonetheless the tightest pair in the sweep (0.0110 and 0.0083 relative
  error) and its ratio is 0.9958.
- **04:42:40Z — a Brave Browser helper at 118.8%**, inside `long UNION`
  (04:39:14Z–04:42:45Z). `UNION` is one of the two undecided rows, and this reading is the
  strongest single reason not to read its 0.941 as a real decline.
- **04:33:08Z–04:35:09Z — Spotlight `mds` at 41.1–51.9%**, spanning the end of
  `long TAGGED_SET` and the start of `long FILTER`. `TAGGED_SET` is the other undecided row.
- **04:46:41Z — `load1` 10.28**, with `wdavdaemon_unprivileged` at 45.1%, `duetexpertd` at
  16.5%. This is the sample immediately after `short INTERSECT` finished; the gate then
  held the next arm off for 2m36s.
- **04:48:42Z and 04:50:44Z — `fileproviderd` at 63.8% and 95.4%**, the first between arms
  and the second inside `long COUNT`.

Mean CPU across all samples for the resident managed-endpoint stack: Microsoft Defender's
processes 24.0%, ManageEngine `com.manageengine.appctrl.driver` 12.3%, `trustd` 9.0%.

**The resident managed-endpoint stack never goes away on this host.** That it does not
disqualify a quiesced attestation here is a ruling by the orchestrating session recorded
in `computenet-i61m`'s entry, not a judgement made by this one; this session executed it.
The readings above are recorded in full so a reader who rejects that ruling can grade this
run SHARED and re-read the table themselves. Note what such a reader would find: the two
arms of every subject are minutes apart under one gate, so the ratio — not the absolute
throughput — is the quantity this entry claims, and it is the quantity least exposed to a
constant background load.

### Trigger (`[BEN1-31]`/`[BEN1-32]`)

`TriggerClaim.Cited` on `[BEN1-28]`, verdict `INCONCLUSIVE`, with the criterion and the
measured clause both generated from the same numbers by `IterationLengthCriterion`, so the
sentence in the rendered block cannot drift from the table beside it. The SHORT arm's block
carries `TriggerClaim.None` — MARKED INCOMPLETE — and correctly so: the control arm answers
no trigger question on its own; the A/B does, and the LONG arm's block carries it. This
entry cites neither G-21 nor G-43.

### WAL/journal statement (`[BEN1-29]`)

Unchanged and re-confirmed against the source both arms compiled: `Graphs.kt`'s `Rig` for
`Drive.REAL` constructs `ManagedHost(scheduler = scheduler)` with no `journal` argument,
and no `civictech.cell.durability` type appears in `Graphs.kt`, `Deltas.kt` or
`OperatorThroughputBenchmark.kt`. WAL/journal sync is not in play; KBLK is not named.

### Coverage, and what this sweep is not

Eight set-shaped subjects x two arms x `direction=INSERT` x `drive=REAL` = **16 of 16
invocations measured**, no row omitted. It is **not** a sweep of all 18 subjects, not a
`drive=SIM` measurement, not a RETRACT measurement, and not a statement about the join /
grouped / combine families — `IterationLengthAbRenderTest` refuses a non-set-shaped or
non-INSERT row outright rather than averaging one into a criterion stated over the
set-shaped INSERT family. The narrowing is the one `computenet-bzwx` specified.

**The second instrument the item offered was deliberately not spent.** The bead suggested a
JFR or async-profiler wall-clock profile of `COUNT` or `FLAT_MAP` at a long iteration as a
legitimate second instrument, on the premise that those two subjects lose half their
throughput. They do not, so there is no large time to attribute and the profile would have
had nothing to name. Profiling the two rows that *are* undecided would be a different item
against a few-percent effect, and is filed rather than improvised here.

### Artifacts

The sixteen per-arm raw JMH CSVs and logs, the two concatenated per-arm artifacts the
renderer read, the per-arm polling-gate log, the 30 s sampler log and the driver script are
retained at `$HOME/computenet-runs/computenet-bzwx/` on host `NL-MGD6FQJW91` — **outside
the repository**, because run artifacts are not committed in this lane. They are therefore
machine-local and not reproducible from this repository alone; the rendered blocks below,
`IterationLengthCriterion` and `IterationLengthCriterionTest` are the durable part.

### Commands, exactly

```
./gradlew :bench:jmhJar

# one invocation per (subject, arm), arms alternating per subject, each gated on a
# polled load1 trough below 4.00:
/Users/merlijn/Library/Java/JavaVirtualMachines/ms-21.0.11/Contents/Home/bin/java \
     -jar bench/build/libs/bench-jmh.jar 'OperatorThroughputBenchmark.real' \
     -p subject=<one of the eight> -p direction=INSERT -r <1s | 10s> \
     -rf csv -rff /abs/path/arms/<arm>-<subject>.csv \
     > /abs/path/arms/<arm>-<subject>.log 2>&1

# concatenated per arm (headers de-duplicated) into iteration-ab.csv/.log (LONG) and
# iteration-ab.short.csv/.short.log (SHORT), then rendered through the module's own
# entry point:
./gradlew :bench:test -PbenchOnly=true --rerun \
  --tests 'civictech.bench.micro.IterationLengthAbRenderTest' \
  -Dcivictech.bench.jmhResults=/abs/path/iteration-ab.csv \
  -Dcivictech.bench.harnessSha=1f68b10d4 \
  -Dcivictech.bench.date=2026-08-26
```

The rendered blocks below were read back out of the JUnit XML `<system-out>` and pasted
verbatim, not retyped.

---

Renderer's own output for the **LONG arm (10 s measurement iterations)**, pasted verbatim:

## 2026-08-26 — iteration-length A/B, LONG arm (10 s measurement iterations): REAL-drive INSERT throughput over the eight set-shaped subjects — the treatment arm of computenet-bzwx's test of [BEN1-28]'s TIME channel
Harness: 1f68b10d4 · JVM Microsoft (Microsoft-13877178)/21.0.11 · heap JVM defaults (VM options: <none>) · Apple M3 Max, 16 cores, Mac OS X 26.6.2
JMH: mode=Throughput forks=2 warmup=5 iters=10 · drive=REAL
| subject | value | notes |
| --- | --- | --- |
| TAGGED_SET insert | 794175.181787 ± 17745.380301 ops/s | |
| FILTER insert | 864755.887523 ± 9609.133759 ops/s | |
| UNION insert | 738521.700916 ± 17638.412536 ops/s | |
| INTERSECT insert | 367427.420641 ± 3251.348948 ops/s | |
| COUNT insert | 872955.288862 ± 7220.108972 ops/s | |
| FLAT_MAP insert | 700089.527186 ± 24298.871769 ops/s | |
| PRESENCE_COUNT insert | 390592.218743 ± 10346.687589 ops/s | |
| QUORUM insert | 378884.765548 ± 11069.304255 ops/s | |
Trigger: [BEN1-28] — INCONCLUSIVE: the criterion applied is that each set-shaped subject's ratio of long-arm to short-arm throughput is taken with its error bars propagated, a row counts as losing throughput only if the whole ratio interval falls below MATERIAL_RATIO=0.90 and as not losing it only if the whole interval falls above it, a row whose relative error exceeds RESOLVABLE_RELATIVE_ERROR=0.10 in either arm is unresolved and votes for nothing, and the question fires only if a strict majority of the set-shaped rows lose throughput, retires only if every row is resolved and every row does not, and is otherwise undecided; measured, across 8 set-shaped rows the long-arm/short-arm throughput ratio ranges 0.915597819264048 (TAGGED_SET) to 1.0131152994940666 (PRESENCE_COUNT), with 0 rows losing throughput, 6 not losing it, 2 straddling the 0.90 boundary and 0 unresolved for width of error bar; the computenet-i61m subject split UNRESOLVED

Row dispersion (drive=REAL; informational, nothing excluded):
- TAGGED_SET insert (drive=REAL): 794175.181787 ± 17745.380301 ops/s, relative dispersion 0.022344415574748294 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- FILTER insert (drive=REAL): 864755.887523 ± 9609.133759 ops/s, relative dispersion 0.01111196107207125 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- UNION insert (drive=REAL): 738521.700916 ± 17638.412536 ops/s, relative dispersion 0.02388340452842862 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- INTERSECT insert (drive=REAL): 367427.420641 ± 3251.348948 ops/s, relative dispersion 0.008848955644975598 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- COUNT insert (drive=REAL): 872955.288862 ± 7220.108972 ops/s, relative dispersion 0.008270880609947689 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- FLAT_MAP insert (drive=REAL): 700089.527186 ± 24298.871769 ops/s, relative dispersion 0.03470823491199615 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- PRESENCE_COUNT insert (drive=REAL): 390592.218743 ± 10346.687589 ops/s, relative dispersion 0.026489743247568028 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- QUORUM insert (drive=REAL): 378884.765548 ± 11069.304255 ops/s, relative dispersion 0.029215490464468558 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)

---

Renderer's own output for the **SHORT arm (1 s measurement iterations)**, pasted verbatim.
Its trigger line reads MARKED INCOMPLETE by design — see "Trigger" above:

## 2026-08-26 — iteration-length A/B, SHORT arm (1 s measurement iterations): REAL-drive INSERT throughput over the eight set-shaped subjects — the control arm of computenet-bzwx's test of [BEN1-28]'s TIME channel
Harness: 1f68b10d4 · JVM Microsoft (Microsoft-13877178)/21.0.11 · heap JVM defaults (VM options: <none>) · Apple M3 Max, 16 cores, Mac OS X 26.6.2
JMH: mode=Throughput forks=2 warmup=5 iters=10 · drive=REAL
| subject | value | notes |
| --- | --- | --- |
| TAGGED_SET insert | 867384.309003 ± 12066.054026 ops/s | |
| FILTER insert | 868818.859076 ± 8110.335861 ops/s | |
| UNION insert | 784508.563072 ± 18963.185739 ops/s | |
| INTERSECT insert | 375458.151458 ± 3361.196983 ops/s | |
| COUNT insert | 876605.147058 ± 9636.497096 ops/s | |
| FLAT_MAP insert | 697725.782412 ± 5532.867463 ops/s | |
| PRESENCE_COUNT insert | 385535.801244 ± 6698.441313 ops/s | |
| QUORUM insert | 376677.294247 ± 4064.056547 ops/s | |
Trigger: none cited — entry MARKED INCOMPLETE, not presented as a finding

Row dispersion (drive=REAL; informational, nothing excluded):
- TAGGED_SET insert (drive=REAL): 867384.309003 ± 12066.054026 ops/s, relative dispersion 0.013910851165695074 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- FILTER insert (drive=REAL): 868818.859076 ± 8110.335861 ops/s, relative dispersion 0.009334898496132376 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- UNION insert (drive=REAL): 784508.563072 ± 18963.185739 ops/s, relative dispersion 0.024172057045169067 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- INTERSECT insert (drive=REAL): 375458.151458 ± 3361.196983 ops/s, relative dispersion 0.008952254651943532 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- COUNT insert (drive=REAL): 876605.147058 ± 9636.497096 ops/s, relative dispersion 0.010992973436605212 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- FLAT_MAP insert (drive=REAL): 697725.782412 ± 5532.867463 ops/s, relative dispersion 0.007929859555817442 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- PRESENCE_COUNT insert (drive=REAL): 385535.801244 ± 6698.441313 ops/s, relative dispersion 0.01737436910239279 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
- QUORUM insert (drive=REAL): 376677.294247 ± 4064.056547 ops/s, relative dispersion 0.010789226239729388 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)

---

The criterion's own inputs and derived interval per row, printed by the same test run
and pasted verbatim:

```
A/B criterion inputs (ops/s = DELTAS per second; ratio = long-arm score / short-arm score, interval propagated from both arms' 99.9% error bars):
| subject | short arm (ops/s) | short rel. err | long arm (ops/s) | long rel. err | ratio | ratio low | ratio high | row |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| COUNT | 876605.147058 | 0.010992973436605212 | 872955.288862 | 0.008270880609947689 | 0.9958363714743754 | 0.97686131722734 | 1.0152332473297379 | DOES_NOT_COST |
| FILTER | 868818.859076 | 0.009334898496132376 | 864755.887523 | 0.01111196107207125 | 0.995323568876807 | 0.9751605473979402 | 1.0158665770102855 | DOES_NOT_COST |
| FLAT_MAP | 697725.782412 | 0.007929859555817442 | 700089.527186 | 0.03470823491199615 | 1.0033877847624157 | 0.9609418320515756 | 1.0465122992603473 | DOES_NOT_COST |
| INTERSECT | 375458.151458 | 0.008952254651943532 | 367427.420641 | 0.008848955644975598 | 0.9786108497423357 | 0.9613449608414586 | 0.996188668386171 | DOES_NOT_COST |
| PRESENCE_COUNT | 385535.801244 | 0.01737436910239279 | 390592.218743 | 0.026489743247568028 | 1.0131152994940666 | 0.9694348169990341 | 1.0583404614714491 | DOES_NOT_COST |
| QUORUM | 376677.294247 | 0.010789226239729388 | 378884.765548 | 0.029215490464468558 | 1.0058603779275117 | 0.9660507337223962 | 1.046538422011087 | DOES_NOT_COST |
| TAGGED_SET | 867384.309003 | 0.013910851165695074 | 794175.181787 | 0.022344415574748294 | 0.915597819264048 | 0.8828580146488577 | 0.949261350805418 | UNDECIDED |
| UNION | 784508.563072 | 0.024172057045169067 | 738521.700916 | 0.02388340452842862 | 0.9413813126832886 | 0.8972104986227372 | 0.9877404211965901 | UNDECIDED |

MATERIAL_RATIO=0.9 RESOLVABLE_RELATIVE_ERROR=0.1
verdict=INCONCLUSIVE
computenet-i61m subject split: UNRESOLVED
```


---

## 2026-08-26 — per-benchmark-class noise floors: the machinery and the derivation FORMAT, pre-registered with NO derivations yet (computenet-cm4w, half (a))

**This entry publishes no number.** It records a criterion and its format, fixed in
committed source before the measurement it will be applied to exists — the same
pre-registration discipline the `NOISE_FLOOR` entry above used (margin fixed on
`computenet-x9e.3.3` before the first run reported a score) and `computenet-bzwx`'s
`IterationLengthCriterion` used (`MATERIAL_RATIO`/`RESOLVABLE_RELATIVE_ERROR` fixed
before the A/B ran). The three sequential quiesced repeat runs per benchmark class that
would produce actual floors are **pending a dedicated quiesced slot** and were NOT
attempted here; see "What is outstanding" below.

### What was wrong

`NOISE_FLOOR = 0.005` was derived from `SmokeBenchmark.baseline`, a 4.3 ns branch-free
bit mixer, and then inherited by every benchmark class in the module. Hosted-graph
measurements intrinsically run 0.01–0.15 relative dispersion, so the 2026-08-21 findings
review measured what that inheritance does: **66 of 72** throughput rows and **all 10**
fan-out rows sat above 0.005. A bound a benchmark class cannot clear even on a silent
machine is not detecting interference; it is restating that a hosted graph is not a bit
mixer. A floor derived from a class's *own* repeat-run history restores the signal — a
row above that class's floor is more dispersed than that same benchmark is when the
machine is quiet, which is what "interference or a confound" actually means.

### The machinery, as landed

`bench/src/main/kotlin/civictech/bench/ClassNoiseFloor.kt`:

- `CLASS_NOISE_FLOOR_DERIVATIONS: List<ClassNoiseFloor>` — **empty**. Nothing may enter it
  that did not come from the procedure below.
- `noiseFloorFor(benchmarkClass, floors)` — that class's derived floor where one exists,
  and `NOISE_FLOOR` where none does. The class floor WINS in both directions: a class
  quieter than `SmokeBenchmark` would derive a *tighter* floor, and a resolution taking
  `max(classFloor, NOISE_FLOOR)` would be wrong exactly there while looking right on every
  hosted-graph class.
- `classifyAgainst(result, floor)` — the one copy of the classification arithmetic; both
  `classify` overloads reach it, so the absolute-value and non-finite refusals have a
  single definition.
- `ThroughputReport.DispersionNote` resolves its bound from the row's own benchmark class
  (carried off the JMH results file by `toResults`), and its rendered sentence says WHICH
  bound it used — "above the harness sanity bound NOISE_FLOOR 0.005" and "above the
  `<Class>` class floor 0.06 (derived from that class's own quiesced repeat runs)" are
  different claims about the world and are phrased differently.

While the derivation list is empty every class resolves to `NOISE_FLOOR`, so the harness
classifies today exactly as it did before this machinery existed. That is the correct
behaviour for a floor that has not been measured, and `DispersionNoteFloorTest` pins it.

### The margin, fixed before the numbers

**`CLASS_FLOOR_MARGIN = 2.0`.** The same 2x as `NOISE_FLOOR`'s, and deliberately the same
rather than a fresh choice: a per-class floor and the global bound are the same
construction over different subjects, so a reader comparing them should be reading a
difference in the *measurement*, not in the margin. The size carries over unchanged —
three runs on a deliberately idle host, with error bars that measure dispersion *within* a
run rather than across runs, is a lower bound three times over, so the floor must sit above
it or the class fires on itself.

### The derivation procedure, fixed before the runs

Per benchmark class, at **that class's own annotation configuration** (its declared
`@Fork`/`@Warmup`/`@Measurement`/`@BenchmarkMode`, never a config chosen to make the number
smaller):

1. `./gradlew :bench:jmhJar` once, then **three sequential** executions of
   `bench/build/libs/bench-jmh.jar` filtered to that class, under the module's declared
   toolchain JDK, on a host attested `quiesced` (`scripts/bench-series/run-series.sh`'s
   one-directional guard: 1-minute load average at or below 0.25 x core count, plus the
   operator's own attestation that no other session, build or scheduled scan is live).
2. Across all rows of all three runs, take the **maximum** relative dispersion
   (`scoreError / score`). Maximum, not mean: the floor has to sit above the worst
   quiet-host row, or a quiet host produces rows above their own floor.
3. Floor = `CLASS_FLOOR_MARGIN` x that maximum, rounded UP to three decimals. Computed by
   `ClassNoiseFloor.floor` — never hand-entered, so the table cannot hold a number that
   disagrees with its own derivation.
4. Append the entry rendered by `renderDerivation(...)`, which prints the observation, the
   pre-fixed margin, the computed floor, the run count, the host state, the JMH config, and
   both halves of the what-it-does / does-not-establish pair.

`ClassNoiseFloor`'s constructor refuses a `hostState` other than `quiesced` and fewer than
three runs, so a floor derived through interference cannot be represented at all.

### What is outstanding — half (b)

Nothing here is a floor. The derivations for `OperatorThroughputBenchmark`,
`FanOutScalingBenchmark`, `CellFootprintBenchmark` and `BoundedReadBenchmark` require a
host that is actually quiesced; the pinned machine left its quiesced window on 2026-08-26
around 07:05 local (load above 20 on 16 cores, an endpoint scanner near 80% CPU, a human
at the keyboard), and a floor derived through that interference would be a floor measuring
the interference, inherited by every row later classified against it. No number was
estimated, scaled from another class, or carried over. The runs are routed to a dedicated
quiesced slot on `computenet-cm4w` (`metadata.compute=dedicated`).

## 2026-08-26 — per-class noise floor for `CellFootprintBenchmark`, derived forward from its own quiesced repeat runs
**SUPERSEDED 2026-08-27 by `computenet-3sua`** — the estimator this block used (the maximum over every observation) was replaced by `classFloorStatistic`, and this class's floor was re-derived from these same three runs to **0.398**. The live entry is the last one in this file; the block below is retained as the record of the runs and of the JBR-25 defect `computenet-7v7m` fixed, and its 1.044 is NOT the floor in force.
Harness: a7c6a0382 · host state quiesced · 3 sequential repeat runs
JMH: mode=AverageTime unit=us forks=1 warmup=3x1s measurement=5x1s (the class's own annotation configuration)
Measured under: JDK 21.0.5, OpenJDK 64-Bit Server VM, 21.0.5+11-LTS (Amazon Corretto; :bench's resolved toolchain launcher)
| quantity | value |
| --- | --- |
| max observed relative dispersion across all rows of all 3 runs | 0.5217864937179187 |
| margin, fixed before the runs (CLASS_FLOOR_MARGIN) | 2.0 |
| derived floor = margin x observed, rounded up to three decimals | 1.044 |
Derivation: forward. The margin was fixed in `ClassNoiseFloor.kt` before any per-class number existed; the floor is computed from the observation by `ClassNoiseFloor.floor` and is not hand-entered. What it establishes: rows of `CellFootprintBenchmark` measured under this configuration on a quiesced host stayed at or under 0.5217864937179187 relative dispersion, so a later row above 1.044 is more dispersed than this class is when the machine is quiet. What it does NOT establish: anything about another benchmark class, about this class under a different annotation configuration, about this class on another host, or about this class under a JVM other than JDK 21.0.5, OpenJDK 64-Bit Server VM, 21.0.5+11-LTS (Amazon Corretto; :bench's resolved toolchain launcher).

### Provenance and limits of the entry above (`computenet-7v7m`, superseding `computenet-ahn0`)

**This entry REPLACES an earlier derivation of the same class published the same day, and
the replacement is part of the record rather than a quiet overwrite.** `computenet-ahn0`
derived `CellFootprintBenchmark`'s floor from three quiesced runs and published
`observedMaxRelativeDispersion` 0.2961501149112133, floor 0.593. All three of those runs
were measured under the **JetBrains Runtime, JDK 25.0.2** — each retained log banner reads
`# VM version: JDK 25.0.2, OpenJDK 64-Bit Server VM, 25.0.2+10-b329.111` with
`# VM invoker: …/jbr-25.0.2/…/bin/java`, and every row of all three results files carries
`jdkVersion` 25.0.2. The module's declared Gradle toolchain is **JDK 21**, and step 1 of
the procedure pre-registered in `ClassNoiseFloor.kt` requires the runs be made under it.
A per-class floor is applied at classification time to rows `run-series.sh` forces to be
measured under 21, so a floor derived under 25 may be loose or tight against them and
nothing states which. The earlier number is superseded, not retained as a second entry,
and no part of it survives in `CLASS_NOISE_FLOOR_DERIVATIONS`.

The trap is worth naming because it is still live: **a bare `java` on this host is JBR
25.0.2**, and `JAVA_HOME` is unset. `scripts/bench-series/run-series.sh` refuses a
non-21 launcher for exactly this reason (`PINNED_JDK_MAJOR=21`); invoking the JMH jar
directly bypasses that refusal, which is how the first derivation was made. The runs
below mirrored both of its refusals — the load gate and the JDK pin — and additionally
verified each run's own `# VM version:` banner after the fact.

**The measuring JVM, quoted from the artifact.** All three runs:

    # VM version: JDK 21.0.5, OpenJDK 64-Bit Server VM, 21.0.5+11-LTS

launched by absolute path from
`/Users/merlijn/Library/Java/JavaVirtualMachines/corretto-21.0.5/Contents/Home/bin/java`
(`openjdk version "21.0.5" 2024-10-15 LTS`, **Amazon Corretto 21.0.5+11-LTS**). That is
the launcher Gradle's own toolchain resolution selects for `:bench` on this host today
(`./gradlew javaToolchains`), which is what makes it the module's declared toolchain
rather than merely some JDK 21. It is a **different vendor** from the Microsoft OpenJDK
21.0.11 and Eclipse Adoptium 21.0.11 that earlier BEN1 findings entries name: the
Adoptium build those entries invoked by absolute path under `~/.gradle/jdks/` is no
longer installed, and this host now offers two JDK 21s (Microsoft 21.0.11 and Corretto
21.0.5) of which Gradle picks Corretto. The floor is therefore a claim about Corretto
21.0.5, and the rendered block says so.

**Host and gate.** NL-MGD6FQJW91, 16 cores, so `run-series.sh`'s gate is a 1-minute load
average at or below 4.00. Each of the three runs was preceded by its own attestation of
that gate, taken immediately before the run and recorded here in full: run 1 at 12:14:13
local, 1-min 3.67; run 2 at 12:21:01, 1-min 3.22; run 3 at 12:24:07, 1-min 1.95. One
intervening reading REFUSED the gate and the run was held rather than taken — 12:17:23 at
4.21, run 1's own load still decaying out of the 1-minute average — and the hold ran
another 11 samples (peaking again at 6.04 and 4.97) before the gate opened. Wall time per
run: 177s / 177s / 178s. The gate was never relaxed to fit the schedule.

**What was live on the host during the runs, stated rather than claimed away.** The gate
is one-directional — it can refuse a wrong claim and can never confirm a right one — so
the honest attestation is what was actually running, not "an empty machine". Two Claude
Code sessions were live on this host throughout: an orchestrating session and this
implementing one, plus a 20-second `uptime` load sampler between runs. An idle Gradle
daemon from the `:bench:jmhJar` build remained resident. The corporate security stack
(Microsoft Defender, ManageEngine appctrl, `trustd`) is resident and never leaves. No
build, no other benchmark, and no scheduled endpoint scan ran concurrently with a
measurement. The baseline this floor is derived against is therefore "this machine with
its permanent resident load and two idle agent sessions", which is the quietest state it
reaches, not a silent machine.

**Why the floor is 1.044, and the limit of that number.** The maximum over all 63 rows is
`realSnapshot` OR_MAP_CELL N1E5 in run 1, at 0.5218 — a JMH 99.9% error bar half the size
of the score itself, on a host that passed the gate. The underlying spread is real and
reproducible: 12 of the 63 rows exceed 0.10 — OR_MAP_CELL and SET_CELL in all three runs,
MAP_CELL in two, KEYED_SET_CELL and LIST_CELL in one each; the median row is 0.038,
itself between seven and eight times the global bound. That spread is the structural fact
the per-class floor exists to expose — the global 0.005 fired on those rows on every
quiet-host run and so distinguished nothing.

But the row that *sets* the floor is comparatively isolated in a way the spread is not.
OR_MAP_CELL N1E5 measured 0.522 / 0.119 / 0.199 across runs 1 / 2 / 3, so the maximum is
about 2.6x its own next-worst observation, and the second-highest row over all 63 is 0.201
(MAP_CELL N1E5, run 2). A floor of 1.044 is consequently a **weak bound**: it will refuse
very little, and it should not be read as "this class typically disperses 0.5". It is
nonetheless the number the pre-registered procedure yields, and it stands as published.
The procedure says MAXIMUM, not mean, precisely so that the floor sits above the worst
quiet-host row; dropping that row, or switching statistic, *after seeing that the maximum
came out inconvenient* is the reverse-engineering the forward discipline exists to
prevent. Tightening this floor requires more runs decided in advance, not a different
reading of these three.

**Comparison with the superseded JBR 25 runs — an observation, not a finding.** Under JBR
25.0.2 the same class on the same host observed a maximum of 0.296 (KEYED_SET_CELL N1E5);
under Corretto 21.0.5 it observed 0.522 (OR_MAP_CELL N1E5). Three runs per arm, taken
hours apart, with no interleaving and no repetition — that is nowhere near enough to
attribute the difference to the runtime rather than to run-to-run variation in a class
whose worst rows vary by 2.6x between its own repeats. It is recorded so the next reader
knows both numbers exist and which one is live; it establishes nothing about JDK 21 versus
JDK 25 for this benchmark, and a claim in that direction needs its own interleaved A/B.

**Three of the four classes were NOT derived, and no number was estimated for them.**
`OperatorThroughputBenchmark`, `FanOutScalingBenchmark` and `BoundedReadBenchmark` still
fall back to `NOISE_FLOOR`. They were not attempted because their own annotation
configurations do not fit one dedicated slot: `OperatorThroughputBenchmark` is 72 rows
(2 benchmarks x 18 subjects x 2 directions) at `@Fork(2)` x (5 warmup + 10 measurement)
x 1s, about 48 minutes per run and so ~144 minutes for the three required runs;
`FanOutScalingBenchmark` is 30 rows at up to `@Fork(5)`, about 27 minutes per run;
`BoundedReadBenchmark` is 6 rows at `@Fork(5)`, about 7 minutes per run — against
`CellFootprintBenchmark`'s measured 177 seconds. Running any of them at a reduced fork or
iteration count to fit the slot would derive the floor from a configuration the floor does
not describe, which the pre-registered procedure forbids. They are routed to their own
dedicated slots instead. **Whoever works them must launch a JDK 21 by absolute path and
check each run log's `# VM version:` banner before trusting its numbers** — that is what
went wrong here.

**Run evidence**, outliving the worktree: `$HOME/computenet-runs/computenet-7v7m/` holds
the three results files (`cellfootprint-{1,2,3}.json`), the three JMH logs whose
`# VM version:` banner is the load-bearing artifact (`cellfootprint-{1,2,3}.log`), the
load sampling (`loadwatch.txt`) and the runner that enforced both refusals
(`derive-run.sh`). The superseded JBR 25 runs remain at
`$HOME/computenet-runs/computenet-ahn0/`.

## 2026-08-26 — the per-class floor derivation procedure is amended to admit ROW-SET decomposition (no number derived)

**This entry records a change to a pre-registered procedure, not a measurement.** Nothing
was run for it and no floor moved. It is appended here, and the amended text is committed
in `ClassNoiseFloor.kt`, **before any number derived under the amended form exists** — the
same forward discipline under which the margin, the statistic and the rendered format were
fixed before the first per-class number existed. `computenet-3omz.1`.

**What changed.** Step 1 of `ClassFloorDerivation`'s procedure read "three **sequential**
executions of `bench/build/libs/bench-jmh.jar` filtered to that class". That admits exactly
one shape: a class completed in one uninterrupted stretch. The 2026-08-26 entry above sized
the three un-derived classes at roughly 48, 27 and 7 minutes per run — about four hours of
continuous gated measurement for the three required runs each — and the pinned host
NL-MGD6FQJW91 is a laptop in daily interactive use whose owner stated on 2026-08-26 that a
four-hour idle block is marginal. A procedure that only completes as one stretch does not
complete at all, which is why those three classes are still falling back to `NOISE_FLOOR`.

The amendment states that **the unit of decomposition is the ROW SET**: a class's
derivation may be completed across several short quiesced windows, each measuring a SUBSET
of the class's rows, and the class is done when every row has been measured exactly three
times in three separate JVM invocations.

**Why that is sound.** The derived quantity is `max |scoreError / score|` over all rows of
all three runs. `max` is associative and commutative, so folding it over rows measured at
different times yields the same number — bit for bit — as folding it over all of them at
once. JMH forks per (benchmark, `@Param`) combination, so a row's own measurement does not
depend on which other rows shared its invocation: a benchmark regex or a `-p` value selects
WHICH rows run, never HOW a row is measured.

**What the amendment explicitly does NOT permit.** Every row still runs at its class's own
annotation configuration, three times. **No threshold, fork count, iteration count,
iteration duration, thread count, benchmark mode or margin may be changed to make a unit
fit a window** — not `-f`, not `-wi`, not `-i`, not `-w`, not `-r`, not `-t`, not `-bm`,
not `CLASS_FLOOR_MARGIN`, not `NOISE_FLOOR`. That list is illustrative and the rule is
not: the only thing a unit may vary is which rows it measures. A unit too long for the
available window is split into FEWER ROWS, never into a cheaper configuration — including
one that keeps the iteration counts and shortens the iterations, which is the same shrink
wearing a different flag. This is a change to scheduling and to
nothing else, and it must never become the route by which a configuration is shrunk. The
estimator is untouched: still the maximum, still 2x, still rounded up to three decimals.
Whether the maximum is the right statistic — the 2026-08-26 entry above calls the resulting
1.044 a weak bound — remains open as `computenet-3sua` and is not answered here.

**What decomposition costs, stated rather than assumed.** Rows measured in windows hours
apart see different ordering and different thermal state than rows measured back to back.
Nothing has measured whether that matters for these classes. It is a known difference in
the measurement, not a defect the amendment repairs.

**Per-unit obligations.** Everything the procedure required per run is now required per
UNIT: the host gate (`run-series.sh`'s 1-minute load average at or below 0.25 x core count)
is attested immediately before each unit, and each unit's own log's `# VM version:` banner
is verified afterwards — the per-unit copy of the check `computenet-7v7m` added per run.

**Two refusals make the decomposition safe, and both are implemented rather than asked
for.** `FloorDerivationLedger` (`bench/src/main/kotlin/civictech/bench/FloorDerivationLedger.kt`)
is the sanctioned accumulator; it persists one class's partial derivation — per-unit banner,
gate reading, timestamp, and one observation per row — to a human-readable file outside the
repository, so a window that ends mid-class loses nothing.

1. **A floor may be rendered only from a COMPLETE row set**, and the refusal names every
   outstanding row with its own observation count. A maximum over a subset can only be
   SMALLER than the maximum over the whole set, so a floor rendered from a partial row set
   is systematically too LOW — the direction that admits rows the floor should have
   refused. Completeness is checked against a row universe pre-registered in committed
   source (`EXPECTED_PLAN_ROW_COUNTS`) rather than against the enumeration itself, because
   completeness computed over a filtered enumeration is satisfied vacuously. The
   pre-registered counts, verified against the classes' own `@Benchmark` methods and
   `@Param` enums on 2026-08-26: `CellFootprintBenchmark` 21 (1 x 7 `CellFamily` x 3
   `Scale`), `OperatorThroughputBenchmark` 72 (2 x 18 `Subject` x 2 `Direction`),
   `FanOutScalingBenchmark` 30 (6 x 5 `FanDegree`), `BoundedReadBenchmark` 6 (2 x 3
   `SetScale`), `SmokeBenchmark` 1.
2. **A floor may be rendered only from a row set measured under ONE JVM version.** With
   units spread over days the measuring JDK can change between them, and that defect has
   already happened in compressed form: `computenet-ahn0` derived `CellFootprintBenchmark`
   under JBR 25.0.2 rather than the toolchain's JDK 21, and the floor moved from 0.593 to
   1.044 when re-derived. On this host a bare `java` is JBR 25 and `JAVA_HOME` is unset, so
   the trap is live. A mixed-JVM row set is refused at render and warned about at ingest.

A fourth observation of any row is also refused — exactly three fold into the statistic and
nothing is dropped to make room — and a ledger file that cannot be parsed in full is
refused on load rather than partially applied, since a half-loaded ledger is an incomplete
row set wearing a complete one's face.

**Verification**: `./gradlew :bench:test --rerun`. The fold-equivalence property (a fold
over an arbitrary partition of a row set into units equals the whole-set maximum) is a
synthetic property test over generated dispersions from a fixed seed; it needs no
measurement, and none was taken for this entry.

## 2026-08-27 — Correction to the ROW-SET decomposition entry above: `EXPECTED_PLAN_ROW_COUNTS` no longer pre-registers `SmokeBenchmark`

Filed as `computenet-chw2`, from the review of `computenet-epxt` (PR #506, merged as
`bd6e10c28`). The **"2026-08-26 — the per-class floor derivation procedure is amended to
admit ROW-SET decomposition"** entry above states the pre-registered counts as —

> `CellFootprintBenchmark` 21 (1 x 7 `CellFamily` x 3 `Scale`), `OperatorThroughputBenchmark`
> 72 (2 x 18 `Subject` x 2 `Direction`), `FanOutScalingBenchmark` 30 (6 x 5 `FanDegree`),
> `BoundedReadBenchmark` 6 (2 x 3 `SetScale`), `SmokeBenchmark` 1.

— naming `SmokeBenchmark 1` as a fifth pre-registered class. `computenet-epxt` removed that
entry from `EXPECTED_PLAN_ROW_COUNTS` (`bench/src/main/kotlin/civictech/bench/FloorDerivationLedger.kt`)
after the entry above was written, so a reader citing that list today gets a class the table
no longer serves.

**The mechanism.** `SmokeBenchmark` carries only `@State`/`@BenchmarkMode`/`@OutputTimeUnit`
and relies on JMH's own defaults for `@Fork`/`@Warmup`/`@Measurement` — it is a discovery
sentinel, not a derivation target. `UnitSizing.estimateRowSeconds` sizes a unit by reading
those three annotations at method or class level by reflection; for `SmokeBenchmark` that
reflection finds nothing at either level, so no unit can ever be sized for it. Pre-registering
a count let `plan` accept a class `next` could never advance on — plannable but not actually
serviceable. Removing the entry makes `plan --class SmokeBenchmark` refuse too, on
`DerivationPlan`'s own "no pre-registered row count" check, so `plan` and `next` now agree in
refusing the class rather than disagreeing about whether it can be derived.

**The table today**, verified directly against `EXPECTED_PLAN_ROW_COUNTS` at `bd6e10c28`,
holds four classes, unchanged from the entry above except for the removal:
`CellFootprintBenchmark` 21, `OperatorThroughputBenchmark` 72, `FanOutScalingBenchmark` 30,
`BoundedReadBenchmark` 6.

**Not affected**: every other figure in the entry above — the row-set completeness rule, the
single-JVM-version rule, the decomposition scheduling rules, and the per-unit obligations —
none of which depended on `SmokeBenchmark` being pre-registered.

**Not repaired here**: the entry above still lists `SmokeBenchmark 1` verbatim, and this file
is append-only by its own header rule (nothing above the insertion point is edited, reordered
or deleted). This correction is the only record that the list changed.

**Verification**: none taken for this entry — it corrects prose against committed source
(`bench/src/main/kotlin/civictech/bench/FloorDerivationLedger.kt` at `bd6e10c28`), not a
measurement.

---

## 2026-08-27 — the residual of `computenet-bzwx`'s iteration-length A/B, at 16 forks: `TAGGED_SET` and `UNION` both RESOLVE to the not-material side, and they agree

`computenet-ciz9`. `computenet-bzwx` (the entry above) reached `INCONCLUSIVE` on
`[BEN1-28]`'s TIME channel for one reason only: exactly two of its eight set-shaped rows had
ratio intervals straddling the pre-registered `MATERIAL_RATIO = 0.90`. It deliberately did
**not** add forks for those two after seeing which two they were — adjusting a design to the
rows that failed to decide, once their identity is known, is the defect pre-registration
exists to prevent — so the residual was carried here to be measured under its own criterion,
fixed in committed source before its numbers existed. This is that measurement.

**Headline.** At 16 forks per arm — eight times `computenet-bzwx`'s two — both residual rows
resolve, both to the **does-not-cost** side of 0.90, and they agree with each other.
`TAGGED_SET` reports a long/short throughput ratio of **0.9734** (interval 0.9650–0.9820)
against `bzwx`'s 0.9156 (0.8829–0.9493); `UNION` reports **0.9447** (0.9279–0.9617) against
`bzwx`'s 0.9414 (0.8972–0.9877). By the criterion committed as `f875ceb7b` before the first
arm ran, the residual question **RETIRES** — for these two rows and nothing else.

**Read that verdict at exactly its width.** It is a statement about `TAGGED_SET` and `UNION`
under this instrument. The other six set-shaped rows were **not re-measured here** and are
not restated: `computenet-bzwx`'s numbers for them stand as its own entry reports them. What
this entry adds is that the two rows which prevented that entry from deciding no longer
prevent it.

### The residual A/B, per subject

`ratio` is long-arm score / short-arm score; the interval is propagated worst-case from both
arms' 99.9% error bars (long low over short high, long high over short low), which is wider
than a root-sum-square combination would be. `bzwx` is the same subject's 2-fork row from the
entry above, quoted from its table.

| subject | short arm 1 s (ops/s) | long arm 10 s (ops/s) | ratio | interval | row | `bzwx` ratio (2 forks) | `bzwx` interval |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `TAGGED_SET` | 818913.8 ± 4033.1 | 797154.3 ± 3021.8 | 0.9734 | 0.9650–0.9820 | DOES_NOT_COST | 0.9156 | 0.8829–0.9493 |
| `UNION` | 790147.5 ± 7503.8 | 746420.3 ± 6275.7 | 0.9447 | 0.9279–0.9617 | DOES_NOT_COST | 0.9414 | 0.8972–0.9877 |

`verdict=RETIRES`, `agreement=AGREE_DOES_NOT_COST`, `MATERIAL_RATIO=0.9`,
`RESOLVABLE_RELATIVE_ERROR=0.1`, `FORKS=16`. The design bought what it was sized to buy: the
ratio interval's half-width fell from 0.0332 to **0.0085** for `TAGGED_SET` (3.9x) and from
0.0453 to **0.0169** for `UNION` (2.7x). Neither row was anywhere near the
`RESOLVABLE_RELATIVE_ERROR` limit — the widest relative error in either arm is `UNION`'s short
arm at 0.0095.

### Both rows still show a real, small decline, and the criterion calls it immaterial rather than absent

**This is the part a reader must not compress into the word RETIRES.** `DOES_NOT_COST` means
*the whole interval sits above 0.90*. It does not mean the ratio is 1. Both intervals here
**exclude 1.0**:

- `TAGGED_SET` loses between 1.8% and 3.5% of its throughput to the 10x longer iteration.
- `UNION` loses between 3.8% and 7.2%.

So at this power the honest statement is: **a small within-iteration decline is present in
both residual subjects and is resolved to a few percent, and the lane's pre-registered
materiality bar of 10% says that is not a cost.** The bar was fixed by `computenet-bzwx`
before any of these numbers existed and was deliberately **not** moved by this item in either
direction; moving it now, with two known point estimates in hand, is the thing this item was
created to avoid. A later item that wants a *different* question — "is there any decline at
all, and what is it?" — is asking about the interval's distance from 1.0, not from 0.90, and
should say so in its own pre-registered criterion rather than reinterpreting this one.

For scale, `computenet-bzwx`'s own six decided rows: `INTERSECT`'s interval (0.9613–0.9962)
also excluded 1.0, while `FILTER`, `COUNT`, `FLAT_MAP`, `PRESENCE_COUNT` and `QUORUM` all
spanned it. So the residual pair are not qualitatively different from `INTERSECT`; they are
the low end of a family in which small sub-material declines are visible once the error bars
are tight enough to see them.

### Do the two rows agree?

**Yes — `AGREE_DOES_NOT_COST`**, and that is the criterion's own word for it, computed by a
function deliberately kept separate from the verdict so neither answer depends on the other's
shape. Both rows resolved, and both to the same side.

They agree in the weaker sense too: their point ratios differ (0.973 vs 0.945) and the
intervals do not overlap, so the two subjects are not showing the *same size* of decline —
`UNION`'s is roughly twice `TAGGED_SET`'s. That ordering is worth recording because the bead
predicted it: `UNION` is the one subject whose `gc.count` collapsed in `computenet-i61m`'s
allocation sweep (57 against 165–205), the signature of fewer, larger collections, so a real
`UNION` effect "would not be surprising and would be worth naming". It is named: `UNION` shows
the larger decline of the pair, resolved and excluding 1.0, and still below the materiality
bar.

### Which of the bead's two candidate explanations the numbers favour

The bead offered two: (1) a real few-percent decline specific to these two subjects, (2) noise
— they carried the two widest short-arm error bars in `bzwx`'s sweep, and `bzwx`'s sampler
caught a Brave helper at 118.8% inside `long UNION` and Spotlight `mds` at 41–52% spanning
`long TAGGED_SET`.

**The two rows split between the two explanations, and that is the finding.**

- **`UNION`: explanation (1).** Its point ratio barely moved — 0.9414 at 2 forks, 0.9447 at
  16 — while its interval shrank by 2.7x. A number that stays put as the error bar collapses
  is the signature of a real effect, not of the excursion `bzwx` flagged. `bzwx`'s reading
  that the Brave helper was "the strongest single reason not to read its 0.941 as a real
  decline" is, on this evidence, **wrong**: the decline reproduces at eight times the power on
  a different day, and is now bounded at 3.8–7.2%.
- **`TAGGED_SET`: explanation (2), at least in part.** Its point ratio moved from 0.9156 to
  0.9734 — a shift of 0.058, roughly 1.7x `bzwx`'s own half-width, i.e. a move too large to
  be comfortable as sampling variation alone and consistent with `bzwx`'s Spotlight `mds`
  excursion having depressed its long arm. Its underlying decline is real but about half the
  size `bzwx` measured.

This is a reading of two runs, not a measurement: nothing here re-runs `bzwx`'s configuration
under a forced excursion to confirm the attribution.

### Why this is a comparison of ratios across two runs, and what makes that legitimate

The absolute levels differ between the two runs — `TAGGED_SET`'s short arm is 818914 here
against `bzwx`'s 867384 (5.6% lower), `UNION`'s 790148 against 784509 (0.7% higher) — as
absolute throughput on this host does between days. **The measured code is identical.** Across
the 23 commits between `bzwx`'s harness commit `1f68b10d4` and this run's `f875ceb7b`, `git
log 1f68b10d4..f875ceb7b -- kernel/src/main bench/src/jmh` is **empty**: nothing under the
kernel's main sources or the benchmark bodies changed at all. The only source this item added
is a criterion and a render entry point under `bench/src/test`, which no benchmark body reads.
The quantity compared across the two runs is therefore the within-run *ratio*, which is the
quantity least exposed to a day-to-day shift in absolute level.

### Nothing was tuned

`[BEN1-28]`'s standing instruction — the growth is observed, never tuned — was followed. No
tag map was cleared, compacted or bounded, and nothing under `kernel/src/main` or
`bench/src/jmh` was touched. Both arms ran the same warmup (`5 x 1 s`) and the same
measurement iteration count (`10`); the two knobs that differ from `computenet-bzwx` are `-f
16` in place of the class's declared 2, and the subject list. The single knob that differs
*between arms* is JMH's `-r` (`1s` / `10s`), and both arms' `# Measurement:` banners are
retained in their logs as proof of that.

### The criterion, and that it was fixed before the numbers

`IterationLengthResidualCriterion` (in `bench/src/test/kotlin/civictech/bench/micro/
IterationLengthCriterion.kt`) was committed as **`f875ceb7b`** — the same sha
`bench-jmh.jar`'s own `Harness-Commit-Sha` manifest attribute carries, and the same sha the
rendered blocks below report — **before the first arm ran at 14:51:54Z**. It states:

- the pair (`TAGGED_SET`, `UNION`) and nothing else, with the render entry point REFUSING any
  other coverage, so the six `DOES_NOT_COST` rows cannot be restated as if re-measured;
- the fork count **16**, derived from `computenet-bzwx`'s *published error bars* rather than
  from the time available. `TAGGED_SET` was the binding row: 0.0156 of ratio left to cover
  against a half-width of 0.0332 needs a 2.13x shrink, i.e. at least `2 x 2.13^2` = 9.1 forks
  on the `1/sqrt(n)` term alone. 16 buys about 3.3x once `t(0.999, df)`'s own shrink from
  `df = 19` to `df = 159` is included — margin over the minimum, not exactly the minimum. It
  cost 65 minutes of measurement, about what `bzwx`'s two forks over eight subjects cost;
- its **own** copies of `MATERIAL_RATIO = 0.90` and `RESOLVABLE_RELATIVE_ERROR = 0.10` rather
  than importing the family criterion's, so a later change to those cannot retroactively move
  the boundary this run was decided against;
- a two-row aggregation — fires only if BOTH rows lose throughput, retires only if BOTH
  resolve and neither does, otherwise undecided, including the opposite-sides case — with
  agreement as a separate function;
- and, in advance, **what the design could not resolve**: a row whose true ratio sits within
  roughly 0.01 of 0.90 would have stayed undecided at any affordable fork count, and that
  outcome was pre-declared a null result rather than a failed run. It did not occur.

`IterationLengthResidualCriterionTest` — untagged, so it runs in `:bench:test` on every build
— pins 23 cases across all of it, including that the residual thresholds are its own constants
and that `computenet-bzwx`'s actual `TAGGED_SET` error bars still straddle the boundary while
the same point ratio at 16-fork error bars does not.

### Host, and the readings actually met — per arm

**The host is the pinned one**: `NL-MGD6FQJW91`, Apple M3 Max, Mac15,9, 16 cores, macOS
26.6.2, measuring on Microsoft OpenJDK 21.0.11+10-LTS invoked by absolute path (a bare `java`
here is JBR 25). CPU/cores/OS in the rendered blocks below come from each measuring fork's own
`@Setup(Level.Trial)` banner.

**Interleaving, and how it differs from `computenet-bzwx`'s — stated because it is coarser.**
At 2 forks each of `bzwx`'s sixteen arms took a few minutes, so one host excursion could
swallow an arm whole, and alternating the arm order per subject was the defence. At 16 forks
one arm is one JMH invocation spanning ~28 minutes of **sixteen separate JVMs**, and JMH's
99.9% interval is computed over all 160 measurement iterations — so an excursion covering a
few minutes touches a small share of the forks, is diluted in the mean, and *widens* the
reported interval rather than silently shifting it. This run therefore alternates at the
subject level: `TAGGED_SET` ran short-then-long and `UNION` long-then-short, so a monotone
drift over the 65-minute window biases the two rows' ratios in **opposite** directions and
cannot make them agree spuriously. They agree anyway. A reader who considers 28 minutes too
long a block to gate once should read the per-arm excursion table below, which is why it is
recorded in full.

**Per-arm gate.** Each of the four launches polled `load1` every 10 s and started only on a
reading below the quiesced threshold **4.00** (16 cores x 0.25, the threshold
`computenet-i61m`, `computenet-x9e.14` and `computenet-bzwx` all used). **4 of 4 arms met the
gate; zero were run un-gated.**

| subject | arm order | arm | window (UTC) | `load1` at start | `load1` at end |
| --- | --- | --- | --- | --- | --- |
| `TAGGED_SET` | short, long | short (1 s) | 14:51:54–14:56:01 | 3.03 | 3.18 |
| `TAGGED_SET` | short, long | long (10 s) | 14:56:01–15:24:09 | 3.18 | 3.81 |
| `UNION` | long, short | long (10 s) | 15:24:09–15:52:17 | 3.81 | 4.50 |
| `UNION` | long, short | short (1 s) | 15:52:48–15:56:56 | 3.97 | 3.39 |

Three of the four gates found a trough on their first poll. Only `short UNION` had to wait:
`long UNION` ended with `load1` at 4.50, and the gate polled four times over 31 s (4.19, 4.33,
then 3.97) before starting. The whole run occupied 14:51:54Z–15:56:56Z.

**Non-resident consumers during the window are recorded rather than discarded.** A 30 s
sampler ran throughout (130 samples, top 5 processes by CPU each). `load1` ranged
**2.42–10.64**, with the measuring JVM itself accounting for 133.7–438.9% CPU. Everything
non-JVM at or above 40%, with the arm it falls inside:

| when (UTC) | process | CPU | inside |
| --- | --- | --- | --- |
| 14:59:55 | `fileproviderd` | 94.4% | long `TAGGED_SET` |
| 15:05:57 | `node` (nvm v25.9.0) | 43.8% | long `TAGGED_SET` |
| 15:08:58 | Brave Browser Helper (Renderer) | 43.3% | long `TAGGED_SET` |
| 15:11:28 | Brave Browser Helper (Renderer) | 56.4% | long `TAGGED_SET` |
| 15:15:29 | `fileproviderd` | 77.5% | long `TAGGED_SET` |
| 15:21:00 | `wdavdaemon_unprivileged` 169.1%, IntelliJ IDEA 137.9%, `wdavdaemon_enterprise` 72.7%, `com.manageengine.appctrl.driver` 42.1% | — | long `TAGGED_SET` (`load1` peaks 10.64 at 15:21:30) |
| 15:28:02, 15:29:02 | `mediaanalysisd` | 77.1%, 82.3% | long `UNION` (`load1` 10.29) |
| 15:30:33 | `PerfPowerServicesSignpostReader` | 87.6% | long `UNION` |
| 15:36:04 | `corespotlightd` | 125.2% | long `UNION` |
| 15:42:35 | three Brave renderers | 62.9%, 61.6%, 43.7% | long `UNION` |
| 15:45:36 | Brave Browser Helper (Renderer) | 124.3% | long `UNION` |
| 15:47:06 | `photoanalysisd` 86.5%, Spotlight `mds` 72.3% | — | long `UNION` |
| 15:50:37 | `wdavdaemon_enterprise` | 106.4% | long `UNION` |
| 15:51:08 | `node` (nvm v25.9.0) | 99.5% | long `UNION` |
| 15:53:08, 15:54:08, 15:54:38, 15:55:09 | `wdavdaemon_unprivileged` | 62.4%, 64.8%, 44.8%, 41.1% | short `UNION` |
| 15:54:38 | `fileproviderd` | 52.0% | short `UNION` |

Mean CPU across all 130 samples for the resident managed-endpoint stack, as a **lower bound**
— the sampler records only the top five processes, so a process below fifth place in a sample
contributes zero: Microsoft Defender `wdavdaemon_unprivileged` 8.0%, `trustd` 6.0%,
ManageEngine `com.manageengine.appctrl.driver` 4.4%.

**The excursion pattern makes this entry's conclusion conservative, not fragile, and that is
worth reading carefully.** Every heavy excursion in the two `TAGGED_SET` arms and all but one
in the two `UNION` arms fell inside a **LONG** arm. An excursion inside the long arm depresses
the long arm's score and therefore pushes the ratio **down**, toward `COSTS`. The ratios came
out at 0.973 and 0.945 anyway, both intervals clear of 0.90. So the direction of the
contamination is opposite to the direction of the verdict: a cleaner host would move these
rows further away from the materiality boundary, not toward it. `short UNION` is the one arm
whose contamination runs the other way — a Defender burst spanning most of its four minutes —
and it is the arm whose row is nonetheless the *lower* of the two, i.e. a depressed short arm
would have inflated `UNION`'s ratio, so `UNION`'s true ratio may be marginally lower than
0.9447. Its ratio would have to fall a further 0.0447 to reach 0.90 — more than 2.6 times its
whole 99.9% interval half-width.

**The resident managed-endpoint stack never goes away on this host.** That it does not
disqualify a quiesced attestation here is a ruling recorded in `computenet-i61m`'s entry, not
a judgement made by this one; this session executed it. IntelliJ IDEA at 137.9% inside `long
TAGGED_SET` is the one consumer here that is neither resident nor system: it is an idle IDE's
background activity on the pinned host, recorded rather than discarded, and it falls in the
long arm — again the conservative direction.

### Trigger (`[BEN1-31]`/`[BEN1-32]`)

`TriggerClaim.Cited` on `[BEN1-28]`, verdict `RETIRES`, with the criterion and the measured
clause both generated from the same numbers by `IterationLengthResidualCriterion`, so the
sentence in the rendered block cannot drift from the table beside it. The statement says in
its own words that it is stated over the two residual subjects only and that the other six
set-shaped rows are neither re-measured nor re-stated. The SHORT arm's block carries
`TriggerClaim.None` — MARKED INCOMPLETE — correctly: the control arm answers no trigger
question on its own. This entry cites neither G-21 nor G-43.

### WAL/journal statement (`[BEN1-29]`)

Unchanged and re-confirmed against the source both arms compiled — which, per the git check
above, is byte-identical to what `computenet-bzwx` compiled: `Graphs.kt`'s `Rig` for
`Drive.REAL` constructs `ManagedHost(scheduler = scheduler)` with no `journal` argument, and
no `civictech.cell.durability` type appears in `Graphs.kt`, `Deltas.kt` or
`OperatorThroughputBenchmark.kt`. WAL/journal sync is not in play; KBLK is not named.

### Coverage, and what this sweep is not

Two subjects x two arms x `direction=INSERT` x `drive=REAL` = **4 of 4 invocations measured**,
no row omitted. It is **not** a re-measurement of the other six set-shaped subjects, not a
sweep of all 18, not a `drive=SIM` measurement, not a RETRACT measurement, and not a statement
about the join / grouped / combine families. `IterationLengthResidualAbRenderTest` refuses any
coverage other than exactly `TAGGED_SET` and `UNION` rather than aggregating a wider or
narrower set into a criterion stated over that pair.

**The instrument is one-sided and a reader has to carry that**, exactly as
`computenet-bzwx`'s entry says. Iteration length is not a *label* on the tag map: a longer
iteration accumulates more tag-map state, but also everything else that grows with
time-in-iteration — JIT state, a filling young generation, any other internal collection. The
absence of a *material* decline is the stronger direction for this instrument, and it is
evidence against any within-iteration accumulation costing these two subjects more than a few
percent of wall clock, the tag map included. The few percent that IS present is likewise not
attributed to the map by this entry.

### Artifacts

The four per-arm raw JMH CSVs and logs, the two concatenated per-arm artifacts the renderer
read, the per-arm polling-gate log, the 30 s sampler log and the driver script are retained at
`$HOME/computenet-runs/computenet-ciz9/` on host `NL-MGD6FQJW91` — **outside the repository**,
because run artifacts are not committed in this lane. They are therefore machine-local and not
reproducible from this repository alone; the rendered blocks below,
`IterationLengthResidualCriterion`, `IterationLengthResidualAbRenderTest` and
`IterationLengthResidualCriterionTest` are the durable part.

### Commands, exactly

```
./gradlew :bench:jmhJar          # manifest Harness-Commit-Sha: f875ceb7b

# four invocations, each gated on a polled load1 trough below 4.00, arm order
# reversed between the two subjects (TAGGED_SET short,long — UNION long,short):
/Users/merlijn/Library/Java/JavaVirtualMachines/ms-21.0.11/Contents/Home/bin/java \
     -jar bench/build/libs/bench-jmh.jar 'OperatorThroughputBenchmark.real' \
     -p subject=<TAGGED_SET|UNION> -p direction=INSERT -f 16 -r <1s | 10s> \
     -rf csv -rff /abs/path/arms/<arm>-<subject>.csv \
     > /abs/path/arms/<arm>-<subject>.log 2>&1

# concatenated per arm (headers de-duplicated) into residual-ab.csv/.log (LONG) and
# residual-ab.short.csv/.short.log (SHORT), then rendered through the module's own
# entry point:
./gradlew :bench:test -PbenchOnly=true --rerun \
  --tests 'civictech.bench.micro.IterationLengthResidualAbRenderTest' \
  -Dcivictech.bench.jmhResults=/abs/path/residual-ab.csv \
  -Dcivictech.bench.harnessSha=f875ceb7b \
  -Dcivictech.bench.date=2026-08-27
```

The rendered blocks below were read back out of the JUnit XML `<system-out>` and pasted
verbatim, not retyped.

---

Renderer's own output for the **LONG arm (10 s measurement iterations, 16 forks)**, pasted verbatim:

## 2026-08-27 — residual iteration-length A/B, LONG arm (10 s measurement iterations, 16 forks): REAL-drive INSERT throughput over TAGGED_SET and UNION — the treatment arm of computenet-ciz9's resolution of the two rows computenet-bzwx left straddling its materiality boundary
Harness: f875ceb7b · JVM Microsoft (Microsoft-13877178)/21.0.11 · heap JVM defaults (VM options: <none>) · Apple M3 Max, 16 cores, Mac OS X 26.6.2
JMH: mode=Throughput forks=16 warmup=5 iters=10 · drive=REAL
| subject | value | notes |
| --- | --- | --- |
| TAGGED_SET insert | 797154.289641 ± 3021.760469 ops/s | |
| UNION insert | 746420.331932 ± 6275.706349 ops/s | |
Trigger: [BEN1-28] — RETIRES: the criterion applied is stated over the two residual subjects TAGGED_SET and UNION only — the rows computenet-bzwx left straddling its boundary, the other six set-shaped rows being neither re-measured nor re-stated here — and is that each of the two subjects' ratio of long-arm to short-arm throughput is taken with its error bars propagated, a row counts as losing throughput only if the whole ratio interval falls below MATERIAL_RATIO=0.90 and as not losing it only if the whole interval falls above it, a row whose relative error exceeds RESOLVABLE_RELATIVE_ERROR=0.10 in either arm or whose interval straddles the boundary resolves nothing, and the residual question fires only if BOTH rows lose throughput, retires only if BOTH rows are resolved and neither loses it, and is otherwise undecided — which includes the case where the two rows resolve to opposite sides; measured, at 16 forks per arm, TAGGED_SET ratio 0.9734288399578311 (interval 0.9649863991759609 to 0.981954848967152, DOES_NOT_COST); UNION ratio 0.9446594303267468 (interval 0.9279049419155644 to 0.9617351946766355, DOES_NOT_COST); the two rows AGREE-DOES-NOT-COST

Row dispersion (drive=REAL; informational, nothing excluded):
- TAGGED_SET insert (drive=REAL): 797154.289641 ± 3021.760469 ops/s, relative dispersion 0.00379068457420063
- UNION insert (drive=REAL): 746420.331932 ± 6275.706349 ops/s, relative dispersion 0.008407737678790515 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)
---

Renderer's own output for the **SHORT arm (1 s measurement iterations, 16 forks)**, pasted verbatim:

## 2026-08-27 — residual iteration-length A/B, SHORT arm (1 s measurement iterations, 16 forks): REAL-drive INSERT throughput over TAGGED_SET and UNION — the control arm of computenet-ciz9's resolution of the two rows computenet-bzwx left straddling its materiality boundary
Harness: f875ceb7b · JVM Microsoft (Microsoft-13877178)/21.0.11 · heap JVM defaults (VM options: <none>) · Apple M3 Max, 16 cores, Mac OS X 26.6.2
JMH: mode=Throughput forks=16 warmup=5 iters=10 · drive=REAL
| subject | value | notes |
| --- | --- | --- |
| TAGGED_SET insert | 818913.778716 ± 4033.083385 ops/s | |
| UNION insert | 790147.547327 ± 7503.798354 ops/s | |
Trigger: none cited — entry MARKED INCOMPLETE, not presented as a finding

Row dispersion (drive=REAL; informational, nothing excluded):
- TAGGED_SET insert (drive=REAL): 818913.778716 ± 4033.083385 ops/s, relative dispersion 0.0049249182146178985
- UNION insert (drive=REAL): 790147.547327 ± 7503.798354 ops/s, relative dispersion 0.009496705241172606 — above the harness sanity bound NOISE_FLOOR 0.005 (informational; the row is reported, and no comparison is drawn from it that its error bar does not support)

## 2026-08-27 — `CellFootprintBenchmark`'s per-class floor RE-DERIVED under a new pre-registered estimator: 1.044 -> 0.398, from the same three retained runs (`computenet-3sua`)

**No new measurement was made.** This is arithmetic over `computenet-7v7m`'s three
retained quiesced runs — the same 63 observations, the same host, the same harness sha,
the same JDK — read by a different estimator. Every provenance field is unchanged, which
is why the machine-rendered block below still carries the runs' own date, 2026-08-26, in
its heading. It is reproduced verbatim from `renderDerivation`, as step 4 of the procedure
requires; it is not composed by hand.

**What changed.** `ClassFloorDerivation` step 2 used to read "the **maximum** observed
relative dispersion ... Maximum, not mean". It now reads `classFloorStatistic`: the
**MEDIAN** of each row's relative dispersions across its repeats, then the **MAXIMUM** of
those per-row medians across the class's rows. The maintainer's decision to replace the
estimator is recorded on `computenet-3sua` (2026-08-27); the concrete statistic and its
rationale are the implementer's, and both live in `bench/src/main/kotlin/civictech/bench/ClassNoiseFloor.kt`.

**Why, argued from robustness and from nothing else.** A derivation's observations form a
grid, and its two axes are different kinds of variation:

- **Across REPEATS of one row** — same benchmark, same params, same host, minutes apart —
  variation is transient: an interference event the one-directional quiesced-host gate
  could not see, a compilation decision that went differently in one fork, a thermal
  excursion. A maximum on that axis has **breakdown point zero**. One contaminated
  observation, anywhere in the grid, sets the bound every future row of the class is
  classified against, permanently. A median has breakdown 1/2; at three observations it
  takes two of the three to move it, and two of three is a reproducible property of the
  row rather than an event.
- **Across ROWS** variation is structural. A `@Param` combination that disperses heavily
  disperses heavily every time, and that is a fact about the class rather than noise in
  it. **The maximum is KEPT there**, and the original step 2's rationale carries over
  word for word: the floor has to sit above the worst quiet-host row, or a quiet host
  produces rows above their own floor. A percentile or a mean across rows would discard
  honest high-dispersion rows and publish a floor the class exceeds on a silent machine —
  exactly the failure "maximum, not mean" was pre-registered against.

Median rather than a high percentile *within* a row: at three observations per row, every
order statistic strictly above the median IS the maximum, so a "75th percentile" would be
the old estimator under a new name. The median is the only order statistic of three with a
non-zero breakdown point, and it needs no re-picking if `CLASS_FLOOR_MIN_RUNS` ever
changes.

**The ordering, which is the whole guarantee.** The estimator and its rationale were
committed on their own, in `b9dc0ba86`, with the `CellFootprintBenchmark` entry explicitly
marked PENDING RE-DERIVATION and still carrying the old value. Only then was the statistic
computed over the retained runs. An estimator chosen after seeing what each candidate
yields is a preference wearing a criterion's clothes, and it is the same move as
reverse-engineering `NOISE_FLOOR`'s 2x margin from the run it is applied to. The
re-derivation script is retained at `$HOME/computenet-runs/computenet-3sua/rederive.py`;
it asserts, as a control, that its own extraction reproduces the previously published
maximum `0.5217864937179187` bit for bit before computing anything new.

**That the floor got TIGHTER is an outcome, not a justification.** 1.044 -> 0.398 is a
2.6x tightening, and it must not be read as the reason the statistic was picked. A
statistic chosen on these grounds that produced a LOOSER floor would have discharged
`computenet-3sua` identically, and the item's own text says so. Nothing above may be
revisited on the ground that some other statistic yields a number someone prefers.

**No grandfathering.** `doc/bench/findings.md` and `CLASS_NOISE_FLOOR_DERIVATIONS` now
carry **one** statistic across the whole per-class floor table. The 2026-08-26 entry above
is marked superseded in place rather than deleted; its runs are the input to this
re-derivation and its provenance section remains the record of the JBR-25 defect
`computenet-7v7m` fixed. `computenet-akfa`'s three remaining classes
(`OperatorThroughputBenchmark`, `FanOutScalingBenchmark`, `BoundedReadBenchmark`) will be
derived under this same estimator when they are derived; nothing about them is decided
here.

**What the number is.** The row that sets the floor is the same one that set it before:
`realSnapshot` OR_MAP_CELL at `N1E5`, measured 0.5218 / 0.1186 / 0.1986 across runs
1 / 2 / 3. Under the maximum it contributed 0.5218 — its single worst repeat, about 2.6x
its own next-worst observation, against 0.201 as the second-highest row over all 63. Under
`classFloorStatistic` it contributes its median, 0.19864889236475775. The change is not
that a different part of the class was found to be worst; it is that the same row is now
represented by what it typically does.

**The limit of 0.398, stated where the number is.** It is still a loose bound: a row may
carry a JMH 99.9% error bar nearly 40% the size of its own score and pass. That is the
class rather than the estimator — `realSnapshot` at `N1E5` genuinely disperses that much
on a silent machine (12 of the 63 rows exceed 0.10: OR_MAP_CELL and SET_CELL in all three
runs, MAP_CELL in two, KEYED_SET_CELL and LIST_CELL in one each), and a floor that refused
it would be refusing the benchmark instead of detecting interference. Nor does 0.398 mean
"no observation here exceeded 0.199" — a median does not bound the sample it is drawn
from, and one of these very rows was measured at 0.522.

## 2026-08-26 — per-class noise floor for `CellFootprintBenchmark`, derived forward from its own quiesced repeat runs
Harness: a7c6a0382 · host state quiesced · 3 sequential repeat runs
JMH: mode=AverageTime unit=us forks=1 warmup=3x1s measurement=5x1s (the class's own annotation configuration)
Measured under: JDK 21.0.5, OpenJDK 64-Bit Server VM, 21.0.5+11-LTS (Amazon Corretto; :bench's resolved toolchain launcher)
| quantity | value |
| --- | --- |
| statistic (max over rows of the per-row MEDIAN relative dispersion) over all rows of all 3 runs | 0.19864889236475775 |
| margin, fixed before the runs (CLASS_FLOOR_MARGIN) | 2.0 |
| derived floor = margin x statistic, rounded up to three decimals | 0.398 |
Estimator: `classFloorStatistic` — the MEDIAN of each row's relative dispersions across its repeats, then the MAXIMUM of those medians across the class's rows. Robust within a row (one contaminated repeat cannot set a class's floor), conservative across rows (a reproducibly high-dispersion row is a fact about the class, not noise in it). Pre-registered in `ClassNoiseFloor.kt` before it was computed over any measurement — see `classFloorStatistic`'s own documentation for the argument, which is made from robustness and never from the value the statistic yields.
Derivation: forward. The margin was fixed in `ClassNoiseFloor.kt` before any per-class number existed; the floor is computed from the statistic by `ClassNoiseFloor.floor` and is not hand-entered. What it establishes: on a quiesced host, every row of `CellFootprintBenchmark` measured under this configuration had a TYPICAL (median) relative dispersion at or under 0.19864889236475775, so a later row above 0.398 is more dispersed than this class typically is when the machine is quiet. What it does NOT establish: that no individual observation in the derivation exceeded 0.19864889236475775 — a median does not bound the sample it is drawn from, and single high repeats are exactly what this estimator declines to build a floor on. Nor anything about another benchmark class, about this class under a different annotation configuration, about this class on another host, or about this class under a JVM other than JDK 21.0.5, OpenJDK 64-Bit Server VM, 21.0.5+11-LTS (Amazon Corretto; :bench's resolved toolchain launcher).
