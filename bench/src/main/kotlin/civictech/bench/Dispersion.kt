package civictech.bench

/**
 * The relative-dispersion threshold [classify] compares a [BenchResult] against
 * (BS-11, the classification half of `[BEN1-25]`).
 *
 * **PROVISIONAL.** This value is a placeholder for this task only. The
 * noise-floor sibling task of this feature (`computenet-x9e.3`) replaces it with a
 * value derived from an observed noise floor measured on real hardware; nothing in
 * this task's scope justifies `0.05` as the "real" threshold. `ResultModelTest`'s
 * classification tests are written threshold-relative (constructing results just
 * above/below this constant, never against a hard-coded ratio) precisely so they
 * keep passing once the sibling task changes this value.
 */
const val NOISE_FLOOR: Double = 0.05

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
 * Classifies [result] by comparing [BenchResult.relativeDispersion] against
 * [NOISE_FLOOR]: strictly above is [Reportability.Unreportable], at or below is
 * [Reportability.Reportable].
 */
fun classify(result: BenchResult): Reportability =
    if (result.relativeDispersion > NOISE_FLOOR) {
        Reportability.Unreportable
    } else {
        Reportability.Reportable
    }
