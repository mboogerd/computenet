package civictech.bench

import kotlin.math.abs
import kotlin.math.ceil

/**
 * Per-benchmark-class noise floors, and the machinery that resolves one
 * (`computenet-cm4w`).
 *
 * ## The defect this exists to close
 *
 * [NOISE_FLOOR] was derived from `SmokeBenchmark.baseline` — a 4.3 ns branch-free bit
 * mixer — and then inherited by every benchmark class in the module. It therefore says
 * nothing about what `OperatorThroughputBenchmark` or `FanOutScalingBenchmark` can
 * achieve on this hardware: a hosted graph runs 0.01–0.15 relative dispersion for
 * structural reasons, so measuring it against a bit mixer's floor produces a bound that
 * fires on *every* row and so distinguishes nothing. The 2026-08-21 findings review
 * measured that: 66 of 72 throughput rows and all 10 fan-out rows sat above 0.005. A
 * bound a benchmark class cannot clear even when the host is silent is not detecting
 * interference; it is reporting that a hosted graph is not a bit mixer.
 *
 * A floor derived from a class's own repeat-run history restores the signal: a row above
 * *that* class's floor is more dispersed than the same benchmark was on a quiet machine,
 * which is what "interference or a confound" actually means.
 *
 * ## Status: TWO classes derived, two still falling back
 *
 * [CLASS_NOISE_FLOOR_DERIVATIONS] holds two entries.
 *
 * - `CellFootprintBenchmark`, whose runs were made 2026-08-26 (`computenet-ahn0`,
 *   **re-measured the same day under the toolchain JDK by `computenet-7v7m`** after the
 *   first three runs turned out to have been measured under JBR 25; see step 1 below) and
 *   whose floor was **re-derived from those retained runs on 2026-08-27 by
 *   `computenet-3sua`** when [classFloorStatistic] replaced the maximum.
 * - `BoundedReadBenchmark`, derived 2026-08-27 by `computenet-akfa` from three sequential
 *   whole-class quiesced runs made under [classFloorStatistic] from the outset — the
 *   first class whose observations were gathered after the estimator was fixed, so
 *   nothing about it is a re-reading of an older set. It is the cheapest of the three
 *   classes the procedure still named as undrived (6 rows, ~5 minutes per run), and it
 *   was seated on its own dispatch rather than alongside another class because the
 *   statistic is taken across all three runs of a class and a part-class derivation is
 *   not a checkpoint.
 *
 * The other two classes the procedure names (`OperatorThroughputBenchmark`,
 * `FanOutScalingBenchmark`) have **no** derivation and therefore still fall back to
 * [NOISE_FLOOR]. That is not an oversight and no number may be entered for them that did
 * not come from their own three quiesced runs: deriving a floor requires three sequential
 * repeat runs of the class on a **quiesced** host, and a floor derived through
 * interference would be a floor measuring the interference, silently inherited by every
 * row later classified against it.
 *
 * The two that remain were not attempted because they do not fit one dedicated slot.
 * Measured at the classes' own annotation configurations on 2026-08-26:
 * `OperatorThroughputBenchmark` is 72 rows at `@Fork(2)` x 15s = ~48 min per run (~144
 * min for three) and `FanOutScalingBenchmark` 30 rows at up to `@Fork(5)` (~27 min per
 * run) — against `CellFootprintBenchmark`'s measured 177s per run and
 * `BoundedReadBenchmark`'s measured ~5 min per run (6 rows at `@Fork(5)`, estimated ~7
 * min on 2026-08-26 and timed at 00:05:13 by all three runs' own JMH
 * `# Run complete. Total time:` lines). Shrinking any of those configurations to fit a
 * slot is exactly the thing the
 * procedure forbids, so they are routed to their own dedicated slots instead.
 *
 * The format, the margin and the resolution rules were fixed HERE, in committed source,
 * **before** any of these numbers existed, for the same reason [NOISE_FLOOR]'s 2x margin
 * was fixed before its first run reported a number — so neither can be reverse-engineered
 * from the measurement it is applied to.
 *
 * ## The derivation procedure, fixed in advance
 *
 * For each benchmark class, at **that class's own annotation configuration** (its
 * declared `@Fork`/`@Warmup`/`@Measurement`/`@BenchmarkMode`, not a config chosen to make
 * the number smaller):
 *
 * 1. `./gradlew :bench:jmhJar` once, then **three** executions of
 *    `bench/build/libs/bench-jmh.jar` covering that class — sequentially, or decomposed
 *    into row-set units as the "Decomposition" section below permits — under the module's
 *    declared toolchain JDK, on a host attested [QUIESCED_HOST_STATE] (`run-series.sh`'s
 *    one-directional guard: 1-minute load average at or below 0.25 x core count, plus the
 *    operator's own attestation that no other session, build or scheduled scan is live —
 *    the guard can refuse a wrong claim and can never confirm a right one).
 *
 *    **Launch that JDK by absolute path, and read each run log's own `# VM version:`
 *    banner before trusting its numbers** (`computenet-7v7m`). A bare `java` is not the
 *    toolchain: on this repository's own host it is JBR 25.0.2 while the toolchain is 21,
 *    and the first derivation of `CellFootprintBenchmark` was measured under it — three
 *    runs, all three logs banner-stamped `JDK 25.0.2`, every results row stamped
 *    `jdkVersion` 25.0.2 — and published as a floor that would be applied to rows
 *    `run-series.sh` forces to be measured under 21. `run-series.sh` refuses a non-21
 *    launcher (`PINNED_JDK_MAJOR`); invoking the jar directly bypasses that refusal, so
 *    the banner check is the derivation's own copy of it. The banner is the artifact:
 *    `grep -m1 -E '^# VM version' <log>`.
 * 2. Compute [classFloorStatistic] over the observations: for each ROW, the **median**
 *    of that row's relative dispersions (`scoreError / score`, JMH's 99.9% bar over its
 *    own score) across its [CLASS_FLOOR_MIN_RUNS] observations; then the **maximum** of
 *    those per-row medians across the class's rows. Robust WITHIN a row, conservative
 *    ACROSS rows — see [classFloorStatistic] for why the two axes get different
 *    treatment, and `computenet-3sua` for the decision that replaced the previous
 *    "maximum over every observation".
 * 3. The floor is that statistic times [CLASS_FLOOR_MARGIN], rounded UP to three decimals
 *    ([roundUpToThreeDecimals]) — computed by [ClassNoiseFloor.floor], never hand-typed.
 * 4. Append the derivation to `doc/bench/findings.md` as its own entry, rendered by
 *    [renderDerivation] so the entry cannot drift from the constant.
 *
 * A class with no such derivation has no floor, and its rows are classified against
 * [NOISE_FLOOR] — visibly the global bound, in the message [describeFloor] renders.
 * Falling back is the honest state; inventing a class floor by analogy to another class,
 * or by scaling one, is not a derivation and must not enter
 * [CLASS_NOISE_FLOOR_DERIVATIONS].
 *
 * ## Decomposition: the unit is the ROW SET (amendment, 2026-08-26, `computenet-3omz`)
 *
 * Step 1 above originally read "three **sequential** executions of the jar filtered to
 * that class", which admits only one shape: a class completed in one uninterrupted
 * stretch. Sized at the three un-derived classes' own annotation configurations that is
 * about four hours of continuous gated measurement, and the pinned host is a laptop in
 * daily interactive use whose owner has stated (2026-08-26) that a four-hour idle block
 * is marginal. A procedure that only completes as one stretch does not complete at all,
 * so it is amended here — **in committed source, before any number derived under the
 * amended form exists**, which is the same forward discipline the margin and the format
 * were fixed under, and the reason this text lands three dependency edges before the task
 * that first runs a decomposed derivation.
 *
 * **What the amendment permits.** A class's derivation may be completed across MULTIPLE
 * short quiesced windows. The unit of decomposition is the **ROW SET**: each window
 * measures a SUBSET of the class's rows, and the class is done when every row of the
 * class has been measured exactly [CLASS_FLOOR_MIN_RUNS] times in [CLASS_FLOOR_MIN_RUNS]
 * separate JVM invocations. This is sound because the derived quantity ([classFloorStatistic])
 * decomposes by row: a row's median depends only on that row's own observations, and the
 * across-row fold is a `max`, which is associative and commutative — so folding over rows
 * measured at different times yields the same number as folding over all of them at once.
 * JMH forks per (benchmark, `@Param`) combination, so a row's own measurement does not
 * depend on which other rows shared its invocation. (The decomposition argument was
 * originally written for a plain maximum over every observation; `computenet-3sua`
 * changed the estimator and the argument survives it unchanged in force, because the new
 * statistic is still computed per row and still folded across rows by `max`.)
 *
 * **What the amendment does NOT permit, stated so it cannot be read as a loophole.**
 *
 * - Every row still runs at its class's OWN annotation configuration, three times. Row
 *   selection — a benchmark regex, or `-p` naming a value the class already declares —
 *   chooses WHICH rows run, never HOW a row is measured.
 * - **No threshold, fork count, iteration count, iteration DURATION, thread count,
 *   benchmark mode or margin may be changed to make a unit fit a window.** Not `-f`, not
 *   `-wi`, not `-i`, not `-w`, not `-r`, not `-t`, not `-bm`, not [CLASS_FLOOR_MARGIN],
 *   not [NOISE_FLOOR]. The list is illustrative and the rule is not: the ONLY thing a
 *   unit may vary is which rows it measures. A unit too long for the available window is
 *   split into fewer rows, and never into a cheaper configuration — including a
 *   configuration that keeps the iteration COUNTS and shortens the iterations, which is
 *   the same shrink wearing a different flag. This amendment is to SCHEDULING and to
 *   nothing else; it must never become the route by which a configuration is shrunk.
 * - The estimator is untouched BY THIS AMENDMENT: whatever [classFloorStatistic] is, the
 *   floor is still [CLASS_FLOOR_MARGIN] x it, still [roundUpToThreeDecimals]. Whether the
 *   maximum-over-every-observation was the right statistic was a separate question
 *   (`computenet-3sua`); it has since been answered, and the answer changed
 *   [classFloorStatistic] — but nothing in this scheduling amendment participated in that
 *   choice, then or now.
 *
 * **What decomposition costs, stated rather than assumed.** Rows measured in windows
 * hours apart see different ordering and different thermal state than rows measured back
 * to back. Nothing has measured whether that matters for these classes. It is a known
 * difference in the measurement, not a defect the amendment repairs.
 *
 * **Per-unit obligations.** Everything step 1 requires per run is now required per UNIT:
 *
 * - The host gate (`run-series.sh`'s 1-minute load average at or below 0.25 x core count)
 *   is attested immediately BEFORE each unit. A gate reading taken before the first unit
 *   says nothing about the host when the fourth ran.
 * - Each unit's own log's `# VM version:` banner is verified after the fact
 *   (`grep -m1 -E '^# VM version' <log>`) — the per-unit copy of the check
 *   `computenet-7v7m` added per run.
 *
 * **Two refusals a decomposed derivation must carry**, both implemented by
 * [FloorDerivationLedger], which is the sanctioned way to accumulate one:
 *
 * 1. **A floor may be rendered only from a COMPLETE row set** — every row of the class,
 *    [CLASS_FLOOR_MIN_RUNS] observations each — and the refusal must NAME the outstanding
 *    rows and their counts. A maximum over a subset of ROWS can only be SMALLER than the
 *    maximum over the whole set, so a floor rendered from a partial row set is
 *    systematically too LOW, which is the direction that admits rows the floor should
 *    have refused; and a SHORT row — fewer than [CLASS_FLOOR_MIN_RUNS] observations — has
 *    no robustness left in its median, so it can bias [classFloorStatistic] either way.
 *    Completeness must be checked against a row universe pre-registered independently of
 *    the enumeration (`EXPECTED_PLAN_ROW_COUNTS`): completeness computed over a filtered
 *    enumeration is satisfied vacuously.
 * 2. **A floor may be rendered only from a row set measured under ONE JVM version.** With
 *    units spread over days the measuring JDK can change between them. That is not
 *    hypothetical: `computenet-ahn0` derived `CellFootprintBenchmark`'s first floor under
 *    JBR 25.0.2 rather than the toolchain's JDK 21 — a bare `java` on this host is JBR 25
 *    and `JAVA_HOME` is unset — and re-deriving under 21 moved the floor from 0.593 to
 *    1.044. (Both of those are old-estimator numbers, from before `computenet-3sua`
 *    replaced the maximum with [classFloorStatistic]; the size of the JVM's effect is the
 *    point, and it is not re-derived here because a superseded measurement is not
 *    re-published.)
 */
