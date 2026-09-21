package civictech.demo.alignment

import java.io.Serializable
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
 * A live rating: [value] in 1..9 under [key]. Unrated is absence of the key,
 * never a sentinel value (computenet-sigl0-D5).
 */
data class Rating(val key: RatingKey, val value: Int) : Serializable

/** Ratings on one (idea, dimension): count, mean and SAMPLE standard deviation (0.0 when n < 2). */
data class DimStats(val n: Long, val mean: Double, val stdev: Double) : Serializable

/**
 * An idea's weighted aggregate (computenet-sigl0-D2..D4): [score] is
 * Σ w_d·mean_d / Σ w_d over the dimensions that have ≥1 rating AND a weight;
 * [contributions] holds each such dimension's w_d·mean_d / Σ w_d (they sum to
 * [score]); [byDim] holds those same dimensions' statistics; [split] is true
 * when any of them has n ≥ 2 and stdev ≥ [Alignment.SPLIT_STDEV]. An idea with
 * no rated-and-weighted dimension has no [Scored] at all.
 */
data class Scored(
    val score: Double,
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
     * the write-side ratings and weights. Written independently of the cell
     * path on purpose (computenet-sigl0-D7) — it groups raw ratings and uses
     * exact integer moment sums, where the dataflow folds insert/retract
     * accumulators — so `AlignmentBatchAgreementTest` comparing the two is a
     * check, not a tautology.
     */
    fun rankBatch(ratings: Map<RatingKey, Int>, weights: Map<DimKey, Double>): Map<IdeaKey, Scored> {
        val byIdea = ratings.entries.groupBy { IdeaKey(it.key.topic, it.key.idea) }
        val out = HashMap<IdeaKey, Scored>()
        for ((idea, rows) in byIdea) {
            val stats = sortedMapOf<String, DimStats>()
            for ((dim, dimRows) in rows.groupBy { it.key.dim }) {
                if (weights[DimKey(idea.topic, dim)] == null) continue
                val values = dimRows.map { it.value.toLong() }
                val n = values.size.toLong()
                val sum = values.sum()
                val sumSq = values.sumOf { it * it }
                // n·Σx² − (Σx)² is exact in Long; one rounding at the division
                val variance = if (n < 2) 0.0 else (n * sumSq - sum * sum).toDouble() / (n * (n - 1))
                stats[dim] = DimStats(n, sum.toDouble() / n, sqrt(variance))
            }
            if (stats.isEmpty()) continue
            val totalWeight = stats.keys.sumOf { weights.getValue(DimKey(idea.topic, it)) }
            val contributions = stats.mapValues { (dim, s) -> weights.getValue(DimKey(idea.topic, dim)) * s.mean / totalWeight }
            out[idea] = Scored(
                score = contributions.values.sum(),
                contributions = contributions,
                byDim = stats,
                split = stats.values.any { it.n >= 2 && it.stdev >= SPLIT_STDEV },
            )
        }
        return out
    }
}
