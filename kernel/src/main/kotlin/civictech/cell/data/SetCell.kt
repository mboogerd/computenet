package civictech.cell.data

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Stateful
import civictech.cell.TagFrontier
import civictech.cell.Timestamp
import civictech.cell.port.*
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
) : Serializable, MergeablePayload {
    fun merge(other: SetDelta<E>): SetDelta<E> =
        SetDelta(mergeTags(adds, other.adds), mergeTags(dels, other.dels))

    @Suppress("UNCHECKED_CAST")
    override fun mergeWith(other: MergeablePayload): MergeablePayload = merge(other as SetDelta<E>)

    companion object {
        private fun <E> mergeTags(
            a: Map<E, Set<Timestamp>>,
            b: Map<E, Set<Timestamp>>,
        ): Map<E, Set<Timestamp>> =
            (a.keys + b.keys).associateWith { (a[it] ?: emptySet()) + (b[it] ?: emptySet()) }
    }
}

interface SetApi<E> {
    val inlet: Use<SetOps<E>>
    val outlet: Subscribe<Propagate<SetDelta<E>>>
}

class SetCell<E>(override val ref: CellRef = CellRef(UUID.randomUUID())) :
    SetApi<E>, Cell, Stateful, Replicable<SetDelta<E>> {
    override val inlet = registerPort("inlet", FanInlet.create<SetOps<E>>())
    override val outlet = registerPort("outlet", FanOutlet.create<Propagate<SetDelta<E>>>())

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

    private fun liveTags(element: E): Set<Timestamp> =
        (adds[element] ?: emptySet<Timestamp>()) - (dels[element] ?: emptySet())

    /** Current membership: elements with at least one un-tombstoned add-tag. */
    fun membership(): Set<E> = adds.keys.filterTo(mutableSetOf()) { liveTags(it).isNotEmpty() }

    private val inletApi = object : SetOps<E> {
        override fun add(element: E) {
            val tag = Timestamp(tagSource, ++tagCounter)
            adds.getOrPut(element) { mutableSetOf() } += tag
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
        outlet.originate { propagate(SetDelta(newAdds, newDels)) }
    }

    /** Highest tag counter observed per tag source (spec 20/21 §Pull, 93 I-24). */
    private fun currentFrontier(): TagFrontier {
        val frontier = mutableMapOf<UUID, Long>()
        (adds.values.asSequence() + dels.values.asSequence()).flatten().forEach { tag ->
            frontier.merge(tag.sourceId, tag.counter, ::maxOf)
        }
        return TagFrontier(frontier)
    }

    /** Only the tags a [since] frontier has not yet observed; unfiltered when [since] is null. */
    private fun sinceFilter(source: Map<E, MutableSet<Timestamp>>, since: TagFrontier?): Map<E, Set<Timestamp>> =
        source.mapValues { (_, tags) ->
            if (since == null) tags.toSet()
            else tags.filterTo(mutableSetOf()) { (since.perSource[it.sourceId] ?: -1L) < it.counter }
        }.filterValues { it.isNotEmpty() }

    init {
        inlet.serve(inletApi)
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
            val addsOut = sinceFilter(adds, request.since)
            val delsOut = sinceFilter(dels, request.since)
            if (addsOut.isEmpty() && delsOut.isEmpty()) return@handle
            outlet.baselineTo(request.replyTo, currentFrontier()) {
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
