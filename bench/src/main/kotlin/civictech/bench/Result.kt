package civictech.bench

/**
 * Which measurement regime produced a [BenchResult] (`[BEN1-26]`).
 *
 * [BenchResult.drive] is a required, non-nullable, no-default field of this type —
 * never a `Boolean`, never a nullable `Drive?` — because the two regimes are not
 * comparable and a result that does not say which one it came from cannot be told
 * apart from one that does.
 */
enum class Drive {
    SIM,
    REAL,
}

/**
 * One measured result (`[BEN1-23]`/`[BEN1-24]`/`[BEN1-26]`, BS-13).
 *
 * @param value the measured statistic itself (a JMH score, or an explicitly stated
 *   equivalent), expressed in [unit].
 * @param unit the unit both [value] and [dispersion] are expressed in (e.g.
 *   `"ops/s"`, `"ns/op"`).
 * @param dispersion JMH's reported error at 99.9% confidence, or an explicitly
 *   stated equivalent, in the same [unit] as [value] (`[BEN1-24]`). Non-negative.
 * @param drive which measurement regime produced this result — required, with no
 *   default (`[BEN1-26]`).
 * @param env the [RunEnvironment] this result was measured under (`[BEN1-23]`).
 */
data class BenchResult(
    val value: Double,
    val unit: String,
    val dispersion: Double,
    val drive: Drive,
    val env: RunEnvironment,
) {
    init {
        require(unit.isNotBlank()) { "unit must not be blank" }
        require(value.isFinite()) { "value must be finite, was $value" }
        require(dispersion.isFinite() && dispersion >= 0.0) {
            "dispersion must be finite and non-negative, was $dispersion"
        }
    }

    /**
     * [dispersion] normalized by [value] — the input BS-11's classification
     * ([classify]) compares against [NOISE_FLOOR] (`[BEN1-25]`).
     */
    val relativeDispersion: Double
        get() = dispersion / value
}

/**
 * A table of [BenchResult]s that all share exactly one [Drive] (`[BEN1-27]`, BS-12),
 * each optionally paired with a caller-supplied per-row entry in [labels] (`[BEN1-30]`).
 *
 * [labels] — not a field on [BenchResult] itself — is this table's per-row subject: what
 * distinguishes an insert row from a retract row of the same operator, or a "before" row
 * from an "after" row, when both share one [BenchResult.unit]. It stays off `BenchResult`
 * deliberately: `ResultModelTest`'s constructor-count check pins that type to exactly one
 * JVM constructor and no defaulted field, so a display label — which is not a measurement
 * fact — belongs beside the table, not inside the result. [labels] defaults to `null` so
 * existing single-argument construction (`FindingsTable(results)`) is unaffected; a table
 * built that way carries no rendering label, and [Findings.entry] refuses to render it
 * (`[BEN1-30]`) rather than inventing one.
 *
 * Construction refuses:
 * - an empty [results] collection,
 * - a [results] collection whose [BenchResult.drive] values form a `Set<Drive>` of
 *   size other than exactly 1 — i.e. any mix of [Drive.SIM] and [Drive.REAL], and
 * - a non-null [labels] whose size does not equal [results]'s, or that contains a
 *   blank entry.
 *
 * All refusals throw [IllegalArgumentException] from the constructor itself, so a
 * `FindingsTable` instance is proof its results are drive-homogeneous and its labels
 * (when present) are well-formed — there is no post-construction check a caller could
 * forget to run.
 */
class FindingsTable(val results: List<BenchResult>, val labels: List<String>? = null) {

    /** The single [Drive] shared by every result in [results]. */
    val drive: Drive

    init {
        require(results.isNotEmpty()) {
            "FindingsTable requires a non-empty collection of BenchResult"
        }
        val drives = results.map { it.drive }.toSet()
        require(drives.size == 1) {
            "FindingsTable requires all results to share one Drive, found $drives"
        }
        drive = drives.single()
        if (labels != null) {
            require(labels.size == results.size) {
                "FindingsTable requires exactly one label per result (${results.size}), " +
                    "found ${labels.size}"
            }
            require(labels.all { it.isNotBlank() }) {
                "FindingsTable requires every label to be non-blank, found $labels"
            }
        }
    }
}
