package civictech.bench

import kotlin.math.abs
import kotlin.math.ceil

/**
 * Per-`@Benchmark`-METHOD noise floors, and the machinery that resolves one
 * (`computenet-cm4w`; the grain moved from the class to the method in
 * `computenet-x9e.18`).
 *
 * ## The grain: one floor per `@Benchmark` method
 *
 * A floor is keyed by [FloorKey] — the (class, `@Benchmark` method) pair — and folded by
 * [classFloorStatistic] over that method's rows alone. **A class whose methods carry
 * unlike measurement regimes therefore carries several floors.** The argument is in
 * [classFloorStatistic]'s own "grain" section and is made from the structure of JMH's
 * annotations: `@BenchmarkMode`, `@Fork`, `@Warmup` and `@Measurement` are method-level,
 * so the method is the unit that carries one regime. It was decided (mlboogerd,
 * 2026-08-29) ahead of every number it produces, and no number may be used to revisit it.
 *
 * The previous grain — one floor per class — is retired, not deprecated: nothing resolves
 * by class alone any more, and [noiseFloorFor] refuses to answer a caller that supplies
 * only a class. What that grain cost is recorded on the `FanOutScalingBenchmark` entries
 * in [CLASS_NOISE_FLOOR_DERIVATIONS], where a single floor of 0.953 was applied to a
 * `Mode.AverageTime` sub-family whose own floors are 0.148 and 0.107.
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
 * ## Status: ALL FOUR classes the procedure names are derived
 *
 * [CLASS_NOISE_FLOOR_DERIVATIONS] holds ELEVEN entries — one per `@Benchmark` METHOD of
 * the four benchmark classes the
 * procedure names — and **nothing named by it falls back to [NOISE_FLOOR] any more**. That
 * is the end state `computenet-cm4w` opened this file for; the fallback path below is not
 * dead, and still resolves any class with no derivation — `SmokeBenchmark`, or a class
 * added later — to the global bound.
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
 * - `FanOutScalingBenchmark`, derived 2026-08-28 by `computenet-akfa` on its own
 *   dedicated slot, likewise gathered wholly under [classFloorStatistic]. 30 rows, three
 *   whole-class runs of 00:14:15 each. It is the first class whose derivation had to
 *   choose between the whole-class shape and `computenet-3omz`'s row-set decomposition
 *   and could still afford the whole-class one: at 14 minutes a run fits a quiesced
 *   window whole, and decomposing a 30-row class into 3-row units would have bought ten
 *   times the gate waits for a scheduling freedom the slot did not need.
 * - `OperatorThroughputBenchmark`, derived 2026-08-28 by `computenet-x9e.17` on its own
 *   dedicated slot — the last class to come off the global fallback, and by a wide margin
 *   the most expensive: 72 rows, three whole-class runs of 00:37:11, 00:37:01 and
 *   00:37:02. Its first run was taken a slot earlier as a CALIBRATION of the 2026-08-26
 *   estimate and retained un-ingested, then ingested here as run 1 because this item ran
 *   from the same harness sha its jar was stamped at; the derivation is still three
 *   whole-class quiesced runs under [classFloorStatistic], gathered on one UTC day.
 *
 * The per-run costs, all now MEASURED at the classes' own annotation configurations rather
 * than estimated: `CellFootprintBenchmark` 177s, `BoundedReadBenchmark` 00:05:13,
 * `FanOutScalingBenchmark` 00:14:15, `OperatorThroughputBenchmark` ~00:37:05. The
 * 2026-08-26 estimates were conservative for every class but by very different factors —
 * `FanOutScalingBenchmark` by about 2x (~27 min estimated), `OperatorThroughputBenchmark`
 * by only ~23% (~48 min estimated, and its "72 rows x 2 forks x 15s = 36 min of pure
 * iteration" arithmetic was the better guide) — so a future class's estimate must not be
 * corrected by analogy to whichever class was measured last. Shrinking any of those
 * configurations to fit a slot is exactly the thing the procedure forbids; each of the
 * last three classes was routed to its own dedicated slot instead.
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
 * How much headroom a derived floor carries over the worst relative dispersion
 * its three quiesced runs actually observed.
 *
 * **The margin is 2x, and it is fixed here before any derived number exists** — the
 * condition [NOISE_FLOOR]'s KDoc states for any amendment to this file's family of
 * criteria, and the same discipline `computenet-bzwx` used for
 * `IterationLengthCriterion`'s thresholds.
 *
 * Why 2x, and why the *same* 2x as the global bound rather than a fresh choice: the
 * derived floor and [NOISE_FLOOR] are the same construction applied to different
 * subjects, and a reader comparing a derived floor to the global one should be reading a
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
 * The one host state under which a floor may be derived.
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
 * The floor's estimator: the **maximum, over one `@Benchmark` METHOD's rows, of each
 * row's MEDIAN relative dispersion across that row's repeat observations**
 * (`computenet-3sua` for the median; `computenet-x9e.18` for the method grain).
 *
 * ## The fold's GRAIN is one `@Benchmark` method, not one class
 *
 * The maintainer decision of 2026-08-29 (`computenet-x9e.18`) moved the across-row fold
 * from "every row of the class" to "the rows of one `@Benchmark` method". **A floor must
 * span ONE MEASUREMENT REGIME, and in JMH the annotation configuration that defines a
 * regime is per-method:** `@BenchmarkMode`, `@Fork`, `@Warmup` and `@Measurement` are all
 * method-level, and a method-level one overrides the class-level default for that method
 * alone. Folding per method therefore folds within a regime instead of across several.
 * `@Param` variation WITHIN a method is the workload sweep the class exists to draw, and
 * those rows are meant to be comparable against one bound — which is why the fold stops
 * at the method and does **not** go on to `@Param`. A per-`@Param` grain was considered
 * and REJECTED: on a 30-row class it approaches one floor per row, and a row cannot
 * detect its own interference against a bound derived from itself.
 *
 * The reasoning is stated from the structure of JMH's annotations and would have been
 * adopted had every class come out uniform; see [CLASS_NOISE_FLOOR_DERIVATIONS] for what
 * the re-derived numbers turned out to be, which is an OUTCOME of the rule and never an
 * argument for it.
 *
 * **This function itself is unchanged.** The grain lives entirely in the CALLER's
 * grouping: `perRowObservations` is now one `@Benchmark` method's rows rather than a whole
 * class's. That is deliberate — the estimator `computenet-3sua` pre-registered (median
 * within a row, maximum across rows) is not amended by the grain change, only applied to
 * a narrower row set.
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
 * A derivation's observations form a grid: one axis is the method's ROWS (its `@Param`
 * combinations), the other is the REPEATS of each row. The previous estimator —
 * a single maximum over the flattened grid — treated both axes as one, and that is what
 * this replaces. They are not the same kind of variation. (The third axis, the
 * `@Benchmark` METHOD, is not folded over at all any more: it partitions the derivation
 * into separate floors — see this function's "grain" section above.)
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
 * heavily every time, and that is a fact about the method rather than noise in it. The
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
 * @param perRowObservations one entry per ROW **of a single `@Benchmark` method**, each
 *   holding that row's relative
 *   dispersions across its repeats. Grouping by row is the caller's job because the row
 *   key is the caller's (`FloorDerivationLedger` has `RowKey`; a caller reading a results
 *   file has whatever identifies a row there) — but the grouping is not optional, and
 *   nor is the PARTITION BY METHOD that precedes it: passing a whole class's rows here
 *   silently computes the retired per-class statistic, which this function cannot detect
 *   any more than it can detect a flattened grid. `FloorDerivationLedger.render()`
 *   partitions by `RowKey.method` before calling in, and that is where the grain is
 *   enforced. This
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
 * What a derived floor is keyed by: one `@Benchmark` METHOD of one benchmark class
 * (`computenet-x9e.18`).
 *
 * The key used to be the class alone. It is a pair now because the fold's grain is the
 * method — see [classFloorStatistic]'s "grain" section — so a class that declares several
 * `@Benchmark` methods carries several floors, and resolving one of them by class alone
 * would have to pick between them.
 *
 * [benchmarkMethod] is the SIMPLE method name, the last dot-separated segment of JMH's
 * `pkg.Class.method` benchmark name — the same form `RowKey.method` and
 * `ThroughputReport.JmhRow.method` carry, so a ledger row and a results-file row name
 * their floor identically without either having to translate.
 */
