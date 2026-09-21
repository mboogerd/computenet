package civictech.demo.alignment

import java.io.Serializable
import java.math.BigDecimal
import kotlin.math.sqrt

// Domain of the alignment demo (feature computenet-sigl0). Deliberately free of
// any `civictech.cell.*` type: [Alignment.rankBatch] below is the batch oracle
// the seeded agreement test holds the incremental dataflow (AlignmentCells.kt)
// against, and it must not share a code path with it (computenet-sigl0-D7).

/** A topic's id: a [slug] of its title. */
data class TopicId(val value: String) : Serializable {
    override fun toString(): String = value
}

/** One idea within a topic. */
data class IdeaKey(val topic: TopicId, val idea: String) : Serializable

/** One creator-defined dimension within a topic (the key of its weight). */
data class DimKey(val topic: TopicId, val dim: String) : Serializable

/** One idea on one dimension (the key of its rating statistics). */
data class IdeaDimKey(val topic: TopicId, val idea: String, val dim: String) : Serializable {
    val ideaKey: IdeaKey get() = IdeaKey(topic, idea)
    val dimKey: DimKey get() = DimKey(topic, dim)
}

/** One participant's rating slot on one idea and dimension. */
data class RatingKey(val topic: TopicId, val idea: String, val dim: String, val participant: String) : Serializable {
    val ideaDimKey: IdeaDimKey get() = IdeaDimKey(topic, idea, dim)
}

/**
 * The rating scale (epic computenet-9y79n, "DECIDED ... SUPERSEDES the integer
 * 1..9 scale"): a rating is a real number in the closed range [1, 9], held as
 * FIXED-POINT THOUSANDTHS (an `Int` in [MIN_MILLI]..[MAX_MILLI]).
 *
 * Why fixed point: every sum and moment sum stays an exact integer under any
 * insert/retract order, so the incremental stats and [Alignment.rankBatch]
 * compute bit-identical means and variances from the same integers (as v1 did
 * with 1..9) and the agreement test needs no float-order tolerance argument.
 * A thousandth is far below what a continuous, readout-free "feel" slider can
 * express, so the input rounding loses nothing a participant could mean. A
 * future effective value derived from a per-participant history (a weighted
 * average, also real-valued) rounds into the same representation.
 *
 * The API and JSON speak plain numbers: [format] renders the thousandths with
 * trailing zeros stripped, so an integer rating serialises exactly as v1 did
 * (`8`, not `8.0`) and v1 journals and responses stay byte-identical.
 */
object RatingScale {
    const val MIN_MILLI = 1000
    const val MAX_MILLI = 9000

    /** True for a finite [v] with 1 ≤ v ≤ 9 — the API's acceptance rule, checked BEFORE rounding. */
    fun valid(v: Double): Boolean = v.isFinite() && v >= 1.0 && v <= 9.0

    /** [v] (must be [valid]) rounded half-up to thousandths. */
    fun toMilli(v: Double): Int {
        require(valid(v)) { "rating $v is not a finite number in [1, 9]" }
        return Math.round(v * 1000.0).toInt()
    }

    /** Thousandths as a JSON number, trailing zeros stripped: 8000 → `8`, 6370 → `6.37`. */
    fun format(milli: Int): String = BigDecimal.valueOf(milli.toLong(), 3).stripTrailingZeros().toPlainString()
}

/**
 * A live rating: [milli] in [RatingScale.MIN_MILLI]..[RatingScale.MAX_MILLI]
 * (thousandths of the [1, 9] scale) under [key]. Unrated is absence of the
 * key, never a sentinel value (computenet-sigl0-D5).
 */
data class Rating(val key: RatingKey, val milli: Int) : Serializable

/**
 * Ratings on one (idea, dimension): count, mean and SAMPLE standard deviation
 * (0.0 when n < 2), on the [1, 9] scale. Both implementations compute them
 * from exact integer moment sums over thousandths as `sum / (1000·n)` and
 * `sqrt((n·Σx² − (Σx)²) / (n(n−1))) / 1000`; the `n·Σx²` term stays exact in a
 * `Long` up to about 3·10⁵ ratings on one (idea, dimension), far past any demo.
 */
data class DimStats(val n: Long, val mean: Double, val stdev: Double) : Serializable

/** Which way a dimension pulls an idea's score (computenet-k1d4g-D1): VALUE is higher-is-better, COST higher-is-worse. */
enum class Direction { VALUE, COST }

/**
 * A dimension's facilitator configuration (computenet-k1d4g-D1): its weight
 * (finite, > 0) and its [direction]. The value of the weights map end to end —
 * write side, `MapCell`, fusion inlet and [Alignment.rankBatch] — so a
 * direction flip is one put.
 */
data class DimConfig(val weight: Double, val direction: Direction) : Serializable