object ClassFloorDerivation {

    /** Documentation anchor only; see this object's KDoc. */
    const val PROCEDURE_OWNER: String = "computenet-cm4w"

    /**
     * The work item that amended step 1 to admit row-set decomposition. Documentation
     * anchor only; see this object's "Decomposition" section.
     */
    const val DECOMPOSITION_OWNER: String = "computenet-3omz"
}

/**
 * How much headroom a derived per-class floor carries over the worst relative dispersion
 * its three quiesced runs actually observed.
 *
 * **The margin is 2x, and it is fixed here before any per-class number exists** — the
 * condition [NOISE_FLOOR]'s KDoc states for any amendment to this file's family of
 * criteria, and the same discipline `computenet-bzwx` used for
 * `IterationLengthCriterion`'s thresholds.
 *
 * Why 2x, and why the *same* 2x as the global bound rather than a fresh choice: the
 * per-class floor and [NOISE_FLOOR] are the same construction applied to different
 * subjects, and a reader comparing a class floor to the global one should be reading a
 * difference in the *measurement*, not a difference in the margin. Changing the base and
 * the margin together would make that comparison uninterpretable. The justification for
 * the size carries over unchanged: three runs on a deliberately idle host, with error
 * bars that measure dispersion *within* a run rather than across runs, is a lower bound
 * three times over, so the floor must sit above it or the class fires on itself. One
 * binary order is the smallest headroom that admits that structural gap while still
 * refusing a row more than twice as dispersed as the class's own quiet-host worst case.
 */