data class FloorKey(val benchmarkClass: String, val benchmarkMethod: String) {

    init {
        require(benchmarkClass.isNotBlank()) { "benchmarkClass must not be blank" }
        require(benchmarkMethod.isNotBlank()) { "benchmarkMethod must not be blank" }
    }

    /** `Class.method` — how a refusal, a rendered note and a findings heading name it. */
    fun describe(): String = "$benchmarkClass.$benchmarkMethod"
}

/**
 * One `@Benchmark` METHOD's forward-derived noise floor, with the provenance that makes it
 * checkable (`computenet-cm4w`; per-method since `computenet-x9e.18`).
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
 * @param benchmarkMethod the `@Benchmark` method this floor covers — its simple name, as
 *   `ThroughputReport.JmhRow.method` and `FloorDerivationLedger.RowKey.method` carry it.
 *   With [benchmarkClass] it forms this record's [key]. It has no default: a record that
 *   did not say which method it describes would be a per-class floor wearing the new
 *   type's name, and the whole content of `computenet-x9e.18` is that those are different
 *   quantities.
 * @param observedRobustDispersion [classFloorStatistic] over this METHOD's
 *   observations — the maximum, over the method's rows, of each row's MEDIAN
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
    val benchmarkMethod: String,
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
        require(benchmarkMethod.isNotBlank()) {
            "benchmarkMethod must not be blank — a floor covers one @Benchmark method " +
                "since computenet-x9e.18, and a record that cannot name its method is a " +
                "retired per-class floor in the current type's clothing"
        }
        require(observedRobustDispersion.isFinite() && observedRobustDispersion > 0.0) {
            "observedRobustDispersion must be finite and strictly positive, was " +
                "$observedRobustDispersion"
        }
        require(runs >= CLASS_FLOOR_MIN_RUNS) {
            "a derived floor requires at least $CLASS_FLOOR_MIN_RUNS observations of " +
                "every row, was $runs"
        }
        require(derivedOn.isNotBlank()) { "derivedOn must not be blank" }
        require(harnessCommitSha.isNotBlank()) { "harnessCommitSha must not be blank" }
        require(hostState == QUIESCED_HOST_STATE) {
            "a derived floor may only be derived on a host attested " +
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

    /** What this record is resolved by: its (class, method) pair. */
    val key: FloorKey get() = FloorKey(benchmarkClass, benchmarkMethod)

    /**
     * The derived floor: [CLASS_FLOOR_MARGIN] x [observedRobustDispersion], rounded
     * up to three decimals. Computed, never stored — see this class's KDoc.
     */
    val floor: Double
        get() = roundUpToThreeDecimals(CLASS_FLOOR_MARGIN * observedRobustDispersion)
}

