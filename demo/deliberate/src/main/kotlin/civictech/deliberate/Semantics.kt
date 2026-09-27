package civictech.deliberate

import civictech.agora.semantics.DfQuad
import civictech.agora.semantics.GradualSemantics
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * The gradual semantics a deliberation can be propagated under (SPEC CRED-03,
 * §2 "Semantics layers"). Each is an agora [GradualSemantics]: its `combine`
 * sees only the *energies* of a claim's incoming edges, and an agora edge's
 * energy is its own credence (the Jev strength stance, lowered by undercutters)
 * times its source's credence. Ported from the deliberate semantics prototype
 * (`semantics.js`); every candidate keeps agora's base, the clamped mean of the
 * stances (the prototype's `shrunkBase` at its default root-prior weight 1).
 */
object SemanticsCatalog {
    /** Every semantics the app can run, in display order. `dfquad` is the default primary. */
    val IDS = listOf("dfquad", "wlo", "jnb", "woe", "euler", "qe", "mlp")
    const val DEFAULT_PRIMARY = "dfquad"

    fun of(id: String, wlo: WeightedLogOdds = WeightedLogOdds()): GradualSemantics = when (id) {
        "dfquad" -> DfQuad
        "wlo" -> wlo
        "jnb" -> JeffreyNaiveBayes()
        "woe" -> WeightOfEvidence()
        "euler" -> EulerBased
        "qe" -> QuadraticEnergy
        "mlp" -> MlpBased
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
) : GradualSemantics {
    init {
        require(p >= 1) { "wlo p must be >= 1: $p" }
        require(k >= 0 && alpha >= 0 && gamma > 0) { "wlo needs alpha >= 0, k >= 0, gamma > 0" }
    }

    override fun base(stances: Collection<Double>): Double = DfQuad.base(stances)

    override fun combine(base: Double, attacks: List<Double>, supports: List<Double>): Double {
        fun g(e: Double) = e.coerceIn(0.0, 1.0).pow(gamma)
        return sigmoid(alpha * logit(clampBase(base)) + k * (pnorm(supports.map(::g), p) - pnorm(attacks.map(::g), p)))
    }

    override fun toString() = "wlo(alpha=$alpha, k=$k, p=$p, gamma=$gamma)"
}

/**
 * Jeffrey / naive-Bayes likelihood ratios: a true argument of strength s is
 * evidence with likelihood ratio `((1+s)/(1−s))^K`; its log-weight per side
 * aggregates by p-norm into `sigmoid(α·logit(base) + ‖W_S‖_p − ‖W_A‖_p)`.
 * Defaults α = 1, K = 0.7, p = 2, s clamped to `smax` = 0.8.
 *
 * Port note: the prototype's edge applies Jeffrey conditioning to the
 * credence c and strength s separately (`ln(c·LR(s) + 1 − c)`), but an agora
 * edge emits only their product e = c·s. The port reads e as the strength of
 * a certain argument (`K·ln((1+e)/(1−e))`) — exact when the source is certain,
 * and otherwise a doubted argument counts as a weaker sure one.
 */
class JeffreyNaiveBayes(
    val alpha: Double = 1.0,
    val bigK: Double = 0.7,
    val p: Double = 2.0,
    val smax: Double = 0.8,
) : GradualSemantics {
    override fun base(stances: Collection<Double>): Double = DfQuad.base(stances)

    private fun weight(e: Double): Double {
        val s = e.coerceIn(0.0, smax)
        return bigK * ln((1 + s) / (1 - s))
    }

    override fun combine(base: Double, attacks: List<Double>, supports: List<Double>): Double =
        sigmoid(alpha * logit(clampBase(base)) + pnorm(supports.map(::weight), p) - pnorm(attacks.map(::weight), p))
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
    override fun base(stances: Collection<Double>): Double = DfQuad.base(stances)

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
        if (xs.isEmpty()) return 0.5
        return sigmoid(xs.map { logit(it.coerceIn(LOW, HIGH)) }.average())
    }
}

/** Euler-based semantics (Amgoud & Ben-Naim 2018): `1 − (1 − b²) / (1 + b·e^E)`, E = Σ supports − Σ attacks. */
object EulerBased : GradualSemantics {
    override fun base(stances: Collection<Double>): Double = DfQuad.base(stances)

    override fun combine(base: Double, attacks: List<Double>, supports: List<Double>): Double =
        1 - (1 - base * base) / (1 + base * exp(net(attacks, supports)))
}

/** Quadratic energy (Potyka 2018): `b − b·h(−E) + (1 − b)·h(E)`, h(x) = max(0,x)² / (1 + max(0,x)²). */
object QuadraticEnergy : GradualSemantics {
    override fun base(stances: Collection<Double>): Double = DfQuad.base(stances)

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
    override fun base(stances: Collection<Double>): Double = DfQuad.base(stances)

    override fun combine(base: Double, attacks: List<Double>, supports: List<Double>): Double =
        sigmoid(logit(clampBase(base)) + net(attacks, supports))
}