const val CLASS_FLOOR_MARGIN: Double = 2.0

/**
 * The one host state under which a per-class floor may be derived.
 *
 * [ClassNoiseFloor] refuses to be constructed with any other value, so a floor derived on
 * a shared host cannot be represented at all — the refusal is structural rather than a
 * convention someone has to remember. It matches `scripts/bench-series/run-series.sh`'s
 * `--host-state quiesced` attestation, which is where the gate is actually enforced at
 * run time.
 */
const val QUIESCED_HOST_STATE: String = "quiesced"

/** The fewest sequential repeat runs a derivation may rest on. */
const val CLASS_FLOOR_MIN_RUNS: Int = 3

/**
 * The middle value of [values] — the mean of the two middle values at even size.
 *
 * The even-size rule is stated here, in committed source, rather than left to whichever
 * convention a later reader assumes, because [CLASS_FLOOR_MIN_RUNS] is not fixed forever:
 * a derivation resting on four observations per row must not be able to pick between two
 * defensible medians after seeing which one it prefers.
 */
fun medianOf(values: List<Double>): Double {
    require(values.isNotEmpty()) { "median of an empty sample is undefined" }
    require(values.all { it.isFinite() }) {
        "every observation must be finite, was $values"
    }
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
}

/**
 * The per-class floor's estimator: the **maximum, over the class's rows, of each row's
 * MEDIAN relative dispersion across that row's repeat observations** (`computenet-3sua`).
 *
 * Pre-registered here, in committed source, **before it was computed over any measurement
 * whatsoever** — including before it was computed over `CellFootprintBenchmark`'s three
 * retained runs, whose re-derivation is the same work item. That ordering is the whole
 * content of the guarantee: an estimator chosen after seeing what each candidate yields is
 * not a criterion, it is a preference wearing one, and it is the identical move to
 * reverse-engineering [NOISE_FLOOR]'s 2x margin from the run it is applied to. Nothing
 * below argues from a value, and nothing below may be amended by an argument from one.
 *
 * ## Why the two axes are treated differently
 *
 * A derivation's observations form a grid: one axis is the class's ROWS (its `@Benchmark`
 * x `@Param` combinations), the other is the REPEATS of each row. The previous estimator —
 * a single maximum over the flattened grid — treated both axes as one, and that is what
 * this replaces. They are not the same kind of variation.
 *
 * **Across REPEATS of one row, variation is transient.** Same benchmark, same parameters,
 * same configuration, same host, minutes apart. Whatever moves a row between its own
 * repeats is a property of the moment, not of the class: an interference event the
 * quiesced-host gate could not see (that gate is one-directional and can only ever refuse
 * a wrong claim — see [QUIESCED_HOST_STATE]), a compilation decision that went differently
 * in one fork, a thermal excursion. On that axis a maximum has **breakdown point zero**:
 * one contaminated observation, anywhere in the grid, sets the floor that every future row
 * of the class is classified against, forever. The median has breakdown 1/2 — at three
 * observations it takes TWO of the three to move it, and two of three is no longer an
 * isolated event but a reproducible property of the row, which is precisely what a floor
 * should be built from.
 *
 * **Across ROWS, variation is structural, and the maximum is KEPT.** Different `@Param`
 * combinations are genuinely different workloads; a row that disperses heavily disperses
 * heavily every time, and that is a fact about the class rather than noise in it. The
 * original step 2's rationale applies unchanged on this axis and is retained verbatim in
 * force: *the floor has to sit above the worst quiet-host row, or a quiet host produces
 * rows above their own floor.* A high percentile across rows, or a mean, would discard
 * honest high-dispersion rows and publish a floor the class exceeds on a silent machine —
 * exactly the failure the maximum was pre-registered against. So: robust WITHIN a row,
 * conservative ACROSS rows.
 *
 * ## Why the median, and not a high percentile, within a row
 *
 * At [CLASS_FLOOR_MIN_RUNS] = 3 observations, every order statistic strictly above the
 * median IS the maximum (or an interpolation dominated by it), so a "75th percentile"
 * would be the old estimator under a new name. The median is the only order statistic of
 * three with a non-zero breakdown point. It also survives a change to
 * [CLASS_FLOOR_MIN_RUNS] without being re-chosen: at more observations per row it stays
 * the same definition and its breakdown point stays 1/2, whereas a percentile would have
 * to be re-picked — and re-picking an estimator is exactly the act this pre-registration
 * exists to make unavailable.
 *
 * ## What this does and does not buy
 *
 * It removes the single-contaminated-observation failure mode. It does NOT make a floor
 * tight: a class whose rows reproducibly disperse heavily still gets a high floor, and
 * that is the finding rather than a defect in it. Whether any given class's floor comes
 * out tighter or looser than it did under the old estimator is an OUTCOME of this rule and
 * never a reason to revisit it.
 *
 * @param perRowObservations one entry per ROW, each holding that row's relative
 *   dispersions across its repeats. Grouping by row is the caller's job because the row
 *   key is the caller's (`FloorDerivationLedger` has `RowKey`; a caller reading a results
 *   file has whatever identifies a row there) — but the grouping is not optional. This
 *   function cannot tell a correctly grouped grid from a flattened one, and a caller that
 *   passes every observation as a single entry silently gets a plain maximum back, which
 *   is the old estimator. The refusal that actually protects that is
 *   `FloorDerivationLedger`'s completeness check, which counts observations PER ROW
 *   against a pre-registered row universe, so a flattened set cannot reach a render.
 */