/**
 * Every derived floor this repository holds — **one per `@Benchmark` METHOD**, eleven
 * across the four classes the procedure names (`computenet-x9e.18`).
 *
 * **The table was keyed by CLASS until 2026-08-30 and is keyed by (class, method) now.**
 * The grain change is [classFloorStatistic]'s, argued there from the structure of JMH's
 * annotations — `@BenchmarkMode`, `@Fork`, `@Warmup` and `@Measurement` are method-level,
 * so a method is the unit that carries one measurement regime — and decided ahead of
 * every number below. Everything in this KDoc that reports what a number came out to is
 * an OUTCOME of that rule and may not be used to revisit it.
 *
 * **Every entry was RE-DERIVED under the new grain. Nothing is grandfathered**, because
 * the table carries one statistic across all of it. The re-derivation was pure
 * RECOMPUTATION from the retained observations of the very same runs the superseded
 * per-class entries were derived from — no benchmark was re-measured, so every provenance
 * field (date of the runs, harness sha, host state, measuring JVM, assembly) is carried
 * across unchanged, and only [ClassNoiseFloor.benchmarkMethod], the row set folded, and
 * the resulting statistic are new.
 *
 * **How each class's input set was confirmed to be the one its published entry came
 * from.** The check is arithmetic and it is the reason `computenet-x9e.18`'s trap did not
 * fire: folding each retained set at the OLD whole-class grain reproduces that class's
 * superseded published statistic **bit for bit** —
 * `CellFootprintBenchmark` 0.19864889236475775 (floor 0.398),
 * `BoundedReadBenchmark` 0.028527147482145923 (0.058),
 * `FanOutScalingBenchmark` 0.4762179191123049 (0.953),
 * `OperatorThroughputBenchmark` 0.05106599919551368 (0.103). A set that folded to
 * anything else would be a different measurement set, which is exactly what the
 * `floor-derivations/CellFootprintBenchmark/` directory turns out to be (below).
 *
 * **The `CellFootprintBenchmark` inputs are `computenet-7v7m`'s
 * `cellfootprint-{1,2,3}.json`, NOT a ledger under `$HOME/computenet-runs/floor-derivations/`
 * (`computenet-xppx`, 2026-08-28).** Unlike the other three classes this one has no
 * `FloorDerivationLedger` behind its number. A directory named
 * `floor-derivations/CellFootprintBenchmark/` may exist on a given machine and even look
 * complete (right row count, single JVM, v1 ledger format), but it is not the input to
 * this entry: it was observed on MacBoo (NL-MGD6FQJW91) on 2026-08-28 to hold
 * `computenet-3omz.4`'s ledger-machinery exercise instead, measured from a jar at harness
 * sha `5a2fdccfd` — not this entry's `a7c6a0382` — which folds under
 * [classFloorStatistic] to 0.485, not 0.398. That directory is machine-local leftover
 * state, not a repository artifact, so its presence or absence is not asserted here; the
 * warning is for whoever next re-derives this class and is tempted to fold whatever sits
 * under that path. The real inputs are named in this entry's own KDoc, above the
 * constructor call, and nowhere under `floor-derivations/`.
 *
 * ## What the finer grain actually changed, per class
 *
 * **`CellFootprintBenchmark` — one method, one floor, number UNCHANGED at 0.398.** The
 * class declares a single `@Benchmark`, `realSnapshot`, so its 21 rows were already one
 * regime and the per-method fold is the per-class fold on this class. That is an outcome
 * of the rule rather than a special case, and it is the cleanest evidence that the grain
 * change is a partition and not a re-estimation: where there is nothing to partition,
 * nothing moves. The worst row by typical dispersion is still `realSnapshot` OR_MAP_CELL
 * at `N1E5`, whose three observations were 0.522 / 0.119 / 0.199 and whose median is
 * therefore 0.199.
 *
 * **`BoundedReadBenchmark` — two methods, 0.058 splits into 0.052 and 0.058.** Barely a
 * change, and that is the finding: this class was already uniform (all six per-row
 * medians between 0.0167 and 0.0285), so its two methods are two samples of much the same
 * regime. `realHostedSnapshotOf` keeps the old number because it held the row that set
 * it; `realDirect` gains a floor of its own that is about 10% tighter.
 *
 * **`FanOutScalingBenchmark` — six methods, and this is the class the grain change was
 * noticed on.** Its single floor was 0.953, the largest in the table, set by
 * `simBatchFixedState[degree=D256]`, while the `Mode.AverageTime` `sim`/`real` rows — the
 * fan-out curve the class exists to draw — all sat at or under 0.074 and so were measured
 * against a bound more than an order of magnitude looser than themselves. Under the
 * method grain those two methods get 0.148 and 0.107, and the three sub-families separate
 * exactly along the annotation boundaries that define them: `sim`/`real` at
 * `Mode.AverageTime` `@Fork(5)`, `simFixedState`/`realFixedState` at
 * `Mode.SingleShotTime` `@Fork(3)` (0.617 and 0.472), and the two
 * `@OperationsPerInvocation(200)` batch methods (0.953 and 0.694). Nothing here was
 * arranged: the partition is by `@Benchmark` method and the regimes fell out of it.
 *
 * **`OperatorThroughputBenchmark` — two methods, 0.103 and 0.094.** The mildest case,
 * as expected: every row of this class is measured under ONE annotation configuration, so
 * its two methods really are one regime and the split is small. Its 72 per-row medians
 * still span a factor of about ten WITHIN each method, and the typical row is still
 * roughly an order of magnitude below its floor. **The grain change does not fix that,
 * and is not claimed to**: `@Param` spread inside one method is the workload sweep the
 * class exists to draw, and folding it away is the per-`@Param` grain that was considered
 * and rejected ([classFloorStatistic]).
 *
 * ## What these numbers do NOT say
 *
 * As under every previous grain, a floor is not a bound on the individual observations it
 * was drawn from — a median does not bound its own sample, and several of these rows were
 * measured above their own method's statistic (`realSnapshot` OR_MAP_CELL N1E5 at 0.522
 * against 0.199; `simBatchFixedState` D256 at 0.490 against 0.476; `realDirect` N1E5 at
 * 0.0408 against 0.0255). And no entry says anything about its method on another host,
 * under `-prof gc`, or under any `@Fork`/iteration count other than the one its own
 * [ClassNoiseFloor.jmhConfig] names.
 *
 * A class absent from this list has not had its quiesced repeat runs made, and no number
 * may be entered here that did not come from them. An absent class — or an absent METHOD
 * of a present class, which is now equally possible — falls back to [NOISE_FLOOR] and the
 * harness behaves for it exactly as it did before this file existed. The only classes
 * still resolving to [NOISE_FLOOR] are `SmokeBenchmark` (from which [NOISE_FLOOR] itself
 * was derived, and which the procedure deliberately does not name) and any class added
 * after this table.
 */
