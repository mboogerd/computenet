package civictech.cell.data.delta

import civictech.cell.IndexedValueSerializer
import java.io.Serializable

/**
 * Convergence limit (G-23, documented): index-addressed deltas are only
 * meaningful in the emission order of a single FIFO stream — concurrent
 * multi-writer edits do not converge. Stable multi-writer sequences need
 * position identifiers (RGA/LSEQ style), out of scope until replication (42).
 */
@kotlinx.serialization.Serializable
@kotlinx.serialization.SerialName("ListDelta")
data class ListDelta<E>(
    val adds: List<@kotlinx.serialization.Serializable(with = IndexedValueSerializer::class) IndexedValue<E>> = emptyList(),
    val updates: List<@kotlinx.serialization.Serializable(with = IndexedValueSerializer::class) IndexedValue<E>> = emptyList(),
    val removals: List<Int> = emptyList()
) : Serializable
