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
