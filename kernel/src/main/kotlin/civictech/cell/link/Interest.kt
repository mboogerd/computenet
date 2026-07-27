package civictech.cell.link

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

    /**
     * The interest algebra closes here (PN-3a, plan §2 F6, spec 42 §Interest-
     * scoped instance sets). Before this, combinators over interests were
     * anonymous `object : Interest` values that returned `overlaps = true`
     * unconditionally — a lie the linker cannot distinguish from a real overlap,
     * and non-`Serializable` (a captured lambda), so they could never ride the
     * versioned interest-assignment table across the wire. Every arm below is a
     * `data class`/`object`: [overlaps] is computed structurally by the single
     * symmetric [Companion.overlap] (honest — provably-disjoint pairs return
     * `false`, undecidable pairs stay conservatively `true` but *symmetrically*),
     * and every arm round-trips through Java serialization to an `equals` value.
     */
    companion object {
        /**
         * Structural, **symmetric** overlap decision shared by every arm (the
         * honesty [overlaps] promises). Symmetric by construction: the pair is
         * matched without regard to argument order, so `overlap(a, b) ==
         * overlap(b, a)` for all arms. Honest where decidable — [Empty] overlaps
         * nothing, [Slots]/[Ranges] compare their sets exactly, [Union]/
         * [Intersect] distribute over their members — and conservatively `true`
         * only where a shared key is genuinely undecidable ([Complement], mixed
         * slot/range kinds), never the blanket `true` the old anonymous
         * combinators returned.
         */
        fun overlap(a: Interest, b: Interest): Boolean = when {
            a is Empty || b is Empty -> false
            a is Total || b is Total -> true
            a is Union -> a.members.any { overlap(it, b) }
            b is Union -> b.members.any { overlap(a, it) }
            a is Intersect -> a.members.all { overlap(it, b) }
            b is Intersect -> b.members.all { overlap(a, it) }
            a is Complement || b is Complement -> true // negation: a shared key is undecidable — link conservatively
            a is Slots && b is Slots -> a.slots.any { it in b.slots }
            a is Ranges && b is Ranges -> a.intersectsRanges(b)
            else -> true // mixed decidable kinds (Slots vs Ranges): conservative, filtering still applies per-emission
        }
    }

    /** Total interest — every key, every delta (the replication setting; the default). */
    @kotlinx.serialization.Serializable
    @kotlinx.serialization.SerialName("Interest.Total")
    object Total : Interest {
        // honest against the new algebra: Total overlaps everything EXCEPT Empty
        // (symmetric with Empty.overlaps). For every pre-existing kind (Total,
        // Slots) this still returns true, so non-opting graphs are unchanged.
        override fun overlaps(other: Interest): Boolean = overlap(this, other)
        override fun admits(key: Any?): Boolean = true
        override fun toString(): String = "Interest.Total"
        private fun readResolve(): Any = Total
    }

    /** Empty interest — no key, no delta: overlaps nothing, admits nothing (the identity for [Union]). */
    @kotlinx.serialization.Serializable
    @kotlinx.serialization.SerialName("Interest.Empty")
    object Empty : Interest {
        override fun overlaps(other: Interest): Boolean = false
        override fun admits(key: Any?): Boolean = false
        override fun toString(): String = "Interest.Empty"
        private fun readResolve(): Any = Empty
    }

    /** Admits a key any member admits (∪); overlaps anything any member overlaps. */
    @kotlinx.serialization.Serializable
    @kotlinx.serialization.SerialName("Interest.Union")
    data class Union(val members: List<@kotlinx.serialization.Polymorphic Interest>) : Interest {
        override fun overlaps(other: Interest): Boolean = overlap(this, other)
        override fun admits(key: Any?): Boolean = members.any { it.admits(key) }
    }

    /** Admits a key every member admits (∩); the empty intersect admits all (vacuous). */
    @kotlinx.serialization.Serializable
    @kotlinx.serialization.SerialName("Interest.Intersect")
    data class Intersect(val members: List<@kotlinx.serialization.Polymorphic Interest>) : Interest {
        override fun overlaps(other: Interest): Boolean = overlap(this, other)
        override fun admits(key: Any?): Boolean = members.all { it.admits(key) }
    }

    /** Admits exactly the keys [of] does not (¬); overlap against a negation is undecidable ⇒ conservative. */
    @kotlinx.serialization.Serializable
    @kotlinx.serialization.SerialName("Interest.Complement")
    data class Complement(@kotlinx.serialization.Polymorphic val of: Interest) : Interest {
        override fun overlaps(other: Interest): Boolean = overlap(this, other)
        override fun admits(key: Any?): Boolean = !of.admits(key)
    }

    /**
     * A union of half-open integer ranges `[lo, hi)` over a key's numeric value
     * (the ordered-key partitioner, complementary to [Slots]' hash partitioner).
     * A key admits iff `(key as Number).toLong()` lands in some range; a
     * non-numeric key admits nowhere. Two [Ranges] overlap iff any of their
     * ranges intersect — decidable and honest.
     */
    @kotlinx.serialization.Serializable
    @kotlinx.serialization.SerialName("Interest.Ranges")
    data class Ranges(val ranges: List<Range>) : Interest {
        @kotlinx.serialization.Serializable
        @kotlinx.serialization.SerialName("Interest.Range")
        data class Range(val lo: Long, val hi: Long) : java.io.Serializable {
            init { require(lo <= hi) { "range lo ($lo) must be <= hi ($hi)" } }
            fun contains(v: Long): Boolean = v in lo until hi
            fun intersects(other: Range): Boolean = lo < other.hi && other.lo < hi
        }

        override fun overlaps(other: Interest): Boolean = overlap(this, other)
        override fun admits(key: Any?): Boolean {
            val v = (key as? Number)?.toLong() ?: return false
            return ranges.any { it.contains(v) }
        }

        /** Do any of this interest's ranges intersect any of [other]'s? (symmetric) */
        fun intersectsRanges(other: Ranges): Boolean =
            ranges.any { r -> other.ranges.any { r.intersects(it) } }
    }

    /**
     * A hash-slot set out of [totalSlots] slots (spec 42: "hash-slot set").
     * A key belongs to slot `floorMod(key.hashCode(), totalSlots)`; the
     * interest admits a key iff its slot is in [slots]. Two [Slots] interests
     * overlap iff their slot sets intersect — so a disjoint slot assignment
     * (the partitioning setting) forms no cross-shard links, and the union of
     * the shards is conflict-free by construction.
     */
    @kotlinx.serialization.Serializable
    @kotlinx.serialization.SerialName("Interest.Slots")
    data class Slots(val slots: Set<Int>, val totalSlots: Int) : Interest {
        init {
            require(totalSlots > 0) { "totalSlots must be positive, got $totalSlots" }
        }

        // routed through the shared symmetric decision (Slots vs Slots still
        // compares slot sets exactly, Slots vs Total still true) — bit-identical
        // for every pre-existing kind; only the new arms are decided honestly.
        override fun overlaps(other: Interest): Boolean = overlap(this, other)

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

/**
 * T05 finding 6: a running count of [sliceTo] refusals — an emission whose
 * delta is not [Scoped] offered to a non-[Interest.Total] interest. Every
 * refusal counts here; the accompanying `System.err` line is emitted once per
 * offending delta class (see [loggedRefusals]), matching the current registry
 * style (e.g. [civictech.cell.port.FanOutlet]'s once-per-ref target-miss log).
 * The residual per-link-ack gap for a *legitimate* `Scoped`-slices-to-nothing
 * result is separate, and is filed as **G-66** in
 * `doc/spec/90-roadmap/91-gap-analysis.md` (T05 finding 8) — this counter is
 * specifically the "shipped whole" bug, now refused.
 */
val refusedSliceCount = java.util.concurrent.atomic.AtomicLong()

/**
 * Delta classes already reported by [sliceTo]'s refusal path — log once per
 * class, not once per emission. `sliceTo` runs on the per-emission gossip/
 * shard-routing path, so an unconditional `println` turns a single
 * misconfigured non-`Total` mesh into unbounded stderr traffic proportional
 * to throughput; [refusedSliceCount] remains the exact, per-refusal signal.
 */
private val loggedRefusals = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

/**
 * The **one** slice-and-route primitive (PN-6, plan §3 "keep exactly one
 * link/slice mechanism"): restrict [delta] to the sub-delta [interest] admits
 * over [keyOf]. Both the gossip linker ([civictech.cell.replication.Replication],
 * `keyOf` = identity — the element *is* the key in a replica mesh) and the shard
 * router ([civictech.cell.partition.PartitionedShardSet], `keyOf` = the group key) are
 * this same function under different `keyOf`s — replication and partitioning are
 * two settings of one knob, not two mechanisms.
 *
 * [Interest.Total] short-circuits to the whole delta (the replication default,
 * byte-identical to no filter); `null` means nothing is admitted and the
 * emission is dropped. T05 finding 6: a non-[Scoped] delta offered to a
 * **non-Total** interest used to ride the link whole (`else -> delta`) —
 * silently breaking "a delta a peer has no interest in never crosses" (spec
 * 40/42 §Interest-scoped instance sets) for `Replicable`s whose delta doesn't
 * implement `Scoped` (`PnCounterDelta`, `WatermarkDelta`, `CounterDelta`,
 * `ListDelta`) yet can join a partial-interest mesh (`PnCounterCell`,
 * `WatermarkCell`). Now refused — dropped, counted, logged — like a
 * legitimate `Scoped`-slices-to-nothing result, per
 * [civictech.cell.data.Replicable]'s KDoc rule.
 */
@Suppress("UNCHECKED_CAST")
fun <D> sliceTo(delta: D, interest: Interest, keyOf: (Any?) -> Any?): D? = when {
    interest is Interest.Total -> delta
    delta is Scoped<*> -> (delta as Scoped<D>).within(interest, keyOf)
    else -> {
        refusedSliceCount.incrementAndGet()
        val kind = delta?.let { it::class.simpleName } ?: "null"
        if (loggedRefusals.add(kind)) {
            System.err.println(
                "[sliceTo] refused: $kind is not Scoped and interest is not " +
                    "Total (spec 40/42 §Interest-scoped instance sets) — a non-Total mesh requires a Scoped " +
                    "delta; dropping rather than shipping it whole across a partial-interest link " +
                    "(logged once per delta class; see refusedSliceCount for the exact count)"
            )
        }
        null
    }
}
