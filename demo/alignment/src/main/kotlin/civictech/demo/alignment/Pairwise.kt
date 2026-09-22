package civictech.demo.alignment

import java.util.SortedMap
import java.util.TreeMap
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/** Which side of a [Judgement] was higher on the dimension, or neither. */
enum class Outcome { A, B, EQUAL }

/** One participant's pairwise judgement: [a] vs [b] on one dimension, with [outcome] as seen from [a]. */
data class Judgement(val a: String, val b: String, val outcome: Outcome)

/**
 * Converts one participant's pairwise judgements on one dimension into that participant's ratings
 * on the [RatingScale] (feature computenet-k6rrk, ALN2.7, design k6rrk-D1; research R15 — people
 * judge relative better than absolute).
 *
 * Model: P(i beats j) = s_i / (s_i + s_j), a Bradley–Terry latent strength per idea. An [Outcome.A]
 * or [Outcome.B] judgement counts as one full win for the higher side; an [Outcome.EQUAL] judgement
 * counts as half a win for each side (so it still contributes one game to both sides' opponent
 * counts, just with a 50/50 split of the win). The MLE is found by the classic minorize-maximize
 * (MM) update (copied from `demo/backlog-triage`'s `BradleyTerry`, not imported — `demo/alignment`
 * does not depend on `:demo:backlog-triage`):
 *
 *     next_i = (wins_i + prior) / (2·prior / (s_i + 1) + Σ_j n_ij / (s_i + s_j))
 *
 * where `prior` = 0.5 phantom games against a fixed strength-1.0 opponent keep an undefeated idea's
 * strength finite. [ratings] fits FROM SCRATCH on every call: every idea starts at strength 1.0, the
 * sweep visits ideas in sorted-id order, and it stops when the largest relative change across a
 * sweep drops below `1e-9` or after 1000 sweeps — never a warm start, so the result depends only on
 * the judgement SET given, never on call history or argument order. After fitting, strengths are
 * rescaled to a geometric mean of 1.0 (so the fit has no absolute reference), then mapped onto
 * `[1, 9]` by `v = 1 + 8 · s / (1 + s)` (clamped to `[1, 9]` against rounding) and rounded to
 * thousandths with [RatingScale.toMilli].
 *
 * Properties that follow from the model + prior being invariant under `s → 1/s` with outcomes
 * flipped, and from the geometric-mean normalization: a single A-beats-B judgement splits their
 * combined rating exactly `10.000` (in milli) around a v_a > 5; the middle idea of a 3-chain sits at
 * exactly `5.000`; an all-EQUAL judgement set (or a judgement contradicted by its reverse) leaves
 * every idea at exactly `5.000`.
 *
 * Only ideas that appear in at least one judgement are present in the result. An empty [judgements]
 * collection returns an empty map.
 */
object PairwiseFit {
    private const val PRIOR = 0.5
    private const val TOL = 1e-9
    private const val MAX_SWEEPS = 1000

    fun ratings(judgements: Collection<Judgement>): SortedMap<String, Int> {
        val result: SortedMap<String, Int> = TreeMap()
        if (judgements.isEmpty()) return result

        // wins[(winner, loser)] = weight of wins winner earned over loser (1.0 for a decisive
        // judgement, 0.5 for each side of an EQUAL judgement); summing both directions of a pair
        // gives the total game count between them, exactly as in Ranking.kt's BradleyTerry.
        val wins = mutableMapOf<Pair<String, String>, Double>()
        val items = sortedSetOf<String>()

        fun win(winner: String, loser: String, weight: Double) {
            wins.merge(winner to loser, weight, Double::plus)
        }

        for (j in judgements) {
            items += j.a
            items += j.b
            when (j.outcome) {
                Outcome.A -> win(j.a, j.b, 1.0)
                Outcome.B -> win(j.b, j.a, 1.0)
                Outcome.EQUAL -> {
                    win(j.a, j.b, 0.5)
                    win(j.b, j.a, 0.5)
                }
            }
        }

        val winsOf = mutableMapOf<String, Double>()
        val opponents = mutableMapOf<String, MutableMap<String, Double>>()
        wins.forEach { (pair, weight) ->
            val (winner, loser) = pair
            winsOf.merge(winner, weight, Double::plus)
            opponents.getOrPut(winner) { mutableMapOf() }.merge(loser, weight, Double::plus)
            opponents.getOrPut(loser) { mutableMapOf() }.merge(winner, weight, Double::plus)
        }

        val order = items.toList()
        val s = order.associateWithTo(mutableMapOf()) { 1.0 }
        for (sweep in 0 until MAX_SWEEPS) {
            var maxRel = 0.0
            for (i in order) {
                val si = s.getValue(i)
                val num = (winsOf[i] ?: 0.0) + PRIOR
                var den = 2 * PRIOR / (si + 1.0)
                opponents[i]?.forEach { (j, nij) -> den += nij / (si + s.getValue(j)) }
                val next = num / den
                maxRel = maxOf(maxRel, abs(next - si) / si)
                s[i] = next
            }
            if (maxRel < TOL) break
        }

        val norm = exp(s.values.sumOf { ln(it) } / s.size)
        for (item in order) {
            val strength = s.getValue(item) / norm
            val v = (1.0 + 8.0 * strength / (1.0 + strength)).coerceIn(1.0, 9.0)
            result[item] = RatingScale.toMilli(v)
        }
        return result
    }
}