fun classFloorStatistic(perRowObservations: Collection<List<Double>>): Double {
    require(perRowObservations.isNotEmpty()) {
        "a class floor statistic needs at least one row's observations"
    }
    require(perRowObservations.none { it.isEmpty() }) {
        "every row must carry at least one observation; ${
            perRowObservations.count { it.isEmpty() }
        } row(s) carried none"
    }
    return perRowObservations.maxOf { medianOf(it) }
}

/**
 * [value] rounded UP to three decimal places — the rounding [NOISE_FLOOR]'s own
 * derivation used (`2 x 0.0024683 = 0.0049366`, recorded as `0.005`).
 *
 * Up, never to-nearest: rounding a floor down would publish a bound the measurement it
 * was derived from does not support.
 */
fun roundUpToThreeDecimals(value: Double): Double {
    require(value.isFinite() && value >= 0.0) {
        "value must be finite and non-negative, was $value"
    }
    // The 1e-9 nudge is a guard against double representation error, not a fudge of the
    // rounding rule. `2.0 * 0.0305` is not exactly 0.061 in binary, and a product that
    // overshoots its decimal value by one ulp would otherwise be rounded up a whole
    // 0.001 — a 1.6% inflation of the floor caused by nothing but the encoding. The
    // nudge is 1e-12 in floor units, twelve orders below the dispersions being bounded,
    // so it cannot absorb a real difference.
    val scaled = ceil(value * 1000.0 - 1e-9)
    // `ceil(-1e-9)` is `-0.0`, and a floor of negative zero would compare equal to 0.0
    // while printing as "-0.0" in a findings entry. Normalise it back.
    return if (scaled == 0.0) 0.0 else scaled / 1000.0
}

/**
 * How a derivation's observations were actually GATHERED — provenance about method, never
 * about the number.
 *
 * It exists because the rendered findings block used to describe every derivation as
 * "`runs` sequential repeat runs" (`computenet-71hu`). That sentence was true while a
 * derivation WAS three back-to-back whole-class executions, and it became false the moment
 * `computenet-3omz` made a derivation assemblable from many short units in many separate
 * processes: `computenet-3omz.4`'s set was 63 observations from nine units across nine
 * processes and the block still called it three sequential repeat runs. A findings entry
 * is a published claim about method, so the claim has to follow the ledger rather than a
 * count whose name outlived its meaning.
 *
 * [ClassNoiseFloor.runs] is left alone deliberately: it means, and always meant, how many
 * observations of EVERY row fold into the maximum, it is [CLASS_FLOOR_MIN_RUNS] under both
 * assemblies, and re-deriving it would change a published number to fix a sentence. This
 * type supplements it instead, and nothing here participates in [ClassNoiseFloor.floor].
 *
 * A record may leave it unstated (`null`), and then the block says only what is certainly
 * true — that every row carries `runs` observations. That, and not [WholeClassRuns], is
 * the absent value on purpose: a default that asserts sequential runs is exactly the lie
 * this type was added to remove, and it would be re-told by the first construction site
 * that forgot the field.
 */
sealed interface DerivationAssembly {

    /**
     * [runs] sequential executions of the whole class, each measuring every row once —
     * the original procedure, and the only shape "N sequential repeat runs" describes.
     */
    data class WholeClassRuns(val runs: Int) : DerivationAssembly {
        init {
            require(runs >= 1) { "a whole-class-runs assembly needs at least one run, was $runs" }
        }
    }

    /**
     * The row set was assembled from [units] separately-invoked measuring units — one JMH
     * invocation, in its own process, per unit — as `computenet-3omz`'s checkpointed
     * derivation produces. Units, not runs: no unit measured the whole class.
     */
    data class UnitAssembled(val units: Int) : DerivationAssembly {
        init {
            require(units >= 1) { "a unit-assembled derivation needs at least one unit, was $units" }
        }
    }
}

