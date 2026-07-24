package civictech.demo.tiering

import java.io.Serializable

/** A fused score and its fixed-threshold tier. */
data class Tiered(val score: Double, val tier: String) : Serializable

/** Score fusion + fixed-threshold bucketing (the user-decided semantics). */
object Tiering {
    /** Tiers best-first; absolute valuations map S=6 .. F=0. */
    val TIERS = listOf("S", "A", "B", "C", "D", "E", "F")
    val SCORE_OF: Map<String, Long> = TIERS.withIndex().associate { (i, t) -> t to (6 - i).toLong() }
    val TIER_OF_SCORE: Map<Long, String> = SCORE_OF.entries.associate { (t, s) -> s to t }

    const val TIER_WEIGHT = 0.7
    const val PREF_WEIGHT = 0.3

    /** Fixed cutoffs on the fused score in [0,1]. */
    fun tierOf(score: Double): String = when {
        score >= 0.85 -> "S"
        score >= 0.70 -> "A"
        score >= 0.55 -> "B"
        score >= 0.40 -> "C"
        score >= 0.25 -> "D"
        score >= 0.10 -> "E"
        else -> "F"
    }

    /**
     * tierAvg ∈ [0,6] (mean absolute score), prefAvg ∈ [-1,1] (mean pairwise
     * contribution). Both normalized to [0,1] and blended 0.7/0.3; an item
     * with only one signal uses that signal alone; no signal at all → null.
     */
    fun fuse(tierAvg: Double?, prefAvg: Double?): Tiered? {
        val tierNorm = tierAvg?.let { it / 6.0 }
        val prefNorm = prefAvg?.let { (it + 1.0) / 2.0 }
        val score = when {
            tierNorm != null && prefNorm != null -> TIER_WEIGHT * tierNorm + PREF_WEIGHT * prefNorm
            tierNorm != null -> tierNorm
            prefNorm != null -> prefNorm
            else -> return null
        }
        return Tiered(score, tierOf(score))
    }
}
