package civictech.cell.data

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.MessageContext
import civictech.cell.Propagate
import civictech.cell.Stateful
import civictech.cell.Timestamp
import civictech.cell.port.EdgeClose
import civictech.cell.port.EdgeOpen
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.Link
import civictech.cell.port.ProtocolSupport
import civictech.cell.port.Protocols
import civictech.cell.port.Serve
import civictech.cell.port.Subscribe
import civictech.cell.port.registerPort
import java.io.Serializable
import java.util.*
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.delta.TagState

/**
 * Per-source-link membership for a dynamic `SetDelta<E>` fan-in (the shared core
 * of [PresenceCountCell] and [QuorumSetCell]). Where [UnionSetCell] folds *all*
 * sources into one [TagState], this keeps **one [TagState] per open source link**
 * so the presence count — the number of distinct live source links asserting an
 * element — is exact.
 *
 * Link identity is obtained exactly as [civictech.cell.consistency.GlitchFreeCell]
 * obtains it (spec 12, 20/22):
 *  - **EdgeOpen/EdgeClose** arrive on the inlet's `TopologyOrder` protocol
 *    sub-channel, so the lane set tracks the live-link frontier ([liveSources]).
 *  - a reactive delta carries [MessageContext.sourcePort] — the immediate
 *    upstream outlet ref, equal to the lane's [Link.from] — so it is attributed
 *    to its originating lane.
 *  - the producer's `onLinked` catch-up push (spec 21, G-22) arrives *context-
 *    less* (via `outlet.at(..)`, no wave stamp), which happens only for the link
 *    whose [EdgeOpen] this handler just recorded; [openingLink] attributes it.
 */
internal class PresenceLanes<E> {
    /** Open source links, keyed by [Link.id] (the lane key). */
    private val edges = LinkedHashMap<UUID, Link>()

    /** Per-link live membership; one [TagState] per open source link. */
    private val lanes = LinkedHashMap<UUID, TagState<E>>()

    /**
     * The link whose context-less `onLinked` catch-up push is currently in
     * flight. Set on [EdgeOpen] (which the handshake delivers immediately before
     * the producer's push, on the same thread) and consumed by the sole
     * context-less delivery a fan-in ever sees.
     */
    private var openingLink: Link? = null

    /** Count of live source links — the open-edge frontier a threshold reads as `n`. */
    val liveSources: Int get() = lanes.size

    /** Every element asserted by at least one live lane. */
    fun elements(): Set<E> = lanes.values.flatMapTo(LinkedHashSet()) { it.elements }

    /** Number of distinct live lanes currently asserting [element]. */
    fun count(element: E): Int = lanes.values.count { element in it }

    /** Union of the live input tags carrying [element] across all lanes. */
    fun tags(element: E): Set<Timestamp> = lanes.values.flatMapTo(mutableSetOf()) { it.tags(element) }

    fun open(link: Link) {
        edges[link.id] = link
        lanes[link.id] = TagState()
        openingLink = link
    }

    /** Drops [link]'s lane; returns the elements it had asserted (their counts now drop by one). */
    fun close(link: Link): Set<E> {
        edges.remove(link.id)
        if (openingLink?.id == link.id) openingLink = null
        return lanes.remove(link.id)?.elements?.toSet() ?: emptySet()
    }

    /**
     * Folds [delta] into the lane it arrived on and returns the elements whose
     * per-lane membership the fold touched — the recompute frontier. An
     * unattributable delivery (no matching lane) folds nowhere and touches
     * nothing, mirroring GlitchFreeCell's drop of a delta with no open edge.
     */
    fun fold(ctx: MessageContext?, delta: SetDelta<E>): Set<E> {
        val lane = laneFor(ctx) ?: return emptySet()
        val effective = lane.apply(delta)
        return effective.adds.keys + effective.dels.keys
    }

    private fun laneFor(ctx: MessageContext?): TagState<E>? {
        val linkId = if (ctx != null) {
            edges.values.singleOrNull { it.from == ctx.sourcePort }?.id
        } else {
            openingLink?.id
        }
        return linkId?.let { lanes[it] }
    }

    fun snapshot(): Serializable = HashMap(lanes.mapValues { it.value.snapshot() })

    @Suppress("UNCHECKED_CAST")
    fun restore(state: Serializable) {
        edges.clear()
        lanes.clear()
        openingLink = null
        (state as Map<UUID, Serializable>).forEach { (id, laneState) ->
            lanes[id] = TagState<E>().apply { restore(laneState) }
        }
    }
}

interface PresenceCountApi<E> {
    /** Fan-in: one link per source, each carrying `SetDelta<E>` under its own tag lane. */
    val inlet: Serve<Propagate<SetDelta<E>>>
    val outlet: Subscribe<Propagate<MapDelta<E, Int>>>
}

/**
 * Presence count over a dynamic `SetDelta<E>` fan-in: emits `MapDelta<E, Int>`
 * carrying, per element, the number of distinct live source links currently
 * asserting it. `put(e, k)` when `e`'s live-source count becomes `k`;
 * `removal(e)` when it drops to 0 (group-death, as [GroupByCell]).
 *
 * **Effective-only** (spec 21): emission is gated on the count actually changing,
 * so tag churn that leaves a lane's membership — and thus the count — unchanged
 * emits nothing; and opening an empty source link (which no element's count
 * depends on) recomputes nothing.
 *
 * The stored [counts] is a redundant last-emitted cache kept in lock-step with
 * [lanes]; it is *rebuilt from the lanes* on [restore], so snapshot/restore
 * round-trips per-link state alone and the count map falls out of it.
 */
class PresenceCountCell<E>(override val ref: CellRef = CellRef(UUID.randomUUID())) :
    PresenceCountApi<E>, Cell, Stateful {
    override val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<E>>>())
    override val outlet = registerPort("outlet", FanOutlet.create<Propagate<MapDelta<E, Int>>>())

    private val lanes = PresenceLanes<E>()
    private val counts = mutableMapOf<E, Int>()

    init {
        ProtocolSupport.of(inlet).handle(Protocols.TopologyOrder) { link, event ->
            when (event) {
                EdgeOpen -> lanes.open(link) // empty lane: no element's count moves
                EdgeClose -> recompute(lanes.close(link))
                else -> {}
            }
        }
        inlet.serve(object : Propagate<SetDelta<E>> {
            override fun propagate(value: SetDelta<E>) {
                recompute(lanes.fold(CurrentContext.get(), value))
            }
        })
        // late-join catch-up (G-22): current counts as a delta-from-empty
        outlet.linking.onLinked = { link ->
            if (counts.isNotEmpty()) outlet.at(link.to).propagate(MapDelta(counts.toMap(), emptySet()))
        }
    }

    private fun recompute(candidates: Collection<E>) {
        if (candidates.isEmpty()) return
        val puts = mutableMapOf<E, Int>()
        val removals = mutableSetOf<E>()
        candidates.forEach { element ->
            val now = lanes.count(element)
            val old = counts[element] ?: 0
            if (now != old) {
                if (now == 0) {
                    counts.remove(element)
                    removals += element
                } else {
                    counts[element] = now
                    puts[element] = now
                }
            }
        }
        if (puts.isNotEmpty() || removals.isNotEmpty()) outlet.call.propagate(MapDelta(puts, removals))
    }

    override fun snapshot(): Serializable = lanes.snapshot()

    override fun restore(state: Serializable) {
        lanes.restore(state)
        counts.clear()
        lanes.elements().forEach { counts[it] = lanes.count(it) }
    }

    companion object {
        fun <E> create(): PresenceCountApi<E> = PresenceCountCell()
    }
}