/**
 * One benchmark class's forward-derived noise floor, with the provenance that makes it
 * checkable (`computenet-cm4w`).
 *
 * The floor itself is **not a field**: [floor] is computed from
 * [observedRobustDispersion] and [CLASS_FLOOR_MARGIN], so the table can never hold a
 * number that disagrees with its own derivation, and changing the margin moves every
 * derived floor in the same commit. That is the same property
 * `resolveEffect(effect, combinedError)` was extracted to give the series comparator: one
 * definition, no second place to restate it.
 *
 * @param benchmarkClass the benchmark's SIMPLE class name — the key
 *   `ThroughputReport.JmhRow.benchmarkClass` exposes, so a results file names its own
 *   floor and a caller does not have to.
 * @param observedRobustDispersion [classFloorStatistic] over the derivation's
 *   observations — the maximum, over the class's rows, of each row's MEDIAN
 *   `|scoreError / score|` across its [runs] observations. Must be finite and strictly
 *   positive: a zero would mean a benchmark with no dispersion at all, which is a broken
 *   measurement rather than a perfect one, and it would derive a floor of zero that every
 *   subsequent row exceeds. The field was named `observedMaxRelativeDispersion` while the
 *   estimator was a plain maximum over every observation; `computenet-3sua` changed the
 *   estimator and renamed the field with it, because a name that outlives its meaning is
 *   the same defect `computenet-71hu` removed from the rendered block's "N sequential
 *   repeat runs".
 * @param runs how many observations of EVERY row fold into the statistic. At least
 *   [CLASS_FLOOR_MIN_RUNS] — JMH reports a `NaN` error at or below two measurement
 *   samples, and a floor drawn from fewer than three observations per row cannot see
 *   run-to-run variation at all. It does NOT say how those observations were gathered;
 *   [assembly] does (`computenet-71hu`).
 * @param derivedOn the ISO date of the derivation runs.
 * @param harnessCommitSha the harness commit the runs were made at.
 * @param hostState the attested host state; must be [QUIESCED_HOST_STATE].
 * @param jmhConfig the class's own annotation configuration, as run — recorded so a later
 *   reader can tell whether a fresh row was measured under the config the floor describes.
 * @param measuringJvm the JVM the three runs actually MEASURED under, copied from the run
 *   logs' own `# VM version:` banner and named with its vendor. It is a field, and
 *   [renderDerivation] prints it, because the alternative was measured: `computenet-ahn0`
 *   derived this class's first floor under JBR 25.0.2 while the module's toolchain is
 *   JDK 21, and neither the record nor the rendered block could state which JVM produced
 *   the number — the defect was legible only in a run log nobody was obliged to keep
 *   (`computenet-7v7m`). A floor is applied to rows measured under the toolchain JDK, so
 *   a floor derived under another runtime may be loose or tight and the reader cannot
 *   tell which unless the entry says what it ran on. This field cannot prove the JVM was
 *   the right one — nothing in a data class can — but it makes a wrong one visible on the
 *   page instead of invisible.
 * @param assembly how the observations were gathered, when the derivation knows. See
 *   [DerivationAssembly] for why it is separate from [runs] and why its absent value
 *   asserts nothing.
 */
data class ClassNoiseFloor(
    val benchmarkClass: String,
    val observedRobustDispersion: Double,
    val runs: Int,
    val derivedOn: String,
    val harnessCommitSha: String,
    val hostState: String,
    val jmhConfig: String,
    val measuringJvm: String,
    val assembly: DerivationAssembly? = null,
) {
    init {
        require(benchmarkClass.isNotBlank()) { "benchmarkClass must not be blank" }
        require(observedRobustDispersion.isFinite() && observedRobustDispersion > 0.0) {
            "observedRobustDispersion must be finite and strictly positive, was " +
                "$observedRobustDispersion"
        }
        require(runs >= CLASS_FLOOR_MIN_RUNS) {
            "a per-class floor requires at least $CLASS_FLOOR_MIN_RUNS observations of " +
                "every row, was $runs"
        }
        require(derivedOn.isNotBlank()) { "derivedOn must not be blank" }
        require(harnessCommitSha.isNotBlank()) { "harnessCommitSha must not be blank" }
        require(hostState == QUIESCED_HOST_STATE) {
            "a per-class floor may only be derived on a host attested " +
                "'$QUIESCED_HOST_STATE', was '$hostState'. A floor derived through " +
                "interference is a floor measuring the interference, and every row later " +
                "classified against it inherits that"
        }
        require(jmhConfig.isNotBlank()) { "jmhConfig must not be blank" }
        require(measuringJvm.isNotBlank()) {
            "measuringJvm must not be blank — a derivation that cannot say which JVM it " +
                "measured under is the defect computenet-7v7m was filed for"
        }
        // A record that says "three sequential runs" while carrying five observations per
        // row describes neither, and the rendered block would publish the disagreement.
        val wholeClass = assembly as? DerivationAssembly.WholeClassRuns
        require(wholeClass == null || wholeClass.runs == runs) {
            "a whole-class-runs assembly of ${wholeClass?.runs} runs cannot produce $runs " +
                "observations of every row; each such run measures every row exactly once"
        }
    }

    /**
     * The derived floor: [CLASS_FLOOR_MARGIN] x [observedRobustDispersion], rounded
     * up to three decimals. Computed, never stored — see this class's KDoc.
     */
    val floor: Double
        get() = roundUpToThreeDecimals(CLASS_FLOOR_MARGIN * observedRobustDispersion)
}