val CLASS_NOISE_FLOOR_DERIVATIONS: List<ClassNoiseFloor> = listOf(
    /**
     * `computenet-7v7m`, 2026-08-26 — the runs. Three sequential runs of
     * `civictech.bench.micro.CellFootprintBenchmark` (21 rows each: `realSnapshot` over
     * 7 cell families x 3 scales) from `bench/build/libs/bench-jmh.jar` at `a7c6a0382`,
     * each preceded by its own attestation of `run-series.sh`'s gate (1-minute load
     * average at or below 0.25 x 16 cores = 4.00 on NL-MGD6FQJW91), and each verified
     * after the fact against its own log's `# VM version:` banner.
     *
     * **Re-folded 2026-08-30 by `computenet-x9e.18`** at the per-`@Benchmark`-method
     * grain, from those same three runs' retained JSON — arithmetic over kept artifacts,
     * not a fresh measurement, which is why every provenance field below is unchanged.
     * The class declares ONE `@Benchmark`, so the method's row set IS the class's and the
     * statistic is unmoved from `computenet-3sua`'s 2026-08-27 re-derivation: still
     * 0.19864889236475775, still floor 0.398. The per-row median that sets it is
     * `realSnapshot` OR_MAP_CELL `N1E5`, whose observations across runs 1 / 2 / 3 were
     * 0.5217864937179187 / 0.11856655814861747 / 0.19864889236475775.
     *
     * **This SUPERSEDES `computenet-ahn0`'s derivation of the same class**, which
     * observed 0.2961501149112133 and published a floor of 0.593. That measurement is not
     * retained as a second entry and must not be: all three of its runs were measured
     * under JBR 25.0.2 (`# VM version: JDK 25.0.2 …` in each log, `jdkVersion` 25.0.2 on
     * every row), not the module's declared toolchain JDK 21, which step 1 of the
     * pre-registered procedure requires.
     *
     * **The observations behind this entry are `computenet-7v7m`'s `cellfootprint-{1,2,3}.json`
     * — NOT a ledger under `$HOME/computenet-runs/floor-derivations/CellFootprintBenchmark/`**
     * (`computenet-xppx`). Confirmed for this re-fold by folding the JSONs at the old
     * whole-class grain and reproducing the superseded 0.19864889236475775 exactly; the
     * directory of that name folds to 0.485 and would not have. A future re-derivation of
     * this class must use the JSONs named above, never whatever that directory contains.
     */
    ClassNoiseFloor(
        benchmarkClass = "CellFootprintBenchmark",
        benchmarkMethod = "realSnapshot",
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
        // block's "3 sequential repeat runs" is a claim the runs support.
        assembly = DerivationAssembly.WholeClassRuns(runs = 3),
    ),
    /**
     * `computenet-akfa`, 2026-08-27; re-folded per method 2026-08-30 by
     * `computenet-x9e.18`. Three sequential runs of
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
     * measuring JVM and one harness sha. Raw artifacts:
     * `$HOME/computenet-runs/floor-derivations/BoundedReadBenchmark/`. Re-folding that
     * ledger at the old whole-class grain reproduces the superseded 0.028527147482145923
     * exactly, which is how it was confirmed to be this class's published input set.
     *
     * This method's three `@Param` rows are `scale` = `N1E3` / `N1E4` / `N1E5`, whose
     * medians are 0.02389 / 0.02553 / 0.02456; the row that sets the statistic is
     * `realDirect[scale=N1E4]`, observed at 0.024338591249818783 / 0.02552534622609911 /
     * 0.038565703815156345. Under the retired per-class grain this method shared
     * `realHostedSnapshotOf`'s 0.058; its own floor is 0.052.
     */
    ClassNoiseFloor(
        benchmarkClass = "BoundedReadBenchmark",
        benchmarkMethod = "realDirect",
        observedRobustDispersion = 0.02552534622609911,
        runs = 3,
        derivedOn = "2026-08-27",
        harnessCommitSha = "19055b951",
        hostState = QUIESCED_HOST_STATE,
        // The class-level annotations, which this method does not override.
        jmhConfig = "mode=AverageTime unit=ms forks=5 warmup=5x1s measurement=5x1s",
        measuringJvm = "JDK 21.0.5, OpenJDK 64-Bit Server VM, 21.0.5+11-LTS " +
            "(Amazon Corretto; :bench's resolved toolchain launcher)",
        assembly = DerivationAssembly.WholeClassRuns(runs = 3),
    ),
    /**
     * The second method of the same `computenet-akfa` derivation — see the `realDirect`
     * entry above for the runs, the gate readings, the ledger and how the input set was
     * confirmed. Nothing about the measurement differs; only the row set folded does.
     *
     * The row that sets the statistic is `realHostedSnapshotOf[scale=N1E4]`, whose three
     * observations were 0.028527147482145923 / 0.02740650775589135 /
     * 0.031330611761921666. This is the row that set the RETIRED per-class floor as well,
     * so this method's 0.058 is numerically the old class floor — an outcome of the
     * partition, not a carry-over: the number was recomputed from the ledger and happens
     * to be the same because the maximum-setting row is inside this method.
     *
     * The largest single observation in the class is 0.0408 (`realDirect` at `N1E5`),
     * above this method's statistic and belonging to the OTHER method, which is why a
     * floor is not a bound on the observations it was drawn from.
     */
    ClassNoiseFloor(
        benchmarkClass = "BoundedReadBenchmark",
        benchmarkMethod = "realHostedSnapshotOf",
        observedRobustDispersion = 0.028527147482145923,
        runs = 3,
        derivedOn = "2026-08-27",
        harnessCommitSha = "19055b951",
        hostState = QUIESCED_HOST_STATE,
        jmhConfig = "mode=AverageTime unit=ms forks=5 warmup=5x1s measurement=5x1s",
        measuringJvm = "JDK 21.0.5, OpenJDK 64-Bit Server VM, 21.0.5+11-LTS " +
            "(Amazon Corretto; :bench's resolved toolchain launcher)",
        assembly = DerivationAssembly.WholeClassRuns(runs = 3),
    ),
    /**
     * `computenet-akfa`, 2026-08-28; re-folded per method 2026-08-30 by
     * `computenet-x9e.18` — **the class the grain change was noticed on**. Three
     * sequential runs of `civictech.bench.micro.FanOutScalingBenchmark` (30 rows each:
     * `sim`, `real`, `simFixedState`, `realFixedState`, `simBatchFixedState`,
     * `realBatchFixedState` over `FanDegree`'s five constants) from
     * `bench/build/libs/bench-jmh.jar` at `57c860075`, on the pinned host NL-MGD6FQJW91
     * (16 cores, gate threshold 4.00), each preceded by its own attestation of
     * `run-series.sh`'s gate — the readings immediately before the three invocations were
     * 3.21, 2.74 and 2.61 — and each verified after the fact against its own log's
     * `# VM version:` banner, every one of which read
     * `JDK 21.0.5, OpenJDK 64-Bit Server VM, 21.0.5+11-LTS`.
     *
     * Accumulated through [FloorDerivationLedger], which checked the row set COMPLETE
     * against `EXPECTED_PLAN_ROW_COUNTS` (30 rows x 3 observations = 90) and
     * single-JVM / single-harness-sha. Raw artifacts:
     * `$HOME/computenet-runs/floor-derivations/FanOutScalingBenchmark/`. Re-folding that
     * ledger at the old whole-class grain reproduces the superseded 0.4762179191123049
     * exactly, which is how it was confirmed to be this class's published input set.
     *
     * **The six entries that follow are where the retired single floor of 0.953 went.**
     * Each carries its OWN [ClassNoiseFloor.jmhConfig] — read off the class's source
     * annotations, not chosen here — and the three distinct configurations are precisely
     * the three sub-families the single floor was averaging over. That is the grain
     * argument made visible: the class declares `@BenchmarkMode`/`@Fork`/`@Warmup`/
     * `@Measurement` overrides at the METHOD, so a per-method floor is a per-regime floor.
     *
     * `real` is one of the two `Mode.AverageTime` methods that draw the fan-out curve. Its
     * five `degree` rows have medians 0.02 to 0.053 and the row that sets its statistic is
     * `real[degree=D256]`, observed at 0.05344856341208027 / 0.04898584474755644 /
     * 0.07354653878023955. Its floor is 0.107 against the retired 0.953 — a bound about
     * nine times tighter, and the concrete measure of what the per-class grain cost this
     * sub-family.
     */
    ClassNoiseFloor(
        benchmarkClass = "FanOutScalingBenchmark",
        benchmarkMethod = "real",
        observedRobustDispersion = 0.05344856341208027,
        runs = 3,
        derivedOn = "2026-08-28",
        harnessCommitSha = "57c860075",
        hostState = QUIESCED_HOST_STATE,
        // The class-level `@BenchmarkMode(AverageTime)` `@Fork(5)` 5+5 x 1s that `sim`
        // and `real` inherit — this method declares no override.
        jmhConfig = "mode=AverageTime unit=us forks=5 warmup=5x1s measurement=5x1s",
        measuringJvm = "JDK 21.0.5, OpenJDK 64-Bit Server VM, 21.0.5+11-LTS " +
            "(Amazon Corretto; :bench's resolved toolchain launcher)",
        assembly = DerivationAssembly.WholeClassRuns(runs = 3),
    ),
    /**
     * The `@OperationsPerInvocation(200)` batch method paired with `simBatchFixedState` —
     * see the `real` entry above for the runs, the ledger and the confirmation. The row
     * that sets its statistic is `realBatchFixedState[degree=D256]`, observed at
     * 0.3465268573449362 / 0.4448126689446901 / 0.06600571487591653: two of three repeats
     * near 0.4, so the median is a reproducible property of the row rather than one
     * contaminated repeat, which is what [classFloorStatistic] requires. Floor 0.694.
     *
     * This sub-family is genuinely the noisiest in the class, and under the method grain
     * it says so on its own account instead of setting a bound for the fan-out curve.
     */
    ClassNoiseFloor(
        benchmarkClass = "FanOutScalingBenchmark",
        benchmarkMethod = "realBatchFixedState",
        observedRobustDispersion = 0.3465268573449362,
        runs = 3,
        derivedOn = "2026-08-28",
        harnessCommitSha = "57c860075",
        hostState = QUIESCED_HOST_STATE,
        jmhConfig = "mode=AverageTime unit=us forks=3 warmup=3x1s measurement=6x1s " +
            "opsPerInvocation=200",
        measuringJvm = "JDK 21.0.5, OpenJDK 64-Bit Server VM, 21.0.5+11-LTS " +
            "(Amazon Corretto; :bench's resolved toolchain launcher)",
        assembly = DerivationAssembly.WholeClassRuns(runs = 3),
    ),
    /**
     * One of the two `Mode.SingleShotTime` `@Fork(3)` methods — a regime the class-level
     * annotations do not describe at all, which is the clearest case in the table for
     * folding at the method. See the `real` entry above for the runs and the ledger.
     *
     * The row that sets its statistic is `realFixedState[degree=D16]`, observed at
     * 0.1776885372889194 / 0.23553487816595828 / 0.2810243813213844. Floor 0.472. Note
     * that it is the SMALLEST `degree` that sets this sub-family's floor, the opposite of
     * the `AverageTime` methods above: a single shot at low fan-out is dominated by
     * fixed costs it cannot amortise, and that is a property of the regime rather than
     * interference in it.
     */
    ClassNoiseFloor(
        benchmarkClass = "FanOutScalingBenchmark",
        benchmarkMethod = "realFixedState",
        observedRobustDispersion = 0.23553487816595828,
        runs = 3,
        derivedOn = "2026-08-28",
        harnessCommitSha = "57c860075",
        hostState = QUIESCED_HOST_STATE,
        jmhConfig = "mode=SingleShotTime forks=3 warmup=5 measurement=10",
        measuringJvm = "JDK 21.0.5, OpenJDK 64-Bit Server VM, 21.0.5+11-LTS " +
            "(Amazon Corretto; :bench's resolved toolchain launcher)",
        assembly = DerivationAssembly.WholeClassRuns(runs = 3),
    ),
    /**
     * The simulated half of the `Mode.AverageTime` fan-out curve — see the `real` entry
     * above for the runs and the ledger. The row that sets its statistic is
     * `sim[degree=D64]`, observed at 0.07567183802470076 / 0.05998789587833256 /
     * 0.07360411934818362. Floor 0.148.
     *
     * 0.0736 is the largest per-row median anywhere in this class's `AverageTime`
     * sub-family, and it is the number `computenet-x9e.18`'s "at or under 0.074" refers
     * to: under the retired per-class floor of 0.953 these rows were measured against a
     * bound they could not reach even under heavy interference, so for them the per-class
     * floor distinguished about as little as the global [NOISE_FLOOR] it replaced. That
     * is the defect this file opens by describing, arriving from the other end, and it is
     * what the method grain removes.
     */
    ClassNoiseFloor(
        benchmarkClass = "FanOutScalingBenchmark",
        benchmarkMethod = "sim",
        observedRobustDispersion = 0.07360411934818362,
        runs = 3,
        derivedOn = "2026-08-28",
        harnessCommitSha = "57c860075",
        hostState = QUIESCED_HOST_STATE,
        jmhConfig = "mode=AverageTime unit=us forks=5 warmup=5x1s measurement=5x1s",
        measuringJvm = "JDK 21.0.5, OpenJDK 64-Bit Server VM, 21.0.5+11-LTS " +
            "(Amazon Corretto; :bench's resolved toolchain launcher)",
        assembly = DerivationAssembly.WholeClassRuns(runs = 3),
    ),
    /**
     * **The method that set the retired per-class floor of 0.953**, and now the only one
     * that carries it. See the `real` entry above for the runs and the ledger.
     *
     * The row is `simBatchFixedState[degree=D256]`, observed at 0.05878770597834225 /
     * 0.4762179191123049 / 0.4901410407490696 — two of three repeats near 0.48, so the
     * row is typical of itself rather than an outlier, which is exactly the case
     * [classFloorStatistic]'s median is meant to keep and the retired
     * maximum-over-observations would have exaggerated. Floor 0.953.
     *
     * That this number is unchanged is the point of the partition: the class's worst
     * regime keeps the bound its own measurement supports, and the other five methods
     * stop being measured against it.
     */
    ClassNoiseFloor(
        benchmarkClass = "FanOutScalingBenchmark",
        benchmarkMethod = "simBatchFixedState",
        observedRobustDispersion = 0.4762179191123049,
        runs = 3,
        derivedOn = "2026-08-28",
        harnessCommitSha = "57c860075",
        hostState = QUIESCED_HOST_STATE,
        jmhConfig = "mode=AverageTime unit=us forks=3 warmup=3x1s measurement=6x1s " +
            "opsPerInvocation=200",
        measuringJvm = "JDK 21.0.5, OpenJDK 64-Bit Server VM, 21.0.5+11-LTS " +
            "(Amazon Corretto; :bench's resolved toolchain launcher)",
        assembly = DerivationAssembly.WholeClassRuns(runs = 3),
    ),
    /**
     * The simulated `Mode.SingleShotTime` method — see `realFixedState` above for why
     * this regime's floors are high and why the smallest `degree` sets them. The row is
     * `simFixedState[degree=D16]`, observed at 0.2949310405621919 / 0.3288383063811838 /
     * 0.3082968565114873, a tight cluster: this is a reproducibly dispersed row, not a
     * contaminated one. Floor 0.617.
     */
    ClassNoiseFloor(
        benchmarkClass = "FanOutScalingBenchmark",
        benchmarkMethod = "simFixedState",
        observedRobustDispersion = 0.3082968565114873,
        runs = 3,
        derivedOn = "2026-08-28",
        harnessCommitSha = "57c860075",
        hostState = QUIESCED_HOST_STATE,
        jmhConfig = "mode=SingleShotTime forks=3 warmup=5 measurement=10",
        measuringJvm = "JDK 21.0.5, OpenJDK 64-Bit Server VM, 21.0.5+11-LTS " +
            "(Amazon Corretto; :bench's resolved toolchain launcher)",
        assembly = DerivationAssembly.WholeClassRuns(runs = 3),
    ),
    /**
     * `computenet-x9e.17`, 2026-08-28 — the last class to come off the global fallback;
     * re-folded per method 2026-08-30 by `computenet-x9e.18`. Three sequential
     * whole-class runs of `civictech.bench.micro.OperatorThroughputBenchmark` (72 rows
     * each: `sim` and `real` over 18 `Subject` constants x 2 `Direction` constants) from
     * `bench/build/libs/bench-jmh.jar` at `551da80f4`, on the pinned host NL-MGD6FQJW91
     * (16 cores, gate threshold 4.00), each preceded by its own attestation of
     * `run-series.sh`'s gate — the readings immediately before the three invocations were
     * 3.98, 3.99 and 3.28 — and each verified after the fact against its own log's
     * `# VM version:` banner, every one of which read
     * `JDK 21.0.5, OpenJDK 64-Bit Server VM, 21.0.5+11-LTS`. The runs took 00:37:11,
     * 00:37:01 and 00:37:02 by their own JMH `# Run complete.` lines — which is the
     * quiesced slot the recompute-don't-re-measure decision was weighed against.
     *
     * **Run 1 was measured in an earlier slot, as a calibration, and retained rather than
     * ingested.** That slot's dispatch carried a decision gate — time run 1, continue only
     * if it came in at or under 30 minutes — and 00:37:11 took the stop branch, so nothing
     * was entered on one run. It is a valid whole-class observation and was ingested
     * unchanged because `computenet-x9e.17` ran from the same commit its jar is stamped
     * at, which is the condition [FloorDerivationLedger] enforces.
     *
     * Accumulated through [FloorDerivationLedger], which checked the row set COMPLETE
     * against `EXPECTED_PLAN_ROW_COUNTS` (72 rows x 3 observations = 216) and
     * single-JVM / single-harness-sha. Raw artifacts:
     * `$HOME/computenet-runs/floor-derivations/OperatorThroughputBenchmark/`. Re-folding
     * that ledger at the old whole-class grain reproduces the superseded
     * 0.05106599919551368 exactly, which is how it was confirmed to be this class's
     * published input set.
     *
     * `real`'s 36 rows are set by `real[direction=INSERT, subject=UNION]`, observed at
     * 0.027521119935862228 / 0.04671765550789494 / 0.05615832981889777. Floor 0.094
     * against the retired class floor of 0.103 — a small move, as expected for a class
     * whose every row is measured under ONE annotation configuration. Note that the
     * class's largest single observation, 0.07199549750373181 (`real`, `RETRACT`,
     * `COALESCING_COMBINE`), belongs to this method and does NOT set its floor: that
     * row's other two repeats are 0.0153 and 0.0075, so its median is 0.0153. Under the
     * maximum-over-every-observation estimator `computenet-3sua` replaced it would have
     * set the floor at 0.144 single-handedly.
     */
    ClassNoiseFloor(
        benchmarkClass = "OperatorThroughputBenchmark",
        benchmarkMethod = "real",
        observedRobustDispersion = 0.04671765550789494,
        runs = 3,
        derivedOn = "2026-08-28",
        harnessCommitSha = "551da80f4",
        hostState = QUIESCED_HOST_STATE,
        jmhConfig = "mode=Throughput unit=ops/s forks=2 warmup=5x1s measurement=10x1s " +
            "opsPerInvocation=512",
        measuringJvm = "JDK 21.0.5, OpenJDK 64-Bit Server VM, 21.0.5+11-LTS " +
            "(Amazon Corretto; :bench's resolved toolchain launcher)",
        assembly = DerivationAssembly.WholeClassRuns(runs = 3),
    ),
    /**
     * The simulated half of the same `computenet-x9e.17` derivation — see the `real`
     * entry above for the runs, the calibration run, the ledger and the confirmation.
     *
     * The row that sets its statistic is `sim[direction=INSERT, subject=LOOKUP_JOIN]`,
     * observed at 0.05139149274325982 / 0.005662117024059593 / 0.05106599919551368. Two
     * of its three repeats sit at ~0.051, so the median is a reproducible property of
     * that row. This is the row that set the RETIRED per-class floor, so this method's
     * 0.103 is numerically the old class floor — recomputed from the ledger, not carried
     * over.
     *
     * **The looseness recorded for this class is NOT repaired by the method grain, and
     * that is stated rather than corrected.** The median of this method's 36 per-row
     * medians is about 0.011, roughly an order of magnitude below its own floor. The
     * spread is `@Param` spread inside one regime — the workload sweep the class exists
     * to draw — and folding it away is the per-`@Param` grain that was considered and
     * rejected in [classFloorStatistic]. What the floor is NOT is the previous state: the
     * global [NOISE_FLOOR] of 0.005 was exceeded by 210 of this class's 216 observations
     * and by all 72 row medians, so it fired essentially everywhere and separated nothing.
     */
    ClassNoiseFloor(
        benchmarkClass = "OperatorThroughputBenchmark",
        benchmarkMethod = "sim",
        observedRobustDispersion = 0.05106599919551368,
        runs = 3,
        derivedOn = "2026-08-28",
        harnessCommitSha = "551da80f4",
        hostState = QUIESCED_HOST_STATE,
        jmhConfig = "mode=Throughput unit=ops/s forks=2 warmup=5x1s measurement=10x1s " +
            "opsPerInvocation=512",
        measuringJvm = "JDK 21.0.5, OpenJDK 64-Bit Server VM, 21.0.5+11-LTS " +
            "(Amazon Corretto; :bench's resolved toolchain launcher)",
        assembly = DerivationAssembly.WholeClassRuns(runs = 3),
    ),
)

