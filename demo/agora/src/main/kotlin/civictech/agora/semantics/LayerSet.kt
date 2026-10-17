package civictech.agora.semantics

import kotlin.math.exp
import kotlin.math.ln

/**
 * Consensus from the independent credence layers in `demo/deliberate/SPEC.md`
 * CRED-05: the geometric mean of member-layer odds. It summarizes layers and
 * never feeds back into them.
 */
object Consensus {
    private const val serialVersionUID: Long = 1L

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

    private fun logit(p: Double) = ln(p / (1 - p))
    private fun sigmoid(z: Double) = 1 / (1 + exp(-z))
}

/**
 * The credence layers one deliberation graph evaluates (`demo/deliberate/SPEC.md`
 * CRED-04): every claim and edge cell computes one credence per layer, so a
 * credence is a vector indexed like [ids]. [consensusMembers] are the layers
 * [Consensus] averages (those that do not run are skipped; none running means
 * all); [headline] is what deliberate's `NodeDto.credence` shows: a layer id,
 * or [CONSENSUS] (the default) for the consensus value itself.
 */
class LayerSet(
    val ids: List<String>,
    val semantics: List<GradualSemantics>,
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

    /** The value deliberate's `NodeDto.credence` shows for this vector and consensus. */
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

    /** CRED-05 over a credence vector, in the same order as [Consensus.of]. */
    fun consensus(values: List<Double>): Double = Consensus.ofValues(memberIndex.map { values[it] })

    /** Layer id → credence. */
    fun named(values: List<Double>): Map<String, Double> = ids.zip(values).toMap()

    companion object {
        private const val serialVersionUID: Long = 1L

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
