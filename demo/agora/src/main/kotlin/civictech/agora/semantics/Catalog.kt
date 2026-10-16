package civictech.agora.semantics

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The gradual semantics catalog specified by `demo/deliberate/SPEC.md`
 * CRED-03/04. Each rule keeps Agora's clamped mean stance base; the
 * deliberate `NodeDto.credence` is a consumer-facing projection of the result.
 */
object SemanticsCatalog {
    /** Every semantics the app can run, in display order. `dfquad` always runs. */
    val IDS = listOf("dfquad", "wlo", "jnb", "woe", "euler", "qe", "mlp", "glo")
    const val DEFAULT_PRIMARY = "dfquad"

    fun of(id: String, wlo: WeightedLogOdds = WeightedLogOdds()): GradualSemantics = when (id) {
        "dfquad" -> DfQuad
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

private fun defaultBase(stances: Collection<Double>) = DfQuad.base(stances)
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
) : GradualSemantics {
    private companion object {
        const val serialVersionUID: Long = 1L
    }

    init {
        require(p >= 1) { "wlo p must be >= 1: $p" }
        require(k >= 0 && alpha >= 0 && gamma > 0) { "wlo needs alpha >= 0, k >= 0, gamma > 0" }
    }

    override fun base(stances: Collection<Double>) = defaultBase(stances)

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
 * matters, and the reason an influence carries both vectors rather than their
 * product.
 */
class JeffreyNaiveBayes(
    val alpha: Double = 1.0,
    val bigK: Double = 0.7,
    val p: Double = 2.0,
    val r: Double = 0.0,
    val smax: Double = 0.8,
) : GradualSemantics {
    private companion object {
        const val serialVersionUID: Long = 1L
    }

    override val separatesCredence: Boolean
        get() = true

    override fun base(stances: Collection<Double>) = defaultBase(stances)

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
) : GradualSemantics {
    private companion object {
        const val serialVersionUID: Long = 1L
    }

    override fun base(stances: Collection<Double>) = defaultBase(stances)

    private fun weight(e: Double) = -ln(1 - e.coerceIn(0.0, emax))

    override fun combine(base: Double, attacks: List<Double>, supports: List<Double>): Double =
        sigmoid(alpha * logit(clampBase(base)) + k * (pnorm(supports.map(::weight), p) - pnorm(attacks.map(::weight), p)))
}

/** Euler-based semantics (Amgoud & Ben-Naim 2018): `1 − (1 − b²) / (1 + b·e^E)`, E = Σ supports − Σ attacks. */
object EulerBased : GradualSemantics {
    private const val serialVersionUID: Long = 1L

    override fun base(stances: Collection<Double>) = defaultBase(stances)

    override fun combine(base: Double, attacks: List<Double>, supports: List<Double>): Double =
        1 - (1 - base * base) / (1 + base * exp(net(attacks, supports)))
}

/** Quadratic energy (Potyka 2018): `b − b·h(−E) + (1 − b)·h(E)`, h(x) = max(0,x)² / (1 + max(0,x)²). */
object QuadraticEnergy : GradualSemantics {
    private const val serialVersionUID: Long = 1L

    override fun base(stances: Collection<Double>) = defaultBase(stances)

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
object MlpBased : GradualSemantics {
    private const val serialVersionUID: Long = 1L

    override fun base(stances: Collection<Double>) = defaultBase(stances)

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
class GatedLogOdds(val k: Double = 5.0, val cap: Double = 0.999) : GradualSemantics {
    private companion object {
        const val serialVersionUID: Long = 1L
    }

    init {
        require(k > 0 && cap > 0 && cap < 1) { "glo needs k > 0 and 0 < cap < 1" }
    }

    override val separatesCredence: Boolean
        get() = true

    override fun base(stances: Collection<Double>) = defaultBase(stances)

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