/**
 * [derivations] indexed by their [ClassNoiseFloor.key] — the (class, `@Benchmark` method)
 * pair (`computenet-x9e.18`; keyed by class alone until 2026-08-30).
 *
 * Refuses two derivations naming one KEY: a method with two floors has no floor, and
 * silently keeping the last one would make the resolved bound depend on list order. Two
 * derivations naming one CLASS are now ordinary and expected — that is what a class of
 * several `@Benchmark` methods looks like.
 */
fun floorTable(derivations: List<ClassNoiseFloor>): Map<FloorKey, Double> {
    val duplicated = derivations.groupingBy { it.key }.eachCount().filterValues { it > 1 }
    require(duplicated.isEmpty()) {
        "each (benchmark class, @Benchmark method) pair may carry at most one derived " +
            "floor, found " +
            duplicated.entries.sortedBy { it.key.describe() }
                .joinToString { "'${it.key.describe()}' x ${it.value}" }
    }
    return derivations.associate { it.key to it.floor }
}

/**
 * The live table, derived from [CLASS_NOISE_FLOOR_DERIVATIONS]: eleven `@Benchmark`
 * methods across the four classes the procedure names. Everything else falls back to
 * [NOISE_FLOOR].
 */
val CLASS_NOISE_FLOOR_TABLE: Map<FloorKey, Double> = floorTable(CLASS_NOISE_FLOOR_DERIVATIONS)

