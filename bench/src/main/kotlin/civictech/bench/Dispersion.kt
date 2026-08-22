package civictech.bench

/**
 * The relative-dispersion threshold [classify] compares a [BenchResult] against
 * (BS-11, the classification half of `[BEN1-25]`).
 *
 * **DERIVED FROM MEASUREMENT, NOT ASSERTED** (`[BEN1-25]`'s provenance half; the epic's
 * "Honesty note on verifiability"). The full derivation, with the raw JMH output and the
 * limits of what it supports, is the first entry of `doc/bench/findings.md` — this KDoc
 * is its summary, and the two are meant to be read together.
 *
 * ## What was measured
 *
 * `civictech.bench.micro.SmokeBenchmark.baseline` — the discovery sentinel, a
 * deterministic branch-free bit mixer, which is the cheapest and quietest thing this
 * repository can measure. Procedure: `./gradlew :bench:jmhJar` once, then **three**
 * sequential executions of `bench/build/libs/bench-jmh.jar` (the jar directly, not
 * `./gradlew :bench:jmh`), under the module's declared toolchain JDK.
 *
 * - Date: 2026-08-18.
 * - Machine: Apple M2 Pro, 10 cores, 16 GiB, macOS 26.6.1 (Darwin 25.6.0, arm64),
 *   host deliberately quiesced for the measurement.
 * - JVM: Eclipse Adoptium (Temurin) 21.0.11+10-LTS, no VM options, default 4 GiB heap.
 * - Harness commit: `cbea0290`.
 * - JMH 1.37 defaults: `Mode.AverageTime`, ns/op, 5 forks x (5 warmup + 5 measurement)
 *   iterations x 10 s, 1 thread, compiler blackholes.
 *
 * ## Raw observations (score +/- error at 99.9% confidence, ns/op)
 *
 * | run | score | error(99.9%) | relative dispersion |
 * | --- | --- | --- | --- |
 * | 1 | 4.321050323941347 | 0.004992364297944783 | 0.0011554 |
 * | 2 | 4.324870473041170 | 0.010675229190884424 | 0.0024683 |
 * | 3 | 4.319768621726090 | 0.003294929916128341 | 0.0007628 |
 *
 * The three runs agree: their scores span 0.0051 ns/op, a run-to-run relative spread of
 * 0.0012 — the same order as the within-run errors, so the error bars are not
 * understating the variation between runs. Run 2's wider error comes from one fork mean
 * at 4.3459 against four near 4.319; that is ordinary fork-to-fork variation, and no run
 * showed the disagreement that would indicate interference from other load on the host.
 *
 * ## Derivation (forward, and in that order)
 *
 * Observed noise floor = **max** relative dispersion across the runs = `0.0024683`
 * (run 2). Threshold = 2 x the observed floor, rounded up to three decimals = **0.005**.
 *
 * The 2x margin was fixed and recorded on `computenet-x9e.3.3` **before the first run
 * reported a number**, precisely so it could not be reverse-engineered from one. Its
 * justification: the observation is a lower bound three times over — the cheapest
 * possible benchmark, a deliberately idle host, and an error that measures dispersion
 * *within* one run rather than across runs or across benchmarks. The threshold must
 * therefore sit above the observed floor, or even an ideal benchmark on an idle machine
 * would classify [Reportability.Unreportable] and the harness could never report
 * anything. One binary order of headroom is the smallest margin that admits that
 * structural gap while still refusing a result more than twice as dispersed as the
 * idealized baseline.
 *
 * ## What this value does NOT establish
 *
 * It is the noise floor of *this host* measuring *the cheapest possible benchmark*. A
 * real measurement of kernel operators may legitimately exceed 0.005 without being
 * meaningless — that outcome is information about the benchmark (or the host), and the
 * honest response is to say so, not to widen this constant until the result fits.
 * Re-deriving it is legitimate; re-deriving it *forward*, from a fresh recorded
 * measurement whose margin is stated before the numbers are known, is the condition.
 * Appending the new derivation to `doc/bench/findings.md` is part of that condition, not
 * an optional courtesy.
 *
 * ## DEMOTED 2026-08-22 (`computenet-785b`): what this constant still gates
 *
 * The value is unchanged and **not re-derived**; what changed is its *reach*. It no
 * longer gates whether a measurement may be reported. It is now a **sanity bound on the
 * harness itself** — the quantity `SmokeBenchmark.baseline` is re-measured against to
 * detect drift in the discovery sentinel, which is the only thing the 2026-08-18
 * derivation above ever measured.
 *
 * The reason is stated in the section directly above, and the 2026-08-21 findings review
 * measured its consequence: because 0.005 is the dispersion of the cheapest possible
 * operation on a deliberately idle host, and hosted-graph measurements intrinsically run
 * 0.01–0.15, an absolute gate at 0.005 classified **66 of 72** throughput rows and **all
 * 10** fan-out rows [Unreportable]. A gate that refuses nearly everything measured is not
 * protecting the reader from noise; it is withholding every number while the reader has
 * no way to see how noisy each one is.
 *
 * What replaced it is claim-relative and lives below: [resolveEffect]. A **standalone
 * number is always reportable with its error bar attached** — `value ± dispersion unit`
 * states its own precision, so the reader can discount it themselves. A **comparison** is
 * reportable only when the claimed effect exceeds the combined error bars of the rows it
 * is drawn from. The forward derivation of that criterion's margin is the 2026-08-22
 * entry appended to `doc/bench/findings.md`, per the amendment condition above.
 */