/**
 * An idea's value ÷ cost aggregate (computenet-k1d4g-D2, D3; computenet-sigl0-D2..D4).
 *
 * Over the idea's rated-and-configured dimensions, split by direction into V
 * (value) and C (cost): [value] is Σ_V w_d·mean_d / Σ_V w_d (null when V is
 * empty) and [cost] the same over C. When the TOPIC has any COST dimension
 * configured (rated or not), [score] is value / cost if both are non-null and
 * null otherwise; when it has none, [score] is [value] — the v1 weighted mean.
 * [contributions] holds each VALUE dimension's w_d·mean_d / Σ_V w_d ÷ (cost ?: 1),
 * so they sum to [score]; it is empty when [score] is null. Cost ratings are
 * 1..9, so cost ≥ 1 and the division is safe.
 *
 * [byDim] holds every rated-and-configured dimension's statistics, whatever
 * its direction; [split] is true when any of them has n ≥ 2 and stdev ≥
 * [Alignment.SPLIT_STDEV]. A null [score] is not a sentinel: it is a real
 * value naming the missing side. An idea with NO rated-and-configured
 * dimension has no [Scored] at all (sigl0-D5).
 */
data class Scored(
    val score: Double?,
    val value: Double?,
    val cost: Double?,
    val contributions: Map<String, Double>,
    val byDim: Map<String, DimStats>,
    val split: Boolean,
) : Serializable

/** Same rule as backlog-triage's `slug`: lowercase, runs of `[^a-z0-9]` → `-`, trimmed, ≤ 80 chars. */
fun slug(s: String): String =
    s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(80)

object Alignment {
    /** Split threshold on the 1–9 scale: {3, 7} (stdev 2.83) splits, {5, 7} (1.41) does not. */
    const val SPLIT_STDEV = 2.0

    /**
     * The batch reference: every idea's [Scored] recomputed from scratch from
     * the write-side ratings and dimension configs. Written independently of
     * the cell path on purpose (computenet-sigl0-D7) — it groups raw ratings
     * and uses exact integer moment sums, where the dataflow folds
     * insert/retract accumulators, and it derives a topic's has-cost bit by
     * scanning [dims] where the cell keeps an index — so
     * `AlignmentBatchAgreementTest` comparing the two is a check, not a
     * tautology.
     */
    /** [ratings] values are thousandths ([Rating.milli]). */
    fun rankBatch(ratings: Map<RatingKey, Int>, dims: Map<DimKey, DimConfig>): Map<IdeaKey, Scored> {
        val costTopics = dims.filterValues { it.direction == Direction.COST }.keys.map { it.topic }.toSet()
        val byIdea = ratings.entries.groupBy { IdeaKey(it.key.topic, it.key.idea) }
        val out = HashMap<IdeaKey, Scored>()
        for ((idea, rows) in byIdea) {
            val stats = sortedMapOf<String, DimStats>()
            for ((dim, dimRows) in rows.groupBy { it.key.dim }) {
                if (dims[DimKey(idea.topic, dim)] == null) continue
                val values = dimRows.map { it.value.toLong() }
                val n = values.size.toLong()
                val sum = values.sum()
                val sumSq = values.sumOf { it * it }
                // n·Σx² − (Σx)² is exact in Long (thousandths²); one rounding at the division
                val variance = if (n < 2) 0.0 else (n * sumSq - sum * sum).toDouble() / (n * (n - 1))
                stats[dim] = DimStats(n, sum.toDouble() / (1000 * n), sqrt(variance) / 1000.0)
            }
            if (stats.isEmpty()) continue
            fun config(dim: String) = dims.getValue(DimKey(idea.topic, dim))
            fun weightedMean(dir: Direction): Double? {
                val side = stats.filterKeys { config(it).direction == dir }
                if (side.isEmpty()) return null
                return side.entries.sumOf { (d, s) -> config(d).weight * s.mean } / side.keys.sumOf { config(it).weight }
            }
            val value = weightedMean(Direction.VALUE)
            val cost = weightedMean(Direction.COST)
            val score = when {
                idea.topic !in costTopics -> value
                value != null && cost != null -> value / cost
                else -> null
            }
            val contributions = if (score == null) emptyMap() else {
                val valueDims = stats.filterKeys { config(it).direction == Direction.VALUE }
                val totalValueWeight = valueDims.keys.sumOf { config(it).weight }
                valueDims.mapValues { (d, s) -> config(d).weight * s.mean / totalValueWeight / (cost ?: 1.0) }
            }
            out[idea] = Scored(
                score = score,
                value = value,
                cost = cost,
                contributions = contributions,
                byDim = stats,
                split = stats.values.any { it.n >= 2 && it.stdev >= SPLIT_STDEV },
            )
        }
        return out
    }
}