/**
 * Every per-class floor this repository has actually derived.
 *
 * **Two entries.** See [ClassFloorDerivation]'s "Status" section: a class absent from
 * this list has not had its three sequential quiesced repeat runs made, and no number may
 * be entered here that did not come from them. An absent class falls back to
 * [NOISE_FLOOR] and the harness behaves for it exactly as it did before this file
 * existed — which is the correct behaviour for a floor that has not been measured.
 * `OperatorThroughputBenchmark` and `FanOutScalingBenchmark` are the two that are still
 * absent.
 *
 * **The `CellFootprintBenchmark` entry is a RE-DERIVATION (`computenet-3sua`, 2026-08-27).** It is the same
 * three quiesced runs `computenet-7v7m` made — the same 63 retained observations, no new
 * measurement — recomputed under [classFloorStatistic], which replaced the previous
 * maximum-over-every-observation. The estimator was committed first, on its own, before
 * this number was computed under it; the value below is an OUTCOME of that rule and was
 * not available when it was chosen. No entry is grandfathered: this table carries one
 * statistic across every row of it. The `BoundedReadBenchmark` entry needs no
 * re-derivation for the same reason from the other direction — its observations were
 * gathered on 2026-08-27 (`computenet-akfa`), after [classFloorStatistic] was committed,
 * so it has only ever been read by the statistic in force.
 *
 * On the size of `CellFootprintBenchmark`'s floor: the worst quiet-host row by TYPICAL dispersion
 * is `realSnapshot` OR_MAP_CELL at `N1E5`, whose three observations were 0.522 / 0.119 /
 * 0.199 and whose median is therefore 0.199 — so the floor is 0.398. `realSnapshot` at
 * `N1E5` runs high dispersion *reproducibly*: 12 of the 63 rows exceed 0.10 — OR_MAP_CELL
 * and SET_CELL in all three runs, MAP_CELL in two, KEYED_SET_CELL and LIST_CELL in one
 * each. That is precisely the structural spread [ClassFloorDerivation]'s "defect this
 * exists to close" section describes: the global bound fired on those rows every time and
 * so distinguished nothing.
 *
 * **What changed, and what did not.** Under the old estimator this class's floor was
 * 1.044, set by OR_MAP_CELL N1E5's single worst repeat (0.522, about 2.6x its own
 * next-worst observation, against 0.201 as the second-highest row over all 63). The floor
 * is now 0.398. The row that sets it is the SAME row — the change is not that a different
 * part of the class was found to be worst, but that the row is now represented by what it
 * typically does rather than by its most extreme repeat.
 *
 * **The limit of this particular number, stated where the number is.** 0.398 is still a
 * loose bound in absolute terms: a row may carry a JMH 99.9% error bar nearly 40% the size
 * of its own score and pass. That is the class, not the estimator — `realSnapshot` at
 * `N1E5` genuinely disperses that much on a silent machine, and a floor that refused it
 * would be refusing the benchmark rather than detecting interference. A reader should also
 * not read 0.398 as "no observation here exceeded 0.199": a median does not bound the
 * sample it is drawn from, and one of these very rows was measured at 0.522.
 *
 * **What must not be read into the tightening.** That 0.398 came out below 1.044 is an
 * outcome, not a justification. The estimator was argued and committed on robustness
 * grounds alone (see [classFloorStatistic]), and it would have discharged
 * `computenet-3sua` identically had it produced a LOOSER floor. Nothing here may be
 * revisited on the ground that some other statistic would yield a number someone prefers.
 *
 * **On the size of `BoundedReadBenchmark`'s floor, 0.058.** The row that sets it is
 * `realHostedSnapshotOf` at `N1E4`, whose three observations were 0.0285 / 0.0274 /
 * 0.0313 and whose median is therefore 0.0285. The class is tight and uniform in a way
 * `CellFootprintBenchmark` is not: all six per-row medians fall between 0.0167 and 0.0285,
 * a spread of well under a factor of two, and the row that sets the floor does so by a
 * margin of about 12% over the next-highest row rather than by being an outlier. So this
 * floor is one an ordinary row of the class clears comfortably, which is what a per-class
 * floor is supposed to look like — unlike the global [NOISE_FLOOR] of 0.005, which every
 * one of these 18 observations exceeds and which therefore distinguished nothing for this
 * class either.
 *
 * **What 0.058 does NOT say.** It is not a bound on the individual observations it was
 * drawn from: the largest single observation in the set is 0.0408 (`realDirect` at
 * `N1E5`), above the 0.0285 statistic, because a median does not bound its own sample.
 * That is the estimator working as documented in [classFloorStatistic] rather than a
 * discrepancy. Nor does it say anything about `BoundedReadBenchmark` under `-prof gc`,
 * under a different `@Fork`/iteration count, or on another host — the entry's [jmhConfig]
 * and [ClassNoiseFloor.measuringJvm] fields state the configuration it describes, and
 * only that one.
 */
