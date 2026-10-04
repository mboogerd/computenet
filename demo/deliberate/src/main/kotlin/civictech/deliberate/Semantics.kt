package civictech.deliberate

import civictech.agora.semantics.DfQuad
import civictech.agora.semantics.GradualSemantics
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.math.pow

/**
 * One argument as a semantics layer sees it (SPEC CRED-04): the [strength] of
 * its edge — the edge's own credence in that layer, i.e. the Jev strength
 * stance lowered by any undercutter — and the [credence] of its source claim
 * in that layer. They arrive separately (an [Influence] carries both vectors),
 * so a semantics that treats them differently, like [JeffreyNaiveBayes], is
 * exact rather than reading their product.
 */
data class Arg(val strength: Double, val credence: Double)

/**
 * A credence layer's rule (SPEC CRED-03/04), the prototype `semantics.js`
 * shape: [energy] turns one (strength, credence) pair into an energy and
 * [combine] folds a claim's base with the energies of its attacks and
 * supports. The default energy is DF-QuAD's product `s·c`; the default base
 * is agora's, the clamped mean of the stances.
 */
interface Semantics : java.io.Serializable {
    fun base(stances: Collection<Double>): Double = DfQuad.base(stances)

    fun energy(arg: Arg): Double = arg.strength.coerceIn(0.0, 1.0) * arg.credence.coerceIn(0.0, 1.0)

    fun combine(base: Double, attacks: List<Double>, supports: List<Double>): Double

    /** The credence of a claim with [base] and these arguments. */
    fun evaluate(base: Double, attacks: List<Arg>, supports: List<Arg>): Double =
        combine(base, attacks.map(::energy), supports.map(::energy))
}

/** An agora [GradualSemantics] as a layer: product energies, agora's own base. */
class EnergySemantics(@Transient private val g: GradualSemantics) : Semantics {
    override fun base(stances: Collection<Double>) = g.base(stances)
    override fun combine(base: Double, attacks: List<Double>, supports: List<Double>) = g.combine(base, attacks, supports)
    override fun toString() = g.toString()

    /** The only energy semantics in the catalog is DF-QuAD; do not serialize its non-serializable strategy object. */
    private fun readResolve(): Any = EnergySemantics(DfQuad)
}

/**
 * The gradual semantics a deliberation can be propagated under (SPEC CRED-03,
 * §2 "Credence layers and consensus"), ported from the deliberate semantics
 * prototype (`semantics.js`); every candidate keeps agora's base, the clamped
 * mean of the stances (the prototype's `shrunkBase` at its default root-prior
 * weight 1).
 */
object SemanticsCatalog {
    /** Every semantics the app can run, in display order. `dfquad` always runs. */
    val IDS = listOf("dfquad", "wlo", "jnb", "woe", "euler", "qe", "mlp", "glo")
    const val DEFAULT_PRIMARY = "dfquad"

    fun of(id: String, wlo: WeightedLogOdds = WeightedLogOdds()): Semantics = when (id) {
        "dfquad" -> EnergySemantics(DfQuad)
        "wlo" -> wlo
        "jnb" -> JeffreyNaiveBayes()
        "woe" -> WeightOfEvidence()
        "euler" -> EulerBased
        "qe" -> QuadraticEnergy
        "mlp" -> MlpBased
        "glo" -> GatedLogOdds()
        else -> throw IllegalArgumentException("unknown semantics '$id' (${IDS.joinToString()})")
    }
}

private const val FLOOR = DfQuad.BASE_FLOOR

private fun clampBase(b: Double) = b.coerceIn(FLOOR, 1 - FLOOR)
private fun logit(p: Double) = ln(p / (1 - p))
private fun sigmoid(z: Double) = 1 / (1 + exp(-z))

/** p-norm aggregation: p = 1 additive, p ≥ 50 read as max. Diminishing, never saturating. */
private fun pnorm(xs: List<Double>, p: Double): Double = when {
    xs.isEmpty() -> 0.0
    p >= 50 -> xs.max()
    else -> xs.sumOf { it.pow(p) }.pow(1 / p)
}

/** Σ supports − Σ attacks: the net energy of the additive semantics. */
private fun net(attacks: List<Double>, supports: List<Double>) = supports.sum() - attacks.sum()

