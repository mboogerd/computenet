package civictech.demo.backlogtriage

import civictech.cell.data.Aggregator
import java.io.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Incremental aggregate ranking engines over a stream of pairwise
 * preferences. Each engine folds `add`/`retract` deltas as they happen —
 * no engine ever replays the full preference set from scratch.
 *
 * Contract: `retract` may only be called for a previously added pair.
 * [ratings] exposes items with at least one live comparison; higher is
 * better. Exactness under retraction: [MeanOfSigns] and [BradleyTerry] are
 * exact (their state is invertible counts); [Elo] is approximate — see its
 * doc.
 */
interface RatingEngine {
    fun add(winner: String, loser: String)
    fun retract(winner: String, loser: String)
    fun ratings(): Map<String, Double>
}

/**
 * Mean of ±1 signs: score = (wins − losses) / comparisons, in [-1, 1].
 * The pure reference implementation of what the cell pipeline
 * (flatMap ±1 → GroupBy avg) computes; coverage-biased and blind to
 * opponent strength, but exact, order-independent, and O(1) per delta.
 */
class MeanOfSigns : RatingEngine {
    private val sum = mutableMapOf<String, Long>()
    private val n = mutableMapOf<String, Long>()

    private fun bump(item: String, ds: Long, dn: Long) {
        sum.merge(item, ds, Long::plus)
        if (n.merge(item, dn, Long::plus) == 0L) { n.remove(item); sum.remove(item) }
    }

    override fun add(winner: String, loser: String) { bump(winner, +1, +1); bump(loser, -1, +1) }

    override fun retract(winner: String, loser: String) { bump(winner, -1, -1); bump(loser, +1, -1) }

    override fun ratings(): Map<String, Double> =
        n.mapValues { (item, count) -> sum.getValue(item).toDouble() / count }
}

/**
 * Bradley–Terry: fits a latent strength per item with
 * P(a beats b) = s_a / (s_a + s_b), via the classic MM update. Incremental
 * in two ways: the sufficient statistics (per-ordered-pair win counts) are
 * maintained exactly under add/retract, and each [ratings] call warm-starts
 * from the previous fit, so one delta typically converges in a few sweeps.
 *
 * Regularization: every item also plays `2·prior` phantom games (prior wins
 * + prior losses) against a fixed strength-1.0 opponent — keeps undefeated
 * items finite and disconnected components comparable. Strengths are
 * normalized to geometric mean 1.0.
 */
class BradleyTerry(
    private val prior: Double = 0.5,
    private val tol: Double = 1e-9,
    private val maxIters: Int = 1000,
) : RatingEngine {
    private val wins = mutableMapOf<Pair<String, String>, Int>()   // (winner, loser) → count
    private val games = mutableMapOf<String, Int>()
    private val strength = mutableMapOf<String, Double>()          // warm start across calls

    private fun bump(item: String, d: Int) {
        if (games.merge(item, d, Int::plus) == 0) games.remove(item)
    }

    override fun add(winner: String, loser: String) {
        wins.merge(winner to loser, 1, Int::plus)
        bump(winner, +1); bump(loser, +1)
    }

    override fun retract(winner: String, loser: String) {
        val key = winner to loser
        val c = wins[key] ?: return
        if (c == 1) wins.remove(key) else wins[key] = c - 1
        bump(winner, -1); bump(loser, -1)
    }

    override fun ratings(): Map<String, Double> {
        val items = games.keys.toList()
        if (items.isEmpty()) return emptyMap()

        val winsOf = mutableMapOf<String, Int>()
        val opponents = mutableMapOf<String, MutableMap<String, Int>>()  // item → other → games
        wins.forEach { (pair, c) ->
            val (w, l) = pair
            winsOf.merge(w, c, Int::plus)
            opponents.getOrPut(w) { mutableMapOf() }.merge(l, c, Int::plus)
            opponents.getOrPut(l) { mutableMapOf() }.merge(w, c, Int::plus)
        }

        val s = items.associateWithTo(mutableMapOf()) { strength[it] ?: 1.0 }
        for (iter in 0 until maxIters) {
            var maxRel = 0.0
            for (i in items) {
                val si = s.getValue(i)
                val num = (winsOf[i] ?: 0) + prior
                var den = 2 * prior / (si + 1.0)
                opponents[i]?.forEach { (j, nij) -> den += nij / (si + s.getValue(j)) }
                val next = num / den
                maxRel = maxOf(maxRel, abs(next - si) / si)
                s[i] = next
            }
            if (maxRel < tol) break
        }

        val norm = exp(s.values.sumOf { ln(it) } / s.size)
        s.replaceAll { _, v -> v / norm }
        strength.clear(); strength.putAll(s)
        return s
    }
}

