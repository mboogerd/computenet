package civictech.cell.data.delta

import civictech.cell.MergeablePayload
import civictech.cell.Timestamp
import java.io.Serializable

/**
 * Observed-remove set delta (G-23): every add carries a unique tag; a remove
 * carries exactly the tags it observed. Merging is tag-set union — commutative,
 * associative, idempotent — so membership converges regardless of arrival
 * order. An element is present iff it has an add-tag not covered by a del.
 * Add-wins falls out: a concurrent add's tag is never observed by the remove.
 */
@kotlinx.serialization.Serializable
@kotlinx.serialization.SerialName("SetDelta")
data class SetDelta<E>(
    val adds: Map<E, Set<Timestamp>> = emptyMap(),
    val dels: Map<E, Set<Timestamp>> = emptyMap(),
) : Serializable, MergeablePayload, civictech.cell.link.Scoped<SetDelta<E>> {
    fun merge(other: SetDelta<E>): SetDelta<E> =
        SetDelta(mergeTags(adds, other.adds), mergeTags(dels, other.dels))

    @Suppress("UNCHECKED_CAST")
    override fun mergeWith(other: MergeablePayload): MergeablePayload = merge(other as SetDelta<E>)

    /**
     * Restrict this delta to the elements whose key [interest] admits (spec 42
     * §Interest-scoped instance sets): the per-emission filter the gossip
     * linker applies to a partial-interest target. [keyOf] projects an element
     * to the key the interest is scoped over (identity for a replica mesh, the
     * group key for a `PartitionedCell` shard). Returns `null` when the
     * restriction is empty — the emission never rides the link.
     */
    override fun within(
        interest: civictech.cell.link.Interest,
        keyOf: (Any?) -> Any?,
    ): SetDelta<E>? {
        if (interest is civictech.cell.link.Interest.Total) return this
        val a = adds.filterKeys { interest.admits(keyOf(it)) }
        val d = dels.filterKeys { interest.admits(keyOf(it)) }
        return if (a.isEmpty() && d.isEmpty()) null else SetDelta(a, d)
    }

    companion object {
        private fun <E> mergeTags(
            a: Map<E, Set<Timestamp>>,
            b: Map<E, Set<Timestamp>>,
        ): Map<E, Set<Timestamp>> =
            (a.keys + b.keys).associateWith { (a[it] ?: emptySet()) + (b[it] ?: emptySet()) }
    }
}