/**
 * Weighted log-odds sum:
 * `sigmoid(α·logit(base) + k·(‖S^γ‖_p − ‖A^γ‖_p))` over the support and
 * attack energies S and A (each clamped to [0,1] before `^γ`).
 * Defaults α = 1, k = 2.4, p = 2, γ = 1.3. The output lies strictly inside
 * (0,1). Agora's cycle-damping argument assumes DF-QuAD's loop gain; a
 * deliberation tree has no cycles, so it is not needed here.
 */
class WeightedLogOdds(
    val alpha: Double = 1.0,
    val k: Double = 2.4,
    val p: Double = 2.0,
    val gamma: Double = 1.3,
) : Semantics {
    init {
        require(p >= 1) { "wlo p must be >= 1: $p" }
        require(k >= 0 && alpha >= 0 && gamma > 0) { "wlo needs alpha >= 0, k >= 0, gamma > 0" }
    }

    override fun combine(base: Double, attacks: List<Double>, supports: List<Double>): Double {
        fun g(e: Double) = e.coerceIn(0.0, 1.0).pow(gamma)
        return sigmoid(alpha * logit(clampBase(base)) + k * (pnorm(supports.map(::g), p) - pnorm(attacks.map(::g), p)))
    }

    override fun toString() = "wlo(alpha=$alpha, k=$k, p=$p, gamma=$gamma)"
}

/**
 * Jeffrey / naive-Bayes likelihood ratios, exactly as the prototype's
 * `energy_jnb` / `combine_jnb`: a true argument of strength s is evidence with
 * likelihood ratio `LR = ((1+s)/(1−s))^K` (s clamped to [0, `smax`]); Jeffrey
 * conditioning on the source's credence c gives the odds multiplier
 * `m = c·LR + (1−c)·LR^(−r)`, and the argument's energy is the log-weight
 * `ln m` (signed when r > 0: a doubted argument then counts for the other
 * side). Energies aggregate per effective side by p-norm into
 * `sigmoid(α·logit(base) + ‖W_S‖_p − ‖W_A‖_p)`.
 * Defaults α = 1, K = 0.7, p = 2, r = 0, smax = 0.8.
 *
 * Strength and credence enter separately — the one semantics for which that
 * matters, and the reason an [Influence] carries both vectors rather than
 * their product.
 */
class JeffreyNaiveBayes(
    val alpha: Double = 1.0,
    val bigK: Double = 0.7,
    val p: Double = 2.0,
    val r: Double = 0.0,
    val smax: Double = 0.8,
) : Semantics {
    override fun energy(arg: Arg): Double {
        val s = arg.strength.coerceIn(0.0, smax)
        val lr = ((1 + s) / (1 - s)).pow(bigK)
        val c = arg.credence.coerceIn(0.0, 1.0)
        return ln(c * lr + (1 - c) * lr.pow(-r))
    }

    override fun combine(base: Double, attacks: List<Double>, supports: List<Double>): Double {
        // effectiveSides: a negative energy argues for the other side (only reachable when r > 0).
        val pro = supports.filter { it >= 0 } + attacks.filter { it < 0 }.map { -it }
        val con = attacks.filter { it >= 0 } + supports.filter { it < 0 }.map { -it }
        return sigmoid(alpha * logit(clampBase(base)) + pnorm(pro, p) - pnorm(con, p))
    }
}

/**
 * Log-odds DF-QuAD (weight of evidence): DF-QuAD's probabilistic sum read
 * additively in evidence space — w(e) = −ln(1 − min(e, emax)) — and applied in
 * log-odds: `sigmoid(α·logit(base) + k·(‖W_S‖_p − ‖W_A‖_p))`.
 * Defaults α = 1, k = 1.2, p = 2, emax = 0.7.
 */
class WeightOfEvidence(
    val alpha: Double = 1.0,
    val k: Double = 1.2,
    val p: Double = 2.0,
    val emax: Double = 0.7,
) : Semantics {
    private fun weight(e: Double) = -ln(1 - e.coerceIn(0.0, emax))

    override fun combine(base: Double, attacks: List<Double>, supports: List<Double>): Double =
        sigmoid(alpha * logit(clampBase(base)) + k * (pnorm(supports.map(::weight), p) - pnorm(attacks.map(::weight), p)))
}

/**
 * SPEC §2 "Credence layers and consensus": the consensus credence of a node is
 * the geometric mean of its member layers' odds, `sigmoid(mean(logit(c_i)))`
 * with each c_i clamped to [[LOW], [HIGH]]. It summarises layers that were
 * propagated independently; nothing feeds it back into any layer.
 */
