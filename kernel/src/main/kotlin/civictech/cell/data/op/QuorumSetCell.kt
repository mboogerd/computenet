package civictech.cell.data.op

import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.Propagate
import civictech.cell.Stateful
import civictech.cell.Timestamp
import civictech.cell.protocol.EdgeClose
import civictech.cell.protocol.EdgeOpen
import civictech.cell.protocol.ProtocolSupport
import civictech.cell.protocol.Protocols
import civictech.cell.port.Serve
import civictech.cell.port.Subscribe
import civictech.cell.link.catchUpOnLinked
import civictech.cell.control.absorbAck
import civictech.gen.wire.CellBase
import java.io.Serializable
import java.util.*
import civictech.cell.data.delta.SetDelta

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
 * Output tag discipline is [IntersectSetCell]'s — and, since T07 finding 3,
 * literally shared with it via [AdvertisedLedger]: on entry an element is
 * advertised downstream with its live input tags; on exit *exactly those*
 * advertised tags are deleted, so a downstream `SetView`/[UnionSetCell] tracks
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

    /** Elements currently advertised downstream, each with the exact tags advertised on entry (RS-5.3, T07 finding 3: shared with [IntersectSetCell]). */
    private val ledger: JoinLedger<E> = AdvertisedLedger()

    init {
        ProtocolSupport.of(inlet).handle(Protocols.TopologyOrder) { link, event ->
            when (event) {
                // n changed → the threshold shifted; re-evaluate the whole
                // working set, not just an incoming delta's elements.
                EdgeOpen -> {
                    lanes.open(link)
                    evaluate(lanes.elements() + ledger.entries.keys)
                }
                EdgeClose -> {
                    val orphaned = lanes.close(link)
                    evaluate(lanes.elements() + ledger.entries.keys + orphaned)
                }
                else -> {}
            }
        }
        // late-join catch-up (G-22): the advertised quorum as a delta-from-empty
        outlet.catchUpOnLinked { if (ledger.isEmpty) null else ledger.asDelta() }
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
            if (meets) {
                ledger.enter(element) { lanes.tags(element) }?.let { adds[element] = it }
            } else {
                ledger.exit(element)?.let { dels[element] = it }
            }
        }
        // T05 finding 2: a re-evaluation that changes no membership (tag
        // churn, or a link open/close that doesn't tip any element's count
        // across the threshold) now absorb-acks the reactive wave instead of
        // silently dropping it — a GlitchFreeCell downstream would otherwise
        // stall forever on such a wave. Behavior change: this operator now
        // acks. (No-op via absorbAck's own CurrentContext guard when
        // `evaluate` runs from the EdgeOpen/EdgeClose protocol path, which
        // carries no reactive wave context.)
        emitOrAbsorb(
            adds.isEmpty() && dels.isEmpty(),
            emit = { outlet.call.propagate(SetDelta(adds, dels)) },
            absorbAck = { outlet.absorbAck() },
        )
    }

    override fun snapshot(): Serializable = arrayListOf(lanes.snapshot(), ledger.snapshot())

    @Suppress("UNCHECKED_CAST")
    override fun restore(state: Serializable) {
        val (laneState, ledgerState) = state as ArrayList<Serializable>
        lanes.restore(laneState)
        ledger.restore(ledgerState)
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