/**
 * The relative-dispersion bound a row of [benchmarkClass]'s [benchmarkMethod] is
 * classified against: that METHOD's own derived floor where one exists, and [NOISE_FLOOR]
 * where none does.
 *
 * **The resolution takes the method as well as the class since `computenet-x9e.18`.** A
 * caller that knows only the class can no longer be answered honestly: a class of several
 * `@Benchmark` methods holds several floors, and picking one of them — or folding them
 * together with a `max` — would restore exactly the per-class bound the grain change
 * retired. So a `null` or blank [benchmarkMethod] resolves to [NOISE_FLOOR] on the same
 * footing as a `null` class, rather than to some summary of the class's floors.
 *
 * A `null` or blank [benchmarkClass] is a row whose class is not known to the caller —
 * `Footprint.toResults` builds rows from a walk rather than from a JMH results file, for
 * instance — and resolves to [NOISE_FLOOR]. It is not an error: an unknown class has no
 * derived floor by definition, which is exactly the fallback case.
 *
 * @param floors the table to resolve against. Defaults to [CLASS_NOISE_FLOOR_TABLE]; the
 *   parameter exists so every branch of the resolution can be exercised on every
 *   `:bench:test` run without depending on what the live table happens to hold — a
 *   criterion that executes once in its life is not pinned.
 */