object Consensus {
    const val LOW = 0.001
    const val HIGH = 0.999
    /** The layers that pass every intuition check (D1–D5c) at their defaults. */
    val DEFAULT_MEMBERS = listOf("wlo", "jnb", "woe")

    /** The consensus of [credences] (layer id → credence) over [members]; all layers when no member is present. */
    fun of(credences: Map<String, Double>, members: Collection<String>): Double {
        val xs = members.mapNotNull { credences[it] }.ifEmpty { credences.values.toList() }
        return ofValues(xs)
    }

    /** The consensus of the member credences [xs], in member order. */
    fun ofValues(xs: List<Double>): Double {
        if (xs.isEmpty()) return 0.5
        return sigmoid(xs.map { logit(it.coerceIn(LOW, HIGH)) }.average())
    }
}

/**
 * Model A: absolute shares for the listed positions of one issue. Credences
 * are used as weights and normalised only when their sum exceeds one. The
 * remainder `1 - shares.sum()` is the share that none of the listed positions
 * holds. This makes complementary binary positions reproduce their verdict,
 * preserves first impressions instead of sharpening their odds, and exposes
 * jointly implausible positions instead of forcing them to fill the frame.
 *
 * The object keeps its original name for source compatibility with the first
 * model-A implementation; the operation is deliberately no longer a softmax.
 */
object Softmax {
    fun shares(credences: List<Double>): List<Double> {
        if (credences.isEmpty()) return emptyList()
        val weights = credences.map { it.coerceIn(0.0, 1.0) }
        val total = maxOf(1.0, weights.sum())
        return weights.map { it / total }
    }
}

/**
 * The credence layers one deliberation graph evaluates (SPEC CRED-04): every
 * claim and edge cell computes one credence per layer, so a credence is a
 * vector indexed like [ids]. [consensusMembers] are the layers [Consensus]
 * averages (those that do not run are skipped; none running means all);
 * [headline] is what `NodeDto.credence` shows: a layer id, or [CONSENSUS]
 * (the default) for the consensus value itself.
 */
class LayerSet(
    val ids: List<String>,
    val semantics: List<Semantics>,
    consensusMembers: List<String> = Consensus.DEFAULT_MEMBERS,
    val headline: String = ids.first(),
) : java.io.Serializable {
    init {
        require(ids.isNotEmpty()) { "at least one credence layer must run" }
        require(ids.size == semantics.size) { "one semantics per layer id" }
        require(ids.distinct() == ids) { "duplicate layer ids: $ids" }
        require(headline == CONSENSUS || headline in ids) { "unknown headline layer '$headline'" }
    }

    /** The consensus members that run, in their configured order; every layer when none does. */
    val members: List<String> = consensusMembers.filter { it in ids }.ifEmpty { ids }
    private val memberIndex = members.map(ids::indexOf)
    private val headlineIndex = ids.indexOf(headline)

    /** The value `NodeDto.credence` shows for a node with this credence vector and consensus. */
    fun headlineOf(values: List<Double>, consensus: Double): Double =
        if (headline == CONSENSUS) consensus else values[headlineIndex]

    /**
     * One credence per layer for a node with these [stances] and arguments.
     * [priorWeight] < 1 shrinks each layer's base towards [NEUTRAL_PRIOR] —
     * `½ + w·(base − ½)` — before the arguments are weighed (model D's local
     * arguments-first view); 1 (the default) is the ordinary credence.
     */
    fun evaluate(
        stances: Collection<Double>,
        attacks: List<List<Arg>>,
        supports: List<List<Arg>>,
        priorWeight: Double = 1.0,
    ): List<Double> = semantics.mapIndexed { l, s ->
        val base = s.base(stances).let { if (priorWeight == 1.0) it else NEUTRAL_PRIOR + priorWeight * (it - NEUTRAL_PRIOR) }
        s.evaluate(base, attacks.map { it[l] }, supports.map { it[l] })
    }

    /** SPEC CRED-05, over a credence vector. Same arithmetic, same order as [Consensus.of]. */
    fun consensus(values: List<Double>): Double = Consensus.ofValues(memberIndex.map { values[it] })

    /** Layer id → credence. */
    fun named(values: List<Double>): Map<String, Double> = ids.zip(values).toMap()

    companion object {
        /** Headline value meaning "show the consensus" rather than one layer (the default). */
        const val CONSENSUS = "consensus"

        /** Model D: the prior the arguments-first view shrinks Jev's first impression towards. */
        const val NEUTRAL_PRIOR = 0.5

        /**
         * Model D: the weight Jev's first impression keeps in the local
         * arguments-first view, `½ + w·(p − ½)`. 0 — a neutral
         * prior — is the smallest choice; a weak prior would be 0 < w < 1.
         */
        const val WEAK_PRIOR_WEIGHT = 0.0

        /** Model D: [a] and [b] fall strictly on different sides of ½ (a value of exactly ½ is on neither). */
        fun oppositeSides(a: Double, b: Double): Boolean = (a - NEUTRAL_PRIOR) * (b - NEUTRAL_PRIOR) < 0

        /** Every layer in [ids] from the catalog. */
        fun of(
            ids: List<String>,
            consensusMembers: List<String> = Consensus.DEFAULT_MEMBERS,
            headline: String = ids.first(),
            wlo: WeightedLogOdds = WeightedLogOdds(),
        ) = LayerSet(ids, ids.map { SemanticsCatalog.of(it, wlo) }, consensusMembers, headline)
    }
}