const val NOISE_FLOOR: Double = 0.005

/**
 * Whether a [BenchResult]'s relative dispersion is at or under the harness's own sanity
 * bound (BS-11, the classification half of `[BEN1-25]`).
 *
 * **This is no longer a reportability gate** (`computenet-785b`; see [NOISE_FLOOR]'s
 * "DEMOTED" section). [Unreportable] does not mean the result may not be published — a
 * standalone number is always reportable with its error bar attached, and whether a
 * *comparison* may be drawn is [resolveEffect]'s question, not this one. The names are
 * kept because the harness's own drift check ([classify] over `SmokeBenchmark.baseline`)
 * is what they were derived for and still describe.
 */
enum class Reportability {
    /** [BenchResult.relativeDispersion] is at or below [NOISE_FLOOR]. */
    Reportable,

    /** [BenchResult.relativeDispersion] exceeds [NOISE_FLOOR]. */
    Unreportable,
}

/**
 * Classifies [result] by comparing the MAGNITUDE of [BenchResult.relativeDispersion]
 * against [NOISE_FLOOR]: strictly above is [Reportability.Unreportable], at or below
 * is [Reportability.Reportable].
 *
 * [BenchResult.relativeDispersion] is `dispersion / value` — a signed ratio, because
 * [BenchResult.value] is not required to be positive. A direct `relativeDispersion >
 * NOISE_FLOOR` comparison (the original, defective form) lets two shapes walk around
 * the gate: a negative [BenchResult.value] makes the ratio negative, which is never
 * `>` a positive threshold, and `value == 0.0` with `dispersion == 0.0` makes the
 * ratio `NaN`, and IEEE 754 defines every direct comparison against `NaN` — including
 * `NaN > x` — as `false`. Both would otherwise fall through to [Reportability.Reportable].
 * Comparing the absolute value closes the sign case; explicitly refusing a
 * non-finite magnitude (rather than relying on `NaN > x` being `false`) closes the
 * `NaN` case instead of accidentally depending on the same IEEE 754 behavior that
 * caused it.
 */
fun classify(result: BenchResult): Reportability {
    val magnitude = kotlin.math.abs(result.relativeDispersion)
    return if (!magnitude.isFinite() || magnitude > NOISE_FLOOR) {
        Reportability.Unreportable
    } else {
        Reportability.Reportable
    }
}