fun noiseFloorFor(
    benchmarkClass: String?,
    benchmarkMethod: String?,
    floors: Map<FloorKey, Double> = CLASS_NOISE_FLOOR_TABLE,
): Double {
    if (benchmarkClass.isNullOrBlank() || benchmarkMethod.isNullOrBlank()) return NOISE_FLOOR
    return floors[FloorKey(benchmarkClass, benchmarkMethod)] ?: NOISE_FLOOR
}

/**
 * Whether (`benchmarkClass`, `benchmarkMethod`) resolves to a floor of its own, as
 * opposed to falling back to the global [NOISE_FLOOR].
 *
 * Distinct from `noiseFloorFor(...) != NOISE_FLOOR`, and deliberately so: a method whose
 * derived floor happens to equal 0.005 has a floor of its own, and a message that called
 * it a fallback would be wrong about where the bound came from.
 *
 * The name is kept from the per-class era on purpose — it is the same question asked at
 * the finer grain, and renaming it would churn every call site and every test name for a
 * word.
 */
fun hasClassFloor(
    benchmarkClass: String?,
    benchmarkMethod: String?,
    floors: Map<FloorKey, Double> = CLASS_NOISE_FLOOR_TABLE,
): Boolean = !benchmarkClass.isNullOrBlank() && !benchmarkMethod.isNullOrBlank() &&
    floors.containsKey(FloorKey(benchmarkClass, benchmarkMethod))