/**
 * Elo: the classic online rating — each preference is a "game" applied in
 * arrival order; the winner takes `k · (1 − expected)` points off the loser.
 * Order-dependent by design (the journal makes arrival order stable across
 * restarts, so recovery is deterministic). Retraction is the standard
 * approximate inverse — the game is played backwards at *current* ratings —
 * leaving a residual bounded by k per retraction; an item whose live game
 * count reaches zero is dropped from [ratings], so a fully-retracted item
 * leaves no visible artifact.
 */
class Elo(
    private val k: Double = 32.0,
    private val base: Double = 1000.0,
) : RatingEngine {
    private val rating = mutableMapOf<String, Double>()
    private val games = mutableMapOf<String, Int>()

    private fun expected(a: Double, b: Double) = 1.0 / (1.0 + 10.0.pow((b - a) / 400.0))

    override fun add(winner: String, loser: String) {
        val rw = rating.getOrDefault(winner, base)
        val rl = rating.getOrDefault(loser, base)
        val d = k * (1.0 - expected(rw, rl))
        rating[winner] = rw + d
        rating[loser] = rl - d
        games.merge(winner, 1, Int::plus); games.merge(loser, 1, Int::plus)
    }

    override fun retract(winner: String, loser: String) {
        if ((games[winner] ?: 0) == 0 || (games[loser] ?: 0) == 0) return
        val rw = rating.getValue(winner)
        val rl = rating.getValue(loser)
        val d = k * (1.0 - expected(rw, rl))
        rating[winner] = rw - d
        rating[loser] = rl + d
        games.merge(winner, -1, Int::plus); games.merge(loser, -1, Int::plus)
    }

    override fun ratings(): Map<String, Double> =
        rating.filterKeys { (games[it] ?: 0) > 0 }
}

/**
 * TrueSkill (two-player, no-draw form): the Bayesian Elo-derivative. Each
 * item holds a Gaussian skill belief N(μ, σ²); a game moves both beliefs by
 * the truncated-Gaussian moment updates (v/w functions), so upsets move more
 * and every game *shrinks* uncertainty. The exposed rating is the
 * conservative estimate μ − 3σ — a well-evidenced item outranks an
 * equal-record newcomer because its σ is smaller.
 *
 * Skill-dynamics noise (τ) is omitted: backlog value judgements don't drift
 * the way player skill does, and τ=0 keeps retraction near-symmetric.
 * Retraction is the approximate inverse at current beliefs (same contract as
 * [Elo]): bounded residual, and a fully-retracted item drops out of
 * [ratings].
 */