/**
 * How much wider than the combined error bars a claimed effect must be before
 * [resolveEffect] calls it resolved (`computenet-785b`).
 *
 * **The margin is 1x, and it was fixed before the numbers it would be applied to were
 * known** — the condition [NOISE_FLOOR]'s KDoc states for any amendment to this file's
 * criteria. Two facts make that checkable rather than merely asserted:
 *
 * - The convention is **adopted, not invented here**. `FanOutFixedStateRenderTest` and
 *   `FanOutBatchFixedStateRenderTest` already resolve a segment marginal by
 *   `|marginal| > combinedError`, with `combinedError` the two endpoints' 99.9%
 *   half-widths **summed**, and that criterion produced the epic's cleanest verdict
 *   (2026-08-20, G-43's "linear in degree, not worse" SURVIVES). This constant
 *   generalizes a rule whose margin was settled by an entry that could not see this
 *   one's numbers.
 * - No hosted-graph comparison was evaluated under it before it was written down. This
 *   change is harness and documentation only; it runs no sweep and re-derives no
 *   measured constant.
 *
 * Why 1x and not [NOISE_FLOOR]'s 2x: the conservatism is already inside the combination.
 * [combinedError] is the **sum** of the two half-widths, which is the widest defensible
 * way to combine them — it is what you get when the two errors are perfectly correlated
 * and both point against the claim. Root-sum-square, the usual choice for independent
 * errors, is ~0.71x as wide for two equal bars, so the sum already carries ~1.41x of
 * headroom over it; and the bars themselves are 99.9% half-widths, not standard errors.
 * Doubling on top of that would refuse effects the data does establish, which is the
 * failure this criterion exists to stop repeating.
 */
const val COMBINED_ERROR_MARGIN: Double = 1.0

/**
 * Whether a claimed effect between two measurements is established by the data, or is
 * inside the noise (`computenet-785b`).
 */
enum class EffectResolution {
    /**
     * The magnitude of the difference exceeds [combinedError] scaled by
     * [COMBINED_ERROR_MARGIN]: the sign and the size of the effect are established, and
     * the comparison may be reported.
     */
    Resolved,

    /**
     * The difference does not exceed the combined error bars. The two rows are still
     * reportable *individually*, each with its own error bar; what may not be reported is
     * a claim that one differs from the other, because these measurements do not
     * establish it — not even its sign.
     */
    Unresolved,
}

/**
 * The two rows' error bars combined, in their shared unit — the conservative **sum** of
 * [BenchResult.dispersion], not the root-sum-square (see [COMBINED_ERROR_MARGIN] for why).
 *
 * @throws IllegalArgumentException if the two results are not expressed in the same unit.
 *   Subtracting a `ns/op` from an `ops/s` produces a number with no meaning, and there is
 *   no conversion this function could apply that would not be a guess about what the
 *   caller meant.
 */
fun combinedError(left: BenchResult, right: BenchResult): Double {
    require(left.unit == right.unit) {
        "cannot combine error bars across units: '${left.unit}' vs '${right.unit}'"
    }
    return left.dispersion + right.dispersion
}

/**
 * Classifies the claim "[left] differs from [right]" against the combined error bars of
 * the two rows (`computenet-785b`).
 *
 * `|left.value - right.value| > COMBINED_ERROR_MARGIN * combinedError(left, right)` is
 * [EffectResolution.Resolved]; anything else — including exact equality, and including
 * two zero-width error bars over two identical values — is
 * [EffectResolution.Unresolved].
 *
 * The comparison is **strict**, matching the fan-out criterion this generalizes: an
 * effect exactly equal to its own error bar is not established by it.
 *
 * @throws IllegalArgumentException if the two results are not expressed in the same unit.
 */
fun resolveEffect(left: BenchResult, right: BenchResult): EffectResolution {
    val bar = COMBINED_ERROR_MARGIN * combinedError(left, right)
    val effect = kotlin.math.abs(left.value - right.value)
    return if (effect > bar) EffectResolution.Resolved else EffectResolution.Unresolved
}