val CLASS_NOISE_FLOOR_DERIVATIONS: List<ClassNoiseFloor> = listOf(
    /**
     * `computenet-7v7m`, 2026-08-26 — the re-derivation. Three sequential runs of
     * `civictech.bench.micro.CellFootprintBenchmark` (21 rows each: `realSnapshot` over
     * 7 cell families x 3 scales) from `bench/build/libs/bench-jmh.jar` at `a7c6a0382`,
     * each preceded by its own attestation of `run-series.sh`'s gate (1-minute load
     * average at or below 0.25 x 16 cores = 4.00 on NL-MGD6FQJW91), and each verified
     * after the fact against its own log's `# VM version:` banner.
     *
     * **Re-derived 2026-08-27 by `computenet-3sua`** under [classFloorStatistic], from
     * those same three runs' retained JSON — arithmetic over kept artifacts, not a fresh
     * measurement, which is why every provenance field below (date of the RUNS, harness
     * sha, host state, JMH config, measuring JVM) is unchanged. The per-row median that
     * sets it is `realSnapshot` OR_MAP_CELL N1E5, whose observations across runs 1 / 2 / 3
     * were 0.5217864937179187 / 0.11856655814861747 / 0.19864889236475775. Under the previous
     * estimator this entry read 0.5217864937179187, floor 1.044.
     *
     * **This SUPERSEDES `computenet-ahn0`'s derivation of the same class**, which
     * observed 0.2961501149112133 and published a floor of 0.593. That measurement is not
     * retained as a second entry and must not be: all three of its runs were measured
     * under JBR 25.0.2 (`# VM version: JDK 25.0.2 …` in each log, `jdkVersion` 25.0.2 on
     * every row), not the module's declared toolchain JDK 21, which step 1 of the
     * pre-registered procedure requires.
     */
    ClassNoiseFloor(
        benchmarkClass = "CellFootprintBenchmark",
        observedRobustDispersion = 0.19864889236475775,
        runs = 3,
        derivedOn = "2026-08-26",
        harnessCommitSha = "a7c6a0382",
        hostState = QUIESCED_HOST_STATE,
        jmhConfig = "mode=AverageTime unit=us forks=1 warmup=3x1s measurement=5x1s",
        // Verbatim from all three run logs' banner, plus the vendor `-version` reports.
        // Amazon Corretto rather than the Microsoft OpenJDK 21.0.11 earlier BEN1 findings
        // entries name: this is the JDK 21 Gradle's own toolchain resolution selects for
        // `:bench` on this host today (`./gradlew javaToolchains`), and the Eclipse
        // Adoptium 21.0.11 those entries used by absolute path is no longer installed.
        measuringJvm = "JDK 21.0.5, OpenJDK 64-Bit Server VM, 21.0.5+11-LTS " +
            "(Amazon Corretto; :bench's resolved toolchain launcher)",
        // Stated rather than left to the absent value: this derivation really was three
        // back-to-back executions of the whole class (see this entry's own KDoc), so the
        // block's "3 sequential repeat runs" is a claim the runs support. A later entry
        // derived through `computenet-3omz`'s checkpointed ledger carries
        // DerivationAssembly.UnitAssembled instead, and `floorTool render` writes it.
        assembly = DerivationAssembly.WholeClassRuns(runs = 3),
    ),
    /**
     * `computenet-akfa`, 2026-08-27. Three sequential runs of
     * `civictech.bench.micro.BoundedReadBenchmark` (6 rows each: `realDirect` and
     * `realHostedSnapshotOf` over 3 `SetScale` constants) from
     * `bench/build/libs/bench-jmh.jar` at `19055b951`, on the pinned host NL-MGD6FQJW91
     * (16 cores, gate threshold 4.00), each preceded by its own attestation of
     * `run-series.sh`'s gate — the readings immediately before the three invocations were
     * 2.87, 2.99 and 2.84 — and each verified after the fact against its own log's
     * `# VM version:` banner, all three of which read
     * `JDK 21.0.5, OpenJDK 64-Bit Server VM, 21.0.5+11-LTS`.
     *
     * Accumulated through [FloorDerivationLedger] (`floorTool plan` / `ingest` /
     * `render`), which is what checks the row set is COMPLETE against
     * `EXPECTED_PLAN_ROW_COUNTS` (6 rows x 3 observations = 18) and that it spans one
     * measuring JVM and one harness sha. Each of the three units happened to measure the
     * whole class, so the assembly below is [DerivationAssembly.WholeClassRuns] and not
     * [DerivationAssembly.UnitAssembled]: `computenet-3omz`'s decomposition was available
     * and was not needed, because one whole-class run is 00:05:13 and fits a quiesced
     * window whole.
     *
     * The per-row median that sets the statistic is `realHostedSnapshotOf` at `N1E4`,
     * whose observations across runs 1 / 2 / 3 were 0.028527147482145923 /
     * 0.02740650775589135 / 0.031330611761921666. Unlike the entry above, this one is not
     * a re-derivation of anything: the observations were gathered after
     * [classFloorStatistic] was committed, so no earlier number for this class exists and
     * none is superseded.
     */
    ClassNoiseFloor(
        benchmarkClass = "BoundedReadBenchmark",
        observedRobustDispersion = 0.028527147482145923,
        runs = 3,
        derivedOn = "2026-08-27",
        harnessCommitSha = "19055b951",
        hostState = QUIESCED_HOST_STATE,
        jmhConfig = "mode=AverageTime unit=ms forks=5 warmup=5x1s measurement=5x1s",
        // Verbatim from all three run logs' banner, plus the vendor `-version` reports —
        // the same convention (and, as it happens, the same launcher) as the entry above.
        measuringJvm = "JDK 21.0.5, OpenJDK 64-Bit Server VM, 21.0.5+11-LTS " +
            "(Amazon Corretto; :bench's resolved toolchain launcher)",
        assembly = DerivationAssembly.WholeClassRuns(runs = 3),
    ),
)

/**
 * [derivations] indexed by [ClassNoiseFloor.benchmarkClass].
 *
 * Refuses two derivations naming one class: a class with two floors has no floor, and
 * silently keeping the last one would make the resolved bound depend on list order.
 */
fun floorTable(derivations: List<ClassNoiseFloor>): Map<String, Double> {
    val duplicated = derivations.groupingBy { it.benchmarkClass }.eachCount()
        .filterValues { it > 1 }
    require(duplicated.isEmpty()) {
        "each benchmark class may carry at most one derived floor, found " +
            duplicated.entries.sortedBy { it.key }.joinToString { "'${it.key}' x ${it.value}" }
    }
    return derivations.associate { it.benchmarkClass to it.floor }
}

/**
 * The live table, derived from [CLASS_NOISE_FLOOR_DERIVATIONS]. Two classes today —
 * `CellFootprintBenchmark` and `BoundedReadBenchmark`; everything else falls back.
 */
val CLASS_NOISE_FLOOR_TABLE: Map<String, Double> = floorTable(CLASS_NOISE_FLOOR_DERIVATIONS)

/**
 * The relative-dispersion bound [benchmarkClass]'s rows are classified against: that
 * class's own derived floor where one exists, and [NOISE_FLOOR] where none does.
 *
 * A `null` or blank [benchmarkClass] is a row whose class is not known to the caller —
 * `Footprint.toResults` builds rows from a walk rather than from a JMH results file, for
 * instance — and resolves to [NOISE_FLOOR]. It is not an error: an unknown class has no
 * class floor by definition, which is exactly the fallback case.
 *
 * @param floors the table to resolve against. Defaults to [CLASS_NOISE_FLOOR_TABLE]; the
 *   parameter exists so every branch of the resolution can be exercised on every
 *   `:bench:test` run while the live table is empty — a criterion that executes once in
 *   its life is not pinned.
 */
fun noiseFloorFor(
    benchmarkClass: String?,
    floors: Map<String, Double> = CLASS_NOISE_FLOOR_TABLE,
): Double {
    if (benchmarkClass.isNullOrBlank()) return NOISE_FLOOR
    return floors[benchmarkClass] ?: NOISE_FLOOR
}

/**
 * Whether [benchmarkClass] resolves to a floor of its own, as opposed to falling back to
 * the global [NOISE_FLOOR].
 *
 * Distinct from `noiseFloorFor(...) != NOISE_FLOOR`, and deliberately so: a class whose
 * derived floor happens to equal 0.005 has a floor of its own, and a message that called
 * it a fallback would be wrong about where the bound came from.
 */
fun hasClassFloor(
    benchmarkClass: String?,
    floors: Map<String, Double> = CLASS_NOISE_FLOOR_TABLE,
): Boolean = !benchmarkClass.isNullOrBlank() && floors.containsKey(benchmarkClass)

/**
 * How a rendered note names the bound a row was classified against — which floor, and
 * whether it is the class's own or the global fallback.
 *
 * The two phrasings are different on purpose. `NOISE_FLOOR` is a bit mixer's dispersion
 * on an idle host, so a hosted-graph row above it means almost nothing; a row above its
 * own class's floor means that class was noisier than it is when the machine is quiet.
 * A reader must be able to tell the two statements apart from the text alone.
 */