/**
 * How a rendered note names the bound a row was classified against — which floor, and
 * whether it is the row's own method's or the global fallback.
 *
 * The two phrasings are different on purpose. `NOISE_FLOOR` is a bit mixer's dispersion
 * on an idle host, so a hosted-graph row above it means almost nothing; a row above its
 * own method's floor means that benchmark was noisier than it is when the machine is
 * quiet. A reader must be able to tell the two statements apart from the text alone.
 *
 * The derived phrasing names `Class.method`, not the class: two methods of one class can
 * carry floors an order of magnitude apart (`FanOutScalingBenchmark.sim` at 0.148 against
 * `.simBatchFixedState` at 0.953), so a note that said only "the FanOutScalingBenchmark
 * class floor" would not identify the bound it was talking about.
 */
fun describeFloor(
    benchmarkClass: String?,
    benchmarkMethod: String?,
    floors: Map<FloorKey, Double> = CLASS_NOISE_FLOOR_TABLE,
): String {
    val floor = noiseFloorFor(benchmarkClass, benchmarkMethod, floors)
    return if (hasClassFloor(benchmarkClass, benchmarkMethod, floors)) {
        "the $benchmarkClass.$benchmarkMethod method floor $floor (derived from that " +
            "method's own quiesced repeat runs)"
    } else {
        "the harness sanity bound NOISE_FLOOR $floor"
    }
}

/**
 * Classifies [result] against the bound (`benchmarkClass`, `benchmarkMethod`) resolves to
 * (`computenet-cm4w`; per-method since `computenet-x9e.18`).
 *
 * The arithmetic is [classifyAgainst]'s and is unchanged from the single-argument
 * [classify]: magnitude of the relative dispersion, non-finite refused explicitly,
 * strictly-above is [Reportability.Unreportable]. Only the bound is chosen differently.
 */
fun classify(
    result: BenchResult,
    benchmarkClass: String?,
    benchmarkMethod: String?,
    floors: Map<FloorKey, Double> = CLASS_NOISE_FLOOR_TABLE,
): Reportability =
    classifyAgainst(result, noiseFloorFor(benchmarkClass, benchmarkMethod, floors))

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
        "## ${derivation.derivedOn} — per-method noise floor for " +
            "`${derivation.benchmarkClass}.${derivation.benchmarkMethod}`, derived " +
            "forward from its own quiesced repeat runs"
    )
    appendLine(
        "Harness: ${derivation.harnessCommitSha} · host state ${derivation.hostState} · " +
            "$gathering"
    )
    appendLine(
        "JMH: ${derivation.jmhConfig} (this @Benchmark method's own annotation " +
            "configuration, class-level defaults included)"
    )
    // The measuring JVM is rendered, not left to prose, because a prose caveat is exactly
    // what was missing when a floor derived under JBR 25 shipped as if it described the
    // toolchain JDK (computenet-7v7m). See ClassNoiseFloor.measuringJvm.
    appendLine("Measured under: ${derivation.measuringJvm}")
    appendLine("| quantity | value |")
    appendLine("| --- | --- |")
    appendLine(
        "| statistic (max over this METHOD's rows of the per-row MEDIAN relative " +
            "dispersion) $statisticOver | ${derivation.observedRobustDispersion} |"
    )
    appendLine("| margin, fixed before the runs (CLASS_FLOOR_MARGIN) | $CLASS_FLOOR_MARGIN |")
    appendLine(
        "| derived floor = margin x statistic, rounded up to three decimals | " +
            "${derivation.floor} |"
    )
    appendLine(
        "Estimator: `classFloorStatistic` — the MEDIAN of each row's relative dispersions " +
            "across its repeats, then the MAXIMUM of those medians across the rows of " +
            "ONE `@Benchmark` METHOD. Robust within a row (one contaminated repeat " +
            "cannot set a floor), conservative across rows (a reproducibly " +
            "high-dispersion row is a fact about the method, not noise in it), and " +
            "partitioned by method because `@BenchmarkMode`/`@Fork`/`@Warmup`/" +
            "`@Measurement` are method-level, so a method is the unit that carries one " +
            "measurement regime (`computenet-x9e.18`). Pre-registered in " +
            "`ClassNoiseFloor.kt` before it was computed over any measurement — see " +
            "`classFloorStatistic`'s own documentation for the argument, which is made " +
            "from robustness and never from the value the statistic yields."
    )
    appendLine(
        "Derivation: forward. The margin was fixed in `ClassNoiseFloor.kt` before any " +
            "derived number existed; the floor is computed from the statistic by " +
            "`ClassNoiseFloor.floor` and is not hand-entered. What it establishes: on a " +
            "quiesced host, every row of " +
            "`${derivation.benchmarkClass}.${derivation.benchmarkMethod}` measured under " +
            "this configuration had a TYPICAL (median) relative dispersion at or under " +
            "${derivation.observedRobustDispersion}, so a later row above " +
            "${derivation.floor} is more dispersed than this class typically is when the " +
            "machine is quiet. What it does NOT establish: that no individual observation " +
            "in the derivation exceeded ${derivation.observedRobustDispersion} — a median " +
            "does not bound the sample it is drawn from, and single high repeats are " +
            "exactly what this estimator declines to build a floor on. Nor anything about " +
            "another " +
            "benchmark class or another `@Benchmark` method of this one — including a " +
            "sibling method of the same class, whose floor is derived separately and " +
            "may differ by an order of magnitude — about this method under a different " +
            "annotation configuration, about it on another host, or about it under " +
            "a JVM other than ${derivation.measuringJvm}."
    )
}
