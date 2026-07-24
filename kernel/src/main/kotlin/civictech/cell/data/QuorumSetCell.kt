package civictech.cell.data

import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.Stateful
import civictech.cell.Timestamp
import civictech.cell.port.EdgeClose
import civictech.cell.port.EdgeOpen
import civictech.cell.port.ProtocolSupport
import civictech.cell.port.Protocols
import civictech.cell.port.Serve
import civictech.cell.port.Subscribe
import civictech.gen.wire.CellBase
import java.io.Serializable
import java.util.*

@CellBase
interface QuorumSetApi<E> {
    /** Fan-in: one link per source, each carrying `SetDelta<E>` under its own tag lane. */
    val inlet: Serve<Propagate<SetDelta<E>>>
    val outlet: Subscribe<Propagate<SetDelta<E>>>
}

/**
 * Quorum over a dynamic `SetDelta<E>` fan-in: emits `SetDelta<E>` of the elements
 * whose presence count (distinct live source links asserting them, see
 * [PresenceLanes]) meets [threshold]. The threshold receives the current
 * **live-source count `n`** (the open-edge frontier), so the whole family of
 * quorum views is one lambda:
 *
 * | view | `threshold` |
 * |---|---|
 * | union (any)          | `{ 1 }`       |
 * | intersection (all)   | `{ n -> n }`  |
 * | majority             | `{ n -> n / 2 + 1 }` |
 * | k-of-n quorum        | `{ k }`       |
 * | near-miss (all-but-one) | `{ n -> n - 1 }` |
 *
 * Output tag discipline is [IntersectSetCell]'s: on entry an element is
 * advertised downstream with its live input tags; on exit *exactly those*
 * advertised tags are deleted, so a downstream [SetView]/[UnionSetCell] tracks
 * membership precisely and tag churn while membership holds is absorbed
 * (effective-only, spec 21). Because the threshold reads `n`, a link
 * opening/closing re-evaluates the quorum even for elements whose own count did
 * not move (e.g. an empty source joining tightens an intersection).
 */
class QuorumSetCell<E>(
    ref: CellRef = CellRef(UUID.randomUUID()),
    private val threshold: (liveSources: Int) -> Int,
) : QuorumSetCellBase<E>(ref), Stateful {
    private val lanes = PresenceLanes<E>()

    /** Elements currently advertised downstream, each with the exact tags advertised on entry. */
    private val advertised = mutableMapOf<E, Set<Timestamp>>()

    init {
        ProtocolSupport.of(inlet).handle(Protocols.TopologyOrder) { link, event ->
            when (event) {
                // n changed → the threshold shifted; re-evaluate the whole
                // working set, not just an incoming delta's elements.
                EdgeOpen -> {
                    lanes.open(link)
                    evaluate(lanes.elements() + advertised.keys)
                }
                EdgeClose -> {
                    val orphaned = lanes.close(link)
                    evaluate(lanes.elements() + advertised.keys + orphaned)
                }
                else -> {}
            }
        }
        // late-join catch-up (G-22): the advertised quorum as a delta-from-empty
        outlet.linking.onLinked = { link ->
            if (advertised.isNotEmpty()) outlet.at(link.to).propagate(SetDelta(adds = advertised.toMap()))
        }
    }

    override fun onInlet(value: SetDelta<E>) {
        evaluate(lanes.fold(CurrentContext.get(), value))
    }

    private fun evaluate(candidates: Collection<E>) {
        if (candidates.isEmpty()) return
        val target = threshold(lanes.liveSources)
        val adds = mutableMapOf<E, Set<Timestamp>>()
        val dels = mutableMapOf<E, Set<Timestamp>>()
        candidates.toSet().forEach { element ->
            val count = lanes.count(element)
            // an absent element (count 0) is never in the quorum, even if the
            // threshold is non-positive (near-miss with a single source).
            val meets = count >= 1 && count >= target
            val was = element in advertised
            if (meets && !was) {
                val tags = lanes.tags(element)
                advertised[element] = tags
                adds[element] = tags
            } else if (!meets && was) {
                dels[element] = advertised.remove(element)!!
            }
        }
        if (adds.isNotEmpty() || dels.isNotEmpty()) outlet.call.propagate(SetDelta(adds, dels))
    }

    override fun snapshot(): Serializable = arrayListOf(lanes.snapshot(), HashMap(advertised))

    @Suppress("UNCHECKED_CAST")
    override fun restore(state: Serializable) {
        val (laneState, adv) = state as ArrayList<Serializable>
        lanes.restore(laneState)
        advertised.clear()
        advertised.putAll(adv as Map<E, Set<Timestamp>>)
    }

    companion object {
        fun <E> create(threshold: (Int) -> Int): QuorumSetApi<E> = QuorumSetCell(threshold = threshold)

        /** Every live source must assert the element — generalises a chained [IntersectSetCell]. */
        fun <E> intersection(ref: CellRef = CellRef(UUID.randomUUID())): QuorumSetCell<E> =
            QuorumSetCell(ref) { n -> n }

        /** Any live source suffices — matches [UnionSetCell] membership. */
        fun <E> union(ref: CellRef = CellRef(UUID.randomUUID())): QuorumSetCell<E> =
            QuorumSetCell(ref) { 1 }

        /** A strict majority of live sources: `n / 2 + 1`. */
        fun <E> majority(ref: CellRef = CellRef(UUID.randomUUID())): QuorumSetCell<E> =
            QuorumSetCell(ref) { n -> n / 2 + 1 }

        /** All-but-one of the live sources: `n - 1` (the near-miss view). */
        fun <E> nearMiss(ref: CellRef = CellRef(UUID.randomUUID())): QuorumSetCell<E> =
            QuorumSetCell(ref) { n -> n - 1 }

        /** A fixed quorum of [k] live sources, independent of `n` (k-of-n). */
        fun <E> kOfN(k: Int, ref: CellRef = CellRef(UUID.randomUUID())): QuorumSetCell<E> =
            QuorumSetCell(ref) { k }
    }
}