class TrueSkill(
    private val mu0: Double = 25.0,
    private val sigma0: Double = 25.0 / 3,
    private val beta: Double = 25.0 / 6,
) : RatingEngine {
    private class Belief(var mu: Double, var variance: Double)

    private val beliefs = mutableMapOf<String, Belief>()
    private val games = mutableMapOf<String, Int>()

    private fun belief(item: String) = beliefs.getOrPut(item) { Belief(mu0, sigma0 * sigma0) }

    override fun add(winner: String, loser: String) {
        val w = belief(winner)
        val l = belief(loser)
        val c2 = 2 * beta * beta + w.variance + l.variance
        val c = sqrt(c2)
        val v = vExceeds((w.mu - l.mu) / c)
        val u = v * (v + (w.mu - l.mu) / c)
        w.mu += w.variance / c * v
        l.mu -= l.variance / c * v
        w.variance *= (1 - w.variance / c2 * u).coerceAtLeast(1e-6)
        l.variance *= (1 - l.variance / c2 * u).coerceAtLeast(1e-6)
        games.merge(winner, 1, Int::plus); games.merge(loser, 1, Int::plus)
    }

    override fun retract(winner: String, loser: String) {
        if ((games[winner] ?: 0) == 0 || (games[loser] ?: 0) == 0) return
        val w = belief(winner)
        val l = belief(loser)
        val c2 = 2 * beta * beta + w.variance + l.variance
        val c = sqrt(c2)
        val v = vExceeds((w.mu - l.mu) / c)
        val u = v * (v + (w.mu - l.mu) / c)
        // approximate inverse at current beliefs: re-inflate σ, back out μ
        w.variance = (w.variance / (1 - w.variance / c2 * u)).coerceAtMost(sigma0 * sigma0)
        l.variance = (l.variance / (1 - l.variance / c2 * u)).coerceAtMost(sigma0 * sigma0)
        w.mu -= w.variance / c * v
        l.mu += l.variance / c * v
        games.merge(winner, -1, Int::plus); games.merge(loser, -1, Int::plus)
    }

    override fun ratings(): Map<String, Double> =
        beliefs.filterKeys { (games[it] ?: 0) > 0 }
            .mapValues { (_, b) -> b.mu - 3 * sqrt(b.variance) }

    /** φ(t)/Φ(t) — mean shift of a Gaussian truncated to "winner exceeds". */
    private fun vExceeds(t: Double): Double {
        val denom = cdf(t)
        return if (denom < 1e-10) -t else pdf(t) / denom
    }

    private fun pdf(x: Double) = exp(-x * x / 2) / sqrt(2 * PI)

    private fun cdf(x: Double) = 0.5 * (1 + erf(x / sqrt(2.0)))

    private fun erf(x: Double): Double {   // Abramowitz–Stegun 7.1.26, |ε| < 1.5e-7
        val sign = if (x < 0) -1.0 else 1.0
        val a = abs(x)
        val t = 1.0 / (1.0 + 0.3275911 * a)
        val poly = ((((1.061405429 * t - 1.453152027) * t + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t
        return sign * (1 - poly * exp(-a * a))
    }
}

/**
 * Glicko (one-game rating periods): Elo plus a per-item rating deviation
 * that shrinks with evidence — the logistic-model sibling of [TrueSkill].
 * Pairwise-local like every Elo derivative: a game moves only the two
 * participants. Displayed rating is the conservative r − 2·RD. Rating-
 * deviation decay over idle time is omitted (preferences don't age), and
 * retraction is the usual approximate inverse at current state.
 */
class Glicko(
    private val base: Double = 1500.0,
    private val rd0: Double = 350.0,
) : RatingEngine {
    private class B(var r: Double, var rd: Double)

    private val items = mutableMapOf<String, B>()
    private val games = mutableMapOf<String, Int>()
    private val q = ln(10.0) / 400.0

    private fun g(rd: Double) = 1.0 / sqrt(1.0 + 3.0 * q * q * rd * rd / (PI * PI))

    private fun expect(r: Double, opp: B) = 1.0 / (1.0 + 10.0.pow(-g(opp.rd) * (r - opp.r) / 400.0))

    /** 1/d² — the information one game against `opp` carries about `me`. */
    private fun info(me: B, opp: B): Double {
        val e = expect(me.r, opp)
        return q * q * g(opp.rd).pow(2) * e * (1 - e)
    }

    private fun step(me: B, opp: B, s: Double) {
        val e = expect(me.r, opp)
        val denom = 1.0 / (me.rd * me.rd) + info(me, opp)
        me.r += q / denom * g(opp.rd) * (s - e)
        me.rd = sqrt(1.0 / denom)
    }

    override fun add(winner: String, loser: String) {
        val w = items.getOrPut(winner) { B(base, rd0) }
        val l = items.getOrPut(loser) { B(base, rd0) }
        val wPre = B(w.r, w.rd)
        val lPre = B(l.r, l.rd)
        step(w, lPre, 1.0)
        step(l, wPre, 0.0)
        games.merge(winner, 1, Int::plus); games.merge(loser, 1, Int::plus)
    }

    override fun retract(winner: String, loser: String) {
        val w = items[winner] ?: return
        val l = items[loser] ?: return
        if ((games[winner] ?: 0) == 0 || (games[loser] ?: 0) == 0) return
        // approximate inverse at current state: back out the shift, re-widen RD
        fun unstep(me: B, opp: B, s: Double) {
            val e = expect(me.r, opp)
            val denom = 1.0 / (me.rd * me.rd) + info(me, opp)
            me.r -= q / denom * g(opp.rd) * (s - e)
            val widened = 1.0 / (me.rd * me.rd) - info(me, opp)
            me.rd = if (widened > 1.0 / (rd0 * rd0)) sqrt(1.0 / widened) else rd0
        }
        val wPre = B(w.r, w.rd)
        val lPre = B(l.r, l.rd)
        unstep(w, lPre, 1.0)
        unstep(l, wPre, 0.0)
        games.merge(winner, -1, Int::plus); games.merge(loser, -1, Int::plus)
    }

    override fun ratings(): Map<String, Double> =
        items.filterKeys { (games[it] ?: 0) > 0 }.mapValues { (_, b) -> b.r - 2 * b.rd }
}

/**
 * Weng–Lin online Bradley–Terry (2011; the OpenSkill "full pairing" rule):
 * the SAME latent-strength model [BradleyTerry] fits by global refit,
 * estimated instead as a Bayesian online update that is *pairwise-local* —
 * the truly incremental Bradley–Terry. Each item holds N(μ, σ²) on the
 * log-strength scale; the win probability is logistic in (μ_w − μ_l)/c.
 * Displayed rating is the ordinal μ − 3σ (as OpenSkill). γ = 1
 * simplification on the variance update; retraction is the approximate
 * inverse at current beliefs.
 */
class WengLin(
    private val mu0: Double = 25.0,
    private val sigma0: Double = 25.0 / 3,
    private val beta: Double = 25.0 / 6,
    private val kappa: Double = 1e-4,
) : RatingEngine {
    private class B(var mu: Double, var variance: Double)

    private val items = mutableMapOf<String, B>()
    private val games = mutableMapOf<String, Int>()

    override fun add(winner: String, loser: String) {
        val w = items.getOrPut(winner) { B(mu0, sigma0 * sigma0) }
        val l = items.getOrPut(loser) { B(mu0, sigma0 * sigma0) }
        val c = sqrt(w.variance + l.variance + 2 * beta * beta)
        val pw = exp(w.mu / c) / (exp(w.mu / c) + exp(l.mu / c))
        val pl = 1 - pw
        w.mu += w.variance / c * (1 - pw)
        l.mu -= l.variance / c * pl
        w.variance *= (1 - w.variance / (c * c) * pw * pl).coerceAtLeast(kappa)
        l.variance *= (1 - l.variance / (c * c) * pw * pl).coerceAtLeast(kappa)
        games.merge(winner, 1, Int::plus); games.merge(loser, 1, Int::plus)
    }

    override fun retract(winner: String, loser: String) {
        val w = items[winner] ?: return
        val l = items[loser] ?: return
        if ((games[winner] ?: 0) == 0 || (games[loser] ?: 0) == 0) return
        val c = sqrt(w.variance + l.variance + 2 * beta * beta)
        val pw = exp(w.mu / c) / (exp(w.mu / c) + exp(l.mu / c))
        val pl = 1 - pw
        w.variance = (w.variance / (1 - w.variance / (c * c) * pw * pl).coerceAtLeast(kappa))
            .coerceAtMost(sigma0 * sigma0)
        l.variance = (l.variance / (1 - l.variance / (c * c) * pw * pl).coerceAtLeast(kappa))
            .coerceAtMost(sigma0 * sigma0)
        w.mu -= w.variance / c * (1 - pw)
        l.mu += l.variance / c * pl
        games.merge(winner, -1, Int::plus); games.merge(loser, -1, Int::plus)
    }

    override fun ratings(): Map<String, Double> =
        items.filterKeys { (games[it] ?: 0) > 0 }.mapValues { (_, b) -> b.mu - 3 * sqrt(b.variance) }
}

/**
 * Wilson lower-bound win rate (default z = 1.96, the 95% bound): an
 * evidence-aware score with NO opponent model — a 1-0 record scores lower
 * than 3-0 because the confidence interval is wider. Per-key *independent*,
 * which puts it in the class the kernel can already express: this is a
 * plain [Aggregator] for `GroupByCell` over the same ±1 contribution stream
 * the mean pipeline uses — no custom cell involved.
 */
class WilsonAggregator(private val z: Double = 1.96) : Aggregator<Contribution, Double, WilsonAggregator.Acc> {
    data class Acc(val wins: Long, val n: Long) : Serializable

    override fun empty() = Acc(0, 0)

    override fun insert(acc: Acc, element: Contribution) =
        Acc(acc.wins + if (element.sign > 0) 1 else 0, acc.n + 1)

    override fun retract(acc: Acc, element: Contribution) =
        Acc(acc.wins - if (element.sign > 0) 1 else 0, acc.n - 1)

    override fun value(acc: Acc): Double {
        if (acc.n == 0L) return 0.0
        val n = acc.n.toDouble()
        val p = acc.wins / n
        val z2 = z * z
        return (p + z2 / (2 * n) - z * sqrt(p * (1 - p) / n + z2 / (4 * n * n))) / (1 + z2 / n)
    }
}

/**
 * Meta aggregation over the other engines, by Borda count: combines their
 * *rankings* rather than their incomparable score scales. Owns private
 * delegate instances and forwards every add/retract, so it is self-contained
 * and exactly as incremental as its delegates; the combination itself is a
 * read-time sort of each delegate's ratings.
 *
 * Score = mean normalized Borda points across delegates, in [0, 1]:
 * 1 = unanimous first place, 0 = unanimous last. Score ties within a
 * delegate share fractional (average) ranks, so an algorithm with many ties
 * (e.g. mean-of-signs) doesn't arbitrarily break them.
 */
class MetaRank(private val delegates: List<RatingEngine>) : RatingEngine {

    override fun add(winner: String, loser: String) = delegates.forEach { it.add(winner, loser) }

    override fun retract(winner: String, loser: String) = delegates.forEach { it.retract(winner, loser) }

    override fun ratings(): Map<String, Double> = Borda.combine(delegates.map { it.ratings() })
}

/** The Borda combination shared by [MetaRank] and the dataflow `MetaRankCell`. */
object Borda {

    fun combine(perSource: List<Map<String, Double>>): Map<String, Double> {
        val items = perSource.flatMap { it.keys }.toSet()
        if (items.isEmpty()) return emptyMap()
        val n = items.size
        val points = mutableMapOf<String, Double>()
        for (ratings in perSource) {
            fractionalRanks(ratings, items).forEach { (item, r) ->
                points.merge(item, (n - r) / (n - 1.0).coerceAtLeast(1.0), Double::plus)
            }
        }
        return points.mapValues { it.value / perSource.size }
    }

    /** 1-based ranks by score descending; equal scores share the average rank. */
    private fun fractionalRanks(ratings: Map<String, Double>, items: Set<String>): Map<String, Double> {
        fun scoreOf(item: String) = ratings[item] ?: Double.NEGATIVE_INFINITY
        val sorted = items.sortedWith(compareByDescending<String> { scoreOf(it) }.thenBy { it })
        val ranks = mutableMapOf<String, Double>()
        var i = 0
        while (i < sorted.size) {
            var j = i
            while (j < sorted.size && scoreOf(sorted[j]) == scoreOf(sorted[i])) j++
            val avg = (i + 1 + j) / 2.0   // mean of 1-based positions i+1..j
            for (k in i until j) ranks[sorted[k]] = avg
            i = j
        }
        return ranks
    }
}
