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
 * ## Status: MACHINERY ONLY — no class floor has been derived yet
 *
 * [CLASS_NOISE_FLOOR_DERIVATIONS] is deliberately **empty**, and everything below
 * therefore falls back to [NOISE_FLOOR] today. That is not an oversight and it is not a
 * placeholder waiting to be filled with a plausible number: deriving a floor requires
 * three sequential repeat runs of the class on a **quiesced** host, and a floor derived
 * through interference would be a floor measuring the interference, silently inherited by
 * every row later classified against it. The derivation runs are routed to a dedicated
 * quiesced slot (`computenet-cm4w`, `metadata.compute=dedicated`); the format, the margin
 * and the resolution rules are fixed HERE, in committed source, **before** the numbers
 * exist, for the same reason [NOISE_FLOOR]'s 2x margin was fixed before its first run
 * reported a number — so neither can be reverse-engineered from the measurement it is
 * applied to.
 *
 * ## The derivation procedure, fixed in advance
 *
 * For each benchmark class, at **that class's own annotation configuration** (its
 * declared `@Fork`/`@Warmup`/`@Measurement`/`@BenchmarkMode`, not a config chosen to make
 * the number smaller):
 *
 * 1. `./gradlew :bench:jmhJar` once, then **three sequential** executions of
 *    `bench/build/libs/bench-jmh.jar` filtered to that class, under the module's declared
 *    toolchain JDK, on a host attested [QUIESCED_HOST_STATE] (`run-series.sh`'s
 *    one-directional guard: 1-minute load average at or below 0.25 x core count, plus the
 *    operator's own attestation that no other session, build or scheduled scan is live —
 *    the guard can refuse a wrong claim and can never confirm a right one).
 * 2. Take, across all rows of all three runs, the **maximum** observed relative
 *    dispersion (`scoreError / score`, JMH's 99.9% bar over its own score). Maximum, not
 *    mean: the floor has to sit above the worst quiet-host row, or a quiet host produces
 *    rows above their own floor.
 * 3. The floor is that maximum times [CLASS_FLOOR_MARGIN], rounded UP to three decimals
 *    ([roundUpToThreeDecimals]) — computed by [ClassNoiseFloor.floor], never hand-typed.
 * 4. Append the derivation to `doc/bench/findings.md` as its own entry, rendered by
 *    [renderDerivation] so the entry cannot drift from the constant.
 *
 * A class with no such derivation has no floor, and its rows are classified against
 * [NOISE_FLOOR] — visibly the global bound, in the message [describeFloor] renders.
 * Falling back is the honest state; inventing a class floor by analogy to another class,
 * or by scaling one, is not a derivation and must not enter
 * [CLASS_NOISE_FLOOR_DERIVATIONS].
 */
object ClassFloorDerivation {

    /** Documentation anchor only; see this object's KDoc. */
    const val PROCEDURE_OWNER: String = "computenet-cm4w"
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
 * One benchmark class's forward-derived noise floor, with the provenance that makes it
 * checkable (`computenet-cm4w`).
 *
 * The floor itself is **not a field**: [floor] is computed from
 * [observedMaxRelativeDispersion] and [CLASS_FLOOR_MARGIN], so the table can never hold a
 * number that disagrees with its own derivation, and changing the margin moves every
 * derived floor in the same commit. That is the same property
 * `resolveEffect(effect, combinedError)` was extracted to give the series comparator: one
 * definition, no second place to restate it.
 *
 * @param benchmarkClass the benchmark's SIMPLE class name — the key
 *   `ThroughputReport.JmhRow.benchmarkClass` exposes, so a results file names its own
 *   floor and a caller does not have to.
 * @param observedMaxRelativeDispersion the MAXIMUM `|scoreError / score|` observed across
 *   every row of all [runs] runs. Must be finite and strictly positive: a zero would mean
 *   a benchmark with no dispersion at all, which is a broken measurement rather than a
 *   perfect one, and it would derive a floor of zero that every subsequent row exceeds.
 * @param runs how many sequential repeat runs were taken. At least [CLASS_FLOOR_MIN_RUNS]
 *   — JMH reports a `NaN` error at or below two measurement samples, and a floor drawn
 *   from fewer than three runs cannot see run-to-run variation at all.
 * @param derivedOn the ISO date of the derivation runs.
 * @param harnessCommitSha the harness commit the runs were made at.
 * @param hostState the attested host state; must be [QUIESCED_HOST_STATE].
 * @param jmhConfig the class's own annotation configuration, as run — recorded so a later
 *   reader can tell whether a fresh row was measured under the config the floor describes.
 */
data class ClassNoiseFloor(
    val benchmarkClass: String,
    val observedMaxRelativeDispersion: Double,
    val runs: Int,
    val derivedOn: String,
    val harnessCommitSha: String,
    val hostState: String,
    val jmhConfig: String,
) {
    init {
        require(benchmarkClass.isNotBlank()) { "benchmarkClass must not be blank" }
        require(observedMaxRelativeDispersion.isFinite() && observedMaxRelativeDispersion > 0.0) {
            "observedMaxRelativeDispersion must be finite and strictly positive, was " +
                "$observedMaxRelativeDispersion"
        }
        require(runs >= CLASS_FLOOR_MIN_RUNS) {
            "a per-class floor requires at least $CLASS_FLOOR_MIN_RUNS sequential repeat " +
                "runs, was $runs"
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
    }

    /**
     * The derived floor: [CLASS_FLOOR_MARGIN] x [observedMaxRelativeDispersion], rounded
     * up to three decimals. Computed, never stored — see this class's KDoc.
     */
    val floor: Double
        get() = roundUpToThreeDecimals(CLASS_FLOOR_MARGIN * observedMaxRelativeDispersion)
}

/**
 * Every per-class floor this repository has actually derived.
 *
 * **EMPTY, on purpose.** See [ClassFloorDerivation]'s "Status" section: the three
 * sequential quiesced repeat runs per class have not been made, and no number may be
 * entered here that did not come from them. While this list is empty every class falls
 * back to [NOISE_FLOOR] and the harness behaves exactly as it did before this file
 * existed — which is the correct behaviour for a floor that has not been measured.
 */
val CLASS_NOISE_FLOOR_DERIVATIONS: List<ClassNoiseFloor> = emptyList()

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

/** The live table, derived from [CLASS_NOISE_FLOOR_DERIVATIONS]. Empty today. */
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
    appendLine(
        "## ${derivation.derivedOn} — per-class noise floor for " +
            "`${derivation.benchmarkClass}`, derived forward from its own quiesced " +
            "repeat runs"
    )
    appendLine(
        "Harness: ${derivation.harnessCommitSha} · host state ${derivation.hostState} · " +
            "${derivation.runs} sequential repeat runs"
    )
    appendLine("JMH: ${derivation.jmhConfig} (the class's own annotation configuration)")
    appendLine("| quantity | value |")
    appendLine("| --- | --- |")
    appendLine(
        "| max observed relative dispersion across all rows of all " +
            "${derivation.runs} runs | ${derivation.observedMaxRelativeDispersion} |"
    )
    appendLine("| margin, fixed before the runs (CLASS_FLOOR_MARGIN) | $CLASS_FLOOR_MARGIN |")
    appendLine(
        "| derived floor = margin x observed, rounded up to three decimals | " +
            "${derivation.floor} |"
    )
    appendLine(
        "Derivation: forward. The margin was fixed in `ClassNoiseFloor.kt` before any " +
            "per-class number existed; the floor is computed from the observation by " +
            "`ClassNoiseFloor.floor` and is not hand-entered. What it establishes: rows " +
            "of `${derivation.benchmarkClass}` measured under this configuration on a " +
            "quiesced host stayed at or under " +
            "${derivation.observedMaxRelativeDispersion} relative dispersion, so a later " +
            "row above ${derivation.floor} is more dispersed than this class is when the " +
            "machine is quiet. What it does NOT establish: anything about another " +
            "benchmark class, about this class under a different annotation " +
            "configuration, or about this class on another host."
    )
}
