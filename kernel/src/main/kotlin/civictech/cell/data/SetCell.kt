package civictech.cell.data

import civictech.cell.CellRef
import civictech.cell.Stateful
import civictech.cell.TagFrontier
import civictech.cell.Timestamp
import civictech.cell.port.*
import civictech.gen.wire.CellBase
import civictech.gen.wire.Contract
import java.io.Serializable
import java.util.*
import civictech.cell.host.MergeablePayload

@Contract
interface SetOps<E> {
    fun add(element: E)
    fun remove(element: E)
}

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
) : Serializable, MergeablePayload, civictech.cell.replication.Scoped<SetDelta<E>> {
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
        interest: civictech.cell.replication.Interest,
        keyOf: (Any?) -> Any?,
    ): SetDelta<E>? {
        if (interest is civictech.cell.replication.Interest.Total) return this
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

@CellBase
interface SetApi<E> {
    val inlet: Use<SetOps<E>>
    val outlet: Subscribe<Propagate<SetDelta<E>>>
}

class SetCell<E>(ref: CellRef = CellRef(UUID.randomUUID())) :
    SetCellBase<E>(ref), Stateful, Replicable<SetDelta<E>>, DeliveryTracking {
    /**
     * Replica gossip intake (spec 42, M7.3): another replica's effective
     * deltas merge here; only *new* tag information re-emits (effective-only,
     * 21), so gossip echoes around any mesh topology die out.
     */
    override val deltaInlet = registerPort("deltaInlet", FanInlet.create<Propagate<SetDelta<E>>>())

    // Full OR-set (M7.3): adds = every add-tag ever seen, dels = tombstones.
    // An element is present iff it has an add-tag without a matching del-tag.
    // Tombstones are what make multi-path gossip safe: a removed tag arriving
    // late over another path stays removed.
    // ponytail: tag sets grow monotonically; compaction is future work (G-25)
    private val adds = mutableMapOf<E, MutableSet<Timestamp>>()
    private val dels = mutableMapOf<E, MutableSet<Timestamp>>()

    // Tags are minted locally, not taken from the wave's MessageContext:
    // observed-remove correctness needs a tag unique per add *instance*, and a
    // wave timestamp repeats across every cell the wave touches (22).
    // Replay-stable identity (M10.1): the source is DERIVED from the ref, so a
    // recovered instance replaying its journal re-mints the exact tags the
    // network already observed — random sources would resurrect removed
    // elements (a pre-crash remove can't cover a re-minted add). Uniqueness
    // across instances rides instanceId uniqueness (the replication contract).
    private val tagSource: UUID =
        UUID.nameUUIDFromBytes("set-tags:${ref.id}:${ref.instanceId}".toByteArray())
    private var tagCounter = 0L

    // Per-origin delivered frontier (spec 40/42 §Delivered watermarks, E3.3(a)):
    // add-tags this replica has durably absorbed, tracked as a max-contiguous
    // prefix per ORIGIN source (the tag's minting source, still visible here in
    // the fold). Listeners — the replica's WatermarkCell companion — advance on
    // each raised prefix, so the merged lattice answers "which origin waves has
    // the replica set delivered" (E3.4), not "how many did each replica re-emit".
    private val delivered = DeliveredFrontier()
    private val deliveryListeners = mutableListOf<(UUID, Long) -> Unit>()

    override fun onDeliver(listener: (source: UUID, thru: Long) -> Unit) {
        deliveryListeners += listener
    }

    /** Fold [tags] into the delivered frontier; notify listeners of each raised per-origin prefix. */
    private fun recordDelivered(tags: Iterable<Timestamp>) {
        if (deliveryListeners.isEmpty()) return
        val advanced = HashMap<UUID, Long>()
        for (tag in tags) delivered.deliver(tag.sourceId, tag.counter)?.let { advanced[tag.sourceId] = it }
        for ((source, thru) in advanced) deliveryListeners.forEach { it(source, thru) }
    }

    private fun liveTags(element: E): Set<Timestamp> =
        (adds[element] ?: emptySet<Timestamp>()) - (dels[element] ?: emptySet())

    /** Current membership: elements with at least one un-tombstoned add-tag. */
    fun membership(): Set<E> = adds.keys.filterTo(mutableSetOf()) { liveTags(it).isNotEmpty() }

    // constructed inline: the factory runs during base-class init, before this
    // class's own fields initialize — the object only *captures* `this`; its
    // methods read subclass state later, at message time.
    override fun inletHandler(): SetOps<E> = object : SetOps<E> {
        override fun add(element: E) {
            val tag = Timestamp(tagSource, ++tagCounter)
            adds.getOrPut(element) { mutableSetOf() } += tag
            recordDelivered(listOf(tag)) // a local mint is trivially contiguous
            outlet.call.propagate(SetDelta(adds = mapOf(element to setOf(tag))))
        }

        override fun remove(element: E) {
            // effective-only (21): removing an unobserved element is a no-op
            val observed = liveTags(element)
            if (observed.isEmpty()) return
            dels.getOrPut(element) { mutableSetOf() } += observed
            outlet.call.propagate(SetDelta(dels = mapOf(element to observed)))
        }
    }

    /** Merge a peer replica's delta; re-emit exactly the new tag information. */
    private fun applyRemote(delta: SetDelta<E>) {
        val newAdds = delta.adds
            .mapValues { (e, tags) -> tags - (adds[e] ?: emptySet()) }
            .filterValues { it.isNotEmpty() }
        val newDels = delta.dels
            .mapValues { (e, tags) -> tags - (dels[e] ?: emptySet()) }
            .filterValues { it.isNotEmpty() }
        if (newAdds.isEmpty() && newDels.isEmpty()) return // echo terminates here
        newAdds.forEach { (e, tags) -> adds.getOrPut(e) { mutableSetOf() } += tags }
        newDels.forEach { (e, tags) -> dels.getOrPut(e) { mutableSetOf() } += tags }
        // advance the per-origin delivered frontier before re-emitting: membership
        // now reflects these tags, so a peer reading the watermark that this
        // advance gossips will also see the element live here (E3.3(a)/E3.4).
        recordDelivered(newAdds.values.flatten())
        outlet.originate { propagate(SetDelta(newAdds, newDels)) }
    }

    /**
     * Highest tag counter observed per tag source, restricted to the keys
     * [scope] admits (spec 20/21 §Pull, 93 I-24; PN-3c). `null`/[Interest.Total]
     * scope iterates every key — byte-identical to the pre-scope frontier — so a
     * scope-absent pull's reported currency is unchanged.
     */
    private fun currentFrontier(scope: civictech.cell.replication.Interest? = null): TagFrontier {
        val admit: (E) -> Boolean =
            if (scope == null || scope is civictech.cell.replication.Interest.Total) { _ -> true }
            else { e -> scope.admits(e) }
        val frontier = mutableMapOf<UUID, Long>()
        val addSeq = adds.asSequence().filter { admit(it.key) }.map { it.value }
        val delSeq = dels.asSequence().filter { admit(it.key) }.map { it.value }
        (addSeq + delSeq).flatten().forEach { tag ->
            frontier.merge(tag.sourceId, tag.counter, ::maxOf)
        }
        return TagFrontier(frontier)
    }

    /**
     * Restrict a since-filtered output map to the keys [scope] admits (PN-3c):
     * the per-element interest filter a partial-interest pull applies. Returns
     * the same map unchanged for `null`/[Interest.Total] scope — the scope-absent
     * reply is verbatim.
     */
    private fun scopedTo(
        source: Map<E, Set<Timestamp>>,
        scope: civictech.cell.replication.Interest?,
    ): Map<E, Set<Timestamp>> =
        if (scope == null || scope is civictech.cell.replication.Interest.Total) source
        else source.filterKeys { scope.admits(it) }

    /** Only the tags a [since] frontier has not yet observed; unfiltered when [since] is null. */
    private fun sinceFilter(source: Map<E, MutableSet<Timestamp>>, since: TagFrontier?): Map<E, Set<Timestamp>> =
        source.mapValues { (_, tags) ->
            if (since == null) tags.toSet()
            else tags.filterTo(mutableSetOf()) { (since.perSource[it.sourceId] ?: -1L) < it.counter }
        }.filterValues { it.isNotEmpty() }

    init {
        deltaInlet.serve(object : Propagate<SetDelta<E>> {
            override fun propagate(value: SetDelta<E>) = applyRemote(value)
        })
        // late-join catch-up (G-22) — and replica initial sync / anti-entropy
        // (M7.4): full tag state as one delta-from-empty, tombstones included,
        // to just the new subscriber; idempotence makes replays harmless
        outlet.catchUpOnLinked {
            if (adds.isEmpty() && dels.isEmpty()) null
            else SetDelta(
                adds = adds.mapValues { it.value.toSet() },
                dels = dels.mapValues { it.value.toSet() },
            )
        }
        // on-demand pull (spec 20/21 §Pull, G-18 residual, decided in 93
        // I-16/I-24): a single-wave state-as-delta reply, stamped as a catch-
        // up baseline (MessageContext.baseline) and delivered only to the
        // requester — never broadcast, never admitted to wave completeness.
        ProtocolSupport.of(outlet).handle(Protocols.StateRequest) { _, message ->
            val request = message as StateRequest
            // scope filter (PN-3c): restrict the reply to the requester's
            // interest slice. scope absent/Total ⇒ the maps and the reported
            // frontier are the pre-scope values, so the reply is verbatim.
            val addsOut = scopedTo(sinceFilter(adds, request.since), request.scope)
            val delsOut = scopedTo(sinceFilter(dels, request.since), request.scope)
            if (addsOut.isEmpty() && delsOut.isEmpty()) return@handle
            outlet.baselineTo(request.replyTo, currentFrontier(request.scope)) {
                propagate(SetDelta(addsOut, delsOut))
            }
        }
    }

    // snapshot/restore (G-25 seam): elements must be Serializable. The tag
    // counter is state too (M10.2): a checkpoint-restored instance must not
    // re-mint tags it already used — journal-tail replay continues the count.
    override fun snapshot(): Serializable =
        HashMap(
            mapOf(
                "adds" to HashMap(adds.mapValues { HashSet(it.value) }),
                "dels" to HashMap(dels.mapValues { HashSet(it.value) }),
                "counter" to tagCounter,
            )
        )

    @Suppress("UNCHECKED_CAST")
    override fun restore(state: Serializable) {
        val maps = state as Map<String, Any>
        adds.clear()
        dels.clear()
        (maps.getValue("adds") as Map<E, Set<Timestamp>>).forEach { (e, tags) -> adds[e] = tags.toMutableSet() }
        (maps.getValue("dels") as Map<E, Set<Timestamp>>).forEach { (e, tags) -> dels[e] = tags.toMutableSet() }
        tagCounter = maps["counter"] as? Long ?: 0L
    }

    companion object {
        fun <E> create(): SetApi<E> = SetCell()
    }
}
