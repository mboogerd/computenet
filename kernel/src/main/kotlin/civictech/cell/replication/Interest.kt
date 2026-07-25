package civictech.cell.replication

import java.io.Serializable

/**
 * A per-instance demand predicate over the delta/key space (spec 40/42
 * §Interest-scoped instance sets, realizing CP-D1). Replication and
 * partitioning are not two mechanisms — they are two settings of this one
 * knob over one instance-set mesh:
 *
 * - **total** interest on every instance ⇒ replication (every delta rides
 *   every link; the cell's idempotent merge dedups echoes);
 * - **disjoint** key-interest ⇒ partitioning (each instance owns one key
 *   range, no two overlap, so union is conflict-free with the merge function
 *   never exercised);
 * - **overlapping partial** interest ⇒ sharded replication (idempotent on the
 *   overlap, disjoint on the remainder).
 *
 * The linker ([Replication.maybeLink]) consults two instances' interests to
 * decide whether a gossip link forms at all ([overlaps]) and filters every
 * emission to the *target's* interest before it rides the link ([admits]): a
 * delta a peer has no interest in never crosses. [Total] is the default — with
 * it, [overlaps] is always true and [admits] is always true, so the linker's
 * behavior is byte-identical to pre-interest gossip.
 *
 * Interest is [Serializable] so it can be announced with a ref and cross the
 * wire (the versioned interest-assignment table, `PartitionedCell.routingEpoch`,
 * CP-D3).
 */
interface Interest : Serializable {

    /**
     * Does this interest share any key-space with [other]? A gossip link forms
     * only where two instances' interests overlap; disjoint interests never
     * link, so a delta cannot even reach an instance that does not want it.
     * MUST be symmetric.
     */
    fun overlaps(other: Interest): Boolean

    /** Does this interest want the delta touching [key]? The per-element emission filter. */
    fun admits(key: Any?): Boolean

    /** Total interest — every key, every delta (the replication setting; the default). */
    object Total : Interest {
        override fun overlaps(other: Interest): Boolean = true
        override fun admits(key: Any?): Boolean = true
        override fun toString(): String = "Interest.Total"
        private fun readResolve(): Any = Total
    }

    /**
     * A hash-slot set out of [totalSlots] slots (spec 42: "hash-slot set").
     * A key belongs to slot `floorMod(key.hashCode(), totalSlots)`; the
     * interest admits a key iff its slot is in [slots]. Two [Slots] interests
     * overlap iff their slot sets intersect — so a disjoint slot assignment
     * (the partitioning setting) forms no cross-shard links, and the union of
     * the shards is conflict-free by construction.
     */
    data class Slots(val slots: Set<Int>, val totalSlots: Int) : Interest {
        init {
            require(totalSlots > 0) { "totalSlots must be positive, got $totalSlots" }
        }

        override fun overlaps(other: Interest): Boolean = when (other) {
            is Total -> true
            is Slots -> slots.any { it in other.slots }
            else -> true // unknown interest kind: link conservatively, filtering still applies per-emission
        }

        override fun admits(key: Any?): Boolean = slotOf(key, totalSlots) in slots

        companion object {
            /** The slot a [key] hashes to over [totalSlots] slots — the total, deterministic partitioner. */
            fun slotOf(key: Any?, totalSlots: Int): Int = Math.floorMod((key?.hashCode() ?: 0), totalSlots)

            /** The disjoint one-slot-per-shard assignment over [totalSlots]: shard i owns slots ≡ i (mod totalSlots). */
            fun forShard(shard: Int, shardCount: Int, totalSlots: Int): Slots =
                Slots((0 until totalSlots).filterTo(mutableSetOf()) { Math.floorMod(it, shardCount) == shard }, totalSlots)
        }
    }
}

/**
 * A delta that can be restricted to the sub-delta an [Interest] admits (spec
 * 42 §Interest-scoped instance sets): the per-emission filter the linker
 * applies before a delta rides a link to a partial-interest target. A delta
 * that does not implement this rides links whole (the replication default);
 * only interest-scoped substrates (`PartitionedCell`, CP-D3) need the filter.
 */
interface Scoped<D> {
    /**
     * The largest sub-delta of this delta whose keys [interest] admits, or
     * `null` when nothing remains (the emission is dropped rather than riding
     * the link). [keyOf] projects an element to the key the interest is scoped
     * over — for a group-partitioned structure that is the group key, not the
     * raw element.
     */
    fun within(interest: Interest, keyOf: (Any?) -> Any?): D?
}
