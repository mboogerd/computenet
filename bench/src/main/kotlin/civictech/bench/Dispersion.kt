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
 */
const val NOISE_FLOOR: Double = 0.005

/**
 * Whether a [BenchResult]'s relative dispersion is low enough to report
 * (BS-11, the classification half of `[BEN1-25]`).
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