fun describeFloor(
    benchmarkClass: String?,
    floors: Map<String, Double> = CLASS_NOISE_FLOOR_TABLE,
): String {
    val floor = noiseFloorFor(benchmarkClass, floors)
    return if (hasClassFloor(benchmarkClass, floors)) {
        "the $benchmarkClass class floor $floor (derived from that class's own quiesced " +
            "repeat runs)"
    } else {
        "the harness sanity bound NOISE_FLOOR $floor"
    }
}

/**
 * Classifies [result] against the bound [benchmarkClass] resolves to (`computenet-cm4w`).
 *
 * The arithmetic is [classifyAgainst]'s and is unchanged from the single-argument
 * [classify]: magnitude of the relative dispersion, non-finite refused explicitly,
 * strictly-above is [Reportability.Unreportable]. Only the bound is chosen differently.
 */
fun classify(
    result: BenchResult,
    benchmarkClass: String?,
    floors: Map<String, Double> = CLASS_NOISE_FLOOR_TABLE,
): Reportability = classifyAgainst(result, noiseFloorFor(benchmarkClass, floors))

/**
 * The classification arithmetic over an explicit [floor] — the one definition both
 * [classify] overloads reach.
 *
 * See [classify]`(BenchResult)` for why the comparison is over the ABSOLUTE value and why
 * a non-finite magnitude is refused explicitly rather than left to IEEE 754.
 */
fun classifyAgainst(result: BenchResult, floor: Double): Reportability {
    require(floor.isFinite() && floor > 0.0) {
        "floor must be finite and strictly positive, was $floor"
    }
    val magnitude = abs(result.relativeDispersion)
    return if (!magnitude.isFinite() || magnitude > floor) {
        Reportability.Unreportable
    } else {
        Reportability.Reportable
    }
}

/**
 * The `doc/bench/findings.md` derivation block for one [ClassNoiseFloor], fixed in this
 * source file **before any derivation exists** so the entry cannot be written to fit its
 * numbers (`computenet-cm4w`).
 *
 * Rendered from the record itself, so the published floor is arithmetically the record's
 * [ClassNoiseFloor.floor] and the two cannot drift. Whoever runs the derivation appends
 * this block; they do not compose one.
 */
fun renderDerivation(derivation: ClassNoiseFloor): String = buildString {
    // How the observations were GATHERED, said only as far as the record actually knows
    // (`computenet-71hu`). The unqualified "N sequential repeat runs" this used to print
    // unconditionally is retained for — and only for — the shape it describes.
    val gathering = when (val assembly = derivation.assembly) {
        is DerivationAssembly.WholeClassRuns ->
            "${assembly.runs} sequential repeat runs"
        is DerivationAssembly.UnitAssembled ->
            "${derivation.runs} observations of every row, assembled from " +
                "${assembly.units} measuring units in ${assembly.units} separate processes"
        null -> "${derivation.runs} observations of every row"
    }
    val statisticOver = if (derivation.assembly is DerivationAssembly.WholeClassRuns) {
        "over all rows of all ${derivation.runs} runs"
    } else {
        "over all rows, ${derivation.runs} observations each"
    }
    appendLine(
        "## ${derivation.derivedOn} — per-class noise floor for " +
            "`${derivation.benchmarkClass}`, derived forward from its own quiesced " +
            "repeat runs"
    )
    appendLine(
        "Harness: ${derivation.harnessCommitSha} · host state ${derivation.hostState} · " +
            "$gathering"
    )
    appendLine("JMH: ${derivation.jmhConfig} (the class's own annotation configuration)")
    // The measuring JVM is rendered, not left to prose, because a prose caveat is exactly
    // what was missing when a floor derived under JBR 25 shipped as if it described the
    // toolchain JDK (computenet-7v7m). See ClassNoiseFloor.measuringJvm.
    appendLine("Measured under: ${derivation.measuringJvm}")
    appendLine("| quantity | value |")
    appendLine("| --- | --- |")
    appendLine(
        "| statistic (max over rows of the per-row MEDIAN relative dispersion) " +
            "$statisticOver | ${derivation.observedRobustDispersion} |"
    )
    appendLine("| margin, fixed before the runs (CLASS_FLOOR_MARGIN) | $CLASS_FLOOR_MARGIN |")
    appendLine(
        "| derived floor = margin x statistic, rounded up to three decimals | " +
            "${derivation.floor} |"
    )
    appendLine(
        "Estimator: `classFloorStatistic` — the MEDIAN of each row's relative dispersions " +
            "across its repeats, then the MAXIMUM of those medians across the class's " +
            "rows. Robust within a row (one contaminated repeat cannot set a class's " +
            "floor), conservative across rows (a reproducibly high-dispersion row is a " +
            "fact about the class, not noise in it). Pre-registered in " +
            "`ClassNoiseFloor.kt` before it was computed over any measurement — see " +
            "`classFloorStatistic`'s own documentation for the argument, which is made " +
            "from robustness and never from the value the statistic yields."
    )
    appendLine(
        "Derivation: forward. The margin was fixed in `ClassNoiseFloor.kt` before any " +
            "per-class number existed; the floor is computed from the statistic by " +
            "`ClassNoiseFloor.floor` and is not hand-entered. What it establishes: on a " +
            "quiesced host, every row of `${derivation.benchmarkClass}` measured under " +
            "this configuration had a TYPICAL (median) relative dispersion at or under " +
            "${derivation.observedRobustDispersion}, so a later row above " +
            "${derivation.floor} is more dispersed than this class typically is when the " +
            "machine is quiet. What it does NOT establish: that no individual observation " +
            "in the derivation exceeded ${derivation.observedRobustDispersion} — a median " +
            "does not bound the sample it is drawn from, and single high repeats are " +
            "exactly what this estimator declines to build a floor on. Nor anything about " +
            "another " +
            "benchmark class, about this class under a different annotation " +
            "configuration, about this class on another host, or about this class under " +
            "a JVM other than ${derivation.measuringJvm}."
    )
}
