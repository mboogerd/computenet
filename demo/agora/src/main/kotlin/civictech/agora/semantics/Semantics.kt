package civictech.agora.semantics

/**
 * One argument as a semantics layer sees it (`demo/deliberate/SPEC.md`
 * CRED-04): the [strength] of its edge — the edge's own credence in that
 * layer, i.e. the Jev strength stance lowered by any undercutter — and the
 * [credence] of its source claim in that layer. They arrive separately
 * (deliberate's `Influence` carries both vectors), so a semantics that treats
 * them differently, like [JeffreyNaiveBayes], is exact rather than reading
 * their product.
 */
data class Arg(val strength: Double, val credence: Double)

/**
 * Gradual argumentation semantics: how a claim's credence follows from its
 * stances and the energies of its incoming attack/support edges. Pure — the
 * same functions drive the incremental cells and the batch reference solver
 * in the exit test, so incremental == batch is a property of the propagation
 * machinery, never of divergent math.
 */
interface GradualSemantics : java.io.Serializable {
    /**
     * Base score from raw per-user stances; empty → neutral 0.5. MUST clamp
     * away from 0 and 1: the clamp is what keeps a single cycle's loop gain
     * below 1 (|∂combine/∂energy| ≤ max(base, 1−base)), so feedback decays
     * geometrically instead of oscillating forever.
     */
    fun base(stances: Collection<Double>): Double

    /** Turn one edge strength/source-credence pair into an influence energy. */
    fun energy(arg: Arg): Double = arg.strength.coerceIn(0.0, 1.0) * arg.credence.coerceIn(0.0, 1.0)

    /**
     * Combine base with attack/support energies into a credence. Total,
     * order-insensitive in each list, result in [0,1].
     */
    fun combine(base: Double, attacks: List<Double>, supports: List<Double>): Double

    /** The credence of a claim with [base] and these arguments. */
    fun evaluate(base: Double, attacks: List<Arg>, supports: List<Arg>): Double =
        combine(base, attacks.map(::energy), supports.map(::energy))

    /**
     * True when [energy] reads the source credence other than as a factor, so
     * agora's product influence `s·c` cannot stand in for it; `ClaimCell`
     * refuses such a semantics.
     */
    val separatesCredence: Boolean
        get() = false
}

/**
 * DF-QuAD (Rago/Toni/Aurisicchio/Baroni 2016) adapted to weighted edges:
 * energies aggregate by probabilistic sum, and the winning side's surplus
 * moves the base toward its extreme, scaled by the remaining headroom —
 * continuous at the tie, monotone in each energy.
 */
object DfQuad : GradualSemantics {
    private const val serialVersionUID: Long = 3368913810358108050L
    const val BASE_FLOOR = 0.01

    override fun base(stances: Collection<Double>): Double =
        (if (stances.isEmpty()) 0.5 else stances.average()).coerceIn(BASE_FLOOR, 1 - BASE_FLOOR)

    override fun combine(base: Double, attacks: List<Double>, supports: List<Double>): Double {
        val ea = probSum(attacks)
        val es = probSum(supports)
        return if (es >= ea) base + (1 - base) * (es - ea)
        else base - base * (ea - es)
    }

    /** F(xs) = 1 − ∏(1 − xᵢ): monotone, order-free, saturating at 1. */
    private fun probSum(energies: List<Double>): Double =
        1 - energies.fold(1.0) { p, v -> p * (1 - v.coerceIn(0.0, 1.0)) }
}
