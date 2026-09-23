package civictech.timetravel.fidelity

/**
 * A verdict on how faithfully a reconstructed value (a cell, or a whole run) reflects
 * what actually ran, per the epic's honesty boundary (`doc/spec/20-dataflow-semantics/24-data-cells.md`
 * §Durability spectrum, "Boundary of the landed mechanism", [24-DUR-04]). Totally ordered
 * `Faithful < Degraded < Unreconstructible` by rank; [worst] merges two verdicts, keeping the
 * higher rank and the union of their reasons.
 *
 * `Degraded` and `Unreconstructible` must carry at least one [Reason] — an empty reason set
 * would be a verdict nobody can act on or explain ([TTD1-33], `computenet-kxex2` D1/D5).
 */
sealed interface Fidelity : Comparable<Fidelity> {
    /** Why this verdict is what it is. Empty only for [Faithful]. */
    val reasons: Set<Reason>

    /** The rank used for ordering: 0 for [Faithful], 1 for [Degraded], 2 for [Unreconstructible]. */
    val rank: Int

    override fun compareTo(other: Fidelity): Int = rank.compareTo(other.rank)

    /** The reconstruction matches what ran; nothing to report. */
    object Faithful : Fidelity {
        override val reasons: Set<Reason> = emptySet()
        override val rank: Int = 0
        override fun toString(): String = "Faithful"
    }

    /** The reconstruction is plausible but not guaranteed to match what ran, for [reasons]. */
    data class Degraded(override val reasons: Set<Reason>) : Fidelity {
        init {
            require(reasons.isNotEmpty()) { "Degraded requires at least one reason" }
        }

        override val rank: Int = 1
    }

    /** No reconstruction can be trusted at all, for [reasons]. Carries no value ([TTD1-34]). */
    data class Unreconstructible(override val reasons: Set<Reason>) : Fidelity {
        init {
            require(reasons.isNotEmpty()) { "Unreconstructible requires at least one reason" }
        }

        override val rank: Int = 2
    }

    companion object {
        /** Convenience factory for [Degraded] from varargs. */
        fun degraded(vararg reasons: Reason): Fidelity = Degraded(reasons.toSet())

        /** Convenience factory for [Unreconstructible] from varargs. */
        fun unreconstructible(vararg reasons: Reason): Fidelity = Unreconstructible(reasons.toSet())
    }
}

/**
 * The worse (higher-ranked) of [a] and [b]. When ranks tie — including `Faithful`/`Faithful` —
 * the result carries the union of both reason sets; [Fidelity.Faithful] has no reasons, so
 * `worst(Faithful, Faithful) == Faithful`.
 */
fun worst(a: Fidelity, b: Fidelity): Fidelity {
    val reasons = a.reasons + b.reasons
    return when {
        a.rank > b.rank -> withReasons(a, reasons)
        b.rank > a.rank -> withReasons(b, reasons)
        else -> withReasons(a, reasons)
    }
}

private fun withReasons(verdict: Fidelity, reasons: Set<Reason>): Fidelity = when (verdict) {
    is Fidelity.Faithful -> if (reasons.isEmpty()) Fidelity.Faithful else Fidelity.Degraded(reasons)
    is Fidelity.Degraded -> Fidelity.Degraded(reasons)
    is Fidelity.Unreconstructible -> Fidelity.Unreconstructible(reasons)
}
