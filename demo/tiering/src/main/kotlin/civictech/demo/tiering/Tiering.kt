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

    /**
     * The [Tiered] a **manually pinned** tier label displays as (feature
     * computenet-j2x.5, task .1).
     *
     * A manual re-tier names a tier, not a score, but the board renders
     * `{item, score}` chips and sorts within a row by score — so the label has
     * to carry one. The canonical choice is the label's own absolute
     * valuation, normalized the same way [fuse] normalizes `tierAvg`:
     * `SCORE_OF[label] / 6.0`. That is the score a unanimous board of agents
     * valuing the item at exactly this tier (and no preferences) would have
     * fused to, so a pin reads as "everyone said this tier" rather than as an
     * out-of-band number.
     *
     * It is also a fixed point of [tierOf]: `tierOf(manualTiered(t).score) == t`
     * for every `t` in [TIERS] — the displayed score can never contradict the
     * row the chip sits in. This is presentation logic, deliberately here and
     * not in the kernel: convergence of the manual map is the OR-map's job.
     */
    fun manualTiered(tier: String): Tiered {
        val score = SCORE_OF.getValue(tier) / 6.0
        return Tiered(score, tier)
    }
}