/** Euler-based semantics (Amgoud & Ben-Naim 2018): `1 − (1 − b²) / (1 + b·e^E)`, E = Σ supports − Σ attacks. */
object EulerBased : Semantics {
    override fun combine(base: Double, attacks: List<Double>, supports: List<Double>): Double =
        1 - (1 - base * base) / (1 + base * exp(net(attacks, supports)))
}

/** Quadratic energy (Potyka 2018): `b − b·h(−E) + (1 − b)·h(E)`, h(x) = max(0,x)² / (1 + max(0,x)²). */
object QuadraticEnergy : Semantics {
    private fun h(x: Double): Double {
        val m = maxOf(0.0, x)
        return m * m / (1 + m * m)
    }

    override fun combine(base: Double, attacks: List<Double>, supports: List<Double>): Double {
        val e = net(attacks, supports)
        return base - base * h(-e) + (1 - base) * h(e)
    }
}

/** MLP-based semantics (Potyka 2021): `sigmoid(logit(b) + Σ supports − Σ attacks)`. */
object MlpBased : Semantics {
    override fun combine(base: Double, attacks: List<Double>, supports: List<Double>): Double =
        sigmoid(logit(clampBase(base)) + net(attacks, supports))
}

/**
 * Gated log-odds: the rule the deliberate credence benchmark selected
 * (doc/research/deliberate-credence-bench, Tiers 1, 2 and 4). An argument's
 * energy is `2·atanh(min(s·u(c), [cap]))`, where the gate
 * `u(c) = max(0, 2·sigmoid(k·logit c) − 1)` keeps a doubted source (c ≤ ½)
 * inert and lets a believed one count nearly in full, and the strength s caps
 * what it can contribute. Each side aggregates by a 2-norm, so a duplicate
 * counts √2 rather than 2 times and a flood of weak arguments grows like √n:
 * `sigmoid(logit(base) + ‖S‖₂ − ‖A‖₂)`. Support and attack are weighted
 * alike. Strength and credence arrive separately, as for [JeffreyNaiveBayes].
 * Defaults k = 5, cap = 0.999; no fitted weights.
 */
class GatedLogOdds(val k: Double = 5.0, val cap: Double = 0.999) : Semantics {
    init {
        require(k > 0 && cap > 0 && cap < 1) { "glo needs k > 0 and 0 < cap < 1" }
    }

    /** `2·sigmoid(k·logit c) − 1` written without the logit, floored at 0: exact at c = 0 and c = 1. */
    private fun gate(c: Double): Double {
        val a = c.coerceIn(0.0, 1.0).pow(k)
        val b = (1 - c.coerceIn(0.0, 1.0)).pow(k)
        return maxOf(0.0, (a - b) / (a + b))
    }

    override fun energy(arg: Arg): Double {
        val x = minOf(arg.strength.coerceIn(0.0, 1.0) * gate(arg.credence), cap)
        return ln((1 + x) / (1 - x))
    }

    override fun combine(base: Double, attacks: List<Double>, supports: List<Double>): Double =
        sigmoid(logit(clampBase(base)) + norm2(supports) - norm2(attacks))

    private fun norm2(xs: List<Double>) = sqrt(xs.sumOf { it * it })

    override fun toString() = "glo(k=$k, cap=$cap)"
}
